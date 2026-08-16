#!/usr/bin/env python3
"""Verify a published Central deployment against retained release evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import time
import urllib.error
import urllib.parse
import urllib.request


SHA256_LINE = re.compile(r"^([0-9a-f]{64})\s+(.+)$")


def fail(message: str) -> None:
    raise SystemExit(f"Central publication verification failed: {message}")


def purl_version(purl: str) -> str:
    parsed = urllib.parse.urlsplit(purl)
    if parsed.scheme != "pkg" or not parsed.path.startswith("maven/"):
        fail("deployment returned a non-Maven package URL")
    _, separator, version = parsed.path.rpartition("@")
    if not separator or not version:
        fail("deployment returned a Maven package URL without a version")
    return urllib.parse.unquote(version)


def load_status(path: pathlib.Path, version: str) -> None:
    try:
        status = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"could not read Central status JSON: {error}")
    if not isinstance(status, dict) or status.get("deploymentState") != "PUBLISHED":
        fail("Central deployment is not PUBLISHED")

    purls = status.get("purls") or []
    if not isinstance(purls, list) or not all(isinstance(purl, str) for purl in purls):
        fail("Central deployment returned malformed package URLs")
    for purl in purls:
        if purl_version(purl) != version:
            fail("Central deployment contains a different component version")
    if not purls:
        print("Central status omitted package URLs; verifying every staged byte instead")


def load_staging_hashes(evidence_dir: pathlib.Path) -> dict[str, str]:
    manifest = evidence_dir / "SHA256SUMS"
    try:
        lines = manifest.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        fail(f"could not read SHA256SUMS: {error}")

    staging_hashes: dict[str, str] = {}
    for line in lines:
        match = SHA256_LINE.fullmatch(line)
        if not match:
            fail("SHA256SUMS contains a malformed entry")
        digest, relative_name = match.groups()
        relative = pathlib.PurePosixPath(relative_name)
        if relative.is_absolute() or ".." in relative.parts:
            fail("SHA256SUMS contains an unsafe path")
        prefix = "staging-repository/"
        if relative_name.startswith(prefix):
            central_name = relative_name.removeprefix(prefix)
            if not central_name or central_name in staging_hashes:
                fail("SHA256SUMS contains an invalid staged path")
            staging_hashes[central_name] = digest
    if not staging_hashes:
        fail("SHA256SUMS does not contain staged Maven files")
    return staging_hashes


def artifact_url(base_url: str, relative_name: str) -> str:
    encoded = "/".join(urllib.parse.quote(part, safe="") for part in relative_name.split("/"))
    return f"{base_url.rstrip('/')}/{encoded}"


def verify_bytes(
    staging_hashes: dict[str, str], base_url: str, attempts: int, delay_seconds: float
) -> None:
    pending = dict(staging_hashes)
    last_errors: dict[str, str] = {}
    for attempt in range(1, attempts + 1):
        for relative_name, expected_digest in list(pending.items()):
            try:
                with urllib.request.urlopen(artifact_url(base_url, relative_name), timeout=30) as response:
                    actual_digest = hashlib.sha256(response.read()).hexdigest()
            except (urllib.error.URLError, TimeoutError, OSError) as error:
                last_errors[relative_name] = str(error)
                continue
            if actual_digest != expected_digest:
                fail(f"published checksum differs for {relative_name}")
            pending.pop(relative_name)
            last_errors.pop(relative_name, None)

        if not pending:
            print(f"Verified {len(staging_hashes)} staged Maven files from Central")
            return
        if attempt < attempts:
            print(
                f"Waiting for {len(pending)} Central file(s) to become readable "
                f"(attempt {attempt} of {attempts})"
            )
            time.sleep(delay_seconds)

    names = ", ".join(sorted(pending))
    details = "; ".join(f"{name}: {last_errors.get(name, 'unavailable')}" for name in sorted(pending))
    fail(f"Central files did not become readable: {names} ({details})")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--status-json", type=pathlib.Path, required=True)
    parser.add_argument("--evidence-dir", type=pathlib.Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument(
        "--repository-url",
        default="https://repo.maven.apache.org/maven2",
    )
    parser.add_argument("--attempts", type=int, default=30)
    parser.add_argument("--delay-seconds", type=float, default=20)
    args = parser.parse_args()
    if args.attempts < 1 or args.delay_seconds < 0:
        parser.error("attempts must be positive and delay-seconds must be non-negative")
    return args


def main() -> None:
    args = parse_args()
    load_status(args.status_json, args.version)
    staging_hashes = load_staging_hashes(args.evidence_dir)
    verify_bytes(staging_hashes, args.repository_url, args.attempts, args.delay_seconds)


if __name__ == "__main__":
    main()
