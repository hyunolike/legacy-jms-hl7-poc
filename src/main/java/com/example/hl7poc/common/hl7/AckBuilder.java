package com.example.hl7poc.common.hl7;

/**
 * HL7 ACK 생성. 구현은 7단계({@code HapiAckBuilder}).
 *
 * <p>인터페이스를 먼저 두는 이유: 5단계의 리스너가 ACK 생성에 의존하는데,
 * 리스너의 관심사는 "무슨 코드로 응답할지"이지 "ACK 를 어떻게 조립할지"가 아니다.
 * 경계를 먼저 그어 두면 7단계에서 ERR 세그먼트를 붙여도 리스너는 손대지 않는다.
 */
public interface AckBuilder {

    /**
     * 원본 헤더를 받아 ACK 를 만든다.
     *
     * @param errorCode 오류 코드(AE/AR 일 때). 없으면 null
     * @param errorText 사람이 읽을 오류 설명. 없으면 null.
     *                  <b>환자 정보를 넣지 않는다</b> — ACK 는 네트워크로 나가고
     *                  상대 시스템 로그에 그대로 쌓인다.
     */
    String buildAck(Hl7MessageContext header, AckCode ackCode, String errorCode, String errorText);

    /**
     * 헤더조차 읽지 못했을 때의 거절 ACK.
     *
     * <p>MSH-10 을 모르므로 MSA-2 에 무엇을 넣을지가 문제다. 구현체가 정한다.
     */
    String buildRejectAck(String rawMessage, String errorCode, String errorText);
}
