(ns re-frame.core-resources
  "Public-API wrappers for the optional resources artefact (Spec 016).
  Implementation ships in `day8/re-frame2-resources` (`re-frame.resources`).
  See [Conventions §Optional-artefact wrapper convention](../../../../../spec/Conventions.md#optional-artefact-wrapper-convention).

  Resources is a POST-V1 OPTIONAL capability: declarative server-state as
  a runtime-managed read model. These wrappers look the producing impl up
  through the late-bind hook registry at call time; an app that omits the
  artefact sees a structured `:rf.error/resources-artefact-missing`
  ex-info naming the exact Maven coordinate + require form."
  (:require [re-frame.core-artefact #?@(:clj  [:refer        [defwrapper]]
                                        :cljs [:refer-macros [defwrapper]])]))

#?(:clj (set! *warn-on-reflection* true))

(def ^:private resources-artefact
  {:error-keyword :rf.error/resources-artefact-missing
   :maven         "day8/re-frame2-resources"
   :require-ns    "re-frame.resources"})

(defwrapper reg-resource
  "Per Spec 016 §Public API §Registration. Register a resource — a named,
  cached read of remote/external state — under `resource-id` with
  `resource-spec`. The spec carries the REQUIRED, fail-closed `:scope`
  policy (`:rf.scope/global` | a resolver | `:rf.scope/from-caller`),
  `:params-schema`, `:request`, and optional `:data-schema` /
  `:stale-after-ms` / `:gc-after-ms` / `:tags` / `:sensitive?`. Views read
  the resource through passive `[:rf.resource/*]` subscriptions; route
  entry / events / machines cause it to fetch. Late-bound via
  `:resources/reg-resource`."
  {:hook :resources/reg-resource :artefact resources-artefact :on-absent :throw
   :ex-data {:resource-id resource-id}}
  ([resource-id resource-spec] :delegate))

(defwrapper clear-resource
  "Per Spec 016 §Public API §Registration. Remove a registered resource
  (a registration-lifecycle operation — NOT the normal cache-invalidation
  API; for data lifecycle use `:rf.resource/invalidate-tags` /
  `:rf.resource/remove` / `:rf.resource/clear-scope`). Also disposes the
  resource-runtime state for the id in each affected frame. Late-bound via
  `:resources/clear-resource`."
  {:hook :resources/clear-resource :artefact resources-artefact :on-absent :throw
   :ex-data {:resource-id resource-id}}
  ([resource-id] :delegate))

(defwrapper resource-meta
  "Per Spec 016 §Introspection. Return the registered resource's spec map
  (`:params-schema`, `:data-schema`, `:request`, `:scope`, `:transport`,
  `:stale-after-ms`, `:gc-after-ms`, `:tags`, `:doc`, source coords) for
  `resource-id`, or nil. Late-bound via `:resources/resource-meta`."
  {:hook :resources/resource-meta :artefact resources-artefact :on-absent :throw
   :ex-data {:resource-id resource-id}}
  ([resource-id] :delegate))

(defwrapper resource-state
  "Per Spec 016 §Introspection. Return a resource instance's runtime
  state for an explicit-frame target `{:resource :scope :params :frame}`.
  Per EP-0002 the frame is carried explicitly — a frameless call with no
  resolvable context fails closed rather than inspecting the wrong frame.
  Late-bound via `:resources/resource-state`."
  {:hook :resources/resource-state :artefact resources-artefact :on-absent :throw}
  ([opts] :delegate))

(defwrapper resources
  "Per Spec 016 §Introspection. Return resource introspection for a frame
  target `{:frame …}` — the registered resources and (with the runtime
  slice) the live per-frame resource-instance table. Late-bound via
  `:resources/resources`."
  {:hook :resources/resources :artefact resources-artefact :on-absent :throw
   :arglists '([] [opts])}
  ([]     :delegate)
  ([opts] :delegate))

(defwrapper install-revalidation-listeners!
  "Per Spec 016 §Deferred slices (focus/reconnect revalidation). Install
  host `window` focus / network-reconnect listeners that drive active-stale
  revalidation for `frame-id`. On window focus / tab return the listener
  dispatches `[:rf.resource/window-focused]` at the frame; on network
  reconnect it dispatches `[:rf.resource/network-reconnected]`. The event
  handlers scan the frame's ACTIVE-OWNER stale entries and refetch them in
  the background (cause `:focus` / `:reconnect` — a cause, never an owner;
  generation + stale-suppression protect late replies). Fresh entries and
  owner-free entries are left alone. Idempotent (re-install replaces the
  frame's listeners — hot-reload safe). CLJS-only (the JVM arm is a no-op —
  no DOM under SSR / JVM). Cancelled on frame destroy. Late-bound via
  `:resources/install-revalidation-listeners!`."
  {:hook :resources/install-revalidation-listeners! :artefact resources-artefact :on-absent :throw
   :ex-data {:frame-id frame-id}}
  ([frame-id] :delegate))

(defwrapper remove-revalidation-listeners!
  "Per Spec 016 §Deferred slices. Tear down the `window` focus / online
  revalidation listeners installed by `install-revalidation-listeners!` for
  `frame-id`. No-op when none is installed (and on the JVM). CLJS-only.
  Late-bound via `:resources/remove-revalidation-listeners!`."
  {:hook :resources/remove-revalidation-listeners! :artefact resources-artefact :on-absent :throw
   :ex-data {:frame-id frame-id}}
  ([frame-id] :delegate))

;; ---- Mutations (rf2-dwme29, EP-0003 §Mutations — first public-beta gate) --

(defwrapper reg-mutation
  "Per Spec 016 §Deferred slices / EP-0003 §Mutations. Register a mutation
  — a named, causal WRITE to remote state that, on success, invalidates /
  patches / populates cached resource reads — under `mutation-id` with
  `mutation-spec`. The spec carries the REQUIRED `:request` (a Spec 014
  managed-HTTP args map — the write) and `:params-schema`, plus optional
  `:invalidates` (`(fn [params result] -> #{tag …})`), `:patches` /
  `:populates` (controlled resource-entry transforms / seeds applied on
  success), `:scope`, `:invalidate-timing` (`:after-success` (default) |
  `:before-request` | `:after-failure` | `:after-settle`), `:retry` (write
  retries are OPT-IN), `:transport`, and `:doc`. Run it with
  `[:rf.mutation/execute {:mutation … :params … :instance …}]`; observe it
  through the passive `[:rf.mutation/*]` subs keyed by `:instance` id.
  Late-bound via `:resources/reg-mutation`."
  {:hook :resources/reg-mutation :artefact resources-artefact :on-absent :throw
   :ex-data {:mutation-id mutation-id}}
  ([mutation-id mutation-spec] :delegate))

(defwrapper clear-mutation
  "Per Spec 016 §Deferred slices / EP-0003 §Mutations. Remove a registered
  mutation (a registration-lifecycle operation — NOT a form-error reset;
  for the causal runtime-instance reset use the `[:rf.mutation/clear …]`
  event). Late-bound via `:resources/clear-mutation`."
  {:hook :resources/clear-mutation :artefact resources-artefact :on-absent :throw
   :ex-data {:mutation-id mutation-id}}
  ([mutation-id] :delegate))

(defwrapper mutation-meta
  "Per Spec 016 §Deferred slices / EP-0003 §Mutations. Return the
  registered mutation's spec map (`:request`, `:params-schema`,
  `:invalidates`, `:patches`, `:populates`, `:scope`, `:invalidate-timing`,
  `:transport`, `:doc`, source coords) for `mutation-id`, or nil.
  Late-bound via `:resources/mutation-meta`."
  {:hook :resources/mutation-meta :artefact resources-artefact :on-absent :throw
   :ex-data {:mutation-id mutation-id}}
  ([mutation-id] :delegate))

(defwrapper mutation-state
  "Per Spec 016 §Deferred slices / EP-0003 §Mutations. Return a mutation
  INSTANCE's durable runtime row (`{:status :result :error …}`) for an
  explicit-frame target `{:instance … :frame …}`, or nil. Per EP-0002 the
  frame is carried explicitly. Late-bound via `:resources/mutation-state`."
  {:hook :resources/mutation-state :artefact resources-artefact :on-absent :throw}
  ([opts] :delegate))

(defwrapper mutations
  "Per Spec 016 §Deferred slices / EP-0003 §Mutations. Return mutation
  introspection for a frame target `{:frame …}` — the registered mutation
  ids and the live per-frame mutation-instance table (keyed by instance id;
  Xray groups instances under their registered mutation id). Without
  `:frame` only the static registry is returned. Late-bound via
  `:resources/mutations`."
  {:hook :resources/mutations :artefact resources-artefact :on-absent :throw
   :arglists '([] [opts])}
  ([]     :delegate)
  ([opts] :delegate))

;; ---- Named resource-scope resolvers (rf2-hls77w, EP-0016 D3 slice 2) ------

(defwrapper reg-resource-scope
  "Per Spec 016 §Named resource-scope resolvers (`reg-resource-scope`) /
  EP-0016 Decision 3. Register a PURE named scope resolver under `scope-id`
  — the one scope-resolution currency reused by resource registration,
  route resources, event-side ensure, subscriptions, invalidation
  descriptors, populate/patch/remove targets, and `clear-scope`. `resolver`
  is the primary declared-inputs map `{:inputs {name [:db <rf-path>]}
  :resolve (fn [inputs ctx] -> scope|nil)}` or the whole-db fn sugar
  `(fn [db ctx] -> scope|nil)` (an explicit, tooling-marked whole-db
  dependency). The shipped input source is `[:db <rf-path>]`; `[:runtime …]`
  is reserved and rejected loudly. A `nil` resolve result is FAIL-CLOSED at
  every scope-requiring site (never an implicit global). Referenced via
  `{:from-db <scope-id>}`. Late-bound via `:resources/reg-resource-scope`."
  {:hook :resources/reg-resource-scope :artefact resources-artefact :on-absent :throw
   :ex-data {:scope-id scope-id}}
  ([scope-id resolver] :delegate))

(defwrapper clear-resource-scope
  "Per Spec 016 §Named resource-scope resolvers. Remove a registered
  resource-scope resolver (a registration-lifecycle removal — the `clear-`
  decrement counterpart of `reg-resource-scope`). A resolver holds no
  per-frame runtime state of its own, so nothing beyond the registrar entry
  is disposed. Late-bound via `:resources/clear-resource-scope`."
  {:hook :resources/clear-resource-scope :artefact resources-artefact :on-absent :throw
   :ex-data {:scope-id scope-id}}
  ([scope-id] :delegate))

(defwrapper resolve-resource-scope
  "Per Spec 016 §Named resource-scope resolvers / §`clear-scope` resolves
  the concrete scope from the coeffect db (EP-0016 issue 7). Resolver helper:
  resolve the named resolver `scope-id` against the supplied `db` value,
  returning a canonical concrete scope or nil — a plain function over the
  resolver registry, NOT an effect (no app-state / dispatch side effects).
  It is a PURE data helper (rf2-ru73k6 F3): it routes through the trace-free
  `resolve-scope*-pure` evaluator and so does NOT emit
  `:rf.resource/scope-resolved` — a passive read advertised as pure carries
  no observability side effect. The CAUSAL resolution boundaries that DO carry
  that trace evidence (a resource event's `{:from-db …}` scope, route entry,
  mutation settle) run the traced `resolve-scope*` wrapper instead. Canonical
  use is the logout idiom:
  resolve the concrete old scope from the handler's coeffect db
  (pre-transition by definition) and pass it to `:rf.resource/clear-scope`
  concretely. Throws when no resolver is registered under `scope-id` (the
  loud fail-closed boundary). Late-bound via
  `:resources/resolve-resource-scope`."
  {:hook :resources/resolve-resource-scope :artefact resources-artefact :on-absent :throw
   :ex-data {:scope-id scope-id}}
  ([db scope-id] :delegate))
