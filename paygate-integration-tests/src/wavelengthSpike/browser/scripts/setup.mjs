import { lstat, readdir, realpath } from "node:fs/promises";
import { getuid } from "node:process";
import { resolve, sep } from "node:path";
import { dirname } from "node:path";
import { fileURLToPath } from "node:url";

import { chromium } from "@playwright/test";
import { startHarnessServer } from "./server.mjs";

// Run only in the operator's own terminal. No screenshot, trace, browser event log,
// seed/password argument, automation callback, or payment action is installed here.
let context;
let server;
let closing;
async function close() {
  closing ??= (async () => {
    await context?.close();
    await server?.close();
  })();
  return closing;
}
process.on("SIGINT", () => { void close(); });
process.on("SIGTERM", () => { void close(); });

try {
  const port = Number(process.env.WAVELENGTH_SPIKE_BROWSER_PORT);
  const mode = process.env.WAVELENGTH_SPIKE_SETUP_MODE ?? "fresh";
  if (mode !== "fresh" && mode !== "existing") throw new Error("Invalid setup mode");
  const profilePath = process.env.WAVELENGTH_SPIKE_BROWSER_PROFILE_DIR;
  const runtimeDirectory = process.env.WAVELENGTH_SPIKE_RUNTIME_DIR;
  if (!Number.isInteger(port) || port < 1024 || port > 65535 || !profilePath || !runtimeDirectory) {
    throw new Error("Missing operator paths or fixed port");
  }
  const profile = resolve(profilePath);
  const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
  const repository = resolve(root, "../../../..");
  if (profile === repository || profile.startsWith(`${repository}${sep}`) || await realpath(profile) !== profile) {
    throw new Error("Invalid profile path");
  }
  const metadata = await lstat(profile);
  if (!metadata.isDirectory() || metadata.isSymbolicLink() || metadata.uid !== getuid() || (metadata.mode & 0o077) !== 0) {
    throw new Error("Profile must be an owned private directory");
  }
  const entries = await readdir(profile);
  if ((mode === "fresh" && entries.length !== 0) || (mode === "existing" && entries.length === 0)) {
    throw new Error("Profile does not match requested mode");
  }
  server = await startHarnessServer(runtimeDirectory, port, true, true);
  // Rebuild after acquiring the port, before opening any browser page. Never serve
  // a stale setup bundle or overwrite assets while a live runner owns this port.
  await import("./build.mjs");
  context = await chromium.launchPersistentContext(profile, {
    headless: false,
    recordHar: undefined,
    recordVideo: undefined,
    timeout: 30_000,
  });
  if (context.pages().length > 1) throw new Error("Single setup tab required");
  const page = context.pages()[0] ?? await context.newPage();
  page.on("close", () => { void close(); });
  context.on("page", () => { if (context.pages().length > 1) void close(); });
  await page.goto(`${server.origin}/setup?mode=${mode}`, { waitUntil: "domcontentloaded", timeout: 15_000 });
  process.stdout.write(`Operator-only setup: ${server.origin}/setup?mode=${mode}\nClose the browser window to end setup. Never share its seed or password.\n`);
  await new Promise((done) => context.once("close", done));
} catch {
  process.exitCode = 1;
  process.stderr.write("Operator setup unavailable; no diagnostics or secrets recorded.\n");
} finally {
  await close().catch(() => { process.exitCode = 1; });
}
