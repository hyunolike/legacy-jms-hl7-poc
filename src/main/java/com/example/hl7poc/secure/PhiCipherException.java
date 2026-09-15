package com.example.hl7poc.secure;

/**
 * 암복호화 실패.
 *
 * <p>메시지에 평문이나 키를 절대 넣지 않는다. 이 예외는 거의 항상 그대로 로그로
 * 나간다.
 */
public class PhiCipherException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PhiCipherException(String message) {
        super(message);
    }

    public PhiCipherException(String message, Throwable cause) {
        super(message, cause);
    }
}
