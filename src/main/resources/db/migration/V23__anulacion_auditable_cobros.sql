-- Una anulación conserva el movimiento original y agrega su auditoría.
ALTER TABLE cobros
    ADD COLUMN anulado_at TIMESTAMP,
    ADD COLUMN anulado_por_id BIGINT,
    ADD COLUMN motivo_anulacion VARCHAR(255);

ALTER TABLE cobros
    ADD CONSTRAINT fk_cobros_anulado_por
        FOREIGN KEY (anulado_por_id) REFERENCES users (id),
    ADD CONSTRAINT ck_cobros_anulacion_completa
        CHECK (
            (anulado_at IS NULL AND anulado_por_id IS NULL AND motivo_anulacion IS NULL)
            OR
            (anulado_at IS NOT NULL
                AND anulado_por_id IS NOT NULL
                AND motivo_anulacion IS NOT NULL
                AND btrim(motivo_anulacion) <> '')
        );

CREATE INDEX idx_cobros_reparacion_activo
    ON cobros (reparacion_id)
    WHERE anulado_at IS NULL;

CREATE INDEX idx_cobros_taller_fecha_activo
    ON cobros (taller_id, created_at)
    WHERE anulado_at IS NULL;
