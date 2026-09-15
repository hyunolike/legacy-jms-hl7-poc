package com.example.hl7poc.adt.domain;

import java.util.Date;

/**
 * {@code adt_message} 테이블 한 행.
 *
 * <p>PHI 필드는 이 객체에 <b>이미 암호화된 상태</b>로 들어온다. 서비스 계층이
 * 암호화한 뒤 이 객체를 만들고, DAO 는 받은 값을 그대로 넣는다. DAO 가 암호화를
 * 맡으면 "암호화를 거치지 않는 경로"가 생기기 쉽다.
 */
public class AdtMessageRecord {

    private Long id;

    private String sendingApplication;
    private String sendingFacility;
    private String receivingApplication;
    private String receivingFacility;
    private Date messageDateTime;
    private String messageType;
    private String triggerEvent;
    private String messageControlId;
    private String processingId;
    private String hl7Version;

    /** HMAC 블라인드 인덱스 (검색용) */
    private String patientIdHash;
    /** 아래 네 개는 암호문이다 */
    private String patientIdEnc;
    private String patientNameEnc;
    private String patientDobEnc;
    private String patientPhoneEnc;
    /** 단독 식별성이 낮아 평문 허용 */
    private String patientSex;

    private String patientClass;
    private String assignedLocation;
    private Date admitDateTime;
    private Date dischargeDateTime;

    private String rawMsgSha256;
    private ProcessStatus status;
    private String lastError;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSendingApplication() { return sendingApplication; }
    public void setSendingApplication(String v) { this.sendingApplication = v; }

    public String getSendingFacility() { return sendingFacility; }
    public void setSendingFacility(String v) { this.sendingFacility = v; }

    public String getReceivingApplication() { return receivingApplication; }
    public void setReceivingApplication(String v) { this.receivingApplication = v; }

    public String getReceivingFacility() { return receivingFacility; }
    public void setReceivingFacility(String v) { this.receivingFacility = v; }

    public Date getMessageDateTime() { return copy(messageDateTime); }
    public void setMessageDateTime(Date v) { this.messageDateTime = copy(v); }

    public String getMessageType() { return messageType; }
    public void setMessageType(String v) { this.messageType = v; }

    public String getTriggerEvent() { return triggerEvent; }
    public void setTriggerEvent(String v) { this.triggerEvent = v; }

    public String getMessageControlId() { return messageControlId; }
    public void setMessageControlId(String v) { this.messageControlId = v; }

    public String getProcessingId() { return processingId; }
    public void setProcessingId(String v) { this.processingId = v; }

    public String getHl7Version() { return hl7Version; }
    public void setHl7Version(String v) { this.hl7Version = v; }

    public String getPatientIdHash() { return patientIdHash; }
    public void setPatientIdHash(String v) { this.patientIdHash = v; }

    public String getPatientIdEnc() { return patientIdEnc; }
    public void setPatientIdEnc(String v) { this.patientIdEnc = v; }

    public String getPatientNameEnc() { return patientNameEnc; }
    public void setPatientNameEnc(String v) { this.patientNameEnc = v; }

    public String getPatientDobEnc() { return patientDobEnc; }
    public void setPatientDobEnc(String v) { this.patientDobEnc = v; }

    public String getPatientPhoneEnc() { return patientPhoneEnc; }
    public void setPatientPhoneEnc(String v) { this.patientPhoneEnc = v; }

    public String getPatientSex() { return patientSex; }
    public void setPatientSex(String v) { this.patientSex = v; }

    public String getPatientClass() { return patientClass; }
    public void setPatientClass(String v) { this.patientClass = v; }

    public String getAssignedLocation() { return assignedLocation; }
    public void setAssignedLocation(String v) { this.assignedLocation = v; }

    public Date getAdmitDateTime() { return copy(admitDateTime); }
    public void setAdmitDateTime(Date v) { this.admitDateTime = copy(v); }

    public Date getDischargeDateTime() { return copy(dischargeDateTime); }
    public void setDischargeDateTime(Date v) { this.dischargeDateTime = copy(v); }

    public String getRawMsgSha256() { return rawMsgSha256; }
    public void setRawMsgSha256(String v) { this.rawMsgSha256 = v; }

    public ProcessStatus getStatus() { return status; }
    public void setStatus(ProcessStatus v) { this.status = v; }

    public String getLastError() { return lastError; }
    public void setLastError(String v) { this.lastError = v; }

    private static Date copy(Date d) {
        return (d == null) ? null : new Date(d.getTime());
    }

    /** 암호문이나 해시 전체를 로그에 흘리지 않는다. */
    @Override
    public String toString() {
        return "AdtMessageRecord{id=" + id
                + ", ctrlId=" + messageControlId
                + ", type=" + messageType
                + ", facility=" + sendingFacility
                + ", status=" + status
                + ", ptKey=" + (patientIdHash == null ? "-" : patientIdHash.substring(0, 8))
                + "}";
    }
}
