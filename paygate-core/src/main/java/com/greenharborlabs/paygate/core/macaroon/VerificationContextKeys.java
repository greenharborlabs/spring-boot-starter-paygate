package com.greenharborlabs.paygate.core.macaroon;

/**
 * Standard keys for request metadata entries in {@link L402VerificationContext}. Used by delegation
 * caveat verifiers to look up request information.
 */
public final class VerificationContextKeys {

  public static final String REQUEST_PATH = "request.path";
  public static final String REQUEST_METHOD = "request.method";
  public static final String REQUEST_CLIENT_IP = "request.client_ip";
  public static final String REQUESTED_CAPABILITY = "request.capability";

  private VerificationContextKeys() {
    // utility class
  }
}
