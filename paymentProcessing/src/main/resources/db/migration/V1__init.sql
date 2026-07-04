CREATE TABLE payments (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  sender_account VARCHAR(255) NOT NULL ,
  receiver_account VARCHAR(255) NOT NULL ,
  amount DECIMAL(19,2) NOT NULL,
  currency VARCHAR(10) NOT NULL,
  status VARCHAR(20) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);