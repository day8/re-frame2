# login_helix — Helix substrate login

The canonical login feature, rendered through the Helix adapter.
Same dataflow, schemas, machine, and HTTP stub as
[`examples/reagent/login/`](../../reagent/login/); only the view
layer differs.

## What this demonstrates

- **`defnc` components consuming subs via `use-subscribe`** — the
  React-hooks idiom replaces Reagent's RAtom indirection. Views are
  plain `defnc`; `reg-view` stays Reagent-only.
- **Substrate-agnostic Spec surfaces** — the Spec 005 state
  machine, Spec 010 schemas, and Spec 014 managed-HTTP surfaces work
  unchanged under Helix. The same registrations, the same machine
  transitions, the same canned-stub seam.
- **Cross-substrate parity at the tag layer** — machine states
  carry Spec 005 `:tags` (`:auth/busy`, `:auth/authenticated`,
  `:auth/locked`); the view reads them via the `:rf/machine-has-tag?`
  framework sub. The terminal `:locked-out` state (reached after the
  fourth failed submit) is surfaced as a non-interactive
  locked-account panel rather than a dead-but-enabled form — same
  tag + locked-panel pattern as the state-machines walkthrough; only
  the hook idiom differs.
- **No auto-injection** — Helix components take `dispatch` off a
  `(rf/frame-handle)` and call `use-subscribe` directly. The component
  layer is explicit; the artefact layer beneath is identical.

## Why this shape

Substrate parity demonstration. Pair with
[`examples/reagent/login/`](../../reagent/login/) (the reference)
and [`examples/uix/login_uix/`](../../uix/login_uix/) (the UIx twin)
to see exactly which layers are substrate-agnostic and which are
substrate-specific. The same login feature in three substrates —
identical registries, three view layers.

### The substrate boundary — same model, three view layers

`core.cljs` carries a `SUBSTRATE BOUNDARY` divider. Above it is the
**substrate-agnostic artefact layer** — the Malli schemas, the
`:auth.login.demo/managed-stub` fx, the `:auth.login/flow` state machine
(a named `auth-login-machine` def passed to `reg-machine`, the same shape
all three substrates use), and the named subs. These are in semantic + id
parity with the Reagent and UIx login examples — the same `:auth.login/*`
ids and the same registered shapes; only the per-file explanatory comments
differ. The artefact layer never names a substrate. Below the divider is
the **only** substrate-specific code: the Helix `defnc` views + the mount.

That duplication across the three login examples is **deliberate and the
intended v2 style**, not copy-paste drift. The id-identity *is* the
cross-substrate parity demonstration: the same machine + schemas + HTTP
stub driving Reagent `reg-view`, UIx `defui`, and Helix `defnc`
proves the Spec 005 machine, Spec 010 schemas, and Spec 014 managed-HTTP
surfaces are substrate-agnostic. It is intentionally **not** hoisted into a
shared model namespace — each substrate login is a self-contained
`:browser` build, and `npm run test:bundle-isolation` proves a Helix
`main.js` carries no Reagent/UIx code (and vice versa). A shared model
required into all three builds would defeat that isolation and the parity
claim it underwrites. The rationale and its four bounding conditions are
catalogued in
[`examples/TESTING.md` §Exception 2](../../TESTING.md#exception-2--the-cross-substrate-reagentuixhelix-id-share).

The folder name carries the `_helix` substrate suffix so the
top-level namespace doesn't collide with Reagent or UIx siblings on
the classpath.

## Files

```
login_helix/
  core.cljs    — schema + events + subs + machine + defnc views + mount.
  index.html   — minimal host page.
```

## How to run

```bash
# From implementation/:
npm run dev:example -- examples/login-helix
```

One command: it stages this folder's hand-written
[`index.html`](index.html) + the shared `_shared/` assets next to the
compiled `main.js`, starts `shadow-cljs watch` (edits recompile live),
serves `out/examples/login-helix/` on a free local port, and prints the
URL to open. Add `--no-watch` for a one-shot compile-and-serve.

(`npm run test:adapter-smokes` does not build this example — it compiles and
serves only the three adapter testbeds; see
[`examples/helix/README.md`](../README.md).) Examples are
test-free per [`examples/README.md`](../../README.md).

<details><summary>Advanced: raw <code>shadow-cljs watch</code></summary>

`npm run dev:example` wraps the raw watch + manual staging recipe. To
drive shadow-cljs directly: `shadow-cljs watch examples/login-helix`
emits `main.js` into `out/examples/login-helix/`; you then copy this
folder's [`index.html`](index.html) (and the shared assets under
[`../../_shared/`](../../_shared/)) alongside it and serve the output dir
yourself.

</details>

## Cross-references

- [`examples/reagent/login/`](../../reagent/login/) — the canonical Reagent reference.
- [`examples/uix/login_uix/`](../../uix/login_uix/) — the UIx twin.
- [`spec/005-StateMachines.md`](../../../spec/005-StateMachines.md), [`spec/010-Schemas.md`](../../../spec/010-Schemas.md), [`spec/014-HTTPRequests.md`](../../../spec/014-HTTPRequests.md) — the substrate-agnostic surfaces.
- [`spec/006-ReactiveSubstrate.md`](../../../spec/006-ReactiveSubstrate.md) — the substrate contract the Helix adapter satisfies.
- [`implementation/adapters/helix/`](../../../implementation/adapters/helix/) — the adapter implementation.
