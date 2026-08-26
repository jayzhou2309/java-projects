-- =====================================================================
-- Seckill (Flash Sale) System - Core Schema
-- Engine: InnoDB (row-level locking, required for concurrent stock ops)
-- Charset: utf8mb4
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. seckill_activity
-- ---------------------------------------------------------------------
CREATE TABLE seckill_activity (
                                  id                  BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
                                  activity_name       VARCHAR(128)    NOT NULL,
                                  start_time          DATETIME        NOT NULL,
                                  end_time            DATETIME        NOT NULL,
                                  activity_status     TINYINT         NOT NULL DEFAULT 0 COMMENT '0-NOT_START 1-ONGOING 2-ENDED 3-OFFLINE',
                                  limit_count         INT             NOT NULL DEFAULT 1 COMMENT 'max purchases per user for this activity',
                                  ip_limit_threshold  INT             NOT NULL DEFAULT 100 COMMENT 'max requests per IP per minute',
                                  remark              VARCHAR(512)    DEFAULT NULL,
                                  create_time         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                  update_time         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                                  is_deleted          TINYINT         NOT NULL DEFAULT 0,
                                  KEY idx_status_time (activity_status, start_time, end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Seckill activity';

-- ---------------------------------------------------------------------
-- 2. seckill_goods
-- availableStock is the single source of truth guarded against
-- negative values by a CHECK constraint. The decrement statement
-- (see repository layer) MUST be:
--   UPDATE seckill_goods
--   SET available_stock = available_stock - 1, version = version + 1
--   WHERE id = ? AND available_stock > 0
-- and the caller must verify affected rows == 1 (never read-then-write).
-- ---------------------------------------------------------------------
CREATE TABLE seckill_goods (
                               id                  BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
                               activity_id         BIGINT UNSIGNED NOT NULL,
                               goods_id            BIGINT UNSIGNED NOT NULL,
                               seckill_price       DECIMAL(10,2)   NOT NULL,
                               original_price      DECIMAL(10,2)   NOT NULL,
                               total_stock         INT             NOT NULL DEFAULT 0,
                               available_stock     INT             NOT NULL DEFAULT 0,
                               sales_count          INT             NOT NULL DEFAULT 0,
                               version             INT             NOT NULL DEFAULT 0 COMMENT 'optimistic lock, incremented on every stock change',
                               create_time         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
                               update_time         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                               is_deleted          TINYINT         NOT NULL DEFAULT 0,
                               KEY idx_activity (activity_id),
                               CONSTRAINT chk_available_stock_non_negative CHECK (available_stock >= 0),
                               CONSTRAINT chk_available_stock_le_total CHECK (available_stock <= total_stock)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Seckill product / stock';

-- ---------------------------------------------------------------------
-- 3. seckill_order
-- uk_user_activity is what actually guarantees "one purchase per user":
-- a duplicate INSERT will throw a constraint violation even under a
-- race of two concurrent requests for the same user - the check must
-- not rely on a prior SELECT.
-- ---------------------------------------------------------------------
CREATE TABLE seckill_order (
                               id                  BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
                               order_no            VARCHAR(64)     NOT NULL,
                               user_id             BIGINT UNSIGNED NOT NULL,
                               activity_id         BIGINT UNSIGNED NOT NULL,
                               seckill_goods_id    BIGINT UNSIGNED NOT NULL,
                               goods_id            BIGINT UNSIGNED NOT NULL,
                               seckill_price       DECIMAL(10,2)   NOT NULL,
                               order_status        TINYINT         NOT NULL DEFAULT 0 COMMENT '0-QUEUEING 1-SUCCESS 2-FAIL 3-TIME_OUT 4-CLOSED',
                               pay_status          TINYINT         NOT NULL DEFAULT 0 COMMENT '0-UN_PAY 1-PAID 2-REFUND',
                               pay_time            DATETIME        DEFAULT NULL,
                               close_time          DATETIME        DEFAULT NULL,
                               pay_deadline        DATETIME        NOT NULL COMMENT 'create_time + 5 minutes; scanned by the auto-release job',
                               create_time         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
                               update_time         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                               is_deleted          TINYINT         NOT NULL DEFAULT 0,
                               UNIQUE KEY uk_order_no (order_no),
                               UNIQUE KEY uk_user_activity (user_id, activity_id) COMMENT 'enforces one order per user per activity, race-safe',
                               KEY idx_create_time (create_time),
                               KEY idx_timeout_scan (order_status, pay_deadline) COMMENT 'used by the timeout-release scheduled job'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Seckill order';

-- ---------------------------------------------------------------------
-- 4. seckill_stock_log
-- ---------------------------------------------------------------------
CREATE TABLE seckill_stock_log (
                                   id                  BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
                                   seckill_goods_id    BIGINT UNSIGNED NOT NULL,
                                   order_no            VARCHAR(64)     DEFAULT NULL,
                                   change_type         TINYINT         NOT NULL COMMENT '1-DEDUCT 2-ROLLBACK 3-MANUAL',
                                   change_num          INT             NOT NULL COMMENT 'positive=increase, negative=decrease',
                                   stock_before        INT             NOT NULL,
                                   stock_after         INT             NOT NULL,
                                   remark              VARCHAR(255)    DEFAULT NULL,
                                   create_time         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                   KEY idx_goods_time (seckill_goods_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Stock change audit log';