# Plan de implementación: pregunta antes de ejecutar — el cliente frena lo que no le pediste y pide el contexto que le falta

Estado: **fases E1 y E2 implementadas** (2026-09-16; promesas 601-608) · Nace de un pedido del Capitán (2026-09-16): «me
gustaría que si no sabe qué hacer me pregunte, que pueda tener claro algo antes de ejecutar, que pida contexto para hacer
las tareas bien» · Rama: `yokh/pregunta-antes`

Hoy el cliente ejecuta **todo** lo que Graph decide. La única pregunta que existe es la que hace Graph (`ask_user`), y llega
cuando Graph quiere: si el modelo se lanza a mandar un mensaje, a llamar o a compartir algo que la persona no pidió, el
cliente lo hace y lo cuenta después. Esta spec pone una **compuerta de pregunta** entre el motor y la acción: lo sensible que
el pedido no autorizó no se ejecuta, se pregunta; un destino ambiguo se pregunta con las opciones que el cliente **vio**; y un
dato que falta se pide, de a uno. Mientras espera, la corrida queda viva y quieta.

No cambia el contrato del turno con Graph: ni `TurnRequest`, ni `TurnResponse`, ni el prompt. La respuesta de la persona
vuelve al cerebro por donde ya vuelve el desenlace de una acción —su `results`—, y el cerebro decide el turno siguiente.

---

## Diagnóstico: qué se midió

Leído en `yokh/pregunta-antes` (`ac46ee6`), no supuesto.

| Qué | Medida | Fuente |
|---|---|---|
| El motor ejecuta toda acción que llega | `turn.actions.forEachIndexed { … out += execute(action) }`: entre el turno y el teléfono solo está la puerta del freno | `core/…/application/Engine.kt:94-100` · `:149-167` |
| Nadie compara el pedido con la acción | `goal` se usa para `brain.begin(goal)` y para narrar; ninguna clase del núcleo lo vuelve a mirar | `Engine.kt:55-59` (`grep` sobre `core/src`) |
| La única pregunta es la de Graph | `turn.question` → `voice.speak(q)` → `user.ask(q)` → `b.inform(…)`; sin canal, «No hay usuario; usa tu mejor criterio.» | `Engine.kt:103-107` |
| El canal para preguntar YA existe | `UserChannel.ask(question): String`, con dos implementaciones reales: el diálogo «Tengo una duda» de la burbuja (texto o voz) y el «El asistente tiene una duda» de la app | `core/…/domain/Ports.kt:130-132` · `app/…/ui/FloatingBubble.kt:862` · `app/…/ui/MainActivity.kt:1562` |
| El «contexto pendiente» es otra cosa | `PendingVoice` recuerda lo que el asistente propuso o preguntó **por voz** para unir dos activaciones distintas; se consume al empezar la corrida siguiente, no dentro de una corrida | `app/…/GraphApp.kt:67` · `:193-200` · `:479-499` |
| La respuesta de la persona ya viaja sin tocar el protocolo | `results` van en el mismo orden que las acciones; `inform` es el carril de la pregunta de Graph | `core/…/graph/TurnProtocol.kt:47-49` · `GraphBrain.kt:24-26` |
| Un dato que falta se rellena solo | `set_alarm` sin `hour` pone las 8:00 (`it.int("hour", 8)`), `set_timer` sin `seconds` pone 60, y un `message` vacío manda un SMS vacío | `core/…/domain/Model.kt:41` · `:82-84` · `:85-87` · `:96-98` |
| Lo sensible pasa como cualquier gesto | `send_sms`, `call`, `send_email` y `share_text` entran por la puerta igual que un `scroll`: la puerta mira el freno y el tope, no lo que la acción hace | `core/…/precision/Puerta.kt:185-198` |
| El cliente sí puede ver la pantalla | `uiContext` trae «etiquetas visibles: A · B · C» (hasta 28, sin repetidas) y `installedApps()` trae las apps con launcher | `app/…/platform/GraphAccessibilityService.kt:213-214` · `app/…/GraphApp.kt:323-326` |
| El log de una pregunta ya es medida | `❓ pregunta de N caracteres`: el texto no sale | `Engine.kt:104` |

**Lo que esto significa:** no falta una interfaz nueva ni una pantalla nueva — falta **una decisión antes de actuar**. El
canal de preguntar existe y funciona; lo que no existe es alguien que mire la acción, la compare con lo que la persona pidió
y frene. Y como el log sale del teléfono por la telemetría (spec 005), esa decisión tiene que dejar medida, no texto.

---

## Por qué esto va dirigido por especificación

Porque «preguntar de más» y «preguntar de menos» fallan en silencio, y en direcciones opuestas: un cliente que pregunta
todo se vuelve inusable y nadie escribe un test que falle por eso; uno que no pregunta nada manda el mensaje equivocado una
vez cada mil corridas y el log dice «ok». La regla va en un solo sitio, con listas cerradas, y cada caso —el que pasa y el
que frena— es una fila con su juez y su sabotaje.

---

## La especificación

Esta spec numera sus promesas **desde 601** (ver `docs/como-trabajamos.md`). El enunciado de cada promesa es **literal** el
del mapa `PROMESAS` del test que la juzga; si cambia uno, cambia el otro en el mismo commit.

| Archivo | Promesas |
|---|---|
| `core/src/commonTest/kotlin/graph/core/contrato/Contrato006PreguntaAntes.kt` | 601, 603-608 |
| `core/src/jvmTest/kotlin/graph/core/contrato/Contrato006LoQueVe.kt` (lee las fuentes de la app: solo jvm lee disco) | 602 |

| # | Promesa | Fase |
|---|---|---|
| 601 | Una acción sensible que el pedido no autorizó no se ejecuta: el cliente pregunta primero y nada llega al teléfono; si el pedido ya la pidió, con ese destinatario y ese contenido, se ejecuta sin preguntar. | E1 |
| 602 | Un destino ambiguo se pregunta ofreciendo las opciones que el cliente vio —las etiquetas de la pantalla o las apps instaladas—, nunca una lista inventada; con un solo candidato, o con uno que es igual a lo pedido, no pregunta. | E1 |
| 603 | Un dato que falta se pide, uno por pregunta y el más importante primero; una hora que falta no cae en el default de las 8, y lo que la acción ya trae no se pregunta. | E1 |
| 604 | Mientras espera la respuesta no se ejecuta nada ni se pide otro turno: la corrida queda viva y quieta, ningún tope ni plazo la resuelve actuando por su cuenta, y el alto de siempre la para. | E1 |
| 605 | La respuesta continúa la MISMA corrida: vuelve al cerebro como resultado de la acción frenada, en el mismo hilo y sin abrir otro, y sin repetir la pregunta ya hecha. | E1 |
| 606 | No se pregunta dos veces lo mismo en la misma corrida: contestado que sí, la misma acción pasa sin preguntar; contestado que no, o sin contestar, no se ejecuta ni se vuelve a preguntar. | E1 |
| 607 | Sin canal para preguntar, una acción sensible no se ejecuta: el cerebro se entera por el resultado y la corrida sigue. | E1 |
| 608 | De una pregunta del cliente solo sale la medida: su clase y los largos. Ni el texto de la pregunta, ni la respuesta, ni el destinatario ni las opciones salen al log, y lo que sale pasa entero la puerta de la telemetría. | E2 |

**La que cierra el asunto es la 604.** Las otras siete deciden *cuándo* preguntar; la 604 es la que hace que preguntar sea
seguro: una corrida que espera no actúa, no paga turnos, no se «destraba» sola con un plazo — y se para con el mismo freno de
siempre, porque una pregunta sin salida sería otra forma de colgarse.

### La regla, en una línea por clase

`graph.core.pregunta.CompuertaDePregunta` mira cada acción ANTES de que el motor la ejecute y devuelve, o `null` (pasa), o el
resultado de una acción que no se hizo. Decide en este orden: **permiso**, **cuál**, **dato que falta**. El permiso va
primero porque es el que evita el daño; las otras dos son para hacerlo bien.

**1. Permiso.** `graph.core.pregunta.AccionSensible` clasifica la acción con una tabla cerrada. Lo sensible llega por dos vías:

| Vía | Acción | Clase | Destinatario | Contenido |
|---|---|---|---|---|
| MCP | `send_sms` | mensaje | `number` | `message` |
| MCP | `send_email` | mensaje | `to` | `subject` + `body` |
| MCP | `call` | llamada | `number` | — |
| MCP | `share_text` | compartir | — | `text` |
| Herramienta aprendida | cualquier `taps` cuya etiqueta lleve una palabra de la lista cerrada | la de la palabra | la etiqueta | — |

`dial` **no** es sensible: abre el marcador y no llama (`Model.kt:92`). Las palabras de las etiquetas, como palabra entera y
sin tildes: `enviar/envia/mandar/manda/responder` (mensaje), `llamar/llama/videollamada` (llamada),
`borrar/borra/eliminar/elimina/vaciar/desinstalar` (borrar), `pagar/paga/comprar/compra/transferir/suscribirse` (pago),
`compartir/comparte/publicar/publica` (compartir) y `bloquear/silenciar/desactivar` (ajuste que afecta a otros).

El pedido autoriza esa acción si cumple las tres:

- **nombra la acción** — una palabra de la lista de esa clase está en el pedido (`manda`, `mándale`, `escríbele`, `dile`,
  `llama`, `márcale`, `borra`, `paga`, `comparte`…);
- **nombra al destinatario** — alguna palabra de letras de 3 o más del destinatario está en el pedido. Un destinatario sin
  letras (un número que el cerebro sacó de los contactos) no se puede cruzar con el pedido: no frena por sí solo;
- **el contenido viene del pedido** — alguna palabra de 4 o más del contenido, fuera de una lista corta de palabras de
  relleno, está en el pedido. Un contenido sin palabras propias no frena.

«mándale a Ana que llego tarde» → `send_sms` con «Llego tarde»: pide mandar ✓, «ana» está ✓, «llego» y «tarde» están ✓ →
**ejecuta sin preguntar**. El mismo pedido con un `send_email` a `jefe@…` → «jefe» no está → **pregunta**.

**2. Cuál.** Con un nombre que el cliente puede resolver mirando —una app en `launch_app`/`open_app`, una etiqueta en los
`taps` de una aprendida— se cuentan los candidatos de lo que **vio**: las apps instaladas o las etiquetas de `uiContext`. Dos
o más candidatos y ninguno igual al nombre pedido → pregunta ofreciendo **esos** candidatos, hasta seis. Uno solo, uno igual,
o ninguno: no pregunta (nada que elegir, o nada que ofrecer).

**3. Dato que falta.** Tabla cerrada por herramienta, **ordenada por importancia**: se pide el primero que falte y nada más.

| Herramienta | Campos, en orden |
|---|---|
| `set_alarm` | `hour` |
| `set_timer` | `seconds` |
| `create_event` | `start`, `title` |
| `send_sms` | `number` |
| `send_email` | `to` |
| `call` | `number` |
| `launch_app` | `app` |
| `web_search`, `open_maps` | `query` |
| `directions` | `destination` |
| `open_url` | `url` |

**La respuesta.** No la interpreta el cliente: vuelve al cerebro dentro del `results` de esa acción
(`pregunté primero y no la hice — te pregunté «…» y contestaste «…»`) y el cerebro decide el turno siguiente. Lo único que el
cliente lee de la respuesta es si **niega**, como palabra entera y de una lista cerrada (`no`, `cancela`, `para`, `nada`,
`olvídalo`, `ninguno`…), o si está vacía: eso deja el asunto negado para toda la corrida. Cualquier otra respuesta lo deja
consultado, y la misma acción, si el cerebro insiste, pasa sin volver a preguntar.

Así no hay bucle: una acción frenada se pregunta **una vez**, y el turno siguiente la ejecuta o no según lo que la persona
contestó, no según lo que el cliente crea que quiso decir.

### Con qué se juzga cada una

Ninguna toca Android, red ni disco: el teléfono, los gestos, el sistema y el reproductor son los falsos de la 003 (`Mano`),
el cerebro es un guion que cuenta sus turnos y graba los `results` que le llegan, y el canal de preguntar es un falso que
anota cada pregunta y contesta lo que le digan. Todo lo que se juzga pasa por el `ArmadoDeEjecucion` de verdad: la compuerta
la arma él, y ningún archivo de la app la construye.

| # | Cómo se juzga sin tocar nada |
|---|---|
| 601 | Con el armado de verdad y el pedido «abre el chat de Zorbax»: `send_sms`, `send_email`, `call`, `share_text` y una aprendida que toca «Enviar» → ninguna llega al teléfono, cada una deja una pregunta de clase permiso y el resultado empieza con «pregunté primero y no la hice». Con el pedido «mándale a Ana que llego tarde» y `send_sms` a un número con «Llego tarde» → llega al teléfono y no se pregunta nada; el mismo pedido con `send_email` a «jefe@…», con otro contenido, o con `call` → pregunta. `dial` no pregunta nunca, y un `scroll`, un `tap` y `go_home` tampoco |
| 602 | El catálogo de apps («Bancolombia», «Banco de Bogotá», «Nequi») con `launch_app app=Banco` → una pregunta de clase cuál con exactamente esas dos, en ese orden, y ninguna inventada; con `app=Nequi` no pregunta; con `app=Bancolombia` (igual a un candidato) tampoco. Una aprendida con `taps=Juan` y `uiContext` con «Juan Pérez · Juan Carlos · Archivar» → pregunta con esos dos; con `taps=Archivar` no. Y las fuentes de la app: `GraphAccessibilityService` escribe «etiquetas visibles: » y une con « · », que es lo que el cliente parsea (`Vista.ETIQUETAS`) |
| 603 | `set_alarm` sin `hour` → pregunta por la hora y **no** llegan las 8:00 al teléfono; con `hour=7` pasa. `create_event` sin `start` ni `title` → una sola pregunta, la del cuándo; con `start` puesto y sin `title`, la del título. `set_timer`, `send_sms`, `web_search`, `directions`, `open_url` y `launch_app` sin su campo → una pregunta cada uno, con el campo que falta |
| 604 | Un canal que se queda esperando (`CompletableDeferred`): con el armado de verdad, un turno de dos acciones y `maxTurns=1`, mientras espera nada llega al teléfono, el cerebro dio un solo turno y la corrida no termina (300 ms reales después sigue viva y con la pregunta pendiente). Después, `armado.parar("píldora")` con la gracia del test → la corrida termina en `Paraste`, el teléfono sigue sin recibir nada y la respuesta que llega tarde no ejecuta nada |
| 605 | El cerebro guionado graba los `results` de cada turno: turno 1 pregunta, turno 2 recibe un `results` con la pregunta y la respuesta de la persona; `begin` se llamó una sola vez, `inform` ninguna (ese carril es el de la pregunta de Graph), y el canal recibió una sola pregunta aunque el turno 2 repita la acción |
| 606 | Contestado «sí, mándaselo»: la misma acción en el turno 2 llega al teléfono sin preguntar. Contestado «no»: no llega, no se vuelve a preguntar y el resultado dice «dijiste que no». Sin contestar (respuesta vacía): igual que «no». Y un asunto distinto (otro destinatario) sí se pregunta |
| 607 | El mismo motor con `user = null`: la acción sensible no llega al teléfono, el resultado empieza con «no hay a quién preguntarle», el turno siguiente se pide igual y la corrida termina normal |
| 608 | Una bitácora común con datos de verdad (un pedido, un contacto, un número, un mensaje, dos apps candidatas): ninguna línea, sin tildes ni mayúsculas, contiene un trozo de 4 caracteres de esos datos ni «ana», «juan» o el número como palabra entera. Y cada línea de la compuerta pasa **entera** por `PuertaDeTelemetria.mensaje`: el tag `pregunta`, la clase, «pregunta de N caracteres», «respuesta de N caracteres» y «N opciones» sobreviven |

### Sabotajes (cada uno pone roja su promesa)

| # | Sabotaje | Lo que debe decir el juez |
|---|---|---|
| 601 | la tabla de acciones sensibles se vacía | roja: `send_sms` llega al teléfono sin preguntar |
| 601 | el pedido autoriza con solo nombrar la acción (no se mira el destinatario) | roja: el correo al jefe no pregunta |
| 602 | la pregunta de «cuál» ofrece los candidatos que no vio (lista fija) | roja: las opciones no son las que vio |
| 602 | dos candidatos ya no son ambigüedad (basta uno) | roja: `launch_app app=Banco` no pregunta |
| 603 | los campos que faltan se preguntan todos de una | roja: `create_event` deja dos preguntas |
| 603 | la hora que falta vuelve a caer en el default | roja: la alarma de las 8 llega al teléfono |
| 604 | la espera de la respuesta se acota con un plazo que sigue sin respuesta | roja: la corrida termina sola y actúa |
| 605 | la respuesta va por `inform` en vez de por el `results` de la acción | roja: el turno 2 no la ve en sus `results` |
| 606 | el registro de lo ya preguntado se vacía en cada turno | roja: la misma acción pregunta dos veces |
| 606 | una negación cuenta como autorización | roja: «no» ejecuta la acción |
| 607 | sin canal, la acción sensible se ejecuta «con tu mejor criterio» | roja: el `send_sms` llega al teléfono |
| 608 | el log de la compuerta nombra el destinatario | roja: «ana» sale en una línea |
| 608 | la clase de la pregunta sale como texto libre (no está en la lista cerrada de la puerta) | roja: la línea sale como `‹N›` |

---

## Las fases

### Fase E1 — la compuerta y su cableado (esta corrida)

- `core/…/pregunta/AccionSensible.kt` — la tabla cerrada de lo sensible, las palabras de cada clase y si el pedido lo pidió.
- `core/…/pregunta/Pregunta.kt` — la pregunta (clase, asunto, texto, opciones), la **pregunta pendiente** (la pregunta y la
  acción que frenó) y cómo se lee una respuesta (niega / autoriza).
- `core/…/pregunta/CompuertaDePregunta.kt` — el orden de las tres decisiones, la vista de lo que el cliente ve, el registro
  de lo ya preguntado en esta corrida y los resultados que vuelven al cerebro.
- `core/…/application/Engine.kt` — la compuerta se arma con la corrida (`empieza(goal)`), ve cada pantalla (`vio(state)`) y
  se consulta antes de ejecutar; una acción frenada no señala vía ni toca nada, y deja su línea como cualquier otra.
- `core/…/precision/ArmadoDeEjecucion.kt` — `arma` construye la compuerta con el canal, la voz, el log y el catálogo de apps.
- `app/…/Ejecucion.kt` y `app/…/GraphApp.kt` — la corrida le pasa las apps instaladas al armado, que es quien arma todo.

Pone verdes: **601-607**.

### Fase E2 — que la pregunta salga como medida

- `core/…/telemetria/PuertaDeTelemetria.kt` — el tag `pregunta`; las clases `permiso`, `cual` y `dato` y los desenlaces
  `autoriza` y `niega` como nombres de la lista cerrada; `opciones` como sustantivo de medida; y las tres frases fijas que el
  cliente le devuelve al cerebro (`pregunté primero y no la hice`, `dijiste que no`, `no hay a quién preguntarle`).

Pone verdes: **608**.

---

## Diferencias deliberadas con Windows

`U-Windows-App` no tiene nada de esto: su cliente ejecuta lo que el backend decide y la única pregunta es la del modelo. La
compuerta es propia de Android, donde una acción sensible sale del teléfono hacia otra persona (un SMS, una llamada) y no se
puede deshacer. Si U la quiere, se porta al revés que siempre: de aquí para allá.

## Lo que le toca a Graph (propuesto, no hecho)

El cliente no cambia el prompt de Graph ni su contrato. Con la compuerta puesta, a Graph le conviene saber que existe: que
una acción puede volver con «pregunté primero y no la hice» y que la respuesta de la persona llega en `results`, no en
`inform`. La redacción propuesta está en el reporte de esta rama, bajo `PARA GRAPH:`; no se toca el repo `Graph` desde aquí.

## Lo que NO entra, y por qué

- **Computer-use por coordenadas.** Un `tap(x,y)` sobre el botón «Enviar» no se puede clasificar: el motor solo tiene el
  punto, y la puerta hoy se arma **sin** `nodoEn` (`ArmadoDeEjecucion.puerta`), así que el cliente no sabe qué nodo hay
  debajo. Cuando 3E cablee `nodoEn`, la compuerta puede mirar la etiqueta del nodo con las mismas listas: es una promesa
  nueva, no un cambio de esta.
- **Los pasos de un workflow** (`WorkflowRunner`): tocan por etiqueta a través de la puerta, no por el motor. Un workflow lo
  enseñó la persona; frenarlo paso a paso es otra decisión, con su spec.
- **La pregunta de Graph** (`ask_user`): sigue igual, por `inform`, y no entra al registro de lo ya preguntado. Repetirla o
  no es cosa de Graph.
- **Interpretar la respuesta.** El cliente solo lee si niega; lo demás lo decide el cerebro con el contexto entero. Un
  clasificador de «sí/no» en el cliente es justo lo que el cerebro hace mejor.
- **Preguntar por lo aprendido** (memoria durable, preferencias): la compuerta mira el pedido y la pantalla, no la memoria.

## Límites conocidos

- Un destinatario **sin letras** —el número que el cerebro resolvió de los contactos— no se puede cruzar con el pedido: si el
  pedido pide mandar y el contenido viene de él, pasa. Cambiar esto exigiría que el cliente resolviera contactos, que es
  justo lo que no hace.
- Las listas de palabras son cerradas y en español: un pedido en otro idioma («text Ana that I'm late») no autoriza, así que
  el cliente pregunta. Preguntar de más es el lado seguro del error.
- El registro de lo ya preguntado vive en la corrida del motor: un **reencaminado** (la persona habla otra vez) arma una
  sesión nueva y puede volver a preguntar lo mismo. Es deliberado: el pedido cambió.
- Un **paso consciente** de un workflow corre con un objetivo sintético que suele nombrar la acción del paso, así que se
  autoriza a sí mismo. Hoy no se nota (`subconsciousExecution = false`), y cuando se encienda hay que decidirlo aparte.
- La ambigüedad se mide sobre lo que `uiContext` muestra: hasta 28 etiquetas y **sin repetidas**, así que dos elementos con
  la misma etiqueta exacta no se ven como dos. Se ven los parecidos, que es el caso que rompe.

## Riesgo

Que pregunte de más y canse. Se acota por diseño: solo lo de la tabla cerrada pregunta, una vez por asunto y por corrida, y
lo que el pedido ya dijo pasa de largo. Si en el teléfono resulta pesada, se recorta la tabla —con su promesa— antes que la
regla.
