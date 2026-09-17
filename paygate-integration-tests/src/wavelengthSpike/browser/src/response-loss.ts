import type { PaymentClient } from "./payment-harness.js";

/** Drops the entire real dispatch response, not the payment or a fabricated settlement event. */
export function loseDispatchResponse(
  client: PaymentClient,
  beforeDispatch: () => Promise<void>,
  responseLost: () => Promise<void>,
): PaymentClient {
  return {
    list: (request) => client.list(request),
    prepareSend: (request) => client.prepareSend(request),
    subscribe: (listener) => client.subscribe(listener),
    startActivity: (request) => client.startActivity(request),
    stopActivity: () => client.stopActivity(),
    sendPrepared: async (prepared) => {
      await beforeDispatch();
      try {
        await client.sendPrepared(prepared);
      } finally {
        await responseLost();
      }
      return new Promise(() => undefined);
    },
  };
}
