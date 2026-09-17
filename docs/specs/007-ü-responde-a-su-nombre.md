# Ü responde a su nombre — palabra de activación para empezar a escuchar sin tocar nada

Estado: **implementada** (2026-09-16; promesas 701-705 verdes, contrato de 159 promesas; cada una se vio ROJA con un
sabotaje real). Nace de un pedido del Capitán: «me gusta llamarlo por su nombre para que se active... puede ser hola ü o
ey ü o algo similar para que empiece modo escucha y pueda ayudar, la idea es que me responda amigable respondiendo el
saludo y mostrando que sí está activo y escuchando, para saber que el agente sí está en modo ejecución sin necesidad de
oprimirle el botón de audio, para tener una conversación fluida» · Rama: `yokh/palabra-de-activacion`.

Hoy el Modo Reunión (spec de la ergonomía del silencio, `VoiceDock`) solo se enciende arrastrando la burbuja a una esquina
y manteniéndola 2.5 s. Esta spec agrega una segunda puerta de entrada, por voz: decir el nombre de Ü enciende el mismo
Modo Reunión, con un saludo amistoso que confirma que está escuchando.

---

## Diagnóstico: lo ya investigado (no se remide, viene del briefing)

| Qué | Dónde |
|---|---|
| El Modo Reunión completo: escucha en segmentos, `taskQueue`/`taskWorker` en paralelo, `muted`/`toggleMute()` | `app/…/ui/VoiceDock.kt` |
| El reconocedor gratis del sistema (no manda audio a ningún servidor) | `app/…/voice/Transcribers.kt` → `SystemTranscriber` |
| Narración visible (`narrate`) y voz hablada (`speak`, OpenAI con fallback a TTS del sistema) | `app/…/ui/FloatingBubble.kt` |
| El aviso flotante reusable (`showBadge`/`hideBadge`) | `app/…/ui/VoiceDock.kt` |

## Decisión de costo y privacidad (tomada por el Capitán, no se repregunta)

Escuchar la palabra tiene que ser **gratis y liviano**: reconocedor del sistema, con reconocimiento **en el dispositivo**
pedido explícitamente (`RecognizerIntent.EXTRA_PREFER_OFFLINE`). Solo al detectar la palabra se entra al Modo Reunión de
verdad, que decide solo entre Deepgram o el sistema, igual que siempre. **Nunca se guarda ni se loguea la frase completa**
que se dijo antes de detectar la palabra — solo un evento de que se detectó, sin texto. Esta escucha corre solo con la
pantalla encendida y el servicio de accesibilidad activo.

**Límite conocido, documentado a propósito:** no sobrevive a pantalla apagada ni a Doze. Un motor dedicado de palabra de
activación que sí sobreviva queda para una versión futura.

---

## La especificación

Esta spec numera sus promesas **desde 701** (ver `docs/como-trabajamos.md`).

| Archivo | Promesas |
|---|---|
| `core/src/commonTest/kotlin/graph/core/contrato/Contrato007ÜResponde.kt` (función pura, sin Android) | 701, 702 |
| `core/src/jvmTest/kotlin/graph/core/contrato/Contrato007WakeWordEnApp.kt` (lee las fuentes de `app`, mismo criterio que 246/256/259 (`Contrato002VozEnVivoDev.kt`) y 602/611/613/615/619/621 (`Contrato006LoQueVe.kt`): `app` no corre en `jvmTest`) | 703, 704, 705 |

| # | Promesa |
|---|---|
| 701 | `PalabraDeActivacion.activa(texto)` reconoce que la persona llamó a Ü por su nombre con «hola ü», «hola u», «ey ü», «ey u», «oye ü», «oye u», «hey ü»/«hey u» (variante razonable, se pronuncia igual que «ey»), y con «ü»/«u» sola cuando es TODA la frase — sin importar mayúsculas, tildes/diéresis ni signos de puntuación alrededor. |
| 702 | `PalabraDeActivacion.activa(texto)` NO activa con «una silla», «hola» sola (sin nombrar a Ü), «hola tú», «tuve», ni con ninguna palabra que solo contenga la letra u en medio de otra palabra (p. ej. «cuchara», «auto»): el nombre tiene que ser una palabra completa de la frase, no una coincidencia parcial. |
| 703 | La escucha de la palabra vive en `app/`, en su propio archivo, y usa `SystemTranscriber` pidiendo reconocimiento en el dispositivo (`EXTRA_PREFER_OFFLINE`); su bucle nunca nombra `MeetingBrain` ni `taskQueue`, y nunca pasa la frase escuchada completa a `LogBus.log` (ni loguea nada que no sea un aviso fijo, sin la variable del texto). |
| 704 | El interruptor «Activar «Hola Ü»» vive en el panel principal de `MainActivity` (no en el panel de desarrollador), se guarda en las preferencias y arranca APAGADO hasta que el usuario lo prende una vez. |
| 705 | Al detectar la palabra: suena un aviso ya existente, la burbuja reacciona con una animación ya existente, se narra y se habla un saludo elegido al azar entre variantes, el badge de `VoiceDock` avisa «te escucho» reusando su mecanismo, y se entra al Modo Reunión llamando a `dock()` de forma programática (sin coordenadas de arrastre) — sin romper cómo se exponen `docked`/`listening`. |

---

## Las frases: lista cerrada, documentada

**Activan** (normalizadas: minúsculas, sin tildes ni diéresis, sin signos de puntuación, palabra completa):

`hola ü` · `hola u` · `ey ü` · `ey u` · `oye ü` · `oye u` · `hey ü` · `hey u` · `ü` (sola, toda la frase) · `u` (sola, toda
la frase)

`hey` se agrega como variante razonable: en el habla casual se pronuncia igual que `ey` y el reconocedor a veces la
transcribe así (préstamo del inglés ya común en español hablado).

**NO activan** (falsos positivos que la regla tiene que evitar):

`una silla` · `hola` (sola, sin nombrar a Ü) · `hola tú` (tú ≠ ü: dos letras, no una) · `tuve` · cualquier palabra que solo
contenga la letra u en medio de otra palabra (`cuchara`, `auto`, `su`, `tu`) · cualquier frase de tres o más palabras
(el nombre se dice solo o con un prefijo corto, nunca en medio de una oración larga)

## La regla, en una línea

`graph.core.voz.PalabraDeActivacion.activa(texto)` normaliza el texto (minúsculas; tildes y diéresis fuera; todo lo que no
sea letra o dígito pasa a espacio; espacios repetidos colapsados) y compara por **palabras completas**, nunca por
substring: activa si la frase normalizada es exactamente `"u"`, o exactamente `"<prefijo> u"` con el prefijo en la lista
cerrada `{hola, ey, hey, oye}`. Comparar por palabra completa (no `contains`) es lo que separa `hola tú` (rechaza: la
segunda palabra es `tu`, no `u`) de `hola u` (acepta), y lo que separa `cuchara` (una sola palabra, no es `u`) de `u` sola.

## Implementación

**Núcleo (`core/`, puro):** `graph/core/voz/PalabraDeActivacion.kt` — un `object` con `activa(texto: String): Boolean` y
la normalización interna. Sin Android, sin coroutines: se prueba con `commonTest` de verdad, sin fuente ni mock.

**App (`app/`), tres piezas:**

- `voice/Transcribers.kt` — `SystemTranscriber` gana un parámetro `preferOffline: Boolean = false` (default preserva el
  comportamiento existente para `defaultTranscriber`/`AssistActivity`); cuando es `true`, el intent suma
  `RecognizerIntent.EXTRA_PREFER_OFFLINE = true`.
- `ui/WakeWordDock.kt` (nuevo) — el bucle liviano: mientras `shouldListen()` sea verdadero, escucha un tramo con
  `SystemTranscriber(service, preferOffline = true)`, lo pasa por `PalabraDeActivacion.activa`; si coincide llama a
  `onDetected()`, si no, descarta el texto (nunca lo loguea) y vuelve a escuchar. Nunca nombra `MeetingBrain` ni
  `taskQueue`: no sabe que existen.
- `ui/VoiceDock.kt` — dos agregados mínimos, sin tocar la lógica existente: `dockNow()` (wrapper público de `dock()`
  privado, para entrar al Modo Reunión sin pasar por el arrastre) y `showListeningBadge()` (wrapper público de
  `showBadge("👂 te escucho…")`, reusando el mecanismo existente).
- `ui/FloatingBubble.kt` — arma el `WakeWordDock` con `shouldListen` (apagado si: el interruptor está OFF en prefs, no
  hay accesibilidad, el Modo Reunión ya está anclado o escuchando, hay una ejecución en curso, la escucha en vivo de
  ejecución está prendida, o hay un panel abierto) y `onDetected` (chime + `pulse()` + saludo al azar por `narrate`/
  `speak` + `voiceDock.showListeningBadge()` + `voiceDock.dockNow()`). `show()` arranca el `WakeWordDock` si la
  preferencia ya estaba prendida de una sesión anterior.
- `ui/MainActivity.kt` — botón/interruptor «Activar «Hola Ü»» junto a «Hacer de Ü tu asistente», guardado en
  `prefs.getBoolean("wakeWordEnabled", false)` y avisando a la burbuja activa (si la hay) al cambiar.

No se toca `listenLoop()`, `taskQueue`, `taskWorker` ni `muted`/`toggleMute()`: el Modo Reunión que ya existe no cambia.

## Tabla de sabotajes

| Sabotaje | Rompe |
|---|---|
| S701a | quitar el mapa de tildes/diéresis (`ü`→`u`, `ú`→`u`, …) de la normalización | 701 |
| S701b | quitar `hey` de los prefijos válidos | 701 |
| S702a | cambiar la comparación de palabra completa por `contains` (substring) | 702 |
| S702b | no separar el token final: dejar pasar frases de tres o más palabras | 702 |
| S703 | el bucle de la palabra construye el transcriptor sin `preferOffline = true` | 703 |
| S704 | el interruptor vive fuera del panel principal, o arranca prendido por defecto | 704 |
| S705 | `onDetected` no llama a `dockNow()`, o rompe la exposición de `docked`/`listening` | 705 |

## Lo que NO entra en esta spec

- Un motor de palabra de activación que sobreviva a pantalla apagada/Doze (requiere un servicio dedicado; queda para una
  versión futura, documentado arriba como límite conocido).
- Cambiar el Modo Reunión en sí (`listenLoop`, `taskQueue`, `muted`): esta spec solo agrega una segunda puerta de entrada.
- Telemetría remota de la detección: el evento queda en el log local (`LogBus`); sumarlo a `PuertaDeTelemetria` con su
  propia promesa es una ampliación futura, no parte de este pedido.
