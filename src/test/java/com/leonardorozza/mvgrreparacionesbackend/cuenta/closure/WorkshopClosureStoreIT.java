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
class WorkshopClosureStoreIT {
 @Container static final PostgreSQLContainer PG=new PostgreSQLContainer("postgres:16-alpine")
   .withDatabaseName("closure_store_synthetic").withUsername("closure").withPassword("closure");
 @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
  r.add("spring.datasource.url",PG::getJdbcUrl);r.add("spring.datasource.username",PG::getUsername);
  r.add("spring.datasource.password",PG::getPassword);r.add("spring.datasource.driver-class-name",PG::getDriverClassName);
  r.add("spring.flyway.enabled",()->"true");r.add("spring.jpa.hibernate.ddl-auto",()->"validate");
  r.add("spring.jpa.properties.hibernate.dialect",()->"org.hibernate.dialect.PostgreSQLDialect");
 }
 @TestConfiguration static class Configuration {
  @Bean @Primary AdjustableClock closureStoreClock() { return new AdjustableClock(); }
 }
 static final class AdjustableClock extends Clock {
  volatile Instant now=Instant.now();
  public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;}
  public Instant instant(){return now;}
 }
 @Autowired JdbcTemplate jdbc;
 @Autowired PlatformTransactionManager manager;
 @Autowired WorkshopClosureStore store;
 @Autowired WorkshopClosureGate gate;
 @Autowired AdjustableClock clock;
 @Autowired JwtUtils jwt;
 @Autowired UserRepository users;
 @Autowired PasswordEncoder passwords;
 @Autowired AccountSessionPolicy sessions;
 private Actor own;
 private static final String PASSWORD="closure-store-synthetic-password";

 @BeforeEach void fixture(){clock.now=Instant.now();own=actor();}

 @Test void restrictionIsAtomicRevokesEveryEpochAndKeepsIndividualActivationAndReplayReadOnly(){
  var oldJwt=token(own.owner(),own.workshop());
  UUID ref=UUID.randomUUID();
  var receipt=close(own,ref);
  assertThat(receipt.state()).isEqualTo("RESTRINGIDO");assertThat(receipt.generation()).isEqualTo(1);
  assertThat(versions(own.workshop())).containsOnly(1L);
  assertThat(jdbc.queryForObject("SELECT active FROM users WHERE id=?",Boolean.class,own.inactive())).isFalse();
  assertThat(jdbc.queryForObject("SELECT activo FROM talleres WHERE id=?",Boolean.class,own.workshop())).isTrue();
  assertThat(jwt.validateToken(oldJwt,principal(own.owner(),own.workshop()))).isFalse();
  assertThat(sessions.issueSession(own.owner(),own.workshop(),PASSWORD).token()).isNotBlank();
  assertThatThrownBy(()->sessions.issueSession(own.employee(),own.workshop(),PASSWORD)).isInstanceOf(BadCredentialsException.class);
  var before=fingerprint();
  assertThat(close(own,ref).reused()).isTrue();
  assertThat(fingerprint()).isEqualTo(before);
 }

 @Test void restorationRevokesGraceSessionsAndDoesNotReactivateEmployeesOrSubscription(){
  var receipt=close(own,UUID.randomUUID());String graceJwt=token(own.owner(),own.workshop());
  var subscription=jdbc.queryForMap("SELECT * FROM suscripciones WHERE taller_id=?",own.workshop());
  var restored=tx(()->{gate.lockExclusive(own.workshop());return store.restore(own.workshop(),own.owner(),receipt.reference());});
  assertThat(restored.state()).isEqualTo("RESTAURADO");assertThat(versions(own.workshop())).containsOnly(2L);
  assertThat(jwt.validateToken(graceJwt,principal(own.owner(),own.workshop()))).isFalse();
  assertThat(jdbc.queryForObject("SELECT active FROM users WHERE id=?",Boolean.class,own.inactive())).isFalse();
  assertThat(jdbc.queryForMap("SELECT * FROM suscripciones WHERE taller_id=?",own.workshop())).isEqualTo(subscription);
  gate.requireOperational(own.workshop());
  var before=fingerprint();
  assertThat(tx(()->{gate.lockExclusive(own.workshop());return store.restore(own.workshop(),own.owner(),receipt.reference());}).reused()).isTrue();
  assertThat(fingerprint()).isEqualTo(before);
 }

 @Test void exactGraceDeadlineFailsClosedWithoutChangingHistory(){
  var receipt=close(own,UUID.randomUUID());clock.now=receipt.reversibleUntil();var before=fingerprint();
  assertThatThrownBy(()->tx(()->{gate.lockExclusive(own.workshop());return store.restore(own.workshop(),own.owner(),receipt.reference());}))
    .isInstanceOf(WorkshopClosureStore.Rejected.class);
  assertThat(fingerprint()).isEqualTo(before);
 }

 @Test void callerRollbackRestoresSessionsJobsAndHistory(){
  UUID job=ready(own,0);var before=fingerprint();
  template().executeWithoutResult(status->{gate.lockExclusive(own.workshop());store.restrict(own.workshop(),own.owner(),UUID.randomUUID());status.setRollbackOnly();});
  assertThat(fingerprint()).isEqualTo(before);
  assertThat(jdbc.queryForObject("SELECT token_version FROM cuenta_exportaciones WHERE id=?",Long.class,job)).isZero();
 }

 @Test void readyRebindingIsExplicitAndPreservesCiphertextCaptureAndDeadline(){
  UUID job=ready(own,0);var before=jdbc.queryForMap("SELECT archive_cipher,capturada_en,expira_en FROM cuenta_exportaciones WHERE id=?",job);
  close(own,UUID.randomUUID());
  assertThat(jdbc.queryForObject("SELECT token_version FROM cuenta_exportaciones WHERE id=?",Long.class,job)).isEqualTo(1);
  var after=jdbc.queryForMap("SELECT archive_cipher,capturada_en,expira_en FROM cuenta_exportaciones WHERE id=?",job);
  assertThat((byte[])after.get("archive_cipher")).containsExactly((byte[])before.get("archive_cipher"));
  assertThat(after.get("capturada_en")).isEqualTo(before.get("capturada_en"));
  assertThat(after.get("expira_en")).isEqualTo(before.get("expira_en"));
 }

 @Test void aReadyFromAnotherEpochIsRevokedEvenIfItsVersionEqualsTheNextOwnerEpoch(){
  UUID job=ready(own,1);close(own,UUID.randomUUID());
  assertThat(jdbc.queryForObject("SELECT estado FROM cuenta_exportaciones WHERE id=?",String.class,job)).isEqualTo("REVOKED");
  assertThat(jdbc.queryForObject("SELECT archive_cipher IS NULL FROM cuenta_exportaciones WHERE id=?",Boolean.class,job)).isTrue();
 }

 @Test void anEmployeeEpochOverflowRejectsTheWholeTransition(){
  jdbc.update("UPDATE users SET token_version=? WHERE id=?",Long.MAX_VALUE,own.employee());var before=fingerprint();
  assertThatThrownBy(()->close(own,UUID.randomUUID())).isInstanceOf(WorkshopClosureStore.Rejected.class);
  assertThat(fingerprint()).isEqualTo(before);
 }

 @Test void aReferenceCollisionCannotAffectAnotherWorkshop(){
  UUID reference=UUID.randomUUID();close(own,reference);Actor other=actor();var before=fingerprint();
  assertThatThrownBy(()->close(other,reference)).isInstanceOf(WorkshopClosureStore.Rejected.class);
  assertThat(fingerprint()).isEqualTo(before);
 }

 @Test void noGateAndForeignOwnerAreRejectedBeforeAnyDml(){
  var before=fingerprint();
  assertThatThrownBy(()->tx(()->store.restrict(own.workshop(),own.owner(),UUID.randomUUID()))).isInstanceOf(WorkshopClosureStore.Rejected.class);
  assertThatThrownBy(()->tx(()->{gate.lockExclusive(own.workshop());return store.restrict(own.workshop(),own.employee(),UUID.randomUUID());})).isInstanceOf(WorkshopClosureStore.Rejected.class);
  assertThat(fingerprint()).isEqualTo(before);
 }

 @ParameterizedTest @ValueSource(strings={"clientes","users","cuenta_exportaciones","legal_aceptacion_lotes"})
 void directSqlCannotBypassTheClosedWorkshop(String table){
  close(own,UUID.randomUUID());var before=fingerprint();
  String sql=switch(table){
   case "clientes" -> "INSERT INTO clientes(nombre,taller_id) VALUES('blocked',"+own.workshop()+")";
   case "users" -> "INSERT INTO users(username,email,password,role,taller_id,active,email_verificado,token_version) VALUES('blocked','blocked@synthetic.invalid','hash','USER',"+own.workshop()+",true,true,0)";
   case "cuenta_exportaciones" -> "INSERT INTO cuenta_exportaciones(taller_id,user_id) VALUES("+own.workshop()+","+own.owner()+")";
   default -> "INSERT INTO legal_aceptacion_lotes(taller_id,user_id) VALUES("+own.workshop()+","+own.owner()+")";
  };
  assertThatThrownBy(()->jdbc.execute(sql)).satisfies(failure->assertThat(sqlState(failure)).isEqualTo("P0033"));
  assertThat(fingerprint()).isEqualTo(before);
 }

 @Test void anAdmittedWriterFinishesBeforeClosureCommits()throws Exception{
  try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
   var wrote=new CountDownLatch(1);var release=new CountDownLatch(1);
   Future<?> writer=executor.submit(()->tx(()->{jdbc.update("UPDATE clientes SET nombre='admitted' WHERE id=?",own.customer());wrote.countDown();await(release);return null;}));
   assertThat(wrote.await(3,TimeUnit.SECONDS)).isTrue();
   Future<?> closing=executor.submit(()->close(own,UUID.randomUUID()));
   try {
    awaitWaitingExclusive();assertThat(closing.isDone()).isFalse();
   } finally {release.countDown();}
   writer.get(5,TimeUnit.SECONDS);closing.get(5,TimeUnit.SECONDS);
   assertThat(jdbc.queryForObject("SELECT nombre FROM clientes WHERE id=?",String.class,own.customer())).isEqualTo("admitted");
   assertThatThrownBy(()->jdbc.update("UPDATE clientes SET nombre='late' WHERE id=?",own.customer())).satisfies(f->assertThat(sqlState(f)).isEqualTo("P0033"));
  }
 }

 @Test void aWriterNeverWaitsBehindAnExclusiveClosureAndOtherWorkshopsContinue()throws Exception{
  Actor other=actor();
  try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
   var exclusive=new CountDownLatch(1);var release=new CountDownLatch(1);
   Future<?> closing=executor.submit(()->tx(()->{gate.lockExclusive(own.workshop());exclusive.countDown();await(release);return store.restrict(own.workshop(),own.owner(),UUID.randomUUID());}));
   assertThat(exclusive.await(3,TimeUnit.SECONDS)).isTrue();
   try {
    Future<?> writer=executor.submit(()->jdbc.update("UPDATE clientes SET nombre='late' WHERE id=?",own.customer()));
    assertThatThrownBy(()->writer.get(1,TimeUnit.SECONDS)).satisfies(f->assertThat(sqlState(f)).isEqualTo("P0034"));
    assertThat(jdbc.update("UPDATE clientes SET nombre='other' WHERE id=?",other.customer())).isEqualTo(1);
   } finally {release.countDown();}
   closing.get(5,TimeUnit.SECONDS);
  }
 }

 @Test void aSnapshotTakenBeforeClosureCannotWriteAfterWaitingForItsCommit()throws Exception{
  try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
   TransactionTemplate rr=template();rr.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
   assertThatThrownBy(()->rr.execute(status->{
    assertThat(jdbc.queryForObject("SELECT cierre_estado FROM talleres WHERE id=?",String.class,own.workshop())).isEqualTo("ABIERTO");
    try{executor.submit(()->close(own,UUID.randomUUID())).get(5,TimeUnit.SECONDS);}catch(Exception e){throw new AssertionError(e);}
    jdbc.update("INSERT INTO clientes(nombre,apellido,telefono,taller_id) VALUES('stale','synthetic','1200000000',?)",own.workshop());return null;
   })).satisfies(f->assertThat(sqlState(f)).isEqualTo("40001"));
  }
  assertThat(jdbc.queryForObject("SELECT count(*) FROM clientes WHERE taller_id=? AND nombre='stale'",Long.class,own.workshop())).isZero();
 }

 @Test void changingTheAnchorWithoutTheExclusiveProtocolIsRejected(){
  var before=fingerprint();
  assertThatThrownBy(()->jdbc.update("UPDATE talleres SET cierre_version=cierre_version+1 WHERE id=?",own.workshop()))
    .satisfies(f->assertThat(sqlState(f)).isEqualTo("P0033"));
  assertThat(fingerprint()).isEqualTo(before);
 }

 private void awaitWaitingExclusive()throws InterruptedException{
  long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
  while(System.nanoTime()<end){
   if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_locks WHERE locktype='advisory' AND mode='ExclusiveLock' AND NOT granted)",Boolean.class)))return;
   Thread.sleep(10);
  }
  throw new AssertionError("Closure did not wait on the admitted transaction");
 }
 private static void await(CountDownLatch latch){try{if(!latch.await(3,TimeUnit.SECONDS))throw new AssertionError("Fixture deadline");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
 private TransactionTemplate template(){var tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(8);return tx;}
 private <T>T tx(Supplier<T> work){return template().execute(status->work.get());}
 private WorkshopClosureStore.Receipt close(Actor actor,UUID ref){return tx(()->{gate.lockExclusive(actor.workshop());return store.restrict(actor.workshop(),actor.owner(),ref);});}
 private List<Long> versions(long workshop){return jdbc.queryForList("SELECT token_version FROM users WHERE taller_id=? ORDER BY id",Long.class,workshop);}
 private AuthenticatedUserPrincipal principal(long user,long workshop){return new AuthenticatedUserPrincipal(users.findSessionByIdAndTallerId(user,workshop).orElseThrow(),clock.instant());}
 private String token(long user,long workshop){return jwt.generateToken(principal(user,workshop),workshop);}
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
 private Map<String,String> fingerprint(){Map<String,String> result=new TreeMap<>();for(String table:List.of("talleres","users","clientes","suscripciones","cuenta_cierres","cuenta_reautenticaciones","cuenta_exportaciones"))result.put(table,jdbc.queryForObject(
  "SELECT count(*)::text||':'||coalesce(md5(string_agg(md5(to_jsonb(r)::text)||':'||xmin::text,',' ORDER BY md5(to_jsonb(r)::text)||':'||xmin::text)),'') FROM public."+table+" r",String.class));return result;}
 private static String sqlState(Throwable failure){for(Throwable current=failure;current!=null;current=current.getCause())if(current instanceof SQLException sql)return sql.getSQLState();return null;}
 private record Actor(long workshop,long owner,long employee,long inactive,long customer){}
}
