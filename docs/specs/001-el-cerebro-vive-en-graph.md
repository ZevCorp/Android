# Plan de implementación: el cerebro vive en Graph — Android pasa a ser cliente tonto

Estado: **fases A y B implementadas** (2026-09-14; promesas 1-11 verdes; corrida a mano en el teléfono contra Graph real) · **promesa 12 verde** (2026-09-14; el hallazgo del Nivel 4, corregido y medido otra vez en el teléfono) · **revisión R1: promesas 13-14 verdes y el juez endurecido** (2026-09-14; cada test nuevo se vio ROJO con un sabotaje real; la corrida en el teléfono va en R2) · Nace de leer el cliente Windows (`U-Windows-App`) que ya
habla con Graph · Rama: `yokh/cliente-graph`

Hoy el Android piensa solo: `OpenAiBrain` y `GeminiBrain` (en `app/…/platform/`) arman el system
prompt, declaran el catálogo de herramientas MCP y llaman al modelo con la key del proveedor
horneada en el APK. Windows y macOS ya no hacen eso: mandan la pantalla a Graph
(`POST /api/v1/agent/turn`) y reciben las acciones. Este plan pone al Android en la misma fila con un
**tercer proveedor `GRAPH`** junto a `OPENAI` y `GEMINI`; los dos viejos quedan como fallback hasta
que haya paridad, y después se retiran (spec aparte).

---

## Diagnóstico: qué se midió

Se midió leyendo los dos repos, no suponiendo.

| Qué | Medida | Fuente |
|---|---|---|
| El motor ya es agnóstico del cerebro | `ExecutionEngine` recibe `() -> Brain`, llama `next(state, results)`, ejecuta, pregunta al usuario y llama `inform`. No sabe de proveedores | `core/src/commonMain/kotlin/graph/core/application/Engine.kt` |
| Un cerebro intercambiable es un `ThreadedBrain` | `interactionId`, `hasPendingCalls`, `totalTokens`, `resume(id)`; el composition root los persiste entre activaciones | `core/…/domain/Ports.kt:90-115` · `app/…/GraphApp.kt:299-351` |
| El contrato JSON con Graph ya está escrito | `ScreenState`, `TurnRequest`, `AgentAction`, `TurnResponse` — nombres de campo exactos | `U-Windows-App/windows-client/src/Domain/Protocol.cs` |
| El bucle de Windows tiene 6 reglas que no están en ninguna otra parte | goal solo en el 1er turno; `session` opaco de vuelta; `results` en orden; `inform` aparte y una sola vez; screenshot solo si el turno anterior pidió `needsScreenshot`; acción desconocida → resultado de texto sin abortar | `U-Windows-App/windows-client/src/Agent/AgentLoop.cs:84-300` |
| Las cabeceras de atribución son cuatro | `X-API-Key`, `X-Miracle-App`, `X-Miracle-Feature: conscious_bridge`, `X-Miracle-User-Email` (si hay) | `U-Windows-App/windows-client/src/Backend/BackendClient.cs:26-120` |
| Los HTTP transitorios ya están definidos | `0 · 408 · 429 · 502 · 503 · 504`. Windows los clasifica pero **no** reintenta | `U-Windows-App/windows-graph/src/GraphClient.cs:18` |
| `core` no tiene ni un test | No existe `core/src/commonTest`. Nada juzga hoy al motor ni a los cerebros | `ls core/src` |
| La pantalla llega como `"pkg · título"` | `GraphAccessibilityService` arma `screen` con paquete y título separados por ` · ` | `app/…/platform/GraphAccessibilityService.kt:169` |

**Lo que esto significa:** no hay que tocar el motor ni la superficie. Falta **un cerebro que sea
un cliente HTTP** y nada más — y el contrato que lo juzgue, porque hoy nada en `core` se juzga.

---

## Por qué esto va dirigido por especificación

Porque un cliente tonto se rompe **en silencio**: si repite el objetivo en cada turno, Graph arranca
una conversación nueva cada vez y la corrida "funciona" pero no avanza; si manda la respuesta del
usuario en `results` en vez de `inform`, el `ask_user` pendiente nunca se resuelve y el modelo
pregunta lo mismo otra vez; si el `session` pierde una comilla al pasar por el JSON, el backend
descarta el hilo y el cliente no se entera. Ninguno de esos fallos tira una excepción. Los tres
fallan en verde.

**La regla del flujo, y no tiene excepciones:** ninguna línea de producción entra antes que la
promesa que la juzga. El contrato nace ROJO (commit 2) y la implementación lo pone INTACTO
(commit 3). Una promesa que pasa apenas se escribe no probó nada.

---

## La especificación

Es el primer contrato del repo Android: se numera desde 1. Los números no se reciclan. El enunciado
de cada promesa es **literal** el del test (`core/src/commonTest/kotlin/graph/core/contrato/Contrato001CerebroEnGraph.kt`,
método `promesaNN`); si cambia uno, cambia el otro en el mismo commit.

| # | Promesa | Fase |
|---|---|---|
| 1 | El primer turno lleva el objetivo y ningún session; los siguientes llevan el session que devolvió Graph y no repiten el objetivo. | A |
| 2 | Los resultados de las acciones viajan en `results` en el mismo orden; la respuesta a una pregunta viaja en `inform` una sola vez y nunca en `results`. | A |
| 3 | La captura viaja solo cuando el turno anterior la pidió, como PNG en base64 sin prefijo data-uri; si no la pidió, el campo no viaja. | A |
| 4 | Cada acción de Graph se traduce a la acción local equivalente (tap, type, scroll, swipe, key, wait, mcp); una acción desconocida no rompe la corrida: su resultado es "acción desconocida: <kind>". | A |
| 5 | `done`, `question`, `text`, `narration`, `speech` e `intents` de Graph llegan al motor tal cual. | A |
| 6 | Un HTTP transitorio (0, 408, 429, 502, 503, 504) se reintenta hasta 3 veces con espera creciente; 401 o 403 no se reintenta y dice que la key de Graph no vale; un `error` en el cuerpo termina el turno con ese texto. | A |
| 7 | El cliente no manda modelo, prompt ni catálogo de herramientas: el request solo tiene session, goal, userId, state, results e inform. | A |
| 8 | Cada request lleva `X-API-Key`, `X-Miracle-App: android_app` y `X-Miracle-Feature: conscious_bridge`; el email y el id de dispositivo viajan solo si existen. | A |
| 9 | La key de Graph se resuelve prefs sobre compilada; sin key, el proveedor GRAPH no llama a nadie y dice en una línea qué falta. | A |
| 10 | La superficie se deriva del paquete y la pantalla: origin `android://<paquete>`, pathname `/<pantalla>`, id = origin + pathname. | A |
| 11 | Un `session` devuelto por Graph se conserva byte a byte y vuelve en el siguiente request aunque contenga JSON, comillas o caracteres no ASCII. | A |
| 12 | Cada objetivo nuevo abre un hilo nuevo en Graph: el primer turno de cada corrida viaja sin session aunque haya uno de una corrida anterior o uno reanudado. | B |
| 13 | Un turno de Graph nunca espera más de 6 minutos en total: un fallo de conexión se reintenta, pero una lectura agotada no, porque el turno pudo haberse cobrado. | B · revisión |
| 14 | Cancelar la corrida durante un POST no se registra como fallo de red ni reintenta. | B · revisión |
| 15 | Las apps instaladas se consultan una sola vez por corrida y viajan en cada turno de esa corrida. | B · revisión R2 |

**La que cierra el asunto es la 7.** Mientras el cliente mande prompt o catálogo, no es tonto: es
el cerebro viejo con otro transporte. Las otras diez protegen el camino; la 7 es la que define qué
es este proveedor.

**La 12 nació de medir, no de leer** (Nivel 4, corrida 2): con `session` reanudado + `goal` nuevo,
Graph siguió el hilo viejo y reabrió la calculadora en vez de los ajustes. Se espeja Windows
(`AgentLoop.cs:96`, `session = null` por objetivo). **La promesa 1 no cambia:** su test nunca fijó
que un hilo reanudado se adopta; esa regla vivía solo en el comentario y en el `begin()` de
`GraphBrain` («con un hilo reanudado viaja igual»), y es lo que la 12 contradice y retira.

**La 15 nació de la revisión** (ronda R2): `GraphBrain` pedía las apps en cada turno, y desde
`MainActivity` esa consulta (una llamada al `PackageManager` por paquete instalado) corría en el
hilo principal.

**La 13 y la 14 nacieron de la revisión** (ronda R1): con 30 s para conectar y 5 min para leer por
intento, cuatro intentos podían tener el turno colgado más de 20 minutos, y una lectura agotada
se reintentaba aunque Graph ya hubiera recibido (y quizá cobrado) el turno. Y cancelar la corrida
en medio de un POST se tragaba como HTTP 0: se registraba como red caída y se reintentaba.

### Con qué se juzga cada una

Todas son **mapa a mano dentro de la propia prueba**: un `TurnTransport` falso que graba cada
request y devuelve respuestas guionadas, y un `sleep` falso que anota las esperas en vez de dormir.
Ninguna toca red, Android ni disco.

| # | Cómo se juzga sin tocar nada |
|---|---|
| 1 | Dos turnos guionados. El request 1 tiene `goal` y no tiene `session`; el request 2 tiene el `session` del response 1 y no tiene `goal` |
| 2 | Turno con `question`; se llama `inform("sí")` y luego `next(results=["ok","falló"])`. El request lleva `results` en ese orden e `inform:"sí"`; el turno siguiente ya no lleva `inform` |
| 3 | El turno 1 lleva una pantalla con PNG y nadie la pidió → no tiene la clave `screenshot`. Response con `needsScreenshot:false` → el request siguiente tampoco. Response con `needsScreenshot:true` → viaja como base64 del PNG, sin `data:` |
| 4 | Un response con las siete `kind` conocidas (y `scroll` en los dos sentidos: `down:false` sube) produce sus `AgentAction` con sus campos; un `kind:"teleport"` corrido por el `ExecutionEngine` real (con teléfono y MCP falsos) devuelve en el request siguiente `results:["acción desconocida: teleport"]` |
| 5 | Un response con los seis campos cargados → el `BrainTurn` los tiene idénticos |
| 6 | Guion `503, 0, 200` → 3 requests y esperas `[800, 1600]`; cada transitorio solo y primero, seguido de `200` → 2 requests; guion `504, 502, 408, 429` → falla tras 4 requests (el 504 abre: al final pasaba aunque no fuera transitorio); `503×4` con `error` en el cuerpo → el mensaje final lo trae; `429` con `Retry-After` 4 y 120 → esperas `[4000, 10000]`; un transporte que lanza «no protocol» → el mensaje trae la causa y no la key; `"actions":null` y `"args":{"hour":7}` con HTTP 200 → el mensaje dice `$.actions` / `$.actions[0].args` y trae el cuerpo; `401` → 1 request y mensaje "la key de graph no vale"; `200` con `error:"sin cupo"` → excepción "sin cupo" |
| 7 | El conjunto de claves del request es subconjunto de `{session, goal, userId, state, results, inform}`, y las de `state` lo son de las nueve de `ScreenState` en `Protocol.cs`: `{screen, uiContext, width, height, screenshot, apps, surfaceId, surfaceOrigin, surfacePathname}` |
| 8 | `GraphHeaders.build` con y sin email/deviceId; y el request grabado lleva esas cabeceras |
| 9 | `GraphCredentials.resolve` con prefs, con compilada, con ambas y con ninguna; un `GraphBrain` sin key no hace ningún request y falla con la línea que dice qué falta |
| 10 | `AndroidSurface.from("com.miui.calculator · Calculadora")` y sin título; `"com.android.settings · 设置"` no queda en `/` y su pathname, decodificado, vuelve a `设置`; `"Configurações"` decodificado conserva todas sus letras; los dos son segmentos de URL válidos |
| 11 | Response con `session` = un JSON con comillas escapadas, `ñ` y CJK → el request siguiente lleva la misma cadena, comparada tras decodificar el JSON |
| 12 | (a) Corrida 1 guionada hasta `done` con `session:"s-fin"`, luego `begin("abre los ajustes")` en la misma instancia → el request 3 no tiene `session` y sí `goal`. (b) `resume("s-fin")` y `begin(goal)` → el request 1 no tiene `session` y sí `goal` |
| 13 | Reloj `TestTimeSource` que avanzan el guion y las esperas. (a) `-1` → 1 request, 0 esperas, mensaje «no respondió a tiempo … no se reintentó para no cobrar dos veces». (b) `0` → se reintenta. (c) `504` que tarda 179,5 s → 2 requests, esperas `[800]`, el turno no pasa de 6 min y el mensaje lo dice. (d) Con 200 ms de turno por delante, un transporte que se cuelga se corta en el tope (real, `withTimeout` de 3 s alrededor para que un fallo no cuelgue el juez) |
| 14 | El transporte lanza `CancellationException` → sale tal cual de `next`, 1 request, 0 esperas y ninguna línea de log con «transitorio» |
| 15 | Un `listApps` que cuenta sus llamadas. Corrida de 3 turnos → 1 llamada y `state.apps` en los 3 requests. `begin` de un objetivo nuevo y un turno → 2 llamadas: la cuenta es por corrida, no por cerebro |

---

## Las fases

### Fase A — la capa pura en `core` (esta corrida)

Todo en `core/src/commonMain/kotlin/graph/core/graph/`, sin dependencias nuevas:

- `TurnProtocol.kt` — espejo `@Serializable` de `Protocol.cs`; `toBrainTurn()` y `toTurnState()`.
- `Surface.kt` — `AndroidSurface.from(screen)`.
- `GraphHeaders.kt` — las cabeceras de atribución.
- `GraphCredentials.kt` — prefs sobre compilada.
- `TurnTransport.kt` — la única puerta a la red; la app la implementa en la fase B.
- `GraphBrain.kt` — el `ThreadedBrain` que manda pantalla y recibe acciones.

Más `AgentAction.Unknown(kind)` en `Ports.kt` y su rama en el motor: es lo mínimo para que una
acción desconocida devuelva texto sin ejecutar nada (promesa 4). No existía una vía equivalente.

Pone verdes: **1-11**.

### Fase B — el cableado en `app` (hecha, 2026-09-14)

Lo que quedó, archivo por archivo:

- `app/…/GraphApp.kt` — `Provider.GRAPH`; la key se resuelve **prefs `graphApiKey` sobre
  `DEFAULT_GRAPH_API_KEY`** con `GraphCredentials` (no pasa por `RemoteConfig`: Graph ES el backend
  nuevo); `graphBaseUrl` = pref o `https://graph-eight-pied.vercel.app`; `userId`/`email` de la sesión
  Supabase si existe; `deviceId` = `Settings.Secure.ANDROID_ID`; `listApps` del `PackageManager`
  (desde R2: una consulta por corrida en `Dispatchers.IO`, promesa 15; antes, una por turno y en el
  hilo principal). Con `Falta`, `run()` no
  instancia el cerebro: loguea `[graph]`, lo dice por voz y devuelve la línea (promesa 9).
- `app/…/platform/GraphTransport.kt` — el `TurnTransport` real: `HttpURLConnection`, 30 s conectar
  / 5 min leer, cancelable (`disconnect()` al cancelar; desde R2, también en toda otra salida, lectura agotada incluida), cuerpo también en errores, status 0 cuando no
  conectó y -1 cuando conectó y la lectura se agotó (con `connect()` explícito, el timeout de conexión
  solo salta ahí dentro), `Retry-After` en segundos, y la cancelación sale como cancelación (R1).
- `app/…/ui/MainActivity.kt` — «Graph — cerebro remoto» en el selector de modelo; campos
  `graphApiKey` (password) y `graphBaseUrl` en el panel; «Guardar keys» los persiste.
- `app/build.gradle.kts` — `graphApiKey` de `apikey.properties` o env `GRAPH_API_KEY` (la misma
  variable que Windows) → `BuildConfig.DEFAULT_GRAPH_API_KEY`.
- `.githooks/pre-push` — el portero: compila release, corre `scripts/contrato.sh` y exige promesa
  propia a toda rama que cambie código. `docs/como-trabajamos.md` cuenta el método.

La prueba a mano dejó **un hallazgo del backend** (abajo). Decidido: se espeja Windows y entra como
**promesa 12** (cada objetivo abre un hilo nuevo en Graph); OpenAI y Gemini conservan su reanudación.

### Nivel 4 — corrida a mano (2026-09-14, Xiaomi M2101K7BL · Android 12 · APK release 0.42)

Proveedor elegido desde el panel de desarrollador (triple toque arriba-derecha → «Modelo» → «Graph»).
Tarea escrita en el campo «Pídeme algo». Evidencia de `adb logcat -s Graph:D`:

**Corrida 1 — «abre la calculadora»** (hilo fresco: la app recién instalada)

```
21:23:25.797 [graph] turno 1 · HTTP 200 · 5338ms · 1 acciones
21:23:25.798 [run] turno 1 · 7063ms · 📝 texto · "com.miui.home · Launcher del sistema" · decide: MCP launch_app {app=Calculadora}
21:23:27.108 [api] launch_app → com.miui.calculator
21:23:31.232 [graph] turno 2 · HTTP 200 · 3240ms · 0 acciones
21:23:31.233 [run] ■ 2 turnos · 1 acciones · 12s · Listo, calculadora abierta.
mCurrentFocus=Window{4c3fbde u0 com.miui.calculator/com.miui.calculator.cal.CalculatorActivity}
```

Graph aceptó la key y `X-Miracle-App: android_app` (HTTP 200 en todos los turnos); resolvió
`launch_app` con la etiqueta exacta del `apps` que mandó el cliente; la calculadora quedó en foco.

**Corrida 2 — «abre los ajustes»** (hilo REANUDADO: `resume = true` con el `session` de la corrida 1)

```
21:25:00.937 [graph] turno 1 · HTTP 200 · 4330ms · 1 acciones
21:25:00.938 [run] turno 1 · … · decide: MCP launch_app {app=Calculator}
21:25:02.288 [api] no encontré la app "Calculator"
21:25:07.695 [graph] turno 2 · HTTP 200 · 4300ms · 0 acciones
21:25:16.911 [graph] turno 3 · HTTP 200 · 6599ms · 1 acciones   (computer-use tap)
21:25:27.362 [graph] turno 4 · HTTP 200 · 6894ms · 2 acciones   (tap + wait)
21:25:35.154 [graph] turno 5 · HTTP 200 · 4395ms · 0 acciones
21:25:35.154 [run] ■ 5 turnos · 4 acciones · 39s · Ahora sí: la calculadora está abierta.
mCurrentFocus=Window{19cbf3c u0 com.miui.calculator/com.miui.calculator.cal.CalculatorActivity}
```

**Hallazgo (el riesgo que esta spec dejó para medir):** con `session` del hilo anterior **y**
`goal` nuevo en el mismo request, Graph siguió el hilo viejo e ignoró el objetivo nuevo: abrió la
calculadora (esta vez con la etiqueta en inglés, «Calculator», que no existe en el teléfono, y luego
por computer-use) en vez de los ajustes. Windows no tiene este problema porque **nunca reanuda**:
`AgentLoop.cs:96` arranca `session = null` en cada objetivo y el `goal` viaja solo cuando
`session == null`. El Android sí reanuda (`GraphApp.newSession(resume = true)`) para dar continuidad
entre activaciones, y esa continuidad es la que Graph no honra. Una sola corrida; suficiente para
no cerrar los ojos, insuficiente para llamarlo ley. **Decidido:** espejar Windows; entra como
**promesa 12** con su test antes que el código. OpenAI y Gemini no cambian: su reanudación queda igual.

**Corrida nueva con la promesa 12 (2026-09-14, mismo teléfono, APK release 0.42 con el fix).** Dos
prompts seguidos en la misma sesión de la app, sin reinstalar entre ellos; entre uno y otro se volvió
a la app con `am start`. `GraphBrain.begin()` descarta cualquier `session` y `resume()` es no-op; el
log dice si el turno abre hilo (`session=nuevo`) o lo sigue (`session=continúa`), nunca su contenido.

```
21:38:34.674 [run] ▶ "abre la calculadora"
21:38:40.082 [graph] turno 1 · session=nuevo · HTTP 200 · 3886ms · 1 acciones
21:38:40.083 [run] turno 1 · 5408ms · 📝 texto · "com.miui.home · Launcher del sistema" · decide: MCP launch_app {app=Calculadora}
21:38:41.279 [api] launch_app → com.miui.calculator
21:38:48.033 [graph] turno 2 · session=continúa · HTTP 200 · 5808ms · 0 acciones
21:38:48.035 [run] ■ 2 turnos · 1 acciones · 13s · Calculadora abierta. ✨

21:42:38.094 [run] ▶ "abre los ajustes"
21:42:43.353 [graph] turno 1 · session=nuevo · HTTP 200 · 4327ms · 1 acciones
21:42:43.354 [run] turno 1 · 5258ms · 📝 texto · "com.miui.calculator" · decide: MCP open_settings {section=general}
21:42:43.437 [api] Intent android.settings.SETTINGS → lanzado
21:42:48.269 [graph] turno 2 · session=continúa · HTTP 200 · 4034ms · 0 acciones
21:42:48.270 [run] ■ 2 turnos · 1 acciones · 10s · Listo, ajustes abiertos.
mCurrentFocus=Window{55a6616 u0 com.android.settings/com.android.settings.MiuiSettings}
```

Con hilo nuevo, la corrida 2 abrió Ajustes en 2 turnos y 10 s (antes: 5 turnos, 39 s y la calculadora
otra vez). El hilo se sigue usando dentro de cada corrida (`session=continúa` en el turno 2).

Efecto colateral visto, fuera de esta spec: el destilador de memoria y la anticipación siguen
llamando a Gemini y hoy devuelven `HTTP 429` (créditos agotados). No afectan al turno de Graph;
son la fase C.

### Fase C — paridad y retiro (spec aparte)

Cuando GRAPH haga en el teléfono lo que hoy hacen OPENAI y GEMINI, esos dos se retiran junto con
sus keys horneadas. No se planifica aquí: se planifica cuando se haya medido.

---

## Diferencias deliberadas con Windows

| Qué | Windows | Android | Por qué |
|---|---|---|---|
| Reintentos en HTTP transitorio | clasifica pero **no** reintenta | hasta **3** reintentos, espera 800 / 1600 / 3200 ms (429 con `Retry-After`: eso, hasta 10 s), todo dentro de un tope de **6 min por turno**; una lectura agotada (-1) **no** se reintenta | la red móvil se cae al cambiar de celda o de wifi a datos; un 0 o un 503 casi siempre sale bien al segundo intento. En escritorio no vale la espera. La lectura agotada no, porque Graph pudo haber cobrado el turno |
| `X-Miracle-App` | `windows_app` | `android_app` | atribución del consumo por plataforma |
| `X-Miracle-Device-Id` | no viaja | viaja si existe | en el teléfono no hay "máquina": el id de dispositivo es lo que separa dos usuarios con el mismo email |
| Superficie | `SurfaceLocator` (UIA) | `android://<paquete>` + `/<pantalla>`; la pantalla en minúsculas, espacios → `-` y percent-encoding UTF-8 de lo que no es ascii (desde R1: antes se tiraba, y «设置» quedaba en `/`) | mismo contrato (`surfaceId/Origin/Pathname`), distinta fuente |
| Modo legacy (`u-windows-backend`) | existe | **no** existe | el Android nunca habló con ese backend; no hay a qué volver |

---

## Lo que NO entra, y por qué

- **Las rutas de enseñanza** (`/api/v1/teach/*`): son otra conversación con otro contrato.
- **Telemetría** (`TelemetryBus`): en Android no existe y no se inventa aquí.
- **Retirar OPENAI/GEMINI**: fase C, cuando haya paridad medida.
- **Cambiar el motor** más allá de la rama para `Unknown`: el motor no sabe de Graph, y así sigue.

## Límites conocidos

- **El `goal` puede llevar texto armado por el cliente**, no solo lo que dijo el usuario: el bloque
  «CONTEXTO INMEDIATO» y el reencaminado de `GraphApp.kt`. No es system prompt ni catálogo de
  herramientas (la promesa 7 sigue en pie), pero es el cliente decidiendo qué lee el modelo. Se
  revisa en el sprint 5.

## Riesgo

El mayor es que Graph cambie el contrato y este cliente no se entere: por eso `ignoreUnknownKeys`
(un campo nuevo no rompe) y por eso el `error` del cuerpo se lee siempre, también con HTTP 200.
Lo que no cubre este contrato es la semántica del backend (qué hace Graph con `session` + `goal`
a la vez, por ejemplo): eso se midió en la fase B contra el backend real — ver «Nivel 4».
