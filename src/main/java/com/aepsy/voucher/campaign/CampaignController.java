package com.aepsy.voucher.campaign;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/campaigns")
public class CampaignController {
    private final CampaignService campaigns;
    public CampaignController(CampaignService campaigns) { this.campaigns = campaigns; }

    @PostMapping
    ResponseEntity<Campaign> create(@RequestBody @Valid CreateCampaignCommand request) {
        Campaign created = campaigns.create(request);
        return ResponseEntity.created(URI.create("/api/v1/admin/campaigns/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    Campaign get(@PathVariable UUID id) { return campaigns.get(id); }
}
