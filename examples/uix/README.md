# UIx — examples

UIx is the second substrate re-frame2 learned to render through (see
[Spec 006 §Adapter shipping convention](../../spec/006-ReactiveSubstrate.md)),
and the examples here exist to make one claim visible: the dataflow
does not care which one you pick. UIx renders through React hooks
rather than Reagent's reactive atoms, yet it plugs into the *same*
`re-frame.adapter.context` React Context the Reagent adapter exposes
(Decision 2). In principle a single app could even mix the two —
though the sane default is to choose one substrate and stay there.

This directory is **not** a 1:1 mirror of the Reagent set, and that's
by design. Per Decision 7 of
[Spec 006 §Adapter shipping convention](../../spec/006-ReactiveSubstrate.md)
and [Conventions §Adapter test matrix policy](../../spec/Conventions.md#adapter-test-matrix-policy),
a non-canonical adapter doesn't have to re-prove the whole catalogue —
it ships a representative **curated example subset** (the spec's term;
Decision 7 is "Curated example set"). For UIx that subset is
**counter + login** — the curated pair whose dataflow is shared
verbatim with the Reagent siblings (substrate-agnostic events, subs,
schemas, machine, managed-HTTP stub). That pair is the load-bearing
one: get the same events and subs flowing through `defui` components
and you've shown the UIx adapter honours the substrate contract.
Inside this tree the pair carries **compile coverage only**
(`test:examples-compile`, see [Testing](#testing)); the *runtime*
smoke that actually exercises the contract is the adapter testbed at
[`implementation/adapters/uix/testbed/spec.cjs`](../../implementation/adapters/uix/testbed/),
not a per-example browser gate. (We keep the word "smoke" for that
runtime adapter gate, never for the curated example pages.) The
Reagent realworld scaffold is thick with Reagent-flavoured idioms, so
it stays deferred until a UIx user actually asks for it.

Riding alongside the curated pair is **`dashboard_uix`** — a
design-led example, there to prove UIx can drive a polished multi-pane
layout rather than just a counter and a login form. It is a documented
example and a declared shadow-cljs build (so it earns its compile
coverage), but it is deliberately **not** part of the Decision-7
curated subset: the spec's UIx subset is counter + login only, and
`dashboard_uix` makes no Decision-7 contract claim.

## Layout

```
uix/
  counter_uix/    <-- the Reagent counter dataflow rendered through UIx
  login_uix/      <-- the Reagent login example through UIx
  dashboard_uix/  <-- design-led example proving multi-pane layout on UIx
```

Each example sits in its own folder with the CLJS source (`core.cljs`) and a hand-written `index.html`. The `examples/` tree is **test-free**: no example ships a Playwright spec — see [Testing](#testing) below for where the real regression coverage lives. Those `_uix` suffixes on the folder names aren't cosmetic: the CLJS namespaces (`counter-uix.core`, `login-uix.core`) have to stay distinct from their Reagent siblings (`counter.core`, `login.core`), because both substrate trees land on the *same* shadow-cljs classpath and two namespaces of the same name would collide. The folder name just tracks the namespace, with `-` becoming `_` on disk as the convention demands.

Now the part worth dwelling on: the dataflow — events, subs, schemas, machine, managed-HTTP stub — is **identical** to the Reagent siblings under [`../reagent/`](../reagent/). Not similar, identical. The only thing that moves is the view layer, where UIx components are written as `defui` and pull subs in through the `use-subscribe` hook (Decision 1, UIx-idiomatic) instead of dereferencing a reactive atom. Put a Reagent example and its UIx twin side by side and the seam is unmistakable — everything above the view is the same code; only the rendering changes.

### Shared registration ids — deliberate, build-isolated

When I said the dataflow is identical, I meant it down to the names. `counter_uix` and `login_uix` register the **same registration ids** as their Reagent (and Helix) siblings — the `:counter/*` event + sub ids, the `:auth.login/flow` machine event, the `:auth.login.demo/managed-stub` fx, the `:auth.login/state` / `:auth.login/error` subs, and the `:auth.login/flow` machine's `[:schemas :data]`. The machine snapshot lives in runtime-db at `[:rf.runtime/machines :snapshots :auth.login/flow]`; its `[:schemas :data]` is a top-level key on the machine spec that validates the machine's **`:data` slot only** (`{:attempts ... :error ...}`) at the `:where :machine-data` boundary — per [Spec 005 §Schema validation](../../spec/005-StateMachines.md) — not the whole `{:state ... :data ...}` snapshot, and not `reg-app-schema` (machine snapshots are runtime-db state, not app-db). This is **byte-for-byte id reuse on purpose** — the id-identity *is* the cross-substrate parity demonstration.

This sounds alarming until you remember where ids actually live. A registration id is scoped to the **image** a frame resolves against, not to one process-global registry — so the *same* `:counter/inc` may legitimately exist in two different images meaning two entirely different things (see [Images](../../docs/guide/concepts/images.md) and [Frames](../../docs/guide/concepts/frames.md)). What these examples share is the spelling of the ids; what a runtime *does* with a given id is decided by the image the frame runs against. The **canonical statement** of this carve-out (with the same four bounding conditions) lives in [`examples/TESTING.md` §Exception 2 — the cross-substrate Reagent/UIx/Helix id share](../TESTING.md#exception-2--the-cross-substrate-reagentuixhelix-id-share), alongside its sibling [§Exception 1 — the stock/slim counter `:counter/*` share](../TESTING.md#exception-1--the-stockslim-counter-counter-id-share). It is a bounded exception to the example-id-prefix convention, not an oversight, and the same four conditions apply:

1. **Allowed only because each example is a separate standalone shadow-cljs build** (`examples/counter-uix`, `examples/login-uix`) that MUST NOT be co-required with its Reagent/Helix twin into one runtime. They never share a JS runtime, so the identical ids never have to resolve inside one image.
2. **If any of these examples is ever folded into a shared wrapper / showcase / `test:browser` bundle alongside a sibling substrate, the ids must be disambiguated first** — either give each frame its own explicit image (disjoint `:select-ns` selectors, supplied to `rf/make-frame` / `reg-frame` via `:images`, so each frame resolves only its own substrate's registrations) or prefix the ids before co-loading them into one default image. The default image — the implicit projection over every `reg-*` loaded with no explicit `:images` — **fails loud** on a cross-namespace `(kind, id)` collision (`:rf.error/image-duplicate-id` at frame-creation time), naming both source namespaces; there is no silent last-write-wins on that path. So a naive co-load of two byte-identical twins into one default image is a refused assembly, not a silent clobber — which is exactly why explicit images or prefixed ids are the way to co-mount them.
3. **The carve-out covers shared event/sub/fx/machine/schema ids only — never views.** UIx views are `defui` (their own namespace); there is no `reg-view` registration to share or to resolve.
4. **Bundle isolation is the regression surface** that keeps the build split honest. `npm run test:bundle-isolation` release-builds and greps the counter triplet's `main.js` (`examples/counter`, `examples/counter-uix`, `examples/counter-helix`) in isolation; the other UIx builds carry compile coverage rather than a per-bundle grep.

Renaming the UIx ids to a `:counter-uix/*` / `:auth.login-uix/*` stem would tick the prefix-convention box, but it would also quietly dissolve the very parity these examples exist to demonstrate — so we leave the ids alone and take the documented exception instead. It's the same trade-off the slim-counter carve-out resolves the same way.

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
npm run test:adapter-smokes
```

That compiles the three adapter testbeds (`adapters/reagent-testbed`, `adapters/uix-testbed`, `adapters/helix-testbed`), stages each `index.html`, serves them, and drives the three `spec.cjs` smokes; the example builds in this directory carry no `spec.cjs` (the `examples/` tree is test-free). They do, however, get **compile coverage**: `npm run test:examples-compile` (from `implementation/`) `shadow-cljs compile`s every declared standalone `:examples/*` build — all three UIx builds here (`examples/counter-uix`, `examples/login-uix`, `examples/dashboard-uix`) — and fails on any compile error or warning, so a namespace / `:init-fn` / `:require` / UIx-form regression in these builds can no longer ship green. See [`examples/TESTING.md`](../TESTING.md#compile-coverage-gate-testexamples-compile). Bundle isolation is verified separately and is narrower: `npm run test:bundle-isolation` release-builds and greps the **counter triplet only** (`examples/counter`, `examples/counter-uix`, `examples/counter-helix`) to confirm a UIx counter bundle's `main.js` carries no Reagent code (and vice versa, and likewise for UIx ↔ Helix). The other UIx builds (`login-uix`, `dashboard-uix`) are compile-covered, not bundle-isolation-grepped.

To run one UIx example interactively in a browser, from `implementation/`:

```bash
npm run dev:example -- examples/counter-uix
```

That one command stages the example's hand-written `index.html` + the shared `_shared/` assets next to the compiled `main.js`, starts `shadow-cljs watch` (edits recompile live), serves the output dir on a free local port, and prints the URL to open. Swap in `examples/login-uix` or `examples/dashboard-uix` for the others; `npm run dev:example -- --list` lists every runnable standalone example. Add `--no-watch` for a one-shot compile-and-serve.

<details><summary>Advanced / troubleshooting: raw <code>shadow-cljs watch</code></summary>

The one-command runner wraps the raw watch + manual staging recipe:

```bash
shadow-cljs watch examples/counter-uix
```

The build emits `main.js` into `out/examples/counter-uix/`; you then copy the example's hand-written [`counter_uix/index.html`](counter_uix/index.html) (and the shared assets it references under [`../_shared/`](../_shared/)) alongside it and serve the output dir yourself. `npm run dev:example` does this staging + serving for you, so reach for the raw command only when you need to drive shadow-cljs directly.

</details>

## Cross-references

- [`spec/006-ReactiveSubstrate.md` §CLJS reference: UIx as alternative substrate](../../spec/006-ReactiveSubstrate.md#cljs-reference-uix-as-alternative-substrate) — the substrate contract that the UIx adapter implements; the eight decisions (hooks-first `use-subscribe`, frame Context, no auto-injection, `reg-view` Reagent-only, source-coord injection at the substrate boundary, `flush-views!` for tests, the curated example set, and target version UIx 2.x).
- [`spec/Conventions.md`](../../spec/Conventions.md#adapter-test-matrix-policy) — adapter test matrix policy: Reagent canonical, UIx and Helix smoke-tested.
- [`examples/reagent/counter/`](../reagent/counter/) and [`examples/reagent/login/`](../reagent/login/) — the canonical Reagent counterparts (same dataflow; different view layer; namespace prefix without the `_uix` suffix).
- [`examples/reagent/notebook/`](../reagent/notebook/) — the Reagent design-led sibling of `dashboard_uix`; same "Editorial Warm" identity, different substrate.
