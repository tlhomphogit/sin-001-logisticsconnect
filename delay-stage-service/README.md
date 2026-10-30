# DelayStageServiceApp

## Overview

Tracks the Transit Delay Stage (0–8, e.g., weather shutdowns, customs bottlenecks, and operational disruptions) for logistics hubs.
Part of the [LogisticsConnect](../README.md) project. Independent Maven module, no parent POM.

MQ integration note: This service is configured to publish stage change notifications to ActiveMQ topic `package-status-topic` via `co.wethinkcode.logisticsconnect.mq.MqConfig` (Stage 3).

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
- **Description**: Updates the delay stage for the specified hub ID.
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
  - **State Preservation**: Failed update attempts guarantee that the existing stage in memory is left untouched.

## Project Structure

```
delay-stage-service/
├── pom.xml
└── src/
    ├── main/java/co/wethinkcode/logisticsconnect/
    │   ├── DelayStageRecord.java
    │   ├── DelayStageServiceApp.java
    │   └── mq/
    │       └── MqConfig.java
    └── test/java/co/wethinkcode/logisticsconnect/
        └── DelayStageServiceAppTest.java
```

## Build

```bash
mvn -f delay-stage-service/pom.xml clean package
```

## Test

Runs JUnit 5 test suite validating defaults, boundary conditions, fractional rejection, and error handling:

```bash
mvn -f delay-stage-service/pom.xml test
```

## Run

```bash
java -jar delay-stage-service/target/delay-stage-service.jar
```

Listens on port `7052`.
