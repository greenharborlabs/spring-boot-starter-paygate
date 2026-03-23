package com.greenharborlabs.paygate.protocol.mpp;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

import com.greenharborlabs.paygate.api.ChallengeContext;
import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentReceipt;

/**
 * Utility class that builds a {@link PaymentReceipt} from an MPP credential and challenge context.
 */
public final class MppReceipt {

    private MppReceipt() {
        // utility class
    }

    /**
     * Builds a {@link PaymentReceipt} from the given credential and challenge context.
     *
     * @param credential the validated payment credential (metadata must be {@link MppMetadata})
     * @param context    the challenge context that originated the payment
     * @return a receipt with status "success", method "lightning", and protocol scheme "Payment"
     * @throws NullPointerException     if credential or context is null
     * @throws IllegalArgumentException if credential metadata is not {@link MppMetadata}
     *                                  or the echoed challenge does not contain an "id" entry
     */
    public static PaymentReceipt from(PaymentCredential credential, ChallengeContext context) {
        Objects.requireNonNull(credential, "credential must not be null");
        Objects.requireNonNull(context, "context must not be null");

        if (!(credential.metadata() instanceof MppMetadata mppMetadata)) {
            throw new IllegalArgumentException(
                    "Expected MppMetadata but got " + credential.metadata().getClass().getName());
        }

        String challengeId = mppMetadata.echoedChallenge().get("id");
        if (challengeId == null) {
            throw new IllegalArgumentException("Echoed challenge is missing required 'id' field");
        }

        String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now());

        return new PaymentReceipt(
                "success",
                challengeId,
                "lightning",
                null,
                context.priceSats(),
                timestamp,
                "Payment"
        );
    }
}
