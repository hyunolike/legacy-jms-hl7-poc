package com.example.hl7poc.ws;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;

import javax.xml.ws.Endpoint;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HapiContext;

import com.example.hl7poc.common.dto.AdtEvent;
import com.example.hl7poc.common.exception.PermanentProcessingException;
import com.example.hl7poc.common.exception.TransientProcessingException;
import com.example.hl7poc.common.hl7.HapiHl7Parser;
import com.example.hl7poc.common.hl7.Hl7Samples;
import com.example.hl7poc.ws.client.HospitalBSoapClient;
import com.example.hl7poc.ws.mock.HospitalBAdmissionEndpoint;
import com.example.hl7poc.ws.mock.HospitalBAdmissionEndpoint.Behavior;

/**
 * 병원 B SOAP 연동 검증. mock 서버를 같은 JVM 안에 띄워 실제로 왕복시킨다.
 *
 * <p>여기서 정작 확인해야 하는 것은 정상 경로가 아니라 <b>실패 경로</b>다.
 * 실제 상대 시스템에는 "타임아웃을 내 달라"고 부탁할 수 없다.
 *
 * <p>모든 환자 정보는 가상의 합성 데이터다.
 */
public class HospitalBSoapClientTest {

    private static Endpoint endpoint;
    private static HospitalBAdmissionEndpoint impl;
    private static String address;

    private static HapiContext hapiContext;
    private static HapiHl7Parser parser;

    @BeforeClass
    public static void startServer() throws Exception {
        int port = freePort();
        address = "http://localhost:" + port + "/hospital-b/admission";

        impl = new HospitalBAdmissionEndpoint();
        endpoint = Endpoint.publish(address, impl);

        hapiContext = new DefaultHapiContext();
        parser = new HapiHl7Parser(hapiContext);
        parser.setValidationEnabled(true);
    }

    @AfterClass
    public static void stopServer() throws Exception {
        if (endpoint != null) {
            endpoint.stop();
        }
        if (hapiContext != null) {
            hapiContext.close();
        }
    }

    @After
    public void resetMock() {
        impl.reset();
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static HospitalBSoapClient client(String url, int connectMs, int readMs) {
        HospitalBSoapClient c = new HospitalBSoapClient();
        c.setEndpointUrl(url);
        c.setConnectTimeoutMs(connectMs);
        c.setReadTimeoutMs(readMs);
        c.afterPropertiesSet();
        return c;
    }

    private static HospitalBSoapClient client() {
        return client(address, 2000, 5000);
    }

    private static AdtEvent event(String sampleFile) {
        return parser.toAdtEvent(parser.parseHeader(Hl7Samples.loadAsCr(sampleFile)));
    }

    // ------------------------------------------------------------------
    // 정상 경로
    // ------------------------------------------------------------------

    @Test
    public void 입원_통보가_병원B_에_도달한다() {
        client().notifyAdtEvent(event("adt_a01_admit.hl7"));

        List<String> received = impl.receivedControlIds();
        assertEquals(1, received.size());
        assertEquals("MSG00000001", received.get(0));
    }

    @Test
    public void 퇴원_통보도_도달한다() {
        client().notifyAdtEvent(event("adt_a03_discharge.hl7"));
        assertEquals("MSG00000002", impl.receivedControlIds().get(0));
    }

    @Test
    public void 환자_정보가_왕복에서_깨지지_않는다() {
        // SOAP 는 XML 이고 인코딩 사고가 나기 쉬운 구간이다. 특히 한글은
        // 로컬에서 되던 것이 상대 환경에서 깨지는 일이 흔하다. 실제로 왕복시켜
        // 확인하는 것 말고는 방법이 없다.
        client().notifyAdtEvent(event("adt_a01_admit.hl7"));

        com.example.hl7poc.ws.dto.AdmissionNotice got = impl.lastNotice();
        assertEquals("홍길동", got.getPatientName());
        assertEquals("PAT000001", got.getPatientId());
        assertEquals("19850214", got.getBirthDate());
        assertEquals("M", got.getSex());
        assertEquals("HOSP_A", got.getSendingFacility());
        assertEquals("A01", got.getTriggerEvent());
        assertEquals("I", got.getPatientClass());
        assertEquals("WARD5 501 A", got.getAssignedLocation());
        assertEquals("20260301093000", got.getAdmitDateTime());
        assertNull("입원 통보에는 퇴원 시각이 없다", got.getDischargeDateTime());
    }

    @Test
    public void 퇴원_통보에는_퇴원_시각이_실린다() {
        client().notifyAdtEvent(event("adt_a03_discharge.hl7"));
        com.example.hl7poc.ws.dto.AdmissionNotice got = impl.lastNotice();
        assertEquals("A03", got.getTriggerEvent());
        assertEquals("20260305141500", got.getDischargeDateTime());
    }

    // ------------------------------------------------------------------
    // 실패 분류
    // ------------------------------------------------------------------

    @Test
    public void 상대의_업무적_거절은_재시도하지_않는_오류다() {
        impl.setBehavior(Behavior.REJECT);
        try {
            client().notifyAdtEvent(event("adt_a01_admit.hl7"));
            fail("PermanentProcessingException 이 나야 한다");
        } catch (PermanentProcessingException expected) {
            assertEquals("WS-REJECTED", expected.getErrorCode());
            assertFalse("재시도해도 같은 답이 온다", expected.isRetryable());
            assertTrue("사유가 전달된다", expected.getMessage().contains("등록되지 않은 환자"));
        }
    }

    @Test
    public void 상대의_내부_오류는_재시도_대상이다() {
        impl.setBehavior(Behavior.FAULT);
        try {
            client().notifyAdtEvent(event("adt_a01_admit.hl7"));
            fail("TransientProcessingException 이 나야 한다");
        } catch (TransientProcessingException expected) {
            assertEquals("WS-FAULT", expected.getErrorCode());
            assertTrue(expected.isRetryable());
        }
    }

    @Test
    public void 서버가_없으면_재시도_대상이다() throws Exception {
        // 아무도 듣지 않는 포트로 보낸다.
        String dead = "http://localhost:" + freePort() + "/hospital-b/admission";
        try {
            client(dead, 2000, 5000).notifyAdtEvent(event("adt_a01_admit.hl7"));
            fail("TransientProcessingException 이 나야 한다");
        } catch (TransientProcessingException expected) {
            assertEquals("WS-TRANSPORT", expected.getErrorCode());
            assertTrue(expected.isRetryable());
        }
    }

    /**
     * 타임아웃이 실제로 걸리는지가 핵심이다.
     *
     * <p>이 SOAP 호출은 DB 트랜잭션 안에서 일어난다. 타임아웃이 동작하지 않으면
     * 커넥션을 쥔 채 멈춘 컨슈머가 쌓여 커넥션 풀이 통째로 마른다. 연동 장애 하나가
     * 브리지 전체를 세우는 경로다.
     *
     * <p>JAX-WS 타임아웃 키는 표준이 아니라 구현체마다 다르고, 틀린 키는 조용히
     * 무시된다. "설정했으니 되겠지"가 통하지 않는 자리라 실측으로 고정한다.
     */
    @Test
    public void 읽기_타임아웃이_실제로_걸린다() {
        impl.setBehavior(Behavior.SLOW);
        impl.setSlowMillis(10_000L);

        final int readTimeoutMs = 1000;
        long startedAt = System.currentTimeMillis();
        try {
            client(address, 2000, readTimeoutMs).notifyAdtEvent(event("adt_a01_admit.hl7"));
            fail("타임아웃이 나야 한다");
        } catch (TransientProcessingException expected) {
            assertEquals("WS-TRANSPORT", expected.getErrorCode());
        }
        long elapsed = System.currentTimeMillis() - startedAt;

        assertTrue("타임아웃(" + readTimeoutMs + "ms)보다 훨씬 오래 걸렸습니다: " + elapsed
                        + "ms. 타임아웃 프로퍼티 키가 무시된 것일 수 있습니다.",
                elapsed < 5000);
        assertTrue("너무 빨리 끊겼습니다: " + elapsed + "ms", elapsed >= readTimeoutMs - 200);
    }

    // ------------------------------------------------------------------
    // 설정 검증
    // ------------------------------------------------------------------

    @Test
    public void 타임아웃이_0이면_기동_시점에_막는다() {
        // 0 은 무한 대기다. 커넥션 풀을 말려 죽이는 설정이므로 뜨지 못하게 한다.
        assertConfigRejected(address, 0, 5000);
        assertConfigRejected(address, 3000, 0);
    }

    @Test
    public void 엔드포인트가_없거나_잘못되면_기동_시점에_막는다() {
        assertConfigRejected(null, 3000, 5000);
        assertConfigRejected("", 3000, 5000);
        assertConfigRejected("이건 URL 이 아니다", 3000, 5000);
    }

    private static void assertConfigRejected(String url, int connectMs, int readMs) {
        try {
            client(url, connectMs, readMs);
            fail("거부되어야 합니다: url=" + url + ", connect=" + connectMs + ", read=" + readMs);
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage() != null);
        }
    }

    // ------------------------------------------------------------------
    // 동시성
    // ------------------------------------------------------------------

    /**
     * 컨슈머가 최대 10개까지 늘어나는 구조라, 클라이언트 빈 하나를 여러 스레드가
     * 동시에 쓴다. JAX-WS 포트 프록시는 스레드 세이프하지 않으므로 포트를 필드로
     * 들고 있으면 여기서 깨진다. 호출마다 포트를 만드는 설계가 맞는지 확인한다.
     */
    @Test
    public void 여러_스레드가_동시에_호출해도_안전하다() throws Exception {
        final HospitalBSoapClient shared = client();
        final int threads = 10;
        final int callsPerThread = 5;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(new Callable<Integer>() {
                    @Override
                    public Integer call() {
                        for (int i = 0; i < callsPerThread; i++) {
                            shared.notifyAdtEvent(event("adt_a01_admit.hl7"));
                        }
                        return callsPerThread;
                    }
                }));
            }
            int total = 0;
            for (Future<Integer> f : futures) {
                total += f.get();   // 예외가 났으면 여기서 터진다
            }
            assertEquals(threads * callsPerThread, total);
            assertEquals("서버도 같은 수만큼 받아야 한다",
                    threads * callsPerThread, impl.receivedControlIds().size());
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // Spring 배선
    // ------------------------------------------------------------------

    /**
     * 운영 XML(app-context-ws.xml)이 그대로 로딩되고, 주입된 빈이 실제로 동작하는지
     * 본다. 단위 테스트에서 클라이언트를 손으로 만들어 쓰면, 정작 XML 의 프로퍼티
     * 이름이 틀려도 통과한다.
     */
    @Test
    public void 운영_XML_로_주입된_클라이언트가_동작한다() {
        System.setProperty("HL7POC_HOSPITAL_B_ENDPOINT", address);
        org.springframework.context.support.ClassPathXmlApplicationContext ctx = null;
        try {
            ctx = new org.springframework.context.support.ClassPathXmlApplicationContext(
                    "classpath:spring/test-ws-context.xml");

            com.example.hl7poc.ws.client.HospitalBNotifier notifier =
                    ctx.getBean("hospitalBNotifier",
                            com.example.hl7poc.ws.client.HospitalBNotifier.class);
            assertTrue(notifier instanceof HospitalBSoapClient);

            notifier.notifyAdtEvent(event("adt_a01_admit.hl7"));
            assertEquals("MSG00000001", impl.lastNotice().getMessageControlId());
        } finally {
            if (ctx != null) {
                ctx.close();
            }
            System.clearProperty("HL7POC_HOSPITAL_B_ENDPOINT");
        }
    }

    // ------------------------------------------------------------------
    // PHI 보호
    // ------------------------------------------------------------------

    @Test
    public void 통보_객체의_toString_은_마스킹된다() {
        // SOAP 요청/응답을 로그로 남기는 습관이 흔한데, 그 로그가 평문 PHI
        // 저장소가 되어 버린다.
        com.example.hl7poc.ws.dto.AdmissionNotice n =
                new com.example.hl7poc.ws.dto.AdmissionNotice();
        n.setPatientId("PAT000001");
        n.setPatientName("홍길동");
        n.setBirthDate("19850214");

        String s = n.toString();
        assertFalse(s.contains("PAT000001"));
        assertFalse(s.contains("홍길동"));
        assertFalse(s.contains("19850214"));
    }
}
