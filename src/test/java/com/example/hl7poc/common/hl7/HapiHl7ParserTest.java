package com.example.hl7poc.common.hl7;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.text.SimpleDateFormat;
import java.util.Date;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HapiContext;

import com.example.hl7poc.common.dto.AdtEvent;
import com.example.hl7poc.common.dto.PatientInfo;
import com.example.hl7poc.common.exception.Hl7ParseException;
import com.example.hl7poc.common.exception.Hl7ProcessingException;
import com.example.hl7poc.common.exception.PermanentProcessingException;

/**
 * HL7 파서 래퍼 검증. 모든 입력은 가상의 합성 데이터다.
 */
public class HapiHl7ParserTest {

    private static HapiContext hapiContext;
    private static HapiHl7Parser parser;

    @BeforeClass
    public static void setUp() {
        hapiContext = new DefaultHapiContext();
        parser = new HapiHl7Parser(hapiContext);
        parser.setValidationEnabled(true);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        hapiContext.close();
    }

    private static String ymdhms(Date d) {
        return new SimpleDateFormat("yyyyMMddHHmmss").format(d);
    }

    // ------------------------------------------------------------------
    // 정상 경로
    // ------------------------------------------------------------------

    @Test
    public void A01_헤더를_읽는다() {
        Hl7MessageContext ctx = parser.parseHeader(Hl7Samples.loadAsCr("adt_a01_admit.hl7"));

        assertEquals("HIS", ctx.getSendingApplication());
        assertEquals("HOSP_A", ctx.getSendingFacility());
        assertEquals("BRIDGE", ctx.getReceivingApplication());
        assertEquals("MSG00000001", ctx.getMessageControlId());
        assertEquals("ADT^A01", ctx.getMessageType());
        assertEquals("A01", ctx.getTriggerEvent());
        assertEquals("P", ctx.getProcessingId());
        assertEquals("2.5", ctx.getVersion());
        assertEquals("20260301093000", ymdhms(ctx.getMessageDateTime()));
        assertEquals("원문 지문은 64자 hex", 64, ctx.getRawSha256().length());
    }

    @Test
    public void A01_본문을_업무_DTO_로_바꾼다() {
        AdtEvent e = parser.toAdtEvent(
                parser.parseHeader(Hl7Samples.loadAsCr("adt_a01_admit.hl7")));

        PatientInfo p = e.getPatient();
        assertEquals("PAT000001", p.getPatientId());
        assertEquals("홍", p.getFamilyName());
        assertEquals("길동", p.getGivenName());
        assertEquals("홍길동", p.getFullName());
        assertEquals("19850214", p.getBirthDate());
        assertEquals("M", p.getSex());
        assertTrue("주소에 우편번호까지 조합된다", p.getAddress().contains("03172"));
        assertEquals("010-0000-0001", p.getPhone());

        assertEquals("I", e.getPatientClass());
        assertEquals("WARD5 501 A", e.getAssignedLocation());
        assertEquals("ACC0000001", e.getVisitNumber());
        assertEquals("20260301093000", ymdhms(e.getAdmitDateTime()));
        assertNull("입원 메시지에는 퇴원 시각이 없다", e.getDischargeDateTime());
        assertTrue(e.isAdmission());
    }

    @Test
    public void A03_퇴원_시각을_읽는다() {
        AdtEvent e = parser.toAdtEvent(
                parser.parseHeader(Hl7Samples.loadAsCr("adt_a03_discharge.hl7")));

        assertTrue(e.isDischarge());
        assertEquals("MSG00000002", e.getHeader().getMessageControlId());
        assertEquals("20260301093000", ymdhms(e.getAdmitDateTime()));
        assertEquals("20260305141500", ymdhms(e.getDischargeDateTime()));
        assertEquals("같은 환자의 후속 이벤트", "PAT000001", e.getPatient().getPatientId());
    }

    // ------------------------------------------------------------------
    // 개행 정규화
    // ------------------------------------------------------------------

    @Test
    public void 구분자가_CR_LF_CRLF_어느_쪽이든_같게_읽는다() {
        String lf = Hl7Samples.load("adt_a01_admit.hl7");
        String cr = lf.replace("\n", "\r");
        String crlf = lf.replace("\n", "\r\n");

        String a = parser.parseHeader(lf).getMessageControlId();
        String b = parser.parseHeader(cr).getMessageControlId();
        String c = parser.parseHeader(crlf).getMessageControlId();

        assertEquals("MSG00000001", a);
        assertEquals(a, b);
        assertEquals(a, c);

        // 정규화 결과가 같아야 지문(SHA-256)도 같다. 같은 메시지가 전송 경로에
        // 따라 다른 지문을 갖게 되면 중복 판정이 어긋난다.
        assertEquals(parser.parseHeader(lf).getRawSha256(),
                     parser.parseHeader(crlf).getRawSha256());
    }

    @Test
    public void MLLP_프레이밍이_붙어_있어도_읽는다() {
        char start = (char) 0x0B;
        char end = (char) 0x1C;
        String framed = start + Hl7Samples.loadAsCr("adt_a01_admit.hl7") + end + "\r";
        assertEquals("MSG00000001", parser.parseHeader(framed).getMessageControlId());
    }

    // ------------------------------------------------------------------
    // 오류 경로
    // ------------------------------------------------------------------

    @Test
    public void MSH_가_깨지면_AR_로_거절한다() {
        try {
            parser.parseHeader(Hl7Samples.loadAsCr("adt_a01_broken_msh.hl7"));
            fail("Hl7ParseException 이 나야 한다");
        } catch (Hl7ParseException expected) {
            assertEquals(AckCode.AR, expected.getAckCode());
            assertFalse("재시도 대상이 아니다", expected.isRetryable());
            assertFalse("원문이 예외 메시지에 새지 않아야 한다",
                    expected.getMessage().contains("PAT000009"));
        }
    }

    @Test
    public void PID_세그먼트가_없으면_AE_로_거절한다() {
        Hl7MessageContext ctx = parser.parseHeader(Hl7Samples.loadAsCr("adt_a01_missing_pid.hl7"));
        // 헤더는 읽혔다 — 그래서 정확한 ACK 를 보낼 수 있다. 이게 2단계 분리의 이유다.
        assertEquals("MSG00000101", ctx.getMessageControlId());
        assertPermanent(ctx, "HL7-NO-PATIENT-ID");
    }

    @Test
    public void 환자_식별자가_비어_있으면_AE_로_거절한다() {
        Hl7MessageContext ctx =
                parser.parseHeader(Hl7Samples.loadAsCr("adt_a01_missing_patient_id.hl7"));
        assertEquals("MSG00000102", ctx.getMessageControlId());
        assertPermanent(ctx, "HL7-NO-PATIENT-ID");
    }

    @Test
    public void 지원하지_않는_트리거는_AE_로_거절한다() {
        Hl7MessageContext ctx = parser.parseHeader(Hl7Samples.loadAsCr("adt_a08_unsupported.hl7"));
        assertEquals("A08", ctx.getTriggerEvent());
        assertPermanent(ctx, "HL7-UNSUPPORTED-TRIGGER");
    }

    @Test
    public void 빈_메시지는_AR_로_거절한다() {
        try {
            parser.parseHeader("   ");
            fail("Hl7ParseException 이 나야 한다");
        } catch (Hl7ParseException expected) {
            assertEquals(AckCode.AR, expected.getAckCode());
        }
    }

    /**
     * MSH-7 이 HL7 형식이 아닌 경우. HAPI 검증과 우리 날짜 파서 중 어느 쪽이
     * 먼저 잡든, 재시도하지 않는 오류로 분류되어야 한다는 것이 요구사항이다.
     */
    @Test
    public void 잘못된_날짜_형식은_재시도하지_않는_오류다() {
        try {
            Hl7MessageContext ctx =
                    parser.parseHeader(Hl7Samples.loadAsCr("adt_a01_bad_datetime.hl7"));
            parser.toAdtEvent(ctx);
            fail("오류가 나야 한다");
        } catch (Hl7ProcessingException expected) {
            assertFalse("재시도 대상이 아니어야 한다", expected.isRetryable());
            assertNotNull(expected.getErrorCode());
            System.out.println("  [확인] 잘못된 날짜 -> " + expected.getClass().getSimpleName()
                    + " / " + expected.getErrorCode() + " / " + expected.getMessage());
        }
    }

    private static void assertPermanent(Hl7MessageContext ctx, String expectedErrorCode) {
        try {
            parser.toAdtEvent(ctx);
            fail("PermanentProcessingException 이 나야 한다");
        } catch (PermanentProcessingException expected) {
            assertEquals(expectedErrorCode, expected.getErrorCode());
            assertEquals(AckCode.AE, expected.getAckCode());
            assertFalse("재시도 대상이 아니다", expected.isRetryable());
        }
    }
}
