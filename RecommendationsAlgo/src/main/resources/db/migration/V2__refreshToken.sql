CREATE TABLE refresh_tokens (
                                id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                                user_id     BIGINT       NOT NULL,
                                token_hash  VARCHAR(255) NOT NULL,
                                expires_at  TIMESTAMP    NOT NULL,
                                revoked_at  TIMESTAMP    NULL,
                                created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

                                CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id)
                                    REFERENCES users (id) ON DELETE CASCADE,
                                CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash)
);

-- Lookup on refresh: find valid tokens for a user
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);

-- Cleanup / expiry sweep job, and quick validity checks on refresh
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens (expires_at);

ALTER TABLE users ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;