package com.leonardorozza.mvgrreparacionesbackend.cuenta.export.http;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantContext;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.export.ExportPackageException;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.reauth.ExportReauthenticationService;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.UserRepository;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.ExportService;
import com.leonardorozza.mvgrreparacionesbackend.utils.jwt.JwtUtils;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.leonardorozza.mvgrreparacionesbackend.cuenta.reauth.ExportReauthenticationPurpose.DESCARGAR_EXPORTACION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/** PostgreSQL 16, real JWT/BCrypt/grant transactions and the unchanged POI/JPA workbook implementation. */
@SpringBootTest(properties = {
        "exports.jobs.enabled=false", "spring.config.import=", "spring.config.additional-location=",
        "spring.config.location=optional:classpath:/application.properties",
        "mail.enabled=false", "mercadopago.enabled=false", "mercadopago.checkout-enabled=false",
        "photos.private.enabled=false", "ordenfix.legal.registration-consent.enabled=false",
        "ordenfix.legal.registration-enforcement.enabled=false", "ordenfix.legal.account-read.enabled=false",
        "ordenfix.legal.account-acceptance.enabled=false", "ordenfix.legal.public-documents.enabled=false",
        "ordenfix.legal.public-requirements.enabled=false", "ordenfix.legal.aggregate-context.enabled=false",
        "ordenfix.legal.editorial-context.enabled=false", "ordenfix.legal.import-context.enabled=false",
        "ordenfix.legal.dry-run-context.enabled=false", "ordenfix.legal.public-document-read-context.enabled=false",
        "ordenfix.legal.public-requirements-context.enabled=false"
})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProtectedExcelExportIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_protected_excel").withUsername("ordenfix").withPassword("ordenfix");
    private static final String PASSWORD = "protected-excel-synthetic-password";
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired ExportReauthenticationService reauth;
    @Autowired ExportService actualExcel;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtUtils jwt;
    private ProtectedExcelExportService service;
    private final AtomicInteger generations = new AtomicInteger();
    private Runnable beforeGeneration;
    private Actor own;
    private String access;

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @BeforeEach void prepare() {
        assertThat(manager).isInstanceOf(JpaTransactionManager.class);
        own = actor();
        access = token(own);
        beforeGeneration = () -> { };
        generations.set(0);
        // The observation point is after the real preflight and before real JPA/POI.
        // No query/repository or reauthentication collaborator is stubbed.
        ExportService observed = mock(ExportService.class);
        doAnswer(invocation -> {
            generations.incrementAndGet();
            beforeGeneration.run();
            return actualExcel.exportarExcel();
        }).when(observed).exportarExcel();
        service = new ProtectedExcelExportService(reauth, observed, jdbc, manager);
    }

    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test void realPreflightAcceptsEveryMappedCategoryAndPreservesFormatCalculationsAndTenantIsolation() throws Exception {
        Actor other = actor();
        String proof = grant(access);
        byte[] ownBytes = download(own, access, proof);
        used(proof, true);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(ownBytes))) {
            assertThat(sheetNames(workbook)).containsExactly("Clientes", "Órdenes", "Cobros", "Presupuestos");
            assertThat(workbook.getSheet("Clientes").getLastRowNum()).isEqualTo(1);
            assertThat(workbook.getSheet("Clientes").getRow(1).getCell(0).getNumericCellValue()).isEqualTo(own.customerId());
            assertThat(workbook.getSheet("Órdenes").getRow(1).getCell(9).getNumericCellValue()).isEqualTo(120);
            assertThat(workbook.getSheet("Órdenes").getRow(1).getCell(10).getNumericCellValue()).isEqualTo(25);
            assertThat(workbook.getSheet("Órdenes").getRow(1).getCell(11).getNumericCellValue()).isEqualTo(95);
            assertThat(workbook.getSheet("Cobros").getLastRowNum()).isEqualTo(2);
            assertThat(workbook.getSheet("Presupuestos").getLastRowNum()).isEqualTo(1);
            assertThat(text(workbook)).contains(own.marker(), "Cobro activo", "Anulado sintético", "ANULADO");
            assertThat(text(workbook)).doesNotContain(other.marker(), PASSWORD, access, proof);
            assertThat(workbook.getProperties().getCoreProperties().getDescription()).contains("Reporte operativo", "sin validez fiscal");
        }
        String otherAccess = token(other), otherProof = grant(otherAccess);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(download(other, otherAccess, otherProof)))) {
            assertThat(text(workbook)).contains(other.marker()).doesNotContain(own.marker());
            assertThat(workbook.getSheet("Clientes").getRow(1).getCell(0).getNumericCellValue()).isEqualTo(other.customerId());
        }
        used(otherProof, true);
        assertThat(generations.get()).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(strings = {"clientes", "presupuesto_items"})
    void aggregateRowBudgetRejectsTopLevelAndRelatedRowsBeforePoiAndKeepsProofUsable(String table) throws Exception {
        if (table.equals("clientes")) {
            jdbc.update("""
                    INSERT INTO clientes(nombre,apellido,telefono,taller_id)
                    SELECT 'Capacity fixture','Client','cap-' || n, ? FROM generate_series(1,50000) n
                    """, own.workshopId());
        } else {
            jdbc.update("""
                    INSERT INTO presupuesto_items(presupuesto_id,descripcion,cantidad,precio_unitario,tipo_item)
                    SELECT ?, 'Capacity item', 1, 1, 'MANO_DE_OBRA' FROM generate_series(1,50000)
                    """, own.budgetId());
        }
        String proof = grant(access);

        rejected(proof, ExportPackageException.Code.CAPACITY_EXCEEDED);
        assertThat(generations.get()).isZero();
        used(proof, false);

        if (table.equals("clientes")) jdbc.update("DELETE FROM clientes WHERE taller_id=? AND nombre='Capacity fixture'", own.workshopId());
        else jdbc.update("DELETE FROM presupuesto_items WHERE presupuesto_id=? AND descripcion='Capacity item'", own.budgetId());
        assertThat(download(own, access, proof)).isNotEmpty();
        used(proof, true);
        assertThat(generations.get()).isEqualTo(1);
    }

    @Test void toastedCompressedContentIsMeasuredBeforeJpaHydrationAndPoi() throws Exception {
        // Synthetic ciphertext-shaped data is deliberately oversized; no reader decrypts it.
        jdbc.update("UPDATE reparaciones SET pin_desbloqueo_cifrado=repeat('X',17*1024*1024) WHERE id=?", own.repairId());
        assertThat(jdbc.queryForObject("""
                SELECT pg_column_size(pin_desbloqueo_cifrado) < octet_length(pin_desbloqueo_cifrado)
                  FROM reparaciones WHERE id=?
                """, Boolean.class, own.repairId())).isTrue();
        String proof = grant(access);

        rejected(proof, ExportPackageException.Code.CAPACITY_EXCEEDED);
        assertThat(generations.get()).isZero();
        used(proof, false);

        jdbc.update("UPDATE reparaciones SET pin_desbloqueo_cifrado=NULL WHERE id=?", own.repairId());
        assertThat(download(own, access, proof)).isNotEmpty();
        used(proof, true);
    }

    @ParameterizedTest @ValueSource(strings = {"cobros", "repuestos"})
    void reverseForeignReferencesCannotEnterUnscopedLegacyTotals(String table) throws Exception {
        Actor other = actor();
        long corruptedId = table.equals("cobros")
                ? jdbc.queryForObject("""
                    INSERT INTO cobros(reparacion_id,taller_id,monto,metodo,observaciones)
                    VALUES (?, ?, 777, 'EFECTIVO', 'Foreign reverse fixture') RETURNING id
                    """, Long.class, own.repairId(), other.workshopId())
                : jdbc.queryForObject("""
                    INSERT INTO repuestos(nombre,descripcion,precio,cantidad,reparacion_id,taller_id)
                    VALUES ('Foreign reverse fixture','Foreign',777,1,?,?) RETURNING id
                    """, Long.class, own.repairId(), other.workshopId());
        String proof = grant(access);

        rejected(proof, ExportPackageException.Code.INCONSISTENT_RELATION);
        assertThat(generations.get()).isZero();
        used(proof, false);

        jdbc.update(table.equals("cobros") ? "DELETE FROM cobros WHERE id=?" : "DELETE FROM repuestos WHERE id=?", corruptedId);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(download(own, access, proof)))) {
            assertThat(workbook.getSheet("Órdenes").getRow(1).getCell(9).getNumericCellValue()).isEqualTo(120);
            assertThat(workbook.getSheet("Órdenes").getRow(1).getCell(10).getNumericCellValue()).isEqualTo(25);
            assertThat(text(workbook)).doesNotContain("Foreign reverse fixture", other.marker());
        }
        used(proof, true);
    }

    @Test void insertCommittedAfterPreflightDoesNotExpandTheWorkbookSnapshot() throws Exception {
        String proof = grant(access);
        beforeGeneration = () -> {
            assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("repeatable read");
            int snapshotPid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
            try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
                assertThat(connection.getAutoCommit()).isTrue();
                try (var query = connection.createStatement(); var result = query.executeQuery("SELECT pg_backend_pid()")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isNotEqualTo(snapshotPid);
                }
                try (var insert = connection.prepareStatement("""
                        INSERT INTO clientes(nombre,apellido,telefono,taller_id)
                        VALUES ('Committed after preflight','Client','late-snapshot',?)
                        """)) {
                    insert.setQueryTimeout(10);
                    insert.setLong(1, own.workshopId());
                    assertThat(insert.executeUpdate()).isEqualTo(1);
                }
            } catch (java.sql.SQLException failure) { throw new AssertionError(failure); }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM clientes WHERE taller_id=?", Long.class, own.workshopId())).isEqualTo(1);
        };

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(download(own, access, proof)))) {
            assertThat(workbook.getSheet("Clientes").getLastRowNum()).isEqualTo(1);
            assertThat(text(workbook)).contains(own.marker()).doesNotContain("Committed after preflight");
        }
        used(proof, true);
        assertThat(generations.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM clientes WHERE taller_id=?", Long.class, own.workshopId())).isEqualTo(2);
    }

    @Test void workbookIoFailureRollsBackTheRealProofConsumption() throws Exception {
        String proof = grant(access);
        beforeGeneration = () -> {
            // Observe the uncommitted consumption in the actual transaction before simulating POI I/O failure.
            used(proof, true);
            throw new UncheckedIOException(new IOException("SYNTHETIC_POI_PRIVATE_DIAGNOSTIC"));
        };

        rejected(proof, ExportPackageException.Code.UNAVAILABLE);
        assertThat(generations.get()).isEqualTo(1);
        used(proof, false);

        beforeGeneration = () -> { };
        assertThat(download(own, access, proof)).isNotEmpty();
        used(proof, true);
        assertThat(generations.get()).isEqualTo(2);
    }

    private Actor actor() {
        String marker = "Excel-" + UUID.randomUUID().toString().substring(0, 8);
        long workshop = jdbc.queryForObject("INSERT INTO talleres(nombre) VALUES(?) RETURNING id", Long.class, marker);
        long user = jdbc.queryForObject("""
                INSERT INTO users(username,email,password,role,taller_id,active,email_verificado,token_version)
                VALUES (?, ?, ?, 'ADMIN', ?, true, true, 0) RETURNING id
                """, Long.class, marker, marker + "@synthetic.invalid", encoder.encode(PASSWORD), workshop);
        jdbc.update("INSERT INTO suscripciones(taller_id,plan,estado,fecha_inicio) VALUES(?,'FREE','TRIAL',CURRENT_DATE)", workshop);
        long customer = jdbc.queryForObject("INSERT INTO clientes(nombre,apellido,telefono,taller_id) VALUES(?,'Client','own-phone',?) RETURNING id", Long.class, marker, workshop);
        long equipment = jdbc.queryForObject("INSERT INTO equipos(marca,modelo,cliente_id,taller_id) VALUES('Fixture','Phone',?,?) RETURNING id", Long.class, customer, workshop);
        long repair = jdbc.queryForObject("""
                INSERT INTO reparaciones(descripcion_problema,estado,precio_final,fecha_ingreso,equipo_id,taller_id,tecnico_id,credenciales_cifrado_version)
                VALUES ('Trabajo','INGRESADO',100,CURRENT_DATE,?,?,?,1) RETURNING id
                """, Long.class, equipment, workshop, user);
        jdbc.update("INSERT INTO repuestos(nombre,descripcion,precio,cantidad,reparacion_id,taller_id) VALUES('Pieza','Fixture',10,2,?,?)", repair, workshop);
        jdbc.update("INSERT INTO cobros(reparacion_id,taller_id,monto,metodo,observaciones,created_at) VALUES(?,?,25,'EFECTIVO','Cobro activo',CURRENT_TIMESTAMP)", repair, workshop);
        jdbc.update("""
                INSERT INTO cobros(reparacion_id,taller_id,monto,metodo,observaciones,created_at,anulado_at,anulado_por_id,motivo_anulacion)
                VALUES(?,?,30,'TRANSFERENCIA','Anulado sintético',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?,'Corrección')
                """, repair, workshop, user);
        long budget = jdbc.queryForObject("""
                INSERT INTO presupuestos(reparacion_id,taller_id,estado,tipo,total,created_at)
                VALUES(?,?,'PENDIENTE','ORIGINAL',120,CURRENT_TIMESTAMP) RETURNING id
                """, Long.class, repair, workshop);
        jdbc.update("INSERT INTO presupuesto_items(presupuesto_id,descripcion,cantidad,precio_unitario,tipo_item) VALUES(?,'Trabajo',1,120,'MANO_DE_OBRA')", budget);
        return new Actor(user, workshop, customer, repair, budget, marker);
    }

    private String token(Actor actor) {
        var user = users.findSessionByIdAndTallerId(actor.userId(), actor.workshopId()).orElseThrow();
        return jwt.generateToken(new AuthenticatedUserPrincipal(user), actor.workshopId());
    }

    private String grant(String accessToken) { return reauth.issue(accessToken, PASSWORD, DESCARGAR_EXPORTACION).token(); }

    private byte[] download(Actor actor, String accessToken, String proof) {
        TenantContext.setTallerId(actor.workshopId());
        try { return service.download(accessToken, proof); }
        finally { TenantContext.clear(); }
    }

    private void rejected(String proof, ExportPackageException.Code code) {
        assertThatThrownBy(() -> download(own, access, proof)).isInstanceOfSatisfying(ExportPackageException.class, failure -> {
            assertThat(failure.code()).isEqualTo(code);
            assertThat(failure.getCause()).isNull();
            assertThat(failure.getSuppressed()).isEmpty();
            assertThat(failure.getMessage()).doesNotContain(PASSWORD, access, proof, "SYNTHETIC_POI_PRIVATE_DIAGNOSTIC");
        });
    }

    private void used(String proof, boolean expected) {
        assertThat(jdbc.queryForObject("SELECT usada_en IS NOT NULL FROM cuenta_reautenticaciones WHERE token_hash=?", Boolean.class, hash(proof))).isEqualTo(expected);
    }

    private static List<String> sheetNames(XSSFWorkbook workbook) {
        return java.util.stream.IntStream.range(0, workbook.getNumberOfSheets()).mapToObj(workbook::getSheetName).toList();
    }

    private static String text(XSSFWorkbook workbook) {
        StringBuilder text = new StringBuilder();
        workbook.forEach(sheet -> sheet.forEach(row -> row.forEach(cell -> {
            if (cell.getCellType() == CellType.STRING) text.append(cell.getStringCellValue()).append('\n');
        })));
        return text.toString();
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new AssertionError(failure); }
    }

    private record Actor(long userId, long workshopId, long customerId, long repairId, long budgetId, String marker) { }
}
