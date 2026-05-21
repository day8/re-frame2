# Helix — examples

The Helix adapter (see [Spec 006 §CLJS reference: Helix as alternative substrate](../../spec/006-ReactiveSubstrate.md#cljs-reference-helix-as-alternative-substrate-rf2-2qit)). Helix is the third canonical browser substrate alongside Reagent and UIx; the eight UIx decisions transferred unchanged because Helix and UIx share the React + hooks substrate model. The adapter consumes the same `re-frame.adapter.context` React Context object the Reagent and UIx adapters expose (Decision 2), so a single app can in principle mix-and-match — though the canonical pattern is to choose one substrate per app.

This directory holds the **smoke-test subset** for the Helix adapter, not a 1:1 mirror of the Reagent set. Per [Conventions §Adapter test matrix policy](../../spec/Conventions.md#adapter-test-matrix-policy): non-canonical adapters ship a representative subset. For Helix the subset is **counter + login + process-monitor** — the counter and login pair share their dataflow with the Reagent siblings (substrate-agnostic events, subs, schemas, machine, managed-HTTP stub); the process-monitor is a design-led example proving Helix can drive a polished multi-pane layout. The Reagent realworld scaffold is heavy with Reagent-flavoured idioms and is deferred until a Helix user wants it.

## Layout

```
helix/
  counter_helix/          <-- the Reagent counter dataflow rendered through Helix
  login_helix/            <-- the Reagent login example through Helix
  process_monitor_helix/  <-- design-led example proving multi-pane layout on Helix
```

Each example sits in its own folder with the CLJS source (`core.cljs`) and a hand-written `index.html`. The `examples/` tree is **test-free** (locked policy, rf2-8cevm): no example ships a Playwright spec — see [Testing](#testing) below for where the real regression coverage lives. The on-disk folder names carry the `_helix` suffix because the CLJS namespaces (`counter-helix.core`, `login-helix.core`) are deliberately distinct from their Reagent siblings (`counter.core`, `login.core`) and UIx siblings (`counter-uix.core`, `login-uix.core`) — every substrate tree ends up on the same shadow-cljs classpath, so the namespaces have to be unique. The folder name follows the namespace convention (`-` becomes `_` on disk).

The dataflow — events, subs, schemas, machine, managed-HTTP stub — is **identical** to the Reagent and UIx siblings under [`../reagent/`](../reagent/) and [`../uix/`](../uix/); only the view layer differs. Helix components are written as `defnc` and consume subs via the `use-subscribe` hook (Decision 1).

Per Decision 4 the `reg-view` macro stays Reagent-only; Helix users write `defnc` directly and pair it with `(rf/dispatcher)` for click handlers (Decision 3 — components call dispatch / use-subscribe explicitly, no auto-injection).

## What each example demonstrates

- **`helix/counter_helix/`** ([build id `examples/counter-helix`](../../implementation/shadow-cljs.edn))
  Same `:counter/initialise` / `:counter/inc` / `:counter/dec` events as the Reagent counter; the view renders +/- buttons and a count between them. The count seeds to `5` (via `:counter/initialise`) and moves as the buttons dispatch.

- **`helix/login_helix/`** ([build id `examples/login-helix`](../../implementation/shadow-cljs.edn))
  Same login state machine (`:idle -> :submitting -> :authed`/`:error-shown`/`:locked-out`), same Malli schemas, same `:rf.http/managed.login-demo` stub fx as the Reagent and UIx login examples. The view layer is a Helix `defnc` form using `helix.hooks/use-state` for local input state. Entering credentials and submitting drives the machine to `:authed` and the welcome banner appears.

- **`helix/process_monitor_helix/`** ([build id `examples/process-monitor-helix`](../../implementation/shadow-cljs.edn))
  Design-led example proving Helix can drive a substantive multi-pane layout. Shares the `_shared/css/style.css` "Editorial Warm" identity with the Reagent notebook and UIx dashboard counterparts. No state machines, no HTTP — design-led examples exist to prove polished visuals + interaction, not to replay platform features other examples already cover.

## Testing

The `examples/` tree carries no tests (locked policy, rf2-8cevm). Browser smoke coverage for the Helix substrate lives at the **adapter level**: a single mount + dispatch + assert smoke at [`implementation/adapters/helix/testbed/spec.cjs`](../../implementation/adapters/helix/testbed/) (one each for Reagent, UIx, and Helix). Real regressions are caught by the substrate contract tests (`npm run test:cljs`), the Causa feature-matrix gate (`npm run test:causa-feature-gate`), bundle-isolation, the perf-bundle gate, and mcp-conformance — not by per-example specs.

From `implementation/`, the adapter smokes run via:

```bash
npm run test:examples
```

That compiles the three adapter testbeds (`adapters/reagent-testbed`, `adapters/uix-testbed`, `adapters/helix-testbed`), stages each `index.html`, serves them, and drives the three `spec.cjs` smokes; the example builds in this directory are not staged because nothing tests them. Bundle isolation is verified separately (each per-substrate shadow-cljs build lets CI confirm a Reagent bundle's `main.js` carries no Helix code and vice versa, and likewise for UIx ↔ Helix).

To iterate on one Helix example interactively, from `implementation/`:

```bash
shadow-cljs watch examples/counter-helix
```

The build emits `main.js` into `out/examples/counter-helix/`; copy the example's hand-written [`counter_helix/index.html`](counter_helix/index.html) (and the shared assets it references under [`../_shared/`](../_shared/)) alongside it to load the watched build in a browser.

## Cross-references

- [`spec/006-ReactiveSubstrate.md` §CLJS reference: Helix as alternative substrate](../../spec/006-ReactiveSubstrate.md#cljs-reference-helix-as-alternative-substrate-rf2-2qit) — the substrate contract that the Helix adapter implements; the eight transferred decisions (frame Context, hooks-first, `use-subscribe`, no auto-injection, source-coord injection at the substrate boundary, `flush-views!` for tests, the smoke-test subset, and target version Helix 0.2.x).
- [`spec/Conventions.md` §Adapter test matrix policy](../../spec/Conventions.md#adapter-test-matrix-policy) — adapter test matrix policy: Reagent canonical, UIx and Helix smoke-tested.
- [`examples/reagent/counter/`](../reagent/counter/) and [`examples/reagent/login/`](../reagent/login/) — the canonical Reagent counterparts (same dataflow; different view layer; namespace prefix without the `_helix` suffix).
- [`examples/uix/counter_uix/`](../uix/counter_uix/) and [`examples/uix/login_uix/`](../uix/login_uix/) — the UIx siblings; the dataflow is identical, the view layer uses `defui` + `use-subscribe` rather than `defnc` + `use-subscribe`.
- [`examples/reagent/notebook/`](../reagent/notebook/) and [`examples/uix/dashboard_uix/`](../uix/dashboard_uix/) — the Reagent and UIx design-led siblings of `process_monitor_helix`; same "Editorial Warm" identity, different substrate.
