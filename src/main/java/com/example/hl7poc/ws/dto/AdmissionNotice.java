package com.example.hl7poc.ws.dto;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlType;

import com.example.hl7poc.common.util.MaskingUtils;

/**
 * 병원 B 로 보내는 입퇴원 통보.
 *
 * <p><b>최소한만 보낸다.</b> HL7 원문을 통째로 넘기면 편하지만, 상대가 필요로 하지
 * 않는 항목(주소, 보험 정보, 담당의)까지 넘어간다. 유출 표면은 주고받는 항목 수에
 * 비례한다. 여기서는 병원 B 가 환자를 식별하고 입퇴원을 기록하는 데 필요한
 * 항목만 담는다.
 *
 * <p><b>이 객체는 평문 PHI 를 담는다.</b> DB 에는 암호문으로 들어가지만 상대
 * 시스템은 평문이 필요하다. 그래서 전송 구간 보호가 필수다 — 실제 운영에서는
 * HTTPS + 상호 인증(mTLS)이 아니면 이 연동을 열어서는 안 된다. 이 PoC 는 로컬
 * HTTP 를 쓰며, 그것이 PoC 인 이유다.
 *
 * <p>날짜를 {@code XMLGregorianCalendar} 가 아니라 문자열로 두었다. HL7 이 부분
 * 날짜를 허용하는데 달력 타입으로 바꾸면 없는 정보를 지어내게 된다(4단계와 같은
 * 이유). 실무에서는 상대와 합의한 포맷을 문자열로 주고받는 편이 사고가 적다.
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "AdmissionNotice", propOrder = {
        "messageControlId", "sendingFacility", "triggerEvent",
        "patientId", "patientName", "birthDate", "sex",
        "patientClass", "assignedLocation", "admitDateTime", "dischargeDateTime"})
public class AdmissionNotice {

    /** 원본 MSH-10. 병원 B 쪽에서도 멱등성 키로 쓸 수 있게 넘긴다. */
    private String messageControlId;
    /** 원본 MSH-4. Control ID 는 기관 안에서만 유일하므로 함께 넘겨야 의미가 있다. */
    private String sendingFacility;
    /** A01(입원) / A03(퇴원) */
    private String triggerEvent;

    private String patientId;
    private String patientName;
    private String birthDate;
    private String sex;

    private String patientClass;
    private String assignedLocation;
    private String admitDateTime;
    private String dischargeDateTime;

    public String getMessageControlId() { return messageControlId; }
    public void setMessageControlId(String v) { this.messageControlId = v; }

    public String getSendingFacility() { return sendingFacility; }
    public void setSendingFacility(String v) { this.sendingFacility = v; }

    public String getTriggerEvent() { return triggerEvent; }
    public void setTriggerEvent(String v) { this.triggerEvent = v; }

    public String getPatientId() { return patientId; }
    public void setPatientId(String v) { this.patientId = v; }

    public String getPatientName() { return patientName; }
    public void setPatientName(String v) { this.patientName = v; }

    public String getBirthDate() { return birthDate; }
    public void setBirthDate(String v) { this.birthDate = v; }

    public String getSex() { return sex; }
    public void setSex(String v) { this.sex = v; }

    public String getPatientClass() { return patientClass; }
    public void setPatientClass(String v) { this.patientClass = v; }

    public String getAssignedLocation() { return assignedLocation; }
    public void setAssignedLocation(String v) { this.assignedLocation = v; }

    public String getAdmitDateTime() { return admitDateTime; }
    public void setAdmitDateTime(String v) { this.admitDateTime = v; }

    public String getDischargeDateTime() { return dischargeDateTime; }
    public void setDischargeDateTime(String v) { this.dischargeDateTime = v; }

    /**
     * 항상 마스킹한다. SOAP 요청/응답을 로그로 남기는 습관이 흔한데,
     * 그 로그가 평문 PHI 저장소가 되어 버린다.
     */
    @Override
    public String toString() {
        return "AdmissionNotice{ctrlId=" + messageControlId
                + ", facility=" + sendingFacility
                + ", trigger=" + triggerEvent
                + ", patientId=" + MaskingUtils.maskPatientId(patientId)
                + ", name=" + MaskingUtils.maskName(patientName)
                + ", dob=" + MaskingUtils.maskBirthDate(birthDate)
                + ", class=" + patientClass
                + ", location=" + assignedLocation
                + "}";
    }
}
