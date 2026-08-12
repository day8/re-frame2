'use strict';
// A WELL-BEHAVED REFERENCE RENDER MODULE.
//
// It stands in for an application's compiled server bundle. It renders no
// React and requires no build, because every guarantee under test is a
// property of the SERVICE rather than of a renderer — and a suite that
// needed a shadow-cljs compile to check an allowlist would be a suite
// nobody runs.
//
// `'use strict'` is deliberate and load-bearing: a strict module takes a
// TypeError when it writes to a frozen snapshot, which is what makes the
// freeze visible. `sloppy.cjs` is its control.
//
// Everything this module reports back rides `meta`, which the service
// passes through untouched.

/** Object identities this isolate has already been handed. */
const seen = new WeakSet();

/** Live renders in this isolate, and the high-water mark. Guarantee 3. */
let inFlight = 0;
let overlapMax = 0;

module.exports = {
  protocol: 1,
  buildId: 'reference-build-1',
  entries: {
    'app/root': { stateAllowlist: [':todos', ':route', ':delay', ':bytes'] },
    'app/other': { stateAllowlist: [':route'] },
  },

  booted: false,
  boot() {
    module.exports.booted = true;
  },

  async render({ entry, state, args }, emit) {
    inFlight += 1;
    overlapMax = Math.max(overlapMax, inFlight);
    try {
      const seenBefore = seen.has(state);
      seen.add(state);

      let mutationThrew = false;
      try {
        // The snapshot is frozen; in strict mode this throws.
        state[':todos'] = 'MUTATED';
      } catch {
        mutationThrew = true;
      }

      const delay = Number(state[':delay'] ?? 0);
      if (delay > 0) await new Promise((r) => setTimeout(r, delay));

      emit(`<div data-entry="${entry}">${state[':todos'] ?? ''}</div>`);

      return {
        meta: {
          entry,
          args: args ?? null,
          seenBefore,
          frozen: Object.isFrozen(state),
          mutationThrew,
          // What the module actually READ — so a test can prove one
          // request never saw another's state.
          readTodos: state[':todos'] ?? null,
          readRoute: state[':route'] ?? null,
          overlapMax,
          threadId: require('node:worker_threads').threadId,
        },
      };
    } finally {
      inFlight -= 1;
    }
  },
};
