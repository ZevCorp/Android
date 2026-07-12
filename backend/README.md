# Ü · Backend (el cerebro)

El **cerebro** del asistente Ü para Windows, desplegable en **Vercel**. Aquí vive TODA la inteligencia
—el modelo con computer-use, el catálogo MCP, los prompts, la memoria del usuario, el aprendizaje y
los workflows— **separada del cliente**. El cliente Windows es tonto; este servicio piensa por él.

> **Por qué existe esta separación.** En el app Android, el cerebro (GeminiBrain/OpenAiBrain, el
> catálogo MCP, el aprendizaje, los prompts) se compila **dentro del APK**. Descompilarlo revela la
> innovación completa. Aquí, el cliente Windows no contiene ni una línea del cerebro: solo sabe pedir
> decisiones a este backend y ejecutarlas. La competencia que descompile el `.exe` no encuentra nada.

## El contrato (una sola ruta)

`POST /api/agent/turn` — un turno del bucle de ejecución. El cliente lo llama en bucle:

- **Primer turno:** `{ goal, state }` (sin `session`).
- **Siguientes:** `{ session, state, results, inform? }` (echa el blob opaco del turno anterior).

Devuelve `{ session, actions[], narration, speech?, question?, text, done, needsScreenshot, intents[] }`.

- `state` = lo que el cliente ve: `screen`, `uiContext` (árbol de UI leído con UIA), `width/height`,
  `screenshot?` (PNG base64, solo si el turno anterior pidió computer-use), `apps?`.
- `actions` = lo que el cliente debe ejecutar: `tap/type/scroll/swipe/key/wait` (computer-use) o
  `mcp` (llamada a una herramienta por nombre, que el cliente despacha a su ejecutor local).
- `session` = estado del cerebro firmado con HMAC (opaco para el cliente). El backend es **stateless**:
  todo el hilo (el `previous_response_id` de OpenAI, las llamadas pendientes, el objetivo) viaja aquí.

`GET /api/health` — sonda de salud y reporte de configuración (sin secretos).

## Arquitectura

```
api/agent/turn.ts        ← handler HTTP (Vercel Function)
api/health.ts
src/
  domain/
    actions.ts           ← el contrato: ScreenState, Action, BrainTurn
    mcp.ts               ← catálogo MCP (SOLO declaración; los ejecutores viven en el cliente)
    session.ts           ← blob de sesión opaco (HMAC), para ser stateless
  brain/
    openai.ts            ← el cerebro (Responses API, computer-use + MCP) — port de OpenAiBrain.kt
    prompt.ts            ← el system prompt y las reglas (la parte más copiable, aquí protegida)
  application/
    engine.ts            ← orquesta UN turno (ensambla catálogo + memoria + aprendizajes)
  memory/store.ts        ← knowledge-base personal (por usuario) — hoy en memoria
  learning/workflows.ts  ← herramientas aprendidas + workflows — hoy en memoria
  config.ts · container.ts
```

Ver `ARCHITECTURE.md` para el detalle de por qué el bucle se parte cliente/servidor y cómo se mapea
al `core` Kotlin del app Android.

## Desarrollo

```bash
npm install
npm run typecheck          # tsc --noEmit
npm run dev                # vercel dev (necesita la CLI de Vercel y .env.local)
```

Crea `.env.local` a partir de `.env.example` con al menos `OPENAI_API_KEY`.

## Despliegue en Vercel

1. `npm i -g vercel && vercel link` (o importa el repo en vercel.com).
2. Configura las variables de entorno del proyecto (Settings → Environment Variables):
   `OPENAI_API_KEY` (obligatoria), `MODEL`, `EFFORT`, `SESSION_SECRET`, `CLIENT_TOKEN` (opcional).
3. `npm run deploy` (o push a la rama conectada).
4. El cliente Windows apunta su `BackendUrl` a `https://<tu-deploy>.vercel.app`.

## Estado (primera construcción)

- ✅ Bucle de ejecución mixto completo (computer-use + MCP) con el cerebro OpenAI.
- ✅ Catálogo MCP base (gestos de Windows + acciones de sistema) declarado al modelo.
- ✅ Memoria y aprendizaje como interfaces con stores en memoria y puntos de extensión marcados.
- ⏳ Persistencia real (Supabase/KV/Neo4j), enseñanza pasiva/activa y post-procesamiento de workflows:
  se enchufan en `memory/` y `learning/` sin tocar el cliente ni el contrato.
