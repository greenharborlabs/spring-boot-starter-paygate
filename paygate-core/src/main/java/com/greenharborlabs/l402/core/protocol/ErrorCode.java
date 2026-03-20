package com.greenharborlabs.l402.core.protocol;

public enum ErrorCode {

    INVALID_MACAROON(401),
    INVALID_PREIMAGE(401),
    EXPIRED_CREDENTIAL(401),
    INVALID_SERVICE(401),
    REVOKED_CREDENTIAL(401),
    LIGHTNING_UNAVAILABLE(503),
    MALFORMED_HEADER(400);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
