package com.aepsy.voucher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** HTTP contracts written before the application implementation. No service mocks. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "voucher.batching.processing-chunk-size=2",
        "voucher.batching.window-ms=500",
        "voucher.allocation.scan-interval-ms=40"
})
class ClaimApiIntegrationTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) { TestDatabase.configure(registry); }

    @Test
    void campaignAdministrationRequiresAuthentication() throws Exception {
        Reply response = send("POST", "/api/v1/admin/campaigns", Map.of("code", code(), "capacity", 5), false);
        assertThat(response.status()).isEqualTo(401);
    }

    @Test
    void campaignCreationResolvesConfigurationAndCanBeInspected() throws Exception {
        Reply created = adminCampaign(Map.of("code", code(), "capacity", 5));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().path("capacity").asInt()).isEqualTo(5);
        assertThat(created.body().path("batchWindowMs").asInt()).isEqualTo(500);
        assertThat(created.body().path("issuedCount").asInt()).isZero();
        Reply inspected = send("GET", "/api/v1/admin/campaigns/" + created.body().path("id").asText(), null, true);
        assertThat(inspected.status()).isEqualTo(200);
        assertThat(inspected.body().path("id")).isEqualTo(created.body().path("id"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void nonPositiveCapacityIsRejected(int capacity) throws Exception {
        assertThat(adminCampaign(Map.of("code", code(), "capacity", capacity)).status()).isEqualTo(400);
    }

    @Test
    void invalidWindowAndDuplicateCampaignCodeAreRejected() throws Exception {
        assertThat(adminCampaign(Map.of("code", code(), "capacity", 5, "batchWindowMs", 0)).status()).isEqualTo(400);
        String code = code();
        assertThat(adminCampaign(Map.of("code", code, "capacity", 5)).status()).isEqualTo(201);
        Reply duplicate = adminCampaign(Map.of("code", code, "capacity", 5));
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.body().path("code").asText()).isEqualTo("CAMPAIGN_CODE_EXISTS");
    }

    @Test
    void unknownCampaignAndMalformedClaimsHaveClearErrors() throws Exception {
        Reply missing = claim(UUID.randomUUID().toString(), "someone");
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.body().path("code").asText()).isEqualTo("CAMPAIGN_NOT_FOUND");
        assertThat(claim("not-a-uuid", "someone").status()).isEqualTo(400);
        assertThat(claim(UUID.randomUUID().toString(), " ").status()).isEqualTo(400);
        assertThat(send("GET", "/api/v1/claims/" + UUID.randomUUID(), null, false).status()).isEqualTo(404);
    }

    @Test
    void clientCannotSupplyLoyaltyPoints() throws Exception {
        String campaign = campaign(5, 1000);
        Reply result = send("POST", "/api/v1/claims", Map.of("campaignId", campaign, "userId", "someone", "loyaltyPoints", 999999), false);
        assertThat(result.status()).isEqualTo(400);
    }

    @Test
    void twentyConcurrentUsersCompeteAsOneCohortAndTopFiveWin() throws Exception {
        String campaign = campaign(5, 5000);
        List<Reply> admissions = concurrent(20, n -> () -> claim(campaign, "priority-" + (n + 1)));
        Set<String> batchIds = new HashSet<>();
        for (Reply admission : admissions) {
            assertThat(admission.status()).isEqualTo(202);
            assertThat(admission.headers().firstValue("Location")).isPresent();
            batchIds.add(admission.body().path("batchId").asText());
            assertThat(result(admission).path("status").asText()).isEqualTo("PENDING");
        }
        assertThat(batchIds).hasSize(1);
        List<JsonNode> results = finished(admissions);
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < results.size(); i++) {
            JsonNode result = results.get(i);
            assertThat(result.path("status").asText()).isEqualTo(i >= 15 ? "ISSUED" : "REJECTED");
            if (i >= 15) codes.add(result.path("voucherCode").asText());
            else assertThat(result.path("reason").asText()).isEqualTo("SOLD_OUT");
        }
        assertThat(codes).hasSize(5);
        assertThat(inventory(campaign)).isEqualTo(5);
        assertThat(finished(List.of(claim(campaign, "late-low-score"))).get(0).path("status").asText()).isEqualTo("REJECTED");
        assertThat(inventory(campaign)).isEqualTo(5);
    }

    @Test
    void processingChunksPreserveGlobalOrderingAcrossTwelveWinners() throws Exception {
        String campaign = campaign(12, 5000);
        List<Reply> admissions = concurrent(20, n -> () -> claim(campaign, "priority-" + (n + 1)));
        assertThat(admissions.stream().map(r -> r.body().path("batchId").asText()).distinct()).hasSize(1);
        List<JsonNode> results = finished(admissions);
        for (int i = 0; i < results.size(); i++) {
            assertThat(results.get(i).path("status").asText()).isEqualTo(i >= 8 ? "ISSUED" : "REJECTED");
        }
        assertThat(inventory(campaign)).isEqualTo(12);
    }

    @Test
    void concurrentRetriesReturnOneLogicalRequestAndOneVoucher() throws Exception {
        String campaign = campaign(10, 1500);
        List<Reply> retries = concurrent(50, n -> () -> claim(campaign, "same-user"));
        for (Reply reply : retries) assertThat(reply.status()).isIn(200, 202);
        assertThat(retries.stream().map(r -> r.body().path("requestId").asText()).distinct()).hasSize(1);
        JsonNode issued = finished(List.of(retries.get(0))).get(0);
        assertThat(issued.path("status").asText()).isEqualTo("ISSUED");
        Reply replay = claim(campaign, "same-user");
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().path("voucherCode")).isEqualTo(issued.path("voucherCode"));
        assertThat(inventory(campaign)).isEqualTo(1);
    }

    @Test
    void burstDoesNotOversellOrApplyASharedTenRequestThrottle() throws Exception {
        String campaign = campaign(7, 1000);
        List<Reply> admissions = concurrent(120, n -> () -> claim(campaign, "burst-" + n));
        for (Reply reply : admissions) assertThat(reply.status()).isIn(200, 202);
        List<JsonNode> results = finished(admissions);
        assertThat(results.stream().filter(n -> n.path("status").asText().equals("ISSUED"))).hasSize(7);
        assertThat(results.stream().filter(n -> n.path("status").asText().equals("REJECTED"))).hasSize(113);
        assertThat(results.stream().filter(n -> n.has("voucherCode")).map(n -> n.path("voucherCode").asText()).distinct()).hasSize(7);
        assertThat(inventory(campaign)).isEqualTo(7);
    }

    @Test
    void sameUserCanClaimIndependentlyAcrossCampaigns() throws Exception {
        String first = campaign(1, 500);
        String second = campaign(1, 500);
        List<JsonNode> results = finished(List.of(claim(first, "shared-user"), claim(second, "shared-user")));
        assertThat(results).allSatisfy(n -> assertThat(n.path("status").asText()).isEqualTo("ISSUED"));
        assertThat(results.get(0).path("voucherCode")).isNotEqualTo(results.get(1).path("voucherCode"));
    }

    @Test
    void acceptedClaimsResolveAfterCampaignEndsAndReplayStillWorks() throws Exception {
        Reply created = adminCampaign(Map.of("code", code(), "capacity", 1, "batchWindowMs", 5000,
                "endsAt", Instant.now().plusSeconds(2).toString()));
        assertThat(created.status()).isEqualTo(201);
        String campaign = created.body().path("id").asText();
        Reply admitted = claim(campaign, "early-user");
        JsonNode result = finished(List.of(admitted)).get(0);
        assertThat(result.path("status").asText()).isEqualTo("ISSUED");
        Reply replay = claim(campaign, "early-user");
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().path("requestId")).isEqualTo(result.path("requestId"));
        Reply late = claim(campaign, "new-user");
        assertThat(late.status()).isEqualTo(409);
        assertThat(late.body().path("code").asText()).isEqualTo("CAMPAIGN_ENDED");
    }

    @Test
    void laterHighScoreDoesNotDisplaceEarlierAwards() throws Exception {
        String campaign = campaign(1, 500);
        JsonNode first = finished(List.of(claim(campaign, "priority-1"))).get(0);
        JsonNode later = finished(List.of(claim(campaign, "priority-40"))).get(0);
        assertThat(first.path("status").asText()).isEqualTo("ISSUED");
        assertThat(later.path("status").asText()).isEqualTo("REJECTED");
    }

    @Test
    void campaignCannotAcceptClaimsBeforeItsStart() throws Exception {
        Reply created = adminCampaign(Map.of("code", code(), "capacity", 1,
                "startsAt", Instant.now().plusSeconds(60).toString()));
        assertThat(created.status()).isEqualTo(201);
        Reply early = claim(created.body().path("id").asText(), "too-early");
        assertThat(early.status()).isEqualTo(409);
        assertThat(early.body().path("code").asText()).isEqualTo("CAMPAIGN_NOT_STARTED");
    }

    @Test
    void failureAfterAnIntermediateChunkRollsBackWholeWindowAndRecovers() throws Exception {
        String campaign = campaign(5, 3000);
        String function = "test_fail_" + UUID.randomUUID().toString().replace("-", "");
        String trigger = function + "_trigger";
        double failuresBefore = send("GET", "/actuator/metrics/voucher.allocation.failures", null, true)
                .body().path("measurements").path(0).path("value").asDouble(0);
        jdbc.execute("""
                CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.campaign_id = '%s'::uuid AND
                        (SELECT count(*) FROM voucher WHERE campaign_id = NEW.campaign_id) >= 2 THEN
                        RAISE EXCEPTION 'injected failure after first processing chunk';
                    END IF;
                    RETURN NEW;
                END $$
                """.formatted(function, campaign));
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON voucher FOR EACH ROW EXECUTE FUNCTION " + function + "()");
        List<Reply> admissions;
        try {
            admissions = concurrent(20, n -> () -> claim(campaign, "priority-" + (n + 1)));
            assertThat(admissions.stream().map(r -> r.body().path("batchId").asText()).distinct()).hasSize(1);
            await().atMost(Duration.ofSeconds(12)).untilAsserted(() -> {
                double failuresNow = send("GET", "/actuator/metrics/voucher.allocation.failures", null, true)
                        .body().path("measurements").path(0).path("value").asDouble();
                assertThat(failuresNow).isGreaterThan(failuresBefore);
            });
            assertThat(inventory(campaign)).isZero();
            for (Reply admission : admissions) assertThat(result(admission).path("status").asText()).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM voucher WHERE campaign_id = ?", Integer.class, UUID.fromString(campaign))).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + trigger + " ON voucher");
            jdbc.execute("DROP FUNCTION IF EXISTS " + function + "()");
        }
        List<JsonNode> results = finished(admissions);
        for (int i = 0; i < results.size(); i++) {
            assertThat(results.get(i).path("status").asText()).isEqualTo(i >= 15 ? "ISSUED" : "REJECTED");
        }
        assertThat(inventory(campaign)).isEqualTo(5);
    }

    @Test
    void twoApplicationInstancesShareInventoryAndIdempotency() throws Exception {
        try (var second = new SpringApplicationBuilder(VoucherApplication.class).run(
                "--server.port=0", "--spring.datasource.url=" + TestDatabase.url(),
                "--spring.datasource.username=postgres", "--spring.datasource.password=postgres",
                "--voucher.admin.username=test-admin", "--voucher.admin.password=test-only-password",
                "--voucher.batching.processing-chunk-size=1", "--voucher.allocation.scan-interval-ms=40")) {
            int otherPort = ((ServletWebServerApplicationContext) second).getWebServer().getPort();
            String campaign = campaign(7, 1000);
            List<Reply> submissions = concurrent(80, n -> () -> sendAt(n % 2 == 0 ? port : otherPort,
                    "POST", "/api/v1/claims", Map.of("campaignId", campaign, "userId", "cross-instance-" + (n % 20)), false));
            for (Reply reply : submissions) assertThat(reply.status()).isIn(200, 202);
            assertThat(submissions.stream().map(r -> r.body().path("requestId").asText()).distinct()).hasSize(20);
            List<JsonNode> results = finished(submissions);
            assertThat(results.stream().filter(n -> n.path("status").asText().equals("ISSUED"))
                    .map(n -> n.path("voucherCode").asText()).distinct()).hasSize(7);
            assertThat(inventory(campaign)).isEqualTo(7);
        }
    }

    private String campaign(int capacity, int windowMs) throws Exception {
        Reply reply = adminCampaign(Map.of("code", code(), "capacity", capacity, "batchWindowMs", windowMs));
        assertThat(reply.status()).isEqualTo(201);
        return reply.body().path("id").asText();
    }

    private int inventory(String campaign) throws Exception {
        return send("GET", "/api/v1/admin/campaigns/" + campaign, null, true).body().path("issuedCount").asInt();
    }

    private Reply adminCampaign(Map<String, Object> body) throws Exception {
        return send("POST", "/api/v1/admin/campaigns", body, true);
    }

    private Reply claim(String campaign, String user) throws Exception {
        return send("POST", "/api/v1/claims", Map.of("campaignId", campaign, "userId", user), false);
    }

    private JsonNode result(Reply admission) throws Exception {
        return send("GET", "/api/v1/claims/" + admission.body().path("requestId").asText(), null, false).body();
    }

    private List<JsonNode> finished(List<Reply> admissions) {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            for (Reply admission : admissions) assertThat(result(admission).path("status").asText()).isIn("ISSUED", "REJECTED");
        });
        return admissions.stream().map(a -> {
            try { return result(a); } catch (Exception failure) { throw new RuntimeException(failure); }
        }).toList();
    }

    private List<Reply> concurrent(int count, java.util.function.IntFunction<Callable<Reply>> factory) throws Exception {
        var executor = Executors.newFixedThreadPool(Math.min(count, 24));
        try {
            List<Callable<Reply>> tasks = new ArrayList<>();
            for (int i = 0; i < count; i++) tasks.add(factory.apply(i));
            List<Reply> results = new ArrayList<>();
            for (var future : executor.invokeAll(tasks)) results.add(future.get());
            return results;
        } finally { executor.shutdownNow(); }
    }

    private Reply send(String method, String path, Object body, boolean admin) throws Exception {
        return sendAt(port, method, path, body, admin);
    }

    private Reply sendAt(int targetPort, String method, String path, Object body, boolean admin) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + targetPort + path))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json");
        if (admin) request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                "test-admin:test-only-password".getBytes(StandardCharsets.UTF_8)));
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body().isBlank() ? mapper.createObjectNode()
                : mapper.readTree(response.body()), response.headers());
    }

    private static String code() { return "TEST_" + UUID.randomUUID().toString().replace("-", "").toUpperCase(); }
    private record Reply(int status, JsonNode body, java.net.http.HttpHeaders headers) { }
}
