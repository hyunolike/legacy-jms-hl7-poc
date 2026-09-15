package com.example.hl7poc;

import java.util.concurrent.CountDownLatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * HL7 ADT 브리지의 진입점.
 *
 * <p>condb-secure 와 동일하게 WAS 에 배포하지 않고, {@code main()} 에서
 * {@link ClassPathXmlApplicationContext} 를 직접 열어 기동한다. 컨텍스트가 뜨면
 * {@code DefaultMessageListenerContainer} 가 자기 스레드에서 큐를 소비하기
 * 시작하므로, 메인 스레드는 종료 신호를 받을 때까지 대기만 하면 된다.
 *
 * <p>Spring Boot 라면: {@code @SpringBootApplication} 클래스 하나로 끝나고,
 * 대기 로직도 스프링이 non-daemon 스레드를 띄워 대신 처리해 준다.
 */
public final class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    /** 루트 컨텍스트. 기능별 XML 은 이 파일이 {@code <import>} 한다. */
    private static final String CONTEXT_LOCATION = "classpath:spring/app-context.xml";

    private Main() {
        // 인스턴스화 금지
    }

    public static void main(String[] args) {
        final long startedAt = System.currentTimeMillis();
        final CountDownLatch shutdownLatch = new CountDownLatch(1);

        ClassPathXmlApplicationContext context = null;
        try {
            context = new ClassPathXmlApplicationContext(CONTEXT_LOCATION);

            // 컨텍스트 자체의 정리는 스프링 훅에 맡기고,
            // 메인 스레드를 깨우는 역할만 별도 훅으로 둔다.
            context.registerShutdownHook();
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    LOG.info("종료 신호 수신. 리스너 컨테이너를 정리합니다.");
                    shutdownLatch.countDown();
                }
            }, "hl7poc-shutdown"));

            LOG.info("HL7 ADT 브리지 기동 완료 ({} ms). 종료하려면 Ctrl+C.",
                    System.currentTimeMillis() - startedAt);

            shutdownLatch.await();
            LOG.info("HL7 ADT 브리지를 종료합니다.");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("대기 중 인터럽트. 종료합니다.");
        } catch (RuntimeException e) {
            // 기동 실패는 조용히 죽으면 원인 추적이 어렵다. 반드시 남기고 비정상 종료한다.
            LOG.error("기동 실패: {}", e.getMessage(), e);
            if (context != null) {
                context.close();
            }
            System.exit(1);
        }
    }
}
