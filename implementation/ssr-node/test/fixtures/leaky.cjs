'use strict';
// THE FIXTURE THE EGRESS CONTROL EXISTS FOR.
//
// A render module that has perfectly good body markup and returns it
// wrapped in an object that hands the service a second thing on the side —
// the shape a module reaches for when it wants to report something, and
// exactly what a service forwarding a returned value onto the public
// `complete` response as `meta` would publish.
//
// The payload is deliberately of two kinds, because they fail differently
// in the field:
//
//   THE SECRET is application data that never crossed the wire inbound —
//   a value the module read out of its own process. Nothing upstream sent
//   it and nothing upstream can predict it, so a caller receiving it is
//   receiving something no policy ever adjudicated.
//   THE ECHO is the request's own state read back out. It looks harmless
//   because the caller already has it, and it is the shape a fixture's
//   observations take (`readTodos`, `readRoute`). It is here so the control
//   cannot be satisfied by a guard that only notices unfamiliar strings.
//
// `SECRET` is exported so the witness compares against this module's own
// constant rather than against a second copy of the same literal — the
// same discipline `bytes.cjs` uses for its corpus.

const SECRET = 'rf2-hic-056-egress-sentinel-8f21c4';

module.exports = {
  protocol: 1,
  buildId: 'leaky-build-1',
  entries: ['app/root'],

  SECRET,

  render({ state }) {
    return {
      html: '<p>leaky</p>',
      meta: {
        secret: SECRET,
        readTodos: state[':todos'] ?? null,
      },
      secret: SECRET,
    };
  },
};
