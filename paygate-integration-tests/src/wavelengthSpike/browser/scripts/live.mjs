import { chromium } from "@playwright/test";
import { startHarnessServer } from "./server.mjs";

// No console/pageerror/request/response listeners, HAR, traces, video, screenshots, or raw errors.
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
process.on("SIGTERM", () => { void close().finally(() => process.exit(1)); });
const watchdog = setTimeout(() => { void close().finally(() => process.exit(1)); }, 300_000);
try {
  let size = 0;
  const chunks = [];
  for await (const chunk of process.stdin) {
    size += chunk.length;
    if (size > 8192) throw new Error("invalid input");
    chunks.push(chunk);
  }
  const { invoice } = JSON.parse(Buffer.concat(chunks).toString("utf8"));
  chunks.forEach((chunk) => chunk.fill(0));
  if (typeof invoice !== "string" || !/^lntb[0-9a-z]{1,4091}$/.test(invoice)) throw new Error("invalid invoice");
  const port = Number(process.env.WAVELENGTH_SPIKE_BROWSER_PORT);
  if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error("invalid port");
  server = await startHarnessServer(process.env.WAVELENGTH_SPIKE_RUNTIME_DIR, port, true);
  context = await chromium.launchPersistentContext(process.env.WAVELENGTH_SPIKE_BROWSER_PROFILE_DIR, {
    headless: false,
    recordHar: undefined,
    recordVideo: undefined,
    timeout: 30_000,
  });
  if (context.pages().length > 1) throw new Error("single tab required");
  const page = context.pages()[0] ?? await context.newPage();
  page.setDefaultTimeout(15_000);
  let dispatches = 0;
  let dispatchedAt = 0;
  let lost = false;
  let reloaded = false;
  await context.exposeBinding("spikeDispatch", ({ frame }) => {
    if (frame !== page.mainFrame() || reloaded || dispatches !== 0) throw new Error("dispatch blocked");
    dispatches += 1;
    dispatchedAt = Date.now();
  });
  await context.exposeBinding("spikeResponseLost", ({ frame }) => {
    if (frame !== page.mainFrame() || dispatches !== 1) throw new Error("invalid loss boundary");
    lost = true;
  });
  await page.goto(`${server.origin}/live`, { waitUntil: "domcontentloaded", timeout: 15_000 });
  await page.waitForFunction(() => window.spike?.ready === true, undefined, { timeout: 120_000 });
  await page.evaluate((value) => window.spike.begin(value), invoice);
  const until = Date.now() + 120_000;
  while (!lost && Date.now() < until) await new Promise((resolve) => setTimeout(resolve, 100));
  if (!lost || dispatches !== 1 || !dispatchedAt) throw new Error("dispatch unproved");
  reloaded = true;
  await page.reload({ waitUntil: "domcontentloaded", timeout: 15_000 });
  const remaining = () => {
    const value = 120_000 - (Date.now() - dispatchedAt);
    if (value <= 0) throw new Error("payment outcome unknown");
    return value;
  };
  await page.waitForFunction(() => window.spike?.ready === true, undefined, { timeout: remaining() });
  await page.evaluate(() => window.spike.recover());
  await page.waitForFunction(() => window.spike?.recovered === true, undefined, { timeout: remaining() });
  if (dispatches !== 1 || !lost || !reloaded) throw new Error("recovery unproved");
  await close();
  process.stdout.write("T7_RECOVERED_ONE_DISPATCH\n");
} catch {
  process.exitCode = 1;
} finally {
  clearTimeout(watchdog);
  await close().catch(() => { process.exitCode = 1; });
}
