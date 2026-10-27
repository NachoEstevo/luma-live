import test from 'node:test';
import assert from 'node:assert/strict';
import { makePayload, observeMeta } from '../server/meta.js';

const observation = { question: '¿Qué ves?', image: 'data:image/jpeg;base64,YWJj', history: [] };
test('fixed provider request keeps image in the user turn and requests no provider storage', () => {
  const payload = makePayload(observation, 'muse-spark-1.3');
  assert.equal(payload.store, false); assert.equal(payload.input[0].role, 'user');
  assert.equal(payload.input[0].content[1].image_url, observation.image);
  assert.match(payload.instructions, /No des instrucciones de movilidad/);
});
test('adapter parses measured usage without forwarding credentials or raw provider body', async () => {
  let captured;
  const fetchImpl = async (url, options) => {
    captured = { url, options };
    return Response.json({ status: 'completed', output: [{ type: 'message', role: 'assistant', content: [{ type: 'output_text', text: 'Una pintura.' }] }], usage: { input_tokens: 1500, output_tokens: 100 } });
  };
  const result = await observeMeta(observation, { key: 'TEST_SECRET', model: 'muse-spark-1.3', fetchImpl });
  assert.equal(captured.url, 'https://api.meta.ai/v1/responses');
  assert.equal(captured.options.headers.Authorization, 'Bearer TEST_SECRET');
  assert.equal(result.text, 'Una pintura.'); assert.equal(result.estimatedUsd, 0.0023);
  assert.ok(!JSON.stringify(result).includes('TEST_SECRET'));
});
test('provider errors are sanitized; incomplete responses fail rather than becoming spoken success', async () => {
  await assert.rejects(observeMeta(observation, { key: 'secret', model: 'muse-spark-1.3', fetchImpl: async () => new Response('sensitive details', { status: 401 }) }), error => !error.message.includes('sensitive') && error.status === 502);
  await assert.rejects(observeMeta(observation, { key: 'secret', model: 'muse-spark-1.3', fetchImpl: async () => Response.json({ status: 'incomplete', output: [] }) }), /no completó/);
});

test('cancelled provider work is reported as potentially billable, never as a free success', async () => {
  const controller = new AbortController(); controller.abort();
  await assert.rejects(observeMeta(observation, {
    key: 'TEST_SECRET', model: 'muse-spark-1.3', signal: controller.signal,
    fetchImpl: async () => { throw new Error('TEST_SECRET in a transport exception'); },
  }), error => error.status === 504 && /cobrado/.test(error.message) && !error.message.includes('TEST_SECRET'));
});
