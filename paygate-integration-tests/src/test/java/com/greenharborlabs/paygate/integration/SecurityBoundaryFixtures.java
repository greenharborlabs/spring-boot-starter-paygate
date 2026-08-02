package com.greenharborlabs.paygate.integration;

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

  private SecurityBoundaryFixtures() {}
}
