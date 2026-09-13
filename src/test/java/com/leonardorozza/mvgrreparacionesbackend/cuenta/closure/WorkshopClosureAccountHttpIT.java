package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.export.ExportSchedulingPausedTestSupport;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.export.ExportJobService;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import com.leonardorozza.mvgrreparacionesbackend.utils.jwt.JwtUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** E: actual Spring Security, JWT/password checks, transaction services and frozen PostgreSQL guards. */
@SpringBootTest(properties={
 "ordenfix.cuenta.cierre.http-enabled=true", "security.rate-limit.enabled=false",
 "app.cors.allowed-origins=http://localhost:5173", "exports.jobs.enabled=true",
 "exports.jobs.active-key-version=1", "exports.jobs.key-versions=1", "exports.jobs.keys.v1=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
 "spring.config.import=", "spring.config.additional-location=", "spring.config.location=optional:classpath:/application.properties",
 "mail.enabled=false", "mercadopago.enabled=false", "mercadopago.checkout-enabled=false", "photos.private.enabled=false",
 "ordenfix.legal.registration-consent.enabled=false", "ordenfix.legal.registration-enforcement.enabled=false",
 "ordenfix.legal.account-read.enabled=false", "ordenfix.legal.account-acceptance.enabled=false",
 "ordenfix.legal.public-documents.enabled=false", "ordenfix.legal.public-requirements.enabled=false",
 "ordenfix.legal.aggregate-context.enabled=false", "ordenfix.legal.editorial-context.enabled=false",
 "ordenfix.legal.import-context.enabled=false", "ordenfix.legal.dry-run-context.enabled=false",
 "ordenfix.legal.public-document-read-context.enabled=false", "ordenfix.legal.public-requirements-context.enabled=false"})
@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class WorkshopClosureAccountHttpIT extends ExportSchedulingPausedTestSupport {
 @Container static final PostgreSQLContainer PG=new PostgreSQLContainer("postgres:16-alpine")
  .withDatabaseName("ordenfix_closure_account_http").withUsername("closure").withPassword("fixture-only");
 @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
  r.add("spring.datasource.url",PG::getJdbcUrl); r.add("spring.datasource.username",PG::getUsername);
  r.add("spring.datasource.password",PG::getPassword); r.add("spring.datasource.driver-class-name",PG::getDriverClassName);
  r.add("spring.flyway.enabled",()->"true");r.add("spring.jpa.hibernate.ddl-auto",()->"validate");
  r.add("spring.jpa.properties.hibernate.dialect",()->"org.hibernate.dialect.PostgreSQLDialect");
 }
 @TestConfiguration static class Configuration { @Bean @Primary AdjustableClock closureAccountClock(){return new AdjustableClock();} }
 static final class AdjustableClock extends Clock {
  Instant now=Instant.now(); public Instant instant(){return now;} public ZoneId getZone(){return ZoneOffset.UTC;}
  public Clock withZone(ZoneId zone){return this;}
 }
 @Autowired WebApplicationContext context; @Autowired FilterChainProxy security; @Autowired JdbcTemplate jdbc;
 @Autowired UserRepository users; @Autowired JwtUtils jwt; @Autowired PasswordEncoder passwords;
 @Autowired ExportJobService jobs; @Autowired AdjustableClock clock;
 @MockitoSpyBean WorkshopClosureEffects effects;
 private WorkshopClosureEffects effectsTarget;
 private MockMvc mvc; private Actor own; private String access;
 private final ObjectMapper json=new ObjectMapper();
 private static final String PASSWORD="Closure-account-http-synthetic-123", BASE="/api/cuenta/cierre";
 @BeforeEach void fixture(){
  clock.now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  effectsTarget=AopTestUtils.getUltimateTargetObject(effects);reset(effectsTarget);
  mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(security).build(); own=actor("ADMIN",true);access=token(own);
 }
 @AfterEach void resetEffects(){reset(effectsTarget);}

 @Test void statusCloseReloginReplayRestoreAndLatestReceiptAreConsistent() throws Exception {
  var before=fingerprint();
  auth(get(BASE),access).andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("ABIERTO"))
   .andExpect(jsonPath("$.puedeSolicitar").value(true)).andExpect(jsonPath("$.puedeRestaurar").value(false))
   .andExpect(jsonPath("$.ultimaOperacion").isEmpty()).andExpect(header().string("Cache-Control",containsString("no-store")));
  assertThat(fingerprint()).isEqualTo(before);
  UUID close=UUID.randomUUID();String proof=grant(access,"CERRAR",close,close);
  var closed=node(command(access,proof,"CERRAR",close,close).andExpect(status().isOk()));
  assertThat(closed.path("estadoResultante").asText()).isEqualTo("RESTRINGIDO");
  assertThat(closed.path("reutilizada").asBoolean()).isFalse();
  auth(get(BASE),access).andExpect(status().isUnauthorized());
  String restricted=login(own); var after=fingerprint();
  auth(get("/api/perfil"),restricted).andExpect(status().isOk()).andExpect(jsonPath("$.accesoTaller").value("RESTRICTED"));
  auth(get(BASE),restricted).andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("RESTRINGIDO"))
   .andExpect(jsonPath("$.puedeSolicitar").value(false)).andExpect(jsonPath("$.puedeRestaurar").value(true))
   .andExpect(jsonPath("$.referencia").value(close.toString())).andExpect(jsonPath("$.ultimaOperacion.operacionId").value(close.toString()));
  assertThat(fingerprint()).isEqualTo(after);
  command(restricted,proof,"CERRAR",close,close).andExpect(status().isOk()).andExpect(jsonPath("$.reutilizada").value(true));
  assertThat(fingerprint()).isEqualTo(after);
  auth(get("/api/reparaciones"),restricted).andExpect(status().isLocked());
  UUID restore=UUID.randomUUID();String restoreProof=grant(restricted,"RESTAURAR",restore,close);
  command(restricted,restoreProof,"RESTAURAR",restore,close).andExpect(status().isOk()).andExpect(jsonPath("$.estadoResultante").value("ABIERTO"));
  auth(get(BASE),restricted).andExpect(status().isUnauthorized());
  String reopened=login(own);
  auth(get(BASE),reopened).andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("ABIERTO"))
   .andExpect(jsonPath("$.ultimaOperacion.proposito").value("RESTAURAR")).andExpect(jsonPath("$.referencia").isEmpty());
  auth(get("/api/perfil"),reopened).andExpect(status().isOk()).andExpect(jsonPath("$.accesoTaller").value("OPERATIVE"));
  assertThat(jdbc.queryForObject("SELECT count(*) FROM cuenta_cierre_operaciones WHERE taller_id=?",Long.class,own.workshop())).isEqualTo(2);
 }
 @ParameterizedTest @ValueSource(strings={"USER","UNVERIFIED","ANONYMOUS"})
 void authorityPrecedesBodyParsingAndNoConfirmationIsIssued(String kind)throws Exception {
  String denied=kind.equals("ANONYMOUS")?"malformed":token(actor(kind.equals("USER")?"USER":"ADMIN",!kind.equals("UNVERIFIED")));
  int status=kind.equals("ANONYMOUS")?401:403;var before=fingerprint();
  auth(get(BASE),denied).andExpect(status().is(status));
  auth(post(BASE+"/reauthenticaciones").contentType(APPLICATION_JSON).content("{"),denied).andExpect(status().is(status));
  assertThat(fingerprint()).isEqualTo(before);
 }
 @Test void foreignWorkshopDoesNotSeeOrReuseAnOwnReceipt()throws Exception {
  UUID operation=UUID.randomUUID();command(access,grant(access,"CERRAR",operation,operation),"CERRAR",operation,operation).andExpect(status().isOk());
  Actor foreign=actor("ADMIN",true);String other=token(foreign);
  var view=node(auth(get(BASE),other).andExpect(status().isOk()));
  assertThat(view.path("ultimaOperacion").isNull()).isTrue();assertThat(view.toString()).doesNotContain(operation.toString(),own.email());
  String proof=grant(other,"CERRAR",operation,operation);var before=fingerprint();
  command(other,proof,"CERRAR",operation,operation).andExpect(status().isConflict());assertThat(fingerprint()).isEqualTo(before);
 }
 @Test void failureAfterOutboxWorkRollsBackProofEpochHistoryAndReceipt()throws Exception {
  UUID operation=UUID.randomUUID();String proof=grant(access,"CERRAR",operation,operation);var before=fingerprint();
  doAnswer(call->{call.callRealMethod();throw new IllegalStateException("fixture private diagnostic");})
   .when(effectsTarget).enqueueClose(any(),any(),anyLong(),anyLong(),any());
  var response=command(access,proof,"CERRAR",operation,operation).andExpect(status().isServiceUnavailable()).andReturn().getResponse();
  assertThat(response.getContentAsString()).doesNotContain(proof,PASSWORD,access,"fixture private diagnostic");
  assertThat(fingerprint()).isEqualTo(before);reset(effectsTarget);
  command(access,proof,"CERRAR",operation,operation).andExpect(status().isOk());
 }
 @Test void exactGraceDeadlineDoesNotAllowStateOrRestoreWithAnOldSession()throws Exception {
  UUID operation=UUID.randomUUID();var receipt=node(command(access,grant(access,"CERRAR",operation,operation),"CERRAR",operation,operation).andExpect(status().isOk()));
  String restricted=login(own);clock.now=Instant.parse(receipt.path("reversibleHasta").asText());
  auth(get(BASE),restricted).andExpect(status().isUnauthorized());
  auth(post(BASE+"/reauthenticaciones").contentType(APPLICATION_JSON).content("{}"),restricted).andExpect(status().isUnauthorized());
  assertThat(jdbc.queryForObject("SELECT cierre_estado FROM talleres WHERE id=?",String.class,own.workshop())).isEqualTo("RESTRINGIDO");
 }
 @Test void previouslyReadyZipRemainsDownloadableButNewZipAndExcelAreBlocked()throws Exception {
  String exportProof=exportGrant(access,"EXPORTAR");
  String id=node(auth(post("/api/exportaciones").header("Idempotency-Key",UUID.randomUUID()).header("X-Reauth-Token",exportProof),access).andExpect(status().isAccepted())).path("id").asText();
  assertThat(jobs.runNext()).isTrue();var expiry=jdbc.queryForObject("SELECT expira_en FROM cuenta_exportaciones WHERE id=?",OffsetDateTime.class,UUID.fromString(id));
  UUID operation=UUID.randomUUID();command(access,grant(access,"CERRAR",operation,operation),"CERRAR",operation,operation).andExpect(status().isOk());
  String restricted=login(own);
  auth(post("/api/exportaciones"),restricted).andExpect(status().isLocked());auth(post("/api/export/excel"),restricted).andExpect(status().isLocked());
  auth(get("/api/exportaciones/actual"),restricted).andExpect(status().isOk()).andExpect(jsonPath("$.exportacion.estado").value("READY"));
  auth(post("/api/exportaciones/"+id+"/archivo").header("X-Reauth-Token",exportGrant(restricted,"DESCARGAR_EXPORTACION")),restricted)
   .andExpect(status().isOk()).andExpect(content().contentType("application/zip"));
  assertThat(jdbc.queryForObject("SELECT expira_en FROM cuenta_exportaciones WHERE id=?",OffsetDateTime.class,UUID.fromString(id))).isEqualTo(expiry);
 }
 @Test void badPasswordAndConfirmationDoNotCloseTheWorkshop()throws Exception {
  UUID operation=UUID.randomUUID();
  auth(post(BASE+"/reauthenticaciones").contentType(APPLICATION_JSON).content(json.writeValueAsString(Map.of("passwordActual","wrong","proposito","CERRAR","operacionId",operation,"cierreReferencia",operation))),access)
   .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PASSWORD_ACTUAL_INVALIDA"));
  String proof=grant(access,"CERRAR",operation,operation);var before=fingerprint();
  auth(post(BASE+"/operaciones").contentType(APPLICATION_JSON).header("X-Reauth-Token",proof).content(json.writeValueAsString(Map.of("proposito","CERRAR","operacionId",operation,"cierreReferencia",operation,"confirmacion","cerrar mi taller"))),access)
   .andExpect(status().isBadRequest());assertThat(fingerprint()).isEqualTo(before);
 }
 @ParameterizedTest @ValueSource(strings={"/", "/extra", "/operaciones", "/reauthenticaciones"})
 void unexpectedGetRoutesCannotExecuteCommands(String suffix)throws Exception {
  var response=auth(get(BASE+suffix),access).andReturn().getResponse();assertThat(response.getStatus()).isBetween(400,499);
 }
 @Test void corsAllowsOnlyTheFrontendOriginAndRequiredProtectedHeaders()throws Exception {
  mvc.perform(options(BASE+"/operaciones").header("Origin","http://localhost:5173").header("Access-Control-Request-Method","POST")
   .header("Access-Control-Request-Headers","authorization,content-type,x-reauth-token"))
   .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin","http://localhost:5173"));
 }
 private Actor actor(String role,boolean verified){
  long taller=jdbc.queryForObject("INSERT INTO talleres(nombre,telefono) VALUES('Taller cierre HTTP','1100000000') RETURNING id",Long.class);
  String email=UUID.randomUUID()+"@closure-http.synthetic.invalid";
  long user=jdbc.queryForObject("INSERT INTO users(username,email,password,role,taller_id,active,email_verificado,token_version) VALUES('Titular HTTP',?,?,?,?,true,?,0) RETURNING id",Long.class,email,passwords.encode(PASSWORD),role,taller,verified);
  jdbc.update("INSERT INTO suscripciones(plan,estado,fecha_inicio,taller_id) VALUES('FREE','TRIAL',CURRENT_DATE,?)",taller);
  return new Actor(taller,user,email);
 }
 private String token(Actor actor){return jwt.generateToken(new AuthenticatedUserPrincipal(users.findSessionByIdAndTallerId(actor.user(),actor.workshop()).orElseThrow(),clock.instant()),actor.workshop());}
 private String login(Actor actor)throws Exception{return node(mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON).content(json.writeValueAsString(Map.of("email",actor.email(),"password",PASSWORD)))).andExpect(status().isOk())).path("token").asText();}
 private String grant(String access,String purpose,UUID operation,UUID reference)throws Exception {
  return node(auth(post(BASE+"/reauthenticaciones").contentType(APPLICATION_JSON).content(json.writeValueAsString(Map.of("passwordActual",PASSWORD,"proposito",purpose,"operacionId",operation,"cierreReferencia",reference))),access)
   .andExpect(status().isOk())).path("token").asText();
 }
 private String exportGrant(String access,String purpose)throws Exception{return node(auth(post("/api/cuenta/reauthenticaciones").contentType(APPLICATION_JSON).content(json.writeValueAsString(Map.of("passwordActual",PASSWORD,"proposito",purpose))),access).andExpect(status().isOk())).path("reauthToken").asText();}
 private ResultActions command(String access,String proof,String purpose,UUID operation,UUID reference)throws Exception {
  var builder=post(BASE+"/operaciones").contentType(APPLICATION_JSON).content(json.writeValueAsString(Map.of("proposito",purpose,"operacionId",operation,"cierreReferencia",reference,"confirmacion",purpose.equals("CERRAR")?"CERRAR MI TALLER":"RESTAURAR MI TALLER")));
  if(proof!=null)builder.header("X-Reauth-Token",proof);return auth(builder,access);
 }
 private ResultActions auth(MockHttpServletRequestBuilder request,String access)throws Exception{return mvc.perform(request.header("Authorization","Bearer "+access));}
 private JsonNode node(ResultActions result)throws Exception{return json.readTree(result.andReturn().getResponse().getContentAsByteArray());}
 private Map<String,List<Map<String,Object>>> fingerprint(){
  var result=new TreeMap<String,List<Map<String,Object>>>();
  for(String table:List.of("talleres","users","cuenta_cierres","cuenta_cierre_confirmaciones","cuenta_cierre_operaciones","cuenta_cierre_efectos"))
   result.put(table,jdbc.queryForList("SELECT xmin::text AS version,ctid::text AS position,to_jsonb(t)::text AS row FROM public."+table+" t ORDER BY to_jsonb(t)::text"));
  return result;
 }
 private record Actor(long workshop,long user,String email) { }
}
