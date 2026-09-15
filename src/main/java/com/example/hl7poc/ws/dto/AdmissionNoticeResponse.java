package com.example.hl7poc.ws.dto;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlType;

/**
 * 병원 B 의 응답.
 *
 * <p><b>업무적 거절을 SOAP Fault 가 아니라 정상 응답의 상태값으로 표현한다.</b>
 * Fault 는 "요청을 처리할 수 없었다"는 프로토콜 수준 신호라 전송 오류와 구분이
 * 흐려진다. "없는 환자입니다"는 상대가 정상적으로 판단한 결과이므로 200 응답에
 * 담는 편이 클라이언트가 재시도 여부를 가르기 쉽다.
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "AdmissionNoticeResponse", propOrder = {"status", "receiptId", "reason"})
public class AdmissionNoticeResponse {

    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_REJECTED = "REJECTED";

    private String status;
    /** 병원 B 가 부여한 접수 번호. 추적용. */
    private String receiptId;
    /** 거절 사유. 환자 정보를 넣지 않는다 — 우리 로그에 그대로 쌓인다. */
    private String reason;

    public AdmissionNoticeResponse() {
    }

    public static AdmissionNoticeResponse accepted(String receiptId) {
        AdmissionNoticeResponse r = new AdmissionNoticeResponse();
        r.status = STATUS_ACCEPTED;
        r.receiptId = receiptId;
        return r;
    }

    public static AdmissionNoticeResponse rejected(String reason) {
        AdmissionNoticeResponse r = new AdmissionNoticeResponse();
        r.status = STATUS_REJECTED;
        r.reason = reason;
        return r;
    }

    public boolean isAccepted() {
        return STATUS_ACCEPTED.equals(status);
    }

    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }

    public String getReceiptId() { return receiptId; }
    public void setReceiptId(String v) { this.receiptId = v; }

    public String getReason() { return reason; }
    public void setReason(String v) { this.reason = v; }

    @Override
    public String toString() {
        return "AdmissionNoticeResponse{status=" + status
                + ", receiptId=" + receiptId
                + ", reason=" + reason + "}";
    }
}
