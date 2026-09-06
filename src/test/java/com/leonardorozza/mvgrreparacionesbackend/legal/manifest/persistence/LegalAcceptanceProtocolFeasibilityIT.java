package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.Actor;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.Stage;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.Written;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.AudienciaLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.PerfilAgregadoLegal;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceProtocolFeasibilityITSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Characterizes the frozen V28 SQL acceptance protocol before designing V29.
 * Fixture-only additive PK guards demonstrate feasibility, not production deployment/readiness.
 */
@Execution(ExecutionMode.SAME_THREAD)
class LegalAcceptanceProtocolFeasibilityIT {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName(DATABASE)
            .withUsername("ordenfix")
            .withPassword("ordenfix");

    @TempDir
    private static Path temporaryDirectory;

    private static DataSource ownerDataSource;
    private static JdbcTemplate owner;
    private static LegalAcceptanceProtocolFeasibilityITSupport fixture;

    @BeforeAll
    static void migrateFrozenV28AndProvisionNominalFixtureRoles() throws Exception {
        POSTGRES.start();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target("28").load().migrate();
        ownerDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        owner = new JdbcTemplate(ownerDataSource);
        var release = LegalV28AggregateITSupport.releaseWithContinuedUse(
                temporaryDirectory, LegalAcceptanceProtocolFeasibilityIT.class, "acceptance-protocol-15a");
        UUID publication = LegalV28AggregateITSupport.importRelease(ownerDataSource, release);
        LegalManifestPersistenceITSupport.promoteToReady(owner, publication);
        fixture = new LegalAcceptanceProtocolFeasibilityITSupport(ownerDataSource, POSTGRES.getJdbcUrl());
        fixture.provisionRoles();
        assertThat(owner.queryForObject("""
                SELECT checksum FROM flyway_schema_history WHERE version = '27' AND success
                """, Integer.class)).isEqualTo(1_575_269_868);
        assertThat(owner.queryForObject("""
                SELECT checksum FROM flyway_schema_history WHERE version = '28' AND success
                """, Integer.class)).isEqualTo(1_900_377_028);
    }

    @AfterAll
    static void stopFixtureContainer() {
        POSTGRES.stop();
    }

    @Test
    void ownerControlCommitsCanonicalEvidenceMetadataAndLedgerInOneTransaction() throws Exception {
        Actor actor = insertActor(owner);
        Written written;
        String transactionId;
        try (Connection connection = transaction(ownerDataSource)) {
            JdbcTemplate jdbc = jdbc(connection);
            written = writeCompleteAcceptance(jdbc, actor);
            transactionId = transactionId(jdbc);
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
            connection.commit();
        }
        assertCommitted(written, transactionId, false);
    }

    @Test
    void newIdempotencyKeyCannotReferenceAnAlreadyCommittedLot() throws Exception {
        Actor actor = insertActor(owner);
        Written existing;
        try (Connection connection = transaction(ownerDataSource)) {
            existing = writeCompleteAcceptance(jdbc(connection), actor);
            connection.commit();
        }
        Map<String, Long> before = fixture.counts();
        try (Connection connection = transaction(ownerDataSource)) {
            JdbcTemplate jdbc = jdbc(connection);
            // A preceding business write proves rollback of the entire caller transaction.
            jdbc.update("INSERT INTO talleres (nombre) VALUES ('15A rollback before historical ledger')");
            Throwable failure = catchThrowable(() -> insertLedger(jdbc, existing.lot(), "ACEPTACION_LEGAL"));
            assertSqlState(failure, "23514");
            assertThat(sqlCause(failure).getMessage())
                    .contains("debe confirmarse con el resultado de negocio");
            connection.rollback();
        }
        assertThat(fixture.counts()).isEqualTo(before);
        assertThat(owner.queryForObject("""
                SELECT count(*) FROM legal_idempotencia_resultados WHERE lote_id = ?
                """, Long.class, existing.lot().id())).isEqualTo(1L);
    }

    @Test
    void anEmptyLotWithCompleteMetadataAndLedgerStillFailsAtCommit() throws Exception {
        Actor actor = insertActor(owner);
        Map<String, Long> before = fixture.counts();
        try (Connection connection = transaction(ownerDataSource)) {
            JdbcTemplate jdbc = jdbc(connection);
            var aggregate = materialize(jdbc, PerfilAgregadoLegal.AUTHENTICATED_PENDING, AudienciaLegal.USER);
            var lot = insertLot(jdbc, actor, aggregate);
            insertMetadata(jdbc, lot);
            insertLedger(jdbc, lot, "ACEPTACION_LEGAL");
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM legal_aceptacion_metadatos_cifrados
                     WHERE lote_id = ? AND tipo = 'IP' AND ciphertext IS NOT NULL
                    """, Long.class, lot.id())).isEqualTo(1L);
            Throwable failure = catchThrowable(connection::commit);
            assertSqlState(failure, "23514");
            assertThat(sqlCause(failure).getMessage()).contains("no contiene actos");
            connection.rollback();
        }
        assertThat(fixture.counts()).isEqualTo(before);
    }

    @Test
    void selectOnlyActorPermissionCannotTakeShareLocksOrCompleteTheLotInsertGraph() throws Exception {
        Actor actor = insertActor(owner);
        DataSource selectActor = fixture.dataSource(SELECT_ACTOR_ROLE);
        try (Connection connection = transaction(selectActor)) {
            JdbcTemplate jdbc = jdbc(connection);
            assertThat(jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, actor.userId()))
                    .isEqualTo("USER");
            Throwable failure = catchThrowable(() -> jdbc.queryForObject(
                    "SELECT id FROM users WHERE id = ? FOR SHARE", Long.class, actor.userId()));
            assertSqlState(failure, "42501");
            assertThat(sqlCause(failure).getMessage()).contains("users");
            connection.rollback();
        }
        Map<String, Long> before = fixture.counts();
        try (Connection connection = transaction(selectActor)) {
            // Same complete writer as the owner/positive restricted controls. It fails in the lot
            // guard's users FOR SHARE, before later inserts can disguise the missing privilege.
            Throwable failure = catchThrowable(() -> writeCompleteAcceptance(jdbc(connection), actor));
            assertSqlState(failure, "42501");
            assertThat(sqlCause(failure).getMessage()).contains("users");
            connection.rollback();
        }
        assertThat(fixture.counts()).isEqualTo(before);
    }

    @Test
    void updateIdGrantAloneAllowsRowLocksAndDangerousRealIdentityMutation() throws Exception {
        Actor unreferencedActor = insertActor(owner);
        long unreferencedWorkshop = Objects.requireNonNull(owner.queryForObject(
                "INSERT INTO talleres (nombre) VALUES ('15A unreferenced workshop') RETURNING id", Long.class));
        long changedUser = -unreferencedActor.userId();
        long changedWorkshop = -unreferencedWorkshop;
        Map<String, Long> before = fixture.counts();
        try (Connection connection = transaction(fixture.dataSource(UNSAFE_ACTOR_ROLE))) {
            JdbcTemplate jdbc = jdbc(connection);
            assertThat(jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR SHARE",
                    Long.class, unreferencedActor.userId())).isEqualTo(unreferencedActor.userId());
            assertThat(jdbc.queryForObject("SELECT id FROM talleres WHERE id = ? FOR SHARE",
                    Long.class, unreferencedWorkshop)).isEqualTo(unreferencedWorkshop);
            assertThat(jdbc.update("UPDATE users SET id = ? WHERE id = ?",
                    changedUser, unreferencedActor.userId())).isOne();
            assertThat(jdbc.update("UPDATE talleres SET id = ? WHERE id = ?",
                    changedWorkshop, unreferencedWorkshop)).isOne();
            connection.commit();
        }
        assertThat(fixture.counts()).isEqualTo(before);
        assertThat(owner.queryForObject("SELECT count(*) FROM users WHERE id = ?", Long.class, changedUser))
                .isEqualTo(1L);
        assertThat(owner.queryForObject("SELECT count(*) FROM users WHERE id = ?",
                Long.class, unreferencedActor.userId())).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM talleres WHERE id = ?",
                Long.class, changedWorkshop)).isEqualTo(1L);
        assertThat(owner.queryForObject("SELECT count(*) FROM talleres WHERE id = ?",
                Long.class, unreferencedWorkshop)).isZero();
    }

    @Test
    void globalStatementGuardRejectsRealNoopAndZeroRowIdentityUpdatesWithoutBreakingAcceptance()
            throws Exception {
        Actor actor = insertActor(owner);
        fixture.installIdentityGuard();
        try {
            for (DataSource dataSource : List.of(ownerDataSource, fixture.dataSource(ACCEPTOR_ROLE))) {
                for (String table : List.of("users", "talleres")) {
                    long id = table.equals("users") ? actor.userId() : actor.workshopId();
                    for (String update : List.of(
                            "UPDATE " + table + " SET id = -id WHERE id = ?",
                            "UPDATE " + table + " SET id = id WHERE id = ?",
                            "UPDATE " + table + " SET id = id WHERE id = ? AND false")) {
                        Map<String, Long> before = fixture.counts();
                        try (Connection connection = transaction(dataSource)) {
                            Throwable failure = catchThrowable(() -> jdbc(connection).update(update, id));
                            assertSqlState(failure, "23514");
                            assertThat(sqlCause(failure).getMessage())
                                    .contains("15A fixture: identity UPDATE is forbidden");
                            connection.rollback();
                        }
                        assertThat(fixture.counts()).isEqualTo(before);
                        assertThat(owner.queryForObject("SELECT count(*) FROM " + table + " WHERE id = ?",
                                Long.class, id)).isEqualTo(1L);
                    }
                }
            }

            Written written;
            String transactionId;
            try (Connection connection = transaction(fixture.dataSource(ACCEPTOR_ROLE))) {
                JdbcTemplate jdbc = jdbc(connection);
                assertThat(jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR SHARE",
                        Long.class, actor.userId())).isEqualTo(actor.userId());
                assertThat(jdbc.queryForObject("SELECT id FROM talleres WHERE id = ? FOR SHARE",
                        Long.class, actor.workshopId())).isEqualTo(actor.workshopId());
                written = writeCompleteAcceptance(jdbc, actor);
                transactionId = transactionId(jdbc);
                jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
                connection.commit();
            }
            assertCommitted(written, transactionId, false);
            for (String forbidden : List.of(
                    "INSERT INTO talleres (nombre) VALUES ('forbidden')",
                    "INSERT INTO users DEFAULT VALUES",
                    "UPDATE users SET email = email WHERE false",
                    "DELETE FROM legal_aceptaciones WHERE false",
                    "TRUNCATE TABLE legal_aceptacion_lotes",
                    "CREATE TABLE public.ordenfix_15a_forbidden (id int)")) {
                try (Connection connection = transaction(fixture.dataSource(ACCEPTOR_ROLE))) {
                    Throwable failure = catchThrowable(() -> jdbc(connection).execute(forbidden));
                    assertSqlState(failure, "42501");
                    connection.rollback();
                }
            }
        } finally {
            fixture.removeIdentityGuard();
        }
    }

    @Test
    void restrictedJdbcRegistrationRollsBackEveryStageThenCommitsAllBusinessAndLegalRowsTogether()
            throws Exception {
        fixture.installIdentityGuard();
        try {
            Map<String, Long> before = fixture.counts();
            DataSource registration = fixture.dataSource(REGISTRATION_ROLE);
            for (Stage failedStage : Stage.values()) {
                List<Stage> reached = new ArrayList<>();
                try (Connection connection = transaction(registration)) {
                    Throwable failure = catchThrowable(() -> writeRegistration(jdbc(connection), stage -> {
                        reached.add(stage);
                        if (stage == failedStage) {
                            throw new FixtureRollback(stage);
                        }
                    }));
                    assertThat(failure).isInstanceOf(FixtureRollback.class);
                    assertThat(reached.getLast()).isEqualTo(failedStage);
                    connection.rollback();
                }
                assertThat(fixture.counts()).as("durable rows after rollback at %s", failedStage)
                        .isEqualTo(before);
            }
            Written written;
            String transactionId;
            List<Stage> completed = new ArrayList<>();
            try (Connection connection = transaction(registration)) {
                JdbcTemplate jdbc = jdbc(connection);
                written = writeRegistration(jdbc, completed::add);
                transactionId = transactionId(jdbc);
                connection.commit();
            }
            assertThat(completed).containsExactly(Stage.values());
            assertCommitted(written, transactionId, true);
            assertThat(owner.queryForMap("""
                    SELECT usuario.role, usuario.active, usuario.email_verificado, usuario.token_version,
                           taller.activo, suscripcion.plan, suscripcion.estado,
                           suscripcion.fecha_fin_trial - suscripcion.fecha_inicio AS trial_days
                      FROM users usuario JOIN talleres taller ON taller.id = usuario.taller_id
                      JOIN suscripciones suscripcion ON suscripcion.taller_id = taller.id
                     WHERE usuario.id = ? AND taller.id = ?
                    """, written.lot().actor().userId(), written.lot().actor().workshopId()))
                    .containsEntry("role", "ADMIN")
                    .containsEntry("active", true)
                    .containsEntry("email_verificado", false)
                    .containsEntry("token_version", 0L)
                    .containsEntry("activo", true)
                    .containsEntry("plan", "FREE")
                    .containsEntry("estado", "TRIAL")
                    .containsEntry("trial_days", 14);
        } finally {
            fixture.removeIdentityGuard();
        }
    }

    private static void assertCommitted(Written written, String transactionId, boolean registration) {
        var lot = written.lot();
        assertThat(owner.queryForObject("SELECT count(*) FROM legal_aceptacion_lotes WHERE id = ?",
                Long.class, lot.id())).isEqualTo(1L);
        assertThat(owner.queryForObject("SELECT count(*) FROM legal_aceptaciones WHERE lote_id = ?",
                Long.class, lot.id())).isEqualTo((long) written.acts().size());
        assertThat(owner.queryForObject("""
                SELECT count(*) FROM legal_aceptacion_documentos document
                  JOIN legal_aceptaciones act ON act.id = document.aceptacion_id WHERE act.lote_id = ?
                """, Long.class, lot.id())).isEqualTo((long) written.documents());
        assertThat(owner.queryForObject("SELECT count(*) FROM legal_aceptacion_metadatos WHERE lote_id = ?",
                Long.class, lot.id())).isEqualTo(1L);
        assertThat(owner.queryForObject("SELECT count(*) FROM legal_aceptacion_metadatos_cifrados WHERE lote_id = ?",
                Long.class, lot.id())).isEqualTo(1L);
        assertThat(owner.queryForObject("SELECT count(*) FROM legal_idempotencia_resultados WHERE lote_id = ?",
                Long.class, lot.id())).isEqualTo(1L);
        assertThat(owner.queryForObject("""
                SELECT metadata.capturado_en = lote.aceptado_en
                   AND resultado.completed_at = lote.aceptado_en
                   AND resultado.expires_at >= resultado.completed_at + INTERVAL '24 hours'
                   AND lote.user_id = resultado.user_id AND lote.taller_id = resultado.taller_id
                  FROM legal_aceptacion_lotes lote
                  JOIN legal_aceptacion_metadatos metadata ON metadata.lote_id = lote.id
                  JOIN legal_idempotencia_resultados resultado ON resultado.lote_id = lote.id
                 WHERE lote.id = ?
                """, Boolean.class, lot.id())).isTrue();
        assertThat(owner.queryForObject("SELECT aceptado_en FROM legal_aceptacion_lotes WHERE id = ?",
                OffsetDateTime.class, lot.id())).isEqualTo(lot.acceptedAt());
        for (String table : List.of("legal_aceptacion_lotes", "legal_aceptaciones",
                "legal_aceptacion_metadatos", "legal_aceptacion_metadatos_cifrados", "legal_idempotencia_resultados")) {
            String column = table.equals("legal_aceptacion_lotes") ? "id" : "lote_id";
            assertThat(owner.queryForList("SELECT xmin::text FROM " + table + " WHERE " + column + " = ?",
                    String.class, lot.id())).isNotEmpty().containsOnly(transactionId);
        }
        assertThat(owner.queryForList("""
                SELECT document.xmin::text FROM legal_aceptacion_documentos document
                  JOIN legal_aceptaciones act ON act.id = document.aceptacion_id WHERE act.lote_id = ?
                """, String.class, lot.id())).isNotEmpty().containsOnly(transactionId);
        if (registration) {
            assertThat(lot.aggregate().outcome()).isEqualTo(LegalRequiredSetAggregateReceipt.Outcome.CREATED);
            assertThat(owner.queryForList("""
                    SELECT xmin::text FROM legal_requisito_agregados WHERE id = ?
                    UNION ALL SELECT xmin::text FROM legal_requisito_agregado_scopes WHERE agregado_id = ?
                    UNION ALL SELECT xmin::text FROM users WHERE id = ?
                    UNION ALL SELECT xmin::text FROM talleres WHERE id = ?
                    UNION ALL SELECT xmin::text FROM suscripciones WHERE taller_id = ?
                    """, String.class, lot.aggregate().aggregateId(), lot.aggregate().aggregateId(),
                    lot.actor().userId(), lot.actor().workshopId(), lot.actor().workshopId()))
                    .hasSize(5).containsOnly(transactionId);
        }
    }

    private static String transactionId(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT pg_catalog.pg_current_xact_id()::text", String.class);
    }

    private static void assertSqlState(Throwable failure, String sqlState) {
        assertThat(sqlCause(failure).getSQLState()).isEqualTo(sqlState);
    }

    private static SQLException sqlCause(Throwable failure) {
        assertThat(failure).isNotNull();
        Throwable current = failure;
        while (current != null && !(current instanceof SQLException)) {
            current = current.getCause();
        }
        assertThat(current).isInstanceOf(SQLException.class);
        return (SQLException) current;
    }

    private static final class FixtureRollback extends RuntimeException {
        private FixtureRollback(Stage stage) {
            super("deliberate fixture rollback after " + stage);
        }
    }
}
