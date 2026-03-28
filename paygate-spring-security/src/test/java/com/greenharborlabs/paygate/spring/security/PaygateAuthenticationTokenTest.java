package com.greenharborlabs.paygate.spring.security;

import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.ProtocolMetadata;
import com.greenharborlabs.paygate.core.lightning.PaymentPreimage;
import com.greenharborlabs.paygate.core.macaroon.Caveat;
import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.paygate.core.protocol.L402Credential;
import com.greenharborlabs.paygate.core.macaroon.VerificationContextKeys;
import com.greenharborlabs.paygate.core.protocol.L402HeaderComponents;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaygateAuthenticationTokenTest {

    private static final SecureRandom RNG = new SecureRandom();

    // ========== Existing L402 tests (preserved) ==========

    @Test
    void unauthenticatedTokenHoldsComponents() {
        var components = new L402HeaderComponents("L402", "mac-base64", "abcd".repeat(16));
        var token = new PaygateAuthenticationToken(components);

        assertThat(token.isAuthenticated()).isFalse();
        assertThat(token.getComponents()).isSameAs(components);
        assertThat(token.getComponents().macaroonBase64()).isEqualTo("mac-base64");
        assertThat(token.getComponents().preimageHex()).isEqualTo("abcd".repeat(16));
        assertThat(token.getTokenId()).isNull();
        assertThat(token.getServiceName()).isNull();
        assertThat(token.getAttributes()).isEmpty();
        assertThat(token.getAuthorities()).isEmpty();
    }

    @Test
    void unauthenticatedTokenRedactsSensitiveValues() {
        var components = new L402HeaderComponents("L402", "mac-base64", "abcd".repeat(16));
        var token = new PaygateAuthenticationToken(components);

        assertThat(token.getPrincipal()).isEqualTo("[unauthenticated]");
        assertThat(token.getCredentials()).isEqualTo("[REDACTED]");
    }

    @Test
    void unauthenticatedTokenRejectsNullComponents() {
        assertThatThrownBy(() -> new PaygateAuthenticationToken((L402HeaderComponents) null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void authenticatedTokenReturnsNullComponents() {
        L402Credential credential = createTestCredential(List.of());
        var token = PaygateAuthenticationToken.authenticated(credential, "svc");

        assertThat(token.getComponents()).isNull();
    }

    @Test
    void authenticatedTokenExposesCredentialDetails() {
        L402Credential credential = createTestCredential(List.of(
                new Caveat("service", "api"),
                new Caveat("valid_until", "2026-12-31T23:59:59Z")
        ));

        var token = PaygateAuthenticationToken.authenticated(credential, "my-api");

        assertThat(token.isAuthenticated()).isTrue();
        assertThat(token.getTokenId()).isEqualTo(credential.tokenId());
        assertThat(token.getServiceName()).isEqualTo("my-api");
        assertThat(token.getPrincipal()).isEqualTo(credential.tokenId());
        assertThat(token.getCredentials()).isEqualTo(credential);
        assertThat(token.getL402Credential()).isEqualTo(credential);
    }

    @Test
    void authenticatedTokenHasL402Authority() {
        L402Credential credential = createTestCredential(List.of());
        var token = PaygateAuthenticationToken.authenticated(credential, "svc");

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_PAYMENT", "ROLE_L402");
    }

    @Test
    void authenticatedTokenExtractsCaveatAttributes() {
        L402Credential credential = createTestCredential(List.of(
                new Caveat("service", "api"),
                new Caveat("tier", "premium")
        ));

        var token = PaygateAuthenticationToken.authenticated(credential, "api");

        assertThat(token.getAttributes())
                .containsEntry("tokenId", credential.tokenId())
                .containsEntry("serviceName", "api")
                .containsEntry("service", "api")
                .containsEntry("tier", "premium");
        assertThat(token.getAttribute("tier")).isEqualTo("premium");
        assertThat(token.getAttribute("nonexistent")).isNull();
    }

    @Test
    void builtInAttributesCannotBeOverwrittenByCaveats() {
        L402Credential credential = createTestCredential(List.of(
                new Caveat("tokenId", "attacker-controlled-value"),
                new Caveat("serviceName", "attacker-service")
        ));

        var token = PaygateAuthenticationToken.authenticated(credential, "trusted-service");

        // Built-in attributes must win over attacker-controlled caveat keys
        assertThat(token.getAttribute("tokenId")).isEqualTo(credential.tokenId());
        assertThat(token.getAttribute("serviceName")).isEqualTo("trusted-service");
    }

    @Test
    void authenticatedTokenWithNullServiceName() {
        L402Credential credential = createTestCredential(List.of());
        var token = PaygateAuthenticationToken.authenticated(credential, null);

        assertThat(token.getServiceName()).isNull();
        assertThat(token.getAttributes()).doesNotContainKey("serviceName");
        assertThat(token.getAttributes()).containsKey("tokenId");
    }

    @Test
    void authenticatedTokenMapsCapabilitiesToAuthorities() {
        L402Credential credential = createTestCredential(List.of(
                new Caveat("test-service_capabilities", "search,analyze")
        ));

        var token = PaygateAuthenticationToken.authenticated(credential, "test-service");

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_PAYMENT", "ROLE_L402", "L402_CAPABILITY_search", "L402_CAPABILITY_analyze");
    }

    @Test
    void authenticatedTokenWithNoCapabilitiesCaveatHasOnlyRoleL402() {
        L402Credential credential = createTestCredential(List.of(
                new Caveat("other_key", "value")
        ));

        var token = PaygateAuthenticationToken.authenticated(credential, "svc");

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_PAYMENT", "ROLE_L402");
    }

    @Test
    void caveatRejectsEmptyCapabilitiesValue() {
        assertThatThrownBy(() -> new Caveat("svc_capabilities", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("value must not be null, empty, or blank");
    }

    @Test
    void authenticatedTokenDeduplicatesCapabilities() {
        L402Credential credential = createTestCredential(List.of(
                new Caveat("svc_capabilities", "search,search")
        ));

        var token = PaygateAuthenticationToken.authenticated(credential, "svc");

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_PAYMENT", "ROLE_L402", "L402_CAPABILITY_search");
    }

    @Test
    void authenticatedTokenHandlesMalformedCapabilitiesValue() {
        L402Credential credential = createTestCredential(List.of(
                new Caveat("svc_capabilities", "search,,analyze,")
        ));

        var token = PaygateAuthenticationToken.authenticated(credential, "svc");

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_PAYMENT", "ROLE_L402", "L402_CAPABILITY_search", "L402_CAPABILITY_analyze");
    }

    @Test
    void authenticatedTokenWithNullServiceNameSkipsCapabilityExtraction() {
        L402Credential credential = createTestCredential(List.of(
                new Caveat("null_capabilities", "search")
        ));

        var token = PaygateAuthenticationToken.authenticated(credential, null);

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_PAYMENT", "ROLE_L402");
    }

    @Test
    void authenticatedTokenDeduplicatesAcrossMultipleCapabilityCaveats() {
        L402Credential credential = createTestCredential(List.of(
                new Caveat("svc_capabilities", "search,read"),
                new Caveat("svc_capabilities", "read,write")
        ));

        var token = PaygateAuthenticationToken.authenticated(credential, "svc");

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_PAYMENT", "ROLE_L402", "L402_CAPABILITY_search", "L402_CAPABILITY_read", "L402_CAPABILITY_write");
    }

    // ========== Protocol-agnostic unauthenticated token tests ==========

    @Test
    void unauthenticatedProtocolAgnosticTokenHoldsAuthorizationHeader() {
        var token = PaygateAuthenticationToken.unauthenticated(
                "Payment preimage=abcd1234", Map.of("method", "GET", VerificationContextKeys.REQUESTED_CAPABILITY, "read"));

        assertThat(token.isAuthenticated()).isFalse();
        assertThat(token.getAuthorizationHeader()).isEqualTo("Payment preimage=abcd1234");
        assertThat(token.getRequestMetadata().get(VerificationContextKeys.REQUESTED_CAPABILITY)).isEqualTo("read");
        assertThat(token.getRequestMetadata()).containsEntry("method", "GET");
        assertThat(token.getComponents()).isNull();
        assertThat(token.getTokenId()).isNull();
        assertThat(token.getAttributes()).isEmpty();
        assertThat(token.getAuthorities()).isEmpty();
    }

    @Test
    void unauthenticatedProtocolAgnosticTokenRedactsSensitiveValues() {
        var token = PaygateAuthenticationToken.unauthenticated(
                "Payment preimage=secret", Map.of());

        assertThat(token.getPrincipal()).isEqualTo("[unauthenticated]");
        assertThat(token.getCredentials()).isEqualTo("[REDACTED]");
    }

    @Test
    void unauthenticatedProtocolAgnosticTokenRejectsNullHeader() {
        assertThatThrownBy(() -> PaygateAuthenticationToken.unauthenticated(
                null, Map.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("authorizationHeader");
    }

    @Test
    void unauthenticatedProtocolAgnosticTokenRejectsNullMetadata() {
        assertThatThrownBy(() -> PaygateAuthenticationToken.unauthenticated(
                "Payment preimage=x", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("requestMetadata");
    }

    @Test
    void unauthenticatedProtocolAgnosticTokenAcceptsAbsentCapability() {
        var token = PaygateAuthenticationToken.unauthenticated(
                "Payment preimage=x", Map.of());

        assertThat(token.getRequestMetadata().get(VerificationContextKeys.REQUESTED_CAPABILITY)).isNull();
    }

    @Test
    void unauthenticatedProtocolAgnosticTokenAcceptsEmptyMetadata() {
        var token = PaygateAuthenticationToken.unauthenticated(
                "Payment preimage=x", Map.of());

        assertThat(token.getRequestMetadata()).isEmpty();
    }

    // ========== PaymentCredential authenticated token tests ==========

    @Test
    void authenticatedPaymentCredentialMppHasRolePayment() {
        PaymentCredential cred = createMppCredential("token-123", null);
        var token = PaygateAuthenticationToken.authenticated(cred, "my-service");

        assertThat(token.isAuthenticated()).isTrue();
        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_PAYMENT");
    }

    @Test
    void authenticatedPaymentCredentialL402HasBothRoles() {
        PaymentCredential cred = createL402PaymentCredential("token-456");
        var token = PaygateAuthenticationToken.authenticated(cred, "svc");

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_PAYMENT", "ROLE_L402");
    }

    @Test
    void authenticatedPaymentCredentialExposesTokenId() {
        PaymentCredential cred = createMppCredential("my-token-id", null);
        var token = PaygateAuthenticationToken.authenticated(cred, "svc");

        assertThat(token.getTokenId()).isEqualTo("my-token-id");
        assertThat(token.getPrincipal()).isEqualTo("my-token-id");
    }

    @Test
    void authenticatedPaymentCredentialExposesProtocolScheme() {
        PaymentCredential cred = createMppCredential("tok", null);
        var token = PaygateAuthenticationToken.authenticated(cred, "svc");

        assertThat(token.getProtocolScheme()).isEqualTo("Payment");
    }

    @Test
    void authenticatedPaymentCredentialExposesAttributes() {
        PaymentCredential cred = createMppCredential("tok-999", "did:key:z6Mk...");
        var token = PaygateAuthenticationToken.authenticated(cred, "my-api");

        assertThat(token.getAttributes())
                .containsEntry("tokenId", "tok-999")
                .containsEntry("protocolScheme", "Payment")
                .containsEntry("source", "did:key:z6Mk...")
                .containsEntry("serviceName", "my-api");
    }

    @Test
    void authenticatedPaymentCredentialWithNullServiceName() {
        PaymentCredential cred = createMppCredential("tok", null);
        var token = PaygateAuthenticationToken.authenticated(cred, null);

        assertThat(token.getServiceName()).isNull();
        assertThat(token.getAttributes()).doesNotContainKey("serviceName");
        assertThat(token.getAttributes()).containsKey("tokenId");
    }

    @Test
    void authenticatedPaymentCredentialWithNullSource() {
        PaymentCredential cred = createMppCredential("tok", null);
        var token = PaygateAuthenticationToken.authenticated(cred, "svc");

        assertThat(token.getAttributes()).doesNotContainKey("source");
    }

    @Test
    void authenticatedPaymentCredentialReturnsCredentialFromGetCredentials() {
        PaymentCredential cred = createMppCredential("tok", null);
        var token = PaygateAuthenticationToken.authenticated(cred, "svc");

        assertThat(token.getCredentials()).isInstanceOf(PaymentCredential.class);
        assertThat(token.getPaymentCredential()).isEqualTo(cred);
    }

    @Test
    void authenticatedPaymentCredentialHasNullL402Credential() {
        PaymentCredential cred = createMppCredential("tok", null);
        var token = PaygateAuthenticationToken.authenticated(cred, "svc");

        assertThat(token.getL402Credential()).isNull();
    }

    @Test
    void authenticatedPaymentCredentialHasNullComponents() {
        PaymentCredential cred = createMppCredential("tok", null);
        var token = PaygateAuthenticationToken.authenticated(cred, "svc");

        assertThat(token.getComponents()).isNull();
        assertThat(token.getAuthorizationHeader()).isNull();
    }

    @Test
    void authenticatedPaymentCredentialHasEmptyRequestMetadata() {
        PaymentCredential cred = createMppCredential("tok", null);
        var token = PaygateAuthenticationToken.authenticated(cred, "svc");

        assertThat(token.getRequestMetadata()).isEmpty();
    }

    @Test
    void authenticatedPaymentCredentialRejectsNull() {
        assertThatThrownBy(() -> PaygateAuthenticationToken.authenticated(
                (PaymentCredential) null, "svc"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("paymentCredential");
    }

    // ========== L402 authenticated token protocol scheme tests ==========

    @Test
    void l402AuthenticatedTokenHasL402ProtocolScheme() {
        L402Credential credential = createTestCredential(List.of());
        var token = PaygateAuthenticationToken.authenticated(credential, "svc");

        assertThat(token.getProtocolScheme()).isEqualTo("L402");
    }

    @Test
    void l402AuthenticatedTokenHasNullPaymentCredential() {
        L402Credential credential = createTestCredential(List.of());
        var token = PaygateAuthenticationToken.authenticated(credential, "svc");

        assertThat(token.getPaymentCredential()).isNull();
    }

    // ========== L402 unauthenticated token new accessor tests ==========

    @Test
    void l402UnauthenticatedTokenHasNullProtocolScheme() {
        var components = new L402HeaderComponents("L402", "mac-base64", "abcd".repeat(16));
        var token = new PaygateAuthenticationToken(components);

        assertThat(token.getProtocolScheme()).isNull();
        assertThat(token.getPaymentCredential()).isNull();
        assertThat(token.getAuthorizationHeader()).isNull();
    }

    @Test
    void twoArgConstructorPreservesRequestMetadata() {
        var components = new L402HeaderComponents("L402", "mac-base64", "abcd".repeat(16));
        var metadata = Map.of("path", "/api/data", "method", "POST", "client_ip", "10.0.0.1",
                VerificationContextKeys.REQUESTED_CAPABILITY, "write");
        var token = new PaygateAuthenticationToken(components, metadata);

        assertThat(token.getRequestMetadata()).isEqualTo(metadata);
        assertThat(token.getRequestMetadata().get(VerificationContextKeys.REQUESTED_CAPABILITY)).isEqualTo("write");
        assertThat(token.getComponents()).isSameAs(components);
    }

    // ========== setAuthenticated security tests ==========

    @Test
    void setAuthenticatedTrueThrowsIllegalArgumentException() {
        var components = new L402HeaderComponents("L402", "mac-base64", "abcd".repeat(16));
        var token = new PaygateAuthenticationToken(components);

        assertThatThrownBy(() -> token.setAuthenticated(true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot set this token to trusted");
    }

    @Test
    void setAuthenticatedTrueThrowsOnAuthenticatedToken() {
        L402Credential credential = createTestCredential(List.of());
        var token = PaygateAuthenticationToken.authenticated(credential, "svc");

        assertThat(token.isAuthenticated()).isTrue();
        assertThatThrownBy(() -> token.setAuthenticated(true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot set this token to trusted");
    }

    @Test
    void setAuthenticatedFalseAllowed() {
        L402Credential credential = createTestCredential(List.of());
        var token = PaygateAuthenticationToken.authenticated(credential, "svc");

        token.setAuthenticated(false);
        assertThat(token.isAuthenticated()).isFalse();
    }

    @Test
    void setAuthenticatedTrueThrowsOnProtocolAgnosticUnauthenticatedToken() {
        var token = PaygateAuthenticationToken.unauthenticated(
                "Payment preimage=x", Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "read"));

        assertThatThrownBy(() -> token.setAuthenticated(true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot set this token to trusted");
    }

    @Test
    void setAuthenticatedTrueThrowsOnProtocolAgnosticAuthenticatedToken() {
        PaymentCredential cred = createMppCredential("tok", null);
        var token = PaygateAuthenticationToken.authenticated(cred, "svc");

        assertThat(token.isAuthenticated()).isTrue();
        assertThatThrownBy(() -> token.setAuthenticated(true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot set this token to trusted");
    }

    // ========== 3-arg authenticated() with capabilities tests ==========

    @Test
    void l402WithExplicitCapabilitiesProducesPaygateCapabilityAuthorities() {
        L402Credential credential = createTestCredential(List.of());
        var token = PaygateAuthenticationToken.authenticated(credential, "svc", Set.of("search"));

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_PAYMENT", "ROLE_L402", "PAYGATE_CAPABILITY_search");
    }

    @Test
    void l402DualEmitCaveatAndExplicitCapabilityProducesBothPrefixes() {
        L402Credential credential = createTestCredential(List.of(
                new Caveat("svc_capabilities", "search")
        ));
        var token = PaygateAuthenticationToken.authenticated(credential, "svc", Set.of("search"));

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder(
                        "ROLE_PAYMENT", "ROLE_L402",
                        "L402_CAPABILITY_search", "PAYGATE_CAPABILITY_search");
    }

    @Test
    void l402ThreeArgWithCaveatCapabilitiesEmitsBothPrefixes() {
        L402Credential credential = createTestCredential(List.of(
                new Caveat("svc_capabilities", "search,analyze")
        ));
        var token = PaygateAuthenticationToken.authenticated(credential, "svc", Set.of("extra"));

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder(
                        "ROLE_PAYMENT", "ROLE_L402",
                        "L402_CAPABILITY_search", "L402_CAPABILITY_analyze",
                        "PAYGATE_CAPABILITY_search", "PAYGATE_CAPABILITY_analyze",
                        "PAYGATE_CAPABILITY_extra");
    }

    @Test
    void l402TwoArgBackwardCompatNoPaygateCapabilities() {
        L402Credential credential = createTestCredential(List.of(
                new Caveat("svc_capabilities", "search")
        ));
        var token = PaygateAuthenticationToken.authenticated(credential, "svc");

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .doesNotContain("PAYGATE_CAPABILITY_search");
        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains("L402_CAPABILITY_search");
    }

    @Test
    void l402ThreeArgEmptyCapabilitiesNoPaygateAuthorities() {
        L402Credential credential = createTestCredential(List.of());
        var token = PaygateAuthenticationToken.authenticated(credential, "svc", Set.of());

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_PAYMENT", "ROLE_L402");
    }

    @Test
    void mppWithCapabilitiesProducesPaygateCapabilityAuthorities() {
        PaymentCredential cred = createMppCredential("tok", null);
        var token = PaygateAuthenticationToken.authenticated(cred, "svc", Set.of("search", "analyze"));

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_PAYMENT", "PAYGATE_CAPABILITY_search", "PAYGATE_CAPABILITY_analyze");
    }

    @Test
    void mppWithEmptyCapabilitiesSameAsTwoArg() {
        PaymentCredential cred = createMppCredential("tok", null);
        var token = PaygateAuthenticationToken.authenticated(cred, "svc", Set.of());

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_PAYMENT");
    }

    @Test
    void mppNullCapabilitiesThrowsNpe() {
        PaymentCredential cred = createMppCredential("tok", null);

        assertThatThrownBy(() -> PaygateAuthenticationToken.authenticated(cred, "svc", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("capabilities must not be null");
    }

    @Test
    void l402NullCapabilitiesThrowsNpe() {
        L402Credential credential = createTestCredential(List.of());

        assertThatThrownBy(() -> PaygateAuthenticationToken.authenticated(credential, "svc", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("capabilities must not be null");
    }

    @Test
    void mppL402ProtocolSchemeWithCapabilitiesHasRoleL402() {
        PaymentCredential cred = createL402PaymentCredential("tok");
        var token = PaygateAuthenticationToken.authenticated(cred, "svc", Set.of("read"));

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_PAYMENT", "ROLE_L402", "PAYGATE_CAPABILITY_read");
    }

    @Test
    void capabilitiesSetWithNullElementsSkipsNullsSilently() {
        PaymentCredential cred = createMppCredential("tok", null);
        Set<String> caps = new HashSet<>();
        caps.add("search");
        caps.add(null);
        caps.add("analyze");

        var token = PaygateAuthenticationToken.authenticated(cred, "svc", caps);

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_PAYMENT", "PAYGATE_CAPABILITY_search", "PAYGATE_CAPABILITY_analyze");
    }

    @Test
    void l402AuthorityOrdering() {
        L402Credential credential = createTestCredential(List.of(
                new Caveat("svc_capabilities", "search")
        ));
        var token = PaygateAuthenticationToken.authenticated(credential, "svc", Set.of("extra"));

        var authorityStrings = token.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        // ROLE_PAYMENT before ROLE_L402 before L402_CAPABILITY before PAYGATE_CAPABILITY
        assertThat(authorityStrings.indexOf("ROLE_PAYMENT"))
                .isLessThan(authorityStrings.indexOf("ROLE_L402"));
        assertThat(authorityStrings.indexOf("ROLE_L402"))
                .isLessThan(authorityStrings.indexOf("L402_CAPABILITY_search"));
        assertThat(authorityStrings.indexOf("L402_CAPABILITY_search"))
                .isLessThan(authorityStrings.indexOf("PAYGATE_CAPABILITY_search"));
    }

    // ========== Helpers ==========

    private L402Credential createTestCredential(List<Caveat> caveats) {
        byte[] paymentHash = new byte[32];
        byte[] tokenIdBytes = new byte[32];
        RNG.nextBytes(paymentHash);
        RNG.nextBytes(tokenIdBytes);

        var identifier = new MacaroonIdentifier(0, paymentHash, tokenIdBytes);
        byte[] idBytes = MacaroonIdentifier.encode(identifier);
        byte[] sig = new byte[32];
        RNG.nextBytes(sig);

        var macaroon = new Macaroon(idBytes, null, caveats, sig);
        var preimage = new PaymentPreimage(paymentHash); // reuse hash as preimage for test

        String tokenId = java.util.HexFormat.of().formatHex(tokenIdBytes);
        return new L402Credential(macaroon, preimage, tokenId);
    }

    private PaymentCredential createMppCredential(String tokenId, String source) {
        byte[] paymentHash = new byte[32];
        byte[] preimage = new byte[32];
        RNG.nextBytes(paymentHash);
        RNG.nextBytes(preimage);

        return new PaymentCredential(
                paymentHash, preimage, tokenId, "Payment", source,
                new ProtocolMetadata() {});
    }

    private PaymentCredential createL402PaymentCredential(String tokenId) {
        byte[] paymentHash = new byte[32];
        byte[] preimage = new byte[32];
        RNG.nextBytes(paymentHash);
        RNG.nextBytes(preimage);

        return new PaymentCredential(
                paymentHash, preimage, tokenId, "L402", null,
                new ProtocolMetadata() {});
    }
}
