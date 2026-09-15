package com.example.hl7poc.common.exception;

import com.example.hl7poc.common.hl7.AckCode;

/**
 * HL7 메시지의 구조 자체를 해석할 수 없을 때. MSH 가 깨졌거나 인코딩 문자가
 * 잘못된 경우가 대부분이다.
 *
 * <p>재시도해도 같은 바이트가 다시 올 뿐이므로 {@link #isRetryable()} 은 false 다.
 * 송신 측에는 AR(Reject)로 알린다.
 *
 * <p>주의: 이 예외가 나면 MSH-10 을 못 읽었을 수 있다. 그 경우 ACK 의 MSA-2 에
 * 넣을 Control ID 가 없으므로, 7단계 {@code AckBuilder} 가 대체값을 쓴다.
 */
public class Hl7ParseException extends Hl7ProcessingException {

    private static final long serialVersionUID = 1L;

    /**
     * 상대에게 보낼 고정 문구.
     *
     * <p>파싱 실패의 상세는 거의 항상 파서 라이브러리가 만든 문구이고, HAPI 는
     * 거기에 원문 앞 50자를 붙인다. 그대로 ACK 에 실으면 환자 정보가 상대 병원
     * 로그로 넘어간다. 상세는 우리 로그에만 남긴다.
     */
    private static final String CLIENT_SAFE_TEXT = "HL7 메시지 구조를 해석할 수 없습니다.";

    public Hl7ParseException(String message) {
        super("HL7-PARSE", message, CLIENT_SAFE_TEXT, null);
    }

    public Hl7ParseException(String message, Throwable cause) {
        super("HL7-PARSE", message, CLIENT_SAFE_TEXT, cause);
    }

    @Override
    public AckCode getAckCode() {
        return AckCode.AR;
    }

    @Override
    public boolean isRetryable() {
        return false;
    }
}
