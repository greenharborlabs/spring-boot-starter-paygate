package com.greenharborlabs.l402.core.lightning;

public interface LightningBackend {

    /**
     * Creates a new lightning invoice for the given amount and memo.
     *
     * @param amountSats the invoice amount in satoshis
     * @param memo       a human-readable description attached to the invoice
     * @return the created invoice
     * @throws LightningTimeoutException if the operation exceeds the configured timeout
     * @throws LightningException on connectivity or backend failures
     */
    Invoice createInvoice(long amountSats, String memo);

    /**
     * Looks up an existing invoice by its payment hash.
     *
     * @param paymentHash the 32-byte payment hash identifying the invoice
     * @return the invoice details
     * @throws LightningTimeoutException if the operation exceeds the configured timeout
     * @throws LightningException on connectivity or backend failures
     */
    Invoice lookupInvoice(byte[] paymentHash);

    /**
     * Returns whether the lightning backend is reachable and operational.
     * <p>
     * Implementations MUST return {@code false} on timeout rather than
     * throwing {@link LightningTimeoutException}. This method must never
     * propagate timeout exceptions to callers.
     *
     * @return {@code true} if healthy, {@code false} otherwise
     */
    boolean isHealthy();
}
