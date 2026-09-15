# 2단계 — Docker Compose(ActiveMQ, DB)와 Ant 빌드

이 단계의 산출물은 **인프라와 빌드**다. 아직 업무 코드는 없고, 기동 뼈대(`Main`)와
로깅 설정만 있다. 이 문서의 내용은 전부 실제로 실행해 검증했다(6절 참고).

| 파일 | 역할 |
|---|---|
| `docker/docker-compose.yml` | ActiveMQ Classic 5.18.3 + PostgreSQL 15 |
| `docker/activemq/activemq.xml` | 브로커 설정 (큐별 DLQ, 큐 사전 생성) |
| `docker/postgres/initdb/01-schema.sql` | 스키마 (initdb 자동 실행) |
| `ivy.xml`, `ivysettings.xml` | 의존성 정의 |
| `build.xml` | Ant 빌드 / 실행 / 인프라 제어 |
| `src/main/java/com/example/hl7poc/Main.java` | `ClassPathXmlApplicationContext` 기동 |
| `src/main/resources/logback.xml` | MSH-10 기반 MDC 로그 패턴 |
| `.env.example` | 환경변수 템플릿 (키는 코드에 두지 않는다) |

---

## 1. 빠른 시작

```bash
cp .env.example .env          # 필요하면 값 수정
ant docker-up                 # ActiveMQ + PostgreSQL 기동
ant resolve                   # 의존성 내려받기 (최초 1회, 약 1분)
ant dist                      # 컴파일 + 배포본 생성
ant -p                        # 전체 타깃 목록
```

| Ant 타깃 | 설명 |
|---|---|
| `resolve` | Ivy 로 의존성 해석 후 `lib/` 에 복사 |
| `compile` / `compile-test` | 컴파일 (`-Dskip.resolve=true` 로 해석 생략) |
| `dist` | `dist/*.jar` + `dist/lib/` 배포본 |
| `run-bridge` | ① 브리지 본체 |
| `run-mock` | ② 병원 B mock SOAP 서버 |
| `run-simulator` | ③ 병원 A 시뮬레이터 (`-Dargs="a01 3"`) |
| `run-dlq` | DLQ 재처리 도구 (`-Dargs="list"`) |
| `test` | JUnit 실행 |
| `docker-up` / `docker-down` / `docker-reset` / `docker-logs` | 인프라 제어 |
| `clean` / `distclean` | 산출물 / 의존성까지 삭제 |

접속 정보: ActiveMQ 콘솔 `http://localhost:8161/admin` (admin/admin),
OpenWire `tcp://localhost:61616`, PostgreSQL `localhost:5432/hl7poc`.

---

## 2. ActiveMQ 설정에서 바꾼 것

배포본 기본 `activemq.xml` 에서 세 군데만 손댔다.

**① 큐별 개별 DLQ.** 기본값은 모든 큐가 `ActiveMQ.DLQ` 하나를 공유한다. 큐가 늘어나면
어느 흐름이 죽었는지 DLQ 만 보고는 알 수 없고, 재처리 도구도 원본 큐를 되짚을 수 없다.

```xml
<deadLetterStrategy>
    <individualDeadLetterStrategy queuePrefix="DLQ." useQueueForQueueMessages="true"
                                  processNonPersistent="true" processExpired="true"/>
</deadLetterStrategy>
```

`Q.HL7.ADT.REQ` → `DLQ.Q.HL7.ADT.REQ` 로 1:1 대응된다. `processNonPersistent="true"` 는
비영속 메시지도 DLQ 로 보낸다. 시뮬레이터가 비영속으로 보내도 실패 메시지가 조용히
사라지지 않게 하려는 것이다.

**② 큐 사전 생성.** `<destinations>` 로 `Q.HL7.ADT.REQ` / `Q.HL7.ADT.ACK` /
`Q.HL7.ADT.PARK` 를 미리 만든다. 아무도 접속하지 않은 상태에서도 콘솔에 보인다.

**③ `schedulerSupport="true"`.** 지금은 클라이언트측 `RedeliveryPolicy` 를 쓰지만,
브로커측 `RedeliveryPlugin` 으로 갈아탈 때 필요하다. 플러그인 설정 자체는 파일에
주석으로 들어 있다.

> ⚠️ **둘을 동시에 켜면 재시도가 곱해진다** (3회 × 3회 = 최대 9회 처리).
> 브로커측으로 전환하려면 `app-context-jms.xml` 의 `RedeliveryPolicy` 를
> `maximumRedeliveries="0"` 으로 내려야 한다. 3단계에서 다시 짚는다.

> **Spring Boot라면**: 브로커 설정은 애플리케이션 밖의 일이라 똑같다. 달라지는 건
> 클라이언트 쪽(`spring.activemq.*`)뿐이다.

---

## 3. 스키마에서 정한 것

`docker/postgres/initdb/01-schema.sql` 한 곳만 스키마의 출처다.
(1단계 문서에는 `src/main/resources/sql/` 에도 두는 것으로 적었지만, 두 벌을 유지하면
반드시 어긋난다. `docker/` 쪽 하나로 합쳤다.)

- **`processed_message`** — 멱등성 선점. PK 는 `(sending_facility, msg_control_id)` 복합.
  Control ID 는 송신 시스템 안에서만 유일하므로 MSH-10 단독 PK 는 병원이 늘어나는
  순간 깨진다. `ack_payload` 를 함께 저장해 중복 수신 시 같은 ACK 를 재전송한다.
- **`adt_message`** — 업무 데이터. PHI 는 `_enc` 컬럼(암호문)으로만 저장하고,
  검색이 필요한 환자 ID 는 `patient_id_hash`(HMAC) 를 따로 둔다.
  HL7 원문은 보관하지 않고 `raw_msg_sha256` 지문만 남긴다.
- **`processing_log`** — MSH-10 기준 단계 추적. `detail` 에는 PHI 를 넣지 않는다.
- `status` 는 `CHECK` 제약으로 값을 강제한다. 오타 하나로 통계가 조용히 틀어지는 것을 막는다.

initdb 는 **데이터 볼륨이 비어 있을 때만** 실행된다. 스키마를 고쳤다면
`ant docker-reset` 으로 볼륨째 지우고 다시 올려야 한다.

---

## 4. 의존성에서 실제로 부딪힌 문제

### 4.1 Ant 단독이 아니라 Ant + Ivy

순수 Ant 로 가면 jar 44개를 손으로 받아 `lib/` 에 넣고 버전 충돌을 눈으로 맞춰야 한다.
의존성 **해석만** Ivy 에 맡기고 빌드는 Ant 그대로 두는 절충을 택했다. Ivy jar 자체도
`build.xml` 이 Maven Central 에서 한 번 내려받으므로, 빌드 머신에 미리 깔 것이 없다.

### 4.2 slf4j 버전이 조용히 올라갔다 — 실제로 발생한 문제

`ivy.xml` 에 `slf4j-api 1.7.36` + `logback-classic 1.2.13` 으로 적었는데,
해석 결과 `slf4j-api-2.0.13.jar` 가 내려왔다. **activemq-client 5.18.6 이 slf4j 2.0 에
맞춰 빌드**되어 있어 충돌 해소 과정에서 2.0 이 이긴 것이다.

이 조합(slf4j 2.0 + logback 1.2.x)은 **빌드도 성공하고 실행도 되지만 로그가 한 줄도
안 나온다.** logback 1.2 는 slf4j 1.7 의 `StaticLoggerBinder` 방식으로 붙는데 slf4j 2.0 은
`ServiceLoader` 방식이라 바인딩이 안 되고, no-op 로거로 조용히 떨어진다. 운영에서 만나면
원인 찾기가 가장 고약한 부류다.

1.7 로 강제로 내리는 선택지도 있지만, 그러면 ActiveMQ 가 slf4j 2 전용 메서드를 부를 때
`NoSuchMethodError` 가 난다. 그래서 **반대 방향으로 맞췄다: slf4j 2.0.13 + logback 1.3.14.**
(logback 1.3.x 는 Java 8 을 계속 지원한다. Java 11 이상을 요구하는 건 1.4.x 부터다.)

### 4.3 commons-logging 브리지는 넣지 않는다

`jcl-over-slf4j` 를 넣으려다 뺐다. Spring 5 는 `spring-jcl` 로 이미
`org.apache.commons.logging.*` 를 제공하며 slf4j 로 위임한다. 둘을 같이 넣으면 같은
클래스가 두 jar 에서 나와 클래스패스 순서에 따라 동작이 갈린다. `ivy.xml` 에서
`commons-logging` 은 전역 exclude 하고 `spring-jcl` 하나만 남긴다.

### 4.4 JMS API 아티팩트

`activemq-client 5.18.6` 이 `jakarta.jms-api 2.0.3` 을 전이 의존으로 끌어온다.
**2.0.3 까지는 패키지명이 `javax.jms` 그대로**라 레거시 제약을 만족한다. 여기에
`javax.jms:javax.jms-api` 를 따로 선언하면 같은 클래스가 중복되므로 선언하지 않는다.
ActiveMQ 5.19+ 는 `jakarta.jms`(네임스페이스 변경)로 넘어가므로 5.18 계열에 고정한다.

### 4.5 Java 버전

`build.xml` 은 `release="11"` 로 컴파일한다. `java.release` 프로퍼티만 `8` 로 바꾸면
Java 8 산출물이 나온다(단, JDK 22 이상에서는 `release=8` 이 제거되었다).
검증은 JDK 21 에서 `release=11` 로 했고, Spring 5.3 / ActiveMQ 5.18 / HAPI 2.3 모두
정상 로드됐다.

---

## 5. build.xml 에서 눈여겨볼 곳

- **`<junit>` 태스크를 쓰지 않는다.** Ant 의 `<junit>` 은 `ant-junit` 플러그인이 따로
  필요해서 환경을 탄다. `org.junit.runner.JUnitCore` 를 `<java>` 로 직접 호출하면
  추가 설치 없이 어디서나 돈다. 테스트 클래스 목록은 `<pathconvert>` + `package` 매퍼로
  만든다.
- **conf 별 lib 디렉터리.** `lib/runtime` 과 `lib/test` 로 나눠 받는다. 테스트 전용
  jar(JUnit, Mockito, 임베디드 브로커)이 런타임 클래스패스에 섞이지 않는다.
- **fat jar 을 만들지 않는다.** `dist/*.jar` + `dist/lib/` + MANIFEST `Class-Path` 조합이다.
  레거시 환경에서는 라이브러리만 교체하는 패치가 흔해서 이 편이 실제에 가깝다.
- **`-Dskip.resolve=true`** 로 의존성 해석을 건너뛴다. 오프라인이거나 반복 컴파일할 때 쓴다.
- **XML 주석 안에 `--` 를 쓸 수 없다.** `--env-file` 을 주석에 적었다가 빌드가 깨졌다.
  XML 규격상 금지된 문자열이라 Ant 가 파일 자체를 못 읽는다.

---

## 6. 검증 결과

이 환경(JDK 21, Ant 1.10.14, Docker)에서 실제로 돌려 확인한 것:

| 항목 | 결과 |
|---|---|
| `ant resolve` | 성공. runtime 44개 / test 58개 jar |
| `ant compile` / `ant dist` | 성공. `dist/hl7-adt-bridge-0.1.0.jar` + `dist/lib` 43개 |
| `ant test` (테스트 0개) | 성공 (조용히 건너뜀) |
| `ant run-bridge` | `app-context.xml` 부재를 명확히 로깅하고 비정상 종료 (3단계 전까지 정상 동작) |
| 로깅 스택 | slf4j 2.0.13 → logback 1.3.14 바인딩 확인. MDC `ctrl/type/pt` 가 콘솔·파일 양쪽에 출력됨 |
| 핵심 클래스 로드 | `ActiveMQConnectionFactory`, `PipeParser`, `DefaultMessageListenerContainer`, `JdbcTemplate`, `org.postgresql.Driver` 모두 OK |
| `docker compose up` | activemq / postgres 둘 다 `healthy` |
| ActiveMQ 커스텀 설정 적용 | 브로커명 `hl7poc` 로 기동, 스케줄러 스토어 생성 확인 |
| 큐 사전 생성 | `Q.HL7.ADT.REQ` / `Q.HL7.ADT.ACK` / `Q.HL7.ADT.PARK` 확인 |
| 스키마 생성 | 테이블 3개, 제약 5개, 인덱스 확인 |
| 멱등성 동작 | 같은 `(HOSP_A, MSG00000001)` 2회 INSERT → `INSERT 0 1` / `INSERT 0 0`. 송신기관만 다른 같은 Control ID 는 별개 행으로 저장됨 (복합 키 선택이 옳았음을 확인) |
| `status` CHECK 제약 | 잘못된 값 거부 확인 |

아직 검증하지 않은 것: 실제 JMS 송수신, DLQ 이동, SOAP 연동. 각각 3·6·7단계에서 다룬다.

### 환경 관련 참고

- Ant 의 `<echo>` 한글이 깨진다면 로케일 문제다. `LANG=C.UTF-8` 등 UTF-8 로케일에서는
  정상 출력된다. 빌드 동작에는 영향이 없다.
- 이 PoC 를 검증한 컨테이너에서는 Docker Hub 의 blob CDN 이 네트워크 정책에 막혀 있어
  `mirror.gcr.io` 로 이미지를 받아 로컬 태그를 붙여 썼다. 일반 개발 환경에서는
  `docker-compose.yml` 그대로 동작한다.
