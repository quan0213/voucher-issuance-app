package com.aepsy.voucher.campaign;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/campaigns")
public class CampaignController {
    private final CampaignService campaigns;
    public CampaignController(CampaignService campaigns) { this.campaigns = campaigns; }

    public record CreateCampaign(@NotNull @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,63}$") String code,
                                 @NotNull @Positive Integer capacity,
                                 @Min(100) @Max(10000) Integer batchWindowMs,
                                 Instant startsAt, Instant endsAt) { }

    @PostMapping
    ResponseEntity<Campaign> create(@RequestBody @Valid CreateCampaign request) {
        Campaign created = campaigns.create(request);
        return ResponseEntity.created(URI.create("/api/v1/admin/campaigns/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    Campaign get(@PathVariable UUID id) { return campaigns.get(id); }
}
