package com.leonardorozza.mvgrreparacionesbackend.service.security;

import com.leonardorozza.mvgrreparacionesbackend.exceptions.DeviceCredentialProtectionException;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeviceCredentialCipherTests {

    private static final String KEY =
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private final DeviceCredentialCipher cipher = new DeviceCredentialCipher(KEY);

    @Test
    void cifraConNonceUnicoYAutenticaElContexto() {
        String first = cipher.encryptPin("7391", 10L);
        String second = cipher.encryptPin("7391", 10L);

        assertThat(first).startsWith("v1:").doesNotContain("7391");
        assertThat(second).startsWith("v1:").isNotEqualTo(first);
        assertThat(cipher.decryptPin(first, 10L)).isEqualTo("7391");

        assertThatThrownBy(() -> cipher.decryptPin(first, 11L))
                .isInstanceOf(DeviceCredentialProtectionException.class);
        assertThatThrownBy(() -> cipher.decryptPattern(first, 10L))
                .isInstanceOf(DeviceCredentialProtectionException.class);
    }

    @Test
    void rechazaCiphertextAlteradoSinIncluirloEnElError() {
        String encrypted = cipher.encryptPattern("zigzag", 20L);
        byte[] payload = Base64.getUrlDecoder().decode(encrypted.substring("v1:".length()));
        payload[payload.length - 1] ^= 0x01;
        String tampered = "v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);

        assertThatThrownBy(() -> cipher.decryptPattern(tampered, 20L))
                .isInstanceOf(DeviceCredentialProtectionException.class)
                .hasMessageNotContaining(encrypted)
                .hasMessageNotContaining("zigzag");
    }

    @Test
    void fallaSeguroSiLaClaveFaltaOEsInvalida() {
        assertThatThrownBy(() -> new DeviceCredentialCipher(""))
                .isInstanceOf(DeviceCredentialProtectionException.class)
                .hasMessageContaining(DeviceCredentialCipher.ENVIRONMENT_VARIABLE)
                .hasMessageNotContaining("1234");

        assertThatThrownBy(() -> new DeviceCredentialCipher("bm8tc29uLTMtYnl0ZXM="))
                .isInstanceOf(DeviceCredentialProtectionException.class)
                .hasMessageContaining("32 bytes");
    }
}
