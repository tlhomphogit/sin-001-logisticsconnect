# TransitServiceApp

## Overview

Calculates estimated arrival windows (ETAs) based on hub identity and current delay stages.
Part of the [LogisticsConnect](../README.md) project. Independent Maven module, no parent POM.

In Stage 3, `transit-service` is decoupled from `delay-stage-service`: it subscribes to ActiveMQ topic `package-status-topic` via `TransitDelaySubscriber` and maintains a thread-safe local cache (`TransitDelayCache`), eliminating runtime HTTP RPC to `delay-stage-service`.

## REST API Endpoints

### 1. `GET /health`
- **Response**: `200 OK` with plain text `OK`.

### 2. `GET /transit/{hubId}`
- **Description**: Computes estimated transit duration and arrival window for a package passing through the given hub.
- **Upstream Dependencies**:
  1. `hub-service` (`GET /hubs/{hubId}`) — queries place-name and operational status.
  2. `TransitDelayCache` (local in-memory cache) — queried for the latest delay stage received from ActiveMQ topic `package-status-topic`.
- **Success Response**: `200 OK`
  ```json
  {
    "hubId": "H-501",
    "province": "Western Cape",
    "sortingCenter": "Cape Town Port",
    "active": true,
    "stage": 4,
    "baseTransitHours": 24,
    "delayHours": 24,
    "minEstimatedHours": 48,
    "maxEstimatedHours": 56,
    "estimatedArrivalWindow": "48-56 hours"
  }
  ```
- **Preservation of Null Attributes**:
  If a hub has missing fields (such as `province: null` for `H-508`), they are preserved directly in the transit response without hallucinating values:
  ```json
  {
    "hubId": "H-508",
    "province": null,
    "sortingCenter": "Pretoria North",
    "active": true,
    "stage": 0,
    "baseTransitHours": 24,
    "delayHours": 0,
    "minEstimatedHours": 24,
    "maxEstimatedHours": 32,
    "estimatedArrivalWindow": "24-32 hours"
  }
  ```
- **Error Responses**:
  - `404 Not Found`: Returned when the queried `hubId` does not exist in `hub-service`.
  - `502 Bad Gateway`: Returned when upstream `hub-service` cannot be reached or returns an error.
  - `503 Service Unavailable`: Returned when the queried hub exists in `hub-service`, but no delay stage event has been received in the local cache:
    ```json
    {
      "error": "Delay stage unavailable",
      "message": "No delay stage event received for hub: H-501",
      "hubId": "H-501"
    }
    ```

## Transit Calculation Formula

- **Base Transit Hours**: Standard 24 hours baseline duration from dispatch to delivery under normal conditions.
- **Delay Hours**: Computed as `stage * 6` hours (providing 0 to 48 hours additional delay for stages 0–8).
- **Minimum Estimated Hours**: `minEstimatedHours = baseTransitHours + delayHours`.
- **Maximum Estimated Hours**: `maxEstimatedHours = minEstimatedHours + 8` (8-hour operational buffer window).
- **Estimated Arrival Window**: Formatted as `"{minEstimatedHours}-{maxEstimatedHours} hours"`.

## Asynchronous Decoupling & Caching Architecture

### 1. Event Ingestion & Validation
`TransitDelaySubscriber` listens for JMS `TextMessage` updates on `package-status-topic`. Each message must satisfy:
- Valid JSON object with `hubId`, `stage`, and `timestamp`.
- Non-blank string `hubId` (canonicalized to uppercase).
- Integer `stage` between `0` and `8` inclusive.
- Positive `timestamp` (numeric epoch milliseconds or ISO-8601 string).
- Malformed payloads, invalid stages, or missing fields are logged and dropped without interrupting subsequent message processing.

### 2. Ordering & Deduplication Policy
Events are applied atomically to `TransitDelayCache` using `cache.compute()`:
- **Newer Event**: If `incoming.timestamp > existing.timestamp`, the stage is updated. A newer event may legitimately decrease the delay stage (e.g., weather clearing from stage 4 down to stage 1).
- **Stale or Duplicate Event**: If `incoming.timestamp <= existing.timestamp`, the event is discarded as outdated or duplicate, preserving existing state.

### 3. Startup, Missing-Event, and Restart Behavior
- **Recommended Startup Order**:
  1. ActiveMQ Broker (`common/docker-compose.yml`)
  2. `ingestion-service` (:7050)
  3. `hub-service` (:7051)
  4. `transit-service` (:7053) — subscriber begins listening to topic
  5. `delay-stage-service` (:7052) — updates publish to active subscriber
- **Missing-Event Behavior**: If a hub has not yet received any event from the broker, `GET /transit/{hubId}` returns `503 Service Unavailable`, preventing the emission of arbitrary or ungrounded ETAs.
- **In-Memory State Limitations**: The delay cache is non-persistent in this stage. If `transit-service` restarts, all previously cached stages in memory are cleared until new events are broadcast on the ActiveMQ topic.

## Configuration

| Setting | System Property | Environment Variable | Default |
| :--- | :--- | :--- | :--- |
| Hub Service Base URL | `hub.service.base.url` | `HUB_SERVICE_BASE_URL` | `http://localhost:7051` |
| ActiveMQ Broker URL | `activemq.broker.url` | `ACTIVEMQ_BROKER_URL` | `tcp://localhost:61616` |
| ActiveMQ Topic Name | `activemq.topic` | `ACTIVEMQ_TOPIC` | `package-status-topic` |

## Project Structure

```
transit-service/
├── pom.xml
└── src/
    ├── main/java/co/wethinkcode/logisticsconnect/
    │   ├── HubRecord.java
    │   ├── TransitDelayCache.java
    │   ├── TransitResponse.java
    │   ├── TransitServiceApp.java
    │   └── mq/
    │       ├── DelayStageEvent.java
    │       ├── MqConfig.java
    │       └── TransitDelaySubscriber.java
    └── test/java/co/wethinkcode/logisticsconnect/
        ├── TransitServiceAppTest.java
        └── TransitServiceMqIT.java
```

## Build

```bash
mvn -f transit-service/pom.xml clean package
```

## Test

### 1. Default Unit Tests (No Broker Required)
Runs JUnit 5 test suite with preloaded/mock cache (no broker or Docker needed):

```bash
mvn -f transit-service/pom.xml clean verify
```

### 2. Integration Tests (Requires ActiveMQ Broker)
Runs integration test suite `*IT` validating live ActiveMQ message delivery, parsing, cache updates, and ETA calculation on an isolated topic:

```bash
mvn -f transit-service/pom.xml -Pintegration-tests clean verify
```

## Run

```bash
java -jar transit-service/target/transit-service.jar
```

Listens on port `7053`.
