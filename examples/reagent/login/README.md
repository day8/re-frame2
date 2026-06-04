# login — full feature scaffold

The canonical end-to-end single-feature example: a login flow with a
state machine, schemas, managed HTTP, registered views, and headless
tests. Demonstrates [Construction Prompt
CP-6](../../../spec/Construction-Prompts.md) (full feature scaffold)
in one file, kept compact for AI-readability.

## What this demonstrates

- **CP-6 feature scaffold** — the `:auth.login/*` registry slice as
  one coherent feature: schema + events + subs + views + machine +
  tests, kept in one file for compactness.
- **CP-5 state machine** — the login flow as a transition table read
  via `[:rf/runtime :machines :snapshots :auth.login/flow]`. States: `:idle →
  :submitting → {:error-shown | :authed | :locked-out}`.
- **CP-8 schema attachment** — Malli schema for the machine snapshot,
  attached via `rf/reg-app-schema [:rf/runtime :machines :snapshots :auth.login/flow]`.
- **CP-1 + CP-2** — pure `reg-event-db` handlers and pure `reg-sub`
  derivations off the machine snapshot.
- **CP-3 registered fx** — `:rf.http/managed` (Spec 014) plus
  `:fx-overrides` redirecting to the framework-shipped canned-success
  stub, so the example runs without a backend.
- **CP-4 registered view** — Var reference, Form-1 (canonical).
- **State tags** (Spec 005) — `:auth/busy` on `:submitting`,
  `:auth/authenticated` on `:authed`. Views query them via
  `(rf/machine-has-tag? :auth.login/flow ...)` instead of
  boolean-discriminator subs.
- **Open-map idiom** — every shape on the wire is an open map.
- **Headless test** — browserless smoke test demonstrating the full
  test seam.

## Why this shape

This is the canonical cross-substrate base. The same login feature is
mirrored 1:1 as [`examples/uix/login_uix/`](../../uix/login_uix/) and
[`examples/helix/login_helix/`](../../helix/login_helix/) — same
machine, same schemas, same HTTP stub; only the view layer differs.
That makes the three substrate variants a clean apples-to-apples
comparison. (Per Spec 006 §Adapter shipping convention Decision 7.)

The substrate here is **stock Reagent** (not reagent-slim) — this is
the reference example; `counter` / `counter_slim_and_fast` is the
dedicated stock-vs-slim contrast pair.

In a real codebase this single file would split per CP-6 conventions:
`login/schema.cljc | events.cljs | subs.cljs | views.cljs |
machines.cljs | events_test.cljs`. Kept as one file here for brevity.

## Files

```
login/
  core.cljs    — schema + events + subs + views + machine + tests + mount.
  index.html   — minimal host page.
```

## How to run

```bash
# From implementation/:
shadow-cljs watch examples/login
```

The watch build emits `main.js` into `out/examples/login/`; copy this
folder's hand-written [`index.html`](index.html) (and the shared
assets it references under [`../../_shared/`](../../_shared/))
alongside it, then serve `out/examples/login/` over HTTP.
(`npm run test:examples` does not build this example — it compiles and
serves only the three adapter testbeds; see
[`examples/reagent/README.md`](../README.md).) Examples are test-free per
[`examples/README.md`](../../README.md).

## Cross-references

- [Construction Prompts CP-1 / CP-2 / CP-3 / CP-4 / CP-5 / CP-6 / CP-8](../../../spec/Construction-Prompts.md) — the prompts this example instantiates.
- [`spec/005-StateMachines.md`](../../../spec/005-StateMachines.md) — the machine substrate.
- [`spec/010-Schemas.md`](../../../spec/010-Schemas.md) — schema attachment.
- [`spec/014-HTTPRequests.md`](../../../spec/014-HTTPRequests.md) — `:rf.http/managed`.
- [`examples/uix/login_uix/`](../../uix/login_uix/) + [`examples/helix/login_helix/`](../../helix/login_helix/) — the substrate variants.
