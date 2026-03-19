package com.greenharborlabs.l402.core.protocol;

public class L402Exception extends RuntimeException {

    private final ErrorCode errorCode;
    private final String tokenId;

    public L402Exception(ErrorCode errorCode, String message, String tokenId) {
        super(message);
        this.errorCode = errorCode;
        this.tokenId = tokenId;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public String getTokenId() {
        return tokenId;
    }
}
