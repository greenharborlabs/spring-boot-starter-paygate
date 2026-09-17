import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
  ABANDON_RECOVERY_ACK,
  BrowserPaymentHarness,
} from "../src/payment-harness.js";
import { RecoveryBlockedError, RecoveryStore } from "../src/recovery-store.js";
import {
  CHALLENGE,
  FakeClient,
  MemoryStorage,
  NOW_MS,
  PREIMAGE,
  completeEntry,
  failedEntry,
  pendingEntry,
} from "./fixtures.js";

const ORIGIN = "https://payer.example";
const TARGET = "/protected?price=10";
const immediateDelay = async () => undefined;
const nextTurnDelay = async () => new Promise<void>((resolve) => setImmediate(resolve));

describe("single-dispatch browser recovery", () => {
  it("prevents dispatch when pre-dispatch storage fails", async () => {
    const storage = new MemoryStorage();
    storage.failSet = true;
    const client = new FakeClient();
    const harness = harnessFor(client, storage);

    await assert.rejects(() => harness.dispatch(CHALLENGE, TARGET), RecoveryBlockedError);
    assert.equal(client.dispatchCount, 0);
    assert.equal(client.stopCount, 1);
  });

  it("reload before any durable intent remains payment-required and dispatch-free", () => {
    const client = new FakeClient();
    const harness = harnessFor(client, new MemoryStorage());
    assert.equal(harness.current().state, "payment_required");
    assert.equal(client.dispatchCount, 0);
  });

  it("rejects overlapping dispatch calls before a second quote or payment", async () => {
    const client = new FakeClient();
    client.sendEntry = completeEntry();
    const originalPrepare = client.prepareSend.bind(client);
    let prepareCount = 0;
    let signalPrepareStarted: () => void = () => undefined;
    let releasePrepare: () => void = () => undefined;
    const prepareStarted = new Promise<void>((resolve) => {
      signalPrepareStarted = resolve;
    });
    const prepareGate = new Promise<void>((resolve) => {
      releasePrepare = resolve;
    });
    client.prepareSend = async () => {
      prepareCount += 1;
      signalPrepareStarted();
      await prepareGate;
      return originalPrepare();
    };
    const harness = harnessFor(client, new MemoryStorage(), async () => 200);

    const first = harness.dispatch(CHALLENGE, TARGET);
    await prepareStarted;
    await assert.rejects(() => harness.dispatch(CHALLENGE, TARGET), RecoveryBlockedError);
    releasePrepare();

    assert.equal((await first).state, "unlocked");
    assert.equal(prepareCount, 1);
    assert.equal(client.dispatchCount, 1);
  });

  it("blocks dispatch when the supported activity snapshot cannot establish prior state", async () => {
    const client = new FakeClient();
    client.listFails = true;
    const result = await harnessFor(client, new MemoryStorage()).dispatch(CHALLENGE, TARGET);
    assert.equal(result.state, "payment_outcome_unknown");
    assert.equal(client.dispatchCount, 0);
  });

  it("prevents redispatch when stored and progress hashes contradict", async () => {
    const client = new FakeClient();
    const conflicting = completeEntry();
    client.listedEntries = [
      {
        ...conflicting,
        progress: {
          ...conflicting.progress!,
          paymentHash: "11".repeat(32),
        },
      },
    ];
    const result = await harnessFor(client, new MemoryStorage()).dispatch(CHALLENGE, TARGET);
    assert.equal(result.state, "payment_outcome_unknown");
    assert.equal(client.dispatchCount, 0);
  });

  it("recovers delayed completion when the entire dispatch response is lost", async () => {
    const storage = new MemoryStorage();
    const client = new FakeClient();
    client.neverResolveDispatchResponse = true;
    client.onDispatch = () => queueMicrotask(() => client.emit({ type: "activity", payload: completeEntry() }));
    const requests: Array<{ target: string; authorization: string }> = [];
    const harness = harnessFor(client, storage, async (target, authorization) => {
      requests.push({ target, authorization });
      return 200;
    }, nextTurnDelay);

    const result = await harness.dispatch(CHALLENGE, TARGET);

    assert.deepEqual(result, { state: "unlocked", detail: "none" });
    assert.equal(client.dispatchCount, 1);
    assert.equal(requests.length, 1);
    assert.equal(requests[0]?.target, `${ORIGIN}${TARGET}`);
    assert.equal(requests[0]?.authorization, `L402 AQIDBA==:${PREIMAGE}`);
    assert.equal(harness.current().state, "payment_required");
  });

  it("blocks after reload between durable intent and dispatch without inventing a payment retry", async () => {
    const storage = new MemoryStorage();
    storage.afterSet = () => {
      storage.afterSet = null;
      throw new Error("simulated reload");
    };
    const firstClient = new FakeClient();
    await assert.rejects(() => harnessFor(firstClient, storage).dispatch(CHALLENGE, TARGET));
    assert.equal(firstClient.dispatchCount, 0);

    const reloadedClient = new FakeClient();
    const result = await harnessFor(reloadedClient, storage).recover();
    assert.deepEqual(result, { state: "payment_outcome_unknown", detail: "pending" });
    assert.equal(reloadedClient.dispatchCount, 0);
  });

  it("reconciles reload immediately after dispatch and before its response without a second dispatch", async () => {
    const storage = new MemoryStorage();
    const firstClient = new FakeClient();
    firstClient.neverResolveDispatchResponse = true;
    const first = await harnessFor(firstClient, storage).dispatch(CHALLENGE, TARGET);
    assert.equal(first.state, "payment_outcome_unknown");
    assert.equal(firstClient.dispatchCount, 1);

    const reloadedClient = new FakeClient();
    reloadedClient.replayEntries = [completeEntry()];
    const recovered = await harnessFor(reloadedClient, storage, async () => 200).recover();
    assert.equal(recovered.state, "unlocked");
    assert.equal(firstClient.dispatchCount + reloadedClient.dispatchCount, 1);
  });

  it("reconciles a pending reload followed by delayed stream completion", async () => {
    const storage = new MemoryStorage();
    const firstClient = new FakeClient();
    firstClient.sendEntry = pendingEntry();
    const first = await harnessFor(firstClient, storage).dispatch(CHALLENGE, TARGET);
    assert.equal(first.state, "payment_outcome_unknown");

    const reloadedClient = new FakeClient();
    reloadedClient.replayEntries = [pendingEntry()];
    const originalStart = reloadedClient.startActivity.bind(reloadedClient);
    reloadedClient.startActivity = async () => {
      await originalStart();
      queueMicrotask(() =>
        reloadedClient.emit({ type: "activity", payload: completeEntry() }),
      );
    };
    const recovered = await harnessFor(
      reloadedClient,
      storage,
      async () => 200,
      nextTurnDelay,
    ).recover();

    assert.equal(recovered.state, "unlocked");
    assert.equal(firstClient.dispatchCount + reloadedClient.dispatchCount, 1);
  });

  it("does not dispatch when activity replay already identifies the challenge", async () => {
    const client = new FakeClient();
    client.replayEntries = [completeEntry()];
    const result = await harnessFor(client, new MemoryStorage(), async () => 200).dispatch(
      CHALLENGE,
      TARGET,
    );
    assert.equal(result.state, "unlocked");
    assert.equal(client.dispatchCount, 0);
  });

  it("retains the original challenge after settlement until protected 200", async () => {
    const storage = new MemoryStorage();
    const firstClient = new FakeClient();
    firstClient.sendEntry = completeEntry();
    const statuses = [503, 418];
    const first = await harnessFor(
      firstClient,
      storage,
      async () => statuses.shift() ?? 418,
    ).dispatch(CHALLENGE, TARGET);
    assert.deepEqual(first, { state: "payment_outcome_unknown", detail: "retry_rejected" });

    const reloadedClient = new FakeClient();
    reloadedClient.replayEntries = [completeEntry()];
    const recovered = await harnessFor(reloadedClient, storage, async () => 200).recover();
    assert.equal(recovered.state, "unlocked");
    assert.equal(firstClient.dispatchCount + reloadedClient.dispatchCount, 1);
  });

  it("uses only the documented five retries for receiver 503 and then retains recovery", async () => {
    const storage = new MemoryStorage();
    const client = new FakeClient();
    client.sendEntry = completeEntry();
    let attempts = 0;
    const result = await harnessFor(client, storage, async () => {
      attempts += 1;
      return 503;
    }).dispatch(CHALLENGE, TARGET);
    assert.equal(attempts, 6);
    assert.deepEqual(result, { state: "payment_outcome_unknown", detail: "retry_rejected" });
    assert.equal(harnessFor(new FakeClient(), storage).current().state, "payment_outcome_unknown");
  });

  it("keeps recovery blocked when the original protected retry is unreachable", async () => {
    const storage = new MemoryStorage();
    const client = new FakeClient();
    client.sendEntry = completeEntry();
    const result = await harnessFor(client, storage, async () => {
      throw new Error("unreachable");
    }).dispatch(CHALLENGE, TARGET);
    assert.deepEqual(result, { state: "payment_outcome_unknown", detail: "retry_rejected" });
    assert.equal(harnessFor(new FakeClient(), storage).current().state, "payment_outcome_unknown");
  });

  it("keeps a second 402 as challenge error without clearing the original record", async () => {
    const storage = new MemoryStorage();
    const client = new FakeClient();
    client.sendEntry = completeEntry();
    const result = await harnessFor(client, storage, async () => 402).dispatch(CHALLENGE, TARGET);
    assert.equal(result.state, "challenge_error");
    assert.equal(harnessFor(new FakeClient(), storage).current().state, "payment_outcome_unknown");
  });

  it("keeps terminal failure blocked because timeout is not conclusive cancellation", async () => {
    const storage = new MemoryStorage();
    const client = new FakeClient();
    client.sendEntry = failedEntry();
    const result = await harnessFor(client, storage).dispatch(CHALLENGE, TARGET);
    assert.deepEqual(result, { state: "payment_outcome_unknown", detail: "failed" });
    assert.equal(harnessFor(new FakeClient(), storage).current().state, "payment_outcome_unknown");
  });

  it("rejects an invalid recovered preimage and retains recovery metadata", async () => {
    const storage = new MemoryStorage();
    const client = new FakeClient();
    client.sendEntry = completeEntry("11".repeat(32));
    const result = await harnessFor(client, storage).dispatch(CHALLENGE, TARGET);
    assert.deepEqual(result, { state: "payment_outcome_unknown", detail: "failed" });
    assert.equal(harnessFor(new FakeClient(), storage).current().state, "payment_outcome_unknown");
  });

  it("requires an explicit warned abandonment before unresolved metadata is cleared", async () => {
    const storage = new MemoryStorage();
    const client = new FakeClient();
    client.sendEntry = pendingEntry();
    const harness = harnessFor(client, storage);
    await harness.dispatch(CHALLENGE, TARGET);
    assert.equal(harness.current().state, "payment_outcome_unknown");
    assert.throws(() => harness.abandon(""), RecoveryBlockedError);
    harness.abandon(ABANDON_RECOVERY_ACK);
    assert.equal(harness.current().state, "payment_required");
  });
});

function harnessFor(
  client: FakeClient,
  storage: MemoryStorage,
  fetcher: (target: string, authorization: string) => Promise<number> = async () => 500,
  delay: (milliseconds: number) => Promise<void> = immediateDelay,
): BrowserPaymentHarness {
  return new BrowserPaymentHarness(
    client,
    new RecoveryStore(storage, ORIGIN),
    fetcher,
    delay,
    () => NOW_MS,
  );
}
