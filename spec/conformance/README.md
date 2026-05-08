# Conformance Corpus

> **Type:** Reference
> The conformance test suite for re-frame2 implementations. The corpus is the verification mechanism for [Goal 2 — AI-implementable from the spec alone](../000-Vision.md#ai-implementable-from-the-spec-alone): the claim that the spec is sufficiently complete for an AI armed only with `/spec/` + this corpus to produce a working reference implementation in any host language. A spec gap surfaces as a fixture an AI cannot reproduce without consulting outside sources; closing the gap is a spec-level remediation, not an implementation-level one.

## Naming convention

Fixture filenames mirror their `:fixture/id` exactly, with the slash that separates the namespace from the local name rewritten as a hyphen and the rest of the id preserved. A fixture with `:fixture/id :counter/inc-once` lives in `counter-inc-once.edn`. Both filename and id use kebab-case; no underscores.

## What this is

A set of **fixture files** in EDN format, each describing one canonical interaction:

- A starting state (a frame configuration plus initial `app-db`).
- A sequence of dispatched events.
- The expected emissions: final `app-db`, trace events, effects routed to fx, return values from subscriptions.

An implementation conforms if it produces matching emissions for every fixture in the corpus.

## Why EDN

EDN is the natural data format for the CLJS reference. For other-host implementations:

- A small EDN reader exists in nearly every host language (or can be written in ~200 lines for any language with hash-maps and vectors).
- The structure is host-language-agnostic — keywords map to namespaced strings or branded enums per the host's identity primitive.
- The corpus is itself **machine-readable data** — implementers in a new language read the corpus, generate host-native test code, and report.

A JSON-translated corpus is also published for hosts where EDN is too friction; the JSON form is mechanically derived from the EDN source so the two never drift.

## Fixture format

Each fixture is an EDN map:

```clojure
{:fixture/id           :counter/inc-once
 :fixture/doc          "Single increment of the counter."
 :fixture/capabilities #{:core/event-handler :core/sub :core/trace}     ;; per §Capability tagging below
 :fixture/registry    {:event {:counter/initialise {:doc "Seed."}
                               :counter/inc        {:doc "Increment."}}
                       :sub   {:count             {:doc "Current count."}}
                       :fx    {}}
 :fixture/handlers    {:event {:counter/initialise (fn [_ _] {:count 0})
                               :counter/inc        (fn [db _] (update db :count inc))}
                       :sub   {:count (fn [db _] (:count db))}}
 :fixture/frame-config {:on-create [:counter/initialise]}
 :fixture/dispatches   [[:counter/inc]]
 :fixture/expect
 {:final-app-db        {:count 1}
  :sub-values          {[:count] 1}
  :trace-emissions     [{:operation :event :tags {:event-id :counter/inc}}
                        {:operation :event/do-fx :tags {}}]
  :effects-routed      []}}
```

### Capability tagging

Per [Goal 6 — Hierarchical FSM substrate with implementor-chosen capabilities](../000-Vision.md#hierarchical-fsm-substrate-with-implementor-chosen-capabilities) and [005 §Capability matrix](../005-StateMachines.md#capability-matrix), conformance is **graded against each port's claimed capability list** rather than all-or-nothing. Every fixture self-declares which capabilities it exercises via `:fixture/capabilities`. The harness only runs fixtures whose capability set is a subset of the port's claimed list; un-runnable fixtures are reported as "not exercised" rather than "failed."

Capability tag conventions:

- `:core/*` — pattern-required basics every conformant port supports (event handler, frame, dispatch envelope, sub, trace, fx, error).
- `:fsm/*` — FSM-richness axis (`:fsm/flat`, `:fsm/hierarchical`, `:fsm/eventless-always`, `:fsm/delayed-after`).
- `:actor/*` — actor-model axis (`:actor/own-state`, `:actor/spawn-destroy`, `:actor/cross-actor-fx`, `:actor/invoke`).
- `:routing/*`, `:ssr/*`, `:schemas/*` — per-spec capabilities for ports that ship them.

A flat-FSM-only port declares `:capabilities #{:core/event-handler ... :fsm/flat :actor/own-state :actor/spawn-destroy ...}` in its harness manifest; the corpus runs every fixture whose capabilities are a subset and skips the rest. The aggregate score is `passed / claimed-applicable` — an accounting of what works for the claimed list.

(The `:fixture/handlers` entries are written in EDN-ish form; in practice the handler bodies are described as data the host realises into closures. Worked example below.)

### Handler bodies as data

Since the corpus is host-agnostic, handler bodies can't be CLJS lambdas — they must be data the host realises into native closures.

The convention:

```clojure
;; A handler body is a small DSL the corpus harness interprets.
{:counter/initialise [[:set [:count] 0]]                      ;; sets db's :count to 0
 :counter/inc        [[:update [:count] [:fn :inc]]]}         ;; updates db's :count via :inc
```

The harness in each host implements a small interpreter for this DSL — `[:set path value]`, `[:update path fn]`, `[:dispatch event]`, etc. A complete interpreter is ~50 lines per host. The DSL is itself versioned and described by `:rf/fixture-handler-body` in [Spec-Schemas](../Spec-Schemas.md#rffixture-file-and-rffixture-handler-body).

This keeps the corpus pure data; no host-specific code ships in the fixtures.

### Handler-body DSL ops

The complete DSL operator set. The schemas live in [Spec-Schemas §`:rf/fixture-handler-body`](../Spec-Schemas.md#rffixture-file-and-rffixture-handler-body); below is the operator reference.

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
5. **Runs `:fixture/calls`** (if present) — direct invocations of `machine-transition` for machine fixtures.
6. **Captures observables** — final `app-db`, sub values (per `:fixture/expect :sub-values`), trace events emitted, effects routed.
7. **Compares** — partial-match per assertion. `:trace-emissions` partial-matches each trace event by its specified keys; absent keys are ignored. `:final-app-db` is a literal compare. `:expected-fx-emitted` matches the fx pairs in declaration order.

The harness reports per-fixture pass/fail; aggregate score is the count of passing fixtures over total fixtures.

Each fixture is **a single file** of <200 lines including registrations and expectations. Adding a fixture is a small focused change.

## How an implementation runs the corpus

1. Read all `.edn` files in `fixtures/`.
2. For each fixture:
   a. Bootstrap the host's runtime with the fixture's registry.
   b. Realise handler bodies via the DSL interpreter.
   c. Create a frame per `:fixture/frame-config`.
   d. Run each dispatch in `:fixture/dispatches`.
   e. After drain, capture: final `app-db`, sub values, emitted trace events, effects routed.
   f. Compare actual vs expected.
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
| `invoke-spawn-on-entry-destroy-on-exit.edn` | `:machine/invoke-spawn-on-entry-destroy-on-exit` | Declarative `:invoke` on a state node desugars at registration time to entry/exit `:spawn` / `:destroy-machine` fx; verifies the spawn fx args, the `:on-spawn` callback updating the parent's `:data`, and the exit destroy fx targeting the recorded child id. |
| `always-single-microstep.edn` | `:machine/always-single-microstep` | Eventless `:always` transition fires once after entry under a guard that just became true; one microstep settles to the target; verifies microstep counter and per-microstep + macrostep trace events; the intermediate "guard now true" state is NOT externally observable (atomic commit). |
| `always-depth-exceeded.edn` | `:machine/always-depth-exceeded` | A cycle of `:always` transitions across two states never settles; the microstep loop hits the configured `:always-depth-limit` (5 in this fixture) and emits `:rf.error/machine-always-depth-exceeded`. Snapshot uncommitted; recovery is `:no-recovery`. |
| `after-single-delay.edn` | `:machine/after-single-delay` | Single `:after` entry fires after the configured delay; verifies `:rf.machine.timer/scheduled` at state entry, `:rf.machine.timer/fired` at expiry, and the snapshot transitions to the target after the test-clock advances past the delay. |
| `after-stale-detection.edn` | `:machine/after-stale-detection` | A real `:on` event arrives before the `:after` timer expires; the machine transitions out of the state; the epoch advances; the eventual timer firing is detected as stale (`:rf.machine.timer/stale-after`) and silently ignored. The "real event beats the timer" race the epoch mechanism handles. |
| `after-hierarchy.edn` | `:machine/after-hierarchy` | `:after` on a parent compound state remains active while the snapshot is in any child; sibling-leaf transitions inside the parent do NOT cancel the parent's `:after`. Exiting the parent advances the epoch and the parent-level timer's eventual firing is stale. |
| `routing-match-url.edn` | `:routing/match-url` | Bidirectional URL ↔ params; round-trip property |
| `routing-navigate.edn` | `:routing/navigate` | `:rf.route/navigate` updates `app-db` + emits `:rf.nav/push-url` fx |
| `route-ranking-precedence.edn` | `:routing/ranking-precedence` | Deterministic 6-rule ranking cascade resolves overlapping routes; equal-score warning |
| `route-stale-nav-token-suppression.edn` | `:routing/stale-nav-token-suppression` | Older route load arrives after a fresh navigation; the carried nav-token is stale; runtime suppresses the result and emits `:route.nav-token/stale-suppressed` |
| `route-fragment-change.edn` | `:routing/fragment-change` | `:fragment` is part of the route slice; fragment-only changes do NOT re-fire `:on-match`; path changes do |
| `route-navigation-blocked.edn` | `:routing/navigation-blocked` | `:can-leave` guard rejects a navigation; `:rf/pending-navigation` is set; URL unchanged; `:rf.route/continue` resumes; `:rf.route/cancel` abandons |
| `ssr-render-to-string.edn` | `:ssr/render-to-string` | Pure hiccup → HTML emission with text/attr escaping; void elements; doctype option |
| `ssr-hydrate.edn` | `:ssr/hydrate` | `:rf/hydrate` seeds `app-db` from server payload; subsequent dispatches operate on hydrated state |
| `ssr-redirect.edn` | `:ssr/redirect` | `:rf.server/redirect` truncates HTML; response carries `:status 302` + `:location` only (per [011 §Redirect precedence](../011-SSR.md#redirect-precedence)) |
| `ssr-set-status.edn` | `:ssr/set-status` | `:rf.server/set-status 404` populates the response accumulator; HTML body still renders |
| `ssr-cookie.edn` | `:ssr/cookie` | `:rf.server/set-cookie` adds a structured cookie to `:cookies`; host adapter would serialise to `Set-Cookie:` |
| `ssr-head-emits.edn` | `:ssr/head-emits` | Active route's `:head` resolves to a registered head fn; rendered HTML's `<head>` contains the expected title/meta/link tags |
| `ssr-head-hydration.edn` | `:ssr/head-hydration` | Hydration payload carries `:rf/head` + `:rf/head-hash`; client recomputes; matches; no `:rf.warning/head-mismatch` emitted |
| `ssr-error-sanitisation.edn` | `:ssr/error-sanitisation` | Handler throws; trace carries full detail; public response shape is locked generic-500; rendered HTML contains no internal detail |
| `ssr-error-known-mapping.edn` | `:ssr/error-known-mapping` | Default projector maps `:rf.error/no-such-handler` (routing context) → `{:status 404 :code :not-found ...}` public-error |

Coverage spans the main categories: handlers, frames, envelope, subs, fx, errors, machines, routing, SSR, hydration.

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
