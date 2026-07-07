# Graph Android — motor de ejecución mixto (Gemini + MCP)

Un asistente con carita flotante que controla tu teléfono Android. Le pides algo por **texto o voz** y lo ejecuta con **Gemini 3.5 Flash**, eligiendo en cada momento la vía más directa:

- **Herramientas MCP** — gestos básicos declarados como capacidades de primera clase (rápidos, limpios y deterministas).
- **Computer-use** — Gemini mira la pantalla y toca/escribe donde haga falta (para elementos concretos de una app).

No hay modos separados: es **un solo motor de ejecución mixto**. El modelo decide, turno a turno, si usar un gesto MCP o computer-use.

> **Base mínima que crece.** El corazón es el motor mixto. Encima ya viven: enseñanza **pasiva** (observación silenciosa) y enseñanza **activa** (compartir pantalla → conocimiento textual por app, fase 1 sin árbol de UI). Aún no hay workflows ni terminal — se integrarán después. Está el botón de **detener**.

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

## Dos capas de enseñanza (el asistente estructura el MCP de cada app)

Hay **dos modos** de enseñanza, separados a propósito:

### Enseñanza PASIVA (se activa en la app principal)

Actívala/desactívala desde el botón **"Aprendizaje pasivo"** de la **app principal** (antes estaba en la burbuja). No hay barra, ni botón de detener, ni popups, ni iluminación: usas el teléfono **con normalidad** y el asistente solo observa — captura el **árbol de UI completo** y tus **clics como señales de valor**. Cuando **sales de una app** (o apagas el modo), estructura lo observado como herramienta MCP con criterio estricto: **solo guarda lo que entendió con certeza muy alta y tiene valor real según tus clics**; si la app ya tenía mapa, lo **refina** en vez de duplicarlo.

**Mantén oprimido el 🎓** de la burbuja para ver lo que ya sabe: se iluminan los contornos de todos los elementos ya trackeados en MCPs de la app visible, y el overlay se actualiza mientras navegas a otras apps. Vuelve a mantenerlo oprimido para ocultarlo. (Esto no cambió.)

### Enseñanza ACTIVA (el 🎓 de la burbuja, al tocarlo — compartir pantalla)

**Toca el 🎓** de la burbuja para iniciar la enseñanza **activa**: se **comparte la pantalla** (se graba video **y audio**) y le enseñas al asistente hablándole mientras le muestras cosas. Vuelve a tocar el 🎓 (o la acción de la notificación) para **terminar**. Al terminar, **todo el video se procesa con Gemini** y se estructura como **conocimiento textual por app** en la capa MCP (la memoria durable / knowledge-base): p.ej. *"el contacto de mi mamá en WhatsApp se llama 'Ale', no 'mamá'"*. Cuando después el asistente vaya a **usar esa app**, consume **fielmente** ese contexto completo.

Como el micrófono está ocupado grabando, si al procesar el video queda algo por confirmar, el asistente te lo **pregunta por voz al terminar** (igual que puede preguntarte durante la enseñanza pasiva), y tu respuesta también se guarda.

> **Fase 1 (esta versión):** la enseñanza activa **aún no** estructura el árbol de UI — guarda **solo texto** de cómo usar las apps en la knowledge-base MCP. El árbol de UI llegará después.

Mientras el modo está activo, el asistente puede **intervenir por voz por iniciativa propia**, con una cadena de pensamiento corta que decide entre tres: **proponer ayuda** con algo concreto y pendiente que ve en pantalla ("veo que te escribieron, ¿quieres que te ayude con eso?" — si aceptas, lo ejecuta), **preguntar** una duda genuinamente útil (se guarda como memoria durable de esa app), o **quedarse callado** (el default). Siempre que interviene por voz, respondes con el **micrófono sticky** que aparece bajo la carita.

**Durante la ejecución** el motor pregunta ante la **mínima duda** sobre datos que solo tú sabes ("¿cuál es el chat de Sebastián?"). Si respondes esa duda con el micrófono sticky, se enciende la **escucha en tiempo real por el resto de esa ejecución** (todo lo que digas se suma al objetivo); se apaga al terminar o tocando la burbuja. Es el único caso donde ese modo se enciende fuera de las esquinas.

**Modo reunión (esquinas).** Arrastra la burbuja **con el dedo** hasta una esquina superior y **mantenla ahí ~2.5 s**: Ü entra a la reunión como un integrante más y escucha la conversación de corrido — sirve igual para una sola persona dictando órdenes que para varias personas desarrollando ideas. Cada fragmento pasa por el **cerebro de reunión** (`MeetingBrain`), que decide su jugada: **tomar nota** de lo importante, **lanzar una construcción en paralelo** cuando se define algo ("probemos X" → crear repo en GitHub, abrir Claude → Code, sesión nueva con el repo y un prompt detallado que construya el MVP; la escucha nunca se detiene mientras el motor trabaja), **hablar solo si le hablan**, o **detectar el cierre** de la reunión e intervenir por voz: resume las notas, muestra lo que construyó con una demo narrada sección por sección e invita a pedir cambios de una vez. Todo queda en `files/meetings/reunion_<fecha>.md` (notas + tareas + transcripción). Lanzarla de un flick a la esquina **no** activa el modo — hace falta el dedo sostenido. Sacar la burbuja de la esquina termina la reunión.

**En ejecución** la herramienta aprendida es generalista: su descripción documenta los grupos de elementos y cómo componerlos, y el modelo pasa `taps` con la secuencia que necesite (`calculadora(taps="5,+,7,+,8,=")`) — cualquier cálculo, no solo el que vio. Se reproduce por árbol de UI, sin imagen. La **barra de velocidad** de la app principal ajusta la pausa entre esos steps. La carita además **parpadea al cambiar de vía**: 1 vez al pasar a ejecución consciente (computer-use con screenshots), 2 veces al pasar a subconsciente (MCP).

- `core`: `PassiveLearning` (acumula señales por app y consolida al salir); puertos `LearningBrain`, `LearningSurface`; `LearnedTool` = nombre + documentación + catálogo de elementos + paquete de la app.
- `app`: `GeminiLearning` (consolidación estricta), `HighlightOverlay` (contornos de lo aprendido), el servicio captura clics-señal y detecta el cambio de app en primer plano.

## Las dos vías, un solo cerebro

`GeminiBrain` declara al modelo, en el mismo turno, la herramienta nativa `computer_use` **y** las herramientas MCP como funciones. Cuando el modelo llama una función MCP → `Mcp.call()`; cuando usa computer-use → primitivas de `Phone`. El bucle vive en `ExecutionEngine` (core).

## Mensaje-sobre-mensaje (reencaminado en caliente)

Mientras el asistente ejecuta aparece un **micrófono casi invisible** abajo. Si le dices algo más, **no se encola**: se cancela el motor y se **reinterpretan todos los prompts juntos** (uno puede anular, modificar o ampliar al otro — decisión pura del LLM). `GraphApp.run` corre un bucle que reconstruye el objetivo con `buildGoal(prompts)` y relanza el motor cada vez que `augmentExecution` añade un audio.

## El amigo prevenido (anticipación)

Al **terminar** una tarea, una cadena de pensamiento CORTÍSIMA (`Anticipation`) evalúa si hay algo que hacer **justo ahora** para que lo pedido no se tropiece — como un amigo que se anticipa a los problemas. Ejecuta **de forma autónoma** solo acciones de **certeza total y seguras** (p.ej. tras poner una alarma, `set_volume alarm 100`); cualquier riesgo que no deba tocar por su cuenta lo deja como **aviso hablado**. Nunca envía mensajes, llama, compra ni nada irreversible.

## La burbuja flotante

Al activar el servicio de accesibilidad aparece la carita de Graph como overlay permanente sobre cualquier app (`TYPE_ACCESSIBILITY_OVERLAY`, sin permisos extra). Es arrastrable y desde ella:

- 💬 le pides algo por **texto** o **voz** (🎤 dicta sin abrir la app)
- 🎓 **toque**: inicia/termina la enseñanza **activa** (compartir pantalla); **mantener oprimido**: muestra/oculta lo aprendido en la app visible
- 🚀 durante la ejecución vuela hacia donde actúa y narra con personalidad (TTS + globo de diálogo)
- ⏹ un botón rojo permite **detener** en cualquier momento
- ❓ si el asistente tiene una duda real, te pregunta ahí mismo (respondes por texto o voz)

## Puesta en marcha

1. Compila e instala: `./gradlew :app:assembleDebug && adb install app/build/outputs/apk/debug/app-debug.apk`.
2. Abre **Graph**: trae una API key por defecto; puedes reemplazarla en la app.
3. Activa el **servicio de accesibilidad** de Graph (botón en la app) → aparece la burbuja.
4. Pídele algo por texto o voz (p.ej. "abre el cajón de apps y busca la calculadora").

El modelo por defecto es `gemini-3.5-flash` (cambiable en prefs con la clave `model`).

## Actualizaciones automáticas (sin Play Store)

La app se actualiza sola: consulta en Supabase la última versión, avisa con una notificación y la
instala con un toque (`PackageInstaller`). El admin publica una versión y notifica a todos desde un
panel oculto (mantén oprimido el título **"Actualizaciones"**). Todo el detalle — clave de firma,
token de administrador, backend de Supabase y el paso a paso para lanzar una versión — está en
[RELEASING.md](RELEASING.md). Para que las actualizaciones funcionen, **todos los builds se firman con
la misma clave** (`app/graph-release.jks`), fijada en `app/build.gradle.kts`.

## Arquitectura (clean, multiplataforma desde el diseño)

- **`core/`** — Kotlin Multiplatform puro (sin API de Android): el protocolo MCP (`Mcp`, `McpTool`), los puertos (`Phone`, `Gestures`, `Brain`, `Voice`, `UserChannel`) y el motor `ExecutionEngine`.
- **`app/`** — adaptadores Android: `GraphAccessibilityService` (implementa `Phone` + `Gestures`), `GeminiBrain` (Interactions API), burbuja y UI.

Ver [ARCHITECTURE.md](ARCHITECTURE.md).
