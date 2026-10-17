package com.aepsy.voucher.loyalty;

import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Map;

@Validated
@ConfigurationProperties("voucher.loyalty")
public record LoyaltyProperties(Map<String, @PositiveOrZero Long> points) {
    public LoyaltyProperties { points = points == null ? Map.of() : Map.copyOf(points); }
}
