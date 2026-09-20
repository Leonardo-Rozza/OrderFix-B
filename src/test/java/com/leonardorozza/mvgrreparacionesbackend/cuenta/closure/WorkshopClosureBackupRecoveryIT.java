package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.recovery.RecoveryCheckpoint;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.recovery.RecoveryCheckpoint.Surface;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.recovery.RecoveryCheckpointComparison;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.recovery.RecoveryCheckpointFiles;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.recovery.RecoverySnapshotReader;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.recovery.RecoveryReadSchemaPreflight;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosureBackupCheck.*;
import static org.assertj.core.api.Assertions.*;

/** Real logical backup, synthetic data only. No application, HTTP, workers or provider ports are started. */
@Testcontainers
@Timeout(120)
class WorkshopClosureBackupRecoveryIT {
    @Container static final PostgreSQLContainer SOURCE = postgres("backup_source");
    @Container static final PostgreSQLContainer TARGET = postgres("backup_target");
    @TempDir Path temporary;
    Database source;
    Database target;

    @BeforeEach void databases() throws Exception {
        assertLoopback(SOURCE);
        assertLoopback(TARGET);
        source = freshDatabase(SOURCE);
        target = freshDatabase(TARGET);
        Flyway.configure().dataSource(source.jdbc().getDataSource())
                .locations("classpath:db/migration").load().migrate();
        assertThat(target.jdbc().queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public'"))
                .as("the recovery destination has not been migrated").isEmpty();
    }

    @Test void backupBeforeClosureRecoversOldAccessPayloadsAndMissingEffects() throws Exception {
        Actor ready = actor();
        Actor queued = actor();
        Actor untouched = actor();
        UUID readyJob = export(ready, "READY");
        UUID queuedJob = export(queued, "QUEUED");
        var control = controlRows(source, untouched);
        Backup backup = dump();

        Evidence first = transition(ready, UUID.randomUUID(), false);
        Evidence second = transition(queued, UUID.randomUUID(), false);
        assertEpochs(source, ready, 1);
        assertEpochs(source, queued, 1);
        assertThat(exportState(source, readyJob)).isEqualTo("READY");
        assertThat(exportState(source, queuedJob)).isEqualTo("REVOKED");
        assertThat(source.jdbc().queryForObject("SELECT snapshot_cipher IS NULL FROM cuenta_exportaciones WHERE id=?",
                Boolean.class, queuedJob)).isTrue();
        assertThat(effectTypes(source)).containsExactly("AVISO_CIERRE", "AVISO_CIERRE");
        assertThat(controlRows(source, untouched)).isEqualTo(control);
        UUID environment = UUID.randomUUID();
        var external = captureWithoutWrites(source, environment);

        recover(backup);
        assertThat(workshopState(target, ready)).isEqualTo("ABIERTO");
        assertThat(workshopState(target, queued)).isEqualTo("ABIERTO");
        assertEpochs(target, ready, 0);
        assertEpochs(target, queued, 0);
        assertThat(exportState(target, readyJob)).isEqualTo("READY");
        assertThat(exportState(target, queuedJob)).isEqualTo("QUEUED");
        assertThat(target.jdbc().queryForObject("SELECT octet_length(snapshot_cipher) FROM cuenta_exportaciones WHERE id=?",
                Integer.class, queuedJob)).isEqualTo(32);
        assertThat(effectTypes(target)).isEmpty();
        assertThat(target.jdbc().queryForObject("SELECT count(*) FROM cuenta_cierre_operaciones", Integer.class)).isZero();
        assertThat(controlRows(target, untouched)).isEqualTo(control);
        Report report = compareWithoutWrites(List.of(first, second));
        assertThat(report.status()).isEqualTo(Status.DIVERGENCIAS);
        assertThat(report.findings()).containsExactly(
                new Finding(ready.workshop(), Issue.DATABASE_BEHIND),
                new Finding(queued.workshop(), Issue.DATABASE_BEHIND));
        var complete = compareCheckpointWithoutWrites(external, environment);
        assertChangedSurfaces(complete, ready.workshop(), Surface.WORKSHOP, Surface.USERS, Surface.CLOSURES, Surface.OPERATIONS);
        assertChangedSurfaces(complete, queued.workshop(), Surface.WORKSHOP, Surface.USERS, Surface.CLOSURES, Surface.OPERATIONS);
        assertThat(complete.findings()).hasSize(8);
    }

    @Test void backupBeforeRestoringAccessRecoversRestrictionOldEpochsAndPreviouslyRevokedArchive() throws Exception {
        Actor actor = actor();
        Actor untouched = actor();
        UUID job = export(actor, "READY");
        UUID reference = UUID.randomUUID();
        transition(actor, reference, false);
        var originalExport = source.jdbc().queryForMap("SELECT * FROM cuenta_exportaciones WHERE id=?", job);
        Backup backup = dump();

        Evidence current = transition(actor, reference, true);
        assertThat(workshopState(source, actor)).isEqualTo("ABIERTO");
        assertEpochs(source, actor, 2);
        assertThat(exportState(source, job)).isEqualTo("REVOKED");
        assertThat(source.jdbc().queryForObject("SELECT archive_cipher IS NULL FROM cuenta_exportaciones WHERE id=?",
                Boolean.class, job)).isTrue();
        assertThat(effectTypes(source)).containsExactly("AVISO_CIERRE", "AVISO_RESTAURACION");
        UUID environment = UUID.randomUUID();
        var external = captureWithoutWrites(source, environment);

        recover(backup);
        assertThat(workshopState(target, actor)).isEqualTo("RESTRINGIDO");
        assertEpochs(target, actor, 1);
        assertThat(exportState(target, job)).isEqualTo("READY");
        assertThat(target.jdbc().queryForObject("SELECT expira_en FROM cuenta_exportaciones WHERE id=?",
                Object.class, job)).isEqualTo(originalExport.get("expira_en"));
        assertThat(target.jdbc().queryForObject("SELECT archive_cipher FROM cuenta_exportaciones WHERE id=?",
                byte[].class, job)).containsExactly((byte[]) originalExport.get("archive_cipher"));
        assertThat(effectTypes(target)).containsExactly("AVISO_CIERRE");
        assertThat(controlRows(target, untouched)).isEqualTo(controlRows(source, untouched));
        Report report = compareWithoutWrites(List.of(current));
        assertThat(report.status()).isEqualTo(Status.DIVERGENCIAS);
        assertThat(report.findings()).containsExactly(new Finding(actor.workshop(), Issue.DATABASE_BEHIND),
                new Finding(actor.workshop(), Issue.OWNER_EPOCH_BEHIND));
        var complete = compareCheckpointWithoutWrites(external, environment);
        assertChangedSurfaces(complete, actor.workshop(), Surface.WORKSHOP, Surface.USERS, Surface.CLOSURES, Surface.OPERATIONS);
        assertThat(complete.findings()).hasSize(4);

        // The restored schema must enforce the closure guard, not just contain its name.
        assertThatThrownBy(() -> target.jdbc().update(
                "INSERT INTO clientes(nombre,apellido,taller_id) VALUES('Synthetic','Blocked',?)", actor.workshop()))
                .isInstanceOfSatisfying(DataAccessException.class, failure -> {
                    assertThat(failure.getMostSpecificCause()).isInstanceOf(SQLException.class);
                    assertThat(((SQLException) failure.getMostSpecificCause()).getSQLState()).isEqualTo("P0033");
                });
        assertThat(rows(target, false)).isEqualTo(backup.rows());
    }

    @Test void matchingBackupNeverAuthorizesReopeningOrProvesTheEvidenceListIsComplete() throws Exception {
        Actor restricted = actor();
        Actor restored = actor();
        Evidence first = transition(restricted, UUID.randomUUID(), false);
        UUID reference = UUID.randomUUID();
        transition(restored, reference, false);
        Evidence second = transition(restored, reference, true);
        Backup backup = dump();
        recover(backup);

        Report matching = compareWithoutWrites(List.of(first, second));
        assertThat(matching.status()).isEqualTo(Status.COMPARACION_COMPATIBLE);
        assertThat(matching.checked()).isEqualTo(2);
        assertThat(matching.findings()).isEmpty();
        assertEpochs(target, restricted, 1);
        assertEpochs(target, restored, 2);
        assertThat(effectTypes(target)).containsExactly("AVISO_CIERRE", "AVISO_CIERRE", "AVISO_RESTAURACION");

        Actor createdAfterBackup = actor();
        Evidence missing = transition(createdAfterBackup, UUID.randomUUID(), false);
        assertThat(compareWithoutWrites(List.of(first, second)).status()).isEqualTo(Status.COMPARACION_COMPATIBLE);
        Report completeFixture = compareWithoutWrites(List.of(first, second, missing));
        assertThat(completeFixture.status()).isEqualTo(Status.DIVERGENCIAS);
        assertThat(completeFixture.findings()).containsExactly(
                new Finding(createdAfterBackup.workshop(), Issue.DATABASE_MISSING));
    }

    @Test void truncatedBackupCannotProduceAUsableDatabaseOrACompatibleReport() throws Exception {
        Evidence evidence = transition(actor(), UUID.randomUUID(), false);
        Backup backup = dump();
        byte[] original = Files.readAllBytes(backup.file());
        Path truncated = temporary.resolve("truncated.dump");
        Files.write(truncated, Arrays.copyOf(original, original.length / 2));
        Files.setPosixFilePermissions(truncated, PosixFilePermissions.fromString("rw-------"));
        String remote = copyToTarget(truncated);
        var failure = restoreCommand(remote);
        assertThat(failure.getExitCode()).as("a truncated archive must fail restoration").isNotZero();
        assertThat(target.jdbc().queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public'")).isEmpty();
        assertThatThrownBy(() -> checker().compare(List.of(evidence)))
                .isInstanceOfSatisfying(Rejected.class, error -> assertThat(error.code()).isEqualTo(Rejected.Code.UNAVAILABLE));
    }

    @Test void restoredOperationalRowsAreDetectedDespiteIdenticalClosureMetadataAndOwnerEpoch() throws Exception {
        Actor actor = actor();
        source.jdbc().update("INSERT INTO articulos(taller_id,nombre) SELECT ?,'Synthetic article' FROM generate_series(1,26)", actor.workshop());
        UUID reference = UUID.randomUUID();
        Evidence metadata = transition(actor, reference, false, Instant.now().minus(8, ChronoUnit.DAYS));
        Backup backup = dump();
        var deletion = new WorkshopOperationalDeletionService(source.jdbc(), source.manager(), true);
        var batch = deletion.deleteBatch(actor.workshop(), reference, UUID.randomUUID(), WorkshopOperationalDeletionService.Category.ARTICULOS);
        assertThat(batch.deleted()).isEqualTo(25);
        assertThat(source.jdbc().queryForObject("SELECT count(*) FROM articulos WHERE taller_id=?", Long.class, actor.workshop())).isEqualTo(1);
        UUID environment = UUID.randomUUID();
        var external = captureWithoutWrites(source, environment);

        recover(backup);
        assertThat(target.jdbc().queryForObject("SELECT count(*) FROM articulos WHERE taller_id=?", Long.class, actor.workshop())).isEqualTo(26);
        assertThat(target.jdbc().queryForObject("SELECT count(*) FROM cuenta_borrado_lotes", Long.class)).isZero();
        assertThat(compareWithoutWrites(List.of(metadata)).status()).isEqualTo(Status.COMPARACION_COMPATIBLE);
        var report = compareCheckpointWithoutWrites(external, environment);
        assertChangedSurfaces(report, actor.workshop(), Surface.ARTICULOS, Surface.DELETION_BATCHES);
        assertThat(report.findings()).hasSize(2);
    }

    @ParameterizedTest(name="read profile rejects {0}")
    @ValueSource(strings={"TRIGGER", "FUNCTION", "DEFAULT", "FOREIGN_KEY", "ACL", "INHERITANCE", "CHECK"})
    void recoveryCaptureRejectsChangedSchemaWithoutChangingRows(String change) {
        Actor actor = actor(); operationalRows(actor);
        if ("CHECK".equals(change)) source.jdbc().update("UPDATE equipos SET tipo='OTRO' WHERE taller_id=?", actor.workshop());
        UUID environment = UUID.randomUUID();
        captureWithoutWrites(source, environment);
        String baseline = recoveryReadFingerprint(source);
        var before = rows(source, true);
        List<String> statements = switch (change) {
            case "TRIGGER" -> List.of("ALTER TABLE public.articulos DISABLE TRIGGER aa_cuenta_borrado_v37");
            case "FUNCTION" -> List.of("""
                    CREATE OR REPLACE FUNCTION public.cuenta_borrado_contexto_vacio_v37() RETURNS TRIGGER
                    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
                    BEGIN RETURN NULL; END;
                    $$
                    """);
            case "DEFAULT" -> List.of("ALTER TABLE public.equipos ALTER COLUMN tipo SET DEFAULT 'CELULAR'");
            case "FOREIGN_KEY" -> List.of("ALTER TABLE public.presupuesto_items DROP CONSTRAINT fk_presupuesto_items_cierre_taller");
            case "ACL" -> List.of("GRANT SELECT ON public.cuenta_borrado_lotes TO PUBLIC");
            case "INHERITANCE" -> List.of("CREATE SCHEMA external_recovery_child",
                    "CREATE TABLE external_recovery_child.equipos_child () INHERITS(public.equipos)");
            case "CHECK" -> List.of("ALTER TABLE public.equipos DROP CONSTRAINT ck_equipos_tipo",
                    "ALTER TABLE public.equipos ADD CONSTRAINT ck_equipos_tipo CHECK (tipo IN ('CELULAR','NOTEBOOK','CONSOLA','PC_ESCRITORIO','MONITOR','OTRO'))");
            default -> throw new AssertionError(change);
        };
        // Deliberate schema corruption in this test's fresh database; no business writes follow it.
        statements.forEach(source.jdbc()::execute);
        assertThat(recoveryReadFingerprint(source)).isNotEqualTo(baseline);
        assertThatThrownBy(() -> new RecoverySnapshotReader(source.jdbc(), source.manager()).capture(environment, UUID.randomUUID()))
                .isInstanceOfSatisfying(RecoverySnapshotReader.Rejected.class,
                        failure -> assertThat(failure.code()).isEqualTo(RecoverySnapshotReader.Rejected.Code.UNAVAILABLE))
                .hasNoCause();
        assertThat(rows(source, true)).as("rejecting schema drift cannot change business or evidence rows").isEqualTo(before);
    }

    @Test void portableReadProfilesSurviveLogicalRestore() throws Exception {
        Actor actor = actor(); operationalRows(actor);
        String migratedFingerprint = "a9424a71510dc7b894b78a93fdebf81937c33998f996d894e076a1a609f9e964";
        String restoredFingerprint = "48f45d690c48d3422f415c00c2ffd6facd4fd5457626d618473518b98ce1e428";
        assertThat(recoveryReadFingerprint(source)).isEqualTo(migratedFingerprint);
        String migratedCatalog = recoveryReadCatalog(source);
        Backup backup = dump(); recover(backup);
        assertThat(recoveryReadFingerprint(target)).isEqualTo(restoredFingerprint);
        String restoredCatalog = recoveryReadCatalog(target);
        var migratedNormalized = normalizeVarcharArrayCastsForComparison(migratedCatalog);
        var restoredNormalized = normalizeVarcharArrayCastsForComparison(restoredCatalog);
        assertThat(migratedNormalized.replacements()).isEqualTo(52);
        assertThat(restoredNormalized.replacements()).isZero();
        assertThat(restoredNormalized.catalog()).as("only the exact varchar array cast syntax changes on restore")
                .isEqualTo(migratedNormalized.catalog());

        Database twiceRestored = restoreAgain(target);
        assertThat(recoveryReadFingerprint(twiceRestored)).as("a subsequent physical rebuild retains the approved restored profile")
                .isEqualTo(restoredFingerprint);
        assertThat(recoveryReadCatalog(twiceRestored)).isEqualTo(restoredCatalog);
        UUID environment = UUID.randomUUID();
        var initial = captureWithoutWrites(source, environment);
        assertThat(RecoveryCheckpointComparison.compare(initial, captureWithoutWrites(target, environment)).status())
                .isEqualTo(RecoveryCheckpointComparison.Status.MATCH);
        assertThat(RecoveryCheckpointComparison.compare(initial, captureWithoutWrites(twiceRestored, environment)).status())
                .isEqualTo(RecoveryCheckpointComparison.Status.MATCH);
    }

    /** Test-only explanation of the two exact accepted hashes; production never rewrites catalog text. */
    private static NormalizedCatalog normalizeVarcharArrayCastsForComparison(String catalog) {
        var arrays = Pattern.compile("\\(ARRAY\\[((?:'(?:[^']|'')*'::character varying)(?:, '(?:[^']|'')*'::character varying)*)\\]\\)::text\\[\\]").matcher(catalog);
        var literal = Pattern.compile("'(?:[^']|'')*'::character varying");
        StringBuilder normalized = new StringBuilder();
        int replacements = 0;
        while (arrays.find()) {
            String elements = literal.matcher(arrays.group(1)).results()
                    .map(match -> "(" + match.group() + ")::text").collect(java.util.stream.Collectors.joining(", "));
            arrays.appendReplacement(normalized, java.util.regex.Matcher.quoteReplacement("ARRAY[" + elements + "]"));
            replacements++;
        }
        arrays.appendTail(normalized);
        return new NormalizedCatalog(normalized.toString(), replacements);
    }

    private record NormalizedCatalog(String catalog, int replacements) { }

    private static String recoveryReadFingerprint(Database database) {
        return readRecoverySchema(database, RecoveryReadSchemaPreflight::fingerprint);
    }

    private static String recoveryReadCatalog(Database database) {
        return readRecoverySchema(database, jdbc -> {
            try {
                var sql = RecoveryReadSchemaPreflight.class.getDeclaredField("SQL");
                sql.setAccessible(true);
                return jdbc.queryForObject((String) sql.get(null), String.class);
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        });
    }

    private static String readRecoverySchema(Database database, java.util.function.Function<JdbcTemplate, String> read) {
        var tx = new TransactionTemplate(database.manager());
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setReadOnly(true);
        return tx.execute(status -> {
            var jdbc = database.jdbc();
            jdbc.execute("SET TRANSACTION READ ONLY");
            jdbc.execute("SET LOCAL search_path=pg_catalog, public, pg_temp");
            jdbc.execute("SET LOCAL row_security=off");
            jdbc.execute("SET LOCAL statement_timeout='5s'");
            jdbc.execute("SET LOCAL lock_timeout='2s'");
            jdbc.execute("SET LOCAL TIME ZONE 'UTC'");
            jdbc.execute("SET LOCAL DateStyle='ISO, YMD'");
            jdbc.execute("SET LOCAL IntervalStyle='postgres'");
            jdbc.execute("SET LOCAL bytea_output='hex'");
            jdbc.execute("SET LOCAL extra_float_digits=3");
            return read.apply(jdbc);
        });
    }

    @Test void aMatchingLogicalCheckpointSurvivesPhysicalRowVersionsAndRealRestore() throws Exception {
        Actor open = actor(); operationalRows(open);
        Actor restricted = actor(); transition(restricted, UUID.randomUUID(), false);
        Actor restored = actor(); UUID reference = UUID.randomUUID();
        transition(restored, reference, false); transition(restored, reference, true);
        UUID environment = UUID.randomUUID();
        var external = captureWithoutWrites(source, environment);
        var logical = rows(source, false);
        var files = new RecoveryCheckpointFiles();
        byte[] key = new byte[32]; Arrays.fill(key, (byte) 42);
        Path checkpointFile = temporary.resolve("synthetic-recovery-checkpoint");
        var receipt = files.write(checkpointFile, external, key);
        byte[] checkpointBytes = Files.readAllBytes(checkpointFile);
        String oldTid = source.jdbc().queryForObject("SELECT ctid::text FROM articulos WHERE taller_id=?", String.class, open.workshop());
        source.jdbc().update("UPDATE articulos SET nombre=nombre WHERE taller_id=?", open.workshop());
        assertThat(source.jdbc().queryForObject("SELECT ctid::text FROM articulos WHERE taller_id=?", String.class, open.workshop())).isNotEqualTo(oldTid);
        assertThat(rows(source, false)).isEqualTo(logical);
        Backup backup = dump(); recover(backup);
        var authenticated = files.read(checkpointFile, key, receipt);
        assertThat(authenticated).isEqualTo(external);
        var report = compareCheckpointWithoutWrites(authenticated, environment);
        assertThat(report.status()).isEqualTo(RecoveryCheckpointComparison.Status.MATCH);
        assertThat(report.findings()).isEmpty();
        assertThat(report.notice()).isEqualTo("NO_AUTORIZA_REAPERTURA");
        assertThat(target.jdbc().queryForObject("SELECT count(*) FROM presupuesto_items WHERE taller_id=?", Long.class, open.workshop())).isEqualTo(2);
        assertThat(rows(target, true)).as("physical transaction versions are not portable evidence").isNotEqualTo(rows(source, true));
        assertThat(Files.readAllBytes(checkpointFile)).containsExactly(checkpointBytes);
    }

    @Test void employeeEpochRollbackCannotHideBehindAnUnchangedOwnerEpoch() throws Exception {
        Actor actor = actor();
        Evidence metadata = transition(actor, UUID.randomUUID(), false);
        Backup backup = dump();
        assertThat(source.jdbc().update("UPDATE users SET token_version=token_version+1 WHERE taller_id=? AND role='USER' AND active", actor.workshop())).isEqualTo(1);
        assertThat(source.jdbc().queryForObject("SELECT token_version FROM users WHERE id=?", Long.class, actor.owner())).isEqualTo(metadata.ownerEpoch());
        UUID environment = UUID.randomUUID();
        var external = captureWithoutWrites(source, environment);
        recover(backup);
        assertThat(compareWithoutWrites(List.of(metadata)).status()).isEqualTo(Status.COMPARACION_COMPATIBLE);
        var report = compareCheckpointWithoutWrites(external, environment);
        assertChangedSurfaces(report, actor.workshop(), Surface.USERS);
        assertThat(report.findings()).hasSize(1);
    }

    @Test void fullCheckpointFindsAWorkshopOmittedByAPartialCompatibleEvidenceList() throws Exception {
        Actor first = actor(); Evidence metadata = transition(first, UUID.randomUUID(), false);
        Backup backup = dump();
        Actor missing = actor(); transition(missing, UUID.randomUUID(), false);
        UUID environment = UUID.randomUUID();
        var external = captureWithoutWrites(source, environment);
        recover(backup);
        assertThat(compareWithoutWrites(List.of(metadata)).status()).isEqualTo(Status.COMPARACION_COMPATIBLE);
        var report = compareCheckpointWithoutWrites(external, environment);
        assertThat(report.status()).isEqualTo(RecoveryCheckpointComparison.Status.DIFFERENCES);
        assertThat(report.findings()).containsExactly(new RecoveryCheckpointComparison.Finding(missing.workshop(), null,
                RecoveryCheckpointComparison.Issue.MISSING_WORKSHOP));
    }

    @Test void fullCheckpointAlsoDetectsAnUnexpectedWorkshopInTheRecoveredDatabase() throws Exception {
        Actor first = actor(); transition(first, UUID.randomUUID(), false);
        UUID environment = UUID.randomUUID();
        var external = captureWithoutWrites(source, environment);
        Actor extra = actor();
        Backup backup = dump(); recover(backup);
        var report = compareCheckpointWithoutWrites(external, environment);
        assertThat(report.status()).isEqualTo(RecoveryCheckpointComparison.Status.DIFFERENCES);
        assertThat(report.findings()).containsExactly(new RecoveryCheckpointComparison.Finding(extra.workshop(), null,
                RecoveryCheckpointComparison.Issue.UNEXPECTED_WORKSHOP));
    }

    @Test void equalRowCountsAndDistinctItemSetsDoNotHideChangedOperationalContent() throws Exception {
        Actor actor = actor(); operationalRows(actor);
        long article = source.jdbc().queryForObject("SELECT id FROM articulos WHERE taller_id=?", Long.class, actor.workshop());
        long budget = source.jdbc().queryForObject("SELECT id FROM presupuestos WHERE taller_id=?", Long.class, actor.workshop());
        source.jdbc().update("INSERT INTO presupuesto_items(presupuesto_id,descripcion,cantidad,precio_unitario) VALUES(?,'Other synthetic item',1,10)", budget);
        Backup backup = dump();
        source.jdbc().update("UPDATE articulos SET nombre='Updated synthetic content' WHERE id=?", article);
        source.jdbc().update("""
                UPDATE presupuesto_items SET descripcion='Other synthetic item'
                WHERE ctid IN (SELECT ctid FROM presupuesto_items WHERE presupuesto_id=?
                    AND descripcion='Duplicate synthetic item' ORDER BY ctid LIMIT 1)
                """, budget);
        UUID environment = UUID.randomUUID();
        var external = captureWithoutWrites(source, environment);
        recover(backup);
        assertThat(source.jdbc().queryForObject("SELECT count(*) FROM articulos", Long.class)).isEqualTo(1);
        assertThat(target.jdbc().queryForObject("SELECT count(*) FROM articulos", Long.class)).isEqualTo(1);
        assertThat(source.jdbc().queryForObject("SELECT count(*) FROM presupuesto_items", Long.class)).isEqualTo(3);
        assertThat(target.jdbc().queryForObject("SELECT count(*) FROM presupuesto_items", Long.class)).isEqualTo(3);
        assertThat(source.jdbc().queryForList("SELECT DISTINCT descripcion FROM presupuesto_items ORDER BY descripcion", String.class))
                .isEqualTo(target.jdbc().queryForList("SELECT DISTINCT descripcion FROM presupuesto_items ORDER BY descripcion", String.class));
        var report = compareCheckpointWithoutWrites(external, environment);
        assertChangedSurfaces(report, actor.workshop(), Surface.ARTICULOS, Surface.ITEMS);
        assertThat(report.findings()).hasSize(2);
    }

    @Test void snapshotRejectsTheSurfaceCapacitySentinelInsteadOfReturningPartialEvidence() {
        Actor first = actor(), second = actor();
        assertThat(source.jdbc().update("""
                INSERT INTO articulos(taller_id,nombre)
                SELECT CASE WHEN number<=5000 THEN ? ELSE ? END,'Capacity synthetic'
                FROM generate_series(1,10001) AS number
                """, first.workshop(), second.workshop())).isEqualTo(10001);
        var before = rows(source, true);
        assertThatThrownBy(() -> new RecoverySnapshotReader(source.jdbc(), source.manager()).capture(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOfSatisfying(RecoverySnapshotReader.Rejected.class,
                        failure -> assertThat(failure.code()).isEqualTo(RecoverySnapshotReader.Rejected.Code.CAPACITY_EXCEEDED))
                .hasNoCause();
        assertThat(rows(source, true)).isEqualTo(before);
    }

    @Test void snapshotUsesAnIndependentReadOnlyRepeatableReadTransaction() {
        Actor actor = actor();
        source.jdbc().update("INSERT INTO articulos(taller_id,nombre) VALUES(?,'Committed synthetic content')", actor.workshop());
        UUID environment = UUID.randomUUID();
        var expected = captureWithoutWrites(source, environment);
        var before = rows(source, true);
        AtomicBoolean observed = new AtomicBoolean();
        JdbcTemplate inspecting = new JdbcTemplate(source.jdbc().getDataSource()) {
            @Override public void execute(String sql) {
                assertThat(queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("repeatable read");
                assertThat(queryForObject("SHOW transaction_read_only", String.class)).isEqualTo("on");
                observed.set(true);
                super.execute(sql);
            }
        };
        var reader = new RecoverySnapshotReader(inspecting, source.manager());
        new TransactionTemplate(source.manager()).executeWithoutResult(status -> {
            source.jdbc().update("UPDATE articulos SET nombre='Caller uncommitted content' WHERE taller_id=?", actor.workshop());
            var independent = reader.capture(environment, UUID.randomUUID());
            assertThat(RecoveryCheckpointComparison.compare(expected, independent).status()).isEqualTo(RecoveryCheckpointComparison.Status.MATCH);
            status.setRollbackOnly();
        });
        assertThat(observed).isTrue();
        assertThat(rows(source, true)).isEqualTo(before);
    }

    @Test void packagedMaintenanceCliAuthenticatesARealRestoredBackupAndReportsDrift() throws Exception {
        Actor actor = actor();
        source.jdbc().update("INSERT INTO articulos(taller_id,nombre) VALUES(?,'CLI original synthetic')", actor.workshop());
        UUID environment = UUID.randomUUID(), checkpoint = UUID.randomUUID();
        Path key = temporary.resolve("cli-synthetic.key");
        byte[] secret = new byte[32]; Arrays.fill(secret, (byte) 53);
        Files.write(key, secret); Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-------"));
        Path file = temporary.resolve("cli-initial-checkpoint");
        var sourceBefore = rows(source, true);
        var captured = cli(source, "capture", file, environment, checkpoint, key, null);
        String digest = capturedDigest(captured, environment, checkpoint);
        assertThat(rows(source, true)).isEqualTo(sourceBefore);
        Backup backup = dump(); recover(backup);
        var targetBefore = rows(target, true);
        var matching = cli(target, "compare", file, environment, checkpoint, key, digest);
        assertThat(matching.exit()).isZero();
        assertThat(matching.output().strip()).isEqualTo("MATCH findings=0 NO_AUTORIZA_REAPERTURA");
        assertThat(rows(target, true)).isEqualTo(targetBefore);

        source.jdbc().update("UPDATE articulos SET nombre='CLI later synthetic' WHERE taller_id=?", actor.workshop());
        Path later = temporary.resolve("cli-later-checkpoint");
        UUID laterId = UUID.randomUUID();
        String laterDigest = capturedDigest(cli(source, "capture", later, environment, laterId, key, null), environment, laterId);
        var changed = cli(target, "compare", later, environment, laterId, key, laterDigest);
        assertThat(changed.exit()).isEqualTo(2);
        assertThat(changed.output().lines().toList()).containsExactly("DIFFERENCES findings=1 NO_AUTORIZA_REAPERTURA",
                "workshop=" + actor.workshop() + " surface=ARTICULOS issue=SURFACE_CHANGED");

        String wrongDigest = (laterDigest.charAt(0) == '0' ? "1" : "0") + laterDigest.substring(1);
        var wrongReceipt = cli(target, "compare", later, environment, laterId, key, wrongDigest);
        assertThat(wrongReceipt.exit()).isEqualTo(1);
        assertThat(wrongReceipt.output().strip()).isEqualTo("RECOVERY_CHECK_FAILED NO_AUTORIZA_REAPERTURA");
        Path wrongKey = temporary.resolve("cli-wrong-synthetic.key");
        byte[] otherSecret = new byte[32]; Arrays.fill(otherSecret, (byte) 71);
        Files.write(wrongKey, otherSecret); Files.setPosixFilePermissions(wrongKey, PosixFilePermissions.fromString("rw-------"));
        var unauthenticated = cli(target, "compare", later, environment, laterId, wrongKey, laterDigest);
        assertThat(unauthenticated.exit()).isEqualTo(1);
        assertThat(unauthenticated.output().strip()).isEqualTo("RECOVERY_CHECK_FAILED NO_AUTORIZA_REAPERTURA");
        assertThat(rows(target, true)).isEqualTo(targetBefore);
    }

    private CliResult cli(Database database, String operation, Path file, UUID environment, UUID checkpoint,
                          Path key, String digest) throws Exception {
        String directory = System.getProperty("ordenfix.build.directory");
        String finalName = System.getProperty("ordenfix.build.final-name");
        assertThat(directory).isNotBlank(); assertThat(finalName).isNotBlank();
        Path jar = Path.of(directory, finalName + "-recovery-cli.jar").toAbsolutePath();
        assertThat(jar).isRegularFile();
        Path output = Files.createTempFile(temporary, "cli-output-", ".txt");
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dspring.config.location=file:/nonexistent/synthetic-recovery-config.properties",
                "-Dordenfix.recovery.quarantine=true", "-jar", jar.toString(), operation, file.toString());
        builder.environment().clear();
        builder.environment().put("ORDENFIX_RECOVERY_ENVIRONMENT_ID", environment.toString());
        builder.environment().put("ORDENFIX_RECOVERY_CHECKPOINT_ID", checkpoint.toString());
        builder.environment().put("ORDENFIX_RECOVERY_KEY_FILE", key.toString());
        builder.environment().put("ORDENFIX_RECOVERY_JDBC_URL", ((DriverManagerDataSource) database.jdbc().getDataSource()).getUrl());
        builder.environment().put("ORDENFIX_RECOVERY_JDBC_USERNAME", "fixture");
        builder.environment().put("ORDENFIX_RECOVERY_JDBC_PASSWORD", "fixture-password");
        if (digest != null) builder.environment().put("ORDENFIX_RECOVERY_EXPECTED_SHA256", digest);
        builder.redirectErrorStream(true).redirectOutput(output.toFile());
        Process process = builder.start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("maintenance CLI has a bounded process lifetime").isTrue();
            String text = Files.readString(output);
            assertThat(text).doesNotContain("fixture-password", "jdbc:postgresql", "synthetic-recovery-config", key.toString(), "Spring Boot");
            return new CliResult(process.exitValue(), text);
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    private static String capturedDigest(CliResult result, UUID environment, UUID checkpoint) {
        assertThat(result.exit()).isZero();
        var match = Pattern.compile("CAPTURED environment=" + environment + " checkpoint=" + checkpoint
                + " sha256=([0-9a-f]{64}) NO_AUTORIZA_REAPERTURA").matcher(result.output().strip());
        assertThat(match.matches()).isTrue();
        return match.group(1);
    }

    private record CliResult(int exit, String output) { }

    private RecoveryCheckpoint.Snapshot captureWithoutWrites(Database database, UUID environment) {
        var before = rows(database, true);
        var snapshot = new RecoverySnapshotReader(database.jdbc(), database.manager()).capture(environment, UUID.randomUUID());
        assertThat(rows(database, true)).as("checkpoint capture cannot change its source").isEqualTo(before);
        return snapshot;
    }

    private RecoveryCheckpointComparison.Report compareCheckpointWithoutWrites(RecoveryCheckpoint.Snapshot expected, UUID environment) {
        var before = rows(target, true);
        var actual = captureWithoutWrites(target, environment);
        var report = RecoveryCheckpointComparison.compare(expected, actual);
        assertThat(report.notice()).isEqualTo("NO_AUTORIZA_REAPERTURA");
        assertThat(rows(target, true)).as("comparison cannot reconcile or mutate recovery data").isEqualTo(before);
        return report;
    }

    private static void assertChangedSurfaces(RecoveryCheckpointComparison.Report report, long workshop, Surface... surfaces) {
        assertThat(report.status()).isEqualTo(RecoveryCheckpointComparison.Status.DIFFERENCES);
        for (Surface surface : surfaces)
            assertThat(report.findings()).contains(new RecoveryCheckpointComparison.Finding(workshop, surface,
                    RecoveryCheckpointComparison.Issue.SURFACE_CHANGED));
    }

    private void operationalRows(Actor actor) {
        long client = source.jdbc().queryForObject("INSERT INTO clientes(taller_id,nombre,apellido,telefono) VALUES(?,'Synthetic','Customer','555-0100') RETURNING id", Long.class, actor.workshop());
        long equipment = source.jdbc().queryForObject("INSERT INTO equipos(taller_id,cliente_id,marca,modelo,tipo) VALUES(?,?,'Synthetic','Equipment','TV') RETURNING id", Long.class, actor.workshop(), client);
        long repair = source.jdbc().queryForObject("INSERT INTO reparaciones(taller_id,equipo_id,descripcion_problema,estado) VALUES(?,?,'Synthetic repair','RECIBIDO') RETURNING id", Long.class, actor.workshop(), equipment);
        long article = source.jdbc().queryForObject("INSERT INTO articulos(taller_id,nombre) VALUES(?,'Synthetic article') RETURNING id", Long.class, actor.workshop());
        long budget = source.jdbc().queryForObject("INSERT INTO presupuestos(taller_id,reparacion_id,estado,total) VALUES(?,?,'PENDIENTE',20) RETURNING id", Long.class, actor.workshop(), repair);
        source.jdbc().update("INSERT INTO presupuesto_items(presupuesto_id,descripcion,cantidad,precio_unitario) SELECT ?,'Duplicate synthetic item',1,10 FROM generate_series(1,2)", budget);
        source.jdbc().update("INSERT INTO cobros(taller_id,reparacion_id,monto,metodo) VALUES(?,?,10,'EFECTIVO')", actor.workshop(), repair);
        source.jdbc().update("INSERT INTO repuestos(taller_id,reparacion_id,articulo_id,nombre,precio) VALUES(?,?,?,'Synthetic spare',10)", actor.workshop(), repair, article);
    }

    private static PostgreSQLContainer postgres(String name) {
        return new PostgreSQLContainer("postgres:16-alpine").withDatabaseName(name)
                .withUsername("fixture").withPassword("fixture-password")
                .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                        new PortBinding(Ports.Binding.bindIp("127.0.0.1"), ExposedPort.tcp(5432))));
    }

    private static void assertLoopback(PostgreSQLContainer container) {
        assertThat(container.getContainerInfo().getNetworkSettings().getPorts().getBindings().get(ExposedPort.tcp(5432)))
                .isNotEmpty().allSatisfy(binding -> assertThat(binding.getHostIp()).isEqualTo("127.0.0.1"));
    }

    private static Database freshDatabase(PostgreSQLContainer container) throws Exception {
        String name = "recovery_" + UUID.randomUUID().toString().replace("-", "");
        command(container, "createdb", "--host=/var/run/postgresql", "--username=fixture", "--template=template0", name);
        var dataSource = new DriverManagerDataSource("jdbc:postgresql://" + container.getHost() + ":"
                + container.getMappedPort(5432) + "/" + name, container.getUsername(), container.getPassword());
        return new Database(name, new JdbcTemplate(dataSource), new DataSourceTransactionManager(dataSource));
    }

    private Actor actor() {
        long workshop = source.jdbc().queryForObject("INSERT INTO talleres(nombre) VALUES('Recovery synthetic') RETURNING id", Long.class);
        source.jdbc().update("INSERT INTO suscripciones(taller_id,plan,estado) VALUES(?,'FREE','TRIAL')", workshop);
        long owner = user(workshop, "ADMIN", true);
        user(workshop, "USER", true);
        user(workshop, "USER", false);
        return new Actor(workshop, owner);
    }

    private long user(long workshop, String role, boolean active) {
        String mark = UUID.randomUUID().toString();
        return source.jdbc().queryForObject("""
                INSERT INTO users(username,email,password,role,taller_id,active,email_verificado,token_version)
                VALUES(?,?,'not-a-login-secret',?,?,?,true,0) RETURNING id
                """, Long.class, mark, mark + "@synthetic.invalid", role, workshop, active);
    }

    private UUID export(Actor actor, String state) {
        UUID id = UUID.randomUUID();
        // Opaque 32-byte fixtures satisfy storage constraints; they are not deliverable ZIPs.
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) 1);
        source.jdbc().update("""
                INSERT INTO cuenta_exportaciones(id,user_id,taller_id,token_version,session_hash,request_hash,estado,
                    creada_en,actualizada_en,expira_en,capturada_en,snapshot_cipher,archive_cipher)
                VALUES(?,?,?,0,?,?,?,clock_timestamp(),clock_timestamp(),clock_timestamp()+INTERVAL '1 hour',
                    clock_timestamp(),?,?)
                """, id, actor.owner(), actor.workshop(), hex(), hex(), state,
                "QUEUED".equals(state) ? bytes : null, "READY".equals(state) ? bytes : null);
        return id;
    }

    /** Consumer fixture: proof + operation + real Store/Effects, with all SQL guards enabled. */
    private Evidence transition(Actor actor, UUID reference, boolean restore) {
        return transition(actor, reference, restore, Instant.now());
    }

    private Evidence transition(Actor actor, UUID reference, boolean restore, Instant at) {
        TransactionTemplate transaction = new TransactionTemplate(source.manager());
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction.execute(status -> {
            new WorkshopClosureGate(source.jdbc()).lockExclusive(actor.workshop());
            Clock clock = Clock.fixed(at.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
            String proof = hex();
            String purpose = restore ? "RESTAURAR" : "CERRAR";
            UUID operation = restore ? UUID.randomUUID() : reference;
            long epoch = source.jdbc().queryForObject("SELECT token_version FROM users WHERE id=?", Long.class, actor.owner());
            long generation = source.jdbc().queryForObject("SELECT cierre_version FROM talleres WHERE id=?", Long.class, actor.workshop());
            source.jdbc().update("""
                    INSERT INTO cuenta_cierre_confirmaciones(token_hash,user_id,taller_id,token_version,session_hash,proposito,
                        operacion_id,cierre_referencia,cierre_version,creada_en,expira_en)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?)
                    """, proof, actor.owner(), actor.workshop(), epoch, hex(), purpose, operation, reference, generation,
                    Timestamp.from(clock.instant()), Timestamp.from(clock.instant().plusSeconds(120)));
            source.jdbc().update("UPDATE cuenta_cierre_confirmaciones SET usada_en=? WHERE token_hash=?", Timestamp.from(clock.instant()), proof);
            var store = new WorkshopClosureStore(source.jdbc(), clock);
            var receipt = restore ? store.restore(actor.workshop(), actor.owner(), reference)
                    : store.restrict(actor.workshop(), actor.owner(), reference);
            source.jdbc().update("""
                    INSERT INTO cuenta_cierre_operaciones(operacion_id,taller_id,user_id,proposito,cierre_referencia,cierre_version,
                        request_digest,proof_hash,estado_resultante,politica,confirmado_en,reversible_hasta,eliminacion_prevista_en,registrada_en)
                    VALUES(?,?,?,?,?,?,?,?,?,'ordenfix-cierre/1',?,?,?,?)
                    """, operation, actor.workshop(), actor.owner(), purpose, reference, generation + 1, hex(), proof,
                    restore ? "ABIERTO" : "RESTRINGIDO", Timestamp.from(receipt.confirmedAt()),
                    Timestamp.from(receipt.reversibleUntil()), Timestamp.from(receipt.deletionExpectedBy()), Timestamp.from(clock.instant()));
            var effects = new WorkshopClosureEffects(source.jdbc());
            if (restore) effects.enqueueRestore(operation, reference, actor.workshop(), actor.owner(), clock.instant());
            else effects.enqueueClose(operation, reference, actor.workshop(), actor.owner(), clock.instant());
            return new Evidence(actor.workshop(), actor.owner(), reference, generation + 1,
                    restore ? State.RESTORED : State.RESTRICTED, epoch + 1);
        });
    }

    private Backup dump() throws Exception {
        var before = rows(source, true);
        var logical = rows(source, false);
        var sequences = sequences(source);
        var triggers = triggers(source);
        String remote = "/tmp/" + UUID.randomUUID() + ".dump";
        command(SOURCE, "pg_dump", "--host=/var/run/postgresql", "--username=fixture", "--dbname=" + source.name(),
                "--format=custom", "--file=" + remote);
        Path file = temporary.resolve(UUID.randomUUID() + ".dump");
        SOURCE.copyFileFromContainer(remote, file.toString());
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.size(file)).isPositive();
        assertThat(rows(source, true)).as("dump does not change source rows").isEqualTo(before);
        return new Backup(file, logical, sequences, triggers);
    }

    private void recover(Backup backup) throws Exception {
        var result = restoreCommand(copyToTarget(backup.file()));
        assertThat(result.getExitCode()).as("pg_restore: %s", result.getStderr()).isZero();
        assertThat(rows(target, false)).isEqualTo(backup.rows());
        assertThat(sequences(target)).isEqualTo(backup.sequences());
        assertThat(triggers(target)).isEqualTo(backup.triggers());
        assertThat(target.jdbc().queryForObject("SELECT max(version::int) FROM flyway_schema_history WHERE success", Integer.class))
                .isEqualTo(37);
    }

    private static Database restoreAgain(Database restored) throws Exception {
        var before = rows(restored, true);
        var logical = rows(restored, false);
        var sequenceValues = sequences(restored);
        var triggerDefinitions = triggers(restored);
        String remote = "/tmp/" + UUID.randomUUID() + ".dump";
        command(TARGET, "pg_dump", "--host=/var/run/postgresql", "--username=fixture", "--dbname=" + restored.name(),
                "--format=custom", "--file=" + remote);
        Database rebuilt = freshDatabase(TARGET);
        command(TARGET, "pg_restore", "--host=/var/run/postgresql", "--username=fixture", "--dbname=" + rebuilt.name(),
                "--exit-on-error", "--single-transaction", "--no-owner", remote);
        assertThat(rows(restored, true)).as("a second dump cannot mutate its source").isEqualTo(before);
        assertThat(rows(rebuilt, false)).isEqualTo(logical);
        assertThat(sequences(rebuilt)).isEqualTo(sequenceValues);
        assertThat(triggers(rebuilt)).isEqualTo(triggerDefinitions);
        return rebuilt;
    }

    private String copyToTarget(Path file) throws Exception {
        String remote = "/tmp/" + UUID.randomUUID() + ".dump";
        TARGET.copyFileToContainer(MountableFile.forHostPath(file, 0600), remote);
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        var digest = command(TARGET, "sha256sum", remote);
        assertThat(digest.getStdout().split(" ")[0]).isEqualTo(expected);
        return remote;
    }

    private org.testcontainers.containers.Container.ExecResult restoreCommand(String remote) throws Exception {
        // Empty target, same synthetic role; retain ACLs, fail on error and roll back the whole restore.
        return TARGET.execInContainer("timeout", "-s", "KILL", "45", "pg_restore", "--host=/var/run/postgresql",
                "--username=fixture", "--dbname=" + target.name(), "--exit-on-error", "--single-transaction", "--no-owner", remote);
    }

    private static org.testcontainers.containers.Container.ExecResult command(PostgreSQLContainer container, String... args) throws Exception {
        String[] bounded = new String[args.length + 4];
        System.arraycopy(new String[]{"timeout", "-s", "KILL", "45"}, 0, bounded, 0, 4);
        System.arraycopy(args, 0, bounded, 4, args.length);
        var result = container.execInContainer(bounded);
        assertThat(result.getExitCode()).as("%s: %s", args[0], result.getStderr()).isZero();
        return result;
    }

    private WorkshopClosureBackupCheck checker() { return new WorkshopClosureBackupCheck(target.jdbc(), target.manager()); }

    private Report compareWithoutWrites(List<Evidence> evidence) {
        var before = rows(target, true);
        Report report = checker().compare(evidence);
        assertThat(report.notice()).isEqualTo(Notice.NO_AUTORIZA_REAPERTURA);
        assertThat(rows(target, true)).as("diagnosis cannot reconcile or mutate the recovered database").isEqualTo(before);
        return report;
    }

    private static Map<String, String> rows(Database database, boolean includeRowVersion) {
        var result = new LinkedHashMap<String, String>();
        for (String table : database.jdbc().queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename", String.class)) {
            assertThat(table).matches("[a-z][a-z0-9_]*");
            String row = "to_jsonb(r)::text" + (includeRowVersion ? "||xmin::text" : "");
            result.put(table, database.jdbc().queryForObject("SELECT coalesce(string_agg(" + row
                    + ",',' ORDER BY to_jsonb(r)::text),'') FROM public.\"" + table + "\" r", String.class));
        }
        return result;
    }

    private static List<Map<String, Object>> sequences(Database database) {
        return database.jdbc().queryForList("SELECT sequencename,last_value FROM pg_sequences WHERE schemaname='public' ORDER BY sequencename");
    }

    private static List<Map<String, Object>> triggers(Database database) {
        return database.jdbc().queryForList("""
                SELECT c.relname,t.tgname,t.tgenabled,pg_get_triggerdef(t.oid) AS definition
                  FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace
                 WHERE n.nspname='public' AND NOT t.tgisinternal ORDER BY c.relname,t.tgname
                """);
    }

    private static Map<String, String> controlRows(Database database, Actor actor) {
        return Map.of(
                "workshop", database.jdbc().queryForObject("SELECT to_jsonb(t)::text FROM talleres t WHERE id=?", String.class, actor.workshop()),
                "users", database.jdbc().queryForObject("SELECT jsonb_agg(to_jsonb(u) ORDER BY id)::text FROM users u WHERE taller_id=?", String.class, actor.workshop()),
                "subscription", database.jdbc().queryForObject("SELECT to_jsonb(s)::text FROM suscripciones s WHERE taller_id=?", String.class, actor.workshop()));
    }

    private static List<Map<String, Object>> userRows(Database database, Actor actor) {
        return database.jdbc().queryForList("SELECT id,active,token_version FROM users WHERE taller_id=? ORDER BY id", actor.workshop());
    }

    private static void assertEpochs(Database database, Actor actor, long epoch) {
        var users = userRows(database, actor);
        assertThat(users).hasSize(3).allSatisfy(user -> assertThat(((Number) user.get("token_version")).longValue()).isEqualTo(epoch));
        assertThat(users).extracting(user -> user.get("active")).containsExactly(true, true, false);
    }

    private static String workshopState(Database database, Actor actor) {
        return database.jdbc().queryForObject("SELECT cierre_estado FROM talleres WHERE id=?", String.class, actor.workshop());
    }

    private static String exportState(Database database, UUID job) {
        return database.jdbc().queryForObject("SELECT estado FROM cuenta_exportaciones WHERE id=?", String.class, job);
    }

    private static List<String> effectTypes(Database database) {
        return database.jdbc().queryForList("SELECT tipo FROM cuenta_cierre_efectos ORDER BY tipo", String.class);
    }

    private static String hex() { return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""); }
    private record Database(String name, JdbcTemplate jdbc, DataSourceTransactionManager manager) { }
    private record Actor(long workshop, long owner) { }
    private record Backup(Path file, Map<String, String> rows, List<Map<String, Object>> sequences,
                          List<Map<String, Object>> triggers) { }
}
