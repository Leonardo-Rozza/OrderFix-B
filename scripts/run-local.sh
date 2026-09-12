#!/bin/sh
set -eu

# Carga explícita sólo para este arranque. No se importa al compilar ni al ejecutar tests.
if [ "$#" -gt 1 ]; then
    echo "Uso: ./scripts/run-local.sh [archivo-de-secretos.properties]" >&2
    exit 2
fi
repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
secrets_file=${1:-"$repo_root/src/main/resources/application-secret.properties"}
case "$secrets_file" in
    /*) ;;
    *) secrets_file="$PWD/$secrets_file" ;;
esac
if [ ! -f "$secrets_file" ] || [ ! -r "$secrets_file" ]; then
    echo "No se puede leer el archivo local de configuración. Indicá un archivo .properties existente." >&2
    exit 2
fi
export SPRING_CONFIG_IMPORT="file:$secrets_file"
cd "$repo_root"
exec ./mvnw spring-boot:run
