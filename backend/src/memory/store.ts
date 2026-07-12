// MemoryStore: la knowledge-base personal del usuario (memoria durable), SOLO en el servidor.
//
// En Android esto vivía en `graph_memory` (Supabase, RLS por user_id) y se inyectaba en el prompt.
// Aquí es la misma idea pero es el backend quien la posee e inyecta: el cliente nunca ve la memoria
// ni sabe cómo moldea el comportamiento del cerebro. Esta es una de las piezas de innovación que la
// separación protege.
//
// Primera construcción: interfaz + implementación en memoria (por usuario). Enchufar Supabase/KV es
// cambiar solo la implementación, sin tocar el cerebro ni el cliente.

export interface MemoryStore {
  /** Notas durables del usuario, ya formateadas para el prompt (agrupadas por app). "" si no hay. */
  forPrompt(userId: string): Promise<string>;
  /** Guarda una nota durable (p.ej. "mi mamá en WhatsApp se llama 'Ale'"). */
  remember(userId: string, app: string, note: string): Promise<void>;
}

/** Implementación en memoria del proceso. Se pierde entre cold starts; sustituir por KV/Supabase. */
export class InMemoryMemoryStore implements MemoryStore {
  private byUser = new Map<string, Map<string, string[]>>();

  async forPrompt(userId: string): Promise<string> {
    const apps = this.byUser.get(userId);
    if (!apps || apps.size === 0) return '';
    let out = '';
    for (const [app, notes] of apps) {
      out += `\n### ${app}\n`;
      for (const n of notes) out += `- ${n}\n`;
    }
    return out.trim();
  }

  async remember(userId: string, app: string, note: string): Promise<void> {
    const apps = this.byUser.get(userId) ?? new Map<string, string[]>();
    const notes = apps.get(app) ?? [];
    notes.push(note);
    apps.set(app, notes);
    this.byUser.set(userId, apps);
  }
}
