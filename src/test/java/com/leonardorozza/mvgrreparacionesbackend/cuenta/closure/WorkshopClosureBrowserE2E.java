package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.export.ExportSchedulingPausedTestSupport;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.export.ExportJobService;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.reauth.ExportReauthenticationService;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.reauth.ExportReauthenticationPurpose;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import com.leonardorozza.mvgrreparacionesbackend.utils.jwt.JwtUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** Opt-in local E gate: real browser, HTTP, JWT/BCrypt and PG16. Only export scheduling is driven by this fixture. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties = {
        "ordenfix.cuenta.cierre.http-enabled=true", "server.address=127.0.0.1", "server.shutdown=immediate", "security.rate-limit.enabled=true", "app.cors.allowed-origins=http://127.0.0.1:5178", "exports.jobs.enabled=true", "exports.jobs.active-key-version=1", "exports.jobs.key-versions=1",
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
class WorkshopClosureBrowserE2E extends ExportSchedulingPausedTestSupport {
 @Container static final PostgreSQLContainer PG=new PostgreSQLContainer("postgres:16-alpine")
  .withDatabaseName("ordenfix_closure_browser").withUsername("closure").withPassword("synthetic-only");
 @DynamicPropertySource static void database(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",PG::getJdbcUrl);r.add("spring.datasource.username",PG::getUsername);
  r.add("spring.datasource.password",PG::getPassword);r.add("spring.datasource.driver-class-name",PG::getDriverClassName);
  r.add("spring.flyway.enabled",()->"true");r.add("spring.jpa.hibernate.ddl-auto",()->"validate");
  r.add("spring.jpa.properties.hibernate.dialect",()->"org.hibernate.dialect.PostgreSQLDialect");
 }
 @Autowired JdbcTemplate jdbc;@Autowired PasswordEncoder encoder;@Autowired ExportJobService jobs;
 @Autowired ExportReauthenticationService reauth;@Autowired JwtUtils jwt;@Autowired UserRepository users;
 @LocalServerPort int port;
 @Test void browserClosesDownloadsDuringRestrictionAndRestoresThroughRealHttp()throws Exception {
  String runId=UUID.randomUUID().toString();var json=new ObjectMapper();
  List<Map<String,String>> accounts=new ArrayList<>();Map<String,Long> workshops=new HashMap<>();
  for(String viewport:List.of("desktop","mobile-320")){
   long workshop=jdbc.queryForObject("INSERT INTO talleres(nombre,telefono) VALUES('Taller de cierre de prueba','1100000000') RETURNING id",Long.class);
   String email=runId+"-"+viewport+"@closure.synthetic.invalid",password="Closure-browser-synthetic-123";
   String employee=runId+"-employee-"+viewport+"@closure.synthetic.invalid";
   long owner=jdbc.queryForObject("INSERT INTO users(username,email,password,role,taller_id,active,email_verificado,token_version) VALUES('Titular de prueba',?,?,'ADMIN',?,true,true,0) RETURNING id",Long.class,email,encoder.encode(password),workshop);
   jdbc.update("INSERT INTO users(username,email,password,role,taller_id,active,email_verificado,token_version) VALUES('Empleado de prueba',?,?,'USER',?,true,true,0)",employee,encoder.encode(password),workshop);
   jdbc.update("INSERT INTO suscripciones(taller_id,plan,estado,fecha_inicio) VALUES(?,'FREE','TRIAL',CURRENT_DATE)",workshop);
   jdbc.update("INSERT INTO clientes(nombre,apellido,telefono,taller_id) VALUES(?,'Prueba','1100000000',?)","CLIENTE_CIERRE_"+viewport,workshop);
   String access=jwt.generateToken(new AuthenticatedUserPrincipal(users.findSessionByIdAndTallerId(owner,workshop).orElseThrow()),workshop);
   var job=jobs.request(access,reauth.issue(access,password,ExportReauthenticationPurpose.EXPORTAR).token(),UUID.randomUUID());
   assertThat(jobs.runNext()).isTrue();assertThat(jobs.status(access,job.id()).state()).isEqualTo("READY");
   accounts.add(Map.of("email",email,"password",password,"employee",employee,"viewport",viewport,"jobId",job.id().toString()));workshops.put(viewport,workshop);
  }
  Path frontend=Path.of(System.getProperty("ordenfix.browser.frontend","../mvgr-reparaciones-frontend")).toRealPath();
  assertThat(frontend.resolve("playwright.closure-real.config.ts")).isRegularFile();
  Path output=Files.createTempDirectory("ordenfix-closure-browser-");Path report=output.resolve("report.json"),log=output.resolve("playwright.log");
  var builder=new ProcessBuilder("npm","exec","--","playwright","test","--config=playwright.closure-real.config.ts")
   .directory(frontend.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
  builder.environment().put("ORDENFIX_CLOSURE_E2E_API_URL","http://127.0.0.1:"+port);
  builder.environment().put("ORDENFIX_CLOSURE_E2E_ACCOUNTS_JSON",json.writeValueAsString(accounts));
  builder.environment().put("ORDENFIX_CLOSURE_E2E_REPORT",report.toString());builder.environment().put("ORDENFIX_CLOSURE_E2E_RUN_ID",runId);
  Process process=builder.start();Map<Long,ProcessIdentity> descendants=new LinkedHashMap<>();
  try{
   long deadline=System.nanoTime()+Duration.ofMinutes(5).toNanos();
   while(process.isAlive()&&System.nanoTime()<deadline){
    process.descendants().forEach(handle->descendants.putIfAbsent(handle.pid(),new ProcessIdentity(handle,handle.info().startInstant())));
    process.waitFor(200,TimeUnit.MILLISECONDS);
   }
   assertThat(process.isAlive()).as("Browser timeout; %s",log).isFalse();
   assertThat(process.exitValue()).as("Browser failed; %s%n%s",log,tail(log)).isZero();
   var rows=json.readTree(Files.readAllBytes(report));assertThat(rows.isArray()).isTrue();assertThat(rows.size()).isEqualTo(2);
   Set<String> views=new HashSet<>();
   for(var row:rows){
    String viewport=row.path("viewport").asText();assertThat(views.add(viewport)).isTrue();assertThat(row.path("runId").asText()).isEqualTo(runId);
    Long workshop=workshops.get(viewport);assertThat(workshop).isNotNull();
    UUID reference=UUID.fromString(row.path("reference").asText()),restore=UUID.fromString(row.path("restoreOperation").asText());
    assertThat(jdbc.queryForObject("SELECT cierre_estado FROM talleres WHERE id=?",String.class,workshop)).isEqualTo("ABIERTO");
    assertThat(jdbc.queryForList("SELECT token_version FROM users WHERE taller_id=?",Long.class,workshop)).containsOnly(2L);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM cuenta_cierre_operaciones WHERE taller_id=?",Long.class,workshop)).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT estado FROM cuenta_cierres WHERE referencia=? AND taller_id=?",String.class,reference,workshop)).isEqualTo("RESTAURADO");
    assertThat(jdbc.queryForObject("SELECT proposito FROM cuenta_cierre_operaciones WHERE operacion_id=? AND taller_id=?",String.class,restore,workshop)).isEqualTo("RESTAURAR");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM cuenta_cierre_efectos WHERE taller_id=? AND estado='PENDIENTE'",Long.class,workshop)).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM clientes WHERE taller_id=?",Long.class,workshop)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT estado FROM cuenta_exportaciones WHERE id=?",String.class,UUID.fromString(row.path("jobId").asText()))).isEqualTo("REVOKED");
   }
   assertThat(views).containsExactlyInAnyOrder("desktop","mobile-320");
   System.out.println("Closure browser gate passed; synthetic evidence: "+output);
  }finally{
   process.descendants().forEach(handle->descendants.putIfAbsent(handle.pid(),new ProcessIdentity(handle,handle.info().startInstant())));
   for(var identity:descendants.values())identity.stop(false);process.destroy();process.waitFor(3,TimeUnit.SECONDS);
   for(var identity:descendants.values())identity.stop(true);if(process.isAlive())process.destroyForcibly();
  }
 }
 private static String tail(Path log)throws IOException {var lines=Files.readAllLines(log);return String.join("\n",lines.subList(Math.max(0,lines.size()-35),lines.size()));}
 private record ProcessIdentity(ProcessHandle handle,Optional<Instant> started){void stop(boolean force){
  if(handle.isAlive()&&started.isPresent()&&handle.info().startInstant().equals(started)){if(force)handle.destroyForcibly();else handle.destroy();}
 }}
}
