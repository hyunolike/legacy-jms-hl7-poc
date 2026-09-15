# 8단계 — 병원 A 시뮬레이터와 통합 테스트 시나리오

> 모든 환자 정보는 **가상의 합성 데이터**다.

이 단계는 "손으로 돌려 보는" 단계다. 자동 테스트 112개가 이미 돌지만, 세 프로세스를
실제로 띄워 로그를 눈으로 따라가 보지 않으면 보이지 않는 것이 있다.
실제로 여기서 **PHI 유출 경로를 하나 찾았다**(4절).

---

## 1. 세 프로세스 띄우기

```bash
# 키는 바깥에서 주입한다. 없으면 브리지가 기동 시점에 실패한다.
export HL7POC_PHI_KEY="$(openssl rand -base64 32)"
export HL7POC_BLIND_INDEX_KEY="$(openssl rand -base64 32)"

ant docker-up        # ① ActiveMQ + PostgreSQL

# 터미널 2
ant run-mock         # ② 병원 B mock SOAP 서버 (localhost:9090)

# 터미널 3
ant run-bridge       # ③ 브리지 본체
```

기동 로그로 확인할 것:

```
PHI 키 공급자 준비 완료. currentKeyId=k1, 보유 키 수=1
병원 B SOAP 클라이언트 준비 완료. endpoint=http://localhost:9090/... connect=3000ms, read=5000ms
HL7 ADT 브리지 기동 완료 (1894 ms). 종료하려면 Ctrl+C.
```

---

## 2. 시뮬레이터

```bash
ant run-simulator -Dargs="send a01 3"      # A01 3건 (매번 새 Control ID)
ant run-simulator -Dargs="admit-discharge" # 같은 환자의 입원 → 퇴원
ant run-simulator -Dargs="duplicate"       # 같은 Control ID 를 두 번
ant run-simulator -Dargs="burst 30"        # 환자 5명에 분산해 30건
ant run-simulator -Dargs="file adt_a01_broken_msh.hl7"   # 샘플 그대로 (오류)
ant run-simulator -Dargs="ack 15"          # 15초간 ACK 수신 출력
```

**Control ID 를 매번 새로 만드는 것이 기본**이다. 샘플을 그대로 보내면 두 번째
실행부터는 전부 중복으로 판정되어 아무 일도 일어나지 않는다. 멱등성이 제대로
동작한다는 뜻이지만, 손으로 돌려 볼 때는 "고장 난 것처럼" 보인다.
중복 시연은 `duplicate` 로 따로 뒀다.

필드를 바꿀 때 문자열 치환이 아니라 HAPI Terser 를 쓴다. 치환은 필드 위치가
어긋나도 조용히 통과해서, 잘못된 메시지를 만들어 놓고도 모른다.

> 시뮬레이터도 DLQ 도구처럼 **리스너를 띄우지 않는 전용 컨텍스트**
> (`app-context-simulator.xml`)를 쓴다. 자기가 보낸 메시지를 자기가 가져가면 안 되고,
> 브리지가 안 떠 있어도 메시지를 쌓아 둘 수 있어야 한다.

---

## 3. 시나리오별 실제 결과

### 시나리오 1 — 입원 → 퇴원 (순서 보장)

```
→ 발행 ctrlId=SIM...0001 group=PAT000001
→ 발행 ctrlId=SIM...0002 group=PAT000001
```

브리지 로그:

```
[adtRequestListenerContainer-3] [ctrl=SIM...0001 type=ADT^A01] STEP=FORWARDED 병원 B 접수 완료. receiptId=HOSPB-000001
[adtRequestListenerContainer-3] [ctrl=SIM...0001 type=ADT^A01] STEP=ACK_SENT
[adtRequestListenerContainer-3] [ctrl=SIM...0002 type=ADT^A03] STEP=PERSISTED id=2 trigger=A03
[adtRequestListenerContainer-3] [ctrl=SIM...0002 type=ADT^A03] STEP=ACK_SENT
```

두 건이 **같은 스레드(`-3`)에서 순서대로** 처리됐다. `JMSXGroupID` 가 동작한 것이다.

### 시나리오 2 — 중복 (멱등성)

```
STEP=DEDUP_SKIP 이미 처리한 메시지입니다. 업무 처리를 건너뜁니다. storedAck=AA
STEP=ACK_REPLAY 저장된 ACK 를 재전송합니다.
```

ACK 는 두 번 나가고 업무 처리는 한 번이다. ACK 를 안 보내면 상대가 계속 재전송한다.

### 시나리오 3 — 오류 3종

```
STEP=PARK 파싱 실패로 격리합니다. code=HL7-PARSE
STEP=PARK 처리 불가로 격리합니다. code=HL7-UNSUPPORTED-TRIGGER msg=지원하지 않는 트리거: A08
STEP=PARK 처리 불가로 격리합니다. code=HL7-NO-PATIENT-ID msg=PID-3(환자 식별자)가 없습니다.
```

셋 다 재시도 없이 격리 큐로 갔다.

### 시나리오 4 — ACK 눈으로 보기

```
ACK #2 (ctrlId=SIM...0001)
    MSA|AA|SIM...0001
ACK #7 (ctrlId=MSG00000103)
    MSA|AE|MSG00000103
    ERR|||201^Unsupported event code^HL70357|E||||[HL7-UNSUPPORTED-TRIGGER] 지원하지 않는 트리거 이벤트입니다: A08 (지원: [A01, A03])
ACK #8 (ctrlId=MSG00000101)
    MSA|AE|MSG00000101
    ERR|||101^Required field missing^HL70357|E||||[HL7-NO-PATIENT-ID] PID-3(환자 식별자)가 없습니다.
```

### 시나리오 5 — burst 30건 (동시성)

```
참여한 컨슈머 스레드: -1 -2 -3 -4 -5 -7 -8 -9   (3개에서 8개로 증가)
환자 5명 × 6건 = 30건, 전부 FORWARDED
순서 역전(OUT_OF_ORDER) 감지: 0건
PHI 평문 점검: 0건
```

---

## 4. ⚠️ 통합 시나리오가 잡아낸 PHI 유출 경로

ACK 를 **눈으로 보다가** 발견했다.

```
ERR|||102^Data type error^HL70357|E||||[HL7-PARSE] HL7 구조를 해석할 수 없습니다:
  Determine encoding for message. The following is the first 50 chars of the message
  for reference, although this may not be where the issue is: MSH\F\\S\\R\\E\\T\\F\HIS\F\HOSP_A\F\...
```

4단계에서 **우리 예외 메시지에는 원문을 넣지 않도록 막아 두었다.** 그런데
**HAPI 의 예외 메시지가 "참고용"이라며 원문 앞 50자를 붙여 준다.** 우리는 그
문구를 `e.getMessage()` 로 받아 ACK 의 ERR-8 에 실었고, ACK 는 네트워크로 나가
**상대 병원 로그에 그대로 쌓인다.** 우리가 통제할 수 없는 곳에 사본이 생기는 것이다.

이 경우 앞 50자는 MSH 라 실제 환자 정보는 없었다. 하지만 MSH 로 시작하지 않는
입력(쓰레기 데이터, 프레이밍이 어긋난 메시지)이면 그 자리에 무엇이든 올 수 있다.
**"우리 코드가 안 넣으면 안 들어간다"는 가정이 틀렸다** — 라이브러리가 대신 넣는다.

### 수정: 진단용 메시지와 상대에게 보낼 문구를 나눈다

```java
public abstract class Hl7ProcessingException extends RuntimeException {
    private final String clientSafeText;   // ACK 로 나가는 문구
    public String getClientSafeText() { ... }
}

// Hl7ParseException 은 고정 문구를 쓴다
private static final String CLIENT_SAFE_TEXT = "HL7 메시지 구조를 해석할 수 없습니다.";
```

내부 로그에는 상세(HAPI 문구 포함)가 그대로 남아 진단이 되고, ACK 에는 안전한
문구만 나간다. 수정 후:

```
MSA|AR|UNKNOWN
ERR|||102^Data type error^HL70357|E||||[HL7-PARSE] HL7 메시지 구조를 해석할 수 없습니다.
```

회귀 테스트(`파싱_실패_ACK_에_원문_조각이_새지_않는다`)로 고정했다. 이 테스트는
예외 메시지에는 `PAT000001` 이 남아 있고 ACK 에는 없음을 함께 확인한다.

> **왜 자동 테스트로 안 잡혔나**: 단위 테스트는 오류 코드와 MSA 값을 검사했지
> ERR-8 의 *내용*을 검사하지 않았다. "내용이 무엇이어야 하는가"를 미리 알았다면
> 애초에 버그를 안 만들었을 것이다. 출력물을 한 번은 눈으로 봐야 하는 이유다.

---

## 5. 메시지 그룹에 대한 실측 — 설계안의 단서를 확인했다

1단계에 이렇게 적었다.

> 컨슈머가 죽으면 그룹이 다른 컨슈머로 재배정되면서 순간적으로 순서가 흔들릴 수
> 있다. 그래서 DB 에도 방어선을 둔다.

burst 30건을 돌려 보니 **컨슈머가 죽지 않아도 그룹이 옮겨 다닌다.**

```
환자 해시별로 쓰인 스레드 수
  06d260a3 → 2개
  380ba8d0 → 2개
  4d26e01e → 1개
  4e91d44a → 2개
  bcf35295 → 1개
```

부하가 몰려 컨슈머가 3개에서 8개로 늘어나는 동안, ActiveMQ 가 그룹을 재배정한
것이다. **`maxConcurrentConsumers` 를 쓰는 한 그룹 고정은 "대체로"일 뿐이다.**

다만 이번 실행에서 순서는 전부 지켜졌다(`OUT_OF_ORDER` 0건, 환자별 시각 단조 증가).
재배정이 메시지 경계에서 일어나기 때문이다. 그래도 DB 방어선을 둔 판단은 유지한다 —
"대체로 지켜진다"에 의료 데이터의 정합성을 걸 수는 없다.

---

## 6. 검증 결과

```
ant docker-up && ant test → 112개 전부 통과
```

수동 시나리오로 확인한 것:

| 확인 | 결과 |
|---|---|
| 세 프로세스가 각각 `main()` 으로 기동 | ✅ |
| 입원 → 퇴원이 같은 컨슈머에서 순서대로 | ✅ |
| 중복 시 `DEDUP_SKIP` + `ACK_REPLAY`, 업무는 1회 | ✅ |
| 오류 3종이 재시도 없이 격리 + 정확한 ACK 코드 | ✅ |
| burst 30건 → 컨슈머 3→8 자동 증가, 전부 FORWARDED | ✅ |
| 순서 역전 0건, 환자별 이벤트 시각 단조 증가 | ✅ |
| DB PHI 평문 0건 | ✅ |
| 로그·ACK 마스킹 (`PAT****01`, `홍**`, `1985****`) | ✅ |
| **ACK 의 PHI 유출 경로 발견 및 수정** | ✅ |

남은 것은 9단계(README 최종본)다.
