# Plan de implementación: del teléfono solo salen medidas — la telemetría remota no lleva lo que la persona dice ni lo que ve

Estado: **fases D1 y D2 implementadas** (2026-09-15; promesas 501-511) · Nace de la decisión D del Capitán (2026-09-15): «del
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
del test: 501-506 y 508-511 en `core/src/commonTest/kotlin/graph/core/contrato/Contrato005SoloSalenMedidas.kt` y 507 en
`core/src/jvmTest/kotlin/graph/core/contrato/Contrato005CableadoDeTelemetria.kt`.

| # | Promesa | Fase |
|---|---|---|
| 501 | De una línea del log a la telemetría remota solo sale lo que tiene forma de medida permitida: un nombre de la lista cerrada, una medida o un id opaco; todo texto libre sale como su largo ‹N› y un tag fuera de la lista sale como «otro». | D1 |
| 502 | Un número sale del teléfono solo como medida: pegado a su unidad, detrás de HTTP, intento, turno o una clave=, o delante de caracteres, bytes, turnos o acciones. Una clave, un teléfono o una cédula dentro de un texto no salen. | D1 |
| 503 | Un id sale solo si es opaco: un UUID, un sello # de 8 hex con alguna letra, una celda:x,y, una ruta de la API hecha de segmentos conocidos e ids, o un prefijo cerrado (call_, item_, wf_…) con la forma de quien lo produce. Una palabra tras el prefijo, un paquete de app o un número largo no son ids. | D1 |
| 504 | Lo que se sube de un pedido y de un usuario lo arma la puerta: el pedido, el resumen, el nombre y el modelo del teléfono viajan como su largo; el estado y la vía, de una lista cerrada; los ids, solo si son UUID; y ninguna clave fuera de las de la RPC. | D1 |
| 505 | Cada fila de log que sale la arma la puerta, con solo device_id, prompt_id, tag y message. Detrás de TelemetriaDeVoz la medida de la voz sobrevive y ni una palabra de lo dicho sale; pasar dos veces por la puerta da lo mismo que una, y un mensaje enorme sale acotado. | D1 |
| 506 | Las líneas de precisión que ya son medidas pasan sin tocar precisión: la de peticion entera con su destino sellado, en celda o por su largo; las del motor con sus largos, turnos, acciones y tiempos; y del freno, el tope y el workflow, su medida, su sello y el tipo de la excepción. | D1 |
| 507 | En las fuentes de app todo lo que Telemetry sube lo arma PuertaDeTelemetria: cada http de Telemetry manda un cuerpo de la puerta, Telemetry no arma JSON ni abre otra conexión, en app solo se usan sus miembros init, userName, deviceId, ensureUser, promptStarted, promptFinished y enqueue, nadie más nombra las tablas de telemetría y LogBus sigue guardando la línea entera. | D1 |
| 508 | Un sello sale solo si tiene al menos una letra: # con ocho cifras decimales es un pedido, una factura o una cédula y sale como su largo; y el sello de verdad de precisión lleva siempre una letra, así que ninguna línea real de peticion pierde su destino sellado. | D2 |
| 509 | Un id con prefijo sale solo con la forma y el largo de quien lo produce: wf_ con 13 cifras de Graph, call_ de 24 e item_ de 21 en base62, resp_ y msg_ de 50 hex, files/ de 12; una palabra con una cifra, un número u otro largo no es id, y una ruta de la API con un segmento de texto o de más de 6 cifras sale como su largo. | D2 |
| 510 | Una unidad sostiene un número solo como la escriben los logs: ms, s, KB, MB y % pegados, ms, min, KB y MB con espacio, y s con espacio hasta 3 cifras; B y h no son unidad, números seguidos cuyas cifras juntas llegan a 7 se tapan, y paso y turno sostienen a lo más 3 cifras. | D2 |
| 511 | Una coordenada, una celda y una marca ‹N› sostienen a lo más 5 cifras por componente: con más, también una marca escrita a mano, salen como su largo; un tramo de más de 99999 caracteres sale como ‹99999›, así que pasar dos veces por la puerta sigue dando lo mismo. | D2 |

### La regla, en una línea por clase

`graph.core.telemetria.PuertaDeTelemetria` parte cada mensaje en trozos y decide cada uno:

- **Queda tal cual:** un nombre de la lista cerrada (`EVENTOS`: acciones y herramientas del MCP, estados, proveedores), una
  frase fija de la lista cerrada (`FRASES`: `usuario dijo`, `Ü dijo`, `sesión cerrada`, `nodo sin id estructural`, `ya hay
  una tarea en curso`…), un signo de estructura (`·`, `:`, `=`, `«»`, `→`, los marcadores `▶ ■ ✋ 🧩 👁`…), el nombre de
  una excepción (`…Exception`, `…Error`).
- **Queda como medida:** un número pegado a su unidad, solo `ms`, `s`, `KB`, `MB` o `%` (`1234ms`, `12s`, `3KB`, `40%`);
  con espacio, `ms`, `min`, `KB` y `MB`, y `s` hasta 3 cifras (`primera=120 ms`, `tope de 90 s`, `5 min`); un número delante
  de un sustantivo de medida (`42 caracteres`, `120 bytes`, `3 turnos`); un número detrás de un prefijo de medida (`HTTP 503`,
  `intento 2/3`, `llamadas=5`), hasta 4 cifras si solo lo sostiene el prefijo, 3 detrás de `paso` y `turno`, y hasta 6 con
  unidad o sustantivo; «`X de N caracteres`» con `X` de la lista de descriptores (`objetivo`, `pedido`, `campo`, `nombre`,
  `destino`…); y un contador `N/M` que abre la línea. `B` y `h` no son unidad (`301B`, `45 B`, `1234h` caen). Números
  seguidos —con sus unidades, sus sustantivos y la puntuación que los parte— cuyas cifras juntas llegan a 7 caen todos:
  `300 s 123 s 4567 s`, `12.345.678 caracteres`.
- **Queda como id opaco:** UUID; `#` + 8 hex **con al menos una letra** (`#12345678` es un número; el sello HMAC de
  precisión lleva siempre una `a` delante); `celda:x,y` y coordenadas `(x,y)` con hasta 5 cifras por componente; una ruta
  `/api/vN/…` cuyos segmentos son de la lista cerrada, UUID, números de hasta 6 cifras o ids con prefijo; y un id con
  prefijo **con la forma y el largo de quien lo produce**:

  | Prefijo | Forma | Quién lo produce |
  |---|---|---|
  | `wf_` | 13 cifras que empiezan en 1 | Graph: `wf_${Date.now()}` (`WorkflowLearner.js:39`), id de sesión y de workflow |
  | `call_` | 24 base62 con mayúsculas y minúsculas | GPT-Live: la llamada a una herramienta (spec 002) |
  | `item_` | 21 base62 con mayúsculas y minúsculas | GPT-Live: la delegación |
  | `resp_`, `msg_` | 50 hex | OpenAI Responses, medido en el teléfono (2026-09-14) |
  | `files/` | 12 minúsculas y cifras, con al menos una de cada | Gemini Files API: el video subido (`GeminiVideo.kt:99`) |

  Cualquier otro prefijo cerrado (`ses-`, `sess_`, `evt_`, `req_`, `wf-`…) y cualquier otro largo caen enteros.
- **Todo lo demás cae:** cada tramo seguido de trozos que caen (con los espacios y la puntuación de adentro) sale como
  `‹N›`, su largo en caracteres; un tramo de más de 99999 caracteres sale como `‹99999›`. Una marca `‹N›` pasa solo con hasta
  5 cifras: escrita a mano con más, cae. Un tag fuera de `TAGS` (o de `voz-…`) sale como `otro`.

Del pedido y del usuario: `p_prompt`, `p_summary`, `p_user_name`, `p_display_name` y `p_device_model` viajan como «`N
caracteres`»; `p_status` ∈ {running, ok, error, cancelled} y `p_source` ∈ {app, burbuja}, si no «`otro`»;
`p_app_version` solo con forma de versión; `p_id`, `p_device_id` y `prompt_id` solo si son UUID, si no `null`.

### Con qué se juzga cada una

| # | Cómo se juzga sin tocar nada |
|---|---|
| 501 | Líneas reales de cada fuga del diagnóstico con secretos sembrados (`Zorbax`, `Qwyk`, `7731`): ningún trozo de 3 caracteres de un secreto sale; salidas exactas donde el formato importa; los tags `run`, `voz-viva` quedan y uno inventado es `otro` |
| 502 | «mi clave es 7731», un teléfono y una cédula salen como `‹N›`; `HTTP 503`, `reintento 2/3`, `1600ms`, `42 caracteres`, `llamadas=5`, `primera=120 ms` salen tal cual; un número de 10 cifras detrás de un prefijo no sale |
| 503 | UUID, `#a1b2c3d4`, `celda:3,4`, `(120,300)`, `wf_1789054200000`, `call_…`, `/api/v1/workflows/wf_1789054200000` quedan; `wf-zorbax`, `#Zorbax12`, `com.whatsapp`, `3001234567` y una ruta con un segmento que es texto caen |
| 504 | El JSON de `cuerpoDePedido` y `cuerpoDeUsuario` se parsea: claves exactas de la RPC, largos en vez de texto, estado y vía cerrados, id no UUID → `null`, y ningún secreto en el cuerpo |
| 505 | El JSON de `filasDeLog`: cuatro claves por fila, mensaje igual a la puerta; líneas de la voz pasadas por `TelemetriaDeVoz.paraRemoto` y luego por la puerta conservan «`usuario dijo: N caracteres`»; `mensaje(mensaje(x)) == mensaje(x)`; 10 000 palabras salen en ≤ 4001 caracteres sin un `‹` abierto |
| 506 | Los formatos literales de `yokh/precision` (`CuentaDePeticion.cerrar`, `TopeDeIntentos.enLog`, `Engine`, `ArmadoDeEjecucion`, `Puerta`) salen iguales o conservan su medida, su sello y su excepción. Y, con precisión ya integrada, el código de verdad: `CuentaDePeticion` con `TopeDeIntentos.enLog` sobre un nodo sellado, una celda, un campo, un nombre y un nodo sin id deja líneas `peticion:` que pasan enteras; `Freno` y `ArmadoDeEjecucion` con un pedido que nombra a alguien conservan «(pedido de N caracteres)» y «ya hay una tarea en curso» sin un trozo de la persona |
| 507 | Fuentes de `app` sin comentarios: cada `http(` de `Telemetry.kt` lleva un cuerpo `PuertaDeTelemetria.…(`; `Telemetry.kt` no nombra `Json…`, ni claves de la RPC, y abre una sola conexión; los miembros públicos de `Telemetry` son los siete; cada aparición de `Telemetry` fuera de su archivo es el import simple o uno de esos miembros; nadie más nombra las tablas; `LogBus` sigue guardando `line` entera |
| 508 | `#12345678`, `#40123456`, `#10203040` y `#00000000`, sueltos y dentro de las líneas del revisor, salen como `‹N›`; `#a1b2c3d4`, `#1234567f` quedan. Y 2000 nodos distintos por `TopeDeIntentos.enLog` y `CuentaDePeticion` reales: cada sello lleva una letra y cada línea `peticion` pasa entera (sin la letra, ≈46 salen todo cifras) |
| 509 | Los ids reales de Graph, GPT-Live, OpenAI y Gemini quedan, sueltos, en su ruta y en la línea de la lección; `ses-3001234567`, `wf-anapaula1`, `call_mama2`, `files/3001234567`, un `wf_` de otro largo, un `call_` de 24 minúsculas, `files/mariapaulina` y las rutas con `3001234567` o `ses-juanperez7` salen como `‹N›` |
| 510 | Las fugas del revisor (`301B`, `45 B`, `300 s 123 s 4567 s`, `300 ms 123 4567`, `482913 s`, `1234h`, `12.345.678 caracteres`, `paso 4521`, `turno: 9876`) salen como `‹N›`; las medidas de Engine, GraphBrain, `Reintentos.corto`, el freno, la voz y GeminiBrain salen iguales o con su medida |
| 511 | `(3001234,567)`, `tap(123456,1)`, `celda:1,123456`, `‹3001234567›` salen como `‹N›`, también en la segunda pasada; `(99999,-99999)`, `celda:99999,99999`, `‹12345›` quedan; 120000 caracteres de una palabra salen `‹99999›` dos veces |
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
| 508 | el sello de la puerta vuelve a aceptar 8 cifras | roja: `#12345678` sale |
| 508 | `sello` en precisión devuelve los 8 hex del HMAC sin su letra | roja: un sello real sale todo cifras |
| 509 | un id con prefijo vuelve a pasar con cualquier dígito | roja: `wf-anapaula1` sale |
| 509 | un segmento numérico de ruta vuelve a admitir 20 cifras | roja: `/api/v1/workflows/3001234567` sale |
| 510 | `B` vuelve a ser sustantivo de medida | roja: `45 B` sale |
| 510 | la serie de números seguidos no se tapa | roja: `300 ms` de `300 ms 123 4567` sale |
| 510 | `paso` y `turno` sostienen 4 cifras | roja: `paso 4521` sale |
| 511 | la marca `‹N›` vuelve a admitir cualquier cantidad de cifras | roja: `‹3001234567›` sale |
| 511 | la celda pierde su tope de 5 cifras | roja: `celda:1,12345` sale |

---

## Las fases

### Fase D1 — la puerta y su cableado (esta corrida)

- `core/src/commonMain/kotlin/graph/core/telemetria/PuertaDeTelemetria.kt` — la regla, las listas cerradas y los tres
  cuerpos (`filasDeLog`, `cuerpoDePedido`, `cuerpoDeUsuario`).
- `app/…/platform/Telemetry.kt` — deja de armar JSON: encola `LineaDeLog` locales y sube lo que arma la puerta.
- `LogBus.kt` no cambia: `TelemetriaDeVoz.paraRemoto` sigue primero (245, 246) y la puerta se aplica detrás, al armar la fila.

Pone verdes: **501-507**.

### Fase D2 — los huecos que encontró el revisor de la ola 1

El revisor conjunto reprodujo, con la puerta de verdad, números y textos que salían como si fueran medidas o ids. Cada
hueco se cierra en la puerta con su promesa y su sabotaje; el único productor que cambia es el sello de precisión.

- `PuertaDeTelemetria.kt` — el sello pide una letra (508); los ids con prefijo, la forma real de cada productor, y las rutas,
  segmentos numéricos de hasta 6 cifras (509); unidades como las escriben los logs, la serie de números seguidos y `paso` y
  `turno` hasta 3 cifras (510); coordenadas, celdas y marcas hasta 5 cifras por componente, y la marca con tope (511).
- `precision/Sello.kt` — el sello es una `a` y 7 hex del HMAC: sigue siendo `#` y 8 hex, y nunca todo cifras. Cada plataforma
  pone solo su HMAC (`hmacDelProceso`); el formato lo decide common.
- La 503 cambia su enunciado y sus ejemplos en el mismo commit que su test: `ses-1`, `wf-9f8e7d`, `item_1` y `files/abc123`
  no los produce nadie, y con la 509 dejan de ser ids.

Pone verdes: **508-511**.

---

## Diferencias deliberadas con Windows

U-Windows-App no sube el log a Supabase: su telemetría es local. En Android el panel remoto existe y queda, pero solo con
medidas.

## Lo que NO entra, y por qué

- **`CloudSync`** (`GraphApp.kt:134, 141, 255`): sube en claro la memoria, los workflows y las herramientas aprendidas a
  `graph_memory`, `graph_workflows` y `graph_learned_tools`. No pasa por esta puerta: es la copia en la nube de lo que la app
  sabe. Se decide en la ola 2, con spec propia.
- **Lo que viaja a Graph y a los modelos** (el objetivo, la pantalla): es el trabajo, no telemetría.
- **`RemoteConfig`, `Updater`, `SupabaseAuth`**: leen o autentican; no suben el log.
- **Cambiar qué se loguea en local**: el panel de desarrollador y logcat siguen completos.

## Límites conocidos

- Una palabra de la lista cerrada que aparece dentro de un texto libre sale suelta (p. ej. `send_sms`), y un número pegado
  a un sustantivo de medida también (`3 pasos`). Nunca sale una palabra fuera de las listas.
- Los tipos de evento de la voz (`«session.algo»`) salen reducidos: queda `session` y el resto es `‹N›`.
- La tarjeta del panel pierde el nombre y el modelo del teléfono. **Decidido:** la tarjeta de `graph_app_users` sube el
  nombre y el modelo del teléfono solo como largo («N caracteres»), y la instalación se identifica por su id de instalación
  y su versión.
- Números separados por un signo o un prefijo no son una serie: `HTTP 300 · HTTP 123 · HTTP 4567` sale entero, porque las
  líneas de precisión (`llamadas=5 distintas=3 …`) tienen esa misma forma. Nadie escribe así un teléfono en un texto libre.
- Una medida de 7 o más cifras cae (un cuerpo de más de 999999 bytes, una espera de más de 999999 ms). Los bytes con `B` de
  `MicrofonoPcm` («trozos de 4800 B») y `GeminiBrain` («recibí 1234B») salen como `‹N›`: `B` no se separa de un portal.
- Un nombre de Gemini sin ninguna cifra (≈2 % de los que genera) sale como `‹N›`; el formato de 12 caracteres se leyó de
  la documentación y de `GeminiVideo.kt`, no se midió en el teléfono.

## Riesgo

Que una línea útil para depurar quede ilegible en el panel remoto. Se acepta: el detalle vive en el log local; si una
medida nueva hace falta afuera, se agrega a la lista cerrada con su promesa.
