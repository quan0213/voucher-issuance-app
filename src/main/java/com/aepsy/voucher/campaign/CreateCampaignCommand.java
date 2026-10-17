package com.aepsy.voucher.campaign;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

public record CreateCampaignCommand(
        @NotNull @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,63}$") String code,
        @NotNull @Positive Integer capacity,
        @Min(100) @Max(10000) Integer batchWindowMs,
        Instant startsAt,
        Instant endsAt) { }
