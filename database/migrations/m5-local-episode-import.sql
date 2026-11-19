-- M5 single-episode import: local episode import revision + nullable external playback columns.
-- Existing databases only; prerequisites: M4-A1 local identities and S2 source binding tables.
-- Fresh databases created with database/feibijiubi.sql already apply these changes; do not rerun this file.
-- MySQL 8.0.16+ (same prerequisite as M4-A1). No data writes, no backfill, no fake COS keys.
--
-- Why these two statements:
--   * local_video_episode.import_revision records the source revision that the first import
--     observed. Retries return it unchanged; a later M6 line change cannot erase the fact.
--   * M5 external videos have no local playback object: video_url / video_key stay NULL.
--     Existing COS rows keep their values; no UPDATE/backfill is performed here.
--   * cover_url / cover_key remain NOT NULL: M5 always claims an administrator-uploaded COS
--     cover. The migration deliberately does not relax them.
--
-- IF NOT EXISTS is not available for ADD COLUMN / MODIFY COLUMN in MySQL; this file is not
-- idempotent and must be applied once. Re-running it fails loudly instead of silently skipping.

ALTER TABLE local_video_episode
    ADD COLUMN import_revision BIGINT NULL
        COMMENT 'M5 first-import source revision; stable retry evidence, not rewritten by M6';

ALTER TABLE video
    MODIFY COLUMN video_url VARCHAR(500) NULL
        COMMENT '视频URL；外部来源为 NULL，历史 COS 行保持原值',
    MODIFY COLUMN video_key VARCHAR(500) NULL
        COMMENT 'COS 对象 key；外部来源为 NULL（不写假 key），历史行保持原值';
