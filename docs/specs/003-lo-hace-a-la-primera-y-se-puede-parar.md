# Plan de implementación: lo hace a la primera y se puede parar — el freno y la puerta única

Estado: **fase 3A implementada** (2026-09-14; promesas 301-306 verdes; cada una se vio ROJA con un sabotaje real) · **fase 3B implementada** (2026-09-14; promesas 307-309 y 316 verdes; 17 sabotajes y cada uno puso ROJA su promesa; la 308 juzga además el cableado de `GraphApp` y `Ejecucion`) · **fase 3C implementada** (promesas 310-315 verdes; entró con el merge `297a2c6`) · **Nivel 4 de la 3B hecho** (2026-09-15; en el celular, la píldora y la notificación cortan la corrida sin pedir otro turno a Graph, y la tarea siguiente nace suelta) · **revisión de 3A-3C, parte 1** (2026-09-15; promesas 317-319 nuevas y 306-308, 310 y 315 endurecidas; cada una se vio ROJA con un sabotaje real) · **revisión de 3A-3C, parte 2** (2026-09-15; promesa 320 nueva y 308, 310-313, 315 y 317 endurecidas; la app comparte un tope y una cuenta por proceso; 16 sabotajes y cada uno puso ROJA su promesa) · Nace de leer el freno de `U-Windows-App`
(`windows-client/src/Actions/Freno.cs`, promesas 21-28 y 59 de su contrato) y de un hallazgo grave
de U que el Android no puede heredar · Rama: `yokh/precision`

Hoy el Android no tiene freno: tiene un `cancel()`. El motor ejecuta cada acción directamente sobre
el teléfono y el MCP, y lo único que lo detiene es cancelar la corrutina de la corrida. Esta spec
pone **una sola puerta** entre el motor y el teléfono, y un **freno inyectable** que esa puerta
consulta en cada entrada. Con el freno echado no se puede actuar, aunque el código que actúa no
sepa que el freno existe.

---

## Diagnóstico: qué se midió

Se midió leyendo los dos repos, no suponiendo.

| Qué | Medida | Fuente |
|---|---|---|
| El motor toca el teléfono sin intermediario | `execute` llama `phone.tap/type/…` y `mcp.call` directo; `Wait` es `delay` de un tirón | `core/…/application/Engine.kt:106-116` |
| La app pasa la superficie cruda | `ExecutionEngine(phone = surface, mcp = Mcp(service, …))` en la corrida y en el step consciente | `app/…/GraphApp.kt:378-386` · `:398-402` |
| Parar hoy es cancelar la corrutina | `stopExecution()` → `runJob?.cancel(CancellationException("Detenida por ti ✋"))`: corta en el próximo punto de suspensión, no en la próxima entrada al teléfono, y nada impide que otro camino (un workflow, la voz) siga tocando | `app/…/GraphApp.kt:578-581` |
| Entre turnos el motor duerme 400 ms y entre pasos `stepDelay` | un alto pedido ahí no se ve hasta que vuelve a pedir turno a Graph (que cuesta) | `Engine.kt:75` · `Engine.kt:84` |
| **Hallazgo grave de U** | en producción Escape no frena `map_batch` ni `map_take`: `Freno.Empezar` solo se llama en `AcomodarEscritorio.cs:120,194,616,699`; sin eso `Pide` sale en `Freno.cs:126` sin armar. Su promesa 59 da verde con un freno falso (`Contrato.cs:3566-3573`): juzgaba la clase, no el cableado | `U-Windows-App/windows-client/src/Actions/Freno.cs` · `tests/ContratoDelGrafo/Contrato.cs` |
| U ya pagó el freno que no suelta | `Termine` solo apagaba «estoy haciendo algo» y el alto quedaba pedido: con el freno en la puerta, entre una tarea y la siguiente la máquina quedaba muerta | `Freno.cs:100-111` (promesa 26 de U) |

**Lo que esto significa:** el freno de U es correcto como clase y falla como cableado. En Android el
freno es una **instancia inyectada** (no un `object`), la **puerta** es la que ejecuta, y **sin
tarea abierta la puerta no deja pasar nada**: olvidarse de abrir la tarea no deja un freno que no
frena, deja un teléfono que no se toca y lo dice en el log.

---

## La especificación

Bloque 301-399 (spec 003). Los números no se reciclan. El enunciado de cada promesa es **literal**
el del mapa `PROMESAS` del test que la juzga (método `promesaNNN`); si cambia uno, cambia el otro en el mismo commit.
Los jueces viven en varios archivos:

| Archivo | Promesas |
|---|---|
| `core/src/commonTest/kotlin/graph/core/contrato/Contrato003FrenoYPuerta.kt` | 301-306 |
| `core/src/jvmTest/kotlin/graph/core/contrato/Contrato003LaAppPorLaPuerta.kt` (lee las fuentes de la app: solo jvm lee disco) | 307, 308, 320 |
| `core/src/commonTest/kotlin/graph/core/contrato/Contrato003ArmadoYAlto.kt` | 309, 316 |
| `core/src/commonTest/kotlin/graph/core/contrato/Contrato003TopeYCuenta.kt` | 310-315 |
| `core/src/commonTest/kotlin/graph/core/contrato/Contrato003LoQueSaleYUnaCorrida.kt` | 317, 318 |
| `core/src/jvmTest/kotlin/graph/core/contrato/Contrato003Carrera.kt` (hilos de verdad) | 319 |

| # | Promesa | Fase |
|---|---|---|
| 301 | Sin tarea abierta la puerta no deja pasar ninguna entrada al teléfono y lo dice en el log. | 3A |
| 302 | Empezar una tarea desarma un alto viejo, y pedir el alto sin tarea abierta no arma el freno. | 3A |
| 303 | Con el freno echado ninguna entrada llega al teléfono y la corrida termina como cancelación, sin ejecutar el resto ni pedir otro turno. | 3A |
| 304 | El alto se avisa una sola vez por tarea aunque se pida diez veces, y al soltar se dice «Listo, tienes el control de vuelta.» una vez. | 3A |
| 305 | Una espera se corta en cuanto se pide el alto, no al agotar el plazo. | 3A |
| 306 | Terminar suelta el freno siempre, aunque la tarea reviente; después la puerta vuelve a exigir tarea abierta; una tarea anidada no la cierra. | 3A · revisión 3B |
| 307 | El motor y el MCP solo se arman sobre la puerta: ningún archivo de la app construye un ExecutionEngine o un Mcp, ni entrega el servicio de accesibilidad crudo como manos. | 3B |
| 308 | La píldora, la notificación y cualquier otra orden de parar usan el mismo alto: frenan la corrida en curso por la misma puerta. | 3B |
| 309 | Un alto a mitad de turno no ejecuta las acciones que faltan ni le pide otro turno a Graph. | 3B |
| 310 | Dos intentos y no tres: en una misma petición, la tercera entrada hacia un destino que ya falló dos veces no toca el teléfono y dice qué salió en cada una. | 3C |
| 311 | El destino es el nodo realmente tocado, se pida como se pida; sin nodo bajo el punto, la misma celda de 48 dp es el mismo destino; al escribir, el campo cuenta tal como se pidió. | 3C |
| 312 | Un intento se logra si la acción se dio y escribió o cambió la pantalla, textos incluidos; tocar tres veces un botón que cambia el texto visible nunca se bloquea; una lista de homónimos no es intento. | 3C |
| 313 | Elegir por número solo vale con una lista previa de ese nombre y se compara como número; sin lista, el número no abre un destino nuevo. | 3C |
| 314 | Una petición nueva devuelve el tope a cero; un aviso del sistema o una llamada retirada no. | 3C |
| 315 | Cada petición deja una línea `peticion:` con su medida; lo rechazado y lo retirado también cuentan; sin llamadas no se emite. | 3C |
| 316 | Parar dentro de un paso consciente de un workflow para la corrida entera: el workflow no sigue con el paso siguiente. | 3B |
| 317 | Ninguna línea de log de la ejecución lleva lo que el usuario escribió, pidió o lo que la pantalla muestra: solo tipos, largos, celdas y nombres de herramienta. | revisión 3A-3C |
| 318 | Una corrida de fuera no se abre encima de otra: la segunda dice «ya hay una tarea en curso» sin pedir turnos ni tocar el teléfono, y un motor sin tarea abierta termina como parada sin pedir otro turno. | revisión 3A-3C |
| 319 | Pedir el alto al mismo tiempo que termina la tarea nunca deja el freno armado sin tarea ni hace nacer parada a la siguiente, y dos vías que abren una corrida a la vez nunca abren las dos. | revisión 3A-3C · parte 3 |
| 320 | Un paso consciente dentro de una corrida no devuelve el tope a cero ni abre otra petición; una corrida nueva de fuera sí. | revisión 3A-3C, parte 2 |

**La que cierra el asunto es la 301.** Es la que U no tiene: en U un freno sin `Empezar` es un
freno que no frena y nadie se entera. Aquí una puerta sin tarea abierta no deja pasar nada y lo dice.

### Con qué se juzga cada una

Ninguna toca Android, red ni disco: teléfono, gestos, sistema y reproductor falsos que graban cada
entrada; un cerebro guionado que cuenta sus turnos; y un reloj `TestTimeSource` con una espera que
lo avanza en vez de dormir.

| # | Cómo se juzga sin tocar nada |
|---|---|
| 301 | Una `Puerta` sobre falsos, sin tarea abierta: las 31 entradas (las 6 de `Phone` que no son `state`, las 5 de `Gestures`, las 19 de `SystemApi` y `tapLabel`) devuelven `false`, ningún falso graba nada y hay una línea `puerta: sin tarea abierta, no paso «…»` por entrada. `state` sí pasa. Un `Mcp` construido sobre la puerta devuelve «la herramienta no se pudo ejecutar» sin tocar. Con tarea abierta, las mismas entradas llegan |
| 302 | Tarea 1 con alto pedido → `termine()` no, `empezar("tarea 2")` → `pedido` es `false`. Freno sin tarea: `pide` diez veces → `pedido` es `false`, no hay aviso ni línea de log |
| 303 | El `ExecutionEngine` real con la puerta como teléfono y MCP. (a) El alto se pide durante el primer `tap` de un turno de cuatro acciones: el teléfono graba un solo `tap`, gestos y sistema nada, el cerebro dio un solo turno, `run` devuelve «paraste: …» y se narra una sola vez, sin «¡Listo!». (b) El alto se pide dentro de `next`: ninguna entrada llega. (c) El alto se pide en la última acción de un turno: no se pide otro turno. (d) El alto se pide dentro de `next` y el turno trae una pregunta: no se dice ni se le pregunta a nadie. (e) Lo mismo con un turno `done`: no dice el resumen ni «¡Listo!», devuelve «paraste: …». (f) Un `Wait(3000)` del modelo con el alto a los 120 ms: `run` acaba en menos de 1 s. (g) Cancelar el trabajo que corre `run` propaga una cancelación que no es `Paraste` y no narra «Paré». La entrada rechazada lanza `Paraste` (tipo exacto) con el mensaje «paraste tú» |
| 304 | `empezar` + `pide` ×10 → un aviso y una línea «freno: alto pedido (…); paro «…»». `termine()` → «Listo, tienes el control de vuelta.» una vez; otro `termine()` no lo repite; una tarea sin alto no lo dice |
| 305 | `duerme(3000)` con reloj de prueba y el alto pedido a los 120 ms → devuelve `true`, pasó menos de 200 ms y ningún trozo pasó de 40 ms. Sin alto, `duerme(100)` devuelve `false` tras 100 ms en trozos `[40, 40, 20]`. Y con el reloj y la espera reales, un alto a los 120 ms corta un `duerme(3000)` antes de 1 s |
| 306 | `enTarea { throw Reventon("revienta") }` (una excepción que no es cancelación, para que una `Paraste` no cuente como reventón) → sale tal cual, `abierta` y `pedido` son `false`. Con un alto pedido dentro también, y la frase de devolver el control se dice. Después, un `tap` por la puerta no pasa y lo dice el log. Anidada: `enTarea("exterior") { enTarea("paso consciente") { tap }; … }` → tras el bloque de dentro la tarea sigue abierta, el `tap` siguiente pasa y el alto arma. Un `avisa` que lanza un `Error` (no una `Exception`, como un TTS sin inicializar) en el alto y al soltar: sale la causa de la tarea, el freno suelta y el log dice dos veces «no pude avisar». Un `avisa` que lanza `CancellationException`: `pide` y `termine` la dejan salir, el freno suelta igual y no hay «no pude avisar» |
| 307 | Lee `app/src/main/kotlin/**/*.kt` (se busca subiendo desde el directorio del test): ningún archivo construye `ExecutionEngine(`, `Mcp(`, `WorkflowRunner(` ni `Puerta(`, tampoco con el nombre calificado (solo se exime `AgentAction.Mcp(`), ni los esconde tras `typealias … =`, `import … as` o `::ExecutionEngine`; `ArmadoDeEjecucion(` y `Manos(` solo en `Ejecucion.kt`; ningún argumento `phone`/`gestures`/`player` recibe `service`/`ui`/`this`. Y `ArmadoDeEjecucion` puro con falsos crudos: el motor mira la tarea antes de cada turno, así que la tarea se cierra al narrar la intención de la primera acción; desde ahí el motor, una herramienta MCP y el reproductor de workflows no llegan a ningún falso y la puerta lo dice seis veces |
| 308 | Fuentes, solo lo que no se puede correr sin Android: `StopReceiver` llama `Ejecucion.parar("notificación")`, la píldora de `FloatingBubble` `Ejecucion.parar("píldora")`, `stopExecution` `Ejecucion.parar("botón")`, y ningún archivo de la app cancela el trabajo de la corrida (`runJob`) ni llama `stopExecution` desde la píldora o la notificación. Dentro del bloque de `Ejecucion.correr` de `GraphApp.run` (llaves contadas, no posiciones en la función): el motor, `Ejecucion.sigue()` tras él, y después reencaminar y `anticipate(`; `consciousStep` devuelve `Ejecucion.pasoConsciente(` y no corre un motor por su cuenta; en `anticipate`, la sentencia siguiente a `anticipation.consider(` es `Ejecucion.sigue()` sola y a su misma sangría (ni dentro de un `runCatching`, ni tras un `if`), y nada en la app se declara `Ejecucion`, lo importa de otro paquete o lo trae con `import … as` o `typealias`; `Ejecucion.parar` es `armado.parar(porque)` sin decidir el corte; el único `ArmadoDeEjecucion(` de la app le da `lanza = { c -> ….launch { c() } }` y no toca la gracia; un solo `Freno(`, en `Ejecucion.kt`. Por comportamiento, con el armado de verdad y la gracia escrita en el test (1500 ms): `parar` sin tarea no arma ni lanza un corte; dentro de `correr` arma el freno de esa tarea y tres órdenes avisan una vez; una corrida cuyo turno vuelve 100 ms después del alto termina por el alto («✋») y no se corta; y un turno colgado se corta pasada la gracia, no antes (≥ 1500 ms y < 2500 ms), y la corrida termina como cancelación soltando el freno. Y `GraphApp.run` no empieza nada con otra corrida viva: `if (Ejecucion.enCurso) return yaHayUna()` es una sentencia a la sangría de la función, antes de su primer efecto (`Telemetry.`, `bubble?.ask(`, el destilador, el contexto pendiente, `goalPrompts`, la ventana de contexto o `Ejecucion.correr(`); `memoryDistiller.`, `consumePendingVoice(`, `goalPrompts.clear(` y `maxContextTokens` solo aparecen dentro del bloque de `Ejecucion.correr`; `yaHayUna` devuelve `CorridaEnCurso.MENSAJE` y `Ejecucion.enCurso` es el del armado. Nada se llama `Ejecucion` tampoco al desestructurar (`val (Ejecucion) = …`), como parámetro de una lambda ni en un `for` |
| 309 | `ArmadoDeEjecucion` con `GraphBrain` real sobre un transporte guionado que cuenta requests. Graph manda `[tap, tap, type]` o `[tap, wait, wait]` y el alto llega durante el primer `tap`: una sola acción ejecutada (una línea «▪»), un solo request a Graph y `correr` termina en `Paraste` |
| 310 | `TopeDeIntentos` puro: un toque que se dio sin cambiar la pantalla y otro que revienta hacia «Guardar» → el tercero se rechaza con «no lo intento una tercera vez: «Guardar» ya falló dos veces en esta petición — 1) … · 2) …» y «Cambia de vía…»; otro destino pasa. Y la `Puerta` de verdad: `tap`, `type` y `tapLabel` fallidos dos veces → el tercero devuelve `false`, el teléfono falso grabó dos entradas y el log dice `tope: no paso «…»: …ya falló dos veces…` (sin el rechazo entero, que nombra el destino: promesa 317). Con el alto pedido y los tres destinos (toque, escritura y etiqueta) castigados, cada entrada lanza `Paraste`, el tope no contesta y la cuenta cierra con `llamadas=6 … rechazadas=0`: mirar el tope antes que el freno deja el mismo log de siempre y devuelve `false`. Una entrada frenada no suma un fallo: tras un fallo, un alto pedido al leer la huella y una cancelación dentro del `tap`, el toque siguiente llega, y solo el que sigue a ese se rechaza |
| 311 | Puerta con `nodoEn`: dos puntos distintos (y de celdas distintas) del mismo nodo son un destino; dos nodos pequeños dentro de la misma celda son dos. Sin nodo bajo el punto, dos puntos de la misma celda de 48 dp son un destino y la celda vecina es otro; sin `nodoEn`, la celda. `celdaPx(3f)` = 144 y `celdaPx(2.75f)` = 132. Al escribir cuenta el punto pedido, no el nodo que lo resuelve: el mismo campo pedido en otra celda es otro destino. Por nombre, «Teléfono», «telefono» y «TELÉFONO » son un campo: dos fallos frenan el tercero, y el rechazo lo nombra «TELÉFONO» |
| 312 | Calculadora: cinco toques al mismo nodo «7», cada uno cambia solo el texto del display (la huella sin textos es idéntica antes y después) → cinco entradas y cero rechazos; el cambio aparece solo después de `asentar`. «Guardar»: tres toques sin cambio → el tercero no llega. Escribir tres veces con éxito no se bloquea; un toque que no se dio cuenta como fallo aunque la pantalla cambie; tres listas de homónimos no son intento. Sin `huella`, tres toques sin juzgar no bloquean, el log lo dice una vez y la cuenta los deja en `primera=— ultima=—` |
| 313 | Sin lista: dos fallos a «Descargas» → `which=1` y `which=2` se rechazan y el rechazo no nombra `which`. Con lista de «Descargas»: dos fallos al 1 → «01» y «+1» se rechazan, el 2 pasa y el rechazo dice «prueba OTRO candidato con which» con la lista; fallos a «2» frenan «02» y «+2»; una lista de «Documentos» no habilita `which` en «Descargas»; el selector del candidato tocado por coordenada es ese candidato. Una lista sin candidatos no deja elegir: con dos fallos a `which=1`, `which=1…6` se rechazan sin sugerir `which` |
| 314 | Tope con dos fallos: `abrePeticion(SISTEMA, …)` y `retirada` lo dejan rechazando y la cuenta no emite; `abrePeticion(PERSONA, …)` lo vacía (fallos y listas), emite la línea de la anterior, y por la puerta el tercer toque vuelve a llegar |
| 315 | `CuentaDePeticion` con `TestTimeSource`: línea exacta `llamadas=5 distintas=3 intentos_max=3 «celda:1,1» primera=800 ms ultima=1400 ms desde_peticion=1000 ms rechazadas=1 retiradas=1` y en el log como `peticion: …`; sin acción que actuó, `primera=— ultima=—`; un resultado sin llamada no cuenta; sin llamadas ni `cerrar` ni `nuevaPeticion` emiten. Un selector va sellado por su estructura (`id`, `cls`, `path`), `«#xxxxxxxx»`: el mismo selector da el mismo sello en otra petición y con otro texto visible, otro id da otro, y la línea no lleva `text=`; una celda va tal cual; tocar «Guardar» por nombre deja `«nombre de 7 caracteres»`, un campo por nombre va `campo de 8 caracteres`, un nodo sin id `nodo sin id estructural`, y el candidato que elige `which` con el sello de su nodo. Por la puerta: tres toques a «Guardar» (uno rechazado) y un scroll dejan `llamadas=4 distintas=2 intentos_max=3 «#…» … rechazadas=1`. Una retirada y dos rechazos sin llamadas no emiten ni por `cerrar` ni por `nuevaPeticion`, y no se cuelan en la siguiente; abierta con `abrePeticion(PERSONA, …)`, `desde_peticion` es un número (500 ms), no «—» |
| 316 | Un workflow de tres pasos (consciente, subconsciente, consciente) como herramienta MCP del motor armado. Sin alto: pasan `tap`, `tapLabel` y `tap` (el paso consciente anidado no cerró la corrida). Con el alto durante el `tap` del paso 1: nada más llega, el workflow no vuelve a leer la pantalla para el paso 2 ni da el paso 1 por hecho, Graph no da otro turno y `correr` termina en `Paraste` |
| 317 | Una bitácora común recorre, con datos de verdad (un contacto, un número, un mensaje, un correo, una búsqueda, una URL, una dirección, una app y el título de un chat): las 31 entradas de la puerta sin tarea y con el alto echado; el tope y la cuenta sobre la fila de un contacto que no responde (toque, escritura y etiqueta hasta el rechazo, dos líneas `peticion:`) y con la huella y el nodo que revientan con el título en el mensaje; y una corrida del armado, con tope y cuenta compartidos, con el pedido, `type`, `open_app`, `send_sms`, una herramienta aprendida y un workflow que fallan, un paso consciente cuyo Graph revienta, una pregunta, un resumen, una corrida que se intenta abrir encima y un alto. Cada vía tiene que haber escrito su línea en esa corrida (también `peticion:`); ninguna línea, sin tildes ni mayúsculas, contiene un trozo de esos datos. «Ana» y «Mamá» también se tocan por nombre hasta el rechazo. Un trozo es cada ventana de 4 caracteres dentro de cada palabra de los datos, y «ana», «mama» y «juan» se buscan como palabra entera, quitando antes el sello `#xxxxxxxx` (ocho hex al azar pueden formar «4471»); los datos están elegidos para que ningún trozo sea parte de una palabra del log («corrida», «cuento»). Y lo que el log dice de un destino no se revierte: el rechazo de «Ana» (el `tope:` y su `peticion:`) no contiene el FNV-1a sin sal de ningún nombre de un diccionario chico y es idéntico al de «Eva» y «Luz»; una fila con «Ana» o con «Eva» en `text=` deja la misma línea; la misma fila da el mismo sello dos veces en el proceso y otro en una JVM nueva que lanza la prueba |
| 318 | Dentro de una corrida de fuera, `correr` otra lanza `CorridaEnCurso` con «ya hay una tarea en curso»: su cerebro no da un turno, nada llega al teléfono, la primera sigue abierta y el alto la para; después la siguiente se abre y nace suelta. Lo mismo desde dos corrutinas, con la primera esperando a Graph. Un motor sin tarea abierta devuelve «paraste: …» con cero turnos y sin narrar «Paré». Y si la tarea se cierra debajo del motor (en su primer `tap`), no pide el segundo turno. Si se cierra dentro de `next` y el turno trae pregunta y `speech`: cero preguntas, nada dicho y nada tocado. Un log que revienta en «tarea abierta»: la corrida no queda en curso y la siguiente se abre. Con la primera corrida cancelada pero detenida en `NonCancellable` antes de su `finally`, la segunda lanza `CorridaEnCurso` sin turnos ni toques |
| 319 | Hilos de verdad: la píldora pide el alto en bucle desde otro hilo mientras la corrida hace `empezar`/`termine` hasta un millón de vueltas o 3 s. Tras cada `termine`, ni `abierta` ni `pedido`; los avisos alternan «Vale, paro.» → «Listo, tienes el control de vuelta.» sin salirse de orden y hay tantos altos como devoluciones (y más de cero); la tarea siguiente nace suelta. Sin candado: 5.363 frenos armados sin tarea en un millón de vueltas. Y tres hilos que viven toda la prueba giran hasta una señal común y llaman `empiezaSiNoHayOtra` a la vez, hasta 200.000 vueltas o 3 s: en cada vuelta abre exactamente una |
| 320 | El `ArmadoDeEjecucion` real con un tope y una cuenta compartidos y un teléfono cuyo «Guardar» nunca responde. Dentro de una corrida, un motor da dos toques fallidos, se intenta abrir otra corrida encima y un paso consciente arma su motor sobre otra puerta y toca por tercera vez: no llega (dos toques en el teléfono), dentro no se emite ninguna `peticion:`, y al acabar sale una sola, `llamadas=3 … rechazadas=1 retiradas=0`. Una corrida nueva de fuera: el toque vuelve a llegar y deja su propia línea `llamadas=1 …`. Y en la app: `Ejecucion.kt` construye el único `TopeDeIntentos(` y la única `CuentaDePeticion(` como `private val` del objeto y se los da a su único `ArmadoDeEjecucion(`; nada los esconde tras `typealias`, `import … as` o `::`, y nadie en la app nombra `abrePeticion` ni `nuevaPeticion`, tampoco por referencia (`TopeDeIntentos::nuevaPeticion`) |

---

## Las fases

### Fase 3A — la capa pura en `core` (esta corrida)

Todo en `core/src/commonMain/kotlin/graph/core/precision/`, sin dependencias nuevas:

- `Freno.kt` — instancia inyectable: `empezar`, `pide`, `pedido`, `abierta`, `termine`, `enTarea`,
  `duerme` (trozos de 40 ms con reloj y espera inyectables) y el aviso único.
- `Paraste.kt` — `Paraste(motivo) : CancellationException`: una parada no es un fallo (promesa 14).
- `Puerta.kt` — expone `telefono: Phone`, `gestos: Gestures`, `sistema: SystemApi` y `reproductor: UiPlayer`
  envolviendo los reales (cuatro vistas y no una clase: `Phone.openApp` y `SystemApi.openApp` tienen la
  misma firma y delegan en objetos distintos).
- `NodoVivo.kt` — el nodo vivo mínimo para 3C/3D, sin lógica.
- `Engine.kt` — recibe el freno con default `null`; una `Paraste` termina la corrida como
  cancelación; con freno, las esperas del motor se cortan al pedir el alto y no se pide turno con
  el alto echado.

Pone verdes: **301-306**. La app todavía no usa la puerta.

### Fase 3B — el cableado en `app` (promesas 307-309 y 316)

- `core/…/precision/ArmadoDeEjecucion.kt` — la única fábrica: recibe las manos crudas (`Manos`: Phone, Gestures,
  SystemApi, UiPlayer) y devuelve el motor, el MCP y el reproductor de workflows **sobre las vistas de la
  puerta**, con el freno en el motor. Expone `correr` (la tarea de fuera: abre, recuerda su trabajo y, si hubo
  alto, termina en `Paraste`), `parar`, `sigue`, `cortaSiNoSuelta` y `pasoConsciente`.
- `app/…/Ejecucion.kt` — un solo `Freno` por proceso; el único sitio que entrega el servicio de accesibilidad
  y `AndroidSystemApi` como manos. `parar(porque)` pide el alto y, si la corrida no suelta en 1,5 s (un turno de
  Graph colgado en red), corta su trabajo **después** del alto.
- `GraphApp` — `run` corre dentro de `Ejecucion.correr`; tras cada motor mira `sigue()`: con alto no reencamina
  ni anticipa. `stopExecution` es `Ejecucion.parar("botón")`. El paso consciente usa `pasoConsciente`.
- `StopReceiver` → `Ejecucion.parar("notificación")`; la píldora → `Ejecucion.parar("píldora")`.
- `WorkflowRunner` deja pasar la cancelación de un paso consciente (antes la convertía en «paso fallido» y seguía).
- `Freno.enTarea` anidada no empieza ni termina: solo cierra quien abrió.
- `ExecutionEngine` mira el freno también justo después de `next`: un turno que vuelve con pregunta o `done`
  tras el alto no pregunta ni celebra.

### Nivel 4 de la 3B — en el teléfono (hecho, 2026-09-15)

Xiaomi M2101K7BL · Android 12 · MIUI 13 · APK release 0.42 armado en `297a2c6` (merge de 3B y 3C, contrato de 31
promesas) con `apikey.properties`, instalado con `adb install -r` (`lastUpdateTime=2026-09-14 23:42:40`). Las
tareas se escribieron en «Pídeme algo»; log con `adb logcat -v time -s Graph:D`. El primer intento (HEAD `2e18c83`,
2026-09-14 23:29) no traía key: la corrida se cortó antes de abrir la tarea con «no hay key de graph», sin tocar
el teléfono ni llamar a nadie.

**(a) «abre la calculadora»: pasa por la puerta y termina.** Launcher → Calculadora.

```
23:43:32.877 [freno] tarea abierta «abre la calculadora»
23:43:40.760 [graph] turno 1 · session=nuevo · HTTP 200 · 6107ms · 1 acciones
23:43:42.261 [api] launch_app → com.miui.calculator
23:43:42.261 [run]   ▪ MCP launch_app {app=Calculadora} → ok
23:43:46.473 [run] ■ 2 turnos · 1 acciones · 13s · ¡Listo, calculadora abierta! 🧮
mCurrentFocus=Window{22a6c21 u0 com.miui.calculator/com.miui.calculator.cal.CalculatorActivity}
```

La puerta no escribe nada cuando deja pasar: la prueba es que la acción corrió con la tarea abierta y sin
`[puerta] sin tarea abierta, no paso …`.

**(b) La píldora a mitad.** «abre ajustes, entra a wifi, vuelve y entra a bluetooth». Se tocó la píldora negra
«ejecutando…» del notch (arriba al centro) un segundo después de la acción del turno 1, con el turno 2 en red:

```
09:22:43.609 [graph] turno 1 · session=nuevo · HTTP 200 · 5782ms · 1 acciones
09:22:43.637 [run]   ▪ MCP open_settings {section=wifi} → ok
09:22:44.713 [app] ⏹ alto pedido por píldora
09:22:44.713 [freno] alto pedido (píldora); paro «abre ajustes, entra a wifi, vuelve y entra a bluetooth»
09:22:46.215 [freno] la corrida no soltó en 1500 ms tras el alto (un turno colgado en red): corto su trabajo
09:22:46.406 [freno] suelto «abre ajustes, entra a wifi, vuelve y entra a bluetooth»: el control vuelve a ti
09:22:46.407 [app] Ejecución detenida ✋
09:22:46.408 [run] ✋ paraste tú · 2 turnos · 1 acciones · 8s · no sigo
mCurrentFocus=Window{177cd25 u0 com.android.settings/com.android.settings.Settings$BluetoothSettingsActivity}
```

Ningún `[graph] turno` después de 09:22:44.713: el turno 2 que estaba en red se cortó sin registrarse ni ejecutar
nada. En pantalla, toast «Detenido» y el globo «Vale, paro.» de la burbuja. El foco en Bluetooth es de antes: la
tarea de Ajustes venía de ahí y `open_settings {section=wifi}` dio `ok` sin cambiar de pantalla.

Un intento anterior (09:06) llegó tarde y enseñó otra cosa: la corrida ya había cerrado (`■ 3 turnos` a 09:06:54.788)
pero la tarea seguía abierta durante la anticipación, que reintentaba Gemini en 429. El alto cayó ahí y la cortó:

```
09:06:54.788 [run] ■ 3 turnos · 2 acciones · 19s · Listo: abrí Wi‑Fi y luego volví a Ajustes para entrar a Bluetooth.
09:06:59.917 [freno] alto pedido (píldora); paro «abre ajustes, entra a wifi, vuelve y entra a bluetooth»
09:07:01.420 [freno] la corrida no soltó en 1500 ms tras el alto (un turno colgado en red): corto su trabajo
09:07:01.818 [freno] suelto «abre ajustes, entra a wifi, vuelve y entra a bluetooth»: el control vuelve a ti
09:07:01.820 [app] Ejecución detenida ✋
```

**(c) La notificación a mitad.** «abre ajustes, entra a wifi, vuelve, entra a bluetooth, vuelve y entra a
pantalla». En la persiana de MIUI la notificación «Ü está ejecutando · Toca para detener» sale **colapsada**: el
botón «⏹ Detener» no se ve sin expandirla. Se tocó el cuerpo, que manda el mismo broadcast (`setContentIntent(stop)`
→ `StopReceiver`). El primer intento (09:25) no sirvió: con la persiana abierta el motor vio `com.android.systemui`,
la cerró con `key back` (turno 3) y el toque cayó en la lista de Wi-Fi. El segundo (09:31) abrió la persiana, tomó
la captura y tocó en el mismo paso, justo tras la acción del turno 1:

```
09:31:33.976 [graph] turno 1 · session=nuevo · HTTP 200 · 4575ms · 1 acciones
09:31:34.003 [run]   ▪ MCP open_settings {section=general} → ok
09:31:36.153 [app] ⏹ alto pedido por notificación
09:31:36.153 [freno] alto pedido (notificación); paro «abre ajustes, entra a wifi, vuelve, entra a bluetooth, vuelv»
09:31:37.656 [freno] la corrida no soltó en 1500 ms tras el alto (un turno colgado en red): corto su trabajo
09:31:37.987 [freno] suelto «abre ajustes, entra a wifi, vuelve, entra a bluetooth, vuelv»: el control vuelve a ti
09:31:37.988 [app] Ejecución detenida ✋
09:31:37.989 [run] ✋ paraste tú · 2 turnos · 1 acciones · 9s · no sigo
mCurrentFocus=Window{9a9cf3d u0 com.android.settings/com.android.settings.MiuiSettings}
```

Cero `[graph] turno` después del alto.

**(d) La tarea siguiente nace suelta.** «abre la calculadora» después de los altos, desde Ajustes:

```
09:43:51.632 [freno] tarea abierta «abre la calculadora»
09:44:00.689 [graph] turno 1 · session=nuevo · HTTP 200 · 6855ms · 1 acciones
09:44:02.428 [run]   ▪ MCP launch_app {app=Calculadora} → ok
09:44:08.673 [run] ■ 2 turnos · 1 acciones · 16s · Calculadora abierta.
mCurrentFocus=Window{2b1ae5e u0 com.miui.calculator/com.miui.calculator.cal.CalculatorActivity}
```

Sin `[puerta] paraste tú, no paso …`: el alto de (c) no quedó armado.

Pantallas recorridas: Launcher, Calculadora, Ajustes (principal, Wi-Fi, Bluetooth, Pantalla) y la persiana de
notificaciones. Lo que el teléfono enseñó y queda fuera de la 3B:

- La tarea sigue abierta, con la píldora «ejecutando…» a la vista, unos 7 s después de `■` mientras la anticipación
  reintenta Gemini. Un alto ahí dice «Ejecución detenida ✋» sobre una tarea que ya estaba hecha.
- Gemini responde 429 (sin crédito) en cada corrida: el destilador de memoria, la anticipación y el
  post-proceso del workflow reintentan 3 veces cada uno (~7 s) antes de fallar.
- La notificación de ejecución sale colapsada en MIUI: el botón «⏹ Detener» solo se ve al expandirla.
- La enseñanza pasiva graba como paso del workflow un toque de la persona durante la corrida
  (`[workflow] step 2: (sin etiqueta)` a 09:25:39.792, el toque que cayó en la lista de Wi-Fi).
- `open_settings {section=wifi}` devuelve `ok` aunque la tarea de Ajustes, parada en Bluetooth, no cambie de
  pantalla.

---

### Fase 3C — el tope de dos intentos y la cuenta por petición (promesas 310-315)

Estado: implementada en `yokh/precision-tope` (310-315 verdes; cada una se vio ROJA primero y con un
sabotaje real). Nace de `U-Windows-App/windows-client/src/Voice/TopeDeIntentos.cs` y `CuentaDelTurno.cs`
(spec 017 de U, promesas 204, 205 y 207). Se copió el comportamiento y el porqué, no el archivo. Entró a
`yokh/precision` con el merge `297a2c6`. Desde la revisión de 3A-3C, parte 2, la app comparte un tope y una cuenta
por proceso (ver «Tope y cuenta en la app»); lo que dice qué nodo se toca y si la pantalla cambió todavía no: eso es 3E.

En `core/src/commonMain/kotlin/graph/core/precision/`:

- `TopeDeIntentos.kt` — puro, `MAXIMO = 2` por petición. Vigila tocar y escribir; mirar no cuenta.
  - **El destino** de un toque es el `selector` del nodo bajo el punto (el que se toca de verdad); sin
    nodo, la celda de 48 dp `celda:<x div celdaPx>,<y div celdaPx>`, con `celdaPx` sacado de la densidad
    (`TopeDeIntentos.celdaPx(densidad)`). Al escribir cuenta el campo tal como se pidió: por nombre, el
    nombre aplanado como cualquier otro («Teléfono» y «TELÉFONO » son un campo, como en U); por coordenada
    (la puerta), la celda del punto pedido y no el nodo que lo resuelve. Tocar y
    escribir en el mismo sitio son destinos distintos. Por nombre (`tapLabel`, la voz), el nombre aplanado
    (minúsculas, sin tildes, espacios juntos).
  - **`which`** solo cuenta si en la petición hubo una lista de homónimos de ese nombre, y se lee como
    número (`"02"` y `"+2"` son el 2). Con candidatos, el número lleva al selector del candidato: es el
    mismo destino que tocar ese nodo por coordenada. Sin lista, con una lista sin candidatos (no hay entre qué
    elegir; U abría `nombre#N` y dejaba doce toques al mismo botón con `which=1…6`), o fuera de rango, no abre
    destino nuevo.
  - **Fallo** es una excepción o un intento sin logro. **Logro** es que la acción se dio y escribió o
    cambió la huella. Una lista de homónimos no es intento. Un toque que se dio sin huella con que
    juzgar no cuenta como fallo: el tope nunca frena por adivinar.
  - **La tercera** al mismo destino no se ejecuta: «no lo intento una tercera vez: «X» ya falló dos veces
    en esta petición — 1) … · 2) …», y la vía: con lista, «prueba OTRO candidato con which»; sin lista,
    «Cambia de vía: mira la pantalla y toca otra cosa, o dile al usuario qué está pasando.» (al escribir,
    «escribe en otro campo»).
- `CuentaDePeticion.kt` — pura, reloj `TimeSource` inyectado. Emite en el log
  `peticion: llamadas=N distintas=N intentos_max=N «destino» primera=X ms ultima=Y ms desde_peticion=Z ms rechazadas=N retiradas=N`.
  El denominador es lo pedido: lo rechazado y lo retirado también son llamadas. «Actuó» es lo que se
  sabe: sin acción que actuó, `primera=— ultima=—`; sin llamadas no se emite. `abrePeticion(quien, tope,
  cuenta)` es el único sitio que decide qué abre una petición: la persona sí; un aviso del sistema no
  (en U el eco del altavoz vaciaba el tope a mitad de una petición).
- `Puerta.kt`, aditivo y con defaults `null`: `nodoEn`, `huella`, `tope`, `cuenta` y `asentar`. En `tap`,
  `type` y `tapLabel`, después del freno: se consulta el tope; si rechaza, no se toca el teléfono, se
  devuelve `false` y el log dice `tope: no paso «…»: <rechazo>`. Si pasa: huella antes, se actúa,
  `asentar()`, huella después, y el resultado va al tope y a la cuenta. Las demás entradas solo cuentan
  como llamadas (actuó = devolvió `true`). Sin tope ni cuenta, la puerta es la de 3A.

**Diferencia deliberada con U: el tope también frena los toques por coordenada de Graph, y la huella
que decide «cambió» incluye los textos visibles de la ventana activa.** U solo aplica el tope a sus
herramientas por etiqueta (`map_take`, `map_type`) y su «Guardar» que no cambia la pantalla se frena a la
tercera. En Android Graph toca por coordenada, así que el tope tiene que estar en la puerta; y con una
huella sin textos (paquete, ventana, ids, etiquetas accionables), apretar tres veces el «7» de la
calculadora —cambia el display, no los botones— contaría como tercer intento fallido y bloquearía a la
persona. Un falso «cambió» solo afloja la protección; un falso «no cambió» bloquea, que es el lado
peligroso. La huella la construye la app (3D/3E) con los textos dentro; la 312 lo juzga con una
calculadora cuya huella sin textos no cambia.

**No se espera con sleeps fijos.** `asentar: suspend () -> Unit = {}` se llama antes de la huella de
después; esperar a que la pantalla se asiente (dos lecturas iguales con techo, promesa 169 de U) lo
cablea 3E.

**Tope y cuenta en la app (revisión de 3A-3C, parte 2).** Hasta aquí `ArmadoDeEjecucion` armaba cada `Puerta` sin tope
ni cuenta, y una nueva en cada `arma`: cada ronda de reencaminado, cada paso consciente, cada catálogo. Ahora el armado
recibe `tope` y `cuenta` (parámetros con default `null` al final) y los pone en todas sus puertas, y `Ejecucion` tiene uno
de cada por proceso, como el freno (la celda sale de `Resources.getSystem().displayMetrics.density`). La petición es la
corrida de fuera: `correr` llama `abrePeticion(PERSONA, …)` al abrir la tarea —el tope vuelve a cero y la cuenta mide desde
ahí— y `cuenta.cerrar()` al acabar, bien, parada o reventada, así la línea `peticion:` cae en la sesión de telemetría de su
corrida y no en la siguiente. Una corrida rechazada encima no abre ni cierra nada, y `pasoConsciente` no reinicia (320). Un
audio nuevo durante la ejecución reencamina dentro de la misma corrida y es la misma petición: el objetivo se reinterpreta
junto, no es otro pedido; la acción anticipada, igual.

Lo que queda para **3E** es lo que alimenta al tope desde el teléfono: `nodoEn` (el nodo vivo bajo el punto), `huella` (con
los textos visibles de la ventana activa) y `asentar` (sin sleeps fijos). Sin ellos, en el teléfono el destino de un toque es
su celda de 48 dp, y un toque que se dio nunca es fallo ni «actuó» (sin huella no se juzga si cambió): hoy el tope solo frena
a la tercera un toque, una escritura o una etiqueta que no se dieron (`false`) o reventaron, y `primera=` mide la primera
escritura que escribió o la primera entrada no vigilada que devolvió `true`.

**Diferencias con U en la cuenta**, a propósito o por ahora:
- U cuenta intentos por destino también en `map_go_to`, `map_open_app`, `map_unblock` y `file_open` (su
  `TopeDeIntentos.Acciones`). Android solo en lo que vigila el tope —tocar, escribir y tocar por etiqueta—: `open_app`,
  `launch_app`, `open_settings` y el resto cuentan como llamadas, sin destino. Un `open_app` repetido no sube `intentos_max`.
- Sin destinos, U escribe `intentos_max=0` y Android `intentos_max=0 «—»`: la columna del destino está siempre, y la línea se
  lee con un solo patrón.

Los sabotajes de la parte 2, uno por arreglo, aplicados sobre `8f597f2` y revertidos con `git checkout`:

| Sabotaje | Qué rompe | Rojo |
|---|---|---|
| S313 | `which` con una lista sin candidatos vuelve a abrir `nombre#N` | 313 |
| S311 | escribir por nombre no aplana el campo | 311 |
| S7b | el tope se mira antes que el freno, con el mismo log y la misma cuenta | 310 |
| S6 | una entrada frenada (alto al leer la huella o cancelación en el `tap`) suma un fallo | 310 |
| S5 | `abrePeticion` llama `cerrar()` en vez de `nuevaPeticion()` | 315 |
| S11 | sin huella la cuenta marca «actuó» (`!= false`) | 312 |
| S4 | la cuenta emite con cero llamadas si hubo retiradas o rechazos | 315 |
| S8a | una puerta con un tope nuevo en cada `arma` | 320 |
| S8b | `pasoConsciente` abre otra petición | 320 |
| S8c | `correr` no abre la petición | 320 |
| S8d | `correr` abre la petición antes de mirar si hay otra corrida | 320 |
| S8e | `correr` no cierra la petición al acabar | 320 |
| S8f | `Ejecucion` no da el tope ni la cuenta al armado | 320 |
| S9a | `anticipate` propone sin `Ejecucion.sigue()` tras `consider` | 308 |
| S9b | `Ejecucion.sigue()` dentro de un `runCatching` que se traga la parada | 308 |
| S9c | un `private object Ejecucion { fun sigue() = Unit }` dentro de `GraphApp` | 308 |

Límites dichos:
- El rechazo no llega al modelo: la puerta devuelve `false` y el motor lo traduce a «no se pudo
  ejecutar la acción» (mismo límite que 3A). Desde la revisión de 3A-3C tampoco va al log, que sale del
  teléfono (promesa 317): el log dice la acción, «ya falló dos veces» y el destino sellado por su estructura con la llave del proceso, o como su tipo y su largo; el texto
  entero solo existe en `TopeDeIntentos.rechazo` hasta que 3E se lo lleve al modelo.
- `tapLabel` cuenta por el nombre pedido, no por el nodo que resuelve el reproductor: la puerta no ve
  ese nodo. Tocar «Guardar» por etiqueta y por coordenada son dos destinos hasta que 3D lo resuelva.
- Sin `huella`, un toque que se dio no se juzga: ni fallo para el tope ni «actuó» para la cuenta.
- Sin candado (el del freno llegó con la promesa 319; el tope y la cuenta no lo tienen): dos entradas
  simultáneas desde hilos distintos podrían contarse mal. Desde la parte 2 hay uno de cada por proceso, pero una sola
  corrida de fuera toca a la vez (318) y el catálogo de la anticipación arma su puerta sin actuar.

## Lo que NO entra, y por qué

- **Homónimos** (3D) y **lo que alimenta el tope desde el teléfono** —`nodoEn`, `huella` y `asentar`— y **que el rechazo
  llegue al modelo** (3E). El tope de dos intentos y la línea `peticion:` ya entraron: 3C, y cableados en la app en la
  revisión de 3A-3C, parte 2.
- **Que el motor abra la tarea**: la abre quien arma la corrida (3B). Un motor que la abriera la
  cerraría también en el step consciente de un workflow, a mitad de la corrida de fuera.

## Límites conocidos

- El texto «no ejecutado: no hay tarea abierta» no llega al modelo: las interfaces del teléfono
  devuelven `Boolean` y el motor traduce `false` a «no se pudo ejecutar la acción». El porqué queda
  en el log. Si 3B necesita que el modelo lo lea, es un cambio del motor con su promesa.
- `Freno` mira y cambia su estado en un `Candado` (`expect`/`actual`, `synchronized` en jvm) con el log y el
  aviso dentro: el aviso corre con el candado cerrado, así que tiene que ser rápido y no esperar a otro hilo
  que pida el alto (el de la app lanza el globo en su scope y vuelve). El candado es reentrante para el mismo hilo, no
  para otro: un aviso que espera (un `join`, un `runBlocking`, un `speak` que se bloquea hasta el hilo de la UI) a un hilo
  que llama `pide`, `termine` o `empezar` cuelga los dos para siempre, porque ese hilo espera el candado que tiene el aviso.
- Hay **una** corrida de fuera por proceso: la segunda (la burbuja y la app principal a la vez) no se abre, dice
  «ya hay una tarea en curso» y no paga nada (318). `GraphApp.run` lo mira antes de pedir el nombre y de abrir la sesión
  de telemetría, y el armado lo decide de verdad al abrir: el destilador de memoria, el contexto pendiente de voz, el pedido
  de `goalPrompts` y la ventana de contexto los hace solo quien abrió, dentro de `Ejecucion.correr` (308). La sesión de
  telemetría (`Telemetry.promptStarted`) sigue antes de abrir, pendiente de la decisión sobre telemetría: una corrida que se
  cruza entre mirar y abrir todavía cambia el prompt en curso de la telemetría y, al rechazarse, lo deja en nulo.
- El log de la ejecución (puerta, tope, cuenta, motor, freno, MCP y workflows) no lleva lo que la persona
  escribe, pide o ve, ni un sello que se pueda revertir o seguir de un proceso a otro (317), pero la app sigue mandando el prompt por su cuenta: `Telemetry.promptStarted` y
  líneas propias de la app como `[app] Pídeme: …` o `＋ audio durante ejecución: …`. Eso queda fuera de esta spec.
- Cortar el trabajo tras la gracia abandona el turno de Graph en vuelo (promesa 14: no es fallo de red ni se
  reintenta); ese turno pudo cobrarse.
- «ya hay una tarea en curso» se ve como una respuesta más: `MainActivity` la escribe en su log como cualquier resultado
  (una línea de la app, pendiente de la decisión sobre telemetría), `AssistActivity` la pone en un globo normal (y en
  `actOn` no la muestra) y la burbuja la saca en el mismo toast. Distinguirla pide un globo de error que hoy no existe.
- La reunión (`VoiceDock`) cancela su cola de tareas al terminar sin pedir el alto: es desmontaje, no una orden
  de parar, y la puerta igual deja de dejar pasar en cuanto `correr` suelta.
