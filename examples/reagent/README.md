# Reagent — examples

The canonical substrate for re-frame2: every Spec (002 Frames, 004 Views, 005 StateMachines, 006 ReactiveSubstrate, 010 Schemas, 011 SSR, 012 Routing, 014 HTTPRequests, every Pattern-* doc) was authored against the Reagent adapter, and every JVM `clojure -M:test` run, every shadow-cljs `node-test` build, every `:browser-test` run, and every `npm run test:examples` invocation exercises the Reagent path end-to-end. See [Conventions §Adapter test matrix policy](../../spec/Conventions.md#adapter-test-matrix-policy) for the policy and rationale.

This directory holds the **full set of worked Reagent examples** (counting each 7GUIs task individually) that ship in the catalogue at [examples/README.md](../README.md). Each example sits in its own self-contained sub-folder with the CLJS source and a hand-written `index.html`. The 7GUIs cluster has its own internal grouping under [`seven_guis/`](seven_guis/README.md).

Story Stage 8 (`tools/story` end-to-end on the counter) lives as a tool-owned testbed at [`tools/story/testbeds/counter_with_stories/`](../../tools/story/testbeds/counter_with_stories/) — catalogued with the tool that owns it rather than alongside the tutorial examples.

## Layout

```
reagent/
  counter/                     <-- the smallest possible app (CP-1, CP-2, CP-4)
  login/                       <-- single-feature scaffold (CP-5, CP-6)
  todomvc/                     <-- canonical benchmark (TodoMVC spec)
  routing/                     <-- CP-7 worked example (Spec 012)
  ssr/                         <-- CP-9 worked example (Spec 011)
  ssr_streaming/               <-- streaming SSR worked example (Spec 011 §Streaming)
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
  realworld/                   <-- the canonical multi-artefact integration test
```

Per [`spec/Conventions.md`](../../spec/Conventions.md): all examples register their app-db slices via `reg-app-schema`; views are registered via the `reg-view` macro (Var-reference Form-1); the catalogue at [`../README.md`](../README.md) maps each example to the Specs it exercises.

## Running

The `examples/` tree is **test-free** — real-regression coverage lives in the substrate contract tests (`npm run test:cljs`) and the framework gates, not under `examples/`. `npm run test:examples` compiles and serves only the three adapter testbeds (`implementation/adapters/<name>/testbed/`); it does **not** build the examples in this directory. See [examples/README.md §Testing](../README.md) for the full split.

To view one example in a browser, watch its build directly from `implementation/`:

```bash
shadow-cljs watch examples/counter
```

The watch build emits `main.js` into `out/examples/counter/`; copy the example's hand-written `index.html` (and the shared assets it references under [`../_shared/`](../_shared/)) next to it, then serve `out/examples/counter/` over HTTP.

## Cross-references

- [`spec/006-ReactiveSubstrate.md`](../../spec/006-ReactiveSubstrate.md) — the substrate contract Reagent's adapter satisfies.
- [`spec/Conventions.md`](../../spec/Conventions.md) — adapter test matrix policy, packaging conventions, the bundle-isolation argument.
- [`examples/uix/`](../uix/) — UIx-substrate counterparts of `counter` and `login` (smoke-test pair per Decision 7).
- [`examples/reagent-slim/`](../reagent-slim/) — the `day8/reagent-slim` substrate's example set (the counter re-mounted on the slim Reagent rewrite; adapter-owned bundle-isolation fixture).
