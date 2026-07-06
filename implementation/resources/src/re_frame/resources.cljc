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
            [re-frame.error :as error]
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
            [re-frame.subs :as subs]
            ;; rf2-8x0gfa (EP-0015): the OFF-BOX trace-row egress projector for
            ;; the resource/mutation trace family's scoped-key slots. A
            ;; production-reachable ns (NOT the bundle-isolated tooling sibling)
            ;; so the `:resources/project-resource-trace-egress` hook below is
            ;; published on BOTH runtimes whenever resources is loaded — the
            ;; epoch tool-pair consults it on every off-box record projection.
            [re-frame.resources.trace-egress :as trace-egress]
            [re-frame.resources.work-ledger :as work-ledger]
            ;; EP-0014 slice-4 (rf2-gn9juw): the JVM-only require of the
            ;; resources tooling sibling that backs the `resource-algebra-view`
            ;; / `resource-cache-algebra-view` aliases at the foot of this ns.
            ;; CLJS deliberately OMITS this require so a CLJS app that loads the
            ;; resources artefact but never attaches a tool DCEs the tooling
            ;; body wholesale — the facade never reaches it. JVM has no bundle
            ;; to protect; the alias gives JVM tools / conformance fixtures the
            ;; ergonomic `re-frame.resources/<name>` shape. Mirrors the
            ;; `re-frame.flows` → `re-frame.flows.tooling` JVM-only require
            ;; (rf2-s8w3nw slice-3) and `re-frame.subs` → `re-frame.subs.tooling`
            ;; (rf2-bmzq0 slice-2).
            #?@(:clj [[re-frame.resources.tooling :as resources-tooling]])))

#?(:clj (set! *warn-on-reflection* true))

;; ---- public-surface re-exports --------------------------------------------
;; These `def`s make the sibling fns reachable as
;; `re-frame.resources/<name>` so consumers (the `re-frame.core` late-bind
;; bridge, conformance, tests, examples) see one surface.

(def reg-resource    registry/reg-resource)
(def clear-resource  registry/clear-resource)
(def resource-meta   registry/resource-meta)
(def resource-ids    registry/resource-ids)

;; EP-0014 slice-4 (rf2-gn9juw): the derivation/process algebra view of
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
;; `re-frame.core` facade export (EP-0014 issue-1 disposition). Mirrors the
;; `re-frame.flows/flow-algebra-view` JVM alias (slice-3).
#?(:clj
   (do
     (def resource-algebra-view       resources-tooling/resource-algebra-view)
     (def resource-cache-algebra-view resources-tooling/resource-cache-algebra-view)))

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
;; removal; `resolve-resource-scope` is a resolver helper that resolves a
;; named scope against a supplied db value (the logout coeffect-db idiom) —
;; a PURE function over the resolver registry: not an effect (no app-state /
;; dispatch side effects) and (rf2-ru73k6) NO observability side effect
;; either, so it does NOT emit `:rf.resource/scope-resolved` trace. The
;; `:rf.resource/scope-resolved` evidence is emitted only at the CAUSAL
;; resolution boundaries (`{:from-db ...}` scope, route entry, mutation
;; settle), per Spec 016 §Named resource-scope resolvers.
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
  registry is returned (no ambient frame fallback, EP-0002).

  The `:entries` map is keyed on the CEDN-1 byte `key-id` STRING (the SAME
  key the internal runtime storage, the SSR wire, and the reverse indexes
  use — `state/entries-path`, `state/key-id`); each entry carries its
  kind-preserving public scoped-resource-key VECTOR
  `[canonical-scope resource-id canonical-params]` under `:resource/key`
  (rf2-jtlq7l / rf2-ka2nkx). Callers that need to destructure
  `[scope resource-id params]`, filter by scope/resource, or compare
  against scoped keys read each entry's `:resource/key`; the byte string
  map key is purely an opaque distinct identity.

  WHY the map key is the byte string, NOT the scoped-key vector: Clojure
  map keys compare by `=`, and `=` is COARSER than the CEDN-1 byte identity
  for SEQUENTIAL params — `(= [scope rid {:xs [1 2 3]}] [scope rid {:xs '(1 2 3)}])`
  is TRUE while their `canonical-bytes` differ (`v[…]` vs `l(…)`). Rekeying
  the byte-keyed runtime map onto the `=`-colliding vector would `assoc`
  one CEDN-distinct entry OVER the other, so the public read could report
  ONE entry for TWO live cache entries (rf2-ka2nkx). Keying on the byte
  `key-id` string (which compares by content, the exact CEDN-1 identity)
  keeps every live entry distinct. Internal storage stays byte-keyed."
  ([] {:resource-ids (resource-ids) :entries {}})
  ([{:keys [frame]}]
   {:resource-ids (resource-ids)
    :entries      (if frame
                    (let [entries (get-in (frame/frame-runtime-db-value frame)
                                          (state/entries-path))]
                      ;; rf2-ka2nkx — the runtime `:entries` map is ALREADY keyed
                      ;; on the CEDN-1 byte `key-id`; return it as-is (every entry
                      ;; carries its kind-preserving `:resource/key` vector). Do
                      ;; NOT rekey onto the `=`-colliding scoped-key vector — that
                      ;; collapses CEDN-distinct sequential-params entries.
                      (or entries {}))
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
    (error/throw-error!
      :rf.error/no-frame-context
      'rf/resource-state
      (str "resource-state requires an explicit :frame "
           "introspection target. A frameless call would "
           "pass nil through to the runtime-db lookup and "
           "return nil — indistinguishable from a genuinely "
           "absent entry. Pass {:resource … :scope … "
           ":params … :frame <frame-id>}. Per Spec 016 "
           "§Introspection / EP-0002.")
      {:recovery :pass-frame
       :extra    {:opts (dissoc opts :frame)}}))
  (let [;; EP-0016 D3 slice 3: a `{:from-db <id>}` scope on the introspection
        ;; target resolves against the frame's app-db value (the same db the
        ;; reactive sub resolves against), so `resource-state` and a live
        ;; `[:rf.resource/state …]` sub resolve the SAME scoped key.
        scoped-key (resource-subs/resolve-scoped-key
                     opts (frame/frame-app-db-value frame))
        runtime-db (frame/frame-runtime-db-value frame)]
    (get-in runtime-db (state/entry-path scoped-key))))

(defn mutations
  "Return mutation introspection for a frame target (EP-0003 §Mutations /
  Xray). Returns `{:mutation-ids [...] :instances {…}}` — the static
  registry (every registered mutation id) plus, when `:frame` is supplied,
  the live per-frame mutation-INSTANCE map. That map is keyed on each
  instance id's CEDN-1 byte `key-id` (`{<key-id> <instance>}`, per
  rf2-8iciw8), NOT the raw instance id — each instance carries its
  kind-preserving id alongside under `:instance/id`. Xray groups instances
  under their registered `:mutation/id` while showing each separately.
  Without `:frame` only the static registry is returned (no ambient frame
  fallback, EP-0002)."
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
  EP-0002 the frame target is carried explicitly; a frameless call FAILS
  CLOSED (rf2-a76921): an absent / nil `:frame` raises the structured
  `:rf.error/no-frame-context` rather than passing nil through to a
  runtime-db lookup that returns nil — a nil that is INDISTINGUISHABLE from
  a genuinely absent instance. This mirrors `resource-state`'s explicit-frame
  contract; the two introspection halves now fail closed symmetrically.

  Frame existence is NOT a precondition: an explicit but unknown / destroyed
  `:frame` reads as `nil` runtime-db and returns `nil` (no instance) — the
  same result as a live frame with no instance under that id. The fail-closed
  boundary is the MISSING explicit target, not a vanished one; a valid
  explicit frame lookup returns `nil` only for a genuinely absent instance."
  [{:keys [instance frame] :as opts}]
  (when (nil? frame)
    (error/throw-error!
      :rf.error/no-frame-context
      'rf/mutation-state
      (str "mutation-state requires an explicit :frame "
           "introspection target. A frameless call would "
           "pass nil through to the runtime-db lookup and "
           "return nil — indistinguishable from a genuinely "
           "absent instance. Pass {:instance … "
           ":frame <frame-id>}. Per EP-0003 §Mutations / "
           "EP-0002.")
      {:recovery :pass-frame
       :extra    {:opts (dissoc opts :frame)}}))
  (let [runtime-db (frame/frame-runtime-db-value frame)]
    (get-in runtime-db (mstate/instance-path instance))))

;; ---- named reactive-read sugar --------------------------------------------
;; Thin read sugar over the canonical `[:rf.mutation/state …]` /
;; `[:rf.resource/state …]` subscription vectors. Defined + exported HERE on
;; the `re-frame.resources` façade (NOT `re-frame.core`) so a non-resources
;; app's production-elision bundle carries no resource/mutation keyword
;; strings — the resources bundle-isolation invariant, the peer of routing's
;; `sub-route`. The vector forms remain the registered subs; these layer over
;; them. Each is a single thin fn (NOT a per-projection family).

(defn sub-mutation
  "Subscribe to a mutation INSTANCE's state. Sugar over
  `(subscribe [:rf.mutation/state {:instance <instance>}])`. Returns a
  reaction over the mutation view-model `{:status :result :error
  :affected-keys :pending? :success? :error? :settled? :optimistic?}` — the
  idle empty-state shape until the instance's first `:rf.mutation/execute`.

  `instance` is the INSTANCE id a view scopes one form submission's state to
  (so a view reading one submission never sees another concurrent submission
  of the same mutation); the `{:instance …}` wrapping lives INSIDE the sugar,
  so callers pass just the instance.

  The 2-arity `opts` map carries the same `{:frame <target>}` capability the
  underlying subscription vector accepts — `<target>` is a frame-id keyword
  or a live frame object — so a read can target an explicit frame from
  outside an established scope. Without `:frame` the read resolves the
  ambient frame through the carried scope/hold chain, exactly like the bare
  `(subscribe [:rf.mutation/state {:instance instance}])`.

  The `[:rf.mutation/state {:instance …}]` vector form remains the canonical
  registered sub; this is ergonomic read sugar over it. Per EP-0003
  §Mutations."
  ([instance] (subs/subscribe [:rf.mutation/state {:instance instance}]))
  ([instance opts]
   (if-let [frame (:frame opts)]
     (subs/subscribe frame [:rf.mutation/state {:instance instance}])
     (subs/subscribe [:rf.mutation/state {:instance instance}]))))

(defn sub-resource
  "Subscribe to a resource instance's state. Sugar over
  `(subscribe [:rf.resource/state <query>])`. Returns a reaction over the
  resource view-model `{:status :data :error :refresh-error :loading?
  :fetching? :stale? :has-data?}` (plus the `:keep-previous?` projection) —
  the idle empty-state shape until a route / event / machine CAUSES the
  fetch (a sub never fetches).

  `query` is the `{:resource :params}` map (with the optional `:scope` the
  sub-side scope resolution reads) the canonical vector form takes; the sugar
  passes it through unchanged, so it resolves the SAME scoped key as
  `(subscribe [:rf.resource/state query])` — including the fail-closed
  `:rf.error/resource-sub-unresolved-scope` boundary.

  The 2-arity `opts` map carries the same `{:frame <target>}` capability the
  underlying subscription vector accepts — `<target>` is a frame-id keyword
  or a live frame object — so a read can target an explicit frame from
  outside an established scope. Without `:frame` the read resolves the
  ambient frame through the carried scope/hold chain, exactly like the bare
  `(subscribe [:rf.resource/state query])`.

  The `[:rf.resource/state <query>]` vector form remains the canonical
  registered sub; this is ergonomic read sugar over it. Per Spec 016
  §Subscriptions."
  ([query] (subs/subscribe [:rf.resource/state query]))
  ([query opts]
   (if-let [frame (:frame opts)]
     (subs/subscribe frame [:rf.resource/state query])
     (subs/subscribe [:rf.resource/state query]))))

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

;; :rf.resource/generation-allocation cofx + :rf.resource/commit-generation
;; fx — Spec 016 §Restore and replay part 1 + 002 §Durable join keys are
;; recordable (rf2-abyycr). The host-side generation allocator is a monotone
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
;; host high-water with `max`. Mirrors routing's nav-allocation seam
;; (rf2-oosjmh / rf2-vcop6y). Registered in the façade so a `:reload`
;; re-wires them.
(cofx/reg-cofx :rf.resource/generation-allocation
               state/generation-allocation-cofx-meta
               state/generation-allocation-cofx)
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
;; EP-0020 §Polling: the poll-only cancel fx. `:rf.resource/release-owner`
;; emits it for each entry that became owner-free (polling stops the instant
;; the last owner releases — a poll never pins an owner-free entry; the stale
;; / GC timers stay armed because an owner-free entry still GCs).
(fx/reg-fx :rf.resource/cancel-poll-timers
           timers/cancel-poll-timers-meta
           timers/cancel-poll-timers-handler)

;; EP-0017: a time-consuming resource / mutation handler DECLARES the
;; framework-stamped causal-time fact `:rf/time-ms` via `:rf.cofx/requires`
;; (rf2-601ife). Under EP-0017 declared-only delivery the runtime stages
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

;; EP-0017: the load-causing events (ensure / refetch / mutation execute)
;; DECLARE the RECORDABLE generation-allocation cofx AND the framework-stamped
;; `:rf/time-ms` via `:rf.cofx/requires` (rf2-abyycr / rf2-601ife). Their
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
;; rf2-v8x9n8 — every entry-mutating handler is wrapped with
;; `resource-events/with-classification-lowering` so its durable `:rf.db/runtime`
;; transition LOWERS each live entry's projection-relative `:sensitive` /
;; `:large` classification into the per-frame elision registry under `:source
;; :resource` (the EP-0025 standard model — the routing/machines lowering peer),
;; instead of re-deriving it only at the family-private projectors. The
;; reconciliation is idempotent + self-dropping, so the registry's resource-
;; sourced set stays in step with `:entries` no matter which handler ran.
(events/reg-event :rf.resource/ensure
                     generation-meta
                     (resource-events/with-classification-lowering
                       resource-events/ensure-handler))
(events/reg-event :rf.resource/refetch
                     generation-meta
                     (resource-events/with-classification-lowering
                       resource-events/refetch-handler))
;; EP-0021 R2 — `:rf.resource/load-more` extends an infinite feed by one page.
;; It mints a generation (the same host-side monotone allocator, for stale
;; suppression — the work-id derives from it) and records the work-ledger
;; `:started-at` from the causal `:rf/time-ms`, so it declares the recordable
;; generation-allocation cofx + the time cofx exactly like ensure / refetch.
(events/reg-event :rf.resource/load-more
                     generation-meta
                     (resource-events/with-classification-lowering
                       resource-events/load-more-handler))
;; EP-0021 R6 (rf2-byl7bk.3.3) — `:rf.resource.internal/refetch-page` re-fetches
;; ONE page of a multi-page refetch sweep (`:refetch-all-pages?` /
;; `:refetch-window`). Chained by `page-succeeded-handler` one leg at a time; it
;; mints a fresh generation (the work-id derives from it, for per-leg stale
;; suppression) and records the work `:started-at` from the causal `:rf/time-ms`,
;; so it declares the recordable generation-allocation + time cofx exactly like
;; load-more. User code MUST NOT dispatch it.
(events/reg-event :rf.resource.internal/refetch-page
                     generation-meta
                     (resource-events/with-classification-lowering
                       resource-events/refetch-page-handler))
;; EP-0017 (rf2-601ife): `invalidate-tags` writes the durable `:invalidated-at`
;; fact from the event's causal `:rf/time-ms`, so it declares the time cofx.
(events/reg-event :rf.resource/invalidate-tags
                     time-meta
                     (resource-events/with-classification-lowering
                       resource-events/invalidate-tags-handler))
(events/reg-event :rf.resource/release-owner
                     framework-authority-meta
                     (resource-events/with-classification-lowering
                       resource-events/release-owner-handler))
(events/reg-event :rf.resource/clear-scope
                     framework-authority-meta
                     (resource-events/with-classification-lowering
                       resource-events/clear-scope-handler))
(events/reg-event :rf.resource/remove
                     framework-authority-meta
                     (resource-events/with-classification-lowering
                       resource-events/remove-handler))

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
;; EP-0017 (rf2-601ife): the focus / reconnect scans make a replay-relevant
;; active-stale SELECTION against the token's causal `:rf/time-ms`, so they
;; declare the time cofx (the scan writes nothing durable itself, but a
;; replayed signal must SELECT the same set the recorded time dictated).
(events/reg-event :rf.resource/window-focused
                     time-meta
                     resource-events/window-focused-handler)
(events/reg-event :rf.resource/network-reconnected
                     time-meta
                     resource-events/network-reconnected-handler)

;; Framework-internal reply handlers. Per Spec 016 §Events / §Transport.
;; User code MUST NOT dispatch these.
;; EP-0017 (rf2-601ife / rf2-rl27r2): the reply + stale-timer handlers consume
;; the reply / timer token's causal `:rf/time-ms` — succeeded → durable
;; `:loaded-at` / `:stale-at`; failed + aborted → the canonical reply's
;; `:completed-at` (rf2-rl27r2 — failure / cancellation replies now carry it,
;; symmetric with success + mutation); stale-fired → a replay-stable freshness
;; re-check. Each declares the time cofx so the fact is delivered flat.
(events/reg-event :rf.resource.internal/succeeded
                     time-meta
                     (resource-events/with-classification-lowering
                       resource-events/succeeded-handler))
(events/reg-event :rf.resource.internal/failed
                     time-meta
                     (resource-events/with-classification-lowering
                       resource-events/failed-handler))
;; EP-0021 R1/R2 — the infinite-feed PAGE reply handlers. DISTINCT from the
;; scalar succeeded / failed replies: a page success APPENDS / replaces-in-place
;; one page (`entry-replace-page`); a page failure is the THIRD error channel
;; (`entry-page-failed` keeps the feed + records `:page-error`). They reuse the
;; same `live-entry-for-reply` verification + stale suppression and consume the
;; reply token's causal `:rf/time-ms` (→ `:loaded-at` / `:completed-at`), so
;; each declares the time cofx. User code MUST NOT dispatch them.
(events/reg-event :rf.resource.internal/page-succeeded
                     time-meta
                     (resource-events/with-classification-lowering
                       resource-events/page-succeeded-handler))
(events/reg-event :rf.resource.internal/page-failed
                     time-meta
                     (resource-events/with-classification-lowering
                       resource-events/page-failed-handler))
(events/reg-event :rf.resource.internal/aborted
                     time-meta
                     (resource-events/with-classification-lowering
                       resource-events/aborted-handler))
(events/reg-event :rf.resource.internal/stale-fired
                     time-meta
                     (resource-events/with-classification-lowering
                       resource-events/stale-fired-handler))
(events/reg-event :rf.resource.internal/gc-fired
                     framework-authority-meta
                     (resource-events/with-classification-lowering
                       resource-events/gc-fired-handler))
;; EP-0020 §Polling — an active-owner `:poll` timer fired. The handler
;; re-checks the live entry (present? owned? not hidden? not in-flight?) and
;; unconditionally refetches (cause `:poll`, never an owner) when polling
;; should continue, re-arming the next interval. The host `:poll` timer
;; dispatches this; user code MUST NOT dispatch it directly.
(events/reg-event :rf.resource.internal/poll-fired
                     framework-authority-meta
                     (resource-events/with-classification-lowering
                       resource-events/poll-fired-handler))
(events/reg-event :rf.resource.internal/stale-suppressed
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
;; it declares the recordable `:rf.resource/generation-allocation` cofx
;; (rf2-abyycr) and reads the recorded `:generation` flat — the instance-id /
;; work-id derive from it, so recording the generation makes them reproduce on
;; replay for free. The internal replies carry the verification payload
;; (instance id + work-id + generation). User code MUST NOT dispatch the
;; internal replies.
(events/reg-event :rf.mutation/execute
                     generation-meta
                     mutation-events/execute-handler)
(events/reg-event :rf.mutation/clear
                     framework-authority-meta
                     mutation-events/clear-handler)
;; EP-0017 (rf2-601ife): the mutation reply handlers consume the reply token's
;; causal `:rf/time-ms` for the canonical reply's `:completed-at` → the durable
;; instance `:settled-at` + any patch / populate `:loaded-at`, so they declare
;; the time cofx (success + failure both, symmetric).
(events/reg-event :rf.mutation.internal/succeeded
                     time-meta
                     mutation-events/succeeded-handler)
(events/reg-event :rf.mutation.internal/failed
                     time-meta
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
   :resources/resolve-resource-scope resolve-resource-scope
   ;; rf2-84l82t (EP-0015): the OFF-BOX trace egress projector for a
   ;; `:rf.resource/scope-resolved` row — the central trace egress pipeline
   ;; (epoch tool-pair) consults it to redact the resolver's resolved
   ;; `:input-values` / `:scope` (a value-path walk is structurally blind to
   ;; resolver-owned values once copied into trace tags). Published so the
   ;; epoch artefact reaches it without a static :require on resources.
   :resources/project-scope-resolved-egress
   scope-registry/project-scope-resolved-egress
   ;; rf2-8x0gfa (EP-0015): the family-level OFF-BOX trace egress projector for
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
   trace-egress/project-resource-trace-egress})
