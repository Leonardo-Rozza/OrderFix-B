package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Exact V34 confirmation/command/outbox delta; V27–V33 resources and fingerprints stay frozen. */
final class LegalV34ClosureSchema {
    static final String FLYWAY_SCRIPT = "V34__cierre_confirmado_taller.sql";
    // Frozen from the final V34 resource migrated on a fresh PostgreSQL 16 instance.
    static final int FLYWAY_CHECKSUM = -1749634207;
    static final String EXPECTED = "d85cd057703c8cf2c5edb9917c004b696df38bda63d2ef4eb4a9fb5af3df79ef";
    static final String V33_DELTA = "ebc3478a5b9dcac1be47d561e4d858d5cea2d03028491069b445569231fa788b";
    static final LegalV29AcceptanceInventory.CatalogFingerprint LEGAL_CATALOG =
            new LegalV29AcceptanceInventory.CatalogFingerprint(
                    12,"28c4c52db8902f5097d957b7f7ad1263e7398eec719c482816e5ed41e194adb5",
                    125,"1b2af7407adbff4f9236d83dc1f0a93627cfe295647a6dfe6e745da5614a4c1b",
                    100,"7f2a9fd6df89df1edc9efd21945db18cbf15b842180c844737703ff202fea646",
                    43,"f67d6a99f2c90d9b2e31b3b82f0b0a05201dbec2da1d089ccb08f4e5be347235",
                    49,"76d982d5b3c6260d065d67c6ae52c4810fc69a017f9671b892b488699cef9e61",
                    5,"7df57cfa66f5f92adc282732fc09a25a1a76407665b20793f7e729fd8712cdbf");
    static final List<String> TABLES = List.of(
            "cuenta_cierre_confirmaciones", "cuenta_cierre_operaciones", "cuenta_cierre_efectos");
    static final List<String> FUNCTIONS = List.of(
            "cuenta_cierre_confirmacion_guard_v34", "cuenta_cierre_operacion_guard_v34",
            "cuenta_cierre_efecto_guard_v34", "cuenta_cierre_renewal_guard_v34", "cuenta_cierre_link_observation_v34");
    static final Map<String,String> TRIGGERS = Map.of(
            "cuenta_cierre_confirmaciones", "aa_cuenta_cierre_confirmacion_v34",
            "cuenta_cierre_operaciones", "aa_cuenta_cierre_operacion_v34",
            "cuenta_cierre_efectos", "aa_cuenta_cierre_efecto_v34",
            "suscripciones", "ab_cuenta_cierre_renewal_v34",
            "subscription_provider_links", "ac_cuenta_cierre_link_v34");
    private static final String TABLE_NAMES = java.util.stream.Stream.concat(TABLES.stream(), java.util.stream.Stream.of("suscripciones", "subscription_provider_links"))
            .map(name -> "'" + name + "'").collect(java.util.stream.Collectors.joining(","));
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
                WHERE n.nspname='public' AND c.relname IN ('cuenta_cierre_confirmaciones','cuenta_cierre_operaciones','cuenta_cierre_efectos')),
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
                WHERE n.nspname='public' AND c.relname IN ('cuenta_cierre_confirmaciones','cuenta_cierre_operaciones','cuenta_cierre_efectos') AND a.attnum>0),
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
                WHERE n.nspname='public' AND c.relname IN ('cuenta_cierre_confirmaciones','cuenta_cierre_operaciones','cuenta_cierre_efectos')),
              'indexes', (SELECT jsonb_agg(jsonb_build_array(c.relname,idx.relname,idx.relkind,idx.relpersistence,
                  am.amname,idx.reloptions,i.indisunique,i.indnullsnotdistinct,i.indisprimary,i.indisexclusion,
                  i.indimmediate,i.indisclustered,i.indisvalid,i.indcheckxmin,i.indisready,i.indislive,i.indisreplident,
                  i.indnatts,i.indnkeyatts,i.indkey::text,i.indcollation::text,i.indclass::text,i.indoption::text,
                  pg_get_indexdef(i.indexrelid)) ORDER BY c.relname,idx.relname)
                FROM pg_index i JOIN pg_class c ON c.oid=i.indrelid JOIN pg_class idx ON idx.oid=i.indexrelid
                JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_am am ON am.oid=idx.relam
                WHERE n.nspname='public' AND c.relname IN ('cuenta_cierre_confirmaciones','cuenta_cierre_operaciones','cuenta_cierre_efectos')),
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
                WHERE n.nspname='public' AND p.proname IN ('cuenta_cierre_confirmacion_guard_v34','cuenta_cierre_operacion_guard_v34',
                    'cuenta_cierre_efecto_guard_v34','cuenta_cierre_renewal_guard_v34','cuenta_cierre_link_observation_v34'))
            )::text
            """.formatted(TABLE_NAMES);

    private LegalV34ClosureSchema() { }

    static void verify(JdbcTemplate jdbc) {
        if (!V33_DELTA.equals(LegalV33ClosureSchema.snapshot(jdbc)) || !EXPECTED.equals(snapshot(jdbc))) {
            throw new LegalEditorialOperationalException(LegalManifestIssueCode.SCHEMA_DRIFT, "database/schema");
        }
    }

    /** Diagnostic output contains a digest only, never confirmation or outbox rows. */
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
