// El catálogo MCP: SOLO la declaración (nombre, descripción, esquema de parámetros, `via`).
//
// Diferencia CLAVE con el Android actual: aquí el catálogo NO tiene ejecutores (`run`). El cerebro
// vive en el backend y solo DECLARA las herramientas al modelo; la EJECUCIÓN ocurre en el cliente,
// que tiene su propio registro `nombre -> ejecutor local` (gesto de Windows / acción de sistema).
// Así los prompts, descripciones y la lógica del catálogo — la innovación — nunca salen del servidor.
//
// Portado de core/domain/Model.kt (clase `Mcp`) quitando los lambdas de ejecución.

export interface McpParam {
  name: string;
  description: string;
  /** Si no está vacío, es una enumeración cerrada. */
  options?: string[];
}

export interface McpTool {
  name: string;
  description: string;
  params: McpParam[];
  /** Documenta CÓMO lo ejecuta el cliente (gesto de Windows vs API del sistema vs aprendido). */
  via: string;
}

const GESTURE = 'gesto de Windows';
const SYSTEM = 'API/acción del sistema (sin navegar la UI)';
export const WORKFLOW_VIA = 'workflow (subconsciente ↔ consciente)';
export const LEARNED_VIA = 'aprendido (árbol de UI)';

/**
 * Gestos de navegación de Windows, análogos a los gestos de accesibilidad de Android. El cliente los
 * implementa con atajos del shell (Win, Win+D, Win+A, Alt+Tab, rueda del ratón).
 */
const gestureTools: McpTool[] = [
  { name: 'go_home', via: GESTURE, params: [], description: 'Muestra el escritorio (minimiza todo), equivalente a ir al inicio.' },
  { name: 'open_app_drawer', via: GESTURE, params: [], description: 'Abre el menú Inicio para buscar y lanzar aplicaciones.' },
  { name: 'open_notifications', via: GESTURE, params: [], description: 'Abre el centro de notificaciones de Windows.' },
  {
    name: 'switch_window', via: GESTURE,
    description: 'Cambia entre ventanas abiertas (Alt+Tab).',
    params: [{ name: 'direction', description: 'Hacia qué ventana moverse', options: ['next', 'previous'] }],
  },
  {
    name: 'scroll_menu', via: GESTURE,
    description: 'Desliza (scroll) dentro de una lista o menú.',
    params: [{ name: 'direction', description: 'Dirección del desplazamiento', options: ['up', 'down'] }],
  },
];

/**
 * Acciones del sistema por API/Intent equivalente de Windows (headless, sin navegar la UI). El
 * cliente las implementa con `Process.Start`, protocolos (`mailto:`, `ms-settings:`), el
 * portapapeles, etc. El modelo las prefiere sobre computer-use para tareas del sistema.
 */
const systemTools: McpTool[] = [
  {
    name: 'launch_app', via: SYSTEM,
    description: 'Abre una aplicación por su nombre directamente (menú Inicio / ejecutable), sin navegar la UI.',
    params: [{ name: 'app', description: 'Nombre visible o ejecutable de la app' }],
  },
  {
    name: 'set_alarm', via: SYSTEM,
    description: 'Crea una alarma en la app Reloj de Windows / Tareas programadas.',
    params: [
      { name: 'hour', description: 'Hora 0-23' },
      { name: 'minute', description: 'Minuto 0-59' },
      { name: 'message', description: 'Etiqueta (opcional)' },
    ],
  },
  {
    name: 'set_timer', via: SYSTEM,
    description: 'Inicia un temporizador.',
    params: [
      { name: 'seconds', description: 'Duración en segundos' },
      { name: 'message', description: 'Etiqueta (opcional)' },
    ],
  },
  {
    name: 'create_event', via: SYSTEM,
    description: 'Crea un evento de calendario (protocolo del calendario / Outlook).',
    params: [
      { name: 'title', description: 'Título del evento' },
      { name: 'start', description: 'Inicio ISO-8601 local, p.ej. 2026-07-06T15:00 (opcional)' },
      { name: 'location', description: 'Lugar (opcional)' },
    ],
  },
  {
    name: 'dial', via: SYSTEM,
    description: 'Abre el marcador (tel:) con un número.',
    params: [{ name: 'number', description: 'Número de teléfono' }],
  },
  {
    name: 'send_sms', via: SYSTEM,
    description: 'Abre un SMS prellenado (protocolo sms:) — el usuario confirma el envío.',
    params: [
      { name: 'number', description: 'Destinatario' },
      { name: 'message', description: 'Texto (opcional)' },
    ],
  },
  {
    name: 'send_email', via: SYSTEM,
    description: 'Abre un correo prellenado (mailto:).',
    params: [
      { name: 'to', description: 'Destinatario (opcional)' },
      { name: 'subject', description: 'Asunto (opcional)' },
      { name: 'body', description: 'Cuerpo (opcional)' },
    ],
  },
  {
    name: 'web_search', via: SYSTEM,
    description: 'Busca en la web en el navegador por defecto.',
    params: [{ name: 'query', description: 'Qué buscar' }],
  },
  {
    name: 'open_url', via: SYSTEM,
    description: 'Abre una URL en el navegador.',
    params: [{ name: 'url', description: 'URL http(s)' }],
  },
  {
    name: 'open_maps', via: SYSTEM,
    description: 'Abre un lugar o búsqueda en el mapa.',
    params: [{ name: 'query', description: 'Lugar o búsqueda' }],
  },
  {
    name: 'directions', via: SYSTEM,
    description: 'Abre la navegación hacia un destino.',
    params: [{ name: 'destination', description: 'Destino' }],
  },
  {
    name: 'open_camera', via: SYSTEM,
    description: 'Abre la app de Cámara.',
    params: [],
  },
  {
    name: 'open_settings', via: SYSTEM,
    description: 'Abre una pantalla de Configuración de Windows (ms-settings:).',
    params: [{ name: 'section', description: 'Sección', options: ['general', 'wifi', 'bluetooth', 'network', 'display', 'sound', 'battery', 'privacy', 'apps'] }],
  },
  {
    name: 'share_text', via: SYSTEM,
    description: 'Abre el diálogo de compartir de Windows con un texto.',
    params: [{ name: 'text', description: 'Texto a compartir' }],
  },
  {
    name: 'set_clipboard', via: SYSTEM,
    description: 'Copia un texto al portapapeles (sin UI).',
    params: [{ name: 'text', description: 'Texto a copiar' }],
  },
  {
    name: 'set_volume', via: SYSTEM,
    description: 'Ajusta el volumen del sistema directamente (sin UI) a un porcentaje 0-100.',
    params: [{ name: 'percent', description: 'Nivel 0-100 (usa 100 para asegurar que se oiga)' }],
  },
  {
    name: 'adjust_volume', via: SYSTEM,
    description: 'Sube, baja, muda o restaura el volumen del sistema con un solo golpe (como la tecla física).',
    params: [{ name: 'direction', description: 'Acción', options: ['raise', 'lower', 'mute', 'unmute'] }],
  },
];

/**
 * El catálogo MCP base: gestos + acciones de sistema. Las herramientas APRENDIDAS y los WORKFLOWS
 * se añaden encima en runtime desde los stores de aprendizaje (ver `learning/`), sin tocar esto.
 */
export function baseCatalog(): McpTool[] {
  return [...gestureTools, ...systemTools];
}

/** Nombres de las herramientas base (para que el cerebro sepa cuáles son llamadas MCP vs custom). */
export function catalogNames(tools: McpTool[]): Set<string> {
  return new Set(tools.map((t) => t.name));
}

/** Documentación del protocolo en Markdown, agrupada por vía (útil para depurar/exponer). */
export function docMarkdown(tools: McpTool[]): string {
  const byVia = new Map<string, McpTool[]>();
  for (const t of tools) {
    const g = byVia.get(t.via) ?? [];
    g.push(t);
    byVia.set(t.via, g);
  }
  let out = '# Herramientas MCP disponibles\n';
  for (const [via, group] of byVia) {
    out += `\n## Vía: ${via}\n`;
    for (const t of group) {
      out += `\n### \`${t.name}\`\n${t.description}\n`;
      for (const p of t.params) {
        const opts = p.options && p.options.length ? ` (opciones: ${p.options.join(', ')})` : '';
        out += `- **${p.name}**: ${p.description}${opts}\n`;
      }
    }
  }
  return out;
}
