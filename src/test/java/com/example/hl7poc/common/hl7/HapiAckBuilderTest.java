package com.example.hl7poc.common.hl7;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.util.Terser;

/**
 * ACK 생성 검증.
 *
 * <p>생성한 ACK 를 <b>다시 파싱해서</b> 확인한다. 문자열 비교로 검사하면 필드
 * 구분자 개수 같은 것에 테스트가 묶여서, 규격상 동등한 변경에도 깨진다.
 */
public class HapiAckBuilderTest {

    private static HapiContext hapiContext;
    private static HapiHl7Parser parser;
    private static HapiAckBuilder builder;

    @BeforeClass
    public static void setUp() {
        hapiContext = new DefaultHapiContext();
        parser = new HapiHl7Parser(hapiContext);
        parser.setValidationEnabled(true);
        builder = new HapiAckBuilder(hapiContext);
        builder.setSendingApplication("HL7POC_BRIDGE");
        builder.setSendingFacility("HOSP_BRIDGE");
    }

    @AfterClass
    public static void tearDown() throws Exception {
        hapiContext.close();
    }

    private static Hl7MessageContext header(String sample) {
        return parser.parseHeader(Hl7Samples.loadAsCr(sample));
    }

    private static Terser reparse(String ack) {
        // 검증을 끈 파서로 다시 읽는다. ACK 자체가 규격에 맞는지는 아래 별도 확인.
        return new Terser(parser.parseHeader(ack).getSourceMessage());
    }

    private static String get(String ack, String path) {
        try {
            return reparse(ack).get(path);
        } catch (Exception e) {
            throw new IllegalStateException("ACK 에서 " + path + " 를 읽지 못했습니다", e);
        }
    }

    // ------------------------------------------------------------------

    @Test
    public void 정상_ACK_는_AA_와_원본_ControlId_를_담는다() {
        String ack = builder.buildAck(header("adt_a01_admit.hl7"), AckCode.AA, null, null);
        System.out.println("  [AA]\n    " + ack.replace("\r", "\n    "));

        assertEquals("AA", get(ack, "/MSA-1"));
        // MSA-2 는 원본 MSH-10 이다. 이게 틀리면 상대가 응답을 원본과 못 잇는다.
        assertEquals("MSG00000001", get(ack, "/MSA-2"));
        assertEquals("ACK", get(ack, "/MSH-9-1"));
        assertEquals("2.5", get(ack, "/MSH-12-1"));
    }

    @Test
    public void ACK_자신의_ControlId_는_원본과_다르다() {
        // 흔한 혼동 지점이다. MSH-10 은 이 ACK 메시지 자신의 식별자이고,
        // 원본 식별자는 MSA-2 에 들어간다.
        String ack = builder.buildAck(header("adt_a01_admit.hl7"), AckCode.AA, null, null);
        String ackOwnId = get(ack, "/MSH-10");

        assertFalse("ACK 의 MSH-10 이 원본과 같으면 안 된다", "MSG00000001".equals(ackOwnId));
        assertTrue("ACK 도 자기 식별자를 가져야 한다", ackOwnId != null && !ackOwnId.isEmpty());
    }

    @Test
    public void 응답은_요청의_반대_방향으로_간다() {
        // 원본: HIS/HOSP_A → BRIDGE/HOSP_BRIDGE
        // ACK : HL7POC_BRIDGE/HOSP_BRIDGE → HIS/HOSP_A
        String ack = builder.buildAck(header("adt_a01_admit.hl7"), AckCode.AA, null, null);

        assertEquals("HL7POC_BRIDGE", get(ack, "/MSH-3-1"));
        assertEquals("HOSP_BRIDGE", get(ack, "/MSH-4-1"));
        assertEquals("HIS", get(ack, "/MSH-5-1"));
        assertEquals("HOSP_A", get(ack, "/MSH-6-1"));
    }

    @Test
    public void 오류_ACK_는_표준_오류코드를_ERR3_에_담는다() {
        String ack = builder.buildAck(header("adt_a01_missing_pid.hl7"),
                AckCode.AE, "HL7-NO-PATIENT-ID", "PID-3(환자 식별자)가 없습니다.");
        System.out.println("  [AE]\n    " + ack.replace("\r", "\n    "));

        assertEquals("AE", get(ack, "/MSA-1"));
        assertEquals("MSG00000101", get(ack, "/MSA-2"));

        // 내부 코드를 ERR-3 에 그대로 넣으면 상대가 해석할 수 없다.
        // 표준 코드(테이블 0357)를 넣고, 내부 코드는 ERR-8 에 남긴다.
        assertEquals("101", get(ack, "/ERR-3-1"));
        assertEquals("Required field missing", get(ack, "/ERR-3-2"));
        assertEquals("HL70357", get(ack, "/ERR-3-3"));
        assertEquals("E", get(ack, "/ERR-4"));
        assertTrue(get(ack, "/ERR-8").contains("HL7-NO-PATIENT-ID"));
    }

    @Test
    public void 미지원_트리거는_201_로_매핑된다() {
        String ack = builder.buildAck(header("adt_a08_unsupported.hl7"),
                AckCode.AE, "HL7-UNSUPPORTED-TRIGGER", "지원하지 않는 트리거입니다: A08");
        assertEquals("201", get(ack, "/ERR-3-1"));
        assertEquals("Unsupported event code", get(ack, "/ERR-3-2"));
    }

    @Test
    public void 모르는_오류코드는_기본값으로_매핑된다() {
        String ack = builder.buildAck(header("adt_a01_admit.hl7"),
                AckCode.AE, "완전히-새로운-코드", "무언가 잘못됐습니다");
        // 매핑에 없다고 ACK 생성이 실패하면 안 된다. 상대는 아무 응답도 못 받는다.
        assertEquals("207", get(ack, "/ERR-3-1"));
        assertTrue(get(ack, "/ERR-8").contains("완전히-새로운-코드"));
    }

    @Test
    public void 정상_ACK_에는_ERR_세그먼트가_없다() {
        String ack = builder.buildAck(header("adt_a01_admit.hl7"), AckCode.AA, null, null);
        assertFalse("AA 에 ERR 이 붙으면 상대가 오류로 오해한다", ack.contains("ERR|"));
    }

    // ------------------------------------------------------------------
    // 헤더를 못 읽은 경우
    // ------------------------------------------------------------------

    @Test
    public void MSH_가_깨져도_ControlId_를_최대한_건져_낸다() {
        // 구분자가 살아 있으면 MSH-10 은 건질 수 있는 경우가 많다.
        String broken = "MSH|^~\\&|HIS|HOSP_A|BRIDGE|HOSP_BRIDGE|20260301093000||ADT^A01"
                + "|MSG00000999|P|2.5\rPID|1||깨진데이터";
        String ack = builder.buildRejectAck(broken, "HL7-PARSE", "구조를 읽을 수 없습니다");
        System.out.println("  [AR]\n    " + ack.replace("\r", "\n    "));

        assertEquals("AR", get(ack, "/MSA-1"));
        assertEquals("MSG00000999", get(ack, "/MSA-2"));
        assertEquals("102", get(ack, "/ERR-3-1"));
    }

    @Test
    public void 정말_못_건지면_UNKNOWN_을_넣는다() {
        // 비워 두면 상대가 어느 메시지에 대한 응답인지 몰라 재전송을 반복한다.
        String ack = builder.buildRejectAck("완전히 쓰레기 데이터", "HL7-PARSE", "구조 불명");
        assertEquals("AR", get(ack, "/MSA-1"));
        assertEquals(HapiAckBuilder.UNKNOWN_CONTROL_ID, get(ack, "/MSA-2"));
    }

    @Test
    public void 트리거도_최대한_건져_낸다() {
        String broken = "MSH|^~\\&|HIS|HOSP_A|BRIDGE|HOSP_BRIDGE|20260301093000||ADT^A03"
                + "|MSG00000998|P|2.5\rPID|1||깨짐";
        String ack = builder.buildRejectAck(broken, "HL7-PARSE", "구조를 읽을 수 없습니다");
        assertEquals("원본 트리거를 되살려 담는다", "A03", get(ack, "/MSH-9-2"));
    }

    @Test
    public void 트리거를_못_건지면_자리_채움값을_쓴다() {
        // HL7 에 "알 수 없음" 트리거는 없다. 비워 두면 HAPI 인코딩이 터지므로
        // 부득이하게 자리를 채운다. ACK 를 아예 못 보내는 것보다 낫다.
        String ack = builder.buildRejectAck("완전히 쓰레기", "HL7-PARSE", "구조 불명");
        assertEquals(HapiAckBuilder.UNKNOWN_TRIGGER_EVENT, get(ack, "/MSH-9-2"));
    }

    @Test
    public void ControlId_추출_경계값() {
        assertEquals("UNKNOWN", HapiAckBuilder.bestEffortControlId(null));
        assertEquals("UNKNOWN", HapiAckBuilder.bestEffortControlId(""));
        assertEquals("UNKNOWN", HapiAckBuilder.bestEffortControlId("PID|1||PAT1"));
        // 필드가 모자란 경우
        assertEquals("UNKNOWN", HapiAckBuilder.bestEffortControlId("MSH|^~\\&|HIS|HOSP_A"));
        // 비어 있는 MSH-10
        assertEquals("UNKNOWN", HapiAckBuilder.bestEffortControlId(
                "MSH|^~\\&|HIS|HOSP_A|B|F|20260301||ADT^A01||P|2.5"));
    }

    // ------------------------------------------------------------------
    // PHI 보호
    // ------------------------------------------------------------------

    /**
     * 실제로 유출이 일어났던 경로다.
     *
     * <p>HAPI 의 파싱 예외 메시지는 "참고용"이라며 원문 앞 50자를 붙여 준다.
     * 그 문구를 그대로 ERR-8 에 실었더니 환자 정보가 상대 병원으로 나갔다.
     * 통합 시나리오에서 ACK 를 눈으로 보고서야 발견했다.
     */
    @Test
    public void 파싱_실패_ACK_에_원문_조각이_새지_않는다() {
        String raw = "MSH|^~\\&|HIS|HOSP_A|BRIDGE|HOSP_BRIDGE|20260301|"
                + "|ADT^A01|MSG00000999|P|2.5\rPID|1||PAT000001^^^HOSP_A^MR||홍^길동^^^^^L";

        // 파서가 만드는 예외를 그대로 재현한다(HAPI 문구 + 원문 조각).
        com.example.hl7poc.common.exception.Hl7ParseException e =
                new com.example.hl7poc.common.exception.Hl7ParseException(
                        "HL7 구조를 해석할 수 없습니다: The following is the first 50 chars"
                                + " of the message for reference: " + raw);

        // 내부 로그용 메시지에는 상세가 남아 있어야 진단이 된다.
        assertTrue(e.getMessage().contains("PAT000001"));

        // 그러나 ACK 에는 나가면 안 된다.
        String ack = builder.buildRejectAck(raw, e.getErrorCode(), e.getClientSafeText());
        String err8 = get(ack, "/ERR-8");
        assertFalse("환자번호가 ACK 로 나가면 안 된다", err8.contains("PAT000001"));
        assertFalse("이름이 ACK 로 나가면 안 된다", err8.contains("홍"));
        assertFalse("원문 조각이 ACK 로 나가면 안 된다", err8.contains("MSH|"));
        assertTrue("무엇이 문제인지는 알려 준다", err8.contains("HL7-PARSE"));
    }

    @Test
    public void 오류_문구가_길어도_잘라서_보낸다() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longText.append("아주 긴 오류 설명 ");
        }
        String ack = builder.buildAck(header("adt_a01_admit.hl7"),
                AckCode.AE, "HL7-PARSE", longText.toString());

        // ACK 는 네트워크로 나가 상대 로그에 그대로 쌓인다. 무한정 흘려보내지 않는다.
        assertTrue(get(ack, "/ERR-8").length() <= 250);
    }

    @Test
    public void 오류_문구의_개행은_세그먼트를_깨뜨리지_않는다() {
        // 개행이 그대로 들어가면 HL7 세그먼트 구분자가 되어 메시지가 통째로 망가진다.
        String ack = builder.buildAck(header("adt_a01_admit.hl7"),
                AckCode.AE, "HL7-PARSE", "첫 줄\r둘째 줄\n셋째 줄");

        assertEquals("AE", get(ack, "/MSA-1"));
        assertFalse(get(ack, "/ERR-8").contains("\r"));
        assertFalse(get(ack, "/ERR-8").contains("\n"));
    }
}
