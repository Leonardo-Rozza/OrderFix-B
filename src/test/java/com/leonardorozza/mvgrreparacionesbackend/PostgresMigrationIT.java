package com.leonardorozza.mvgrreparacionesbackend;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class PostgresMigrationIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_migration_test")
            .withUsername("ordenfix")
            .withPassword("ordenfix");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.flyway.baseline-on-migrate", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void appliesAllMigrationsFromScratchAndValidatesJpaSchema() {
        assertThat(flyway.info().pending()).isEmpty();

        assertThat(Arrays.stream(flyway.info().applied())
                .map(MigrationInfo::getVersion)
                .filter(version -> version != null)
                .map(Object::toString))
                .contains("17", "18", "19", "20", "21", "22", "23", "24", "25");

        String tipoPngQr = jdbcTemplate.queryForObject("""
                SELECT data_type
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'taller_qr_cobro'
                  AND column_name = 'png'
                """, String.class);
        Integer largoShaQr = jdbcTemplate.queryForObject("""
                SELECT character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'taller_qr_cobro'
                  AND column_name = 'sha256'
                """, Integer.class);
        Integer columnasQrNoNulas = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'taller_qr_cobro'
                  AND column_name IN ('taller_id', 'png', 'sha256')
                  AND is_nullable = 'NO'
                """, Integer.class);
        assertThat(tipoPngQr).isEqualTo("bytea");
        assertThat(largoShaQr).isEqualTo(64);
        assertThat(columnasQrNoNulas).isEqualTo(3);

        String accionBorradoTallerQr = jdbcTemplate.queryForObject("""
                SELECT confdeltype::text
                FROM pg_constraint
                WHERE conname = 'fk_taller_qr_cobro_taller'
                """, String.class);
        assertThat(accionBorradoTallerQr).isEqualTo("c");

        Integer primaryKeyQr = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_constraint
                WHERE conrelid = 'taller_qr_cobro'::regclass
                  AND contype = 'p'
                """, Integer.class);
        assertThat(primaryKeyQr).isEqualTo(1);

        Integer checksQrValidados = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_constraint
                WHERE conname IN ('ck_taller_qr_cobro_png', 'ck_taller_qr_cobro_sha256')
                  AND convalidated = TRUE
                """, Integer.class);
        String definicionCheckPngQr = jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conname = 'ck_taller_qr_cobro_png'
                """, String.class);
        String definicionCheckShaQr = jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conname = 'ck_taller_qr_cobro_sha256'
                """, String.class);
        assertThat(checksQrValidados).isEqualTo(2);
        assertThat(definicionCheckPngQr).contains("octet_length", "1048576");
        assertThat(definicionCheckShaQr).contains("sha256", "64", "0-9a-f");

        Integer largoAliasCobro = jdbcTemplate.queryForObject("""
                SELECT character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'talleres'
                  AND column_name = 'alias_cobro'
                """, Integer.class);
        Integer largoTitularCobro = jdbcTemplate.queryForObject("""
                SELECT character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'talleres'
                  AND column_name = 'titular_cobro'
                """, Integer.class);
        Integer largoEntidadCobro = jdbcTemplate.queryForObject("""
                SELECT character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'talleres'
                  AND column_name = 'entidad_cobro'
                """, Integer.class);
        assertThat(largoAliasCobro).isEqualTo(120);
        assertThat(largoTitularCobro).isEqualTo(160);
        assertThat(largoEntidadCobro).isEqualTo(120);

        String mostrarEnResumenNullable = jdbcTemplate.queryForObject("""
                SELECT is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'talleres'
                  AND column_name = 'mostrar_en_resumen'
                """, String.class);
        String mostrarEnResumenDefault = jdbcTemplate.queryForObject("""
                SELECT column_default
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'talleres'
                  AND column_name = 'mostrar_en_resumen'
                """, String.class);
        assertThat(mostrarEnResumenNullable).isEqualTo("NO");
        assertThat(mostrarEnResumenDefault).isEqualTo("true");

        Boolean mostrarEnResumenNuevoTaller = jdbcTemplate.queryForObject("""
                INSERT INTO talleres (nombre)
                VALUES ('Taller migración V24')
                RETURNING mostrar_en_resumen
                """, Boolean.class);
        assertThat(mostrarEnResumenNuevoTaller).isTrue();

        Integer largoReferencia = jdbcTemplate.queryForObject("""
                SELECT character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'cobros'
                  AND column_name = 'referencia'
                """, Integer.class);
        assertThat(largoReferencia).isEqualTo(120);

        Integer columnasAnulacion = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'cobros'
                  AND column_name IN ('anulado_at', 'anulado_por_id', 'motivo_anulacion')
                  AND is_nullable = 'YES'
                """, Integer.class);
        assertThat(columnasAnulacion).isEqualTo(3);

        String accionBorradoFk = jdbcTemplate.queryForObject("""
                SELECT confdeltype::text
                FROM pg_constraint
                WHERE conname = 'fk_cobros_anulado_por'
                """, String.class);
        assertThat(accionBorradoFk).isEqualTo("a");

        Boolean checkAnulacionValidado = jdbcTemplate.queryForObject("""
                SELECT convalidated
                FROM pg_constraint
                WHERE conname = 'ck_cobros_anulacion_completa'
                """, Boolean.class);
        assertThat(checkAnulacionValidado).isTrue();

        String definicionCheckAnulacion = jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conname = 'ck_cobros_anulacion_completa'
                """, String.class);
        assertThat(definicionCheckAnulacion)
                .contains("anulado_at", "anulado_por_id", "motivo_anulacion", "btrim");

        Integer indicesActivos = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND indexname IN (
                    'idx_cobros_reparacion_activo',
                    'idx_cobros_taller_fecha_activo'
                  )
                """, Integer.class);
        assertThat(indicesActivos).isEqualTo(2);

        Integer checksNoValidados = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_constraint
                WHERE conname IN (
                    'ck_reparaciones_precio_estimado_no_negativo',
                    'ck_reparaciones_precio_final_no_negativo',
                    'ck_repuestos_precio_no_negativo',
                    'ck_repuestos_cantidad_positiva',
                    'ck_cobros_monto_positivo',
                    'ck_presupuestos_total_no_negativo',
                    'ck_presupuesto_items_precio_no_negativo',
                    'ck_presupuesto_items_cantidad_positiva',
                    'ck_articulos_precio_no_negativo',
                    'ck_articulos_costo_no_negativo',
                    'ck_articulos_stock_no_negativo',
                    'ck_articulos_stock_minimo_no_negativo'
                ) AND convalidated = FALSE
                """, Integer.class);
        assertThat(checksNoValidados).isEqualTo(12);
    }

    @Test
    void v21AplicaLosChecksNotValidALasEscriturasNuevas() {
        Long tallerId = jdbcTemplate.queryForObject("""
                INSERT INTO talleres (nombre)
                VALUES ('Taller migración V21')
                RETURNING id
                """, Long.class);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO articulos (taller_id, nombre, precio, stock, stock_minimo, activo)
                VALUES (?, 'Artículo inválido', -1, 0, 0, TRUE)
                """, tallerId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining("ck_articulos_precio_no_negativo");

        Integer insertados = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM articulos
                WHERE taller_id = ? AND nombre = 'Artículo inválido'
                """, Integer.class, tallerId);
        assertThat(insertados).isZero();
    }

    @Test
    void v25ImponeRelacionUnoAUnoShaHexadecimalYLimiteBytea() {
        Long tallerId = jdbcTemplate.queryForObject("""
                INSERT INTO talleres (nombre)
                VALUES ('Taller QR válido')
                RETURNING id
                """, Long.class);
        jdbcTemplate.update("""
                INSERT INTO taller_qr_cobro (taller_id, png, sha256)
                VALUES (?, ?, ?)
                """, tallerId, new byte[]{1, 2, 3}, "a".repeat(64));

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO taller_qr_cobro (taller_id, png, sha256)
                VALUES (?, ?, ?)
                """, tallerId, new byte[]{4}, "b".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining("taller_qr_cobro_pkey");

        Long tallerShaInvalido = jdbcTemplate.queryForObject("""
                INSERT INTO talleres (nombre)
                VALUES ('Taller QR sha inválido')
                RETURNING id
                """, Long.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO taller_qr_cobro (taller_id, png, sha256)
                VALUES (?, ?, ?)
                """, tallerShaInvalido, new byte[]{1}, "Z".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining("ck_taller_qr_cobro_sha256");

        Long tallerPngInvalido = jdbcTemplate.queryForObject("""
                INSERT INTO talleres (nombre)
                VALUES ('Taller QR png inválido')
                RETURNING id
                """, Long.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO taller_qr_cobro (taller_id, png, sha256)
                VALUES (?, ?, ?)
                """, tallerPngInvalido, new byte[1_048_577], "c".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining("ck_taller_qr_cobro_png");
    }
}
