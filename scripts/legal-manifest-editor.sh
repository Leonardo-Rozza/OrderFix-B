#!/bin/sh

set -eu

# Estas variables son interpretadas por la JVM antes de que LegalManifestCli pueda
# aplicar su frontera de redaccion. El launcher operativo debe quitarlas.
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project_dir=$(dirname -- "$script_dir")
legal_cli_jar=${ORDENFIX_LEGAL_CLI_JAR:-"$project_dir/target/mvgr-reparaciones-backend-0.0.1-SNAPSHOT-legal-cli.jar"}
java_bin=${ORDENFIX_JAVA_BIN:-java}

exec "$java_bin" -jar "$legal_cli_jar" "$@"
