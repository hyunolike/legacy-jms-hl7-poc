package com.example.hl7poc.jms.listener;

import javax.jms.BytesMessage;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.TextMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessagePostProcessor;

import com.example.hl7poc.adt.service.AdtProcessingService;
import com.example.hl7poc.common.dto.AdtEvent;
import com.example.hl7poc.common.dto.ProcessResult;
import com.example.hl7poc.common.exception.Hl7ParseException;
import com.example.hl7poc.common.exception.Hl7ProcessingException;
import com.example.hl7poc.common.exception.TransientProcessingException;
import com.example.hl7poc.common.hl7.AckBuilder;
import com.example.hl7poc.common.hl7.AckCode;
import com.example.hl7poc.common.hl7.Hl7MessageContext;
import com.example.hl7poc.common.hl7.Hl7Parser;
import com.example.hl7poc.common.util.TraceContext;
import com.example.hl7poc.jms.publisher.AckPublisher;
import com.example.hl7poc.jms.support.JmsHeaders;

/**
 * ADT 요청 큐 리스너. condb-secure 의 {@code onMessage()} 자리에 대응한다.
 *
 * <p><b>이 클래스의 유일한 책임은 오류 분류다.</b> 업무 로직은 서비스가, 파싱은
 * 파서가 한다. 여기서 정하는 것은 딱 하나 — "이 실패를 롤백해서 재시도할 것인가,
 * 격리하고 커밋할 것인가".
 *
 * <pre>
 *   Hl7ParseException            → PARK + AR + 커밋   (재시도 무의미)
 *   PermanentProcessingException → PARK + AE + 커밋   (재시도 무의미)
 *   TransientProcessingException → 예외 재전파 → 롤백 → 재전송 → DLQ
 *   그 밖의 RuntimeException      → 재전파 (버그일 수 있으므로 보수적으로 재시도)
 * </pre>
 *
 * <p><b>재시도 무의미한 오류를 롤백시키면 안 되는 이유</b>: 최대 재시도 횟수만큼
 * 같은 실패를 반복하고 결국 DLQ 에 쌓인다. CPU 와 로그를 태우면서 송신 측에는
 * 아무 응답도 못 준다. 이 구조에서 가장 흔한 설계 실수다.
 */
public class AdtMessageListener implements MessageListener {

    private static final Logger LOG = LoggerFactory.getLogger(AdtMessageListener.class);

    private final Hl7Parser hl7Parser;
    private final AdtProcessingService adtProcessingService;
    private final AckBuilder ackBuilder;
    private final AckPublisher ackPublisher;

    private JmsTemplate parkTemplate;
    private Destination parkQueue;

    public AdtMessageListener(Hl7Parser hl7Parser,
                              AdtProcessingService adtProcessingService,
                              AckBuilder ackBuilder,
                              AckPublisher ackPublisher) {
        this.hl7Parser = hl7Parser;
        this.adtProcessingService = adtProcessingService;
        this.ackBuilder = ackBuilder;
        this.ackPublisher = ackPublisher;
    }

    public void setParkTemplate(JmsTemplate parkTemplate) {
        this.parkTemplate = parkTemplate;
    }

    public void setParkQueue(Destination parkQueue) {
        this.parkQueue = parkQueue;
    }

    @Override
    public void onMessage(Message message) {
        final long startedAt = System.currentTimeMillis();
        try {
            TraceContext.begin(safeJmsMessageId(message));

            // ── 1단계: 본문 추출 + 헤더 ────────────────────────────────────
            // 둘을 같은 try 로 묶는 것이 중요하다. 본문이 비어 있거나 지원하지 않는
            // 메시지 타입인 것도 "구조를 못 읽었다"이지 일시 오류가 아니다.
            // 이 두 줄이 갈라져 있으면 빈 메시지가 재시도 4회를 돌고 DLQ 로 간다
            // (테스트 본문이_비어_있는_메시지는_한_번만_읽고_분류된다 가 잡아낸 실수다).
            //
            // 여기서 실패하면 MSH-10 을 모른다. ACK 의 MSA-2 에 넣을 값이 없으므로
            // 별도의 거절 ACK 경로를 탄다.
            String raw = null;
            final Hl7MessageContext header;
            try {
                raw = extractText(message);
                LOG.info("STEP=RECEIVED bytes={}", raw.length());
                header = hl7Parser.parseHeader(raw);
            } catch (Hl7ParseException e) {
                // raw 가 null 이면 본문조차 못 읽은 것이다. 격리 큐에는 빈 문자열을
                // 보내고 헤더로 이유를 남긴다 — 메시지 자체를 잃지 않기 위해서다.
                handleUnparseable((raw == null) ? "" : raw, e);
                return;
            }

            TraceContext.setMessage(header.getMessageControlId(), header.getMessageType());
            LOG.info("STEP=PARSED facility={} version={}",
                    header.getSendingFacility(), header.getVersion());

            // ── 2단계: 본문 + 업무 처리 ────────────────────────────────────
            try {
                AdtEvent event = hl7Parser.toAdtEvent(header);
                ProcessResult result = adtProcessingService.process(event);
                publishAck(header, result);

            } catch (Hl7ProcessingException e) {
                if (e.isRetryable()) {
                    // 롤백시켜 재전송을 받는다. ACK 는 보내지 않는다 —
                    // 아직 성공도 실패도 확정되지 않았다.
                    LOG.warn("STEP=RETRYABLE_ERROR code={} msg={} (롤백 후 재시도)",
                            e.getErrorCode(), e.getMessage());
                    throw e;
                }
                handlePermanent(header, raw, e);
            }

        } catch (Hl7ProcessingException e) {
            throw e;

        } catch (RuntimeException e) {
            // 분류되지 않은 예외는 버그일 가능성이 높다. 격리해서 덮어 버리면
            // 문제를 못 보고 지나간다. 재시도 → DLQ 로 보내 드러나게 한다.
            LOG.error("STEP=UNEXPECTED_ERROR 분류되지 않은 예외입니다. 롤백합니다.", e);
            throw e;

        } finally {
            LOG.debug("STEP=DONE elapsed={}ms", System.currentTimeMillis() - startedAt);
            // 지우지 않으면 스레드 풀에서 다음 메시지가 앞 메시지의 Control ID 를
            // 달고 로그를 남긴다. 장애 조사를 통째로 망친다.
            TraceContext.clear();
        }
    }

    // ------------------------------------------------------------------
    // 오류 처리
    // ------------------------------------------------------------------

    /** 구조 자체를 못 읽은 경우. MSH-10 을 모른다. */
    private void handleUnparseable(String raw, Hl7ParseException e) {
        LOG.warn("STEP=PARK 파싱 실패로 격리합니다. code={} msg={}", e.getErrorCode(), e.getMessage());
        park(raw, null, null, e);
        String ack = ackBuilder.buildRejectAck(raw, e.getErrorCode(), e.getMessage());
        ackPublisher.publish(ack, null);
        // 예외를 다시 던지지 않는다 → 트랜잭션이 커밋되고 메시지가 소비된다.
    }

    /** 읽었지만 업무적으로 처리 불가. MSH-10 을 알고 있으므로 정확한 ACK 를 보낸다. */
    private void handlePermanent(Hl7MessageContext header, String raw, Hl7ProcessingException e) {
        LOG.warn("STEP=PARK 처리 불가로 격리합니다. code={} msg={}", e.getErrorCode(), e.getMessage());

        final String facility = header.getSendingFacility();
        final String controlId = header.getMessageControlId();

        park(raw, facility, controlId, e);
        adtProcessingService.recordParked(facility, controlId, e.getErrorCode(), e.getMessage());

        String ack = ackBuilder.buildAck(header, e.getAckCode(), e.getErrorCode(), e.getMessage());
        adtProcessingService.recordAck(facility, controlId, e.getAckCode(), ack);
        ackPublisher.publish(ack, controlId);
    }

    /**
     * 격리 큐로 원문을 보낸다.
     *
     * <p>이 발행도 리스너 트랜잭션에 묶여 있다. 뒤에서 예외가 나면 격리도 취소되므로
     * "격리는 됐는데 메시지는 소비 안 된" 어긋난 상태가 생기지 않는다.
     */
    private void park(final String raw, final String facility, final String controlId,
                      final Hl7ProcessingException e) {
        if (parkTemplate == null || parkQueue == null) {
            LOG.error("격리 큐가 설정되지 않았습니다. 메시지를 버리지 않으려면 롤백합니다.");
            throw new TransientProcessingException("CONFIG-NO-PARK-QUEUE",
                    "격리 큐(parkQueue)가 설정되지 않았습니다.");
        }
        parkTemplate.convertAndSend(parkQueue, raw, new MessagePostProcessor() {
            @Override
            public Message postProcessMessage(Message message) throws JMSException {
                message.setStringProperty(JmsHeaders.ERROR_CODE, e.getErrorCode());
                // 오류 문구는 예외 메시지 그대로다. 파서가 원문을 넣지 않도록
                // 이미 막아 두었다(4단계).
                message.setStringProperty(JmsHeaders.ERROR_TEXT, e.getMessage());
                if (controlId != null) {
                    message.setStringProperty(JmsHeaders.MSG_CONTROL_ID, controlId);
                }
                if (facility != null) {
                    message.setStringProperty(JmsHeaders.SENDING_FACILITY, facility);
                }
                return message;
            }
        });
    }

    // ------------------------------------------------------------------
    // ACK
    // ------------------------------------------------------------------

    private void publishAck(Hl7MessageContext header, ProcessResult result) {
        final String facility = header.getSendingFacility();
        final String controlId = header.getMessageControlId();

        if (result.isDuplicate() && result.getStoredAckPayload() != null) {
            // 새로 만들지 않고 저장된 것을 그대로 돌려준다. 최초에 AE 를 보냈는데
            // 재전송에 AA 를 보내면 송신 측 상태가 뒤집힌다.
            LOG.info("STEP=ACK_REPLAY 저장된 ACK 를 재전송합니다.");
            ackPublisher.publish(result.getStoredAckPayload(), controlId);
            return;
        }

        String ack = ackBuilder.buildAck(header, result.getAckCode(), null, null);
        adtProcessingService.recordAck(facility, controlId, result.getAckCode(), ack);
        ackPublisher.publish(ack, controlId);
    }

    // ------------------------------------------------------------------
    // 메시지 본문 추출
    // ------------------------------------------------------------------

    /**
     * {@link TextMessage} 와 {@link BytesMessage} 를 모두 받는다.
     *
     * <p>실제 연동에서는 상대 시스템에 따라 둘 다 온다. BytesMessage 를 UTF-8 로
     * 읽는 것은 가정이다 — 한글이 섞인 HL7 을 EUC-KR 로 보내는 시스템도 있다.
     * 그 경우 MSH-18(문자셋)을 보고 분기해야 하는데, 그러려면 헤더를 먼저 읽어야
     * 해서 순환이 생긴다. 실무에서는 상대 기관별 설정으로 고정하는 편이 안전하다.
     */
    private String extractText(Message message) {
        try {
            if (message instanceof TextMessage) {
                String text = ((TextMessage) message).getText();
                if (text == null) {
                    throw new Hl7ParseException("본문이 비어 있는 TextMessage 입니다.");
                }
                return text;
            }
            if (message instanceof BytesMessage) {
                BytesMessage bytes = (BytesMessage) message;
                byte[] buf = new byte[(int) bytes.getBodyLength()];
                bytes.readBytes(buf);
                return new String(buf, "UTF-8");
            }
            throw new Hl7ParseException(
                    "지원하지 않는 JMS 메시지 타입입니다: " + message.getClass().getName());

        } catch (JMSException e) {
            // 브로커와의 통신 문제일 수 있다. 재시도 대상으로 본다.
            throw new TransientProcessingException("JMS-READ",
                    "JMS 메시지를 읽지 못했습니다: " + e.getMessage(), e);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 을 쓸 수 없습니다.", e);
        }
    }

    private String safeJmsMessageId(Message message) {
        try {
            return message.getJMSMessageID();
        } catch (JMSException e) {
            return null;
        }
    }
}
