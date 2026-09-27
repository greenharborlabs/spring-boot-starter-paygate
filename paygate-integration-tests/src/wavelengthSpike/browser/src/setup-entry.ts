import { createWebClient, defaultConfig } from "@lightninglabs/wavelength-web";

// Operator-only page; never run under Playwright payment automation or retain the seed in storage.
const creationMode = new URLSearchParams(location.search).get("mode") === "fresh";
const client = createWebClient({
  runtimeBaseUrl: new URL("/runtime/v0.1.1/", location.origin).href,
  workerURL: "/assets/wavelength-worker.js",
  runtimeCache: false,
  debug: false,
});
const state = element<HTMLElement>("state");
const createSection = element<HTMLElement>("create-section");
const backupSection = element<HTMLElement>("backup-section");
const unlockSection = element<HTMLElement>("unlock-section");
const fundSection = element<HTMLElement>("fund-section");
const createPassword = element<HTMLInputElement>("create-password");
const createConfirm = element<HTMLInputElement>("create-confirm");
const unlockPassword = element<HTMLInputElement>("unlock-password");
const createButton = element<HTMLButtonElement>("create");
const unlockButton = element<HTMLButtonElement>("unlock");
const seed = element<HTMLElement>("seed");
const backupButton = element<HTMLButtonElement>("backed-up");
const verifyBackupButton = element<HTMLButtonElement>("verify-backup");
const firstWord = element<HTMLInputElement>("first-word");
const lastWord = element<HTMLInputElement>("last-word");
const depositButton = element<HTMLButtonElement>("deposit");
const balanceButton = element<HTMLButtonElement>("balance");
const diagnosticButton = element<HTMLButtonElement>("diagnose");
let backupPending = false;
let creationUnresolved = false;
let busy = false;

window.addEventListener("beforeunload", (event) => {
  if (backupPending || creationUnresolved) {
    event.preventDefault();
    event.returnValue = "";
  }
});

async function showWalletState(): Promise<void> {
  const info = await client.getInfo();
  createSection.hidden = !creationMode || info.network !== "signet" || info.walletState !== "none";
  unlockSection.hidden = info.network !== "signet" || info.walletState !== "locked";
  fundSection.hidden = info.network !== "signet" || info.walletState !== "ready" || backupPending || creationUnresolved;
  if (info.network !== "signet") {
    state.textContent = "Wrong network; do not create or fund a wallet";
  } else if (info.walletState === "none") {
    state.textContent = creationMode
      ? "No wallet in this fresh profile. Operator may create one."
      : "No wallet found in existing profile. Stop; do not create or fund.";
  } else if (info.walletState === "locked") {
    state.textContent = "Existing wallet locked. Unlock privately.";
  } else if (info.walletState === "ready") {
    state.textContent = "Signet wallet ready. Verify funding before T7.";
  } else {
    state.textContent = "Wallet is not ready; wait for sync or inspect out of band.";
  }
}

createButton.addEventListener("click", () => {
  if (busy || createSection.hidden) return;
  let password = createPassword.value;
  let confirmation = createConfirm.value;
  createPassword.value = "";
  createConfirm.value = "";
  if (password.length < 12 || password !== confirmation) {
    password = "";
    confirmation = "";
    state.textContent = "Use matching passwords of at least 12 characters; retry privately.";
    return;
  }
  confirmation = "";
  busy = true;
  creationUnresolved = true;
  createButton.disabled = true;
  createSection.hidden = true;
  state.textContent = "Creating wallet. Do not close this page.";
  void (async () => {
    try {
      const result = await client.createWallet({ password });
      password = "";
      if (!Array.isArray(result.mnemonic) || result.mnemonic.length !== 24 ||
          !result.mnemonic.every((word) => typeof word === "string" && /^[a-z]+$/.test(word))) {
        state.textContent = "Seed backup unavailable; do not fund this wallet. Stop and inspect out of band.";
        return;
      }
      backupPending = true;
      seed.textContent = result.mnemonic.join(" ");
      backupSection.hidden = false;
      creationUnresolved = false;
      try { result.mnemonic.fill(""); } catch { /* Backup remains visible; never log mnemonic. */ }
      state.textContent = "Write down and verify the seed offline before clearing it.";
    } catch {
      state.textContent = "Creation result uncertain. Do not retry or fund; inspect wallet and backup state privately.";
    } finally {
      password = "";
      busy = false;
    }
  })();
});

verifyBackupButton.addEventListener("click", () => {
  if (!backupPending) return;
  const words = seed.textContent?.split(" ") ?? [];
  const match = firstWord.value.trim().toLowerCase() === words[0] &&
    lastWord.value.trim().toLowerCase() === words[23];
  firstWord.value = "";
  lastWord.value = "";
  if (!match) {
    state.textContent = "Backup check failed. Recheck your offline copy; do not fund.";
    return;
  }
  verifyBackupButton.disabled = true;
  backupButton.hidden = false;
  state.textContent = "Verify all 24 words offline, then clear the seed from this page.";
});

backupButton.addEventListener("click", () => {
  if (!backupPending || backupButton.hidden) return;
  seed.textContent = "";
  backupPending = false;
  backupSection.hidden = true;
  void showWalletState().catch(() => { state.textContent = "Wallet status unavailable; do not fund."; });
});

unlockButton.addEventListener("click", () => {
  if (busy || unlockSection.hidden) return;
  let password = unlockPassword.value;
  unlockPassword.value = "";
  busy = true;
  unlockButton.disabled = true;
  void client.unlockWallet({ password })
    .then(showWalletState)
    .catch(() => { state.textContent = "Unlock unavailable; inspect privately."; })
    .finally(() => { password = ""; busy = false; unlockButton.disabled = false; });
});

depositButton.addEventListener("click", () => {
  if (busy || fundSection.hidden) return;
  busy = true;
  depositButton.disabled = true;
  element<HTMLElement>("address").textContent = "";
  void client.deposit().then((result) => {
    if (!/^tb1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]{10,100}$/.test(result.address)) {
      state.textContent = "Unexpected address; do not fund.";
      return;
    }
    element<HTMLElement>("address").textContent = result.address;
    state.textContent = "Verify the signet address and fund out of band only with explicit operator approval.";
  }).catch(() => { state.textContent = "Deposit address unavailable; do not fund."; })
    .finally(() => { busy = false; depositButton.disabled = false; });
});

balanceButton.addEventListener("click", () => {
  if (busy || fundSection.hidden) return;
  busy = true;
  balanceButton.disabled = true;
  void client.balance().then((result) => {
    const { confirmedSat, pendingInSat, pendingOutSat, creditAvailableSat } = result;
    if (![confirmedSat, pendingInSat, pendingOutSat, creditAvailableSat].every(Number.isSafeInteger)) {
      state.textContent = "Invalid balance; inspect out of band.";
      return;
    }
    element<HTMLElement>("balance-value").textContent =
      `Confirmed: ${confirmedSat} sat; pending in: ${pendingInSat} sat; pending out: ${pendingOutSat} sat; credits: ${creditAvailableSat} sat.`;
  }).catch(() => { state.textContent = "Balance unavailable; inspect out of band."; })
    .finally(() => { busy = false; balanceButton.disabled = false; });
});

diagnosticButton.addEventListener("click", () => {
  if (busy || fundSection.hidden) return;
  busy = true;
  diagnosticButton.disabled = true;
  const output = element<HTMLElement>("diagnostic");
  output.textContent = "Checking read-only wallet state. Do not retry while pending.";
  void Promise.all([
    client.list({ view: "activity", kinds: ["deposit"], limit: 50 }),
    client.list({ view: "vtxos", limit: 50 }),
    client.list({ view: "onchain", limit: 50 }),
  ]).then(([activity, vtxos, onchain]) => {
    if (activity.view !== "activity" || !activity.activity ||
        vtxos.view !== "vtxos" || !vtxos.vtxos ||
        onchain.view !== "onchain" || !onchain.onchain) {
      throw new Error("Unexpected SDK list variant");
    }
    const { entries, total, hasMore, nextCursor } = activity.activity;
    const { vtxos: liveVtxos, total: liveCount } = vtxos.vtxos;
    const { txs: onchainTxs, total: onchainCount, hasMore: onchainMore } = onchain.onchain;
    const validCount = (value: number): boolean => Number.isSafeInteger(value) && value >= 0;
    if (!Array.isArray(entries) || entries.length > 50 || !validCount(total) || total !== entries.length ||
        typeof hasMore !== "boolean" || typeof nextCursor !== "string" ||
        (hasMore && !nextCursor) ||
        !Array.isArray(liveVtxos) || liveVtxos.length > 50 || !validCount(liveCount) || liveCount < liveVtxos.length ||
        !liveVtxos.every((v) => v.status === "live" && validCount(v.amountSat)) ||
        !Array.isArray(onchainTxs) || onchainTxs.length > 50 || !validCount(onchainCount) ||
        onchainCount < onchainTxs.length || typeof onchainMore !== "boolean") {
      throw new Error("Unexpected SDK list shape");
    }
    const statuses = { pending: 0, complete: 0, failed: 0 };
    const phases = { waiting_for_confirmation: 0, settling: 0, confirmed: 0 };
    const knownPhases = new Set([
      "unspecified", "request_created", "waiting_for_payment", "payment_detected", "settling",
      "confirmed", "refunding", "refunded", "failed", "waiting_for_confirmation",
    ]);
    for (const entry of entries) {
      if (entry.kind !== "deposit" || !Object.hasOwn(statuses, entry.status)) {
        throw new Error("Unexpected deposit activity");
      }
      statuses[entry.status as keyof typeof statuses]++;
      const phase = entry.progress?.phase;
      if (phase !== undefined && !knownPhases.has(phase)) throw new Error("Unexpected deposit phase");
      if (phase && Object.hasOwn(phases, phase)) phases[phase as keyof typeof phases]++;
    }
    // Never render entry IDs, addresses, outpoints, txids, phase labels or errors.
    output.textContent = `Deposit activity (up to 50): pending=${statuses.pending}, complete=${statuses.complete}, failed=${statuses.failed}` +
      `${activity.activity.hasMore ? "; more pages exist" : ""}. ` +
      `Phases: awaiting confirmation=${phases.waiting_for_confirmation}, settling=${phases.settling}, confirmed=${phases.confirmed}, other or absent=${entries.length - phases.waiting_for_confirmation - phases.settling - phases.confirmed}. ` +
      `Live VTXOs=${liveCount}; on-chain history entries (all wallet)=${onchainCount}. ` +
      "Counts cannot prove this deposit's stage, fee readiness or custody.";
  }).catch(() => {
    output.textContent = "Read-only boarding check unavailable; no conclusion. Do not retry or fund again.";
  }).finally(() => { busy = false; diagnosticButton.disabled = false; });
});

void client.ready()
  .then(() => client.start(defaultConfig("signet", { debugLevel: "off" })))
  .then(showWalletState)
  .catch(() => { state.textContent = "Pinned runtime unavailable. Do not create or fund a wallet."; });

function element<T extends HTMLElement>(id: string): T {
  const found = document.getElementById(id);
  if (found === null) throw new Error("Operator setup element unavailable");
  return found as T;
}
