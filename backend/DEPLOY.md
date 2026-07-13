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
