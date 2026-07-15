// Archivo de los videos de enseñanza en Supabase Storage (bucket privado `teach-videos`), para que
// el equipo pueda verlos desde el dashboard de Supabase.
//
// MISMO PATRÓN QUE GEMINI (ver geminiVideo.ts): el backend FIRMA una URL de subida con la
// service_role key y se la entrega al cliente; el cliente sube el mp4 directo a Supabase. Ni el
// video pasa por Vercel (límite de 4.5 MB) ni la service_role key llega al cliente. La URL firmada
// vale 2 horas y solo sirve para escribir en esa ruta concreta.
//
// EL BUCKET ES PRIVADO a propósito, a diferencia de web/apks/windows: son grabaciones de pantalla de
// sistemas clínicos y pueden tener datos de pacientes visibles. Nadie con el link puede verlos; hay
// que entrar al dashboard (o usar la service_role key).

import { config, videoArchiveEnabled } from '../config';

export interface SignedVideoUpload {
  /** URL absoluta a la que el cliente hace PUT con el mp4. Ya trae el token embebido. */
  uploadUrl: string;
  /** Ruta dentro del bucket, para poder encontrarlo después. */
  path: string;
}

/**
 * Firma una URL de subida para un mp4 nuevo. Devuelve null si el archivo no está configurado
 * (faltan SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY): archivar es deseable, no imprescindible — la
 * enseñanza debe seguir funcionando aunque el archivo esté apagado.
 */
export async function signVideoUpload(userId: string, recordedAtIso: string): Promise<SignedVideoUpload | null> {
  if (!videoArchiveEnabled()) return null;

  const path = `${sanitize(userId)}/${sanitize(recordedAtIso)}.mp4`;
  const endpoint = `${config.supabaseUrl}/storage/v1/object/upload/sign/${config.supabaseVideoBucket}/${path}`;

  const res = await fetch(endpoint, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${config.supabaseServiceKey}`,
      apikey: config.supabaseServiceKey,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({}),
  });
  if (!res.ok) {
    const body = await res.text().catch(() => '');
    throw new Error(`Supabase sign upload HTTP ${res.status}: ${body.slice(0, 200)}`);
  }

  // Responde { url: "/object/upload/sign/<bucket>/<path>?token=..." } — relativa a /storage/v1.
  const body = (await res.json()) as { url?: string };
  if (!body.url) throw new Error('Supabase no devolvió la url firmada');

  return { uploadUrl: `${config.supabaseUrl}/storage/v1${body.url}`, path };
}

/** Rutas de Storage: sin barras ni caracteres raros que rompan el path o permitan escapar de la carpeta. */
function sanitize(s: string): string {
  return (s || 'anon').replace(/[^a-zA-Z0-9_-]/g, '_').slice(0, 80);
}
