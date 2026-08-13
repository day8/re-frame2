'use strict';
// THE ONE VALUE THE EGRESS DOOR USED TO ADMIT.
//
// It emits perfectly good body markup and then returns `null` — and for
// one commit that passed as a clean success, because the door read
// `out !== undefined && out !== null` and `null` fell through the gap
// between the two clauses.
//
// The gap matters more than its width. A function that falls off its end
// returns `undefined`, so `undefined` is what ABSENCE looks like here;
// `null` is a value someone typed, and `return null` is the spelling a
// render module reaches for to mean "nothing to say" — the most likely
// deliberate return this contract will ever be handed, not an exotic one.
// The single exception was sitting on the most probable path.
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
  entries: { 'app/root': { stateAllowlist: [':todos'] } },

  render(_call, emit) {
    emit('<p>null-return</p>');
    // Written out rather than left implicit, because the whole point is
    // that this is not the same event as falling off the end.
    return null;
  },
};
