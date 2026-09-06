(ns re-frame.resources
  "Resources — declarative server-state as a runtime-managed read model
  over a frame work ledger. Per Spec 016.

  A resource is a named, cached read of remote or external state.
  `reg-resource` registers it; views read it through PASSIVE subscriptions
  (`[:rf/resource …]`); route entry, events, and machines CAUSE it
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

  This is an optional artefact
  (`day8/re-frame2-resources`). `re-frame.core` MUST NOT `:require` it; the
  public-API surface is published through the late-bind table, so an app
  that omits the artefact sees the wrappers throw a clean
  `:rf.error/resources-artefact-missing`. The routing + SSR integrations
  are LATE-BOUND (resources never statically `:require`s routing / ssr /
  http), so an app that loads resources but not those optional artefacts
  carries none of their code. Nothing here `:require`s from `tools/`.

  Loading this namespace registers the complete resource and mutation event,
  subscription, effect, and integration surface."
  (:require [re-frame.cofx :as rf.cofx]
            [re-frame.error :as rf.error]
            [re-frame.events :as rf.events]
            [re-frame.frame :as rf.frame]
            [re-frame.fx :as rf.fx]
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
            ;; The OFF-BOX trace-row egress projector for
            ;; the resource/mutation trace family's scoped-key slots. A
            ;; production-reachable ns (NOT the bundle-isolated tooling sibling)
            ;; so the `:resources/project-resource-trace-egress` hook below is
            ;; published on BOTH runtimes whenever resources is loaded — the
            ;; epoch tool-pair consults it on every off-box record projection.
            [re-frame.resources.trace-egress :as rf.resources.trace-egress]
            [re-frame.resources.work-ledger :as rf.resources.work-ledger]
            ;; JVM-only require of the resources tooling sibling that backs the
            ;; `resource-algebra-view`
            ;; / `resource-cache-algebra-view` aliases at the foot of this ns.
            ;; CLJS deliberately OMITS this require so a CLJS app that loads the
            ;; resources artefact but never attaches a tool DCEs the tooling
            ;; body wholesale — the facade never reaches it. JVM has no bundle
            ;; to protect; the alias gives JVM tools / conformance fixtures the
            ;; ergonomic `re-frame.resources/<name>` shape.
            #?@(:clj [[re-frame.resources.tooling :as rf.resources.tooling]])))

#?(:clj (set! *warn-on-reflection* true))

;; ---- public-surface re-exports --------------------------------------------
;; These `def`s make the sibling fns reachable as
;; `re-frame.resources/<name>` so consumers (the `re-frame.core` late-bind
;; bridge, conformance, tests, examples) see one surface.

(def reg-resource    rf.resources.registry/reg-resource)
(def clear-resource  rf.resources.registry/clear-resource)
(def resource-meta   rf.resources.registry/resource-meta)
(def resource-ids    rf.resources.registry/resource-ids)

;; Derivation/process algebra views of
;; registered resources (`resource-algebra-view`, static) and of a frame's
;; live cache entries (`resource-cache-algebra-view`, live). A resource is the
;; canonical PROCESS member of the algebra (Derivations §Process). The static
;; view is JVM-runnable (the resource registry is partition-agnostic metadata),
;; so JVM convenience aliases let tools / conformance fixtures reach it as
;; `re-frame.resources/<name>` without naming the sibling. The bodies live in
;; `re-frame.resources.tooling` so a CLJS app that loads the resources artefact
;; but attaches no tool DCEs them (the CLJS facade never `:require`s the tooling
;; sibling — the require above is `#?@(:clj ...)`-gated). CLJS consumers (Xray +
;; conformance) call `re-frame.resources.tooling/<name>` directly. No
;; `re-frame.core` facade export.
#?(:clj
   (do
     (def resource-algebra-view       rf.resources.tooling/resource-algebra-view)
     (def resource-cache-algebra-view rf.resources.tooling/resource-cache-algebra-view)))

;; Mutations (Spec 016 §Mutations). `reg-mutation` registers a causal-write
;; mutation; `:rf.mutation/execute` runs it over the SAME managed-HTTP
;; transport the resources use, with success-time resource invalidation /
;; patch / populate; `clear-mutation` is the registration-lifecycle removal.
(def reg-mutation    rf.resources.mutation-registry/reg-mutation)
(def clear-mutation  rf.resources.mutation-registry/clear-mutation)
(def mutation-meta   rf.resources.mutation-registry/mutation-meta)
(def mutation-ids    rf.resources.mutation-registry/mutation-ids)

;; Named resource-scope resolvers (Spec 016 §Named resource-scope resolvers).
;; `reg-resource-scope`
;; registers a PURE named scope resolver under the `:resource-scope`
;; registrar kind; `clear-resource-scope` is the registration-lifecycle
;; removal; `resolve-resource-scope` is a resolver helper that resolves a
;; named scope against a supplied db value (the logout coeffect-db idiom) —
;; a PURE function over the resolver registry: not an effect (no app-state /
;; dispatch side effects) and has NO observability side effect, so it does NOT
;; emit `:rf.resource/scope-resolved` trace. The
;; `:rf.resource/scope-resolved` evidence is emitted only at the CAUSAL
;; resolution boundaries (`{:from-db ...}` scope, route entry, mutation
;; settle), per Spec 016 §Named resource-scope resolvers.
(def reg-resource-scope     rf.resources.scope-registry/reg-resource-scope)
(def clear-resource-scope   rf.resources.scope-registry/clear-resource-scope)
(def resolve-resource-scope rf.resources.scope-registry/resolve-resource-scope)
(def scope-resolver-meta    rf.resources.scope-registry/scope-resolver-meta)
(def scope-resolver-ids     rf.resources.scope-registry/scope-resolver-ids)

(defn resources
  "Return resource introspection for a frame target (Spec 016
  §Introspection). Returns `{:resource-ids [...] :entries {…}}` — the
  static registry (every registered resource id) plus, when `:frame` is
  supplied, the live per-frame resource-instance entries map
  (`{<key-id> <entry>}`). Without `:frame` only the static
  registry is returned; there is no ambient frame fallback.

  The `:entries` map is keyed on the CEDN-1 byte `key-id` STRING (the SAME
  key the internal runtime storage, the SSR wire, and the reverse indexes
  use — `rf.resources.state/entries-path`, `rf.resources.state/key-id`); each entry carries its
  kind-preserving public scoped-resource-key VECTOR
  `[canonical-scope resource-id canonical-params]` under `:resource/key`.
  Callers that need to destructure
  `[scope resource-id params]`, filter by scope/resource, or compare
  against scoped keys read each entry's `:resource/key`; the byte string
  map key is purely an opaque distinct identity.

  WHY the map key is the byte string, NOT the scoped-key vector: Clojure
  map keys compare by `=`, and `=` is COARSER than the CEDN-1 byte identity
  for SEQUENTIAL params — `(= [scope rid {:xs [1 2 3]}] [scope rid {:xs '(1 2 3)}])`
  is TRUE while their `canonical-bytes` differ (`v[…]` vs `l(…)`). Rekeying
  the byte-keyed runtime map onto the `=`-colliding vector would `assoc`
  one CEDN-distinct entry OVER the other, so the public read could report
  ONE entry for TWO live cache entries. Keying on the byte
  `key-id` string (which compares by content, the exact CEDN-1 identity)
  keeps every live entry distinct. Internal storage stays byte-keyed."
  ([] {:resource-ids (resource-ids) :entries {}})
  ([{:keys [frame]}]
   {:resource-ids (resource-ids)
    :entries      (if frame
                    (let [entries (get-in (rf.frame/frame-runtime-db-value frame)
                                          (rf.resources.state/entries-path))]
                      ;; Preserve the byte-keyed map: re-keying on scoped-key
                      ;; vectors can collapse CEDN-distinct sequential params
                      ;; that compare equal under Clojure `=`.
                      (or entries {}))
                    {})}))

(defn resource-state
  "Return a resource instance's durable runtime ENTRY for an explicit
  `:frame` introspection target `{:resource :scope :params :frame}` (Spec
  016 §Introspection), or nil when no entry exists for that scoped key in
  that frame. The frame target is explicit; a frameless call FAILS CLOSED:
  an absent / nil `:frame` raises
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
    (rf.error/throw-error!
      :rf.error/no-frame-context
      'rf/resource-state
      (str "resource-state requires an explicit :frame "
           "introspection target. A frameless call would "
           "pass nil through to the runtime-db lookup and "
           "return nil — indistinguishable from a genuinely "
           "absent entry. Pass {:resource … :scope … "
           ":params … :frame <frame-id>}. Per Spec 016 "
           "§Introspection.")
      {:recovery :pass-frame
       :extra    {:opts (dissoc opts :frame)}}))
  (let [;; A `{:from-db <id>}` scope on the introspection target resolves
        ;; against the frame's app-db value (the same db the
        ;; reactive sub resolves against), so `resource-state` and a live
        ;; `[:rf/resource …]` sub resolve the SAME scoped key.
        scoped-key (rf.resources.subs/resolve-scoped-key
                     opts (rf.frame/frame-app-db-value frame))
        runtime-db (rf.frame/frame-runtime-db-value frame)]
    (get-in runtime-db (rf.resources.state/entry-path scoped-key))))

(defn mutations
  "Return mutation introspection for a frame target (Spec 016 §Mutations /
  Xray). Returns `{:mutation-ids [...] :instances {…}}` — the static
  registry (every registered mutation id) plus, when `:frame` is supplied,
  the live per-frame mutation-INSTANCE map. That map is keyed on each
  instance id's CEDN-1 byte `key-id` (`{<key-id> <instance>}`), NOT the raw
  instance id — each instance carries its
  kind-preserving id alongside under `:instance/id`. Xray groups instances
  under their registered `:mutation/id` while showing each separately.
  Without `:frame` only the static registry is returned (no ambient frame
  fallback)."
  ([] {:mutation-ids (mutation-ids) :instances {}})
  ([{:keys [frame]}]
   {:mutation-ids (mutation-ids)
    :instances    (if frame
                    (or (get-in (rf.frame/frame-runtime-db-value frame)
                                (rf.resources.mutation-runtime/instances-path)) {})
                    {})}))

(defn mutation-state
  "Return a mutation INSTANCE's durable runtime row for an explicit
  `:frame` introspection target `{:instance :frame}` (Spec 016 §Mutations),
  or nil when no instance exists under that instance id in that frame. The
  frame target is explicit; a frameless call FAILS CLOSED: an absent / nil
  `:frame` raises the structured
  `:rf.error/no-frame-context` rather than passing nil through to a
  runtime-db lookup that returns nil — a nil that is INDISTINGUISHABLE from
  a genuinely absent instance. This mirrors `resource-state`'s explicit-frame
  contract; the two introspection halves fail closed symmetrically.

  Frame existence is NOT a precondition: an explicit but unknown / destroyed
  `:frame` reads as `nil` runtime-db and returns `nil` (no instance) — the
  same result as a live frame with no instance under that id. The fail-closed
  boundary is the MISSING explicit target, not a vanished one; a valid
  explicit frame lookup returns `nil` only for a genuinely absent instance."
  [{:keys [instance frame] :as opts}]
  (when (nil? frame)
    (rf.error/throw-error!
      :rf.error/no-frame-context
      'rf/mutation-state
      (str "mutation-state requires an explicit :frame "
           "introspection target. A frameless call would "
           "pass nil through to the runtime-db lookup and "
           "return nil — indistinguishable from a genuinely "
           "absent instance. Pass {:instance … "
           ":frame <frame-id>}. Per Spec 016 §Mutations.")
      {:recovery :pass-frame
       :extra    {:opts (dissoc opts :frame)}}))
  (let [runtime-db (rf.frame/frame-runtime-db-value frame)]
    (get-in runtime-db (rf.resources.mutation-runtime/instance-path instance))))

;; A resource / mutation state read is a subscription VECTOR —
;; `(subscribe [:rf/resource
;; <query>])` / `(subscribe [:rf/mutation {:instance <instance>}])` —
;; one read grammar; `subscribe`'s own frame-first arity carries the
;; `{:frame …}` target for an explicit frame.

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
(def ^:private framework-authority-meta rf.resources.state/framework-authority-meta)

;; :rf.resource/generation-allocation cofx + :rf.resource/commit-generation
;; fx — Spec 016 §Restore and replay part 1 + 002 §Durable join keys are
;; recordable. The host-side generation allocator is a monotone
;; high-water mark that never rewinds across epoch restore (so a pre-restore
;; in-flight reply's generation can never match a post-restore live entry).
;; The generation is also a DURABLE JOIN KEY (written onto the entry/instance
;; and stamped on the reply token for stale suppression), so the minted VALUE
;; must be RECORDABLE even though the allocator stays host-transient: the
;; `:rf.resource/generation-allocation` cofx is GENERATOR-BACKED + recordable
;; — its generator mints the next allocation at processing-start and the
;; runtime records the value on the token (replay re-presents it; strict
;; replay fails loud on a missing allocation rather than re-minting a
;; divergent generation). The ensure/refetch/execute handlers read the
;; recorded `:generation` flat and write only it durably; the fx advances the
;; host high-water with `max`. Registered in the façade so a `:reload`
;; re-wires them.
(rf.cofx/reg-cofx :rf.resource/generation-allocation
               rf.resources.state/generation-allocation-cofx-meta
               rf.resources.state/generation-allocation-cofx)
(rf.fx/reg-fx :rf.resource/commit-generation
           rf.resources.state/commit-generation-meta
           rf.resources.state/commit-generation-handler)

;; Work-ledger host-handle side-table write fx. The work-handle
;; side table is host-side transient state (NOT runtime-db), so its writes
;; ride fx exactly as the host-side generation high-water bump does. The
;; runtime emits :rf.resource/record-work-handle alongside the transport
;; lower and :rf.resource/clear-work-handle when an attempt is superseded /
;; settled. Per Spec 016 §Frame work ledger. Registered in the façade so a
;; `:reload` re-wires them.
(rf.fx/reg-fx :rf.resource/record-work-handle
           rf.resources.work-ledger/record-work-handle-meta
           rf.resources.work-ledger/record-work-handle-handler)
(rf.fx/reg-fx :rf.resource/clear-work-handle
           rf.resources.work-ledger/clear-work-handle-meta
           rf.resources.work-ledger/clear-work-handle-handler)

;; Stale / GC timer side-table write fx. The stale / GC timer
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
(rf.fx/reg-fx :rf.resource/schedule-timers
           rf.resources.timers/schedule-timers-meta
           rf.resources.timers/schedule-timers-handler)
(rf.fx/reg-fx :rf.resource/cancel-timers
           rf.resources.timers/cancel-timers-meta
           rf.resources.timers/cancel-timers-handler)
;; The poll-only cancel fx. `:rf.resource/release-owner`
;; emits it for each entry that became owner-free (polling stops the instant
;; the last owner releases — a poll never pins an owner-free entry; the stale
;; / GC timers stay armed because an owner-free entry still GCs).
(rf.fx/reg-fx :rf.resource/cancel-poll-timers
           rf.resources.timers/cancel-poll-timers-meta
           rf.resources.timers/cancel-poll-timers-handler)

;; A time-consuming resource / mutation handler DECLARES the
;; framework-stamped causal-time fact `:rf/time-ms` via `:rf.cofx/requires`
;; through `:rf.cofx/requires`. Under declared-only delivery the runtime stages
;; EXACTLY the facts a handler declares, FLAT in the coeffects map — nothing
;; implicit, including `:rf/time-ms` (re-frame.cofx ns docstring; the router
;; stamps it on the dispatch envelope's `:rf.cofx`, but the handler only
;; SEES it if it declares it). The handlers read the delivered flat
;; `(:rf/time-ms coeffects)` for their replay-relevant freshness decisions and
;; durable timestamps (fresh-skip / `:started-at` / `:invalidated-at` /
;; `:completed-at` → `:loaded-at` / `:settled-at` / stale-timer re-check) — NOT
;; through the whole `:rf.cofx` token. Declaring it also makes `handler-meta`
;; expose the time requirement so strict replay + tooling can see the
;; dependency. `:rf/time-ms` is the framework's ONE built-in recordable+provided
;; cofx (re-frame.cofx); it is registered, so a declaration is never the
;; `:rf.error/unregistered-cofx` typo case.
(def ^:private time-meta
  (assoc framework-authority-meta
         :rf.cofx/requires [:rf/time-ms]))

;; Load-causing events (ensure / refetch / mutation execute) DECLARE the
;; RECORDABLE generation-allocation cofx AND the framework-stamped
;; `:rf/time-ms` via `:rf.cofx/requires`. Their
;; handlers read the recorded allocation FLAT under `:coeffects
;; :rf.resource/generation-allocation` (`{:generation N :counter N}`) and the
;; causal time FLAT under `:coeffects :rf/time-ms` — the generation comes
;; from the recorded allocation rather than an ambient `(inc snapshot)`, and
;; the time fact is read directly from the coeffects, not through the whole
;; `:rf.cofx` token. The allocation is recorded on the token
;; (generator-backed recordable cofx) so replay reproduces an identical
;; generation; the host high-water advances via the
;; `:rf.resource/commit-generation` fx (`max`).
(def ^:private generation-meta
  (assoc framework-authority-meta
         :rf.cofx/requires [:rf.resource/generation-allocation :rf/time-ms]))

;; Public resource events (map payloads). Per Spec 016 §Events.
;; Every entry-mutating handler is wrapped with
;; `rf.resources.events/with-classification-lowering` so its durable `:rf.db/runtime`
;; transition LOWERS each live entry's projection-relative `:sensitive` /
;; `:large` classification into the per-frame elision registry under `:source
;; :resource` (the routing/machines lowering peer),
;; instead of re-deriving it only at the family-private projectors. The
;; reconciliation is idempotent + self-dropping, so the registry's resource-
;; sourced set stays in step with `:entries` no matter which handler ran.
(rf.events/reg-event :rf.resource/ensure
                     generation-meta
                     (rf.resources.events/with-classification-lowering
                       rf.resources.events/ensure-handler))
(rf.events/reg-event :rf.resource/refetch
                     generation-meta
                     (rf.resources.events/with-classification-lowering
                       rf.resources.events/refetch-handler))
;; `:rf.resource/load-more` extends an infinite feed by one page.
;; It mints a generation (the same host-side monotone allocator, for stale
;; suppression — the work-id derives from it) and records the work-ledger
;; `:started-at` from the causal `:rf/time-ms`, so it declares the recordable
;; generation-allocation cofx + the time cofx exactly like ensure / refetch.
(rf.events/reg-event :rf.resource/load-more
                     generation-meta
                     (rf.resources.events/with-classification-lowering
                       rf.resources.events/load-more-handler))
;; `:rf.resource.internal/refetch-page` re-fetches
;; ONE page of a multi-page refetch sweep (`:refetch-all-pages?` /
;; `:refetch-window`). Chained by `page-succeeded-handler` one leg at a time; it
;; mints a fresh generation (the work-id derives from it, for per-leg stale
;; suppression) and records the work `:started-at` from the causal `:rf/time-ms`,
;; so it declares the recordable generation-allocation + time cofx exactly like
;; load-more. User code MUST NOT dispatch it.
(rf.events/reg-event :rf.resource.internal/refetch-page
                     generation-meta
                     (rf.resources.events/with-classification-lowering
                       rf.resources.events/refetch-page-handler))
;; `invalidate-tags` writes the durable `:invalidated-at`
;; fact from the event's causal `:rf/time-ms`, so it declares the time cofx.
(rf.events/reg-event :rf.resource/invalidate-tags
                     time-meta
                     (rf.resources.events/with-classification-lowering
                       rf.resources.events/invalidate-tags-handler))
(rf.events/reg-event :rf.resource/release-owner
                     framework-authority-meta
                     (rf.resources.events/with-classification-lowering
                       rf.resources.events/release-owner-handler))
;; EP-0037 R2 — the attach-before-release primitive for a KEPT branch-plan
;; identity: attach the next route-plan owner onto a shared ancestor read
;; WITHOUT a fetch (the partial-revalidation law), dispatched ahead of the
;; prior owner's release. See resources.route/route-resource-plan.
(rf.events/reg-event :rf.resource.internal/adopt-owner
                     framework-authority-meta
                     (rf.resources.events/with-classification-lowering
                       rf.resources.events/adopt-route-owner-handler))
;; rf2-y8jjk — the release-side twin of `adopt-owner`: release an owner from a
;; SUBSET of the identities it holds. Dispatched by the route planner on a
;; same-token REPLAN (`:rf.route/replan-resources`) for exactly the identities
;; the new plan dropped, ordered AFTER the attach fx; clears no route slot (the
;; replan commit owns them). A framework primitive, not an app verb. See
;; resources.route/route-resource-plan (plan mode `:replan`).
(rf.events/reg-event :rf.resource.internal/release-owner-identities
                     framework-authority-meta
                     (rf.resources.events/with-classification-lowering
                       rf.resources.events/release-owner-identities-handler))
;; rf2-x76af2.14 — clear-scope / remove settle in-flight work rows terminal
;; `:cancelled`, and a cancellation is a COMPLETION: its terminal outcome carries
;; the event's causal `:completed-at` (from the declared-flat `:rf/time-ms`),
;; symmetric with every reply-driven cancellation (rf2-rl27r2). Declare
;; `time-meta` (framework-authority + the `:rf/time-ms` cofx) so the causal time
;; is delivered flat and the handler can stamp it onto the cancelled work row.
(rf.events/reg-event :rf.resource/clear-scope
                     time-meta
                     (rf.resources.events/with-classification-lowering
                       rf.resources.events/clear-scope-handler))
(rf.events/reg-event :rf.resource/remove
                     time-meta
                     (rf.resources.events/with-classification-lowering
                       rf.resources.events/remove-handler))

;; Focus / reconnect revalidation events (Spec 016 §Stale and GC scheduling).
;; The host focus / online listeners
;; (`re-frame.resources.revalidate-listeners`) dispatch these; each scans the
;; frame's active-owner STALE entries and refetches them in the background
;; with cause `:focus` / `:reconnect` (a CAUSE, never an owner — the refetch
;; attaches no owner, so it never creates liveness; generation +
;; stale-suppression protect late replies). They make no durable write
;; themselves (only `:rf.resource/refetch` dispatches), but carry the
;; framework-authority stamp for family uniformity. User code MUST NOT
;; dispatch them directly.
;; The focus / reconnect scans make a replay-relevant
;; active-stale SELECTION against the token's causal `:rf/time-ms`, so they
;; declare the time cofx (the scan writes nothing durable itself, but a
;; replayed signal must SELECT the same set the recorded time dictated).
(rf.events/reg-event :rf.resource/window-focused
                     time-meta
                     rf.resources.events/window-focused-handler)
(rf.events/reg-event :rf.resource/network-reconnected
                     time-meta
                     rf.resources.events/network-reconnected-handler)

;; Framework-internal reply handlers. Per Spec 016 §Events / §Transport.
;; User code MUST NOT dispatch these.
;; The reply + stale-timer handlers consume
;; the reply / timer token's causal `:rf/time-ms` — succeeded → durable
;; `:loaded-at` / `:stale-at`; failed → the canonical reply's `:completed-at`
;; (an ordinary failure and the `:rf.http/aborted` cancellation branch both
;; carry it, symmetrically with success + mutation); stale-fired → a
;; replay-stable freshness re-check. Each declares the time cofx so the fact
;; is delivered flat.
(rf.events/reg-event :rf.resource.internal/succeeded
                     time-meta
                     (rf.resources.events/with-classification-lowering
                       rf.resources.events/succeeded-handler))
(rf.events/reg-event :rf.resource.internal/failed
                     time-meta
                     (rf.resources.events/with-classification-lowering
                       rf.resources.events/failed-handler))
;; The infinite-feed PAGE reply handlers are DISTINCT from the
;; scalar succeeded / failed replies: a page success APPENDS / replaces-in-place
;; one page (`entry-replace-page`); a page failure is the THIRD error channel
;; (`entry-page-failed` keeps the feed + records `:page-error`). They reuse the
;; same `live-entry-for-reply` verification + stale suppression and consume the
;; reply token's causal `:rf/time-ms` (→ `:loaded-at` / `:completed-at`), so
;; each declares the time cofx. User code MUST NOT dispatch them.
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
;; An active-owner `:poll` timer fired. The handler
;; re-checks the live entry (present? owned? not hidden? not in-flight?) and
;; unconditionally refetches (cause `:poll`, never an owner) when polling
;; should continue, re-arming the next interval. The host `:poll` timer
;; dispatches this; user code MUST NOT dispatch it directly.
(rf.events/reg-event :rf.resource.internal/poll-fired
                     framework-authority-meta
                     (rf.resources.events/with-classification-lowering
                       rf.resources.events/poll-fired-handler))
(rf.events/reg-event :rf.resource.internal/stale-suppressed
                     framework-authority-meta
                     rf.resources.events/stale-suppressed-handler)

;; Passive resource subs. Per Spec 016 §Subscriptions.
(rf.resources.subs/register-subs!)

;; Mutations (Spec 016 §Mutations). The causal-write counterpart of the resource
;; events: `:rf.mutation/execute` mints an instance + work-ledger record and
;; lowers the write through the SAME managed-HTTP transport; on success it
;; patches / populates resource entries then invalidates tags;
;; `:rf.mutation/clear` is the
;; causal instance reset. `:rf.mutation/execute` mints a generation (the same
;; host-side monotone allocator the resources use, for stale suppression), so
;; it declares the recordable `:rf.resource/generation-allocation` cofx
;; and reads the recorded `:generation` flat — the instance-id /
;; work-id derive from it, so recording the generation makes them reproduce on
;; replay for free. The internal replies carry the verification payload
;; (instance id + work-id + generation). User code MUST NOT dispatch the
;; internal replies.
;;
;; The ENTRY-mutating mutation handlers (`execute` optimistic apply / seed;
;; `succeeded` `:patches` / `:populates` / `:removes`; `failed` conflict-aware
;; rollback restore / remove / invalidate) are wrapped in
;; `rf.resources.events/with-classification-lowering` exactly like the resource
;; reply/lifecycle handlers — a `:populates` can CREATE a brand-new registered-
;; resource entry the elision registry never lowered a declaration for, so
;; without the reconcile the per-frame registry drifts out of step with
;; `:entries` and a fine-grained-classified field would ride egress verbatim
;; (rf2-x76af2.13). The reconcile is idempotent + value-independent, so it is
;; safe to add and rides unchanged when a handler makes no durable write.
;; rf2-825mzj — the mutation INSTANCE-mutating handlers ALSO lower each live
;; instance's owner-declared projection-relative `:sensitive` / `:large`
;; classification into the per-frame elision registry (under `:source :mutation`,
;; via `with-mutation-classification-lowering`), so a `:sensitive [[:params
;; :password]]` mutation's durable instance `:params` redacts at every registry-
;; reading egress boundary (epoch export, off-box tool, MCP) while the raw value
;; stays on the instance for the success-path `:invalidates` / `:patches`. This
;; is the mutation peer of the resource-entry `with-classification-lowering`
;; fold. `:rf.mutation/clear` DROPS an instance, so it is wrapped too (the
;; self-dropping reconcile removes the cleared instance's lowered declaration).
(defn- with-mutation-classification-lowering
  "Wrap a mutation event handler `f` so the `:rf.db/runtime` value of its returned
  effects map is reconciled through `rf.resources.classification/reconcile-mutation-registry`
  (resolver = `rf.resources.mutation-registry/mutation-meta`) — lowering each live mutation
  instance's projection-relative classification into the per-frame elision
  registry under `:source :mutation`. Composes with the resource-entry
  `rf.resources.events/with-classification-lowering` (each reconcile touches only its
  own registry owner, so order is immaterial). A returned map with no
  `:rf.db/runtime` key rides unchanged."
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
                     framework-authority-meta
                     (with-mutation-classification-lowering
                       rf.resources.mutation-events/clear-handler))
;; The mutation reply handlers consume the reply token's
;; causal `:rf/time-ms` for the canonical reply's `:completed-at` → the durable
;; instance `:settled-at` + any patch / populate `:loaded-at`, so they declare
;; the time cofx (success + failure both, symmetric).
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

;; Passive mutation subs. Per Spec 016 §Mutations.
(rf.resources.mutation-subs/register-subs!)

;; rf2-3ej3xu — the dispatched-event trace projection for
;; `[:rf.mutation/execute …]`. The execute payload's classification lives on
;; the MUTATION spec named INSIDE the args (per-owner, the SAME rf2-825mzj
;; declaration surface the durable instance + continuation reply read), not on
;; the `:rf.mutation/execute` event registration, so the core event-vector
;; projection chokepoint (`re-frame.classification/redact-event-by-registration`)
;; defers to this resources-owned hook — the event peer of
;; `:http/project-managed-fx-args`. Published from the facade (not the
;; classification ns) because the resolver is `rf.resources.mutation-registry/mutation-meta`
;; and the registry requires the classification ns (a back-require would cycle).
(rf.late-bind/set-fn! :resources/project-execute-event-args
                   (fn project-execute-event-args-hook [args]
                     (rf.resources.classification/project-execute-event-args
                       args rf.resources.mutation-registry/mutation-meta)))

;; LATE-BOUND cross-feature integrations (Spec 016 §Route integration /
;; §SSR and hydration). Wired here so they re-install on a `:reload`. Each
;; publishes a late-bind hook the host artefact (routing / ssr) CONSULTS;
;; both are no-op-effect on an app that never loads the host artefact.
(rf.resources.route/install-routing-integration!)
(rf.resources.ssr/install-ssr-integration!)

;; Release the destroyed frame's host-side TRANSIENT
;; resource caches — the work-ledger host handles (AbortControllers,
;; `re-frame.resources.work-ledger/handle-table`), the stale / GC timer
;; handles (`re-frame.resources.timers/timer-table`), AND the
;; generation high-water mark (`re-frame.resources.state/generation-cache`).
;; None is runtime-db state — all live in module-level atoms (host-derived,
;; ephemeral, off the epoch / SSR egress wire; the generation host-side so an
;; epoch restore cannot rewind + recycle a generation). `rf.frame/destroy-frame!`
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
  high-water mark + focus/reconnect revalidation listeners).
  The `:resources/on-frame-destroyed!` teardown body — one composed hook, no
  second teardown path."
  [frame-id]
  (rf.resources.work-ledger/on-frame-destroyed! frame-id)
  (rf.resources.timers/on-frame-destroyed! frame-id)
  (rf.resources.revalidate-listeners/on-frame-destroyed! frame-id)
  (rf.resources.state/release-frame! frame-id)
  nil)

(rf.late-bind/set-fn! :resources/on-frame-destroyed! release-resources-host-caches!)

;; Revalidation is a FRAME PROPERTY, like URL ownership (rf2-kuky.33,
;; following routing's `:url-bound?` fold — rf2-g8pbwg, API-shrink #6). The
;; frame's `:revalidate-on` config key — a set drawn from the closed enum
;; `#{:focus :reconnect}` — is honoured by the frame (re-)registration
;; lifecycle: this hook fires at the END of BOTH `upsert-frame!` branches,
;; AFTER the container is live and `:initial-events` ran, so the listeners a
;; frame declares are installed without the app sequencing anything. There is
;; no install/remove fn. Re-registration RECONCILES (replace-don't-stack, and a
;; re-registration that drops the key relinquishes the listeners); destroy
;; removes through the composed `:resources/on-frame-destroyed!` hook above.
;; Per Spec 016 §Stale and GC scheduling.
(defn- on-frame-registered!
  "The `:resources/on-frame-registered!` lifecycle body: reconcile the
  (re-)registered frame's host focus/reconnect listeners against its
  COMMITTED `:revalidate-on` config value. Reads the frame's own metadata
  rather than a passed config, so it always sees what the registry actually
  holds. Returns nil."
  [frame-id]
  (rf.resources.revalidate-listeners/reconcile-listeners!
    frame-id
    (:revalidate-on (rf.frame/frame-meta frame-id)))
  nil)

(rf.late-bind/set-fn! :resources/on-frame-registered! on-frame-registered!)

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
;; `re-frame.resources.test-support` require to keep test fixtures out of the
;; always-on production façade; that namespace publishes
;; it from its own ns-load. The shared CLJS make-reset-runtime-fixture
;; reset-hooks table consults it by key and no-ops when test-support is
;; absent.
(rf.late-bind/set-fns!
  {:resources/reg-resource   reg-resource
   :resources/clear-resource clear-resource
   :resources/resource-meta  resource-meta
   :resources/resource-state resource-state
   :resources/resources      resources
   ;; Mutation registration + introspection, published through the same
   ;; late-bind table so `re-frame.core`'s `reg-mutation` / `clear-mutation`
   ;; / `mutation-meta` / `mutation-state` / `mutations` wrappers reach the
   ;; producing impl without a static :require.
   :resources/reg-mutation   reg-mutation
   :resources/clear-mutation clear-mutation
   :resources/mutation-meta  mutation-meta
   :resources/mutation-state mutation-state
   :resources/mutations      mutations
   ;; Named resource-scope resolvers, published through the same late-bind
   ;; table so
   ;; `re-frame.core`'s `reg-resource-scope` / `clear-resource-scope` /
   ;; `resolve-resource-scope` wrappers reach the producing impl without a
   ;; static :require.
   :resources/reg-resource-scope     reg-resource-scope
   :resources/clear-resource-scope   clear-resource-scope
   :resources/resolve-resource-scope resolve-resource-scope
   ;; OFF-BOX trace egress projector for a
   ;; `:rf.resource/scope-resolved` row — the central trace egress pipeline
   ;; (epoch tool-pair) consults it to redact the resolver's resolved
   ;; `:input-values` / `:scope` (a value-path walk is structurally blind to
   ;; resolver-owned values once copied into trace tags). Published so the
   ;; epoch artefact reaches it without a static :require on resources.
   :resources/project-scope-resolved-egress
   rf.resources.scope-registry/project-scope-resolved-egress
   ;; Family-level OFF-BOX trace egress projector for
   ;; the rest of the resource/mutation trace family — projects the scoped-key
   ;; slots (`:resource/key` / `:resource/keys` / `:matched` / `:removed` /
   ;; `:keys` / `:exempt` / `:committed` / the rollback `:dispositions` + the
   ;; `:restored` / `:conflicted` / `:refetched` key vectors) through the
   ;; resource OWNER classification, fail-closed on an unregistered owner. The
   ;; broader-family analogue of `:resources/project-scope-resolved-egress`;
   ;; the epoch tool-pair consults it over `:rf.resource/*` + `:rf.mutation/*`
   ;; rows (a generic value-path walk is structurally blind to owner-local
   ;; scoped keys once copied into trace tags). Published so the epoch artefact
   ;; reaches it without a static :require on resources.
   :resources/project-resource-trace-egress
   rf.resources.trace-egress/project-resource-trace-egress
   ;; The same owner classification, reached by SLOT rather than by op
   ;; (rf2-1kiuj). An `ensure` lowers into effects that address the work BY its
   ;; scoped key, so the family's keys ride `:rf.fx/args` / `:rf.event/fx` on
   ;; `:rf.fx/*` / `:rf.error/*` rows — which the namespace routing above never
   ;; reaches, and which the app-db-rooted walk cannot classify. The epoch
   ;; tool-pair applies this to EVERY row (it no-ops on a row carrying neither
   ;; slot), so no row predicate has to be kept in step with the emit sites.
   :resources/project-fx-args-egress
   rf.resources.trace-egress/project-fx-args-egress})
