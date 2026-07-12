# Arquitectura — la separación cliente / cerebro

Este documento explica **cómo** se parte en dos el asistente y **por qué** el corte queda donde queda.

## El problema que resuelve (heredado de Android)

En el app Android, `core/` es Kotlin Multiplatform "limpio", pero la inteligencia real
(`OpenAiBrain`/`GeminiBrain`, el catálogo `Mcp`, `PassiveLearning`, `GeminiWorkflow`, `KnowledgeGraph`,
los prompts) se compila **dentro del APK**. Aunque el diseño era "multiplataforma", en la práctica el
**cerebro viaja en el binario del cliente**: se descompila y se copia.

Para Windows invertimos la regla desde el día uno: **el cliente no piensa**. Solo hace I/O (leer el
árbol de UI con UIA, capturar pantalla, mover ratón/teclado, hablar). El cerebro vive en este backend.

## Dónde se corta el bucle

El `ExecutionEngine.run` del core Android corría un bucle **entero en el dispositivo**:

```
loop:
  state   = phone.state(withScreenshot)      // I/O local
  turn    = brain.next(state, results)       // ← inteligencia (HTTP a OpenAI, en el APK)
  results = execute(turn.actions)            // I/O local
```

Aquí partimos ese bucle por la línea `brain.next`:

```
CLIENTE (Windows, tonto)                     BACKEND (Vercel, el cerebro)
─────────────────────────                    ─────────────────────────────
state = uia.Read() [+ screenshot]
POST /api/agent/turn { state, results } ───► resolveTurn:
                                               tools = base + aprendidas + workflows
                                               memory = memoryStore.forPrompt(user)
                                               turn  = openaiBrain(session, state, ...)
              ◄─── { session, actions, ... }   (session firmada, stateless)
execute(actions)  // tap/type/mcp local
(repite)
```

- La **decisión** (qué tocar, qué herramienta MCP, qué preguntar) es 100% servidor.
- La **ejecución** (cómo se toca en Windows, cómo se lee el árbol) es 100% cliente.
- El **contrato** entre ambos es `domain/actions.ts` — nada más cruza.

## Por qué el cliente conduce el bucle (y no el servidor)

Vercel son funciones **serverless**: sin estado entre requests, sin conexiones largas. Si el servidor
condujera el bucle necesitaría mantener viva la sesión y hacer streaming bidireccional — mal encaje.
En cambio, que el **cliente conduzca** y el servidor resuelva **un turno stateless por request** encaja
perfecto: cada `POST /turn` es independiente. El estado del cerebro (hilo de OpenAI, llamadas
pendientes) viaja firmado en `session`, así que dos requests seguidos no comparten memoria de proceso.

## Mapa al core Kotlin

| Android (`core`/`app`)                     | Backend (este repo)                          |
|--------------------------------------------|----------------------------------------------|
| `domain/Ports.kt` (Brain, AgentAction, BrainTurn) | `domain/actions.ts`                   |
| `domain/Model.kt` (`Mcp`, `McpTool`)       | `domain/mcp.ts` (solo declaración)           |
| `application/Engine.kt` (`ExecutionEngine`) | `application/engine.ts` + el bucle del cliente |
| `platform/OpenAiBrain.kt`                  | `brain/openai.ts` + `brain/prompt.ts`        |
| `platform/MemoryStore.kt`                  | `memory/store.ts`                            |
| `core/**` Learning/Workflow + `GeminiLearning`/`GeminiWorkflow` | `learning/workflows.ts`  |
| `platform/GraphAccessibilityService` (Phone/Gestures) | **el cliente Windows** (UIA/SendInput) |

La diferencia esencial: en Android las columnas izquierda y derecha **eran el mismo binario**. Aquí
están separadas por la red, y solo la izquierda-de-ejecución (UIA/SendInput) viaja al usuario.

## Puntos de extensión (fases siguientes)

- **Persistencia**: sustituir `InMemoryMemoryStore`/`InMemoryLearningStore` por Supabase/KV/Neo4j en
  `container.ts`. El cerebro y el cliente no cambian.
- **Enseñanza pasiva/activa**: el cliente ya envía el árbol de UI cada turno; añadir un endpoint
  `/api/learn` que reciba trazas y las post-procese (como `GeminiLearning`/`GeminiWorkflow`).
- **Workflows encadenados**: hoy el catálogo los declara; el `WorkflowRunner` server-side que encadena
  steps (subconsciente ↔ consciente) se añade en `application/` devolviendo `actions` compuestas.
