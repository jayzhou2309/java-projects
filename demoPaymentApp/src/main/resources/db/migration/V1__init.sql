-- ============================================================
-- V1__init_schema.sql
-- Initial schema for Payment Service (MySQL)
-- ============================================================

-- ============================================================
-- APP_USERS
-- ============================================================
CREATE TABLE app_users (
                           id            BIGINT          NOT NULL AUTO_INCREMENT,
                           name          VARCHAR(100)    NOT NULL,
                           email         VARCHAR(150)    NOT NULL,
                           password_hash VARCHAR(255)    NOT NULL,
                           created_at    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
                           PRIMARY KEY (id),
                           UNIQUE KEY uq_users_email (email)
);

CREATE INDEX idx_users_email ON app_users (email);

-- ============================================================
-- ACCOUNTS
-- ============================================================
CREATE TABLE accounts (
                          id         BIGINT           NOT NULL AUTO_INCREMENT,
                          user_id    BIGINT           NOT NULL,
                          balance    DECIMAL(15, 2)   NOT NULL DEFAULT 0.00,
                          currency   VARCHAR(3)       NOT NULL DEFAULT 'SGD',
                          created_at TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP,
                          PRIMARY KEY (id),
                          CONSTRAINT chk_balance_non_negative CHECK (balance >= 0),
                          CONSTRAINT fk_accounts_user FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE
);

CREATE INDEX idx_accounts_user_id ON accounts (user_id);

-- ============================================================
-- TRANSACTIONS
-- ============================================================
CREATE TABLE transactions (
                              id                   BIGINT                               NOT NULL AUTO_INCREMENT,
                              sender_account_id    BIGINT                               NOT NULL,
                              receiver_account_id  BIGINT                               NOT NULL,
                              amount               DECIMAL(15, 2)                       NOT NULL,
                              status               ENUM('PENDING', 'SUCCESS', 'FAILED') NOT NULL DEFAULT 'PENDING',
                              created_at           TIMESTAMP                            NOT NULL DEFAULT CURRENT_TIMESTAMP,
                              PRIMARY KEY (id),
                              CONSTRAINT chk_amount_positive    CHECK (amount > 0),
                              CONSTRAINT chk_different_accounts CHECK (sender_account_id <> receiver_account_id),
                              CONSTRAINT fk_transactions_sender   FOREIGN KEY (sender_account_id)   REFERENCES accounts(id),
                              CONSTRAINT fk_transactions_receiver FOREIGN KEY (receiver_account_id) REFERENCES accounts(id)
);

CREATE INDEX idx_transactions_sender   ON transactions (sender_account_id);
CREATE INDEX idx_transactions_receiver ON transactions (receiver_account_id);
CREATE INDEX idx_transactions_status   ON transactions (status);