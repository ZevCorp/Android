# Plan de implementación: lo hace a la primera y se puede parar — el freno y la puerta única

Estado: **fase 3A implementada** (2026-09-14; promesas 301-306 verdes; cada una se vio ROJA con un sabotaje real) · **fase 3B implementada** (2026-09-14; promesas 307-309 y 316 verdes; 17 sabotajes y cada uno puso ROJA su promesa; la 308 juzga además el cableado de `GraphApp` y `Ejecucion`) · **Nivel 4 de la 3B pendiente**: el APK de este worktree no trae key de Graph · Nace de leer el freno de `U-Windows-App`
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
el del test (`core/src/commonTest/kotlin/graph/core/contrato/Contrato003FrenoYPuerta.kt`, método
`promesaNNN`); si cambia uno, cambia el otro en el mismo commit.

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
| 306 | `enTarea { throw Reventon("revienta") }` (una excepción que no es cancelación, para que una `Paraste` no cuente como reventón) → sale tal cual, `abierta` y `pedido` son `false`. Con un alto pedido dentro también, y la frase de devolver el control se dice. Después, un `tap` por la puerta no pasa y lo dice el log. Anidada: `enTarea("exterior") { enTarea("paso consciente") { tap }; … }` → tras el bloque de dentro la tarea sigue abierta, el `tap` siguiente pasa y el alto arma |
| 307 | Lee `app/src/main/kotlin/**/*.kt` (se busca subiendo desde el directorio del test): ningún archivo construye `ExecutionEngine(`, `Mcp(`, `WorkflowRunner(` ni `Puerta(`; `ArmadoDeEjecucion(` y `Manos(` solo en `Ejecucion.kt`; ningún argumento `phone`/`gestures`/`player` recibe `service`/`ui`/`this`. Y `ArmadoDeEjecucion` puro con falsos crudos: sin tarea abierta, el motor, una herramienta MCP y el reproductor de workflows no llegan a ningún falso |
| 308 | Fuentes: `StopReceiver` llama `Ejecucion.parar("notificación")`, la píldora de `FloatingBubble` `Ejecucion.parar("píldora")`, `stopExecution` `Ejecucion.parar("botón")`, y ningún archivo de la app cancela el trabajo de la corrida (`runJob`) ni llama `stopExecution` desde la píldora o la notificación. El cableado: `GraphApp.run` corre el motor dentro de `Ejecucion.correr` y mira `Ejecucion.sigue()` tras el motor, antes de reencaminar o anticipar; `Ejecucion.parar` pide el alto al armado y después, no en su lugar, `cortaSiNoSuelta`, sin `cancel`; `Ejecucion.correr` es `armado.correr`; y en la app hay un solo `Freno(`, en `Ejecucion.kt`. Puro: `parar` sin tarea no arma; dentro de `correr` arma el freno de esa tarea, tres órdenes distintas avisan una vez; y un turno de Graph colgado tras el alto se corta con `cortaSiNoSuelta` y la corrida termina como cancelación |
| 309 | `ArmadoDeEjecucion` con `GraphBrain` real sobre un transporte guionado que cuenta requests. Graph manda `[tap, tap, type]` o `[tap, wait, wait]` y el alto llega durante el primer `tap`: una sola acción ejecutada (una línea «▪»), un solo request a Graph y `correr` termina en `Paraste` |
| 310 | `TopeDeIntentos` puro: un toque que se dio sin cambiar la pantalla y otro que revienta hacia «Guardar» → el tercero se rechaza con «no lo intento una tercera vez: «Guardar» ya falló dos veces en esta petición — 1) … · 2) …» y «Cambia de vía…»; otro destino pasa. Y la `Puerta` de verdad: `tap`, `type` y `tapLabel` fallidos dos veces → el tercero devuelve `false`, el teléfono falso grabó dos entradas y el log dice `tope: no paso «…»: no lo intento…` |
| 311 | Puerta con `nodoEn`: dos puntos distintos (y de celdas distintas) del mismo nodo son un destino; dos nodos pequeños dentro de la misma celda son dos. Sin nodo bajo el punto, dos puntos de la misma celda de 48 dp son un destino y la celda vecina es otro; sin `nodoEn`, la celda. `celdaPx(3f)` = 144 y `celdaPx(2.75f)` = 132. Al escribir cuenta el punto pedido, no el nodo que lo resuelve: el mismo campo pedido en otra celda es otro destino |
| 312 | Calculadora: cinco toques al mismo nodo «7», cada uno cambia solo el texto del display (la huella sin textos es idéntica antes y después) → cinco entradas y cero rechazos; el cambio aparece solo después de `asentar`. «Guardar»: tres toques sin cambio → el tercero no llega. Escribir tres veces con éxito no se bloquea; un toque que no se dio cuenta como fallo aunque la pantalla cambie; tres listas de homónimos no son intento. Sin `huella`, tres toques sin juzgar no bloquean y el log lo dice una vez |
| 313 | Sin lista: dos fallos a «Descargas» → `which=1` y `which=2` se rechazan y el rechazo no nombra `which`. Con lista de «Descargas»: dos fallos al 1 → «01» y «+1» se rechazan, el 2 pasa y el rechazo dice «prueba OTRO candidato con which» con la lista; fallos a «2» frenan «02» y «+2»; una lista de «Documentos» no habilita `which` en «Descargas»; el selector del candidato tocado por coordenada es ese candidato |
| 314 | Tope con dos fallos: `abrePeticion(SISTEMA, …)` y `retirada` lo dejan rechazando y la cuenta no emite; `abrePeticion(PERSONA, …)` lo vacía (fallos y listas), emite la línea de la anterior, y por la puerta el tercer toque vuelve a llegar |
| 315 | `CuentaDePeticion` con `TestTimeSource`: línea exacta `llamadas=5 distintas=3 intentos_max=3 «celda:1,1» primera=800 ms ultima=1400 ms desde_peticion=1000 ms rechazadas=1 retiradas=1` y en el log como `peticion: …`; sin acción que actuó, `primera=— ultima=—`; un resultado sin llamada no cuenta; sin llamadas ni `cerrar` ni `nuevaPeticion` emiten. Por la puerta: tres toques a «Guardar» (uno rechazado) y un scroll dejan `llamadas=4 … rechazadas=1` |
| 316 | Un workflow de tres pasos (consciente, subconsciente, consciente) como herramienta MCP del motor armado. Sin alto: pasan `tap`, `tapLabel` y `tap` (el paso consciente anidado no cerró la corrida). Con el alto durante el `tap` del paso 1: nada más llega, el workflow no vuelve a leer la pantalla para el paso 2 ni da el paso 1 por hecho, Graph no da otro turno y `correr` termina en `Paraste` |

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

### Nivel 4 de la 3B — en el teléfono (pendiente, 2026-09-14)

Xiaomi M2101K7BL · Android 12 · APK release 0.42 armado en este worktree (HEAD `2e18c83`), instalado con
`adb install -r`. Primer intento, «abre la calculadora» escrito en «Pídeme algo». `adb logcat -s Graph:D`:

```
23:29:22.046 [app] Pídeme: abre la calculadora
23:29:22.090 [graph] no hay key de graph: ponela en el panel de desarrollador o en apikey.properties como graphApiKey
mCurrentFocus=Window{e5a51a5 u0 com.miui.home/com.miui.home.launcher.Launcher}
```

El proveedor guardado es GRAPH, pero este APK se armó sin `apikey.properties` (fuera de git; no está en este
worktree) y prefs no tiene key: la corrida se corta antes de abrir la tarea, sin tocar el teléfono ni llamar a
nadie. Falta, con la key puesta: (a) «abre la calculadora» pasando por la puerta, con `freno: tarea abierta` y la
acción; (b) «abre ajustes y entra a wifi y luego a bluetooth» parada con la píldora a mitad: `freno: alto pedido
(píldora)`, `run: ✋` y ningún `[graph] turno` después; (c) lo mismo desde la notificación.

---

### Fase 3C — el tope de dos intentos y la cuenta por petición (promesas 310-315)

Estado: implementada en `yokh/precision-tope` (310-315 verdes; cada una se vio ROJA primero y con un
sabotaje real). Nace de `U-Windows-App/windows-client/src/Voice/TopeDeIntentos.cs` y `CuentaDelTurno.cs`
(spec 017 de U, promesas 204, 205 y 207). Se copió el comportamiento y el porqué, no el archivo. La app
todavía no la cablea: eso es 3E.

En `core/src/commonMain/kotlin/graph/core/precision/`:

- `TopeDeIntentos.kt` — puro, `MAXIMO = 2` por petición. Vigila tocar y escribir; mirar no cuenta.
  - **El destino** de un toque es el `selector` del nodo bajo el punto (el que se toca de verdad); sin
    nodo, la celda de 48 dp `celda:<x div celdaPx>,<y div celdaPx>`, con `celdaPx` sacado de la densidad
    (`TopeDeIntentos.celdaPx(densidad)`). Al escribir cuenta el campo tal como se pidió: por nombre, el
    nombre; por coordenada (la puerta), la celda del punto pedido y no el nodo que lo resuelve. Tocar y
    escribir en el mismo sitio son destinos distintos. Por nombre (`tapLabel`, la voz), el nombre aplanado
    (minúsculas, sin tildes, espacios juntos).
  - **`which`** solo cuenta si en la petición hubo una lista de homónimos de ese nombre, y se lee como
    número (`"02"` y `"+2"` son el 2). Con candidatos, el número lleva al selector del candidato: es el
    mismo destino que tocar ese nodo por coordenada. Sin lista, o fuera de rango, no abre destino nuevo.
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

Límites dichos:
- El rechazo no llega al modelo: la puerta devuelve `false` y el motor lo traduce a «no se pudo
  ejecutar la acción» (mismo límite que 3A). El texto queda en el log.
- `tapLabel` cuenta por el nombre pedido, no por el nodo que resuelve el reproductor: la puerta no ve
  ese nodo. Tocar «Guardar» por etiqueta y por coordenada son dos destinos hasta que 3D lo resuelva.
- Sin `huella`, un toque que se dio no se juzga: ni fallo para el tope ni «actuó» para la cuenta.
- Como el freno, sin compare-and-set ni candados (common no los trae): dos entradas simultáneas desde
  hilos distintos podrían contarse mal. Hoy una sola corrida toca a la vez.

## Lo que NO entra, y por qué

- **Tope de intentos, homónimos, telemetría por petición**: fases 3C, 3D y 3E.
- **Que el motor abra la tarea**: la abre quien arma la corrida (3B). Un motor que la abriera la
  cerraría también en el step consciente de un workflow, a mitad de la corrida de fuera.

## Límites conocidos

- El texto «no ejecutado: no hay tarea abierta» no llega al modelo: las interfaces del teléfono
  devuelven `Boolean` y el motor traduce `false` a «no se pudo ejecutar la acción». El porqué queda
  en el log. Si 3B necesita que el modelo lo lea, es un cambio del motor con su promesa.
- `Freno` usa `@Volatile` y no compare-and-set (common no trae atómicos sin dependencia): dos `pide`
  exactamente simultáneos desde hilos distintos podrían avisar dos veces.
- Hay **una** tarea por proceso. Dos corridas de fuera simultáneas (la burbuja y la app principal a la vez)
  comparten la tarea: un alto frena las dos, y la primera que acaba la cierra; la otra ya no toca el teléfono
  y lo dice en el log. Falla cerrada, no abierta. La app ya asumía una corrida a la vez (`runJob` único).
- Cortar el trabajo tras la gracia abandona el turno de Graph en vuelo (promesa 14: no es fallo de red ni se
  reintenta); ese turno pudo cobrarse.
- La reunión (`VoiceDock`) cancela su cola de tareas al terminar sin pedir el alto: es desmontaje, no una orden
  de parar, y la puerta igual deja de dejar pasar en cuanto `correr` suelta.
