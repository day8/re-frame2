(ns re-frame.resources.mutation-events
  "The mutation event handlers — the causal WRITE surface over managed
  HTTP, with success-time resource invalidation / patch / populate. Per
  Spec 016 §Deferred slices (mutations, first public-beta gate) and
  EP-0003 §Mutations.

  The public mutation events take MAP payloads (mirroring the resource
  events):

    [:rf.mutation/execute {:mutation … :params … :instance … :scope … :cause …}]
    [:rf.mutation/clear   {:instance …}]   ;; causal instance reset (NOT a form-error reset)

  Plus the framework-INTERNAL replies (`:rf.mutation.internal/*`) that
  carry the verification payload (`:instance-id` / `:work/id` /
  `:mutation-id` / `:scope` / `:generation` / `:rf.frame/id`) — user code
  MUST NOT dispatch them. The internal replies RECEIVE the canonical uniform
  reply map (Managed-Effects §The uniform reply envelope) — built by
  `re-frame.resources.reply` with `:work/kind :mutation` from the
  transport's public payload + the verification payload, so a mutation write
  settles through the SAME one-status reply shape resources + every other
  managed-async family produce (EP-0011 §Mutation Reply).

  ## What this slice does

  - **`:rf.mutation/execute`** mints a mutation INSTANCE (keyed by a
    caller-supplied or generated instance id, so concurrent submissions of
    the same mutation id never clobber each other), creates a work-ledger
    record (reusing the resource work-ledger substrate, work-kind
    `:mutation`), and lowers the mutation's `:request` through the SAME
    managed-HTTP transport the resources use (the runtime owns reply
    addressing; generation / work-id stale-suppression as for resources).
  - On **success**: applies the mutation's controlled `:patches` /
    `:populates` to the affected resource entries, then invalidates the
    `:invalidates` tags the patch did not already satisfy (the explicit
    invalidation TIMING — `:after-success` by default — decides when).
  - On **failure**: settles the instance `:error`; optionally invalidates
    tags (`:after-failure` / `:after-settle` timing, when useful).
  - **`:rf.mutation/clear`** clears a settled instance's runtime row (the
    causal reset; clears registration+runtime, NOT a form-error reset).

  Every handler carries framework-write authority
  (`state/framework-authority-meta`) so a returned `:rf.db/runtime` effect
  is in-bounds; the registrations live in the `re-frame.resources` façade.

  ## Stale suppression (the correctness boundary, as for resources)

  The internal reply payloads stamp the qualified `:rf.frame/id` +
  `:work/id` + `:instance-id` + `:generation`; the reply handlers verify
  the live instance's `:current-work` + `:generation` before writing. A
  superseded / vanished reply (a re-execute under the same instance id, or
  a `:rf.mutation/clear`) is suppressed — it MUST NEVER mutate a newer
  instance. Cancellation is opportunistic; stale suppression is mandatory.

  ## Optimistic rollback is DEFERRED

  This slice does NO optimistic update / snapshot / rollback. The instance
  `:affected-keys` / `:patch-summary` slots and the success trace reserve
  the shape (EP-0003 §Mutations: \"reserve room … affected resource keys,
  patch summaries, snapshot ids, rollback result, and reconciliation
  refetches\") so the later optimistic slice fills the symmetric rollback
  half without a shape change."
  (:require [clojure.set :as set]
            [re-frame.frame :as frame]
            [re-frame.reply :as reply]
            [re-frame.resources.mutation-registry :as mreg]
            [re-frame.resources.mutation-runtime :as mstate]
            [re-frame.resources.registry :as registry]
            [re-frame.resources.reply :as rreply]
            [re-frame.resources.scope-registry :as scope-registry]
            [re-frame.resources.state :as state]
            [re-frame.resources.transport :as transport]
            [re-frame.resources.work-ledger :as work-ledger]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- durable timestamps ---------------------------------------------------
;;
;; EP-0010: every durable mutation timestamp is a causal world input — the
;; instance / work-ledger `:started-at` is the `:rf.mutation/execute` token's
;; `:time-ms` (rf2-dsyqmz); the terminal `:settled-at` + any patch / populate
;; `:loaded-at` is the reply token's completion time (`:completed-at`, carried
;; on the reply event's `:rf.world/inputs`, rf2-40dqi6 / rf2-r65m41). No
;; ambient clock is read at a durable write site, so this ns no longer defines
;; a `now-ms` helper (the remaining timer DELAYS are advisory host transients
;; computed from the durable absolute timestamps).

(defn- stale-at-for
  "Compute `:stale-at` for a patched / populated resource entry from
  `loaded-at` + the resource's `:stale-after-ms` policy, or nil. Mirrors the
  resource read path so a patched entry ages exactly as a fetched one."
  [resource-spec loaded-at]
  (when-let [ms (:stale-after-ms resource-spec)]
    (+ loaded-at ms)))

(defn- positive-or-nil
  "Return `ms` when it is a positive number, else nil (a non-positive /
  absent policy never arms a timer). Mirrors `events/positive-or-nil` so a
  patched / populated entry arms exactly the timers a fetched one would."
  [ms]
  (when (and (number? ms) (pos? ms)) ms))

(defn- server-frame?
  "True iff `frame-id` is an SSR / server frame (its `:config :platform` is
  `:server`). Reads ONLY the FRAME's platform — NOT the host-wide
  `active-platform` default (which is `:server` on the JVM, so a JVM
  client-mode unit test must still arm timers). Mirrors
  `events/server-frame?`. Per Spec 016 §Stale and GC scheduling (no
  wall-clock background timers under SSR)."
  [frame-id]
  (= :server (:platform (frame/frame-meta frame-id))))

(defn- timer-delays
  "The advisory stale / GC timer delays for a patched / populated entry from
  the resource spec's `:stale-after-ms` / `:gc-after-ms` policy, or nil when
  the resource declares neither (so no `:rf.resource/schedule-timers` fx is
  emitted for that key). Mirrors the read path's
  `positive-or-nil`-guarded delay derivation."
  [resource-spec]
  (let [stale-delay-ms (positive-or-nil (:stale-after-ms resource-spec))
        gc-delay-ms    (positive-or-nil (:gc-after-ms resource-spec))]
    (when (or stale-delay-ms gc-delay-ms)
      {:stale-delay-ms stale-delay-ms :gc-delay-ms gc-delay-ms})))

;; ---- instance-id minting --------------------------------------------------

(defn- mint-instance-id
  "Resolve the mutation INSTANCE id: the caller-supplied `:instance` when
  present (so an app can address its own instance — e.g. a form keyed by a
  row id), else a generated id derived from the mutation id + the frame's
  monotone generation snapshot + the cause, kept PURE (no host call — the
  generation cofx already threaded the snapshot in). Two concurrent
  submissions of the same mutation id get DIFFERENT generated ids (the
  generation differs), so they never clobber each other's instance row. Per
  EP-0003 §Mutations (a generated or caller-supplied mutation instance id)."
  [mutation-id supplied-instance generation]
  (or supplied-instance
      [:rf.mutation/instance mutation-id generation]))

;; ---- exact-target scope resolution (EP-0016 Rider 2 / slice 6) -------------
;;
;; A map-form `:populates` / `:patches` target declares its OWN `:scope` —
;; concrete, `:rf.scope/same` (the mutation's resolved scope, the default), or
;; a `{:from-db …}` named-resolver reference resolved against the settle-time
;; app-db (the single use-time rule). This mirrors the invalidation-descriptor
;; scope resolution (`resolve-descriptor-scope` below); both share the same
;; `:rf.scope/same` marker, the same `state/canonicalize-scope` path, and the
;; same fail-closed nil-resolution discipline.

(defn- resolve-exact-target-scope
  "Resolve a map-form exact target's DECLARED `:scope` (via `mstate/target-scope`,
  defaulting `:rf.scope/same`) to a concrete cache scope at settle time, against
  the mutation's resolved scope `mut-scope` and the handler's app-db `db`.
  Returns `[:resolved <concrete-scope>]` or `[:nil-resolved <from-db-id>]` (a
  `{:from-db …}` reference that resolved nil — FAIL-CLOSED: the target is
  dropped, never written under an implicit global). Cross-scope is NOT a target
  concept (an exact target writes ONE key, so there is no scope-agnostic form).
  Per Spec 016 §Map-form exact resource targets / §Resolver references."
  [target mut-scope db where]
  (let [scope (mstate/target-scope target)]
    (cond
      (= scope mstate/same-scope-marker)
      [:resolved mut-scope]

      (scope-registry/from-db-reference? scope)
      (if-let [s (scope-registry/resolve-from-db-reference scope db where)]
        [:resolved (state/canonicalize-scope s where nil)]
        [:nil-resolved (:from-db scope)])

      :else
      [:resolved (state/canonicalize-scope scope where nil)])))

;; ---- the success-time patch / populate / invalidate composition -----------

(defn- apply-patches
  "Apply the mutation spec's `:patches` to the resource cache. `:patches` is
  `(fn [params result] -> {target patch-fn})` — each KEY is a map-form exact
  target `{:resource :params :scope}` (EP-0016 Rider 2 — the only public input
  form) and each `patch-fn` is `(fn [old-data result] -> new-data)` applied to
  the resolved key's last-known-good `:data` (controlled patch,
  structural-shared, freshness refreshed). Returns `[runtime-db' affected-keys
  policies nil-resolved-ids]`. A patch on a key with no entry / no data is a
  no-op (a patch transforms existing data; populate seeds); a target whose
  `{:from-db …}` scope resolves nil is FAIL-CLOSED (dropped). Per EP-0003
  §Mutations / Spec 016 §Map-form exact resource targets."
  [runtime-db patches-fn params result clock-ms mut-scope db where]
  (let [[patches nil-ids]
        (when patches-fn
          ;; rf2-3yyaur / EP-0016 Rider 2 — resolve each map-form target's
          ;; scope against the settle-time db, then validate + canonicalize
          ;; EVERY resolved key BEFORE any cache mutation (fail-closed: one bad
          ;; target rejects the whole patch arm; a nil-resolving {:from-db …}
          ;; target is dropped, never a partial / wrong-scope write).
          (mstate/validate-target-map!
            (patches-fn params result)
            #(resolve-exact-target-scope % mut-scope db where)
            registry/resource-meta :patches where))]
    (if (seq patches)
      (reduce-kv
        (fn [[db' ks policies nils] scoped-key patch-fn]
          (let [entry (get-in db' (state/entry-path scoped-key))]
            (if (and entry (state/has-data? entry))
              (let [rspec    (registry/resource-meta (:resource/id entry))
                    stale-at (stale-at-for rspec clock-ms)
                    entry'   (mstate/patch-entry entry patch-fn result
                                                 {:clock-ms clock-ms :stale-at stale-at})
                    delays   (timer-delays rspec)]
                [(assoc-in db' (state/entry-path scoped-key) entry')
                 (conj ks scoped-key)
                 (cond-> policies delays (assoc scoped-key delays))
                 nils])
              [db' ks policies nils])))
        [runtime-db #{} {} (vec nil-ids)]
        patches)
      [runtime-db #{} {} (vec nil-ids)])))

(defn- apply-populates
  "Apply the mutation spec's `:populates` to the resource cache. `:populates`
  is `(fn [params result] -> {target value})` — each KEY is a map-form exact
  target `{:resource :params :scope}` (EP-0016 Rider 2) and each resolved key
  is SEEDED `:loaded` from `value` (controlled populate — an AUTHORITATIVE
  load, Rider 1). The populated entry takes the resource's tags from the
  resource spec's `:tags` fn (a populated entry MUST carry its own tags so a
  later invalidation can reach it). Returns `[runtime-db' affected-keys
  policies nil-resolved-ids]`. A target whose `{:from-db …}` scope resolves nil
  is FAIL-CLOSED (dropped). Per EP-0003 §Mutations / Spec 016 §Map-form exact
  resource targets / §Populate is an authoritative load."
  [runtime-db populates-fn params result clock-ms mut-scope db where]
  (let [[populates nil-ids]
        (when populates-fn
          (mstate/validate-target-map!
            (populates-fn params result)
            #(resolve-exact-target-scope % mut-scope db where)
            registry/resource-meta :populates where))]
    (if (seq populates)
      (reduce-kv
        (fn [[db' ks policies nils] scoped-key value]
          (let [[_scope resource-id rparams] scoped-key
                rspec    (registry/resource-meta resource-id)
                stale-at (stale-at-for rspec clock-ms)
                tags-fn  (:tags rspec)
                tags     (when tags-fn (set (tags-fn rparams value)))
                entry    (get-in db' (state/entry-path scoped-key))
                entry'   (mstate/populate-entry entry resource-id value
                                                {:clock-ms clock-ms :stale-at stale-at :tags tags})
                delays   (timer-delays rspec)]
            [(assoc-in db' (state/entry-path scoped-key) entry')
             (conj ks scoped-key)
             (cond-> policies delays (assoc scoped-key delays))
             nils]))
        [runtime-db #{} {} (vec nil-ids)]
        populates)
      [runtime-db #{} {} (vec nil-ids)])))

;; ---- scoped invalidation descriptors (EP-0016 D2 / slice 5) ---------------
;;
;; The mutation `:invalidates` arm now lowers TWO public forms — the bare
;; tag-set shorthand AND the per-target descriptor form — into ONE canonical
;; descriptor vector (the pure `mstate/normalize-invalidation-descriptors`),
;; then resolves each descriptor's OWN scope and dispatches the SINGLE scoped
;; invalidation engine (`:rf.resource/invalidate-tags`) once per descriptor.
;; The runtime does not re-implement the invalidation — it causes it, once per
;; resolved `(tags, scope)` pair (Spec 016 §Scoped invalidation descriptors).

(defn- resolve-descriptor-scope
  "Resolve ONE invalidation descriptor's `:scope` to a concrete cache scope at
  settle time, against the mutation's resolved scope `mut-scope` and the
  handler's app-db coeffect `db` (the EP-0010-coherent causal input). Returns
  `[:resolved <concrete-scope>]`, `[:cross-scope]` (the audited escape — no
  concrete scope, the engine ignores the scope filter), or `[:nil-resolved
  <from-db-id>]` (a `{:from-db …}` reference that resolved nil — FAIL-CLOSED:
  this descriptor produces NO invalidation, never an implicit global blast).
  Per Spec 016 §Scoped invalidation descriptors / §Resolver references.

    - `:rf.scope/same` (the default) -> the mutation's resolved scope;
    - `:rf.scope/global` / a concrete value -> routed through the SHARED
      `state/canonicalize-scope` (typo / host / global-spelling guarantees);
    - `{:from-db <id>}` -> resolved against `db` at use time, nil fail-closed;
    - a `:cross-scope? true` descriptor -> `[:cross-scope]` (scope-agnostic by
      construction; its `:scope`, if any, is advisory only)."
  [{:keys [scope cross-scope?]} mut-scope db where]
  (cond
    cross-scope?                                [:cross-scope]
    (= scope mstate/same-scope-marker)          [:resolved mut-scope]
    (scope-registry/from-db-reference? scope)   (if-let [s (scope-registry/resolve-from-db-reference scope db where)]
                                                  [:resolved (state/canonicalize-scope s where nil)]
                                                  [:nil-resolved (:from-db scope)])
    :else                                       [:resolved (state/canonicalize-scope scope where nil)]))

(defn- invalidation-plan
  "Resolve the mutation's `:invalidates` result into the per-descriptor
  invalidation plan at settle time. `:invalidates` is
  `(fn [params result] -> <tag-set | descriptor | descriptor-vector>)`
  (Spec 016 §Scoped invalidation descriptors / §Cache-consequence callback
  signatures — the one canonical `(params result)` signature, NO `db`/`ctx`).

  The raw result lowers (purely) into the canonical descriptor vector; each
  descriptor's OWN scope is resolved against the mutation's resolved scope
  `mut-scope` and the handler app-db `db` at settle time (the single use-time
  rule for `{:from-db …}` references). A `:rf.scope/same` (default) descriptor
  resolves to `mut-scope`; a concrete / `:rf.scope/global` scope is canonicalized;
  a `:cross-scope? true` descriptor lowers to the audited scope-agnostic engine
  path; a `{:from-db …}` reference that resolves NIL is FAIL-CLOSED — it is
  recorded in `:unresolved` and produces NO dispatch (never an implicit global
  blast).

  Returns `nil` when the mutation has no `:invalidates` fn, else a plan map
  `{:dispatches [<resolved-descriptor> …] :unresolved [<from-db-id> …]
  :descriptor-count <n>}` — the caller turns `:dispatches` into the
  per-descriptor `:rf.resource/invalidate-tags` fx and attaches the whole plan
  (incl. the fail-closed `:unresolved` evidence) to the mutation settlement
  trace (the descriptor-level invalidation evidence Spec 016 §Trace evidence
  for invalidation prescribes, recorded on the existing `:rf.mutation/*`
  settlement op rather than a new trace op). Per EP-0003 §Mutations / EP-0016 D2."
  [invalidates-fn params result mut-scope db where]
  (when invalidates-fn
    (let [descriptors (mstate/normalize-invalidation-descriptors
                        (invalidates-fn params result) where)]
      (reduce
        (fn [plan {:keys [tags cross-scope? refetch-populated?] :as descriptor}]
          (if-not (seq tags)
            plan
            (let [[outcome scope-or-id] (resolve-descriptor-scope
                                          descriptor mut-scope db where)]
              (case outcome
                :nil-resolved
                (update plan :unresolved conj scope-or-id)
                :cross-scope
                (update plan :dispatches conj
                        {:scope nil :cross-scope? true :tags tags
                         :refetch-populated? refetch-populated?})
                :resolved
                (update plan :dispatches conj
                        {:scope scope-or-id :cross-scope? false :tags tags
                         :refetch-populated? refetch-populated?})))))
        {:dispatches [] :unresolved [] :descriptor-count (count descriptors)}
        descriptors))))

(defn- plan->fx
  "Turn an `invalidation-plan` `:dispatches` vector into the per-descriptor
  `[:dispatch [:rf.resource/invalidate-tags …]]` fx — one dispatch into the
  SINGLE scoped invalidation engine per resolved descriptor. Returns nil when
  the plan dispatches nothing (so the caller's `cond->` skips it).

  EP-0016 Rider 1 (populate is an authoritative load): `populated-ks` is the
  set of EXACT scoped keys this same mutation just POPULATED. A populated key
  is FRESH for the mutation result, so it is EXEMPT from immediate refetch by
  this same mutation's invalidation pass — UNLESS the descriptor opts in with
  `:refetch-populated? true` (the partial-reply case). Each descriptor's
  dispatch therefore carries `:exempt-keys` = the populated keys (default) or
  `#{}` (when `:refetch-populated?`), and the invalidation engine excludes the
  exempt keys from its matched set. Per Spec 016 §Populate is an authoritative
  load."
  [plan cause populated-ks]
  (not-empty
    (mapv (fn [{:keys [scope cross-scope? tags refetch-populated?]}]
            (let [exempt (if refetch-populated? #{} (set populated-ks))]
              [:dispatch [:rf.resource/invalidate-tags
                          (cond-> {:tags tags :cause cause}
                            (some? scope)  (assoc :scope scope)
                            cross-scope?   (assoc :cross-scope? true)
                            (seq exempt)   (assoc :exempt-keys exempt))]]))
          (:dispatches plan))))

(defn- plan-tags
  "The union of every dispatched descriptor's tags (the invalidated-tags trace
  reservation / `:patch-summary` facet)."
  [plan]
  (into #{} (mapcat :tags) (:dispatches plan)))

(defn- plan-trace
  "The descriptor-level invalidation evidence facet for the mutation settlement
  trace (Spec 016 §Trace evidence for invalidation): the descriptor count, the
  per-descriptor resolved `(scope, cross-scope?, tags, refetch-populated?)`, the
  fail-closed `:unresolved` `{:from-db …}` ids (descriptors that resolved nil
  and produced no invalidation), and `:populate-exempt` — the EXACT keys this
  same mutation populated that are exempted from same-mutation refetch by
  Rider 1 (empty when no `:populates`, or when every descriptor opted into
  `:refetch-populated? true`). nil-safe (an absent plan yields nil)."
  [plan populated-ks]
  (when plan
    (let [any-refetch? (some :refetch-populated? (:dispatches plan))]
      {:descriptor-count (:descriptor-count plan)
       :dispatched (mapv (fn [{:keys [scope cross-scope? tags refetch-populated?]}]
                           {:scope scope :cross-scope? cross-scope? :tags (vec tags)
                            :refetch-populated? (boolean refetch-populated?)})
                         (:dispatches plan))
       :unresolved (vec (:unresolved plan))
       ;; Rider 1: the populated keys exempted from this mutation's refetch
       ;; (empty when a descriptor opted into a same-mutation refetch).
       :populate-exempt (if any-refetch? [] (vec populated-ks))})))

;; ---- mutation completion continuation — call-site :reply-to (D1) -----------
;;
;; EP-0016 Decision 1 / Spec 016 §Mutation completion continuations. A
;; `:rf.mutation/execute` MAY carry an optional `:reply-to` event target. When
;; the runtime ACCEPTS a reply as current (the live-instance gate matched —
;; frame + work-id + generation), the runtime dispatches that target with one
;; CANONICAL reply map appended as the final argument (the `:rf/reply-target`
;; `:append` delivery — the shared `re-frame.reply/complete`, NOT a
;; family-private callback contract). A STALE / superseded reply never fires
;; the continuation (the mandatory stale-suppression boundary the reply
;; envelope already enforces, inherited for free).
;;
;; The continuation is a CAUSAL EVENT TARGET, not a callback (First Principles
;; §A verified mutation reply is a causal token): the accepted reply drives
;; durable app state only by dispatching an ordinary event through the event
;; tape / interceptor chain / replay. The reply target is DATA-ONLY (a public
;; event-vector prefix or descriptor — `re-frame.reply` rejects host handles),
;; so it rides the verification reply-payload safely through durable reply
;; addressing.

(defn- continuation-reply
  "Augment the canonical uniform reply map (`re-frame.resources.reply`,
  `:work/kind :mutation`) with the mutation-specific facts a `:reply-to`
  continuation needs (Spec 016 §Mutation completion continuations, the reply
  table). The canonical reply already carries `:status`, `:value` / `:error`,
  `:work/id`, `:work/kind`, `:work/status`, `:rf.frame/id`, `:completed-at`,
  and `:correlation`; this layers on the TOP-LEVEL mutation facts the
  continuation handler reads directly:

  - `:mutation`      — the mutation id;
  - `:params`        — the canonical params used for the accepted attempt;
  - `:instance`      — the mutation instance id;
  - `:scope`         — the resolved (execution) mutation scope;
  - `:affected-keys` — the resource keys the accepted reply populated /
                       patched / invalidated (a `:rf/scoped-resource-key`
                       set — empty when the reply touched no cache);
  - `:cause`         — `[:mutation <mutation-id> <instance-id>]`, the data
                       explaining what caused the continuation.

  Pure — no clock, no dispatch. The reply map is data-only (the egress policy
  walks it on the way to the trace bus / dispatch)."
  [reply {:keys [mutation-id params instance-id scope affected-keys]}]
  (assoc reply
         :mutation      mutation-id
         :params        params
         :instance      instance-id
         :scope         scope
         :affected-keys (set affected-keys)
         :cause         [:mutation mutation-id instance-id]))

(defn- continuation-fx
  "Build the `[:dispatch <completed-event>]` fx that delivers the continuation
  reply to the call-site `:reply-to` target, or nil when no target was
  supplied. Uses the shared `re-frame.reply/complete` to append the reply map
  as the final argument (the `:append` delivery — static call-site args are
  preserved, the reply lands after them), so the continuation rides the SAME
  reply substrate every managed-async family uses (NOT a family-private
  callback contract). A nil / blank target yields nil (no continuation).
  Emits the `:rf.mutation/replied` trace evidence as a side effect when a
  continuation is dispatched (D1; never on a stale/suppressed reply — that
  path does not call this)."
  [reply-to reply {:keys [frame-id mutation-id instance-id work-id status]}]
  (when-let [completed (reply/complete reply-to reply)]
    (trace/emit! :rf.event :rf.mutation/replied
                 {:rf.frame/id frame-id :mutation mutation-id :instance instance-id
                  :work-id work-id :status status :target reply-to
                  :cause [:mutation mutation-id instance-id]})
    [:dispatch completed]))

;; ---- :rf.mutation/execute -------------------------------------------------

(defn execute-handler
  "`:rf.mutation/execute` — run a mutation: mint an INSTANCE (keyed by a
  caller-supplied or generated instance id), create a work-ledger record,
  and lower the mutation's `:request` through managed HTTP. Per Spec 016
  §Deferred slices / EP-0003 §Mutations. Payload:
  `{:mutation :params :instance :scope :cause}`.

  Reuses the resource work-ledger substrate (work-kind `:mutation`), the
  host-side generation allocator (the same monotone, never-rewinding
  high-water mark — so a pre-restore in-flight reply can never match a
  post-restore instance), and the managed-HTTP transport lower (the runtime
  owns reply addressing — the app `:request` MUST NOT supply `:request-id` /
  `:on-success` / `:on-failure`).

  `:before-request` invalidation timing fires its `:invalidates` BEFORE the
  request is lowered (a rare timing for pessimistic stale-then-write).

  Returns the event-fx map (`:rf.db/runtime` + `:fx`)."
  [{rt :rf.db/runtime, frame-id :rf.frame/id, gen-snapshot :rf.resource/generation
    world :rf.world/inputs, app-db :db}
   [_event-id {:keys [mutation params instance scope cause reply-to] :as _payload}]]
  (let [where      'rf.mutation/execute
        runtime-db (or rt {})
        spec       (mreg/require-mutation-spec! mutation where)
        cparams    (mreg/validate+canonicalize-params mutation spec params where)
        cscope     (mreg/resolve-scope mutation spec scope)
        ;; rf2-e8wj5t — reject a non-serializable caller-supplied instance id
        ;; BEFORE any runtime-db / work-ledger write or HTTP lowering (the
        ;; instance id is durable + trace-visible + epoch-restore-safe). A nil
        ;; instance falls through to the generated id (serializable by
        ;; construction).
        _          (mstate/validate-instance-id! instance where)
        generation (state/next-generation gen-snapshot)
        instance-id (mint-instance-id mutation instance generation)
        ;; the work-id reuses the resource work-id shape, but keyed by the
        ;; instance id (a mutation's identity unit) + generation, so a
        ;; re-execute under the same instance id mints a NEW work-id (stale
        ;; suppression keys on it). The scoped "key" for a mutation is the
        ;; instance id (no cache identity — a write is not cached by params).
        work-id    (work-ledger/resource-work-id [:rf.mutation instance-id] generation)
        ;; rf2-sxyrzk — the transport correlation token is the frame-QUALIFIED
        ;; request-id, NOT the bare work-id. The managed-HTTP in-flight registry
        ;; keys by request-id PROCESS-GLOBALLY and supersedes by equal
        ;; request-id (Spec 014); a mutation instance id is frame-local, so two
        ;; frames executing the same mutation instance at the same generation
        ;; would collide on a bare work-id and abort/supersede each other's
        ;; in-flight write. Qualifying with the frame id isolates them.
        request-id (work-ledger/managed-request-id frame-id work-id)
        ;; EP-0010 §Resources, Mutations, And Work-Ledger Timestamps:
        ;; `:rf.mutation/execute` writes the durable instance + work-ledger
        ;; `:started-at` from the TRIGGERING TOKEN'S `:time-ms` (the causal
        ;; world input the router stamped once at the dispatch boundary), NOT
        ;; an ambient clock read in the reducer. Replay-stable: the same
        ;; execute token mints the same `:started-at`.
        started-at (:time-ms world)
        transport-id (or (:transport spec) transport/default-transport)
        ;; the mutation's :request returns the Spec 014 managed-HTTP args
        ;; (the causal write); the runtime owns reply addressing.
        http-args  ((:request spec) cparams nil)
        invalidate-timing (or (:invalidate-timing spec) :after-success)
        before?    (= :before-request invalidate-timing)
        ;; EP-0016 D2: a `:before-request` invalidation resolves descriptor
        ;; scopes (incl. `{:from-db …}`) against the EXECUTE-time app-db (the
        ;; causal coeffect db), then lowers each descriptor into one dispatch
        ;; of the single scoped invalidation engine.
        before-plan (when before?
                      (invalidation-plan (:invalidates spec) cparams nil cscope app-db where))
        ;; a `:before-request` invalidation runs BEFORE the write is even sent,
        ;; so no `:populates` has run — there are no populated keys to exempt
        ;; (Rider 1 is a success-path concept).
        before-fxs  (when before-plan
                      (plan->fx before-plan [:mutation mutation instance-id] #{}))
        instance'  (mstate/empty-instance
                     mutation instance-id
                     {:scope cscope :params cparams :cause cause
                      :generation generation :work-id work-id :started-at started-at})
        record     (-> (work-ledger/work-record
                         {:work-id      work-id
                          :frame-id     frame-id
                          :resource-key [:rf.mutation instance-id]
                          :generation   generation
                          :transport    transport-id
                          :cause        cause
                          :started-at   started-at})
                       ;; the work record's neutral :work/kind for a mutation
                       ;; attempt (the ledger is named neutrally; the resource
                       ;; writer uses :resource, the mutation writer :mutation).
                       (assoc :work/kind :mutation))
        lower-fx   (transport/lower-ensure
                     transport-id
                     {:http-args    http-args
                      :request-id   request-id
                      ;; the reply addresses the MUTATION internal replies,
                      ;; not the resource ones — the verification payload
                      ;; carries the instance id, not a resource scoped key.
                      :on-success-id :rf.mutation.internal/succeeded
                      :on-failure-id :rf.mutation.internal/failed
                      ;; EP-0007: the verification work identity is `:work/id`
                      ;; (the ledger / instance `:current-work` / reply-
                      ;; envelope spelling), one attempt one name.
                      ;; the verification payload also CARRIES the optional
                      ;; call-site `:reply-to` continuation target (D1): it
                      ;; rides the internal reply event to the success/failure
                      ;; handler, which delivers it (via the shared reply
                      ;; substrate) ONLY for an accepted reply. The target is
                      ;; data-only (a public event-vector prefix / descriptor),
                      ;; so it is durable-reply-addressing safe. Omitted when
                      ;; the caller supplied none.
                      :reply-payload (cond-> {:instance-id instance-id
                                              :mutation-id mutation
                                              :work/id     work-id
                                              :scope       cscope
                                              :generation  generation}
                                       (some? reply-to) (assoc :reply-to reply-to))
                      :work-id      work-id
                      :resource-key [:rf.mutation instance-id]
                      :scope        cscope
                      :frame-id     frame-id
                      :generation   generation
                      :where        where})
        rdb'       (-> runtime-db
                       (assoc-in (mstate/instance-path instance-id) instance')
                       (work-ledger/put-record work-id record))]
    (trace/emit! :rf.event :rf.mutation/started
                 (cond-> {:rf.frame/id frame-id :mutation mutation :instance instance-id
                          :work-id work-id :generation generation :scope cscope
                          :cause cause :invalidate-timing invalidate-timing}
                   ;; EP-0016 D2: a `:before-request` invalidation attaches its
                   ;; descriptor-level evidence (resolved scopes + fail-closed
                   ;; nil-resolved `{:from-db …}` ids) to the started trace.
                   before-plan (assoc :invalidation (plan-trace before-plan #{}))))
    {:rf.db/runtime rdb'
     ;; rf2-agrjvk — `:before-request` invalidation must precede the request
     ;; being lowered to transport. fx run in order, so the invalidation
     ;; dispatch is placed BEFORE `lower-fx` (not appended after it). The
     ;; generation commit + work-handle record stay first (they establish the
     ;; stale-suppression identity the lowered request rides); the invalidation
     ;; then fires; only THEN does the write lower. Without the reorder the
     ;; contract ("before-request invalidation happens before the request is
     ;; lowered") was violated — the request lowered first.
     :fx (cond-> [[:rf.resource/commit-generation {:value generation}]
                  [:rf.resource/record-work-handle
                   {:frame-id frame-id :work-id work-id
                    :transport transport-id :request-id request-id}]]
           before-fxs (into before-fxs)
           true       (conj lower-fx))}))

;; ---- :rf.mutation/clear — causal instance reset ---------------------------

(defn clear-handler
  "`:rf.mutation/clear` — clear a mutation INSTANCE's runtime row (the
  causal reset — clears the instance state, NOT a form-error reset of a
  view). Per EP-0003 §Mutations (failure-state lifetime + a causal
  clear/reset event). Payload: `{:instance …}` (clear one instance) or
  `{:mutation …}` (clear every instance of a mutation id).

  Best-effort aborts any in-flight attempt for a cleared instance
  (opportunistic; stale suppression by work-id + generation protects
  correctness — the instance a late reply would write into is gone, so the
  reply handler's existence check suppresses it). Marks the in-flight work
  row terminal `:cancelled`."
  [{rt :rf.db/runtime, frame-id :rf.frame/id} [_event-id {:keys [instance mutation]}]]
  (let [runtime-db (or rt {})
        instances  (get-in runtime-db (mstate/instances-path))
        target-ids (cond
                     (some? instance) (filter #(= % instance) (keys instances))
                     (some? mutation) (keep (fn [[iid inst]]
                                              (when (= mutation (:mutation/id inst)) iid))
                                            instances)
                     :else            nil)
        target-ids (vec target-ids)
        ;; collect in-flight work ids for the cleared instances (abort + cancel)
        in-flight  (into []
                         (keep (fn [iid]
                                 (let [inst (get instances iid)
                                       wid  (:current-work inst)]
                                   (when wid
                                     [wid (:transport (work-ledger/get-record runtime-db wid))]))))
                         target-ids)
        rdb'       (-> runtime-db
                       (update-in (mstate/instances-path)
                                  (fn [m] (reduce dissoc m target-ids)))
                       (as-> db (reduce (fn [d [wid _]]
                                          (work-ledger/update-record
                                            d wid work-ledger/mark-terminal
                                            :cancelled {:reason :mutation-clear}))
                                        db in-flight)))]
    (trace/emit! :rf.event :rf.mutation/cleared
                 {:rf.frame/id frame-id :cleared target-ids
                  :aborted (mapv first in-flight)})
    {:rf.db/runtime rdb'
     ;; rf2-sxyrzk — abort by the frame-QUALIFIED request-id (the token the
     ;; lower registered); the bare work-id would miss the in-flight request.
     :fx (into [] (keep (fn [[wid transport]] (work-ledger/abort-fx transport frame-id wid)))
               in-flight)}))

;; ---- framework-internal reply handlers ------------------------------------
;;
;; Verify frame + work-id + generation against the live instance before
;; writing (stale suppression is the correctness boundary). User code MUST
;; NOT dispatch these.

(defn- live-instance-for-reply
  "Look the live mutation instance up for an internal reply and verify it is
  still the one the reply belongs to: the reply's stamped `:rf.frame/id`
  equals the RECEIVING frame (`receiving-frame-id`), the instance exists,
  its `:current-work` equals the reply's `:work/id`, AND its `:generation`
  equals the reply's `:generation`. Returns the instance on a match, nil on
  a cross-frame / stale / superseded / cleared reply (which MUST be
  suppressed). Mirrors the resource `live-entry-for-reply`.

  FRAME VERIFICATION (rf2-jzh5gq, the mutation analogue of rf2-eu2ifi): the
  managed-HTTP transport stamps the qualified `:rf.frame/id` into every
  reply payload at lowering (`transport/http.cljc`); the reply handler runs
  in the RECEIVING frame's cofx. A reply whose payload frame does not match
  the receiving frame is REJECTED without touching this frame's instance or
  ledger — with two frames using the same mutation instance / generation, a
  misrouted reply can no longer settle the WRONG frame. The frame stamp is
  checked FIRST (before the per-frame instance lookup), so a cross-frame
  reply is rejected even when both frames happen to hold the same instance
  at the same generation. A reply with no stamped frame (a direct-dispatch
  test payload that omits `:rf.frame/id`) skips the frame check (nil never
  collides with a concrete frame id) and is verified by work-id +
  generation alone.

  The verification work identity is `:work/id` (EP-0007 — one attempt, one
  work id, one name)."
  [runtime-db receiving-frame-id {work-id :work/id :keys [instance-id generation] :as payload}]
  (let [reply-frame (:rf.frame/id payload)]
    (when (or (nil? reply-frame) (= reply-frame receiving-frame-id))
      (when-let [inst (get-in runtime-db (mstate/instance-path instance-id))]
        (when (and (= work-id (:current-work inst))
                   (= generation (:generation inst)))
          inst)))))

(defn- instance-current-generation
  "Read the LIVE counterpart generation for a stale-suppression gate: the
  `:generation` of the mutation instance currently occupying `instance-id`
  at completion. nil when no instance occupies the slot (it was cleared —
  no live counterpart, exactly the supersession the gate records). The
  `:current` half of the carried-vs-current pair the canonical stale reply
  carries; the `:carried` half is the generation stamped on the reply token
  (`(:generation payload)`)."
  [runtime-db instance-id]
  (:generation (get-in runtime-db (mstate/instance-path instance-id))))

(defn- stale-suppress-reply
  "Build the canonical `:status :stale` reply outcome for a superseded /
  cleared MUTATION reply through the SHARED `re-frame.reply` substrate (via
  `rreply/stale-reply`), so the mutation family lowers its stale outcome
  exactly as every other managed-async family does (Managed-Effects §Stale
  suppression). The carried correlation is the reply token's `:work/id` +
  `:generation`; the current correlation is the live instance's
  `:generation` (nil when the slot is gone — no live counterpart). The
  result rides `:rf.reply/status :stale` / `:rf.reply/work-status
  :suppressed` / `:rf.reply/stale-reason` / the carried-vs-current
  generation pair ADDITIVELY onto the existing `:rf.mutation/stale-
  suppressed` trace via `emit-mutation-stale-suppressed!`.

  Returns the `re-frame.reply/suppress` outcome map (`:deliver?` false — no
  durable mutation write, no `:reply-to` continuation; `:reply` is the
  data-only `:status :stale` reply; `:work/status :suppressed`). `extra`
  threads diagnostic facts (e.g. `:outcome`) onto the stale reply."
  [runtime-db {work-id :work/id :keys [instance-id generation scope mutation-id] :as payload} extra]
  (let [carried-gen (:generation payload)
        current-gen (instance-current-generation runtime-db instance-id)]
    (rreply/stale-reply
      {:carried {:work/id work-id :generation carried-gen}
       :current {:generation current-gen}
       :extra   (merge {:work/id      work-id
                        :work/kind    rreply/work-kind-mutation
                        :stale/reason :rf.mutation/superseded
                        :correlation  (cond-> {:generation {:carried carried-gen
                                                             :current current-gen}}
                                        (some? instance-id)  (assoc :instance/id instance-id)
                                        (some? mutation-id)  (assoc :mutation/id mutation-id)
                                        (some? scope)         (assoc :scope scope))}
                       extra)})))

(defn- emit-mutation-stale-suppressed!
  "Emit the `:rf.mutation/stale-suppressed` trace for a suppressed late
  mutation reply, carrying its bespoke facts (`:instance` / `:work-id` /
  `:generation` / `:outcome`) PLUS the canonical reply-envelope vocabulary
  ADDITIVELY (joined to `:work/id` via the shared `:rf.reply/*` facts):
  `:rf.reply/status :stale`, `:rf.reply/work-status :suppressed`,
  `:rf.reply/stale-reason`, `:rf.reply/work-id`, and `:rf.reply/correlation`
  (the carried-vs-current generation gate) — the SAME additive shape the
  machine `:rf.machine/done` and the resource stale path ride (Managed-
  Effects §Tracing / EP-0011). `stale` is the `stale-suppress-reply`
  outcome; its trace summary routes wire slots through the shared elider via
  `rreply/trace-reply`."
  [frame-id instance-id work-id generation outcome stale]
  (let [summary (rreply/trace-reply (:reply stale))]
    (trace/emit! :rf.event :rf.mutation/stale-suppressed
                 {:rf.frame/id frame-id :instance instance-id
                  :work-id work-id :generation generation :outcome outcome
                  ;; reply-envelope vocabulary (Managed-Effects §9) — the
                  ;; canonical :status :stale reply produced via the shared
                  ;; substrate, recorded ADDITIVELY (the bespoke facts above
                  ;; are preserved).
                  :rf.reply/status      (:status summary)
                  :rf.reply/work-status (:work/status summary)
                  :rf.reply/work-id     (:work/id summary)
                  :rf.reply/stale-reason (:stale/reason summary)
                  :rf.reply/correlation (:correlation summary)})))

;; The managed-HTTP transport APPENDS its PUBLIC reply payload as the LAST
;; arg of the internal reply event (Spec 014 §Reply addressing), exactly as
;; for resources — so a live reply lands as a 3-element event:
;;   [:rf.mutation.internal/succeeded <verification-payload> {:kind :success :value <data>}]
;;   [:rf.mutation.internal/failed    <verification-payload> {:kind :failure :failure <envelope>}]
;; The reply handlers RE-LIFT (arg 2 + arg 3) into the ONE canonical reply
;; map (`re-frame.resources.reply`, `:work/kind :mutation`) — `{:status
;; :value/:error :work/id :work/kind :mutation :work/status :rf.frame/id
;; :completed-at :correlation}` (Managed-Effects §The uniform reply envelope
;; / EP-0011 §Mutation Reply). The mutation instance then stores the canonical
;; reply's `:value` under its durable `:result` (the instance layer's spelling
;; of the same decoded result — the reply-map spelling is `:value`, kh9jz6 /
;; EP-0007). A direct-dispatch test may inline :result / :error on arg 2.

(defn- reply-success-result
  "Extract the decoded mutation result from a managed-HTTP success reply
  (`{:kind :success :value <data>}` appended as arg 3). Falls back to an
  inline `:result` on the verification payload (the direct-dispatch test
  shape)."
  [verification-payload http-result]
  (if (contains? http-result :value)
    (:value http-result)
    (:result verification-payload)))

(defn- reply-failure-error
  "Extract the failure envelope from a managed-HTTP failure reply
  (`{:kind :failure :failure <envelope>}` appended as arg 3). Falls back to
  an inline `:error` on the verification payload."
  [verification-payload http-result]
  (if (contains? http-result :failure)
    (:failure http-result)
    (:error verification-payload)))

(defn succeeded-handler
  "`:rf.mutation.internal/succeeded` — a mutation write succeeded. Verifies
  frame + work-id + generation against the live instance; on match:

    1. applies the mutation's controlled `:patches` (transform existing
       resource entries) and `:populates` (seed resource entries) from the
       result — BEFORE invalidation (a patch makes an entry fresh; a later
       same-tag invalidation would just re-stale it);
    2. invalidates the `:invalidates` tags (scoped) the patch did not
       already satisfy — the explicit `:after-success` (default) /
       `:after-settle` timing fires here;
    3. settles the instance `:success` with the result + the affected-key /
       patch-summary trace reservation.

  A stale / superseded / cleared reply is SUPPRESSED. Per EP-0003
  §Mutations; EP-0011 §Mutation Reply.

  The reply is re-lifted into the canonical reply map (`rreply/success-reply`
  with `:work/kind :mutation`); the decoded result rides as `:value` (the
  reply-map spelling) and is stored under the instance's durable `:result`
  (the instance-layer spelling — kh9jz6 / EP-0007).

  Event shape: `[_ <verification-payload> <http-result>]`."
  [{rt :rf.db/runtime, frame-id :rf.frame/id, world :rf.world/inputs, app-db :db}
   [_event-id {work-id :work/id :keys [instance-id mutation-id generation scope reply-to] :as payload} http-result]]
  (let [where      'rf.mutation.internal/succeeded
        runtime-db (or rt {})
        ;; EP-0010 §Managed Effects And Reply Tokens / §Resources, Mutations,
        ;; And Work-Ledger Timestamps: the reply is a CAUSAL TOKEN; the host
        ;; completion time (`:completed-at`, read ONCE at the transport
        ;; finalisation boundary) rides the reply event's `:rf.world/inputs`
        ;; `:time-ms`. The handler MUST NOT re-read the clock. It carries onto
        ;; the canonical reply as `:completed-at` and is the source of the
        ;; terminal `:settled-at` + any patch/populate `:loaded-at`.
        completed-at (:time-ms world)
        ;; the ONE canonical reply map (Managed-Effects §The uniform reply
        ;; envelope), `:work/kind :mutation`. The internal mutation reply
        ;; target receives it directly; the decoded result is `:value`.
        reply      (rreply/success-reply payload (reply-success-result payload http-result)
                                         {:work-kind rreply/work-kind-mutation
                                          :completed-at completed-at})
        result     (:value reply)
        inst       (live-instance-for-reply runtime-db frame-id payload)]
    (if (nil? inst)
      ;; STALE SUPPRESSION (mandatory): a superseded / cleared / cross-frame
      ;; reply never mutates a newer (or another frame's) instance — NO
      ;; durable write. Per Managed-Effects §Stale suppression the completion
      ;; is recorded `:status :stale` / `:work/status :suppressed` through the
      ;; SHARED `re-frame.reply` substrate (via `stale-suppress-reply`), and
      ;; the canonical reply-envelope vocabulary rides ADDITIVELY on the
      ;; `:rf.mutation/stale-suppressed` trace. Settle the (already-superseded)
      ;; work row terminal + clear the host handle.
      (let [stale (stale-suppress-reply runtime-db payload {:outcome :success})]
        (emit-mutation-stale-suppressed!
          frame-id instance-id work-id generation :success stale)
        (work-ledger/clear-handle! frame-id work-id)
        {:rf.db/runtime (work-ledger/update-record
                          runtime-db work-id work-ledger/mark-terminal
                          :suppressed {:reason :stale-reply :outcome :success})})
      (let [spec        (mreg/mutation-meta mutation-id)
            params      (:params inst)
            timing      (or (:invalidate-timing spec) :after-success)
            ;; EP-0010: the terminal mutation reply writes `:settled-at` from
            ;; the reply completion time, and ANY resource patch/populate
            ;; `:loaded-at` produced by the mutation uses that SAME causal
            ;; completion time (off the reply token, never an ambient read).
            clock-ms    completed-at
            ;; 1. controlled patch / populate (BEFORE invalidation). Each arm
            ;; takes a map-form exact target (EP-0016 Rider 2 — the only public
            ;; input form), resolves each target's declared `:scope`
            ;; (`:rf.scope/same` / concrete / `{:from-db …}`) against this
            ;; reply's app-db at settle time, validates + canonicalizes the
            ;; resolved key fail-closed, and returns the per-key advisory stale
            ;; / GC timer policy (so this handler arms timers exactly as the
            ;; read path does — a populate seeds a fresh, ownerless entry that
            ;; otherwise has a durable :stale-at / :gc-after-ms policy but NO
            ;; armed reaper) PLUS the fail-closed nil-resolved {:from-db …} ids
            ;; (targets dropped because their scope resolver returned nil).
            ;; populate wins on a key written by both (it ran last).
            [rdb1 patched-ks patch-policies patch-nil-ids]        (apply-patches runtime-db (:patches spec) params result clock-ms scope app-db where)
            [rdb2 populated-ks populate-policies populate-nil-ids] (apply-populates rdb1 (:populates spec) params result clock-ms scope app-db where)
            patch-affected      (set/union patched-ks populated-ks)
            timer-policies      (merge patch-policies populate-policies)
            target-nil-ids      (into (vec patch-nil-ids) populate-nil-ids)
            ;; the success-time invalidation (EP-0016 D2: per-descriptor
            ;; scoped). The patch already freshened the keys it touched; the
            ;; invalidation reaches the OTHER tagged entries (lists, siblings)
            ;; the patch did not seed. Each descriptor resolves its OWN scope
            ;; (incl. `{:from-db …}` against this reply's app-db at settle time)
            ;; and lowers into one dispatch of the single scoped invalidation
            ;; engine; a nil-resolving `{:from-db …}` is fail-closed (recorded
            ;; in the plan `:unresolved`, no dispatch). EP-0016 Rider 1: the
            ;; keys this same mutation just POPULATED are EXEMPT from this
            ;; invalidation pass's refetch (a populate is an authoritative load
            ;; — the key is already fresh for the result) unless a descriptor
            ;; opts in with `:refetch-populated? true`.
            inv-plan    (when (#{:after-success :after-settle} timing)
                          (invalidation-plan (:invalidates spec) params result scope app-db where))
            inv-fxs     (when inv-plan (plan->fx inv-plan [:mutation mutation-id instance-id] populated-ks))
            ;; the union of every dispatched descriptor's tags (the affected-key
            ;; / patch-summary trace reservation records the invalidated tags).
            inv-tags    (when inv-plan (plan-tags inv-plan))
            ;; the affected-key / patch-summary trace reservation (optimistic
            ;; rollback shape — DEFERRED; populated descriptively here)
            affected    (vec patch-affected)
            patch-summary {:patched   (vec patched-ks)
                           :populated (vec populated-ks)
                           :invalidated-tags (vec (or inv-tags #{}))
                           ;; EP-0016 Rider 2 fail-closed evidence: map-form
                           ;; populate/patch targets whose {:from-db …} scope
                           ;; resolved nil were DROPPED (never written under an
                           ;; implicit global).
                           :target-unresolved (vec target-nil-ids)
                           ;; reserved for the later optimistic slice:
                           :snapshot-id nil :rollback nil :reconciliation-refetches nil}
            inst'       (mstate/instance-succeeded
                          inst {:result result :settled-at clock-ms
                                :affected-keys affected :patch-summary patch-summary})
            rdb'        (-> rdb2
                            (assoc-in (mstate/instance-path instance-id) inst')
                            (work-ledger/update-record
                              work-id work-ledger/mark-terminal
                              :completed {:settled-at clock-ms})
                            ;; recompute indexes — patch/populate may have
                            ;; changed entries' tags / created entries.
                            (update state/resources-key state/recompute-indexes))
            ;; arm the advisory stale / GC timers (host-side side table) for
            ;; every patched / populated key carrying a policy — one
            ;; `:rf.resource/schedule-timers` fx per key, mirroring the
            ;; resource read path's emission (cancel-then-arm; SSR-gated by
            ;; the carried `:server?` flag; the re-check handlers re-derive
            ;; freshness / GC-eligibility from the durable facts, so a
            ;; never-fired server timer is harmless). Without this, a
            ;; populated ownerless entry would carry a durable :stale-at /
            ;; :gc-after-ms policy but NO armed reaper. Per Spec 016 §Stale
            ;; and GC scheduling.
            server?     (server-frame? frame-id)
            timer-fx    (mapv (fn [[scoped-key {:keys [stale-delay-ms gc-delay-ms]}]]
                                [:rf.resource/schedule-timers
                                 {:frame-id       frame-id
                                  :resource-key   scoped-key
                                  :stale-delay-ms stale-delay-ms
                                  :gc-delay-ms    gc-delay-ms
                                  :server?        server?}])
                              timer-policies)
            ;; PHASE 6 (Spec 016 §Phase order) — the mutation completion
            ;; continuation. The instance + work-ledger row are now settled
            ;; (`inst'` / `rdb'`) and the cache consequences are computed, so a
            ;; handler reached by `:reply-to` observes both already-settled for
            ;; this ACCEPTED reply. The continuation reply is the canonical
            ;; uniform reply map plus the mutation-specific facts; it is
            ;; dispatched LAST (after the cache-consequence fx) so the
            ;; continuation runs after the invalidation it composes with.
            cont-fx     (continuation-fx
                          reply-to
                          (continuation-reply
                            reply {:mutation-id mutation-id :params params
                                   :instance-id instance-id :scope scope
                                   :affected-keys patch-affected})
                          {:frame-id frame-id :mutation-id mutation-id
                           :instance-id instance-id :work-id work-id
                           :status (:status reply)})]
        (work-ledger/clear-handle! frame-id work-id)
        (trace/emit! :rf.event :rf.mutation/succeeded
                     (cond-> {:rf.frame/id frame-id :instance instance-id :mutation mutation-id
                              :work-id work-id :generation generation
                              :affected-keys affected :patch-summary patch-summary}
                       ;; EP-0016 D2: the descriptor-level invalidation evidence
                       ;; (resolved scope per descriptor + fail-closed
                       ;; nil-resolved `{:from-db …}` ids) rides the settlement
                       ;; trace (Spec 016 §Trace evidence for invalidation). The
                       ;; Rider 1 populate-exempt evidence (the populated keys
                       ;; exempted from this mutation's refetch) rides the same
                       ;; facet (Spec 016 §Populate is an authoritative load).
                       inv-plan (assoc :invalidation (plan-trace inv-plan populated-ks))))
        {:rf.db/runtime rdb'
         :fx (cond-> (vec timer-fx)
               inv-fxs (into inv-fxs)
               cont-fx (conj cont-fx))}))))

(defn failed-handler
  "`:rf.mutation.internal/failed` — a mutation write failed. Verifies frame
  + work-id + generation; on match settles the instance `:error` with the
  failure envelope and (optionally) invalidates tags
  (`:after-failure` / `:after-settle` timing, when useful). A mutation
  failure has no `:refresh-error` analogue — a write has no last-known-good
  to keep, so `:error` is terminal until a causal `:rf.mutation/clear`. A
  stale / superseded / cleared reply is suppressed. Per EP-0003 §Mutations;
  EP-0011 §Mutation Reply.

  The failure is re-lifted into the canonical reply map
  (`rreply/failure-reply` with `:work/kind :mutation` — `:status :error`
  carrying the `:rf.http/*` envelope under `:error`, or `:status :cancelled`
  for an abort). The `:error` envelope (the same closed shape the instance
  `:error` stores) is read back from the canonical reply.

  Event shape: `[_ <verification-payload> <http-result>]`."
  [{rt :rf.db/runtime, frame-id :rf.frame/id, world :rf.world/inputs, app-db :db}
   [_event-id {work-id :work/id :keys [instance-id mutation-id generation scope reply-to] :as payload} http-result]]
  (let [where      'rf.mutation.internal/failed
        runtime-db (or rt {})
        ;; EP-0010 §Managed Effects And Reply Tokens / §Resources, Mutations,
        ;; And Work-Ledger Timestamps: a terminal mutation reply writes
        ;; `:settled-at` from the reply completion time. The host
        ;; `:completed-at` (read ONCE at the transport finalisation boundary)
        ;; rides the reply event's `:rf.world/inputs` `:time-ms`; the handler
        ;; MUST NOT re-read the clock. Carried onto the canonical reply as
        ;; `:completed-at`.
        completed-at (:time-ms world)
        ;; the ONE canonical reply map (Managed-Effects §The uniform reply
        ;; envelope), `:work/kind :mutation`. `:error` carries the closed
        ;; `:rf.http/*` envelope the instance `:error` also stores.
        reply      (rreply/failure-reply payload (reply-failure-error payload http-result)
                                         {:work-kind rreply/work-kind-mutation
                                          :completed-at completed-at})
        error      (:error reply)
        inst       (live-instance-for-reply runtime-db frame-id payload)]
    (if (nil? inst)
      ;; STALE SUPPRESSION (mandatory): a superseded / cleared / cross-frame
      ;; failure reply never mutates a newer (or another frame's) instance —
      ;; NO durable write, NO `:reply-to` continuation. The completion is
      ;; recorded `:status :stale` / `:work/status :suppressed` through the
      ;; SHARED `re-frame.reply` substrate (via `stale-suppress-reply`), with
      ;; the canonical reply-envelope vocabulary riding ADDITIVELY on the
      ;; `:rf.mutation/stale-suppressed` trace.
      (let [stale (stale-suppress-reply runtime-db payload {:outcome :failure})]
        (emit-mutation-stale-suppressed!
          frame-id instance-id work-id generation :failure stale)
        (work-ledger/clear-handle! frame-id work-id)
        {:rf.db/runtime (work-ledger/update-record
                          runtime-db work-id work-ledger/mark-terminal
                          :suppressed {:reason :stale-reply :outcome :failure})})
      (let [spec     (mreg/mutation-meta mutation-id)
            params   (:params inst)
            timing   (or (:invalidate-timing spec) :after-success)
            ;; EP-0010: the terminal failure reply writes `:settled-at` from
            ;; the reply completion time (off the reply token, never a fresh
            ;; ambient read).
            clock-ms completed-at
            ;; failure-time invalidation (only when the timing opts in):
            ;; some mutations invalidate on failure to force a re-read of
            ;; the authoritative server state after a rejected write. EP-0016
            ;; D2: per-descriptor scoped (the failure result is nil, so
            ;; descriptor fns close over only `params`).
            inv-plan (when (#{:after-failure :after-settle} timing)
                       (invalidation-plan (:invalidates spec) params nil scope app-db where))
            ;; a FAILED write applies no `:populates` (the result is nil), so
            ;; there are no populated keys to exempt (Rider 1 is a success-path
            ;; concept) — the empty exempt set keeps the failure-time
            ;; invalidation a plain pass.
            inv-fxs  (when inv-plan (plan->fx inv-plan [:mutation mutation-id instance-id] #{}))
            inv-tags (when inv-plan (plan-tags inv-plan))
            inst'    (mstate/instance-failed
                       inst {:error error :settled-at clock-ms
                             :affected-keys (when inv-tags [])})
            rdb'     (-> runtime-db
                         (assoc-in (mstate/instance-path instance-id) inst')
                         (work-ledger/update-record
                           work-id work-ledger/mark-terminal
                           :failed {:error error}))
            ;; PHASE 6 — the continuation fires for ANY accepted terminal reply
            ;; (D1 delivery rule: keyed on acceptance, not a status
            ;; enumeration), so an accepted `:error` (and an accepted terminal
            ;; `:cancelled`) reply dispatches `:reply-to` too — the handler folds
            ;; validation errors / form state / notifications off the reply
            ;; `:status`. A failed write touches no EXACT cache key, so
            ;; `:affected-keys` is empty (the invalidate-on-failure timing marks
            ;; TAGS stale, not exact keys). Dispatched LAST, after the optional
            ;; failure-time invalidation it composes with.
            cont-fx  (continuation-fx
                       reply-to
                       (continuation-reply
                         reply {:mutation-id mutation-id :params params
                                :instance-id instance-id :scope scope
                                :affected-keys #{}})
                       {:frame-id frame-id :mutation-id mutation-id
                        :instance-id instance-id :work-id work-id
                        :status (:status reply)})]
        (work-ledger/clear-handle! frame-id work-id)
        (trace/emit! :rf.event :rf.mutation/failed
                     (cond-> {:rf.frame/id frame-id :instance instance-id :mutation mutation-id
                              :work-id work-id :generation generation :error error
                              :invalidated-tags (vec (or inv-tags #{}))}
                       ;; EP-0016 D2: descriptor-level failure-invalidation
                       ;; evidence rides the failed-settlement trace (no
                       ;; populated keys on a failure, so the Rider 1 exempt
                       ;; set is empty).
                       inv-plan (assoc :invalidation (plan-trace inv-plan #{}))))
        {:rf.db/runtime rdb'
         :fx (cond-> []
               inv-fxs (into inv-fxs)
               cont-fx (conj cont-fx))}))))
