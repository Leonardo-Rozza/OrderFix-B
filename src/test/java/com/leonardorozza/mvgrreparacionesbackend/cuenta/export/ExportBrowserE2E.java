package com.leonardorozza.mvgrreparacionesbackend.cuenta.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;
import static org.assertj.core.api.Assertions.*;

/** Opt-in browser gate. Real Tomcat, JWT/BCrypt, CORS, PostgreSQL and downloads; no HTTP/provider mocks.
 * Only the scheduler trigger is accelerated by the owning test loop; job operations are unchanged. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties = {
        "server.address=127.0.0.1", "server.shutdown=immediate", "security.rate-limit.enabled=true", "app.cors.allowed-origins=http://127.0.0.1:5177", "exports.jobs.enabled=true", "exports.jobs.active-key-version=1", "exports.jobs.key-versions=1",
        "exports.jobs.keys.v1=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "spring.config.import=", "spring.config.additional-location=",
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
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ExportBrowserE2E {
    @Container static final PostgreSQLContainer PG=new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_export_browser").withUsername("ordenfix").withPassword("ordenfix");
    @Autowired JdbcTemplate jdbc;
    @Autowired ExportJobService jobs;
    @Autowired ExportWorkPermit permit;
    @Autowired PasswordEncoder encoder;
    @MockitoBean(name="exportJobWorker") ExportJobConfiguration.Worker scheduler;
    @LocalServerPort int port;
    private final ObjectMapper json=new ObjectMapper();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",PG::getJdbcUrl); registry.add("spring.datasource.username",PG::getUsername);
        registry.add("spring.datasource.password",PG::getPassword); registry.add("spring.datasource.driver-class-name",PG::getDriverClassName);
        registry.add("spring.flyway.enabled",()->"true"); registry.add("spring.jpa.hibernate.ddl-auto",()->"validate");
        registry.add("spring.jpa.properties.hibernate.dialect",()->"org.hibernate.dialect.PostgreSQLDialect");
    }
    @Test void browserRequestsRecoversAndDownloadsBothFormatsWithRealPostgres() throws Exception {
        String runId=UUID.randomUUID().toString();
        List<Map<String,String>> accounts=new ArrayList<>();
        for(String viewport:List.of("desktop","mobile-320")) {
            long workshop=jdbc.queryForObject("INSERT INTO talleres(nombre,telefono) VALUES('Taller de exportación de prueba','1100000000') RETURNING id",Long.class);
            String email=runId+"-"+viewport+"@export.synthetic.invalid", password="Export-browser-synthetic-123";
            jdbc.update("INSERT INTO users(username,email,password,role,taller_id,active,email_verificado,token_version) VALUES('Titular de prueba',?,?,'ADMIN',?,true,true,0)",email,encoder.encode(password),workshop);
            String customer="CLIENTE_PROPIO_"+viewport;
            jdbc.update("INSERT INTO clientes(nombre,apellido,telefono,taller_id) VALUES(?,'Prueba','1100000000',?)",customer,workshop);
            accounts.add(Map.of("email",email,"password",password,"cliente",customer,"viewport",viewport));
        }
        Path frontend=Path.of(System.getProperty("ordenfix.browser.frontend","../mvgr-reparaciones-frontend")).toRealPath();
        assertThat(frontend.resolve("playwright.export-real.config.ts")).isRegularFile();
        Path output=Files.createTempDirectory("ordenfix-export-browser-");
        Path report=output.resolve("report.json"), log=output.resolve("playwright.log");
        ProcessBuilder builder=new ProcessBuilder("npm","exec","--","playwright","test","--config=playwright.export-real.config.ts")
                .directory(frontend.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("ORDENFIX_EXPORT_E2E_API_URL","http://127.0.0.1:"+port);
        builder.environment().put("ORDENFIX_EXPORT_E2E_ACCOUNTS_JSON",json.writeValueAsString(accounts));
        builder.environment().put("ORDENFIX_EXPORT_E2E_REPORT",report.toString());
        builder.environment().put("ORDENFIX_EXPORT_E2E_RUN_ID",runId);
        Process process=builder.start(); Map<Long,ProcessIdentity> descendants=new LinkedHashMap<>();
        try {
            long deadline=System.nanoTime()+Duration.ofMinutes(4).toNanos();
            while(process.isAlive() && System.nanoTime()<deadline) {
                process.descendants().forEach(handle->descendants.putIfAbsent(handle.pid(),new ProcessIdentity(handle,handle.info().startInstant())));
                try(var slot=permit.tryAcquire()) { if(slot!=null) jobs.runNext(); }
                process.waitFor(200,TimeUnit.MILLISECONDS);
            }
            assertThat(process.isAlive()).as("Browser timed out; evidence: %s",log).isFalse();
            assertThat(process.exitValue()).as("Browser failed; evidence: %s%n%s",log,tail(log)).isZero();
            var rows=json.readTree(Files.readAllBytes(report)); assertThat(rows.isArray()).isTrue(); assertThat(rows.size()).isEqualTo(2);
            Set<String> views=new HashSet<>();
            for(var row:rows) {
                String viewport=row.path("viewport").asText(); assertThat(views.add(viewport)).isTrue();
                assertThat(row.path("runId").asText()).isEqualTo(runId);
                String own="CLIENTE_PROPIO_"+viewport, foreign="CLIENTE_PROPIO_"+(viewport.equals("desktop")?"mobile-320":"desktop");
                Path zipPath=Path.of(row.path("zipPath").asText()).toRealPath();
                Path excelPath=Path.of(row.path("excelPath").asText()).toRealPath();
                assertThat(zipPath.startsWith(output.toRealPath())).isTrue(); assertThat(excelPath.startsWith(output.toRealPath())).isTrue();
                byte[] zipBytes=Files.readAllBytes(zipPath), excelBytes=Files.readAllBytes(excelPath);
                assertThat(ExportFile.digest(zipBytes)).isEqualTo(row.path("zipSha256").asText());
                assertThat(ExportFile.digest(excelBytes)).isEqualTo(row.path("excelSha256").asText());
                Map<String,byte[]> files=unzip(zipBytes);
                assertThat(json.readTree(files.get("manifest.json")).path("exportacion_integral_completa").asBoolean()).isTrue();
                assertThat(new String(files.get("datos/clientes.json"),StandardCharsets.UTF_8)).contains(own).doesNotContain(foreign);
                try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(excelBytes))) {
                    var text=new StringBuilder(); workbook.getSheet("Clientes").forEach(r->r.forEach(cell->text.append(cell.toString())));
                    assertThat(text.toString()).contains(own).doesNotContain(foreign);
                }
                UUID id=UUID.fromString(row.path("jobId").asText());
                assertThat(jdbc.queryForObject("SELECT estado FROM cuenta_exportaciones WHERE id=?",String.class,id)).isEqualTo("READY");
            }
            assertThat(views).containsExactlyInAnyOrder("desktop","mobile-320");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM cuenta_exportaciones",Long.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM cuenta_reautenticaciones WHERE usada_en IS NOT NULL",Long.class)).isEqualTo(4);
            System.out.println("Export browser gate passed; synthetic evidence: "+output);
        } finally {
            process.descendants().forEach(handle->descendants.putIfAbsent(handle.pid(),new ProcessIdentity(handle,handle.info().startInstant())));
            for(var identity:descendants.values()) identity.stop(false);
            process.destroy(); process.waitFor(3,TimeUnit.SECONDS);
            for(var identity:descendants.values()) identity.stop(true);
            if(process.isAlive()) process.destroyForcibly();
        }
    }
    private static String tail(Path log)throws IOException {
        List<String> lines=Files.readAllLines(log); return String.join("\n",lines.subList(Math.max(0,lines.size()-35),lines.size()));
    }
    private static Map<String,byte[]> unzip(byte[] bytes)throws IOException {
        Map<String,byte[]> entries=new LinkedHashMap<>(); try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry()) entries.put(entry.getName(),zip.readAllBytes());
        } return entries;
    }
    private record ProcessIdentity(ProcessHandle handle,Optional<Instant> started) {
        void stop(boolean force) {
            if(handle.isAlive() && started.isPresent() && handle.info().startInstant().equals(started)) {
                if(force) handle.destroyForcibly(); else handle.destroy();
            }
        }
    }
}
