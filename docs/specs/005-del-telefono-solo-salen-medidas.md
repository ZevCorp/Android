# Plan de implementación: del teléfono solo salen medidas — la telemetría remota no lleva lo que la persona dice ni lo que ve

Estado: **fase D1 implementada** (2026-09-15; promesas 501-507) · Nace de la decisión D del Capitán (2026-09-15): «del
teléfono a la telemetría remota solo salen medidas» · Rama: `yokh/integracion`

La app sube a Supabase, para el panel Android del Provider Studio, tres cosas: la tarjeta de cada instalación, cada pedido
con su desenlace y **todas** las líneas del `LogBus`. Eso incluye lo que la persona pide, lo que la app contesta, lo que se
oye durante una ejecución, lo que recuerda y las etiquetas de la pantalla. Esta spec pone **una sola puerta**, pura y en
`core`, por la que pasa todo lo que va a la telemetría remota, y la hace **negar por defecto**: sale lo que tiene forma de
medida permitida; todo lo demás sale como su largo o no sale. El log local (panel de desarrollador y logcat) no cambia.

---

## Diagnóstico: qué se midió

Leído en el código de `yokh/integracion` (154dd20), no supuesto.

| Qué | Medida | Fuente |
|---|---|---|
| Todo el bus va a remoto | `LogBus.log` encola cada línea en `Telemetry.enqueue`; solo la voz (`voz-*`) pasa antes por `TelemetriaDeVoz.paraRemoto` | `app/…/platform/LogBus.kt:30` |
| Las líneas suben enteras | `graph_exec_logs` con `tag` (60) y `message` (4000) tal cual | `app/…/platform/Telemetry.kt:161-178` |
| El pedido sube como texto | `promptStarted(prompt)` manda `p_prompt`; `promptFinished` repite `p_prompt` y manda `p_summary` (2000) | `Telemetry.kt:102-146`, `GraphApp.kt:498, 542, 545, 548` |
| La tarjeta sube el nombre | `ensureUser(name)` manda `p_display_name` y `p_device_model` | `Telemetry.kt:82-95`, `GraphApp.kt:455, 712`, `MainActivity.kt:158` |
| Lo que la persona pide | `[app] Pídeme: $prompt`; `▶ "$goal"` en el motor; `▶ acción: "…"` del asistente | `MainActivity.kt:1537`, `Engine.kt:42`, `AssistActivity.kt:218` |
| El resultado | `■ … · ${summary.take(120)}`; `🗣 ${turn.speech}`; `❓ $q` | `Engine.kt:96, 66, 80` |
| El audio durante la ejecución | `＋ audio durante ejecución: "…"`; `en vivo: "…"`; en reunión, la transcripción y la tarea | `GraphApp.kt:601`, `FloatingBubble.kt:1041`, `VoiceDock.kt:163, 191, 223` |
| El contexto pendiente y las propuestas | `🔗 contexto pendiente (…): "…"`, `🙋 propongo tras ejecutar: …`, `🤝 acción anticipada: …`, `🔮 anticipo: …` | `GraphApp.kt:477, 563, 569`, `Anticipation.kt:94` |
| La memoria | `🧠 recordado [app]: nota`, `🧠 aprendido de tu respuesta [app]: nota` | `GraphApp.kt:468, 480` |
| Etiquetas y textos de pantalla | `turno … · "${state.screen.take(36)}"`, `tap "$label": …`, `señal clic "$label" en $app`, `❌ "$label"`, pasos de workflow por su `target`, el portapapeles | `Engine.kt:63`, `GraphAccessibilityService.kt:367, 372, 421, 429`, `PassiveLearning.kt:82`, `WorkflowRunner.kt:63-93`, `AndroidSystemApi.kt:133` |
| Otro sink hacia la telemetría | ninguno: solo `Telemetry.kt` nombra `graph_exec_logs`, `graph_upsert_prompt` y `graph_upsert_app_user` | `grep` sobre `app/src` |

**Lo que esto significa:** arreglar línea por línea no alcanza, porque la próxima línea que alguien escriba vuelve a
filtrar. La regla va en un solo sitio, que niega por defecto, y el cableado se juzga para que nadie la esquive.

---

## Por qué esto va dirigido por especificación

Porque una fuga no falla: el panel se ve mejor cuanto más texto le llega. Ningún test de comportamiento se pone rojo si
una línea nueva vuelca lo que la persona dijo. La puerta se juzga por forma (qué sale de líneas reales con secretos
sembrados) y el cableado se juzga en las fuentes, como la 245 y la 246.

---

## La especificación

Esta spec numera sus promesas **desde 501** (ver `docs/como-trabajamos.md`). El enunciado de cada promesa es **literal** el
del test: 501-506 en `core/src/commonTest/kotlin/graph/core/contrato/Contrato005SoloSalenMedidas.kt` y 507 en
`core/src/jvmTest/kotlin/graph/core/contrato/Contrato005CableadoDeTelemetria.kt`.

| # | Promesa | Fase |
|---|---|---|
| 501 | De una línea del log a la telemetría remota solo sale lo que tiene forma de medida permitida: un nombre de la lista cerrada, una medida o un id opaco; todo texto libre sale como su largo ‹N› y un tag fuera de la lista sale como «otro». | D1 |
| 502 | Un número sale del teléfono solo como medida: pegado a su unidad, detrás de HTTP, intento, turno o una clave=, o delante de caracteres, bytes, turnos o acciones. Una clave, un teléfono o una cédula dentro de un texto no salen. | D1 |
| 503 | Un id sale solo si es opaco: un UUID, un sello # de 8 hex, una celda:x,y, una ruta de la API hecha de segmentos conocidos e ids, o un prefijo cerrado (call_, ses-, wf-…) con dígitos. Una palabra tras el prefijo, un paquete de app o un número largo no son ids. | D1 |
| 504 | Lo que se sube de un pedido y de un usuario lo arma la puerta: el pedido, el resumen, el nombre y el modelo del teléfono viajan como su largo; el estado y la vía, de una lista cerrada; los ids, solo si son UUID; y ninguna clave fuera de las de la RPC. | D1 |
| 505 | Cada fila de log que sale la arma la puerta, con solo device_id, prompt_id, tag y message. Detrás de TelemetriaDeVoz la medida de la voz sobrevive y ni una palabra de lo dicho sale; pasar dos veces por la puerta da lo mismo que una, y un mensaje enorme sale acotado. | D1 |
| 506 | Las líneas de precisión que ya son medidas pasan sin tocar precisión: la de peticion entera con su destino sellado, en celda o por su largo; las del motor con sus largos, turnos, acciones y tiempos; y del freno, el tope y el workflow, su medida, su sello y el tipo de la excepción. | D1 |
| 507 | En las fuentes de app todo lo que Telemetry sube lo arma PuertaDeTelemetria: cada http de Telemetry manda un cuerpo de la puerta, Telemetry no arma JSON ni abre otra conexión, en app solo se usan sus miembros init, userName, deviceId, ensureUser, promptStarted, promptFinished y enqueue, nadie más nombra las tablas de telemetría y LogBus sigue guardando la línea entera. | D1 |

### La regla, en una línea por clase

`graph.core.telemetria.PuertaDeTelemetria` parte cada mensaje en trozos y decide cada uno:

- **Queda tal cual:** un nombre de la lista cerrada (`EVENTOS`: acciones y herramientas del MCP, estados, proveedores), una
  frase fija de la lista cerrada (`FRASES`: `usuario dijo`, `Ü dijo`, `sesión cerrada`, `nodo sin id estructural`, `ya hay
  una tarea en curso`…), un signo de estructura (`·`, `:`, `=`, `«»`, `→`, los marcadores `▶ ■ ✋ 🧩 👁`…), el nombre de
  una excepción (`…Exception`, `…Error`).
- **Queda como medida:** un número pegado a su unidad (`1234ms`, `12s`, `3KB`, `40%`); un número delante de un sustantivo
  de medida (`42 caracteres`, `120 bytes`, `3 turnos`); un número detrás de un prefijo de medida (`HTTP 503`, `intento 2/3`,
  `llamadas=5`, `primera=120 ms`), hasta 4 cifras si solo lo sostiene el prefijo y hasta 9 con unidad o sustantivo; «`X de N
  caracteres`» con `X` de la lista de descriptores (`objetivo`, `pedido`, `campo`, `nombre`, `destino`…); y un contador `N/M`
  que abre la línea.
- **Queda como id opaco:** UUID; `#` + 8 hex (el sello HMAC de precisión); `celda:x,y`; coordenadas `(x,y)`; una ruta
  `/api/vN/…` cuyos segmentos son de la lista cerrada o ids; `call_`, `item_`, `msg_`, `resp_`, `sess_`, `ses-`, `wf-`,
  `evt_`, `req_`, `files/` seguidos de algo con al menos un dígito.
- **Todo lo demás cae:** cada tramo seguido de trozos que caen (con los espacios y la puntuación de adentro) sale como
  `‹N›`, su largo en caracteres. Un tag fuera de `TAGS` (o de `voz-…`) sale como `otro`.

Del pedido y del usuario: `p_prompt`, `p_summary`, `p_user_name`, `p_display_name` y `p_device_model` viajan como «`N
caracteres`»; `p_status` ∈ {running, ok, error, cancelled} y `p_source` ∈ {app, burbuja}, si no «`otro`»;
`p_app_version` solo con forma de versión; `p_id`, `p_device_id` y `prompt_id` solo si son UUID, si no `null`.

### Con qué se juzga cada una

| # | Cómo se juzga sin tocar nada |
|---|---|
| 501 | Líneas reales de cada fuga del diagnóstico con secretos sembrados (`Zorbax`, `Qwyk`, `7731`): ningún trozo de 3 caracteres de un secreto sale; salidas exactas donde el formato importa; los tags `run`, `voz-viva` quedan y uno inventado es `otro` |
| 502 | «mi clave es 7731», un teléfono y una cédula salen como `‹N›`; `HTTP 503`, `reintento 2/3`, `1600ms`, `42 caracteres`, `llamadas=5`, `primera=120 ms` salen tal cual; un número de 10 cifras detrás de un prefijo no sale |
| 503 | UUID, `#a1b2c3d4`, `celda:3,4`, `(120,300)`, `ses-1`, `call_…`, `/api/v1/workflows/wf-1` quedan; `wf-zorbax`, `#Zorbax12`, `com.whatsapp`, `3001234567` y una ruta con un segmento que es texto caen |
| 504 | El JSON de `cuerpoDePedido` y `cuerpoDeUsuario` se parsea: claves exactas de la RPC, largos en vez de texto, estado y vía cerrados, id no UUID → `null`, y ningún secreto en el cuerpo |
| 505 | El JSON de `filasDeLog`: cuatro claves por fila, mensaje igual a la puerta; líneas de la voz pasadas por `TelemetriaDeVoz.paraRemoto` y luego por la puerta conservan «`usuario dijo: N caracteres`»; `mensaje(mensaje(x)) == mensaje(x)`; 10 000 palabras salen en ≤ 4001 caracteres sin un `‹` abierto |
| 506 | Los formatos literales de `yokh/precision` (`CuentaDePeticion.cerrar`, `TopeDeIntentos.enLog`, `Engine`, `ArmadoDeEjecucion`, `Puerta`) salen iguales o conservan su medida, su sello y su excepción. Y, con precisión ya integrada, el código de verdad: `CuentaDePeticion` con `TopeDeIntentos.enLog` sobre un nodo sellado, una celda, un campo, un nombre y un nodo sin id deja líneas `peticion:` que pasan enteras; `Freno` y `ArmadoDeEjecucion` con un pedido que nombra a alguien conservan «(pedido de N caracteres)» y «ya hay una tarea en curso» sin un trozo de la persona |
| 507 | Fuentes de `app` sin comentarios: cada `http(` de `Telemetry.kt` lleva un cuerpo `PuertaDeTelemetria.…(`; `Telemetry.kt` no nombra `Json…`, ni claves de la RPC, y abre una sola conexión; los miembros públicos de `Telemetry` son los siete; cada aparición de `Telemetry` fuera de su archivo es el import simple o uno de esos miembros; nadie más nombra las tablas; `LogBus` sigue guardando `line` entera |

### Sabotajes (cada uno pone roja su promesa)

| # | Sabotaje | Lo que debe decir el juez |
|---|---|---|
| 501 | toda palabra fuera de las listas queda | roja: un secreto sale |
| 502 | todo número queda, con o sin contexto | roja: la clave sale |
| 503 | un id con prefijo ya no pide dígitos | roja: `wf-zorbax` sale |
| 504 | `p_prompt` viaja crudo | roja: el pedido sale |
| 505 | `filasDeLog` pone el mensaje crudo | roja: el mensaje no es el de la puerta |
| 506 | el sello pide 9 hex en vez de 8 | roja: el destino sellado cae |
| 506 | `TopeDeIntentos.enLog` dice «etiqueta de N caracteres» (precisión cambia su formato; los casos literales siguen verdes) | roja: la línea real de peticion cae |
| 507 | `promptStarted` vuelve a armar su JSON en `Telemetry.kt` | roja: un `http(` sin cuerpo de la puerta |

---

## Las fases

### Fase D1 — la puerta y su cableado (esta corrida)

- `core/src/commonMain/kotlin/graph/core/telemetria/PuertaDeTelemetria.kt` — la regla, las listas cerradas y los tres
  cuerpos (`filasDeLog`, `cuerpoDePedido`, `cuerpoDeUsuario`).
- `app/…/platform/Telemetry.kt` — deja de armar JSON: encola `LineaDeLog` locales y sube lo que arma la puerta.
- `LogBus.kt` no cambia: `TelemetriaDeVoz.paraRemoto` sigue primero (245, 246) y la puerta se aplica detrás, al armar la fila.

Pone verdes: **501-507**.

---

## Diferencias deliberadas con Windows

U-Windows-App no sube el log a Supabase: su telemetría es local. En Android el panel remoto existe y queda, pero solo con
medidas.

## Lo que NO entra, y por qué

- **`CloudSync`** (memoria, workflows y herramientas aprendidas a Supabase): no es telemetría, es la copia en la nube de lo
  que la app sabe, y sube texto a propósito. Si también debe acotarse es otra decisión.
- **Lo que viaja a Graph y a los modelos** (el objetivo, la pantalla): es el trabajo, no telemetría.
- **`RemoteConfig`, `Updater`, `SupabaseAuth`**: leen o autentican; no suben el log.
- **Cambiar qué se loguea en local**: el panel de desarrollador y logcat siguen completos.

## Límites conocidos

- Una palabra de la lista cerrada que aparece dentro de un texto libre sale suelta (p. ej. `send_sms`), y un número pegado
  a un sustantivo de medida también (`3 pasos`). Nunca sale una palabra fuera de las listas.
- Los tipos de evento de la voz (`«session.algo»`) salen reducidos: queda `session` y el resto es `‹N›`.
- La tarjeta del panel pierde el nombre y el modelo del teléfono (salen como su largo): ver la decisión pendiente abajo.

## Riesgo

Que una línea útil para depurar quede ilegible en el panel remoto. Se acepta: el detalle vive en el log local; si una
medida nueva hace falta afuera, se agrega a la lista cerrada con su promesa.
