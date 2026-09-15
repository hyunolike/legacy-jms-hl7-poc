package com.example.hl7poc.adt.dao;

import com.example.hl7poc.common.hl7.AckCode;

/**
 * 멱등성 선점 테이블 접근.
 */
public interface ProcessedMessageDao {

    /**
     * {@code (sending_facility, msg_control_id)} 를 선점한다.
     *
     * @return true 면 최초 수신(계속 처리), false 면 이미 처리된 메시지
     */
    boolean tryClaim(String sendingFacility, String messageControlId);

    /** 최초 처리 시 보냈던 ACK 를 저장한다. 중복 수신 때 그대로 재전송한다. */
    void storeAck(String sendingFacility, String messageControlId,
                  AckCode ackCode, String ackPayload);

    /** 저장된 ACK 원문. 없으면 null. */
    String findAckPayload(String sendingFacility, String messageControlId);

    /** 저장된 ACK 코드. 없으면 null. */
    AckCode findAckCode(String sendingFacility, String messageControlId);
}
