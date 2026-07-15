// Preload: corre en un mundo aislado con acceso al DOM de la webapp, PERO sin exponerle Node.
//
// Su único trabajo: inyectar el botón flotante "Ü" que SOLO existe en la app de escritorio (la webapp
// remota no lo tiene ni sabe de él). Al pulsarlo, manda un IPC al proceso principal para lanzar la
// carita flotante (el asistente WPF). Como el botón se crea aquí y no en el código de la webapp, se
// cumple: "un botón que está solamente en la app Windows".

const { ipcRenderer, contextBridge } = require('electron');

// API mínima por si la webapp (o un preview) quisiera activar el asistente por su cuenta en el futuro.
contextBridge.exposeInMainWorld('uDesktop', {
  activateAssistant: () => ipcRenderer.send('activate-assistant'),
  isDesktop: true,
});

function injectAssistantButton() {
  if (document.getElementById('u-desktop-fab')) return;

  const btn = document.createElement('button');
  btn.id = 'u-desktop-fab';
  btn.type = 'button';
  btn.setAttribute('aria-label', 'Activar asistente Ü');
  btn.title = 'Activar asistente Ü (controla tu PC)';
  btn.textContent = 'Ü';
  Object.assign(btn.style, {
    position: 'fixed',
    right: '24px',
    bottom: '24px',
    zIndex: '2147483647',
    width: '58px',
    height: '58px',
    borderRadius: '50%',
    border: 'none',
    cursor: 'pointer',
    fontSize: '24px',
    fontWeight: '700',
    color: '#fff',
    background: 'radial-gradient(circle at 30% 30%, #6f8bff, #2a3bd0)',
    boxShadow: '0 6px 20px rgba(0,0,0,.45)',
    transition: 'transform .12s ease',
  });
  btn.addEventListener('mouseenter', () => { btn.style.transform = 'scale(1.08)'; });
  btn.addEventListener('mouseleave', () => { btn.style.transform = 'scale(1)'; });
  // El handler se define en este mundo aislado, así que conserva acceso a ipcRenderer.
  btn.addEventListener('click', () => ipcRenderer.send('activate-assistant'));

  document.body.appendChild(btn);
}

// Inyecta en cuanto haya DOM, y re-inyecta si la SPA (Next.js) reemplaza el body al navegar.
window.addEventListener('DOMContentLoaded', injectAssistantButton);
const observer = new MutationObserver(() => {
  if (document.body && !document.getElementById('u-desktop-fab')) injectAssistantButton();
});
window.addEventListener('DOMContentLoaded', () => {
  if (document.body) observer.observe(document.body, { childList: true });
});
