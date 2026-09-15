# 4단계 — HL7 샘플 메시지와 파서 래퍼

> 이 문서에 나오는 모든 환자 정보는 **가상의 합성 데이터**다. 실제 환자 정보는
> 어떤 형태로도 포함되어 있지 않다.

## 산출물

```
common/
├── hl7/
│   ├── AckCode.java            AA / AE / AR
│   ├── Hl7Parser.java          인터페이스 (2단계 분리)
│   ├── HapiHl7Parser.java      HAPI 구현
│   ├── Hl7MessageContext.java  MSH 헤더 (불변)
│   └── Hl7Dates.java           가변 자리수 TS 파싱
├── dto/
│   ├── AdtEvent.java           업무 계층이 보는 유일한 입력
│   └── PatientInfo.java        PID 평문 (toString 은 항상 마스킹)
├── exception/
│   ├── Hl7ProcessingException.java   기반 — 재시도 여부를 타입에 박는다
│   ├── Hl7ParseException.java        → AR, 재시도 X
│   ├── PermanentProcessingException  → AE, 재시도 X
│   └── TransientProcessingException  → ACK 없음, 재시도 O
└── util/
    ├── MaskingUtils.java
    └── TraceContext.java       MDC put/clear

src/test/resources/hl7-samples/   샘플 8종
```

---

## 1. 샘플 메시지

| 파일 | 내용 | 기대 결과 |
|---|---|---|
| `adt_a01_admit.hl7` | 입원 | AA |
| `adt_a03_discharge.hl7` | 같은 환자의 퇴원 | AA |
| `adt_a01_admit_other_patient.hl7` | 다른 환자 입원 (메시지 그룹 테스트용) | AA |
| `adt_a01_missing_pid.hl7` | PID 세그먼트 없음 | AE |
| `adt_a01_missing_patient_id.hl7` | PID 는 있으나 PID-3 비어 있음 | AE |
| `adt_a01_broken_msh.hl7` | MSH 인코딩 문자 누락 | AR |
| `adt_a08_unsupported.hl7` | 유효한 HL7 이나 미지원 트리거 | AE |
| `adt_a01_bad_datetime.hl7` | MSH-7 날짜 형식 오류 | AR (아래 3.3) |

**필드 위치는 손으로 세지 않았다.** `PV1-44`(입원 시각)처럼 뒤쪽에 있는 필드는
파이프를 세다 보면 반드시 틀린다. 인덱스 기반 생성기로 만들고, 테스트가
`PV1-44`/`PV1-45` 값을 실제로 읽어 확인한다. 값이 맞게 나온다는 것이 곧 필드
위치가 맞다는 증거다.

**저장은 LF, 전송은 CR.** HL7 의 세그먼트 구분자는 CR(`\r`)이다. 그런데 CR 로
저장하면 에디터에서 한 줄로 보이고 git diff 도 못 쓴다. 그래서 파일은 LF 로
저장하고 파서가 정규화한다. 실제로 세 가지 형태 모두 같게 읽히는지 테스트한다.

---

## 2. 설계에서 가장 중요한 결정: 파싱을 두 단계로 나눈다

```java
Hl7MessageContext parseHeader(String raw);    // MSH 만
AdtEvent          toAdtEvent(Hl7MessageContext);  // PID/PV1 + 업무 규칙
```

한 번에 다 하는 `parse()` 하나로 만들면, **본문이 잘못됐을 때 ACK 조차 못 보내는
상태**가 된다. ACK 의 MSA-2 에는 원본의 MSH-10 이 들어가야 하는데, 파싱이 통째로
실패하면 그 값을 모르기 때문이다.

상대 병원 입장에서는 "보냈는데 아무 응답이 없음"이 되고, 그 시스템은 재전송을
반복한다. 우리는 같은 메시지를 계속 거절하고, 양쪽 로그에 노이즈만 쌓인다.

테스트가 이 성질을 고정한다 — `PID_세그먼트가_없으면_AE_로_거절한다` 는 본문
매핑이 실패하기 **전에** 헤더에서 `MSG00000101` 을 읽었음을 먼저 확인한다.

> **Spring Boot라면**: 달라지지 않는다. 이건 프레임워크 문제가 아니라 프로토콜
> 설계 문제다.

---

## 3. 구현에서 부딪힌 것들

### 3.1 HL7 날짜는 자리수가 가변이다

`YYYY`, `YYYYMM`, `YYYYMMDD`, `YYYYMMDDHHMM`, `YYYYMMDDHHMMSS[.S...]` 에 선택적
`+/-ZZZZ` 오프셋이 붙는다. `"yyyyMMddHHmmss"` 하나로 파싱하도록 짜 두면 자리수가
짧은 실제 메시지에서 바로 깨진다.

`SimpleDateFormat` 을 **static 으로 공유하지 않는 것**도 중요하다. 스레드 세이프하지
않은데 컨슈머가 10개까지 늘어나는 구조다. 공유하면 값이 조용히 섞인다 — 재현이
거의 안 되는 종류의 버그다.

`setLenient(false)` 도 명시했다. 기본값(lenient)이면 `20261345` 가 예외 없이
다음 해 1월로 넘어간다. 잘못된 날짜가 그대로 DB 에 적재되는 경로다.

생년월일은 `Date` 가 아니라 **문자열로 보관**한다. HL7 이 부분 날짜를 허용하므로
`Date` 로 바꾸는 순간 없는 정보(1월 1일)를 지어내게 된다.

### 3.2 HAPI 검증 오류 메시지의 필드 번호는 그대로 믿으면 안 된다

`MSH-7` 에 잘못된 날짜를 넣고 돌렸더니 HAPI 가 이렇게 보고했다.

```
Primitive value '2026-03-01 09:30:00' requires to be empty or a HL7 datetime string
  at MSH-6(0)
```

실제로 틀린 필드는 **MSH-7** 이다. MSH 세그먼트는 MSH-1(필드 구분자)과
MSH-2(인코딩 문자)를 특수 취급하기 때문에 내부 인덱스가 한 칸 밀린다.
HAPI 의 오류 위치를 보고 엉뚱한 필드를 파헤치기 쉬우니, **값으로 확인**하는 편이 빠르다.

### 3.3 잘못된 날짜는 AR 로 떨어진다 (AE 가 아니라)

검증을 켜 두면 HAPI 가 `pipeParser.parse()` 단계에서 형식 오류를 잡는다. 따라서
우리 `Hl7Dates` 까지 오지 않고 `Hl7ParseException`(AR) 이 된다. 검증을 끄면
`Hl7Dates` 가 잡아 `PermanentProcessingException`(AE) 이 된다.

둘 다 "재시도하지 않는 오류"라는 요구사항은 만족한다. 테스트도 구체적 타입이
아니라 **`isRetryable() == false`** 를 검사하도록 썼다. 검증 설정이 바뀌어도
테스트가 깨지지 않으면서, 진짜 중요한 성질은 고정된다.

### 3.4 Terser 로 읽는다 (생성된 모델 클래스 대신)

HAPI 는 `ADT_A01#getPID()` 같은 타입 안전한 접근자를 제공하지만, `Terser` 로
경로 문자열(`/PID-3-1`)을 쓴다. 실제 병원 시스템은 규격에서 조금씩 벗어난 메시지를
보내고, 그때 구조체 기반 접근은 `null` 을 내거나 클래스 캐스팅에서 깨진다.
Terser 는 "없으면 null" 로 일관되게 동작해서 방어 코드를 짜기 쉽다.

대신 경로 오타를 컴파일 시점에 못 잡는다. 그래서 테스트로 모든 경로를 한 번씩
훑는다.

### 3.5 예외 메시지에 원문을 넣지 않는다

```java
throw new Hl7ParseException("HL7 구조를 해석할 수 없습니다: " + e.getMessage(), e);
```

`rawMessage` 를 붙이고 싶은 유혹이 크지만, 예외 메시지는 거의 항상 그대로 로그로
나가고 원문에는 환자 정보가 들어 있다. 테스트가 이것도 고정한다 —
깨진 MSH 샘플의 환자번호(`PAT000009`)가 예외 메시지에 없어야 통과한다.

### 3.6 `PatientInfo.toString()` 이 마스킹한다

`log.info("환자={}", patient)` 한 줄이면 마스킹 호출을 잊는다. 사람이 매번
기억하는 대신 타입이 보장하게 만든다. 테스트가 환자번호·이름·생년월일·전화번호·
상세주소가 `toString()` 결과에 남지 않음을 확인한다.

### 3.7 `TraceContext.clear()` 를 빼먹으면 조용히 망가진다

MDC 는 스레드 로컬이다. 지우지 않으면 스레드 풀에서 다음 메시지가 **앞 메시지의
Control ID 를 달고** 로그를 남긴다. 평소에는 드러나지 않다가 장애 조사 때 추적을
통째로 망친다. 5단계 리스너에서 `finally` 에 넣는다.

---

## 4. 예외 계층 = 재시도 정책

재시도 여부를 예외 **타입**에 박아 둔다. 리스너는 예외 메시지를 읽고 판단하지 않고
`isRetryable()` 만 본다.

| 예외 | ACK | 재시도 | 처리 |
|---|---|---|---|
| `Hl7ParseException` | AR | ✗ | PARK 큐로 격리 후 **정상 커밋** |
| `PermanentProcessingException` | AE | ✗ | 위와 동일 |
| `TransientProcessingException` | (없음) | ✓ | **롤백** → 2s/4s/8s 재전송 → DLQ |

일시 오류에 ACK 를 보내지 않는 것이 포인트다. 아직 성공도 실패도 확정되지 않았는데
성급하게 AE 를 보내면 송신 측은 "영구 실패"로 처리해 버린다.

런타임 예외로 둔 이유는 `MessageListener#onMessage` 가 체크 예외를 던질 수 없어서다.
다만 DAO/SOAP 계층은 체크 예외를 올릴 수 있으므로 `tx:advice` 의 `rollback-for` 는
넓게(`java.lang.Exception`) 열어 둔다.

---

## 5. 검증 결과

```
ant test
  HapiHl7ParserTest   11개   파싱, 매핑, 정규화, 오류 분류
  Hl7DatesTest         6개   가변 자리수, 오프셋, 부분 날짜, 존재하지 않는 날짜
  MaskingUtilsTest     8개   마스킹 규칙, toString 유출 방지
  SpringWiringTest     5개   (3단계, 인프라 필요)
  ─────────────────────────
  Docker 없이 25개 / Docker 포함 30개 전부 통과
```

주요 확인 항목:

| 확인 | 결과 |
|---|---|
| A01/A03 헤더·본문 매핑 (MSH 10필드, PID 7필드, PV1-2/3/19/44/45) | ✅ |
| CR / LF / CRLF 세 형태가 같은 Control ID·같은 SHA-256 | ✅ |
| MLLP 프레이밍(0x0B/0x1C)이 붙어 있어도 파싱 | ✅ |
| PID 없음 → 헤더는 읽힌 상태로 AE | ✅ |
| 미지원 트리거(A08) → AE | ✅ |
| MSH 깨짐 → AR, 예외 메시지에 원문 미유출 | ✅ |
| 잘못된 날짜 → 재시도하지 않는 오류 | ✅ |
| `PatientInfo.toString()` 에 PHI 미노출 | ✅ |
| UTF-8 한글 세그먼트 파싱 | ✅ |

아직 검증하지 않은 것: 멱등성 DB 경로, 암복호화, SOAP 연동, ACK 생성.
각각 5·6·7단계에서 다룬다.
