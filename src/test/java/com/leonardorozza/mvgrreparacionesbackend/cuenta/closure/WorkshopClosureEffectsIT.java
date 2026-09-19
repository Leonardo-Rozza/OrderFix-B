package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** PostgreSQL V34, synthetic ports and a consumer fixture. Command authorization is tested separately. */
@Testcontainers
class WorkshopClosureEffectsIT {
    @Container static final PostgreSQLContainer PG=new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("closure_effects").withUsername("fixture").withPassword("fixture-password");
    static JdbcTemplate jdbc;static DataSourceTransactionManager manager;
    WorkshopClosureEffects effects;WorkshopClosureGate gate;MutableClock clock;
    long taller,user,subscription;UUID closure;
    @BeforeAll static void database() {
        var source=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);
    }
    @BeforeEach void seed() {
        jdbc.execute("TRUNCATE TABLE cuenta_cierre_efectos");
        effects=new WorkshopClosureEffects(jdbc);gate=new WorkshopClosureGate(jdbc);clock=new MutableClock(Instant.now());
        taller=jdbc.queryForObject("INSERT INTO talleres(nombre) VALUES('effects synthetic') RETURNING id",Long.class);
        subscription=jdbc.queryForObject("INSERT INTO suscripciones(taller_id,plan,estado) VALUES(?,'FREE','TRIAL') RETURNING id",Long.class,taller);
        user=jdbc.queryForObject("INSERT INTO users(username,email,password,role,taller_id,active,email_verificado,token_version) VALUES(?,?,?,'ADMIN',?,true,true,0) RETURNING id",
                Long.class,UUID.randomUUID().toString(),UUID.randomUUID()+"@synthetic.invalid","not-a-login-secret",taller);
        closure=UUID.randomUUID();
    }
    @Test void noPortsDoNotClaimOrChangePendingWork() {
        long link=link(true,"pending","remote-1");close();
        String before=fingerprint();assertThat(worker(null,null).runNext()).isFalse();
        assertThat(fingerprint()).isEqualTo(before);assertThat(state(link)).isEqualTo("PENDIENTE");
    }
    @Test void effectsParticipateInTheCallerRollback() {
        link(true,"creating",null);closedFixture();
        assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(status->{
            gate.lockExclusive(taller);effects.enqueueClose(closure,closure,taller,user,clock.instant());throw new IllegalStateException("rollback fixture");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cuenta_cierre_efectos",Long.class)).isZero();
    }
    @Test void replayDoesNotWriteRowsOrExtendDates() {
        link(true,"pending","remote-1");close();String before=fingerprint();clock.advance(Duration.ofMinutes(1));
        enqueue();assertThat(fingerprint()).isEqualTo(before);
    }
    @Test void allCurrentAndHistoricalLinksRemainTargetsIncludingLocallyCanceled() {
        long old=link(false,"canceled","old-remote"),current=link(true,"authorized","new-remote");close();
        assertThat(state(old)).isEqualTo("PENDIENTE");assertThat(state(current)).isEqualTo("PENDIENTE");
        assertThat(effects.blocksRenewal(old)).isTrue();
    }
    @Test void creatingWithoutReferenceIsNotClaimedAndLateSqlAckAfterRestoreIsCaptured() {
        long link=link(true,"creating",null);close();var port=mock(ClosureRenewalPort.class);
        assertThat(worker(port,null).runNext()).isFalse();verifyNoInteractions(port);
        restoredFixture();
        jdbc.update("UPDATE subscription_provider_links SET external_subscription_id='late-remote',status='authorized' WHERE id=?",link);
        assertThat(jdbc.queryForObject("SELECT expected_external_id FROM cuenta_cierre_efectos WHERE link_id=?",String.class,link)).isEqualTo("late-remote");
        assertThatThrownBy(()->jdbc.update("UPDATE suscripciones SET plan='PRO',estado='ACTIVA' WHERE id=?",subscription)).isInstanceOf(RuntimeException.class);
        assertThat(effects.blocksRenewal(link)).isTrue();
    }
    @Test void alreadyCanceledRemoteIsConfirmedWithoutSendingCancellationAgain() {
        long link=link(true,"canceled","remote-1");close();var port=mock(ClosureRenewalPort.class);
        when(port.inspect(any())).thenAnswer(call->observation(call.getArgument(0),ClosureRenewalPort.State.CANCELED));
        assertThat(worker(port,null).runNext()).isTrue();assertThat(state(link)).isEqualTo("CONFIRMADO");
        assertThat(worker(port,null).runNext()).isFalse();verify(port,never()).cancel(any());
    }
    @Test void providerIoHappensOutsideTransactionsAndConfirmationCannotTouchCurrentReplacement() {
        long old=link(false,"authorized","old-remote");long current=link(true,"authorized","new-remote");close();
        jdbc.update("UPDATE cuenta_cierre_efectos SET available_at=? WHERE link_id=?",Timestamp.from(clock.instant().plusSeconds(3600)),current);
        String projection=jdbc.queryForObject("SELECT mp_preapproval_id FROM suscripciones WHERE id=?",String.class,subscription);
        var port=mock(ClosureRenewalPort.class);
        when(port.inspect(any())).thenAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return observation(call.getArgument(0),ClosureRenewalPort.State.ACTIVE);});
        when(port.cancel(any())).thenAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return observation(call.getArgument(0),ClosureRenewalPort.State.CANCELED);});
        assertThat(worker(port,null).runNext()).isTrue();
        assertThat(state(old)).isEqualTo("CONFIRMADO");
        assertThat(jdbc.queryForObject("SELECT mp_preapproval_id FROM suscripciones WHERE id=?",String.class,subscription)).isEqualTo(projection);
    }
    @Test void mismatchedProviderIdentityIsUncertainAndNeverCanceled() {
        long link=link(true,"pending","remote-1");close();var port=mock(ClosureRenewalPort.class);
        when(port.inspect(any())).thenReturn(new ClosureRenewalPort.Observation("foreign","foreign",ClosureRenewalPort.State.ACTIVE));
        worker(port,null).runNext();assertThat(state(link)).isEqualTo("INCIERTO");verify(port,never()).cancel(any());
    }
    @Test void ambiguousEmailIsNotRetried() {
        close();var port=mock(ClosureNotificationPort.class);when(port.send(any())).thenThrow(new IllegalStateException("synthetic ambiguity"));
        assertThat(worker(null,port).runNext()).isTrue();assertThat(worker(null,port).runNext()).isFalse();
        verify(port,times(1)).send(any());
        assertThat(jdbc.queryForObject("SELECT estado FROM cuenta_cierre_efectos WHERE tipo='AVISO_CIERRE'",String.class)).isEqualTo("INCIERTO");
    }
    @Test void crashOnLastAttemptIsRecoveredToUncertain() {
        long link=link(true,"pending","remote-1");close();
        jdbc.update("UPDATE cuenta_cierre_efectos SET estado='EN_CURSO',intentos=10,lease_token=?,lease_until=? WHERE link_id=?",
                UUID.randomUUID(),Timestamp.from(clock.instant().minusSeconds(1)),link);
        var port=mock(ClosureRenewalPort.class);assertThat(worker(port,null).runNext()).isFalse();
        assertThat(state(link)).isEqualTo("INCIERTO");verifyNoInteractions(port);
    }
    @Test void expiredEmailLeaseBecomesUncertainWithoutResending() {
        close();jdbc.update("UPDATE cuenta_cierre_efectos SET estado='EN_CURSO',intentos=1,lease_token=?,lease_until=? WHERE tipo='AVISO_CIERRE'",
                UUID.randomUUID(),Timestamp.from(clock.instant().minusSeconds(1)));
        var port=mock(ClosureNotificationPort.class);assertThat(worker(null,port).runNext()).isFalse();verifyNoInteractions(port);
        assertThat(jdbc.queryForObject("SELECT estado FROM cuenta_cierre_efectos WHERE tipo='AVISO_CIERRE'",String.class)).isEqualTo("INCIERTO");
    }
    @Test void staleAcknowledgementCannotConfirmAnExpiredLeaseAndRetryUsesTheSameKey() {
        long link=link(true,"authorized","remote-1");close();var port=mock(ClosureRenewalPort.class);
        List<UUID> keys=new ArrayList<>();
        when(port.inspect(any())).thenAnswer(call->{var target=(ClosureRenewalPort.Target)call.getArgument(0);keys.add(target.operationKey());return observation(target,ClosureRenewalPort.State.ACTIVE);});
        when(port.cancel(any())).thenAnswer(call->{clock.advance(Duration.ofMinutes(3));return observation(call.getArgument(0),ClosureRenewalPort.State.CANCELED);});
        worker(port,null).runNext();assertThat(state(link)).isEqualTo("EN_CURSO");
        doAnswer(call->{var target=(ClosureRenewalPort.Target)call.getArgument(0);keys.add(target.operationKey());return observation(target,ClosureRenewalPort.State.CANCELED);}).when(port).inspect(any());
        worker(port,null).runNext();assertThat(state(link)).isEqualTo("CONFIRMADO");
        assertThat(keys).hasSize(2);assertThat(keys.get(0)).isEqualTo(keys.get(1));verify(port,times(1)).cancel(any());
    }
    @Test void inspectionThatExhaustsTheLeaseCannotStartCancellation() {
        long link=link(true,"authorized","remote-1");close();var port=mock(ClosureRenewalPort.class);
        when(port.inspect(any())).thenAnswer(call->{clock.advance(Duration.ofMinutes(3));return observation(call.getArgument(0),ClosureRenewalPort.State.ACTIVE);});
        assertThat(worker(port,null).runNext()).isTrue();verify(port,never()).cancel(any());
        assertThat(state(link)).isEqualTo("EN_CURSO");
    }

    @Test void concurrentWorkersNeverClaimTheSameLiveLease() throws Exception {
        long link=link(true,"pending","remote-1");close();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var port=mock(ClosureRenewalPort.class);
        when(port.inspect(any())).thenAnswer(call->{entered.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();return observation(call.getArgument(0),ClosureRenewalPort.State.CANCELED);});
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->worker(port,null).runNext());assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            try {assertThat(pool.submit(()->worker(port,null).runNext()).get(5,TimeUnit.SECONDS)).isFalse();}
            finally {release.countDown();}
            assertThat(first.get(5,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(state(link)).isEqualTo("CONFIRMADO");verify(port,times(1)).inspect(any());
    }
    @Test void aRowLockWaitCannotAccreditAnAcknowledgementAfterItsLeaseExpires() throws Exception {
        long link=link(true,"pending","remote-1");close();var insideIo=new CountDownLatch(1);var finishIo=new CountDownLatch(1);
        var locked=new CountDownLatch(1);var releaseLock=new CountDownLatch(1);var port=mock(ClosureRenewalPort.class);
        when(port.inspect(any())).thenAnswer(call->{insideIo.countDown();assertThat(finishIo.await(5,TimeUnit.SECONDS)).isTrue();return observation(call.getArgument(0),ClosureRenewalPort.State.CANCELED);});
        try(var pool=Executors.newFixedThreadPool(2)) {
            var running=pool.submit(()->worker(port,null).runNext());assertThat(insideIo.await(5,TimeUnit.SECONDS)).isTrue();
            var holder=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(status->{
                jdbc.queryForList("SELECT efecto_id FROM cuenta_cierre_efectos WHERE link_id=? FOR UPDATE",link);locked.countDown();
                try{assertThat(releaseLock.await(5,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
            }));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            try {
                finishIo.countDown();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);boolean waiting=false;
                while(System.nanoTime()<deadline) {
                    waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE 'SELECT efecto_id FROM public.cuenta_cierre_efectos%')",Boolean.class));
                    if(waiting)break;Thread.sleep(10);
                }
                assertThat(waiting).isTrue();clock.advance(Duration.ofMinutes(3));
            } finally {releaseLock.countDown();finishIo.countDown();}
            holder.get(5,TimeUnit.SECONDS);assertThat(running.get(5,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(state(link)).isEqualTo("EN_CURSO");
    }

    @Test void contradictoryLateObservationDoesNotEraseIdentityOrKeepClaimingCancellationConfirmed() {
        long link=link(true,"canceled","remote-1");close();var port=mock(ClosureRenewalPort.class);
        when(port.inspect(any())).thenAnswer(call->observation(call.getArgument(0),ClosureRenewalPort.State.CANCELED));worker(port,null).runNext();
        restoredFixture();jdbc.update("UPDATE subscription_provider_links SET status='authorized' WHERE id=?",link);
        assertThat(state(link)).isEqualTo("INCIERTO");assertThat(effects.blocksRenewal(link)).isTrue();
        assertThatThrownBy(()->jdbc.update("UPDATE subscription_provider_links SET external_subscription_id='other' WHERE id=?",link)).isInstanceOf(RuntimeException.class);
    }
    @Test void freshAuthorizedObservationWithTheSameStatusInvalidatesAnEarlierCancellationReceipt() {
        long link=link(true,"authorized","remote-1");close();var port=mock(ClosureRenewalPort.class);
        when(port.inspect(any())).thenAnswer(call->observation(call.getArgument(0),ClosureRenewalPort.State.CANCELED));worker(port,null).runNext();
        assertThat(state(link)).isEqualTo("CONFIRMADO");restoredFixture();
        jdbc.update("UPDATE subscription_provider_links SET status='authorized',updated_at=updated_at+INTERVAL '1 second' WHERE id=?",link);
        assertThat(state(link)).isEqualTo("INCIERTO");assertThat(effects.blocksRenewal(link)).isTrue();
    }
    @Test void archivingAConfirmedLinkForAnExplicitNewCheckoutPreservesItsReceipt() {
        long old=link(true,"authorized","old-remote");close();var port=mock(ClosureRenewalPort.class);
        when(port.inspect(any())).thenAnswer(call->observation(call.getArgument(0),ClosureRenewalPort.State.CANCELED));worker(port,null).runNext();restoredFixture();
        jdbc.update("UPDATE subscription_provider_links SET is_current=false,updated_at=updated_at+INTERVAL '1 second' WHERE id=?",old);
        long fresh=link(true,"creating",null);
        assertThat(state(old)).isEqualTo("CONFIRMADO");assertThat(effects.blocksRenewal(old)).isTrue();
        assertThat(effects.blocksRenewal(fresh)).isFalse();
    }

    @Test void oneThousandLinksAreCapturedAndOneThousandOneRollsBack() {
        insertLinks(1000);close();assertThat(jdbc.queryForObject("SELECT count(*) FROM cuenta_cierre_efectos WHERE tipo='CANCELAR_RENOVACION'",Long.class)).isEqualTo(1000);
        restoredFixture();link(false,"canceled","extra-remote");
        assertThatThrownBy(this::enqueue).isInstanceOf(ClosurePreparationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cuenta_cierre_efectos WHERE tipo='CANCELAR_RENOVACION'",Long.class)).isEqualTo(1000);
    }
    @Test void unrepresentedProviderEvidenceBlocksNewEntitlementAfterRestore() {
        jdbc.update("UPDATE suscripciones SET mp_status='authorized',mp_preapproval_id='orphan' WHERE id=?",subscription);close();
        assertThat(effects.blocksWorkshopRenewal(taller)).isTrue();restoredFixture();
        assertThatThrownBy(()->jdbc.update("UPDATE suscripciones SET plan='PRO',estado='ACTIVA' WHERE id=?",subscription)).isInstanceOf(RuntimeException.class);
    }
    @Test void providerContractsRedactRemoteIdentifiersFromToString() {
        var target=new ClosureRenewalPort.Target(UUID.randomUUID(),1,"private-reference","private-id");
        assertThat(target.toString()).doesNotContain("private-");
        assertThat(observation(target,ClosureRenewalPort.State.CANCELED).toString()).doesNotContain("private-");
    }
    @Test void exhaustedUncertainRenewalCanBeReconciledWithoutRetryingCancellationOrChangingItsTarget() {
        long link=uncertainLink();UUID id=effectId(link);var port=mock(ClosureRenewalPort.class);
        String before=fingerprint();String linkBefore=linkFingerprint();String subscriptionBefore=subscriptionFingerprint();
        Map<String,Object> targetBefore=jdbc.queryForMap("SELECT * FROM cuenta_cierre_efectos WHERE efecto_id=?",id);
        when(port.inspect(any())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(fingerprint()).as("inspection must not claim a lease or consume another attempt").isEqualTo(before);
            ClosureRenewalPort.Target target=call.getArgument(0);
            assertThat(target.operationKey()).isEqualTo(id);assertThat(target.linkId()).isEqualTo(link);
            assertThat(target.externalReference()).isEqualTo(targetBefore.get("expected_external_reference"));
            assertThat(target.externalId()).isEqualTo(targetBefore.get("expected_external_id"));
            return observation(target,ClosureRenewalPort.State.CANCELED);
        });
        var reconciler=reconciler(port);
        assertThat(reconciler.reconcile(taller,id)).isEqualTo(WorkshopClosureRenewalReconciler.Result.CONFIRMED);
        assertThat(state(link)).isEqualTo("CONFIRMADO");
        Map<String,Object> after=jdbc.queryForMap("SELECT * FROM cuenta_cierre_efectos WHERE efecto_id=?",id);
        assertThat(after).containsEntry("intentos",10).containsEntry("lease_token",null).containsEntry("lease_until",null);
        assertThat(after.get("confirmed_at")).isNotNull();
        after.remove("estado");after.remove("confirmed_at");targetBefore.remove("estado");targetBefore.remove("confirmed_at");
        assertThat(after).isEqualTo(targetBefore);
        assertThat(linkFingerprint()).isEqualTo(linkBefore);assertThat(subscriptionFingerprint()).isEqualTo(subscriptionBefore);
        assertUnchangedReconciliation(reconciler,taller,id,WorkshopClosureRenewalReconciler.Result.REUSED);
        verify(port,times(1)).inspect(any());verify(port,never()).cancel(any());verifyNoMoreInteractions(port);
        assertThat(effects.blocksRenewal(link)).as("historical closure fencing survives a cancellation receipt").isTrue();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"ACTIVE","RETRYABLE","UNCERTAIN","NULL","NULL_STATE","FOREIGN_REFERENCE","FOREIGN_ID","THROWS"})
    void ambiguousInspectionKeepsEveryRowAndXminUnchanged(String kind) {
        long link=uncertainLink();UUID id=effectId(link);var port=mock(ClosureRenewalPort.class);
        when(port.inspect(any())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            ClosureRenewalPort.Target target=call.getArgument(0);
            return switch(kind) {
                case "NULL"->null;
                case "NULL_STATE"->new ClosureRenewalPort.Observation(target.externalReference(),target.externalId(),null);
                case "FOREIGN_REFERENCE"->new ClosureRenewalPort.Observation("other-reference",target.externalId(),ClosureRenewalPort.State.CANCELED);
                case "FOREIGN_ID"->new ClosureRenewalPort.Observation(target.externalReference(),"other-id",ClosureRenewalPort.State.CANCELED);
                case "THROWS"->throw new IllegalStateException("synthetic-provider-private-payload");
                default->observation(target,ClosureRenewalPort.State.valueOf(kind));
            };
        });
        assertUnchangedReconciliation(reconciler(port),taller,id,WorkshopClosureRenewalReconciler.Result.UNRESOLVED);
        assertThat(state(link)).isEqualTo("INCIERTO");verify(port,times(1)).inspect(any());verify(port,never()).cancel(any());
    }

    @Test void reconciliationWithoutPortAndInvalidCallerBoundaryNeverTouchesTheOutbox() {
        long link=uncertainLink();UUID id=effectId(link);
        assertUnchangedReconciliation(reconciler(null),taller,id,WorkshopClosureRenewalReconciler.Result.PORT_UNAVAILABLE);
        var port=mock(ClosureRenewalPort.class);var reconciler=reconciler(port);var before=reconciliationFingerprint();
        assertThatThrownBy(()->reconciler.reconcile(0,id)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->reconciler.reconcile(-1,id)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->reconciler.reconcile(taller,null)).isInstanceOf(IllegalArgumentException.class);
        new TransactionTemplate(manager).executeWithoutResult(status->
                assertThatThrownBy(()->reconciler.reconcile(taller,id)).isInstanceOf(IllegalStateException.class));
        assertThat(reconciliationFingerprint()).isEqualTo(before);verifyNoInteractions(port);
    }

    @Test void reconciliationDoesNotInspectForeignMissingNonCancellationOrNonUncertainEffects() {
        long link=link(true,"authorized","remote-1");close();UUID id=effectId(link);var port=mock(ClosureRenewalPort.class);var reconciler=reconciler(port);
        long foreign=jdbc.queryForObject("INSERT INTO talleres(nombre) VALUES('other reconciliation workshop') RETURNING id",Long.class);
        assertUnchangedReconciliation(reconciler,foreign,id,WorkshopClosureRenewalReconciler.Result.NOT_ELIGIBLE);
        assertUnchangedReconciliation(reconciler,taller,UUID.randomUUID(),WorkshopClosureRenewalReconciler.Result.NOT_ELIGIBLE);
        assertUnchangedReconciliation(reconciler,taller,id,WorkshopClosureRenewalReconciler.Result.NOT_ELIGIBLE);
        jdbc.update("UPDATE cuenta_cierre_efectos SET estado='EN_CURSO',intentos=1,lease_token=?,lease_until=? WHERE efecto_id=?",
                UUID.randomUUID(),Timestamp.from(clock.instant().plusSeconds(60)),id);
        assertUnchangedReconciliation(reconciler,taller,id,WorkshopClosureRenewalReconciler.Result.NOT_ELIGIBLE);
        jdbc.update("UPDATE cuenta_cierre_efectos SET estado='INCIERTO' WHERE taller_id=? AND tipo='AVISO_CIERRE'",taller);
        UUID notification=jdbc.queryForObject("SELECT efecto_id FROM cuenta_cierre_efectos WHERE taller_id=? AND tipo='AVISO_CIERRE'",UUID.class,taller);
        assertUnchangedReconciliation(reconciler,taller,notification,WorkshopClosureRenewalReconciler.Result.NOT_ELIGIBLE);
        verifyNoInteractions(port);
    }
    @Test void reconciliationCannotInventARemoteTargetFromAnUnrepresentedSubscription() {
        jdbc.update("UPDATE suscripciones SET mp_status='authorized',mp_preapproval_id='orphan' WHERE id=?",subscription);close();
        UUID review=jdbc.queryForObject("SELECT efecto_id FROM cuenta_cierre_efectos WHERE taller_id=? AND tipo='REVISAR_RENOVACION'",UUID.class,taller);
        var port=mock(ClosureRenewalPort.class);
        assertUnchangedReconciliation(reconciler(port),taller,review,WorkshopClosureRenewalReconciler.Result.NOT_ELIGIBLE);
        verifyNoInteractions(port);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"EFFECT_NOOP","LINK_NOOP","LINK_OBSERVATION"})
    void anotherSqlObservationDuringInspectionMakesTheCapturedVersionStaleEvenWithoutChangedValues(String mutation) {
        long link=uncertainLink();UUID id=effectId(link);var port=mock(ClosureRenewalPort.class);
        var observed=new AtomicReference<Map<String,String>>();
        when(port.inspect(any())).thenAnswer(call->{
            switch(mutation) {
                case "EFFECT_NOOP"->jdbc.update("UPDATE cuenta_cierre_efectos SET intentos=intentos WHERE efecto_id=?",id);
                case "LINK_NOOP"->jdbc.update("UPDATE subscription_provider_links SET status=status,updated_at=updated_at WHERE id=?",link);
                default->jdbc.update("UPDATE subscription_provider_links SET status='authorized',updated_at=updated_at+INTERVAL '1 second' WHERE id=?",link);
            }
            observed.set(reconciliationFingerprint());
            return observation(call.getArgument(0),ClosureRenewalPort.State.CANCELED);
        });
        assertThat(reconciler(port).reconcile(taller,id)).isEqualTo(WorkshopClosureRenewalReconciler.Result.STALE);
        assertThat(reconciliationFingerprint()).isEqualTo(observed.get());assertThat(state(link)).isEqualTo("INCIERTO");
        verify(port,times(1)).inspect(any());verify(port,never()).cancel(any());
    }

    @Test void concurrentReconciliationsInspectTheSameTargetButOnlyOneConfirmsItsCapturedVersion() throws Exception {
        long link=uncertainLink();UUID id=effectId(link);var port=mock(ClosureRenewalPort.class);
        var entered=new CountDownLatch(2);var finish=new CountDownLatch(1);
        when(port.inspect(any())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            entered.countDown();assertThat(finish.await(5,TimeUnit.SECONDS)).isTrue();
            return observation(call.getArgument(0),ClosureRenewalPort.State.CANCELED);
        });
        var firstReconciler=reconciler(port);var secondReconciler=reconciler(port);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->firstReconciler.reconcile(taller,id));
            var second=pool.submit(()->secondReconciler.reconcile(taller,id));
            try {assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();} finally {finish.countDown();}
            assertThat(List.of(first.get(5,TimeUnit.SECONDS),second.get(5,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(WorkshopClosureRenewalReconciler.Result.CONFIRMED,WorkshopClosureRenewalReconciler.Result.STALE);
        } finally {finish.countDown();}
        assertThat(state(link)).isEqualTo("CONFIRMADO");
        assertThat(jdbc.queryForObject("SELECT intentos FROM cuenta_cierre_efectos WHERE efecto_id=?",Integer.class,id)).isEqualTo(10);
        assertUnchangedReconciliation(firstReconciler,taller,id,WorkshopClosureRenewalReconciler.Result.REUSED);
        verify(port,times(2)).inspect(any());verify(port,never()).cancel(any());
    }

    @Test void anExclusiveWorkshopGateRejectsReconciliationBeforeInspectionOrDml() throws Exception {
        long link=uncertainLink();UUID id=effectId(link);var port=mock(ClosureRenewalPort.class);
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var before=reconciliationFingerprint();
        try(var pool=Executors.newSingleThreadExecutor()) {
            var holder=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(status->{
                gate.lockExclusive(taller);locked.countDown();
                try {assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();}
                catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}
            }));
            try {
                assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(()->reconciler(port).reconcile(taller,id)).isInstanceOf(WorkshopClosureBusyException.class);
                verifyNoInteractions(port);assertThat(reconciliationFingerprint()).isEqualTo(before);
            } finally {release.countDown();}
            holder.get(5,TimeUnit.SECONDS);
        } finally {release.countDown();}
    }

    @Test void anObservationAtTheDeadlineCannotConfirmTheEffect() {
        long link=uncertainLink();UUID id=effectId(link);var port=mock(ClosureRenewalPort.class);
        when(port.inspect(any())).thenAnswer(call->{clock.advance(Duration.ofSeconds(120));return observation(call.getArgument(0),ClosureRenewalPort.State.CANCELED);});
        assertUnchangedReconciliation(reconciler(port),taller,id,WorkshopClosureRenewalReconciler.Result.STALE);
        assertThat(state(link)).isEqualTo("INCIERTO");verify(port,never()).cancel(any());
    }
    @Test void waitingForTheLinkLockCannotConfirmAfterTheObservationDeadline() throws Exception {
        long link=uncertainLink();UUID id=effectId(link);var port=mock(ClosureRenewalPort.class);
        var insideIo=new CountDownLatch(1);var finishIo=new CountDownLatch(1);var locked=new CountDownLatch(1);var releaseLock=new CountDownLatch(1);
        when(port.inspect(any())).thenAnswer(call->{insideIo.countDown();assertThat(finishIo.await(5,TimeUnit.SECONDS)).isTrue();return observation(call.getArgument(0),ClosureRenewalPort.State.CANCELED);});
        var before=reconciliationFingerprint();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var running=pool.submit(()->reconciler(port).reconcile(taller,id));assertThat(insideIo.await(5,TimeUnit.SECONDS)).isTrue();
            var holder=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(status->{
                jdbc.queryForList("SELECT id FROM subscription_provider_links WHERE id=? FOR UPDATE",link);locked.countDown();
                try {assertThat(releaseLock.await(5,TimeUnit.SECONDS)).isTrue();}
                catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}
            }));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            try {
                finishIo.countDown();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);boolean waiting=false;
                while(System.nanoTime()<until) {
                    waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%subscription_provider_links%' AND query LIKE '%FOR SHARE%')",Boolean.class));
                    if(waiting)break;Thread.sleep(10);
                }
                assertThat(waiting).as("finalization must acquire the link lock after I/O").isTrue();
                clock.advance(Duration.ofSeconds(120));
            } finally {releaseLock.countDown();finishIo.countDown();}
            holder.get(5,TimeUnit.SECONDS);assertThat(running.get(5,TimeUnit.SECONDS)).isEqualTo(WorkshopClosureRenewalReconciler.Result.STALE);
        } finally {releaseLock.countDown();finishIo.countDown();}
        assertThat(reconciliationFingerprint()).isEqualTo(before);verify(port,never()).cancel(any());
    }

    @Test void aDeadlineReachedAfterTheUpdateRollsBackTheProvisionalReceipt() {
        long link=uncertainLink();UUID id=effectId(link);var port=mock(ClosureRenewalPort.class);var changed=new AtomicBoolean();
        when(port.inspect(any())).thenAnswer(call->observation(call.getArgument(0),ClosureRenewalPort.State.CANCELED));
        var connection=afterConfirmationUpdate(()->{changed.set(true);clock.advance(Duration.ofSeconds(120));});
        assertUnchangedReconciliation(reconciler(connection,port),taller,id,WorkshopClosureRenewalReconciler.Result.STALE);
        assertThat(changed.get()).as("the injected deadline occurred after a real SQL update").isTrue();
        assertThat(state(link)).isEqualTo("INCIERTO");verify(port,never()).cancel(any());
    }
    @Test void aDatabaseFailureAfterTheUpdateRollsBackAndReturnsOnlyASanitizedFailure() {
        long link=uncertainLink();UUID id=effectId(link);var port=mock(ClosureRenewalPort.class);var changed=new AtomicBoolean();
        when(port.inspect(any())).thenAnswer(call->observation(call.getArgument(0),ClosureRenewalPort.State.CANCELED));
        var connection=afterConfirmationUpdate(()->{changed.set(true);throw new org.springframework.dao.DataAccessResourceFailureException("synthetic-private-jdbc-payload");});
        var before=reconciliationFingerprint();
        assertThatThrownBy(()->reconciler(connection,port).reconcile(taller,id))
                .isInstanceOf(WorkshopClosureRenewalReconciler.Rejected.class).hasMessageNotContaining("synthetic-private-jdbc-payload");
        assertThat(changed.get()).isTrue();assertThat(reconciliationFingerprint()).isEqualTo(before);
        assertThat(state(link)).isEqualTo("INCIERTO");verify(port,never()).cancel(any());
    }

    @Test void reconcilingAnUncertainHistoricalLinkAfterRestoreKeepsItsFenceAndCurrentReplacementUntouched() {
        long old=uncertainLink();UUID id=effectId(old);restoredFixture();
        jdbc.update("UPDATE subscription_provider_links SET is_current=false,updated_at=updated_at+INTERVAL '1 second' WHERE id=?",old);
        long fresh=link(true,"creating",null);
        var currentBefore=jdbc.queryForMap("SELECT to_jsonb(l)::text AS row,xmin::text AS version FROM subscription_provider_links l WHERE id=?",fresh);
        String projectionBefore=subscriptionFingerprint();var port=mock(ClosureRenewalPort.class);
        when(port.inspect(any())).thenAnswer(call->{
            ClosureRenewalPort.Target target=call.getArgument(0);assertThat(target.linkId()).isEqualTo(old);
            return observation(target,ClosureRenewalPort.State.CANCELED);
        });
        assertThat(reconciler(port).reconcile(taller,id)).isEqualTo(WorkshopClosureRenewalReconciler.Result.CONFIRMED);
        assertThat(state(old)).isEqualTo("CONFIRMADO");assertThat(effects.blocksRenewal(old)).isTrue();assertThat(effects.blocksRenewal(fresh)).isFalse();
        assertThat(jdbc.queryForMap("SELECT to_jsonb(l)::text AS row,xmin::text AS version FROM subscription_provider_links l WHERE id=?",fresh)).isEqualTo(currentBefore);
        assertThat(subscriptionFingerprint()).isEqualTo(projectionBefore);
        assertThat(jdbc.queryForObject("SELECT cierre_estado FROM talleres WHERE id=?",String.class,taller)).isEqualTo("ABIERTO");
        verify(port,times(1)).inspect(any());verify(port,never()).cancel(any());
    }

    private long uncertainLink() {
        long link=link(true,"authorized","remote-reconciliation");close();
        jdbc.update("UPDATE cuenta_cierre_efectos SET estado='INCIERTO',intentos=10 WHERE link_id=?",link);
        return link;
    }
    private UUID effectId(long link){return jdbc.queryForObject("SELECT efecto_id FROM cuenta_cierre_efectos WHERE link_id=?",UUID.class,link);}
    private String linkFingerprint(){return jdbc.queryForObject("SELECT coalesce(string_agg(to_jsonb(l)::text||xmin::text,',' ORDER BY id),'') FROM subscription_provider_links l WHERE suscripcion_id=?",String.class,subscription);}
    private String subscriptionFingerprint(){return jdbc.queryForObject("SELECT to_jsonb(s)::text||xmin::text FROM suscripciones s WHERE id=?",String.class,subscription);}
    private Map<String,String> reconciliationFingerprint(){return Map.of("effects",fingerprint(),"links",linkFingerprint(),"subscription",subscriptionFingerprint());}
    private void assertUnchangedReconciliation(WorkshopClosureRenewalReconciler reconciler,long workshop,UUID id,WorkshopClosureRenewalReconciler.Result expected) {
        var before=reconciliationFingerprint();assertThat(reconciler.reconcile(workshop,id)).isEqualTo(expected);assertThat(reconciliationFingerprint()).isEqualTo(before);
    }
    private JdbcTemplate afterConfirmationUpdate(Runnable after) {
        return new JdbcTemplate(jdbc.getDataSource()) {
            @Override public int update(String sql,Object... args) {
                int changed=super.update(sql,args);
                if(changed>0 && sql.stripLeading().startsWith("UPDATE public.cuenta_cierre_efectos"))after.run();
                return changed;
            }
        };
    }
    private WorkshopClosureRenewalReconciler reconciler(ClosureRenewalPort port){return reconciler(jdbc,port);}
    @SuppressWarnings("unchecked") private WorkshopClosureRenewalReconciler reconciler(JdbcTemplate connection,ClosureRenewalPort port) {
        ObjectProvider<ClosureRenewalPort> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(port);
        return new WorkshopClosureRenewalReconciler(connection,manager,clock,provider);
    }

    private void close(){closedFixture();enqueue();}
    private void enqueue(){new TransactionTemplate(manager).executeWithoutResult(status->{gate.lockExclusive(taller);effects.enqueueClose(closure,closure,taller,user,clock.instant());});}
    private void closedFixture() {transition(false);}
    private void restoredFixture(){transition(true);}
    /** Real proof guards, exclusive gate and store; only the JWT/password reader is outside this consumer fixture. */
    private void transition(boolean restore) {
        new TransactionTemplate(manager).executeWithoutResult(status->{
            gate.lockExclusive(taller);
            String hash=hex(),purpose=restore?"RESTAURAR":"CERRAR";
            UUID operation=restore?UUID.randomUUID():closure;
            long epoch=jdbc.queryForObject("SELECT token_version FROM users WHERE id=?",Long.class,user);
            long generation=jdbc.queryForObject("SELECT cierre_version FROM talleres WHERE id=?",Long.class,taller);
            jdbc.update("""
                    INSERT INTO cuenta_cierre_confirmaciones(token_hash,user_id,taller_id,token_version,session_hash,proposito,
                        operacion_id,cierre_referencia,cierre_version,creada_en,expira_en)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?)
                    """,hash,user,taller,epoch,hex(),purpose,operation,closure,generation,Timestamp.from(clock.instant()),Timestamp.from(clock.instant().plusSeconds(120)));
            jdbc.update("UPDATE cuenta_cierre_confirmaciones SET usada_en=? WHERE token_hash=?",Timestamp.from(clock.instant()),hash);
            var store=new WorkshopClosureStore(jdbc,clock);
            var receipt=restore?store.restore(taller,user,closure):store.restrict(taller,user,closure);
            jdbc.update("""
                    INSERT INTO cuenta_cierre_operaciones(operacion_id,taller_id,user_id,proposito,cierre_referencia,cierre_version,
                        request_digest,proof_hash,estado_resultante,politica,confirmado_en,reversible_hasta,eliminacion_prevista_en,registrada_en)
                    VALUES(?,?,?,?,?,?,?,?,?,'ordenfix-cierre/1',?,?,?,?)
                    """,operation,taller,user,purpose,closure,generation+1,hex(),hash,restore?"ABIERTO":"RESTRINGIDO",
                    Timestamp.from(receipt.confirmedAt()),Timestamp.from(receipt.reversibleUntil()),Timestamp.from(receipt.deletionExpectedBy()),Timestamp.from(clock.instant()));
            if(restore)effects.enqueueRestore(operation,closure,taller,user,clock.instant());
        });
    }
    private long link(boolean current,String state,String remote) {
        remote=remote==null?null:remote+"-"+taller;
        String reference="ref-"+UUID.randomUUID();
        long id=jdbc.queryForObject("INSERT INTO subscription_provider_links(suscripcion_id,provider,external_reference,external_subscription_id,idempotency_key,status,is_current,created_at,updated_at) VALUES(?,'MERCADO_PAGO',?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id",Long.class,
                subscription,reference,remote,UUID.randomUUID().toString(),state,current);
        if(current)jdbc.update("UPDATE suscripciones SET mp_external_reference=?,mp_preapproval_id=?,mp_status=? WHERE id=?",reference,remote,state,subscription);
        return id;
    }
    private void insertLinks(int count) {for(int i=0;i<count;i++)link(i==count-1,"canceled","remote-"+i);}
    private String state(long link){return jdbc.queryForObject("SELECT estado FROM cuenta_cierre_efectos WHERE link_id=?",String.class,link);}
    private String fingerprint(){return jdbc.queryForObject("SELECT coalesce(string_agg(to_jsonb(e)::text||xmin::text,',' ORDER BY efecto_id),'') FROM cuenta_cierre_efectos e",String.class);}
    @SuppressWarnings("unchecked") private WorkshopClosureEffectWorker worker(ClosureRenewalPort renew,ClosureNotificationPort notify) {
        ObjectProvider<ClosureRenewalPort> r=mock(ObjectProvider.class);ObjectProvider<ClosureNotificationPort> n=mock(ObjectProvider.class);
        when(r.getIfAvailable()).thenReturn(renew);when(n.getIfAvailable()).thenReturn(notify);
        return new WorkshopClosureEffectWorker(jdbc,manager,clock,r,n);
    }
    private static ClosureRenewalPort.Observation observation(ClosureRenewalPort.Target target,ClosureRenewalPort.State state){return new ClosureRenewalPort.Observation(target.externalReference(),target.externalId(),state);}
    private static String hex(){return UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");}
    private static final class MutableClock extends Clock {
        final AtomicReference<Instant> now;MutableClock(Instant now){this.now=new AtomicReference<>(now);}void advance(Duration d){now.updateAndGet(i->i.plus(d));}
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now.get();}
    }
}
