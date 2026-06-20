# login_uix — UIx substrate login

The canonical login feature, rendered through the UIx adapter. Same
dataflow, schemas, machine, and HTTP stub as
[`examples/reagent/login/`](../../reagent/login/); only the view
layer differs.

## What this demonstrates

- **`defui` components consuming subs via `use-subscribe`** — the
  React-hooks idiom replaces Reagent's RAtom indirection. Views are
  plain `defui`; `reg-view` stays Reagent-only.
- **Substrate-agnostic Spec surfaces** — the Spec 005 state
  machine, Spec 010 schemas, and Spec 014 managed-HTTP surfaces work
  unchanged under UIx. The same registrations, the same machine
  transitions, the same canned-stub seam.
- **Cross-substrate parity at the tag layer** — machine states
  carry Spec 005 `:tags` (`:auth/busy`, `:auth/authenticated`,
  `:auth/locked`); the view reads them via the `:rf/machine-has-tag?`
  framework sub. The terminal `:locked-out` state (reached after the
  fourth failed submit) is surfaced as a non-interactive
  locked-account panel rather than a dead-but-enabled form — same
  tag + locked-panel pattern as the state-machines walkthrough; only
  the hook idiom differs.
- **No auto-injection** — UIx components take `dispatch` off a
  `(rf/frame-handle)` and call `use-subscribe` directly. The component
  layer is explicit; the artefact layer beneath is identical.

## Why this shape

Substrate parity demonstration. Pair with
[`examples/reagent/login/`](../../reagent/login/) (the reference) and
[`examples/helix/login_helix/`](../../helix/login_helix/) (the Helix
twin) to see exactly which layers are substrate-agnostic and which
are substrate-specific. The same login feature in three substrates —
identical registries, three view layers.

The folder name carries the `_uix` substrate suffix so the
top-level namespace doesn't collide with `examples/reagent/login/` on
the classpath.

## Files

```
login_uix/
  core.cljs    — schema + events + subs + machine + defui views + mount.
  index.html   — minimal host page.
```

## How to run

```bash
# From implementation/:
npm run dev:example -- examples/login-uix
```

One command: it stages this folder's hand-written
[`index.html`](index.html) + the shared `_shared/` assets next to the
compiled `main.js`, starts `shadow-cljs watch` (edits recompile live),
serves `out/examples/login-uix/` on a free local port, and prints the
URL to open. Add `--no-watch` for a one-shot compile-and-serve.

(`npm run test:adapter-smokes` does not build this example — it compiles and
serves only the three adapter testbeds; see
[`examples/uix/README.md`](../README.md).) Examples are test-free
per [`examples/README.md`](../../README.md).

<details><summary>Advanced: raw <code>shadow-cljs watch</code></summary>

`npm run dev:example` wraps the raw watch + manual staging recipe. To
drive shadow-cljs directly: `shadow-cljs watch examples/login-uix`
emits `main.js` into `out/examples/login-uix/`; you then copy this
folder's [`index.html`](index.html) (and the shared assets under
[`../../_shared/`](../../_shared/)) alongside it and serve the output
dir yourself.

</details>

## Cross-references

- [`examples/reagent/login/`](../../reagent/login/) — the canonical Reagent reference.
- [`examples/helix/login_helix/`](../../helix/login_helix/) — the Helix twin.
- [`spec/005-StateMachines.md`](../../../spec/005-StateMachines.md), [`spec/010-Schemas.md`](../../../spec/010-Schemas.md), [`spec/014-HTTPRequests.md`](../../../spec/014-HTTPRequests.md) — the substrate-agnostic surfaces.
- [`spec/006-ReactiveSubstrate.md`](../../../spec/006-ReactiveSubstrate.md) — the substrate contract the UIx adapter satisfies.
- [`implementation/adapters/uix/`](../../../implementation/adapters/uix/) — the adapter implementation.
