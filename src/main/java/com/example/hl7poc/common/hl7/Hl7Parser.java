package com.example.hl7poc.common.hl7;

import com.example.hl7poc.common.dto.AdtEvent;
import com.example.hl7poc.common.exception.Hl7ParseException;
import com.example.hl7poc.common.exception.PermanentProcessingException;

/**
 * HL7 v2 파서 래퍼.
 *
 * <p>두 단계로 나눈 것이 이 인터페이스의 핵심이다.
 * <ol>
 *   <li>{@link #parseHeader(String)} — MSH 만 읽는다. 여기까지 성공하면 무슨 일이
 *       있어도 ACK 를 만들어 보낼 수 있다(MSA-2 에 넣을 Control ID 를 확보했으므로).</li>
 *   <li>{@link #toAdtEvent(Hl7MessageContext)} — PID/PV1 을 읽고 업무 규칙을 본다.
 *       여기서 실패해도 1번의 결과가 살아 있으므로 정확한 AE 응답이 가능하다.</li>
 * </ol>
 *
 * <p>한 번에 다 하는 {@code parse()} 하나로 만들면, 본문이 잘못됐을 때 ACK 조차
 * 못 보내는 상태가 된다. 상대 병원 입장에서는 "보냈는데 아무 응답이 없음"이 되고,
 * 그 시스템은 재전송을 반복한다.
 */
public interface Hl7Parser {

    /**
     * MSH 세그먼트를 읽는다. 개행 정규화와 MLLP 프레이밍 제거도 여기서 한다.
     *
     * @throws Hl7ParseException 구조 자체를 해석할 수 없을 때 → AR
     */
    Hl7MessageContext parseHeader(String rawMessage);

    /**
     * 본문(PID/PV1)을 읽어 업무 DTO 로 만든다.
     *
     * @throws PermanentProcessingException 필수 값 누락, 미지원 트리거 등 → AE
     */
    AdtEvent toAdtEvent(Hl7MessageContext context);
}
