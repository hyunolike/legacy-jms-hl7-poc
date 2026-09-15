package com.example.hl7poc.simulator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Queue;
import javax.jms.TextMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessagePostProcessor;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.parser.PipeParser;
import ca.uhn.hl7v2.util.Terser;

import com.example.hl7poc.jms.support.JmsHeaders;

/**
 * 병원 A 시뮬레이터. ADT 메시지를 요청 큐에 발행한다.
 *
 * <pre>
 *   ant run-simulator -Dargs="send a01 3"      A01 3건 (매번 새 Control ID)
 *   ant run-simulator -Dargs="send a03 1"      A03 1건
 *   ant run-simulator -Dargs="admit-discharge" 같은 환자의 입원 → 퇴원
 *   ant run-simulator -Dargs="duplicate"       같은 Control ID 를 두 번 (멱등성)
 *   ant run-simulator -Dargs="burst 20"        여러 환자 섞어서 20건 (동시성)
 *   ant run-simulator -Dargs="file adt_a01_broken_msh.hl7"   샘플 그대로 (오류 케이스)
 *   ant run-simulator -Dargs="ack 15"          15초간 ACK 를 받아 출력
 * </pre>
 *
 * <p><b>Control ID 를 매번 새로 만드는 이유</b>: 샘플을 그대로 보내면 두 번째
 * 실행부터는 전부 중복으로 판정되어 업무 처리가 일어나지 않는다. 멱등성이
 * 제대로 동작한다는 뜻이지만, 손으로 돌려 볼 때는 "아무 일도 안 일어나는" 것처럼
 * 보인다. 그래서 기본은 새 ID, 중복 시연은 {@code duplicate} 로 따로 둔다.
 *
 * <p>필드를 바꿀 때 문자열 치환을 쓰지 않고 HAPI Terser 를 쓴다. 치환은 필드
 * 위치가 어긋나도 조용히 통과해서, 잘못된 메시지를 만들어 놓고도 모른다.
 */
public final class HospitalASimulator {

    private static final Logger LOG = LoggerFactory.getLogger(HospitalASimulator.class);

    private static final String CONTEXT = "classpath:spring/app-context-simulator.xml";

    private static final String SAMPLE_A01 = "adt_a01_admit.hl7";
    private static final String SAMPLE_A03 = "adt_a03_discharge.hl7";

    /** burst 에서 돌려 쓸 가상 환자들. 전부 합성 데이터다. */
    private static final String[] PATIENTS = {
            "PAT000001", "PAT000002", "PAT000003", "PAT000004", "PAT000005"};

    private final JmsTemplate jmsTemplate;
    private final Queue requestQueue;
    private final Queue ackQueue;
    private final PipeParser pipeParser;

    private int sequence;

    HospitalASimulator(JmsTemplate jmsTemplate, Queue requestQueue, Queue ackQueue,
                       HapiContext hapiContext) {
        this.jmsTemplate = jmsTemplate;
        this.requestQueue = requestQueue;
        this.ackQueue = ackQueue;
        this.pipeParser = hapiContext.getPipeParser();
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
            HospitalASimulator sim = new HospitalASimulator(
                    ctx.getBean("standaloneJmsTemplate", JmsTemplate.class),
                    ctx.getBean("adtRequestQueue", Queue.class),
                    ctx.getBean("adtAckQueue", Queue.class),
                    ctx.getBean("hapiContext", HapiContext.class));

            String command = args[0].toLowerCase();
            if ("send".equals(command)) {
                String trigger = (args.length > 1) ? args[1].toLowerCase() : "a01";
                int count = intArg(args, 2, 1);
                sim.send(trigger, count);
            } else if ("admit-discharge".equals(command)) {
                sim.admitThenDischarge();
            } else if ("duplicate".equals(command)) {
                sim.duplicate();
            } else if ("burst".equals(command)) {
                sim.burst(intArg(args, 1, 20));
            } else if ("file".equals(command)) {
                if (args.length < 2) {
                    printUsage();
                    System.exit(1);
                }
                sim.sendFile(args[1]);
            } else if ("ack".equals(command)) {
                sim.listenAck(intArg(args, 1, 10));
            } else {
                printUsage();
                System.exit(1);
            }
        } finally {
            ctx.close();
        }
    }

    private static int intArg(String[] args, int index, int defaultValue) {
        if (args.length <= index) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(args[index].trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("숫자가 필요합니다: " + args[index]);
        }
    }

    private static void printUsage() {
        System.out.println("사용법:");
        System.out.println("  send <a01|a03> [건수]   새 Control ID 로 발행");
        System.out.println("  admit-discharge         같은 환자의 입원 → 퇴원");
        System.out.println("  duplicate               같은 Control ID 를 두 번 (멱등성 확인)");
        System.out.println("  burst [건수]            여러 환자 섞어서 발행 (동시성 확인)");
        System.out.println("  file <샘플파일명>        샘플을 그대로 발행 (오류 케이스)");
        System.out.println("  ack [초]                ACK 를 받아 출력");
    }

    // ------------------------------------------------------------------
    // 시나리오
    // ------------------------------------------------------------------

    void send(String trigger, int count) {
        String sample = "a03".equals(trigger) ? SAMPLE_A03 : SAMPLE_A01;
        for (int i = 0; i < count; i++) {
            String controlId = nextControlId();
            publish(render(sample, controlId, PATIENTS[0], new Date()),
                    controlId, PATIENTS[0]);
        }
        LOG.info("발행 완료: {} {}건", trigger.toUpperCase(), count);
    }

    /**
     * 같은 환자의 입원 → 퇴원. 순서가 지켜지는지 보는 데 쓴다.
     *
     * <p>둘 다 같은 {@code JMSXGroupID} 를 달고 나가므로 브로커가 같은 컨슈머에
     * 고정한다. 컨슈머가 3~10개로 늘어나 있어도 이 두 건은 순서대로 처리된다.
     */
    void admitThenDischarge() {
        String patient = PATIENTS[0];
        Date admitAt = new Date();
        Date dischargeAt = new Date(admitAt.getTime() + 3L * 24 * 60 * 60 * 1000);

        String admitId = nextControlId();
        publish(render(SAMPLE_A01, admitId, patient, admitAt), admitId, patient);

        String dischargeId = nextControlId();
        publish(render(SAMPLE_A03, dischargeId, patient, dischargeAt), dischargeId, patient);

        LOG.info("발행 완료: 입원({}) → 퇴원({}), 같은 그룹 {}", admitId, dischargeId, patient);
    }

    /**
     * 같은 메시지를 두 번 보낸다.
     *
     * <p>기대 결과: ACK 는 두 번 오지만 병원 B 전달과 DB 적재는 한 번뿐이다.
     * 브리지 로그에 {@code STEP=DEDUP_SKIP} 과 {@code STEP=ACK_REPLAY} 가 보인다.
     */
    void duplicate() {
        String controlId = nextControlId();
        String message = render(SAMPLE_A01, controlId, PATIENTS[0], new Date());
        publish(message, controlId, PATIENTS[0]);
        publish(message, controlId, PATIENTS[0]);
        LOG.info("발행 완료: 같은 Control ID({}) 2건. ACK 는 2번, 업무 처리는 1번이어야 한다.",
                controlId);
    }

    /** 여러 환자를 섞어 한꺼번에 보낸다. 컨슈머가 늘어나는지 보는 데 쓴다. */
    void burst(int count) {
        for (int i = 0; i < count; i++) {
            String patient = PATIENTS[i % PATIENTS.length];
            String sample = (i % 3 == 2) ? SAMPLE_A03 : SAMPLE_A01;
            String controlId = nextControlId();
            publish(render(sample, controlId, patient, new Date()), controlId, patient);
        }
        LOG.info("발행 완료: {}건 ({}명의 환자에 분산)", count, PATIENTS.length);
    }

    /**
     * 샘플 파일을 손대지 않고 그대로 보낸다.
     *
     * <p>오류 케이스용이다. MSH 가 깨진 샘플은 Control ID 를 바꿀 수도 없다 —
     * 애초에 파싱이 안 되기 때문이다.
     */
    void sendFile(String fileName) {
        String body = loadSample(fileName);
        publish(body, null, null);
        LOG.info("발행 완료: {} (원본 그대로)", fileName);
    }

    /** ACK 큐를 구독해 MSA/ERR 줄만 뽑아 출력한다. */
    void listenAck(int seconds) {
        LOG.info("{}초간 ACK 를 기다립니다...", seconds);
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        int received = 0;

        while (System.currentTimeMillis() < deadline) {
            javax.jms.Message m = jmsTemplate.receive(ackQueue);
            if (m == null) {
                continue;
            }
            received++;
            try {
                String text = (m instanceof TextMessage) ? ((TextMessage) m).getText() : "";
                String ctrlId = m.getStringProperty(JmsHeaders.MSG_CONTROL_ID);
                LOG.info("ACK #{} (ctrlId={})", received, ctrlId);
                for (String line : text.split("\r|\n")) {
                    // MSH 에는 볼 것이 없고, PID 는 애초에 ACK 에 없다.
                    if (line.startsWith("MSA") || line.startsWith("ERR")) {
                        LOG.info("    {}", line);
                    }
                }
            } catch (JMSException e) {
                LOG.warn("ACK 를 읽지 못했습니다: {}", e.getMessage());
            }
        }
        LOG.info("ACK 수신 종료: 총 {}건", received);
    }

    // ------------------------------------------------------------------
    // 메시지 생성
    // ------------------------------------------------------------------

    /**
     * 샘플의 MSH-10(Control ID), MSH-7(메시지 시각), PID-3(환자 ID), 그리고
     * 입퇴원 시각을 바꿔 새 메시지를 만든다.
     */
    String render(String sampleFile, String controlId, String patientId, Date when) {
        try {
            ca.uhn.hl7v2.model.Message message = pipeParser.parse(normalize(loadSample(sampleFile)));
            Terser t = new Terser(message);

            t.set("/MSH-10", controlId);
            t.set("/MSH-7", ts(when));
            t.set("/PID-3-1", patientId);

            // 입원/퇴원 시각도 함께 옮긴다. 안 그러면 MSH-7 만 미래로 가고
            // PV1-44 는 과거에 머물러 앞뒤가 맞지 않는 메시지가 된다.
            if (notEmpty(t.get("/PV1-44"))) {
                t.set("/PV1-44", ts(when));
            }
            if (notEmpty(t.get("/PV1-45"))) {
                t.set("/PV1-45", ts(when));
            }
            return pipeParser.encode(message);

        } catch (HL7Exception e) {
            throw new IllegalStateException("샘플을 가공하지 못했습니다: " + sampleFile, e);
        }
    }

    private void publish(final String body, final String controlId, final String patientId) {
        jmsTemplate.convertAndSend(requestQueue, body, new MessagePostProcessor() {
            @Override
            public javax.jms.Message postProcessMessage(javax.jms.Message message)
                    throws JMSException {
                if (controlId != null) {
                    message.setStringProperty(JmsHeaders.MSG_CONTROL_ID, controlId);
                }
                if (patientId != null) {
                    // 같은 환자의 메시지를 같은 컨슈머에 고정해 순서를 보장한다.
                    // 실제 운영에서는 환자 ID 를 그대로 쓰지 말고 해시를 쓴다 —
                    // 이 값은 브로커 콘솔과 로그에 평문으로 노출된다.
                    message.setStringProperty(JmsHeaders.GROUP_ID, patientId);
                }
                return message;
            }
        });
        LOG.info("→ 발행 ctrlId={} group={} bytes={}", controlId, patientId, body.length());
    }

    private String nextControlId() {
        sequence++;
        return "SIM" + new SimpleDateFormat("yyyyMMddHHmmss").format(new Date())
                + String.format("%04d", sequence);
    }

    private static String ts(Date when) {
        return new SimpleDateFormat("yyyyMMddHHmmss").format(when);
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String normalize(String raw) {
        return raw.replace("\r\n", "\r").replace('\n', '\r');
    }

    private static String loadSample(String fileName) {
        String path = "hl7-samples/" + fileName;
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try (InputStream in = cl.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("샘플을 찾을 수 없습니다: " + path);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), "UTF-8");
        } catch (IOException e) {
            throw new IllegalStateException("샘플을 읽지 못했습니다: " + path, e);
        }
    }

    Destination requestQueue() {
        return requestQueue;
    }
}
