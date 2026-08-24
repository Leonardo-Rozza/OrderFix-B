package com.leonardorozza.mvgrreparacionesbackend;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class LegalConcurrencyIT {

    private static final int FUTURE_TIMEOUT_SECONDS = 10;
    private static final int LOCK_OBSERVATION_TIMEOUT_SECONDS = 5;
    private static final String SHA_256 = "a".repeat(64);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_concurrency")
            .withUsername("ordenfix")
            .withPassword("ordenfix");

    @BeforeAll
    static void aplicarMigraciones() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void insertDeHijoQueGanaHaceEsperarAlSelloYDejaElGrafoCompleto() throws Exception {
        DocumentoFixture fixture = crearPublicacionDocumental(1, false, false);
        String workerName = applicationName("seal_after_child");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<ResultadoSql> sealFuture = null;

        try (Connection childConnection = nuevaConexion(applicationName("child_wins"));
             Connection sealConnection = nuevaConexion(workerName)) {
            executeUpdate(childConnection, """
                    INSERT INTO legal_documento_contextos (documento_version_id, contexto)
                    VALUES (?, 'USO_CONTINUADO')
                    """, fixture.version(0));

            CountDownLatch started = new CountDownLatch(1);
            sealFuture = executor.submit(concurrentSql(sealConnection, started, () ->
                    executeUpdate(sealConnection, """
                            UPDATE legal_publicaciones
                               SET estado_construccion = 'SELLADO'
                             WHERE id = ?
                            """, fixture.publicacionId())));

            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertWaitingForLock(childConnection, workerName, sealFuture);

            childConnection.commit();
            ResultadoSql sealResult = sealFuture.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(sealResult).isEqualTo(ResultadoSql.success());
        } finally {
            shutdownExecutor(executor, sealFuture);
        }

        try (Connection verify = nuevaConexion(applicationName("verify_child_wins"))) {
            assertThat(queryString(verify, """
                    SELECT estado_construccion
                      FROM legal_publicaciones
                     WHERE id = ?
                    """, fixture.publicacionId())).isEqualTo("SELLADO");
            assertThat(queryInt(verify, """
                    SELECT count(*)
                      FROM legal_documento_contextos
                     WHERE documento_version_id = ?
                    """, fixture.version(0))).isOne();
        }
    }

    @Test
    void selloQueGanaHaceEsperarYRechazaElHijoTardio() throws Exception {
        DocumentoFixture fixture = crearPublicacionDocumental(1, true, false);
        String workerName = applicationName("late_child");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<ResultadoSql> childFuture = null;

        try (Connection sealConnection = nuevaConexion(applicationName("seal_wins"));
             Connection childConnection = nuevaConexion(workerName)) {
            executeUpdate(sealConnection, """
                    UPDATE legal_publicaciones
                       SET estado_construccion = 'SELLADO'
                     WHERE id = ?
                    """, fixture.publicacionId());

            CountDownLatch started = new CountDownLatch(1);
            childFuture = executor.submit(concurrentSql(childConnection, started, () ->
                    executeUpdate(childConnection, """
                            INSERT INTO legal_documento_contextos
                                (documento_version_id, contexto)
                            VALUES (?, 'REGISTRO')
                            """, fixture.version(0))));

            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertWaitingForLock(sealConnection, workerName, childFuture);

            sealConnection.commit();
            ResultadoSql childResult = childFuture.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(childResult.successful()).isFalse();
            assertThat(childResult.sqlState()).isEqualTo("23514");
            assertThat(childResult.message()).contains("ya esta sellada");
        } finally {
            shutdownExecutor(executor, childFuture);
        }

        try (Connection verify = nuevaConexion(applicationName("verify_seal_wins"))) {
            assertThat(queryString(verify, """
                    SELECT estado_construccion
                      FROM legal_publicaciones
                     WHERE id = ?
                    """, fixture.publicacionId())).isEqualTo("SELLADO");
            assertThat(queryInt(verify, """
                    SELECT count(*)
                      FROM legal_documento_contextos
                     WHERE documento_version_id = ?
                    """, fixture.version(0))).isOne();
        }
    }

    @Test
    void sellosDePublicacionesDistintasSeSerializanAntesDeTomarLocksDeFila() throws Exception {
        DocumentoFixture first = crearPublicacionDocumental(1, true, false);
        DocumentoFixture second = crearPublicacionDocumental(1, true, false);
        String workerName = applicationName("second_publication_seal");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<ResultadoSql> secondSeal = null;

        try (Connection firstConnection = nuevaConexion(applicationName("first_publication_seal"));
             Connection secondConnection = nuevaConexion(workerName)) {
            executeUpdate(firstConnection, """
                    UPDATE legal_publicaciones
                       SET estado_construccion = 'SELLADO'
                     WHERE id = ?
                    """, first.publicacionId());

            CountDownLatch started = new CountDownLatch(1);
            secondSeal = executor.submit(concurrentSql(secondConnection, started, () ->
                    executeUpdate(secondConnection, """
                            UPDATE legal_publicaciones
                               SET estado_construccion = 'SELLADO'
                             WHERE id = ?
                            """, second.publicacionId())));

            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertWaitingForLock(firstConnection, workerName, secondSeal);

            firstConnection.commit();
            assertThat(secondSeal.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .isEqualTo(ResultadoSql.success());
        } finally {
            shutdownExecutor(executor, secondSeal);
        }

        try (Connection verify = nuevaConexion(applicationName("verify_seal_barrier"))) {
            assertThat(queryInt(verify, """
                    SELECT count(*)
                      FROM legal_publicaciones
                     WHERE id IN (?, ?)
                       AND estado_construccion = 'SELLADO'
                    """, first.publicacionId(), second.publicacionId())).isEqualTo(2);
        }
    }

    @Test
    void protocoloDeEscrituraRechazaRepeatableReadAntesDeReleerEstadoBloqueado()
            throws Exception {
        DocumentoFixture openPublication = crearPublicacionDocumental(1, true, false);
        DocumentoFixture sealedPublication = crearPublicacionDocumental(1, true, true);
        UUID replacementBatchId = UUID.randomUUID();

        try (Connection setup = nuevaConexion(applicationName("rr_setup"))) {
            executeUpdate(setup, """
                    INSERT INTO legal_documento_reemplazo_lotes (id, creado_en)
                    VALUES (?, CURRENT_TIMESTAMP)
                    """, replacementBatchId);
            setup.commit();
        }

        ResultadoSql publicationResult;
        try (Connection connection = nuevaConexion(
                applicationName("rr_publication"), Connection.TRANSACTION_REPEATABLE_READ)) {
            publicationResult = ejecutarYConfirmar(connection, () -> executeUpdate(connection, """
                    UPDATE legal_publicaciones
                       SET estado_construccion = 'SELLADO'
                     WHERE id = ?
                    """, openPublication.publicacionId()));
        }

        ResultadoSql transitionResult;
        try (Connection connection = nuevaConexion(
                applicationName("rr_transition"), Connection.TRANSACTION_REPEATABLE_READ)) {
            transitionResult = ejecutarYConfirmar(connection, () -> insertTransition(
                    connection, sealedPublication.version(0), "BORRADOR", "PUBLICADA"));
        }

        ResultadoSql replacementResult;
        try (Connection connection = nuevaConexion(
                applicationName("rr_replacement"), Connection.TRANSACTION_REPEATABLE_READ)) {
            replacementResult = ejecutarYConfirmar(connection, () -> executeUpdate(connection, """
                    UPDATE legal_documento_reemplazo_lotes
                       SET estado_construccion = 'SELLADO'
                     WHERE id = ?
                    """, replacementBatchId));
        }

        assertThat(List.of(publicationResult, transitionResult, replacementResult))
                .allSatisfy(result -> {
                    assertThat(result.successful()).isFalse();
                    assertThat(result.sqlState()).isEqualTo("25001");
                    assertThat(result.message()).contains("READ COMMITTED");
                });

        try (Connection verify = nuevaConexion(applicationName("verify_rr_guard"))) {
            assertThat(queryString(verify, """
                    SELECT estado_construccion FROM legal_publicaciones WHERE id = ?
                    """, openPublication.publicacionId())).isEqualTo("ABIERTO");
            assertThat(queryString(verify, """
                    SELECT estado FROM legal_documento_versiones WHERE id = ?
                    """, sealedPublication.version(0))).isEqualTo("BORRADOR");
            assertThat(queryString(verify, """
                    SELECT estado_construccion
                      FROM legal_documento_reemplazo_lotes
                     WHERE id = ?
                    """, replacementBatchId)).isEqualTo("ABIERTO");
        }
    }

    @Test
    void dosTransicionesConcurrentesDeLaMismaVersionDejanUnSoloEvento() throws Exception {
        DocumentoFixture fixture = crearPublicacionDocumental(1, true, true);
        UUID versionId = fixture.version(0);
        String workerName = applicationName("same_transition");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<ResultadoSql> secondTransition = null;

        try (Connection firstConnection = nuevaConexion(applicationName("first_transition"));
             Connection secondConnection = nuevaConexion(workerName)) {
            insertTransition(firstConnection, versionId, "BORRADOR", "PUBLICADA");

            CountDownLatch started = new CountDownLatch(1);
            secondTransition = executor.submit(concurrentSql(secondConnection, started, () ->
                    insertTransition(secondConnection, versionId, "BORRADOR", "PUBLICADA")));

            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertWaitingForLock(firstConnection, workerName, secondTransition);

            firstConnection.commit();
            ResultadoSql secondResult = secondTransition.get(
                    FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(secondResult.successful()).isFalse();
            assertThat(secondResult.sqlState()).isEqualTo("40001");
            assertThat(secondResult.message()).contains("estado documental desactualizado");
        } finally {
            shutdownExecutor(executor, secondTransition);
        }

        try (Connection verify = nuevaConexion(applicationName("verify_same_transition"))) {
            assertThat(queryString(verify, """
                    SELECT estado
                      FROM legal_documento_versiones
                     WHERE id = ?
                    """, versionId)).isEqualTo("PUBLICADA");
            assertThat(queryInt(verify, """
                    SELECT count(*)
                      FROM legal_documento_transiciones
                     WHERE documento_version_id = ?
                       AND estado_anterior = 'BORRADOR'
                       AND estado_nuevo = 'PUBLICADA'
                    """, versionId)).isOne();
        }
    }

    @Test
    void ordinalSuperiorConcurrenteSerializaYRechazaElOrdinalInferiorTardio() throws Exception {
        DocumentoFixture fixture = crearPublicacionDocumental(2, true, true);
        UUID lowerVersionId = fixture.version(0);
        UUID higherVersionId = fixture.version(1);
        String workerName = applicationName("lower_ordinal");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<ResultadoSql> lowerTransition = null;

        try (Connection higherConnection = nuevaConexion(applicationName("higher_ordinal"));
             Connection lowerConnection = nuevaConexion(workerName)) {
            insertTransition(higherConnection, higherVersionId, "BORRADOR", "PUBLICADA");

            CountDownLatch started = new CountDownLatch(1);
            lowerTransition = executor.submit(concurrentSql(lowerConnection, started, () ->
                    insertTransition(lowerConnection, lowerVersionId, "BORRADOR", "PUBLICADA")));

            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertWaitingForLock(higherConnection, workerName, lowerTransition);

            higherConnection.commit();
            ResultadoSql lowerResult = lowerTransition.get(
                    FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(lowerResult.successful()).isFalse();
            assertThat(lowerResult.sqlState()).isEqualTo("23514");
            assertThat(lowerResult.message()).contains("no supera el maximo publicado");
        } finally {
            shutdownExecutor(executor, lowerTransition);
        }

        try (Connection verify = nuevaConexion(applicationName("verify_lineage"))) {
            assertThat(queryString(verify, """
                    SELECT estado FROM legal_documento_versiones WHERE id = ?
                    """, higherVersionId)).isEqualTo("PUBLICADA");
            assertThat(queryString(verify, """
                    SELECT estado FROM legal_documento_versiones WHERE id = ?
                    """, lowerVersionId)).isEqualTo("BORRADOR");
            assertThat(queryInt(verify, """
                    SELECT count(*)
                      FROM legal_documento_transiciones t
                      JOIN legal_documento_versiones v ON v.id = t.documento_version_id
                     WHERE v.documento_linea_id = ?
                       AND t.estado_nuevo = 'PUBLICADA'
                    """, fixture.lineaId())).isOne();
        }
    }

    @Test
    void dosLotesDeReemplazoSuperpuestosSoloPuedenSellarUno() throws Exception {
        ReemplazoFixture fixture = crearReemplazoSuperpuesto();
        String workerName = applicationName("replacement_loser");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<ResultadoSql> secondSeal = null;

        try (Connection firstConnection = nuevaConexion(applicationName("replacement_winner"));
             Connection secondConnection = nuevaConexion(workerName)) {
            executeUpdate(firstConnection, """
                    UPDATE legal_documento_reemplazo_lotes
                       SET estado_construccion = 'SELLADO'
                     WHERE id = ?
                    """, fixture.firstBatchId());

            CountDownLatch started = new CountDownLatch(1);
            secondSeal = executor.submit(concurrentSql(secondConnection, started, () ->
                    executeUpdate(secondConnection, """
                            UPDATE legal_documento_reemplazo_lotes
                               SET estado_construccion = 'SELLADO'
                             WHERE id = ?
                            """, fixture.secondBatchId())));

            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertWaitingForLock(firstConnection, workerName, secondSeal);

            firstConnection.commit();
            ResultadoSql secondResult = secondSeal.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(secondResult.successful()).isFalse();
            assertThat(secondResult.sqlState()).isEqualTo("23514");
            assertThat(secondResult.message()).contains("anteriores deben estar VIGENTE");
        } finally {
            shutdownExecutor(executor, secondSeal);
        }

        try (Connection verify = nuevaConexion(applicationName("verify_replacement"))) {
            assertThat(queryString(verify, """
                    SELECT estado_construccion
                      FROM legal_documento_reemplazo_lotes
                     WHERE id = ?
                    """, fixture.firstBatchId())).isEqualTo("SELLADO");
            assertThat(queryString(verify, """
                    SELECT estado_construccion
                      FROM legal_documento_reemplazo_lotes
                     WHERE id = ?
                    """, fixture.secondBatchId())).isEqualTo("ABIERTO");
            assertThat(queryString(verify, """
                    SELECT estado FROM legal_documento_versiones WHERE id = ?
                    """, fixture.previousVersionId())).isEqualTo("REEMPLAZADA");
            assertThat(queryString(verify, """
                    SELECT estado FROM legal_documento_versiones WHERE id = ?
                    """, fixture.successorVersionId())).isEqualTo("VIGENTE");
            assertThat(queryInt(verify, """
                    SELECT count(*)
                      FROM legal_documento_transiciones
                     WHERE reemplazo_lote_id = ?
                    """, fixture.firstBatchId())).isEqualTo(2);
            assertThat(queryInt(verify, """
                    SELECT count(*)
                      FROM legal_documento_transiciones
                     WHERE reemplazo_lote_id = ?
                    """, fixture.secondBatchId())).isZero();
            assertThat(queryString(verify, """
                    SELECT documento_version_id::text
                      FROM legal_documento_vigentes
                     WHERE tipo = 'TERMINOS_SERVICIO'
                       AND locale = 'es-AR'
                       AND contexto = 'USO_CONTINUADO'
                    """)).isEqualTo(fixture.successorVersionId().toString());
        }
    }

    private DocumentoFixture crearPublicacionDocumental(
            int versionCount, boolean includeContexts, boolean seal) throws SQLException {
        UUID publicationId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        List<UUID> versionIds = new ArrayList<>(versionCount);

        try (Connection connection = nuevaConexion(applicationName("document_fixture"))) {
            try {
                executeUpdate(connection, """
                        INSERT INTO legal_publicaciones (
                            id, publication_external_id, schema_version, locale,
                            manifest_sha256, manifest_canonico, razon_social, cuit,
                            domicilio_legal, jurisdiccion, horario_atencion,
                            email_legal, email_privacidad, email_soporte,
                            revision_legal_estado, revision_contable_estado, importado_en)
                        VALUES (?, ?, 1, 'es-AR', ?, '{}', 'OrdenFix Test', '20304050607',
                                'Calle de prueba 123', 'CABA', 'Lunes a viernes',
                                'legal@test.com', 'privacidad@test.com', 'soporte@test.com',
                                'PENDIENTE', 'PENDIENTE', CURRENT_TIMESTAMP)
                        """, publicationId, "publication-" + publicationId, SHA_256);
                executeUpdate(connection, """
                        INSERT INTO legal_documento_lineas (
                            id, clave, tipo, locale, publicacion_intro_id, creado_en)
                        VALUES (?, ?, 'TERMINOS_SERVICIO', 'es-AR', ?, CURRENT_TIMESTAMP)
                        """, lineId, "document-" + lineId, publicationId);

                for (int index = 0; index < versionCount; index++) {
                    UUID versionId = UUID.randomUUID();
                    versionIds.add(versionId);
                    executeUpdate(connection, """
                            INSERT INTO legal_documento_versiones (
                                id, documento_linea_id, publicacion_intro_id, version,
                                lineage_ordinal, titulo, contenido_markdown, sha256,
                                vigente_desde, requires_reacceptance)
                            VALUES (?, ?, ?, ?, ?, ?, '# contenido', ?,
                                    CURRENT_TIMESTAMP - INTERVAL '1 hour', FALSE)
                            """, versionId, lineId, publicationId, "v" + (index + 1),
                            index + 1, "Documento " + (index + 1), SHA_256);
                    executeUpdate(connection, """
                            INSERT INTO legal_publicacion_documentos (
                                publicacion_id, documento_version_id, manifest_ordinal)
                            VALUES (?, ?, ?)
                            """, publicationId, versionId, index + 1);
                    if (includeContexts) {
                        executeUpdate(connection, """
                                INSERT INTO legal_documento_contextos
                                    (documento_version_id, contexto)
                                VALUES (?, 'USO_CONTINUADO')
                                """, versionId);
                    }
                }

                if (seal) {
                    executeUpdate(connection, """
                            UPDATE legal_publicaciones
                               SET estado_construccion = 'SELLADO'
                             WHERE id = ?
                            """, publicationId);
                }
                connection.commit();
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        }

        return new DocumentoFixture(publicationId, lineId, List.copyOf(versionIds));
    }

    private ReemplazoFixture crearReemplazoSuperpuesto() throws SQLException {
        DocumentoFixture document = crearPublicacionDocumental(2, true, true);
        UUID previousVersionId = document.version(0);
        UUID successorVersionId = document.version(1);

        publish(previousVersionId);
        makeCurrent(document, previousVersionId);
        publish(successorVersionId);

        UUID firstBatchId = UUID.randomUUID();
        UUID secondBatchId = UUID.randomUUID();
        try (Connection connection = nuevaConexion(applicationName("replacement_fixture"))) {
            try {
                for (UUID batchId : List.of(firstBatchId, secondBatchId)) {
                    executeUpdate(connection, """
                            INSERT INTO legal_documento_reemplazo_lotes (id, creado_en)
                            VALUES (?, CURRENT_TIMESTAMP)
                            """, batchId);
                    executeUpdate(connection, """
                            INSERT INTO legal_documento_reemplazo_anteriores
                                (lote_id, documento_version_id)
                            VALUES (?, ?)
                            """, batchId, previousVersionId);
                    executeUpdate(connection, """
                            INSERT INTO legal_documento_reemplazo_sucesoras
                                (lote_id, documento_version_id, publicacion_id)
                            VALUES (?, ?, ?)
                            """, batchId, successorVersionId, document.publicacionId());
                }
                connection.commit();
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        }

        return new ReemplazoFixture(
                firstBatchId, secondBatchId, previousVersionId, successorVersionId);
    }

    private void publish(UUID versionId) throws SQLException {
        try (Connection connection = nuevaConexion(applicationName("publish"))) {
            try {
                insertTransition(connection, versionId, "BORRADOR", "PUBLICADA");
                connection.commit();
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        }
    }

    private void makeCurrent(DocumentoFixture document, UUID versionId) throws SQLException {
        try (Connection connection = nuevaConexion(applicationName("make_current"))) {
            try {
                insertTransition(connection, versionId, "PUBLICADA", "VIGENTE");
                executeUpdate(connection, """
                        INSERT INTO legal_documento_vigentes (
                            tipo, locale, contexto, documento_version_id,
                            documento_linea_id, publicacion_id)
                        VALUES ('TERMINOS_SERVICIO', 'es-AR', 'USO_CONTINUADO', ?, ?, ?)
                        """, versionId, document.lineaId(), document.publicacionId());
                connection.commit();
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        }
    }

    private static void insertTransition(
            Connection connection, UUID versionId, String previousState, String nextState)
            throws SQLException {
        executeUpdate(connection, """
                INSERT INTO legal_documento_transiciones (
                    documento_version_id, estado_anterior, estado_nuevo,
                    motivo, reemplazo_lote_id, ocurrido_en)
                VALUES (?, ?, ?, NULL, NULL, CURRENT_TIMESTAMP)
                """, versionId, previousState, nextState);
    }

    private static Connection nuevaConexion(String applicationName) throws SQLException {
        return nuevaConexion(applicationName, Connection.TRANSACTION_READ_COMMITTED);
    }

    private static Connection nuevaConexion(String applicationName, int isolationLevel)
            throws SQLException {
        Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        connection.setAutoCommit(false);
        connection.setTransactionIsolation(isolationLevel);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('application_name', ?, false)")) {
            statement.setString(1, applicationName);
            statement.execute();
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL lock_timeout = '5s'");
            statement.execute("SET LOCAL statement_timeout = '10s'");
        }
        return connection;
    }

    private static ResultadoSql ejecutarYConfirmar(Connection connection, SqlOperation operation) {
        try {
            operation.execute();
            connection.commit();
            return ResultadoSql.success();
        } catch (SQLException exception) {
            rollbackQuietly(connection);
            return ResultadoSql.failure(exception);
        }
    }

    private static Callable<ResultadoSql> concurrentSql(
            Connection connection, CountDownLatch started, SqlOperation operation) {
        return () -> {
            started.countDown();
            try {
                operation.execute();
                connection.commit();
                return ResultadoSql.success();
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                return ResultadoSql.failure(exception);
            }
        };
    }

    private static void assertWaitingForLock(
            Connection observer, String applicationName, Future<?> future) throws Exception {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(LOCK_OBSERVATION_TIMEOUT_SECONDS);
        String lastState = null;
        String lastWaitEvent = null;

        while (System.nanoTime() < deadline) {
            try (PreparedStatement statement = observer.prepareStatement("""
                    SELECT state, wait_event_type
                      FROM pg_stat_activity
                     WHERE application_name = ?
                       AND pid <> pg_backend_pid()
                    """)) {
                statement.setString(1, applicationName);
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next()) {
                        lastState = rows.getString("state");
                        lastWaitEvent = rows.getString("wait_event_type");
                        if ("Lock".equals(lastWaitEvent)) {
                            assertThat(future.isDone()).isFalse();
                            return;
                        }
                    }
                }
            }

            if (future.isDone()) {
                throw new AssertionError("La operación concurrente terminó antes de esperar el lock: "
                        + future.get());
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }

        throw new AssertionError("No se observó espera de lock para " + applicationName
                + "; último state=" + lastState + ", wait_event_type=" + lastWaitEvent);
    }

    private static int executeUpdate(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            return statement.executeUpdate();
        }
    }

    private static String queryString(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }

    private static int queryInt(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getInt(1);
            }
        }
    }

    private static void shutdownExecutor(ExecutorService executor, Future<?> future)
            throws InterruptedException {
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            if (!connection.isClosed() && !connection.getAutoCommit()) {
                connection.rollback();
            }
        } catch (SQLException ignored) {
            // El cierre de la conexión abortará cualquier transacción que no haya podido revertirse.
        }
    }

    private static String applicationName(String purpose) {
        return "legal_it_" + purpose + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    @FunctionalInterface
    private interface SqlOperation {
        void execute() throws SQLException;
    }

    private record DocumentoFixture(
            UUID publicacionId, UUID lineaId, List<UUID> versionIds) {
        private UUID version(int index) {
            return versionIds.get(index);
        }
    }

    private record ReemplazoFixture(
            UUID firstBatchId,
            UUID secondBatchId,
            UUID previousVersionId,
            UUID successorVersionId) {
    }

    private record ResultadoSql(boolean successful, String sqlState, String message) {
        private static ResultadoSql success() {
            return new ResultadoSql(true, null, null);
        }

        private static ResultadoSql failure(SQLException exception) {
            return new ResultadoSql(false, exception.getSQLState(), exception.getMessage());
        }
    }
}
