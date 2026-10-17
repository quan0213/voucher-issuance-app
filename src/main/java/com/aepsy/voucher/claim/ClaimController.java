package com.aepsy.voucher.claim;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/claims")
public class ClaimController {
    private final ClaimSubmissionService claims;
    public ClaimController(ClaimSubmissionService claims) { this.claims = claims; }

    public record SubmitClaim(@NotNull UUID campaignId,
                              @NotBlank @Size(max = 128) @Pattern(regexp = "(?s)\\S(?:.*\\S)?") String userId) { }

    @PostMapping
    ResponseEntity<ClaimResult> submit(@RequestBody @Valid SubmitClaim request) {
        ClaimResult result = claims.submit(request.campaignId(), request.userId());
        if (!result.status().equals("PENDING")) return ResponseEntity.ok(result);
        return ResponseEntity.accepted().location(URI.create("/api/v1/claims/" + result.requestId()))
                .header("Retry-After", "1").body(result);
    }

    @GetMapping("/{requestId}")
    ClaimResult get(@PathVariable UUID requestId) { return claims.get(requestId); }
}
