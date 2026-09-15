# 6단계 — 병원 B mock SOAP 서버와 클라이언트

> 모든 환자 정보는 **가상의 합성 데이터**다.

## 산출물

```
ws/
├── HospitalBAdmissionPort.java       계약(SEI) — 서버와 클라이언트가 공유
├── dto/
│   ├── AdmissionNotice.java          통보 (toString 은 항상 마스킹)
│   └── AdmissionNoticeResponse.java  접수/거절
├── client/
│   ├── HospitalBNotifier.java        (5단계에서 정의한 인터페이스)
│   └── HospitalBSoapClient.java      JAX-WS 클라이언트
└── mock/
    ├── HospitalBAdmissionEndpoint.java   동작을 바꿀 수 있는 mock
    └── MockHospitalBServer.java          독립 main (ant run-mock)

src/main/resources/wsdl/hospital-b-admission.wsdl
```

실행: `ant run-mock` → `http://localhost:9090/hospital-b/admission`
(WSDL 은 `?wsdl` 로 노출된다)

---

## 1. 실제로 부딪힌 문제: WSDL 없이는 타입드 프록시를 못 만든다

처음에는 WSDL 파일 없이 가려 했다. 코드에 SEI 가 있으니 충분할 것 같았다.

```java
service = Service.create(SERVICE_QNAME);
service.addPort(PORT_QNAME, SOAPBinding.SOAP11HTTP_BINDING, endpointUrl);
port = service.getPort(PORT_QNAME, HospitalBAdmissionPort.class);   // ← 여기서 실패
```

```
javax.xml.ws.WebServiceException: WSDL Metadata not available to create the proxy,
either Service instance or ServiceEndpointInterface ... should have WSDL information
```

JAX-WS RI 는 타입드 포트 프록시를 만들 때 WSDL 메타데이터를 **요구한다**.
`addPort` 로 주소만 알려 주는 것으로는 부족하다.

### 그렇다고 런타임에 받아오면 안 된다

```java
service = Service.create(new URL(endpointUrl + "?wsdl"), SERVICE_QNAME);  // ✗
```

이러면 **상대 서버가 내려가 있는 동안 우리 애플리케이션이 기동조차 못 한다.**
연동 상대의 가용성이 우리 기동 조건이 되어서는 안 된다. 브리지가 떠 있어야 큐에
메시지라도 쌓이는데, 상대가 죽었다고 우리도 못 뜨면 메시지가 갈 곳이 없다.

### 결론: WSDL 을 리포지토리에 둔다

`src/main/resources/wsdl/hospital-b-admission.wsdl` 을 클래스패스에서 읽는다.
실제 연동에서는 상대 기관이 준 WSDL 이 그 자리에 온다 — 레거시 프로젝트에서
WSDL 이 소스와 함께 커밋돼 있는 이유가 이것이다.

**import 도 인라인으로 바꿔야 한다.** mock 서버가 생성한 WSDL 은 스키마를
이렇게 참조한다.

```xml
<xsd:import schemaLocation="http://localhost:9090/hospital-b/admission?xsd=1"/>
```

파일로 저장해도 이 상태면 런타임에 결국 그 서버를 찾아간다. 로컬 WSDL 을 쓰는
의미가 사라진다. 그래서 스키마를 통째로 인라인해 **자기완결적**으로 만들었다.

엔드포인트 주소는 WSDL 에 적힌 값이 아니라 `BindingProvider.ENDPOINT_ADDRESS_PROPERTY`
로 매 호출 덮어쓴다. 개발/스테이징/운영이 같은 WSDL 을 쓰고 주소만 달라지는 것이
정상이다.

> **Spring Boot라면**: `spring-boot-starter-web-services` 의 `WebServiceTemplate` 을
> 쓰면 WSDL 없이 마샬러만으로 호출할 수 있다. 대신 계약이 코드에만 존재하게 되어,
> 상대와 규격이 어긋나도 런타임에야 안다. 트레이드오프가 다르다.

---

## 2. JAX-WS 포트 프록시는 스레드 세이프하지 않다

이 계층에서 가장 흔한 사고다. **부하가 걸려야 드러난다.**

```java
// ✗ 포트를 필드로 들고 재사용 — 컨슈머 10개가 request context 를 공유한다
private HospitalBAdmissionPort port;

// ✓ Service 는 한 번만(스레드 세이프), 포트는 호출마다
private Service service;
private HospitalBAdmissionPort newPort() {
    HospitalBAdmissionPort port = service.getPort(PORT_QNAME, HospitalBAdmissionPort.class);
    ((BindingProvider) port).getRequestContext().put(...);
    return port;
}
```

`BindingProvider` 의 request context 를 **포트 인스턴스가 들고 있다.** 여러 스레드가
같은 포트에 엔드포인트 주소와 타임아웃을 써 넣으면 서로의 설정이 섞인다.
포트 생성은 `Service` 생성보다 훨씬 싸므로 매번 만드는 편이 맞다.

`여러_스레드가_동시에_호출해도_안전하다` 테스트가 10 스레드 × 5회로 확인한다.

---

## 3. 타임아웃 키는 표준이 아니다 — 그래서 실측한다

```java
"com.sun.xml.ws.connect.timeout"            // JAX-WS RI (jaxws-rt)
"com.sun.xml.internal.ws.connect.timeout"   // JDK 내장판
```

JAX-WS 타임아웃 프로퍼티는 **규격에 없고 구현체마다 이름이 다르다.** 더 나쁜 것은
**틀린 키를 넣어도 예외가 나지 않는다**는 점이다. 조용히 무시되고 무한 대기가 된다.

이 SOAP 호출은 DB 트랜잭션 안에서 일어난다. 타임아웃이 동작하지 않으면 커넥션을
쥔 채 멈춘 컨슈머가 쌓여 **커넥션 풀이 통째로 마른다.** 연동 장애 하나가 브리지
전체를 세우는 경로다.

"설정했으니 되겠지"가 통하지 않는 자리라 실측으로 고정했다.
`읽기_타임아웃이_실제로_걸린다` 는 mock 을 10초 지연시키고 타임아웃을 1초로 준 뒤,
**5초 안에 예외가 나는지**를 검사한다.

타임아웃 0(무한 대기)은 `afterPropertiesSet` 에서 기동 자체를 막는다.

---

## 4. 업무적 거절은 Fault 가 아니라 상태값으로

```
접수 → AdmissionNoticeResponse{status=ACCEPTED, receiptId=...}
거절 → AdmissionNoticeResponse{status=REJECTED, reason=...}   ← 200 응답
```

SOAP Fault 는 "요청을 처리할 수 없었다"는 프로토콜 수준 신호라 전송 오류와 구분이
흐려진다. "등록되지 않은 환자입니다"는 상대가 **정상적으로 판단한 결과**이므로
200 응답에 담는 편이 클라이언트가 재시도 여부를 가르기 쉽다.

### 실패 분류

| 상황 | 예외 | 재시도 | 근거 |
|---|---|---|---|
| 응답 `REJECTED` | `Permanent` (WS-REJECTED) | ✗ | 다시 보내도 같은 답 |
| SOAP Fault | `Transient` (WS-FAULT) | ✓ | 대개 상대 내부 오류 |
| 연결 실패 / 타임아웃 / 5xx | `Transient` (WS-TRANSPORT) | ✓ | 전송 계층 문제 |
| 빈 응답 | `Transient` (WS-EMPTY-RESPONSE) | ✓ | 판단 불가 |

**SOAP Fault 를 일시 오류로 보는 것은 보수적 선택이다.** Fault 안에는 "잘못된
요청"도 섞여 있을 수 있다. 실제 연동에서는 fault code 를 보고 갈라야 한다.
지금 보수적으로 잡은 이유는 대가가 비대칭이기 때문이다 — 영구 오류를 일시로
잘못 보면 DLQ 한 건이지만, 그 반대는 **데이터 유실**이다.

---

## 5. mock 이 필요한 진짜 이유

정상 경로를 확인하려고 만드는 것이 아니다. **실제 상대 시스템에는 "타임아웃을 내
달라", "거절해 달라", "Fault 를 던져 달라"고 부탁할 수 없다.** 연동에서 정작
확인해야 하는 것은 실패 경로인데, 그걸 시험할 방법이 mock 말고는 없다.

`HospitalBAdmissionEndpoint` 는 `Behavior` 로 동작을 바꾼다.

| Behavior | 용도 |
|---|---|
| `ACCEPT` | 정상 |
| `REJECT` | 업무적 거절 → 재시도 안 함을 확인 |
| `SLOW` | 응답 지연 → 읽기 타임아웃 확인 |
| `FAULT` | 서버 내부 오류 → SOAP Fault 처리 확인 |

받은 통보 원본도 보관한다. "무엇이 실제로 넘어왔는지"는 왕복시켜 보지 않으면
모른다 — 특히 인코딩은 로컬에서 되던 것이 상대 환경에서 깨진다.

---

## 6. PHI 를 다루는 방식

**최소한만 보낸다.** HL7 원문을 통째로 넘기면 편하지만 상대가 필요로 하지 않는
항목(주소, 보험 정보, 담당의)까지 넘어간다. 유출 표면은 주고받는 항목 수에
비례한다.

**이 통보에는 평문 PHI 가 실린다.** DB 에는 암호문으로 들어가지만 상대 시스템은
평문이 필요하다. 그래서 전송 구간 보호가 필수다. 클라이언트는 `http://` 엔드포인트를
보면 기동 시 경고를 남긴다.

```
WARN 병원 B 연동이 평문 HTTP 입니다. 환자 정보가 전송 구간에 노출됩니다.
     운영에서는 HTTPS + 상호 인증(mTLS)이 필요합니다.
```

**`AdmissionNotice.toString()` 은 항상 마스킹한다.** SOAP 요청/응답을 로그로 남기는
습관이 흔한데, 그 로그가 평문 PHI 저장소가 되어 버린다.

---

## 7. 단순화한 것 (실무와 다른 점)

- **WSDL 을 mock 에서 생성했다.** 실제로는 상대가 준 WSDL 로 `wsimport` 를 돌려
  스텁을 만드는 것이 보통이다. 이 PoC 는 양쪽을 다 만들므로 계약을 자바
  인터페이스로 두는 편이 낫다(빌드에 코드 생성이 끼지 않고, 계약 변경이 컴파일
  에러로 드러난다). 대신 실제 상대와 붙일 때는 SEI 가 WSDL 과 어긋나지 않는지
  확인해야 한다.
- **날짜를 `XMLGregorianCalendar` 가 아니라 문자열로** 주고받는다. HL7 이 부분
  날짜를 허용하는데 달력 타입으로 바꾸면 없는 정보를 지어내게 된다(4단계와 같은 이유).
- **`@WebService` 어노테이션을 쓴다.** XML 설정 원칙의 예외인데, JAX-WS 규격 자체가
  어노테이션 기반이라 선택의 여지가 없다.

---

## 8. 검증 결과

```
ant docker-up && ant test → 85개 전부 통과 (약 64초)
  HospitalBSoapClientTest  13개 (신규) — mock 서버를 같은 JVM 에 띄워 실제 왕복
```

| 확인 | 결과 |
|---|---|
| Java 21 + jaxws-rt 2.3.7 로 mock 서버 기동, WSDL 자동 노출 | ✅ |
| 입원/퇴원 통보가 병원 B 에 도달 | ✅ |
| **한글 환자명이 왕복에서 깨지지 않음** (실제 SOAP 왕복으로 확인) | ✅ |
| 모든 필드(기관, 트리거, 환자, 병동, 입퇴원 시각)가 정확히 전달 | ✅ |
| 업무적 거절 → `Permanent`, 재시도 안 함 | ✅ |
| SOAP Fault → `Transient`, 재시도 | ✅ |
| 서버 없음 → `Transient` | ✅ |
| **읽기 타임아웃이 실제로 걸림** (10초 지연 vs 1초 타임아웃) | ✅ |
| 타임아웃 0 / 잘못된 URL → 기동 시점에 차단 | ✅ |
| **10 스레드 × 5회 동시 호출 안전** (포트 프록시 비공유 확인) | ✅ |
| 운영 XML(`app-context-ws.xml`)로 주입된 빈이 실제로 동작 | ✅ |
| `AdmissionNotice.toString()` 에 PHI 미노출 | ✅ |

아직 남은 것: ACK 생성(`HapiAckBuilder`)과 DLQ 재처리. 7단계에서 다루며,
그때 `app-context.xml` 전체가 처음으로 로딩된다.
