# Verification results

Verified on October 17, 2026, on macOS with an Apple Silicon processor and PostgreSQL 17.11. The HTTP integration suite also passed on the originally installed x86 OpenJDK 17.0.2. The successful load runs used the native ARM64 Temurin JDK 17.0.20.1.

## Automated tests

`./mvnw -B verify` passed 24 tests with zero failures, errors, or skipped tests:

- 17 HTTP integration cases, including parameterized validation, same-window loyalty priority, replay, oversell prevention, expiry, two application instances, and recovery from a failure injected after the first processing chunk.
- 7 configuration cases for valid binding and startup rejection of invalid window or chunk settings.

The first Git commit contains the tests without application Java code. Its baseline failed because the Spring Boot application configuration did not yet exist.

## Live HTTP runs

| Run | Users | Maximum in flight | Window | Accepted | Issued | Rejected | Result |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Priority smoke test | 20 | 20 | 5,000 ms | 20 | 5 | 15 | Passed; exactly the five highest mock scores won within one cohort. |
| Load run | 5,000 | 1,000 | 500 ms | 5,000 | 100 | 4,900 | Passed with zero admission HTTP or transport errors. |
| Full burst | 5,000 | 5,000 | 500 ms | 5,000 | 100 | 4,900 | Passed with zero admission HTTP or transport errors. |

For the native 1,000-in-flight run, all admissions completed in 4.626 seconds, admission HTTP p95 was 1,705.01 ms, and the whole test completed in 6.285 seconds. Its admissions spanned ten cohorts.

For the native 5,000-in-flight burst, all admissions completed in 2.774 seconds, admission HTTP p95 was 2,676.45 ms, and the whole test completed in 4.488 seconds. Its admissions spanned four cohorts. Mock loyalty priority was verified separately in the one-cohort smoke test and integration tests; different time windows intentionally do not share a global ranking.

These runs establish the tested inventory and replay behavior under the stated load. The initial p95 admission target of 300 ms was not achieved at this burst size. The load script does not measure individual admission-to-resolution latency, so the p95 two-second result target is not claimed. All test HTTP durations start after the load generator's semaphore is acquired and exclude any earlier client-side concurrency wait.

An earlier run with the original x86 Java 17 runtime under Rosetta produced admission and transport timeouts. Switching to a native ARM64 JDK removed those errors in the reported runs. A deployment capacity claim still requires a benchmark on its actual hardware and runtime.

Generated machine-readable reports are written under `target/reports/` and are intentionally ignored by Git. Docker Compose is provided as an alternative, but Docker was not installed on this machine and that path was not executed here.

## Fresh clone workflow

The final verification uses a separate clone with new local settings and database files, running the documented `make verify` workflow on alternate local ports. Its result is recorded after execution.
