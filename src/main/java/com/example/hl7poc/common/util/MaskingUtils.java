package com.example.hl7poc.common.util;

/**
 * 로그에 나갈 환자 식별정보를 가린다.
 *
 * <p>원칙은 "지워도 추적은 가능하게"다. 전부 {@code ****} 로 바꾸면 장애 조사 때
 * 같은 환자의 로그를 이어 볼 수 없으므로, 앞뒤 일부를 남겨 식별은 되지 않으면서
 * 대조는 가능한 형태로 만든다.
 *
 * <p>이 클래스는 <b>마지막 방어선</b>이다. 애초에 원문 HL7 을 INFO 로 찍지 않는
 * 것이 먼저다. 로그를 보는 사람이 많을수록 유출 표면이 넓어진다.
 */
public final class MaskingUtils {

    private static final String EMPTY_MARK = "-";

    private MaskingUtils() {
    }

    /**
     * 이름: 첫 글자만 남긴다. {@code 홍길동 → 홍**}, {@code 김영 → 김*}.
     * 한 글자면 통째로 가린다(첫 글자만 남기면 원본 그대로가 되므로).
     */
    public static String maskName(String name) {
        if (isBlank(name)) {
            return EMPTY_MARK;
        }
        String s = name.trim();
        if (s.length() == 1) {
            return "*";
        }
        return s.charAt(0) + repeat('*', s.length() - 1);
    }

    /**
     * 환자 식별자: 앞 3자와 뒤 2자만 남긴다.
     * {@code PAT000001 → PAT****01}. 6자 이하면 전부 가린다.
     */
    public static String maskPatientId(String patientId) {
        if (isBlank(patientId)) {
            return EMPTY_MARK;
        }
        String s = patientId.trim();
        if (s.length() <= 6) {
            return repeat('*', s.length());
        }
        return s.substring(0, 3) + repeat('*', s.length() - 5) + s.substring(s.length() - 2);
    }

    /**
     * 생년월일: 연도만 남긴다. {@code 19850214 → 1985****}.
     * HL7 은 부분 날짜(연도만, 연월만)를 허용하므로 길이를 가정하지 않는다.
     */
    public static String maskBirthDate(String birthDate) {
        if (isBlank(birthDate)) {
            return EMPTY_MARK;
        }
        String s = birthDate.trim();
        if (s.length() <= 4) {
            return s;
        }
        return s.substring(0, 4) + repeat('*', s.length() - 4);
    }

    /**
     * 전화번호: 숫자만 추려 앞 3자리와 뒤 4자리를 남긴다.
     * {@code 010-0000-0001 → 010-****-0001}. 7자리 이하면 전부 가린다.
     */
    public static String maskPhone(String phone) {
        if (isBlank(phone)) {
            return EMPTY_MARK;
        }
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.length() <= 7) {
            return repeat('*', Math.max(1, digits.length()));
        }
        return digits.substring(0, 3) + "-****-" + digits.substring(digits.length() - 4);
    }

    /**
     * 주소: 앞 두 어절(보통 시/도 + 시/군/구)까지만 남긴다.
     * {@code 서울특별시 종로구 세종대로 1 → 서울특별시 종로구 ***}.
     */
    public static String maskAddress(String address) {
        if (isBlank(address)) {
            return EMPTY_MARK;
        }
        String[] parts = address.trim().split("\\s+");
        if (parts.length <= 2) {
            return parts[0] + " ***";
        }
        return parts[0] + " " + parts[1] + " ***";
    }

    /**
     * 정해진 규칙이 없는 값을 통째로 가릴 때. 길이 정보만 남긴다.
     * 값 자체보다 "비어 있었는지"가 궁금한 경우가 많기 때문이다.
     */
    public static String maskAll(String value) {
        if (isBlank(value)) {
            return EMPTY_MARK;
        }
        return "***(" + value.trim().length() + ")";
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String repeat(char c, int count) {
        if (count <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
