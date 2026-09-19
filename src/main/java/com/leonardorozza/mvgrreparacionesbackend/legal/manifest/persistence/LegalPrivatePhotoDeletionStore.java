package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.photos.PrivatePhotoException;
import com.leonardorozza.mvgrreparacionesbackend.photos.storage.PrivatePhotoStorage.StoredAsset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Photo-specific durable objectives. Every method participates in the caller's verified RC boundary.
 * The caller locks the photo before this row; no storage I/O belongs inside these transactions. */
final class LegalPrivatePhotoDeletionStore {
    private final JdbcTemplate jdbc;

    LegalPrivatePhotoDeletionStore(JdbcTemplate jdbc) { this.jdbc=Objects.requireNonNull(jdbc); }

    /** First mutation lock, before actor/photo/receipt row locks; never waits behind a closure transition. */
    void admit(long workshop) {
        boundary();
        if(workshop<=0 || !Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT pg_catalog.pg_try_advisory_xact_lock_shared(pg_catalog.hashtextextended(?,0))",
                Boolean.class,"ordenfix:closure:"+workshop)))throw PrivatePhotoException.unavailable();
    }

    Target prepare(Photo photo) {
        boundary();
        List<Target> found=find(photo.id());
        if(!found.isEmpty()) {
            Target existing=single(found);match(photo,existing);
            if(existing.terminal())throw PrivatePhotoException.unavailable();
            return existing;
        }
        int changed=jdbc.update("""
                INSERT INTO public.reparacion_foto_eliminaciones
                    (foto_id,user_id,taller_id,object_key,asset_id,asset_version,creada_en,identificada_en,resultado)
                VALUES(?,?,?,?,?,?,statement_timestamp(),
                    CASE WHEN ?::varchar IS NULL THEN NULL ELSE statement_timestamp() END,'PENDIENTE')
                """,photo.id(),photo.user(),photo.workshop(),photo.key(),photo.assetId(),photo.version(),photo.assetId());
        if(changed!=1)throw PrivatePhotoException.unavailable();
        Target result=single(find(photo.id()));match(photo,result);return result;
    }

    /** Called only after metadata and original-content accreditation, before issuing DELETE. */
    Target identify(Photo photo,Target expected,StoredAsset discovered) {
        boundary();
        Target current=single(find(photo.id()));match(photo,current);matchBase(expected,current);
        if(discovered==null || !current.key().equals(discovered.objectKey()) || discovered.assetId()==null
                || discovered.version()==null)throw PrivatePhotoException.unavailable();
        if(current.knownIdentity()) {
            if(!current.assetId().equals(discovered.assetId()) || !current.version().equals(discovered.version()))
                throw PrivatePhotoException.unavailable();
            return current;
        }
        if(current.terminal() || expected.knownIdentity())throw PrivatePhotoException.unavailable();
        if(jdbc.update("""
                UPDATE public.reparacion_foto_eliminaciones SET asset_id=?,asset_version=?,identificada_en=statement_timestamp()
                WHERE foto_id=? AND resultado='PENDIENTE' AND asset_id IS NULL
                """,discovered.assetId(),discovered.version(),current.id())!=1)throw PrivatePhotoException.unavailable();
        Target result=single(find(photo.id()));match(photo,result);return result;
    }

    /** Caller changes the photo to ELIMINADA in this same transaction and checks deferred constraints. */
    Target complete(Photo photo,Target expected,Result result) {
        boundary();
        if(result==null || result==Result.PENDIENTE)throw PrivatePhotoException.unavailable();
        Target current=single(find(photo.id()));match(photo,current);matchBase(expected,current);
        if(expected.knownIdentity() && (!Objects.equals(expected.assetId(),current.assetId())
                || !Objects.equals(expected.version(),current.version())))throw PrivatePhotoException.unavailable();
        if(current.terminal())return current; // Concurrent completion never rewrites its evidence.
        if(!Objects.equals(expected.assetId(),current.assetId()) || !Objects.equals(expected.version(),current.version())
                || (result==Result.IDENTIDAD_ELIMINADA)!=current.knownIdentity())throw PrivatePhotoException.unavailable();
        if(jdbc.update("""
                UPDATE public.reparacion_foto_eliminaciones SET resultado=?,observada_en=statement_timestamp(),
                    confirmada_en=CASE WHEN ?='IDENTIDAD_ELIMINADA' THEN statement_timestamp() ELSE NULL END
                WHERE foto_id=? AND resultado='PENDIENTE'
                """,result.name(),result.name(),current.id())!=1)throw PrivatePhotoException.unavailable();
        return single(find(photo.id()));
    }

    private List<Target> find(UUID id) {
        return jdbc.query("""
                SELECT foto_id,user_id,taller_id,object_key,asset_id,asset_version,resultado
                FROM public.reparacion_foto_eliminaciones WHERE foto_id=? FOR UPDATE
                """,(rs,n)->new Target(rs.getObject("foto_id",UUID.class),rs.getLong("user_id"),rs.getLong("taller_id"),
                rs.getString("object_key"),rs.getString("asset_id"),rs.getString("asset_version"),Result.valueOf(rs.getString("resultado"))),id);
    }
    private static Target single(List<Target> values) {
        if(values.size()!=1)throw PrivatePhotoException.unavailable();return values.getFirst();
    }
    private static void match(Photo photo,Target target) {
        if(!photo.id().equals(target.id()) || photo.user()!=target.user() || photo.workshop()!=target.workshop()
                || !photo.key().equals(target.key()) || (photo.assetId()!=null
                && (!photo.assetId().equals(target.assetId()) || !photo.version().equals(target.version()))))
            throw PrivatePhotoException.unavailable();
    }
    private static void matchBase(Target expected,Target actual) {
        if(expected==null || !expected.id().equals(actual.id()) || expected.user()!=actual.user()
                || expected.workshop()!=actual.workshop() || !expected.key().equals(actual.key()))throw PrivatePhotoException.unavailable();
    }
    private static void boundary() {
        if(!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw PrivatePhotoException.unavailable();
    }

    enum Result { PENDIENTE, IDENTIDAD_ELIMINADA, AUSENCIA_OBSERVADA_SIN_IDENTIDAD }
    record Photo(UUID id,long user,long workshop,String key,String assetId,String version) {
        Photo {
            if(id==null || user<=0 || workshop<=0 || !("ordenfix-private/"+id).equals(key)
                    || (assetId==null)!=(version==null))throw PrivatePhotoException.unavailable();
        }
        @Override public String toString(){return "PhotoDeletionIdentity[redacted]";}
    }
    record Target(UUID id,long user,long workshop,String key,String assetId,String version,Result result) {
        boolean knownIdentity(){return assetId!=null;}
        boolean terminal(){return result!=Result.PENDIENTE;}
        @Override public String toString(){return "PhotoDeletionTarget[redacted]";}
    }
}
