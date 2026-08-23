-- Referencia externa informada manualmente por el taller (alias de operación,
-- número de transferencia, comprobante, etc.). No implica conciliación bancaria.
ALTER TABLE cobros
    ADD COLUMN referencia VARCHAR(120);
