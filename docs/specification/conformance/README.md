# Conformance Corpus

> Status: Skeleton. The conformance test suite for re-frame2 implementations. Per [reorient.md](../reorient.md): the spec aims to be sufficiently complete that an AI can one-shot the implementation in any host language. The conformance corpus is **how that claim is verified.**

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
- The corpus is itself **data the AI can read** — an AI implementing the pattern in a new language reads the corpus, generates host-native test code, and reports.

A JSON-translated corpus is also published for hosts where EDN is too friction; the JSON form is mechanically derived from the EDN source so the two never drift.

## Fixture format

Each fixture is an EDN map:

```clojure
{:fixture/id          :counter/inc-once
 :fixture/doc         "Single increment of the counter."
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

(The `:fixture/handlers` entries are written in EDN-ish form; in practice the handler bodies are described as data the host realises into closures. Worked example below.)

### Handler bodies as data

Since the corpus is host-agnostic, handler bodies can't be CLJS lambdas — they must be data the host realises into native closures.

The convention:

```clojure
;; A handler body is a small DSL the corpus harness interprets.
{:counter/initialise [[:set [:count] 0]]                      ;; sets db's :count to 0
 :counter/inc        [[:update [:count] [:fn :inc]]]}         ;; updates db's :count via :inc
```

The harness in each host implements a small interpreter for this DSL — `[:set path value]`, `[:update path fn]`, `[:dispatch event]`, etc. A complete interpreter is ~50 lines per host. The DSL is itself versioned and described by `:rf/fixture-handler-body` in [Spec-Schemas](../Spec-Schemas.md).

This keeps the corpus pure data; no host-specific code ships in the fixtures.

## Coverage

The corpus aims to exercise every spec-internal shape and every load-bearing behaviour. Initial coverage:

| Fixture | Exercises |
|---|---|
| `counter/inc-once.edn` | Trivial event handler; sub computation; trace emission |
| `counter/inc-multi.edn` | Run-to-completion drain across multiple events |
| `frame/lifecycle.edn` | `:on-create`, `:on-destroy`, frame metadata |
| `frame/multi-instance.edn` | Two frames isolated; same handler registry |
| `dispatch/envelope.edn` | Envelope shape: `:frame`, `:trace-id`, `:source` |
| `dispatch/sync.edn` | `dispatch-sync` settles before return |
| `dispatch/depth-limit.edn` | Drain-depth-exceeded error trace |
| `fx/registered.edn` | `reg-fx` resolution; routed args |
| `fx/override-by-id.edn` | `:fx-overrides` id-valued; pattern-level form |
| `fx/platforms.edn` | `:platforms` metadata; server-side fx skipping |
| `sub/computation.edn` | Pure derivation; `compute-sub` |
| `sub/chain.edn` | `:<-` signal-graph composition |
| `machine/transition.edn` | `machine-transition` pure fn; snapshot shape |
| `machine/event-handler.edn` | Machine as event handler with `:machine-path` |
| `error/handler-exception.edn` | Structured error trace; `:rf.error/handler-exception` |
| `error/schema-failure.edn` | Schema validation trace (dynamic-host only) |
| `ssr/render-to-string.edn` | Server flow; pure hiccup → HTML emission |
| `ssr/hydrate.edn` | `:rf/hydrate` event; client-side state seeding |
| `ssr/mismatch.edn` | Hydration mismatch detection |
| `routing/match-url.edn` | URL → route-id + params resolution |
| `routing/navigate.edn` | `:route/navigate` event; URL push effect |

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

The harness is small (~300 lines per host) and is itself documented as a [Construction Prompt CP-conformance](#) (forthcoming).

## Versioning

The corpus is versioned alongside the spec. Each fixture file declares the spec version it was authored against:

```clojure
{:fixture/id        :counter/inc-once
 :fixture/spec-version "1.0"
 ...}
```

When the spec changes shape (new required key in `:rf/dispatch-envelope`, new error category), affected fixtures bump their `:spec-version` and the corpus's harness check rejects implementations that haven't moved with the spec.

## Initial fixtures

See `fixtures/` for the actual files. Each fixture is one EDN file; each exercises one shape of the spec.

| Fixture | Coverage |
|---|---|
| `counter_inc_once.edn` | Trivial event handler; sub computation; trace emission |
| `frame_multi_instance.edn` | Multi-frame isolation with shared registrar |
| `dispatch_envelope.edn` | Envelope shape (`:event`, `:frame`, `:source`, `:trace-id`) surfacing in cofx |
| `sub_chain.edn` | `:<-` chained subs; static dependency topology |
| `fx_override_by_id.edn` | Pattern-level id-valued override seam |
| `fx_platforms.edn` | `:platforms` gating on `reg-fx` for SSR |
| `error_handler_exception.edn` | Structured `:rf.error/handler-exception` trace + cascade halt |
| `machine_transition.edn` | Pure machine-transition; xstate-flavored target/actions |
| `routing_match_url.edn` | Bidirectional URL ↔ params; round-trip property |

These nine cover the main categories (handlers, frames, envelope, subs, fx, errors, machines, routing). Pending fixtures for additional coverage: SSR (render-to-string, hydrate, mismatch), drain depth limit, no-such-handler, schema-validation-failure (dynamic-host only). Add as the spec firms up; not blocking.

## Cross-references

- [reorient.md §Open questions Q10](../reorient.md) — the conformance test corpus question.
- [Spec-Schemas.md](../Spec-Schemas.md) — the shapes implementations validate / match against.
- [009 §Error contract](../009-Instrumentation.md) — error-event shape that error fixtures expect.
- [011-SSR.md](../011-SSR.md) — SSR fixtures' protocol.
- [012-Routing.md](../012-Routing.md) — routing fixtures' protocol.
