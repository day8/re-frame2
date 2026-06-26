# counter_uix — UIx substrate counter

This is the [Reagent counter](../../reagent/counter/) again — same minus
button, same plus button, same number — but rendered through a different
[substrate](../../../docs/guide/glossary.md#substrate). That sounds like a
trivial difference, and the surprising payoff is that it almost is. The
[events](../../../docs/guide/glossary.md#event), the
[subscription](../../../docs/guide/glossary.md#subscription), and the
[app-db](../../../docs/guide/glossary.md#app-db) are copied across *unchanged* —
character for character — and everything that moves between the two
examples lives in one place: how the view reads state and dispatches.

That's the whole reason to read this one. Open it side by side with its
Reagent twin and the seam jumps out at you. Below the view there's no seam
at all, because re-frame2's core is substrate-agnostic; the registrations
genuinely don't know which React-family library is rendering them. Above
the view, you get to write in the idiom your substrate prefers. Here that
idiom is hooks — UIx is "hooks all the way down" — so instead of Reagent's
reactive ratom you call a `use-subscribe` hook, and instead of letting a
macro hand you `dispatch`, you pull it off a frame-handle yourself. The
[adapter](../../../docs/guide/glossary.md#adapter) is the small map of glue
that makes that swap a one-liner.

## What this demonstrates

- **`init!` with the UIx adapter** —
  `(rf/init! uix-adapter/adapter)`. The
  [adapter](../../../docs/guide/glossary.md#adapter) is a *value* — the map of
  glue functions binding re-frame2's core to UIx — and you pass it in
  directly. There's no registry of named substrates to look up; to render
  on UIx instead of Reagent you require *its* adapter and hand
  [`init!`](../../../docs/guide/glossary.md#init) that. One line moves.
- **`reg-event` / `reg-sub`, byte-for-byte identical** — the three
  [event handlers](../../../docs/guide/glossary.md#event-handler) and the one
  [subscription](../../../docs/guide/glossary.md#subscription) are lifted
  verbatim from the Reagent counter. Each handler is a pure function
  returning a `{:db …}` [effect map](../../../docs/guide/glossary.md#effect-map);
  the sub reads the count straight out of
  [app-db](../../../docs/guide/glossary.md#app-db). They sit *above* the
  substrate boundary and are blissfully unaware there's a boundary there at
  all — which is exactly the point being made.
- **`use-subscribe`, the hooks idiom** — the view calls
  `(uix-adapter/use-subscribe [:counter/value])` directly. This is the React
  way to read derived state: a hook, not a dereferenced reactive atom.
  Same subscription, same cached value, native ergonomics.
- **`dispatch` off a frame-handle** — UIx has no `reg-view` macro quietly
  injecting `dispatch` for you (that convenience stays Reagent-only; UIx
  users write `defui` directly). So the view grabs a
  [frame-handle](../../../docs/guide/glossary.md#frame-handle) —
  `(:dispatch (rf/frame-handle))` — and closes over it. A frame-handle is a
  frame captured *as a value*: it pins the render-time frame, so the
  closed-over `dispatch` keeps firing into the right frame even from an
  async callback, instead of raising `:rf.error/no-frame-context` once the
  surrounding scope is gone.
- **A shared frame-context** — under the hood the UIx and Reagent adapters
  resolve their frame through the *same* React Context object. The
  cross-substrate parity isn't a coincidence maintained in three places;
  it's one runtime mechanism the adapters share.

## Why this shape

This example is the counter half of the UIx **curated example subset** —
counter + login — per [Spec 006 §Adapter shipping convention Decision
7](../../../spec/006-ReactiveSubstrate.md) ("Curated example set"). That
pair is the curated subset chosen to exercise the substrate contract;
inside this tree it carries compile coverage only (the runtime
substrate-contract smoke is the adapter testbed at
[`implementation/adapters/uix/testbed/spec.cjs`](../../../implementation/adapters/uix/testbed/spec.cjs)).
`dashboard_uix` is a documented design-led example alongside it, not part
of the Decision-7 curated example subset.

The most instructive way to read it is as one corner of a triangle. Set it
beside [`examples/reagent/counter/`](../../reagent/counter/) and
[`examples/helix/counter_helix/`](../../helix/counter_helix/) and you have
the same application rendered three ways. The events, subscription, and
app-db are the constant; the substrate is the variable. Diff any two and
what's left is precisely the
[adapter](../../../docs/guide/glossary.md#adapter)'s job description — which
is a far more convincing argument that the core is substrate-agnostic than
any paragraph (including this one) could make.

One mundane housekeeping note: the folder carries the `-uix` namespace
suffix so its top-level namespace doesn't collide with
`examples/reagent/counter/` on the classpath. Both want to be *the*
counter; the suffix lets them coexist.

## Files

```
counter_uix/
  core.cljs    — events, sub, defui view, mount.
  index.html   — minimal host page.
```

## How to run

```bash
# From implementation/:
npm run dev:example -- examples/counter-uix
```

One command: it stages this folder's hand-written
[`index.html`](index.html) + the shared `_shared/` assets next to the
compiled `main.js`, starts `shadow-cljs watch` (edits recompile live),
serves `out/examples/counter-uix/` on a free local port, and prints the
URL to open. Add `--no-watch` for a one-shot compile-and-serve.

(`npm run test:adapter-smokes` does not build this example — it compiles and
serves only the three adapter testbeds; see
[`examples/uix/README.md`](../README.md).) Examples are
test-free per [`examples/README.md`](../../README.md); the UIx adapter
smoke lives at
[`implementation/adapters/uix/testbed/spec.cjs`](../../../implementation/adapters/uix/testbed/spec.cjs).

<details><summary>Advanced: raw <code>shadow-cljs watch</code></summary>

`npm run dev:example` wraps the raw watch + manual staging recipe. To
drive shadow-cljs directly: `shadow-cljs watch examples/counter-uix`
emits `main.js` into `out/examples/counter-uix/`; you then copy this
folder's [`index.html`](index.html) (and the shared assets under
[`../../_shared/`](../../_shared/)) alongside it and serve the output dir
yourself.

</details>

## Cross-references

- [`spec/006-ReactiveSubstrate.md`](../../../spec/006-ReactiveSubstrate.md) — the substrate contract the UIx adapter satisfies.
- [`spec/Conventions.md` §Adapter test matrix policy](../../../spec/Conventions.md#adapter-test-matrix-policy) — why all three substrate counters exist.
- [`examples/reagent/counter/`](../../reagent/counter/) + [`examples/helix/counter_helix/`](../../helix/counter_helix/) — the other two substrate variants.
- [`implementation/adapters/uix/`](../../../implementation/adapters/uix/) — the adapter implementation.
