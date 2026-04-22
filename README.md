# 📖 Core Concept

  분산 시스템의 데이터 흐름을 이해하기 위한 MQ 핵심 개념 및 아키텍처 학습 레포지토리


---

### 📝 Repository Overview
이 레포지토리는 **ureka-mq-org** 스터디에서 진행하는 이론적 고찰을 기록합니다. 다양한 환경에서 어떤 메시지 브로커가 적합한지 판단할 수 있는 기준을 정립하고, 특히 **RabbitMQ**와 **Kafka**의 내부 동작 방식을 심도 있게 분석합니다.

---

### 🔍 Main Concept Analysis

#### 1. Various MQ Exploration
다양한 저장소 기반의 메시징 구현 방식과 특징을 분석합니다.
* **PGMQ (Postgres MQ)**: RDBMS(Postgres) 기반의 메시지 큐 구현 및 트랜잭션 보장 분석
* **Redis MQ**: List, Pub/Sub, Stream 등 Redis 자료구조를 활용한 비동기 처리 이해

#### 2. Deep Dive (Focus)
현재 가장 널리 쓰이는 두 브로커의 핵심 개념을 정리합니다.
* **RabbitMQ**: AMQP 프로토콜, Exchange 라우팅 전략, 메시지 보존(Durability) 및 신뢰성 보장 원리
* **Apache Kafka**: 분산 이벤트 스트리밍 아키텍처, Topic/Partition 구조, Log-structured Storage 메커니즘

<br/>


---

### 🛠 Tech Stack
<div align="left">
  <img src="https://img.shields.io/badge/RabbitMQ-FF6600?style=flat-square&logo=rabbitmq&logoColor=white">
  <img src="https://img.shields.io/badge/Apache%20Kafka-231F20?style=flat-square&logo=apachekafka&logoColor=white">
  <img src="https://img.shields.io/badge/PostgreSQL-4169E1?style=flat-square&logo=postgresql&logoColor=white">
  <img src="https://img.shields.io/badge/Redis-DC382D?style=flat-square&logo=redis&logoColor=white">
</div>
