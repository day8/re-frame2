# Helix — examples

The Helix adapter (see [Spec 006 §CLJS reference: Helix as alternative substrate](../../spec/006-ReactiveSubstrate.md#cljs-reference-helix-as-alternative-substrate-rf2-2qit)). Helix is the third canonical browser substrate alongside Reagent and UIx; the eight UIx decisions transferred unchanged because Helix and UIx share the React + hooks substrate model. The adapter consumes the same `re-frame.adapter.context` React Context object the Reagent and UIx adapters expose (Decision 2), so a single app can in principle mix-and-match — though the canonical pattern is to choose one substrate per app.

This directory holds the **smoke-test subset** for the Helix adapter, not a 1:1 mirror of the Reagent set. Per [Conventions §Adapter test matrix policy](../../spec/Conventions.md#adapter-test-matrix-policy): non-canonical adapters ship a representative subset; the standard trio is **counter + login + realworld**, and for Helix that is reduced to **counter + login** — realworld is heavy with Reagent-flavoured idioms and is deferred until a Helix user wants it.

## Layout

```
helix/
  counter_helix/   <-- the Reagent counter dataflow rendered through Helix
  login_helix/     <-- the Reagent login example through Helix
```

Each example sits in its own folder with the CLJS source (`core.cljs`), a hand-written `index.html`, and a Playwright smoke spec (`<name>.spec.cjs`). The on-disk folder names carry the `_helix` suffix because the CLJS namespaces (`counter-helix.core`, `login-helix.core`) are deliberately distinct from their Reagent siblings (`counter.core`, `login.core`) and UIx siblings (`counter-uix.core`, `login-uix.core`) — every substrate tree ends up on the same shadow-cljs classpath, so the namespaces have to be unique. The folder name follows the namespace convention (`-` becomes `_` on disk).

The dataflow — events, subs, schemas, machine, managed-HTTP stub — is **identical** to the Reagent and UIx siblings under [`../reagent/`](../reagent/) and [`../uix/`](../uix/); only the view layer differs. Helix components are written as `defnc` and consume subs via the `use-subscribe` hook (Decision 1).

Per Decision 4 the `reg-view` macro stays Reagent-only; Helix users write `defnc` directly and pair it with `(rf/dispatcher)` for click handlers (Decision 3 — components call dispatch / use-subscribe explicitly, no auto-injection).

## What each example demonstrates

- **`helix/counter_helix/`** ([build id `examples/counter-helix`](../../implementation/shadow-cljs.edn))
  Same `:counter/initialise` / `:counter/inc` / `:counter/dec` events as the Reagent counter; the view renders +/- buttons and a count between them. Smoke-spec asserts an initial render of `5` (seeded by `:counter/initialise`), then drives clicks and asserts the count moves.

- **`helix/login_helix/`** ([build id `examples/login-helix`](../../implementation/shadow-cljs.edn))
  Same login state machine (`:idle -> :submitting -> :authed`/`:error-shown`/`:locked-out`), same Malli schemas, same `:rf.http/managed.login-demo` stub fx as the Reagent and UIx login examples. The view layer is a Helix `defnc` form using `helix.hooks/use-state` for local input state. Smoke-spec drives credentials → submit → asserts the welcome banner appears on success.

## Running

The Helix examples are wired into the same orchestrator as the Reagent and UIx sets. From `implementation/`:

```bash
npm run test:examples
```

That compiles `examples/counter-helix` and `examples/login-helix` alongside every Reagent and UIx example, stages each `index.html`, serves the lot, and drives the per-example smoke specs. The bundle-isolation grep at [`implementation/scripts/check-bundle-isolation.cjs`](../../implementation/scripts/check-bundle-isolation.cjs) runs against the Reagent counter; the per-substrate-per-example shadow-cljs builds let CI verify a Reagent example's `main.js` carries no Helix code and vice versa (and likewise for UIx ↔ Helix).

To iterate on one Helix example interactively, from `implementation/`:

```bash
shadow-cljs watch examples/counter-helix
```

(Run `npm run test:examples` once first so `out/examples/counter-helix/index.html` is staged.)

## Cross-references

- [`spec/006-ReactiveSubstrate.md` §CLJS reference: Helix as alternative substrate](../../spec/006-ReactiveSubstrate.md#cljs-reference-helix-as-alternative-substrate-rf2-2qit) — the substrate contract that the Helix adapter implements; the eight transferred decisions (frame Context, hooks-first, `use-subscribe`, no auto-injection, source-coord injection at the substrate boundary, `flush-views!` for tests, the smoke-test subset, and target version Helix 0.2.x).
- [`spec/Conventions.md` §Adapter test matrix policy](../../spec/Conventions.md#adapter-test-matrix-policy) — adapter test matrix policy: Reagent canonical, UIx and Helix smoke-tested.
- [`examples/reagent/counter/`](../reagent/counter/) and [`examples/reagent/login/`](../reagent/login/) — the canonical Reagent counterparts (same dataflow; different view layer; namespace prefix without the `_helix` suffix).
- [`examples/uix/counter_uix/`](../uix/counter_uix/) and [`examples/uix/login_uix/`](../uix/login_uix/) — the UIx siblings; the dataflow is identical, the view layer uses `defui` + `use-subscribe` rather than `defnc` + `use-subscribe`.
