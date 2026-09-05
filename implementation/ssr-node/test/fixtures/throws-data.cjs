'use strict';
// A render that throws an Error BUILT FROM THE DATA IT WAS HANDED, which
// is the ordinary shape of a renderer exception rather than an exotic one.
//
// `throws.cjs` is the well-behaved thrower: its messages are constants, so
// it can witness that a throw refuses and that a post-emit throw tears,
// but it cannot witness what a refusal CARRIES. Every real renderer
// exception is the other shape — `Cannot read properties of undefined`
// names the property, a validation error quotes the value, a template
// error interpolates the row it was rendering. So the interesting question
// is not whether a module CAN put request state on an Error; it is that
// doing so is the normal case, and the boundary has to hold anyway.
//
// Three sentinels, one per live field, each sourced from a DIFFERENT
// allowlisted state key, so a green scan cannot be one field's accident:
//
//   `:for-code`     → `error.code`     — the field that selects the HTTP status
//   `:for-message`  → `error.message`  — the field a diagnostic reaches for first
//   `:for-detail`   → `error.detail`   — nested, because a shallow scan is not one
//
// The `app/as-*` entries do the second half: instead of an invented code
// they set a code that is a REAL member of this service's closed refusal
// family, which is the arm where nothing looks wrong at all — the refusal
// is well-formed, the status is a plausible one, and it is a lie about
// whose fault the failure was. The literals are spelled out here rather
// than required from `../../src/protocol.cjs` on purpose: an application
// bundle does not import the service's protocol module, and a spoof
// written the way an application would write it is the one worth testing.
// `egress.test.cjs` asserts they really are members, so the row cannot go
// vacuous if the family is renamed.

const SPOOFED_CODE = Object.freeze({
  'app/as-caller-fault': ':rf.ssr-node/unknown-entry', // 400 — "the caller asked for a bad entry"
  'app/as-saturated': ':rf.ssr-node/service-saturated', // 503 — "retry, we are busy"
  'app/as-deadline': ':rf.ssr-node/render-timeout', // 504 — "it ran too long"
});

const ENTRY = {
  stateAllowlist: [':for-code', ':for-message', ':for-detail'],
  runtimeAllowlist: [],
};

module.exports = {
  protocol: 1,
  buildId: 'throws-data-build-1',
  entries: {
    'app/plain': ENTRY, // throws before emitting; invented code
    'app/torn': ENTRY, // emits, THEN throws — the torn response
    'app/as-caller-fault': ENTRY,
    'app/as-saturated': ENTRY,
    'app/as-deadline': ENTRY,
  },

  SPOOFED_CODE,

  render({ entry, state }, emit) {
    if (entry === 'app/torn') emit('<p>first</p>');
    const err = new Error(`render failed while handling ${state[':for-message']}`);
    // `code` is a bare property on an ordinary `Error` — nothing stops a
    // module setting it, and a module that has its own error taxonomy will.
    err.code = SPOOFED_CODE[entry] ?? state[':for-code'];
    err.detail = {
      echoed: state[':for-detail'],
      nested: { deeper: state[':for-detail'] },
    };
    throw err;
  },
};
