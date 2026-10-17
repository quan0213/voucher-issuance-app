# Voucher Issuance Service

A Java 17 / Spring Boot 3 service for limited voucher campaigns. It prevents overselling and duplicate claims, and prioritizes users by mocked loyalty points within a configurable time window.

For the design and concurrency rules, start with [Architecture](ARCHITECTURE.md).

## Quick start

Requires JDK 17, Python 3.9+, Make, and curl or wget on macOS or Linux (WSL on Windows). Set `JAVA_HOME` to your JDK; use an ARM64 JDK on Apple Silicon. Run as a normal OS user because PostgreSQL cannot initialize as root.

From the repository root:

```bash
make verify
```

This downloads dependencies, runs the tests, starts PostgreSQL and the app, then runs a live API test with **20 users competing for 5 vouchers**. The test checks that the five highest loyalty scores in one window win, voucher codes are unique, and a repeated claim returns the original result.

Maven and PostgreSQL are managed by the project; Docker is optional. The first run needs internet access. The app and database remain running afterward.

```bash
curl --fail http://127.0.0.1:8080/actuator/health/readiness
```

Default ports: API **8080**, PostgreSQL **55432**. Local settings, generated admin credentials, and database files are stored in ignored `.local/` files.

## Useful commands

| Command | Purpose |
| --- | --- |
| `make up` | Start PostgreSQL and the app. |
| `make test` | Run tests with an isolated PostgreSQL database. |
| `make smoke` | Run the live API test against the running app. |
| `make load` | Test 5,000 claims with up to 1,000 requests in flight. |
| `make restart` | Rebuild and restart after code changes. |
| `make status` / `make logs` | Check processes or inspect logs. |
| `make down` | Stop the app and database; preserve local data. |

## Run in IntelliJ IDEA

1. Open `pom.xml` as a Maven project and select JDK 17 and the Maven Wrapper.
2. Run `make setup` and `make db-up` in the IDE terminal.
3. Run `com.aepsy.voucher.VoucherApplication` with this repository as the working directory.

If the background app is running, use `make down` before step 2 to free port 8080. Tests also run directly from the IDE.

## API

| Method | Endpoint | Purpose |
| --- | --- | --- |
| POST | `/api/v1/admin/campaigns` | Create a campaign with a fixed voucher capacity. |
| GET | `/api/v1/admin/campaigns/{id}` | Inspect campaign settings and issued count. |
| POST | `/api/v1/claims` | Submit `{ "userId": "user-1", "campaignId": "<id>" }`. |
| GET | `/api/v1/claims/{requestId}` | Read the claim result. |

A new claim returns **202 Accepted** with a `requestId`. Poll that request until it becomes `ISSUED` with a `voucherCode`, or `REJECTED` with `reason: SOLD_OUT`. Repeating the same user and campaign returns the original request; a completed replay returns **200**.

Run `make smoke` for an automatic API walkthrough, or use [api.http](api.http) with the `local` environment in a compatible editor HTTP client. Setup generates its private credentials file. Admin endpoints use HTTP Basic authentication; credentials are also in `.local/settings.json`. User identity and loyalty are mocked for this assignment.

## Batch configuration

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `VOUCHER_BATCH_WINDOW_MS` | `500` | How long requests are collected before allocation, in milliseconds. |
| `VOUCHER_BATCH_PROCESSING_CHUNK_SIZE` | `500` | How many requests the worker processes per database chunk. |

The window closes by time, never by request count. Chunk size does not change which users compete or their loyalty order. Campaign creation can override the window with `batchWindowMs`.

Example for new campaigns:

```bash
make down
VOUCHER_BATCH_WINDOW_MS=1000 VOUCHER_BATCH_PROCESSING_CHUNK_SIZE=100 make up
```

## Further reading

- [Architecture](ARCHITECTURE.md): sequence diagrams, batch queries, priority rules, and concurrency guarantees.
- [Verification results](VERIFICATION.md): 24 passing tests and measured load results, including latency limits.
- [Detailed specification](TECH_SPEC.md): API contracts, locking protocol, and implementation plan.
