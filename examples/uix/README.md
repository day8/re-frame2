# UIx — examples

The UIx adapter (see [Spec 006 §Adapter shipping convention](../../spec/006-ReactiveSubstrate.md)). UIx is the second adapter to ship; it consumes the same `re-frame.adapter.context` React Context that the Reagent adapter exposes (Decision 2), so a single app can in principle mix-and-match — though the canonical pattern is to choose one substrate per app.

This directory holds the UIx adapter examples, not a 1:1 mirror of the Reagent set. Per Decision 7 of [Spec 006 §Adapter shipping convention](../../spec/006-ReactiveSubstrate.md) and [Conventions §Adapter test matrix policy](../../spec/Conventions.md#adapter-test-matrix-policy): non-canonical adapters ship a representative **smoke-test subset**. For UIx that subset is **counter + login** — the curated pair that shares its dataflow with the Reagent siblings (substrate-agnostic events, subs, schemas, machine, managed-HTTP stub), chosen because exercising it confirms the UIx adapter implements the substrate contract. Inside this tree that pair carries **compile coverage only** (`test:examples-compile`, see [Testing](#testing)); the *runtime* smoke that proves the substrate contract is the adapter testbed at [`implementation/adapters/uix/testbed/spec.cjs`](../../implementation/adapters/uix/testbed/), not a per-example browser gate. The Reagent realworld scaffold is heavy with Reagent-flavoured idioms and is deferred until a UIx user wants it.

Alongside the smoke pair this directory also ships **`dashboard_uix`** — a design-led example proving UIx can drive a polished multi-pane layout. It is a documented example and a declared shadow-cljs build (so it carries compile coverage), but it is **not** part of the Decision-7 smoke-test subset: the spec's UIx subset is counter + login only, and `dashboard_uix` makes no Decision-7 contract claim.

## Layout

```
uix/
  counter_uix/    <-- the Reagent counter dataflow rendered through UIx
  login_uix/      <-- the Reagent login example through UIx
  dashboard_uix/  <-- design-led example proving multi-pane layout on UIx
```

Each example sits in its own folder with the CLJS source (`core.cljs`) and a hand-written `index.html`. The `examples/` tree is **test-free**: no example ships a Playwright spec — see [Testing](#testing) below for where the real regression coverage lives. The on-disk folder names carry the `_uix` suffix because the CLJS namespaces (`counter-uix.core`, `login-uix.core`) are deliberately distinct from their Reagent siblings (`counter.core`, `login.core`) — both substrate trees end up on the same shadow-cljs classpath, so the namespaces have to be unique. The folder name follows the namespace convention (`-` becomes `_` on disk).

The dataflow — events, subs, schemas, machine, managed-HTTP stub — is **identical** to the Reagent siblings under [`../reagent/`](../reagent/); only the view layer differs. UIx components are written as `defui` and consume subs via the `use-subscribe` hook (Decision 1, UIx-idiomatic).

### Shared registry ids — deliberate, build-isolated

The "identical" above is literal: `counter_uix` and `login_uix` register the **same app-global registry ids** as their Reagent (and Helix) siblings — the `:counter/*` event + sub ids, the `:auth.login/flow` machine event, the `:auth.login.demo/managed-stub` fx, the `:auth.login/state` / `:auth.login/error` subs, and the `[:rf/runtime :machines :snapshots :auth.login/flow]` app-schema path. This is **byte-for-byte id reuse on purpose** — the id-identity *is* the cross-substrate parity demonstration, exactly as the stock/slim counter `:counter/*` carve-out documented in [`examples/TESTING.md` §Event-id and subscription-id namespacing](../TESTING.md#event-id-and-subscription-id-namespacing). It is a bounded exception to the example-id-prefix convention, not an oversight, and the same four conditions apply:

1. **Allowed only because each example is a separate standalone shadow-cljs build** (`examples/counter-uix`, `examples/login-uix`) that MUST NOT be co-required with its Reagent/Helix twin into one runtime. They never share a JS runtime, so the identical ids never collide.
2. **If any of these examples is ever folded into a shared wrapper / showcase / `test:browser` bundle alongside a sibling substrate, the ids MUST be prefixed first.** Because the twins start byte-identical, a co-load collision would overwrite handlers/subs/schema *silently* until one side diverges.
3. **The carve-out covers shared event/sub/fx/machine/schema ids only — never views.** UIx views are `defui` (their own namespace); there is no `reg-view` registration to collide.
4. **Bundle isolation is the regression surface** that keeps the build split honest (each per-substrate build's `main.js` is grepped in isolation).

Renaming the UIx ids to a `:counter-uix/*` / `:auth.login-uix/*` stem would conform to the prefix rule but *weaken* the parity claim these examples exist to make, so it is not done — same trade-off the slim-counter carve-out resolves the same way.

## What each example demonstrates

- **`uix/counter_uix/`** ([build id `examples/counter-uix`](../../implementation/shadow-cljs.edn))
  Same `:counter/initialise` / `:counter/inc` / `:counter/dec` events as the Reagent counter; the view renders +/- buttons and a count between them. The count seeds to `5` (via `:counter/initialise`) and moves as the buttons dispatch.

- **`uix/login_uix/`** ([build id `examples/login-uix`](../../implementation/shadow-cljs.edn))
  Same login state machine (`:idle -> :submitting -> :authed`/`:error-shown`), same Malli schemas, same `:auth.login.demo/managed-stub` stub fx as the Reagent login example. The view layer is a UIx `defui` form. Entering credentials and submitting drives the machine to `:authed` and the welcome banner appears on success.

- **`uix/dashboard_uix/`** ([build id `examples/dashboard-uix`](../../implementation/shadow-cljs.edn))
  Design-led example proving UIx can drive a substantive multi-pane layout. Shares the `_shared/css/style.css` "Editorial Warm" visual identity with the Reagent notebook and Helix process-monitor counterparts. No state machines, no HTTP — design-led examples exist to prove polished visuals + interaction, not to replay platform features other examples already cover.

## Testing

The `examples/` tree carries no tests. Browser smoke coverage for the UIx substrate lives at the **adapter level**: a single mount + dispatch + assert smoke at [`implementation/adapters/uix/testbed/spec.cjs`](../../implementation/adapters/uix/testbed/) (one each for Reagent, UIx, and Helix). Real regressions are caught by the substrate contract tests (`npm run test:cljs`), the Xray feature-matrix gate (`npm run test:xray-feature-gate`), bundle-isolation, the perf-bundle gate, and mcp-conformance — not by per-example specs.

From `implementation/`, the adapter smokes run via:

```bash
npm run test:examples
```

That compiles the three adapter testbeds (`adapters/reagent-testbed`, `adapters/uix-testbed`, `adapters/helix-testbed`), stages each `index.html`, serves them, and drives the three `spec.cjs` smokes; the example builds in this directory carry no `spec.cjs` (the `examples/` tree is test-free). They do, however, get **compile coverage**: `npm run test:examples-compile` (from `implementation/`) `shadow-cljs compile`s every declared standalone `:examples/*` build — including `examples/login-uix` and `examples/dashboard-uix` — and fails on any compile error or warning, so a namespace / `:init-fn` / `:require` / UIx-form regression in these builds can no longer ship green. See [`examples/TESTING.md`](../TESTING.md#compile-coverage-gate-testexamples-compile). Bundle isolation is verified separately (each per-substrate shadow-cljs build lets CI confirm a Reagent bundle's `main.js` carries no UIx code and vice versa, and likewise for UIx ↔ Helix).

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
