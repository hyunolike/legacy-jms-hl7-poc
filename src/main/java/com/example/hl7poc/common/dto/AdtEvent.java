package com.example.hl7poc.common.dto;

import java.util.Date;

import com.example.hl7poc.common.hl7.Hl7MessageContext;

/**
 * 파싱과 매핑이 끝난 ADT 이벤트. 업무 계층({@code adt})이 다루는 유일한 입력이다.
 *
 * <p>여기서부터는 HAPI 타입이 하나도 나오지 않는다. 파서를 다른 구현으로
 * 갈아끼워도 서비스/DAO 는 손대지 않는다.
 */
public final class AdtEvent {

    private final Hl7MessageContext header;
    private final PatientInfo patient;
    private final String patientClass;       // PV1-2  I(입원)/O(외래)/E(응급)
    private final String assignedLocation;   // PV1-3  병동^병실^병상
    private final String visitNumber;        // PV1-19
    private final Date admitDateTime;        // PV1-44
    private final Date dischargeDateTime;    // PV1-45

    public AdtEvent(Hl7MessageContext header, PatientInfo patient, String patientClass,
                    String assignedLocation, String visitNumber,
                    Date admitDateTime, Date dischargeDateTime) {
        this.header = header;
        this.patient = patient;
        this.patientClass = patientClass;
        this.assignedLocation = assignedLocation;
        this.visitNumber = visitNumber;
        this.admitDateTime = admitDateTime;
        this.dischargeDateTime = dischargeDateTime;
    }

    public Hl7MessageContext getHeader() { return header; }
    public PatientInfo getPatient() { return patient; }
    public String getPatientClass() { return patientClass; }
    public String getAssignedLocation() { return assignedLocation; }
    public String getVisitNumber() { return visitNumber; }
    public Date getAdmitDateTime() {
        return (admitDateTime == null) ? null : new Date(admitDateTime.getTime());
    }
    public Date getDischargeDateTime() {
        return (dischargeDateTime == null) ? null : new Date(dischargeDateTime.getTime());
    }

    /** 편의 메서드. 트리거 이벤트는 헤더에 있지만 업무 코드에서 자주 쓴다. */
    public String getTriggerEvent() {
        return header.getTriggerEvent();
    }

    public boolean isAdmission() {
        return "A01".equals(getTriggerEvent());
    }

    public boolean isDischarge() {
        return "A03".equals(getTriggerEvent());
    }

    @Override
    public String toString() {
        return "AdtEvent{" + header
                + ", trigger=" + getTriggerEvent()
                + ", class=" + patientClass
                + ", location=" + assignedLocation
                + ", patient=" + patient
                + "}";
    }
}
