# TransitServiceApp

## Overview

Calculates estimated arrival windows (ETAs) based on hub identity and current delay stages.
Part of the [LogisticsConnect](../README.md) project. Independent Maven module, no parent POM.

MQ integration note: This service subscribes to the ActiveMQ topic `package-status-topic` via `co.wethinkcode.logisticsconnect.mq.MqConfig` (Stage 3).

## REST API Endpoints

### 1. `GET /health`
- **Response**: `200 OK` with plain text `OK`.

### 2. `GET /transit/{hubId}`
- **Description**: Computes estimated transit duration and arrival window for a package passing through the given hub.
- **Upstream Dependencies**:
  1. `hub-service` (`GET /hubs/{hubId}`) — queries place-name and operational status.
  2. `delay-stage-service` (`GET /delay-stage/{hubId}`) — queries current delay stage.
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
  - `404 Not Found`: Propagated when the queried `hubId` does not exist in `hub-service`.
  - `502 Bad Gateway`: Returned when `hub-service` or `delay-stage-service` cannot be reached, times out, or returns invalid/malformed payloads.

## Transit Calculation Formula

- **Base Transit Hours**: Standard 24 hours baseline duration from dispatch to delivery under normal conditions.
- **Delay Hours**: Computed as `stage * 6` hours (providing 0 to 48 hours additional delay for stages 0–8).
- **Minimum Estimated Hours**: `minEstimatedHours = baseTransitHours + delayHours`.
- **Maximum Estimated Hours**: `maxEstimatedHours = minEstimatedHours + 8` (8-hour operational buffer window).
- **Estimated Arrival Window**: Formatted as `"{minEstimatedHours}-{maxEstimatedHours} hours"`.

## Configuration & Resiliency

- **Hub Service Base URL**: Configurable via system property `hub.service.base.url` or environment variable `HUB_SERVICE_BASE_URL`. Defaults to `http://localhost:7051`.
- **Delay Stage Service Base URL**: Configurable via system property `delay.stage.service.base.url` or environment variable `DELAY_STAGE_SERVICE_BASE_URL`. Defaults to `http://localhost:7052`.
- **Bounded Timeouts**: All upstream REST calls use bounded 5-second connection and read timeouts.

## Project Structure

```
transit-service/
├── pom.xml
└── src/
    ├── main/java/co/wethinkcode/logisticsconnect/
    │   ├── HubRecord.java
    │   ├── TransitResponse.java
    │   ├── TransitServiceApp.java
    │   └── mq/
    │       └── MqConfig.java
    └── test/java/co/wethinkcode/logisticsconnect/
        └── TransitServiceAppTest.java
```

## Build

```bash
mvn -f transit-service/pom.xml clean package
```

## Test

Runs JUnit 5 test suite with mocked Javalin upstream servers (no external network or Docker required):

```bash
mvn -f transit-service/pom.xml test
```

## Run

```bash
java -jar transit-service/target/transit-service.jar
```

Listens on port `7053`.
