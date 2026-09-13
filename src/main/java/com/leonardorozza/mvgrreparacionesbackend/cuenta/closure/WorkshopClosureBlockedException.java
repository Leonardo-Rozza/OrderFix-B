package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

public final class WorkshopClosureBlockedException extends RuntimeException {
    public WorkshopClosureBlockedException() { super("El taller está en proceso de cierre."); }
}
