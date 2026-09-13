import type {
  Entry,
  ListRequest,
  ListResult,
  PrepareSendResult,
  SendResult,
  WavelengthEvent,
  WavelengthListener,
} from "@lightninglabs/wavelength-web";

import type { PaymentClient } from "../src/payment-harness.js";

export const INVOICE =
  "lntb100n1p45n5sqpp5ve584t0cv27hwmy0cx9ca8uwyqyfw9y9dm3r8vus9fv36r2l9yjsdpd2pshjempw3jjqumede6xset5d93jq4pkypnxj7r5w4ex2xqrrsscqpjvcdccme7lyc3ser88a4c3gnnpqyhk2qy4yhn4qxk5epjsce59t5q3hyssfk92e0929weshd9g3xqk6w0j3el8vf28flm2twrn5u2f7qqwuywwh";
export const PAYMENT_HASH = "66687aadf862bd776c8fc18b8e9f8e20089714856ee233b3902a591d0d5f2925";
export const PREIMAGE = "00".repeat(32);
export const MACAROON = "AQIDBA==";
export const CHALLENGE = `L402 version="0", token="${MACAROON}", macaroon="${MACAROON}", invoice="${INVOICE}"`;
export const NOW_MS = 1_800_000_001_000;

export class MemoryStorage implements Storage {
  private readonly values = new Map<string, string>();
  readonly length = 0;
  failGet = false;
  failSet = false;
  failRemove = false;
  afterSet: (() => void) | null = null;

  clear(): void {
    this.values.clear();
  }

  getItem(key: string): string | null {
    if (this.failGet) throw new Error("storage unavailable");
    return this.values.get(key) ?? null;
  }

  key(index: number): string | null {
    return [...this.values.keys()][index] ?? null;
  }

  removeItem(key: string): void {
    if (this.failRemove) throw new Error("storage unavailable");
    this.values.delete(key);
  }

  setItem(key: string, value: string): void {
    if (this.failSet) throw new Error("storage unavailable");
    this.values.set(key, value);
    this.afterSet?.();
  }
}

export class FakeClient implements PaymentClient {
  dispatchCount = 0;
  startCount = 0;
  stopCount = 0;
  listCount = 0;
  listedEntries: Entry[] = [];
  replayEntries: Entry[] = [];
  sendEntry: Entry = pendingEntry();
  rejectDispatchResponse = false;
  neverResolveDispatchResponse = false;
  listFails = false;
  onDispatch: (() => void) | null = null;
  private listeners = new Set<WavelengthListener>();

  async prepareSend(): Promise<PrepareSendResult> {
    return {
      sendIntentId: "supported-single-use-intent",
      amountSat: 10,
      expectedFeeSat: 1,
      feeKnown: true,
      expectedTotalOutflowSat: 11,
      totalOutflowKnown: true,
      rail: "lightning",
      quoteStatus: "complete",
      destinationSummary: "synthetic",
      invoiceDescription: "Paygate synthetic T6 fixture",
      paymentHash: PAYMENT_HASH,
      expiresAtUnix: 1_800_003_600,
      selectedOutpoints: [],
      warning: "",
    };
  }

  async sendPrepared(): Promise<SendResult> {
    this.dispatchCount += 1;
    this.onDispatch?.();
    if (this.neverResolveDispatchResponse) {
      return new Promise<SendResult>(() => undefined);
    }
    if (this.rejectDispatchResponse) {
      throw new Error("response lost");
    }
    return { entry: this.sendEntry, actualAmountSat: 10, paymentHash: PAYMENT_HASH };
  }

  async list(_request?: ListRequest): Promise<ListResult> {
    this.listCount += 1;
    if (this.listFails) throw new Error("list unavailable");
    return {
      view: "activity",
      activity: {
        entries: this.listedEntries,
        total: this.listedEntries.length,
        hasMore: false,
        nextCursor: "",
      },
    };
  }

  subscribe(listener: WavelengthListener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  async startActivity(): Promise<void> {
    this.startCount += 1;
    for (const entry of this.replayEntries) {
      this.emit({ type: "activity", payload: entry });
    }
  }

  stopActivity(): void {
    this.stopCount += 1;
  }

  emit(event: WavelengthEvent): void {
    for (const listener of this.listeners) listener(event);
  }
}

export function pendingEntry(): Entry {
  return entry("pending", "");
}

export function completeEntry(preimage = PREIMAGE): Entry {
  return entry("complete", preimage);
}

export function failedEntry(): Entry {
  return {
    ...entry("failed", ""),
    failureCode: "timed_out",
    failureReason: "",
  };
}

function entry(status: Entry["status"], preimage: string): Entry {
  return {
    id: "synthetic-send-entry",
    kind: "send",
    status,
    amountSat: 10,
    feeSat: 1,
    counterparty: "",
    createdAt: "2027-01-15T08:00:01Z",
    updatedAt: "2027-01-15T08:00:02Z",
    note: "",
    failureReason: "",
    failureCode: "" as Entry["failureCode"],
    cursor: 1,
    progress: {
      phase: status === "complete" ? "confirmed" : "settling",
      phaseLabel: status,
      paymentHash: PAYMENT_HASH,
      txid: "",
      confirmationHeight: 0,
      vTXOOutpoint: "",
      preimage,
    },
    request: {
      type: "lightning",
      lightningInvoice: INVOICE,
      paymentHash: PAYMENT_HASH,
      onchainAddress: "",
      arkAddress: "",
    },
  };
}
