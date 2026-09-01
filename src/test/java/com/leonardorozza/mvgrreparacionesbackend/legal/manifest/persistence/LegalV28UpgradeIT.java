package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class LegalV28UpgradeIT {

    private static final String SCHEMA = "legal_v28_upgrade_it";
    private static final String AGGREGATE_REVISION = "sha256:" + "b".repeat(64);
    private static final String PROVENANCE = "sha256:" + "c".repeat(64);
    private static final UUID AGGREGATE_ID = uuid("v28-upgrade:aggregate");
    private static final UUID AGGREGATE_LOT_ID = uuid("v28-upgrade:acceptance-lot");
    private static final UUID AGGREGATE_ACCEPTANCE_ID = uuid("v28-upgrade:acceptance");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_v28_upgrade")
            .withUsername("ordenfix")
            .withPassword("ordenfix");

    @Test
    void preservesGenuineV27HistoryAndAllowsOnlyNewAggregateV1Lots() throws SQLException {
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        migrate(dataSource, "27");

        LegalV27AcceptanceHistoryFixture fixture =
                new LegalV27AcceptanceHistoryFixture(dataSource, SCHEMA);
        LegalV27AcceptanceHistoryFixture.SeededHistory seeded = fixture.seed();
        LegalV27AcceptanceHistoryFixture.HistorySnapshot before = fixture.capture(seeded);
        Integer v27Checksum = successfulChecksum(dataSource, "27");

        assertThat(before.counts()).containsAllEntriesOf(java.util.Map.of(
                "legal_requisito_conjuntos", 3,
                "legal_requisito_conjunto_miembros", 2,
                "legal_requisito_conjuntos_actuales", 3,
                "legal_aceptacion_lotes", 1,
                "legal_aceptaciones", 2,
                "legal_aceptacion_documentos", 2,
                "legal_aceptacion_metadatos", 1,
                "legal_aceptacion_metadatos_cifrados", 2,
                "legal_idempotencia_resultados", 1));
        assertThat(before.relationships())
                .anySatisfy(relation -> assertThat(relation)
                        .startsWith("CURRENT|CIERRE_CUENTA|")
                        .endsWith("|members=0"))
                .anySatisfy(relation -> assertThat(relation)
                        .startsWith("CURRENT|USO_CONTINUADO|")
                        .contains(seeded.commonRevision()))
                .anySatisfy(relation -> assertThat(relation)
                        .startsWith("CURRENT|ATESTACION_FOTOS|")
                        .contains(seeded.commonRevision()))
                .anySatisfy(relation -> assertThat(relation)
                        .startsWith("ACT|")
                        .endsWith("|USO_CONTINUADO"))
                .anySatisfy(relation -> assertThat(relation)
                        .startsWith("ACT|")
                        .endsWith("|ATESTACION_FOTOS"));

        migrate(dataSource, null);

        LegalV27AcceptanceHistoryFixture.HistorySnapshot afterUpgrade =
                fixture.capture(seeded);
        assertHistoryUnchanged(before, afterUpgrade);
        assertThat(successfulChecksum(dataSource, "27")).isEqualTo(v27Checksum);
        assertThat(queryInt(dataSource, """
                SELECT pg_catalog.count(*)
                  FROM %s
                 WHERE success AND version = '28'
                """.formatted(table("flyway_schema_history")))).isOne();
        assertThat(queryInt(dataSource, "SELECT pg_catalog.count(*) FROM "
                + table("legal_requisito_agregados"))).isZero();
        assertThat(queryInt(dataSource, "SELECT pg_catalog.count(*) FROM "
                + table("legal_requisito_agregado_scopes"))).isZero();

        LegacyColumns legacyColumns = queryLegacyColumns(dataSource, seeded.legacyLotId());
        assertThat(legacyColumns.revisionScheme()).isEqualTo("SCOPE_V1");
        assertThat(legacyColumns.profile()).isNull();
        assertThat(legacyColumns.aggregateId()).isNull();
        assertThat(legacyColumns.requiredSetRevision()).isEqualTo(seeded.commonRevision());

        long aggregateActor = insertValidAggregateAndLot(dataSource, seeded);

        assertThat(queryStrings(dataSource, """
                SELECT revision_scheme
                  FROM %s
                 ORDER BY revision_scheme
                """.formatted(table("legal_aceptacion_lotes"))))
                .containsExactly("AGGREGATE_V1", "SCOPE_V1");
        assertThat(queryInt(dataSource, "SELECT pg_catalog.count(*) FROM "
                + table("legal_requisito_agregados"))).isOne();
        assertThat(queryInt(dataSource, "SELECT pg_catalog.count(*) FROM "
                + table("legal_requisito_agregado_scopes"))).isEqualTo(3);
        assertThat(queryInt(dataSource, """
                SELECT pg_catalog.count(*)
                  FROM %s s
                  JOIN %s m ON m.conjunto_id = s.conjunto_id
                 WHERE s.agregado_id = ?
                   AND s.contexto = 'CIERRE_CUENTA'
                """.formatted(
                        table("legal_requisito_agregado_scopes"),
                        table("legal_requisito_conjunto_miembros")),
                AGGREGATE_ID)).isZero();
        assertHistoryUnchanged(before, fixture.capture(seeded));

        SQLException scopeRejection = attemptNewScopeV1Lot(dataSource, seeded, aggregateActor);
        assertThat(scopeRejection.getSQLState()).isEqualTo("23514");
        assertThat(scopeRejection.getMessage()).contains("AGGREGATE_V1");
        assertThat(queryInt(dataSource, """
                SELECT pg_catalog.count(*)
                  FROM %s
                 WHERE revision_scheme = 'SCOPE_V1'
                """.formatted(table("legal_aceptacion_lotes")))).isOne();
        assertHistoryUnchanged(before, fixture.capture(seeded));

        assertThatThrownBy(fixture::seed)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current exactly 27")
                .hasMessageContaining("observed 28");
    }

    private static void assertHistoryUnchanged(
            LegalV27AcceptanceHistoryFixture.HistorySnapshot before,
            LegalV27AcceptanceHistoryFixture.HistorySnapshot after) {
        assertThat(after.tables())
                .as("canonical legacy row bytes and xmin")
                .isEqualTo(before.tables());
        assertThat(after.counts()).as("legacy cardinalities").isEqualTo(before.counts());
        assertThat(after.relationships())
                .as("legacy foreign-key relationships")
                .isEqualTo(before.relationships());
    }

    private static long insertValidAggregateAndLot(
            DataSource dataSource,
            LegalV27AcceptanceHistoryFixture.SeededHistory seeded) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("""
                            SELECT pg_catalog.pg_advisory_xact_lock_shared(
                                pg_catalog.hashtextextended(
                                    'ordenfix:legal-publicaciones:sello:v1', 0))
                            """);
                }
                update(connection, """
                        INSERT INTO %s
                            (id, perfil, locale, audiencia, revision_scheme,
                             required_set_revision, provenance_fingerprint,
                             scope_count, creado_en)
                        VALUES (?, 'AUTHENTICATED_PENDING', 'es-AR', 'ADMIN_TITULAR',
                                'AGGREGATE_V1', ?, ?, 3, statement_timestamp())
                        """.formatted(table("legal_requisito_agregados")),
                        AGGREGATE_ID, AGGREGATE_REVISION, PROVENANCE);
                insertAggregateScope(connection, 1, "USO_CONTINUADO");
                insertAggregateScope(connection, 2, "ATESTACION_FOTOS");
                insertAggregateScope(connection, 3, "CIERRE_CUENTA");

                long workshopId = insertReturningLong(connection, """
                        INSERT INTO %s (nombre)
                        VALUES ('Taller lote agregado V28')
                        RETURNING id
                        """.formatted(table("talleres")));
                long userId = insertReturningLong(connection, """
                        INSERT INTO %s (username, password, email, role, taller_id)
                        VALUES ('v28-upgrade-admin', 'hash-v28-upgrade',
                                'v28-upgrade@ordenfix.test', 'ADMIN', ?)
                        RETURNING id
                        """.formatted(table("users")), workshopId);
                update(connection, """
                        INSERT INTO %s
                            (id, user_id, taller_id, rol_wire, audiencia,
                             required_set_revision, aceptado_en, revision_scheme,
                             perfil, agregado_id)
                        VALUES (?, ?, ?, 'ADMIN', 'ADMIN_TITULAR', ?,
                                statement_timestamp(), 'AGGREGATE_V1',
                                'AUTHENTICATED_PENDING', ?)
                        """.formatted(table("legal_aceptacion_lotes")),
                        AGGREGATE_LOT_ID,
                        userId,
                        workshopId,
                        AGGREGATE_REVISION,
                        AGGREGATE_ID);
                UUID usageRequirement = seeded.requirementIds().get("USO_CONTINUADO");
                update(connection, """
                        INSERT INTO %s
                            (id, lote_id, user_id, taller_id, requisito_version_id,
                             requisito_clave, requisito_version, contexto, tipo_acto,
                             afirmacion, afirmacion_sha256, requerido)
                        SELECT ?, ?, ?, ?, v.id, l.clave, v.version, l.contexto,
                               l.tipo_acto, v.afirmacion, v.afirmacion_sha256, v.requerido
                          FROM %s v
                          JOIN %s l ON l.id = v.requisito_linea_id
                         WHERE v.id = ?
                        """.formatted(
                                table("legal_aceptaciones"),
                                table("legal_requisito_versiones"),
                                table("legal_requisito_lineas")),
                        AGGREGATE_ACCEPTANCE_ID,
                        AGGREGATE_LOT_ID,
                        userId,
                        workshopId,
                        usageRequirement);
                update(connection, """
                        INSERT INTO %s
                            (aceptacion_id, documento_ordinal, documento_version_id,
                             documento_clave, tipo, version, titulo, sha256)
                        SELECT ?, d.documento_ordinal, v.id, l.clave, l.tipo,
                               v.version, v.titulo, v.sha256
                          FROM %s d
                          JOIN %s v ON v.id = d.documento_version_id
                          JOIN %s l ON l.id = v.documento_linea_id
                         WHERE d.requisito_version_id = ?
                        """.formatted(
                                table("legal_aceptacion_documentos"),
                                table("legal_requisito_documentos"),
                                table("legal_documento_versiones"),
                                table("legal_documento_lineas")),
                        AGGREGATE_ACCEPTANCE_ID,
                        usageRequirement);
                update(connection, """
                        INSERT INTO %s (lote_id, capturado_en, retener_hasta)
                        VALUES (?, statement_timestamp(),
                                statement_timestamp() + INTERVAL '30 days')
                        """.formatted(table("legal_aceptacion_metadatos")),
                        AGGREGATE_LOT_ID);
                update(connection, """
                        INSERT INTO %s
                            (lote_id, tipo, key_version, nonce, ciphertext, tag,
                             longitud_original)
                        VALUES (?, 'IP', 1, ?, ?, ?, 9)
                        """.formatted(table("legal_aceptacion_metadatos_cifrados")),
                        AGGREGATE_LOT_ID,
                        bytes(12, 21),
                        bytes(9, 41),
                        bytes(16, 61));
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET CONSTRAINTS ALL IMMEDIATE");
                }
                connection.commit();
                return userId;
            } catch (RuntimeException | SQLException error) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    error.addSuppressed(rollbackError);
                }
                throw error;
            }
        }
    }

    private static void insertAggregateScope(
            Connection connection,
            int ordinal,
            String context) throws SQLException {
        update(connection, """
                INSERT INTO %s
                    (agregado_id, scope_ordinal, contexto, conjunto_id,
                     publicacion_id, locale, audiencia, required_set_revision)
                SELECT ?, ?, a.contexto, a.conjunto_id, a.publicacion_id,
                       a.locale, a.audiencia, c.required_set_revision
                  FROM %s a
                  JOIN %s c
                    ON c.id = a.conjunto_id
                   AND c.publicacion_id = a.publicacion_id
                 WHERE a.locale = 'es-AR'
                   AND a.contexto = ?
                   AND a.audiencia = 'ADMIN_TITULAR'
                """.formatted(
                        table("legal_requisito_agregado_scopes"),
                        table("legal_requisito_conjuntos_actuales"),
                        table("legal_requisito_conjuntos")),
                AGGREGATE_ID, ordinal, context);
    }

    private static SQLException attemptNewScopeV1Lot(
            DataSource dataSource,
            LegalV27AcceptanceHistoryFixture.SeededHistory seeded,
            long aggregateActor) throws SQLException {
        long workshopId = queryLong(dataSource, """
                SELECT taller_id FROM %s WHERE id = ?
                """.formatted(table("users")), aggregateActor);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                update(connection, """
                        INSERT INTO %s
                            (id, user_id, taller_id, rol_wire, audiencia,
                             required_set_revision, aceptado_en, revision_scheme,
                             perfil, agregado_id)
                        VALUES (?, ?, ?, 'ADMIN', 'ADMIN_TITULAR', ?,
                                statement_timestamp(), 'SCOPE_V1', NULL, NULL)
                        """.formatted(table("legal_aceptacion_lotes")),
                        uuid("v28-upgrade:forbidden-scope-lot"),
                        aggregateActor,
                        workshopId,
                        seeded.commonRevision());
                connection.commit();
                throw new AssertionError("V28 unexpectedly accepted a new SCOPE_V1 lot");
            } catch (SQLException expected) {
                connection.rollback();
                return expected;
            }
        }
    }

    private static LegacyColumns queryLegacyColumns(DataSource dataSource, UUID lotId)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT revision_scheme, perfil, agregado_id, required_set_revision
                       FROM %s
                      WHERE id = ?
                     """.formatted(table("legal_aceptacion_lotes")))) {
            statement.setObject(1, lotId);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                LegacyColumns result = new LegacyColumns(
                        rows.getString(1),
                        rows.getString(2),
                        rows.getObject(3, UUID.class),
                        rows.getString(4));
                assertThat(rows.next()).isFalse();
                return result;
            }
        }
    }

    private static Integer successfulChecksum(DataSource dataSource, String version)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT checksum
                       FROM %s
                      WHERE success AND version = ?
                     """.formatted(table("flyway_schema_history")))) {
            statement.setString(1, version);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                Integer checksum = (Integer) rows.getObject(1);
                assertThat(rows.next()).isFalse();
                return checksum;
            }
        }
    }

    private static int queryInt(DataSource dataSource, String sql, Object... parameters)
            throws SQLException {
        return Math.toIntExact(queryLong(dataSource, sql, parameters));
    }

    private static long queryLong(DataSource dataSource, String sql, Object... parameters)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = prepare(connection, sql, parameters);
             ResultSet rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            long value = rows.getLong(1);
            assertThat(rows.next()).isFalse();
            return value;
        }
    }

    private static List<String> queryStrings(DataSource dataSource, String sql)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (rows.next()) {
                values.add(rows.getString(1));
            }
            return List.copyOf(values);
        }
    }

    private static long insertReturningLong(
            Connection connection,
            String sql,
            Object... parameters) throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, parameters);
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                throw new IllegalStateException("INSERT did not return an id");
            }
            long value = rows.getLong(1);
            if (rows.next()) {
                throw new IllegalStateException("INSERT returned more than one id");
            }
            return value;
        }
    }

    private static void update(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, parameters)) {
            int changed = statement.executeUpdate();
            if (changed != 1) {
                throw new IllegalStateException(
                        "Expected exactly one affected row but observed " + changed);
            }
        }
    }

    private static PreparedStatement prepare(
            Connection connection,
            String sql,
            Object... parameters) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            for (int index = 0; index < parameters.length; index++) {
                if (parameters[index] instanceof byte[] bytes) {
                    statement.setBytes(index + 1, bytes);
                } else {
                    statement.setObject(index + 1, parameters[index]);
                }
            }
            return statement;
        } catch (RuntimeException | SQLException error) {
            statement.close();
            throw error;
        }
    }

    private static void migrate(DataSource dataSource, String target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .createSchemas(true);
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private static String table(String name) {
        return '"' + SCHEMA + "\".\"" + name + '"';
    }

    private static UUID uuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] bytes(int length, int seed) {
        byte[] value = new byte[length];
        for (int index = 0; index < length; index++) {
            value[index] = (byte) (seed + index);
        }
        return value;
    }

    private record LegacyColumns(
            String revisionScheme,
            String profile,
            UUID aggregateId,
            String requiredSetRevision) {
    }
}
