#!/usr/bin/env python3
"""Pay a BOLT11 invoice with Breez SDK Spark and print shell variables.

This helper is intentionally small and strict because its output is consumed by
the L402/MPP smoke tests. It never prints secrets or full payment material to
stderr; successful output is limited to PAYMENT_HASH, PREIMAGE, and FEE_SATS.
"""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from uuid import uuid4


BREEZ_PACKAGE_NAME = "breez_sdk_spark"
HEX_32_RE = re.compile(r"^[0-9a-f]{64}$")


class PayBreezSparkError(RuntimeError):
    """Raised for user-facing payment failures."""


@dataclass(frozen=True)
class BreezConfig:
    api_key: str
    mnemonic: str
    network: str
    storage_dir: str


@dataclass(frozen=True)
class PaymentProof:
    payment_hash: str
    preimage: str
    fee_sats: int


def load_config(env: dict[str, str]) -> BreezConfig:
    api_key = env.get("BREEZ_API_KEY", "").strip()
    mnemonic = env.get("BREEZ_MNEMONIC", "").strip()
    network = env.get("BREEZ_NETWORK", "MAINNET").strip().upper()
    storage_dir = env.get("BREEZ_STORAGE_DIR", ".breez-spark-payer").strip()

    if not api_key:
        raise PayBreezSparkError("BREEZ_API_KEY is required")
    if not mnemonic:
        raise PayBreezSparkError("BREEZ_MNEMONIC is required")
    if network not in {"MAINNET", "REGTEST"}:
        raise PayBreezSparkError("BREEZ_NETWORK must be MAINNET or REGTEST")
    if not storage_dir:
        raise PayBreezSparkError("BREEZ_STORAGE_DIR must not be blank")

    return BreezConfig(
        api_key=api_key,
        mnemonic=mnemonic,
        network=network,
        storage_dir=str(Path(storage_dir).expanduser()),
    )


def validate_hex_32(name: str, value: str | None) -> str:
    normalized = (value or "").strip().lower()
    if not HEX_32_RE.match(normalized):
        raise PayBreezSparkError(f"{name} must be 64 lowercase hex characters")
    return normalized


def verify_preimage(payment_hash: str, preimage: str) -> None:
    expected = hashlib.sha256(bytes.fromhex(preimage)).hexdigest()
    if expected != payment_hash:
        raise PayBreezSparkError(
            "Breez returned an unusable preimage: "
            f"sha256(preimage)={expected} does not match payment_hash={payment_hash}"
        )


def require_bolt11_method(payment_method: Any) -> int:
    is_bolt11 = getattr(payment_method, "is_BOLT11_INVOICE", None)
    if not callable(is_bolt11) or not is_bolt11():
        raise PayBreezSparkError("Breez prepare response was not a BOLT11 invoice payment")

    fee_sats = getattr(payment_method, "lightning_fee_sats", None)
    if fee_sats is None:
        raise PayBreezSparkError("Breez BOLT11 prepare response did not include lightning_fee_sats")
    return int(fee_sats)


def extract_payment_hash(prepare_response: Any, payment: Any) -> str:
    payment_method = getattr(prepare_response, "payment_method", None)
    invoice_details = getattr(payment_method, "invoice_details", None)
    invoice_payment_hash = getattr(invoice_details, "payment_hash", None)
    if invoice_payment_hash:
        return validate_hex_32("payment_hash", invoice_payment_hash)

    details = getattr(payment, "details", None)
    htlc_details = getattr(details, "htlc_details", None)
    htlc_payment_hash = getattr(htlc_details, "payment_hash", None)
    if htlc_payment_hash:
        return validate_hex_32("payment_hash", htlc_payment_hash)

    raise PayBreezSparkError("Breez payment result did not include a payment hash")


def extract_preimage(payment: Any) -> str:
    details = getattr(payment, "details", None)
    htlc_details = getattr(details, "htlc_details", None)
    preimage = getattr(htlc_details, "preimage", None)
    return validate_hex_32("preimage", preimage)


def ensure_completed(sdk_module: Any, payment: Any) -> None:
    status = getattr(payment, "status", None)
    completed = getattr(getattr(sdk_module, "PaymentStatus", object), "COMPLETED", None)
    if status != completed:
        raise PayBreezSparkError(f"Breez payment did not complete within timeout: status={status}")


async def pay_invoice(
    sdk_module: Any,
    config: BreezConfig,
    invoice: str,
    max_fee_sats: int,
    completion_timeout_seconds: int,
) -> PaymentProof:
    network = getattr(sdk_module.Network, config.network)
    sdk_config = sdk_module.default_config(network=network)
    sdk_config.api_key = config.api_key
    seed = sdk_module.Seed.MNEMONIC(mnemonic=config.mnemonic, passphrase=None)

    sdk = await sdk_module.connect(
        sdk_module.ConnectRequest(config=sdk_config, seed=seed, storage_dir=config.storage_dir)
    )

    prepare_response = await sdk.prepare_send_payment(
        sdk_module.PrepareSendPaymentRequest(
            payment_request=sdk_module.PaymentRequest.INPUT(input=invoice),
            amount=None,
            token_identifier=None,
            conversion_options=None,
            fee_policy=None,
        )
    )

    fee_sats = require_bolt11_method(prepare_response.payment_method)
    if fee_sats > max_fee_sats:
        raise PayBreezSparkError(
            f"Breez quoted fee {fee_sats} sats exceeds max fee {max_fee_sats} sats"
        )

    send_response = await sdk.send_payment(
        sdk_module.SendPaymentRequest(
            prepare_response=prepare_response,
            options=sdk_module.SendPaymentOptions.BOLT11_INVOICE(
                prefer_spark=False,
                completion_timeout_secs=completion_timeout_seconds,
            ),
            idempotency_key=str(uuid4()),
        )
    )

    payment = send_response.payment
    ensure_completed(sdk_module, payment)
    payment_hash = extract_payment_hash(prepare_response, payment)
    preimage = extract_preimage(payment)
    verify_preimage(payment_hash, preimage)
    return PaymentProof(payment_hash=payment_hash, preimage=preimage, fee_sats=fee_sats)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Pay a BOLT11 invoice with Breez SDK Spark")
    parser.add_argument("--invoice", required=True, help="BOLT11 invoice to pay")
    parser.add_argument(
        "--max-fee-sats",
        type=int,
        default=10,
        help="Maximum allowed Lightning routing fee in sats",
    )
    parser.add_argument(
        "--completion-timeout-seconds",
        type=int,
        default=30,
        help="How long Breez should wait for a completed payment response",
    )
    return parser


async def async_main(argv: list[str]) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    if args.max_fee_sats < 0:
        raise PayBreezSparkError("--max-fee-sats must be >= 0")
    if args.completion_timeout_seconds <= 0:
        raise PayBreezSparkError("--completion-timeout-seconds must be > 0")

    config = load_config(os.environ)

    try:
        sdk_module = __import__(BREEZ_PACKAGE_NAME)
    except ImportError as exc:
        raise PayBreezSparkError(
            "breez-sdk-spark is not installed. Use pay-breez-spark-invoice.sh "
            "or install breez-sdk-spark==0.17.1 in the active Python environment."
        ) from exc

    proof = await pay_invoice(
        sdk_module=sdk_module,
        config=config,
        invoice=args.invoice,
        max_fee_sats=args.max_fee_sats,
        completion_timeout_seconds=args.completion_timeout_seconds,
    )

    print(f"PAYMENT_HASH={proof.payment_hash}")
    print(f"PREIMAGE={proof.preimage}")
    print(f"FEE_SATS={proof.fee_sats}")
    return 0


def main(argv: list[str]) -> int:
    try:
        return asyncio.run(async_main(argv))
    except PayBreezSparkError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    except Exception as exc:
        print(f"ERROR: Breez SDK payment failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
