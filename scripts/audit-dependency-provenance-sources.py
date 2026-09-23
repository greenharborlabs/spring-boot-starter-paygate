#!/usr/bin/env python3
"""Manually verify every dependency-provenance exception against its source bytes."""

import argparse
import csv
import hashlib
import sys
import urllib.error
import urllib.request
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.parse import urlsplit

REPOSITORIES = {
    "repo1.maven.org": "/maven2/",
    "plugins.gradle.org": "/m2/",
}


def read_exceptions(path):
    with path.open(encoding="utf-8", newline="") as stream:
        rows = [row for row in csv.reader(stream, delimiter="\t") if row and not row[0].startswith("#")]
    if not rows or any(len(row) != 11 for row in rows):
        raise ValueError("exception ledger is empty or has an invalid row")
    return rows


def verify(row):
    exception_id, group, module, version, filename, expected_sha, url, *_ = row
    parsed = urlsplit(url)
    coordinate_path = f"{group.replace('.', '/')}/{module}/{version}/{filename}"
    if (
        parsed.scheme != "https"
        or parsed.netloc not in REPOSITORIES
        or parsed.path != REPOSITORIES[parsed.hostname] + coordinate_path
        or parsed.query
        or parsed.fragment
    ):
        return exception_id, "invalid source URL", 0
    if len(expected_sha) != 64 or any(character not in "0123456789abcdef" for character in expected_sha):
        return exception_id, "invalid SHA-256", 0

    digest = hashlib.sha256()
    size = 0
    request = urllib.request.Request(url, headers={"User-Agent": "paygate-provenance-audit/1.0"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            if urlsplit(response.url).scheme != "https":
                return exception_id, "source redirected away from HTTPS", 0
            while block := response.read(1024 * 1024):
                digest.update(block)
                size += len(block)
    except (OSError, urllib.error.URLError) as error:
        return exception_id, f"source fetch failed: {error}", 0
    if digest.hexdigest() != expected_sha:
        return exception_id, "artifact SHA-256 differs from ledger", size
    return exception_id, "match", size


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ledger", type=Path, default=Path("config/dependency-provenance-exceptions.tsv"))
    args = parser.parse_args()
    try:
        rows = read_exceptions(args.ledger)
    except (OSError, ValueError) as error:
        print(f"Provenance audit failed: {error}", file=sys.stderr)
        return 2
    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(verify, rows))
    failures = [(exception_id, status) for exception_id, status, _ in results if status != "match"]
    counts = Counter(urlsplit(row[6]).hostname for row in rows)
    print(
        f"Provenance audit: {len(rows)} exceptions, {len(results) - len(failures)} SHA-256 matches, "
        f"{sum(size for _, _, size in results)} bytes, sources {dict(counts)}"
    )
    for exception_id, status in failures:
        print(f"{exception_id}: {status}", file=sys.stderr)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
