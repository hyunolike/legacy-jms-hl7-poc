# 1단계 — 전체 아키텍처와 패키지 구조

> PoC 이름: `hl7-adt-bridge` (condb-secure의 구조를 HL7 v2 의료 도메인에 옮긴 학습용 모듈)
> 모든 테스트 데이터는 **가상의 합성 데이터**만 사용한다. 실제 환자 정보는 어떤 경우에도 넣지 않는다.

---

## 0. 가정 (Assumptions)

명시적으로 정해지지 않은 부분은 아래와 같이 가정했다. 다른 값이 필요하면 알려주면 바꾼다.

| 항목 | 가정값 | 비고 |
|---|---|---|
| JDK | Java 11 (LTS) | Java 8도 동작하도록 language level은 8로 유지 |
| Spring Framework | 5.3.39 | XML 설정 전용, `@ComponentScan` 미사용 |
| ActiveMQ | ActiveMQ **Classic** 5.18.x (`javax.jms`) | Artemis 아님. 5.19+는 jakarta로 넘어가므로 5.18 고정 |
| DB | PostgreSQL 15 (Docker) | Oracle XE는 DAO의 SQL만 교체하면 되도록 분리 |
| HL7 파서 | HAPI HL7v2 2.3 (`hapi-base`, `hapi-structures-v25`) | HL7 v2.5 구조체 사용 |
| HL7 버전 | v2.5, `ADT^A01` / `ADT^A03` | MSH-12 = `2.5` |
| 빌드 | Apache **Ant + Ivy** | 순수 Ant는 jar 44개를 수동 관리해야 해서 비현실적 (2단계에서 확정) |
| SOAP | JAX-WS RI (`jaxws-rt`) + 내장 Endpoint | 병원 B mock 서버를 별도 `main()`으로 기동 |
| 트랜잭션 | XA 미사용 (Best-Effort 1PC + 멱등성) | 이유는 4.2절 |
| 인코딩 | HL7 메시지는 UTF-8 (`MSH-18 = UNICODE UTF-8`) | 실무에서는 EUC-KR/ISO IR87도 흔함 |

---

## 1. 큰 그림

```
 ┌──────────────────┐
 │  병원 A 시뮬레이터  │  (simulator/HospitalASimulator.java, 독립 main)
 │  ADT^A01/A03 발행  │
 └─────────┬────────┘
           │ TextMessage(ER7 pipe-delimited)
           │ JMSXGroupID = 환자ID 해시
           ▼
   ╔═══════════════════════════════════════════════════════════════╗
   ║  ActiveMQ Classic 5.18  (Docker)                              ║
   ║   Q.HL7.ADT.REQ   ──재시도 소진──▶  DLQ.Q.HL7.ADT.REQ          ║
   ║   Q.HL7.ADT.ACK   ◀──ACK 발행                                  ║
   ║   Q.HL7.ADT.PARK  ◀──재시도 불가(영구) 오류 격리               ║
   ╚═════════┬═════════════════════════════════════════════════════╝
             │ DefaultMessageListenerContainer
             │ (concurrentConsumers 3~10, sessionTransacted)
             ▼
 ┌───────────────────────────────────────────────────────────────────┐
 │  Standalone JVM — Main.java → ClassPathXmlApplicationContext      │
 │                                                                   │
 │  jms      AdtMessageListener.onMessage(Message)                   │
 │            │                                                      │
 │  common    ├─▶ Hl7Parser.parse()  (HAPI PipeParser + Validation)  │
 │            │      └─ MSH-9 타입, MSH-10 Control ID, PID 세그먼트  │
 │            │                                                      │
 │  adt       ├─▶ AdtProcessingService.process(AdtEvent)             │
 │            │      ├─ 1) 멱등성 선점: INSERT msg_control_id (UQ)   │
 │  secure    │      ├─ 2) PhiCipher.encrypt(이름/생년월일/연락처)   │
 │  adt.dao   │      ├─ 3) DB 적재: 메타데이터 + 암호문              │
 │  ws        │      └─ 4) 병원 B SOAP 호출 (JAX-WS client)          │
 │            │                                                      │
 │  common    └─▶ AckBuilder.build(AA|AE|AR)                         │
 │  jms          └─▶ AckPublisher(JmsTemplate) ─▶ Q.HL7.ADT.ACK      │
 └───────────────────────────────────────────────────────────────────┘
             │ SOAP/HTTP
             ▼
 ┌──────────────────┐        ┌──────────────────────┐
 │  병원 B mock SOAP │        │  PostgreSQL (Docker) │
 │  (ws/mock, JAX-WS)│        │  adt_message 등      │
 └──────────────────┘        └──────────────────────┘
```

### 프로세스는 3개 (각각 `main()`)

| 프로세스 | 클래스 | 역할 | condb-secure 대응 |
|---|---|---|---|
| ① 브리지 (본체) | `Main` | 리스너 컨테이너 기동 후 대기 | `main()` + `ClassPathXmlApplicationContext` 그대로 |
| ② 병원 B mock | `MockHospitalBServer` | JAX-WS `Endpoint.publish()` | GDS 상대 시스템 stub |
| ③ 병원 A 시뮬레이터 | `HospitalASimulator` | ADT 메시지를 큐에 발행 | PNR 요청 발행 측 |

> **Spring Boot라면**: ①은 `@SpringBootApplication` + `spring-boot-starter-activemq`의 `@JmsListener` 하나로 끝나고, ②는 `spring-boot-starter-web-services`, ③은 `CommandLineRunner`로 대체된다. 여기서는 레거시 재현이 목적이므로 `ClassPathXmlApplicationContext`를 직접 연다.

---

## 2. 패키지 구조 (condb-secure 대응)

```
com.example.hl7poc
├── Main.java                         ← ClassPathXmlApplicationContext("classpath:spring/app-context.xml")
│
├── common/                           ← condb-secure의 common
│   ├── hl7/
│   │   ├── Hl7Parser.java            (interface)  parse / encode
│   │   ├── HapiHl7Parser.java        HAPI PipeParser 래퍼 (+ ValidationContext)
│   │   ├── Hl7MessageContext.java    MSH-9/10/12, 수신시각, 원문 등 파싱 결과 홀더
│   │   └── AckBuilder.java           AA/AE/AR ACK 생성 (MSA-1/2, ERR 세그먼트)
│   ├── dto/
│   │   ├── AdtEvent.java             이벤트 단위 DTO (A01/A03 공통)
│   │   ├── PatientInfo.java          PID 평문 DTO (toString은 항상 마스킹)
│   │   └── ProcessResult.java        처리 결과 + ACK 코드 + 오류코드
│   ├── exception/
│   │   ├── Hl7ParseException.java        → 영구 오류 (AR)
│   │   ├── PermanentProcessingException  → 영구 오류 (AE), 재시도 금지
│   │   └── TransientProcessingException  → 일시 오류, 재시도 대상
│   └── util/
│       ├── TraceContext.java         MDC put/clear (msgCtrlId, msgType, patientKey)
│       └── MaskingUtils.java         이름/생년월일/연락처 마스킹 규칙
│
├── jms/                              ← condb-secure의 jms (PNR 요청/응답 큐)
│   ├── listener/AdtMessageListener.java     implements javax.jms.MessageListener
│   ├── publisher/AckPublisher.java          JmsTemplate으로 ACK 발행
│   ├── dlq/DlqReprocessor.java              DLQ → 원본 큐 재투입 (독립 main)
│   └── support/
│       ├── JmsHeaders.java           JMSXGroupID, X-MSG-CTRL-ID 등 헤더 상수
│       └── Hl7TextMessageReader.java TextMessage/BytesMessage 양쪽 수용
│
├── adt/                              ← condb-secure의 condb (업무 서비스 + DB)
│   ├── service/
│   │   ├── AdtProcessingService.java        (interface)
│   │   └── AdtProcessingServiceImpl.java    @Transactional 대신 XML tx:advice 적용
│   ├── dao/
│   │   ├── AdtMessageDao.java / AdtMessageDaoJdbc.java    JdbcTemplate
│   │   └── ProcessedMessageDao.java / ...Jdbc.java        멱등성 테이블
│   └── domain/
│       ├── AdtMessageRecord.java
│       └── ProcessStatus.java        RECEIVED / PERSISTED / FORWARDED / FAILED / PARKED
│
├── secure/                           ← condb-secure의 secure (암복호화)
│   ├── PhiCipher.java                (interface) encrypt/decrypt
│   ├── AesGcmPhiCipher.java          AES-256-GCM, IV 12B 랜덤, 출력 v1:{iv}:{ct}
│   ├── key/
│   │   ├── KeyProvider.java          (interface) currentKey() / keyById()
│   │   ├── EnvKeyProvider.java       환경변수 HL7POC_PHI_KEY (Base64)
│   │   └── KeyStoreKeyProvider.java  JCEKS 키스토어 (기본 구현)
│   └── mask/PhiMasker.java           로그 출력 직전 마스킹
│
├── ws/                               ← condb-secure의 ws (GDS SOAP)
│   ├── client/
│   │   ├── HospitalBNotifier.java        (interface)
│   │   └── HospitalBSoapClient.java      JAX-WS Dispatch 또는 wsimport 스텁
│   ├── mock/
│   │   ├── HospitalBAdmissionEndpoint.java   @WebService 구현 (JAX-WS는 어노테이션 필수)
│   │   └── MockHospitalBServer.java          Endpoint.publish (독립 main)
│   └── dto/AdmissionNotice.java / AdmissionNoticeResponse.java
│
└── simulator/
    └── HospitalASimulator.java       샘플 HL7 파일을 읽어 큐에 발행 (독립 main)
```

### 대응표 재확인

| condb-secure | 이 PoC | 핵심 차이 |
|---|---|---|
| `jms` (PNR 요청/응답) | `jms` (ADT 요청 / ACK 응답 / DLQ) | 응답이 자유 포맷이 아니라 **HL7 ACK 규격** |
| `condb` (업무 + DB) | `adt` (ADT 처리 + DAO) | 업무 키가 PNR → **MSH-10 Control ID** |
| `secure` (암복호화) | `secure` (PHI/PII 암복호화, 키 관리) | 대상이 PID-5/7/13 등 **환자 식별정보** |
| `ws` (GDS SOAP) | `ws` (병원 B SOAP + mock 서버) | mock 서버를 같은 리포에 포함 |
| `common` | `common` (HL7 파서 래퍼, DTO, 예외) | HAPI 의존을 이 패키지에만 가둔다 |

---

## 3. 디렉터리 구조 (Ant 레이아웃)

```
legacy-jms-hl7-poc/
├── build.xml                      Ant 빌드 (compile / jar / run-bridge / run-mock / run-simulator)
├── ivy.xml, ivysettings.xml       의존성 (Ivy)
├── lib/                           Ivy가 내려받는 jar (.gitignore)
├── docker/
│   ├── docker-compose.yml         activemq + postgres
│   ├── activemq/activemq.xml      individualDeadLetterStrategy, 큐 사전 생성
│   └── postgres/initdb/01-schema.sql   ← 스키마의 유일한 출처
├── src/
│   ├── main/
│   │   ├── java/com/example/hl7poc/...   (2절 구조)
│   │   └── resources/
│   │       ├── spring/
│   │       │   ├── app-context.xml            루트 (import만)
│   │       │   ├── app-context-common.xml     프로퍼티, 파서, 마스커
│   │       │   ├── app-context-datasource.xml DataSource, JdbcTemplate, txManager
│   │       │   ├── app-context-jms.xml        ConnectionFactory, DMLC, JmsTemplate
│   │       │   ├── app-context-adt.xml        서비스, DAO, tx:advice
│   │       │   ├── app-context-secure.xml     PhiCipher, KeyProvider
│   │       │   └── app-context-ws.xml         SOAP 클라이언트
│   │       ├── config/hl7poc.properties       (비밀값 제외, ${ENV} 치환)
│   │       ├── sql/schema.sql
│   │       └── logback.xml
│   └── test/
│       ├── java/com/example/hl7poc/...
│       └── resources/hl7-samples/
│           ├── adt_a01_admit.hl7
│           ├── adt_a03_discharge.hl7
│           ├── adt_a01_missing_pid.hl7        (검증 실패 케이스)
│           └── adt_a01_broken_msh.hl7         (파싱 실패 케이스)
├── docs/
│   ├── 01-architecture.md          ← 이 문서
│   └── ...                         단계별 문서
└── README.md
```

> **Spring Boot라면**: `src/main/resources/application.yml` 하나에 DataSource/JMS 설정이 모이고, XML 7개는 `@Configuration` 클래스 또는 스타터의 auto-configuration으로 사라진다.

---

## 4. 설계 포인트별 결정 사항

### 4.1 멱등성 — MSH-10 기준

- `processed_message(msg_control_id PK)` 테이블에 **먼저 INSERT**해서 자리를 선점한다. `INSERT ... ON CONFLICT DO NOTHING`의 영향 행 수가 0이면 이미 처리된 메시지이므로, 업무 로직을 건너뛰고 **저장해 둔 이전 ACK를 그대로 재전송**한 뒤 커밋한다(ACK 유실 대비).
- 유니크 키는 `MSH-10` 단독이 아니라 `(sending_facility, msg_control_id)` 복합으로 잡는다. Control ID는 송신 시스템 내에서만 유일하기 때문이다.
- 중복 판정은 DB 유니크 제약이 최종 심판이다. 애플리케이션의 `SELECT` 선조회는 동시성 상황에서 신뢰할 수 없으므로 참고용으로만 쓴다.

> **Spring Boot라면**: 동일하다. 멱등성은 프레임워크가 해결해 주지 않는다. `@Transactional` 위치만 어노테이션으로 바뀔 뿐.

### 4.2 트랜잭션 경계 — XA를 쓰지 않는 이유와 대안

세 가지 자원(JMS 소비, DB 쓰기, SOAP 호출)이 얽힌다. SOAP은 애초에 트랜잭션 자원이 아니므로 XA로 묶어도 완전한 원자성은 얻을 수 없다. 그래서 **Best-Effort 1PC + 멱등성** 조합을 택한다.

```
DMLC(sessionTransacted=true, transactionManager=DataSourceTransactionManager)
 └─ [DB tx 시작]
      1) 멱등성 INSERT (중복이면 여기서 조기 종료)
      2) PHI 암호화 + adt_message INSERT
      3) 병원 B SOAP 호출        ← 여기서 실패하면 DB도 롤백, JMS도 롤백 → 재시도
      4) 상태 FORWARDED 로 UPDATE
    [DB tx 커밋]
 └─ [JMS session 커밋] → 메시지 소비 확정, 이 시점에 ACK도 함께 전송
```

- 커밋 순서는 **DB 먼저, JMS 나중**이다. DB 커밋 후 JMS 커밋이 실패하면 메시지가 재전송되지만, 4.1의 멱등성이 두 번째 처리를 막는다. 반대 순서는 메시지 유실이 되므로 쓰지 않는다.
- ACK 발행은 `TransactionAwareConnectionFactoryProxy`로 **리스너의 DB 트랜잭션에 동기화**시킨다. 그래야 "처리 롤백했는데 성공 ACK만 나가는" 상황이 없다. (3단계 실측 정정: 컨슈머의 JMS 세션을 공유하는 것이 아니라, 별도 세션의 commit/rollback을 DB 트랜잭션 완료 시점에 묶는 방식이다. 자세한 내용은 [03-spring-xml.md](03-spring-xml.md) 3.1절.)
- SOAP 호출을 DB 트랜잭션 안에 두면 커넥션을 오래 잡는다. PoC에서는 단순함을 위해 안에 두되, 타임아웃(connect 3s / read 5s)을 반드시 건다. 실무 규모에서는 `adt_message`에 `PENDING_FORWARD`로 커밋하고 별도 워커가 전송하는 아웃박스 패턴이 정석이며, 이 대안도 문서에 남긴다.
- acknowledge 모드: `sessionTransacted=true`를 쓰므로 `AUTO_ACKNOWLEDGE`/`CLIENT_ACKNOWLEDGE`는 무시된다. 트랜잭션을 끄고 간다면 `CLIENT_ACKNOWLEDGE` + 수동 `message.acknowledge()`가 차선이지만, 재시도 제어가 약해 선택하지 않는다.

> **Spring Boot라면**: `spring.jms.listener.session-transacted=true` 한 줄 + `@Transactional`. 커밋 순서와 멱등성 고민은 똑같이 남는다.

### 4.3 동시성과 순서 보장

- `concurrentConsumers=3`, `maxConcurrentConsumers=10`, `idleConsumerLimit=1` — 처리량과 커넥션 수의 절충.
- **같은 환자의 A01 → A03 순서가 뒤집히면 안 된다.** ActiveMQ **Message Group**을 쓴다. 발행 측이 `JMSXGroupID = sha256(환자ID) 앞 16자`를 세팅하면, 브로커가 같은 그룹을 항상 같은 컨슈머에 고정(pin)한다. 그룹 간에는 병렬, 그룹 내에서는 직렬이 된다.
- 컨슈머가 죽으면 그룹이 다른 컨슈머로 재배정되면서 순간적으로 순서가 흔들릴 수 있다. 그래서 **DB에도 방어선**을 둔다: 같은 환자의 이벤트 시각(MSH-7)이 이미 저장된 최신 이벤트보다 과거이면 "늦게 도착한 옛 메시지"로 보고 무시하거나 별도 기록한다.
- 메시지 그룹을 쓸 때는 `prefetch`를 낮춘다(1~10). prefetch가 크면 한 컨슈머가 그룹을 잔뜩 쥐고 있어 부하가 쏠린다.

> **Spring Boot라면**: `spring.jms.listener.concurrency=3-10`. 순서 보장은 역시 브로커 기능(Message Group)에 의존한다.

### 4.4 재시도와 DLQ

오류를 **일시적(재시도 가치 있음)** 과 **영구적(재시도 무의미)** 로 나누는 것이 핵심이다.

| 오류 | 예 | 처리 | ACK |
|---|---|---|---|
| 파싱 실패 | MSH 깨짐, 인코딩 문자 오류 | 롤백하지 않고 `Q.HL7.ADT.PARK`로 이동 후 **커밋** | `AR` (Reject) |
| 검증 실패 | PID-3 없음, 필수 필드 누락 | 위와 동일 | `AE` (Error) |
| 일시 오류 | DB 커넥션 끊김, SOAP 타임아웃 | 예외를 던져 **롤백** → RedeliveryPolicy에 따라 재시도 | ACK 없음 (재시도) |
| 재시도 소진 | 위 재시도가 3회 실패 | 브로커가 `DLQ.Q.HL7.ADT.REQ`로 이동 | ACK 없음 |

- 재시도 무의미한 메시지를 롤백시키면 최대 재시도 횟수만큼 CPU를 태우고 DLQ에 들어간다. 그래서 파싱/검증 실패는 **정상 커밋 + 격리 큐(PARK)** 로 즉시 빼낸다. 이게 condb-secure류 구조에서 가장 흔한 실수 지점이다.
- `RedeliveryPolicy`: `maximumRedeliveries=3`, `initialRedeliveryDelay=2000ms`, `useExponentialBackOff=true`, `backOffMultiplier=2` → 2s, 4s, 8s.
- 클라이언트 측 재전송은 그 컨슈머를 붙잡아 둔다. 지연을 길게 가져가려면 브로커 측 `RedeliveryPlugin`(스케줄러 기반)이 낫다. PoC에서는 클라이언트 정책으로 시작하고, `activemq.xml`에 `RedeliveryPlugin` 예시를 주석으로 함께 넣는다.
- DLQ 정책: `individualDeadLetterStrategy queuePrefix="DLQ." useQueueForQueueMessages="true"` → 큐별 DLQ로 분리.
- 재처리: `DlqReprocessor`가 DLQ를 **browse → 선택 → 원본 큐로 재발행 → DLQ에서 consume**. 재처리해도 멱등성 덕분에 중복 적재가 없다. 재처리 횟수는 커스텀 헤더 `X-REPROCESS-COUNT`로 누적해 무한 루프를 막는다.

> **Spring Boot라면**: `DefaultErrorHandler` / `spring.jms.listener.*`로 비슷하게 잡지만, "영구 오류는 재시도하지 않는다"는 분류는 직접 짜야 한다.

### 4.5 보안

- **키 하드코딩 금지.** `KeyProvider`를 인터페이스로 두고 기본 구현은 JCEKS 키스토어(`EnvKeyProvider`는 로컬 개발용). 키스토어 경로와 비밀번호는 환경변수 `HL7POC_KEYSTORE_PATH` / `HL7POC_KEYSTORE_PASS`로 주입하고, Spring XML에서는 `${...}`로 받는다. properties 파일에는 **비밀값을 넣지 않고** placeholder만 둔다.
- 암호문 포맷: `v1:{keyId}:{base64(iv)}:{base64(ciphertext||tag)}`. 접두 버전과 keyId를 넣어야 나중에 키 로테이션이 가능하다. IV는 매 호출 랜덤 12바이트(GCM에서 IV 재사용은 치명적).
- 검색이 필요한 필드(환자 ID)는 암호문으로는 검색이 안 되므로 **HMAC-SHA256 블라인드 인덱스**(`patient_id_hash`)를 따로 저장한다. 평문 SHA256은 사전 공격에 약하므로 키를 쓴 HMAC이어야 한다.
- 로그 마스킹: `PhiMasker` + DTO의 `toString()` 오버라이드 + logback 커스텀 컨버터. 원칙은 **원문 HL7 메시지 전체를 INFO 레벨로 찍지 않는다**. DEBUG에서만, 그것도 마스킹해서 출력한다.
- `.gitignore`에 `*.jceks`, `*.p12`, `.env` 추가.

> **Spring Boot라면**: Jasypt나 Spring Cloud Vault를 붙이는 게 일반적이지만, 키 라이프사이클 설계는 동일하게 직접 해야 한다.

### 4.6 관측성 — MSH-10 기준 추적

- 리스너 진입 즉시 MDC에 `msgCtrlId`(MSH-10), `msgType`(MSH-9), `patientKey`(해시 앞 8자), `jmsMsgId`를 넣고, `finally`에서 반드시 `MDC.clear()`.
- logback 패턴: `%d{ISO8601} %-5level [%thread] [ctrl=%X{msgCtrlId} type=%X{msgType} pt=%X{patientKey}] %logger{36} - %msg%n`
- 단계별 고정 문구로 로그를 남겨 grep 가능하게 한다: `STEP=RECEIVED` → `PARSED` → `DEDUP_OK|DEDUP_SKIP` → `ENCRYPTED` → `PERSISTED` → `FORWARDED` → `ACK_SENT`. 하나의 MSH-10으로 전 구간을 이어 볼 수 있다.
- `adt_message.status`와 `processing_log` 테이블에도 같은 단계를 적재해 DB만으로 추적 가능하게 한다.
- ACK 발행 시 JMS 헤더에 `X-MSG-CTRL-ID`를 복사해, 응답 큐 쪽에서도 상관관계를 잡을 수 있게 한다.

> **Spring Boot라면**: Micrometer Tracing이 traceId를 자동 전파하지만, "MSH-10을 업무 상관관계 키로 쓴다"는 결정은 동일하게 필요하다.

---

## 5. DB 스키마 초안

> 확정본은 `docker/postgres/initdb/01-schema.sql` 에 있다(2단계에서 컬럼을 보강했다).
> 아래는 설계 의도를 보기 위한 축약본이다.


```sql
-- 멱등성 선점 테이블 (가장 먼저 INSERT)
CREATE TABLE processed_message (
    sending_facility  VARCHAR(64)  NOT NULL,
    msg_control_id    VARCHAR(64)  NOT NULL,
    first_seen_at     TIMESTAMP    NOT NULL DEFAULT now(),
    ack_code          CHAR(2),              -- AA / AE / AR (재전송용)
    ack_payload       TEXT,                 -- 생성했던 ACK 원문
    PRIMARY KEY (sending_facility, msg_control_id)
);

-- 업무 데이터 (PHI는 암호문으로만)
CREATE TABLE adt_message (
    id                BIGSERIAL PRIMARY KEY,
    sending_facility  VARCHAR(64)  NOT NULL,
    sending_app       VARCHAR(64),
    msg_control_id    VARCHAR(64)  NOT NULL,
    msg_type          VARCHAR(16)  NOT NULL,   -- ADT^A01
    trigger_event     VARCHAR(8)   NOT NULL,   -- A01 / A03
    hl7_version       VARCHAR(8),
    msg_datetime      TIMESTAMP,               -- MSH-7
    patient_id_hash   VARCHAR(64)  NOT NULL,   -- HMAC-SHA256 블라인드 인덱스
    patient_name_enc  TEXT,                    -- v1:kid:iv:ct
    patient_dob_enc   TEXT,
    patient_phone_enc TEXT,
    patient_sex       CHAR(1),                 -- 식별성 낮아 평문 허용
    raw_msg_sha256    CHAR(64)     NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    retry_count       INT          NOT NULL DEFAULT 0,
    last_error        VARCHAR(500),
    created_at        TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_adt_msg UNIQUE (sending_facility, msg_control_id)
);
CREATE INDEX ix_adt_patient ON adt_message (patient_id_hash, msg_datetime DESC);

-- 단계별 추적 로그
CREATE TABLE processing_log (
    id             BIGSERIAL PRIMARY KEY,
    msg_control_id VARCHAR(64) NOT NULL,
    step           VARCHAR(24) NOT NULL,
    detail         VARCHAR(500),
    logged_at      TIMESTAMP   NOT NULL DEFAULT now()
);
```

- 원문 HL7 전체는 저장하지 않고 **SHA-256 해시만** 둔다. 원문에는 PHI가 그대로 있어 DB 유출 시 피해가 크다. 원문 보관이 요건이면 별도 암호화 컬럼으로 분리한다(현재는 미보관 가정).

---

## 6. 단계 진행 계획

| 단계 | 산출물 | 상태 |
|---|---|---|
| 1 | 아키텍처 + 패키지 구조 (이 문서) | ✅ 완료 |
| 2 | `docker-compose.yml`, `build.xml`, `ivy.xml` | ✅ 완료 → [02-infrastructure.md](02-infrastructure.md) |
| 3 | `app-context.xml` + 기능별 XML 5종 | ✅ 완료 → [03-spring-xml.md](03-spring-xml.md) |
| 4 | HL7 샘플 8종 + `HapiHl7Parser` | ✅ 완료 → [04-hl7-parser.md](04-hl7-parser.md) |
| 5 | 리스너 / 서비스 / DAO / 암복호화 | ✅ 완료 → [05-listener-service-dao-secure.md](05-listener-service-dao-secure.md) |
| 6 | 병원 B mock SOAP 서버 + 클라이언트 | ← 다음 |
| 7 | ACK 생성, 재시도, DLQ 재처리 | 대기 |
| 8 | 병원 A 시뮬레이터 + 통합 테스트 시나리오 | 대기 |
| 9 | README (다이어그램, 실행 방법, condb-secure 비교) | 대기 |
