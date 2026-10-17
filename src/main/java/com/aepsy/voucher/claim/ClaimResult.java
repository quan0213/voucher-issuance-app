package com.aepsy.voucher.claim;

import java.util.UUID;

public record ClaimResult(UUID requestId, UUID campaignId, UUID batchId, String status, String voucherCode, String reason) { }
