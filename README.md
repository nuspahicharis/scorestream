# Betting Service

A small backend service for placing and managing bets, built with **Java 21, Spring Boot, PostgreSQL and Kafka**. Placing a bet publishes an event to Kafka, and a consumer uses it to maintain per-selection statistics. The focus is on the parts that are easy to get wrong: idempotent requests, safe retries, concurrent updates, and what happens when a dependency is down.

It is a hands-on project: the goal was to build a realistic slice of a betting backend and to be able to explain and defend every design decision in it.

## Contents

- [What it does](#what-it-does)
- [Quick start](#quick-start)
- [API](#api)
- [Architecture](#architecture)
- [Design decisions](#design-decisions)
- [Behaviours I tested by hand](#behaviours-i-tested-by-hand)
- [Known limitations and what I would do next](#known-limitations-and-what-i-would-do-next)
- [Project structure](#project-structure)
- [Configuration](#configuration)

## What it does

- **Place a bet** (`POST /bets`) with an `Idempotency-Key` header. Retrying the same request returns the original bet instead of creating a second one.
- **Get, cancel and settle** a bet. A bet moves through a small state machine, and invalid transitions are rejected.
- **Publish a `BetPlaced` event** to Kafka after a new bet is saved.
- **Consume that event** and update `selection_stats` (bet count and total stake per selection), exactly once in effect even though Kafka delivers at least once.
- **Retry failed messages** with exponential backoff and park permanently failing ones in a dead-letter topic.

## Quick start

Requirements: Docker, and Java 21 only if you want to run the app outside Docker.

```bash
cp .env.example .env        # defaults work as they are
```

### Option A: everything in Docker

```bash
docker compose --profile app up -d --build
docker compose logs -f app  # wait for "Started BettingApplication"
```

### Option B: infrastructure in Docker, app from your IDE or Maven

```bash
docker compose up -d        # Postgres + Kafka only
cd betting
./mvnw spring-boot:run      # .\mvnw.cmd spring-boot:run on Windows
```

### Try it

```bash
curl -i -X POST http://localhost:8080/bets \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-1" \
  -d '{"userId":"user-1","selectionId":"match-1","stake":10.00,"odds":2.00}'
```

PowerShell:

```powershell
$body = '{"userId":"user-1","selectionId":"match-1","stake":10.00,"odds":2.00}'
Invoke-WebRequest -Method Post -Uri http://localhost:8080/bets `
  -Headers @{ "Idempotency-Key" = "demo-1" } -ContentType "application/json" `
  -Body $body -SkipHttpErrorCheck | Select-Object StatusCode, Content
```

The first call returns `201`. Sending the exact same request again returns the same bet with `200` and publishes nothing new.

See the effect of the consumer:

```bash
docker exec -it betting-postgres psql -U betting -d betting -c "SELECT * FROM selection_stats;"
```

Stop everything (add `-v` to also delete the database volume):

```bash
docker compose --profile app down
```

### Tests

```bash
cd betting
./mvnw test
```

The unit tests use Mockito and need nothing running. The default `BettingApplicationTests` loads the full Spring context, so it needs `docker compose up -d` first.

## API

| Method | Path | Description |
|---|---|---|
| `POST` | `/bets` | Place a bet. Requires an `Idempotency-Key` header. `201` for a new bet, `200` when replaying a known key. |

Get, cancel and settle endpoints are exposed by `BetController`. Errors are returned in one consistent shape by a global exception handler (`ApiError`), including a list of field violations for validation failures.

## Architecture

```
            POST /bets
 client ───────────────▶  BetController
                              │
                              ▼
                          BetService ──────── saves ──────▶ PostgreSQL (bets)
                              │
                     after the save succeeds
                              │
                              ▼
                       BetEventPublisher ── key = bet id ──▶ Kafka: bets.placed.v1 (3 partitions)
                                                                  │
                                                                  ▼
                                                          BetPlacedListener
                                                                  │
                                                                  ▼
                                                      SelectionStatsService
                                       ┌──────────── one transaction ────────────┐
                                       │ claim event id in processed_events      │
                                       │ upsert selection_stats                  │
                                       └──────────────────────────────────────────┘

        failed records ─▶ retry with backoff ─▶ Kafka: bets.placed.v1.DLT
```

## Design decisions

**Idempotent placement.** The `Idempotency-Key` is stored with the bet under a unique constraint. A repeated key returns the existing bet. If two identical requests race, the unique constraint lets exactly one insert win. The loser catches the constraint violation, looks up the winner's row and returns it as a replay.

**`place` is deliberately not `@Transactional`.** The save runs in its own short transaction (`saveAndFlush`). That keeps the constraint-violation handling clean, because in PostgreSQL a failed statement aborts the whole surrounding transaction, and it guarantees the Kafka publish happens only after the bet is committed, never for a bet that was rolled back.

**Money uses `BigDecimal`.** Potential payout is `stake × odds` rounded to 2 decimals with `HALF_UP`, stored as `NUMERIC`. No floating point anywhere near money.

**Optimistic locking on bets.** Concurrent cancel and settle calls cannot silently overwrite each other. The loser gets a conflict error.

**Event identity and ordering.** The Kafka record key is the bet id, so all events for one bet land on the same partition in order. The event id is derived deterministically from the bet id, so if the same bet is ever published twice the consumer sees the same id and recognises the duplicate.

**Producer settings.** `acks=all` with idempotence enabled, so a retry inside the producer cannot create duplicates or lose acknowledged data. `max.block.ms` is 3s so a Kafka outage cannot hang a request thread for a minute, and `delivery.timeout.ms` is 60s, which must be at least `request.timeout.ms + linger.ms`.

**Idempotent consumer, because delivery is at-least-once.** The consumer inserts the event id into `processed_events` with `INSERT ... ON CONFLICT DO NOTHING` and checks the row count. Zero rows means it was already processed, so it skips. Otherwise, in the same transaction, it applies the update. If the update fails, the claim is rolled back too, so the event can be retried.

**Atomic upsert in SQL instead of read-modify-write.** `selection_stats` is updated with `INSERT ... ON CONFLICT (selection_id) DO UPDATE SET bet_count = bet_count + 1, total_stake = total_stake + ...`. Reading an entity, incrementing it and saving it would lose updates under concurrency and race on the first insert for a new selection. The database does the arithmetic atomically instead.

**Retry and dead-letter.** Transient failures are retried 3 times with exponential backoff (0.5s, 1s, 2s). Permanent failures such as a malformed payload skip the retries. After that the record is published to `<topic>.DLT` on the same partition, with the error in headers, and the consumer carries on. One bad message cannot block a partition or be lost. The dead-letter destination is set explicitly rather than relying on a library default.

**Flyway migrations, `ddl-auto: validate`.** The schema is versioned SQL. Hibernate only checks that the entities match it.

**Configuration through environment variables.** Defaults work out of the box. Credentials live in `.env`, which is gitignored; `.env.example` documents the variables.

## Behaviours I tested by hand

| Scenario | Result |
|---|---|
| Same `Idempotency-Key` sent twice | Second call returns the same bet, nothing is published again |
| Same event delivered twice to the consumer | Counted once (`processed_events` claim) |
| Kafka stopped, bet placed | Request still returns `201` after about 3s and logs the publish failure (see limitations) |
| Kafka restarted, new bet placed | Producer and consumer reconnect without restarting the app |
| Malformed record sent to the topic | Parked in `bets.placed.v1.DLT` with exception headers, and the next valid bet is processed normally |
| `docker compose down -v`, then up | Flyway applies the migrations and both topics are created from nothing |

## Known limitations and what I would do next

- **Dual write.** Saving the bet and publishing the event are two separate steps, so if Kafka is down at the moment of the request, the bet exists but its event is lost. I reproduced this and left it visible on purpose. The proper fix is the **transactional outbox**: write the event to an `outbox` table in the same transaction as the bet, and have a relay publish from it. That makes the consumer-side idempotency already in place do its job on redelivery.
- **Topics are created by the application** (`NewTopic` beans), which is convenient locally. In production I would create them through infrastructure as code with a replication factor of 3 and `min.insync.replicas=2`, and disable auto-creation on the brokers.
- **Single Kafka broker, replication factor 1.** Fine for development, not for durability.
- **Dead-letter topic is write-only.** There is no tooling to inspect or replay it, and no alerting on its size.
- **Tests.** The business logic is covered by unit tests. There are no controller tests and no integration tests against real Postgres and Kafka; Testcontainers would be the next step.
- **Smoke test.** A small Python script that exercises the running stack end to end.
- **Not included:** authentication, pagination, metrics and tracing.

## Project structure

```
.
├── docker-compose.yml          Postgres, Kafka and (profile "app") the service
├── .env.example                documented environment variables
└── betting/
    ├── Dockerfile              multi-stage build (JDK to build, JRE to run)
    ├── pom.xml
    └── src
        ├── main
        │   ├── java/com/haris/betting
        │   │   ├── bet/        entity, repository, service, controller, DTOs, exceptions
        │   │   ├── common/     error response and global exception handler
        │   │   └── kafka/      event, publisher, listener, stats service, Kafka config
        │   └── resources
        │       ├── application.yml
        │       └── db/migration/   V1 bets, V2 processed_events and selection_stats
        └── test/java/...       unit tests for the entity, service and listener
```

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `DB_HOST` | `localhost` | PostgreSQL host (`postgres` inside compose) |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `betting` | Database name |
| `DB_USER` | `betting` | Database user |
| `DB_PASSWORD` | `betting` | Database password |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka address (`kafka:29092` inside compose) |
| `BET_PLACED_TOPIC` | `bets.placed.v1` | Topic for bet-placed events |

Kafka exposes two listeners: `localhost:9092` for tools and apps on your machine, and `kafka:29092` for containers on the compose network, so the same broker works from both sides.