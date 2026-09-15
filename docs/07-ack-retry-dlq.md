# 7단계 — ACK 생성, 재시도, DLQ 처리

> 모든 환자 정보는 **가상의 합성 데이터**다.

이 단계에서 **`app-context.xml` 전체가 처음으로 로딩된다.** 1~6단계는 계층별로
나눠 검증했는데, 조각이 다 맞아도 합치면 어긋나는 것들이 있다. 실제로 이 단계에서
그런 문제를 하나 잡았다(3절).

## 산출물

```
common/hl7/HapiAckBuilder.java       AA/AE/AR + ERR 세그먼트
jms/dlq/DlqReprocessor.java          DLQ/격리 큐 운영 도구 (ant run-dlq)
spring/app-context-jms-core.xml      ConnectionFactory, 목적지, JmsTemplate
spring/app-context-jms-listener.xml  ACK 발행기, 리스너, 리스너 컨테이너
spring/app-context-dlq-tool.xml      운영 도구 전용 (리스너 없음)
BridgeEndToEndTest                   운영 컨텍스트 전체로 종단 간 검증
```

---

## 1. ACK 생성

```
MSH|^~\&|HL7POC_BRIDGE|HOSP_BRIDGE|HIS|HOSP_A|20260915115857||ACK^A01|111|P|2.5
MSA|AE|MSG00000101
ERR|||101^Required field missing^HL70357|E||||[HL7-NO-PATIENT-ID] PID-3(환자 식별자)가 없습니다.
```

### MSH-10 과 MSA-2 는 다른 것이다

흔한 혼동 지점이다. **MSH-10 은 이 ACK 자신의 식별자**이고, **원본의 식별자는
MSA-2** 에 들어간다. MSH-10 에 원본 ID 를 넣으면 상대 시스템이 "내가 보낸 메시지가
되돌아왔다"고 해석할 수 있다. 테스트로 고정했다
(`ACK_자신의_ControlId_는_원본과_다르다`).

### 내부 오류 코드를 ERR-3 에 넣지 않는다

`ERR-3` 은 HL7 표준 테이블 0357 의 코드 자리다. 여기에 `HL7-NO-PATIENT-ID` 같은
우리 코드를 넣으면 상대 시스템은 해석할 수 없다. 표준 코드를 ERR-3 에 넣고,
우리 코드는 ERR-8(사용자 메시지)에 남겨 양쪽이 다 읽게 한다.

| 내부 코드 | ERR-3 | 의미 |
|---|---|---|
| `HL7-PARSE`, `HL7-TS-FORMAT` | 102 | Data type error |
| `HL7-NO-PATIENT-ID`, `ADT-NO-ADMIT-TIME` 등 | 101 | Required field missing |
| `HL7-UNSUPPORTED-TRIGGER` | 201 | Unsupported event code |
| 매핑에 없는 코드 | 207 | Application internal error |

매핑에 없다고 ACK 생성이 실패하면 안 된다. 상대가 아무 응답도 못 받는 쪽이 훨씬
나쁘다. 그래서 기본값으로 떨어뜨린다.

### 헤더를 못 읽었을 때도 MSA-2 를 채운다

비워 두면 상대가 어느 메시지에 대한 응답인지 모르고, 결국 재전송을 반복한다.
구분자만 살아 있으면 MSH-10 과 MSH-9.2 는 문자열 분해로 건질 수 있다.
그것도 안 되면 `UNKNOWN` 을 넣되 경고 로그를 남긴다.

### 인코딩은 검증을 끈 파서로 한다

**실제로 막혔던 지점이다.** MSH-9.2(트리거)가 비어 있으면 HAPI 가 인코딩 단계의
메시지 규칙 조회에서 `NoSuchElementException` 을 낸다. 그런데 파싱 실패 ACK 가
바로 그 경우다 — 트리거를 모르는 상황에서 응답해야 하는데 정작 그때 인코딩이 막힌다.

두 가지로 대응했다.
1. 트리거도 best-effort 로 건진다. 건지면 정상 경로.
2. 그래도 모르면 자리 채움값을 쓰고, 인코딩은 **검증을 끈 전용 파서**로 한다.
   우리가 알려진 값으로 조립한 ACK 를 스스로 검증할 실익은 크지 않은 반면,
   검증 실패로 응답을 못 보내는 대가는 크다.

### ACK 로 나가는 문구를 정리한다

ACK 는 네트워크로 나가 **상대 시스템 로그에 그대로 쌓인다.** 우리가 통제할 수 없는
곳에 PHI 사본이 생기지 않도록 250자로 자르고, 개행을 공백으로 바꾼다.
개행을 그대로 두면 HL7 세그먼트 구분자가 되어 메시지가 통째로 망가지기도 한다.

---

## 2. DLQ 재처리 도구

```bash
ant run-dlq -Dargs="list"             # DLQ 요약
ant run-dlq -Dargs="list park"        # 격리 큐 요약
ant run-dlq -Dargs="replay 10"        # 10건 재투입
ant run-dlq -Dargs="replay ALL"       # 전부
```

실제 출력:

```
총 2건
   1 ctrlId=MSG00000001 facility=HOSP_A error=WS-TRANSPORT retried=0 at=09-15 12:36:42 bytes=28
   2 ctrlId=MSG00000002 facility=HOSP_A error=WS-TRANSPORT retried=3 at=09-15 12:36:42 bytes=28

재투입 한도(3회)를 넘겨 격리 큐로 옮긴 메시지: 1건
재투입 완료: 1건
```

### 도구는 리스너를 띄우지 않는다

전용 컨텍스트(`app-context-dlq-tool.xml`)를 쓴다. 브리지 본체 컨텍스트를 그대로
열면 **도구를 실행하는 순간 컨슈머가 하나 더 붙어 요청 큐에서 메시지를 가져간다.**
운영자가 현황을 보려고 실행한 명령이 처리 흐름에 끼어드는 셈이다.

그래서 `app-context-jms.xml` 을 core/listener 로 쪼갰다. 도구는 core 만 가져온다.
DB·암복호화·SOAP 도 끌어오지 않는다 — **장애 상황에서 쓰는 도구가 "DB 가 죽어서
안 뜬다"거나 "키가 없어서 안 뜬다"면 쓸모가 없다.**

### 본문을 출력하지 않는다

`list` 는 헤더와 길이만 찍는다. 원문 HL7 에는 환자 정보가 그대로 들어 있고,
운영 도구의 출력은 터미널 기록이나 티켓에 붙여 넣어지기 마련이다. 무엇이 왜
실패했는지 판단하는 데는 헤더로 충분하다.

### 꺼내기와 넣기는 한 트랜잭션

갈라지면 중간에 죽었을 때 메시지가 사라지거나 두 배가 된다.

**여기서 한 번 걸렸다.** 처음에는 주입받은 `standaloneJmsTemplate` 을 썼는데
그 빈은 `sessionTransacted=false` 라 `session.commit()` 이 *"Not a transacted
session"* 으로 실패했다. 도구의 정확성이 남이 설정한 빈의 상태에 달려서는 안 되므로,
**필요한 설정을 갖춘 템플릿을 직접 만들어 쓰도록** 바꿨다.

### 재투입 한도를 넘기면 격리 큐로

한도(3회)를 넘긴 메시지를 DLQ 에 그대로 두면 다음 재투입 때 또 읽혀 같은 자리를
맴돈다. 자동 복구로 답이 안 나오는 메시지이므로 격리 큐로 옮겨 사람이 보게 한다.

### 메시지 그룹을 반드시 옮긴다

`JMSXGroupID` 로 같은 환자의 메시지가 같은 컨슈머에 고정되어 순서가 보장된다.
재투입하면서 빠뜨리면 그 메시지만 순서 보장에서 이탈한다 — **재처리 상황은 순서가
가장 중요한 때인데도.** 테스트로 고정했다.

### 삭제(purge)는 일부러 넣지 않았다

이 큐의 메시지에는 환자 정보가 들어 있다. 되돌릴 수 없는 삭제를 한 줄짜리 명령으로
만들어 두면 사고가 난다. 정말 필요하면 ActiveMQ 콘솔에서 무엇을 지우는지 보면서
하는 편이 안전하다.

---

## 3. ⚠️ 종단 간 테스트가 잡아낸 버그: 롤백 표시가 격리를 삼킨다

계층별 테스트는 전부 통과하는데 종단 간에서만 실패한 케이스다.

**증상**: 병원 B 가 업무적으로 거절(`WS-REJECTED`)하면 AE 응답이 나가야 하는데,
ACK 가 아예 오지 않고 메시지가 DLQ 로 갔다.

**원인**: 연쇄가 이렇다.

1. 서비스가 `PermanentProcessingException` 을 던진다.
2. `tx:advice` 의 `rollback-for="java.lang.Exception"` 이 이를 보고 트랜잭션을
   **rollback-only 로 표시**한다.
3. 리스너는 "재시도 무의미"로 분류하고 격리 큐로 옮긴 뒤 ACK 를 기록한다.
4. 그러나 트랜잭션은 이미 rollback-only 다. 커밋 시점에
   `UnexpectedRollbackException` 이 나면서 **3번이 통째로 사라진다.**
5. 메시지는 재전송되고 결국 DLQ 로 간다.

우리가 명시적으로 "재시도하지 않는다"고 분류한 오류인데도 재시도된 것이다.

**수정**:

```xml
<tx:method name="*" ...
           rollback-for="java.lang.Exception"
           no-rollback-for="com.example.hl7poc.common.exception.PermanentProcessingException"/>
```

`TransientProcessingException` 은 반대로 반드시 롤백되어야 하므로 기본 동작을
그대로 둔다.

**왜 계층별 테스트로는 안 잡히나**: 트랜잭션을 여는 쪽(DMLC), 예외를 분류하는
쪽(리스너), 예외를 던지는 쪽(서비스)이 전부 다른 계층이다. 셋을 실제로 연결해야만
재현된다. 이게 종단 간 테스트를 따로 두는 이유다.

덧붙여, 적재까지 갔다가 거절당한 행은 상태를 `PARKED` 로 바꾼다. `PERSISTED` 로
남으면 "전달 직전에 죽은 메시지"와 구분되지 않는다.

---

## 4. 재시도 파라미터를 설정으로 뺐다

```properties
jms.redelivery.max            = ${HL7POC_REDELIVERY_MAX:3}
jms.redelivery.initialDelayMs = ${HL7POC_REDELIVERY_INITIAL_MS:2000}
jms.redelivery.delayMs        = ${HL7POC_REDELIVERY_DELAY_MS:2000}
```

재빌드 없이 조정할 수 있어야 한다. 상대 시스템의 복구 시간은 연동마다 다르고,
장애 중에 "재시도 간격만 늘리고 싶은" 상황이 실제로 생긴다.

부수 효과로 종단 간 테스트가 빨라졌다. 기본값 2s/4s/8s 로는 DLQ 한 건을 확인하는
데 14초가 걸리는데, 테스트에서는 200ms/400ms/800ms 로 줄여 쓴다. 간격 자체는
`SpringWiringTest` 가 기본 설정으로 이미 검증한다.

---

## 5. 테스트를 쓰면서 겪은 것 (JMS 다루는 사람에게 유용할 만한)

### 메시지 그룹은 browse 와 receive 를 갈라 놓는다

`awaitDepth`(browse)로는 메시지가 보이는데 바로 다음 `receive` 가 20초를 기다리다
실패했다. **ActiveMQ 가 메시지 그룹을 한 컨슈머에 고정(pin)하기 때문**이다.
`CachingConnectionFactory` 는 컨슈머를 닫지 않고 재사용하므로, 그 컨슈머가 그룹
소유권을 계속 쥔 채 다른 컨슈머의 접근을 막는다.

`cacheConsumers=false` 만으로도 간헐적으로 실패했고, 결국 테스트 프로브는 원본
`ConnectionFactory` 를 쓰게 했다. 매 호출 커넥션까지 새로 맺어 느리지만 프로브에는
정확성이 우선이다.

> 운영 코드에는 영향이 없다. 리스너 컨테이너는 원본 팩토리를 쓰고(3단계),
> 캐싱 팩토리는 발행 전용이다.

### 확인하는 순간 메시지가 사라지면 안 된다

"DLQ 에 들어갔는지 확인한 뒤 재투입" 흐름에서 `receive` 로 확인하면 확인하는 순간
꺼내져 재투입할 것이 없다. browse 기반 `awaitDepth` 를 따로 만들었다.

### 재시도 중인 메시지는 다음 테스트로 샌다

한 번 비우는 것으로는 부족하다. 재시도 중인 메시지는 "지금은" 큐에 없다가 몇 초
뒤에 나타난다. 리스너를 먼저 **멈춰서** 진행 중이던 처리를 롤백시킨 뒤에야 전부
비울 수 있다.

---

## 6. 검증 결과

```
ant docker-up && ant test → 111개 전부 통과 (약 136초)
  BridgeEndToEndTest   12개 (신규) — 운영 컨텍스트 전체
  HapiAckBuilderTest   14개 (신규)
```

### 종단 간으로 확인한 것

| 확인 | 결과 |
|---|---|
| **`app-context.xml` 전체가 로딩된다** (6개 XML, 30여 개 빈) | ✅ |
| 입원 → 적재 → 병원 B 전달 → AA 응답, 단계 로그가 전 구간 연결 | ✅ |
| ACK 에 상관관계 헤더(`X_MSG_CONTROL_ID`, `JMSCorrelationID`) | ✅ |
| MSH 깨짐 → 격리 + AR, **DLQ 로 가지 않음** | ✅ |
| 미지원 트리거 → 격리 + AE(201), 환자 식별자 없음 → AE(101) | ✅ |
| 같은 메시지 2회 → **ACK 는 2번, 업무는 1번** | ✅ |
| 병원 B 계속 실패 → 4회 시도 후 DLQ, ACK 없음, DB 롤백 | ✅ |
| 병원 B 업무적 거절 → 1회만 호출, 격리 + AE, 행 상태 `PARKED` | ✅ |
| DLQ 재투입 → 원인 해소 후 정상 처리(AA, FORWARDED) | ✅ |
| 재투입 한도 초과 → 격리 큐로, DLQ 에 남지 않음 | ✅ |
| 재투입 시 메시지 그룹 유지 + 카운트 증가 | ✅ |
| **종단 간 흐름에서도 DB·단계로그에 PHI 평문 없음** | ✅ |

### CLI 로도 확인

`ant run-dlq -Dargs="list"` / `-Dargs="replay ALL"` 를 실제로 실행해
헤더 출력과 한도 초과 격리 동작을 확인했다(2절의 출력이 실제 결과다).

남은 것은 8단계(병원 A 시뮬레이터와 통합 시나리오)와 9단계(README)다.
