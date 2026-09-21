package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Exact V38 profile suppression capability; older resources and fingerprint constants remain frozen. */
public final class LegalV38ProfileErasureSchema {
    static final String FLYWAY_SCRIPT = "V38__supresion_perfil_cuenta.sql";
    // Frozen only by LegalV38SchemaSnapshot on disposable migrated/restored PostgreSQL 16.
    static final int FLYWAY_CHECKSUM = 0; // TO_FREEZE
    static final List<String> TABLES = List.of("cuenta_perfil_bajas", "cuenta_perfil_baja_contextos");
    static final String ENTRY_SIGNATURE = "public.cuenta_cierre_suprimir_perfil_v38(uuid,bigint,uuid)";
    private static final String TABLE_NAMES = "'cuenta_perfil_bajas','cuenta_perfil_baja_contextos','users','talleres','taller_qr_cobro'";
    static final Profile MIGRATED = new Profile(
            LegalV27ImportInventory.EXPECTED_CATALOG,
            LegalV27EditorialInventory.EXPECTED_CATALOG,
            new LegalV29AcceptanceInventory.CatalogFingerprint(0,"TO_FREEZE",0,"TO_FREEZE",0,"TO_FREEZE",0,"TO_FREEZE",0,"TO_FREEZE",0,"TO_FREEZE"),
            List.of("TO_FREEZE_33","TO_FREEZE_34","TO_FREEZE_35","TO_FREEZE_37","TO_FREEZE_PHOTO","TO_FREEZE_38"));
    static final Profile RESTORED = new Profile(
            LegalRestoredV37Catalogs.IMPORT,
            LegalRestoredV37Catalogs.EDITORIAL,
            new LegalV29AcceptanceInventory.CatalogFingerprint(0,"TO_FREEZE",0,"TO_FREEZE",0,"TO_FREEZE",0,"TO_FREEZE",0,"TO_FREEZE",0,"TO_FREEZE"),
            List.of("TO_FREEZE_33","TO_FREEZE_34","TO_FREEZE_35","TO_FREEZE_37","TO_FREEZE_PHOTO","TO_FREEZE_38"));

    private static final String SQL = """
            SELECT pg_catalog.jsonb_build_object(
              'tables', (SELECT jsonb_agg(jsonb_build_array(c.relname,c.relkind,c.relpersistence,c.relrowsecurity,
                  c.relforcerowsecurity,c.relispartition,c.relreplident,c.reloptions,
                  c.relowner=(SELECT relowner FROM pg_class WHERE oid='public.flyway_schema_history'::regclass),
                  EXISTS(SELECT 1 FROM pg_inherits i WHERE i.inhrelid=c.oid OR i.inhparent=c.oid),
                  EXISTS(SELECT 1 FROM pg_rewrite r WHERE r.ev_class=c.oid),
                  EXISTS(SELECT 1 FROM pg_policy p WHERE p.polrelid=c.oid),
                  (CASE WHEN c.relname<>'taller_qr_cobro' THEN (SELECT jsonb_agg(jsonb_build_array(a.grantee=c.relowner,a.grantor=c.relowner,a.privilege_type,a.is_grantable)
                      ORDER BY a.grantee=c.relowner,a.grantor=c.relowner,a.privilege_type,a.is_grantable)
                    FROM aclexplode(COALESCE(c.relacl,acldefault('r',c.relowner))) a) ELSE NULL END)) ORDER BY c.relname)
                FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='public' AND c.relname IN ('cuenta_perfil_bajas','cuenta_perfil_baja_contextos','taller_qr_cobro')),
              'columns', (SELECT jsonb_agg(jsonb_build_array(c.relname,a.attnum,a.attname,
                  format_type(a.atttypid,a.atttypmod),a.attnotnull,a.attidentity,a.attgenerated,a.attisdropped,
                  a.attstorage,a.attcompression,a.attoptions,cn.nspname,co.collname,pg_get_expr(d.adbin,d.adrelid),
                  (SELECT jsonb_agg(jsonb_build_array(acl.grantee=c.relowner,acl.grantor=c.relowner,acl.privilege_type,acl.is_grantable)
                      ORDER BY acl.grantee=c.relowner,acl.grantor=c.relowner,acl.privilege_type,acl.is_grantable)
                    FROM aclexplode(a.attacl) acl WHERE c.relname<>'taller_qr_cobro'))
                  ORDER BY c.relname,a.attnum)
                FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace
                LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum
                LEFT JOIN pg_collation co ON co.oid=a.attcollation LEFT JOIN pg_namespace cn ON cn.oid=co.collnamespace
                WHERE n.nspname='public' AND c.relname IN ('cuenta_perfil_bajas','cuenta_perfil_baja_contextos','taller_qr_cobro') AND a.attnum>0),
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
                WHERE n.nspname='public' AND c.relname IN ('cuenta_perfil_bajas','cuenta_perfil_baja_contextos','taller_qr_cobro')),
              'indexes', (SELECT jsonb_agg(jsonb_build_array(c.relname,idx.relname,idx.relkind,idx.relpersistence,
                  am.amname,idx.reloptions,i.indisunique,i.indnullsnotdistinct,i.indisprimary,i.indisexclusion,
                  i.indimmediate,i.indisclustered,i.indisvalid,i.indcheckxmin,i.indisready,i.indislive,i.indisreplident,
                  i.indnatts,i.indnkeyatts,i.indkey::text,i.indcollation::text,i.indclass::text,i.indoption::text,
                  pg_get_indexdef(i.indexrelid)) ORDER BY c.relname,idx.relname)
                FROM pg_index i JOIN pg_class c ON c.oid=i.indrelid JOIN pg_class idx ON idx.oid=i.indexrelid
                JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_am am ON am.oid=idx.relam
                WHERE n.nspname='public' AND c.relname IN ('cuenta_perfil_bajas','cuenta_perfil_baja_contextos','taller_qr_cobro')),
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
                    WHERE NOT (p.proname='cuenta_cierre_suprimir_perfil_v38' AND a.grantee<>0 AND a.grantee<>p.proowner)))
                  ORDER BY p.proname,oidvectortypes(p.proargtypes))
                FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace JOIN pg_language l ON l.oid=p.prolang
                WHERE n.nspname='public' AND p.proname ~ '_v38$')
            )::text
            """.formatted(TABLE_NAMES);

    private LegalV38ProfileErasureSchema() { }

    /** Accredit before the SQL entry; this does not confer EXECUTE or authorize a target workshop. */
    public static void require(JdbcTemplate jdbc) {
        var verifier = new LegalV29AcceptanceSchemaVerifier(Objects.requireNonNull(jdbc), "public");
        verifier.verify();
        if (verifier.workshopClosureSchemaVersion() != 38) incompatible();
    }

    /** Called only after V29's base/function checks; no recursive runtime dispatch. */
    static void verify(JdbcTemplate jdbc) {
        var observed = profileSnapshot(jdbc);
        // Never combine migrated and restored fragments into an unmeasured third profile.
        if (!MIGRATED.equals(observed) && !RESTORED.equals(observed)) incompatible();
        Boolean unsafe = jdbc.queryForObject("""
                SELECT EXISTS (
                  SELECT 1 FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace
                    CROSS JOIN LATERAL pg_catalog.aclexplode(COALESCE(p.proacl,pg_catalog.acldefault('f',p.proowner))) a
                   WHERE n.nspname='public'
                     AND p.proname IN ('cuenta_cierre_borrar_lote_v37','cuenta_cierre_suprimir_perfil_v38')
                     AND (a.grantee=0 OR a.grantor<>p.proowner OR a.privilege_type<>'EXECUTE'
                       OR (a.grantee<>p.proowner AND a.is_grantable)))
                """, Boolean.class);
        if (!Boolean.FALSE.equals(unsafe)) incompatible();
    }

    static boolean acceptsImport(JdbcTemplate jdbc, String schema, LegalV27ImportInventory.CatalogFingerprint catalog) {
        return (MIGRATED.importCatalog().equals(catalog) || RESTORED.importCatalog().equals(catalog))
                && hasExactHistory(jdbc, schema);
    }

    static boolean acceptsEditorial(JdbcTemplate jdbc, String schema, LegalV27EditorialInventory.CatalogFingerprint catalog) {
        return (MIGRATED.editorialCatalog().equals(catalog) || RESTORED.editorialCatalog().equals(catalog))
                && hasExactHistory(jdbc, schema);
    }

    static boolean acceptsLegal(LegalV29AcceptanceInventory.CatalogFingerprint catalog) {
        return MIGRATED.legalCatalog().equals(catalog) || RESTORED.legalCatalog().equals(catalog);
    }

    static boolean acceptsPhotos(String catalog) {
        return MIGRATED.deltas().get(4).equals(catalog) || RESTORED.deltas().get(4).equals(catalog);
    }

    private static boolean hasExactHistory(JdbcTemplate jdbc, String schema) {
        if (!"public".equals(schema)) return false;
        try {
            return new LegalV29AcceptanceSchemaVerifier(jdbc, schema).workshopClosureSchemaVersion() == 38;
        } catch (LegalEditorialOperationalException failure) {
            if (failure.issue().code() == LegalManifestIssueCode.SCHEMA_DRIFT) return false;
            throw failure;
        }
    }

    /** Diagnostics expose catalog digests only, never account/evidence rows or private context. */
    static Profile profileSnapshot(JdbcTemplate jdbc) {
        return new Profile(new LegalV27ImportSchemaVerifier(jdbc,"public").snapshot().catalog(),
                new LegalEditorialSchemaVerifier(jdbc,"public").snapshot().catalog(),
                new LegalV29AcceptanceSchemaVerifier(jdbc,"public").snapshot().catalog(),
                List.of(LegalV33ClosureSchema.snapshot(jdbc),LegalV34ClosureSchema.snapshot(jdbc),
                        LegalV35PhotoDeletionSchema.snapshot(jdbc),LegalV37OperationalDeletionSchema.snapshot(jdbc),
                        LegalPrivatePhotoSchema.snapshot(jdbc),snapshot(jdbc)));
    }

    static String snapshot(JdbcTemplate jdbc) {
        String catalog = jdbc.queryForObject(SQL, String.class);
        if (catalog == null) incompatible();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(catalog.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 no está disponible"); }
    }

    record Profile(LegalV27ImportInventory.CatalogFingerprint importCatalog,
                   LegalV27EditorialInventory.CatalogFingerprint editorialCatalog,
                   LegalV29AcceptanceInventory.CatalogFingerprint legalCatalog, List<String> deltas) {
        Profile { deltas = List.copyOf(deltas); }
    }

    private static void incompatible() {
        throw new LegalEditorialOperationalException(LegalManifestIssueCode.SCHEMA_DRIFT,"database/schema");
    }
}
