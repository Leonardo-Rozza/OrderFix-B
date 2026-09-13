package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.*;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.pago.mercadopago.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** PostgreSQL V33 and real provider validation; observations are synthetic and no provider is called. */
@SpringBootTest(properties={
        "spring.config.import=", "spring.config.additional-location=",
        "spring.config.location=optional:classpath:/application.properties",
        "mail.enabled=false", "mercadopago.enabled=false", "mercadopago.checkout-enabled=false",
        "mercadopago.collector-id=1", "mercadopago.application-id=2", "mercadopago.amount=24900.00", "mercadopago.currency=ARS",
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
class MercadoPagoClosureIT {
    @Container static final PostgreSQLContainer PG=new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_mp_closure").withUsername("ordenfix").withPassword("fixture-owner");
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired MercadoPagoCheckoutStateService checkout;
    @Autowired MercadoPagoSubscriptionStateService observations;
    @Autowired WorkshopClosureGate gate;
    @Autowired WorkshopClosureStore store;
    @Autowired WorkshopClosureEffects effects;
    @Autowired Clock clock;
    long workshop;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",PG::getJdbcUrl);registry.add("spring.datasource.username",PG::getUsername);
        registry.add("spring.datasource.password",PG::getPassword);registry.add("spring.datasource.driver-class-name",PG::getDriverClassName);
        registry.add("spring.flyway.enabled",()->"true");registry.add("spring.jpa.hibernate.ddl-auto",()->"validate");
        registry.add("spring.jpa.properties.hibernate.dialect",()->"org.hibernate.dialect.PostgreSQLDialect");
    }
    @BeforeEach void seed() {
        workshop=jdbc.queryForObject("INSERT INTO talleres(nombre,email_contacto) VALUES('MP closure','synthetic@closure.invalid') RETURNING id",Long.class);
        jdbc.update("INSERT INTO suscripciones(taller_id,plan,estado) VALUES(?,'FREE','TRIAL')",workshop);
    }
    @Test void restrictedWorkshopCannotPrepareANewProviderAttempt() {
        restrict();
        assertThatThrownBy(()->checkout.prepare(workshop)).isInstanceOf(WorkshopClosureBlockedException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscription_provider_links l JOIN suscripciones s ON s.id=l.suscripcion_id WHERE s.taller_id=?",Long.class,workshop)).isZero();
        assertThat(jdbc.queryForObject("SELECT mp_external_reference FROM suscripciones WHERE taller_id=?",String.class,workshop)).isNull();
    }
    @Test void lateProviderAcknowledgementInvoicesAndCancellationRemainDurableWithoutReopeningWorkshop() {
        var preparation=checkout.prepare(workshop);
        restrict();
        String providerId="pre-"+UUID.randomUUID();
        var response=preapproval(providerId,preparation.externalReference(),"pending");
        checkout.complete(preparation.linkId(),response);
        assertThatThrownBy(()->checkout.requireCheckoutDelivery(workshop,preparation.linkId())).isInstanceOf(WorkshopClosureBlockedException.class);
        assertThat(jdbc.queryForObject("SELECT external_subscription_id FROM subscription_provider_links WHERE id=?",String.class,preparation.linkId())).isEqualTo(providerId);
        assertThat(jdbc.queryForObject("SELECT mp_preapproval_id FROM suscripciones WHERE taller_id=?",String.class,workshop)).isEqualTo(providerId);

        observations.applyPreapproval(providerId,preapproval(providerId,preparation.externalReference(),"authorized"));
        String invoice="invoice-"+UUID.randomUUID();
        OffsetDateTime observed=OffsetDateTime.now(ZoneOffset.UTC);
        observations.applyAuthorizedPayment(invoice,new MercadoPagoAuthorizedPaymentResponse(invoice,providerId,
                preparation.externalReference(),"ARS",new BigDecimal("24900.00"),"processed","",0,observed,observed,observed,
                new MercadoPagoAuthorizedPaymentResponse.Payment("payment-"+UUID.randomUUID(),"approved","accredited")));
        assertThat(jdbc.queryForMap("SELECT plan,estado,mp_status,mp_last_authorized_payment_id FROM suscripciones WHERE taller_id=?",workshop))
                .containsEntry("plan","FREE").containsEntry("estado","TRIAL").containsEntry("mp_status","authorized")
                .containsEntry("mp_last_authorized_payment_id",invoice);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscription_payments WHERE external_authorized_payment_id=?",Long.class,invoice)).isEqualTo(1);

        observations.markReconciliationStarted(providerId);
        observations.markReconciliationSucceeded(providerId);
        observations.applyPreapproval(providerId,preapproval(providerId,preparation.externalReference(),"canceled"));
        assertThat(jdbc.queryForMap("SELECT status,last_reconciliation_status FROM subscription_provider_links WHERE id=?",preparation.linkId()))
                .containsEntry("status","canceled").containsEntry("last_reconciliation_status","SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT mp_status FROM suscripciones WHERE taller_id=?",String.class,workshop)).isEqualTo("canceled");
        observations.applyLocalCancellation(workshop);
        assertThat(jdbc.queryForObject("SELECT cierre_estado FROM talleres WHERE id=?",String.class,workshop)).isEqualTo("RESTRINGIDO");
        assertThatThrownBy(()->checkout.prepare(workshop)).isInstanceOf(WorkshopClosureBlockedException.class);
    }
    @Test void verifiedRemoteCancellationAfterRestorePreservesExistingCancellationSemantics() {
        var preparation=checkout.prepare(workshop);String providerId="pre-"+UUID.randomUUID();
        checkout.complete(preparation.linkId(),preapproval(providerId,preparation.externalReference(),"pending"));
        observations.applyPreapproval(providerId,preapproval(providerId,preparation.externalReference(),"authorized"));
        String mark=UUID.randomUUID().toString();
        long owner=jdbc.queryForObject("INSERT INTO users(username,email,password,role,taller_id,active,email_verificado,token_version) VALUES(?,?,?,'ADMIN',?,true,true,0) RETURNING id",
                Long.class,mark,mark+"@synthetic.invalid","fixture-password-not-for-login",workshop);
        UUID reference=UUID.randomUUID();closureTransition(owner,reference,false);closureTransition(owner,reference,true);
        // Restoration itself preserves the existing plan; the later verified provider event applies its ordinary meaning.
        assertThat(jdbc.queryForObject("SELECT plan FROM suscripciones WHERE taller_id=?",String.class,workshop)).isEqualTo("PRO");
        observations.applyPreapproval(providerId,preapproval(providerId,preparation.externalReference(),"canceled"));
        assertThat(jdbc.queryForMap("SELECT plan,estado,mp_status FROM suscripciones WHERE taller_id=?",workshop))
                .containsEntry("plan","FREE").containsEntry("estado","ACTIVA").containsEntry("mp_status","canceled");
        assertThat(jdbc.queryForObject("SELECT cierre_estado FROM talleres WHERE id=?",String.class,workshop)).isEqualTo("ABIERTO");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cuenta_cierre_efectos WHERE link_id=?",Long.class,preparation.linkId())).isEqualTo(1);
    }
    /** SQL proof fixture exercises V34/store guards; JWT/password verification is covered by command IT. */
    private void closureTransition(long owner,UUID reference,boolean restore) {
        new TransactionTemplate(manager).executeWithoutResult(status->{
            gate.lockExclusive(workshop);Instant now=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            String hash=UUID.randomUUID().toString().replace("-","").repeat(2),purpose=restore?"RESTAURAR":"CERRAR";
            UUID operation=restore?UUID.randomUUID():reference;
            long epoch=jdbc.queryForObject("SELECT token_version FROM users WHERE id=?",Long.class,owner);
            long generation=jdbc.queryForObject("SELECT cierre_version FROM talleres WHERE id=?",Long.class,workshop);
            jdbc.update("""
                    INSERT INTO cuenta_cierre_confirmaciones(token_hash,user_id,taller_id,token_version,session_hash,proposito,
                       operacion_id,cierre_referencia,cierre_version,creada_en,expira_en)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?)
                    """,hash,owner,workshop,epoch,hash,purpose,operation,reference,generation,
                    now.atOffset(ZoneOffset.UTC),now.plusSeconds(120).atOffset(ZoneOffset.UTC));
            jdbc.update("UPDATE cuenta_cierre_confirmaciones SET usada_en=? WHERE token_hash=?",now.atOffset(ZoneOffset.UTC),hash);
            var receipt=restore?store.restore(workshop,owner,reference):store.restrict(workshop,owner,reference);
            Instant completed=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            jdbc.update("""
                    INSERT INTO cuenta_cierre_operaciones(operacion_id,taller_id,user_id,proposito,cierre_referencia,cierre_version,
                       request_digest,proof_hash,estado_resultante,politica,confirmado_en,reversible_hasta,eliminacion_prevista_en,registrada_en)
                    VALUES(?,?,?,?,?,?,?,?,?,'ordenfix-cierre/1',?,?,?,?)
                    """,operation,workshop,owner,purpose,reference,generation+1,hash,hash,restore?"ABIERTO":"RESTRINGIDO",
                    receipt.confirmedAt().atOffset(ZoneOffset.UTC),receipt.reversibleUntil().atOffset(ZoneOffset.UTC),
                    receipt.deletionExpectedBy().atOffset(ZoneOffset.UTC),completed.atOffset(ZoneOffset.UTC));
            if(restore)effects.enqueueRestore(operation,reference,workshop,owner,completed);
            else effects.enqueueClose(operation,reference,workshop,owner,completed);
        });
    }

    private static MercadoPagoPreapprovalResponse preapproval(String id,String reference,String status) {
        return new MercadoPagoPreapprovalResponse(id,status,"https://www.mercadopago.com.ar/checkout",reference,"payer-synthetic",
                1L,2L,OffsetDateTime.now(ZoneOffset.UTC).plusDays(30),
                new MercadoPagoPreapprovalResponse.AutoRecurring(new BigDecimal("24900.00"),"ARS"));
    }
    /** Consumer-state fixture: transition authorization is covered by the dedicated closure store IT. */
    private void restrict() {
        new TransactionTemplate(manager).executeWithoutResult(status->{
            jdbc.execute("SET LOCAL session_replication_role=replica");
            jdbc.update("""
                    UPDATE talleres SET cierre_estado='RESTRINGIDO',cierre_version=1,cierre_referencia=?,
                        cierre_confirmado_en=statement_timestamp(),cierre_reversible_hasta=statement_timestamp()+INTERVAL '7 days',
                        cierre_eliminacion_prevista_en=statement_timestamp()+INTERVAL '37 days' WHERE id=?
                    """,UUID.randomUUID(),workshop);
        });
    }
}
