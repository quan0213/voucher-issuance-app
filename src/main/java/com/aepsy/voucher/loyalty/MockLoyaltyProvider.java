package com.aepsy.voucher.loyalty;

import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

@Component
public class MockLoyaltyProvider implements LoyaltyProvider {
    private final LoyaltyProperties configuration;
    public MockLoyaltyProvider(LoyaltyProperties configuration) { this.configuration = configuration; }

    @Override
    public long pointsFor(String userId) {
        Long configured = configuration.points().get(userId);
        if (configured != null) return configured;
        CRC32 crc = new CRC32();
        crc.update(userId.getBytes(StandardCharsets.UTF_8));
        return crc.getValue() % 10000;
    }
}
