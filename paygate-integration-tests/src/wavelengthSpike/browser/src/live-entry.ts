import { createWebClient, defaultConfig } from "@lightninglabs/wavelength-web";
import { BrowserPaymentHarness } from "./payment-harness.js";
import { loseDispatchResponse } from "./response-loss.js";
import { RecoveryStore } from "./recovery-store.js";

// Direct vendor proof only: the synthetic macaroon and proof sink are NOT a Paygate unlock.
declare global {
  interface Window {
    spikeDispatch: () => Promise<void>;
    spikeResponseLost: () => Promise<void>;
    spike: {
      ready: boolean;
      recovered: boolean;
      begin: (invoice: string) => void;
      recover: () => void;
    };
  }
}

const client = createWebClient({
  runtimeBaseUrl: new URL("/runtime/v0.1.1/", location.origin).href,
  workerURL: "/assets/wavelength-worker.js",
  runtimeCache: false,
  debug: false,
});
const store = new RecoveryStore(sessionStorage, location.origin);
let recovery = false;
let proofSeen = false;
const boundary = loseDispatchResponse(client,
  () => window.spikeDispatch(), () => window.spikeResponseLost());
const harness = new BrowserPaymentHarness(boundary, store, async () => {
  // Called only after the original stored challenge's preimage was locally hash-verified.
  if (!recovery) return 409; // Preserve recovery metadata even if settlement raced reload.
  proofSeen = true;
  return 200; // Direct proof sink; deliberately not a protected HTTP request.
});
window.spike = {
  ready: false,
  recovered: false,
  begin: (invoice) => {
    void harness.dispatch(
      `L402 version="0", token="AQ==", macaroon="AQ==", invoice="${invoice}"`,
      `${location.origin}/direct-proof`,
    ).catch(() => undefined);
  },
  recover: () => {
    recovery = true;
    void harness.recover().then((result) => {
      window.spike.recovered = proofSeen && result.state === "unlocked";
    }).catch(() => undefined);
  },
};

const password = document.querySelector<HTMLInputElement>("#password")!;
const unlock = document.querySelector<HTMLButtonElement>("#unlock")!;
const state = document.querySelector<HTMLElement>("#state")!;
unlock.addEventListener("click", () => {
  unlock.disabled = true;
  let value = password.value;
  password.value = "";
  void client.unlockWallet({ password: value })
    .then(checkReady)
    .catch(() => { state.textContent = "Unlock unavailable; inspect wallet out of band"; })
    .finally(() => { value = ""; unlock.disabled = false; });
});
async function checkReady(): Promise<void> {
  const status = await client.status();
  window.spike.ready = status.ready && status.unlocked && status.network === "signet";
  state.textContent = window.spike.ready ? "Wallet ready on signet" : "Operator unlock required";
}
void client.ready()
  .then(() => client.start(defaultConfig("signet", { debugLevel: "off" })))
  .then(checkReady)
  .catch(() => { state.textContent = "Operator unlock required; no wallet is created by this page"; });
