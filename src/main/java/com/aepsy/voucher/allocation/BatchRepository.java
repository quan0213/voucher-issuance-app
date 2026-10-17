package com.aepsy.voucher.allocation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class BatchRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<ClaimBatch> MAPPER = (row, ignored) -> new ClaimBatch(
            row.getObject("id", UUID.class), row.getObject("campaign_id", UUID.class), row.getLong("sequence_no"),
            row.getTimestamp("opens_at").toInstant(), row.getTimestamp("closes_at").toInstant(), row.getString("status"));

    public BatchRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<ClaimBatch> find(UUID campaign, long sequence) {
        return jdbc.query("SELECT * FROM claim_batch WHERE campaign_id = ? AND sequence_no = ?", MAPPER, campaign, sequence).stream().findFirst();
    }

    public ClaimBatch lockForAdmission(UUID batch) {
        return jdbc.query("SELECT * FROM claim_batch WHERE id = ? FOR SHARE", MAPPER, batch).stream().findFirst().orElseThrow();
    }

    public Optional<ClaimBatch> lockEarliestOpen(UUID campaign) {
        return jdbc.query("""
                SELECT * FROM claim_batch WHERE campaign_id = ? AND status = 'OPEN'
                ORDER BY sequence_no ASC LIMIT 1 FOR UPDATE
                """, MAPPER, campaign).stream().findFirst();
    }

    public List<UUID> dueCampaigns() {
        return jdbc.query("""
                SELECT campaign_id FROM claim_batch
                WHERE status = 'OPEN' AND closes_at <= clock_timestamp()
                GROUP BY campaign_id ORDER BY min(closes_at) LIMIT 100
                """, (row, ignored) -> row.getObject(1, UUID.class));
    }
}
