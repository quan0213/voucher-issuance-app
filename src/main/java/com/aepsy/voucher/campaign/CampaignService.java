package com.aepsy.voucher.campaign;

import com.aepsy.voucher.common.ApiException;
import com.aepsy.voucher.common.BatchingProperties;
import com.aepsy.voucher.common.DatabaseClock;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class CampaignService {
    private final CampaignRepository campaigns;
    private final DatabaseClock clock;
    private final BatchingProperties batching;

    public CampaignService(CampaignRepository campaigns, DatabaseClock clock, BatchingProperties batching) {
        this.campaigns = campaigns;
        this.clock = clock;
        this.batching = batching;
    }

    @Transactional(timeout = 5)
    public Campaign create(CreateCampaignCommand request) {
        Instant now = clock.now();
        Instant starts = request.startsAt() == null ? now : request.startsAt();
        Instant ends = request.endsAt() == null ? starts.plus(Duration.ofDays(1)) : request.endsAt();
        if (!ends.isAfter(starts) || !ends.isAfter(now)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Campaign end must follow its start and creation time.");
        }
        Campaign campaign = new Campaign(UUID.randomUUID(), request.code(), request.capacity(), 0,
                request.batchWindowMs() == null ? batching.windowMs() : request.batchWindowMs(), starts, ends);
        if (!campaigns.insert(campaign)) {
            throw new ApiException(HttpStatus.CONFLICT, "CAMPAIGN_CODE_EXISTS", "That campaign code already exists.");
        }
        return campaigns.find(campaign.id()).orElseThrow();
    }

    public Campaign get(UUID id) {
        return campaigns.find(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CAMPAIGN_NOT_FOUND", "Campaign not found."));
    }

    public void requireOpen(Campaign campaign, Instant now) {
        if (now.isBefore(campaign.startsAt())) throw new ApiException(HttpStatus.CONFLICT, "CAMPAIGN_NOT_STARTED", "Campaign has not started.");
        if (!now.isBefore(campaign.endsAt())) throw new ApiException(HttpStatus.CONFLICT, "CAMPAIGN_ENDED", "Campaign has ended.");
    }
}
