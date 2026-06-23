package com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums;

/**
 * Momento en que se tomó la foto del equipo. Las de ingreso prueban la condición
 * con la que entró (anti "me lo rompiste vos"); las post prueban el trabajo (QA).
 */
public enum MomentoFoto {
    INGRESO,
    POST_REPARACION
}
