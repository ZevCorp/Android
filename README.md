# Graph Android — motor de ejecución mixto (Gemini + MCP)

Un asistente con carita flotante que controla tu teléfono Android. Le pides algo por **texto o voz** y lo ejecuta con **Gemini 3.5 Flash**, eligiendo en cada momento la vía más directa:

- **Herramientas MCP** — gestos básicos declarados como capacidades de primera clase (rápidos, limpios y deterministas).
- **Computer-use** — Gemini mira la pantalla y toca/escribe donde haga falta (para elementos concretos de una app).

No hay modos separados: es **un solo motor de ejecución mixto**. El modelo decide, turno a turno, si usar un gesto MCP o computer-use.

> **v1 intencionalmente mínima.** Esta versión es la base limpia. No hay modo enseñanza, ni compartir pantalla, ni workflows, ni terminal — se integrarán después sobre esta base. Sí está el botón de **detener**.

## Herramientas MCP

Dos familias, ambas declaradas en `core` (`Mcp`) con nombre, esquema de parámetros y `via` (cómo se ejecutan):

**Gestos de accesibilidad** (navegación por swipe):

| Herramienta | Qué hace |
|---|---|
| `go_home` | Vuelve al home de Android |
| `open_app_drawer` | Abre el cajón de aplicaciones |
| `open_notifications` | Despliega la barra de notificaciones |
| `pan_home` | Cambia de panel del home (`left`/`right`) |
| `scroll_menu` | Scroll dentro de una lista/cajón (`up`/`down`) |

**Acciones del sistema por Intent/API** (headless, sin navegar la UI):

| Herramienta | API de Android |
|---|---|
| `launch_app` | Intent de lanzamiento por nombre/paquete |
| `set_alarm` · `set_timer` · `show_alarms` | `AlarmClock` (con `EXTRA_SKIP_UI`) |
| `create_event` | `CalendarContract` (Intent de inserción) |
| `dial` · `call` | `ACTION_DIAL` / `ACTION_CALL` |
| `send_sms` · `send_email` | `ACTION_SENDTO` (`smsto:` / `mailto:`) |
| `web_search` · `open_url` | `ACTION_WEB_SEARCH` / `ACTION_VIEW` |
| `open_maps` · `directions` | `geo:` / `google.navigation:` |
| `open_camera` | `MediaStore.ACTION_IMAGE_CAPTURE` |
| `open_settings` | `Settings.ACTION_*` por sección |
| `share_text` · `set_clipboard` | `ACTION_SEND` / `ClipboardManager` |

Estas usan los **Common Intents de Android** — llamadas de sistema directas, no interacción con la pantalla. El modelo las prefiere sobre computer-use para tareas del sistema. Añadir una capacidad = añadir una entrada a `tools`.

**Herramientas aprendidas** (capa de enseñanza): además, el asistente puede *crear* herramientas MCP nuevas enseñándole (ver abajo). Se ejecutan reproduciendo una secuencia de toques sobre el árbol de UI.

## Capa de enseñanza (el asistente estructura el MCP de la pantalla)

Toca **🎓** en la burbuja e inicia una sesión. El asistente ve **todo el árbol de UI en vivo**; tu **voz** y tus **toques son señales para generalizar** — no se guardan tus clics: tocas "5" y "7" hablando de cálculos y él infiere que *todos* los dígitos y operadores importan. Conversa contigo: cuando entiende algo lo **demuestra iluminando** una secuencia ilustrativa (recuadro azul, tin·tin·tin), pregunta cosas puntuales **iluminando el elemento** (*"¿este es el de borrar?"* → Sí/No) y se ofrece a **probar** tocando de verdad. Al pulsar **✅ terminar**, estructura el **mapa completo de la pantalla** como herramienta MCP documentada.

**En ejecución** la herramienta aprendida es generalista: su descripción documenta los grupos de elementos y cómo componerlos, y el modelo pasa `taps` con la secuencia que necesite (`calculadora(taps="5,+,7,+,8,=")`) — cualquier cálculo, no solo el que enseñaste. Se reproduce por árbol de UI, sin imagen.

- `core`: `LearningSession` (orquestación); puertos `LearningBrain`, `LearningSurface`, `Teacher`; `LearnedTool` = nombre + documentación + catálogo de elementos.
- `app`: `GeminiLearning` (generaliza/estructura), `HighlightOverlay` (recuadro), el servicio captura clics-señal e implementa `LearningSurface`, la burbuja implementa `Teacher`.

## Las dos vías, un solo cerebro

`GeminiBrain` declara al modelo, en el mismo turno, la herramienta nativa `computer_use` **y** las herramientas MCP como funciones. Cuando el modelo llama una función MCP → `Mcp.call()`; cuando usa computer-use → primitivas de `Phone`. El bucle vive en `ExecutionEngine` (core).

## La burbuja flotante

Al activar el servicio de accesibilidad aparece la carita de Graph como overlay permanente sobre cualquier app (`TYPE_ACCESSIBILITY_OVERLAY`, sin permisos extra). Es arrastrable y desde ella:

- 💬 le pides algo por **texto** o **voz** (🎤 dicta sin abrir la app)
- 🚀 durante la ejecución vuela hacia donde actúa y narra con personalidad (TTS + globo de diálogo)
- ⏹ un botón rojo permite **detener** en cualquier momento
- ❓ si el asistente tiene una duda real, te pregunta ahí mismo (respondes por texto o voz)

## Puesta en marcha

1. Compila e instala: `./gradlew :app:assembleDebug && adb install app/build/outputs/apk/debug/app-debug.apk`.
2. Abre **Graph**: trae una API key por defecto; puedes reemplazarla en la app.
3. Activa el **servicio de accesibilidad** de Graph (botón en la app) → aparece la burbuja.
4. Pídele algo por texto o voz (p.ej. "abre el cajón de apps y busca la calculadora").

El modelo por defecto es `gemini-3.5-flash` (cambiable en prefs con la clave `model`).

## Arquitectura (clean, multiplataforma desde el diseño)

- **`core/`** — Kotlin Multiplatform puro (sin API de Android): el protocolo MCP (`Mcp`, `McpTool`), los puertos (`Phone`, `Gestures`, `Brain`, `Voice`, `UserChannel`) y el motor `ExecutionEngine`.
- **`app/`** — adaptadores Android: `GraphAccessibilityService` (implementa `Phone` + `Gestures`), `GeminiBrain` (Interactions API), burbuja y UI.

Ver [ARCHITECTURE.md](ARCHITECTURE.md).
