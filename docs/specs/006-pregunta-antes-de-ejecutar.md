# Plan de implementación: pregunta antes de ejecutar — el cliente frena lo que no le pediste y pide el contexto que le falta

Estado: **fases E1, E2 y E3 implementadas** (2026-09-16; promesas 601-614 verdes, contrato de 134 promesas; cada una se vio
ROJA con un sabotaje real — 19 sabotajes, uno por fila de la tabla de abajo). La **E3 nace de un control que dio NO
APROBADO**: la compuerta de la E1 se saltaba con frases ordinarias, porque «no hay nada que comparar» valía como permiso
(ver «Lo que el control encontró») · Nace de un pedido del Capitán (2026-09-16): «me
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
| `core/src/commonTest/kotlin/graph/core/contrato/Contrato006PreguntaAntes.kt` | 601, 603-610, 612, 614 |
| `core/src/jvmTest/kotlin/graph/core/contrato/Contrato006LoQueVe.kt` (lee las fuentes de la app: solo jvm lee disco) | 602, 611, 613 |

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
| 609 | Un pedido autoriza una acción sensible solo si el destinatario y el contenido de la acción se corresponden con los del pedido: un destinatario que no aparece en el pedido, o que no hay forma de comparar, no autoriza; un teléfono se compara sin separadores ni prefijo de país. | E3 |
| 610 | La autorización vale para esa acción con ese destinatario Y ese contenido: un segundo mensaje al mismo destinatario con otro texto vuelve a preguntar, y un «sí» a un compartir no autoriza el compartir siguiente. | E3 |
| 611 | Solo autoriza el texto que escribió o dictó la persona: lo que redactó el modelo —una acción anticipada autónoma, o el contexto de una propuesta que la persona rechazó— no autoriza nada, y lo sensible se pregunta igual. | E3 |
| 612 | «Ya contestado» solo salta el permiso: un dato que sigue faltando y un nombre que sigue siendo ambiguo no se dan por resueltos —la acción no se ejecuta ni cae en el default de las 8— y tampoco se preguntan en bucle. | E3 |
| 613 | Una duda sin respuesta no traba la app: la persona puede cerrarla y cerrarla cuenta como «no», y si el canal desaparece la corrida termina sin ejecutar lo sensible, sin plazo que decida por su cuenta. | E3 |
| 614 | Una respuesta ambigua no se asume: se vuelve a preguntar una vez y, si sigue ambigua, no se ejecuta. Una negación cuenta cuando abre la respuesta, no en cualquier posición, y una respuesta vacía sigue siendo un no. | E3 |

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

El pedido autoriza esa acción si cumple las tres. **La ausencia de evidencia nunca es permiso**: si algo no se puede
cruzar con el pedido, no está autorizado y se pregunta (promesa 609).

- **nombra la acción** — una palabra de la lista de esa clase está en el pedido (`manda`, `mándale`, `escríbele`, `dile`,
  `llama`, `márcale`, `borra`, `paga`, `comparte`…);
- **el destinatario se corresponde** — el destinatario de la ACCIÓN tiene que aparecer en el pedido de forma reconocible.
  Con letras («Ana», «jefe@…»), alguna palabra suya de 3 o más está en el pedido. Sin letras —el número que el cerebro
  resolvió de los contactos— se compara **como número** con los números del pedido: se quitan separadores y prefijo de país
  y se cruzan las últimas 7 cifras. Si el pedido no trae ningún número, **no hay con qué comparar y no autoriza**. Un
  destinatario vacío no es un destinatario que no cruza: es un dato que falta, y lo pide la pregunta de dato;
- **el contenido se corresponde** — alguna palabra de 4 o más del contenido, fuera de una lista corta de palabras de
  relleno, está en el pedido. Un contenido sin ninguna palabra propia que cruzar (`«Ya voy»`, `«Ok gracias»`) **tampoco
  autoriza**: no dice que la persona lo haya pedido, solo que es corto.

«mándale a Ana que llego tarde» → `send_email` a `ana@…` con «Llego tarde»: pide mandar ✓, «ana» está ✓, «llego» y «tarde»
están ✓ → **ejecuta sin preguntar**. El mismo pedido con un `send_email` a `jefe@…` → «jefe» no está → **pregunta**. Y el
mismo pedido con un `send_sms` al número que el cerebro resolvió → el pedido no trae número, no hay con qué cruzarlo →
**pregunta**. Para que ese SMS pase, el pedido tiene que traer el número: «mándale al 310 445 9821 que llego tarde».

**Quién escribió el pedido importa (promesa 611).** Solo autoriza el texto que la persona escribió o dictó. Los objetivos
que redacta el modelo —la acción anticipada autónoma de `GraphApp.anticipate` y el `CONTEXTO INMEDIATO` de una propuesta—
viajan al motor como parte del `goal`, pero **no** como pedido: el motor recibe aparte lo que dijo la persona
(`ExecutionEngine.run(goal, announce, dijoLaPersona)`) y es eso, y solo eso, lo que la compuerta compara. Sin texto de la
persona no se autoriza nada. Si no, bastaba con que el modelo escribiera «mándale a Ana un mensaje» en su propio objetivo
para autorizarse a sí mismo — y peor: una propuesta que la persona **rechazó** seguía autorizando, porque el contexto
pendiente todavía llevaba la frase.

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
cliente lee de la respuesta es en cuál de tres cae (promesa 614), como palabra entera y con listas cerradas:

| Lectura | Cuándo | Qué hace |
|---|---|---|
| **niega** | está vacía, o abre con una negación (`no`, `cancela`, `nada`, `olvídalo`, `ninguno`…), o trae una negación y ninguna afirmación | deja el asunto negado para toda la corrida |
| **ambigua** | trae una negación Y una afirmación, y la negación no abre («claro, no hay problema, mándalo») | **vuelve a preguntar UNA vez**; si la segunda sigue ambigua, no se ejecuta |
| **autoriza** | cualquier otra | el asunto queda autorizado para esa acción, ese destinatario y ese contenido |

Una negación **en cualquier posición** no vale: «claro, no hay problema, mándalo» decía que sí y se leía como un no, en
silencio. Se mira dónde cae la negación y si hay una afirmación que la contradiga; ante la duda se pregunta otra vez, que es
lo barato, en vez de asumir.

**Lo autorizado es esa acción con ese destinatario y ese contenido (promesa 610).** La llave de lo ya contestado lleva las
tres cosas. Cambiar el texto vuelve a preguntar: un «sí» a «avísale a Ana que llego tarde» no autoriza mandarle después
«transfiéreme la plata». Y `share_text`, que no tiene destinatario, se distingue por su contenido: sin eso, un «sí»
autorizaba cualquier compartir del resto de la corrida.

**«Ya contestado» solo salta el permiso (promesa 612).** Para la pregunta de **dato** y la de **cuál** lo que vale es que el
dato ESTÉ en la acción: si el cerebro repite `set_alarm` sin hora, la segunda vez se vuelve a pedir y, si sigue faltando, la
acción **no se ejecuta** — nunca cae en el default de las 8, que es justo lo que promete la 603. Igual con un nombre que
sigue siendo ambiguo.

Así no hay bucle: un permiso se pregunta **una vez** por asunto, un dato que falta se pide **una vez** más y después falla,
y el turno siguiente ejecuta o no según lo que la persona contestó, no según lo que el cliente crea que quiso decir.

### Con qué se juzga cada una

Ninguna toca Android, red ni disco: el teléfono, los gestos, el sistema y el reproductor son los falsos de la 003 (`Mano`),
el cerebro es un guion que cuenta sus turnos y graba los `results` que le llegan, y el canal de preguntar es un falso que
anota cada pregunta y contesta lo que le digan. Todo lo que se juzga pasa por el `ArmadoDeEjecucion` de verdad: la compuerta
la arma él, y ningún archivo de la app la construye.

| # | Cómo se juzga sin tocar nada |
|---|---|
| 601 | Con el armado de verdad y el pedido «mira el chat de Zorbax»: `send_sms`, `send_email`, `call`, `share_text` y una aprendida que toca «Enviar» → ninguna llega al teléfono, cada una deja una pregunta de clase permiso y el resultado empieza con «pregunté primero y no la hice». Con el pedido «mándale a Ana que llego tarde» y `send_sms` a un número con «Llego tarde» → llega al teléfono y no se pregunta nada; el mismo pedido con `send_email` a «jefe@…», con otro contenido, o con `call` → pregunta. `dial` no pregunta nunca, y un `scroll`, un `tap` y `go_home` tampoco |
| 602 | El catálogo de apps («Bancolombia», «Banco de Bogotá», «Nequi») con `launch_app app=Banco` → una pregunta de clase cuál con exactamente esas dos, en ese orden, y ninguna inventada; con `app=Nequi` no pregunta; con `app=Bancolombia` (igual a un candidato) tampoco. Una aprendida con `taps=Juan` y `uiContext` con «Juan Pérez · Juan Carlos · Archivar» → pregunta con esos dos; con `taps=Archivar` no. Y las fuentes de la app: `GraphAccessibilityService` escribe «etiquetas visibles: » y une con « · », que es lo que el cliente parsea (`Vista.ETIQUETAS`) |
| 603 | `set_alarm` sin `hour` → pregunta por la hora y **no** llegan las 8:00 al teléfono; con `hour=7` pasa. `create_event` sin `start` ni `title` → una sola pregunta, la del cuándo; con `start` puesto y sin `title`, la del título. `set_timer`, `send_sms`, `web_search`, `directions`, `open_url` y `launch_app` sin su campo → una pregunta cada uno, con el campo que falta |
| 604 | Un canal que se queda esperando (`CompletableDeferred`): con el armado de verdad, un turno de dos acciones y `maxTurns=1`, mientras espera nada llega al teléfono, el cerebro dio un solo turno y la corrida no termina (300 ms reales después sigue viva y con la pregunta pendiente). Después, `armado.parar("píldora")` con la gracia del test → la corrida termina en `Paraste`, el teléfono sigue sin recibir nada y la respuesta que llega tarde no ejecuta nada |
| 605 | El cerebro guionado graba los `results` de cada turno: turno 1 pregunta, turno 2 recibe un `results` con la pregunta y la respuesta de la persona; `begin` se llamó una sola vez, `inform` ninguna (ese carril es el de la pregunta de Graph), y el canal recibió una sola pregunta aunque el turno 2 repita la acción |
| 606 | Contestado «sí, mándaselo»: la misma acción en el turno 2 llega al teléfono sin preguntar. Contestado «no»: no llega, no se vuelve a preguntar y el resultado dice «dijiste que no». Sin contestar (respuesta vacía): igual que «no». Y un asunto distinto (otro destinatario) sí se pregunta |
| 607 | El mismo motor con `user = null`: la acción sensible no llega al teléfono, el resultado empieza con «no hay a quién preguntarle», el turno siguiente se pide igual y la corrida termina normal |
| 608 | Una bitácora común con datos de verdad (un pedido, un contacto, un número, un mensaje, dos apps candidatas): ninguna línea, sin tildes ni mayúsculas, contiene un trozo de 4 caracteres de esos datos ni «ana», «juan» o el número como palabra entera. Y cada línea de la compuerta pasa **entera** por `PuertaDeTelemetria.mensaje`: el tag `pregunta`, la clase, «pregunta de N caracteres», «respuesta de N caracteres» y «N opciones» sobreviven |
| 609 | Los seis casos que el control reprodujo contra la tabla real, todos con el armado de verdad: «léeme los mensajes de Zorbax» + `send_sms` a un número con «Ya voy»; «revisa mi correo» + `send_sms` con «Ok gracias»; «llama a mamá» + `call` a otro número; «busca la marca de este producto» + `call`; «mándale a Ana que llego tarde» + `send_sms` al número de otro; y el mismo pedido + `send_email` a «jefe@…» → los seis preguntan. Y lo que sí se corresponde pasa: «mándale a Ana que llego tarde» + `send_email` a «ana@…» con «Llego tarde», y «mándale al 310 445 9821 que llego tarde» + `send_sms` a «+57 310-445-9821» (mismo número con separadores y prefijo) |
| 610 | Con el pedido «mira el chat de Zorbax»: turno 1 pregunta por el mensaje y la persona autoriza («sí, mándaselo»); turno 2 repite la misma acción y pasa sin preguntar; turno 3 va al MISMO destinatario con otro texto → vuelve a preguntar y no llega al teléfono. Igual con `share_text`, que no tiene destinatario: autorizado uno, el siguiente con otro texto se pregunta —antes su llave quedaba vacía y un «sí» autorizaba todos— |
| 611 | El mismo turno sensible con tres orígenes: `dijoLaPersona` = el pedido de la persona → ejecuta; el objetivo de la acción anticipada autónoma («ACCIÓN PREVENTIVA AUTÓNOMA (…): mándale a Ana…») con `dijoLaPersona = null` → pregunta; y el pedido «no, déjalo» con el `CONTEXTO INMEDIATO` de la propuesta rechazada pegado al `goal` pero fuera de `dijoLaPersona` → pregunta. Y las fuentes de la app: `GraphApp` pasa `dijoLaPersona` en sus dos llamadas a `run` |
| 612 | `set_alarm` sin `hour` en dos turnos seguidos: pregunta UNA vez, la alarma de las 8 **no** llega al teléfono en ninguno de los dos, y el segundo vuelve al cerebro diciendo que el dato sigue faltando. Un `launch_app app=Banco` que sigue ambiguo en el turno 2, igual: no abre ninguna. Y un permiso contestado que sí sigue pasando sin preguntar, que es lo único que el atajo debe saltar |
| 613 | El canal cuyo `ask` lanza (la pantalla murió): lo sensible no llega al teléfono, el resultado lo dice, la corrida **termina** y pide su turno siguiente. El canal que contesta «» (la persona cerró la duda): cuenta como no. Y las fuentes de la app: `MainActivity.ask` no usa `setCancelable(false)`, contesta «» al cerrarse y al destruirse la pantalla, y ni ella ni `FloatingBubble` tienen plazo que conteste solo |
| 614 | Siempre con dos turnos de la misma acción, que es como una acción autorizada llega a hacerse (605): «claro, no hay problema, mándalo» → se pregunta una segunda vez y, contestado «dale», el turno siguiente la hace; contestada otra ambigüedad, no la hace y no se pregunta una tercera. «no, déjalo» y «» → niegan a la primera, sin repreguntar. «sí, mándaselo» → autoriza a la primera |

### Sabotajes (cada uno pone roja su promesa)

| # | Sabotaje | Lo que debe decir el juez |
|---|---|---|
| 601 | la tabla de acciones sensibles se vacía | roja: `send_sms` llega al teléfono sin preguntar |
| 601 | el pedido autoriza con solo nombrar la acción (no se mira el destinatario) | roja: el correo al jefe no pregunta |
| 602 | la pregunta de «cuál» ofrece los candidatos que no vio (lista fija) | roja: las opciones no son las que vio |
| 602 | dos candidatos ya no son ambigüedad (basta uno) | roja: `launch_app app=Banco` no pregunta |
| 603 | el dato que falta se pide por el final de la lista, no por el más importante | roja: `create_event` pregunta por el título y no por el cuándo |
| 603 | la hora que falta vuelve a caer en el default | roja: la alarma de las 8 llega al teléfono |
| 604 | la espera de la respuesta se acota con un plazo que sigue sin respuesta | roja: la corrida termina sola y actúa |
| 605 | el resultado de la acción frenada no lleva lo que la persona contestó | roja: el turno 2 no ve la respuesta en sus `results` |
| 606 | el registro de lo ya preguntado se vacía en cada turno | roja: la misma acción pregunta dos veces |
| 606 | una negación cuenta como autorización | roja: «no» ejecuta la acción |
| 607 | sin canal, la acción sensible se ejecuta «con tu mejor criterio» | roja: el `send_sms` llega al teléfono |
| 608 | el log de la compuerta nombra el destinatario | roja: «ana» sale en una línea |
| 608 | la clase de la pregunta sale como texto libre (no está en la lista cerrada de la puerta) | roja: la línea sale como `‹N›` |
| 609 | un destinatario que no se puede cruzar vuelve a autorizar (como antes del control) | roja: «llama a mamá» llama a otro número |
| 609 | los teléfonos se comparan como texto, sin normalizar separadores ni prefijo | roja: el número pedido con prefijo no autoriza su propia llamada |
| 610 | la llave de lo ya contestado vuelve a no llevar el contenido | roja: el segundo mensaje con otro texto no pregunta |
| 611 | la compuerta vuelve a comparar contra el `goal` entero, no contra lo que dijo la persona | roja: la acción anticipada autónoma ejecuta sin preguntar |
| 612 | el atajo de «ya contestado» vuelve a ir antes de distinguir la clase | roja: la alarma de las 8 llega al teléfono en el segundo turno |
| 613 | un canal que revienta tumba la corrida en vez de dejarla seguir sin ejecutar | roja: la corrida no pide el turno siguiente |
| 614 | una negación en cualquier posición vuelve a contar como negación | roja: «claro, no hay problema, mándalo» no ejecuta y no repregunta |

---

## Lo que el control encontró (y por qué existe la fase E3)

El control de la E1/E2 dio **NO APROBADO**: la compuerta frenaba los casos del contrato, pero se saltaba con frases
ordinarias. La causa era una sola y estaba escrita como si fuera una comodidad: `nombra()` devolvía `true` cuando no había
palabras propias que cruzar. Como un número de teléfono no tiene letras y `call` no tiene contenido, **las dos
comprobaciones que debían frenar quedaban vacías** y bastaba con nombrar el verbo.

| Lo que se colaba | Por qué |
|---|---|
| «léeme los mensajes de Zorbax» → `send_sms` a un número con «Ya voy» | «mensajes» es verbo de mensaje; el número no cruza; «Ya voy» no tiene palabra de 4 |
| «llama a mamá» → `call` a **otro** número | «llama» ✓; un número nunca cruzaba |
| «busca la marca de este producto» → `call` | «marca» estaba en los verbos de llamada |
| «mándale a Ana que llego tarde» → `send_sms` al número de **otro** | el destinatario real era un número, y no se comparaba |
| un «sí» a un mensaje → segundo mensaje al mismo destinatario con **otro** texto | la llave de lo ya contestado no llevaba el contenido |
| un objetivo que redactó el modelo | la compuerta leía el `goal` como si lo hubiera escrito la persona |

La lección, que vale más allá de esta spec: **una comprobación que no puede comparar tiene que frenar, no rendirse.** Un
`return true` por «no hay nada que mirar» convierte una compuerta en un adorno, y no hay test que lo note mientras los casos
del contrato traigan siempre datos cruzables.

---

## Las fases

### Fase E1 — la compuerta y su cableado (esta corrida)

- `core/…/pregunta/AccionSensible.kt` — la tabla cerrada de lo sensible, las palabras de cada clase y si el pedido lo pidió.
- `core/…/pregunta/Pregunta.kt` — la pregunta (clase, asunto, texto, opciones) y cómo se lee una respuesta
  (niega / ambigua / autoriza; la tercera la trajo la E3).
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

### Fase E3 — que la compuerta no se salte con una frase ordinaria (tras el NO APROBADO del control)

- `core/…/pregunta/AccionSensible.kt` — la correspondencia de destinatario y contenido: lo que no se puede cruzar **frena**
  en vez de rendirse, y un teléfono se compara como número (sin separadores ni prefijo de país), no como texto.
- `core/…/pregunta/Pregunta.kt` — la respuesta se lee en tres (niega / ambigua / autoriza) y una negación cuenta por dónde
  cae, no por estar.
- `core/…/pregunta/CompuertaDePregunta.kt` — la llave de lo ya contestado lleva el contenido; el atajo de «ya contestado»
  solo salta el permiso; un dato o un «cuál» que sigue faltando se vuelve a pedir y después falla sin ejecutar; un canal que
  revienta no tumba la corrida; y se retira el `pendiente` que no usaba nadie.
- `core/…/application/Engine.kt` — `run(goal, announce, dijoLaPersona)`: la compuerta se arma con lo que dijo la persona, no
  con el objetivo que pudo redactar el modelo.
- `app/…/GraphApp.kt` — marca el origen en sus dos llamadas: la corrida normal pasa los prompts de la persona (el
  `CONTEXTO INMEDIATO` de una propuesta va en el `goal` y **no** autoriza), y la acción anticipada autónoma pasa `null`.
- `app/…/ui/MainActivity.kt` — la duda se puede cerrar, cerrarla cuenta como «no», y si la pantalla muere la espera se
  suelta con un «no» en vez de dejar la corrida colgada para siempre.

Pone verdes: **609-614**.

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

  **El control insistió en esto y hay que leerlo con los ojos abiertos:** con el subconsciente apagado, lo que la compuerta
  cubre de verdad son **cuatro herramientas** (`send_sms`, `send_email`, `call`, `share_text`) más las aprendidas por
  etiqueta. Un toque por coordenadas sobre «Enviar» **no pasa por la compuerta** y manda el mensaje igual. No es un agujero
  de la implementación, es el alcance de esta spec — pero significa que la compuerta **reduce** el daño, no lo cierra, y
  nadie debe contarla como si lo cerrara hasta que 3E cablee `nodoEn`.
- **Los pasos de un workflow** (`WorkflowRunner`): tocan por etiqueta a través de la puerta, no por el motor. Un workflow lo
  enseñó la persona; frenarlo paso a paso es otra decisión, con su spec.
- **La pregunta de Graph** (`ask_user`): sigue igual, por `inform`, y no entra al registro de lo ya preguntado. Repetirla o
  no es cosa de Graph.
- **Interpretar la respuesta.** El cliente solo lee si niega; lo demás lo decide el cerebro con el contexto entero. Un
  clasificador de «sí/no» en el cliente es justo lo que el cerebro hace mejor.
- **Preguntar por lo aprendido** (memoria durable, preferencias): la compuerta mira el pedido y la pantalla, no la memoria.

## Límites conocidos

- Un destinatario **sin letras** —el número que el cerebro resolvió de los contactos— solo se cruza con el pedido si el
  pedido trae un número. Cuando no lo trae, la compuerta **pregunta**, aunque la persona haya dicho «mándale a Ana»: el
  cliente no resuelve contactos, así que no tiene forma de saber si ese número es el de Ana. Es preguntar de más en el caso
  más común de todos, y es a propósito: el control demostró que la alternativa —dar por bueno lo que no se puede
  comparar— deja pasar un SMS al número equivocado con el texto correcto. Si algún día el cliente resuelve contactos, esto
  se revisa con su promesa.
- Las listas de palabras son cerradas y en español: un pedido en otro idioma («text Ana that I'm late») no autoriza, así que
  el cliente pregunta. Preguntar de más es el lado seguro del error.
- El registro de lo ya preguntado vive en la corrida del motor: un **reencaminado** (la persona habla otra vez) arma una
  sesión nueva y puede volver a preguntar lo mismo. Es deliberado: el pedido cambió.
- Un **paso consciente** de un workflow corre con un objetivo sintético que suele nombrar la acción del paso, así que se
  autoriza a sí mismo. Hoy no se nota (`subconsciousExecution = false`), y cuando se encienda hay que decidirlo aparte.
- La ambigüedad se mide sobre lo que `uiContext` muestra: hasta 28 etiquetas y **sin repetidas**, así que dos elementos con
  la misma etiqueta exacta no se ven como dos. Se ven los parecidos, que es el caso que rompe.
- **La pregunta se dice en voz alta, y ese canal lleva contenido.** `CompuertaDePregunta` habla por `Voice.speak` antes de
  preguntar, y el texto nombra al destinatario («¿Se lo mando a «Ana»?») y las opciones de un «cuál». Al **log** no sale
  nada de eso (promesa 608), pero el proveedor de voz sí lo recibe: es el mismo canal por el que ya salen el resumen y las
  narraciones del motor, y frenarlo aquí sería dejar a la persona sin saber qué se le pregunta. Queda **anotado, no
  cambiado**: si algún día la voz deja de ser de confianza, el recorte es de la voz entera, no de esta pregunta.

## Riesgo

Que pregunte de más y canse. Se acota por diseño: solo lo de la tabla cerrada pregunta, una vez por asunto y por corrida, y
lo que el pedido ya dijo pasa de largo. Si en el teléfono resulta pesada, se recorta la tabla —con su promesa— antes que la
regla.
