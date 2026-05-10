# UIx — examples

The UIx adapter ([rf2-3yij](#); see [Spec 006 §Adapter shipping convention](../../spec/006-ReactiveSubstrate.md)). UIx is the second adapter to ship; it consumes the same `re-frame.adapter.context` React Context that the Reagent adapter exposes (Decision 2), so a single app can in principle mix-and-match — though the canonical pattern is to choose one substrate per app.

This directory holds the **smoke-test subset** for the UIx adapter, not a 1:1 mirror of the Reagent set. Per Decision 7 of [Spec 006 §Adapter shipping convention](../../spec/006-ReactiveSubstrate.md) and [Conventions §Adapter test matrix policy](../../spec/Conventions.md#adapter-test-matrix-policy): non-canonical adapters ship a representative subset; the standard trio is **counter + login + realworld**, and for UIx that is reduced to **counter + login** — realworld is heavy with Reagent-flavoured idioms and is deferred until a UIx user wants it.

## Layout

```
uix/
  counter_uix/   <-- the Reagent counter dataflow rendered through UIx
  login_uix/     <-- the Reagent login example through UIx
```

Each example sits in its own folder with the CLJS source (`core.cljs`), a hand-written `index.html`, and a Playwright smoke spec (`<name>.spec.cjs`). The on-disk folder names carry the `_uix` suffix because the CLJS namespaces (`counter-uix.core`, `login-uix.core`) are deliberately distinct from their Reagent siblings (`counter.core`, `login.core`) — both substrate trees end up on the same shadow-cljs classpath, so the namespaces have to be unique. The folder name follows the namespace convention (`-` becomes `_` on disk).

The dataflow — events, subs, schemas, machine, managed-HTTP stub — is **identical** to the Reagent siblings under [`../reagent/`](../reagent/); only the view layer differs. UIx components are written as `defui` and consume subs via the `use-subscribe` hook (Decision 1, UIx-idiomatic).

## What each example demonstrates

- **`uix/counter_uix/`** ([build id `examples/counter-uix`](../../implementation/shadow-cljs.edn))
  Same `:counter/initialise` / `:counter/increment` / `:counter/decrement` events as the Reagent counter; the view renders +/- buttons and a count between them. Smoke-spec asserts an initial render of `5` (seeded by `:counter/initialise`), then drives clicks and asserts the count moves.

- **`uix/login_uix/`** ([build id `examples/login-uix`](../../implementation/shadow-cljs.edn))
  Same login state machine (`:idle -> :submitting -> :authed`/`:error-shown`), same Malli schemas, same `:rf.http/managed.login-demo` stub fx as the Reagent login example. The view layer is a UIx `defui` form. Smoke-spec drives credentials → submit → asserts the welcome banner appears on success.

## Running

The UIx examples are wired into the same orchestrator as the Reagent set. From `implementation/`:

```bash
npm run test:examples
```

That compiles `examples/counter-uix` and `examples/login-uix` alongside every Reagent example, stages each `index.html`, serves the lot, and drives the per-example smoke specs. The bundle-isolation grep at [`implementation/scripts/check-bundle-isolation.cjs`](../../implementation/scripts/check-bundle-isolation.cjs) runs against the Reagent counter; the per-substrate-per-example shadow-cljs builds let CI verify a Reagent example's `main.js` carries no UIx code and vice versa.

To iterate on one UIx example interactively, from `implementation/`:

```bash
shadow-cljs watch examples/counter-uix
```

(Run `npm run test:examples` once first so `out/examples/counter-uix/index.html` is staged.)

## Cross-references

- [`spec/006-ReactiveSubstrate.md`](../../spec/006-ReactiveSubstrate.md) — the substrate contract that adapters implement; the seven decisions (frame Context, hooks-first, `use-subscribe`, no auto-injection, source-coord injection at the substrate boundary, `flush-views!` for tests, and the smoke-test subset).
- [`spec/Conventions.md`](../../spec/Conventions.md#adapter-test-matrix-policy) — adapter test matrix policy: Reagent canonical, UIx and Helix smoke-tested.
- [`examples/reagent/counter/`](../reagent/counter/) and [`examples/reagent/login/`](../reagent/login/) — the canonical Reagent counterparts (same dataflow; different view layer; namespace prefix without the `_uix` suffix).
