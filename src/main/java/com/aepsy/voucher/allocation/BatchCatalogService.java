package com.aepsy.voucher.allocation;

import com.aepsy.voucher.campaign.Campaign;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
public class BatchCatalogService {
    private final BatchRepository batches;
    private final BatchCreationService creation;
    public BatchCatalogService(BatchRepository batches, BatchCreationService creation) { this.batches = batches; this.creation = creation; }

    public ClaimBatch forTime(Campaign campaign, Instant now) {
        long sequence = Duration.between(campaign.startsAt(), now).toMillis() / campaign.batchWindowMs();
        Instant opens = campaign.startsAt().plusMillis(Math.multiplyExact(sequence, campaign.batchWindowMs()));
        Instant proposedEnd = opens.plusMillis(campaign.batchWindowMs());
        Instant closes = proposedEnd.isBefore(campaign.endsAt()) ? proposedEnd : campaign.endsAt();
        return batches.find(campaign.id(), sequence).orElseGet(() -> creation.ensure(campaign, sequence, opens, closes));
    }
}
