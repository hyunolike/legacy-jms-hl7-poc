package com.example.hl7poc.common.hl7;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.model.v25.message.ACK;
import ca.uhn.hl7v2.parser.PipeParser;
import ca.uhn.hl7v2.util.Terser;
import ca.uhn.hl7v2.validation.impl.ValidationContextFactory;

/**
 * HAPI 기반 {@link AckBuilder} 구현. HL7 v2.5 ACK 를 만든다.
 *
 * <p>HAPI 의 {@code generateACK()} 를 쓰지 않고 직접 조립한다. 자동 생성은 원본
 * MSH 를 뒤집어 주는 편의가 있지만, MSH-3/4(우리 시스템 식별자)와 ERR 세그먼트를
 * 우리 규칙대로 채우려면 어차피 Terser 로 다시 손대야 한다. 그럴 바에는 처음부터
 * 명시적으로 만드는 편이 무엇이 들어가는지 분명하다.
 *
 * <p>스레드 세이프하다. 상태를 갖는 것은 설정값 두 개뿐이고 기동 후 바뀌지 않는다.
 */
public class HapiAckBuilder implements AckBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(HapiAckBuilder.class);

    /** MSH-10 을 못 읽었을 때 MSA-2 에 넣을 값. */
    static final String UNKNOWN_CONTROL_ID = "UNKNOWN";

    /**
     * MSH-9.2(트리거)를 모를 때 쓸 값. HL7 에 "알 수 없음" 트리거는 정의돼 있지 않다.
     * 비워 두면 HAPI 가 인코딩 단계에서 터지므로 부득이한 자리 채움이다.
     */
    static final String UNKNOWN_TRIGGER_EVENT = "R01";

    private static final String HL7_VERSION = "2.5";
    private static final String ERROR_CODE_TABLE = "HL70357";

    /**
     * 우리 내부 오류 코드 → HL7 표준 오류 코드(테이블 0357) 매핑.
     *
     * <p>ERR-3 에 내부 코드를 그대로 넣는 구현을 자주 보는데, 상대 시스템은 그 값을
     * 해석할 수 없다. 표준 코드를 ERR-3 에 넣고, 우리 코드는 ERR-8(사용자 메시지)에
     * 남겨 양쪽이 다 읽을 수 있게 한다.
     */
    private static final Map<String, String[]> ERROR_CODE_MAP;
    static {
        Map<String, String[]> m = new HashMap<>();
        m.put("HL7-PARSE",               new String[] {"102", "Data type error"});
        m.put("HL7-NO-CONTROL-ID",       new String[] {"101", "Required field missing"});
        m.put("HL7-NO-TRIGGER",          new String[] {"101", "Required field missing"});
        m.put("HL7-NO-PATIENT-ID",       new String[] {"101", "Required field missing"});
        m.put("HL7-UNSUPPORTED-TRIGGER", new String[] {"201", "Unsupported event code"});
        m.put("HL7-TS-FORMAT",           new String[] {"102", "Data type error"});
        m.put("HL7-BODY-READ",           new String[] {"100", "Segment sequence error"});
        m.put("ADT-NO-ADMIT-TIME",       new String[] {"101", "Required field missing"});
        m.put("ADT-NO-DISCHARGE-TIME",   new String[] {"101", "Required field missing"});
        m.put("WS-REJECTED",             new String[] {"207", "Application internal error"});
        ERROR_CODE_MAP = Collections.unmodifiableMap(m);
    }

    private static final String[] DEFAULT_ERROR = {"207", "Application internal error"};

    private final HapiContext hapiContext;

    /**
     * ACK 인코딩 전용 파서. 검증을 끈다.
     *
     * <p>두 가지 이유다.
     * <ol>
     *   <li><b>실질적</b>: ACK 는 우리가 알려진 값으로 조립한 것이라 스스로 검증할
     *       실익이 거의 없다. 검증이 실패하면 상대는 아무 응답도 못 받고 재전송을
     *       반복한다. 조금 덜 규격적인 ACK 라도 보내는 편이 낫다.</li>
     *   <li><b>실제 제약</b>: HAPI 는 MSH-9.2(트리거 이벤트)가 비어 있으면 인코딩
     *       시점의 메시지 규칙 조회에서 {@code NoSuchElementException} 을 낸다.
     *       그런데 파싱 실패 ACK 가 바로 그 경우다 — 트리거를 모르는 상황에서
     *       응답해야 하는데, 정작 그때 인코딩이 막힌다.</li>
     * </ol>
     */
    private final PipeParser encodeParser;

    private String sendingApplication = "HL7POC_BRIDGE";
    private String sendingFacility = "HOSP_BRIDGE";

    public HapiAckBuilder(HapiContext hapiContext) {
        this.hapiContext = hapiContext;
        this.encodeParser = new PipeParser();
        this.encodeParser.setValidationContext(ValidationContextFactory.noValidation());
    }

    public void setSendingApplication(String sendingApplication) {
        this.sendingApplication = sendingApplication;
    }

    public void setSendingFacility(String sendingFacility) {
        this.sendingFacility = sendingFacility;
    }

    // ------------------------------------------------------------------

    @Override
    public String buildAck(Hl7MessageContext header, AckCode ackCode,
                           String errorCode, String errorText) {
        if (header == null) {
            throw new IllegalArgumentException("헤더 없이는 ACK 를 만들 수 없습니다.");
        }
        return encode(build(
                header.getMessageControlId(),
                header.getTriggerEvent(),
                header.getProcessingId(),
                // 응답은 요청의 반대 방향으로 간다. 상대의 송신 정보가 우리의 수신 정보다.
                header.getSendingApplication(),
                header.getSendingFacility(),
                ackCode, errorCode, errorText));
    }

    @Override
    public String buildRejectAck(String rawMessage, String errorCode, String errorText) {
        // MSH 를 제대로 못 읽었어도 MSA-2 는 채워야 한다. 비워 두면 상대가 어느
        // 메시지에 대한 응답인지 알 수 없어, 결국 재전송을 반복한다.
        String controlId = bestEffortControlId(rawMessage);
        if (UNKNOWN_CONTROL_ID.equals(controlId)) {
            LOG.warn("MSH-10 을 추출하지 못했습니다. MSA-2 에 {} 를 넣습니다."
                    + " 상대가 이 응답을 원본과 연결하지 못할 수 있습니다.", UNKNOWN_CONTROL_ID);
        }
        // 트리거도 건질 수 있으면 건진다. ACK 가 조금이라도 더 많은 단서를 담는다.
        return encode(build(controlId, bestEffortTriggerEvent(rawMessage), null, null, null,
                AckCode.AR, errorCode, errorText));
    }

    // ------------------------------------------------------------------

    private ACK build(String originalControlId, String triggerEvent, String processingId,
                      String originalSendingApp, String originalSendingFacility,
                      AckCode ackCode, String errorCode, String errorText) {
        try {
            ACK ack = new ACK();
            ack.setParser(encodeParser);

            // MSH-9 / MSH-10 / MSH-7 / MSH-12 를 채워 준다.
            // MSH-10 은 ACK 자신의 Control ID 다(원본 것이 아니다 — 흔한 혼동 지점).
            ack.initQuickstart("ACK",
                    // 비워 두면 HAPI 의 메시지 규칙 조회가 터진다(위 encodeParser 주석).
                    (triggerEvent == null || triggerEvent.isEmpty())
                            ? UNKNOWN_TRIGGER_EVENT : triggerEvent,
                    (processingId == null) ? "P" : processingId);

            Terser t = new Terser(ack);
            t.set("/MSH-3-1", sendingApplication);
            t.set("/MSH-4-1", sendingFacility);
            t.set("/MSH-5-1", originalSendingApp);
            t.set("/MSH-6-1", originalSendingFacility);
            t.set("/MSH-12-1", HL7_VERSION);

            t.set("/MSA-1", ackCode.name());
            t.set("/MSA-2", (originalControlId == null) ? UNKNOWN_CONTROL_ID : originalControlId);

            if (ackCode != AckCode.AA) {
                String[] mapped = ERROR_CODE_MAP.get(errorCode);
                if (mapped == null) {
                    mapped = DEFAULT_ERROR;
                }
                t.set("/ERR-3-1", mapped[0]);
                t.set("/ERR-3-2", mapped[1]);
                t.set("/ERR-3-3", ERROR_CODE_TABLE);
                t.set("/ERR-4", "E");
                // 우리 코드와 설명은 사용자 메시지로. 상대 담당자가 읽을 유일한 단서다.
                t.set("/ERR-8", sanitize(errorCode, errorText));
            }
            return ack;

        } catch (HL7Exception | IOException e) {
            // initQuickstart 는 HL7 버전별 구조 정의를 읽느라 IOException 도 던진다.
            throw new IllegalStateException("ACK 생성에 실패했습니다.", e);
        }
    }

    private String encode(ACK ack) {
        try {
            return encodeParser.encode(ack);
        } catch (HL7Exception e) {
            throw new IllegalStateException("ACK 인코딩에 실패했습니다.", e);
        }
    }

    /** MSH-9.2(트리거 이벤트)를 최대한 건져 낸다. 못 건지면 null. */
    static String bestEffortTriggerEvent(String rawMessage) {
        String[] fields = mshFields(rawMessage);
        if (fields == null || fields.length <= 8) {
            return null;
        }
        String[] components = fields[8].split("\\^", -1);
        if (components.length < 2 || components[1].trim().isEmpty()) {
            return null;
        }
        return components[1].trim();
    }

    /**
     * 구분자가 살아 있으면 MSH-10 은 건질 수 있는 경우가 많다.
     * MSH 세그먼트가 통째로 깨진 경우에만 {@link #UNKNOWN_CONTROL_ID} 를 쓴다.
     */
    static String bestEffortControlId(String rawMessage) {
        String[] fields = mshFields(rawMessage);
        if (fields == null || fields.length <= 9) {
            return UNKNOWN_CONTROL_ID;
        }
        String candidate = fields[9].trim();
        return candidate.isEmpty() ? UNKNOWN_CONTROL_ID : candidate;
    }

    /**
     * 첫 줄이 MSH 세그먼트면 파이프로 잘라 돌려준다. 아니면 null.
     *
     * <pre>
     *   MSH|^~\&amp;|앱|기관|앱|기관|시각|보안|타입|ControlId|...
     *    [0] [1]  [2] [3] [4] [5]  [6]  [7]  [8]   [9]
     * </pre>
     */
    private static String[] mshFields(String rawMessage) {
        if (rawMessage == null) {
            return null;
        }
        String firstLine = rawMessage.replace("\r\n", "\n").replace('\r', '\n');
        int nl = firstLine.indexOf('\n');
        if (nl >= 0) {
            firstLine = firstLine.substring(0, nl);
        }
        if (!firstLine.startsWith("MSH")) {
            return null;
        }
        return firstLine.split("\\|", -1);
    }

    /**
     * ERR-8 로 나가는 문구를 정리한다.
     *
     * <p>ACK 는 네트워크로 나가 상대 시스템 로그에 그대로 쌓인다. 환자 정보가
     * 섞이면 우리가 통제할 수 없는 곳에 PHI 사본이 생긴다. 파서가 예외 메시지에
     * 원문을 넣지 않도록 이미 막아 두었지만(4단계), 여기서 한 번 더 길이를 자른다.
     */
    private static String sanitize(String errorCode, String errorText) {
        StringBuilder sb = new StringBuilder();
        if (errorCode != null) {
            sb.append('[').append(errorCode).append("] ");
        }
        if (errorText != null) {
            sb.append(errorText);
        }
        String s = sb.toString().replace('\r', ' ').replace('\n', ' ').trim();
        final int max = 250;
        return (s.length() <= max) ? s : s.substring(0, max);
    }
}
