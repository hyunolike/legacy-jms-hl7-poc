package com.example.hl7poc.adt.dao;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

import com.example.hl7poc.common.hl7.AckCode;

/**
 * {@link ProcessedMessageDao} 의 JdbcTemplate 구현.
 *
 * <p>Oracle 로 옮길 때 손볼 곳은 {@code ON CONFLICT} 하나다
 * (Oracle 은 {@code MERGE} 또는 유니크 위반 예외 처리로 바꾼다).
 */
public class ProcessedMessageDaoJdbc implements ProcessedMessageDao {

    private static final String SQL_CLAIM =
            "INSERT INTO processed_message (sending_facility, msg_control_id) "
            + "VALUES (?, ?) ON CONFLICT DO NOTHING";

    private static final String SQL_STORE_ACK =
            "UPDATE processed_message SET ack_code = ?, ack_payload = ? "
            + "WHERE sending_facility = ? AND msg_control_id = ?";

    private static final String SQL_FIND_ACK_PAYLOAD =
            "SELECT ack_payload FROM processed_message "
            + "WHERE sending_facility = ? AND msg_control_id = ?";

    private static final String SQL_FIND_ACK_CODE =
            "SELECT ack_code FROM processed_message "
            + "WHERE sending_facility = ? AND msg_control_id = ?";

    private final JdbcTemplate jdbcTemplate;

    public ProcessedMessageDaoJdbc(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * {@inheritDoc}
     *
     * <p>영향 행 수로 판단하는 것이 핵심이다. {@code SELECT} 로 먼저 확인하고
     * 없으면 {@code INSERT} 하는 방식은 두 컨슈머가 동시에 들어오면 둘 다 "없음"을
     * 보고 둘 다 처리한다. 판정을 DB 의 유니크 제약에 맡겨야 한다.
     *
     * <p>동시 요청이 겹치면 PostgreSQL 은 앞선 트랜잭션이 끝날 때까지 여기서
     * 대기한다. 앞이 커밋되면 이쪽은 0행(중복), 앞이 롤백되면 이쪽이 1행(최초)을
     * 받는다. 즉 "앞 트랜잭션이 롤백됐는데 뒤에서 중복으로 판정해 버리는" 구멍이
     * 생기지 않는다.
     */
    @Override
    public boolean tryClaim(String sendingFacility, String messageControlId) {
        return jdbcTemplate.update(SQL_CLAIM, sendingFacility, messageControlId) == 1;
    }

    @Override
    public void storeAck(String sendingFacility, String messageControlId,
                         AckCode ackCode, String ackPayload) {
        jdbcTemplate.update(SQL_STORE_ACK,
                (ackCode == null) ? null : ackCode.name(),
                ackPayload, sendingFacility, messageControlId);
    }

    @Override
    public String findAckPayload(String sendingFacility, String messageControlId) {
        return single(jdbcTemplate.queryForList(
                SQL_FIND_ACK_PAYLOAD, String.class, sendingFacility, messageControlId));
    }

    @Override
    public AckCode findAckCode(String sendingFacility, String messageControlId) {
        String code = single(jdbcTemplate.queryForList(
                SQL_FIND_ACK_CODE, String.class, sendingFacility, messageControlId));
        return (code == null) ? null : AckCode.valueOf(code.trim());
    }

    /**
     * {@code queryForObject} 는 행이 없으면 예외를 던진다. "없음"은 정상 상황이라
     * 예외로 다루지 않는다.
     */
    private static String single(List<String> rows) {
        return (rows == null || rows.isEmpty()) ? null : rows.get(0);
    }
}
