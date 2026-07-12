# Ü · Cliente Windows (el frontend tonto)

La carita flotante de Ü para Windows. Es un **frontend sin inteligencia**: lee el árbol de UI con
**UIA**, captura la pantalla, ejecuta ratón/teclado/acciones de sistema y muestra la carita. Cada
turno le pregunta al **backend** (Vercel) qué hacer y lo ejecuta. **No decide nada por su cuenta.**

> Descompilar este `.exe` no revela prompts, catálogo MCP, memoria del usuario ni la key del modelo:
> nada de eso está aquí. Vive en el backend, en un repo separado. Esa es la razón de ser del proyecto.

## Qué hace (y qué NO)

| El cliente SÍ (I/O puro)                              | El cliente NO (vive en el backend)      |
|------------------------------------------------------|-----------------------------------------|
| Leer el árbol de UI con UIA (`UiaReader`)            | Elegir qué tocar / qué herramienta usar |
| Capturar la pantalla a PNG (`Screenshotter`)        | Los prompts y el catálogo MCP           |
| Tap/type/scroll/swipe/tecla con SendInput           | La memoria del usuario                  |
| Gestos y acciones de sistema de Windows             | El aprendizaje y los workflows          |
| Hablar/escuchar (TTS/STT de Windows)                | La key de OpenAI                        |
| Mostrar la carita y recoger texto/voz               | Cualquier llamada al modelo             |

## Estructura

```
WindowsClient.csproj · app.manifest (PerMonitorV2)
App.xaml(.cs)
src/
  Domain/Protocol.cs      ← el contrato con el backend (espejo de domain/actions.ts)
  Uia/UiaReader.cs        ← LEE EL ÁRBOL DE UI CON UIA (lo que pediste)
  Capture/Screenshotter.cs
  Actions/InputExecutor.cs ← SendInput: tap/type/scroll/swipe/key
  Actions/Gestures.cs      ← gestos de Windows (Win+D, Inicio, Alt+Tab…)
  SystemApi/WindowsSystemApi.cs ← launch app, URLs, correo, ajustes, volumen…
  SystemApi/InstalledApps.cs
  Mcp/LocalMcp.cs          ← registro nombre→ejecutor local (NO conoce descripciones ni prompts)
  Backend/BackendClient.cs ← POST /api/agent/turn
  Agent/AgentLoop.cs       ← el bucle del cliente (gemelo de ExecutionEngine, cerebro remoto)
  Voice/VoiceIO.cs
  Ui/FaceWindow.xaml(.cs)  ← la carita flotante
```

## Cómo la lectura del árbol usa UIA

`UiaReader.Read()` toma la ventana en primer plano (`GetForegroundWindow`), la envuelve con
`AutomationElement.FromHandle` y recorre el **ControlView** de UIA recogiendo los elementos accionables
(botones, ítems de lista, edits, enlaces…) con su etiqueta (`Name → AutomationId → HelpText`) y sus
`BoundingRectangle`. Con eso arma:

- `screen` = `proceso · título de ventana`,
- `uiContext` = tipo de app + etiquetas visibles agrupadas por tipo de control.

Ese texto viaja al cerebro cada turno (sin imagen). El screenshot solo se adjunta cuando el backend
pide computer-use. Las herramientas **aprendidas** se reproducen tocando por etiqueta: `InvokeLabel`
(patrón UIA `Invoke`, sin ratón) o, si no, tap por coordenadas del `BoundingRectangle`.

## Compilar y ejecutar (Windows 10/11)

Requiere **.NET 8 SDK** en Windows (WPF + UIA son Windows-only; no compila en Linux/macOS).

```powershell
cd windows-client
dotnet build -c Release
dotnet run -c Release
```

Al abrir, despliega **Backend** en la carita y pon la URL de tu deploy de Vercel
(`https://<tu-deploy>.vercel.app`) y, si configuraste `CLIENT_TOKEN`, el token. Guardar. Luego escribe
o dicta lo que quieras (p.ej. *"abre el navegador y busca el clima"*).

> **Verificación:** al no haber SDK de Windows en el entorno de construcción (Linux), el cliente se
> entrega revisado a mano pero **sin compilar aquí**. El primer `dotnet build` debe correrse en Windows.
