# Miracle · Caparazón Windows (Electron)

App de escritorio instalable en Windows que **embebe la webapp** (`Pagina-web-clientes-final`, Next.js
en Vercel) **sin reescribir nada** y añade la **carita flotante Ü** que controla el PC.

## Cómo encaja todo

```
┌─ Miracle.exe (Electron, instalable con NSIS) ─────────────────────┐
│                                                                    │
│  BrowserWindow.loadURL("https://miracle-web-umber.vercel.app")     │  ← la webapp, 100% reutilizada
│     · sesión persistente (login Supabase sobrevive reinicios)      │
│     · Ctrl+R recarga · enlaces externos → navegador del sistema    │
│                                                                    │
│  preload.js  → inyecta un botón flotante "Ü" (SOLO en el desktop)  │
│                     │ click → ipcRenderer.send('activate-assistant')│
│                     ▼                                              │
│  main.js  → spawn(resources/assistant/U.exe)                      │
│                     │                                              │
│                     ▼   carita flotante WPF (UIA) ──► backend Vercel│
└────────────────────────────────────────────────────────────────────┘
```

La experiencia es exactamente la que pediste: **abres la app → ves toda la webapp → pulsas el botón Ü
(que solo está en el desktop) → aparece la carita flotante capaz de controlar el PC.**

## Por qué se carga la URL en vez de empaquetar el código

La webapp es **Next.js con backend Supabase** (SSR + rutas API): no es un sitio estático que se pueda
"meter como archivos". Ya está desplegada en Vercel, así que el caparazón **carga esa URL de
producción**. Ventajas:

- **Reutilización del 100%** del webapp, sin tocar ni una línea ni mantener un fork.
- **Actualizaciones con fricción casi nula** (ver abajo).
- La webapp corre igual que en el navegador: SSR, rutas API y Supabase funcionan tal cual.

## Actualizaciones de la webapp (lo que pediste)

**No hay que hacer nada en la app instalada.**

```
push al repo de la webapp  →  Vercel redespliega solo  →  al reabrir/refrescar (Ctrl+R)
la app Windows ya muestra la versión nueva.
```

No se reinstala, no se recompila el instalador, no se toca al usuario. Es el flujo de menor fricción
posible porque el "contenido" vive en Vercel, no dentro del `.exe`.

> ¿Apuntar a otro deploy (staging/preview)? Arranca con `WEBAPP_URL=https://mi-preview.vercel.app`
> (ver `config.js`), sin recompilar.

## Actualizaciones del propio caparazón (opcional)

El caparazón (Electron + el `U.exe` empaquetado) sí es un instalador. Solo necesitas republicarlo
cuando cambie el caparazón o el asistente, no cuando cambie la webapp. Está cableado para
**electron-updater**: rellena `build.publish` en `package.json` (p.ej. `provider: github`) y
`main.js` buscará updates al arrancar (`checkForUpdatesAndNotify`). Sin `publish`, se ignora.

## Compilar el instalador (Windows, con .NET 8 SDK + Node 18+)

```powershell
# 1) Compila y empaqueta el asistente WPF dentro del caparazón:
powershell -ExecutionPolicy Bypass -File scripts\prepare-assistant.ps1

# 2) Instala deps del caparazón y genera el instalador NSIS:
npm install
npm run dist        # → dist\Miracle Setup 0.1.0.exe
```

Para probar sin empaquetar: `npm start` (abre la webapp; el botón Ü lanzará `U.exe` si ya lo pusiste
en `resources\assistant\`).

## Notas

- El botón se inyecta desde `preload.js`, en un mundo aislado con `contextIsolation`; la webapp remota
  no ve Node ni el IPC salvo la mini-API `window.uDesktop`.
- `resources/assistant/U.exe` se rellena en el paso 1; el repo solo versiona un `.gitkeep`.
- Añade tu icono en `assets/icon.ico` para personalizar el instalador y la ventana.
- **Verificación:** el JS del caparazón está validado (`node --check`), pero `electron-builder` y el
  `dotnet publish` deben correrse en Windows; no se empaquetó aquí (entorno Linux).
