// Published Standard rates, checked 2026-10-27. Estimates, not an invoice.
export const RATES = Object.freeze({
  'muse-spark-1.3': { input: 1.25, cached: 0.15, output: 4.25 },
  'muse-wonder-1.0': { input: 4, cached: 0.20, output: 20 },
});

export function tokenCost(model, usage) {
  const rate = RATES[model];
  if (!rate || !usage || !Number.isFinite(usage.input_tokens) || !Number.isFinite(usage.output_tokens)) return null;
  const input = Math.max(0, usage.input_tokens);
  const output = Math.max(0, usage.output_tokens);
  const cached = Math.min(input, Math.max(0, usage.input_tokens_details?.cached_tokens || 0));
  return ((input - cached) * rate.input + cached * rate.cached + output * rate.output) / 1e6;
}

export function visualEstimate(images, tokensPerImage = 1500, model = 'muse-spark-1.3') {
  if (!Number.isFinite(images) || images < 0 || !Number.isFinite(tokensPerImage) || tokensPerImage < 0 || !RATES[model]) throw new Error('Invalid cost assumptions');
  return images * tokensPerImage * RATES[model].input / 1e6;
}
