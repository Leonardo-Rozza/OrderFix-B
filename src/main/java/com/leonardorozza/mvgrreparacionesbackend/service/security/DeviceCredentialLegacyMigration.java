package com.leonardorozza.mvgrreparacionesbackend.service.security;

import com.leonardorozza.mvgrreparacionesbackend.exceptions.DeviceCredentialProtectionException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Convierte en el primer arranque los valores planos preservados por V20.
 * La operación es atómica: si falta la clave o un ciphertext existente no se
 * puede autenticar, el arranque falla y la transacción completa se revierte.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class DeviceCredentialLegacyMigration implements ApplicationRunner {

    static final short LEGACY_PLAINTEXT = 0;
    static final short AES_GCM_V1 = 1;

    private final JdbcTemplate jdbcTemplate;
    private final DeviceCredentialCipher cipher;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<CredentialRow> rows = jdbcTemplate.query("""
                        SELECT id,
                               patron_desbloqueo_cifrado,
                               pin_desbloqueo_cifrado,
                               credenciales_cifrado_version
                        FROM reparaciones
                        WHERE credenciales_cifrado_version <> 1
                           OR patron_desbloqueo_cifrado IS NOT NULL
                           OR pin_desbloqueo_cifrado IS NOT NULL
                        ORDER BY id
                        FOR UPDATE
                        """,
                (rs, rowNum) -> new CredentialRow(
                        rs.getLong("id"),
                        rs.getString("patron_desbloqueo_cifrado"),
                        rs.getString("pin_desbloqueo_cifrado"),
                        rs.getShort("credenciales_cifrado_version")));

        for (CredentialRow row : rows) {
            if (row.version() == LEGACY_PLAINTEXT) {
                migrateLegacyRow(row);
            } else if (row.version() == AES_GCM_V1) {
                validateEncryptedRow(row);
            } else {
                throw new DeviceCredentialProtectionException(
                        "Versión de cifrado de credenciales no soportada en la reparación " + row.id() + ".");
            }
        }
    }

    private void migrateLegacyRow(CredentialRow row) {
        String encryptedPattern = cipher.encryptPattern(row.pattern(), row.id());
        String encryptedPin = cipher.encryptPin(row.pin(), row.id());

        int updated = jdbcTemplate.update("""
                        UPDATE reparaciones
                        SET patron_desbloqueo_cifrado = ?,
                            pin_desbloqueo_cifrado = ?,
                            credenciales_cifrado_version = 1
                        WHERE id = ? AND credenciales_cifrado_version = 0
                        """,
                encryptedPattern, encryptedPin, row.id());

        if (updated != 1) {
            throw new DeviceCredentialProtectionException(
                    "La migración de credenciales cambió concurrentemente para la reparación " + row.id() + ".");
        }
    }

    private void validateEncryptedRow(CredentialRow row) {
        cipher.decryptPattern(row.pattern(), row.id());
        cipher.decryptPin(row.pin(), row.id());
    }

    private record CredentialRow(long id, String pattern, String pin, short version) {
    }
}
