import test from 'node:test';
import assert from 'node:assert/strict';
import { validateArtwork, validateVoice, transcribeMeta } from '../server/museum.js';
import { validateObservation } from '../server/session.js';
import { makePayload } from '../server/meta.js';
import { makeServer } from '../server/index.js';

function wav(samples = 16000) {
  const b = Buffer.alloc(44 + samples * 2); b.write('RIFF'); b.writeUInt32LE(b.length - 8, 4); b.write('WAVEfmt ', 8); b.writeUInt32LE(16, 16);
  b.writeUInt16LE(1, 20); b.writeUInt16LE(1, 22); b.writeUInt32LE(16000, 24); b.writeUInt32LE(32000, 28); b.writeUInt16LE(2, 32); b.writeUInt16LE(16, 34); b.write('data', 36); b.writeUInt32LE(samples * 2, 40); return b;
}
const artwork = { allowPaid: true, question: '¿Qué hay a la izquierda?', image: 'data:image/jpeg;base64,YWJj', capturedAt: 100_000 };
test('selected artwork has an explicit bounded reference without weakening fresh camera validation', () => {
  const result = validateArtwork(artwork, 160_000);
  assert.equal(result.capturedAt, 100_000); assert.equal(result.artworkReference, true);
  assert.match(makePayload(result, 'muse-spark-1.3').instructions, /NO visión en vivo/);
  assert.throws(() => validateObservation({ ...artwork, mode: 'meta' }, 160_000), /vencida/);
  assert.throws(() => validateArtwork(artwork, 701_000), /venció/);
  assert.throws(() => validateArtwork({ ...artwork, allowPaid: false }, 160_000), /Confirmá/);
});
test('voice checks real WAV headers, consent, sample rate and duration before provider use', () => {
  assert.equal(validateVoice({ allowPaid: true, audio: wav().toString('base64') }).durationMs, 1000);
  assert.throws(() => validateVoice({ audio: wav().toString('base64') }), /Autorizá/);
  const forged = wav(); forged.writeUInt32LE(48000, 24);
  assert.throws(() => validateVoice({ allowPaid: true, audio: forged.toString('base64') }), /WAV/);
  assert.throws(() => validateVoice({ allowPaid: true, audio: wav(192001).toString('base64') }), /WAV/);
});
test('ASR multipart and output preserve source while never forwarding secret or raw errors', async () => {
  let captured;
  const voice = validateVoice({ allowPaid: true, audio: wav().toString('base64') });
  const result = await transcribeMeta(voice, { key: 'TEST_SECRET', fetchImpl: async (url, options) => {
    captured = { url, options }; return Response.json({ transcript: '¿Qué colores predominan?' });
  }});
  assert.equal(captured.url, 'https://api.meta.ai/v1/asr/transcribe');
  assert.equal(JSON.parse(await captured.options.body.get('request').text()).languageBias[0], 'Spanish');
  assert.equal(result.model, 'muse-voice-transcribe-1.0'); assert.equal(result.text, '¿Qué colores predominan?');
  assert.ok(!JSON.stringify(result).includes('TEST_SECRET'));
  await assert.rejects(transcribeMeta(voice, { key: 'secret', fetchImpl: async () => new Response('private detail', { status: 401 }) }), e => e.status === 502 && !e.message.includes('private detail'));
});

test('museum HTTP routes share the same provider cap and reject missing consent before charging', async t => {
  const urls = [];
  const server = makeServer({ env: { ENABLE_META: 'true', MODEL_API_KEY: 'TEST_SECRET', MAX_META_CALLS: '2' }, fetchImpl: async url => {
    urls.push(url);
    return url.endsWith('/asr/transcribe') ? Response.json({ transcript: '¿Qué colores predominan?' }) : Response.json({ status: 'completed', output: [{ type: 'message', role: 'assistant', content: [{ type: 'output_text', text: 'Predomina el azul.' }] }] });
  }});
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => { server.closeAllConnections(); server.close(resolve); }));
  const base = `http://127.0.0.1:${server.address().port}`;
  const config = await (await fetch(`${base}/api/config`)).json();
  const post = (path, body) => fetch(base + path, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Luma-Token': config.token }, body: JSON.stringify(body) });
  const audio = wav().toString('base64');
  assert.equal((await post('/api/museum/transcribe', { audio })).status, 400);
  const transcription = await (await post('/api/museum/transcribe', { audio, allowPaid: true })).json();
  assert.equal(transcription.text, '¿Qué colores predominan?');
  const body = { ...artwork, capturedAt: Date.now() - 60_000, question: transcription.text };
  const answer = await (await post('/api/museum/ask', body)).json();
  assert.equal(answer.text, 'Predomina el azul.'); assert.equal(answer.gate.remaining, 0);
  assert.equal((await post('/api/museum/transcribe', { audio, allowPaid: true })).status, 429);
  assert.equal(urls.length, 2);
});
