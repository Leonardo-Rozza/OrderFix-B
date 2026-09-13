package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Accredit V29 unchanged and the subscription dependency required by the isolated registration role.
 * Constants describe clean PostgreSQL 16 after V2, V11 and V18; no observed catalog is trusted as a baseline.
 * Sequence values are intentionally excluded because allocating an identity is not schema drift.
 */
final class LegalRegistrationSchemaVerifier implements LegalDatabasePreflight {
    static final String ISSUE_LOCATION = "database/schema";
    private static final String TABLE_NAMES = "'suscripciones'";
    private static final String SEQUENCE_NAMES = "'suscripciones_id_seq'";

    // Frozen once against clean PostgreSQL 16, before any mutation fixture executes.
    private static final CatalogFingerprint EXPECTED = new CatalogFingerprint(
            1, "11ebbf93e7305bebc56324d2625fb12833a25aca6311b3bbff3c0472b2deb505", 19, "5f363c06f3678ca575416bee6de7b4153349503231152f8b341e438388615683", 3, "cc001b8c4555c69586a7ec2141a89ae68f134c0ee9a1fc9f2ee8e8ae782b9cf6",
            4, "5a2ec727e88d5eaac700cf85d52fea8cb760b806e01367f59e40148e12063e61", 6, "e6dbcea517b1d2363648075095202f21f7b3aa21b21786d9ca9e5d53c810ae75", 1, "0da85b25a072b9481fd57c3fe2b40f847dc1e720820e065cdffed3a7df7763a0");

    // Recorded independently from the V33 closure diagnostic; V29–V32 retain EXPECTED above.
    private static final CatalogFingerprint EXPECTED_V33 = new CatalogFingerprint(
            1,"11ebbf93e7305bebc56324d2625fb12833a25aca6311b3bbff3c0472b2deb505",
            19,"5f363c06f3678ca575416bee6de7b4153349503231152f8b341e438388615683",
            3,"cc001b8c4555c69586a7ec2141a89ae68f134c0ee9a1fc9f2ee8e8ae782b9cf6",
            4,"5a2ec727e88d5eaac700cf85d52fea8cb760b806e01367f59e40148e12063e61",
            7,"d3fa1f4953925220d924c93424968f39b84fa92682c596d4d55f3ed6f114707e",
            1,"0da85b25a072b9481fd57c3fe2b40f847dc1e720820e065cdffed3a7df7763a0");

    private final JdbcTemplate jdbc;
    private final String expectedSchema;
    private final LegalV29AcceptanceSchemaVerifier acceptance;

    LegalRegistrationSchemaVerifier(JdbcTemplate jdbc, String expectedSchema) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        if (!"public".equals(expectedSchema)) {
            throw new IllegalArgumentException("El registro requiere el schema public");
        }
        this.expectedSchema = expectedSchema;
        this.acceptance = new LegalV29AcceptanceSchemaVerifier(jdbc, expectedSchema);
    }

    @Override
    public void verify() {
        acceptance.verify();
        verifyTopology();
        CatalogFingerprint expected = acceptance.usesWorkshopClosureSchema() ? EXPECTED_V33 : EXPECTED;
        if (!expected.equals(catalogFingerprint())) incompatible();
    }

    @Override
    public boolean usesJdbc(JdbcTemplate candidate) {
        return jdbc == candidate && acceptance.usesJdbc(candidate);
    }

    String expectedSchema() { return expectedSchema; }

    private void verifyTopology() {
        List<Boolean> clean = jdbc.queryForList("""
                SELECT NOT EXISTS (SELECT 1 FROM pg_catalog.pg_inherits i
                                    WHERE i.inhparent = c.oid OR i.inhrelid = c.oid)
                   AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_rewrite r WHERE r.ev_class = c.oid)
                   AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_policy p WHERE p.polrelid = c.oid)
                  FROM pg_catalog.pg_class c
                  JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = ? AND c.relname = 'suscripciones'
                """, Boolean.class, expectedSchema);
        if (!clean.equals(List.of(true))) incompatible();
    }

    private CatalogFingerprint catalogFingerprint() {
        List<String> tables = canonicalRows("""
                SELECT pg_catalog.jsonb_build_array(
                           c.relname, c.relkind, c.relpersistence,
                           c.relrowsecurity, c.relforcerowsecurity,
                           c.relispartition, c.relreplident,
                           COALESCE(am.amname, ''), COALESCE(c.reloptions, '{}'::text[]))::text
                  FROM pg_catalog.pg_class c
                  JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                  LEFT JOIN pg_catalog.pg_am am ON am.oid = c.relam
                 WHERE n.nspname = ?
                   AND c.relname IN (%s)
                 ORDER BY c.relname
                """.formatted(TABLE_NAMES), expectedSchema);
        List<String> columns = canonicalRows("""
                SELECT pg_catalog.jsonb_build_array(
                           c.relname, a.attnum, a.attname,
                           pg_catalog.format_type(a.atttypid, a.atttypmod),
                           a.attnotnull, a.attidentity, a.attgenerated,
                           a.attisdropped, a.attstorage, a.attcompression,
                           COALESCE(a.attoptions, '{}'::text[]),
                           COALESCE(cn.nspname, ''), COALESCE(co.collname, ''),
                           pg_catalog.replace(
                               COALESCE(pg_catalog.pg_get_expr(
                                   d.adbin, d.adrelid, false), ''),
                               pg_catalog.format('%%I.', ?), '<schema>.'))::text
                  FROM pg_catalog.pg_class c
                  JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                  JOIN pg_catalog.pg_attribute a
                    ON a.attrelid = c.oid AND a.attnum > 0
                  LEFT JOIN pg_catalog.pg_collation co ON co.oid = a.attcollation
                  LEFT JOIN pg_catalog.pg_namespace cn ON cn.oid = co.collnamespace
                  LEFT JOIN pg_catalog.pg_attrdef d
                    ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                 WHERE n.nspname = ?
                   AND c.relname IN (%s)
                 ORDER BY c.relname, a.attnum
                """.formatted(TABLE_NAMES), expectedSchema, expectedSchema);
        List<String> constraints = canonicalRows("""
                SELECT pg_catalog.jsonb_build_array(
                           r.relname, c.conname, c.contype, c.convalidated,
                           c.condeferrable, c.condeferred, c.conislocal,
                           c.coninhcount, c.connoinherit,
                           pg_catalog.replace(
                               pg_catalog.pg_get_constraintdef(c.oid, false),
                               pg_catalog.format('%%I.', ?), '<schema>.'),
                           COALESCE((
                               SELECT pg_catalog.jsonb_agg(
                                          pg_catalog.jsonb_build_array(
                                              (tn.nspname = ?), tr.relname,
                                              CASE
                                                  WHEN it.tgrelid = c.conrelid
                                                      THEN 'conrel'
                                                  WHEN it.tgrelid = c.confrelid
                                                      THEN 'confrel'
                                                  ELSE 'other'
                                              END,
                                              COALESCE(rr.relname, ''),
                                              COALESCE(rn.nspname = ?, false),
                                              COALESCE(ix.relname, ''),
                                              COALESCE(xn.nspname = ?, false),
                                              it.tgenabled, it.tgtype::integer,
                                              pn.nspname, ip.proname,
                                              pg_catalog.oidvectortypes(ip.proargtypes),
                                              it.tgdeferrable, it.tginitdeferred,
                                              (it.tgparentid <> 0), it.tgnargs,
                                              pg_catalog.encode(it.tgargs, 'escape'),
                                              COALESCE(it.tgoldtable, ''),
                                              COALESCE(it.tgnewtable, ''),
                                              it.tgattr::text,
                                              COALESCE(it.tgqual::text, ''))
                                          ORDER BY tn.nspname, tr.relname,
                                                   it.tgtype, pn.nspname, ip.proname,
                                                   it.tgdeferrable, it.tginitdeferred,
                                                   it.tgattr::text,
                                                   COALESCE(it.tgqual::text, ''))
                                 FROM pg_catalog.pg_trigger it
                                 JOIN pg_catalog.pg_class tr ON tr.oid = it.tgrelid
                                 JOIN pg_catalog.pg_namespace tn
                                   ON tn.oid = tr.relnamespace
                                 JOIN pg_catalog.pg_proc ip ON ip.oid = it.tgfoid
                                 JOIN pg_catalog.pg_namespace pn
                                   ON pn.oid = ip.pronamespace
                                 LEFT JOIN pg_catalog.pg_class rr
                                   ON rr.oid = it.tgconstrrelid
                                 LEFT JOIN pg_catalog.pg_namespace rn
                                   ON rn.oid = rr.relnamespace
                                 LEFT JOIN pg_catalog.pg_class ix
                                   ON ix.oid = it.tgconstrindid
                                 LEFT JOIN pg_catalog.pg_namespace xn
                                   ON xn.oid = ix.relnamespace
                                WHERE it.tgconstraint = c.oid
                                  AND it.tgisinternal
                           ), '[]'::pg_catalog.jsonb))::text
                  FROM pg_catalog.pg_constraint c
                  JOIN pg_catalog.pg_class r ON r.oid = c.conrelid
                  JOIN pg_catalog.pg_namespace n ON n.oid = r.relnamespace
                 WHERE n.nspname = ?
                   AND r.relname IN (%s)
                   AND c.contype <> 't'
                 ORDER BY r.relname, c.conname
                """.formatted(TABLE_NAMES),
                expectedSchema, expectedSchema, expectedSchema, expectedSchema, expectedSchema);
        List<String> indexes = canonicalRows("""
                SELECT pg_catalog.jsonb_build_array(
                           r.relname, idx.relname, idx.relkind, idx.relpersistence,
                           am.amname, COALESCE(idx.reloptions, '{}'::text[]),
                           i.indisunique, i.indnullsnotdistinct, i.indisprimary,
                           i.indisexclusion, i.indimmediate, i.indisclustered,
                           i.indisvalid, i.indcheckxmin, i.indisready,
                           i.indislive, i.indisreplident,
                           i.indnatts, i.indnkeyatts,
                           i.indkey::text, i.indcollation::text,
                           i.indclass::text, i.indoption::text,
                           COALESCE(pg_catalog.pg_get_expr(
                               i.indexprs, i.indrelid, false), ''),
                           COALESCE(pg_catalog.pg_get_expr(
                               i.indpred, i.indrelid, false), ''),
                           pg_catalog.replace(
                               pg_catalog.pg_get_indexdef(i.indexrelid, 0, false),
                         pg_catalog.format('%%I.', ?),
                               '<schema>.'))::text
                  FROM pg_catalog.pg_index i
                  JOIN pg_catalog.pg_class r ON r.oid = i.indrelid
                  JOIN pg_catalog.pg_namespace n ON n.oid = r.relnamespace
                  JOIN pg_catalog.pg_class idx ON idx.oid = i.indexrelid
                  JOIN pg_catalog.pg_am am ON am.oid = idx.relam
                 WHERE n.nspname = ?
                   AND r.relname IN (%s)
                 ORDER BY r.relname, idx.relname
                """.formatted(TABLE_NAMES), expectedSchema, expectedSchema);
        List<String> triggers = canonicalRows("""
                SELECT pg_catalog.jsonb_build_array(
                           r.relname, t.tgisinternal,
                           CASE WHEN t.tgisinternal THEN '' ELSE t.tgname END,
                           pn.nspname, p.proname, pg_catalog.oidvectortypes(p.proargtypes),
                           COALESCE(c.conname, ''), COALESCE(cr.relname, ''),
                           COALESCE(cn.nspname, ''),
                           t.tgenabled, t.tgtype::integer, t.tgnargs,
                           pg_catalog.encode(t.tgargs, 'escape'),
                           t.tgdeferrable, t.tginitdeferred,
                           (t.tgconstraint <> 0), (t.tgparentid <> 0),
                           COALESCE(t.tgoldtable, ''), COALESCE(t.tgnewtable, ''),
                           t.tgattr::text, COALESCE(t.tgqual::text, ''),
                           COALESCE(rr.relname, ''), COALESCE(rn.nspname, ''),
                           COALESCE(ix.relname, ''), COALESCE(xn.nspname, ''))::text
                  FROM pg_catalog.pg_trigger t
                  JOIN pg_catalog.pg_class r ON r.oid = t.tgrelid
                  JOIN pg_catalog.pg_namespace n ON n.oid = r.relnamespace
                  JOIN pg_catalog.pg_proc p ON p.oid = t.tgfoid
                  JOIN pg_catalog.pg_namespace pn ON pn.oid = p.pronamespace
                  LEFT JOIN pg_catalog.pg_constraint c ON c.oid = t.tgconstraint
                  LEFT JOIN pg_catalog.pg_class cr ON cr.oid = c.conrelid
                  LEFT JOIN pg_catalog.pg_namespace cn ON cn.oid = cr.relnamespace
                  LEFT JOIN pg_catalog.pg_class rr ON rr.oid = t.tgconstrrelid
                  LEFT JOIN pg_catalog.pg_namespace rn ON rn.oid = rr.relnamespace
                  LEFT JOIN pg_catalog.pg_class ix ON ix.oid = t.tgconstrindid
                  LEFT JOIN pg_catalog.pg_namespace xn ON xn.oid = ix.relnamespace
                 WHERE n.nspname = ? AND r.relname IN (%s)
                 ORDER BY r.relname, t.tgisinternal, c.conname, cn.nspname, cr.relname,
                          t.tgtype, pn.nspname, p.proname, t.tgname
                """.formatted(TABLE_NAMES), expectedSchema);
        List<String> sequences = canonicalRows("""
                SELECT pg_catalog.jsonb_build_array(
                           seq.relname, tbl.relname, a.attname, a.attidentity,
                           d.deptype, (seq.relowner = tbl.relowner),
                           seq.relpersistence,
                           pg_catalog.format_type(ps.seqtypid, NULL),
                           ps.seqstart, ps.seqincrement, ps.seqmax, ps.seqmin,
                           ps.seqcache, ps.seqcycle)::text
                  FROM pg_catalog.pg_class seq
                  JOIN pg_catalog.pg_namespace sn ON sn.oid = seq.relnamespace
                  JOIN pg_catalog.pg_sequence ps ON ps.seqrelid = seq.oid
                  JOIN pg_catalog.pg_depend d
                    ON d.classid = 'pg_catalog.pg_class'::pg_catalog.regclass
                   AND d.objid = seq.oid AND d.objsubid = 0
                   AND d.refclassid = 'pg_catalog.pg_class'::pg_catalog.regclass
                   AND d.deptype = 'i'
                  JOIN pg_catalog.pg_class tbl ON tbl.oid = d.refobjid
                  JOIN pg_catalog.pg_namespace tn ON tn.oid = tbl.relnamespace
                  JOIN pg_catalog.pg_attribute a
                    ON a.attrelid = tbl.oid AND a.attnum = d.refobjsubid
                 WHERE sn.nspname = ? AND tn.nspname = ?
                   AND seq.relkind = 'S'
                   AND seq.relname IN (%s)
                 ORDER BY seq.relname
                """.formatted(SEQUENCE_NAMES), expectedSchema, expectedSchema);
        return new CatalogFingerprint(
                tables.size(), digest(tables),
                columns.size(), digest(columns),
                constraints.size(), digest(constraints),
                indexes.size(), digest(indexes),
                triggers.size(), digest(triggers),
                sequences.size(), digest(sequences));
    }

    private List<String> canonicalRows(String sql, Object... arguments) {
        return jdbc.queryForList(sql, String.class, arguments);
    }

    private static String digest(List<String> rows) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String row : rows) {
                byte[] bytes = row.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 no está disponible", impossible);
        }
    }

    private static void incompatible() {
        throw new LegalEditorialOperationalException(LegalManifestIssueCode.SCHEMA_DRIFT, ISSUE_LOCATION);
    }

    private record CatalogFingerprint(int tableCount, String tablesSha256,
                                      int columnCount, String columnsSha256,
                                      int constraintCount, String constraintsSha256,
                                      int indexCount, String indexesSha256,
                                      int triggerCount, String triggersSha256,
                                      int sequenceCount, String sequencesSha256) {}
}
