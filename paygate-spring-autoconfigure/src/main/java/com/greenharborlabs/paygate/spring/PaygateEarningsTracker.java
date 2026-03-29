package com.greenharborlabs.paygate.spring;

import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe tracker for L402 invoice and earnings statistics.
 *
 * <p>Methods are non-final to allow test stubs to override return values.
 */
public class PaygateEarningsTracker {

  private final LongAdder invoicesCreated = new LongAdder();
  private final LongAdder invoicesSettled = new LongAdder();
  private final LongAdder satsEarned = new LongAdder();

  public void recordInvoiceCreated() {
    invoicesCreated.increment();
  }

  public void recordInvoiceSettled(long sats) {
    invoicesSettled.increment();
    satsEarned.add(sats);
  }

  public long getTotalInvoicesCreated() {
    return invoicesCreated.sum();
  }

  public long getTotalInvoicesSettled() {
    return invoicesSettled.sum();
  }

  public long getTotalSatsEarned() {
    return satsEarned.sum();
  }
}
