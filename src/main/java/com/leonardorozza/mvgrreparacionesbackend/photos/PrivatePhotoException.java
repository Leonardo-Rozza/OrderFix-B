package com.leonardorozza.mvgrreparacionesbackend.photos;

import org.springframework.http.HttpStatus;
import java.util.Map;

public final class PrivatePhotoException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Map<String,Object> details;
    private PrivatePhotoException(HttpStatus status, String code, Map<String,Object> details) {
        super("No se pudo completar la operación de fotos.");
        this.status=status; this.code=code; this.details=Map.copyOf(details);
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
    public Map<String,Object> details() { return details; }
    public static PrivatePhotoException invalid() { return of(HttpStatus.BAD_REQUEST,"FOTO_INVALIDA"); }
    public static PrivatePhotoException unavailable() { return of(HttpStatus.SERVICE_UNAVAILABLE,"FOTOS_PRIVADAS_NO_DISPONIBLES"); }
    public static PrivatePhotoException missing() { return of(HttpStatus.NOT_FOUND,"FOTO_NO_ENCONTRADA"); }
    public static PrivatePhotoException forbidden() { return of(HttpStatus.FORBIDDEN,"FOTO_ACTOR_NO_PERMITIDO"); }
    public static PrivatePhotoException expired() { return of(HttpStatus.CONFLICT,"INTENCION_FOTO_EXPIRADA"); }
    public static PrivatePhotoException required(PrivatePhotoDtos.Requirements current) {
        return new PrivatePhotoException(HttpStatus.PRECONDITION_REQUIRED,"ATESTACION_REQUERIDA",Map.of("requisitosActuales",current));
    }
    public static PrivatePhotoException stale(PrivatePhotoDtos.Requirements current) {
        return new PrivatePhotoException(HttpStatus.CONFLICT,"REQUISITOS_LEGALES_DESACTUALIZADOS",Map.of("requisitosActuales",current));
    }
    public static PrivatePhotoException of(HttpStatus status, String code) { return new PrivatePhotoException(status,code,Map.of()); }
}
