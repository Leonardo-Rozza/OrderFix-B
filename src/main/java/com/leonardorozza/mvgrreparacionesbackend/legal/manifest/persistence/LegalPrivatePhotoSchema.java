package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Exact clean PG16 V30 catalog; prior migration resources and their inventories remain frozen. */
final class LegalPrivatePhotoSchema {
    private LegalPrivatePhotoSchema() { }
    static final LegalV29AcceptanceInventory.CatalogFingerprint LEGAL_CATALOG=new LegalV29AcceptanceInventory.CatalogFingerprint(
            12,"28c4c52db8902f5097d957b7f7ad1263e7398eec719c482816e5ed41e194adb5",
            119,"8e24fd597e342462c0032a6b1752f5db2cc16efc3c1b106680bfa3801bf52330",
            96,"810621455042b0381690e4322d0da3a68fd22e345a10ba2896b5d2bfbaf9f523",
            43,"f67d6a99f2c90d9b2e31b3b82f0b0a05201dbec2da1d089ccb08f4e5be347235",
            38,"aba4594cb91187d860af3024b0ad5aa33cc65045a8e0430e825914dc1a31cd68",
            5,"7df57cfa66f5f92adc282732fc09a25a1a76407665b20793f7e729fd8712cdbf");
    private static final String EXPECTED="2ed4b28d602f9b8e936342db7492ff483dd90b050e5552c2b4f17f7733fba3a6";
    private static final String SQL="""
            SELECT jsonb_build_object(
             'tables',(SELECT jsonb_agg(jsonb_build_array(c.relname,c.relkind,c.relpersistence,c.relrowsecurity,c.relforcerowsecurity,c.relispartition,
               EXISTS(SELECT 1 FROM pg_inherits i WHERE i.inhrelid=c.oid OR i.inhparent=c.oid),
               EXISTS(SELECT 1 FROM pg_rewrite r WHERE r.ev_class=c.oid)) ORDER BY c.relname)
               FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relname IN ('reparacion_fotos_privadas','reparacion_foto_atestaciones')),
             'columns',(SELECT jsonb_agg(jsonb_build_array(c.relname,a.attnum,a.attname,format_type(a.atttypid,a.atttypmod),a.attnotnull,a.attidentity,a.attgenerated,
               pg_get_expr(d.adbin,d.adrelid)) ORDER BY c.relname,a.attnum)
               FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace
               LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum
               WHERE n.nspname='public' AND c.relname IN ('reparacion_fotos_privadas','reparacion_foto_atestaciones') AND a.attnum>0 AND NOT a.attisdropped),
             'constraints',(SELECT jsonb_agg(jsonb_build_array(c.relname,k.conname,k.contype,k.convalidated,k.condeferrable,k.condeferred,pg_get_constraintdef(k.oid,true)) ORDER BY c.relname,k.conname)
               FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace
               WHERE n.nspname='public' AND c.relname IN ('reparacion_fotos_privadas','reparacion_foto_atestaciones')),
             'indexes',(SELECT jsonb_agg(jsonb_build_array(c.relname,idx.relname,i.indisvalid,i.indisready,pg_get_indexdef(i.indexrelid)) ORDER BY c.relname,idx.relname)
               FROM pg_index i JOIN pg_class c ON c.oid=i.indrelid JOIN pg_class idx ON idx.oid=i.indexrelid JOIN pg_namespace n ON n.oid=c.relnamespace
               WHERE n.nspname='public' AND c.relname IN ('reparacion_fotos_privadas','reparacion_foto_atestaciones')),
             'triggers',(SELECT jsonb_agg(jsonb_build_array(c.relname,t.tgname,t.tgenabled,pg_get_triggerdef(t.oid,true)) ORDER BY c.relname,t.tgname)
               FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace
               WHERE n.nspname='public' AND NOT t.tgisinternal AND (c.relname IN ('reparacion_fotos_privadas','reparacion_foto_atestaciones') OR t.tgname='trg_reparacion_fotos_privadas_delete')),
             'functions',(SELECT jsonb_agg(jsonb_build_array(p.proname,p.prosecdef,p.provolatile,p.proparallel,p.proisstrict,p.proleakproof,p.proconfig,p.proretset,l.lanname,pg_get_function_result(p.oid),p.prosrc) ORDER BY p.proname)
               FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace JOIN pg_language l ON l.oid=p.prolang
               WHERE n.nspname='public' AND p.proname IN ('foto_privada_insert_guard_v30','foto_atestacion_insert_guard_v30','foto_privada_completa_v30','foto_privada_conservar_objetos_v30','foto_privada_update_guard_v30'))
            )::text AS photo_catalog
            """;
    static void verify(JdbcTemplate jdbc) {
        verifyCatalog(jdbc, new LegalV29AcceptanceSchemaVerifier(jdbc, "public").workshopClosureSchemaVersion());
    }
    static void verify(JdbcTemplate jdbc, boolean closure) {
        verifyCatalog(jdbc, closure ? new LegalV29AcceptanceSchemaVerifier(jdbc, "public").workshopClosureSchemaVersion() : 0);
    }
    /** All photo-service operations require V35 or a later accredited compatible schema; historical non-photo consumers keep their exact versions. */
    static void requireDeletionReceipts(JdbcTemplate jdbc) {
        int version=new LegalV29AcceptanceSchemaVerifier(jdbc,"public").workshopClosureSchemaVersion();
        if(version<35)throw new IllegalStateException("Esquema de fotos privadas incompatible");
        verifyCatalog(jdbc,version);
    }
    private static void verifyCatalog(JdbcTemplate jdbc,int version) {
            Boolean trustedOwner=jdbc.queryForObject("""
                    SELECT p.proowner=f.relowner AND p.proowner=r.relowner AND p.proowner=m.relowner
                      FROM pg_catalog.pg_proc p
                      JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace
                      CROSS JOIN pg_catalog.pg_class f
                      CROSS JOIN pg_catalog.pg_class r
                      CROSS JOIN pg_catalog.pg_class m
                     WHERE n.nspname='public' AND p.proname='foto_privada_conservar_objetos_v30'
                       AND p.pronargs=0 AND f.oid='public.reparacion_fotos_privadas'::regclass
                       AND r.oid='public.reparaciones'::regclass AND m.oid='public.flyway_schema_history'::regclass
                    """,Boolean.class);
            if(!Boolean.TRUE.equals(trustedOwner)) throw new IllegalStateException("Esquema de fotos privadas incompatible");
            String expected=version>=37 ? LegalV37OperationalDeletionSchema.PHOTO_CATALOG
                    : version>=35 ? LegalV35PhotoDeletionSchema.PHOTO_CATALOG
                    : version!=0 ? LegalV33ClosureSchema.PHOTO_CATALOG : EXPECTED;
            String catalog = snapshot(jdbc);
            if (version == 38 ? !LegalV38ProfileErasureSchema.acceptsPhotos(catalog)
                    : !expected.equals(catalog) && !(version == 37 && LegalRestoredV37Catalogs.PHOTO.equals(catalog)))
                throw new IllegalStateException("Esquema de fotos privadas incompatible");
    }
    /** Fresh-PG diagnostic hashes catalog metadata only, never photo rows or remote identifiers. */
    static String snapshot(JdbcTemplate jdbc) {
        try {
            String raw=jdbc.queryForObject(SQL,String.class);
            if(raw==null)throw new IllegalStateException("Esquema de fotos privadas incompatible");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch(java.security.GeneralSecurityException failure) {throw new IllegalStateException("Esquema de fotos privadas incompatible");}
    }
}
