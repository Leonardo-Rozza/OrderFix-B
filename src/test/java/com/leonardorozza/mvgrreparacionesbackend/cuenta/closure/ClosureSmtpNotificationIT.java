package com.leonardorozza.mvgrreparacionesbackend.cuenta.closure;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real closure guards and leases, synthetic MIME and one loopback-only SMTP exchange. No external provider. */
@Testcontainers
@Timeout(120)
class ClosureSmtpNotificationIT {
    private static final String FROM = "OrdenFix <noreply@synthetic.invalid>";
    private static final String PUBLIC_URL = "https://synthetic.invalid";
    @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("closure_smtp").withUsername("fixture").withPassword("synthetic-smtp-owner")
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindIp("127.0.0.1"),ExposedPort.tcp(5432))));
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager manager;
    MutableClock clock;
    long taller,user;
    UUID closure;
    String email;
    JavaMailSender mail;
    ObjectProvider<JavaMailSender> provider;
    ClosureSmtpNotificationAdapter adapter;
    List<MimeMessage> delivered;

    @BeforeAll static void database() {
        assertThat(PG.getContainerInfo().getNetworkSettings().getPorts().getBindings().get(ExposedPort.tcp(5432)))
                .allSatisfy(binding -> assertThat(binding.getHostIp()).isEqualTo("127.0.0.1"));
        var source = new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source); manager = new DataSourceTransactionManager(source);
    }

    @SuppressWarnings("unchecked")
    @BeforeEach void seed() throws Exception {
        jdbc.execute("TRUNCATE public.cuenta_cierre_efectos");
        clock = new MutableClock(Instant.now().truncatedTo(ChronoUnit.MICROS));
        taller = jdbc.queryForObject("INSERT INTO talleres(nombre) VALUES('SMTP synthetic') RETURNING id",Long.class);
        jdbc.update("INSERT INTO suscripciones(taller_id,plan,estado) VALUES(?,'FREE','TRIAL')",taller);
        email = UUID.randomUUID()+"@synthetic.invalid";
        user = jdbc.queryForObject("INSERT INTO users(username,email,password,role,taller_id,active,email_verificado,token_version) VALUES(?,?,'not-a-login-secret','ADMIN',?,true,true,0) RETURNING id",
                Long.class,UUID.randomUUID().toString(),email,taller);
        closure = UUID.randomUUID();
        delivered = new CopyOnWriteArrayList<>();
        mail = mock(JavaMailSender.class); provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mail);
        when(mail.createMimeMessage()).thenAnswer(call -> {
            assertOutsideTransaction(); return emptyMessage();
        });
        doAnswer(call -> { assertOutsideTransaction(); delivered.add(copy(call.getArgument(0))); return null; })
                .when(mail).send(any(MimeMessage.class));
        adapter = new ClosureSmtpNotificationAdapter(jdbc,manager,clock,provider,FROM,PUBLIC_URL);
    }

    @ParameterizedTest @ValueSource(strings={"CLOSED","RESTORED","HISTORICAL_CLOSED"})
    void acceptedClosureAndRestorationNoticesAreConfirmedOnce(String scenario) throws Exception {
        transition(false);
        String type = "AVISO_CIERRE";
        if (!"CLOSED".equals(scenario)) {
            clock.advance(Duration.ofSeconds(1)); transition(true);
            if ("RESTORED".equals(scenario)) { type="AVISO_RESTAURACION"; postpone("AVISO_CIERRE"); }
            else postpone("AVISO_RESTAURACION");
        }
        UUID id=effectId(type);
        assertThat(worker(adapter).runNextNotification()).isTrue();
        assertThat(state(id)).isEqualTo("CONFIRMADO");
        assertThat(jdbc.queryForObject("SELECT intentos FROM cuenta_cierre_efectos WHERE efecto_id=?",Integer.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT confirmed_at IS NOT NULL AND lease_token IS NULL AND lease_until IS NULL FROM cuenta_cierre_efectos WHERE efecto_id=?",Boolean.class,id)).isTrue();
        assertThat(delivered).hasSize(1); assertMime(delivered.getFirst(),type);
        String confirmed=fingerprint();
        assertThat(worker(adapter).runNextNotification()).isFalse();
        assertThat(fingerprint()).isEqualTo(confirmed);
        verify(mail,times(1)).send(any(MimeMessage.class));
    }

    @Test void anUnverifiedRecipientIsNotSentEvenForAValidHistoricalEvent() {
        transition(false); clock.advance(Duration.ofSeconds(1)); transition(true);
        postpone("AVISO_RESTAURACION");
        jdbc.update("UPDATE users SET email_verificado=false WHERE id=?",user);
        assertThat(worker(adapter).runNextNotification()).isTrue();
        assertThat(state(effectId("AVISO_CIERRE"))).isEqualTo("INCIERTO");
        verify(mail,never()).send(any(MimeMessage.class));
    }

    @ParameterizedTest @ValueSource(strings={"EFFECT","WORKSHOP","USER","CLOSURE","KIND","OCCURRED_AT","LEASE_TOKEN","LEASE_UNTIL"})
    void forgedEventFieldsCannotReachSmtpOrMutateTheEffect(String field) {
        transition(false); var valid=claimedEvent();
        var forged=new ClosureNotificationPort.Event(
                field.equals("EFFECT")?UUID.randomUUID():valid.operationKey(),
                field.equals("CLOSURE")?UUID.randomUUID():valid.closureReference(),
                field.equals("WORKSHOP")?valid.tallerId()+100000:valid.tallerId(),
                field.equals("USER")?valid.userId()+100000:valid.userId(),
                field.equals("KIND")?ClosureNotificationPort.Kind.RESTORED:valid.kind(),
                field.equals("OCCURRED_AT")?valid.occurredAt().plusSeconds(1):valid.occurredAt(),
                field.equals("LEASE_TOKEN")?UUID.randomUUID():valid.leaseToken(),
                field.equals("LEASE_UNTIL")?valid.leaseUntil().plusSeconds(1):valid.leaseUntil());
        String before=fingerprint();
        assertThat(adapter.send(forged)).isEqualTo(ClosureNotificationPort.Result.UNCERTAIN);
        assertThat(fingerprint()).isEqualTo(before);
        verify(mail,never()).send(any(MimeMessage.class));
    }

    @ParameterizedTest @ValueSource(strings={"http://synthetic.invalid","https://synthetic.invalid?token=synthetic","https://user@synthetic.invalid","https://synthetic.invalid#synthetic"})
    void unsafePublicOriginsFailBeforeSmtpAndAreRetryable(String publicUrl) {
        transition(false);var event=claimedEvent();String before=fingerprint();
        var configured=new ClosureSmtpNotificationAdapter(jdbc,manager,clock,provider,FROM,publicUrl);
        assertThat(configured.send(event)).isEqualTo(ClosureNotificationPort.Result.RETRYABLE);
        assertThat(fingerprint()).isEqualTo(before);
        verify(mail,never()).send(any(MimeMessage.class));
    }

    @ParameterizedTest @ValueSource(strings={"one@synthetic.invalid,two@synthetic.invalid","Group:one@synthetic.invalid;","one@synthetic.invalid\r\nBcc:other@synthetic.invalid"})
    void recipientsCannotContainMultipleAddressesGroupsOrHeaders(String recipient) {
        transition(false);clock.advance(Duration.ofSeconds(1));transition(true);
        jdbc.update("UPDATE users SET email=? WHERE id=?",recipient,user);
        var event=claimedEvent();String before=fingerprint();
        assertThat(adapter.send(event)).isEqualTo(ClosureNotificationPort.Result.UNCERTAIN);
        assertThat(fingerprint()).isEqualTo(before);
        verify(mail,never()).send(any(MimeMessage.class));
    }

    @Test void aCallerTransactionCannotReachSmtp() {
        transition(false);var event=claimedEvent();String before=fingerprint();
        new TransactionTemplate(manager).executeWithoutResult(status ->
                assertThat(adapter.send(event)).isEqualTo(ClosureNotificationPort.Result.UNCERTAIN));
        assertThat(fingerprint()).isEqualTo(before);verifyNoInteractions(provider,mail);
    }

    @Test void aDifferentRealWorkshopAndOwnerCannotReuseTheOriginalLease() {
        transition(false);var event=claimedEvent();
        long otherWorkshop=jdbc.queryForObject("INSERT INTO talleres(nombre) VALUES('other synthetic SMTP') RETURNING id",Long.class);
        long otherUser=jdbc.queryForObject("INSERT INTO users(username,email,password,role,taller_id,active,email_verificado,token_version) VALUES(?,?,'synthetic','ADMIN',?,true,true,0) RETURNING id",
                Long.class,UUID.randomUUID().toString(),UUID.randomUUID()+"@synthetic.invalid",otherWorkshop);
        var forged=new ClosureNotificationPort.Event(event.operationKey(),event.closureReference(),otherWorkshop,otherUser,
                event.kind(),event.occurredAt(),event.leaseToken(),event.leaseUntil());
        String before=fingerprint();
        assertThat(adapter.send(forged)).isEqualTo(ClosureNotificationPort.Result.UNCERTAIN);
        assertThat(fingerprint()).isEqualTo(before);verify(mail,never()).send(any(MimeMessage.class));
    }

    @SuppressWarnings("unchecked")
    @Test void theNotificationEntryNeverResolvesAnInstalledRenewalPort() {
        transition(false);
        ObjectProvider<ClosureRenewalPort> renewalProvider=mock(ObjectProvider.class);
        ClosureRenewalPort renewal=mock(ClosureRenewalPort.class);
        when(renewalProvider.getIfAvailable()).thenReturn(renewal);
        ObjectProvider<ClosureNotificationPort> notificationProvider=mock(ObjectProvider.class);
        when(notificationProvider.getIfAvailable()).thenReturn(adapter);
        var notifications=new WorkshopClosureEffectWorker(jdbc,manager,clock,renewalProvider,notificationProvider);
        assertThat(notifications.runNextNotification()).isTrue();
        assertThat(state(effectId("AVISO_CIERRE"))).isEqualTo("CONFIRMADO");
        verifyNoInteractions(renewalProvider,renewal);
    }

    @Test void aMissingNotificationPortDoesNotClaimAnEffect() {
        transition(false);String before=fingerprint();
        assertThat(worker(null).runNextNotification()).isFalse();
        assertThat(fingerprint()).isEqualTo(before);verifyNoInteractions(provider,mail);
    }

    @Test void replayingAnAlreadyAcknowledgedEventCannotSendAgain() {
        transition(false); var observed=new AtomicReference<ClosureNotificationPort.Event>();
        ClosureNotificationPort capture=event -> {observed.set(event); return adapter.send(event);};
        worker(capture).runNextNotification();
        String confirmed=fingerprint();
        assertThat(adapter.send(observed.get())).isEqualTo(ClosureNotificationPort.Result.UNCERTAIN);
        assertThat(fingerprint()).isEqualTo(confirmed);
        verify(mail,times(1)).send(any(MimeMessage.class));
    }

    @Test void preparationFailureRetriesOnlyAfterBackoffWithTheSameOperationIdentity() {
        transition(false); UUID id=effectId("AVISO_CIERRE");
        when(mail.createMimeMessage()).thenThrow(new IllegalStateException("synthetic preparation failure"))
                .thenAnswer(call -> {assertOutsideTransaction(); return emptyMessage();});
        List<ClosureNotificationPort.Event> events=new CopyOnWriteArrayList<>();
        ClosureNotificationPort capture=event -> {events.add(event);return adapter.send(event);};
        assertThat(worker(capture).runNextNotification()).isTrue();
        assertThat(state(id)).isEqualTo("PENDIENTE");
        verify(mail,never()).send(any(MimeMessage.class));
        String waiting=fingerprint();
        assertThat(worker(capture).runNextNotification()).isFalse();
        assertThat(fingerprint()).isEqualTo(waiting);
        Instant available=jdbc.queryForObject("SELECT available_at FROM cuenta_cierre_efectos WHERE efecto_id=?",Timestamp.class,id).toInstant();
        assertThat(available).isAfter(clock.instant()); clock.set(available.plusMillis(1));
        assertThat(worker(capture).runNextNotification()).isTrue();
        assertThat(state(id)).isEqualTo("CONFIRMADO");
        assertThat(events).hasSize(2);assertThat(events.get(1).operationKey()).isEqualTo(events.get(0).operationKey());
        assertThat(events.get(1).leaseToken()).isNotEqualTo(events.get(0).leaseToken());
        assertThat(jdbc.queryForObject("SELECT intentos FROM cuenta_cierre_efectos WHERE efecto_id=?",Integer.class,id)).isEqualTo(2);
        verify(mail,times(1)).send(any(MimeMessage.class));
    }

    @Test void anExceptionAfterSendMayHaveAcceptedTheMessageAndIsNeverAutomaticallyRetried() throws Exception {
        transition(false); UUID id=effectId("AVISO_CIERRE");
        doAnswer(call -> {
            assertOutsideTransaction(); delivered.add(copy(call.getArgument(0)));
            throw new MailSendException("synthetic transport lost acknowledgement");
        }).when(mail).send(any(MimeMessage.class));
        assertThat(worker(adapter).runNextNotification()).isTrue();
        assertThat(state(id)).isEqualTo("INCIERTO");
        assertThat(delivered).hasSize(1);
        clock.advance(Duration.ofDays(1)); String uncertain=fingerprint();
        assertThat(worker(adapter).runNextNotification()).isFalse();
        assertThat(fingerprint()).isEqualTo(uncertain);
        verify(mail,times(1)).send(any(MimeMessage.class));
    }

    @Test void acceptedSmtpWithRolledBackSqlAcknowledgementExpiresWithoutAnotherSend() {
        transition(false); UUID id=effectId("AVISO_CIERRE"); var fault=new AtomicBoolean();
        JdbcTemplate failingAck=new JdbcTemplate(jdbc.getDataSource()) {
            @Override public int update(String sql,Object... args) {
                int changed=super.update(sql,args);
                if (sql.contains("SET estado=?,confirmed_at=?") && fault.compareAndSet(false,true))
                    throw new DataAccessResourceFailureException("synthetic post-update acknowledgement failure");
                return changed;
            }
        };
        assertThatThrownBy(() -> worker(failingAck,adapter).runNextNotification()).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(fault).isTrue(); assertThat(state(id)).isEqualTo("EN_CURSO");
        assertThat(jdbc.queryForObject("SELECT confirmed_at IS NULL FROM cuenta_cierre_efectos WHERE efecto_id=?",Boolean.class,id)).isTrue();
        assertThat(worker(adapter).runNextNotification()).isFalse();
        clock.advance(Duration.ofSeconds(121));
        assertThat(worker(adapter).runNextNotification()).isFalse();
        assertThat(state(id)).isEqualTo("INCIERTO");
        verify(mail,times(1)).send(any(MimeMessage.class)); assertThat(delivered).hasSize(1);
    }

    @Test void twoWorkersCannotSendTheSameLiveLease() throws Exception {
        transition(false); UUID id=effectId("AVISO_CIERRE");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call -> {
            assertOutsideTransaction(); entered.countDown(); assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();
            delivered.add(copy(call.getArgument(0)));return null;
        }).when(mail).send(any(MimeMessage.class));
        try (var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(() -> worker(adapter).runNextNotification());
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            try { assertThat(pool.submit(() -> worker(adapter).runNextNotification()).get(5,TimeUnit.SECONDS)).isFalse(); }
            finally {release.countDown();}
            assertThat(first.get(5,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(state(id)).isEqualTo("CONFIRMADO");
        verify(mail,times(1)).send(any(MimeMessage.class));assertThat(delivered).hasSize(1);
    }

    @ParameterizedTest @ValueSource(strings={"PREPARATION","SEND"})
    void leaseExpirationCannotAccreditAStaleAcknowledgementOrCauseResending(String phase) throws Exception {
        transition(false); UUID id=effectId("AVISO_CIERRE");
        if (phase.equals("PREPARATION")) when(mail.createMimeMessage()).thenAnswer(call -> {
            assertOutsideTransaction();clock.advance(Duration.ofSeconds(121));return emptyMessage();
        });
        else doAnswer(call -> {
            assertOutsideTransaction();delivered.add(copy(call.getArgument(0)));clock.advance(Duration.ofSeconds(121));return null;
        }).when(mail).send(any(MimeMessage.class));
        assertThat(worker(adapter).runNextNotification()).isTrue();
        assertThat(state(id)).isEqualTo("EN_CURSO");
        assertThat(worker(adapter).runNextNotification()).isFalse();
        assertThat(state(id)).isEqualTo("INCIERTO");
        verify(mail,times(phase.equals("SEND")?1:0)).send(any(MimeMessage.class));
    }

    @Test void theSecondPreSendReadRejectsAnOwnerDeactivatedDuringPreparation() {
        transition(false);UUID id=effectId("AVISO_CIERRE");
        when(mail.createMimeMessage()).thenAnswer(call -> {
            assertOutsideTransaction();jdbc.update("UPDATE users SET active=false WHERE id=?",user);return emptyMessage();
        });
        assertThat(worker(adapter).runNextNotification()).isTrue();
        assertThat(state(id)).isEqualTo("INCIERTO");
        verify(mail,never()).send(any(MimeMessage.class));
    }

    @Test void oneRealLoopbackSmtpAcceptanceConfirmsExactlyOneNotice() throws Exception {
        transition(false);UUID id=effectId("AVISO_CIERRE");
        try (var smtp=new LocalSmtp()) {
            var transport=new JavaMailSenderImpl();transport.setHost("127.0.0.1");transport.setPort(smtp.port());
            transport.setDefaultEncoding("UTF-8");
            var properties=transport.getJavaMailProperties();
            properties.setProperty("mail.smtp.auth","false");properties.setProperty("mail.smtp.starttls.enable","false");
            properties.setProperty("mail.smtp.connectiontimeout","2000");properties.setProperty("mail.smtp.timeout","2000");
            properties.setProperty("mail.smtp.writetimeout","2000");
            when(provider.getIfAvailable()).thenReturn(transport);
            assertThat(worker(adapter).runNextNotification()).isTrue();
            assertThat(state(id)).isEqualTo("CONFIRMADO");
            assertMime(smtp.message(),"AVISO_CIERRE");
            assertThat(worker(adapter).runNextNotification()).isFalse();
            verifyNoInteractions(mail);
        }
    }

    private void assertMime(MimeMessage message,String type) throws Exception {
        assertThat(message.getAllRecipients()).hasSize(1);
        assertThat(((InternetAddress)message.getRecipients(Message.RecipientType.TO)[0]).getAddress()).isEqualTo(email);
        assertThat(message.getRecipients(Message.RecipientType.CC)).isNull();assertThat(message.getRecipients(Message.RecipientType.BCC)).isNull();
        assertThat(((InternetAddress)message.getFrom()[0]).getAddress()).isEqualTo("noreply@synthetic.invalid");
        assertThat(message.getSubject()).contains("OrdenFix");
        assertThat(new ContentType(message.getContentType()).getBaseType()).isEqualTo("multipart/alternative");
        var content=(MimeMultipart)message.getContent();assertThat(content.getCount()).isEqualTo(2);
        for(int i=0;i<2;i++) {
            var part=content.getBodyPart(i);var mime=new ContentType(part.getContentType());
            assertThat(mime.getBaseType()).isEqualTo(i==0?"text/plain":"text/html");
            assertThat(mime.getParameter("charset")).isEqualToIgnoringCase("UTF-8");
            String body=(String)part.getContent();
            assertThat(body).contains("OrdenFix",PUBLIC_URL).doesNotContain("not-a-login-secret");
            if(type.equals("AVISO_CIERRE"))assertThat(body.toLowerCase(Locale.ROOT)).contains("cierre");
            else assertThat(body.toLowerCase(Locale.ROOT)).contains("restaur");
            assertThat(part.getFileName()).isNull();assertThat(part.getDisposition()).isNull();
        }
    }

    private void transition(boolean restore) {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            new WorkshopClosureGate(jdbc).lockExclusive(taller);
            String proof=hex(),purpose=restore?"RESTAURAR":"CERRAR";UUID operation=restore?UUID.randomUUID():closure;
            long epoch=jdbc.queryForObject("SELECT token_version FROM users WHERE id=?",Long.class,user);
            long generation=jdbc.queryForObject("SELECT cierre_version FROM talleres WHERE id=?",Long.class,taller);
            jdbc.update("INSERT INTO cuenta_cierre_confirmaciones(token_hash,user_id,taller_id,token_version,session_hash,proposito,operacion_id,cierre_referencia,cierre_version,creada_en,expira_en) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    proof,user,taller,epoch,hex(),purpose,operation,closure,generation,Timestamp.from(clock.instant()),Timestamp.from(clock.instant().plusSeconds(120)));
            jdbc.update("UPDATE cuenta_cierre_confirmaciones SET usada_en=? WHERE token_hash=?",Timestamp.from(clock.instant()),proof);
            var store=new WorkshopClosureStore(jdbc,clock);
            var receipt=restore?store.restore(taller,user,closure):store.restrict(taller,user,closure);
            jdbc.update("""
                    INSERT INTO cuenta_cierre_operaciones(operacion_id,taller_id,user_id,proposito,cierre_referencia,cierre_version,
                    request_digest,proof_hash,estado_resultante,politica,confirmado_en,reversible_hasta,eliminacion_prevista_en,registrada_en)
                    VALUES(?,?,?,?,?,?,?,?,?,'ordenfix-cierre/1',?,?,?,?)
                    """,operation,taller,user,purpose,closure,generation+1,hex(),proof,restore?"ABIERTO":"RESTRINGIDO",
                    Timestamp.from(receipt.confirmedAt()),Timestamp.from(receipt.reversibleUntil()),Timestamp.from(receipt.deletionExpectedBy()),Timestamp.from(clock.instant()));
            var effects=new WorkshopClosureEffects(jdbc);
            if(restore)effects.enqueueRestore(operation,closure,taller,user,clock.instant());
            else effects.enqueueClose(operation,closure,taller,user,clock.instant());
        });
    }
    private ClosureNotificationPort.Event claimedEvent() {
        UUID id=effectId("AVISO_CIERRE"),lease=UUID.randomUUID();Instant until=clock.instant().plusSeconds(120);
        jdbc.update("UPDATE cuenta_cierre_efectos SET estado='EN_CURSO',intentos=1,lease_token=?,lease_until=? WHERE efecto_id=?",lease,Timestamp.from(until),id);
        Instant created=jdbc.queryForObject("SELECT created_at FROM cuenta_cierre_efectos WHERE efecto_id=?",Timestamp.class,id).toInstant();
        return new ClosureNotificationPort.Event(id,closure,taller,user,ClosureNotificationPort.Kind.CLOSED,created,lease,until);
    }
    private void postpone(String type){jdbc.update("UPDATE cuenta_cierre_efectos SET available_at=? WHERE taller_id=? AND tipo=?",Timestamp.from(clock.instant().plus(Duration.ofDays(2))),taller,type);}
    private UUID effectId(String type){return jdbc.queryForObject("SELECT efecto_id FROM cuenta_cierre_efectos WHERE taller_id=? AND tipo=?",UUID.class,taller,type);}
    private String state(UUID id){return jdbc.queryForObject("SELECT estado FROM cuenta_cierre_efectos WHERE efecto_id=?",String.class,id);}
    private String fingerprint(){return jdbc.queryForObject("SELECT coalesce(string_agg(to_jsonb(e)::text||xmin::text,',' ORDER BY efecto_id),'') FROM cuenta_cierre_efectos e",String.class);}
    private WorkshopClosureEffectWorker worker(ClosureNotificationPort notification){return worker(jdbc,notification);}
    @SuppressWarnings("unchecked") private WorkshopClosureEffectWorker worker(JdbcTemplate connection,ClosureNotificationPort notification) {
        ObjectProvider<ClosureRenewalPort> renew=mock(ObjectProvider.class);ObjectProvider<ClosureNotificationPort> notify=mock(ObjectProvider.class);
        when(notify.getIfAvailable()).thenReturn(notification);
        return new WorkshopClosureEffectWorker(connection,manager,clock,renew,notify);
    }
    private static void assertOutsideTransaction(){assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();}
    private static MimeMessage emptyMessage(){return new MimeMessage(Session.getInstance(new Properties()));}
    private static MimeMessage copy(MimeMessage original) throws Exception {
        original.saveChanges();var bytes=new ByteArrayOutputStream();original.writeTo(bytes);
        return new MimeMessage(Session.getInstance(new Properties()),new ByteArrayInputStream(bytes.toByteArray()));
    }
    private static String hex(){return UUID.randomUUID().toString().replace("-","").repeat(2);}
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;MutableClock(Instant initial){now=new AtomicReference<>(initial);}
        void advance(Duration amount){now.updateAndGet(value -> value.plus(amount));}void set(Instant value){now.set(value);}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}@Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return now.get();}
    }

    /** Minimal one-message SMTP peer bound exclusively to loopback; captures bytes before replying 250. */
    private static final class LocalSmtp implements AutoCloseable {
        private final ServerSocket listener=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));
        private final ExecutorService thread=Executors.newSingleThreadExecutor();
        private final AtomicReference<Socket> client=new AtomicReference<>();
        private final Future<byte[]> received;
        LocalSmtp() throws IOException {
            listener.setSoTimeout(5000);
            received=thread.submit(() -> {
                try(Socket socket=listener.accept()) {
                    client.set(socket);socket.setSoTimeout(5000);
                    var in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.ISO_8859_1));
                    var out=new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.US_ASCII));
                    reply(out,"220 localhost synthetic SMTP");byte[] message=null;
                    for(String line;(line=in.readLine())!=null;) {
                        if(line.startsWith("EHLO ")||line.startsWith("HELO "))reply(out,"250 localhost");
                        else if(line.startsWith("MAIL FROM:")||line.startsWith("RCPT TO:")||line.equals("RSET"))reply(out,"250 OK");
                        else if(line.equals("DATA")) {
                            if(message!=null)throw new AssertionError("unexpected duplicate DATA");
                            reply(out,"354 End with a dot");var body=new StringBuilder();
                            while((line=in.readLine())!=null&&!line.equals("."))body.append(line.startsWith("..")?line.substring(1):line).append("\r\n");
                            if(line==null)throw new EOFException("incomplete synthetic DATA");
                            message=body.toString().getBytes(StandardCharsets.ISO_8859_1);reply(out,"250 Accepted");
                        } else if(line.equals("QUIT")){reply(out,"221 Bye");break;}
                        else throw new AssertionError("Unexpected SMTP command");
                    }
                    return Objects.requireNonNull(message,"No SMTP message received");
                }
            });
        }
        int port(){return listener.getLocalPort();}
        MimeMessage message() throws Exception {return new MimeMessage(Session.getInstance(new Properties()),new ByteArrayInputStream(received.get(5,TimeUnit.SECONDS)));}
        private static void reply(BufferedWriter out,String line) throws IOException {out.write(line+"\r\n");out.flush();}
        @Override public void close() throws IOException {
            listener.close();Socket socket=client.get();if(socket!=null)socket.close();received.cancel(true);thread.shutdownNow();
        }
    }
}
