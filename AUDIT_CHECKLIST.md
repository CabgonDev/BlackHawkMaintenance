# Checklist de corrección — BlackHawk Maintenance

Esta checklist está ordenada por dependencia. No debe generarse una nueva versión de producción hasta cerrar los puntos P0 y P1.

## Fase 0 — Preparación y control

- [ ] Trabajar únicamente en `C:\Users\t-he-\AndroidStudioProjects\BlackHawkMaintenance`.
- [ ] Crear respaldo previo en `C:\Users\t-he-\OneDrive\Desktop\BHM Codex` antes de editar.
- [ ] Conservar cambios no relacionados.
- [ ] Registrar versión publicada, certificado actual, `versionCode`, versiones Room y configuración Firebase.
- [ ] Crear rama/commit de trabajo cuando exista repositorio Git; actualmente el directorio auditado no contiene `.git`.

## P0 — Firma y secretos

- [ ] Rotar la clave y las credenciales de firma si el árbol o sus respaldos fueron compartidos.
- [ ] Confirmar antes de revocar la clave si se usa Google Play App Signing.
- [ ] Retirar las credenciales de `C:\Users\t-he-\AndroidStudioProjects\BlackHawkMaintenance\keystore.properties`.
- [ ] Retirar `C:\Users\t-he-\AndroidStudioProjects\BlackHawkMaintenance\app\keystore\bhm.jks` del árbol y de artefactos compartidos.
- [ ] Migrar la firma a secretos protegidos de CI o un gestor de secretos.
- [ ] Hacer fallar el build release si faltan secretos; nunca usar valores vacíos.
- [ ] Auditar backups, artefactos y, cuando exista, historial Git.
- [ ] Verificar que el nuevo APK conserva el certificado correcto y actualiza una instalación existente.

## P1 — Persistencia y aislamiento de datos

- [ ] Eliminar `fallbackToDestructiveMigration(dropAllTables = true)` de `app/src/main/java/com/cabgon/blackhawk/data/db/AppDbProvider.kt` y `app/src/main/java/com/cabgon/blackhawk/data/DbProvider.kt`.
- [ ] Documentar todas las versiones de `app/src/main/java/com/cabgon/blackhawk/data/db/AppDatabase.kt` y `app/src/main/java/com/cabgon/blackhawk/data/PreflightDb.kt`.
- [ ] Crear migraciones explícitas para cada versión, incluida la migración 9→10.
- [ ] Verificar que la eliminación de tablas de chat en 9→10 sea intencional.
- [ ] Añadir `MigrationTestHelper` para actualizar desde cada versión publicada.
- [ ] Probar conservación de inspecciones, preflight, usuarios y estados sincronizados.
- [ ] Definir recuperación/exportación si una migración falla.

## P1 — Aislamiento entre cuentas

- [ ] Añadir `ownerUid` a las entidades de `app/src/main/java/com/cabgon/blackhawk/data/PreflightEntities.kt`.
- [ ] Filtrar todos los DAO por `ownerUid` en `app/src/main/java/com/cabgon/blackhawk/data/PreflightDao.kt`.
- [ ] Actualizar `app/src/main/java/com/cabgon/blackhawk/data/PreflightRepository.kt`.
- [ ] Corregir `syncDirtyInspections()` para sincronizar únicamente filas del usuario actual en `app/src/main/java/com/cabgon/blackhawk/data/PreflightRemoteSync.kt`.
- [ ] Validar propietario antes de borrar documentos remotos en `app/src/main/java/com/cabgon/blackhawk/data/PreflightRemoteSync.kt`.
- [ ] Limpiar o aislar la base local al cerrar sesión en `app/src/main/java/com/cabgon/blackhawk/data/auth/AuthStateCleaner.kt`.
- [ ] Probar dos cuentas consecutivas en el mismo dispositivo con datos pendientes de sincronización.

## P1 — Autorización Firebase

Las reglas remotas deben obtenerse desde Firebase Console; su ausencia local no demuestra que producción esté abierta.

- [ ] Exportar y versionar temporalmente Firestore Rules y Storage Rules.
- [ ] Exigir `request.auth.uid == uid` al crear documentos de usuario.
- [ ] Impedir que el cliente cambie `role`, `status`, `uid` o campos administrativos.
- [ ] Restringir inspecciones 40h/120h/480h por propietario o dataset compartido explícito.
- [ ] Validar ownership en En Ruta, preflight, galería y documentos administrativos.
- [ ] Sustituir escrituras administrativas directas de `app/src/main/java/com/cabgon/blackhawk/ui/admin/UsersListFragment.kt` por Functions o reglas con claims server-side.
- [ ] Proteger approve/reject/delete de `app/src/main/java/com/cabgon/blackhawk/data/GalleryRepository.kt`.
- [ ] Eliminar el fallback de `idToken` en el cuerpo de `adminSuspendUser`; usar solo `context.auth` en `functions/index.js`.
- [ ] Validar `targetUid`, motivo y estado del solicitante en Functions.
- [ ] Añadir Firebase Emulator Suite con casos permitidos y denegados.
- [ ] Hacer que cambios de `role` invaliden el cache y la UI de MainActivity.

## P1 — Actualizaciones OTA de contenido

- [ ] Exigir SHA-256 y tamaño para AI, manuales, generalities y frequencies.
- [ ] Permitir únicamente prefijos Storage aprobados.
- [ ] Rechazar `..`, separadores inesperados y rutas arbitrarias.
- [ ] Firmar o proteger los metadatos de actualización.
- [ ] Implementar anti-downgrade monotónico.
- [ ] Descargar a archivo temporal, verificar hash y reemplazar atómicamente.
- [ ] Añadir rollback si la actualización se interrumpe.
- [ ] Mantener las validaciones existentes de `app/src/main/java/com/cabgon/blackhawk/update/AppUpdateManager.kt` para APK.
- [ ] Probar hash incorrecto, archivo truncado, ruta inválida, downgrade y pérdida de red.

## P2 — Ciclo de vida y estabilidad

- [ ] Cambiar `Fragment.lifecycleScope` por `viewLifecycleOwner.lifecycleScope` o `repeatOnLifecycle` en Generalities, GeneralitiesSection, AdminGeneralities, AdminFrequencies, AdminAuditLog y AdminEnRuta.
- [ ] Revisar `app/src/main/java/com/cabgon/blackhawk/content/generalities/GeneralitiesFragment.kt`.
- [ ] Cancelar listeners, jobs y callbacks en `onDestroyView`.
- [ ] Proteger callbacks de cámara/picker en GalleryUploadBottomSheet.
- [ ] Sustituir los hilos manuales de PDF por coroutines cancelables en `app/src/main/java/com/cabgon/blackhawk/ui/fragments/PreflightChecklistFragment.kt` y los fragments 40h/120h/480h.
- [ ] Capturar excepciones de Firestore `.await()` en `app/src/main/java/com/cabgon/blackhawk/admin/generalities/AdminGeneralitiesRollbackFragment.kt`.
- [ ] Corregir el fallback abierto de red en `app/src/main/java/com/cabgon/blackhawk/ui/StartupActivity.kt`.
- [ ] Añadir timeout, estado “desconocido” y botón de reintento.
- [ ] Reemplazar catches vacíos por errores tipados, logs seguros y estados visibles.

## P2 — Sesión, privacidad e intents

- [ ] No confiar en `EXTRA_ACCESS_ALREADY_VALIDATED` ni extras equivalentes recibidos desde otra aplicación en StartupActivity.
- [ ] Usar un estado interno de sesión o permiso firmado para rutas privilegiadas.
- [ ] Decidir si el desbloqueo biométrico offline de 24 horas cumple la política.
- [ ] Añadir bloqueo por inactividad o al volver del background si la política lo exige.
- [ ] Escuchar cambios de `role`, no solo de `status`.
- [ ] Evaluar `FLAG_SECURE` para login, Main, checklists y visor PDF si los datos son sensibles.
- [ ] Evaluar cifrado de Room para amenazas de root/forense.
- [ ] Restringir la API key Firebase por paquete y SHA-1/SHA-256.
- [ ] Activar App Check/Play Integrity y `enforceAppCheck` en Functions.
- [ ] Cambiar desafíos ECDSA a nonce aleatorio de un solo uso con TTL.
- [ ] Validar URLs externas en `app/src/main/java/com/cabgon/blackhawk/data/PartRepo.kt` mediante allowlist.
- [ ] Evitar que `pingAuth` exponga información innecesaria.

## P2 — Galería y almacenamiento

- [ ] En `app/src/main/java/com/cabgon/blackhawk/data/GalleryRepository.kt`, no eliminar el documento Firestore si Storage falló.
- [ ] Usar tombstone, reintento y limpieza verificable.
- [ ] Probar archivos huérfanos, errores de permisos y eliminación parcial.

## P2 — Edge-to-edge y compatibilidad

- [ ] Definir una política global de edge-to-edge para `targetSdk 37`.
- [ ] Aplicar WindowInsets de forma consistente.
- [ ] Revisar layouts con `fitsSystemWindows`.
- [ ] Probar API 28, API actuales, rotación, teclado, barras transparentes y modo inmersivo.
- [ ] Corregir todos los errores de lint antes de suprimir cualquier regla.

## P2 — Pruebas obligatorias

- [ ] Migraciones Room desde cada versión publicada.
- [ ] Dos usuarios en el mismo dispositivo.
- [ ] Logout, cambio de usuario y sincronización pendiente.
- [ ] `pending_auth_reset_version` y `claimDevice`.
- [ ] Biometría, proceso eliminado y restauración.
- [ ] Hash, certificado, downgrade e interrupción de actualización.
- [ ] FileProvider e intents externos.
- [ ] Rotación, back, picker/cámara y destrucción de vistas.
- [ ] Reglas Firebase mediante Emulator Suite.
- [ ] Accesibilidad, edge-to-edge y permisos.
- [ ] Ejecutar todo en CI con API mínima y API actual.

## P3 — Build, dependencias y suministro

- [ ] Separar flavors `dev`, `staging` y `prod`.
- [ ] Usar distintos `google-services.json`; impedir debug contra producción.
- [ ] Añadir `distributionSha256Sum` en `gradle/wrapper/gradle-wrapper.properties`.
- [ ] Activar dependency verification, locks y generación de SBOM.
- [ ] Alinear AGP, Kotlin, KSP, Room y Java toolchain.
- [ ] Retirar JitPack si no es imprescindible.
- [ ] Fijar dependencias de Functions y añadir tests/lint/predeploy.
- [ ] Revisar PDF beta y dependencias transitivas.
- [ ] Crear CI con `check`, `lint`, tests, build release y validación de firma.

## P3 — Artefactos y reproducibilidad

- [ ] Sacar `app/release/app-release.apk`, `mapping.txt` y símbolos nativos del árbol fuente.
- [ ] Ignorar `hs_err_pid*.log`, `replay_pid*.log`, `deps_*`, `project_tree.txt` y artefactos generados.
- [ ] Revisar el OOM histórico de `hs_err_pid2840.log`.
- [ ] Ajustar memoria de Gradle/R8 y documentar el entorno de compilación.
- [ ] Revisar logs antes de publicarlos para evitar datos sensibles.

## P3 — Assets, índices y contenido

- [ ] Confirmar si `inspection_120h.json` e `inspection_480h.json` vacíos son intencionales.
- [ ] Restaurar contenido o marcar explícitamente esos módulos como incompletos.
- [ ] Hacer fallar los scripts si faltan PDFs o índices.
- [ ] Eliminar `pause` de scripts destinados a CI.
- [ ] Fijar versión de Python y validar conteos/checksums de assets antes del release.

## P3 — UI y accesibilidad

- [ ] Mover textos, hints y content descriptions a `strings.xml`.
- [ ] Ejecutar lint de accesibilidad.
- [ ] Revisar TalkBack, foco, contraste, tamaños dinámicos y orientación.
- [ ] Añadir content descriptions funcionales a iconos y controles.

## Cierre de cada corrección

Para marcar un punto como cerrado debe existir:

- [ ] Cambio implementado.
- [ ] Prueba automatizada o evidencia manual reproducible.
- [ ] Ruta y líneas afectadas documentadas.
- [ ] Resultado de `check`, `lint`, tests y build release.
- [ ] Revisión de seguridad cuando afecte autenticación, Firebase, firma u OTA.
- [ ] Registro de fecha, comando ejecutado, versión probada y artefacto generado.

## Criterio final de liberación

- [ ] Sin secretos en código, historial, backups ni artefactos.
- [ ] Migraciones Room probadas y sin borrado destructivo.
- [ ] Aislamiento entre cuentas validado.
- [ ] Firebase Rules y Functions probadas en Emulator Suite.
- [ ] OTA con hash obligatorio, rutas permitidas y anti-downgrade.
- [ ] Lint, tests, `check` y release build verdes.
- [ ] Smoke test en API 28 y Android actual.
- [ ] APK firmado con certificado esperado.
- [ ] Plan de rollback y recuperación documentado.

## Complementos viables derivados de `AUDITORIA_BLACKHAWK_MAINTENANCE.md`

Estos puntos complementan la checklist anterior. Los hallazgos ya cubiertos —firma, reglas Firebase, pruebas, accesibilidad, KSP, assets y OTA— no se duplican.

### P2 — Seguridad de red y permisos

- [ ] Crear `app/src/main/res/xml/network_security_config.xml` con `cleartextTrafficPermitted="false"` y asociarlo al `application` del manifest.
- [ ] Verificar que todas las URLs externas, incluida `https://www.wbparts.com/`, usan HTTPS, timeout y allowlist.
- [ ] Evaluar certificate pinning solo para endpoints bajo control del equipo y con estrategia de rotación, respaldo y recuperación; no bloquear producción sin pruebas de renovación.
- [ ] Implementar la solicitud runtime de `POST_NOTIFICATIONS` para Android 13/API 33+.
- [ ] Manejar rechazo, revocación y reinstalación del permiso sin bloquear el flujo principal.

### P2 — Logs y diagnóstico

- [ ] Inventariar todas las llamadas de logging de producción.
- [ ] Confirmar que nunca se registran contraseñas, tokens completos, claves, PII ni documentos de inspección.
- [ ] Crear un wrapper de logging con niveles y redacción; desactivar `Log.d`/`Log.v` en release mediante configuración de build.
- [ ] Revisar breadcrumbs y mensajes de Crashlytics para que tampoco contengan secretos o PII.

### P2 — Tests de lógica crítica

- [ ] Añadir pruebas unitarias específicas para `DeviceBindingVerifier`.
- [ ] Añadir pruebas unitarias para `BiometricCredentialStore` y rotación/invalidez de claves.
- [ ] Añadir pruebas para `SikorskyPartsIndex`, normalización, límites y consultas FTS.
- [ ] Añadir pruebas para `UserSessionStore`, validación de UID/status y parseo de respuestas de red.
- [ ] Añadir pruebas de errores, timeouts y respuestas incompletas de WBParts.

### P3 — Mantenibilidad de Activities y contratos externos

- [ ] Extraer gradualmente lógica de `StartupActivity.kt` y `LoginActivity.kt` hacia ViewModels/use cases, manteniendo cada extracción cubierta por pruebas.
- [ ] No hacer una reescritura masiva: separar primero migración de sesión, red, navegación y autenticación.
- [ ] Documentar base URL, endpoints, parámetros y esquemas de respuesta de WBParts.
- [ ] Versionar contratos externos y añadir pruebas de compatibilidad para cambios de HTML/JSON.

### P3 — Tamaño del APK y assets

- [ ] Medir el peso real de manuales, índices y checklists por variante.
- [ ] Publicar Android App Bundle y evaluar entrega bajo demanda para manuales grandes.
- [ ] Mantener disponibles los manuales necesarios offline y probar instalación limpia, actualización y acceso sin red.
- [ ] Verificar que la reducción de assets no rompa índices FTS ni enlaces de PDF.

### P3 — Observabilidad controlada

- [ ] Evaluar Firebase Crashlytics para builds release con redacción de PII y separación dev/staging/prod.
- [ ] Evaluar Performance Monitoring para arranque, sincronización, descargas y generación de PDF.
- [ ] Subir mapping/symbols a un almacén protegido y limitar el acceso al equipo autorizado.
- [ ] Documentar retención, consentimiento y exclusiones de datos antes de activar Analytics u otros eventos de uso.

## Hallazgos técnicos adicionales del informe final

### P1 — Backend y Functions

- [ ] Migrar `functions/index.js` completamente a la firma v2 `async (request) => { request.data; request.auth; request.rawRequest }` para `adminSuspendUser` y `pingAuth`.
- [ ] Retirar el workaround de payload doble y el envío de `idToken` dentro del JSON una vez validado el callable estándar.
- [ ] En cualquier ruta de verificación manual que permanezca, usar `verifyIdToken(token, true)` para comprobar revocación.
- [ ] Probar llamadas callable desde el SDK Android, solicitudes sin auth, tokens revocados y tokens expirados.
- [ ] Retirar `pingAuth` si solo se usa como diagnóstico o limitarlo a entorno no productivo sin exponer datos operativos.

### P1 — DialogFragments recreables

- [ ] Corregir `CreateInspection40hDialogFragment`, `CreateInspection120hDialogFragment`, `CreateInspection480hDialogFragment` y `PreflightNewDialog`, que reciben lambdas en el constructor.
- [ ] Usar constructor vacío, `arguments` serializables y `setFragmentResult`/`setFragmentResultListener` para devolver resultados.
- [ ] Probar rotación, cambio de tamaño/fuente, modo oscuro y muerte/recreación del proceso con cada diálogo abierto.

### P1 — Memoria y Firestore

- [ ] Mover imágenes grandes de `app/src/main/res/drawable/` a `drawable-nodpi` o reducirlas a dimensiones de uso real/WebP.
- [ ] Ajustar `safeDecode()` en `BaseInspectionPdfExporter.kt` y `PdfExporter.kt` con `inSampleSize`, `inScaled=false` cuando corresponda y liberación segura de recursos.
- [ ] Medir heap y generación de PDF en dispositivos de memoria baja.
- [ ] Particionar los `WriteBatch` de inspecciones 40h/120h/480h en bloques inferiores al límite de Firestore.
- [ ] Probar creación y borrado con más de 500 ítems y reintentos parciales.

### P2 — Foreground Service y recursos nativos

- [ ] Garantizar que `UpdateDownloadService` llame a `startForeground()` antes de cualquier retorno tras `startForegroundService()`.
- [ ] Revisar las rutas `intent == null`, cancelación y parámetros inválidos en `app/src/main/java/com/cabgon/blackhawk/ui/UpdateDownloadService.kt`.
- [ ] Pasar explícitamente el tipo `FOREGROUND_SERVICE_TYPE_DATA_SYNC` cuando aplique.
- [ ] Implementar `onTimeout()` para `dataSync` y probar descargas largas, cancelación, reinicio y timeout del sistema.
- [ ] Implementar `onCleared()` en `EnRutaViewModel` para detener `SnapshotListener`.
- [ ] Llamar `translator.close()` en `AiQueryFragment.onDestroyView()`.
- [ ] Verificar que todos los `Closeable` de SQLite, ML Kit y archivos se liberen ante éxito, error y cancelación.

### P2 — Contenido y consultas locales

- [ ] Confirmar y completar `inspection_120h.json` y `inspection_480h.json`, o impedir publicar inspecciones vacías.
- [ ] Añadir el asset semilla de `generalidades/generalidades.json` o eliminar el fallback que apunta a un archivo inexistente.
- [ ] Definir comportamiento offline explícito para Frecuencias cuando no exista contenido OTA local.
- [ ] Corregir la columna `content` por `text` en el fallback `LIKE` de `RAGIndex.kt`.
- [ ] Añadir una prueba que fuerce el fallback FTS y verifique resultados reales, sin ocultar la excepción.
- [ ] Confirmar si `sikorsky_parts_chunks.jsonl` y `sikorsky_parts_chunks_fts4.db` tienen consumidores activos; eliminar solo después de una búsqueda completa de referencias y una prueba APK limpia.

### P2 — UI y rendimiento

- [ ] Extraer `RecyclerView` de `NestedScrollView` en `fragment_part_numbers.xml`, `fragment_en_ruta_detail.xml`, `fragment_admin_home.xml` y `fragment_admin_generality_edit.xml`.
- [ ] Probar listas con cientos de resultados y verificar reciclaje, scroll y memoria.
- [ ] Evitar dobles llamadas a `popBackStack()` cuando la validación de red se ejecuta en `onViewCreated()` y `onResume()`.
- [ ] Hacer atómico el reemplazo de archivos de Frecuencias y Generalidades usando `.part`, `.bak` y recuperación, igual que los flujos OTA ya protegidos.

### P3 — Estructura y dependencias

- [ ] Confirmar el uso real de las dos bases Room antes de retirar tablas, DAOs o entidades huérfanas.
- [ ] Documentar la estrategia final: consolidar bases o mantenerlas separadas con responsabilidades claras.
- [ ] Eliminar archivos residuales y clases sin referencias solo después de compilar, ejecutar tests y revisar reflection/R8.
- [ ] Revisar la duplicación 40h/120h/480h y extraer componentes comunes en cambios pequeños cubiertos por pruebas.
- [ ] Confirmar y retirar dependencias sin uso como Volley o `converter-moshi` únicamente después de un análisis de referencias y build completo.
- [ ] Corregir `build_fts_index.py` para aceptar o rechazar explícitamente sus argumentos CLI.
- [ ] Hacer fallar la generación de índices cuando faltan PDFs o se generan cero filas.
- [ ] Eliminar `pause` de scripts de índices para permitir ejecución no interactiva en CI.
- [ ] Añadir validación de conteo, checksum y esquema de cada índice antes del release.
