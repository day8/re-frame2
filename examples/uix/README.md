# UIx — examples

The UIx adapter (see [Spec 006 §Adapter shipping convention](../../spec/006-ReactiveSubstrate.md)). UIx is the second adapter to ship; it consumes the same `re-frame.adapter.context` React Context that the Reagent adapter exposes (Decision 2), so a single app can in principle mix-and-match — though the canonical pattern is to choose one substrate per app.

This directory holds the **smoke-test subset** for the UIx adapter, not a 1:1 mirror of the Reagent set. Per Decision 7 of [Spec 006 §Adapter shipping convention](../../spec/006-ReactiveSubstrate.md) and [Conventions §Adapter test matrix policy](../../spec/Conventions.md#adapter-test-matrix-policy): non-canonical adapters ship a representative subset. For UIx the subset is **counter + login + dashboard** — the counter and login pair share their dataflow with the Reagent siblings (substrate-agnostic events, subs, schemas, machine, managed-HTTP stub); the dashboard is a design-led example proving UIx can drive a polished multi-pane layout. The Reagent realworld scaffold is heavy with Reagent-flavoured idioms and is deferred until a UIx user wants it.

## Layout

```
uix/
  counter_uix/    <-- the Reagent counter dataflow rendered through UIx
  login_uix/      <-- the Reagent login example through UIx
  dashboard_uix/  <-- design-led example proving multi-pane layout on UIx
```

Each example sits in its own folder with the CLJS source (`core.cljs`) and a hand-written `index.html`. The `examples/` tree is **test-free** (locked policy, rf2-8cevm): no example ships a Playwright spec — see [Testing](#testing) below for where the real regression coverage lives. The on-disk folder names carry the `_uix` suffix because the CLJS namespaces (`counter-uix.core`, `login-uix.core`) are deliberately distinct from their Reagent siblings (`counter.core`, `login.core`) — both substrate trees end up on the same shadow-cljs classpath, so the namespaces have to be unique. The folder name follows the namespace convention (`-` becomes `_` on disk).

The dataflow — events, subs, schemas, machine, managed-HTTP stub — is **identical** to the Reagent siblings under [`../reagent/`](../reagent/); only the view layer differs. UIx components are written as `defui` and consume subs via the `use-subscribe` hook (Decision 1, UIx-idiomatic).

## What each example demonstrates

- **`uix/counter_uix/`** ([build id `examples/counter-uix`](../../implementation/shadow-cljs.edn))
  Same `:counter/initialise` / `:counter/inc` / `:counter/dec` events as the Reagent counter; the view renders +/- buttons and a count between them. The count seeds to `5` (via `:counter/initialise`) and moves as the buttons dispatch.

- **`uix/login_uix/`** ([build id `examples/login-uix`](../../implementation/shadow-cljs.edn))
  Same login state machine (`:idle -> :submitting -> :authed`/`:error-shown`), same Malli schemas, same `:rf.http/managed.login-demo` stub fx as the Reagent login example. The view layer is a UIx `defui` form. Entering credentials and submitting drives the machine to `:authed` and the welcome banner appears on success.

- **`uix/dashboard_uix/`** ([build id `examples/dashboard-uix`](../../implementation/shadow-cljs.edn))
  Design-led example proving UIx can drive a substantive multi-pane layout. Shares the `_shared/css/style.css` "Editorial Warm" visual identity with the Reagent notebook and Helix process-monitor counterparts. No state machines, no HTTP — design-led examples exist to prove polished visuals + interaction, not to replay platform features other examples already cover.

## Testing

The `examples/` tree carries no tests (locked policy, rf2-8cevm). Browser smoke coverage for the UIx substrate lives at the **adapter level**: a single mount + dispatch + assert smoke at [`implementation/adapters/uix/testbed/spec.cjs`](../../implementation/adapters/uix/testbed/) (one each for Reagent, UIx, and Helix). Real regressions are caught by the substrate contract tests (`npm run test:cljs`), the Causa feature-matrix gate (`npm run test:causa-feature-gate`), bundle-isolation, the perf-bundle gate, and mcp-conformance — not by per-example specs.

From `implementation/`, the adapter smokes run via:

```bash
npm run test:examples
```

That compiles the three adapter testbeds (`adapters/reagent-testbed`, `adapters/uix-testbed`, `adapters/helix-testbed`), stages each `index.html`, serves them, and drives the three `spec.cjs` smokes; the example builds in this directory are not staged because nothing tests them. Bundle isolation is verified separately (each per-substrate shadow-cljs build lets CI confirm a Reagent bundle's `main.js` carries no UIx code and vice versa, and likewise for UIx ↔ Helix).

To iterate on one UIx example interactively, from `implementation/`:

```bash
shadow-cljs watch examples/counter-uix
```

The build emits `main.js` into `out/examples/counter-uix/`; copy the example's hand-written [`counter_uix/index.html`](counter_uix/index.html) (and the shared assets it references under [`../_shared/`](../_shared/)) alongside it to load the watched build in a browser.

## Cross-references

- [`spec/006-ReactiveSubstrate.md`](../../spec/006-ReactiveSubstrate.md) — the substrate contract that adapters implement; the seven decisions (frame Context, hooks-first, `use-subscribe`, no auto-injection, source-coord injection at the substrate boundary, `flush-views!` for tests, and the smoke-test subset).
- [`spec/Conventions.md`](../../spec/Conventions.md#adapter-test-matrix-policy) — adapter test matrix policy: Reagent canonical, UIx and Helix smoke-tested.
- [`examples/reagent/counter/`](../reagent/counter/) and [`examples/reagent/login/`](../reagent/login/) — the canonical Reagent counterparts (same dataflow; different view layer; namespace prefix without the `_uix` suffix).
- [`examples/reagent/notebook/`](../reagent/notebook/) — the Reagent design-led sibling of `dashboard_uix`; same "Editorial Warm" identity, different substrate.
