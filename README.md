# Graph Android — Teaching · Learning · Subconsciente

Sistema que aprende tareas de un video-tutorial del usuario y termina ejecutándolas sin LLM, igual que el aprendizaje humano: te lo muestran → lo haces conscientemente mientras construyes las conexiones → lo haces en automático, más rápido, más barato y más confiable.

Es el mismo principio del repo [Graph](https://github.com/Joseph1356K/Graph) (extensión de Chrome que graba clics/inputs del DOM como steps de workflows), llevado a Android sobre la superficie de accesibilidad.

## Las tres etapas

1. **Teaching** — Tocas *Grabar tutorial* y muestras la tarea en tu teléfono (puedes narrar por voz). Mientras grabas, **tus clics y tecleos se capturan del árbol de UI** como workflow **borrador** (steps 🟡 DRAFT). Al detener, el video se sube a Gemini, que lo analiza como tutorial y lo destila en una `Lesson` ligada a ese borrador.

2. **Learning (consciente)** — Consolidación supervisada, como el aprendizaje humano: el sistema avanza **un step a la vez ejecutando el workflow por árbol de UI** (no por capturas); tras cada step se toma una captura y **Gemini 3.5 Flash la juzga**. Si el step quedó bien → se marca 🟢 **CONFIRMED** (definitivo, parte de la "red neuronal"). Si falló → **computer use toma el control**, retrocede si hace falta, lo hace él mismo, y el step queda 🔴 **LLM**; el siguiente step vuelve al árbol de UI. Re-ejecutar el learning reintenta los rojos. Si el modelo duda, te pregunta (texto, **voz** o demostración). Las lecciones sin borrador corren el modo libre clásico (el agente ejecuta todo y se graba como DRAFT).

3. **Subconsciente** — Ejecución híbrida: los steps 🟢 y 🟡 corren **sin LLM** sobre accesibilidad; los 🔴 se **delegan puntualmente a Gemini 3.5 Flash**. Activado por terminal:

   ```bash
   cli/graph list
   cli/graph run wf_1778724462696 --input_3="Juan" --input_5="Pérez"
   # o directamente:
   adb shell am broadcast -a com.zevcorp.graph.RUN -n com.zevcorp.graph/.platform.RunCommandReceiver \
       --es id wf_1778724462696 --es input_3 "Juan"
   ```

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
