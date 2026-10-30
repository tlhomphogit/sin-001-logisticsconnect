# IngestionServiceApp

## Overview

Parses and cleans `hubs-global.csv`, a legacy export of hubs, sorting centers, and regional districts data. Serves as the first stage in the LogisticsConnect pipeline. Independent Maven module with no parent POM.

Part of the [LogisticsConnect](../README.md) project.

---

## Implemented Behavior & Cleaning Decisions (Stage 1)

The service loads `hubs-global.csv` directly from the classpath at startup, parses all records using OpenCSV, applies deterministic cleaning and normalization rules, resolves duplicates, and serves the cleaned records via REST.

### 1. Cleaning & Normalization Rules

- **Hub ID Normalization:** Leading and trailing whitespace is trimmed, and IDs are converted to canonical uppercase format (`h-501` &rarr; `H-501`, `H-502 ` &rarr; `H-502`).
- **Whitespace Collapsing:** Leading/trailing padding is trimmed, and consecutive internal spaces are collapsed (`\s+` &rarr; `" "`), e.g., `Cape Town  Port` &rarr; `Cape Town Port`.
- **Province Normalization:** Names are title-cased (`gauteng` &rarr; `Gauteng`, `eastern cape` &rarr; `Eastern Cape`). Known regional spelling variants are normalized to the canonical South African standard: `Kwa-Zulu Natal` and `KwaZulu Natal` &rarr; `KwaZulu-Natal`.
- **Preservation of Missing Information:** Per strict data integrity principles (*"Do not invent missing factual data"*), records with missing attributes are preserved with `null` fields rather than discarded or hallucinated. Specifically, `H-508` has an empty province and is preserved as `"province": null`.
- **Boolean & Placeholder Normalization:** Active status is modeled as a nullable `Boolean`:
  - `true` representations: `true`, `1`, `y`, `yes`, `TRUE`, `YES`.
  - `false` representations: `false`, `0`, `n`, `no`, `FALSE`.
  - Placeholders (`unknown`, `N/A`, `n/a`, `TBD`, `-`, `NaN`, blank): represented as JSON `null` to accurately reflect indeterminate operational state.

### 2. Deterministic Deduplication Policy

- **Entity Identity Key:** A physical logistics hub is identified by composite key `LOC:<province>|<sortingCenter>` only when both `province` and `sortingCenter` are present and non-blank.
- **Incomplete Location Fallback Identity:** If either `province` or `sortingCenter` is missing (`null` or blank), the canonical hub ID is used as the fallback identity (`ID:<canonicalHubId>`). This prevents distinct hubs with incomplete location data (e.g. distinct hubs both missing a province or sorting center) from being incorrectly merged.
- **Winner Selection:** When multiple records describe the same physical entity (sharing the same `LOC:` key), the record with the **lowest canonical hub ID** is retained along with its normalized fields.
- **Conflict Reporting:** Duplicates within a group are checked for conflicting field values against the winner. If a duplicate has a conflicting active status (such as `H-510` reporting `false` against winner `H-500`'s `true`), the conflict is formally reported and logged at warning level.
- **Deterministic Output Ordering:** The output list of cleaned records is sorted in ascending order of canonical hub ID.

### 3. Data Audit Figures

Calculated dynamically from the implementation:
- **Total Input Data Rows:** 18 rows
- **Duplicate Records Merged:** 7 records (`H-504`, `H-505`, `H-506`, `H-510`, `h-512`, `H-515`, `H-516`)
- **Discarded / Rejected Records:** 0 records (incomplete records like `H-508` preserved with `null`)
- **Cleaned Unique Records:** 11 records (`H-500`, `H-501`, `H-502`, `H-503`, `H-507`, `H-508`, `H-509`, `H-511`, `H-513`, `H-514`, `H-517`)
- **Reported Conflicts:** 1 conflict (Entity `[gauteng|johannesburg central]`: Retained `H-500` with `active=true`; duplicate `H-510` had conflicting `active=false`).

---

## Integration Contracts

Listens on port `7050`:

| Method | Endpoint | Response Content-Type | Status | Description |
|---|---|---|---|---|
| `GET` | `/health` | `text/plain` | `200 OK` | Sanity check returning `"OK"` |
| `GET` | `/hubs` | `application/json` | `200 OK` | JSON array of cleaned `HubRecord` objects |

### Example Response (`GET /hubs`)

```json
[
  {
    "hubId": "H-500",
    "province": "Gauteng",
    "sortingCenter": "Johannesburg Central",
    "active": true
  },
  {
    "hubId": "H-508",
    "province": null,
    "sortingCenter": "Pretoria North",
    "active": true
  },
  {
    "hubId": "H-511",
    "province": "Limpopo",
    "sortingCenter": "Polokwane Hub",
    "active": null
  }
]
```

---

## Project Structure

```
ingestion-service/
├── pom.xml
├── README.md
└── src/
    ├── main/
    │   ├── java/co/wethinkcode/logisticsconnect/
    │   │   ├── HubRecord.java
    │   │   ├── HubCsvCleaner.java
    │   │   └── IngestionServiceApp.java
    │   └── resources/
    │       └── hubs-global.csv
    └── test/
        └── java/co/wethinkcode/logisticsconnect/
            ├── HubCsvCleanerTest.java
            └── IngestionServiceAppTest.java
```

---

## Build & Test

### Run Automated Tests and Package
```bash
mvn clean verify
```

### Run Executable Service
```bash
java -jar target/ingestion-service.jar
```

### Live Endpoint Verification
```bash
# Health check
curl -i http://localhost:7050/health

# Hubs endpoint
curl -i http://localhost:7050/hubs
```

---

## Remaining Limitations & Gaps (Beyond Stage 1)

1. **Downstream Integration (Stage 2):** `hub-service` (port 7051) is not yet wired to consume `GET :7050/hubs`.
2. **ActiveMQ Decoupling (Stage 3):** Asynchronous message broker integration via `package-status-topic` is scheduled for Stage 3.
3. **AlertBot (Stage 4):** Threshold delay alerting is scheduled for Stage 4.
