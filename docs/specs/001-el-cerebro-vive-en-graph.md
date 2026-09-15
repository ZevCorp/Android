# Plan de implementación: el cerebro vive en Graph — Android pasa a ser cliente tonto

Estado: **fase A en curso** (2026-09-14) · Nace de leer el cliente Windows (`U-Windows-App`) que ya
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

**La que cierra el asunto es la 7.** Mientras el cliente mande prompt o catálogo, no es tonto: es
el cerebro viejo con otro transporte. Las otras diez protegen el camino; la 7 es la que define qué
es este proveedor.

### Con qué se juzga cada una

Las once son **mapa a mano dentro de la propia prueba**: un `TurnTransport` falso que graba cada
request y devuelve respuestas guionadas, y un `sleep` falso que anota las esperas en vez de dormir.
Ninguna toca red, Android ni disco.

| # | Cómo se juzga sin tocar nada |
|---|---|
| 1 | Dos turnos guionados. El request 1 tiene `goal` y no tiene `session`; el request 2 tiene el `session` del response 1 y no tiene `goal` |
| 2 | Turno con `question`; se llama `inform("sí")` y luego `next(results=["ok","falló"])`. El request lleva `results` en ese orden e `inform:"sí"`; el turno siguiente ya no lleva `inform` |
| 3 | Response con `needsScreenshot:false` → el request siguiente no tiene la clave `screenshot`. Response con `needsScreenshot:true` → viaja como base64 del PNG, sin `data:` |
| 4 | Un response con las siete `kind` conocidas produce las siete `AgentAction` con sus campos; un `kind:"teleport"` corrido por el `ExecutionEngine` real (con teléfono y MCP falsos) devuelve en el request siguiente `results:["acción desconocida: teleport"]` |
| 5 | Un response con los seis campos cargados → el `BrainTurn` los tiene idénticos |
| 6 | Guion `503, 503, 200` → 3 requests y esperas `[800, 1600]`; guion `503×4` → falla tras 4 requests; `401` → 1 request y mensaje "la key de graph no vale"; `200` con `error:"sin cupo"` → excepción "sin cupo" |
| 7 | El conjunto de claves del request es subconjunto de `{session, goal, userId, state, results, inform}` |
| 8 | `GraphHeaders.build` con y sin email/deviceId; y el request grabado lleva esas cabeceras |
| 9 | `GraphCredentials.resolve` con prefs, con compilada, con ambas y con ninguna; un `GraphBrain` sin key no hace ningún request y falla con la línea que dice qué falta |
| 10 | `AndroidSurface.from("com.miui.calculator · Calculadora")` y sin título |
| 11 | Response con `session` = un JSON con comillas escapadas, `ñ` y CJK → el request siguiente lleva la misma cadena, comparada tras decodificar el JSON |

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

### Fase B — el cableado en `app` (otra corrida)

`Provider.GRAPH` en el enum y en el panel de desarrollador; `graphApiKey` y `graphBaseUrl` en
`apikey.properties`/prefs; un `TurnTransport` con `HttpURLConnection` (timeout 5 min, como
Windows); `X-Miracle-Device-Id` desde `Settings.Secure.ANDROID_ID`; `listApps` desde el
`PackageManager`; el portero (`pre-push`) corre `scripts/contrato.sh`. Sin promesas nuevas salvo que
la prueba a mano las pida.

### Fase C — paridad y retiro (spec aparte)

Cuando GRAPH haga en el teléfono lo que hoy hacen OPENAI y GEMINI, esos dos se retiran junto con
sus keys horneadas. No se planifica aquí: se planifica cuando se haya medido.

---

## Diferencias deliberadas con Windows

| Qué | Windows | Android | Por qué |
|---|---|---|---|
| Reintentos en HTTP transitorio | clasifica pero **no** reintenta | hasta **3** reintentos, espera 800 / 1600 / 3200 ms | la red móvil se cae al cambiar de celda o de wifi a datos; un 0 o un 503 casi siempre sale bien al segundo intento. En escritorio no vale la espera |
| `X-Miracle-App` | `windows_app` | `android_app` | atribución del consumo por plataforma |
| `X-Miracle-Device-Id` | no viaja | viaja si existe | en el teléfono no hay "máquina": el id de dispositivo es lo que separa dos usuarios con el mismo email |
| Superficie | `SurfaceLocator` (UIA) | `android://<paquete>` + `/<pantalla>` | mismo contrato (`surfaceId/Origin/Pathname`), distinta fuente |
| Modo legacy (`u-windows-backend`) | existe | **no** existe | el Android nunca habló con ese backend; no hay a qué volver |

---

## Lo que NO entra, y por qué

- **Las rutas de enseñanza** (`/api/v1/teach/*`): son otra conversación con otro contrato.
- **Telemetría** (`TelemetryBus`): en Android no existe y no se inventa aquí.
- **Retirar OPENAI/GEMINI**: fase C, cuando haya paridad medida.
- **Cambiar el motor** más allá de la rama para `Unknown`: el motor no sabe de Graph, y así sigue.

## Riesgo

El mayor es que Graph cambie el contrato y este cliente no se entere: por eso `ignoreUnknownKeys`
(un campo nuevo no rompe) y por eso el `error` del cuerpo se lee siempre, también con HTTP 200.
Lo que no cubre este contrato es la semántica del backend (qué hace Graph con `session` + `goal`
a la vez, por ejemplo): eso se mide en la fase B contra el backend real.
