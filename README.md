# Graph Android — Teaching · Learning · Subconsciente

Sistema que aprende tareas de un video-tutorial del usuario y termina ejecutándolas sin LLM, igual que el aprendizaje humano: te lo muestran → lo haces conscientemente mientras construyes las conexiones → lo haces en automático, más rápido, más barato y más confiable.

Es el mismo principio del repo [Graph](https://github.com/Joseph1356K/Graph) (extensión de Chrome que graba clics/inputs del DOM como steps de workflows), llevado a Android sobre la superficie de accesibilidad.

> **Una sola ejecución, estado por step.** Ya no hay "consciente" vs "subconsciente" como modos separados: es **una sola ejecución** donde conviven ambos según el ESTADO de cada step. 🟢 verde = se hace por árbol de UI sin LLM (subconsciente); 🟡🔴 = Gemini 3.5 Flash lo hace y, si sale bien, pasa a verde. La primera vez que grabas está 100% en borrador; cada corrida sube el % aprendido. Puedes verlo en vivo en el **dashboard**.

## Variantes: una acción, muchas ejecuciones (como Graph)

Cada CLICK guarda también sus **paralelos**: los elementos del mismo tipo vistos en el mismo contenedor del árbol de UI (los otros números de la calculadora, los otros sabores de pizza). Igual que los `alternativeTargets` de Graph. Así un step deja de ser "clic en el 5" y se vuelve un **punto de selección** `pick_<order>`: puedes ejecutar cualquier variante con la **misma velocidad aprendida**, sin volver a enseñar.

```bash
cli/graph info wf_calc      # muestra pick_3: opciones 0,1,…,9,+,−,×,= (default 5)
cli/graph run wf_calc --pick_1=3 --pick_2=× --pick_3=9   # 3×9 aunque enseñaste 5+6+7
```

En ejecución, el paralelo pedido se localiza por su etiqueta en el árbol de UI vivo (nunca por coordenadas). Un LLM que opere la terminal lee `graph info`, ve las opciones y mapea "haz 3×9" a los `--pick_N` — el mismo patrón guide→LLM de Graph.

## Pídele algo (sin video)

Además de enseñarle grabando, puedes **pedirle directamente lo que quieras** por texto o voz desde la barra de chat de la burbuja (o de la app). No cambia el motor: se ejecuta con Gemini 3.5 Flash computer use y, como cualquier ejecución, va **alimentando el aprendizaje por workflow** (graba steps que un learning posterior consolida en verde). La burbuja tiene: barra de chat, 🎤 micrófono para dictar el prompt y ✏️ un botón pequeño para enseñarle con la pantalla.

## Las tres etapas

1. **Teaching = ejecución con el usuario como ejecutor.** Muestras la tarea en tu teléfono (puedes narrar por voz). Es la **misma ejecución** que hace Gemini, solo que aquí el ejecutor eres tú: cada clic/tecleo del árbol de UI se registra en el workflow **ya aprendido (🟢 verde) desde el primer momento** — sin paso de borrador. Al detener, el video se sube a Gemini para entender la intención, y la **capa de inteligencia (curador)** acomoda el **tronco** y las **ramas** situacionales (p.ej. `configurar_direccion`) **y elimina pasos que sobren** (toques accidentales, idas y vueltas). Resultado: un workflow aprendido y limpio de una sola pasada.

2. **Learning / Ejecución (unificada)** — Consolidación supervisada, como el aprendizaje humano. Avanza **un step a la vez ejecutando el workflow por árbol de UI**; tras cada step se toma captura y **Gemini 3.5 Flash la juzga**. Si quedó bien → 🟢 **CONFIRMED** (definitivo). Si falló → **computer use toma el control**, retrocede si hace falta, lo hace él mismo, y el step queda 🔴 **LLM**; el siguiente vuelve al árbol de UI. Cada corrida sube el % aprendido y actualiza el grafo/dashboard en vivo. Si el modelo duda, te pregunta por **voz**, texto o demostración. Los steps ya 🟢 se ejecutan sin LLM (rápido). Las lecciones sin borrador corren el modo libre (el agente ejecuta todo y se graba como DRAFT).

3. **Terminal** — La misma ejecución desde la terminal, con **profundidad** (avanza por tramos) y **ramas**:

   ```bash
   cli/graph list                                   # workflows y % aprendido
   cli/graph info wf_1778724462696                  # red: ramas, variables, % aprendido, cursor, estado por step
   cli/graph run wf_1778724462696 --input_5="pizza"                                # todo el tronco
   cli/graph run wf_1778724462696 --depth 5                                        # avanza solo 5 steps y recuerda dónde seguir
   cli/graph run wf_1778724462696 --branch configurar_direccion --input_5="pizza"  # con rama
   ```

   Los workflows son **terminal-first**: `info` es el "man page" que un LLM lee para decidir profundidad, ramas y variables según qué tan aprendido está. Ver [CLI.md](CLI.md).

## Voz y personalidad

La carita habla (TTS) y muestra un **globo de diálogo** narrando lo que hace con personalidad ("Abro el reloj ⏰", "Busco la mejor pizza para ti 🍕"). La voz está **siempre disponible**: durante el Teaching un observador en vivo puede preguntarte algo importante ("¿y si el restaurante está cerrado?"), y durante la ejecución puede preguntar lo que necesite para hacerlo bien ("¿el Uber es para ti o para tu mamá?"). Solo usa la voz para lo importante.

## Dashboard en vivo

La ejecución se sincroniza en tiempo real con un dashboard web (Supabase Edge Function como host + tabla `runs` con polling cada 1s). Cada step se pinta 🟢/🔴/🟡 mientras el asistente ejecuta, con el step activo pulsando. Botón **📊 Dashboard en vivo** en la app (copia y abre la URL con tu `device`). La carpeta [`dashboard/`](dashboard/) es estática y **lista para Vercel** (`vercel deploy` o conectando el repo); apunta al mismo backend Supabase.

## La burbuja flotante

Al activar el servicio de accesibilidad aparece **la carita de Graph** (la misma del asistente del repo Graph) como burbuja flotante permanente sobre cualquier app — es un overlay de accesibilidad (`TYPE_ACCESSIBILITY_OVERLAY`), sin permisos extra. Es arrastrable y desde ella se maneja todo:

- ⏺ **grabar/detener** un tutorial (Teaching)
- 🎓 lanzar el **Learning** de una lección
- ⚡ ejecutar **workflows** subconscientes
- 🧠 en la app, cada workflow muestra su **red neuronal**: el grafo step a step (🟢 aprendido por árbol de UI · 🔴 aún con Gemini · 🟡 por consolidar), que se va pintando en vivo durante el learning
- Durante el Learning la burbuja se oculta (para no salir en las capturas del agente) y reaparece cuando el asistente tiene una duda: respondes por texto, **voz** (SpeechRecognizer, sin abrir la app) o **demostrándolo**; en modo demo, tocar la burbuja ✅ marca que terminaste.

## Puesta en marcha

1. Compila e instala: `./gradlew :app:assembleDebug && adb install app/build/outputs/apk/debug/app-debug.apk`.
2. Abre **Graph**: trae una API key por defecto (proyecto *Devable AI*); puedes reemplazarla en la app o con `cli/graph set-key <KEY>`.
3. Activa el **servicio de accesibilidad** de Graph (botón en la app) → aparece la burbuja.
4. Etapa 1: graba un tutorial → Etapa 2: tócale *Aprender* → Etapa 3: ejecútalo desde la terminal o la burbuja.

El catálogo legible de workflows (formato `WORKFLOWS.md`, como en Graph) se regenera en cada guardado:
`adb shell run-as com.zevcorp.graph cat files/WORKFLOWS.md`

El modelo por defecto es `gemini-3.5-flash`; se puede cambiar en las prefs (`model`).

## Arquitectura (clean, multiplataforma desde el diseño)

- **`core/`** — Kotlin Multiplatform, puro (sin ninguna API de Android): dominio (`Workflow`, `Step`, `Selector`, `Lesson`) + las tres etapas como casos de uso + puertos.
- **`app/`** — adaptadores Android de esos puertos: AccessibilityService (UiSurface), MediaProjection (ScreenRecorder), Gemini (Analyzer + ComputerUseBrain), archivos JSON (repos), BroadcastReceiver (terminal).

La grabación de workflows vía árbol de UI está implementada **solo para Android**; los puertos ya contemplan las demás superficies (DOM en navegador, AXAccessibility en macOS, UIA en Windows). Ver [ARCHITECTURE.md](ARCHITECTURE.md).
