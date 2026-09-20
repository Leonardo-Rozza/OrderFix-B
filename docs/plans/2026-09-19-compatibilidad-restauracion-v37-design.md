# Compatibilidad de escritura después de restaurar V37

Fecha: 2026-09-19. Continuación autorizada de recuperación con checkpoint y cuarentena.
Backend parte de bd080c7. Sin cambios de frontend, push, despliegue ni datos reales.

## Alcance y decisión

Una base restaurada es otra base reconstruida a partir de un backup. PostgreSQL 16
reconstruye expresiones de catálogo que conservan el significado pero cambian su
representación. Los verificadores históricos comparan huellas exactas y rechazan
esa representación: no basta que el diagnóstico de sólo lectura coincida.

Se agrega un perfil de catálogo restaurado exacto para el esquema actual public/V37,
junto a las constantes migradas originales. No se modifica SQL V27–V37, ni se
normaliza SQL en producción, ni se omite un verificador después de una excepción.
Las alternativas fueron reemplazar las huellas por normalización SQL (amplía el
contrato y puede ocultar cambios) o usar el perfil de lectura como autorización de
escritura (no acredita las capacidades de cada rol). Se descartan ambas.

Los verificadores mantienen sus consultas, sesión, Flyway, topología, funciones,
propiedad y permisos. En V27 import/editorial la variante sólo se admite con historial
V37 exacto, sin recursión ni cambios de los consumidores de versiones anteriores.
V28 conserva su delegación a V29; los deltas de cierre y fotos admiten su variante
completa únicamente para V37. El catálogo de registro, el delta de fotos V35 y el
catálogo propio V37 son idénticos después de restaurar: no necesitan otra constante.
No se aceptan combinaciones por campo de huellas migradas/restauradas. Una tercera
representación de cada catálogo sigue fallando cerrada.

## Plan del corte

1. Capturar huellas con migraciones congeladas en PostgreSQL 16 descartable y
   pg_dump/pg_restore reales; comprobar que sólo cambian las representaciones ya
   identificadas de CHECK/índices y AST de condiciones de trigger.
2. Incorporar constantes restauradas independientes, preservando las originales.
3. Verificar todos los consumidores en fuente, primera y segunda restauración;
   comprobar rechazo de drift y operación/replay/rollback con rol restringido.
4. Pruebas focales y clean verify por afectar verificadores compartidos.
5. Actualizar runbook, comprobar preservación y crear un commit atómico sin push.

## Límites operativos

El perfil sólo acredita metadatos conocidos. No demuestra actualidad de los datos,
no reconcilia borrados o sesiones, no quita la cuarentena ni autoriza reapertura.
La captura externa y revisión de divergencias siguen siendo necesarias. Roles y
configuración global de PostgreSQL no están incluidos en un pg_dump de una base;
el bootstrap de roles se prueba y documenta por separado del catálogo restaurado.

## Evidencia y resultado

Diagnóstico PostgreSQL 16 ejecutado con migraciones originales. Se cotejaron las
huellas de importación, editorial, aceptación, registro, cierre V33/V34, fotos y
borrado V37. El contraste de los dos AST WHEN muestra sólo posiciones de texto y
relabelformat 2→1; ambas definiciones SQL son idénticas. El ensayo de lectura del
corte anterior sigue comprobando las 52 distribuciones de cast varchar[]→text[]
en el catálogo completo, sin cambios semánticos residuales ni normalización en
producción. Una revisión independiente cotejó todas las nuevas constantes.

El helper de historial devuelve rechazo de compatibilidad ante SCHEMA_DRIFT para
preservar los códigos históricos del importador y dry-run; no oculta otros errores.
No se modifica el contrato de errores ni se sustituye un preflight por otro.

Validación focal aprobada: **76 pruebas (22 unitarias y 54 IT)**, cero fallos,
errores u omisiones; BUILD SUCCESS en 03:56 min. Incluye los 23 casos de backup,
20 controles V37 existentes y 11 casos nuevos de compatibilidad. El gate agregado
restringido acredita preflight, permisos y frontera RN/RC; el DML real probado en
la restauración es el servicio de borrado operativo, con replay inmutable,
colisiones rechazadas y rollback posterior a un resultado DELETED. No se atribuye
materialización de agregados al callback vacío de la prueba del gate.

Comando focal:

```sh
DOCKER_AUTH_CONFIG='{"auths":{}}' ./mvnw -B \
  '-Dtest=WorkshopOperationalDeletionWorkerTest' \
  '-Dit.test=LegalV37RestoreCompatibilityIT,LegalV37CompatibilityIT,WorkshopClosureBackupRecoveryIT' verify
```

**Gate integral aprobado**: `./mvnw -B clean verify` finalizó con BUILD SUCCESS en
46:54 min, Java 21 y PostgreSQL 16 Testcontainers: **10.083 pruebas (8.115 unitarias y 1.968 IT)**, cero fallos, errores u omisiones. Incluye el control de los tres JAR
sin propiedades secretas. No hubo correcciones de código ni reejecuciones después
de esta corrida integral; sólo se completó esta documentación.

Evidencia local en `/private/tmp/ordenfix-write-restore-20260919/`: baseline, captura
diagnóstica, logs originales, informes focales/integrales y control de preservación.
La primera invocación focal no ejecutó pruebas por un selector de clase inexistente;
se conserva su log y se corrigió el selector, sin omitir la fase de tests.

Se comprobó la igualdad SHA-256 de las 11 migraciones V27–V37 y de los 179 archivos
no versionados preexistentes del frontend, cuyo HEAD y estado permanecen intactos.
Los inventarios y constantes migradas originales no se editaron. El corte queda en
un único commit `fix(legal): acredita restauraciones logicas v37`, sin push.
La reapertura real sigue requiriendo cuarentena, evidencia externa y reconciliación;
este corte no ejecutó restauraciones de datos reales ni cambió flags operativos.
