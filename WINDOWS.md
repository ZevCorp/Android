# Ü para Windows — frontend y backend separados desde el día uno

Esta rama añade la **versión Windows** del asistente Ü, construida con la separación que el app Android
nunca tuvo: **el cerebro (backend) vive aparte del cliente (frontend)** desde la primera línea.

Proyectos autónomos, listos para vivir en repos separados:

| Carpeta          | Qué es                          | Dónde se despliega          | Contiene la innovación |
|------------------|---------------------------------|-----------------------------|------------------------|
| `backend/`       | El **cerebro** (TypeScript)     | **Vercel** (serverless)     | ✅ todo                |
| `windows-client/`| El **frontend tonto** (C#/WPF)  | La PC del usuario (`.exe`)  | ❌ nada                |
| `windows-graph/` | Grabar/ejecutar workflows contra Graph (SAP GUI / UIA), en pausa | Compila dentro de `U.exe` | ❌ nada |
| `macos-client/`  | El **frontend tonto** (Swift)   | El Mac del usuario (`.app`) | ❌ nada                |

**`U.exe` es la app instalable, sin caparazón alrededor.** Se entrega como `U-Setup.exe` (Velopack):
el usuario instala **una vez** en `%LocalAppData%\U`, sin admin, y a partir de ahí **la carita se
actualiza sola** — ver [`RELEASING-WINDOWS.md`](RELEASING-WINDOWS.md). No hay webapp embebida: se
retiró el caparazón Electron (`windows-shell/`) que la cargaba.

> **La prueba de la separación:** el cliente macOS se añadió **sin tocar una sola línea del backend**.
> Windows lee el árbol de UI con UIA; macOS con la Accessibility API (AXUIElement); ambos hablan el
> mismo contrato (`backend/src/domain/actions.ts`) y reciben el mismo `Action[]`. Un tercer frontend
> (web, Linux, otra plataforma) entra igual: implementa leer-árbol + ejecutar-acciones y ya.

## El porqué (lo que pediste)

En Android, toda la capa de inteligencia —el cerebro LLM, el catálogo MCP, el aprendizaje, los
workflows, los prompts— se compila **dentro del APK**. Es fácil de copiar descompilando. Para Windows
lo hacemos al revés: el cliente solo hace **I/O** (leer el árbol de UI con **UIA**, capturar pantalla,
mover ratón/teclado, hablar) y **cada decisión la toma el backend** por HTTPS. Descompilar el `.exe`
no revela ni un prompt. Y como el cerebro está en su propio repo desplegado en Vercel, lo **controlas y
actualizas sin tocar el cliente**.

Detalle técnico de la separación: `backend/ARCHITECTURE.md`.

## Cómo encajan

```
Cliente Windows (windows-client/)              Backend (backend/, en Vercel)
  UiaReader  → lee el árbol de UI (UIA)
  Screenshotter → PNG (solo si hace falta)
  AgentLoop  ── POST /api/agent/turn ─────────►  resolveTurn → cerebro OpenAI + MCP + memoria
             ◄── { actions, narration, … } ────  (session firmada; stateless)
  InputExecutor / LocalMcp → ejecuta en Windows
```

Contrato único: `backend/src/domain/actions.ts` ↔ `windows-client/src/Domain/Protocol.cs`.

## Puesta en marcha rápida

1. **Backend** → `cd backend && npm install && npm run typecheck`, configura `OPENAI_API_KEY` y
   `vercel deploy`. Ver `backend/README.md`.
2. **Cliente** (en Windows 10/11 con .NET 8 SDK) → `cd windows-client && dotnet run -c Release`, pon la
   URL del deploy en el panel **Backend** de la carita. Ver `windows-client/README.md`.

## Extraer a repos separados

Los dos proyectos son autónomos (cada uno con su README, su `.gitignore` y su config de build). Para
moverlos a sus propios repos manteniendo el historial:

```bash
# Backend → su repo
git subtree split --prefix=backend -b split-backend
git push git@github.com:zevcorp/u-windows-backend.git split-backend:main

# Cliente → su repo
git subtree split --prefix=windows-client -b split-client
git push git@github.com:zevcorp/u-windows-client.git split-client:main
```

O simplemente copia cada carpeta a un repo nuevo. El backend es el que va conectado a Vercel.
