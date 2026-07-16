# Runbook de producción — de cero a un usuario usándolo

Todo lo que hay que hacer para dejar el sistema desplegado y en manos de un usuario, funcionando.
Orden recomendado. Marca cada paso.

Piezas y dónde vive cada una:

| Pieza | Carpeta | Dónde corre en producción |
|---|---|---|
| Cerebro (backend) | `backend/` | **Vercel** (tú lo despliegas) |
| Asistente / carita (WPF) | `windows-client/` | Se compila a `U.exe` — **es lo que se entrega al usuario**, sin instalador ni webapp embebida |
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
5. **Visual C++ Redistributable**: la enseñanza por video (🎓) usa `ScreenRecorderLib`, un ensamblado
   nativo C++ que depende del runtime de Visual C++. Si la máquina del usuario no lo tiene, 🎓 falla
   con `"No se puede encontrar el módulo especificado"` al cargar `ScreenRecorderLib.dll` — el resto
   del asistente funciona normal, solo 🎓 se rompe. Instalar antes de entregar:
   https://aka.ms/vs/17/release/vc_redist.x64.exe (Microsoft, oficial). No hay forma de embeberlo en
   el `.exe`; es un instalable aparte que corre una sola vez por máquina.

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
| `SUPABASE_URL` | `https://<tu-proyecto>.supabase.co` | solo para archivar los videos de 🎓 |
| `SUPABASE_SERVICE_ROLE_KEY` | la `service_role` key (Supabase → Settings → API) | solo para archivar los videos de 🎓 |
| `SUPABASE_VIDEO_BUCKET` | `teach-videos` (default si se omite) | no |

> Si algún día cambias a OpenAI: `PROVIDER=openai`, `OPENAI_API_KEY=…`, opcional `OPENAI_MODEL`, `EFFORT=low`.

**`GEMINI_API_KEY` la usa también la enseñanza por video (🎓).** El cliente NO tiene ninguna key: el
backend firma las subidas y llama al modelo. Por eso un usuario nuevo no configura nada — instala y
🎓 funciona.

> **La enseñanza por video SIEMPRE usa Gemini**, aunque el cerebro corra con `PROVIDER=openai` (es
> quien entiende video). O sea: si estás en OpenAI, `GEMINI_API_KEY` **sigue siendo obligatoria** o
> 🎓 devuelve 500 explicando que falta. `GEMINI_MODEL` es el modelo que usa 🎓; `OPENAI_MODEL` no
> lo afecta.

**Las dos variables de Supabase** son solo para archivar los mp4 que graban los usuarios en el bucket
privado `teach-videos`, y así poder verlos desde el dashboard. Si faltan, 🎓 sigue funcionando
igual (el video se procesa y se guarda en el disco del usuario) — solo perdemos el archivo central;
el backend lo reporta en `archiveError` y queda en el registro del cliente. La `service_role` key
salta RLS y da acceso total al proyecto: va **solo** en Vercel, nunca en el cliente ni en el repo.

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

## Fase 3 — Compilar el asistente (la carita, `U.exe`)

En una máquina **Windows 10/11 con .NET 8 SDK**.

### 3.1 Fijar el backend por defecto en el cliente (para que el usuario no configure nada)
Edita `windows-client/src/Config.cs`:
```csharp
public string BackendUrl { get; set; } = "https://<tu-backend>.vercel.app";  // el link de la Fase 1.3
public string? ClientToken { get; set; } = "<el CLIENT_TOKEN de la Fase 1.2>"; // si activaste auth
```
Así al instalar, la carita ya apunta a producción sin que el usuario toque nada. **No hay ninguna key
de modelo que fijar acá**: la enseñanza por video (🎓) pasa por el backend, que es quien tiene la key
(Fase 1.2). Un usuario nuevo instala y 🎓 funciona sin configurar nada.

### 3.2 Publicar

**NO uses `-p:PublishSingleFile=true`.** `ScreenRecorderLib` (enseñanza por video, 🎓) es un ensamblado
mixto C++/CLI, y `PublishSingleFile` no soporta ensamblados mixtos — el `.exe` arranca pero
`ScreenRecorderLib.dll` no carga (`System.BadImageFormatException`) en cuanto tocás 🎓. Publicá
carpeta completa (self-contained, sin dependencias para el usuario, pero varios archivos en vez de uno):
```powershell
dotnet publish windows-client\WindowsClient.csproj -c Release -r win-x64 --self-contained true -o out\assistant
```
Framework-dependent (más liviano; requiere .NET Desktop Runtime 8 en el PC del usuario):
```powershell
dotnet publish windows-client\WindowsClient.csproj -c Release -r win-x64 --self-contained false -o out\assistant
```
Queda `out\assistant\U.exe` **junto a sus DLLs** (incluida `ScreenRecorderLib.dll`) — no se puede mover
`U.exe` solo, tiene que viajar toda la carpeta. Pruébalo suelto: doble clic → aparece la carita →
escribe algo → probá también 🎓 Enseñar antes de dar por bueno el build.

> **Para distribuir no uses esta carpeta ni un zip a mano**: corré
> `windows-client\scripts\publish-release.ps1 -Version X.Y.Z`, que hace este mismo `publish` y además
> arma el instalador y el paquete de auto-update. Ver [`RELEASING-WINDOWS.md`](RELEASING-WINDOWS.md).

**Firma de código** (Fase 0.3): sin certificado, Windows SmartScreen avisará al ejecutar `U.exe` la
primera vez ("Más información → Ejecutar de todas formas"). En Windows 11 con **Control Inteligente
de Aplicaciones** activo, el bloqueo es más agresivo — puede impedir la ejecución directamente en vez
de solo avisar (Seguridad de Windows → Protección contra virus y amenazas → Historial de protección
para permitirlo caso por caso). Con certificado (OV/EV), fírmalo con
`signtool sign /f cert.pfx /p <pass> /t http://timestamp.digicert.com out\assistant\U.exe` — esto
resuelve ambos avisos para una distribución real.

---

## Fase 5 — (Opcional) Cliente macOS

En un Mac con Xcode: editar `macos-client/Sources/U/Config.swift` (backendUrl/clientToken como en 3.1),
luego `cd macos-client && ./make-app.sh && open U.app`. El usuario concede Accesibilidad + Grabación de
pantalla la primera vez.

---

## Fase 6 — Poner en manos del usuario

1. Entrega **`U-Setup.exe`** (web, correo, tu canal de distribución), que sale de
   `windows-client\scripts\publish-release.ps1` — ver [`RELEASING-WINDOWS.md`](RELEASING-WINDOWS.md).
   Instala en `%LocalAppData%\U` sin pedir admin, y es la **única** vez que el usuario instala algo: a
   partir de ahí la carita se actualiza sola.
2. El usuario lo ejecuta (si no firmaste: "Más información → Ejecutar de todas formas").
3. Primera apertura: aparece la **carita flotante**.
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
4. `U.exe` en una **máquina limpia** (sin .NET si fuiste self-contained): abre, apunta al backend
   correcto, controla el PC.
5. Prueba el flujo de actualización (Fase 8).

---

## Fase 8 — Actualizaciones (fricción por tipo de cambio)

| Cambia… | Qué haces | Qué ve el usuario |
|---|---|---|
| **El cerebro** (prompts, MCP, provider, modelo) | cambias env vars o pusheas `backend/` → Vercel redespliega | Efecto inmediato en la siguiente orden. **Nada que reinstalar.** |
| **La carita** (`windows-client/`) | `publish-release.ps1` + subir 3 archivos al bucket (ver [`RELEASING-WINDOWS.md`](RELEASING-WINDOWS.md)) | Se descarga sola; una pastilla ofrece reiniciar, o se aplica al cerrar. **Nada que reinstalar.** |

Nada obliga al usuario a reinstalar: el cerebro llega al instante y la carita se auto-actualiza
(Velopack). Lo único que se entrega a mano es el `U-Setup.exe` de la **primera** instalación.

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
