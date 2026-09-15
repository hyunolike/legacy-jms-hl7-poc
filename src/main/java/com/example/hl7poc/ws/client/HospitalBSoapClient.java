package com.example.hl7poc.ws.client;

import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

import javax.xml.namespace.QName;
import javax.xml.ws.BindingProvider;
import javax.xml.ws.Service;
import javax.xml.ws.WebServiceException;
import javax.xml.ws.soap.SOAPFaultException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import com.example.hl7poc.common.dto.AdtEvent;
import com.example.hl7poc.common.dto.PatientInfo;
import com.example.hl7poc.common.exception.PermanentProcessingException;
import com.example.hl7poc.common.exception.TransientProcessingException;
import com.example.hl7poc.ws.HospitalBAdmissionPort;
import com.example.hl7poc.ws.dto.AdmissionNotice;
import com.example.hl7poc.ws.dto.AdmissionNoticeResponse;

/**
 * 병원 B SOAP 클라이언트. condb-secure 의 GDS 연동 자리에 대응한다.
 *
 * <p><b>스레드 세이프 주의</b>: JAX-WS 포트 프록시는 스레드 세이프하지 않다.
 * {@code BindingProvider} 의 request context 를 포트 인스턴스가 들고 있어서,
 * 컨슈머 10개가 한 포트를 공유하면 요청 컨텍스트가 섞인다. 그래서
 * <ul>
 *   <li>{@link Service} 는 한 번만 만들어 재사용하고(이건 스레드 세이프),</li>
 *   <li>포트 프록시는 <b>호출마다</b> 새로 만든다.</li>
 * </ul>
 * 포트 생성은 Service 생성보다 훨씬 싸다. 이 구분을 모르고 포트를 필드로 두는
 * 것이 이 계층에서 가장 흔한 사고다 — 부하가 걸려야 드러난다.
 *
 * <p><b>WSDL 은 리포지토리에 두고 클래스패스에서 읽는다.</b> 두 가지를 동시에
 * 만족해야 하기 때문이다.
 * <ul>
 *   <li>타입드 포트 프록시를 만들려면 WSDL 메타데이터가 <b>반드시</b> 필요하다.
 *       {@code Service.create(QName)} + {@code addPort} 조합만으로는 JAX-WS RI 가
 *       "WSDL Metadata not available to create the proxy" 로 거부한다.</li>
 *   <li>그렇다고 런타임에 상대 서버의 {@code ?wsdl} 을 받아오면, 상대가 내려가
 *       있는 동안 우리 애플리케이션이 <b>기동조차 못 한다.</b> 연동 상대의 가용성이
 *       우리 기동 조건이 되어서는 안 된다.</li>
 * </ul>
 * 엔드포인트 주소는 WSDL 에 적힌 값이 아니라 설정값으로 매 호출 덮어쓴다.
 * 개발/스테이징/운영이 같은 WSDL 을 쓰고 주소만 달라지는 것이 정상이다.
 */
public class HospitalBSoapClient implements HospitalBNotifier, InitializingBean {

    private static final Logger LOG = LoggerFactory.getLogger(HospitalBSoapClient.class);

    private static final QName SERVICE_QNAME =
            new QName(HospitalBAdmissionPort.NAMESPACE, HospitalBAdmissionPort.SERVICE_NAME);
    private static final QName PORT_QNAME =
            new QName(HospitalBAdmissionPort.NAMESPACE, HospitalBAdmissionPort.PORT_NAME);

    /** 전송 포맷. 상대와 합의한 값이라는 가정이다. */
    private static final String WIRE_DATE_FORMAT = "yyyyMMddHHmmss";

    /**
     * JAX-WS 타임아웃 키. 표준이 아니라 구현체별로 이름이 다르다.
     * RI(jaxws-rt)와 JDK 내장판 양쪽 키를 다 넣어 둔다 — 어느 쪽이 실제로 쓰이는지는
     * 클래스패스 구성에 달려 있고, 틀린 키는 조용히 무시된다.
     */
    private static final String[] CONNECT_TIMEOUT_KEYS = {
            "com.sun.xml.ws.connect.timeout",
            "com.sun.xml.internal.ws.connect.timeout"};
    private static final String[] READ_TIMEOUT_KEYS = {
            "com.sun.xml.ws.request.timeout",
            "com.sun.xml.internal.ws.request.timeout"};

    /** 클래스패스에 둔 WSDL. 상대가 준 계약 문서가 이 자리에 온다. */
    private static final String WSDL_RESOURCE = "wsdl/hospital-b-admission.wsdl";

    private String endpointUrl;
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 5000;
    private String wsdlResource = WSDL_RESOURCE;

    private Service service;

    public void setEndpointUrl(String endpointUrl) {
        this.endpointUrl = endpointUrl;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    /** 상대 기관별로 WSDL 이 다를 때 바꾼다. 기본값은 이 PoC 의 WSDL. */
    public void setWsdlResource(String wsdlResource) {
        this.wsdlResource = wsdlResource;
    }

    @Override
    public void afterPropertiesSet() {
        if (endpointUrl == null || endpointUrl.trim().isEmpty()) {
            throw new IllegalStateException(
                    "병원 B 엔드포인트가 설정되지 않았습니다. HL7POC_HOSPITAL_B_ENDPOINT 를 확인하세요.");
        }
        try {
            new URL(endpointUrl);
        } catch (MalformedURLException e) {
            throw new IllegalStateException("병원 B 엔드포인트 URL 형식이 올바르지 않습니다: "
                    + endpointUrl, e);
        }
        // 타임아웃이 0 이면 무한 대기다. SOAP 호출이 DB 트랜잭션 안에 있으므로
        // 무한 대기는 커넥션 풀을 통째로 말려 죽인다.
        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0) {
            throw new IllegalStateException(
                    "병원 B 연동 타임아웃은 0보다 커야 합니다. connect=" + connectTimeoutMs
                            + ", read=" + readTimeoutMs);
        }

        URL wsdlUrl = Thread.currentThread().getContextClassLoader().getResource(wsdlResource);
        if (wsdlUrl == null) {
            wsdlUrl = HospitalBSoapClient.class.getClassLoader().getResource(wsdlResource);
        }
        if (wsdlUrl == null) {
            throw new IllegalStateException(
                    "병원 B WSDL 을 클래스패스에서 찾을 수 없습니다: " + wsdlResource);
        }
        // 로컬 파일이므로 네트워크를 타지 않는다. 상대가 내려가 있어도 기동한다.
        this.service = Service.create(wsdlUrl, SERVICE_QNAME);

        if (endpointUrl.startsWith("http://")) {
            // 이 통보에는 평문 환자 정보가 실린다. PoC 라 허용하지만 짚어 둔다.
            LOG.warn("병원 B 연동이 평문 HTTP 입니다. 환자 정보가 전송 구간에 노출됩니다."
                    + " 운영에서는 HTTPS + 상호 인증(mTLS)이 필요합니다. endpoint={}", endpointUrl);
        }
        LOG.info("병원 B SOAP 클라이언트 준비 완료. endpoint={}, connect={}ms, read={}ms",
                endpointUrl, connectTimeoutMs, readTimeoutMs);
    }

    @Override
    public void notifyAdtEvent(AdtEvent event) {
        final AdmissionNotice notice = toNotice(event);
        final long startedAt = System.currentTimeMillis();

        LOG.debug("STEP=FORWARDING 병원 B 로 통보합니다. {}", notice);

        final AdmissionNoticeResponse response;
        try {
            response = newPort().notifyAdmission(notice);

        } catch (SOAPFaultException e) {
            // 상대가 SOAP Fault 를 냈다. 대개 상대 시스템 내부 오류다.
            // 실제 연동에서는 fault code 를 보고 영구/일시를 갈라야 한다.
            // 지금은 보수적으로 일시 오류로 본다 — 영구 오류를 일시로 잘못 보면
            // 대가가 DLQ 한 건이지만, 그 반대는 데이터 유실이다.
            throw new TransientProcessingException("WS-FAULT",
                    "병원 B 가 오류를 반환했습니다: " + e.getMessage(), e);

        } catch (WebServiceException e) {
            // 연결 실패, 타임아웃, HTTP 5xx 등 전송 계층 문제.
            throw new TransientProcessingException("WS-TRANSPORT",
                    "병원 B 와 통신할 수 없습니다: " + e.getMessage(), e);
        }

        final long elapsed = System.currentTimeMillis() - startedAt;

        if (response == null) {
            throw new TransientProcessingException("WS-EMPTY-RESPONSE",
                    "병원 B 응답이 비어 있습니다.");
        }
        if (!response.isAccepted()) {
            // 상대가 정상적으로 판단한 거절이다. 다시 보내도 같은 답이 온다.
            throw new PermanentProcessingException("WS-REJECTED",
                    "병원 B 가 거절했습니다: " + response.getReason());
        }

        LOG.info("STEP=FORWARDED 병원 B 접수 완료. receiptId={} elapsed={}ms",
                response.getReceiptId(), elapsed);
    }

    /**
     * 호출마다 새 포트 프록시를 만든다. 위 클래스 주석의 스레드 세이프 항목 참고.
     */
    private HospitalBAdmissionPort newPort() {
        HospitalBAdmissionPort port = service.getPort(PORT_QNAME, HospitalBAdmissionPort.class);

        Map<String, Object> ctx = ((BindingProvider) port).getRequestContext();
        ctx.put(BindingProvider.ENDPOINT_ADDRESS_PROPERTY, endpointUrl);
        for (String key : CONNECT_TIMEOUT_KEYS) {
            ctx.put(key, connectTimeoutMs);
        }
        for (String key : READ_TIMEOUT_KEYS) {
            ctx.put(key, readTimeoutMs);
        }
        return port;
    }

    private AdmissionNotice toNotice(AdtEvent event) {
        PatientInfo p = event.getPatient();

        AdmissionNotice n = new AdmissionNotice();
        n.setMessageControlId(event.getHeader().getMessageControlId());
        n.setSendingFacility(event.getHeader().getSendingFacility());
        n.setTriggerEvent(event.getTriggerEvent());

        n.setPatientId(p.getPatientId());
        n.setPatientName(p.getFullName());
        n.setBirthDate(p.getBirthDate());
        n.setSex(p.getSex());

        n.setPatientClass(event.getPatientClass());
        n.setAssignedLocation(event.getAssignedLocation());
        n.setAdmitDateTime(format(event.getAdmitDateTime()));
        n.setDischargeDateTime(format(event.getDischargeDateTime()));
        return n;
    }

    /** {@code SimpleDateFormat} 은 스레드 세이프하지 않으므로 매번 만든다. */
    private static String format(Date date) {
        return (date == null) ? null : new SimpleDateFormat(WIRE_DATE_FORMAT).format(date);
    }
}
