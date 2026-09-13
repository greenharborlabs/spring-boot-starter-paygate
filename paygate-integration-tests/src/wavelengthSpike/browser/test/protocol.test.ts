import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
  BrowserProtocolError,
  buildAuthorization,
  parsePaygateChallenge,
  sameOriginGetTarget,
} from "../src/protocol.js";
import { CHALLENGE, INVOICE, MACAROON, PAYMENT_HASH, PREIMAGE } from "./fixtures.js";

describe("Paygate browser protocol boundary", () => {
  it("parses the exact bounded Paygate challenge and verified signet invoice", () => {
    const parsed = parsePaygateChallenge(CHALLENGE);
    assert.deepEqual(parsed, {
      invoice: INVOICE,
      macaroon: MACAROON,
      paymentHash: PAYMENT_HASH,
      amountSats: 10,
      expiresAtUnix: 1_800_003_600,
    });
  });

  it("rejects aliases that disagree, unknown syntax, and wrong-network invoices", () => {
    assert.throws(
      () => parsePaygateChallenge(CHALLENGE.replace(`macaroon="${MACAROON}"`, 'macaroon="BQYHCA=="')),
      BrowserProtocolError,
    );
    assert.throws(() => parsePaygateChallenge(`${CHALLENGE}, realm="extra"`), BrowserProtocolError);
    assert.throws(() => parsePaygateChallenge(CHALLENGE.replace("lntb", "lnbc")), BrowserProtocolError);
  });

  it("formats only a bounded canonical L402 authorization value", () => {
    assert.equal(buildAuthorization(MACAROON, PREIMAGE), `L402 ${MACAROON}:${PREIMAGE}`);
    assert.throws(() => buildAuthorization(MACAROON, "not-hex"), BrowserProtocolError);
  });

  it("accepts only an exact same-origin fragment-free GET target", () => {
    assert.equal(
      sameOriginGetTarget("/protected?price=10", "https://payer.example"),
      "https://payer.example/protected?price=10",
    );
    assert.throws(
      () => sameOriginGetTarget("https://attacker.example/protected", "https://payer.example"),
      BrowserProtocolError,
    );
    assert.throws(
      () => sameOriginGetTarget("/protected#fragment", "https://payer.example"),
      BrowserProtocolError,
    );
  });
});
