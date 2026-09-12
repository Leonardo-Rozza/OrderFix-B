package com.leonardorozza.mvgrreparacionesbackend.cuenta.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.reauth.*;
import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import com.leonardorozza.mvgrreparacionesbackend.photos.*;
import com.leonardorozza.mvgrreparacionesbackend.utils.jwt.JwtUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipInputStream;
import static com.leonardorozza.mvgrreparacionesbackend.cuenta.reauth.ExportReauthenticationPurpose.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.config.import=", "spring.config.additional-location=",
        "spring.config.location=optional:classpath:/application.properties",
        "mail.enabled=false", "mercadopago.enabled=false", "mercadopago.checkout-enabled=false",
        "photos.private.enabled=false", "ordenfix.legal.registration-consent.enabled=false",
        "ordenfix.legal.registration-enforcement.enabled=false", "ordenfix.legal.account-read.enabled=false",
        "ordenfix.legal.account-acceptance.enabled=false", "ordenfix.legal.public-documents.enabled=false",
        "ordenfix.legal.public-requirements.enabled=false", "ordenfix.legal.aggregate-context.enabled=false",
        "ordenfix.legal.editorial-context.enabled=false", "ordenfix.legal.import-context.enabled=false",
        "ordenfix.legal.dry-run-context.enabled=false", "ordenfix.legal.public-document-read-context.enabled=false",
        "ordenfix.legal.public-requirements-context.enabled=false"
})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExportJobServiceIT {
    @Container static final PostgreSQLContainer PG=new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_export_jobs").withUsername("ordenfix").withPassword("ordenfix");
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired ExportReauthenticationService reauth;
    @Autowired WorkshopExportSnapshotService snapshots;
    @Autowired UserRepository users;
    @Autowired JwtUtils jwt;
    @Autowired PasswordEncoder encoder;
    private static final String PASSWORD="export-job-synthetic-password";
    private static final ObjectMapper JSON=new ObjectMapper();
    private final ExportArtifactCodec codec=new ExportArtifactCodec(Map.of(1,Base64.getEncoder().encodeToString(new byte[32])),1);
    private ExportJobService jobs;
    private Actor own;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",PG::getJdbcUrl); registry.add("spring.datasource.username",PG::getUsername);
        registry.add("spring.datasource.password",PG::getPassword); registry.add("spring.datasource.driver-class-name",PG::getDriverClassName);
        registry.add("spring.flyway.enabled",()->"true"); registry.add("spring.jpa.hibernate.ddl-auto",()->"validate");
        registry.add("spring.jpa.properties.hibernate.dialect",()->"org.hibernate.dialect.PostgreSQLDialect");
    }
    @BeforeEach void prepare() {
        jdbc.update("DELETE FROM cuenta_exportaciones");
        own=actor(); jobs=service(snapshots,null);
    }
    @Test void requestReplayAndFreshDownloadProofProduceOneAuthenticatedZip() throws Exception {
        String access=token(own),proof=grant(access,EXPORTAR); UUID request=UUID.randomUUID();
        var created=jobs.request(access,proof,request);
        assertThat(created.state()).isEqualTo("QUEUED"); assertThat(created.reused()).isFalse(); assertUsed(proof,true);
        String replacement=grant(access,EXPORTAR);
        assertThat(jobs.request(access,replacement,request)).isEqualTo(new ExportJobService.Status(created.id(),"QUEUED",created.expiresAt(),true));
        assertUsed(replacement,false);
        assertThat(jobs.runNext()).isTrue(); assertThat(jobs.runNext()).isFalse();
        assertThat(jobs.status(access,created.id()).state()).isEqualTo("READY");
        String differentSession=token(own),download=grant(differentSession,DESCARGAR_EXPORTACION);
        byte[] archive=jobs.authorizedArchive(differentSession,download,created.id()); assertUsed(download,true);
        var files=unzip(archive);
        assertThat(JSON.readTree(files.get("manifest.json")).path("exportacion_integral_completa").asBoolean()).isTrue();
        assertThat(new String(files.get("datos/clientes.json"),StandardCharsets.UTF_8)).contains("CAPTURED_CUSTOMER");
        assertThat(new String(jdbc.queryForObject("SELECT archive_cipher FROM cuenta_exportaciones WHERE id=?",byte[].class,created.id()),StandardCharsets.ISO_8859_1))
                .doesNotContain("CAPTURED_CUSTOMER",PASSWORD,access,download);
        assertThat(jdbc.queryForObject("SELECT snapshot_cipher IS NULL FROM cuenta_exportaciones WHERE id=?",Boolean.class,created.id())).isTrue();
        assertThatThrownBy(()->jobs.authorizedArchive(differentSession,download,created.id())).isInstanceOf(RuntimeException.class);
    }
    @Test void jobInsertFailureRollsBackTheProofConsumption() {
        String access=token(own),proof=grant(access,EXPORTAR);
        jdbc.execute("CREATE FUNCTION export_job_reject_fixture() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'SYNTHETIC_FAILURE'; END $$");
        jdbc.execute("CREATE TRIGGER export_job_reject_fixture BEFORE INSERT ON cuenta_exportaciones FOR EACH ROW EXECUTE FUNCTION export_job_reject_fixture()");
        try { assertThatThrownBy(()->jobs.request(access,proof,UUID.randomUUID())).isInstanceOf(ExportPackageException.class).hasNoCause(); }
        finally { jdbc.execute("DROP TRIGGER export_job_reject_fixture ON cuenta_exportaciones"); jdbc.execute("DROP FUNCTION export_job_reject_fixture()"); }
        assertUsed(proof,false); assertThat(jdbc.queryForObject("SELECT count(*) FROM cuenta_exportaciones",Long.class)).isZero();
        assertThat(jobs.request(access,proof,UUID.randomUUID()).state()).isEqualTo("QUEUED");
    }
    @Test void replayFromAnotherSessionAndAnotherRequestWhileActiveDoNotConsumeProof() {
        String access=token(own); UUID key=UUID.randomUUID(); jobs.request(access,grant(access,EXPORTAR),key);
        String other=token(own),proof=grant(other,EXPORTAR);
        assertThatThrownBy(()->jobs.request(other,proof,key)).isInstanceOf(ExportPackageException.class); assertUsed(proof,false);
        assertThatThrownBy(()->jobs.request(other,proof,UUID.randomUUID())).isInstanceOf(ExportPackageException.class); assertUsed(proof,false);
    }
    @Test void anotherWorkshopCannotReadStatusOrConsumeItsProofForSomeoneElsesArchive() {
        String access=token(own); var job=jobs.request(access,grant(access,EXPORTAR),UUID.randomUUID()); jobs.runNext();
        Actor foreign=actor(); String other=token(foreign),proof=grant(other,DESCARGAR_EXPORTACION);
        assertThatThrownBy(()->jobs.status(other,job.id())).isInstanceOf(ExportPackageException.class);
        assertThatThrownBy(()->jobs.authorizedArchive(other,proof,job.id())).isInstanceOf(ExportPackageException.class); assertUsed(proof,false);
    }
    @ParameterizedTest @ValueSource(strings={"role","user","workshop","email","version"})
    void revocationClearsAllCiphertextAndNeverDelivers(String change) {
        String access=token(own); var job=jobs.request(access,grant(access,EXPORTAR),UUID.randomUUID()); jobs.runNext();
        String proof=grant(access,DESCARGAR_EXPORTACION);
        switch(change) {
            case "role"->jdbc.update("UPDATE users SET role='USER' WHERE id=?",own.user());
            case "user"->jdbc.update("UPDATE users SET active=false WHERE id=?",own.user());
            case "workshop"->jdbc.update("UPDATE talleres SET activo=false WHERE id=?",own.workshop());
            case "email"->jdbc.update("UPDATE users SET email_verificado=false WHERE id=?",own.user());
            case "version"->jdbc.update("UPDATE users SET token_version=token_version+1 WHERE id=?",own.user());
        }
        jobs.cleanup(); assertPurged(job.id(),"REVOKED");
        assertThatThrownBy(()->jobs.authorizedArchive(access,proof,job.id())).isInstanceOf(RuntimeException.class); assertUsed(proof,false);
    }
    @Test void expirationRemovesCiphertextAndEventuallyPurgesTombstoneAndOldGrants() {
        String access=token(own); var job=jobs.request(access,grant(access,EXPORTAR),UUID.randomUUID()); jobs.runNext();
        jdbc.update("UPDATE cuenta_exportaciones SET creada_en=clock_timestamp()-INTERVAL '2 hours',expira_en=clock_timestamp()-INTERVAL '1 minute' WHERE id=?",job.id());
        jobs.cleanup(); assertPurged(job.id(),"EXPIRED");
        assertThat(jobs.status(access,job.id()).state()).isEqualTo("EXPIRED");
        jdbc.update("UPDATE cuenta_exportaciones SET actualizada_en=clock_timestamp()-INTERVAL '8 days' WHERE id=?",job.id());
        jdbc.update("UPDATE cuenta_reautenticaciones SET creada_en=clock_timestamp()-INTERVAL '2 hours',expira_en=clock_timestamp()-INTERVAL '119 minutes',usada_en=NULL WHERE user_id=?",own.user());
        jobs.cleanup(); assertThat(jdbc.queryForObject("SELECT count(*) FROM cuenta_exportaciones WHERE id=?",Long.class,job.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cuenta_reautenticaciones WHERE user_id=?",Long.class,own.user())).isZero();
    }
    @Test void retriesReuseThePersistedSnapshotEvenAfterBusinessDataChanges() throws Exception {
        Photo photo=photo(); PrivatePhotoService port=mock(PrivatePhotoService.class); var captures=spy(snapshots);
        when(port.content(any(),eq(photo.repair()),eq(photo.id()))).thenThrow(new IllegalStateException("provider diagnostic"))
                .thenReturn(new PrivatePhotoDtos.Content("image/png",photo.bytes()));
        jobs=service(captures,port); String access=token(own); var job=jobs.request(access,grant(access,EXPORTAR),UUID.randomUUID());
        jobs.runNext(); assertThat(jobs.status(access,job.id()).state()).isEqualTo("QUEUED");
        byte[] first=jdbc.queryForObject("SELECT snapshot_cipher FROM cuenta_exportaciones WHERE id=?",byte[].class,job.id()); assertThat(first).isNotEmpty();
        jdbc.update("UPDATE clientes SET nombre='CHANGED_AFTER_CAPTURE' WHERE taller_id=?",own.workshop());
        // A new service instance represents process recovery, retaining only durable PostgreSQL state.
        jobs=service(captures,port); jobs.runNext();
        verify(captures,times(1)).capture(own.user(),own.workshop(),0);
        var files=unzip(jobs.authorizedArchive(access,grant(access,DESCARGAR_EXPORTACION),job.id()));
        assertThat(new String(files.get("datos/clientes.json"),StandardCharsets.UTF_8)).contains("CAPTURED_CUSTOMER").doesNotContain("CHANGED_AFTER_CAPTURE");
        assertThat(files.get("archivos/fotos/"+photo.id()+".png")).containsExactly(photo.bytes());
        assertThat(jobs.status(access,job.id()).expiresAt()).isBefore(Instant.now().plusSeconds(3605));
    }
    @Test void threeTransientFailuresClearTheSnapshotAndReleaseWorkshopCapacity() throws Exception {
        Photo photo=photo(); PrivatePhotoService port=mock(PrivatePhotoService.class);
        when(port.content(any(),eq(photo.repair()),eq(photo.id()))).thenThrow(new IllegalStateException("provider diagnostic"));
        jobs=service(snapshots,port); String access=token(own); var job=jobs.request(access,grant(access,EXPORTAR),UUID.randomUUID());
        jobs.runNext(); jobs.runNext(); jobs.runNext(); assertPurged(job.id(),"FAILED");
        assertThat(jdbc.queryForObject("SELECT intentos FROM cuenta_exportaciones WHERE id=?",Integer.class,job.id())).isEqualTo(3);
        assertThat(jobs.request(access,grant(access,EXPORTAR),UUID.randomUUID()).state()).isEqualTo("QUEUED");
    }
    @Test void revokedQueuedJobIsNeverCaptured() {
        var captures=spy(snapshots); jobs=service(captures,null); String access=token(own);
        var job=jobs.request(access,grant(access,EXPORTAR),UUID.randomUUID()); jdbc.update("UPDATE users SET active=false WHERE id=?",own.user());
        assertThat(jobs.runNext()).isFalse(); verify(captures,never()).capture(anyLong(),anyLong(),anyLong()); assertPurged(job.id(),"REVOKED");
    }
    @Test void modifiedCiphertextCannotConsumeDownloadProof() {
        String access=token(own); var job=jobs.request(access,grant(access,EXPORTAR),UUID.randomUUID()); jobs.runNext();
        jdbc.update("UPDATE cuenta_exportaciones SET archive_cipher=set_byte(archive_cipher,40,get_byte(archive_cipher,40)#1) WHERE id=?",job.id());
        String proof=grant(access,DESCARGAR_EXPORTACION);
        assertThatThrownBy(()->jobs.authorizedArchive(access,proof,job.id())).isInstanceOf(ExportPackageException.class).hasNoCause(); assertUsed(proof,false);
    }
    @Test void missingOrRevokedPhotoVetoesAnAlreadyPreparedArchive() throws Exception {
        Photo photo=photo(); PrivatePhotoService port=mock(PrivatePhotoService.class);
        when(port.content(any(),eq(photo.repair()),eq(photo.id()))).thenReturn(new PrivatePhotoDtos.Content("image/png",photo.bytes()));
        jobs=service(snapshots,port); String access=token(own); var job=jobs.request(access,grant(access,EXPORTAR),UUID.randomUUID()); jobs.runNext();
        assertThat(jobs.status(access,job.id()).state()).isEqualTo("READY");
        replica(()->jdbc.update("UPDATE reparacion_fotos_privadas SET estado='LIMPIEZA_PENDIENTE' WHERE id=?",photo.id()));
        jobs.cleanup(); assertPurged(job.id(),"REVOKED");
    }
    @Test void competingRequestsWithSameKeyHaveOneDurableEffect() throws Exception {
        String access=token(own),proof=grant(access,EXPORTAR); UUID key=UUID.randomUUID();
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<ExportJobService.Status> request=()->{start.await(); return jobs.request(access,proof,key);};
            var first=executor.submit(request); var second=executor.submit(request); start.countDown();
            var a=first.get(15,TimeUnit.SECONDS); var b=second.get(15,TimeUnit.SECONDS);
            assertThat(a.id()).isEqualTo(b.id()); assertThat(a.reused()).isNotEqualTo(b.reused());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cuenta_exportaciones",Long.class)).isEqualTo(1); assertUsed(proof,true);
    }
    @Test void workerWithExpiredLeaseCannotOverwriteOrDeleteTheSuccessorArtifact() throws Exception {
        Photo photo=photo(); PrivatePhotoService slow=mock(PrivatePhotoService.class),fast=mock(PrivatePhotoService.class);
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(slow.content(any(),eq(photo.repair()),eq(photo.id()))).thenAnswer(call->{entered.countDown(); assertThat(release.await(15,TimeUnit.SECONDS)).isTrue(); return new PrivatePhotoDtos.Content("image/png",photo.bytes());});
        when(fast.content(any(),eq(photo.repair()),eq(photo.id()))).thenReturn(new PrivatePhotoDtos.Content("image/png",photo.bytes()));
        var old=service(snapshots,slow); var next=service(snapshots,fast); String access=token(own);
        var job=old.request(access,grant(access,EXPORTAR),UUID.randomUUID());
        try(var executor=Executors.newSingleThreadExecutor()) {
            var running=executor.submit(old::runNext);
            try {
                assertThat(entered.await(15,TimeUnit.SECONDS)).isTrue();
                assertThat(next.runNext()).isFalse();
                jdbc.update("UPDATE cuenta_exportaciones SET lease_hasta=clock_timestamp()-INTERVAL '1 second' WHERE id=?",job.id());
                assertThat(next.runNext()).isTrue(); assertThat(next.status(access,job.id()).state()).isEqualTo("READY");
                byte[] winner=jdbc.queryForObject("SELECT archive_cipher FROM cuenta_exportaciones WHERE id=?",byte[].class,job.id());
                release.countDown(); assertThat(running.get(15,TimeUnit.SECONDS)).isTrue();
                assertThat(jdbc.queryForObject("SELECT archive_cipher FROM cuenta_exportaciones WHERE id=?",byte[].class,job.id())).containsExactly(winner);
                assertThat(next.status(access,job.id()).state()).isEqualTo("READY");
            } finally { release.countDown(); }
        }
    }
    @Test void globalCapacityRejectsWithoutConsumingTheFifthProof() {
        for(int i=0;i<4;i++) { Actor actor=actor(); String access=token(actor); jobs.request(access,grant(access,EXPORTAR),UUID.randomUUID()); }
        String access=token(own),proof=grant(access,EXPORTAR);
        assertThatThrownBy(()->jobs.request(access,proof,UUID.randomUUID())).isInstanceOf(ExportPackageException.class); assertUsed(proof,false);
    }
    @Test void photoDeletionWaitsUntilTheReadyPublicationCommits() throws Exception {
        Photo photo=photo(); PrivatePhotoService port=mock(PrivatePhotoService.class);
        when(port.content(any(),eq(photo.repair()),eq(photo.id()))).thenReturn(new PrivatePhotoDtos.Content("image/png",photo.bytes()));
        jobs=service(snapshots,port); String access=token(own);
        var job=jobs.request(access,grant(access,EXPORTAR),UUID.randomUUID());
        jdbc.execute("CREATE FUNCTION export_publish_barrier_fixture() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.estado='READY' THEN PERFORM pg_advisory_xact_lock(9176423); END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER export_publish_barrier_fixture BEFORE UPDATE ON cuenta_exportaciones FOR EACH ROW EXECUTE FUNCTION export_publish_barrier_fixture()");
        try(var controller=java.sql.DriverManager.getConnection(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()); var executor=Executors.newFixedThreadPool(2)) {
            controller.createStatement().execute("SELECT pg_advisory_lock(9176423)");
            var publish=executor.submit(jobs::runNext);
            try {
                awaitDatabaseWait("%archive_cipher=%");
                var deletion=executor.submit(()-> {
                    try(var connection=java.sql.DriverManager.getConnection(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword())) {
                        connection.setAutoCommit(false); connection.createStatement().execute("SET LOCAL session_replication_role=replica");
                        try(var statement=connection.prepareStatement("UPDATE reparacion_fotos_privadas SET estado='LIMPIEZA_PENDIENTE' WHERE id=?")) {
                            statement.setObject(1,photo.id()); statement.executeUpdate();
                        }
                        connection.commit(); return true;
                    }
                });
                awaitDatabaseWait("UPDATE reparacion_fotos_privadas SET estado=%");
                assertThat(deletion.isDone()).isFalse();
                controller.createStatement().execute("SELECT pg_advisory_unlock(9176423)");
                assertThat(publish.get(15,TimeUnit.SECONDS)).isTrue(); assertThat(deletion.get(15,TimeUnit.SECONDS)).isTrue();
            } finally { controller.createStatement().execute("SELECT pg_advisory_unlock(9176423)"); }
        } finally { jdbc.execute("DROP TRIGGER export_publish_barrier_fixture ON cuenta_exportaciones"); jdbc.execute("DROP FUNCTION export_publish_barrier_fixture()"); }
        jobs.cleanup(); assertPurged(job.id(),"REVOKED");
    }
    @Test void latestRecoversOnlyTheCurrentOwnersJobAcrossSessions() {
        String access=token(own);
        assertThat(jobs.latest(access)).isEmpty();
        var job=jobs.request(access,grant(access,EXPORTAR),UUID.randomUUID());
        assertThat(jobs.latest(token(own))).contains(job);
        assertThat(jobs.latest(token(actor()))).isEmpty();
        jobs.runNext();
        assertThat(jobs.latest(access).orElseThrow().state()).isEqualTo("READY");
        jdbc.update("UPDATE users SET token_version=1 WHERE id=?",own.user());
        assertThat(jobs.latest(token(own))).isEmpty();
    }
    @Test void invalidProofCannotReadTheArchiveByteaOrStartDecryption() {
        String access=token(own);
        var job=jobs.request(access,grant(access,EXPORTAR),UUID.randomUUID()); jobs.runNext();
        var observedJdbc=spy(jdbc); var observedCodec=spy(codec);
        var guarded=new ExportJobService(observedJdbc,manager,reauth,snapshots,observedCodec,new ExportPhotoReader(()->null));
        assertThatThrownBy(()->guarded.authorizedArchive(access,"A".repeat(43),job.id()))
                .isInstanceOf(com.leonardorozza.mvgrreparacionesbackend.exceptions.BadRequestException.class);
        verify(observedJdbc,never()).queryForObject(eq("SELECT archive_cipher FROM public.cuenta_exportaciones WHERE id=?"),eq(byte[].class),eq(job.id()));
        verify(observedCodec,never()).decryptArchive(any(),any());
    }
    @Test void sessionIsRecheckedAfterDecryptionAndFailedDeliveryRollsBackProof() {
        String access=token(own);
        var job=jobs.request(access,grant(access,EXPORTAR),UUID.randomUUID()); jobs.runNext();
        String proof=grant(access,DESCARGAR_EXPORTACION); var observed=spy(codec);
        var plaintextSeen=new java.util.concurrent.atomic.AtomicReference<byte[]>();
        doAnswer(invocation->{
            byte[] plaintext=(byte[])invocation.callRealMethod(); plaintextSeen.set(plaintext);
            jdbc.update("UPDATE users SET token_version=1 WHERE id=?",own.user());
            return plaintext;
        }).when(observed).decryptArchive(any(),any());
        var guarded=new ExportJobService(jdbc,manager,reauth,snapshots,observed,new ExportPhotoReader(()->null));
        assertThatThrownBy(()->guarded.authorizedArchive(access,proof,job.id()))
                .isInstanceOf(com.leonardorozza.mvgrreparacionesbackend.exceptions.UnauthorizedException.class);
        assertUsed(proof,false);
        assertThat(plaintextSeen.get()).isNotEmpty().containsOnly((byte)0);
    }
    private void awaitDatabaseWait(String query) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime()<deadline) {
            if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND query LIKE ? AND wait_event_type='Lock')",Boolean.class,query)) return;
            Thread.sleep(20);
        }
        throw new AssertionError("PostgreSQL did not observe the expected lock wait");
    }
    private ExportJobService service(WorkshopExportSnapshotService capture,PrivatePhotoService photos) {
        return new ExportJobService(jdbc,manager,reauth,capture,codec,new ExportPhotoReader(()->photos));
    }
    private Actor actor() {
        long workshop=jdbc.queryForObject("INSERT INTO talleres(nombre) VALUES('Export job') RETURNING id",Long.class);
        long user=jdbc.queryForObject("INSERT INTO users(username,email,password,role,taller_id,active,email_verificado,token_version) VALUES('Export actor',?,?,'ADMIN',?,true,true,0) RETURNING id",Long.class,UUID.randomUUID()+"@export.synthetic.invalid",encoder.encode(PASSWORD),workshop);
        jdbc.update("INSERT INTO clientes(nombre,apellido,telefono,taller_id) VALUES('CAPTURED_CUSTOMER','Fixture','0000000000',?)",workshop);
        return new Actor(user,workshop);
    }
    private String token(Actor actor) { return jwt.generateToken(new AuthenticatedUserPrincipal(users.findSessionByIdAndTallerId(actor.user(),actor.workshop()).orElseThrow()),actor.workshop()); }
    private String grant(String access,ExportReauthenticationPurpose purpose) { return reauth.issue(access,PASSWORD,purpose).token(); }
    private void assertUsed(String proof,boolean used) { assertThat(jdbc.queryForObject("SELECT usada_en IS NOT NULL FROM cuenta_reautenticaciones WHERE token_hash=?",Boolean.class,ExportFile.digest(proof.getBytes(StandardCharsets.UTF_8)))).isEqualTo(used); }
    private void assertPurged(UUID id,String state) {
        assertThat(jdbc.queryForMap("SELECT estado,snapshot_cipher IS NULL AS no_snapshot,archive_cipher IS NULL AS no_archive,cardinality(foto_ids) AS photos FROM cuenta_exportaciones WHERE id=?",id))
                .containsEntry("estado",state).containsEntry("no_snapshot",true).containsEntry("no_archive",true).containsEntry("photos",0);
    }
    private static Map<String,byte[]> unzip(byte[] bytes)throws IOException {
        var result=new LinkedHashMap<String,byte[]>(); try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) { for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry()) result.put(entry.getName(),zip.readAllBytes()); } return result;
    }
    private void replica(Runnable work) { new TransactionTemplate(manager).executeWithoutResult(status->{jdbc.execute("SET LOCAL session_replication_role=replica"); work.run();}); }
    private Photo photo()throws Exception {
        var output=new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",output); byte[] bytes=output.toByteArray();
        long customer=jdbc.queryForObject("SELECT id FROM clientes WHERE taller_id=? LIMIT 1",Long.class,own.workshop());
        long equipment=jdbc.queryForObject("INSERT INTO equipos(marca,modelo,cliente_id,taller_id) VALUES('Marca','Modelo',?,?) RETURNING id",Long.class,customer,own.workshop());
        long repair=jdbc.queryForObject("INSERT INTO reparaciones(equipo_id,taller_id,descripcion_problema,estado,fecha_ingreso,precio_estimado) VALUES(?,?,'Export fixture','INGRESADO',current_date,1000) RETURNING id",Long.class,equipment,own.workshop());
        UUID id=UUID.randomUUID();
        replica(()->jdbc.update("""
                INSERT INTO reparacion_fotos_privadas(id,reparacion_id,reparacion_original_id,taller_id,user_id,rol_wire,nombre,mime_type,bytes,sha256,momento,estado,
                    confirmado_en,expira_en,retener_hasta,asociada_en,object_key,asset_id,asset_version,scope_hmac,key_hmac,fingerprint_hmac,hmac_key_version)
                VALUES(?,?,?,?,?,'ADMIN','Export image','image/png',?,?,'INGRESO','ASOCIADA',
                    statement_timestamp()-INTERVAL '1 minute',statement_timestamp()+INTERVAL '14 minutes',statement_timestamp()+INTERVAL '1 hour',statement_timestamp()-INTERVAL '30 seconds',
                    ?,?,'1',?,?,?,1)
                """,id,repair,repair,own.workshop(),own.user(),bytes.length,ExportFile.digest(bytes),"ordenfix-private/"+id,"asset-"+id,id.toString().replace("-", "").repeat(2),"b".repeat(64),"c".repeat(64)));
        return new Photo(id,repair,bytes);
    }
    private record Actor(long user,long workshop) { }
    private record Photo(UUID id,long repair,byte[] bytes) { }
}
