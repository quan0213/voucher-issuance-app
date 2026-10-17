package com.aepsy.voucher.claim;

import com.aepsy.voucher.allocation.BatchCatalogService;
import com.aepsy.voucher.campaign.CampaignService;
import com.aepsy.voucher.common.ApiException;
import com.aepsy.voucher.common.DatabaseClock;
import com.aepsy.voucher.loyalty.LoyaltyProvider;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class ClaimSubmissionService {
    private final ClaimRepository claims;
    private final CampaignService campaigns;
    private final LoyaltyProvider loyalty;
    private final BatchCatalogService catalog;
    private final ClaimAdmissionService admission;
    private final DatabaseClock clock;
    private final MeterRegistry metrics;

    public ClaimSubmissionService(ClaimRepository claims, CampaignService campaigns, LoyaltyProvider loyalty,
                                  BatchCatalogService catalog, ClaimAdmissionService admission, DatabaseClock clock,
                                  MeterRegistry metrics) {
        this.claims = claims; this.campaigns = campaigns; this.loyalty = loyalty; this.catalog = catalog;
        this.admission = admission; this.clock = clock; this.metrics = metrics;
    }

    public ClaimResult submit(UUID campaignId, String user) {
        var existing = claims.find(campaignId, user);
        if (existing.isPresent()) {
            metrics.counter("voucher.claim.replays").increment();
            return existing.get();
        }
        var campaign = campaigns.get(campaignId);
        campaigns.requireOpen(campaign, clock.now());
        long points = loyalty.pointsFor(user);
        if (points < 0) throw ApiException.unavailable();
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                var now = clock.now();
                campaigns.requireOpen(campaign, now);
                var batch = catalog.forTime(campaign, now);
                ClaimResult result = admission.admitOneWindow(campaign, batch, user, points);
                if (result != null) {
                    metrics.counter("voucher.claim.submissions").increment();
                    return result;
                }
            } catch (TransientDataAccessException transientFailure) {
                metrics.counter("voucher.claim.retries").increment();
            }
            try { Thread.sleep(ThreadLocalRandom.current().nextLong(10, 40)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw ApiException.unavailable(); }
        }
        throw ApiException.unavailable();
    }

    public ClaimResult get(UUID requestId) {
        return claims.find(requestId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CLAIM_NOT_FOUND", "Claim not found."));
    }
}
