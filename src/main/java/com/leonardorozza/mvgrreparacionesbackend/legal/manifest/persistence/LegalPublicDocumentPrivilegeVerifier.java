package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Verifies the public document reader's exact effective PostgreSQL capabilities.
 *
 * <p>The system boundary deliberately follows the accredited V28 verifier without refactoring
 * the frozen materializer. This role can read only the three document relations and migration
 * evidence; it has no application-function, row-lock UPDATE or aggregate/evidence capability.</p>
 */
final class LegalPublicDocumentPrivilegeVerifier implements LegalDatabasePreflight {

    static final String ISSUE_LOCATION = "database/privileges";
    static final Set<String> LARGE_OBJECT_CREATION_FUNCTIONS = Set.of(
            "lo_creat(integer)",
            "lo_create(oid)",
            "lo_from_bytea(oid, bytea)",
            "lo_import(text)",
            "lo_import(text, oid)");
    static final Set<String> SESSION_ADVISORY_LOCK_FUNCTIONS = Set.of(
            "pg_advisory_lock(bigint)", "pg_advisory_lock(integer, integer)",
            "pg_advisory_lock_shared(bigint)", "pg_advisory_lock_shared(integer, integer)",
            "pg_try_advisory_lock(bigint)", "pg_try_advisory_lock(integer, integer)",
            "pg_try_advisory_lock_shared(bigint)",
            "pg_try_advisory_lock_shared(integer, integer)");
    static final Set<String> READ_TABLES = Set.of(
            "legal_documento_lineas",
            "legal_documento_versiones",
            "legal_documento_contextos",
            "flyway_schema_history");
    static final Set<String> V28_FUNCTIONS =
            LegalV28AggregateInventory.V28_FUNCTIONS.keySet();
    private static final String LEGAL_GRAPH_TABLE_NAMES = sqlStrings(
            LegalV28AggregateInventory.LEGAL_GRAPH_TABLES);

    private final JdbcTemplate jdbc;
    private final String expectedRole;
    private final String expectedSchema;

    LegalPublicDocumentPrivilegeVerifier(
            JdbcTemplate jdbc,
            String expectedRole,
            String expectedSchema) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.expectedRole = requireText(expectedRole, "expectedRole");
        this.expectedSchema = requireText(expectedSchema, "expectedSchema");
    }

    @Override
    public void verify() {
        RoleState role = verifyRoleIdentity();
        verifyNoMembership(role.oid());
        verifyNoDatabaseOwnership(role.oid());
        verifyDatabase(role.oid());
        verifySchemas(role.oid());
        verifySystemSchemaBoundary(role.oid());
        verifyRelationsAndColumns(role.oid());
        verifySequences(role.oid());
        verifyEffectiveSessionSettings();
        verifyParameterPrivileges(role.oid());
        verifyLargeObjects(role.oid());
        verifyLargeObjectCreationFunctions(role.oid());
        verifyFunctions(role.oid());
        verifyNoSessionAdvisoryLockAcquisition(role.oid());
    }

    @Override
    public boolean usesJdbc(JdbcTemplate candidate) {
        return jdbc == candidate;
    }

    String expectedRole() {
        return expectedRole;
    }

    private RoleState verifyRoleIdentity() {
        List<RoleState> rows = jdbc.query("""
                SELECT r.oid::bigint AS oid,
                       SESSION_USER AS session_name,
                       CURRENT_USER AS current_name,
                       r.rolcanlogin, r.rolinherit, r.rolsuper, r.rolcreatedb,
                       r.rolcreaterole, r.rolreplication, r.rolbypassrls
                  FROM pg_catalog.pg_roles r
                 WHERE r.rolname = CURRENT_USER
                   AND CURRENT_USER = ?
                   AND SESSION_USER = ?
                """, (resultSet, rowNumber) -> new RoleState(
                resultSet.getLong("oid"),
                resultSet.getString("session_name"),
                resultSet.getString("current_name"),
                resultSet.getBoolean("rolcanlogin"),
                resultSet.getBoolean("rolinherit"),
                resultSet.getBoolean("rolsuper"),
                resultSet.getBoolean("rolcreatedb"),
                resultSet.getBoolean("rolcreaterole"),
                resultSet.getBoolean("rolreplication"),
                resultSet.getBoolean("rolbypassrls")),
                expectedRole,
                expectedRole);
        if (rows.size() != 1) {
            incompatible();
        }
        RoleState role = rows.getFirst();
        if (!expectedRole.equals(role.sessionName())
                || !expectedRole.equals(role.currentName())
                || !role.canLogin()
                || role.inherits()
                || role.superuser()
                || role.createDatabase()
                || role.createRole()
                || role.replication()
                || role.bypassRls()) {
            incompatible();
        }
        return role;
    }

    private void verifyNoMembership(long roleOid) {
        MembershipState memberships = jdbc.queryForObject("""
                SELECT (
                           SELECT pg_catalog.count(*)
                             FROM pg_catalog.pg_roles candidate
                            WHERE candidate.oid <> ?::oid
                              AND pg_catalog.pg_has_role(
                                  ?::oid, candidate.oid, 'MEMBER')
                       ) AS received,
                       (
                           SELECT pg_catalog.count(*)
                             FROM pg_catalog.pg_auth_members membership
                            WHERE membership.roleid = ?::oid
                       ) AS delegated
                """, (resultSet, rowNumber) -> new MembershipState(
                resultSet.getLong("received"),
                resultSet.getLong("delegated")),
                roleOid, roleOid, roleOid);
        if (memberships == null
                || memberships.received() != 0L
                || memberships.delegated() != 0L) {
            incompatible();
        }
    }

    private void verifyDatabase(long roleOid) {
        List<DatabaseState> databases = jdbc.query("""
                SELECT d.datname,
                       d.datname = pg_catalog.current_database() AS current_database,
                       d.datdba = ?::oid AS owner,
                       pg_catalog.has_database_privilege(
                           ?::oid, d.oid, 'CONNECT') AS connect,
                       pg_catalog.has_database_privilege(
                           ?::oid, d.oid, 'CONNECT WITH GRANT OPTION') AS connect_grant,
                       pg_catalog.has_database_privilege(
                           ?::oid, d.oid, 'CREATE') AS create_database,
                       pg_catalog.has_database_privilege(
                           ?::oid, d.oid, 'TEMPORARY') AS temporary,
                       EXISTS (
                           SELECT 1
                             FROM pg_catalog.aclexplode(COALESCE(
                                 d.datacl,
                                 pg_catalog.acldefault('d', d.datdba))) acl
                            WHERE acl.grantee = 0::oid
                              AND acl.privilege_type IN (
                                  'CONNECT', 'CREATE', 'TEMPORARY')
                       ) AS public_acl
                  FROM pg_catalog.pg_database d
                 WHERE d.datallowconn
                 ORDER BY d.datname
                """, (resultSet, rowNumber) -> new DatabaseState(
                resultSet.getString("datname"),
                resultSet.getBoolean("current_database"),
                resultSet.getBoolean("owner"),
                resultSet.getBoolean("connect"),
                resultSet.getBoolean("connect_grant"),
                resultSet.getBoolean("create_database"),
                resultSet.getBoolean("temporary"),
                resultSet.getBoolean("public_acl")),
                repeated(roleOid, 5));
        boolean currentFound = false;
        for (DatabaseState database : databases) {
            currentFound |= database.currentDatabase();
            if (database.owner()
                    || database.connect() != database.currentDatabase()
                    || database.connectGrant()
                    || database.createDatabase()
                    || database.temporary()
                    || database.publicAcl()) {
                incompatible();
            }
        }
        if (!currentFound) {
            incompatible();
        }
    }

    private void verifyNoDatabaseOwnership(long roleOid) {
        Boolean ownsDatabase = jdbc.queryForObject("""
                SELECT pg_catalog.count(*) > 0
                  FROM pg_catalog.pg_database
                 WHERE datdba = ?::oid
                """, Boolean.class, roleOid);
        if (!Boolean.FALSE.equals(ownsDatabase)) {
            incompatible();
        }
    }

    private void verifySchemas(long roleOid) {
        List<SchemaState> schemas = jdbc.query("""
                SELECT n.nspname, n.nspowner = ?::oid AS owner,
                       pg_catalog.has_schema_privilege(
                           ?::oid, n.oid, 'USAGE') AS usage,
                       pg_catalog.has_schema_privilege(
                           ?::oid, n.oid, 'USAGE WITH GRANT OPTION') AS usage_grant,
                       pg_catalog.has_schema_privilege(
                           ?::oid, n.oid, 'CREATE') AS create_schema
                  FROM pg_catalog.pg_namespace n
                 WHERE n.nspname <> 'information_schema'
                   AND n.nspname NOT LIKE 'pg\\_%' ESCAPE '\\'
                 ORDER BY n.nspname
                """, (resultSet, rowNumber) -> new SchemaState(
                resultSet.getString("nspname"),
                resultSet.getBoolean("owner"),
                resultSet.getBoolean("usage"),
                resultSet.getBoolean("usage_grant"),
                resultSet.getBoolean("create_schema")),
                repeated(roleOid, 4));
        boolean expectedFound = false;
        for (SchemaState schema : schemas) {
            boolean expected = expectedSchema.equals(schema.name());
            expectedFound |= expected;
            if (schema.owner()
                    || schema.createSchema()
                    || schema.usageGrant()
                    || schema.usage() != expected) {
                incompatible();
            }
        }
        if (!expectedFound) {
            incompatible();
        }
    }

    private void verifySystemSchemaBoundary(long roleOid) {
        List<SystemSchemaState> schemas = jdbc.query("""
                SELECT n.nspname,
                       n.nspowner = ?::oid AS owner,
                       pg_catalog.has_schema_privilege(
                           ?::oid, n.oid, 'USAGE WITH GRANT OPTION') AS usage_grant,
                       pg_catalog.has_schema_privilege(
                           ?::oid, n.oid, 'CREATE') AS create_schema,
                       pg_catalog.has_schema_privilege(
                           ?::oid, n.oid, 'CREATE WITH GRANT OPTION') AS create_grant,
                       EXISTS (
                           SELECT 1
                             FROM pg_catalog.pg_class relation
                            WHERE relation.relnamespace = n.oid
                              AND relation.relowner = ?::oid
                       ) AS owns_relation,
                       EXISTS (
                           SELECT 1
                             FROM pg_catalog.pg_proc function
                            WHERE function.pronamespace = n.oid
                              AND function.proowner = ?::oid
                       ) AS owns_function,
                       EXISTS (
                           SELECT 1
                             FROM pg_catalog.aclexplode(n.nspacl) acl
                            WHERE acl.grantee = ?::oid
                       ) AS direct_schema_acl,
                       EXISTS (
                           SELECT 1
                             FROM pg_catalog.pg_class relation
                             CROSS JOIN LATERAL pg_catalog.aclexplode(
                                 COALESCE(
                                     relation.relacl,
                                     pg_catalog.acldefault(
                                         CASE
                                             WHEN relation.relkind = 'S'
                                                 THEN 's'::"char"
                                             ELSE 'r'::"char"
                                         END,
                                         relation.relowner))) acl
                            WHERE relation.relnamespace = n.oid
                              AND relation.relkind IN ('r', 'p', 'v', 'm', 'f', 'S')
                              AND (acl.grantee = ?::oid
                                  OR (acl.grantee = 0::oid
                                      AND NOT (
                                          n.nspname = 'information_schema'
                                          AND relation.oid < 16384::oid
                                          AND relation.relname NOT IN (
                                              '_pg_foreign_data_wrappers',
                                              '_pg_foreign_servers',
                                              '_pg_foreign_table_columns',
                                              '_pg_foreign_tables',
                                              '_pg_user_mappings',
                                              'transforms')
                                          AND acl.privilege_type = 'SELECT'
                                          AND NOT acl.is_grantable)
                                      AND NOT EXISTS (
                                          SELECT 1
                                            FROM pg_catalog.aclexplode(COALESCE(
                                                (
                                                    SELECT initial.initprivs
                                                      FROM pg_catalog.pg_init_privs initial
                                                     WHERE initial.classoid =
                                                         'pg_catalog.pg_class'::pg_catalog.regclass
                                                       AND initial.objoid = relation.oid
                                                       AND initial.objsubid = 0
                                                ),
                                                pg_catalog.acldefault(
                                                    CASE
                                                        WHEN relation.relkind = 'S'
                                                            THEN 's'::"char"
                                                        ELSE 'r'::"char"
                                                    END,
                                                    relation.relowner))) baseline
                                           WHERE baseline.grantee = 0::oid
                                             AND baseline.privilege_type =
                                                 acl.privilege_type
                                             AND (NOT acl.is_grantable
                                                 OR baseline.is_grantable))))
                       ) AS relation_acl_drift,
                       EXISTS (
                           SELECT 1
                             FROM pg_catalog.pg_class relation
                             JOIN pg_catalog.pg_attribute attribute
                               ON attribute.attrelid = relation.oid
                              AND attribute.attnum > 0
                              AND NOT attribute.attisdropped
                             CROSS JOIN LATERAL pg_catalog.aclexplode(
                                 attribute.attacl) acl
                            WHERE relation.relnamespace = n.oid
                              AND (acl.grantee = ?::oid
                                  OR (acl.grantee = 0::oid
                                      AND NOT EXISTS (
                                          SELECT 1
                                            FROM pg_catalog.pg_init_privs initial
                                            CROSS JOIN LATERAL pg_catalog.aclexplode(
                                                initial.initprivs) baseline
                                           WHERE initial.classoid =
                                               'pg_catalog.pg_class'::pg_catalog.regclass
                                             AND initial.objoid = relation.oid
                                             AND initial.objsubid = attribute.attnum
                                             AND baseline.grantee = 0::oid
                                             AND baseline.privilege_type =
                                                 acl.privilege_type
                                             AND (NOT acl.is_grantable
                                                 OR baseline.is_grantable))))
                       ) AS column_acl_drift,
                       EXISTS (
                           SELECT 1
                             FROM pg_catalog.pg_proc function
                             CROSS JOIN LATERAL pg_catalog.aclexplode(
                                 COALESCE(
                                     function.proacl,
                                     pg_catalog.acldefault(
                                         'f', function.proowner))) acl
                            WHERE function.pronamespace = n.oid
                              AND (acl.grantee = ?::oid
                                  OR (acl.grantee = 0::oid
                                      AND NOT EXISTS (
                                          SELECT 1
                                            FROM pg_catalog.aclexplode(COALESCE(
                                                (
                                                    SELECT initial.initprivs
                                                      FROM pg_catalog.pg_init_privs initial
                                                     WHERE initial.classoid =
                                                         'pg_catalog.pg_proc'::pg_catalog.regclass
                                                       AND initial.objoid = function.oid
                                                       AND initial.objsubid = 0
                                                ),
                                                pg_catalog.acldefault(
                                                    'f', function.proowner))) baseline
                                           WHERE baseline.grantee = 0::oid
                                             AND baseline.privilege_type =
                                                 acl.privilege_type
                                             AND (NOT acl.is_grantable
                                                 OR baseline.is_grantable))))
                       ) AS function_acl_drift,
                       EXISTS (
                           SELECT 1
                             FROM pg_catalog.pg_proc function
                            WHERE function.pronamespace = n.oid
                              AND function.prosecdef
                              AND pg_catalog.has_function_privilege(
                                  ?::oid, function.oid, 'EXECUTE')
                       ) AS executable_security_definer,
                       EXISTS (
                           SELECT 1
                             FROM pg_catalog.pg_class relation
                            WHERE relation.relnamespace = n.oid
                              AND relation.relname IN (%s)
                       ) AS relation_collision,
                       EXISTS (
                           SELECT 1
                             FROM pg_catalog.pg_proc function
                            WHERE function.pronamespace = n.oid
                              AND function.proname LIKE 'legal\\_%%' ESCAPE '\\'
                       ) AS legal_function_collision
                  FROM pg_catalog.pg_namespace n
                 WHERE n.nspname = 'information_schema'
                    OR n.nspname LIKE 'pg\\_%%' ESCAPE '\\'
                 ORDER BY n.nspname
                """.formatted(LEGAL_GRAPH_TABLE_NAMES),
                (resultSet, rowNumber) -> new SystemSchemaState(
                        resultSet.getString("nspname"),
                        resultSet.getBoolean("owner"),
                        resultSet.getBoolean("usage_grant"),
                        resultSet.getBoolean("create_schema"),
                        resultSet.getBoolean("create_grant"),
                        resultSet.getBoolean("owns_relation"),
                        resultSet.getBoolean("owns_function"),
                        resultSet.getBoolean("direct_schema_acl"),
                        resultSet.getBoolean("relation_acl_drift"),
                        resultSet.getBoolean("column_acl_drift"),
                        resultSet.getBoolean("function_acl_drift"),
                        resultSet.getBoolean("executable_security_definer"),
                        resultSet.getBoolean("relation_collision"),
                        resultSet.getBoolean("legal_function_collision")),
                repeated(roleOid, 11));
        Set<String> found = new HashSet<>();
        for (SystemSchemaState schema : schemas) {
            found.add(schema.name());
            if (schema.owner()
                    || schema.usageGrant()
                    || schema.createSchema()
                    || schema.createGrant()
                    || schema.ownsRelation()
                    || schema.ownsFunction()
                    || schema.directSchemaAcl()
                    || schema.relationAclDrift()
                    || schema.columnAclDrift()
                    || schema.functionAclDrift()
                    || schema.executableSecurityDefiner()
                    || schema.relationCollision()
                    || schema.legalFunctionCollision()) {
                incompatible();
            }
        }
        if (!found.containsAll(Set.of("pg_catalog", "information_schema"))) {
            incompatible();
        }
    }

    private void verifyRelationsAndColumns(long roleOid) {
        List<RelationState> relations = jdbc.query("""
                SELECT n.nspname, c.relname, c.relowner = ?::oid AS owner,
                       pg_catalog.has_table_privilege(?::oid, c.oid, 'SELECT') AS can_select,
                       pg_catalog.has_table_privilege(?::oid, c.oid,
                           'SELECT WITH GRANT OPTION') AS select_grant,
                       pg_catalog.has_table_privilege(?::oid, c.oid, 'INSERT') AS can_insert,
                       pg_catalog.has_table_privilege(?::oid, c.oid,
                           'INSERT WITH GRANT OPTION') AS insert_grant,
                       pg_catalog.has_table_privilege(?::oid, c.oid, 'UPDATE') AS can_update,
                       pg_catalog.has_table_privilege(?::oid, c.oid, 'DELETE') AS can_delete,
                       pg_catalog.has_table_privilege(?::oid, c.oid,
                           'DELETE WITH GRANT OPTION') AS delete_grant,
                       pg_catalog.has_table_privilege(?::oid, c.oid, 'TRUNCATE') AS can_truncate,
                       pg_catalog.has_table_privilege(?::oid, c.oid, 'REFERENCES') AS can_reference,
                       pg_catalog.has_table_privilege(?::oid, c.oid, 'TRIGGER') AS can_trigger,
                       EXISTS (
                           SELECT 1
                             FROM pg_catalog.aclexplode(c.relacl) acl
                            WHERE acl.grantee = 0::oid
                       ) AS public_acl
                  FROM pg_catalog.pg_class c
                  JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname <> 'information_schema'
                   AND n.nspname NOT LIKE 'pg\\_%' ESCAPE '\\'
                   AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
                 ORDER BY n.nspname, c.relname
                """, (resultSet, rowNumber) -> new RelationState(
                resultSet.getString("nspname"),
                resultSet.getString("relname"),
                resultSet.getBoolean("owner"),
                resultSet.getBoolean("can_select"),
                resultSet.getBoolean("select_grant"),
                resultSet.getBoolean("can_insert"),
                resultSet.getBoolean("insert_grant"),
                resultSet.getBoolean("can_update"),
                resultSet.getBoolean("can_delete"),
                resultSet.getBoolean("delete_grant"),
                resultSet.getBoolean("can_truncate"),
                resultSet.getBoolean("can_reference"),
                resultSet.getBoolean("can_trigger"),
                resultSet.getBoolean("public_acl")),
                repeated(roleOid, 11));

        Set<String> expectedRelations = READ_TABLES;
        Set<String> foundRelations = new HashSet<>();
        for (RelationState relation : relations) {
            boolean targetSchema = expectedSchema.equals(relation.schema());
            boolean allowedRead = targetSchema && READ_TABLES.contains(relation.name());
            if (allowedRead) {
                foundRelations.add(relation.name());
            }
            if (relation.owner()
                    || relation.canSelect() != allowedRead
                    || relation.selectGrant()
                    || relation.canInsert()
                    || relation.insertGrant()
                    || relation.canUpdate()
                    || relation.canDelete()
                    || relation.deleteGrant()
                    || relation.canTruncate()
                    || relation.canReference()
                    || relation.canTrigger()
                    || relation.publicAcl()) {
                incompatible();
            }
        }
        if (!foundRelations.equals(expectedRelations)) {
            incompatible();
        }

        List<ColumnState> columns = jdbc.query("""
                SELECT n.nspname, c.relname, a.attname,
                       pg_catalog.has_column_privilege(
                           ?::oid, c.oid, a.attnum, 'SELECT') AS can_select,
                       pg_catalog.has_column_privilege(
                           ?::oid, c.oid, a.attnum,
                           'SELECT WITH GRANT OPTION') AS select_grant,
                       pg_catalog.has_column_privilege(
                           ?::oid, c.oid, a.attnum, 'INSERT') AS can_insert,
                       pg_catalog.has_column_privilege(
                           ?::oid, c.oid, a.attnum,
                           'INSERT WITH GRANT OPTION') AS insert_grant,
                       pg_catalog.has_column_privilege(
                           ?::oid, c.oid, a.attnum, 'UPDATE') AS can_update,
                       pg_catalog.has_column_privilege(
                           ?::oid, c.oid, a.attnum,
                           'UPDATE WITH GRANT OPTION') AS update_grant,
                       pg_catalog.has_column_privilege(
                           ?::oid, c.oid, a.attnum, 'REFERENCES') AS can_reference,
                       EXISTS (
                           SELECT 1
                             FROM pg_catalog.aclexplode(a.attacl) acl
                            WHERE acl.grantee = 0::oid
                       ) AS public_acl
                  FROM pg_catalog.pg_class c
                  JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                  JOIN pg_catalog.pg_attribute a
                    ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
                 WHERE n.nspname <> 'information_schema'
                   AND n.nspname NOT LIKE 'pg\\_%' ESCAPE '\\'
                   AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
                 ORDER BY n.nspname, c.relname, a.attnum
                """, (resultSet, rowNumber) -> new ColumnState(
                resultSet.getString("nspname"),
                resultSet.getString("relname"),
                resultSet.getString("attname"),
                resultSet.getBoolean("can_select"),
                resultSet.getBoolean("select_grant"),
                resultSet.getBoolean("can_insert"),
                resultSet.getBoolean("insert_grant"),
                resultSet.getBoolean("can_update"),
                resultSet.getBoolean("update_grant"),
                resultSet.getBoolean("can_reference"),
                resultSet.getBoolean("public_acl")),
                repeated(roleOid, 7));
        for (ColumnState column : columns) {
            boolean targetSchema = expectedSchema.equals(column.schema());
            boolean allowedRead = targetSchema && READ_TABLES.contains(column.table());
            if (column.canSelect() != allowedRead
                    || column.selectGrant()
                    || column.canInsert()
                    || column.insertGrant()
                    || column.canUpdate()
                    || column.updateGrant()
                    || column.canReference()
                    || column.publicAcl()) {
                incompatible();
            }
        }
    }

    private void verifySequences(long roleOid) {
        List<SequenceState> sequences = jdbc.query("""
                SELECT n.nspname, c.relname, c.relowner = ?::oid AS owner,
                       pg_catalog.has_sequence_privilege(
                           ?::oid, c.oid, 'USAGE') AS can_usage,
                       pg_catalog.has_sequence_privilege(
                           ?::oid, c.oid, 'USAGE WITH GRANT OPTION') AS usage_grant,
                       pg_catalog.has_sequence_privilege(
                           ?::oid, c.oid, 'SELECT') AS can_select,
                       pg_catalog.has_sequence_privilege(
                           ?::oid, c.oid, 'UPDATE') AS can_update
                  FROM pg_catalog.pg_class c
                  JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname <> 'information_schema'
                   AND n.nspname NOT LIKE 'pg\\_%' ESCAPE '\\'
                   AND c.relkind = 'S'
                 ORDER BY n.nspname, c.relname
                """, (resultSet, rowNumber) -> new SequenceState(
                resultSet.getString("nspname"),
                resultSet.getString("relname"),
                resultSet.getBoolean("owner"),
                resultSet.getBoolean("can_usage"),
                resultSet.getBoolean("usage_grant"),
                resultSet.getBoolean("can_select"),
                resultSet.getBoolean("can_update")),
                repeated(roleOid, 5));
        for (SequenceState sequence : sequences) {
            if (sequence.owner()
                    || sequence.canUsage()
                    || sequence.usageGrant()
                    || sequence.canSelect()
                    || sequence.canUpdate()) {
                incompatible();
            }
        }
    }

    private void verifyLargeObjects(long roleOid) {
        List<LargeObjectState> largeObjects = jdbc.query("""
                SELECT lom.oid::bigint AS oid,
                       lom.lomowner = ?::oid AS owner,
                       EXISTS (
                           SELECT 1
                             FROM pg_catalog.aclexplode(COALESCE(
                                 lom.lomacl,
                                 pg_catalog.acldefault('L', lom.lomowner))) acl
                            WHERE acl.grantee IN (0::oid, ?::oid)
                              AND acl.privilege_type IN ('SELECT', 'UPDATE')
                       ) AS direct_or_public_acl
                  FROM pg_catalog.pg_largeobject_metadata lom
                 ORDER BY lom.oid
                """, (resultSet, rowNumber) -> new LargeObjectState(
                resultSet.getLong("oid"),
                resultSet.getBoolean("owner"),
                resultSet.getBoolean("direct_or_public_acl")),
                roleOid, roleOid);
        if (largeObjects.stream().anyMatch(largeObject ->
                largeObject.owner() || largeObject.directOrPublicAcl())) {
            incompatible();
        }
    }

    private void verifyLargeObjectCreationFunctions(long roleOid) {
        List<FunctionPrivilege> functions = jdbc.query("""
                SELECT n.nspname,
                       p.proname || '(' ||
                           pg_catalog.oidvectortypes(p.proargtypes) || ')' AS signature,
                       p.proowner = ?::oid AS owner,
                       pg_catalog.has_function_privilege(
                           ?::oid, p.oid, 'EXECUTE') AS can_execute,
                       pg_catalog.has_function_privilege(
                           ?::oid, p.oid,
                           'EXECUTE WITH GRANT OPTION') AS execute_grant,
                       EXISTS (
                           SELECT 1
                             FROM pg_catalog.aclexplode(COALESCE(
                                 p.proacl,
                                 pg_catalog.acldefault('f', p.proowner))) acl
                            WHERE acl.grantee = 0
                              AND acl.privilege_type = 'EXECUTE'
                       ) AS public_execute
                  FROM pg_catalog.pg_proc p
                  JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
                 WHERE n.nspname = 'pg_catalog'
                   AND p.proname IN ('lo_creat', 'lo_create', 'lo_from_bytea', 'lo_import')
                 ORDER BY signature
                """, (resultSet, rowNumber) -> new FunctionPrivilege(
                resultSet.getString("nspname"),
                resultSet.getString("signature"),
                resultSet.getBoolean("owner"),
                resultSet.getBoolean("can_execute"),
                resultSet.getBoolean("execute_grant"),
                resultSet.getBoolean("public_execute")),
                roleOid, roleOid, roleOid);
        Set<String> found = new HashSet<>();
        for (FunctionPrivilege function : functions) {
            if (!"pg_catalog".equals(function.schema())
                    || !LARGE_OBJECT_CREATION_FUNCTIONS.contains(function.signature())
                    || function.owner()
                    || function.canExecute()
                    || function.executeGrant()
                    || function.publicExecute()) {
                incompatible();
            }
            found.add(function.signature());
        }
        if (!found.equals(LARGE_OBJECT_CREATION_FUNCTIONS)) {
            incompatible();
        }
    }

    private void verifyEffectiveSessionSettings() {
        SessionSettings settings = jdbc.queryForObject("""
                SELECT pg_catalog.current_setting('session_replication_role')
                           AS session_replication_role,
                       pg_catalog.current_setting('lo_compat_privileges')
                           AS lo_compat_privileges,
                       pg_catalog.current_schema() AS current_schema,
                       pg_catalog.current_schemas(false)::text AS search_path,
                       pg_catalog.current_setting('search_path')
                           AS configured_search_path,
                       pg_catalog.pg_my_temp_schema() AS temp_schema
                """, (resultSet, rowNumber) -> new SessionSettings(
                resultSet.getString("session_replication_role"),
                resultSet.getString("lo_compat_privileges"),
                resultSet.getString("current_schema"),
                resultSet.getString("search_path"),
                resultSet.getString("configured_search_path"),
                resultSet.getLong("temp_schema")));
        String quotedSchema = "\"" + expectedSchema + "\"";
        if (settings == null
                || !"origin".equals(settings.sessionReplicationRole())
                || !"off".equals(settings.loCompatPrivileges())
                || !"pg_catalog".equals(settings.currentSchema())
                || !("{pg_catalog," + expectedSchema + "}").equals(
                        settings.searchPath())
                || !(("pg_catalog, " + expectedSchema + ", pg_temp").equals(
                            settings.configuredSearchPath())
                        || ("pg_catalog, " + quotedSchema + ", pg_temp").equals(
                            settings.configuredSearchPath()))
                || settings.tempSchema() != 0L) {
            incompatible();
        }
    }

    private void verifyParameterPrivileges(long roleOid) {
        List<ParameterState> parameters = jdbc.query("""
                SELECT acl.parname,
                       pg_catalog.has_parameter_privilege(
                           ?::oid, acl.parname, 'SET') AS can_set,
                       pg_catalog.has_parameter_privilege(
                           ?::oid, acl.parname,
                           'SET WITH GRANT OPTION') AS set_grant,
                       pg_catalog.has_parameter_privilege(
                           ?::oid, acl.parname, 'ALTER SYSTEM') AS can_alter_system,
                       pg_catalog.has_parameter_privilege(
                           ?::oid, acl.parname,
                           'ALTER SYSTEM WITH GRANT OPTION') AS alter_system_grant
                  FROM pg_catalog.pg_parameter_acl acl
                 ORDER BY acl.parname
                """, (resultSet, rowNumber) -> new ParameterState(
                resultSet.getString("parname"),
                resultSet.getBoolean("can_set"),
                resultSet.getBoolean("set_grant"),
                resultSet.getBoolean("can_alter_system"),
                resultSet.getBoolean("alter_system_grant")),
                repeated(roleOid, 4));
        if (parameters.stream().anyMatch(parameter ->
                parameter.canSet()
                        || parameter.setGrant()
                        || parameter.canAlterSystem()
                        || parameter.alterSystemGrant())) {
            incompatible();
        }
    }

    private void verifyFunctions(long roleOid) {
        List<FunctionPrivilege> functions = jdbc.query("""
                SELECT n.nspname,
                       p.proname || '(' ||
                           pg_catalog.oidvectortypes(p.proargtypes) || ')' AS signature,
                       p.proowner = ?::oid AS owner,
                       pg_catalog.has_function_privilege(
                           ?::oid, p.oid, 'EXECUTE') AS can_execute,
                       pg_catalog.has_function_privilege(
                           ?::oid, p.oid,
                           'EXECUTE WITH GRANT OPTION') AS execute_grant,
                       EXISTS (
                           SELECT 1
                             FROM pg_catalog.aclexplode(COALESCE(
                                 p.proacl,
                                 pg_catalog.acldefault('f', p.proowner))) acl
                            WHERE acl.grantee = 0
                              AND acl.privilege_type = 'EXECUTE'
                       ) AS public_execute
                  FROM pg_catalog.pg_proc p
                  JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
                 WHERE n.nspname <> 'information_schema'
                   AND n.nspname NOT LIKE 'pg\\_%' ESCAPE '\\'
                 ORDER BY n.nspname, signature
                """, (resultSet, rowNumber) -> new FunctionPrivilege(
                resultSet.getString("nspname"),
                resultSet.getString("signature"),
                resultSet.getBoolean("owner"),
                resultSet.getBoolean("can_execute"),
                resultSet.getBoolean("execute_grant"),
                resultSet.getBoolean("public_execute")),
                roleOid, roleOid, roleOid);
        Set<String> foundV28 = new HashSet<>();
        for (FunctionPrivilege function : functions) {
            boolean v28 = expectedSchema.equals(function.schema())
                    && V28_FUNCTIONS.contains(function.signature());
            if (v28) {
                foundV28.add(function.signature());
            }
            if (function.owner()
                    || function.canExecute()
                    || function.executeGrant()
                    || function.publicExecute()) {
                incompatible();
            }
        }
        if (!foundV28.equals(V28_FUNCTIONS)) {
            incompatible();
        }
    }

    private void verifyNoSessionAdvisoryLockAcquisition(long roleOid) {
        List<String> executable = jdbc.queryForList("""
                SELECT p.proname || '(' ||
                           pg_catalog.oidvectortypes(p.proargtypes) || ')'
                  FROM pg_catalog.pg_proc p
                  JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
                 WHERE n.nspname = 'pg_catalog'
                   AND p.proname IN ('pg_advisory_lock', 'pg_advisory_lock_shared',
                       'pg_try_advisory_lock', 'pg_try_advisory_lock_shared')
                   AND pg_catalog.has_function_privilege(?::oid, p.oid, 'EXECUTE')
                 ORDER BY 1
                """, String.class, roleOid);
        List<String> catalog = jdbc.queryForList("""
                SELECT p.proname || '(' ||
                           pg_catalog.oidvectortypes(p.proargtypes) || ')'
                  FROM pg_catalog.pg_proc p
                  JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
                 WHERE n.nspname = 'pg_catalog'
                   AND p.proname IN ('pg_advisory_lock', 'pg_advisory_lock_shared',
                       'pg_try_advisory_lock', 'pg_try_advisory_lock_shared')
                 ORDER BY 1
                """, String.class);
        if (!executable.isEmpty()
                || !new HashSet<>(catalog).equals(SESSION_ADVISORY_LOCK_FUNCTIONS)) {
            incompatible();
        }
    }

    private static Object[] repeated(long value, int count) {
        Object[] values = new Object[count];
        java.util.Arrays.fill(values, value);
        return values;
    }

    private static String sqlStrings(Iterable<String> values) {
        List<String> quoted = new ArrayList<>();
        values.forEach(value -> quoted.add("'" + value.replace("'", "''") + "'"));
        return String.join(", ", quoted);
    }

    private static String requireText(String candidate, String name) {
        String value = Objects.requireNonNull(candidate, name).strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " no puede estar vacío");
        }
        return value;
    }

    private static void incompatible() {
        throw new LegalEditorialOperationalException(
                LegalManifestIssueCode.ROLE_PRIVILEGE_DRIFT,
                ISSUE_LOCATION);
    }

    private record RoleState(
            long oid,
            String sessionName,
            String currentName,
            boolean canLogin,
            boolean inherits,
            boolean superuser,
            boolean createDatabase,
            boolean createRole,
            boolean replication,
            boolean bypassRls) { }

    private record MembershipState(long received, long delegated) { }

    private record DatabaseState(
            String name,
            boolean currentDatabase,
            boolean owner,
            boolean connect,
            boolean connectGrant,
            boolean createDatabase,
            boolean temporary,
            boolean publicAcl) { }

    private record SchemaState(
            String name,
            boolean owner,
            boolean usage,
            boolean usageGrant,
            boolean createSchema) { }

    private record SystemSchemaState(
            String name,
            boolean owner,
            boolean usageGrant,
            boolean createSchema,
            boolean createGrant,
            boolean ownsRelation,
            boolean ownsFunction,
            boolean directSchemaAcl,
            boolean relationAclDrift,
            boolean columnAclDrift,
            boolean functionAclDrift,
            boolean executableSecurityDefiner,
            boolean relationCollision,
            boolean legalFunctionCollision) { }

    private record RelationState(
            String schema,
            String name,
            boolean owner,
            boolean canSelect,
            boolean selectGrant,
            boolean canInsert,
            boolean insertGrant,
            boolean canUpdate,
            boolean canDelete,
            boolean deleteGrant,
            boolean canTruncate,
            boolean canReference,
            boolean canTrigger,
            boolean publicAcl) { }

    private record ColumnState(
            String schema,
            String table,
            String name,
            boolean canSelect,
            boolean selectGrant,
            boolean canInsert,
            boolean insertGrant,
            boolean canUpdate,
            boolean updateGrant,
            boolean canReference,
            boolean publicAcl) { }

    private record SequenceState(
            String schema,
            String name,
            boolean owner,
            boolean canUsage,
            boolean usageGrant,
            boolean canSelect,
            boolean canUpdate) { }

    private record LargeObjectState(
            long oid,
            boolean owner,
            boolean directOrPublicAcl) { }

    private record ParameterState(
            String name,
            boolean canSet,
            boolean setGrant,
            boolean canAlterSystem,
            boolean alterSystemGrant) { }

    private record SessionSettings(
            String sessionReplicationRole,
            String loCompatPrivileges,
            String currentSchema,
            String searchPath,
            String configuredSearchPath,
            long tempSchema) { }

    private record FunctionPrivilege(
            String schema,
            String signature,
            boolean owner,
            boolean canExecute,
            boolean executeGrant,
            boolean publicExecute) { }
}
