-- QR raster normalizado por taller. El PK sobre taller_id garantiza la relación 1:1.
CREATE TABLE taller_qr_cobro (
    taller_id BIGINT PRIMARY KEY,
    png BYTEA NOT NULL,
    sha256 VARCHAR(64) NOT NULL,

    CONSTRAINT fk_taller_qr_cobro_taller
        FOREIGN KEY (taller_id) REFERENCES talleres (id) ON DELETE CASCADE,
    CONSTRAINT ck_taller_qr_cobro_png
        CHECK (octet_length(png) BETWEEN 1 AND 1048576),
    CONSTRAINT ck_taller_qr_cobro_sha256
        CHECK (sha256 ~ '^[0-9a-f]{64}$')
);
