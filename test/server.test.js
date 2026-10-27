import test from 'node:test';
import assert from 'node:assert/strict';
import { makeServer } from '../server/index.js';

async function running(t, options = {}) {
  const server = makeServer(options); await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => { server.closeAllConnections(); server.close(resolve); }));
  const base = `http://127.0.0.1:${server.address().port}`;
  const config = await (await fetch(`${base}/api/config`)).json();
  const post = body => fetch(`${base}/api/observe`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Luma-Token': config.token }, body: JSON.stringify(body) });
  return { base, config, post };
}
test('default mode needs no credential or provider; source and env cannot be fetched', async t => {
  let calls = 0;
  const { base, config, post } = await running(t, { env: {}, fetchImpl: async () => { calls += 1; throw new Error(); } });
  assert.equal(config.metaEnabled, false);
  const result = await (await post({ mode: 'fixture', question: 'Describí la obra' })).json();
  assert.equal(result.mode, 'fixture'); assert.equal(result.estimatedUsd, 0); assert.equal(calls, 0);
  assert.equal((await fetch(`${base}/.env`)).status, 404);
  assert.equal((await fetch(`${base}/server/index.js`)).status, 404);
  assert.equal((await fetch(`${base}/api/config`, { headers: { Origin: 'https://untrusted.example' } })).status, 403);
});
test('server blocks missing local token and does not silently enable paid calls with a key alone', async t => {
  const { base, config, post } = await running(t, { env: { MODEL_API_KEY: 'TEST_SECRET' } });
  assert.equal(config.metaEnabled, false); assert.ok(!JSON.stringify(config).includes('TEST_SECRET'));
  assert.equal((await fetch(`${base}/api/observe`, { method: 'POST', body: '{}' })).status, 403);
  const response = await post({ mode: 'meta', question: 'Ver', allowPaid: true, capturedAt: Date.now(), image: 'data:image/jpeg;base64,YWJj' });
  assert.equal(response.status, 503);
});
test('live-shaped route uses a stubbed provider and enforces process cap', async t => {
  const { post } = await running(t, { env: { ENABLE_META: 'true', MODEL_API_KEY: 'TEST_SECRET', MAX_META_CALLS: '1' }, fetchImpl: async () => Response.json({ status: 'completed', output: [{ type: 'message', role: 'assistant', content: [{ type: 'output_text', text: 'Una obra.' }] }], usage: { input_tokens: 1000, output_tokens: 100 } }) });
  const body = { mode: 'meta', question: 'Ver', allowPaid: true, capturedAt: Date.now(), image: 'data:image/jpeg;base64,YWJj' };
  const result = await (await post(body)).json();
  assert.equal(result.gate.remaining, 0); assert.equal(result.mode, 'meta');
  assert.equal((await post(body)).status, 429);
});
