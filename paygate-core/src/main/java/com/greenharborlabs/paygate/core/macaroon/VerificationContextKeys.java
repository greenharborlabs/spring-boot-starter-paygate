package com.greenharborlabs.paygate.core.macaroon;

/**
 * Standard keys for request metadata entries in {@link L402VerificationContext}. Used by delegation
 * caveat verifiers to look up request information.
 */
public final class VerificationContextKeys {

  /** The concrete path of the current request, used for holder caveat attenuation. */
  public static final String REQUEST_PATH = "request.path";

  /** The canonical registered route identity, used for exact route caveat validation. */
  public static final String REQUEST_ROUTE = "request.route";

  /** The actual HTTP method of the current request. */
  public static final String REQUEST_METHOD = "request.method";

  public static final String REQUEST_DIGEST = "request.digest";
  public static final String REQUEST_CLIENT_IP = "request.client_ip";
  public static final String REQUESTED_CAPABILITY = "request.capability";

  private VerificationContextKeys() {
    // utility class
  }
}
