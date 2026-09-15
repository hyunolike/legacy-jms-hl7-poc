package com.example.hl7poc.common.util;

import org.slf4j.MDC;

/**
 * MSH-10 기준 추적 컨텍스트.
 *
 * <p>리스너 진입 직후 {@link #begin}, {@code finally} 에서 반드시 {@link #clear}.
 * 지우지 않으면 스레드 풀에서 다음 메시지가 <b>앞 메시지의 Control ID 를 달고</b>
 * 로그를 남긴다. 장애 조사를 통째로 망치는 버그이고, 평소에는 드러나지 않는다.
 *
 * <p>여기 넣는 값에는 환자 식별정보를 그대로 쓰지 않는다. patientKey 는 해시
 * 앞자리만 쓴다(같은 환자를 이어 보기에는 충분하고, 역산은 되지 않는다).
 */
public final class TraceContext {

    public static final String KEY_MSG_CONTROL_ID = "msgCtrlId";
    public static final String KEY_MSG_TYPE = "msgType";
    public static final String KEY_PATIENT_KEY = "patientKey";
    public static final String KEY_JMS_MESSAGE_ID = "jmsMsgId";

    private TraceContext() {
    }

    public static void begin(String jmsMessageId) {
        put(KEY_JMS_MESSAGE_ID, jmsMessageId);
    }

    /** MSH 를 읽은 직후 호출한다. 파싱 전에는 Control ID 를 알 수 없다. */
    public static void setMessage(String messageControlId, String messageType) {
        put(KEY_MSG_CONTROL_ID, messageControlId);
        put(KEY_MSG_TYPE, messageType);
    }

    /** 환자 ID 해시의 앞 8자만 넣는다. */
    public static void setPatientKey(String patientIdHash) {
        if (patientIdHash == null || patientIdHash.isEmpty()) {
            return;
        }
        put(KEY_PATIENT_KEY, patientIdHash.substring(0, Math.min(8, patientIdHash.length())));
    }

    public static String currentMessageControlId() {
        return MDC.get(KEY_MSG_CONTROL_ID);
    }

    public static void clear() {
        MDC.remove(KEY_MSG_CONTROL_ID);
        MDC.remove(KEY_MSG_TYPE);
        MDC.remove(KEY_PATIENT_KEY);
        MDC.remove(KEY_JMS_MESSAGE_ID);
    }

    private static void put(String key, String value) {
        if (value == null || value.isEmpty()) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }
}
