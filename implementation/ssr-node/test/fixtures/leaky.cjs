'use strict';
// THE FIXTURE THE EGRESS CONTROL EXISTS FOR.
//
// A render module that emits perfectly good body markup and then tries to
// hand the service a second thing on the side — which is exactly what the
// well-behaved fixtures in this directory used to do, and exactly what
// this package shipped for one commit forwarding onto the public
// `complete` frame as `meta`.
//
// The payload is deliberately of two kinds, because they fail differently
// in the field:
//
//   THE SECRET is application data that never crossed the wire inbound —
//   a value the module read out of its own process. Nothing upstream sent
//   it and nothing upstream can predict it, so a caller receiving it is
//   receiving something no allowlist ever adjudicated.
//   THE ECHO is the request's own state read back out. It looks harmless
//   because the caller already has it, and it is the shape the fixtures
//   actually had (`readTodos`, `readRoute`). It is here so the control
//   cannot be satisfied by a guard that only notices unfamiliar strings.
//
// `SECRET` is exported so the witness compares against this module's own
// constant rather than against a second copy of the same literal — the
// same discipline `bytes.cjs` uses for its corpus.

const SECRET = 'rf2-hic-056-egress-sentinel-8f21c4';

module.exports = {
  protocol: 1,
  buildId: 'leaky-build-1',
  entries: { 'app/root': { stateAllowlist: [':todos'] } },

  SECRET,

  render({ state }, emit) {
    emit('<p>leaky</p>');
    return {
      meta: {
        secret: SECRET,
        readTodos: state[':todos'] ?? null,
      },
      secret: SECRET,
    };
  },
};
