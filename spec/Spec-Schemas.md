# Spec-Internal Shape Descriptions

> **Type:** Schemas
> The canonical shapes of the spec's runtime data, written in Malli (for the CLJS reference). Shape description is required (so AIs and tools can read shapes); the *mechanism* is not. **Dynamically typed hosts** (CLJS, Python, Ruby, JS) realise these shapes as runtime schemas — Malli, Pydantic, dry-rb, Zod. **Statically typed hosts** (TypeScript, Kotlin, Rust, F#) realise the same shapes as types in the language's own type system, generally without a runtime schema library. Both are first-class.

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

## Schema layers

Each schema in this catalogue belongs to exactly one of three layers. The layer tells consumers what role the schema plays in the contract:

- **Runtime** — shapes the framework produces or consumes during normal operation (the dispatch envelope the runtime constructs, the effect-map a handler returns, the snapshot a machine writes, the trace event a tool reads). Implementations *must* match these on the wire; they are observable to every layer above.
- **Public** — shapes the user passes into `reg-*` metadata or `(rf/configure ...)` opts. These describe *what tooling reads when introspecting registrations*: the metadata map shape, the frame-meta returned by `(frame-meta id)`, the route-metadata accepted by `reg-route`. Tools target this surface; implementations validate user input against it.
- **Conformance** — shapes that exist for the conformance corpus and capability-tagging machinery. The handler-body DSL, the fixture-file format, the capability-tagging convention — none of these flow through the runtime; they exist so a host-agnostic test harness can drive any implementation.

Layer membership is **disjoint**: a schema is exactly one of Runtime, Public, or Conformance. Each schema entry below carries a `> Layer:` header naming its layer.

### v1 vs post-v1 contracts

The schemas below are scoped to the **v1 contract**. Where a shape declares optional keys that are reserved for **post-v1** features, those keys are flagged inline with a `(post-v1)` annotation. v1 implementations emit only the v1 keys and tolerate (per the open-map convention) but do not require post-v1 keys.

The hydration payload is the canonical example: v1 ships with a small required set; post-v1 will extend it with sub-warmups, machine-snapshot wire forms, and similar. The v1-required set is held stable across the v1 lifetime; post-v1 keys appear additively.

## Schemas

### `:rf/dispatch-envelope`

> **Layer:** Runtime

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
   [:source                {:optional true} [:enum :ui :timer :http :machine :repl :ssr-hydration :test :other]] ;; trigger kind
   [:origin                {:optional true} :keyword]                      ;; actor identity (default :app) — per [002 §Dispatch origin tagging]
   [:dispatched-at         {:optional true} :any]])                        ;; CLJS reference may add an impl-specific timestamp; tools tolerate
```

### `:rf/dispatch-opts`

> **Layer:** Public

The opts map a user passes to `(dispatch event opts)` / `(dispatch-sync event opts)` / `(subscribe query-v opts)`. The runtime promotes these into a `:rf/dispatch-envelope`. **The opts schema is a *subset* of the envelope** — opts the user supplies are user-facing; envelope keys the runtime adds (`:event` itself, `:dispatched-at`) are internal.

```clojure
(def DispatchOpts
  [:map
   [:frame                 {:optional true} :keyword]                       ;; defaults to :rf/default
   [:fx-overrides          {:optional true} [:map-of :keyword :any]]
   [:interceptor-overrides {:optional true} [:map-of :keyword :any]]
   [:interceptors          {:optional true} [:vector :any]]
   [:trace-id              {:optional true} :any]
   [:source                {:optional true} [:enum :ui :timer :http :machine :repl :ssr-hydration :test :other]]
   [:origin                {:optional true} :keyword]])                     ;; actor identity tag — defaults to :app when omitted
```

The promotion is structural: `(dispatch event opts)` → envelope is `(merge {:event event :frame :rf/default :dispatched-at (now)} opts)`. The runtime asserts `:event` and `:frame` are present after the merge.

### `:rf/registration-metadata`

> **Layer:** Public

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
   ])
```

Per-kind extensions (sub-specific, fx-specific, view-specific) are additive maps that conform to RegistrationMetadata's open shape.

The `reg-event-*` interceptor chain is **not** a metadata-map key — it is the positional vector slot between the metadata-map and the handler. Per [001-Registration §Allowed forms of the middle slot](001-Registration.md#allowed-forms-of-the-middle-slot) and [Conventions §`:interceptors` is positional, not metadata](Conventions.md#interceptors-is-positional-not-metadata-reg-event-), `:interceptors` inside this map is silently ignored and the runtime emits `:rf.warning/interceptors-in-metadata-map`. (`reg-frame`'s metadata-map *does* carry an `:interceptors` key — that's a per-kind extension defined in [Spec 002 §`:interceptors`](002-Frames.md#interceptors--add-interceptors-to-a-frames-events).)

### `:rf/source-coord-meta`

> **Layer:** Public

The registration-metadata source-coord sub-shape captured at `reg-*` macro-expansion time. Returned (as a sub-map of `:rf/registration-metadata`) from `(rf/handler-meta kind id)` and `(rf/frame-meta id)`; pair-shaped tools and IDE jump-to-source consumers read it for click-back-to-code resolution per [Tool-Pair §Source-mapping UI clicks back to code](Tool-Pair.md#source-mapping-ui-clicks-back-to-code).

```clojure
(def SourceCoordMeta
  [:map
   [:ns      :symbol]                                                       ;; the namespace symbol of the call site
   [:line    nat-int?]                                                      ;; integer source line
   [:column  nat-int?]                                                      ;; integer source column
   [:file    [:maybe :string]]])                                            ;; absolute or classpath-relative source file; nil when not captured
```

The four keys are the canonical source-coord shape. The CLJS reference fills all four from `(meta &form)` (`:line`, `:column`) and the compile-time `*ns*` / `*file*` (per [Spec 001 §Source-coordinate capture](001-Registration.md#source-coordinate-capture-cljs-reference)). Programmatic registrations that bypass the macro path may carry a partial shape (e.g. only `:ns` resolved); consumers handle missing keys defensively. Companion shape: `:rf/source-coord-attr` below — a string-encoded contraction of this map suitable for DOM-attribute emission.

### `:rf/source-coord-attr`

> **Layer:** Public

The DOM-attribute string contract emitted by Reagent / SSR adapters as the value of `data-rf2-source-coord` on rendered view roots (per [Spec 006 §Attribute value format](006-ReactiveSubstrate.md#attribute-value-format) and [011 §Source-coord annotation under SSR](011-SSR.md#source-coord-annotation-under-ssr)). A 4-segment colon-separated string:

```
<ns>:<sym>:<line>:<col>
```

- `<ns>` — the registered handler-id's namespace (string-encoded).
- `<sym>` — the registered handler-id's name (string-encoded). Note this is the **registry handler-id**, not a file path.
- `<line>` — integer source line, or the literal `?` for programmatic registrations whose macro-captured coord is absent.
- `<col>` — integer source column, or the literal `?` for programmatic registrations whose macro-captured coord is absent.

```clojure
(def SourceCoordAttr
  [:re #"^[^:]+:[^:]+:(?:\d+|\?):(?:\d+|\?)$"])                             ;; pragmatic regex; consumers parse 4 segments
```

Consumers parse the four segments pragmatically (split on `:` from the right twice to recover `<line>` and `<col>`, then on `:` once more for `<ns>`/`<sym>`). To recover the full `:rf/source-coord-meta` shape — including `:file` — look up the handler-id via `(rf/handler-meta :view <handler-id>)` and read its source-coord sub-map; `:file` is **not** recoverable by parsing the attribute alone (it is not encoded in the 4-segment string).

The string format is committed as a public contract (rf2-q7r0): pair-shaped tools, conformance harnesses, and CDP-driven test runners all parse it directly. Future extensions are additive — additional trailing segments may appear; consumers MUST handle the 4-segment shape and tolerate (ignore) trailing segments they do not recognise.

### `:rf/effect-map`

> **Layer:** Runtime

The return value of `reg-event-fx` handlers. **Only two keys: `:db` and `:fx`.**

```clojure
(def EffectMap
  [:map {:closed true}
   [:db {:optional true} :any]                                           ;; new app-db (replace)
   [:fx {:optional true} [:vector [:tuple :keyword :any]]]               ;; effects: [[fx-id args] ...]
   ])
```

Every effect — including dispatching another event, scheduling a delayed dispatch, HTTP requests, navigation, anything — goes through `:fx` as a registered-fx-id + args pair:

```clojure
{:db (assoc db :counter 1)
 :fx [[:dispatch       [:counter/saved]]
      [:dispatch-later {:ms 1000 :dispatch [:counter/cleanup]}]
      [:http           {:method :get :url "/api/items"}]
      [:localstorage/set {:key "counter" :value 1}]]}
```

The single-form rule lets the runtime walk one ordered list of effects, and fits the pattern's regularity-over-cleverness principle ([Principles.md §Regularity](Principles.md#regularity-over-cleverness)). Migrating from earlier re-frame versions: see [MIGRATION.md](MIGRATION.md).

Note the schema is **closed** — unlike most spec-internal shapes which are open maps. The effect map is a contract between handler and runtime; novel keys would be silently ignored, which is exactly the kind of footgun the closed shape rules out.

**Normative ordering and atomicity.** Beyond the shape, the effect map carries a runtime-ordering contract that conformant implementations must produce: `:db` is the first side effect (when present, committed atomically before any `:fx` entry); `:fx` entries are processed in source order, serially (entry N's fx-handler returns before entry N+1's begins); subscriptions observe the post-`:db` state from the first `:fx` entry onwards; if an fx-handler throws, subsequent `:fx` entries continue to run and each error is traced as `:rf.error/fx-handler-exception`. See [002 §`:fx` ordering and atomicity guarantees](002-Frames.md#fx-ordering-and-atomicity-guarantees) for the full rules and rationale.
```

### `:rf/trace-event`

> **Layer:** Runtime

Universal trace event shape, including error events.

```clojure
(def TraceEvent
  [:map
   [:id        :any]                                                       ;; auto-incrementing per-process counter
   [:operation :keyword]                                                   ;; what this trace describes
   [:op-type   :keyword]                                                   ;; discriminator (open vocabulary; see below)
   [:time      :any]                                                       ;; emit timestamp (host clock)
   [:tags      {:optional true} [:map-of :keyword :any]]                   ;; op-type-specific payload
   [:source    {:optional true} :keyword]                                  ;; (when present) the trigger source
   [:recovery  {:optional true} [:enum :no-recovery :replaced-with-default :retried :skipped :warned-and-replaced :logged-and-skipped :ignored]]])
```

The runtime emits event-at-a-time, not span-shaped: there is no `:start`/`:end`/`:duration` pair and no `:child-of` parent-id. Cascade correlation rides on `:dispatch-id` / `:parent-dispatch-id` under `:tags` of `:event/dispatched` events (per [009 §Dispatch correlation](009-Instrumentation.md#dispatch-correlation-dispatch-id--parent-dispatch-id)). Per-event frame attribution rides under `[:tags :frame]`.

The `:op-type` vocabulary is **open** — implementations and tools may add new values additively per [Spec-ulation](Principles.md). The reserved values used by the framework (in alphabetical order):

| `:op-type` | Used for | Spec |
|---|---|---|
| `:error` | Any `:rf.error/*` operation | 009 |
| `:event` | Top-level event handler invocation | 009 |
| `:event/do-fx` | Effect-resolution pass after handler returns | 009 |
| `:frame/created`, `:frame/destroyed` | Frame lifecycle | 002 |
| `:rf.frame/drain-aborted` | A frame's drain loop detected `(:destroyed? (:lifecycle frame))` mid-cycle; remaining queued events are dropped. `:op-type :event` (lifecycle, not error). `:tags {:frame <id> :dropped-count <int>}`. Per [002 §Edge cases worth pinning](002-Frames.md#edge-cases-worth-pinning) | 002 |
| `:rf.machine/transition` | State-machine transition | 005 |
| `:rf.machine.microstep/transition` | State-machine `:always` per-microstep transition | 005 |
| `:rf.machine.timer/scheduled` | State-machine `:after` timer scheduled at state entry (or re-scheduled on subscription-driven re-resolution per [005 §Dynamic delay re-resolution](005-StateMachines.md#dynamic-delay-re-resolution)). `:tags` carries `:delay-source <:literal | :sub | :fn>` and `:sub-id` (when source = `:sub`). | 005 |
| `:rf.machine.timer/fired` | State-machine `:after` timer fired (live; epoch matched). `:tags` carries `:fired? <bool>` indicating whether the guard passed (false ⇒ suppressed, no transition; sibling timers continue). | 005 |
| `:rf.machine.timer/stale-after` | State-machine `:after` timer fired with mismatched epoch (state was exited before the timer expired) and was silently ignored | 005 |
| `:rf.machine.timer/cancelled-on-resolution` | State-machine `:after` timer with subscription-vector delay was cancelled because the subscription's value changed; a fresh timer is scheduled. Per [005 §Dynamic delay re-resolution](005-StateMachines.md#dynamic-delay-re-resolution). `:tags {:delay <prior-ms> :reason :sub-changed :sub-id <sub-id>}`. | 005 |
| `:rf.machine.timer/skipped-on-server` | State-machine `:after` entry reached under SSR; timer scheduling suppressed per [005 §SSR mode](005-StateMachines.md#ssr-mode) | 005 |
| `:rf.registry/handler-registered`, `:rf.registry/handler-cleared`, `:rf.registry/handler-replaced` | Registrar mutations | 001 / 009 |
| `:view/render` | View render (per [Spec 004 §Render-tree primitives](004-Views.md)) | 004 / 009 |
| `:rf.epoch/snapshotted`, `:rf.epoch/restored`, `:rf.epoch/db-replaced` | Tool-Pair epoch operations (the latter is the rf2-zq55 pair-tool write surface; see [Tool-Pair §Pair-tool writes](Tool-Pair.md#pair-tool-writes--state-injection)) | Tool-Pair |
| `:route.nav-token/allocated` | A new navigation token was allocated for an `:rf/url-changed` / `:rf.route/navigate` cascade. `:tags {:route-id <id> :nav-token <token>}` | 012 |
| `:route.nav-token/stale-suppressed` | An async result arrived carrying a `:nav-token` that no longer matches the active route's token; the result was silently suppressed. `:tags {:carried-token <t1> :current-token <t2> :event-id <id>}` | 012 |
| `:rf.route/url-changed` | The runtime emitted `:rf/url-changed` for a fragment-only navigation (path and query unchanged); `:on-match` was NOT re-fired. `:tags {:route-id <id> :prev-fragment <s> :next-fragment <s>}` | 012 |
| `:rf.route/navigation-blocked` | A `:can-leave` guard rejected a navigation; pending-nav slot was set; user dispatch of `:rf.route/continue` / `:rf.route/cancel` is awaited. `:tags {:pending-id <id> :requested-url <url> :rejecting-route <id>}` | 012 |
| `:rf.fx/override-applied` | An override redirected an fx call | 002 |
| `:ssr` | Generic SSR-context op (server-render boundaries) | 011 |
| `:sub/create`, `:sub/run` | Subscription lifecycle | 009 |
| `:warning` | Non-error advisories (e.g., `:rf.warning/plain-fn-under-non-default-frame-once`) | 009 |
| `:fx` | An effect substrate event (e.g. `:rf.fx/handled`, `:rf.fx/override-applied`) — the universal discriminator for fx outcomes when not error/warning-shaped | 009 |
| `:info` | Informational advisories (e.g. `:rf.http/retry-attempt`) | 009 / 014 |
| `:registry` | Registrar mutation events (handler-registered/cleared/replaced) | 001 / 009 |
| `:machine` | Machine-substrate events (transitions, lifecycle, timers) | 005 |
| `:rf.epoch` | Epoch-history events (snapshotted, restored, db-replaced) | Tool-Pair |
| `:frame` | Frame-lifecycle events (created, re-registered, destroyed) | 002 |

The error category schemas in [009 §Error contract](009-Instrumentation.md#error-contract) are *refinements* of TraceEvent for `:op-type :error` events. The unified error/warning envelope is captured by `:rf/error-event` (below).

### `:rf/error-event`

> **Layer:** Runtime

A refinement of `:rf/trace-event` for the unified error/warning envelope. Every error or warning emitted by the runtime conforms to this shape; per-category schemas (in [009 §Error contract](009-Instrumentation.md#error-contract)) further constrain `:tags`.

```clojure
(def ErrorEvent
  [:map
   [:id        :any]
   [:operation :keyword]                        ;; one of the reserved :rf.error/* / :rf.warning/* / :rf.fx/* / :rf.ssr/* operations
   [:op-type   [:enum :error :warning]]         ;; :error for failures; :warning for advisories
   [:time      :any]                            ;; emit timestamp (host clock)
   [:source    {:optional true} :keyword]
   [:recovery  {:optional true} [:enum :no-recovery :replaced-with-default :retried :skipped :warned-and-replaced :logged-and-skipped :ignored]]
   [:tags      [:map
                [:category    :keyword]         ;; same value as :operation, for consumer convenience
                [:failing-id  {:optional true} :any]
                [:frame       {:optional true} :keyword]
                [:reason      {:optional true} :string]]]])    ;; remaining keys are category-specific
```

The `:op-type` discriminates severity: `:error` halts or recovers a specific operation; `:warning` is an advisory the runtime emitted alongside continuing default behaviour. Consumers branch on `:op-type` for severity routing and on `:operation` for category-specific handling.

The canonical category vocabulary is fixed-and-additive (Spec-ulation): existing categories cannot be renamed or removed; new categories are added by extending the operation namespace. The current set is enumerated in [009 §Error categories](009-Instrumentation.md#error-categories-initial-set) and reproduced in [API.md §Error contract](API.md#error-contract). Reserved operation namespaces:

| Namespace | Severity | Used for |
|---|---|---|
| `:rf.error/*` | `:error` | Runtime failures (handler/sub/fx exceptions, missing handlers, schema failures, drain depth, override fallthrough, adapter misuse) |
| `:rf.warning/*` | `:warning` | Authoring-time advisories the runtime can detect but does not abort on (e.g. plain Reagent fn under non-default frame) |
| `:rf.fx/*` | `:warning` | Effect-resolution advisories (e.g. fx skipped because of `:platforms`) |
| `:rf.ssr/*` | `:warning` | SSR-specific advisories (hydration mismatch and similar) |
| `:rf.epoch/*` | `:error` | Epoch-history restore failures (per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel)) |
| `:rf.http/*` | `:warning` / `:info` | Managed-HTTP advisories (key-ignored-on-jvm, retry-attempt) per [014](014-HTTPRequests.md) |
| `:route.nav-token/*` | `:error` | Stale-result-suppression on async navigation (per [012 §Navigation tokens](012-Routing.md#navigation-tokens--stale-result-suppression)) |
| `:rf.frame/*` | `:event` | Frame-lifecycle events (e.g. `:rf.frame/drain-aborted` per [002](002-Frames.md)) |

### Per-category `:tags` schemas

> **Layer:** Runtime

Each error / warning category enumerated in [009 §Error categories](009-Instrumentation.md#error-categories-initial-set) has a registered Malli schema describing its `:tags` payload, so consumers can validate without ad-hoc parsing. The schemas below are the canonical CLJS-reference shapes; ports translate them mechanically into the host's schema language (per [§Scope](#scope)).

Common keys (`:category`, `:failing-id`, `:reason`, `:frame`) are inherited from the `:rf/error-event` envelope above; the per-category schemas below describe the *additional* category-specific keys. Open-map convention applies — implementations may add fields additively without breaking consumers (per [§Schema convention](#schema-convention)).

```clojure
;; --- runtime: handler / sub / fx / interceptor exceptions ---

(def HandlerExceptionTags
  [:map
   [:category          [:= :rf.error/handler-exception]]
   [:failing-id        :keyword]
   [:reason            :string]
   [:event             [:vector :any]]
   [:handler-id        :keyword]
   [:exception-message :string]
   [:exception-data    {:optional true} :any]])

(def FxHandlerExceptionTags
  [:map
   [:category          [:= :rf.error/fx-handler-exception]]
   [:failing-id        :keyword]
   [:fx-id             :keyword]
   [:fx-args           :any]
   [:frame             {:optional true} :keyword]
   [:exception-message :string]])

(def SubExceptionTags
  [:map
   [:category          [:= :rf.error/sub-exception]]
   [:failing-id        :keyword]
   [:sub-id            :keyword]
   [:sub-query         [:vector :any]]
   [:exception-message :string]])

(def NoSuchSubTags
  [:map
   [:category :keyword]            ;; [:= :rf.error/no-such-sub] in a closed schema
   [:query-v  [:vector :any]]
   [:frame    {:optional true} :keyword]])

(def NoSuchHandlerTags
  [:map
   [:category :keyword]
   [:event-id {:optional true} :keyword]
   [:event    {:optional true} [:vector :any]]
   [:frame    {:optional true} :keyword]
   [:url      {:optional true} :string]    ;; routing-side variant
   [:kind     {:optional true} :keyword]])

(def NoSuchFxTags
  [:map
   [:category :keyword]
   [:fx-id    :keyword]
   [:fx-args  :any]
   [:frame    {:optional true} :keyword]])

(def OverrideFallthroughTags
  [:map
   [:category      :keyword]
   [:failing-id    :keyword]
   [:overrides-map [:map-of :keyword :any]]
   [:looked-up-id  :keyword]])

(def UnwrapBadEventShapeTags
  [:map
   [:category :keyword]
   [:event    [:vector :any]]
   [:expected :string]])

;; --- runtime: validation / drain / dispatch lifecycle ---

(def SchemaValidationTags
  [:map
   [:category    [:= :rf.error/schema-validation-failure]]
   [:failing-id  :keyword]
   [:reason      {:optional true} :string]
   [:where       [:enum :event :sub-return :app-db :fx-args :cofx :cofx-args :on-create]]
   [:path        {:optional true} [:vector :any]]
   [:value       {:optional true} :any]
   [:explain     {:optional true} :any]                ;; Malli explanation shape
   [:explanation {:optional true} :any]])              ;; legacy alias retained for back-compat

(def DrainDepthExceededTags
  [:map
   [:category   :keyword]
   [:frame      :keyword]
   [:depth      :int]
   [:queue-size :int]
   [:last-event {:optional true} [:vector :any]]
   [:rollback?  {:optional true} :boolean]])

(def DispatchSyncInHandlerTags
  [:map
   [:category :keyword]
   [:frame    :keyword]
   [:event    [:vector :any]]
   [:reason   :string]])

(def FrameDestroyedTags
  [:map
   [:category :keyword]
   [:frame    :keyword]
   [:event    {:optional true} [:vector :any]]
   [:query-v  {:optional true} [:vector :any]]])

(def EffectMapShapeTags
  [:map
   [:category      :keyword]
   [:failing-id    :keyword]
   [:event-id      :keyword]
   [:event         [:vector :any]]
   [:offending-key :keyword]
   [:value         :any]
   [:reason        :string]])

(def FlowEvalExceptionTags
  [:map
   [:category :keyword]
   [:frame    :keyword]
   [:event    [:vector :any]]
   [:exception :any]])

;; --- runtime: state-machine errors (per [005](005-StateMachines.md)) ---

(def MachineActionExceptionTags
  [:map
   [:category          [:= :rf.error/machine-action-exception]]
   [:machine-id        :keyword]
   [:action-id         :any]
   [:state-path        [:vector :any]]
   [:transition        :any]
   [:event             [:vector :any]]
   [:failing-id        :keyword]
   [:handler-id        :keyword]
   [:frame             :keyword]
   [:exception-message :string]
   [:exception-data    {:optional true} :any]])

(def MachineRaiseDepthExceededTags
  [:map
   [:category   :keyword]
   [:machine-id :keyword]
   [:depth      :int]])

(def MachineAlwaysDepthExceededTags
  [:map
   [:category   :keyword]
   [:machine-id :keyword]
   [:depth      :int]
   [:path       [:vector :any]]])

(def MachineUnresolvedGuardTags
  [:map
   [:category   :keyword]
   [:guard      :keyword]
   [:machine-id :keyword]])

(def MachineUnresolvedActionTags
  [:map
   [:category   :keyword]
   [:action     :keyword]
   [:machine-id :keyword]])

(def MachineBadGuardFormTags
  [:map
   [:category :keyword]
   [:guard    :any]])

(def MachineBadActionFormTags
  [:map
   [:category :keyword]
   [:action   :any]])

(def MachineBadStateFormTags
  [:map
   [:category :keyword]
   [:state    :any]])

(def MachineBadOnClauseTags
  [:map
   [:category :keyword]
   [:value    :any]])

(def MachineActionWroteDbTags
  [:map
   [:category        :keyword]
   [:machine-id      :keyword]
   [:action-id       :any]
   [:state-path      [:vector :any]]
   [:offending-value :any]])

(def MachineGrammarNotInV1Tags
  [:map
   [:category   :keyword]
   [:machine-id :keyword]
   [:feature    :keyword]
   [:substitute {:optional true} :string]])

(def MachineUnhandledEventTags
  [:map
   [:category   :keyword]
   [:machine-id :keyword]
   [:event      [:vector :any]]
   [:state      :any]])

(def MachineStateNotInDefinitionTags
  [:map
   [:category   :keyword]
   [:machine-id :keyword]
   [:state      :any]])

(def MachineSnapshotVersionMismatchTags
  [:map
   [:category         :keyword]
   [:machine-id       :keyword]
   [:version-recorded :any]
   [:version-current  :any]])

(def MachineAlwaysSelfLoopTags
  [:map
   [:category   :keyword]
   [:state      :keyword]
   [:machine-id :keyword]])

(def MachineCompoundStateMissingInitialTags
  [:map
   [:category   :keyword]
   [:machine-id :keyword]
   [:state      :any]])

(def SystemIdCollisionTags
  [:map
   [:category         :keyword]
   [:frame            :keyword]
   [:system-id        :any]
   [:existing-machine :keyword]
   [:rebound-to       :keyword]
   [:reason           :string]])

;; --- runtime: routing errors (per [012](012-Routing.md)) ---

(def NoSuchRouteTags
  [:map
   [:category :keyword]
   [:route-id :keyword]])

(def MissingRouteParamTags
  [:map
   [:category :keyword]
   [:param    :keyword]
   [:route-id :keyword]])

(def DuplicateUrlBindingTags
  [:map
   [:category        :keyword]
   [:existing-frame  :keyword]
   [:offending-frame :keyword]])

(def RouteShadowedByEqualScoreTags
  [:map
   [:category  :keyword]
   [:route-id  :keyword]
   [:shadowed  :keyword]
   [:rank      {:optional true} :any]])

(def StaleSuppressedTags
  [:map
   [:category      :keyword]
   [:carried-token :any]
   [:current-token :any]
   [:event-id      {:optional true} :keyword]])

;; --- runtime: schemas / preset / adapter / SSR errors ---

(def BadAppSchemasArgTags
  [:map
   [:category :keyword]
   [:received :any]
   [:expected :string]])

(def UnknownPresetTags
  [:map
   [:category :keyword]
   [:preset   :any]
   [:valid    [:set :keyword]]])

(def AdapterAlreadyInstalledTags
  [:map
   [:category  :keyword]
   [:installed :any]
   [:attempted :any]])

(def NoAdapterSpecifiedTags
  ;; Per rf2-agql: (rf/init! …) raised because the consumer did not pass
  ;; an adapter spec map. The only legal call shape is (rf/init! adapter-map);
  ;; calling (rf/init!) with no args, nil, or a non-map argument (e.g. a
  ;; keyword) raises this error. Surfaced as a thrown ex-info, not a trace.
  [:map
   [:category :keyword]
   [:where    [:or :symbol :string]]
   [:received {:optional true} :any]
   [:expected {:optional true} :string]
   [:recovery {:optional true} :keyword]
   [:reason   :string]])

(def AdapterMap
  ;; The substrate adapter spec map per Spec 006 §The reactive-substrate
  ;; adapter contract. Nine entries; see the spec for the contract on each.
  ;; Consumers pass this to (rf/init! adapter-map) — each adapter ns
  ;; (re-frame.adapter.{reagent,uix,helix}, re-frame.ssr,
  ;; re-frame.substrate.plain-atom) exports an `adapter` Var of this shape.
  [:map
   [:make-state-container      fn?]
   [:read-container            fn?]
   [:replace-container!        fn?]
   [:make-derived-value        fn?]
   [:render                    fn?]
   [:render-to-string          fn?]
   [:subscribe-container       {:optional true} fn?]
   [:register-context-provider {:optional true} fn?]
   [:dispose-adapter!          {:optional true} fn?]])

(def RenderOnHeadlessAdapterTags
  [:map
   [:category :keyword]
   [:reason   :string]])

(def NoHiccupEmitterBoundTags
  [:map
   [:category    :keyword]
   [:reason      :string]
   [:render-tree :any]])

(def SanitisedOnProjectionTags
  [:map
   [:category                   :keyword]
   [:projector-id               :keyword]
   [:original-operation         {:optional true} :keyword]
   [:projection-failure-reason  {:optional true} :string]
   [:exception-message          {:optional true} :string]
   [:returned                   {:optional true} :any]
   [:reason                     :string]])

;; --- runtime: flow errors (per [013](013-Flows.md)) ---

(def FlowCycleTags
  [:map
   [:category :keyword]
   [:cycle    [:vector :any]]])

(def FlowMissingIdTags
  [:map
   [:category :keyword]
   [:flow     :map]])

(def FlowBadInputsTags
  [:map
   [:category :keyword]
   [:flow     :map]
   [:reason   :string]])

(def FlowBadOutputTags
  [:map
   [:category :keyword]
   [:flow     :map]
   [:reason   :string]])

(def FlowBadPathTags
  [:map
   [:category :keyword]
   [:flow     :map]
   [:reason   :string]])

;; --- runtime: artefact-missing errors (per MIGRATION §M-31) ---

(def ArtefactMissingTags
  ;; Shared shape for the six artefact-missing categories: flows, ssr,
  ;; routing, schemas, machines, http. Each surfaces as a thrown ex-info
  ;; with this payload; not a trace event.
  [:map
   [:category :keyword]
   [:where    [:or :symbol :string]]
   [:reason   :string]
   ;; per-artefact optional context keys
   [:flow-id    {:optional true} :keyword]
   [:route-id   {:optional true} :keyword]
   [:machine-id {:optional true} :keyword]
   [:path       {:optional true} [:vector :any]]
   [:id         {:optional true} :keyword]
   [:frame      {:optional true} :keyword]])

;; --- runtime: epoch restore errors (per [Tool-Pair §Time-travel]) ---

(def RestoreUnknownEpochTags
  [:map
   [:category     :keyword]
   [:frame        :keyword]
   [:epoch-id     :any]
   [:history-size :int]])

(def RestoreSchemaMismatchTags
  [:map
   [:category               :keyword]
   [:frame                  :keyword]
   [:epoch-id               :any]
   [:schema-digest-recorded :any]
   [:schema-digest-current  :any]
   [:failing-paths          [:vector :any]]])

(def RestoreMissingHandlerTags
  [:map
   [:category :keyword]
   [:frame    :keyword]
   [:epoch-id :any]
   [:missing  [:vector [:map [:kind :keyword] [:id :keyword]]]]])

(def RestoreVersionMismatchTags
  [:map
   [:category         :keyword]
   [:frame            :keyword]
   [:epoch-id         :any]
   [:machine-id       :keyword]
   [:version-recorded :any]
   [:version-current  :any]])

(def RestoreDuringDrainTags
  [:map
   [:category :keyword]
   [:frame    :keyword]
   [:epoch-id :any]])

;; --- rf2-zq55: Tool-Pair §Pair-tool writes — reset-frame-db! ---

(def DbReplacedTags
  ;; :rf.epoch/db-replaced — fired by reset-frame-db! on the success
  ;; path. :op-type :rf.epoch (not :error). Carries the synthetic
  ;; record's epoch-id so consumers can correlate the trace with the
  ;; recorded epoch in epoch-history.
  [:map
   [:frame    :keyword]
   [:epoch-id :any]])

(def ResetFrameDbDuringDrainTags
  ;; :rf.epoch/reset-frame-db-during-drain — failure mode: caller
  ;; invoked reset-frame-db! while the frame's drain was in flight.
  ;; Mirrors RestoreDuringDrainTags' shape (no :epoch-id slot — the
  ;; injection was rejected before any synthetic record was assembled).
  [:map
   [:category :keyword]
   [:frame    :keyword]])

(def ResetFrameDbSchemaMismatchTags
  ;; :rf.epoch/reset-frame-db-schema-mismatch — failure mode: the
  ;; new-db argument failed the frame's currently-registered app-schema
  ;; set. :failing-paths enumerates the paths that did not validate.
  [:map
   [:category      :keyword]
   [:frame         :keyword]
   [:failing-paths [:vector :any]]])

;; --- rf2-d656: Tool-Pair §Surface behaviour against destroyed frames ---

(def EpochCbSilencedOnFrameDestroyTags
  ;; :rf.epoch.cb/silenced-on-frame-destroy — emitted once per
  ;; (frame-id, cb-id) pair when a frame previously observed by a
  ;; register-epoch-cb callback is destroyed. :op-type :rf.epoch.cb (not
  ;; :error). One-shot; subsequent destroys of the same frame do not
  ;; re-emit. The callback registration remains in place; eviction is
  ;; the consumer's call. Per Tool-Pair §Surface behaviour against
  ;; destroyed frames.
  [:map
   [:frame-id :keyword]
   [:cb-id    [:or :keyword :string]]])

;; --- warnings: SSR / authoring-time advisories ---

(def MultipleStatusSetTags
  [:map
   [:category     :keyword]
   [:writes       [:vector :any]]
   [:final-status :any]
   [:frame        {:optional true} :keyword]])

(def MultipleRedirectsTags
  [:map
   [:category       :keyword]
   [:writes         [:vector :any]]
   [:final-redirect :any]
   [:frame          {:optional true} :keyword]])

(def HeadMismatchTags
  [:map
   [:category    :keyword]
   [:server-hash :any]
   [:client-hash :any]
   [:head-id     {:optional true} :keyword]])

(def HydrationMismatchTags
  [:map
   [:category        :keyword]
   [:server-hash     :any]
   [:client-hash     :any]
   [:first-diff-path {:optional true} [:vector :any]]])

(def InterceptorsInMetadataMapTags
  [:map
   [:category       :keyword]
   [:reg-fn         :string]
   [:id             :keyword]
   [:offending-keys [:vector :keyword]]
   [:reason         :string]])

(def PlainFnUnderNonDefaultFrameOnceTags
  [:map
   [:category      :keyword]
   [:fn-name       :string]
   [:rendered-under :keyword]
   [:routed-to     :keyword]])

(def NoClockConfiguredTags
  [:map
   [:category :keyword]
   [:feature  :keyword]
   [:fallback {:optional true} :any]])

(def DecodeDefaultedTags
  [:map
   [:category         :keyword]
   [:request-id       :any]
   [:url              :string]
   [:content-type     {:optional true} :string]
   [:resolved-decoder :keyword]])

(def WriteAfterDestroyTags
  [:map
   [:category :keyword]
   [:reason   :string]])

;; --- info: managed-HTTP retry advisories ---

(def CljsOnlyKeyIgnoredOnJvmTags
  [:map
   [:category :keyword]
   [:key      :keyword]
   [:url      :string]])

(def RetryAttemptTags
  [:map
   [:category        :keyword]
   [:request-id      :any]
   [:url             :string]
   [:attempt         :int]
   [:max-attempts    :int]
   [:failure         :any]              ;; one of the :rf.http/* failure-map shapes
   [:next-backoff-ms {:optional true} [:maybe :int]]])

;; --- info: per-frame HTTP interceptor lifecycle (rf2-6y3q) ---

(def HttpInterceptorRegisteredTags
  [:map
   [:category :keyword]
   [:frame    :keyword]
   [:id       :keyword]])

(def HttpInterceptorClearedTags
  [:map
   [:category :keyword]
   [:frame    :keyword]
   [:id       :keyword]])

;; --- error: a request-interceptor :before threw (rf2-6y3q) ---

(def HttpInterceptorFailedTags
  [:map
   [:category       :keyword]
   [:frame          :keyword]
   [:interceptor-id :keyword]
   [:url            {:optional true} [:maybe :string]]
   [:cause          {:optional true} [:maybe :string]]])

;; --- value schemas for the per-frame request-side interceptor (rf2-6y3q) ---

(def HttpInterceptor
  [:map
   [:frame  {:optional true} :keyword]   ;; defaults to :rf/default
   [:id     :keyword]                    ;; addressable for clear-http-interceptor
   [:before [:=> [:cat :map] :map]]])    ;; (fn [ctx] ctx')

(def HttpInterceptorContext
  [:map
   [:request :map]                       ;; the :request envelope per Spec 014
   [:args    :map]                       ;; the full :rf.http/managed args map
   [:frame   :keyword]
   [:event   {:optional true} [:maybe vector?]]])

;; --- fx-substrate event (warning-shaped per :rf/error-event table) ---

(def FxSkippedOnPlatformTags
  [:map
   [:category             :keyword]
   [:fx-id                :keyword]
   [:fx-args              :any]
   [:frame                {:optional true} :keyword]
   [:platform             :keyword]
   [:registered-platforms [:set :keyword]]])

(def FxHandledTags
  [:map
   [:category :keyword]
   [:fx-id    :keyword]
   [:fx-args  :any]
   [:frame    {:optional true} :keyword]])

;; --- frame-lifecycle event (op-type :event, not error/warning) ---

(def DrainAbortedTags
  [:map
   [:category      [:= :rf.frame/drain-aborted]]
   [:frame         :keyword]
   [:dropped-count :int]])
```

Pattern-level: every implementation registers an equivalent set of schemas. The category vocabulary is fixed-and-additive per [Spec-ulation](Principles.md): existing categories cannot be renamed or removed; new categories appear additively.

The schemas above are *open* (Malli's default `[:map ...]`) — consumers receive payloads that conform to the listed keys plus any additive keys the implementation adds. Validation against these schemas is non-fatal in dev: a `validate` failure is logged via the same trace stream (per [009](009-Instrumentation.md)) but does not abort the consumer. In production, both validation and the trace stream are compile-time elided (per [009](009-Instrumentation.md) lead claim and [Spec 000 C-000.35](000-Vision.md)) — there is no runtime validation cost and no trace emission.

### `:rf/handler-body-dsl`

> **Layer:** Conformance

Conformance-corpus event/sub/view handler bodies are described as data so any host can interpret them without shipping CLJS lambdas. The DSL is a small fixed vocabulary of operations the harness in each host implements. Grammar:

```clojure
(def HandlerBodyOp
  [:or
   ;; --- db / context manipulation ---
   [:tuple [:= :set]               [:vector :any] :any]                  ;; [:set path value]
   [:tuple [:= :update]            [:vector :any] :any]                  ;; [:update path fn-spec]    -- fn-spec is e.g. [:fn :inc]
   [:tuple [:= :merge-into-db]     :map]                                  ;; [:merge-into-db {...}]
   [:tuple [:= :get]               [:vector :any]]                       ;; [:get path]               -- read; used in sub bodies / preds
   ;; --- event / cofx access ---
   [:tuple [:= :event-arg]         :int]                                 ;; [:event-arg N]                     -- the Nth element of the event vector
   [:tuple [:= :event-arg]         :int :any]                            ;; [:event-arg N default]             -- the Nth element; default returned when the element is nil (default-for-nil; never type-dispatched)
   [:tuple [:= :get-event-arg]     :int :keyword]                        ;; [:get-event-arg N :key]            -- (get (nth event N) :key)
   [:tuple [:= :get-event-arg]     :int :keyword :any]                   ;; [:get-event-arg N :key default]    -- key-access with default if missing/nil
   [:tuple [:= :cofx-without]      :keyword]                             ;; [:cofx-without :db]       -- assert :db absent (test fixture)
   ;; --- effects / dispatch ---
   [:tuple [:= :dispatch]          [:vector :any]]                       ;; [:dispatch [:event ...]]
   [:tuple [:= :fx]                [:vector :any]]                       ;; [:fx [[fx-id args] ...]]  -- explicit effect-map :fx slot
   ;; --- view / sub return shapes ---
   [:tuple [:= :hiccup]            [:vector :any]]                       ;; [:hiccup [...]]           -- view body returning hiccup
   ;; --- inline fn references ---
   [:tuple [:= :fn]                :keyword]                             ;; [:fn :inc]                -- look up an interpreter-known fn
   ;; --- control / negative cases ---
   [:tuple [:= :throw]]                                                   ;; [:throw]                   -- error-path fixtures
   [:tuple [:= :noop]]])                                                  ;; [:noop]

(def HandlerBody
  [:vector HandlerBodyOp])
```

The body of a fixture event handler / sub computation is a vector of these ops, executed in order. Semantics:

| Op | Used in | Effect |
|---|---|---|
| `:set` | event-db, event-fx | `(assoc-in db path value)` |
| `:update` | event-db, event-fx | `(update-in db path f)` where `f` resolves a `[:fn :name]` form to a host-built-in fn |
| `:merge-into-db` | event-db, event-fx | `(merge db m)` |
| `:get` | sub, predicate | Read from current `db` — produces the sub's return value |
| `:event-arg` | event, sub | Selects an event-vector argument by index. An optional 3rd element is a **default-for-nil** — returned when the Nth element is `nil`. The 3rd element is never type-dispatched (per rf2-pz9f); for key-access into a map argument use `:get-event-arg`. |
| `:get-event-arg` | event, sub | `(get (nth event N) :key)` — single-key access into the Nth event arg; an optional 4th element is a default returned when the key is missing/nil |
| `:cofx-without` | event-fx | Asserts a cofx key is absent — test fixture |
| `:dispatch` | event-fx | Adds `[:dispatch event]` to the effect-map's `:fx` |
| `:fx` | event-fx | Adds entries to the effect-map's `:fx` directly |
| `:hiccup` | view | Returns the literal hiccup vector as the view's render-tree |
| `:fn` | as a value inside other ops | Names a built-in fn (e.g. `:inc`, `:dec`, `:identity`) |
| `:throw` | any | Throws — exercises `:rf.error/*` paths |
| `:noop` | any | Does nothing — used to anchor empty-body fixtures |

Built-in fns the `[:fn :name]` form resolves to: `:inc`, `:dec`, `:identity`, `:not`, `:keyword?`, `:number?`, `:string?`. Hosts may extend this set additively per a corpus revision.

The op vocabulary is **closed for v1** of the corpus. The conformance corpus's `:fixture/handlers` shape (per [conformance/README.md](conformance/README.md)) consumes this DSL — `:rf/handler-body-dsl` and `:rf/fixture-handler-body` are synonyms; this entry is canonical.

State-machine transition-table guards and actions are referenced by **inline fn or keyword reference into the machine's local `:guards` / `:actions` map** in `:rf/transition-table` below; those are the *runtime* grammar for machine transitions per [005-StateMachines.md](005-StateMachines.md), not part of this conformance handler-body DSL. Transition slots take fn-valued or keyword-valued `:guard` and `:action` slots — keyword values resolve **machine-locally** against the spec's `:guards` / `:actions` map (no global registry); effects emitted by an action — including the reserved fx-id `:raise` and the canonical actor-lifecycle fx-ids `:rf.machine/spawn` / `:rf.machine/destroy` — appear inside the action's returned `:fx` vector.

### `:rf/transition-table`

> **Layer:** Public

Grammar for state-machine transition tables (per [005](005-StateMachines.md)). Public because the user supplies the transition table to `create-machine-handler` as registration data; tools introspect it via `(machine-meta id)`. The v1 foundation (`machine-transition` / `create-machine-handler` and the rest of the machine-as-event-handler surface — see [005 §Disposition](005-StateMachines.md#disposition)) interprets the grammar that maps to the v1 reference's claimed [capability list](005-StateMachines.md#capability-matrix). The CLJS reference claims flat FSM, hierarchical compound states, eventless `:always`, delayed `:after`, and declarative `:invoke`; it does **not** claim parallel regions or history states (substitutes per [005 §Substitutes for skipped features](005-StateMachines.md#substitutes-for-skipped-features)).

The schema below covers the flat FSM grammar, the **hierarchical compound** extension (per [005 §Hierarchical compound states](005-StateMachines.md#hierarchical-compound-states)), the eventless **`:always`** extension (per [005 §Eventless `:always` transitions](005-StateMachines.md#eventless-always-transitions)), the delayed **`:after`** extension (per [005 §Delayed `:after` transitions](005-StateMachines.md#delayed-after-transitions)), and the declarative **`:invoke`** extension (per [005 §Declarative `:invoke` (sugar over spawn)](005-StateMachines.md#declarative-invoke-sugar-over-spawn)). All extensions are additive (open-map invariant) without breaking the `Transition` schema.

```clojure
(def TransitionTable
  [:ref ::state-node])                                                     ;; a TransitionTable IS the root state-node (it just happens to be where :initial / :states begin)

;; A state-node is recursive — a leaf has no :states; a compound state declares
;; :states and MUST declare :initial. Per [005 §Initial-state cascading]
;; (005-StateMachines.md#initial-state-cascading).
;;
;; The :guards and :actions maps are root-only — they declare the machine's
;; named guard / action implementations. Transition-table keyword references
;; (`:guard :foo`, `:action :bar`) resolve **machine-locally** against these
;; maps; there is no global :machine-guard / :machine-action registry. See
;; [005 §Registration](005-StateMachines.md#registration--the-machine-is-the-event-handler)
;; and [005 §Inspectability bias](005-StateMachines.md#inspectability-bias).
(def StateNode
  [:schema {:registry {::state-node
                       [:map
                        [:type    {:optional true}
                         [:enum :single :parallel]]                         ;; root-only — controls how the runtime interprets the spec; absent / :single is the default (flat-or-compound shape disambiguated by whether `:states` declares nested `:states`). `:parallel` switches the spec to parallel-region mode — `:regions` (below) is required and `:states` / `:initial` MUST be absent. Per rf2-l67o (Nine States Stage 2) and [005 §Parallel regions](005-StateMachines.md#parallel-regions).
                        [:regions {:optional true}
                         [:map-of :keyword [:ref ::state-node]]]            ;; root-only — required iff `:type :parallel`. Each entry's value is a full state-node body (its own `:initial` + `:states`, optionally `:tags`, `:on`, etc.). Region names are keywords; region-name → state-tree. All regions are active simultaneously; the snapshot's `:state` is a map of region-name → keyword-or-vector-path. Per rf2-l67o.
                        [:initial {:optional true} :keyword]                ;; required iff :states is present (compound state); points to the cascade entry-point
                        [:states  {:optional true} [:map-of :keyword [:ref ::state-node]]]
                        [:data    {:optional true} :map]                    ;; root-only — initial extended-state data map; ignored on non-root nodes. Per rf2-l67o §9.4 (Shared `:data`): parallel-region machines share one `:data` blob across every region. There is no per-region `:data` slot — apps that need per-region encapsulation register N independent machines (see [CP-5-MachineGuide §Substitutes](CP-5-MachineGuide.md#substitutes-for-skipped-features)).
                        [:guards  {:optional true} [:map-of :keyword fn?]]  ;; root-only — machine-local guard implementations; keys are referenced from :guard slots
                        [:actions {:optional true} [:map-of :keyword fn?]]  ;; root-only — machine-local action implementations; keys are referenced from :action / :entry / :exit slots
                        [:on-spawn-actions {:optional true} [:map-of :keyword fn?]] ;; root-only — optional map of named spawn-callbacks; consulted before :actions when an :on-spawn slot uses a keyword reference. See [005 §Registration](005-StateMachines.md#registration--the-machine-is-the-event-handler).
                        [:entry   {:optional true} ActionRef]               ;; one fn or one keyword reference into the machine's :actions map
                        [:exit    {:optional true} ActionRef]               ;; one fn or one keyword reference into the machine's :actions map
                        [:invoke  {:optional true} InvokeSpec]              ;; declarative spawn-on-entry / destroy-on-exit; at most one per state; see :rf/state-node §:invoke and [005 §Declarative :invoke](005-StateMachines.md#declarative-invoke-sugar-over-spawn)
                        [:invoke-all {:optional true} InvokeAllSpec]        ;; spawn-N-children-and-join sugar; mutually exclusive with :invoke; see :rf/state-node §:invoke-all and [005 §Spawn-and-join via :invoke-all](005-StateMachines.md#spawn-and-join-via-invoke-all)
                        [:always  {:optional true}                          ;; eventless transitions checked after entry (or after any transition landing here); first-match-wins; see [005 §Eventless :always transitions](005-StateMachines.md#eventless-always-transitions)
                         [:vector
                          [:map
                           [:guard  {:optional true} GuardRef]              ;; same shape as :on transition slot; resolves machine-locally against :guards map
                           [:target {:optional true} TransitionTarget]      ;; keyword (sibling of declaring state) or vector (absolute path); same-state same-guard self-loops rejected at registration
                           [:action {:optional true} ActionRef]
                           [:meta   {:optional true} :map]]]]
                        [:after   {:optional true}                          ;; delayed transitions; <delay> → transition spec where <delay> is pos-int? OR a subscription vector ([sub-id & args] resolved through subscribe; re-resolves on subscription change) OR (fn [snapshot] ms) computed at state entry; epoch-based stale detection; SSR no-ops scheduling; see [005 §Delayed :after transitions](005-StateMachines.md#delayed-after-transitions) and [005 §Dynamic delay re-resolution](005-StateMachines.md#dynamic-delay-re-resolution). Per rf2-3y3y.
                         [:map-of
                          [:or pos-int?                                     ;; literal milliseconds (default form)
                               [:vector :any]                               ;; subscription vector — [sub-id & args]; re-resolves on sub change
                               fn?]                                          ;; (fn [snapshot] ms) — local-data-derived delay; computed once at entry
                          [:or :keyword                                     ;; keyword-target sugar — desugars to {:target <kw>} at registration
                               [:map                                        ;; full transition spec — same shape as an :on slot
                                [:guard  {:optional true} GuardRef]
                                [:target {:optional true} TransitionTarget]
                                [:action {:optional true} ActionRef]
                                [:meta   {:optional true} :map]]]]]
                        [:on      {:optional true} EventMap]                ;; event → transition
                        [:tags    {:optional true} [:set :keyword]]         ;; runtime-projected onto snapshot's :tags — see [005 §State tags](005-StateMachines.md#state-tags); union of active-configuration tag sets is stamped at [:rf/machines <id> :tags] on every transition commit. Reserved framework namespace (`:rf/*`, `:rf.*/*`) per Conventions.md §Reserved namespaces. Per rf2-ee0d.
                        [:meta    {:optional true} :map]]}}
   [:ref ::state-node]])

;; The :invoke spec on a state node. Per [005 §Declarative :invoke (sugar over spawn)]
;; (005-StateMachines.md#declarative-invoke-sugar-over-spawn), `create-machine-handler`
;; rewrites this slot into entry/exit actions emitting :rf.machine/spawn / :rf.machine/destroy fx
;; at registration time; the runtime sees only the desugared form. Constraint:
;; **exactly one of :machine-id or :definition** must be supplied — `create-machine-handler`
;; rejects any other shape at registration time as a malformed transition table.
(def InvokeSpec
  [:map
   [:machine-id {:optional true} :keyword]                                  ;; registered machine id
   [:definition {:optional true} [:ref ::state-node]]                       ;; inline transition table (root state-node)
   [:data       {:optional true} [:or :map fn?]]                            ;; literal initial data, OR (fn [snap event] data) computed at entry time
   [:id-prefix  {:optional true} :keyword]                                  ;; defaults to :machine-id; base for the gensym'd actor id
   [:on-spawn   {:optional true} fn?]                                       ;; (fn [data spawned-id] new-data) — how the parent records the child id
   [:start      {:optional true} [:vector :any]]                            ;; event vector dispatched to the newborn after spawn
   [:invoke-id  {:optional true} :keyword]                                  ;; explicit id instead of gensym (per-state singleton actor)
   [:system-id  {:optional true} :keyword]])                                ;; per [005 §Named addressing via :system-id]; binds [:rf/system-ids <sid>] in the spawning frame
;; The pre-rf2-3y3y :timeout-ms / :on-timeout slots are DROPPED — wall-clock timeouts on
;; an :invoke-bearing state are expressed via the parent state's :after slot. See
;; [005 §Wall-clock timeouts on :invoke — use parent state's :after] and
;; [MIGRATION §M-41].

;; The :invoke-all spec on a state node — spawn-N-children-and-join. Per
;; [005 §Spawn-and-join via :invoke-all](005-StateMachines.md#spawn-and-join-via-invoke-all)
;; and rf2-6vmw. `create-machine-handler` walks the spec at construction time
;; and rewrites the slot into entry/exit actions emitting N parallel
;; :rf.machine/spawn fx (entry) and per-child :rf.machine/destroy fx (exit),
;; plus an internal join-state hook that intercepts :on-child-done /
;; :on-child-error events at the parent's handler boundary, updates the
;; runtime-owned join state at [:rf/spawned <parent-id> <invoke-id> :join],
;; and dispatches the resolution event into the parent.
;;
;; Each child invoke-spec extends InvokeSpec with a required :id keyword
;; that names the child for join-state addressing. The :id is the second-
;; position payload arg the parent's :on-child-done / :on-child-error events
;; carry from the child back to the parent.
(def InvokeAllChildSpec
  [:map
   [:id          :keyword]                                                  ;; user-supplied id for join-state addressing — REQUIRED
   [:machine-id  {:optional true} :keyword]                                 ;; registered machine id (xor :definition)
   [:definition  {:optional true} [:ref ::state-node]]                      ;; inline transition table (xor :machine-id)
   [:data        {:optional true} [:or :map fn?]]
   [:id-prefix   {:optional true} :keyword]
   [:on-spawn    {:optional true} [:or :keyword fn?]]
   [:start       {:optional true} [:vector :any]]
   [:invoke-id   {:optional true} :keyword]
   [:system-id   {:optional true} :keyword]])

(def InvokeAllSpec
  [:map
   [:children         [:vector InvokeAllChildSpec]]                         ;; vector of ≥ 1 child spec
   [:join             {:optional true}
                      [:or
                       [:enum :all :any]
                       [:map [:n   pos-int?]]
                       [:map [:fn  fn?]]]]                                  ;; default :all
   [:on-child-done    :keyword]                                             ;; child → parent event keyword (required)
   [:on-child-error   :keyword]                                             ;; child → parent event keyword (required)
   [:on-all-complete  {:optional true} [:vector :any]]                      ;; required iff :join is :all (registration-time check)
   [:on-some-complete {:optional true} [:vector :any]]                      ;; required iff :join is :any / {:n N} / {:fn ...}
   [:on-any-failed    {:optional true} [:vector :any]]                      ;; optional; if absent, child failures don't short-circuit
   [:cancel-on-decision? {:optional true} :boolean]])                       ;; default true
;; The pre-rf2-3y3y :timeout-ms / :on-timeout slots are DROPPED — wall-clock timeouts on
;; an :invoke-all-bearing state are expressed via the parent state's :after slot. See
;; [005 §Wall-clock timeouts on :invoke — use parent state's :after] and
;; [MIGRATION §M-41].

;; The snapshot's location in app-db is the reserved path [:rf/machines <id>]
;; — runtime-managed and not part of the transition-table grammar. See
;; [005 §Where snapshots live](005-StateMachines.md#where-snapshots-live).

;; Event map keys are event ids (keywords) or the wildcard :* (any-event fallback).
;; Values are a single Transition or a vector of Transition candidates evaluated first-match-wins.
(def EventMap
  [:map-of [:or :keyword [:= :*]] [:or Transition [:vector Transition]]])

(def Transition
  [:or
   TransitionTarget                                                         ;; target shorthand — keyword OR vector path; see TransitionTarget below
   [:map
    [:target  {:optional true} TransitionTarget]                            ;; one of: keyword (relative to declaring state), [:vector :keyword] (absolute path from root), or :same-state (external self-transition); omit for internal
    [:guard   {:optional true} GuardRef]                                    ;; one fn or one registered id
    [:action  {:optional true} ActionRef]                                   ;; one fn or one registered id (singular — no :actions vector)
    [:meta    {:optional true} :map]]])

;; A transition's :target admits both forms per [005 §Target resolution]
;; (005-StateMachines.md#target-resolution--vector-vs-keyword).
;;   - keyword form  — relative to the state where the transition is DECLARED (sibling resolution)
;;   - vector form   — absolute path from the root
;; Plus the literal :same-state for external self-transitions.
(def TransitionTarget
  [:or :keyword [:vector :keyword]])

;; Guards are one inline fn or one keyword reference resolved against the
;; machine's local :guards map. No compound data form ({:and ...} / {:or ...}
;; / {:not ...}) — compound logic is fn composition or a named entry in the
;; machine's :guards map (whose name carries semantic content visualisers and
;; AIs read).
(def GuardRef
  [:or :keyword fn?])

;; Actions are one inline fn or one keyword reference resolved against the
;; machine's local :actions map, returning the {:data :fx} effect map. No
;; action-vector form ([a1 a2 a3]) — multi-step actions are fn composition.
;; The action's returned :fx may contain the reserved fx-id :raise (which
;; the machine handler routes locally) and the canonical actor-lifecycle
;; fx-ids :rf.machine/spawn / :rf.machine/destroy (which reach the standard
;; do-fx through :rf.fx/spawn-args below).
(def ActionRef
  [:or :keyword fn?])
```

The recursive `::state-node` ref is registered under the spec id `:rf/state-node` so individual nodes (and slices of a transition table) can be validated in isolation. The `TransitionTarget` schema is registered as `:rf/transition-target`. **Compound states without `:initial`** are a registration error — emits `:rf.error/machine-compound-state-missing-initial` per [005 §Initial-state cascading](005-StateMachines.md#initial-state-cascading).

**Guard / action reference resolution.** A `GuardRef` / `ActionRef` keyword is **machine-local** — it resolves to `(get-in spec [:guards <id>])` / `(get-in spec [:actions <id>])`, where `spec` is the root `::state-node` of the transition table. Resolution is performed at registration time: `create-machine-handler` walks the table (in `:on`, `:always`, `:entry`, `:exit` slots) and verifies each keyword reference resolves to a fn in the spec's `:guards` / `:actions` map. Unresolved references fail registration with `:rf.error/machine-unresolved-guard` (with `:tags {:guard-id <id> :machine-id <id>}`) or `:rf.error/machine-unresolved-action` (with `:tags {:action-id <id> :machine-id <id>}`). There is **no global guard/action registry** — each machine has its own `:guards` / `:actions` namespace. Cross-machine reuse is via Clojure vars referenced from each machine's map.

**`:invoke` constraint.** The `:invoke` slot's `InvokeSpec` declares both `:machine-id` and `:definition` as optional, but **exactly one** must be supplied for any actual `:invoke` slot — Malli alone cannot express the xor without a richer combinator, so `create-machine-handler` enforces it at registration time and rejects malformed slots as a transition-table error. `:invoke` is registration-time sugar — see [005 §Declarative `:invoke` (sugar over spawn)](005-StateMachines.md#declarative-invoke-sugar-over-spawn) for the desugaring rules; the runtime never sees an `:invoke` key at transition time.

**`:type :parallel` constraint.** A root state-node declaring `:type :parallel` MUST declare a non-empty `:regions` map and MUST NOT declare `:initial` or `:states` — those slots are mutually exclusive with `:regions`. Each region's value is itself a full `::state-node` body (its own `:initial` + `:states` for the compound case, or no `:states` for a flat region). `create-machine-handler` validates the shape at registration time and rejects malformed declarations with `:rf.error/machine-parallel-bad-shape`. Nested parallel regions (a region whose own state-tree contains another `:type :parallel`) are not supported in v1; the validator rejects them with `:rf.error/machine-parallel-nested-not-supported`. Per rf2-l67o (Nine States Stage 2) and [005 §Parallel regions](005-StateMachines.md#parallel-regions).

**`:timeout-ms` removed.** Per rf2-3y3y, the pre-release `:timeout-ms` / `:on-timeout` slots on `:invoke` / `:invoke-all` are DROPPED. State-level `:after` on the parent state subsumes the wall-clock guard, with the standard exit-cascade destroying spawned children. `create-machine-handler` rejects any `:timeout-ms` or `:on-timeout` key on either slot at registration time with `:rf.error/invoke-timeout-ms-removed`. The retired error categories `:rf.error/machine-invoke-timeout-without-on-timeout`, `:rf.error/machine-invoke-on-timeout-without-timeout`, and `:rf.error/machine-invoke-timeout-not-positive` are no longer emitted. See [005 §Wall-clock timeouts on `:invoke` — use parent state's `:after`](005-StateMachines.md#wall-clock-timeouts-on-invoke--use-parent-states-after) and [MIGRATION §M-41](MIGRATION.md#m-41-timeout-ms-removed-from-invoke--invoke-all-on-invoke--use-parent-states-after).

**`:always` constraints.** The `:always` slot is checked at registration time for two registration-error categories:

- **`:rf.error/machine-always-self-loop`** — an `:always` entry whose `:target` resolves to the declaring state itself with the same `:guard` reference (or no guard) is rejected at registration time, with `:tags {:state <state-keyword> :machine-id <id>}`. Same-state same-guard self-loops would either spin to depth-exceeded or be a no-op; in both cases the author meant something else. Self-targeting `:always` with a *different* guard is permitted (re-entry on a changed condition). See [005 §Self-loop forbidden at registration](005-StateMachines.md#self-loop-forbidden-at-registration).

A second `:always`-related category, **`:rf.error/machine-always-depth-exceeded`**, is a *runtime* error (not registration): emitted when the microstep loop exceeds its depth limit (default 16), with `:tags {:machine-id <id> :depth <limit> :path [<state> ...]}` and `:recovery :no-recovery`. The cascade halts with the snapshot uncommitted. See [005 §Bounded depth](005-StateMachines.md#bounded-depth).

**`:after` constraints.** Per [005 §Delayed `:after` transitions](005-StateMachines.md#delayed-after-transitions), the `:after` slot's value is a map whose keys are one of three forms — positive-integer millisecond delays, **subscription vectors** (`[:sub-id & args]` resolved through `subscribe`'s machinery; re-resolves on subscription change per [005 §Dynamic delay re-resolution](005-StateMachines.md#dynamic-delay-re-resolution)), or fns of the entering snapshot returning a positive integer — and whose values are either keyword-target sugar (`{5000 :timeout}`) or a full transition spec (`{5000 {:guard :still-loading? :target :hard-error}}`). Sugar normalises at registration time. Cancellation is not a separate fx — staleness is detected via an **epoch counter** stored in `:data` under the reserved key `:rf/after-epoch` (the `:rf/`-namespace within `:data` is reserved for runtime-managed bookkeeping). The clock primitives live in [`re-frame.interop`](002-Frames.md#interop-layer--clock-primitives--see-spec-005) (`now-ms`, `schedule-after!`, `cancel-scheduled!`); tests swap the interop layer rather than configuring a framework-level clock. Hosts whose interop layer hasn't been wired with a clock emit **`:rf.warning/no-clock-configured`** when `:after` is exercised — an advisory-not-fatal: the runtime falls back to a host-native clock if available. Trace events: `:rf.machine.timer/scheduled`, `:rf.machine.timer/fired`, `:rf.machine.timer/stale-after`, `:rf.machine.timer/cancelled-on-resolution`, `:rf.machine.timer/skipped-on-server` (added to the trace-op vocabulary above). Per rf2-3y3y.

### `:rf/machine-snapshot`

> **Layer:** Runtime

The runtime snapshot of a machine instance. Per [005 §Snapshot shape](005-StateMachines.md#snapshot-shape), every conformant snapshot is print/read round-trippable so it survives the wire (SSR hydration, [011](011-SSR.md)) and the time-axis (Tool-Pair epoch replay).

```clojure
(def MachineSnapshot
  [:map
   ;; :state has THREE arms — disambiguated by the machine's declared shape:
   ;;   - keyword                       for flat machines (e.g. :idle)
   ;;   - [:vector :keyword]            for compound machines — root → active leaf path (e.g. [:authenticated :cart :browsing])
   ;;   - [:map-of :keyword <region-state>]
   ;;                                   for parallel-region machines (`:type :parallel`) — region-name → that region's keyword-or-vector-path. Per rf2-l67o (Nine States Stage 2).
   ;; Implementations accept all three forms on read and may normalise the compound
   ;; arm to vector internally. Per [005 §Snapshot shape](005-StateMachines.md#snapshot-shape).
   [:state    [:multi {:dispatch (fn [v] (cond (keyword? v) :flat
                                                (vector? v)  :compound
                                                (map? v)     :parallel))}
               [:flat     :keyword]
               [:compound [:vector :keyword]]
               ;; Region values are themselves a flat keyword or a compound path —
               ;; each region runs an independent state-tree. Nested parallel regions
               ;; are not supported in v1; a region's state value cannot itself be a map.
               [:parallel [:map-of :keyword [:or :keyword [:vector :keyword]]]]]]
   [:data     {:optional true} :map]                                       ;; the machine's extended state; closed under print/read
   ;; :tags is the runtime-projected union of every active state-node's
   ;; `:tags` set; recomputed on every transition commit. Optional —
   ;; implementations MAY elide the key when the union is empty (per
   ;; [005 §State tags §Snapshot shape change]
   ;; (005-StateMachines.md#snapshot-shape-change)). Per rf2-ee0d.
   [:tags     {:optional true} [:set :keyword]]
   [:meta     {:optional true}
    [:map
     [:rf/snapshot-version {:optional true} :int]                          ;; bumped when definition shape changes incompatibly
     ]]])                                                                   ;; remaining :meta keys are user-defined and tolerated
```

Stability invariants the implementation upholds (see [005 §Snapshot shape](005-StateMachines.md#snapshot-shape)):

1. `(read-string (pr-str snapshot))` returns an `=`-equal value — no functions, atoms, JS objects in `:data` (or `:tags` — but `:tags` is a set of keywords, both of which are EDN-clean).
2. Snapshots represent committed state only; no in-flight microstate is captured.
3. Hot-reloading a definition does not invalidate snapshots whose `:state` is still a member.
4. `:rf/snapshot-version` mismatch between snapshot and definition emits `:rf.warning/machine-snapshot-version-mismatch`.
5. `:tags` is **read-only** for users — actions cannot return `:tags` in their `{:data :fx}` effect map; the runtime owns the slot and recomputes it from `:state` at every commit.

**Effect-map note.** A machine handler returns a standard `:rf/effect-map` (`:db` + `:fx`). The action-internal `{:data :fx}` shape is *internal* to the machine handler; the handler lowers `:data` to a single `:db` write at `[:rf/machines <id> :data]` before returning. The closed `:rf/effect-map` contract (`:db` + `:fx` only) is preserved at the handler boundary.

### `:rf/machines` (reserved app-db key)

> **Layer:** Runtime

`[:rf/machines]` is a **reserved key in every frame's `app-db`**. The runtime owns it; user code MUST NOT write under it. Per [005 §Where snapshots live](005-StateMachines.md#where-snapshots-live), every machine's snapshot lives at `[:rf/machines <machine-id>]` — the location is fixed and is not part of any user-supplied spec.

```clojure
(def Machines
  [:map-of :keyword :rf/machine-snapshot])

;; registered by the runtime at boot:
(rf/reg-app-schema [:rf/machines] Machines)
```

Each registered machine contributes one entry. The runtime composes `Machines`'s schema additively from the registered machines' declared `:data` shapes (per machine: refine `:rf/machine-snapshot` with the machine's `:state` enum and `:data` schema). Per-frame isolation is automatic — each frame's `app-db` has its own `:rf/machines` map; the same machine id can exist in multiple frames without collision.

Cross-reference: `:rf/machine-snapshot` (above) is the value type for each entry in `:rf/machines`.

### `:rf/spawned` (reserved app-db key)

> **Layer:** Runtime

`[:rf/spawned]` is a **reserved key in every frame's `app-db`**. The runtime owns it; user code MUST NOT write under it. Per [005 §Declarative `:invoke` (sugar over spawn)](005-StateMachines.md#declarative-invoke-sugar-over-spawn) and rf2-t07u (Option A revised), the runtime tracks each declarative-`:invoke` spawn at `[:rf/spawned <parent-machine-id> <invoke-id>]` so the matching destroy cascade can locate the spawned id without depending on the user's `:on-spawn` callback having stashed it under any particular `:data` slot.

```clojure
;; Per-invoke slot — either a single spawned-id keyword (for ordinary :invoke)
;; OR a join-bookkeeping map (for :invoke-all per rf2-6vmw):
;;   {:children    {<child-id> <spawned-id>, ...}      ;; N children
;;    :done        #{<child-id> ...}                   ;; user-ids that signalled :on-child-done
;;    :failed      #{<child-id> ...}                   ;; user-ids that signalled :on-child-error
;;    :resolved?   true|false                          ;; latch flips once the join condition resolves
;;    :spec        <invoke-all-spec>}                  ;; back-reference for the join intercept
;; Reads at the destroy-resolution call site disambiguate by value type:
;; keyword → :invoke leaf actor address; map → :invoke-all bookkeeping.
(def InvokeAllJoinState
  [:map
   [:children  [:map-of :keyword :keyword]]                                 ;; child-id → spawned-id
   [:done      [:set :keyword]]
   [:failed    [:set :keyword]]
   [:resolved? :boolean]
   [:spec      :map]])

(def Spawned
  ;; A two-level map: parent-machine-id → invoke-id → (spawned-id | join-state).
  ;; The invoke-id is the absolute prefix-path of the :invoke-bearing
  ;; state node — a vector of keywords (e.g. [:authenticating],
  ;; [:cart :loading]). Two states named `:loading` in different parents
  ;; are disambiguated by their full prefix-paths.
  [:map-of :keyword                                                          ;; parent-machine-id
           [:map-of [:vector :keyword]                                       ;; invoke-id
                    [:or :keyword InvokeAllJoinState]]])                     ;; spawned-id (:invoke) | join-state (:invoke-all)

;; registered by the runtime at boot:
(rf/reg-app-schema [:rf/spawned] Spawned)
```

Allocated lazily — absent until the first declarative-`:invoke` (or `:invoke-all`) spawn binds a slot, and pruned to absent again when the last slot is cleared (sibling lazy-allocation invariant to `[:rf/system-ids]`). Imperative from-action `[:rf.machine/spawn ...]` calls (where the user owns the destroy via hand-emitted `[:rf.machine/destroy actor-id]`) leave the slot untouched.

Per-frame isolation is automatic — each frame's `app-db` has its own `:rf/spawned` map; same parent-id + invoke-id in different frames do not collide. Frame revertibility is inherited (the slot walks back atomically with `app-db` on a frame revert).

### `:rf/route-pattern`

> **Layer:** Public

The canonical **path-pattern grammar** for `reg-route`'s `:path` value. Per [012 §Path-pattern grammar](012-Routing.md#path-pattern-grammar-canonical), this is the wire-form every conforming implementation parses and emits.

```clojure
(def RoutePattern
  ;; The shape is a string — the schema below is descriptive (a regex constraint), not structural.
  ;; A formal data-form grammar (vector-of-segments) is post-v1; this string
  ;; form is the v1 contract.
  [:and :string
   [:re #"^(?:/|(?:/(?:[^:*{}/?][^/?{}]*|:[a-zA-Z][a-zA-Z0-9_-]*|\*[a-zA-Z][a-zA-Z0-9_-]*|\{/[^/{}]+\}\?))+/?)$"]])
```

Productions (per 012):

| Token | Meaning |
|---|---|
| `/` (root) | Root pattern. |
| `/literal` | Literal segment. |
| `/:name` | Named param segment. |
| `{/:name}?` or `{/literal}?` | Optional segment group; final group only; not nested. |
| `/*name` | Catch-all (splat); must be the final segment; at most one per pattern. |

Implementations register this schema via `reg-app-schema [:rf/route-pattern]` so a route's `:path` value can be validated at registration time and the conformance harness can lint route tables.

### `:rf/route-rank`

> **Layer:** Runtime

The structural-rank tuple `match-url` computes for each registered route, per [012 §Route ranking algorithm](012-Routing.md#route-ranking-algorithm). Registrars attach the computed rank under `:rf.route/rank` on the route's metadata so tooling can read it via `(rf/handler-meta :route route-id)` and so AI scaffolds can render the precedence cascade without re-parsing patterns.

```clojure
(def RouteRank
  ;; A vector of integers, lexicographically comparable. Higher = more specific.
  ;; The registrar's stable-sort by registration time provides rule 6.
  [:tuple :int                                                            ;; rule 1 — static-segment count
          :int                                                            ;; rule 2 — total segment count
          [:enum 0 1]                                                     ;; rule 3 — splat? 0 = has splat; 1 = no splat (named params win)
          [:enum 0 1]                                                     ;; rule 4 — catch-all? 0 = is "/*"; 1 = otherwise
          [:enum 0 1]])                                                   ;; rule 5 — has optional group? 0 = yes; 1 = no
```

Implementations rank candidates by descending `route-rank` then by ascending registration time (stable sort). Equal-score candidates emit `:rf.warning/route-shadowed-by-equal-score` at registration time per [API.md §Error contract](API.md#error-contract); the warning's `:tags` carry `{:route-id <new> :shadowed <existing> :rank <RouteRank>}`.

### `:rf/route-slice`

> **Layer:** Runtime

The shape of `app-db`'s `:rf/route` slice, per [012 §The `:rf/route` slice](012-Routing.md#the-rfroute-slice).

```clojure
(def RouteSlice
  [:map
   [:id          :keyword]                                                 ;; current route id (e.g. :route/cart)
   [:params      {:optional true} :map]                                    ;; path params (matches the route's :params schema)
   [:query       {:optional true} :map]                                    ;; query params (matches the route's :query schema; includes :query-defaults)
   [:fragment    {:optional true} [:maybe :string]]                        ;; URL fragment (#section); nil when absent. Per [012 §Fragments](012-Routing.md#fragments).
   [:transition  {:optional true} [:enum :idle :loading :error]]           ;; navigation transition state
   [:error       {:optional true} :any]                                    ;; populated when :transition = :error; conforms to :rf/error per 009
   [:nav-token   {:optional true} :any]])                                  ;; per-navigation epoch token; per [012 §Navigation tokens](012-Routing.md#navigation-tokens--stale-result-suppression)
```

Open shape — implementations may add `:rf.route/...`-namespaced keys (e.g., the runtime's saved scroll-position cache might surface a `:rf.route/saved-scroll` key, opt-in).

### `:rf/route-metadata`

> **Layer:** Public

The shape of the metadata map passed to `reg-route`. Reserved keys per [012 §Reserved route-metadata keys](012-Routing.md#reserved-route-metadata-keys).

```clojure
(def RouteMetadata
  [:map
   [:doc             {:optional true} :string]
   [:path            :string]                                              ;; conforms to :rf/route-pattern
   [:params          {:optional true} :any]                                ;; Malli schema for path params
   [:query           {:optional true} :any]                                ;; Malli schema for query/search params
   [:query-defaults  {:optional true} [:map-of :keyword :any]]             ;; defaults for absent query keys
   [:query-retain    {:optional true} [:set :keyword]]                     ;; query keys carried through subsequent navigations
   [:tags            {:optional true} [:set :keyword]]
   [:parent          {:optional true} :keyword]                            ;; parent route id; used by :rf.route/chain sub
   [:on-match        {:optional true} [:vector [:vector :any]]]            ;; events to dispatch when this route becomes active
   [:on-error        {:optional true} [:vector :any]]                      ;; event to dispatch if any :on-match event errors
   [:can-leave       {:optional true} :keyword]                            ;; sub-id; (subscribe [<sub-id>]) returns boolean — true means "OK to leave". Per [012 §Navigation blocking](012-Routing.md#navigation-blocking--pending-nav-protocol).
   [:scroll          {:optional true} [:or
                                       [:enum :top :restore :preserve]
                                       :map]]])                            ;; map form is post-v1 / host-extensible
```

Per-host extension keys (`:myapp/...`, `:rf.tooling/...`) are tolerated — RouteMetadata composes with `:rf/registration-metadata`'s open shape.

### `:rf/pending-navigation`

> **Layer:** Runtime

The shape of `app-db`'s `:rf/pending-navigation` slot, set by the runtime when a navigation is blocked by a `:can-leave` guard. Per [012 §Navigation blocking](012-Routing.md#navigation-blocking--pending-nav-protocol).

```clojure
(def PendingNavigation
  [:map
   [:id                  :string]                                         ;; opaque pending-nav id (gensym); used by :rf.route/continue / :rf.route/cancel
   [:requested-by-event  [:vector :any]]                                  ;; the original :rf/url-requested or :rf.route/navigate event vector
   [:requested-url       :string]                                         ;; the URL the user was trying to reach
   [:reason              {:optional true} :string]                        ;; human-readable explanation for the dialog
   [:rejecting-route     :keyword]                                        ;; the route id whose :can-leave guard rejected
   [:rejecting-guard     {:optional true} :keyword]])                     ;; the sub-id of the rejecting guard (for tooling)
```

The slot is `nil` (or absent) when no navigation is pending. Cleared by `:rf.route/continue` (the navigation completes) or `:rf.route/cancel` (the navigation is abandoned). Open map — implementations may attach `:rf.route/...`-namespaced metadata; user code reads via `(subscribe [:rf/pending-navigation])`.

### `:rf.fx/with-nav-token-args`

> **Layer:** Runtime

Args of the framework-supplied `:rf.route/with-nav-token` fx wrapper, per [012 §Navigation tokens](012-Routing.md#navigation-tokens--stale-result-suppression). Threads the current `:nav-token` into a wrapped follow-up dispatch so the receiving handler can detect stale results.

```clojure
(def WithNavTokenFxArgs
  [:map
   [:do        [:vector :any]]                                            ;; an fx entry to perform — typically [:dispatch [<event-id> args ...]]
   [:nav-token :any]])                                                    ;; the token captured at scheduling time (gensym or counter)
```

Registered under spec id `:rf.fx/with-nav-token-args`. The wrapped fx receives the carried token in cofx; on receipt, the framework-provided `:nav-token` cofx checks the carried token against the current `:rf/route` slice's `:nav-token`. Mismatch → suppress + emit `:route.nav-token/stale-suppressed` trace.

### `:rf/hydration-payload`

> **Layer:** Runtime

Per [011 §The `:rf/hydrate` event](011-SSR.md#the-rfhydrate-event). The **canonical** shape of the data crossing the wire from server to client. **v1 and post-v1 are kept separate**: the v1 schema below carries only v1 keys; the post-v1 extension is a separate schema that *refines* the v1 shape. v1 implementations emit and consume exactly the v1 shape; post-v1 keys in payloads from a future server are tolerated (open map) but ignored on a v1 client.

**`:rf/hydration-payload` (v1):**

```clojure
(def HydrationPayload
  [:map
   [:rf/version       :int]                                                ;; pattern-protocol version (integer; v1 = 1)
   [:rf/frame-id      :keyword]                                            ;; the frame id to seed
   [:rf/app-db        :any]                                                ;; serialised app-db (authoritative)
   [:rf/ssr-rendered-at {:optional true} :int]                             ;; ms-since-epoch the server completed render
   [:rf/route         {:optional true}                                     ;; the matched route slice the server resolved
                                       [:map
                                        [:id     :keyword]
                                        [:params {:optional true} :map]
                                        [:query  {:optional true} :map]]]
   [:rf/render-hash   {:optional true} :string]                            ;; structural hash of the server-rendered render-tree, for mismatch detection. Covers both body and head — the runtime emits a single `:rf.ssr/hydration-mismatch` and discriminates head-vs-body via the `:failing-id` tag (per [011 §Hydration-mismatch detection](011-SSR.md#hydration-mismatch-detection)). The head-hash surface (a separate `:rf/head-hash` key) is reserved for the post-v1 `reg-head` payload extension and not part of the v1 wire.
   [:rf/schema-digest {:optional true} :string]                            ;; hash of the server's registered app-schema set (per [010-Schemas.md](010-Schemas.md))
   ])
```

**`:rf/hydration-payload-postv1` (post-v1 extension):**

```clojure
;; Reserved for a future re-frame2.x. Keys appear additively on top of v1.
;; v1 implementations tolerate these on the wire but do not emit or consume them.
(def HydrationPayloadPostV1
  [:merge
   HydrationPayload
   [:map
    [:rf/machine-snapshots {:optional true} [:map-of :keyword :rf/machine-snapshot]]  ;; per-machine snapshots keyed by machine-id; mirrors the in-app-db [:rf/machines] map
    [:rf/sub-warmups       {:optional true} [:map-of [:vector :any] :any]]            ;; pre-computed sub values (per [011-SSR.md](011-SSR.md))
    ]])
```

The split between v1 and post-v1 keeps the v1 contract auditable: a v1 conformance harness validates against `HydrationPayload` exactly; the post-v1 extension is a separate schema users opt into when they upgrade. The `:rf/version` integer increments when the post-v1 schema becomes the v2 contract.

**Merge policy:** the standard `:rf/hydrate` handler **replaces** the frame's `app-db` with `(:rf/app-db payload)`. Server is authoritative for the initial client state. See [011 §The `:rf/hydrate` event](011-SSR.md#the-rfhydrate-event) for the transient-client-state pattern (seed before hydrate via `:on-create`; the replace clobbers it; if the user wants seeded transient client state to survive, the handler is opt-in customisable via re-registration of `:rf/hydrate`).

**Why integer `:rf/version` (not string):** integer comparison is cheaper for tools and hosts to do compatibility checks against; pattern-protocol versions are monotonic increments (1, 2, ...) rather than semver-style strings.

### `:rf/response`

> **Layer:** Runtime

The HTTP-response accumulator owned by the request frame during SSR. Per [011 §HTTP response contract](011-SSR.md#http-response-contract). Populated during the drain by the standard `:rf.server/*` fx; consumed by the host adapter to build the wire response.

```clojure
(def Response
  [:map
   [:status   {:optional true} :int]                                       ;; default 200 if no fx sets it
   [:headers  {:optional true} [:vector [:tuple :string :string]]]         ;; ordered [name value] pairs; case-insensitive name match
   [:cookies  {:optional true} [:vector [:ref :rf.server/cookie]]]         ;; structured cookies (per :rf.server/cookie below)
   [:redirect {:optional true} [:maybe [:map
                                        [:status   {:optional true} :int] ;; default 302
                                        [:location :string]]]]
   [:content-type {:optional true} :string]])                              ;; convenience accessor; mirrors headers' "content-type"
```

Open shape — implementations may attach `:rf.response/...`-namespaced keys (e.g., `:rf.response/cache-tag`) without breaking consumers. The canonical six fx (`:rf.server/set-status`, `:rf.server/set-header`, `:rf.server/append-header`, `:rf.server/set-cookie`, `:rf.server/delete-cookie`, `:rf.server/redirect`) write only the four canonical keys.

### `:rf.server/cookie`

> **Layer:** Runtime

The structured-cookie shape that `:rf.server/set-cookie` and `:rf.server/delete-cookie` produce. Per [011 §Cookie shape](011-SSR.md#cookie-shape). The host adapter serialises this to a `Set-Cookie:` header per RFC 6265.

```clojure
(def Cookie
  [:map
   [:name      :string]
   [:value     :string]
   [:max-age   {:optional true} :int]
   [:expires   {:optional true} :int]                                      ;; ms-since-epoch; alternative to :max-age
   [:secure    {:optional true} :boolean]
   [:http-only {:optional true} :boolean]
   [:same-site {:optional true} [:enum :strict :lax :none]]
   [:path      {:optional true} :string]
   [:domain    {:optional true} :string]])
```

Either `:max-age` or `:expires` may be supplied (or neither — session cookie). User code does not build wire strings.

### `:rf/head-model`

> **Layer:** Runtime

The data model for SSR head/meta content. Per [011 §Head/meta contract](011-SSR.md#headmeta-contract). Pure data; the runtime emits `<head>...</head>` from this map in canonical key order.

```clojure
(def HeadModel
  [:map
   [:title      {:optional true} :string]
   [:meta       {:optional true} [:vector [:map-of :keyword [:or :string :int :boolean]]]]
   [:link       {:optional true} [:vector [:map-of :keyword :string]]]
   [:script     {:optional true} [:vector [:map-of :keyword [:or :string :int :boolean]]]]
   [:json-ld    {:optional true} [:vector :map]]                           ;; structured-data objects (raw maps, serialised as application/ld+json)
   [:html-attrs {:optional true} [:map-of :keyword :string]]               ;; attributes on <html>
   [:body-attrs {:optional true} [:map-of :keyword :string]]])             ;; attributes on <body>
```

The shape is **open** — implementations may add `:rf.head/...`-namespaced keys (e.g., `:rf.head/preload` for resource hints) without breaking consumers.

### `:rf/public-error`

> **Layer:** Runtime

The sanitised, client-safe projection of an internal error trace event. Per [011 §Server error projection](011-SSR.md#server-error-projection). The error projector consumes a `:rf/error-event` (per [009 §Error event shape](009-Instrumentation.md#the-error-event-shape)) and returns this shape.

```clojure
(def PublicError
  [:map {:closed true}                                                     ;; closed in prod — extra keys are a leak risk
   [:status     :int]
   [:code       :keyword]                                                  ;; stable category (:not-found :bad-request :unauthorised :internal-error ...)
   [:message    :string]                                                   ;; one-sentence human-facing
   [:retryable? :boolean]
   [:details    {:optional true} :any]])                                   ;; dev-only; the full trace event for developer view
```

The map is **closed** — production must not silently leak unknown keys. The `:details` key is dev-only (gated by `:dev-error-detail?`); production builds elide it.

### Standard fx args schemas

> **Layer:** Runtime

The `:rf/effect-map`'s `:fx` is `[[fx-id args] ...]`. Each *standard* `fx-id` (the ones the runtime / standard libraries register) has a known args shape; this section registers them so the conformance corpus and AI scaffolding can validate fx-args at the call site. User-registered fx attach their own args schema via the `:spec` metadata on `reg-fx` (per [010 §Where schemas attach](010-Schemas.md#where-schemas-attach)).

```clojure
;; :dispatch — dispatches another event in the same frame
(def DispatchFxArgs
  [:vector :any])                                                          ;; an event vector

;; :dispatch-later — schedules a delayed dispatch (or dispatches a vector of them)
(def DispatchLaterFxArgs
  [:or
   [:map
    [:ms       :int]                                                        ;; non-negative
    [:dispatch [:vector :any]]]
   [:vector
    [:map
     [:ms       :int]
     [:dispatch [:vector :any]]]]])

;; :http — pattern-level HTTP fx (per Pattern-RemoteData). Args are user-supplied;
;; the framework treats them opaquely. Schema is recommendation, not contract.
(def HttpFxArgs
  [:map
   [:method  [:enum :get :post :put :patch :delete :head :options]]
   [:url     :string]
   [:body         {:optional true} :any]
   [:headers      {:optional true} [:map-of :keyword :string]]
   [:on-success   {:optional true} [:vector :any]]
   [:on-error     {:optional true} [:vector :any]]])

;; :rf.nav/push-url — navigate to a URL via history.pushState. Client only.
(def NavPushUrlFxArgs :string)

;; :rf.nav/replace-url — replace history entry via history.replaceState. Client only.
(def NavReplaceUrlFxArgs :string)

;; :rf.nav/scroll — scroll-on-navigate. Client only. Per [012 §Scroll restoration].
(def NavScrollFxArgs
  [:map
   [:strategy  [:or
                [:enum :top :restore :preserve]
                :map]]                                                       ;; map form is host-extensible (post-v1)
   [:from      {:optional true} [:map [:id :keyword] [:params {:optional true} :map] [:query {:optional true} :map]]]
   [:to        {:optional true} [:map [:id :keyword] [:params {:optional true} :map] [:query {:optional true} :map]]]
   [:saved-pos {:optional true} [:tuple :int :int]]])                        ;; runtime-captured saved position (for :restore)

;; :rf.machine/spawn — canonical actor-lifecycle fx-id (registered globally by
;; re-frame.machines); usable inside any event handler's :fx (machine actions
;; and ordinary handlers alike) to spawn a dynamic actor. Per
;; [005 §Spawning](005-StateMachines.md#spawning--dynamic-actors). The :raise
;; reserved fx-id (machine-internal, routed by the machine handler) takes a
;; bare event vector — same shape as :dispatch — and so does not need its own
;; args schema.
(def SpawnFxArgs
  [:map
   ;; one of :machine-id (registered) or :definition (inline transition table)
   [:machine-id    {:optional true} :keyword]
   [:definition    {:optional true} :any]                                   ;; an inline TransitionTable
   [:id-prefix     {:optional true} :keyword]                               ;; defaults to :machine-id; base for the gensym'd actor id
   [:data          {:optional true} :map]                                   ;; initial data; overrides definition default
   [:on-spawn      {:optional true} fn?]                                    ;; (fn [data id] new-data) — advisory user-side bookkeeping per rf2-t07u
   [:start         {:optional true} [:vector :any]]                         ;; event vector dispatched to the new actor immediately after spawn
   [:system-id     {:optional true} :keyword]                               ;; per [005 §Named addressing via :system-id]; binds [:rf/system-ids <sid>] in the spawning frame
   ;; Runtime-stamped on declarative-:invoke spawns (per rf2-t07u; not user-supplied).
   ;; The pair addresses the runtime-owned spawn registry slot at
   ;; [:rf/spawned <parent-id> <invoke-id>]; absent on imperative from-action
   ;; spawns (those user-owned destroys are still hand-emitted with the actor id).
   [:rf/parent-id  {:optional true} :keyword]                               ;; parent machine's registration-id
   [:rf/invoke-id  {:optional true} [:vector :keyword]]                     ;; absolute prefix-path of the :invoke-bearing state node
   [:rf/spawned-id {:optional true} :keyword]])                             ;; resolved gensym'd id, threaded through so spawn-fx registers under the same id :on-spawn observed (rf2-suue)

;; The spawned actor's snapshot lives at [:rf/machines <gensym'd-id>] in the
;; active frame's app-db — runtime-managed; not part of the spawn-spec.

;; :rf.machine/destroy — canonical actor-destroy fx-id (registered globally
;; by re-frame.machines); usable inside any event handler's :fx (machine
;; actions and ordinary handlers alike) to tear down a dynamic actor. Per
;; [005 §Spawning] and rf2-t07u (Option A revised). Two argument shapes:
;;   - a bare actor-id keyword — the legacy / imperative form (action emits
;;     `[:rf.machine/destroy actor-id]` with the recorded id directly).
;;   - a `{:rf/parent-id :rf/invoke-id}` map — the declarative-:invoke
;;     exit-cascade form. The fx handler reads the spawned id back from
;;     `[:rf/spawned <parent-id> <invoke-id>]` at call time and tears down
;;     whatever id is currently bound there.
(def DestroyMachineFxArgs
  [:or :keyword
       [:map
        [:rf/parent-id :keyword]
        [:rf/invoke-id [:vector :keyword]]]])

;; --- :rf.server/* fx — HTTP response contract per [011 §HTTP response contract] ---

;; :rf.server/set-status — set the response status code
(def SetStatusFxArgs :int)                                                 ;; e.g. 200 / 404 / 500

;; :rf.server/set-header — replace a header (case-insensitive name match)
(def SetHeaderFxArgs
  [:map
   [:name  :string]
   [:value :string]])

;; :rf.server/append-header — add another instance of a (possibly multi-valued) header
(def AppendHeaderFxArgs
  [:map
   [:name  :string]
   [:value :string]])

;; :rf.server/set-cookie — args is the :rf.server/cookie shape
(def SetCookieFxArgs
  [:ref :rf.server/cookie])

;; :rf.server/delete-cookie — clear a named cookie at a path/domain
(def DeleteCookieFxArgs
  [:map
   [:name   :string]
   [:path   {:optional true} :string]
   [:domain {:optional true} :string]])

;; :rf.server/redirect — set status (default 302) and Location; truncates HTML body
(def RedirectFxArgs
  [:map
   [:status   {:optional true} :int]                                       ;; default 302
   [:location :string]])
```

These are registered under spec ids:

| Spec id | Args of fx |
|---|---|
| `:rf.fx/dispatch-args` | `:dispatch` (and `:raise`, which takes the same event-vector shape) |
| `:rf.fx/dispatch-later-args` | `:dispatch-later` |
| `:rf.fx/http-args` | `:http` (recommendation; user-owned) |
| `:rf.fx/nav/push-url-args` | `:rf.nav/push-url` (per [012](012-Routing.md)) |
| `:rf.fx/nav/replace-url-args` | `:rf.nav/replace-url` |
| `:rf.fx/nav/scroll-args` | `:rf.nav/scroll` |
| `:rf.fx/spawn-args` | `:rf.machine/spawn` (the canonical actor-lifecycle fx-id; emitted from any event handler's `:fx` and from machine actions; per [005](005-StateMachines.md)) |
| `:rf.fx/destroy-machine-args` | `:rf.machine/destroy` (the canonical actor-destroy fx-id; per [005](005-StateMachines.md) and rf2-t07u — accepts either a bare actor-id keyword or a `{:rf/parent-id :rf/invoke-id}` map) |
| `:rf.fx.server/set-status-args` | `:rf.server/set-status` (per [011 §HTTP response contract](011-SSR.md#http-response-contract)) |
| `:rf.fx.server/set-header-args` | `:rf.server/set-header` |
| `:rf.fx.server/append-header-args` | `:rf.server/append-header` |
| `:rf.fx.server/set-cookie-args` | `:rf.server/set-cookie` (the `:rf.server/cookie` shape) |
| `:rf.fx.server/delete-cookie-args` | `:rf.server/delete-cookie` |
| `:rf.fx.server/redirect-args` | `:rf.server/redirect` |

The `:http` schema is **user-owned, not framework-owned** — projects that ship their own HTTP integration register their own `:spec` on their own `:http` `reg-fx`. The schema here is a reasonable starting point (the conformance corpus uses it) but is not part of the locked pattern contract.

Per-fx args validation runs as part of the standard fx-arg validation (per [010 §Validation timing](010-Schemas.md#validation-timing)) when the `reg-fx` registration carries a `:spec`. The standard fx ship with `:spec` set to the corresponding schema above.

### `:rf/frame-meta`

> **Layer:** Public

Returned by `(frame-meta frame-id)`. The `:preset` field, when present, records which preset was applied (per [002 §Frame presets](002-Frames.md#frame-presets--capability-bundles-for-common-configurations)); the *expanded* keys are the effective metadata map.

```clojure
(def FrameMeta
  [:map
   [:id           :keyword]
   [:created-at   :any]                                                    ;; timestamp
   [:preset       {:optional true} [:enum :default :test :story :ssr-server]] ;; per 002 §Frame presets
   [:on-create    {:optional true} [:vector :any]]                         ;; the init event vector
   [:on-destroy   {:optional true} [:vector :any]]
   [:fx-overrides {:optional true} [:map-of :keyword :any]]
   [:interceptor-overrides {:optional true} [:map-of :keyword :any]]
   [:interceptors {:optional true} [:vector :any]]
   [:drain-depth  {:optional true} :int]
   [:doc          {:optional true} :string]
   [:tags         {:optional true} [:set :keyword]]
   [:url-bound?   {:optional true} :boolean]                                ;; per [012-Routing.md](012-Routing.md)
   [:platform     {:optional true} :keyword]                                ;; the frame's active platform; per [011-SSR.md](011-SSR.md). Single keyword (one platform per frame); compared against `reg-fx`'s `:platforms` set.
   [:on-error     {:optional true} :keyword]])                              ;; error-projection target; per [011-SSR.md](011-SSR.md). The `:ssr-server` preset wires `:rf.error/server-projection`.
```

### `:rf/preset-expansion`

> **Layer:** Public

The fixed, closed expansion table for `:preset` values. Each preset expands to a metadata sub-map; the runtime merges user-supplied metadata over the expansion. Known presets: `:default`, `:test`, `:story`, `:ssr-server`. Unknown values raise `:rf.error/unknown-preset` at registration time.

```clojure
(def PresetExpansion
  [:map
   [:default     [:= {}]]                                                   ;; empty expansion
   [:test        [:map
                  [:fx-overrides [:= {:rf.http/managed :rf.http/managed-canned-success}]] ;; exact pair fixed by 002 §`:test` preset
                  [:drain-depth  [:= 100]]]]
   [:story       [:map
                  [:fx-overrides [:= {:rf.http/managed :rf.http/managed-canned-success}]] ;; exact pair fixed by 002 §`:story` preset
                  [:drain-depth  [:= 16]]]]
   [:ssr-server  [:map
                  [:platform     [:= :server]]
                  [:on-error     [:= :rf.error/server-projection]]]]])
```

The fully-expanded metadata returned from `frame-meta` conforms to `:rf/frame-meta`; the schema for the expansion *table itself* is `:rf/preset-expansion`. Implementations must produce the same expansion the table specifies, modulo user-supplied overrides.

### `:rf/variant`

> **Layer:** Public (post-v1 library)

The serialisable artefact contract for a story variant (post-v1 library; see [007 §Variant artefact contract](007-Stories.md#variant-artefact-contract--variants-are-data-not-functions)). **Variants are data, not functions** — every key is a value-shape, no fn-valued slots.

```clojure
(def Variant
  [:map {:closed false}
   [:variant-id  :keyword]                                                  ;; :story.<path>/<variant>
   [:doc         {:optional true} :string]
   [:extends     {:optional true} :keyword]                                 ;; parent variant id (composed)
   [:events      {:optional true} [:vector [:vector :any]]]                 ;; setup events (data only)
   [:play        {:optional true} [:vector [:vector :any]]]                 ;; post-render interaction sequence
   [:args        {:optional true} :map]                                     ;; override or extend the parent story's args
   [:argtypes    {:optional true} :map]                                     ;; per-arg control descriptions
   [:tags        {:optional true} [:set :keyword]]                          ;; from the registered tag vocabulary
   [:decorators  {:optional true} [:vector [:vector :any]]]                 ;; [decorator-id args...]; id-valued
   [:loaders     {:optional true} [:vector [:vector :any]]]                 ;; async setup events
   [:platforms   {:optional true} [:set [:enum :server :client]]]])
```

**Composition.** When `:extends` is present, the registrar resolves the parent variant's `:rf/variant` and merges (child wins key-by-key) before storing. The stored body is fully resolved — no further resolution at runtime.

**No fn-valued slots.** Decorators are id-valued (`[decorator-id args...]`); loaders are event vectors (the *handler* the loader event ids point to is the only fn-valued part — and it lives at the registration site, not in the variant body). Variants are wire-portable, storable as snapshots, and structurally diffable.

### `:rf/epoch-record`

> **Layer:** Runtime

Per-frame epoch snapshot, recorded on each drain-completion in dev builds. Used by Tool-Pair for time-travel and post-mortem analysis. Production builds elide entirely (no schema validation needed in prod).

```clojure
(def EpochRecord
  [:map
   [:epoch-id      :any]                                                    ;; opaque, unique within a frame's history
   [:frame         :keyword]
   [:committed-at  :any]                                                    ;; timestamp
   [:event-id      :keyword]                                                ;; the event that triggered the cascade
   [:trigger-event [:vector :any]]                                          ;; the full event vector
   [:db-before     :any]                                                    ;; app-db before the cascade
   [:db-after      :any]                                                    ;; app-db after drain settled
   [:schema-digest {:optional true} [:maybe :string]]                       ;; (rf2-0z1z) digest of the frame's app-schema set at record time, per [010 §Schema digest](010-Schemas.md#schema-digest); nil on hosts without a runtime schema layer
   [:trace-events  {:optional true} [:vector :any]]                         ;; the cascade's trace events (raw)
   [:sub-runs      {:optional true} [:vector
                                     [:map
                                      [:sub-id      :any]
                                      [:query-v     [:vector :any]]
                                      [:recomputed? :boolean]]]]           ;; per-sub activity in this cascade
   [:renders       {:optional true} [:vector
                                     [:map
                                      [:render-key   [:tuple :any :any]]   ;; [<view-id-or-:rf.view/anonymous> <instance-token>]
                                      [:triggered-by [:maybe :any]]        ;; sub-id or nil
                                      [:elapsed-ms   :any]]]]              ;; per-render activity in this cascade
   [:effects       {:optional true} [:vector
                                     [:map
                                      [:fx-id        :keyword]
                                      [:args         :any]
                                      [:outcome      [:enum :ok :error :skipped-on-platform]]
                                      [:error-trace  {:optional true} :any]]]] ;; per-effect activity in this cascade
   ])
```

The `:db-before` / `:db-after` pair lets pair tools display diffs cheaply.

**Structured slots are derived from `:trace-events`.** The `:sub-runs`, `:renders`, and `:effects` slots are pre-computed projections of the underlying `:trace-events` stream, surfacing the per-sub / per-render / per-effect activity of the cascade in a shape pair-shaped tools can route off without re-folding the raw trace each time. The legacy `:trace-events` slot remains the raw underpinning; the structured slots derive from it.

- `:sub-runs` — every sub the cascade re-ran. `:recomputed?` is `true` for every entry: under the value-equality rule in [Spec 006 §Invalidation algorithm](006-ReactiveSubstrate.md#invalidation-algorithm) (rf2-719e), a sub whose inputs are value-equal to the prior call does not re-run its body and therefore does not emit `:sub/run`, so cache-hit subs are absent from this projection. The slot answers "which subs moved this cascade?" without re-deriving from the trace. (rf2-7e2y dropped a `:result-changed?` slot that was structurally always true under the same semantics — consumers wanting the input-changed-but-value-equal distinction must consume the raw trace until that distinction is wired through as a separate signal.)
- `:renders` — every render that fired during the cascade. `:triggered-by` names the sub-id whose value-change triggered the render, or is `nil` for the initial mount / a render driven by something other than a sub change. `:elapsed-ms` is the render's wall-clock duration. `:render-key` is a **tuple** `[<view-id> <instance-token>]` (rf2-t5tx). The first slot is the `reg-view` registry id, or `:rf.view/anonymous` for plain Reagent fns (implementations may derive a tooling-friendly substitute from `(.-displayName fn)` when cheap); the second slot is an integer instance-token minted at mount time from a runtime counter atom. Tools that aggregate by view use the first slot; tools that distinguish per-mount activity use the second. Cross-run correlation (replay) is out of scope — instance-tokens regenerate per mount; alternative keys (positional path, parent context) are an open question if Tool-Pair replay grows that need.
- `:effects` — every effect dispatched in the cascade's `:event/do-fx` step. **Every dispatched fx surfaces exactly one entry**, regardless of outcome — successes, warnings, and errors are all recorded so per-event fx attribution is available without re-folding the raw trace stream. `:outcome` is `:ok` on success, `:error` if the effect threw or returned a structured error, `:skipped-on-platform` when the effect is registered with `:platforms` that exclude the current host (per [011](011-SSR.md)). `:error-trace` (when present, on `:error` outcomes) references the corresponding error trace event by `:id`. The `:fx-id`s of reserved runtime fx (`:dispatch`, `:dispatch-later`, `:rf.fx/reg-flow`, `:rf.fx/clear-flow`, `:rf.machine/spawn`, `:rf.machine/destroy`) appear in `:effects` alongside user-registered fx — one entry per dispatched pair, in source order.
- `:schema-digest` (rf2-0z1z) — the canonical wire form (per [010 §Schema digest](010-Schemas.md#schema-digest)) of the frame's app-schema set at the moment this epoch was recorded. Pinned per-epoch so `restore-epoch`'s `:rf.epoch/restore-schema-mismatch` trace can carry both the **recorded** digest and the frame's **current** digest, letting pair tools attribute restore failures to schema drift. `nil` on hosts that ship no runtime schema layer (the slot is optional and tolerated absent).

`:trace-events` is optional because for long histories the per-epoch trace can be large — implementations may choose to drop traces from older epochs. The structured slots have the same per-epoch-storage tradeoff and may likewise be elided for older epochs in the ring buffer.

### `:rf/fixture-file`

> **Layer:** Conformance

The host-agnostic conformance fixture format. Per [conformance/README.md](conformance/README.md), each fixture is one EDN file describing a canonical interaction (registry, handlers-as-data, dispatches/calls, expected emissions).

```clojure
(def FixtureFile
  [:map
   [:fixture/id           :keyword]
   [:fixture/spec-version :string]
   [:fixture/doc          {:optional true} :string]

   [:fixture/registry
    [:map
     [:event           {:optional true} [:map-of :keyword :map]]
     [:sub             {:optional true} [:map-of :keyword :map]]
     [:fx              {:optional true} [:map-of :keyword :map]]
     [:cofx            {:optional true} [:map-of :keyword :map]]
     [:view            {:optional true} [:map-of :keyword :map]]
     [:app-schema      {:optional true} [:map-of [:vector :any] :any]]
     [:route           {:optional true} [:map-of :keyword :map]]]]

   ;; Handler bodies are expressed in the :rf/handler-body-dsl grammar
   ;; defined above (single canonical definition).
   [:fixture/handlers
    [:map-of :keyword [:map-of :keyword HandlerBody]]]

   [:fixture/frame-config {:optional true} :map]
   [:fixture/dispatches   {:optional true} [:vector [:vector :any]]]

   ;; `:fixture/calls` carries direct invocations of pure primitives (Mode B).
   ;; Each entry dispatches on `:call`; per-op record shapes match the
   ;; fixture-runner's case dispatch (`implementation/core/test/re_frame/conformance_test.clj` `run-call`).
   ;; The six operators cover state-machine transitions, URL ↔ route helpers, and SSR rendering.
   [:fixture/calls
    {:optional true}
    [:vector
     [:multi {:dispatch :call}
      ;; Pure machine-transition. Returns [next-snapshot effects].
      [:machine-transition
       [:map
        [:call                 [:= :machine-transition]]
        [:definition           :any]                                          ;; transition table per [005-StateMachines.md](005-StateMachines.md)
        [:snapshot             :any]                                          ;; {:state :data} input snapshot
        [:event                [:vector :any]]                                ;; event vector to apply
        [:expect-next-snapshot :any]                                          ;; expected snapshot after transition
        [:expect-effects       [:vector :any]]]]                              ;; expected fx vector returned by the action

      ;; URL → route-match. `:expect` is the match map or `nil` for unmatched.
      [:match-url
       [:map
        [:call    [:= :match-url]]
        [:url     :string]
        [:expect  :any]]]                                                     ;; {:route-id :params :query :validation-failed?} or nil

      ;; route-id + params [+ query] → URL string.
      [:route-url
       [:map
        [:call      [:= :route-url]]
        [:route-id  :keyword]
        [:params    :map]
        [:query     {:optional true} :map]                                    ;; 3-arity form when present
        [:expect    :string]]]                                                ;; the rebuilt URL

      ;; Round-trip property: route-url ∘ match-url is identity for the URL.
      [:round-trip
       [:map
        [:call  [:= :round-trip]]
        [:url   :string]]]

      ;; Asserts winner's :rf.route/rank tuple compares greater than loser's
      ;; via lex compare. Per [012 §Route ranking algorithm](012-Routing.md#route-ranking-algorithm).
      [:assert-rank-greater
       [:map
        [:call    [:= :assert-rank-greater]]
        [:winner  :keyword]                                                   ;; route-id expected to outrank
        [:loser   :keyword]]]                                                 ;; route-id expected to be outranked

      ;; SSR pure render. `:input` is hiccup or a registered-view event vector.
      [:render-to-string
       [:map
        [:call    [:= :render-to-string]]
        [:input   :any]                                                       ;; hiccup or [:view-id args ...]
        [:opts    {:optional true} :map]                                      ;; {:doctype? bool} etc.
        [:expect  :string]]]]]]                                               ;; expected HTML output

   [:fixture/expect
    {:optional true}
    [:map
     [:final-app-db        {:optional true} :any]
     [:sub-values          {:optional true} [:map-of [:vector :any] :any]]
     [:sub-graph-topology  {:optional true} :map]
     [:trace-emissions     {:optional true} [:vector :map]]
     [:effects-routed      {:optional true} [:vector :any]]]]])               ;; routed-fx pairs in declaration order, per [conformance/README.md](conformance/README.md) §Fixture lifecycle
```

`:rf/fixture-handler-body` is a synonym for `:rf/handler-body-dsl` (defined above) — the fixture format reuses the canonical DSL grammar rather than redefining it. Reserved built-ins are enumerated in [conformance/README.md §Handler-body DSL builtins](conformance/README.md#handler-body-dsl-builtins).

The schema is open by convention — fixture files may add `:fixture/<key>`-namespaced metadata keys.

## Conformance

An implementation conforms if every runtime shape it produces *matches* the structures described above. Multiple paths to conformance:

- **Dynamically typed hosts with a runtime schema layer** (CLJS+Malli; Python+Pydantic; JS+Zod; Ruby+dry-rb): validate emitted shapes against registered schemas. Failures are surfaced as `:rf.error/schema-validation-failure` trace events ([009 §Error contract](009-Instrumentation.md#error-contract)).
- **Statically typed hosts** (TypeScript, Kotlin, Rust, F#): the type system enforces shape correctness through the runtime. Mismatches at the boundary (incoming JSON, deserialised state) are caught by a small boundary validator if needed. Most internal shape errors don't arise at all because the compiler rejected them.
- **Dynamically typed hosts without a runtime schema layer** (rare; primarily early-stage prototypes): match shapes by convention. Conformance is verified by the fixture corpus alone.

The conformance test corpus is built from canonical interactions (a counter increment, a feature scaffolding, a state-machine transition, a server-side render + hydration round-trip) along with the expected emissions. The corpus format is itself data — an EDN/JSON file — so an AI can read it, generate test code in the host language, and report conformance scores.

All three paths pass the corpus; the differences are about *when* shape errors are detected (compile time vs. runtime) and *what* mechanism catches them.

## Cross-references

- [000-Vision.md](000-Vision.md) — the open-maps-with-schemas commitment.
- [009 §Error contract](009-Instrumentation.md#error-contract) — error-event refinements of `TraceEvent`.
- [010-Schemas.md](010-Schemas.md) — the CLJS reference's schema integration (Malli); this doc applies the same shape discipline to the spec's *own* runtime data.
- [011-SSR.md](011-SSR.md) — hydration payload context.
- [005-StateMachines.md](005-StateMachines.md) — transition table grammar context.
