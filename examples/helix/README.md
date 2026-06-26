# Helix — examples

The Helix adapter (see [Spec 006 §CLJS reference: Helix as alternative substrate](../../spec/006-ReactiveSubstrate.md#cljs-reference-helix-as-alternative-substrate)). Helix is the third canonical browser substrate alongside Reagent and UIx; the eight UIx decisions apply unchanged because Helix and UIx share the React + hooks substrate model. The adapter consumes the same `re-frame.adapter.context` React Context object the Reagent and UIx adapters expose (Decision 2), so a single app can in principle mix-and-match — though the canonical pattern is to choose one substrate per app.

This directory holds the Helix adapter examples, not a 1:1 mirror of the Reagent set. Per Decision 7 of [Spec 006 §CLJS reference: Helix as alternative substrate](../../spec/006-ReactiveSubstrate.md#cljs-reference-helix-as-alternative-substrate) and [Conventions §Adapter test matrix policy](../../spec/Conventions.md#adapter-test-matrix-policy): non-canonical adapters ship a representative **smoke-test subset**. For Helix that subset is **counter + login** — the pair share their dataflow with the Reagent siblings (substrate-agnostic events, subs, schemas, machine, managed-HTTP stub), so confirming the pair runs is what proves the Helix adapter implements the substrate contract correctly. The Reagent realworld scaffold is heavy with Reagent-flavoured idioms and is deferred until a Helix user wants it.

Alongside the smoke pair this directory also ships **`process_monitor_helix`** — a design-led example proving Helix can drive a polished multi-pane layout. It is a documented example and a declared shadow-cljs build (so it carries compile coverage), but it is **not** part of the Decision-7 smoke-test subset: the spec's Helix subset is counter + login only, and `process_monitor_helix` makes no Decision-7 contract claim.

## Layout

```
helix/
  counter_helix/          <-- the Reagent counter dataflow rendered through Helix
  login_helix/            <-- the Reagent login example through Helix
  process_monitor_helix/  <-- design-led example proving multi-pane layout on Helix
```

Each example sits in its own folder with the CLJS source (`core.cljs`) and a hand-written `index.html`. The `examples/` tree is **test-free**: no example ships a Playwright spec — see [Testing](#testing) below for where the real regression coverage lives. The on-disk folder names carry the `_helix` suffix because the CLJS namespaces (`counter-helix.core`, `login-helix.core`) are deliberately distinct from their Reagent siblings (`counter.core`, `login.core`) and UIx siblings (`counter-uix.core`, `login-uix.core`) — every substrate tree ends up on the same shadow-cljs classpath, so the namespaces have to be unique. The folder name follows the namespace convention (`-` becomes `_` on disk).

The dataflow — events, subs, schemas, machine, managed-HTTP stub — is **identical** to the Reagent and UIx siblings under [`../reagent/`](../reagent/) and [`../uix/`](../uix/); only the view layer differs. Helix components are written as `defnc` and consume subs via the `use-subscribe` hook (Decision 1). Each `core.cljs` carries a `SUBSTRATE BOUNDARY` divider that makes this split legible at a glance: the substrate-agnostic artefact layer (events / subs / schemas / machine / fx) sits above it, and the only substrate-specific code (the `defnc` views + mount) sits below.

This duplication of the artefact layer across the three substrates is **deliberate and the intended v2 style**, not copy-paste drift waiting to happen. The byte-for-byte id-identity *is* the cross-substrate parity demonstration: the same `:counter/*` / `:auth.login/*` ids driving Reagent `reg-view`, UIx `defui`, and Helix `defnc` proves the adapter contract is the whole story. It is intentionally **not** hoisted into a shared model namespace — each substrate example is a self-contained `:browser` build, and `npm run test:bundle-isolation` release-builds and greps the counter triplet (`examples/counter`, `examples/counter-uix`, `examples/counter-helix`) to confirm a Helix counter `main.js` carries no Reagent/UIx code (and vice versa). That counter-triplet grep is the representative proof of the build split; a shared model required into all three builds would defeat that isolation and the parity claim it underwrites. The rationale and its four bounding conditions are catalogued in [`examples/TESTING.md` §Exception 2](../TESTING.md#exception-2--the-cross-substrate-reagentuixhelix-id-share).

Per Decision 4 the `reg-view` macro stays Reagent-only; Helix users write `defnc` directly and take `dispatch` off a `(rf/frame-handle)` for click handlers (Decision 3 — components call dispatch / use-subscribe explicitly, no auto-injection).

## What each example demonstrates

- **`helix/counter_helix/`** ([build id `examples/counter-helix`](../../implementation/shadow-cljs.edn))
  Same `:counter/initialise` / `:counter/inc` / `:counter/dec` events as the Reagent counter; the view renders +/- buttons and a count between them. The count seeds to `5` (via `:counter/initialise`) and moves as the buttons dispatch.

- **`helix/login_helix/`** ([build id `examples/login-helix`](../../implementation/shadow-cljs.edn))
  Same login state machine (`:idle -> :submitting -> :authed`/`:error-shown`/`:locked-out`), same Malli schemas, same `:auth.login.demo/managed-stub` stub fx, and the same byte-identical Pattern-Forms slice/events/subs as the Reagent and UIx login examples. The view layer is a Helix `defnc` form with **controlled inputs**: the email/password draft lives in app-db at `[:auth :login-form]` (read via the `:auth.login/draft` sub, mutated via `:auth.login/edit-field`) — no `use-state` for input state. Entering credentials and submitting drives the machine to `:authed` and the welcome banner appears.

- **`helix/process_monitor_helix/`** ([build id `examples/process-monitor-helix`](../../implementation/shadow-cljs.edn))
  Design-led example proving Helix can drive a substantive multi-pane layout. Shares the `_shared/css/style.css` "Editorial Warm" identity with the Reagent notebook and UIx dashboard counterparts. The desktop layout is the canonical two-pane shell (process pane + log pane); narrow viewports get a responsive path (stacked panes ≤900px, wrapping summary tiles and collapsed row/log tracks ≤560px) so the declared `width=device-width` viewport renders without horizontal overflow on phones and tablets. No state machines, no HTTP — design-led examples exist to prove polished visuals + interaction, not to replay platform features other examples already cover.

## Testing

The `examples/` tree carries no tests. Browser smoke coverage for the Helix substrate lives at the **adapter level**: a single mount + dispatch + assert smoke at [`implementation/adapters/helix/testbed/spec.cjs`](../../implementation/adapters/helix/testbed/) (one each for Reagent, UIx, and Helix). Real regressions are caught by the substrate contract tests (`npm run test:cljs`), the Xray feature-matrix gate (`npm run test:xray-feature-gate`), bundle-isolation, the perf-bundle gate, and mcp-conformance — not by per-example specs.

From `implementation/`, the adapter smokes run via:

```bash
npm run test:adapter-smokes
```

That compiles the three adapter testbeds (`adapters/reagent-testbed`, `adapters/uix-testbed`, `adapters/helix-testbed`), stages each `index.html`, serves them, and drives the three `spec.cjs` smokes; the example builds in this directory carry no `spec.cjs` (the `examples/` tree is test-free). They do, however, get **compile coverage**: `npm run test:examples-compile` (from `implementation/`) `shadow-cljs compile`s every declared standalone `:examples/*` build — all three Helix builds here (`examples/counter-helix`, `examples/login-helix`, `examples/process-monitor-helix`) — and fails on any compile error or warning, so a namespace / `:init-fn` / `:require` / Helix-form regression in these builds can no longer ship green. See [`examples/TESTING.md`](../TESTING.md#compile-coverage-gate-testexamples-compile). Bundle isolation is verified separately and is narrower: `npm run test:bundle-isolation` release-builds and greps the **counter triplet only** (`examples/counter`, `examples/counter-uix`, `examples/counter-helix`) to confirm a Helix counter bundle's `main.js` carries no Reagent code (and vice versa, and likewise for UIx ↔ Helix). The other Helix builds (`login-helix`, `process-monitor-helix`) are compile-covered, not bundle-isolation-grepped.

**The design-led example is compile-covered but render-verified by a documented checklist.** `process_monitor_helix` is a polished interactive UI (live tick loop, filter chips, row selection, responsive layout), and `test:examples-compile` proves only that it *compiles* — it never serves the page, so a blank render, broken `_shared` asset path, stalled tick loop, dead filters, or mobile overflow would still pass green. Because the `examples/` tree is test-free (no `*.spec.cjs`), that design-led class of regression is guarded by the **manual checklist** in [`process_monitor_helix/README.md` §Design-led runtime](process_monitor_helix/README.md#design-led-runtime--what-to-copy-and-a-manual-checklist) — nonblank render, visible process/log panes, filter-chip + row-selection interaction, the live tick, and a narrow-viewport no-horizontal-overflow pass — plus the static `check-examples-assets` WCAG/asset contract. This mirrors the UIx [`dashboard_uix`](../uix/dashboard_uix/) sibling's policy: design-led polish is checklist-guarded, not converted into a per-example Playwright suite.

To iterate on one Helix example interactively, from `implementation/`:

```bash
npm run dev:example -- examples/counter-helix
```

One command stages the example's hand-written `index.html` + the shared `_shared/` assets next to the compiled `main.js`, starts `shadow-cljs watch`, serves the output dir on a free local port, and prints the URL. Swap the build id for `examples/login-helix` or `examples/process-monitor-helix`. (The raw `shadow-cljs watch examples/counter-helix` + manual-copy recipe still works as an advanced path — see each per-example README.)

## Cross-references

- [`spec/006-ReactiveSubstrate.md` §CLJS reference: Helix as alternative substrate](../../spec/006-ReactiveSubstrate.md#cljs-reference-helix-as-alternative-substrate) — the substrate contract that the Helix adapter implements; the eight decisions (frame Context, hooks-first, `use-subscribe`, no auto-injection, source-coord injection at the substrate boundary, `flush-views!` for tests, the smoke-test subset, and target version Helix 0.2.x).
- [`spec/Conventions.md` §Adapter test matrix policy](../../spec/Conventions.md#adapter-test-matrix-policy) — adapter test matrix policy: Reagent canonical, UIx and Helix smoke-tested.
- [`examples/reagent/counter/`](../reagent/counter/) and [`examples/reagent/login/`](../reagent/login/) — the canonical Reagent counterparts (same dataflow; different view layer; namespace prefix without the `_helix` suffix).
- [`examples/uix/counter_uix/`](../uix/counter_uix/) and [`examples/uix/login_uix/`](../uix/login_uix/) — the UIx siblings; the dataflow is identical, the view layer uses `defui` + `use-subscribe` rather than `defnc` + `use-subscribe`.
- [`examples/reagent/notebook/`](../reagent/notebook/) and [`examples/uix/dashboard_uix/`](../uix/dashboard_uix/) — the Reagent and UIx design-led siblings of `process_monitor_helix`; same "Editorial Warm" identity, different substrate.
