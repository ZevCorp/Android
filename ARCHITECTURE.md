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

## Fuera de v1 (se integran después)

Modo enseñanza, compartir pantalla, workflows y control por terminal. La base actual está pensada para crecer: los workflows futuros serán capacidades MCP autogeneradas y documentadas por el asistente.
