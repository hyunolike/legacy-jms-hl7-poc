package com.example.hl7poc.common.hl7;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.parser.PipeParser;
import ca.uhn.hl7v2.util.Terser;
import ca.uhn.hl7v2.validation.impl.ValidationContextFactory;

import com.example.hl7poc.common.dto.AdtEvent;
import com.example.hl7poc.common.dto.PatientInfo;
import com.example.hl7poc.common.exception.Hl7ParseException;
import com.example.hl7poc.common.exception.PermanentProcessingException;

/**
 * HAPI HL7v2 기반 {@link Hl7Parser} 구현.
 *
 * <p>HAPI 의존이 프로젝트에서 이 클래스와 {@code AckBuilder} 안에만 있도록 한다.
 * {@link Terser} 로 경로 문자열을 써서 읽는데, 생성된 모델 클래스
 * ({@code ADT_A01#getPID()}) 를 직접 쓰는 것보다 구조 변화에 강하다. 실제 병원
 * 시스템은 규격에서 조금씩 벗어난 메시지를 보내기 때문이다.
 *
 * <p>스레드 세이프하다. {@link PipeParser} 와 {@link HapiContext} 는 재사용 가능하고,
 * 상태를 가진 {@code SimpleDateFormat} 류는 {@link Hl7Dates} 가 매번 새로 만든다.
 */
public class HapiHl7Parser implements Hl7Parser {

    private static final Logger LOG = LoggerFactory.getLogger(HapiHl7Parser.class);

    /** MLLP 프레이밍 바이트. 소켓에서 바로 넘어온 메시지에 붙어 있는 경우가 있다. */
    private static final char MLLP_START = 0x0B;
    private static final char MLLP_END = 0x1C;
    private static final char BOM = '﻿';

    private final HapiContext hapiContext;
    private final PipeParser pipeParser;

    /** 이 브리지가 처리하는 트리거 이벤트. 나머지는 AE 로 거절한다. */
    private Set<String> supportedTriggerEvents =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList("A01", "A03")));

    public HapiHl7Parser(HapiContext hapiContext) {
        this.hapiContext = hapiContext;
        this.pipeParser = hapiContext.getPipeParser();
    }

    /**
     * HAPI 검증을 켤지 여부.
     *
     * <p>끄면 규격에서 벗어난 메시지도 일단 받아들인다. 실제 연동 초기에는
     * 상대 시스템이 규격을 덜 지키는 경우가 많아 끄고 시작하는 일이 흔한데,
     * 그러면 잘못된 값이 그대로 DB 까지 내려간다. 기본값은 켜 둔다.
     */
    public void setValidationEnabled(boolean validationEnabled) {
        if (validationEnabled) {
            hapiContext.setValidationContext(ValidationContextFactory.defaultValidation());
        } else {
            hapiContext.setValidationContext(ValidationContextFactory.noValidation());
            LOG.warn("HL7 검증이 꺼져 있습니다. 규격 위반 메시지가 그대로 통과합니다.");
        }
    }

    public void setSupportedTriggerEvents(List<String> events) {
        this.supportedTriggerEvents =
                Collections.unmodifiableSet(new HashSet<>(events));
    }

    // ------------------------------------------------------------------
    // 1단계: 헤더
    // ------------------------------------------------------------------

    @Override
    public Hl7MessageContext parseHeader(String rawMessage) {
        if (rawMessage == null || rawMessage.trim().isEmpty()) {
            throw new Hl7ParseException("빈 메시지입니다.");
        }
        final String normalized = normalize(rawMessage);

        final Message message;
        try {
            message = pipeParser.parse(normalized);
        } catch (HL7Exception e) {
            // 원문을 예외 메시지에 넣지 않는다. 예외는 대개 그대로 로그로 나가고,
            // 원문에는 환자 정보가 들어 있다.
            throw new Hl7ParseException("HL7 구조를 해석할 수 없습니다: " + e.getMessage(), e);
        }

        try {
            Terser t = new Terser(message);
            String msgCode = trimToNull(t.get("/MSH-9-1"));
            String trigger = trimToNull(t.get("/MSH-9-2"));

            return new Hl7MessageContext.Builder()
                    .rawMessage(normalized)
                    .rawSha256(sha256Hex(normalized))
                    .sendingApplication(trimToNull(t.get("/MSH-3-1")))
                    .sendingFacility(trimToNull(t.get("/MSH-4-1")))
                    .receivingApplication(trimToNull(t.get("/MSH-5-1")))
                    .receivingFacility(trimToNull(t.get("/MSH-6-1")))
                    .messageDateTime(Hl7Dates.parse(t.get("/MSH-7")))
                    .messageType(join(msgCode, trigger))
                    .triggerEvent(trigger)
                    .messageControlId(trimToNull(t.get("/MSH-10")))
                    .processingId(trimToNull(t.get("/MSH-11-1")))
                    .version(trimToNull(t.get("/MSH-12-1")))
                    .sourceMessage(message)
                    .build();

        } catch (HL7Exception e) {
            throw new Hl7ParseException("MSH 세그먼트를 읽을 수 없습니다: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // 2단계: 본문
    // ------------------------------------------------------------------

    @Override
    public AdtEvent toAdtEvent(Hl7MessageContext context) {
        requireHeader(context);

        final String trigger = context.getTriggerEvent();
        if (trigger == null) {
            throw new PermanentProcessingException("HL7-NO-TRIGGER",
                    "MSH-9.2(트리거 이벤트)가 비어 있습니다.");
        }
        if (!supportedTriggerEvents.contains(trigger)) {
            throw new PermanentProcessingException("HL7-UNSUPPORTED-TRIGGER",
                    "지원하지 않는 트리거 이벤트입니다: " + trigger
                            + " (지원: " + supportedTriggerEvents + ")");
        }

        try {
            Terser t = new Terser(context.getSourceMessage());

            // PID 세그먼트 자체가 없으면 Terser 는 예외가 아니라 null 을 준다.
            // 없는 것과 비어 있는 것을 구분하지 않으므로 필수 값으로 판단한다.
            String patientId = trimToNull(t.get("/PID-3-1"));
            if (patientId == null) {
                throw new PermanentProcessingException("HL7-NO-PATIENT-ID",
                        "PID-3(환자 식별자)가 없습니다. ADT 메시지로 처리할 수 없습니다.");
            }

            PatientInfo patient = new PatientInfo(
                    patientId,
                    trimToNull(t.get("/PID-5-1")),
                    trimToNull(t.get("/PID-5-2")),
                    trimToNull(t.get("/PID-7")),
                    trimToNull(t.get("/PID-8")),
                    joinAddress(t),
                    trimToNull(t.get("/PID-13-1")));

            AdtEvent event = new AdtEvent(
                    context,
                    patient,
                    trimToNull(t.get("/PV1-2")),
                    joinLocation(t),
                    trimToNull(t.get("/PV1-19-1")),
                    Hl7Dates.parse(t.get("/PV1-44")),
                    Hl7Dates.parse(t.get("/PV1-45")));

            validateBusinessRules(event);
            return event;

        } catch (HL7Exception e) {
            throw new PermanentProcessingException("HL7-BODY-READ",
                    "본문 세그먼트를 읽을 수 없습니다: " + e.getMessage(), e);
        }
    }

    /**
     * 트리거별 필수 값. HAPI 의 구조 검증은 "세그먼트가 있는가"까지만 보고,
     * "퇴원 메시지에 퇴원 시각이 있는가" 같은 업무 규칙은 보지 않는다.
     */
    private void validateBusinessRules(AdtEvent event) {
        if (event.isAdmission() && event.getAdmitDateTime() == null) {
            throw new PermanentProcessingException("ADT-NO-ADMIT-TIME",
                    "A01(입원) 메시지에 PV1-44(입원 시각)가 없습니다.");
        }
        if (event.isDischarge() && event.getDischargeDateTime() == null) {
            throw new PermanentProcessingException("ADT-NO-DISCHARGE-TIME",
                    "A03(퇴원) 메시지에 PV1-45(퇴원 시각)가 없습니다.");
        }
    }

    private void requireHeader(Hl7MessageContext context) {
        if (context == null || context.getSourceMessage() == null) {
            throw new IllegalArgumentException("parseHeader 결과가 필요합니다.");
        }
        if (context.getMessageControlId() == null) {
            // MSH-10 이 없으면 멱등성 키를 만들 수 없다. 처리 자체가 불가능하다.
            throw new PermanentProcessingException("HL7-NO-CONTROL-ID",
                    "MSH-10(Message Control ID)이 비어 있습니다.");
        }
    }

    // ------------------------------------------------------------------
    // 정규화
    // ------------------------------------------------------------------

    /**
     * HL7 의 세그먼트 구분자는 CR({@code \r})이다. 그런데 파일로 주고받거나
     * 텍스트 에디터를 거치면 LF 나 CRLF 로 바뀌어 온다. 이 정규화를 빼먹으면
     * "우리 쪽에서는 되는데 상대가 보낸 건 안 되는" 문제가 생긴다.
     *
     * <p>MLLP 프레이밍 바이트와 BOM 도 함께 걷어낸다.
     */
    static String normalize(String raw) {
        String s = raw;
        if (!s.isEmpty() && s.charAt(0) == BOM) {
            s = s.substring(1);
        }
        s = s.replace(String.valueOf(MLLP_START), "")
             .replace(String.valueOf(MLLP_END), "");
        s = s.replace("\r\n", "\r").replace('\n', '\r');
        // 앞쪽 공백/개행은 MSH 인식을 방해한다. 뒤쪽 개행은 HAPI 가 알아서 무시한다.
        while (!s.isEmpty() && (s.charAt(0) == '\r' || s.charAt(0) == ' ')) {
            s = s.substring(1);
        }
        return s;
    }

    // ------------------------------------------------------------------
    // 보조
    // ------------------------------------------------------------------

    private String joinAddress(Terser t) throws HL7Exception {
        // XAD: 도로명^기타^도시^주^우편번호^국가
        String street = trimToNull(t.get("/PID-11-1"));
        String city = trimToNull(t.get("/PID-11-3"));
        String zip = trimToNull(t.get("/PID-11-5"));
        StringBuilder sb = new StringBuilder();
        appendIfPresent(sb, street);
        appendIfPresent(sb, city);
        appendIfPresent(sb, zip);
        return (sb.length() == 0) ? null : sb.toString();
    }

    private String joinLocation(Terser t) throws HL7Exception {
        // PL: 병동^병실^병상
        String point = trimToNull(t.get("/PV1-3-1"));
        String room = trimToNull(t.get("/PV1-3-2"));
        String bed = trimToNull(t.get("/PV1-3-3"));
        StringBuilder sb = new StringBuilder();
        appendIfPresent(sb, point);
        appendIfPresent(sb, room);
        appendIfPresent(sb, bed);
        return (sb.length() == 0) ? null : sb.toString();
    }

    private static void appendIfPresent(StringBuilder sb, String value) {
        if (value == null) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(value);
    }

    private static String join(String code, String trigger) {
        if (code == null) {
            return null;
        }
        return (trigger == null) ? code : code + "^" + trigger;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * 원문 지문. 원문 자체는 저장하지 않지만 "같은 바이트였는지"는 확인할 수 있어야
     * 한다(재처리 시 원문이 바뀌지 않았음을 보이는 용도).
     */
    static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
            // SHA-256 과 UTF-8 은 모든 JVM 이 지원하도록 규격에 박혀 있다.
            throw new IllegalStateException("SHA-256/UTF-8 을 쓸 수 없습니다.", e);
        }
    }
}
