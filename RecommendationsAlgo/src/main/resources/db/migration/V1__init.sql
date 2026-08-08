CREATE TABLE users (
                       id            BIGINT AUTO_INCREMENT PRIMARY KEY,
                       username      VARCHAR(50)  NOT NULL,
                       email         VARCHAR(255) NOT NULL,
                       password_hash VARCHAR(255) NOT NULL,
                       created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

                       CONSTRAINT uq_users_username UNIQUE (username),
                       CONSTRAINT uq_users_email UNIQUE (email)
);

CREATE INDEX idx_users_created_at ON users (created_at);

CREATE TABLE content (
                         id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                         title       VARCHAR(255) NOT NULL,
                         category    VARCHAR(100) NOT NULL,
                         creator_id  BIGINT       NOT NULL,
                         created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Flexible bag for duration_sec, tags[], and future fields without a migration
                         metadata    JSON         NOT NULL,

                         CONSTRAINT fk_content_creator FOREIGN KEY (creator_id)
                             REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_content_category ON content (category);
CREATE INDEX idx_content_creator_id ON content (creator_id);
CREATE INDEX idx_content_created_at ON content (created_at);

-- Note: MySQL doesn't support GIN indexes on JSON columns like Postgres does.
-- If you need to filter/search inside metadata efficiently, consider adding
-- generated columns for the specific JSON fields you query most, e.g.:
-- ALTER TABLE content ADD COLUMN tag VARCHAR(100)
--   GENERATED ALWAYS AS (JSON_UNQUOTE(JSON_EXTRACT(metadata, '$.tag'))) STORED,
--   ADD INDEX idx_content_metadata_tag (tag);

CREATE TABLE interactions (
                              id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
                              user_id            BIGINT       NOT NULL,
                              content_id         BIGINT       NOT NULL,
                              type               VARCHAR(20)  NOT NULL,
                              watch_duration_sec INTEGER,
                              `timestamp`        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

                              CONSTRAINT fk_interactions_user FOREIGN KEY (user_id)
                                  REFERENCES users (id) ON DELETE CASCADE,
                              CONSTRAINT fk_interactions_content FOREIGN KEY (content_id)
                                  REFERENCES content (id) ON DELETE CASCADE,
                              CONSTRAINT chk_interactions_type
                                  CHECK (type IN ('VIEW', 'LIKE', 'WATCH'))
);

-- "top interacted categories per user" / user history lookups
CREATE INDEX idx_interactions_user_id ON interactions (user_id);

-- popularity aggregation (view/like counts per content item)
CREATE INDEX idx_interactions_content_id_type ON interactions (content_id, type);

-- recency-weighted scoring and time-window queries
CREATE INDEX idx_interactions_timestamp ON interactions (`timestamp`);

-- Denormalized counters maintained by the Kafka consumer as interactions land,
-- so "total views / total likes per content" don't require a COUNT(*) scan.
CREATE TABLE content_metrics (
                                 content_id   BIGINT PRIMARY KEY,
                                 view_count   BIGINT      NOT NULL DEFAULT 0,
                                 like_count   BIGINT      NOT NULL DEFAULT 0,
                                 watch_count  BIGINT      NOT NULL DEFAULT 0,
                                 updated_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

                                 CONSTRAINT fk_content_metrics_content FOREIGN KEY (content_id)
                                     REFERENCES content (id) ON DELETE CASCADE
);