package com.greenharborlabs.paygate.spring;

/** Raised when bounded request-body capture for digest binding exceeds configured limits. */
public final class RequestBodyTooLargeException extends RuntimeException {

  public RequestBodyTooLargeException(String message) {
    super(message);
  }
}
