package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardorozza.mvgrreparacionesbackend.config.tenant.TenantContext;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.export.*;
import com.leonardorozza.mvgrreparacionesbackend.cuenta.reauth.*;
import com.leonardorozza.mvgrreparacionesbackend.service.ReparacionService;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion.ReparacionRequestDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.dto.reparacion.ReparacionResponseDTO;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.UserSecurityStateLock;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import static com.leonardorozza.mvgrreparacionesbackend.cuenta.reauth.ExportReauthenticationPurpose.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real JWT/HTTP, service transactions and PostgreSQL V33; no email, payment or photo providers. */
@SpringBootTest(properties={"spring.config.import=","spring.config.additional-location=",
        "spring.config.location=optional:classpath:/application.properties","exports.jobs.enabled=false",
        "mail.enabled=false","mercadopago.enabled=false","mercadopago.checkout-enabled=false","photos.private.enabled=false",
        "ordenfix.legal.registration-consent.enabled=false","ordenfix.legal.registration-enforcement.enabled=false",
        "ordenfix.legal.account-read.enabled=false","ordenfix.legal.account-acceptance.enabled=false",
        "ordenfix.legal.public-documents.enabled=false","ordenfix.legal.public-requirements.enabled=false",
        "ordenfix.legal.aggregate-context.enabled=false","ordenfix.legal.editorial-context.enabled=false",
        "ordenfix.legal.import-context.enabled=false","ordenfix.legal.dry-run-context.enabled=false",
        "ordenfix.legal.public-document-read-context.enabled=false","ordenfix.legal.public-requirements-context.enabled=false"})
@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class WorkshopClosureHttpIT {
    @Container static final PostgreSQLContainer PG=new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("closure_http_synthetic").withUsername("closure").withPassword("closure");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",PG::getJdbcUrl);r.add("spring.datasource.username",PG::getUsername);
        r.add("spring.datasource.password",PG::getPassword);r.add("spring.datasource.driver-class-name",PG::getDriverClassName);
        r.add("spring.flyway.enabled",()->"true");r.add("spring.jpa.hibernate.ddl-auto",()->"validate");
        r.add("spring.jpa.properties.hibernate.dialect",()->"org.hibernate.dialect.PostgreSQLDialect");
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired WorkshopClosureStore store;
    @Autowired WorkshopClosureGate gate;
    @Autowired ReparacionService repairs;
    @Autowired PasswordEncoder passwords;
    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy securityFilters;
    private MockMvc mvc;
    @Autowired ExportReauthenticationService reauth;
    @Autowired WorkshopExportSnapshotService snapshots;
    @MockitoSpyBean UserSecurityStateLock security;
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String PASSWORD="closure-http-synthetic-password";
    private Actor actor;
    @BeforeEach void seed() {
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(securityFilters).build();
        long workshop=jdbc.queryForObject("INSERT INTO talleres(nombre) VALUES('Closure HTTP fixture') RETURNING id",Long.class);
        jdbc.update("INSERT INTO suscripciones(taller_id,plan,estado,fecha_inicio) VALUES(?,'FREE','TRIAL',CURRENT_DATE)",workshop);
        String ownerEmail=UUID.randomUUID()+"@closure.synthetic.invalid",employeeEmail=UUID.randomUUID()+"@closure.synthetic.invalid";
        long owner=user(workshop,"ADMIN",ownerEmail);user(workshop,"USER",employeeEmail);
        long customer=jdbc.queryForObject("INSERT INTO clientes(nombre,apellido,telefono,taller_id) VALUES('Fixture','Customer','00000000',?) RETURNING id",Long.class,workshop);
        long equipment=jdbc.queryForObject("INSERT INTO equipos(marca,modelo,cliente_id,taller_id) VALUES('Fixture','Equipment',?,?) RETURNING id",Long.class,customer,workshop);
        actor=new Actor(workshop,owner,ownerEmail,employeeEmail,equipment);
    }
    @AfterEach void releaseThreadContext(){TenantContext.clear();}

    @Test void restrictedOwnerCanLogInAndReadProfileButOperationalRequestsAndEmployeeLoginAreBlocked() throws Exception {
        String oldOwner=login(actor.ownerEmail());
        String oldEmployee=login(actor.employeeEmail());
        close();
        String restricted=login(actor.ownerEmail());
        var profile=mvc.perform(get("/api/perfil").header("Authorization","Bearer "+restricted))
                .andExpect(status().isOk()).andExpect(jsonPath("$.usuario.role").value("ADMIN"))
                .andExpect(jsonPath("$.taller.id").value(actor.workshop())).andReturn().getResponse();
        assertThat(profile.getContentAsString()).doesNotContain(PASSWORD,"tokenVersion","password");
        mvc.perform(get("/api/reparaciones").header("Authorization","Bearer "+restricted))
                .andExpect(status().isLocked()).andExpect(jsonPath("$.code").value("CUENTA_EN_CIERRE"))
                .andExpect(header().string("Cache-Control","private, no-store"));
        mvc.perform(post("/api/reparaciones").header("Authorization","Bearer "+restricted)
                        .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(Map.of(
                                "equipoId",actor.equipment(),"descripcionProblema","blocked"))))
                .andExpect(status().isLocked()).andExpect(jsonPath("$.code").value("CUENTA_EN_CIERRE"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("email",actor.employeeEmail(),"password",PASSWORD))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/perfil").header("Authorization","Bearer "+oldOwner)).andExpect(status().isForbidden());
        mvc.perform(get("/api/perfil").header("Authorization","Bearer "+oldEmployee)).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reparaciones WHERE taller_id=?",Long.class,actor.workshop())).isZero();
    }

    @Test void twoAdmittedReadCommittedRepairCreationsCommitDistinctNumbersWithoutShareLockUpgradeDeadlock() throws Exception {
        var admitted=new CountDownLatch(2);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<ReparacionResponseDTO> create=()-> {
                TenantContext.setTallerId(actor.workshop());
                try { return tx(()-> {
                    assertThat(jdbc.queryForObject("SHOW transaction_isolation",String.class)).isEqualTo("read committed");
                    gate.requireOperational(actor.workshop());
                    admitted.countDown();await(admitted);
                    var request=new ReparacionRequestDTO();request.setEquipoId(actor.equipment());
                    request.setDescripcionProblema("Concurrent fixture");request.setFechaIngreso(LocalDate.now());
                    return repairs.crear(request);
                }); } finally { TenantContext.clear(); }
            };
            var first=executor.submit(create);var second=executor.submit(create);
            var a=first.get(8,TimeUnit.SECONDS);var b=second.get(8,TimeUnit.SECONDS);
            assertThat(a.getId()).isNotEqualTo(b.getId());
            String prefix="ORD-"+LocalDate.now().getYear()+"-";
            assertThat(List.of(a.getNumeroOrden(),b.getNumeroOrden())).containsExactlyInAnyOrder(prefix+"0001",prefix+"0002");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reparaciones WHERE taller_id=?",Long.class,actor.workshop())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT secuencia_orden FROM talleres WHERE id=?",Integer.class,actor.workshop())).isEqualTo(2);
    }

    @Test void restoringWhileDownloadOwnsTheUserRowRejectsDownloadAdmissionWithoutALockCycle() throws Exception {
        String access=login(actor.ownerEmail());
        var codec=spy(new ExportArtifactCodec(Map.of(1,Base64.getEncoder().encodeToString(new byte[32])),1));
        var jobs=new ExportJobService(jdbc,manager,reauth,snapshots,codec,new ExportPhotoReader(()->null));
        var job=jobs.request(access,reauth.issue(access,PASSWORD,EXPORTAR).token(),UUID.randomUUID());
        assertThat(jobs.runNext()).isTrue();assertThat(jobs.status(access,job.id()).state()).isEqualTo("READY");
        var receipt=close();String restricted=login(actor.ownerEmail());
        String proof=reauth.issue(restricted,PASSWORD,DESCARGAR_EXPORTACION).token();
        var userLocked=new CountDownLatch(1);var releaseDownload=new CountDownLatch(1);
        var intercept=new AtomicBoolean(true);
        doAnswer(call->{
            call.callRealMethod();
            if(intercept.compareAndSet(true,false)) {userLocked.countDown();await(releaseDownload);}
            return null;
        }).when(security).refreshAndLock(any());
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var downloading=executor.submit(()->jobs.authorizedArchive(restricted,proof,job.id()));
            assertThat(userLocked.await(3,TimeUnit.SECONDS)).isTrue();
            var restoring=executor.submit(()->tx(()-> {
                gate.lockExclusive(actor.workshop());
                return store.restore(actor.workshop(),actor.owner(),receipt.reference());
            }));
            try {
                awaitWaitingOwner();
                assertThat(restoring.isDone()).isFalse();
            } finally {releaseDownload.countDown();}
            assertThatThrownBy(()->downloading.get(5,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(WorkshopClosureBusyException.class);
            assertThat(restoring.get(5,TimeUnit.SECONDS).state()).isEqualTo("RESTAURADO");
        } finally {releaseDownload.countDown();reset(security);}
        verify(codec,never()).decryptArchive(any(),any());
        assertThat(jdbc.queryForObject("SELECT cierre_estado FROM talleres WHERE id=?",String.class,actor.workshop())).isEqualTo("ABIERTO");
        assertThat(jdbc.queryForObject("SELECT estado FROM cuenta_exportaciones WHERE id=?",String.class,job.id())).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject("SELECT archive_cipher IS NULL FROM cuenta_exportaciones WHERE id=?",Boolean.class,job.id())).isTrue();
    }

    private void awaitWaitingOwner() throws InterruptedException {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
        while(System.nanoTime()<end) {
            if(Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid()
                      AND query LIKE 'SELECT token_version FROM public.users WHERE id=%' AND wait_event_type='Lock')
                    """,Boolean.class)))return;
            Thread.sleep(10);
        }
        throw new AssertionError("Restore did not reach the controlled user-row wait");
    }
    private static void await(CountDownLatch latch) {
        try { if(!latch.await(4,TimeUnit.SECONDS))throw new AssertionError("Fixture deadline"); }
        catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}
    }
    private String login(String email)throws Exception {
        var response=mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("email",email,"password",PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse();
        String token=JSON.readTree(response.getContentAsByteArray()).path("token").asText();
        assertThat(token).isNotBlank();return token;
    }
    private long user(long workshop,String role,String email) {
        return jdbc.queryForObject("""
                INSERT INTO users(username,email,password,role,taller_id,active,email_verificado,token_version)
                VALUES('Closure fixture',?,?,?, ?,true,true,0) RETURNING id
                """,Long.class,email,passwords.encode(PASSWORD),role,workshop);
    }
    private <T>T tx(Supplier<T> work) {
        var transaction=new TransactionTemplate(manager);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(8);return transaction.execute(status->work.get());
    }
    private WorkshopClosureStore.Receipt close() {
        return tx(()->{gate.lockExclusive(actor.workshop());return store.restrict(actor.workshop(),actor.owner(),UUID.randomUUID());});
    }
    private record Actor(long workshop,long owner,String ownerEmail,String employeeEmail,long equipment) { }
}
