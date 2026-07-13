# Ü · Cliente macOS (el frontend tonto)

La carita flotante de Ü para macOS. Es un **frontend sin inteligencia**, gemelo del de Windows: lee el
árbol de UI con la **Accessibility API (AXUIElement)**, captura la pantalla, ejecuta ratón/teclado/
acciones de sistema y muestra la carita. Cada turno le pregunta al **backend** (Vercel) qué hacer.
**No decide nada por su cuenta** y **el backend no cambió ni una línea** para soportar esta plataforma
— esa es la prueba de que la separación funciona.

## Qué hace (y qué NO)

| El cliente SÍ (I/O puro)                              | El cliente NO (vive en el backend)      |
|------------------------------------------------------|-----------------------------------------|
| Leer el árbol de UI con AXUIElement (`AxReader`)     | Elegir qué tocar / qué herramienta usar |
| Capturar la pantalla a PNG (`Screenshotter`)        | Los prompts y el catálogo MCP           |
| Tap/type/scroll/swipe/tecla con CGEvent             | La memoria del usuario                  |
| Gestos y acciones de sistema de macOS               | El aprendizaje y los workflows          |
| Hablar (AVSpeechSynthesizer)                        | La key del modelo                       |
| Mostrar la carita y recoger texto                   | Cualquier llamada al modelo             |

## Estructura (espejo del cliente Windows)

```
Package.swift · Resources/Info.plist (LSUIElement) · make-app.sh
Sources/U/
  Domain/Protocol.swift    ← el contrato con el backend (espejo de domain/actions.ts)
  Ax/AxReader.swift        ← LEE EL ÁRBOL DE UI CON LA ACCESSIBILITY API (equivalente a UIA)
  Capture/Screenshotter.swift
  Actions/InputExecutor.swift ← CGEvent: tap/type/scroll/swipe/key
  Actions/Gestures.swift      ← gestos de macOS (Mission Control, Spotlight, ⌘Tab…)
  SystemApi/MacSystemApi.swift ← abrir apps, URLs, correo, ajustes, volumen (osascript)…
  SystemApi/InstalledApps.swift
  Mcp/LocalMcp.swift          ← registro nombre→ejecutor local (NO conoce descripciones ni prompts)
  Backend/BackendClient.swift ← POST /api/agent/turn
  Agent/AgentLoop.swift       ← el bucle del cliente (gemelo del de Windows, cerebro remoto)
  Voice/VoiceIO.swift
  Ui/FaceWindow.swift · main.swift
```

## Cómo la lectura del árbol usa la Accessibility API

`AxReader.read()` toma la app en primer plano (`NSWorkspace.frontmostApplication`), crea su
`AXUIElementCreateApplication(pid)`, obtiene la ventana enfocada (`kAXFocusedWindowAttribute`) y recorre
sus hijos (`kAXChildrenAttribute`) recogiendo los elementos accionables (botones, ítems de menú, campos,
enlaces, celdas…) con su etiqueta (`title → description → value → identifier`) y su `frame` (posición +
tamaño en puntos). Con eso arma `screen` (`app · título`) y `uiContext` (elementos por rol). Ese texto
viaja al cerebro cada turno; el screenshot solo cuando el backend pide computer-use. Las herramientas
aprendidas se reproducen tocando por etiqueta: `AXUIElementPerformAction(kAXPressAction)` o, si no, tap
por coordenadas del `frame`.

## Compilar y ejecutar (macOS 13+)

Requiere **Xcode / Swift toolchain**.

```bash
cd macos-client
./make-app.sh        # compila (swift build -c release) y arma U.app
open U.app
```

La **primera vez**, concede dos permisos y reinicia Ü:

- **Accesibilidad** (Ajustes → Privacidad y seguridad → Accesibilidad) — para leer el árbol de UI y
  sintetizar teclado/ratón.
- **Grabación de pantalla** (Ajustes → Privacidad y seguridad → Grabación de pantalla) — solo para el
  screenshot de computer-use.

En la carita, panel **Backend**, pon `http://localhost:3000` (backend local) o la URL de tu deploy de
Vercel. Escribe lo que quieras (p.ej. *"abre Safari y busca el clima"*).

> **Verificación:** al no haber toolchain de macOS en el entorno de construcción (Linux), el cliente se
> entrega revisado a mano pero **sin compilar aquí**. El primer `swift build` debe correrse en macOS.
