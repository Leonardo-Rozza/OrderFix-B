package com.leonardorozza.mvgrreparacionesbackend.cuenta.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.io.ByteArrayOutputStream;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.config.import=", "spring.config.additional-location=",
        "spring.config.location=optional:classpath:/application.properties",
        "mail.enabled=false", "mercadopago.enabled=false", "mercadopago.checkout-enabled=false",
        "photos.private.enabled=false", "ordenfix.legal.registration-consent.enabled=false",
        "ordenfix.legal.registration-enforcement.enabled=false", "ordenfix.legal.account-read.enabled=false",
        "ordenfix.legal.account-acceptance.enabled=false", "ordenfix.legal.public-documents.enabled=false",
        "ordenfix.legal.public-requirements.enabled=false", "ordenfix.legal.aggregate-context.enabled=false",
        "ordenfix.legal.editorial-context.enabled=false", "ordenfix.legal.import-context.enabled=false",
        "ordenfix.legal.dry-run-context.enabled=false", "ordenfix.legal.public-document-read-context.enabled=false",
        "ordenfix.legal.public-requirements-context.enabled=false"
})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkshopExportJpaWiringIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_export_jpa").withUsername("ordenfix").withPassword("ordenfix");
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired WorkshopExportSnapshotService service;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }
    @Test void mainJpaManagerBindsJdbcToANewReadOnlySnapshotOutsideAnUncommittedCaller() throws Exception {
        assertThat(transactionManager).isInstanceOf(JpaTransactionManager.class);
        long workshop = jdbc.queryForObject("INSERT INTO talleres(nombre) VALUES('Export wiring') RETURNING id", Long.class);
        long actor = jdbc.queryForObject("""
                INSERT INTO users(username,email,password,role,taller_id,active,email_verificado,token_version)
                VALUES('Export wiring','export-wiring@synthetic.invalid','not-login-capable','ADMIN',?,true,true,3) RETURNING id
                """, Long.class, workshop);
        long customer = jdbc.queryForObject("INSERT INTO clientes(nombre,apellido,telefono,taller_id) VALUES('Committed','Client','0000000000',?) RETURNING id",Long.class,workshop);
        var outer = new TransactionTemplate(transactionManager);
        ExportSnapshot snapshot = outer.execute(status -> {
            jdbc.update("UPDATE clientes SET nombre='Uncommitted' WHERE id=?", customer);
            var result = service.capture(actor, workshop, 3);
            status.setRollbackOnly();
            return result;
        });
        var output = new ByteArrayOutputStream();
        snapshot.files().stream().filter(file -> file.path().equals("datos/clientes.json")).findFirst().orElseThrow().writeTo(output);
        assertThat(new ObjectMapper().readTree(output.toByteArray()).get(0).path("nombre").asText()).isEqualTo("Committed");
        assertThat(jdbc.queryForObject("SELECT nombre FROM clientes WHERE id=?",String.class,customer)).isEqualTo("Committed");
    }
}
