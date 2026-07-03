# Graph Android — Teaching · Learning · Subconsciente

Sistema que aprende tareas de un video-tutorial del usuario y termina ejecutándolas sin LLM, igual que el aprendizaje humano: te lo muestran → lo haces conscientemente mientras construyes las conexiones → lo haces en automático, más rápido, más barato y más confiable.

Es el mismo principio del repo [Graph](https://github.com/Joseph1356K/Graph) (extensión de Chrome que graba clics/inputs del DOM como steps de workflows), llevado a Android sobre la superficie de accesibilidad.

## Las tres etapas

1. **Teaching** — Tocas *Grabar tutorial* y muestras la tarea en tu teléfono (puedes narrar por voz). Al detener, el video se sube a Gemini, que lo analiza como tutorial y lo destila en una `Lesson` (meta, app, resumen, pasos).

2. **Learning (consciente)** — La `Lesson` pasa como contexto a **Gemini 3.5 Flash con computer use** (Interactions API, entorno `mobile`), que ejecuta la tarea en tu teléfono real (capturas de pantalla → `click`/`type`/`open_app`/… → gestos y accesibilidad). Cada acción se resuelve contra el árbol de UI de accesibilidad y se graba como **step semántico** (viewId ≈ `data-testid`, contentDesc ≈ `aria-label`…), formando un `Workflow` modular con variables `input_N` derivadas de los campos escritos. Si el modelo tiene dudas, **te pregunta**: respondes por texto, por **voz**, o **demostrándolo en pantalla** — tu demo también se graba como steps del workflow, como si las hubiera hecho el asistente.

3. **Subconsciente** — El workflow aprendido se ejecuta **sin LLM**, directamente sobre accesibilidad, activado por comandos de terminal:

   ```bash
   cli/graph list
   cli/graph run wf_1778724462696 --input_3="Juan" --input_5="Pérez"
   # o directamente:
   adb shell am broadcast -a com.zevcorp.graph.RUN -n com.zevcorp.graph/.platform.RunCommandReceiver \
       --es id wf_1778724462696 --es input_3 "Juan"
   ```

## Puesta en marcha

1. Compila e instala: `./gradlew :app:assembleDebug && adb install app/build/outputs/apk/debug/app-debug.apk` (o `gradle` si no usas wrapper).
2. Abre **Graph**, pega tu **Gemini API key** (o `cli/graph set-key <KEY>`).
3. Activa el **servicio de accesibilidad** de Graph (botón en la app).
4. Etapa 1: graba un tutorial → Etapa 2: tócale *Aprender* → Etapa 3: ejecútalo desde la terminal.

El catálogo legible de workflows (formato `WORKFLOWS.md`, como en Graph) se regenera en cada guardado:
`adb shell run-as com.zevcorp.graph cat files/WORKFLOWS.md`

El modelo por defecto es `gemini-3.5-flash`; se puede cambiar en las prefs (`model`).

## Arquitectura (clean, multiplataforma desde el diseño)

- **`core/`** — Kotlin Multiplatform, puro (sin ninguna API de Android): dominio (`Workflow`, `Step`, `Selector`, `Lesson`) + las tres etapas como casos de uso + puertos.
- **`app/`** — adaptadores Android de esos puertos: AccessibilityService (UiSurface), MediaProjection (ScreenRecorder), Gemini (Analyzer + ComputerUseBrain), archivos JSON (repos), BroadcastReceiver (terminal).

La grabación de workflows vía árbol de UI está implementada **solo para Android**; los puertos ya contemplan las demás superficies (DOM en navegador, AXAccessibility en macOS, UIA en Windows). Ver [ARCHITECTURE.md](ARCHITECTURE.md).
