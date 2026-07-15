# Runbook de producción — de cero a un usuario usándolo

Todo lo que hay que hacer para dejar el sistema desplegado y en manos de un usuario, funcionando.
Orden recomendado. Marca cada paso.

Piezas y dónde vive cada una:

| Pieza | Carpeta | Dónde corre en producción |
|---|---|---|
| Cerebro (backend) | `backend/` | **Vercel** (tú lo despliegas) |
| Webapp de clientes | *(repo de terceros)* | **Vercel** (ya desplegada: `miracle-web-umber.vercel.app`) |
| Asistente / carita (WPF) | `windows-client/` | Se compila a `U.exe` y se empaqueta dentro del instalador |
| Caparazón Windows | `windows-shell/` | Se compila a `Miracle Setup.exe` (lo instala el usuario) |
| Cliente macOS (opcional) | `macos-client/` | `U.app` en el Mac del usuario |

---

## Fase 0 — Decisiones antes de empezar

1. **Proveedor del cerebro**: `gemini` (tienes key) u `openai`. Este runbook asume **gemini**.
2. **Autenticación del backend** (importante, ver Fase 1.4): decide si proteges el endpoint con
   `CLIENT_TOKEN`. **Recomendado sí** para producción.
3. **Firma de código** (Fase 4.4): sin certificado, Windows SmartScreen avisará al instalar. Para
   producción "seria" consigue un certificado de firma (OV/EV). Se puede lanzar sin firmar (el usuario
   pulsa "Más información → Ejecutar de todas formas"), pero genera fricción y desconfianza.
4. **Runtime de .NET**: decide si el `U.exe` es *framework-dependent* (pesa poco, el usuario necesita
   el .NET Desktop Runtime 8) o *self-contained* (pesa ~150 MB, no necesita nada). Para poner en manos
   de un usuario **recomiendo self-contained** (Fase 3).

---

## Fase 1 — Backend en producción (Vercel)

El código está en `backend/` dentro de este repo.

### 1.1 Importar en Vercel
- vercel.com → **Add New… → Project** → importa el repo → **Root Directory = `backend`**.
- (Alternativa CLI: `cd backend && npm i -g vercel && vercel link`.)

### 1.2 Variables de entorno (Settings → Environment Variables, scope *Production*)

| Variable | Valor | Obligatoria |
|---|---|---|
| `PROVIDER` | `gemini` | sí |
| `GEMINI_API_KEY` | tu key de Google (Gemini) | sí |
| `GEMINI_MODEL` | `gemini-3.5-flash` | recomendado |
| `SESSION_SECRET` | una cadena aleatoria larga (p.ej. `openssl rand -hex 32`) | **sí** (sin ella la firma de sesión usa un default inseguro) |
| `CLIENT_TOKEN` | un token secreto (p.ej. `openssl rand -hex 24`) | recomendado (ver 1.4) |

> Si algún día cambias a OpenAI: `PROVIDER=openai`, `OPENAI_API_KEY=…`, opcional `OPENAI_MODEL`, `EFFORT=low`.

### 1.3 Desplegar
- CLI: `vercel deploy --prod`. O push a la rama conectada.
- **Anota la URL**: `https://<tu-backend>.vercel.app` ← este es el **link del backend**.

### 1.4 Seguridad del endpoint (léelo)
El endpoint `/api/agent/turn` consume tu key de LLM en cada llamada. La key **nunca sale del servidor**
(bien), pero si el endpoint queda **abierto**, cualquiera que tenga la URL puede gastar tu presupuesto.
- **Mínimo para producción**: pon `CLIENT_TOKEN`. El cliente lo manda como `Authorization: Bearer …`.
  Es un candado suave: hay que embeberlo en el cliente distribuido, así que un usuario determinado
  podría extraerlo — pero frena el abuso casual y bots.
- **Fuerte (siguiente iteración)**: validar en el backend el JWT de la sesión de Supabase del usuario
  (el mismo login de la webapp) en vez de un token compartido. Recomendado cuando escales.
- Añade también un límite de gasto/uso en el panel de Google (Gemini) por si acaso.

### 1.5 Verificar
```bash
curl https://<tu-backend>.vercel.app/api/health
# → {"ok":true,"provider":"gemini","model":"gemini-3.5-flash","configured":true,"authRequired":true}
```
`configured:true` = la key está. `authRequired:true` = `CLIENT_TOKEN` activo.

---

## Fase 2 — Verificar la webapp de clientes

El caparazón carga la webapp por URL; su producción es responsabilidad de su repo.
1. Confirma su URL de producción (por defecto el caparazón usa `https://miracle-web-umber.vercel.app`).
2. Ábrela en un navegador: debe cargar, permitir login (Supabase) y funcionar.
3. Si la URL real es otra, la fijarás en Fase 4.2.

---

## Fase 3 — Compilar el asistente (la carita, `U.exe`)

En una máquina **Windows 10/11 con .NET 8 SDK**.

### 3.1 Fijar el backend por defecto en el cliente (para que el usuario no configure nada)
Edita `windows-client/src/Config.cs`:
```csharp
public string BackendUrl { get; set; } = "https://<tu-backend>.vercel.app";  // el link de la Fase 1.3
public string? ClientToken { get; set; } = "<el CLIENT_TOKEN de la Fase 1.2>"; // si activaste auth
```
Así al instalar, la carita ya apunta a producción sin que el usuario toque nada.

### 3.2 Publicar
Framework-dependent (liviano; requiere .NET Desktop Runtime 8 en el PC del usuario):
```powershell
dotnet publish windows-client\WindowsClient.csproj -c Release -r win-x64 --self-contained false -o out\assistant
```
**Recomendado — self-contained (sin dependencias para el usuario):**
```powershell
dotnet publish windows-client\WindowsClient.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -o out\assistant
```
Debe quedar `out\assistant\U.exe`. Pruébalo suelto: doble clic → aparece la carita → escribe algo.

---

## Fase 4 — Compilar el instalador Windows (caparazón Electron)

En Windows con **Node 18+** (y el .NET SDK de la Fase 3).

### 4.1 Empaquetar el asistente dentro del caparazón
```powershell
cd windows-shell
powershell -ExecutionPolicy Bypass -File scripts\prepare-assistant.ps1
```
(Si en la Fase 3 usaste self-contained, edita el script para usar los mismos flags, o copia a mano tu
`out\assistant\*` a `windows-shell\resources\assistant\`.)

### 4.2 Fijar la URL de la webapp (si no es la de por defecto)
Edita `windows-shell/config.js` → `webappUrl: 'https://<tu-webapp>.vercel.app'`.

### 4.3 Icono (opcional pero recomendado)
Coloca `windows-shell/assets/icon.ico` (256×256).

### 4.4 Generar el instalador
```powershell
npm install
npm run dist        # → windows-shell\dist\Miracle Setup 0.1.0.exe
```
Este `.exe` es lo que entregas al usuario. Contiene el caparazón + `U.exe`.

**Firma de código** (Fase 0.3): con certificado, configura en `package.json → build.win`
(`certificateFile`/`certificatePassword` o firma con `signtool` post-build). Sin firma, funciona pero
SmartScreen avisa.

---

## Fase 5 — (Opcional) Cliente macOS

En un Mac con Xcode: editar `macos-client/Sources/U/Config.swift` (backendUrl/clientToken como en 3.1),
luego `cd macos-client && ./make-app.sh && open U.app`. El usuario concede Accesibilidad + Grabación de
pantalla la primera vez.

---

## Fase 6 — Poner en manos del usuario

1. Entrega `Miracle Setup 0.1.0.exe` (web, correo, tu canal de distribución).
2. El usuario ejecuta el instalador (si no firmaste: "Más información → Ejecutar de todas formas").
3. Primera apertura:
   - Ve la **webapp** completa y hace login (Supabase).
   - Pulsa el **botón "Ü"** (abajo a la derecha) → aparece la **carita flotante**.
   - **Permisos**: para controlar el PC, la carita usa envío de teclado/ratón y lectura de UI (UIA);
     en Windows no requiere permiso extra para apps normales. Para controlar apps que corren **como
     administrador**, la carita debe ejecutarse elevada (ver nota al final).
4. Le pide algo por texto/voz → la carita ejecuta.

---

## Fase 7 — Verificación end-to-end (haz esto antes de entregar)

1. `curl …/api/health` → `configured:true`.
2. Backend suelto: un turno con curl devuelve `actions` (ver `backend/README.md`).
3. `U.exe` suelto → carita aparece, apunta al backend de producción, ejecuta una orden simple
   (p.ej. *"abre el navegador y busca gatos"*).
4. Instalador en una **máquina limpia** (sin .NET si fuiste self-contained): instala, abre, webapp
   carga, login funciona, botón Ü lanza la carita, la carita controla el PC.
5. Prueba el flujo de actualización (Fase 8).

---

## Fase 8 — Actualizaciones (fricción por tipo de cambio)

| Cambia… | Qué haces | Qué ve el usuario |
|---|---|---|
| **La webapp** | push a su repo → Vercel redespliega solo | Al reabrir/`Ctrl+R`, ya ve lo nuevo. **Nada que reinstalar.** |
| **El cerebro** (prompts, MCP, provider, modelo) | cambias env vars o pusheas `backend/` → Vercel redespliega | Efecto inmediato en la siguiente orden. **Nada que reinstalar.** |
| **La carita o el caparazón** | recompilas (Fases 3–4) y republicas el instalador | Reinstala, o auto-update si configuraste `electron-updater` (ver `windows-shell/README.md`). |

El grueso de la evolución (webapp + cerebro) llega **sin tocar al usuario**. Solo cambios de la app
nativa requieren un nuevo instalador.

---

## Notas y límites conocidos (para lanzar con los ojos abiertos)

- **Memoria/aprendizaje del cerebro**: hoy los stores son en memoria y se reinician entre *cold starts*
  de Vercel. El bucle de ejecución funciona perfecto (es stateless por request); lo que aún no persiste
  es la memoria personal y los workflows aprendidos. Para activarlos en serio: conectar Supabase/KV en
  `backend/src/container.ts` (interfaces ya listas). No bloquea el lanzamiento del control por voz/texto.
- **Coste**: cada orden consume tokens de Gemini. Pon límite de gasto en Google y considera
  `CLIENT_TOKEN` + rate limiting.
- **Apps elevadas (admin)**: para que la carita controle ventanas de apps que corren como
  administrador, el `U.exe` debe correr elevado (o firmarse con `uiAccess`). Para apps normales no hace
  falta.
- **Firma de código**: sin certificado, SmartScreen/Defender pueden marcar el instalador. Consíguelo
  antes de una distribución amplia.
- **Los dos clientes nativos (WPF/Swift) no se compilaron en el entorno de desarrollo** (era Linux); el
  primer build en Windows/macOS puede pedir ajustes menores.
