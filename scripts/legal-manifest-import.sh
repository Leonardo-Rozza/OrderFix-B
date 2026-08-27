#!/bin/sh

set -eu

# Estas variables son interpretadas por la JVM antes de que LegalManifestCli pueda
# aplicar su frontera de redaccion. El launcher operativo debe quitarlas.
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS

legal_cli_jar=${ORDENFIX_LEGAL_CLI_JAR:?ORDENFIX_LEGAL_CLI_JAR requerida}
java_bin=${ORDENFIX_JAVA_BIN:-java}

exec "$java_bin" -jar "$legal_cli_jar" import "$@"
