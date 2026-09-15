package com.example.hl7poc.common.hl7;

import java.util.Date;

import ca.uhn.hl7v2.model.Message;

/**
 * MSH 세그먼트에서 읽어 낸 메시지 헤더.
 *
 * <p><b>본문(PID/PV1)과 분리한 이유</b>: 본문이 업무 규칙에 어긋나도 ACK 는 보내야
 * 하고, ACK 에는 MSH-10 이 반드시 들어간다. 헤더 파싱과 본문 매핑을 한 단계로
 * 묶으면 "본문이 잘못됐는데 ACK 도 못 보내는" 상태가 된다.
 *
 * <p>불변 객체다. 컨슈머가 여러 개 도는 구조에서 헤더 정보를 중간에 바꾸면
 * 추적이 불가능해진다.
 */
public final class Hl7MessageContext {

    private final String rawMessage;
    private final String rawSha256;

    private final String sendingApplication;    // MSH-3
    private final String sendingFacility;       // MSH-4
    private final String receivingApplication;  // MSH-5
    private final String receivingFacility;     // MSH-6
    private final Date messageDateTime;         // MSH-7
    private final String messageType;           // MSH-9.1^MSH-9.2
    private final String triggerEvent;          // MSH-9.2
    private final String messageControlId;      // MSH-10
    private final String processingId;          // MSH-11
    private final String version;               // MSH-12

    /**
     * HAPI 원본 메시지.
     *
     * <p>HAPI 의존을 {@code common.hl7} 밖으로 내보내지 않는 것이 이 PoC 의 규칙이라,
     * 이 필드는 같은 패키지의 {@code AckBuilder} 와 {@code AdtEventMapper} 만 쓴다.
     * {@code adt} / {@code secure} / {@code ws} 계층은 이 객체를 건드리지 않는다.
     */
    private final Message sourceMessage;

    Hl7MessageContext(Builder b) {
        this.rawMessage = b.rawMessage;
        this.rawSha256 = b.rawSha256;
        this.sendingApplication = b.sendingApplication;
        this.sendingFacility = b.sendingFacility;
        this.receivingApplication = b.receivingApplication;
        this.receivingFacility = b.receivingFacility;
        this.messageDateTime = b.messageDateTime;
        this.messageType = b.messageType;
        this.triggerEvent = b.triggerEvent;
        this.messageControlId = b.messageControlId;
        this.processingId = b.processingId;
        this.version = b.version;
        this.sourceMessage = b.sourceMessage;
    }

    public String getRawMessage() { return rawMessage; }
    public String getRawSha256() { return rawSha256; }
    public String getSendingApplication() { return sendingApplication; }
    public String getSendingFacility() { return sendingFacility; }
    public String getReceivingApplication() { return receivingApplication; }
    public String getReceivingFacility() { return receivingFacility; }
    public Date getMessageDateTime() {
        return (messageDateTime == null) ? null : new Date(messageDateTime.getTime());
    }
    public String getMessageType() { return messageType; }
    public String getTriggerEvent() { return triggerEvent; }
    public String getMessageControlId() { return messageControlId; }
    public String getProcessingId() { return processingId; }
    public String getVersion() { return version; }
    public Message getSourceMessage() { return sourceMessage; }

    /**
     * 로그용. 원문이나 환자 정보는 절대 넣지 않는다. 헤더만으로도 추적에 충분하다.
     */
    @Override
    public String toString() {
        return "Hl7MessageContext{ctrlId=" + messageControlId
                + ", type=" + messageType
                + ", facility=" + sendingFacility
                + ", version=" + version
                + ", sha256=" + (rawSha256 == null ? "-" : rawSha256.substring(0, 8))
                + "}";
    }

    public static final class Builder {
        private String rawMessage;
        private String rawSha256;
        private String sendingApplication;
        private String sendingFacility;
        private String receivingApplication;
        private String receivingFacility;
        private Date messageDateTime;
        private String messageType;
        private String triggerEvent;
        private String messageControlId;
        private String processingId;
        private String version;
        private Message sourceMessage;

        public Builder rawMessage(String v) { this.rawMessage = v; return this; }
        public Builder rawSha256(String v) { this.rawSha256 = v; return this; }
        public Builder sendingApplication(String v) { this.sendingApplication = v; return this; }
        public Builder sendingFacility(String v) { this.sendingFacility = v; return this; }
        public Builder receivingApplication(String v) { this.receivingApplication = v; return this; }
        public Builder receivingFacility(String v) { this.receivingFacility = v; return this; }
        public Builder messageDateTime(Date v) { this.messageDateTime = v; return this; }
        public Builder messageType(String v) { this.messageType = v; return this; }
        public Builder triggerEvent(String v) { this.triggerEvent = v; return this; }
        public Builder messageControlId(String v) { this.messageControlId = v; return this; }
        public Builder processingId(String v) { this.processingId = v; return this; }
        public Builder version(String v) { this.version = v; return this; }
        public Builder sourceMessage(Message v) { this.sourceMessage = v; return this; }

        public Hl7MessageContext build() {
            return new Hl7MessageContext(this);
        }
    }
}
