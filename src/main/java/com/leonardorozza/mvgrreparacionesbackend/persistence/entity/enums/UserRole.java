package com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums;

public enum UserRole {
    ADMIN,
    USER;

    public AudienciaLegal toAudienciaLegal() {
        return switch (this) {
            case ADMIN -> AudienciaLegal.ADMIN_TITULAR;
            case USER -> AudienciaLegal.USER;
        };
    }
}
