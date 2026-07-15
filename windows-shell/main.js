// Proceso principal de Electron: el caparazón Windows.
//
// Responsabilidades (deliberadamente mínimas — el caparazón NO tiene lógica de negocio):
//  1. Abrir una ventana que carga la webapp desplegada (100% reutilizada, auto-actualiza vía Vercel).
//  2. Recibir el IPC del botón inyectado y lanzar el asistente WPF (U.exe) → la carita flotante.
//  3. (Opcional) Auto-actualizar el propio caparazón vía electron-updater.

const { app, BrowserWindow, ipcMain, session, shell, dialog, Menu } = require('electron');
const path = require('node:path');
const fs = require('node:fs');
const { spawn } = require('node:child_process');
const config = require('./config');

let mainWindow = null;
let assistantProc = null;

// Una sola instancia del caparazón.
if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.focus();
    }
  });
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 820,
    minWidth: 900,
    minHeight: 600,
    backgroundColor: '#0b0b0f',
    title: 'Miracle',
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      // Partición persistente: la sesión de la webapp (login Supabase, cookies) sobrevive reinicios.
      partition: 'persist:webapp',
    },
  });

  // Carga la webapp de producción. Si no hay red, muestra un aviso amable con reintento.
  loadWebapp();

  // Los enlaces "target=_blank" / externos se abren en el navegador del sistema, no ventanas huérfanas.
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith('http')) shell.openExternal(url);
    return { action: 'deny' };
  });

  mainWindow.webContents.on('did-fail-load', (_e, code, desc, url, isMainFrame) => {
    if (isMainFrame) showLoadError(desc || `error ${code}`);
  });

  // Menú mínimo con recargar (para traer la última versión del webapp al instante).
  Menu.setApplicationMenu(Menu.buildFromTemplate([
    {
      label: 'Miracle',
      submenu: [
        { label: 'Recargar webapp', accelerator: 'CmdOrCtrl+R', click: () => mainWindow.reload() },
        { label: 'Activar asistente Ü', accelerator: 'CmdOrCtrl+U', click: launchAssistant },
        { type: 'separator' },
        { role: 'toggleDevTools' },
        { role: 'quit', label: 'Salir' },
      ],
    },
  ]));
}

function loadWebapp() {
  mainWindow.loadURL(config.webappUrl).catch(() => showLoadError('no se pudo conectar'));
}

function showLoadError(reason) {
  const html = `data:text/html;charset=utf-8,${encodeURIComponent(`
    <body style="margin:0;height:100vh;display:flex;align-items:center;justify-content:center;
      background:#0b0b0f;color:#eee;font-family:system-ui;text-align:center">
      <div>
        <h2>No pude abrir la webapp</h2>
        <p style="opacity:.7">${reason}. Revisa tu conexión.</p>
        <button onclick="location.reload()" style="padding:10px 18px;border:0;border-radius:10px;
          background:#3b5bff;color:#fff;font-size:15px;cursor:pointer">Reintentar</button>
      </div>
    </body>`)}`;
  // Reintenta la URL real tras un momento; mientras, muestra el aviso.
  mainWindow.loadURL(html);
  setTimeout(loadWebapp, 4000);
}

// --- El asistente (carita flotante WPF) ---

ipcMain.on('activate-assistant', launchAssistant);

function launchAssistant() {
  if (assistantProc && assistantProc.exitCode === null) {
    // Ya está corriendo; no lo dupliques.
    return;
  }
  const exe = findAssistant();
  if (!exe) {
    dialog.showErrorBox(
      'Asistente no encontrado',
      'No encontré el ejecutable del asistente (U.exe). Compílalo desde windows-client y colócalo en ' +
        'resources/assistant/ (o usa scripts/prepare-assistant.ps1 antes de empaquetar).',
    );
    return;
  }
  try {
    assistantProc = spawn(exe, [], { detached: true, stdio: 'ignore' });
    assistantProc.on('exit', () => { assistantProc = null; });
    assistantProc.unref();
  } catch (e) {
    dialog.showErrorBox('Asistente', `No pude lanzar el asistente: ${e.message}`);
  }
}

function findAssistant() {
  const candidates = [
    path.join(process.resourcesPath || '', 'assistant', config.assistantExeName),
    path.join(__dirname, 'resources', 'assistant', config.assistantExeName),
  ];
  return candidates.find((p) => p && fs.existsSync(p)) || null;
}

// --- Auto-actualización del caparazón (opcional; solo si se configuró `publish` en electron-builder) ---

async function maybeAutoUpdate() {
  if (!app.isPackaged) return;
  try {
    const { autoUpdater } = require('electron-updater');
    autoUpdater.autoDownload = true;
    await autoUpdater.checkForUpdatesAndNotify();
  } catch {
    // electron-updater no instalado o sin feed de publicación: se ignora silenciosamente.
  }
}

app.whenReady().then(() => {
  createWindow();
  maybeAutoUpdate();
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});
