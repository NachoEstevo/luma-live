import test from 'node:test';
import assert from 'node:assert/strict';
import { tokenCost, visualEstimate } from '../server/cost.js';

test('cached tokens are a subset of input; Spark and Wonder have different rates', () => {
  const usage = { input_tokens: 1_000_000, output_tokens: 100_000, input_tokens_details: { cached_tokens: 200_000 } };
  assert.equal(tokenCost('muse-spark-1.3', usage), 1.455);
  assert.equal(tokenCost('muse-wonder-1.0', usage), 5.24);
});
test('unknown usage never gets reported as free', () => {
  assert.equal(tokenCost('muse-spark-1.3', null), null);
  assert.equal(tokenCost('unknown', { input_tokens: 20, output_tokens: 10 }), null);
});
test('visual cost scenarios use explicit hypothetical token assumptions', () => {
  assert.equal(visualEstimate(30), 0.05625);
  assert.equal(visualEstimate(360), 0.675);
  assert.equal(visualEstimate(3600), 6.75);
  assert.throws(() => visualEstimate(-1));
});
