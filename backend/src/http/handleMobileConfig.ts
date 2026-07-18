// Lógica de GET /api/mobile/config, independiente del runtime (Vercel o servidor local) — mismo
// patrón que handleTurn.ts.
//
// A diferencia de Windows, el cliente Android trae su propio cerebro (OpenAiBrain/GeminiBrain corren
// en el APK). Este endpoint NO participa del bucle de ejecución: es solo reparto de configuración para
// que el usuario nunca tenga que pegar una API key a mano ni depender de que el build la traiga
// horneada. Las keys reales jamás viven en el cliente en reposo (en el .apk) — llegan por red y se
// cachean, así rotarlas es cambiar la env var aquí, sin recompilar ni redistribuir el APK.
//
// Nota de seguridad: esto NO da la misma protección que el modelo de Windows (donde la key de OpenAI
// jamás sale del servidor). Aquí el dispositivo sigue llamando a OpenAI/Gemini directo, así que recibe
// la key en claro. Es una mejora sobre hornearla en el APK (rotable sin release, no vive en un archivo
// estático descargable) pero no elimina la exposición en el dispositivo.

import { config } from '../config';

export interface HttpResult {
  status: number;
  json: unknown;
}

export function handleMobileConfig(authHeader?: string): HttpResult {
  // Fail-closed: sin ANDROID_CLIENT_TOKEN configurado, el endpoint no reparte nada (nunca "abierto por defecto").
  if (!config.androidClientToken) {
    return { status: 503, json: { error: 'ANDROID_CLIENT_TOKEN no configurado en el servidor' } };
  }
  const auth = (authHeader || '').replace(/^Bearer\s+/i, '');
  if (auth !== config.androidClientToken) return { status: 401, json: { error: 'no autorizado' } };

  // Cada campo solo aparece si está configurado: el cliente distingue "no vino" (usa su propio
  // default) de "vino vacío". Nunca se listan keys de terceros ajenas a Android (p.ej. Supabase).
  const json: Record<string, string> = {};
  if (config.geminiApiKey) json.geminiKey = config.geminiApiKey;
  if (config.openAiApiKey) json.openaiKey = config.openAiApiKey;
  if (config.deepgramApiKey) json.deepgramKey = config.deepgramApiKey;
  if (config.neo4jUri) json.neo4jUri = config.neo4jUri;
  if (config.neo4jUser) json.neo4jUser = config.neo4jUser;
  if (config.neo4jPass) json.neo4jPass = config.neo4jPass;

  return { status: 200, json: { ok: true, ...json } };
}
