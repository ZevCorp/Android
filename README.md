# Graph Android — motor de ejecución mixto (Gemini + MCP)

Un asistente con carita flotante que controla tu teléfono Android. Le pides algo por **texto o voz** y lo ejecuta con **Gemini 3.5 Flash**, eligiendo en cada momento la vía más directa:

- **Herramientas MCP** — gestos básicos declarados como capacidades de primera clase (rápidos, limpios y deterministas).
- **Computer-use** — Gemini mira la pantalla y toca/escribe donde haga falta (para elementos concretos de una app).

No hay modos separados: es **un solo motor de ejecución mixto**. El modelo decide, turno a turno, si usar un gesto MCP o computer-use.

> **Base mínima que crece.** El corazón es el motor mixto. Encima ya viven: enseñanza **pasiva** (observación silenciosa), enseñanza **activa** (compartir pantalla → conocimiento textual por app + paso a paso por accesibilidad) y **workflows** (el puente consciente ↔ subconsciente, ver abajo). Aún no hay terminal — se integrará después. Está el botón de **detener**.

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
| `check_simit_fines` | `ACTION_VIEW` a simit.org.co + computer-use |

Estas usan los **Common Intents de Android** — llamadas de sistema directas, no interacción con la pantalla. El modelo las prefiere sobre computer-use para tareas del sistema. Añadir una capacidad = añadir una entrada a `tools`.

**Skill: comparendos SIMIT.** `check_simit_fines` abre el portal oficial de multas de tránsito colombianas y guía al modelo (vía la `description` de la herramienta, que es lo que el LLM lee como function-calling schema) para leer estado y fecha de cada infracción y razonar sobre caducidad (1 año, art. 161 Ley 769/2002) y prescripción (3 años, art. 159) — con la advertencia explícita de que no es asesoría legal definitiva. Límite estricto: nunca paga, envía ni radica nada por el usuario; el derecho de petición para alegar la caducidad debe presentarlo el usuario mismo ante el organismo de tránsito (SIMIT solo consulta).

**Herramientas aprendidas** (capa de enseñanza): además, el asistente puede *crear* herramientas MCP nuevas enseñándole (ver abajo). Se ejecutan reproduciendo una secuencia de toques sobre el árbol de UI.

## Dos capas de enseñanza (el asistente estructura el MCP de cada app)

Hay **dos modos** de enseñanza, separados a propósito:

### Enseñanza PASIVA (se activa en la app principal)

Actívala/desactívala desde el botón **"Aprendizaje pasivo"** de la **app principal** (antes estaba en la burbuja). No hay barra, ni botón de detener, ni popups, ni iluminación: usas el teléfono **con normalidad** y el asistente solo observa — captura el **árbol de UI completo** y tus **clics como señales de valor**. Cuando **sales de una app** (o apagas el modo), estructura lo observado como herramienta MCP con criterio estricto: **solo guarda lo que entendió con certeza muy alta y tiene valor real según tus clics**; si la app ya tenía mapa, lo **refina** en vez de duplicarlo.

Mientras observa, el asistente también puede **intervenir por voz por iniciativa propia** (proponer ayuda, preguntar una duda valiosa, o callar — el default). Esa decisión está **conectada a tu knowledge-base personal**: si le diste una instrucción como *"cada vez que estemos en WhatsApp y un usuario me pida una mejora, propón hacerla tú"*, al ver ese escenario en vivo te lo **propone por voz** y, si aceptas, **lo ejecuta**. Es un mindset propositivo (detectar en tiempo real algo que te ahorraría tiempo), distinto del "amigo prevenido" de después de cada ejecución (proteger la acción recién hecha).

**Mantén oprimido el 🎓** de la burbuja para ver lo que ya sabe: se iluminan los contornos de **todos** los elementos accionables detectados en la app visible — **verde** los que ya están trackeados en MCPs, en el **acento del tema** (negro/blanco) el resto — y el overlay se actualiza mientras navegas. Mientras lo mantienes oprimido, cada toque destella el elemento que el agente resolvería al replicar ese clic: **naranja** si coincide con lo que tocaste, **rojo** si es un bug de ID ambiguo (ver "Bugs de UI" abajo). Vuelve a mantenerlo oprimido para ocultarlo.

**Bugs de UI (mejora continua, transversal a cualquier app).** El agente aprende un clic por la
ETIQUETA del elemento y, al replicarlo, toca el PRIMER nodo con esa etiqueta — en listas donde varias
filas comparten el mismo id (p.ej. todos los chats de WhatsApp con `contact_row_container`) eso
siempre cae en el primero. Un diagnóstico corre **solo, sin necesitar la visualización**, en cada clic
del aprendizaje pasivo: si detecta ese desajuste, un LLM (`GeminiClickDoctor`) analiza el snapshot de
la pantalla, halla el **ID único correcto** y escribe un **resumen de cómo endurecer la detección
nativa**. Todo aparece en la card **"Bugs de UI"** del panel de desarrollador.

### Enseñanza ACTIVA (el 🎓 de la burbuja, al tocarlo — compartir pantalla)

**Toca el 🎓** de la burbuja para iniciar la enseñanza **activa**: se **comparte la pantalla** (se graba video **y audio**) y le enseñas al asistente hablándole mientras le muestras cosas. Vuelve a tocar el 🎓 (o la acción de la notificación) para **terminar**. Al terminar, **todo el video se procesa con Gemini** y se estructura como **conocimiento textual por app** en la capa MCP (la memoria durable / knowledge-base): p.ej. *"el contacto de mi mamá en WhatsApp se llama 'Ale', no 'mamá'"*. Cuando después el asistente vaya a **usar esa app**, consume **fielmente** ese contexto completo.

Como el micrófono está ocupado grabando, si al procesar el video queda algo por confirmar, el asistente te lo **pregunta por voz al terminar** (igual que puede preguntarte durante la enseñanza pasiva) y abre su popup para que respondas por texto o con el micrófono del popup; tu respuesta también se guarda.

> **Fase 1 (esta versión):** la enseñanza activa **aún no** estructura el árbol de UI — guarda **solo texto** de cómo usar las apps en la knowledge-base MCP. El árbol de UI llegará después.

Mientras el modo está activo, el asistente puede **intervenir por voz por iniciativa propia**, con una cadena de pensamiento corta que decide entre tres: **proponer ayuda** con algo concreto y pendiente que ve en pantalla, **preguntar** una duda genuinamente útil (se guarda como memoria durable de esa app), o **quedarse callado** (el default). La decisión corre **sin thinking del modelo** (el razonamiento es el propio campo del JSON) para que la propuesta llegue en segundos, y siempre **nombra su referente concreto** ("en ese chat con Julián veo que te pidió X, ¿quieres que lo haga yo?") — así la entiendes aunque ya hayas cambiado de pantalla. Le respondes por **cualquier vía de siempre** — tocar la carita y usar el texto o el micrófono del panel, las esquinas — porque lo que propuso queda como **contexto pendiente del hilo unificado**: un "sí, hazlo" ejecuta exactamente esa tarea. (El micrófono sticky flotante que aparecía bajo la carita se eliminó: nadie lo usaba.)

**Durante la ejecución** el motor pregunta ante la **mínima duda** sobre datos que solo tú sabes ("¿cuál es el chat de Sebastián?"). Si respondes esa duda por voz ("Responder con voz" en el popup), se enciende la **escucha en tiempo real por el resto de esa ejecución** (todo lo que digas se suma al objetivo); se apaga al terminar o tocando la burbuja. Es el único caso donde ese modo se enciende fuera de las esquinas.

**Modo reunión (esquinas).** Arrastra la burbuja **con el dedo** hasta una esquina superior y **mantenla ahí ~2.5 s**: Ü entra a la reunión como un integrante más y escucha la conversación de corrido — sirve igual para una sola persona dictando órdenes que para varias personas desarrollando ideas. Cada fragmento pasa por el **cerebro de reunión** (`MeetingBrain`), que decide su jugada: **tomar nota** de lo importante, **lanzar una construcción en paralelo** cuando se define algo ("probemos X" → crear repo en GitHub, abrir Claude → Code, sesión nueva con el repo y un prompt detallado que construya el MVP; la escucha nunca se detiene mientras el motor trabaja), **hablar solo si le hablan**, o **detectar el cierre** de la reunión e intervenir por voz: resume las notas, muestra lo que construyó con una demo narrada sección por sección e invita a pedir cambios de una vez. Todo queda en `files/meetings/reunion_<fecha>.md` (notas + tareas + transcripción). Lanzarla de un flick a la esquina **no** activa el modo — hace falta el dedo sostenido. Sacar la burbuja de la esquina termina la reunión.

**En ejecución** la herramienta aprendida es generalista: su descripción documenta los grupos de elementos y cómo componerlos, y el modelo pasa `taps` con la secuencia que necesite (`calculadora(taps="5,+,7,+,8,=")`) — cualquier cálculo, no solo el que vio. Se reproduce por árbol de UI, sin imagen. La **barra de velocidad** de la app principal ajusta la pausa entre esos steps. La carita además **parpadea al cambiar de vía**: 1 vez al pasar a ejecución consciente (computer-use con screenshots), 2 veces al pasar a subconsciente (MCP).

- `core`: `PassiveLearning` (acumula señales por app y consolida al salir); puertos `LearningBrain`, `LearningSurface`; `LearnedTool` = nombre + documentación + catálogo de elementos + paquete de la app.
- `app`: `GeminiLearning` (consolidación estricta), `HighlightOverlay` (contornos de lo aprendido), el servicio captura clics-señal y detecta el cambio de app en primer plano.

## Workflows: el puente consciente ↔ subconsciente

El asistente está inspirado en el ser humano: ejecutando **una misma tarea** puede hacer switch rápidamente entre lo **consciente** (mirar y decidir: Gemini computer-use) y lo **subconsciente** (lo que ya conoce: MCP). El **workflow** es el punto de enlace entre esos dos mundos: un flujo de **steps** que se concatenan para completar una tarea — primero a, después b, después c — donde **la unidad del step es el clic** sobre un elemento del árbol de UI.

- **Se crean en la enseñanza.** Ambos modos son el punto de creación: mientras le enseñas (pasiva: usando el teléfono con normalidad; activa: compartiendo pantalla), el asistente ve explícitamente el paso a paso y **va guardando el workflow step by step**. La enseñanza termina al **salir de la app** (pasiva) o al **terminar la grabación** (activa).
- **Post-procesamiento LLM.** Al terminar, la traza cruda pasa a un LLM que **estructura el workflow de forma efectiva**: limpia los pasos basura e innecesarios, organiza los necesarios y agrega una **nota de contexto opcional** a los pasos que decida (no es obligatoria). Además **asigna los MCPs que ya están listos**: los steps cuyo elemento quedó bien detectado en el árbol de UI quedan **subconscientes**; los que no se lograron detectar quedan para ejecutarse de forma **consciente**.
- **Aprendizaje continuo: workflows y MCPs se reconectan solos, nazca primero el que nazca.** Si un
  workflow nace y ya existían MCPs de esa app, el post-procesamiento usa ese catálogo para marcar
  subconscientes los pasos que ya estaban cubiertos. Y si un MCP nace o se refina después, un
  post-procesamiento especializado de **reconexión** revisa los workflows existentes de esa app y
  sube a subconscientes los pasos conscientes que el mapa nuevo ya cubre. Cuando ambos nacen en la
  misma pasada de enseñanza, primero consolida el MCP y después se estructura el workflow.
- **La ejecución MCP está plasmada encima de los workflows.** Cada workflow se declara como herramienta MCP (`workflow_*`) y el modelo lo invoca entero (con un `context` de datos variables). El `WorkflowRunner` recorre los steps **haciendo switch entre consciente y subconsciente según avanza el workflow**: los subconscientes se ejecutan como clics por árbol de UI (sin pantalla) y los conscientes con un motor Gemini acotado a ese único paso. Si un clic subconsciente falla, ese step **cae en caliente a la vía consciente**.
- Persisten en `files/workflows/` + nube (`graph_workflows`), igual que los aprendizajes.
- **Los ves nacer y mejorar**: card **Workflows** en la app principal (paso a paso 🧩/👁 por step) y
  narración al aprenderlos. Durante la ejecución, la **statusbar negra** de arriba muestra la vía en
  vivo (👁 consciente / 🧩 subconsciente) y tocarla detiene.
- **Grafo de conocimiento (opcional)**: configura una instancia de Neo4j Aura en la app y todo el
  conocimiento (mapas MCP, elementos, workflows y sus steps) se proyecta como grafo navegable.
  Plan de pruebas conjunto en `TESTING.md`.

## Tu cuenta: dos capas de conocimiento, dos dueños

El asistente mejora con el uso por dos vías **separadas a propósito**, y el inicio de sesión (tarjeta **"Tu cuenta"** de la app, email + contraseña) es la frontera entre ambas:

- **El mapa de UI de las apps es TRANSVERSAL** (`graph_learned_tools`): si un usuario le enseña la calculadora o WhatsApp, ese entendimiento del árbol de UI les sirve a **todos** los usuarios. Cualquiera lo lee (con o sin cuenta); **aportar** aprendizajes nuevos requiere sesión.
- **La knowledge-base personal es SOLO TUYA** (`graph_memory`, la memoria durable): cosas como *"mi mamá está guardada como 'Ale' en WhatsApp"* o *"soy desarrollador: ayúdame con las tareas de desarrollo entrantes"* pertenecen a tu **cuenta**. En el servidor lo garantiza RLS por `user_id`: nadie más puede leerlas, ni siquiera con la key pública de la app.

Sin sesión el asistente funciona igual, pero tus recuerdos viven **solo en ese teléfono** (archivo anónimo local). Al iniciar sesión, esas notas anónimas se **adoptan a tu cuenta** (se suben y te siguen entre teléfonos y reinstalaciones); al cerrar sesión, el motor deja de inyectarlas y tu memoria queda a salvo en la nube. Localmente cada cuenta tiene su propio archivo (`memory-<userId>.json`), así que varios usuarios en un mismo teléfono no se mezclan.

El registro crea la cuenta al instante (sin confirmación por correo) vía la Edge Function `graph-signup`.

## Las dos vías, un solo cerebro

`GeminiBrain` declara al modelo, en el mismo turno, la herramienta nativa `computer_use` **y** las herramientas MCP como funciones. Cuando el modelo llama una función MCP → `Mcp.call()`; cuando usa computer-use → primitivas de `Phone`. El bucle vive en `ExecutionEngine` (core).

## Mensaje-sobre-mensaje (reencaminado en caliente)

Mientras el asistente ejecuta aparece un **micrófono casi invisible** abajo. Si le dices algo más, **no se encola**: se cancela el motor y se **reinterpretan todos los prompts juntos** (uno puede anular, modificar o ampliar al otro — decisión pura del LLM). `GraphApp.run` corre un bucle que reconstruye el objetivo con `buildGoal(prompts)` y relanza el motor cada vez que `augmentExecution` añade un audio.

## La anticipación proactiva

Al **terminar** una tarea, una cadena de pensamiento CORTÍSIMA (`Anticipation`) evalúa si hay **una acción directa** —el siguiente eslabón natural de lo que acaba de hacer— que le ahorre al usuario el próximo paso. El mindset es **proactivo, no miedoso**: en vez de advertencias precavidas de hipótesis lejanas (*"ten un cargador cerca porque gasta batería"*), **propone la acción concreta encadenada** con la tarea (*tras revisar cuántos datos quedan para compartir → "¿activo el compartir datos?"*) y, si el usuario acepta, la **ejecuta**. Su **default es callar**: solo habla cuando la recomendación es **claramente valiosa**, filtrada por un umbral de valor autoevaluado (`worth ≥ 0.30`) en vez de un contador fijo — así, en la práctica, solo interviene en ~3 de cada 10 ejecuciones. Sin sesgos por costumbre (no ofrece cambiar el volumen salvo que sea de verdad el paso obvio). Nunca envía mensajes, llama, compra ni nada irreversible por su cuenta.

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
