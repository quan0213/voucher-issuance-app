package com.aepsy.voucher.allocation;

import com.aepsy.voucher.campaign.Campaign;
import com.aepsy.voucher.campaign.CampaignRepository;
import com.aepsy.voucher.common.BatchingProperties;
import com.aepsy.voucher.common.DatabaseClock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class BatchAllocationService {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final CampaignRepository campaigns;
    private final BatchRepository batches;
    private final DatabaseClock clock;
    private final BatchingProperties configuration;

    public BatchAllocationService(JdbcTemplate jdbc, CampaignRepository campaigns, BatchRepository batches,
                                   DatabaseClock clock, BatchingProperties configuration) {
        this.jdbc = jdbc; this.named = new NamedParameterJdbcTemplate(jdbc); this.campaigns = campaigns;
        this.batches = batches; this.clock = clock; this.configuration = configuration;
    }

    public record AllocationResult(UUID campaignId, UUID batchId, int issued) { }
    private record Candidate(UUID id, String userId) { }

    /** One transaction per whole cohort; chunks never become separate fairness groups. */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 30)
    public Optional<AllocationResult> allocateNextBatch(UUID campaignId) {
        jdbc.execute("SET LOCAL lock_timeout = '2000ms'");
        Optional<Campaign> locked = campaigns.lockForAllocation(campaignId);
        if (locked.isEmpty()) return Optional.empty();
        Campaign campaign = locked.get();
        Optional<ClaimBatch> selected = batches.lockEarliestOpen(campaignId);
        if (selected.isEmpty()) return Optional.empty();
        ClaimBatch batch = selected.get();
        Instant now = clock.now();
        if (now.isBefore(batch.closesAt())) return Optional.empty();

        int remaining = campaign.capacity() - campaign.issuedCount();
        int winners = 0;
        int chunkSize = configuration.processingChunkSize();
        while (remaining > 0) {
            // This fresh statement sees admissions committed while the batch lock was waiting.
            List<Candidate> chunk = pending(batch.id(), Math.min(chunkSize, remaining));
            if (chunk.isEmpty()) break;
            issue(campaign, chunk, now);
            resolve(chunk, "ISSUED", now);
            remaining -= chunk.size();
            winners += chunk.size();
        }
        while (true) {
            List<Candidate> losers = pending(batch.id(), chunkSize);
            if (losers.isEmpty()) break;
            resolve(losers, "REJECTED", now);
        }
        int changed = jdbc.update("""
                UPDATE campaign SET issued_count = issued_count + ?
                WHERE id = ? AND issued_count <= capacity - ?
                """, winners, campaignId, winners);
        require(changed == 1, "Campaign inventory update failed");
        require(jdbc.update("""
                UPDATE claim_batch SET status = 'COMPLETED', completed_at = clock_timestamp()
                WHERE id = ? AND status = 'OPEN'
                """, batch.id()) == 1, "Batch completion failed");
        return Optional.of(new AllocationResult(campaignId, batch.id(), winners));
    }

    private List<Candidate> pending(UUID batch, int limit) {
        return jdbc.query("""
                SELECT id, user_id FROM claim_request WHERE batch_id = ? AND status = 'PENDING'
                ORDER BY loyalty_points_snapshot DESC, accepted_at ASC, id ASC LIMIT ?
                """, (row, ignored) -> new Candidate(row.getObject("id", UUID.class), row.getString("user_id")), batch, limit);
    }

    private void issue(Campaign campaign, List<Candidate> candidates, Instant now) {
        int[] counts = jdbc.batchUpdate("""
                INSERT INTO voucher (id, campaign_id, user_id, claim_request_id, code, issued_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, candidates.stream().map(candidate -> new Object[]{UUID.randomUUID(), campaign.id(),
                candidate.userId(), candidate.id(), "VC-" + UUID.randomUUID().toString().replace("-", ""), Timestamp.from(now)}).toList());
        require(counts.length == candidates.size(), "Incomplete voucher insert batch");
        for (int count : counts) require(count == 1 || count == Statement.SUCCESS_NO_INFO, "Voucher insert failed");
    }

    private void resolve(List<Candidate> candidates, String status, Instant now) {
        String reason = status.equals("REJECTED") ? "SOLD_OUT" : null;
        Map<String, Object> parameters = new java.util.HashMap<>();
        parameters.put("ids", candidates.stream().map(Candidate::id).toList());
        parameters.put("status", status);
        parameters.put("reason", reason);
        parameters.put("resolved", Timestamp.from(now));
        int changed = named.update("""
                UPDATE claim_request SET status = :status, rejection_reason = :reason, resolved_at = :resolved
                WHERE id IN (:ids) AND status = 'PENDING'
                """, parameters);
        require(changed == candidates.size(), "Request result update failed");
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalStateException(message);
    }
}
