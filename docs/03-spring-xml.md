# 3단계 — `app-context.xml` 및 기능별 XML

| 파일 | 대응하는 condb-secure 계층 | 내용 |
|---|---|---|
| `app-context.xml` | — | `<import>` 만 하는 루트 |
| `app-context-common.xml` | `common` | 프로퍼티 치환, HAPI 컨텍스트, 파서, ACK 빌더 |
| `app-context-datasource.xml` | — | DBCP2 풀, JdbcTemplate, 트랜잭션 매니저 |
| `app-context-jms.xml` | `jms` | ConnectionFactory, 재전송 정책, DMLC, JmsTemplate |
| `app-context-adt.xml` | `condb` | 서비스, DAO, `tx:advice` 트랜잭션 경계 |
| `app-context-secure.xml` | `secure` | 키 공급자, AES-GCM, 블라인드 인덱스, 마스커 |
| `app-context-ws.xml` | `ws` | 병원 B SOAP 클라이언트 |
| `config/hl7poc.properties` | — | `${ENV:기본값}` 형태의 설정 |

업무 빈이 가리키는 클래스(파서, 서비스, DAO, 암호화 등)는 4~6단계 산출물이라 아직
없다. 그래서 이 단계의 검증은 **인프라 XML(datasource + jms)을 그대로 import 하고
업무 빈만 스텁으로 가린** 통합 테스트로 했다(`SpringWiringTest`). 설정을 복사하지
않고 운영 XML 을 직접 import 하는 것이 핵심이다. 복사하면 검증한 설정과 실제로 도는
설정이 갈라져 검증이 의미를 잃는다.

---

## 1. import 순서는 (거의) 의미가 없다

레거시 코드베이스에서 "순서가 중요하니 건드리지 말 것" 주석을 자주 보는데, 대부분
사실이 아니다.

- 빈 의존성은 참조로 해석되므로 정의 순서와 무관하다.
- 리스너 컨테이너는 모든 싱글턴이 만들어진 뒤 `SmartLifecycle.start()` 에서 뜨므로,
  `jms` 를 앞에 둔다고 먼저 메시지를 받지 않는다.

순서가 실제로 의미를 갖는 경우는 하나뿐이다: **같은 id 를 두 번 정의하면 나중 것이
이긴다.** 이 성질을 테스트에서 쓴다. `test-infra-context.xml` 은 운영 XML 을 import
한 **뒤에** `adtMessageListener` / `ackPublisher` 를 다시 정의해 스텁으로 가린다.
앞선 정의는 버려지므로 아직 존재하지 않는 클래스의 로딩도 일어나지 않는다.

---

## 2. 트랜잭션 경계

```
DMLC(sessionTransacted=true, transactionManager=DataSourceTransactionManager)
 └─ [DB 트랜잭션 시작]
      onMessage(): 멱등성 선점 → 암호화 → DB 적재 → SOAP 호출 → ACK 발행
    [DB 커밋]
    [JMS 세션 커밋]  ← 메시지 소비 확정
    [ACK 세션 커밋]  ← ACK 가 실제로 나가는 시점
```

DB 가 먼저, JMS 가 나중이다. 그 사이에 죽으면 메시지가 재전송되지만
`(sending_facility, MSH-10)` 멱등성이 두 번째 처리를 막는다. 반대 순서였다면
메시지 유실이 된다.

`tx:advice` 는 `adt` 서비스에도 걸어 두었다. 리스너 경로에서는 `REQUIRED` 로
기존 트랜잭션에 합류만 하므로 중첩이 생기지 않는다. 이게 필요한 이유는 JMS 를 거치지
않는 경로 — DLQ 재처리 도구, 서비스 단위 테스트 — 때문이다.

`rollback-for="java.lang.Exception"` 을 명시한 이유: Spring 기본값은
`RuntimeException` 과 `Error` 에만 롤백한다. 체크 예외를 쓰는 레거시 코드에서
"예외는 났는데 커밋은 됐다"가 여기서 나온다.

> **Spring Boot라면**: `spring.jms.listener.session-transacted=true` +
> `@Transactional(rollbackFor=...)`. 커밋 순서와 멱등성 고민은 그대로 남는다.

---

## 3. 실측으로 바로잡은 것 두 가지

### 3.1 ACK 롤백은 "세션 공유"가 아니라 "트랜잭션 동기화"다 — 1단계 설명 정정

1단계 문서에 *"ACK 발행을 리스너의 같은 JMS 세션에 참여시킨다"* 고 적었다.
**결과는 맞지만 메커니즘 설명이 틀렸다.**

실제 동작은 이렇다. `TransactionAwareConnectionFactoryProxy` 에
`synchedLocalTransactionAllowed=true` 를 주면, 현재 스레드에 활성 스프링 트랜잭션
(여기서는 DMLC 가 연 **DB 트랜잭션**)이 있을 때 **별도의 JMS 세션**을 만들고 그
세션의 commit/rollback 을 DB 트랜잭션 완료 시점에 묶는다. 동기화의 기준점은 컨슈머
세션이 아니라 DB 트랜잭션이다.

차이가 중요한 이유: 컨슈머 세션이 기준이라면 리스너 컨테이너의 `cacheLevel` 에 따라
동작이 갈리지만, DB 트랜잭션이 기준이므로 `cacheLevel` 과 **무관하게** 성립한다.
아래 3.2 의 대조 실험에서 두 설정 모두 ACK 롤백이 동작한 것이 이 설명의 근거다.

남는 빈틈: ACK 세션 커밋은 DB 커밋 **이후**다. 그 사이에 죽으면 DB 는 커밋됐는데
ACK 는 안 나간 상태가 된다. 이때는 상대가 재전송하고, 멱등성 테이블에 저장해 둔
ACK 를 그대로 되돌려준다(1단계 4.1절). 설계가 이미 이 경우를 덮고 있다.

### 3.2 `cacheLevel` 을 명시하지 않으면 재전송 지연이 통째로 사라진다

처음에는 `cacheLevel` 을 지정하지 않았다. DMLC 는 `CACHE_AUTO` 일 때 **"외부
트랜잭션 매니저가 있으면 `CACHE_NONE`"** 을 고른다. 그 상태로 테스트했더니 재시도
횟수(4회)와 DLQ 이동은 정상인데 **간격이 0.03초**였다. 설정한 2s/4s/8s 지수 백오프가
전혀 적용되지 않았다.

같은 테스트를 `cacheLevel` 만 바꿔 두 번 돌렸다.

| `cacheLevel` | 재배달 간격 (실측) | 컨슈머 스레드 | ACK 롤백 | 재시도 횟수 | DLQ |
|---|---|---|---|---|---|
| `CACHE_NONE` (CACHE_AUTO의 선택) | 0.023s / 0.023s / 0.056s | 매번 다름 | ✅ | 4회 ✅ | ✅ |
| **`CACHE_CONSUMER`** | **2.023s / 4.014s / 8.014s** | 동일 유지 | ✅ | 4회 ✅ | ✅ |

**원인**: 클라이언트측 `RedeliveryPolicy` 의 지연은 "그 컨슈머를 잠시 쉬게 하는"
방식으로 구현되어 있다. `CACHE_NONE` 은 수신할 때마다 컨슈머를 만들고 버리므로,
쉬게 할 컨슈머가 남아 있지 않아 지연이 증발한다.

**왜 위험한가**: 다른 지표는 전부 정상으로 보인다. 재시도도 하고 DLQ 로도 간다.
그런데 지연 없는 재시도는 일시 장애(DB 순단, 상대 시스템 재기동)가 복구될 시간을
주지 못하므로 **사실상 재시도가 아니다.** 4번 연속 같은 이유로 실패하고 DLQ 로 간다.

**결정**: `cacheLevelName="CACHE_CONSUMER"` 를 명시한다. 3.1 에서 확인했듯 ACK
트랜잭션성은 `cacheLevel` 과 무관하므로, 안전성을 잃지 않고 지연만 되찾는다.

**회귀 방지**: `재전송_지연이_지수_백오프로_적용된다` 테스트가 간격의 하한
(1.5s / 3.5s / 7s)을 검사한다. 이 테스트가 실제로 잡는지도 확인했다 —
`HL7POC_JMS_CACHE_LEVEL=CACHE_NONE` 으로 되돌리면 `gap[0]=23ms` 로 실패한다.

> 브로커측 `RedeliveryPlugin` 으로 옮기면 이 문제 자체가 사라진다(지연을 브로커
> 스케줄러가 관리하므로 컨슈머 수명과 무관). 대신 클라이언트측
> `maximumRedeliveries` 를 0 으로 내려야 재시도가 곱해지지 않는다.
> 7단계에서 두 방식을 비교한다.

---

## 4. 그 밖의 설정 판단

### 커넥션 풀 크기 ≥ 최대 컨슈머 수

`db.pool.maxTotal(15) > jms.consumers.max(10)`. 같거나 작으면 모든 컨슈머가 커넥션을
하나씩 쥔 상태에서 DLQ 재처리나 헬스체크가 들어오는 순간 `maxWaitMillis` 만큼
기다리다 죽는다.

`testOnBorrow` + `validationQuery` 는 DB 재기동이나 방화벽 idle timeout 으로 끊긴
커넥션을 빌려주는 사고를 막는다. 레거시 환경에서 "새벽에만 나는" 장애의 단골이다.

### ConnectionFactory 세 개의 역할 분담

| 빈 | 쓰는 곳 | 이유 |
|---|---|---|
| `jmsConnectionFactory` | 리스너 컨테이너 | DMLC 는 자체 캐싱을 한다. 캐싱 팩토리와 겹치면 세션 수명 관리가 어긋난다 |
| `cachingConnectionFactory` | 시뮬레이터, DLQ 도구 | 리스너 트랜잭션 바깥. 세션/프로듀서 캐싱 이득만 취한다 |
| `transactionAwareConnectionFactory` | 리스너 안의 ACK/PARK 발행 | 위 3.1 |

`trustAllPackages` 는 기본값(false)을 유지한다. `TextMessage` 만 쓰므로
`ObjectMessage` 역직렬화를 열어 줄 이유가 없다.

### `maxMessagesPerTask`

주지 않으면(-1) 태스크가 끝나지 않아 컨슈머가 한 번 늘면 줄지 않는다. 10 으로 두어
한가해지면 최소치(3)로 돌아오게 한다.

### 키는 파일에도 두지 않는다

`hl7poc.properties` 의 값은 전부 `${ENV_VAR:기본값}` 형태다. 파일은 "기본값 목록"이
되고 실제 값은 환경변수로 들어온다.

DB 비밀번호에는 기본값이 있고 PHI 암호화 키에는 없다. 의도된 차이다. DB 는 로컬
docker-compose 전용 계정이라 기본값이 있어야 바로 돌려볼 수 있다. 반면 암호화 키에
기본값을 두면 "설정 안 해도 돌아가네" 하고 그대로 운영에 나간다. 비어 있으면
`KeyProvider` 가 기동 시점에 즉시 실패한다(5단계에서 구현).

---

## 5. 검증 결과

`ant docker-up` 후 `ant test`. 인프라가 없으면 `Assume` 으로 건너뛰므로
Docker 없이도 `ant test` 는 통과한다.

| 테스트 | 확인 내용 | 결과 |
|---|---|---|
| `운영_XML_이_그대로_로딩된다` | DataSource·DMLC·템플릿 생성, 컨슈머 3/10, `sessionTransacted`, DB 왕복 | ✅ |
| `정상_처리되면_ACK_가_발행된다` | 1회 배달, ACK 발행 | ✅ |
| `리스너가_실패하면_ACK_도_롤백되고_재시도_후_DLQ_로_간다` | 4회 배달 후 `DLQ.Q.HL7.ADT.REQ` 이동, ACK 0건(누수 없음) | ✅ |
| `재전송_지연이_지수_백오프로_적용된다` | 간격 하한 1.5s/3.5s/7s | ✅ |
| `리스너가_실패하면_DB_쓰기도_롤백된다` | `processing_log` 행 0건 | ✅ |

전체 5개 통과, 약 59초(DLQ 시나리오 3건 × 14초).

아직 검증하지 않은 것: HL7 파싱, 암복호화, SOAP 연동, 멱등성의 실제 코드 경로.
각각 4·5·6단계에서 다룬다.
