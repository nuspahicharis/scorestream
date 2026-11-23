# scorestream

A small Spring Boot service that ingests live match events through Kafka and serves the current state of each match over a REST API.

## Overview

```
 HTTP client ──► POST /api/events ──► Kafka topic: match-events ──► Consumer ──► In-memory match state
                                                                                         │
 HTTP client ◄── GET /api/matches/{id} ◄─────────────────────────────────────────────────┘
```

- **Producer**: accepts match events (goal, card, substitution, etc.) over REST and publishes them to Kafka, keyed by match ID so events for one match stay ordered.
- **Consumer**: reads events from the topic and updates the live score and event timeline for each match.
- **Query API**: returns the current score and timeline for a match.

## Tech stack

- Java 21
- Spring Boot 3.x
- Spring for Apache Kafka
- Maven
- Docker Compose (local Kafka)
- JUnit 5, Testcontainers

## Getting started

### Prerequisites

- JDK 21+
- Maven 3.9+
- Docker

### Run locally

```bash
# 1. Start Kafka
docker compose up -d

# 2. Start the service
./mvnw spring-boot:run
```

The service starts on `http://localhost:8080`.

### Configuration

| Property | Default | Description |
|---|---|---|
| `spring.kafka.bootstrap-servers` | `localhost:9092` | Kafka broker address |
| `scorestream.topic` | `match-events` | Topic for match events |
| `scorestream.partitions` | `3` | Partitions created for the topic |
| `server.port` | `8080` | HTTP port |

Override with environment variables, e.g. `SPRING_KAFKA_BOOTSTRAP_SERVERS=broker:9092`.

## API

### Publish an event

```http
POST /api/events
Content-Type: application/json

{
  "matchId": "m-1001",
  "type": "GOAL",
  "team": "HOME",
  "player": "Player Name",
  "minute": 34
}
```

Response: `202 Accepted`

Event types: `GOAL`, `YELLOW_CARD`, `RED_CARD`, `SUBSTITUTION`, `KICK_OFF`, `FULL_TIME`.

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
  "events": [
    { "type": "GOAL", "team": "HOME", "player": "Player Name", "minute": 34 }
  ]
}
```

### Health

```http
GET /actuator/health
```

## Design notes

- **Ordering**: events are keyed by `matchId`, so all events for a match land on one partition and are processed in order.
- **Delivery**: the producer uses `acks=all` with idempotence enabled. The consumer commits offsets after processing (at-least-once), and applying an event is idempotent via an event ID, so redelivery doesn't double-count a goal.
- **Failures**: messages that fail processing are retried with backoff, then sent to a dead-letter topic (`match-events.DLT`).
- **State**: match state is held in memory for simplicity. Swapping in PostgreSQL or Redis only requires a new implementation of `MatchStateRepository`.

## Project structure

```
src/main/java/com/haris/scorestream/
├── api/          # REST controllers and DTOs
├── kafka/        # Producer, consumer, topic and error-handling config
├── domain/       # Match, MatchEvent, event types
└── service/      # Match state logic
```

## Testing

```bash
./mvnw test
```

Integration tests start a real Kafka broker with Testcontainers, so Docker must be running.

## Roadmap

- Persist match state in PostgreSQL
- Stream live updates to clients over SSE or WebSocket
- Add metrics (consumer lag, events per second) via Micrometer
- Schema validation with Avro and a schema registry


