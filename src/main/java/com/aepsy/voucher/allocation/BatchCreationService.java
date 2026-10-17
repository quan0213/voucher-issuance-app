package com.aepsy.voucher.allocation;

import com.aepsy.voucher.campaign.Campaign;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Commits the catalog row before any request may join the cohort. */
@Service
public class BatchCreationService {
    private final JdbcTemplate jdbc;
    private final BatchRepository batches;
    public BatchCreationService(JdbcTemplate jdbc, BatchRepository batches) { this.jdbc = jdbc; this.batches = batches; }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, timeout = 5)
    public ClaimBatch ensure(Campaign campaign, long sequence, Instant opens, Instant closes) {
        jdbc.update("""
                INSERT INTO claim_batch (id, campaign_id, sequence_no, opens_at, closes_at)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (campaign_id, sequence_no) DO NOTHING
                """, UUID.randomUUID(), campaign.id(), sequence, Timestamp.from(opens), Timestamp.from(closes));
        return batches.find(campaign.id(), sequence).orElseThrow();
    }
}
