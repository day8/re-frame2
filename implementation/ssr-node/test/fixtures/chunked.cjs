'use strict';
// A MULTI-CHUNK MODULE — the separability requirement's witness.
//
// It emits N chunks the way a streaming renderer would, without being
// one. The point is that no layer between here and the transport is
// allowed to notice: the isolate forwards each chunk, the service yields
// each chunk, and only the buffered HTTP mode ever holds a whole body.
// If a middle layer had joined, this fixture's chunk count would arrive
// as 1.

module.exports = {
  protocol: 1,
  buildId: 'chunked-build-1',
  entries: { 'app/root': { stateAllowlist: [':bytes'] } },

  async render({ state }, emit) {
    const parts = JSON.parse(state[':bytes'] ?? '["<a>","<b>","<c>"]');
    for (const p of parts) {
      // A real streaming render yields to the loop between chunks; so
      // does this, so the chunks cannot arrive in one synchronous burst
      // that would hide an ordering defect.
      await new Promise((r) => setImmediate(r));
      emit(p);
    }
  },
};
