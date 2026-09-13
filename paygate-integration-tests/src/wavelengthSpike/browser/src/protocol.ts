import { decodeBolt11 } from "farrier-kit/bolt11";

export const MAX_CHALLENGE_BYTES = 8192;
export const MAX_INVOICE_BYTES = 4096;
export const MAX_AUTHORIZATION_BYTES = 8192;

const BASE64 = /^[A-Za-z0-9+/]+={0,2}$/;
const HEX_32 = /^[0-9a-f]{64}$/;
const PAYGATE_CHALLENGE =
  /^L402 version="0", token="([A-Za-z0-9+/]+={0,2})", macaroon="([A-Za-z0-9+/]+={0,2})", invoice="([A-Za-z0-9]+)"$/;

export interface VerifiedChallenge {
  readonly invoice: string;
  readonly macaroon: string;
  readonly paymentHash: string;
  readonly amountSats: number;
  readonly expiresAtUnix: number;
}

export class BrowserProtocolError extends Error {
  constructor() {
    super("Invalid Paygate L402 challenge");
    this.name = "BrowserProtocolError";
  }
}

export function parsePaygateChallenge(header: string): VerifiedChallenge {
  if (!isBoundedAscii(header, MAX_CHALLENGE_BYTES)) {
    throw new BrowserProtocolError();
  }
  const match = PAYGATE_CHALLENGE.exec(header);
  if (match === null || match[1] !== match[2]) {
    throw new BrowserProtocolError();
  }
  const macaroon = match[1];
  const invoice = match[3];
  if (macaroon === undefined || invoice === undefined || !isCanonicalBase64(macaroon)) {
    throw new BrowserProtocolError();
  }
  return verifyChallengeFields(invoice, macaroon);
}

export function verifyChallengeFields(invoice: string, macaroon: string): VerifiedChallenge {
  if (
    !isBoundedAscii(invoice, MAX_INVOICE_BYTES) ||
    !isCanonicalBase64(macaroon) ||
    utf8Length(macaroon) > MAX_CHALLENGE_BYTES
  ) {
    throw new BrowserProtocolError();
  }

  try {
    const decoded = decodeBolt11(invoice);
    if (
      decoded.network !== "tb" ||
      decoded.amountSats === null ||
      decoded.amountSats <= 0 ||
      !Number.isSafeInteger(decoded.amountSats) ||
      !HEX_32.test(decoded.paymentHashHex)
    ) {
      throw new BrowserProtocolError();
    }
    const expiresAtUnix = decoded.timestamp + decoded.expirySeconds;
    if (!Number.isSafeInteger(expiresAtUnix) || expiresAtUnix <= decoded.timestamp) {
      throw new BrowserProtocolError();
    }
    return {
      invoice,
      macaroon,
      paymentHash: decoded.paymentHashHex,
      amountSats: decoded.amountSats,
      expiresAtUnix,
    };
  } catch (error) {
    if (error instanceof BrowserProtocolError) {
      throw error;
    }
    throw new BrowserProtocolError();
  }
}

export function buildAuthorization(macaroon: string, preimageHex: string): string {
  if (!isCanonicalBase64(macaroon) || !HEX_32.test(preimageHex)) {
    throw new BrowserProtocolError();
  }
  const value = `L402 ${macaroon}:${preimageHex}`;
  if (!isBoundedAscii(value, MAX_AUTHORIZATION_BYTES)) {
    throw new BrowserProtocolError();
  }
  return value;
}

export function sameOriginGetTarget(target: string, origin: string): string {
  try {
    const base = new URL(origin);
    const parsed = new URL(target, base);
    if (
      parsed.origin !== base.origin ||
      parsed.username !== "" ||
      parsed.password !== "" ||
      parsed.hash !== "" ||
      (parsed.protocol !== "https:" && parsed.hostname !== "127.0.0.1" && parsed.hostname !== "localhost")
    ) {
      throw new BrowserProtocolError();
    }
    return parsed.href;
  } catch (error) {
    if (error instanceof BrowserProtocolError) {
      throw error;
    }
    throw new BrowserProtocolError();
  }
}

function isCanonicalBase64(value: string): boolean {
  if (!BASE64.test(value) || value.length > MAX_CHALLENGE_BYTES || value.length % 4 !== 0) {
    return false;
  }
  try {
    const decoded = atob(value);
    let binary = "";
    for (let index = 0; index < decoded.length; index += 1) {
      binary += String.fromCharCode(decoded.charCodeAt(index));
    }
    return btoa(binary) === value;
  } catch {
    return false;
  }
}

function isBoundedAscii(value: string, maximumBytes: number): boolean {
  if (value.length === 0 || utf8Length(value) > maximumBytes) {
    return false;
  }
  for (let index = 0; index < value.length; index += 1) {
    const code = value.charCodeAt(index);
    if (code < 0x20 || code > 0x7e) {
      return false;
    }
  }
  return true;
}

function utf8Length(value: string): number {
  return new TextEncoder().encode(value).byteLength;
}
