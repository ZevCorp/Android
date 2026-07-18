# Desplegar el backend en Vercel

El código del backend ya está en el repo (`backend/`). La key del modelo **nunca va en el código** —
se configura como variable de entorno en Vercel. Dos formas de desplegar:

## Opción A — CLI de Vercel (rápida, recomendada)

Desde tu máquina, dentro de `backend/`:

```bash
npm i -g vercel
cd backend
vercel link            # crea/enlaza el proyecto (elige tu cuenta)

# Variables de entorno (se guardan en Vercel, no en el código):
vercel env add PROVIDER production          # valor: gemini
vercel env add GEMINI_API_KEY production    # pega tu key de Gemini
vercel env add GEMINI_MODEL production      # valor: gemini-3.5-flash
vercel env add SESSION_SECRET production    # cualquier string largo aleatorio

vercel deploy --prod   # despliega a producción → te da la URL https://...vercel.app
```

## Opción B — Importar el repo en vercel.com

1. vercel.com → **Add New… → Project** → importa el repo de GitHub.
2. **Root Directory** = `backend`.
3. **Environment Variables**: añade `PROVIDER=gemini`, `GEMINI_API_KEY=<tu key>`,
   `GEMINI_MODEL=gemini-3.5-flash`, `SESSION_SECRET=<aleatorio>`.
4. **Deploy**. Cada push a la rama vuelve a desplegar solo.

## Comprobar

```bash
curl https://<tu-deploy>.vercel.app/api/health
# → {"ok":true,"provider":"gemini","model":"gemini-3.5-flash","configured":true,...}
```

Si `configured` es `false`, falta la env var `GEMINI_API_KEY` (o `PROVIDER` no es `gemini`).

## Cambiar de proveedor (OpenAI)

Cambia en Vercel `PROVIDER=openai` y añade `OPENAI_API_KEY` (+ opcional `OPENAI_MODEL`, `EFFORT`).
El cliente Windows no cambia.

## Apuntar el cliente

En la carita flotante del cliente Windows, panel **Backend**, pon la URL de tu deploy
(`https://<tu-deploy>.vercel.app`) y Guardar.

## Reparto de keys para Android (`GET /api/mobile/config`)

A diferencia de Windows, el cerebro de Android vive **en el dispositivo** (compilado en el APK): este
endpoint no participa del bucle de ejecución, solo evita que el usuario tenga que pegar sus API keys a
mano o depender de que el build las traiga horneadas. El cliente lo llama al arrancar, cachea la
respuesta y sigue funcionando offline con lo último que recibió.

Variables de entorno (Vercel → Settings → Environment Variables):

- `ANDROID_CLIENT_TOKEN` (**obligatoria** para activar el endpoint): cualquier string aleatorio largo.
  Sin esta variable el endpoint responde `503` siempre (fail-closed) — nunca reparte keys sin auth.
  Debe coincidir con el token que trae el cliente Android (`ANDROID_CLIENT_TOKEN` en el APK).
- `DEEPGRAM_API_KEY`, `NEO4J_URI`, `NEO4J_USER`, `NEO4J_PASS` (opcionales): si faltan, el cliente cae a
  sus valores por defecto de siempre sin romperse.
- `OPENAI_API_KEY` / `GEMINI_API_KEY`: son las MISMAS que ya usa el cerebro de Windows (una sola fuente
  de verdad) — si ya están configuradas arriba, Android las recibe sin configurar nada más.

Comprobar:

```bash
curl -H "Authorization: Bearer <ANDROID_CLIENT_TOKEN>" https://<tu-deploy>.vercel.app/api/mobile/config
```

**Nota de seguridad:** esto NO da la misma protección que el modelo de Windows (donde la key de OpenAI
nunca sale del servidor). El teléfono sigue llamando a OpenAI/Gemini directo, así que recibe la key en
claro por red. Es una mejora real sobre hornearla en el APK (rotable sin sacar una versión nueva, no
vive en un archivo estático descargable) pero no elimina la exposición en el dispositivo — para eso
haría falta que Android proxee también sus llamadas de inferencia por este backend, como Windows.
