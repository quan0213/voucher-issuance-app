CREATE TABLE campaign (
    id UUID PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE CHECK (code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    capacity INTEGER NOT NULL CHECK (capacity > 0),
    issued_count INTEGER NOT NULL DEFAULT 0 CHECK (issued_count >= 0 AND issued_count <= capacity),
    batch_window_ms INTEGER NOT NULL CHECK (batch_window_ms BETWEEN 100 AND 10000),
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL CHECK (ends_at > starts_at),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE claim_batch (
    id UUID PRIMARY KEY,
    campaign_id UUID NOT NULL REFERENCES campaign(id),
    sequence_no BIGINT NOT NULL CHECK (sequence_no >= 0),
    opens_at TIMESTAMPTZ NOT NULL,
    closes_at TIMESTAMPTZ NOT NULL CHECK (closes_at > opens_at),
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'COMPLETED')),
    completed_at TIMESTAMPTZ,
    UNIQUE (campaign_id, sequence_no),
    UNIQUE (id, campaign_id),
    CHECK ((status = 'OPEN' AND completed_at IS NULL) OR (status = 'COMPLETED' AND completed_at IS NOT NULL))
);

CREATE TABLE claim_request (
    id UUID PRIMARY KEY,
    campaign_id UUID NOT NULL,
    user_id VARCHAR(128) NOT NULL CHECK (length(user_id) > 0 AND user_id = btrim(user_id)),
    batch_id UUID NOT NULL,
    loyalty_points_snapshot BIGINT NOT NULL CHECK (loyalty_points_snapshot >= 0),
    accepted_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ISSUED', 'REJECTED')),
    rejection_reason VARCHAR(32),
    resolved_at TIMESTAMPTZ,
    UNIQUE (campaign_id, user_id),
    UNIQUE (id, campaign_id, user_id),
    FOREIGN KEY (batch_id, campaign_id) REFERENCES claim_batch(id, campaign_id),
    CHECK ((status = 'PENDING' AND rejection_reason IS NULL AND resolved_at IS NULL)
        OR (status = 'ISSUED' AND rejection_reason IS NULL AND resolved_at IS NOT NULL)
        OR (status = 'REJECTED' AND rejection_reason IS NOT NULL AND rejection_reason = 'SOLD_OUT' AND resolved_at IS NOT NULL))
);

CREATE TABLE voucher (
    id UUID PRIMARY KEY,
    campaign_id UUID NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    claim_request_id UUID NOT NULL UNIQUE,
    code VARCHAR(128) NOT NULL UNIQUE,
    issued_at TIMESTAMPTZ NOT NULL,
    UNIQUE (campaign_id, user_id),
    FOREIGN KEY (claim_request_id, campaign_id, user_id) REFERENCES claim_request(id, campaign_id, user_id)
);

CREATE INDEX claim_batch_due_idx ON claim_batch (closes_at, campaign_id) WHERE status = 'OPEN';
CREATE INDEX claim_batch_open_order_idx ON claim_batch (campaign_id, sequence_no) WHERE status = 'OPEN';
CREATE INDEX claim_request_pending_rank_idx
    ON claim_request (batch_id, loyalty_points_snapshot DESC, accepted_at ASC, id ASC) WHERE status = 'PENDING';
