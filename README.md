# LogisticsConnect

## Overview

Supply chain parcel delivery hub and transit delay tracking.

Domain entities: hubs, sorting centers, regional districts.

Application and domain classes use `co.wethinkcode.logisticsconnect`; messaging
classes use its `mq` subpackage. LogisticsConnect is built as a small set of
independent services, following a growth path from simple data
cleanup through synchronous REST calls to asynchronous MQ decoupling and alerting:

1. clean a messy legacy CSV export (`hubs-global.csv`) — handled by **IngestionServiceApp**
2. serve it up and act on it, via three REST services calling each other directly
   over HTTP
3. decouple the relevant services with an ActiveMQ topic (`package-status-topic`) instead of
   direct calls — shared broker setup lives in [`common/`](common)
4. raise the alarm on failure — handled by **AlertBotApp**

| Service | Folder | Port | Role |
|---|---|---|---|
| IngestionServiceApp | [`ingestion-service/`](ingestion-service) | 7050 | Parses and cleans `hubs-global.csv` |
| HubServiceApp | [`hub-service/`](hub-service) | 7051 | Serves provinces and sorting centers (place-name source of truth). |
| DelayStageServiceApp | [`delay-stage-service/`](delay-stage-service) | 7052 | Tracks the Transit Delay Stage (0-8, e.g. weather shutdowns). |
| TransitServiceApp | [`transit-service/`](transit-service) | 7053 | Calculates estimated arrival windows based on hub and delay stage. |
| AlertBotApp | [`alertbot/`](alertbot) | 7054 | Optional scaffold for simulated delay notifications; not implemented. |

Shared ActiveMQ broker setup and MQ configuration notes live in [`common/`](common).
The broker uses port 61616 for OpenWire and port 8161 for its web console.
Delay updates use the `package-status-topic` topic.

## Implementation status

Required stages **1–3 are implemented and locally verified**. Stage 4, AlertBot,
is an optional scaffold and is not implemented.

| Stage | Status | Implemented behavior |
|---|---|---|
| 1. CSV ingestion | Complete | Classpath CSV loading, normalization, deterministic deduplication and `GET /hubs` |
| 2. REST integration | Complete | Hub lookup, validated delay-stage updates and ETA calculation; the stage-2 delay REST lookup was replaced in stage 3 |
| 3. MQ decoupling | Complete | Delay changes publish to `package-status-topic`; transit consumes events into a local cache |
| 4. AlertBot | Optional, not implemented | The supplied scaffold remains available for future work |

The supplied CSV is unchanged: **18 data rows produce 11 cleaned hubs**, with
7 duplicate records merged and no rows rejected. Missing facts remain `null`;
for example, H-508 retains a null province and sorting center `Pretoria North`.
See [ingestion-service/README.md](ingestion-service/README.md) for cleaning rules.

Current runtime integration:

- Hub service loads cleaned hub records from ingestion over REST.
- Transit service retrieves hub details from hub service over REST.
- Delay-stage service publishes stage changes through ActiveMQ.
- Transit service calculates ETAs from its local event cache, without calling
  delay-stage service over HTTP.

The earlier independent Spring prototype is preserved in Git history. The
active implementation and build instructions use the services in this fork.

## Project brief

The upstream stage descriptions and estimates below are retained for reference.
Current completion status is recorded above.

Implement the four stages below, in order — each one builds on the last, and the
later stages assume the earlier ones work. Stage 1-3 are required; stage 4 is a
stretch goal if you have time left.

| Stage | Required? | What "done" looks like | Rough effort |
|---|---|---|---|
| 1. Clean `hubs-global.csv` | Required | IngestionServiceApp exposes the cleaned records via REST (see [Integration contracts](#integration-contracts)); every issue category in [ingestion-service/README.md](ingestion-service/README.md) is handled | ~1-1.5h |
| 2. Wire up the REST services | Required | hub-service, delay-stage-service, and transit-service each expose real domain endpoints (not just `/health`) and call each other synchronously per the contracts below; `transit-service` can return an ETA for a hub | ~1.5-2h |
| 3. Decouple with the MQ topic | Required | `delay-stage-service` publishes to `package-status-topic` on stage change; `transit-service` subscribes instead of calling `delay-stage-service` directly; broker runs via `common/docker-compose.yml` | ~1h |
| 4. AlertBot | Stretch | `alertbot` subscribes to `package-status-topic` and simulates posting an alert when a hub's delay stage crosses a threshold you choose | ~30-45m |

You don't need to match any exact field names, endpoint paths, or message shapes —
the ones below are illustrative. Favor a working, readable implementation over a
gold-plated one; partial completion of stage 3 or 4 is fine if 1-2 are solid.

## Integration contracts

Two kinds of integration point exist in this repo: synchronous REST calls (stage 2)
and the asynchronous MQ topic (stage 3). Field/endpoint names below are illustrative
— reasonable variations are fine as long as the shape (who calls whom, with what
kind of payload) is preserved.

### REST (stage 2)

| Caller | Callee | Example | Purpose |
|---|---|---|---|
| hub-service | ingestion-service | `GET :7050/hubs` → JSON array of cleaned hub records | hub-service loads its place-name data from the cleaned CSV output instead of re-parsing it itself |
| transit-service | hub-service | `GET :7051/hubs/{hubId}` → hub/sorting-center details | transit-service needs hub location data to calculate an ETA |
| transit-service | delay-stage-service | `GET :7052/delay-stage/{hubId}` → `{ "hubId": "H-501", "stage": 3 }` | transit-service needs the current delay stage to calculate an ETA — **this call is replaced by the MQ subscription in stage 3** |
| (client) | delay-stage-service | `POST :7052/delay-stage/{hubId}` with a body like `{ "stage": 3 }` | the stage/state-change endpoint referenced in [common/README.md](common/README.md) — this is also where the stage-3 MQ publish happens |

### MQ (stage 3) — topic `package-status-topic`

Already documented in detail in [common/README.md](common/README.md): broker URL and
topic name come from the shared `co.wethinkcode.logisticsconnect.mq.MqConfig` class,
duplicated into each participating service.

- **Producer:** `delay-stage-service`, on its stage/state-change endpoint above.
- **Consumers:** `transit-service` (replacing its direct REST call to
  delay-stage-service) and, for the stretch goal, `alertbot`.
- **Example message shape:** `{ "hubId": "H-501", "stage": 5, "timestamp": "2026-07-18T10:15:00Z" }`

The implemented publisher emits a positive numeric epoch-millisecond timestamp.
The subscriber also accepts ISO-8601 timestamp strings as in the illustrative
contract above. It validates message type, required fields, stage range and
timestamp before updating its cache.

## Project structure

Each service is an independent Maven project:

```text
sin-001-logisticsconnect/
├── README.md
├── RUBRIC.md
├── Makefile                # Build, test and lifecycle shortcuts
├── scripts/dev.sh          # Linux/WSL process launcher
├── ingestion-service/       # 7050: CSV cleaning and cleaned records
├── hub-service/             # 7051: hub lookup
├── delay-stage-service/     # 7052: stage API and JMS publisher
├── transit-service/         # 7053: JMS subscriber, cache and ETA API
├── common/                 # ActiveMQ Compose file and integration notes
└── alertbot/               # 7054: optional scaffold
```

## Build

Requirements: Linux/WSL with Bash 4 or later, GNU Make, JDK 17 or later,
Maven 3.8 or later, and `curl`. Docker with Docker Compose is needed when the
launcher starts the broker. Maven source/target remains Java 17.

The launcher uses `/proc`, `flock`, `setsid`, `nohup` and `timeout`, which are
standard tools in Ubuntu/WSL. There is no parent POM or Maven wrapper.

Run commands from the repository root. Build and run all default tests:

```bash
make test
```

To package the services without running tests:

```bash
make build
```

Both commands process the four independent service modules. Default tests use
injected dependencies or local test servers and do not need separately running
services, Docker or an ActiveMQ broker.

The equivalent direct Maven commands remain available:

```bash
mvn -f ingestion-service/pom.xml clean verify
mvn -f hub-service/pom.xml clean verify
mvn -f delay-stage-service/pom.xml clean verify
mvn -f transit-service/pom.xml clean verify
```

## Run

### Start everything from one terminal

Stop any manually started instances of the four application services first.
Ports 7050–7053 must be free or belong to services already tracked by this launcher.
Then run:

```bash
make up
make status
```

`make up` builds missing JARs, reuses a broker listener on localhost:61616 or starts
the supplied Compose broker, then launches ingestion, hub, transit and delay-stage
in that order. It waits for each application's HTTP health check and returns the
terminal to you while the processes keep running.

HTTP health does not prove that a delay event has arrived or that the JMS
subscription is ready. Check the subscriber-ready log before publishing the first
update; transit returns 503 until a valid event arrives for the requested hub.

| Command | Purpose |
|---|---|
| `make up` | Build missing JARs and start all four services and the broker |
| `make status` | Show tracked processes, ports and HTTP health |
| `make logs` | Follow all application logs; Ctrl+C exits the viewer |
| `make stop` | Stop tracked application processes; keep the shared broker running |
| `make restart` | Stop and start the application processes using existing JARs |
| `make build` | Package all four services without executing tests |
| `make test` | Run `clean verify` for all four services |

Logs and PID records are stored outside the repository in a directory under
`${TMPDIR:-/tmp}`. `make status` prints the log directory. PID records include the
process start time and boot ID so stale records do not identify a different
process. An occupied application port owned by an untracked process causes startup
to fail. Failed startup stops only services started by that invocation.

`make up` uses existing JARs when present. To apply source changes:

```bash
make stop
make build
make up
```

`make restart` does not rebuild. Override the per-service startup wait when needed:

```bash
LOGISTICS_START_TIMEOUT=90 make up
```

The launcher uses the default localhost ports documented below. Custom application
ports or a remote broker require adjusting the launcher or using manual startup.
Keep Makefile recipe indentation as tabs and save the launcher with LF line endings.
The Makefile invokes Bash directly, so an executable bit on `scripts/dev.sh` is not
required.

### Manual startup: broker

If a compatible ActiveMQ Classic broker already listens on ports 61616 and
8161, reuse it. Otherwise start the supplied broker:

```bash
docker compose -f common/docker-compose.yml up -d
docker compose -f common/docker-compose.yml ps
```

Default broker URL: `tcp://localhost:61616`.
Web console: `http://localhost:8161`.
Topic: `package-status-topic`.

See [common/README.md](common/README.md) and the service READMEs for broker
configuration. The broker must be available before starting the MQ-aware
services.

### Manual startup: application services

Use a separate terminal for each command, with every terminal at the repository
root. Start them in this order:

Terminal 1 — ingestion:

```bash
java -jar ingestion-service/target/ingestion-service.jar
```

Wait for ingestion to start, then use Terminal 2 — hub lookup:

```bash
java -jar hub-service/target/hub-service.jar
```

Terminal 3 — transit subscriber:

```bash
java -jar transit-service/target/transit-service.jar
```

Wait for the subscriber-ready log before publishing any delay changes.
Terminal 4 — delay-stage publisher:

```bash
java -jar delay-stage-service/target/delay-stage-service.jar
```

Use another terminal for requests. Each service exposes `GET /health`:

```bash
curl -sS -i http://localhost:7050/health
curl -sS -i http://localhost:7051/health
curl -sS -i http://localhost:7052/health
curl -sS -i http://localhost:7053/health
```

A successful health response is `200 OK` with body `OK`. Health checks do not
establish a delay stage; a known hub still needs a received event before transit
can calculate its ETA.

## API and demonstration

| Service | Endpoint | Behavior |
|---|---|---|
| Ingestion | `GET :7050/hubs` | Returns the 11 cleaned hub records |
| Hub | `GET :7051/hubs/{hubId}` | Returns hub details; unknown hubs return 404 |
| Delay stage | `GET :7052/delay-stage/{hubId}` | Returns stored stage, defaulting to 0 for an unrecorded ID |
| Delay stage | `POST :7052/delay-stage/{hubId}` | Accepts an integer stage 0–8; effective changes publish an event |
| Transit | `GET :7053/transit/{hubId}` | Uses hub REST data and cached delay events; unknown hubs return 404 and missing delay events return 503 |

### Cleaned data and missing events

```bash
curl -sS -i http://localhost:7050/hubs
curl -sS -i http://localhost:7051/hubs/H-508
curl -sS -i http://localhost:7053/transit/H-501
```

H-508 has `province: null` and `sortingCenter: "Pretoria North"`. H-511 and H-517
retain `active: null`.

On a fresh transit process, the last request returns 503 until H-501 receives a
valid event. Transit does not infer stage 0 from an empty cache.

### Publish a change and obtain an ETA

After `make up` (or manual startup), with fresh service processes and the subscriber ready:

```bash
curl -sS -i -X POST http://localhost:7052/delay-stage/H-501 \
  -H 'Content-Type: application/json' \
  -d '{"stage":4}'
```

The update returns 200 and publishes an event. Delivery is asynchronous; repeat
the following GET until it reflects stage 4:

```bash
curl -sS -i http://localhost:7053/transit/H-501
```

The response reports stage 4 with a **48–56-hour** arrival window. The demonstration
formula is:

- Base transit: 24 hours.
- Additional delay: `stage × 6` hours.
- Minimum ETA: `24 + stage × 6` hours.
- Maximum ETA: minimum plus 8 hours.

This is a deterministic demonstration formula, not a prediction based on live
route or weather data.

A newer update may reduce the delay:

```bash
curl -sS -i -X POST http://localhost:7052/delay-stage/H-501 \
  -H 'Content-Type: application/json' \
  -d '{"stage":2}'
```

After delivery, transit reports stage 2 with a **36–44-hour** window.

### Validation and decoupling

```bash
curl -sS -i -X POST http://localhost:7052/delay-stage/H-501 \
  -H 'Content-Type: application/json' \
  -d '{"stage":9.5}'
curl -sS -i http://localhost:7052/delay-stage/H-501
curl -sS -i http://localhost:7053/transit/H-501
```

The invalid update returns 400. Both services retain stage 2; transit retains the
36–44-hour window. Missing/null stages, malformed JSON, wrong types and integers
outside 0–8 are also rejected.

After transit has received stage 2, the recorded decoupling check stopped only
the delay-stage publisher while keeping hub, ingestion, transit and the broker
running. With manual startup, stop Terminal 4 using Ctrl+C. For launcher-started
services, `make status` shows the publisher PID; sending `kill -TERM <publisher-pid>`
reproduces that check. This request still succeeds with the cached stage and ETA:

```bash
curl -sS -i http://localhost:7053/transit/H-501
```

For launcher-started processes, finish with:

```bash
make stop
```

For manual startup, use Ctrl+C in each application terminal. The launcher leaves
the broker running because it may be shared. If you started the Compose broker
specifically for this demo, stop it when finished:

```bash
docker compose -f common/docker-compose.yml stop
```

## Tests and recorded verification

Recorded local results from 30 October 2026:

| Service | Default tests | Broker integration tests |
|---|---:|---:|
| Ingestion | 12 | — |
| Hub | 4 | — |
| Delay stage | 7 | 1 |
| Transit | 9 | 1 |
| **Total** | **32** | **2** |

All recorded tests passed with zero failures, errors or skips. Run the four
`clean verify` commands in [Build](#build), or `make test`, for default tests.

With a broker running, use the integration profile for real JMS delivery tests:

```bash
mvn -f delay-stage-service/pom.xml -Pintegration-tests clean verify
mvn -f transit-service/pom.xml -Pintegration-tests clean verify
```

The profile runs module unit tests through Surefire and `*IT` tests through
Failsafe. Integration tests use isolated test topics. The profile also builds
from a fresh checkout before running those tests.

Stage-2 packaged-JAR checks confirmed cleaned data and null preservation across
all 11 hubs, REST integration, stage updates, validation and ETA calculation.
Stage-3 packaged-JAR checks confirmed JMS topic delivery, decreasing delay stages,
missing-event responses, and transit serving its cached ETA after the publisher
stopped. The Stage-2 delay REST lookup was replaced by the Stage-3 subscriber.

## Current limitations

- Hub and delay data are held in memory; hub data comes from the bundled CSV.
- Transit returns 503 for a known hub until its first valid delay event arrives.
- Transit restart clears its delay cache. Automatic replay and durable
  subscription recovery are not implemented; it waits for a new event.
- Posting an unchanged stage publishes no event, including posting stage 0 for
  an unrecorded ID. Repeating the same POST cannot refill an empty transit cache.
- Only events with a strictly newer timestamp replace a cached stage. Equal or
  older timestamps are ignored, even if their stage differs.
- A publication exception returns HTTP 500 and leaves the producer's local stage
  unchanged. This project does not implement a database transaction or outbox.
- The ETA formula is illustrative. AlertBot remains an optional scaffold.

Service details: [ingestion](ingestion-service/README.md),
[hub](hub-service/README.md), [delay stage](delay-stage-service/README.md),
[transit](transit-service/README.md), and [shared integration setup](common/README.md).# LogisticsConnect

## Overview

Supply chain parcel delivery hub and transit delay tracking.

Domain entities: hubs, sorting centers, regional districts.

Application and domain classes use `co.wethinkcode.logisticsconnect`; messaging
classes use its `mq` subpackage. LogisticsConnect is built as a small set of
independent services, following a growth path from simple data
cleanup through synchronous REST calls to asynchronous MQ decoupling and alerting:

1. clean a messy legacy CSV export (`hubs-global.csv`) — handled by **IngestionServiceApp**
2. serve it up and act on it, via three REST services calling each other directly
   over HTTP
3. decouple the relevant services with an ActiveMQ topic (`package-status-topic`) instead of
   direct calls — shared broker setup lives in [`common/`](common)
4. raise the alarm on failure — handled by **AlertBotApp**

| Service | Folder | Port | Role |
|---|---|---|---|
| IngestionServiceApp | [`ingestion-service/`](ingestion-service) | 7050 | Parses and cleans `hubs-global.csv` |
| HubServiceApp | [`hub-service/`](hub-service) | 7051 | Serves provinces and sorting centers (place-name source of truth). |
| DelayStageServiceApp | [`delay-stage-service/`](delay-stage-service) | 7052 | Tracks the Transit Delay Stage (0-8, e.g. weather shutdowns). |
| TransitServiceApp | [`transit-service/`](transit-service) | 7053 | Calculates estimated arrival windows based on hub and delay stage. |
| AlertBotApp | [`alertbot/`](alertbot) | 7054 | Optional scaffold for simulated delay notifications; not implemented. |

Shared ActiveMQ broker setup and MQ configuration notes live in [`common/`](common).
The broker uses port 61616 for OpenWire and port 8161 for its web console.
Delay updates use the `package-status-topic` topic.

## Implementation status

Required stages **1–3 are implemented and locally verified**. Stage 4, AlertBot,
is an optional scaffold and is not implemented.

| Stage | Status | Implemented behavior |
|---|---|---|
| 1. CSV ingestion | Complete | Classpath CSV loading, normalization, deterministic deduplication and `GET /hubs` |
| 2. REST integration | Complete | Hub lookup, validated delay-stage updates and ETA calculation; the stage-2 delay REST lookup was replaced in stage 3 |
| 3. MQ decoupling | Complete | Delay changes publish to `package-status-topic`; transit consumes events into a local cache |
| 4. AlertBot | Optional, not implemented | The supplied scaffold remains available for future work |

The supplied CSV is unchanged: **18 data rows produce 11 cleaned hubs**, with
7 duplicate records merged and no rows rejected. Missing facts remain `null`;
for example, H-508 retains a null province and sorting center `Pretoria North`.
See [ingestion-service/README.md](ingestion-service/README.md) for cleaning rules.

Current runtime integration:

- Hub service loads cleaned hub records from ingestion over REST.
- Transit service retrieves hub details from hub service over REST.
- Delay-stage service publishes stage changes through ActiveMQ.
- Transit service calculates ETAs from its local event cache, without calling
  delay-stage service over HTTP.

The earlier independent Spring prototype is preserved in Git history. The
active implementation and build instructions use the services in this fork.

## Project brief

The upstream stage descriptions and estimates below are retained for reference.
Current completion status is recorded above.

Implement the four stages below, in order — each one builds on the last, and the
later stages assume the earlier ones work. Stage 1-3 are required; stage 4 is a
stretch goal if you have time left.

| Stage | Required? | What "done" looks like | Rough effort |
|---|---|---|---|
| 1. Clean `hubs-global.csv` | Required | IngestionServiceApp exposes the cleaned records via REST (see [Integration contracts](#integration-contracts)); every issue category in [ingestion-service/README.md](ingestion-service/README.md) is handled | ~1-1.5h |
| 2. Wire up the REST services | Required | hub-service, delay-stage-service, and transit-service each expose real domain endpoints (not just `/health`) and call each other synchronously per the contracts below; `transit-service` can return an ETA for a hub | ~1.5-2h |
| 3. Decouple with the MQ topic | Required | `delay-stage-service` publishes to `package-status-topic` on stage change; `transit-service` subscribes instead of calling `delay-stage-service` directly; broker runs via `common/docker-compose.yml` | ~1h |
| 4. AlertBot | Stretch | `alertbot` subscribes to `package-status-topic` and simulates posting an alert when a hub's delay stage crosses a threshold you choose | ~30-45m |

You don't need to match any exact field names, endpoint paths, or message shapes —
the ones below are illustrative. Favor a working, readable implementation over a
gold-plated one; partial completion of stage 3 or 4 is fine if 1-2 are solid.

## Integration contracts

Two kinds of integration point exist in this repo: synchronous REST calls (stage 2)
and the asynchronous MQ topic (stage 3). Field/endpoint names below are illustrative
— reasonable variations are fine as long as the shape (who calls whom, with what
kind of payload) is preserved.

### REST (stage 2)

| Caller | Callee | Example | Purpose |
|---|---|---|---|
| hub-service | ingestion-service | `GET :7050/hubs` → JSON array of cleaned hub records | hub-service loads its place-name data from the cleaned CSV output instead of re-parsing it itself |
| transit-service | hub-service | `GET :7051/hubs/{hubId}` → hub/sorting-center details | transit-service needs hub location data to calculate an ETA |
| transit-service | delay-stage-service | `GET :7052/delay-stage/{hubId}` → `{ "hubId": "H-501", "stage": 3 }` | transit-service needs the current delay stage to calculate an ETA — **this call is replaced by the MQ subscription in stage 3** |
| (client) | delay-stage-service | `POST :7052/delay-stage/{hubId}` with a body like `{ "stage": 3 }` | the stage/state-change endpoint referenced in [common/README.md](common/README.md) — this is also where the stage-3 MQ publish happens |

### MQ (stage 3) — topic `package-status-topic`

Already documented in detail in [common/README.md](common/README.md): broker URL and
topic name come from the shared `co.wethinkcode.logisticsconnect.mq.MqConfig` class,
duplicated into each participating service.

- **Producer:** `delay-stage-service`, on its stage/state-change endpoint above.
- **Consumers:** `transit-service` (replacing its direct REST call to
  delay-stage-service) and, for the stretch goal, `alertbot`.
- **Example message shape:** `{ "hubId": "H-501", "stage": 5, "timestamp": "2026-07-18T10:15:00Z" }`

The implemented publisher emits a positive numeric epoch-millisecond timestamp.
The subscriber also accepts ISO-8601 timestamp strings as in the illustrative
contract above. It validates message type, required fields, stage range and
timestamp before updating its cache.

## Project structure

Each service is an independent Maven project:

```text
sin-001-logisticsconnect/
├── README.md
├── RUBRIC.md
├── Makefile                # Build, test and lifecycle shortcuts
├── scripts/dev.sh          # Linux/WSL process launcher
├── ingestion-service/       # 7050: CSV cleaning and cleaned records
├── hub-service/             # 7051: hub lookup
├── delay-stage-service/     # 7052: stage API and JMS publisher
├── transit-service/         # 7053: JMS subscriber, cache and ETA API
├── common/                 # ActiveMQ Compose file and integration notes
└── alertbot/               # 7054: optional scaffold
```

## Build

Requirements: Linux/WSL with Bash 4 or later, GNU Make, JDK 17 or later,
Maven 3.8 or later, and `curl`. Docker with Docker Compose is needed when the
launcher starts the broker. Maven source/target remains Java 17.

The launcher uses `/proc`, `flock`, `setsid`, `nohup` and `timeout`, which are
standard tools in Ubuntu/WSL. There is no parent POM or Maven wrapper.

Run commands from the repository root. Build and run all default tests:

```bash
make test
```

To package the services without running tests:

```bash
make build
```

Both commands process the four independent service modules. Default tests use
injected dependencies or local test servers and do not need separately running
services, Docker or an ActiveMQ broker.

The equivalent direct Maven commands remain available:

```bash
mvn -f ingestion-service/pom.xml clean verify
mvn -f hub-service/pom.xml clean verify
mvn -f delay-stage-service/pom.xml clean verify
mvn -f transit-service/pom.xml clean verify
```

## Run

### Start everything from one terminal

Stop any manually started instances of the four application services first.
Ports 7050–7053 must be free or belong to services already tracked by this launcher.
Then run:

```bash
make up
make status
```

`make up` builds missing JARs, reuses a broker listener on localhost:61616 or starts
the supplied Compose broker, then launches ingestion, hub, transit and delay-stage
in that order. It waits for each application's HTTP health check and returns the
terminal to you while the processes keep running.

HTTP health does not prove that a delay event has arrived or that the JMS
subscription is ready. Check the subscriber-ready log before publishing the first
update; transit returns 503 until a valid event arrives for the requested hub.

| Command | Purpose |
|---|---|
| `make up` | Build missing JARs and start all four services and the broker |
| `make status` | Show tracked processes, ports and HTTP health |
| `make logs` | Follow all application logs; Ctrl+C exits the viewer |
| `make stop` | Stop tracked application processes; keep the shared broker running |
| `make restart` | Stop and start the application processes using existing JARs |
| `make build` | Package all four services without executing tests |
| `make test` | Run `clean verify` for all four services |

Logs and PID records are stored outside the repository in a directory under
`${TMPDIR:-/tmp}`. `make status` prints the log directory. PID records include the
process start time and boot ID so stale records do not identify a different
process. An occupied application port owned by an untracked process causes startup
to fail. Failed startup stops only services started by that invocation.

`make up` uses existing JARs when present. To apply source changes:

```bash
make stop
make build
make up
```

`make restart` does not rebuild. Override the per-service startup wait when needed:

```bash
LOGISTICS_START_TIMEOUT=90 make up
```

The launcher uses the default localhost ports documented below. Custom application
ports or a remote broker require adjusting the launcher or using manual startup.
Keep Makefile recipe indentation as tabs and save the launcher with LF line endings.
The Makefile invokes Bash directly, so an executable bit on `scripts/dev.sh` is not
required.

### Manual startup: broker

If a compatible ActiveMQ Classic broker already listens on ports 61616 and
8161, reuse it. Otherwise start the supplied broker:

```bash
docker compose -f common/docker-compose.yml up -d
docker compose -f common/docker-compose.yml ps
```

Default broker URL: `tcp://localhost:61616`.
Web console: `http://localhost:8161`.
Topic: `package-status-topic`.

See [common/README.md](common/README.md) and the service READMEs for broker
configuration. The broker must be available before starting the MQ-aware
services.

### Manual startup: application services

Use a separate terminal for each command, with every terminal at the repository
root. Start them in this order:

Terminal 1 — ingestion:

```bash
java -jar ingestion-service/target/ingestion-service.jar
```

Wait for ingestion to start, then use Terminal 2 — hub lookup:

```bash
java -jar hub-service/target/hub-service.jar
```

Terminal 3 — transit subscriber:

```bash
java -jar transit-service/target/transit-service.jar
```

Wait for the subscriber-ready log before publishing any delay changes.
Terminal 4 — delay-stage publisher:

```bash
java -jar delay-stage-service/target/delay-stage-service.jar
```

Use another terminal for requests. Each service exposes `GET /health`:

```bash
curl -sS -i http://localhost:7050/health
curl -sS -i http://localhost:7051/health
curl -sS -i http://localhost:7052/health
curl -sS -i http://localhost:7053/health
```

A successful health response is `200 OK` with body `OK`. Health checks do not
establish a delay stage; a known hub still needs a received event before transit
can calculate its ETA.

## API and demonstration

| Service | Endpoint | Behavior |
|---|---|---|
| Ingestion | `GET :7050/hubs` | Returns the 11 cleaned hub records |
| Hub | `GET :7051/hubs/{hubId}` | Returns hub details; unknown hubs return 404 |
| Delay stage | `GET :7052/delay-stage/{hubId}` | Returns stored stage, defaulting to 0 for an unrecorded ID |
| Delay stage | `POST :7052/delay-stage/{hubId}` | Accepts an integer stage 0–8; effective changes publish an event |
| Transit | `GET :7053/transit/{hubId}` | Uses hub REST data and cached delay events; unknown hubs return 404 and missing delay events return 503 |

### Cleaned data and missing events

```bash
curl -sS -i http://localhost:7050/hubs
curl -sS -i http://localhost:7051/hubs/H-508
curl -sS -i http://localhost:7053/transit/H-501
```

H-508 has `province: null` and `sortingCenter: "Pretoria North"`. H-511 and H-517
retain `active: null`.

On a fresh transit process, the last request returns 503 until H-501 receives a
valid event. Transit does not infer stage 0 from an empty cache.

### Publish a change and obtain an ETA

After `make up` (or manual startup), with fresh service processes and the subscriber ready:

```bash
curl -sS -i -X POST http://localhost:7052/delay-stage/H-501 \
  -H 'Content-Type: application/json' \
  -d '{"stage":4}'
```

The update returns 200 and publishes an event. Delivery is asynchronous; repeat
the following GET until it reflects stage 4:

```bash
curl -sS -i http://localhost:7053/transit/H-501
```

The response reports stage 4 with a **48–56-hour** arrival window. The demonstration
formula is:

- Base transit: 24 hours.
- Additional delay: `stage × 6` hours.
- Minimum ETA: `24 + stage × 6` hours.
- Maximum ETA: minimum plus 8 hours.

This is a deterministic demonstration formula, not a prediction based on live
route or weather data.

A newer update may reduce the delay:

```bash
curl -sS -i -X POST http://localhost:7052/delay-stage/H-501 \
  -H 'Content-Type: application/json' \
  -d '{"stage":2}'
```

After delivery, transit reports stage 2 with a **36–44-hour** window.

### Validation and decoupling

```bash
curl -sS -i -X POST http://localhost:7052/delay-stage/H-501 \
  -H 'Content-Type: application/json' \
  -d '{"stage":9.5}'
curl -sS -i http://localhost:7052/delay-stage/H-501
curl -sS -i http://localhost:7053/transit/H-501
```

The invalid update returns 400. Both services retain stage 2; transit retains the
36–44-hour window. Missing/null stages, malformed JSON, wrong types and integers
outside 0–8 are also rejected.

After transit has received stage 2, the recorded decoupling check stopped only
the delay-stage publisher while keeping hub, ingestion, transit and the broker
running. With manual startup, stop Terminal 4 using Ctrl+C. For launcher-started
services, `make status` shows the publisher PID; sending `kill -TERM <publisher-pid>`
reproduces that check. This request still succeeds with the cached stage and ETA:

```bash
curl -sS -i http://localhost:7053/transit/H-501
```

For launcher-started processes, finish with:

```bash
make stop
```

For manual startup, use Ctrl+C in each application terminal. The launcher leaves
the broker running because it may be shared. If you started the Compose broker
specifically for this demo, stop it when finished:

```bash
docker compose -f common/docker-compose.yml stop
```

## Tests and recorded verification

Recorded local results from 30 October 2026:

| Service | Default tests | Broker integration tests |
|---|---:|---:|
| Ingestion | 12 | — |
| Hub | 4 | — |
| Delay stage | 7 | 1 |
| Transit | 9 | 1 |
| **Total** | **32** | **2** |

All recorded tests passed with zero failures, errors or skips. Run the four
`clean verify` commands in [Build](#build), or `make test`, for default tests.

With a broker running, use the integration profile for real JMS delivery tests:

```bash
mvn -f delay-stage-service/pom.xml -Pintegration-tests clean verify
mvn -f transit-service/pom.xml -Pintegration-tests clean verify
```

The profile runs module unit tests through Surefire and `*IT` tests through
Failsafe. Integration tests use isolated test topics. The profile also builds
from a fresh checkout before running those tests.

Stage-2 packaged-JAR checks confirmed cleaned data and null preservation across
all 11 hubs, REST integration, stage updates, validation and ETA calculation.
Stage-3 packaged-JAR checks confirmed JMS topic delivery, decreasing delay stages,
missing-event responses, and transit serving its cached ETA after the publisher
stopped. The Stage-2 delay REST lookup was replaced by the Stage-3 subscriber.

## Current limitations

- Hub and delay data are held in memory; hub data comes from the bundled CSV.
- Transit returns 503 for a known hub until its first valid delay event arrives.
- Transit restart clears its delay cache. Automatic replay and durable
  subscription recovery are not implemented; it waits for a new event.
- Posting an unchanged stage publishes no event, including posting stage 0 for
  an unrecorded ID. Repeating the same POST cannot refill an empty transit cache.
- Only events with a strictly newer timestamp replace a cached stage. Equal or
  older timestamps are ignored, even if their stage differs.
- A publication exception returns HTTP 500 and leaves the producer's local stage
  unchanged. This project does not implement a database transaction or outbox.
- The ETA formula is illustrative. AlertBot remains an optional scaffold.

Service details: [ingestion](ingestion-service/README.md),
[hub](hub-service/README.md), [delay stage](delay-stage-service/README.md),
[transit](transit-service/README.md), and [shared integration setup](common/README.md).