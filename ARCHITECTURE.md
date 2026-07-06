# Arquitectura

Clean architecture: núcleo puro y multiplataforma + una capa de adaptadores por plataforma.

```
┌──────────────────────────────────────────────────────────────┐
│ core/ (Kotlin Multiplatform · commonMain · sin Android)      │
│                                                              │
│  domain/                                                     │
│    ScreenState                                               │
│    Mcp · McpTool · McpParam   ← protocolo MCP (gestos)       │
│    Phone · Gestures           ← superficie del teléfono      │
│    Brain · AgentAction · BrainTurn ← el cerebro (Gemini)     │
│    Voice · UserChannel · GraphLog                            │
│                                                              │
│  application/                                                │
│    ExecutionEngine  ← bucle mixto: gesto MCP ↔ computer-use  │
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

Hoy los gestos están hardcodeados contra la superficie de accesibilidad; en v2 el asistente añadirá herramientas aprendidas del árbol de UI sin tocar el motor.

## `Phone` / `Gestures`: la superficie por plataforma

`Phone` son las primitivas de computer-use (tap/type/openApp/scroll/swipe/pressKey + `state()` con screenshot). `Gestures` son los gestos semánticos que MCP expone (home, appDrawer, notifications, panHome, scrollMenu). En Android ambos los implementa `GraphAccessibilityService` vía `dispatchGesture` y `performGlobalAction`. Para otra plataforma (DOM, macOS AX, Windows UIA) se implementan estos dos puertos sin tocar `core/`.

## Enseñanza: pasiva y activa

Sobre el motor mixto viven dos capas de enseñanza que alimentan la capa MCP:

- **Pasiva** — se activa en la **app principal**. El `GraphAccessibilityService` observa clics + árbol de UI y `PassiveLearning` (core) consolida el mapa MCP de cada app al salir de ella (`GeminiLearning` en app). Mantener oprimido el 🎓 de la burbuja dibuja los contornos de lo aprendido (`HighlightOverlay`).
- **Activa** — el **🎓 de la burbuja**, al tocarlo. `ActiveLearning` (app) lanza `ScreenTeachActivity` (permiso de captura) → `ScreenTeachService` graba pantalla + audio (MediaProjection + MediaRecorder). Al terminar, `GeminiVideo` sube el mp4 a la Files API de Gemini y lo estructura como **conocimiento textual por app**, guardado en `MemoryStore` (memoria durable) y consumido fielmente por el motor al operar esa app. **Fase 1: solo texto, sin árbol de UI.**

## Cuentas: quién es dueño de cada capa de conocimiento

Las dos capas de enseñanza tienen **dueños distintos**, y `SupabaseAuth` (email + contraseña, sesión en prefs con auto-refresh) es la frontera:

- **`LearnedToolRepo` → `graph_learned_tools`**: el mapa de UI de las apps es **compartido entre todos los usuarios** (lectura pública; escribir requiere sesión). Aprenderlo una vez sirve para todos.
- **`MemoryStore` → `graph_memory`**: la memoria durable es **personal**. En el servidor, RLS por `user_id` (solo el dueño lee/escribe sus notas); en el teléfono, un archivo por cuenta (`memory-<userId>.json`) y un archivo anónimo (`memory.json`) para cuando no hay sesión, cuyas notas se **adoptan** a la cuenta al iniciar sesión (`MemoryStore.adoptAnonymous`, orquestado por `GraphApp.sessionChanged`).

`CloudSync` adjunta el token de la sesión a todas las llamadas (`CloudSync.userToken`); sin sesión, la memoria no viaja a la nube y los aprendizajes de UI solo se leen.

## Fuera de esta fase (se integran después)

Workflows, control por terminal, y la estructuración del **árbol de UI** en la enseñanza activa. La base está pensada para crecer: los workflows futuros serán capacidades MCP autogeneradas y documentadas por el asistente.
