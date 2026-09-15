package com.example.hl7poc.jms.publisher;

import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessagePostProcessor;

import com.example.hl7poc.jms.support.JmsHeaders;

/**
 * ACK 를 응답 큐로 발행한다.
 *
 * <p>여기 쓰이는 {@link JmsTemplate} 은 {@code transactionAwareConnectionFactory} 를
 * 물고 있다. 리스너가 롤백하면 이 ACK 도 함께 사라진다(3단계 문서 3.1절).
 * 그래서 "처리는 실패했는데 성공 ACK 만 나가는" 상황이 생기지 않는다.
 */
public class AckPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(AckPublisher.class);

    private final JmsTemplate jmsTemplate;
    private final Destination ackQueue;

    public AckPublisher(JmsTemplate jmsTemplate, Destination ackQueue) {
        this.jmsTemplate = jmsTemplate;
        this.ackQueue = ackQueue;
    }

    /**
     * @param messageControlId 원본 MSH-10. 응답 큐 쪽에서도 상관관계를 잡을 수 있게
     *                         JMS 헤더로 함께 실어 보낸다. ACK 본문을 파싱하지 않고도
     *                         선택 소비(selector)가 가능해진다.
     */
    public void publish(final String ackPayload, final String messageControlId) {
        jmsTemplate.convertAndSend(ackQueue, ackPayload, new MessagePostProcessor() {
            @Override
            public Message postProcessMessage(Message message) throws JMSException {
                if (messageControlId != null) {
                    message.setStringProperty(JmsHeaders.MSG_CONTROL_ID, messageControlId);
                    message.setJMSCorrelationID(messageControlId);
                }
                return message;
            }
        });
        LOG.info("STEP=ACK_SENT 응답 큐로 발행했습니다.");
    }
}
