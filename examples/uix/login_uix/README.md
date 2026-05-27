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
  carry Spec 005 `:tags` (`:auth/busy`, `:auth/authenticated`); the
  view reads them via the `:rf/machine-has-tag?` framework sub. Same
  tag taxonomy as the Reagent reference; only the hook idiom differs.
- **No auto-injection** — UIx components call `rf/dispatcher` and
  `use-subscribe` directly. The component layer is explicit; the
  artefact layer beneath is identical.

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
shadow-cljs watch examples/login-uix
```

Run `npm run test:examples` once first so
`out/examples/login-uix/index.html` is staged. Examples are test-free
per [`examples/README.md`](../../README.md).

## Cross-references

- [`examples/reagent/login/`](../../reagent/login/) — the canonical Reagent reference.
- [`examples/helix/login_helix/`](../../helix/login_helix/) — the Helix twin.
- [`spec/005-StateMachines.md`](../../../spec/005-StateMachines.md), [`spec/010-Schemas.md`](../../../spec/010-Schemas.md), [`spec/014-HTTPRequests.md`](../../../spec/014-HTTPRequests.md) — the substrate-agnostic surfaces.
- [`spec/006-ReactiveSubstrate.md`](../../../spec/006-ReactiveSubstrate.md) — the substrate contract the UIx adapter satisfies.
- [`implementation/adapters/uix/`](../../../implementation/adapters/uix/) — the adapter implementation.
