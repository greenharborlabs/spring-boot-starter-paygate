package com.greenharborlabs.paygate.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PaymentValidationExceptionTest {

  private static final String ATTACKER_DETAIL = "ATTACKER_DETAIL_DO_NOT_EXPOSE";
  private static final String CAUSE_DETAIL = "CAUSE_DETAIL_DO_NOT_EXPOSE";

  @Test
  void exposesOnlyTheFourStableProtocolNeutralFailureCategories() {
    assertThat(PaymentValidationException.ErrorCode.values())
        .extracting(Enum::name)
        .containsExactlyInAnyOrder("MALFORMED", "INVALID", "INSUFFICIENT", "UNAVAILABLE");
  }

  @ParameterizedTest
  @MethodSource("failureCategoryStatuses")
  void eachFailureCategoryMapsToItsStableHttpStatus(String categoryName, int expectedStatus) {
    var category = PaymentValidationException.ErrorCode.valueOf(categoryName);

    var exception = new PaymentValidationException(category, ATTACKER_DETAIL);

    assertThat(exception.getErrorCode()).isSameAs(category);
    assertThat(exception.getHttpStatus()).isEqualTo(expectedStatus);
    assertThat(exception.getProblemTypeUri()).startsWith("https://");
  }

  private static Stream<Arguments> failureCategoryStatuses() {
    return Stream.of(
        Arguments.of("MALFORMED", 400),
        Arguments.of("INVALID", 402),
        Arguments.of("INSUFFICIENT", 402),
        Arguments.of("UNAVAILABLE", 503));
  }

  @Test
  void publicMessageDoesNotExposeCallerSuppliedDetail() {
    var exception =
        new PaymentValidationException(
            PaymentValidationException.ErrorCode.valueOf("INVALID"), ATTACKER_DETAIL);

    assertThat(exception.getMessage()).doesNotContain(ATTACKER_DETAIL);
  }

  @Test
  void publicMessageDoesNotExposeCauseDetail() {
    var cause = new IllegalStateException(CAUSE_DETAIL);
    var exception =
        new PaymentValidationException(
            PaymentValidationException.ErrorCode.valueOf("UNAVAILABLE"), ATTACKER_DETAIL, cause);

    assertThat(exception.getMessage()).doesNotContain(ATTACKER_DETAIL).doesNotContain(CAUSE_DETAIL);
    assertThat(exception.getCause()).isSameAs(cause);
  }

  @Test
  void nullFailureCategoryIsRejected() {
    assertThatThrownBy(() -> new PaymentValidationException(null, "safe detail"))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("errorCode");
  }

  @Test
  void isRuntimeException() {
    var exception =
        new PaymentValidationException(
            PaymentValidationException.ErrorCode.valueOf("MALFORMED"), "safe detail");

    assertThat(exception).isInstanceOf(RuntimeException.class);
  }
}
