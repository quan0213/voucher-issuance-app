package com.aepsy.voucher.claim;

import com.aepsy.voucher.allocation.BatchRepository;
import com.aepsy.voucher.allocation.ClaimBatch;
import com.aepsy.voucher.campaign.Campaign;
import com.aepsy.voucher.campaign.CampaignService;
import com.aepsy.voucher.common.DatabaseClock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Service
public class ClaimAdmissionService {
    private final JdbcTemplate jdbc;
    private final ClaimRepository claims;
    private final BatchRepository batches;
    private final CampaignService campaigns;
    private final DatabaseClock clock;

    public ClaimAdmissionService(JdbcTemplate jdbc, ClaimRepository claims, BatchRepository batches,
                                 CampaignService campaigns, DatabaseClock clock) {
        this.jdbc = jdbc; this.claims = claims; this.batches = batches; this.campaigns = campaigns; this.clock = clock;
    }

    /** Null means that the caller must select the current window in a new transaction. */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public ClaimResult admitOneWindow(Campaign campaign, ClaimBatch candidate, String user, long points) {
        var existing = claims.find(campaign.id(), user);
        if (existing.isPresent()) return existing.get();
        jdbc.execute("SET LOCAL lock_timeout = '1500ms'");
        ClaimBatch batch = batches.lockForAdmission(candidate.id());
        Instant now = clock.now();
        campaigns.requireOpen(campaign, now);
        if (!batch.status().equals("OPEN") || now.isBefore(batch.opensAt()) || !now.isBefore(batch.closesAt())) return null;
        jdbc.update("""
                INSERT INTO claim_request (id, campaign_id, user_id, batch_id, loyalty_points_snapshot, accepted_at)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (campaign_id, user_id) DO NOTHING
                """, UUID.randomUUID(), campaign.id(), user, batch.id(), points, Timestamp.from(now));
        // A new READ COMMITTED statement also sees a competing insert that committed during ON CONFLICT.
        return claims.find(campaign.id(), user).orElseThrow();
    }
}
