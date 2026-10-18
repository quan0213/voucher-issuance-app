# Code Structure and Style

The application is organized by feature. Controllers handle HTTP, services implement use cases and transaction boundaries, and repositories execute SQL. Related classes stay together so a reviewer can follow one feature without navigating separate global controller, service, and repository packages.

## Source layout

```text
src/main/java/com/aepsy/voucher/
├── VoucherApplication.java
├── campaign/
│   ├── CampaignController.java
│   ├── CampaignService.java
│   ├── CampaignRepository.java
│   ├── Campaign.java
│   └── CreateCampaignCommand.java
├── claim/
│   ├── ClaimController.java
│   ├── ClaimSubmissionService.java
│   ├── ClaimAdmissionService.java
│   ├── ClaimRepository.java
│   └── ClaimResult.java
├── allocation/
│   ├── AllocationScheduler.java
│   ├── BatchCatalogService.java
│   ├── BatchCreationService.java
│   ├── BatchAllocationService.java
│   ├── BatchRepository.java
│   └── ClaimBatch.java
├── loyalty/
│   ├── LoyaltyProvider.java
│   ├── MockLoyaltyProvider.java
│   └── LoyaltyProperties.java
└── common/
    ├── SecurityConfiguration.java
    ├── ApiErrorHandler.java
    ├── ApiException.java
    ├── DatabaseClock.java
    ├── AdminProperties.java
    └── BatchingProperties.java

src/main/resources/
├── application.yml
└── db/migration/V1__voucher_schema.sql

src/test/java/com/aepsy/voucher/
├── ClaimApiIntegrationTest.java
├── BatchingConfigurationTest.java
├── TestDatabase.java
└── LocalPostgres.java
```

## Responsibilities

| Package | Purpose |
| --- | --- |
| `campaign` | Admin endpoints, campaign validation, schedules, and inventory queries. |
| `claim` | Public claim endpoints, duplicate handling, durable admission, and result lookup. |
| `allocation` | Window creation, discovery of due work, loyalty ranking, and voucher issuance. |
| `loyalty` | Mock scores behind a replaceable `LoyaltyProvider` interface. |
| `common` | Shared configuration, security, error responses, and authoritative database time. |

The repository root contains the Maven Wrapper, [Makefile](Makefile), [scripts](scripts/), and [api.http](api.http). Flyway manages the database schema; generated credentials and local database data stay in ignored `.local/` files.

## Execution paths

```text
Campaign creation:
CampaignController -> CampaignService -> CampaignRepository

Claim submission:
ClaimController -> ClaimSubmissionService
                   ├── LoyaltyProvider
                   ├── BatchCatalogService -> BatchCreationService
                   └── ClaimAdmissionService -> PostgreSQL

Allocation:
AllocationScheduler -> BatchAllocationService -> PostgreSQL

Result lookup:
ClaimController -> ClaimSubmissionService -> ClaimRepository
```

`ClaimSubmissionService` orchestrates the request without holding a transaction while resolving loyalty or retrying. Separate Spring beans define the short batch-creation and admission transactions. `BatchAllocationService` owns one transaction for the complete closed window, including every processing chunk. The scheduler calls it through the transactional bean for each window.

## Coding conventions

| Area | Convention |
| --- | --- |
| Names | `PascalCase` classes, `camelCase` methods and variables, `UPPER_SNAKE_CASE` constants. |
| Formatting | UTF-8, LF endings, four spaces for Java; two for Markdown, YAML, JSON, and XML, as defined in [.editorconfig](.editorconfig). Makefile recipes use tabs. |
| Dependencies | Constructor injection and `private final` fields. |
| Data objects | Java records for immutable request, result, configuration, and database row models. |
| HTTP | Controllers validate input, delegate to services, and choose response status and headers. |
| SQL | Spring JDBC with bound parameters and text blocks for multiline queries. |
| Transactions | Explicit service boundaries; allocation and admission use `READ COMMITTED`. |
| Errors | `ApiErrorHandler` maps application exceptions to responses with `code`, `message`, and `traceId`. |
| Configuration | Typed, validated properties; invalid batch configuration fails startup. |
| Comments | Explain concurrency decisions and transaction constraints alongside the affected code. |

## Reviewer starting points

| Question | Read |
| --- | --- |
| How is one claim accepted safely? | [ClaimSubmissionService](src/main/java/com/aepsy/voucher/claim/ClaimSubmissionService.java) and [ClaimAdmissionService](src/main/java/com/aepsy/voucher/claim/ClaimAdmissionService.java). |
| How are time windows calculated? | [BatchCatalogService](src/main/java/com/aepsy/voucher/allocation/BatchCatalogService.java). |
| Where are loyalty ordering and chunk processing implemented? | [BatchAllocationService](src/main/java/com/aepsy/voucher/allocation/BatchAllocationService.java). |
| Which constraints and indexes protect the data? | [V1 migration](src/main/resources/db/migration/V1__voucher_schema.sql). |
| How are concurrency and failures tested? | [ClaimApiIntegrationTest](src/test/java/com/aepsy/voucher/ClaimApiIntegrationTest.java). |

`ClaimApiIntegrationTest` covers real HTTP requests against PostgreSQL, including multiple application instances and rollback after a processing-chunk failure. `BatchingConfigurationTest` checks property binding and validation. `TestDatabase` provides an isolated database; `LocalPostgres` launches the persistent local development database.

For runtime sequences and SQL explanations, see [Architecture](ARCHITECTURE.md). For runnable requests and responses, see [API examples](API_EXAMPLES.md).
