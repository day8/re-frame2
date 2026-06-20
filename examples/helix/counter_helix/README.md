# counter_helix — Helix substrate counter

The canonical counter, rendered through the Helix adapter. Same six
dominoes as [`examples/reagent/counter/`](../../reagent/counter/);
different substrate.

## What this demonstrates

- **`rf/init!` with the Helix adapter** — `(rf/init! helix-adapter/adapter)`.
  No default-adapter registry; the adapter spec map is passed
  explicitly.
- **`reg-event` / `reg-sub`** —
  *substrate-agnostic*. The exact same registrations as the Reagent
  and UIx counters; the artefact layer doesn't know which substrate
  is below.
- **`use-subscribe` hook (Helix idiomatic)** — components call
  `(helix-adapter/use-subscribe [:counter/value])` directly.
- **`(:dispatch (rf/frame-handle))` for click handlers** — Helix
  components take `dispatch` off a `(rf/frame-handle)` and close over
  it. The handle captures the render-time frame, so the closed-over
  `dispatch` keeps targeting that frame even from an async callback.
  There is no auto-injection in Helix — `reg-view` stays Reagent-only;
  Helix users write `defnc` directly.
- **Shared frame-context** — the same React Context object the
  Reagent and UIx adapters consume. Cross-substrate parity is at the
  runtime layer.

## Why this shape

This example is the counter half of the Helix **smoke-test subset** —
counter + login — per [Spec 006 §CLJS reference: Helix as alternative
substrate Decision 7](../../../spec/006-ReactiveSubstrate.md#cljs-reference-helix-as-alternative-substrate).
That pair is what confirms the Helix adapter implements the substrate
contract; `process_monitor_helix` is a documented design-led example
alongside it, not part of the Decision-7 smoke subset. Pair with
[`examples/reagent/counter/`](../../reagent/counter/) and
[`examples/uix/counter_uix/`](../../uix/counter_uix/) to see the
substrate boundary cleanly.

### The substrate boundary — same model, three view layers

`core.cljs` carries a `SUBSTRATE BOUNDARY` divider. Above it is the
**substrate-agnostic artefact layer** — the `:counter/*` events and the
`:counter/value` sub. Those lines are byte-for-byte identical in the
Reagent and UIx counters; the artefact layer never names a substrate.
Below the divider is the **only** substrate-specific code: the Helix
`defnc` view + the mount.

That duplication across the three counters is **deliberate and the
intended v2 style**, not copy-paste drift waiting to happen. The
id-identity *is* the cross-substrate parity demonstration: byte-identical
events + sub driving Reagent `reg-view`, UIx `defui`, and Helix `defnc`
proves the adapter contract is the whole story. It is intentionally **not**
hoisted into a shared model namespace — each substrate counter is a
self-contained `:browser` build, and `npm run test:bundle-isolation` greps
each released bundle to prove a Helix `main.js` carries no Reagent/UIx code
(and vice versa). A shared model required into all three builds would
defeat that isolation and the parity claim it underwrites. The rationale
and its four bounding conditions are catalogued in
[`examples/TESTING.md` §Exception 2](../../TESTING.md#exception-2--the-cross-substrate-reagentuixhelix-id-share).

The folder name carries the `_helix` namespace suffix so the
top-level namespace doesn't collide with Reagent or UIx siblings on
the classpath.

## Files

```
counter_helix/
  core.cljs    — events, sub, defnc view, mount.
  index.html   — minimal host page.
```

## How to run

```bash
# From implementation/:
npm run dev:example -- examples/counter-helix
```

One command: it stages this folder's hand-written
[`index.html`](index.html) + the shared `_shared/` assets next to the
compiled `main.js`, starts `shadow-cljs watch` (edits recompile live),
serves `out/examples/counter-helix/` on a free local port, and prints the
URL to open. Add `--no-watch` for a one-shot compile-and-serve.

(`npm run test:adapter-smokes` does not build this example — it compiles and
serves only the three adapter testbeds; see
[`examples/helix/README.md`](../README.md).) Examples are
test-free per [`examples/README.md`](../../README.md); the Helix
adapter smoke lives at
[`implementation/adapters/helix/testbed/spec.cjs`](../../../implementation/adapters/helix/testbed/spec.cjs).

<details><summary>Advanced: raw <code>shadow-cljs watch</code></summary>

`npm run dev:example` wraps the raw watch + manual staging recipe. To
drive shadow-cljs directly: `shadow-cljs watch examples/counter-helix`
emits `main.js` into `out/examples/counter-helix/`; you then copy this
folder's [`index.html`](index.html) (and the shared assets under
[`../../_shared/`](../../_shared/)) alongside it and serve the output dir
yourself.

</details>

## Cross-references

- [`spec/006-ReactiveSubstrate.md`](../../../spec/006-ReactiveSubstrate.md) — the substrate contract the Helix adapter satisfies.
- [`spec/Conventions.md` §Adapter test matrix policy](../../../spec/Conventions.md#adapter-test-matrix-policy) — why all three substrate counters exist.
- [`examples/reagent/counter/`](../../reagent/counter/) + [`examples/uix/counter_uix/`](../../uix/counter_uix/) — the other two substrate variants.
- [`implementation/adapters/helix/`](../../../implementation/adapters/helix/) — the adapter implementation.
