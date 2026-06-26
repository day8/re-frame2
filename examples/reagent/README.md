# Reagent — examples

Reagent is the canonical substrate, and that word carries weight here. Every Spec — 002 Frames, 004 Views, 005 StateMachines, 006 ReactiveSubstrate, 010 Schemas, 011 SSR, 012 Routing, 014 HTTPRequests, 016 Resources, and every Pattern-* doc — was authored against the Reagent adapter, so this is the substrate the framework was *thought through* on. If a contract holds anywhere, it holds here first.

What you'll find in this directory is the **full set of worked Reagent examples** (counting each 7GUIs task individually) shipped in the catalogue at [examples/README.md](../README.md). Each one is self-contained: its own sub-folder, its own CLJS source, and a hand-written `index.html` — no shared scaffolding to untangle. The 7GUIs cluster keeps its own internal grouping under [`seven_guis/`](seven_guis/README.md).

A couple of things live *near* the examples but aren't part of this set, and it's worth knowing why. Story Stage 8 — `tools/story` driven end-to-end on the counter — sits as a tool-owned testbed at [`tools/story/testbeds/counter_with_stories/`](../../tools/story/testbeds/counter_with_stories/). It's catalogued with the tool that owns it rather than here, because the counter is the substrate Story happens to exercise, not the point.

Two of the examples *do* ship something extra: an **intentionally auxiliary Story showcase** layered right over the example — [`login/`](login/) and [`nine_states/`](nine_states/). These are not separate testbeds like the counter one above; each one reaches into its *own* example's real machine and views and enumerates that example's view-states as Story variants. They live in the example folder precisely because they showcase *that* worked example. Each example's README has the run command.

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

Those two RealWorld variants are **siblings on purpose, not a half-finished rewrite** — and the pairing is itself the lesson. [`realworld/`](realworld/) is the canonical [Spec 014 `:rf.http/managed`](../../spec/014-HTTPRequests.md) demo, doing all the managed-HTTP work by hand: schema-driven decode, classification order, retry + abort, optimistic-rollback. [`realworld_resources/`](realworld_resources/) is the *same* Conduit app expressed through [Spec 016 Resources](../../spec/016-Resources.md) + mutations — the same behaviour, but as declarative server-state (`read → write → invalidate → refetch`) with none of the hand-wiring. Both stay, because that managed-HTTP coverage is load-bearing on its own; but the real reward is reading them side by side and watching how much ceremony the resources layer quietly absorbs (start at [`realworld_resources/README.md`](realworld_resources/README.md)).

A few conventions tie the set together, all from [`spec/Conventions.md`](../../spec/Conventions.md): schema-bearing examples register their app-db slices via `reg-app-schema`, views are registered via the `reg-view` macro (Var-reference Form-1), and the catalogue at [`../README.md`](../README.md) maps each example to the Specs it exercises. A handful — `counter`, `routing`, `todomvc`, `notebook`, `managed_http_counter` — are deliberately schema-free, kept minimal so the dataflow shows through.

## Running

To look at one of these in a browser, watch its build directly from `implementation/`:

```bash
shadow-cljs watch examples/counter
```

Then serve the example's output directory over HTTP and open it — there's no bundler config or dev server to stand up.

## Coverage level per Reagent example

The table below records the *highest* coverage layer pinning each example.

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

## Cross-references

- [`spec/006-ReactiveSubstrate.md`](../../spec/006-ReactiveSubstrate.md) — the substrate contract Reagent's adapter satisfies.
- [`spec/Conventions.md`](../../spec/Conventions.md) — the registration and packaging conventions these examples follow.
- [`examples/uix/`](../uix/) — UIx-substrate counterparts of `counter` and `login` (smoke-test pair per Decision 7).
- [`examples/reagent-slim/`](../reagent-slim/) — the `day8/reagent-slim` substrate's example set (the counter re-mounted on the slim Reagent rewrite; adapter-owned bundle-isolation fixture).
