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
  carry Spec 005 `:tags` (`:auth/busy`, `:auth/authenticated`); the
  view reads them via the `:rf/machine-has-tag?` framework sub. Same
  tag taxonomy as the Reagent reference; only the hook idiom differs.
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
shadow-cljs watch examples/login-helix
```

Run `npm run test:examples` once first so
`out/examples/login-helix/index.html` is staged. Examples are
test-free per [`examples/README.md`](../../README.md).

## Cross-references

- [`examples/reagent/login/`](../../reagent/login/) — the canonical Reagent reference.
- [`examples/uix/login_uix/`](../../uix/login_uix/) — the UIx twin.
- [`spec/005-StateMachines.md`](../../../spec/005-StateMachines.md), [`spec/010-Schemas.md`](../../../spec/010-Schemas.md), [`spec/014-HTTPRequests.md`](../../../spec/014-HTTPRequests.md) — the substrate-agnostic surfaces.
- [`spec/006-ReactiveSubstrate.md`](../../../spec/006-ReactiveSubstrate.md) — the substrate contract the Helix adapter satisfies.
- [`implementation/adapters/helix/`](../../../implementation/adapters/helix/) — the adapter implementation.
