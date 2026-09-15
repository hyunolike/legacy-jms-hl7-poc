package com.example.hl7poc.adt.dao;

import org.springframework.jdbc.core.JdbcTemplate;

public class ProcessingLogDaoJdbc implements ProcessingLogDao {

    private static final String SQL_INSERT =
            "INSERT INTO processing_log (sending_facility, msg_control_id, step, detail) "
            + "VALUES (?,?,?,?)";

    private static final int MAX_DETAIL_LENGTH = 500;

    private final JdbcTemplate jdbcTemplate;

    public ProcessingLogDaoJdbc(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void log(String sendingFacility, String messageControlId, String step, String detail) {
        jdbcTemplate.update(SQL_INSERT, sendingFacility, messageControlId, step, truncate(detail));
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return (s.length() <= MAX_DETAIL_LENGTH) ? s : s.substring(0, MAX_DETAIL_LENGTH);
    }
}
