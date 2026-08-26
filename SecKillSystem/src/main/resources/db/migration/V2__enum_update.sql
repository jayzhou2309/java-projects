-- V2__change_status_columns_to_string.sql

-- ============================================================
-- 1. seckill_activity.activity_status
-- ============================================================

ALTER TABLE seckill_activity
    MODIFY COLUMN activity_status VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED';

UPDATE seckill_activity
SET activity_status = CASE activity_status
                          WHEN '0' THEN 'NOT_STARTED'
                          WHEN '1' THEN 'ONGOING'
                          WHEN '2' THEN 'ENDED'
                          WHEN '3' THEN 'OFFLINE'
                          ELSE 'NOT_STARTED'
    END;


-- ============================================================
-- 2. seckill_order.order_status
-- ============================================================

ALTER TABLE seckill_order
    MODIFY COLUMN order_status VARCHAR(20) NOT NULL DEFAULT 'QUEUEING';

UPDATE seckill_order
SET order_status = CASE order_status
                       WHEN '0' THEN 'QUEUEING'
                       WHEN '1' THEN 'SUCCESS'
                       WHEN '2' THEN 'FAIL'
                       WHEN '3' THEN 'TIME_OUT'
                       WHEN '4' THEN 'CLOSED'
                       ELSE 'QUEUEING'
    END;


-- ============================================================
-- 3. seckill_order.pay_status
-- ============================================================

ALTER TABLE seckill_order
    MODIFY COLUMN pay_status VARCHAR(20) NOT NULL DEFAULT 'UN_PAY';

UPDATE seckill_order
SET pay_status = CASE pay_status
                     WHEN '0' THEN 'UN_PAY'
                     WHEN '1' THEN 'PAID'
                     WHEN '2' THEN 'REFUND'
                     ELSE 'UN_PAY'
    END;