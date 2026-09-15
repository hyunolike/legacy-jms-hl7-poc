package com.example.hl7poc.adt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import java.net.Socket;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HapiContext;

import com.example.hl7poc.adt.service.AdtProcessingService;
import com.example.hl7poc.common.dto.AdtEvent;
import com.example.hl7poc.common.dto.ProcessResult;
import com.example.hl7poc.common.exception.TransientProcessingException;
import com.example.hl7poc.common.hl7.AckCode;
import com.example.hl7poc.common.hl7.HapiHl7Parser;
import com.example.hl7poc.common.hl7.Hl7Samples;
import com.example.hl7poc.common.util.Base64Utils;
import com.example.hl7poc.secure.PhiCipher;
import com.example.hl7poc.support.FakeHospitalBNotifier;
import com.example.hl7poc.support.FakeHospitalBNotifier.Behavior;

/**
 * 업무 계층 통합 검증. PostgreSQL 이 실제로 떠 있어야 한다({@code ant docker-up}).
 * 없으면 {@code Assume} 으로 건너뛴다.
 *
 * <p>모든 환자 정보는 가상의 합성 데이터다.
 */
public class AdtProcessingServiceTest {

    private static final int DB_PORT = 5432;

    private static ClassPathXmlApplicationContext context;
    private static AdtProcessingService service;
    private static JdbcTemplate jdbcTemplate;
    private static PhiCipher phiCipher;
    private static FakeHospitalBNotifier notifier;

    private static HapiContext hapiContext;
    private static HapiHl7Parser parser;

    @BeforeClass
    public static void startContext() {
        assumeTrue("PostgreSQL 이 떠 있지 않아 건너뜁니다 (ant docker-up)", portOpen(DB_PORT));

        // 키는 코드/설정 파일이 아니라 바깥에서 주입한다. 테스트도 같은 경로를 쓴다.
        System.setProperty("HL7POC_PHI_KEY", randomKey());
        System.setProperty("HL7POC_BLIND_INDEX_KEY", randomKey());

        context = new ClassPathXmlApplicationContext("classpath:spring/test-adt-context.xml");
        service = context.getBean("adtProcessingService", AdtProcessingService.class);
        jdbcTemplate = context.getBean("jdbcTemplate", JdbcTemplate.class);
        phiCipher = context.getBean("phiCipher", PhiCipher.class);
        notifier = context.getBean("hospitalBNotifier", FakeHospitalBNotifier.class);

        hapiContext = new DefaultHapiContext();
        parser = new HapiHl7Parser(hapiContext);
        parser.setValidationEnabled(true);
    }

    @AfterClass
    public static void stopContext() throws Exception {
        if (context != null) {
            context.close();
        }
        if (hapiContext != null) {
            hapiContext.close();
        }
        System.clearProperty("HL7POC_PHI_KEY");
        System.clearProperty("HL7POC_BLIND_INDEX_KEY");
    }

    @Before
    public void clean() {
        jdbcTemplate.update("TRUNCATE processed_message, adt_message, processing_log RESTART IDENTITY");
        notifier.reset(Behavior.SUCCEED);
    }

    @After
    public void cleanAfter() {
        clean();
    }

    private static AdtEvent event(String sampleFile) {
        return parser.toAdtEvent(parser.parseHeader(Hl7Samples.loadAsCr(sampleFile)));
    }

    // ------------------------------------------------------------------
    // 정상 경로
    // ------------------------------------------------------------------

    @Test
    public void 입원_메시지를_적재하고_병원B_로_전달한다() {
        ProcessResult result = service.process(event("adt_a01_admit.hl7"));

        assertEquals(AckCode.AA, result.getAckCode());
        assertFalse(result.isDuplicate());
        assertEquals("병원 B 로 한 번 전달된다", 1, notifier.callCount());

        Map<String, Object> row = row("MSG00000001");
        assertEquals("FORWARDED", row.get("status"));
        assertEquals("ADT^A01", row.get("msg_type"));
        assertEquals("A01", row.get("trigger_event"));
        assertEquals("I", row.get("patient_class"));
        assertNotNull("입원 시각이 적재된다", row.get("admit_datetime"));
        assertNull("입원 메시지에는 퇴원 시각이 없다", row.get("discharge_datetime"));

        // 단계 로그가 DB 에도 남는다 — "이 MSH-10 은 어디까지 갔나"를 SQL 로 답한다.
        assertTrue(steps("MSG00000001").containsAll(
                java.util.Arrays.asList("DEDUP_OK", "ENCRYPTED", "PERSISTED", "FORWARDED")));
    }

    @Test
    public void 퇴원_메시지도_적재한다() {
        service.process(event("adt_a01_admit.hl7"));
        ProcessResult result = service.process(event("adt_a03_discharge.hl7"));

        assertEquals(AckCode.AA, result.getAckCode());
        Map<String, Object> row = row("MSG00000002");
        assertEquals("A03", row.get("trigger_event"));
        assertNotNull("퇴원 시각이 적재된다", row.get("discharge_datetime"));
    }

    // ------------------------------------------------------------------
    // PHI 보호
    // ------------------------------------------------------------------

    @Test
    public void 환자_정보가_DB_에_평문으로_남지_않는다() {
        service.process(event("adt_a01_admit.hl7"));

        // 테이블 전체를 텍스트로 훑는다. 컬럼을 하나씩 확인하면 새로 추가된
        // 컬럼을 놓친다.
        String dump = jdbcTemplate.queryForObject(
                "SELECT adt_message::text FROM adt_message WHERE msg_control_id = ?",
                String.class, "MSG00000001");

        assertFalse("환자번호 평문", dump.contains("PAT000001"));
        assertFalse("이름 평문", dump.contains("홍길동"));
        assertFalse("생년월일 평문", dump.contains("19850214"));
        assertFalse("전화번호 평문", dump.contains("010-0000-0001"));
    }

    @Test
    public void 암호문은_복호화하면_원문이_나온다() {
        service.process(event("adt_a01_admit.hl7"));
        Map<String, Object> row = row("MSG00000001");

        assertEquals("PAT000001", phiCipher.decrypt((String) row.get("patient_id_enc")));
        assertEquals("홍길동", phiCipher.decrypt((String) row.get("patient_name_enc")));
        assertEquals("19850214", phiCipher.decrypt((String) row.get("patient_dob_enc")));
        assertEquals("010-0000-0001", phiCipher.decrypt((String) row.get("patient_phone_enc")));
        // 성별은 단독 식별성이 낮아 평문을 허용한다(통계 조회가 잦다).
        assertEquals("M", row.get("patient_sex"));
    }

    @Test
    public void 같은_환자는_같은_블라인드_인덱스를_갖는다() {
        service.process(event("adt_a01_admit.hl7"));      // PAT000001
        service.process(event("adt_a03_discharge.hl7"));  // 같은 환자
        service.process(event("adt_a01_admit_other_patient.hl7"));   // PAT000002

        String h1 = (String) row("MSG00000001").get("patient_id_hash");
        String h2 = (String) row("MSG00000002").get("patient_id_hash");
        String h3 = (String) row("MSG00000003").get("patient_id_hash");

        assertEquals("같은 환자 → 같은 인덱스 (그래야 WHERE 로 찾는다)", h1, h2);
        assertFalse("다른 환자 → 다른 인덱스", h1.equals(h3));
        assertFalse("인덱스에 평문이 남지 않는다", h1.contains("PAT"));
    }

    // ------------------------------------------------------------------
    // 멱등성
    // ------------------------------------------------------------------

    @Test
    public void 같은_메시지를_두_번_받으면_한_번만_처리한다() {
        ProcessResult first = service.process(event("adt_a01_admit.hl7"));
        service.recordAck("HOSP_A", "MSG00000001", AckCode.AA, "저장된-ACK");

        ProcessResult second = service.process(event("adt_a01_admit.hl7"));

        assertFalse(first.isDuplicate());
        assertTrue("두 번째는 중복으로 판정", second.isDuplicate());

        // 핵심: 병원 B 에 같은 입원 통보가 두 번 가면 상대 데이터가 깨진다.
        assertEquals("병원 B 전달은 한 번뿐", 1, notifier.callCount());
        assertEquals("적재도 한 행뿐", 1, countRows("MSG00000001"));

        // 저장해 둔 ACK 를 그대로 돌려준다. 새로 만들면 최초와 다른 코드가 나갈 수 있다.
        assertEquals("저장된-ACK", second.getStoredAckPayload());
        assertEquals(AckCode.AA, second.getAckCode());
        assertTrue(steps("MSG00000001").contains("DEDUP_SKIP"));
    }

    @Test
    public void 송신기관이_다르면_같은_ControlId_라도_별개_메시지다() {
        // MSH-10 은 송신 시스템 안에서만 유일하다. 단독 키로 잡으면 병원이
        // 하나 늘어나는 순간 멀쩡한 메시지가 중복으로 버려진다.
        service.process(event("adt_a01_admit.hl7"));           // HOSP_A / MSG00000001

        int claimed = jdbcTemplate.update(
                "INSERT INTO processed_message (sending_facility, msg_control_id) "
                        + "VALUES (?, ?) ON CONFLICT DO NOTHING", "HOSP_C", "MSG00000001");
        assertEquals("다른 기관의 같은 Control ID 는 선점된다", 1, claimed);
    }

    // ------------------------------------------------------------------
    // 롤백
    // ------------------------------------------------------------------

    @Test
    public void 병원B_전달이_실패하면_적재와_선점이_함께_롤백된다() {
        notifier.reset(Behavior.FAIL_TRANSIENT);

        try {
            service.process(event("adt_a01_admit.hl7"));
            fail("TransientProcessingException 이 나야 한다");
        } catch (TransientProcessingException expected) {
            assertEquals("WS-TIMEOUT", expected.getErrorCode());
            assertTrue(expected.isRetryable());
        }

        // 적재가 남아 있으면 재시도 때 유니크 제약에 걸려 영영 처리되지 않는다.
        assertEquals("adt_message 가 롤백된다", 0, countRows("MSG00000001"));
        assertEquals("멱등성 선점도 롤백된다", 0, countClaims("MSG00000001"));
        assertEquals("단계 로그도 롤백된다", 0, steps("MSG00000001").size());
    }

    @Test
    public void 롤백된_뒤_재시도하면_정상_처리된다() {
        // 앞의 테스트가 보장하는 것의 실질적 의미다. 선점이 남아 있었다면
        // 재시도가 "중복"으로 판정되어 메시지가 조용히 사라진다.
        notifier.reset(Behavior.FAIL_TRANSIENT);
        try {
            service.process(event("adt_a01_admit.hl7"));
            fail();
        } catch (TransientProcessingException ignored) {
            // 기대한 예외
        }

        notifier.reset(Behavior.SUCCEED);
        ProcessResult retry = service.process(event("adt_a01_admit.hl7"));

        assertFalse("중복이 아니라 최초 처리로 취급되어야 한다", retry.isDuplicate());
        assertEquals(AckCode.AA, retry.getAckCode());
        assertEquals("FORWARDED", row("MSG00000001").get("status"));
    }

    // ------------------------------------------------------------------
    // 순서 역전
    // ------------------------------------------------------------------

    @Test
    public void 순서가_뒤집혀_도착하면_기록을_남기되_버리지는_않는다() {
        // 퇴원(3/5)을 먼저, 입원(3/1)을 나중에 받는다.
        service.process(event("adt_a03_discharge.hl7"));
        ProcessResult late = service.process(event("adt_a01_admit.hl7"));

        assertEquals("늦게 온 메시지도 처리한다", AckCode.AA, late.getAckCode());
        assertEquals(1, countRows("MSG00000001"));
        assertTrue("순서 역전을 기록으로 남긴다",
                steps("MSG00000001").contains("OUT_OF_ORDER"));
    }

    // ------------------------------------------------------------------
    // 도우미
    // ------------------------------------------------------------------

    private static Map<String, Object> row(String controlId) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM adt_message WHERE msg_control_id = ?", controlId);
    }

    private static int countRows(String controlId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM adt_message WHERE msg_control_id = ?",
                Integer.class, controlId);
    }

    private static int countClaims(String controlId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM processed_message WHERE msg_control_id = ?",
                Integer.class, controlId);
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
}
