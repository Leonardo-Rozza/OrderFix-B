# Diseño de entitlement de suscripción

Fecha: 2026-08-13

## Objetivo

Evitar que el nombre comercial `PRO` conceda funciones por sí solo. El acceso debe depender de una suscripción vigente y aplicar la misma regla tanto al gating de funciones como al límite mensual de reparaciones.

## Regla aprobada

Una política pura `SubscriptionEntitlementPolicy` será la única fuente de verdad:

- `TRIAL` concede entitlement PRO completo, aunque el plan comercial sea `FREE`.
- `PRO + ACTIVA` concede entitlement PRO.
- `FREE + ACTIVA` conserva únicamente las funciones y el límite FREE.
- `VENCIDA` y `CANCELADA` no conceden entitlement PRO, sin importar el plan comercial.

`PlanFeatureService` consultará esta política para `requerir` y `capacidades`. `PlanLimitService` mantendrá el bloqueo de escritura para `VENCIDA` y `CANCELADA`, usará la misma política para reconocer acceso ilimitado y aplicará el tope mensual solamente cuando no exista entitlement PRO.

## Pruebas

- Matriz unitaria pura: TRIAL, PRO+ACTIVA, FREE+ACTIVA, PRO+VENCIDA y PRO+CANCELADA.
- Flujos HTTP: capacidades y acceso real a funciones para trial, PRO activo y estados PRO inactivos.
- Regresión del límite mensual: trial sin tope y FREE activo con tope.

No se modifican Mercado Pago, entidades, migraciones, configuración Maven, secretos ni servicios de clientes/equipos.
