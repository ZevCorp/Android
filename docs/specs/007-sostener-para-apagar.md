# Plan de implementación: sostener la burbuja 5 segundos la apaga — explota y Ü se duerme

Estado: **implementada** (2026-09-16; promesas 710-717 verdes, contrato de 8 promesas; cada una se vio ROJA leyendo
las fuentes sin el cableado antes de escribirlo). Nace de un pedido del Capitán: «me gustaría que cuando lo sostengo
durante 5 segundos haga una animación como de explotarse y ya se apague o cierre el agente» · Rama:
`yokh/apagar-con-gesto`.

**Control (2026-09-16): NO APROBADO, corregido en el mismo commit.** El uso más natural del gesto (sostener quieta,
sin arrastrar, que es exactamente lo previsto) rompía el apagado apenas se soltaba el dedo — ver los dos hallazgos
de abajo y la promesa nueva 718. Contrato ahora en 9 promesas (710-718); 712 se endureció con el umbral corregido.

- **[ALTA, corregido]** Soltar el dedo justo después de que la explosión disparó caía, sin condición, en
  `v.performClick()` → `onBubbleTap()` → `openPanel()`: el apagado duraba un cuarto de segundo antes de que el mismo
  gesto lo deshiciera. Se agregó `shutdownFired` (bandera de instancia, `false` por defecto): `explodeAndSleep()` la
  pone en `true` al arrancar, `ACTION_DOWN` la resetea a `false` en cada toque nuevo, y `ACTION_UP`/`ACTION_CANCEL`
  la mira ANTES de decidir `v.performClick()`: si el apagado ya disparó en este mismo toque, no hace nada (ni click,
  ni fling, ni nada del resto del arrastre). El siguiente toque, con Ü ya despierta, se comporta como siempre. Ver
  promesa 718.
- **[MEDIA, corregido]** El umbral de 120 (≈11 px, pensado para distinguir tap de arrastre en cientos de ms)
  también cancelaba el apagado pendiente — aplicado a un sostenido de 5 s, el temblor normal de una mano real lo
  supera con facilidad, cortando el apagado sin que la persona sienta que hizo algo mal. Se separaron los dos
  criterios: el umbral chico (120) sigue decidiendo tap-vs-arrastre en el instante y ya NO cancela el apagado; un
  umbral propio, `SHUTDOWN_CANCEL_DISTANCE_SQ = 120 * 36` (6× la distancia lineal, ~66 px en vez de ~11 px), es el
  único que cancela el apagado por movimiento real. Ver promesa 712 (reescrita).
- **[MEDIA, test]** Dos jueces por texto (711 y 714) daban falso verde si se borraba la llamada real pero quedaba un
  COMENTARIO con las mismas palabras. Se agregó `sinComentarios()` (descarta líneas que empiezan con `//` antes de
  buscar el patrón) y se aplicó a esas dos promesas puntuales, sin rehacer el juez entero.

**Control (2026-09-16, segunda pasada): NO APROBADO, corregido en el mismo commit.** Dos hallazgos MEDIA sobre
`WakeWordDock.kt` (no altos, pero reales). Contrato ahora en 10 promesas (710-720).

- **[MEDIA, corregido]** El `getOrElse { "" }` de `loop()` tragaba una `CancellationException` como cualquier otro
  error. Hoy no explota solo porque el `delay()` siguiente corta la iteración por casualidad, no por garantía:
  cualquier código futuro entre ese `getOrElse` y la próxima suspensión reabre el problema. Se agregó el
  relanzamiento explícito: `.getOrElse { e -> if (e is CancellationException) throw e else "" }`. Ver promesa 720.
- **[MEDIA, corregido]** `start()` solo miraba `loopJob?.isActive` para decidir si ya había un bucle corriendo, pero
  `stop()` anulaba `loopJob` en forma síncrona mientras la limpieza real de la corrutina vieja (`transcriber = null`,
  `listening = false`) corría después, de forma asíncrona. Un `stop()` (sostener para dormir) seguido de un
  `start()` casi inmediato (`wakeIfAsleep()`) podía dejar que la limpieza vieja pisara el `transcriber` de la
  iteración NUEVA y activa, dejándolo en `null` sin que nadie lo pare — el reconocedor seguiría escuchando con Ü
  dormido, el mismo síntoma que la promesa 719 evitó por otra vía. Se agregó un contador de generación (`gen`):
  `start()` lo incrementa y lo captura en `myGen`, y la limpieza de fin de iteración solo escribe si
  `myGen == gen`. Ver promesa 720.

Esta spec numera sus promesas **desde 710** (ver `docs/como-trabajamos.md`). Si al integrar con la otra mitad de la
007 (`ü-responde-a-su-nombre`, en otra rama) los números chocan, se renumera en ese momento.

---

## Diagnóstico: qué ya existía (`fa72c6b`)

| Qué | Medida | Fuente |
|---|---|---|
| La burbuja ya se arrastra con un `OnTouchListener` propio, no un `OnLongClickListener` | `attachDrag` maneja `ACTION_DOWN/MOVE/UP/CANCEL` a mano, con `moved` para distinguir toque de arrastre | `FloatingBubble.kt:264-307` |
| Ya hay un armado por tiempo sosteniendo el dedo quieto en una esquina | `VoiceDock.arm` cuenta 2,5 s antes de `dock()`; conviven sin tocarse, cada uno con su propio temporizador | `VoiceDock.kt:100-108` |
| Ya hay animación de escala con `ValueAnimator` + interpoladores de rebote | `idleEase`/`idleGrow` (`OvershootInterpolator`) en `animateScale` | `FloatingBubble.kt:184-186`, `:248-261` |
| El cierre prolijo del modo reunión ya existe y persiste sus notas | `VoiceDock.destroy()`/`undock()` | `VoiceDock.kt:159-168`, `:291-300` |
| No hay ningún camino de "apagar todo" invocable desde la propia app | `GraphAccessibilityService.onDestroy()` solo corre cuando ANDROID para el servicio (Ajustes); nada de adentro puede pedirlo | `GraphAccessibilityService.kt:50-55` |
| Android no deja que una app apague su propio permiso de accesibilidad | Restricción del sistema, no del código: `onInterrupt()`/`onDestroy()` los dispara Android, nunca la propia app | — |

**Lo que esto significa:** no hay que inventar un long-press nuevo ni traer una librería de partículas — hay que
extender el `OnTouchListener` que ya cuenta el arrastre con un segundo temporizador (5 s en vez de 2,5 s), y
construir un apagado "de mentiras": no del servicio (imposible desde adentro), sino de la burbuja y de todo lo que
escucha.

---

## Restricción real de Android (para que quede escrito, no oculto)

Una app **no puede** apagar su propio permiso de accesibilidad desde adentro; eso solo lo hace la persona desde
Ajustes de Android, por seguridad del sistema. Así que «apagar/cerrar el agente» con este gesto significa: **la
burbuja desaparece de la pantalla, se corta el modo reunión y cualquier voz sonando, y Ü queda dormido** hasta que
se lo vuelva a abrir (abriendo la app, o manteniendo apretado el botón de encendido, que invoca `AssistActivity`).
El permiso de accesibilidad sigue concedido: por eso volver a activarlo es instantáneo, sin ir a Ajustes.

---

## La especificación

| Archivo | Promesas |
|---|---|
| `core/src/jvmTest/kotlin/graph/core/contrato/Contrato007SostenerParaApagar.kt` (lee las fuentes de `app`, igual que la 259: solo `jvmTest` lee disco) | 710-720 |

| # | Promesa |
|---|---|
| 710 | Sostener la burbuja quieta, sin moverla y sin soltarla, arma un temporizador de 5 segundos hacia el apagado; con el dedo quieto ese tiempo entero, se dispara. |
| 711 | Soltar el dedo (`ACTION_UP` o `ACTION_CANCEL`) antes de que se cumplan los 5 segundos cancela el apagado sin efecto: el toque simple y el arrastre normal siguen funcionando igual que siempre. |
| 712 | Un movimiento FRANCO de la burbuja cancela el apagado pendiente, con un umbral propio y bastante más tolerante que el de tap/arrastre (para que el temblor normal de una mano sostenida 5 s no lo corte solo), sin tocar el resto del arrastre ni el aviso al modo reunión. |
| 713 | Cumplidos los 5 segundos, la burbuja anima una explosión (escala hacia arriba y opacidad hacia 0 con `ValueAnimator`, sin traer ninguna librería nueva) y solo al terminar la animación dispara el apagado. |
| 714 | El apagado corta el modo reunión si estaba activo (persistiendo sus notas) y detiene cualquier voz sonando, la del sistema y la de OpenAI. |
| 715 | El apagado quita la vista de la burbuja de la pantalla y deja registrado que Ü se apagó por este gesto, con la misma medida que cualquier otra línea del log (nunca texto libre fuera de la puerta de telemetría). |
| 716 | Despertar a Ü vuelve a mostrar la misma burbuja sin recrear el motor de voz ni los sonidos, y no hace nada si ya estaba despierta. |
| 717 | Abrir la app de nuevo (`dockToApp`/`setHiddenForApp`) o el asistente del botón de encendido despiertan a Ü si estaba dormido por el gesto. |
| 718 | Si el apagado ya disparó DENTRO del mismo toque (la burbuja explotó sin que hubiera arrastre), soltar el dedo justo después no cuenta como un click normal: no cae en `performClick()` ni reabre el panel. El próximo toque, con Ü ya despierta, se comporta como siempre. |
| 719 | Mientras Ü está dormido no se puede seguir escuchando la palabra de activación: `canListenForWakeWord()` excluye el estado dormido y `sleep()` detiene ese bucle; si el interruptor seguía prendido, despertar lo retoma. |
| 720 | En `WakeWordDock`, una cancelación de la corrutina de escucha (`CancellationException`) se relanza en vez de tragarse como un error más, y un `start()` inmediato después de un `stop()` nunca deja que la limpieza de la iteración vieja pise el `transcriber`/`listening` de la iteración nueva (token de generación). |

### La regla, en una línea por clase

`FloatingBubble.attachDrag` arma, en `ACTION_DOWN`, un `Job` que espera `SHUTDOWN_HOLD_MS` (5.000 ms) y llama a
`explodeAndSleep()`. Se cancela en `ACTION_MOVE` cuando el movimiento supera `SHUTDOWN_CANCEL_DISTANCE_SQ` (un
umbral propio y más tolerante que el de tap/arrastre) y al soltar (`ACTION_UP`/`ACTION_CANCEL`), sin agregar ninguna
condición nueva sobre el estado del modo reunión: los dos temporizadores (el de 2,5 s de `VoiceDock.arm` y el de 5 s
de este apagado) corren sobre el mismo flujo de eventos sin mirarse entre sí.

`ACTION_UP`/`ACTION_CANCEL` mira además `shutdownFired` (bandera de instancia que `explodeAndSleep()` pone en
`true` al arrancar y que cada `ACTION_DOWN` resetea a `false`): si el apagado ya disparó en este mismo toque, no
llama a `v.performClick()` ni a nada del resto del arrastre — el gesto que apagó a Ü no puede además reabrirlo.

`explodeAndSleep()` anima la burbuja (escala 1× → 2,2×, opacidad 1 → 0, ~260 ms) y al terminar llama a `sleep()`:
corta `VoiceDock` (`destroy()`, que persiste sus notas igual que `undock()`), detiene el TTS del sistema y el de
OpenAI, quita la vista de la burbuja del `WindowManager` y deja una línea de log. `wakeIfAsleep()` es su inversa:
vuelve a agregar la MISMA vista al `WindowManager` sin recrear `TextToSpeech` ni `SoundPool` — se llama desde
`dockToApp()`, `setHiddenForApp()` (los dos caminos por los que `MainActivity.onResume()` ya toca la burbuja al
volver a la app) y desde `AssistActivity.onCreate()` (el asistente del botón de encendido).

`WakeWordDock.loop()` relanza la `CancellationException` de `t.listen()` en vez de tragarla en el `getOrElse`, y
`start()` arma un contador de generación (`gen`, incrementado en cada llamada) que cada iteración de `loop()`
captura como `myGen`: la limpieza de fin de iteración (`transcriber = null`, `listening = false`) solo escribe si
`myGen` sigue siendo la generación vigente, así un `stop()` seguido de un `start()` casi inmediato nunca deja que
la limpieza de la iteración vieja pise el estado de la nueva.

### Con qué se juzga cada una

Es Android puro —gestos con la mano, `ValueAnimator`, `WindowManager`— y no corre en `jvmTest`, pero lo que promete
está escrito: dónde se arma el temporizador, dónde se cancela y qué corta la explosión antes de quitar la burbuja.
Se juzga leyendo las fuentes de `app`, igual que la promesa 259 y las 602/611/613/615/619/621 de la spec 006.

| # | Cómo se juzga sin tocar nada |
|---|---|
| 710 | `attachDrag` declara `SHUTDOWN_HOLD_MS = 5_000L` y su bloque `ACTION_DOWN` arma `scope.launch { delay(SHUTDOWN_HOLD_MS); explodeAndSleep() }` |
| 711 | El bloque `ACTION_UP, ACTION_CANCEL` cancela ese `Job` antes de decidir el resto, y sigue conteniendo `v.performClick()` y `flingToEdge(...)` (descartando líneas `//` antes de buscar) |
| 712 | `ACTION_MOVE` deja el umbral chico (120) solo para `moved`/`voiceDock.track`, y cancela el `Job` con un umbral propio `SHUTDOWN_CANCEL_DISTANCE_SQ` declarado entre 5× y 8× el chico (en distancia al cuadrado) |
| 713 | `explodeAndSleep()` usa `ValueAnimator`, cambia `bubble.scaleX/scaleY` y `bubble.alpha`, y su `onAnimationEnd` llama a `sleep()` |
| 714 | `sleep()` llama a `voiceDock.destroy()` (o `undock()`) y a `tts?.stop()` y `openAiTts.stop()` (descartando líneas `//` antes de buscar) |
| 715 | `sleep()` llama a `wm.removeView(bubble)`, marca `asleep = true`, y su `LogBus.log(tag, …)` usa un tag de la lista cerrada de `PuertaDeTelemetria.TAGS` |
| 716 | `wakeIfAsleep()` corta temprano si `!asleep`, llama a `wm.addView(bubble, bubbleParams)` y no contiene `TextToSpeech(` ni `SoundPool.Builder` |
| 717 | `dockToApp()` y `setHiddenForApp()` llaman a `wakeIfAsleep()`, y `AssistActivity` llama a `bubble?.wakeIfAsleep()` |
| 718 | `explodeAndSleep()` marca `shutdownFired = true`, `ACTION_DOWN` la resetea a `false`, y `ACTION_UP, ACTION_CANCEL` la mira con `if (shutdownFired) { … } else if (!moved) v.performClick()` |
| 719 | `canListenForWakeWord()` incluye `!asleep`, `sleep()` llama a `wakeWordDock.stop()` (descartando líneas `//` antes de buscar), y `wakeIfAsleep()` llama a `wakeWordDock.start(...)` si `wakeWordEnabled` sigue en `true` |
| 720 | `WakeWordDock` declara `private var gen = 0`, `start()` la incrementa (`++gen`) y se la pasa a `loop()`, `loop()` relanza con `is CancellationException) throw` dentro del `getOrElse` de `t.listen()`, y su limpieza de fin de iteración va dentro de `if (myGen == gen) { listening = false; transcriber = null }` |

### Sabotajes (cada uno pone roja su promesa)

| # | Sabotaje | Lo que debe decir el juez |
|---|---|---|
| 710 | `ACTION_DOWN` no arma ningún temporizador | roja: no encuentra `scope.launch { delay(SHUTDOWN_HOLD_MS)…` |
| 711 | `ACTION_UP`/`ACTION_CANCEL` no cancela el `Job` (dispara aunque se soltó antes de los 5 s), o queda solo como comentario | roja: `sinComentarios()` no encuentra `shutdownJob?.cancel()` real en ese bloque |
| 712 | el movimiento franco no cancela el apagado pendiente, o vuelve a cancelarlo con el umbral chico (120) en vez del propio | roja: no encuentra el `if (shutdownJob != null && … > SHUTDOWN_CANCEL_DISTANCE_SQ)`, o `SHUTDOWN_CANCEL_DISTANCE_SQ` sale de 5×-8× el umbral chico, o `shutdownJob?.cancel()` reaparece dentro del `if (moved || … > 120)` |
| 713 | la explosión no anima nada, o dispara `sleep()` antes de terminar la animación | roja: no hay `ValueAnimator` o `onAnimationEnd` no llama a `sleep()` |
| 714 | `sleep()` no corta el modo reunión si estaba activo, o queda solo como comentario | roja: `sinComentarios()` no encuentra `voiceDock.destroy()`/`undock()` real |
| 715 | `sleep()` no quita la burbuja de la pantalla, o loguea con un tag fuera de la lista cerrada | roja: no encuentra `wm.removeView(bubble)`, o el tag no está en `PuertaDeTelemetria.TAGS` |
| 716 | `wakeIfAsleep()` recrea el TTS o el SoundPool en vez de reusarlos | roja: encuentra `TextToSpeech(` o `SoundPool.Builder` en su cuerpo |
| 717 | `dockToApp()`/`setHiddenForApp()`/`AssistActivity` dejan de despertar a Ü | roja: no encuentra `wakeIfAsleep()` en alguno de los tres |
| 718 | se quita `shutdownFired` (o su reseteo, o el `if` que la mira en `ACTION_UP`/`ACTION_CANCEL`): el toque que apaga a Ü vuelve a colar un `v.performClick()` sin condición | roja: no encuentra la bandera, su reseteo en `ACTION_DOWN`, o el `if (shutdownFired) { … } else if (!moved) v.performClick()` en ese orden |
| 719 | se quita `&& !asleep` de `canListenForWakeWord()`, o `wakeWordDock.stop()` de `sleep()` (o queda solo como comentario), o `wakeWordDock.start(...)` de `wakeIfAsleep()` | roja: no encuentra `!asleep` en `canListenForWakeWord()`, o `sinComentarios()` no encuentra `wakeWordDock.stop()` real en `sleep()`, o no encuentra el `if (wakeWordEnabled) wakeWordDock.start(...)` en `wakeIfAsleep()` |
| 720 | en `WakeWordDock.loop()` se quita el `if (e is CancellationException) throw e` del `getOrElse` (vuelve a tragarla como `""`), o por separado se quita el chequeo de generación (`if (myGen == gen)`) antes de limpiar `transcriber`/`listening` | roja: no encuentra `is CancellationException) throw` dentro del `getOrElse` de `t.listen()`, o no encuentra `if (myGen == gen) { listening = false … transcriber = null }` envolviendo la limpieza |

---

## Lo que NO entra, y por qué

- **Apagar el servicio de accesibilidad de verdad.** Android no lo permite desde adentro; es la restricción de
  arriba, no una promesa pendiente.
- **Un sonido de explosión nuevo.** No hay ningún efecto existente (`playTick`/`playListenChime`) que encaje con un
  estallido; en vez de grabar uno, el efecto es visual (escala + opacidad) más una vibración corta.
- **Frenar una ejecución en curso.** Mientras Ü ejecuta una tarea, la burbuja pasa a pass-through
  (`companion(true)` → `FLAG_NOT_TOUCHABLE`) y no recibe toques: este gesto físicamente no se puede disparar en medio
  de una corrida, así que no hay nada que cortar ahí.

## Límites conocidos

- El permiso de accesibilidad sigue concedido todo el tiempo: "apagado" es un estado de la app (`asleep`), no del
  sistema. Un reinicio del teléfono, o desactivar y reactivar el servicio desde Ajustes, también despierta a Ü por
  las vías normales (`onServiceConnected` crea una burbuja nueva).
- Mientras Ü está dormido, el aprendizaje pasivo y el resto del servicio de accesibilidad (`onAccessibilityEvent`)
  siguen corriendo: lo único que se apaga es la burbuja, el modo reunión y la voz. Apagar también eso es otra
  decisión, fuera de esta spec.
