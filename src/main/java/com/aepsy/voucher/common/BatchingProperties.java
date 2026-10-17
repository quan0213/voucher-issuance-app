package com.aepsy.voucher.common;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("voucher.batching")
public record BatchingProperties(@Min(100) @Max(10000) int windowMs,
                                 @Min(1) @Max(10000) int processingChunkSize) { }
