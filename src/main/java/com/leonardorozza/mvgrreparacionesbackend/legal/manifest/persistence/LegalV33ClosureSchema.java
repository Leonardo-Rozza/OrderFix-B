package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Exact V33 closure delta on clean PostgreSQL 16; older inventories remain frozen. */
final class LegalV33ClosureSchema {
    static final String FLYWAY_SCRIPT = "V33__cierre_coordinado_taller.sql";
    // Frozen from the final V33 resource migrated on a fresh PostgreSQL 16 instance.
    static final int FLYWAY_CHECKSUM = 1626375596;
    static final String EXPECTED = "461115b871cb59f089b865e992835a1385a7412a3d152af651f79b49e94edd6e";
    static final LegalV29AcceptanceInventory.CatalogFingerprint LEGAL_CATALOG =
            new LegalV29AcceptanceInventory.CatalogFingerprint(
                    12,"28c4c52db8902f5097d957b7f7ad1263e7398eec719c482816e5ed41e194adb5",
                    125,"1b2af7407adbff4f9236d83dc1f0a93627cfe295647a6dfe6e745da5614a4c1b",
                    100,"7f2a9fd6df89df1edc9efd21945db18cbf15b842180c844737703ff202fea646",
                    43,"f67d6a99f2c90d9b2e31b3b82f0b0a05201dbec2da1d089ccb08f4e5be347235",
                    49,"76d982d5b3c6260d065d67c6ae52c4810fc69a017f9671b892b488699cef9e61",
                    5,"7df57cfa66f5f92adc282732fc09a25a1a76407665b20793f7e729fd8712cdbf");
    static final String PHOTO_CATALOG = "3b88eb2fb5488c30934eba177c522c0baf6d70953c2a9f72c35eccaf390bd4da";
    static final List<String> GUARDED_TABLES = List.of(
            "talleres", "users", "clientes", "equipos", "reparaciones", "repuestos", "articulos",
            "presupuestos", "cobros", "taller_qr_cobro", "reparacion_fotos", "presupuesto_items",
            "auth_tokens", "suscripciones", "subscription_provider_links", "subscription_payments",
            "cuenta_reautenticaciones", "cuenta_exportaciones", "reparacion_fotos_privadas",
            "reparacion_foto_atestaciones", "legal_aceptacion_lotes", "legal_aceptaciones",
            "legal_aceptacion_documentos", "legal_aceptacion_metadatos", "legal_aceptacion_metadatos_cifrados",
            "legal_idempotencia_resultados", "legal_idempotencia_sin_actos", "legal_idempotencia_sin_actos_referencias");
    private static final String TABLE_NAMES = java.util.stream.Stream.concat(GUARDED_TABLES.stream(), java.util.stream.Stream.of("cuenta_cierres"))
            .map(name -> "'" + name + "'").collect(java.util.stream.Collectors.joining(","));
    private static final String SQL = """
            SELECT pg_catalog.jsonb_build_object(
              'tables', (SELECT jsonb_agg(jsonb_build_array(c.relname,c.relkind,c.relpersistence,c.relrowsecurity,
                  c.relforcerowsecurity,c.relispartition,c.relreplident,c.reloptions,
                  c.relowner=(SELECT relowner FROM pg_class WHERE oid='public.flyway_schema_history'::regclass),
                  EXISTS(SELECT 1 FROM pg_inherits i WHERE i.inhrelid=c.oid OR i.inhparent=c.oid),
                  EXISTS(SELECT 1 FROM pg_rewrite r WHERE r.ev_class=c.oid),
                  EXISTS(SELECT 1 FROM pg_policy p WHERE p.polrelid=c.oid),
                  (CASE WHEN c.relname='cuenta_cierres' THEN (SELECT jsonb_agg(jsonb_build_array(a.grantee=c.relowner,a.grantor=c.relowner,a.privilege_type,a.is_grantable)
                      ORDER BY a.grantee=c.relowner,a.grantor=c.relowner,a.privilege_type,a.is_grantable)
                    FROM aclexplode(COALESCE(c.relacl,acldefault('r',c.relowner))) a) ELSE NULL END)) ORDER BY c.relname)
                FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='public' AND c.relname IN ('cuenta_cierres','reparacion_fotos','presupuesto_items','auth_tokens')),
              'columns', (SELECT jsonb_agg(jsonb_build_array(c.relname,a.attnum,a.attname,
                  format_type(a.atttypid,a.atttypmod),a.attnotnull,a.attidentity,a.attgenerated,a.attisdropped,
                  a.attstorage,a.attcompression,a.attoptions,cn.nspname,co.collname,pg_get_expr(d.adbin,d.adrelid))
                  ORDER BY c.relname,a.attnum)
                FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace
                LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum
                LEFT JOIN pg_collation co ON co.oid=a.attcollation LEFT JOIN pg_namespace cn ON cn.oid=co.collnamespace
                WHERE n.nspname='public' AND c.relname IN ('cuenta_cierres','reparacion_fotos','presupuesto_items','auth_tokens') AND a.attnum>0),
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
                WHERE n.nspname='public' AND c.relname IN ('cuenta_cierres','reparacion_fotos','presupuesto_items','auth_tokens')),
              'indexes', (SELECT jsonb_agg(jsonb_build_array(c.relname,idx.relname,idx.relkind,idx.relpersistence,
                  am.amname,idx.reloptions,i.indisunique,i.indnullsnotdistinct,i.indisprimary,i.indisexclusion,
                  i.indimmediate,i.indisclustered,i.indisvalid,i.indcheckxmin,i.indisready,i.indislive,i.indisreplident,
                  i.indnatts,i.indnkeyatts,i.indkey::text,i.indcollation::text,i.indclass::text,i.indoption::text,
                  pg_get_indexdef(i.indexrelid)) ORDER BY c.relname,idx.relname)
                FROM pg_index i JOIN pg_class c ON c.oid=i.indrelid JOIN pg_class idx ON idx.oid=i.indexrelid
                JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_am am ON am.oid=idx.relam
                WHERE n.nspname='public' AND c.relname IN ('cuenta_cierres','reparacion_fotos','presupuesto_items','auth_tokens')),
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
                WHERE n.nspname='public' AND p.proname IN ('cuenta_cierre_exclusivo_v33','cuenta_cierre_estado_v33',
                    'cuenta_cierre_guard_v33','cuenta_cierre_historial_guard_v33'))
            )::text
            """.formatted(TABLE_NAMES);

    private LegalV33ClosureSchema() { }

    static void verify(JdbcTemplate jdbc) {
        if (!EXPECTED.equals(snapshot(jdbc))) {
            throw new LegalEditorialOperationalException(LegalManifestIssueCode.SCHEMA_DRIFT, "database/schema");
        }
    }

    /** Diagnostic output contains a digest only, never account rows or credentials. */
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
