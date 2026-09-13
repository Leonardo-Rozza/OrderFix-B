package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.http;

import org.springframework.http.HttpStatus;

final class WorkshopClosureHttpException extends RuntimeException {
    final HttpStatus status;
    final String code;
    final long retryAfter;
    WorkshopClosureHttpException(HttpStatus status,String code,String message) { this(status,code,message,0); }
    private WorkshopClosureHttpException(HttpStatus status,String code,String message,long retryAfter) {
        super(message); this.status=status; this.code=code; this.retryAfter=retryAfter;
    }
    static WorkshopClosureHttpException invalid() { return new WorkshopClosureHttpException(HttpStatus.BAD_REQUEST,"SOLICITUD_INVALIDA","La solicitud de cierre no es válida."); }
    static WorkshopClosureHttpException unauthorized() { return new WorkshopClosureHttpException(HttpStatus.UNAUTHORIZED,"SESION_INVALIDA","Iniciá sesión nuevamente para continuar."); }
    static WorkshopClosureHttpException forbidden() { return new WorkshopClosureHttpException(HttpStatus.FORBIDDEN,"ACCESO_DENEGADO","Esta función corresponde al titular del taller."); }
    static WorkshopClosureHttpException unavailable() { return new WorkshopClosureHttpException(HttpStatus.SERVICE_UNAVAILABLE,"CIERRE_NO_DISPONIBLE","No se pudo comprobar la operación. Volvé a consultar el estado del taller."); }
    static WorkshopClosureHttpException limited(long seconds) { return new WorkshopClosureHttpException(HttpStatus.TOO_MANY_REQUESTS,"LIMITE_SOLICITUDES","Esperá antes de volver a intentarlo.",seconds); }
    static WorkshopClosureHttpException proof() { return new WorkshopClosureHttpException(HttpStatus.BAD_REQUEST,"REAUTENTICACION_INVALIDA","La confirmación no es válida o ya venció. Volvé a confirmar tu contraseña."); }
    static WorkshopClosureHttpException missing() { return new WorkshopClosureHttpException(HttpStatus.NOT_FOUND,"SOLICITUD_INVALIDA","La ruta solicitada no está disponible."); }
}
