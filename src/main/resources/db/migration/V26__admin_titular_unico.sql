-- ============================================================
-- V26 - Un único ADMIN titular por taller
-- ============================================================
--
-- No se elige ni se corrige un titular automáticamente. Ante datos históricos
-- incompatibles la migración falla y obliga a resolverlos explícitamente antes
-- del despliegue.

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM users WHERE taller_id IS NULL) THEN
        RAISE EXCEPTION
            'V26 no puede aplicarse: existen usuarios sin taller.';
    END IF;

    IF EXISTS (SELECT 1 FROM users WHERE role NOT IN ('ADMIN', 'USER')) THEN
        RAISE EXCEPTION
            'V26 no puede aplicarse: existen roles de usuario desconocidos.';
    END IF;

    IF EXISTS (
        SELECT taller_id
        FROM users
        WHERE role = 'ADMIN'
        GROUP BY taller_id
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION
            'V26 no puede aplicarse: existen talleres con múltiples ADMIN.';
    END IF;
END $$;

ALTER TABLE users
    ALTER COLUMN taller_id SET NOT NULL;

ALTER TABLE users
    ADD CONSTRAINT ck_users_role
    CHECK (role IN ('ADMIN', 'USER'));

CREATE UNIQUE INDEX uk_users_admin_titular_por_taller
    ON users (taller_id)
    WHERE role = 'ADMIN';
