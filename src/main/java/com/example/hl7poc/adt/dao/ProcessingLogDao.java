package com.example.hl7poc.adt.dao;

/**
 * 단계별 추적 로그 적재.
 *
 * <p>로그 파일과 별개로 DB 에도 남기는 이유: 파일 로그는 회전되어 사라지고,
 * 여러 인스턴스에 흩어진다. "이 MSH-10 은 어디까지 갔나"를 SQL 한 줄로 답할 수
 * 있어야 운영이 편하다.
 *
 * <p>{@code detail} 에는 환자 정보를 넣지 않는다. 마스킹된 값이나 코드만 넣는다.
 */
public interface ProcessingLogDao {

    void log(String sendingFacility, String messageControlId, String step, String detail);
}
