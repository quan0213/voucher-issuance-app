package com.aepsy.voucher.allocation;

import java.time.Instant;
import java.util.UUID;

public record ClaimBatch(UUID id, UUID campaignId, long sequenceNo, Instant opensAt, Instant closesAt, String status) { }
