# Spec-Internal Shape Descriptions

> Status: Drafting. **The canonical shapes of the spec's runtime data**, written here in Malli (for the CLJS reference). Per [reorient.md](reorient.md): shape description is required (so AIs and tools can read shapes); the *mechanism* is not. **Dynamically typed hosts** (CLJS, Python, Ruby, JS) realise these shapes as runtime schemas — Malli, Pydantic, dry-rb, Zod. **Statically typed hosts** (TypeScript, Kotlin, Rust, F#) realise the same shapes as types in the language's own type system, generally without a runtime schema library. Both are first-class.

## Why this doc exists

The spec describes shapes for user code (events, subs, app-db slices) — those use whatever the host provides (schemas in dynamic hosts; types in static hosts). The same discipline applies to the spec's *own* runtime shapes (trace events, dispatch envelope, registration metadata, transition tables, hydration payload). Without it, the spec describes an external standard whose internals are themselves not described.

Two payoffs:

1. **Conformance.** An implementation passes if the runtime shapes it produces *match* the structures described below. Schema-bearing hosts validate at runtime; statically-typed hosts catch mismatches at compile time. Both work.
2. **AI-first.** An AI inspecting a runtime can ask "what's the shape of this trace event?" and get a schema (dynamic host) or a type definition (static host). Either way, the runtime is self-describing.

## Scope

The Malli forms below are the **canonical** shape descriptions for the CLJS reference. For a different host:

- **Schema-bearing dynamic host** (Python+Pydantic, Ruby+dry-rb, JS+Zod): translate each Malli form into the host's schema language. The shape is identical; the syntax differs.
- **Statically typed host** (TypeScript, Kotlin, Rust, F#): translate each Malli form into a type definition. The shape is identical; runtime validation is unnecessary if the type system enforces correctness throughout. A boundary validator (e.g., Zod for incoming JSON) may still be useful at system edges.

A port can translate the Malli forms below mechanically:

```clojure
;; Malli (CLJS reference)
(def DispatchEnvelope
  [:map
   [:event [:vector :any]]
   [:frame :keyword]
   ...])
```

```typescript
// TypeScript equivalent (no runtime schema; types only)
type DispatchEnvelope = {
  event: ReadonlyArray<unknown>;
  frame: Id;
  fxOverrides?: Record<string, Id | ((m: Envelope, args: unknown) => unknown)>;
  interceptorOverrides?: Record<string, Id | null>;
  traceId?: string;
  source?: 'ui' | 'timer' | 'http' | 'machine' | 'repl' | 'ssr-hydration' | 'test' | 'other';
  // open: additional keys are tolerated
  [k: string]: unknown;
};
```

```python
# Python equivalent (Pydantic; dynamic host)
class DispatchEnvelope(BaseModel):
    model_config = ConfigDict(extra='allow')   # open
    event: list
    frame: Id
    fx_overrides: Optional[Dict[Id, Any]] = None
    interceptor_overrides: Optional[Dict[Id, Any]] = None
    trace_id: Optional[str] = None
    source: Optional[Literal['ui', 'timer', 'http', 'machine', 'repl', 'ssr-hydration', 'test', 'other']] = None
```

The shape is the same in all three; the mechanism is local to the host.

## Schema convention

All spec-internal schemas:

- Are **open maps** by default (`:closed false`, equivalent to Malli's default behaviour). Unknown keys are tolerated; producers may add new keys additively.
- Are namespaced under `:rf/...` to avoid colliding with user schemas.
- Are registered at runtime via `reg-app-schema` for inspectability via `(app-schema-at [:rf/...])`.
- Use the lightest schema that captures the shape — preferring `[:map ...]` over more specific Malli grammars.

## Schemas

### `:rf/dispatch-envelope`

Carried internally by every dispatch. User-facing event vector remains a vector; the envelope wraps it.

```clojure
(def DispatchEnvelope
  [:map
   [:event                                 [:vector :any]]                ;; the user-facing event vector
   [:frame                                 :keyword]                       ;; target frame id
   [:fx-overrides         {:optional true} [:map-of :keyword :any]]        ;; id-valued at the pattern level; CLJS reference also accepts function values
   [:interceptor-overrides {:optional true} [:map-of :keyword :any]]
   [:interceptors          {:optional true} [:vector :any]]
   [:trace-id              {:optional true} :any]
   [:source                {:optional true} [:enum :ui :timer :http :machine :repl :ssr-hydration :test :other]]])
```

### `:rf/registration-metadata`

Common shape for the metadata map every `reg-*` accepts in its middle slot.

```clojure
(def RegistrationMetadata
  [:map
   [:doc       {:optional true} :string]
   [:spec      {:optional true} :any]                                      ;; Malli schema (or implementation equivalent)
   [:ns        {:optional true} :symbol]                                   ;; auto-supplied by macros
   [:line      {:optional true} :int]
   [:file      {:optional true} :string]
   [:tags      {:optional true} [:set :keyword]]                           ;; user-defined tags
   [:platforms {:optional true} [:set [:enum :server :client]]]            ;; for reg-fx / reg-cofx; per [011](011-SSR.md)
   [:interceptors {:optional true} [:vector :any]]                         ;; for reg-event-*
   ])
```

Per-kind extensions (sub-specific, fx-specific, view-specific) are additive maps that conform to RegistrationMetadata's open shape.

### `:rf/effect-map`

The return value of `reg-event-fx` handlers.

```clojure
(def EffectMap
  [:map                                                                   ;; open: user-registered fx add their own keys
   [:db              {:optional true} :any]                               ;; new app-db (replace)
   [:dispatch        {:optional true} [:vector :any]]                     ;; single child event
   [:dispatch-later  {:optional true} [:vector [:map [:ms :int] [:dispatch [:vector :any]]]]]
   [:fx              {:optional true} [:vector [:tuple :keyword :any]]]   ;; preferred multi-fx form: [[fx-id args] ...]
   ])
```

### `:rf/trace-event`

Universal trace event shape, including error events.

```clojure
(def TraceEvent
  [:map
   [:id        :any]
   [:operation :keyword]                                                   ;; what this trace describes
   [:op-type   [:enum :event :sub/run :sub/create :render :raf
                     :event/do-fx :machine/transition
                     :registry/handler-registered :registry/handler-cleared
                     :frame/created :frame/destroyed
                     :error :warning
                     :ssr]]
   [:start     :any]                                                       ;; timestamp; impl-specific
   [:end       {:optional true} :any]
   [:duration  {:optional true} number?]
   [:tags      {:optional true} [:map-of :keyword :any]]                   ;; op-type-specific payload
   [:child-of  {:optional true} :any]
   [:source    {:optional true} :keyword]
   [:frame     {:optional true} :keyword]                                  ;; the frame this trace happened in
   [:recovery  {:optional true} [:enum :no-recovery :replaced-with-default :retried :skipped :warned-and-replaced]]])
```

The error category schemas in [009 §Error contract](009-Instrumentation.md#error-contract) are *refinements* of TraceEvent for `:op-type :error` events.

### `:rf/transition-table`

Grammar for state-machine transition tables (per [005](005-StateMachines.md)). xstate-flavoured.

```clojure
(def TransitionTable
  [:map
   [:id              {:optional true} :keyword]
   [:initial         :keyword]                                             ;; the initial state node
   [:context         {:optional true} :map]                                ;; initial extended state
   [:states          [:map-of :keyword StateNode]]
   [:on              {:optional true} [:map-of :keyword Transition]]       ;; global transitions
   [:meta            {:optional true} :map]])

(def StateNode
  [:map
   [:on              {:optional true} [:map-of :keyword Transition]]       ;; event → transition
   [:entry           {:optional true} [:vector :keyword]]                  ;; action ids
   [:exit            {:optional true} [:vector :keyword]]
   [:always          {:optional true} Transition]                           ;; eventless transition
   [:meta            {:optional true} :map]])

(def Transition
  [:or
   :keyword                                                                 ;; target state id (shorthand)
   [:map
    [:target  {:optional true} :keyword]
    [:cond    {:optional true} :keyword]                                    ;; guard id
    [:actions {:optional true} [:vector :keyword]]                          ;; action ids
    [:meta    {:optional true} :map]]])
```

### `:rf/machine-snapshot`

The runtime snapshot of a machine instance.

```clojure
(def MachineSnapshot
  [:map
   [:state    :keyword]                                                    ;; current state id
   [:context  {:optional true} :map]                                       ;; current extended state
   [:meta     {:optional true} :map]])
```

### `:rf/hydration-payload`

Per [011](011-SSR.md). The shape of the data crossing the wire from server to client.

```clojure
(def HydrationPayload
  [:map
   [:rf/version    :string]                                                ;; pattern version, e.g. "1.0"
   [:rf/frame-id   :keyword]                                               ;; the frame id to seed
   [:rf/app-db     :any]                                                   ;; serialised app-db
   [:rf/render-hash {:optional true} :string]                              ;; hash of the server-rendered render-tree, for mismatch detection
   [:rf/sub-warmups {:optional true} [:map-of [:vector :any] :any]]        ;; pre-computed sub values (post-v1; see [011] S-7)
   ])
```

### `:rf/frame-meta`

Returned by `(frame-meta frame-id)`.

```clojure
(def FrameMeta
  [:map
   [:id           :keyword]
   [:created-at   :any]                                                    ;; timestamp
   [:on-create    {:optional true} [:vector :any]]                         ;; the init event vector
   [:on-destroy   {:optional true} [:vector :any]]
   [:fx-overrides {:optional true} [:map-of :keyword :any]]
   [:interceptor-overrides {:optional true} [:map-of :keyword :any]]
   [:interceptors {:optional true} [:vector :any]]
   [:drain-depth  {:optional true} :int]
   [:doc          {:optional true} :string]
   [:tags         {:optional true} [:set :keyword]]])
```

## Conformance

An implementation conforms if every runtime shape it produces *matches* the structures described above. Multiple paths to conformance:

- **Dynamically typed hosts with a runtime schema layer** (CLJS+Malli; Python+Pydantic; JS+Zod; Ruby+dry-rb): validate emitted shapes against registered schemas. Failures are surfaced as `:rf.error/schema-validation-failure` trace events ([009 §Error contract](009-Instrumentation.md#error-contract)).
- **Statically typed hosts** (TypeScript, Kotlin, Rust, F#): the type system enforces shape correctness through the runtime. Mismatches at the boundary (incoming JSON, deserialised state) are caught by a small boundary validator if needed. Most internal shape errors don't arise at all because the compiler rejected them.
- **Dynamically typed hosts without a runtime schema layer** (rare; primarily early-stage prototypes): match shapes by convention. Conformance is verified by the fixture corpus alone.

The conformance test corpus is built from canonical interactions (a counter increment, a feature scaffolding, a state-machine transition, a server-side render + hydration round-trip) along with the expected emissions. The corpus format is itself data — an EDN/JSON file — so an AI can read it, generate test code in the host language, and report conformance scores.

All three paths pass the corpus; the differences are about *when* shape errors are detected (compile time vs. runtime) and *what* mechanism catches them.

## Cross-references

- [reorient.md](reorient.md) — the open-maps-with-schemas commitment.
- [009 §Error contract](009-Instrumentation.md#error-contract) — error-event refinements of `TraceEvent`.
- [010-Schemas.md](010-Schemas.md) — the CLJS reference's schema integration (Malli); this doc applies the same shape discipline to the spec's *own* runtime data.
- [011-SSR.md](011-SSR.md) — hydration payload context.
- [005-StateMachines.md](005-StateMachines.md) — transition table grammar context.
- [AI-Audit.md](AI-Audit.md) — the audit that surfaced these gaps.
