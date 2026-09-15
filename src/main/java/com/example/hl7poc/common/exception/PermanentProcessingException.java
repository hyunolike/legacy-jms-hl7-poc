package com.example.hl7poc.common.exception;

import com.example.hl7poc.common.hl7.AckCode;

/**
 * 메시지는 읽었지만 업무적으로 처리할 수 없을 때.
 * 필수 세그먼트 누락, 환자 식별자 없음, 지원하지 않는 트리거 이벤트 등.
 *
 * <p>같은 메시지를 백 번 다시 보내도 결과가 같으므로 재시도하지 않는다.
 * 격리 큐(PARK)로 빼고 AE 로 응답한 뒤 정상 커밋한다.
 */
public class PermanentProcessingException extends Hl7ProcessingException {

    private static final long serialVersionUID = 1L;

    public PermanentProcessingException(String errorCode, String message) {
        super(errorCode, message);
    }

    public PermanentProcessingException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }

    @Override
    public AckCode getAckCode() {
        return AckCode.AE;
    }

    @Override
    public boolean isRetryable() {
        return false;
    }
}
