// Configuración del backend desde variables de entorno (Vercel Project Settings → Environment Vars).
// NADA de esto vive en el cliente: ni la key del modelo, ni el modelo, ni el effort, ni el secreto
// de sesión. Ese es el punto de la separación.

export const config = {
  /** Key de OpenAI. OBLIGATORIA en producción. */
  openAiApiKey: process.env.OPENAI_API_KEY || '',
  /** Modelo con computer-use (Responses API). Cambiable sin redeploy del cliente. */
  model: process.env.MODEL || 'gpt-5.6',
  /** reasoning.effort: none/minimal/low/medium/high/xhigh. Para computer-use se recomienda "low". */
  effort: process.env.EFFORT || 'low',
  /** Secreto HMAC para firmar el blob de sesión opaco. */
  sessionSecret: process.env.SESSION_SECRET || '',
  /** Token opcional para autenticar clientes (Bearer). Si está vacío, la API es abierta (dev). */
  clientToken: process.env.CLIENT_TOKEN || '',
};

export function assertConfigured(): void {
  if (!config.openAiApiKey) throw new Error('OPENAI_API_KEY no está configurada en el entorno.');
}
