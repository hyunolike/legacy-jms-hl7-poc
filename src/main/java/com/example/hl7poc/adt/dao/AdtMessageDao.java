package com.example.hl7poc.adt.dao;

import java.util.Date;

import com.example.hl7poc.adt.domain.AdtMessageRecord;
import com.example.hl7poc.adt.domain.ProcessStatus;

/**
 * {@code adt_message} 테이블 접근.
 */
public interface AdtMessageDao {

    /** @return 생성된 PK */
    long insert(AdtMessageRecord record);

    void updateStatus(long id, ProcessStatus status, String lastError);

    /**
     * PK 를 모를 때 쓰는 상태 갱신. 리스너가 격리 처리를 할 때는 적재 시점의 id 를
     * 들고 있지 않다.
     *
     * @return 영향 행 수. 적재 전에 실패한 메시지는 0 이며 이는 정상이다.
     */
    int updateStatusByControlId(String sendingFacility, String messageControlId,
                                ProcessStatus status, String lastError);

    /**
     * 같은 환자의 가장 최근 이벤트 시각(MSH-7).
     *
     * <p>메시지 순서가 뒤집혀 도착했는지 판단하는 데 쓴다. 메시지 그룹으로 순서를
     * 잡아도 컨슈머가 죽어 그룹이 재배정되는 순간에는 흔들릴 수 있다.
     *
     * @return 이력이 없으면 null
     */
    Date findLatestEventTime(String patientIdHash);

    /** 테스트/운영 조회용. 없으면 null. */
    AdtMessageRecord findByControlId(String sendingFacility, String messageControlId);
}
