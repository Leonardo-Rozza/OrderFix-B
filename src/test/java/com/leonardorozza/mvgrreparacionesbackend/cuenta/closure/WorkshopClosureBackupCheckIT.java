package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import static com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosureBackupCheck.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Disposable PostgreSQL and real V33/V34 guards. No provider, production backup or write operation in the checker. */
@Testcontainers
class WorkshopClosureBackupCheckIT {
    @Container static final PostgreSQLContainer PG=new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("closure_backup_check").withUsername("fixture").withPassword("fixture-password");
    static JdbcTemplate jdbc;static DataSourceTransactionManager manager;static WorkshopClosureGate gate;static WorkshopClosureStore store;
    WorkshopClosureBackupCheck check;
    @BeforeAll static void database(){
        var source=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);gate=new WorkshopClosureGate(jdbc);store=new WorkshopClosureStore(jdbc,Clock.systemUTC());
    }
    @BeforeEach void setup(){check=new WorkshopClosureBackupCheck(jdbc,manager);}
    @Test void matchingRestrictedStateIsReadWithoutDmlAndDoesNotAuthorizeReopening(){
        var actor=actor();var expected=close(actor);String before=fingerprint();
        Report report=check.compare(List.of(expected));assertThat(report.status()).isEqualTo(Status.COMPARACION_COMPATIBLE);
        assertThat(report.notice()).isEqualTo(Notice.NO_AUTORIZA_REAPERTURA);assertThat(report.findings()).isEmpty();
        assertThat(fingerprint()).isEqualTo(before);
    }
    @Test void restoredStateUsesTheHistoricalReferenceWhenTheAnchorReferenceIsNull(){
        var actor=actor();var closed=close(actor);restore(actor,closed.reference());
        var expected=new Evidence(actor.workshop(),actor.owner(),closed.reference(),2,State.RESTORED,2);
        assertThat(jdbc.queryForObject("SELECT cierre_referencia IS NULL FROM talleres WHERE id=?",Boolean.class,actor.workshop())).isTrue();
        assertThat(check.compare(List.of(expected)).status()).isEqualTo(Status.COMPARACION_COMPATIBLE);
    }
    @Test void backupBeforeClosureCannotBeMistakenForTheSuppliedClosedState(){
        var actor=actor();var expected=new Evidence(actor.workshop(),actor.owner(),UUID.randomUUID(),1,State.RESTRICTED,1);
        assertThat(check.compare(List.of(expected)).findings()).containsExactly(new Finding(actor.workshop(),Issue.DATABASE_BEHIND));
    }
    @Test void missingWorkshopAndNewerDatabaseProduceDifferentFindings(){
        var missing=new Evidence(Long.MAX_VALUE,1,UUID.randomUUID(),1,State.RESTRICTED,1);
        assertThat(check.compare(List.of(missing)).findings()).containsExactly(new Finding(Long.MAX_VALUE,Issue.DATABASE_MISSING));
        var actor=actor();var old=close(actor);restore(actor,old.reference());
        assertThat(check.compare(List.of(old)).findings()).extracting(Finding::issue).contains(Issue.DATABASE_AHEAD,Issue.OWNER_EPOCH_AHEAD);
    }
    @Test void equalGenerationDoesNotHideAReferenceOrOwnerEpochDifference(){
        var actor=actor();var expected=close(actor);
        var foreign=new Evidence(actor.workshop(),actor.owner(),UUID.randomUUID(),1,State.RESTRICTED,1);
        assertThat(check.compare(List.of(foreign)).findings()).containsExactly(new Finding(actor.workshop(),Issue.REFERENCE_OR_STATE_DIVERGENT));
        var newerEpoch=new Evidence(actor.workshop(),actor.owner(),expected.reference(),1,State.RESTRICTED,2);
        assertThat(check.compare(List.of(newerEpoch)).findings()).containsExactly(new Finding(actor.workshop(),Issue.OWNER_EPOCH_BEHIND));
    }
    @Test void oneThousandMissingIdsAreComparedWithoutTreatingTheBundleAsComplete(){
        var input=IntStream.rangeClosed(1,1000).mapToObj(i->new Evidence(Long.MAX_VALUE-i,1,new UUID(0,i),1,State.RESTRICTED,1)).toList();
        var report=check.compare(input);assertThat(report.checked()).isEqualTo(1000);assertThat(report.findings()).hasSize(1000);
        assertThat(report.findings()).allMatch(item->item.issue()==Issue.DATABASE_MISSING);
        assertThat(report.notice()).isEqualTo(Notice.NO_AUTORIZA_REAPERTURA);
    }
    @Test void aCallerUncommittedWriteIsNotPartOfTheIndependentSnapshot(){
        var actor=actor();var expected=close(actor);
        new TransactionTemplate(manager).executeWithoutResult(status->{
            jdbc.update("UPDATE users SET token_version=token_version+1 WHERE id=?",actor.owner());
            assertThat(check.compare(List.of(expected)).status()).isEqualTo(Status.COMPARACION_COMPATIBLE);status.setRollbackOnly();
        });
    }
    @Test @SuppressWarnings("unchecked") void aConcurrentCommitCannotMixOwnerEpochsInsideOneReport()throws Exception {
        var first=actor();var second=actor();var firstEvidence=close(first);var secondEvidence=close(second);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);JdbcTemplate observed=mock(JdbcTemplate.class);
        doAnswer(call->{
            String sql=call.getArgument(0);PreparedStatementSetter setter=call.getArgument(1);RowMapper<Snapshot> mapper=call.getArgument(2);
            return jdbc.query(sql,setter,(rs,row)->{
                Snapshot snapshot=mapper.mapRow(rs,row);
                if(row==0){entered.countDown();try{assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
                return snapshot;
            });
        }).when(observed).query(anyString(),any(PreparedStatementSetter.class),any(RowMapper.class));
        doAnswer(call->{jdbc.execute(call.getArgument(0,String.class));return null;}).when(observed).execute(anyString());
        var concurrentCheck=new WorkshopClosureBackupCheck(observed,manager);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var report=executor.submit(()->concurrentCheck.compare(List.of(firstEvidence,secondEvidence)));
            try {
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                new TransactionTemplate(manager).executeWithoutResult(status->{jdbc.update("UPDATE users SET token_version=token_version+1 WHERE id IN (?,?)",first.owner(),second.owner());});
            } finally {release.countDown();}
            assertThat(report.get(5,TimeUnit.SECONDS).status()).isEqualTo(Status.COMPARACION_COMPATIBLE);
        }
        assertThat(check.compare(List.of(firstEvidence,secondEvidence)).findings()).extracting(Finding::issue)
                .containsExactly(Issue.OWNER_EPOCH_AHEAD,Issue.OWNER_EPOCH_AHEAD);
    }
    private Evidence close(Actor actor){UUID reference=UUID.randomUUID();new TransactionTemplate(manager).executeWithoutResult(status->{gate.lockExclusive(actor.workshop());store.restrict(actor.workshop(),actor.owner(),reference);});return new Evidence(actor.workshop(),actor.owner(),reference,1,State.RESTRICTED,1);}
    private void restore(Actor actor,UUID reference){new TransactionTemplate(manager).executeWithoutResult(status->{gate.lockExclusive(actor.workshop());store.restore(actor.workshop(),actor.owner(),reference);});}
    private Actor actor(){String mark=UUID.randomUUID().toString();long workshop=jdbc.queryForObject("INSERT INTO talleres(nombre) VALUES('backup synthetic') RETURNING id",Long.class);
        long owner=jdbc.queryForObject("INSERT INTO users(username,email,password,role,taller_id,active,email_verificado,token_version) VALUES(?,?,?,'ADMIN',?,true,true,0) RETURNING id",Long.class,
                mark,mark+"@synthetic.invalid","not-a-login-secret",workshop);return new Actor(workshop,owner);}
    private String fingerprint(){StringBuilder value=new StringBuilder();for(String table:List.of("talleres","users","cuenta_cierres"))value.append(jdbc.queryForObject(
            "SELECT coalesce(string_agg(to_jsonb(r)::text||xmin::text,',' ORDER BY to_jsonb(r)::text),'') FROM public."+table+" r",String.class));return value.toString();}
    private record Actor(long workshop,long owner){}
}
