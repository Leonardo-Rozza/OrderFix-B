package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real V32 -> V33 backfill and ordinary cascades. All operations retain every database trigger. */
@Testcontainers
class WorkshopClosureCascadeIT {
    @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("closure_cascades_synthetic").withUsername("closure_owner").withPassword("fixture-owner-only");
    private static JdbcTemplate jdbc;
    private static Graph legacy;

    @BeforeAll static void database() {
        var ownerSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        var owner = new JdbcTemplate(ownerSource);
        Flyway.configure().dataSource(ownerSource).locations("classpath:db/migration").target("32").load().migrate();
        legacy = graph(owner);
        children(owner, legacy);
        Flyway.configure().dataSource(ownerSource).locations("classpath:db/migration").target("33").load().migrate();

        // Exercise automatic SECURITY DEFINER guards without granting function execution or owner powers.
        owner.execute("CREATE ROLE closure_writer LOGIN PASSWORD 'fixture-writer-only' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT");
        owner.execute("GRANT USAGE ON SCHEMA public TO closure_writer");
        owner.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO closure_writer");
        owner.execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO closure_writer");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), "closure_writer", "fixture-writer-only"));
    }

    @BeforeEach void guardsRemainActiveForTheNonOwnerWriter() {
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("closure_writer");
        assertThat(jdbc.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname=current_user", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
                JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='public' AND t.tgname='aa_cuenta_guard_v33' AND t.tgenabled='O'
                  AND c.relname IN ('users','auth_tokens','reparaciones','reparacion_fotos',
                                    'presupuestos','presupuesto_items','talleres','taller_qr_cobro')
                """, Long.class)).isEqualTo(8);
    }

    @Test void migrationBackfillsTheThreeLegacyOwnershipColumnsAndPreservesPayloads() {
        assertThat(jdbc.queryForObject("SELECT taller_id FROM auth_tokens WHERE user_id=?", Long.class, legacy.user())).isEqualTo(legacy.workshop());
        assertThat(jdbc.queryForObject("SELECT taller_id FROM reparacion_fotos WHERE reparacion_id=?", Long.class, legacy.repair())).isEqualTo(legacy.workshop());
        assertThat(jdbc.queryForObject("SELECT taller_id FROM presupuesto_items WHERE presupuesto_id=?", Long.class, legacy.budget())).isEqualTo(legacy.workshop());
        assertThat(jdbc.queryForObject("SELECT tipo FROM auth_tokens WHERE user_id=?", String.class, legacy.user())).isEqualTo("RESET_PASSWORD");
        assertThat(jdbc.queryForObject("SELECT url FROM reparacion_fotos WHERE reparacion_id=?", String.class, legacy.repair())).isEqualTo("https://synthetic.invalid/legacy.jpg");
        assertThat(jdbc.queryForObject("SELECT descripcion FROM presupuesto_items WHERE presupuesto_id=?", String.class, legacy.budget())).isEqualTo("Mano de obra fixture");
    }

    @Test void deletingAUserCascadesItsEmailTokenUsingPersistedOwnershipAfterTheUserDisappears() {
        Graph own = graph(jdbc), other = graph(jdbc);
        children(jdbc, own); children(jdbc, other);
        assertThat(jdbc.queryForObject("SELECT taller_id FROM auth_tokens WHERE user_id=?", Long.class, own.user())).isEqualTo(own.workshop());
        assertThat(jdbc.update("DELETE FROM users WHERE id=?", own.user())).isEqualTo(1);
        assertThat(count("auth_tokens", "user_id", own.user())).isZero();
        assertThat(count("users", "id", own.user())).isZero();
        assertThat(count("auth_tokens", "user_id", other.user())).isEqualTo(1);
        assertThat(count("users", "id", other.user())).isEqualTo(1);
        assertThat(count("reparaciones", "id", own.repair())).isEqualTo(1);
    }

    @Test void deletingARepairRetainsTheLegacyPhotoCascadeAndTheBudgetItemCascade() {
        Graph own = graph(jdbc), other = graph(jdbc);
        children(jdbc, own); children(jdbc, other);
        assertThat(jdbc.update("DELETE FROM reparaciones WHERE id=?", own.repair())).isEqualTo(1);
        assertThat(count("reparacion_fotos", "reparacion_id", own.repair())).isZero();
        assertThat(count("presupuestos", "id", own.budget())).isZero();
        assertThat(count("presupuesto_items", "presupuesto_id", own.budget())).isZero();
        assertThat(count("reparacion_fotos", "reparacion_id", other.repair())).isEqualTo(1);
        assertThat(count("presupuesto_items", "presupuesto_id", other.budget())).isEqualTo(1);
        assertThat(count("equipos", "id", own.equipment())).isEqualTo(1);
    }

    @Test void deletingABudgetCascadesItsItemsWithoutDeletingTheRepairOrForeignItems() {
        Graph own = graph(jdbc), other = graph(jdbc);
        children(jdbc, own); children(jdbc, other);
        assertThat(jdbc.update("DELETE FROM presupuestos WHERE id=?", own.budget())).isEqualTo(1);
        assertThat(count("presupuesto_items", "presupuesto_id", own.budget())).isZero();
        assertThat(count("presupuesto_items", "presupuesto_id", other.budget())).isEqualTo(1);
        assertThat(count("reparaciones", "id", own.repair())).isEqualTo(1);
        assertThat(count("reparacion_fotos", "reparacion_id", own.repair())).isEqualTo(1);
    }

    @Test void deletingAnOpenWorkshopDeletesItsQrWhileTheAnchorIsStillVisible() {
        long own = workshop(jdbc), other = workshop(jdbc);
        for (long id : List.of(own, other)) {
            jdbc.update("INSERT INTO taller_qr_cobro(taller_id,png,sha256) VALUES(?,decode('0102','hex'),repeat('a',64))", id);
        }
        assertThat(jdbc.update("DELETE FROM talleres WHERE id=?", own)).isEqualTo(1);
        assertThat(count("talleres", "id", own)).isZero();
        assertThat(count("taller_qr_cobro", "taller_id", own)).isZero();
        assertThat(count("talleres", "id", other)).isEqualTo(1);
        assertThat(count("taller_qr_cobro", "taller_id", other)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"auth_tokens", "reparacion_fotos", "presupuesto_items"})
    void clientsCannotRemapTheOwnershipThatMakesCascadingDeletesSafe(String table) {
        Graph own = graph(jdbc), other = graph(jdbc);
        children(jdbc, own); children(jdbc, other);
        String parentColumn = switch (table) {
            case "auth_tokens" -> "user_id";
            case "reparacion_fotos" -> "reparacion_id";
            default -> "presupuesto_id";
        };
        long parent = switch (table) {
            case "auth_tokens" -> own.user();
            case "reparacion_fotos" -> own.repair();
            default -> own.budget();
        };
        String before = jdbc.queryForObject("SELECT to_jsonb(r)::text FROM " + table + " r WHERE " + parentColumn + "=?", String.class, parent);
        assertThatThrownBy(() -> jdbc.update("UPDATE " + table + " SET taller_id=? WHERE " + parentColumn + "=?", other.workshop(), parent))
                .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("P0033"));
        assertThat(jdbc.queryForObject("SELECT to_jsonb(r)::text FROM " + table + " r WHERE " + parentColumn + "=?", String.class, parent)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT taller_id FROM " + table + " WHERE " + parentColumn + "=?", Long.class, parent)).isEqualTo(own.workshop());
    }

    private static Graph graph(JdbcTemplate db) {
        long workshop = workshop(db);
        String mark = UUID.randomUUID().toString();
        long user = db.queryForObject("""
                INSERT INTO users(username,email,password,role,taller_id,active,email_verificado,token_version)
                VALUES(?,?,'fixture-password-hash','ADMIN',?,true,true,0) RETURNING id
                """, Long.class, "Fixture-" + mark, mark + "@synthetic.invalid", workshop);
        long customer = db.queryForObject("""
                INSERT INTO clientes(nombre,apellido,telefono,taller_id) VALUES('Fixture','Cascade','555-0100',?) RETURNING id
                """, Long.class, workshop);
        long equipment = db.queryForObject("""
                INSERT INTO equipos(marca,modelo,cliente_id,taller_id) VALUES('Fixture','Cascade',?,?) RETURNING id
                """, Long.class, customer, workshop);
        long repair = db.queryForObject("""
                INSERT INTO reparaciones(descripcion_problema,estado,equipo_id,taller_id)
                VALUES('Fixture cascade','INGRESADA',?,?) RETURNING id
                """, Long.class, equipment, workshop);
        long budget = db.queryForObject("""
                INSERT INTO presupuestos(reparacion_id,taller_id,estado,total) VALUES(?,?,'BORRADOR',10) RETURNING id
                """, Long.class, repair, workshop);
        return new Graph(workshop, user, equipment, repair, budget);
    }

    private static long workshop(JdbcTemplate db) {
        return db.queryForObject("INSERT INTO talleres(nombre) VALUES(?) RETURNING id", Long.class, "Cascade-" + UUID.randomUUID());
    }

    /** Omits the three new columns exactly as the existing JPA mappings do. */
    private static void children(JdbcTemplate db, Graph graph) {
        db.update("""
                INSERT INTO auth_tokens(user_id,tipo,token_hash,expira_en)
                VALUES(?,'RESET_PASSWORD',?,CURRENT_TIMESTAMP+INTERVAL '1 hour')
                """, graph.user(), UUID.randomUUID().toString().replace("-", "").repeat(2));
        db.update("INSERT INTO reparacion_fotos(reparacion_id,url) VALUES(?,'https://synthetic.invalid/legacy.jpg')", graph.repair());
        db.update("""
                INSERT INTO presupuesto_items(presupuesto_id,descripcion,cantidad,precio_unitario)
                VALUES(?,'Mano de obra fixture',1,10)
                """, graph.budget());
    }

    private static long count(String table, String column, long id) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + "=?", Long.class, id);
    }
    private static String sqlState(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql) return sql.getSQLState();
        }
        return null;
    }
    private record Graph(long workshop, long user, long equipment, long repair, long budget) { }
}
