package com.greenharborlabs.paygate.spring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@DisplayName("CaffeineCapabilityCache")
class CaffeineCapabilityCacheTest {

    private CaffeineCapabilityCache cache;

    @BeforeEach
    void setUp() {
        cache = new CaffeineCapabilityCache(10_000);
    }

    @Nested
    @DisplayName("store and retrieve")
    class StoreAndRetrieve {

        @Test
        @DisplayName("returns stored capability by tokenId")
        void storeAndGet() {
            cache.store("tok1", "search", 3600);
            assertThat(cache.get("tok1")).isEqualTo("search");
        }
    }

    @Nested
    @DisplayName("missing entries")
    class MissingEntries {

        @Test
        @DisplayName("returns null for nonexistent tokenId")
        void missingEntry() {
            assertThat(cache.get("nonexistent")).isNull();
        }
    }

    @Nested
    @DisplayName("expiration")
    class Expiration {

        @Test
        @DisplayName("returns null for expired entry")
        void expiredEntry() {
            cache.store("tok2", "read", 0);
            // Caffeine needs cleanUp to process zero-TTL entries
            assertThat(cache.get("tok2")).isNull();
        }
    }

    @Nested
    @DisplayName("null/empty capability no-op")
    class NullEmptyCapability {

        @Test
        @DisplayName("null capability is a no-op")
        void nullCapability() {
            cache.store("tok3", null, 3600);
            assertThat(cache.get("tok3")).isNull();
        }

        @Test
        @DisplayName("empty capability is a no-op")
        void emptyCapability() {
            cache.store("tok3", "", 3600);
            assertThat(cache.get("tok3")).isNull();
        }
    }

    @Nested
    @DisplayName("error handling")
    class ErrorHandling {

        @Test
        @DisplayName("null tokenId throws NullPointerException")
        void nullTokenId() {
            assertThatNullPointerException()
                    .isThrownBy(() -> cache.store(null, "search", 3600));
        }

        @Test
        @DisplayName("negative TTL throws IllegalArgumentException")
        void negativeTtl() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> cache.store("tok4", "search", -1));
        }
    }
}
