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
│       ├── path.cljc        EP-0012 — internal `:rf/path` algebra (get/lookup/put/over/compose/prefix?/overlap?/instantiate)
│       ├── identity.cljc    EP-0012 — internal canonical-EDN identity (`CEDN-1`); digest is a derived projection
│       ├── registrar.cljc   EP 001 — the (kind, id) → metadata registry
│       ├── frame.cljc       EP 002 — frame: {frame-state, queue, sub-cache, id}; frame-state = {:rf.db/app :rf.db/runtime}
│       ├── events.cljc      EP 002 — event handlers; interceptor chain
│       ├── fx.cljc          EP 002 — fx registration + invocation
│       ├── cofx.cljc        EP 002 — coeffect registration + declared delivery (value-returning suppliers; EP-0017)
│       ├── subs.cljc        EP 002 — subscription cache + signal graph
│       ├── interceptor.cljc EP 002 — interceptor primitives (impl detail)
│       ├── router.cljc      EP 002 — dispatch routing + drain
│       ├── views.cljs       EP 004 — view registration
│       ├── spec.cljc        EP 010 — schema hooks + validation
│       ├── trace.cljc       EP 009 — trace listener registry + ring buffer
│       ├── emit.cljc        EP 009 — trace emit sites
│       ├── error.cljc       EP 009 — structured error contract
│       ├── substrate/       EP 006 — substrate contract + reference substrate
│       │   ├── adapter.cljc   the ten-entry substrate contract (6 req + 3 opt + dispose)
│       │   └── plain_atom.cljc plain-atom reference substrate (JVM / SSR / headless)
│       └── test_support.cljc EP 008 — test fixtures + helpers
├── adapters/                EP 006 — React-binding substrate adapters
│   ├── reagent/             Reagent + React adapter
│   ├── reagent-slim/        slim Reagent variant (no stock Reagent / react-dom)
│   ├── uix/                 UIx + React adapter
│   ├── helix/               Helix + React adapter
│   └── test-react/          test-only React harness adapter
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

### The shared path + identity foundation (`core/src/re_frame/{path,identity}.cljc`)

**What you'll find.** Two small internal namespaces that every path-shaped and identity-shaped fact inherits (EP-0012). `path.cljc` holds the `:rf/path` algebra — `get` / `lookup` / `put` / `over` / `compose` / `prefix?` / `overlap?` / `instantiate` over concrete path vectors, with `[]` as the root path and the path laws (root-path law, `put`-`lookup` round trip, symmetric `overlap?`) backed by a dedicated law-test file (`core/test/re_frame/path_laws_cljs_test.cljc`). `identity.cljc` holds the canonical-EDN identity function and the `CEDN-1` reference byte encoding (type tag before every value, map entries sorted by key bytes, set elements sorted by element bytes), fail-closed on out-of-domain host values (`:rf.error/non-edn-identity`), with the digest as a derived, recomputable projection — backed by `core/test/re_frame/identity_cedn1_cljs_test.cljc`. Both namespace docstrings open by stating they are **INTERNAL** at this slice — semantics normative, names not yet public — and the consumers (flows / schemas / routing / resources) that will graduate a public name once two-plus use them unchanged.

**What's CLJS-specific.**

- The function/namespace **names** (`re-frame.path`, `re-frame.identity`) and their `.cljc` cross-compilation. Your port picks its own internal module names; the names are not the contract.
- `get-in` / `assoc-in`-shaped intermediate-creation (missing intermediates become maps). The *behaviour* is the contract (`put` is total, root-path law holds); the host primitive you build it on is yours — and a port MUST NOT just delegate `put` to the host's `assoc-in`, which violates the root-path law at `[]`.
- The `CEDN-1` byte details (UTF-8 token stream, the specific type-tag letters) are the reference encoding. A port MAY store a normalized projection or a digest instead — but equality-sensitive comparison MUST be equivalent to comparing `CEDN-1` bytes, so the encoding is effectively pattern-required even though the storage form is a choice.

**What's pattern-required.** One path algebra + one canonical identity, inherited by every consumer — no private per-subsystem overlap / canonicalization / round-trip logic, and internal-first (no facade export until two-plus consumers force a public name). The full pattern-required list (segment domain, root-path law, symmetric `overlap?`, `[:rf.path/param …]` templates, fail-closed CEDN-1 identity, scoped resource keys, canonical route emission) lives in [`phase-2-impl-order.md` §The shared path + identity foundation (EP-0012)](phase-2-impl-order.md#the-shared-path--identity-foundation-ep-0012) — don't re-derive it here.

### EP 001 — Registration (`core/src/re_frame/registrar.cljc`)

**What you'll find.** A single atom holding a nested map: `{:event {:id metadata} :sub {...} ...}`. `register-kind` / `unregister-kind` / `query-kind` operate on the atom. Re-registration replaces atomically and emits the trace event.

**What's CLJS-specific.**

- The atom + `swap!` model. Your host's mutable cell of choice (a Ref, a RefCell, a StateFlow, a class instance) does the same job.
- Source-coord capture via macros. The CLJS reference's `reg-event` (the one public event registrar, EP-0018) is a macro that records `:ns`/`:line`/`:file` at compile time. Hosts without macros use stack frames, build-time codegen, or omit.

**What's pattern-required.** The registry is data, queryable via the public API. The `(kind, id) → metadata` shape is the contract; the storage mechanism is yours.

### EP 002 — Frames + events + effects + subs (`core/src/re_frame/{frame,events,fx,cofx,subs,router}.cljc`)

**What you'll find.** A frame is a deftype wrapping **one** `r/atom` (Reagent ratom) for the `frame-state` container value — the two-partition map `{:rf.db/app <app-db> :rf.db/runtime <runtime-db>}` (EP-0001) — plus mutable fields for the queue and sub-cache. `app-db` and `runtime-db` are projection reactions (`r/reaction`) layered over that one container, not separate cells; the projection equality gives partition-aware sub-cache invalidation for free (a runtime-only commit leaves the app-db projection `identical?`, so app subs don't recompute). The event handler chain is implemented as **interceptors** — chained transforms over a context map, executed in `:before` and reversed in `:after` order. The dispatch loop runs the interceptor chain to completion before dequeuing the next event. The router (`router.cljc`) stamps the flat `:rf.cofx` recordable-coeffect map (with `:rf/time-ms`) onto the envelope at the causal boundary (EP-0010 recording / EP-0017 authoring) — reading the clock **once**, preserving a caller-supplied map verbatim, and stamping each `:dispatch` / `:dispatch-later` child token **fresh** — and `cofx.cljc` delivers a handler's declared (`:rf.cofx/requires`) coeffects flat (value-returning graded `reg-cofx`; `inject-cofx` hard-errors), re-presenting recordable values on replay instead of re-reading the host.

**What's CLJS-specific.**

- Interceptors as the implementation strategy for the six-step pipeline. They're an internal implementation detail per [`spec/Cross-Spec-Interactions.md`](https://day8.github.io/re-frame2/spec/Cross-Spec-Interactions/); your port can use a different mechanism (a monadic computation, a coroutine, a state machine over the dispatch envelope) so long as the observable contract from EP 002 is preserved.
- Reagent ratom + auto-tracked subscriptions. Your reactive substrate (D2 / F3) decides how this works.
- `defmulti` for fx resolution. A simple lookup table works too.
- The clock read for `:rf/time-ms` is `(.getTime (js/Date.))`. Your host reads its own wall clock — the *contract* is that the read happens once at the causal boundary and lands on the envelope's flat `:rf.cofx` map under `:rf/time-ms`, not that it's `js/Date`.

**What's pattern-required.** Frame as `{frame-state, queue, sub-cache, id}`, where `frame-state` is the two-partition container value `{:rf.db/app <app-db> :rf.db/runtime <runtime-db>}` with app-db and runtime-db as read-only derived projections (one physical container, two projection reactions — pattern contract per [`spec/002-Frames.md` §One physical container, two projection reactions](https://day8.github.io/re-frame2/spec/002-Frames/), not just the reference's choice); event handler is pure `(app-db, event) → effects-map` returning `#{:db :rf.db/runtime :fx}` (ordinary app handlers emit only `:db` + `:fx`); closed effect-map; an ordinary `:db` effect replaces only app-db; run-to-completion drain per frame; subscription cache invalidates by value-equality, partition-aware. The causal-world-input contract is pattern-required too (EP-0010 recording / EP-0017 authoring): a flat `:rf.cofx` map (required `:rf/time-ms`) guaranteed on every envelope, stamped once at the causal boundary and unconditionally (prod + dev), preserved verbatim when the caller supplies it, stamped fresh per child token (no `:rf/time-ms` inheritance), delivered as a handler's **declared** (`:rf.cofx/requires`) coeffects flat alongside `:db` / `:event` — only the declared facts arrive, with recordable coeffects whose captured value replay returns; durable writes read world facts from the recorded map, never the host ambiently; `:dispatched-at` is retired. See [`phase-2-impl-order.md` EP 002 §The world-input contract](phase-2-impl-order.md#the-world-input-contract-ep-0010).

### EP 006 — Reactive substrate (`core/src/re_frame/substrate/` + `adapters/`)

**What you'll find.** The substrate contract is defined in-core at `core/src/re_frame/substrate/adapter.cljc`, with a dependency-free reference substrate alongside it at `core/src/re_frame/substrate/plain_atom.cljc` — `clojure.core/atom`, hand-rolled signal graph, no render trigger (JVM / SSR / headless). The React-binding adapters then ship as sibling artefacts under `adapters/` — `reagent`, `reagent-slim`, `uix`, `helix` (plus `test-react` for the test harness). The Reagent-family adapters are browser-facing — `r/atom` as the container, Reagent `r/reaction` for subs, React for the render trigger. The in-core plain-atom substrate and every adapter implement the same ten-entry contract. **Lifecycle wiring is the core's public `install-adapter!` / `destroy-adapter!` pair** — `install-adapter!` binds the adapter at boot; `destroy-adapter!` tears it down and calls the adapter spec's internal `:dispose-adapter!` slot (if present). Keep the two distinct: `install-adapter!` / `destroy-adapter!` are the public core lifecycle verbs (per [`spec/API.md`](https://day8.github.io/re-frame2/spec/API/)); `:dispose-adapter!` is the **adapter-spec map key** the adapter implements (the lifecycle slot `destroy-adapter!` invokes), not a public function.

**What's CLJS-specific.**

- Reagent's auto-tracked deref-during-render dependency capture. Your host's React binding supplies the equivalent over its `useSyncExternalStore` (UIx / Helix: a `use-subscribe` hook; TS-React / Fable.React / Feliz / ReasonReact / Halogen-React / Kotlin-React: the same pattern).
- The component-lifecycle integration uses Reagent's lifecycle methods. Other substrates plug into their own.

**What's pattern-required.** The six required functions (`make-state-container`, `read-container`, `replace-container!`, `make-derived-value`, `render`, `render-to-string` — note `render-to-string` is required, JVM-runnable, even for no-SSR ports) + three optional (`subscribe-container`, `register-context-provider`, `flush-render!`) + one lifecycle slot (the adapter-spec map's internal `:dispose-adapter!`); install/teardown via the core's public `install-adapter!` / `destroy-adapter!` lifecycle pair (the public verb is `destroy-adapter!` — `:dispose-adapter!` is the adapter map's internal slot it calls, not a public function). One adapter bound at boot — the boot-bound adapter selection, no realm/container layer wrapping it (there is no "multi-adapter coexistence is N realms" model; image assembly plus frame isolation are the whole composition story). Adapter-internal state derivable from the frame value (revertibility constraint).

### EP 004 — Views (`core/src/re_frame/views.cljs`)

**What you'll find.** `reg-view` is a macro that wraps a function, captures source coords, and registers the wrapper with the registrar. The wrapper plugs into Reagent's component model. Plain Reagent functions (not registered via `reg-view`) still work — they bypass the registry and the frame-propagation contract.

**What's CLJS-specific.**

- Hiccup as the render-tree shape. Your render-tree is yours.
- The macro-based source-coord capture (same constraint as EP 001).
- Frame propagation via Reagent component context.

**What's pattern-required.** Pure `(state, props) → render-tree`. Render-tree is serialisable data. `reg-view` is the registry-aware entry point. Frame propagation is supported.

### EP 009 — Instrumentation (`core/src/re_frame/{trace,emit,error}.cljc`)

**What you'll find.** Two distinct surfaces with opposite production postures, both reached through the one public **stream-parameterized listener verb** `register-listener! stream id f` (streams `:trace` / `:events` / `:errors` / `:epoch`; the obsolete `register-(event|error)-listener!` facade pairs were collapsed into it). The **dev trace surface** (`:trace`) — a listener registry + retain-N ring buffer in `trace.cljc`, the rich dev emit sites in `emit.cljc` — is gated behind `re-frame.interop/debug-enabled?` and DCEs out of production. The **always-on emit substrates** (`:events` / `:errors`, internally `re-frame.event-emit` / `re-frame.error-emit`) ride a *separate, ungated* path that survives `:advanced` + `goog.DEBUG=false`; they fire tight fixed-shape records post-elision (`re-frame.elision/elide-wire-value` substitutes sentinels before fan-out) with per-listener exception isolation, fanned across EVERY frame UNPROJECTED — the ADVANCED corpus-wide hook, NOT the off-box default (that is the frame-owned `:observability` sink via `register-observability-sink!`, EP 015). The structured error contract lives in `error.cljc`; an `:rf.error/*` event flows on BOTH surfaces. Recovery is the framework's typed per-category default — there is no app-steering recovery policy. A CI script (`implementation/scripts/check-elision.cjs`) scans production bundles for dev-only sentinel strings; the build fails if any are found.

**What's CLJS-specific.**

- Closure DCE for **dev-trace-surface** production elision. JS / TS and Squint use Vite's `define` constants and tree-shaking; Fable uses `#if !DEBUG` + tree-shake; Scala.js uses link-time-`if`; Kotlin/JS uses release-variant module omission. (The always-on substrates are NOT elided by any of these — they sit outside the gate.)
- The sentinel-string CI verifier is portable — copy the pattern, adapt to your bundler — and add a positive assertion that the always-on substrates survive the production bundle.
- Chrome Performance API bridge (`performance.mark` / `performance.measure`). Every in-scope host targets the browser, so the same Performance API is uniformly available; the bridge itself is optional (the trace surface is the contract).

**What's pattern-required.** One **stream-parameterized listener verb** (`register-listener!` / `unregister-listener!` / `clear-listeners!`, closed streams `:trace` / `:events` / `:errors` / `:epoch`). **Dev trace surface** (`:trace`, production-elided): synchronous + in-order + per-emit trace stream + retain-N ring buffer. **Always-on emit substrates** (`:events` one tight record per event / `:errors` one record per `:rf.error/*` cascade error, production-survivable) — these MUST keep firing under production elision, deliver identical record shapes dev/prod, run post-elision, fan UNPROJECTED across EVERY frame, and isolate per-listener exceptions. They are the ADVANCED corpus-wide hook; the **normal off-box production path** (hosted observability, production error reporting, SSR fail-closed) is the frame-owned `:observability` sink (`register-observability-sink!`), consuming already-PROJECTED records. Structured error contract with `:operation :rf.error/<category>` flows on both; recovery is the framework's typed per-category default (no app-steering recovery policy).

### EP 015 — Data Classification (`core/src/re_frame/{frame_classification,projection,elision,observability}.cljc`)

**What you'll find.** `frame_classification.cljc` installs frame-owned durable app-db classification — `reg-frame`'s `:sensitive {:app-db […] :http {…}}` / `:large {:app-db […]}` declarations land in the reserved `[:rf.runtime/elision]` runtime-db child under `:source :frame` (atomically at frame creation, before `:initial-events` run). `projection.cljc` holds `project-egress` — the one public record-level boundary primitive every off-box sink routes through; it dispatches on a record's `:kind` to a private per-kind projector and delegates tree-shaped slots to `elision.cljc`'s `elide-wire-value` walker, which substitutes the Spec 009 wire markers `:rf/redacted` (sensitive) / `:rf.size/large-elided {:bytes N …}` (large) at classified paths. (`:rf/large {:bytes N :head}` / `:rf/redacted {:bytes N}` are the Spec 015 *display* renderings a tool shows for those elided values — a layer above the wire, not what the walker writes.) `observability.cljc` routes handled-event / error records through `project-egress` to frame-declared sinks. Real values flow through the runtime unchanged; substitution happens only at projection time. (`marks.cljc` survives as an internal/test helper — the public `add-marks` / `set-marks` surface was removed by EP-0015; frame config replaces it.)

**What's CLJS-specific.**

- Runtime-db registry storage + projection-time path-graph union for propagation. Your host may store classification differently and may use write-time taint-tracking instead of a projection-time union — both conform.
- `get-in` / `assoc-in` path grain for the path vocabulary. Your port uses its own path-access primitive (D4 S3).

**What's pattern-required.** v1-required (not optional). Opt-in, owner-owned, two parallel axes (`:sensitive` / `:large`) — one declaration surface per owner, never two for the same shape. The four owners: (1) **frame config** — `reg-frame` carries `:sensitive {:app-db […] :http {…}}` / `:large {:app-db […]}` for durable app-db, frame-local HTTP carriers, and `:observability` sink policy (re-registration REPLACES; malformed paths fail at registration); (2) **schema props** for owner-local schema'd data — machine `:data` is the **schema-first exception** (`reg-machine` is `(machine-id machine-spec)` / `(machine-id opts machine-spec)`, the optional `opts` carrying an event-vector `:schema` and NO top-level `:sensitive` / `:large` keys; `:data` slots classify via `:sensitive?` / `:large?` props on the `:data-schema`, rooted under `[:data …]`), and the same per-slot mechanism covers resource `:data-schema` / `:params-schema` and HTTP `:decode` bodies (Spec 015 §Machine-owned / §Resource / Spec 005 §Privacy); (3) **registration metadata** — `reg-event` (the one public event registrar, EP-0018) / `reg-sub` / `reg-fx` / `reg-cofx` / `reg-flow` accept `{:sensitive [paths] :large [paths]}` indexing into that registration's primary shape; (4) **derived-output declassification** via `:rf.egress/output-sensitivity` (`:rf.egress/inherit` / `:rf.egress/sensitive` / `:rf.egress/public`), NOT a `:sensitive false` boolean. Classification propagates (footgun prevention, not security-grade taint) across the framework-known dataflow. Every off-box record routes through `project-egress` under the owning frame's classification + one of the closed six `:rf.egress/*` profiles — sensitive wins over large; no classified value crosses the trust boundary in dev OR production; routing/projection are fail-closed (no `:rf/default` synthesis). Handlers / sub-fns / fx-handlers ALWAYS see real values.

## Walk by optional artefact

### `epoch/`

EP 009's optional time-travel layer. A frame-state ring buffer keyed by event id; `epoch-history` and `restore-epoch` are the public API. Useful pattern; copy the shape if your port wants time-travel.

### `flows/`

EP 013 implementation. Substrate-independent; the contract lives in 013.

### `http/`

EP 014 implementation + the managed-HTTP pattern. The Spec 014 framework surface is the **`:rf.http/managed`** fx (plus `:rf.http/managed-abort`) — it wraps a request lifecycle through a registered state machine; that is the canonical optional framework effect id conformance / tooling / `:fx-overrides` key off. Lower-level bare `:http` is NOT a reserved framework fx — it is app/user/implementation-specific (registered via the app's own `reg-fx`), so don't describe the managed lifecycle as "the `:http` fx". Substantial — read `014-HTTPRequests.md` (and `Managed-Effects.md` for the managed-fx lifecycle it rides on) first.

### `machines/`

EP 005 implementation. Largest non-core artefact. The transition machine, drain extensions for `:always` / `:after`, the `:spawn` contract for child-machine spawning. The CLJS reference uses spec multi-methods + a hand-rolled drain loop.

### `routing/`

EP 012 implementation. Hand-rolled URL matcher with a six-rule precedence cascade; not a third-party routing library. The routing registry plugs into EP 001's registrar — `(registrations :route)` is queryable.

### `schemas/`

EP 010 implementation. Malli is the wire layer; `reg-app-schema` and `:schema` metadata are the public API (the metadata key is `:schema` — v1's `:spec` was renamed with no back-compat alias). Replace Malli with the host's mechanism per D5 — Zod for the dynamically-typed in-scope hosts (TS / Squint), or the host's own type system for the statically-typed ones.

### `ssr/`

EP 011 implementation. Pure hiccup → HTML emitter (~200 lines) for the server side; `reagent.dom.client/hydrate` for the client side. The `:platforms` metadata fx-gating is in `core/`, not here — only the render-to-string and hydration helpers live here.

## What this tour deliberately doesn't tell you

- **Which mechanism is correct for your host.** That's Phase 1's job (`phase-1-decisions.md`). The tour names what the CLJS reference did; your port may diverge on every single point and still be a conformant re-frame2 implementation.
- **How to read the source line-by-line.** This is a map, not a transcription. When the leaf says "the dispatch loop runs the interceptor chain to completion before dequeuing the next event," it doesn't tell you which function in `router.cljc` to read. That's a follow-up: open the file, find the function (it's a fairly small file), read the top-level loop. The tour orients; the source reveals.
- **What the spec mandates.** That's `spec/`'s job. If anything in the tour reads as a requirement, that's tour-rhetoric leaking. Test every "the reference does X" against `spec/` before committing it to your port's design.

## When to consult the tour

- **Phase 1.** As a sanity check on Phase 1 decisions — "the CLJS reference picked X for F5; I'm picking Y because my host gives me Z." The tour grounds the choice.
- **Phase 2, per EP.** As a starting point for "where would I look to see how to handle the awkward case in EP N?" Open the matching directory above; read the corresponding source file.
- **Spec gaps.** When a spec section is ambiguous and the tour shows the reference made a specific choice — that's not the spec's choice, that's the reference's. File it as a GitHub issue per [`cardinal-rules.md` §§8–9](cardinal-rules.md).

## When NOT to consult the tour

- **As a contract.** Never. The contract is `spec/`. The tour is "one worked example."
- **As a copy-paste source.** Translating Clojure macros into TS classes line-by-line produces brittle code. Read the contract, design from the contract, then *maybe* peek at the reference for an awkward edge case.
- **As a teaching resource.** The narrative guide at [`docs/guide/`](https://day8.github.io/re-frame2/guide/README/) is the teaching resource. The tour assumes you've read the spec.
