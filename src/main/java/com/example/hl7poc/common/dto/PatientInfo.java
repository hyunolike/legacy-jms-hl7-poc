package com.example.hl7poc.common.dto;

import com.example.hl7poc.common.util.MaskingUtils;

/**
 * PID 세그먼트에서 뽑은 환자 정보(평문).
 *
 * <p>이 객체는 <b>메모리 안에서만</b> 평문으로 존재한다. DB 로 갈 때는 secure
 * 계층이 암호화하고, 로그로 갈 때는 {@link #toString()} 이 마스킹한다.
 *
 * <p>생년월일을 {@code Date} 가 아니라 문자열로 두는 이유: HL7 은 부분 날짜
 * (연도만, 연월만)를 허용한다. {@code Date} 로 바꾸는 순간 없는 정보(1월 1일)를
 * 지어내게 되고, 그 값이 그대로 DB 에 적재된다.
 */
public final class PatientInfo {

    private final String patientId;   // PID-3.1
    private final String familyName;  // PID-5.1
    private final String givenName;   // PID-5.2
    private final String birthDate;   // PID-7 (원문 그대로)
    private final String sex;         // PID-8
    private final String address;     // PID-11 (조합된 문자열)
    private final String phone;       // PID-13.1

    public PatientInfo(String patientId, String familyName, String givenName,
                       String birthDate, String sex, String address, String phone) {
        this.patientId = patientId;
        this.familyName = familyName;
        this.givenName = givenName;
        this.birthDate = birthDate;
        this.sex = sex;
        this.address = address;
        this.phone = phone;
    }

    public String getPatientId() { return patientId; }
    public String getFamilyName() { return familyName; }
    public String getGivenName() { return givenName; }
    public String getBirthDate() { return birthDate; }
    public String getSex() { return sex; }
    public String getAddress() { return address; }
    public String getPhone() { return phone; }

    /** 성 + 이름. 한국식 표기라 구분자를 넣지 않는다. */
    public String getFullName() {
        String f = (familyName == null) ? "" : familyName;
        String g = (givenName == null) ? "" : givenName;
        return f + g;
    }

    /**
     * 로그에 그대로 찍혀도 안전하도록 항상 마스킹한다.
     *
     * <p>이게 왜 중요한가: 로그 한 줄을 {@code log.info("환자={}", patient)} 로
     * 짜는 순간 마스킹 호출을 잊는다. 사람이 매번 기억하는 대신 타입이 보장하게
     * 만드는 편이 안전하다.
     */
    @Override
    public String toString() {
        return "PatientInfo{id=" + MaskingUtils.maskPatientId(patientId)
                + ", name=" + MaskingUtils.maskName(getFullName())
                + ", dob=" + MaskingUtils.maskBirthDate(birthDate)
                + ", sex=" + (sex == null ? "-" : sex)
                + ", addr=" + MaskingUtils.maskAddress(address)
                + ", phone=" + MaskingUtils.maskPhone(phone)
                + "}";
    }
}
