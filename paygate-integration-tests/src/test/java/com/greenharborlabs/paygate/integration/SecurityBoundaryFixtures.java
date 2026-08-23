package com.greenharborlabs.paygate.integration;

import java.util.concurrent.atomic.AtomicLong;

/** Deterministic, non-secret inputs shared by security-boundary integration regressions. */
public final class SecurityBoundaryFixtures {

  public static final String CHEAP_ROUTE = "/security-boundary/cheap";
  public static final String EXPENSIVE_ROUTE = "/security-boundary/expensive";
  public static final String ITEM_ROUTE_PATTERN = "/security-boundary/items/{itemId}";
  public static final String FIRST_ITEM_ROUTE = "/security-boundary/items/fixture-alpha";
  public static final String SECOND_ITEM_ROUTE = "/security-boundary/items/fixture-beta";

  public static final String GET_METHOD = "GET";
  public static final String POST_METHOD = "POST";
  public static final String HEAD_METHOD = "HEAD";

  public static final String CAPABILITY_MARKER = "security-boundary-capability-marker";
  public static final String NO_CAPABILITY_MARKER = "~";

  public static final String MACAROON_MARKER = "c2VjdXJpdHktYm91bmRhcnktbWFjYXJvb24tbWFya2Vy";
  public static final String PREIMAGE_MARKER =
      "73656375726974792d626f756e646172792d707265696d6167652d6d61726b21";
  public static final String AUTHORIZATION_HEADER_MARKER =
      "L402 " + MACAROON_MARKER + ":" + PREIMAGE_MARKER;

  /**
   * Creates an isolated side-effect recorder for security-boundary tests.
   *
   * <p>Tests use this instead of inferring side effects from a response. Each counter represents an
   * action that must not occur on a fail-closed path.
   */
  public static SideEffectRecorder recordingSideEffects() {
    return new SideEffectRecorder();
  }

  private SecurityBoundaryFixtures() {}

  /** Records side effects that security-boundary tests must be able to rule out explicitly. */
  public static final class SideEffectRecorder {
    private final AtomicLong protectedHandlerExecutions = new AtomicLong();
    private final AtomicLong invoiceCreations = new AtomicLong();
    private final AtomicLong rootKeyOperations = new AtomicLong();
    private final AtomicLong protocolFormattingOperations = new AtomicLong();

    /** Records protected application handler execution. */
    public void recordProtectedHandlerExecution() {
      protectedHandlerExecutions.incrementAndGet();
    }

    /** Records Lightning invoice creation. */
    public void recordInvoiceCreation() {
      invoiceCreations.incrementAndGet();
    }

    /** Records a root-key store operation. */
    public void recordRootKeyOperation() {
      rootKeyOperations.incrementAndGet();
    }

    /** Records protocol challenge formatting. */
    public void recordProtocolFormatting() {
      protocolFormattingOperations.incrementAndGet();
    }

    /** Returns an atomic snapshot suitable for assertions after a request completes. */
    public SideEffectCounts snapshot() {
      return new SideEffectCounts(
          protectedHandlerExecutions.get(),
          invoiceCreations.get(),
          rootKeyOperations.get(),
          protocolFormattingOperations.get());
    }

    /** Clears every side-effect count before the next request. */
    public void reset() {
      protectedHandlerExecutions.set(0);
      invoiceCreations.set(0);
      rootKeyOperations.set(0);
      protocolFormattingOperations.set(0);
    }
  }

  /** Immutable counts captured from a {@link SideEffectRecorder}. */
  public record SideEffectCounts(
      long protectedHandlerExecutions,
      long invoiceCreations,
      long rootKeyOperations,
      long protocolFormattingOperations) {}
}
