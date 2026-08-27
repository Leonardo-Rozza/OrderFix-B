package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.core.LegalManifestIssueCode;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Verifies the import role's effective PostgreSQL capabilities, including PUBLIC and membership. */
final class LegalImportPrivilegeVerifier implements LegalDatabasePreflight {

    static final String ISSUE_LOCATION = "database/privileges";

    private final JdbcTemplate jdbc;
    private final String expectedRole;
    private final String expectedSchema;

    LegalImportPrivilegeVerifier(
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
        verifyDatabase(role.oid());
        verifySchemas(role.oid());
        verifyRelationsAndColumns(role.oid());
        verifySequences(role.oid());
        verifyParameterPrivileges(role.oid());
        verifyLargeObjects(role.oid());
        verifyFunctions(role.oid());
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
                           ?::oid, d.oid, 'TEMPORARY') AS temporary
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
                resultSet.getBoolean("temporary")),
                roleOid, roleOid, roleOid, roleOid, roleOid);
        boolean currentFound = false;
        for (DatabaseState database : databases) {
            currentFound |= database.currentDatabase();
            if (database.owner()
                    || database.connect() != database.currentDatabase()
                    || database.connectGrant()
                    || database.createDatabase()
                    || database.temporary()) {
                incompatible();
            }
        }
        if (!currentFound) {
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
                roleOid, roleOid, roleOid, roleOid);
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
                       pg_catalog.has_table_privilege(?::oid, c.oid, 'TRUNCATE') AS can_truncate,
                       pg_catalog.has_table_privilege(?::oid, c.oid, 'REFERENCES') AS can_reference,
                       pg_catalog.has_table_privilege(?::oid, c.oid, 'TRIGGER') AS can_trigger
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
                resultSet.getBoolean("can_truncate"),
                resultSet.getBoolean("can_reference"),
                resultSet.getBoolean("can_trigger")),
                repeated(roleOid, 10));

        Set<String> expectedRelations = new HashSet<>(LegalV27ImportInventory.IMPORT_TABLES);
        expectedRelations.add(LegalV27ImportInventory.FLYWAY_HISTORY_TABLE);
        Set<String> foundRelations = new HashSet<>();
        for (RelationState relation : relations) {
            boolean targetSchema = expectedSchema.equals(relation.schema());
            boolean importTable = targetSchema
                    && LegalV27ImportInventory.IMPORT_TABLES.contains(relation.name());
            boolean history = targetSchema
                    && LegalV27ImportInventory.FLYWAY_HISTORY_TABLE.equals(relation.name());
            if (importTable || history) {
                foundRelations.add(relation.name());
            }
            boolean allowedRead = importTable || history;
            if (relation.owner()
                    || relation.canSelect() != allowedRead
                    || relation.selectGrant()
                    || relation.canInsert() != importTable
                    || relation.insertGrant()
                    || relation.canUpdate()
                    || relation.canDelete()
                    || relation.canTruncate()
                    || relation.canReference()
                    || relation.canTrigger()) {
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
                           ?::oid, c.oid, a.attnum, 'REFERENCES') AS can_reference
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
                resultSet.getBoolean("can_reference")),
                repeated(roleOid, 7));
        for (ColumnState column : columns) {
            boolean targetSchema = expectedSchema.equals(column.schema());
            boolean importTable = targetSchema
                    && LegalV27ImportInventory.IMPORT_TABLES.contains(column.table());
            boolean history = targetSchema
                    && LegalV27ImportInventory.FLYWAY_HISTORY_TABLE.equals(column.table());
            boolean allowedUpdate = importTable
                    && LegalV27ImportInventory.UPDATE_COLUMNS
                            .getOrDefault(column.table(), Set.of())
                            .contains(column.name());
            if (column.canSelect() != (importTable || history)
                    || column.selectGrant()
                    || column.canInsert() != importTable
                    || column.insertGrant()
                    || column.canUpdate() != allowedUpdate
                    || column.updateGrant()
                    || column.canReference()) {
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
        Set<String> found = new HashSet<>();
        for (SequenceState sequence : sequences) {
            boolean allowed = expectedSchema.equals(sequence.schema())
                    && LegalV27ImportInventory.IDENTITY_SEQUENCES
                            .containsKey(sequence.name());
            if (allowed) {
                found.add(sequence.name());
            }
            if (sequence.owner()
                    || sequence.canUsage() != allowed
                    || sequence.usageGrant()
                    || sequence.canSelect()
                    || sequence.canUpdate()) {
                incompatible();
            }
        }
        if (!found.equals(LegalV27ImportInventory.IDENTITY_SEQUENCES.keySet())) {
            incompatible();
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
                largeObject.owner()
                        || largeObject.directOrPublicAcl())) {
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
        Set<String> foundV27 = new HashSet<>();
        for (FunctionPrivilege function : functions) {
            boolean v27 = expectedSchema.equals(function.schema())
                    && LegalV27ImportInventory.ALL_V27_FUNCTIONS
                            .contains(function.signature());
            if (v27) {
                foundV27.add(function.signature());
            }
            boolean allowed = v27
                    && LegalV27ImportInventory.IMPORT_FUNCTIONS
                            .containsKey(function.signature());
            if (function.owner()
                    || function.canExecute() != allowed
                    || function.executeGrant()
                    || (v27 && function.publicExecute())) {
                incompatible();
            }
        }
        if (!foundV27.equals(LegalV27ImportInventory.ALL_V27_FUNCTIONS)) {
            incompatible();
        }
    }

    private static Object[] repeated(long value, int count) {
        Object[] values = new Object[count];
        java.util.Arrays.fill(values, value);
        return values;
    }

    private static String requireText(String candidate, String name) {
        String value = Objects.requireNonNull(candidate, name).strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " no puede estar vacío");
        }
        return value;
    }

    private static void incompatible() {
        throw new LegalImportOperationalException(
                LegalManifestIssueCode.IMPORT_DB_PRIVILEGES_INCOMPATIBLE,
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
            boolean temporary) { }

    private record SchemaState(
            String name,
            boolean owner,
            boolean usage,
            boolean usageGrant,
            boolean createSchema) { }

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
            boolean canTruncate,
            boolean canReference,
            boolean canTrigger) { }

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
            boolean canReference) { }

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

    private record FunctionPrivilege(
            String schema,
            String signature,
            boolean owner,
            boolean canExecute,
            boolean executeGrant,
            boolean publicExecute) { }
}
