package com.leonardorozza.mvgrreparacionesbackend;

import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.ContextoLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.EstadoVersionLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.LocaleLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.TipoOperacionIdempotenteLegal;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal.LegalAceptacionDocumentoRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal.LegalAceptacionMetadataCifradaRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal.LegalAceptacionMetadataRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal.LegalAceptacionRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal.LegalDocumentoTransicionRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal.LegalDocumentoVersionRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal.LegalIdempotenciaResultadoRepository;
import com.leonardorozza.mvgrreparacionesbackend.persistence.repository.legal.LegalRequisitoDocumentoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.sql.Savepoint;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class LegalPersistenceIT {

    private static final Instant BASE_TIME = Instant.parse("2026-01-10T12:00:00Z");
    private static final String REVISION = "sha256:" + hex('e');

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ordenfix_legal_persistence_test")
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
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private LegalDocumentoVersionRepository documentoVersionRepository;

    @Autowired
    private LegalAceptacionRepository aceptacionRepository;

    @Autowired
    private LegalAceptacionDocumentoRepository aceptacionDocumentoRepository;

    @Autowired
    private LegalRequisitoDocumentoRepository requisitoDocumentoRepository;

    @Autowired
    private LegalDocumentoTransicionRepository documentoTransicionRepository;

    @Autowired
    private LegalAceptacionMetadataCifradaRepository metadataCifradaRepository;

    @Autowired
    private LegalAceptacionMetadataRepository metadataRepository;

    @Autowired
    private LegalIdempotenciaResultadoRepository idempotenciaRepository;

    private TransactionTemplate transaction;

    @BeforeEach
    void cleanLegalState() {
        DataSource dataSource = jdbc.getDataSource();
        if (!(jdbc instanceof InstantAwareJdbcTemplate)) {
            jdbc = new InstantAwareJdbcTemplate(dataSource);
        }
        transaction = new TransactionTemplate(transactionManager);
        jdbc.execute("""
                TRUNCATE TABLE legal_publicaciones, legal_documento_reemplazo_lotes
                RESTART IDENTITY CASCADE
                """);
    }

    @Test
    void sellaPublicacionCompletaConOrdinalGlobalEnDosScopesYRechazaInsercionesTardias() {
        UUID publicationId = openPublication("dos-scopes");

        DocumentLine termsLine = addDocumentLine(
                publicationId, "terms-registro", "TERMINOS_SERVICIO");
        DocumentVersion terms = addDocumentVersion(
                publicationId, termsLine, "1.0.0", 1, 1,
                BASE_TIME, "REGISTRO");
        RequirementLine registrationLine = addRequirementLine(
                publicationId, "registro-admin", "REGISTRO", "ACEPTACION",
                "ADMIN_TITULAR");
        RequirementVersion registration = addRequirementVersion(
                publicationId, registrationLine, "1.0.0", 1, 1, true, terms);
        addSnapshot(publicationId, "REGISTRO", "ADMIN_TITULAR", REVISION,
                List.of(registration));

        DocumentLine privacyLine = addDocumentLine(
                publicationId, "privacy-cierre", "POLITICA_PRIVACIDAD");
        DocumentVersion privacy = addDocumentVersion(
                publicationId, privacyLine, "1.0.0", 1, 2,
                BASE_TIME, "CIERRE_CUENTA");
        RequirementLine closureLine = addRequirementLine(
                publicationId, "cierre-admin", "CIERRE_CUENTA", "LECTURA",
                "ADMIN_TITULAR");
        RequirementVersion closure = addRequirementVersion(
                publicationId, closureLine, "1.0.0", 1, 2, false, privacy);
        UUID secondSnapshot = addSnapshot(
                publicationId, "CIERRE_CUENTA", "ADMIN_TITULAR", hexRevision('f'),
                List.of(closure));

        sealPublication(publicationId);

        assertThat(stringValue("SELECT estado_construccion FROM legal_publicaciones WHERE id = ?",
                publicationId)).isEqualTo("SELLADO");
        assertThat(intValue("""
                SELECT manifest_ordinal
                  FROM legal_requisito_conjunto_miembros
                 WHERE conjunto_id = ?
                """, secondSnapshot)).isEqualTo(2);

        assertRejected(() -> jdbc.update("""
                        INSERT INTO legal_documento_contextos (documento_version_id, contexto)
                        VALUES (?, 'USO_CONTINUADO')
                        """, terms.id()),
                "ya esta sellada");
        assertRejected(() -> jdbc.update("""
                        INSERT INTO legal_documento_lineas
                            (id, clave, tipo, locale, publicacion_intro_id, creado_en)
                        VALUES (?, ?, 'AVISO_CLIENTES_TALLER', 'es-AR', ?, ?)
                        """, UUID.randomUUID(), uniqueKey("late"), publicationId, BASE_TIME),
                "ya esta sellada");
    }

    @Test
    void funcionesDeTriggerNoPermitenSombrearTablasLegalesConPgTemp() {
        UUID publicationId = openPublication("search-path");
        DocumentLine line = addDocumentLine(
                publicationId, "search-path-document", "TERMINOS_SERVICIO");
        DocumentVersion document = addDocumentVersion(
                publicationId, line, "1", 1, 1, BASE_TIME, "REGISTRO");
        sealPublication(publicationId);

        SQLException[] rejection = new SQLException[1];
        inTransaction(() -> jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TEMP TABLE legal_publicaciones (
                            id UUID PRIMARY KEY,
                            estado_construccion VARCHAR(10) NOT NULL
                        ) ON COMMIT DROP
                        """);
            }
            try (var insertShadow = connection.prepareStatement("""
                    INSERT INTO legal_publicaciones (id, estado_construccion)
                    VALUES (?, 'ABIERTO')
                    """)) {
                insertShadow.setObject(1, publicationId);
                insertShadow.executeUpdate();
            }

            Savepoint beforeLateChild = connection.setSavepoint("before_late_child");
            try (var lateChild = connection.prepareStatement("""
                    INSERT INTO public.legal_documento_contextos
                        (documento_version_id, contexto)
                    VALUES (?, 'USO_CONTINUADO')
                    """)) {
                lateChild.setObject(1, document.id());
                lateChild.executeUpdate();
            } catch (SQLException error) {
                rejection[0] = error;
                connection.rollback(beforeLateChild);
            }
            try (var statement = connection.createStatement()) {
                statement.execute("DROP TABLE legal_publicaciones");
            }
            return null;
        }));

        assertThat((Throwable) rejection[0]).isNotNull();
        assertThat(rejection[0].getMessage()).contains("ya esta sellada");
        assertThat(intValue("""
                SELECT count(*) FROM legal_documento_contextos
                 WHERE documento_version_id = ? AND contexto = 'USO_CONTINUADO'
                """, document.id())).isZero();
    }

    @Test
    void rechazaSellosIncompletosYOrdinalesDeManifiestoNoContiguosSinEstadoParcial() {
        UUID incompletePublication = openPublication("incompleta");
        addDocumentLine(incompletePublication, "linea-sin-version", "TERMINOS_SERVICIO");

        assertRejected(() -> sealPublication(incompletePublication),
                "linea documental sin version propia");
        assertThat(stringValue("SELECT estado_construccion FROM legal_publicaciones WHERE id = ?",
                incompletePublication)).isEqualTo("ABIERTO");

        UUID gappedPublication = openPublication("ordinales");
        DocumentLine firstLine = addDocumentLine(
                gappedPublication, "doc-ordinal-uno", "TERMINOS_SERVICIO");
        addDocumentVersion(gappedPublication, firstLine, "1", 1, 1,
                BASE_TIME, "REGISTRO");
        DocumentLine secondLine = addDocumentLine(
                gappedPublication, "doc-ordinal-tres", "POLITICA_PRIVACIDAD");
        addDocumentVersion(gappedPublication, secondLine, "1", 1, 3,
                BASE_TIME, "REGISTRO");

        assertRejected(() -> sealPublication(gappedPublication),
                "ordinales de manifiesto no contiguos");
        assertThat(stringValue("SELECT estado_construccion FROM legal_publicaciones WHERE id = ?",
                gappedPublication)).isEqualTo("ABIERTO");
    }

    @Test
    void publicaActivaYMaterializaSlotsPeroRespetaBarreraTemporal() {
        UUID publicationId = openPublication("activacion");
        DocumentLine line = addDocumentLine(
                publicationId, "doc-activo", "TERMINOS_SERVICIO");
        DocumentVersion active = addDocumentVersion(
                publicationId, line, "1", 1, 1, BASE_TIME.plusSeconds(3),
                "REGISTRO", "USO_CONTINUADO");

        DocumentLine futureLine = addDocumentLine(
                publicationId, "doc-futuro", "POLITICA_PRIVACIDAD");
        DocumentVersion future = addDocumentVersion(
                publicationId, futureLine, "1", 1, 2,
                Instant.now().plusSeconds(3_600), "REGISTRO");
        sealPublication(publicationId);

        publishDocument(active);
        assertRejected(() -> transitionDocument(
                        active.id(), "PUBLICADA", "VIGENTE", null, BASE_TIME.plusSeconds(2)),
                "vigente_desde");
        activateDocument(active);

        assertThat(stringValue("SELECT estado FROM legal_documento_versiones WHERE id = ?",
                active.id())).isEqualTo("VIGENTE");
        assertThat(intValue("SELECT count(*) FROM legal_documento_vigentes WHERE documento_version_id = ?",
                active.id())).isEqualTo(2);

        publishDocument(future);
        assertRejected(() -> activateDocument(future), "vigente_desde");
        assertThat(stringValue("SELECT estado FROM legal_documento_versiones WHERE id = ?",
                future.id())).isEqualTo("PUBLICADA");
        assertThat(intValue("SELECT count(*) FROM legal_documento_vigentes WHERE documento_version_id = ?",
                future.id())).isZero();
    }

    @Test
    void linajePublicadoEsMonotonoYEstadosTerminalesEHistorialSonAppendOnly() {
        UUID publicationId = openPublication("linaje");
        DocumentLine line = addDocumentLine(
                publicationId, "doc-linaje", "TERMINOS_SERVICIO");
        DocumentVersion abandoned = addDocumentVersion(
                publicationId, line, "1", 1, 1, BASE_TIME, "REGISTRO");
        DocumentVersion winner = addDocumentVersion(
                publicationId, line, "2", 2, 2, BASE_TIME, "REGISTRO");
        sealPublication(publicationId);

        publishDocument(winner);
        assertRejected(() -> publishDocument(abandoned), "no supera el maximo publicado");
        assertThat(stringValue("SELECT estado FROM legal_documento_versiones WHERE id = ?",
                abandoned.id())).isEqualTo("BORRADOR");

        assertRejected(() -> jdbc.update(
                        "UPDATE legal_documento_versiones SET titulo = 'Mutado' WHERE id = ?",
                        winner.id()),
                "estado legal solo puede ser materializado");

        activateDocument(winner);
        retireDocument(winner, "Documento discontinuado");

        assertThat(stringValue("SELECT estado FROM legal_documento_versiones WHERE id = ?",
                winner.id())).isEqualTo("RETIRADA");
        assertThat(intValue("SELECT count(*) FROM legal_documento_vigentes WHERE documento_version_id = ?",
                winner.id())).isZero();
        assertRejected(() -> transitionDocument(
                        winner.id(), "RETIRADA", "VIGENTE", null, BASE_TIME.plusSeconds(10)),
                "ck_legal_documento_transicion_arista");
        assertRejected(() -> jdbc.update("""
                        UPDATE legal_documento_transiciones
                           SET ocurrido_en = ocurrido_en + INTERVAL '1 second'
                         WHERE documento_version_id = ?
                        """, winner.id()),
                "append-only");
        assertThat(intValue("""
                SELECT count(*) FROM legal_documento_transiciones
                 WHERE documento_version_id = ?
                """, winner.id())).isEqualTo(3);
    }

    @Test
    void snapshotActualEsExactoYLaRetiradaDocumentalActivaFailClosedSinBorrarHistoria() {
        ActiveLegalFixture fixture = createActiveFixture("fail-closed");

        assertThat(intValue("""
                SELECT count(*) FROM legal_requisito_conjuntos_actuales
                 WHERE conjunto_id = ?
                """, fixture.snapshotId())).isEqualTo(1);

        retireDocument(fixture.document(), "Nueva politica pendiente");

        assertThat(intValue("SELECT count(*) FROM legal_requisito_conjuntos_actuales")).isZero();
        assertThat(intValue("SELECT count(*) FROM legal_requisito_conjuntos WHERE id = ?",
                fixture.snapshotId())).isEqualTo(1);
        assertThat(intValue("""
                SELECT count(*) FROM legal_requisito_conjunto_miembros WHERE conjunto_id = ?
                """, fixture.snapshotId())).isEqualTo(1);

        assertRejected(() -> inTransaction(() -> {
            insertCurrentSnapshot(fixture);
            forceConstraints();
        }), "documentos no vigentes o ajenos");
        assertThat(intValue("SELECT count(*) FROM legal_requisito_conjuntos_actuales")).isZero();
    }

    @Test
    void evidenciaExactaEsInmutableYPurgaMetadataComoTombstoneReservandoElNonce() {
        ActiveLegalFixture fixture = createActiveFixture("evidencia");
        Actor actor = createActor("ADMIN");
        byte[] ipNonce = bytes(12, 7);
        byte[] userAgentNonce = bytes(12, 8);
        Instant retention = Instant.now().plusSeconds(2);

        Acceptance acceptance = createAcceptance(
                fixture, actor, BASE_TIME.plusSeconds(100), retention,
                ipNonce, userAgentNonce, false, null);

        assertThat(intValue("""
                SELECT count(*)
                  FROM legal_aceptacion_lotes lote
                  JOIN legal_aceptacion_metadatos metadata ON metadata.lote_id = lote.id
                 WHERE lote.id = ?
                   AND lote.aceptado_en = metadata.capturado_en
                   AND lote.aceptado_en > TIMESTAMPTZ '2026-01-10T12:00:00Z'
                """, acceptance.lotId())).isOne();

        assertRejected(() -> jdbc.update("""
                        UPDATE legal_aceptacion_metadatos_cifrados
                           SET ciphertext = NULL, tag = NULL, longitud_original = NULL,
                               tombstone_en = transaction_timestamp()
                         WHERE lote_id = ?
                        """, acceptance.lotId()),
                "transicion de tombstone cifrado invalida");

        assertThat(intValue("SELECT count(*) FROM legal_aceptaciones WHERE id = ?",
                acceptance.acceptanceId())).isEqualTo(1);
        assertThat(intValue("""
                SELECT count(*) FROM legal_aceptacion_documentos
                 WHERE aceptacion_id = ?
                """, acceptance.acceptanceId())).isEqualTo(1);
        assertRejected(() -> jdbc.update("""
                        UPDATE legal_aceptaciones SET afirmacion = 'otra' WHERE id = ?
                        """, acceptance.acceptanceId()),
                "append-only");

        Actor wrongSnapshotActor = createActor("ADMIN");
        assertRejected(() -> createAcceptance(
                        fixture, wrongSnapshotActor, BASE_TIME.plusSeconds(110),
                        Instant.now().plusSeconds(60),
                        bytes(12, 9), null, true, null),
                "snapshot canonico invalido");

        Actor missingIpActor = createActor("ADMIN");
        assertRejected(() -> createAcceptanceWithoutIp(
                        fixture, missingIpActor, BASE_TIME.plusSeconds(120),
                        Instant.now().plusSeconds(60)),
                "metadata activa incompleta");

        awaitDatabaseClockAfter(retention);
        inTransaction(() -> {
            assertThat(metadataCifradaRepository.tombstonearTodosPorLote(acceptance.lotId()))
                    .isEqualTo(2);
            assertThat(metadataRepository.marcarPurgaCompletada(acceptance.lotId())).isOne();
            forceConstraints();
        });

        assertThat(intValue("""
                SELECT count(*)
                  FROM legal_aceptacion_metadatos_cifrados
                 WHERE lote_id = ? AND tombstone_en IS NOT NULL
                   AND ciphertext IS NULL AND tag IS NULL AND longitud_original IS NULL
                """, acceptance.lotId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT nonce FROM legal_aceptacion_metadatos_cifrados
                 WHERE lote_id = ? AND tipo = 'IP'
                """, byte[].class, acceptance.lotId())).containsExactly(ipNonce);
        assertRejected(() -> jdbc.update("""
                        INSERT INTO legal_aceptacion_metadatos_cifrados
                            (lote_id, tipo, key_version, nonce, ciphertext, tag, longitud_original)
                        VALUES (?, 'USER_AGENT', 1, ?, ?, ?, 20)
                        """, acceptance.lotId(), bytes(12, 10), bytes(8, 45), bytes(16, 46)),
                "junto con su cabecera");

        Actor nonceReuseActor = createActor("ADMIN");
        assertRejected(() -> createAcceptance(
                        fixture, nonceReuseActor, BASE_TIME.plusSeconds(130),
                        Instant.now().plusSeconds(60),
                        ipNonce, null, false, null),
                "uk_legal_aceptacion_metadata_nonce");
    }

    @Test
    void idempotenciaSoloConfirmaExitosEsInmutableYPermitePurgarUnicamenteVencidos() {
        ActiveLegalFixture fixture = createActiveFixture("idempotencia");
        Actor currentActor = createActor("ADMIN");
        Instant completedAt = BASE_TIME;
        Instant expiresAt = Instant.now().plusSeconds(86_460);
        IdempotencyData currentData = new IdempotencyData(
                "ACEPTACION_LEGAL", "/api/legal/acceptances", hex('1'), hex('2'), hex('3'),
                1, completedAt, expiresAt);

        Acceptance current = createAcceptance(
                fixture, currentActor, completedAt, Instant.now().plusSeconds(172_800),
                bytes(12, 20), null, false, currentData);
        Long currentResultId = jdbc.queryForObject("""
                SELECT id FROM legal_idempotencia_resultados WHERE lote_id = ?
                """, Long.class, current.lotId());
        assertThat(intValue("""
                SELECT count(*)
                  FROM legal_idempotencia_resultados resultado
                  JOIN legal_aceptacion_lotes lote ON lote.id = resultado.lote_id
                 WHERE resultado.id = ?
                   AND resultado.completed_at = lote.aceptado_en
                   AND resultado.expires_at >= lote.aceptado_en + INTERVAL '24 hours'
                """, currentResultId)).isOne();
        assertThat(idempotenciaRepository.findVigenteByTuplaHmac(
                TipoOperacionIdempotenteLegal.ACEPTACION_LEGAL,
                currentData.routeTemplate(), currentData.keyVersion(),
                currentData.scopeHmac(), currentData.keyHmac(), Instant.now())).isPresent();
        assertThat(idempotenciaRepository.findVigenteByTuplaHmac(
                TipoOperacionIdempotenteLegal.ACEPTACION_LEGAL,
                currentData.routeTemplate(), currentData.keyVersion(),
                currentData.scopeHmac(), hex('6'), Instant.now())).isEmpty();

        assertRejected(() -> jdbc.update("""
                        UPDATE legal_idempotencia_resultados SET fingerprint_hmac = ? WHERE id = ?
                        """, hex('4'), currentResultId),
                "inmutable");
        assertRejected(() -> jdbc.update(
                        "DELETE FROM legal_idempotencia_resultados WHERE id = ?", currentResultId),
                "antes de expires_at");
        assertRejected(() -> jdbc.update("""
                        INSERT INTO legal_idempotencia_resultados
                            (operacion, route_template, scope_hmac, idempotency_key_hmac,
                             fingerprint_hmac, hmac_key_version, user_id, taller_id, lote_id,
                             completed_at, expires_at)
                        VALUES ('ACEPTACION_LEGAL', '/api/legal/late', ?, ?, ?, 1, ?, ?, ?, ?, ?)
                        """, hex('5'), hex('6'), hex('7'), currentActor.userId(),
                        currentActor.tallerId(), current.lotId(), completedAt, expiresAt),
                "confirmarse con el resultado de negocio");

        Actor duplicateActor = createActor("ADMIN");
        assertRejected(() -> createAcceptance(
                        fixture, duplicateActor, completedAt, Instant.now().plusSeconds(172_800),
                        bytes(12, 21), null, false, currentData),
                "uk_legal_idempotencia_resultado");

        Actor backdatedActor = createActor("ADMIN");
        IdempotencyData alreadyExpiredData = new IdempotencyData(
                "ACEPTACION_LEGAL", "/api/legal/backdated", hex('b'), hex('c'), hex('d'),
                1, BASE_TIME, BASE_TIME.plusSeconds(86_400));
        assertRejected(() -> createAcceptance(
                        fixture, backdatedActor, BASE_TIME,
                        Instant.now().plusSeconds(172_800), bytes(12, 23), null,
                        false, alreadyExpiredData),
                "ck_legal_idempotencia_expiracion");

        Actor expiredActor = createActor("ADMIN");
        Instant oldCompletedAt = BASE_TIME;
        IdempotencyData expiredData = new IdempotencyData(
                "ACEPTACION_LEGAL", "/api/legal/expired", hex('8'), hex('9'), hex('a'),
                1, oldCompletedAt, oldCompletedAt.plusSeconds(86_400));
        Acceptance expired = createAcceptance(
                fixture, expiredActor, oldCompletedAt, Instant.now().plusSeconds(172_800),
                bytes(12, 22), null, false, null);
        insertHistoricalExpiredIdempotency(expiredData, expiredActor, expired.lotId());
        Long expiredResultId = jdbc.queryForObject("""
                SELECT id FROM legal_idempotencia_resultados WHERE lote_id = ?
                """, Long.class, expired.lotId());
        assertThat(idempotenciaRepository.findVigenteByTuplaHmac(
                TipoOperacionIdempotenteLegal.ACEPTACION_LEGAL,
                expiredData.routeTemplate(), expiredData.keyVersion(),
                expiredData.scopeHmac(), expiredData.keyHmac(), Instant.now())).isEmpty();

        assertThat(jdbc.update(
                "DELETE FROM legal_idempotencia_resultados WHERE id = ?", expiredResultId))
                .isEqualTo(1);
        assertThat(intValue("SELECT count(*) FROM legal_idempotencia_resultados WHERE id = ?",
                expiredResultId)).isZero();
    }

    @Test
    void repositoriosExponenCatalogoPublicoHistoricoEHistorialSiempreAisladoPorTenant() {
        UUID catalogPublication = openPublication("catalogo");
        DocumentLine termsLine = addDocumentLine(
                catalogPublication, "catalog-terms", "TERMINOS_SERVICIO");
        DocumentVersion retiredTerms = addDocumentVersion(
                catalogPublication, termsLine, "1", 1, 1,
                BASE_TIME, "REGISTRO");
        DocumentLine privacyLine = addDocumentLine(
                catalogPublication, "catalog-privacy", "POLITICA_PRIVACIDAD");
        DocumentVersion currentPrivacy = addDocumentVersion(
                catalogPublication, privacyLine, "1", 1, 2,
                BASE_TIME, "REGISTRO");
        DocumentLine draftLine = addDocumentLine(
                catalogPublication, "catalog-future", "ACUERDO_TRATAMIENTO_DATOS");
        DocumentVersion merelyPublished = addDocumentVersion(
                catalogPublication, draftLine, "1", 1, 3,
                BASE_TIME, "REGISTRO");
        sealPublication(catalogPublication);

        publishDocument(retiredTerms);
        activateDocument(retiredTerms);
        retireDocument(retiredTerms, "Version historica");
        publishDocument(currentPrivacy);
        activateDocument(currentPrivacy);
        inTransaction(() -> {
            assertThat(documentoVersionRepository.findById(merelyPublished.id()))
                    .get().extracting(item -> item.getEstado())
                    .isEqualTo(EstadoVersionLegal.BORRADOR);
            assertThat(documentoTransicionRepository.insertarTransicion(
                    merelyPublished.id(), EstadoVersionLegal.BORRADOR.name(),
                    EstadoVersionLegal.PUBLICADA.name(), null, null,
                    BASE_TIME.plusSeconds(1))).isOne();
            assertThat(documentoVersionRepository.findById(merelyPublished.id()))
                    .get().extracting(item -> item.getEstado())
                    .isEqualTo(EstadoVersionLegal.PUBLICADA);
        });

        var catalog = documentoVersionRepository.findCatalogoPublico(
                LocaleLegal.ES_AR, null, PageRequest.of(0, 10));
        assertThat(catalog.getContent())
                .extracting(item -> item.getId())
                .containsExactly(retiredTerms.id(), currentPrivacy.id());
        assertThat(catalog.getContent())
                .extracting(item -> item.getEstado())
                .containsExactly(EstadoVersionLegal.RETIRADA, EstadoVersionLegal.VIGENTE);
        assertThat(documentoVersionRepository.findCatalogoPublico(
                LocaleLegal.ES_AR, ContextoLegal.CIERRE_CUENTA, PageRequest.of(0, 10)))
                .isEmpty();
        assertThat(documentoVersionRepository
                .findByLinea_ClaveAndLinea_LocaleAndVersion(
                        termsLine.key(), LocaleLegal.ES_AR, retiredTerms.version()))
                .isPresent();

        ActiveLegalFixture acceptanceFixture = createActiveFixture("repo-history");
        Actor actor = createActor("ADMIN");
        Actor anotherTenant = createActor("ADMIN");
        Acceptance acceptance = createAcceptance(
                acceptanceFixture, actor, BASE_TIME.plusSeconds(500),
                Instant.now().plusSeconds(86_400), bytes(12, 30), null, false, null);

        var ownHistory = aceptacionRepository.findHistorial(
                actor.userId(), actor.tallerId(), null, PageRequest.of(0, 10));
        assertThat(ownHistory.getContent())
                .extracting(item -> item.getAceptacionId())
                .containsExactly(acceptance.acceptanceId());
        assertThat(ownHistory.getContent())
                .extracting(item -> item.getAfirmacion())
                .containsExactly(acceptanceFixture.requirement().statement());
        assertThat(aceptacionDocumentoRepository.findDocumentosByAceptacionIds(
                List.of(acceptance.acceptanceId())))
                .singleElement()
                .satisfies(document -> {
                    assertThat(document.getDocumentoVersionId())
                            .isEqualTo(acceptanceFixture.document().id());
                    assertThat(document.getDocumentoClave())
                            .isEqualTo(acceptanceFixture.document().key());
                });
        assertThat(requisitoDocumentoRepository.findDocumentosByRequisitoVersionIds(
                List.of(acceptanceFixture.requirement().id())))
                .singleElement()
                .satisfies(document -> {
                    assertThat(document.getDocumentoVersionId())
                            .isEqualTo(acceptanceFixture.document().id());
                    assertThat(document.getDocumentoClave())
                            .isEqualTo(acceptanceFixture.document().key());
                });
        assertThat(aceptacionRepository.findHistorial(
                actor.userId(), anotherTenant.tallerId(), null, PageRequest.of(0, 10)))
                .isEmpty();
        assertThat(aceptacionRepository.findByUserIdAndTallerIdAndRequisitoVersionId(
                actor.userId(), actor.tallerId(), acceptanceFixture.requirement().id()))
                .isPresent();
        assertThat(aceptacionRepository.findByUserIdAndTallerIdAndRequisitoVersionId(
                actor.userId(), anotherTenant.tallerId(), acceptanceFixture.requirement().id()))
                .isEmpty();
    }

    private ActiveLegalFixture createActiveFixture(String suffix) {
        UUID publicationId = openPublication(suffix);
        DocumentLine documentLine = addDocumentLine(
                publicationId, suffix + "-document", "TERMINOS_USUARIO");
        DocumentVersion document = addDocumentVersion(
                publicationId, documentLine, "1.0.0", 1, 1,
                BASE_TIME, "REGISTRO");
        RequirementLine requirementLine = addRequirementLine(
                publicationId, suffix + "-requirement", "REGISTRO", "ACEPTACION",
                "ADMIN_TITULAR");
        RequirementVersion requirement = addRequirementVersion(
                publicationId, requirementLine, "1.0.0", 1, 1, true, document);
        UUID snapshotId = addSnapshot(
                publicationId, "REGISTRO", "ADMIN_TITULAR", REVISION,
                List.of(requirement));
        sealPublication(publicationId);
        publishDocument(document);
        activateDocument(document);
        publishRequirement(requirement);
        inTransaction(() -> {
            transitionRequirement(requirement.id(), "PUBLICADA", "VIGENTE", null,
                    BASE_TIME.plusSeconds(4));
            insertCurrentSnapshot(new ActiveLegalFixture(
                    publicationId, document, requirement, snapshotId, REVISION));
            forceConstraints();
        });
        return new ActiveLegalFixture(
                publicationId, document, requirement, snapshotId, REVISION);
    }

    private UUID openPublication(String suffix) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO legal_publicaciones
                    (id, publication_external_id, schema_version, locale,
                     manifest_sha256, manifest_canonico, razon_social, cuit,
                     domicilio_legal, jurisdiccion, horario_atencion,
                     email_legal, email_privacidad, email_soporte,
                     revision_legal_estado, revision_contable_estado, importado_en)
                VALUES (?, ?, 1, 'es-AR', ?, '{}', 'OrdenFix SAS', '30712345678',
                        'Calle Legal 123', 'CABA', 'Lunes a viernes de 9 a 18',
                        'legal@ordenfix.test', 'privacidad@ordenfix.test',
                        'soporte@ordenfix.test', 'PENDIENTE', 'PENDIENTE', ?)
                """, id, "publication-" + suffix + "-" + id, hex('a'), BASE_TIME);
        return id;
    }

    private DocumentLine addDocumentLine(UUID publicationId, String suffix, String type) {
        UUID id = UUID.randomUUID();
        String key = uniqueKey(suffix);
        jdbc.update("""
                INSERT INTO legal_documento_lineas
                    (id, clave, tipo, locale, publicacion_intro_id, creado_en)
                VALUES (?, ?, ?, 'es-AR', ?, ?)
                """, id, key, type, publicationId, BASE_TIME);
        return new DocumentLine(id, publicationId, key, type);
    }

    private DocumentVersion addDocumentVersion(
            UUID publicationId,
            DocumentLine line,
            String version,
            int lineageOrdinal,
            int manifestOrdinal,
            Instant effectiveAt,
            String... contexts) {
        UUID id = UUID.randomUUID();
        String title = "Documento " + line.key();
        String sha = hex((char) ('b' + Math.floorMod(manifestOrdinal, 4)));
        jdbc.update("""
                INSERT INTO legal_documento_versiones
                    (id, documento_linea_id, publicacion_intro_id, version,
                     lineage_ordinal, titulo, contenido_markdown, sha256,
                     vigente_desde, requires_reacceptance)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, false)
                """, id, line.id(), publicationId, version, lineageOrdinal,
                title, "# " + title, sha, effectiveAt);
        for (String context : contexts) {
            jdbc.update("""
                    INSERT INTO legal_documento_contextos (documento_version_id, contexto)
                    VALUES (?, ?)
                    """, id, context);
        }
        jdbc.update("""
                INSERT INTO legal_publicacion_documentos
                    (publicacion_id, documento_version_id, manifest_ordinal)
                VALUES (?, ?, ?)
                """, publicationId, id, manifestOrdinal);
        return new DocumentVersion(
                id, line.id(), publicationId, line.key(), line.type(), version,
                title, sha, lineageOrdinal, manifestOrdinal, List.of(contexts));
    }

    private RequirementLine addRequirementLine(
            UUID publicationId,
            String suffix,
            String context,
            String actType,
            String... audiences) {
        UUID id = UUID.randomUUID();
        String key = uniqueKey(suffix);
        jdbc.update("""
                INSERT INTO legal_requisito_lineas
                    (id, clave, locale, contexto, tipo_acto, publicacion_intro_id, creado_en)
                VALUES (?, ?, 'es-AR', ?, ?, ?, ?)
                """, id, key, context, actType, publicationId, BASE_TIME);
        for (String audience : audiences) {
            jdbc.update("""
                    INSERT INTO legal_requisito_audiencias (requisito_linea_id, audiencia)
                    VALUES (?, ?)
                    """, id, audience);
        }
        return new RequirementLine(id, publicationId, key, context, actType, List.of(audiences));
    }

    private RequirementVersion addRequirementVersion(
            UUID publicationId,
            RequirementLine line,
            String version,
            int lineageOrdinal,
            int manifestOrdinal,
            boolean required,
            DocumentVersion... documents) {
        UUID id = UUID.randomUUID();
        String statement = "Acepto " + line.key();
        String statementSha = hex('d');
        jdbc.update("""
                INSERT INTO legal_requisito_versiones
                    (id, requisito_linea_id, publicacion_intro_id, version,
                     lineage_ordinal, afirmacion, afirmacion_sha256, requerido,
                     requires_reacceptance)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, false)
                """, id, line.id(), publicationId, version, lineageOrdinal,
                statement, statementSha, required);
        for (int index = 0; index < documents.length; index++) {
            jdbc.update("""
                    INSERT INTO legal_requisito_documentos
                        (requisito_version_id, documento_version_id, documento_ordinal)
                    VALUES (?, ?, ?)
                    """, id, documents[index].id(), index + 1);
        }
        jdbc.update("""
                INSERT INTO legal_publicacion_requisitos
                    (publicacion_id, requisito_version_id, manifest_ordinal)
                VALUES (?, ?, ?)
                """, publicationId, id, manifestOrdinal);
        return new RequirementVersion(
                id, line.id(), publicationId, line.key(), version, line.context(), line.actType(),
                statement, statementSha, required, lineageOrdinal, manifestOrdinal,
                line.audiences(), List.of(documents));
    }

    private UUID addSnapshot(
            UUID publicationId,
            String context,
            String audience,
            String revision,
            List<RequirementVersion> requirements) {
        UUID snapshotId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO legal_requisito_conjuntos
                    (id, publicacion_id, locale, contexto, audiencia,
                     required_set_revision, creado_en)
                VALUES (?, ?, 'es-AR', ?, ?, ?, ?)
                """, snapshotId, publicationId, context, audience, revision, BASE_TIME);
        for (RequirementVersion requirement : requirements) {
            jdbc.update("""
                    INSERT INTO legal_requisito_conjunto_miembros
                        (conjunto_id, publicacion_id, requisito_version_id,
                         requisito_linea_id, manifest_ordinal)
                    VALUES (?, ?, ?, ?, ?)
                    """, snapshotId, publicationId, requirement.id(), requirement.lineId(),
                    requirement.manifestOrdinal());
        }
        return snapshotId;
    }

    private void sealPublication(UUID publicationId) {
        inTransaction(() -> {
            jdbc.update("""
                    UPDATE legal_publicaciones
                       SET estado_construccion = 'SELLADO', sellado_en = ?
                     WHERE id = ?
                    """, BASE_TIME.plusSeconds(1), publicationId);
            forceConstraints();
        });
    }

    private void publishDocument(DocumentVersion document) {
        inTransaction(() -> transitionDocument(
                document.id(), "BORRADOR", "PUBLICADA", null, BASE_TIME.plusSeconds(2)));
    }

    private void activateDocument(DocumentVersion document) {
        inTransaction(() -> {
            transitionDocument(
                    document.id(), "PUBLICADA", "VIGENTE", null, BASE_TIME.plusSeconds(3));
            for (String context : document.contexts()) {
                jdbc.update("""
                        INSERT INTO legal_documento_vigentes
                            (tipo, locale, contexto, documento_version_id,
                             documento_linea_id, publicacion_id, estado_documento)
                        VALUES (?, 'es-AR', ?, ?, ?, ?, 'VIGENTE')
                        """, document.type(), context, document.id(), document.lineId(),
                        document.publicationId());
            }
            forceConstraints();
        });
    }

    private void retireDocument(DocumentVersion document, String reason) {
        inTransaction(() -> {
            jdbc.update("""
                    DELETE FROM legal_documento_vigentes WHERE documento_version_id = ?
                    """, document.id());
            transitionDocument(
                    document.id(), "VIGENTE", "RETIRADA", reason, BASE_TIME.plusSeconds(5));
            forceConstraints();
        });
    }

    private void transitionDocument(
            UUID documentId,
            String from,
            String to,
            String reason,
            Instant occurredAt) {
        jdbc.update("""
                INSERT INTO legal_documento_transiciones
                    (documento_version_id, estado_anterior, estado_nuevo, motivo, ocurrido_en)
                VALUES (?, ?, ?, ?, ?)
                """, documentId, from, to, reason, occurredAt);
    }

    private void publishRequirement(RequirementVersion requirement) {
        inTransaction(() -> transitionRequirement(
                requirement.id(), "BORRADOR", "PUBLICADA", null, BASE_TIME.plusSeconds(3)));
    }

    private void transitionRequirement(
            UUID requirementId,
            String from,
            String to,
            String reason,
            Instant occurredAt) {
        jdbc.update("""
                INSERT INTO legal_requisito_transiciones
                    (requisito_version_id, estado_anterior, estado_nuevo, motivo, ocurrido_en)
                VALUES (?, ?, ?, ?, ?)
                """, requirementId, from, to, reason, occurredAt);
    }

    private void insertCurrentSnapshot(ActiveLegalFixture fixture) {
        jdbc.update("""
                INSERT INTO legal_requisito_conjuntos_actuales
                    (locale, contexto, audiencia, conjunto_id, publicacion_id, actualizado_en)
                VALUES ('es-AR', 'REGISTRO', 'ADMIN_TITULAR', ?, ?, ?)
                """, fixture.snapshotId(), fixture.publicationId(), BASE_TIME.plusSeconds(4));
    }

    private Actor createActor(String role) {
        String token = UUID.randomUUID().toString();
        Long workshopId = jdbc.queryForObject("""
                INSERT INTO talleres (nombre) VALUES (?) RETURNING id
                """, Long.class, "Taller legal " + token);
        Long userId = jdbc.queryForObject("""
                INSERT INTO users (username, password, email, role, taller_id)
                VALUES (?, 'hash-test', ?, ?, ?) RETURNING id
                """, Long.class, "user-" + token.substring(0, 8),
                token + "@ordenfix.test", role, workshopId);
        return new Actor(userId, workshopId, role);
    }

    private Acceptance createAcceptance(
            ActiveLegalFixture fixture,
            Actor actor,
            Instant acceptedAt,
            Instant retainUntil,
            byte[] ipNonce,
            byte[] userAgentNonce,
            boolean corruptCanonicalSnapshot,
            IdempotencyData idempotency) {
        UUID lotId = UUID.randomUUID();
        UUID acceptanceId = UUID.randomUUID();
        inTransaction(() -> {
            insertAcceptanceRows(
                    fixture, actor, acceptedAt, retainUntil, ipNonce, userAgentNonce,
                    corruptCanonicalSnapshot, lotId, acceptanceId);
            if (idempotency != null) {
                insertIdempotency(idempotency, actor, lotId);
            }
            forceConstraints();
        });
        return new Acceptance(lotId, acceptanceId);
    }

    private void insertAcceptanceRows(
            ActiveLegalFixture fixture,
            Actor actor,
            Instant acceptedAt,
            Instant retainUntil,
            byte[] ipNonce,
            byte[] userAgentNonce,
            boolean corruptCanonicalSnapshot,
            UUID lotId,
            UUID acceptanceId) {
        RequirementVersion requirement = fixture.requirement();
        DocumentVersion document = fixture.document();
        jdbc.update("""
                INSERT INTO legal_aceptacion_lotes
                    (id, user_id, taller_id, rol_wire, audiencia,
                     required_set_revision, aceptado_en)
                VALUES (?, ?, ?, ?, 'ADMIN_TITULAR', ?, ?)
                """, lotId, actor.userId(), actor.tallerId(), actor.role(),
                fixture.revision(), acceptedAt);
        jdbc.update("""
                INSERT INTO legal_aceptaciones
                    (id, lote_id, user_id, taller_id, requisito_version_id,
                     requisito_clave, requisito_version, contexto, tipo_acto,
                     afirmacion, afirmacion_sha256, requerido)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, acceptanceId, lotId, actor.userId(), actor.tallerId(), requirement.id(),
                requirement.key(), requirement.version(), requirement.context(), requirement.actType(),
                requirement.statement(), corruptCanonicalSnapshot ? hex('0') : requirement.statementSha(),
                requirement.required());
        jdbc.update("""
                INSERT INTO legal_aceptacion_documentos
                    (aceptacion_id, documento_ordinal, documento_version_id,
                     documento_clave, tipo, version, titulo, sha256)
                VALUES (?, 1, ?, ?, ?, ?, ?, ?)
                """, acceptanceId, document.id(), document.key(), document.type(),
                document.version(), document.title(), document.sha());
        jdbc.update("""
                INSERT INTO legal_aceptacion_metadatos
                    (lote_id, capturado_en, retener_hasta)
                VALUES (?, ?, ?)
                """, lotId, acceptedAt, retainUntil);
        jdbc.update("""
                INSERT INTO legal_aceptacion_metadatos_cifrados
                    (lote_id, tipo, key_version, nonce, ciphertext, tag, longitud_original)
                VALUES (?, 'IP', 1, ?, ?, ?, 9)
                """, lotId, ipNonce, bytes(8, 41), bytes(16, 42));
        if (userAgentNonce != null) {
            jdbc.update("""
                    INSERT INTO legal_aceptacion_metadatos_cifrados
                        (lote_id, tipo, key_version, nonce, ciphertext, tag, longitud_original)
                    VALUES (?, 'USER_AGENT', 1, ?, ?, ?, 80)
                    """, lotId, userAgentNonce, bytes(12, 43), bytes(16, 44));
        }
    }

    private void createAcceptanceWithoutIp(
            ActiveLegalFixture fixture,
            Actor actor,
            Instant acceptedAt,
            Instant retainUntil) {
        UUID lotId = UUID.randomUUID();
        UUID acceptanceId = UUID.randomUUID();
        inTransaction(() -> {
            RequirementVersion requirement = fixture.requirement();
            DocumentVersion document = fixture.document();
            jdbc.update("""
                    INSERT INTO legal_aceptacion_lotes
                        (id, user_id, taller_id, rol_wire, audiencia,
                         required_set_revision, aceptado_en)
                    VALUES (?, ?, ?, 'ADMIN', 'ADMIN_TITULAR', ?, ?)
                    """, lotId, actor.userId(), actor.tallerId(), fixture.revision(), acceptedAt);
            jdbc.update("""
                    INSERT INTO legal_aceptaciones
                        (id, lote_id, user_id, taller_id, requisito_version_id,
                         requisito_clave, requisito_version, contexto, tipo_acto,
                         afirmacion, afirmacion_sha256, requerido)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, acceptanceId, lotId, actor.userId(), actor.tallerId(), requirement.id(),
                    requirement.key(), requirement.version(), requirement.context(), requirement.actType(),
                    requirement.statement(), requirement.statementSha(), requirement.required());
            jdbc.update("""
                    INSERT INTO legal_aceptacion_documentos
                        (aceptacion_id, documento_ordinal, documento_version_id,
                         documento_clave, tipo, version, titulo, sha256)
                    VALUES (?, 1, ?, ?, ?, ?, ?, ?)
                    """, acceptanceId, document.id(), document.key(), document.type(),
                    document.version(), document.title(), document.sha());
            jdbc.update("""
                    INSERT INTO legal_aceptacion_metadatos
                        (lote_id, capturado_en, retener_hasta)
                    VALUES (?, ?, ?)
                    """, lotId, acceptedAt, retainUntil);
            forceConstraints();
        });
    }

    private void insertIdempotency(IdempotencyData data, Actor actor, UUID lotId) {
        jdbc.update("""
                INSERT INTO legal_idempotencia_resultados
                    (operacion, route_template, scope_hmac, idempotency_key_hmac,
                     fingerprint_hmac, hmac_key_version, user_id, taller_id, lote_id,
                     completed_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, data.operation(), data.routeTemplate(), data.scopeHmac(), data.keyHmac(),
                data.fingerprintHmac(), data.keyVersion(), actor.userId(), actor.tallerId(), lotId,
                data.completedAt(), data.expiresAt());
    }

    private void insertHistoricalExpiredIdempotency(
            IdempotencyData data, Actor actor, UUID lotId) {
        inTransaction(() -> {
            // Fixture histórico: V27 no posee filas legales previas y un resultado nuevo nunca
            // puede nacer vencido. Se omite sólo el trigger de INSERT para probar la purga de una
            // fila que, en producción, habría envejecido durante más de 24 horas.
            jdbc.execute("SET LOCAL session_replication_role = 'replica'");
            insertIdempotency(data, actor, lotId);
            jdbc.execute("SET LOCAL session_replication_role = 'origin'");
        });
    }

    private void forceConstraints() {
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
    }

    private void awaitDatabaseClockAfter(Instant target) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            OffsetDateTime databaseNow = jdbc.queryForObject(
                    "SELECT clock_timestamp()", OffsetDateTime.class);
            if (databaseNow != null && !databaseNow.toInstant().isBefore(target)) {
                return;
            }
            LockSupport.parkNanos(java.time.Duration.ofMillis(20).toNanos());
        }
        throw new AssertionError("El reloj PostgreSQL no alcanzo la fecha de retencion de la prueba");
    }

    private void inTransaction(Runnable action) {
        transaction.executeWithoutResult(status -> action.run());
    }

    private void assertRejected(Runnable action, String expectedMessagePart) {
        assertThatThrownBy(action::run)
                .isInstanceOf(RuntimeException.class)
                .satisfies(error -> assertThat(rootCause(error).getMessage())
                        .containsIgnoringCase(expectedMessagePart));
    }

    private int intValue(String sql, Object... parameters) {
        Integer value = jdbc.queryForObject(sql, Integer.class, parameters);
        return value == null ? 0 : value;
    }

    private String stringValue(String sql, Object... parameters) {
        return jdbc.queryForObject(sql, String.class, parameters);
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String uniqueKey(String suffix) {
        return suffix + "-" + UUID.randomUUID();
    }

    private static String hexRevision(char value) {
        return "sha256:" + hex(value);
    }

    private static String hex(char value) {
        return String.valueOf(value).repeat(64);
    }

    private static byte[] bytes(int length, int value) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static final class InstantAwareJdbcTemplate extends JdbcTemplate {

        private InstantAwareJdbcTemplate(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public int update(String sql, Object... parameters) {
            Object[] postgresParameters = Arrays.stream(parameters)
                    .map(parameter -> parameter instanceof Instant instant
                            ? OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)
                            : parameter)
                    .toArray();
            return super.update(sql, postgresParameters);
        }
    }

    private record DocumentLine(
            UUID id,
            UUID publicationId,
            String key,
            String type) {
    }

    private record DocumentVersion(
            UUID id,
            UUID lineId,
            UUID publicationId,
            String key,
            String type,
            String version,
            String title,
            String sha,
            int lineageOrdinal,
            int manifestOrdinal,
            List<String> contexts) {
    }

    private record RequirementLine(
            UUID id,
            UUID publicationId,
            String key,
            String context,
            String actType,
            List<String> audiences) {
    }

    private record RequirementVersion(
            UUID id,
            UUID lineId,
            UUID publicationId,
            String key,
            String version,
            String context,
            String actType,
            String statement,
            String statementSha,
            boolean required,
            int lineageOrdinal,
            int manifestOrdinal,
            List<String> audiences,
            List<DocumentVersion> documents) {
    }

    private record ActiveLegalFixture(
            UUID publicationId,
            DocumentVersion document,
            RequirementVersion requirement,
            UUID snapshotId,
            String revision) {
    }

    private record Actor(Long userId, Long tallerId, String role) {
    }

    private record Acceptance(UUID lotId, UUID acceptanceId) {
    }

    private record IdempotencyData(
            String operation,
            String routeTemplate,
            String scopeHmac,
            String keyHmac,
            String fingerprintHmac,
            int keyVersion,
            Instant completedAt,
            Instant expiresAt) {
    }
}
