package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopProfileErasureService.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Synthetic profiles only. Frozen history/proof guards stay enabled; no external provider or real account. */
@Testcontainers
@Timeout(120)
class WorkshopProfileErasureIT {
    @Container static final PostgreSQLContainer PG=new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("profile_erasure").withUsername("fixture").withPassword("synthetic-profile-secret")
            .withCreateContainerCmdModifier(command->command.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindIp("127.0.0.1"),ExposedPort.tcp(5432))));
    static JdbcTemplate jdbc; static DataSourceTransactionManager manager;
    long taller,user;UUID closure;WorkshopProfileErasureService service;
    @BeforeAll static void database() {
        var source=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);
    }
    @BeforeEach void seed() {
        taller=workshop();user=account(taller,"ADMIN",true);closure=UUID.randomUUID();
        jdbc.update("INSERT INTO suscripciones(taller_id,plan,estado) VALUES(?,'FREE','TRIAL')",taller);
        service=new WorkshopProfileErasureService(jdbc,manager,true);
    }
    @Test void removesOnlyProfilesAndQrWithAnImmutableReplayableReceipt() {
        long employee=account(taller,"USER",true),inactive=account(taller,"USER",false);
        qr();long foreign=workshop();account(foreign,"ADMIN",true);
        close(8);String foreignBefore=profiles(foreign),evidence=retained();
        Map<Long,Long> epochs=epochs();String oldEmail=jdbc.queryForObject("SELECT email FROM users WHERE id=?",String.class,user);
        UUID operation=UUID.randomUUID();Receipt result=service.suppress(taller,closure,operation);
        assertThat(result.status()).isEqualTo(Status.SUPPRESSED);assertThat(result.users()).isEqualTo(3);
        assertThat(result.qrRemoved()).isTrue();assertThat(result.receiptId()).isEqualTo(operation);
        assertThat(jdbc.queryForList("SELECT id FROM users WHERE taller_id=? ORDER BY id",Long.class,taller))
                .containsExactly(user,employee,inactive);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE taller_id=? AND (active OR email_verificado OR password<>'!' OR username<>'Usuario dado de baja' OR email NOT LIKE '%@cuenta-eliminada.invalid')",Long.class,taller)).isZero();
        epochs.forEach((id,version)->assertThat(jdbc.queryForObject("SELECT token_version FROM users WHERE id=?",Long.class,id)).isEqualTo(version+1));
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT email) FROM users WHERE taller_id=?",Long.class,taller)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT nombre='Taller dado de baja' AND email_contacto IS NULL AND telefono IS NULL AND alias_cobro IS NULL AND titular_cobro IS NULL AND entidad_cobro IS NULL AND NOT mostrar_en_resumen AND activo AND cierre_estado='RESTRINGIDO' FROM talleres WHERE id=?",Boolean.class,taller)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM taller_qr_cobro WHERE taller_id=?",Long.class,taller)).isZero();
        assertThat(retained()).isEqualTo(evidence);assertThat(profiles(foreign)).isEqualTo(foreignBefore);
        String once=profiles(taller)+receiptRows();Receipt repeated=service.suppress(taller,closure,operation);
        assertThat(repeated).isEqualTo(new Receipt(Status.REUSED,operation,result.users(),result.qrRemoved(),result.suppressedAt()));
        assertThat(profiles(taller)+receiptRows()).isEqualTo(once);
        long newWorkshop=workshop();long newUser=account(newWorkshop,"ADMIN",true);
        jdbc.update("UPDATE users SET email=? WHERE id=?",oldEmail,newUser);
        assertThat(newUser).isNotEqualTo(user);
        assertThat(new WorkshopOperationalDeletionProgress(jdbc,manager).read(taller,closure).hasRows()).isFalse();
        assertThat(new WorkshopOperationalDeletionService(jdbc,manager,true).deleteBatch(taller,closure,UUID.randomUUID(),
                WorkshopOperationalDeletionService.Category.CLIENTES).status()).isEqualTo(WorkshopOperationalDeletionService.Status.EMPTY);
    }
    @ParameterizedTest @ValueSource(strings={"OPEN","GRACE","WRONG_CLOSURE","WRONG_WORKSHOP"})
    void unavailableTargetsCannotChangeProfiles(String scenario) {
        if(!scenario.equals("OPEN"))close(scenario.equals("GRACE")?6:8);
        String before=profiles(taller)+receiptRows();
        reject(()->service.suppress(scenario.equals("WRONG_WORKSHOP")?workshop():taller,
                scenario.equals("WRONG_CLOSURE")?UUID.randomUUID():closure,UUID.randomUUID()));
        assertThat(profiles(taller)+receiptRows()).isEqualTo(before);
    }
    @Test void operationalRowsBlockProfileErasureUntilTheirOwnDeletionCompletes() {
        jdbc.update("INSERT INTO clientes(nombre,telefono,taller_id) VALUES('synthetic','123',?)",taller);close(8);
        String before=profiles(taller)+receiptRows();reject(()->service.suppress(taller,closure,UUID.randomUUID()));
        assertThat(profiles(taller)+receiptRows()).isEqualTo(before);
        new WorkshopOperationalDeletionService(jdbc,manager,true).deleteBatch(taller,closure,UUID.randomUUID(),
                WorkshopOperationalDeletionService.Category.CLIENTES);
        assertThat(service.suppress(taller,closure,UUID.randomUUID()).status()).isEqualTo(Status.SUPPRESSED);
    }
    @Test void operationIdentityCannotBeReboundAndAnotherOperationCannotOverwriteTheReceipt() {
        close(8);UUID op=UUID.randomUUID();service.suppress(taller,closure,op);String before=profiles(taller)+receiptRows();
        reject(()->service.suppress(taller,closure,UUID.randomUUID()));
        reject(()->service.suppress(taller,UUID.randomUUID(),op));
        assertThat(profiles(taller)+receiptRows()).isEqualTo(before);
    }
    @Test void tokenVersionOverflowRollsBackEveryProfileAndTheQr() {
        long employee=account(taller,"USER",true);qr();close(8);
        jdbc.update("UPDATE users SET token_version=? WHERE id=?",Long.MAX_VALUE,employee);
        String before=profiles(taller)+receiptRows();reject(()->service.suppress(taller,closure,UUID.randomUUID()));
        assertThat(profiles(taller)+receiptRows()).isEqualTo(before);
    }
    @Test void capacityFailsClosedWithoutPartiallyErasingTheOwner() {
        jdbc.update("INSERT INTO users(username,email,password,role,taller_id,active,email_verificado,token_version) SELECT 'synthetic',?||n||'@synthetic.invalid','synthetic','USER',?,true,true,0 FROM generate_series(1,1000) n",UUID.randomUUID().toString(),taller);
        close(8);String before=profiles(taller)+receiptRows();reject(()->service.suppress(taller,closure,UUID.randomUUID()));
        assertThat(profiles(taller)+receiptRows()).isEqualTo(before);
    }
    @Test void failureAfterSqlReturnsRollsBackProfilesQrAndReceiptWithoutExposingDetails() {
        qr();close(8);String before=profiles(taller)+receiptRows();var executed=new AtomicBoolean();
        var failing=new JdbcTemplate(jdbc.getDataSource()) {
            @Override public <T> T queryForObject(String sql,RowMapper<T> mapper,Object... args) {
                T result=super.queryForObject(sql,mapper,args);
                if(sql.contains("cuenta_cierre_suprimir_perfil_v38(")){executed.set(true);throw new IllegalStateException("private email and SQL detail");}
                return result;
            }
        };
        reject(()->new WorkshopProfileErasureService(failing,manager,true).suppress(taller,closure,UUID.randomUUID()));
        assertThat(executed).isTrue();assertThat(profiles(taller)+receiptRows()).isEqualTo(before);
    }
    @Test void twoConcurrentCallsCommitOneErasureAndOneReplay() throws Exception {
        close(8);UUID operation=UUID.randomUUID();var ready=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<Receipt> call=()->{ready.await();return service.suppress(taller,closure,operation);};
            var one=executor.submit(call);var two=executor.submit(call);ready.countDown();
            var results=List.of(one.get(20,TimeUnit.SECONDS),two.get(20,TimeUnit.SECONDS));
            assertThat(results).extracting(Receipt::status).containsExactlyInAnyOrder(Status.SUPPRESSED,Status.REUSED);
            assertThat(results.get(0).suppressedAt()).isEqualTo(results.get(1).suppressedAt());
        }
        assertThat(jdbc.queryForObject("SELECT token_version FROM users WHERE id=?",Long.class,user)).isEqualTo(2);
    }
    @Test void newReadCommittedTransactionSurvivesAnUnrelatedCallerRollback() {
        close(8);long other=workshop();account(other,"ADMIN",true);String before=profiles(other);var observed=new AtomicBoolean();
        var inspecting=new JdbcTemplate(jdbc.getDataSource()) {
            @Override public <T> T queryForObject(String sql,RowMapper<T> mapper,Object... args) {
                if(sql.contains("cuenta_cierre_suprimir_perfil_v38(")) {
                    assertThat(queryForObject("SHOW transaction_isolation",String.class)).isEqualTo("read committed");observed.set(true);
                }
                return super.queryForObject(sql,mapper,args);
            }
        };
        var outer=new TransactionTemplate(manager);outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.executeWithoutResult(status->{jdbc.update("UPDATE talleres SET nombre='rollback synthetic' WHERE id=?",other);
            new WorkshopProfileErasureService(inspecting,manager,true).suppress(taller,closure,UUID.randomUUID());status.setRollbackOnly();});
        assertThat(observed).isTrue();assertThat(profiles(other)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cuenta_perfil_bajas WHERE taller_id=?",Long.class,taller)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"LIVE","UNCERTAIN","PENDING","EXPIRED"})
    void onlyALiveSmtpLeaseDelaysErasureAndNoEffectIsFalselyAcknowledged(String scenario) {
        close(8);UUID effect=jdbc.queryForObject("SELECT efecto_id FROM cuenta_cierre_efectos WHERE taller_id=?",UUID.class,taller);
        if(scenario.equals("LIVE")||scenario.equals("EXPIRED")) jdbc.update("UPDATE cuenta_cierre_efectos SET estado='EN_CURSO',intentos=1,lease_token=?,lease_until=clock_timestamp()+(? * interval '1 second') WHERE efecto_id=?",UUID.randomUUID(),scenario.equals("LIVE")?120:-1,effect);
        else if(scenario.equals("UNCERTAIN"))jdbc.update("UPDATE cuenta_cierre_efectos SET estado='INCIERTO' WHERE efecto_id=?",effect);
        String before=rows("cuenta_cierre_efectos");
        if(scenario.equals("LIVE"))reject(()->service.suppress(taller,closure,UUID.randomUUID()));
        else assertThat(service.suppress(taller,closure,UUID.randomUUID()).status()).isEqualTo(Status.SUPPRESSED);
        assertThat(rows("cuenta_cierre_efectos")).isEqualTo(before);
    }
    @Test void directUpdatesAndForgedContextCannotBypassThePrivateCapability() {
        close(8);String before=profiles(taller)+receiptRows();
        assertThatThrownBy(()->jdbc.update("UPDATE users SET username='Usuario dado de baja',email=?||'@cuenta-eliminada.invalid',password='!',active=false,email_verificado=false,token_version=token_version+1 WHERE id=?",UUID.randomUUID().toString(),user)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(status->{
            new WorkshopClosureGate(jdbc).lockExclusive(taller);
            jdbc.execute("SET LOCAL ordenfix.profile_erasure='true'");
            jdbc.update("DELETE FROM taller_qr_cobro WHERE taller_id=?",taller);
            jdbc.update("UPDATE talleres SET nombre='Taller dado de baja',email_contacto=NULL,telefono=NULL,alias_cobro=NULL,titular_cobro=NULL,entidad_cobro=NULL,mostrar_en_resumen=false WHERE id=?",taller);
        })).isInstanceOf(RuntimeException.class);
        assertThat(profiles(taller)+receiptRows()).isEqualTo(before);
    }
    private long workshop() {return jdbc.queryForObject("INSERT INTO talleres(nombre,email_contacto,telefono,alias_cobro,titular_cobro,entidad_cobro) VALUES('synthetic workshop','contact@synthetic.invalid','123','synthetic-alias','synthetic-owner','synthetic-bank') RETURNING id",Long.class);}
    private long account(long workshop,String role,boolean active) {return jdbc.queryForObject("INSERT INTO users(username,email,password,role,taller_id,active,email_verificado,token_version) VALUES('synthetic owner',?,'old-password-hash',?,?,?,true,0) RETURNING id",Long.class,UUID.randomUUID()+"@synthetic.invalid",role,workshop,active);}
    private void qr(){jdbc.update("INSERT INTO taller_qr_cobro(taller_id,png,sha256) VALUES(?,?,?)",taller,new byte[]{1,2,3},"a".repeat(64));}
    private void close(int daysAgo) {
        Instant now=jdbc.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class).toInstant().minus(Duration.ofDays(daysAgo)).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        new TransactionTemplate(manager).executeWithoutResult(status->{
            new WorkshopClosureGate(jdbc).lockExclusive(taller);String proof=hex();
            jdbc.update("INSERT INTO cuenta_cierre_confirmaciones(token_hash,user_id,taller_id,token_version,session_hash,proposito,operacion_id,cierre_referencia,cierre_version,creada_en,expira_en) VALUES(?,?,?,0,?,'CERRAR',?,?,0,?,?)",proof,user,taller,hex(),closure,closure,Timestamp.from(now),Timestamp.from(now.plusSeconds(120)));
            jdbc.update("UPDATE cuenta_cierre_confirmaciones SET usada_en=? WHERE token_hash=?",Timestamp.from(now),proof);
            var receipt=new WorkshopClosureStore(jdbc,Clock.fixed(now,ZoneOffset.UTC)).restrict(taller,user,closure);
            jdbc.update("INSERT INTO cuenta_cierre_operaciones(operacion_id,taller_id,user_id,proposito,cierre_referencia,cierre_version,request_digest,proof_hash,estado_resultante,politica,confirmado_en,reversible_hasta,eliminacion_prevista_en,registrada_en) VALUES(?,?,?,'CERRAR',?,1,?,?,'RESTRINGIDO','ordenfix-cierre/1',?,?,?,?)",closure,taller,user,closure,hex(),proof,Timestamp.from(receipt.confirmedAt()),Timestamp.from(receipt.reversibleUntil()),Timestamp.from(receipt.deletionExpectedBy()),Timestamp.from(now));
            new WorkshopClosureEffects(jdbc).enqueueClose(closure,closure,taller,user,now);
        });
    }
    private Map<Long,Long> epochs(){var result=new HashMap<Long,Long>();jdbc.query("SELECT id,token_version FROM users WHERE taller_id=?",row->{result.put(row.getLong(1),row.getLong(2));},taller);return result;}
    private String profiles(long id) {return jdbc.queryForObject("SELECT (SELECT to_jsonb(t)::text||xmin::text FROM talleres t WHERE id=?)||coalesce((SELECT string_agg(to_jsonb(u)::text||xmin::text,',' ORDER BY id) FROM users u WHERE taller_id=?),'')||coalesce((SELECT to_jsonb(q)::text||xmin::text FROM taller_qr_cobro q WHERE taller_id=?),'')",String.class,id,id,id);}
    private String rows(String table){return jdbc.queryForObject("SELECT coalesce(string_agg(to_jsonb(r)::text||xmin::text,',' ORDER BY to_jsonb(r)::text),'') FROM public."+table+" r WHERE taller_id=?",String.class,taller);}
    private String receiptRows(){return rows("cuenta_perfil_bajas");}
    private String retained(){return rows("cuenta_cierres")+rows("cuenta_cierre_operaciones")+rows("cuenta_cierre_efectos")+rows("cuenta_cierre_confirmaciones")+rows("suscripciones");}
    private static String hex(){return UUID.randomUUID().toString().replace("-","").repeat(2);}
    private static void reject(Runnable call){assertThatThrownBy(call::run).isInstanceOfSatisfying(Rejected.class,failure->{assertThat(failure.code()).isEqualTo(Rejected.Code.UNAVAILABLE);assertThat(failure).hasNoCause();});}
}
