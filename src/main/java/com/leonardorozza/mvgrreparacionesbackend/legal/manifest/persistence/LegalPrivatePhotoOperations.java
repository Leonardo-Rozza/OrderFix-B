package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.config.security.AuthenticatedUserPrincipal;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.*;
import com.leonardorozza.mvgrreparacionesbackend.legal.http.LegalPrivateRequirementsResponses;
import com.leonardorozza.mvgrreparacionesbackend.persistence.entity.enums.*;
import com.leonardorozza.mvgrreparacionesbackend.photos.*;
import com.leonardorozza.mvgrreparacionesbackend.photos.storage.PrivatePhotoStorage;
import com.leonardorozza.mvgrreparacionesbackend.photos.storage.PrivatePhotoStorage.StoredAsset;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import static com.leonardorozza.mvgrreparacionesbackend.photos.PrivatePhotoDtos.*;

/** Private storage effects occur between, never inside, these commit-sensitive transactions. */
public final class LegalPrivatePhotoOperations implements PrivatePhotoService {
    private final HikariDataSource pool;
    private final LegalPrivateRequirementsDataSource source;
    private final JdbcTemplate jdbc;
    private final LegalAcceptanceTransactionBoundary boundary;
    private final LegalIdempotencyCoordinator coordinator;
    private final LegalAcceptanceWriter writer;
    private final LegalAcceptanceEvidenceReader evidence;
    private final LegalPrivateRequirementsReader reader;
    private final LegalRequiredSetAggregateStore aggregates;
    private final LegalAcceptanceSelection selector=new LegalAcceptanceSelection();
    private final PrivatePhotoStorage storage;
    private final LegalPrivatePhotoDeletionStore deletions;
    private final long retentionSeconds;
    private CleanupCursor cleanupCursor;
    private final LegalApplicableScopeResolver scopes=new LegalApplicableScopeResolver((profile,audience)-> {
        if(profile!=PerfilAgregadoLegal.AUTHENTICATED_PENDING || audience==null) throw PrivatePhotoException.unavailable();
        return List.of(ContextoLegal.USO_CONTINUADO,ContextoLegal.ATESTACION_FOTOS);
    });

    public static LegalPrivatePhotoOperations open(Environment environment,PrivatePhotoStorage storage) {
        HikariDataSource pool=null;
        try {
            var keys=LegalAcceptanceKeyConfiguration.from(environment);
            Duration retention=Duration.parse(environment.getRequiredProperty("photos.private.retention"));
            if(retention.getNano()!=0 || retention.getSeconds()<=900 || retention.getSeconds()>315_360_000) throw PrivatePhotoException.unavailable();
            String url=required(environment,"jdbc-url"),user=required(environment,"username"),password=required(environment,"password");
            if(!url.startsWith("jdbc:postgresql://") || url.contains("#")) throw PrivatePhotoException.unavailable();
            if(url.contains("?")) for(String option:url.substring(url.indexOf('?')+1).split("&")) {
                String name=java.net.URLDecoder.decode(option.split("=",2)[0],java.nio.charset.StandardCharsets.UTF_8);
                if(!Set.of("sslmode","sslrootcert","sslcert","sslkey","sslpassword").contains(name)) throw PrivatePhotoException.unavailable();
            }
            HikariConfig config=new HikariConfig();
            config.setPoolName("private-photos");config.setJdbcUrl(url);config.setUsername(user);config.setPassword(password);
            config.setDriverClassName("org.postgresql.Driver");config.setMinimumIdle(0);config.setMaximumPoolSize(2);
            config.setConnectionTimeout(1_000);config.setValidationTimeout(1_000);config.setInitializationFailTimeout(-1);
            config.addDataSourceProperty("connectTimeout","1");config.addDataSourceProperty("loginTimeout","1");
            config.addDataSourceProperty("socketTimeout","6");config.addDataSourceProperty("cancelSignalTimeout","1");
            config.addDataSourceProperty("ApplicationName","ordenfix-private-photos");
            pool=new HikariDataSource(config);
            return new LegalPrivatePhotoOperations(pool,keys,user,retention.getSeconds(),Objects.requireNonNull(storage));
        } catch(RuntimeException failure) {
            if(pool!=null) pool.close();
            throw new IllegalArgumentException("La configuración de fotos privadas es inválida.");
        }
    }
    private static String required(Environment env,String name) {
        String value=env.getRequiredProperty("photos.private."+name);
        if(value.isBlank()) throw PrivatePhotoException.unavailable();return value;
    }
    private LegalPrivatePhotoOperations(HikariDataSource pool,LegalAcceptanceKeyConfiguration keys,String role,
            long retentionSeconds,PrivatePhotoStorage storage) {
        this.pool=pool;this.storage=storage;this.retentionSeconds=retentionSeconds;
        source=new LegalPrivateRequirementsDataSource(pool,Duration.ofSeconds(15),System::nanoTime,1_000);
        jdbc=new JdbcTemplate(source);
        deletions=new LegalPrivatePhotoDeletionStore(jdbc);
        var manager=new DataSourceTransactionManager(source);manager.setRollbackOnCommitFailure(false);
        var template=new TransactionTemplate(manager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);template.setTimeout(15);template.setReadOnly(false);
        var schema=new LegalV29AcceptanceSchemaVerifier(jdbc,"public");
        var privileges=new LegalAcceptancePrivilegeVerifier(jdbc,role,"public",true);
        boundary=new LegalAcceptanceTransactionBoundary(jdbc,source,template,new LegalDatabaseBudgets(15,5,1,1),schema,privileges);
        var results=new LegalIdempotencyResultStore(jdbc);
        coordinator=new LegalIdempotencyCoordinator(jdbc,schema,keys.keyring(),results);
        writer=new LegalAcceptanceWriter(jdbc,keys.codec(),keys.retentionPolicy(),results);
        evidence=new LegalAcceptanceEvidenceReader(jdbc);reader=new LegalPrivateRequirementsReader(jdbc);
        var revisions=new LegalRequiredSetAggregateRevisionCalculator();var provenance=new LegalRequiredSetAggregateProvenanceCalculator();
        aggregates=new LegalRequiredSetAggregateStore(jdbc,revisions,provenance,new LegalRequiredSetAggregateReplayVerifier(jdbc,revisions,provenance));
    }

    @Override public Requirements requirements(AuthenticatedUserPrincipal principal,long repair) {
        return tx(principal,repair,(actor,deadline)-> {
            var observed=editorial(deadline);lockActor(actor,false);lockRepair(actor,repair);
            return current(actor,observed,deadline).wire();
        });
    }

    @Override public Result<Intention> create(AuthenticatedUserPrincipal principal,long repair,String key,Create input,LegalRequestMetadata metadata) {
        return tx(principal,repair,(actor,deadline)-> {
            // Shape is retained separately from semantic confirmation; no provider operation precedes it.
            if(input==null || metadata==null) throw PrivatePhotoException.invalid();
            try { LegalAcceptanceCommandValidator.requireIdempotencyKey(key); }
            catch(IllegalArgumentException failure) { throw PrivatePhotoException.invalid(); }
            if(input.atestacion()==null || !input.atestacion().confirmada()) {
                var observed=editorial(deadline);lockActor(actor,false);lockRepair(actor,repair);
                throw PrivatePhotoException.required(current(actor,observed,deadline).wire());
            }
            if(!"AUTORIZACION_DATOS_CLIENTE".equals(input.atestacion().tipo())
                    || !List.of("FOTOS").equals(input.atestacion().alcances())) throw PrivatePhotoException.invalid();
            LegalAcceptanceCommand command;
            try {
                command=LegalAcceptanceCommandValidator.photo(actor,input.requiredSetRevision(),input.aceptacionesLegales(),
                        new LegalAcceptanceCommand.PhotoContext(repair,input.nombre(),input.mimeType(),input.bytes(),input.sha256(),
                                input.momento()==null?null:input.momento().name()));
            } catch(IllegalArgumentException failure) { throw PrivatePhotoException.invalid(); }
            var reservation=coordinator.reserve(command,key,deadline::remainingMillis);
            if(reservation.replay().isPresent()) {
                // Ledger replay has already checked HMAC, durable canonical evidence and actor state.
                List<Row> found=new ArrayList<>();
                for(var candidate:reservation.candidates()) found.addAll(jdbc.query(SELECT+" WHERE scope_hmac=? AND key_hmac=?",
                        (rs,n)->row(rs),candidate.scopeHmac(),candidate.idempotencyKeyHmac()));
                if(found.size()!=1) throw PrivatePhotoException.unavailable();
                Row row=found.getFirst();requireContext(row,actor,repair,true);
                if(!row.name().equals(input.nombre()) || !row.mime().equals(input.mimeType()) || row.bytes()!=input.bytes()
                        || !row.sha().equals(input.sha256()) || row.moment()!=input.momento()
                        || reservation.candidates().stream().noneMatch(c->c.keyVersion()==row.keyVersion()
                            && c.scopeHmac().equals(row.scope()) && c.idempotencyKeyHmac().equals(row.keyHmac())
                            && c.matchesFingerprint(row.fingerprint())))throw PrivatePhotoException.unavailable();
                var replayIds=reservation.replay().orElseThrow().acceptanceIds();
                if(replayIds.isEmpty())throw PrivatePhotoException.unavailable();
                var canonicalPhotoIds=jdbc.queryForList("SELECT id FROM public.legal_aceptaciones WHERE contexto='ATESTACION_FOTOS' AND id IN ("
                        +String.join(",",Collections.nCopies(replayIds.size(),"?"))+")",UUID.class,replayIds.toArray());
                var linkedIds=jdbc.queryForList("SELECT aceptacion_id FROM public.reparacion_foto_atestaciones WHERE foto_id=? AND user_id=? AND taller_id=?",UUID.class,row.id(),actor.userId(),actor.tallerId());
                if(canonicalPhotoIds.isEmpty() || !new HashSet<>(canonicalPhotoIds).equals(new HashSet<>(linkedIds)))throw PrivatePhotoException.unavailable();
                return new Result<>(view(row),true);
            }
            var gate=boundary.enterEditorialShared(reservation,deadline);
            lockActor(actor,true);reservation.requireWriteActor(actor);lockRepair(actor,repair,true);
            Long count=jdbc.queryForObject("SELECT count(*) FROM public.reparacion_fotos_privadas WHERE reparacion_id=? AND taller_id=? AND estado<>'ELIMINADA'",Long.class,repair,actor.tallerId());
            if(count==null)throw PrivatePhotoException.unavailable();
            if(count>=100)throw PrivatePhotoException.of(HttpStatus.CONFLICT,"FOTO_LIMITE_ALCANZADO");
            var observed=new LegalEditorialTimeBoundary(gate.transactionAt(),now());
            Current current=current(actor,observed,deadline);
            if(!command.requiredSetRevision().equals(current.legal().requiredSetRevision())) throw PrivatePhotoException.stale(current.wire());
            Set<UUID> photoRequirements=new HashSet<>();
            current.legal().snapshot().requirements().stream().filter(r->r.context()==ContextoLegal.ATESTACION_FOTOS)
                    .forEach(r->{if(r.actType()!=TipoActoLegal.DECLARACION)throw PrivatePhotoException.unavailable();photoRequirements.add(r.versionId());});
            if(photoRequirements.isEmpty() || command.acceptances().stream().filter(a->photoRequirements.contains(a.requisitoVersionId()) && a.confirmado())
                    .map(LegalAcceptanceCommand.Acceptance::requisitoVersionId).distinct().count()!=photoRequirements.size())
                throw PrivatePhotoException.required(current.wire());
            var existing=evidence.read(reservation,actor);
            LegalAcceptanceSelection.Selection chosen;
            try { chosen=selector.select(command,current.legal(),existing); }
            catch(LegalAcceptanceValidationException invalid) { throw PrivatePhotoException.invalid(); }
            var result=writer.write(reservation,actor,current.aggregate(),chosen,metadata);
            UUID id=UUID.randomUUID();var fingerprint=reservation.activeFingerprint();
            // Store the object identity before any external side effect, including an uncertain upload ACK.
            jdbc.update("""
                    INSERT INTO public.reparacion_fotos_privadas
                    (id,reparacion_id,reparacion_original_id,taller_id,user_id,rol_wire,nombre,mime_type,bytes,sha256,momento,
                     estado,expira_en,retener_hasta,object_key,scope_hmac,key_hmac,fingerprint_hmac,hmac_key_version)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,'AUTORIZADA',statement_timestamp()+interval '15 minutes',
                           statement_timestamp()+?*interval '1 second',?,?,?,?,?)
                    """,id,repair,repair,actor.tallerId(),actor.userId(),actor.role().name(),input.nombre(),input.mimeType(),input.bytes(),input.sha256(),
                    input.momento().name(),retentionSeconds,"ordenfix-private/"+id,fingerprint.scopeHmac(),fingerprint.idempotencyKeyHmac(),
                    fingerprint.fingerprintHmac(),fingerprint.keyVersion());
            int linked=0;
            for(UUID acceptance:result.acceptanceIds()) linked+=jdbc.update("""
                    INSERT INTO public.reparacion_foto_atestaciones(foto_id,aceptacion_id,user_id,taller_id,alcance)
                    SELECT ?,id,user_id,taller_id,'FOTOS' FROM public.legal_aceptaciones
                    WHERE id=? AND user_id=? AND taller_id=? AND contexto='ATESTACION_FOTOS'
                    """,id,acceptance,actor.userId(),actor.tallerId());
            if(linked!=photoRequirements.size())throw PrivatePhotoException.unavailable();
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");deadline.check();
            return new Result<>(view(load(id,actor,repair,false)),false);
        });
    }

    @Override public Intention intention(AuthenticatedUserPrincipal principal,long repair,UUID id) {
        return tx(principal,repair,(actor,deadline)-> {lockActor(actor,false);return view(load(id,actor,repair,false));});
    }
    @Override public Intention upload(AuthenticatedUserPrincipal principal,long repair,UUID id,String mime,byte[] bytes) {
        Lease lease=claim(principal,repair,id,true);
        Row row=lease.row();
        try {
            if(!row.mime().equals(mime))throw PrivatePhotoException.invalid();
            PrivatePhotoImageValidator.validate(mime,bytes,row.bytes(),row.sha());
            StoredAsset asset=storage.find(row.key()).orElseGet(()->storage.upload(row.key(),row.mime(),bytes));
            accredit(asset,row);
            byte[] actual=storage.read(asset,PrivatePhotoImageValidator.MAX_BYTES);
            PrivatePhotoImageValidator.validate(row.mime(),actual,row.bytes(),row.sha());
            return tx(principal,repair,(actor,deadline)-> {
                lockActor(actor,false);Row locked=load(id,actor,repair,true);requireLease(locked,lease);
                requireLive(locked);
                jdbc.update("UPDATE public.reparacion_fotos_privadas SET estado='SUBIDA',asset_id=?,asset_version=?,lease_id=NULL,lease_hasta=NULL WHERE id=?",
                        asset.assetId(),asset.version(),id);
                return view(load(id,actor,repair,false));
            });
        } catch(RuntimeException failure) {
            release(principal,repair,lease);
            if(failure instanceof PrivatePhotoException typed)throw typed;
            throw PrivatePhotoException.unavailable();
        }
    }
    @Override public Result<Photo> finish(AuthenticatedUserPrincipal principal,long repair,UUID id) {
        Result<Photo> existing=tx(principal,repair,(actor,deadline)-> {
            lockActor(actor,false);Row row=load(id,actor,repair,false);requireContext(row,actor,repair,true);
            return new Result<>(row.state().equals("ASOCIADA")?photo(row):null,row.state().equals("ASOCIADA"));
        });
        if(existing.reused())return existing;
        Lease lease=claim(principal,repair,id,false);Row row=lease.row();
        try {
            StoredAsset asset=storage.find(row.key()).orElseThrow(PrivatePhotoException::unavailable);
            accredit(asset,row);if(!Objects.equals(asset.assetId(),row.assetId()) || !Objects.equals(asset.version(),row.version()))throw PrivatePhotoException.unavailable();
            PrivatePhotoImageValidator.validate(row.mime(),storage.read(asset,PrivatePhotoImageValidator.MAX_BYTES),row.bytes(),row.sha());
            return tx(principal,repair,(actor,deadline)-> {
                lockActor(actor,false);Row locked=load(id,actor,repair,true);requireLease(locked,lease);requireLive(locked);
                if(!locked.state().equals("SUBIDA"))throw PrivatePhotoException.unavailable();
                jdbc.update("UPDATE public.reparacion_fotos_privadas SET estado='ASOCIADA',asociada_en=statement_timestamp(),lease_id=NULL,lease_hasta=NULL WHERE id=?",id);
                return new Result<>(photo(load(id,actor,repair,false)),false);
            });
        } catch(RuntimeException failure) {
            release(principal,repair,lease);throw failure instanceof PrivatePhotoException typed?typed:PrivatePhotoException.unavailable();
        }
    }
    @Override public List<Photo> photos(AuthenticatedUserPrincipal principal,long repair) {
        return tx(principal,repair,(actor,deadline)-> {
            lockActor(actor,false);
            var rows=jdbc.query(SELECT+" WHERE taller_id=? AND reparacion_id=? AND estado IN ('ASOCIADA','LIMPIEZA_PENDIENTE') AND asociada_en IS NOT NULL AND retener_hasta>statement_timestamp() ORDER BY asociada_en,id LIMIT 101",
                    (rs,n)->row(rs),actor.tallerId(),repair);
            if(rows.size()>100)throw PrivatePhotoException.unavailable();
            return rows.stream().map(LegalPrivatePhotoOperations::photo).toList();
        });
    }
    @Override public Content content(AuthenticatedUserPrincipal principal,long repair,UUID id) {
        Row row=tx(principal,repair,(actor,deadline)-> {lockActor(actor,false);Row found=load(id,actor,repair,false);requireAssociated(found);return found;});
        try {
            StoredAsset asset=storage.find(row.key()).orElseThrow(PrivatePhotoException::unavailable);accredit(asset,row);
            if(!Objects.equals(asset.assetId(),row.assetId()) || !Objects.equals(asset.version(),row.version()))throw PrivatePhotoException.unavailable();
            byte[] bytes=storage.read(asset,PrivatePhotoImageValidator.MAX_BYTES);
            PrivatePhotoImageValidator.validate(row.mime(),bytes,row.bytes(),row.sha());
            // Revocation or concurrent deletion before delivery vetoes the bytes.
            tx(principal,repair,(actor,deadline)-> {lockActor(actor,false);requireAssociated(load(id,actor,repair,false));return Boolean.TRUE;});
            return new Content(row.mime(),bytes);
        } catch(RuntimeException failure) {throw failure instanceof PrivatePhotoException typed?typed:PrivatePhotoException.unavailable();}
    }
    @Override public void delete(AuthenticatedUserPrincipal principal,long repair,UUID id) {
        DeletionWork work=tx(principal,repair,(actor,deadline)-> {
            deletions.admit(actor.tallerId());
            lockActor(actor,false);Row found=load(id,actor,repair,true);
            if(found.state().equals("ELIMINADA"))return new DeletionWork(found,null);
            requireNoLease(found);
            if(!found.state().equals("LIMPIEZA_PENDIENTE"))jdbc.update(
                    "UPDATE public.reparacion_fotos_privadas SET estado='LIMPIEZA_PENDIENTE',lease_id=NULL,lease_hasta=NULL WHERE id=?",id);
            var target=deletions.prepare(deletionPhoto(found));
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");deadline.check();
            return new DeletionWork(found,target);
        });
        if(work.target()==null)return; // Historical ELIMINADA rows are never given invented receipts.
        Removal observation=removeObject(work);
        tx(principal,repair,(actor,deadline)-> {
            deletions.admit(actor.tallerId());lockActor(actor,false);
            completeDeletion(work.row(),observation);
            deadline.check();return Boolean.TRUE;
        });
    }

    @Override public synchronized int cleanup() {
        CleanupCursor previous=cleanupCursor;
        List<Row> candidates=system(deadline->{
            List<Row> page=cleanupPage(previous);
            return page.isEmpty() && previous!=null ? cleanupPage(null) : page;
        });
        if(candidates.isEmpty()) {cleanupCursor=null;return 0;}
        // Advance even when every storage operation fails. A process restart safely begins at the first page.
        Row last=candidates.getLast();cleanupCursor=new CleanupCursor(last.confirmedAt(),last.id());
        int cleaned=0;
        for(Row candidate:candidates) {
            try {
                DeletionWork work=system(deadline->{
                    deletions.admit(candidate.workshop());
                    Row current=lockDeletionRow(candidate);
                    if(current.state().equals("ELIMINADA"))return new DeletionWork(current,null);
                    requireNoLease(current);
                    if(!Set.of("EXPIRADA","FALLIDA","LIMPIEZA_PENDIENTE").contains(current.state())
                            && current.retention().isAfter(now()) && (current.state().equals("ASOCIADA") || current.expires().isAfter(now())))
                        throw PrivatePhotoException.unavailable();
                    if(!current.state().equals("LIMPIEZA_PENDIENTE"))jdbc.update(
                            "UPDATE public.reparacion_fotos_privadas SET estado='LIMPIEZA_PENDIENTE',lease_id=NULL,lease_hasta=NULL WHERE id=?",current.id());
                    var target=deletions.prepare(deletionPhoto(current));
                    jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");deadline.check();
                    return new DeletionWork(current,target);
                });
                if(work.target()!=null) {
                    Removal observation=removeObject(work);
                    system(deadline->{completeDeletion(work.row(),observation);deadline.check();return Boolean.TRUE;});
                }
                cleaned++;
            } catch(RuntimeException failure) { /* retain the durable target and exact identity for the next bounded pass */ }
        }
        return cleaned;
    }
    private List<Row> cleanupPage(CleanupCursor after) {
        String eligible=SELECT+" WHERE estado<>'ELIMINADA' AND (lease_hasta IS NULL OR lease_hasta<statement_timestamp()) AND "
                +"(estado IN ('EXPIRADA','FALLIDA','LIMPIEZA_PENDIENTE') OR (estado IN ('AUTORIZADA','SUBIDA') AND expira_en<=statement_timestamp()) OR retener_hasta<=statement_timestamp())";
        String order=" ORDER BY confirmado_en,id LIMIT 10";
        return after==null ? jdbc.query(eligible+order,(rs,n)->row(rs))
                : jdbc.query(eligible+" AND (confirmado_en,id)>(?,?)"+order,(rs,n)->row(rs),
                    OffsetDateTime.ofInstant(after.confirmedAt(),java.time.ZoneOffset.UTC),after.id());
    }
    /** All external deletion paths use this committed target; no storage I/O occurs in a JDBC transaction. */
    private Removal removeObject(DeletionWork work) {
        Row row=work.row();var target=work.target();
        try {
            if(!target.knownIdentity()) {
                var found=storage.find(target.key());
                if(found.isPresent()) {
                    StoredAsset actual=found.get();accredit(actual,row);
                    byte[] original=storage.read(actual,PrivatePhotoImageValidator.MAX_BYTES);
                    try {PrivatePhotoImageValidator.validate(row.mime(),original,row.bytes(),row.sha());}
                    finally {if(original!=null)Arrays.fill(original,(byte)0);}
                    var expected=target;
                    target=system(deadline->{
                        deletions.admit(row.workshop());Row current=lockDeletionRow(row);
                        var identified=deletions.identify(deletionPhoto(current),expected,actual);
                        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");deadline.check();return identified;
                    });
                } else {
                    // Preserve the existing empty-intention lifecycle without inventing a remote receipt.
                    // A point-in-time absent key does not rule out an uncertain or delayed upload.
                    if(storage.find(target.key()).isPresent())throw PrivatePhotoException.unavailable();
                    return new Removal(target,LegalPrivatePhotoDeletionStore.Result.AUSENCIA_OBSERVADA_SIN_IDENTIDAD);
                }
            }
            if(target.terminal())return new Removal(target,target.result());
            // A missing public key never substitutes for deleting/checking the known immutable asset identity.
            storage.delete(target.key(),target.assetId());
            if(storage.find(target.key()).isPresent())throw PrivatePhotoException.unavailable();
            return new Removal(target,LegalPrivatePhotoDeletionStore.Result.IDENTIDAD_ELIMINADA);
        } catch(RuntimeException failure) {throw PrivatePhotoException.unavailable();}
    }
    private Row lockDeletionRow(Row expected) {
        var rows=jdbc.query(SELECT+" WHERE id=? AND taller_id=? FOR UPDATE",(rs,n)->row(rs),expected.id(),expected.workshop());
        if(rows.size()!=1)throw PrivatePhotoException.unavailable();Row current=rows.getFirst();
        if(current.user()!=expected.user() || !current.key().equals(expected.key()))throw PrivatePhotoException.unavailable();
        return current;
    }
    private void completeDeletion(Row expected,Removal observation) {
        deletions.admit(expected.workshop());Row current=lockDeletionRow(expected);
        if(!Set.of("LIMPIEZA_PENDIENTE","ELIMINADA").contains(current.state()))throw PrivatePhotoException.unavailable();
        requireNoLease(current);
        deletions.complete(deletionPhoto(current),observation.target(),observation.result());
        if(!current.state().equals("ELIMINADA") && jdbc.update("""
                UPDATE public.reparacion_fotos_privadas SET estado='ELIMINADA',asset_id=NULL,asset_version=NULL,lease_id=NULL,lease_hasta=NULL
                WHERE id=? AND estado='LIMPIEZA_PENDIENTE'
                """,current.id())!=1)throw PrivatePhotoException.unavailable();
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
    }
    private static LegalPrivatePhotoDeletionStore.Photo deletionPhoto(Row row) {
        return new LegalPrivatePhotoDeletionStore.Photo(row.id(),row.user(),row.workshop(),row.key(),row.assetId(),row.version());
    }
    private Lease claim(AuthenticatedUserPrincipal principal,long repair,UUID id,boolean upload) {
        return tx(principal,repair,(actor,deadline)-> {
            lockActor(actor,false);Row row=load(id,actor,repair,true);requireContext(row,actor,repair,true);requireLive(row);
            if(upload ? !Set.of("AUTORIZADA","SUBIDA").contains(row.state()) : !row.state().equals("SUBIDA"))throw PrivatePhotoException.invalid();
            requireNoLease(row);UUID token=UUID.randomUUID();
            jdbc.update("UPDATE public.reparacion_fotos_privadas SET lease_id=?,lease_hasta=statement_timestamp()+interval '2 minutes' WHERE id=?",token,id);
            return new Lease(row,token);
        });
    }
    private void release(AuthenticatedUserPrincipal principal,long repair,Lease lease) {
        try {tx(principal,repair,(actor,deadline)-> {jdbc.update("UPDATE public.reparacion_fotos_privadas SET lease_id=NULL,lease_hasta=NULL WHERE id=? AND lease_id=?",lease.row().id(),lease.token());return Boolean.TRUE;});}
        catch(RuntimeException failure) { /* lease expiration preserves recovery after revocation/connection loss */ }
    }
    private void requireLive(Row row) {if(!row.expires().isAfter(now()))throw PrivatePhotoException.expired();}
    private void requireNoLease(Row row) {
        if(row.lease()!=null && row.leaseUntil().isAfter(now()))throw PrivatePhotoException.of(HttpStatus.CONFLICT,"OPERACION_EN_PROGRESO");
    }
    private void requireLease(Row row,Lease expected) {
        if(!expected.token().equals(row.lease()) || row.leaseUntil()==null || !row.leaseUntil().isAfter(now()))throw PrivatePhotoException.unavailable();
    }
    private void requireAssociated(Row row) {
        if(!row.state().equals("ASOCIADA") || !row.retention().isAfter(now()))throw PrivatePhotoException.missing();
    }
    private static void accredit(StoredAsset asset,Row row) {
        if(asset==null || !row.key().equals(asset.objectKey()) || !row.mime().equals(asset.mimeType()) || row.bytes()!=asset.bytes()
                || asset.assetId()==null || asset.assetId().isBlank() || asset.version()==null || asset.version().isBlank())throw PrivatePhotoException.unavailable();
    }

    private <T>T tx(AuthenticatedUserPrincipal principal,long repair,Work<T> work) {
        return system(deadline->{
            LegalActorSnapshot actor=identify(principal);observe(actor);
            if(repair<=0 || !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM public.reparaciones WHERE id=? AND taller_id=?)",Boolean.class,repair,actor.tallerId())))throw PrivatePhotoException.missing();
            return work.run(actor,deadline);
        });
    }
    private <T>T system(SystemWork<T> work) {
        var completion=new LegalTransactionCompletionState<T>();
        try {return boundary.execute(completion,(status,deadline)->{LegalPrivatePhotoSchema.requireDeletionReceipts(jdbc);return work.run(deadline);});}
        catch(RuntimeException failure) {
            var state=completion.snapshot();
            if(state.completion()==LegalTransactionCompletionState.Completion.ROLLED_BACK && state.persistence()==LegalTransactionCompletionState.Persistence.NOT_PERSISTED) {
                if(failure instanceof PrivatePhotoException typed)throw typed;
                if(failure instanceof LegalIdempotencyException typed)throw switch(typed.reason()) {
                    case KEY_REUSED->PrivatePhotoException.of(HttpStatus.CONFLICT,"IDEMPOTENCY_KEY_REUTILIZADA");
                    case IN_PROGRESS->PrivatePhotoException.of(HttpStatus.CONFLICT,"OPERACION_EN_PROGRESO");
                    case INVALID_ACTOR->PrivatePhotoException.of(HttpStatus.UNAUTHORIZED,"FOTO_ACTOR_NO_VALIDO");
                    default->PrivatePhotoException.unavailable();
                };
            }
            throw PrivatePhotoException.unavailable();
        }
    }
    private static LegalActorSnapshot identify(AuthenticatedUserPrincipal principal) {
        if(principal==null || principal.getUserId()==null || principal.getUserId()<=0 || principal.getTallerId()==null || principal.getTallerId()<=0
                || !principal.isEnabled() || principal.getAuthorities().size()!=1 || principal.getTokenVersion()<0)throw PrivatePhotoException.of(HttpStatus.UNAUTHORIZED,"FOTO_ACTOR_NO_VALIDO");
        UserRole role=switch(principal.getAuthorities().iterator().next().getAuthority()) {
            case "ROLE_ADMIN"->UserRole.ADMIN;case "ROLE_USER"->UserRole.USER;default->throw PrivatePhotoException.of(HttpStatus.UNAUTHORIZED,"FOTO_ACTOR_NO_VALIDO");};
        return new LegalActorSnapshot(principal.getUserId(),principal.getTallerId(),role,principal.getTokenVersion(),true,true);
    }
    private void observe(LegalActorSnapshot actor) {
        Boolean valid=jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM public.users u JOIN public.talleres t ON t.id=u.taller_id
              WHERE u.id=? AND u.taller_id=? AND u.role=? AND u.active AND t.activo AND t.cierre_estado='ABIERTO' AND u.token_version=?)
            """,Boolean.class,actor.userId(),actor.tallerId(),actor.role().name(),actor.tokenVersion());
        if(!Boolean.TRUE.equals(valid))throw PrivatePhotoException.of(HttpStatus.UNAUTHORIZED,"FOTO_ACTOR_NO_VALIDO");
    }
    private void lockActor(LegalActorSnapshot actor,boolean exclusive) {
        jdbc.queryForList("SELECT pg_catalog.pg_advisory_xact_lock"+(exclusive?"":"_shared")+"(pg_catalog.hashtextextended(pg_catalog.jsonb_build_array('ordenfix:legal-actor:v1',?::bigint,?::bigint)::text,0))",actor.tallerId(),actor.userId());
        jdbc.queryForList("SELECT id FROM public.talleres WHERE id=? FOR SHARE",actor.tallerId());
        jdbc.queryForList("SELECT id FROM public.users WHERE id=? AND taller_id=? FOR SHARE",actor.userId(),actor.tallerId());observe(actor);
    }
    private void lockRepair(LegalActorSnapshot actor,long repair) { lockRepair(actor,repair,false); }
    private void lockRepair(LegalActorSnapshot actor,long repair,boolean exclusive) {
        if(jdbc.queryForList("SELECT id FROM public.reparaciones WHERE id=? AND taller_id=? FOR "+(exclusive?"UPDATE":"SHARE"),repair,actor.tallerId()).size()!=1)throw PrivatePhotoException.missing();
    }
    private LegalEditorialTimeBoundary editorial(LegalPrivateRequirementsDeadline deadline) {
        deadline.check();jdbc.queryForList("SELECT pg_catalog.pg_advisory_xact_lock_shared(pg_catalog.hashtextextended(?,0))",LegalManifestDatabaseGate.EDITORIAL_LOCK_NAME);
        Instant transaction=Objects.requireNonNull(jdbc.queryForObject("SELECT transaction_timestamp()",OffsetDateTime.class)).toInstant();
        return new LegalEditorialTimeBoundary(transaction,now());
    }
    private Current current(LegalActorSnapshot actor,LegalEditorialTimeBoundary observed,LegalPrivateRequirementsDeadline deadline) {
        var applicable=scopes.resolve(PerfilAgregadoLegal.AUTHENTICATED_PENDING,LocaleLegal.ES_AR,actor.audience());
        var aggregate=aggregates.materialize(applicable,observed);
        var legal=reader.read(actor,applicable,aggregate,observed,deadline);
        Set<UUID> pending=new HashSet<>();legal.requirements().forEach(r->pending.add(r.versionId()));
        var needed=legal.snapshot().requirements().stream().filter(r->r.context()==ContextoLegal.ATESTACION_FOTOS || pending.contains(r.versionId())).toList();
        if(needed.stream().noneMatch(r->r.context()==ContextoLegal.ATESTACION_FOTOS && r.actType()==TipoActoLegal.DECLARACION && r.required()))throw PrivatePhotoException.unavailable();
        var wire=new Requirements("es-AR",legal.requiredSetRevision(),needed.stream().map(r->new LegalPrivateRequirementsResponses.Requisito(
            r.versionId().toString(),r.context().name(),r.actType().name(),r.statement(),r.statementSha256(),r.required(),r.documents().stream().map(d->new LegalPrivateRequirementsResponses.Documento(
                d.versionId().toString(),d.type().name(),d.version(),d.title(),d.markdown(),d.sha256(),d.effectiveAt().toInstant().toString(),"VIGENTE","es-AR")).toList())).toList(),
                new Limits(PrivatePhotoImageValidator.MAX_BYTES,PrivatePhotoImageValidator.MIME_TYPES,100));
        return new Current(aggregate,legal,wire);
    }
    private Row load(UUID id,LegalActorSnapshot actor,long repair,boolean lock) {
        if(id==null)throw PrivatePhotoException.missing();
        var rows=jdbc.query(SELECT+" WHERE id=? AND taller_id=? AND reparacion_id=?"+(lock?" FOR UPDATE":""),(rs,n)->row(rs),id,actor.tallerId(),repair);
        if(rows.size()!=1)throw PrivatePhotoException.missing();return rows.getFirst();
    }
    private static void requireContext(Row row,LegalActorSnapshot actor,long repair,boolean creator) {
        if(row.repair()!=repair || row.workshop()!=actor.tallerId())throw PrivatePhotoException.missing();
        if(creator && row.user()!=actor.userId())throw PrivatePhotoException.forbidden();
    }
    private Instant now() {return Objects.requireNonNull(jdbc.queryForObject("SELECT statement_timestamp()",OffsetDateTime.class)).toInstant();}
    private static final String SELECT="SELECT id,reparacion_original_id,taller_id,user_id,nombre,fingerprint_hmac,scope_hmac,key_hmac,hmac_key_version,mime_type,bytes,sha256,momento,estado,expira_en,retener_hasta,asociada_en,object_key,asset_id,asset_version,lease_id,lease_hasta,confirmado_en FROM public.reparacion_fotos_privadas";
    private static Row row(java.sql.ResultSet rs)throws java.sql.SQLException {
        return new Row(rs.getObject("id",UUID.class),rs.getLong("reparacion_original_id"),rs.getLong("taller_id"),rs.getLong("user_id"),rs.getString("nombre"),rs.getString("fingerprint_hmac"),rs.getString("scope_hmac"),rs.getString("key_hmac"),rs.getInt("hmac_key_version"),rs.getString("mime_type"),rs.getLong("bytes"),rs.getString("sha256"),MomentoFoto.valueOf(rs.getString("momento")),rs.getString("estado"),instant(rs,"expira_en"),instant(rs,"retener_hasta"),instant(rs,"asociada_en"),rs.getString("object_key"),rs.getString("asset_id"),rs.getString("asset_version"),rs.getObject("lease_id",UUID.class),instant(rs,"lease_hasta"),instant(rs,"confirmado_en"));
    }
    private static Instant instant(java.sql.ResultSet rs,String column)throws java.sql.SQLException {var value=rs.getObject(column,OffsetDateTime.class);return value==null?null:value.toInstant();}
    private Intention view(Row row) {
        if(Set.of("AUTORIZADA","SUBIDA").contains(row.state()) && !row.expires().isAfter(now()))
            return new Intention(row.id(),"EXPIRADA",row.expires(),null);
        Upload upload=Set.of("AUTORIZADA","SUBIDA").contains(row.state())?new Upload("PUT","/api/reparaciones/"+row.repair()+"/cargas-foto/"+row.id()+"/contenido"):null;
        return new Intention(row.id(),row.state(),row.expires(),upload);
    }
    private static Photo photo(Row row) {return new Photo(row.id(),row.moment(),row.mime(),row.bytes(),row.sha(),row.associated());}
    private record Current(LegalRequiredSetAggregateReceipt aggregate,LegalAuthenticatedRequirements legal,Requirements wire) { }
    private record Row(UUID id,long repair,long workshop,long user,String name,String fingerprint,String scope,String keyHmac,int keyVersion,String mime,long bytes,String sha,MomentoFoto moment,String state,Instant expires,Instant retention,Instant associated,String key,String assetId,String version,UUID lease,Instant leaseUntil,Instant confirmedAt) { }
    private record DeletionWork(Row row,LegalPrivatePhotoDeletionStore.Target target) {
        @Override public String toString(){return "PrivatePhotoDeletionWork[redacted]";}
    }
    private record Removal(LegalPrivatePhotoDeletionStore.Target target,LegalPrivatePhotoDeletionStore.Result result) {
        @Override public String toString(){return "PrivatePhotoRemovalObservation[redacted]";}
    }
    private record CleanupCursor(Instant confirmedAt,UUID id) { }
    private record Lease(Row row,UUID token) { }
    @FunctionalInterface private interface Work<T> {T run(LegalActorSnapshot actor,LegalPrivateRequirementsDeadline deadline);}
    @FunctionalInterface private interface SystemWork<T> {T run(LegalPrivateRequirementsDeadline deadline);}
    @Override public void close() {try {source.close();}finally {pool.close();}}
}
