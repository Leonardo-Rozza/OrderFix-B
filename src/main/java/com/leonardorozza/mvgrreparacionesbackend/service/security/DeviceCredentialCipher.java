package com.leonardorozza.mvgrreparacionesbackend.service.security;

import com.leonardorozza.mvgrreparacionesbackend.exceptions.DeviceCredentialProtectionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Protege PIN y patrón con AES-256-GCM. Cada valor usa un nonce aleatorio y AAD
 * que lo liga a la reparación y al tipo de credencial, evitando intercambios
 * silenciosos entre filas o columnas.
 */
@Component
public class DeviceCredentialCipher {

    public static final String ENVIRONMENT_VARIABLE = "DEVICE_CREDENTIALS_ENCRYPTION_KEY";

    private static final String FORMAT_PREFIX = "v1:";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    /**
     * La propiedad usa exactamente el nombre de la variable de entorno. No hay
     * default operativo ni se persiste la clave en archivos de configuración.
     */
    public DeviceCredentialCipher(
            @Value("${DEVICE_CREDENTIALS_ENCRYPTION_KEY:}") String encodedKey) {
        this.key = parseKey(encodedKey);
    }

    public String encryptPin(String plaintext, long reparacionId) {
        return encrypt(plaintext, context(reparacionId, "pin"));
    }

    public String encryptPattern(String plaintext, long reparacionId) {
        return encrypt(plaintext, context(reparacionId, "patron"));
    }

    public String decryptPin(String ciphertext, long reparacionId) {
        return decrypt(ciphertext, context(reparacionId, "pin"));
    }

    public String decryptPattern(String ciphertext, long reparacionId) {
        return decrypt(ciphertext, context(reparacionId, "patron"));
    }

    private String encrypt(String plaintext, byte[] context) {
        if (plaintext == null) {
            return null;
        }
        SecretKeySpec configuredKey = requireKey();
        byte[] nonce = new byte[NONCE_BYTES];
        SECURE_RANDOM.nextBytes(nonce);

        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, configuredKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(context);
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = ByteBuffer.allocate(nonce.length + encrypted.length)
                    .put(nonce)
                    .put(encrypted)
                    .array();
            return FORMAT_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        } catch (GeneralSecurityException ex) {
            throw new DeviceCredentialProtectionException(
                    "No se pudo proteger la credencial del dispositivo.", ex);
        }
    }

    private String decrypt(String ciphertext, byte[] context) {
        if (ciphertext == null) {
            return null;
        }
        SecretKeySpec configuredKey = requireKey();
        if (!ciphertext.startsWith(FORMAT_PREFIX)) {
            throw new DeviceCredentialProtectionException(
                    "La credencial del dispositivo tiene un formato no reconocido.");
        }

        try {
            byte[] payload = Base64.getUrlDecoder().decode(ciphertext.substring(FORMAT_PREFIX.length()));
            if (payload.length < NONCE_BYTES + (TAG_BITS / Byte.SIZE)) {
                throw new DeviceCredentialProtectionException(
                        "La credencial del dispositivo está incompleta.");
            }
            byte[] nonce = Arrays.copyOfRange(payload, 0, NONCE_BYTES);
            byte[] encrypted = Arrays.copyOfRange(payload, NONCE_BYTES, payload.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, configuredKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(context);
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (DeviceCredentialProtectionException ex) {
            throw ex;
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new DeviceCredentialProtectionException(
                    "No se pudo validar la credencial protegida del dispositivo.", ex);
        }
    }

    private SecretKeySpec requireKey() {
        return key;
    }

    private static SecretKeySpec parseKey(String encodedKey) {
        if (!StringUtils.hasText(encodedKey)) {
            throw new DeviceCredentialProtectionException(
                    "Falta la variable de entorno " + ENVIRONMENT_VARIABLE + ".");
        }

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedKey.trim());
        } catch (IllegalArgumentException ex) {
            throw new DeviceCredentialProtectionException(
                    "La variable de entorno " + ENVIRONMENT_VARIABLE + " no es Base64 válido.", ex);
        }

        try {
            if (decoded.length != KEY_BYTES) {
                throw new DeviceCredentialProtectionException(
                        "La variable de entorno " + ENVIRONMENT_VARIABLE
                                + " debe contener exactamente 32 bytes codificados en Base64.");
            }
            return new SecretKeySpec(decoded, "AES");
        } finally {
            Arrays.fill(decoded, (byte) 0);
        }
    }

    private static byte[] context(long reparacionId, String field) {
        return ("reparacion:" + reparacionId + ":" + field + ":v1")
                .getBytes(StandardCharsets.UTF_8);
    }
}
