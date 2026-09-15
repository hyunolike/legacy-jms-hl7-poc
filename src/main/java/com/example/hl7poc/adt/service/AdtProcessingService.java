package com.example.hl7poc.adt.service;

import com.example.hl7poc.common.dto.AdtEvent;
import com.example.hl7poc.common.dto.ProcessResult;
import com.example.hl7poc.common.hl7.AckCode;

/**
 * ADT 이벤트 처리. condb-secure 의 업무 서비스에 대응한다.
 *
 * <p>트랜잭션 경계는 두 군데서 열린다.
 * <ul>
 *   <li>JMS 경로 — 리스너 컨테이너(DMLC)가 이미 열어 둔 트랜잭션에 합류한다.</li>
 *   <li>비-JMS 경로 — DLQ 재처리 도구나 테스트가 직접 부를 때는 여기의
 *       {@code tx:advice} 가 연다.</li>
 * </ul>
 */
public interface AdtProcessingService {

    /**
     * 멱등성 선점 → 암호화 → 적재 → 병원 B 전달.
     *
     * @throws com.example.hl7poc.common.exception.TransientProcessingException
     *         일시 오류. 호출자가 롤백해 재시도를 유도해야 한다.
     * @throws com.example.hl7poc.common.exception.PermanentProcessingException
     *         업무적으로 처리 불가.
     */
    ProcessResult process(AdtEvent event);

    /**
     * 보낸 ACK 를 멱등성 테이블에 기록한다. 중복 수신 시 그대로 재전송하기 위해서다.
     *
     * <p>리스너가 ACK 를 만든 뒤 호출한다. 같은 트랜잭션 안에서 일어나므로,
     * 처리가 롤백되면 ACK 기록도 함께 사라진다.
     */
    void recordAck(String sendingFacility, String messageControlId,
                   AckCode ackCode, String ackPayload);

    /** 파싱/검증 실패로 격리한 사실을 남긴다. */
    void recordParked(String sendingFacility, String messageControlId,
                      String errorCode, String errorText);
}
