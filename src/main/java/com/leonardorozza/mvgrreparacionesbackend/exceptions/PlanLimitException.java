package com.leonardorozza.mvgrreparacionesbackend.exceptions;

/**
 * Se lanza cuando una acción supera los límites del plan actual del taller
 * (ej: tope de reparaciones del plan FREE) o la suscripción no está vigente.
 */
public class PlanLimitException extends RuntimeException {
    public PlanLimitException(String message) {
        super(message);
    }
}
