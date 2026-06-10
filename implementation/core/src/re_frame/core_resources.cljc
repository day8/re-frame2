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
