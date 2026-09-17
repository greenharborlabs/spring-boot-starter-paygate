import assert from "node:assert/strict";
import { it } from "node:test";
import { loseDispatchResponse } from "../src/response-loss.js";
import { FakeClient } from "./fixtures.js";

it("dispatches once but does not deliver any send result or handle", async () => {
  const client = new FakeClient();
  let counted = 0;
  let lost = 0;
  let returned = false;
  const boundary = loseDispatchResponse(client, async () => { counted++; }, async () => { lost++; });
  void boundary.sendPrepared(await client.prepareSend()).then(() => { returned = true; });
  await new Promise<void>((resolve) => setImmediate(resolve));
  assert.equal(client.dispatchCount, 1);
  assert.equal(counted, 1);
  assert.equal(lost, 1);
  assert.equal(returned, false);
});

it("counter rejection prevents SDK dispatch before it can occur", async () => {
  const client = new FakeClient();
  const boundary = loseDispatchResponse(client, async () => { throw new Error("blocked"); }, async () => undefined);
  await assert.rejects(boundary.sendPrepared(await client.prepareSend()), /blocked/);
  assert.equal(client.dispatchCount, 0);
});

it("a real dispatch rejection is still an indeterminate loss, never a repayment", async () => {
  const client = new FakeClient();
  client.rejectDispatchResponse = true;
  let lost = false;
  const boundary = loseDispatchResponse(client, async () => undefined, async () => { lost = true; });
  await assert.rejects(boundary.sendPrepared(await client.prepareSend()));
  assert.equal(lost, true);
  assert.equal(client.dispatchCount, 1);
});
