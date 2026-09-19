package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Fail-closed PostgreSQL 16 accreditation for the complete V29 acceptance boundary.
 *
 * <p>The catalog includes the eight V28 relations, both supplementary ledger tables and the
 * users/talleres dependencies. Frozen V27 base surfaces are checked without recursive dispatch.
 * The compatibility entry points preserve historical V27 consumers and exact V28 consumers,
 * while requiring complete V29 accreditation whenever its migration is selected.</p>
 */
final class LegalV29AcceptanceSchemaVerifier implements LegalDatabasePreflight {

    static final String ISSUE_LOCATION = "database/schema";
    // V36 changes an independent business column. Legal/photo catalogs stay at their frozen V35 values.
    static final String V36_FLYWAY_SCRIPT = "V36__tipo_equipos_multidispositivo.sql";
    static final int V36_FLYWAY_CHECKSUM = -1571524851; // Captured by LegalV36SchemaSnapshot on clean PostgreSQL 16.

    private static final Pattern SAFE_SCHEMA = Pattern.compile("[a-z_][a-z0-9_]{0,62}");
    private static final String TABLE_NAMES = sqlStrings(
            LegalV29AcceptanceInventory.CATALOG_TABLES);
    private static final String LEGAL_GRAPH_TABLE_NAMES = sqlStrings(
            LegalV29AcceptanceInventory.LEGAL_GRAPH_TABLES);
    private static final String SEQUENCE_NAMES = sqlStrings(
            LegalV29AcceptanceInventory.IDENTITY_SEQUENCES.keySet());

    private final JdbcTemplate jdbc;
    private final String expectedSchema;
    private final LegalV27ImportSchemaVerifier importVerifier;
    private final LegalEditorialSchemaVerifier editorialVerifier;

    LegalV29AcceptanceSchemaVerifier(JdbcTemplate jdbc, String expectedSchema) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.expectedSchema = requireSafeSchema(expectedSchema);
        this.importVerifier = new LegalV27ImportSchemaVerifier(jdbc, this.expectedSchema);
        this.editorialVerifier = new LegalEditorialSchemaVerifier(jdbc, this.expectedSchema);
    }

    @Override
    public void verify() {
        verifyImportSurface();
        editorialVerifier.verifyBase();
        if (compatibleVersion().ordinal() < RuntimeVersion.V29.ordinal()) {
            incompatible();
        }
        verifyV29Surface();
    }

    /** Import already accredited its own base; V29 additionally requires the editorial base. */
    static void verifyAfterImportBase(JdbcTemplate jdbc, String schema) {
        LegalV29AcceptanceSchemaVerifier verifier = new LegalV29AcceptanceSchemaVerifier(jdbc, schema);
        if (verifier.compatibleVersion().ordinal() >= RuntimeVersion.V29.ordinal()) {
            verifier.editorialVerifier.verifyBase();
            verifier.verifyV29Surface();
        }
    }

    /** Editorial already accredited its own base; V29 additionally requires the import base. */
    static void verifyAfterEditorialBase(JdbcTemplate jdbc, String schema) {
        LegalV29AcceptanceSchemaVerifier verifier = new LegalV29AcceptanceSchemaVerifier(jdbc, schema);
        if (verifier.compatibleVersion().ordinal() >= RuntimeVersion.V29.ordinal()) {
            verifier.verifyImportSurface();
            verifier.verifyV29Surface();
        }
    }

    /** True only after full V29 delta verification; false keeps the caller's exact V28 checks. */
    static boolean verifyAfterAggregateBases(JdbcTemplate jdbc, String schema) {
        LegalV29AcceptanceSchemaVerifier verifier = new LegalV29AcceptanceSchemaVerifier(jdbc, schema);
        if (verifier.compatibleVersion().ordinal() >= RuntimeVersion.V29.ordinal()) {
            verifier.verifyV29Surface();
            return true;
        }
        return false;
    }

    private void verifyV29Surface() {
        verifyRelationTopology();
        RuntimeVersion version = compatibleVersion();
        boolean photos = version.ordinal() >= RuntimeVersion.V30.ordinal();
        boolean closure = version.ordinal() >= RuntimeVersion.V33.ordinal();
        if (closure && !"public".equals(expectedSchema)) incompatible();
        var expected = version.ordinal() >= RuntimeVersion.V34.ordinal() ? LegalV34ClosureSchema.LEGAL_CATALOG
                : closure ? LegalV33ClosureSchema.LEGAL_CATALOG : photos ? LegalPrivatePhotoSchema.LEGAL_CATALOG : LegalV29AcceptanceInventory.EXPECTED_CATALOG;
        if (!catalogFingerprint().equals(expected)) {
            incompatible();
        }
        verifySchemaFunctions();
        if (version.ordinal() >= RuntimeVersion.V35.ordinal()) LegalV35PhotoDeletionSchema.verify(jdbc);
        if (photos) LegalPrivatePhotoSchema.verify(jdbc, closure);
        if (version == RuntimeVersion.V34) LegalV34ClosureSchema.verify(jdbc);
        else if (version == RuntimeVersion.V33) LegalV33ClosureSchema.verify(jdbc);
    }

    @Override
    public boolean usesJdbc(JdbcTemplate candidate) {
        return jdbc == candidate
                && importVerifier.usesJdbc(candidate)
                && editorialVerifier.usesJdbc(candidate);
    }

    String expectedSchema() {
        return expectedSchema;
    }

    /** Package-private diagnostic used only to freeze clean PostgreSQL 16 constants. */
    CatalogSnapshot snapshot() {
        return new CatalogSnapshot(catalogFingerprint(), functionStates(), flywayStates());
    }

    /** Exact history is checked before allowing the closure-era photo column capability. */
    boolean usesWorkshopClosureSchema() { return workshopClosureSchemaVersion() != 0; }

    int workshopClosureSchemaVersion() {
        return switch (compatibleVersion()) {
            case V33 -> 33;
            case V34 -> 34;
            case V35 -> 35;
            case V36 -> 36;
            default -> 0;
        };
    }

    boolean usesPhotoDeletionSchema() { return compatibleVersion().ordinal() >= RuntimeVersion.V35.ordinal(); }

    /** Exact ordered history since V27; the eleventh row is a bounded incompatibility sentinel. */
    private RuntimeVersion compatibleVersion() {
        List<FlywayState> actual = flywayStates();
        for (RuntimeVersion version : RuntimeVersion.values()) {
            if (actual.equals(expectedFlywayStates(version))) {
                if (version.ordinal() < RuntimeVersion.V29.ordinal()) {
                    verifyV29ExtensionAbsent();
                }
                return version;
            }
        }
        incompatible();
        throw new IllegalStateException("La incompatibilidad de esquema debe interrumpir la ejecución");
    }

    private void verifyV29ExtensionAbsent() {
        Boolean present = jdbc.queryForObject("""
                SELECT EXISTS (
                           SELECT 1
                             FROM pg_catalog.pg_class rel
                             JOIN pg_catalog.pg_namespace ns
                               ON ns.oid = rel.relnamespace
                            WHERE ns.nspname = ?
                              AND rel.relname IN (%s)
                       ) OR EXISTS (
                           SELECT 1
                             FROM pg_catalog.pg_proc fn
                             JOIN pg_catalog.pg_namespace ns
                               ON ns.oid = fn.pronamespace
                            WHERE ns.nspname = ?
                              AND fn.proname IN (%s)
                       ) OR EXISTS (
                           SELECT 1
                             FROM pg_catalog.pg_trigger trg
                             JOIN pg_catalog.pg_class rel ON rel.oid = trg.tgrelid
                             JOIN pg_catalog.pg_namespace ns
                               ON ns.oid = rel.relnamespace
                            WHERE ns.nspname = ?
                              AND trg.tgname IN (%s)
                       )
                """.formatted(
                        sqlStrings(LegalV29AcceptanceInventory.V29_TABLES),
                        sqlStrings(functionNames(LegalV29AcceptanceInventory.V29_FUNCTIONS.keySet())),
                        sqlStrings(LegalV29AcceptanceInventory.V29_TRIGGER_NAMES)),
                Boolean.class, expectedSchema, expectedSchema, expectedSchema);
        if (!Boolean.FALSE.equals(present)) {
            incompatible();
        }
    }

    private void verifyRelationTopology() {
        if (!relationTopologyStates().equals(expectedRelationTopologyStates())) {
            incompatible();
        }
    }

    private List<RelationTopologyState> relationTopologyStates() {
        return jdbc.query("""
                        SELECT c.relname,
                               EXISTS (
                                   SELECT 1
                                     FROM pg_catalog.pg_inherits inheritance
                                    WHERE inheritance.inhparent = c.oid
                               ) AS has_descendants,
                               EXISTS (
                                   SELECT 1
                                     FROM pg_catalog.pg_inherits inheritance
                                    WHERE inheritance.inhrelid = c.oid
                               ) AS has_ancestors,
                               EXISTS (
                                   SELECT 1
                                     FROM pg_catalog.pg_rewrite rewrite
                                    WHERE rewrite.ev_class = c.oid
                               ) AS has_rules
                          FROM pg_catalog.pg_class c
                          JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                         WHERE n.nspname = ?
                           AND c.relname IN (%s)
                         ORDER BY c.relname
                        """.formatted(LEGAL_GRAPH_TABLE_NAMES),
                (resultSet, rowNumber) -> new RelationTopologyState(
                        resultSet.getString("relname"),
                        resultSet.getBoolean("has_descendants"),
                        resultSet.getBoolean("has_ancestors"),
                        resultSet.getBoolean("has_rules")),
                expectedSchema);
    }

    private static List<RelationTopologyState> expectedRelationTopologyStates() {
        return LegalV29AcceptanceInventory.LEGAL_GRAPH_TABLES.stream()
                .sorted()
                .map(name -> new RelationTopologyState(name, false, false, false))
                .toList();
    }

    private void verifyImportSurface() {
        try {
            importVerifier.verifyBase();
        } catch (LegalImportOperationalException importFailure) {
            if (importFailure.issue().code()
                    != LegalManifestIssueCode.IMPORT_DB_SCHEMA_INCOMPATIBLE) {
                throw importFailure;
            }
            throw new LegalEditorialOperationalException(
                    LegalManifestIssueCode.SCHEMA_DRIFT,
                    ISSUE_LOCATION,
                    importFailure);
        }
    }

    private List<FlywayState> flywayStates() {
        String history = quoteIdentifier(expectedSchema)
                + "."
                + quoteIdentifier(LegalV29AcceptanceInventory.FLYWAY_HISTORY_TABLE);
        return jdbc.query("""
                        SELECT history.version, history.type, history.script,
                               history.checksum, history.success,
                               history.installed_rank = (
                                   SELECT pg_catalog.max(candidate.installed_rank)
                                     FROM %s candidate
                               ) AS latest
                          FROM %s history
                         WHERE history.version IN (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            OR history.installed_rank >= (
                                SELECT pg_catalog.min(first_v27.installed_rank)
                                  FROM %s first_v27
                                 WHERE first_v27.version = ?
                            )
                         ORDER BY history.installed_rank
                         LIMIT 11
                        """.formatted(history, history, history),
                (resultSet, rowNumber) -> new FlywayState(
                        resultSet.getString("version"),
                        resultSet.getString("type"),
                        resultSet.getString("script"),
                        (Integer) resultSet.getObject("checksum"),
                        resultSet.getBoolean("success"),
                        resultSet.getBoolean("latest")),
                LegalV29AcceptanceInventory.FLYWAY_VERSION_V27,
                LegalV29AcceptanceInventory.FLYWAY_VERSION_V28,
                LegalV29AcceptanceInventory.FLYWAY_VERSION_V29,
                "30", "31", "32", "33", "34", "35", "36",
                LegalV29AcceptanceInventory.FLYWAY_VERSION_V27);
    }

    private static List<FlywayState> expectedFlywayStates(RuntimeVersion version) {
        List<FlywayState> states = new ArrayList<>();
        states.add(new FlywayState(
                LegalV29AcceptanceInventory.FLYWAY_VERSION_V27,
                LegalV29AcceptanceInventory.FLYWAY_TYPE,
                LegalV29AcceptanceInventory.FLYWAY_SCRIPT_V27,
                LegalV29AcceptanceInventory.FLYWAY_CHECKSUM_V27,
                true,
                version == RuntimeVersion.V27));
        if (version == RuntimeVersion.V27) {
            return List.copyOf(states);
        }
        states.add(new FlywayState(
                LegalV29AcceptanceInventory.FLYWAY_VERSION_V28,
                LegalV29AcceptanceInventory.FLYWAY_TYPE,
                LegalV29AcceptanceInventory.FLYWAY_SCRIPT_V28,
                LegalV29AcceptanceInventory.FLYWAY_CHECKSUM_V28,
                true,
                version == RuntimeVersion.V28));
        if (version == RuntimeVersion.V28) {
            return List.copyOf(states);
        }
        states.add(new FlywayState(
                LegalV29AcceptanceInventory.FLYWAY_VERSION_V29,
                LegalV29AcceptanceInventory.FLYWAY_TYPE,
                LegalV29AcceptanceInventory.FLYWAY_SCRIPT_V29,
                LegalV29AcceptanceInventory.FLYWAY_CHECKSUM_V29,
                true,
                version == RuntimeVersion.V29));
        if (version.ordinal() >= RuntimeVersion.V30.ordinal()) {
            states.add(new FlywayState("30", "SQL", "V30__fotos_privadas_contextuales.sql",
                    -1584212728, true, version == RuntimeVersion.V30));
        }
        // V31 adds an independent account table. Its legal and photo catalogs remain exactly V30.
        if (version.ordinal() >= RuntimeVersion.V31.ordinal()) {
            states.add(new FlywayState("31", "SQL", "V31__reautenticacion_exportaciones.sql",
                    518186831, true, version == RuntimeVersion.V31));
        }
        if (version.ordinal() >= RuntimeVersion.V32.ordinal()) {
            states.add(new FlywayState("32", "SQL", "V32__trabajos_exportacion_temporal.sql", -1414907070, true, version == RuntimeVersion.V32));
        }
        if (version.ordinal() >= RuntimeVersion.V33.ordinal()) {
            states.add(new FlywayState("33", "SQL", LegalV33ClosureSchema.FLYWAY_SCRIPT,
                    LegalV33ClosureSchema.FLYWAY_CHECKSUM, true, version == RuntimeVersion.V33));
        }
        if (version.ordinal() >= RuntimeVersion.V34.ordinal()) {
            states.add(new FlywayState("34", "SQL", LegalV34ClosureSchema.FLYWAY_SCRIPT,
                    LegalV34ClosureSchema.FLYWAY_CHECKSUM, true, version == RuntimeVersion.V34));
        }
        if (version.ordinal() >= RuntimeVersion.V35.ordinal()) {
            states.add(new FlywayState("35", "SQL", LegalV35PhotoDeletionSchema.FLYWAY_SCRIPT,
                    LegalV35PhotoDeletionSchema.FLYWAY_CHECKSUM, true, version == RuntimeVersion.V35));
        }
        if (version == RuntimeVersion.V36) {
            states.add(new FlywayState("36", "SQL", V36_FLYWAY_SCRIPT, V36_FLYWAY_CHECKSUM, true, true));
        }
        return List.copyOf(states);
    }

    private LegalV29AcceptanceInventory.CatalogFingerprint catalogFingerprint() {
        List<String> tables = canonicalRows("""
                SELECT pg_catalog.jsonb_build_array(
                           c.relname, c.relkind, c.relpersistence,
                           c.relrowsecurity, c.relforcerowsecurity,
                           c.relispartition, c.relreplident,
                           COALESCE(am.amname, ''))::text
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
                           a.attisdropped,
                           pg_catalog.replace(
                               COALESCE(pg_catalog.pg_get_expr(
                                   d.adbin, d.adrelid, false), ''),
                               pg_catalog.format('%%I.', ?), '<schema>.'))::text
                  FROM pg_catalog.pg_class c
                  JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                  JOIN pg_catalog.pg_attribute a
                    ON a.attrelid = c.oid AND a.attnum > 0
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
                           r.relname, t.tgname, (pn.nspname = ?), p.proname,
                           pg_catalog.oidvectortypes(p.proargtypes),
                           t.tgenabled, t.tgnargs,
                           pg_catalog.encode(t.tgargs, 'escape'),
                           t.tgdeferrable, t.tginitdeferred,
                           (t.tgconstraint <> 0), (t.tgparentid <> 0),
                           COALESCE(t.tgoldtable, ''),
                           COALESCE(t.tgnewtable, ''), t.tgattr::text,
                           COALESCE(t.tgqual::text, ''),
                           t.tgtype::integer)::text
                  FROM pg_catalog.pg_trigger t
                  JOIN pg_catalog.pg_class r ON r.oid = t.tgrelid
                  JOIN pg_catalog.pg_namespace n ON n.oid = r.relnamespace
                  JOIN pg_catalog.pg_proc p ON p.oid = t.tgfoid
                  JOIN pg_catalog.pg_namespace pn ON pn.oid = p.pronamespace
                 WHERE n.nspname = ?
                   AND r.relname IN (%s)
                   AND NOT t.tgisinternal
                 ORDER BY r.relname, t.tgname
                """.formatted(TABLE_NAMES), expectedSchema, expectedSchema);
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
        return new LegalV29AcceptanceInventory.CatalogFingerprint(
                tables.size(), digest(tables),
                columns.size(), digest(columns),
                constraints.size(), digest(constraints),
                indexes.size(), digest(indexes),
                triggers.size(), digest(triggers),
                sequences.size(), digest(sequences));
    }

    private void verifySchemaFunctions() {
        if (!functionStates().equals(expectedFunctionStates())) {
            incompatible();
        }
    }

    private Map<String, FunctionState> functionStates() {
        List<FunctionState> rows = jdbc.query("""
                SELECT p.proname || '(' ||
                           pg_catalog.oidvectortypes(p.proargtypes) || ')' AS signature,
                       pg_catalog.pg_get_function_result(p.oid) AS result,
                       l.lanname, p.prokind, p.provolatile, p.proparallel,
                       p.prosecdef, p.proleakproof, p.proisstrict, p.proretset,
                       p.proconfig = ARRAY[
                           'search_path=pg_catalog, ' || ? || ', pg_temp'
                       ]::text[] AS safe_search_path,
                       p.prosrc
                  FROM pg_catalog.pg_proc p
                  JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
                  JOIN pg_catalog.pg_language l ON l.oid = p.prolang
                 WHERE n.nspname = ?
                   AND p.proname IN (%s)
                 ORDER BY signature
                """.formatted(sqlStrings(schemaFunctionNames())),
                (resultSet, rowNumber) -> new FunctionState(
                        resultSet.getString("signature"),
                        resultSet.getString("result"),
                        resultSet.getString("lanname"),
                        resultSet.getString("prokind"),
                        resultSet.getString("provolatile"),
                        resultSet.getString("proparallel"),
                        resultSet.getBoolean("prosecdef"),
                        resultSet.getBoolean("proleakproof"),
                        resultSet.getBoolean("proisstrict"),
                        resultSet.getBoolean("proretset"),
                        resultSet.getBoolean("safe_search_path"),
                        sha256(normalizeLineEndings(resultSet.getString("prosrc")))),
                expectedSchema,
                expectedSchema);
        Map<String, FunctionState> states = new LinkedHashMap<>();
        for (FunctionState row : rows) {
            if (states.put(row.signature(), row) != null) {
                incompatible();
            }
        }
        return Map.copyOf(states);
    }

    private static Map<String, FunctionState> expectedFunctionStates() {
        Map<String, FunctionState> expected = new LinkedHashMap<>();
        LegalV29AcceptanceInventory.SCHEMA_FUNCTIONS.forEach((signature, spec) ->
                expected.put(signature, new FunctionState(
                        signature,
                        functionResult(signature),
                        LegalV29AcceptanceInventory.SQL_FUNCTIONS.contains(signature)
                                ? "sql"
                                : "plpgsql",
                        "f", "v", "u",
                        false, false, false, false, true,
                        spec.sourceSha256())));
        return Map.copyOf(expected);
    }

    private static String functionResult(String signature) {
        if (LegalV29AcceptanceInventory.SQL_FUNCTIONS.contains(signature)) {
            return "boolean";
        }
        if (LegalV29AcceptanceInventory.VOID_FUNCTIONS.contains(signature)) {
            return "void";
        }
        return "trigger";
    }

    private List<String> canonicalRows(String sql, Object... arguments) {
        return jdbc.queryForList(sql, String.class, arguments);
    }

    private static List<String> schemaFunctionNames() {
        return functionNames(LegalV29AcceptanceInventory.SCHEMA_FUNCTIONS.keySet());
    }

    private static List<String> functionNames(Iterable<String> signatures) {
        List<String> names = new ArrayList<>();
        for (String signature : signatures) {
            names.add(signature.substring(0, signature.indexOf('(')));
        }
        return names.stream().distinct().sorted().toList();
    }

    private static String digest(List<String> rows) {
        MessageDigest digest = sha256Digest();
        for (String row : rows) {
            byte[] bytes = row.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(String value) {
        MessageDigest digest = sha256Digest();
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 no está disponible", impossible);
        }
    }

    private static String normalizeLineEndings(String source) {
        return Objects.requireNonNull(source, "function source")
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }

    private static String requireSafeSchema(String candidate) {
        String schema = Objects.requireNonNull(candidate, "expectedSchema");
        if (!SAFE_SCHEMA.matcher(schema).matches()) {
            throw new IllegalArgumentException("Schema PostgreSQL de aceptación inválido");
        }
        return schema;
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static String sqlStrings(Iterable<String> values) {
        List<String> quoted = new ArrayList<>();
        values.forEach(value -> quoted.add("'" + value.replace("'", "''") + "'"));
        return String.join(", ", quoted);
    }

    private static void incompatible() {
        throw new LegalEditorialOperationalException(
                LegalManifestIssueCode.SCHEMA_DRIFT,
                ISSUE_LOCATION);
    }

    record CatalogSnapshot(
            LegalV29AcceptanceInventory.CatalogFingerprint catalog,
            Map<String, FunctionState> functions,
            List<FlywayState> flyway) { }

    record FunctionState(
            String signature,
            String result,
            String language,
            String kind,
            String volatility,
            String parallel,
            boolean securityDefiner,
            boolean leakproof,
            boolean strict,
            boolean returnsSet,
            boolean safeSearchPath,
            String sourceSha256) { }

    record FlywayState(
            String version,
            String type,
            String script,
            Integer checksum,
            boolean success,
            boolean latest) { }

    private enum RuntimeVersion { V27, V28, V29, V30, V31, V32, V33, V34, V35, V36 }

    private record RelationTopologyState(
            String relation,
            boolean hasDescendants,
            boolean hasAncestors,
            boolean hasRules) { }
}
