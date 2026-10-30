-- S2 synchronous manual binding; prerequisite: M4-A1 local identities and users.
-- File only: NOT executed. MySQL 8.0.16+. No dependency on the unused M4-A2 tables.
-- Signed addresses are sensitive: restrict DB/backup access and disable SQL parameter logging.
-- IF NOT EXISTS does not validate existing table shapes. No automatic retention/deletion.
CREATE TABLE IF NOT EXISTS local_episode_source_state (
    episode_id BIGINT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (episode_id),
    CONSTRAINT fk_local_source_state_episode FOREIGN KEY (episode_id) REFERENCES local_video_episode (id),
    CONSTRAINT ck_local_source_state_revision CHECK (revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS local_episode_media_candidate (
    id BIGINT NOT NULL AUTO_INCREMENT,
    episode_id BIGINT NOT NULL,
    expected_revision BIGINT NOT NULL,
    target_binding_id BIGINT NULL COMMENT 'Explicit replacement target; checked under episode lock, never inferred from position',
    source_code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    work_id VARCHAR(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    line_ordinal SMALLINT NOT NULL,
    entry_ordinal SMALLINT NOT NULL,
    label VARCHAR(255) NOT NULL,
    address VARCHAR(2048) NOT NULL COMMENT 'Server-only original signed address; no logs/API exposure',
    address_hash CHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    probe_status VARCHAR(32) NOT NULL,
    candidate_seconds DOUBLE NULL,
    manifest_hash CHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    captured_by INT NOT NULL,
    captured_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    confirmed_seconds DOUBLE NULL,
    confirmed_by INT NULL,
    confirmed_at DATETIME(3) NULL,
    confirmation_reason VARCHAR(500) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_local_candidate_scope (episode_id, id),
    CONSTRAINT fk_local_candidate_episode FOREIGN KEY (episode_id) REFERENCES local_video_episode (id),
    CONSTRAINT fk_local_candidate_captured_by FOREIGN KEY (captured_by) REFERENCES users (id),
    CONSTRAINT fk_local_candidate_confirmed_by FOREIGN KEY (confirmed_by) REFERENCES users (id),
    CONSTRAINT ck_local_candidate_revision CHECK (expected_revision >= 0),
    CONSTRAINT ck_local_candidate_position CHECK (line_ordinal BETWEEN 1 AND 16 AND entry_ordinal BETWEEN 1 AND 1000),
    CONSTRAINT ck_local_candidate_seconds CHECK (candidate_seconds IS NULL OR candidate_seconds > 0),
    CONSTRAINT ck_local_candidate_confirmation CHECK (confirmed_seconds IS NULL OR confirmed_seconds > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS local_episode_source_binding (
    id BIGINT NOT NULL AUTO_INCREMENT,
    episode_id BIGINT NOT NULL,
    candidate_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_local_binding_candidate (candidate_id),
    KEY idx_local_binding_episode (episode_id, id),
    CONSTRAINT fk_local_binding_candidate FOREIGN KEY (episode_id, candidate_id)
        REFERENCES local_episode_media_candidate (episode_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
