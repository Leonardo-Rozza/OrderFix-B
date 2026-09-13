package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.User;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.UserRole;
import java.time.Instant;

/** Access derives from current durable workshop state; neither JWT claims nor subscription flags decide it. */
public final class WorkshopClosureAccess {
    public enum Mode { OPERATIVE, RESTRICTED, DENIED }
    private WorkshopClosureAccess() { }

    public static Mode mode(User user, Instant now) {
        if (user == null || now == null || user.getId() == null || user.getId() <= 0
                || !Boolean.TRUE.equals(user.getActive()) || user.getTokenVersion() < 0
                || user.getRole() == null || user.getTaller() == null) return Mode.DENIED;
        var workshop = user.getTaller();
        if (workshop.getId() == null || workshop.getId() <= 0 || !Boolean.TRUE.equals(workshop.getActivo())
                || workshop.getCierreVersion() < 0) return Mode.DENIED;
        if ("ABIERTO".equals(workshop.getCierreEstado())) return Mode.OPERATIVE;
        if (!"RESTRINGIDO".equals(workshop.getCierreEstado()) || user.getRole() != UserRole.ADMIN
                || !Boolean.TRUE.equals(user.getEmailVerificado()) || workshop.getCierreVersion() == 0
                || workshop.getCierreReferencia() == null || workshop.getCierreConfirmadoEn() == null
                || workshop.getCierreReversibleHasta() == null || workshop.getCierreEliminacionPrevistaEn() == null) {
            return Mode.DENIED;
        }
        try {
            var schedule = new WorkshopClosurePolicy.Schedule(workshop.getCierreConfirmadoEn().toInstant(),
                    workshop.getCierreReversibleHasta().toInstant(), workshop.getCierreEliminacionPrevistaEn().toInstant());
            return schedule.canRestoreAt(now) ? Mode.RESTRICTED : Mode.DENIED;
        } catch (IllegalArgumentException invalidState) {
            return Mode.DENIED;
        }
    }
}
