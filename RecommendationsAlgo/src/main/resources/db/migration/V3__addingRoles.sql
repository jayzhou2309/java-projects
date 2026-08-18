-- V2__add_role_and_soft_delete.sql

ALTER TABLE users
    ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER';

ALTER TABLE users
    ADD CONSTRAINT chk_users_role CHECK (role IN ('USER', 'ADMIN'));

ALTER TABLE content
    ADD COLUMN deleted_at TIMESTAMP NULL;

-- Soft-deleted content should stop showing up in browsing/recommendation queries —
-- this index supports filtering WHERE deleted_at IS NULL efficiently
CREATE INDEX idx_content_deleted_at ON content (deleted_at);