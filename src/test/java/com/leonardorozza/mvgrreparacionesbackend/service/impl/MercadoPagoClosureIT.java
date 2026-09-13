package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosureBlockedException;
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
        assertThatThrownBy(()->checkout.requireCheckoutDelivery(workshop)).isInstanceOf(WorkshopClosureBlockedException.class);
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
