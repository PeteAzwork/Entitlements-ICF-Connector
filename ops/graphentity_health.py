#!/usr/bin/env python3
"""Health summary for the Graph Entity Service connector in IDM (plan WS9.03; runbook section 5).

Reports, for operations:
  * the connector's Test operation (system/graphentity?_action=test);
  * the latest reconciliation per graphentity mapping: state, duration, failures, and the orphan
    situations that are only REPORTed (UNASSIGNED, UNQUALIFIED, TARGET_IGNORED);
  * implicit-sync and reconciliation failures in the sync audit over the last --hours;
  * with --log FILE (IDM's log, e.g. logs/openidm0.log.0 or `docker logs` output): counts of the connector's
    own warnings - dropped relationships, failed verification reads, HTTP errors by status.

Credentials come from the environment, never from arguments: IDM_URL (default http://localhost:8080/openidm),
IDM_USER, IDM_PASSWORD. Exit status: 0 healthy, 1 attention needed, 2 could not check.
Standard library only.
"""
import argparse
import collections
import datetime
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

ORPHANS = ('UNASSIGNED', 'UNQUALIFIED', 'TARGET_IGNORED')


def call(base, method, path, body=None):
    request = urllib.request.Request(
        base.rstrip('/') + '/' + path, method=method,
        data=None if body is None else json.dumps(body).encode(),
        headers={'X-OpenIDM-Username': os.environ.get('IDM_USER', ''),
                 'X-OpenIDM-Password': os.environ.get('IDM_PASSWORD', ''),
                 'X-OpenIDM-NoSession': 'true', 'Content-Type': 'application/json', 'Accept': 'application/json'})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.loads(response.read() or b'null')


def query(base, resource, query_filter, **params):
    params['_queryFilter'] = query_filter
    return call(base, 'GET', resource + '?' + urllib.parse.urlencode(params)).get('result', [])


def log_signals(path):
    counts = collections.Counter()
    statuses = collections.Counter()
    with open(path, encoding='utf-8', errors='replace') as log:
        for line in log:
            if 'dropped edge ' in line:
                counts['dropped relationships (dropped edge …)'] += 1
            if 'verification GET failed' in line:
                counts['failed verification reads (C1)'] += 1
            m = re.search(r'HTTP (\d{3}) during (\w+)', line)
            if m:
                statuses[f'HTTP {m.group(1)} during {m.group(2)}'] += 1
    return counts, statuses


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--connector', default='graphentity')
    parser.add_argument('--hours', type=int, default=24, help='sync-audit window (default 24)')
    parser.add_argument('--log', help="IDM's log file, for the connector's warning counts")
    args = parser.parse_args()
    base = os.environ.get('IDM_URL', 'http://localhost:8080/openidm')
    if not os.environ.get('IDM_USER') or not os.environ.get('IDM_PASSWORD'):
        print('Set IDM_USER and IDM_PASSWORD in the environment.', file=sys.stderr)
        return 2
    attention = []

    try:
        test = call(base, 'POST', f'system/{args.connector}?_action=test', {})
        ok = test.get('ok') is True
        print(f"Connector test: {'ok' if ok else 'FAILED'}" + ('' if ok else f" ({test.get('error', 'no detail')})"))
        if not ok:
            attention.append('the connector Test fails')
    except urllib.error.HTTPError as e:
        print(f'Connector test: FAILED (HTTP {e.code})')
        attention.append('the connector Test fails')
    except OSError as e:
        print(f'Cannot reach IDM at {base}: {e}', file=sys.stderr)
        return 2

    print('\nLatest reconciliation per mapping:')
    try:
        recons = call(base, 'GET', 'recon').get('reconciliations', [])
    except urllib.error.HTTPError:
        recons = []
    latest = {}
    for r in recons:
        if f'_system{args.connector.capitalize()}' in r.get('mapping', '') and (
                r['mapping'] not in latest or r.get('started', '') > latest[r['mapping']].get('started', '')):
            latest[r['mapping']] = r
    if not latest:
        print('  none since IDM started (history is in audit/recon)')
    for mapping, r in sorted(latest.items()):
        situations = {k: v for k, v in (r.get('situationSummary') or {}).items() if v}
        failures = (r.get('statusSummary') or {}).get('FAILURE', 0)
        orphans = {k: situations[k] for k in ORPHANS if k in situations}
        print(f"  {mapping}: {r.get('state')} started {r.get('started')} ended {r.get('ended')}")
        print(f'    situations {situations}; failures {failures}; orphans reported {orphans or "none"}')
        if r.get('state') != 'SUCCESS' or failures:
            attention.append(f'{mapping}: state {r.get("state")}, {failures} failure(s)')

    since = (datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(hours=args.hours)).strftime('%Y-%m-%dT%H:%M:%S')
    print(f'\nSync failures in the last {args.hours} h:')
    try:
        failures = query(base, 'audit/sync', f'status eq "FAILURE" and timestamp gt "{since}"', _pageSize=1000,
                         _fields='mapping,situation,action,sourceObjectId,targetObjectId,message')
    except urllib.error.HTTPError as e:
        failures = []
        print(f'  could not read audit/sync (HTTP {e.code})')
    ours = [f for f in failures if f'_system{args.connector.capitalize()}' in (f.get('mapping') or '')]
    by_mapping = collections.Counter(f['mapping'] for f in ours)
    print('  none' if not ours else '\n'.join(f'  {m}: {n}' for m, n in by_mapping.items()))
    for f in ours[:5]:
        print(f"    e.g. {f.get('situation')} {f.get('action')} {f.get('sourceObjectId')}: {(f.get('message') or '')[:160]}")
    if ours:
        attention.append(f'{len(ours)} sync failure(s) in the last {args.hours} h')

    if args.log:
        counts, statuses = log_signals(args.log)
        print(f'\nConnector warnings in {args.log}:')
        for name, n in list(counts.items()) + list(statuses.items()):
            print(f'  {name}: {n}')
        if not counts and not statuses:
            print('  none')

    print('\nAttention: ' + ('; '.join(attention) if attention else 'nothing'))
    return 1 if attention else 0


if __name__ == '__main__':
    sys.exit(main())
