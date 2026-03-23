package com.greenharborlabs.paygate.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentValidationExceptionTest {

    // --- ErrorCode mappings ---

    @Test
    void malformedCredentialMapsToCorrectStatusAndUri() {
        var code = PaymentValidationException.ErrorCode.MALFORMED_CREDENTIAL;
        assertThat(code.httpStatus()).isEqualTo(402);
        assertThat(code.problemTypeUri()).isEqualTo("https://paymentauth.org/problems/malformed-credential");
    }

    @Test
    void invalidPreimageMapsToCorrectStatusAndUri() {
        var code = PaymentValidationException.ErrorCode.INVALID_PREIMAGE;
        assertThat(code.httpStatus()).isEqualTo(402);
        assertThat(code.problemTypeUri()).isEqualTo("https://paymentauth.org/problems/verification-failed");
    }

    @Test
    void invalidChallengeBindingMapsToCorrectStatusAndUri() {
        var code = PaymentValidationException.ErrorCode.INVALID_CHALLENGE_BINDING;
        assertThat(code.httpStatus()).isEqualTo(402);
        assertThat(code.problemTypeUri()).isEqualTo("https://paymentauth.org/problems/verification-failed");
    }

    @Test
    void expiredCredentialMapsToCorrectStatusAndUri() {
        var code = PaymentValidationException.ErrorCode.EXPIRED_CREDENTIAL;
        assertThat(code.httpStatus()).isEqualTo(402);
        assertThat(code.problemTypeUri()).isEqualTo("https://paymentauth.org/problems/verification-failed");
    }

    @Test
    void methodUnsupportedMapsToCorrectStatusAndUri() {
        var code = PaymentValidationException.ErrorCode.METHOD_UNSUPPORTED;
        assertThat(code.httpStatus()).isEqualTo(400);
        assertThat(code.problemTypeUri()).isEqualTo("https://paymentauth.org/problems/method-unsupported");
    }

    @ParameterizedTest
    @EnumSource(PaymentValidationException.ErrorCode.class)
    void allErrorCodesHaveNonNullProblemTypeUri(PaymentValidationException.ErrorCode code) {
        assertThat(code.problemTypeUri()).isNotNull();
        assertThat(code.problemTypeUri()).startsWith("https://");
    }

    @ParameterizedTest
    @EnumSource(PaymentValidationException.ErrorCode.class)
    void allErrorCodesHavePositiveHttpStatus(PaymentValidationException.ErrorCode code) {
        assertThat(code.httpStatus()).isGreaterThanOrEqualTo(400);
        assertThat(code.httpStatus()).isLessThan(600);
    }

    @Test
    void allFiveErrorCodesExist() {
        assertThat(PaymentValidationException.ErrorCode.values()).hasSize(5);
    }

    // --- Constructor: (ErrorCode, String) ---

    @Test
    void twoArgConstructorSetsFieldsCorrectly() {
        var ex = new PaymentValidationException(
                PaymentValidationException.ErrorCode.MALFORMED_CREDENTIAL,
                "bad token"
        );

        assertThat(ex.getMessage()).isEqualTo("bad token");
        assertThat(ex.getErrorCode()).isEqualTo(PaymentValidationException.ErrorCode.MALFORMED_CREDENTIAL);
        assertThat(ex.getTokenId()).isNull();
        assertThat(ex.getHttpStatus()).isEqualTo(402);
        assertThat(ex.getProblemTypeUri()).isEqualTo("https://paymentauth.org/problems/malformed-credential");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void twoArgConstructorWithNullErrorCodeThrows() {
        assertThatThrownBy(() -> new PaymentValidationException(null, "msg"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("errorCode");
    }

    // --- Constructor: (ErrorCode, String, String) ---

    @Test
    void threeArgStringConstructorSetsTokenId() {
        var ex = new PaymentValidationException(
                PaymentValidationException.ErrorCode.INVALID_PREIMAGE,
                "wrong preimage",
                "tok_123"
        );

        assertThat(ex.getMessage()).isEqualTo("wrong preimage");
        assertThat(ex.getErrorCode()).isEqualTo(PaymentValidationException.ErrorCode.INVALID_PREIMAGE);
        assertThat(ex.getTokenId()).isEqualTo("tok_123");
        assertThat(ex.getHttpStatus()).isEqualTo(402);
        assertThat(ex.getProblemTypeUri()).isEqualTo("https://paymentauth.org/problems/verification-failed");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void threeArgStringConstructorWithNullTokenIdIsAllowed() {
        var ex = new PaymentValidationException(
                PaymentValidationException.ErrorCode.EXPIRED_CREDENTIAL,
                "expired",
                (String) null
        );

        assertThat(ex.getTokenId()).isNull();
    }

    @Test
    void threeArgStringConstructorWithNullErrorCodeThrows() {
        assertThatThrownBy(() -> new PaymentValidationException(null, "msg", "tok"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("errorCode");
    }

    // --- Constructor: (ErrorCode, String, Throwable) ---

    @Test
    void threeArgCauseConstructorPreservesCauseChain() {
        var rootCause = new RuntimeException("root cause");
        var ex = new PaymentValidationException(
                PaymentValidationException.ErrorCode.INVALID_CHALLENGE_BINDING,
                "binding failed",
                rootCause
        );

        assertThat(ex.getMessage()).isEqualTo("binding failed");
        assertThat(ex.getCause()).isSameAs(rootCause);
        assertThat(ex.getErrorCode()).isEqualTo(PaymentValidationException.ErrorCode.INVALID_CHALLENGE_BINDING);
        assertThat(ex.getTokenId()).isNull();
        assertThat(ex.getHttpStatus()).isEqualTo(402);
        assertThat(ex.getProblemTypeUri()).isEqualTo("https://paymentauth.org/problems/verification-failed");
    }

    @Test
    void threeArgCauseConstructorWithNullErrorCodeThrows() {
        assertThatThrownBy(() -> new PaymentValidationException(null, "msg", new RuntimeException()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("errorCode");
    }

    @Test
    void causeConstructorWithNestedCauseChain() {
        var innerCause = new IllegalStateException("inner");
        var outerCause = new RuntimeException("outer", innerCause);
        var ex = new PaymentValidationException(
                PaymentValidationException.ErrorCode.METHOD_UNSUPPORTED,
                "unsupported",
                outerCause
        );

        assertThat(ex.getCause()).isSameAs(outerCause);
        assertThat(ex.getCause().getCause()).isSameAs(innerCause);
    }

    // --- Is a RuntimeException ---

    @Test
    void isRuntimeException() {
        var ex = new PaymentValidationException(
                PaymentValidationException.ErrorCode.MALFORMED_CREDENTIAL,
                "test"
        );

        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    // --- Each ErrorCode propagates correctly through exception ---

    @ParameterizedTest
    @EnumSource(PaymentValidationException.ErrorCode.class)
    void eachErrorCodePropagatesStatusAndUriToException(PaymentValidationException.ErrorCode code) {
        var ex = new PaymentValidationException(code, "test message");

        assertThat(ex.getHttpStatus()).isEqualTo(code.httpStatus());
        assertThat(ex.getProblemTypeUri()).isEqualTo(code.problemTypeUri());
        assertThat(ex.getErrorCode()).isEqualTo(code);
    }
}
