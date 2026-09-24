'use strict';
// THE ONE VALUE AN EGRESS DOOR IS LIKELIEST TO ADMIT BY MISTAKE.
//
// It emits perfectly good body markup and then returns `null` — which a
// door reading `out !== undefined && out !== null` would pass as a clean
// success, `null` falling through the gap between the two clauses.
//
// The gap matters more than its width. A function that falls off its end
// returns `undefined`, so `undefined` is what ABSENCE looks like here;
// `null` is a value someone typed, and `return null` is the spelling a
// render module reaches for to mean "nothing to say" — the most likely
// deliberate return this contract will ever be handed, not an exotic one.
// Such an exception would sit on the most probable path.
//
// It carries no payload at all, and that is deliberate. `leaky.cjs`
// already proves the door refuses a module that hands over application
// data; this fixture isolates the OTHER half of the claim — that the
// refusal is about a module reaching for a second channel, not about what
// happened to be found on it — so the rows it drives cannot be read as a
// second run of the leak.

module.exports = {
  protocol: 1,
  buildId: 'null-return-build-1',
  entries: { 'app/root': { stateAllowlist: [':todos'], runtimeAllowlist: [] } },

  render(_call, emit) {
    emit('<p>null-return</p>');
    // Written out rather than left implicit, because the whole point is
    // that this is not the same event as falling off the end.
    return null;
  },
};
