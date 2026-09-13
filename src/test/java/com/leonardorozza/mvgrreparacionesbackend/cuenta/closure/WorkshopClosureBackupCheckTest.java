package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;
import static com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosureBackupCheck.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkshopClosureBackupCheckTest {
    private static final Instant NOW=Instant.parse("2026-09-12T12:00:00Z");
    private static final UUID REF=UUID.fromString("00000000-0000-0000-0000-000000000007");
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    private final WorkshopClosureBackupCheck check=new WorkshopClosureBackupCheck(jdbc,manager);
    private static Evidence evidence(){return new Evidence(7,11,REF,1,State.RESTRICTED,3);}
    private static Snapshot snapshot(long generation,String state,UUID reference,Long owner,Long epoch,boolean coherent){
        return new Snapshot(7,NOW,true,generation,state,reference,owner,7L,"ADMIN",epoch,coherent);
    }
    @Test void emptyOrNullEvidenceDoesNotBecomeACompatibleReport(){
        rejected(null,Rejected.Code.INVALID_INPUT);rejected(List.of(),Rejected.Code.INVALID_INPUT);verifyNoInteractions(jdbc,manager);
    }
    @Test void nullItemIsRejectedBeforeDatabaseAccess(){
        rejected(Arrays.asList(evidence(),null),Rejected.Code.INVALID_INPUT);verifyNoInteractions(jdbc,manager);
    }
    @Test void oneThousandOneEntriesAreRejectedBeforeDatabaseAccess(){
        rejected(IntStream.rangeClosed(1,1001).mapToObj(i->new Evidence(i,1,new UUID(0,i),1,State.RESTRICTED,0)).toList(),Rejected.Code.CAPACITY_EXCEEDED);
        verifyNoInteractions(jdbc,manager);
    }
    @Test void repeatedWorkshopAndRepeatedReferenceAreRejected(){
        rejected(List.of(evidence(),new Evidence(7,12,UUID.randomUUID(),1,State.RESTRICTED,0)),Rejected.Code.INVALID_INPUT);
        rejected(List.of(evidence(),new Evidence(8,12,REF,1,State.RESTRICTED,0)),Rejected.Code.INVALID_INPUT);verifyNoInteractions(jdbc,manager);
    }
    @Test void malformedBoundsAndMissingStateAreRejected(){
        for(var invalid:List.of(new Evidence(0,11,REF,1,State.RESTRICTED,0),new Evidence(7,0,REF,1,State.RESTRICTED,0),
                new Evidence(7,11,null,1,State.RESTRICTED,0),new Evidence(7,11,REF,0,State.RESTRICTED,0),
                new Evidence(7,11,REF,1,null,0),new Evidence(7,11,REF,1,State.RESTRICTED,-1)))
            rejected(List.of(invalid),Rejected.Code.INVALID_INPUT);
        verifyNoInteractions(jdbc,manager);
    }
    @Test void exactMetadataMatchesButDoesNotAccreditExternalEvidence(){
        assertThat(compare(evidence(),snapshot(1,"RESTRINGIDO",REF,11L,3L,true))).isEmpty();
    }
    @Test void missingDatabaseAndOlderGenerationAreDistinct(){
        assertThat(compare(evidence(),new Snapshot(7,NOW,false,0,null,null,null,null,null,null,false))).containsExactly(Issue.DATABASE_MISSING);
        assertThat(compare(evidence(),new Snapshot(7,NOW,true,0,"ABIERTO",null,null,null,null,null,true))).containsExactly(Issue.DATABASE_BEHIND);
    }
    @Test void newerDatabaseIsNotMistakenForACompleteJournal(){
        assertThat(compare(evidence(),snapshot(2,"ABIERTO",REF,11L,3L,true))).containsExactly(Issue.DATABASE_AHEAD);
    }
    @Test void equalGenerationRequiresTheExactReferenceAndState(){
        assertThat(compare(evidence(),snapshot(1,"RESTRINGIDO",UUID.randomUUID(),11L,3L,true))).containsExactly(Issue.REFERENCE_OR_STATE_DIVERGENT);
        assertThat(compare(evidence(),snapshot(1,"ABIERTO",REF,11L,3L,true))).containsExactly(Issue.REFERENCE_OR_STATE_DIVERGENT);
    }
    @Test void inconsistentLocalHistoryIsReportedEvenWhenSuppliedValuesMatch(){
        assertThat(compare(evidence(),snapshot(1,"RESTRINGIDO",REF,11L,3L,false))).containsExactly(Issue.LOCAL_INCONSISTENCY);
    }
    @Test void ownerAndEpochAreCheckedIndependentlyOfClosureGeneration(){
        assertThat(compare(evidence(),snapshot(1,"RESTRINGIDO",REF,12L,3L,true))).containsExactly(Issue.OWNER_DIVERGENT);
        assertThat(compare(evidence(),snapshot(1,"RESTRINGIDO",REF,11L,2L,true))).containsExactly(Issue.OWNER_EPOCH_BEHIND);
        assertThat(compare(evidence(),snapshot(1,"RESTRINGIDO",REF,11L,4L,true))).containsExactly(Issue.OWNER_EPOCH_AHEAD);
    }
    @Test void deletedMetadataCannotClaimTheMissingDeletionImplementation(){
        var deleted=new Evidence(7,11,REF,1,State.DELETED,3);
        assertThat(compare(deleted,snapshot(1,"ELIMINADO",REF,11L,3L,true))).containsExactly(Issue.DELETION_NOT_IMPLEMENTED);
    }
    @Test @SuppressWarnings("unchecked") void transactionIsFreshReadOnlyAndCompatibleStillWarnsAgainstReopening(){
        when(manager.getTransaction(any())).thenAnswer(call->{
            TransactionDefinition definition=call.getArgument(0);assertThat(definition.isReadOnly()).isTrue();
            assertThat(definition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            assertThat(definition.getTimeout()).isEqualTo(10);return new SimpleTransactionStatus();
        });
        when(jdbc.query(anyString(),any(PreparedStatementSetter.class),any(RowMapper.class))).thenReturn(List.of(snapshot(1,"RESTRINGIDO",REF,11L,3L,true)));
        var report=check.compare(List.of(evidence()));assertThat(report.status()).isEqualTo(Status.COMPARACION_COMPATIBLE);
        assertThat(report.notice()).isEqualTo(Notice.NO_AUTORIZA_REAPERTURA);assertThat(report.observedAt()).isEqualTo(NOW);
        assertThat(report.findings()).isEmpty();assertThat(report.checked()).isEqualTo(1);
        verify(jdbc).execute("SET LOCAL statement_timeout='5s'");verify(jdbc).execute("SET LOCAL lock_timeout='2s'");
        verify(jdbc).query(startsWith("WITH requested"),any(PreparedStatementSetter.class),any(RowMapper.class));verifyNoMoreInteractions(jdbc);
    }
    @Test void databaseFailuresHaveNoSqlCauseOrDiagnostics(){
        when(manager.getTransaction(any())).thenThrow(new IllegalStateException("private database details"));
        var failure=catchThrowable(()->check.compare(List.of(evidence())));
        assertThat(failure).isInstanceOf(Rejected.class).hasMessage("No se pudo comparar la evidencia de recuperación.").hasNoCause();
        assertThat(((Rejected)failure).code()).isEqualTo(Rejected.Code.UNAVAILABLE);
    }
    @Test void recordsDoNotPrintIdentifiers(){
        assertThat(evidence().toString()).doesNotContain(REF.toString(),"titularId");
        assertThat(snapshot(1,"RESTRINGIDO",REF,11L,3L,true).toString()).doesNotContain(REF.toString(),"ownerId");
        var report=new Report(Status.DIVERGENCIAS,NOW,1,List.of(new Finding(7,Issue.DATABASE_BEHIND)),Notice.NO_AUTORIZA_REAPERTURA);
        assertThat(report.toString()).doesNotContain(REF.toString(),"tallerId");
        assertThatThrownBy(()->report.findings().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    private void rejected(List<Evidence> value,Rejected.Code code){var thrown=catchThrowable(()->check.compare(value));assertThat(thrown).isInstanceOf(Rejected.class);assertThat(((Rejected)thrown).code()).isEqualTo(code);}
}
