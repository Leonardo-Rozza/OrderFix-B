package com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums;

/**
 * Funciones exclusivas del plan PRO. La clave (key) es la que se expone al frontend
 * en el mapa de capacidades de GET /api/suscripcion.
 */
public enum PlanFeature {
    INVENTARIO("inventario"),
    COBROS("cobros"),
    DASHBOARD("dashboard"),
    EMPLEADOS_MULTIPLES("empleadosMultiples");

    private final String key;

    PlanFeature(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }
}
