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
│  SupabaseAuth              → cuenta del usuario (sesión)     │
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
- **Aprendizaje continuo (reconexión bidireccional)** — workflows y MCPs nacen y se refinan
  constantemente, en cualquier orden, y el sistema los mantiene conectados siempre:
  - *Workflow nace y ya existían MCPs*: `structure` recibe el catálogo de MCPs previos; los clics que
    encajan con elementos ya mapeados nacen subconscientes aunque esta pasada no los detectara.
  - *MCP nace/mejora y ya existían workflows*: al consolidar un mapa, `PassiveLearning.onLearned`
    dispara `reconcile` (post-procesamiento especializado), que sube a subconscientes los pasos
    conscientes de los workflows de esa app que el catálogo nuevo ya cubre — sin tocar orden,
    cantidad ni acciones.
  - *Ambos en la misma pasada*: al salir de una app la secuencia es consolidar el MCP → reconectar
    workflows previos → estructurar el workflow (que ya ve el catálogo fresco). Sin carreras.
- **Ejecución** — la ejecución MCP está plasmada encima de los workflows: cada workflow se declara
  como herramienta MCP (`workflow_*`) y el modelo la invoca entera. `WorkflowRunner` (core) recorre
  los steps haciendo **switch** entre vías según avanza: los subconscientes tocan por árbol de UI
  (`UiPlayer.tapLabel`, sin pantalla) y los conscientes corren un motor acotado a ese único step. Si
  un clic subconsciente falla, el step cae en caliente a la vía consciente, como un humano que "despierta"
  cuando algo no está donde lo esperaba.
- **Persistencia** — `WorkflowRepo` (files/workflows/) + copia en la nube (`graph_workflows`).
- **Grafo de conocimiento (Neo4j)** — `KnowledgeGraph` proyecta los aprendizajes del árbol de UI y
  los workflows a Neo4j Aura (Cypher por HTTP, credenciales en la app): `(:Workflow)-[:HAS_STEP]->`
  `(:Step)-[:NEXT]->(:Step)`, `(:Step)-[:TAPS]->(:Element)-[:IN_APP]->(:App)`,
  `(:McpMap)-[:KNOWS]->(:Element)`. Offline-first: el disco local manda; el grafo es la proyección
  navegable del conocimiento (push al guardar + sync completo al arrancar). Neo4j no corre embebido
  en Android: por eso la proyección es remota, con el mismo patrón que CloudSync.
- **Visibilidad** — card "Workflows" en la app principal (paso a paso con vía 🧩/👁 por step, borrar)
  y una **statusbar negra** durante la ejecución que muestra la vía en vivo (👁 consciente /
  🧩 subconsciente); tocarla detiene.

## Resiliencia ante la sobrecarga de Google

La API de Gemini devuelve `429/5xx` cuando el modelo está *"experiencing high demand"* — errores que
Google mismo marca como temporales (*"please try again later"*). `GeminiHttp.withRetry` envuelve todas
las llamadas (motor `GeminiBrain`, destiladores `GeminiJson`, consolidación `GeminiLearning`, video
`GeminiVideo`) y reintenta con backoff exponencial (0.8s → 8s). Sin esto, cada bache de demanda hacía
"fallar todo" al primer intento; con reintento, la mayoría se recupera sola. Un `5xx` significa que el
servidor no creó la interacción, así que reintentar el mismo POST no duplica acciones.

## `Phone` / `Gestures`: la superficie por plataforma

`Phone` son las primitivas de computer-use (tap/type/openApp/scroll/swipe/pressKey + `state()` con screenshot). `Gestures` son los gestos semánticos que MCP expone (home, appDrawer, notifications, panHome, scrollMenu). En Android ambos los implementa `GraphAccessibilityService` vía `dispatchGesture` y `performGlobalAction`. Para otra plataforma (DOM, macOS AX, Windows UIA) se implementan estos dos puertos sin tocar `core/`.

## Enseñanza: pasiva y activa

Sobre el motor mixto viven dos capas de enseñanza que alimentan la capa MCP (y ambas son, además, el
punto de creación de los workflows: ver arriba):

- **Pasiva** — se activa en la **app principal**. El `GraphAccessibilityService` observa clics + árbol de UI y `PassiveLearning` (core) consolida el mapa MCP de cada app al salir de ella (`GeminiLearning` en app). Mantener oprimido el 🎓 de la burbuja dibuja los contornos de lo aprendido (`HighlightOverlay`). Mientras observa, `LearningInquiry` decide con la **memoria personal a la vista** si intervenir por voz: proponer una acción (mindset propositivo en tiempo real; las instrucciones del usuario en su knowledge-base mandan), preguntar una duda valiosa, o callar. Una propuesta aceptada va al motor de ejecución.
- **Activa** — el **🎓 de la burbuja**, al tocarlo. `ActiveLearning` (app) lanza `ScreenTeachActivity` (permiso de captura) → `ScreenTeachService` graba pantalla + audio (MediaProjection + MediaRecorder). Al terminar, `GeminiVideo` sube el mp4 a la Files API de Gemini y lo estructura como **conocimiento textual por app**, guardado en `MemoryStore` (memoria durable) y consumido fielmente por el motor al operar esa app. **Fase 1: solo texto, sin árbol de UI.**

## Cuentas: quién es dueño de cada capa de conocimiento

Las dos capas de enseñanza tienen **dueños distintos**, y `SupabaseAuth` (email + contraseña, sesión en prefs con auto-refresh) es la frontera:

- **`LearnedToolRepo` → `graph_learned_tools`**: el mapa de UI de las apps es **compartido entre todos los usuarios** (lectura pública; escribir requiere sesión). Aprenderlo una vez sirve para todos.
- **`MemoryStore` → `graph_memory`**: la memoria durable es **personal**. En el servidor, RLS por `user_id` (solo el dueño lee/escribe sus notas); en el teléfono, un archivo por cuenta (`memory-<userId>.json`) y un archivo anónimo (`memory.json`) para cuando no hay sesión, cuyas notas se **adoptan** a la cuenta al iniciar sesión (`MemoryStore.adoptAnonymous`, orquestado por `GraphApp.sessionChanged`).

`CloudSync` adjunta el token de la sesión a todas las llamadas (`CloudSync.userToken`); sin sesión, la memoria no viaja a la nube y los aprendizajes de UI solo se leen.

## Fuera de esta fase (se integran después)

Control por terminal y la estructuración del **árbol de UI** desde el video de la enseñanza activa
(hoy el paso a paso activo se captura por accesibilidad durante la grabación; el video aporta el
conocimiento textual).
