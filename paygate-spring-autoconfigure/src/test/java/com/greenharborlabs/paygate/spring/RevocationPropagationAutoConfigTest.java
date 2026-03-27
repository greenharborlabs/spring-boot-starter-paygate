package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.core.credential.CredentialStore;
import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.InvoiceStatus;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.core.lightning.PaymentPreimage;
import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.ObservableRootKeyStore;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import com.greenharborlabs.paygate.api.crypto.SensitiveBytes;
import com.greenharborlabs.paygate.core.protocol.L402Credential;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests verifying that root key revocation propagates to
 * credential cache eviction through the auto-configured
 * {@link ObservableRootKeyStore} and {@link com.greenharborlabs.paygate.core.credential.CredentialCacheEvictionListener}.
 */
@DisplayName("Revocation propagation via auto-configuration")
class RevocationPropagationAutoConfigTest {

    private static final HexFormat HEX = HexFormat.of();

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PaygateAutoConfiguration.class,
                    WebMvcAutoConfiguration.class
            ))
            .withPropertyValues(
                    "paygate.enabled=true",
                    "paygate.backend=lnbits",
                    "paygate.root-key-store=memory"
            )
            .withBean(LightningBackend.class, StubLightningBackend::new);

    @Test
    @DisplayName("RootKeyStore bean is ObservableRootKeyStore when paygate.enabled=true")
    void rootKeyStoreIsObservable() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(RootKeyStore.class);
            assertThat(context.getBean(RootKeyStore.class))
                    .isInstanceOf(ObservableRootKeyStore.class);
        });
    }

    @Test
    @DisplayName("revoking a root key evicts the corresponding credential from CredentialStore")
    void revocationEvictsCredential() {
        contextRunner.run(context -> {
            RootKeyStore rootKeyStore = context.getBean(RootKeyStore.class);
            CredentialStore credentialStore = context.getBean(CredentialStore.class);

            // Generate a root key to get a valid keyId
            var result = rootKeyStore.generateRootKey();
            byte[] keyId = result.tokenId();
            String hexKeyId = HEX.formatHex(keyId);

            // Store a credential keyed by the hex keyId
            var credential = createTestCredential(hexKeyId);
            credentialStore.store(hexKeyId, credential, 3600);

            // Verify credential is stored
            assertThat(credentialStore.get(hexKeyId)).isNotNull();

            // Revoke the root key — should trigger eviction
            rootKeyStore.revokeRootKey(keyId);

            // Credential should be evicted
            assertThat(credentialStore.get(hexKeyId)).isNull();
        });
    }

    @Test
    @DisplayName("custom RootKeyStore override is wrapped with ObservableRootKeyStore")
    void customRootKeyStoreIsWrapped() {
        contextRunner
                .withUserConfiguration(CustomRootKeyStoreConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(RootKeyStore.class);
                    RootKeyStore store = context.getBean(RootKeyStore.class);
                    assertThat(store).isInstanceOf(ObservableRootKeyStore.class);
                });
    }

    @Test
    @DisplayName("already-wrapped ObservableRootKeyStore is not double-wrapped")
    void noDoubleWrapping() {
        contextRunner
                .withUserConfiguration(PreWrappedRootKeyStoreConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(RootKeyStore.class);
                    RootKeyStore store = context.getBean(RootKeyStore.class);
                    assertThat(store).isInstanceOf(ObservableRootKeyStore.class);
                    // If double-wrapped, the outer observable would not be the same instance
                    // that was registered. Verify it's the exact pre-wrapped instance.
                    assertThat(store).isSameAs(
                            context.getBean(PreWrappedRootKeyStoreConfig.class).preWrapped);
                });
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static final SecureRandom RANDOM = new SecureRandom();

    private static L402Credential createTestCredential(String tokenId) {
        byte[] identifier = new byte[66];
        RANDOM.nextBytes(identifier);
        byte[] signature = new byte[32];
        RANDOM.nextBytes(signature);
        byte[] preimageBytes = new byte[32];
        RANDOM.nextBytes(preimageBytes);

        Macaroon macaroon = new Macaroon(identifier, "https://example.com", List.of(), signature);
        PaymentPreimage preimage = new PaymentPreimage(preimageBytes);
        return new L402Credential(macaroon, preimage, tokenId);
    }

    // -----------------------------------------------------------------------
    // Test configurations
    // -----------------------------------------------------------------------

    @Configuration(proxyBeanMethods = false)
    static class CustomRootKeyStoreConfig {
        @Bean
        RootKeyStore rootKeyStore() {
            return new CustomRootKeyStore();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PreWrappedRootKeyStoreConfig {
        final ObservableRootKeyStore preWrapped =
                new ObservableRootKeyStore(new CustomRootKeyStore());

        @Bean
        RootKeyStore rootKeyStore() {
            return preWrapped;
        }
    }

    static class CustomRootKeyStore implements RootKeyStore {
        @Override
        public GenerationResult generateRootKey() {
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            byte[] id = new byte[32];
            new SecureRandom().nextBytes(id);
            return new GenerationResult(new SensitiveBytes(key), id);
        }

        @Override
        public SensitiveBytes getRootKey(byte[] keyId) {
            return new SensitiveBytes(new byte[32]);
        }

        @Override
        public void revokeRootKey(byte[] keyId) {
            // no-op
        }
    }

    static class StubLightningBackend implements LightningBackend {
        @Override
        public Invoice createInvoice(long amountSats, String memo) {
            byte[] paymentHash = new byte[32];
            new SecureRandom().nextBytes(paymentHash);
            Instant now = Instant.now();
            return new Invoice(paymentHash, "lnbc" + amountSats + "n1pstub", amountSats,
                    memo, InvoiceStatus.PENDING, null, now, now.plus(1, ChronoUnit.HOURS));
        }

        @Override
        public Invoice lookupInvoice(byte[] paymentHash) {
            return null;
        }

        @Override
        public boolean isHealthy() {
            return true;
        }
    }
}
