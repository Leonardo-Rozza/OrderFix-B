package com.leonardorozza.mvgrreparacionesbackend;

import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosureEffects;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosureGate;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.closure.WorkshopClosureStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** Real 35→36 upgrade and new writes on disposable PG16. No provider, guard bypass or inferred category. */
@Testcontainers
class EquipmentTypeMigrationIT {
    @Container static final PostgreSQLContainer PG=new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_equipment_type").withUsername("owner").withPassword("synthetic-equipment");
    private static JdbcTemplate current;
    private static long workshop,client;

    @BeforeAll static void migrateCurrent() {
        var source=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("36").load().migrate();
        current=new JdbcTemplate(source);workshop=workshop(current);client=client(current,workshop);
    }

    @Test void upgradePreservesOpenAndRestrictedEquipmentWithoutRowUpdatesOrCategoryInference() {
        current.execute("CREATE DATABASE ordenfix_equipment_before_v36");
        DataSource previous=new DriverManagerDataSource(PG.getJdbcUrl().replace("/"+PG.getDatabaseName(),"/ordenfix_equipment_before_v36"),PG.getUsername(),PG.getPassword());
        var jdbc=new JdbcTemplate(previous);
        Flyway.configure().dataSource(previous).locations("classpath:db/migration").target("35").load().migrate();
        long open=workshop(jdbc),closed=workshop(jdbc);
        long ownClient=client(jdbc,open),closedClient=client(jdbc,closed);
        jdbc.update("INSERT INTO equipos(marca,modelo,imei,cliente_id,taller_id) VALUES('Apple','iPhone','synthetic-serial',?,?)",ownClient,open);
        jdbc.update("INSERT INTO equipos(marca,modelo,imei,cliente_id,taller_id) VALUES('Samsung','Monitor',NULL,?,?)",closedClient,closed);
        restrict(jdbc,previous,closed);
        var before=equipmentRows(jdbc);
        var workshopBefore=jdbc.queryForList("SELECT to_jsonb(t)::text FROM talleres t ORDER BY id",String.class);
        var guardsBefore=jdbc.queryForList("SELECT tgname||':'||tgenabled::text FROM pg_trigger WHERE tgrelid='equipos'::regclass ORDER BY tgname",String.class);
        assertThat(jdbc.queryForObject("SHOW session_replication_role",String.class)).isEqualTo("origin");
        assertThat(jdbc.queryForObject("SELECT cierre_estado FROM talleres WHERE id=?",String.class,closed)).isEqualTo("RESTRINGIDO");
        assertThatThrownBy(()->jdbc.update("UPDATE equipos SET modelo='prohibited' WHERE taller_id=?",closed)).isInstanceOf(DataAccessException.class);

        var flyway=Flyway.configure().dataSource(previous).locations("classpath:db/migration").target("36").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(equipmentRows(jdbc)).as("every previous value, xmin and ctid remains unchanged").isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT tipo FROM equipos ORDER BY id",String.class)).containsExactly("OTRO","OTRO");
        assertThat(jdbc.queryForList("SELECT to_jsonb(t)::text FROM talleres t ORDER BY id",String.class)).isEqualTo(workshopBefore);
        assertThat(jdbc.queryForList("SELECT tgname||':'||tgenabled::text FROM pg_trigger WHERE tgrelid='equipos'::regclass ORDER BY tgname",String.class)).isEqualTo(guardsBefore);
        assertThat(jdbc.queryForObject("SHOW session_replication_role",String.class)).isEqualTo("origin");
        assertThatThrownBy(()->jdbc.update("UPDATE equipos SET tipo='TV' WHERE taller_id=?",closed)).isInstanceOf(DataAccessException.class);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"CELULAR","NOTEBOOK","CONSOLA","PC_ESCRITORIO","MONITOR","TV","OTRO"})
    void databaseAcceptsEveryExplicitCategory(String category) {
        long id=current.queryForObject("INSERT INTO equipos(marca,modelo,cliente_id,taller_id,tipo) VALUES('Synthetic','Equipment',?,?,?) RETURNING id",Long.class,client,workshop,category);
        assertThat(current.queryForObject("SELECT tipo FROM equipos WHERE id=?",String.class,id)).isEqualTo(category);
    }
    @ParameterizedTest @NullSource @ValueSource(strings={"","celular","TABLET","TV "})
    void databaseRejectsUnknownOrNullCategories(String category) {
        Throwable failure=catchThrowable(()->current.update("INSERT INTO equipos(marca,modelo,cliente_id,taller_id,tipo) VALUES('Synthetic','Invalid',?,?,?)",client,workshop,category));
        assertThat(failure).isInstanceOf(DataAccessException.class);
        var sql=(PSQLException)((DataAccessException)failure).getMostSpecificCause();
        assertThat(sql.getSQLState()).isEqualTo(category==null?"23502":"23514");
        if(category!=null)assertThat(sql.getServerErrorMessage().getConstraint()).isEqualTo("ck_equipos_tipo");
    }
    @Test void omittedCategoryUsesOtherWhileAnOldUpdateKeepsTheExistingCategoryAndIdentifier() {
        long id=current.queryForObject("INSERT INTO equipos(marca,modelo,imei,cliente_id,taller_id) VALUES('Synthetic','Legacy','series-A',?,?) RETURNING id",Long.class,client,workshop);
        assertThat(current.queryForObject("SELECT tipo FROM equipos WHERE id=?",String.class,id)).isEqualTo("OTRO");
        current.update("UPDATE equipos SET tipo='NOTEBOOK' WHERE id=?",id);
        current.update("UPDATE equipos SET modelo='Updated' WHERE id=?",id);
        assertThat(current.queryForMap("SELECT tipo,imei,modelo FROM equipos WHERE id=?",id))
                .containsEntry("tipo","NOTEBOOK").containsEntry("imei","series-A").containsEntry("modelo","Updated");
    }

    private static List<String> equipmentRows(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT (to_jsonb(e)-'tipo')::text||':'||xmin::text||':'||ctid::text FROM equipos e ORDER BY id",String.class);
    }
    private static long workshop(JdbcTemplate jdbc) {
        return jdbc.queryForObject("INSERT INTO talleres(nombre) VALUES('Synthetic workshop') RETURNING id",Long.class);
    }
    private static long client(JdbcTemplate jdbc,long workshop) {
        return jdbc.queryForObject("INSERT INTO clientes(nombre,apellido,telefono,taller_id) VALUES('Synthetic','Client',?,?) RETURNING id",Long.class,UUID.randomUUID().toString().substring(0,12),workshop);
    }
    /** V34 consumer protocol, with all closure guards active during the upgrade fixture. */
    private static void restrict(JdbcTemplate jdbc,DataSource source,long workshop) {
        long user=jdbc.queryForObject("INSERT INTO users(username,email,password,role,taller_id,active,email_verificado,token_version) VALUES('Synthetic owner',?,'fixture','ADMIN',?,true,true,0) RETURNING id",Long.class,UUID.randomUUID()+"@synthetic.invalid",workshop);
        var transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.executeWithoutResult(status->{
            new WorkshopClosureGate(jdbc).lockExclusive(workshop);
            Clock clock=Clock.fixed(Instant.now().truncatedTo(ChronoUnit.MICROS),ZoneOffset.UTC);
            UUID reference=UUID.randomUUID();String proof="a".repeat(64);
            jdbc.update("""
                    INSERT INTO cuenta_cierre_confirmaciones(token_hash,user_id,taller_id,token_version,session_hash,proposito,
                        operacion_id,cierre_referencia,cierre_version,creada_en,expira_en)
                    VALUES(?,?,?,0,?,'CERRAR',?,?,0,?,?)
                    """,proof,user,workshop,"b".repeat(64),reference,reference,Timestamp.from(clock.instant()),Timestamp.from(clock.instant().plusSeconds(120)));
            jdbc.update("UPDATE cuenta_cierre_confirmaciones SET usada_en=? WHERE token_hash=?",Timestamp.from(clock.instant()),proof);
            var receipt=new WorkshopClosureStore(jdbc,clock).restrict(workshop,user,reference);
            jdbc.update("""
                    INSERT INTO cuenta_cierre_operaciones(operacion_id,taller_id,user_id,proposito,cierre_referencia,cierre_version,
                        request_digest,proof_hash,estado_resultante,politica,confirmado_en,reversible_hasta,eliminacion_prevista_en,registrada_en)
                    VALUES(?,?,?,'CERRAR',?,1,?,?,'RESTRINGIDO','ordenfix-cierre/1',?,?,?,?)
                    """,reference,workshop,user,reference,"c".repeat(64),proof,Timestamp.from(receipt.confirmedAt()),
                    Timestamp.from(receipt.reversibleUntil()),Timestamp.from(receipt.deletionExpectedBy()),Timestamp.from(clock.instant()));
            new WorkshopClosureEffects(jdbc).enqueueClose(reference,reference,workshop,user,clock.instant());
        });
    }
}
