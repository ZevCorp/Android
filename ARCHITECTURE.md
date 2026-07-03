# Arquitectura

Clean architecture con el núcleo 100 % puro y multiplataforma, y una capa de adaptadores por plataforma.

```
┌──────────────────────────────────────────────────────────────┐
│ core/ (Kotlin Multiplatform · commonMain · sin Android)      │
│                                                              │
│  domain/   Workflow · Step · Selector · Variable · Lesson    │
│            Puertos: ScreenRecorder · TutorialAnalyzer ·      │
│            UiSurface · ComputerUseBrain · UserChannel ·      │
│            WorkflowRepository · LessonRepository             │
│                                                              │
│  application/  TeachingStage → LearningStage → Subconscious  │
└──────────────────────────────────────────────────────────────┘
                  ▲ implementan los puertos
┌──────────────────────────────────────────────────────────────┐
│ app/ (Android)                                               │
│  GraphAccessibilityService → UiSurface                       │
│  RecorderService/DroidScreenRecorder → ScreenRecorder        │
│  GeminiTutorialAnalyzer / GeminiComputerUse → LLM            │
│  FileLessonRepo / FileWorkflowRepo (+WORKFLOWS.md) → repos   │
│  RunCommandReceiver + cli/graph → activación por terminal    │
│  MainActivity → UserChannel (texto · voz · demostración)     │
└──────────────────────────────────────────────────────────────┘
```

## Las tres etapas como casos de uso

| Etapa | Caso de uso | Entrada | Salida | LLM |
|---|---|---|---|---|
| 1 · Teaching | `TeachingStage` | video de pantalla (+narración) | `Lesson` | video → análisis |
| 2 · Learning (consciente) | `LearningStage` | `Lesson` | `Workflow` (steps semánticos + variables) | computer use, con preguntas al usuario |
| 3 · Subconsciente | `SubconsciousStage` | `wf_id` + `input_N` desde terminal | ejecución sobre accesibilidad | **ninguno** |

## UiSurface: una superficie por plataforma

`UiSurface` es la abstracción del árbol de UI: ejecuta acciones semánticas y captura las del usuario. Los steps usan `Selector`, el localizador portable (misma jerarquía de prioridad que `buildElementSelector` en la extensión de Chrome de Graph):

| Selector (core) | Android (implementado) | Navegador (futuro) | macOS (futuro) | Windows (futuro) |
|---|---|---|---|---|
| `viewId` | `viewIdResourceName` | `data-testid` / `#id` | `AXIdentifier` | `AutomationId` |
| `text` | `text` | texto visible | `AXTitle` | `Name` |
| `contentDesc` | `contentDescription` | `aria-label` | `AXDescription` | `HelpText` |
| `className` | clase del widget | tag | `AXRole` | `ControlType` |
| `pkg` / `bounds` | paquete + rect en pantalla | origin + rect | app + frame | proceso + rect |

Sólo Android implementa el registro de workflows vía UI-tree (requisito). Para otra plataforma se implementa `UiSurface` (+ recorder/LLM/repos si cambian) sin tocar `core/`.

## Flujo de datos del Learning

1. `LearningStage` pide a `ComputerUseBrain` (Gemini 3.5 Flash + computer use nativo, [Interactions API](https://ai.google.dev/gemini-api/docs/computer-use): `POST /v1beta/interactions`, tool `{type:"computer_use", environment:"mobile"}`, estado en servidor vía `previous_interaction_id`) la siguiente jugada con la captura de pantalla actual.
2. Las acciones (`click`, `type`, `open_app`, `drag_and_drop`, `long_press`…) llegan en coordenadas normalizadas 0-1000; `UiSurface.tapAt/typeAt` las resuelve contra el nodo real del árbol de accesibilidad y devuelve el **step semántico**, que se graba. Los resultados vuelven como `function_result` (con `call_id`, url y screenshot inline); las acciones marcadas con `safety_decision` se confirman automáticamente porque el usuario supervisa el Learning en vivo.
3. `ask_user` → `UserChannel.ask` → respuesta por texto/voz (se informa al modelo) o demo (se activa `setCapturing`, los eventos de accesibilidad del usuario se convierten en steps `USER_DEMO` del mismo workflow).
4. Al terminar, los INPUT se destilan como variables `input_<order>` (igual que Graph) y el workflow se persiste + se regenera `WORKFLOWS.md`.

## Paridad con el repo Graph

| Graph (Chrome/DOM) | Graph Android |
|---|---|
| recorder.js (content script) | `GraphAccessibilityService` (eventos de accesibilidad) |
| CLICK / INPUT / NAVIGATION | CLICK / INPUT / LAUNCH (+SCROLL/KEY/WAIT) |
| selector CSS `[data-testid]`→`#id`→`[name]`→tag | `viewId`→`text`→`contentDesc`→`className+bounds` |
| merge de eventos input (último valor) | merge de `TYPE_VIEW_TEXT_CHANGED` por selector |
| variables `input_N` + defaults | `Workflow.deriveVariables` |
| `node index.js "run wf_x" --input_3=…` | `cli/graph run wf_x --input_3=…` (adb broadcast) |
| WORKFLOWS.md | `files/WORKFLOWS.md` |
| Playwright replay | `SubconsciousStage` sobre accesibilidad |
