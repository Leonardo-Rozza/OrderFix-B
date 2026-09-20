package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Exact V37 operational deletion capability. Historical fingerprints remain independent and frozen. */
public final class LegalV37OperationalDeletionSchema {
    static final String FLYWAY_SCRIPT = "V37__borrado_operativo_taller.sql";
    // Filled exclusively from the final migration on disposable PostgreSQL 16 by LegalV37SchemaSnapshot.
    static final int FLYWAY_CHECKSUM = 6712563;
    static final String EXPECTED = "8286a4003e05b279f250fed4323e618ed9de3d6bf0b1d42c459095551fe8fff1";
    static final String V33_DELTA = "cb49d289f80cf377ea9d04271238c46f1fd7f474c0c60dcd1fdb4a4602278661";
    static final String V35_DELTA = "1f87ce70ba546cdf7174c0d4363b57f09c5f7eee5ed2d4dcdc3c50dcfee3d4cd";
    static final String PHOTO_CATALOG = "a2c4a2bab28fc63dc3a3465967edfa7f3f9c36ecf8adc0cdcef305af415350cf";
    static final List<String> TABLES = List.of("cuenta_borrado_lotes", "cuenta_borrado_contextos");
    static final String ENTRY_SIGNATURE = "public.cuenta_cierre_borrar_lote_v37(uuid,bigint,uuid,text)";
    private static final String TABLE_NAMES = "'cuenta_borrado_lotes','cuenta_borrado_contextos'";
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
                    FROM aclexplode(COALESCE(c.relacl,acldefault('r',c.relowner))) a)) ORDER BY c.relname)
                FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='public' AND c.relname IN ('cuenta_borrado_lotes','cuenta_borrado_contextos')),
              'columns', (SELECT jsonb_agg(jsonb_build_array(c.relname,a.attnum,a.attname,
                  format_type(a.atttypid,a.atttypmod),a.attnotnull,a.attidentity,a.attgenerated,a.attisdropped,
                  a.attstorage,a.attcompression,a.attoptions,cn.nspname,co.collname,pg_get_expr(d.adbin,d.adrelid),
                  (SELECT jsonb_agg(jsonb_build_array(acl.grantee=c.relowner,acl.grantor=c.relowner,acl.privilege_type,acl.is_grantable)
                      ORDER BY acl.grantee=c.relowner,acl.grantor=c.relowner,acl.privilege_type,acl.is_grantable)
                    FROM aclexplode(a.attacl) acl))
                  ORDER BY c.relname,a.attnum)
                FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace
                LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum
                LEFT JOIN pg_collation co ON co.oid=a.attcollation LEFT JOIN pg_namespace cn ON cn.oid=co.collnamespace
                WHERE n.nspname='public' AND c.relname IN ('cuenta_borrado_lotes','cuenta_borrado_contextos') AND a.attnum>0),
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
                WHERE n.nspname='public' AND c.relname IN ('cuenta_borrado_lotes','cuenta_borrado_contextos')),
              'indexes', (SELECT jsonb_agg(jsonb_build_array(c.relname,idx.relname,idx.relkind,idx.relpersistence,
                  am.amname,idx.reloptions,i.indisunique,i.indnullsnotdistinct,i.indisprimary,i.indisexclusion,
                  i.indimmediate,i.indisclustered,i.indisvalid,i.indcheckxmin,i.indisready,i.indislive,i.indisreplident,
                  i.indnatts,i.indnkeyatts,i.indkey::text,i.indcollation::text,i.indclass::text,i.indoption::text,
                  pg_get_indexdef(i.indexrelid)) ORDER BY c.relname,idx.relname)
                FROM pg_index i JOIN pg_class c ON c.oid=i.indrelid JOIN pg_class idx ON idx.oid=i.indexrelid
                JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_am am ON am.oid=idx.relam
                WHERE n.nspname='public' AND c.relname IN ('cuenta_borrado_lotes','cuenta_borrado_contextos')),
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
                    FROM aclexplode(COALESCE(p.proacl,acldefault('f',p.proowner))) a
                    WHERE NOT (p.proname='cuenta_cierre_borrar_lote_v37' AND a.grantee<>0 AND a.grantee<>p.proowner)))
                  ORDER BY p.proname,oidvectortypes(p.proargtypes))
                FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace JOIN pg_language l ON l.oid=p.prolang
                WHERE n.nspname='public' AND p.proname ~ '_v37$')
            )::text
            """.formatted(TABLE_NAMES);

    private LegalV37OperationalDeletionSchema() { }

    /** Public narrow entry for the internal deletion service; this does not confer a SQL capability. */
    public static void require(JdbcTemplate jdbc) {
        var verifier = new LegalV29AcceptanceSchemaVerifier(Objects.requireNonNull(jdbc), "public");
        verifier.verify();
        if (verifier.workshopClosureSchemaVersion() != 37) incompatible();
    }

    /** Delta only, invoked by V29 after its full historical/legal surface; no recursive dispatch. */
    static void verify(JdbcTemplate jdbc) {
        var catalogs = List.of(LegalV33ClosureSchema.snapshot(jdbc),
                LegalV34ClosureSchema.snapshot(jdbc), LegalV35PhotoDeletionSchema.snapshot(jdbc), snapshot(jdbc));
        if (!catalogs.equals(List.of(V33_DELTA, LegalV34ClosureSchema.EXPECTED, V35_DELTA, EXPECTED))
                && !catalogs.equals(LegalRestoredV37Catalogs.CLOSURE_DELTAS)) incompatible();
        // The SQL entry is deliberately grantable to a restricted executor role. Its name/OID is
        // installation-specific; PUBLIC, delegation, and non-owner grantors are never acceptable.
        Boolean unsafeGrant = jdbc.queryForObject("""
                SELECT EXISTS (
                  SELECT 1 FROM pg_catalog.pg_proc p
                    JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace
                    CROSS JOIN LATERAL pg_catalog.aclexplode(
                      COALESCE(p.proacl,pg_catalog.acldefault('f',p.proowner))) a
                   WHERE n.nspname='public' AND p.proname='cuenta_cierre_borrar_lote_v37'
                     AND (a.grantee=0 OR a.grantor<>p.proowner OR a.privilege_type<>'EXECUTE'
                       OR (a.grantee<>p.proowner AND a.is_grantable)))
                """, Boolean.class);
        if (!Boolean.FALSE.equals(unsafeGrant)) incompatible();
    }

    /** Catalog metadata only; never reads operational rows, context payloads or receipt values. */
    static String snapshot(JdbcTemplate jdbc) {
        String catalog = jdbc.queryForObject(SQL, String.class);
        if (catalog == null) incompatible();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(catalog.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 no está disponible", impossible);
        }
    }

    private static void incompatible() {
        throw new LegalEditorialOperationalException(LegalManifestIssueCode.SCHEMA_DRIFT, "database/schema");
    }
}
