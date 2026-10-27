import { randomUUID } from 'node:crypto';

export class HttpError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

export function validateObservation(body, now = Date.now()) {
  if (!body || !['fixture', 'meta'].includes(body.mode)) throw new HttpError(400, 'Elegí demostración o Meta.');
  if (typeof body.question !== 'string' || !body.question.trim() || body.question.length > 1000) throw new HttpError(400, 'La pregunta debe tener entre 1 y 1000 caracteres.');
  if (body.mode === 'meta') {
    if (body.allowPaid !== true) throw new HttpError(400, 'Confirmá el envío a Meta y el uso de API paga.');
    if (typeof body.image !== 'string' || !/^data:image\/(jpeg|png);base64,[A-Za-z0-9+/]+={0,2}$/.test(body.image) || body.image.length > 2_000_000) throw new HttpError(400, 'Se necesita una captura JPEG/PNG de hasta 2 MB codificados.');
    if (!Number.isFinite(body.capturedAt) || now - body.capturedAt > 15_000 || body.capturedAt - now > 2000) throw new HttpError(400, 'La imagen está vencida. Tomá una captura nueva.');
  }
  const history = Array.isArray(body.history) ? body.history.slice(-4).map(turn => {
    if (!turn || !['user', 'assistant'].includes(turn.role) || typeof turn.text !== 'string') throw new HttpError(400, 'Contexto inválido.');
    return { role: turn.role, text: turn.text.slice(0, 1500) };
  }) : [];
  return { ...body, question: body.question.trim(), history };
}

export function createCallGate(maxCalls = 24) {
  let attempted = 0;
  let busy = false;
  return {
    status: () => ({ attempted, remaining: Math.max(0, maxCalls - attempted), maxCalls, busy }),
    enter() {
      if (busy) throw new HttpError(429, 'Hay una consulta en curso. Esperá a que termine.');
      if (attempted >= maxCalls) throw new HttpError(429, 'Se alcanzó el límite de llamadas de este proceso.');
      busy = true; attempted += 1;
      let released = false;
      return () => { if (!released) { busy = false; released = true; } };
    },
  };
}

export function newSessionToken() { return randomUUID(); }
