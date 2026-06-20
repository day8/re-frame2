# counter_uix — UIx substrate counter

The canonical counter, rendered through the UIx adapter. Same six
dominoes as [`examples/reagent/counter/`](../../reagent/counter/);
different substrate.

## What this demonstrates

- **`rf/init!` with the UIx adapter** — `(rf/init! uix-adapter/adapter)`.
  No default-adapter registry; the adapter spec map is passed
  explicitly.
- **`reg-event` / `reg-sub`** —
  *substrate-agnostic*. The exact same registrations as the Reagent
  counter; the artefact layer doesn't know which substrate is below.
- **`use-subscribe` hook (UIx idiomatic)** — components call
  `(uix-adapter/use-subscribe [:counter/value])` directly. The hook
  is the React idiom; no Reagent-style RAtom indirection.
- **`(:dispatch (rf/frame-handle))` for click handlers** — UIx
  components take `dispatch` off a `(rf/frame-handle)` and close over
  it. The handle captures the render-time frame, so the closed-over
  `dispatch` keeps targeting that frame even from an async callback.
  There is no auto-injection in UIx — `reg-view` stays Reagent-only;
  UIx users write `defui` directly.
- **Shared frame-context** — the same React Context object the
  Reagent and Helix adapters consume. Cross-substrate parity is
  at the runtime layer.

## Why this shape

This example is the counter half of the UIx **curated example subset** —
counter + login — per [Spec 006 §Adapter shipping convention Decision
7](../../../spec/006-ReactiveSubstrate.md) ("Curated example set"). That
pair is the curated subset chosen to exercise the substrate contract;
inside this tree it carries compile coverage only (the runtime
substrate-contract smoke is the adapter testbed at
[`implementation/adapters/uix/testbed/spec.cjs`](../../../implementation/adapters/uix/testbed/spec.cjs)).
`dashboard_uix` is a documented design-led example alongside it, not part
of the Decision-7 curated example subset. Pair with
[`examples/reagent/counter/`](../../reagent/counter/) and
[`examples/helix/counter_helix/`](../../helix/counter_helix/) to see the
substrate boundary cleanly.

The folder name carries the `-uix` namespace suffix so the
top-level namespace doesn't collide with `examples/reagent/counter/`
on the classpath.

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
