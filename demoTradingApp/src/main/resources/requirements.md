# Java Trading Platform

## Tech Stack

Backend
- Java 21
- Spring Boot 3
- Spring Data JPA (Hibernate)
- Spring Security
- MySQL
- Kafka
- Redis (optional)
- Docker
- Maven

Testing
- JUnit 5
- Mockito
- Testcontainers

Monitoring
- Spring Boot Actuator
- Micrometer
- Prometheus
- Grafana

---

# Project Goal

Develop a simplified stock trading platform that supports:

- User registration
- Portfolio management
- Buy orders
- Sell orders
- Order matching
- Trade execution
- Transaction history
- Event-driven architecture using Kafka

This project is intended to showcase backend engineering skills commonly required in investment banks.

---

# Functional Requirements

## Authentication

Users can

- Register
- Login
- JWT Authentication
- Refresh Token

Roles

- USER
- ADMIN

---

## Portfolio

Users can

- Deposit funds
- Withdraw funds
- View available cash
- View holdings

Example

Cash

$20,000

Holdings

AAPL
50 shares

TSLA
20 shares

---

## Stocks

Admin can

Create stocks

Example

AAPL

TSLA

NVDA

MSFT

Fields

- Symbol
- Company Name
- Current Price

---

## Orders

User can

Place Buy Order

Fields

- Stock
- Quantity
- Price

Place Sell Order

Fields

- Stock
- Quantity
- Price

Order Types

- Market
- Limit

Status

- Pending
- Partially Filled
- Filled
- Cancelled

---

## Matching Engine

Automatically matches

Buy Orders

Sell Orders

Rules

Highest buy price first

Lowest sell price first

FIFO if prices are equal

---

## Trade Execution

When an order matches

Create Trade

Update Portfolio

Update Cash

Update Holdings

Publish Kafka Event

---

## Trade History

User can view

Executed Trades

Cancelled Orders

Open Orders

---

## Kafka Events

Topics

order-created

order-matched

trade-executed

portfolio-updated

notification

Consumers

Notification Service

Audit Service

Analytics Service

---

## Notifications

Examples

Order Filled

Order Cancelled

Deposit Successful

---

## Admin Dashboard

View

Users

Orders

Trades

Kafka Health

System Metrics

---

# Non-functional Requirements

Response time

<200ms for CRUD

Trade execution

<500ms

Concurrent Users

1000+

Availability

99.9%

---

# Database

MySQL

Tables

users

roles

stocks

portfolios

holdings

orders

trades

transactions

refresh_tokens

---

# Database Relationships

User

1 -> 1 Portfolio

Portfolio

1 -> Many Holdings

Stock

1 -> Many Orders

Order

Many -> 1 User

Trade

2 Orders

Buyer

Seller

---

# API

Authentication

POST /auth/register

POST /auth/login

POST /auth/refresh

---

Portfolio

GET /portfolio

POST /deposit

POST /withdraw

---

Stocks

GET /stocks

GET /stocks/{symbol}

POST /stocks

---

Orders

POST /orders

GET /orders

GET /orders/{id}

DELETE /orders/{id}

---

Trades

GET /trades

GET /trades/history

---

Kafka Flow

User Places Order

↓

Order Service

↓

Save Order

↓

Publish

order-created

↓

Matching Engine

↓

Find Match

↓

Execute Trade

↓

Publish

trade-executed

↓

Portfolio Service

↓

Update Holdings

↓

Publish

portfolio-updated

↓

Notification Service

---

Concurrency

Use

ExecutorService

for asynchronous matching

Use optimistic locking

@Version

to prevent lost updates

Use

@Transactional

during trade execution

---

Validation

Cannot buy if insufficient cash

Cannot sell if insufficient shares

Quantity > 0

Price > 0

Market orders execute immediately

Limit orders remain pending

---

Testing

Unit Tests

Service Layer

Repository Tests

Integration Tests

Kafka Tests

Controller Tests

Load Tests

---

Future Improvements

Redis cache

WebSocket live prices

Market data service

Options trading

Stop-loss orders

Docker Compose

Kubernetes

CI/CD

Prometheus

Grafana

ElasticSearch

Audit logging

Distributed tracing

Microservice architecture