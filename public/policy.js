// Sequential observations; no camera/video semantics are implied by this scheduler.
export class ObservationPolicy {
  constructor({ minIntervalMs = 10_000, maxObservations = 12, maxDurationMs = 120_000 } = {}) {
    this.interval = minIntervalMs; this.max = maxObservations; this.duration = maxDurationMs;
    this.reset();
  }
  reset(now = Date.now()) { this.started = now; this.last = -Infinity; this.count = 0; this.busy = false; }
  next(now = Date.now()) {
    if (now - this.started >= this.duration || this.count >= this.max) return 'finished';
    if (this.busy || now - this.last < this.interval) return 'wait';
    return 'capture';
  }
  begin(now = Date.now()) {
    if (this.next(now) !== 'capture') return false;
    this.busy = true; this.last = now; this.count += 1; return true;
  }
  end() { this.busy = false; }
}

export function summarizeMetrics(rows) {
  const provider = rows.filter(row => row.mode === 'meta');
  const completed = provider.filter(row => row.status === 'completed');
  const sorted = completed.map(row => row.roundTripMs).sort((a, b) => a - b);
  const percentile = p => sorted.length ? sorted[Math.max(0, Math.ceil(sorted.length * p) - 1)] : null;
  return {
    source: 'browser-phone-or-webcam-prototype',
    metaAttempts: provider.length, completed: completed.length,
    failedOrCancelled: provider.length - completed.length,
    roundTripMedianMs: percentile(0.5), roundTripP95Ms: percentile(0.95),
    knownEstimatedUsd: completed.reduce((sum, row) => sum + (row.estimatedUsd || 0), 0),
    unknownBillingAttempts: provider.filter(row => row.status !== 'completed' || row.estimatedUsd == null).length,
  };
}
