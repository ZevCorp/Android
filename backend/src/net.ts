// Configuración de red: hace que `fetch` (undici, integrado en Node) respete el proxy de salida del
// entorno (HTTPS_PROXY). Sin esto, en entornos con proxy obligatorio las llamadas al modelo fallan.
// En Vercel no hay proxy y esto es un no-op. Se importa una vez desde los providers.

let configured = false;

export async function ensureProxy(): Promise<void> {
  if (configured) return;
  configured = true;
  const proxy = process.env.HTTPS_PROXY || process.env.https_proxy || process.env.HTTP_PROXY;
  if (!proxy) return;
  try {
    const undici = await import('undici');
    const dispatcher = new undici.ProxyAgent(proxy);
    // setGlobalDispatcher hace que el `fetch` global de Node use el proxy.
    (undici as unknown as { setGlobalDispatcher: (d: unknown) => void }).setGlobalDispatcher(dispatcher);
  } catch {
    // undici siempre está en Node 18+; si no, el fetch global intentará conexión directa.
  }
}
