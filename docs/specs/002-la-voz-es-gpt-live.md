# Plan de implementación: la voz es GPT-Live — conversación fluida por voz

Estado: **fase A1 implementada** (2026-09-14; promesas 201-217 verdes; A2 y B pendientes) · Nace de portar la voz de `U-Windows-App`,
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
| 203 | De las tres copias de una llamada solo cuenta `response.output_item.done`; sus argumentos llegan como mapa de texto y un valor no texto viaja como su JSON crudo; un JSON ilegible no revienta: en los argumentos da un mapa vacío y en el mensaje entero, ningún hecho. | A1 |
| 204 | El audio de salida vacío o hecho de ceros no suena; una pausa de pico 1 y una muestra con solo el byte alto sí suenan con el PCM exacto; el micrófono viaja en `session.input_audio.append` con su PCM exacto en base64. | A1 |
| 205 | Las transcripciones se traducen a lo que dijo el usuario y a lo que dijo Ü; `error` da Falla con su code literal (vacío si no trae), `session.closed` da Falla con su motivo y code vacío; ningún mensaje de GPT-Live produce CierraElTurno ni HablaronEncima; solo `session.started` es Abierta. | A1 |
| 206 | `session.usage.updated` da la Duración acumulada solo si trae segundos numéricos. | A1 |
| 207 | Un resultado de herramienta nunca pasa de 32 768 bytes serializados: si no cabe se recorta sin partir caracteres y dice cuánto se recortó de cuánto; si cabe, viaja entero; y GPT-Live no se declara capaz de mirar, porque una captura no cabe. | A1 |
| 208 | Cambiar de modo no reabre la sesión: manda `session.update` con la delegación entera y detrás `session.instructions.append` con el prefijo literal de cambio de modo y las reglas nuevas, o con el de vuelta y la persona de la voz cuando se regresa al modo de siempre. | A1 |
| 209 | Dictar es `session.commentary.append` con delegation_id nulo y el prefijo literal delante del texto; no abre sesión nueva ni pide `response.create`. | A1 |
| 210 | El primer trozo del usuario abre turno y el segundo no; el turno se cierra una sola vez a los 2000 ms exactos del último trozo, no a los 1999, y el audio en ceros no retrasa el cierre. | A1 |
| 211 | No se cierra el turno con llamadas en curso; la devolución de la última vuelve a contar el silencio desde ese momento; y una llamada devuelta antes de oírse no queda en curso. | A1 |
| 212 | Una pausa sin respuesta de Ü sigue siendo la misma petición; cerrar sin que Ü contestara no abre una petición nueva. | A1 |
| 213 | El audio de Ü con pico por encima de 1000 sostiene el turno abierto y el de pico 1000 o menos no; sonido sin nada dicho no abre un turno que cerrar. | A1 |
| 214 | La compuerta nace abierta; mientras Ü suena el micrófono sale como ceros del mismo tamaño, la gracia aguanta 300 ms tras vaciarse la cola y luego el trozo pasa idéntico; abrir la reabre sin esperar, y los ms tragados se cuentan. | A1 |
| 215 | Por defecto el micrófono viaja siempre sin compuerta; con AEC no actúa; forzarla la activa siempre. | A1 |
| 216 | La voz sostenida sobre la línea base dispara la interrupción; un golpe corto y el eco fuerte no disparan; tras disparar no vuelve a disparar hasta que Ü suene otra vez. | A1 |
| 217 | Sin crédito, clave inválida (incluido HTTP 401) o modelo inexistente son fatales y se dicen con su causa; cualquier otro código, prosa o vacío se puede reintentar. | A1 |

**La que cierra el asunto es la 203.** Un traductor que ejecuta la llamada tres veces, la primera
sin argumentos, hace otra cosa que lo que se pidió y no avisa. Las demás protegen el camino; la
203 es la que decide si la voz hace lo que el usuario dijo.

### Con qué se juzga cada una

Las diecisiete son **entradas y relojes a mano dentro de la propia prueba**: mensajes JSON copiados
de las capturas de U, PCM construido byte a byte y un reloj que es una variable. Ninguna toca red,
micrófono, altavoz ni Android.

| # | Cómo se juzga sin tocar nada |
|---|---|
| 201 | Se parsea el JSON emitido y se comparan campos, nunca texto crudo. Instrucciones del delegado de 24 KB con tildes, comillas, barra invertida, tabuladores y saltos de línea: llegan iguales en bytes UTF-8. La sesión no tiene `tools`. La URL no lleva la clave; la cabecera `Authorization` sí. Un delegado distinto se elige al construir |
| 202 | `texto()` da dos mensajes: el `response.item.create` con `input_text` y el `response.create`. `resultados()` de dos llamadas da dos `function_call_output` y ninguno es `response.create`. `pedirRespuesta()` es solo `{"type":"response.create"}` |
| 203 | Las tres copias reales de una llamada (capturadas por U el 2026-09-12) dan UN `Pide` con sus argumentos. Argumentos con número, booleano, objeto y null salen como `7`, `true`, `{"a":[1,2]}`, `null`. Argumentos ilegibles o que no son objeto dan mapa vacío; un mensaje ilegible, vacío o sin `type` da lista vacía |
| 204 | Delta vacío y 4800 B de ceros: ningún `Suena`. Pausa con muestras +1 y −1, muestra 256 (solo byte alto), muestra −32768 y un seno de pico 7000: `Suena` con el PCM idéntico. `audio(pcm)` lleva el base64 exacto |
| 205 | Transcripciones reales de U; `error` con code, sin code, sin message y no objeto; `session.closed` con motivo y sin él. Todo lo leído más `session.delegation.created`, `response.completed` y `session.usage.updated`: ningún cierre de turno ni «hablaron encima», y solo `session.started` es `Abierta` |
| 206 | 12.0 y luego 25.0 salen tal cual (acumulado); 7 son 7.0; `"12"` en texto, `usage` vacío o ausente no dan nada |
| 207 | Un resultado de 40 KB con tildes, emoji y comillas sale en ≤ 32 768 B y > 32 752 B, con el principio del texto y la cola con los bytes exactos. 9000 emoji: lo guardado son emoji enteros. 20 000 comillas (20 KB de texto, 40 KB serializados): se recorta aunque el texto cabría. Un texto que da un mensaje de 32 768 B exactos va entero; uno de 32 769 B, recortado |
| 208 | Al modo aprendiz: `session.update` cuya sesión solo tiene `delegation`, con instrucciones y herramientas nuevas, y detrás el append con el prefijo escrito letra a letra en la prueba. De vuelta: la delegación recupera las instrucciones completas y el append lleva el prefijo de vuelta más la persona de la voz, sin las de operar |
| 209 | `dictar(" voy por el peso ")` es un solo mensaje con `delegation_id` null y el contenido literal |
| 210 | Reloj a mano: 1000, 1999 ms del último trozo no cierran; 2000 cierra y 2100 ya no. Audio de ceros entre medias no cuenta. Recién nacido, un minuto de audio no cierra nada |
| 211 | Una llamada en curso aguanta 6000 ms; devuelta, 1999 ms no cierran y 2000 sí. Dos llamadas: hasta devolver la segunda. Una devuelta antes del `Pide` no queda en curso |
| 212 | Pausa que cierra sin respuesta de Ü: lo siguiente del usuario no abre. Tras contestar Ü, sí. La respuesta de Ü dentro del mismo turno cuenta; el saludo previo no |
| 213 | Voz de pico 1001 cada 100 ms sostiene 3000 ms tras la transcripción; pico 1000 no sostiene. Sonido de pico 7000 sin nada dicho no abre turno |
| 214 | Recién nacida deja pasar la misma instancia. Sonando: ceros del mismo tamaño. Gracia: a 299 ms tras vaciarse, ceros; a 300, el trozo idéntico. `abrir()` deja pasar a los 50 ms. Tres trozos de 100 ms tragados son 300 ms |
| 215 | `activa(forzada, aec)` sin `sinCaminoDeEco`: falso. Con AEC y sin camino de eco declarado falso: no actúa. Forzada: siempre verdadero |
| 216 | Eco 800 aprendido; voz 6000 sostenida dispara. Golpe de un trozo y ráfaga de dos no. Frase nueva de Ü a 2600 tras silencio siembra la base y no dispara. Voz 400 sobre una base de 100 no pasa el piso. Tras disparar, voz con la cola cortada no re-dispara; con Ü sonando otra vez, sí. `rms` de una onda cuadrada ±6000 es 6000 |
| 217 | Las tres causas con sus códigos (también `type.code` y `401`) nombran su palabra y ninguna otra. Reintentables: vacío, blancos, `response_input_buffer_full`, códigos de cierre, prosa en inglés que menciona 401 o créditos |

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

Pone verdes: **201-217**.

### Fase A2 — la máquina de estados de la conversación (otra corrida)

Pura también, en `core`: la decisión única al acabar la escucha (terminar con causa, «no pude
abrir», reconectar o terminar), las tres puertas por las que llega la causa fatal (code de Falla,
descripción del cierre, HTTP del apretón de manos) y cuál gana, la reconexión (máximo 4, espera
300·n ms, contador a cero al arrancar y en cada cierre de turno), los 3 intentos de apertura solo
por red (esperas 1 s y 2 s), el `response.create` una vez por tanda cuando no queda llamada sin
contestar, y los mensajes de conexión solo al llegar `Abierta`. Promesas desde la **218**.

### Fase B — el cableado en `app` (otra corrida)

El socket real (cabecera `Authorization`, cierre normal «fin»), el micrófono a 24 kHz en trozos de
100 ms, la cola del altavoz de 30 s que descarta lo viejo, el AEC del sistema, y dónde vive la
persona de la voz. Con la corrida a mano en el teléfono como nivel 4.

---

## Diferencias deliberadas con Windows

| Qué | Windows | Android | Por qué |
|---|---|---|---|
| Argumento no texto | `JsonElement.ToString()`: `true` sale `True`, `null` sale vacío | el JSON crudo: `true`, `null`, `7`, `{"a":1}` | la herramienta recibe lo que mandó el delegado, sin un artefacto de .NET en medio |
| Bytes que ocupa un resultado | System.Text.Json escapa lo no ASCII: «á» son 6 B y un emoji 12 | kotlinx escribe UTF-8 crudo: «á» son 2 B y un emoji 4; solo escapa comillas, barra invertida y control | se cuenta lo que realmente emite el serializador de aquí; el tope sigue sobre el mensaje que viaja, que cumple las dos lecturas del servidor |
| El cambio de modo recuerda la apertura | `ProtocoloGptLive` guarda las instrucciones con que abrió para saber si «vuelve» | quien llama dice `vuelve` y pasa la persona de la voz | el traductor queda sin estado de verdad; lo que hay que recordar es de la conversación (A2) |
| El prefijo de dictar | lo compone `ConversacionEnVivo.cs:1262` | lo pone `dictar()` | el prefijo es parte del protocolo medido, no de quien llama |
| Default de la compuerta | `CompuertaActiva(..., sinCaminoDeEco = false)` y la variable `U_SIN_ECO` lo invierte | `sinCaminoDeEco = true` en la firma | en el teléfono no hay variables de entorno: el default de la decisión del dueño lo dice el código |
| `PaseParaVolver`, `Consumo`, `Fotograma` | existen para otros protocolos | no existen | GPT-Live nunca los produce y `mira = false`; se añaden cuando haya un protocolo que los use |
| Concurrencia de `TurnosSinMarca` | `lock` interno | sin candado | commonMain no tiene `synchronized`; A2 lo confina a un solo hilo o corrutina |

---

## Lo que NO entra, y por qué

- **Socket, micrófono, altavoz, AEC**: fase B. Esta capa se juzga sin nada de eso a propósito.
- **La persona de la voz y las instrucciones de Ü**: viven en el cliente en Windows (deuda: pasar a
  Graph). Aquí entran por parámetro; decidir dónde viven es de la fase B.
- **Vigilar los 128 items de la sesión**: nadie lo hace en Windows y no se inventa aquí sin medir.
- **GPT Realtime y Gemini**: solo GPT-Live-1.

## Riesgo

El mayor es que GPT-Live cambie de forma y ninguna excepción lo diga: por eso `leer` nunca revienta
y todo lo que no reconoce da lista vacía. El segundo, el borde de los 2000 ms: se midió un hueco de
2009 ms, así que 1 de cada 10 tareas puede cerrar el turno 9 ms antes de que Ü hable; subirlo retrasa
todos los cierres. Se congela en 2000 como en Windows y se vuelve a medir en el teléfono en la fase B.
