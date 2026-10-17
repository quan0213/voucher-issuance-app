package com.aepsy.voucher;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.concurrent.CountDownLatch;

/** Development-only launcher; excluded from the production application jar. */
public final class LocalPostgres {
    private LocalPostgres() { }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        Path root = Path.of(args[1]).toAbsolutePath();
        Files.createDirectories(root);
        EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                .setLocaleConfig("locale", "C")
                .setDataDirectory(root.resolve("postgres").toFile())
                .setCleanDataDirectory(false)
                .setServerConfig("listen_addresses", "127.0.0.1")
                .setServerConfig("max_connections", "100")
                .setServerConfig("fsync", "on")
                .setServerConfig("synchronous_commit", "on")
                .setServerConfig("full_page_writes", "on")
                .setPort(port).start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { postgres.close(); Files.deleteIfExists(root.resolve("db-ready")); }
            catch (Exception ignored) { }
        }));
        try (var connection = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + port + "/postgres", "postgres", "postgres")) {
            boolean exists;
            try (var query = connection.createStatement(); var rows = query.executeQuery("SELECT 1 FROM pg_database WHERE datname = 'vouchers'")) {
                exists = rows.next();
            }
            if (!exists) try (var create = connection.createStatement()) { create.execute("CREATE DATABASE vouchers"); }
        }
        Files.writeString(root.resolve("db-ready"), Integer.toString(port));
        System.out.println("LOCAL_DATABASE_READY port=" + port);
        new CountDownLatch(1).await();
    }
}
