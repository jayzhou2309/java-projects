-- ============================================================
-- V1__init_shipping_schema.sql
--
-- Flyway initial migration for the Shipping Backend.
-- Flyway runs this automatically on startup if it hasn't been
-- applied yet. The version prefix V1__ ensures it runs first.
-- ============================================================

-- Create the database if it doesn't exist
CREATE DATABASE IF NOT EXISTS ShippingApp
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE ShippingApp;

-- ── shipment table ────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS shipment (
                                        id              BIGINT          NOT NULL AUTO_INCREMENT,
                                        tracking_number VARCHAR(50)     NOT NULL,
                                        sender_name     VARCHAR(100)    NOT NULL,
                                        receiver_name   VARCHAR(100)    NOT NULL,
                                        origin_address  VARCHAR(255)    NOT NULL,
                                        dest_address    VARCHAR(255)    NOT NULL,
                                        status          VARCHAR(20)     NOT NULL DEFAULT 'CREATED',
                                        created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                        updated_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
                                            ON UPDATE CURRENT_TIMESTAMP,

                                        CONSTRAINT pk_shipment          PRIMARY KEY (id),
                                        CONSTRAINT uq_tracking_number   UNIQUE      (tracking_number),
                                        CONSTRAINT chk_status           CHECK       (status IN ('CREATED','IN_TRANSIT','DELIVERED','FAILED'))
);

-- ── indexes ───────────────────────────────────────────────────────────────────

-- Speed up the /track/{number} endpoint
CREATE INDEX idx_shipment_tracking_number ON shipment (tracking_number);

-- Speed up status-based filtering queries
CREATE INDEX idx_shipment_status ON shipment (status);

-- ── seed data ─────────────────────────────────────────────────────────────────

INSERT INTO shipment (tracking_number, sender_name, receiver_name, origin_address, dest_address, status)
VALUES
    ('TRK-SEED0001', 'Alice Tan', 'Bob Lee',   '10 Orchard Rd, Singapore',  '5 Marina Blvd, Singapore',  'CREATED'),
    ('TRK-SEED0002', 'Carol Ng',  'Dan Lim',   '22 Toa Payoh, Singapore',   '88 Jurong West, Singapore', 'IN_TRANSIT'),
    ('TRK-SEED0003', 'Eve Wong',  'Frank Koh', '3 Bugis St, Singapore',     '77 Tampines Ave, Singapore','DELIVERED');