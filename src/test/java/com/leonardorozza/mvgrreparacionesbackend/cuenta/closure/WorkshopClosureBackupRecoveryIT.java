package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
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
        TransactionTemplate transaction = new TransactionTemplate(source.manager());
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction.execute(status -> {
            new WorkshopClosureGate(source.jdbc()).lockExclusive(actor.workshop());
            Clock clock = Clock.fixed(Instant.now().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
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
                .isEqualTo(35);
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
