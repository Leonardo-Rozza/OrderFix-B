package com.leonardorozza.mvgrreparacionesbackend.cuenta.recovery;

import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Portable PostgreSQL 16/V37 maintenance read profile. Never confers write privileges and never
 * replaces the historical write verifiers. Definitions are deparsed: physical OIDs, parse locations,
 * RI trigger names, storage statistics and mutable sequence values are deliberately not evidence.
 */
public final class RecoveryReadSchemaPreflight {
    // Independently reviewed PG16 catalogs. pg_restore distributes varchar[] -> text[] casts
    // across the literal array elements in 46 CHECKs and two index predicates. Everything else
    // is identical. Accept only these two complete catalogs, never rewrite arbitrary SQL at runtime.
    static final String MIGRATED_V37 = "a9424a71510dc7b894b78a93fdebf81937c33998f996d894e076a1a609f9e964";
    static final String RESTORED_V37 = "48f45d690c48d3422f415c00c2ffd6facd4fd5457626d618473518b98ce1e428";
    private static final String PROTOCOL = "ordenfix-recovery-read-schema/1\n";
    private static final int MAX_CATALOG_CHARS = 4 * 1024 * 1024;

    /*
     * Each row has a logical kind, identity and value. JSONB and C collation provide deterministic
     * framing/order. Index semantics, rather than the chosen equivalent index name, bind a FK's
     * dependency. ACLs normalize only the object's owner; every additional grantee stays explicit.
     */
    private static final String SQL = """
            WITH owner AS (
              SELECT relowner AS oid FROM pg_catalog.pg_class
              WHERE oid='public.flyway_schema_history'::pg_catalog.regclass
            ), relations AS (
              SELECT c.* FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
              WHERE n.nspname='public'
            ), indexes AS (
              SELECT i.indexrelid, jsonb_build_array(tn.nspname,t.relname,am.amname,
                i.indisunique,i.indnullsnotdistinct,i.indisexclusion,i.indimmediate,
                i.indisvalid,i.indisready,i.indislive,
                i.indnatts,i.indnkeyatts,
                (SELECT jsonb_agg(pg_get_indexdef(i.indexrelid,s,false) ORDER BY s)
                   FROM generate_series(1,i.indnatts::int) s),
                pg_get_expr(i.indpred,i.indrelid,false),
                (SELECT jsonb_agg(jsonb_build_array(n.nspname,o.opcname) ORDER BY x.ordinality)
                   FROM unnest(i.indclass::oid[]) WITH ORDINALITY x(oid,ordinality)
                   JOIN pg_opclass o ON o.oid=x.oid JOIN pg_namespace n ON n.oid=o.opcnamespace),
                (SELECT jsonb_agg(jsonb_build_array(n.nspname,c.collname) ORDER BY x.ordinality)
                   FROM unnest(i.indcollation::oid[]) WITH ORDINALITY x(oid,ordinality)
                   LEFT JOIN pg_collation c ON c.oid=x.oid LEFT JOIN pg_namespace n ON n.oid=c.collnamespace),
                i.indoption::text) AS definition
              FROM pg_index i JOIN pg_class t ON t.oid=i.indrelid
              JOIN pg_namespace tn ON tn.oid=t.relnamespace
              JOIN pg_class idx ON idx.oid=i.indexrelid JOIN pg_am am ON am.oid=idx.relam
              WHERE tn.nspname='public'
            ), privileges AS (
              SELECT 'schema' AS kind,n.nspname AS identity,n.nspowner AS owner,
                coalesce(n.nspacl,acldefault('n',n.nspowner)) AS acl
                FROM pg_namespace n WHERE n.nspname='public'
              UNION ALL
              SELECT 'relation',c.relname,c.relowner,
                coalesce(c.relacl,acldefault(CASE WHEN c.relkind='S' THEN 's'::"char" ELSE 'r'::"char" END,c.relowner))
                FROM relations c WHERE c.relkind IN ('r','p','v','m','f','S')
              UNION ALL
              SELECT 'column',c.relname||'.'||a.attname,c.relowner,a.attacl
                FROM relations c JOIN pg_attribute a ON a.attrelid=c.oid
                WHERE a.attnum>0 AND NOT a.attisdropped AND a.attacl IS NOT NULL
              UNION ALL
              SELECT 'function',p.proname||'('||pg_get_function_identity_arguments(p.oid)||')',p.proowner,
                coalesce(p.proacl,acldefault('f',p.proowner))
                FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public'
            ), entries AS (
              SELECT 'database' AS kind,''::text AS identity,jsonb_build_array(d.encoding,d.datlocprovider,
                d.datcollate,d.datctype,d.daticulocale,d.daticurules,d.datcollversion) AS value
                FROM pg_database d WHERE d.datname=current_database()
              UNION ALL
              SELECT 'schema',n.nspname,jsonb_build_array(
                CASE WHEN n.nspowner=(SELECT oid FROM owner) THEN 'OWNER'
                     WHEN pg_get_userbyid(n.nspowner)='pg_database_owner' THEN 'DATABASE_OWNER'
                     ELSE pg_get_userbyid(n.nspowner) END)
                FROM pg_namespace n WHERE n.nspname='public'
              UNION ALL
              SELECT 'relation',c.relname,jsonb_build_array(c.relkind,c.relpersistence,c.relrowsecurity,
                c.relforcerowsecurity,c.relispartition,c.relreplident,c.reloptions,
                c.relowner=(SELECT oid FROM owner),am.amname,
                CASE WHEN c.relkind='p' THEN pg_get_partkeydef(c.oid) END,
                pg_get_expr(c.relpartbound,c.oid,false),
                CASE WHEN c.relkind IN ('v','m') THEN pg_get_viewdef(c.oid,false) END,
                EXISTS(SELECT 1 FROM pg_rewrite r WHERE r.ev_class=c.oid),
                EXISTS(SELECT 1 FROM pg_policy p WHERE p.polrelid=c.oid))
                FROM relations c LEFT JOIN pg_am am ON am.oid=c.relam
              UNION ALL
              SELECT 'inheritance',cn.nspname||'.'||c.relname,jsonb_build_array(
                cn.nspname,c.relname,i.inhseqno,pn.nspname,p.relname,i.inhdetachpending)
                FROM pg_inherits i JOIN pg_class c ON c.oid=i.inhrelid
                JOIN pg_namespace cn ON cn.oid=c.relnamespace
                JOIN pg_class p ON p.oid=i.inhparent JOIN pg_namespace pn ON pn.oid=p.relnamespace
                WHERE cn.nspname='public' OR pn.nspname='public'
              UNION ALL
              SELECT 'column',c.relname||'.'||a.attname,jsonb_build_array(
                (SELECT count(*) FROM pg_attribute earlier WHERE earlier.attrelid=a.attrelid
                  AND earlier.attnum>0 AND earlier.attnum<=a.attnum AND NOT earlier.attisdropped),
                format_type(a.atttypid,a.atttypmod),a.attnotnull,a.attidentity,a.attgenerated,
                a.attislocal,a.attinhcount,a.attstorage,a.attcompression,a.attoptions,a.attfdwoptions,
                n.nspname,co.collname,pg_get_expr(d.adbin,d.adrelid,false))
                FROM relations c JOIN pg_attribute a ON a.attrelid=c.oid
                LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum
                LEFT JOIN pg_collation co ON co.oid=a.attcollation LEFT JOIN pg_namespace n ON n.oid=co.collnamespace
                WHERE a.attnum>0 AND NOT a.attisdropped
              UNION ALL
              SELECT 'constraint',c.relname||'.'||k.conname,jsonb_build_array(k.contype,k.convalidated,
                k.condeferrable,k.condeferred,k.conislocal,k.coninhcount,k.connoinherit,
                k.conparentid<>0,pg_get_constraintdef(k.oid,false),idx.definition)
                FROM pg_constraint k JOIN relations c ON c.oid=k.conrelid
                LEFT JOIN indexes idx ON idx.indexrelid=k.conindid
              UNION ALL
              SELECT 'index',c.relname,jsonb_build_array(idx.definition,i.indisprimary,i.indisreplident,
                i.indisclustered,pg_get_indexdef(c.oid,0,false))
                FROM relations c JOIN indexes idx ON idx.indexrelid=c.oid JOIN pg_index i ON i.indexrelid=c.oid
              UNION ALL
              SELECT 'user-trigger',c.relname||'.'||t.tgname,jsonb_build_array(t.tgenabled,t.tgtype,
                t.tgdeferrable,t.tginitdeferred,t.tgparentid<>0,pg_get_triggerdef(t.oid,false))
                FROM relations c JOIN pg_trigger t ON t.tgrelid=c.oid WHERE NOT t.tgisinternal
              UNION ALL
              SELECT 'internal-trigger',c.relname,jsonb_build_array(t.tgenabled,t.tgtype,t.tgdeferrable,
                t.tginitdeferred,t.tgparentid<>0,t.tgnargs,encode(t.tgargs,'hex'),
                pg_get_expr(t.tgqual,t.tgrelid,false),pn.nspname,p.proname,
                pg_get_function_identity_arguments(p.oid),kn.nspname,kc.relname,k.conname,
                rn.nspname,rc.relname,idx.definition,t.tgoldtable,t.tgnewtable,
                (SELECT jsonb_agg(a.attname ORDER BY x.ordinality)
                  FROM unnest(t.tgattr::smallint[]) WITH ORDINALITY x(num,ordinality)
                  JOIN pg_attribute a ON a.attrelid=t.tgrelid AND a.attnum=x.num))
                FROM relations c JOIN pg_trigger t ON t.tgrelid=c.oid
                JOIN pg_proc p ON p.oid=t.tgfoid JOIN pg_namespace pn ON pn.oid=p.pronamespace
                LEFT JOIN pg_constraint k ON k.oid=t.tgconstraint
                LEFT JOIN pg_class kc ON kc.oid=k.conrelid LEFT JOIN pg_namespace kn ON kn.oid=kc.relnamespace
                LEFT JOIN pg_class rc ON rc.oid=t.tgconstrrelid LEFT JOIN pg_namespace rn ON rn.oid=rc.relnamespace
                LEFT JOIN indexes idx ON idx.indexrelid=t.tgconstrindid WHERE t.tgisinternal
              UNION ALL
              SELECT 'function',p.proname||'('||pg_get_function_identity_arguments(p.oid)||')',
                jsonb_build_array(p.proowner=(SELECT oid FROM owner),l.lanname,p.prokind,p.prosecdef,
                  p.provolatile,p.proparallel,p.proisstrict,p.proleakproof,p.proretset,p.proconfig,
                  p.procost,p.prorows,p.probin,p.prosrc,
                  CASE WHEN p.prokind<>'a' THEN pg_get_functiondef(p.oid) END,
                  sn.nspname,sp.proname,CASE WHEN sp.oid IS NOT NULL THEN pg_get_function_identity_arguments(sp.oid) END)
                FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace JOIN pg_language l ON l.oid=p.prolang
                LEFT JOIN pg_proc sp ON sp.oid=p.prosupport LEFT JOIN pg_namespace sn ON sn.oid=sp.pronamespace
                WHERE n.nspname='public'
              UNION ALL
              SELECT 'sequence',c.relname,jsonb_build_array(format_type(s.seqtypid,NULL),s.seqstart,
                s.seqincrement,s.seqmax,s.seqmin,s.seqcache,s.seqcycle,
                (SELECT jsonb_agg(jsonb_build_array(n.nspname,r.relname,a.attname,d.deptype)
                    ORDER BY n.nspname,r.relname,a.attname,d.deptype)
                   FROM pg_depend d JOIN pg_class r ON r.oid=d.refobjid
                   JOIN pg_namespace n ON n.oid=r.relnamespace
                   JOIN pg_attribute a ON a.attrelid=r.oid AND a.attnum=d.refobjsubid
                   WHERE d.classid='pg_catalog.pg_class'::regclass AND d.objid=c.oid
                     AND d.refclassid='pg_catalog.pg_class'::regclass AND d.deptype IN ('a','i')))
                FROM relations c JOIN pg_sequence s ON s.seqrelid=c.oid
              UNION ALL
              SELECT 'rule',c.relname||'.'||r.rulename,jsonb_build_array(r.ev_enabled,pg_get_ruledef(r.oid,false))
                FROM relations c JOIN pg_rewrite r ON r.ev_class=c.oid
              UNION ALL
              SELECT 'policy',c.relname||'.'||p.polname,jsonb_build_array(p.polcmd,p.polpermissive,
                (SELECT jsonb_agg(CASE WHEN x=0 THEN 'PUBLIC' WHEN x=c.relowner THEN 'OWNER'
                  ELSE pg_get_userbyid(x) END ORDER BY CASE WHEN x=0 THEN 'PUBLIC' WHEN x=c.relowner THEN 'OWNER'
                  ELSE pg_get_userbyid(x) END COLLATE "C") FROM unnest(p.polroles) x),
                pg_get_expr(p.polqual,p.polrelid,false),pg_get_expr(p.polwithcheck,p.polrelid,false))
                FROM relations c JOIN pg_policy p ON p.polrelid=c.oid
              UNION ALL
              SELECT 'type',t.typname,jsonb_build_array(t.typtype,t.typcategory,t.typispreferred,t.typnotnull,
                t.typowner=(SELECT oid FROM owner),format_type(t.typbasetype,t.typtypmod),
                CASE WHEN t.typelem<>0 THEN format_type(t.typelem,NULL) END,
                cn.nspname,co.collname,
                (SELECT jsonb_agg(e.enumlabel ORDER BY e.enumsortorder) FROM pg_enum e WHERE e.enumtypid=t.oid),
                (SELECT jsonb_agg(jsonb_build_array(k.conname,pg_get_constraintdef(k.oid,false)) ORDER BY k.conname)
                   FROM pg_constraint k WHERE k.contypid=t.oid))
                FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace
                LEFT JOIN pg_collation co ON co.oid=t.typcollation LEFT JOIN pg_namespace cn ON cn.oid=co.collnamespace
                WHERE n.nspname='public'
              UNION ALL
              SELECT 'collation',n.nspname||'.'||co.collname,jsonb_build_array(co.collprovider,co.collisdeterministic,
                co.collencoding,co.collcollate,co.collctype,co.colliculocale,co.collicurules,co.collversion)
                FROM pg_collation co JOIN pg_namespace n ON n.oid=co.collnamespace
                WHERE n.nspname='public' OR EXISTS(SELECT 1 FROM relations c JOIN pg_attribute a ON a.attrelid=c.oid
                  WHERE a.attcollation=co.oid AND a.attnum>0 AND NOT a.attisdropped)
              UNION ALL
              SELECT 'acl',p.kind||':'||p.identity,jsonb_build_array(
                CASE WHEN a.grantee=0 THEN 'PUBLIC' WHEN a.grantee=p.owner THEN 'OWNER' ELSE pg_get_userbyid(a.grantee) END,
                CASE WHEN a.grantor=p.owner THEN 'OWNER' ELSE pg_get_userbyid(a.grantor) END,
                a.privilege_type,a.is_grantable)
                FROM privileges p CROSS JOIN LATERAL aclexplode(p.acl) a
              UNION ALL
              SELECT 'default-acl',coalesce(n.nspname,'*')||':'||d.defaclobjtype::text,jsonb_build_array(
                CASE WHEN d.defaclrole=(SELECT oid FROM owner) THEN 'OWNER' ELSE pg_get_userbyid(d.defaclrole) END,
                CASE WHEN a.grantee=0 THEN 'PUBLIC' WHEN a.grantee=d.defaclrole THEN 'OWNER' ELSE pg_get_userbyid(a.grantee) END,
                CASE WHEN a.grantor=d.defaclrole THEN 'OWNER' ELSE pg_get_userbyid(a.grantor) END,
                a.privilege_type,a.is_grantable)
                FROM pg_default_acl d LEFT JOIN pg_namespace n ON n.oid=d.defaclnamespace
                CROSS JOIN LATERAL aclexplode(d.defaclacl) a WHERE n.nspname='public' OR d.defaclnamespace=0
              UNION ALL
              SELECT 'migration',version,jsonb_build_array(version,type,script,checksum,success)
                FROM public.flyway_schema_history
                WHERE version IS NULL OR version !~ '^[0-9]+$' OR version::numeric>=27
            )
            SELECT coalesce(jsonb_agg(jsonb_build_array(kind,identity,value)
              ORDER BY kind COLLATE "C",identity COLLATE "C",value::text COLLATE "C"),'[]'::jsonb)::text FROM entries
            """;

    private RecoveryReadSchemaPreflight() { }

    public static void require(JdbcTemplate jdbc) {
        String observed = fingerprint(jdbc);
        if (!MIGRATED_V37.equals(observed) && !RESTORED_V37.equals(observed)) throw new Rejected();
    }

    /** Diagnostic only: returning a hash does not accept it as a supported schema. */
    public static String fingerprint(JdbcTemplate jdbc) {
        try {
            Objects.requireNonNull(jdbc);
            Boolean session = jdbc.queryForObject("""
                    SELECT current_setting('server_version_num')::integer BETWEEN 160000 AND 169999
                      AND current_setting('transaction_isolation')='repeatable read'
                      AND current_setting('transaction_read_only')='on'
                      AND replace(current_setting('search_path'),' ','')='pg_catalog,public,pg_temp'
                      AND current_setting('row_security')='off'
                      AND current_setting('TimeZone')='UTC'
                      AND current_setting('DateStyle')='ISO, YMD'
                      AND current_setting('IntervalStyle')='postgres'
                      AND current_setting('bytea_output')='hex'
                      AND current_setting('extra_float_digits')='3'
                    """, Boolean.class);
            if (!Boolean.TRUE.equals(session)) throw new Rejected();
            String catalog = jdbc.queryForObject(SQL, String.class);
            if (catalog == null || catalog.length() > MAX_CATALOG_CHARS) throw new Rejected();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((PROTOCOL + catalog).getBytes(StandardCharsets.UTF_8)));
        } catch (Rejected rejected) {
            throw rejected;
        } catch (RuntimeException | NoSuchAlgorithmException failure) {
            throw new Rejected();
        }
    }

    public static final class Rejected extends RuntimeException {
        private Rejected() { super("El esquema no admite la lectura segura de recuperación."); }
    }
}
