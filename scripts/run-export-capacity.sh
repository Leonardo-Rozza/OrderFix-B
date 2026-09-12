#!/bin/sh
set -eu

# No permitir opciones JVM heredadas que impriman credenciales o cambien el probe.
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project_dir=$(dirname -- "$script_dir")

exec python3 - "$project_dir" "$@" <<'PY'
import argparse
import os
import pathlib
import platform
import shutil
import signal
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

project = pathlib.Path(sys.argv.pop(1))
parser = argparse.ArgumentParser(
    description="Probe manual: codec real + BYTEA en PostgreSQL 16 descartable, sin Maven ni datos reales.",
    epilog="Requiere Docker, Java/Javac 21 y clases/dependencias ya compiladas. No acredita capacidad de la app completa.",
)
parser.add_argument("--synthetic-pg16-only", action="store_true", required=True,
                    help="autoriza únicamente el fixture y PostgreSQL descartable del probe")
parser.add_argument("--heap", choices=("768m", "1g", "2g"), default="2g",
                    help="heap máximo del proceso hijo (default: 2g; no es recomendación de producción)")
parser.add_argument("--timeout-seconds", type=int, default=180, help="plazo del probe, 30–900 segundos (default: 180)")
parser.add_argument("--java-home", type=pathlib.Path, help="JDK 21; por defecto JAVA_HOME o java/javac del PATH")
source = parser.add_mutually_exclusive_group()
source.add_argument("--classpath-file", type=pathlib.Path, help="archivo con un classpath ya resuelto; no se imprime")
source.add_argument("--report", type=pathlib.Path,
                    default=project / "target/surefire-reports/TEST-com.leonardorozza.mvgrreparacionesbackend.cuenta.export.LocalExportPackageWriterTest.xml",
                    help="XML Surefire previo con java.class.path; el script nunca ejecuta Maven")
args = parser.parse_args()
if not 30 <= args.timeout_seconds <= 900:
    parser.error("--timeout-seconds debe estar entre 30 y 900")
try:
    if args.classpath_file:
        classpath = args.classpath_file.read_text().strip()
    else:
        tree = ET.parse(args.report)
        classpath = next(item.attrib["value"] for item in tree.findall("./properties/property")
                         if item.attrib.get("name") == "java.class.path")
    if not classpath or "\n" in classpath or "\r" in classpath:
        raise ValueError()
except (OSError, ET.ParseError, StopIteration, KeyError, ValueError):
    parser.error("Falta un classpath previo válido. Proveer --classpath-file o un XML Surefire con --report; no se compilará con Maven automáticamente.")

java_home = args.java_home or (pathlib.Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else None)
java = str(java_home / "bin/java") if java_home else shutil.which("java")
javac = str(java_home / "bin/javac") if java_home else shutil.which("javac")
if not java or not javac or not pathlib.Path(java).is_file() or not pathlib.Path(javac).is_file():
    parser.error("Faltan java/javac: configurar --java-home con un JDK 21")
if not pathlib.Path("/usr/bin/time").is_file():
    parser.error("Se requiere /usr/bin/time para registrar RSS y tiempo")

probe_source = project / "src/test/java/com/leonardorozza/mvgrreparacionesbackend/cuenta/export/ExportArtifactCapacityProbe.java"
output = pathlib.Path(tempfile.mkdtemp(prefix="ordenfix-export-capacity-", dir="/private/tmp" if pathlib.Path("/private/tmp").is_dir() else None))
print("Resultados y clases aisladas: " + str(output), flush=True)
environment = dict(os.environ)
for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
    environment.pop(name, None)
# El probe sólo utiliza imágenes públicas y una contraseña sintética propia.
environment["DOCKER_AUTH_CONFIG"] = '{"auths":{}}'

# El grupo incluye /usr/bin/time y la JVM. El plazo no deja un hijo Java suelto.
def bounded(command, log_path, timeout):
    with log_path.open("w") as log:
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT,
                                   cwd=project, env=environment, start_new_session=True)
        try:
            return process.wait(timeout=timeout)
        except (subprocess.TimeoutExpired, KeyboardInterrupt):
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
            print("Proceso interrumpido o vencido. Revisar el log y la limpieza de los contenedores propios del probe.", file=sys.stderr)
            return 124

code = bounded([javac, "-proc:none", "-cp", classpath, "-d", str(output), str(probe_source)], output / "javac.log", 60)
if code:
    print("Falló la compilación aislada; ver javac.log. No se ejecutó el probe.", file=sys.stderr)
    sys.exit(code)

main = "com.leonardorozza.mvgrreparacionesbackend.cuenta.export.ExportArtifactCapacityProbe"
command = ["/usr/bin/time", "-l" if platform.system() == "Darwin" else "-v", java,
           "-Xmx" + args.heap, "-Djava.awt.headless=true", "-cp", str(output) + os.pathsep + classpath,
           main, "--synthetic-pg16-only"]
log_path = output / ("heap-" + args.heap + ".log")
code = bounded(command, log_path, args.timeout_seconds)
print("Resultado: exit=" + str(code) + "; log=" + str(log_path))
print("Medición del codec/JDBC sintético; no incluye memoria de Docker/PG ni acredita el backend completo.")
sys.exit(code)
PY
