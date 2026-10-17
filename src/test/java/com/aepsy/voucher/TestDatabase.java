package com.aepsy.voucher;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Real PostgreSQL 17, isolated from the developer's existing databases. */
final class TestDatabase {
    private static final EmbeddedPostgres POSTGRES = start();

    private TestDatabase() { }

    private static EmbeddedPostgres start() {
        try {
            EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                    .setServerConfig("listen_addresses", "127.0.0.1")
                    .setServerConfig("max_connections", "100")
                    .setPort(0).start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { postgres.close(); } catch (Exception ignored) { }
            }));
            return postgres;
        } catch (Exception failure) {
            throw new IllegalStateException("Could not start isolated PostgreSQL 17", failure);
        }
    }

    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://127.0.0.1:" + POSTGRES.getPort() + "/postgres");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("voucher.admin.username", () -> "test-admin");
        registry.add("voucher.admin.password", () -> "test-only-password");
        for (int n = 1; n <= 40; n++) {
            int score = n * 100;
            registry.add("voucher.loyalty.points.priority-" + n, () -> score);
        }
    }
}
