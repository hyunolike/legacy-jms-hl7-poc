package com.example.hl7poc.common.exception;

import com.example.hl7poc.common.hl7.AckCode;

/**
 * 잠시 뒤에는 성공할 수 있는 오류. DB 커넥션 끊김, 병원 B SOAP 타임아웃 등.
 *
 * <p>리스너가 이 예외를 그대로 올리면 트랜잭션이 롤백되고
 * {@code RedeliveryPolicy} 에 따라 2s → 4s → 8s 간격으로 재전송된다.
 * 소진하면 브로커가 DLQ 로 보낸다.
 *
 * <p>이 경우 ACK 를 보내지 않는다. 아직 성공도 실패도 확정되지 않았기 때문이다.
 * 여기서 성급하게 AE 를 보내면 송신 측은 "영구 실패"로 처리해 버린다.
 */
public class TransientProcessingException extends Hl7ProcessingException {

    private static final long serialVersionUID = 1L;

    public TransientProcessingException(String errorCode, String message) {
        super(errorCode, message);
    }

    public TransientProcessingException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }

    /** 재시도 중에는 ACK 를 보내지 않으므로 이 값은 쓰이지 않는다. */
    @Override
    public AckCode getAckCode() {
        return AckCode.AE;
    }

    @Override
    public boolean isRetryable() {
        return true;
    }
}
