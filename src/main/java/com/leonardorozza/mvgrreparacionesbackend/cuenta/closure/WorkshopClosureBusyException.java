package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

public final class WorkshopClosureBusyException extends RuntimeException {
    public WorkshopClosureBusyException() { super("No se pudo comprobar el acceso al taller. Intentá nuevamente."); }
}
