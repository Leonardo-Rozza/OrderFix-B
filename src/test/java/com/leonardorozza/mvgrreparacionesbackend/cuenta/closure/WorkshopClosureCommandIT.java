package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.AccountSessionPolicy;
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
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.config.import=","spring.config.additional-location=",
 "spring.config.location=optional:classpath:/application.properties","exports.jobs.enabled=false",
 "mail.enabled=false","mercadopago.enabled=false","mercadopago.checkout-enabled=false","photos.private.enabled=false",
 "ordenfix.legal.registration-consent.enabled=false","ordenfix.legal.registration-enforcement.enabled=false",
 "ordenfix.legal.account-read.enabled=false","ordenfix.legal.account-acceptance.enabled=false",
 "ordenfix.legal.public-documents.enabled=false","ordenfix.legal.public-requirements.enabled=false",
 "ordenfix.legal.aggregate-context.enabled=false","ordenfix.legal.editorial-context.enabled=false",
 "ordenfix.legal.import-context.enabled=false","ordenfix.legal.dry-run-context.enabled=false",
 "ordenfix.legal.public-document-read-context.enabled=false","ordenfix.legal.public-requirements-context.enabled=false"})
@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class WorkshopClosureCommandIT {
 @Container static final PostgreSQLContainer PG=new PostgreSQLContainer("postgres:16-alpine")
   .withDatabaseName("closure_command_synthetic").withUsername("closure").withPassword("closure");
 @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
  r.add("spring.datasource.url",PG::getJdbcUrl);r.add("spring.datasource.username",PG::getUsername);
  r.add("spring.datasource.password",PG::getPassword);r.add("spring.datasource.driver-class-name",PG::getDriverClassName);
  r.add("spring.flyway.enabled",()->"true");r.add("spring.jpa.hibernate.ddl-auto",()->"validate");
  r.add("spring.jpa.properties.hibernate.dialect",()->"org.hibernate.dialect.PostgreSQLDialect");
 }
 @TestConfiguration static class Configuration {
  @Bean @Primary AdjustableClock closureCommandClock() { return new AdjustableClock(); }
 }
 static final class AdjustableClock extends Clock {
  volatile Instant now=Instant.now();
  public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;}
  public Instant instant(){return now;}
 }
 @Autowired JdbcTemplate jdbc;
 @Autowired PlatformTransactionManager manager;
 @Autowired WorkshopClosureStore store;
 @Autowired WorkshopClosureCommandService commands;
 @Autowired WorkshopClosureReauthenticationService authorization;
 @org.springframework.test.context.bean.override.mockito.MockitoSpyBean WorkshopClosureEffects effects;
 @Autowired WorkshopClosureGate gate;
 @Autowired AdjustableClock clock;
 @Autowired JwtUtils jwt;
 @Autowired UserRepository users;
 @Autowired PasswordEncoder passwords;
 @Autowired AccountSessionPolicy sessions;
 private Actor own;
 private WorkshopClosureEffects effectsTarget;
 private static final String PASSWORD="closure-command-synthetic-password";
 @BeforeEach void fixture(){
  clock.now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);own=actor();
  effectsTarget=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(effects);
  org.mockito.Mockito.reset(effectsTarget);
 }

 @Test void closeAndRestoreAreAtomicAndReplayReturnsTheOriginalReceiptWithoutDml(){
  String old=token(own);UUID ref=UUID.randomUUID();var proof=grant(old,WorkshopClosurePurpose.CERRAR,ref,ref);
  var closed=commands.execute(old,proof,WorkshopClosurePurpose.CERRAR,ref,ref,"CERRAR MI TALLER");
  assertThat(closed.reused()).isFalse();assertThat(closed.generation()).isEqualTo(1);assertThat(closed.stateAtCommit()).isEqualTo("RESTRINGIDO");
  assertThat(jdbc.queryForList("SELECT token_version FROM users WHERE taller_id=?",Long.class,own.workshop())).containsOnly(1L);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM cuenta_cierre_efectos WHERE operacion_id=? AND tipo='AVISO_CIERRE'",Long.class,ref)).isEqualTo(1);
  var afterClose=fingerprint();
  assertThatThrownBy(()->commands.execute(old,proof,WorkshopClosurePurpose.CERRAR,ref,ref,"CERRAR MI TALLER"))
    .isInstanceOf(WorkshopClosureCommandService.Rejected.class);
  assertThat(fingerprint()).isEqualTo(afterClose);
  String restricted=token(own);
  var replay=commands.execute(restricted,null,WorkshopClosurePurpose.CERRAR,ref,ref,"CERRAR MI TALLER");
  assertThat(replay.reused()).isTrue();assertThat(replay.completedAt()).isEqualTo(closed.completedAt());
  assertThat(fingerprint()).isEqualTo(afterClose);
  UUID restore=UUID.randomUUID();String restoreProof=grant(restricted,WorkshopClosurePurpose.RESTAURAR,restore,ref);
  var restored=commands.execute(restricted,restoreProof,WorkshopClosurePurpose.RESTAURAR,restore,ref,"RESTAURAR MI TALLER");
  assertThat(restored.generation()).isEqualTo(2);assertThat(restored.stateAtCommit()).isEqualTo("ABIERTO");
  assertThat(restored.reversibleUntil()).isEqualTo(closed.reversibleUntil());
  assertThat(jdbc.queryForObject("SELECT active FROM users WHERE id=?",Boolean.class,own.inactive())).isFalse();
  String reopened=token(own);var afterRestore=fingerprint();
  assertThat(commands.execute(reopened,null,WorkshopClosurePurpose.RESTAURAR,restore,ref,"RESTAURAR MI TALLER").reused()).isTrue();
  assertThat(commands.execute(reopened,null,WorkshopClosurePurpose.CERRAR,ref,ref,"CERRAR MI TALLER").stateAtCommit()).isEqualTo("RESTRINGIDO");
  assertThat(fingerprint()).isEqualTo(afterRestore);
  UUID next=UUID.randomUUID();String nextProof=grant(reopened,WorkshopClosurePurpose.CERRAR,next,next);
  assertThat(commands.execute(reopened,nextProof,WorkshopClosurePurpose.CERRAR,next,next,"CERRAR MI TALLER").generation()).isEqualTo(3);
 }

 @ParameterizedTest @ValueSource(strings={"cerrar mi taller"," CERRAR MI TALLER","CERRAR MI TALLER ","RESTAURAR MI TALLER",""})
 void writtenConfirmationIsExactAndDoesNotConsumeTheProof(String confirmation){
  String access=token(own);UUID op=UUID.randomUUID();String proof=grant(access,WorkshopClosurePurpose.CERRAR,op,op);var before=fingerprint();
  assertThatThrownBy(()->commands.execute(access,proof,WorkshopClosurePurpose.CERRAR,op,op,confirmation))
   .isInstanceOfSatisfying(WorkshopClosureCommandService.Rejected.class,e->assertThat(e.code()).isEqualTo(WorkshopClosureCommandService.Rejected.Code.CONFIRMATION_INVALID));
  assertThat(fingerprint()).isEqualTo(before);
 }

 @Test void purposeReferenceAndWorkshopCollisionsFailClosed(){
  UUID ref=UUID.randomUUID();String access=token(own);String proof=grant(access,WorkshopClosurePurpose.CERRAR,ref,ref);
  commands.execute(access,proof,WorkshopClosurePurpose.CERRAR,ref,ref,"CERRAR MI TALLER");
  Actor other=actor();String otherAccess=token(other);String otherProof=grant(otherAccess,WorkshopClosurePurpose.CERRAR,ref,ref);
  var before=fingerprint();
  assertThatThrownBy(()->commands.execute(otherAccess,otherProof,WorkshopClosurePurpose.CERRAR,ref,ref,"CERRAR MI TALLER"))
    .isInstanceOfSatisfying(WorkshopClosureCommandService.Rejected.class,e->assertThat(e.code()).isEqualTo(WorkshopClosureCommandService.Rejected.Code.CONFLICT));
  assertThatThrownBy(()->commands.execute(token(own),null,WorkshopClosurePurpose.RESTAURAR,ref,ref,"RESTAURAR MI TALLER"))
    .isInstanceOf(WorkshopClosureCommandService.Rejected.class);
  assertThat(fingerprint()).isEqualTo(before);
 }

 @Test void effectsFailureRollsBackProofEpochsHistoryAndReceipt(){
  String access=token(own);UUID op=UUID.randomUUID();String proof=grant(access,WorkshopClosurePurpose.CERRAR,op,op);var before=fingerprint();
  org.mockito.Mockito.doAnswer(call->{call.callRealMethod();throw new IllegalStateException("synthetic private detail");})
   .when(effectsTarget).enqueueClose(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any());
  assertThatThrownBy(()->commands.execute(access,proof,WorkshopClosurePurpose.CERRAR,op,op,"CERRAR MI TALLER"))
    .isInstanceOfSatisfying(WorkshopClosureCommandService.Rejected.class,e->{assertThat(e.getCause()).isNull();assertThat(e.getMessage()).doesNotContain("synthetic");});
  assertThat(fingerprint()).isEqualTo(before);
  org.mockito.Mockito.reset(effectsTarget);
  assertThat(commands.execute(access,proof,WorkshopClosurePurpose.CERRAR,op,op,"CERRAR MI TALLER").reused()).isFalse();
 }

 @Test void finalProofDeadlineFailureRollsBackEvenAfterTheOutboxWasWritten(){
  String access=token(own);UUID op=UUID.randomUUID();String proof=grant(access,WorkshopClosurePurpose.CERRAR,op,op);var before=fingerprint();
  org.mockito.Mockito.doAnswer(call->{var result=call.callRealMethod();clock.now=clock.now.plusSeconds(300);return result;})
    .when(effectsTarget).enqueueClose(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any());
  assertThatThrownBy(()->commands.execute(access,proof,WorkshopClosurePurpose.CERRAR,op,op,"CERRAR MI TALLER"))
    .isInstanceOf(WorkshopClosureCommandService.Rejected.class);
  assertThat(fingerprint()).isEqualTo(before);
 }

 @Test void epochOverflowRollsBackConsumedProof(){
  jdbc.update("UPDATE users SET token_version=? WHERE id=?",Long.MAX_VALUE,own.employee());
  String access=token(own);UUID op=UUID.randomUUID();String proof=grant(access,WorkshopClosurePurpose.CERRAR,op,op);var before=fingerprint();
  assertThatThrownBy(()->commands.execute(access,proof,WorkshopClosurePurpose.CERRAR,op,op,"CERRAR MI TALLER"))
    .isInstanceOfSatisfying(WorkshopClosureCommandService.Rejected.class,e->assertThat(e.code()).isEqualTo(WorkshopClosureCommandService.Rejected.Code.CAPACITY));
  assertThat(fingerprint()).isEqualTo(before);
 }

 @Test void exactGraceEndRejectsRestoreAndReplayWithoutDml(){
  String access=token(own);UUID ref=UUID.randomUUID();var closed=commands.execute(access,grant(access,WorkshopClosurePurpose.CERRAR,ref,ref),WorkshopClosurePurpose.CERRAR,ref,ref,"CERRAR MI TALLER");
  String restricted=token(own);UUID op=UUID.randomUUID();String proof=grant(restricted,WorkshopClosurePurpose.RESTAURAR,op,ref);var before=fingerprint();
  clock.now=closed.reversibleUntil();
  assertThatThrownBy(()->commands.execute(restricted,proof,WorkshopClosurePurpose.RESTAURAR,op,ref,"RESTAURAR MI TALLER")).isInstanceOf(WorkshopClosureCommandService.Rejected.class);
  assertThatThrownBy(()->commands.execute(restricted,null,WorkshopClosurePurpose.CERRAR,ref,ref,"CERRAR MI TALLER")).isInstanceOf(WorkshopClosureCommandService.Rejected.class);
  assertThat(fingerprint()).isEqualTo(before);
 }

 @Test void commandCommitIsIndependentFromCallerRollback(){
  Actor other=actor();String access=token(own);UUID op=UUID.randomUUID();String proof=grant(access,WorkshopClosurePurpose.CERRAR,op,op);
  TransactionTemplate outer=new TransactionTemplate(manager);
  outer.executeWithoutResult(status->{
   jdbc.update("UPDATE clientes SET nombre='outer pending' WHERE id=?",other.customer());
   commands.execute(access,proof,WorkshopClosurePurpose.CERRAR,op,op,"CERRAR MI TALLER");status.setRollbackOnly();
  });
  assertThat(jdbc.queryForObject("SELECT cierre_estado FROM talleres WHERE id=?",String.class,own.workshop())).isEqualTo("RESTRINGIDO");
  assertThat(jdbc.queryForObject("SELECT nombre FROM clientes WHERE id=?",String.class,other.customer())).isEqualTo("fixture");
 }

 @Test void simultaneousUseNeverDuplicatesTheCommandOrEffects()throws Exception{
  String access=token(own);UUID op=UUID.randomUUID();String proof=grant(access,WorkshopClosurePurpose.CERRAR,op,op);
  try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
   var start=new CountDownLatch(1);
   var jobs=java.util.stream.IntStream.range(0,2).mapToObj(i->executor.submit(()->{start.await();try{
    commands.execute(access,proof,WorkshopClosurePurpose.CERRAR,op,op,"CERRAR MI TALLER");return true;
   }catch(WorkshopClosureCommandService.Rejected expected){return false;}})).toList();
   start.countDown();int accepted=0;for(var job:jobs)if(job.get(10,TimeUnit.SECONDS))accepted++;
   assertThat(accepted).isEqualTo(1);
  }
  assertThat(jdbc.queryForObject("SELECT count(*) FROM cuenta_cierre_operaciones WHERE operacion_id=?",Long.class,op)).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM cuenta_cierre_efectos WHERE operacion_id=? AND tipo='AVISO_CIERRE'",Long.class,op)).isEqualTo(1);
 }

 @Test void receiptAndConsumedProofCannotBeRewrittenOrDeleted(){
  String access=token(own);UUID op=UUID.randomUUID();commands.execute(access,grant(access,WorkshopClosurePurpose.CERRAR,op,op),WorkshopClosurePurpose.CERRAR,op,op,"CERRAR MI TALLER");var before=fingerprint();
  assertThatThrownBy(()->jdbc.update("UPDATE cuenta_cierre_operaciones SET request_digest=repeat('f',64) WHERE operacion_id=?",op)).satisfies(e->assertThat(sqlState(e)).isEqualTo("P0033"));
  assertThatThrownBy(()->jdbc.update("DELETE FROM cuenta_cierre_operaciones WHERE operacion_id=?",op)).satisfies(e->assertThat(sqlState(e)).isEqualTo("P0033"));
  assertThatThrownBy(()->jdbc.update("DELETE FROM cuenta_cierre_confirmaciones WHERE operacion_id=?",op)).satisfies(e->assertThat(sqlState(e)).isEqualTo("P0033"));
  assertThat(fingerprint()).isEqualTo(before);
 }

 @ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(WorkshopClosurePurpose.class)
 void competingWorkshopsReportConflictAndRollBackTheLosingTransition(WorkshopClosurePurpose purpose)throws Exception{
  Actor other=actor();UUID operation=UUID.randomUUID();
  UUID ownReference=purpose==WorkshopClosurePurpose.CERRAR?operation:UUID.randomUUID();
  UUID otherReference=purpose==WorkshopClosurePurpose.CERRAR?operation:UUID.randomUUID();
  if(purpose==WorkshopClosurePurpose.RESTAURAR){
   for(var entry:List.of(Map.entry(own,ownReference),Map.entry(other,otherReference))){
    String access=token(entry.getKey());UUID reference=entry.getValue();
    commands.execute(access,grant(access,WorkshopClosurePurpose.CERRAR,reference,reference),
      WorkshopClosurePurpose.CERRAR,reference,reference,"CERRAR MI TALLER");
   }
  }
  String ownAccess=token(own),otherAccess=token(other);
  String ownProof=grant(ownAccess,purpose,operation,ownReference),otherProof=grant(otherAccess,purpose,operation,otherReference);
  var beforeOwn=workshopFingerprint(own.workshop());var beforeOther=workshopFingerprint(other.workshop());
  var atInsert=new CountDownLatch(2);
  String collisionTable=purpose==WorkshopClosurePurpose.CERRAR?"public.cuenta_cierres(":"public.cuenta_cierre_operaciones(";
  // Pause actual JDBC calls immediately before the contested INSERT. Both transactions have already
  // observed no matching receipt/history; no route mocks or altered PostgreSQL guards are involved.
  JdbcTemplate racingJdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())){
   @Override public int update(String sql,Object...arguments){
    if(sql.contains("INSERT INTO "+collisionTable)){
     atInsert.countDown();
     try{assertThat(atInsert.await(5,TimeUnit.SECONDS)).isTrue();}
     catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
    }
    return super.update(sql,arguments);
   }
  };
  var racing=new WorkshopClosureCommandService(racingJdbc,gate,authorization,
    new WorkshopClosureStore(racingJdbc,clock),effects,manager,clock);
  String phrase=purpose==WorkshopClosurePurpose.CERRAR?"CERRAR MI TALLER":"RESTAURAR MI TALLER";
  try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
   var first=executor.submit(()->raceCommand(racing,ownAccess,ownProof,purpose,operation,ownReference,phrase));
   var second=executor.submit(()->raceCommand(racing,otherAccess,otherProof,purpose,operation,otherReference,phrase));
   assertThat(List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
  }
  long winningWorkshop=Objects.requireNonNull(jdbc.queryForObject(
    "SELECT taller_id FROM cuenta_cierre_operaciones WHERE operacion_id=?",Long.class,operation));
  Actor loser=winningWorkshop==own.workshop()?other:own;
  assertThat(workshopFingerprint(loser.workshop())).isEqualTo(loser==own?beforeOwn:beforeOther);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM cuenta_cierre_operaciones WHERE operacion_id=?",Long.class,operation)).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM cuenta_cierre_confirmaciones WHERE operacion_id=? AND usada_en IS NOT NULL",Long.class,operation)).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM cuenta_cierre_efectos WHERE operacion_id=? AND taller_id=?",Long.class,operation,loser.workshop())).isZero();
 }
 private boolean raceCommand(WorkshopClosureCommandService service,String access,String proof,WorkshopClosurePurpose purpose,
   UUID operation,UUID reference,String phrase){
  try{service.execute(access,proof,purpose,operation,reference,phrase);return true;}
  catch(WorkshopClosureCommandService.Rejected rejected){
   assertThat(rejected.code()).isEqualTo(WorkshopClosureCommandService.Rejected.Code.CONFLICT);
   assertThat(rejected.getCause()).isNull();return false;
  }
 }
 private Map<String,String> workshopFingerprint(long workshop){
  Map<String,String> result=new TreeMap<>();
  for(String table:List.of("talleres","users","clientes","suscripciones","cuenta_cierres","cuenta_reautenticaciones",
    "cuenta_exportaciones","cuenta_cierre_confirmaciones","cuenta_cierre_operaciones","cuenta_cierre_efectos")){
   String column=table.equals("talleres")?"id":"taller_id";
   result.put(table,jdbc.queryForObject("SELECT count(*)::text||':'||coalesce(md5(string_agg(md5(to_jsonb(r)::text)||':'||xmin::text,',' ORDER BY md5(to_jsonb(r)::text)||':'||xmin::text)),'') FROM public."
     +table+" r WHERE "+column+"=?",String.class,workshop));
  }
  return result;
 }

 private String grant(String access,WorkshopClosurePurpose purpose,UUID op,UUID ref){return authorization.issue(access,PASSWORD,purpose,op,ref).token();}
 private String token(Actor actor){return tx(()->jwt.generateToken(new AuthenticatedUserPrincipal(users.findSessionByIdAndTallerId(actor.owner(),actor.workshop()).orElseThrow(),clock.now),actor.workshop()));}
 private <T>T tx(Supplier<T> body){TransactionTemplate t=new TransactionTemplate(manager);t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);t.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);return t.execute(s->body.get());}
 private Actor actor(){
  String mark=UUID.randomUUID().toString();
  long workshop=jdbc.queryForObject("INSERT INTO talleres(nombre) VALUES(?) RETURNING id",Long.class,"Closure-"+mark);
  jdbc.update("INSERT INTO suscripciones(taller_id,plan,estado,fecha_inicio) VALUES(?,'FREE','TRIAL',CURRENT_DATE)",workshop);
  long owner=user(workshop,"ADMIN",true);long employee=user(workshop,"USER",true);long inactive=user(workshop,"USER",false);
  long customer=jdbc.queryForObject("INSERT INTO clientes(nombre,apellido,telefono,taller_id) VALUES('fixture','synthetic','1100000000',?) RETURNING id",Long.class,workshop);
  return new Actor(workshop,owner,employee,inactive,customer);
 }
 private long user(long workshop,String role,boolean active){String mark=UUID.randomUUID().toString();return jdbc.queryForObject(
  "INSERT INTO users(username,email,password,role,taller_id,active,email_verificado,token_version) VALUES(?,?,?,?,?,?,true,0) RETURNING id",Long.class,
  "Closure-"+mark,mark+"@synthetic.invalid",passwords.encode(PASSWORD),role,workshop,active);}
 private UUID ready(Actor actor,long version){UUID id=UUID.randomUUID();jdbc.update("""
  INSERT INTO cuenta_exportaciones(id,user_id,taller_id,token_version,session_hash,request_hash,estado,creada_en,actualizada_en,expira_en,capturada_en,archive_cipher)
  VALUES(?,?,?,?,repeat('a',64),?,'READY',clock_timestamp(),clock_timestamp(),clock_timestamp()+INTERVAL '1 hour',clock_timestamp(),decode(repeat('01',32),'hex'))
  """,id,actor.owner(),actor.workshop(),version,id.toString().replace("-","").repeat(2));return id;}
 private Map<String,String> fingerprint(){Map<String,String> result=new TreeMap<>();for(String table:List.of("talleres","users","clientes","suscripciones","cuenta_cierres","cuenta_reautenticaciones","cuenta_exportaciones","cuenta_cierre_confirmaciones","cuenta_cierre_operaciones","cuenta_cierre_efectos"))result.put(table,jdbc.queryForObject(
  "SELECT count(*)::text||':'||coalesce(md5(string_agg(md5(to_jsonb(r)::text)||':'||xmin::text,',' ORDER BY md5(to_jsonb(r)::text)||':'||xmin::text)),'') FROM public."+table+" r",String.class));return result;}
 private static String sqlState(Throwable failure){for(Throwable current=failure;current!=null;current=current.getCause())if(current instanceof SQLException sql)return sql.getSQLState();return null;}
 private record Actor(long workshop,long owner,long employee,long inactive,long customer){}
}
