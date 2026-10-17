package com.aepsy.voucher.common;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("voucher.admin")
public record AdminProperties(@NotBlank String username, @NotBlank String password) { }
