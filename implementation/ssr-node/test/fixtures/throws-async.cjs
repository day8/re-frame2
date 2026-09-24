'use strict';
// A render whose exception ESCAPES THE RENDER CALL — thrown from a callback
// the render scheduled, on a tick after `render` already handed its promise
// back to the service.
//
// `throws-data.cjs` is the synchronous thrower: it throws while the service
// is inside `await renderModule.render(...)`, so `worker.cjs`'s own
// try/catch is holding the stack and the exception door closes on it. This
// fixture is the shape that try/catch cannot see. A callback scheduled with
// `setImmediate`/`setTimeout`, a `.then` with no rejection handler, an
// event emitter's listener — none of them run inside the awaited call, so a
// throw from one is an UNCAUGHT EXCEPTION IN THE WORKER THREAD. Node
// terminates the thread and raises `'error'` on the parent's `Worker`, which
// is a different receiver in a different file with its own refusal to build.
//
// That is not an exotic module. A renderer that kicks off a timer, resolves
// data asynchronously, or subscribes to anything is one bug away from it,
// and the bug is the ordinary kind — the callback dereferences the thing it
// was rendering. So the sentinel is built the way every real one is: out of
// the request state the module was handed.
//
// THE ENTRIES ARE A MATCHED PAIR, which is what makes this a measurement of
// the second door rather than a re-run of the first:
//
//   `app/rejected`  — the SAME Error, reaching the service by rejecting the
//                     promise it returned. Awaited, so `handleRender`
//                     catches it: this is the first door, and it is
//                     here as the control that says the two paths differ.
//   `app/uncaught`  — the same Error, thrown from a scheduled callback while
//                     the returned promise never settles. Nothing awaits it.
//   `app/uncaught-torn` — the same again, after a chunk has already left, so
//                     the response is torn rather than clean.
//   `app/uncaught-null` — a scheduled callback that throws `null`, which is
//                     what CLJS emits for `(throw nil)`. Node hands the
//                     parent's `'error'` listener `null` itself, not an
//                     Error, so a listener that reads `err.message` throws
//                     in the MAIN thread and takes the whole sidecar down.
//
// The Error carries a `code` that is a real member of the service's closed
// refusal family, for the same reason `throws-data.cjs` does: a module with
// its own error taxonomy will set `code`, and a boundary that believed it
// would let a render fault choose its own HTTP status.

const ENTRY = { stateAllowlist: [':for-uncaught'], runtimeAllowlist: [] };

/** A real member of the service's family — see the header. 400, not 500. */
const SPOOFED_CODE = ':rf.ssr-node/unknown-entry';

module.exports = {
  protocol: 1,
  buildId: 'throws-async-build-1',
  entries: {
    'app/rejected': ENTRY,
    'app/uncaught': ENTRY,
    'app/uncaught-torn': ENTRY,
    'app/uncaught-null': ENTRY,
  },

  SPOOFED_CODE,

  render({ entry, state }, emit) {
    const err = new Error(`render callback saw ${state[':for-uncaught']}`);
    err.code = SPOOFED_CODE;
    err.detail = { echoed: state[':for-uncaught'] };

    // The control: awaited, so the exception door closes on it.
    if (entry === 'app/rejected') return Promise.reject(err);

    if (entry === 'app/uncaught-torn') emit('<p>first</p>');

    // The case. Thrown on a later tick, outside every `try` in this
    // process's render path — an uncaught exception in the worker thread.
    // `app/uncaught-null` throws the nullish value instead of the Error.
    const thrown = entry === 'app/uncaught-null' ? null : err;
    setImmediate(() => {
      throw thrown;
    });

    // And the render never finishes, so the only thing that can settle this
    // request is the worker dying under it. A promise that resolved would
    // let `handleRender` complete first and hide the whole path.
    return new Promise(() => {});
  },
};
