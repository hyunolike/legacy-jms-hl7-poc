package com.example.hl7poc.adt.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Date;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import com.example.hl7poc.adt.domain.AdtMessageRecord;
import com.example.hl7poc.adt.domain.ProcessStatus;

/**
 * {@link AdtMessageDao} 의 JdbcTemplate 구현.
 */
public class AdtMessageDaoJdbc implements AdtMessageDao {

    private static final String SQL_INSERT =
            "INSERT INTO adt_message ("
            + " sending_app, sending_facility, receiving_app, receiving_facility,"
            + " msg_datetime, msg_type, trigger_event, msg_control_id, processing_id, hl7_version,"
            + " patient_id_hash, patient_id_enc, patient_name_enc, patient_dob_enc,"
            + " patient_phone_enc, patient_sex,"
            + " patient_class, assigned_location, admit_datetime, discharge_datetime,"
            + " raw_msg_sha256, status"
            + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private static final String SQL_UPDATE_STATUS =
            "UPDATE adt_message SET status = ?, last_error = ?, updated_at = now() WHERE id = ?";

    private static final String SQL_LATEST_EVENT_TIME =
            "SELECT max(msg_datetime) FROM adt_message WHERE patient_id_hash = ?";

    private static final String SQL_FIND_BY_CTRL =
            "SELECT * FROM adt_message WHERE sending_facility = ? AND msg_control_id = ?";

    /** VARCHAR(500) 컬럼에 맞춘다. 넘치면 INSERT 전체가 실패한다. */
    private static final int MAX_ERROR_LENGTH = 500;

    private final JdbcTemplate jdbcTemplate;

    public AdtMessageDaoJdbc(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public long insert(final AdtMessageRecord r) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(new PreparedStatementCreator() {
            @Override
            public PreparedStatement createPreparedStatement(Connection con) throws SQLException {
                PreparedStatement ps = con.prepareStatement(SQL_INSERT, new String[] {"id"});
                int i = 1;
                ps.setString(i++, r.getSendingApplication());
                ps.setString(i++, r.getSendingFacility());
                ps.setString(i++, r.getReceivingApplication());
                ps.setString(i++, r.getReceivingFacility());
                setTimestamp(ps, i++, r.getMessageDateTime());
                ps.setString(i++, r.getMessageType());
                ps.setString(i++, r.getTriggerEvent());
                ps.setString(i++, r.getMessageControlId());
                ps.setString(i++, r.getProcessingId());
                ps.setString(i++, r.getHl7Version());
                ps.setString(i++, r.getPatientIdHash());
                ps.setString(i++, r.getPatientIdEnc());
                ps.setString(i++, r.getPatientNameEnc());
                ps.setString(i++, r.getPatientDobEnc());
                ps.setString(i++, r.getPatientPhoneEnc());
                ps.setString(i++, r.getPatientSex());
                ps.setString(i++, r.getPatientClass());
                ps.setString(i++, r.getAssignedLocation());
                setTimestamp(ps, i++, r.getAdmitDateTime());
                setTimestamp(ps, i++, r.getDischargeDateTime());
                ps.setString(i++, r.getRawMsgSha256());
                ps.setString(i, r.getStatus().name());
                return ps;
            }
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("adt_message PK 를 받지 못했습니다.");
        }
        return key.longValue();
    }

    @Override
    public void updateStatus(long id, ProcessStatus status, String lastError) {
        jdbcTemplate.update(SQL_UPDATE_STATUS, status.name(), truncate(lastError), id);
    }

    @Override
    public Date findLatestEventTime(String patientIdHash) {
        List<Timestamp> rows =
                jdbcTemplate.queryForList(SQL_LATEST_EVENT_TIME, Timestamp.class, patientIdHash);
        if (rows.isEmpty() || rows.get(0) == null) {
            return null;
        }
        return new Date(rows.get(0).getTime());
    }

    @Override
    public AdtMessageRecord findByControlId(String sendingFacility, String messageControlId) {
        List<AdtMessageRecord> rows = jdbcTemplate.query(
                SQL_FIND_BY_CTRL, ROW_MAPPER, sendingFacility, messageControlId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static final RowMapper<AdtMessageRecord> ROW_MAPPER = new RowMapper<AdtMessageRecord>() {
        @Override
        public AdtMessageRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            AdtMessageRecord r = new AdtMessageRecord();
            r.setId(rs.getLong("id"));
            r.setSendingApplication(rs.getString("sending_app"));
            r.setSendingFacility(rs.getString("sending_facility"));
            r.setReceivingApplication(rs.getString("receiving_app"));
            r.setReceivingFacility(rs.getString("receiving_facility"));
            r.setMessageDateTime(toDate(rs.getTimestamp("msg_datetime")));
            r.setMessageType(rs.getString("msg_type"));
            r.setTriggerEvent(rs.getString("trigger_event"));
            r.setMessageControlId(rs.getString("msg_control_id"));
            r.setProcessingId(rs.getString("processing_id"));
            r.setHl7Version(rs.getString("hl7_version"));
            r.setPatientIdHash(rs.getString("patient_id_hash"));
            r.setPatientIdEnc(rs.getString("patient_id_enc"));
            r.setPatientNameEnc(rs.getString("patient_name_enc"));
            r.setPatientDobEnc(rs.getString("patient_dob_enc"));
            r.setPatientPhoneEnc(rs.getString("patient_phone_enc"));
            r.setPatientSex(rs.getString("patient_sex"));
            r.setPatientClass(rs.getString("patient_class"));
            r.setAssignedLocation(rs.getString("assigned_location"));
            r.setAdmitDateTime(toDate(rs.getTimestamp("admit_datetime")));
            r.setDischargeDateTime(toDate(rs.getTimestamp("discharge_datetime")));
            r.setRawMsgSha256(rs.getString("raw_msg_sha256"));
            r.setStatus(ProcessStatus.valueOf(rs.getString("status")));
            r.setLastError(rs.getString("last_error"));
            return r;
        }
    };

    private static void setTimestamp(PreparedStatement ps, int index, Date value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.TIMESTAMP);
        } else {
            ps.setTimestamp(index, new Timestamp(value.getTime()));
        }
    }

    private static Date toDate(Timestamp ts) {
        return (ts == null) ? null : new Date(ts.getTime());
    }

    /** 오류 문구가 길다고 적재 자체가 실패하면 원인 추적이 더 어려워진다. */
    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return (s.length() <= MAX_ERROR_LENGTH) ? s : s.substring(0, MAX_ERROR_LENGTH);
    }
}
