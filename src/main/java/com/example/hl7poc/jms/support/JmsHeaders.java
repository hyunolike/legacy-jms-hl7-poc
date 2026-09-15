package com.example.hl7poc.jms.support;

/**
 * 커스텀 JMS 헤더 이름.
 *
 * <p>JMS 프로퍼티 이름에는 하이픈을 쓸 수 없다(식별자 규칙을 따른다). 브로커나
 * selector 문법에 따라 조용히 무시되거나 예외가 나므로 밑줄을 쓴다.
 */
public final class JmsHeaders {

    /** 원본 MSH-10. 요청/ACK/PARK/DLQ 전 구간에서 상관관계 키로 쓴다. */
    public static final String MSG_CONTROL_ID = "X_MSG_CONTROL_ID";

    /** 송신 기관(MSH-4). 멱등성 키의 나머지 절반이다. */
    public static final String SENDING_FACILITY = "X_SENDING_FACILITY";

    /** 격리/DLQ 로 보낸 이유 코드. */
    public static final String ERROR_CODE = "X_ERROR_CODE";

    /** 사람이 읽을 오류 설명(마스킹된 값만). */
    public static final String ERROR_TEXT = "X_ERROR_TEXT";

    /** DLQ 재처리 횟수. 무한 루프를 막는 데 쓴다(7단계). */
    public static final String REPROCESS_COUNT = "X_REPROCESS_COUNT";

    /**
     * ActiveMQ 메시지 그룹 키. 같은 값이면 같은 컨슈머로 고정되어 순서가 보장된다.
     * 이 이름은 ActiveMQ 규격이라 바꿀 수 없다.
     */
    public static final String GROUP_ID = "JMSXGroupID";

    private JmsHeaders() {
    }
}
