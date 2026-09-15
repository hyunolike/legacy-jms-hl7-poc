package com.example.hl7poc.ws.mock;

import java.util.concurrent.CountDownLatch;

import javax.xml.ws.Endpoint;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 병원 B mock SOAP 서버. 독립 프로세스로 띄운다({@code ant run-mock}).
 *
 * <p>{@link Endpoint#publish} 는 JDK 내장 HTTP 서버({@code com.sun.net.httpserver})를
 * 쓴다. 별도 WAS 없이 {@code main()} 하나로 뜨므로 이 PoC 의 standalone 구조와
 * 잘 맞는다.
 *
 * <p>WSDL 은 {@code {엔드포인트}?wsdl} 로 자동 노출된다. 실제 연동에서 상대에게
 * 넘길 계약 문서를 여기서 바로 뽑아볼 수 있다.
 */
public final class MockHospitalBServer {

    private static final Logger LOG = LoggerFactory.getLogger(MockHospitalBServer.class);

    public static final String DEFAULT_ADDRESS = "http://localhost:9090/hospital-b/admission";

    private MockHospitalBServer() {
    }

    public static void main(String[] args) throws InterruptedException {
        final String address = (args.length > 0) ? args[0] : DEFAULT_ADDRESS;

        HospitalBAdmissionEndpoint impl = new HospitalBAdmissionEndpoint();
        Endpoint endpoint = Endpoint.publish(address, impl);

        LOG.info("병원 B mock SOAP 서버가 떴습니다.");
        LOG.info("  엔드포인트: {}", address);
        LOG.info("  WSDL      : {}?wsdl", address);
        LOG.info("종료하려면 Ctrl+C.");

        final CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                LOG.info("병원 B mock 서버를 내립니다.");
                latch.countDown();
            }
        }, "hospital-b-shutdown"));

        try {
            latch.await();
        } finally {
            endpoint.stop();
        }
    }
}
