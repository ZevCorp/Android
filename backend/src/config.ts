// Configuración del backend desde variables de entorno (Vercel Project Settings → Environment Vars).
// NADA de esto vive en el cliente: ni las keys de los modelos, ni el modelo, ni el provider, ni el
// secreto de sesión. Ese es el punto de la separación.

export type Provider = 'openai' | 'gemini';

export const config = {
  /** Proveedor del cerebro: 'openai' | 'gemini'. Cambiable sin tocar el cliente. */
  provider: (process.env.PROVIDER || 'gemini').toLowerCase() as Provider,

  // --- OpenAI (computer-use nativo, Responses API) ---
  openAiApiKey: process.env.OPENAI_API_KEY || '',
  openAiModel: process.env.OPENAI_MODEL || process.env.MODEL || 'gpt-5.6',
  /** reasoning.effort: none/minimal/low/medium/high/xhigh. Para computer-use se recomienda "low". */
  effort: process.env.EFFORT || 'low',

  // --- Gemini (Google, generateContent con function-calling + visión) ---
  geminiApiKey: process.env.GEMINI_API_KEY || process.env.GOOGLE_API_KEY || '',
  geminiModel: process.env.GEMINI_MODEL || 'gemini-3.5-flash',

  /** Secreto HMAC para firmar el blob de sesión opaco. */
  sessionSecret: process.env.SESSION_SECRET || '',
  /** Token opcional para autenticar clientes (Bearer). Si está vacío, la API es abierta (dev). */
  clientToken: process.env.CLIENT_TOKEN || '',

  // --- Supabase Storage (archivo de los videos de enseñanza, bucket privado `teach-videos`) ---
  /** URL del proyecto, p.ej. https://xxxx.supabase.co */
  supabaseUrl: (process.env.SUPABASE_URL || '').replace(/\/+$/, ''),
  /**
   * service_role key. Solo se usa para FIRMAR URLs de subida de corta duración que se le entregan al
   * cliente; la key jamás sale del servidor. Si falta, la enseñanza por video sigue funcionando pero
   * el mp4 no se archiva (ver videoStorage.ts).
   */
  supabaseServiceKey: process.env.SUPABASE_SERVICE_ROLE_KEY || '',
  /** Bucket donde se archivan los mp4. Privado: son grabaciones de pantalla de sistemas clínicos. */
  supabaseVideoBucket: process.env.SUPABASE_VIDEO_BUCKET || 'teach-videos',
};

/** ¿Está configurado el archivo de videos en Supabase Storage? */
export function videoArchiveEnabled(): boolean {
  return Boolean(config.supabaseUrl && config.supabaseServiceKey);
}

/** Modelo activo según el provider. */
export function activeModel(): string {
  return config.provider === 'gemini' ? config.geminiModel : config.openAiModel;
}

/** Key activa según el provider. */
export function activeKey(): string {
  return config.provider === 'gemini' ? config.geminiApiKey : config.openAiApiKey;
}

export function assertConfigured(): void {
  if (config.provider === 'gemini' && !config.geminiApiKey)
    throw new Error('GEMINI_API_KEY no está configurada en el entorno.');
  if (config.provider === 'openai' && !config.openAiApiKey)
    throw new Error('OPENAI_API_KEY no está configurada en el entorno.');
}
