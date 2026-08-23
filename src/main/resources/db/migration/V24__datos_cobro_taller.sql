-- Datos públicos opcionales que cada taller puede compartir para recibir pagos.
ALTER TABLE talleres
    ADD COLUMN alias_cobro VARCHAR(120),
    ADD COLUMN titular_cobro VARCHAR(160),
    ADD COLUMN entidad_cobro VARCHAR(120),
    ADD COLUMN mostrar_en_resumen BOOLEAN NOT NULL DEFAULT TRUE;
