# Reagent — examples

The canonical substrate for re-frame2: every Spec (002 Frames, 004 Views, 005 StateMachines, 006 ReactiveSubstrate, 010 Schemas, 011 SSR, 012 Routing, 014 HTTPRequests, 016 Resources, every Pattern-* doc) was authored against the Reagent adapter, and the Reagent path is exercised end-to-end by every JVM `clojure -M:test` run and every shadow-cljs `node-test` build. See [Conventions §Adapter test matrix policy](../../spec/Conventions.md#adapter-test-matrix-policy) for the policy and rationale.

**What pins these examples (the coverage layers).** The `examples/` tree is itself test-free (no `*.spec.cjs`, no `test/` dirs under `examples/`), but each example's behaviour is pinned by one or more of three distinct layers — do not conflate them:

| Layer | Command | What it covers |
|---|---|---|
| **Direct/headless CLJS fixture** | `npm run test:cljs` | Per-example semantic tests (outside `examples/`) that require the example's production namespace and drive its events / subs / machines / resources directly. Only some examples have one (see the per-example coverage column below). |
| **Compile coverage** | `npm run test:examples-compile` | Compiles EVERY declared `:examples/*` shadow-cljs build (warnings-as-errors). Catches a missing namespace / typo'd init-fn / bad `:require` / compile-time form error in any example — but proves nothing about runtime behaviour. |
| **Adapter-mount browser smoke** | `npm run test:examples` | Exactly THREE adapter-level smokes (Reagent / UIx / Helix) at `implementation/adapters/<name>/testbed/spec.cjs` — mount + dispatch + assert. It does **NOT** build the examples in this directory. |

Real cross-cutting regression coverage additionally lives in the framework gates (`npm run test:xray-feature-gate`, `test:bundle-isolation`, `test:perf-bundle`, mcp-conformance). The per-example coverage column in the [catalogue](../README.md) and the [coverage table below](#coverage-level-per-reagent-example) record which layer(s) pin each example.

This directory holds the **full set of worked Reagent examples** (counting each 7GUIs task individually) that ship in the catalogue at [examples/README.md](../README.md). Each example sits in its own self-contained sub-folder with the CLJS source and a hand-written `index.html`. The 7GUIs cluster has its own internal grouping under [`seven_guis/`](seven_guis/README.md).

Story Stage 8 (`tools/story` end-to-end on the counter) lives as a tool-owned testbed at [`tools/story/testbeds/counter_with_stories/`](../../tools/story/testbeds/counter_with_stories/) — catalogued with the tool that owns it rather than alongside the tutorial examples.

Two examples additionally ship an **intentionally auxiliary Story showcase** layered over the example itself — [`login/`](login/) (`stories.cljs` + `stories_host.cljs` + `stories.index.html`, build `:examples/login-with-stories`) and [`nine_states/`](nine_states/) (build `:examples/nine-states-with-stories`). Unlike the tool-owned counter testbed above, these are not separate testbeds: each sources its own example's real machine/views and enumerates the example's view-states as Story variants (with the Xray preload wired). They live in the example folder because they showcase *that* worked example; the example tree stays test-free — a Story showcase is a runnable inspection surface, not a test. See each example's README for the showcase run command.

## Layout

```
reagent/
  counter/                     <-- the smallest possible app (CP-1, CP-2, CP-4)
  login/                       <-- single-feature scaffold (CP-5, CP-6)
  todomvc/                     <-- canonical benchmark (TodoMVC spec)
  routing/                     <-- CP-7 worked example (Spec 012)
  ssr/                         <-- CP-9 worked example (Spec 011)
  ssr_streaming/               <-- streaming SSR worked example (Spec 011 §Streaming)
  resources/                   <-- Spec 016 Resources worked example (route/event/machine-owned)
  infinite_feed/               <-- EP-0021 infinite resource (load-more / infinite-scroll feed)
  linearlite/                  <-- EP-0019 optimistic mutation + rollback (Linearlite issue board)
  resources_ssr/               <-- Spec 016 §SSR resource preload + hydration
  managed_http_counter/        <-- compact Spec 014 demo
  state_machine_walkthrough/   <-- runnable companion to docs/guide/11-machines
  nine_states/                 <-- the nine canonical UI states
  flows/                       <-- Spec 013 Flows exemplar (cart with materialised totals)
  boot/                        <-- Pattern-Boot worked example
  long_running_work/           <-- Pattern-LongRunningWork worked example
  websocket/                   <-- Pattern-WebSocket worked example
  notebook/                    <-- design-led example (Editorial Warm identity)
  seven_guis/                  <-- 7GUIs benchmark cluster
    cells/  circle_drawer/  crud/  flight_booker/  temperature/  timer/
  realworld/                   <-- the canonical multi-artefact integration test (managed HTTP: Spec 014)
  realworld_resources/         <-- the SAME app on resources + mutations (Spec 016)
```

The two RealWorld variants are deliberate **siblings, not a rewrite**: [`realworld/`](realworld/) is the canonical [Spec 014 `:rf.http/managed`](../../spec/014-HTTPRequests.md) demo (schema-driven decode, classification order, retry + abort, optimistic-rollback against managed HTTP), and [`realworld_resources/`](realworld_resources/) expresses the *same* Conduit app through [Spec 016 Resources](../../spec/016-Resources.md) + mutations (declarative server-state: `read → write → invalidate → refetch` with no hand-wiring). Both stay because that managed-HTTP coverage is load-bearing; read them side by side to see what resources buy you (see [`realworld_resources/README.md`](realworld_resources/README.md)).

Per [`spec/Conventions.md`](../../spec/Conventions.md): schema-bearing examples register their app-db slices via `reg-app-schema`; views are registered via the `reg-view` macro (Var-reference Form-1); the catalogue at [`../README.md`](../README.md) maps each example to the Specs it exercises. The intentionally minimal examples (`counter`, `routing`, `todomvc`, `notebook`, `managed_http_counter`) are deliberately schema-free — `counter` in particular is kept dependency-light so it doubles as a bundle-isolation fixture, so adding a schema there would weaken that coverage.

## Running

The `examples/` tree is **test-free** — real-regression coverage lives in the substrate contract tests (`npm run test:cljs`) and the framework gates, not under `examples/`. `npm run test:examples` compiles and serves only the three adapter testbeds (`implementation/adapters/<name>/testbed/`); it does **not** build the examples in this directory. See [examples/README.md §Testing](../README.md) for the full split.

To view one example in a browser, watch its build directly from `implementation/`:

```bash
shadow-cljs watch examples/counter
```

The watch build emits `main.js` into `out/examples/counter/`; copy the example's hand-written `index.html` (and the shared assets it references under [`../_shared/`](../_shared/)) next to it, then serve `out/examples/counter/` over HTTP.

## Coverage level per Reagent example

The maintained map below records the highest coverage layer pinning each example, so a newly-added canonical example cannot silently remain unpinned. **Compile-only** examples carry only `test:examples-compile` coverage by design (deliberately minimal or design-led; the runtime contracts they lean on are pinned by the substrate contract tests + framework gates, not a per-example fixture). **Direct semantic fixture** examples additionally have a headless CLJS (or JVM) test that requires the production namespace and drives its wiring; the "fixture" column names it. All examples also ride the three adapter-mount browser smokes transitively (the adapter, not the page, is what those smokes assert).

| Example | Coverage level | Direct fixture (if any) |
|---|---|---|
| `counter/` | Compile-only + bundle-isolation fixture | — (kept dependency-light as the bundle-isolation fixture) |
| `login/` | Direct semantic fixture | `re-frame.login-cljs-test`; machine table also in `state-machine-walkthrough` |
| `todomvc/` | Direct semantic fixture | `re-frame.todomvc-cljs-test` |
| `routing/` | Compile-only | — (Spec 012 routing pinned in `implementation/routing/test/`) |
| `ssr/` | Direct semantic fixture (JVM) | `re-frame.examples-test` (`ssr-example-*`) |
| `ssr_streaming/` | Direct semantic fixture (JVM) | `re-frame.examples-test` (`ssr-streaming-example-*`) |
| `managed_http_counter/` | Compile-only | — (Spec 014 pinned in `implementation/http/test/`) |
| `state_machine_walkthrough/` | Direct semantic fixture (JVM) | `re-frame.examples-test` (`state-machine-walkthrough-runs-headless`) |
| `nine_states/` | Direct semantic fixture | `re-frame.nine-states-cljs-test` |
| `boot/` | Direct semantic fixture | `re-frame.boot-cljs-test` |
| `long_running_work/` | Direct semantic fixture | `re-frame.long-running-work-cljs-test` |
| `websocket/` | Direct semantic fixture | `re-frame.websocket-cljs-test` |
| `seven_guis/*` (6) | Compile-only | — (design-led 7GUIs benchmarks; Spec 004/006 pinned in core) |
| `notebook/` | Compile-only (design-led) | — (Editorial-Warm design exemplar; tiny pure markdown parser) |
| `flows/` | Compile-only | — (Spec 013 Flows pinned in `implementation/flows/test/`) |
| `resources/` | Direct semantic fixture | `re-frame.resources-example-cljs-test` (route / event-lease / manual-refresh / reader start-stop) |
| `resources_ssr/` | Direct semantic fixture (JVM) | `re-frame.examples-test` (`resources-ssr-example-dynamic-payload-hydrates-without-frame-id-mismatch`) |
| `realworld_resources/` | Direct semantic fixture | `re-frame.realworld-resources-cljs-test` (session scope / bearer / mutation populates+invalidates+reply-to / editor flow+can-leave / logout clear-scope / auth machine) |
| `realworld/` | Direct semantic fixture | `re-frame.realworld-cljs-test` |
| `infinite_feed/` | Direct semantic fixture | `re-frame.infinite-feed-example-cljs-test` (route page-0 ensure / causal load-more append+cursor / nil terminal / page-error third channel / page-0 first-load error) |
| `linearlite/` | Direct semantic fixture | `re-frame.linearlite-example-cljs-test` (route board ensure / `:optimistic` apply before reply / `:populates` commit / failure rollback for create+edit-title+change-status) |

The direct fixtures live under `implementation/adapters/reagent/test/re_frame/*_cljs_test.cljs` (CLJS) and `implementation/core/test/re_frame/examples_test.clj` (JVM) — never under `examples/`. The `resources/` machine-owned-resource ENSURE step is intentionally left to the artefact runtime + compile coverage (driving the live machine spawn/destroy deterministically in a shared headless bundle is brittle); its start/stop EVENT glue is the pinned example-specific assertion.

## Cross-references

- [`spec/006-ReactiveSubstrate.md`](../../spec/006-ReactiveSubstrate.md) — the substrate contract Reagent's adapter satisfies.
- [`spec/Conventions.md`](../../spec/Conventions.md) — adapter test matrix policy, packaging conventions, the bundle-isolation argument.
- [`examples/uix/`](../uix/) — UIx-substrate counterparts of `counter` and `login` (smoke-test pair per Decision 7).
- [`examples/reagent-slim/`](../reagent-slim/) — the `day8/reagent-slim` substrate's example set (the counter re-mounted on the slim Reagent rewrite; adapter-owned bundle-isolation fixture).
