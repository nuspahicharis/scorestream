# scorestream

A Spring Boot service that ingests live match events through Kafka, applies them idempotently to match state stored in PostgreSQL, and serves the current state of each match over REST and Server-Sent Events.

I built it to get back up to speed with Java and to work with Spring Boot and Kafka properly. The domain (live match events) was chosen because it forces the interesting problems: per-match ordering, duplicate delivery, bad messages and concurrent updates.

## Contents

- [Overview](#overview)
- [Features](#features)
- [Tech stack](#tech-stack)
- [Getting started](#getting-started)
- [Configuration](#configuration)
- [API](#api)
- [Domain rules](#domain-rules)
- [Data model](#data-model)
- [Design notes](#design-notes)
- [Observability](#observability)
- [Testing](#testing)
- [Project structure](#project-structure)
- [Implementation status](#implementation-status)
- [Known limitations and next steps](#known-limitations-and-next-steps)

## Overview

```
                        ┌───────────────────────── scorestream ─────────────────────────┐
                        │                                                                │
 HTTP client ──► POST /api/events ──► Producer ──► Kafka: match-events (3 partitions)    │
                        │                               │            key = matchId       │
                        │                               ▼                                │
                        │                          Consumer (group: scorestream)         │
                        │                               │   retry + backoff              │
                        │                               ├──────────► match-events.DLT    │
                        │                               ▼                                │
                        │              PostgreSQL (one transaction per event)            │
                        │              match_events (dedupe) + matches (score/status)    │
                        │                               │                                │
 HTTP client ◄── GET /api/matches/{id} ◄────────────────┤                                │
 HTTP client ◄── GET /api/matches/{id}/stream (SSE) ◄───┘                                │
                        └────────────────────────────────────────────────────────────────┘
```

- **Producer**: validates match events received over REST and publishes them to Kafka, keyed by match ID so all events for one match stay ordered on one partition.
- **Consumer**: reads events and applies them to match state inside a single database transaction. Redelivered events are detected and skipped.
- **Query API**: returns the current score, status and timeline of a match, lists matches, and streams live updates.
- **Failure path**: transient failures are retried with exponential backoff. Messages that can never succeed (invalid payloads, rule violations) go straight to a dead-letter topic.

## Features

- REST ingestion with Bean Validation and a consistent `application/problem+json` error format (RFC 9457)
- Idempotent publishing with an optional `Idempotency-Key` header
- Kafka producer with `acks=all`, idempotence enabled and delivery timeouts. The API returns `202` only after the broker has acknowledged the record.
- Per-match ordering through key-based partitioning
- At-least-once consumption with idempotent event application (effectively-once state)
- Exactly one database transaction per event: dedupe, validate against match state, update the score, record the timeline entry
- Retry with exponential backoff, non-retryable exception classification and a dead-letter topic
- Match state persisted in PostgreSQL with Flyway migrations. An in-memory implementation of the same interface is used for fast unit tests.
- Live updates over Server-Sent Events
- Actuator health (liveness and readiness) and Micrometer metrics, including consumer lag and event throughput
- Integration tests against real Kafka and PostgreSQL via Testcontainers
- Docker Compose for local infrastructure, a Dockerfile for the service and a GitHub Actions CI workflow

## Tech stack

- Java 21
- Spring Boot (version pinned in `pom.xml`): Web, Validation, Data JPA, Actuator
- Spring for Apache Kafka
- PostgreSQL 16 and Flyway
- Micrometer
- Maven (wrapper included)
- Docker and Docker Compose (Kafka in KRaft mode, no ZooKeeper)
- JUnit 5, AssertJ, Mockito, Awaitility, Testcontainers

## Getting started

### Prerequisites

- JDK 21+
- Docker (running)

Maven is not required, since the repository ships the Maven wrapper.

### Run locally

```bash
# 1. Start Kafka and PostgreSQL
docker compose up -d

# 2. Start the service
./mvnw spring-boot:run          # Windows PowerShell: .\mvnw.cmd spring-boot:run
```

The service starts on `http://localhost:8080`. On startup it applies the Flyway migrations and creates the `match-events` topic (3 partitions) and its dead-letter topic if they do not exist.

### Try it

```bash
# kick off a match
curl -X POST localhost:8080/api/events -H "Content-Type: application/json" \
  -d '{"matchId":"m-1001","type":"KICK_OFF"}'

# score a goal
curl -X POST localhost:8080/api/events -H "Content-Type: application/json" \
  -H "Idempotency-Key: 6f1d5a7e-0f4c-4e53-9d0e-1b3a9c1f2a10" \
  -d '{"matchId":"m-1001","type":"GOAL","team":"HOME","player":"Player Name","minute":34}'

# read the state
curl localhost:8080/api/matches/m-1001
```

PowerShell users can use `Invoke-RestMethod` with `-Method Post -ContentType application/json -Body ...`.

### Run the whole stack in containers

```bash
docker compose --profile app up --build
```

### Stop and clean up

```bash
docker compose down        # keep data
docker compose down -v     # also remove the PostgreSQL volume
```

## Configuration

Defaults live in `application.yml`. Every property can be overridden with an environment variable (for example `SPRING_KAFKA_BOOTSTRAP_SERVERS=broker:9092`). Local credentials come from a git-ignored `.env`, and `.env.example` shows the expected keys.

| Property | Default | Description |
|---|---|---|
| `spring.kafka.bootstrap-servers` | `localhost:9092` | Kafka broker address |
| `scorestream.topic` | `match-events` | Topic for match events |
| `scorestream.partitions` | `3` | Partitions created for the topic |
| `scorestream.replication-factor` | `1` | Replication factor (use 3 outside local development) |
| `scorestream.consumer.max-attempts` | `4` | Delivery attempts before a message goes to the DLT |
| `scorestream.consumer.backoff-initial-ms` | `500` | First retry delay (doubles on each attempt) |
| `scorestream.publish-timeout-ms` | `3000` | How long `POST /api/events` waits for the broker acknowledgement |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/scorestream` | PostgreSQL connection |
| `spring.jpa.open-in-view` | `false` | No lazy loading in the web layer |
| `server.port` | `8080` | HTTP port |

## API

### Publish an event

```http
POST /api/events
Content-Type: application/json
Idempotency-Key: 6f1d5a7e-0f4c-4e53-9d0e-1b3a9c1f2a10     (optional)

{
  "matchId": "m-1001",
  "type": "GOAL",
  "team": "HOME",
  "player": "Player Name",
  "minute": 34
}
```

Response: `202 Accepted`

```json
{ "eventId": "6f1d5a7e-0f4c-4e53-9d0e-1b3a9c1f2a10", "matchId": "m-1001" }
```

- `Idempotency-Key` becomes the event ID. Sending the same key again publishes nothing new and is applied at most once by the consumer. Without the header the server generates an ID, which makes retries of that request indistinguishable from new events.
- `202` means the broker acknowledged the record. It does **not** mean the match state has been updated yet (see [Design notes](#design-notes)).
- If Kafka does not acknowledge within `publish-timeout-ms`, the response is `503`.

**Event types and required fields**

| Type | `team` | `player` | `minute` |
|---|---|---|---|
| `KICK_OFF` | no | no | no |
| `GOAL` | yes | yes | yes |
| `YELLOW_CARD` | yes | yes | yes |
| `RED_CARD` | yes | yes | yes |
| `SUBSTITUTION` | yes | yes | yes |
| `FULL_TIME` | no | no | no |

`team` is `HOME` or `AWAY`. `minute` is 0 to 130.

### Get match state

```http
GET /api/matches/m-1001
```

```json
{
  "matchId": "m-1001",
  "home": 1,
  "away": 0,
  "status": "LIVE",
  "updatedAt": "2026-11-26T11:34:12Z",
  "events": [
    { "eventId": "6f1d5a7e-0f4c-4e53-9d0e-1b3a9c1f2a10", "type": "GOAL", "team": "HOME", "player": "Player Name", "minute": 34 }
  ]
}
```

`404` if the match is unknown.

### List matches

```http
GET /api/matches?status=LIVE&page=0&size=20
```

Returns match summaries (ID, score, status) ordered by last update, using keyset-friendly ordering. `status` is optional and one of `NOT_STARTED`, `LIVE`, `FINISHED`.

### Stream live updates

```http
GET /api/matches/m-1001/stream
Accept: text/event-stream
```

Sends the current state immediately and then one `match-update` event whenever the match changes. Heartbeat comments keep idle connections open.

### Health and metrics

```http
GET /actuator/health            # liveness and readiness, including database and Kafka
GET /actuator/metrics
GET /actuator/prometheus
```

### Errors

All errors use `application/problem+json`. Validation errors include a list of field violations.

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Validation failed",
  "instance": "/api/events",
  "violations": [ { "field": "minute", "message": "must be less than or equal to 130" } ]
}
```

| Status | When |
|---|---|
| 400 | Invalid body, missing required field for the event type, malformed JSON |
| 404 | Unknown match |
| 405 | Unsupported method |
| 503 | Kafka did not acknowledge in time |
| 500 | Unexpected error (details are logged, never returned) |

## Domain rules

Match state moves through `NOT_STARTED → LIVE → FINISHED`.

- `KICK_OFF` is the only event accepted on a match that does not exist or is `NOT_STARTED`. It moves the match to `LIVE`.
- `GOAL`, `YELLOW_CARD`, `RED_CARD` and `SUBSTITUTION` are accepted only while `LIVE`. A goal increments the score of the given team.
- `FULL_TIME` is accepted only while `LIVE` and moves the match to `FINISHED`.
- A `FINISHED` match is immutable. Further events are rejected.

An event that violates these rules can never succeed, so retrying it would only delay everything behind it. It is classified as non-retryable and goes straight to the dead-letter topic.

## Data model

Flyway owns the schema (`src/main/resources/db/migration`). Hibernate only validates it (`ddl-auto: validate`). Applied migrations are never edited.

```sql
CREATE TABLE matches (
    match_id    VARCHAR(100) PRIMARY KEY,
    home_score  INT          NOT NULL DEFAULT 0 CHECK (home_score >= 0),
    away_score  INT          NOT NULL DEFAULT 0 CHECK (away_score >= 0),
    status      VARCHAR(20)  NOT NULL CHECK (status IN ('NOT_STARTED','LIVE','FINISHED')),
    version     BIGINT       NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ  NOT NULL
);

CREATE TABLE match_events (
    event_id    UUID         PRIMARY KEY,          -- doubles as the idempotency record
    match_id    VARCHAR(100) NOT NULL REFERENCES matches (match_id),
    type        VARCHAR(20)  NOT NULL,
    team        VARCHAR(10),
    player      VARCHAR(100),
    minute      INT,
    kafka_topic     VARCHAR(100) NOT NULL,
    kafka_partition INT          NOT NULL,
    kafka_offset    BIGINT       NOT NULL,
    received_at TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_match_events_match ON match_events (match_id, received_at);
CREATE INDEX idx_matches_status_updated ON matches (status, updated_at DESC);
```

## Design notes

### Ordering

Events are keyed by `matchId`, so all events for a match land on one partition and are consumed in order. Ordering across different matches is not guaranteed and is not needed. Parallelism comes from partitions: at most one consumer per partition in the group, so the partition count caps consumer parallelism. Increasing partitions later changes the key-to-partition mapping, so plan the count up front.

### Delivery and idempotency

- The producer uses `acks=all` with idempotence enabled, so producer retries cannot create duplicates or reorder records within a partition.
- The consumer commits offsets only after the database transaction commits (at-least-once). A crash between commit and offset commit causes a redelivery.
- Applying an event is idempotent: the first statement in the transaction is `INSERT ... ON CONFLICT (event_id) DO NOTHING`. If no row was inserted, the event was already applied and processing stops, so a redelivery never double-counts a goal. At-least-once delivery plus idempotent processing gives effectively-once state.
- The Kafka coordinates (topic, partition, offset) are stored with each event for auditing and replay debugging.

### One transaction per event

Inside a single `@Transactional` method the consumer inserts the event, locks the match row, validates the transition against the domain rules, updates the score and status, and commits. Either the whole event is applied or none of it is. `@Version` optimistic locking protects the match row if anything else ever updates it; the consumer retries on `OptimisticLockingFailureException`.

### Failures

| Failure | Handling |
|---|---|
| Transient (database unavailable, timeout, lock conflict) | Retried in place with exponential backoff up to `max-attempts` |
| Poison message (undeserializable payload) | `ErrorHandlingDeserializer` captures it, then it goes to the DLT without blocking the partition |
| Rule violation or validation failure | Non-retryable, sent straight to `match-events.DLT` |
| Exhausted retries | Sent to `match-events.DLT` |

DLT records carry the original key and payload plus headers with the exception class, message and source offset, so they can be inspected and replayed after a fix.

While a record is being retried, its partition is blocked. That preserves order for that match but delays other matches on the same partition, which is why the retry window is short and bounded.

### What `202 Accepted` means

The API acknowledges that the event is durably in Kafka, not that match state has been updated. State is eventually consistent: a `GET` immediately after a `POST` can return the previous state for a short time. This is the trade-off for decoupling ingestion from processing, and it is intentional.

### Persistence

Match state lives in PostgreSQL, so the service can run several instances in one consumer group and survive restarts. `MatchStateRepository` is an interface with a PostgreSQL implementation (production) and an in-memory implementation (unit tests, fast feedback). The keys that matter for concurrency are the primary key on `event_id` and the row lock on `matches`.

### Live updates

After a successful commit, the consumer publishes an in-process application event that feeds the SSE emitters. Fan-out is in-process, so a client only receives updates processed by the instance it is connected to (see limitations).

## Observability

- **Health**: liveness and readiness probes, including database and Kafka checks.
- **Metrics (Micrometer)**: events published, events applied, duplicates skipped, events sent to the DLT, processing time per event, consumer lag, plus Kafka client, HikariCP and JVM metrics. Exposed at `/actuator/prometheus`.
- **Logging**: structured log lines carry `matchId` and `eventId` so one event can be followed from the HTTP request through Kafka to the database write.

## Testing

```bash
./mvnw test
```

| Level | What is covered |
|---|---|
| Unit | Domain rules and state transitions, event-to-state application, idempotent redelivery, error classification (JUnit 5, AssertJ, Mockito) |
| Web slice | Controllers: validation, status codes, error format, header handling (`@WebMvcTest`) |
| Integration | Real Kafka and PostgreSQL through Testcontainers: publish → consume → state, duplicate delivery, retry then DLT, poison message, restart with committed offsets, concurrent events for several matches (Awaitility for async assertions) |

Integration tests need Docker running.

## Project structure

```
src/main/java/com/haris/scorestream/
├── api/          # REST controllers, request/response DTOs, SSE endpoint, exception handler
├── kafka/        # Producer, consumer, topic config, error handling and DLT
├── domain/       # Match, MatchEvent, event types and the transition rules
├── service/      # Event application logic (one transaction per event)
├── persistence/  # MatchStateRepository, JPA entities, Flyway migrations
└── config/       # Typed configuration properties
```

## Implementation status

Tick these off as they land. The README should describe only what exists on `main`.

**Core**
- [ ] Docker Compose with Kafka (KRaft) and PostgreSQL
- [ ] Event DTO, validation and `POST /api/events`
- [ ] Producer: `acks=all`, idempotence, key = `matchId`, acknowledgement wait
- [ ] Consumer applying events to match state
- [ ] `GET /api/matches/{id}`

**Correctness**
- [ ] Domain rules and state transitions
- [ ] Idempotent application via `event_id` primary key
- [ ] `Idempotency-Key` support on the API
- [ ] Retry with backoff, non-retryable classification, dead-letter topic
- [ ] PostgreSQL persistence with Flyway
- [ ] Problem-details error format

**Extras**
- [ ] `GET /api/matches` listing and filtering
- [ ] SSE live stream
- [ ] Micrometer metrics and consumer lag
- [ ] Dockerfile and `--profile app` compose service
- [ ] GitHub Actions CI (build and test)

**Tests**
- [ ] Unit tests for domain and application logic
- [ ] `@WebMvcTest` controller tests
- [ ] Testcontainers integration tests (Kafka and PostgreSQL)

## Known limitations and next steps

- **Dual write on ingestion**: the API publishes to Kafka directly. This is fine because the API does not write to the database, but any future feature that writes to both should use a transactional outbox (business row plus outbox row in one transaction, then a relay or CDC publishes to Kafka).
- **No authentication or rate limiting** on the API.
- **JSON payloads**: contracts are enforced by validation only. Avro or Protobuf with a schema registry would enforce compatibility between producers and consumers.
- **SSE fan-out is per instance**: with several instances, clients should be fed from a shared channel (a Kafka topic per instance, Redis pub/sub or similar).
- **Single broker locally**: replication factor 1 and no `min.insync.replicas` guarantees. Production would use replication factor 3 and `min.insync.replicas=2`.
- **Late or out-of-order events across partitions** are not reconciled. Per-match ordering relies on a single partition per key. A per-event sequence number would allow rejecting stale updates explicitly.
- **DLT replay** is manual. A small admin endpoint or tool for inspecting and replaying DLT records is the natural next step.
- **Retention and archiving** of `match_events` (time-based partitioning) for long-running deployments.

## About

This is a learning project. I use AI tools as a second pair of eyes for reviews and for exploring unfamiliar APIs, and I make and own the design decisions. The code is written, run and tested by me.
