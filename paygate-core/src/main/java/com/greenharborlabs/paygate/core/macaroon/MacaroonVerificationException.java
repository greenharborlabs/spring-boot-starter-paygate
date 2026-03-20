package com.greenharborlabs.paygate.core.macaroon;

public class MacaroonVerificationException extends RuntimeException {

    public MacaroonVerificationException(String message) {
        super(message);
    }
}
