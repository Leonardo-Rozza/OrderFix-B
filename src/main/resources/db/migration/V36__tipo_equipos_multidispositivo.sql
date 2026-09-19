-- Equipment categories share the existing repair workflow. Existing rows remain unclassified:
-- PostgreSQL supplies the constant default without issuing UPDATE against restricted workshops.
ALTER TABLE public.equipos
    ADD COLUMN tipo VARCHAR(20) NOT NULL DEFAULT 'OTRO',
    ADD CONSTRAINT ck_equipos_tipo CHECK (
        tipo IN ('CELULAR','NOTEBOOK','CONSOLA','PC_ESCRITORIO','MONITOR','TV','OTRO'));
