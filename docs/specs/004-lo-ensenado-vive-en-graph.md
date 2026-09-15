# Plan de implementación: lo enseñado vive en Graph — el protocolo y el cliente de aprendizaje

Estado: **fase 4A1 en curso** (2026-09-14; promesas 401-406 escritas) · Nace de leer el cliente Windows (`U-Windows-App`) que ya graba, guarda y ejecuta
workflows en Graph · Rama: `yokh/aprendizaje-graph`

Hoy el Android aprende solo: `ActiveLearning`, `GeminiLearning`, `GeminiWorkflow` y `WorkflowRepo`
(en `app/…/platform/`) mandan el video y los pasos a Gemini con la key horneada y guardan los
workflows en el teléfono. Windows ya no hace eso: abre una sesión de aprendizaje en Graph, le manda
los pasos uno a uno, cierra la sesión y Graph persiste el workflow; para ejecutarlo le pide el plan.
Esta spec pone en `core` **el protocolo y el cliente** de esas rutas. No cablea nada en la app: el
orquestador de la lección (4A2), el grabador por accesibilidad (4B), la enseñanza activa (4C) y el
reproductor (4D) van en specs y fases propias, sobre este cliente.

---

## Diagnóstico: qué se midió

Se midió leyendo el cliente Windows, no suponiendo. Graph no está en esta máquina: ninguna línea de
este cliente habló con él todavía (ver «Supuestos de Graph sin verificar»).

| Qué | Medida | Fuente |
|---|---|---|
| El contrato de aprendizaje y workflows ya está escrito | sesión, paso, nota de contexto, cierre, lista, plan, alineación; nombres de campo exactos (snake en la sesión, camel en el paso y el plan) | `U-Windows-App/windows-graph/src/Contracts.cs` |
| Las rutas de enseñanza por video tienen su propio contrato | `upload-token`, `file-state`, `process-video` (la interpretación viaja como JSON crudo), `interpret-steps` | `U-Windows-App/windows-client/src/Teach/TeachSession.cs:150-460` |
| Las cabeceras de aprendizaje no llevan `X-Miracle-Feature` | `GraphClient` pone `X-API-Key`, `X-Miracle-App`, `X-Miracle-Device-Id`, `X-Miracle-User-Email` (si hay); `/teach/*` va por `BackendClient`, que sí suma `X-Miracle-Feature: conscious_bridge` | `GraphClient.cs:64-80` · `BackendClient.cs:68` |
| Los topes no son los del turno | 90 s por llamada en `GraphClient`; 5 min en `/teach/*` | `GraphClient.cs:64` · `BackendClient.cs:45` |
| El cierre falla en flujos largos | Vercel corta el post-procesado con 504; Windows reintenta 3 veces con 3 s y 8 s y, si no, lanza `FinishPendingException`: los pasos ya están guardados, falta el resumen | `WorkflowRecorder.cs:96-165` · `GraphClient.cs:24-40` |
| Graph manda «ausente» como `""` | en C# `??` no cae con `""`: `workflow_id`, `summary` y `error` vacíos se tomaban por valores | resumen del sprint 4 («Trampas») |
| `createdAt` llega como entero Neo4j | `{low, high}`, el valor es `high·2³² + low` con `low` **sin signo**; a veces un número llano | `windows-client/src/Workflows/NombreDeWorkflow.cs:91-115` |
| La lista llega del más viejo al más nuevo | `ORDER BY w.id ASC`: lo recién enseñado quedaba al final | `SelectorDeWorkflows.cs` · promesa 110 de Windows |
| Tres de cuatro workflows no tenían nombre | «Workflow sin descripción» (relleno del grabador) y «User workflow summary:» (encabezado del LLM), medido contra el Graph vivo el 2026-09-02 | `NombreDeWorkflow.cs:1-60` · promesa 108 de Windows |
| Borrar lo que no existe es 404 | el carrusel de Windows lo trataba como error | `GraphClient.cs:187-198` |
| La alineación es best-effort pero muda | `PrependAlignmentStepAsync` hace `catch { }`: su fallo no queda en ningún lado | `GraphClient.cs:145-151` |
| Interpretar sin video es el respaldo del respaldo | `InterpretarPasosAsync` nunca lanza: devuelve `""` y lo dice en el log | `TeachSession.cs:225-270` |

**Lo que esto significa:** el cliente de aprendizaje es otro cliente tonto, como el del turno, y se
rompe igual de callado: un `""` que se toma por id, un paso con `alternativeTargets` que Graph no
entiende, un cierre que se reintenta después de haberse cobrado, un workflow que se presenta como
«Workflow sin descripción». Ninguno tira una excepción.

---

## La especificación

Bloque 401+ (la 001 usa 1-99). Los números no se reciclan. El enunciado de cada promesa es
**literal** el del test (`core/src/commonTest/kotlin/graph/core/contrato/Contrato004EnsenadoEnGraph.kt`,
método `promesaNNN`); si cambia uno, cambia el otro en el mismo commit.

| # | Promesa | Fase |
|---|---|---|
| 401 | El protocolo de aprendizaje y workflows es espejo de Windows campo por campo; un campo vacío de Graph cuenta como ausente y createdAt se lee sin signo. | 4A1 |
| 402 | Cada llamada de aprendizaje lleva X-API-Key, X-Miracle-App android_app y el id de dispositivo; el email solo si existe. | 4A1 |
| 403 | Un transitorio de aprendizaje se reintenta como en el cerebro, pero terminar una sesión se reintenta 3 veces con 3 s y 8 s y una lectura agotada nunca se reintenta. | 4A1 |
| 404 | La lista de workflows va del más nuevo al más viejo y ningún workflow se presenta con un nombre de relleno de Graph. | 4A1 |
| 405 | Borrar un workflow que ya no existe cuenta como borrado; alinear antes de un workflow es best-effort pero su error queda en el log. | 4A1 |
| 406 | Interpretar pasos nunca revienta: sin respuesta de Graph devuelve que el modelo no opinó y lo dice. | 4A1 |

**La que cierra el asunto es la 401.** Si el protocolo no es el de Windows, Graph recibe algo que
acepta con HTTP 200 y descarta en silencio (un `actionType` que no conoce, unos `alternativeTargets`
que no son lista): el workflow se guarda y no se puede ejecutar.

### Con qué se juzga cada una

Todas son **mapa a mano dentro de la propia prueba**: un `TurnTransport` falso que graba método, URL,
cuerpo, cabeceras y tope de cada llamada y devuelve respuestas guionadas; un `sleep` que anota las
esperas; un `TestTimeSource` que avanzan el guion y las esperas. Ninguna toca red, Android ni disco.

| # | Cómo se juzga sin tocar nada |
|---|---|
| 401 | Las claves de cada request son exactamente las de `Contracts.cs`/`TeachSession.cs` (sesión en snake, paso en camel, `note{role,transcript,mode}`, cierre `{}`, plan `{variables, execution_intent}`, las cuatro de `/teach/*`); un `""` del request viaja; `alternativeTargets` viaja como lista y el resto de pistas como texto. Respuestas con `""` y `null` en `workflow_id`, `summary`, `error`, `url`, `label` → ausentes (y un `error:""` con HTTP 200 no termina nada); un campo que Graph agregue no rompe; el `PlanStep` expone superficie, `readiness` (número o texto), huella, `clickPos`, `alternativeTargets` y `nodePath`; la interpretación de `process-video` llega cruda e idéntica; `createdAt` `{low negativo, high}` da el mismo instante que el número llano |
| 402 | Las nueve llamadas de aprendizaje y workflows con y sin email: `X-API-Key`, `X-Miracle-App: android_app`, `X-Miracle-Device-Id`, nunca `X-Miracle-Feature`; las cuatro de `/teach/*`, además, `X-Miracle-Feature: conscious_bridge`. Método y URL de cada una, con el id escapado en la ruta; la key no viaja ni en la URL ni en el cuerpo |
| 403 | `mandarPaso` con `503, 0, 200` → 3 llamadas, esperas `[800, 1600]`; cada transitorio solo y primero se reintenta; `429` con `Retry-After` 4 → `[4000]`; `-1` → 1 llamada y el mensaje dice que no se reintentó. `terminar` con `504×3` → 3 llamadas, esperas `[3000, 8000]` y `FinishPendiente` con el id de sesión; `504, 200` → 2; `-1` → 1 llamada, 0 esperas; `400` → 1. Una llamada colgada se corta en su tope y cuenta como lectura agotada (1 llamada). El tope que recibe el transporte: 90 s general, 5 min en `/teach/*`. Cancelar sale como `CancellationException`, tipo exacto |
| 404 | Graph lista viejo → nuevo (con `createdAt` Neo4j) → el cliente devuelve nuevo → viejo; sin fecha, por id descendente. `nombre()` de «Workflow sin descripción», «User workflow summary:», `""` y «No description» no contiene el relleno y sí la app, «2 sep 13:42» (con desfase de -5 h) y «6 pasos»; una descripción de verdad se respeta tal cual |
| 405 | `borrar` con `404` → no lanza, 1 llamada `DELETE`; con `500` → `GraphException` tipo exacto. `prependAlignment` con `500 {"error":…}` o con un transporte que lanza → no lanza, devuelve `false` y una línea del log trae el id y la causa; con `200` → `true` y ninguna línea de fallo; cancelar sale tal cual |
| 406 | `interpretSteps` con `503×4`, `-1`, transporte que lanza, cuerpo ilegible, `{}`, `interpretation:null` e `interpretation:""`, sin key y sin pasos → `null` sin excepción, y el log dice «el modelo no opinó» con la causa; con `interpretation` de verdad → el JSON crudo idéntico; cancelar sale tal cual |

---

## Las fases

### Fase 4A1 — protocolo y cliente en `core` (esta corrida)

Todo en `core/src/commonMain/kotlin/graph/core/graph/`, sin dependencias nuevas:

- `TurnTransport.kt` — `send(method, url, body, headers, timeout)` con implementación por defecto
  (POST va a `post`): los transportes de la 001 no cambian.
- `Reintentos.kt` — el bucle de reintentos del turno, sacado de `GraphBrain` sin cambiar lo que hace,
  para que «como en el cerebro» sea el mismo código y no una copia que se desincroniza.
- `GraphHeaders.kt` — `feature` opcional (por defecto la del cerebro, promesa 8 intacta).
- `learning/Protocol.kt` — espejo `@Serializable` de `Contracts.cs` y de los contratos de
  `TeachSession.cs`; `vacioEsAusente`; `WorkflowResumen` (espejo de `WorkflowSummary.FromJson`).
- `learning/LearningClient.kt` — las trece llamadas, con topes, reintentos y errores.
- `learning/NombreDeWorkflow.kt` — espejo de `NombreDeWorkflow.cs`.

Y `app/…/platform/GraphTransport.kt` implementa `send` con `HttpURLConnection` (GET, POST, PUT sin
cuerpo binario, DELETE; tope de lectura por llamada).

Pone verdes: **401-406**.

### Lo que viene (specs y fases propias, sobre este cliente)

4A2 orquestador de la lección · 4B grabador por accesibilidad · 4C enseñanza activa por Graph y
`pending-finish` · 4D reproductor del plan · 4E `workflow_*` por Graph · 4F comprobar.

---

## Diferencias deliberadas con Windows

| Qué | Windows | Android | Por qué |
|---|---|---|---|
| Reintentos en transitorio (0, 408, 429, 502, 503, 504) | `GraphClient` no reintenta | igual que el turno: hasta 3 reintentos con 800 / 1600 / 3200 ms (429 con `Retry-After`: eso, hasta 10 s), todo dentro del tope de la llamada | la red móvil (spec 001). El cierre tiene su propio calendario, el de Windows |
| Tope | 90 s por request (`HttpClient.Timeout`) | 90 s **por llamada**, intentos y esperas incluidos (5 min en `/teach/*`); en `terminar`, 90 s **por intento** | con reintentos, un tope por intento haría la llamada de hasta 6 min; el cierre ya tiene sus tres intentos contados y un 504 de Vercel tarda lo que tarda |
| Lectura agotada en `terminar` | llega como cancelación y no se reintenta | `-1`, no se reintenta y lanza `GraphException` (no `FinishPendiente`) | Graph pudo haber cerrado, y cobrado, la sesión: reintentar más tarde lo decide quien llama (4C) |
| `error` en un cuerpo 2xx | se ignora | termina la llamada con ese texto; `""` no cuenta | criterio del cerebro (promesa 6) |
| `""` de Graph | `??` no cae | ausente al leer (`vacioEsAusente`) | la trampa medida |
| Cancelar `interpretSteps` | el `catch (Exception)` se traga también la cancelación | la cancelación sale | cancelar la corrida no es un fallo del modelo (promesa 14) |
| Fallo de `prepend-alignment` | `catch { }` mudo | devuelve `false` y deja la causa en el log | best-effort no es invisible |
| Cuerpo de `notaDeContexto`, `borrar`, `prependAlignment` | se parsea (`PostAsync<JsonElement>`): un 2xx vacío lanza | no se parsea; solo cuentan el status y un `error` legible | nadie usa ese cuerpo; un 2xx vacío no es un fallo |
| `X-Miracle-App` y `execution_intent.source` | `windows_app` · `windows-u` | `android_app` · `android_app` | atribución por plataforma |
| `createdAt` como fecha ISO | se lee | no se lee (queda sin fecha) | Graph manda el entero Neo4j (medido 2026-09-02); leer ISO sin `kotlinx-datetime` es otra dependencia |

---

## Supuestos de Graph sin verificar

Todo lo que este cliente asume y **no** está en `windows-graph/src/Contracts.cs` ni se midió desde un
Android. Se verifica en el Nivel 4 de 4C/4D, contra el Graph vivo; hasta entonces, cada uno es un
riesgo abierto.

- `POST /api/v1/learning/sessions` acepta `context.platform = "android"` y un `source_url`/`source_origin` `android://…`.
- `StepRequest.actionType` `key` y `scroll` (el grabador de Windows los emite y `WorkflowExecutor` descarta del plan lo que no sea `input|select|click|navigation`): el cliente los deja pasar como texto libre; qué hace Graph con ellos no se sabe.
- `navigation` con `url = android://paquete/Activity` es ejecutable para Graph.
- Los selectores opacos `a11y:…` y las pistas de superficie de Android (`observedSurface android://…`, `readiness`, `fingerprint`, `clickPos`) viajan y vuelven intactos en el plan, igual que los de UIA/SAP.
- `X-Miracle-App: android_app` sin `X-Miracle-Feature` es válido en `/learning/*` y `/workflows/*` (en el turno se midió con la feature).
- `execution_intent.source = "android_app"` es un valor aceptado (Windows manda `windows-u`).
- `X-Miracle-Feature: conscious_bridge` es la atribución correcta para `/teach/*` también desde Android.
- `GET /api/v1/workflows` devuelve para esta API key los workflows grabados desde Android, con el mismo shape (`id`, `description`, `sourceOrigin`, `sourceTitle`, `totalSteps`, `createdAt {low, high}`).
- `DELETE /api/v1/workflows/{id}` sigue respondiendo 404 cuando no existe (y no 200 ni 410).
- `finish`, `plan` e `interpret-steps` no dependen de Gemini; `upload-token` y `process-video` sí (sin créditos desde 2026-09-03, pueden fallar).
- Un 2xx con `{"error":"…"}` es un fallo y no un éxito parcial.
- Las sesiones de aprendizaje viven en memoria serverless: por eso el id va siempre en la ruta; si Graph pierde una sesión entre instancias, el paso responde con un error que este cliente muestra tal cual.

---

## Lo que NO entra, y por qué

- **El PUT del video a Gemini y al archivo**: es binario y va con el orquestador (4A2/4C). `send` ya
  acepta `PUT`, pero sin cuerpo binario.
- **`pending-finish.json` y el reintento al arrancar**: disco y ciclo de vida de la app (4C). Este
  cliente solo distingue `FinishPendiente` para que eso sea posible.
- **El sondeo de `file-state` (2 s × 90)**: es orquestación (4A2). Aquí, una consulta.
- **`POST /api/v1/autofill/match` y `GET /api/v1`**: no los usa ninguna fase del sprint 4.
- **Cablear en la app** (`GraphApp`, `MainActivity`, `ActiveLearning`): 4C.
- **Nombre puesto por el usuario** (promesa 109 de Windows): disco, 4C.

## Riesgo

El mayor es el mismo de la 001: que Graph cambie el contrato y este cliente no se entere. Por eso
`ignoreUnknownKeys`, `""` = ausente, `null` tolerado, y la interpretación cruda: quien la entiende es
una pieza pura que no vive aquí.
