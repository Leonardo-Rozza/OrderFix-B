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

/** Fail-closed PostgreSQL 16 catalog accreditation for the complete V27 editorial graph. */
final class LegalEditorialSchemaVerifier implements LegalDatabasePreflight {

    static final String ISSUE_LOCATION = "database/schema";

    private static final Pattern SAFE_SCHEMA = Pattern.compile("[a-z_][a-z0-9_]{0,62}");
    private static final String TABLE_NAMES = sqlStrings(
            LegalV27EditorialInventory.EDITORIAL_TABLES);
    private static final String SEQUENCE_NAMES = sqlStrings(
            LegalV27EditorialInventory.IDENTITY_SEQUENCES.keySet());

    private final JdbcTemplate jdbc;
    private final String expectedSchema;

    LegalEditorialSchemaVerifier(JdbcTemplate jdbc, String expectedSchema) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.expectedSchema = requireSafeSchema(expectedSchema);
    }

    @Override
    public void verify() {
        verifyBase();
        LegalV29AcceptanceSchemaVerifier.verifyAfterEditorialBase(jdbc, expectedSchema);
    }

    /** Frozen migrated V27 surface, plus its exact public/V37 logical-restore representation. */
    void verifyBase() {
        verifySessionSchema();
        verifyFlywayHistory();
        var catalog = catalogFingerprint();
        if (!catalog.equals(LegalV27EditorialInventory.EXPECTED_CATALOG)
                && !(catalog.equals(LegalRestoredV37Catalogs.EDITORIAL)
                    && LegalRestoredV37Catalogs.hasExactV37History(jdbc, expectedSchema))) {
            incompatible();
        }
        verifyEditorialFunctions();
    }

    @Override
    public boolean usesJdbc(JdbcTemplate candidate) {
        return jdbc == candidate;
    }

    String expectedSchema() {
        return expectedSchema;
    }

    CatalogSnapshot snapshot() {
        return new CatalogSnapshot(catalogFingerprint(), functionStates());
    }

    private void verifySessionSchema() {
        SessionSchema session = jdbc.queryForObject("""
                SELECT pg_catalog.current_schema() AS current_schema,
                       pg_catalog.current_schemas(false)::text AS search_path,
                       pg_catalog.current_setting('search_path') AS configured_search_path,
                       pg_catalog.current_setting('server_version_num')::integer
                           AS server_version_num,
                       pg_catalog.pg_my_temp_schema() AS temp_schema
                """, (resultSet, rowNumber) -> new SessionSchema(
                resultSet.getString("current_schema"),
                resultSet.getString("search_path"),
                resultSet.getString("configured_search_path"),
                resultSet.getInt("server_version_num"),
                resultSet.getLong("temp_schema")));
        if (session == null
                || session.tempSchema() != 0L
                || session.serverVersionNum() / 10_000 != 16
                || !allowedSearchPath(session)) {
            incompatible();
        }
    }

    private boolean allowedSearchPath(SessionSchema session) {
        String quotedSchema = "\"" + expectedSchema + "\"";
        boolean legacySingleSchema = ("{" + expectedSchema + "}")
                        .equals(session.searchPath())
                && expectedSchema.equals(session.currentSchema())
                && (expectedSchema.equals(session.configuredSearchPath())
                    || quotedSchema.equals(session.configuredSearchPath())
                    || (LegalV27EditorialInventory.DEFAULT_SCHEMA.equals(expectedSchema)
                        && "\"$user\", public".equals(
                                session.configuredSearchPath())));
        boolean restrictedEditorial = ("{pg_catalog," + expectedSchema + "}")
                        .equals(session.searchPath())
                && "pg_catalog".equals(session.currentSchema())
                && (("pg_catalog, " + expectedSchema + ", pg_temp")
                            .equals(session.configuredSearchPath())
                    || ("pg_catalog, " + quotedSchema + ", pg_temp")
                            .equals(session.configuredSearchPath()));
        return legacySingleSchema || restrictedEditorial;
    }

    private void verifyFlywayHistory() {
        String history = quoteIdentifier(expectedSchema)
                + "."
                + quoteIdentifier(LegalV27EditorialInventory.FLYWAY_HISTORY_TABLE);
        List<FlywayState> rows = jdbc.query("""
                        SELECT version, type, script, checksum, success
                          FROM %s
                         WHERE version = ?
                        """.formatted(history),
                (resultSet, rowNumber) -> new FlywayState(
                        resultSet.getString("version"),
                        resultSet.getString("type"),
                        resultSet.getString("script"),
                        (Integer) resultSet.getObject("checksum"),
                        resultSet.getBoolean("success")),
                LegalV27EditorialInventory.FLYWAY_VERSION);
        FlywayState expected = new FlywayState(
                LegalV27EditorialInventory.FLYWAY_VERSION,
                LegalV27EditorialInventory.FLYWAY_TYPE,
                LegalV27EditorialInventory.FLYWAY_SCRIPT,
                LegalV27EditorialInventory.FLYWAY_CHECKSUM,
                true);
        if (!rows.equals(List.of(expected))) {
            incompatible();
        }
    }

    private LegalV27EditorialInventory.CatalogFingerprint catalogFingerprint() {
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
                           COALESCE(pg_catalog.pg_get_expr(
                               d.adbin, d.adrelid, false), ''))::text
                  FROM pg_catalog.pg_class c
                  JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                  JOIN pg_catalog.pg_attribute a
                    ON a.attrelid = c.oid AND a.attnum > 0
                  LEFT JOIN pg_catalog.pg_attrdef d
                    ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                 WHERE n.nspname = ?
                   AND c.relname IN (%s)
                 ORDER BY c.relname, a.attnum
                """.formatted(TABLE_NAMES), expectedSchema);
        List<String> constraints = canonicalRows("""
                SELECT pg_catalog.jsonb_build_array(
                           r.relname, c.conname, c.contype, c.convalidated,
                           c.condeferrable, c.condeferred, c.conislocal,
                           c.coninhcount, c.connoinherit,
                           pg_catalog.pg_get_constraintdef(c.oid, false),
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
                expectedSchema, expectedSchema, expectedSchema, expectedSchema);
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
        return new LegalV27EditorialInventory.CatalogFingerprint(
                tables.size(), digest(tables),
                columns.size(), digest(columns),
                constraints.size(), digest(constraints),
                triggers.size(), digest(triggers),
                sequences.size(), digest(sequences));
    }

    private void verifyEditorialFunctions() {
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
                """.formatted(sqlStrings(editorialFunctionNames())),
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

    private Map<String, FunctionState> expectedFunctionStates() {
        Map<String, FunctionState> expected = new LinkedHashMap<>();
        LegalV27EditorialInventory.EDITORIAL_FUNCTIONS.forEach((signature, spec) ->
                expected.put(signature, new FunctionState(
                        signature,
                        LegalV27EditorialInventory.VOID_FUNCTIONS.contains(signature)
                                ? "void"
                                : "trigger",
                        "plpgsql", "f", "v", "u",
                        false, false, false, false, true,
                        spec.sourceSha256())));
        return Map.copyOf(expected);
    }

    private List<String> canonicalRows(String sql, Object... arguments) {
        return jdbc.queryForList(sql, String.class, arguments);
    }

    private static List<String> editorialFunctionNames() {
        List<String> names = new ArrayList<>();
        for (String signature : LegalV27EditorialInventory.EDITORIAL_FUNCTIONS.keySet()) {
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
            throw new IllegalArgumentException("Schema PostgreSQL editorial inválido");
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
            LegalV27EditorialInventory.CatalogFingerprint catalog,
            Map<String, FunctionState> functions) { }

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

    private record SessionSchema(
            String currentSchema,
            String searchPath,
            String configuredSearchPath,
            int serverVersionNum,
            long tempSchema) { }

    private record FlywayState(
            String version,
            String type,
            String script,
            Integer checksum,
            boolean success) { }
}
