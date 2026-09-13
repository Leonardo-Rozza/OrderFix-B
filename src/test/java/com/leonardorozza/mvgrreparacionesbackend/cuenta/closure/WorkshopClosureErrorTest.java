package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import com.leonardorozza.mvgrreparacionesbackend.exceptions.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;
import java.sql.SQLException;
import static org.assertj.core.api.Assertions.assertThat;

class WorkshopClosureErrorTest {
 private final GlobalExceptionHandler handler=new GlobalExceptionHandler();
 private final MockHttpServletRequest request=new MockHttpServletRequest("POST","/api/clientes");
 @Test void aLateSqlFenceRejectionIsSafeAndNotLoggedAsAnUnknownFailure(){
  var response=handler.handleGeneralError(new RuntimeException("private diagnostic",new SQLException("private row","P0033")),request);
  assertThat(response.getStatusCode().value()).isEqualTo(423);
  assertThat(response.getHeaders().getCacheControl()).isEqualTo("private, no-store");
  assertThat(response.getBody().toString()).contains("CUENTA_EN_CIERRE").doesNotContain("private diagnostic","private row");
 }
 @Test void aBusyFenceReportsRetryableUnavailability(){
  var response=handler.handleDataIntegrity(new DataIntegrityViolationException("private diagnostic",new SQLException("private row","P0034")),request);
  assertThat(response.getStatusCode().value()).isEqualTo(503);
  assertThat(response.getBody().toString()).contains("CUENTA_NO_DISPONIBLE").doesNotContain("private diagnostic","private row");
 }
 @Test void applicationAdmissionUsesTheSamePublicClosureContract(){
  assertThat(handler.handleClosure(new WorkshopClosureBlockedException(),request).getStatusCode().value()).isEqualTo(423);
  assertThat(handler.handleClosure(new WorkshopClosureBusyException(),request).getStatusCode().value()).isEqualTo(503);
 }
}
