package com.leonardorozza.mvgrreparacionesbackend.config.tenant;

/**
 * Guarda el taller (tenant) del request actual en un ThreadLocal.
 * Lo setea el JwtFilter al validar el token y se limpia al final de cada request.
 */
public final class TenantContext {

    private static final ThreadLocal<Long> CURRENT_TALLER = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void setTallerId(Long tallerId) {
        CURRENT_TALLER.set(tallerId);
    }

    public static Long getTallerId() {
        return CURRENT_TALLER.get();
    }

    public static void clear() {
        CURRENT_TALLER.remove();
    }
}
