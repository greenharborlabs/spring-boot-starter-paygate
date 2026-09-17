import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import { chromium } from "@playwright/test";

import { startHarnessServer } from "../scripts/server.mjs";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const runtimeDirectory = process.env.WAVELENGTH_SPIKE_RUNTIME_DIR
  ? resolve(process.env.WAVELENGTH_SPIKE_RUNTIME_DIR)
  : resolve(root, "build/runtime-v0.1.1");
const profile = await mkdtemp(join(tmpdir(), "paygate-wavelength-profile-"));
const server = await startHarnessServer(runtimeDirectory);
let context;
try {
  const indexResponse = await fetch(`${server.origin}/`);
  assertHeader(indexResponse, "content-security-policy", "worker-src 'self'");
  assertHeader(indexResponse, "cross-origin-embedder-policy", "require-corp");
  assertHeader(indexResponse, "cross-origin-opener-policy", "same-origin");
  assertHeader(indexResponse, "x-content-type-options", "nosniff");
  await indexResponse.body?.cancel();

  const wasmResponse = await fetch(`${server.origin}/runtime/v0.1.1/wavewalletdk.wasm`);
  assertHeader(wasmResponse, "content-type", "application/wasm");
  assertHeader(wasmResponse, "cache-control", "immutable");
  await wasmResponse.body?.cancel();

  context = await chromium.launchPersistentContext(profile, {
    headless: true,
    recordHar: undefined,
    recordVideo: undefined,
  });
  const pages = context.pages();
  const page = pages[0] ?? (await context.newPage());
  await page.goto(`${server.origin}/?runtime=ready`, { waitUntil: "domcontentloaded" });
  await page.locator('[data-harness-state="runtime_ready"]').waitFor({ timeout: 120_000 });
  const capabilities = await page.evaluate(() => ({
    crossOriginIsolated: globalThis.crossOriginIsolated,
    secureContext: globalThis.isSecureContext,
    opfs: typeof navigator.storage?.getDirectory === "function",
    webLocks: typeof navigator.locks === "object",
    worker: typeof Worker === "function",
  }));
  if (Object.values(capabilities).some((available) => !available)) {
    throw new Error("Clean browser profile lacks a required runtime capability");
  }
  await page.evaluate(() => sessionStorage.setItem("paygate.wavelength.synthetic-reload", "present"));
  await page.reload({ waitUntil: "domcontentloaded" });
  const survivedReload = await page.evaluate(
    () => sessionStorage.getItem("paygate.wavelength.synthetic-reload") === "present",
  );
  if (!survivedReload) {
    throw new Error("Clean browser profile did not preserve session recovery storage across reload");
  }
  console.log("clean-profile runtime_ready storage_reload_ready");
} finally {
  await context?.close();
  await server.close();
  await rm(profile, { recursive: true, force: true });
}

function assertHeader(response, name, expectedFragment) {
  const value = response.headers.get(name);
  if (value === null || !value.includes(expectedFragment)) {
    throw new Error("Harness serving header verification failed");
  }
}

// Verify the live serving contract without starting/administering a wallet.
const liveServer = await startHarnessServer(runtimeDirectory, 0, true);
try {
  for (const path of ["/live", "/assets/live-entry.js", "/assets/wavelength-worker.js"]) {
    const response = await fetch(`${liveServer.origin}${path}`);
    if (response.status !== 200) throw new Error("Live asset is unavailable");
    assertHeader(response, "content-security-policy", "https://signet.wavelength-rest.lightning.finance");
    assertHeader(response, "content-security-policy", "https://signet.swapd-rest.lightning.finance");
    assertHeader(response, "cache-control", "no-store");
    await response.body?.cancel();
  }
} finally {
  await liveServer.close();
}
