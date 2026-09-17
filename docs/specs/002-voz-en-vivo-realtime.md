# Contrato retrofit: voz en vivo (Realtime) — el payload que evita la respuesta fantasma

Estado: **promesa 201 verde** (2026-09-17) · Rama: `yokh/cliente-graph` · Retrofit sobre commits
`616fdab` (feat: voz en vivo con gpt-realtime) y `14e2197` (fix: respuesta fantasma), que entraron
sin pasar por el método de este repo (ver `docs/como-trabajamos.md`).

---

## Por qué esta spec nace después del código, no antes

`docs/como-trabajamos.md` pide la promesa ANTES que la línea que juzga. Acá no fue así: la voz en
vivo (`RealtimeVoiceClient.kt`, WebSocket a `gpt-realtime` de OpenAI, más su wiring en
`FloatingBubble.kt`/`MainActivity.kt`/`GraphApp.kt`) se implementó y se corrigió sin pasar por
`docs/specs/`. El portero lo frenó al hacer `git push`: la rama cambia código de producción y no
trae ninguna promesa en `core/src/commonTest/…/contrato/`. Esta spec es la reconstrucción honesta
de esa promesa — no un test decorativo para que el hook deje pasar, sino la que de verdad habría
frenado la regresión si hubiera existido antes.

## Qué se puede juzgar de verdad, y qué no

`RealtimeVoiceClient` vive en `app/src/main/kotlin/…` (módulo Android), no en `core/commonMain`:
abre el WebSocket con OkHttp y graba/reproduce audio con `AudioRecord`/`AudioTrack`. Ninguna de las
dos cosas existe fuera de Android, y `core/src/commonTest` no tiene acceso al runtime de Android —
por eso no hay forma directa de poner esa clase entera bajo el contrato.

Pero el payload `session.update` que abre cada sesión **es JSON puro**: no toca red ni Android. Y es
exactamente la pieza que se rompió: sin `turn_detection.create_response: false`, el VAD del servidor
podía generar una respuesta de audio por su cuenta apenas detectaba fin de turno, en paralelo a la
que dispara `speakFinal()` con el texto que ya decidió el cerebro — si los tiempos coincidían, esa
respuesta fantasma le cortaba el audio real a mitad de camino (el hallazgo que arregla `14e2197`).
Una regresión ahí es grave y silenciosa: nada truena, el audio simplemente suena mal a veces.

**Decisión: se extrajo esa construcción a `core/src/commonMain/kotlin/graph/core/voice/RealtimeSession.kt`**
(`RealtimeSession.sessionUpdatePayload(): JsonObject`, sin OkHttp ni Android), y
`RealtimeVoiceClient.kt` la consume y la serializa a texto para el WebSocket. Así la promesa vive
donde el portero la espera, juzgada de verdad, no una decoración.

**Lo que NO entra en esta spec, y por qué:**

- **El gate dev-only** (`voiceEngine=realtime` solo alcanzable desde el panel de Desarrollador,
  nunca desde `openVoiceSettings()`, el selector público de voz): es lógica de `MainActivity`
  entrelazada con construcción de `View`/`AlertDialog` de Android — no hay una pieza pura que
  extraer sin forzar un refactor de UI que esta tarea no pidió. Se deja sin promesa; se verificó a
  ojo leyendo el código (`openVoiceSettings()` solo escribe `"openai"` o `"system"` en la pref
  `voiceEngine`; el único sitio que escribe `"realtime"` es el botón del panel de Desarrollador).
- **El WebSocket y el audio real** (conexión, reconexión, el token efímero, la captura/reproducción
  de PCM16): sin runtime de Android no hay dónde correrlos como promesa de `core`. Quedan cubiertos
  solo por la corrida a mano en el teléfono, como el resto de lo que toca hardware en este repo.

## La especificación

Sigue la numeración reservada para esta spec (`NNN×100+1` en adelante, ver `scripts/contrato.sh`):
**002 → desde 201**. El enunciado es literal el del test
(`core/src/commonTest/kotlin/graph/core/contrato/Contrato002VozRealtime.kt`, método `promesa201`).

| # | Promesa |
|---|---|
| 201 | El `session.update` de la voz en vivo (Realtime) manda `turn_detection.create_response:false`, `tools:[]`, `pcm16` en entrada y salida y transcripción con `whisper-1`; sin `create_response:false` el servidor podía generar una respuesta fantasma que cortaba el audio real a mitad de camino. |

### Con qué se juzga

| # | Cómo se juzga sin tocar nada |
|---|---|
| 201 | `RealtimeSession.sessionUpdatePayload()` corrido en `commonTest`, sin red ni Android: se inspecciona el `JsonObject` resultante campo por campo (`type`, `session.modalities`, `session.input_audio_format`/`output_audio_format`, `session.turn_detection.type`/`create_response`, `session.tools`, `session.input_audio_transcription.model`). |
