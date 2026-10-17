package com.aepsy.voucher.loyalty;

public interface LoyaltyProvider {
    long pointsFor(String userId);
}
