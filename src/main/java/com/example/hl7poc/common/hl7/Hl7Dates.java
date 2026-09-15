package com.example.hl7poc.common.hl7;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.hl7poc.common.exception.PermanentProcessingException;

/**
 * HL7 v2 의 TS(timestamp) 필드를 {@link Date} 로 바꾼다.
 *
 * <p>HL7 날짜는 자리수가 가변이다. {@code YYYY}, {@code YYYYMM}, {@code YYYYMMDD},
 * {@code YYYYMMDDHHMM}, {@code YYYYMMDDHHMMSS[.S...]} 에 선택적으로
 * {@code +/-ZZZZ} 오프셋이 붙는다. "yyyyMMddHHmmss 로 파싱"만 해 두면 자리수가
 * 짧은 실제 메시지에서 바로 깨진다.
 *
 * <p>{@code SimpleDateFormat} 은 스레드 세이프하지 않으므로 매번 만든다.
 * 컨슈머가 10개까지 늘어나는 구조에서 static 필드로 공유하면 조용히 값이 섞인다.
 */
public final class Hl7Dates {

    /** 숫자 14자리까지 + 선택적 소수부 + 선택적 타임존 오프셋 */
    private static final Pattern TS_PATTERN =
            Pattern.compile("^(\\d{4,14})(?:\\.(\\d{1,4}))?(?:([+-])(\\d{4}))?$");

    private Hl7Dates() {
    }

    /**
     * @param value HL7 TS 문자열. null 이거나 비어 있으면 null 을 돌려준다
     *              (선택 필드가 비어 있는 것은 오류가 아니다).
     * @throws PermanentProcessingException 형식이 어긋날 때. 같은 값을 다시 보내도
     *         결과가 같으므로 재시도 대상이 아니다.
     */
    public static Date parse(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String s = value.trim();
        Matcher m = TS_PATTERN.matcher(s);
        if (!m.matches()) {
            throw new PermanentProcessingException("HL7-TS-FORMAT",
                    "HL7 날짜 형식이 아닙니다: " + s);
        }

        String digits = m.group(1);
        // 자리수가 홀수이거나 규격에 없는 길이면 거부한다 (예: 5자리, 7자리)
        if (digits.length() % 2 != 0) {
            throw new PermanentProcessingException("HL7-TS-FORMAT",
                    "HL7 날짜 자리수가 올바르지 않습니다: " + s);
        }

        String pattern;
        switch (digits.length()) {
            case 4:  pattern = "yyyy";               break;
            case 6:  pattern = "yyyyMM";             break;
            case 8:  pattern = "yyyyMMdd";           break;
            case 10: pattern = "yyyyMMddHH";         break;
            case 12: pattern = "yyyyMMddHHmm";       break;
            case 14: pattern = "yyyyMMddHHmmss";     break;
            default:
                throw new PermanentProcessingException("HL7-TS-FORMAT",
                        "HL7 날짜 자리수가 올바르지 않습니다: " + s);
        }

        SimpleDateFormat sdf = new SimpleDateFormat(pattern);
        // lenient 를 켜 두면 20261345 같은 값이 조용히 다음 해로 넘어간다.
        sdf.setLenient(false);
        String offsetSign = m.group(3);
        if (offsetSign != null) {
            sdf.setTimeZone(TimeZone.getTimeZone("GMT" + offsetSign + m.group(4)));
        }

        try {
            return sdf.parse(digits);
        } catch (ParseException e) {
            throw new PermanentProcessingException("HL7-TS-FORMAT",
                    "HL7 날짜 값이 올바르지 않습니다: " + s, e);
        }
    }
}
