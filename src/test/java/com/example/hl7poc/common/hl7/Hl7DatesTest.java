package com.example.hl7poc.common.hl7;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import org.junit.Test;

import com.example.hl7poc.common.exception.PermanentProcessingException;

/**
 * HL7 TS(timestamp) 파싱 검증.
 *
 * <p>HL7 날짜는 자리수가 가변이다. "yyyyMMddHHmmss 로 파싱"만 해 두면 실제
 * 메시지에서 바로 깨진다. 그 경계들을 고정한다.
 */
public class Hl7DatesTest {

    private static String fmt(Date d, String pattern) {
        return new SimpleDateFormat(pattern).format(d);
    }

    @Test
    public void 자리수가_가변인_날짜를_모두_읽는다() {
        assertEquals("2026", fmt(Hl7Dates.parse("2026"), "yyyy"));
        assertEquals("202603", fmt(Hl7Dates.parse("202603"), "yyyyMM"));
        assertEquals("20260301", fmt(Hl7Dates.parse("20260301"), "yyyyMMdd"));
        assertEquals("202603010930", fmt(Hl7Dates.parse("202603010930"), "yyyyMMddHHmm"));
        assertEquals("20260301093045",
                fmt(Hl7Dates.parse("20260301093045"), "yyyyMMddHHmmss"));
    }

    @Test
    public void 소수부가_붙어도_읽는다() {
        assertEquals("20260301093045",
                fmt(Hl7Dates.parse("20260301093045.1234"), "yyyyMMddHHmmss"));
    }

    @Test
    public void 타임존_오프셋을_반영한다() {
        // 같은 순간을 다른 오프셋으로 표기하면 UTC 값이 달라야 한다.
        Date kst = Hl7Dates.parse("20260301093000+0900");
        Date utc = Hl7Dates.parse("20260301093000+0000");
        assertEquals(9L * 60 * 60 * 1000, utc.getTime() - kst.getTime());

        SimpleDateFormat utcFmt = new SimpleDateFormat("yyyyMMddHHmmss");
        utcFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        assertEquals("20260301003000", utcFmt.format(kst));
    }

    @Test
    public void 빈_값은_오류가_아니라_null_이다() {
        // 선택 필드가 비어 있는 것은 정상이다. 여기서 예외를 던지면
        // 퇴원 시각이 없는 입원 메시지가 전부 실패한다.
        assertNull(Hl7Dates.parse(null));
        assertNull(Hl7Dates.parse(""));
        assertNull(Hl7Dates.parse("   "));
    }

    @Test
    public void 형식이_어긋나면_재시도하지_않는_오류다() {
        assertRejected("2026-03-01 09:30:00");
        assertRejected("20260301T0930");
        assertRejected("abc");
        assertRejected("20260");      // 홀수 자리
    }

    @Test
    public void 존재하지_않는_날짜는_조용히_넘어가지_않는다() {
        // lenient 를 켜 두면 20261345 가 다음 해로 넘어가 버린다.
        assertRejected("20261345");
        assertRejected("20260132");
    }

    private static void assertRejected(String value) {
        try {
            Hl7Dates.parse(value);
            fail("거부되어야 합니다: " + value);
        } catch (PermanentProcessingException expected) {
            assertEquals("HL7-TS-FORMAT", expected.getErrorCode());
            assertEquals(false, expected.isRetryable());
        }
    }
}
