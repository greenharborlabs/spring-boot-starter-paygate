package com.greenharborlabs.l402.spring.security;

import com.greenharborlabs.l402.core.lightning.PaymentPreimage;
import com.greenharborlabs.l402.core.macaroon.Caveat;
import com.greenharborlabs.l402.core.macaroon.Macaroon;
import com.greenharborlabs.l402.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.l402.core.protocol.L402Credential;
import com.greenharborlabs.l402.core.protocol.L402HeaderComponents;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.security.SecureRandom;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class L402AuthenticationTokenTest {

    private static final SecureRandom RNG = new SecureRandom();

    @Test
    void unauthenticatedTokenHoldsComponents() {
        var components = new L402HeaderComponents("L402", "mac-base64", "abcd".repeat(16));
        var token = new L402AuthenticationToken(components);

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
        var token = new L402AuthenticationToken(components);

        assertThat(token.getPrincipal()).isEqualTo("[unauthenticated-l402]");
        assertThat(token.getCredentials()).isEqualTo("[REDACTED]");
    }

    @Test
    void unauthenticatedTokenRejectsNullComponents() {
        assertThatThrownBy(() -> new L402AuthenticationToken((L402HeaderComponents) null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void authenticatedTokenReturnsNullComponents() {
        L402Credential credential = createTestCredential(List.of());
        var token = L402AuthenticationToken.authenticated(credential, "svc");

        assertThat(token.getComponents()).isNull();
    }

    @Test
    void authenticatedTokenExposesCredentialDetails() {
        L402Credential credential = createTestCredential(List.of(
                new Caveat("service", "api"),
                new Caveat("valid_until", "2026-12-31T23:59:59Z")
        ));

        var token = L402AuthenticationToken.authenticated(credential, "my-api");

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
        var token = L402AuthenticationToken.authenticated(credential, "svc");

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_L402");
    }

    @Test
    void authenticatedTokenExtractsCaveatAttributes() {
        L402Credential credential = createTestCredential(List.of(
                new Caveat("service", "api"),
                new Caveat("tier", "premium")
        ));

        var token = L402AuthenticationToken.authenticated(credential, "api");

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

        var token = L402AuthenticationToken.authenticated(credential, "trusted-service");

        // Built-in attributes must win over attacker-controlled caveat keys
        assertThat(token.getAttribute("tokenId")).isEqualTo(credential.tokenId());
        assertThat(token.getAttribute("serviceName")).isEqualTo("trusted-service");
    }

    @Test
    void authenticatedTokenWithNullServiceName() {
        L402Credential credential = createTestCredential(List.of());
        var token = L402AuthenticationToken.authenticated(credential, null);

        assertThat(token.getServiceName()).isNull();
        assertThat(token.getAttributes()).doesNotContainKey("serviceName");
        assertThat(token.getAttributes()).containsKey("tokenId");
    }

    @Test
    void authenticatedTokenMapsCapabilitiesToAuthorities() {
        L402Credential credential = createTestCredential(List.of(
                new Caveat("test-service_capabilities", "search,analyze")
        ));

        var token = L402AuthenticationToken.authenticated(credential, "test-service");

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_L402", "L402_CAPABILITY_search", "L402_CAPABILITY_analyze");
    }

    @Test
    void authenticatedTokenWithNoCapabilitiesCaveatHasOnlyRoleL402() {
        L402Credential credential = createTestCredential(List.of(
                new Caveat("other_key", "value")
        ));

        var token = L402AuthenticationToken.authenticated(credential, "svc");

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_L402");
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

        var token = L402AuthenticationToken.authenticated(credential, "svc");

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_L402", "L402_CAPABILITY_search");
    }

    @Test
    void authenticatedTokenHandlesMalformedCapabilitiesValue() {
        L402Credential credential = createTestCredential(List.of(
                new Caveat("svc_capabilities", "search,,analyze,")
        ));

        var token = L402AuthenticationToken.authenticated(credential, "svc");

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_L402", "L402_CAPABILITY_search", "L402_CAPABILITY_analyze");
    }

    @Test
    void authenticatedTokenWithNullServiceNameSkipsCapabilityExtraction() {
        L402Credential credential = createTestCredential(List.of(
                new Caveat("null_capabilities", "search")
        ));

        var token = L402AuthenticationToken.authenticated(credential, null);

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_L402");
    }

    @Test
    void authenticatedTokenDeduplicatesAcrossMultipleCapabilityCaveats() {
        L402Credential credential = createTestCredential(List.of(
                new Caveat("svc_capabilities", "search,read"),
                new Caveat("svc_capabilities", "read,write")
        ));

        var token = L402AuthenticationToken.authenticated(credential, "svc");

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_L402", "L402_CAPABILITY_search", "L402_CAPABILITY_read", "L402_CAPABILITY_write");
    }

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
}
