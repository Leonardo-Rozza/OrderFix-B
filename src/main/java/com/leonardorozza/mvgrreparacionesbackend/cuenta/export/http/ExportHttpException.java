package com.leonardorozza.mvgrreparacionesbackend.cuenta.export.http;

import org.springframework.http.HttpStatus;

/** A public, fixed message/code only; never retains request, SQL or cryptographic causes. */
final class ExportHttpException extends RuntimeException {
    final HttpStatus status;
    final String code;
    final long retryAfter;
    ExportHttpException(HttpStatus status, String code, String message) { this(status, code, message, 0); }
    ExportHttpException(HttpStatus status, String code, String message, long retryAfter) {
        super(message); this.status=status; this.code=code; this.retryAfter=retryAfter;
    }
    static ExportHttpException invalid() { return new ExportHttpException(HttpStatus.BAD_REQUEST,"SOLICITUD_INVALIDA","La solicitud de exportación no es válida."); }
    static ExportHttpException tooLarge() { return new ExportHttpException(HttpStatus.PAYLOAD_TOO_LARGE,"SOLICITUD_INVALIDA","El cuerpo de la solicitud supera el tamaño permitido."); }
    static ExportHttpException unavailable() { return new ExportHttpException(HttpStatus.SERVICE_UNAVAILABLE,"EXPORTACION_NO_DISPONIBLE","La exportación no está disponible en este momento."); }
    static ExportHttpException unauthorized() { return new ExportHttpException(HttpStatus.UNAUTHORIZED,"SESION_NO_VALIDA","Se requiere una sesión válida."); }
    static ExportHttpException forbidden() { return new ExportHttpException(HttpStatus.FORBIDDEN,"EXPORTACION_NO_PERMITIDA","La exportación corresponde al titular del taller con email verificado."); }
    static ExportHttpException missing() { return new ExportHttpException(HttpStatus.NOT_FOUND,"EXPORTACION_NO_ENCONTRADA","No se encontró la exportación solicitada."); }
    static ExportHttpException limited(long seconds) { return new ExportHttpException(HttpStatus.TOO_MANY_REQUESTS,"EXPORTACION_LIMITE","Esperá un momento antes de volver a intentar la exportación.",Math.max(1,seconds)); }
}
