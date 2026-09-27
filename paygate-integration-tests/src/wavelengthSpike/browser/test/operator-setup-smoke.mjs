import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import { chromium } from "@playwright/test";
import { build } from "esbuild";
import { startHarnessServer } from "../scripts/server.mjs";

// Synthetic-only browser flow: replace the SDK at bundle time. No real wallet,
// seed, funding, requests to vendor endpoints, tracing, or captured browser logs.
const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const compiled = await build({
  entryPoints: [resolve(root, "src/setup-entry.ts")],
  bundle: true,
  write: false,
  format: "esm",
  platform: "browser",
  plugins: [{
    name: "synthetic-wallet-only",
    setup(plugin) {
      plugin.onResolve({ filter: /^@lightninglabs\/wavelength-web$/ }, () => ({ path: "sdk", namespace: "synthetic" }));
      plugin.onLoad({ filter: /.*/, namespace: "synthetic" }, () => ({
        contents: "export const createWebClient = () => window.syntheticWallet; export const defaultConfig = () => ({});",
        loader: "js",
      }));
    },
  }],
});
const profile = await mkdtemp(join(tmpdir(), "wavelength-synthetic-setup-"));
const server = await startHarnessServer(resolve(root, "build/runtime-v0.1.1"), 0, true, true);
let context;
try {
  context = await chromium.launchPersistentContext(profile, {
    headless: true, recordHar: undefined, recordVideo: undefined,
  });
  await context.addInitScript(() => {
    let walletState = "none";
    const counts = { create: 0, deposit: 0, list: 0, send: 0 };
    window.syntheticCounts = counts;
    window.syntheticWallet = {
      ready: async () => {},
      start: async () => ({}),
      getInfo: async () => ({ network: "signet", walletState }),
      createWallet: async () => {
        counts.create += 1;
        walletState = "ready";
        return { mnemonic: Array(24).fill("synthetic") };
      },
      unlockWallet: async () => { walletState = "ready"; return {}; },
      deposit: async () => { counts.deposit += 1; return { address: `tb1q${"q".repeat(39)}` }; },
      balance: async () => ({ confirmedSat: 0, pendingInSat: 0, pendingOutSat: 0, creditAvailableSat: 0 }),
      list: async ({ view }) => {
        counts.list += 1;
        if (view === "activity") return {
          view, activity: { entries: [{ kind: "deposit", status: "pending", id: "synthetic-private-id", progress: { phase: "settling", phaseLabel: "synthetic-private-label" } }], total: 1, hasMore: false, nextCursor: "" },
        };
        if (view === "vtxos") return { view, vtxos: { total: 0, vtxos: [] } };
        if (view === "onchain") return { view, onchain: { total: 1, txs: [{ txid: "synthetic-private-txid" }], hasMore: false } };
        throw new Error("unexpected synthetic view");
      },
    };
  });
  const page = context.pages()[0] ?? await context.newPage();
  await page.route("**/assets/setup-entry.js", (route) => route.fulfill({
    status: 200, contentType: "text/javascript", body: compiled.outputFiles[0].text,
  }));
  await page.goto(`${server.origin}/setup?mode=fresh`, { waitUntil: "domcontentloaded" });
  await page.locator("#create-section:visible").waitFor();
  if (await page.evaluate(() => window.syntheticCounts.create !== 0)) throw new Error("Creation ran on load");
  await page.locator("#create-password").fill("synthetic-not-a-real-password");
  await page.locator("#create-confirm").fill("synthetic-not-a-real-password");
  await page.locator("#create").click();
  await page.locator("#backup-section:visible").waitFor();
  const backupReady = await page.evaluate(() =>
    document.querySelector("#seed").textContent.split(" ").length === 24 &&
    document.querySelector("#create-password").value === "" &&
    document.querySelector("#create-confirm").value === "" &&
    document.querySelector("#fund-section").hidden &&
    window.syntheticCounts.create === 1 && window.syntheticCounts.deposit === 0);
  if (!backupReady) throw new Error("Synthetic backup flow unsafe");
  if (await page.locator("#backed-up").isVisible()) throw new Error("Backup cleared without word check");
  await page.locator("#first-word").fill("incorrect");
  await page.locator("#last-word").fill("synthetic");
  await page.locator("#verify-backup").click();
  if (await page.locator("#backed-up").isVisible()) throw new Error("Wrong backup words accepted");
  await page.locator("#first-word").fill("synthetic");
  await page.locator("#last-word").fill("synthetic");
  await page.locator("#verify-backup").click();
  await page.locator("#backed-up").click();
  await page.locator("#fund-section:visible").waitFor();
  if (await page.locator("#seed").textContent() !== "") throw new Error("Seed display not cleared");
  await page.locator("#deposit").click();
  await page.locator("#address").getByText(/^tb1q/).waitFor();
  if (await page.evaluate(() => window.syntheticCounts.deposit !== 1 || window.syntheticCounts.send !== 0)) {
    throw new Error("Unexpected synthetic wallet operation");
  }
  await page.locator("#diagnose").click();
  await page.getByText("Live VTXOs=0", { exact: false }).waitFor();
  const diagnosticSafe = await page.evaluate(() =>
    window.syntheticCounts.list === 3 && window.syntheticCounts.send === 0 &&
    document.querySelector("#diagnostic").textContent.includes("pending=1") &&
    document.querySelector("#diagnostic").textContent.includes("settling=1") &&
    !document.body.textContent.includes("synthetic-private-"));
  if (!diagnosticSafe) throw new Error("Read-only diagnostics exposed wallet identifiers");

  await page.evaluate(() => { window.syntheticListBase = window.syntheticWallet.list; });
  for (const scenario of ["unknown-phase", "unknown-status", "oversized-page", "wrong-view", "rejected-call"]) {
    await page.evaluate((testCase) => {
      window.syntheticWallet.list = async (request) => {
        if (testCase === "rejected-call") throw new Error("synthetic-private-error");
        const result = await window.syntheticListBase(request);
        if (request.view === "activity") {
          if (testCase === "unknown-phase") result.activity.entries[0].progress.phase = "synthetic-private-phase";
          if (testCase === "unknown-status") result.activity.entries[0].status = "synthetic-private-status";
          if (testCase === "oversized-page") {
            result.activity.entries = Array(51).fill(result.activity.entries[0]);
            result.activity.total = 51;
          }
          if (testCase === "wrong-view") result.view = "vtxos";
        }
        return result;
      };
    }, scenario);
    await page.locator("#diagnose").click();
    await page.getByText("Read-only boarding check unavailable; no conclusion.", { exact: false }).waitFor();
    if (await page.evaluate(() => document.body.textContent.includes("synthetic-private-"))) {
      throw new Error("Malformed diagnostic leaked wallet data");
    }
    await page.locator("#diagnose:enabled").waitFor();
  }
  if (await page.evaluate(() => window.syntheticCounts.send !== 0)) {
    throw new Error("Diagnostic dispatched a send");
  }

  const failedPage = await context.newPage();
  await failedPage.route("**/assets/setup-entry.js", (route) => route.fulfill({
    status: 200, contentType: "text/javascript", body: compiled.outputFiles[0].text,
  }));
  await failedPage.goto(`${server.origin}/setup?mode=fresh`, { waitUntil: "domcontentloaded" });
  await failedPage.locator("#create-section:visible").waitFor();
  await failedPage.evaluate(() => {
    window.syntheticWallet.createWallet = async () => { throw new Error("synthetic-only-failure"); };
  });
  await failedPage.locator("#create-password").fill("synthetic-not-a-real-password");
  await failedPage.locator("#create-confirm").fill("synthetic-not-a-real-password");
  await failedPage.locator("#create").click();
  await failedPage.getByText("Creation result uncertain.", { exact: false }).waitFor();
  const stopped = await failedPage.evaluate(() =>
    document.querySelector("#create").disabled &&
    document.querySelector("#fund-section").hidden &&
    document.querySelector("#seed").textContent === "" &&
    !document.body.textContent.includes("synthetic-only-failure"));
  if (!stopped) throw new Error("Uncertain creation did not fail closed");

  const existingPage = await context.newPage();
  await existingPage.route("**/assets/setup-entry.js", (route) => route.fulfill({
    status: 200, contentType: "text/javascript", body: compiled.outputFiles[0].text,
  }));
  await existingPage.goto(`${server.origin}/setup?mode=existing`, { waitUntil: "domcontentloaded" });
  await existingPage.getByText("No wallet found in existing profile.", { exact: false }).waitFor();
  if (await existingPage.locator("#create-section").isVisible()) {
    throw new Error("Existing profile allowed wallet creation");
  }
  process.stdout.write("synthetic-setup create_backup_clear_deposit_only uncertain_creation_blocked existing_mode_blocked\n");
} finally {
  await context?.close();
  await server.close();
  await rm(profile, { recursive: true, force: true });
}
