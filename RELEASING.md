# Publicar actualizaciones (Graph Android)

Guía única para lanzar una nueva versión y que a **todos los usuarios** les llegue el aviso y puedan
actualizar con un toque, sin pasar por Play Store. Sirve tanto para la persona administradora como
para un **nuevo agente** que continúe el trabajo.

> ⚠️ Este documento contiene **secretos** (clave de firma, token de admin). El repositorio es
> **privado** a propósito. No lo hagas público ni compartas estos valores fuera del equipo.

---

## 1. Cómo funciona (resumen)

- La app es **sideloaded** (no está en Play Store). Actualizar = descargar e instalar un APK nuevo.
- Todas las apps consultan en **Supabase** la última versión publicada (tabla `graph_release`). Si el
  `version_code` es mayor al instalado, muestran el botón **"Actualizar"** y una **notificación**.
- El aviso llega casi como un push porque el proceso vive por el servicio de accesibilidad (sondea
  cada ~30 min y al abrir la app).
- Publicar una versión = escribir esa fila vía una **Edge Function protegida por un token de admin**.
- La instalación usa `PackageInstaller` (el sistema pide confirmar). Requiere que el usuario permita
  "instalar apps desconocidas" para Graph la primera vez.

Código relevante:
- Cliente: `app/src/main/kotlin/com/zevcorp/graph/platform/Updater.kt`,
  `InstallReceiver.kt`; UI y panel admin en `ui/MainActivity.kt` (tarjeta "Actualizaciones").
- Sondeo: `GraphApp.onCreate` (bucle cada 30 min).

---

## 2. Firma de la app (CRÍTICO)

Android **solo permite actualizar** si la versión nueva está firmada con **la misma clave** que la
instalada. Por eso todos los builds usan un keystore **fijo, versionado en el repo**:

- Keystore: `app/graph-release.jks`
- storePassword: `graphupdate`
- keyAlias: `graph`
- keyPassword: `graphupdate`
- Certificado: `CN=Graph, O=ZevCorp, C=US`

Configurado en `app/build.gradle.kts` (`signingConfigs.shared`), aplicado a los build types `debug` y
`release`. **Nunca** cambies esta clave: si lo haces, ningún usuario podrá actualizar (tendrían que
desinstalar y reinstalar). Si el keystore se pierde, se pierde la capacidad de actualizar la base
instalada.

---

## 3. Backend en Supabase

- Proyecto: **`miracle-app`** · ref **`zyvfamlhlmztliexvmej`** · región us-east-1
- URL base: `https://zyvfamlhlmztliexvmej.supabase.co`
- Anon/publishable key (la usa la app, no es secreta): `sb_publishable_qroW231Ts7UYAEgr_f5cnQ_3SrW2ZrI`

**Tabla `graph_release`** (fila única `id = 1`): `version_code`, `version_name`, `apk_url`, `notes`,
`published_at`. RLS: lectura pública (anon); **sin** políticas de escritura (solo la Edge Function con
service role puede escribir).

**Bucket público `apks`**: aquí viven los archivos APK. URL pública de un archivo:
`https://zyvfamlhlmztliexvmej.supabase.co/storage/v1/object/public/apks/<archivo>`

**Edge Function `publish-release`** (`verify_jwt = false`): valida el token de admin del lado
servidor y hace upsert de la fila con la service role. Endpoint:
`https://zyvfamlhlmztliexvmej.supabase.co/functions/v1/publish-release`

### Cuentas y capas de conocimiento (Supabase Auth)

- **Registro**: Edge Function **`graph-signup`** (`verify_jwt = false`) — crea la cuenta **ya
  confirmada** con la service role (el proyecto exige confirmación por email para la otra app que
  vive aquí, pero Graph no la necesita) y la marca con `user_metadata {app:"graph"}`. El trigger
  `private.handle_new_user` **omite** a estos usuarios (no les crea perfil clínico ni organización).
- **Login**: grant de contraseña normal de GoTrue (`/auth/v1/token?grant_type=password`), con
  refresh automático en el cliente (`SupabaseAuth.kt`).
- **Tabla `graph_memory`** (knowledge-base PERSONAL): columnas `id`, `user_id` (default
  `auth.uid()`), `app`, `note`, `updated_at`; unique `(user_id, note)`. **RLS: solo el dueño** lee y
  escribe sus filas. Las filas antiguas de antes de las cuentas quedaron con `user_id null`
  (invisibles por la API).
- **Tabla `graph_learned_tools`** (mapa de UI, TRANSVERSAL): RLS con **lectura pública** (anon y
  autenticados) y **escritura solo autenticada** — todos se benefician, aportar pide cuenta.

### Token de administrador (SECRETO)

```
7d14188d810aa7044d66d4f019cfe904
```

Vive **solo** en el código de la Edge Function (constante `ADMIN_TOKEN`), nunca dentro del APK. Para
cambiarlo: edita la constante y vuelve a desplegar la función (`deploy_edge_function`).

---

## 3b. Play Protect y la instalación en teléfonos de usuarios

Graph es sideloaded y usa accesibilidad + overlay + micrófono: exactamente el perfil que Play
Protect mira con lupa. Para que la instalación sea lo más confiable posible:

**Del lado del desarrollador (ya aplicado en el build):**
- **Distribuir SIEMPRE el build `release`** (`gradle :app:assembleRelease`), nunca `app-debug.apk`:
  un APK `debuggable` sideloaded es lo primero que Play Protect bloquea. El release se firma con la
  misma clave compartida, así que actualiza sin problema sobre instalaciones previas.
- Sin ofuscación (`isMinifyEnabled = false`): el código ofuscado puntúa PEOR en el análisis.
- `versionCode` siempre creciente y firma estable (misma `graph-release.jks`).
- Si Play Protect marca la app aun así, existe el **formulario de apelación oficial** para apps
  distribuidas fuera de Play (busca "Play Protect appeals form" en la documentación de Google Play);
  Google la analiza y deja de marcarla para todos los usuarios. La solución definitiva a futuro:
  publicarla en Play Console como **prueba cerrada/interna** — firmada y distribuida por Google, las
  advertencias desaparecen.

**Lo que ve el usuario al instalar (guía para compartirles):**
1. Al abrir el APK, Android pide permitir "instalar apps desconocidas" para esa app (Chrome,
   WhatsApp, Files…): **Permitir** solo esta vez.
2. Si Play Protect muestra "App no segura" / "Se bloqueó la instalación": tocar **"Más detalles"** →
   **"Instalar de todas formas"**. Si ofrece "enviar la app para su análisis", mejor: tras el
   análisis, Google suele dejar de advertir.
3. En Android 13+ el sistema puede bloquear la activación de accesibilidad para apps sideloaded
   ("Ajuste restringido"): ir a **Ajustes → Apps → Graph → ⋮ (arriba a la derecha) → "Permitir
   ajustes restringidos"**, y luego sí activar el servicio de accesibilidad.

## 4. Lanzar una versión nueva (paso a paso)

1. **Sube el número de versión** en `app/build.gradle.kts`: incrementa `versionCode` (entero) y
   `versionName`. El `versionCode` DEBE ser mayor al publicado.
2. **Compila el APK RELEASE** (ver §5; `assembleRelease`, no debug). Sale firmado con la clave fija.
3. **Sube el APK al bucket `apks`** desde el panel de Supabase (Storage → `apks` → Upload). Nombre
   sugerido: `graph-<versionCode>.apk` (p.ej. `graph-23.apk`). Copia su URL pública.
4. **Publica desde la app** (como admin): en la tarjeta **"Actualizaciones"** mantén oprimido el
   título → ingresa el **token de admin** → `versionCode`, `versionName`, la **URL del APK** y las
   notas → **"Publicar y notificar"**.
   - Alternativa sin la app (curl):
     ```bash
     curl -X POST "https://zyvfamlhlmztliexvmej.supabase.co/functions/v1/publish-release" \
       -H "apikey: sb_publishable_qroW231Ts7UYAEgr_f5cnQ_3SrW2ZrI" \
       -H "Authorization: Bearer sb_publishable_qroW231Ts7UYAEgr_f5cnQ_3SrW2ZrI" \
       -H "Content-Type: application/json" \
       -d '{"admin_token":"7d14188d810aa7044d66d4f019cfe904","version_code":23,"version_name":"0.23","apk_url":"https://zyvfamlhlmztliexvmej.supabase.co/storage/v1/object/public/apks/graph-23.apk","notes":"Qué cambió"}'
     ```
5. Listo. Cada usuario ve la notificación y actualiza con un toque.

> El único paso manual es **subir el archivo APK** al bucket (los binarios no se pueden subir por las
> herramientas MCP). Todo lo demás es un botón.

---

## 5. Compilar el APK (para un agente sin Android SDK)

Requiere JDK 17+ y el Android SDK (platform-35, build-tools 35.0.0). Si no hay SDK:

```bash
# 1) Command-line tools
mkdir -p /root/android-sdk/cmdline-tools && cd /root/android-sdk/cmdline-tools
curl -sSL -o clt.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
unzip -q clt.zip && mv cmdline-tools latest
export ANDROID_SDK_ROOT=/root/android-sdk ANDROID_HOME=/root/android-sdk
yes | ./latest/bin/sdkmanager --sdk_root=$ANDROID_SDK_ROOT --licenses
./latest/bin/sdkmanager --sdk_root=$ANDROID_SDK_ROOT "platform-tools" "platforms;android-35" "build-tools;35.0.0"

# 2) Build (el wrapper puede fallar al bajar Gradle; usa el gradle del sistema si existe: /opt/gradle/bin/gradle)
cd <repo>
printf 'sdk.dir=/root/android-sdk\n' > local.properties
gradle :app:assembleRelease --no-daemon  # o ./gradlew si el wrapper funciona
# APK: app/build/outputs/apk/release/app-release.apk  ← este es el que se distribuye
# (assembleDebug solo para desarrollo; NUNCA distribuir app-debug.apk a usuarios)
```

Verifica la firma (debe decir `CN=Graph, O=ZevCorp`):
`/root/android-sdk/build-tools/35.0.0/apksigner verify --print-certs app/build/outputs/apk/debug/app-debug.apk`

---

## 6. Checklist para un nuevo agente

- [ ] La firma no cambió (mismo `app/graph-release.jks`).
- [ ] `versionCode` incrementado.
- [ ] APK compilado y firmado con `CN=Graph, O=ZevCorp`.
- [ ] APK subido al bucket `apks`; URL pública verificada (abre en el navegador).
- [ ] Publicado vía la Edge Function / panel admin con el token correcto.
- [ ] `graph_release` refleja la versión nueva (`GET .../rest/v1/graph_release`).

## 7. Mejora futura: push instantáneo (FCM)

Hoy el aviso es por sondeo (~30 min / al abrir), suficiente porque el servicio está siempre activo.
Para push instantáneo con la app cerrada haría falta Firebase Cloud Messaging: crear proyecto
Firebase, agregar `google-services.json`, un token de servidor y enviar el mensaje desde la Edge
Function tras publicar. No implementado aún.
