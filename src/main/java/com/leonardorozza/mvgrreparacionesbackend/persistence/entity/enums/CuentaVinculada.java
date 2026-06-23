package com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums;

/**
 * Cuenta vinculada al equipo (Activation Lock / FRP). Si está activa y el cliente
 * no conoce las credenciales, tras una reparación el equipo puede quedar bloqueado
 * en la pantalla de bienvenida y no poder entregarse activado.
 */
public enum CuentaVinculada {
    NINGUNA,
    ICLOUD,
    GOOGLE,
    OTRA
}
