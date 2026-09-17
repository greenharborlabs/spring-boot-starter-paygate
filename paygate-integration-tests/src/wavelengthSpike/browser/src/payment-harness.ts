import type {
  Entry,
  ListResult,
  PrepareSendResult,
  SendResult,
  WavelengthClient,
  WavelengthEvent,
} from "@lightninglabs/wavelength-web";

import { buildAuthorization, parsePaygateChallenge, type VerifiedChallenge } from "./protocol.js";
import { RecoveryBlockedError, RecoveryStore, type RecoveryRecord } from "./recovery-store.js";

const PAYMENT_WAIT_MS = 120_000;
const RETRY_DELAYS_MS = [1_000, 2_000, 4_000, 8_000, 15_000] as const;
const HEX_32 = /^[0-9a-f]{64}$/;
export const ABANDON_RECOVERY_ACK = "I_UNDERSTAND_A_PAYMENT_MAY_HAVE_OCCURRED";

export type PaymentState =
  | "payment_required"
  | "paying_over_lightning"
  | "payment_outcome_unknown"
  | "challenge_error"
  | "unlocked";

export interface HarnessResult {
  readonly state: PaymentState;
  readonly detail: "none" | "pending" | "failed" | "retry_rejected" | "storage_blocked";
}

export type PaymentClient = Pick<
  WavelengthClient,
  "list" | "prepareSend" | "sendPrepared" | "startActivity" | "stopActivity" | "subscribe"
>;

export type ProtectedFetcher = (target: string, authorization: string) => Promise<number>;
export type Delay = (milliseconds: number) => Promise<void>;

interface MonitorStart {
  readonly listSucceeded: boolean;
  readonly existingMatch: boolean;
}

export class BrowserPaymentHarness {
  private dispatchInFlight = false;

  constructor(
    private readonly client: PaymentClient,
    private readonly store: RecoveryStore,
    private readonly fetchProtected: ProtectedFetcher,
    private readonly delay: Delay = defaultDelay,
    private readonly now: () => number = Date.now,
    private readonly onState: (state: PaymentState) => void = () => undefined,
  ) {}

  current(): HarnessResult {
    try {
      return this.store.load() === null
        ? { state: "payment_required", detail: "none" }
        : { state: "payment_outcome_unknown", detail: "pending" };
    } catch (error) {
      if (error instanceof RecoveryBlockedError) {
        return { state: "payment_outcome_unknown", detail: "storage_blocked" };
      }
      throw error;
    }
  }

  async dispatch(challengeHeader: string, target: string): Promise<HarnessResult> {
    if (this.dispatchInFlight) {
      throw new RecoveryBlockedError();
    }
    this.dispatchInFlight = true;
    try {
      return await this.dispatchOnce(challengeHeader, target);
    } finally {
      this.dispatchInFlight = false;
    }
  }

  private async dispatchOnce(challengeHeader: string, target: string): Promise<HarnessResult> {
    if (this.store.load() !== null) {
      throw new RecoveryBlockedError();
    }
    const challenge = parsePaygateChallenge(challengeHeader);
    if (challenge.expiresAtUnix * 1_000 <= this.now()) {
      throw new RecoveryBlockedError();
    }
    const prepared = await this.client.prepareSend({ invoice: challenge.invoice });
    validatePrepared(prepared, challenge);

    const monitor = new ActivityMonitor(this.client, challenge.paymentHash);
    const started = await monitor.start();
    let record: RecoveryRecord;
    try {
      record = this.store.save(challenge, target, this.now());
    } catch (error) {
      monitor.stop();
      throw error;
    }

    this.onState("paying_over_lightning");
    if (!started.listSucceeded) {
      monitor.stop();
      this.onState("payment_outcome_unknown");
      return { state: "payment_outcome_unknown", detail: "pending" };
    }
    if (started.existingMatch) {
      return this.finish(record, monitor);
    }

    void this.client
      .sendPrepared(prepared)
      .then((result) => {
        validateSendResult(result, challenge);
        monitor.observe(result.entry);
      })
      .catch(() => {
        // A dispatch error or lost response cannot establish that no payment occurred.
      });
    return this.finish(record, monitor);
  }

  async recover(): Promise<HarnessResult> {
    const record = this.store.load();
    if (record === null) {
      return { state: "payment_required", detail: "none" };
    }
    this.onState("payment_outcome_unknown");
    const monitor = new ActivityMonitor(this.client, record.paymentHash);
    const started = await monitor.start();
    if (!started.listSucceeded) {
      monitor.stop();
      return { state: "payment_outcome_unknown", detail: "pending" };
    }
    return this.finish(record, monitor);
  }

  abandon(acknowledgement: string): void {
    if (acknowledgement !== ABANDON_RECOVERY_ACK) {
      throw new RecoveryBlockedError();
    }
    this.store.clear();
    this.onState("payment_required");
  }

  private async finish(record: RecoveryRecord, monitor: ActivityMonitor): Promise<HarnessResult> {
    const elapsed = Math.max(0, this.now() - record.createdAtUnixMs);
    const remaining = Math.max(0, PAYMENT_WAIT_MS - elapsed);
    try {
      const entry = await monitor.terminalWithin(remaining, this.delay);
      if (entry === null) {
        this.onState("payment_outcome_unknown");
        return { state: "payment_outcome_unknown", detail: "pending" };
      }
      if (entry.status === "failed") {
        this.onState("payment_outcome_unknown");
        return { state: "payment_outcome_unknown", detail: "failed" };
      }
      const preimageHex = entry.progress?.preimage;
      if (preimageHex === undefined || preimageHex === "") {
        this.onState("payment_outcome_unknown");
        return { state: "payment_outcome_unknown", detail: "pending" };
      }
      const valid = await validatedPreimage(preimageHex, record.paymentHash);
      if (!valid) {
        this.onState("payment_outcome_unknown");
        return { state: "payment_outcome_unknown", detail: "failed" };
      }
      return await this.retryOriginal(record, preimageHex);
    } finally {
      monitor.stop();
    }
  }

  private async retryOriginal(record: RecoveryRecord, preimageHex: string): Promise<HarnessResult> {
    let authorization = buildAuthorization(record.macaroon, preimageHex);
    try {
      try {
        let status = await this.fetchProtected(record.target, authorization);
        for (const retryDelay of RETRY_DELAYS_MS) {
          if (status !== 503) {
            break;
          }
          await this.delay(retryDelay);
          status = await this.fetchProtected(record.target, authorization);
        }
        if (status === 200) {
          this.store.clear();
          this.onState("unlocked");
          return { state: "unlocked", detail: "none" };
        }
        this.onState(status === 402 ? "challenge_error" : "payment_outcome_unknown");
        return {
          state: status === 402 ? "challenge_error" : "payment_outcome_unknown",
          detail: "retry_rejected",
        };
      } catch {
        this.onState("payment_outcome_unknown");
        return { state: "payment_outcome_unknown", detail: "retry_rejected" };
      }
    } finally {
      authorization = "";
    }
  }
}

class ActivityMonitor {
  private readonly terminal: Promise<Entry>;
  private resolveTerminal!: (entry: Entry) => void;
  private unsubscribe: (() => void) | null = null;
  private matchingEntryIds = new Set<string>();
  private matchingObserved = false;
  private protocolFailure = false;

  constructor(
    private readonly client: PaymentClient,
    private readonly paymentHash: string,
  ) {
    this.terminal = new Promise<Entry>((resolve) => {
      this.resolveTerminal = resolve;
    });
  }

  async start(): Promise<MonitorStart> {
    this.unsubscribe = this.client.subscribe((event: WavelengthEvent) => {
      if (event.type === "activity") {
        this.observe(event.payload);
      }
    });
    try {
      await this.client.startActivity({ includeExisting: true, kinds: ["send"], cursor: 0 });
    } catch {
      return { listSucceeded: false, existingMatch: this.matchingObserved };
    }

    try {
      await this.inspectListHistory();
      return {
        listSucceeded: !this.protocolFailure,
        existingMatch: this.matchingObserved,
      };
    } catch {
      return { listSucceeded: false, existingMatch: this.matchingObserved };
    }
  }

  observe(entry: Entry): void {
    if (entry.kind !== "send") {
      return;
    }
    const hashes = entryHashes(entry);
    if (hashes.includes(this.paymentHash) && hashes.some((value) => value !== this.paymentHash)) {
      this.protocolFailure = true;
      return;
    }
    if (!isMatchingSend(entry, this.paymentHash)) {
      return;
    }
    this.matchingObserved = true;
    this.matchingEntryIds.add(entry.id);
    if (this.matchingEntryIds.size > 1) {
      this.protocolFailure = true;
      return;
    }
    if (entry.status === "failed" || (entry.status === "complete" && entry.progress?.preimage)) {
      this.resolveTerminal(entry);
    }
  }

  async terminalWithin(milliseconds: number, delay: Delay): Promise<Entry | null> {
    return Promise.race([this.terminal, delay(Math.max(0, milliseconds)).then(() => null)]);
  }

  stop(): void {
    this.client.stopActivity();
    this.unsubscribe?.();
    this.unsubscribe = null;
  }

  private async inspectListHistory(): Promise<void> {
    let cursor: string | undefined;
    const seenCursors = new Set<string>();
    for (let page = 0; page < 5; page += 1) {
      const request = {
        view: "activity" as const,
        pendingOnly: false,
        kinds: ["send" as const],
        limit: 100,
        ...(cursor === undefined ? {} : { cursor }),
      };
      const result: ListResult = await this.client.list(request);
      if (result.view !== "activity" || result.activity === undefined) {
        throw new RecoveryBlockedError();
      }
      const activity = result.activity;
      if (activity.total !== activity.entries.length || activity.entries.length > 100) {
        throw new RecoveryBlockedError();
      }
      for (const entry of activity.entries) {
        this.observe(entry);
      }
      if (this.protocolFailure || this.matchingEntryIds.size > 1) {
        throw new RecoveryBlockedError();
      }
      if (!activity.hasMore) {
        if (activity.nextCursor !== "") {
          throw new RecoveryBlockedError();
        }
        return;
      }
      if (activity.nextCursor === "" || seenCursors.has(activity.nextCursor)) {
        throw new RecoveryBlockedError();
      }
      seenCursors.add(activity.nextCursor);
      cursor = activity.nextCursor;
    }
    throw new RecoveryBlockedError();
  }
}

function validatePrepared(prepared: PrepareSendResult, challenge: VerifiedChallenge): void {
  if (
    prepared.paymentHash.toLowerCase() !== challenge.paymentHash ||
    prepared.amountSat !== challenge.amountSats ||
    prepared.expiresAtUnix !== challenge.expiresAtUnix ||
    prepared.sendIntentId === ""
  ) {
    throw new RecoveryBlockedError();
  }
}

function validateSendResult(result: SendResult, challenge: VerifiedChallenge): void {
  if (
    result.paymentHash?.toLowerCase() !== challenge.paymentHash ||
    result.actualAmountSat !== challenge.amountSats ||
    !isMatchingSend(result.entry, challenge.paymentHash)
  ) {
    throw new RecoveryBlockedError();
  }
}

function isMatchingSend(entry: Entry, expectedHash: string): boolean {
  const hashes = entryHashes(entry);
  return entry.kind === "send" && hashes.length > 0 && hashes.every((value) => value === expectedHash);
}

function entryHashes(entry: Entry): string[] {
  return [entry.progress?.paymentHash, entry.request?.paymentHash]
    .filter((value): value is string => value !== undefined && value !== "")
    .map((value) => value.toLowerCase());
}

async function validatedPreimage(preimageHex: string, paymentHash: string): Promise<boolean> {
  if (!HEX_32.test(preimageHex) || !HEX_32.test(paymentHash)) {
    return false;
  }
  const preimage = hexBytes(preimageHex);
  let digest = new Uint8Array();
  try {
    digest = new Uint8Array(await crypto.subtle.digest("SHA-256", preimage));
    const expected = hexBytes(paymentHash);
    try {
      let difference = digest.length ^ expected.length;
      const maximum = Math.max(digest.length, expected.length);
      for (let index = 0; index < maximum; index += 1) {
        difference |= (digest[index] ?? 0) ^ (expected[index] ?? 0);
      }
      return difference === 0;
    } finally {
      expected.fill(0);
    }
  } finally {
    preimage.fill(0);
    digest.fill(0);
  }
}

function hexBytes(value: string): Uint8Array<ArrayBuffer> {
  const bytes = new Uint8Array(value.length / 2);
  for (let index = 0; index < bytes.length; index += 1) {
    bytes[index] = Number.parseInt(value.slice(index * 2, index * 2 + 2), 16);
  }
  return bytes;
}

function defaultDelay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}
