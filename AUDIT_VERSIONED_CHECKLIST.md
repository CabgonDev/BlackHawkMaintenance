# Checklist versionado de remediación — BlackHawk Maintenance

Este es el checklist único de remediación de BlackHawk Maintenance. Organiza los hallazgos de auditoría en entregas que se pueden revisar, probar y publicar una por una, desde la versión actual 5.0.1. El orden va de menor complejidad y tiempo a mayor riesgo, coordinación y duración.

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

- [x] Confirmar que todo destino de edición está bajo `C:\Users\t-he-\AndroidStudioProjects\BlackHawkMaintenance`.
  - **Evidencia (archivos y líneas):** `AUDIT_VERSIONED_CHECKLIST.md:38-41` (registro documental); raíz del repositorio confirmada en `C:\Users\t-he-\AndroidStudioProjects\BlackHawkMaintenance`.
  - **Comando y resultado:** `Get-Location` → `C:\Users\t-he-\AndroidStudioProjects\BlackHawkMaintenance`; `git rev-parse --show-toplevel` → `C:/Users/t-he-/AndroidStudioProjects/BlackHawkMaintenance` (exit `0`).
  - **Versión / artefacto / rollback:** App `5.0.1 (Hawk)` (`versionCode` `501`); verificación exclusivamente documental (sin artefacto generado). **Rollback:** revertir esta casilla en `AUDIT_VERSIONED_CHECKLIST.md`.
- [x] Conservar un snapshot previo en `C:\Users\t-he-\OneDrive\Desktop\BHM Codex\backups\` con fecha, lista de archivos y checksum del documento auditado.
  - **Evidencia (archivos y líneas):** `AUDIT_VERSIONED_CHECKLIST.md:42-45` (registro documental); snapshot externo creado en `C:\Users\t-he-\OneDrive\Desktop\BHM Codex\backups\baseline-v5.0.1-audit-20261118-182809\` (`AUDIT_VERSIONED_CHECKLIST.md`, `repo-tracked-files.txt`, `snapshot-files.txt`, `snapshot-metadata.txt`, `SHA256SUMS.txt`); además se conservan intactos 5 snapshots previos del `2026-11-16` (`pre-audit-roadmap-20261116-212625`, `pre-version-baseline-update-20261116-215357`, `pre-local-cloud-distribution-20261116-220342`, `pre-audit-distribution-correction-20261116-220826`, `pre-single-audit-checklist-20261116-221537`).
  - **Comando y resultado:** Snapshot fechado `2026-11-18T18:28:09-06:00` (`2026-11-19T00:28:09Z`) sobre `HEAD` `43f6943` con `Get-FileHash -Algorithm SHA256` verificado idéntico entre origen y copia: `AUDIT_VERSIONED_CHECKLIST.md` (`33,371` B) = `40474014459C5146130C6BB0D348FCA14C87751BA56BFE837B314BC8B7AF631B`; `repo-tracked-files.txt` (`536` archivos trackeados) = `9EEE68B5011E6ECA6D0A54CCE793B3AA11AC928411FF16E2E3209C16F49B9AFE` (exit `0`).
  - **Versión / artefacto / rollback:** App `5.0.1 (Hawk)` (`versionCode` `501`); artefacto de respaldo recuperable en `baseline-v5.0.1-audit-20261118-182809\`. **Rollback:** restaurar `AUDIT_VERSIONED_CHECKLIST.md` desde dicho snapshot comprobando su SHA-256 en `SHA256SUMS.txt`.
- [x] Registrar `versionCode`, certificado esperado, versiones de Room, AGP/Kotlin/KSP/Java, proyecto Firebase y configuración de publicación.
  - **Línea base registrada:** `applicationId`/`namespace` `com.cabgon.blackhawk`, `versionCode` `501`, `versionName` `5.0.1 (Hawk)`, `minSdk`/`targetSdk`/`compileSdk` `28`/`37`/`37`; certificado público esperado del APK release: DN `C=MX, ST=Mexico, L=Mexico, O=Cabgon, OU=Cabgon, CN=Christian Cabrera`, SHA-256 `024db26301133f4ac627a26f89169d11d0000ca535e631e1d9965ee3c8ec1b28`, SHA-1 `92f6f9250403786e4a84071ee43cecb574f41f67`, MD5 `ccf7b9fe857072c29f6948f422a4c1e6` (esquema APK v2/v3; SHA-1 corregido tras verificar ambos APK con `apksigner`); Room `2.8.5` (`AppDatabase` v10 con schemas `1,3..10` — hallazgo: falta `2.json` — y `PreflightDb` v1 con schema `1.json`), AGP `9.4.1`, Kotlin `2.4.20`, KSP `2.3.12`, Gradle Wrapper `9.8.0` (sin `distributionSha256Sum`), Java host OpenJDK Temurin `21.0.9+10-LTS` y JVM target `17`; Firebase proyecto `blackhawk-maintenance`, BoM `34.19.0`, `google-services` `4.5.0`, `functions` Node `20` (`firebase-admin ^13.6.0`, `firebase-functions ^7.0.0`; `firestore.rules` y `storage.rules` referenciados en `firebase.json` pero administrados en consola y ausentes localmente); publicación fuera de Play Store con OTA en canal `stable` (metadatos en Firestore `ai_ota/channels` y descarga de APK desde Firebase Storage `ai/stable/apk/` con verificación de tamaño, SHA-256, `packageName`, `longVersionCode` y certificado) y firma release vía `keystore.properties` + `app/keystore/bhm.jks` locales ignorados (sin exponer secretos; hallazgo para v5.0.2: `signingConfigs.release` no falla explícitamente si faltan propiedades).
  - **Evidencia (archivos y líneas):** `AUDIT_VERSIONED_CHECKLIST.md:46-50`; `app/build.gradle.kts:11-25,38-81,99-108`; `settings.gradle.kts:18-27`; `gradle/libs.versions.toml:2-8,21,80-84`; `gradle/wrapper/gradle-wrapper.properties:4`; `.firebaserc:1-5`; `firebase.json:1-23`; `functions/package.json:11-18`; `app/src/main/java/com/cabgon/blackhawk/data/db/AppDatabase.kt:27-53`; `app/src/main/java/com/cabgon/blackhawk/data/preflight/PreflightDb.kt:6-16`; `app/src/main/java/com/cabgon/blackhawk/ai/update/AppUpdateManager.kt:32-386`; `app/src/main/java/com/cabgon/blackhawk/ai/update/AiOtaChannelRepository.kt:35-74`; `app/release/output-metadata.json:14-15`.
  - **Comando y resultado:** `java -version` → Temurin `21.0.9+10-LTS` (exit `0`); `apksigner.bat verify --print-certs app\release\app-release.apk` → DN y digests registrados (exit `0`; `keytool -printcert -jarfile` reportó sin firma JAR v1 por esquema v2/v3); verificación de claves sin valores en `keystore.properties` confirmó `storeFile`, `storePassword`, `keyAlias` y `keyPassword` presentes y no vacías (exit `0`).
  - **Versión / artefacto / rollback:** App `5.0.1 (Hawk)` (`versionCode` `501`); artefacto observado en disco `app/release/app-release.apk` (no regenerado); cambio solo documental. **Rollback:** revertir esta casilla en `AUDIT_VERSIONED_CHECKLIST.md`.
- [x] Mantener `C:\Users\t-he-\OneDrive\Desktop\BHM Codex` como respaldo/recuperación; no usarlo para staging, compilaciones ni copias de trabajo.
  - **Evidencia (archivos y líneas):** `AUDIT_VERSIONED_CHECKLIST.md:51-54` (registro documental); directorio externo `C:\Users\t-he-\OneDrive\Desktop\BHM Codex` verificado sin `.git`, sin `build/`, sin staging ni archivos fuente.
  - **Comando y resultado:** `Get-ChildItem -Path "C:\Users\t-he-\OneDrive\Desktop\BHM Codex" -Force` → únicamente `backups/` y `MODIFICACIONES_HASTA_2026-10-07.md` (`4,659` B) (exit `0`).
  - **Versión / artefacto / rollback:** App `5.0.1 (Hawk)` (`versionCode` `501`); sin artefacto generado en el repositorio. **Rollback:** revertir esta casilla en `AUDIT_VERSIONED_CHECKLIST.md`.
- [x] Mantener cambios no relacionados y documentar cualquier diferencia previa a la auditoría.
  - **Diferencias y bloqueos preexistentes documentados:** El árbol de trabajo estaba limpio al inicio (`43f6943`) y sin cambios no relacionados tras `87ab6d4`. Se documentan sin ocultar los siguientes hallazgos previos de línea base: (1) **bloqueo de gate de calidad en `lintDebug`**: `.\gradlew.bat check lint test --no-daemon` falla con **2,031 errores y 1,167 advertencias** (`2,030` `[RestrictedApi]` en 8 archivos generados de Room/KSP expuestos por `app/build.gradle.kts:27-36` + `1` error real `[UseAppTint]` en `app/src/main/res/layout/item_preflight_checklist.xml:36`, más aviso de features deprecadas para Gradle 10; `testReleaseUnitTest` no llegó a ejecutarse); (2) permanecen en disco local (ignorados por Git) `keystore.properties`, `app/keystore/bhm.jks`, `app/google-services.json`, `local.properties`, `app/release/*`, `hs_err_pid2840.log`, `replay_pid2840.log`, `deps_*.txt`, `gradle_projects.txt`, `project_tree.txt` y `/package-lock.json`; (3) diferencias de configuración menores: comentario desactualizado `2.3.20` en `settings.gradle.kts:21`, ausencia de `app/schemas/com.cabgon.blackhawk.data.db.AppDatabase/2.json`, ausencia de `distributionSha256Sum` en wrapper y tags remotos heredados `v236`/`v237`.
  - **Evidencia (archivos y líneas):** `AUDIT_VERSIONED_CHECKLIST.md:55-59`; `app/build.gradle.kts:27-36`; `app/src/main/res/layout/item_preflight_checklist.xml:36`; `settings.gradle.kts:21`; `gradle/wrapper/gradle-wrapper.properties:4`; `app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt`; `app/build/test-results/testDebugUnitTest/TEST-*.xml`.
  - **Comando y resultado:** `git status --short --branch` (`## master...origin/master` en `43f6943`, `## master...origin/master [ahead 1]` tras `87ab6d4`) y `git diff --stat` vacío; `.\gradlew.bat check lint test --no-daemon` → `BUILD FAILED in 19s` (exit `1`; 42 tareas: 1 ejecutada `:app:lintDebug` FAILED, 41 `UP-TO-DATE`; `:app:testDebugUnitTest` con **26/26** pruebas en verde: `LoginBiometricStateTest` 4, `LoginFlowPolicyTest` 11, `RegisterViewModelTest` 5, `DownloadedFileCommitTest` 3, `DownloadProgressTrackerTest` 3).
  - **Versión / artefacto / rollback:** App `5.0.1 (Hawk)` (`versionCode` `501`); reportes de diagnóstico en `app/build/` sin modificar código ni lint. **Rollback:** revertir esta casilla en `AUDIT_VERSIONED_CHECKLIST.md`.
- [x] Revisar `.gitignore` y excluir `keystore.properties`, `*.jks`, `*.keystore`, `google-services.json`, `app/release/`, APK/AAB, `mapping.txt`, `native-debug-symbols.zip`, `output-metadata.json`, `hs_err_pid*.log`, `replay_pid*.log`, `deps_*.txt`, `project_tree.txt`, `.kotlin/`, `.cxx/`, `.externalNativeBuild/` y `functions/node_modules/`.
  - **Evidencia (archivos y líneas):** `AUDIT_VERSIONED_CHECKLIST.md:60-63`; `.gitignore:8-48` (`/app/release/` L8, `**/*.apk` L9, `**/*.aab` L10, `**/mapping.txt` L11, `**/native-debug-symbols.zip` L12, `**/output-metadata.json` L13, `keystore.properties` L16, `*.jks` L17, `*.keystore` L18, `app/google-services.json` y `**/google-services.json` L21–22, `.kotlin/` L26, `**/.cxx/` L29, `**/.externalNativeBuild/` L30, `hs_err_pid*.log` L33, `replay_pid*.log` L34, `deps_*.txt` L35, `project_tree.txt` L36, `functions/node_modules/` L41); `functions/.gitignore:1` (`node_modules/`).
  - **Comando y resultado:** `git check-ignore -v` ejecutado sobre 17 rutas representativas de todos los patrones exigidos → `17/17` ignoradas (exit `0`); cruce contra `git ls-files` → `0` archivos sensibles o generados trackeados en `HEAD` (los presentes en disco figuran `tracked=False, ignored=True`).
  - **Versión / artefacto / rollback:** App `5.0.1 (Hawk)` (`versionCode` `501`); `.gitignore` ya cumplía sin modificaciones; cambio solo documental. **Rollback:** revertir esta casilla en `AUDIT_VERSIONED_CHECKLIST.md`.
- [x] Ejecutar `git add --dry-run .` y revisar manualmente cada archivo propuesto antes del primer `git add`.
  - **Evidencia (archivos y líneas):** `AUDIT_VERSIONED_CHECKLIST.md:64-67`; revisión manual de `git add --dry-run .`, de los `536` archivos trackeados en `43f6943` (`repo-tracked-files.txt`) y de los `537` archivos del commit bootstrap `b1823df`.
  - **Comando y resultado:** `git add --dry-run .` → exit `0`, salida vacía (`0` archivos propuestos; no se ejecutó `git add` real ni se propusieron secretos ni artefactos generados); `git ls-tree -r --name-only b1823df` → `537` entradas verificadas sin secretos.
  - **Versión / artefacto / rollback:** App `5.0.1 (Hawk)` (`versionCode` `501`); sin artefacto generado. **Rollback:** revertir esta casilla en `AUDIT_VERSIONED_CHECKLIST.md`.
- [x] Escanear el conjunto staged en busca de contraseñas, tokens, claves privadas, PII y documentos de inspección.
  - **Evidencia (archivos y líneas):** `AUDIT_VERSIONED_CHECKLIST.md:68-72`; conjunto staged vacío antes de edición y `536` archivos trackeados en `HEAD` revisados (`app/build.gradle.kts:55,57` solo lecturas de propiedades; `app/src/main/res/values/strings.xml:59` placeholder `tecnico@tucorreo.com`; `LoginFlowPolicyTest.kt` y `RegisterViewModelTest.kt` correos sintéticos de prueba; `app/src/main/assets/checklists/{preflight,inspection_40h,inspection_120h,inspection_480h}.json` solo plantillas).
  - **Comando y resultado:** `git diff --cached --name-status` (vacío antes de editar); escaneo regex de claves privadas, tokens, API keys, contraseñas y PII sobre `git ls-files` → `0` secretos/credenciales hardcodeadas ni PII personal real (exit `0`); `git log --all --name-status` sobre historial completo de `master` (exit `0`).
  - **Hallazgos históricos y notas (no ocultados):** (1) `app/google-services.json` permanece en commits históricos de `master` previos a `b1823df` (`ba13147` agregado, `6f2e4bc` modificado, `d3e0092` borrado), junto con manuales PDF (`app/src/main/assets/{iads,sikorsky}/manuals/*.pdf`) y artefactos `app/.cxx` en `ba13147..d3e0092` — no se detectaron `keystore.properties`, `*.jks`, `*.keystore`, `*.apk` ni `mapping.txt` en el historial; la evaluación/mitigación histórica corresponde a **App v5.0.2**; (2) `app/src/main/assets/checklists/preflight.json:4-6` incluye catálogo operativo de matrículas (`1091..1098`), grados y especialidades (plantilla, no inspecciones llenadas) y `inspection_120h.json`/`inspection_480h.json` tienen `sections: []` (pendiente en **App v5.1.0**).
  - **Versión / artefacto / rollback:** App `5.0.1 (Hawk)` (`versionCode` `501`); escaneo de solo lectura sin artefacto generado. **Rollback:** revertir esta casilla en `AUDIT_VERSIONED_CHECKLIST.md`.
- [x] Conservar la rama principal remota `master` y su historial mediante `fetch`; evitar cualquier reset destructivo.
  - **Evidencia (archivos y líneas):** `AUDIT_VERSIONED_CHECKLIST.md:73-76`; referencias Git `master` y `remotes/origin/master` preservadas intactas sin comandos destructivos (`reset`, `clean`, `checkout`, `stash`).
  - **Comando y resultado:** `git branch -a -vv` → `* master 87ab6d4 [origin/master: ahead 1]`, `remotes/origin/master 43f6943`; `git rev-list --count origin/master` → `26` (22 commits históricos `ba13147..1fd85e0` + 4 commits de auditoría `b1823df..43f6943`), `git rev-list --count master` → `27` (incluye `87ab6d4`); `git fetch --dry-run origin` → exit `0` confirmando `origin/master` alineado y reportando únicamente tags remotos heredados `v236` y `v237` aún no traídos localmente.
  - **Versión / artefacto / rollback:** App `5.0.1 (Hawk)` (`versionCode` `501`); sin artefacto generado. **Rollback:** revertir esta casilla en `AUDIT_VERSIONED_CHECKLIST.md` (el historial Git local y remoto permanece íntegro).
- [x] Configurar `origin` como `https://github.com/CabgonDev/BlackHawkMaintenance.git` y registrar el SHA remoto observado.
  - **Evidencia (archivos y líneas):** `AUDIT_VERSIONED_CHECKLIST.md:77-80`; configuración remota `origin` apuntando a `https://github.com/CabgonDev/BlackHawkMaintenance.git`.
  - **Comando y resultado:** `git remote -v` → `origin https://github.com/CabgonDev/BlackHawkMaintenance.git` (`fetch` y `push`); `git ls-remote origin` → exit `0` con `HEAD` y `refs/heads/master` = `43f694370be970551ed1455fb78db99b9e6626ac`, `refs/tags/v0.0.1` = `d83acc26cfbb4aa6e2a82a1142e0939d4e5109c0` (`b1823df881759ea35c749c6024a85fe06c24b6fe`), y tags remotos heredados `refs/tags/v236` y `refs/tags/v237` = `006fe1d76bbf057556184982a45ba1da2f64d30f` (nota: `master` local está en `87ab6d49899aff7f3273d4c35ebd52ca3b0b2d95`, `[ahead 1]` sin `push`).
  - **Versión / artefacto / rollback:** App `5.0.1 (Hawk)` (`versionCode` `501`); sin artefacto generado ni `push`. **Rollback:** revertir esta casilla en `AUDIT_VERSIONED_CHECKLIST.md`.
- [x] Crear el commit inicial con solo fuentes, configuración no secreta, índices necesarios y documentación (`b1823df`, publicado en `master`).
- [x] Decidir si los PDFs ignorados por `app/src/main/assets/**/*.pdf` se distribuyen desde otro almacén o deben versionarse para conservar la experiencia offline; no quitar la exclusión sin revisar tamaño y licencias.
  - **Decisión aprobada (App v5.0.1 / `versionCode` 501):** Se mantiene la exclusión `app/src/main/assets/**/*.pdf` en `.gitignore:43`. Los PDFs no se versionan ni se empaquetan dentro del APK y no existen PDFs actuales bajo `app/src/main/assets/`. En el código cliente observado, los manuales (y sus manifests) se descargan desde Firebase Storage mediante `ManualUpdateManager`. `StartupActivity` comprueba/descarga el contenido durante el flujo de arranque posterior a la autenticación, y `PdfViewerActivity` descarga bajo demanda si el archivo no está en caché. El modo offline funciona únicamente para manuales ya descargados en caché local.
  - **Evidencia (archivos y líneas):** `AUDIT_VERSIONED_CHECKLIST.md:82-86` (registro documental); `.gitignore:43`; `app/src/main/java/com/cabgon/blackhawk/manuals/update/ManualUpdateManager.kt:31-36,82-137,146-393` (manifests y descarga de PDFs desde `FirebaseStorage.getInstance().reference`; error controlado si no hay internet y falta caché en `:355-359`); `app/src/main/java/com/cabgon/blackhawk/ui/StartupActivity.kt:168-270,400-518,681-821,890-902,998-1011,1067-1084` (flujo post-autenticación que invoca `ManualUpdateManager.checkAndUpdateForPackage` y modo offline limitado a contenido en caché); `app/src/main/java/com/cabgon/blackhawk/ui/pdf/PdfViewerActivity.kt:100-109,335-388` (`openPdfSmart` abre desde caché y `downloadToCacheThenOpen` descarga bajo demanda con `ManualUpdateManager.ensureManualCached`).
  - **Comando y resultado:** `(Get-ChildItem -Path app\src\main\assets -Recurse -Filter "*.pdf" -ErrorAction SilentlyContinue | Measure-Object).Count` → `0` (conteo actual de PDFs en `app/src/main/assets/`: `0`).
  - **Versión / artefacto / rollback:** Versión observada `5.0.1 (Hawk)` (`versionCode` `501`); cambio exclusivamente documental (sin artefacto APK generado ni cambio de versión). **Rollback:** revertir la modificación de esta casilla en `AUDIT_VERSIONED_CHECKLIST.md` o restaurar desde el snapshot `C:\Users\t-he-\OneDrive\Desktop\BHM Codex\backups\baseline-v5.0.1-audit-20261118-182809`.

**Evidencia:** snapshot recuperable, salida de `git status --short --branch`, lista de archivos staged y SHA del commit. **Rollback:** restaurar el snapshot y eliminar únicamente el `.git` local si la selección contiene secretos.

## App v5.0.2 — Contención de firma y secretos

**Esfuerzo estimado:** 1–3 días más coordinación de la distribución y recuperación. **Commit sugerido:** `security(v5.0.2): move release signing to protected secrets`. **Gate:** APK firmado con la clave/certificado de continuidad, instalable sobre la versión 5.0.1 y sin credenciales en árbol, historial, nube pública ni artefactos.

- [x] Definir quién custodia la clave privada de firma, quién puede generar releases y cómo se recupera desde un respaldo cifrado fuera del repositorio.
  - **Evidencia (custodia y recuperación):** `docs/adr/0002-ci-and-historical-google-services.md:1-68` y `docs/adr/0003-signing-recovery-and-compromise-migration.md:1-73` registran a `CabgonDev` como custodio operativo y revisor requerido del entorno GitHub `protected-release`, limitado a `master`; el workflow materializa secretos bajo `$RUNNER_TEMP` con limpieza `always()`. El respaldo `C:\Users\t-he-\OneDrive\Desktop\BHM Codex\backups\final-v5.0.2-recovery-20261119-112052` contiene solamente `*.dpapi`, metadata, checksums y `recovery.ps1`; no contiene `keystore.properties` ni `bhm.jks` en claro.
  - **Comando y resultado:** `recovery.ps1` ejecutado como el mismo usuario Windows → round-trip DPAPI verificado para `keystore.properties` (`e8e17190ef38876f17634dad8398f793803a94d25f1ba99be02b433861ba8737`) y `bhm.jks` (`ba0c9eb959a74e9d21bbfb8efc87ce26f26825b1c641ef9929f0c341677439b3`); el directorio temporal de recuperación fue eliminado en `finally`.
  - **Versión / rollback:** App `5.0.2 (Hawk)` (`versionCode` `502`); recuperación probada fuera del checkout. **Rollback:** ejecutar el procedimiento DPAPI y montar los archivos solo bajo `$RUNNER_TEMP` o un directorio protegido equivalente.
- [x] Inventariar y sanear `keystore.properties`, `app/keystore/bhm.jks`, backups, APK, mapping, símbolos y el historial remoto; no publicar una clave privada en el canal local ni en la nube.
  - **Evidencia de inventario:** Inventario local sin valores sensibles: `keystore.properties` (87 B), `app/keystore/bhm.jks` (2,692 B), `app/google-services.json` (722 B), APK release previo (101,767,842 B), `mapping.txt` (84,974,345 B), símbolos nativos (27,124,697 B) y APK 5.0.2 generado bajo `app/build/outputs/apk/release/`; todos permanecen ignorados y no trackeados. El historial remoto contiene `app/google-services.json` en `ba13147`, `6f2e4bc` y `d3e0092`; no se detectaron keystores, APKs ni mappings en esos objetos.
  - **Evidencia de saneamiento final:** el inventario local y la historia Git no contienen `keystore.properties`, `*.jks`, `*.keystore`, APK, mapping ni símbolos trackeados; el snapshot antiguo `pre-audit-roadmap-20261116-212625` fue saneado: sus copias de firma/Google Services en claro se eliminaron tras preservar ciphertext DPAPI en `legacy-sensitive-quarantine-20261119-112434`. El historial conserva únicamente referencias históricas a `app/google-services.json`; no contiene claves privadas.
  - **Comando y resultado:** auditoría `git log --all --name-only` → 3 referencias históricas a `app/google-services.json` y 0 nombres de keystore/APK/mapping/símbolos; `git grep` sobre todos los commits → 0 claves privadas y coincidencias de API key únicamente en esos JSON históricos; auditoría recursiva de backups → 0 archivos de credenciales en claro y 5 archivos DPAPI cifrados. **Rollback:** restaurar el snapshot sanitizado o descifrar únicamente el quarantine DPAPI como usuario autorizado.
- [x] Mantener el mismo certificado de firma para que Android acepte actualizaciones instaladas localmente o desde la nube.
  - **Evidencia (archivos y líneas):** `app/build.gradle.kts:11-81` conserva la configuración release y exige el archivo protegido; `app/build/outputs/apk/release/app-release.apk` fue generado con `versionCode` `502` y el certificado público esperado.
  - **Comando y resultado:** `apksigner.bat verify --print-certs app\\build\\outputs\\apk\\release\\app-release.apk` → DN esperado y SHA-256 `024db26301133f4ac627a26f89169d11d0000ca535e631e1d9965ee3c8ec1b28`; `adb install -r` sobre el dispositivo con `5.0.1` → `Success`; después `dumpsys package` → `versionCode=502`, `versionName=5.0.2 (Hawk)` y el mismo `dataDir=/data/user/0/com.cabgon.blackhawk`.
  - **Versión / artefacto / rollback:** App `5.0.2 (Hawk)` (`versionCode` `502`); APK release protegido generado en `app/build/outputs/apk/release/app-release.apk`. **Rollback:** reinstalar el APK firmado `5.0.1` solo mediante el procedimiento de distribución autorizado, conservando la clave de continuidad.
- [x] Si la clave está comprometida, documentar antes de rotarla un camino compatible de migración de firma y recuperación para las instalaciones existentes; no asumir que una nueva clave puede actualizar la instalación actual.
  - **Evidencia (política):** `docs/adr/0003-signing-recovery-and-compromise-migration.md:1-73` documenta congelamiento del release, recuperación DPAPI, continuidad con el certificado actual, creación de `SigningCertificateLineage` mediante `apksigner rotate --set-installed-data true`, pruebas API 28/actual, migración de datos y revocación solo después de cumplir el gate de adopción. La clave actual no está declarada comprometida, por lo que no se ejecuta una rotación de firma APK en v5.0.2.
- [x] Rotar contraseñas o credenciales expuestas y conservar protegida la clave que mantiene la continuidad de instalación.
  - **Evidencia (cloud y archivos):** la clave histórica `projects/1071542595622/locations/global/keys/b6acd7f0-c917-4c95-8e1c-343a03acde8e` quedó restringida a `com.cabgon.blackhawk` + SHA-1 `92f6f9250403786e4a84071ee43cecb574f41f67`; se creó la clave rotada `projects/1071542595622/locations/global/keys/4ce05da4-a559-47ef-b95c-125ed7629702` con las mismas restricciones, se actualizó el `app/google-services.json` ignorado y el secreto `GOOGLE_SERVICES_JSON_BASE64`. El recurso anterior permanece restringido para rollback durante la adopción de 5.0.2.
  - **Comando y resultado:** API Keys `PATCH`/operación `operations/akmf.p10-1071542595622-8c4973a3-e219-4ff2-a09c-a1bdee7f5878` → restricción aplicada; `getKeyString` de la nueva clave coincidió con el JSON local; build release local `BUILD SUCCESSFUL in 3m 12s`; run final [44579250096](https://github.com/CabgonDev/BlackHawkMaintenance/actions/runs/44579250096) sobre `f1d9ab6` → `success`, certificado esperado y hash `b13a1ca19f4716ad3e25aabbbf94e7fe933088cac4fb6804e37b3e2782a3b9c0`.
  - **Control posterior a la entrega:** revocar/eliminar la clave anterior únicamente después de confirmar la migración de las instalaciones 5.0.1; durante v5.0.2 permanece restringida para rollback y no bloquea la entrega.
- [x] Migrar firma release a secretos protegidos de CI o un gestor de secretos.
  - **Evidencia (archivos y líneas):** `.github/workflows/release-signing.yml:13-96` configura `protected-release`, checkout fijo en `master`, permisos de solo lectura, `chmod +x ./gradlew`, los cuatro secretos de firma y `GOOGLE_SERVICES_JSON_BASE64`; materializa los archivos con `umask 077` y los elimina en `always()`. `docs/adr/0002-ci-and-historical-google-services.md:1-68` documenta la custodia, el flujo y el rollback.
  - **Comando y resultado:** GitHub Actions run final [44579250096](https://github.com/CabgonDev/BlackHawkMaintenance/actions/runs/44579250096) sobre `f1d9ab6`, aprobado por el entorno protegido → `success`; `BUILD SUCCESSFUL`; certificado SHA-256 `024db26301133f4ac627a26f89169d11d0000ca535e631e1d9965ee3c8ec1b28`; hash público del APK `b13a1ca19f4716ad3e25aabbbf94e7fe933088cac4fb6804e37b3e2782a3b9c0`; limpieza de signing inputs y `app/google-services.json` completada.
  - **Versión / artefacto / rollback:** App `5.0.2 (Hawk)` (`versionCode` `502`); APK remoto firmado y verificado por CI, no publicado automáticamente. **Rollback:** deshabilitar el workflow y recuperar el APK/clave de continuidad según el ADR.
- [x] Hacer fallar el release si faltan `storeFile`, `storePassword`, `keyAlias` o `keyPassword`; nunca sustituirlos por valores vacíos.
  - **Evidencia (archivos y líneas):** `app/build.gradle.kts:11-53` valida `storeFile`, `storePassword`, `keyAlias` y `keyPassword` solo al solicitar tareas de empaquetado release; acepta un archivo protegido mediante `releaseSigningPropertiesFile` o `RELEASE_SIGNING_PROPERTIES_FILE` y no imprime valores; `docs/adr/0001-release-signing-properties.md` documenta la decisión y el rollback.
  - **Comando y resultado:** `./gradlew.bat :app:assembleRelease -PreleaseSigningPropertiesFile=C:/__bhm_missing_v502_signing__.properties --no-daemon` → falla durante la configuración con `Release signing properties are unavailable`; archivo temporal con solo `storeFile` → falla con `Missing properties: storePassword, keyAlias, keyPassword`; ninguna salida contiene contraseñas, alias ni rutas de secretos. La configuración de tareas Debug llegó a enumerar `assembleDebug` sin exigir propiedades release; con el SDK accesible mediante el entorno protegido, `./gradlew.bat :app:assembleRelease --no-daemon` terminó `BUILD SUCCESSFUL`.
  - **Versión / artefacto / rollback:** App `5.0.2 (Hawk)` (`versionCode` `502`); APK release protegido generado en `app/build/outputs/apk/release/app-release.apk`. **Rollback:** revertir `app/build.gradle.kts` y `docs/adr/0001-release-signing-properties.md`, conservando la clave de continuidad protegida.
- [x] Verificar el certificado del APK con `apksigner` y probar instalación limpia y actualización directa desde 5.0.1 conservando datos.
  - **Evidencia (archivos y líneas):** `app/build/outputs/apk/release/app-release.apk` y `app/build/outputs/apk/release/output-metadata.json` (`applicationId` `com.cabgon.blackhawk`, `versionCode` `502`, `versionName` `5.0.2 (Hawk)`).
  - **Comando y resultado:** `apksigner.bat verify --print-certs` → certificado esperado y SHA-256 `024db26301133f4ac627a26f89169d11d0000ca535e631e1d9965ee3c8ec1b28`; dispositivo `HYBIROYPDIHYKJIV` tenía `5.0.1`, `adb install -r app-release.apk` → `Success`, y después mostró `versionCode=502`, `versionName=5.0.2 (Hawk)` con `dataDir=/data/user/0/com.cabgon.blackhawk` sin desinstalación previa; smoke launch de `StartupActivity` sin entradas de crash de `com.cabgon.blackhawk`. En el usuario Android temporal `10`, `adb install --user 10 app-release.apk` → `Success`, `dumpsys package` mostró `dataDir=/data/user/10/com.cabgon.blackhawk`, `StartupActivity` inició sin crash y el usuario fue eliminado después de la prueba.
  - **Versión / artefacto / rollback:** App `5.0.2 (Hawk)` (`versionCode` `502`); APK firmado verificable, instalación limpia aislada y actualización directa completadas. **Rollback:** detener la distribución y recuperar el APK 5.0.1 firmado con la misma clave si una validación posterior falla.
- [x] Auditar que ningún secreto vuelva a aparecer en la historia Git, artefactos, logs o respaldos publicados.
  - **Evidencia final:** no hay claves privadas, contraseñas de firma ni archivos de firma en el árbol trackeado, artefactos CI, logs o backups en claro. La historia conserva `app/google-services.json` como hallazgo histórico; sus API keys son identificadores de cliente, la clave histórica quedó restringida y la nueva clave activa está rotada/restringida a paquete + SHA-1. `firebase-debug.log` no existe en el árbol, los runs `44448082108`, `44451187761` y `44579250096` terminaron con limpieza de secretos, y el workflow nunca imprime valores.
  - **Comando y resultado:** `git grep` sobre todos los commits → 0 claves privadas; auditoría de backups → 0 archivos `keystore.properties`, `*.jks`, `*.keystore` o `google-services.json` en claro; `git status --short --branch` → solo `.vscode/settings.json` no trackeado; no se reescribió `master` porque la mitigación cloud y el control de acceso eliminan la validez operativa de las claves históricas sin destruir la trazabilidad.
  - **Versión / rollback:** App `5.0.2 (Hawk)` (`versionCode` `502`); auditoría final de secretos completada. **Rollback:** conservar el historial y restaurar únicamente desde DPAPI/entornos protegidos, nunca desde copias en claro.

**Evidencia:** fingerprint SHA-256 del certificado, build release protegido, procedencia del APK y prueba de instalación/actualización. **Rollback:** recuperar la clave y contraseña desde el respaldo cifrado; si no se puede conservar la firma, detener la distribución hasta aprobar un plan de migración que no pierda datos.

## App v5.0.3 — Artefactos y reproducibilidad

**Esfuerzo estimado:** 1–2 días. **Commit sugerido:** `build(v5.0.3): make builds reproducible and clean`. **Gate:** un checkout limpio no contiene salidas generadas y reproduce `check` con el entorno documentado.

- [ ] Retirar del árbol versionable `app/release/app-release.apk`, `mapping.txt`, `native-debug-symbols.zip` y metadatos generados; conservarlos solo en el almacén protegido de artefactos.
  - **Estado de publicación (2026-11-19):** la candidata v5.0.3 local (`versionCode` `503`) generó `app/build/outputs/apk/release/app-release.apk` con 101685922 bytes y SHA-256 `202f9a17014f523ad6d278ba5b2beeb799e087b4fb1a7e5178c1c6c2d2e840f3`, certificado release esperado y `BUILD SUCCESSFUL`; no se subió ni reemplazó el APK activo. El APK activo v5.0.2 se archivó server-side como `ai/stable/apk/release502.apk` (101767842 bytes; mismo MD5 y CRC32C que `app-release.apk`). `ai_ota/channels/stable/config` permanece en v5.0.2 (`appApkVersionCode=502`, `appApkBytes=101767842`, `appApkPath=ai/stable/apk/app-release.apk`, `minAppVersionCode=502`) hasta completar la aceptación al 100%.
- [ ] Ignorar `hs_err_pid*.log`, `replay_pid*.log`, `deps_debugRuntime.txt`, `deps_releaseRuntime.txt` y `project_tree.txt`.
- [ ] Revisar `hs_err_pid2840.log` para identificar la causa del OOM y documentar si requiere un ajuste.
- [ ] Ajustar `org.gradle.jvmargs`, R8 y memoria del entorno con una justificación reproducible.
- [x] Añadir `distributionSha256Sum` en `gradle/wrapper/gradle-wrapper.properties`.
  - **Evidencia (2026-11-19):** `gradle/wrapper/gradle-wrapper.properties:5` fija `distributionSha256Sum=bafd5ce9cfaea0fbccfdc8439a1ac42fbd4cd9c89dc9a988228d8a2639a58e6c`, correspondiente a `gradle-9.8.0-bin.zip`; el wrapper se consumió durante `:app:testDebugUnitTest :app:assembleRelease` con `BUILD SUCCESSFUL`.
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

- [x] Obtener desde Firebase Console un snapshot fechado de Firestore Rules y Storage Rules solo si existe acceso; no inventar ni desplegar `firestore.rules`/`storage.rules` locales como sustituto.
  - **Evidencia cloud (2026-11-19):** Firestore release `projects/blackhawk-maintenance/releases/cloud.firestore`, ruleset previo `9dabc331-c0b3-4d79-9b9d-c83cc0be2c30`, actualizado el `2026-10-04T22:21:51.087834Z`; durante la primera corrección se publicaron `984def3d-0716-4e47-80f4-2e4335186de8`, `891a9c0a-0aae-427c-a4a1-4531bf905b79` y `775c22d2-2b24-4a9b-93d2-85ad0d30b73e`; el ajuste final activo es `51e4daca-66c0-4666-ac19-fa48c75a8be1`, actualizado el `2026-11-19T20:21:47.436518Z`. Storage release `projects/blackhawk-maintenance/releases/firebase.storage/blackhawk-maintenance.firebasestorage.app` conserva el ruleset `6cd97f6e-544c-452d-a8de-0c00b5ca2760`, actualizado el `2026-10-05T00:49:13.556012Z`. Los snapshots auditados se versionan como `firestore.rules` y `storage.rules`; Storage no se modificó.
- [ ] Exigir `request.auth.uid == uid` al crear documentos de usuario y bloquear cambios de `role`, `status`, `uid` y campos administrativos desde el cliente.
- [ ] Restringir inspecciones 40h/120h/480h por propietario o dataset compartido explícito; validar ownership en En Ruta, preflight, galería y documentos administrativos.
- [x] En Estado de material (En Ruta), permitir a todo usuario aprobado leer el dataset compartido y mostrar quién editó cada registro sin abrir perfiles privados completos.
  - **Evidencia de corrección:** el hallazgo de v5.0.2 se reprodujo en `EnRutaViewModel.kt`: Developer resolvía `users/{uid}` ajenos, mientras User recibía `PERMISSION_DENIED` y veía `—`. La solución denormaliza `lastEditorUserId`, `lastEditorProfileUid`, `lastEditorGrade` y `lastEditorName` en `EnRutaStatusEntity`/Firestore, elimina lecturas de `users/{uid}` ajenos, añade migración Room `10→11` y actualiza la caché cuando llega una atribución histórica con la misma marca de edición. Firestore mantiene `allow read: if isApproved()` para `/en_ruta` y `recargas`; se corrigió el solapamiento del comodín recursivo y la regla de escritura exige UID autenticado, identidad ligada y snapshots no vacíos; en escrituras nuevas el grado/nombre deben coincidir con el perfil vigente, mientras las ediciones legacy que no tocan esos campos conservan snapshots históricos. El ruleset final activo es `51e4daca-66c0-4666-ac19-fa48c75a8be1` (actualizado `2026-11-19T20:21:47.436518Z`) y el dry-run posterior compiló correctamente. La matriz aislada de Auth/Firestore Emulator pasó contra las reglas finales en el proyecto ficticio `demo-bhm-enruta-rules`: lectura compartida, privacidad de perfiles, merge legacy, rechazo de UID/nombre/grado falsos, atribución válida, creación/borrado de moderadores y descendientes `recargas`; no hubo escrituras de producción. Se verificó el backfill controlado de `lastEditorProfileUid` en `1091`, `1092`, `1093`, `1094`, `1097` y `1098`, conservando los snapshots históricos y sin modificar datos operativos. **Aceptación:** User ve todos los registros y editor; no obtiene lectura de `users/{otherUid}`; Developer conserva su acceso.
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

## Mapa de cobertura por área

| Área de auditoría | Entregas |
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
