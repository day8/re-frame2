'use strict';
// EVERY RETURN THE EGRESS DOOR REFUSES, AND THE TWO IT ADMITS.
//
// A string is the whole of the accepted set, so each refused entry returns
// one of the values a render module plausibly reaches for instead:
// `undefined` from falling off the end, `null` meaning "nothing to say", a
// number, a plain object, and promises resolving to those — the last
// because `render` may be async and the door must read the RESOLVED value.
//
// None of them carries a payload, and that is deliberate. `leaky.cjs`
// already proves the door refuses a module that hands over application
// data; this fixture isolates the OTHER half of the claim — that the
// refusal is about the SHAPE of what came back, not about what happened to
// be found on it — so the rows it drives cannot be read as a second run of
// the leak.
//
// The two string entries are the control: a door that refused everything
// would satisfy every refused row.

const RETURNS = Object.freeze({
  'app/undefined': () => undefined,
  'app/null': () => null,
  'app/number': () => 42,
  'app/object': () => ({}),
  'app/promise-of-null': () => Promise.resolve(null),
  'app/promise-of-object': () => Promise.resolve({}),
  'app/string': () => '<p>a string</p>',
  'app/promise-of-string': () => Promise.resolve('<p>a promised string</p>'),
});

const ENTRY = { stateAllowlist: [], runtimeAllowlist: [] };

module.exports = {
  protocol: 1,
  buildId: 'non-string-return-build-1',
  entries: Object.fromEntries(Object.keys(RETURNS).map((entry) => [entry, ENTRY])),

  render({ entry }) {
    return RETURNS[entry]();
  },
};
