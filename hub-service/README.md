# HubServiceApp

## Overview

Serves provinces and sorting centers (place-name source of truth) for LogisticsConnect.
Part of the [LogisticsConnect](../README.md) project. Independent Maven module, no parent POM.

`hub-service` consumes cleaned hub data from `ingestion-service` (`GET /hubs`) and provides individual hub lookup via `GET /hubs/{hubId}`.

## REST API Endpoints

### 1. `GET /health`
- **Response**: `200 OK` with plain text `OK`.

### 2. `GET /hubs/{hubId}`
- **Description**: Returns normalized details for a single logistics hub by its canonical hub ID (e.g., `H-501`).
- **Success Response**: `200 OK`
  ```json
  {
    "hubId": "H-501",
    "province": "Western Cape",
    "sortingCenter": "Cape Town Port",
    "active": true
  }
  ```
- **Preservation of Nulls**: If upstream ingestion contains missing attributes (such as `province` for `H-508`), they are preserved as JSON `null` rather than omitted or replaced with synthetic defaults:
  ```json
  {
    "hubId": "H-508",
    "province": null,
    "sortingCenter": "Pretoria North",
    "active": true
  }
  ```
- **Error Responses**:
  - `404 Not Found`: If the `hubId` does not exist in the cleaned dataset:
    ```json
    {"error": "Hub not found: H-999"}
    ```
  - `503 Service Unavailable`: If `ingestion-service` cannot be reached or fails to return the hub dataset, preventing empty responses:
    ```json
    {"error": "Failed to load hub data from ingestion service"}
    ```

## Configuration & Resiliency

- **Upstream Ingestion Base URL**: Configurable via system property `ingestion.base.url` or environment variable `INGESTION_BASE_URL`. Defaults to `http://localhost:7050`.
- **Bounded Timeouts**: All upstream HTTP requests use a bounded 5-second connection and read timeout.
- **Preloading & On-Demand Fallback**:
  - Preloads the hub cache during service startup if `ingestion-service` is available.
  - If the cache is empty or initial connection fails during startup, attempts an on-demand refresh when a client requests `GET /hubs/{hubId}`.
  - An ingestion failure never silently turns into an empty dataset.

## Project Structure

```
hub-service/
├── pom.xml
└── src/
    ├── main/java/co/wethinkcode/logisticsconnect/
    │   ├── HubRecord.java
    │   └── HubServiceApp.java
    └── test/java/co/wethinkcode/logisticsconnect/
        └── HubServiceAppTest.java
```

## Build

```bash
mvn -f hub-service/pom.xml clean package
```

## Test

Runs JUnit 5 test suite with mocked Javalin upstream servers (no external network or Docker required):

```bash
mvn -f hub-service/pom.xml test
```

## Run

```bash
java -jar hub-service/target/hub-service.jar
```

Listens on port `7051`.
