package com.example.hl7poc.common.hl7;

/**
 * HL7 ACK 의 확인 코드(MSA-1).
 *
 * <ul>
 *   <li>{@link #AA} — Application Accept. 정상 처리했다.</li>
 *   <li>{@link #AE} — Application Error. 메시지는 읽었으나 업무적으로 처리할 수
 *       없다. 같은 메시지를 다시 보내도 결과는 같다.</li>
 *   <li>{@link #AR} — Application Reject. 메시지 구조 자체를 해석할 수 없다.</li>
 * </ul>
 *
 * <p>AE 와 AR 의 구분이 중요한 이유: 둘 다 "재시도해도 소용없음"이지만, 송신 측에
 * 알려 주는 원인이 다르다. AR 은 "네가 보낸 게 HL7 이 아니다", AE 는 "HL7 은
 * 맞는데 내용이 우리 업무 규칙에 맞지 않는다"이다.
 */
public enum AckCode {
    AA, AE, AR
}
