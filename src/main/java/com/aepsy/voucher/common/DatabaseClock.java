package com.aepsy.voucher.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

@Component
public class DatabaseClock {
    private final JdbcTemplate jdbc;
    public DatabaseClock(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Instant now() { return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant(); }
}
