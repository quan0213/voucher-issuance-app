#!/usr/bin/env python3
"""Real HTTP smoke/load test using only Python's standard library."""
import argparse
import asyncio
import base64
from collections import Counter
import json
import math
from pathlib import Path
import resource
import sys
import time
import uuid
import zlib
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parent.parent


async def run(args):
    settings_file = ROOT / '.local/settings.json'
    if not settings_file.exists():
        raise RuntimeError('Run make up before the live API test.')
    settings = json.loads(settings_file.read_text())
    address = urlsplit(args.base_url or f"http://127.0.0.1:{settings['port']}")
    if address.scheme != 'http':
        raise ValueError('This local test supports http:// endpoints only.')
    host, port = address.hostname, address.port or 80
    auth = base64.b64encode(f"{settings['adminUsername']}:{settings['adminPassword']}".encode()).decode()
    semaphore = asyncio.Semaphore(args.concurrency)

    async def request(method, path, payload=None, admin=False):
        async with semaphore:
            started = time.perf_counter()
            reader, writer = await asyncio.wait_for(asyncio.open_connection(host, port), timeout=30)
            try:
                body = b'' if payload is None else json.dumps(payload).encode()
                headers = [f'{method} {path} HTTP/1.1', f'Host: {host}:{port}', 'Connection: close',
                           'Content-Type: application/json', f'Content-Length: {len(body)}']
                if admin:
                    headers.append('Authorization: Basic ' + auth)
                writer.write(('\r\n'.join(headers) + '\r\n\r\n').encode() + body)
                await writer.drain()
                head = (await asyncio.wait_for(reader.readuntil(b'\r\n\r\n'), timeout=45)).decode('iso-8859-1')
                lines = head.split('\r\n')
                status = int(lines[0].split()[1])
                response_headers = dict(line.split(': ', 1) for line in lines[1:] if ': ' in line)
                response_headers = {key.lower(): value for key, value in response_headers.items()}
                if response_headers.get('transfer-encoding', '').lower() == 'chunked':
                    chunks = []
                    while True:
                        size = int((await reader.readline()).split(b';', 1)[0].strip(), 16)
                        if size == 0:
                            await reader.readline()
                            break
                        chunks.append(await reader.readexactly(size))
                        await reader.readexactly(2)
                    data = b''.join(chunks)
                elif 'content-length' in response_headers:
                    data = await reader.readexactly(int(response_headers['content-length']))
                else:
                    data = await reader.read()
                return status, json.loads(data) if data else {}, time.perf_counter() - started
            finally:
                writer.close()
                await writer.wait_closed()

    soft, hard = resource.getrlimit(resource.RLIMIT_NOFILE)
    required = args.concurrency * 2 + 256
    if soft < required:
        resource.setrlimit(resource.RLIMIT_NOFILE, (min(hard, required), hard))

    status, campaign, _ = await request('POST', '/api/v1/admin/campaigns', {
        'code': 'API_' + uuid.uuid4().hex.upper(), 'capacity': args.capacity, 'batchWindowMs': args.window_ms
    }, admin=True)
    assert status == 201, (status, campaign)
    campaign_id = campaign['id']
    users = []
    scores = set()
    prefix = uuid.uuid4().hex[:8]
    n = 0
    while len(users) < args.users:
        user = f'api-{prefix}-{n}'
        n += 1
        score = zlib.crc32(user.encode()) % 10000
        if args.check_priority and score in scores:
            continue
        users.append(user)
        scores.add(score)

    started = time.perf_counter()
    admissions = await asyncio.gather(*(request('POST', '/api/v1/claims', {'campaignId': campaign_id, 'userId': user})
                                        for user in users), return_exceptions=True)
    admission_seconds = time.perf_counter() - started
    failures = [f'{type(reply).__name__}: {reply}' if isinstance(reply, Exception) else f'HTTP {reply[0]} {reply[1].get("code", "")}'
                for reply in admissions if isinstance(reply, Exception) or reply[0] not in (200, 202)]
    http_counts = Counter('transport_error' if isinstance(reply, Exception) else str(reply[0]) for reply in admissions)
    if failures:
        breakdown = Counter(type(reply).__name__ for reply in admissions if isinstance(reply, Exception))
        raise AssertionError(f'{len(failures)} admission failures; HTTP counts={dict(http_counts)}; transport errors={dict(breakdown)}; examples={failures[:3]}')
    ids = [reply[1]['requestId'] for reply in admissions]
    assert len(set(ids)) == args.users, 'Distinct users must have distinct request IDs.'
    batches = {reply[1]['batchId'] for reply in admissions}
    if args.check_priority:
        assert len(batches) == 1, 'Priority demonstration requires one cohort; increase --window-ms on a slow machine.'

    deadline = time.monotonic() + 120
    resolved = {}
    while len(resolved) < args.users:
        pending = [request_id for request_id in ids if request_id not in resolved]
        replies = await asyncio.gather(*(request('GET', '/api/v1/claims/' + request_id) for request_id in pending))
        for request_id, (status, body, _) in zip(pending, replies):
            assert status == 200, (status, body)
            if body['status'] != 'PENDING':
                resolved[request_id] = body
        if len(resolved) < args.users:
            assert time.monotonic() < deadline, 'Timed out waiting for durable results.'
            await asyncio.sleep(1)
    winners = [i for i, request_id in enumerate(ids) if resolved[request_id]['status'] == 'ISSUED']
    assert len(winners) == min(args.capacity, args.users), 'Inventory allocation mismatch.'
    voucher_codes = {resolved[ids[i]]['voucherCode'] for i in winners}
    assert len(voucher_codes) == len(winners), 'Duplicate voucher code.'
    for i, request_id in enumerate(ids):
        if i not in winners:
            assert resolved[request_id]['reason'] == 'SOLD_OUT'
    if args.check_priority:
        ranked = sorted(range(len(users)), key=lambda i: zlib.crc32(users[i].encode()) % 10000, reverse=True)
        assert set(winners) == set(ranked[:args.capacity]), 'Loyalty ordering was not preserved.'
    _, inventory, _ = await request('GET', '/api/v1/admin/campaigns/' + campaign_id, admin=True)
    assert inventory['issuedCount'] == len(winners)
    replay_status, replay, _ = await request('POST', '/api/v1/claims', {'campaignId': campaign_id, 'userId': users[0]})
    assert replay_status == 200 and replay['requestId'] == ids[0], 'Retry created a new logical request.'
    durations = sorted(reply[2] * 1000 for reply in admissions)
    percentile = lambda p: round(durations[min(len(durations) - 1, math.ceil(p * len(durations)) - 1)], 2)
    report = {'result': 'PASS', 'users': args.users, 'maximumInFlight': args.concurrency,
              'capacity': args.capacity, 'windowMs': args.window_ms, 'cohorts': len(batches),
              'httpStatusCounts': dict(http_counts), 'issued': len(winners), 'rejected': args.users - len(winners),
              'admissionSeconds': round(admission_seconds, 3), 'admissionHttpMs': {
                  'p50': percentile(.5), 'p95': percentile(.95), 'p99': percentile(.99)},
              'totalSeconds': round(time.perf_counter() - started, 3),
              'globalPriorityWithinOneCohortChecked': args.check_priority}
    report_dir = ROOT / 'target/reports'
    report_dir.mkdir(parents=True, exist_ok=True)
    (report_dir / f'api-{args.users}.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--users', type=int, default=20)
    parser.add_argument('--capacity', type=int, default=5)
    parser.add_argument('--concurrency', type=int, default=20)
    parser.add_argument('--window-ms', type=int, default=5000)
    parser.add_argument('--check-priority', action='store_true')
    parser.add_argument('--base-url')
    arguments = parser.parse_args()
    if arguments.users < 1 or arguments.capacity < 1 or arguments.concurrency < 1:
        parser.error('users, capacity, and concurrency must be positive')
    try:
        asyncio.run(run(arguments))
    except (AssertionError, RuntimeError, ValueError, OSError) as failure:
        print(f'FAIL: {failure}', file=sys.stderr)
        sys.exit(1)
