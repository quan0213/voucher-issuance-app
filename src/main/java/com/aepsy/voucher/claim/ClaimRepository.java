package com.aepsy.voucher.claim;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class ClaimRepository {
    private final JdbcTemplate jdbc;
    private static final String RESULT_SQL = """
            SELECT r.id, r.campaign_id, r.batch_id, r.status, r.rejection_reason, v.code
            FROM claim_request r LEFT JOIN voucher v ON v.claim_request_id = r.id
            """;
    private static final RowMapper<ClaimResult> MAPPER = (row, ignored) -> new ClaimResult(
            row.getObject("id", UUID.class), row.getObject("campaign_id", UUID.class), row.getObject("batch_id", UUID.class),
            row.getString("status"), row.getString("code"), row.getString("rejection_reason"));

    public ClaimRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Optional<ClaimResult> find(UUID campaign, String user) {
        return jdbc.query(RESULT_SQL + " WHERE r.campaign_id = ? AND r.user_id = ?", MAPPER, campaign, user).stream().findFirst();
    }
    public Optional<ClaimResult> find(UUID request) {
        return jdbc.query(RESULT_SQL + " WHERE r.id = ?", MAPPER, request).stream().findFirst();
    }
}
