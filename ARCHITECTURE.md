# Arquitectura

Clean architecture: núcleo puro y multiplataforma + una capa de adaptadores por plataforma.

```
┌──────────────────────────────────────────────────────────────┐
│ core/ (Kotlin Multiplatform · commonMain · sin Android)      │
│                                                              │
│  domain/                                                     │
│    ScreenState                                               │
│    Mcp · McpTool · McpParam   ← protocolo MCP (gestos)       │
│    Workflow · WorkflowStep    ← el puente entre las dos vías │
│    Phone · Gestures           ← superficie del teléfono      │
│    Brain · AgentAction · BrainTurn ← el cerebro (Gemini)     │
│    Voice · UserChannel · GraphLog                            │
│                                                              │
│  application/                                                │
│    ExecutionEngine  ← bucle mixto: gesto MCP ↔ computer-use  │
│    WorkflowRecorder ← graba el paso a paso en la enseñanza   │
│    WorkflowRunner   ← ejecuta steps subconscientes↔conscientes│
└──────────────────────────────────────────────────────────────┘
                  ▲ implementan los puertos
┌──────────────────────────────────────────────────────────────┐
│ app/ (Android)                                               │
│  GraphAccessibilityService → Phone + Gestures                │
│  GeminiBrain               → Brain (Interactions API)        │
│  FloatingBubble / MainActivity → Voice · UserChannel · chat  │
│  StopReceiver              → detener desde la notificación    │
└──────────────────────────────────────────────────────────────┘
```

## El motor mixto

`ExecutionEngine.run(goal)` corre un solo bucle. En cada turno `Brain.next(state)` devuelve acciones que pueden ser:

- **`AgentAction.Mcp(tool, args)`** → se despacha a `Mcp.call()` (un gesto declarado).
- **primitivas de computer-use** (`Tap`, `Type`, `OpenApp`, `Scroll`, `Swipe`, `Key`, `Wait`) → se ejecutan contra `Phone`.

El propio modelo elige la vía: `GeminiBrain` le declara, en el mismo turno, la herramienta nativa `computer_use` (environment mobile, coordenadas 0-1000) **y** las herramientas MCP como funciones custom (junto a `ask_user` y `speak`). Así "ir al home" sale por un gesto MCP limpio y "tocar este botón" por computer-use.

## El protocolo MCP

`Mcp` (core) es el catálogo de capacidades: cada `McpTool` tiene nombre único, descripción, esquema de parámetros (`McpParam`, con enumeraciones) y un ejecutor que llama a un `Gestures`. Expone:

- `tools` → para declararlas al modelo como funciones.
- `docMarkdown()` → documentación legible del protocolo (semilla de los "workflows powered by MCP" de v2).
- `call(name, args)` → despacho de una llamada.

Hoy los gestos están hardcodeados contra la superficie de accesibilidad; las herramientas aprendidas del árbol de UI y los workflows se añaden al catálogo sin tocar el motor.

## Workflows: el puente consciente ↔ subconsciente

El asistente está inspirado en el ser humano: una misma tarea se ejecuta alternando entre lo
**consciente** (mirar y decidir: Gemini con computer-use) y lo **subconsciente** (lo que ya conoce:
MCP). El **workflow** es el punto de enlace entre esos dos mundos: un flujo de **steps** que se
concatenan para completar una tarea (primero a, después b, después c), donde la **unidad del step es
el clic** sobre un elemento del árbol de UI.

- **Creación** — ambos modos de enseñanza son el punto de creación. Mientras el asistente ve el paso
  a paso, `WorkflowRecorder` (core) guarda el workflow step by step. La enseñanza se cierra al salir
  de la app (pasiva) o al terminar la grabación (activa) y la traza cruda pasa al post-procesamiento.
- **Post-procesamiento** — `GeminiWorkflow` (app, implementa `WorkflowBrain`) es quien organiza el
  workflow de forma efectiva: limpia los pasos basura e innecesarios, organiza los necesarios, agrega
  una **nota de contexto opcional** a los steps que decida (no es obligatoria), y asigna la vía de
  cada uno: los steps cuyo elemento quedó bien detectado en el árbol de UI se marcan **subconscientes**
  (MCP listo); los que no se lograron detectar quedan para ejecutarse de forma **consciente**.
- **Ejecución** — la ejecución MCP está plasmada encima de los workflows: cada workflow se declara
  como herramienta MCP (`workflow_*`) y el modelo la invoca entera. `WorkflowRunner` (core) recorre
  los steps haciendo **switch** entre vías según avanza: los subconscientes tocan por árbol de UI
  (`UiPlayer.tapLabel`, sin pantalla) y los conscientes corren un motor acotado a ese único step. Si
  un clic subconsciente falla, el step cae en caliente a la vía consciente, como un humano que "despierta"
  cuando algo no está donde lo esperaba.
- **Persistencia** — `WorkflowRepo` (files/workflows/) + copia en la nube (`graph_workflows`).

## `Phone` / `Gestures`: la superficie por plataforma

`Phone` son las primitivas de computer-use (tap/type/openApp/scroll/swipe/pressKey + `state()` con screenshot). `Gestures` son los gestos semánticos que MCP expone (home, appDrawer, notifications, panHome, scrollMenu). En Android ambos los implementa `GraphAccessibilityService` vía `dispatchGesture` y `performGlobalAction`. Para otra plataforma (DOM, macOS AX, Windows UIA) se implementan estos dos puertos sin tocar `core/`.

## Enseñanza: pasiva y activa

Sobre el motor mixto viven dos capas de enseñanza que alimentan la capa MCP (y ambas son, además, el
punto de creación de los workflows: ver arriba):

- **Pasiva** — se activa en la **app principal**. El `GraphAccessibilityService` observa clics + árbol de UI y `PassiveLearning` (core) consolida el mapa MCP de cada app al salir de ella (`GeminiLearning` en app). Mantener oprimido el 🎓 de la burbuja dibuja los contornos de lo aprendido (`HighlightOverlay`).
- **Activa** — el **🎓 de la burbuja**, al tocarlo. `ActiveLearning` (app) lanza `ScreenTeachActivity` (permiso de captura) → `ScreenTeachService` graba pantalla + audio (MediaProjection + MediaRecorder). Al terminar, `GeminiVideo` sube el mp4 a la Files API de Gemini y lo estructura como **conocimiento textual por app**, guardado en `MemoryStore` (memoria durable) y consumido fielmente por el motor al operar esa app. **Fase 1: solo texto, sin árbol de UI.**

## Fuera de esta fase (se integran después)

Control por terminal y la estructuración del **árbol de UI** desde el video de la enseñanza activa
(hoy el paso a paso activo se captura por accesibilidad durante la grabación; el video aporta el
conocimiento textual).
