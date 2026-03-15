package com.greenharborlabs.l402.spring.security;

import com.greenharborlabs.l402.core.lightning.PaymentPreimage;
import com.greenharborlabs.l402.core.macaroon.Caveat;
import com.greenharborlabs.l402.core.macaroon.L402VerificationContext;
import com.greenharborlabs.l402.core.macaroon.Macaroon;
import com.greenharborlabs.l402.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.l402.core.protocol.ErrorCode;
import com.greenharborlabs.l402.core.protocol.L402Credential;
import com.greenharborlabs.l402.core.protocol.L402Exception;
import com.greenharborlabs.l402.core.protocol.L402Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.security.SecureRandom;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class L402AuthenticationProviderTest {

    @Mock
    private L402Validator l402Validator;

    private L402AuthenticationProvider provider;

    private static final SecureRandom RNG = new SecureRandom();
    private static final String SERVICE_NAME = "test-service";

    @BeforeEach
    void setUp() {
        provider = new L402AuthenticationProvider(l402Validator, SERVICE_NAME);
    }

    @Test
    void constructorRejectsNullValidator() {
        assertThatThrownBy(() -> new L402AuthenticationProvider(null, "svc"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void supportsL402AuthenticationToken() {
        assertThat(provider.supports(L402AuthenticationToken.class)).isTrue();
    }

    @Test
    void doesNotSupportOtherTokenTypes() {
        assertThat(provider.supports(UsernamePasswordAuthenticationToken.class)).isFalse();
    }

    @Test
    void returnsNullForNonL402Authentication() {
        var otherAuth = new UsernamePasswordAuthenticationToken("user", "pass");

        Authentication result = provider.authenticate(otherAuth);

        assertThat(result).isNull();
    }

    @Test
    void authenticatesValidL402Token() {
        String macaroonB64 = "dGVzdG1hY2Fyb29u";
        String preimageHex = "a".repeat(64);

        L402Credential credential = createTestCredential(List.of(new Caveat("service", "api")));
        String expectedHeader = "L402 " + macaroonB64 + ":" + preimageHex;
        when(l402Validator.validate(eq(expectedHeader), any(L402VerificationContext.class)))
                .thenReturn(new L402Validator.ValidationResult(credential, true));

        var unauthToken = new L402AuthenticationToken(macaroonB64, preimageHex);

        Authentication result = provider.authenticate(unauthToken);

        assertThat(result).isInstanceOf(L402AuthenticationToken.class);
        assertThat(result.isAuthenticated()).isTrue();

        var authToken = (L402AuthenticationToken) result;
        assertThat(authToken.getTokenId()).isEqualTo(credential.tokenId());
        assertThat(authToken.getServiceName()).isEqualTo(SERVICE_NAME);
        assertThat(authToken.getL402Credential()).isEqualTo(credential);
        assertThat(authToken.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_L402");
        assertThat(authToken.getAttribute("service")).isEqualTo("api");
    }

    @Test
    void throwsBadCredentialsOnValidationFailure() {
        String macaroonB64 = "badmac";
        String preimageHex = "b".repeat(64);
        String expectedHeader = "L402 " + macaroonB64 + ":" + preimageHex;

        when(l402Validator.validate(eq(expectedHeader), any(L402VerificationContext.class)))
                .thenThrow(new L402Exception(ErrorCode.INVALID_MACAROON, "bad sig", "tok1"));

        var unauthToken = new L402AuthenticationToken(macaroonB64, preimageHex);

        assertThatThrownBy(() -> provider.authenticate(unauthToken))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("L402 authentication failed")
                .hasMessageNotContaining("bad sig")
                .hasCauseInstanceOf(L402Exception.class);
    }

    @Test
    void throwsBadCredentialsWhenRawCredentialsMissing() {
        L402Credential credential = createTestCredential(List.of());
        var authenticatedToken = L402AuthenticationToken.authenticated(credential, "svc");

        assertThatThrownBy(() -> provider.authenticate(authenticatedToken))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("missing raw credentials");
    }

    @Test
    void allowsNullServiceName() {
        var providerNoService = new L402AuthenticationProvider(l402Validator, null);

        String macaroonB64 = "dGVzdA==";
        String preimageHex = "c".repeat(64);
        String expectedHeader = "L402 " + macaroonB64 + ":" + preimageHex;

        L402Credential credential = createTestCredential(List.of());
        when(l402Validator.validate(eq(expectedHeader), any(L402VerificationContext.class)))
                .thenReturn(new L402Validator.ValidationResult(credential, true));

        var unauthToken = new L402AuthenticationToken(macaroonB64, preimageHex);
        Authentication result = providerNoService.authenticate(unauthToken);

        assertThat(result).isInstanceOf(L402AuthenticationToken.class);
        assertThat(((L402AuthenticationToken) result).getServiceName()).isNull();
    }

    @Test
    void passesRequestedCapabilityThroughToValidatorContext() {
        String macaroonB64 = "dGVzdG1hY2Fyb29u";
        String preimageHex = "a".repeat(64);
        String expectedHeader = "L402 " + macaroonB64 + ":" + preimageHex;

        L402Credential credential = createTestCredential(List.of(new Caveat("capabilities", "read")));
        when(l402Validator.validate(eq(expectedHeader), any(L402VerificationContext.class)))
                .thenReturn(new L402Validator.ValidationResult(credential, true));

        var unauthToken = new L402AuthenticationToken(macaroonB64, preimageHex, "read");

        Authentication result = provider.authenticate(unauthToken);

        assertThat(result.isAuthenticated()).isTrue();

        ArgumentCaptor<L402VerificationContext> contextCaptor =
                ArgumentCaptor.forClass(L402VerificationContext.class);
        verify(l402Validator).validate(eq(expectedHeader), contextCaptor.capture());

        L402VerificationContext capturedContext = contextCaptor.getValue();
        assertThat(capturedContext.getServiceName()).isEqualTo(SERVICE_NAME);
        assertThat(capturedContext.getRequestedCapability()).isEqualTo("read");
    }

    @Test
    void passesNullCapabilityWhenNotSpecified() {
        String macaroonB64 = "dGVzdG1hY2Fyb29u";
        String preimageHex = "a".repeat(64);
        String expectedHeader = "L402 " + macaroonB64 + ":" + preimageHex;

        L402Credential credential = createTestCredential(List.of());
        when(l402Validator.validate(eq(expectedHeader), any(L402VerificationContext.class)))
                .thenReturn(new L402Validator.ValidationResult(credential, true));

        var unauthToken = new L402AuthenticationToken(macaroonB64, preimageHex);

        provider.authenticate(unauthToken);

        ArgumentCaptor<L402VerificationContext> contextCaptor =
                ArgumentCaptor.forClass(L402VerificationContext.class);
        verify(l402Validator).validate(eq(expectedHeader), contextCaptor.capture());

        L402VerificationContext capturedContext = contextCaptor.getValue();
        assertThat(capturedContext.getServiceName()).isEqualTo(SERVICE_NAME);
        assertThat(capturedContext.getRequestedCapability()).isNull();
    }

    @Test
    void capabilityMismatchResultsInBadCredentialsException() {
        String macaroonB64 = "dGVzdG1hY2Fyb29u";
        String preimageHex = "a".repeat(64);
        String expectedHeader = "L402 " + macaroonB64 + ":" + preimageHex;

        when(l402Validator.validate(eq(expectedHeader), any(L402VerificationContext.class)))
                .thenThrow(new L402Exception(ErrorCode.INVALID_SERVICE, "capability mismatch", "tok1"));

        var unauthToken = new L402AuthenticationToken(macaroonB64, preimageHex, "write");

        assertThatThrownBy(() -> provider.authenticate(unauthToken))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("L402 authentication failed")
                .hasCauseInstanceOf(L402Exception.class);
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
        var preimage = new PaymentPreimage(paymentHash);

        String tokenId = java.util.HexFormat.of().formatHex(tokenIdBytes);
        return new L402Credential(macaroon, preimage, tokenId);
    }
}
