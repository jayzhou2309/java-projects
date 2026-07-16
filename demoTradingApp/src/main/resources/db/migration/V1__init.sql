-- V1__init.sql

CREATE TABLE roles (
                       id BIGINT AUTO_INCREMENT PRIMARY KEY,
                       name VARCHAR(50) NOT NULL UNIQUE
);

CREATE TABLE users (
                       id BIGINT AUTO_INCREMENT PRIMARY KEY,
                       username VARCHAR(100) NOT NULL UNIQUE,
                       email VARCHAR(255) NOT NULL UNIQUE,
                       password VARCHAR(255) NOT NULL,
                       enabled BOOLEAN NOT NULL DEFAULT TRUE,
                       created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                       updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                           ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE user_roles (
                            user_id BIGINT NOT NULL,
                            role_id BIGINT NOT NULL,

                            PRIMARY KEY(user_id, role_id),

                            CONSTRAINT fk_user_roles_user
                                FOREIGN KEY(user_id)
                                    REFERENCES users(id),

                            CONSTRAINT fk_user_roles_role
                                FOREIGN KEY(role_id)
                                    REFERENCES roles(id)
);

CREATE TABLE portfolios (
                            id BIGINT AUTO_INCREMENT PRIMARY KEY,

                            user_id BIGINT NOT NULL UNIQUE,

                            available_cash DECIMAL(19,4) NOT NULL DEFAULT 0,

                            reserved_cash DECIMAL(19,4) NOT NULL DEFAULT 0,

                            version BIGINT NOT NULL DEFAULT 0,

                            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

                            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                                ON UPDATE CURRENT_TIMESTAMP,

                            CONSTRAINT fk_portfolio_user
                                FOREIGN KEY(user_id)
                                    REFERENCES users(id)
);

CREATE TABLE stocks (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,

                        symbol VARCHAR(20) NOT NULL UNIQUE,

                        company_name VARCHAR(255) NOT NULL,

                        current_price DECIMAL(19,4) NOT NULL,

                        active BOOLEAN NOT NULL DEFAULT TRUE,

                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE holdings (
                          id BIGINT AUTO_INCREMENT PRIMARY KEY,

                          portfolio_id BIGINT NOT NULL,

                          stock_id BIGINT NOT NULL,

                          quantity BIGINT NOT NULL,

                          average_price DECIMAL(19,4) NOT NULL,

                          version BIGINT NOT NULL DEFAULT 0,

                          UNIQUE(portfolio_id, stock_id),

                          CONSTRAINT fk_holdings_portfolio
                              FOREIGN KEY(portfolio_id)
                                  REFERENCES portfolios(id),

                          CONSTRAINT fk_holdings_stock
                              FOREIGN KEY(stock_id)
                                  REFERENCES stocks(id)
);

CREATE TABLE orders (

                        id BIGINT AUTO_INCREMENT PRIMARY KEY,

                        user_id BIGINT NOT NULL,

                        stock_id BIGINT NOT NULL,

                        order_type ENUM('MARKET','LIMIT') NOT NULL,

                        side ENUM('BUY','SELL') NOT NULL,

                        status ENUM(
        'PENDING',
        'PARTIALLY_FILLED',
        'FILLED',
        'CANCELLED'
    ) NOT NULL,

                        quantity BIGINT NOT NULL,

                        remaining_quantity BIGINT NOT NULL,

                        price DECIMAL(19,4),

                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                            ON UPDATE CURRENT_TIMESTAMP,

                        version BIGINT NOT NULL DEFAULT 0,

                        CONSTRAINT fk_orders_user
                            FOREIGN KEY(user_id)
                                REFERENCES users(id),

                        CONSTRAINT fk_orders_stock
                            FOREIGN KEY(stock_id)
                                REFERENCES stocks(id)
);

CREATE TABLE trades (

                        id BIGINT AUTO_INCREMENT PRIMARY KEY,

                        buy_order_id BIGINT NOT NULL,

                        sell_order_id BIGINT NOT NULL,

                        stock_id BIGINT NOT NULL,

                        quantity BIGINT NOT NULL,

                        execution_price DECIMAL(19,4) NOT NULL,

                        executed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

                        CONSTRAINT fk_trade_buy
                            FOREIGN KEY(buy_order_id)
                                REFERENCES orders(id),

                        CONSTRAINT fk_trade_sell
                            FOREIGN KEY(sell_order_id)
                                REFERENCES orders(id),

                        CONSTRAINT fk_trade_stock
                            FOREIGN KEY(stock_id)
                                REFERENCES stocks(id)
);

CREATE TABLE transactions (

                              id BIGINT AUTO_INCREMENT PRIMARY KEY,

                              portfolio_id BIGINT NOT NULL,

                              type ENUM(
        'DEPOSIT',
        'WITHDRAW',
        'BUY',
        'SELL'
    ) NOT NULL,

                              amount DECIMAL(19,4) NOT NULL,

                              reference_id BIGINT,

                              created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

                              CONSTRAINT fk_transaction_portfolio
                                  FOREIGN KEY(portfolio_id)
                                      REFERENCES portfolios(id)
);

CREATE TABLE refresh_tokens (

                                id BIGINT AUTO_INCREMENT PRIMARY KEY,

                                user_id BIGINT NOT NULL,

                                token VARCHAR(512) NOT NULL UNIQUE,

                                expiry TIMESTAMP NOT NULL,

                                revoked BOOLEAN DEFAULT FALSE,

                                CONSTRAINT fk_refresh_user
                                    FOREIGN KEY(user_id)
                                        REFERENCES users(id)
);

-- ============================================
-- Indexes
-- ============================================

CREATE INDEX idx_orders_status
    ON orders(status);

CREATE INDEX idx_orders_stock
    ON orders(stock_id);

CREATE INDEX idx_orders_created
    ON orders(created_at);

CREATE INDEX idx_orders_side
    ON orders(side);

CREATE INDEX idx_trade_stock
    ON trades(stock_id);

CREATE INDEX idx_trade_time
    ON trades(executed_at);

CREATE INDEX idx_holding_portfolio
    ON holdings(portfolio_id);

CREATE INDEX idx_transactions_portfolio
    ON transactions(portfolio_id);