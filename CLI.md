# CLI — etapa subconsciente desde la terminal

Los workflows de Graph son **terminal-first**: están diseñados para que un LLM (o tú) los descubra, mapee y ejecute con comandos. Requiere `adb` con el teléfono conectado y el servicio de accesibilidad de Graph activo.

## El flujo típico de un asistente operando la terminal

```bash
# 1. Descubrir qué sabe hacer el teléfono
cli/graph list
#   wf_1778… · Pedir comida en DiDi · 14 steps · 1 ramas · graph run wf_1778… [--branch configurar_direccion] --input_5="..."

# 2. Mapear la red de un workflow: ramas (cuándo activarlas), variables y estado de cada step
cli/graph info wf_1778724462696
#   proposito: Pedir comida a domicilio en DiDi Food
#   uso: graph run wf_1778… [--branch configurar_direccion] --input_5="..."
#   rama --branch configurar_direccion: primera vez o cuando cambia el lugar de entrega
#   variable --input_5: Buscar restaurante (default: "salchipapa")
#   step 1 [verde]: LAUNCH DiDi Food
#   step 2 [draft] [rama configurar_direccion]: CLICK Agregar dirección
#   ...

# 3. Ejecutar el camino que aplique (modular: el tronco siempre, las ramas según contexto)
cli/graph run wf_1778724462696 --input_5="pizza"                                  # caso normal
cli/graph run wf_1778724462696 --branch configurar_direccion --input_5="pizza"    # en casa de un amigo
```

Los steps 🟢 corren por árbol de UI sin LLM; los 🔴 se delegan puntualmente a Gemini 3.5 Flash.

## Variantes con `pick_N` (paralelos)

Cada CLICK sobre un elemento que tiene "paralelos" (otros del mismo tipo en el mismo contenedor: números, sabores, tallas) genera una variable de selección `pick_<order>`. `graph info` lista sus opciones; puedes ejecutar cualquier variante con la misma velocidad aprendida:

```bash
cli/graph info wf_calc
#   variable --pick_3: 5 (default: "5") · opciones: 5, 0, 1, 2, 3, 4, 6, 7, 8, 9, +, −, ×, =
cli/graph run wf_calc --pick_1=3 --pick_2=× --pick_3=9   # ejecuta 3×9 aunque enseñaste 5+6+7
```

## Otros comandos

```bash
cli/graph set-key AIza...        # configurar la API key sin abrir la app
```

## Equivalentes crudos con adb

```bash
adb shell am broadcast -a com.zevcorp.graph.LIST -n com.zevcorp.graph/.platform.RunCommandReceiver
adb shell am broadcast -a com.zevcorp.graph.INFO -n com.zevcorp.graph/.platform.RunCommandReceiver --es id wf_X
adb shell am broadcast -a com.zevcorp.graph.RUN  -n com.zevcorp.graph/.platform.RunCommandReceiver \
    --es id wf_X --es branches "configurar_direccion" --es input_5 "pizza"
adb logcat -s GraphCLI:I                                     # resultados
adb shell run-as com.zevcorp.graph cat files/WORKFLOWS.md    # catálogo completo
```
