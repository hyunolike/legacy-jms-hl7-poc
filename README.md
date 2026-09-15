# legacy-jms-hl7-poc

레거시 Spring(XML 설정) + ActiveMQ + Ant 구조를 **의료 도메인(HL7 v2)** 에 옮긴 학습용 PoC.

병원 A의 환자 입퇴원(ADT) 이벤트를 HL7 v2 메시지로 수신 → 파싱/검증 → 환자 식별정보(PHI) 암호화 →
DB 적재 → 병원 B로 SOAP 전달 → HL7 ACK 응답 발행까지를 재현한다.

> 사용하는 모든 데이터는 **가상의 합성 데이터**다. 실제 환자 정보는 포함하지 않는다.

## 문서

- [1단계 — 아키텍처와 패키지 구조](docs/01-architecture.md)
- [2단계 — Docker Compose와 Ant 빌드](docs/02-infrastructure.md)
- [3단계 — Spring XML 설정과 트랜잭션 경계](docs/03-spring-xml.md)
- [4단계 — HL7 샘플 메시지와 파서 래퍼](docs/04-hl7-parser.md)
- [5단계 — 리스너·서비스·DAO·암복호화](docs/05-listener-service-dao-secure.md)
- [6단계 — 병원 B SOAP 연동](docs/06-soap-integration.md)
- [7단계 — ACK 생성·재시도·DLQ](docs/07-ack-retry-dlq.md)
- [8단계 — 시뮬레이터와 통합 시나리오](docs/08-simulator-scenarios.md)

## 빠른 시작

```bash
cp .env.example .env
ant docker-up     # ActiveMQ + PostgreSQL
ant resolve       # 의존성 (최초 1회)
ant dist          # 컴파일 + 배포본
ant test          # 통합 테스트 (인프라 없으면 자동 건너뜀)
ant -p            # 전체 타깃 목록
```

ActiveMQ 콘솔 http://localhost:8161/admin (admin/admin) · PostgreSQL localhost:5432/hl7poc

## 진행 상태

- [x] 1. 전체 아키텍처 설명과 디렉터리/패키지 구조
- [x] 2. Docker Compose(ActiveMQ, DB)와 `build.xml`
- [x] 3. `app-context.xml` 및 기능별 XML (jms / adt / secure / ws)
- [x] 4. HL7 샘플 메시지와 파서 래퍼
- [x] 5. 리스너, 서비스, DAO, 암복호화 구현
- [x] 6. mock SOAP 서버와 클라이언트
- [x] 7. ACK 생성, 재시도, DLQ 처리
- [x] 8. 병원 A 시뮬레이터와 통합 테스트 시나리오
- [ ] 9. README 최종본
