# counter_helix — Helix substrate counter

The canonical counter, rendered through Helix instead of Reagent. Here's
the load-bearing trick, and it's worth dwelling on: the events and the
subscription in this example are **byte-for-byte identical** to the ones
in the [Reagent](../../reagent/counter/) and [UIx](../../uix/counter_uix/)
counters. Not "similar." Not "ported." The same characters on disk. Only
the view layer at the bottom of the file changes.

That's the whole pitch for re-frame2's [substrate](../../../docs/guide/glossary.md#substrate)
story in one example. Your [events](../../../docs/guide/glossary.md#event),
your [subscriptions](../../../docs/guide/glossary.md#subscription), and your
[app-db](../../../docs/guide/glossary.md#app-db) don't know — and aren't
allowed to know — which React-family library is painting pixels underneath.
Swap the [adapter](../../../docs/guide/glossary.md#adapter) and the same
state model lights up a different renderer.

## What this demonstrates

- **Installing the Helix adapter** — `(rf/init! helix-adapter/adapter)`.
  An [adapter](../../../docs/guide/glossary.md#adapter) is a *value* — the
  little map of glue that binds re-frame2 to a rendering library — and you
  pass it in by hand. There's no default-adapter registry doing it behind
  your back. (`init!` installs the adapter; it does **not** create a
  [frame](../../../docs/guide/glossary.md#frame) — see the mount below.)
- **`reg-event` / `reg-sub`, substrate-agnostic** — the `:counter/*`
  [event handlers](../../../docs/guide/glossary.md#event-handler) and the
  `:counter/value` [subscription](../../../docs/guide/glossary.md#subscription)
  are the artefact layer, and they are exactly the registrations the
  Reagent and UIx counters use. The code that computes *what your app does*
  has no idea what's below it.
- **Reading state with the `use-subscribe` hook** — Helix's idiom is hooks
  all the way down, so a component reads a subscription by calling
  `(helix-adapter/use-subscribe [:counter/value])` directly, getting back
  the live value.
- **Dispatching off a frame-handle** — the click handlers take `dispatch`
  off a [`(rf/frame-handle)`](../../../docs/guide/glossary.md#frame-handle)
  and close over it. A frame-handle is a frame captured *as a value*; it
  pins the render-time frame, so the closed-over `dispatch` keeps aiming at
  the right frame even when fired later from an async callback. There's no
  auto-injection in Helix — `reg-view` stays a Reagent-only convenience, and
  Helix users write `defnc` and wire `dispatch` themselves.
- **The shared frame-context** — the render is wrapped in
  `frame-provider-existing`, the same React Context machinery the Reagent
  and UIx adapters consume. The substrate boundary is real, but it sits at
  the runtime layer, not in three parallel universes.

## Why this shape

Read this example next to its two siblings —
[`examples/reagent/counter/`](../../reagent/counter/) and
[`examples/uix/counter_uix/`](../../uix/counter_uix/) — and the substrate
boundary stops being an abstract claim and becomes a diff you can run your
eye down. They're the same app three times, sharing every line of model and
differing only in the view.

Why does the framework even *carry* a Helix counter? Because counter + login
is the representative pair that proves the Helix adapter actually satisfies
the substrate contract — the curated smoke subset per
[Spec 006 §CLJS reference: Helix as alternative substrate, item 7](../../../spec/006-ReactiveSubstrate.md#cljs-reference-helix-as-alternative-substrate).
(`process_monitor_helix` is a separate, design-led example that sits
*alongside* the subset, not part of it.)

### The substrate boundary — same model, three view layers

Open `core.cljs` and you'll find a literal `SUBSTRATE BOUNDARY` divider
drawn across the file. Above it: the **substrate-agnostic artefact layer** —
the `:counter/*` events and the `:counter/value` sub. Those lines are
identical, character for character, in all three counters; the artefact
layer never names a substrate. Below it: the **only** substrate-specific
code in the example — the Helix `defnc` views and the mount.

The instinct of every seasoned engineer here is to recoil. Duplicated code
across three folders? Surely that's copy-paste rot waiting to bite someone.
But the duplication is the *point*, and it's load-bearing rather than lazy:
the id-identity **is** the parity demonstration. Byte-identical events and a
byte-identical sub, driving a Reagent `reg-view`, a UIx `defui`, and a Helix
`defnc`, is the proof that the adapter contract is the whole story — that
nothing leaks across the boundary. Hoist the model into a shared namespace
and you'd lose exactly that proof. Each substrate counter is a self-contained
`:browser` build, and `npm run test:bundle-isolation` greps every released
bundle to confirm a Helix `main.js` carries no Reagent or UIx code (and vice
versa). A shared model required into all three builds would defeat both the
isolation *and* the parity claim it underwrites. The rationale and its four
bounding conditions are catalogued in
[`examples/TESTING.md` §Exception 2](../../TESTING.md#exception-2--the-cross-substrate-reagentuixhelix-id-share).

One last detail you'll notice in the source: the folder carries the `_helix`
namespace suffix (`counter-helix.core`) purely so its top-level namespace
doesn't collide with the Reagent and UIx siblings on the classpath. Nothing
deeper than housekeeping.

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
