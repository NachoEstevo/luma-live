import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
import { RATES } from './cost.js';
import { createCallGate, HttpError, newSessionToken, validateObservation } from './session.js';
import { observeFixture, observeMeta } from './meta.js';
import { validateArtwork, validateVoice, transcribeMeta } from './museum.js';

const root = fileURLToPath(new URL('../public/', import.meta.url));
const files = new Map([
  ['/', ['index.html', 'text/html; charset=utf-8']],
  ['/app.js', ['app.js', 'text/javascript; charset=utf-8']],
  ['/styles.css', ['styles.css', 'text/css; charset=utf-8']],
  ['/policy.js', ['policy.js', 'text/javascript; charset=utf-8']],
  ['/sample.svg', ['sample.svg', 'image/svg+xml']],
]);

export function makeServer({ env = process.env, fetchImpl = fetch } = {}) {
  const model = env.META_MODEL || 'muse-spark-1.3';
  if (!RATES[model]) throw new Error('META_MODEL must be a supported Standard model.');
  const maxCalls = Number(env.MAX_META_CALLS || 24);
  if (!Number.isInteger(maxCalls) || maxCalls < 1 || maxCalls > 100) throw new Error('MAX_META_CALLS must be between 1 and 100.');
  const gate = createCallGate(maxCalls);
  const token = newSessionToken();
  const enabled = env.ENABLE_META === 'true' && Boolean(env.MODEL_API_KEY?.trim());
  return createServer(async (req, res) => {
    const send = (status, value) => {
      if (!res.destroyed) { res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' }); res.end(JSON.stringify(value)); }
    };
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Referrer-Policy', 'no-referrer');
    res.setHeader('Permissions-Policy', 'camera=(self), microphone=(self)');
    res.setHeader('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'");
    try {
      const port = res.socket.localPort;
      const allowedHosts = [`127.0.0.1:${port}`, `localhost:${port}`];
      if (!allowedHosts.includes(req.headers.host)) throw new HttpError(403, 'Solo acceso local.');
      if (req.headers.origin && !allowedHosts.some(host => req.headers.origin === `http://${host}`)) throw new HttpError(403, 'Origen no permitido.');
      const url = new URL(req.url, 'http://localhost');
      if (req.method === 'GET' && url.pathname === '/api/config') return send(200, { metaEnabled: enabled, model, token, gate: gate.status() });
      if (req.method === 'POST' && ['/api/observe', '/api/museum/ask', '/api/museum/transcribe'].includes(url.pathname)) {
        if (req.headers['x-luma-token'] !== token) throw new HttpError(403, 'Recargá la página para iniciar una sesión local.');
        if (!req.headers['content-type']?.startsWith('application/json')) throw new HttpError(415, 'Se requiere JSON.');
        let size = 0; const chunks = [];
        for await (const chunk of req) {
          size += chunk.length;
          if (size > 2_100_000) throw new HttpError(413, 'Captura demasiado grande.');
          chunks.push(chunk);
        }
        let body;
        try { body = JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { throw new HttpError(400, 'JSON inválido.'); }
        const voice = url.pathname === '/api/museum/transcribe' ? validateVoice(body) : null;
        const observation = voice ? null : url.pathname === '/api/museum/ask' ? validateArtwork(body) : validateObservation(body);
        if (observation?.mode === 'fixture') return send(200, { ...observeFixture(observation.question), gate: gate.status() });
        if (!enabled) throw new HttpError(503, 'Meta no está habilitado en el servidor. Usá demostración o configurá .env.');
        const release = gate.enter();
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), 30_000);
        const disconnect = () => { if (!res.writableEnded) controller.abort(); };
        res.on('close', disconnect);
        try {
          const options = { key: env.MODEL_API_KEY, model, signal: controller.signal, fetchImpl };
          const result = voice ? await transcribeMeta(voice, options) : await observeMeta(observation, options);
          return send(200, { ...result, gate: gate.status() });
        } finally { clearTimeout(timer); res.off('close', disconnect); release(); }
      }
      const file = files.get(url.pathname);
      if (req.method !== 'GET' || !file) throw new HttpError(404, 'No encontrado.');
      const data = await readFile(resolve(root, file[0]));
      res.writeHead(200, { 'Content-Type': file[1] }); res.end(data);
    } catch (error) {
      send(error instanceof HttpError ? error.status : 500, { error: error instanceof HttpError ? error.message : 'Error local inesperado.', gate: gate.status() });
    }
  });
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const port = Number(process.env.PORT || 4173);
  makeServer().listen(port, '127.0.0.1', () => console.log(`Luma Live: http://127.0.0.1:${port} (fixture by default)`));
}
