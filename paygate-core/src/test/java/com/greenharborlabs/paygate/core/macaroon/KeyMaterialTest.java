package com.greenharborlabs.paygate.core.macaroon;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class KeyMaterialTest {

    @Test
    void zeroizeFillsWithZeros() {
        byte[] key = new byte[]{0x01, 0x02, 0x03};
        KeyMaterial.zeroize(key);
        assertThat(key).containsOnly(0);
    }

    @Test
    void zeroizeNullIsNoop() {
        assertThatNoException().isThrownBy(() -> KeyMaterial.zeroize((byte[]) null));
    }

    @Test
    void zeroizeVarargsHandlesNulls() {
        byte[] a = {1, 2};
        byte[] b = {3, 4};
        KeyMaterial.zeroize(a, null, b);
        assertThat(a).containsOnly(0);
        assertThat(b).containsOnly(0);
    }

    @Test
    void zeroizeVarargsNullArrayIsNoop() {
        assertThatNoException().isThrownBy(() -> KeyMaterial.zeroize((byte[][]) null));
    }

    @Test
    void zeroizeNormalArrayAllBytesZero() {
        byte[] data = new byte[32];
        Arrays.fill(data, (byte) 0xFF);
        KeyMaterial.zeroize(data);
        assertThat(data).hasSize(32).containsOnly(0);
    }

    @Test
    void zeroizeEmptyArray() {
        byte[] empty = new byte[0];
        KeyMaterial.zeroize(empty);
        assertThat(empty).isEmpty();
    }
}
