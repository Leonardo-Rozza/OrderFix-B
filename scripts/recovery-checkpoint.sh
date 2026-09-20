#!/bin/sh

set -eu

# The JVM reads these before main and can print private values or load agents.
# The recovery CLI does not initialize Spring or load application configuration.
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project_dir=$(dirname -- "$script_dir")
recovery_cli_jar=${ORDENFIX_RECOVERY_CLI_JAR:-"$project_dir/target/mvgr-reparaciones-backend-0.0.1-SNAPSHOT-recovery-cli.jar"}
java_bin=${ORDENFIX_JAVA_BIN:-java}

exec "$java_bin" -jar "$recovery_cli_jar" "$@"
