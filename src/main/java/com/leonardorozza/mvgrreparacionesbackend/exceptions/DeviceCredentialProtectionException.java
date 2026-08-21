package com.leonardorozza.mvgrreparacionesbackend.exceptions;

/**
 * Falla interna al cifrar, descifrar o migrar credenciales de acceso a un dispositivo.
 * El mensaje nunca debe contener texto plano ni ciphertext.
 */
public class DeviceCredentialProtectionException extends RuntimeException {

    public DeviceCredentialProtectionException(String message) {
        super(message);
    }

    public DeviceCredentialProtectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
