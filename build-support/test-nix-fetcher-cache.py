#!/usr/bin/env python3
"""Ensure cache optimization cannot persist URL/auth fields or import partial metadata."""

import importlib.util
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('fetcher', Path(__file__).with_name('nix-fetcher-cache.py'))
fetcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fetcher)


class FetcherCache(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.database = self.root / 'live.sqlite'
        self.bundle = self.root / 'public.json'
        self.rev = 'a' * 40
        self.allowed = {self.rev}
        self.entry = dict(domain='gitRevToTreeHash', key={'rev': self.rev}, value={'treeHash': 'b' * 40}, timestamp=100)

    def test_roundtrip_uses_nix_compact_keys(self):
        self.bundle.write_text(json.dumps({'format': 1, 'entries': [self.entry]}))
        self.assertEqual(fetcher.import_metadata(self.database, self.bundle, self.allowed), 1)
        with sqlite3.connect(self.database) as db:
            self.assertEqual(db.execute('SELECT key FROM Cache').fetchone()[0], '{"rev":"' + self.rev + '"}')
        self.assertEqual(fetcher.export_metadata(self.database, self.bundle, self.allowed), 1)
        self.assertEqual(json.loads(self.bundle.read_text())['entries'], [self.entry])

    def test_export_drops_credential_urls_headers_and_unlocked_revisions(self):
        with sqlite3.connect(self.database) as db:
            db.execute(fetcher.SCHEMA)
            for domain, key, value in [
                ('file', {'url': 'https://example.test/?token=SYNTHETIC_SECRET'}, {'url': 'secret'}),
                ('gitRevToTreeHash', {'rev': 'c' * 40}, {'treeHash': 'd' * 40}),
                ('gitRevToTreeHash', {'rev': self.rev}, {'treeHash': 'b' * 40, 'Authorization': 'SYNTHETIC_SECRET'}),
            ]:
                db.execute('INSERT INTO Cache VALUES (?,?,?,?)', (domain, json.dumps(key), json.dumps(value), 100))
        self.assertEqual(fetcher.export_metadata(self.database, self.bundle, self.allowed), 0)
        self.assertNotIn('SYNTHETIC_SECRET', self.bundle.read_text())
        self.assertNotIn('https', self.bundle.read_text())

    def test_import_rejects_unexpected_fields_before_any_database_write(self):
        bad = dict(self.entry, value={'treeHash': 'b' * 40, 'header': 'SYNTHETIC_SECRET'})
        self.bundle.write_text(json.dumps({'format': 1, 'entries': [self.entry, bad]}))
        with self.assertRaises(ValueError):
            fetcher.import_metadata(self.database, self.bundle, self.allowed)
        self.assertFalse(self.database.exists())

    def test_unknown_private_input_cannot_be_cached(self):
        lock = self.root / 'flake.lock'
        lock.write_text(json.dumps({'nodes': {'input': {'locked': {'type': 'github', 'owner': 'private-owner', 'repo': 'private', 'rev': self.rev}}}}))
        with self.assertRaises(ValueError):
            fetcher.revisions(lock)


if __name__ == '__main__':
    unittest.main()
