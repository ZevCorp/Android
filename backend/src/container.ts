// Composition root del backend: cablea las implementaciones concretas de los stores.
//
// Primera construcción: stores en memoria (se pierden entre cold starts de Vercel). Para producción,
// sustituir por Supabase/KV/Neo4j aquí — sin tocar el cerebro, el engine, ni el cliente.

import { InMemoryMemoryStore, MemoryStore } from './memory/store';
import { InMemoryLearningStore, LearningStore } from './learning/workflows';

let memory: MemoryStore | null = null;
let learning: LearningStore | null = null;

export function deps(): { memory: MemoryStore; learning: LearningStore } {
  if (!memory) memory = new InMemoryMemoryStore();
  if (!learning) learning = new InMemoryLearningStore();
  return { memory, learning };
}
