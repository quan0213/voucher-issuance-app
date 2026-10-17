package com.aepsy.voucher.campaign;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CampaignRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<Campaign> MAPPER = (row, ignored) -> new Campaign(
            row.getObject("id", UUID.class), row.getString("code"), row.getInt("capacity"),
            row.getInt("issued_count"), row.getInt("batch_window_ms"),
            row.getTimestamp("starts_at").toInstant(), row.getTimestamp("ends_at").toInstant());

    public CampaignRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Campaign> find(UUID id) {
        return jdbc.query("SELECT * FROM campaign WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public Optional<Campaign> lockForAllocation(UUID id) {
        return jdbc.query("SELECT * FROM campaign WHERE id = ? FOR NO KEY UPDATE SKIP LOCKED", MAPPER, id)
                .stream().findFirst();
    }

    public boolean insert(Campaign campaign) {
        return jdbc.update("""
                INSERT INTO campaign (id, code, capacity, issued_count, batch_window_ms, starts_at, ends_at)
                VALUES (?, ?, ?, 0, ?, ?, ?) ON CONFLICT (code) DO NOTHING
                """, campaign.id(), campaign.code(), campaign.capacity(), campaign.batchWindowMs(),
                Timestamp.from(campaign.startsAt()), Timestamp.from(campaign.endsAt())) == 1;
    }
}
