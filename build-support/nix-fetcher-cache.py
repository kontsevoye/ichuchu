#!/usr/bin/env python3
"""Reuse only hash/time metadata for audited public locked GitHub flake inputs."""

import argparse
import json
from pathlib import Path
import re
import sqlite3

PUBLIC_INPUTS = {
    ('NixOS', 'nixpkgs'), ('numtide', 'devshell'), ('numtide', 'flake-utils'),
    ('tadfisher', 'android-nixpkgs'), ('mitchellh', 'zig-overlay'),
    ('nix-systems', 'default'), ('edolstra', 'flake-compat'),
}
DOMAINS = {'gitRevToTreeHash': 'treeHash', 'gitRevToLastModified': 'lastModified'}
SCHEMA = '''CREATE TABLE IF NOT EXISTS Cache (
    domain TEXT NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL,
    timestamp INTEGER NOT NULL, PRIMARY KEY(domain, key))'''


def revisions(lock):
    result = set()
    for node in json.loads(lock.read_text())['nodes'].values():
        value = node.get('locked')
        if not value:
            continue
        if (value.get('type') != 'github' or value.get('host', 'github.com') != 'github.com'
                or (value.get('owner'), value.get('repo')) not in PUBLIC_INPUTS):
            raise ValueError('Fetcher caching is restricted to audited public GitHub inputs')
        if not re.fullmatch('[0-9a-f]{40}', value.get('rev', '')):
            raise ValueError('Unsupported locked revision format')
        result.add(value['rev'])
    return result


def validate(entry, allowed):
    if set(entry) != {'domain', 'key', 'value', 'timestamp'}:
        raise ValueError('Unexpected fetcher metadata fields')
    domain, key, value, timestamp = (entry[x] for x in ('domain', 'key', 'value', 'timestamp'))
    if domain not in DOMAINS or not isinstance(key, dict) or set(key) != {'rev'} or key['rev'] not in allowed:
        raise ValueError('Unexpected fetcher key')
    field = DOMAINS[domain]
    if not isinstance(value, dict) or set(value) != {field}:
        raise ValueError('Unexpected fetcher value fields')
    if field == 'treeHash' and not (isinstance(value[field], str) and re.fullmatch('[0-9a-f]{40}', value[field])):
        raise ValueError('Unexpected tree hash')
    if field == 'lastModified' and not (type(value[field]) is int and 0 <= value[field] < 2**63):
        raise ValueError('Unexpected modification time')
    if not (type(timestamp) is int and 0 <= timestamp < 2**63):
        raise ValueError('Unexpected cache timestamp')
    return entry


def export_metadata(database, bundle, allowed):
    entries = {}
    if database.is_file():
        with sqlite3.connect(f'file:{database}?mode=ro', uri=True) as db:
            for domain, key, value, timestamp in db.execute(
                    'SELECT domain,key,value,timestamp FROM Cache WHERE domain IN (?,?)', tuple(DOMAINS)):
                try:
                    entry = dict(domain=domain, key=json.loads(key), value=json.loads(value), timestamp=timestamp)
                    entry = validate(entry, allowed)
                    identity = (domain, entry['key']['rev'])
                    if identity not in entries or timestamp > entries[identity]['timestamp']:
                        entries[identity] = entry
                except (ValueError, TypeError):
                    continue
    bundle.parent.mkdir(parents=True, exist_ok=True)
    # A fresh JSON file cannot retain deleted SQLite pages, URLs, headers or other cache domains.
    bundle.write_text(json.dumps({'format': 1, 'entries': [entries[key] for key in sorted(entries)]}, sort_keys=True, separators=(',', ':')) + '\n')
    return len(entries)


def import_metadata(database, bundle, allowed):
    if not bundle.is_file():
        return 0
    if bundle.stat().st_size > 1024 * 1024:
        raise ValueError('Oversized fetcher metadata bundle')
    data = json.loads(bundle.read_text())
    if set(data) != {'format', 'entries'} or data['format'] != 1 or not isinstance(data['entries'], list):
        raise ValueError('Unexpected fetcher metadata format')
    if len(data['entries']) > 2 * len(allowed):
        raise ValueError('Too many fetcher metadata entries')
    entries = [validate(entry, allowed) for entry in data['entries']]
    database.parent.mkdir(parents=True, exist_ok=True)
    compact = lambda value: json.dumps(value, sort_keys=True, separators=(',', ':'))
    with sqlite3.connect(database) as db:
        db.execute(SCHEMA)
        db.executemany('INSERT OR REPLACE INTO Cache(domain,key,value,timestamp) VALUES (?,?,?,?)',
                       [(e['domain'], compact(e['key']), compact(e['value']), e['timestamp']) for e in entries])
    return len(entries)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['validate', 'import', 'export'])
    parser.add_argument('--lock', type=Path, default=Path('flake.lock'))
    parser.add_argument('--database', type=Path)
    parser.add_argument('--bundle', type=Path)
    args = parser.parse_args()
    try:
        allowed = revisions(args.lock)
        if args.mode == 'validate':
            print(f'Validated {len(allowed)} public locked revisions')
        else:
            if args.database is None or args.bundle is None:
                parser.error('--database and --bundle required')
            function = import_metadata if args.mode == 'import' else export_metadata
            print(f'Fetcher metadata {args.mode}: {function(args.database, args.bundle, allowed)} hash/time entries')
    except (ValueError, TypeError, KeyError, sqlite3.Error):
        raise SystemExit('Invalid or unsupported public fetcher cache; no metadata contents logged')
