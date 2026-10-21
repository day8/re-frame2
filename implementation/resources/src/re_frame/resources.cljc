(ns re-frame.resources
  "Resources: declarative server-state as a runtime-managed read model over a
  frame work ledger. Spec 016 §Abstract.

  This namespace is the **public boot point and façade** for the
  resources artefact: apps boot it with `(:require [re-frame.resources])`.
  Doing so transitively loads every concern sibling under
  `re-frame.resources.*` and runs the registrations at the bottom of this
  file. They live here, not in the siblings, so a
  `(require 're-frame.resources :reload)` on a fresh registrar re-wires every
  handler.

  The artefact is optional: `re-frame.core` must not `:require` it and reaches
  it through the late-bind table, so an app without it gets
  `:rf.error/resources-artefact-missing`. Routing and SSR integrations are
  late-bound too, so an app that omits those artefacts carries none of their
  code."
  (:require [re-frame.cofx :as rf.cofx]
            [re-frame.error :as rf.error]
            [re-frame.events :as rf.events]
            [re-frame.frame :as rf.frame]
            [re-frame.fx :as rf.fx]
            ;; The one read transport: loading resources registers
            ;; `:rf.http/managed` and its late-bind hooks.
            [re-frame.http.managed]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.resources.classification :as rf.resources.classification]
            [re-frame.resources.events :as rf.resources.events]
            [re-frame.resources.mutation-events :as rf.resources.mutation-events]
            [re-frame.resources.mutation-registry :as rf.resources.mutation-registry]
            [re-frame.resources.mutation-runtime :as rf.resources.mutation-runtime]
            [re-frame.resources.mutation-subs :as rf.resources.mutation-subs]
            [re-frame.resources.registry :as rf.resources.registry]
            [re-frame.resources.revalidate-listeners :as rf.resources.revalidate-listeners]
            [re-frame.resources.route :as rf.resources.route]
            [re-frame.resources.scope-registry :as rf.resources.scope-registry]
            [re-frame.resources.ssr :as rf.resources.ssr]
            [re-frame.resources.state :as rf.resources.state]
            [re-frame.resources.subs :as rf.resources.subs]
            [re-frame.resources.timers :as rf.resources.timers]
            ;; Production-reachable (unlike `tooling`), so the off-box trace
            ;; egress hooks below are published on both runtimes.
            [re-frame.resources.trace-egress :as rf.resources.trace-egress]
            ;; `re-frame.resources.tooling` is deliberately not required: it
            ;; is bundle-isolated, and its consumers name it directly.
            [re-frame.resources.work-ledger :as rf.resources.work-ledger]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- public-surface re-exports --------------------------------------------
;; There are no `clear-*`, `*-meta` or `*-ids` re-exports: the inverse is the
;; kind-keyed `(rf/clear <kind> id)` and introspection is the generic
;; registrar read, e.g.
;;   (:rf/resource (rf/handler-meta {:kind :resource :id id}))
;; The algebra views live only in the bundle-isolated `tooling` sibling.
;; Derivations §Resources expose process nodes.

(def reg-resource    rf.resources.registry/reg-resource)

(def reg-mutation    rf.resources.mutation-registry/reg-mutation)

;; `resolve-resource-scope` is pure: it emits no `:rf.resource/scope-resolved`
;; trace, which only the causal resolution boundaries emit.
;; Spec 016 §Named resource-scope resolvers (`reg-resource-scope`)
(def reg-resource-scope     rf.resources.scope-registry/reg-resource-scope)
(def resolve-resource-scope rf.resources.scope-registry/resolve-resource-scope)

(defn resource-state
  "The durable runtime entry for the introspection target
  `{:resource :scope :params :frame}`, or nil when that frame holds none.
  `:frame` is a frame id or a live frame value; the scoped key resolves as a
  subscription would.

  A missing `:frame` throws `:rf.error/no-frame-context`, because a nil
  lookup would be indistinguishable from an absent entry. An unknown or
  destroyed frame is not an error: it reads as nil.
  Spec 016 §Introspection and projection (tool/test only)"
  [{:keys [frame] :as opts}]
  (when (nil? frame)
    (rf.error/throw-error!
      :rf.error/no-frame-context
      'rf.resources/resource-state
      (str "resource-state requires an explicit :frame "
           "introspection target. A frameless call would "
           "pass nil through to the runtime-db lookup and "
           "return nil — indistinguishable from a genuinely "
           "absent entry. Pass {:resource … :scope … "
           ":params … :frame <frame-id>}. Per Spec 016 "
           "§Introspection.")
      {:recovery :pass-frame
       :extra    {:opts (dissoc opts :frame)}}))
  (let [;; Normalise a frame value to its id, or a value would read a live
        ;; entry as a silent nil.
        frame      (rf.frame/frame-value->id frame)
        ;; Resolve `{:from-db …}` against the frame's app-db, as the live
        ;; `[:rf/resource …]` sub does, so both find the same scoped key.
        scoped-key (rf.resources.subs/resolve-scoped-key
                     opts (rf.frame/frame-app-db-value frame))
        runtime-db (rf.frame/frame-runtime-db-value frame)]
    (when scoped-key
      (get-in runtime-db (rf.resources.state/entry-path scoped-key)))))

(defn mutation-state
  "The durable runtime row for the mutation instance target
  `{:instance :frame}`, or nil when that frame holds none. Fails closed on a
  missing `:frame` exactly as `resource-state` does.
  Spec 016 §Mutations (first public-beta gate)"
  [{:keys [instance frame] :as opts}]
  (when (nil? frame)
    (rf.error/throw-error!
      :rf.error/no-frame-context
      'rf.resources/mutation-state
      (str "mutation-state requires an explicit :frame "
           "introspection target. A frameless call would "
           "pass nil through to the runtime-db lookup and "
           "return nil — indistinguishable from a genuinely "
           "absent instance. Pass {:instance … "
           ":frame <frame-id>}. Per Spec 016 §Mutations.")
      {:recovery :pass-frame
       :extra    {:opts (dissoc opts :frame)}}))
  (let [runtime-db (rf.frame/frame-runtime-db-value (rf.frame/frame-value->id frame))]
    (get-in runtime-db (rf.resources.mutation-runtime/instance-path instance))))

;; ---- event / sub / hook registrations -------------------------------------

;; Every resource handler carries `:rf/framework-authority? true`, so its
;; `:rf.db/runtime` effect is an in-bounds framework write.
;; Spec 016 §Write authority
(def ^:private framework-authority-meta rf.resources.state/framework-authority-meta)

;; The generation is a durable join key, so the allocation is a recordable
;; cofx (replay re-presents it) while the allocator stays host-transient and
;; never rewinds; the fx advances the high-water mark with `max`.
;; Spec 016 §1. The generation allocator is monotonic and host-side; it does not rewind
(rf.cofx/reg-cofx :rf.resource/generation-allocation
               rf.resources.state/generation-allocation-cofx-meta
               rf.resources.state/generation-allocation-cofx)
(rf.fx/reg-fx :rf.resource/commit-generation
           rf.resources.state/commit-generation-meta
           rf.resources.state/commit-generation-handler)

;; Host side-table writes (work handles, timers) ride fx because the tables
;; are host-transient, not runtime-db. Spec 016 §Frame work ledger
(rf.fx/reg-fx :rf.resource/record-work-handle
           rf.resources.work-ledger/record-work-handle-meta
           rf.resources.work-ledger/record-work-handle-handler)
(rf.fx/reg-fx :rf.resource/clear-work-handle
           rf.resources.work-ledger/clear-work-handle-meta
           rf.resources.work-ledger/clear-work-handle-handler)

;; Neither timer fx declares `:platforms`: schedule-timers skips arming under
;; SSR through its carried `:server?` flag (a JVM client still arms), and
;; cancellation is never platform-gated. Spec 016 §Stale and GC scheduling
(rf.fx/reg-fx :rf.resource/schedule-timers
           rf.resources.timers/schedule-timers-meta
           rf.resources.timers/schedule-timers-handler)
(rf.fx/reg-fx :rf.resource/cancel-timers
           rf.resources.timers/cancel-timers-meta
           rf.resources.timers/cancel-timers-handler)
;; Poll-only: an owner-free entry stops polling but keeps its stale / GC
;; timers, because it still GCs. Spec 016 §Polling
(rf.fx/reg-fx :rf.resource/cancel-poll-timers
           rf.resources.timers/cancel-poll-timers-meta
           rf.resources.timers/cancel-poll-timers-handler)
;; Runs after the hydrated runtime-db has committed: `commit-frame-effects!`
;; installs both partitions before `run-fx-effects!` walks `:fx`.
(rf.fx/reg-fx :rf.resource/hydrate-rearm
  {:doc "Resource-internal: arm the GC timer of every resource entry the frame's runtime-db holds, and the poll timer of each owned entry whose resource declares `:poll-interval-ms`, right after a client `:rf/hydrate` committed it, so a hydrated entry whose owner rode the wire is still collected once it is owner-free and idle, and polls while it is owned. No stale timer. Refuses a `:server` frame. Per Spec 016 §Freshness clock contract and §Polling. Not for direct application use."}
  (fn [{frame-id :frame} _args]
    (let [;; A nil frame stamp is an invariant failure, never `:rf/default`.
          frame-id (rf.frame/require-frame-stamp!
                     frame-id :rf.resource/hydrate-rearm
                     {:where 'rf.resource/hydrate-rearm})]
      (rf.resources.ssr/rearm-timers-after-hydration! frame-id))
    nil))

;; Cofx delivery is declared-only, so a handler sees `:rf/time-ms` flat in its
;; coeffects only if it declares it; declaring it also exposes the time
;; dependency to strict replay and tooling.
(def ^:private time-meta
  (assoc framework-authority-meta
         :rf.cofx/requires [:rf/time-ms]))

;; Load-causing events read the recorded allocation and the causal time flat.
(def ^:private generation-meta
  (assoc framework-authority-meta
         :rf.cofx/requires [:rf.resource/generation-allocation :rf/time-ms]))

;; Every entry-mutating handler is wrapped in `with-classification-lowering`,
;; which keeps the per-frame elision registry in step with `:entries`
;; whichever handler ran. Spec 016 §Events (map payloads, not positional argument vectors)
(rf.events/reg-event :rf.resource/ensure
                     generation-meta
                     (rf.resources.events/with-classification-lowering
                       rf.resources.events/ensure-handler))
(rf.events/reg-event :rf.resource/refetch
                     generation-meta
                     (rf.resources.events/with-classification-lowering
                       rf.resources.events/refetch-handler))
(rf.events/reg-event :rf.resource/load-more
                     generation-meta
                     (rf.resources.events/with-classification-lowering
                       rf.resources.events/load-more-handler))
;; One leg of a multi-page refetch sweep, chained by `page-succeeded-handler`.
;; Each leg mints its own generation for per-leg stale suppression.
(rf.events/reg-event :rf.resource.internal/refetch-page
                     generation-meta
                     (rf.resources.events/with-classification-lowering
                       rf.resources.events/refetch-page-handler))
(rf.events/reg-event :rf.resource/invalidate-tags
                     time-meta
                     (rf.resources.events/with-classification-lowering
                       rf.resources.events/invalidate-tags-handler))
(rf.events/reg-event :rf.resource/release-owner
                     framework-authority-meta
                     (rf.resources.events/with-classification-lowering
                       rf.resources.events/release-owner-handler))
;; Attach-before-release for a kept branch-plan identity: dispatched ahead of
;; the prior owner's release, with no fetch. Spec 016 §Plan diff and owner handoff
(rf.events/reg-event :rf.resource.internal/adopt-owner
                     framework-authority-meta
                     (rf.resources.events/with-classification-lowering
                       rf.resources.events/adopt-route-owner-handler))
;; The release-side twin, ordered after the attach fx on a same-token replan;
;; the replan commit owns the route slots, so it clears none.
;; Spec 016 §Route-plan replan — same-token reconciliation
(rf.events/reg-event :rf.resource.internal/release-owner-identities
                     framework-authority-meta
                     (rf.resources.events/with-classification-lowering
                       rf.resources.events/release-owner-identities-handler))
;; Cancelling in-flight work is a completion, so these stamp the causal
;; `:completed-at` and declare the time cofx.
(rf.events/reg-event :rf.resource/clear-scope
                     time-meta
                     (rf.resources.events/with-classification-lowering
                       rf.resources.events/clear-scope-handler))
;; The `:schema` is the payload shape; the handler validates each scope.
;; Spec 016 §Changing principal
(rf.events/reg-event :rf.resource/change-principal
                     (assoc time-meta
                            :schema [:cat [:= :rf.resource/change-principal]
                                     [:map {:closed true}
                                      [:from {:optional true} [:maybe [:vector :any]]]
                                      [:cause :some]]])
                     (rf.resources.events/with-classification-lowering
                       rf.resources.events/change-principal-handler))
(rf.events/reg-event :rf.resource/remove
                     time-meta
                     (rf.resources.events/with-classification-lowering
                       rf.resources.events/remove-handler))

;; The scans write nothing durable but declare the time cofx, because a
;; replayed signal must select the same active-stale set.
;; Spec 016 §Revalidation is a frame property (`:revalidate-on`)
(rf.events/reg-event :rf.resource/window-focused
                     time-meta
                     rf.resources.events/window-focused-handler)
(rf.events/reg-event :rf.resource/network-reconnected
                     time-meta
                     rf.resources.events/network-reconnected-handler)

;; Framework-internal replies; user code must not dispatch them.
;; Spec 016 §Race and in-flight semantics
(rf.events/reg-event :rf.resource.internal/succeeded
                     time-meta
                     (rf.resources.events/with-classification-lowering
                       rf.resources.events/succeeded-handler))
(rf.events/reg-event :rf.resource.internal/failed
                     time-meta
                     (rf.resources.events/with-classification-lowering
                       rf.resources.events/failed-handler))
(rf.events/reg-event :rf.resource.internal/page-succeeded
                     time-meta
                     (rf.resources.events/with-classification-lowering
                       rf.resources.events/page-succeeded-handler))
(rf.events/reg-event :rf.resource.internal/page-failed
                     time-meta
                     (rf.resources.events/with-classification-lowering
                       rf.resources.events/page-failed-handler))
(rf.events/reg-event :rf.resource.internal/stale-fired
                     time-meta
                     (rf.resources.events/with-classification-lowering
                       rf.resources.events/stale-fired-handler))
(rf.events/reg-event :rf.resource.internal/gc-fired
                     framework-authority-meta
                     (rf.resources.events/with-classification-lowering
                       rf.resources.events/gc-fired-handler))
(rf.events/reg-event :rf.resource.internal/poll-fired
                     framework-authority-meta
                     (rf.resources.events/with-classification-lowering
                       rf.resources.events/poll-fired-handler))
(rf.events/reg-event :rf.resource.internal/stale-suppressed
                     framework-authority-meta
                     rf.resources.events/stale-suppressed-handler)

(rf.resources.subs/register-subs!)

;; Mutation handlers that touch entries get the entry reconcile too, because
;; a `:populates` can create an entry the registry never lowered; instance
;; handlers also get the `:source :mutation` reconcile.
;; Spec 016 §Mutations (first public-beta gate)
(defn- with-mutation-classification-lowering
  "Wraps mutation handler `f` so the `:rf.db/runtime` of its returned effects
  is reconciled into the per-frame elision registry under `:source :mutation`.
  Each reconcile touches only its own owner, so composition order with
  `with-classification-lowering` is immaterial. A result without
  `:rf.db/runtime` passes through."
  [f]
  (fn [& args]
    (let [effects (apply f args)]
      (if (and (map? effects) (contains? effects :rf.db/runtime))
        (update effects :rf.db/runtime
                rf.resources.classification/reconcile-mutation-registry rf.resources.mutation-registry/mutation-meta)
        effects))))
(rf.events/reg-event :rf.mutation/execute
                     generation-meta
                     (rf.resources.events/with-classification-lowering
                       (with-mutation-classification-lowering
                         rf.resources.mutation-events/execute-handler)))
(rf.events/reg-event :rf.mutation/clear
                     time-meta
                     (with-mutation-classification-lowering
                       rf.resources.mutation-events/clear-handler))
(rf.events/reg-event :rf.mutation.internal/succeeded
                     time-meta
                     (rf.resources.events/with-classification-lowering
                       (with-mutation-classification-lowering
                         rf.resources.mutation-events/succeeded-handler)))
(rf.events/reg-event :rf.mutation.internal/failed
                     time-meta
                     (rf.resources.events/with-classification-lowering
                       (with-mutation-classification-lowering
                         rf.resources.mutation-events/failed-handler)))

(rf.resources.mutation-subs/register-subs!)

;; The execute payload's classification lives on the mutation named inside the
;; args, so core's event-vector redaction defers to this hook. It is published
;; here because the registry requires the classification ns.
(rf.late-bind/set-fn! :resources/project-execute-event-args
                   (fn project-execute-event-args-hook [args]
                     (rf.resources.classification/project-execute-event-args
                       args rf.resources.mutation-registry/mutation-meta)))

;; Wired here so they re-install on a `:reload`.
;; Spec 016 §Route integration and Spec 016 §SSR and hydration
(rf.resources.route/install-routing-integration!)
(rf.resources.ssr/install-ssr-integration!)

;; The generation high-water mark is process-global, so destroy leaves it and
;; a same-id successor never re-mints a predecessor's generation.
(defn- release-resources-host-caches!
  "Releases the destroyed frame's host-transient caches: work-ledger handles,
  timer handles and revalidation listeners. Durable records leave with the
  frame value. Runtime-Subsystems §5. Teardown"
  [frame-id]
  (rf.resources.work-ledger/on-frame-destroyed! frame-id)
  (rf.resources.timers/on-frame-destroyed! frame-id)
  (rf.resources.revalidate-listeners/on-frame-destroyed! frame-id)
  nil)

(rf.late-bind/set-fn! :resources/on-frame-destroyed! release-resources-host-caches!)

;; Fires at the end of both `upsert-frame!` branches, after `:initial-events`
;; ran; re-registration reconciles rather than stacking listeners.
(defn- on-frame-registered!
  "Reconciles the frame's focus / reconnect listeners against its committed
  `:revalidate-on`, read from the registry rather than a passed config.
  Returns nil. Spec 016 §Revalidation is a frame property (`:revalidate-on`)"
  [frame-id]
  (rf.resources.revalidate-listeners/reconcile-listeners!
    frame-id
    (:revalidate-on (rf.frame/frame-meta frame-id)))
  nil)

(rf.late-bind/set-fn! :resources/on-frame-registered! on-frame-registered!)
(rf.frame/add-config-keys! #{:revalidate-on})

;; ---- late-bind hook registration ------------------------------------------
;; `:resources/reg-resource` doubles as the `:resources` feature probe.
;; The test reset hook is published by `re-frame.resources.test-support`, to
;; keep fixtures out of the production façade.
(rf.late-bind/set-fns!
  {:resources/reg-resource   reg-resource
   :resources/clear-resource rf.resources.registry/clear-resource
   :resources/reg-mutation   reg-mutation
   :resources/clear-mutation rf.resources.mutation-registry/clear-mutation
   :resources/reg-resource-scope     reg-resource-scope
   :resources/clear-resource-scope   rf.resources.scope-registry/clear-resource-scope
   :resources/resolve-resource-scope resolve-resource-scope
   ;; Off-box trace egress projectors. A value-path walk cannot see
   ;; owner-local values once they are copied into trace tags or fx args, so
   ;; the epoch tool-pair consults these.
   :resources/project-scope-resolved-egress
   rf.resources.scope-registry/project-scope-resolved-egress
   :resources/project-resource-trace-egress
   rf.resources.trace-egress/project-resource-trace-egress
   ;; Applied to every row; it no-ops on a row with neither slot.
   :resources/project-fx-args-egress
   rf.resources.trace-egress/project-fx-args-egress
   :resources/project-resource-sub-egress
   rf.resources.trace-egress/project-read-sub-egress})
