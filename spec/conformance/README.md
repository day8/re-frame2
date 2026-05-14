# Conformance Corpus

> **Type:** Reference
> The conformance test suite for re-frame2 implementations. The corpus is the verification mechanism for [Goal 2 — AI-implementable from the spec alone](../000-Vision.md#ai-implementable-from-the-spec-alone): the claim that the spec is sufficiently complete for an AI armed only with `/spec/` + this corpus to produce a working reference implementation in any host language. A spec gap surfaces as a fixture an AI cannot reproduce without consulting outside sources; closing the gap is a spec-level remediation, not an implementation-level one.

## Naming convention

Fixture filenames mirror their `:fixture/id` exactly, with the slash that separates the namespace from the local name rewritten as a hyphen and the rest of the id preserved. A fixture with `:fixture/id :counter/inc-once` lives in `counter-inc-once.edn`. Both filename and id use kebab-case; no underscores.

## What this is

A set of **fixture files** in EDN format, each describing one canonical interaction. Two complementary fixture shapes cover the spec (full detail in [§Fixture format](#fixture-format)):

- **Dispatch-driven fixtures** (Mode A) — a frame configuration plus initial `app-db`, a sequence of dispatched events, and the expected emissions after drain: final `app-db`, trace events, effects routed to fx, return values from subscriptions.
- **Pure / direct-call fixtures** (Mode B) — direct invocations of pure primitives (machine transitions, URL ↔ params helpers, hiccup → HTML rendering) with call-local expectations. No frame, no dispatch loop; JVM-runnable.

An implementation conforms if it produces matching emissions for every fixture in the corpus whose capabilities are a subset of the port's claimed list.

## Why EDN

EDN is the natural data format for the CLJS reference. For other-host implementations:

- A small EDN reader exists in nearly every host language (or can be written in ~200 lines for any language with hash-maps and vectors).
- The structure is host-language-agnostic — keywords map to namespaced strings or branded enums per the host's identity primitive.
- The corpus is itself **machine-readable data** — implementers in a new language read the corpus, generate host-native test code, and report.

A JSON-translated corpus may be published in the future for hosts where EDN is too much friction; if and when it ships, the JSON form will be mechanically derived from the EDN source so the two cannot drift. Until then, the EDN files in `fixtures/` are the canonical source. Implementors targeting non-EDN hosts either ship a small EDN reader (~200 lines for any host with hash-maps and vectors) or translate the corpus locally as part of harness bootstrap.

## Fixture format

Each fixture is an EDN map. A fixture exercises the spec in **one of two modes**:

- **Mode A — dispatch-driven.** A frame is created, a sequence of events is dispatched, and the harness compares the post-drain observables (`app-db`, sub values, trace emissions, effects routed) against a single top-level `:fixture/expect`. Use this mode for event/sub/fx/trace/error semantics that only make sense inside a running frame. Most fixtures in the corpus use this mode.
- **Mode B — pure / direct-call.** No frame, no dispatch loop. The fixture lists one or more direct calls to a re-frame primitive (e.g. `machine-transition`, `match-url`, `route-url`, `render-to-string`) and each call carries its **own** expectation inline. Use this mode for primitives that are pure functions of their inputs — machine transitions, URL ↔ params helpers, hiccup → HTML rendering. JVM-runnable; nothing about the substrate's wiring is exercised.

A fixture chooses one mode; the runner executes whichever of `:fixture/dispatches` and `:fixture/calls` is present. (A few fixtures may include calls after a dispatch sequence to assert pure-function output against the post-drain registry; that is still Mode A — the dispatches are the load-bearing part and `:fixture/expect` is the primary contract.)

### Mode A — dispatch-driven

The classic shape: a starting state (frame configuration plus initial `app-db`), a sequence of events, and one top-level expectation block.

```clojure
{:fixture/id           :counter/inc-once
 :fixture/doc          "Single increment of the counter."
 :fixture/capabilities #{:core/event-handler :core/sub}                ;; per §Capability tagging below — see also §Capability tagging worked example
 :fixture/registry    {:event {:counter/initialise {:doc "Seed."}
                               :counter/inc        {:doc "Increment."}}
                       :sub   {:count             {:doc "Current count."}}
                       :fx    {}}
 :fixture/handlers    {:event {:counter/initialise [[:set [:count] 0]]
                               :counter/inc        [[:update [:count] [:fn :inc]]]}
                       :sub   {:count [[:get [:count]]]}}
 :fixture/frame-config {:on-create [:counter/initialise]}
 :fixture/dispatches   [[:counter/inc]]
 :fixture/expect
 {:final-app-db        {:count 1}
  :sub-values          {[:count] 1}
  :trace-emissions     [{:operation :event :tags {:event-id :counter/inc}}
                        {:operation :event/do-fx :tags {}}]
  :effects-routed      []}}
```

The expectation keys inside `:fixture/expect` are partial-match by convention: `:trace-emissions` matches each trace event by its specified keys (absent keys ignored), `:final-app-db` is a literal compare, `:effects-routed` matches the routed-fx pairs in declaration order. See [§Fixture lifecycle](#fixture-lifecycle) for the full comparison contract.

### Mode B — pure / direct-call

For pure primitives — machine transitions, URL helpers, render-to-string — the fixture skips the frame entirely. `:fixture/calls` is a vector of call records; each record names the primitive in `:call`, supplies its arguments, and carries its **own** expectation alongside (typically `:expect`, or operation-specific keys like `:expect-next-snapshot` + `:expect-effects` for `:machine-transition`).

```clojure
;; Excerpt — full file at fixtures/machine-transition.edn
{:fixture/id           :rf.machine/transition
 :fixture/capabilities #{:fsm/flat}
 :fixture/doc          "Pure machine-transition. Given a definition and snapshot, applying an event yields the next snapshot."

 :fixture/registry
 {:machine-action
  {:traffic-light/log-yellow {:doc "Logs a state transition."}
   :traffic-light/log-red    {:doc "Logs a state transition."}
   :traffic-light/log-green  {:doc "Logs a state transition."}}}

 :fixture/handlers
 {:machine-action
  {:traffic-light/log-yellow [[:fx :log {:level :info :msg "yellow"}]]
   :traffic-light/log-red    [[:fx :log {:level :info :msg "red"}]]
   :traffic-light/log-green  [[:fx :log {:level :info :msg "green"}]]}}

 :fixture/calls
 [{:call                 :machine-transition
   :definition           {:initial :green
                          :data    {}
                          :states  {:green  {:on {:tick {:target :yellow
                                                         :action :traffic-light/log-yellow}}}
                                    :yellow {:on {:tick {:target :red
                                                         :action :traffic-light/log-red}}}
                                    :red    {:on {:tick {:target :green
                                                         :action :traffic-light/log-green}}}}}
   :snapshot             {:state :green :data {}}
   :event                [:tick]
   :expect-next-snapshot {:state :yellow :data {}}
   :expect-effects       [[:log {:level :info :msg "yellow"}]]}

  ;; Unknown event in current state: snapshot unchanged, no effects.
  {:call                 :machine-transition
   :definition           {:initial :green
                          :data    {}
                          :states  {:green {:on {:tick {:target :yellow
                                                        :action :traffic-light/log-yellow}}}}}
   :snapshot             {:state :green :data {}}
   :event                [:emergency-stop]
   :expect-next-snapshot {:state :green :data {}}
   :expect-effects       []}]}
```

Routing and SSR pure-call fixtures use the same shape with different `:call` ops:

```clojure
;; Excerpt — full file at fixtures/routing-match-url.edn
{:fixture/id           :routing/match-url
 :fixture/capabilities #{:routing/match-url}

 :fixture/registry
 {:route
  {:route/article-detail {:path "/articles/:id" :params [:map [:id :string]]}
   :route/search         {:path  "/search"
                          :query [:map [:q :string] [:page {:optional true} :int]]}}}

 :fixture/calls
 [{:call :match-url :url "/articles/intro"
   :expect {:route-id :route/article-detail :params {:id "intro"} :query {} :validation-failed? false}}

  {:call :route-url :route-id :route/article-detail :params {:id "intro"}
   :expect "/articles/intro"}

  ;; Round-trip property: route-url ∘ match-url is identity.
  {:call :round-trip :url "/articles/intro"}]}
```

The reserved `:call` operators currently used by the corpus are `:machine-transition`, `:match-url`, `:route-url`, `:round-trip`, `:assert-rank-greater`, and `:render-to-string`. The set is additive — new pure primitives may register a new `:call` op in subsequent fixture spec versions; existing ops cannot be redefined.

### Capability tagging

Per [Goal 6 — Hierarchical FSM substrate with implementor-chosen capabilities](../000-Vision.md#hierarchical-fsm-substrate-with-implementor-chosen-capabilities) and [005 §Capability matrix](../005-StateMachines.md#capability-matrix), conformance is **graded against each port's claimed capability list** rather than all-or-nothing. Every fixture self-declares which capabilities it exercises via `:fixture/capabilities`. The harness only runs fixtures whose capability set is a subset of the port's claimed list; un-runnable fixtures are reported as "not exercised" rather than "failed."

Capability tag conventions:

- `:core/*` — pattern-required basics every conformant port supports (event handler, frame, dispatch envelope, sub, trace, fx, error).
- `:fsm/*` — FSM-richness axis (`:fsm/flat`, `:fsm/hierarchical`, `:fsm/eventless-always`, `:fsm/delayed-after`, `:fsm/tags`, `:fsm/parallel-regions`).
- `:actor/*` — actor-model axis (`:actor/own-state`, `:actor/spawn-destroy`, `:actor/cross-actor-fx`, `:actor/invoke`, `:actor/spawn-and-join`, `:actor/system-id`).
- `:routing/*`, `:ssr/*`, `:schemas/*` — per-spec capabilities for ports that ship them.

A flat-FSM-only port declares `:capabilities #{:core/event-handler ... :fsm/flat :actor/own-state :actor/spawn-destroy ...}` in its harness manifest; the corpus runs every fixture whose capabilities are a subset and skips the rest. The aggregate score is `passed / claimed-applicable` — an accounting of what works for the claimed list.

A port's harness MUST distinguish two flavours of out-of-claim capability so silent rot can't mask coverage gaps (per `rf2-a3q1r`):

- **Intentional out-of-claim** — the port's `claimed-capabilities` deliberately excludes the capability (e.g. a flat-FSM-only port skipping `:fsm/hierarchical`). The harness MAINTAINS an explicit `known-skipped-capabilities` allowlist; capabilities in this set produce "skipped (out-of-claim)" reports without failing the suite.
- **Typo / claim-set drift** — a fixture's capability appears in neither the claimed set nor the allowlist. The pre-rf2-a3q1r harness silently skipped these; the post-rf2-a3q1r contract is that the suite FAILS with a diagnostic naming the unknown capability. The remedy is either to add the capability to `claimed-capabilities` (with the runtime backing to match) or to add it to `known-skipped-capabilities` (an explicit decision not to claim it).

The reference harness (`implementation/core/test/re_frame/conformance_test.clj` and `implementation/core/test/re_frame/conformance_corpus_cljs_test.cljs`) keeps `known-skipped-capabilities` as an empty set today: every capability referenced by a corpus fixture is also claimed. The allowlist exists so that any future divergence requires an explicit decision rather than silent drift.

See [§Capability tagging worked example](#capability-tagging-worked-example) immediately below for the actual tags carried by representative fixtures in the corpus today.

### Capability tagging worked example

The conventions above describe the schema; the corpus itself shows what those tags look like in practice. The following five fixtures are pulled verbatim from `fixtures/` — together they span the main capability axes (core, FSM hierarchy, actor model, flow tracing, managed HTTP). An AI port author landing fresh can use these as a tag-vocabulary reference rather than inventing names.

| Fixture | `:fixture/capabilities` | What the tag set means |
|---|---|---|
| `counter-inc-once.edn` | `#{:core/event-handler :core/sub}` | The simplest pattern-required-only fixture: a single event handler, one sub. Every conformant port runs this. |
| `frame-lifecycle.edn` | `#{:core/event-handler :core/frame :core/trace}` | The default frame's `:on-create` / `:on-destroy` events fire at frame creation and destruction; the runtime emits `:frame/created` / `:frame/destroyed` trace ops. Verifies both the frame-lifecycle contract and the trace-bus emission. |
| `frame-multi-instance.edn` | `#{:core/event-handler :core/sub :core/frame :core/trace}` | Two frames sharing one registrar with isolated app-db; each trace event carries a per-frame `:frame` tag so the bus is multi-frame addressable. |
| `error-handler-exception.edn` | `#{:core/event-handler :core/error :core/trace}` | A handler throws; the runtime emits a structured `:rf.error/handler-exception` trace with `:op-type :error` and `:recovery :no-recovery`. The trace shape is the primary contract. |
| `after-hierarchy.edn` | `#{:fsm/hierarchical :fsm/delayed-after}` | A parent compound state with an `:after` timer. Only ports that claim both hierarchical FSM and `:after` will run it. |
| `spawn-from-action.edn` | `#{:fsm/flat :actor/spawn-destroy}` | Imperative spawn of a child actor from inside a transition action. Requires the actor-model spawn-destroy capability on top of flat FSMs. |
| `flow-lifecycle-emits-traces.edn` | `#{:core/event-handler :flow/basic :flow/trace}` | The flow primitive emits its five lifecycle trace events. Requires the flow substrate and the flow-trace stream. |
| `http-managed-get-success.edn` | `#{:core/event-handler :core/sub :core/fx :rf.http/managed}` | The managed-HTTP fx happy path. Builds on the core triad and adds the managed-HTTP capability from [Spec 014](../014-HTTPRequests.md). |

Read the chosen tag namespaces from the [conventions list above](#capability-tagging) and refer back to this table when authoring a new fixture or a port's harness manifest: copy the tag from the closest existing fixture rather than coining a new one.

### Handler bodies as data

Since the corpus is host-agnostic, handler bodies can't be CLJS lambdas — they must be data the host realises into native closures.

The convention:

```clojure
;; A handler body is a small DSL the corpus harness interprets.
{:counter/initialise [[:set [:count] 0]]                      ;; sets db's :count to 0
 :counter/inc        [[:update [:count] [:fn :inc]]]}         ;; updates db's :count via :inc
```

The harness in each host implements a small interpreter for this DSL — `[:set path value]`, `[:update path fn]`, `[:dispatch event]`, etc. A complete interpreter is ~50 lines per host. The DSL is itself versioned and described by `:rf/fixture-handler-body` in [Spec-Schemas](../Spec-Schemas.md#rffixture-file).

This keeps the corpus pure data; no host-specific code ships in the fixtures.

### Handler-body DSL ops

The complete DSL operator set. The schemas live in [Spec-Schemas §`:rf/fixture-handler-body`](../Spec-Schemas.md#rffixture-file); below is the operator reference.

**Data ops** (mutate or read `db`):

| Op | Signature | Meaning |
|---|---|---|
| `[:set path value]` | path = vector of keywords | `assoc-in` `db` at `path` with the literal `value` (resolved via the value DSL below). |
| `[:update path fn-form]` | path = vector; `fn-form` = `[:fn ...]` | `update-in` `db` at `path` applying `fn-form`. |
| `[:get path]` | path = vector | (Sub bodies) read `db` at `path` and return the value. |
| `[:reduce-input sub-id reduce-fn-form map-fn-form]` | ids/fn-forms | (Sub bodies) compute `(reduce reduce-fn (map map-fn input-sub-value))`. |

**Effect ops** (emit fx):

| Op | Signature | Meaning |
|---|---|---|
| `[:fx fx-id args]` | id, args | Emit a single fx as if returned in `:fx`. |
| `[:fx [[fx-id args] [fx-id args] ...]]` | vector of pairs | Emit multiple fx in declaration order. |
| `[:dispatch event-vec]` | event vector | Convenience for `[:fx :dispatch event-vec]`. |

**Control / failure ops:**

| Op | Signature | Meaning |
|---|---|---|
| `[:throw "message"]` | string | Throw a host-native exception with the given message. Used by error fixtures. |
| `[:noop]` | — | Explicit no-op; useful for default fx registrations the fixture overrides. |

**Reflection / value ops** (used as arguments to data ops; not standalone steps):

| Form | Meaning |
|---|---|
| `[:event-arg n]` | The n-th element of the event vector (0-based). `[:event-arg 1]` is typical for the first user-supplied arg. |
| `[:event-arg n default-val]` | The n-th element of the event vector; `default-val` if the n-th element is `nil`. The 3rd element is **always** a default-for-nil — it is never type-dispatched, even when it's a keyword and the resolved value happens to be a map. For key-access into a map argument, use `:get-event-arg`. |
| `[:get-event-arg n :key]` | `:key`-access into the n-th element of the event vector — equivalent to `(get (nth event n) :key)`. The n-th element is expected to be a map. |
| `[:get-event-arg n :key default-val]` | Same as above with a default if `:key` is missing or its value is `nil`. |
| `[:fn :keyword]` | Reference a host-builtin function by keyword. |
| `[:fn :keyword arg1 arg2 ...]` | Partial application: `[:fn :conj :should-not-fire]` is `(fn [x] (conj x :should-not-fire))`. |

#### Handler-body DSL builtins

The reserved set of `:fn` builtins each host implements. The corpus uses only registered builtins.

| Builtin | Arity | Maps to |
|---|---|---|
| `:inc` | 1 | numeric increment |
| `:dec` | 1 | numeric decrement |
| `:+`, `:-`, `:*`, `:/` | 2+ | arithmetic |
| `:identity` | 1 | identity |
| `:conj` | 1 (with partial second arg) | append-to-collection |
| `:assoc` | 2 (with partial keys/values) | map assoc |
| `:dissoc` | 1 (with partial keys) | map dissoc |
| `:item-amount` | 1 | `(* (:qty item) (:price item))` — used by `sub-chain.edn` |
| `:count` | 1 | collection count |

Implementations register each builtin by name during harness bootstrap. The set is stable and additive — new builtins may be added in subsequent fixture spec versions; existing builtins cannot be redefined.

### Fixture lifecycle

Each fixture defines an **invariant the implementation upholds**. The harness:

1. **Bootstraps the registrar** — for each kind in `:fixture/registry`, register every id with the supplied metadata.
2. **Realises handler bodies** — for each `:fixture/handlers` entry, interpret the DSL ops into a host-native closure and bind it to the id under the kind.
3. **Creates the frame** — apply `:fixture/frame-config` via `make-frame` (or the host equivalent); this fires `:on-create` and any `:on-create` events seeded into the frame.
4. **Runs `:fixture/dispatches`** — one event vector per call, each via `dispatch-sync`. Each settles to fixed point before the next.
5. **Runs `:fixture/calls`** (if present) — direct invocations of pure primitives (`machine-transition`, `match-url`, `route-url`, `render-to-string`, `round-trip`, `assert-rank-greater`). Each call carries its own expectation; mismatches surface as fixture-level failures.
6. **Captures observables** (Mode A) — final `app-db`, sub values (per `:fixture/expect :sub-values`), trace events emitted, effects routed.
7. **Compares** (Mode A) — partial-match per assertion. `:trace-emissions` partial-matches each trace event by its specified keys; absent keys are ignored. `:final-app-db` is a literal compare. `:effects-routed` matches the routed-fx pairs in declaration order.

For Mode B fixtures, comparison happens inline at each call; there is no top-level `:fixture/expect` to evaluate after drain.

The harness reports per-fixture pass/fail; aggregate score is the count of passing fixtures over total fixtures.

Each fixture is **a single file** of <200 lines including registrations and expectations. Adding a fixture is a small focused change.

## How an implementation runs the corpus

1. Read all `.edn` files in `fixtures/`.
2. For each fixture:
   a. Bootstrap the host's runtime with the fixture's registry.
   b. Realise handler bodies via the DSL interpreter.
   c. **If `:fixture/dispatches` is present (Mode A):**
      - Create a frame per `:fixture/frame-config`.
      - Run each dispatch.
      - After drain, capture: final `app-db`, sub values, emitted trace events, effects routed.
      - Compare actuals against `:fixture/expect`.
   d. **If `:fixture/calls` is present (Mode B):**
      - For each call record, invoke the named primitive with the supplied arguments.
      - Compare the result against the call-local expectation (`:expect`, or operation-specific keys like `:expect-next-snapshot` + `:expect-effects`).
3. Report pass/fail per fixture; total conformance score.

The harness is small (~300 lines per host).

## Versioning

The corpus is versioned alongside the spec. Each fixture file declares the spec version it was authored against:

```clojure
{:fixture/id        :counter/inc-once
 :fixture/spec-version "1.0"
 ...}
```

When the spec changes shape (new required key in `:rf/dispatch-envelope`, new error category), affected fixtures bump their `:spec-version` and the corpus's harness check rejects implementations that haven't moved with the spec.

## Fixtures

See `fixtures/` for the actual files. Each fixture is one EDN file; each exercises one shape of the spec.

| Fixture | `:fixture/id` | Coverage |
|---|---|---|
| `counter-inc-once.edn` | `:counter/inc-once` | Trivial event handler; sub computation; trace emission |
| `counter-inc-multi.edn` | `:counter/inc-multi` | Multi-event drain to fixed point; final state visible to subs |
| `frame-multi-instance.edn` | `:frame/multi-instance` | Multi-frame isolation with shared registrar |
| `frame-lifecycle.edn` | `:frame/lifecycle` | `:on-create` and `:on-destroy` events; lifecycle trace emissions |
| `dispatch-envelope.edn` | `:dispatch/envelope` | Envelope shape (`:event`, `:frame`, `:source`, `:trace-id`) surfacing in cofx |
| `drain-depth-limit.edn` | `:drain/depth-limit` | Drain-depth-exceeded error + `:rf.error/drain-depth-exceeded` trace |
| `sub-chain.edn` | `:sub/chain` | `:<-` chained subs; static dependency topology |
| `fx-db-first.edn` | `:fx/db-first` | `:db` commits atomically before any `:fx` entry runs; the first `:fx` entry's handler observes the post-`:db` state |
| `fx-ordering-source-order.edn` | `:fx/ordering-source-order` | `:fx` entries process in source order; the dispatched events accumulate in the runtime FIFO in the same order |
| `fx-override-by-id.edn` | `:fx/override-by-id` | Pattern-level id-valued override seam |
| `fx-platforms.edn` | `:fx/platforms` | `:platforms` gating on `reg-fx` for SSR |
| `error-handler-exception.edn` | `:error/handler-exception` | Structured `:rf.error/handler-exception` trace + cascade halt |
| `error-no-such-handler.edn` | `:error/no-such-handler` | Dispatch with no registered handler; cascade continues |
| `error-schema-failure.edn` | `:error/schema-failure` | `:rf.error/schema-validation-failure` (dynamic-host only) |
| `error-fx-handler-exception.edn` | `:error/fx-handler-exception` | `:rf.error/fx-handler-exception` (fx throws during effect resolution) |
| `error-sub-exception.edn` | `:error/sub-exception` | `:rf.error/sub-exception` (sub computation throws) |
| `error-override-fallthrough.edn` | `:error/override-fallthrough` | `:rf.error/override-fallthrough` (override id is not registered) |
| `ssr-hydration-mismatch.edn` | `:ssr/hydration-mismatch` | `:rf.ssr/hydration-mismatch` (server-hash ≠ client-hash) |
| `machine-transition.edn` | `:rf.machine/transition` | Pure machine-transition; canonical grammar (`{:state :data}` snapshot, single-fn `:action` slot) |
| `hierarchical-compound-transition.edn` | `:machine/hierarchical-compound-transition` | Sibling-leaf transition inside a compound; verifies the LCA (parent) does NOT exit/re-enter |
| `hierarchical-cross-level-transition.edn` | `:machine/hierarchical-cross-level-transition` | Deeply-nested leaf to top-level sibling; LCA-based exit cascade fires every intermediate ancestor deepest-first; vector-form `:target`. |
| `hierarchical-parent-fallthrough.edn` | `:machine/hierarchical-parent-fallthrough` | Deepest-wins resolution: event declared on a parent is found via leaf-up walk; leaf-level handler overrides; unknown event leaves snapshot unchanged. |
| `invoke-spawn-on-entry-destroy-on-exit.edn` | `:machine/invoke-spawn-on-entry-destroy-on-exit` | Declarative `:invoke` on a state node desugars at registration time to entry/exit `:rf.machine/spawn` / `:rf.machine/destroy` fx; verifies the spawn fx args, the `:on-spawn` callback updating the parent's `:data`, and the exit destroy fx targeting the recorded child id. |
| `always-single-microstep.edn` | `:machine/always-single-microstep` | Eventless `:always` transition fires once after entry under a guard that just became true; one microstep settles to the target; verifies microstep counter and per-microstep + macrostep trace events; the intermediate "guard now true" state is NOT externally observable (atomic commit). |
| `always-depth-exceeded.edn` | `:machine/always-depth-exceeded` | A cycle of `:always` transitions across two states never settles; the microstep loop hits the configured `:always-depth-limit` (5 in this fixture) and emits `:rf.error/machine-always-depth-exceeded`. Snapshot uncommitted; recovery is `:no-recovery`. |
| `after-single-delay.edn` | `:machine/after-single-delay` | Single `:after` entry fires after the configured delay; verifies `:rf.machine.timer/scheduled` at state entry, `:rf.machine.timer/fired` at expiry, and the snapshot transitions to the target after the test-clock advances past the delay. |
| `after-stale-detection.edn` | `:machine/after-stale-detection` | A real `:on` event arrives before the `:after` timer expires; the machine transitions out of the state; the epoch advances; the eventual timer firing is detected as stale (`:rf.machine.timer/stale-after`) and silently ignored. The "real event beats the timer" race the epoch mechanism handles. |
| `after-hierarchy.edn` | `:machine/after-hierarchy` | `:after` on a parent compound state remains active while the snapshot is in any child; sibling-leaf transitions inside the parent do NOT cancel the parent's `:after`. Exiting the parent advances the epoch and the parent-level timer's eventual firing is stale. |
| `routing-match-url.edn` | `:routing/match-url` | Bidirectional URL ↔ params; round-trip property |
| `routing-navigate.edn` | `:routing/navigate` | `:rf.route/navigate` updates `app-db` + emits `:rf.nav/push-url` fx |
| `route-ranking-precedence.edn` | `:routing/ranking-precedence` | Deterministic 6-rule ranking cascade resolves overlapping routes; equal-score warning |
| `route-stale-nav-token-suppression.edn` | `:routing/stale-nav-token-suppression` | Older route load arrives after a fresh navigation; the carried nav-token is stale; runtime suppresses the result and emits `:rf.route.nav-token/stale-suppressed` |
| `route-fragment-change.edn` | `:routing/fragment-change` | `:fragment` is part of the route slice; fragment-only changes do NOT re-fire `:on-match`; path changes do |
| `route-navigation-blocked.edn` | `:routing/navigation-blocked` | `:can-leave` guard rejects a navigation; `:rf/pending-navigation` is set; URL unchanged; `:rf.route/continue` resumes; `:rf.route/cancel` abandons |
| `ssr-render-to-string.edn` | `:ssr/render-to-string` | Pure hiccup → HTML emission with text/attr escaping; void elements; doctype option |
| `ssr-hydrate.edn` | `:ssr/hydrate` | `:rf/hydrate` seeds `app-db` from server payload; subsequent dispatches operate on hydrated state |
| `ssr-redirect.edn` | `:ssr/redirect` | `:rf.server/redirect` truncates HTML; response carries `:status 302` + `:location` only (per [011 §Redirect precedence](../011-SSR.md#redirect-precedence)) |
| `ssr-set-status.edn` | `:ssr/set-status` | `:rf.server/set-status 404` populates the response accumulator; HTML body still renders |
| `ssr-cookie.edn` | `:ssr/cookie` | `:rf.server/set-cookie` adds a structured cookie to `:cookies`; host adapter would serialise to `Set-Cookie:` |
| `ssr-head-emits.edn` | `:ssr/head-emits` | Active route's `:head` resolves to a registered head fn; rendered HTML's `<head>` contains the expected title/meta/link tags |
| `ssr-head-hydration.edn` | `:ssr/head-hydration` | Hydration payload carries the unified `:rf/render-hash` (covering head + body in v1); client recomputes; matches; no `:rf.ssr/hydration-mismatch` (`:failing-id :rf.ssr/head-mismatch`) emitted. A dedicated `:rf/head` / `:rf/head-hash` payload channel is reserved for the post-v1 `reg-head` extension. |
| `ssr-error-sanitisation.edn` | `:ssr/error-sanitisation` | Handler throws; trace carries full detail; public response shape is locked generic-500; rendered HTML contains no internal detail |
| `ssr-error-known-mapping.edn` | `:ssr/error-known-mapping` | Default projector maps `:rf.error/no-such-handler` (routing context) → `{:status 404 :code :not-found ...}` public-error |
| `epoch-record-shape.edn` | `:epoch/record-shape` | Per-dispatch `:rf/epoch-record` carries `:event-id`, `:trigger-event`, `:db-before` / `:db-after` snapshot pair, and `:outcome :ok` — the Tool-Pair time-travel contract |
| `epoch-ring-multi-dispatch.edn` | `:epoch/ring-multi-dispatch` | Multiple settled drains append to the epoch-history ring in oldest-first order; each record's `:db-before` chains from the previous `:db-after` |
| `trace-buffer-filter-categories.edn` | `:trace-buffer/filter-categories` | One cascade reaches all four trace-bus categories — `:event` (lifecycle), `:rf.fx/handled`, `:sub/run`, `:rf.error/handler-exception` — so the trace-buffer's filter axes (`:op-type` / `:operation` / `:severity`) have reachable data per category |
| `view-registration.edn` | `:view/registration` | `reg-view` registers a view body that consumes a sub; the registrar accepts the registration and the dependent sub returns the live post-drain value (the data a render-trigger would consume). Render-time observables remain out of scope per the §Render-time observables note below |
| `http-interceptor-before-transforms.edn` | `:rf.http.interceptor/before-transforms` | `:rf.fx/reg-http-interceptor` registers a request-side `:before` interceptor (Spec 014 §Middleware, rf2-6y3q + rf2-yhfgf); the `:rf.http.interceptor/registered` trace fires at registration, and the body executes against the live ctx on a subsequent canned-success request — observable via a marker the body dispatches into app-db |
| `http-interceptor-clear.edn` | `:rf.http.interceptor/clear` | `:rf.fx/clear-http-interceptor` unregisters an interceptor by id; the `:rf.http.interceptor/cleared` trace fires; a subsequent request runs WITHOUT the cleared body — the dispatched-marker counter advances on the pre-clear request and STAYS at one through the post-clear request |

Coverage spans the main categories: handlers, frames, envelope, subs, fx, errors, machines, routing, SSR, hydration, epoch, trace bus, view registration, and HTTP request interceptors.

## Render-time observables (out of scope)

The corpus is host-agnostic pure-data event/sub/fx semantics. Render-time observables — React-context propagation through `frame-provider`, Reagent reactivity, component lifecycle, and the like — are not currently expressible as fixtures because the corpus has no render capability and no harness side that mounts a component tree to capture what happens during render. These behaviours are verified by host-side unit tests instead (e.g. `runtime_cljs_test.cljs` covers `frame-provider`'s establish-context and nested-override behaviour for the CLJS reference).

If a port (CLJS, JVM SSR, future hosts) needs to claim conformance for render-time behaviour, this README will grow a `:reagent/render` (or equivalent) capability tag and a host-side fixture runner that mounts the fixture's tree and captures the observable. Tracked as future work — see bead `rf2-j9yf` for context.

## Cross-references

- [Implementor-Checklist.md](../Implementor-Checklist.md) — decision-ordered companion to [000 §Host-profile matrix](../000-Vision.md#host-profile-matrix); Part 1 capability declarations and Part 3 (Conformance) tell the implementor which fixtures will run.
- [Spec-Schemas.md §Conformance](../Spec-Schemas.md#conformance) — the conformance test corpus question.
- [Spec-Schemas.md](../Spec-Schemas.md) — the shapes implementations validate / match against.
- [009 §Error contract](../009-Instrumentation.md) — error-event shape that error fixtures expect.
- [011-SSR.md](../011-SSR.md) — SSR fixtures' protocol.
- [012-Routing.md](../012-Routing.md) — routing fixtures' protocol.
- [Pattern-StaleDetection.md](../Pattern-StaleDetection.md) — the meta-pattern shared by `after-stale-detection.edn` and `route-stale-nav-token-suppression.edn`.
