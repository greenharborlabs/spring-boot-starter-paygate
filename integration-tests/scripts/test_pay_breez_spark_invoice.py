#!/usr/bin/env python3
"""Unit tests for the Breez Spark payer helper."""

import hashlib
import importlib.util
import pathlib
import sys
import types
import unittest


SCRIPT = pathlib.Path(__file__).with_name("pay_breez_spark_invoice.py")
SPEC = importlib.util.spec_from_file_location("pay_breez_spark_invoice", SCRIPT)
helper = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = helper
SPEC.loader.exec_module(helper)


class HelperValidationTest(unittest.TestCase):
    def test_load_config_requires_secrets(self):
        with self.assertRaisesRegex(helper.PayBreezSparkError, "BREEZ_API_KEY"):
            helper.load_config({})
        with self.assertRaisesRegex(helper.PayBreezSparkError, "BREEZ_MNEMONIC"):
            helper.load_config({"BREEZ_API_KEY": "key"})

    def test_load_config_rejects_invalid_network(self):
        with self.assertRaisesRegex(helper.PayBreezSparkError, "BREEZ_NETWORK"):
            helper.load_config(
                {
                    "BREEZ_API_KEY": "key",
                    "BREEZ_MNEMONIC": "abandon " * 12,
                    "BREEZ_NETWORK": "testnet",
                }
            )

    def test_fee_limit_uses_lightning_fee(self):
        method = types.SimpleNamespace(
            is_BOLT11_INVOICE=lambda: True,
            lightning_fee_sats=7,
        )
        self.assertEqual(helper.require_bolt11_method(method), 7)

    def test_fee_limit_rejects_non_bolt11(self):
        method = types.SimpleNamespace(is_BOLT11_INVOICE=lambda: False)
        with self.assertRaisesRegex(helper.PayBreezSparkError, "BOLT11"):
            helper.require_bolt11_method(method)

    def test_extracts_and_verifies_preimage(self):
        preimage = "01" * 32
        payment_hash = hashlib.sha256(bytes.fromhex(preimage)).hexdigest()
        payment = types.SimpleNamespace(
            details=types.SimpleNamespace(
                htlc_details=types.SimpleNamespace(
                    payment_hash=payment_hash,
                    preimage=preimage,
                )
            )
        )
        prepare = types.SimpleNamespace(payment_method=types.SimpleNamespace(invoice_details=None))

        self.assertEqual(helper.extract_payment_hash(prepare, payment), payment_hash)
        self.assertEqual(helper.extract_preimage(payment), preimage)
        helper.verify_preimage(payment_hash, preimage)

    def test_mismatched_preimage_fails(self):
        with self.assertRaisesRegex(helper.PayBreezSparkError, "unusable preimage"):
            helper.verify_preimage("00" * 32, "01" * 32)


if __name__ == "__main__":
    unittest.main()
