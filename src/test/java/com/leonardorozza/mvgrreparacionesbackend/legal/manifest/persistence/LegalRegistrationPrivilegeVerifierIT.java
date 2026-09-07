package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.SQLException;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** Exact effective ACL and real PostgreSQL denials for the registration writer only. */
@Execution(ExecutionMode.SAME_THREAD)
class LegalRegistrationPrivilegeVerifierIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_registration_privileges").withUsername("ordenfix").withPassword("ordenfix");
    private static final String ROLE = "ordenfix_registration_privilege_it";
    private static final String PASSWORD = "registration-privilege-fixture";
    private static JdbcTemplate owner;
    private static JdbcTemplate restricted;
    private static LegalRegistrationPrivilegeVerifier verifier;

    @BeforeAll static void start() throws Exception {
        POSTGRES.start();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        var credentials = LegalRestrictedRegistrationRoleFixture.provision(owner, ROLE, PASSWORD);
        restricted = new JdbcTemplate(new DriverManagerDataSource(credentials.jdbcUrl(), credentials.username(), credentials.password()));
        verifier = new LegalRegistrationPrivilegeVerifier(restricted, ROLE, "public");
        assertThat(credentials.toString()).doesNotContain(PASSWORD, POSTGRES.getJdbcUrl());
    }

    @AfterAll static void stop() { POSTGRES.stop(); }

    @Test void exactPrivilegesAccreditV29WithoutSequencesOrTableWideBusinessAccess() {
        new LegalV29AcceptanceSchemaVerifier(restricted, "public").verify();
        verifier.verify();
        assertThat(restricted.queryForList("""
                SELECT relname FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                 WHERE n.nspname='public' AND relkind IN ('r','p') AND pg_catalog.has_table_privilege(c.oid,'SELECT')
                """, String.class)).containsExactlyInAnyOrder(
                "flyway_schema_history", "legal_publicaciones", "legal_publicacion_requisitos", "legal_publicacion_documentos",
                "legal_requisito_conjuntos_actuales", "legal_requisito_conjuntos", "legal_requisito_conjunto_miembros",
                "legal_requisito_lineas", "legal_requisito_audiencias", "legal_requisito_versiones", "legal_requisito_documentos",
                "legal_documento_lineas", "legal_documento_versiones", "legal_documento_contextos", "legal_documento_vigentes",
                "legal_requisito_agregados", "legal_requisito_agregado_scopes", "legal_aceptacion_lotes", "legal_aceptaciones",
                "legal_aceptacion_documentos", "legal_requisito_transiciones", "legal_documento_transiciones",
                "legal_aceptacion_metadatos", "legal_idempotencia_resultados", "legal_idempotencia_sin_actos",
                "legal_idempotencia_sin_actos_referencias");
        assertThat(restricted.queryForList("""
                SELECT relname FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                 WHERE n.nspname='public' AND relkind IN ('r','p') AND pg_catalog.has_table_privilege(c.oid,'INSERT')
                """, String.class)).containsExactlyInAnyOrder("legal_requisito_agregados", "legal_requisito_agregado_scopes",
                "legal_aceptacion_lotes", "legal_aceptaciones", "legal_aceptacion_documentos", "legal_aceptacion_metadatos",
                "legal_aceptacion_metadatos_cifrados", "legal_idempotencia_resultados", "legal_idempotencia_sin_actos",
                "legal_idempotencia_sin_actos_referencias");
        assertThat(restricted.queryForList("""
                SELECT relname||'.'||attname FROM pg_catalog.pg_class c
                JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                JOIN pg_catalog.pg_attribute a ON a.attrelid=c.oid
                 WHERE n.nspname='public' AND attnum>0 AND NOT attisdropped AND relkind IN ('r','p')
                   AND pg_catalog.has_column_privilege(c.oid, a.attnum, 'UPDATE') ORDER BY 1
                """, String.class)).containsExactly("legal_aceptacion_lotes.id", "legal_aceptacion_metadatos.lote_id",
                "legal_aceptaciones.id", "legal_idempotencia_sin_actos.id", "legal_requisito_agregados.id",
                "legal_requisito_conjuntos_actuales.conjunto_id", "talleres.id", "users.id");
        assertThat(restricted.queryForList("""
                SELECT attname FROM pg_catalog.pg_attribute
                 WHERE attrelid='public.legal_aceptacion_metadatos_cifrados'::regclass AND attnum>0 AND NOT attisdropped
                   AND pg_catalog.has_column_privilege(attrelid, attnum, 'SELECT') ORDER BY 1
                """, String.class)).containsExactly("lote_id", "tipo", "tombstone_en");
        assertThat(restricted.queryForObject("""
                SELECT count(*) FROM pg_catalog.pg_class WHERE relkind='S'
                  AND (pg_catalog.has_sequence_privilege(oid,'USAGE') OR pg_catalog.has_sequence_privilege(oid,'SELECT')
                       OR pg_catalog.has_sequence_privilege(oid,'UPDATE'))
                """, Integer.class)).isZero();
        assertThat(restricted.queryForObject("SELECT current_user = session_user AND current_user = ?", Boolean.class, ROLE)).isTrue();
        assertThat(restricted.queryForList("""
                SELECT c.relname||'.'||a.attname FROM pg_catalog.pg_class c
                  JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                  JOIN pg_catalog.pg_attribute a ON a.attrelid=c.oid
                 WHERE n.nspname='public' AND c.relname IN ('users','talleres','suscripciones')
                   AND a.attnum>0 AND NOT a.attisdropped
                   AND pg_catalog.has_column_privilege(c.oid,a.attnum,'INSERT') ORDER BY 1
                """, String.class)).containsExactly(
                "suscripciones.created_at", "suscripciones.estado", "suscripciones.fecha_fin_trial",
                "suscripciones.fecha_inicio", "suscripciones.plan", "suscripciones.taller_id", "suscripciones.updated_at",
                "talleres.activo", "talleres.created_at", "talleres.email_contacto", "talleres.nombre",
                "talleres.telefono", "talleres.updated_at", "users.active", "users.email", "users.email_verificado",
                "users.password", "users.role", "users.taller_id", "users.token_version", "users.username");
    }

    @Test void restrictedColumnInsertsCreateTheAccountWithoutReadingSubscriptionIdsOrCredentials() throws Exception {
        verifier.verify();
        String marker = UUID.randomUUID().toString();
        String email = marker + "@registration.test";
        LocalDate firstDay = LocalDate.of(2026, 9, 7);
        LocalDateTime auditAt = LocalDateTime.of(2026, 9, 7, 17, 20);
        long workshop;
        long user;
        try (Connection connection = Objects.requireNonNull(restricted.getDataSource()).getConnection()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            JdbcTemplate sameConnection = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            assertThat(sameConnection.queryForObject("SELECT current_user=session_user AND current_user=?",
                    Boolean.class, ROLE)).isTrue();
            workshop = Objects.requireNonNull(sameConnection.queryForObject("""
                    INSERT INTO public.talleres(nombre,email_contacto,telefono,activo,created_at,updated_at)
                    VALUES (?,?,?,true,?,?) RETURNING id
                    """, Long.class, "Registration " + marker, email, "1100000000", auditAt, auditAt));
            assertThat(sameConnection.update("""
                    INSERT INTO public.suscripciones(taller_id,plan,estado,fecha_inicio,fecha_fin_trial,created_at,updated_at)
                    VALUES (?,'FREE','TRIAL',?,?,?,?)
                    """, workshop, firstDay, firstDay.plusDays(14), auditAt, auditAt)).isEqualTo(1);
            user = Objects.requireNonNull(sameConnection.queryForObject("""
                    INSERT INTO public.users(username,password,email,role,taller_id,active,email_verificado,token_version)
                    VALUES (?,'synthetic-fixture-hash',?,'ADMIN',?,true,false,0) RETURNING id
                    """, Long.class, "Registration actor", email, workshop));
            assertThat(sameConnection.queryForObject("""
                    SELECT u.role='ADMIN' AND u.active AND u.token_version=0 AND t.activo
                      FROM public.users u JOIN public.talleres t ON t.id=u.taller_id
                     WHERE u.id=? AND t.id=?
                    """, Boolean.class, user, workshop)).isTrue();
            assertThat(owner.queryForObject("SELECT count(*) FROM users WHERE id=?", Long.class, user)).isZero();
            connection.commit();
        }
        assertThat(owner.queryForObject("""
                SELECT u.email=? AND u.password='synthetic-fixture-hash' AND u.role='ADMIN'
                   AND u.active AND NOT u.email_verificado AND u.token_version=0 AND t.activo
                   AND t.secuencia_orden=0 AND t.mostrar_en_resumen AND t.anio_secuencia_orden IS NULL
                   AND t.alias_cobro IS NULL AND t.titular_cobro IS NULL AND t.entidad_cobro IS NULL
                   AND t.created_at=? AND t.updated_at=? AND s.created_at=? AND s.updated_at=?
                   AND s.plan='FREE' AND s.estado='TRIAL' AND s.fecha_inicio=? AND s.fecha_fin_trial=?
                   AND s.reparaciones_mes=0 AND s.consumo_mes IS NULL AND s.mp_preapproval_id IS NULL
                   AND s.mp_payer_id IS NULL AND s.mp_status IS NULL AND s.proximo_cobro IS NULL
                  FROM public.users u JOIN public.talleres t ON t.id=u.taller_id
                  JOIN public.suscripciones s ON s.taller_id=t.id WHERE u.id=? AND t.id=?
                """, Boolean.class, email, auditAt, auditAt, auditAt, auditAt,
                firstDay, firstDay.plusDays(14), user, workshop)).isTrue();
        verifier.verify();
    }

    @Test void aCallerRollbackRemovesEveryBusinessRowInsertedWithTheRestrictedRole() throws Exception {
        verifier.verify();
        String marker = UUID.randomUUID().toString();
        long workshop;
        long user;
        try (Connection connection = Objects.requireNonNull(restricted.getDataSource()).getConnection()) {
            connection.setAutoCommit(false);
            JdbcTemplate sameConnection = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            workshop = Objects.requireNonNull(sameConnection.queryForObject(
                    "INSERT INTO talleres(nombre,activo) VALUES (?,true) RETURNING id", Long.class, marker));
            sameConnection.update("INSERT INTO suscripciones(taller_id,plan,estado) VALUES (?,'FREE','TRIAL')", workshop);
            user = Objects.requireNonNull(sameConnection.queryForObject("""
                    INSERT INTO users(username,password,email,role,taller_id,active,email_verificado,token_version)
                    VALUES (?,'synthetic-fixture-hash',?,'ADMIN',?,true,false,0) RETURNING id
                    """, Long.class, "Rollback actor", marker + "@registration.test", workshop));
            connection.rollback();
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM users WHERE id=?", Long.class, user)).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM suscripciones WHERE taller_id=?", Long.class, workshop)).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM talleres WHERE id=?", Long.class, workshop)).isZero();
        verifier.verify();
    }

    @Test void everyBusinessInsertColumnIsMandatoryButItsGrantOptionIsForbidden() {
        LegalRegistrationPrivilegeVerifier.INSERT_COLUMNS.forEach((table, columns) -> columns.forEach(column -> {
            drift("REVOKE INSERT (" + column + ") ON " + table + " FROM " + ROLE,
                    "GRANT INSERT (" + column + ") ON " + table + " TO " + ROLE);
        }));
        drift("GRANT INSERT (email) ON users TO " + ROLE + " WITH GRANT OPTION",
                "REVOKE GRANT OPTION FOR INSERT (email) ON users FROM " + ROLE);
    }

    @ParameterizedTest @ValueSource(strings = {"users", "talleres", "suscripciones"})
    void businessTableWideInsertRemainsForbidden(String table) {
        verifier.verify();
        sqlState(() -> restricted.update("INSERT INTO " + table + " (id) VALUES (987654321)"), "42501");
        // PostgreSQL also revokes column INSERT grants when revoking the table privilege.
        String columns = String.join(", ", LegalRegistrationPrivilegeVerifier.INSERT_COLUMNS.get(table).stream().sorted().toList());
        drift("GRANT INSERT ON " + table + " TO " + ROLE,
                "REVOKE INSERT ON " + table + " FROM " + ROLE,
                "GRANT INSERT (" + columns + ") ON " + table + " TO " + ROLE);
    }

    @ParameterizedTest @ValueSource(strings = {"users.id", "talleres.id", "suscripciones.id",
            "suscripciones.reparaciones_mes", "talleres.mostrar_en_resumen"})
    void additionalBusinessInsertColumnsFailClosed(String qualifiedColumn) {
        String[] parts = qualifiedColumn.split("\\.");
        drift("GRANT INSERT (" + parts[1] + ") ON " + parts[0] + " TO " + ROLE,
                "REVOKE INSERT (" + parts[1] + ") ON " + parts[0] + " FROM " + ROLE);
    }

    @ParameterizedTest @ValueSource(strings = {"users.email", "users.password", "users.username",
            "talleres.email_contacto", "suscripciones.id", "suscripciones.plan"})
    void privateAccountReadsAreDeniedAndTheirGrantIsRejected(String qualifiedColumn) {
        String[] parts = qualifiedColumn.split("\\.");
        verifier.verify();
        sqlState(() -> restricted.queryForList("SELECT " + parts[1] + " FROM " + parts[0]), "42501");
        drift("GRANT SELECT (" + parts[1] + ") ON " + parts[0] + " TO " + ROLE,
                "REVOKE SELECT (" + parts[1] + ") ON " + parts[0] + " FROM " + ROLE);
    }

    @Test void subscriptionReturningIdCannotExpandTheApprovedReadSurface() {
        verifier.verify();
        long workshop = Objects.requireNonNull(owner.queryForObject(
                "INSERT INTO talleres(nombre) VALUES (?) RETURNING id", Long.class, "Returning " + UUID.randomUUID()));
        sqlState(() -> restricted.queryForObject("""
                INSERT INTO suscripciones(taller_id,plan,estado) VALUES (?,'FREE','TRIAL') RETURNING id
                """, Long.class, workshop), "42501");
        assertThat(owner.queryForObject("SELECT count(*) FROM suscripciones WHERE taller_id=?", Long.class, workshop)).isZero();
        verifier.verify();
    }

    @ParameterizedTest @ValueSource(strings = {"users_id_seq", "talleres_id_seq", "suscripciones_id_seq"})
    void accountIdentitySequencesNeverNeedDirectCapabilities(String sequence) {
        for (String privilege : List.of("USAGE", "SELECT", "UPDATE")) {
            drift("GRANT " + privilege + " ON SEQUENCE " + sequence + " TO " + ROLE,
                    "REVOKE " + privilege + " ON SEQUENCE " + sequence + " FROM " + ROLE);
        }
        sqlState(() -> restricted.queryForObject("SELECT nextval('public." + sequence + "')", Long.class), "42501");
    }

    @Test void aSystemSchemaCollisionAlsoIncludesTheSubscriptionTable() {
        verifier.verify();
        owner.execute("CREATE TABLE information_schema.suscripciones(id bigint)");
        try {
            assertThatThrownBy(verifier::verify).isInstanceOf(LegalEditorialOperationalException.class);
        } finally {
            owner.execute("DROP TABLE information_schema.suscripciones");
        }
        verifier.verify();
    }

    @ParameterizedTest @ValueSource(strings = {"legal_aceptacion_lotes", "legal_aceptaciones", "legal_aceptacion_documentos",
            "legal_aceptacion_metadatos", "legal_aceptacion_metadatos_cifrados", "legal_idempotencia_resultados",
            "legal_idempotencia_sin_actos", "legal_idempotencia_sin_actos_referencias"})
    void eachAdditionalInsertIsMandatory(String table) {
        drift("REVOKE INSERT ON " + table + " FROM " + ROLE, "GRANT INSERT ON " + table + " TO " + ROLE);
    }

    @ParameterizedTest @ValueSource(strings = {"legal_aceptacion_metadatos", "legal_idempotencia_resultados",
            "legal_idempotencia_sin_actos", "legal_idempotencia_sin_actos_referencias"})
    void eachResultAndHeaderReadIsMandatory(String table) {
        drift("REVOKE SELECT ON " + table + " FROM " + ROLE, "GRANT SELECT ON " + table + " TO " + ROLE);
    }

    @ParameterizedTest @ValueSource(strings = {"lote_id", "tipo", "tombstone_en"})
    void onlyRequiredMetadataColumnsAreMandatory(String column) {
        drift("REVOKE SELECT (" + column + ") ON legal_aceptacion_metadatos_cifrados FROM " + ROLE,
                "GRANT SELECT (" + column + ") ON legal_aceptacion_metadatos_cifrados TO " + ROLE);
    }

    @ParameterizedTest @ValueSource(strings = {"ciphertext", "tag", "nonce", "key_version", "id", "longitud_original"})
    void encryptedPayloadReadsAreDeniedAndEvenOneColumnFailsPreflight(String column) {
        sqlState(() -> restricted.queryForList("SELECT " + column + " FROM legal_aceptacion_metadatos_cifrados"), "42501");
        drift("GRANT SELECT (" + column + ") ON legal_aceptacion_metadatos_cifrados TO " + ROLE,
                "REVOKE SELECT (" + column + ") ON legal_aceptacion_metadatos_cifrados FROM " + ROLE);
    }

    @Test void deniedCapabilitiesCannotBeRecoveredThroughPublicColumnsFunctionsOrGrantOptions() {
        sqlState(() -> restricted.queryForList("SELECT email, password FROM users"), "42501");
        sqlState(() -> restricted.execute("DELETE FROM legal_idempotencia_resultados"), "42501");
        sqlState(() -> restricted.execute("UPDATE legal_aceptaciones SET afirmacion = afirmacion"), "42501");
        sqlState(() -> restricted.execute("SELECT pg_catalog.pg_advisory_lock(123::bigint)"), "42501");
        sqlState(() -> restricted.execute("CREATE TEMP TABLE unauthorized(id integer)"), "42501");
        drift("GRANT SELECT (email) ON users TO " + ROLE, "REVOKE SELECT (email) ON users FROM " + ROLE);
        drift("GRANT DELETE ON legal_idempotencia_resultados TO " + ROLE, "REVOKE DELETE ON legal_idempotencia_resultados FROM " + ROLE);
        drift("GRANT SELECT ON legal_aceptaciones TO PUBLIC", "REVOKE SELECT ON legal_aceptaciones FROM PUBLIC");
        drift("GRANT INSERT ON legal_aceptaciones TO " + ROLE + " WITH GRANT OPTION",
                "REVOKE GRANT OPTION FOR INSERT ON legal_aceptaciones FROM " + ROLE);
        drift("GRANT EXECUTE ON FUNCTION legal_validar_conjuntos_actuales() TO " + ROLE,
                "REVOKE EXECUTE ON FUNCTION legal_validar_conjuntos_actuales() FROM " + ROLE);
    }

    @Test void mandatoryFunctionClosureAndLockColumnsAreAccreditedIndividually() {
        for (String function : LegalRegistrationPrivilegeVerifier.PRIVILEGED_FUNCTIONS) {
            drift("REVOKE EXECUTE ON FUNCTION " + function + " FROM " + ROLE,
                    "GRANT EXECUTE ON FUNCTION " + function + " TO " + ROLE);
        }
        LegalRegistrationPrivilegeVerifier.UPDATE_COLUMNS.forEach((table, columns) -> columns.forEach(column ->
                drift("REVOKE UPDATE (" + column + ") ON " + table + " FROM " + ROLE,
                        "GRANT UPDATE (" + column + ") ON " + table + " TO " + ROLE)));
    }

    @Test void systemPrivilegesMembershipOwnershipAndSearchPathFailClosed() {
        drift("ALTER ROLE " + ROLE + " INHERIT", "ALTER ROLE " + ROLE + " NOINHERIT");
        drift("GRANT pg_read_all_data TO " + ROLE, "REVOKE pg_read_all_data FROM " + ROLE);
        drift("GRANT USAGE ON SEQUENCE users_id_seq TO " + ROLE, "REVOKE USAGE ON SEQUENCE users_id_seq FROM " + ROLE);
        drift("GRANT SET ON PARAMETER session_replication_role TO " + ROLE,
                "REVOKE SET ON PARAMETER session_replication_role FROM " + ROLE);
        drift("GRANT CREATE ON SCHEMA public TO " + ROLE, "REVOKE CREATE ON SCHEMA public FROM " + ROLE);
        drift("ALTER ROLE " + ROLE + " IN DATABASE " + POSTGRES.getDatabaseName() + " SET search_path=public",
                "ALTER ROLE " + ROLE + " IN DATABASE " + POSTGRES.getDatabaseName() + " SET search_path=pg_catalog,public,pg_temp");
        assertThatThrownBy(() -> new LegalRegistrationPrivilegeVerifier(restricted, "foreign", "public").verify())
                .isInstanceOf(LegalEditorialOperationalException.class);
    }

    @Test void roleFixtureRefusesReuseBeforeChangingGrants() {
        assertThatThrownBy(() -> LegalRestrictedRegistrationRoleFixture.provision(owner, ROLE, "other-fixture"))
                .isInstanceOf(IllegalStateException.class);
        verifier.verify();
    }

    @Test void roleFixtureRefusesAnUnapprovedDatabaseBeforeCreatingARole() {
        String candidateRole = "ordenfix_registration_rejected_fixture";
        String otherUrl = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/postgres";
        JdbcTemplate otherDatabase = new JdbcTemplate(new DriverManagerDataSource(
                otherUrl, POSTGRES.getUsername(), POSTGRES.getPassword()));
        assertThat(owner.queryForObject("SELECT count(*) FROM pg_roles WHERE rolname=?", Long.class, candidateRole)).isZero();

        assertThatThrownBy(() -> LegalRestrictedRegistrationRoleFixture.provision(
                otherDatabase, candidateRole, "unused-fixture-password"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("El fixture de registro sólo puede mutar una base efímera dedicada");

        assertThat(owner.queryForObject("SELECT count(*) FROM pg_roles WHERE rolname=?", Long.class, candidateRole)).isZero();
        verifier.verify();
    }

    private static void drift(String mutation, String... restorations) {
        verifier.verify();
        owner.execute(mutation);
        try { assertThatThrownBy(verifier::verify).isInstanceOf(LegalEditorialOperationalException.class); }
        finally { for (String restoration : restorations) owner.execute(restoration); }
        verifier.verify();
    }

    private static void sqlState(org.assertj.core.api.ThrowableAssert.ThrowingCallable work, String expected) {
        Throwable failure = catchThrowable(work);
        assertThat(failure).isNotNull();
        while (failure != null && !(failure instanceof SQLException)) failure = failure.getCause();
        assertThat(failure).isInstanceOf(SQLException.class);
        assertThat(((SQLException) failure).getSQLState()).isEqualTo(expected);
    }
}
