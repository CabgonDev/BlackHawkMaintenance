# Blackhawk Maintenance – DEV NOTES

Este documento define reglas y lineamientos para desarrollo en el proyecto **Blackhawk Maintenance**.  
Debe ser leído y respetado por cualquier persona (humana o IA) que toque el código.

---

## 0. Contexto del proyecto

- App Android para **gestión y consulta de mantenimiento** de helicópteros UH-60L Black Hawk.
- Uso **real** en campo (Fuerza Aérea Mexicana, Escuadrón Aéreo 113).
- Prioridad absoluta:
    - Estabilidad.
    - Respeto a la arquitectura y datos existentes.
    - Cambios mínimos y bien controlados.

---

## 1. Reglas generales de colaboración

1. **Siempre trabajar sobre el código ACTUAL**
    - Si se proporciona un ZIP actualizado del proyecto, ese zip es la **única fuente de verdad**.
    - No asumir que el código “se parece” a versiones anteriores.
    - No inventar clases, paquetes o rutas que no existan en el repo.

2. **Cambios siempre en archivos COMPLETOS**
    - Si se modifica un `Fragment`, `Adapter`, `Activity`, etc., se debe entregar el archivo **completo**, listo para copiar/pegar.
    - Evitar instrucciones tipo “agrega estas 3 líneas en tal función”.
    - Esto evita desincronización y parches a medias.

3. **No introducir nuevas arquitecturas sin autorización**
    - Prohibido “refactorizar” hacia patrones nuevos (RAG, capas extra, nuevos repositorios, etc.) sin que el owner lo pida explícitamente.
    - El objetivo es **hacer funcionar lo que ya existe**, no convertir el proyecto en laboratorio.

4. **Respetar los nombres y contratos existentes**
    - No cambiar nombres de:
        - Clases.
        - Métodos públicos.
        - IDs de vistas.
        - Rutas de assets.
    - Si es estrictamente necesario cambiar algo, debe estar claramente documentado y coordinado.

5. **No borrar lógica o módulos sin entender su impacto**
    - Antes de tocar un módulo, identificar:
        - Dónde se usa.
        - Qué otros módulos dependen de él.
    - Evitar “limpiezas” agresivas que rompan funcionalidad real usada en campo.

---

## 2. Datos y assets

### 2.1. Índices y DBs en `assets`

- La app usa datos en `assets/` y/o `assets/index/` para:
    - Índices locales de **números de parte**.
    - Manuales técnicos en PDF.

Reglas:

- No mover ni renombrar archivos en `assets/` sin actualizar TODO el código que los referencia.
- No asumir otros formatos o ubicaciones distintas a las que ya existen en el repo.

---

## 3. Módulo “Números de Parte”

Este módulo ya está funcionando y **no debe romperse**.

### 3.1. Visión funcional

- Entrada:
    - Campo de texto para **PN** (número de parte).
    - Botón **Buscar**.
- Salida:
    - Tarjeta **WBParts** (fuente online, si hay internet).
    - Tarjeta **Local** (fuente offline, índice Sikorsky).
    - Lista de resultados agrupados por **manual**, con filas clicables que abren el PDF en la página correspondiente.

### 3.2. Clases clave

- `PartNumbersFragment`
    - Controla la pantalla de búsqueda de números de parte.
    - Hace:
        - Búsqueda WBParts vía `PartRepo.searchPartInfo(q)`.
        - Búsqueda local vía `SikorskyPartsIndex.openFromAssets(...).search(q, limit = 500)`.
    - Muestra:
        - Tarjeta WBParts con cuerpo de texto plano y chip **WBParts Web**.
        - Tarjeta Local con:
            - `Local (N coincidencias)`.
            - PN / NSN / descripción corta (2 palabras).
            - Texto instructivo:  
              `Toca una fila para abrir el manual. Mostrando N filas.`

- `SikorskyPartsIndex`
    - Se abre **desde assets**.
    - Expone un método `search(query, limit)` que regresa una lista de `SikorskyPartHit`.

- `SikorskyPartHit`
    - Representa una coincidencia:
        - Número de página.
        - PN.
        - NSN.
        - Descripción.
        - Fig.
        - `assetPath` del manual.

- `SikorskyPartHitAdapter`
    - Adapter para el `RecyclerView` de resultados locales.
    - **Agrupa** los hits por manual.
    - Muestra headers expandibles por manual.
    - En cada fila:
        - Línea 1: PN.
        - Línea 2: `Pág X | NSN ... | FIG ... | Manual`.

### 3.3. Layouts relevantes

- `res/layout/fragment_part_numbers.xml`
    - Root: `LinearLayout` vertical, **sin ScrollView**.
    - Contiene:
        - Título: “Números de parte”.
        - Fila búsqueda (EditText + Botón).
        - CardView WBParts.
        - CardView Local.
        - `RecyclerView` (`rvLocalOccurrences`) con:
            - `layout_height="0dp"`.
            - `layout_weight="1"`.
            - `nestedScrollingEnabled="true"`.

- `res/layout/item_part_hit.xml`
    - Layout para cada fila de resultado local.

- `res/layout/item_manual_header.xml`
    - Layout para cada encabezado de manual (expansible/colapsable).

---

## 4. Reglas específicas de UI

1. **Prohibido meter `RecyclerView` dentro de `ScrollView`**
    - Esto provoca que solo se vean unas pocas filas (3–4) aunque existan muchas más.
    - El patrón aprobado es:
        - Layout de alto `match_parent`.
        - Sección superior fija (título + tarjetas).
        - `RecyclerView` con `layout_weight="1"` para hacer scroll solo en la lista.

2. **Mantener estilos coherentes**
    - Cuerpos de texto tipo “body”: `14sp`.
    - Botón/chip de WBParts:
        - `txtWBPartsGo` con fondo `@drawable/bg_wbparts_chip`.
        - Texto corto tipo “WBParts Web”.

3. **No cambiar IDs de vistas existentes**
    - `edtPart`, `btnSearchPart`, `layoutWBPartsCard`, `txtWBPartsBody`, `txtWBPartsGo`,
      `layoutLocalCard`, `txtLocalTitle`, `txtLocalMeta`, `txtLocalBody`, `rvLocalOccurrences`,
      etc.
    - El binding (`FragmentPartNumbersBinding`) depende de ellos.

---

## 5. Patrones de código aceptados en este módulo

1. **Búsquedas locales siempre via `SikorskyPartsIndex`**
    - No insertar otras librerías o engines de búsqueda sin autorización.
    - Si se requiere mejorar scoring/ranking, hacerlo dentro del flujo de `SikorskyPartsIndex` ya existente.

2. **Límites explícitos**
    - `search(query, limit = 500)` es suficiente para campo.
    - Si se ajusta el límite, documentarlo.

3. **Adapter agrupado, no plano**
    - `SikorskyPartHitAdapter` debe:
        - Recibir una lista plana de `SikorskyPartHit`.
        - Internamente agrupar por manual.
        - Mostrar headers expandibles.

---

## 6. Lineamientos para IA (#TeamGabo)

Cuando se use IA (por ejemplo, ChatGPT) en este repo:

1. **La IA debe trabajar SIEMPRE con:**
    - El **zip actual** del proyecto (si se proporciona).
    - Referencias exactas a las rutas reales (`app/src/...`, `res/layout/...`, etc.).

2. **La IA debe entregar:**
    - Archivos completos (listas para copiar/pegar).
    - Instrucciones precisas del tipo:
        - “Reemplaza `X.kt` por este contenido”.
        - “Crea `Y.xml` en tal ruta”.

3. **La IA NO debe:**
    - Introducir nuevas capas/arquitecturas sin petición explícita.
    - Renombrar clases, métodos o IDs.
    - Asumir estructuras que no existen en el repo.

4. **Prioridad:**
    - Resolver el problema real del usuario (campo/mantenimiento).
    - Minimizar cambios.
    - Mantener compatibilidad con el código existente.

### 6.1. Modelo recomendado (GPT)

Para edición de código de este repo:

- **Modelo recomendado:** `GPT-5.1 Thinking`
    - Usar este modelo para:
        - Cualquier modificación de código.
        - Trabajo directo sobre el zip/proyecto real.
    - Motivo: comportamiento más estable y alineado con las reglas de este documento.

Para ideas, brainstorming, documentación y diseño conceptual:

- Se puede usar `GPT-5.2 Auto` o similares,  
  **pero** las propuestas deben revisarse y solo aplicar cambios en el repo a través de:
    - GPT-5.1 Thinking, o
    - revisión manual siguiendo las reglas de este DEV_NOTES.

---

## 7. Roadmap (resumen de alto nivel)

### 7.1. Estado de módulos principales

- **Números de Parte:** ESTABLE (V2.0.x)
- **Frecuencias:** ESTABLE (V2.1)
- **Generalidades:** ESTABLE (V2.2+)
- **Puesta en Marcha (checklists):** Pendiente
- **Galería + moderación:** Pendiente
- **Chat IA / Feedback IA:** En construcción / futuro

### 7.2. Hitos por versión

No es documento funcional, solo referencia rápida:

- **V2.0.x**
    - Estabilidad general.
    - Módulo Números de Parte funcionando 100%.

- **V2.1**
    - Frecuencias OTA.
    - Módulo Frecuencias operativo y estable en producción.

- **V2.2+**
    - Generalidades OTA.
    - Puesta en Marcha (checklists).
    - Galería con moderación.

- **Panel Admin**
    - Gestión de contenido OTA.
    - Roles (Developer/Admin/Moderator).
    - Publicaciones versionadas (frecuencias, generalidades, checklists).

---

## 8. Última nota

Cualquier cambio en este documento debe hacerse con el mismo criterio que el código:

- Explícito.
- Justificado.
- Pensando en uso real en campo, no en ejercicios teóricos.

#TeamGabo
