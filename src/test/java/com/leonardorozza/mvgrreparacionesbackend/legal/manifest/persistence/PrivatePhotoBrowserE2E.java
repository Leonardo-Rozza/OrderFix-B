package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.MvgrReparacionesBackendApplication;
import com.leonardorozza.mvgrreparacionesbackend.photos.storage.PrivatePhotoStorage;
import com.leonardorozza.mvgrreparacionesbackend.photos.storage.PrivatePhotoStorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in browser gate: its name matches neither the default *Test nor *IT patterns.
 * Real Boot/Tomcat, PostgreSQL and browser; the sole external-provider replacement stores actual
 * image bytes in this test's temporary directory and loses its first successful upload ACK.
 * Explicit synthetic-only opt-in selects the real Cloudinary adapter under the same lost-ACK guard;
 * the default laboratory never reads provider credentials or contacts Cloudinary. */
@SpringBootTest(classes={PrivatePhotoBrowserE2E.LocalStorageConfiguration.class, MvgrReparacionesBackendApplication.class},
        webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"spring.config.import=", "spring.config.additional-location=",
                "spring.config.location=optional:classpath:/application.properties"})
@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PrivatePhotoBrowserE2E {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String APP_ROLE="photos_browser_app", APP_PASSWORD="photos-browser-synthetic-app";
    private static final String PASSWORD="Clave-sintetica-E2E-123";
    private static final String RUN_ID=UUID.randomUUID().toString();
    private static final String FRONTEND_ORIGIN="http://127.0.0.1:5176";
    private static final List<String> BUSINESS_TABLES=List.of("users","talleres","suscripciones","auth_tokens",
            "clientes","equipos","reparaciones","repuestos","reparacion_fotos","presupuestos","presupuesto_items",
            "articulos","cobros","subscription_provider_links","payment_events","subscription_payments","taller_qr_cobro");
    private static final List<String> UNCHANGED_TABLES=List.of("users","auth_tokens","repuestos","reparacion_fotos",
            "presupuestos","presupuesto_items","articulos","cobros","subscription_provider_links","payment_events",
            "subscription_payments","taller_qr_cobro");
    private static final Map<String,Account> ACCOUNTS=new LinkedHashMap<>();
    private static JdbcTemplate owner;
    private static LegalRestrictedAcceptanceRoleFixture.Credentials privateCredentials;
    @TempDir static Path ownedDirectory;
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_acceptance_photos_browser").withUsername("ordenfix").withPassword("ordenfix");
    @Autowired JdbcTemplate applicationJdbc;
    @Autowired PrivatePhotoStorage storage;
    static BrowserCloudinaryStorage liveStorage;
    @LocalServerPort int port;

    @DynamicPropertySource static synchronized void properties(DynamicPropertyRegistry registry) throws Exception {
        BrowserCloudinaryStorage.enabled(System.getenv()); // Reject malformed opt-in before preparing the laboratory.
        if(privateCredentials==null) {
            assertThat(ownedDirectory).isDirectory();
            privateCredentials=LegalPrivatePhotoOperationsIT.prepare(POSTGRES,ownedDirectory.resolve("publication"));
            owner=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
            provisionApplicationRole();
            seedAccounts();
        }
        Map<String,String> values=new LinkedHashMap<>(LegalPrivatePhotoOperationsIT.properties(privateCredentials));
        values.put("spring.datasource.url",POSTGRES.getJdbcUrl());
        values.put("spring.datasource.username",APP_ROLE); values.put("spring.datasource.password",APP_PASSWORD);
        values.put("spring.datasource.driver-class-name","org.postgresql.Driver");
        values.put("spring.datasource.hikari.maximum-pool-size","4"); values.put("spring.datasource.hikari.minimum-idle","0");
        values.put("spring.flyway.enabled","false");
        values.put("spring.jpa.hibernate.ddl-auto","validate"); values.put("spring.jpa.open-in-view","false");
        values.put("spring.jpa.properties.hibernate.dialect","org.hibernate.dialect.PostgreSQLDialect");
        // The isolated application role keeps pg_catalog first in search_path; JPA entities live in public.
        values.put("spring.jpa.properties.hibernate.default_schema","public");
        values.put("server.address","127.0.0.1"); values.put("server.shutdown","immediate");
        values.put("server.forward-headers-strategy","none");
        values.put("server.tomcat.remoteip.protocol-header",""); values.put("server.tomcat.remoteip.remote-ip-header","");
        values.put("app.cors.allowed-origins",FRONTEND_ORIGIN); values.put("app.public-url",FRONTEND_ORIGIN);
        values.put("security.rate-limit.enabled","true"); values.put("security.rate-limit.trust-forwarded-headers","false");
        values.put("security.rate-limit.login.requests","20"); // Twelve real logins; production remains ten.
        values.put("security.jwt.secret","photos-browser-synthetic-jwt-secret-at-least-32-bytes");
        values.put("security.jwt.issuer","ordenfix-photos-browser"); values.put("security.jwt.audience","ordenfix-photos-browser-api");
        values.put("security.jwt.expiration","3600000");
        values.put("DEVICE_CREDENTIALS_ENCRYPTION_KEY",Base64.getEncoder().encodeToString("dddddddddddddddddddddddddddddddd".getBytes(StandardCharsets.US_ASCII)));
        values.put("mail.enabled","false"); values.put("mercadopago.enabled","false"); values.put("mercadopago.checkout-enabled","false");
        values.put("exports.jobs.enabled","false");
        values.put("ordenfix.legal.maintenance.enabled","false"); values.put("ordenfix.legal.maintenance.scheduled","false"); values.put("ordenfix.cuenta.cierre.http-enabled","false");
        // The photo cleanup scheduler remains real, bounded to rows in this disposable database.
        values.put("admin.user","Unused photo fixture"); values.put("admin.email","unused-photo@ordenfix-e2e.test");
        values.put("admin.password","Unused-synthetic-password-123");
        values.put("ordenfix.legal.account-read.enabled","false"); values.put("ordenfix.legal.account-acceptance.enabled","false");
        values.put("ordenfix.legal.registration-consent.enabled","false"); values.put("ordenfix.legal.registration-enforcement.enabled","false");
        values.put("ordenfix.legal.public-documents.enabled","false"); values.put("ordenfix.legal.public-requirements.enabled","false");
        values.forEach((key,value)->registry.add(key,()->value));
    }

    @Test void browserConfirmsUploadsRecoversReadsAndDeletesPrivatePhotos() throws Throwable {
        assertThat(port).isPositive();
        assertThat(applicationJdbc.queryForObject("SELECT current_user",String.class)).isEqualTo(APP_ROLE);
        assertThat(owner.queryForObject("SELECT has_table_privilege(?, 'public.legal_aceptaciones', 'INSERT')",Boolean.class,APP_ROLE)).isFalse();
        assertThat(owner.queryForObject("SELECT has_table_privilege(?, 'public.reparacion_fotos_privadas', 'INSERT')",Boolean.class,APP_ROLE)).isFalse();
        assertThat(owner.queryForObject("SELECT count(*) FROM pg_roles WHERE rolname IN (?,?) AND NOT rolsuper AND NOT rolbypassrls AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication",Long.class,
                APP_ROLE,privateCredentials.username())).isEqualTo(2);
        Path frontend=Path.of(System.getProperty("ordenfix.browser.frontend","../mvgr-reparaciones-frontend")).toRealPath();
        assertThat(frontend.resolve("playwright.photos-real.config.ts")).isRegularFile();
        Path report=ownedDirectory.resolve("browser-report.json"), log=ownedDirectory.resolve("playwright.log");
        Map<String,List<String>> unchanged=unchangedRows();
        long repairsBefore=count("reparaciones"), clientsBefore=count("clientes"), equipmentBefore=count("equipos");
        assertThat(count("reparacion_fotos_privadas")).isZero();
        assertThat(count("reparacion_foto_atestaciones")).isZero();
        Instant started=owner.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class).toInstant();
        ProcessBuilder builder=new ProcessBuilder("npm","exec","--","playwright","test","--config=playwright.photos-real.config.ts")
                .directory(frontend.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("ORDENFIX_PHOTOS_E2E_API_URL","http://127.0.0.1:"+port);
        builder.environment().put("ORDENFIX_PHOTOS_E2E_RUN_ID",RUN_ID);
        builder.environment().put("ORDENFIX_PHOTOS_E2E_REPORT",report.toString());
        builder.environment().put("ORDENFIX_PHOTOS_E2E_ACCOUNTS_JSON",JSON.writeValueAsString(browserAccounts()));
        Process process=builder.start(); Map<Long,OwnedProcess> descendants=new LinkedHashMap<>(); Throwable primary=null;
        try {
            long until=System.nanoTime()+Duration.ofMinutes(5).toNanos(); boolean finished=false;
            while(System.nanoTime()<until) {
                rememberDescendants(process,descendants);
                if(process.waitFor(200,TimeUnit.MILLISECONDS)) { finished=true; break; }
            }
            rememberDescendants(process,descendants);
            assertThat(finished).as("Playwright exceeded five minutes.\n%s",tail(log)).isTrue();
            assertThat(process.exitValue()).as("Playwright failed.\n%s",tail(log)).isZero();
            Instant ended=owner.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class).toInstant();
            verifyReport(report,started,ended);
            assertThat(count("reparaciones")).isEqualTo(repairsBefore+4);
            assertThat(count("clientes")).isEqualTo(clientsBefore+2);
            assertThat(count("equipos")).isEqualTo(equipmentBefore+2);
            assertThat(unchangedRows()).isEqualTo(unchanged);
        } catch(Throwable failure) { primary=failure; throw failure; }
        finally {
            Throwable cleanupFailure=null;
            try { stopOwnedProcesses(process,descendants); } catch(Throwable failure) { cleanupFailure=failure; }
            try { if(liveStorage!=null)liveStorage.cleanupOwned(); }
            catch(Throwable failure) { if(cleanupFailure==null)cleanupFailure=failure;else cleanupFailure.addSuppressed(failure); }
            if(cleanupFailure!=null) { if(primary!=null)primary.addSuppressed(cleanupFailure);else throw cleanupFailure; }
        }
    }

    @AfterAll static void closeLiveProvider() { if(liveStorage!=null)liveStorage.close(); }

    private static void verifyReport(Path path,Instant started,Instant finished) throws Exception {
        assertThat(path).isRegularFile(); assertThat(Files.size(path)).isBetween(1L,65_536L);
        JsonNode entries=JSON.readTree(Files.readAllBytes(path)); assertThat(entries.isArray()).isTrue(); assertThat(entries).hasSize(4);
        Set<String> scenarios=new HashSet<>(); Set<UUID> photoIds=new HashSet<>(); Set<Long> repairs=new HashSet<>();
        Set<String> fields=Set.of("runId","project","scenario","repairId","equipmentId","actorId","actorRole","workshopId",
                "peerId","foreignId","photoId","bytes","sha256","uploadFailure","finalized","deleted","ownRead","peerRead","foreignRead","anonymousRead");
        for(JsonNode entry:entries) {
            assertThat(entry.isObject()).isTrue(); assertThat(entry.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrderElementsOf(fields);
            assertThat(text(entry,"runId")).isEqualTo(RUN_ID);
            String project=text(entry,"project"), scenario=text(entry,"scenario");
            assertThat(project).isIn("desktop","mobile-320"); assertThat(scenario).isIn("nuevo","existente");
            assertThat(scenarios.add(project+":"+scenario)).isTrue();
            Account account=ACCOUNTS.get(project); boolean admin=scenario.equals("nuevo");
            long actor=admin?account.ownerId():account.employeeId(), peer=admin?account.employeeId():account.ownerId();
            assertThat(id(entry,"actorId")).isEqualTo(actor); assertThat(id(entry,"peerId")).isEqualTo(peer);
            assertThat(id(entry,"foreignId")).isEqualTo(account.otherId()); assertThat(id(entry,"workshopId")).isEqualTo(account.tallerId());
            assertThat(text(entry,"actorRole")).isEqualTo(admin?"ADMIN":"USER");
            assertThat(id(entry,"uploadFailure")).isEqualTo(503); assertThat(id(entry,"finalized")).isEqualTo(201);
            assertThat(id(entry,"deleted")).isEqualTo(204); assertThat(id(entry,"ownRead")).isEqualTo(200);
            assertThat(id(entry,"peerRead")).isEqualTo(200); assertThat(id(entry,"foreignRead")).isEqualTo(404);
            assertThat(id(entry,"anonymousRead")).isEqualTo(403);
            UUID photo=UUID.fromString(text(entry,"photoId")); assertThat(photo.toString()).isEqualTo(text(entry,"photoId")); assertThat(photoIds.add(photo)).isTrue();
            long repair=id(entry,"repairId"), equipment=id(entry,"equipmentId"); assertThat(repairs.add(repair)).isTrue();
            if(!admin)assertThat(equipment).isEqualTo(account.equipmentId());
            assertThat(owner.queryForObject("SELECT taller_id=? AND equipo_id=? FROM reparaciones WHERE id=?",Boolean.class,account.tallerId(),equipment,repair)).isTrue();
            Map<String,Object> row=owner.queryForMap("SELECT * FROM reparacion_fotos_privadas WHERE id=?",photo);
            assertThat(((Number)row.get("reparacion_id")).longValue()).isEqualTo(repair);
            assertThat(((Number)row.get("reparacion_original_id")).longValue()).isEqualTo(repair);
            assertThat(((Number)row.get("user_id")).longValue()).isEqualTo(actor);
            assertThat(((Number)row.get("taller_id")).longValue()).isEqualTo(account.tallerId());
            assertThat(row.get("rol_wire")).isEqualTo(admin?"ADMIN":"USER"); assertThat(row.get("estado")).isEqualTo("ELIMINADA");
            assertThat(row.get("nombre")).isEqualTo("foto-sintetica.png"); assertThat(row.get("mime_type")).isEqualTo("image/png");
            assertThat(row.get("momento")).isEqualTo("INGRESO"); assertThat(row.get("sha256")).isEqualTo(text(entry,"sha256"));
            assertThat(((Number)row.get("bytes")).longValue()).isEqualTo(id(entry,"bytes"));
            assertThat(row.get("object_key")).isEqualTo("ordenfix-private/"+photo);
            assertThat(row.get("asset_id")).isNull(); assertThat(row.get("asset_version")).isNull(); assertThat(row.get("lease_id")).isNull(); assertThat(row.get("lease_hasta")).isNull();
            Instant confirmed=time(photo,"confirmado_en"), associated=time(photo,"asociada_en");
            assertThat(confirmed).isBetween(started,finished); assertThat(associated).isBetween(confirmed,finished);
            assertThat(time(photo,"expira_en")).isAfter(confirmed).isBeforeOrEqualTo(confirmed.plusSeconds(900));
            assertThat(time(photo,"retener_hasta")).isAfter(associated);
            var evidence=owner.queryForList("""
                    SELECT a.id,a.contexto,a.tipo_acto,a.user_id,a.taller_id,a.afirmacion_sha256,r.afirmacion_sha256 AS source_digest,
                           a.afirmacion,r.afirmacion AS source_statement,f.alcance
                      FROM reparacion_foto_atestaciones f JOIN legal_aceptaciones a ON a.id=f.aceptacion_id
                      JOIN legal_requisito_versiones r ON r.id=a.requisito_version_id WHERE f.foto_id=?
                    """,photo);
            assertThat(evidence).hasSize(1);
            var act=evidence.getFirst(); assertThat(act.get("contexto")).isEqualTo("ATESTACION_FOTOS");
            assertThat(act.get("tipo_acto")).isEqualTo("DECLARACION"); assertThat(act.get("alcance")).isEqualTo("FOTOS");
            assertThat(((Number)act.get("user_id")).longValue()).isEqualTo(actor); assertThat(((Number)act.get("taller_id")).longValue()).isEqualTo(account.tallerId());
            assertThat(act.get("afirmacion_sha256")).isEqualTo(act.get("source_digest")); assertThat(act.get("afirmacion")).isEqualTo(act.get("source_statement"));
            assertThat(owner.queryForObject("SELECT count(*) FROM legal_aceptacion_documentos WHERE aceptacion_id=?",Long.class,act.get("id"))).isPositive();
            assertThat(owner.queryForObject("""
                    SELECT count(*) FROM legal_aceptacion_documentos ad
                      JOIN legal_documento_versiones d ON d.id=ad.documento_version_id
                     WHERE ad.aceptacion_id=? AND ad.sha256<>d.sha256
                    """,Long.class,act.get("id"))).isZero();
            storageRecord(photo.toString(),id(entry,"bytes"),text(entry,"sha256"));
        }
        assertThat(scenarios).containsExactlyInAnyOrder("desktop:nuevo","desktop:existente","mobile-320:nuevo","mobile-320:existente");
        assertThat(owner.queryForList("SELECT id FROM reparacion_fotos_privadas",UUID.class)).containsExactlyInAnyOrderElementsOf(photoIds);
        assertThat(count("reparacion_foto_atestaciones")).isEqualTo(4);
        // The real repair writer advances the workshop's annual order sequence and audit time.
        int year=started.atZone(ZoneId.systemDefault()).getYear();
        assertThat(finished.atZone(ZoneId.systemDefault()).getYear()).as("This finite run does not exercise a year rollover").isEqualTo(year);
        for(Account account:ACCOUNTS.values()) {
            assertThat(owner.queryForObject("SELECT secuencia_orden=2 AND anio_secuencia_orden=? AND updated_at IS NOT NULL FROM talleres WHERE id=?",Boolean.class,year,account.tallerId())).isTrue();
            assertThat(owner.queryForObject("SELECT t.secuencia_orden=0 AND t.anio_secuencia_orden IS NULL FROM talleres t JOIN users u ON u.taller_id=t.id WHERE u.id=?",Boolean.class,account.otherId())).isTrue();
        }
        if(liveStorage!=null) { liveStorage.verifyComplete();return; }
        LocalStorage instance=LocalStorageConfiguration.instance;
        assertThat(instance.uploadCalls).hasSize(4).allSatisfy((key,count)->assertThat(count).isEqualTo(1));
        assertThat(instance.firstWriteFailures).hasSize(4); assertThat(instance.deleted).hasSize(4);
        assertThat(instance.current).isEmpty();
        try(var files=Files.list(instance.directory)) { assertThat(files.toList()).isEmpty(); }
    }
    private static void storageRecord(String photo,long bytes,String sha) {
        String key="ordenfix-private/"+photo;
        if(liveStorage!=null) { liveStorage.verifyRecord(key,bytes,sha);return; }
        LocalStorage instance=LocalStorageConfiguration.instance;
        assertThat(instance.persisted.get(key).bytes()).isEqualTo(bytes); assertThat(instance.sha.get(key)).isEqualTo(sha);
        assertThat(instance.findCalls.getOrDefault(key,0)).as("retry recovers the already persisted asset").isPositive();
        assertThat(instance.readCalls.getOrDefault(key,0)).isPositive();
    }
    private static Map<String,Object> browserAccounts() {
        Map<String,Object> result=new LinkedHashMap<>();
        ACCOUNTS.forEach((project,a)->result.put(project,Map.of("ownerEmail",a.ownerEmail(),"employeeEmail",a.employeeEmail(),"otherEmail",a.otherEmail(),
                "equipmentId",a.equipmentId(),"equipmentLabel",a.equipmentLabel()))); return result;
    }
    private static void seedAccounts() {
        String hash=new BCryptPasswordEncoder().encode(PASSWORD);
        for(String project:List.of("desktop","mobile-320")) {
            String suffix=project+"-"+RUN_ID.substring(0,8);
            String ownEmail="p-owner-"+suffix+"@ordenfix-e2e.test", employeeEmail="p-user-"+suffix+"@ordenfix-e2e.test", otherEmail="p-other-"+suffix+"@ordenfix-e2e.test";
            long workshop=workshop("Taller fotos "+project), otherWorkshop=workshop("Taller ajeno "+project);
            long own=user("Titular "+suffix,ownEmail,hash,"ADMIN",workshop), employee=user("Empleado "+suffix,employeeEmail,hash,"USER",workshop), other=user("Ajeno "+suffix,otherEmail,hash,"ADMIN",otherWorkshop);
            long client=owner.queryForObject("INSERT INTO clientes(nombre,apellido,telefono,taller_id) VALUES('Cliente','Existente',?,?) RETURNING id",Long.class,project.equals("desktop")?"1155010801":"1155010802",workshop);
            String model=project.equals("desktop")?"Photo desktop":"Photo mobile";
            long equipment=owner.queryForObject("INSERT INTO equipos(marca,modelo,cliente_id,taller_id) VALUES('Samsung',?,?,?) RETURNING id",Long.class,model,client,workshop);
            ACCOUNTS.put(project,new Account(own,employee,other,workshop,ownEmail,employeeEmail,otherEmail,equipment,"Samsung "+model));
        }
    }
    private static long workshop(String name) {
        long id=owner.queryForObject("INSERT INTO talleres(nombre,activo,created_at,updated_at) VALUES(?,true,now(),now()) RETURNING id",Long.class,name);
        owner.update("INSERT INTO suscripciones(taller_id,plan,estado,fecha_inicio,created_at,updated_at) VALUES(?,'PRO','ACTIVA',current_date,now(),now())",id);return id;
    }
    private static long user(String name,String email,String hash,String role,long workshop) {
        return owner.queryForObject("INSERT INTO users(username,email,password,role,active,email_verificado,taller_id) VALUES(?,?,?,?,true,true,?) RETURNING id",Long.class,name,email,hash,role,workshop);
    }
    private static void provisionApplicationRole() {
        assertThat(owner.queryForObject("SELECT current_database()",String.class)).startsWith("ordenfix_legal_acceptance_");
        owner.execute("CREATE ROLE "+APP_ROLE+" LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '"+APP_PASSWORD+"'");
        owner.execute("GRANT CONNECT ON DATABASE "+POSTGRES.getDatabaseName()+" TO "+APP_ROLE);
        owner.execute("GRANT USAGE ON SCHEMA public TO "+APP_ROLE);
        owner.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON "+BUSINESS_TABLES.stream().map(t->"public."+t).collect(Collectors.joining(","))+" TO "+APP_ROLE);
        owner.execute("GRANT SELECT ON public.reparacion_fotos_privadas TO "+APP_ROLE);
        for(String table:BUSINESS_TABLES) {
            String sequence=owner.queryForObject("""
                    SELECT CASE WHEN EXISTS (SELECT 1 FROM pg_catalog.pg_attribute WHERE attrelid=pg_catalog.to_regclass(?)
                      AND attname='id' AND attnum>0 AND NOT attisdropped)
                    THEN pg_catalog.pg_get_serial_sequence(?,'id') ELSE NULL END
                    """,String.class,"public."+table,"public."+table);
            if(sequence!=null)owner.execute("GRANT USAGE,SELECT ON SEQUENCE "+sequence+" TO "+APP_ROLE);
        }
        owner.execute("ALTER ROLE "+APP_ROLE+" IN DATABASE "+POSTGRES.getDatabaseName()+" SET search_path TO pg_catalog,public,pg_temp");
    }
    private static Instant time(UUID photo,String column) {
        if(!Set.of("confirmado_en","asociada_en","expira_en","retener_hasta").contains(column))throw new AssertionError("Unknown fixture column");
        return Objects.requireNonNull(owner.queryForObject("SELECT "+column+" FROM reparacion_fotos_privadas WHERE id=?",OffsetDateTime.class,photo)).toInstant();
    }
    private static long count(String table) { return owner.queryForObject("SELECT count(*) FROM public."+table,Long.class); }
    private static Map<String,List<String>> unchangedRows() {
        Map<String,List<String>> result=new LinkedHashMap<>();
        for(String table:UNCHANGED_TABLES)result.put(table,owner.queryForList("SELECT to_jsonb(t)::text || ':' || xmin::text FROM public."+table+" t ORDER BY 1",String.class));
        result.put("talleres-stable",owner.queryForList("SELECT (to_jsonb(t)-'secuencia_orden'-'anio_secuencia_orden'-'updated_at')::text FROM talleres t ORDER BY 1",String.class));
        return result;
    }
    private static String text(JsonNode node,String field) { assertThat(node.path(field).isTextual()).as(field).isTrue();String value=node.path(field).textValue();assertThat(value).isNotBlank();return value; }
    private static long id(JsonNode node,String field) { assertThat(node.path(field).isIntegralNumber()&&node.path(field).canConvertToLong()).as(field).isTrue();long value=node.path(field).longValue();assertThat(value).isPositive();return value; }

    @TestConfiguration(proxyBeanMethods=false)
    static class LocalStorageConfiguration {
        static LocalStorage instance;
        @Bean(destroyMethod="") PrivatePhotoStorage privatePhotoStorage() throws IOException {
            if(BrowserCloudinaryStorage.enabled(System.getenv())) {
                liveStorage=BrowserCloudinaryStorage.open(System.getenv(),UUID.fromString(RUN_ID),PrivatePhotoBrowserE2E::ownedPhoto);
                return liveStorage;
            }
            instance=new LocalStorage(ownedDirectory.resolve("assets"));return instance;
        }
    }
    /** Admission is tied to rows of this container and these freshly seeded actors, not a provider prefix. */
    private static BrowserCloudinaryStorage.Expected ownedPhoto(UUID id) {
        assertThat(owner.queryForObject("SELECT current_database()",String.class)).isEqualTo(POSTGRES.getDatabaseName());
        var rows=owner.queryForList("""
                SELECT p.taller_id,p.user_id,p.rol_wire,p.bytes,p.sha256,p.mime_type,p.nombre,p.momento,p.object_key,
                       r.taller_id AS repair_workshop,u.taller_id AS user_workshop,u.role,u.active,u.email_verificado
                  FROM reparacion_fotos_privadas p JOIN reparaciones r ON r.id=p.reparacion_id
                  JOIN users u ON u.id=p.user_id WHERE p.id=?
                """,id);
        if(rows.size()!=1)throw new IllegalStateException("Photo is outside this synthetic laboratory.");
        var row=rows.getFirst();
        long workshop=((Number)row.get("taller_id")).longValue(), actor=((Number)row.get("user_id")).longValue();
        Account account=ACCOUNTS.values().stream().filter(a->a.tallerId()==workshop).findFirst().orElseThrow(()->new IllegalStateException("Unknown synthetic workshop."));
        boolean ownAdmin=actor==account.ownerId(), ownEmployee=actor==account.employeeId();
        String role=ownAdmin?"ADMIN":"USER";
        if((!ownAdmin&&!ownEmployee) || ((Number)row.get("repair_workshop")).longValue()!=workshop
                || ((Number)row.get("user_workshop")).longValue()!=workshop || !role.equals(row.get("role"))
                || !role.equals(row.get("rol_wire")) || !Boolean.TRUE.equals(row.get("active"))
                || !Boolean.TRUE.equals(row.get("email_verificado")) || !"foto-sintetica.png".equals(row.get("nombre"))
                || !"INGRESO".equals(row.get("momento")) || !("ordenfix-private/"+id).equals(row.get("object_key")))
            throw new IllegalStateException("Photo is outside this synthetic laboratory.");
        return new BrowserCloudinaryStorage.Expected(((Number)row.get("bytes")).longValue(),(String)row.get("sha256"),(String)row.get("mime_type"));
    }

    static final class LocalStorage implements PrivatePhotoStorage {
        final Path directory;
        final Map<String,StoredAsset> current=new LinkedHashMap<>(),persisted=new LinkedHashMap<>();
        final Map<String,Integer> uploadCalls=new LinkedHashMap<>(),findCalls=new LinkedHashMap<>(),readCalls=new LinkedHashMap<>();
        final Map<String,String> sha=new LinkedHashMap<>();
        final Set<String> firstWriteFailures=new HashSet<>(),deleted=new HashSet<>();
        LocalStorage(Path directory) throws IOException { this.directory=Files.createDirectory(directory); }
        @Override public synchronized StoredAsset upload(String key,String mime,byte[] bytes) {
            uploadCalls.merge(key,1,Integer::sum);
            if(current.containsKey(key))return current.get(key);
            Path path=path(key);
            try {
                Files.write(path,bytes,StandardOpenOption.CREATE_NEW);
                StoredAsset receipt=new StoredAsset(UUID.randomUUID().toString(),key,mime,bytes.length,"1");
                current.put(key,receipt);persisted.put(key,receipt);sha.put(key,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
                if(firstWriteFailures.add(key))throw new PrivatePhotoStorageException(PrivatePhotoStorageException.Reason.UNAVAILABLE);
                return receipt;
            } catch(PrivatePhotoStorageException failure) { throw failure; }
            catch(Exception failure) { throw new IllegalStateException("Local photo fixture failed",failure); }
        }
        @Override public synchronized Optional<StoredAsset> find(String key) { path(key);findCalls.merge(key,1,Integer::sum);return Optional.ofNullable(current.get(key)); }
        @Override public synchronized byte[] read(StoredAsset expected,int maxBytes) {
            assertThat(current.get(expected.objectKey())).isEqualTo(expected);readCalls.merge(expected.objectKey(),1,Integer::sum);
            try { byte[] bytes=Files.readAllBytes(path(expected.objectKey()));assertThat(bytes.length).isLessThanOrEqualTo(maxBytes);return bytes; }
            catch(IOException failure) { throw new IllegalStateException("Local photo fixture failed",failure); }
        }
        @Override public synchronized void delete(String key,String assetId) {
            StoredAsset found=current.get(key); if(found==null)return;
            assertThat(found.assetId()).isEqualTo(assetId);
            try { Files.delete(path(key));current.remove(key);deleted.add(key); }
            catch(IOException failure) { throw new IllegalStateException("Local photo fixture failed",failure); }
        }
        private Path path(String key) { assertThat(key).matches("ordenfix-private/[0-9a-f-]{36}");UUID id=UUID.fromString(key.substring("ordenfix-private/".length()));assertThat(key).endsWith(id.toString());return directory.resolve(id.toString()+".bin"); }
    }
    private record Account(long ownerId,long employeeId,long otherId,long tallerId,String ownerEmail,String employeeEmail,String otherEmail,long equipmentId,String equipmentLabel) { }

    private static void rememberDescendants(Process process, Map<Long, OwnedProcess> owned) {
        process.descendants().forEach(handle -> owned.putIfAbsent(handle.pid(), new OwnedProcess(handle, handle.info().startInstant())));
    }
    private static void stopOwnedProcesses(Process process, Map<Long, OwnedProcess> owned) throws Exception {
        rememberDescendants(process, owned);
        if (process.isAlive()) process.destroy();
        owned.values().stream().filter(OwnedProcess::stillOwnedAndAlive).forEach(value -> value.handle().destroy());
        long gracefulUntil = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < gracefulUntil && (process.isAlive() || owned.values().stream().anyMatch(OwnedProcess::stillOwnedAndAlive))) {
            process.waitFor(100, TimeUnit.MILLISECONDS);
            if (!process.isAlive()) TimeUnit.MILLISECONDS.sleep(50);
        }
        if (process.isAlive()) process.destroyForcibly();
        owned.values().stream().filter(OwnedProcess::stillOwnedAndAlive).forEach(value -> value.handle().destroyForcibly());
        process.waitFor(3, TimeUnit.SECONDS);
        long forcedUntil = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < forcedUntil && owned.values().stream().anyMatch(OwnedProcess::stillOwnedAndAlive)) TimeUnit.MILLISECONDS.sleep(50);
        assertThat(process.isAlive()).as("owned npm process must terminate").isFalse();
        assertThat(owned.values()).noneMatch(OwnedProcess::stillOwnedAndAlive);
        assertThat(owned.values()).as("process cleanup incomplete: a live process has no verifiable start identity")
                .noneMatch(OwnedProcess::identityUnavailableWhileAlive);
    }
    private static String tail(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return "No Playwright log was produced.";
        try (var file = new java.io.RandomAccessFile(path.toFile(), "r")) {
            file.seek(Math.max(0, file.length() - 16_384));
            byte[] bytes = new byte[(int) (file.length() - file.getFilePointer())]; file.readFully(bytes);
            String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\\R");
            return String.join("\n", Arrays.copyOfRange(lines, Math.max(0, lines.length - 60), lines.length));
        }
    }
    private record OwnedProcess(ProcessHandle handle, java.util.Optional<Instant> started) {
        boolean stillOwnedAndAlive() {
            return handle.isAlive() && started.isPresent() && handle.info().startInstant().equals(started);
        }
        boolean identityUnavailableWhileAlive() {
            return handle.isAlive() && (started.isEmpty() || handle.info().startInstant().isEmpty());
        }
    }
}
