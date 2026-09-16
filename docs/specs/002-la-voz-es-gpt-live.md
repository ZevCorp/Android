# Plan de implementación: la voz es GPT-Live — conversación fluida por voz

Estado: **fases A1, A2, B1a y B1b implementadas** (2026-09-15; promesas 201-246 verdes; la corrida en el teléfono, nivel 4, pendiente de confirmación del Capitán) · **fase 2B2a implementada** (2026-09-16; promesas 247-253 verdes: el delegado ya sabe dónde está, qué ve y qué podrá hacer; cada promesa se vio ROJA con un sabotaje real, abajo; la corrida en el teléfono, nivel 4, pendiente) · **arreglos del control de la 2B2a** (2026-09-16; promesas 254-258 verdes, **132 en total**: mirar ya no congela la charla, la sesión cuenta sus bytes y el catálogo es el de verdad; siete sabotajes, cada uno rojo sobre su promesa) · Nace de portar la voz de `U-Windows-App`,
que ya conversa con GPT-Live-1 medido contra el servidor · Rama: `yokh/voz-gpt-live`

El Android de hoy no conversa: escucha una orden, piensa y contesta. Windows ya mantiene una
conversación de voz continua con **GPT-Live-1** (`wss://api.openai.com/v1/live/sessions`): la voz
charla y un modelo **delegado** (`gpt-5.6-luna`) lleva las herramientas. Este plan lo trae al
Android **reescribiendo nativo el comportamiento y el porqué, nunca el archivo**. La fuente es el
código de U (solo lectura); la spec 018 de U se contradice en varios detalles, y donde lo hace
**manda el código**.

---

## Diagnóstico: qué se midió

Se midió leyendo el código de U y las sondas que U corrió contra el servidor real, no suponiendo.

| Qué | Medida | Fuente |
|---|---|---|
| GPT-Live no es Realtime con otro nombre | `gpt-live-1` en `/v1/realtime` contesta «not supported in realtime mode»; va por `/v1/live/sessions`, sin `?model=`, y el servidor calla hasta `session.start` | `voz/Realtime/ProtocoloGptLive.cs:11-14` |
| La voz no lleva herramientas | las lleva el delegado dentro de `session.delegation.responses`; las instrucciones completas van al delegado, íntegras | `ProtocoloGptLive.cs:16-19, 133-195` |
| La sesión es inmutable salvo la delegación | `session.update` con `session.instructions` da «Unknown parameter»; con `delegation` da `session.updated`. Sin el `session.instructions.append` detrás, la voz afirmó 3 de 3 lo que nadie hizo | `ProtocoloGptLive.cs:21-24, 155-183` |
| No hay marcas de turno | ni `speech_started` ni `response.done`; `response.completed` es del delegado y la voz sigue hablando segundos después | `ProtocoloGptLive.cs:26-28` |
| Una llamada llega tres veces | `output_item.added` (arguments vacío) a 1551 ms, `function_call_arguments.done` a 1788, `output_item.done` a 1822; solo la última es la llamada | `ProtocoloGptLive.cs:327-338` |
| El silencio del servidor son ceros exactos | un delta de 100 ms cada ~100-130 ms también callado; las pausas dentro de una frase bajan a pico 1, y un umbral de 64 se comía 11-27 deltas de pausa | `ProtocoloGptLive.cs:303-315, 375-392` |
| Un resultado grande deja la llamada pendiente | un mensaje de 41 084 B dio `response_input_buffer_full` y `function_call_outputs_required`; cada `response.create` posterior falló | `ProtocoloGptLive.cs:221-277` |
| Texto sin `response.create` no se contesta | medido; pedirlo es un mensaje aparte | `ProtocoloGptLive.cs:209-213` |
| El turno se cierra a los 2000 ms, al borde | entre devolver una herramienta y la voz: 1566-1733 ms y un hueco de 2009 ms; con 1500 se cerraba a mitad de tarea | `windows-client/src/Voice/TurnosSinMarca.cs:451-462` |
| Pico de voz 1000 | silencio del servidor pica en 45; la palabra más floja, en 1152 | `TurnosSinMarca.cs:469-475` |
| La compuerta de eco decide por estado, no por volumen | cola con bytes = Ü suena; gracia 300 ms; por defecto el micrófono viaja siempre (decisión del dueño, 2026-08-31) | `voz/Realtime/CompuertaDeEco.cs`, `ModoDeCaptura.cs`, `ConversacionEnVivo.cs:143, 193-201` |
| El detector de interrupción | sostén 240 ms, factor 3.0, piso 500, siembra 250 ms, base `0.8/0.2`, desarmado tras disparar | `voz/Realtime/DetectorDeInterrupcion.cs:64-100`, `ConversacionEnVivo.cs:151` |
| Tres fallos no se arreglan reconectando | sin crédito, clave inválida (HTTP 401 en el apretón de manos de GPT-Live) y modelo inexistente; se reconocen por código y palabra entera, nunca por prosa | `windows-client/src/Voice/NoSeArreglaReintentando.cs` |

**Lo que esto significa:** todo lo que falla en silencio con GPT-Live —la llamada ejecutada tres
veces, el silencio que suena, el resultado que deja la llamada colgada, el turno que no se cierra
nunca o se cierra a mitad de tarea— se decide en código puro, sin socket. Esa capa se escribe y se
juzga primero; el socket y el micrófono vienen después y solo cablean.

---

## Por qué esto va dirigido por especificación

Porque ninguno de estos fallos tira una excepción. Si se traducen las tres copias de una llamada,
la herramienta corre tres veces y la primera sin argumentos. Si el silencio del servidor se toma
por sonido, la compuerta de eco no se reabre nunca. Si un resultado pasa de 32 768 bytes, la
sesión sigue abierta, la voz sigue hablando y el delegado ya no hace nada. Si el turno se cierra a
los 1999 ms, se corta la tarea 1 de cada 10 veces. Todos fallan en verde.

**La regla del flujo, y no tiene excepciones:** ninguna línea de producción entra antes que la
promesa que la juzga. El contrato nace ROJO (commit 2) y la implementación lo pone INTACTO
(commit 3).

---

## La especificación

Esta spec numera sus promesas **desde 201** (spec NNN → NNN×100+1; ver `docs/como-trabajamos.md`).
El enunciado de cada promesa es **literal** el del test
(`core/src/commonTest/kotlin/graph/core/contrato/Contrato002VozGptLive.kt`, método `promesaNNN`);
si cambia uno, cambia el otro en el mismo commit.

| # | Promesa | Fase |
|---|---|---|
| 201 | La apertura es un solo `session.start` a `wss://api.openai.com/v1/live/sessions` con la clave en la cabecera y no en la URL; modelo gpt-live-1, voz marin, audio PCM a 24 kHz, y las herramientas viajan solo dentro de la delegación a gpt-5.6-luna, con las instrucciones del delegado byte a byte. | A1 |
| 202 | Escribir manda el mensaje del usuario y pide respuesta; entregar resultados es un mensaje por llamada y no pide respuesta, y pedirla es un mensaje aparte. | A1 |
| 203 | De las tres copias de una llamada solo cuenta `response.output_item.done`; sus argumentos llegan como mapa de texto y un valor no texto viaja como su JSON crudo; un JSON ilegible no revienta, ni uno demasiado anidado: en los argumentos da un mapa vacío y en el mensaje entero, ningún hecho. | A1 |
| 204 | El audio de salida vacío o hecho de ceros no suena; una pausa de pico 1 y una muestra con solo el byte alto sí suenan con el PCM exacto; el micrófono viaja en `session.input_audio.append` con su PCM exacto en base64. | A1 |
| 205 | Las transcripciones se traducen a lo que dijo el usuario y a lo que dijo Ü; `error` da Falla con su code literal (vacío si no trae), `session.closed` da Falla con su motivo y code vacío; ningún mensaje de GPT-Live produce CierraElTurno ni HablaronEncima; solo `session.started` es Abierta. | A1 |
| 206 | `session.usage.updated` da la Duración acumulada solo si trae segundos numéricos, finitos y no negativos. | A1 |
| 207 | Un resultado de herramienta nunca pasa de 32 768 bytes serializados: si no cabe se recorta sin partir caracteres y dice cuánto se recortó de cuánto; si cabe, viaja entero; y GPT-Live no se declara capaz de mirar, porque una captura no cabe. | A1 |
| 208 | Cambiar de modo no reabre la sesión: manda `session.update` con la delegación entera y detrás `session.instructions.append` con el prefijo literal de cambio de modo y las reglas nuevas, o con el de vuelta y la persona de la voz cuando se regresa al modo de siempre. | A1 |
| 209 | Dictar es `session.commentary.append` con delegation_id nulo y el prefijo literal delante del texto; no abre sesión nueva ni pide `response.create`, y en blanco no manda nada. | A1 |
| 210 | El primer trozo del usuario abre turno y el segundo no; el turno se cierra una sola vez a los 2000 ms exactos del último trozo, no a los 1999, y el audio en ceros no retrasa el cierre. | A1 |
| 211 | No se cierra el turno con llamadas en curso; la devolución de la última vuelve a contar el silencio desde ese momento; y una llamada devuelta antes de oírse no queda en curso. | A1 |
| 212 | Una pausa sin respuesta de Ü sigue siendo la misma petición; cerrar sin que Ü contestara no abre una petición nueva. | A1 |
| 213 | El audio de Ü con pico por encima de 1000 sostiene el turno abierto y el de pico 1000 o menos no; sonido sin nada dicho no abre un turno que cerrar. | A1 |
| 214 | La compuerta nace abierta; mientras Ü suena el micrófono sale como ceros del mismo tamaño, la gracia aguanta 300 ms tras vaciarse la cola y luego el trozo pasa idéntico; abrir la reabre sin esperar, y los ms tragados se cuentan. | A1 |
| 215 | Por defecto el micrófono viaja siempre sin compuerta; con AEC no actúa; forzarla la activa siempre. | A1 |
| 216 | La voz sostenida sobre la línea base dispara la interrupción; un golpe corto y el eco fuerte no disparan; tras disparar no vuelve a disparar hasta que Ü suene otra vez. | A1 |
| 217 | Sin crédito, clave inválida (incluido HTTP 401) o modelo inexistente son fatales y se dicen con su causa; cualquier otro código, prosa o vacío se puede reintentar. | A1 |
| 218 | Sin credencial la voz no llama a nadie y dice qué falta, y la credencial se pide de nuevo en cada apertura; un error de red al abrir se reintenta hasta 3 veces con esperas de 1 s y 2 s; un 401 del apretón de manos, una causa fatal o un fallo al abrir que no es de red no se reintentan. | A2 |
| 219 | «Sesión abierta» y el mensaje de conexión se dicen una sola vez y solo al confirmarse la sesión, nunca al conectar el socket, y «olvidé lo último» solo si antes se confirmó alguna; sin sesión confirmada el micrófono no viaja. | A2 |
| 220 | Una tanda de llamadas se contesta entera y pide respuesta una sola vez, solo cuando no queda ninguna llamada sin contestar; una llamada retirada no se ejecuta. | A2 |
| 221 | Una herramienta que revienta se contesta con su error y nunca deja el turno abierto; su resultado pasa por el recorte. | A2 |
| 222 | El turno se cierra por silencio incluso cuando llega un mensaje sin hechos; con una llamada en curso no se cierra. | A2 |
| 223 | Una causa fatal termina la voz y se dice una sola vez, llegue por error, por cierre o por el apretón de manos; un corte de red reconecta hasta 4 veces con espera creciente, y cerrar un turno devuelve el contador a cero. | A2 |
| 224 | Todas las vías de terminar la escucha (cierre, excepción, cancelación) pasan por la misma decisión; detener nunca reconecta ni anuncia un fatal, y una cancelación que llega del canal con la voz viva es un corte y reconecta. | A2 |
| 225 | Cada conexión empieza con el marcador de turnos nuevo y sin la falla de antes de abrir de la anterior; los segundos de voz se suman entre conexiones y se reportan al detener. | A2 |
| 226 | Un aviso del sistema espera a que la sesión se confirme y a que no queden llamadas pendientes, sale una sola vez con su respuesta pedida y no abre una petición del usuario; con la voz muerta se descarta, lo devuelve y lo deja en el log. | A2 |
| 227 | El audio, las transcripciones y los mensajes del delegado y los argumentos de las llamadas nunca se escriben en el log. | A2 |
| 228 | Al acercarse al tope de 128 items por sesión, contando cada llamada del delegado, se avisa una vez en el log, sin cortar la conversación. | A2 |
| 229 | Con el barge-in por energía encendido, cuando el detector dispara el altavoz se calla, la compuerta se reabre y el trozo viaja intacto, y cada frase nueva de Ü vuelve a sembrar su eco; por defecto está apagado, y sin compuerta activa el detector no actúa. | A2 |
| 230 | Cambiar de modo en plena sesión manda la delegación nueva sin reabrir, y si la sesión se corta, la reapertura ya abre en el modo vigente. | A2 |
| 231 | Detener corta cualquier espera en curso: la voz termina enseguida, no cuando vence la espera. | A2 |
| 232 | Una herramienta que se cancela por su cuenta o lanza un error grave se contesta con su motivo y la voz sigue atendiendo las siguientes; solo terminar la conversación la cancela, y entonces no se contesta. | A2 |
| 233 | Al reconectar, lo que quedó corriendo de la conexión anterior se cancela, no se contesta en la nueva y no bloquea sus herramientas. | A2 |
| 234 | Parar y las herramientas de control no esperan detrás de una herramienta que actúa en la pantalla; las que actúan en la pantalla siguen yendo de a una. | A2 |
| 235 | Retirar una llamada la contesta como no ejecutada, para que el servidor no quede esperando su salida. | A2 |
| 236 | La conversación se atiende de a una cosa por vez aunque la llamen desde varios hilos: ninguna llamada queda en curso por una carrera y ningún envío se intercala con otro. | A2 |
| 237 | Al log de la voz nunca llega el contenido de una herramienta ni de un error: solo su tipo y un motivo saneado. | A2 |
| 238 | Lo escrito con llamadas sin contestar abre su petición y espera en la misma cola que los avisos, sin prefijo, hasta salir con un solo pedido de respuesta; si la conexión muere, lo escrito en cola se descarta y los avisos pasan a la siguiente. | A2 |
| 239 | El canal real entrega un apretón de manos rechazado como rechazo con su código HTTP y su código de error de cabecera; un 401 nunca se confunde con falta de red. | B1a |
| 240 | Un servidor inalcanzable es falta de red con un motivo que no trae la clave ni la URL completa. | B1a |
| 241 | Los mensajes del servidor llegan en el orden en que se mandaron y enviar no espera a que se lea lo recibido. | B1a |
| 242 | Un cierre del servidor llega con su código y motivo; una conexión que se cae sin cerrar llega como cierre por red. | B1a |
| 243 | La clave viaja solo en la cabecera, nunca en la URL ni en el log, y cerrar el canal dos veces no rompe nada ni deja hilos vivos. | B1a |
| 244 | La cola del altavoz guarda como mucho 30 segundos y al llenarse descarta lo más viejo; suena solo si tiene bytes, nunca por volumen, y callar la vacía en el acto. | B1b |
| 245 | A la telemetría remota de la voz solo llega la medida: el largo de cada frase y el cierre del turno; ninguna frase, argumento ni texto del delegado sale del teléfono. | B1b |
| 246 | La voz en vivo solo se arranca desde el panel de desarrollador y toma su clave del build interno, nunca de la configuración remota. | B1b |
| 247 | Dónde estoy: la voz contesta con la app al frente, el tipo de pantalla y su tamaño, leídos del mismo estado que ya arma el turno de Graph y sin pedir captura; si no hay pantalla que leer lo dice y no se la inventa. | 2B2a |
| 248 | Qué veo: las etiquetas visibles, cuántos elementos se pueden tocar y el campo enfocado salen del `uiContext` que ya viaja a Graph; un filtro de hasta 60 caracteres contesta si algo está en pantalla sin mirar tildes ni mayúsculas, y lo que la pantalla no deja leer se dice tal cual. | 2B2a |
| 249 | Qué puedo hacer: el catálogo de capacidades se deriva del catálogo real de acciones, así que una acción nueva aparece sin tocar la voz; va agrupado por vía y cabe en un resultado aunque una descripción sea enorme. | 2B2a |
| 250 | Las tres herramientas de la voz solo leen: no reciben manos, así que ninguna toca la pantalla ni abre nada, y cualquier otra llamada del delegado se contesta «todavía no» sin ejecutar nada. | 2B2a |
| 251 | La sesión abre con las tres herramientas dentro de la delegación y ninguna en la voz; declararlas no gasta items, así que la conversación empieza en cero de los 128, y la apertura entera cabe de sobra en los 32 768 bytes de la sesión. | 2B2a |
| 252 | Leer no congela la charla: las tres son de control, así que una lectura retenida no frena a las que vienen detrás, ni el micrófono, ni el cierre del turno. | 2B2a |
| 253 | Del teléfono solo sale la medida de lo que se mira —cuántas etiquetas y cuántos caracteres—: ni una etiqueta, ni el filtro, ni lo que la pantalla muestra llegan al log local, y lo que llega a la telemetría remota pasa por el filtro de la voz y por la puerta sin una palabra de la pantalla. | 2B2a |
| 254 | Mirar no congela la conversación: la lectura de pantalla corre en su propio despachador y no en el hilo de la voz, así que aunque BLOQUEE el hilo el micrófono sigue viajando y las demás llamadas se contestan; y si tarda más que el tope se contesta que no se pudo mirar, en vez de dejar muda a la voz. | 2B2a |
| 255 | La sesión cuenta sus bytes además de sus items: cada item suma lo que ocupa, se avisa una vez antes de cruzar los 32 768 bytes que admite el servidor y sin cortar nada, y lo que devuelve el catálogo está acotado para que una sola respuesta no se gaste el presupuesto entero. | 2B2a |
| 256 | El catálogo que lee el delegado trae las herramientas aprendidas con el mismo criterio que una corrida: la voz y la anticipación se las piden al único sitio que lo decide, y ninguna de las dos escribe una lista vacía a mano. | 2B2a |
| 257 | Una etiqueta de la pantalla se sanea en origen —sin saltos de línea ni el separador con que se unen— y quien la lee es tolerante: una etiqueta rara no hace decir «no lo veo» de algo que está ni infla la cuenta, y el campo enfocado sale entero aunque su texto traiga comillas y paréntesis. | 2B2a |
| 258 | Sin servicio de accesibilidad las tres herramientas dicen la misma causa con las mismas palabras: qué puedo hacer ya no la calla devolviendo un catálogo vacío, que se lee como que Ü no sabe hacer nada. | 2B2a |

**La que cierra el asunto es la 203.** Un traductor que ejecuta la llamada tres veces, la primera
sin argumentos, hace otra cosa que lo que se pidió y no avisa. Las demás protegen el camino; la
203 es la que decide si la voz hace lo que el usuario dijo.

### Con qué se juzga cada una

Todas son **entradas y relojes a mano dentro de la propia prueba**: mensajes JSON copiados
de las capturas de U, PCM construido byte a byte y un reloj que es una variable. Ninguna toca red,
micrófono, altavoz ni Android. La 236 es la única que no cabe en `commonTest`: allí `corre` es un
`runBlocking` de un solo hilo, que no ve una carrera nunca; vive en `jvmTest` con hilos de verdad. Las 239-243
juzgan el canal real, que es OkHttp y solo existe en jvm: también viven en `jvmTest`, contra un servidor
WebSocket por localhost (MockWebServer, y un `ServerSocket` a mano para la caída). La 246 juzga dónde se
arranca la voz y de dónde sale su clave, que viven en `app` (Android): en `jvmTest` se leen sus fuentes. Ninguna abre una
sesión con OpenAI.

Las pruebas de la conversación corren dentro de `corre`, que en jvm corta a los 30 s: una conversación que se cuelga —una
salida que no llama a `acabar()`— da rojo con su nombre en vez de dejar el contrato corriendo para siempre. **Ese cambio de
`core/src/jvmTest/…/Corre.jvm.kt` es del juez de todas las ramas: al integrar se copia idéntico a las demás.**

| # | Cómo se juzga sin tocar nada |
|---|---|
| 201 | Se parsea el JSON emitido y se comparan campos, nunca texto crudo. Instrucciones del delegado de 24 KB con tildes, comillas, barra invertida, tabuladores y saltos de línea: llegan iguales en bytes UTF-8. La sesión no tiene `tools`. La URL no lleva la clave; la cabecera `Authorization` sí. Un delegado distinto se elige al construir. Las instrucciones empiezan con un espacio y acaban en salto de línea: un `trim()` en el camino también se ve |
| 202 | `texto()` da dos mensajes: el `response.item.create` con `input_text` y el `response.create`. `resultados()` de dos llamadas da dos `function_call_output` y ninguno es `response.create`. `pedirRespuesta()` es solo `{"type":"response.create"}` |
| 203 | Las tres copias reales de una llamada (capturadas por U el 2026-09-12) dan UN `Pide` con sus argumentos. Argumentos con número, booleano, objeto y null salen como `7`, `true`, `{"a":[1,2]}`, `null`. Argumentos ilegibles o que no son objeto dan mapa vacío; un mensaje ilegible, vacío o sin `type` da lista vacía. Demasiado anidado, en un hilo de pila chica para que reventar se vea rojo: argumentos de 1000 niveles dentro de un texto con comillas escapadas, «[» × 4000 y un argumento que es una lista de 4000 objetos dan la llamada con mapa vacío; 64 niveles se leen y 65 no; un mensaje «[» × 4000, uno de 4000 objetos, uno de 4000 objetos dentro de una lista y un error sin message de 1000 niveles dan lista vacía, y la voz que los recibe, también la lista de 4000 objetos, sigue oyendo sin volcarlos. Un error sin message sale con su texto crudo, espacios incluidos, recortado a 400 car. |
| 204 | Delta vacío y 4800 B de ceros: ningún `Suena`. Pausa con muestras +1 y −1, muestra 256 (solo byte alto), muestra −32768 y un seno de pico 7000: `Suena` con el PCM idéntico. `audio(pcm)` lleva el base64 exacto |
| 205 | Transcripciones reales de U; `error` con code, sin code, sin message y no objeto; `session.closed` con motivo y sin él. Todo lo leído más `session.delegation.created`, `response.completed` y `session.usage.updated`: ningún cierre de turno ni «hablaron encima», y solo `session.started` es `Abierta` |
| 206 | 12.0 y luego 25.0 salen tal cual (acumulado); 7 son 7.0; `"12"` en texto, `usage` vacío o ausente no dan nada; tampoco `NaN`, `1e999` ni `-5` |
| 207 | Un resultado de 40 KB con tildes, emoji y comillas sale en ≤ 32 768 B y > 32 752 B, con el principio del texto y la cola con los bytes exactos. 9000 emoji: lo guardado son emoji enteros. 20 000 comillas (20 KB de texto, 40 KB serializados): se recorta aunque el texto cabría. Un texto que da un mensaje de 32 768 B exactos va entero; uno de 32 769 B, recortado |
| 208 | Al modo aprendiz: `session.update` cuya sesión solo tiene `delegation`, con instrucciones y herramientas nuevas, y detrás el append con el prefijo escrito letra a letra en la prueba. De vuelta: la delegación recupera las instrucciones completas y el append lleva el prefijo de vuelta más la persona de la voz, sin las de operar |
| 209 | `dictar(" voy por el peso ")` es un solo mensaje con `delegation_id` null y el contenido literal; vacío, espacios o saltos y tabuladores dan lista vacía |
| 210 | Reloj a mano: 1000, 1999 ms del último trozo no cierran; 2000 cierra y 2100 ya no. Audio de ceros entre medias no cuenta. Recién nacido, un minuto de audio no cierra nada |
| 211 | Una llamada en curso aguanta 6000 ms; devuelta, 1999 ms no cierran y 2000 sí. Dos llamadas: hasta devolver la segunda. Una devuelta antes del `Pide` no queda en curso |
| 212 | Pausa que cierra sin respuesta de Ü: lo siguiente del usuario no abre. Tras contestar Ü, sí. La respuesta de Ü dentro del mismo turno cuenta; el saludo previo no |
| 213 | Voz de pico 1001 cada 100 ms sostiene 3000 ms tras la transcripción; pico 1000 no sostiene. Sonido de pico 7000 sin nada dicho no abre turno |
| 214 | Recién nacida deja pasar la misma instancia. Sonando: ceros del mismo tamaño. Gracia: a 299 ms tras vaciarse, ceros; a 300, el trozo idéntico. `abrir()` deja pasar a los 50 ms. Tres trozos de 100 ms tragados son 300 ms |
| 215 | `activa(forzada, aec)` sin `sinCaminoDeEco`: falso. Con AEC y sin camino de eco declarado falso: no actúa. Forzada: siempre verdadero. Una conversación construida sin `compuertaActiva`, con Ü sonando: el trozo del micrófono viaja idéntico |
| 216 | Eco 800 aprendido; voz 6000 sostenida dispara. Golpe de un trozo y ráfaga de dos no. Frase nueva de Ü a 2600 tras silencio siembra la base y no dispara. Voz 400 sobre una base de 100 no pasa el piso. Tras disparar, voz con la cola cortada no re-dispara; con Ü sonando otra vez, sí. `rms` de una onda cuadrada ±6000 es 6000. Las defensas, cada una con el caso que la muerde: tras 800 y 1800 la base es 1000 (0.8/0.2); el trozo a 250 ms justos del arranque aún siembra (base 720); una frase nueva tras 300 ms de silencio con eco 3000 sobre una base de 250 no dispara; a trozos de 120 ms, el segundo encima dispara (el sostén cuenta desde el trozo anterior) |
| 217 | Las tres causas con sus códigos (también `type.code` y `401`) nombran su palabra y ninguna otra. Reintentables: vacío, blancos, `response_input_buffer_full`, códigos de cierre, prosa en inglés que menciona 401 o créditos. «fin 401» es fatal: la palabra va separada por espacio, no solo por punto |
| 218 | Credencial nula, vacía o en blanco: ningún `abrir` y una frase con «falta». Dos `SinRed` y un `Ok`: 3 aperturas, esperas 1000 y 2000, un solo `session.start`. Tres `SinRed`: se dice que no hay conexión. `Rechazo(401)`: una apertura y ninguna espera; un `Rechazo(503)` tampoco se reintenta. Un `credit_balance_exhausted` antes de abrir: una apertura. Un `Rechazo(403, "invalid_api_key")`: la cabecera también es causa, una apertura. Una credencial rotada entre conexiones: la reapertura va con la nueva; en blanco al reabrir, no se abre y se dice qué falta. `abrir` que lanza una excepción que no es la red: una apertura, ninguna espera, «No pude abrir la voz en vivo: IllegalStateException: …» |
| 219 | Antes de `session.started` el micrófono no sale, no se dice nada y el log no dice «sesión abierta»; al llegar, «Te escucho.» una vez aunque llegue dos veces. Tras un corte, «Sigo…» solo cuando la segunda conexión confirma. Si el `session.start` de la primera conexión no llega a salir, la que confirma dice «Te escucho.», no «Sigo…» |
| 220 | Tres llamadas con el ejecutor retenido: la primera contestada no pide respuesta; la tercera, retirada, no se ejecuta y se contesta como no ejecutada (235); el único `response.create` sale detrás de las tres salidas |
| 221 | Un ejecutor que lanza: la salida es «la herramienta falló: …» y el turno cierra 2000 ms después. Un resultado de 40 KB viaja en ≤ 32 768 B con la marca de recorte. Una herramienta de control que cancela su propia corrutina: se contesta y el turno cierra igual a los 2000 ms |
| 222 | Reloj a mano: con audio en ceros y `session.updated` como únicos mensajes, 1999 ms no cierran y 2000 sí. Con una llamada retenida, 10 s no cierran; devuelta, a los 2000 ms |
| 223 | Fatal por `error`, por la descripción del cierre y por un 401 al reconectar: una frase «No sigo…» y ninguna reconexión; la primera causa gana. Cinco cortes: esperas 300, 600, 900 y 1200 y se deja. Un turno cerrado entre cortes vuelve a esperar 300 |
| 224 | Un cierre normal y una excepción reconectan; `detener` con un fatal guardado y la cancelación de la corrutina terminan sin reconectar ni decir el fatal. Cada vía deja exactamente una línea «fin de la escucha». Un canal que cancela su `Channel` en `recibir()` y al que, al reconectar, le vence un `withTimeout(0)` en `abrir()`: `conversar` no lanza, reconecta dos veces (300 y 600 ms) y la única «fin de la escucha por cancelación» es la de detener. Un adaptador al que le vence un `withTimeout` al mandar el micrófono: `oirMicrofono` no lanza, deja una línea con el tipo y la voz sigue sin reconectar |
| 225 | Una llamada retenida en la conexión 1 no sujeta el turno de la 3. Un error de antes de abrir en la 1 no convierte en «no pude abrir» un corte sin confirmar de la 2. Duraciones 12, 25 y luego 7: se reportan 32 s al detener |
| 226 | Aviso con una llamada retenida: no sale nada; al contestarla, salida + aviso + un `response.create`. Sin pendientes sale ya. El contador de peticiones no se mueve. Con la voz muerta `avisar` devuelve falso y deja «aviso del sistema descartado». Un aviso antes de `session.started`: se acepta, no sale, y al confirmar sale con su `response.create` |
| 227 | Audio con voz, ceros, delta vacío, `output_text.delta` y `function_call_arguments.delta` del delegado: ni el base64 ni el texto del delegado aparecen en el log; lo que dijo Ü, una sola vez al cerrar el turno; un evento desconocido sí se vuelca. Un `output_item.done` con un mensaje del delegado: solo su tipo. Llamadas con `{"texto":"mi clave es 1234"}` y un número: ningún argumento en el log |
| 228 | Una llamada, su resultado, un aviso y 116 textos: 119 items y ningún aviso (la llamada del delegado ocupa un item); el 120 deja una línea; 15 más no dejan otra y siguen saliendo. La conexión nueva empieza en cero |
| 229 | Compuerta activa: eco 800 y voz 6000 sostenida con Ü sonando; dispara, calla una vez, el trozo que dispara viaja idéntico y el siguiente también (reabierta, sin gracia). Sin compuerta: nunca calla y todo viaja idéntico. Con la bandera: tras disparar, Ü vuelve a hablar con un eco de 3000 que no dispara y una voz de 12 000 encima que sí (rearme). Ü calla, pasa la gracia y la frase siguiente con eco de 3000 no dispara: la compuerta al reabrirse le dice al detector que no suena (siembra por frase). Por defecto, compuerta activa y voz encima: nunca calla y todo se traga |
| 230 | Canal con guion: cambiar al modo aprendiz en plena sesión da `session.update` y el append, sin otro `session.start` ni otra URL. Tras un corte, el `session.start` de la reapertura lleva las instrucciones y herramientas del aprendiz; tras otro cambio y otro corte, las del último. La voz reabre siempre con su persona, y en un modo especial, confirmada la sesión y no antes, recibe el append con el prefijo de cambio de modo y las reglas vigentes. De vuelta al modo de siempre, la reapertura no manda append. Con el `session.start` ya enviado y sin confirmar, cambiar al aprendiz da, al confirmarse, `session.update` con su delegación y herramientas y el append, no solo el append; y si la reapertura salió en aprendiz y se vuelve antes de confirmar, sale la vuelta entera con la persona de la voz |
| 231 | Un reloj cuya espera no vence sola: detener durante la espera de 1 s de un reintento de abrir, y durante la de 300 ms de una reconexión, termina la voz sin avanzar el reloj, sin reabrir y sin decir nada más. Si no termina, la prueba abre la espera y sale roja, no colgada |
| 232 | Cuatro llamadas: una lanza `Paraste` (el freno de 3A, una `CancellationException`), otra vence un `withTimeout(0)`, otra `TODO()` y la cuarta contesta. `conversar` no lanza; las cuatro corren y se contestan en orden («se paró: …», «se paró: …», «falló: An operation is not implemented: …», su salida), lo último que sale es un `response.create` y el turno cierra. Con una colgada y otra detrás, detener o cancelar la corrutina cancela la colgada, la de detrás no corre y ninguna se contesta. Una llamada que cancela su propia corrutina con otra detrás, en la pantalla y de control: las dos corren y se contestan («se paró: …» la primera), un aviso sale, el turno cierra y no se reconecta |
| 233 | Una llamada colgada en la conexión 1 y un corte: confirmada la 2, la colgada ya recibió su cancelación; una llamada de la 2 corre, se contesta sola (la vieja no), pide respuesta y su turno cierra a los 2000 ms |
| 234 | `actuaEnPantalla` solo para `pulsar`. Dos `pulsar` retenidos y detrás `parar`, `como_va` y `self_mute`: las tres de control corren y se contestan con la primera de pantalla retenida, sin `response.create`; la segunda de pantalla corre solo al soltar la primera; un único `response.create` al final. Construida sin `actuaEnPantalla`: `parar` espera detrás de un `pulsar` retenido, como toda herramienta |
| 235 | Una llamada retenida y otra en cola que se retira: la retirada no se ejecuta, su salida es «retirada: no se ejecutó» y el único `response.create` va detrás. Retirada antes de llegar y sola: `session.start`, su salida y un `response.create` |
| 236 | `jvmTest`, despachador por defecto y seis hilos de `Executors` con semilla fija que llaman `oirMicrofono`, `retirar` y `avisar` mientras llegan 48 llamadas de pantalla y de control, 60 rondas con un tope de 15 s cada una. El canal cuenta envíos solapados (cede a mitad de cada uno) y anota cada llamada al entregarla. Nunca dos envíos a la vez; cada llamada con una salida; ningún `response.create` con una llamada entregada y sin salida; tras lo último, un pedido de respuesta; cada aviso aceptado, una vez; y el turno se cierra, así que ninguna quedó en curso. El rojo dice cuál: «carrera detectada» si se rompe algo de eso o la ronda se queda 3 s quieta sin terminar; «no terminó a tiempo» si pasa su tope todavía moviéndose, y solo después de repetirla una vez con la misma semilla. Cuántas rondas se repitieron se imprime («236: rondas reintentadas = N») como diagnóstico y no pone rojo: depende de la carga de la máquina, no del código. El escritor único saltado solo en `avisar` y `limitedParallelism(2)` salen rojos 5 de 5 corridas |
| 237 | Herramientas que lanzan `IllegalStateException`, `CancellationException` y `TODO()` con «mi clave es 1234»: al modelo le llega el motivo, al log solo el tipo. Un canal que revienta con el secreto entre comillas: «se cortó la escucha: IllegalStateException». `abrir` que lanza con `Bearer sk-…`, un token largo, el secreto entre comillas, 800 caracteres y una segunda línea: ni lo dicho ni el log los traen, y la línea queda corta. Una clave corta y suelta (`sk-abc123`, sin «Bearer» ni largo de token): tampoco. Un `error` del servidor que repite entre comillas el valor enviado y la cola de la clave, y un cierre fatal con lo mismo en su motivo: «el servidor dice», «el servidor cerró» y la decisión de fin llegan al log sin eso |
| 238 | Una llamada retenida, un aviso y un texto escrito: no sale nada y la petición del texto ya se abrió; al contestarla, salida, aviso, texto sin prefijo y un único `response.create`. Con la llamada colgada y un corte: lo escrito se descarta con una línea en el log y el aviso sale en la conexión nueva |
| 239 | MockWebServer: un 401 con `x-openai-ide-error-code: invalid_api_key` da `Rechazo(401, "invalid_api_key")`; un 403 sin cabecera, un 200 sin upgrade y un 302 hacia un upgrade que sí abriría dan su rechazo con código nulo. El servidor recibe 4 pedidos: nadie reintenta ni sigue la redirección por dentro |
| 240 | Un puerto de localhost recién cerrado da `SinRed`. Un servidor que acepta y nunca contesta da `SinRed` en menos de 3 s con un tope inyectado de 300 ms (el de OkHttp es 10 s). Ningún motivo trae la clave, «Bearer», la URL, su ruta ni un esquema `://` |
| 241 | Recién abierto, el servidor manda 200 mensajes y el canal manda 200 sin leer ninguno: el servidor recibe los suyos en orden y después llegan los del servidor, en orden. Una escucha que ya espera no frena un envío: el servidor lo recibe y su respuesta despierta a esa escucha. Una ráfaga de 200 leída con esperas de 1 ms que se cancelan una y otra vez llega entera y en orden |
| 242 | El servidor manda un mensaje y cierra con 4000 «invalid_request_error.response_input_buffer_full»: llegan el mensaje y `Cierre(4000, …, porRed = false)`; recibir otra vez repite el cierre y enviar lanza sin colgarse. Un `ServerSocket` a mano contesta el 101, manda un mensaje y suelta el socket sin trama: llegan el mensaje y `Cierre(1006, motivo, porRed = true)`, con un motivo sin la clave |
| 243 | El servidor ve `Authorization: Bearer …` y una URL sin la clave. Tras un mensaje en cada sentido, cerrar dos veces no lanza y el servidor recibe una sola trama 1000 «fin»; recibir da ese cierre y enviar lanza. En 3 s no queda vivo ningún hilo de OkHttp nacido con el canal, y ninguna línea del log trae la clave, «Bearer», la URL ni el contenido de un mensaje |
| 244 | Recién nacida y con un trozo vacío no suena. 31 segundos cuyas muestras dicen qué segundo son: quedan 1 440 000 B, se cuentan 48 000 descartados, lo primero que sale es el segundo 1 y lo último el 30. Un trozo de 35 s sobre uno de 1 s: se queda el final del grande (sale primero su segundo 5) y se cuentan 6 s. 100 ms de ceros en cola suenan, y la compuerta los ve sonando. `sacar` da muestras enteras (4799 pedidos son 4798), en orden a través de trozos y sin rellenar. Callar deja la cola sin nada que sacar, y lo que llega después suena solo |
| 245 | Una conversación entera con el canal con guion: el usuario dice su clave con tildes y un emoji, el delegado pide una herramienta con argumentos y escribe texto, llega un evento desconocido con un secreto y Ü contesta. El log local lo trae todo; pasado por el filtro, ni una palabra de eso: salen `usuario dijo: 37 caracteres` y `Ü dijo: 32 caracteres` (el emoji cuenta uno), del evento desconocido solo su tipo, y cada línea sin contenido —el cierre de la escucha y de la sesión incluidos— pasa igual. A mano: el `toString` de `Llamada`, `Resultado`, `Pide`, `DiceU` y `DiceElUsuario`, un JSON del canal y un «dijo:» a mitad de línea no sacan su contenido; fuera de los tags `voz-` no se toca nada |
| 246 | Fuentes de `app` sin comentarios: el botón «Voz en vivo (prueba)» y toda aparición de `VozEnVivoDev` —calificada, en un `typealias` o en un import con `as`— están dentro de `if (mode == MODE_DEV) { … }`, salvo el import simple; `MODE_DEV` es «dev» y `mode` solo sale de la preferencia. Ningún otro archivo nombra a `VozEnVivoDev` ni a `ConversacionViva`, y en el suyo la conversación vive dentro de la clase. En `VozEnVivoDev.kt` no aparece «remote»; la credencial es `claveDelBuildInterno()`, una expresión que solo nombra `prefs.getString("openaiKey", …)` y `BuildConfig.DEFAULT_OPENAI_KEY`. `LogBus` nombra una vez a `Telemetry` y a `enqueue`, en `TelemetriaDeVoz.paraRemoto(tag, message)?.let { Telemetry.enqueue(tag, it) }`, sin reasignar `tag` ni `message`; nadie más en `app` encola ni declara otro `TelemetriaDeVoz`. Y parar no depende de la pantalla: `detener()` se llama una vez, dentro de `alcance.launch { … }`, con `alcance` propio de la voz |
| 247 | Un `TurnScreenState` armado como el del turno (paquete·título, el `uiContext` de la accesibilidad, 1080×2400): la respuesta nombra la app, el tipo de pantalla, el teclado y el tamaño, y nunca pide `screenshot`. Sin estado que leer sale la frase de que no puede ver; un `uiContext` de «sin contenido accesible» sale tal cual |
| 248 | El `uiContext` de una pantalla con 12 tocables, 2 campos, uno enfocado y 28 etiquetas: salen las etiquetas y las cuentas. Filtros «enviar», «ENVIAR» y «camara» sobre «Cámara»: sí, con lo que encontró; «guardar», que no está: no, y lo dice. Un filtro de 300 caracteres se recorta a 60. Un `uiContext` con otro formato no se inventa: vuelve tal cual |
| 249 | Un `Mcp` de verdad sobre manos falsas: cada nombre de `tools` aparece en el texto, agrupado por vía. Se añade una herramienta aprendida y aparece sin tocar la voz. Con la descripción de 1 500 caracteres de `check_simit_fines` y 40 herramientas de relleno, el mensaje que viaja sigue por debajo del tope de 32 768 B |
| 250 | El ejecutor se construye sin teléfono, gestos ni sistema: no hay con qué tocar. `pulsar`, `escribir`, `launch_app`, `go_home` y un nombre inventado se contestan «todavía no», y el catálogo real que se le pasó no se llama ni una vez |
| 251 | La apertura con el catálogo, parseada: sus `tools` son las tres, la sesión no lleva `tools` fuera de la delegación, `itemsEnSesion` es 0 con la sesión ya confirmada, y los bytes UTF-8 del `session.start` se cuentan contra los 32 768 de la sesión |
| 252 | Una lectura retenida y detrás otra llamada y un trozo de micrófono: la segunda se contesta y el audio viaja con la primera aún retenida; al soltarla salen su salida y un único `response.create`, y el turno cierra |
| 253 | Una pantalla con etiquetas sembradas («Zorbax», «Qwyk» y un teléfono) y un filtro secreto: ninguna línea del log trae un trozo de ellos, solo cuentas y largos; y cada línea pasada por `TelemetriaDeVoz.paraRemoto` y por `PuertaDeTelemetria` conserva su medida sin una palabra de la pantalla |
| 254 | Un ejecutor que BLOQUEA el hilo —un `CountDownLatch` de verdad, no un `CompletableDeferred` que suspende— retiene la lectura, y la traba solo la abre un paso del guion, que corre en el hilo de la conversación: si la lectura volviera a ese hilo, ese paso no llegaría nunca y la espera vencería sola, con ese rojo y no un contrato colgado. Con la lectura trabada viajan el micrófono y la salida de otra herramienta, y no se pide respuesta. El tope se juzga dejando vencer el DE LA CLASE, no uno inyectado, y la medida de lo que se esperó sobrevive la puerta de la telemetría |
| 255 | Una conversación con el canal con guion: `bytesEnSesion` empieza en cero, suma lo que se manda y lo que pide el delegado, un resultado de 31 000 caracteres cruza el aviso y deja UNA línea —ni una más, y se sigue contestando—, y la conexión nueva no hereda los bytes de la anterior. El tope del catálogo entra cuatro veces en los 32 768 de la sesión, medido con 40 herramientas de relleno |
| 256 | Las fuentes de `app`: `aprendidasDisponibles()` se declara UNA vez, la voz y la anticipación la usan las dos, y ninguna escribe `emptyList()` en la llamada al catálogo. Y por comportamiento, un `Mcp` de verdad con una herramienta aprendida: `que_puedo_hacer` la nombra pasando por el ejecutor, no por el catálogo suelto |
| 257 | Etiquetas con salto de línea, con el separador dentro, con un punto medio pegado y de más de 40 caracteres. Y sin sanear, como si llegaran de otra versión del servicio: la cuenta sigue siendo 2 y «Buscar» se encuentra. Un campo enfocado cuyo texto trae `")` sale entero |
| 258 | Sin pantalla y sin catálogo, las tres respuestas nombran el servicio de accesibilidad con las mismas palabras; y con servicio pero sin ninguna acción, la respuesta NO dice que el servicio esté apagado |


### Sabotajes de la fase 2B2a (cada uno pone roja su promesa)

Aplicados sobre `fb4e9cc`, uno a uno y revertidos con `git checkout -- core/src app/src`.

| Sabotaje | Qué rompe | Rojo |
|---|---|---|
| S247 | dónde estoy deja de decir el tamaño de la pantalla | 247 |
| S248 | el filtro vuelve a comparar con tildes y mayúsculas | 248 |
| S249 | el catálogo se escribe a mano: solo las tres primeras acciones | 249 |
| S250 | lo que no se sabe hacer se contesta como si se hubiera hecho | 250 |
| S251 | la delegación declara además una herramienta que actuaría | 251 (y 250) |
| S252 | las lecturas se declaran actuando en la pantalla y hacen cola | 252 (y 250) |
| S253 | el log escribe el filtro que buscó la persona | 253 |

### Sabotajes de los arreglos (cada uno pone roja su promesa)

Aplicados sobre `0863403`, uno a uno y revertidos con `git checkout -- core/src app/src`. Las dos mitades de la 254 y de
la 257 se sabotean por separado: una promesa con dos mitades y un solo sabotaje deja media promesa sin juzgar.

| Sabotaje | Qué rompe | Rojo |
|---|---|---|
| S254a | la lectura vuelve al hilo único de la conversación | 254 |
| S254b | la mirada no tiene tope: se espera para siempre | 254 |
| S255 | los bytes del item no se acumulan en la sesión | 255 |
| S256 | la voz vuelve a pedir el catálogo con una lista vacía | 256 |
| S257a | la etiqueta no se sanea en origen | 257 |
| S257b | el enfocado cierra por la PRIMERA comilla-paréntesis | 257 |
| S258 | un catálogo ausente se contesta como un catálogo vacío | 258 |

---

## Las fases

### Fase A1 — la capa pura del protocolo (esta corrida)

Todo en `core/src/commonMain/kotlin/graph/core/voz/`, sin dependencias nuevas:

- `Hecho.kt` — los hechos de la conversación, `Llamada`, `Utensilio`, `Argumento`, `Resultado`.
- `ProtocoloGptLive.kt` — traductor sin estado, en los dos sentidos.
- `Recorte.kt` — el tope de 32 768 bytes del mensaje serializado.
- `Audio.kt` — `esSilencio`, `pico`, `rms` sobre PCM16LE.
- `TurnosSinMarca.kt` — cuándo empieza una petición y cuándo se cierra el turno.
- `CompuertaDeEco.kt` — la compuerta y `ModoDeCaptura`.
- `DetectorDeInterrupcion.kt` — el barge-in con la compuerta activa.
- `Fatales.kt` — qué fallo no se arregla reconectando.
- `JsonCrudo.kt` — el tope de 64 niveles medido antes de parsear, y el texto crudo de un valor sin re-serializarlo. Desde la
  integración de la ola 1, la guarda (`demasiadoAnidado`, `PROFUNDIDAD_MAXIMA`) vive en `core/…/json/Profundidad.kt`, compartida
  con la 413 de la spec 004.

Pone verdes: **201-217**.

### Fase A2 — la máquina de estados de la conversación (otra corrida)

Pura también, en `core`: la decisión única al acabar la escucha (terminar con causa, «no pude
abrir», reconectar o terminar), las tres puertas por las que llega la causa fatal (code de Falla,
descripción del cierre, HTTP del apretón de manos) y cuál gana, la reconexión (máximo 4, espera
300·n ms, contador a cero al arrancar y en cada cierre de turno), los 3 intentos de apertura solo
por red (esperas 1 s y 2 s), el `response.create` una vez por tanda cuando no queda llamada sin
contestar, y los mensajes de conexión solo al llegar `Abierta`. Promesas desde la **218**.

Todo en `core/src/commonMain/kotlin/graph/core/voz/`, sin dependencias nuevas:

- `CanalDeVoz.kt` — el puerto del socket (`Apertura`: `Ok`, `Rechazo(http, código)`, `SinRed`; `Recibido`:
  `Mensaje` o `Cierre(código, motivo, porRed)`) y el `Reloj` inyectable.
- `ConversacionViva.kt` — la máquina de estados: abre, oye el micrófono por la compuerta y el detector,
  reacciona a cada hecho, ejecuta las herramientas por un puerto, cierra turnos y decide en un solo sitio
  si termina, dice por qué o reconecta.

Pone verdes: **218-238**. Se juzga con un canal con guion, un reloj a mano y un ejecutor retenible.

### Fase B1a — el canal real sobre OkHttp (esta corrida)

`core/src/jvmMain/kotlin/graph/core/voz/CanalOkHttp.kt`: `CanalDeVoz` sobre el WebSocket de OkHttp 4.12. `core` tiene
target jvm y Android lo consume, así que el canal se juzga en `jvmTest` sin teléfono. Sin audio, sin app y sin abrir
nunca una sesión real: eso cuesta plata y lo confirma el Capitán.

- Dependencias: `okhttp` en `jvmMain`; `mockwebserver` solo en `jvmTest`.
- Un apretón de manos con HTTP distinto de 101 es `Rechazo` con su código y `x-openai-ide-error-code`; sin respuesta
  HTTP es `SinRed` con un motivo saneado. Las redirecciones no se siguen: también son un no.
- Cada apertura tiene su propio `OkHttpClient`, y cerrar o caer apaga su despachador y su pool: no quedan hilos.

Pone verdes: **239-243**.

### Fase B1b — el equipo de audio, el arranque de prueba y el filtro de telemetría (esta corrida)

Lo puro en `core/src/commonMain/kotlin/graph/core/voz/`, lo que toca Android en `app/src/main/kotlin/com/zevcorp/graph/voice/live/`.
Sin dependencias nuevas y sin abrir nunca una sesión real: el nivel 4 lo confirma el Capitán.

- `ColaDeReproduccion.kt` — la cola del altavoz: 30 s de PCM16 a 24 kHz, descarta lo más viejo por muestras enteras y lo
  cuenta; `sonando()` es «tiene bytes».
- `TelemetriaDeVoz.kt` — lo que de una línea de los tags `voz-` puede salir del teléfono: de una transcripción, su largo.
- `MicrofonoPcm`, `AltavozPcm`, `RelojAndroid` — adaptadores delgados: `AudioRecord` con `VOICE_COMMUNICATION` y el AEC del
  sistema si lo hay; `AudioTrack` en `MODE_STREAM` alimentado desde la cola por un hilo propio.
- `VozEnVivoDev` — arma `ConversacionViva` con `CanalOkHttp`, la clave del build interno, la persona corta del teléfono y el
  catálogo del delegado vacío (las herramientas llegan en la 2B2a). Solo la arranca el panel de desarrollador.
  Tiene su propio alcance (`SupervisorJob` + `Default`): `stop()` lanza `detener()` ahí y no en el de la pantalla, porque desde
  el alcance cancelado de una Activity que se cierra `withContext` lanza antes de correr y el micrófono quedaba abierto. El
  micrófono y el altavoz se sueltan cuando `conversar()` vuelve, por la vía que sea.
- `LogBus` pasa cada línea por `TelemetriaDeVoz` antes de `Telemetry.enqueue`; `Log.d` y el panel siguen viéndolo todo.

Pone verdes: **244-246**.

### Fase B — lo que queda del cableado en `app` (otra corrida)

Las herramientas del delegado (2B2a), sacar la voz del panel de desarrollador y dónde vive la persona de la voz. Con la
corrida a mano en el teléfono como nivel 4.

Dos cuidados que el cableado hereda: `cabeceras()` devuelve la clave (`Bearer …`) en un `Map`, y `Llamada` y
`Hecho.Falla` son data classes cuyo `toString` incluye los argumentos y el mensaje. **Nunca se loguean enteros.**

### Fase 2B2a — ojos y catálogo para el delegado (esta corrida)

El delegado abría **sin herramientas** y lo decía en su propio prompt. Esta fase le da tres, todas de **solo lectura**:
dónde está, qué ve y qué podrá hacer. Ejecutar es la fase siguiente; aquí no hay manos que dar.

Lo puro en `core/src/commonMain/kotlin/graph/core/voz/`, y en `app` solo el cable:

- `OjosDeLaVoz.kt` — `donde_estoy` y `que_veo` sobre el **mismo `TurnScreenState`** que arma el turno de Graph
  (`screen`, `uiContext`, tamaño): no hay una segunda lectura de la pantalla que pueda decir otra cosa, y `screenshot`
  no se pide nunca (`mira = false`). Lo que no encaja con el formato del `uiContext` vuelve tal cual: inventar lo que
  hay en pantalla es justo lo que la persona de la voz prohíbe.
- `CatalogoDeVoz.kt` — las tres `Utensilio` que van en la delegación, y el texto de capacidades **derivado del catálogo
  real de acciones** (`Mcp.tools`, el mismo que ve el cerebro), agrupado por vía y acotado: una acción nueva aparece
  sola, sin una lista a mano que se desincronice. Ninguna de las tres actúa en la pantalla, así que son de control y
  corren en el acto (promesa 234): una lectura lenta no deja mudo al delegado.
- `HerramientasDeVoz.kt` — el ejecutor que la conversación llama. **No recibe manos**: solo el estado de pantalla y el
  catálogo. Lo que pediría ejecutar se contesta «todavía no», sin tocar nada.
- `app/…/voice/live/VozEnVivoDev.kt` — le pasa a la conversación el catálogo, el ejecutor y `actuaEnPantalla`; el estado
  sale del `Phone` que ya expone la app y el catálogo, de `Ejecucion.herramientas(…)`, que lo arma sobre la puerta
  (spec 003, promesa 307). Leer la pantalla pasa siempre por la puerta: mirar no es actuar.

Al log de la voz solo van medidas —cuántas etiquetas, cuántos caracteres—: una etiqueta es lo que la pantalla muestra, y
el log acaba en la telemetría remota (spec 005). Por eso `donde_estoy`, `que_veo` y `que_puedo_hacer` se suman a la lista
cerrada de `PuertaDeTelemetria` con su promesa, y `etiquetas` pasa a ser sustantivo de medida.

**Medido en la propia prueba (promesa 251), no supuesto:** la apertura con el catálogo ocupa **2 295 B** de los 32 768 que
el servidor admite por sesión, y declara **3 herramientas**. Declararlas **no gasta items**: la conversación empieza en 0 de
los 128, porque las herramientas viajan dentro del `session.start` y no son historial.

> La primera medida de esta fase decía **1 198 B**, y era de otra cosa: la prueba medía con dos instrucciones de juguete
> («Eres Ü.») porque las de verdad vivían en `app`, fuera del alcance del contrato. Por eso la persona se mudó a
> `core/…/voz/PersonaDeLaVoz.kt`: ahora la 251 mide lo que de verdad viaja desde el teléfono. **Una medida que no se toma
> sobre lo que viaja no es una medida**, y el margen que se creía tener era casi el doble del real. Lo que el servidor cuenta de verdad
como item solo se sabrá en el nivel 4; por eso el catálogo se agrupa en `que_puedo_hacer` en vez de declarar una
herramienta por acción, que habría metido las ~25 del catálogo real en cada apertura.

Pone verdes: **247-253**.

#### Lo que el control encontró, y cómo quedó (promesas 254-258)

**Mirar congelaba la conversación.** `GraphAccessibilityService.state()` recorre el árbol de accesibilidad con **IPC
binder síncrono**: bloquea el hilo en vez de suspenderlo. Como las herramientas de control corren dentro del hilo único
de `ConversacionViva`, mientras durara el recorrido no se procesaba el audio que llegaba ni entraba el micrófono —con
una lista larga, corte audible; con el servicio colgado, la voz muda y sin decir por qué. La regla ya estaba escrita en
`ConversacionViva.kt`; lo que faltaba era cumplirla. Ahora la lectura salta a **su propio despachador** (`mirarEn`, el
de entrada/salida, **sin default** para que ningún sitio se olvide de decirlo) y lleva tope.

> **El tope es 2 500 ms, y es un tope de CONVERSACIÓN, no de operación.** Mientras la lectura no vuelve, el delegado no
> tiene salida y la voz está callada; un silencio de más de dos segundos y medio ya se lee como que se colgó. Y queda muy
> por encima de lo que tarda un árbol normal —decenas de ms, cientos en una lista larga—, así que solo lo cruza una
> pantalla patológica o un servicio colgado, y entonces vale más decirlo que esperar. Vencido, se contesta que no se pudo
> mirar en vez de dejar muda a la voz.
>
> **No alcanzaba con `withTimeoutOrNull`:** una corrutina que BLOQUEA el hilo no se puede cancelar, así que el tope solo
> vence si lo que se espera es una suspensión. La mirada corre en su propia corrutina —y en un alcance que **no** es hijo
> del que espera, porque con `coroutineScope` habría que esperar a la que quedó bloqueada, que es justo el cuelgue del que
> se huye— y el tope va sobre el `await`.

**La 252 no podía atrapar esto, y no se puede reforzar para que lo atrape.** Juzga con un ejecutor que *suspende*
(`CompletableDeferred`), y algo que suspende suelta el hilo: la conversación sigue igual corra donde corra. Su punto
ciego es estructural, no un caso que le falte. Por eso la **254** usa un ejecutor que **bloquea el hilo de verdad**
(`CountDownLatch`), y la traba solo la abre un paso del guion, que corre en el hilo de la conversación: si la lectura
volviera a ese hilo, ese paso no llegaría nunca. El enunciado de la 252 queda igual.

**La sesión contaba items pero no bytes.** El límite del servidor tiene dos mitades —128 items **y** 32 768 B— y el
historial se llena por la que llegue antes; el recorte mide cada resultado **por separado**, así que dos que caben de a
uno se pasan juntos. Ahora se acumulan los bytes y se avisa una vez a los 30 720, sin cortar nada. El catálogo baja de
12 000 a **4 000** y se acota **en bytes** y no en caracteres, que es como cuenta el servidor («á» son dos): entra cuatro
veces en el presupuesto, y el catálogo real de hoy ocupa ~1 833.

**El catálogo de la voz omitía las aprendidas.** Pedía las herramientas con `emptyList()` mientras el otro llamador sí
las pasaba: en el teléfono la voz prometía un catálogo que no era el del cerebro. El criterio vive ahora en **un solo
sitio** (`GraphApp.aprendidasDisponibles()`) y lo usan los dos.

**Una etiqueta con salto de línea rompía el parseo.** El resumen es texto plano y une las etiquetas con « · », así que
una etiqueta multilínea partía el resumen en una sección que nadie escribió —y se contestaba «no lo veo» de algo que sí
estaba— y una que trajera el separador inflaba la cuenta. Se sanea **en origen** (`etiquetaDePantalla`) y el que la lee
es **tolerante**. El saneo no cambia el sentido de lo que ve el cerebro, que come el mismo texto: un salto pasa a espacio
y el separador a guion. El campo enfocado cierra por la **última** comilla-paréntesis, así que un texto que traiga `")`
ya no lo trunca.

**Sin servicio, cada herramienta lo contaba a su manera.** `que_puedo_hacer` callaba la causa y devolvía un catálogo
vacío, que se lee como que Ü no sabe hacer nada. Ahora `acciones` devuelve `null` —que no es una lista vacía— y las tres
dicen la misma causa con las mismas palabras.

Pone verdes: **254-258**.

---

## Diferencias deliberadas con Windows

| Qué | Windows | Android | Por qué |
|---|---|---|---|
| Argumento no texto | `JsonElement.ToString()`: `true` sale `True`, `null` sale vacío | el JSON crudo: `true`, `null`, `7`, `{"a":1}` | la herramienta recibe lo que mandó el delegado, sin un artefacto de .NET en medio |
| Bytes que ocupa un resultado | System.Text.Json escapa lo no ASCII: «á» son 6 B y un emoji 12 | kotlinx escribe UTF-8 crudo: «á» son 2 B y un emoji 4; solo escapa comillas, barra invertida y control | se cuenta lo que realmente emite el serializador de aquí; el tope sigue sobre el mensaje que viaja, que cumple las dos lecturas del servidor |
| El cambio de modo recuerda la apertura | `ProtocoloGptLive` guarda las instrucciones con que abrió para saber si «vuelve» | quien llama dice `vuelve` y pasa la persona de la voz | el traductor queda sin estado de verdad; lo que hay que recordar es de la conversación (A2) |
| El prefijo de dictar | lo compone `ConversacionEnVivo.cs:1262`, y en blanco sale sin mandar nada | lo pone `dictar()`, y en blanco devuelve lista vacía | el prefijo es parte del protocolo medido, no de quien llama |
| Default de la compuerta | `CompuertaActiva(..., sinCaminoDeEco = false)` y la variable `U_SIN_ECO` lo invierte | `sinCaminoDeEco = true` en la firma | en el teléfono no hay variables de entorno: el default de la decisión del dueño lo dice el código |
| `PaseParaVolver`, `Consumo`, `Fotograma` | existen para otros protocolos | no existen | GPT-Live nunca los produce y `mira = false`; se añaden cuando haya un protocolo que los use |
| Concurrencia de `TurnosSinMarca` | `lock` interno | sin candado | commonMain no tiene `synchronized`; `ConversacionViva` lo confina: todo método público salta a su despachador de un hilo (`limitedParallelism(1)`, promesa 236) |
| Quién escribe en el socket | un único escritor con semáforo (`ConversacionEnVivo.cs:1341-1350`) | igual: todo envío, también el micrófono y la apertura, pasa por un `Mutex` | confinar a un hilo no basta: entre dos puntos de suspensión de un envío cabe otro (promesa 236) |
| Varias tandas de llamadas | cada `Pide` corre en su propio `Task.Run`, en paralelo | las que actúan en la pantalla, un obrero por conexión en el orden en que llegaron; las de control (`parar`, `como_va`, `self_*`, según `actuaEnPantalla`) corren aparte, en el acto | dos manos sobre la pantalla del teléfono a la vez no se cruzan, y «para» no puede esperar a que acabe lo que para (promesa 234); la escucha sigue libre igual |
| Una llamada retirada | la retira el modelo al hablarle encima; ni se ejecuta ni se contesta, porque contestarla la hacía repetirse (`ConversacionEnVivo.cs:1688, 2035`) | no se ejecuta y se contesta «retirada: no se ejecutó»; como toda salida, sin pendientes pide respuesta | GPT-Live nunca emite la retirada: viene de afuera (fase 2C), el servidor no se enteró y sin la salida rechaza el siguiente `response.create` con `function_call_outputs_required` (promesa 235). Que el delegado no la vuelva a pedir se mide en la fase B |
| Una herramienta que se cancela sola o lanza un `Error` | `catch (Exception)`, que en .NET también atrapa la cancelación: se contesta «falló» (`ConversacionEnVivo.cs:2061`) | cancelación ajena o de su propia corrutina: «la herramienta se paró: …»; `Error`: «la herramienta falló: …»; la tanda sigue. Cada llamada corre en su propio `async` bajo `supervisorScope`, y la cancelación es de la voz solo si la conexión se acabó, se detuvo o se canceló la corrutina que la atiende; entonces no se contesta | en Kotlin un `withTimeout` o el freno de 3A (`Paraste`) lanzan la misma excepción que cancelar la corrutina, y un `TODO()` no es `Exception`: sin distinguirlos, el obrero moría en silencio o la voz caía. Y la que cancela su contexto cancelaba el de quien la corría: el obrero moría, o la de control quedaba sin salida y el `function_call` huérfano dejaba mudo al delegado (promesa 232) |
| Una cancelación que sale del socket | la recepción toma toda `OperationCanceledException` por el fin (`ConversacionEnVivo.cs:1375`) | con la voz viva es un corte y reconecta; solo si la corrutina de la voz está cancelada es cancelación | un adaptador que cancela su `Channel` o vence un `withTimeout` lanza `CancellationException` sin que nadie detuviera la voz (promesa 224) |
| Un 401 al abrir la primera vez | «No pude abrir la voz en vivo: {causa} (…)» dicho desde `ArrancarAsync` | pasa por la decisión única: «No sigo con la voz en vivo: {causa} («HTTP 401 …»).» | un solo sitio dice los fatales, llegue por la puerta que llegue (promesa 223) |
| Reconectar con un modo especial puesto | la apertura vuelve a las instrucciones normales | la apertura lleva el modo en curso | quien estaba enseñando sigue enseñando tras un corte |
| Lo dicho antes de un corte | la frase sigue acumulando en la conexión nueva | se descarta con el marcador | la sesión nueva no lo recuerda, y el log no debe pegarlo a lo siguiente |
| Llamadas de una conexión ya cerrada | se ejecutan y sus salidas van al socket nuevo | ni se ejecutan ni se contestan, y las que corrían se cancelan al acabar la conexión | el call_id es de una sesión que ya no existe, y una colgada no puede dejar en cola las de la conexión nueva (promesa 233) |
| Transcripción | por `Dice`, que también lleva los avisos | por `transcribe`, aparte de `dice` | en el teléfono `dice` puede acabar anunciado en voz alta |
| Reabrir tras un cambio de modo | `ReconectarAsync` manda la apertura y no le repite el modo a la voz | recuerda el modo vigente: la delegación va en el `session.start` y, confirmada la sesión, la voz recibe otra vez el append del cambio de modo; si el modo cambió después de mandar el `session.start`, al confirmar sale el cambio entero | sin él el delegado reabría en un modo y la voz en el de siempre; y con solo el append, el delegado se quedaba con la delegación del `session.start` (promesa 230) |
| Vuelco crudo de `response.event` | el mensaje entero salvo los `.delta` | solo el tipo del evento | el contenido es del delegado, y la promesa 227 dice que no se escribe |
| Pedir respuesta a lo escrito | el `response.create` lo añade quien arma `MensajesDeTexto` | `texto()` devuelve el mensaje y su `response.create` | sin él el servidor acepta y calla (medido): es protocolo, no de quien llama |
| JSON demasiado anidado | System.Text.Json corta a 64 niveles con una `JsonException` que se captura | un escáner lineal mide antes de parsear, con el mismo tope de 64 | kotlinx 1.7.1 no tiene tope: miles de niveles lanzan `StackOverflowError`, que no es `Exception` y se llevaba la voz (promesa 203) |
| Un `error` sin message | `GetRawText()` del error, entero | su texto crudo tal como llegó, recortado a 400 caracteres | acaba en el log y en lo que se dice; nunca se re-serializa |
| Barge-in por energía | apagado por defecto; `U_BARGEIN_ENERGIA=1` lo enciende (`ConversacionEnVivo.cs:1183-1196`) | `bargeInPorEnergia = false` por defecto, en la firma | allí se midió que la voz del usuario llegaba ~3× más débil que el eco y solo disparaba en falso; en el teléfono no hay variables de entorno y el default lo dice el código (promesa 229) |
| El micrófono antes de `session.started` | viaja en cuanto el socket está abierto (`ConversacionEnVivo.cs:1223, 1339`) | no viaja hasta que la sesión se confirma | mejora: el servidor aún no escucha, y lo que se mandara antes se pierde o confunde (promesa 219) |
| Avisos del sistema con llamadas pendientes | salen en el acto (`ConversacionEnVivo.cs:2009-2015`) | esperan a que no quede ninguna sin contestar, y a la confirmación | su `response.create` con una salida pendiente es lo que el servidor rechaza con `function_call_outputs_required` (promesa 226) |
| Lo escrito con llamadas pendientes | sale en el acto con su `response.create` (`ConversacionEnVivo.cs:1296-1305`) | abre la petición al escribirse y espera en la cola de los avisos, sin prefijo; sale con un único `response.create`. La cola es de la conexión: al morir, lo escrito se descarta y los avisos pasan a la siguiente | el mismo rechazo del servidor; lo escrito esperaba a una sesión que ya no existe, y un aviso sigue siendo verdad (promesa 238) |
| La apertura no sale en la primera conexión | la reconexión dice siempre «Sigo, pero olvidé lo último…» (`ConversacionEnVivo.cs:1504`) | «olvidé lo último» solo si antes se confirmó alguna sesión; si no, «Te escucho.» | no se olvida lo que nunca empezó (promesa 219) |
| `abrir` lanza algo que no es la red | — (el socket traduce a estado 0) | solo `Apertura.SinRed` se reintenta; otra excepción se dice una vez con su motivo saneado | un TLS roto o una URL mala fallan igual tres veces, y culpar al internet mandaba a buscar donde no era (promesa 218) |
| El mensaje de un error en el log | `e.Message` tal cual | de una herramienta, solo el tipo; del canal, el tipo y la primera línea sin comillas ni nada con forma de clave, recortada a 120 car.; lo que dice el servidor (un `error`, el motivo de un cierre, la decisión de fin), con el mismo saneo. Al modelo sí le llega el motivo, y al usuario lo dicho por el servidor | `LogBus` reenvía cada línea a la telemetría remota, y el mensaje de una herramienta puede traer lo que se escribió (promesa 237) |
| Base64, `call_id` o argumentos inválidos | lanza | lista vacía, id vacío o mapa vacío | mejora: un mensaje raro no se lleva el socket |
| Orden de la compuerta | `CompuertaActiva(aecDelSistema, forzada, sinCaminoDeEco)` | `ModoDeCaptura.activa(forzada, aec, sinCaminoDeEco)` | invertido: dos `Boolean` seguidos se cruzan sin error, así que todos los llamadores usan parámetros nombrados y así debe seguir |
| `seconds` de la duración | cualquier número | solo finito y no negativo | un `NaN` envenenaba el acumulado, y un negativo o un infinito no son una duración (promesa 206) |
| La cola del altavoz | `BufferedWaveProvider` de NAudio (`LiveAudio.cs:498-501`); aparte guarda el pico del volumen que sale | `ColaDeReproduccion` pura en `core`, en muestras enteras; `sonando()` son sus bytes y no hay nivel de salida | se juzga sin altavoz (promesa 244), y un byte suelto desalineaba la voz; la llave de la compuerta es el estado, nunca el volumen |
| Lo que de la voz sale a la telemetría | — | `LogBus` reenvía a Supabase; de los tags `voz-`, una transcripción sale como su largo, un JSON volcado como su tipo y el `toString` de un hecho como su nombre | una frase dicha puede ser una clave, y la tabla remota no es de este teléfono (promesa 245) |
| Salida de audio | `WaveOutEvent` | `AudioTrack` con `USAGE_MEDIA`, sin tocar el modo de audio del teléfono | con `USAGE_VOICE_COMMUNICATION` y sin `MODE_IN_COMMUNICATION` la voz puede salir por el auricular, y cambiar el modo pide `MODIFY_AUDIO_SETTINGS` en el manifiesto de todos los usuarios. Si el AEC no aguanta el eco del altavoz, lo dice el nivel 4 |

---

## Lo que NO entra, y por qué

- **Socket, micrófono, altavoz, AEC**: fase B. Esta capa se juzga sin nada de eso a propósito.
- **La persona de la voz y las instrucciones de Ü**: viven en el cliente en Windows (deuda: pasar a
  Graph). Aquí entran por parámetro; decidir dónde viven es de la fase B.
- **Cortar o compactar al llegar a los 128 items de la sesión**: nadie lo hace en Windows y no se midió
  qué cuenta el servidor como item. A2 solo cuenta lo que la conversación crea y lo avisa en el log a los
  120 (promesa 228); decidir qué hacer es de cuando se mida en el teléfono.
- **GPT Realtime y Gemini**: solo GPT-Live-1.
- **Un `call_id` gigante**: si el mensaje sin salida ya no cabe en 32 768 B (un `call_id` de 40 000 caracteres), el
  resultado sale más grande que el tope. Límite compartido con U; los `call_id` reales son de 29 caracteres
  (`call_ydaLTWADFkH6AtEXUxsfdltF`), y recortar el id rompería la llamada igual.

## Riesgo

El mayor es que GPT-Live cambie de forma y ninguna excepción lo diga: por eso `leer` nunca revienta
y todo lo que no reconoce da lista vacía. El segundo, el borde de los 2000 ms: se midió un hueco de
2009 ms, así que 1 de cada 10 tareas puede cerrar el turno 9 ms antes de que Ü hable; subirlo retrasa
todos los cierres. Se congela en 2000 como en Windows y se vuelve a medir en el teléfono en la fase B.
