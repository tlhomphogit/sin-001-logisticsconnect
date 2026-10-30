# DelayStageServiceApp

## Overview

Tracks the Transit Delay Stage (0–8, e.g., weather shutdowns, customs bottlenecks, and operational disruptions) for logistics hubs and asynchronously publishes stage change notifications to ActiveMQ topic `package-status-topic`.
Part of the [LogisticsConnect](../README.md) project. Independent Maven module, no parent POM.

## REST API Endpoints

### 1. `GET /health`
- **Response**: `200 OK` with plain text `OK`.

### 2. `GET /delay-stage/{hubId}`
- **Description**: Returns the current delay stage for the specified hub ID.
- **Initial Stage Behavior**: In-memory state defaults to stage `0` (normal transit, zero delay) for any unrecorded or newly queried hub ID.
- **Success Response**: `200 OK`
  ```json
  {
    "hubId": "H-501",
    "stage": 0
  }
  ```

### 3. `POST /delay-stage/{hubId}`
- **Description**: Updates the delay stage for the specified hub ID and publishes a change event to ActiveMQ topic `package-status-topic` if the effective stage changes.
- **Request Body**:
  ```json
  {
    "stage": 4
  }
  ```
- **Success Response**: `200 OK`
  ```json
  {
    "hubId": "H-501",
    "stage": 4
  }
  ```
- **Validation Rules & Error Handling**:
  - Acceptable stages are integers in the range `0` to `8` inclusive.
  - Rejects missing `stage`, null values, non-numeric strings, fractional/floating-point values (e.g. `4.5`), and out-of-range values (e.g. `-1` or `9`) with `400 Bad Request`.
  - Rejects malformed JSON with `400 Bad Request`.
  - **State Preservation**: Failed update attempts guarantee that the existing stage in memory is left untouched and no event is published.

## Asynchronous MQ Event Contract

When a valid update changes the effective stage of a hub, `delay-stage-service` publishes a JMS `TextMessage` with JSON payload to topic `package-status-topic`:

```json
{
  "hubId": "H-501",
  "stage": 4,
  "timestamp": 1793362044403
}
```

### Event Fields
- `hubId` (String): Canonical uppercase hub identifier (e.g. `"H-501"`).
- `stage` (int): Valid delay stage between `0` and `8` inclusive.
- `timestamp` (long): Positive epoch milliseconds (`System.currentTimeMillis()`) indicating when the transition occurred.

### Publication Semantics
- **Effective Stage Change**: Events are published only when the stage actually changes. Posting an identical stage preserves existing state and skips duplicate publication.
- **Explicit Failure Handling**: Publication to the broker is attempted before mutating local state. If the broker is unreachable or publication throws an exception, the local state is **not** updated and the request returns `500 Internal Server Error` with details.
- **Concurrency & Thread Safety**: Stage updates and JMS publications are synchronized, ensuring deterministic message ordering and thread safety under concurrent HTTP requests.
- **Shutdown Hooks**: JMS `MessageProducer`, `Session`, and `Connection` are closed during JVM/service shutdown.

## Configuration

| Setting | System Property | Environment Variable | Default |
| :--- | :--- | :--- | :--- |
| Broker URL | `activemq.broker.url` | `ACTIVEMQ_BROKER_URL` | `tcp://localhost:61616` |
| Topic Name | `activemq.topic` | `ACTIVEMQ_TOPIC` | `package-status-topic` |

## Project Structure

```
delay-stage-service/
├── pom.xml
└── src/
    ├── main/java/co/wethinkcode/logisticsconnect/
    │   ├── DelayStageRecord.java
    │   ├── DelayStageServiceApp.java
    │   └── mq/
    │       ├── ActiveMqDelayStagePublisher.java
    │       ├── DelayStageEvent.java
    │       ├── DelayStagePublisher.java
    │       └── MqConfig.java
    └── test/java/co/wethinkcode/logisticsconnect/
        ├── DelayStageServiceAppTest.java
        └── DelayStageServiceMqIT.java
```

## Build

```bash
mvn -f delay-stage-service/pom.xml clean package
```

## Test

### 1. Default Unit Tests (No Broker Required)
Runs JUnit 5 test suite with mock publisher (no broker or Docker needed):

```bash
mvn -f delay-stage-service/pom.xml clean verify
```

### 2. Integration Tests (Requires ActiveMQ Broker)
Runs integration test suite `*IT` against the broker container on an isolated test topic:

```bash
mvn -f delay-stage-service/pom.xml -Pintegration-tests clean verify
```

## Run

```bash
java -jar delay-stage-service/target/delay-stage-service.jar
```

Listens on port `7052`.
