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
| Full-capacity burst | 5,000 | 5,000 | 500 ms | 5,000 | 5,000 | 0 | Passed; every user received a unique voucher and no retries were used. |

For the native 1,000-in-flight run, all admissions completed in 4.626 seconds, admission HTTP p95 was 1,705.01 ms, and the whole test completed in 6.285 seconds. Its admissions spanned ten cohorts.

For the native 5,000-in-flight burst, all admissions completed in 2.774 seconds, admission HTTP p95 was 2,676.45 ms, and the whole test completed in 4.488 seconds. Its admissions spanned four cohorts. Mock loyalty priority was verified separately in the one-cohort smoke test and integration tests; different time windows intentionally do not share a global ranking.

For the full-capacity burst, all 5,000 admissions completed in 2.609 seconds, admission HTTP p95 was 2,498.19 ms, and the whole test completed in 4.188 seconds. The report recorded exactly 5,000 successful attempts and an empty retry counter.

These runs establish the tested inventory and replay behavior under the stated load. The initial p95 admission target of 300 ms was not achieved at this burst size. The load script does not measure individual admission-to-resolution latency, so the p95 two-second result target is not claimed. All test HTTP durations start after the load generator's semaphore is acquired and exclude any earlier client-side concurrency wait.

An earlier run with the original x86 Java 17 runtime under Rosetta produced admission and transport timeouts. An earlier native full-capacity attempt also encountered 651 connection resets while establishing client connections; the subsequent reported full-capacity run passed without retrying. The live client now supports up to three retries for transient network errors or retryable HTTP responses, always using the same user and campaign and reporting every attempt. A deployment capacity claim still requires repeated benchmarks on its actual hardware and runtime.

Generated machine-readable reports are written under `target/reports/` and are intentionally ignored by Git. Docker Compose is provided as an alternative, but Docker was not installed on this machine and that path was not executed here.

## Fresh clone workflow

The documented `make verify` workflow passed from a separate clone with no pre-existing `target` directory, local settings, credentials, or development database files. It used the ARM64 Java 17 runtime and alternate local ports 18080 and 55433 so it could run independently of the main checkout.

That workflow prepared dependencies, passed all 24 tests, initialized a new development database, built and started the API, waited for readiness, and passed the 20-user live priority and replay test. All 20 requests were accepted into one cohort, exactly five won, and no retry was needed. Admission HTTP p95 for this smoke test was 69.98 ms. The scratch clone's processes were stopped after verification.
