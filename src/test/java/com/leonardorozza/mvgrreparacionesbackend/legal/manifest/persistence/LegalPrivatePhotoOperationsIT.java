package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.*;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.export.ExportArtifactCodec;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.export.ExportPackageException;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.export.ExportPhotoReader;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.export.WorkshopExportSnapshotService;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.*;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.*;
import com.leonardorozza.mvgrreparacionesbackend.photos.*;
import com.leonardorozza.mvgrreparacionesbackend.photos.storage.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipInputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.sql.Timestamp;
import java.util.concurrent.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static com.leonardorozza.mvgrreparacionesbackend.photos.PrivatePhotoDtos.*;
import static org.assertj.core.api.Assertions.*;

/** Real V30/V33 role and transactions; storage effects are a test port, never a Cloudinary ACL claim. */
@Testcontainers
class LegalPrivatePhotoOperationsIT {
    static final String ROLE="ordenfix_private_photos";
    static final String PASSWORD="private-photo-synthetic-role";
    @Container static final PostgreSQLContainer PG=new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_acceptance_photos").withUsername("owner").withPassword("fixture-owner-password");
    @TempDir static Path directory;
    static JdbcTemplate owner;
    static LegalRestrictedAcceptanceRoleFixture.Credentials credentials;
    LegalPrivatePhotoOperations service;
    MemoryStorage storage;
    LegalActorSnapshot actor,employee,other;
    long repair;
    byte[] image;

    /** Reused by the opt-in full Boot/browser laboratory, with its own actor/equipment seeds. */
    static LegalRestrictedAcceptanceRoleFixture.Credentials prepare(PostgreSQLContainer postgres,Path directory) throws Exception {
        JdbcTemplate owner=new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
        Flyway.configure().dataSource(owner.getDataSource()).locations("classpath:db/migration").target("29").load().migrate();
        var credentials=LegalRestrictedAcceptanceRoleFixture.provision(owner,ROLE,PASSWORD);
        // The existing golden publication already contains the exact FOTOS declaration and documents.
        var release=LegalV28AggregateITSupport.releaseWithContinuedUse(directory,LegalPrivatePhotoOperationsIT.class,"private-photo-source");
        var publication=LegalV28AggregateITSupport.importRelease(owner.getDataSource(),release);
        LegalManifestPersistenceITSupport.promoteToReady(owner,publication);
        Flyway.configure().dataSource(owner.getDataSource()).locations("classpath:db/migration").target("30").load().migrate();
        owner.execute("GRANT SELECT,INSERT ON public.reparacion_fotos_privadas,public.reparacion_foto_atestaciones TO "+ROLE);
        owner.execute("GRANT UPDATE(estado,asset_id,asset_version,lease_id,lease_hasta,asociada_en) ON public.reparacion_fotos_privadas TO "+ROLE);
        owner.execute("GRANT SELECT(id,taller_id),UPDATE(id) ON public.reparaciones TO "+ROLE);
        owner.execute("GRANT EXECUTE ON FUNCTION public.foto_privada_insert_guard_v30(),public.foto_atestacion_insert_guard_v30(),public.foto_privada_completa_v30(),public.foto_privada_update_guard_v30() TO "+ROLE);
        Flyway.configure().dataSource(owner.getDataSource()).locations("classpath:db/migration").target("35").load().migrate();
        owner.execute("GRANT SELECT(cierre_estado) ON public.talleres TO "+ROLE);
        grantDeletionEvidence(owner,ROLE);
        JdbcTemplate restricted=new JdbcTemplate(new DriverManagerDataSource(credentials.jdbcUrl(),credentials.username(),credentials.password()));
        new LegalV29AcceptanceSchemaVerifier(restricted,"public").verify();
        new LegalAcceptancePrivilegeVerifier(restricted,credentials.username(),"public",true).verify();
        // Testcontainers appends loggerLevel=OFF to its administrative URL. The production
        // private-pool guard deliberately admits only SSL options: use this same container's
        // explicit endpoint, without carrying the fixture-only logging option into it.
        String host=postgres.getHost();
        if(host.contains(":") && !host.startsWith("["))host="["+host+"]";
        String privateUrl="jdbc:postgresql://"+host+":"+postgres.getMappedPort(5432)+"/"+postgres.getDatabaseName();
        return new LegalRestrictedAcceptanceRoleFixture.Credentials(privateUrl,credentials.username(),credentials.password(),credentials.driverClassName());
    }
    static void grantDeletionEvidence(JdbcTemplate owner,String role) {
        owner.execute("GRANT SELECT,INSERT ON public.reparacion_foto_eliminaciones TO "+role);
        owner.execute("GRANT UPDATE(asset_id,asset_version,identificada_en,resultado,observada_en,confirmada_en) ON public.reparacion_foto_eliminaciones TO "+role);
    }
    static Map<String,String> properties(LegalRestrictedAcceptanceRoleFixture.Credentials credentials) {
        return Map.of("photos.private.enabled","true","photos.private.jdbc-url",credentials.jdbcUrl(),
                "photos.private.username",credentials.username(),"photos.private.password",credentials.password(),"photos.private.retention","P30D",
                "ordenfix.legal.idempotency.keyring.1",LegalAcceptanceServiceITSupport.HMAC_SECRET,
                "ordenfix.legal.idempotency.active-write-version","1","ordenfix.legal.account-metadata.keyring.7",LegalAcceptanceServiceITSupport.AES_SECRET,
                "ordenfix.legal.account-metadata.active-write-version","7","ordenfix.legal.account-metadata.retention","P30D");
    }
    @BeforeAll static void prepare() throws Exception {
        credentials=prepare(PG,directory);owner=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
    }
    @BeforeEach void setup() throws Exception {
        owner.execute("TRUNCATE public.talleres CASCADE");
        actor=actor("ADMIN");other=actor("ADMIN");
        owner.update("UPDATE users SET email_verificado=true WHERE id=?",actor.userId());
        long employeeId=owner.queryForObject("INSERT INTO users(username,password,email,role,taller_id,active,token_version) VALUES('Photo employee','fixture','employee-photo@fixture.test','USER',?,true,0) RETURNING id",Long.class,actor.tallerId());
        employee=new LegalActorSnapshot(employeeId,actor.tallerId(),UserRole.USER,0,true,true);
        repair=repair(actor);storage=new MemoryStorage();MockEnvironment environment=new MockEnvironment();
        properties(credentials).forEach(environment::setProperty);service=LegalPrivatePhotoOperations.open(environment,storage);
        image=png();
    }
    @AfterEach void close(){if(service!=null)service.close();}
    static byte[] png() throws Exception {
        var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",bytes);return bytes.toByteArray();
    }
    static String sha(byte[] bytes) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    static LegalActorSnapshot actor(String role) {
        var seeded=LegalRestrictedAcceptanceRoleFixture.seedActor(owner,role);
        return new LegalActorSnapshot(seeded.userId(),seeded.workshopId(),UserRole.valueOf(role),0,true,true);
    }
    static long repair(LegalActorSnapshot actor) {
        long client=owner.queryForObject("INSERT INTO clientes(nombre,apellido,telefono,taller_id) VALUES('Photo client','Fixture',?,?) RETURNING id",Long.class,UUID.randomUUID().toString().substring(0,12),actor.tallerId());
        long equipment=owner.queryForObject("INSERT INTO equipos(marca,modelo,cliente_id,taller_id) VALUES('Marca','Modelo',?,?) RETURNING id",Long.class,client,actor.tallerId());
        return owner.queryForObject("INSERT INTO reparaciones(equipo_id,taller_id,descripcion_problema,estado,fecha_ingreso,precio_estimado) VALUES(?,?,'Fixture','INGRESADO',current_date,50000) RETURNING id",Long.class,equipment,actor.tallerId());
    }
    static AuthenticatedUserPrincipal principal(LegalActorSnapshot actor){return LegalAcceptanceServiceITSupport.principal(actor);}
    Create body(LegalActorSnapshot actor,long repair) throws Exception {
        var requirements=service.requirements(principal(actor),repair);
        var acceptances=requirements.requisitos().stream().map(r->new LegalAcceptanceCommand.Acceptance(UUID.fromString(r.id()),TipoActoLegal.valueOf(r.tipoActo()),r.afirmacionSha256(),r.documentos().stream().map(d->new LegalAcceptanceCommand.Document(UUID.fromString(d.id()),d.sha256())).toList(),true)).toList();
        return new Create("fixture.png","image/png",image.length,sha(image),MomentoFoto.INGRESO,requirements.requiredSetRevision(),acceptances,
                new Attestation("AUTORIZACION_DATOS_CLIENTE",List.of("FOTOS"),true));
    }
    Result<Intention> create(LegalActorSnapshot actor,long repair,String key,Create body){return service.create(principal(actor),repair,key,body,LegalRequestMetadata.of(LegalRequestMetadata.parseIpLiteral("127.0.0.1"),"Private photo PG fixture"));}
    Intention create() throws Exception {return create(actor,repair,UUID.randomUUID().toString(),body(actor,repair)).value();}
    Photo associate() throws Exception {var id=create().id();service.upload(principal(actor),repair,id,"image/png",image);return service.finish(principal(actor),repair,id).value();}
    long count(String table){return owner.queryForObject("SELECT count(*) FROM public."+table,Long.class);}
    String state(UUID id){return owner.queryForObject("SELECT estado FROM public.reparacion_fotos_privadas WHERE id=?",String.class,id);}
    static void code(Throwable failure,String code){assertThat(failure).isInstanceOf(PrivatePhotoException.class);assertThat(((PrivatePhotoException)failure).code()).isEqualTo(code);}

    @Test void restrictedWorkshopCannotCreateReadOrFinalizePrivatePhotos() throws Exception {
        var photo=associate();
        var pending=create();
        var command=body(actor,repair);
        long intentions=count("reparacion_fotos_privadas"),acceptances=count("legal_aceptaciones");
        restrictWorkshop();
        storage.reads.set(0);

        code(catchThrowable(()->service.requirements(principal(actor),repair)),"FOTO_ACTOR_NO_VALIDO");
        code(catchThrowable(()->service.photos(principal(actor),repair)),"FOTO_ACTOR_NO_VALIDO");
        code(catchThrowable(()->service.content(principal(actor),repair,photo.id())),"FOTO_ACTOR_NO_VALIDO");
        code(catchThrowable(()->service.finish(principal(actor),repair,pending.id())),"FOTO_ACTOR_NO_VALIDO");
        code(catchThrowable(()->create(actor,repair,UUID.randomUUID().toString(),command)),"FOTO_ACTOR_NO_VALIDO");
        assertThat(storage.reads.get()).isZero();
        assertThat(storage.uploads.get()).isEqualTo(1);
        assertThat(count("reparacion_fotos_privadas")).isEqualTo(intentions);
        assertThat(count("legal_aceptaciones")).isEqualTo(acceptances);
        assertThat(state(photo.id())).isEqualTo("ASOCIADA");
        assertThat(state(pending.id())).isEqualTo("AUTORIZADA");
        assertThat(service.cleanup()).as("closure alone does not delete retained associated evidence").isZero();
        assertThat(storage.assets).hasSize(1);
    }

    @Test void closureDuringUploadPreservesObjectKeyAndCleanupRecoversTheUnacknowledgedAsset() throws Exception {
        UUID id=create().id();
        storage.afterUpload=this::restrictWorkshop;
        code(catchThrowable(()->service.upload(principal(actor),repair,id,"image/png",image)),"FOTO_ACTOR_NO_VALIDO");
        String key="ordenfix-private/"+id;
        assertThat(storage.assets).containsKey(key);
        assertThat(state(id)).isEqualTo("AUTORIZADA");
        assertThat(owner.queryForMap("SELECT object_key,asset_id IS NULL AS unacknowledged,lease_id IS NOT NULL AS leased FROM reparacion_fotos_privadas WHERE id=?",id))
                .containsEntry("object_key",key).containsEntry("unacknowledged",true).containsEntry("leased",true);

        // Advance the persisted fixture deadlines without altering production retention or waiting in real time.
        fixture(()->owner.update("""
                UPDATE reparacion_fotos_privadas SET confirmado_en=statement_timestamp()-INTERVAL '16 minutes',
                    expira_en=statement_timestamp()-INTERVAL '1 minute',lease_hasta=statement_timestamp()-INTERVAL '1 second'
                WHERE id=?
                """,id));
        assertThat(service.cleanup()).isEqualTo(1);
        assertThat(state(id)).isEqualTo("ELIMINADA");
        assertThat(storage.assets).isEmpty();
        assertThat(storage.deleteAttempts).containsExactly(key);
        assertThat(owner.queryForObject("SELECT object_key FROM reparacion_fotos_privadas WHERE id=?",String.class,id)).isEqualTo(key);
        assertThat(count("reparacion_foto_atestaciones")).isEqualTo(1);
    }

    /** Consumer fixture: real V34 confirmation, store, operation and effects; no disabled closure guard. */
    private UUID restrictWorkshop() {
        var tx=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(owner.getDataSource()));
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx.execute(status->{
            new WorkshopClosureGate(owner).lockExclusive(actor.tallerId());
            Clock clock=Clock.fixed(Instant.now().truncatedTo(ChronoUnit.MICROS),ZoneOffset.UTC);
            UUID reference=UUID.randomUUID();String proof=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
            long epoch=owner.queryForObject("SELECT token_version FROM users WHERE id=?",Long.class,actor.userId());
            long generation=owner.queryForObject("SELECT cierre_version FROM talleres WHERE id=?",Long.class,actor.tallerId());
            owner.update("""
                    INSERT INTO cuenta_cierre_confirmaciones(token_hash,user_id,taller_id,token_version,session_hash,proposito,
                        operacion_id,cierre_referencia,cierre_version,creada_en,expira_en)
                    VALUES(?,?,?,?,?,'CERRAR',?,?,?,?,?)
                    """,proof,actor.userId(),actor.tallerId(),epoch,"a".repeat(64),reference,reference,generation,
                    Timestamp.from(clock.instant()),Timestamp.from(clock.instant().plusSeconds(120)));
            owner.update("UPDATE cuenta_cierre_confirmaciones SET usada_en=? WHERE token_hash=?",Timestamp.from(clock.instant()),proof);
            var receipt=new WorkshopClosureStore(owner,clock).restrict(actor.tallerId(),actor.userId(),reference);
            owner.update("""
                    INSERT INTO cuenta_cierre_operaciones(operacion_id,taller_id,user_id,proposito,cierre_referencia,cierre_version,
                        request_digest,proof_hash,estado_resultante,politica,confirmado_en,reversible_hasta,eliminacion_prevista_en,registrada_en)
                    VALUES(?,?,?,'CERRAR',?,?,?,?,'RESTRINGIDO','ordenfix-cierre/1',?,?,?,?)
                    """,reference,actor.tallerId(),actor.userId(),reference,generation+1,"b".repeat(64),proof,
                    Timestamp.from(receipt.confirmedAt()),Timestamp.from(receipt.reversibleUntil()),
                    Timestamp.from(receipt.deletionExpectedBy()),Timestamp.from(clock.instant()));
            new WorkshopClosureEffects(owner).enqueueClose(reference,reference,actor.tallerId(),actor.userId(),clock.instant());
            return reference;
        });
    }
    private static void fixture(Runnable action) {
        var tx=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(owner.getDataSource()));
        tx.executeWithoutResult(status->{owner.execute("SET LOCAL session_replication_role=replica");action.run();});
    }

    @Test void anAuthorizedPhotoFromTheRealProtocolIsIncludedInTheEncryptedExport() throws Exception {
        owner.update("UPDATE users SET email_verificado=true WHERE id=?",actor.userId());
        var photo=associate();
        var snapshots=new WorkshopExportSnapshotService(owner,new DataSourceTransactionManager(owner.getDataSource()));
        var snapshot=snapshots.capture(actor.userId(),actor.tallerId(),actor.tokenVersion());
        assertThat(snapshot.pendingPhotos()).hasSize(1);
        assertThat(snapshot.pendingPhotos().getFirst().id()).isEqualTo(photo.id());
        var checkpoints=new AtomicInteger();
        var files=new ExportPhotoReader(()->service).read(snapshot,actor.tokenVersion(),checkpoints::incrementAndGet);
        var codec=new ExportArtifactCodec(Map.of(1,Base64.getEncoder().encodeToString(new byte[32])),1);
        var context=new ExportArtifactCodec.Context(UUID.randomUUID(),actor.tallerId(),actor.userId());
        byte[] encrypted=codec.archive(context,snapshot,files);
        Map<String,byte[]> entries=new HashMap<>();
        try(var zip=new ZipInputStream(new ByteArrayInputStream(codec.decryptArchive(context,encrypted)),StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) {assertThat(entries.put(entry.getName(),zip.readAllBytes())).isNull();zip.closeEntry();}
        }
        String photoPath="archivos/fotos/"+photo.id()+".png";
        assertThat(entries.get(photoPath)).containsExactly(image);
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var manifest=mapper.readTree(entries.get("manifest.json"));
        assertThat(manifest.path("exportacion_integral_completa").asBoolean()).isTrue();
        assertThat(manifest.path("archivos_pendientes").isEmpty()).isTrue();
        assertThat(mapper.readTree(entries.get("datos/fotos_privadas.json")).get(0).path("archivo_estado").asText()).isEqualTo("INCLUIDA");
        assertThat(checkpoints.get()).isGreaterThanOrEqualTo(2);
        assertThat(storage.uploads.get()).isEqualTo(1);
        assertThat(state(photo.id())).isEqualTo("ASOCIADA");
    }

    @Test void deletingAPhotoAfterTheSnapshotVetoesExportThroughTheRealReadProtocol() throws Exception {
        owner.update("UPDATE users SET email_verificado=true WHERE id=?",actor.userId());
        var photo=associate();
        var snapshots=new WorkshopExportSnapshotService(owner,new DataSourceTransactionManager(owner.getDataSource()));
        var snapshot=snapshots.capture(actor.userId(),actor.tallerId(),actor.tokenVersion());
        assertThat(snapshot.pendingPhotos()).hasSize(1);
        service.delete(principal(employee),repair,photo.id());
        assertThat(state(photo.id())).isEqualTo("ELIMINADA");
        assertThat(storage.assets).isEmpty();
        var codec=new ExportArtifactCodec(Map.of(1,Base64.getEncoder().encodeToString(new byte[32])),1);
        var context=new ExportArtifactCodec.Context(UUID.randomUUID(),actor.tallerId(),actor.userId());
        var artifact=new java.util.concurrent.atomic.AtomicReference<byte[]>();
        assertThatThrownBy(()-> {
            var files=new ExportPhotoReader(()->service).read(snapshot,actor.tokenVersion(),()->{});
            artifact.set(codec.archive(context,snapshot,files));
        }).isInstanceOf(ExportPackageException.class).hasNoCause();
        assertThat(artifact.get()).isNull();
    }

    @Test void declarationAndIntentionCommitTogetherAndOnlyFinalizeAssociates() throws Exception {
        var intention=create();assertThat(intention.estado()).isEqualTo("AUTORIZADA");
        assertThat(service.photos(principal(actor),repair)).isEmpty();assertThat(storage.uploads.get()).isZero();
        assertThat(count("legal_aceptaciones")).isEqualTo(2);assertThat(count("reparacion_foto_atestaciones")).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM public.legal_idempotencia_resultados WHERE operacion='ATESTACION_FOTOS'",Long.class)).isEqualTo(1);
        service.upload(principal(actor),repair,intention.id(),"image/png",image);
        assertThat(service.photos(principal(actor),repair)).isEmpty();
        var result=service.finish(principal(actor),repair,intention.id());assertThat(result.reused()).isFalse();assertThat(result.value().createdAt()).isNotNull();
        assertThat(service.photos(principal(actor),repair)).containsExactly(result.value());
        assertThat(service.content(principal(employee),repair,intention.id()).bytes()).containsExactly(image);
    }
    @Test void sameKeyReusesIntentAndAnotherManifestFails() throws Exception {
        String key=UUID.randomUUID().toString();var body=body(actor,repair);var first=create(actor,repair,key,body);
        assertThat(create(actor,repair,key,body)).isEqualTo(new Result<>(first.value(),true));
        var changed=new Create("renamed.png",body.mimeType(),body.bytes(),body.sha256(),body.momento(),body.requiredSetRevision(),body.aceptacionesLegales(),body.atestacion());
        code(catchThrowable(()->create(actor,repair,key,changed)),"IDEMPOTENCY_KEY_REUTILIZADA");
        assertThat(count("reparacion_fotos_privadas")).isEqualTo(1);
    }
    @Test void anotherRepairRequiresNewConfirmationButReusesCanonicalDeclaration() throws Exception {
        create();long second=repair(actor);var body=body(actor,second);
        assertThat(body.aceptacionesLegales()).hasSize(1);
        create(actor,second,UUID.randomUUID().toString(),body);
        assertThat(count("legal_aceptaciones")).isEqualTo(2);
        assertThat(count("reparacion_foto_atestaciones")).isEqualTo(2);
        assertThat(count("legal_idempotencia_sin_actos")).isEqualTo(1);
    }
    @Test void missingConfirmationRollsBackBeforeStorage() throws Exception {
        var input=body(actor,repair);var invalid=new Create(input.nombre(),input.mimeType(),input.bytes(),input.sha256(),input.momento(),input.requiredSetRevision(),input.aceptacionesLegales(),null);
        code(catchThrowable(()->create(actor,repair,UUID.randomUUID().toString(),invalid)),"ATESTACION_REQUERIDA");
        assertThat(count("legal_aceptaciones")).isZero();assertThat(count("reparacion_fotos_privadas")).isZero();assertThat(storage.uploads.get()).isZero();
    }
    @Test void staleRevisionRollsBackBeforeAnyEvidence() throws Exception {
        var input=body(actor,repair);var invalid=new Create(input.nombre(),input.mimeType(),input.bytes(),input.sha256(),input.momento(),"sha256:"+"0".repeat(64),input.aceptacionesLegales(),input.atestacion());
        code(catchThrowable(()->create(actor,repair,UUID.randomUUID().toString(),invalid)),"REQUISITOS_LEGALES_DESACTUALIZADOS");
        assertThat(count("legal_aceptaciones")).isZero();assertThat(count("reparacion_fotos_privadas")).isZero();
    }
    @Test void creatorOnlyUploadAndFinalizeButSameWorkshopCanReadDelete() throws Exception {
        var id=create().id();code(catchThrowable(()->service.upload(principal(employee),repair,id,"image/png",image)),"FOTO_ACTOR_NO_PERMITIDO");
        service.upload(principal(actor),repair,id,"image/png",image);
        code(catchThrowable(()->service.finish(principal(employee),repair,id)),"FOTO_ACTOR_NO_PERMITIDO");
        service.finish(principal(actor),repair,id);assertThat(service.content(principal(employee),repair,id).bytes()).containsExactly(image);
        service.delete(principal(employee),repair,id);assertThat(state(id)).isEqualTo("ELIMINADA");
    }
    @Test void foreignAndRevokedActorsCannotReadOrChangeStorage() throws Exception {
        var photo=associate();int uploads=storage.uploads.get();
        code(catchThrowable(()->service.content(principal(other),repair,photo.id())),"FOTO_NO_ENCONTRADA");
        code(catchThrowable(()->service.delete(principal(other),repair,photo.id())),"FOTO_NO_ENCONTRADA");
        owner.update("UPDATE users SET active=false WHERE id=?",actor.userId());
        code(catchThrowable(()->service.content(principal(actor),repair,photo.id())),"FOTO_ACTOR_NO_VALIDO");
        assertThat(storage.uploads.get()).isEqualTo(uploads);assertThat(storage.assets).hasSize(1);
    }
    @Test void invalidManifestBytesNeverReachProviderAndMayRetryCorrectBytes() throws Exception {
        var id=create().id();byte[] different=image.clone();different[different.length-1]^=1;
        code(catchThrowable(()->service.upload(principal(actor),repair,id,"image/png",different)),"FOTO_INVALIDA");
        assertThat(storage.uploads.get()).isZero();assertThat(state(id)).isEqualTo("AUTORIZADA");
        assertThat(service.upload(principal(actor),repair,id,"image/png",image).estado()).isEqualTo("SUBIDA");
    }
    @Test void lostUploadAcknowledgementFindsSamePrivateObjectOnRetry() throws Exception {
        var id=create().id();storage.failUpload.set(true);
        code(catchThrowable(()->service.upload(principal(actor),repair,id,"image/png",image)),"FOTOS_PRIVADAS_NO_DISPONIBLES");
        assertThat(storage.assets).hasSize(1);assertThat(state(id)).isEqualTo("AUTORIZADA");
        service.upload(principal(actor),repair,id,"image/png",image);assertThat(storage.uploads.get()).isEqualTo(1);
        var first=service.finish(principal(actor),repair,id);var second=service.finish(principal(actor),repair,id);
        assertThat(second.reused()).isTrue();assertThat(second.value()).isEqualTo(first.value());
    }
    @Test void failedDeleteKeepsMetadataAndObjectIdentityForCleanup() throws Exception {
        var photo=associate();storage.failDelete.set(true);
        code(catchThrowable(()->service.delete(principal(actor),repair,photo.id())),"FOTOS_PRIVADAS_NO_DISPONIBLES");
        assertThat(state(photo.id())).isEqualTo("LIMPIEZA_PENDIENTE");assertThat(storage.assets).hasSize(1);
        assertThat(service.photos(principal(actor),repair)).containsExactly(photo);
        assertThat(service.cleanup()).isEqualTo(1);assertThat(storage.assets).isEmpty();assertThat(state(photo.id())).isEqualTo("ELIMINADA");
        service.delete(principal(actor),repair,photo.id());assertThat(service.photos(principal(actor),repair)).isEmpty();
    }
    @Test void removalCannotLosePrivateObjectAndKeepsConfirmationAfterConfirmedDelete() throws Exception {
        var photo=associate();var failure=catchThrowable(()->owner.update("DELETE FROM reparaciones WHERE id=?",repair));
        assertThat(failure).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var sql=(org.postgresql.util.PSQLException)((org.springframework.dao.DataIntegrityViolationException)failure).getMostSpecificCause();
        assertThat(sql.getSQLState()).isEqualTo("23514");
        assertThat(sql.getServerErrorMessage().getConstraint()).isEqualTo("foto_privada_borrado_pendiente");
        assertThat(storage.assets).hasSize(1);service.delete(principal(actor),repair,photo.id());
        owner.update("DELETE FROM reparaciones WHERE id=?",repair);
        assertThat(count("reparacion_foto_atestaciones")).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT reparacion_id IS NULL FROM reparacion_fotos_privadas WHERE id=?",Boolean.class,photo.id())).isTrue();
    }
    @Test void terminalUnassociatedIntentCleansItsRealStoredBytes() throws Exception {
        var id=create().id();service.upload(principal(actor),repair,id,"image/png",image);
        // Explicit supported maintenance transition, not time-travel or disabled guards.
        owner.update("UPDATE reparacion_fotos_privadas SET estado='EXPIRADA' WHERE id=?",id);
        assertThat(service.cleanup()).isEqualTo(1);assertThat(storage.assets).isEmpty();assertThat(state(id)).isEqualTo("ELIMINADA");
    }
    @Test void changedManifestIsRejectedByDatabaseImmutabilityGuard() throws Exception {
        var id=create().id();assertThatThrownBy(()->owner.update("UPDATE reparacion_fotos_privadas SET sha256=? WHERE id=?","0".repeat(64),id)).hasMessageContaining("identidad privada inmutable");
    }

    @Test void knownAssetIsDeletedEvenIfLookupByObjectKeyNoLongerFindsIt() throws Exception {
        var photo=associate();storage.hideLookup.set(true);
        service.delete(principal(actor),repair,photo.id());
        assertThat(storage.assets).isEmpty();assertThat(state(photo.id())).isEqualTo("ELIMINADA");
    }
    @Test void legacyRepairDeleteNeedsNoPermissionToReadPrivatePhotos() {
        String legacyRole="photo_legacy_delete_"+UUID.randomUUID().toString().replace("-","");
        owner.execute("CREATE ROLE "+legacyRole+" NOLOGIN");
        owner.execute("GRANT USAGE ON SCHEMA public TO "+legacyRole);
        owner.execute("GRANT SELECT(id),DELETE ON public.reparaciones TO "+legacyRole);
        assertThat(owner.queryForObject("SELECT has_table_privilege(?, 'public.reparacion_fotos_privadas','SELECT')",Boolean.class,legacyRole)).isFalse();
        Integer deleted=owner.execute((org.springframework.jdbc.core.ConnectionCallback<Integer>) connection->{
            try(var change=connection.createStatement()) {change.execute("SET ROLE "+legacyRole);}
            try(var statement=connection.prepareStatement("DELETE FROM public.reparaciones WHERE id=?")) {
                statement.setLong(1,repair);return statement.executeUpdate();
            } finally {try(var reset=connection.createStatement()){reset.execute("RESET ROLE");}}
        });
        assertThat(deleted).isEqualTo(1);
    }

    @Test void cleanupAdvancesPastMoreThanTenPermanentFailuresAndWrapsToRetryTheOldest() throws Exception {
        List<UUID> ids=new ArrayList<>();
        for(int index=0;index<12;index++) {
            UUID id=create().id();ids.add(id);
            service.upload(principal(actor),repair,id,"image/png",image);
            // Supported maintenance transition retains real stored bytes and canonical evidence.
            owner.update("UPDATE reparacion_fotos_privadas SET estado='EXPIRADA' WHERE id=?",id);
        }
        List<UUID> ordered=owner.queryForList("SELECT id FROM reparacion_fotos_privadas ORDER BY confirmado_en,id",UUID.class);
        assertThat(ordered).containsExactlyElementsOf(ids);
        List<String> keys=ordered.stream().map(id->"ordenfix-private/"+id).toList();
        storage.permanentDeleteFailures.addAll(keys.subList(0,11));

        assertThat(service.cleanup()).isZero();
        assertThat(storage.deleteAttempts).containsExactlyElementsOf(keys.subList(0,10));
        assertThat(storage.assets).hasSize(12);
        storage.deleteAttempts.clear();

        assertThat(service.cleanup()).isEqualTo(1);
        assertThat(storage.deleteAttempts).containsExactlyElementsOf(keys.subList(10,12));
        assertThat(state(ordered.getLast())).isEqualTo("ELIMINADA");
        assertThat(storage.assets).hasSize(11);
        storage.deleteAttempts.clear();

        // Exhausted suffix wraps to the beginning in this next pass, without an idle pass or unbounded loop.
        assertThat(service.cleanup()).isZero();
        assertThat(storage.deleteAttempts).containsExactlyElementsOf(keys.subList(0,10));
        assertThat(count("reparacion_foto_atestaciones")).isEqualTo(12);
        assertThat(owner.queryForObject("SELECT count(*) FROM reparacion_fotos_privadas WHERE estado='LIMPIEZA_PENDIENTE'",Long.class)).isEqualTo(11);
    }

    private Map<String,Object> deletion(UUID id) {
        return owner.queryForMap("SELECT * FROM reparacion_foto_eliminaciones WHERE foto_id=?",id);
    }
    private void confirmed(UUID id) {
        assertThat(deletion(id)).containsEntry("resultado","IDENTIDAD_ELIMINADA")
                .containsEntry("asset_id","asset-"+id).containsEntry("asset_version","1")
                .containsEntry("user_id",actor.userId()).containsEntry("taller_id",actor.tallerId());
        assertThat(deletion(id).get("confirmada_en")).isNotNull();
        assertThat(state(id)).isEqualTo("ELIMINADA");
    }
    @Test void durableTargetPrecedesProviderEffectAndSurvivesConfirmedPhotoTombstone() throws Exception {
        var photo=associate();
        storage.beforeDelete=()->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(deletion(photo.id())).containsEntry("resultado","PENDIENTE")
                    .containsEntry("asset_id","asset-"+photo.id()).containsEntry("asset_version","1");
            assertThat(deletion(photo.id()).get("identificada_en")).isNotNull();
        };
        service.delete(principal(actor),repair,photo.id());confirmed(photo.id());
        var original=deletion(photo.id());
        service.delete(principal(actor),repair,photo.id());
        assertThat(deletion(photo.id())).isEqualTo(original);assertThat(storage.deleteAttempts).hasSize(1);
        assertThat(owner.queryForObject("SELECT asset_id IS NULL AND asset_version IS NULL FROM reparacion_fotos_privadas WHERE id=?",Boolean.class,photo.id())).isTrue();
        owner.update("DELETE FROM reparaciones WHERE id=?",repair);
        assertThat(deletion(photo.id())).isEqualTo(original);
    }
    @Test void lostDeleteAcknowledgementLeavesPendingAndRetriesTheSameDurableAssetIdentity() throws Exception {
        var photo=associate();storage.failDeleteAfterEffect.set(true);
        code(catchThrowable(()->service.delete(principal(actor),repair,photo.id())),"FOTOS_PRIVADAS_NO_DISPONIBLES");
        assertThat(storage.assets).isEmpty();assertThat(state(photo.id())).isEqualTo("LIMPIEZA_PENDIENTE");
        assertThat(deletion(photo.id())).containsEntry("resultado","PENDIENTE").containsEntry("confirmada_en",null);
        assertThat(service.cleanup()).isEqualTo(1);confirmed(photo.id());
        assertThat(storage.deleteIdentities).containsExactly("asset-"+photo.id(),"asset-"+photo.id());
    }
    @Test void failedEvidenceCompletionAfterRemoteEffectKeepsIdentityForLaterReconciliation() throws Exception {
        var photo=associate();storage.afterDelete=()->owner.execute("REVOKE UPDATE(confirmada_en) ON reparacion_foto_eliminaciones FROM "+ROLE);
        try {
            code(catchThrowable(()->service.delete(principal(actor),repair,photo.id())),"FOTOS_PRIVADAS_NO_DISPONIBLES");
            assertThat(storage.assets).isEmpty();assertThat(state(photo.id())).isEqualTo("LIMPIEZA_PENDIENTE");
            assertThat(deletion(photo.id())).containsEntry("resultado","PENDIENTE").containsEntry("confirmada_en",null);
        } finally {storage.afterDelete=()->{};grantDeletionEvidence(owner,ROLE);}
        assertThat(service.cleanup()).isEqualTo(1);confirmed(photo.id());
        assertThat(storage.deleteIdentities).containsExactly("asset-"+photo.id(),"asset-"+photo.id());
    }
    @Test void missingReceiptWritePrivilegeCannotProduceAProviderEffect() throws Exception {
        var photo=associate();owner.execute("REVOKE INSERT ON reparacion_foto_eliminaciones FROM "+ROLE);
        try {
            code(catchThrowable(()->service.delete(principal(actor),repair,photo.id())),"FOTOS_PRIVADAS_NO_DISPONIBLES");
            assertThat(storage.deleteAttempts).isEmpty();assertThat(count("reparacion_foto_eliminaciones")).isZero();
            assertThat(storage.assets).hasSize(1);
        } finally {grantDeletionEvidence(owner,ROLE);}
    }
    @Test void noRemoteIdentityIsAnAbsenceObservationAndNeverAnIdentityDeletionReceipt() throws Exception {
        UUID id=create().id();service.delete(principal(actor),repair,id);
        assertThat(state(id)).isEqualTo("ELIMINADA");
        assertThat(deletion(id)).containsEntry("resultado","AUSENCIA_OBSERVADA_SIN_IDENTIDAD")
                .containsEntry("asset_id",null).containsEntry("asset_version",null)
                .containsEntry("identificada_en",null).containsEntry("confirmada_en",null);
        assertThat(deletion(id).get("observada_en")).isNotNull();assertThat(storage.deleteAttempts).isEmpty();
    }
    @Test void unacknowledgedRemoteBytesMustMatchManifestBeforeIdentityCanBeAdoptedForDeletion() throws Exception {
        UUID id=create().id();storage.failUpload.set(true);
        code(catchThrowable(()->service.upload(principal(actor),repair,id,"image/png",image)),"FOTOS_PRIVADAS_NO_DISPONIBLES");
        byte[] changed=image.clone();changed[changed.length-1]^=1;storage.assets.put("ordenfix-private/"+id,changed);
        owner.update("UPDATE reparacion_fotos_privadas SET estado='EXPIRADA' WHERE id=?",id);
        assertThat(service.cleanup()).isZero();assertThat(state(id)).isEqualTo("LIMPIEZA_PENDIENTE");
        assertThat(storage.deleteAttempts).isEmpty();assertThat(deletion(id)).containsEntry("resultado","PENDIENTE").containsEntry("asset_id",null);
        storage.assets.put("ordenfix-private/"+id,image.clone());
        storage.beforeDelete=()->assertThat(deletion(id)).containsEntry("asset_id","asset-"+id);
        assertThat(service.cleanup()).isEqualTo(1);confirmed(id);
    }
    @Test void concurrentApiAndWorkerShareOneImmutableReceiptAndExactRemoteIdentity() throws Exception {
        var photo=associate();var entered=new CountDownLatch(2);var firstEntered=new CountDownLatch(1);var release=new CountDownLatch(1);
        storage.beforeDelete=()->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            entered.countDown();firstEntered.countDown();try {assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();}
            catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
        };
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->service.delete(principal(actor),repair,photo.id()));
            assertThat(firstEntered.await(10,TimeUnit.SECONDS)).isTrue();
            var second=executor.submit(service::cleanup);
            try {assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();} finally {release.countDown();}
            first.get(15,TimeUnit.SECONDS);assertThat(second.get(15,TimeUnit.SECONDS)).isEqualTo(1);
        } finally {release.countDown();}
        confirmed(photo.id());assertThat(count("reparacion_foto_eliminaciones")).isEqualTo(1);
        assertThat(storage.deleteIdentities).containsExactly("asset-"+photo.id(),"asset-"+photo.id());
    }
    @Test void closureInventorySeparatesConfirmedDeletionFromAbsenceObservationAndForeignPhotos() throws Exception {
        var photo=associate();service.delete(principal(actor),repair,photo.id());
        UUID absent=create().id();service.delete(principal(actor),repair,absent);
        long otherRepair=repair(other);create(other,otherRepair,UUID.randomUUID().toString(),body(other,otherRepair));
        UUID closure=restrictWorkshop();
        var inventory=new WorkshopClosureDeletionInventory(owner,new DataSourceTransactionManager(owner.getDataSource()),Clock.systemUTC());
        var result=inventory.inspect(actor.tallerId(),closure);
        assertThat(result.count(WorkshopClosureDeletionInventory.Metric.PRIVATE_PHOTO_DELETION_TARGETS)).isEqualTo(2);
        assertThat(result.count(WorkshopClosureDeletionInventory.Metric.PRIVATE_PHOTO_DELETIONS_CONFIRMED)).isEqualTo(1);
        assertThat(result.count(WorkshopClosureDeletionInventory.Metric.PRIVATE_PHOTO_ABSENCE_ONLY)).isEqualTo(1);
        assertThat(result.count(WorkshopClosureDeletionInventory.Metric.PRIVATE_PHOTO_DELETIONS_PENDING)).isZero();
        assertThat(result.count(WorkshopClosureDeletionInventory.Metric.PRIVATE_PHOTOS_DELETED_WITHOUT_RECEIPT)).isZero();
        assertThat(result.categories().get(WorkshopClosureDeletionInventory.Category.PRIVATE_PHOTOS).reviewReasons())
                .contains(WorkshopClosureDeletionInventory.ReviewReason.PHOTO_DELETION_RECONCILIATION_REQUIRED);
    }

    @Test void aPendingPhotoCannotBecomeDeletedWithoutItsDurableObservation() throws Exception {
        UUID id=create().id();
        owner.update("UPDATE reparacion_fotos_privadas SET estado='LIMPIEZA_PENDIENTE' WHERE id=?",id);
        assertSqlGuard(()->owner.update("UPDATE reparacion_fotos_privadas SET estado='ELIMINADA' WHERE id=?",id),"falta observacion durable");
        assertThat(state(id)).isEqualTo("LIMPIEZA_PENDIENTE");assertThat(count("reparacion_foto_eliminaciones")).isZero();
    }
    @Test void databaseRejectsForeignTargetIdentityRemappingAndConfirmationWithoutAtomicTombstone() throws Exception {
        UUID absent=create().id();
        owner.update("UPDATE reparacion_fotos_privadas SET estado='LIMPIEZA_PENDIENTE' WHERE id=?",absent);
        assertSqlGuard(()->owner.update("""
                INSERT INTO reparacion_foto_eliminaciones(foto_id,user_id,taller_id,object_key)
                VALUES(?,?,?,?)
                """,absent,other.userId(),other.tallerId(),"ordenfix-private/"+absent),"objetivo de eliminacion privado no disponible");
        var photo=associate();storage.failDelete.set(true);
        code(catchThrowable(()->service.delete(principal(actor),repair,photo.id())),"FOTOS_PRIVADAS_NO_DISPONIBLES");
        var target=deletion(photo.id());
        assertSqlGuard(()->owner.update("UPDATE reparacion_foto_eliminaciones SET asset_id='replacement' WHERE foto_id=?",photo.id()),"observacion remota de eliminacion invalida");
        assertSqlGuard(()->owner.update("""
                UPDATE reparacion_foto_eliminaciones SET resultado='IDENTIDAD_ELIMINADA',
                    observada_en=statement_timestamp(),confirmada_en=statement_timestamp() WHERE foto_id=?
                """,photo.id()),"observacion y foto privada inconsistentes");
        assertThat(deletion(photo.id())).isEqualTo(target);assertThat(state(photo.id())).isEqualTo("LIMPIEZA_PENDIENTE");
        assertSqlGuard(()->owner.update("DELETE FROM reparacion_foto_eliminaciones WHERE foto_id=?",photo.id()),"objetivo de eliminacion privado inmutable");
    }

    @Test void aHistoricalTombstoneWithoutEvidenceIsReportedAndNeverBackfilledByReplay() throws Exception {
        var photo=associate();service.delete(principal(actor),repair,photo.id());
        // Owner-only disposable fixture represents a pre-V35 tombstone. This is not an upgrade test:
        // the missing receipt intentionally models evidence that history never recorded.
        owner.execute("TRUNCATE reparacion_foto_eliminaciones");
        var original=owner.queryForMap("SELECT * FROM reparacion_fotos_privadas WHERE id=?",photo.id());
        int providerCalls=storage.deleteAttempts.size();
        service.delete(principal(actor),repair,photo.id());
        assertThat(count("reparacion_foto_eliminaciones")).isZero();
        assertThat(storage.deleteAttempts).hasSize(providerCalls);
        assertThat(owner.queryForMap("SELECT * FROM reparacion_fotos_privadas WHERE id=?",photo.id())).isEqualTo(original);
        UUID closure=restrictWorkshop();
        var inventory=new WorkshopClosureDeletionInventory(owner,new DataSourceTransactionManager(owner.getDataSource()),Clock.systemUTC());
        var result=inventory.inspect(actor.tallerId(),closure);
        assertThat(result.count(WorkshopClosureDeletionInventory.Metric.PRIVATE_PHOTOS_DELETED_WITHOUT_RECEIPT)).isEqualTo(1);
        assertThat(result.count(WorkshopClosureDeletionInventory.Metric.PRIVATE_PHOTO_DELETION_TARGETS)).isZero();
        assertThat(result.count(WorkshopClosureDeletionInventory.Metric.PRIVATE_PHOTO_DELETIONS_CONFIRMED)).isZero();
        assertThat(result.categories().get(WorkshopClosureDeletionInventory.Category.PRIVATE_PHOTOS).reviewReasons())
                .contains(WorkshopClosureDeletionInventory.ReviewReason.PHOTO_DELETION_RECONCILIATION_REQUIRED);
    }

    private static void assertSqlGuard(Runnable action,String message) {
        Throwable failure=catchThrowable(action::run);
        assertThat(failure).isInstanceOf(org.springframework.dao.DataAccessException.class);
        var sql=(org.postgresql.util.PSQLException)((org.springframework.dao.DataAccessException)failure).getMostSpecificCause();
        assertThat(sql.getSQLState()).isEqualTo("23514");
        assertThat(sql.getServerErrorMessage().getMessage()).contains(message);
    }

    static final class MemoryStorage implements PrivatePhotoStorage {
        final Map<String,byte[]> assets=new ConcurrentHashMap<>();final AtomicInteger uploads=new AtomicInteger(),reads=new AtomicInteger();
        Runnable afterUpload=()->{},beforeDelete=()->{},afterDelete=()->{};
        final Set<String> permanentDeleteFailures=new HashSet<>();final List<String> deleteAttempts=new CopyOnWriteArrayList<>(),deleteIdentities=new CopyOnWriteArrayList<>();
        final AtomicBoolean failUpload=new AtomicBoolean(),failDelete=new AtomicBoolean(),hideLookup=new AtomicBoolean(),failDeleteAfterEffect=new AtomicBoolean();
        @Override public StoredAsset upload(String key,String mime,byte[] content){uploads.incrementAndGet();assets.put(key,content.clone());afterUpload.run();if(failUpload.getAndSet(false))throw new PrivatePhotoStorageException(PrivatePhotoStorageException.Reason.UNAVAILABLE);return asset(key);}
        @Override public Optional<StoredAsset> find(String key){return !hideLookup.get() && assets.containsKey(key)?Optional.of(asset(key)):Optional.empty();}
        StoredAsset asset(String key){return new StoredAsset("asset-"+key.substring(key.lastIndexOf('/')+1),key,"image/png",assets.get(key).length,"1");}
        @Override public byte[] read(StoredAsset expected,int max){reads.incrementAndGet();return assets.get(expected.objectKey()).clone();}
        @Override public void delete(String key,String id){
            beforeDelete.run();deleteAttempts.add(key);deleteIdentities.add(id);
            if(permanentDeleteFailures.contains(key) || failDelete.getAndSet(false))throw new PrivatePhotoStorageException(PrivatePhotoStorageException.Reason.UNAVAILABLE);
            assets.remove(key);afterDelete.run();
            if(failDeleteAfterEffect.getAndSet(false))throw new PrivatePhotoStorageException(PrivatePhotoStorageException.Reason.UNAVAILABLE);
        }
    }
}
