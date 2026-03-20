package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.core.lightning.LightningException;
import com.greenharborlabs.paygate.core.lightning.LightningTimeoutException;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Decorates a {@link LightningBackend} to enforce a hard deadline on all
 * operations using virtual threads. This is a defense-in-depth safety net:
 * even if a backend implementation has a bug in its own timeout handling,
 * this wrapper guarantees calls will not block beyond the configured duration.
 *
 * <p>Each operation is submitted to a virtual-thread-per-task executor and
 * awaited with {@link Future#get(long, TimeUnit)}. On timeout the virtual
 * thread is interrupted and a {@link LightningTimeoutException} is thrown.
 */
public class TimeoutEnforcingLightningBackendWrapper implements LightningBackend, java.io.Closeable {

    private final LightningBackend delegate;
    private final int timeoutSeconds;
    private final ExecutorService executor;

    public TimeoutEnforcingLightningBackendWrapper(LightningBackend delegate, int timeoutSeconds) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds must be > 0, got: " + timeoutSeconds);
        }
        this.delegate = delegate;
        this.timeoutSeconds = timeoutSeconds;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public Invoice createInvoice(long amountSats, String memo) {
        return executeWithTimeout(() -> delegate.createInvoice(amountSats, memo), "createInvoice");
    }

    @Override
    public Invoice lookupInvoice(byte[] paymentHash) {
        return executeWithTimeout(() -> delegate.lookupInvoice(paymentHash), "lookupInvoice");
    }

    @Override
    public boolean isHealthy() {
        try {
            return executeWithTimeout(delegate::isHealthy, "isHealthy");
        } catch (LightningTimeoutException _) {
            return false;
        }
    }

    /**
     * Returns the wrapped backend, useful for unwrapping in tests or diagnostics.
     */
    public LightningBackend getDelegate() {
        return delegate;
    }

    @Override
    public void close() {
        executor.close();
    }

    private <T> T executeWithTimeout(Callable<T> task, String operationName) {
        Future<T> future = executor.submit(task);
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new LightningTimeoutException(
                    "Backend " + operationName + " timed out after " + timeoutSeconds + "s", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof LightningException le) {
                throw le;
            }
            throw new LightningException("Backend " + operationName + " failed", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LightningException("Interrupted during " + operationName, e);
        }
    }
}
