import { HttpError, validateObservation } from './session.js';

export function validateArtwork(body, now = Date.now()) {
  if (!Number.isFinite(body?.capturedAt) || now - body.capturedAt > 600_000 || body.capturedAt - now > 2000) {
    throw new HttpError(400, 'La referencia de obra venció. Seleccioná la obra otra vez.');
  }
  // Reuse consent/image/history checks without weakening fresh-camera requests on /api/observe.
  const validated = validateObservation({ ...body, mode: 'meta', capturedAt: now }, now);
  return { ...validated, capturedAt: body.capturedAt, artworkReference: true };
}

export function validateVoice(body) {
  if (body?.allowPaid !== true) throw new HttpError(400, 'Autorizá enviar voz a Meta.');
  if (typeof body.audio !== 'string' || body.audio.length > 520_000 || !/^[A-Za-z0-9+/]+={0,2}$/.test(body.audio)) throw new HttpError(400, 'Audio inválido o demasiado largo.');
  const wav = Buffer.from(body.audio, 'base64');
  if (wav.length < 46 || wav.toString('ascii', 0, 4) !== 'RIFF' || wav.toString('ascii', 8, 12) !== 'WAVE' ||
      wav.toString('ascii', 12, 16) !== 'fmt ' || wav.readUInt32LE(16) !== 16 || wav.readUInt16LE(20) !== 1 ||
      wav.readUInt16LE(22) !== 1 || wav.readUInt32LE(24) !== 16000 || wav.readUInt32LE(28) !== 32000 ||
      wav.readUInt16LE(32) !== 2 || wav.readUInt16LE(34) !== 16 || wav.toString('ascii', 36, 40) !== 'data' ||
      wav.readUInt32LE(4) !== wav.length - 8 || wav.readUInt32LE(40) !== wav.length - 44 || (wav.length - 44) % 2 !== 0 ||
      wav.length - 44 > 384_000) throw new HttpError(400, 'Se requiere WAV mono PCM16, 16 kHz y hasta 12 segundos.');
  return { wav, durationMs: (wav.length - 44) / 32 };
}

export async function transcribeMeta(voice, { key, signal, fetchImpl = fetch }) {
  const start = performance.now();
  const form = new FormData();
  form.append('request', new Blob([JSON.stringify({ mode: 'PUSH_TO_TALK', model: 'muse-voice-transcribe-1.0', audioEncoding: 'WAV', languageBias: ['Spanish'] })], { type: 'application/json' }));
  form.append('audio', new Blob([voice.wav], { type: 'audio/wav' }), 'question.wav');
  let response;
  try {
    response = await fetchImpl('https://api.meta.ai/v1/asr/transcribe', { method: 'POST', headers: { Authorization: `Bearer ${key}` }, body: form, signal, redirect: 'error' });
  } catch {
    throw new HttpError(signal?.aborted ? 504 : 502, 'No se completó la transcripción; puede haber consumo facturado.');
  }
  if (!response.ok) throw new HttpError(502, `Meta Voice rechazó la transcripción (HTTP ${response.status}). No se reintenta automáticamente.`);
  let data;
  try { data = await response.json(); } catch { throw new HttpError(502, 'Respuesta de voz ilegible.'); }
  if (typeof data.transcript !== 'string' || !data.transcript.trim() || data.transcript.length > 1000) throw new HttpError(502, 'No llegó una pregunta reconocible. Intentá una pregunta más corta.');
  return { text: data.transcript.trim(), mode: 'meta', model: 'muse-voice-transcribe-1.0', providerMs: Math.round(performance.now() - start), audioDurationMs: voice.durationMs, estimatedUsd: voice.durationMs * 0.18 / 3_600_000 };
}
