package com.example.hl7poc.jms.dlq;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;

import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.QueueBrowser;
import javax.jms.Session;
import javax.jms.TextMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.jms.core.BrowserCallback;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.SessionCallback;

import com.example.hl7poc.jms.support.JmsHeaders;

/**
 * DLQ / 격리(PARK) 큐 운영 도구.
 *
 * <pre>
 *   ant run-dlq -Dargs="list"             DLQ 내용 요약
 *   ant run-dlq -Dargs="list park"        격리 큐 요약
 *   ant run-dlq -Dargs="replay 10"        DLQ 에서 10건을 요청 큐로 재투입
 *   ant run-dlq -Dargs="replay ALL"       전부 재투입
 *   ant run-dlq -Dargs="replay park ALL"  격리 큐에서 재투입
 * </pre>
 *
 * <p><b>이 도구는 리스너를 띄우지 않는다.</b> 전용 컨텍스트
 * ({@code app-context-dlq-tool.xml})를 쓴다. 브리지 본체 컨텍스트를 그대로 열면
 * 도구를 실행하는 순간 컨슈머가 하나 더 붙어 요청 큐에서 메시지를 가져가 버린다.
 * DB 나 암호화 키 없이도 떠야 한다는 점도 중요하다 — 장애 상황에서 쓰는 도구가
 * "DB 가 죽어서 안 뜬다"면 쓸모가 없다.
 *
 * <p><b>삭제(purge)는 일부러 넣지 않았다.</b> 이 큐의 메시지에는 환자 정보가
 * 들어 있다. 되돌릴 수 없는 삭제를 한 줄짜리 명령으로 만들어 두면 사고가 난다.
 * 정말 필요하면 ActiveMQ 콘솔에서 무엇을 지우는지 보면서 하는 편이 안전하다.
 */
public final class DlqReprocessor {

    private static final Logger LOG = LoggerFactory.getLogger(DlqReprocessor.class);

    private static final String CONTEXT = "classpath:spring/app-context-dlq-tool.xml";

    /** 큐가 비었다고 판단하기까지 기다리는 시간. */
    private static final long RECEIVE_TIMEOUT_MS = 2000L;

    /** 한 번의 실행에서 훑어볼 최대 건수. 무한 루프 방지. */
    private static final int SCAN_LIMIT = 10_000;

    /**
     * 재투입 한도. 이 횟수를 넘긴 메시지는 자동 복구 대상이 아니라고 보고
     * 격리 큐로 옮긴다. 넘긴 메시지를 DLQ 에 그대로 두면, 다음 재투입 때 다시
     * 읽혀 같은 자리를 맴돈다.
     */
    private static final int MAX_REPROCESS = 3;

    private final JmsTemplate jmsTemplate;
    private final Queue requestQueue;
    private final Queue dlqQueue;
    private final Queue parkQueue;

    /**
     * JmsTemplate 을 주입받지 않고 <b>직접 만든다.</b>
     *
     * <p>재투입은 "꺼내기"와 "넣기"가 한 트랜잭션 안에서 일어나야 한다. 갈라지면
     * 중간에 죽었을 때 메시지가 사라지거나 두 배가 된다. 그런데 주입받은 템플릿의
     * {@code sessionTransacted} 가 꺼져 있으면 {@code session.commit()} 이
     * "Not a transacted session" 으로 실패한다 — 실제로 여기서 겪었다.
     *
     * <p>이 도구의 정확성이 남이 설정한 빈의 상태에 달려서는 안 된다. 필요한 설정을
     * 직접 갖춘 템플릿을 만들어 쓴다.
     *
     * <p>CLI 외에 테스트에서도 직접 쓴다. 재처리는 정상 경로보다 검증이 더 중요한데
     * main 을 통해서만 부를 수 있으면 테스트하기 어렵다.
     */
    public DlqReprocessor(ConnectionFactory connectionFactory, Queue requestQueue,
                          Queue dlqQueue, Queue parkQueue) {
        this.jmsTemplate = new JmsTemplate(connectionFactory);
        this.jmsTemplate.setSessionTransacted(true);
        this.jmsTemplate.setReceiveTimeout(RECEIVE_TIMEOUT_MS);
        this.requestQueue = requestQueue;
        this.dlqQueue = dlqQueue;
        this.parkQueue = parkQueue;
    }

    // ------------------------------------------------------------------
    // main
    // ------------------------------------------------------------------

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            System.exit(1);
        }

        ClassPathXmlApplicationContext ctx = new ClassPathXmlApplicationContext(CONTEXT);
        try {
            DlqReprocessor tool = new DlqReprocessor(
                    ctx.getBean("cachingConnectionFactory", ConnectionFactory.class),
                    ctx.getBean("adtRequestQueue", Queue.class),
                    ctx.getBean("adtDlqQueue", Queue.class),
                    ctx.getBean("adtParkQueue", Queue.class));

            String command = args[0].toLowerCase();
            boolean park = args.length > 1 && "park".equalsIgnoreCase(args[1]);
            String amountArg = park ? arg(args, 2) : arg(args, 1);

            if ("list".equals(command)) {
                tool.list(park ? tool.parkQueue : tool.dlqQueue);
            } else if ("replay".equals(command)) {
                int max = parseAmount(amountArg);
                int moved = tool.replay(park ? tool.parkQueue : tool.dlqQueue, max);
                LOG.info("재투입 완료: {}건", moved);
            } else {
                printUsage();
                System.exit(1);
            }
        } finally {
            ctx.close();
        }
    }

    private static String arg(String[] args, int index) {
        return (args.length > index) ? args[index] : null;
    }

    private static int parseAmount(String value) {
        if (value == null || "ALL".equalsIgnoreCase(value)) {
            return SCAN_LIMIT;
        }
        try {
            return Math.max(1, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("건수는 숫자이거나 ALL 이어야 합니다: " + value);
        }
    }

    private static void printUsage() {
        System.out.println("사용법:");
        System.out.println("  list [park]              큐 내용 요약 (원문은 출력하지 않는다)");
        System.out.println("  replay [park] <건수|ALL>  요청 큐로 재투입");
    }

    // ------------------------------------------------------------------
    // list
    // ------------------------------------------------------------------

    /**
     * 큐를 browse 해서 헤더만 출력한다.
     *
     * <p><b>본문은 출력하지 않는다.</b> 원문 HL7 에는 환자 정보가 그대로 들어 있고,
     * 운영 도구의 출력은 터미널 기록이나 티켓에 붙여 넣어지기 마련이다.
     * 무엇이 왜 실패했는지 판단하는 데는 헤더로 충분하다.
     */
    public void list(final Queue queue) {
        List<String> rows = jmsTemplate.browse(queue, new BrowserCallback<List<String>>() {
            @Override
            @SuppressWarnings("unchecked")
            public List<String> doInJms(Session session, QueueBrowser browser) throws JMSException {
                List<String> out = new ArrayList<>();
                Enumeration<Message> e = browser.getEnumeration();
                int index = 0;
                while (e.hasMoreElements() && index < SCAN_LIMIT) {
                    out.add(describe(++index, e.nextElement()));
                }
                return out;
            }
        });

        if (rows.isEmpty()) {
            LOG.info("큐가 비어 있습니다.");
            return;
        }
        LOG.info("총 {}건", rows.size());
        for (String row : rows) {
            LOG.info("  {}", row);
        }
    }

    private static String describe(int index, Message m) throws JMSException {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%4d", index));
        sb.append(" ctrlId=").append(nvl(m.getStringProperty(JmsHeaders.MSG_CONTROL_ID)));
        sb.append(" facility=").append(nvl(m.getStringProperty(JmsHeaders.SENDING_FACILITY)));
        sb.append(" error=").append(nvl(m.getStringProperty(JmsHeaders.ERROR_CODE)));
        sb.append(" retried=").append(reprocessCount(m));
        sb.append(" at=").append(new SimpleDateFormat("MM-dd HH:mm:ss")
                .format(new Date(m.getJMSTimestamp())));
        if (m instanceof TextMessage) {
            String body = ((TextMessage) m).getText();
            // 길이만. 본문은 출력하지 않는다.
            sb.append(" bytes=").append(body == null ? 0 : body.length());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // replay
    // ------------------------------------------------------------------

    /**
     * 큐에서 꺼내 요청 큐로 다시 넣는다.
     *
     * <p><b>한 트랜잭션 안에서 꺼내고 넣는다.</b> 두 동작이 갈라지면 중간에 죽었을 때
     * 메시지가 사라지거나 두 배로 늘어난다. 세션을 transacted 로 열고 마지막에
     * 커밋한다.
     *
     * <p>재투입해도 안전한 이유는 멱등성 때문이다. 이미 처리된 메시지가 섞여 있어도
     * {@code (sending_facility, MSH-10)} 선점에 걸려 업무 처리는 건너뛰고 저장해 둔
     * ACK 만 다시 나간다.
     */
    public int replay(final Queue source, final int max) {
        Integer moved = jmsTemplate.execute(new SessionCallback<Integer>() {
            @Override
            public Integer doInJms(Session session) throws JMSException {
                MessageConsumer consumer = session.createConsumer(source);
                MessageProducer toRequest = session.createProducer(requestQueue);
                MessageProducer toPark = session.createProducer(parkQueue);
                int replayed = 0;
                int parked = 0;
                try {
                    int scanned = 0;
                    while (replayed < max && scanned < SCAN_LIMIT) {
                        Message original = consumer.receive(RECEIVE_TIMEOUT_MS);
                        if (original == null) {
                            break;      // 큐가 비었다
                        }
                        scanned++;

                        int count = reprocessCount(original);
                        if (count >= MAX_REPROCESS) {
                            // 자동 복구로는 답이 안 나오는 메시지다. 사람이 볼 수 있게
                            // 격리 큐로 옮긴다. DLQ 에 두면 다음 실행에서 또 읽힌다.
                            toPark.send(copy(session, original, count,
                                    "DLQ-MAX-REPROCESS"));
                            parked++;
                            continue;
                        }
                        toRequest.send(copy(session, original, count + 1, null));
                        replayed++;
                    }
                    session.commit();
                    if (parked > 0) {
                        LOG.warn("재투입 한도({}회)를 넘겨 격리 큐로 옮긴 메시지: {}건",
                                MAX_REPROCESS, parked);
                    }
                } catch (JMSException e) {
                    session.rollback();
                    throw e;
                }
                return replayed;
            }
        }, true);
        return (moved == null) ? 0 : moved;
    }

    /**
     * 메시지를 복사한다.
     *
     * <p><b>{@code JMSXGroupID} 를 반드시 옮겨야 한다.</b> 이 값으로 같은 환자의
     * 메시지가 같은 컨슈머에 고정되어 순서가 보장된다. 재투입하면서 빠뜨리면
     * 그 메시지만 순서 보장에서 이탈한다 — 재처리 상황은 순서가 가장 중요한
     * 때인데도.
     */
    private static Message copy(Session session, Message original, int newCount,
                                String overrideErrorCode) throws JMSException {
        String body = (original instanceof TextMessage)
                ? ((TextMessage) original).getText() : null;
        TextMessage copy = session.createTextMessage(body);

        copyProperty(original, copy, JmsHeaders.MSG_CONTROL_ID);
        copyProperty(original, copy, JmsHeaders.SENDING_FACILITY);
        copyProperty(original, copy, JmsHeaders.ERROR_TEXT);
        copyProperty(original, copy, JmsHeaders.GROUP_ID);

        String errorCode = (overrideErrorCode != null)
                ? overrideErrorCode : original.getStringProperty(JmsHeaders.ERROR_CODE);
        if (errorCode != null) {
            copy.setStringProperty(JmsHeaders.ERROR_CODE, errorCode);
        }
        copy.setIntProperty(JmsHeaders.REPROCESS_COUNT, newCount);
        return copy;
    }

    private static void copyProperty(Message from, Message to, String name) throws JMSException {
        String value = from.getStringProperty(name);
        if (value != null) {
            to.setStringProperty(name, value);
        }
    }

    private static int reprocessCount(Message m) throws JMSException {
        return m.propertyExists(JmsHeaders.REPROCESS_COUNT)
                ? m.getIntProperty(JmsHeaders.REPROCESS_COUNT) : 0;
    }

    private static String nvl(String s) {
        return (s == null) ? "-" : s;
    }
}
