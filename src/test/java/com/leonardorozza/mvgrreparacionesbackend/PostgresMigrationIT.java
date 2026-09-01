package com.leonardorozza.mvgrreparacionesbackend;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Arrays;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class PostgresMigrationIT {

    private static final Set<String> TABLAS_LEGALES_V27 = Set.of(
            "legal_publicaciones",
            "legal_documento_lineas",
            "legal_documento_versiones",
            "legal_documento_contextos",
            "legal_publicacion_documentos",
            "legal_documento_transiciones",
            "legal_documento_vigentes",
            "legal_documento_reemplazo_lotes",
            "legal_documento_reemplazo_anteriores",
            "legal_documento_reemplazo_sucesoras",
            "legal_requisito_lineas",
            "legal_requisito_audiencias",
            "legal_requisito_versiones",
            "legal_requisito_documentos",
            "legal_publicacion_requisitos",
            "legal_requisito_transiciones",
            "legal_requisito_conjuntos",
            "legal_requisito_conjunto_miembros",
            "legal_requisito_conjuntos_actuales",
            "legal_aceptacion_lotes",
            "legal_aceptaciones",
            "legal_aceptacion_documentos",
            "legal_aceptacion_metadatos",
            "legal_aceptacion_metadatos_cifrados",
            "legal_idempotencia_resultados");

    private static final Set<String> TABLAS_LEGALES_LATEST = Set.copyOf(
            Stream.concat(
                            TABLAS_LEGALES_V27.stream(),
                            Stream.of("legal_requisito_agregados", "legal_requisito_agregado_scopes"))
                    .toList());

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
                .contains("17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27", "28");

        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("28");

        Integer migracionV28Exitosa = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version = '28'
                  AND success = TRUE
                """, Integer.class);
        assertThat(migracionV28Exitosa).isOne();

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
                  AND connamespace = 'public'::regnamespace
                """, String.class);
        assertThat(accionBorradoTallerQr).isEqualTo("c");

        Integer primaryKeyQr = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_constraint
                WHERE conrelid = 'public.taller_qr_cobro'::regclass
                  AND contype = 'p'
                """, Integer.class);
        assertThat(primaryKeyQr).isEqualTo(1);

        Integer checksQrValidados = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_constraint
                WHERE conname IN ('ck_taller_qr_cobro_png', 'ck_taller_qr_cobro_sha256')
                  AND connamespace = 'public'::regnamespace
                  AND convalidated = TRUE
                """, Integer.class);
        String definicionCheckPngQr = jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conname = 'ck_taller_qr_cobro_png'
                  AND connamespace = 'public'::regnamespace
                """, String.class);
        String definicionCheckShaQr = jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conname = 'ck_taller_qr_cobro_sha256'
                  AND connamespace = 'public'::regnamespace
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
                  AND connamespace = 'public'::regnamespace
                """, String.class);
        assertThat(accionBorradoFk).isEqualTo("a");

        Boolean checkAnulacionValidado = jdbcTemplate.queryForObject("""
                SELECT convalidated
                FROM pg_constraint
                WHERE conname = 'ck_cobros_anulacion_completa'
                  AND connamespace = 'public'::regnamespace
                """, Boolean.class);
        assertThat(checkAnulacionValidado).isTrue();

        String definicionCheckAnulacion = jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conname = 'ck_cobros_anulacion_completa'
                  AND connamespace = 'public'::regnamespace
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
                )
                  AND connamespace = 'public'::regnamespace
                  AND convalidated = FALSE
                """, Integer.class);
        assertThat(checksNoValidados).isEqualTo(12);
    }

    @Test
    void latestV28CreaLaEstructuraLegalComprobableSinDatosSemilla() {
        var tablasLegales = jdbcTemplate.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_type = 'BASE TABLE'
                  AND table_name LIKE 'legal_%'
                """, String.class);
        assertThat(tablasLegales).containsExactlyInAnyOrderElementsOf(TABLAS_LEGALES_LATEST);
        assertTablasLegalesVacias(jdbcTemplate, "public", TABLAS_LEGALES_LATEST);

        var tiposRepresentativos = jdbcTemplate.queryForList("""
                SELECT table_name || '.' || column_name || ':' || data_type
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND (table_name, column_name) IN (
                    ('legal_publicaciones', 'id'),
                    ('legal_publicaciones', 'importado_en'),
                    ('legal_documento_contextos', 'id'),
                    ('legal_aceptacion_lotes', 'user_id'),
                    ('legal_aceptacion_lotes', 'revision_scheme'),
                    ('legal_aceptacion_lotes', 'perfil'),
                    ('legal_aceptacion_lotes', 'agregado_id'),
                    ('legal_aceptacion_metadatos_cifrados', 'nonce')
                  )
                """, String.class);
        assertThat(tiposRepresentativos).containsExactlyInAnyOrder(
                "legal_publicaciones.id:uuid",
                "legal_publicaciones.importado_en:timestamp with time zone",
                "legal_documento_contextos.id:bigint",
                "legal_aceptacion_lotes.user_id:bigint",
                "legal_aceptacion_lotes.revision_scheme:character varying",
                "legal_aceptacion_lotes.perfil:character varying",
                "legal_aceptacion_lotes.agregado_id:uuid",
                "legal_aceptacion_metadatos_cifrados.nonce:bytea");

        String identidadFisicaAgregado = jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conname = 'uk_legal_requisito_agregado_identidad_fisica'
                  AND connamespace = 'public'::regnamespace
                """, String.class);
        assertThat(identidadFisicaAgregado).isEqualTo(
                "UNIQUE (perfil, locale, audiencia, revision_scheme, required_set_revision, provenance_fingerprint)");

        var triggerAgregadoDiferido = jdbcTemplate.queryForMap("""
                SELECT tgdeferrable, tginitdeferred
                FROM pg_trigger
                WHERE tgname = 'ct_legal_requisito_agregado_completo'
                  AND tgrelid = 'public.legal_requisito_agregados'::regclass
                """);
        assertThat(triggerAgregadoDiferido)
                .containsEntry("tgdeferrable", true)
                .containsEntry("tginitdeferred", true);

        String uniqueActorTenant = jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conname = 'uk_users_id_taller_id'
                  AND connamespace = 'public'::regnamespace
                """, String.class);
        assertThat(uniqueActorTenant).isEqualTo("UNIQUE (id, taller_id)");

        var constraintsClave = jdbcTemplate.queryForList("""
                SELECT conname
                FROM pg_constraint
                WHERE connamespace = 'public'::regnamespace
                  AND conname IN (
                    'ck_legal_publicaciones_manifest_sha',
                    'pk_legal_documento_vigentes',
                    'fk_legal_aceptacion_lote_actor',
                    'uk_legal_aceptacion_usuario_requisito',
                    'uk_legal_aceptacion_metadata_nonce',
                    'uk_legal_idempotencia_resultado',
                    'ck_legal_idempotencia_expiracion'
                  )
                """, String.class);
        assertThat(constraintsClave).containsExactlyInAnyOrder(
                "ck_legal_publicaciones_manifest_sha",
                "pk_legal_documento_vigentes",
                "fk_legal_aceptacion_lote_actor",
                "uk_legal_aceptacion_usuario_requisito",
                "uk_legal_aceptacion_metadata_nonce",
                "uk_legal_idempotencia_resultado",
                "ck_legal_idempotencia_expiracion");

        String fkActorTenant = jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conname = 'fk_legal_aceptacion_lote_actor'
                  AND connamespace = 'public'::regnamespace
                """, String.class);
        assertThat(fkActorTenant)
                .contains("FOREIGN KEY (user_id, taller_id)", "REFERENCES users(id, taller_id)",
                        "ON DELETE RESTRICT");

        Integer foreignKeysLegalesNoRestrict = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_constraint c
                JOIN pg_class tabla ON tabla.oid = c.conrelid
                JOIN pg_namespace esquema ON esquema.oid = tabla.relnamespace
                WHERE esquema.nspname = 'public'
                  AND tabla.relname LIKE 'legal_%'
                  AND c.contype = 'f'
                  AND c.confdeltype <> 'r'
                """, Integer.class);
        assertThat(foreignKeysLegalesNoRestrict).isZero();

        var indicesClave = jdbcTemplate.queryForList("""
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND indexname IN (
                    'idx_legal_publicaciones_locale_estado',
                    'idx_legal_documento_versiones_lineage',
                    'idx_legal_requisito_lineas_scope',
                    'idx_legal_aceptacion_lotes_actor_fecha',
                    'idx_legal_idempotencia_lookup'
                  )
                """, String.class);
        assertThat(indicesClave).containsExactlyInAnyOrder(
                "idx_legal_publicaciones_locale_estado",
                "idx_legal_documento_versiones_lineage",
                "idx_legal_requisito_lineas_scope",
                "idx_legal_aceptacion_lotes_actor_fecha",
                "idx_legal_idempotencia_lookup");

        var triggersClave = jdbcTemplate.queryForList("""
                SELECT trigger.tgname
                FROM pg_trigger trigger
                JOIN pg_class tabla ON tabla.oid = trigger.tgrelid
                JOIN pg_namespace esquema ON esquema.oid = tabla.relnamespace
                WHERE esquema.nspname = 'public'
                  AND NOT trigger.tgisinternal
                  AND trigger.tgname IN (
                    'trg_legal_publicacion_update_statement',
                    'trg_legal_publicacion_update_guard',
                    'ct_legal_publicacion_sello',
                    'trg_legal_documento_transicion_isolation',
                    'trg_legal_requisito_transicion_isolation',
                    'ct_legal_documento_estado_slots',
                    'ct_legal_requisito_actual_insert',
                    'trg_legal_reemplazo_lote_update_isolation',
                    'ct_legal_reemplazo_lote_sello',
                    'ct_legal_aceptacion_lote_completo',
                    'ct_legal_metadata_cifrada_completa',
                    'trg_legal_idempotencia_update_delete'
                  )
                """, String.class);
        assertThat(triggersClave).containsExactlyInAnyOrder(
                "trg_legal_publicacion_update_statement",
                "trg_legal_publicacion_update_guard",
                "ct_legal_publicacion_sello",
                "trg_legal_documento_transicion_isolation",
                "trg_legal_requisito_transicion_isolation",
                "ct_legal_documento_estado_slots",
                "ct_legal_requisito_actual_insert",
                "trg_legal_reemplazo_lote_update_isolation",
                "ct_legal_reemplazo_lote_sello",
                "ct_legal_aceptacion_lote_completo",
                "ct_legal_metadata_cifrada_completa",
                "trg_legal_idempotencia_update_delete");

        Integer constraintTriggersDiferidos = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_trigger trigger
                JOIN pg_class relation ON relation.oid = trigger.tgrelid
                JOIN pg_namespace schema ON schema.oid = relation.relnamespace
                WHERE schema.nspname = 'public'
                  AND trigger.tgname IN (
                    'ct_legal_publicacion_sello',
                    'ct_legal_documento_estado_slots',
                    'ct_legal_requisito_actual_insert',
                    'ct_legal_reemplazo_lote_sello',
                    'ct_legal_aceptacion_lote_completo',
                    'ct_legal_metadata_cifrada_completa'
                  )
                  AND trigger.tgdeferrable
                  AND trigger.tginitdeferred
                """, Integer.class);
        assertThat(constraintTriggersDiferidos).isEqualTo(6);

        var funcionesClave = jdbcTemplate.queryForList("""
                SELECT rutina.proname
                FROM pg_proc rutina
                JOIN pg_namespace esquema ON esquema.oid = rutina.pronamespace
                WHERE esquema.nspname = 'public'
                  AND rutina.proname IN (
                    'legal_exigir_read_committed',
                    'legal_publicacion_update_statement_guard',
                    'legal_publicacion_constraint_guard',
                    'legal_slots_constraint_guard',
                    'legal_conjuntos_actuales_constraint_guard',
                    'legal_reemplazo_constraint_guard',
                    'legal_aceptacion_constraint_guard',
                    'legal_idempotencia_update_delete_guard'
                  )
                """, String.class);
        assertThat(funcionesClave).containsExactlyInAnyOrder(
                "legal_exigir_read_committed",
                "legal_publicacion_update_statement_guard",
                "legal_publicacion_constraint_guard",
                "legal_slots_constraint_guard",
                "legal_conjuntos_actuales_constraint_guard",
                "legal_reemplazo_constraint_guard",
                "legal_aceptacion_constraint_guard",
                "legal_idempotencia_update_delete_guard");

        Integer funcionesLegalesSinSearchPathSeguro = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_proc funcion
                JOIN pg_namespace esquema ON esquema.oid = funcion.pronamespace
                WHERE esquema.nspname = current_schema()
                  AND funcion.proname LIKE 'legal\\_%' ESCAPE '\\'
                  AND NOT EXISTS (
                      SELECT 1
                      FROM unnest(funcion.proconfig) configuracion
                      WHERE configuracion = format(
                          'search_path=pg_catalog, %I, pg_temp', current_schema()
                      )
                  )
                """, Integer.class);
        assertThat(funcionesLegalesSinSearchPathSeguro).isZero();
    }

    @Test
    void v28MigraDesdeV26YV27VacioPreservandoUsuariosYTalleresSinSemillasLegales() {
        String schema = "v27_upgrade_compatible";
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());

        Flyway flywayV26 = flywayDeSchema(dataSource, schema, "26");
        flywayV26.migrate();
        JdbcTemplate schemaJdbc = new JdbcTemplate(dataSource);

        Long tallerId = schemaJdbc.queryForObject("""
                INSERT INTO %s.talleres (nombre)
                VALUES ('Taller preservado V27')
                RETURNING id
                """.formatted(schema), Long.class);
        Long userId = schemaJdbc.queryForObject("""
                INSERT INTO %s.users (username, password, email, role, active, taller_id)
                VALUES ('Usuario preservado V27', 'hash', 'preservado-v27@test.com', 'USER', TRUE, ?)
                RETURNING id
                """.formatted(schema), Long.class, tallerId);
        Integer talleresAntes = schemaJdbc.queryForObject(
                "SELECT COUNT(*) FROM %s.talleres".formatted(schema), Integer.class);
        Integer usersAntes = schemaJdbc.queryForObject(
                "SELECT COUNT(*) FROM %s.users".formatted(schema), Integer.class);

        Flyway flywayV27 = flywayDeSchema(dataSource, schema, "27");
        flywayV27.migrate();

        assertThat(flywayV27.info().current().getVersion().toString()).isEqualTo("27");
        assertThat(schemaJdbc.queryForObject(
                "SELECT COUNT(*) FROM %s.talleres".formatted(schema), Integer.class))
                .isEqualTo(talleresAntes);
        assertThat(schemaJdbc.queryForObject(
                "SELECT COUNT(*) FROM %s.users".formatted(schema), Integer.class))
                .isEqualTo(usersAntes);
        assertThat(schemaJdbc.queryForObject("""
                SELECT nombre
                FROM %s.talleres
                WHERE id = ?
                """.formatted(schema), String.class, tallerId))
                .isEqualTo("Taller preservado V27");
        assertThat(schemaJdbc.queryForObject("""
                SELECT email
                FROM %s.users
                WHERE id = ? AND taller_id = ?
                """.formatted(schema), String.class, userId, tallerId))
                .isEqualTo("preservado-v27@test.com");

        var tablasLegales = schemaJdbc.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = ?
                  AND table_type = 'BASE TABLE'
                  AND table_name LIKE 'legal_%'
                """, String.class, schema);
        assertThat(tablasLegales).containsExactlyInAnyOrderElementsOf(TABLAS_LEGALES_V27);
        assertTablasLegalesVacias(schemaJdbc, schema, TABLAS_LEGALES_V27);

        Flyway flywayV28 = flywayDeSchema(dataSource, schema, null);
        flywayV28.migrate();

        assertThat(flywayV28.info().current().getVersion().toString()).isEqualTo("28");
        assertThat(schemaJdbc.queryForObject(
                "SELECT COUNT(*) FROM %s.talleres".formatted(schema), Integer.class))
                .isEqualTo(talleresAntes);
        assertThat(schemaJdbc.queryForObject(
                "SELECT COUNT(*) FROM %s.users".formatted(schema), Integer.class))
                .isEqualTo(usersAntes);
        assertThat(schemaJdbc.queryForObject("""
                SELECT nombre
                FROM %s.talleres
                WHERE id = ?
                """.formatted(schema), String.class, tallerId))
                .isEqualTo("Taller preservado V27");
        assertThat(schemaJdbc.queryForObject("""
                SELECT email
                FROM %s.users
                WHERE id = ? AND taller_id = ?
                """.formatted(schema), String.class, userId, tallerId))
                .isEqualTo("preservado-v27@test.com");

        var tablasLegalesV28 = schemaJdbc.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = ?
                  AND table_type = 'BASE TABLE'
                  AND table_name LIKE 'legal_%'
                """, String.class, schema);
        assertThat(tablasLegalesV28)
                .containsExactlyInAnyOrderElementsOf(TABLAS_LEGALES_LATEST);
        assertTablasLegalesVacias(schemaJdbc, schema, TABLAS_LEGALES_LATEST);
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

    @Test
    void v26ImpideMasDeUnAdminTitularPorTaller() {
        String definicionIndice = jdbcTemplate.queryForObject("""
                SELECT indexdef
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND indexname = 'uk_users_admin_titular_por_taller'
                """, String.class);
        assertThat(definicionIndice).contains("UNIQUE", "taller_id", "role", "ADMIN");

        Boolean indiceValido = jdbcTemplate.queryForObject("""
                SELECT i.indisvalid
                FROM pg_index i
                JOIN pg_class c ON c.oid = i.indexrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public'
                  AND c.relname = 'uk_users_admin_titular_por_taller'
                """, Boolean.class);
        String tallerIdNullable = jdbcTemplate.queryForObject("""
                SELECT is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'users'
                  AND column_name = 'taller_id'
                """, String.class);
        Boolean checkRolValidado = jdbcTemplate.queryForObject("""
                SELECT convalidated
                FROM pg_constraint
                WHERE conname = 'ck_users_role'
                  AND connamespace = 'public'::regnamespace
                """, Boolean.class);
        assertThat(indiceValido).isTrue();
        assertThat(tallerIdNullable).isEqualTo("NO");
        assertThat(checkRolValidado).isTrue();

        Long tallerA = jdbcTemplate.queryForObject("""
                INSERT INTO talleres (nombre)
                VALUES ('Taller titular único A')
                RETURNING id
                """, Long.class);
        jdbcTemplate.update("""
                INSERT INTO users (username, password, email, role, active, taller_id)
                VALUES ('Titular A', 'hash', 'titular-a@test.com', 'ADMIN', TRUE, ?)
                """, tallerA);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO users (username, password, email, role, active, taller_id)
                VALUES ('Segundo titular A', 'hash', 'titular-a-2@test.com', 'ADMIN', TRUE, ?)
                """, tallerA))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining("uk_users_admin_titular_por_taller");

        jdbcTemplate.update("""
                INSERT INTO users (username, password, email, role, active, taller_id)
                VALUES ('Empleado A', 'hash', 'empleado-a@test.com', 'USER', TRUE, ?)
                """, tallerA);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO users (username, password, email, role, active, taller_id)
                VALUES ('Sin taller', 'hash', 'sin-taller@test.com', 'USER', TRUE, NULL)
                """))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining("taller_id");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO users (username, password, email, role, active, taller_id)
                VALUES ('Rol desconocido', 'hash', 'rol-desconocido@test.com', 'SUPERVISOR', TRUE, ?)
                """, tallerA))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining("ck_users_role");

        Long tallerB = jdbcTemplate.queryForObject("""
                INSERT INTO talleres (nombre)
                VALUES ('Taller titular único B')
                RETURNING id
                """, Long.class);
        jdbcTemplate.update("""
                INSERT INTO users (username, password, email, role, active, taller_id)
                VALUES ('Titular B', 'hash', 'titular-b@test.com', 'ADMIN', TRUE, ?)
                """, tallerB);

        Integer adminsTallerA = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM users
                WHERE taller_id = ? AND role = 'ADMIN'
                """, Integer.class, tallerA);
        assertThat(adminsTallerA).isOne();
    }

    @Test
    void v26RechazaDatosHistoricosIncompatiblesSinElegirUnTitular() {
        assertV26Rechaza("v26_usuario_sin_taller", "existen usuarios sin taller", fixture ->
                fixture.jdbc().update("""
                        INSERT INTO %s.users (username, password, email, role, active)
                        VALUES ('Sin taller', 'hash', 'legacy-sin-taller@test.com', 'USER', TRUE)
                        """.formatted(fixture.schema())));

        assertV26Rechaza("v26_rol_desconocido", "existen roles de usuario desconocidos", fixture -> {
            Long tallerId = fixture.jdbc().queryForObject("""
                    INSERT INTO %s.talleres (nombre)
                    VALUES ('Taller rol legacy')
                    RETURNING id
                    """.formatted(fixture.schema()), Long.class);
            fixture.jdbc().update("""
                    INSERT INTO %s.users (username, password, email, role, active, taller_id)
                    VALUES ('Legacy', 'hash', 'legacy-rol@test.com', 'SUPERVISOR', TRUE, ?)
                    """.formatted(fixture.schema()), tallerId);
        });

        assertV26Rechaza("v26_admins_duplicados", "existen talleres con múltiples ADMIN", fixture -> {
            Long tallerId = fixture.jdbc().queryForObject("""
                    INSERT INTO %s.talleres (nombre)
                    VALUES ('Taller admins legacy')
                    RETURNING id
                    """.formatted(fixture.schema()), Long.class);
            fixture.jdbc().update("""
                    INSERT INTO %s.users (username, password, email, role, active, taller_id)
                    VALUES
                      ('Admin uno', 'hash', 'legacy-admin-1@test.com', 'ADMIN', TRUE, ?),
                      ('Admin dos', 'hash', 'legacy-admin-2@test.com', 'ADMIN', TRUE, ?)
                    """.formatted(fixture.schema()), tallerId, tallerId);
        });
    }

    private void assertTablasLegalesVacias(
            JdbcTemplate jdbc, String schema, Set<String> tablasLegales) {
        assertThat(tablasLegales).allSatisfy(tabla -> {
            Integer filas = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM %s.%s".formatted(schema, tabla), Integer.class);
            assertThat(filas).as("filas semilla en %s.%s", schema, tabla).isZero();
        });
    }

    private void assertV26Rechaza(
            String schema, String mensajeEsperado, Consumer<SchemaFixture> prepararDatos) {
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());

        flywayDeSchema(dataSource, schema, "25").migrate();
        JdbcTemplate schemaJdbc = new JdbcTemplate(dataSource);
        prepararDatos.accept(new SchemaFixture(schema, schemaJdbc));

        assertThatThrownBy(() -> flywayDeSchema(dataSource, schema, null).migrate())
                .isInstanceOf(FlywayException.class)
                .hasStackTraceContaining(mensajeEsperado);
    }

    private Flyway flywayDeSchema(DataSource dataSource, String schema, String target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private record SchemaFixture(String schema, JdbcTemplate jdbc) {
    }
}
