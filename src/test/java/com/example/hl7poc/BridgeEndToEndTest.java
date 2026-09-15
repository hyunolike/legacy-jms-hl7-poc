package com.example.hl7poc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;

import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.Queue;
import javax.jms.TextMessage;
import javax.xml.ws.Endpoint;

import org.junit.After;
import org.junit.Before;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.jms.core.MessagePostProcessor;

import com.example.hl7poc.common.hl7.Hl7Samples;
import com.example.hl7poc.common.util.Base64Utils;
import com.example.hl7poc.jms.support.JmsHeaders;
import com.example.hl7poc.ws.mock.HospitalBAdmissionEndpoint;
import com.example.hl7poc.ws.mock.HospitalBAdmissionEndpoint.Behavior;

/**
 * 종단 간 검증. <b>운영 컨텍스트({@code app-context.xml})를 통째로 띄운다.</b>
 *
 * <p>여기까지 와서야 전체가 한 번에 뜬다. 1~6단계는 계층별로 나눠 검증했는데,
 * 각 조각이 맞아도 합쳐 놓으면 빈 이름 하나, 프로퍼티 하나가 어긋나 있는 경우가
 * 흔하다. 그건 조각 테스트로는 절대 드러나지 않는다.
 *
 * <p>ActiveMQ 와 PostgreSQL 이 떠 있어야 한다({@code ant docker-up}).
 * 병원 B 는 같은 JVM 안에 mock 으로 띄운다.
 *
 * <p>모든 환자 정보는 가상의 합성 데이터다.
 */
public class BridgeEndToEndTest {

    private static final long ACK_WAIT_MS = 20_000L;

    /**
     * 재전송 간격을 짧게 줄여 쓴다(200ms → 400ms → 800ms).
     *
     * <p>기본값 2s/4s/8s 로 두면 DLQ 한 건을 확인하는 데 14초가 걸리고, 그 사이
     * 재시도 중인 메시지가 다음 테스트로 새어 나간다. 간격 자체는
     * {@code SpringWiringTest} 가 기본 설정으로 이미 검증하므로, 여기서는 흐름만
     * 본다.
     */
    private static final long DLQ_WAIT_MS = 20_000L;

    private static ClassPathXmlApplicationContext context;
    private static JdbcTemplate jdbcTemplate;
    private static JmsTemplate probe;
    private static Endpoint hospitalB;
    private static HospitalBAdmissionEndpoint hospitalBImpl;

    private static Queue requestQueue;
    private static Queue ackQueue;
    private static Queue parkQueue;
    private static Queue dlqQueue;

    @BeforeClass
    public static void startAll() throws Exception {
        assumeTrue("ActiveMQ 가 떠 있지 않아 건너뜁니다 (ant docker-up)", portOpen(61616));
        assumeTrue("PostgreSQL 이 떠 있지 않아 건너뜁니다 (ant docker-up)", portOpen(5432));

        // 병원 B mock 을 먼저 띄운다. 클라이언트 빈이 기동 시 엔드포인트를 검증한다.
        int port = freePort();
        String address = "http://localhost:" + port + "/hospital-b/admission";
        hospitalBImpl = new HospitalBAdmissionEndpoint();
        hospitalB = Endpoint.publish(address, hospitalBImpl);

        // 재시도를 짧게. 브리지 코드가 아니라 설정으로 조정한다(운영에서도 같은 방식).
        System.setProperty("HL7POC_REDELIVERY_INITIAL_MS", "200");
        System.setProperty("HL7POC_REDELIVERY_DELAY_MS", "200");
        System.setProperty("HL7POC_REDELIVERY_MAX_DELAY_MS", "2000");

        // 키와 엔드포인트는 바깥에서 주입한다 — 운영과 같은 경로.
        System.setProperty("HL7POC_PHI_KEY", randomKey());
        System.setProperty("HL7POC_BLIND_INDEX_KEY", randomKey());
        System.setProperty("HL7POC_HOSPITAL_B_ENDPOINT", address);

        context = new ClassPathXmlApplicationContext("classpath:spring/app-context.xml");

        jdbcTemplate = context.getBean("jdbcTemplate", JdbcTemplate.class);
        requestQueue = context.getBean("adtRequestQueue", Queue.class);
        ackQueue = context.getBean("adtAckQueue", Queue.class);
        parkQueue = context.getBean("adtParkQueue", Queue.class);
        dlqQueue = context.getBean("adtDlqQueue", Queue.class);

        // 컨슈머 캐싱을 끈 전용 팩토리를 쓴다.
        //
        // 이 PoC 는 같은 환자의 메시지를 JMSXGroupID 로 묶어 순서를 보장한다.
        // ActiveMQ 는 메시지 그룹을 한 컨슈머에 고정(pin)하는데, 컨슈머를 캐싱하면
        // 그 컨슈머가 그룹 소유권을 계속 쥔다. 그러면 다른 컨슈머는 browse 로
        // 메시지를 "보면서도" receive 로는 가져오지 못한다. 실제로 이 테스트에서
        // 겪었다 — awaitDepth 는 통과하는데 바로 다음 receive 가 20초를 기다리다
        // 실패했다.
        //
        // cacheConsumers=false 만으로도 부족했다(여전히 간헐적으로 못 받았다).
        // 원본 ConnectionFactory 를 그대로 쓴다. 매 호출 커넥션까지 새로 맺어
        // 느리지만, 테스트 프로브에는 정확성이 우선이다.
        probe = new JmsTemplate(context.getBean("jmsConnectionFactory",
                javax.jms.ConnectionFactory.class));
        probe.setReceiveTimeout(300L);
    }

    @AfterClass
    public static void stopAll() {
        if (context != null) {
            context.close();
        }
        if (hospitalB != null) {
            hospitalB.stop();
        }
        System.clearProperty("HL7POC_PHI_KEY");
        System.clearProperty("HL7POC_BLIND_INDEX_KEY");
        System.clearProperty("HL7POC_HOSPITAL_B_ENDPOINT");
        // 다른 테스트 클래스가 같은 JVM 에서 돌기 때문에 반드시 되돌린다.
        System.clearProperty("HL7POC_REDELIVERY_INITIAL_MS");
        System.clearProperty("HL7POC_REDELIVERY_DELAY_MS");
        System.clearProperty("HL7POC_REDELIVERY_MAX_DELAY_MS");
    }

    /**
     * 시작 시점에도 정리한다. 앞 테스트의 {@code @After} 가 놓친 늦게 도착한
     * 메시지를 여기서 한 번 더 걷어 낸다.
     */
    @Before
    public void cleanBefore() {
        clean();
    }

    /**
     * 테스트 사이를 확실히 끊는다.
     *
     * <p>단순히 큐를 한 번 비우는 것으로는 부족하다. 재시도 중인 메시지는 브로커에도
     * 큐에도 "지금은" 보이지 않다가 몇 초 뒤에 나타난다. 그대로 다음 테스트로
     * 넘어가면, 앞 테스트가 만든 메시지가 뒤 테스트의 단언을 깨뜨린다.
     * (실제로 겪었다 — 개별 실행은 통과하는데 모아서 돌리면 실패했다.)
     *
     * <p>그래서 리스너를 먼저 <b>멈춘다</b>. 진행 중이던 처리가 롤백되면서 메시지가
     * 큐로 돌아오고, 그때 비로소 전부 비울 수 있다.
     */
    @After
    public void clean() {
        DefaultMessageListenerContainer container = context.getBean(
                "adtRequestListenerContainer", DefaultMessageListenerContainer.class);
        container.stop();
        try {
            drainUntilQuiet(requestQueue);
            drainUntilQuiet(ackQueue);
            drainUntilQuiet(parkQueue);
            drainUntilQuiet(dlqQueue);
            jdbcTemplate.update(
                    "TRUNCATE processed_message, adt_message, processing_log RESTART IDENTITY");
            hospitalBImpl.reset();
        } finally {
            container.start();
        }
    }

    // ------------------------------------------------------------------
    // 1. 정상 경로
    // ------------------------------------------------------------------

    @Test
    public void 입원_메시지가_적재되고_병원B_로_전달되고_AA_가_돌아온다() throws Exception {
        send("adt_a01_admit.hl7", "PAT000001");

        String ack = awaitText(ackQueue, ACK_WAIT_MS);
        assertTrue("AA 응답", ack.contains("MSA|AA|MSG00000001"));

        // DB
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM adt_message WHERE msg_control_id = ?", "MSG00000001");
        assertEquals("FORWARDED", row.get("status"));

        // 병원 B
        assertEquals(1, hospitalBImpl.receivedControlIds().size());
        assertEquals("MSG00000001", hospitalBImpl.lastNotice().getMessageControlId());
        assertEquals("홍길동", hospitalBImpl.lastNotice().getPatientName());

        // 단계 로그로 전 구간이 이어진다
        List<String> steps = steps("MSG00000001");
        assertTrue(steps.containsAll(java.util.Arrays.asList(
                "DEDUP_OK", "ENCRYPTED", "PERSISTED", "FORWARDED", "ACK_SENT")));
    }

    @Test
    public void ACK_에_상관관계_헤더가_붙는다() throws Exception {
        send("adt_a01_admit.hl7", "PAT000001");
        Message ack = await(ackQueue, ACK_WAIT_MS);

        // 본문을 파싱하지 않고도 응답 큐에서 선택 소비(selector)가 가능해야 한다.
        assertEquals("MSG00000001", ack.getStringProperty(JmsHeaders.MSG_CONTROL_ID));
        assertEquals("MSG00000001", ack.getJMSCorrelationID());
    }

    // ------------------------------------------------------------------
    // 2. 재시도 무의미한 오류 → 격리 + ACK
    // ------------------------------------------------------------------

    @Test
    public void MSH_가_깨진_메시지는_격리되고_AR_이_돌아온다() throws Exception {
        send("adt_a01_broken_msh.hl7", null);

        String ack = awaitText(ackQueue, ACK_WAIT_MS);
        assertTrue("AR 응답: " + ack, ack.contains("MSA|AR|"));

        Message parked = await(parkQueue, ACK_WAIT_MS);
        assertEquals("HL7-PARSE", parked.getStringProperty(JmsHeaders.ERROR_CODE));

        // 재시도하지 않았으므로 DLQ 는 비어 있어야 한다.
        assertEquals("재시도 무의미한 오류가 DLQ 로 가면 안 된다", null, receiveText(dlqQueue));
    }

    @Test
    public void 미지원_트리거는_격리되고_AE_가_돌아온다() throws Exception {
        send("adt_a08_unsupported.hl7", "PAT000001");

        String ack = awaitText(ackQueue, ACK_WAIT_MS);
        assertTrue("AE 응답: " + ack, ack.contains("MSA|AE|MSG00000103"));
        assertTrue("미지원 트리거는 201", ack.contains("201^Unsupported event code"));

        Message parked = await(parkQueue, ACK_WAIT_MS);
        assertEquals("HL7-UNSUPPORTED-TRIGGER",
                parked.getStringProperty(JmsHeaders.ERROR_CODE));
        assertEquals("MSG00000103", parked.getStringProperty(JmsHeaders.MSG_CONTROL_ID));
    }

    @Test
    public void 환자_식별자가_없으면_격리되고_AE_가_돌아온다() throws Exception {
        send("adt_a01_missing_pid.hl7", null);

        String ack = awaitText(ackQueue, ACK_WAIT_MS);
        assertTrue("AE 응답: " + ack, ack.contains("MSA|AE|MSG00000101"));
        assertTrue("필수 항목 누락은 101", ack.contains("101^Required field missing"));
    }

    // ------------------------------------------------------------------
    // 3. 멱등성
    // ------------------------------------------------------------------

    @Test
    public void 같은_메시지를_두_번_보내면_ACK_는_두_번_업무는_한_번() throws Exception {
        send("adt_a01_admit.hl7", "PAT000001");
        String first = awaitText(ackQueue, ACK_WAIT_MS);

        send("adt_a01_admit.hl7", "PAT000001");
        String second = awaitText(ackQueue, ACK_WAIT_MS);

        // 상대는 두 번 다 응답을 받아야 한다. 안 그러면 계속 재전송한다.
        assertTrue(first.contains("MSA|AA|MSG00000001"));
        assertTrue(second.contains("MSA|AA|MSG00000001"));

        // 그러나 업무는 한 번만. 병원 B 에 입원 통보가 두 번 가면 상대가 깨진다.
        assertEquals("병원 B 전달은 한 번뿐", 1, hospitalBImpl.receivedControlIds().size());
        assertEquals("적재도 한 행뿐", Integer.valueOf(1), jdbcTemplate.queryForObject(
                "SELECT count(*) FROM adt_message WHERE msg_control_id = ?",
                Integer.class, "MSG00000001"));
        assertTrue(steps("MSG00000001").contains("DEDUP_SKIP"));
    }

    // ------------------------------------------------------------------
    // 4. 일시 오류 → 재시도 → DLQ
    // ------------------------------------------------------------------

    @Test
    public void 병원B_가_계속_실패하면_재시도_후_DLQ_로_간다() throws Exception {
        hospitalBImpl.setBehavior(Behavior.FAULT);
        send("adt_a01_admit.hl7", "PAT000001");

        awaitDepth(dlqQueue, 1, DLQ_WAIT_MS);
        Message dead = await(dlqQueue, ACK_WAIT_MS);
        assertTrue("원문 그대로 DLQ 로 간다",
                ((TextMessage) dead).getText().contains("MSG00000001"));

        // 최초 1회 + 재전송 3회
        assertEquals("병원 B 는 4번 호출된다", 4, hospitalBImpl.receivedControlIds().size());

        // 롤백됐으므로 아무것도 남지 않는다.
        assertEquals(Integer.valueOf(0), jdbcTemplate.queryForObject(
                "SELECT count(*) FROM adt_message", Integer.class));
        assertEquals("일시 오류에는 ACK 를 보내지 않는다", null, receiveText(ackQueue));
    }

    @Test
    public void 상대가_업무적으로_거절하면_재시도하지_않고_격리한다() throws Exception {
        hospitalBImpl.setBehavior(Behavior.REJECT);
        send("adt_a01_admit.hl7", "PAT000001");

        String ack = awaitText(ackQueue, ACK_WAIT_MS);
        assertTrue("AE 응답: " + ack, ack.contains("MSA|AE|MSG00000001"));

        // 다시 보내도 같은 답이 온다. 재시도는 낭비다.
        assertEquals("한 번만 호출된다", 1, hospitalBImpl.receivedControlIds().size());
        Message parked = await(parkQueue, ACK_WAIT_MS);
        assertEquals("WS-REJECTED", parked.getStringProperty(JmsHeaders.ERROR_CODE));

        // 적재까지 갔다가 거절당한 행은 PARKED 로 남아야 한다. PERSISTED 로 남으면
        // "전달 직전에 죽은 메시지"와 구분되지 않는다.
        assertEquals("PARKED", jdbcTemplate.queryForMap(
                "SELECT * FROM adt_message WHERE msg_control_id = ?",
                "MSG00000001").get("status"));
    }

    // ------------------------------------------------------------------
    // 5. DLQ 재처리
    // ------------------------------------------------------------------

    @Test
    public void DLQ_재처리는_원인이_해소되면_정상_처리된다() throws Exception {
        // 병원 B 장애로 DLQ 까지 밀어 넣는다.
        hospitalBImpl.setBehavior(Behavior.FAULT);
        send("adt_a01_admit.hl7", "PAT000001");
        // 확인만 한다. 여기서 꺼내 버리면 재투입할 메시지가 없다.
        awaitDepth(dlqQueue, 1, DLQ_WAIT_MS);

        // 장애가 복구됐다고 치고 재투입한다.
        hospitalBImpl.reset();
        com.example.hl7poc.jms.dlq.DlqReprocessor tool =
                new com.example.hl7poc.jms.dlq.DlqReprocessor(
                        connectionFactory(), requestQueue, dlqQueue, parkQueue);
        int moved = tool.replay(dlqQueue, 10);
        assertEquals("1건 재투입", 1, moved);

        String ack = awaitText(ackQueue, ACK_WAIT_MS);
        assertTrue("이번에는 AA: " + ack, ack.contains("MSA|AA|MSG00000001"));
        assertEquals("FORWARDED", jdbcTemplate.queryForMap(
                "SELECT * FROM adt_message WHERE msg_control_id = ?",
                "MSG00000001").get("status"));
    }

    @Test
    public void 재투입_한도를_넘긴_메시지는_격리_큐로_옮긴다() throws Exception {
        // 이미 3회 재투입된 것처럼 헤더를 달아 DLQ 에 직접 넣는다.
        final String body = Hl7Samples.loadAsCr("adt_a01_admit.hl7");
        probe.convertAndSend(dlqQueue, body, new MessagePostProcessor() {
            @Override
            public Message postProcessMessage(Message m) throws JMSException {
                m.setIntProperty(JmsHeaders.REPROCESS_COUNT, 3);
                m.setStringProperty(JmsHeaders.MSG_CONTROL_ID, "MSG00000001");
                return m;
            }
        });
        awaitDepth(dlqQueue, 1, ACK_WAIT_MS);

        com.example.hl7poc.jms.dlq.DlqReprocessor tool =
                new com.example.hl7poc.jms.dlq.DlqReprocessor(
                        connectionFactory(), requestQueue, dlqQueue, parkQueue);
        int moved = tool.replay(dlqQueue, 10);

        // 자동 복구로 답이 안 나오는 메시지다. 요청 큐로 돌리지 않는다.
        assertEquals("재투입하지 않는다", 0, moved);
        Message parked = await(parkQueue, ACK_WAIT_MS);
        assertEquals("DLQ-MAX-REPROCESS", parked.getStringProperty(JmsHeaders.ERROR_CODE));
        assertEquals("DLQ 에는 남지 않는다(다음 실행에서 또 읽히면 안 된다)",
                null, receiveText(dlqQueue));
    }

    @Test
    public void 재투입할_때_메시지_그룹을_유지한다() throws Exception {
        // 그룹을 잃으면 그 메시지만 순서 보장에서 이탈한다. 재처리는 순서가 가장
        // 중요한 상황인데도.
        final String body = Hl7Samples.loadAsCr("adt_a01_admit.hl7");
        probe.convertAndSend(dlqQueue, body, new MessagePostProcessor() {
            @Override
            public Message postProcessMessage(Message m) throws JMSException {
                m.setStringProperty(JmsHeaders.GROUP_ID, "그룹-PAT000001");
                m.setStringProperty(JmsHeaders.MSG_CONTROL_ID, "MSG00000001");
                return m;
            }
        });
        awaitDepth(dlqQueue, 1, ACK_WAIT_MS);

        // 리스너가 가져가기 전에 헤더를 보려면 요청 큐가 아닌 곳으로 옮겨야 한다.
        // 격리 큐를 대상으로 삼아 복사 동작만 확인한다.
        com.example.hl7poc.jms.dlq.DlqReprocessor tool =
                new com.example.hl7poc.jms.dlq.DlqReprocessor(
                        connectionFactory(), parkQueue, dlqQueue, parkQueue);
        assertEquals(1, tool.replay(dlqQueue, 10));

        Message moved = await(parkQueue, ACK_WAIT_MS);
        assertEquals("그룹-PAT000001", moved.getStringProperty(JmsHeaders.GROUP_ID));
        assertEquals(Integer.valueOf(1),
                Integer.valueOf(moved.getIntProperty(JmsHeaders.REPROCESS_COUNT)));
    }

    // ------------------------------------------------------------------
    // 6. PHI
    // ------------------------------------------------------------------

    @Test
    public void 종단_간_흐름에서도_DB_에_평문이_남지_않는다() throws Exception {
        send("adt_a01_admit.hl7", "PAT000001");
        awaitText(ackQueue, ACK_WAIT_MS);

        String dump = jdbcTemplate.queryForObject(
                "SELECT adt_message::text FROM adt_message WHERE msg_control_id = ?",
                String.class, "MSG00000001");
        assertFalse(dump.contains("PAT000001"));
        assertFalse(dump.contains("홍길동"));
        assertFalse(dump.contains("19850214"));

        // 단계 로그에도 남으면 안 된다.
        String logDump = jdbcTemplate.queryForObject(
                "SELECT coalesce(string_agg(coalesce(detail,''), ' '), '') FROM processing_log",
                String.class);
        assertFalse(logDump.contains("홍길동"));
        assertFalse(logDump.contains("PAT000001"));
    }

    // ------------------------------------------------------------------
    // 도우미
    // ------------------------------------------------------------------

    private static void send(String sample, final String patientId) {
        final String body = Hl7Samples.loadAsCr(sample);
        probe.convertAndSend(requestQueue, body, new MessagePostProcessor() {
            @Override
            public Message postProcessMessage(Message m) throws JMSException {
                if (patientId != null) {
                    // 같은 환자의 메시지를 같은 컨슈머로 고정해 순서를 보장한다.
                    m.setStringProperty(JmsHeaders.GROUP_ID, patientId);
                }
                return m;
            }
        });
    }

    private static javax.jms.ConnectionFactory connectionFactory() {
        // 재처리 도구도 같은 이유로 원본 팩토리를 쓴다(위 probe 주석 참고).
        return context.getBean("jmsConnectionFactory", javax.jms.ConnectionFactory.class);
    }

    private static Message receive(Destination d) {
        return probe.receive(d);
    }

    private static String receiveText(Destination d) throws JMSException {
        Message m = receive(d);
        return (m == null) ? null : ((TextMessage) m).getText();
    }

    private static Message await(Destination d, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Message m = receive(d);
            if (m != null) {
                return m;
            }
        }
        throw new AssertionError(timeoutMs + "ms 안에 메시지가 오지 않았습니다: " + d);
    }

    private static String awaitText(Destination d, long timeoutMs) throws JMSException {
        return ((TextMessage) await(d, timeoutMs)).getText();
    }

    /**
     * 큐에 메시지가 쌓일 때까지 기다린다. <b>소비하지 않는다.</b>
     *
     * <p>{@link #await} 는 받아서 꺼내 버린다. "DLQ 에 들어갔는지 확인한 뒤 재투입"
     * 같은 흐름에서 그걸 쓰면, 확인하는 순간 메시지가 사라져 재투입할 것이 없다.
     * (이 테스트를 쓰면서 실제로 저지른 실수다.)
     */
    private static void awaitDepth(Queue q, final int expected, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int depth = 0;
        while (System.currentTimeMillis() < deadline) {
            depth = depth(q);
            if (depth >= expected) {
                return;
            }
            sleep(200);
        }
        throw new AssertionError(timeoutMs + "ms 안에 " + q + " 가 " + expected
                + "건에 도달하지 않았습니다. 현재=" + depth);
    }

    private static int depth(Queue q) {
        Integer n = probe.browse(q, new org.springframework.jms.core.BrowserCallback<Integer>() {
            @Override
            public Integer doInJms(javax.jms.Session session, javax.jms.QueueBrowser browser)
                    throws JMSException {
                int count = 0;
                java.util.Enumeration<?> e = browser.getEnumeration();
                while (e.hasMoreElements()) {
                    e.nextElement();
                    count++;
                }
                return count;
            }
        });
        return (n == null) ? 0 : n;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 연속으로 두 번 비어 있을 때까지 비운다. 브로커가 재전송을 예약해 둔 메시지가
     * 뒤늦게 나타나는 경우를 잡기 위해서다.
     */
    private static void drainUntilQuiet(Destination d) {
        int consecutiveEmpty = 0;
        while (consecutiveEmpty < 2) {
            if (receive(d) == null) {
                consecutiveEmpty++;
            } else {
                consecutiveEmpty = 0;
            }
        }
    }

    private static List<String> steps(String controlId) {
        return jdbcTemplate.queryForList(
                "SELECT step FROM processing_log WHERE msg_control_id = ? ORDER BY id",
                String.class, controlId);
    }

    private static String randomKey() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return Base64Utils.encode(raw);
    }

    private static boolean portOpen(int port) {
        try (Socket s = new Socket("localhost", port)) {
            return s.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
