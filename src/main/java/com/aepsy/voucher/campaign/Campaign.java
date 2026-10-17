package com.aepsy.voucher.campaign;

import java.time.Instant;
import java.util.UUID;

public record Campaign(UUID id, String code, int capacity, int issuedCount, int batchWindowMs,
                       Instant startsAt, Instant endsAt) { }
