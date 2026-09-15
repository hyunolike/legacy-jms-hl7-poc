package com.example.hl7poc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.net.Socket;

import javax.jms.Destination;
import javax.jms.TextMessage;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.DefaultMessageListenerContainer;

import com.example.hl7poc.support.ControllableAdtListener;
import com.example.hl7poc.support.ControllableAdtListener.Mode;

/**
 * 3단계 Spring XML 배선 검증.
 *
 * <p>ActiveMQ 와 PostgreSQL 이 실제로 떠 있어야 의미가 있는 통합 테스트다.
 * 인프라가 없으면 {@code Assume} 으로 건너뛴다. {@code ant test} 가 Docker 없이도
 * 통과해야 하기 때문이다. 검증하려면 먼저 {@code ant docker-up} 을 실행한다.
 *
 * <p>확인하는 것
 * <ol>
 *   <li>운영 XML 이 그대로 로딩되는가 (DataSource, DMLC, 템플릿)</li>
 *   <li>정상 처리 시 메시지가 소비되고 ACK 가 나가는가</li>
 *   <li>리스너가 실패하면 <b>이미 발행한 ACK 까지</b> 롤백되는가</li>
 *   <li>재시도 횟수가 RedeliveryPolicy 와 일치하고, 소진 후 DLQ 로 가는가</li>
 *   <li>DB 쓰기가 같은 트랜잭션으로 롤백되는가</li>
 * </ol>
 */
public class SpringWiringTest {

    private static final String BROKER_HOST = "localhost";
    private static final int BROKER_PORT = 61616;
    private static final int DB_PORT = 5432;

    /** 재전송 지연 2s+4s+8s = 14s. 여유를 둔다. */
    private static final long DLQ_WAIT_MS = 40_000L;

    private static ClassPathXmlApplicationContext context;
    private static JmsTemplate probe;
    private static JdbcTemplate jdbcTemplate;
    private static ControllableAdtListener listener;
    private static Destination requestQueue;
    private static Destination ackQueue;
    private static Destination dlqQueue;

    @BeforeClass
    public static void startContext() {
        assumeTrue("ActiveMQ 가 떠 있지 않아 건너뜁니다 (ant docker-up)", portOpen(BROKER_PORT));
        assumeTrue("PostgreSQL 이 떠 있지 않아 건너뜁니다 (ant docker-up)", portOpen(DB_PORT));

        context = new ClassPathXmlApplicationContext("classpath:spring/test-infra-context.xml");

        jdbcTemplate = context.getBean("jdbcTemplate", JdbcTemplate.class);
        listener = context.getBean("adtMessageListener", ControllableAdtListener.class);
        requestQueue = context.getBean("adtRequestQueue", Destination.class);
        ackQueue = context.getBean("adtAckQueue", Destination.class);
        dlqQueue = context.getBean("adtDlqQueue", Destination.class);

        // 큐를 비우고 결과를 읽는 용도. 짧은 타임아웃으로 따로 만든다.
        probe = new JmsTemplate(context.getBean("cachingConnectionFactory",
                javax.jms.ConnectionFactory.class));
        probe.setReceiveTimeout(1000L);
    }

    @AfterClass
    public static void stopContext() {
        if (context != null) {
            context.close();
        }
    }

    @After
    public void drain() {
        if (context == null) {
            return;
        }
        drainQueue(ackQueue);
        drainQueue(dlqQueue);
        jdbcTemplate.update("DELETE FROM processing_log WHERE detail = '롤백되어야 하는 행'");
    }

    // ------------------------------------------------------------------
    // 1. 컨텍스트 로딩
    // ------------------------------------------------------------------

    @Test
    public void 운영_XML_이_그대로_로딩된다() {
        assertNotNull(context.getBean("dataSource"));
        assertNotNull(context.getBean("transactionManager"));
        assertNotNull(context.getBean("jmsTemplate"));
        assertNotNull(context.getBean("standaloneJmsTemplate"));

        DefaultMessageListenerContainer dmlc =
                context.getBean("adtRequestListenerContainer", DefaultMessageListenerContainer.class);
        assertTrue("리스너 컨테이너가 기동해 있어야 한다", dmlc.isRunning());
        assertTrue("세션 트랜잭션이 켜져 있어야 한다", dmlc.isSessionTransacted());
        assertEquals(3, dmlc.getConcurrentConsumers());
        assertEquals(10, dmlc.getMaxConcurrentConsumers());

        // DB 왕복 확인
        assertEquals(Integer.valueOf(1), jdbcTemplate.queryForObject("SELECT 1", Integer.class));
    }

    // ------------------------------------------------------------------
    // 2. 정상 처리
    // ------------------------------------------------------------------

    @Test
    public void 정상_처리되면_ACK_가_발행된다() throws Exception {
        listener.reset(Mode.SUCCEED);
        String body = "OK-" + System.nanoTime();
        probe.convertAndSend(requestQueue, body);

        String ack = awaitText(ackQueue, 10_000L);
        assertEquals("ACK:" + body, ack);
        assertEquals("정상 처리는 한 번만 배달되어야 한다", 1, listener.getDeliveryCount());
    }

    // ------------------------------------------------------------------
    // 3~4. 실패 시 ACK 롤백 + 재시도 + DLQ
    // ------------------------------------------------------------------

    @Test
    public void 리스너가_실패하면_ACK_도_롤백되고_재시도_후_DLQ_로_간다() throws Exception {
        listener.reset(Mode.ACK_THEN_FAIL);
        String body = "FAIL-" + System.nanoTime();
        probe.convertAndSend(requestQueue, body);

        String dead = awaitText(dlqQueue, DLQ_WAIT_MS);
        assertEquals("원문 그대로 DLQ 로 가야 한다", body, dead);

        // maximumRedeliveries=3 → 최초 1회 + 재전송 3회 = 4회
        assertEquals("재배달 횟수가 RedeliveryPolicy 와 일치해야 한다",
                4, listener.getDeliveryCount());

        // 핵심: 매 시도마다 ACK 를 발행했는데, 롤백됐다면 한 건도 남지 않아야 한다.
        String leakedAck = receiveText(ackQueue);
        assertEquals("ACK 가 리스너 트랜잭션에 합류하지 않았다 (롤백 누락)", null, leakedAck);
    }

    /**
     * 재전송 "지연"이 실제로 걸리는지 고정한다.
     *
     * <p>cacheLevel 을 CACHE_NONE 으로 내리면 컨슈머를 매번 버리기 때문에
     * 클라이언트측 지연이 통째로 무시되고 간격이 0.03초로 떨어진다. 그래도
     * 재시도 횟수와 DLQ 이동은 정상이라 다른 테스트로는 잡히지 않는다.
     * 설정이 조용히 되돌아가는 것을 막기 위한 회귀 테스트다.
     */
    @Test
    public void 재전송_지연이_지수_백오프로_적용된다() throws Exception {
        listener.reset(Mode.ACK_THEN_FAIL);
        String body = "BACKOFF-" + System.nanoTime();
        probe.convertAndSend(requestQueue, body);

        awaitText(dlqQueue, DLQ_WAIT_MS);

        java.util.List<Long> gaps = listener.gapsMillis();
        assertEquals("재배달 간격은 3개여야 한다", 3, gaps.size());

        // 설정값 2s → 4s → 8s. 스케줄러 오차와 부하를 감안해 하한만 본다.
        long[] expectedMin = {1500L, 3500L, 7000L};
        for (int i = 0; i < 3; i++) {
            assertTrue("재전송 지연이 적용되지 않았다 (gap[" + i + "]=" + gaps.get(i)
                            + "ms, 기대 최소 " + expectedMin[i] + "ms). "
                            + "cacheLevel 이 CACHE_NONE 으로 내려가지 않았는지 확인할 것. 실측=" + gaps,
                    gaps.get(i) >= expectedMin[i]);
        }
    }

    // ------------------------------------------------------------------
    // 5. DB 롤백
    // ------------------------------------------------------------------

    @Test
    public void 리스너가_실패하면_DB_쓰기도_롤백된다() throws Exception {
        listener.reset(Mode.DB_THEN_FAIL);
        String body = "DBFAIL-" + System.nanoTime();
        probe.convertAndSend(requestQueue, body);

        awaitText(dlqQueue, DLQ_WAIT_MS);

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM processing_log WHERE msg_control_id = ?",
                Integer.class, body);
        assertEquals("DB 쓰기가 롤백되지 않았다", Integer.valueOf(0), rows);
    }

    // ------------------------------------------------------------------
    // 도우미
    // ------------------------------------------------------------------

    private static boolean portOpen(int port) {
        try (Socket s = new Socket(BROKER_HOST, port)) {
            return s.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    private static String receiveText(Destination destination) throws Exception {
        javax.jms.Message m = probe.receive(destination);
        return (m == null) ? null : ((TextMessage) m).getText();
    }

    private static String awaitText(Destination destination, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String text = receiveText(destination);
            if (text != null) {
                return text;
            }
        }
        throw new AssertionError(timeoutMs + "ms 안에 메시지가 도착하지 않았습니다: " + destination);
    }

    private static void drainQueue(Destination destination) {
        try {
            while (probe.receive(destination) != null) {
                // 비운다
            }
        } catch (Exception ignored) {
            // 큐가 없으면 무시
        }
    }
}
