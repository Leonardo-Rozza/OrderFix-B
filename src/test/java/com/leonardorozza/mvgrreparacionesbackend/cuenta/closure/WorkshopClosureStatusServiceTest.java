package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import com.leonardorozza.mvgrreparacionesbackend.exceptions.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.sql.ResultSet;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkshopClosureStatusServiceTest {
    private static final Instant CONFIRMED=Instant.parse("2026-09-12T12:00:00Z"), NOW=CONFIRMED.plusSeconds(30);
    private static final UUID REFERENCE=UUID.fromString("3e3dcb6d-4f7a-4596-b7de-478d0f942745");
    @Test void openStatusUsesNewBoundedTransactionAndGateBeforeFreshAuthorityWithoutDml() throws Exception {
        var f=new Fixture("ABIERTO",0,null);var result=f.service.read("access");
        assertThat(result.state()).isEqualTo("ABIERTO");assertThat(result.canRequest()).isTrue();assertThat(result.canRestore()).isFalse();
        assertThat(result.reference()).isNull();assertThat(result.lastOperation()).isNull();assertThat(result.observedAt()).isEqualTo(NOW);
        assertThat(result.toString()).doesNotContain("Taller privado",REFERENCE.toString());
        var order=inOrder(f.authorization,f.manager,f.gate,f.jdbc);
        order.verify(f.authorization).routeWorkshop("access");order.verify(f.manager).getTransaction(any());
        order.verify(f.gate).requireAccountAccess(7);order.verify(f.authorization).authorize("access");
        verify(f.authorization,times(2)).authorize("access");verify(f.authorization).requireLive(f.authority);verify(f.manager).commit(f.transaction);
        assertThat(mockingDetails(f.jdbc).getInvocations()).noneMatch(call->Set.of("update","batchUpdate").contains(call.getMethod().getName()));
    }
    @Test void restrictedStatusRequiresMatchingImmutableHistoryAndReturnsOnlyCurrentReceipt() throws Exception {
        var f=new Fixture("RESTRINGIDO",1,REFERENCE);f.closeReceipt();var result=f.service.read("access");
        assertThat(result.canRequest()).isFalse();assertThat(result.canRestore()).isTrue();assertThat(result.reference()).isEqualTo(REFERENCE);
        assertThat(result.lastOperation().operationId()).isEqualTo(REFERENCE);assertThat(result.lastOperation().reused()).isFalse();
        assertThat(f.arguments).containsExactly(List.of(11L,7L),List.of(7L,11L));
    }
    @Test void restorationReceiptCanCompleteAfterStoreRestorationClockWithoutChangingItsOriginalSchedule() throws Exception {
        var f=new Fixture("ABIERTO",2,null);f.operation=mock(ResultSet.class);f.operationBase(WorkshopClosurePurpose.RESTAURAR,2,"ABIERTO");
        when(f.operation.getLong(10)).thenReturn(1L);when(f.operation.getString(11)).thenReturn("RESTAURADO");
        f.time(f.operation,12,CONFIRMED.plusSeconds(10));var result=f.service.read("access");
        assertThat(result.canRequest()).isTrue();assertThat(result.reference()).isNull();assertThat(result.lastOperation().purpose()).isEqualTo(WorkshopClosurePurpose.RESTAURAR);
        assertThat(result.lastOperation().confirmedAt()).isEqualTo(CONFIRMED);
    }
    @Test void missingOrForeignHistoryFailsClosedAndRollsBack() throws Exception {
        var f=new Fixture("RESTRINGIDO",1,REFERENCE);when(f.anchor.getBoolean(8)).thenReturn(false);
        assertThatThrownBy(()->f.service.read("access")).isInstanceOf(WorkshopClosureStatusService.Unavailable.class).hasNoCause();verify(f.manager).rollback(f.transaction);
    }
    @Test void operationGenerationCannotBeTakenFromAnotherClosure() throws Exception {
        var f=new Fixture("RESTRINGIDO",1,REFERENCE);f.closeReceipt();when(f.operation.getLong(4)).thenReturn(3L);
        assertThatThrownBy(()->f.service.read("access")).isInstanceOf(WorkshopClosureStatusService.Unavailable.class);verify(f.manager).rollback(f.transaction);
    }
    @Test void graceEndsAtTheExactBoundary() throws Exception {
        var f=new Fixture("RESTRINGIDO",1,REFERENCE,CONFIRMED.plus(Duration.ofDays(7)));
        assertThatThrownBy(()->f.service.read("access")).isInstanceOf(WorkshopClosureBlockedException.class);verify(f.manager).rollback(f.transaction);
    }
    @Test void aChangedFinalAuthorityDiscardsTheStatus() throws Exception {
        var f=new Fixture("ABIERTO",0,null);var changed=new WorkshopClosureReauthenticationService.Authority(11,7,4,0,null,NOW.plusSeconds(300),"session","ABIERTO");
        when(f.authorization.authorize("access")).thenReturn(f.authority,changed);
        assertThatThrownBy(()->f.service.read("access")).isInstanceOf(WorkshopClosureStatusService.Unavailable.class);verify(f.manager).rollback(f.transaction);
    }
    @Test void invalidSessionNeverTouchesSqlAndUnexpectedInfrastructureIsSanitized() throws Exception {
        var f=new Fixture("ABIERTO",0,null);when(f.authorization.routeWorkshop("access")).thenThrow(new UnauthorizedException("Sesión inválida"));
        assertThatThrownBy(()->f.service.read("access")).isInstanceOf(UnauthorizedException.class);verifyNoInteractions(f.jdbc,f.gate,f.manager);
        var broken=new Fixture("ABIERTO",0,null);doThrow(new IllegalStateException("jdbc-password-token-secret")).when(broken.jdbc).execute(anyString());
        assertThatThrownBy(()->broken.service.read("access")).isInstanceOf(WorkshopClosureStatusService.Unavailable.class).hasNoCause().hasMessageNotContaining("secret");
    }
    private static final class Fixture {
        final JdbcTemplate jdbc=mock(JdbcTemplate.class);final WorkshopClosureGate gate=mock(WorkshopClosureGate.class);
        final WorkshopClosureReauthenticationService authorization=mock(WorkshopClosureReauthenticationService.class);
        final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);final SimpleTransactionStatus transaction=new SimpleTransactionStatus();
        final ResultSet anchor=mock(ResultSet.class);ResultSet operation;final List<List<Object>> arguments=new ArrayList<>();
        final WorkshopClosureReauthenticationService.Authority authority;final WorkshopClosureStatusService service;
        Fixture(String state,long generation,UUID reference) throws Exception {this(state,generation,reference,NOW);}
        Fixture(String state,long generation,UUID reference,Instant now) throws Exception {
            authority=new WorkshopClosureReauthenticationService.Authority(11,7,3,generation,reference,now.plusSeconds(300),"session",state);
            when(authorization.routeWorkshop("access")).thenReturn(7L);when(authorization.authorize("access")).thenReturn(authority);
            when(manager.getTransaction(any())).thenAnswer(call->{var definition=call.getArgument(0,TransactionDefinition.class);
                assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                assertThat(definition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
                assertThat(definition.isReadOnly()).isFalse();assertThat(definition.getTimeout()).isEqualTo(10);return transaction;});
            when(anchor.getString(1)).thenReturn("Taller privado");when(anchor.getString(2)).thenReturn(state);when(anchor.getLong(3)).thenReturn(generation);
            when(anchor.getObject(4,UUID.class)).thenReturn(reference);when(anchor.getBoolean(8)).thenReturn(true);
            if(reference!=null){time(anchor,5,CONFIRMED);time(anchor,6,CONFIRMED.plus(Duration.ofDays(7)));time(anchor,7,CONFIRMED.plus(Duration.ofDays(37)));}
            doAnswer(call->{String sql=call.getArgument(0);RowMapper<?> mapper=call.getArgument(1);arguments.add(List.of(call.getArguments()[2],call.getArguments()[3]));
                var row=sql.contains("SELECT t.nombre")?anchor:operation;return row==null?List.of():List.of(mapper.mapRow(row,0));})
                .when(jdbc).query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),any(Object[].class));
            service=new WorkshopClosureStatusService(jdbc,gate,authorization,manager,Clock.fixed(now,ZoneOffset.UTC));
        }
        void closeReceipt()throws Exception{operation=mock(ResultSet.class);operationBase(WorkshopClosurePurpose.CERRAR,1,"RESTRINGIDO");when(operation.getLong(10)).thenReturn(1L);when(operation.getString(11)).thenReturn("RESTRINGIDO");}
        void operationBase(WorkshopClosurePurpose purpose,long generation,String state)throws Exception{
            when(operation.getObject(1,UUID.class)).thenReturn(purpose==WorkshopClosurePurpose.CERRAR?REFERENCE:UUID.fromString("9e3dcb6d-4f7a-4596-b7de-478d0f942745"));
            when(operation.getObject(2,UUID.class)).thenReturn(REFERENCE);when(operation.getString(3)).thenReturn(purpose.name());when(operation.getLong(4)).thenReturn(generation);
            when(operation.getString(5)).thenReturn(state);time(operation,6,CONFIRMED);time(operation,7,CONFIRMED.plus(Duration.ofDays(7)));time(operation,8,CONFIRMED.plus(Duration.ofDays(37)));
            time(operation,9,NOW);when(operation.getBoolean(13)).thenReturn(true);
        }
        void time(ResultSet row,int column,Instant value)throws Exception{when(row.getObject(column,OffsetDateTime.class)).thenReturn(value.atOffset(ZoneOffset.UTC));}
    }
}
