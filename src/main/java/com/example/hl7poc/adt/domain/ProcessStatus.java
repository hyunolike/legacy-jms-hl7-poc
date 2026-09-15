package com.example.hl7poc.adt.domain;

/**
 * ADT 메시지 처리 상태. DB 의 {@code ck_adt_status} CHECK 제약과 값이 일치해야 한다.
 *
 * <p>상태를 열거형으로 두는 이유는 오타 방지만이 아니다. 운영 중 "지금 어디까지
 * 갔다가 멈췄는지"를 이 값 하나로 판단하고, 그에 따라 재처리 방식이 달라진다.
 */
public enum ProcessStatus {

    /** 수신해 파싱까지 끝냈다. 이 상태로 남아 있으면 적재 전에 죽은 것이다. */
    RECEIVED,

    /** DB 적재까지 끝냈다. 이 상태로 남아 있으면 병원 B 전달 전에 죽은 것이다. */
    PERSISTED,

    /** 병원 B 전달까지 끝났다. 정상 종료 상태. */
    FORWARDED,

    /** 재시도 대상 오류로 실패했다. */
    FAILED,

    /** 재시도해도 소용없어 격리 큐로 보냈다. */
    PARKED,

    /** 이미 처리한 메시지를 다시 받았다. 업무 처리는 건너뛰고 ACK 만 재전송했다. */
    DUPLICATE
}
