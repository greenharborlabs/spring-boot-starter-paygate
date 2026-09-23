#!/usr/bin/env python3
"""Validate exact Dependency-Check suppressions against approved risk records."""

import datetime as dt
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


class PolicyError(Exception):
    pass


NS = "https://jeremylong.github.io/DependencyCheck/dependency-suppression.1.3.xsd"
REQUIRED = (
    "Disposition", "Scope", "Owner", "Approval", "Approval date", "Status",
    "Review date", "Compensating controls", "Scanner evidence",
)
HEADING = re.compile(r"## (CVE-\d{4}-\d{4,}) — (\S.*)")
FIELD = re.compile(r"- ([A-Za-z][A-Za-z ]*):\s*(.*)")
DATE = re.compile(r"\d{4}-\d{2}-\d{2}")


def fail(message):
    raise PolicyError(message)


def read(path):
    try:
        return Path(path).read_text(encoding="utf-8")
    except (OSError, UnicodeError) as exc:
        fail(f"cannot read {path}: {exc.strerror if isinstance(exc, OSError) else 'invalid UTF-8'}")


def day(value, label):
    if not DATE.fullmatch(value):
        fail(f"{label} must be canonical YYYY-MM-DD: {value}")
    try:
        return dt.date.fromisoformat(value)
    except ValueError:
        fail(f"{label} is not a valid calendar date: {value}")


def setting(text, pattern, label):
    matches = re.findall(pattern, text, re.MULTILINE)
    if len(matches) != 1:
        fail(f"expected exactly one {label}; found {len(matches)}")
    return matches[0]


def parse_records(text):
    records = {}
    current = None
    last = None
    for number, line in enumerate(text.splitlines(), 1):
        if not line.strip():
            last = None
            continue
        if line.startswith("## "):
            match = HEADING.fullmatch(line)
            if not match:
                fail(f"Markdown line {number}: malformed advisory heading")
            cve = match.group(1)
            if cve in records:
                fail(f"duplicate advisory record: {cve}")
            current = {}
            records[cve] = current
            last = None
            continue
        if line.startswith("# ") and current is None:
            continue
        if current is None:
            fail(f"Markdown line {number}: content outside an advisory section")
        match = FIELD.fullmatch(line)
        if match:
            label, value = match.groups()
            if label not in (*REQUIRED, "Rationale"):
                fail(f"Markdown line {number}: unknown field {label}")
            if label in current:
                fail(f"duplicate {label} field in advisory record")
            current[label] = value.strip()
            last = label
        elif line.startswith("  ") and last is not None:
            current[last] += " " + line.strip()
        else:
            fail(f"Markdown line {number}: expected a labeled field or continuation")
    return records


def only_child(parent, name, cve):
    children = parent.findall(f"{{{NS}}}{name}")
    if len(children) != 1 or not (children[0].text or "").strip():
        fail(f"{cve}: expected exactly one {name}")
    if children[0].attrib:
        fail(f"{cve}: {name} must use an exact value, not a regex")
    return children[0].text.strip()


def validate(xml_path, md_path, build_path, properties_path, today):
    version = setting(read(build_path),
                      r'^\s*id\("org\.owasp\.dependencycheck"\)\s+version\s+"([^"]+)"',
                      "Dependency-Check plugin version in build.gradle.kts")
    project_version = setting(read(properties_path), r"^version=([^\s]+)$", "project version")
    expected_purl = f"pkg:maven/com.greenharborlabs/paygate-lightning-lnbits@{project_version}"
    records = parse_records(read(md_path))
    try:
        root = ET.fromstring(read(xml_path))
    except ET.ParseError as exc:
        fail(f"malformed suppression XML: {exc}")
    if root.tag != f"{{{NS}}}suppressions":
        fail("suppression XML must use the Dependency-Check suppression namespace")
    seen = set()
    for suppression in root:
        if suppression.tag != f"{{{NS}}}suppress":
            fail("suppression XML contains an unexpected element")
        cves = suppression.findall(f"{{{NS}}}cve")
        if len(cves) != 1 or not (cves[0].text or "").strip():
            fail("suppression must contain exactly one CVE advisory scope")
        cve = cves[0].text.strip()
        if not re.fullmatch(r"CVE-\d{4}-\d{4,}", cve):
            fail(f"invalid advisory scope: {cve}")
        if cve in seen:
            fail(f"ambiguous duplicate suppression for {cve}")
        seen.add(cve)
        if set(suppression.attrib) != {"until"} or cves[0].attrib:
            fail(f"{cve}: broad or ambiguous advisory scope")
        allowed = {f"{{{NS}}}{item}" for item in ("cve", "packageUrl", "notes")}
        if any(child.tag not in allowed for child in suppression):
            fail(f"{cve}: broad or ambiguous advisory scope")
        purl = only_child(suppression, "packageUrl", cve)
        if purl != expected_purl:
            fail(f"{cve}: broad or mismatched package URL; expected {expected_purl}")
        until = suppression.get("until")
        if until is None:
            fail(f"{cve}: missing until deadline")
        if not re.fullmatch(r"\d{4}-\d{2}-\d{2}Z", until):
            fail(f"{cve}: until must be canonical YYYY-MM-DDZ: {until}")
        deadline = day(until[:-1], "until")
        if deadline <= today:
            fail(f"{cve}: expired until deadline {until[:-1]}")
        if cve not in records:
            fail(f"{cve}: suppression has no matching risk record")
        fields = records[cve]
        for label in REQUIRED:
            if not fields.get(label):
                fail(f"{cve}: missing {label}")
        if fields["Scope"] != f"`{expected_purl}`":
            fail(f"{cve}: risk record Scope must name exact package URL {expected_purl}")
        if fields["Status"] != "Approved":
            fail(f"{cve}: Status must be Approved")
        if not re.search(r"(?:\b(?:PR|issue)\s*#\d+\b|https?://\S+|\b[0-9a-f]{12,40}\b)",
                         fields["Approval"], re.IGNORECASE):
            fail(f"{cve}: Approval must include a review reference (PR, issue, URL, or commit)")
        for label in ("Approval date", "Review date"):
            evidence_date = day(fields[label], label)
            if evidence_date > today:
                fail(f"{cve}: {label} cannot be in the future: {fields[label]}")
        if day(fields["Review date"], "Review date") < day(fields["Approval date"], "Approval date"):
            fail(f"{cve}: Review date precedes Approval date")
        scanner = re.search(r"\bDependency-Check\s+(\d+\.\d+\.\d+)\b", fields["Scanner evidence"])
        if not scanner or scanner.group(1) != version:
            fail(f"{cve}: stale Scanner evidence; expected Dependency-Check {version}")
    for cve in records:
        if cve not in seen:
            fail(f"{cve}: orphan risk record has no suppression")


def main():
    if len(sys.argv) != 5:
        fail("usage: validator.py SUPPRESSIONS RECORDS BUILD_GRADLE GRADLE_PROPERTIES")
    today = day(os.environ.get("VALIDATION_DATE", dt.datetime.now(dt.timezone.utc).date().isoformat()),
                "VALIDATION_DATE")
    validate(*sys.argv[1:], today)
    print("Dependency-check risk dispositions passed")


if __name__ == "__main__":
    try:
        main()
    except PolicyError as exc:
        print(f"dependency risk disposition: {exc}", file=sys.stderr)
        sys.exit(1)
