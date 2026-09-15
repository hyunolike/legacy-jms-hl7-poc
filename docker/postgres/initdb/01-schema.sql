-- ============================================================================
--  HL7 ADT 브리지 PoC 스키마
--  이 파일은 컨테이너 첫 기동(initdb)에만 실행된다.
--  스키마를 고쳤으면: docker compose -f docker/docker-compose.yml down -v && up -d
--
--  원칙
--   1) 환자 식별정보(PHI)는 평문으로 두지 않는다. 암호문 컬럼(_enc)만 저장한다.
--   2) 검색이 필요한 식별자는 HMAC 블라인드 인덱스(_hash)로 따로 둔다.
--   3) HL7 원문은 보관하지 않고 SHA-256 지문만 남긴다.
--   4) 저장되는 모든 데이터는 가상의 합성 데이터다.
-- ============================================================================

SET client_encoding = 'UTF8';

-- ---------------------------------------------------------------------------
-- 멱등성 선점 테이블
--   업무 로직보다 먼저 INSERT 해서 (송신기관, MSH-10) 조합을 선점한다.
--   Message Control ID 는 "송신 시스템 안에서만" 유일하므로 복합 PK 로 잡는다.
--   ack_payload 를 함께 저장하는 이유: 중복 수신은 대개 상대가 ACK 를 못 받아
--   재전송한 경우다. 그때 업무 처리는 건너뛰되 같은 ACK 를 다시 돌려줘야 한다.
-- ---------------------------------------------------------------------------
CREATE TABLE processed_message (
    sending_facility  VARCHAR(64)  NOT NULL,
    msg_control_id    VARCHAR(64)  NOT NULL,
    first_seen_at     TIMESTAMP    NOT NULL DEFAULT now(),
    ack_code          CHAR(2),                 -- AA / AE / AR
    ack_payload       TEXT,                    -- 최초에 생성했던 ACK 원문 (재전송용)
    CONSTRAINT pk_processed_message PRIMARY KEY (sending_facility, msg_control_id)
);

COMMENT ON TABLE  processed_message IS '멱등성 선점 테이블. INSERT 성공 = 최초 수신';
COMMENT ON COLUMN processed_message.ack_payload IS '중복 수신 시 재전송할 ACK 원문';

-- ---------------------------------------------------------------------------
-- ADT 업무 데이터
-- ---------------------------------------------------------------------------
CREATE TABLE adt_message (
    id                BIGSERIAL    PRIMARY KEY,

    -- MSH 세그먼트 메타데이터 (식별성이 낮아 평문 보관)
    sending_app       VARCHAR(64),             -- MSH-3
    sending_facility  VARCHAR(64)  NOT NULL,   -- MSH-4
    receiving_app     VARCHAR(64),             -- MSH-5
    receiving_facility VARCHAR(64),            -- MSH-6
    msg_datetime      TIMESTAMP,               -- MSH-7
    msg_type          VARCHAR(16)  NOT NULL,   -- MSH-9  예: ADT^A01^ADT_A01
    trigger_event     VARCHAR(8)   NOT NULL,   -- MSH-9.2  A01 / A03
    msg_control_id    VARCHAR(64)  NOT NULL,   -- MSH-10
    processing_id     CHAR(1),                 -- MSH-11  P/T/D
    hl7_version       VARCHAR(8),              -- MSH-12

    -- PID 세그먼트 (PHI)
    patient_id_hash   VARCHAR(64)  NOT NULL,   -- PID-3 의 HMAC-SHA256 (검색용 블라인드 인덱스)
    patient_id_enc    TEXT         NOT NULL,   -- PID-3  암호문
    patient_name_enc  TEXT,                    -- PID-5  암호문
    patient_dob_enc   TEXT,                    -- PID-7  암호문
    patient_phone_enc TEXT,                    -- PID-13 암호문
    patient_sex       CHAR(1),                 -- PID-8  단독 식별성이 낮아 평문 허용

    -- PV1 세그먼트 (입퇴원)
    patient_class     CHAR(1),                 -- PV1-2  I(입원)/O(외래)/E(응급)
    assigned_location VARCHAR(64),             -- PV1-3  병동^병실^병상
    admit_datetime    TIMESTAMP,               -- PV1-44
    discharge_datetime TIMESTAMP,              -- PV1-45

    -- 처리 상태
    raw_msg_sha256    CHAR(64)     NOT NULL,   -- 원문 지문 (원문 자체는 미보관)
    status            VARCHAR(16)  NOT NULL,   -- ProcessStatus enum
    retry_count       INT          NOT NULL DEFAULT 0,
    last_error        VARCHAR(500),
    created_at        TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT now(),

    CONSTRAINT uq_adt_message UNIQUE (sending_facility, msg_control_id),
    CONSTRAINT ck_adt_status  CHECK (status IN
        ('RECEIVED','PERSISTED','FORWARDED','FAILED','PARKED','DUPLICATE'))
);

-- 같은 환자의 이벤트를 시간 역순으로 조회한다(순서 역전 방어 로직에서 사용).
CREATE INDEX ix_adt_patient_time ON adt_message (patient_id_hash, msg_datetime DESC);
CREATE INDEX ix_adt_status       ON adt_message (status, created_at);

COMMENT ON COLUMN adt_message.patient_id_hash IS 'PID-3 의 HMAC-SHA256. 암호문은 검색이 불가하므로 별도 유지';
COMMENT ON COLUMN adt_message.raw_msg_sha256  IS 'HL7 원문 지문. 원문에는 PHI 가 그대로 있어 보관하지 않는다';

-- ---------------------------------------------------------------------------
-- 단계별 추적 로그
--   MSH-10 하나로 RECEIVED → ... → ACK_SENT 전 구간을 DB 만으로 재구성한다.
--   detail 에는 절대 PHI 를 넣지 않는다 (마스킹된 값 또는 코드만).
-- ---------------------------------------------------------------------------
CREATE TABLE processing_log (
    id                BIGSERIAL    PRIMARY KEY,
    sending_facility  VARCHAR(64),
    msg_control_id    VARCHAR(64)  NOT NULL,
    step              VARCHAR(24)  NOT NULL,   -- RECEIVED/PARSED/DEDUP_OK/.../ACK_SENT
    detail            VARCHAR(500),
    logged_at         TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX ix_processing_log_ctrl ON processing_log (msg_control_id, logged_at);

-- ---------------------------------------------------------------------------
-- 권한: 애플리케이션 계정은 DDL 권한 없이 DML 만 갖는 것이 정석이지만,
-- PoC 에서는 initdb 가 만든 소유자 계정(hl7poc)을 그대로 쓴다.
-- ---------------------------------------------------------------------------
