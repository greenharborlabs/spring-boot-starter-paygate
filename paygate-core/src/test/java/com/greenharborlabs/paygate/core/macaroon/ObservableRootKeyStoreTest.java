package com.greenharborlabs.paygate.core.macaroon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ObservableRootKeyStore")
class ObservableRootKeyStoreTest {

    private StubRootKeyStore delegate;
    private ObservableRootKeyStore store;

    @BeforeEach
    void setUp() {
        delegate = new StubRootKeyStore();
        store = new ObservableRootKeyStore(delegate);
    }

    @Nested
    @DisplayName("delegation")
    class Delegation {

        @Test
        @DisplayName("generateRootKey delegates to wrapped store")
        void generateDelegates() {
            RootKeyStore.GenerationResult result = store.generateRootKey();

            assertThat(result).isNotNull();
            assertThat(delegate.generateCalled).isTrue();
        }

        @Test
        @DisplayName("getRootKey delegates to wrapped store")
        void getDelegates() {
            byte[] keyId = {1, 2, 3};
            store.getRootKey(keyId);

            assertThat(delegate.getKeyIdReceived).isEqualTo(keyId);
        }

        @Test
        @DisplayName("revokeRootKey delegates to wrapped store")
        void revokeDelegates() {
            byte[] keyId = {4, 5, 6};
            store.revokeRootKey(keyId);

            assertThat(delegate.revokeKeyIdReceived).isEqualTo(keyId);
        }

        @Test
        @DisplayName("close delegates to wrapped store")
        void closeDelegates() {
            store.close();

            assertThat(delegate.closeCalled).isTrue();
        }
    }

    @Nested
    @DisplayName("listener notification")
    class ListenerNotification {

        @Test
        @DisplayName("listener called with correct keyId on revoke")
        void listenerCalledWithCorrectKeyId() {
            byte[] keyId = {10, 20, 30};
            AtomicReference<byte[]> received = new AtomicReference<>();
            store.addRevocationListener(received::set);

            store.revokeRootKey(keyId);

            assertThat(received.get()).isEqualTo(keyId);
        }

        @Test
        @DisplayName("multiple listeners all called")
        void multipleListenersAllCalled() {
            byte[] keyId = {1, 2, 3};
            List<byte[]> calls = new ArrayList<>();
            store.addRevocationListener(calls::add);
            store.addRevocationListener(calls::add);
            store.addRevocationListener(calls::add);

            store.revokeRootKey(keyId);

            assertThat(calls).hasSize(3);
            for (byte[] call : calls) {
                assertThat(call).isEqualTo(keyId);
            }
        }

        @Test
        @DisplayName("listener exception does not prevent other listeners from firing")
        void listenerExceptionDoesNotPreventOthers() {
            byte[] keyId = {7, 8, 9};
            List<byte[]> calls = new ArrayList<>();
            store.addRevocationListener(calls::add);
            store.addRevocationListener(_ -> { throw new RuntimeException("boom"); });
            store.addRevocationListener(calls::add);

            store.revokeRootKey(keyId);

            assertThat(calls).hasSize(2);
        }

        @Test
        @DisplayName("listener exception does not prevent revocation from completing")
        void listenerExceptionDoesNotPreventRevocation() {
            byte[] keyId = {11, 12, 13};
            store.addRevocationListener(_ -> { throw new RuntimeException("boom"); });

            store.revokeRootKey(keyId);

            // Delegate was called before listeners, so revocation completed
            assertThat(delegate.revokeKeyIdReceived).isEqualTo(keyId);
        }

        @Test
        @DisplayName("listeners not called if delegate revoke throws")
        void listenersNotCalledIfDelegateThrows() {
            delegate.revokeThrows = new RuntimeException("delegate failure");
            List<byte[]> calls = new ArrayList<>();
            store.addRevocationListener(calls::add);

            assertThatThrownBy(() -> store.revokeRootKey(new byte[]{1}))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("delegate failure");

            assertThat(calls).isEmpty();
        }
    }

    @Nested
    @DisplayName("defensive copy")
    class DefensiveCopy {

        @Test
        @DisplayName("mutating keyId after revoke does not affect listener's copy")
        void defensiveCopyProtectsListeners() {
            byte[] keyId = {1, 2, 3, 4};
            AtomicReference<byte[]> received = new AtomicReference<>();
            store.addRevocationListener(received::set);

            store.revokeRootKey(keyId);
            byte[] beforeMutation = Arrays.copyOf(received.get(), received.get().length);

            // Mutate the original array
            keyId[0] = (byte) 0xFF;

            assertThat(received.get()).isEqualTo(beforeMutation);
        }
    }

    @Nested
    @DisplayName("null checks")
    class NullChecks {

        @Test
        @DisplayName("null delegate throws NullPointerException")
        void nullDelegateThrows() {
            assertThatThrownBy(() -> new ObservableRootKeyStore(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null listener throws NullPointerException")
        void nullListenerThrows() {
            assertThatThrownBy(() -> store.addRevocationListener(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // --- Stub delegate for testing ---

    private static final class StubRootKeyStore implements RootKeyStore {
        boolean generateCalled;
        byte[] getKeyIdReceived;
        byte[] revokeKeyIdReceived;
        boolean closeCalled;
        RuntimeException revokeThrows;

        @Override
        public GenerationResult generateRootKey() {
            generateCalled = true;
            byte[] key = {1, 2, 3, 4, 5, 6, 7, 8};
            byte[] tokenId = {9, 10, 11, 12};
            return new GenerationResult(new SensitiveBytes(key), tokenId);
        }

        @Override
        public SensitiveBytes getRootKey(byte[] keyId) {
            getKeyIdReceived = keyId;
            return null;
        }

        @Override
        public void revokeRootKey(byte[] keyId) {
            if (revokeThrows != null) {
                throw revokeThrows;
            }
            revokeKeyIdReceived = keyId;
        }

        @Override
        public void close() {
            closeCalled = true;
        }
    }
}
