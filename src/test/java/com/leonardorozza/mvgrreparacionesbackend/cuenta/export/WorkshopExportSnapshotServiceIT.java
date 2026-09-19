package com.leonardorozza.mvgrreparacionesbackend.cuenta.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real PostgreSQL snapshots and adversarial tenant relations; no Boot or provider configuration. */
@Testcontainers
class WorkshopExportSnapshotServiceIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_export_snapshot")
            .withUsername("ordenfix")
            .withPassword("ordenfix");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicLong IDS = new AtomicLong(9_007_199_254_741_000L);
    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate owner;

    @TempDir Path exportRoot;
    private Scope own;
    private Scope foreign;
    private ObservedDataSource observed;
    private WorkshopExportSnapshotService service;

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        owner = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }

    @BeforeEach
    void fixtures() throws Exception {
        long base = IDS.getAndAdd(100);
        own = seed(base, "OWN_" + base);
        foreign = seed(base + 40, "FOREIGN_" + base);
        owner.update("UPDATE equipos SET tipo='NOTEBOOK',imei='000123456789012' WHERE id=?", own.equipo());
        owner.update("UPDATE equipos SET tipo='TV' WHERE id=?", foreign.equipo());
        observed = new ObservedDataSource(dataSource, false);
        service = service(observed);
    }

    @Test
    void exportsAllBusinessCategoriesPreciselyWithoutSecretsOrOtherWorkshopValues() throws Exception {
        Instant before = Instant.now();
        ExportSnapshot snapshot = capture();
        assertThat(snapshot.actorId()).isEqualTo(own.admin());
        assertThat(snapshot.tallerId()).isEqualTo(own.taller());
        assertThat(snapshot.observedAt()).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
        assertThat(snapshot.pendingPhotos()).isEmpty();
        assertThat(observed.commits.get()).isEqualTo(1);
        assertThat(observed.rollbacks.get()).isZero();

        Map<String, JsonNode> categories = categories(snapshot);
        assertThat(categories.keySet()).contains(
                "talleres", "users", "clientes", "equipos", "reparaciones", "repuestos",
                "articulos", "presupuestos", "presupuesto_items", "cobros", "suscripciones",
                "subscription_payments");
        assertThat(categories.get("talleres").size()).isEqualTo(1);
        assertThat(categories.get("users").size()).isEqualTo(2);
        assertThat(categories.get("clientes").size()).isEqualTo(1);
        assertThat(categories.get("equipos").size()).isEqualTo(1);
        assertThat(categories.get("equipos").get(0).path("tipo").asText()).isEqualTo("NOTEBOOK");
        assertThat(categories.get("equipos").get(0).path("imei").asText()).isEqualTo("000123456789012");
        assertThat(categories.get("reparaciones").size()).isEqualTo(1);
        assertThat(categories.get("repuestos").size()).isEqualTo(2);
        assertThat(categories.get("articulos").size()).isEqualTo(1);
        assertThat(categories.get("presupuestos").size()).isEqualTo(1);
        assertThat(categories.get("presupuesto_items").size()).isEqualTo(2);
        assertThat(categories.get("cobros").size()).isEqualTo(1);
        assertThat(categories.get("suscripciones").size()).isEqualTo(1);
        assertThat(categories.get("subscription_payments").size()).isEqualTo(1);

        JsonNode customer = categories.get("clientes").get(0);
        assertThat(customer.path("id").isTextual()).isTrue();
        assertThat(customer.path("id").asText()).isEqualTo(Long.toString(own.cliente()));
        assertThat(customer.path("nombre").asText()).isEqualTo(own.marker() + " cliente\n=SUM(1,2) á");
        assertThat(customer.get("direccion").isNull()).isTrue();
        assertThat(customer.has("_relaciones_validas")).isFalse();
        assertThat(categories.get("users").get(1).path("active").isBoolean()).isTrue();
        assertThat(categories.get("users").get(1).path("active").asBoolean()).isFalse();
        assertThat(categories.get("articulos").get(0).path("activo").asBoolean()).isFalse();
        assertThat(categories.get("articulos").get(0).path("stock").isInt()).isTrue();
        assertThat(categories.get("articulos").get(0).path("stock").asInt()).isEqualTo(7);
        assertThat(categories.get("repuestos").get(1).get("reparacion_id").isNull()).isTrue();
        assertThat(categories.get("repuestos").get(1).get("articulo_id").isNull()).isTrue();
        assertThat(categories.get("presupuesto_items").get(0))
                .isEqualTo(categories.get("presupuesto_items").get(1));
        assertThat(categories.get("presupuesto_items").get(0).has("id")).isFalse();
        assertThat(categories.get("presupuesto_items").get(0).has("posicion")).isFalse();

        JsonNode repair = categories.get("reparaciones").get(0);
        assertThat(repair.path("precio_estimado").asText()).isEqualTo("12345678.90");
        assertThat(repair.get("precio_final").isNull()).isTrue();
        assertThat(repair.path("fecha_ingreso").asText()).isEqualTo("2026-09-10");
        assertThat(repair.path("created_at").asText()).isEqualTo("2026-09-10T10:11:12.123456");
        assertThat(repair.path("mojado").isBoolean()).isTrue();
        assertThat(repair.path("mojado").asBoolean()).isTrue();
        assertThat(repair.path("numero_orden").asText()).isEqualTo("ORD-2026-0042");
        assertThat(repair.has("codigo_seguimiento")).isFalse();
        assertThat(repair.has("pin_desbloqueo_cifrado")).isFalse();
        assertThat(repair.has("patron_desbloqueo_cifrado")).isFalse();
        assertThat(repair.has("credenciales_cifrado_version")).isFalse();
        JsonNode cancelled = categories.get("cobros").get(0);
        assertThat(cancelled.path("anulado_por_id").asText()).isEqualTo(Long.toString(own.employee()));
        assertThat(cancelled.path("motivo_anulacion").asText()).isEqualTo("Carga duplicada");
        assertThat(cancelled.path("anulado_at").asText()).isEqualTo("2026-09-11T11:12:13");
        JsonNode payment = categories.get("subscription_payments").get(0);
        assertThat(payment.path("amount").isTextual()).isTrue();
        assertThat(payment.path("amount").asText()).isEqualTo("12345678901234567.89");
        assertThat(payment.path("external_payment_id").asText()).isEqualTo(own.marker() + "_commercial_payment");
        assertThat(payment.path("status_detail").asText()).isEqualTo("reimbursed");
        assertThat(payment.path("debit_at").asText()).isEqualTo("2026-09-11T12:23:34Z");
        assertThat(payment.has("retry_attempt")).isFalse();
        assertThat(payment.has("summarized")).isFalse();

        String content = allText(snapshot);
        assertThat(content).doesNotContain(foreign.marker(), "PASSWORD_CANARY", "PIN_CANARY",
                "PATTERN_CANARY", "PAYER_CANARY", "CHECKOUT_CANARY", "CORRELATION_CANARY",
                "SUMMARY_CANARY", "CAUSAL_CANARY", own.tracking(),
                "_relaciones_validas", "token_version", "auth_tokens", "cuenta_reautenticaciones");
        assertThat(categories.get("users").get(0).has("password")).isFalse();

        Path directory = new LocalExportPackageWriter().write(snapshot, exportRoot);
        JsonNode manifest = JSON.readTree(Files.readAllBytes(directory.resolve("manifest.json")));
        assertThat(manifest.path("formato").asText()).isEqualTo("ordenfix-export/1");
        assertThat(manifest.path("fase").asText()).isEqualTo("DATOS_LOCALES");
        assertThat(manifest.path("exportacion_integral_completa").asBoolean()).isFalse();
        assertThat(manifest.path("taller_id").asText()).isEqualTo(Long.toString(own.taller()));
        assertThat(manifest.path("solicitante_id").asText()).isEqualTo(Long.toString(own.admin()));
        assertThat(manifest.path("observado_en").asText()).isEqualTo(snapshot.observedAt().toString());
        assertThat(manifest.path("archivos").size()).isEqualTo(snapshot.files().size());
        assertThat(manifest.path("archivos_pendientes").size()).isZero();
        Map<String, JsonNode> entries = new LinkedHashMap<>();
        for (JsonNode entry : manifest.path("archivos")) {
            assertThat(entries.put(entry.path("ruta").asText(), entry)).isNull();
        }
        for (ExportFile source : snapshot.files()) {
            Path written = directory.resolve(source.path());
            assertThat(Files.isRegularFile(written)).isTrue();
            byte[] actual = Files.readAllBytes(written);
            assertThat(actual).containsExactly(bytes(source));
            JsonNode entry = entries.get(source.path());
            assertThat(entry).isNotNull();
            assertThat(entry.path("tipo").asText()).isEqualTo(source.mediaType());
            assertThat(entry.path("bytes").asLong()).isEqualTo(actual.length);
            assertThat(entry.path("sha256").asText()).isEqualTo(
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(actual)));
            if (source.records() >= 0) assertThat(entry.path("registros").asLong()).isEqualTo(source.records());
            else assertThat(entry.has("registros")).isFalse();
        }
        try (var paths = Files.walk(directory)) {
            assertThat(paths.toList()).noneMatch(path -> path.getFileName().toString().endsWith(".part"));
        }
    }

    @Test
    void exceedingTheCategoryRowLimitFailsWithoutTruncatingTheSnapshot() {
        assertThat(owner.update("""
                INSERT INTO presupuesto_items(presupuesto_id,descripcion,cantidad,precio_unitario,tipo_item,calidad)
                SELECT ?, 'Capacity fixture',1,1.00,'MANO_DE_OBRA',NULL
                  FROM generate_series(1,49999)
                """, own.presupuesto())).isEqualTo(49_999);
        assertThat(owner.queryForObject("SELECT count(*) FROM presupuesto_items WHERE presupuesto_id=?", Long.class,
                own.presupuesto())).isEqualTo(50_001L);
        assertFailure("CAPACITY_EXCEEDED", this::capture);
        assertThat(observed.rollbacks.get()).isEqualTo(1);
        assertThat(observed.commits.get()).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM presupuesto_items WHERE presupuesto_id=?", Long.class,
                own.presupuesto())).isEqualTo(50_001L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"equipo_cliente", "reparacion_equipo", "reparacion_tecnico",
            "reparacion_origen", "repuesto_reparacion", "repuesto_articulo",
            "presupuesto_reparacion", "cobro_reparacion", "cobro_anulador"})
    void rejectsEveryCrossWorkshopRelationAndRollsBackSnapshot(String relation) {
        switch (relation) {
            case "equipo_cliente" -> owner.update("UPDATE equipos SET cliente_id = ? WHERE id = ?", foreign.cliente(), own.equipo());
            case "reparacion_equipo" -> owner.update("UPDATE reparaciones SET equipo_id = ? WHERE id = ?", foreign.equipo(), own.reparacion());
            case "reparacion_tecnico" -> owner.update("UPDATE reparaciones SET tecnico_id = ? WHERE id = ?", foreign.employee(), own.reparacion());
            case "reparacion_origen" -> owner.update("UPDATE reparaciones SET reparacion_origen_id = ? WHERE id = ?", foreign.reparacion(), own.reparacion());
            case "repuesto_reparacion" -> owner.update("UPDATE repuestos SET reparacion_id = ? WHERE id = ?", foreign.reparacion(), own.repuesto());
            case "repuesto_articulo" -> owner.update("UPDATE repuestos SET articulo_id = ? WHERE id = ?", foreign.articulo(), own.repuesto());
            case "presupuesto_reparacion" -> owner.update("UPDATE presupuestos SET reparacion_id = ? WHERE id = ?", foreign.reparacion(), own.presupuesto());
            case "cobro_reparacion" -> owner.update("UPDATE cobros SET reparacion_id = ? WHERE id = ?", foreign.reparacion(), own.cobro());
            case "cobro_anulador" -> owner.update("UPDATE cobros SET anulado_por_id = ? WHERE id = ?", foreign.employee(), own.cobro());
            default -> throw new AssertionError(relation);
        }
        assertFailure("INCONSISTENT_RELATION", this::capture);
        assertThat(observed.rollbacks.get()).isEqualTo(1);
        assertThat(observed.commits.get()).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM clientes WHERE taller_id = ?", Long.class, own.taller())).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"employee", "inactive_actor", "inactive_workshop", "unverified_email",
            "wrong_version", "foreign_actor", "missing_actor"})
    void verifiesCurrentHolderAndAccountState(String denial) {
        long actor = own.admin();
        long expectedVersion = 7;
        switch (denial) {
            case "employee" -> {
                actor = own.employee();
                owner.update("UPDATE users SET active = TRUE WHERE id = ?", actor);
            }
            case "inactive_actor" -> owner.update("UPDATE users SET active = FALSE WHERE id = ?", actor);
            case "inactive_workshop" -> owner.update("UPDATE talleres SET activo = FALSE WHERE id = ?", own.taller());
            case "unverified_email" -> owner.update("UPDATE users SET email_verificado = FALSE WHERE id = ?", actor);
            case "wrong_version" -> expectedVersion = 6;
            case "foreign_actor" -> actor = foreign.admin();
            case "missing_actor" -> actor = own.taller() + 99;
            default -> throw new AssertionError(denial);
        }
        long selectedActor = actor;
        long selectedVersion = expectedVersion;
        assertFailure("ACCESS_DENIED", () -> service.capture(selectedActor, own.taller(), selectedVersion));
        assertThat(observed.commits.get()).isZero();
    }

    @Test
    void rejectsAnInvalidQrDigestInsteadOfReturningAPartialSnapshot() {
        owner.update("UPDATE taller_qr_cobro SET sha256 = ? WHERE taller_id = ?", "0".repeat(64), own.taller());
        assertFailure("INVALID_EVIDENCE", this::capture);
        assertThat(observed.rollbacks.get()).isEqualTo(1);
        assertThat(observed.commits.get()).isZero();
    }

    @Test
    void capturesOneSnapshotAcrossQueriesWhileAConcurrentTransactionChangesTwoCategories() throws Exception {
        ObservedDataSource barrier = new ObservedDataSource(dataSource, true);
        WorkshopExportSnapshotService concurrentService = service(barrier);
        var executor = Executors.newSingleThreadExecutor();
        var future = executor.submit(() -> concurrentService.capture(own.admin(), own.taller(), 7));
        try {
            assertThat(barrier.customersRead.await(10, TimeUnit.SECONDS)).isTrue();
            var writer = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            writer.executeWithoutResult(status -> {
                owner.update("UPDATE clientes SET nombre = ? WHERE id = ?", "CLIENT_AFTER_COMMIT", own.cliente());
                owner.update("UPDATE equipos SET modelo = ? WHERE id = ?", "MODEL_AFTER_COMMIT", own.equipo());
            });
            barrier.writerCommitted.countDown();
            ExportSnapshot first = future.get(10, TimeUnit.SECONDS);
            Map<String, JsonNode> firstCategories = categories(first);
            assertThat(firstCategories.get("clientes").get(0).path("nombre").asText())
                    .isEqualTo(own.marker() + " cliente\n=SUM(1,2) á");
            assertThat(firstCategories.get("equipos").get(0).path("modelo").asText())
                    .isEqualTo(own.marker() + " model_before");
            Map<String, JsonNode> nextCategories = categories(capture());
            assertThat(nextCategories.get("clientes").get(0).path("nombre").asText()).isEqualTo("CLIENT_AFTER_COMMIT");
            assertThat(nextCategories.get("equipos").get(0).path("modelo").asText()).isEqualTo("MODEL_AFTER_COMMIT");
            assertThat(barrier.commits.get()).isEqualTo(1);
            assertThat(barrier.rollbacks.get()).isZero();
        } finally {
            barrier.writerCommitted.countDown();
            future.cancel(true);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private ExportSnapshot capture() {
        return service.capture(own.admin(), own.taller(), 7);
    }

    private static WorkshopExportSnapshotService service(DataSource source) {
        return new WorkshopExportSnapshotService(new JdbcTemplate(source), new DataSourceTransactionManager(source));
    }

    private static void assertFailure(String code, Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(ExportPackageException.class,
                failure -> assertThat(failure.code().name()).isEqualTo(code));
    }

    private static Map<String, JsonNode> categories(ExportSnapshot snapshot) throws Exception {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (ExportFile file : snapshot.files()) {
            byte[] bytes = bytes(file);
            assertThat(file.size()).isEqualTo(bytes.length);
            assertThat(file.sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            if (file.path().startsWith("datos/") && file.path().endsWith(".json")) {
                assertThat(file.mediaType()).startsWith("application/json");
                JsonNode rows = JSON.readTree(bytes);
                assertThat(rows.isArray()).isTrue();
                assertThat(file.records()).isEqualTo(rows.size());
                String category = file.path().substring("datos/".length(), file.path().length() - ".json".length());
                assertThat(result.put(category, rows)).isNull();
            }
        }
        return result;
    }

    private static byte[] bytes(ExportFile file) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        file.writeTo(out);
        return out.toByteArray();
    }

    private static String allText(ExportSnapshot snapshot) throws Exception {
        StringBuilder text = new StringBuilder();
        for (ExportFile file : snapshot.files()) {
            if (file.mediaType().startsWith("application/json") || file.mediaType().startsWith("text/")) {
                text.append(new String(bytes(file), StandardCharsets.UTF_8));
            }
        }
        return text.toString();
    }

    private static Scope seed(long base, String marker) throws Exception {
        Scope s = new Scope(base, marker);
        owner.update("""
                INSERT INTO talleres(id,nombre,email_contacto,telefono,activo,secuencia_orden,
                    anio_secuencia_orden,alias_cobro,titular_cobro,entidad_cobro,mostrar_en_resumen,
                    created_at,updated_at)
                VALUES (?, ?, 'contact@example.invalid', '1112345678', TRUE,42,2026,
                    'alias.prueba','Titular prueba','Banco prueba',FALSE,
                    TIMESTAMP '2026-09-10 10:11:12.123456',TIMESTAMP '2026-09-11 11:12:13')
                """, s.taller(), marker + " workshop");
        owner.update("""
                INSERT INTO users(id,taller_id,username,password,email,role,active,email_verificado,token_version)
                VALUES (?, ?, ?, 'PASSWORD_CANARY', ?, 'ADMIN', TRUE,TRUE,7),
                       (?, ?, ?, 'PASSWORD_CANARY', ?, 'USER', FALSE,TRUE,7)
                """, s.admin(), s.taller(), marker + " admin", "admin" + base + "@example.invalid",
                s.employee(), s.taller(), marker + " former employee", "employee" + base + "@example.invalid");
        owner.update("""
                INSERT INTO clientes(id,taller_id,nombre,apellido,telefono,email,direccion)
                VALUES (?, ?, ?, 'Apellido', '1122334455', 'cliente@example.invalid',NULL)
                """, s.cliente(), s.taller(), marker + " cliente\n=SUM(1,2) á");
        owner.update("""
                INSERT INTO equipos(id,taller_id,cliente_id,marca,modelo,imei,color,descripcion)
                VALUES (?, ?, ?, ?, ?,NULL,'azul','Detalle')
                """, s.equipo(), s.taller(), s.cliente(), marker + " brand", marker + " model_before");
        owner.update("""
                INSERT INTO reparaciones(id,taller_id,equipo_id,descripcion_problema,estado,
                    precio_estimado,precio_final,fecha_ingreso,fecha_estimada_entrega,fecha_entrega,
                    codigo_seguimiento,numero_orden,tecnico_id,mojado,trabajo_en_placa,
                    no_testeable_al_ingreso,tiene_bloqueo_pantalla,tiene_cuenta_vinculada,
                    cliente_conoce_credenciales,pin_desbloqueo_cifrado,patron_desbloqueo_cifrado,
                    credenciales_cifrado_version,accesorios,condiciones_ingreso,observaciones,
                    fecha_conformidad_entrega,garantia_dias,garantia_inicio,garantia_fin,
                    garantia_condiciones,es_garantia,created_at,updated_at)
                VALUES (?, ?, ?, ?, 'ENTREGADO',12345678.90,NULL,DATE '2026-09-10',
                    DATE '2026-09-12',DATE '2026-09-11', ?, 'ORD-2026-0042', ?, TRUE,FALSE,
                    FALSE,TRUE,'NINGUNA',FALSE,'PIN_CANARY','PATTERN_CANARY',1,
                    'Cargador','Rayones','Observación',TIMESTAMP '2026-09-11 11:12:13',
                    30,DATE '2026-09-11',DATE '2026-10-11','Garantía de trabajo',FALSE,
                    TIMESTAMP '2026-09-10 10:11:12.123456',TIMESTAMP '2026-09-11 11:12:13')
                """, s.reparacion(), s.taller(), s.equipo(), marker + " repair", s.tracking(), s.employee());
        owner.update("""
                INSERT INTO articulos(id,taller_id,nombre,descripcion,sku,precio,costo,stock,stock_minimo,
                    activo,created_at,updated_at)
                VALUES (?, ?, ?,NULL,'SKU-01',123.45,NULL,7,2,FALSE,
                    TIMESTAMP '2026-09-10 10:11:12.123456',TIMESTAMP '2026-09-11 11:12:13')
                """, s.articulo(), s.taller(), marker + " article");
        owner.update("""
                INSERT INTO repuestos(id,taller_id,nombre,descripcion,precio,reparacion_id,articulo_id,cantidad)
                VALUES (?, ?, ?,NULL,123.45, ?, ?,2), (?, ?, ?,NULL,10.00,NULL,NULL,1)
                """, s.repuesto(), s.taller(), marker + " part", s.reparacion(), s.articulo(),
                s.unassignedPart(), s.taller(), marker + " unassigned");
        owner.update("""
                INSERT INTO presupuestos(id,taller_id,reparacion_id,estado,total,observaciones,
                    fecha_respuesta,created_at,updated_at,tipo,validez_dias,valido_hasta)
                VALUES (?, ?, ?, 'APROBADO',1234.56,NULL,TIMESTAMP '2026-09-11 11:12:13',
                    TIMESTAMP '2026-09-10 10:11:12.123456',TIMESTAMP '2026-09-11 11:12:13',
                    'ORIGINAL',7,TIMESTAMP '2026-09-17 10:11:12.123456')
                """, s.presupuesto(), s.taller(), s.reparacion());
        for (int copy = 0; copy < 2; copy++) {
            owner.update("""
                    INSERT INTO presupuesto_items(presupuesto_id,descripcion,cantidad,precio_unitario,tipo_item,calidad)
                    VALUES (?, ?,2,12.34,'MANO_DE_OBRA',NULL)
                    """, s.presupuesto(), marker + " repeated item");
        }
        owner.update("""
                INSERT INTO cobros(id,taller_id,reparacion_id,monto,metodo,observaciones,created_at,
                    referencia,anulado_at,anulado_por_id,motivo_anulacion)
                VALUES (?, ?, ?,100.00,'TRANSFERENCIA',NULL,TIMESTAMP '2026-09-10 10:11:12.123456',
                    'Registro externo',TIMESTAMP '2026-09-11 11:12:13', ?,'Carga duplicada')
                """, s.cobro(), s.taller(), s.reparacion(), s.employee());
        owner.update("""
                INSERT INTO suscripciones(id,taller_id,plan,estado,fecha_inicio,fecha_fin_trial,proximo_cobro,
                    consumo_mes,reparaciones_mes,mp_preapproval_id,mp_payer_id,mp_status,mp_external_reference,
                    mp_checkout_init_point,mp_next_payment_at,mp_last_payment_at,mp_last_authorized_payment_id,
                    created_at,updated_at)
                VALUES (?, ?, 'PRO','ACTIVA',DATE '2026-09-10',NULL,DATE '2026-10-10',
                    '2026-09',12, ?,'PAYER_CANARY','authorized', ?,
                    'https://example.invalid/CHECKOUT_CANARY',TIMESTAMPTZ '2026-10-10 09:23:34-03',
                    TIMESTAMPTZ '2026-09-11 09:23:34-03','CAUSAL_CANARY',
                    TIMESTAMP '2026-09-10 10:11:12.123456',TIMESTAMP '2026-09-11 11:12:13')
                """, s.suscripcion(), s.taller(), marker + "_commercial_subscription", "CORRELATION_CANARY_" + base);
        owner.update("""
                INSERT INTO subscription_payments(id,suscripcion_id,provider,external_authorized_payment_id,
                    external_payment_id,amount,currency,invoice_status,payment_status,status_detail,
                    summarized,retry_attempt,debit_at,provider_created_at,provider_modified_at,created_at,updated_at)
                VALUES (?, ?, 'MERCADO_PAGO', ?, ?,12345678901234567.89,'ARS','processed','charged_back',
                    'reimbursed','SUMMARY_CANARY',9,TIMESTAMPTZ '2026-09-11 09:23:34-03',
                    TIMESTAMPTZ '2026-09-11 08:23:34-03',TIMESTAMPTZ '2026-09-11 09:23:34-03',
                    TIMESTAMPTZ '2026-09-11 09:23:34-03',TIMESTAMPTZ '2026-09-11 09:23:34-03')
                """, s.payment(), s.suscripcion(), marker + "_commercial_authorized", marker + "_commercial_payment");
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xff123456);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, "png", output)).isTrue();
        byte[] png = output.toByteArray();
        owner.update("INSERT INTO taller_qr_cobro(taller_id,png,sha256) VALUES (?, ?, ?)",
                s.taller(), png, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png)));
        return s;
    }

    private record Scope(long base, String marker) {
        long taller() { return base; }
        String tracking() { return "TRACK_" + (base - 9_007_199_254_740_000L); }
        long admin() { return base + 1; }
        long employee() { return base + 2; }
        long cliente() { return base + 3; }
        long equipo() { return base + 4; }
        long reparacion() { return base + 5; }
        long articulo() { return base + 6; }
        long repuesto() { return base + 7; }
        long presupuesto() { return base + 8; }
        long cobro() { return base + 9; }
        long suscripcion() { return base + 10; }
        long payment() { return base + 11; }
        long unassignedPart() { return base + 12; }
    }

    /** Observe transaction completion and pause after the client result set has been exhausted. */
    private static final class ObservedDataSource extends AbstractDataSource {
        private final DataSource delegate;
        private final boolean pauseAfterCustomers;
        private final AtomicBoolean paused = new AtomicBoolean();
        private final AtomicInteger commits = new AtomicInteger();
        private final AtomicInteger rollbacks = new AtomicInteger();
        private final CountDownLatch customersRead = new CountDownLatch(1);
        private final CountDownLatch writerCommitted = new CountDownLatch(1);

        private ObservedDataSource(DataSource delegate, boolean pauseAfterCustomers) {
            this.delegate = delegate;
            this.pauseAfterCustomers = pauseAfterCustomers;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return connection(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return connection(delegate.getConnection(username, password));
        }

        private Connection connection(Connection target) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("commit")) commits.incrementAndGet();
                        if (method.getName().equals("rollback")) rollbacks.incrementAndGet();
                        Object value = invoke(target, method, args);
                        if (method.getName().equals("prepareStatement") && value instanceof PreparedStatement statement
                                && args != null && args[0] instanceof String sql && sql.contains("FROM public.clientes c")) {
                            return statement(statement);
                        }
                        return value;
                    });
        }

        private PreparedStatement statement(PreparedStatement target) {
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                        Object value = invoke(target, method, args);
                        return value instanceof ResultSet rows ? rows(rows) : value;
                    });
        }

        private ResultSet rows(ResultSet target) {
            return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                    (proxy, method, args) -> {
                        Object value = invoke(target, method, args);
                        if (pauseAfterCustomers && method.getName().equals("next") && Boolean.FALSE.equals(value)
                                && paused.compareAndSet(false, true)) {
                            customersRead.countDown();
                            if (!writerCommitted.await(10, TimeUnit.SECONDS)) {
                                throw new SQLException("Concurrent writer did not complete within the test deadline");
                            }
                        }
                        return value;
                    });
        }

        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }
    }
}
