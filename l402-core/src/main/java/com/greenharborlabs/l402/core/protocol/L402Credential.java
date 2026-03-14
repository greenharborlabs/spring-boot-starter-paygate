package com.greenharborlabs.l402.core.protocol;

import com.greenharborlabs.l402.core.lightning.PaymentPreimage;
import com.greenharborlabs.l402.core.macaroon.Macaroon;
import com.greenharborlabs.l402.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.l402.core.macaroon.MacaroonSerializer;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An authenticated L402 credential consisting of a macaroon, preimage proof-of-payment,
 * and the hex-encoded token identifier.
 */
public record L402Credential(Macaroon macaroon, PaymentPreimage preimage, String tokenId,
                              List<Macaroon> additionalMacaroons) {

    public L402Credential {
        Objects.requireNonNull(macaroon, "macaroon must not be null");
        Objects.requireNonNull(preimage, "preimage must not be null");
        Objects.requireNonNull(tokenId, "tokenId must not be null");
        Objects.requireNonNull(additionalMacaroons, "additionalMacaroons must not be null");
        if (tokenId.isEmpty()) {
            throw new IllegalArgumentException("tokenId must not be empty");
        }
        additionalMacaroons = List.copyOf(additionalMacaroons);
    }

    /**
     * Backward-compatible constructor for single-token credentials.
     */
    public L402Credential(Macaroon macaroon, PaymentPreimage preimage, String tokenId) {
        this(macaroon, preimage, tokenId, List.of());
    }

    private static final Pattern HEADER_PATTERN =
            Pattern.compile("(LSAT|L402) ([A-Za-z0-9+/=,]+):([a-fA-F0-9]{64})");
    private static final HexFormat HEX = HexFormat.of();

    /**
     * Parses an L402/LSAT Authorization header into an {@link L402Credential}.
     *
     * @param authorizationHeader the raw Authorization header value
     * @return a parsed credential
     * @throws L402Exception with {@link ErrorCode#MALFORMED_HEADER} on any parse failure
     */
    public static L402Credential parse(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isEmpty()) {
            throw new L402Exception(ErrorCode.MALFORMED_HEADER,
                    "Authorization header must not be null or empty", null);
        }

        Matcher matcher = HEADER_PATTERN.matcher(authorizationHeader);
        if (!matcher.matches()) {
            throw new L402Exception(ErrorCode.MALFORMED_HEADER,
                    "Authorization header does not match L402/LSAT format", null);
        }

        String tokensString = matcher.group(2);
        String preimageHex = matcher.group(3);

        // Split on comma to support multi-token headers: "token1,token2:preimage"
        String[] tokenParts = tokensString.split(",", -1);

        // Validate no empty tokens (e.g. from "token1,,token2" or ",token1" or "token1,")
        for (String part : tokenParts) {
            if (part.isEmpty()) {
                throw new L402Exception(ErrorCode.MALFORMED_HEADER,
                        "Empty token in multi-token header", null);
            }
        }

        // Decode primary (first) macaroon
        Macaroon primaryMacaroon = decodeMacaroon(tokenParts[0]);

        // Decode additional macaroons
        List<Macaroon> additionalMacaroons;
        if (tokenParts.length > 1) {
            additionalMacaroons = new ArrayList<>(tokenParts.length - 1);
            for (int i = 1; i < tokenParts.length; i++) {
                additionalMacaroons.add(decodeMacaroon(tokenParts[i]));
            }
            additionalMacaroons = Collections.unmodifiableList(additionalMacaroons);
        } else {
            additionalMacaroons = List.of();
        }

        PaymentPreimage preimage;
        try {
            preimage = PaymentPreimage.fromHex(preimageHex);
        } catch (IllegalArgumentException e) {
            throw new L402Exception(ErrorCode.MALFORMED_HEADER,
                    "Invalid preimage hex: " + e.getMessage(), null);
        }

        MacaroonIdentifier id = MacaroonIdentifier.decode(primaryMacaroon.identifier());
        String tokenId = HEX.formatHex(id.tokenId());

        return new L402Credential(primaryMacaroon, preimage, tokenId, additionalMacaroons);
    }

    private static Macaroon decodeMacaroon(String base64Token) {
        byte[] macaroonBytes;
        try {
            macaroonBytes = Base64.getDecoder().decode(base64Token);
        } catch (IllegalArgumentException e) {
            throw new L402Exception(ErrorCode.MALFORMED_HEADER,
                    "Invalid base64 macaroon encoding: " + e.getMessage(), null);
        }

        try {
            return MacaroonSerializer.deserializeV2(macaroonBytes);
        } catch (IllegalArgumentException e) {
            throw new L402Exception(ErrorCode.MALFORMED_HEADER,
                    "Invalid macaroon data: " + e.getMessage(), null);
        }
    }

    @Override
    public String toString() {
        return "L402Credential[tokenId=" + tokenId + "]";
    }
}
