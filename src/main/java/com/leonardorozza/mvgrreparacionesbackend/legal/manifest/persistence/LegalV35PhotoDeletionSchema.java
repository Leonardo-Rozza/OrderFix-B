package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Exact V35 deletion evidence delta; all earlier migration resources and fingerprints remain frozen. */
final class LegalV35PhotoDeletionSchema {
    static final String FLYWAY_SCRIPT = "V35__recibos_eliminacion_fotos_privadas.sql";
    // Filled once from the final migration on disposable PostgreSQL 16 by LegalV35SchemaSnapshot.
    static final int FLYWAY_CHECKSUM = 1631742413;
    static final String EXPECTED = "ddc53e7fffa427ea1d16e54a6aa3a95ea0ff2095d6bddb7265adfb88e1daae1d";
    static final String V33_DELTA = "748c59bf556242a6b63132cd4dc21af199181fcf8f005688c99eb6edb30e54a3";
    static final String PHOTO_CATALOG = "2a35e1c4c176b1a1e58f47d5c2ea5b9dc936db2df37797513d2250153d303a29";
    static final List<String> TABLES = List.of("reparacion_foto_eliminaciones");
    static final List<String> FUNCTIONS = List.of("foto_eliminacion_guard_v35", "foto_eliminada_recibo_guard_v35", "foto_eliminacion_completa_v35");
    static final Map<String,String> TRIGGERS = Map.of(
            "reparacion_foto_eliminaciones", "aa_foto_eliminacion_v35",
            "reparacion_fotos_privadas", "ab_foto_eliminada_recibo_v35");
    private static final String TABLE_NAMES = "'reparacion_foto_eliminaciones','reparacion_fotos_privadas'";
    private static final String SQL = """
            SELECT pg_catalog.jsonb_build_object(
              'tables', (SELECT jsonb_agg(jsonb_build_array(c.relname,c.relkind,c.relpersistence,c.relrowsecurity,
                  c.relforcerowsecurity,c.relispartition,c.relreplident,c.reloptions,
                  c.relowner=(SELECT relowner FROM pg_class WHERE oid='public.flyway_schema_history'::regclass),
                  EXISTS(SELECT 1 FROM pg_inherits i WHERE i.inhrelid=c.oid OR i.inhparent=c.oid),
                  EXISTS(SELECT 1 FROM pg_rewrite r WHERE r.ev_class=c.oid),
                  EXISTS(SELECT 1 FROM pg_policy p WHERE p.polrelid=c.oid),
                  (SELECT jsonb_agg(jsonb_build_array(a.grantee=c.relowner,a.grantor=c.relowner,a.privilege_type,a.is_grantable)
                      ORDER BY a.grantee=c.relowner,a.grantor=c.relowner,a.privilege_type,a.is_grantable)
                    FROM aclexplode(COALESCE(c.relacl,acldefault('r',c.relowner))) a WHERE a.grantee=0)) ORDER BY c.relname)
                FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='public' AND c.relname IN ('reparacion_foto_eliminaciones')),
              'columns', (SELECT jsonb_agg(jsonb_build_array(c.relname,a.attnum,a.attname,
                  format_type(a.atttypid,a.atttypmod),a.attnotnull,a.attidentity,a.attgenerated,a.attisdropped,
                  a.attstorage,a.attcompression,a.attoptions,cn.nspname,co.collname,pg_get_expr(d.adbin,d.adrelid),
                  (SELECT jsonb_agg(jsonb_build_array(acl.grantee=c.relowner,acl.grantor=c.relowner,acl.privilege_type,acl.is_grantable)
                      ORDER BY acl.grantee=c.relowner,acl.grantor=c.relowner,acl.privilege_type,acl.is_grantable)
                    FROM aclexplode(a.attacl) acl WHERE acl.grantee=0))
                  ORDER BY c.relname,a.attnum)
                FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace
                LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum
                LEFT JOIN pg_collation co ON co.oid=a.attcollation LEFT JOIN pg_namespace cn ON cn.oid=co.collnamespace
                WHERE n.nspname='public' AND c.relname IN ('reparacion_foto_eliminaciones') AND a.attnum>0),
              'constraints', (SELECT jsonb_agg(jsonb_build_array(c.relname,k.conname,k.contype,k.convalidated,
                  k.condeferrable,k.condeferred,k.conislocal,k.coninhcount,k.connoinherit,pg_get_constraintdef(k.oid,true),
                  (SELECT jsonb_agg(jsonb_build_array(tn.nspname,tr.relname,t.tgenabled,t.tgtype,t.tgdeferrable,
                      t.tginitdeferred,t.tgparentid<>0,t.tgnargs,encode(t.tgargs,'escape'),t.tgattr::text,
                      pg_get_expr(t.tgqual,t.tgrelid),pn.nspname,p.proname,oidvectortypes(p.proargtypes))
                      ORDER BY tn.nspname,tr.relname,t.tgtype,pn.nspname,p.proname)
                    FROM pg_trigger t JOIN pg_class tr ON tr.oid=t.tgrelid JOIN pg_namespace tn ON tn.oid=tr.relnamespace
                    JOIN pg_proc p ON p.oid=t.tgfoid JOIN pg_namespace pn ON pn.oid=p.pronamespace
                    WHERE t.tgconstraint=k.oid AND t.tgisinternal)) ORDER BY c.relname,k.conname)
                FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='public' AND c.relname IN ('reparacion_foto_eliminaciones')),
              'indexes', (SELECT jsonb_agg(jsonb_build_array(c.relname,idx.relname,idx.relkind,idx.relpersistence,
                  am.amname,idx.reloptions,i.indisunique,i.indnullsnotdistinct,i.indisprimary,i.indisexclusion,
                  i.indimmediate,i.indisclustered,i.indisvalid,i.indcheckxmin,i.indisready,i.indislive,i.indisreplident,
                  i.indnatts,i.indnkeyatts,i.indkey::text,i.indcollation::text,i.indclass::text,i.indoption::text,
                  pg_get_indexdef(i.indexrelid)) ORDER BY c.relname,idx.relname)
                FROM pg_index i JOIN pg_class c ON c.oid=i.indrelid JOIN pg_class idx ON idx.oid=i.indexrelid
                JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_am am ON am.oid=idx.relam
                WHERE n.nspname='public' AND c.relname IN ('reparacion_foto_eliminaciones')),
              'triggers', (SELECT jsonb_agg(jsonb_build_array(n.nspname,c.relname,t.tgname,t.tgenabled,
                  t.tgparentid<>0,t.tgtype,t.tgnargs,encode(t.tgargs,'escape'),t.tgdeferrable,t.tginitdeferred,
                  t.tgoldtable,t.tgnewtable,t.tgattr::text,pg_get_triggerdef(t.oid,true)) ORDER BY c.relname,t.tgname)
                FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='public' AND c.relname IN (%s) AND NOT t.tgisinternal),
              'functions', (SELECT jsonb_agg(jsonb_build_array(p.proname,oidvectortypes(p.proargtypes),
                  pg_get_function_result(p.oid),l.lanname,p.prokind,p.prosecdef,p.provolatile,p.proparallel,
                  p.proisstrict,p.proleakproof,p.proconfig,p.proretset,p.provariadic,p.proallargtypes,
                  p.proargmodes,p.proargnames,p.pronargdefaults,p.procost,p.prorows,p.prosupport=0,p.probin,p.prosrc,
                  p.proowner=(SELECT relowner FROM pg_class WHERE oid='public.flyway_schema_history'::regclass),
                  (SELECT jsonb_agg(jsonb_build_array(a.grantee=p.proowner,a.grantor=p.proowner,a.privilege_type,a.is_grantable)
                      ORDER BY a.grantee=p.proowner,a.grantor=p.proowner,a.privilege_type,a.is_grantable)
                    FROM aclexplode(COALESCE(p.proacl,acldefault('f',p.proowner))) a)) ORDER BY p.proname,oidvectortypes(p.proargtypes))
                FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace JOIN pg_language l ON l.oid=p.prolang
                WHERE n.nspname='public' AND p.proname IN ('foto_eliminacion_guard_v35','foto_eliminada_recibo_guard_v35','foto_eliminacion_completa_v35'))
            )::text
            """.formatted(TABLE_NAMES);

    private LegalV35PhotoDeletionSchema() { }

    static void verify(JdbcTemplate jdbc) {
        if (!V33_DELTA.equals(LegalV33ClosureSchema.snapshot(jdbc))
                || !LegalV34ClosureSchema.EXPECTED.equals(LegalV34ClosureSchema.snapshot(jdbc))
                || !EXPECTED.equals(snapshot(jdbc))) {
            throw new LegalEditorialOperationalException(LegalManifestIssueCode.SCHEMA_DRIFT, "database/schema");
        }
    }

    /** Catalog digest only; no photo, workshop or provider payload is observed. */
    static String snapshot(JdbcTemplate jdbc) {
        String catalog = jdbc.queryForObject(SQL, String.class);
        if (catalog == null) throw new LegalEditorialOperationalException(LegalManifestIssueCode.SCHEMA_DRIFT, "database/schema");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(catalog.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 no está disponible", impossible);
        }
    }
}
