# Plan de implementación: lo enseñado vive en Graph — el protocolo y el cliente de aprendizaje

Estado: **fase 4A1 implementada** (2026-09-14; promesas 401-406 verdes; cada una se vio ROJA con un
sabotaje real, abajo; Nivel 4 contra el Graph vivo pendiente: va con 4C/4D) · **fase 4A2 implementada** (promesas
407-412 verdes; cada una se vio ROJA con un sabotaje real, abajo) · **revisión de la 4A1 cerrada** (promesas 413-416
verdes y casos nuevos en 404 y 406; cada arreglo se vio ROJO con un sabotaje real, abajo) · **revisión de la 4A2 cerrada** (promesas
417-418 verdes y casos nuevos en 408 y 410-415; cada arreglo se vio ROJO con un sabotaje real, abajo) · **segunda revisión de la
4A2 cerrada** (promesas 419-420 verdes y caso nuevo en 411; cada arreglo se vio ROJO con un sabotaje real, abajo) · Nace de leer el cliente Windows (`U-Windows-App`) que ya graba, guarda y ejecuta
workflows en Graph · Rama: `yokh/aprendizaje-graph`

Hoy el Android aprende solo: `ActiveLearning`, `GeminiLearning`, `GeminiWorkflow` y `WorkflowRepo`
(en `app/…/platform/`) mandan el video y los pasos a Gemini con la key horneada y guardan los
workflows en el teléfono. Windows ya no hace eso: abre una sesión de aprendizaje en Graph, le manda
los pasos uno a uno, cierra la sesión y Graph persiste el workflow; para ejecutarlo le pide el plan.
Esta spec pone en `core` **el protocolo y el cliente** de esas rutas (4A1) y, sobre ese cliente, **el
orquestador puro de una lección** (4A2). No cablea nada en la app: el grabador por accesibilidad (4B), la
enseñanza activa (4C) y el reproductor (4D) van en fases propias, sobre estas piezas.

---

## Diagnóstico: qué se midió

Se midió leyendo el cliente Windows, no suponiendo. Graph no está en esta máquina: ninguna línea de
este cliente habló con él todavía (ver «Supuestos de Graph sin verificar»).

| Qué | Medida | Fuente |
|---|---|---|
| El contrato de aprendizaje y workflows ya está escrito | sesión, paso, nota de contexto, cierre, lista, plan, alineación; nombres de campo exactos (snake en la sesión, camel en el paso y el plan) | `U-Windows-App/windows-graph/src/Contracts.cs` |
| Las rutas de enseñanza por video tienen su propio contrato | `upload-token`, `file-state`, `process-video` (la interpretación viaja como JSON crudo), `interpret-steps` | `U-Windows-App/windows-client/src/Teach/TeachSession.cs:150-460` |
| Las cabeceras de aprendizaje no llevan `X-Miracle-Feature` | `GraphClient` pone `X-API-Key`, `X-Miracle-App`, `X-Miracle-Device-Id`, `X-Miracle-User-Email` (si hay); `/teach/*` va por `BackendClient`, que sí suma `X-Miracle-Feature: conscious_bridge` | `GraphClient.cs:64-80` · `BackendClient.cs:68` |
| Los topes no son los del turno | 90 s por llamada en `GraphClient`; 5 min en `/teach/*` | `GraphClient.cs:64` · `BackendClient.cs:45` |
| El cierre falla en flujos largos | Vercel corta el post-procesado con 504; Windows reintenta 3 veces con 3 s y 8 s y, si no, lanza `FinishPendingException`: los pasos ya están guardados, falta el resumen | `WorkflowRecorder.cs:96-165` · `GraphClient.cs:24-40` |
| Graph manda «ausente» como `""` | en C# `??` no cae con `""`: `workflow_id`, `summary` y `error` vacíos se tomaban por valores | resumen del sprint 4 («Trampas») |
| `createdAt` llega como entero Neo4j | `{low, high}`, el valor es `high·2³² + low` con `low` **sin signo**; a veces un número llano | `windows-client/src/Workflows/NombreDeWorkflow.cs:91-115` |
| La lista llega del más viejo al más nuevo | `ORDER BY w.id ASC`: lo recién enseñado quedaba al final | `SelectorDeWorkflows.cs` · promesa 110 de Windows |
| Tres de cuatro workflows no tenían nombre | «Workflow sin descripción» (relleno del grabador) y «User workflow summary:» (encabezado del LLM), medido contra el Graph vivo el 2026-09-02 | `NombreDeWorkflow.cs:1-60` · promesa 108 de Windows |
| Borrar lo que no existe es 404 | el carrusel de Windows lo trataba como error | `GraphClient.cs:187-198` |
| La alineación es best-effort pero muda | `PrependAlignmentStepAsync` hace `catch { }`: su fallo no queda en ningún lado | `GraphClient.cs:145-151` |
| Interpretar sin video es el respaldo del respaldo | `InterpretarPasosAsync` nunca lanza: devuelve `""` y lo dice en el log | `TeachSession.cs:225-270` |

**Lo que esto significa:** el cliente de aprendizaje es otro cliente tonto, como el del turno, y se
rompe igual de callado: un `""` que se toma por id, un paso con `alternativeTargets` que Graph no
entiende, un cierre que se reintenta después de haberse cobrado, un workflow que se presenta como
«Workflow sin descripción». Ninguno tira una excepción.

---

## La especificación

Bloque 401+ (la 001 usa 1-99). Los números no se reciclan. El enunciado de cada promesa es
**literal** el del test (`core/src/commonTest/kotlin/graph/core/contrato/Contrato004EnsenadoEnGraph.kt`
para 401-406, `Contrato004LeccionEnGraph.kt` para 407-412 y 417-420, y `Contrato004RespuestasDeGraph.kt` para 413-416, método `promesaNNN`); si cambia uno, cambia el
otro en el mismo commit.

| # | Promesa | Fase |
|---|---|---|
| 401 | El protocolo de aprendizaje y workflows es espejo de Windows campo por campo; un campo vacío de Graph cuenta como ausente y createdAt se lee sin signo. | 4A1 |
| 402 | Cada llamada de aprendizaje lleva X-API-Key, X-Miracle-App android_app y el id de dispositivo; el email solo si existe. | 4A1 |
| 403 | Un transitorio de aprendizaje se reintenta como en el cerebro, pero terminar una sesión se reintenta 3 veces con 3 s y 8 s y una lectura agotada nunca se reintenta. | 4A1 |
| 404 | La lista de workflows va del más nuevo al más viejo y ningún workflow se presenta con un nombre de relleno de Graph. | 4A1 |
| 405 | Borrar un workflow que ya no existe cuenta como borrado; alinear antes de un workflow es best-effort pero su error queda en el log. | 4A1 |
| 406 | Interpretar pasos nunca revienta: sin respuesta de Graph devuelve que el modelo no opinó y lo dice. | 4A1 |
| 407 | Los pasos de una demostración viajan a Graph de a uno y en el orden en que ocurrieron; un paso que falla no detiene la grabación y queda contado con su motivo. | 4A2 |
| 408 | Sin sesión abierta en Graph no se empieza a enseñar, se dice por qué y no queda nada abierto. | 4A2 |
| 409 | Al terminar, la lección se escribe en disco antes de tocar la red, entera o nada. | 4A2 |
| 410 | La nota de contexto viaja antes de cerrar la sesión; sin nota, la sesión se cierra igual. | 4A2 |
| 411 | Si cerrar la sesión no sale por un fallo transitorio, queda pendiente en disco y se reintenta al arrancar hasta que sale; una lectura agotada no deja pendiente automático. | 4A2 |
| 412 | Si procesar el video falla, la sesión se cierra igual y el video queda para reprocesar; una demostración descartada no publica nada. | 4A2 |
| 413 | Una respuesta de Graph demasiado anidada es un error manejado: nunca tumba la app, ni al leerla ni al registrarla ni al guardarla. | 4A1 · r1 |
| 414 | Un plan o un video procesado no se pierden por un campo raro: una opción sin value o label y una variable nula se leen como vacías, y un null en notas o preguntas se descarta. | 4A1 · r1 |
| 415 | Un id de workflow en blanco no llama a Graph y dice por qué. | 4A1 · r1 |
| 416 | Una respuesta a la que le falta la clave esperada es un error; una que la trae vacía es válida. | 4A1 · r1 |
| 417 | Si el lector de pasos se muere, los pasos que no viajaron cuentan como no enviados con su motivo y la lección nunca se da por entera ni se anuncia como aprendida con pasos que no llegaron. | 4A2 · r1 |
| 418 | Cancelar el cierre de una lección nunca la deja sin cerrar ni sin pendiente: o se cierra en Graph, o queda un pendiente que el arranque sabe cerrar. | 4A2 · r1 |
| 419 | Ninguna línea de log de la enseñanza lleva lo que el usuario dijo, escribió o nombró: solo ids, cantidades, estados y códigos. | 4A2 · r2 |
| 420 | Un cierre cancelado devuelve el control en un tiempo acotado, y si el proceso muere a mitad del cierre, al arrancar queda un pendiente que lo termina. | 4A2 · r2 |
| 421 | Antes de reintentar un cierre pendiente, el arranque pregunta a Graph si ya lo cerró: si lo cerró no vuelve a cerrarlo ni a cobrarlo, y si no se sabe lo intenta como siempre. | 4A2 · Graph |
| 422 | Mientras una lección se cierra, ningún arranque cierra su sesión: a Graph no le llega un paso ni una nota después de su finish. | 4A2 · Graph |
| 423 | Lo que Graph responde de verdad se lee sin romperse: el workflow con sus variables en lista y sus fechas Neo4j, el plan con su eco y su contexto de ramas, y la alineación dice en el log si ya estaba o se aprendió. | 4A1 · Graph |

**La que cierra el asunto es la 401.** Si el protocolo no es el de Windows, Graph recibe algo que
acepta con HTTP 200 y descarta en silencio (un `actionType` que no conoce, unos `alternativeTargets`
que no son lista): el workflow se guarda y no se puede ejecutar.

### Con qué se juzga cada una

Todas son **mapa a mano dentro de la propia prueba**: un `TurnTransport` falso que graba método, URL,
cuerpo, cabeceras y tope de cada llamada y devuelve respuestas guionadas; un `sleep` que anota las
esperas; un `TestTimeSource` que avanzan el guion y las esperas. Ninguna toca red, Android ni disco.

| # | Cómo se juzga sin tocar nada |
|---|---|
| 401 | Las claves de cada request son exactamente las de `Contracts.cs`/`TeachSession.cs` (sesión en snake, paso en camel, `note{role,transcript,mode}`, cierre `{}`, plan `{variables, execution_intent}`, las cuatro de `/teach/*`); un `""` del request viaja; `alternativeTargets` viaja como lista y el resto de pistas como texto. Respuestas con `""` y `null` en `workflow_id`, `summary`, `error`, `url`, `label` → ausentes (y un `error:""` con HTTP 200 no termina nada); un campo que Graph agregue no rompe; el `PlanStep` expone superficie, `readiness` (número o texto), huella, `clickPos`, `alternativeTargets` y `nodePath`; la interpretación de `process-video` llega cruda e idéntica; `createdAt` `{low negativo, high}` da el mismo instante que el número llano |
| 402 | Las nueve llamadas de aprendizaje y workflows con y sin email: `X-API-Key`, `X-Miracle-App: android_app`, `X-Miracle-Device-Id`, nunca `X-Miracle-Feature`; las cuatro de `/teach/*`, además, `X-Miracle-Feature: conscious_bridge`. Método y URL de cada una, con el id escapado en la ruta; la key no viaja ni en la URL ni en el cuerpo |
| 403 | `mandarPaso` con `503, 0, 200` → 3 llamadas, esperas `[800, 1600]`; cada transitorio solo y primero se reintenta; `429` con `Retry-After` 4 → `[4000]`; `-1` → 1 llamada y el mensaje dice que no se reintentó. `terminar` con `504×3` → 3 llamadas, esperas `[3000, 8000]` y `FinishPendiente` con el id de sesión; `504, 200` → 2; `-1` → 1 llamada, 0 esperas; `400` → 1. Una llamada colgada se corta en su tope y cuenta como lectura agotada (1 llamada). El tope que recibe el transporte: 90 s general, 5 min en `/teach/*`. Cancelar sale como `CancellationException`, tipo exacto |
| 404 | Graph lista viejo → nuevo (con `createdAt` Neo4j) → el cliente devuelve nuevo → viejo; sin fecha, por id descendente. `nombre()` de «Workflow sin descripción», «User workflow summary:», `""` y «No description» no contiene el relleno y sí la app, «2 sep 13:42» (con desfase de -5 h) y «6 pasos»; una descripción de verdad se respeta tal cual; «user workflow summary - registrar» (minúsculas, sin dos puntos) también es relleno |
| 405 | `borrar` con `404` → no lanza, 1 llamada `DELETE`; con `500` → `GraphException` tipo exacto. `prependAlignment` con `500 {"error":…}` o con un transporte que lanza → no lanza, devuelve `false` y una línea del log trae el id y el status (`HTTP 500`, `HTTP 0`), no el texto de Graph (419); con `200` → `true` y ninguna línea de fallo; cancelar sale tal cual |
| 406 | `interpretSteps` con `503×4`, `-1`, transporte que lanza, cuerpo ilegible, `{}`, `interpretation:null` e `interpretation:""`, sin key y sin pasos → `null` sin excepción, y el log dice «el modelo no opinó» con la causa medida (el status, «no respondió a tiempo», «no se pudo leer» o el tipo de lo que se lanzó, como `StackOverflowError`), nunca el texto de Graph (419); una `interpretation` de 2000 niveles y un transporte que lanza un `Error` (no una `Exception`) → `null` y «no opinó», sin volcarla en el log; con `interpretation` de verdad → el JSON crudo idéntico; cancelar sale tal cual |
| 407 | Seis pasos y Graph rechaza el tercero (`400 actionType inválido`): los seis `POST …/steps` salen en el orden observado y nunca dos en vuelo a la vez (cada llamada cede el hilo tres veces: dos lectores se cruzarían); 5 mandados y 1 fallido con su motivo, en el resultado y en la lección, y la sesión se cierra. Con 31 pasos, un solo aviso de 504, al llegar a 30 |
| 408 | `crearSesion` con `401`, `-1`, `400 {error}`, `200` sin id y `503×4`, y sin key (cero llamadas) → `NoSePuede` con la causa en una línea. Después: `pasoObservado` devuelve `false`, la nota se ignora, `terminar` lanza `IllegalStateException` (tipo exacto) y `descartar` devuelve `false`; ni una llamada más que la de abrir, ni una escritura, ni video, y ningún lector vivo (la prueba falla a los 20 s si queda uno). Tras un no, un segundo `empezar` con Graph sano enseña. Cancelado en el último instante de `empezar` —con la sesión ya abierta en Graph, cuando lee la hora—: sale cancelado, `pasoObservado` devuelve `false` y un segundo `empezar` enseña |
| 409 | En la crónica, `disco escribe lecciones/ses-1.json` va después del último paso y antes del primero de video, `context-notes` y `finish`; una sola escritura bajo `lecciones/`, que se lee entera (pasos, identidad, dónde empezó y terminó, nota) y sin motivo. Con un paso colgado que el tope de vaciado corta y sin dónde terminó: el colgado y el de detrás cuentan como no enviados, ninguno viaja después de `finish`, y el motivo dice «2 de 3» y «dónde terminó». Con el almacén cayéndose a mitad de `lecciones/`: nada bajo `lecciones/`, `leccion` nula, un aviso con la causa y la sesión cerrada |
| 410 | Dos trozos de nota → una `context-notes` a esa sesión con los dos, en el orden en que se dijeron, antes de `finish`; sin nota ni resumen del video → cero `context-notes` y un `finish`; la nota con `500` → `finish` igual y un aviso con la causa; sin voz y con resumen del video → la nota lleva el resumen, antes de `finish` |
| 411 | `finish` con `504×3` → 3 cierres, esperas `[3000, 8000]`, `PENDIENTE`, «pendiente de cerrar en Graph» y un archivo en `cierres-pendientes/` con sesión, workflow y cuándo. Al arrancar con Graph aún en 504 se queda; con Graph sano sale con un solo `finish` a esa sesión y se borra, y el arranque siguiente no llama a nadie. `finish` con `-1` → un cierre, `INCIERTO`, «pudo haberlo cerrado» y ningún pendiente. Un pendiente que al arrancar recibe `400` o `-1` se borra: no se reintenta para siempre. Al arrancar con dos pendientes y Graph en 504: dos `finish`, uno por sesión, y ninguna espera. `finish` con `401`, `403` o sin key → `PENDIENTE` y un archivo en `cierres-pendientes/`; al arrancar con `401`, `403` o sin key (cero llamadas) se conservan los tres, y con Graph sano se cierran. Con siete pendientes y Graph en 504, un arranque hace cinco `finish` y queda `(0, 7, 0)`, y el siguiente empieza por los dos que no se intentaron; si cada `finish` tarda 50 s en el reloj inyectado, el arranque hace tres |
| 412 | El video que lanza o devuelve `null` → `finish` igual, `CERRADA`, `videoParaReprocesar` y una marca en `videos-por-reprocesar/` con la sesión, la lección y el motivo; con resumen, sin marca. Descartar con un paso en vuelo, dos en cola y una nota → solo existen el `POST …/sessions` y ese paso: ni pasos, ni nota, ni `finish`, ni video, ni escrituras, y `terminar` lanza `IllegalStateException`. Descartar antes de que el lector arranque → solo el `POST …/sessions`. `descartar` devuelve `true` al descartar y al repetirlo; mientras `terminar` espera un paso en vuelo devuelve `false`, y los tres pasos, la nota, la lección y el `finish` salen igual |
| 413 | En un hilo de pila chica (512 KB), para que un `toString` a 2000 niveles reviente siempre y el runner siga: `{"interpretation":…}` con 63 niveles se lee y con 64 (65 con el cuerpo) es `GraphException` 200 que dice «64 niveles»; unos `[{` dentro de un texto o tras una comilla escapada no cuentan, y tras `"c:\\"` el texto cierra y sí cuentan. Una respuesta de 2000 niveles en las diez llamadas que leen (sesión, paso, cierre, lista, workflow, si ya se cerró, plan, `upload-token`, `file-state`, `process-video`) → `GraphException` tipo exacto con «64 niveles», sin el JSON en el mensaje ni en el log; un 500 con el `error` a 2000 niveles y un `503×4` con el cuerpo a 2000 niveles → `GraphException` con su status y «64 niveles», sin `[[[` en el mensaje ni en el log; `interpretSteps` → `null` y «no opinó … 64 niveles». La lección con el `LearningClient` real: el paso cuya respuesta viene anidada cuenta como no enviado con el porqué y el siguiente sale; el video que la trae queda para reprocesar con «64 niveles»; la lección llega a disco, la sesión se cierra y ni el log ni el disco guardan `[[[` |
| 414 | `plan` con `variables {"pais":null,"edad":7}` → `""` y `"7"`; `variables:null` → ninguna; opciones `{"value":"co","text":…}`, `"label":null`, `"value":null` y `{"label":"Chile"}` → `""` donde falta. `processVideo` con `notes:[null,{…},null]` y `questions:["…",null]` → sin los `null`, con el resto y la interpretación intactos. Al grabar, `FieldOption("", "")` viaja con `value` y `label` vacíos |
| 415 | `borrar("")`, `borrar("  ")`, `workflow("")` y `plan(" ")` con un transporte que respondería 404 → `IllegalArgumentException` tipo exacto que dice «id», «en blanco» y «graph», y cero llamadas. `{"id":17}` en la lista → `"17"`, y `borrar` va a `DELETE …/workflows/17`. `prependAlignment("")` y `("  ")` → `false`, cero llamadas y una línea del log con «id», «en blanco» y «graph» |
| 416 | `listarWorkflows` con `{}` y `{"workflows":null}`; `plan` con `execution_plan` sin `steps`, `{}`, `steps:null` y sin `execution_plan` → `GraphException` 200 que nombra la clave que falta. `{"workflows":[]}` → lista vacía; `{"execution_plan":{"workflowId":"wf-1","steps":[]}}` → plan de 0 pasos |
| 417 | El lector vive en un scope que la prueba cancela. Con un paso mandado, otro en vuelo y dos en cola: después `pasoObservado` devuelve `false`, los tres cuentan como no enviados con «no llegó a enviarse» y «lector», solo viajaron dos pasos, la lección dice «3 de 4» y el mensaje no dice «aprendí» sino «incompleto». Un paso que la cola le entregó al lector justo antes de cancelarlo (un `yield` lo deja esperando en la cola vacía) → cuenta como no enviado. Muerto sin nada pendiente → la lección nombra al lector y el mensaje no dice «aprendí». Con el scope ya cancelado → `NoSePuede` que dice «cancelado», cero llamadas y nada abierto; cancelado mientras Graph abre → `NoSePuede` y nada abierto |
| 418 | `terminar` en su propia corrutina, que la prueba cancela en un punto exacto. Cancelado durante el video → la corrutina sale cancelada, una `context-notes` antes de un `finish`, la lección en disco, una marca de video con «cancel», un segundo `terminar` lanza `IllegalStateException` con «terminada» y el log trae «■ aprendí». Cancelado durante el `finish` con Graph en 504 → los 3 intentos con `[3000, 8000]`, un pendiente, y el arranque siguiente lo cierra. Cancelado vaciando la cola con un paso colgado → un `finish` y ningún paso después, el video ni se llama y queda marcado, y en disco `[true, false, false]` con «se canceló» |
| 419 | Seis demostraciones con el cliente REAL y un solo log para el cliente y la lección, como `LogBus` en la app: la descripción «Registrar a Ana Pérez CC 1037», pasos con `label`, `value`, `selectedValue`, `selectedLabel` y `semanticTarget` sensibles, una nota y un video con resumen e interpretación sensibles. Cierran entera, con ecos, pendiente, fallida, incierta y con el lector muerto por una cancelación que nombra al paciente. Con ecos, Graph rechaza un paso repitiendo su valor, responde a otro algo ilegible que lo trae, devuelve la nota en el error y manda un video procesado que no se lee; el cierre pendiente y el fallido repiten el nombre. Además: un arranque al que Graph responde `400` con el nombre, `interpretSteps` con un `500` y con algo ilegible que traen lo dicho, `uploadToken` con un `archiveError` que nombra al paciente y `prependAlignment` con un `500` que nombra el workflow. Ningún fragmento sensible aparece en ninguna línea; el mensaje para el usuario sí lleva el nombre; cada cierre deja su línea «■» con su sesión, y el paso rechazado, su `HTTP 400` |
| 420 | Con Graph sano: `disco escribe cierres-pendientes/ses-1.json` antes del primer `finish` y `disco borra` después, sin pendiente al final y sin pedir tope. El proceso muere procesando el video y con `finish` en vuelo: con el disco de ese instante, el arranque hace un `finish` a `ses-1` y lo borra. Cancelado en el video y con `finish` en vuelo, con Graph colgado: el cierre le pide `TOPE_DE_CIERRE_CANCELADO` (2 min) a una espera inyectada y no suelta antes; al vencer, sale cancelado, con un `finish` y el pendiente en disco, y el arranque lo cierra. Muere tras un `finish` que salió, justo al borrar el provisional, contra un Graph con la memoria de `e9d0d44` (`GraphDeVerdad`), donde un `finish` repetido responde 200 y cobra otra vez: el arranque no hace ningún `finish`, el LLM cobró una sola vez, queda `(1, 0, 0)` y el siguiente no llama |
| 421 | Un pendiente de una sesión que `GraphDeVerdad` ya cerró → una sola llamada, `GET /api/v1/workflows/ses-1`, ningún `finish`, un cobro, `(1, 0, 0)`, el pendiente borrado y una línea del log con la sesión y «ya estaba cerrada». Con `completedAt` y un `status` que no es `done` → tampoco hay `finish`. Con la sesión en `recording` → el GET y un `finish`, un cobro. Con el GET en `503`, `-1` o `404` → un GET, ninguna espera y el `finish` de siempre: `(1, 0, 0)` si sale, `(0, 0, 1)` con `404` |
| 422 | Una lección con un paso y una nota se queda procesando el video con su provisional en disco, y otro `Leccion` sobre el mismo almacén corre `reintentarPendientes`: cero llamadas y `(0, 1, 0)`. Al soltar el video la lección cierra: en lo que le llegó a `GraphDeVerdad`, ningún paso ni nota de `ses-1` después de su `finish`, un solo cobro y ningún pendiente. Terminada otra lección como `PENDIENTE` (504), un arranque del mismo proceso sí la cierra |
| 423 | Con cuerpos que copian la forma de `e9d0d44`: la lista y un workflow con `variables` en lista de objetos, fechas `{low, high}` y ramas se leen (ids, `createdAt`, `totalSteps`, `status`, las variables siguen en lista); el cierre trae el workflow entero. El plan con eco de `variables` y `executionIntent`, `runtimeIntelligence`, `branchContext` objeto y `null`, la alineación en orden 0 (`app:com.x`) y `key` sin selector → tres pasos en su orden; `404 not found or has no steps` y `500 has no executable steps` → `GraphException` con su status y 4 llamadas en total. La alineación con `already_present` y con `learned` → `true` y una línea del log con el id y cuál de las dos; `404` y `400` → `false` con su status; ni el workflow ni el texto de Graph en el log |

Las 407-412 viven en `Contrato004LeccionEnGraph.kt` y juzgan la lección con el `LearningClient` **real**
encima de un transporte que responde por ruta y cuenta cuántas llamadas hay en vuelo, un `Almacen` en
memoria que se cae a pedido, y una crónica donde la red, el disco y el video anotan lo que hacen en el orden
en que lo hacen: quién fue primero se juzga por posición, no por reloj. El reloj de pared de la lección es
un número fijo y las esperas del cliente se anotan; el único tiempo real es el tope de vaciado de la 409,
recortado a 100 ms, como la llamada colgada de la 403.

Las 413-416 viven en `Contrato004RespuestasDeGraph.kt` y reusan los dos mapas: el transporte guionado de la 401 y, para la
lección de la 413, el transporte por rutas, la crónica y el almacén en memoria de la 407. La 413 corre entera en un hilo
de 512 KB de pila (`correConPilaChica`, en `Corre.kt`): sin guarda, lo que desborda desborda siempre, y el
`StackOverflowError` sale como un AssertionError que lo dice en vez de llevarse el runner.

Las 417-420 viven con las 407-412 y usan el mismo mapa. Ninguna duerme ni depende de hilos: la cancelación llega en un punto
exacto con un `CompletableDeferred` que suelta el transporte o el video, con `CoroutineStart.UNDISPATCHED` para que `terminar`
ya esté esperando la cola, con un `yield` en el hilo único de `runBlocking` o con el reloj de pared de la lección, que cancela
al leerse.

### Verificación (2026-09-14)

El contrato nació rojo contra un esqueleto con `TODO()` (401-406 `⧗ PENDIENTE`, 1-14 intactas) y la
implementación lo dejó en `CONTRATO INTACTO: 20 promesas.` Después, un sabotaje por promesa sobre el
código real, revertido con copia y sha256; cada uno rompió **solo** su promesa:

| # | Sabotaje | Lo que dijo el juez |
|---|---|---|
| 401 | `createdAt` con `low` con signo: `(high shl 32) or low` | `createdAt {low negativo, high} expected:<1789054200000> but was:<-1947162432>` |
| 402 | `X-Miracle-Feature` en todas las llamadas | `POST …/learning/sessions · X-Miracle-Feature solo en /teach/* expected:<null> but was:<conscious_bridge>` |
| 403 | el cierre trata la lectura agotada como transitorio | `-1` en `terminar` → «no lanzó»: se reintentó y el segundo intento cerró |
| 404 | sin ordenar la lista | `expected:<[wf_nuevo, wf_medio, wf_viejo]> but was:<[wf_viejo, wf_medio, wf_nuevo]>` |
| 405 | `borrar` sin aceptar el 404 | `GraphException: Workflow not found (HTTP 404 en DELETE /api/v1/workflows/wf_ido)` |
| 406 | `interpretSteps` relanza lo que falla | `GraphException: graph no respondió (HTTP 503) en POST /api/v1/teach/interpret-steps tras 4 intentos` |

`./gradlew :app:compileReleaseKotlin -q` → exit 0 (con `GraphTransport.send`).

### Verificación de la 4A2 (2026-09-14)

El contrato nació rojo contra un esqueleto con `TODO()` (407-412 `⧗ PENDIENTE`, 1-15 y 401-406 intactas:
`CONTRATO ROTO: 6 promesa(s) incumplida(s)`) y la implementación lo dejó en `CONTRATO INTACTO: 27 promesas.`
Después, un sabotaje por promesa sobre `Leccion.kt` sin commitear, revertido con copia y sha256:

| # | Sabotaje | Lo que dijo el juez |
|---|---|---|
| 407 | un paso que falla corta el lector | `el rechazo no cortó los de después expected:<[…/b1 … …/b6]> but was:<[…/b1, …/b2, …/b3]>`; solo la 407 |
| 407 | cada paso en su propia corrutina | `dos llamadas viajaron a la vez expected:<1> but was:<7>`; rompe también la 409 y la 412: sin un lector único, ni la lección ni el descarte esperan a los pasos |
| 408 | el fallo de `crearSesion` se traga y se enseña con una sesión inventada | `la key no vale: empezó a enseñar sin sesión` (`Ensenando` donde tocaba `NoSePuede`) |
| 409 | la lección se escribe después del video y la nota | `la lección se escribió después de tocar la red del cierre: […, video, red …/context-notes, disco escribe lecciones/ses-1.json, red …/finish]` |
| 410 | el cierre antes de la nota | `la nota viajó después de cerrar la sesión: [… …/steps, …/finish, …/context-notes]` |
| 411 | una lectura agotada al cerrar deja pendiente | `la lectura agotada dejó un pendiente automático: [… cierres-pendientes/ses-2.json]` |
| 411 | el `FinishPendiente` no se escribe | `el cierre que no salió no quedó en disco … expected:<1> but was:<0>` |
| 412 | `descartar` llama a `terminar` | `descartada, siguió publicando … but was:<[…/sessions, …/steps, …/finish]>` |

`./gradlew :app:compileReleaseKotlin -q --rerun` → exit 0. `app/` no se toca en esta fase.

### Verificación de la revisión de la 4A1 (2026-09-14)

Las pruebas se escribieron primero, contra el código de `c55a5df`: `CONTRATO ROTO: 5 promesa(s) incumplida(s)`. La 406
reventó con `java.lang.StackOverflowError` (la interpretación de 2000 niveles, pasada a texto en el log); la 413 dijo
«65 niveles se leyeron · no lanzó»; la 414, `no se pudo leer en $.execution_plan.variables['pais']`; la 415, «borrar · no
lanzó»; la 416, «lista sin workflows: se aceptó como vacío · no lanzó». La 404 siguió verde: su caso nuevo caza un
sabotaje, no un defecto. La implementación lo dejó en `CONTRATO INTACTO: 31 promesas.` Después, un sabotaje por
arreglo sobre el código sin commitear, revertido con copia y sha256:

| # | Sabotaje | Lo que dijo el juez |
|---|---|---|
| 413 | sin guarda de profundidad (ni al leer ni al buscar el `error`) y los logs de `processVideo` e `interpretSteps` pasando la interpretación a texto | `65 niveles se leyeron · no lanzó`; solo la 413: el `StackOverflowError` del log lo atrapa `interpretSteps` y la 406 sigue verde |
| 413 | sin guarda al leer, con los logs en bytes | rompe la 413 y la 406: la interpretación de 2000 niveles vuelve como si fuera buena |
| 413 | con la guarda, el log de `interpretSteps` con `toString` | intacto: la guarda es lo que protege al log |
| 414 | `FieldOption.value` sin default | `no se pudo leer en $.execution_plan.steps[0].allowedOptions[2].value` |
| 415 | `borrar` sin chequear el id | `borrar · no lanzó` |
| 416 | la lista sin `workflows` se toma por vacía | `lista sin workflows: se aceptó como vacío · no lanzó` |
| 404 | `ignoreCase = false` en `esRelleno` | `«user workflow summary - registrar» pasó por nombre` |
| 406 | `catch (e: Exception)` en `interpretSteps` | `java.lang.StackOverflowError: la pila se agotó en el transporte` |

`./gradlew :app:compileReleaseKotlin -q --rerun` → exit 0. `app/` no se toca.

### Verificación de la revisión de la 4A2 (2026-09-15)

Las pruebas se escribieron primero (`ded895e`), contra el código de `3f370e1` con una sola línea nueva: la firma de `descartar`,
que devuelve `Boolean` con el defecto intacto. El juez: `CONTRATO ROTO: 7 promesa(s) incumplida(s)`. La 408 dijo «la key no
vale: dijo que descartó una enseñanza que no empezó»; la 411, «al arrancar, un pendiente se intentó más de una vez» (seis
`finish` donde iban dos); la 412, «descartar durante el cierre dijo que descartó»; la 413, «un 500 anidado de más: graph HTTP
500 en DELETE …: {"error":"neo4j caído","traza":[[[[…»; la 415, «alinear «» dijo que alineó»; la 417, «con el lector muerto,
aceptó un paso que no va a ningún lado»; la 418, «cancelado en el video, la nota no viajó … expected:<1> but was:<0>». La 410
siguió verde: su caso nuevo caza un sabotaje, no un defecto. La implementación lo dejó en `CONTRATO INTACTO: 33 promesas.`
Después, 19 sabotajes sobre el código sin commitear, revertidos con copia y sha256; cada uno rompió **solo** su promesa:

| # | Sabotaje | Lo que dijo el juez |
|---|---|---|
| 417 | `pasoObservado` acepta con el lector muerto | `con el lector muerto, aceptó un paso que no va a ningún lado` |
| 417 | tras el `join` solo se corta por tope o por cancelación: `join()` vuelve a ser «cola vacía» | `los pasos que no viajaron no cuentan como no enviados expected:<[true, false, false, false]> but was:<[true]>` |
| 417 | la lección no nombra al lector muerto | `la lección no dice que el lector se detuvo: «no se observó ningún paso»` |
| 417 | el mensaje dice «aprendí» aunque falten pasos | `se anunció como aprendida con pasos que no llegaron: aprendí «Registrar paciente» (1 paso), SIN comprobar` |
| 417 | la cola sin `onUndeliveredElement` | `el paso que la cola entregó al lector cancelado se perdió expected:<[(b1, false)]> but was:<[]>` |
| 417 | `empezar` sin mirar el scope antes de llamar a Graph | `con el scope cancelado, abrió una sesión en Graph: [POST /api/v1/learning/sessions]` |
| 417 | `empezar` sin mirar el scope después de abrir en Graph | `el scope se canceló mientras Graph abría y empezó a enseñar` (`Ensenando` donde tocaba `NoSePuede`) |
| 418 | `sinCancelar` sin `NonCancellable` | `el cierre no dejó su resultado en el log: [… cierre pendiente guardado (sesión ses-1) …]`: la nota y el `finish` se intentaron y la cancelación los cortó |
| 418 | con el cierre ya cancelado, el video se procesa igual | `cancelado antes del video, lo empezó igual` |
| 411 | el arranque usa los tres intentos del cierre | `al arrancar, un pendiente se intentó más de una vez expected:<[…/ses-a/finish, …/ses-b/finish]> but was:<[…/ses-a/finish, …/ses-a/finish, …` |
| 411 | 401/403 no dejan pendiente, al cerrar ni al arrancar | `HTTP 401 al cerrar: Graph no cerró la sesión (la key de graph no vale (HTTP 401)): 0 pasos mandados; SIN comprobar expected:<PENDIENTE> but was:<FALLIDO>` |
| 411 | sin key es fallido | `sin key al cerrar: Graph no cerró la sesión (no hay key de graph: …) … expected:<PENDIENTE> but was:<FALLIDO>` |
| 411 | hueco S2: al arrancar, 401/403 se descartan (solo el arranque) | `HTTP 401 al arrancar: cerrados, siguen y descartados expected:<(0, 3, 0)> but was:<(0, 0, 3)>` |
| 410 | hueco S1: los trozos de la nota se anteponen | `los trozos de la nota viajaron fuera de orden: «siempre en Colombia es para pacientes nuevos»` |
| 412 | hueco S6: `descartar` corta también durante el cierre | `descartar durante el cierre dijo que descartó` |
| 413 | un no-2xx anidado de más vuelca el cuerpo | `un 500 anidado de más: graph HTTP 500 en DELETE /api/v1/workflows/wf-1: {"error":"neo4j caído","traza":[[[[…` |
| 413 | un transitorio anidado de más vuelca el cuerpo | `un 503 anidado de más: graph no respondió (HTTP 503) en POST /api/v1/learning/sessions/ses-1/steps tras 4 intentos: [[[[…` |
| 415 | `prependAlignment` sin `conId` | `alinear «» dijo que alineó` |
| 408 | `empezar` abre aunque lo hayan cancelado (sin `ensureActive`) | `cancelado al abrir: quedó enseñando y aceptó un paso` |

Sin sabotaje que el juez vea: que la vuelta a NUEVA vaya bajo candado, porque necesita otro hilo (ver «Lo que no cubre el
contrato»), y el `finally` de `terminar`, que con `NonCancellable` solo se ejerce si escapa un `Error`.

`./gradlew :app:compileReleaseKotlin -q --rerun` → exit 0. `app/` no se toca.

---

## Las fases

### Fase 4A1 — protocolo y cliente en `core` (hecha)

Todo en `core/src/commonMain/kotlin/graph/core/graph/`, sin dependencias nuevas:

- `TurnTransport.kt` — `send(method, url, body, headers, timeout)` con implementación por defecto
  (POST va a `post`): los transportes de la 001 no cambian.
- `Reintentos.kt` — el bucle de reintentos del turno, sacado de `GraphBrain` sin cambiar lo que hace,
  para que «como en el cerebro» sea el mismo código y no una copia que se desincroniza.
- `GraphHeaders.kt` — `feature` opcional (por defecto la del cerebro, promesa 8 intacta).
- `learning/Protocol.kt` — espejo `@Serializable` de `Contracts.cs` y de los contratos de
  `TeachSession.cs`; `vacioEsAusente`; `WorkflowResumen` (espejo de `WorkflowSummary.FromJson`).
- `learning/LearningClient.kt` — las trece llamadas, con topes, reintentos y errores.
- `learning/NombreDeWorkflow.kt` — espejo de `NombreDeWorkflow.cs`.
- `learning/Profundidad.kt` — la guarda de 64 niveles antes de parsear (de la revisión, abajo).

Y `app/…/platform/GraphTransport.kt` implementa `send` con `HttpURLConnection` (GET, POST, PUT sin
cuerpo binario, DELETE; tope de lectura por llamada).

Pone verdes: **401-406**.

### Fase 4A2 — la lección, el orquestador puro (hecha)

En `core/src/commonMain/kotlin/graph/core/graph/learning/`, sin dependencias nuevas, sin Android, sin
MediaProjection y sin red real:

- `Almacen.kt` — el puerto de disco: `escribirEntero` (todo o nada), `leer`, `listar`, `borrar`. La app lo
  implementa en 4C (temporal + renombrar).
- `Leccion.kt` — una enseñanza. `empezar` abre la sesión (sin sesión no se enseña); `pasoObservado` y `nota`
  solo encolan, y **un solo lector** manda en serie; `terminar` va en este orden: vaciar la cola (tope 30 s)
  → la lección a disco → el video (una función que devuelve su resumen o `null`) → la nota → el cierre → el
  resultado «SIN comprobar»; `descartar` no publica nada; `reintentarPendientes` es para el arranque.

Lo que deja en el almacén, un archivo por sesión (el id de sesión, escapado, es el nombre):

| Ruta | Qué | Cuándo |
|---|---|---|
| `lecciones/<sesión>.json` | sesión, workflow, descripción, identidad, dónde empezó y terminó, cuándo, cada paso con su resultado, la nota y el motivo si falta algo | al terminar, antes de la red |
| `cierres-pendientes/<sesión>.json` | `sessionId`, `workflowId`, `cuandoMs`, `intentos` (cuántas veces lo intentó un arranque) | provisional, junto con la lección y antes de la red (420). Se borra si `finish` sale, si Graph no respondió a tiempo o si dijo que no; se queda si no salió tras sus tres intentos, por la key (401/403, sin key) o porque un cierre cancelado agotó su tope |
| `videos-por-reprocesar/<sesión>.json` | `sessionId`, `leccion`, `motivo`, `cuandoMs` | el video lanzó, no dejó nada o se canceló el cierre |

Pone verdes: **407-412**.

**Abierto, del Capitán.** Descartar no llama a Graph, igual que Windows. Si Graph guarda los pasos al
llegar —`PendingFinish.cs` dice que sí—, una demostración descartada deja en `GET /workflows` un workflow
sin resumen con los pasos que alcanzaron a viajar. Borrarlo (`DELETE /workflows/{id}`) es borrar datos y
cambia lo que el usuario ve en la lista: no se decidió aquí. Se mide en el Nivel 4 de 4C.

### Revisión de la 4A1 — lo que Graph manda raro (hecha)

Un revisor independiente aprobó `d13ab95` con cambios. Lo que encontró, y cómo quedó:

- **JSON anidado de más (413).** kotlinx-serialization 1.7.1 lee miles de niveles, pero pasarlos a texto desborda la
  pila desde ~1000 (`toString`) y compararlos desde ~5000 (`equals`), medido con 1 MB de pila. Es un
  `StackOverflowError`: ningún `catch (e: Exception)` lo atrapa y en Android tumba el proceso. Bastaba un
  `{"interpretation":[[[…]]]}` de ~2,4 KB en `interpret-steps`, cuyo log hacía `toString().length`. Ahora
  `learning/Profundidad.kt` cuenta los niveles en una pasada lineal que respeta textos y escapes **antes de parsear**
  cualquier respuesta, con el tope de System.Text.Json en U: 64 se leen y 65 son `GraphException`. Ningún `JsonElement`
  que salga de `LearningClient` pasa de 64 niveles, y ningún log vuelca JSON de Graph: dice bytes. `interpretSteps`
  atrapa además `Throwable`, salvo la cancelación.
- **Campos raros (414).** `FieldOption.value` y `label` con `""` por defecto y `@EncodeDefault`: al grabar viajan siempre,
  como en Windows. Los valores de `variables` toleran `null`, y un `null` dentro de `notes` o `questions` se descarta.
- **Id en blanco (415).** `borrar`, `workflow` y `plan` lo rechazan antes de la red; un `id` numérico se lee como texto.
- **Clave que falta (416).** Una lista sin `workflows` o un plan sin `steps` es un fallo; vacías, son válidas.
- **Hueco del contrato (404).** Con `ignoreCase = false` seguía verde; ahora juzga un relleno en minúsculas y sin dos puntos.

**`Leccion.kt` y la profundidad.** No escribe ni compara JSON que venga de Graph: a disco van textos y los `StepRequest`
que arma la superficie (sus `surfaceHints` salen de `SurfaceHint.build`, dos niveles), y la interpretación del video
pasa por `ResumenDeVideo` y `ResultadoDeLeccion` sin serializarse. El riesgo estaba en la entrada, no en la lección: con
la guarda en `LearningClient`, un paso cuya respuesta viene anidada de más cuenta como no enviado con su motivo, y un
video que la trae queda para reprocesar. Lo que 4C guarde de `processVideo` ya llega acotado; un `JsonElement` que no
pase por `LearningClient` no lo cubre esta guarda.

**La guarda se unifica al integrar las ramas.** La rama de voz (`yokh/voz-gpt-live`) tiene la misma cuenta en
`core/…/voz/JsonCrudo.kt` (`demasiadoAnidado`, `PROFUNDIDAD_MAXIMA`; spec 002, promesa 203). La de aquí lleva los mismos
nombres, la misma firma y el mismo tope a propósito: al integrar queda una sola, en un paquete común a las dos, y ambas
specs apuntan a ella. Hasta entonces, un arreglo en una se copia en la otra.

### Revisión de la 4A2 — la lección bajo cancelación (hecha)

Un revisor independiente aprobó la 4A2 con cambios. Lo que encontró, y cómo quedó:

- **El lector se muere (417).** El lector vive en el `scope` que le pasan, que no es de la lección. Si ese scope se cancelaba,
  `join()` volvía enseguida y la lección lo tomaba por «cola vacía»: lo encolado desaparecía, `pasoObservado` seguía diciendo
  `true` y `terminar` anunciaba «aprendí (1 paso)» con la lección «entera» en disco. Ahora `pasoObservado` devuelve `false`
  con el lector muerto; al cerrar, el paso en vuelo, el que la cola ya le había entregado (`onUndeliveredElement`) y lo que
  queda en la cola cuentan como no enviados con su motivo; la lección dice que el lector se detuvo y el mensaje dice
  «incompleto», no «aprendí». `empezar` con el scope cancelado, antes o mientras Graph abre, es `NoSePuede`.
- **Cancelar el cierre (418).** Cancelar `terminar` durante el video dejaba la lección en CERRANDO, sin nota, sin `finish` y
  sin pendiente, y un segundo `terminar` lanzaba. Ahora lo que espera se puede cancelar y lo que escribe o publica corre bajo
  `NonCancellable` (ver «Diferencias»); un `finally` deja la lección TERMINADA pase lo que pase. La trampa:
  `withContext(NonCancellable)` descarta su resultado y lanza al volver si la corrida ya estaba cancelada, así que lo que
  produce se deja en variables y no como valor de retorno.
- **Un intento por pendiente al arrancar (411).** Cada arranque hacía 3 `finish` por pendiente, con 3 s y 8 s y un
  post-procesado de LLM cada uno, para siempre. `LearningClient.terminar` recibe `intentos` (3 por defecto) y el arranque
  usa 1, como `PendingFinish.cs:69`.
- **La key al cerrar (411).** Sin key, o con 401/403, el cierre era `FALLIDO` y no dejaba pendiente, mientras que al arrancar
  esas mismas causas se conservaban. Ahora decide un solo criterio, `trasFallo`, al cerrar y al arrancar: quedan pendientes.
- **Lo demás.** `prependAlignment` pasa por `conId` (415); un error no-2xx anidado de más dice sus bytes en vez de volcar
  `[[[…` en el motivo, el disco y el log (413); `descartar` devuelve `Boolean` y durante el cierre no corta nada (412);
  `empezar` cancelado vuelve a NUEVA bajo candado, también si lo cancelan tras abrir en Graph (408).

**Lo que no cubre el contrato.** Dos caminos del arreglo de `empezar` necesitan otro hilo y no se producen en el hilo único
del contrato: la carrera entre la vuelta a NUEVA y un `descartar` concurrente (por eso va bajo candado), y la cancelación
mientras se espera el segundo candado, porque ningún dueño del candado lo retiene tras suspender. El caso de la 408 cancela en
el último instante antes de abrir, que es el vecino observable. Que muera el proceso a mitad del cierre lo cubre ahora la 420 (abajo).

### Segunda revisión de la 4A2 — el log, el cierre que muere y el arranque (esta corrida)

Un control independiente del diff `3f370e1..00b6d7b` encontró cuatro cosas. Cómo quedaron:

- **Lo dicho iba al log, y el log sale del teléfono (419).** En la app, el log de la lección y del cliente es `LogBus`, que
  manda cada línea a `Telemetry.enqueue` (`app/…/platform/LogBus.kt:25`). La línea «■» llevaba la descripción («aprendí
  «Registrar a Ana Pérez CC 1037»») y los avisos enteros —el motivo del video, el error de la nota—; el paso que no llegaba
  llevaba el mensaje de Graph, que en un error de validación repite el valor, o 200 caracteres de una respuesta ilegible; «▶»
  llevaba la url de la pantalla; `interpretSteps` y `prependAlignment`, el mensaje de lo que falló. El criterio es el que el
  Capitán fijó para la voz: a remoto llega la medida, nunca el contenido. Ahora `GraphException` trae su `medida` —status, ruta
  con sus ids, intentos, bytes—, al log va `medidaDe(e)` (la medida, o el tipo de lo que no es de Graph), y la lección compone
  dos veces lo que dice: para el usuario y el disco, con el nombre y las causas; para el log, con el id del workflow, la sesión y
  las medidas. El texto entero sigue en el resultado (`mensaje`, `avisos`, el `motivo` de cada paso) y en disco. Las 405 y 406
  juzgan ahora la causa medida.
- **El cierre cancelado colgaba a quien llamaba, y un proceso muerto no dejaba pendiente (420).** Cancelado, el cierre corría
  bajo `NonCancellable` sin tope: una nota y tres `finish` de 90 s con sus esperas, unos 4,7 min con el `viewModelScope`
  esperando. Y el pendiente se escribía cuando `finish` ya no había salido: si el proceso moría antes, no quedaba nada que el
  arranque supiera cerrar. Ahora el cierre provisional va a disco **con la lección, antes de la red**, y no solo antes de
  `finish`: el video tarda minutos y es donde más probable es que Android mate el proceso. Si `finish` sale, se borra; si Graph
  no respondió a tiempo o dijo que no, también; si queda pendiente, ya está. Y desde que llega la cancelación, la red del cierre
  tiene `TOPE_DE_CIERRE_CANCELADO` (2 min): un hijo de la corrida de quien llama se entera de la cancelación y arranca el
  cronómetro, que al vencer corta la red; queda el pendiente y la cancelación sale. Cortar a tiempo depende de que el transporte
  sea cancelable, y `GraphTransport` lo es (`disconnect()` al cancelar). El supuesto: si el proceso muere después de un `finish`
  que salió y antes de borrar el provisional, el arranque reintenta una sesión que Graph ya cerró; con 404 o 400, `trasFallo` lo
  juzga FALLIDO y el pendiente se borra tras ese único `finish` (juzgado). Si Graph respondiera 2xx y post-procesara otra vez,
  cobraría dos veces (ver «Supuestos»).
- **El arranque no tenía tope (411).** Con Graph caído, N pendientes × 90 s. Ahora intenta hasta `MAX_PENDIENTES_POR_ARRANQUE`
  (5) y ninguno que empiece pasados `TOPE_DE_ARRANQUE` (2 min), medidos con un reloj inyectable. El tope se mira antes de cada
  intento: el que ya salió termina en su propio tope de 90 s, porque cortarlo a mitad dejaría a Graph cerrando sin que nadie lo
  sepa, así que un arranque dura como mucho 2 min más un intento. El resto queda para el siguiente, que empieza por los que
  menos veces se intentaron (`intentos` en el pendiente): con un orden fijo, cinco pendientes que nunca salen —un flujo largo que
  siempre da 504— taparían al sexto para siempre. 4C lo llama en segundo plano, sin bloquear la UI.
- **`Leido.sinEntregar` se escribía desde dos hilos (sin juez).** `descartar` cancelaba el lector y enseguida la cola: la
  cancelación del lector puede disparar `onUndeliveredElement` en su hilo mientras `cola.cancel()` lo dispara en el de quien
  descarta, sobre una lista sin candado. Ahora `descartar` hace `lector.cancelAndJoin()` antes de `cola.cancel()`, y todas las
  escrituras quedan en serie; `trySend` sobre la cola cerrada o cancelada no llama a `onUndeliveredElement` (medido en
  kotlinx-coroutines 1.8.1). **No hay juez multihilo**: una sonda sobre el código con el defecto —300 rondas de `descartar` y 300
  de `terminar` con un tope de vaciado de 1 ms, el lector en `Dispatchers.Default` y un hilo de superficie encolando 3000 pasos—
  no detectó ni una carrera. El único par concurrente eran una escritura del lector contra las de `cola.cancel()`, y esa lista
  nadie la lee después de descartar: lo único observable sería una excepción dentro de `ArrayList.add`, y un test que la espere
  daría verde por suerte.

**Lo que no cubre el contrato, además.** Si el proceso muere procesando el video, el arranque cierra la sesión sin la nota ni
el resumen, y el video no queda marcado para reprocesar: la marca se escribe cuando el video falla, no antes. Y el tope del
cierre cancelado se juzga con una espera inyectada, no con el reloj: cortar una llamada colgada exige un tiempo que despierte a
la corrutina, y el contrato no trae `kotlinx-coroutines-test`.

### Verificación de la segunda revisión de la 4A2 (2026-09-15)

Las pruebas se escribieron primero (`2b1a8eb`), contra el código de `00b6d7b` con una sola novedad: la firma de `Leccion` (`reloj`,
`esperarTope` y los topes), con el defecto intacto. El juez: `CONTRATO ROTO: 4 promesa(s) incumplida(s)`. La 406 dijo «un Error:
el log no lo dice» (el log traía el mensaje, no el tipo); la 411, «al arrancar con 7 pendientes no hubo tope de cantidad» (siete
`finish` donde iban cinco); la 419, «el log llevó lo que el usuario dijo, escribió o nombró: [■ aprendí «Registrar a Ana Pérez CC
1037» …, el paso 1 no llegó a graph: value «Ana Pérez» …]»; la 420, «el pendiente provisional no se escribió antes del primer
finish». La 405 siguió verde: el mensaje que volcaba el log ya traía el status que ahora juzga. La implementación lo dejó en `CONTRATO INTACTO: 35
promesas.` Después, 6 sabotajes sobre el código sin commitear, revertidos con copia y sha256; cada uno rompió **solo** su promesa:

| # | Sabotaje | Lo que dijo el juez |
|---|---|---|
| 419 | la línea «■» vuelve a llevar el nombre | `el log llevó lo que el usuario dijo, escribió o nombró: [[leccion] ■ aprendí «Registrar a Ana Pérez CC 1037» (3 pasos), SIN comprobar · sesión ses-419-0, …` |
| 419 | en el cliente, la medida de un no-2xx lleva el `error` de Graph | `el log llevó …: [[leccion] el paso 1 no llegó a graph: value «Ana Pérez» no cabe en «Nombre de Ana Pérez» (HTTP 400 …), … la nota de contexto no viajó (nota rechazada: la señora Lucía Gómez tiene VIH …` |
| 420 | el provisional no se escribe: el pendiente solo queda cuando `finish` no salió | `el pendiente provisional no se escribió antes del primer finish: [… disco escribe lecciones/ses-1.json, disco escribe videos-por-reprocesar/ses-1.json, red POST …/ses-1/finish]` |
| 420 | sin tope para el cierre cancelado | `la demostración no terminó en 20 s: quedó algo abierto` (el cierre cancelado esperó los 90 s de `finish`) |
| 411 | el arranque sin tope | `al arrancar con 7 pendientes no hubo tope de cantidad expected:<[ses-t1, …, ses-t5]> but was:<[ses-t1, …, ses-t7]>` |
| 411 | el arranque sin turno: orden fijo por ruta | `el arranque siguiente no empezó por los que quedaron sin intentar expected:<[ses-t6, ses-t7]> but was:<[ses-t1, ses-t2]>` |

Sin sabotaje que el juez vea: la serie de `descartar` (`cancelAndJoin` antes de `cola.cancel()`), porque no hay juez multihilo
(ver arriba).

`./gradlew :app:compileReleaseKotlin -q --rerun` → exit 0. `app/` no se toca.

### Lo que viene (specs y fases propias, sobre este cliente)

4B grabador por accesibilidad · 4C enseñanza activa por Graph (cablea la lección, el almacén y el video) ·
4D reproductor del plan · 4E `workflow_*` por Graph · 4F comprobar.

---

## Diferencias deliberadas con Windows

| Qué | Windows | Android | Por qué |
|---|---|---|---|
| Reintentos en transitorio (0, 408, 429, 502, 503, 504) | `GraphClient` no reintenta | igual que el turno: hasta 3 reintentos con 800 / 1600 / 3200 ms (429 con `Retry-After`: eso, hasta 10 s), todo dentro del tope de la llamada | la red móvil (spec 001). El cierre tiene su propio calendario, el de Windows |
| Tope | 90 s por request (`HttpClient.Timeout`) | 90 s **por llamada**, intentos y esperas incluidos (5 min en `/teach/*`); en `terminar`, 90 s **por intento** | con reintentos, un tope por intento haría la llamada de hasta 6 min; el cierre ya tiene sus tres intentos contados y un 504 de Vercel tarda lo que tarda |
| Lectura agotada en `terminar` | llega como cancelación y no se reintenta | `-1`, no se reintenta y lanza `GraphException` (no `FinishPendiente`) | Graph pudo haber cerrado, y cobrado, la sesión: reintentar más tarde lo decide quien llama (4C) |
| `error` en un cuerpo 2xx | se ignora | termina la llamada con ese texto; `""` no cuenta | criterio del cerebro (promesa 6) |
| `""` de Graph | `??` no cae | ausente al leer (`vacioEsAusente`) | la trampa medida |
| Cancelar `interpretSteps` | el `catch (Exception)` se traga también la cancelación | la cancelación sale | cancelar la corrida no es un fallo del modelo (promesa 14) |
| Fallo de `prepend-alignment` | `catch { }` mudo | devuelve `false` y deja la causa en el log | best-effort no es invisible |
| Cuerpo de `notaDeContexto`, `borrar`, `prependAlignment` | se parsea (`PostAsync<JsonElement>`): un 2xx vacío lanza | no se parsea; solo cuentan el status y un `error` legible | nadie usa ese cuerpo; un 2xx vacío no es un fallo |
| `X-Miracle-App` y `execution_intent.source` | `windows_app` · `windows-u` | `android_app` · `android_app` | atribución por plataforma |
| `createdAt` como fecha ISO | se lee | no se lee (queda sin fecha) | Graph manda el entero Neo4j (medido 2026-09-02); leer ISO sin `kotlinx-datetime` es otra dependencia |
| La lección en disco | se escribe dentro del bloque del video (`GuardarLaLeccion` tras parar el mp4): si parar el video falla, no hay lección | se escribe siempre, después de vaciar los pasos y antes del video, la nota y el cierre | la lección no depende de ninguna llamada |
| El video que no se procesa | se dice en el log y se cierra con los pasos; el mp4 queda en 🎞 Videos sin marca | se cierra igual y queda una marca en `videos-por-reprocesar/` | sin marca, nadie vuelve a procesarlo (Gemini de Graph sin créditos desde 2026-09-03) |
| Vaciar la cola al parar | espera 30 s y cierra con el lector todavía vivo: un paso colgado puede llegar después de `finish` | pasados los 30 s se corta el lector antes de escribir la lección; lo que no salió cuenta como no enviado, con su motivo | ningún paso viaja después del cierre, y la lección dice la verdad |
| Nota de contexto | viaja el resumen del video, no lo hablado | una sola nota con lo hablado y el resumen del video, si hay | lo que el usuario explica de viva voz es el contexto más fiel |
| Cierres pendientes | un solo `pending-finish.json` que se reescribe entero; al cerrar, solo el transitorio deja pendiente; al arrancar, un intento por pendiente y todo lo no transitorio se descarta | un archivo por sesión con escritura atómica, provisional desde antes de la red; `-1` no deja pendiente y al arrancar se descarta; `401`/`403` y sin key dejan pendiente al cerrar y se conservan al arrancar, con un solo criterio (`trasFallo`); al arrancar, un intento por pendiente, como Windows, hasta 5 y 2 min, empezando por los que menos se intentaron | reescribir una lista entera es perder todas por un corte; un proceso que muere a mitad del cierre no pierde la sesión (420); una key mal puesta se arregla, la sesión no murió por eso; tres intentos con esperas y un post-procesado cada uno se repetirían en cada arranque; con Graph caído, N pendientes × 90 s se comerían el arranque (411) |
| Cuándo se vacía la cola | en `recorder.StopAsync`, después del video y de la nota (`WorkflowTeachSession.cs:374`) | lo primero de `terminar`, antes de la lección, el video y la nota | la lección lleva los últimos pasos, y ninguno viaja después de que el video y la nota ya se mandaron |
| Cancelar el cierre | `StopAsync(CancellationToken.None)` (`FaceWindow.xaml.cs:3193`): nada del cierre se cancela, ni el video | se cancela lo que espera —vaciar la cola, el video, que queda para reprocesar y ni se empieza si ya se canceló—; la lección, la nota, el cierre y su pendiente corren bajo `NonCancellable` y la cancelación sale después, con la lección TERMINADA; la red del cierre, con 2 min de tope desde la cancelación | un `viewModelScope` se cancela al salir de la pantalla: minutos de video para nadie no sirven, pero una sesión sin cerrar ni pendiente se pierde (418), y quien cancela no puede quedar esperando 4,7 min (420) |
| El lector de pasos se muere | el lector es de `WorkflowRecorder` y vive lo que vive la grabación | vive en el `scope` que le pasan: si se cancela o falla, lo que no viajó cuenta como no enviado y la lección sale incompleta; `pasoObservado` dice `false` | un scope ajeno se puede cancelar sin que la lección se entere; `join()` no es «cola vacía» (417) |
| Descartar | `DiscardAsync` para el video y borra el mp4, sin llamar a Graph, y no tiene llamadores; `WorkflowRecorder` no tiene descarte | `descartar` corta el lector, suelta la cola y la nota; no llama a Graph ni escribe nada | el único «cerrar» que tiene Graph es `finish`, que post-procesa y persiste: cerrar sería publicar |
| Lista sin `workflows` o plan sin `steps` | `= new()`: lista vacía o plan de 0 pasos | `GraphException` que nombra la clave; vacías sí valen | una respuesta rota no se presenta como «no tienes workflows» ni como un plan que no hace nada (416) |
| `null` dentro de `notes` o `questions` | entra a la lista | se descarta | un hueco no le sirve a nadie, y tirar el resultado perdería un video que Gemini ya cobró (414) |
| Un valor de `variables` que no es texto | `null` entra; un número o un objeto tiran el plan | `null`, objeto o lista → `""`; número → su texto | un plan no se pierde por un campo raro (414) |
| Id de workflow en blanco | `DeleteWorkflowAsync` manda `DELETE /workflows/` | no llama a Graph: `IllegalArgumentException` con el porqué | `/workflows/` es la ruta de la lista, y aquí su 404 contaría como borrado (415) |
| Id numérico en la lista | `WorkflowSummary.Str` solo lee textos: queda `""` | se lee como su texto | un id que no se lee es un workflow que no se puede borrar ni ejecutar (415) |

---

## Supuestos de Graph sin verificar

Todo lo que este cliente asume y **no** está en `windows-graph/src/Contracts.cs` ni se midió desde un
Android. Se verifica en el Nivel 4 de 4C/4D, contra el Graph vivo; hasta entonces, cada uno es un
riesgo abierto.

- `POST /api/v1/learning/sessions` acepta `context.platform = "android"` y un `source_url`/`source_origin` `android://…`.
- `StepRequest.actionType` `key` y `scroll` (el grabador de Windows los emite y `WorkflowExecutor` descarta del plan lo que no sea `input|select|click|navigation`): el cliente los deja pasar como texto libre; qué hace Graph con ellos no se sabe.
- `navigation` con `url = android://paquete/Activity` es ejecutable para Graph.
- Los selectores opacos `a11y:…` y las pistas de superficie de Android (`observedSurface android://…`, `readiness`, `fingerprint`, `clickPos`) viajan y vuelven intactos en el plan, igual que los de UIA/SAP.
- `X-Miracle-App: android_app` sin `X-Miracle-Feature` es válido en `/learning/*` y `/workflows/*` (en el turno se midió con la feature).
- `execution_intent.source = "android_app"` es un valor aceptado (Windows manda `windows-u`).
- `X-Miracle-Feature: conscious_bridge` es la atribución correcta para `/teach/*` también desde Android.
- `GET /api/v1/workflows` devuelve para esta API key los workflows grabados desde Android, con el mismo shape (`id`, `description`, `sourceOrigin`, `sourceTitle`, `totalSteps`, `createdAt {low, high}`).
- `DELETE /api/v1/workflows/{id}` sigue respondiendo 404 cuando no existe (y no 200 ni 410).
- `finish`, `plan` e `interpret-steps` no dependen de Gemini; `upload-token` y `process-video` sí (sin créditos desde 2026-09-03, pueden fallar).
- Un 2xx con `{"error":"…"}` es un fallo y no un éxito parcial.
- Las sesiones de aprendizaje viven en memoria serverless: por eso el id va siempre en la ruta; si Graph pierde una sesión entre instancias, el paso responde con un error que este cliente muestra tal cual.
- Una sesión que nunca recibe `finish` no deja un workflow visible. `PendingFinish.cs` dice que «los pasos ya están guardados» antes del cierre: si es así, una demostración descartada deja en `GET /workflows` un workflow sin resumen con los pasos que alcanzaron a viajar (ver la decisión abierta en 4A2).
- Un `finish` repetido sobre una sesión que Graph ya cerró (el reintento al arrancar de un cierre que sí había salido) responde un error no transitorio y no cierra ni cobra dos veces.
- Si un 502, 503 o 504 en `process-video`, `interpret-steps`, `upload-token` o `finish` llega después de que Gemini o el
  LLM ya cobraron. Esos transitorios se reintentan: `/teach/*` hasta 3 veces y el cierre en 3 intentos, así que hoy
  `process-video` puede llegar a 4 cobros por un solo video.
- Qué responde `finish` reintentado sobre una sesión cerrada o perdida (¿404? ¿400?). Hoy, un transitorio seguido de un
  404 da `GraphException` y no `FinishPendiente`: la lección lo cuenta como `FALLIDO` aunque el primer intento pudo
  haberla cerrado. Con el provisional (420) pasa también al arrancar, cuando el proceso murió entre un `finish` que salió y el
  borrado: con 404 o 400 el pendiente se borra tras un solo intento; con 2xx, Graph podría post-procesar y cobrar dos veces.
- `execution_intent.surface = "native"` es un valor válido desde Android.
- `allowedOptions` trae `value` y `label` no nulos, y los valores de `variables` son texto. Desde la 414 el cliente tolera
  lo contrario, pero Graph no lo promete.
- El `id` de un workflow es siempre texto. Desde la 415 un número se lee como su texto.
- Graph acota la profundidad o el tamaño de `interpretation` y `workflow`. Desde la 413 el cliente corta a 64 niveles;
  el tamaño no lo acota nadie.

---

## Lo que NO entra, y por qué

- **El PUT del video a Gemini y al archivo**: es binario y va con la enseñanza activa (4C). La lección
  recibe el video como una función que devuelve su resumen o `null`. `send` ya acepta `PUT`, pero sin
  cuerpo binario.
- **El `Almacen` de verdad y llamar a `reintentarPendientes` al arrancar**: archivos y ciclo de vida de la
  app (4C). La lección ya guarda, lee y reintenta los pendientes sobre el puerto.
- **El sondeo de `file-state` (2 s × 90)**: va dentro de la función del video (4C). Aquí, una consulta.
- **`interpret-steps` como respaldo del video** (promesa 136 de Windows) y **la skill local**: 4C. La
  lección devuelve el resumen del video y los pasos con su resultado para que quien empaqueta decida.
- **Leer la identidad de la pantalla y dónde terminó la demo**: el grabador por accesibilidad (4B); la
  lección los recibe.
- **`POST /api/v1/autofill/match` y `GET /api/v1`**: no los usa ninguna fase del sprint 4.
- **Cablear en la app** (`GraphApp`, `MainActivity`, `ActiveLearning`): 4C.
- **Nombre puesto por el usuario** (promesa 109 de Windows): disco, 4C.

## Riesgo

El mayor es el mismo de la 001: que Graph cambie el contrato y este cliente no se entere. Por eso
`ignoreUnknownKeys`, `""` = ausente, `null` tolerado, un tope de 64 niveles y la interpretación cruda: quien la entiende es
una pieza pura que no vive aquí.
