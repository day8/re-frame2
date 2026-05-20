# reference-impl-tour

A guided tour of the CLJS reference implementation at `implementation/` in the re-frame2 repo. The tour is **descriptive, not normative**. Read this when you want to see how *someone* solved a problem during their port — but never quote it as "what re-frame2 requires." For "what re-frame2 requires", read `spec/`.

## Why this leaf exists

Engineers porting re-frame2 to a new host often want to see a working realisation before locking their own Phase 1 decisions. The CLJS reference is the only one that exists today, so it's the only worked example available. This leaf tours it.

**The tour's job:** name where each EP's code lives in the reference tree, flag which choices were *CLJS-specific* (and therefore not pattern requirements), and surface the per-host alternatives a port might pick instead.

**Not the tour's job:** teach you the spec. The spec teaches the spec. The tour shows how the reference compiled the spec into one realisation.

## Layout

```
implementation/
├── core/                    EP 001, 002, 008, 009 — the heart of the runtime
│   └── src/re_frame/
│       ├── core.cljc        public API surface (re-exports + macros)
│       ├── registrar.cljc   EP 001 — the (kind, id) → metadata registry
│       ├── frame.cljc       EP 002 — frame: {state, queue, sub-cache, id}
│       ├── events.cljc      EP 002 — event handlers; interceptor chain
│       ├── fx.cljc          EP 002 — fx registration + invocation
│       ├── cofx.cljc        EP 002 — coeffect registration + injection
│       ├── subs.cljc        EP 002 — subscription cache + signal graph
│       ├── interceptor.cljc EP 002 — interceptor primitives (impl detail)
│       ├── router.cljc      EP 002 — dispatch routing + drain
│       ├── views.cljs       EP 004 — view registration
│       ├── spec.cljc        EP 010 — schema hooks + validation
│       └── test_support.cljc EP 008 — test fixtures + helpers
├── adapters/
│   ├── reagent/             EP 006 — Reagent + React substrate adapter
│   └── plain-atom/          EP 006 — JVM / SSR / headless substrate adapter
├── epoch/                   EP 009 — epoch history for time-travel
├── flows/                   EP 013 — flows (separate artefact)
├── http/                    EP 014 — Managed HTTP (separate artefact)
├── machines/                EP 005 — state machine substrate (separate artefact)
├── routing/                 EP 012 — routing (separate artefact)
├── schemas/                 EP 010 — Malli integration (separate artefact)
└── ssr/                     EP 011 — server-side rendering (separate artefact)
```

The per-feature directories ship as **separate artefacts** in the published library — pay-as-you-go. Your port may bundle some or all into one artefact; the split is a packaging choice, not a contract.

## Walk by EP

### EP 001 — Registration (`core/src/re_frame/registrar.cljc`)

**What you'll find.** A single atom holding a nested map: `{:event {:id metadata} :sub {...} ...}`. `register-kind` / `unregister-kind` / `query-kind` operate on the atom. Re-registration replaces atomically and emits the trace event.

**What's CLJS-specific.**

- The atom + `swap!` model. Your host's mutable cell of choice (a Ref, a RefCell, a StateFlow, a class instance) does the same job.
- Source-coord capture via macros. The CLJS reference's `reg-event-db` is a macro that records `:ns`/`:line`/`:file` at compile time. Hosts without macros use stack frames, build-time codegen, or omit.

**What's pattern-required.** The registry is data, queryable via the public API. The `(kind, id) → metadata` shape is the contract; the storage mechanism is yours.

### EP 002 — Frames + events + effects + subs (`core/src/re_frame/{frame,events,fx,cofx,subs,router}.cljc`)

**What you'll find.** A frame is a deftype wrapping `r/atom` (Reagent ratom) for the app-db plus mutable fields for the queue and sub-cache. The event handler chain is implemented as **interceptors** — chained transforms over a context map, executed in `:before` and reversed in `:after` order. The dispatch loop runs the interceptor chain to completion before dequeuing the next event.

**What's CLJS-specific.**

- Interceptors as the implementation strategy for the six-step pipeline. They're an internal implementation detail per [`spec/Cross-Spec-Interactions.md`](https://day8.github.io/re-frame2/spec/Cross-Spec-Interactions/); your port can use a different mechanism (a monadic computation, a coroutine, a state machine over the dispatch envelope) so long as the observable contract from EP 002 is preserved.
- Reagent ratom + auto-tracked subscriptions. Your reactive substrate (D2 / D4.3) decides how this works.
- `defmulti` for fx resolution. A simple lookup table works too.

**What's pattern-required.** Frame as `{state, queue, sub-cache, id}`; event handler is pure `(state, event) → effects-map`; closed effect-map; run-to-completion drain per frame; subscription cache invalidates by value-equality.

### EP 006 — Reactive substrate (`adapters/{reagent,plain-atom}/`)

**What you'll find.** Two adapters in-tree. The Reagent adapter is browser-facing — `r/atom` as the container, Reagent `r/reaction` for subs, React for the render trigger. The plain-atom adapter is JVM-facing — `clojure.core/atom`, hand-rolled signal graph, no render trigger (SSR / headless). Both adapters implement the same six-function contract.

**What's CLJS-specific.**

- Reagent's auto-tracked deref-during-render dependency capture. Your substrate may need explicit subscriptions (Solid: `createMemo`, Vue: `computed`, hand-rolled: explicit `subscribe`/`unsubscribe`).
- The component-lifecycle integration uses Reagent's lifecycle methods. Other substrates plug into their own.

**What's pattern-required.** The six required functions + two optional + one lifecycle. Single-adapter-per-process. Adapter-internal state derivable from the frame value (revertibility constraint).

### EP 004 — Views (`core/src/re_frame/views.cljs`)

**What you'll find.** `reg-view` is a macro that wraps a function, captures source coords, and registers the wrapper with the registrar. The wrapper plugs into Reagent's component model. Plain Reagent functions (not registered via `reg-view`) still work — they bypass the registry and the frame-propagation contract.

**What's CLJS-specific.**

- Hiccup as the render-tree shape. Your render-tree is yours.
- The macro-based source-coord capture (same constraint as EP 001).
- Frame propagation via Reagent component context.

**What's pattern-required.** Pure `(state, props) → render-tree`. Render-tree is serialisable data. `reg-view` is the registry-aware entry point. Frame propagation is supported.

### EP 009 — Instrumentation (`core/src/re_frame/trace.cljc` and related)

**What you'll find.** A listener-registry atom + a ring-buffer atom. Emit walks the registry inline. Production elision via `goog-define :debug-enabled?` + Closure DCE. A CI script (`scripts/check-elision.cjs`) scans production bundles for dev-only sentinel strings; the build fails if any are found.

**What's CLJS-specific.**

- Closure DCE for production elision. JS/TS use Vite's `define` constants and tree-shaking; Rust uses `#[cfg(feature = "trace")]`; Python uses `if __debug__:` and the `-O` flag.
- The sentinel-string CI verifier is portable — copy the pattern, adapt to your bundler.
- Chrome Performance API bridge (`performance.mark` / `performance.measure`) is browser-only; alternative profilers per host (clj-async-profiler on JVM, cProfile in Python, `tracing` spans in Rust).

**What's pattern-required.** Trace event stream synchronous + in-order + per-emit. Listener registry. Retain-N ring buffer (dev). Production elision (host's mechanism). Structured error contract with `:operation :rf.error/<category>`.

## Walk by optional artefact

### `epoch/`

EP 009's optional time-travel layer. A frame-state ring buffer keyed by event id; `epoch-history` and `restore-epoch` are the public API. Useful pattern; copy the shape if your port wants time-travel.

### `flows/`

EP 013 implementation. Substrate-independent; the contract lives in 013.

### `http/`

EP 014 implementation + the Managed-HTTP pattern. The `:http` fx wraps a request lifecycle through a registered state machine. Substantial — read `Pattern-ManagedHTTP.md` first.

### `machines/`

EP 005 implementation. Largest non-core artefact. The transition machine, drain extensions for `:always` / `:after`, the `:spawn` contract for child-machine spawning. The CLJS reference uses spec multi-methods + a hand-rolled drain loop.

### `routing/`

EP 012 implementation. Hand-rolled URL matcher with a six-rule precedence cascade; not a third-party routing library. The routing registry plugs into EP 001's registrar — `(registrations :route)` is queryable.

### `schemas/`

EP 010 implementation. Malli is the wire layer; `reg-app-schema` and `:spec` metadata are the public API. Replace Malli with Zod / Pydantic / dry-rb per D5.

### `ssr/`

EP 011 implementation. Pure hiccup → HTML emitter (~200 lines) for the server side; `reagent.dom.client/hydrate` for the client side. The `:platforms` metadata fx-gating is in `core/`, not here — only the render-to-string and hydration helpers live here.

## What this tour deliberately doesn't tell you

- **Which mechanism is correct for your host.** That's Phase 1's job (`phase-1-decisions.md`). The tour names what the CLJS reference did; your port may diverge on every single point and still be a conformant re-frame2 implementation.
- **How to read the source line-by-line.** This is a map, not a transcription. When the leaf says "the dispatch loop runs the interceptor chain to completion before dequeuing the next event," it doesn't tell you which function in `router.cljc` to read. That's a follow-up: open the file, find the function (it's a fairly small file), read the top-level loop. The tour orients; the source reveals.
- **What the spec mandates.** That's `spec/`'s job. If anything in the tour reads as a requirement, that's tour-rhetoric leaking. Test every "the reference does X" against `spec/` before committing it to your port's design.

## When to consult the tour

- **Phase 1.** As a sanity check on Phase 1 decisions — "the CLJS reference picked X for D4.5; I'm picking Y because my host gives me Z." The tour grounds the choice.
- **Phase 2, per EP.** As a starting point for "where would I look to see how to handle the awkward case in EP N?" Open the matching directory above; read the corresponding source file.
- **Spec gaps.** When a spec section is ambiguous and the tour shows the reference made a specific choice — that's not the spec's choice, that's the reference's. Draft a GitHub issue against `day8/re-frame2` asking for the spec to clarify, show the engineer the draft, wait for explicit OK before filing (see [`cardinal-rules.md` §§8–9](cardinal-rules.md) for the filing pattern and the per-issue approval gate).

## When NOT to consult the tour

- **As a contract.** Never. The contract is `spec/`. The tour is "one worked example."
- **As a copy-paste source.** Translating Clojure macros into TS classes line-by-line produces brittle code. Read the contract, design from the contract, then *maybe* peek at the reference for an awkward edge case.
- **As a teaching resource.** The narrative guide at [`docs/guide/`](https://day8.github.io/re-frame2/guide/README/) is the teaching resource. The tour assumes you've read the spec.
