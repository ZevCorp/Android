# CLI — etapa subconsciente desde la terminal

Requiere `adb` con el teléfono conectado y la app Graph instalada (con el servicio de accesibilidad activo).

```bash
# Listar workflows aprendidos (id, nombre, steps y comando de ejecución)
cli/graph list

# Ejecutar un workflow con variables (los input_N salen de `list` o de WORKFLOWS.md)
cli/graph run wf_1778724462696 --input_3="Juan" --input_5="Pérez"

# Configurar la API key de Gemini sin abrir la app
cli/graph set-key AIza...
```

Equivalentes crudos con adb:

```bash
adb shell am broadcast -a com.zevcorp.graph.LIST -n com.zevcorp.graph/.platform.RunCommandReceiver
adb shell am broadcast -a com.zevcorp.graph.RUN  -n com.zevcorp.graph/.platform.RunCommandReceiver \
    --es id wf_1778724462696 --es input_3 "Juan"
adb logcat -s GraphCLI:I          # resultados
adb shell run-as com.zevcorp.graph cat files/WORKFLOWS.md   # catálogo legible
```
