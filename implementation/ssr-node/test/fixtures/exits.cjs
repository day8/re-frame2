'use strict';
// A render under which the ISOLATE THREAD EXITS — cleanly, with no
// exception anywhere.
//
// `throws-async.cjs` is the neighbouring shape and it is NOT the same
// receiver. There an uncaught exception kills the thread, so Node raises
// `'error'` on the parent's `Worker` and `isolate.cjs`'s `worker.on('error')`
// arm builds the refusal. Here nothing throws: `process.exit()` inside a
// worker stops THAT THREAD rather than the program, so the only event the
// parent ever sees is `'exit'`, and the sibling arm — `worker.on('exit')` —
// is what answers the caller.
//
// That second arm is why this fixture exists (rf2-rhyi). Both arms reach
// `:rf.ssr-node/isolate-lost`, which covers three distinct causes and
// distinguishes them by detail SHAPE alone, so a consumer that cannot see
// `isolate` and `threadId` on one of them cannot say which isolate went or
// which of the three causes it is looking at.
//
// Not an exotic module either. A render module that pulls in a library
// calling `process.exit()` on a configuration fault — or that does so
// itself — takes this path, and it is the quietest of the three: the
// thread is simply gone, with nothing written anywhere.
//
// TWO ENTRIES, the pair every terminal path here is measured on:
//
//   `app/exits`      — the thread goes before anything is emitted, so the
//                      response is clean and `afterChunks` is 0.
//   `app/exits-torn` — a chunk has already reached the caller, so the
//                      response is TORN and the count says so.

const ENTRY = { stateAllowlist: [':for-exit'], runtimeAllowlist: [] };

module.exports = {
  protocol: 1,
  buildId: 'exits-build-1',
  entries: {
    'app/exits': ENTRY,
    'app/exits-torn': ENTRY,
  },

  render({ entry }, emit) {
    if (entry === 'app/exits-torn') emit('<p>first</p>');

    // On a later tick, so the render has genuinely handed its promise back
    // and the service is waiting on a thread that then disappears.
    setImmediate(() => process.exit(0));

    // And the render never finishes: the only thing that can settle this
    // request is the thread going out from under it.
    return new Promise(() => {});
  },
};
