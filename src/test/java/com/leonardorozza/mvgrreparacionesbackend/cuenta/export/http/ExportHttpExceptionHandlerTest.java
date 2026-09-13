package com.leonardorozza.mvgrreparacionesbackend.cuenta.export.http;

import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;

class ExportHttpExceptionHandlerTest {
    @Test void closedAccountHasSpecificLockedCodeAndNoStoreHeaders() {
        var request=new MockHttpServletRequest("POST","/api/export/excel");
        var response=new MockHttpServletResponse();
        var result=new ExportHttpExceptionHandler().handle(new WorkshopClosureBlockedException(),request,response);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        assertThat(result.getBody().getCode()).isEqualTo("CUENTA_EN_CIERRE");
        assertThat(result.getHeaders().getCacheControl()).isEqualTo("private, no-store");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(result.getBody().getDetails()).isNull();
    }
    @Test void busyAdmissionIsUnavailableWithoutDriverDiagnostics() {
        var result=new ExportHttpExceptionHandler().handle(new WorkshopClosureBusyException(),
                new MockHttpServletRequest("POST","/api/exportaciones"),new MockHttpServletResponse());
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(result.getBody().getCode()).isEqualTo("EXPORTACION_NO_DISPONIBLE");
        assertThat(result.getBody().getDetails()).isNull();
    }
}
