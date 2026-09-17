# Plan de implementación: sostener la burbuja 5 segundos la apaga — explota y Ü se duerme

Estado: **implementada** (2026-09-16; promesas 701-708 verdes, contrato de 8 promesas; cada una se vio ROJA leyendo
las fuentes sin el cableado antes de escribirlo). Nace de un pedido del Capitán: «me gustaría que cuando lo sostengo
durante 5 segundos haga una animación como de explotarse y ya se apague o cierre el agente» · Rama:
`yokh/apagar-con-gesto`.

Esta spec numera sus promesas **desde 701** (ver `docs/como-trabajamos.md`). Si al integrar con la otra mitad de la
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
| `core/src/jvmTest/kotlin/graph/core/contrato/Contrato007SostenerParaApagar.kt` (lee las fuentes de `app`, igual que la 259: solo `jvmTest` lee disco) | 701-708 |

| # | Promesa |
|---|---|
| 701 | Sostener la burbuja quieta, sin moverla y sin soltarla, arma un temporizador de 5 segundos hacia el apagado; con el dedo quieto ese tiempo entero, se dispara. |
| 702 | Soltar el dedo (`ACTION_UP` o `ACTION_CANCEL`) antes de que se cumplan los 5 segundos cancela el apagado sin efecto: el toque simple y el arrastre normal siguen funcionando igual que siempre. |
| 703 | Un movimiento significativo de la burbuja (el mismo umbral que ya usa el arrastre) cancela el apagado pendiente, sin tocar el resto del arrastre ni el aviso al modo reunión. |
| 704 | Cumplidos los 5 segundos, la burbuja anima una explosión (escala hacia arriba y opacidad hacia 0 con `ValueAnimator`, sin traer ninguna librería nueva) y solo al terminar la animación dispara el apagado. |
| 705 | El apagado corta el modo reunión si estaba activo (persistiendo sus notas) y detiene cualquier voz sonando, la del sistema y la de OpenAI. |
| 706 | El apagado quita la vista de la burbuja de la pantalla y deja registrado que Ü se apagó por este gesto, con la misma medida que cualquier otra línea del log (nunca texto libre fuera de la puerta de telemetría). |
| 707 | Despertar a Ü vuelve a mostrar la misma burbuja sin recrear el motor de voz ni los sonidos, y no hace nada si ya estaba despierta. |
| 708 | Abrir la app de nuevo (`dockToApp`/`setHiddenForApp`) o el asistente del botón de encendido despiertan a Ü si estaba dormido por el gesto. |

### La regla, en una línea por clase

`FloatingBubble.attachDrag` arma, en `ACTION_DOWN`, un `Job` que espera `SHUTDOWN_HOLD_MS` (5.000 ms) y llama a
`explodeAndSleep()`. Se cancela en el mismo lugar donde ya se distingue toque de arrastre (`moved`) y al soltar
(`ACTION_UP`/`ACTION_CANCEL`), sin agregar ninguna condición nueva sobre el estado del modo reunión: los dos
temporizadores (el de 2,5 s de `VoiceDock.arm` y el de 5 s de este apagado) corren sobre el mismo flujo de eventos
sin mirarse entre sí.

`explodeAndSleep()` anima la burbuja (escala 1× → 2,2×, opacidad 1 → 0, ~260 ms) y al terminar llama a `sleep()`:
corta `VoiceDock` (`destroy()`, que persiste sus notas igual que `undock()`), detiene el TTS del sistema y el de
OpenAI, quita la vista de la burbuja del `WindowManager` y deja una línea de log. `wakeIfAsleep()` es su inversa:
vuelve a agregar la MISMA vista al `WindowManager` sin recrear `TextToSpeech` ni `SoundPool` — se llama desde
`dockToApp()`, `setHiddenForApp()` (los dos caminos por los que `MainActivity.onResume()` ya toca la burbuja al
volver a la app) y desde `AssistActivity.onCreate()` (el asistente del botón de encendido).

### Con qué se juzga cada una

Es Android puro —gestos con la mano, `ValueAnimator`, `WindowManager`— y no corre en `jvmTest`, pero lo que promete
está escrito: dónde se arma el temporizador, dónde se cancela y qué corta la explosión antes de quitar la burbuja.
Se juzga leyendo las fuentes de `app`, igual que la promesa 259 y las 602/611/613/615/619/621 de la spec 006.

| # | Cómo se juzga sin tocar nada |
|---|---|
| 701 | `attachDrag` declara `SHUTDOWN_HOLD_MS = 5_000L` y su bloque `ACTION_DOWN` arma `scope.launch { delay(SHUTDOWN_HOLD_MS); explodeAndSleep() }` |
| 702 | El bloque `ACTION_UP, ACTION_CANCEL` cancela ese `Job` antes de decidir el resto, y sigue conteniendo `v.performClick()` y `flingToEdge(...)` |
| 703 | Dentro del mismo `if (moved || dx*dx+dy*dy>120)` que ya arma el arrastre, se cancela el `Job` cuando `moved` pasa a `true`, y el bloque sigue llamando a `voiceDock.track(...)` |
| 704 | `explodeAndSleep()` usa `ValueAnimator`, cambia `bubble.scaleX/scaleY` y `bubble.alpha`, y su `onAnimationEnd` llama a `sleep()` |
| 705 | `sleep()` llama a `voiceDock.destroy()` (o `undock()`) y a `tts?.stop()` y `openAiTts.stop()` |
| 706 | `sleep()` llama a `wm.removeView(bubble)`, marca `asleep = true`, y su `LogBus.log(tag, …)` usa un tag de la lista cerrada de `PuertaDeTelemetria.TAGS` |
| 707 | `wakeIfAsleep()` corta temprano si `!asleep`, llama a `wm.addView(bubble, bubbleParams)` y no contiene `TextToSpeech(` ni `SoundPool.Builder` |
| 708 | `dockToApp()` y `setHiddenForApp()` llaman a `wakeIfAsleep()`, y `AssistActivity` llama a `bubble?.wakeIfAsleep()` |

### Sabotajes (cada uno pone roja su promesa)

| # | Sabotaje | Lo que debe decir el juez |
|---|---|---|
| 701 | `ACTION_DOWN` no arma ningún temporizador | roja: no encuentra `scope.launch { delay(SHUTDOWN_HOLD_MS)…` |
| 702 | `ACTION_UP`/`ACTION_CANCEL` no cancela el `Job` (dispara aunque se soltó antes de los 5 s) | roja: no encuentra `shutdownJob?.cancel()` en ese bloque |
| 703 | el movimiento significativo no cancela el apagado pendiente | roja: no encuentra `shutdownJob?.cancel()` dentro del `if (moved || …)` |
| 704 | la explosión no anima nada, o dispara `sleep()` antes de terminar la animación | roja: no hay `ValueAnimator` o `onAnimationEnd` no llama a `sleep()` |
| 705 | `sleep()` no corta el modo reunión si estaba activo | roja: no encuentra `voiceDock.destroy()`/`undock()` |
| 706 | `sleep()` no quita la burbuja de la pantalla, o loguea con un tag fuera de la lista cerrada | roja: no encuentra `wm.removeView(bubble)`, o el tag no está en `PuertaDeTelemetria.TAGS` |
| 707 | `wakeIfAsleep()` recrea el TTS o el SoundPool en vez de reusarlos | roja: encuentra `TextToSpeech(` o `SoundPool.Builder` en su cuerpo |
| 708 | `dockToApp()`/`setHiddenForApp()`/`AssistActivity` dejan de despertar a Ü | roja: no encuentra `wakeIfAsleep()` en alguno de los tres |

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
