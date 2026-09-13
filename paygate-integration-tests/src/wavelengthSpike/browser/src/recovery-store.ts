import {
  BrowserProtocolError,
  sameOriginGetTarget,
  verifyChallengeFields,
  type VerifiedChallenge,
} from "./protocol.js";

export const RECOVERY_STORAGE_KEY = "paygate.wavelength.l402-recovery.v1";
const RECORD_KEYS = [
  "amountSats",
  "createdAtUnixMs",
  "dispatchMayHaveOccurred",
  "expiresAtUnix",
  "invoice",
  "macaroon",
  "method",
  "paymentHash",
  "schemaVersion",
  "target",
] as const;

export interface RecoveryRecord {
  readonly schemaVersion: 1;
  readonly invoice: string;
  readonly macaroon: string;
  readonly target: string;
  readonly method: "GET";
  readonly paymentHash: string;
  readonly amountSats: number;
  readonly expiresAtUnix: number;
  readonly createdAtUnixMs: number;
  readonly dispatchMayHaveOccurred: true;
}

export class RecoveryBlockedError extends Error {
  constructor() {
    super("Payment recovery is blocked; inspect the browser wallet before resetting");
    this.name = "RecoveryBlockedError";
  }
}

export class RecoveryStore {
  constructor(
    private readonly storage: Storage,
    private readonly origin: string,
  ) {}

  load(): RecoveryRecord | null {
    let serialized: string | null;
    try {
      serialized = this.storage.getItem(RECOVERY_STORAGE_KEY);
    } catch {
      throw new RecoveryBlockedError();
    }
    if (serialized === null) {
      return null;
    }
    try {
      const candidate: unknown = JSON.parse(serialized);
      return validateRecord(candidate, this.origin);
    } catch {
      throw new RecoveryBlockedError();
    }
  }

  save(challenge: VerifiedChallenge, target: string, createdAtUnixMs: number): RecoveryRecord {
    const record: RecoveryRecord = {
      schemaVersion: 1,
      invoice: challenge.invoice,
      macaroon: challenge.macaroon,
      target: sameOriginGetTarget(target, this.origin),
      method: "GET",
      paymentHash: challenge.paymentHash,
      amountSats: challenge.amountSats,
      expiresAtUnix: challenge.expiresAtUnix,
      createdAtUnixMs,
      dispatchMayHaveOccurred: true,
    };
    const serialized = JSON.stringify(record);
    try {
      this.storage.setItem(RECOVERY_STORAGE_KEY, serialized);
      if (this.storage.getItem(RECOVERY_STORAGE_KEY) !== serialized) {
        throw new Error("storage verification failed");
      }
    } catch {
      throw new RecoveryBlockedError();
    }
    return record;
  }

  clear(): void {
    try {
      this.storage.removeItem(RECOVERY_STORAGE_KEY);
      if (this.storage.getItem(RECOVERY_STORAGE_KEY) !== null) {
        throw new Error("storage cleanup failed");
      }
    } catch {
      throw new RecoveryBlockedError();
    }
  }
}

function validateRecord(candidate: unknown, origin: string): RecoveryRecord {
  if (typeof candidate !== "object" || candidate === null || Array.isArray(candidate)) {
    throw new RecoveryBlockedError();
  }
  const raw = candidate as Record<string, unknown>;
  const keys = Object.keys(raw).sort();
  if (keys.length !== RECORD_KEYS.length || keys.some((key, index) => key !== RECORD_KEYS[index])) {
    throw new RecoveryBlockedError();
  }
  if (
    raw.schemaVersion !== 1 ||
    raw.method !== "GET" ||
    raw.dispatchMayHaveOccurred !== true ||
    typeof raw.invoice !== "string" ||
    typeof raw.macaroon !== "string" ||
    typeof raw.target !== "string" ||
    typeof raw.paymentHash !== "string" ||
    typeof raw.amountSats !== "number" ||
    typeof raw.expiresAtUnix !== "number" ||
    typeof raw.createdAtUnixMs !== "number" ||
    !Number.isSafeInteger(raw.createdAtUnixMs) ||
    raw.createdAtUnixMs < 0
  ) {
    throw new RecoveryBlockedError();
  }

  let challenge: VerifiedChallenge;
  try {
    challenge = verifyChallengeFields(raw.invoice, raw.macaroon);
  } catch (error) {
    if (error instanceof BrowserProtocolError) {
      throw new RecoveryBlockedError();
    }
    throw error;
  }
  if (
    challenge.paymentHash !== raw.paymentHash ||
    challenge.amountSats !== raw.amountSats ||
    challenge.expiresAtUnix !== raw.expiresAtUnix
  ) {
    throw new RecoveryBlockedError();
  }
  const target = sameOriginGetTarget(raw.target, origin);
  if (target !== raw.target) {
    throw new RecoveryBlockedError();
  }
  return {
    schemaVersion: 1,
    invoice: raw.invoice,
    macaroon: raw.macaroon,
    target,
    method: "GET",
    paymentHash: raw.paymentHash,
    amountSats: raw.amountSats,
    expiresAtUnix: raw.expiresAtUnix,
    createdAtUnixMs: raw.createdAtUnixMs,
    dispatchMayHaveOccurred: true,
  };
}
