-- =====================================================
-- USERS
-- =====================================================
CREATE TABLE users (
                       id BIGINT AUTO_INCREMENT PRIMARY KEY,

                       username VARCHAR(50) NOT NULL UNIQUE,
                       email VARCHAR(100) NOT NULL UNIQUE,
                       password VARCHAR(255) NOT NULL,

                       enabled BOOLEAN NOT NULL DEFAULT TRUE,

                       created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                       updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                           ON UPDATE CURRENT_TIMESTAMP
);

-- =====================================================
-- ROLES
-- =====================================================
CREATE TABLE roles (
                       id BIGINT AUTO_INCREMENT PRIMARY KEY,

                       name VARCHAR(30) NOT NULL UNIQUE
);

-- =====================================================
-- USER_ROLES (Many-to-Many)
-- =====================================================
CREATE TABLE user_roles (
                            user_id BIGINT NOT NULL,
                            role_id BIGINT NOT NULL,

                            PRIMARY KEY (user_id, role_id),

                            CONSTRAINT fk_user_roles_user
                                FOREIGN KEY (user_id)
                                    REFERENCES users(id)
                                    ON DELETE CASCADE,

                            CONSTRAINT fk_user_roles_role
                                FOREIGN KEY (role_id)
                                    REFERENCES roles(id)
                                    ON DELETE CASCADE
);

-- =====================================================
-- REFRESH TOKENS
-- =====================================================
CREATE TABLE refresh_tokens (
                                id BIGINT AUTO_INCREMENT PRIMARY KEY,

                                token VARCHAR(512) NOT NULL UNIQUE,

                                user_id BIGINT NOT NULL,

                                expires_at TIMESTAMP NOT NULL,

                                revoked BOOLEAN NOT NULL DEFAULT FALSE,

                                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

                                CONSTRAINT fk_refresh_token_user
                                    FOREIGN KEY (user_id)
                                        REFERENCES users(id)
                                        ON DELETE CASCADE
);

-- =====================================================
-- AUDIT LOGS
-- Written by Kafka Consumer
-- =====================================================
CREATE TABLE audit_logs (
                            id BIGINT AUTO_INCREMENT PRIMARY KEY,

                            event_type VARCHAR(50) NOT NULL,

                            username VARCHAR(50),

                            user_id BIGINT,

                            message VARCHAR(255),

                            ip_address VARCHAR(45),

                            user_agent VARCHAR(255),

                            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- =====================================================
-- INDEXES
-- =====================================================

CREATE INDEX idx_refresh_token
    ON refresh_tokens(token);

CREATE INDEX idx_refresh_user
    ON refresh_tokens(user_id);

CREATE INDEX idx_refresh_expiry
    ON refresh_tokens(expires_at);

CREATE INDEX idx_audit_event
    ON audit_logs(event_type);

CREATE INDEX idx_audit_user
    ON audit_logs(user_id);

CREATE INDEX idx_audit_created
    ON audit_logs(created_at);