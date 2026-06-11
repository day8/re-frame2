(ns re-frame.resources
  "Resources — declarative server-state as a runtime-managed read model
  over a frame work ledger. Per Spec 016.

  A resource is a named, cached read of remote or external state.
  `reg-resource` registers it; views read it through PASSIVE subscriptions
  (`[:rf.resource/state …]`); route entry, events, and machines CAUSE it
  to fetch. The resource runtime owns identity, cache scope, staleness,
  dedupe, invalidation, GC, in-flight ownership, SSR hydration, and tool
  metadata, so an app stops re-implementing that bookkeeping per feature.

  This namespace is the **public boot point and façade** for the
  resources artefact: apps boot it with `(:require [re-frame.resources])`.
  Doing so transitively loads every concern sibling under
  `re-frame.resources.*` and runs the registrations at the bottom of this
  file:

  - `re-frame.resources.state`           — reserved runtime-db paths, durable shapes, the host-side generation allocator, framework-write-authority stamp
  - `re-frame.resources.registry`        — `reg-resource` / `clear-resource` + the `:resource` registrar kind + registry introspection
  - `re-frame.resources.transport`       — the transport-neutral lower seam
  - `re-frame.resources.transport.http`  — the `:rf.http/managed` lowering (late-bound HTTP)
  - `re-frame.resources.events`          — the `:rf.resource/*` causal event handlers (+ internal replies)
  - `re-frame.resources.subs`            — the `:rf.resource/*` passive subs
  - `re-frame.resources.route`           — LATE-BOUND routing integration (`:resources` route-metadata key)
  - `re-frame.resources.ssr`             — LATE-BOUND SSR/hydration projection

  The registrations live HERE (not in the siblings) so a
  `(require 're-frame.resources :reload)` on a fresh registrar
  (`clear-all!` test fixture) re-wires every handler — the long-
  established consumer-test pattern, mirroring `re-frame.routing` /
  `re-frame.machines`.

  ## Optionality + bundle isolation

  Per Spec 016 §Implementation status this is a POST-V1 OPTIONAL artefact
  (`day8/re-frame2-resources`). `re-frame.core` MUST NOT `:require` it; the
  public-API surface is published through the late-bind table, so an app
  that omits the artefact sees the wrappers throw a clean
  `:rf.error/resources-artefact-missing`. The routing + SSR integrations
  are LATE-BOUND (resources never statically `:require`s routing / ssr /
  http), so an app that loads resources but not those optional artefacts
  carries none of their code. Nothing here `:require`s from `tools/`.

  ## Runtime

  The full runtime ships here (EP-0003 complete): the public surface, the
  registrar kind, and the late-bind wiring sit alongside the live runtime
  LOGIC — the entry transition function, work-ledger join/dedupe, stale
  suppression, GC, invalidation, route-plan execution, and hydration
  install. The `:rf.resource/*` event handlers carry the behaviour;
  loading this namespace registers the whole family."
  (:require [re-frame.cofx :as cofx]
            [re-frame.events :as events]
            [re-frame.frame :as frame]
            [re-frame.fx :as fx]
            [re-frame.late-bind :as late-bind]
            [re-frame.resources.events :as resource-events]
            [re-frame.resources.mutation-events :as mutation-events]
            [re-frame.resources.mutation-registry :as mutation-registry]
            [re-frame.resources.mutation-runtime :as mstate]
            [re-frame.resources.mutation-subs :as mutation-subs]
            [re-frame.resources.registry :as registry]
            [re-frame.resources.revalidate-listeners :as revalidate-listeners]
            [re-frame.resources.route :as route]
            [re-frame.resources.scope-registry :as scope-registry]
            [re-frame.resources.ssr :as ssr]
            [re-frame.resources.state :as state]
            [re-frame.resources.subs :as resource-subs]
            [re-frame.resources.timers :as timers]
            [re-frame.resources.work-ledger :as work-ledger]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- public-surface re-exports --------------------------------------------
;; These `def`s make the sibling fns reachable as
;; `re-frame.resources/<name>` so consumers (the `re-frame.core` late-bind
;; bridge, conformance, tests, examples) see one surface.

(def reg-resource    registry/reg-resource)
(def clear-resource  registry/clear-resource)
(def resource-meta   registry/resource-meta)
(def resource-ids    registry/resource-ids)

;; Mutations (rf2-dwme29, Spec 016 §Deferred slices / EP-0003 §Mutations —
;; the first public-beta gate). `reg-mutation` registers a causal-write
;; mutation; `:rf.mutation/execute` runs it over the SAME managed-HTTP
;; transport the resources use, with success-time resource invalidation /
;; patch / populate; `clear-mutation` is the registration-lifecycle removal.
(def reg-mutation    mutation-registry/reg-mutation)
(def clear-mutation  mutation-registry/clear-mutation)
(def mutation-meta   mutation-registry/mutation-meta)
(def mutation-ids    mutation-registry/mutation-ids)

;; Named resource-scope resolvers (rf2-hls77w, Spec 016 §Named
;; resource-scope resolvers / EP-0016 D3 slice 2). `reg-resource-scope`
;; registers a PURE named scope resolver under the `:resource-scope`
;; registrar kind; `clear-resource-scope` is the registration-lifecycle
;; removal; `resolve-resource-scope` is the PURE helper that resolves a
;; named scope against a supplied db value (the logout coeffect-db idiom).
(def reg-resource-scope     scope-registry/reg-resource-scope)
(def clear-resource-scope   scope-registry/clear-resource-scope)
(def resolve-resource-scope scope-registry/resolve-resource-scope)
(def scope-resolver-meta    scope-registry/scope-resolver-meta)
(def scope-resolver-ids     scope-registry/scope-resolver-ids)

;; Focus / reconnect revalidation host-listener install (rf2-vtblcq, Spec 016
;; §Deferred slices). An app calls `install-revalidation-listeners!` for each
;; frame that wants window-focus / network-reconnect active-stale
;; revalidation; the listeners dispatch `:rf.resource/window-focused` /
;; `:rf.resource/network-reconnected` at that frame and are cancelled on
;; frame destroy via the single `:resources/on-frame-destroyed!` hook.
;; CLJS-only host listeners; the JVM arm is a no-op (mirrors routing's
;; install-history-listener!).
(def install-revalidation-listeners! revalidate-listeners/install-revalidation-listeners!)
(def remove-revalidation-listeners!  revalidate-listeners/remove-revalidation-listeners!)

(defn resources
  "Return resource introspection for a frame target (Spec 016
  §Introspection). Returns `{:resource-ids [...] :entries {…}}` — the
  static registry (every registered resource id) plus, when `:frame` is
  supplied, the live per-frame resource-instance entries map
  (`{<scoped-resource-key> <entry>}`). Without `:frame` only the static
  registry is returned (no ambient frame fallback, EP-0002)."
  ([] {:resource-ids (resource-ids) :entries {}})
  ([{:keys [frame]}]
   {:resource-ids (resource-ids)
    :entries      (if frame
                    (or (get-in (frame/frame-runtime-db-value frame)
                                (state/entries-path)) {})
                    {})}))

(defn resource-state
  "Return a resource instance's durable runtime ENTRY for an explicit
  `:frame` introspection target `{:resource :scope :params :frame}` (Spec
  016 §Introspection), or nil when no entry exists for that scoped key in
  that frame. Per EP-0002 the frame target is carried explicitly; a
  frameless call FAILS CLOSED (rf2-c8lgy3): an absent / nil `:frame` raises
  the structured `:rf.error/no-frame-context` rather than passing nil through
  to a runtime-db lookup that returns nil — a nil that is INDISTINGUISHABLE
  from a genuinely absent entry. Resolves the scoped key the same way a
  subscription does (canonical scope + params, sub-side scope precedence,
  fail-closed).

  Frame existence is NOT a precondition: an explicit but unknown / destroyed
  `:frame` reads as `nil` runtime-db and returns `nil` (no entry) — the same
  result as a live frame with no entry for the key. The fail-closed boundary
  is the MISSING explicit target, not a vanished one; a valid explicit frame
  lookup returns `nil` only for a genuinely absent entry."
  [{:keys [frame] :as opts}]
  (when (nil? frame)
    (throw (ex-info ":rf.error/no-frame-context"
                    {:rf.error/id :rf.error/no-frame-context
                     :where       'rf/resource-state
                     :recovery    :pass-frame
                     :reason      (str "resource-state requires an explicit :frame "
                                       "introspection target. A frameless call would "
                                       "pass nil through to the runtime-db lookup and "
                                       "return nil — indistinguishable from a genuinely "
                                       "absent entry. Pass {:resource … :scope … "
                                       ":params … :frame <frame-id>}. Per Spec 016 "
                                       "§Introspection / EP-0002.")
                     :opts        (dissoc opts :frame)})))
  (let [scoped-key (resource-subs/resolve-scoped-key opts)
        runtime-db (frame/frame-runtime-db-value frame)]
    (get-in runtime-db (state/entry-path scoped-key))))

(defn mutations
  "Return mutation introspection for a frame target (EP-0003 §Mutations /
  Xray). Returns `{:mutation-ids [...] :instances {…}}` — the static
  registry (every registered mutation id) plus, when `:frame` is supplied,
  the live per-frame mutation-INSTANCE map (`{<instance-id> <instance>}`).
  Xray groups instances under their registered `:mutation/id` while showing
  each separately. Without `:frame` only the static registry is returned
  (no ambient frame fallback, EP-0002)."
  ([] {:mutation-ids (mutation-ids) :instances {}})
  ([{:keys [frame]}]
   {:mutation-ids (mutation-ids)
    :instances    (if frame
                    (or (get-in (frame/frame-runtime-db-value frame)
                                (mstate/instances-path)) {})
                    {})}))

(defn mutation-state
  "Return a mutation INSTANCE's durable runtime row for an explicit
  `:frame` introspection target `{:instance :frame}` (EP-0003 §Mutations),
  or nil when no instance exists under that instance id in that frame. Per
  EP-0002 the frame target is carried explicitly; a frameless call with no
  resolvable context fails closed."
  [{:keys [instance frame]}]
  (let [runtime-db (frame/frame-runtime-db-value frame)]
    (get-in runtime-db (mstate/instance-path instance))))

;; ---- event / sub / hook registrations -------------------------------------
;; Keeping the registrations in this façade means a `(require
;; 're-frame.resources :reload)` re-wires every handler on a fresh
;; registrar (the `clear-all!` test-fixture recovery pattern).

;; Every resource event handler stamps the reserved
;; `:rf/framework-authority? true` registration-meta (Spec 016 §Write
;; authority) so a returned `:rf.db/runtime` effect is recognised as a
;; framework write — the runtime's `:rf.warning/app-handler-runtime-effect`
;; ownership diagnostic treats these as in-bounds. Applied uniformly so a
;; new resource handler that touches the slice inherits authority by
;; sitting in this façade. (Mirrors routing's framework-authority-meta.)
(def ^:private framework-authority-meta state/framework-authority-meta)

;; :rf.resource/generation cofx + :rf.resource/commit-generation fx —
;; Spec 016 §Restore and replay part 1. The host-side generation allocator
;; (the monotone high-water mark that never rewinds across epoch restore,
;; so a pre-restore in-flight reply's generation can never match a
;; post-restore live entry). The cofx injects the active frame's high-water
;; snapshot; the ensure/refetch handlers mint the next generation purely and
;; bump the high-water mark via the fx. Mirrors routing's nav-counters seam
;; (rf2-oosjmh). Registered in the façade so a `:reload` re-wires them.
(cofx/reg-cofx :rf.resource/generation
               state/generation-cofx-meta
               state/generation-cofx)
(fx/reg-fx :rf.resource/commit-generation
           state/commit-generation-meta
           state/commit-generation-handler)

;; Work-ledger host-handle side-table write fx (rf2-afpdkn). The work-handle
;; side table is host-side transient state (NOT runtime-db), so its writes
;; ride fx exactly as the host-side generation high-water bump does. The
;; runtime emits :rf.resource/record-work-handle alongside the transport
;; lower and :rf.resource/clear-work-handle when an attempt is superseded /
;; settled. Per Spec 016 §Frame work ledger. Registered in the façade so a
;; `:reload` re-wires them.
(fx/reg-fx :rf.resource/record-work-handle
           work-ledger/record-work-handle-meta
           work-ledger/record-work-handle-handler)
(fx/reg-fx :rf.resource/clear-work-handle
           work-ledger/clear-work-handle-meta
           work-ledger/clear-work-handle-handler)

;; Stale / GC timer side-table write fx (rf2-nbjewi). The stale / GC timer
;; handles are host-side transient state (NOT runtime-db), so their writes
;; ride fx exactly as the generation high-water bump + work-handle side-table
;; writes do. The success reply handler emits :rf.resource/schedule-timers
;; once an entry settles :loaded (arming the advisory stale / GC timers from
;; the durable :loaded-at + policy); remove / clear-scope / a fired GC emit
;; :rf.resource/cancel-timers. Both are `:platforms #{:client}` — the runtime
;; platform gate skips them under SSR (which uses the blocking-drain wait
;; point + lazy client revalidation, never wall-clock background timers). Per
;; Spec 016 §Stale and GC scheduling. Registered in the façade so a `:reload`
;; re-wires them.
(fx/reg-fx :rf.resource/schedule-timers
           timers/schedule-timers-meta
           timers/schedule-timers-handler)
(fx/reg-fx :rf.resource/cancel-timers
           timers/cancel-timers-meta
           timers/cancel-timers-handler)

;; The interceptor injected into the load-causing events (ensure / refetch)
;; so their handlers read the host-side generation high-water snapshot under
;; `:coeffects :rf.resource/generation` and mint the next monotone
;; generation purely.
(def ^:private generation-interceptors
  [(cofx/inject-cofx :rf.resource/generation)])

;; Public resource events (map payloads). Per Spec 016 §Events.
(events/reg-event-fx :rf.resource/ensure
                     framework-authority-meta
                     generation-interceptors
                     resource-events/ensure-handler)
(events/reg-event-fx :rf.resource/refetch
                     framework-authority-meta
                     generation-interceptors
                     resource-events/refetch-handler)
(events/reg-event-fx :rf.resource/invalidate-tags
                     framework-authority-meta
                     resource-events/invalidate-tags-handler)
(events/reg-event-fx :rf.resource/release-owner
                     framework-authority-meta
                     resource-events/release-owner-handler)
(events/reg-event-fx :rf.resource/clear-scope
                     framework-authority-meta
                     resource-events/clear-scope-handler)
(events/reg-event-fx :rf.resource/remove
                     framework-authority-meta
                     resource-events/remove-handler)

;; Focus / reconnect revalidation events (rf2-vtblcq, Spec 016 §Stale and GC
;; scheduling / §Deferred slices). The host focus / online listeners
;; (`re-frame.resources.revalidate-listeners`) dispatch these; each scans the
;; frame's active-owner STALE entries and refetches them in the background
;; with cause `:focus` / `:reconnect` (a CAUSE, never an owner — the refetch
;; attaches no owner, so it never creates liveness; generation +
;; stale-suppression protect late replies). They make no durable write
;; themselves (only `:rf.resource/refetch` dispatches), but carry the
;; framework-authority stamp for family uniformity. User code MUST NOT
;; dispatch them directly.
(events/reg-event-fx :rf.resource/window-focused
                     framework-authority-meta
                     resource-events/window-focused-handler)
(events/reg-event-fx :rf.resource/network-reconnected
                     framework-authority-meta
                     resource-events/network-reconnected-handler)

;; Framework-internal reply handlers. Per Spec 016 §Events / §Transport.
;; User code MUST NOT dispatch these.
(events/reg-event-fx :rf.resource.internal/succeeded
                     framework-authority-meta
                     resource-events/succeeded-handler)
(events/reg-event-fx :rf.resource.internal/failed
                     framework-authority-meta
                     resource-events/failed-handler)
(events/reg-event-fx :rf.resource.internal/aborted
                     framework-authority-meta
                     resource-events/aborted-handler)
(events/reg-event-fx :rf.resource.internal/stale-fired
                     framework-authority-meta
                     resource-events/stale-fired-handler)
(events/reg-event-fx :rf.resource.internal/gc-fired
                     framework-authority-meta
                     resource-events/gc-fired-handler)
(events/reg-event-fx :rf.resource.internal/stale-suppressed
                     framework-authority-meta
                     resource-events/stale-suppressed-handler)

;; Passive resource subs. Per Spec 016 §Subscriptions.
(resource-subs/register-subs!)

;; Mutations (rf2-dwme29, Spec 016 §Deferred slices / EP-0003 §Mutations —
;; the first public-beta gate). The causal-write counterpart of the resource
;; events: `:rf.mutation/execute` mints an instance + work-ledger record and
;; lowers the write through the SAME managed-HTTP transport; on success it
;; patches / populates resource entries then invalidates tags (composing with
;; the landed `:rf.resource/invalidate-tags`); `:rf.mutation/clear` is the
;; causal instance reset. `:rf.mutation/execute` mints a generation (the same
;; host-side monotone allocator the resources use, for stale suppression), so
;; it injects the `:rf.resource/generation` cofx. The internal replies carry
;; the verification payload (instance id + work-id + generation). User code
;; MUST NOT dispatch the internal replies.
(events/reg-event-fx :rf.mutation/execute
                     framework-authority-meta
                     generation-interceptors
                     mutation-events/execute-handler)
(events/reg-event-fx :rf.mutation/clear
                     framework-authority-meta
                     mutation-events/clear-handler)
(events/reg-event-fx :rf.mutation.internal/succeeded
                     framework-authority-meta
                     mutation-events/succeeded-handler)
(events/reg-event-fx :rf.mutation.internal/failed
                     framework-authority-meta
                     mutation-events/failed-handler)

;; Passive mutation subs. Per EP-0003 §Mutations.
(mutation-subs/register-subs!)

;; LATE-BOUND cross-feature integrations (Spec 016 §Route integration /
;; §SSR and hydration). Wired here so they re-install on a `:reload`. Each
;; publishes a late-bind hook the host artefact (routing / ssr) CONSULTS;
;; both are no-op-effect on an app that never loads the host artefact.
(route/install-routing-integration!)
(ssr/install-ssr-integration!)

;; rf2-afpdkn / rf2-nbjewi: release the destroyed frame's host-side TRANSIENT
;; resource caches — the work-ledger host handles (AbortControllers,
;; `re-frame.resources.work-ledger/handle-table`), the stale / GC timer
;; handles (`re-frame.resources.timers/timer-table`, rf2-nbjewi), AND the
;; generation high-water mark (`re-frame.resources.state/generation-cache`).
;; None is runtime-db state — all live in module-level atoms (host-derived,
;; ephemeral, off the epoch / SSR egress wire; the generation host-side so an
;; epoch restore cannot rewind + recycle a generation). `frame/destroy-frame!`
;; invokes this SINGLE hook by key (no static dep on resources — the artefact
;; is optional; ONE teardown path, not three). The durable serializable work
;; records + cache entries ride the frame value and are released atomically
;; when the frame is dropped; this hook touches ONLY the host side tables. Per
;; Spec 016 [Runtime-Subsystems] clause 5 / §Stale and GC scheduling (frame
;; destroy cancels all resource timers for that frame). Composed here (one
;; late-bind key → one fn) mirroring routing's `:routing/on-frame-destroyed!`.
(defn- release-resources-host-caches!
  "Release ALL of the destroyed frame's host-side transient resource caches
  (work-ledger host handles + stale / GC timer handles + generation
  high-water mark + focus/reconnect revalidation listeners — rf2-vtblcq).
  The `:resources/on-frame-destroyed!` teardown body — one composed hook, no
  second teardown path."
  [frame-id]
  (work-ledger/on-frame-destroyed! frame-id)
  (timers/on-frame-destroyed! frame-id)
  (revalidate-listeners/on-frame-destroyed! frame-id)
  (state/release-frame! frame-id)
  nil)

(late-bind/set-fn! :resources/on-frame-destroyed! release-resources-host-caches!)

;; ---- late-bind hook registration ------------------------------------------
;; `re-frame.core` MUST NOT `:require [re-frame.resources]` — the artefact
;; is optional. Public-API re-exports are published through the late-bind
;; table; consumers without the artefact see the hooks unregistered and the
;; wrappers throw `:rf.error/resources-artefact-missing`. The
;; `:resources/reg-resource` key doubles as the feature-inspection PROBE
;; (re-frame.features) — its presence in the late-bind table is the
;; loaded?-signal for the `:resources` feature.

;; The test-isolation reset hook (`:resources/reset-resources!`) is NOT
;; published here — it lives behind an explicit
;; `re-frame.resources.test-support` require (the rf2-dbiv8 posture: keep
;; test fixtures out of the always-on production façade), which publishes
;; it from its own ns-load. The shared CLJS make-reset-runtime-fixture
;; reset-hooks table consults it by key and no-ops when test-support is
;; absent.
(late-bind/set-fns!
  {:resources/reg-resource   reg-resource
   :resources/clear-resource clear-resource
   :resources/resource-meta  resource-meta
   :resources/resource-state resource-state
   :resources/resources      resources
   ;; rf2-vtblcq: the focus/reconnect revalidation host-listener install
   ;; surface (CLJS-only host listeners; JVM no-op). Published so re-frame.core
   ;; can reach it without a static :require, mirroring routing's
   ;; :routing/install-history-listener!.
   :resources/install-revalidation-listeners! install-revalidation-listeners!
   :resources/remove-revalidation-listeners!  remove-revalidation-listeners!
   ;; rf2-dwme29 (EP-0003 §Mutations, first public-beta gate): the mutation
   ;; registration + introspection surface, published through the same
   ;; late-bind table so `re-frame.core`'s `reg-mutation` / `clear-mutation`
   ;; / `mutation-meta` / `mutation-state` / `mutations` wrappers reach the
   ;; producing impl without a static :require.
   :resources/reg-mutation   reg-mutation
   :resources/clear-mutation clear-mutation
   :resources/mutation-meta  mutation-meta
   :resources/mutation-state mutation-state
   :resources/mutations      mutations
   ;; rf2-hls77w (EP-0016 D3 slice 2): the named resource-scope resolver
   ;; surface, published through the same late-bind table so
   ;; `re-frame.core`'s `reg-resource-scope` / `clear-resource-scope` /
   ;; `resolve-resource-scope` wrappers reach the producing impl without a
   ;; static :require.
   :resources/reg-resource-scope     reg-resource-scope
   :resources/clear-resource-scope   clear-resource-scope
   :resources/resolve-resource-scope resolve-resource-scope})
