package com.greenharborlabs.paygate.core.lightning;

/** Status of a Lightning Network invoice. */
public enum InvoiceStatus {
  PENDING,
  SETTLED,
  CANCELLED,
  EXPIRED
}
