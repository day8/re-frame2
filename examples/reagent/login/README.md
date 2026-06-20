# login — full feature scaffold

The canonical end-to-end single-feature example: a login flow with a
state machine, schemas, managed HTTP, and registered views.
Demonstrates [Construction Prompt
CP-6](../../../spec/Construction-Prompts.md) (full feature scaffold)
in one file, kept compact for AI-readability. The example tree is
test-free — its flow coverage lives in the framework test
tree (see [How to run](#how-to-run)).

## What this demonstrates

- **CP-6 feature scaffold** — the `:auth.login/*` registry slice as
  one coherent feature: schema + events + subs + views + machine,
  kept in one file for compactness.
- **CP-5 state machine** — the login flow as a transition table; its
  snapshot lives in runtime-db at `[:rf.runtime/machines :snapshots
  :auth.login/flow]`, read via `sub-machine`. States: `:idle →
  :submitting → {:error-shown | :authed | :locked-out}`.
- **CP-8 schema attachment** — `AuthLoginData`, a Malli schema for the
  machine snapshot's `:data` SLOT (the `:attempts` + `:error` map — not
  the whole `{:state … :data …}` snapshot), attached via the machine's
  top-level `:data-schema` slot on `make-machine-handler`. It validates
  at the `:where :machine-data` boundary. (Machine snapshots are
  runtime-db, not app-db, so `reg-app-schema` — which validates the
  app-db partition only — is not the surface for them.)
- **CP-1 + CP-2** — pure `reg-event` handlers and pure `reg-sub`
  derivations off the machine snapshot.
- **CP-3 registered fx** — `:rf.http/managed` (Spec 014) plus
  `:fx-overrides` redirecting to the framework-shipped canned-success
  stub, so the example runs without a backend.
- **CP-4 registered view** — Var reference, Form-1 (canonical).
- **State tags** (Spec 005) — `:auth/busy` on `:submitting`,
  `:auth/authenticated` on `:authed`, `:auth/locked` on `:locked-out`.
  Views query them via `(rf/machine-has-tag? :auth.login/flow ...)`
  instead of boolean-discriminator subs. The terminal `:locked-out`
  state (reached after the fourth failed submit) is surfaced as a
  non-interactive locked-account panel rather than a dead-but-enabled
  form — same tag + locked-panel pattern as the state-machines
  walkthrough.
- **Open-map idiom** — every shape on the wire is an open map.

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

**Production layout advice (not this repo's example layout).** In a
real codebase this single file would split per CP-6 conventions —
`login/schema.cljc | events.cljs | subs.cljs | views.cljs |
machines.cljs | events_test.cljs` — and that `events_test.cljs` is
where a real app's login tests would live. The example tree here is
deliberately different: it is **test-free**, so this folder
ships no inline test fn and no sibling `test/` tree; the flow's coverage
lives in the framework test tree (see [How to run](#how-to-run)). Kept
as one file here for brevity.

## Files

```
login/
  core.cljs            — schema + events + subs + views + machine + mount.
  index.html           — minimal host page (the live app).
  stories.cljs         — Story showcase: one variant per reachable
                         :auth.login/flow state (auxiliary; see below).
  stories_host.cljs    — Story-showcase entry point (live-app ↔ shell hash router).
  stories.index.html   — host page for the Story-showcase build.
```

The three `stories*` files are an **intentionally auxiliary Story
showcase** layered over this example (build `:examples/login-with-stories`)
— not a second example and not tool-owned. They source
`login.core`'s real machine/schemas/views and enumerate every reachable
login state as a Story variant, with the Xray preload wired so the
auth-submit cascade is inspectable. They live here (rather than under
`tools/story/testbeds/`) because they showcase *this* worked example
end-to-end; the tool-owned Story testbeds at
[`tools/story/testbeds/`](../../../tools/story/testbeds/) stay catalogued
with the tool. See [How to run](#how-to-run) for the showcase command.

## How to run

```bash
# From implementation/:
shadow-cljs watch examples/login
```

The watch build emits `main.js` into `out/examples/login/`; copy this
folder's hand-written [`index.html`](index.html) (and the shared
assets it references under [`../../_shared/`](../../_shared/))
alongside it, then serve `out/examples/login/` over HTTP.
(`npm run test:adapter-smokes` does not build this example — it compiles and
serves only the three adapter testbeds; see
[`examples/reagent/README.md`](../README.md).)

To run the Story showcase instead, watch its build and open the Story
shell:

```bash
# From implementation/:
shadow-cljs watch :examples/login-with-stories   # then open http://localhost:8041/#/stories
```

`#/` renders the live login app; `#/stories` mounts the Story shell with
every reachable state as a variant. Press <kbd>Ctrl+Shift+C</kbd> on
either surface to open Xray over the auth-submit cascade.

## Coverage (examples are test-free)

This folder ships no tests. The login flow this example wires is a
near-twin of the `:auth.login/flow` machine the sibling
[`state_machine_walkthrough`](../state_machine_walkthrough/) example
exercises headlessly: the `state-machine-walkthrough-runs-headless`
deftest in
[`implementation/core/test/re_frame/examples_test.clj`](../../../implementation/core/test/re_frame/examples_test.clj)
drives the happy-path / retry-then-lockout / machine-transition
scenarios. Login-specific machine-data schema coverage lives in
[`implementation/adapters/reagent/test/re_frame/login_cljs_test.cljs`](../../../implementation/adapters/reagent/test/re_frame/login_cljs_test.cljs).
Broader contract coverage runs in the substrate contract suite
(`npm run test:cljs`) and the framework gates; the full split is in
[`examples/README.md`](../../README.md).

## Cross-references

- [Construction Prompts CP-1 / CP-2 / CP-3 / CP-4 / CP-5 / CP-6 / CP-8](../../../spec/Construction-Prompts.md) — the prompts this example instantiates.
- [`spec/005-StateMachines.md`](../../../spec/005-StateMachines.md) — the machine substrate.
- [`spec/010-Schemas.md`](../../../spec/010-Schemas.md) — schema attachment.
- [`spec/014-HTTPRequests.md`](../../../spec/014-HTTPRequests.md) — `:rf.http/managed`.
- [`examples/uix/login_uix/`](../../uix/login_uix/) + [`examples/helix/login_helix/`](../../helix/login_helix/) — the substrate variants.
