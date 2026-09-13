import { createWebClient } from "@lightninglabs/wavelength-web";

import { BrowserPaymentHarness } from "./payment-harness.js";
import { RecoveryStore } from "./recovery-store.js";

const status = document.querySelector<HTMLElement>("[data-harness-state]");
if (status === null) {
  throw new Error("Harness status element is unavailable");
}

const requiredCapabilities = {
  secureContext: globalThis.isSecureContext,
  webAssembly: typeof WebAssembly === "object",
  worker: typeof Worker === "function",
  webCrypto: typeof crypto?.subtle === "object",
  webLocks: typeof navigator.locks === "object",
  opfs: typeof navigator.storage?.getDirectory === "function",
  sessionStorage: storageAvailable(),
};

if (Object.values(requiredCapabilities).some((available) => !available)) {
  status.dataset.harnessState = "unsupported";
  status.textContent = "Required browser capability unavailable";
} else {
  // Construct the payment boundary without administering the wallet. Wallet create, unlock,
  // funding, and boarding remain explicit operator actions outside this state machine.
  const client = createWebClient({
    runtimeBaseUrl: new URL("/runtime/v0.1.1/", location.origin).href,
    workerURL: "/assets/wavelength-worker.js",
    runtimeCache: false,
    debug: false,
  });
  const store = new RecoveryStore(sessionStorage, location.origin);
  void new BrowserPaymentHarness(
    client,
    store,
    async (target, authorization) => {
      const response = await fetch(target, {
        method: "GET",
        credentials: "same-origin",
        cache: "no-store",
        headers: { Authorization: authorization },
      });
      return response.status;
    },
  );

  status.dataset.harnessState = "capability_ready";
  status.textContent = "Browser payment capability ready";

  if (new URLSearchParams(location.search).get("runtime") === "ready") {
    client
      .ready()
      .then(() => {
        status.dataset.harnessState = "runtime_ready";
        status.textContent = "Pinned Wavelength runtime ready";
        client.dispose();
      })
      .catch(() => {
        status.dataset.harnessState = "runtime_failed";
        status.textContent = "Pinned Wavelength runtime failed";
        client.dispose();
      });
  } else {
    client.dispose();
  }
}

function storageAvailable(): boolean {
  const key = "paygate.wavelength.storage-probe";
  try {
    sessionStorage.setItem(key, "1");
    const available = sessionStorage.getItem(key) === "1";
    sessionStorage.removeItem(key);
    return available;
  } catch {
    return false;
  }
}
