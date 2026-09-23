#!/usr/bin/env python3
"""Apply the LNbits container advisory gate to a Trivy JSON image report."""

import argparse
import json
import re
import sys
from pathlib import Path


IMAGE_PATTERN = re.compile(r"lnbits/lnbits@sha256:[0-9a-f]{64}\Z")
SEVERITIES = {"UNKNOWN", "LOW", "MEDIUM", "HIGH", "CRITICAL"}
BLOCKING_SEVERITIES = {"HIGH", "CRITICAL"}


class InvalidReport(ValueError):
    """The scanner output cannot support a complete enforcement decision."""


def unique_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise InvalidReport(f"duplicate JSON field: {key}")
        result[key] = value
    return result


def reject_constant(value):
    raise InvalidReport(f"nonstandard JSON constant: {value}")


def require_nonempty_string(value, field):
    if not isinstance(value, str) or not value.strip():
        raise InvalidReport(f"missing or invalid {field}")
    return value


def inspect_report(report, image):
    if not isinstance(report, dict):
        raise InvalidReport("report must be a JSON object")
    if type(report.get("SchemaVersion")) is not int or report["SchemaVersion"] != 2:
        raise InvalidReport("unsupported or missing Trivy schema version")
    if report.get("ArtifactType") != "container_image":
        raise InvalidReport("report is not a container image scan")
    if report.get("ArtifactName") != image:
        raise InvalidReport("report image does not match the requested manifest")
    scanner = report.get("Trivy")
    if not isinstance(scanner, dict):
        raise InvalidReport("scanner identity is missing")
    require_nonempty_string(scanner.get("Version"), "Trivy version")
    artifact_id = report.get("ArtifactID")
    if not isinstance(artifact_id, str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", artifact_id):
        raise InvalidReport("scanned artifact ID is missing or invalid")
    metadata = report.get("Metadata")
    if not isinstance(metadata, dict) or metadata.get("Reference") != image:
        raise InvalidReport("scanned artifact reference does not match the requested manifest")
    results = report.get("Results")
    if not isinstance(results, list) or not results:
        raise InvalidReport("missing or empty scan results")

    findings = 0
    unfixed = 0
    blocking = []
    os_packages = 0
    python_packages = 0
    for result_number, result in enumerate(results, 1):
        if not isinstance(result, dict):
            raise InvalidReport(f"result {result_number} is not an object")
        for field in ("Target", "Class", "Type"):
            require_nonempty_string(result.get(field), f"result {result_number} {field}")
        packages = result.get("Packages")
        if not isinstance(packages, list):
            raise InvalidReport(f"result {result_number} is missing package inventory")
        for package in packages:
            if not isinstance(package, dict):
                raise InvalidReport(f"result {result_number} contains an invalid package")
            require_nonempty_string(package.get("Name"), "package Name")
            require_nonempty_string(package.get("Version"), "package Version")
        if result["Class"] == "os-pkgs":
            os_packages += len(packages)
        if result["Class"] == "lang-pkgs" and result["Type"] == "python-pkg":
            python_packages += len(packages)
        vulnerabilities = result.get("Vulnerabilities", [])
        if not isinstance(vulnerabilities, list):
            raise InvalidReport(f"result {result_number} vulnerabilities are invalid")
        for vulnerability_number, vulnerability in enumerate(vulnerabilities, 1):
            if not isinstance(vulnerability, dict):
                raise InvalidReport(f"vulnerability {vulnerability_number} is not an object")
            for field in ("VulnerabilityID", "PkgName", "InstalledVersion"):
                require_nonempty_string(vulnerability.get(field), field)
            severity = require_nonempty_string(vulnerability.get("Severity"), "Severity")
            if severity not in SEVERITIES:
                raise InvalidReport(f"invalid severity: {severity}")
            fixed_version = vulnerability.get("FixedVersion", "")
            if not isinstance(fixed_version, str):
                raise InvalidReport("FixedVersion must be a string")
            findings += 1
            if fixed_version.strip() and severity in BLOCKING_SEVERITIES:
                blocking.append(vulnerability["VulnerabilityID"])
            elif not fixed_version.strip():
                unfixed += 1
    if not os_packages or not python_packages:
        raise InvalidReport("LNbits report requires nonempty OS and Python package inventories")
    return findings, unfixed, blocking


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True, help="expected digest-pinned platform manifest")
    parser.add_argument("report", type=Path, help="Trivy JSON report")
    args = parser.parse_args()
    if not IMAGE_PATTERN.fullmatch(args.image):
        parser.error("--image must be an LNbits manifest digest reference")

    try:
        with args.report.open(encoding="utf-8") as stream:
            report = json.load(
                stream, object_pairs_hook=unique_keys, parse_constant=reject_constant
            )
        findings, unfixed, blocking = inspect_report(report, args.image)
    except (OSError, UnicodeError, json.JSONDecodeError, InvalidReport) as error:
        print(f"Container scan report invalid: {error}", file=sys.stderr)
        return 2

    print(f"Container scan: {findings} findings, {unfixed} unfixed, {len(blocking)} fixable HIGH/CRITICAL")
    if blocking:
        print(f"Blocking vulnerability IDs: {', '.join(sorted(set(blocking)))}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
