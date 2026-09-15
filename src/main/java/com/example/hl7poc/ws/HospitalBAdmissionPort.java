package com.example.hl7poc.ws;

import javax.jws.WebMethod;
import javax.jws.WebParam;
import javax.jws.WebResult;
import javax.jws.WebService;
import javax.jws.soap.SOAPBinding;

import com.example.hl7poc.ws.dto.AdmissionNotice;
import com.example.hl7poc.ws.dto.AdmissionNoticeResponse;

/**
 * 병원 B 입퇴원 통보 서비스의 계약(SEI).
 *
 * <p>서버(mock)와 클라이언트가 <b>같은 인터페이스를 공유</b>한다. 실제 연동에서는
 * 상대가 준 WSDL 로 {@code wsimport} 를 돌려 스텁을 생성하는 것이 보통이지만,
 * 이 PoC 는 양쪽을 다 만들므로 계약을 자바 인터페이스로 두는 편이 낫다.
 *
 * <ul>
 *   <li>빌드 단계에 코드 생성(wsimport)이 끼지 않아 Ant 빌드가 단순해진다</li>
 *   <li>계약이 바뀌면 컴파일 에러로 드러난다. 생성 스텁은 재생성을 잊으면
 *       런타임에야 안다</li>
 * </ul>
 *
 * <p>대신 실제 상대와 붙일 때는 이 인터페이스가 WSDL 과 어긋나지 않는지 확인해야
 * 한다. 이 PoC 의 단순화 지점이다.
 *
 * <p>{@code @WebService} 어노테이션을 쓰는 것은 레거시 XML 원칙의 예외다.
 * JAX-WS 는 어노테이션 없이는 동작하지 않는다(규격 자체가 어노테이션 기반).
 */
@WebService(name = "HospitalBAdmissionPort",
            targetNamespace = HospitalBAdmissionPort.NAMESPACE)
@SOAPBinding(style = SOAPBinding.Style.DOCUMENT, use = SOAPBinding.Use.LITERAL)
public interface HospitalBAdmissionPort {

    String NAMESPACE = "http://hospital-b.example.com/adt";
    String SERVICE_NAME = "HospitalBAdmissionService";
    String PORT_NAME = "HospitalBAdmissionPort";

    /**
     * 입퇴원 이벤트를 통보한다.
     *
     * @return 접수 또는 거절. 거절은 Fault 가 아니라 상태값으로 온다
     *         ({@link AdmissionNoticeResponse} 주석 참고).
     */
    @WebMethod(operationName = "notifyAdmission")
    @WebResult(name = "response", targetNamespace = NAMESPACE)
    AdmissionNoticeResponse notifyAdmission(
            @WebParam(name = "notice", targetNamespace = NAMESPACE) AdmissionNotice notice);
}
