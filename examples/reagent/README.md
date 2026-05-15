# Reagent — examples

The canonical substrate for re-frame2: every Spec (002 Frames, 004 Views, 005 StateMachines, 006 ReactiveSubstrate, 010 Schemas, 011 SSR, 012 Routing, 014 HTTPRequests, every Pattern-* doc) was authored against the Reagent adapter, and every JVM `clojure -M:test` run, every shadow-cljs `node-test` build, every `:browser-test` run, and every `npm run test:examples` invocation exercises the Reagent path end-to-end. See [Conventions §Adapter test matrix policy](../../spec/Conventions.md#adapter-test-matrix-policy) for the policy and rationale.

This directory holds the **full set of nineteen worked Reagent examples** (counting each 7GUIs task individually) that ship in the catalogue at [examples/README.md](../README.md). Each example sits in its own self-contained sub-folder with the CLJS source, a hand-written `index.html`, and a Playwright smoke spec (`<name>.spec.cjs`). The 7GUIs cluster has its own internal grouping under [`7Guis/`](7Guis/README.md).

Story Stage 8 (`tools/story` end-to-end on the counter) moved out to a tool-owned testbed at [`tools/story/testbeds/counter_with_stories/`](../../tools/story/testbeds/counter_with_stories/) per rf2-p8f2s.

## Layout

```
reagent/
  counter/                     <-- the smallest possible app (CP-1, CP-2, CP-4)
  counter_slim_and_fast/       <-- adapter-owned day8/reagent-slim bundle-isolation fixture
  login/                       <-- single-feature scaffold (CP-5, CP-6)
  todomvc/                     <-- canonical benchmark (TodoMVC spec)
  routing/                     <-- CP-7 worked example (Spec 012)
  ssr/                         <-- CP-9 worked example (Spec 011)
  managed_http_counter/        <-- compact Spec 014 demo
  state_machine_walkthrough/   <-- runnable companion to docs/guide/09
  nine_states/                 <-- the nine canonical UI states
  boot/                        <-- Pattern-Boot worked example (rf2-dsm2)
  long_running_work/           <-- Pattern-LongRunningWork worked example (rf2-o9fg)
  websocket/                   <-- Pattern-WebSocket worked example (rf2-yf97)
  7Guis/                       <-- 7GUIs benchmark cluster
    cells/  circle_drawer/  crud/  flight_booker/  temperature/  timer/
  realworld/                   <-- the canonical multi-artefact integration test
```

Per [`spec/Conventions.md`](../../spec/Conventions.md): all examples register their app-db slices via `reg-app-schema`; views are registered via the `reg-view` macro (Var-reference Form-1); the catalogue at [`../README.md`](../README.md) maps each example to the Specs it exercises.

## Running

From `implementation/`:

```bash
npm run test:examples
```

That compiles every Reagent build under `out/examples/<name>/`, stages the hand-written `index.html` next to `main.js`, serves the output on port 8030, and drives the Playwright specs. See [examples/README.md §End-to-end verification](../README.md#end-to-end-verification) for orchestrator details.

To iterate on one example interactively, watch its build directly from `implementation/`:

```bash
shadow-cljs watch examples/counter
```

(Run `npm run test:examples` once first so `out/examples/counter/index.html` is staged; subsequent watch builds reuse it.)

## Cross-references

- [`spec/006-ReactiveSubstrate.md`](../../spec/006-ReactiveSubstrate.md) — the substrate contract Reagent's adapter satisfies.
- [`spec/Conventions.md`](../../spec/Conventions.md) — adapter test matrix policy, packaging conventions, the bundle-isolation argument.
- [`examples/uix/`](../uix/) — UIx-substrate counterparts of `counter` and `login` (smoke-test pair per Decision 7).
