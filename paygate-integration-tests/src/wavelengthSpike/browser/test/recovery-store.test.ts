import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { parsePaygateChallenge } from "../src/protocol.js";
import {
  RECOVERY_STORAGE_KEY,
  RecoveryBlockedError,
  RecoveryStore,
} from "../src/recovery-store.js";
import { CHALLENGE, MemoryStorage, NOW_MS, PREIMAGE } from "./fixtures.js";

describe("pre-dispatch recovery storage", () => {
  it("round-trips one validated record without a preimage or authorization value", () => {
    const storage = new MemoryStorage();
    const store = new RecoveryStore(storage, "https://payer.example");
    const record = store.save(parsePaygateChallenge(CHALLENGE), "/protected", NOW_MS);

    assert.deepEqual(store.load(), record);
    const serialized = storage.getItem(RECOVERY_STORAGE_KEY);
    assert.ok(serialized);
    assert.equal(serialized.includes(PREIMAGE), false);
    assert.equal(serialized.includes("Authorization"), false);
    assert.equal(record.dispatchMayHaveOccurred, true);
  });

  it("fails closed when storage cannot persist and verify the record", () => {
    const storage = new MemoryStorage();
    storage.failSet = true;
    const store = new RecoveryStore(storage, "https://payer.example");
    assert.throws(
      () => store.save(parsePaygateChallenge(CHALLENGE), "/protected", NOW_MS),
      RecoveryBlockedError,
    );
  });

  it("blocks corrupt or cross-origin records without deleting them", () => {
    const storage = new MemoryStorage();
    storage.setItem(
      RECOVERY_STORAGE_KEY,
      JSON.stringify({ schemaVersion: 1, target: "https://attacker.example" }),
    );
    const store = new RecoveryStore(storage, "https://payer.example");
    assert.throws(() => store.load(), RecoveryBlockedError);
    assert.notEqual(storage.getItem(RECOVERY_STORAGE_KEY), null);
  });

  it("verifies cleanup and reports cleanup failure as blocked", () => {
    const storage = new MemoryStorage();
    const store = new RecoveryStore(storage, "https://payer.example");
    store.save(parsePaygateChallenge(CHALLENGE), "/protected", NOW_MS);
    store.clear();
    assert.equal(store.load(), null);

    store.save(parsePaygateChallenge(CHALLENGE), "/protected", NOW_MS);
    storage.failRemove = true;
    assert.throws(() => store.clear(), RecoveryBlockedError);
  });
});
