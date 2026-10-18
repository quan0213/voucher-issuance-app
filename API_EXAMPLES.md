# API Examples

Run these sections in order, in the same Bash or Zsh terminal, from the repository root. Requirements are listed in [README](README.md). Commands read the generated local credentials and carry IDs between steps, so there are no IDs to replace manually.

The response examples below were captured from a successful local run on October 18, 2026. IDs, timestamps, voucher codes, and trace IDs change on each run.

## 1 Start the application

This starts PostgreSQL and the API and loads the URL and admin credentials into shell variables without printing the password.

```bash
make up

BASE_URL=$(python3 -c 'import json; s=json.load(open(".local/settings.json")); print("http://127.0.0.1:" + str(s["port"]))')
ADMIN_USER=$(python3 -c 'import json; print(json.load(open(".local/settings.json"))["adminUsername"])')
ADMIN_PASSWORD=$(python3 -c 'import json; print(json.load(open(".local/settings.json"))["adminPassword"])')
```

## 2 Check readiness

`GET /actuator/health/readiness` confirms the API and database are ready.

```bash
curl --fail --silent --show-error "$BASE_URL/actuator/health/readiness" | python3 -m json.tool
```

Expected HTTP status: **200**.

```json
{
  "status": "UP"
}
```

## 3 Create a campaign

`POST /api/v1/admin/campaigns` requires admin Basic authentication. This campaign has one voucher and a 500 ms collection window. A fresh code is generated on each run.

```bash
CAMPAIGN_CODE="DEMO_$(python3 -c 'import uuid; print(uuid.uuid4().hex[:12].upper())')"
CAMPAIGN_RESPONSE=$(curl --fail --silent --show-error \
  --user "$ADMIN_USER:$ADMIN_PASSWORD" \
  --header 'Content-Type: application/json' \
  --data "{\"code\":\"$CAMPAIGN_CODE\",\"capacity\":1,\"batchWindowMs\":500}" \
  "$BASE_URL/api/v1/admin/campaigns")
printf '%s\n' "$CAMPAIGN_RESPONSE" | python3 -m json.tool
CAMPAIGN_ID=$(printf '%s' "$CAMPAIGN_RESPONSE" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
```

Expected HTTP status: **201**.

```json
{
  "id": "b23400e4-db15-4e1c-90f4-41a292a50a3c",
  "code": "DEMO_0E3D0100B4A8",
  "capacity": 1,
  "issuedCount": 0,
  "batchWindowMs": 500,
  "startsAt": "2026-10-18T04:46:43.008031Z",
  "endsAt": "2026-10-19T04:46:43.008031Z"
}
```

## 4 Submit the first claim

`POST /api/v1/claims` is public. Admission returns `202` and a durable request ID; the voucher is allocated after the window closes.

```bash
CLAIM_RESPONSE=$(curl --fail --silent --show-error \
  --header 'Content-Type: application/json' \
  --data "{\"campaignId\":\"$CAMPAIGN_ID\",\"userId\":\"demo-user-1\"}" \
  "$BASE_URL/api/v1/claims")
printf '%s\n' "$CLAIM_RESPONSE" | python3 -m json.tool
REQUEST_ID=$(printf '%s' "$CLAIM_RESPONSE" | python3 -c 'import json,sys; print(json.load(sys.stdin)["requestId"])')
```

Expected HTTP status: **202**.

```json
{
  "requestId": "e1a3a578-35fb-4db2-8a29-67758c8e13e0",
  "campaignId": "b23400e4-db15-4e1c-90f4-41a292a50a3c",
  "batchId": "9db9cb7d-5d7a-4262-9a84-0097135a4a6a",
  "status": "PENDING"
}
```

The response also includes `Location: /api/v1/claims/{requestId}` and `Retry-After: 1`. Public claim requests contain only `campaignId` and `userId`; loyalty is resolved by the server.

## 5 Poll until the voucher is issued

`GET /api/v1/claims/{requestId}` returns `200` for both pending and completed claims. This loop repeats the GET until the state is final, for up to about 20 seconds plus HTTP time.

```bash
for attempt in {1..100}; do
  RESULT=$(curl --fail --silent --show-error "$BASE_URL/api/v1/claims/$REQUEST_ID")
  STATUS=$(printf '%s' "$RESULT" | python3 -c 'import json,sys; print(json.load(sys.stdin)["status"])')
  if [ "$STATUS" != "PENDING" ]; then break; fi
  sleep 0.2
done
printf '%s\n' "$RESULT" | python3 -m json.tool
test "$STATUS" != "PENDING"
```

Expected HTTP status: **200**.

```json
{
  "requestId": "e1a3a578-35fb-4db2-8a29-67758c8e13e0",
  "campaignId": "b23400e4-db15-4e1c-90f4-41a292a50a3c",
  "batchId": "9db9cb7d-5d7a-4262-9a84-0097135a4a6a",
  "status": "ISSUED",
  "voucherCode": "VC-ae9e139534a249e9b3c4bb818aa90721"
}
```

## 6 Repeat the same claim

Repeat the first POST with the same campaign and user. A completed replay returns `200`, the same request ID, and the same voucher code; it does not consume another voucher.

```bash
curl --fail --silent --show-error \
  --header 'Content-Type: application/json' \
  --data "{\"campaignId\":\"$CAMPAIGN_ID\",\"userId\":\"demo-user-1\"}" \
  "$BASE_URL/api/v1/claims" | python3 -m json.tool
```

Expected HTTP status: **200**.

```json
{
  "requestId": "e1a3a578-35fb-4db2-8a29-67758c8e13e0",
  "campaignId": "b23400e4-db15-4e1c-90f4-41a292a50a3c",
  "batchId": "9db9cb7d-5d7a-4262-9a84-0097135a4a6a",
  "status": "ISSUED",
  "voucherCode": "VC-ae9e139534a249e9b3c4bb818aa90721"
}
```

## 7 Submit another user after stock is exhausted

The first user has taken the only voucher. A new claim during the active campaign is still accepted as `PENDING`; it is resolved through the same window-based flow.

```bash
SECOND_RESPONSE=$(curl --fail --silent --show-error \
  --header 'Content-Type: application/json' \
  --data "{\"campaignId\":\"$CAMPAIGN_ID\",\"userId\":\"demo-user-2\"}" \
  "$BASE_URL/api/v1/claims")
printf '%s\n' "$SECOND_RESPONSE" | python3 -m json.tool
SECOND_REQUEST_ID=$(printf '%s' "$SECOND_RESPONSE" | python3 -c 'import json,sys; print(json.load(sys.stdin)["requestId"])')
```

Expected HTTP status: **202**.

```json
{
  "requestId": "1e255254-6795-4268-ab7b-5c10003710ed",
  "campaignId": "b23400e4-db15-4e1c-90f4-41a292a50a3c",
  "batchId": "1b619f9e-654c-46f3-80cf-83ed1a29984c",
  "status": "PENDING"
}
```

## 8 Poll the sold-out result

Query the second claim until it resolves. A stored rejection returns HTTP `200` with `status: REJECTED` and `reason: SOLD_OUT`. Inventory does not reset for a later window.

```bash
for attempt in {1..100}; do
  RESULT=$(curl --fail --silent --show-error "$BASE_URL/api/v1/claims/$SECOND_REQUEST_ID")
  STATUS=$(printf '%s' "$RESULT" | python3 -c 'import json,sys; print(json.load(sys.stdin)["status"])')
  if [ "$STATUS" != "PENDING" ]; then break; fi
  sleep 0.2
done
printf '%s\n' "$RESULT" | python3 -m json.tool
test "$STATUS" != "PENDING"
```

Expected HTTP status: **200**.

```json
{
  "requestId": "1e255254-6795-4268-ab7b-5c10003710ed",
  "campaignId": "b23400e4-db15-4e1c-90f4-41a292a50a3c",
  "batchId": "1b619f9e-654c-46f3-80cf-83ed1a29984c",
  "status": "REJECTED",
  "reason": "SOLD_OUT"
}
```

## 9 Inspect campaign inventory

`GET /api/v1/admin/campaigns/{id}` requires admin Basic authentication. Only one voucher was issued, even after the duplicate POST and second user.

```bash
curl --fail --silent --show-error \
  --user "$ADMIN_USER:$ADMIN_PASSWORD" \
  "$BASE_URL/api/v1/admin/campaigns/$CAMPAIGN_ID" | python3 -m json.tool
```

Expected HTTP status: **200**.

```json
{
  "id": "b23400e4-db15-4e1c-90f4-41a292a50a3c",
  "code": "DEMO_0E3D0100B4A8",
  "capacity": 1,
  "issuedCount": 1,
  "batchWindowMs": 500,
  "startsAt": "2026-10-18T04:46:43.008031Z",
  "endsAt": "2026-10-19T04:46:43.008031Z"
}
```

## 10 Try an invalid request

An empty `userId` returns `400`. This example omits curl's `--fail` so the error response is printed normally.

```bash
curl --silent --show-error \
  --header 'Content-Type: application/json' \
  --data "{\"campaignId\":\"$CAMPAIGN_ID\",\"userId\":\"\"}" \
  "$BASE_URL/api/v1/claims" | python3 -m json.tool
```

Expected HTTP status: **400**.

```json
{
  "message": "Check the request fields and their formats.",
  "traceId": "07071973-1de4-4d00-8da3-147846b2cc7e",
  "code": "INVALID_REQUEST"
}
```

## 11 Verify concurrent loyalty priority

The single-user walkthrough above demonstrates all business endpoints. For simultaneous competition, run:

```bash
make smoke
```

This submits 20 users against five vouchers in one 5-second test window. It asserts that the five highest loyalty scores win, all voucher codes are unique, the inventory count matches, and duplicate claims return the original result. The test window is longer than the application's 500 ms default so the demo can gather all 20 users into one window.

The JSON report must contain `result: PASS`, `issued: 5`, `rejected: 15`, and `globalPriorityWithinOneCohortChecked: true`. It is saved in `target/reports/api-20.json`.

To inspect headers for a manual request, add `--include` to curl and remove the `python3 -m json.tool` pipe. After changing a variable or restarting the terminal, rerun from section 1. Use `make down` to stop the local services when finished.

For field constraints and the complete status-code contract, see [API contracts](TECH_SPEC.md#api-contracts). For batch queries and transaction sequences, see [Architecture](ARCHITECTURE.md).
