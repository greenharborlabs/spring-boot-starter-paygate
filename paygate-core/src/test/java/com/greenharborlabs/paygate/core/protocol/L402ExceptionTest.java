package com.greenharborlabs.paygate.core.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class L402ExceptionTest {

  @Nested
  @DisplayName("ErrorCode enum")
  class ErrorCodeTests {

    @Test
    @DisplayName("all 7 error codes exist")
    void allErrorCodesExist() {
      assertThat(ErrorCode.values()).hasSize(7);
      assertThat(ErrorCode.valueOf("INVALID_MACAROON")).isNotNull();
      assertThat(ErrorCode.valueOf("INVALID_PREIMAGE")).isNotNull();
      assertThat(ErrorCode.valueOf("EXPIRED_CREDENTIAL")).isNotNull();
      assertThat(ErrorCode.valueOf("INVALID_SERVICE")).isNotNull();
      assertThat(ErrorCode.valueOf("REVOKED_CREDENTIAL")).isNotNull();
      assertThat(ErrorCode.valueOf("LIGHTNING_UNAVAILABLE")).isNotNull();
      assertThat(ErrorCode.valueOf("MALFORMED_HEADER")).isNotNull();
    }

    @Test
    @DisplayName("INVALID_MACAROON maps to HTTP 401")
    void invalidMacaroonMapsTo401() {
      assertThat(ErrorCode.INVALID_MACAROON.getHttpStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("INVALID_PREIMAGE maps to HTTP 401")
    void invalidPreimageMapsTo401() {
      assertThat(ErrorCode.INVALID_PREIMAGE.getHttpStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("EXPIRED_CREDENTIAL maps to HTTP 401")
    void expiredCredentialMapsTo401() {
      assertThat(ErrorCode.EXPIRED_CREDENTIAL.getHttpStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("INVALID_SERVICE maps to HTTP 401")
    void invalidServiceMapsTo401() {
      assertThat(ErrorCode.INVALID_SERVICE.getHttpStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("REVOKED_CREDENTIAL maps to HTTP 401")
    void revokedCredentialMapsTo401() {
      assertThat(ErrorCode.REVOKED_CREDENTIAL.getHttpStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("LIGHTNING_UNAVAILABLE maps to HTTP 503")
    void lightningUnavailableMapsTo503() {
      assertThat(ErrorCode.LIGHTNING_UNAVAILABLE.getHttpStatus()).isEqualTo(503);
    }

    @Test
    @DisplayName("MALFORMED_HEADER maps to HTTP 400")
    void malformedHeaderMapsTo400() {
      assertThat(ErrorCode.MALFORMED_HEADER.getHttpStatus()).isEqualTo(400);
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    @DisplayName("every error code has a positive HTTP status")
    void allCodesHavePositiveHttpStatus(ErrorCode code) {
      assertThat(code.getHttpStatus()).isGreaterThan(0);
    }
  }

  @Nested
  @DisplayName("Construction")
  class Construction {

    @Test
    @DisplayName("creates exception with error code, message, and token ID")
    void fullConstruction() {
      var exception =
          new L402Exception(ErrorCode.INVALID_MACAROON, "Signature verification failed", "abc123");

      assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_MACAROON);
      assertThat(exception.getMessage()).isEqualTo("Signature verification failed");
      assertThat(exception.getTokenId()).isEqualTo("abc123");
    }

    @Test
    @DisplayName("creates exception with null token ID")
    void nullTokenId() {
      var exception = new L402Exception(ErrorCode.MALFORMED_HEADER, "Cannot parse header", null);

      assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.MALFORMED_HEADER);
      assertThat(exception.getMessage()).isEqualTo("Cannot parse header");
      assertThat(exception.getTokenId()).isNull();
    }

    @Test
    @DisplayName("creates exception for lightning unavailable")
    void lightningUnavailable() {
      var exception =
          new L402Exception(ErrorCode.LIGHTNING_UNAVAILABLE, "Connection refused", "token-42");

      assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.LIGHTNING_UNAVAILABLE);
      assertThat(exception.getMessage()).isEqualTo("Connection refused");
      assertThat(exception.getTokenId()).isEqualTo("token-42");
    }
  }

  @Nested
  @DisplayName("RuntimeException inheritance")
  class Inheritance {

    @Test
    @DisplayName("L402Exception is a RuntimeException")
    void isRuntimeException() {
      var exception = new L402Exception(ErrorCode.INVALID_PREIMAGE, "Bad preimage", null);

      assertThat(exception).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("can be caught as RuntimeException")
    void catchableAsRuntimeException() {
      RuntimeException caught = null;
      try {
        throw new L402Exception(ErrorCode.EXPIRED_CREDENTIAL, "Token expired", "tok-99");
      } catch (RuntimeException e) {
        caught = e;
      }

      assertThat(caught).isNotNull();
      assertThat(caught).isInstanceOf(L402Exception.class);
    }
  }

  @Nested
  @DisplayName("getMessage")
  class GetMessage {

    @Test
    @DisplayName("returns the message passed at construction")
    void returnsConstructionMessage() {
      var exception =
          new L402Exception(ErrorCode.REVOKED_CREDENTIAL, "Root key not found", "revoked-token");

      assertThat(exception.getMessage()).isEqualTo("Root key not found");
    }

    @Test
    @DisplayName("returns null message when constructed with null")
    void returnsNullMessage() {
      var exception = new L402Exception(ErrorCode.INVALID_SERVICE, null, "some-token");

      assertThat(exception.getMessage()).isNull();
    }
  }
}
