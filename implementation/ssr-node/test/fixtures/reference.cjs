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
// It reports what it observed by RENDERING it — the one channel the
// contract gives a render module, and the reason `test/observations.cjs`
// exists. It returns nothing, because a render module has nothing to
// return.

const { encode } = require('../observations.cjs');

/** Object identities this isolate has already been handed. */
const seen = new WeakSet();

/** Live renders in this isolate, and the high-water mark. Guarantee 3. */
let inFlight = 0;
let overlapMax = 0;

/** What a render of this module observed. Shared with its in-process control. */
function observe({ entry, state, args }) {
  const seenBefore = seen.has(state);
  seen.add(state);

  let mutationThrew = false;
  try {
    // The snapshot is frozen; in strict mode this throws.
    state[':todos'] = 'MUTATED';
  } catch {
    mutationThrew = true;
  }

  return {
    entry,
    args: args ?? null,
    seenBefore,
    frozen: Object.isFrozen(state),
    mutationThrew,
    // What the module actually READ — so a test can prove one request
    // never saw another's state.
    readTodos: state[':todos'] ?? null,
    readRoute: state[':route'] ?? null,
    overlapMax,
    threadId: require('node:worker_threads').threadId,
  };
}

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

  async render(call, emit) {
    inFlight += 1;
    overlapMax = Math.max(overlapMax, inFlight);
    try {
      const observed = observe(call);
      const { entry, state } = call;

      const delay = Number(state[':delay'] ?? 0);
      if (delay > 0) await new Promise((r) => setTimeout(r, delay));

      // The overlap high-water mark has to be read AFTER the delay, or it
      // could never see an overlap it was measuring.
      observed.overlapMax = overlapMax;

      emit(`<div data-entry="${entry}"${encode(observed)}>${state[':todos'] ?? ''}</div>`);
    } finally {
      inFlight -= 1;
    }
  },
};
