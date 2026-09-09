package com.leonardorozza.mvgrreparacionesbackend.exceptions;

import org.junit.jupiter.api.Test;
import org.hibernate.exception.ConstraintViolationException;
import java.sql.SQLException;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.assertThat;

class PrivatePhotoDeletionErrorTest {
    private final GlobalExceptionHandler handler=new GlobalExceptionHandler();
    private final MockHttpServletRequest request=new MockHttpServletRequest("DELETE","/api/reparaciones/17");
    @Test void exactReferentialConstraintExplainsHowToPreserveTheFilesWithoutLeakingSql() {
        var result=handler.handleDataIntegrity(new DataIntegrityViolationException("internal-sql",
                new IllegalStateException(sql("23514","foto_privada_borrado_pendiente"))),request);
        assertThat(result.getStatusCode().value()).isEqualTo(409);
        assertThat(result.getBody().getCode()).isEqualTo("FOTOS_PRIVADAS_PENDIENTES");
        assertThat(result.getBody().getMessage()).isEqualTo("Eliminá primero las fotos privadas de la reparación e intentá nuevamente.");
        assertThat(result.getBody().toString()).doesNotContain("internal-sql","secret-row","23514");
    }
    @Test void similarMessageOrDifferentSqlStateRetainsTheExistingGenericIntegrityResponse() {
        for(var failure:java.util.List.of(
                new DataIntegrityViolationException("foto_privada_borrado_pendiente"),
                new DataIntegrityViolationException("internal",sql("23505","foto_privada_borrado_pendiente")),
                new DataIntegrityViolationException("internal",sql("23514","other_constraint")))) {
            var result=handler.handleDataIntegrity(failure,request);
            assertThat(result.getStatusCode().value()).isEqualTo(409);
            assertThat(result.getBody().getCode()).isNull();
            assertThat(result.getBody().getMessage()).contains("teléfono o email");
        }
    }
    @Test void realDriverDiagnosticSurvivesHibernateLosingTheConstraintNameFromCustomRaiseMessage() {
        var driver=driver("23514","foto_privada_borrado_pendiente");
        String extracted=new org.hibernate.dialect.PostgreSQLDialect().getViolatedConstraintNameExtractor().extractConstraintName(driver);
        assertThat(extracted).isNull();
        var translated=new ConstraintViolationException("internal-sql",driver,extracted);
        var result=handler.handleDataIntegrity(new DataIntegrityViolationException("internal",translated),request);
        assertThat(result.getStatusCode().value()).isEqualTo(409);
        assertThat(result.getBody().getCode()).isEqualTo("FOTOS_PRIVADAS_PENDIENTES");
        assertThat(result.getBody().toString()).doesNotContain("secret-row","internal-sql","23514");
    }
    @Test void driverFallbackRequiresExactStateAndStructuredConstraintRatherThanMessage() {
        for (SQLException driver:java.util.List.of(
                driver("23505","foto_privada_borrado_pendiente"),driver("23514","other_constraint"),
                new PSQLException(new ServerErrorMessage("SERROR\0C23514\0Mfoto_privada_borrado_pendiente\0\0")),
                new SQLException("foto_privada_borrado_pendiente","23514"))) {
            var result=handler.handleDataIntegrity(new DataIntegrityViolationException("internal",driver),request);
            assertThat(result.getBody().getCode()).isNull();
        }
    }
    private static PSQLException driver(String state,String constraint) {
        return new PSQLException(new ServerErrorMessage("SERROR\0C"+state+"\0Msecret-row\0n"+constraint+"\0\0"));
    }
    private static ConstraintViolationException sql(String state,String constraint) {
        return new ConstraintViolationException("internal-sql",new SQLException("secret-row",state),constraint);
    }
}
