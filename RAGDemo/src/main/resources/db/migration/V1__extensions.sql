-- PostgreSQL extensions required by the application.

CREATE EXTENSION IF NOT EXISTS vector;

-- Add TimescaleDB later when we actually implement
-- market/quant time-series storage.
-- CREATE EXTENSION IF NOT EXISTS timescaledb;

CREATE EXTENSION IF NOT EXISTS pgcrypto;