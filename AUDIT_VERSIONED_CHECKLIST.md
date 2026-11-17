# Checklist versionado de remediación — BlackHawk Maintenance

Este documento convierte los hallazgos de [`AUDIT_CHECKLIST.md`](AUDIT_CHECKLIST.md) en entregas que se pueden revisar, probar y publicar una por una. El orden va de menor complejidad y tiempo a mayor riesgo, coordinación y duración. La checklist original se conserva como fuente de hallazgos; este archivo es el plan ejecutable.

## Reglas de ejecución

- Cada entrega termina con un commit. Crear un tag `v5.x.y` solo cuando el artefacto de la app de esa versión pase sus gates; los cambios exclusivamente documentales o de CI no requieren incrementar la versión de la app.
- La versión actual de producto es `5.0.1` (`versionCode` `501`). La siguiente versión de producto propuesta es `5.0.2`; nunca reutilizar ni disminuir `versionCode`.
- La secuencia `v0.0.1` del tag Git existente es únicamente el bootstrap del repositorio y no representa una versión de la app.
- La app se distribuye por instalación local o por canales de nube controlados; no se publica en Play Store. El artefacto de producto es un APK firmado y verificable.
- No se abre la siguiente versión hasta cumplir el gate de la anterior.
- Cada casilla cerrada debe registrar archivo y líneas afectadas, comando ejecutado, resultado, versión probada, artefacto generado y procedimiento de rollback.
- Las pruebas de Android se ejecutan con `.\gradlew.bat check lint test --no-daemon` y el build release se repite con secretos válidos en un entorno protegido.
- Las decisiones de política (continuidad y recuperación de la clave de firma, proveedor de App Check compatible con sideload, biometría offline, `FLAG_SECURE`, cifrado de Room, certificate pinning, Crashlytics y Performance Monitoring) se documentan en un ADR antes de marcar su implementación.
- Las reglas de Firestore y Storage son producción administrada en Firebase Console. La ausencia de `firestore.rules` o `storage.rules` en este árbol no demuestra que producción esté abierta. Solo se incorpora un snapshot fechado si existe acceso explícito a la consola; ese snapshot se etiqueta como evidencia, no como fuente de despliegue.
- Las rutas se corrigieron a la estructura real del proyecto. Los archivos que aún no existen se marcan como creación.

## Línea base conocida

| Dato | Valor observado |
|---|---|
| Proyecto | `C:\Users\t-he-\AndroidStudioProjects\BlackHawkMaintenance` |
| `applicationId` | `com.cabgon.blackhawk` |
| `versionCode` / `versionName` | `501` / `5.0.1 (Hawk)` |
| `minSdk` / `targetSdk` / `compileSdk` | `28` / `37` / `37` |
| Java / Kotlin | JVM 17 / Kotlin 2.4.20 |
| Firebase project | `blackhawk-maintenance` (`.firebaserc`) |
| Distribución / OTA de APK | Fuera de Play Store; `AppUpdateManager` obtiene metadatos desde Firestore `ai_ota/channels` y descarga desde Firebase Storage bajo `ai/stable/apk/` |
| Estado Git inicial | Sin `.git` local antes de esta preparación; ahora versionado en `master` |
| Remoto | `https://github.com/CabgonDev/BlackHawkMaintenance.git` |
| Rama remota | `master`, con 22 commits históricos y el árbol final vacío |
| Pruebas existentes reutilizables | `LoginBiometricStateTest`, `LoginFlowPolicyTest`, `RegisterViewModelTest`, `DownloadedFileCommitTest`, `DownloadProgressTrackerTest` |

## App v5.0.1 — Línea base actual y repositorio seguro

**Estado:** versión actual, `versionCode` 501. **Esfuerzo de control Git:** 0.5–1 día. **Commit de bootstrap:** `b1823df` (`chore(v0.0.1): establish safe audit baseline`); el tag `v0.0.1` es del repositorio, no de la app. **Gate:** el snapshot es recuperable y `git add --dry-run` no incluye secretos ni artefactos.

- [ ] Confirmar que todo destino de edición está bajo `C:\Users\t-he-\AndroidStudioProjects\BlackHawkMaintenance`.
- [ ] Conservar un snapshot previo en `C:\Users\t-he-\OneDrive\Desktop\BHM Codex\backups\` con fecha, lista de archivos y checksum del documento auditado.
- [ ] Registrar `versionCode`, certificado esperado, versiones de Room, AGP/Kotlin/KSP/Java, proyecto Firebase y configuración de publicación.
- [ ] Mantener `C:\Users\t-he-\OneDrive\Desktop\BHM Codex` como respaldo/recuperación; no usarlo para staging, compilaciones ni copias de trabajo.
- [ ] Mantener cambios no relacionados y documentar cualquier diferencia previa a la auditoría.
- [ ] Revisar `.gitignore` y excluir `keystore.properties`, `*.jks`, `*.keystore`, `google-services.json`, `app/release/`, APK/AAB, `mapping.txt`, `native-debug-symbols.zip`, `output-metadata.json`, `hs_err_pid*.log`, `replay_pid*.log`, `deps_*.txt`, `project_tree.txt`, `.kotlin/`, `.cxx/`, `.externalNativeBuild/` y `functions/node_modules/`.
- [ ] Ejecutar `git add --dry-run .` y revisar manualmente cada archivo propuesto antes del primer `git add`.
- [ ] Escanear el conjunto staged en busca de contraseñas, tokens, claves privadas, PII y documentos de inspección.
- [ ] Conservar la rama principal remota `master` y su historial mediante `fetch`; evitar cualquier reset destructivo.
- [ ] Configurar `origin` como `https://github.com/CabgonDev/BlackHawkMaintenance.git` y registrar el SHA remoto observado.
- [x] Crear el commit inicial con solo fuentes, configuración no secreta, índices necesarios y documentación (`b1823df`, publicado en `master`).
- [ ] Decidir si los PDFs ignorados por `app/src/main/assets/**/*.pdf` se distribuyen desde otro almacén o deben versionarse para conservar la experiencia offline; no quitar la exclusión sin revisar tamaño y licencias.

**Evidencia:** snapshot recuperable, salida de `git status --short --branch`, lista de archivos staged y SHA del commit. **Rollback:** restaurar el snapshot y eliminar únicamente el `.git` local si la selección contiene secretos.

## App v5.0.2 — Contención de firma y secretos

**Esfuerzo estimado:** 1–3 días más coordinación de la distribución y recuperación. **Commit sugerido:** `security(v5.0.2): move release signing to protected secrets`. **Gate:** APK firmado con la clave/certificado de continuidad, instalable sobre la versión 5.0.1 y sin credenciales en árbol, historial, nube pública ni artefactos.

- [ ] Definir quién custodia la clave privada de firma, quién puede generar releases y cómo se recupera desde un respaldo cifrado fuera del repositorio.
- [ ] Inventariar y sanear `keystore.properties`, `app/keystore/bhm.jks`, backups, APK, mapping, símbolos y el historial remoto; no publicar una clave privada en el canal local ni en la nube.
- [ ] Mantener el mismo certificado de firma para que Android acepte actualizaciones instaladas localmente o desde la nube.
- [ ] Si la clave está comprometida, documentar antes de rotarla un camino compatible de migración de firma y recuperación para las instalaciones existentes; no asumir que una nueva clave puede actualizar la instalación actual.
- [ ] Rotar contraseñas o credenciales expuestas y conservar protegida la clave que mantiene la continuidad de instalación.
- [ ] Migrar firma release a secretos protegidos de CI o un gestor de secretos.
- [ ] Hacer fallar el release si faltan `storeFile`, `storePassword`, `keyAlias` o `keyPassword`; nunca sustituirlos por valores vacíos.
- [ ] Verificar el certificado del APK con `apksigner` y probar instalación limpia y actualización directa desde 5.0.1 conservando datos.
- [ ] Auditar que ningún secreto vuelva a aparecer en la historia Git, artefactos, logs o respaldos publicados.

**Evidencia:** fingerprint SHA-256 del certificado, build release protegido, procedencia del APK y prueba de instalación/actualización. **Rollback:** recuperar la clave y contraseña desde el respaldo cifrado; si no se puede conservar la firma, detener la distribución hasta aprobar un plan de migración que no pierda datos.

## App v5.0.3 — Artefactos y reproducibilidad

**Esfuerzo estimado:** 1–2 días. **Commit sugerido:** `build(v5.0.3): make builds reproducible and clean`. **Gate:** un checkout limpio no contiene salidas generadas y reproduce `check` con el entorno documentado.

- [ ] Retirar del árbol versionable `app/release/app-release.apk`, `mapping.txt`, `native-debug-symbols.zip` y metadatos generados; conservarlos solo en el almacén protegido de artefactos.
- [ ] Ignorar `hs_err_pid*.log`, `replay_pid*.log`, `deps_debugRuntime.txt`, `deps_releaseRuntime.txt` y `project_tree.txt`.
- [ ] Revisar `hs_err_pid2840.log` para identificar la causa del OOM y documentar si requiere un ajuste.
- [ ] Ajustar `org.gradle.jvmargs`, R8 y memoria del entorno con una justificación reproducible.
- [ ] Añadir `distributionSha256Sum` en `gradle/wrapper/gradle-wrapper.properties`.
- [ ] Activar dependency verification, locks y generación de SBOM; documentar cómo se actualizan de forma segura.
- [ ] Alinear AGP, Kotlin, KSP, Room y Java toolchain con versiones compatibles y una prueba de build completa.
- [ ] Fijar la versión de Python de `build_fts_index.py` y de los scripts de índices.
- [ ] Revisar dependencias transitivas del visor PDF beta y retirar JitPack solo si PhotoView deja de requerirlo.
- [ ] Ejecutar `git clean -ndX` en un checkout temporal y comprobar que solo se eliminan generados ignorados.

**Evidencia:** build desde checkout limpio, SBOM, lockfiles, checksum del wrapper y explicación del OOM. **Rollback:** revertir el ajuste de toolchain o memoria conservando el lockfile anterior.

## App v5.1.0 — Assets, índices y contenido local

**Esfuerzo estimado:** 1–3 días, condicionado a disponibilidad del contenido. **Commit sugerido:** `content(v5.1.0): validate inspection assets and indexes`. **Gate:** no se publica una inspección vacía y las búsquedas locales tienen una ruta de recuperación verificable.

- [ ] Decidir con el responsable de producto si `app/src/main/assets/checklists/inspection_120h.json` y `inspection_480h.json` vacíos son intencionales; si no hay contenido válido, bloquear su publicación.
- [ ] Completar esos assets o marcar explícitamente los módulos como incompletos en la UI y documentación.
- [ ] Añadir `app/src/main/assets/generalidades/generalidades.json` o eliminar el fallback que apunta a un archivo inexistente en `GeneralitiesFragment.kt` y `GeneralitiesSectionFragment.kt`.
- [ ] Hacer que `build_fts_index.py` y scripts relacionados fallen si faltan PDFs, generan cero filas o reciben argumentos CLI no soportados.
- [ ] Eliminar `pause` de `build_all_indexes.bat`, `build_iads_index.bat` y `build_sikorsky_index.bat` para permitir CI no interactivo.
- [ ] Validar conteo, checksum y esquema de `sikorsky_parts_chunks.jsonl`, `sikorsky_parts_chunks_fts4.db` y `app/src/main/assets/index/sikorsky_parts_chunks_fts4.db`.
- [ ] Confirmar consumidores activos de los índices antes de borrar cualquier archivo residual.
- [ ] Corregir la columna `content` por `text` en el fallback `LIKE` de `app/src/main/java/com/cabgon/blackhawk/data/RAGIndex.kt`.
- [ ] Añadir una prueba que fuerce el fallback FTS y compruebe resultados reales sin ocultar la excepción.
- [ ] Definir el comportamiento offline de Frecuencias cuando no exista contenido OTA local y validar instalación limpia/actualización sin red.

**Evidencia:** manifest de assets, checksums, conteos, prueba FTS y APK limpio. **Rollback:** restaurar el asset anterior o bloquear la feature mediante una bandera de contenido.

## App v5.2.0 — Ciclo de vida, diálogos y estabilidad de UI

**Esfuerzo estimado:** 3–5 días. **Commit sugerido:** `fix(v5.2.0): harden lifecycle and recreatable UI flows`. **Gate:** rotación, muerte de proceso, back, picker, cámara y generación de PDF no producen crash, callback a vista destruida ni fuga observable.

- [ ] Cambiar `Fragment.lifecycleScope` por `viewLifecycleOwner.lifecycleScope` o `repeatOnLifecycle` en `GeneralitiesFragment.kt`, `GeneralitiesSectionFragment.kt`, `AdminGeneralities*`, `AdminFrequenciesFragment.kt`, `AdminAuditLogFragment.kt` y `AdminEnRutaFragment.kt`.
- [ ] Cancelar listeners, jobs y callbacks en `onDestroyView`; proteger callbacks de `app/src/main/java/com/cabgon/blackhawk/ui/gallery/GalleryUploadBottomSheet.kt`.
- [ ] Sustituir hilos manuales de PDF por coroutines cancelables en `app/src/main/java/com/cabgon/blackhawk/ui/fragments/PreflightChecklistFragment.kt`, `Inspection40hPdfExporter.kt`, `Inspection120hPdfExporter.kt` y `Inspection480hPdfExporter.kt`.
- [ ] Capturar excepciones de `.await()` en `app/src/main/java/com/cabgon/blackhawk/admin/generalities/AdminGeneralitiesRollbackFragment.kt` con estados visibles y logs seguros.
- [ ] Reemplazar catches vacíos por errores tipados, recuperación y mensajes accionables.
- [ ] Corregir el fallback abierto de red de `app/src/main/java/com/cabgon/blackhawk/ui/StartupActivity.kt` con timeout, estado desconocido y reintento.
- [ ] Convertir `CreateInspection40hDialogFragment.kt`, `CreateInspection120hDialogFragment.kt`, `CreateInspection480hDialogFragment.kt` y `PreflightNewDialog.kt` a constructor vacío, `arguments` serializables y `setFragmentResult`.
- [ ] Probar recreación de cada diálogo con rotación, cambio de fuente, modo oscuro y proceso eliminado.
- [ ] Garantizar `startForeground()` temprano y manejo de `intent == null`, cancelación y parámetros inválidos en `app/src/main/java/com/cabgon/blackhawk/ui/UpdateDownloadService.kt`.
- [ ] Implementar `onTimeout()` y `FOREGROUND_SERVICE_TYPE_DATA_SYNC` cuando corresponda; probar descarga larga, reinicio y timeout del sistema.
- [ ] Implementar `onCleared()` en `app/src/main/java/com/cabgon/blackhawk/ui/enruta/EnRutaViewModel.kt` para detener `SnapshotListener`.
- [ ] Llamar `translator.close()` en `app/src/main/java/com/cabgon/blackhawk/ui/fragments/AiQueryFragment.kt` y liberar todos los `Closeable` de SQLite, ML Kit y archivos en éxito, error y cancelación.
- [ ] Evitar dobles `popBackStack()` cuando la validación de red corre desde `onViewCreated()` y `onResume()`.

**Evidencia:** pruebas de recreación, logcat sin excepciones y prueba manual reproducible. **Rollback:** revertir cada extracción en commits pequeños, conservando los tests que detectaron el fallo.

## App v5.3.0 — Compatibilidad, accesibilidad, red y diagnóstico

**Esfuerzo estimado:** 3–5 días. **Commit sugerido:** `compat(v5.3.0): standardize insets accessibility and diagnostics`. **Gate:** lint limpio, smoke test en API 28 y API actual, y logs release sin secretos ni PII.

- [ ] Definir una política global de edge-to-edge para `targetSdk 37`, aplicar `WindowInsets` consistentemente y retirar usos incorrectos de `fitsSystemWindows`.
- [ ] Probar API 28 y Android actual con rotación, teclado, barras transparentes y modo inmersivo.
- [ ] Crear `app/src/main/res/xml/network_security_config.xml` con `cleartextTrafficPermitted="false"` y asociarlo al `application` del manifest.
- [ ] Verificar que todas las URLs externas, incluida WBParts, usan HTTPS, timeout y allowlist.
- [ ] Evaluar certificate pinning solo para endpoints bajo control del equipo, con estrategia de rotación y recuperación documentada.
- [ ] Implementar solicitud runtime de `POST_NOTIFICATIONS` en Android 13/API 33+, incluyendo rechazo, revocación y reinstalación sin bloquear el flujo principal.
- [ ] Mover textos, hints y content descriptions a `strings.xml`; añadir descripciones funcionales a iconos y controles.
- [ ] Ejecutar lint de accesibilidad y probar TalkBack, foco, contraste, tamaños dinámicos y orientación.
- [ ] Inventariar logging de producción; prohibir contraseñas, tokens completos, claves, PII y documentos de inspección.
- [ ] Crear un wrapper de logging con niveles y redacción; desactivar `Log.d`/`Log.v` en release y revisar breadcrumbs de Crashlytics.
- [ ] Mantener los tests existentes (`LoginBiometricStateTest`, `LoginFlowPolicyTest`, `RegisterViewModelTest`, `DownloadedFileCommitTest`, `DownloadProgressTrackerTest`) como gate de regresión.

**Evidencia:** `lint`, matriz API 28/actual, prueba de permisos, TalkBack y captura de logs redactados. **Rollback:** desactivar pinning o cambios visuales mediante un commit separado, sin reabrir cleartext en producción.

## App v5.4.0 — OTA y almacenamiento fiable

**Esfuerzo estimado:** 4–7 días. **Commit sugerido:** `security(v5.4.0): make content updates atomic and verifiable`. **Gate:** una descarga truncada, con hash incorrecto, ruta inválida, downgrade o red perdida no deja contenido corrupto ni borra datos válidos.

- Esta entrega cubre actualizaciones OTA de contenido y pruebas de regresión del OTA de APK ya existente; no crear un segundo servicio o mecanismo de actualización de la aplicación.
- [ ] Documentar el contrato actual de `app/src/main/java/com/cabgon/blackhawk/ai/update/AppUpdateManager.kt`: Firestore `ai_ota/channels`, campos `appApkVersionCode`, `appApkPath`, `appApkSha256`, `appApkBytes`, y prefijo Storage `ai/stable/apk/`.
- [ ] Añadir pruebas para metadatos ausentes o inválidos, path traversal, tamaño/hash incorrectos, APK truncado, package/version/certificado distintos, revalidación de descarga pendiente, cancelación y error del instalador.
- [ ] Confirmar que el cliente rechaza cualquier origen externo que evite el prefijo Storage estable; si `appApkUrl` se conserva como contrato, exigir HTTPS y allowlist explícita o retirarlo.
- [ ] Exigir SHA-256 y tamaño para AI, manuales, generalidades y frecuencias.
- [ ] Permitir únicamente prefijos Storage aprobados y rechazar `..`, separadores inesperados y rutas arbitrarias.
- [ ] Firmar o proteger los metadatos de actualización y aplicar anti-downgrade monotónico.
- [ ] Descargar a `.part`, verificar tamaño/hash, reemplazar atómicamente y conservar `.bak` para rollback.
- [ ] Completar rollback si la actualización se interrumpe y conservar las validaciones APK existentes de `app/src/main/java/com/cabgon/blackhawk/ai/update/AppUpdateManager.kt`.
- [ ] Reutilizar y ampliar `DownloadedFileCommit` y `DownloadedFileCommitTest` en vez de duplicar la lógica ya cubierta.
- [ ] En `app/src/main/java/com/cabgon/blackhawk/ui/gallery/GalleryRepository.kt`, no eliminar el documento Firestore si Storage falla.
- [ ] Implementar tombstone, reintento idempotente y limpieza verificable para archivos huérfanos.
- [ ] Hacer atómico el reemplazo de Frecuencias y Generalidades usando `.part`, `.bak` y recuperación.
- [ ] Probar hash incorrecto, archivo truncado, ruta inválida, downgrade, pérdida de red, permisos, archivo huérfano y eliminación parcial.

**Evidencia:** pruebas automatizadas de commit/rollback, matriz de fallos y estado final de Storage/Firestore. **Rollback:** restaurar `.bak` y repetir la operación con el mismo identificador idempotente.

## App v5.5.0 — Sesión, privacidad y vinculación de dispositivo

**Esfuerzo estimado:** 4–7 días más decisiones de política. **Commit sugerido:** `security(v5.5.0): harden session and device binding`. **Gate:** logout, cambio de usuario, biometría, proceso eliminado e intents externos no permiten acceso cruzado ni rutas privilegiadas.

- [ ] No confiar en `EXTRA_ACCESS_ALREADY_VALIDATED` ni extras equivalentes recibidos desde otra aplicación en `StartupActivity.kt`.
- [ ] Usar estado interno de sesión o permiso firmado para rutas privilegiadas.
- [ ] Decidir y documentar si el desbloqueo biométrico offline de 24 horas cumple política; añadir bloqueo por inactividad o al volver del background si corresponde.
- [ ] Escuchar cambios de `role` y `status`, invalidar cache y actualizar la UI de MainActivity.
- [ ] Evaluar `FLAG_SECURE` para login, Main, checklists y visor PDF, y cifrado de Room frente a root/forense.
- [ ] Restringir la API key Firebase por paquete y SHA-1/SHA-256.
- [ ] Elegir y probar un proveedor de App Check que acepte las instalaciones locales y desde nube; validar la atestación con APK instalado por cada canal antes de activar `enforceAppCheck` en Functions.
- [ ] Usar Play Integrity solo si las pruebas de APK sideload confirman que satisface la política; si no, seleccionar otro proveedor compatible y documentar su modelo de amenaza.
- [ ] Cambiar desafíos ECDSA a nonce aleatorio de un solo uso con TTL.
- [ ] Validar URLs externas en `app/src/main/java/com/cabgon/blackhawk/data/PartRepo.kt` mediante allowlist y limitar `pingAuth` a diagnóstico no sensible.
- [ ] Añadir pruebas unitarias para `DeviceBindingVerifier.kt`, `BiometricCredentialStore.kt`, `UserSessionStore.kt`, UID/status y rotación/invalidez de claves.
- [ ] Antes de los atajos de `StartupActivity`, leer `pending_auth_reset_version`; migrar solo instalaciones sin clave vinculada usando `AuthStateCleaner.clear(applicationContext, clearSavedEmail = false, clearBiometrics = false)`.
- [ ] Abrir `LoginActivity` con `EXTRA_REQUIRE_CREDENTIALS = true`, exigir la contraseña una vez y conservar email/biometría.
- [ ] Limpiar el marcador solo después de autenticación y `claimDevice` exitosos; nunca cerrar sesión de un dispositivo ya vinculado.
- [ ] Probar instalación cancelada/fallida, proceso eliminado, biometría, cambio de usuario e intents externos.

**Evidencia:** tests de sesión/dispositivo, matriz de procesos y registro del ADR. **Rollback:** conservar el marcador pendiente y restaurar la ruta anterior solo para instalaciones no vinculadas.

## App v5.6.0 — Room, migraciones y aislamiento multiusuario

**Esfuerzo estimado:** 5–8 días. **Commit sugerido:** `data(v5.6.0): preserve local data and isolate owners`. **Gate:** migraciones desde cada versión publicada conservan datos y dos cuentas consecutivas nunca comparten filas ni sincronizaciones pendientes.

- [ ] Eliminar `fallbackToDestructiveMigration(dropAllTables = true)` de `app/src/main/java/com/cabgon/blackhawk/data/db/AppDbProvider.kt` y `app/src/main/java/com/cabgon/blackhawk/data/DbProvider.kt`.
- [ ] Documentar versiones y esquemas de `app/src/main/java/com/cabgon/blackhawk/data/db/AppDatabase.kt` y `app/src/main/java/com/cabgon/blackhawk/data/preflight/PreflightDb.kt`.
- [ ] Crear migraciones explícitas para cada versión publicada, incluida 9→10, y confirmar si eliminar tablas de chat es intencional.
- [ ] Añadir `MigrationTestHelper` para cada versión publicada y probar inspecciones, preflight, usuarios y estados sincronizados.
- [ ] Definir recuperación/exportación si una migración falla.
- [ ] Añadir `ownerUid` a `app/src/main/java/com/cabgon/blackhawk/data/preflight/PreflightEntities.kt`.
- [ ] Filtrar por `ownerUid` en `PreflightDao.kt`, `PreflightRepository.kt` y `PreflightRemoteSync.kt`.
- [ ] Validar propietario antes de borrar documentos remotos y sincronizar únicamente filas del usuario actual.
- [ ] Limpiar o aislar la base local al cerrar sesión desde `app/src/main/java/com/cabgon/blackhawk/data/user/AuthStateCleaner.kt`.
- [ ] Probar dos cuentas consecutivas en el mismo dispositivo con datos pendientes, logout, login y reintentos de sincronización.
- [ ] Confirmar el uso real de las dos bases Room antes de retirar tablas, DAOs o entidades; documentar si se consolidan o permanecen separadas.

**Evidencia:** schemas, tests de migración por versión, prueba de dos cuentas y exportación de recuperación. **Rollback:** restaurar el snapshot de Room y detener la migración antes de borrar datos.

## App v5.7.0 — Firebase Rules, Functions y autorización server-side

**Esfuerzo estimado:** 5–10 días más acceso a Firebase Console. **Commit sugerido:** `security(v5.7.0): enforce server-side authorization contracts`. **Gate:** Emulator Suite, callable Android y Functions bloquean toda operación no autorizada.

- [ ] Obtener desde Firebase Console un snapshot fechado de Firestore Rules y Storage Rules solo si existe acceso; no inventar ni desplegar `firestore.rules`/`storage.rules` locales como sustituto.
- [ ] Exigir `request.auth.uid == uid` al crear documentos de usuario y bloquear cambios de `role`, `status`, `uid` y campos administrativos desde el cliente.
- [ ] Restringir inspecciones 40h/120h/480h por propietario o dataset compartido explícito; validar ownership en En Ruta, preflight, galería y documentos administrativos.
- [ ] Sustituir escrituras administrativas directas de `app/src/main/java/com/cabgon/blackhawk/ui/admin/users/UsersListFragment.kt` por Functions o claims server-side.
- [ ] Proteger approve/reject/delete de `app/src/main/java/com/cabgon/blackhawk/ui/gallery/GalleryRepository.kt`.
- [ ] Eliminar el fallback de `idToken` y el payload doble de `adminSuspendUser`; usar `context.auth`/request v2 y validar `targetUid`, motivo y estado del solicitante.
- [ ] Migrar `functions/index.js` a firma v2 `async (request) => { request.data; request.auth; request.rawRequest }` para `adminSuspendUser` y `pingAuth`.
- [ ] Usar `verifyIdToken(token, true)` en cualquier verificación manual que permanezca y retirar `pingAuth` si solo era diagnóstico.
- [ ] Probar callable desde SDK Android sin auth, con token expirado y revocado, y con payload inválido.
- [ ] Añadir Firebase Emulator Suite con casos permitidos y denegados para usuarios, inspecciones, preflight, galería y administración.
- [ ] Invalidar cache y UI cuando cambie `role`; documentar la evidencia de consola con fecha y proyecto.

**Evidencia:** snapshot cloud fechado, reglas/Functions en Emulator Suite, pruebas callable y logs sin datos sensibles. **Rollback:** revertir Functions mediante versión anterior y mantener el snapshot de consola para restauración controlada.

## App v5.8.0 — Memoria, rendimiento y límites de Firestore

**Esfuerzo estimado:** 5–8 días. **Commit sugerido:** `perf(v5.8.0): bound memory and batch workloads`. **Gate:** generación de PDF y cargas grandes funcionan en memoria baja, y ningún `WriteBatch` supera el límite de Firestore.

- [ ] Mover imágenes grandes de `app/src/main/res/drawable/` a `drawable-nodpi` o reducirlas a dimensiones de uso real/WebP.
- [ ] Ajustar `safeDecode()` en `app/src/main/java/com/cabgon/blackhawk/ui/pdf/BaseInspectionPdfExporter.kt` y `PdfExporter.kt` con `inSampleSize`, `inScaled=false` cuando corresponda y liberación segura.
- [ ] Medir heap y generación de PDF en dispositivos de memoria baja, con evidencia antes/después.
- [ ] Particionar WriteBatch de inspecciones 40h/120h/480h en bloques inferiores a 500 operaciones y reintentar fallos parciales.
- [ ] Probar creación y borrado con más de 500 ítems y reintentos parciales.
- [ ] Medir arranque, sincronización, descargas y generación PDF antes de optimizar.
- [ ] Revisar `UpdateDownloadService` con perfil de CPU/memoria para descargas largas y canceladas.
- [ ] Evitar `RecyclerView` dentro de `NestedScrollView` en `fragment_part_numbers.xml`, `fragment_en_ruta_detail.xml`, `fragment_admin_home.xml` y `fragment_admin_generality_edit.xml`.
- [ ] Probar listas con cientos de resultados, reciclaje, scroll y memoria.

**Evidencia:** perfiles de heap/CPU, prueba >500 ítems, tiempos de arranque y ausencia de OOM. **Rollback:** mantener la ruta de batch anterior detrás de una bandera si el nuevo particionado falla.

## App v5.9.0 — Mantenibilidad y contratos externos

**Esfuerzo estimado:** 1–2 semanas. **Commit sugerido:** `refactor(v5.9.0): isolate app flows and external contracts`. **Gate:** cada extracción conserva comportamiento, tiene pruebas y el contrato externo versionado.

- [ ] Extraer gradualmente lógica de `StartupActivity.kt` y `LoginActivity.kt` a ViewModels/use cases, separando migración de sesión, red, navegación y autenticación.
- [ ] Mantener cada extracción en un commit pequeño cubierto por pruebas; no hacer una reescritura masiva.
- [ ] Documentar base URL, endpoints, parámetros y esquemas de respuesta de WBParts.
- [ ] Versionar contratos HTML/JSON y añadir pruebas de compatibilidad para cambios de WBParts.
- [ ] Añadir pruebas de errores, timeouts y respuestas incompletas de WBParts.
- [ ] Reducir duplicación entre inspecciones 40h/120h/480h mediante componentes comunes cubiertos por pruebas.
- [ ] Eliminar clases y archivos sin referencias solo después de compilar y revisar reflection/R8.
- [ ] Confirmar y retirar dependencias sin uso como Volley o `converter-moshi` solo tras análisis de referencias y build completo.
- [ ] Revisar si `sikorsky_parts_chunks.jsonl` y `sikorsky_parts_chunks_fts4.db` tienen consumidores activos antes de eliminarlos.
- [ ] Documentar responsabilidades finales de las dos bases Room.

**Evidencia:** matriz de contratos, tests de compatibilidad, build con R8 y diff por extracción. **Rollback:** revertir el commit de extracción aislado sin mezclar cambios de seguridad o datos.

## App v5.10.0 — CI, variantes y liberación controlada

**Esfuerzo estimado:** 1–3 semanas. **Commit sugerido:** `release(v5.10.0): enforce CI and release gates`. **Gate:** todos los criterios finales de liberación están verdes y existe rollback documentado.

- [ ] Separar flavors `dev`, `staging` y `prod`.
- [ ] Usar `google-services.json` distinto por flavor y bloquear debug contra producción.
- [ ] Fijar dependencias de Functions y añadir tests, lint y predeploy.
- [ ] Crear CI con `check`, lint, tests, build release y validación de firma.
- [ ] Ejecutar matriz de API mínima (28) y API actual, incluyendo smoke test de login, sesión, inspecciones, sincronización, OTA y permisos.
- [ ] Generar un APK universal firmado para instalación local y publicación en el prefijo Firebase Storage consumido por el OTA existente; si el tamaño exige APK divididos, documentar el conjunto y verificar que el flujo actual pueda instalarlos antes de adoptarlos.
- [ ] Mantener manuales necesarios offline y probar instalación limpia, actualización y acceso sin red.
- [ ] Definir responsables y permisos para publicar el APK en `ai/stable/apk/` y editar el documento Firestore `ai_ota/channels`; registrar publicación, checksum SHA-256 y procedimiento de revocación del artefacto.
- [ ] Incluir en el gate del APK release pruebas que confirmen la validación ya implementada en `AppUpdateManager`: versión y tamaño esperados, SHA-256, package name y certificado coincidente con la app instalada antes de abrir el instalador.
- [ ] Probar el flujo OTA existente de extremo a extremo desde Firebase Storage, además de la instalación local manual: actualización sobre 5.0.1, autorización del instalador del sistema para la app de origen, cancelación y recuperación sin pérdida de datos.
- [ ] Si se conserva un canal externo mediante `appApkUrl`, exigir HTTPS, allowlist, timeout y validación equivalente; no permitir que omita los controles del flujo Storage.
- [ ] Subir mapping/symbols a un almacén protegido y limitar acceso al equipo autorizado.
- [ ] Evaluar Crashlytics y Performance Monitoring con redacción de PII, separación dev/staging/prod, retención y consentimiento documentados.
- [ ] Corregir todos los errores de lint antes de suprimir reglas.
- [ ] Revisar logs antes de publicarlos y documentar el entorno exacto de compilación.
- [ ] Registrar fecha, comando, versión, artefacto, certificado, plan de rollback y responsable de cada release.

**Evidencia:** pipeline verde, APK firmado y verificable, smoke tests API 28/actual y por canal de distribución, reportes de lint/tests/check, mapping protegido y plan de rollback probado.

## Cierre obligatorio de cada versión

- [ ] Cambio implementado y limitado al alcance de la versión.
- [ ] Prueba automatizada o evidencia manual reproducible.
- [ ] Archivos y líneas afectadas documentados.
- [ ] `check`, `lint`, tests y, cuando aplique, release build ejecutados con resultado registrado.
- [ ] Revisión de seguridad para autenticación, Firebase, firma u OTA.
- [ ] Snapshot de recuperación y rollback disponible.
- [ ] Commit creado con el mensaje de la versión y tag si el gate se cerró.
- [ ] No se abre la siguiente versión con secretos, migraciones destructivas o pruebas pendientes.

## Criterio final de liberación

- [ ] No hay secretos en código, historia, backups publicados ni artefactos distribuidos.
- [ ] Migraciones Room probadas desde cada versión publicada y sin borrado destructivo accidental.
- [ ] Aislamiento entre cuentas validado con datos pendientes.
- [ ] Firebase Rules y Functions probadas mediante Emulator Suite y evidencia cloud fechada.
- [ ] OTA exige hash/tamaño, rutas permitidas, anti-downgrade y rollback.
- [ ] `lint`, tests, `check` y release build están verdes.
- [ ] Smoke test en API 28 y Android actual completado.
- [ ] APK firmado con el certificado esperado, hash validado y origen de distribución autorizado.
- [ ] Plan de rollback y recuperación documentado y probado.

## Mapa de cobertura del documento fuente

| Secciones de `AUDIT_CHECKLIST.md` | Entregas |
|---|---|
| Fase 0, P0 firma y secretos | Baseline v5.0.1, siguiente release v5.0.2 |
| Persistencia, aislamiento entre cuentas | v5.6.0 |
| Autorización Firebase y Backend/Functions | v5.7.0 |
| OTA, galería y almacenamiento | v5.4.0 |
| Ciclo de vida, DialogFragments, foreground service | v5.2.0 |
| Sesión, privacidad e intents | v5.5.0 |
| Edge-to-edge, red, permisos, logs y accesibilidad | v5.3.0 |
| Pruebas obligatorias | v5.2.0–v5.10.0, según el flujo probado |
| Build, dependencias, suministro y artefactos | v5.0.3, v5.10.0 |
| Assets, índices y contenido | v5.1.0 |
| Memoria, Firestore y rendimiento UI | v5.8.0 |
| Mantenibilidad, contratos y tamaño del APK | v5.9.0, v5.10.0 |
