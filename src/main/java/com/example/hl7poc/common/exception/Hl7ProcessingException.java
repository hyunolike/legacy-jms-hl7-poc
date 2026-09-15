package com.example.hl7poc.common.exception;

import com.example.hl7poc.common.hl7.AckCode;

/**
 * 브리지 처리 중 발생하는 예외의 기반 클래스.
 *
 * <p>이 계층의 핵심은 <b>재시도 가치가 있는가</b>({@link #isRetryable()})를
 * 예외 타입에 박아 두는 것이다. 리스너는 예외 메시지를 읽고 판단하지 않고,
 * 이 플래그만 보고 롤백할지 격리할지를 정한다.
 *
 * <p>재시도가 무의미한 오류를 롤백시키면 최대 재시도 횟수만큼 CPU 를 태우고
 * 결국 DLQ 에 쌓인다. 이런 구조에서 가장 흔한 설계 실수다.
 *
 * <p>런타임 예외로 둔 이유: {@code javax.jms.MessageListener#onMessage} 는 체크
 * 예외를 던질 수 없어서 어차피 감싸야 한다. 다만 DAO/SOAP 계층은 체크 예외를
 * 올릴 수 있으므로 {@code tx:advice} 의 {@code rollback-for} 는 넓게 열어 둔다.
 */
public abstract class Hl7ProcessingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** ERR-3 등에 실어 보낼 내부 오류 코드. 로그 grep 키로도 쓴다. */
    private final String errorCode;

    /**
     * 상대 시스템에 보내도 되는 문구.
     *
     * <p>{@link #getMessage()} 와 나눈 이유가 있다. 예외 메시지에는 진단에 필요한
     * 상세가 들어가는데, 그 상세가 <b>우리가 쓰지 않은 텍스트</b>일 수 있다.
     * 실제로 HAPI 의 파싱 예외 메시지는 "참고용"이라며 원문 앞 50자를 그대로
     * 붙여 준다. 그 문구를 ACK 의 ERR-8 에 실으면 환자 정보가 상대 병원 로그로
     * 넘어간다 — 우리가 통제할 수 없는 곳에 사본이 생긴다.
     *
     * <p>그래서 내부 로그에는 상세를, ACK 에는 이 문구를 쓴다.
     */
    private final String clientSafeText;

    protected Hl7ProcessingException(String errorCode, String message) {
        this(errorCode, message, message, null);
    }

    protected Hl7ProcessingException(String errorCode, String message, Throwable cause) {
        this(errorCode, message, message, cause);
    }

    protected Hl7ProcessingException(String errorCode, String message,
                                     String clientSafeText, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.clientSafeText = clientSafeText;
    }

    public String getErrorCode() {
        return errorCode;
    }

    /** ACK 로 내보낼 문구. 환자 정보나 원문 조각이 섞이지 않은 것만 담는다. */
    public String getClientSafeText() {
        return clientSafeText;
    }

    /** 이 오류를 송신 측에 어떤 ACK 코드로 알릴지. */
    public abstract AckCode getAckCode();

    /** true 면 롤백해서 재전송을 받는다. false 면 격리 큐로 빼고 정상 커밋한다. */
    public abstract boolean isRetryable();
}
