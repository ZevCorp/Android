# Publicar actualizaciones (Ü Windows)

Cómo sacar una versión nueva de la carita (`U.exe`) y que le llegue **sola** a los clientes ya
instalados. El equivalente de [`RELEASING.md`](RELEASING.md), que cubre la app Android.

> Para el **backend** no hay nada que hacer: vive en Vercel y se actualiza con un `git push`. Este
> documento es solo para el cliente Windows, que vive como `.exe` en la máquina del usuario.

---

## 1. Cómo funciona (resumen)

- La carita se instala **una sola vez** con `U-Setup.exe`, en `%LocalAppData%\U` (**sin pedir admin**).
- Usa **[Velopack](https://velopack.io)**: al arrancar y cada ~30 min consulta el feed, y si hay versión
  nueva **la descarga en segundo plano** sin interrumpir al usuario.
- Cuando está descargada, la carita muestra una pastilla azul: **"⬇ Versión X lista — reiniciar"**.
  - Si el cliente la toca → se actualiza y reinicia en el momento.
  - Si la ignora → se instala sola **al cerrar Ü**. El siguiente arranque ya es la versión nueva.
- El feed es el **bucket público `windows`** de Supabase. Publicar = subir 2-3 archivos ahí.
- Después de la primera versión, las descargas son **deltas** (KB, no los ~70 MB completos).

Código relevante:
- `windows-client/src/Update/Updater.cs` — el sondeo, la descarga y el aplicar.
- `windows-client/App.xaml.cs` — `VelopackApp.Build().Run()`, lo primero del proceso (obligatorio).
- `windows-client/src/Ui/FaceWindow.xaml` — la pastilla (`UpdateBtn`).
- `windows-client/src/Config.cs` — `UpdateFeedUrl`.

---

## 2. Infraestructura (ya creada, no hay que volver a hacerla)

- Proyecto Supabase: **`miracle-app`** · ref **`zyvfamlhlmztliexvmej`** (el mismo de Android).
- **Bucket público `windows`**. URL base del feed:
  `https://zyvfamlhlmztliexvmej.supabase.co/storage/v1/object/public/windows`
- Es público a propósito: el updater lee sin credenciales, igual que el bucket `apks`. Ahí solo viajan
  binarios del **cliente tonto**, que no contienen prompts, catálogo MCP ni la key del modelo — todo eso
  vive en el backend. Lo único sensible que viaja embebido es la key de Gemini de 🎓 (ver `Config.cs`);
  si eso preocupa, dejá `GeminiApiKey` vacía en el build que publiques.

**No hay tabla ni Edge Function**, a diferencia de Android: Velopack lee un JSON estático del bucket.
No hay token de admin que proteger — el control de acceso es *quién puede subir al bucket*.

---

## 3. Sacar una versión nueva (paso a paso)

1. **Compilá y empaquetá** (desde `windows-client`, en Windows con .NET 8 SDK):

   ```powershell
   dotnet tool install -g vpk        # solo la primera vez
   .\scripts\publish-release.ps1 -Version 1.0.1
   ```

   La versión **debe ser mayor** que la publicada (SemVer). El script imprime qué subir.

2. **Subí al bucket `windows`** (panel de Supabase → Storage → `windows` → Upload), desde
   `out\releases`:
   - `releases.win.json` ← **el índice; siempre, o nadie ve la versión nueva**
   - `U-<version>-full.nupkg`
   - `U-<version>-delta.nupkg` (si existe)

   > **No borres los `.nupkg` viejos**: son la base contra la que se aplican los deltas.

3. Listo. Cada cliente lo recoge en ~30 min o al siguiente arranque.

**Cliente nuevo** (primera instalación): mandale `out\releases\U-Setup.exe`. A partir de ahí no vuelve
a instalar nada nunca.

---

## 4. Verificar que salió bien

```powershell
# El índice tiene que responder y nombrar la versión nueva:
curl.exe https://zyvfamlhlmztliexvmej.supabase.co/storage/v1/object/public/windows/releases.win.json
```

En la máquina del cliente: el panel **Backend** de la carita muestra `Versión X` abajo, y 📜 (Logs)
tiene las líneas con tag `update`.

---

## 5. Checklist

- [ ] Versión incrementada respecto a la publicada.
- [ ] `publish-release.ps1` terminó sin errores.
- [ ] `releases.win.json` subido (**el que más se olvida**).
- [ ] `.nupkg` nuevo subido; los viejos **siguen** en el bucket.
- [ ] `curl` al `releases.win.json` devuelve la versión nueva.

---

## 6. Detalles que muerden

- **Firma de código**: sin certificado, SmartScreen avisa al correr `U-Setup.exe` la primera vez
  (Fase 0.3 de `PRODUCTION.md`). El auto-update posterior **no** vuelve a mostrar el aviso.
- **`%LocalAppData%`, no `Program Files`**: Velopack no soporta directorios privilegiados. Es a favor
  nuestro — actualiza sin UAC.
- **Nada de `PublishSingleFile`**: `ScreenRecorderLib` es mixto C++/CLI y no lo soporta. Velopack
  empaqueta la carpeta, así que no hace falta.
- **En desarrollo el updater se apaga solo**: con `dotnet run` no hay instalación detrás, `IsInstalled`
  es false y `Updater` no hace nada. Para probar el update de verdad hay que instalar con el Setup.
- **La config del usuario sobrevive**: vive en `%APPDATA%\U\config.json`, fuera de la carpeta de
  instalación que Velopack reemplaza.

## 7. Mejora futura: publicar sin subir a mano

Velopack trae `vpk upload s3`, y el storage de Supabase es compatible con S3. Con las credenciales S3
del proyecto, el paso 2 se convertiría en un flag del script y publicar sería **un solo comando**. No
implementado: hace falta generar esas credenciales en el panel de Supabase.
