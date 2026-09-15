package com.example.hl7poc.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import javax.jms.Destination;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.TextMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jms.core.JmsTemplate;

/**
 * 3단계 XML 배선을 검증하기 위한 리스너 스텁.
 *
 * <p>실제 {@code AdtMessageListener} 는 5단계 산출물이다. 여기서는 배선만
 * 확인하면 되므로, 동작을 외부에서 지정할 수 있는 최소 구현을 둔다.
 * 테스트가 {@link Mode} 를 바꿔 가며 같은 컨테이너에 다른 시나리오를 태운다.
 */
public class ControllableAdtListener implements MessageListener {

    private static final Logger LOG = LoggerFactory.getLogger(ControllableAdtListener.class);

    public enum Mode {
        /** 정상 처리. ACK 를 발행하고 리턴한다. */
        SUCCEED,
        /** ACK 를 발행한 "뒤에" 예외를 던진다. ACK 가 롤백되는지 보기 위한 모드. */
        ACK_THEN_FAIL,
        /** DB 에 기록한 뒤 예외를 던진다. DB 롤백을 보기 위한 모드. */
        DB_THEN_FAIL
    }

    private JmsTemplate jmsTemplate;
    private Destination ackQueue;
    private JdbcTemplate jdbcTemplate;

    private volatile Mode mode = Mode.SUCCEED;
    private final AtomicInteger deliveryCount = new AtomicInteger();
    private final List<Long> deliveryTimestamps = new CopyOnWriteArrayList<>();

    @Override
    public void onMessage(Message message) {
        final int attempt = deliveryCount.incrementAndGet();
        deliveryTimestamps.add(System.currentTimeMillis());
        String body;
        try {
            body = ((TextMessage) message).getText();
        } catch (Exception e) {
            throw new IllegalStateException("TextMessage 읽기 실패", e);
        }
        LOG.info("STEP=RECEIVED attempt={} mode={} body={}", attempt, mode, body);

        switch (mode) {
            case SUCCEED:
                jmsTemplate.convertAndSend(ackQueue, "ACK:" + body);
                LOG.info("STEP=ACK_SENT attempt={}", attempt);
                return;

            case ACK_THEN_FAIL:
                // ACK 를 먼저 발행하고 실패시킨다. 같은 세션에 합류했다면
                // 이 ACK 는 롤백과 함께 사라져야 한다.
                jmsTemplate.convertAndSend(ackQueue, "ACK:" + body);
                LOG.info("STEP=ACK_SENT(그러나 곧 롤백) attempt={}", attempt);
                throw new IllegalStateException("의도된 실패: 재전송/DLQ 검증용");

            case DB_THEN_FAIL:
                jdbcTemplate.update(
                        "INSERT INTO processing_log(msg_control_id, step, detail) VALUES (?,?,?)",
                        body, "RECEIVED", "롤백되어야 하는 행");
                LOG.info("STEP=PERSISTED(그러나 곧 롤백) attempt={}", attempt);
                throw new IllegalStateException("의도된 실패: DB 롤백 검증용");

            default:
                throw new IllegalStateException("알 수 없는 모드: " + mode);
        }
    }

    public void reset(Mode newMode) {
        this.mode = newMode;
        this.deliveryCount.set(0);
        this.deliveryTimestamps.clear();
    }

    /** 배달 사이의 간격(ms). 재전송 지연이 실제로 적용되는지 확인하는 데 쓴다. */
    public List<Long> gapsMillis() {
        List<Long> gaps = new java.util.ArrayList<>();
        for (int i = 1; i < deliveryTimestamps.size(); i++) {
            gaps.add(deliveryTimestamps.get(i) - deliveryTimestamps.get(i - 1));
        }
        return gaps;
    }

    public int getDeliveryCount() {
        return deliveryCount.get();
    }

    public void setJmsTemplate(JmsTemplate jmsTemplate) {
        this.jmsTemplate = jmsTemplate;
    }

    public void setAckQueue(Destination ackQueue) {
        this.ackQueue = ackQueue;
    }

    public void setJdbcTemplate(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
}
