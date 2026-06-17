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
            [re-frame.interop :as interop]
            [re-frame.reply :as reply]
            [re-frame.resources.events :as events]
            [re-frame.resources.mutation-registry :as mreg]
            [re-frame.resources.mutation-runtime :as mstate]
            [re-frame.resources.registry :as registry]
            [re-frame.resources.reply :as rreply]
            [re-frame.resources.scope-registry :as scope-registry]
            [re-frame.resources.state :as state]
            [re-frame.resources.transport :as transport]
            [re-frame.resources.transport.http :as transport-http]
            [re-frame.resources.work-ledger :as work-ledger]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- durable timestamps ---------------------------------------------------
;;
;; EP-0010 / EP-0017: every durable mutation timestamp is a recordable coeffect,
;; DECLARED via `:rf.cofx/requires [:rf/time-ms]` and consumed FLAT from the
;; coeffects map (rf2-601ife — not reached through the whole `:rf.cofx` token).
;; The instance / work-ledger `:started-at` is the `:rf.mutation/execute`
;; token's `:rf/time-ms` (rf2-dsyqmz); the terminal `:settled-at` + any patch /
;; populate `:loaded-at` is the reply token's completion time (`:completed-at`,
;; the reply event's causal `:rf/time-ms`, rf2-40dqi6 / rf2-r65m41). No ambient
;; clock is read at a durable write site, so this ns no longer defines a
;; `now-ms` helper (the remaining timer DELAYS are advisory host transients
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
  monotone generation, kept PURE (no host call — the recorded
  `:rf.resource/generation-allocation` cofx already supplied the value, so
  the derived instance-id reproduces on replay; rf2-abyycr). Two concurrent
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
                                                {:clock-ms clock-ms :stale-at stale-at :tags tags
                                                 :scoped-key scoped-key})
                delays   (timer-delays rspec)]
            [(assoc-in db' (state/entry-path scoped-key) entry')
             (conj ks scoped-key)
             (cond-> policies delays (assoc scoped-key delays))
             nils]))
        [runtime-db #{} {} (vec nil-ids)]
        populates)
      [runtime-db #{} {} (vec nil-ids)])))

(defn- apply-removes
  "Apply the mutation spec's `:removes` to the resource cache (EP-0016 Rider 2 /
  Spec 016 §Map-form exact resource targets — accepted replies apply patches,
  populates, invalidates, AND removes). `:removes` is
  `(fn [params result] -> [target …])` (a collection of map-form exact targets,
  or a single one) — each target is the public map-form `{:resource :params
  :scope}`, resolved + canonicalized exactly as `:patches` / `:populates` are
  (`validate-target-map!`, which threads VALUES; a remove carries no value, so
  each target maps to a placeholder). Each resolved key is DISSOC'd from the
  cache by its byte `key-id` (rf2-9e0tyq — a dissoc by the scoped-key vector
  would no-op and leak the entry), mirroring `:rf.resource/remove`. A target
  whose `{:from-db …}` scope resolves nil is FAIL-CLOSED (dropped). Returns
  `[runtime-db' removed-keys removed-work nil-resolved-ids]` where
  `removed-work` is `[[work-id transport] …]` for each removed entry's in-flight
  attempt (best-effort abort + terminal `:cancelled` work row, as `remove` does)
  and `removed-keys` are the scoped-key VECTORS removed (for `:affected-keys` +
  the trace). A remove of a key with no entry is a no-op. Per EP-0003
  §Mutations / Spec 016 §Map-form exact resource targets."
  [runtime-db removes-fn params result mut-scope db where]
  (let [targets (when removes-fn
                  (let [raw (removes-fn params result)]
                    ;; accept a single map-form target or a collection thereof;
                    ;; lower into the {target placeholder} shape validate-target-map!
                    ;; consumes (it threads VALUES — a remove carries none).
                    (cond
                      (nil? raw)                 nil
                      (mstate/target-map? raw)   {raw ::remove}
                      :else                      (into {} (map (fn [t] [t ::remove])) raw))))
        [resolved nil-ids]
        (when (seq targets)
          (mstate/validate-target-map!
            targets
            #(resolve-exact-target-scope % mut-scope db where)
            registry/resource-meta :removes where))]
    (if (seq resolved)
      (reduce-kv
        (fn [[db' ks work nils] scoped-key _placeholder]
          (let [k-id  (state/key-id scoped-key)
                entry (get-in db' (state/entry-path scoped-key))]
            (if entry
              (let [wid       (:current-work entry)
                    transport (when wid (:transport (work-ledger/get-record db' wid)))]
                [(-> db'
                     (update-in (state/entries-path) dissoc k-id)
                     (cond-> wid (work-ledger/update-record
                                   wid work-ledger/mark-terminal
                                   :cancelled {:reason :mutation-remove})))
                 (conj ks scoped-key)
                 (cond-> work wid (conj [wid transport]))
                 nils])
              [db' ks work nils])))
        [runtime-db #{} [] (vec nil-ids)]
        resolved)
      [runtime-db #{} [] (vec nil-ids)])))

;; ---- optimistic apply (phase 1.5) — EP-0019 Decisions 1, 2, 4 -------------
;;
;; An `:optimistic` / `:optimistic-tags` plan applies a FORWARD patch to the
;; resource cache BEFORE the request settles (phase 1.5 of the mutation phase
;; order). For each touched entry the runtime SNAPSHOTS the truthful inverse
;; (`mstate/snapshot-entry` — the whole entry by reference, or the `:absent`
;; sentinel) + its `:revision` at apply time, applies the forward patch
;; (`mstate/apply-optimistic-patch`, which bumps the entry's `:revision`), and
;; records the inverse on the instance row's `:patch-summary` `:rollback` slot.
;; The SETTLE slice (next) consumes that recorded inverse + slice-1's
;; `state/revision-conflict?` to commit / rollback / reconcile.
;;
;; `:optimistic` is the EXACT-target twin of `:patches` (the same map-form
;; targets, the same `resolve-exact-target-scope` + `validate-target-map!`
;; fail-closed boundary). `:optimistic-tags` is the TAG-ADDRESSED twin of the
;; per-target `:invalidates` descriptors (the same tag index +
;; `resolve-descriptor-scope`); each tag-matched entry gets the same
;; snapshot-inverse treatment. Both are FAIL-CLOSED on a nil-resolving
;; `{:from-db …}` scope (Rider 2 — an optimistic apply WRITES the cache, so it
;; has a leak boundary a read does).

;; `resolve-descriptor-scope` is defined below (the scoped-invalidation section);
;; `optimistic-tag-targets` reuses it (one scope-resolution currency across the
;; optimistic + invalidation tag-addressed paths).
(declare resolve-descriptor-scope)

(defn- mint-snapshot-id
  "PURE: derive the opaque optimistic `:snapshot-id` identifying ONE phase-1.5
  apply (EP-0019 Decision 2). Derived from the instance id + generation (both
  recorded causal facts), so it reproduces on replay for free — no host call, no
  ambient counter. A re-execute under the same instance mints a NEW generation,
  hence a NEW snapshot id, so the SETTLE slice's stale-suppression discards the
  superseded apply's inverse correctly."
  [instance-id generation]
  [:rf.mutation/snapshot instance-id generation])

(defn- optimistic-exact-targets
  "Resolve the `:optimistic` plan `(fn [params] -> {target patch-fn})` into the
  canonical-keyed map `{scoped-key patch-fn-or-nil}` at execute time (EP-0019
  Decision 1). Each KEY is a map-form exact target resolved + canonicalized
  through the SAME fail-closed boundary `:patches` uses
  (`resolve-exact-target-scope` + `validate-target-map!`), so a nil-resolving
  `{:from-db …}` scope DROPS that target (Rider 2). A `nil` patch-fn value is an
  optimistic REMOVE (Open Issue 6). Returns `[canonical-map nil-resolved-ids]`,
  or `[nil []]` when the mutation declares no `:optimistic`. `params` is the
  canonical execute-time params (NO result — the reply does not exist yet)."
  [optimistic-fn params mut-scope db where]
  (if-not optimistic-fn
    [nil []]
    (mstate/validate-target-map!
      (optimistic-fn params)
      #(resolve-exact-target-scope % mut-scope db where)
      registry/resource-meta :optimistic where)))

(defn- warn-optimistic-tag-descriptor-skipped!
  "DEV-ONLY: emit `:rf.warning/optimistic-tags-descriptor-skipped` for a
  malformed `:optimistic-tags` descriptor that was warn-and-skipped (rf2-o5ca8k).
  Behind `interop/debug-enabled?` so Closure DCE elides it in production, riding
  the same `trace/emit!` gate as the other write-side dev tripwires. Returns nil."
  [{:keys [frame-id mutation]} reason detail]
  (when interop/debug-enabled?
    (trace/emit! :warning :rf.warning/optimistic-tags-descriptor-skipped
                 (merge {:rf.frame/id frame-id
                         :mutation    mutation
                         :recovery    :fix-descriptor
                         :reason      reason}
                        detail))))

(defn- normalize-optimistic-tag-descriptors
  "Normalize the `:optimistic-tags` plan `(fn [params] -> descriptors)` into the
  canonical descriptor vector `[{:scope … :tags #{…} :patch fn}]` (EP-0019
  Decision 4). Accepts a single descriptor map or a vector of them; each MUST
  carry a `:patch` fn `(fn [old-data] -> new-data)` (the forward optimistic op
  for every tag-matched entry) and a `:tags` collection. The `:scope` defaults
  to `:rf.scope/same` (resolved at execute time).

  WARN-AND-SKIP (rf2-o5ca8k): a malformed descriptor — a non-map entry, a
  non-collection `:tags`, or a missing `:patch` — is DROPPED with a dev-visible
  `:rf.warning/optimistic-tags-descriptor-skipped` warning, NOT thrown. This runs
  inline in `execute-handler` BEFORE the request lowers; a throw here aborted the
  whole event and the authoritative write NEVER fired — strictly worse than
  `:invalidates` (which runs post-write at settle). The optimistic paint is
  reversible best-effort, so skipping a bad descriptor corrupts nothing (the
  authoritative reply still settles the cache via `:populates` / `:invalidates`);
  the well-formed descriptors in the SAME plan still apply. The fail-closed SCOPE
  boundary is unaffected — it lives downstream in `optimistic-tag-targets` (a
  nil-resolving `{:from-db …}` still drops the target + records unresolved
  evidence). A nil / empty raw plan yields an empty vector.

  `ctx` is `{:frame-id … :mutation …}` for the warning."
  [raw ctx]
  (let [one (fn [descriptor]
              (cond
                (not (map? descriptor))
                (do (warn-optimistic-tag-descriptor-skipped!
                      ctx
                      (str "an :optimistic-tags descriptor must be a map "
                           "{:scope … :tags #{…} :patch (fn [old] new)}; got "
                           (pr-str descriptor) " — skipping it. Per Spec 016 "
                           "§Optimistic mutations / EP-0019 Decision 4.")
                      {:descriptor (pr-str descriptor)})
                    nil)

                (let [tags (:tags descriptor)] (not (and (some? tags) (coll? tags))))
                (do (warn-optimistic-tag-descriptor-skipped!
                      ctx
                      (str "an :optimistic-tags descriptor must carry a non-nil "
                           ":tags collection; got " (pr-str (:tags descriptor))
                           " in " (pr-str descriptor) " — skipping it. Per Spec "
                           "016 §Optimistic mutations.")
                      {:descriptor (pr-str descriptor) :tags (pr-str (:tags descriptor))})
                    nil)

                (not (fn? (:patch descriptor)))
                (do (warn-optimistic-tag-descriptor-skipped!
                      ctx
                      (str "an :optimistic-tags descriptor must carry a :patch fn "
                           "(fn [old-data] -> new-data); got "
                           (pr-str (:patch descriptor)) " in " (pr-str descriptor)
                           " — skipping it. Per Spec 016 §Optimistic mutations.")
                      {:descriptor (pr-str descriptor) :patch (pr-str (:patch descriptor))})
                    nil)

                :else
                (let [{:keys [scope tags patch]} descriptor]
                  {:scope (if (contains? descriptor :scope) scope mstate/same-scope-marker)
                   :tags  (state/normalize-tag-set tags)
                   :patch patch})))]
    (cond
      (or (nil? raw) (and (coll? raw) (empty? raw))) []
      (map? raw)                                     (into [] (keep one) [raw])
      (coll? raw)                                    (into [] (keep one) raw)
      :else
      (do (warn-optimistic-tag-descriptor-skipped!
            ctx
            (str "an :optimistic-tags result must be a descriptor map or a "
                 "vector of descriptor maps {:scope … :tags #{…} :patch fn}; "
                 "got " (pr-str raw) " — skipping the whole plan. Per Spec 016 "
                 "§Optimistic mutations.")
            {:raw (pr-str raw)})
          []))))

(defn- optimistic-tag-targets
  "Resolve the `:optimistic-tags` plan into the per-tag-matched-key target map
  `{scoped-key patch-fn}` at execute time (EP-0019 Decision 4). For each
  descriptor: resolve its OWN scope (`resolve-descriptor-scope` — `:rf.scope/same`
  / concrete / `{:from-db …}`, FAIL-CLOSED on nil), then match every cache entry
  carrying the descriptor's tags IN that scope (the SHARED
  `events/match-invalidation-keys` — the same matcher the invalidation engine
  uses, EP-0014 one tag index). Each matched entry's scoped key maps to the
  descriptor's `:patch` fn. A later descriptor matching the same key wins
  (last-write). Cross-scope optimistic patching is NOT offered (the optimistic
  surface is exact-key or tag-within-named-scope only — §Security). Returns
  `[{scoped-key patch-fn} nil-resolved-ids]`. `entries` is the execute-time
  cache `:entries` map."
  [descriptors entries mut-scope db where]
  (reduce
    (fn [[m nils] {:keys [scope tags patch]}]
      (let [[outcome scope-or-id] (resolve-descriptor-scope
                                    {:scope scope} mut-scope db where)]
        (case outcome
          :nil-resolved [m (conj nils scope-or-id)]
          ;; cross-scope is unreachable (a descriptor has no :cross-scope? here)
          :cross-scope  [m nils]
          :resolved
          (let [{:keys [matched]} (events/match-invalidation-keys
                                    entries scope-or-id false (set tags) #{})]
            [(reduce (fn [m' sk] (assoc m' sk patch)) m matched) nils]))))
    [{} []]
    descriptors))

(defn- forward-summary
  "PURE: the small descriptive `:forward` summary recorded on the inverse for the
  trace (EP-0019 Decision 2): `:remove` for a nil patch-fn (optimistic remove),
  `:seed` for a patch over an absent entry (optimistic seed), else `:patch`."
  [patch-fn before-entry]
  (cond
    (nil? patch-fn)                               :remove
    (= before-entry mstate/absent-snapshot)       :seed
    :else                                         :patch))

(defn- apply-optimistic
  "Apply the resolved optimistic target map `{scoped-key patch-fn-or-nil}` to the
  cache `runtime-db` at execute time (phase 1.5, EP-0019 Decisions 1/2). For each
  key: SNAPSHOT the entry before (`mstate/snapshot-entry` — the truthful inverse),
  record `{:resource/key :revision :before :forward}` (`mstate/record-optimistic-
  entry`), then either DISSOC the entry (a nil patch-fn = optimistic remove) or
  apply the forward patch + bump revision (`mstate/apply-optimistic-patch`). A
  remove of an absent key records the `:absent` inverse and no-ops the cache.
  Returns `[runtime-db' affected-keys inverse-vec]` — `inverse-vec` is the
  recorded `:rollback` slot the instance row carries (in apply order). Indexes are
  recomputed by the caller (a seed may create entries / tags)."
  [runtime-db target-map clock-ms]
  (reduce-kv
    (fn [[db' ks inverses] scoped-key patch-fn]
      (let [entry  (get-in db' (state/entry-path scoped-key))
            before (mstate/snapshot-entry entry)
            fwd    (forward-summary patch-fn before)
            inverse (mstate/record-optimistic-entry scoped-key before fwd)
            db''   (if (nil? patch-fn)
                     ;; optimistic REMOVE — dissoc by the byte key-id
                     (update-in db' (state/entries-path) dissoc (state/key-id scoped-key))
                     (let [[_scope resource-id _p] scoped-key
                           rspec    (registry/resource-meta resource-id)
                           stale-at (stale-at-for rspec clock-ms)
                           tags-fn  (:tags rspec)
                           ;; a freshly-seeded entry needs its resource's tags
                           ;; (so a later invalidation can reach it). An existing
                           ;; entry keeps its own (apply-optimistic-patch prefers
                           ;; the base entry's tags).
                           [_s _r rparams] scoped-key
                           tags     (when tags-fn (set (tags-fn rparams nil)))
                           entry'   (mstate/apply-optimistic-patch
                                      entry patch-fn resource-id
                                      {:clock-ms clock-ms :stale-at stale-at
                                       :tags tags :scoped-key scoped-key})]
                       (assoc-in db' (state/entry-path scoped-key) entry')))]
        [db'' (conj ks scoped-key) (conj inverses inverse)]))
    [runtime-db #{} []]
    target-map))

;; ---- the settle protocol (phase 4) — commit / rollback / reconcile --------
;;
;; The SETTLE slice (EP-0019 Decision 3) consumes the recorded inverse on the
;; instance row's `:patch-summary` `:rollback` slot + slice-1's
;; `state/revision-conflict?` to deterministically dispose each optimistic apply
;; when its mutation reply settles:
;;
;;   - SUCCESS (`:rf.mutation/optimistic-reconciled`) — the authoritative
;;     `:populates` / `:patches` overwrite the optimistic value; the recorded
;;     inverse is DISCARDED (the commit superseded it). The success handler
;;     already applies the cache consequences; the settle here only RECORDS the
;;     reconcile (which optimistic keys the authoritative write owned) + emits
;;     the trace + fills the instance row's `:reconciliation-refetches` slot.
;;   - FAILURE / accepted cancel / restore-DANGLE
;;     (`:rf.mutation/optimistic-rolled-back`) — per recorded inverse, the
;;     conflict-aware rollback: an UNMOVED `:revision` restores the recorded
;;     `:before` verbatim; a MOVED `:revision` defers to `:on-conflict`
;;     (`:invalidate` default — mark stale + refetch; `:force` — restore anyway).
;;   - STALE / superseded — handled by the existing stale-suppression branch
;;     (the inverse is discarded, never replayed; the newer apply recorded the
;;     truthful inverse). The settle below never runs on a suppressed reply.
;;
;; These pieces compute the rolled-back runtime-db + the per-key dispositions
;; (for the trace + the `:reconciliation-refetches` evidence) + the
;; per-conflicted-key invalidation fx; the pure decision lives in
;; `mstate/rollback-entry-disposition` / `mstate/restore-before`.

(defn- recorded-rollback
  "The recorded optimistic inverse vector (`mstate/record-optimistic-entry`
  shapes) the phase-1.5 apply wrote on the live instance's `:patch-summary`
  `:rollback` slot, or nil when this mutation ran no optimistic apply. Read off
  the live instance row (NOT the reply) — the inverse is durable instance state."
  [inst]
  (-> inst :patch-summary :rollback seq))

(defn- invalidate-conflicted-key-fx
  "Build the `[:dispatch [:rf.resource/invalidate-tags …]]` fx that recovers
  authoritative truth for ONE conflicted rollback key under `:on-conflict
  :invalidate` (EP-0019 Decision 3). Marks the entry stale in its OWN scope by
  ITS OWN tags (the read path then refetches the authoritative value), through
  the SAME single scoped invalidation engine the success path uses — never a
  blind restore of the stale recorded inverse. Reads the entry's CURRENT tags +
  scope from `runtime-db` (the moved entry the concurrent write left, NOT the
  stale snapshot). Returns nil when the (moved) entry has since vanished or
  carries no tags — there is nothing to invalidate (the read path will reload on
  the next ensure). `scoped-key` is `[scope resource-id params]`."
  [runtime-db scoped-key cause]
  (let [entry (get-in runtime-db (state/entry-path scoped-key))
        tags  (seq (:tags entry))
        scope (first scoped-key)]
    (when (and entry tags (some? scope))
      [:dispatch [:rf.resource/invalidate-tags
                  {:scope scope :tags (set tags) :cause cause}]])))

(defn- settle-optimistic-rollback
  "ROLL BACK the recorded optimistic apply on a FAILED / cancelled / dangled
  mutation reply (EP-0019 Decision 3). For each recorded inverse, decide the
  conflict-aware disposition (`mstate/rollback-entry-disposition` against the
  CURRENT entry + the resolved `on-conflict` policy), then either RESTORE the
  recorded `:before` verbatim (`mstate/restore-before` — an unmoved revision, or
  `:force`) or leave the moved entry in place and emit a scoped invalidation that
  refetches the authoritative value (`:invalidate`, the default). Recomputes the
  reverse indexes once after the restore pass (a restore may re-create / drop
  entries + tags).

  `inverse` is the recorded `:rollback` vector; `runtime-db` is the live cache at
  settle time; `on-conflict` is the resolved policy; `cause` is the invalidation
  `:cause`. Returns `{:runtime-db <rdb'> :dispositions [<disp> …] :invalidate-fx
  [<fx> …] :restored-keys [<sk> …] :conflicted-keys [<sk> …] :refetched-keys
  [<sk> …]}`. PURE w.r.t. trace (the caller emits `:rf.mutation/optimistic-
  rolled-back`)."
  [inverse runtime-db on-conflict cause]
  (let [dispositions (mapv (fn [{scoped-key :resource/key :as recorded}]
                             (mstate/rollback-entry-disposition
                               (get-in runtime-db (state/entry-path scoped-key))
                               recorded on-conflict))
                           inverse)
        ;; apply every :restore disposition to the cache (an :invalidate
        ;; disposition leaves the moved entry in place — the read path recovers).
        restored?    #(= :restore (:disposition %))
        rdb'         (reduce (fn [rdb disp]
                               (if (restored? disp)
                                 (mstate/restore-before rdb disp)
                                 rdb))
                             runtime-db dispositions)
        rdb''        (update rdb' state/resources-key state/recompute-indexes)
        ;; one scoped invalidation per :invalidate (conflicted, non-:force) key,
        ;; computed against the ROLLED-BACK runtime-db (`rdb''` — the restores are
        ;; in, so the invalidate reads the moved entries that stayed). Drops a key
        ;; whose moved entry vanished / has no tags (nothing to invalidate).
        inval-fxs    (into []
                           (keep (fn [{scoped-key :resource/key :keys [disposition]}]
                                   (when (= :invalidate disposition)
                                     (invalidate-conflicted-key-fx rdb'' scoped-key cause))))
                           dispositions)]
    {:runtime-db      rdb''
     :dispositions    dispositions
     :invalidate-fx   inval-fxs
     :restored-keys   (into [] (comp (filter restored?) (map :resource/key)) dispositions)
     :conflicted-keys (into [] (comp (filter :conflict?) (map :resource/key)) dispositions)
     :refetched-keys  (into [] (comp (filter #(= :invalidate (:disposition %)))
                                     (map :resource/key)) dispositions)}))

(defn- rollback-trace-dispositions
  "PURE: the per-key disposition rows for the `:rf.mutation/optimistic-rolled-back`
  trace (EP-0019 §Surfacing to tooling): each `{:resource/key :restored (bool)
  :conflict (bool) :on-conflict (:invalidate|:force when conflicted)}`. A
  `:restore` disposition reports `:restored true`; an `:invalidate` reports
  `:restored false` (the read path will refetch). `:conflict` mirrors whether the
  entry's revision moved."
  [dispositions on-conflict]
  (mapv (fn [{scoped-key :resource/key :keys [disposition conflict?]}]
          (cond-> {:resource/key scoped-key
                   :restored     (= :restore disposition)
                   :conflict     (boolean conflict?)}
            conflict? (assoc :on-conflict on-conflict)))
        dispositions))

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

(defn- plan-key-evidence
  "The per-descriptor scoped-key evidence the SETTLEMENT path needs from an
  invalidation `plan`, computed against the settle-time cache `entries` (read
  from `runtime-db`) — the SAME match the dispatched `:rf.resource/invalidate-
  tags` passes will see — via the SHARED pure `events/match-invalidation-keys`
  (so the reply, the trace, and the engine never disagree). Returns
  `{:invalidated-keys <vec> :populate-exempt <vec> :per-descriptor [<vec> …]}`:

  - `:invalidated-keys` (rf2-fi6tda.2) — every key a dispatched descriptor
    WILL mark stale (tags intersect, in the descriptor's resolved scope, not
    its own exempt key), de-duplicated across descriptors. This is the set
    Spec 016 §Mutation completion continuations wants unioned into the
    accepted reply's `:affected-keys` (the contract names keys POPULATED,
    PATCHED, REMOVED, **or marked stale**).
  - `:per-descriptor` (rf2-fi6tda.3 finding 2) — the EXACT exempt keys EACH
    dispatched descriptor spared, in `:dispatches` order. The runtime applies
    `:refetch-populated?` PER DESCRIPTOR (`plan->fx` passes the populated keys
    as `:exempt-keys` only for non-opt-in descriptors), so an opt-in descriptor
    spares NONE and a default descriptor spares the populated keys it matched.
    This is the truthful per-pass evidence (the settlement facet's
    `:dispatched` rows carry it).
  - `:populate-exempt` — the UNION of every descriptor's spared keys (a single
    `:refetch-populated? true` descriptor no longer collapses it to `[]`).

  `populated-ks` is the set of EXACT scoped keys this same mutation populated
  (matched by byte `key-id`). Per Spec 016 §Trace evidence for invalidation /
  §Populate is an authoritative load."
  [plan populated-ks runtime-db]
  (let [populated-ids (into #{} (map state/key-id) populated-ks)
        entries       (get-in runtime-db (state/entries-path))
        sk-by-id      (into {} (map (fn [[k-id e]] [k-id (:resource/key e)])) entries)
        id->sk        (fn [ids] (into [] (keep sk-by-id) ids))
        ;; per dispatch: the keys it will stale (exempt set = the populated keys
        ;; UNLESS it opted into a same-mutation refetch — the EXACT exempt rule
        ;; `plan->fx` applies) + the populated keys THIS descriptor spared.
        per-descriptor
        (mapv (fn [{:keys [scope cross-scope? tags refetch-populated?]}]
                (let [tag-set (set tags)
                      exempt  (if refetch-populated? #{} populated-ids)
                      ;; what this descriptor actually stales (exempt removed)
                      {stale :matched-ids} (events/match-invalidation-keys
                                             entries scope cross-scope? tag-set exempt)
                      ;; the populated keys it SPARED (matched with no exempt set)
                      spared  (if refetch-populated?
                                #{}
                                (let [{m :matched-ids} (events/match-invalidation-keys
                                                         entries scope cross-scope? tag-set #{})]
                                  (set/intersection (set m) populated-ids)))]
                  {:stale stale :exempt spared}))
              (:dispatches plan))
        stale-ids  (into #{} (mapcat :stale) per-descriptor)
        exempt-ids (into #{} (mapcat :exempt) per-descriptor)]
    {:invalidated-keys (id->sk stale-ids)
     ;; report by the scoped-key VECTOR, preserving each populated key's spelling
     :populate-exempt  (into [] (filter (fn [sk] (contains? exempt-ids (state/key-id sk)))) populated-ks)
     :per-descriptor   (mapv (comp id->sk :exempt) per-descriptor)}))

(defn- plan-trace
  "The descriptor-level invalidation evidence facet for the mutation settlement
  trace (Spec 016 §Trace evidence for invalidation): the descriptor count, the
  per-descriptor resolved `(scope, cross-scope?, tags, refetch-populated?)` —
  each now also carrying its OWN `:exempt-keys` (the populated keys THAT
  descriptor's pass spared, rf2-fi6tda.3 finding 2) — the fail-closed
  `:unresolved` `{:from-db …}` ids (descriptors that resolved nil and produced
  no invalidation), and the top-level `:populate-exempt` UNION (the keys at
  least one descriptor spared; NOT collapsed to `[]` the instant any descriptor
  opts into `:refetch-populated? true`). Computed per-descriptor against
  `runtime-db`'s settle-time entries. nil-safe (an absent plan yields nil)."
  [plan populated-ks runtime-db]
  (when plan
    (let [{:keys [populate-exempt per-descriptor]}
          (plan-key-evidence plan populated-ks runtime-db)]
      {:descriptor-count (:descriptor-count plan)
       :dispatched (mapv (fn [{:keys [scope cross-scope? tags refetch-populated?]} exempt-keys]
                           {:scope scope :cross-scope? cross-scope? :tags (vec tags)
                            :refetch-populated? (boolean refetch-populated?)
                            :exempt-keys exempt-keys})
                         (:dispatches plan)
                         per-descriptor)
       :unresolved (vec (:unresolved plan))
       :populate-exempt populate-exempt})))

;; ---- dev-only write-side scope-mismatch diagnostic (rf2-byl7bk.4) ----------
;;
;; The mutation-scope footgun (Spec 016 §Mutation scope is two distinct scopes
;; — Scope-match guidance): a mutation's resolved (execution) scope defaults
;; FAIL-OPEN to `:rf.scope/global` when neither the execute payload nor the
;; `reg-mutation` spec declares `:scope`, and a bare-tag-set / `:rf.scope/same`
;; `:invalidates` descriptor then inherits that resolved scope. If the resources
;; carrying the invalidated tags live in a NON-global scope (session / tenant /
;; user-scoped reads), the invalidation runs in the WRONG scope, matches NOTHING,
;; and SILENTLY misses — no error is raised (a scoped invalidation that matches
;; no entry in its own scope is a legitimate "no match in this scope").
;;
;; This is the WRITE-SIDE complement of the read-side `:rf.warning/resource-sub-
;; scope-mismatch` (`subs.cljc`): the SAME silent-miss footgun, observed at the
;; mutation-settlement boundary. The runtime tripwire reuses the SHARED
;; `events/match-invalidation-keys` `:other-scope-hit?` signal — a descriptor
;; that matched ZERO keys in its resolved scope WHILE the same tags DO match an
;; entry in a DIFFERENT scope is a likely scope mismatch (the precise case;
;; a tag that simply has no cache entry anywhere does NOT warn — it is a true
;; "nothing to invalidate", not a mismatch). A `:cross-scope? true` descriptor
;; is the AUDITED deliberate escape and is never flagged.

(defonce ^:private
  ^{:doc "One-shot dedupe set of `[mutation-id descriptor-scope other-scope tags]`
   tuples already warned about, so a mutation re-executed many times (or a form
   firing on every keystroke) emits the write-side scope-mismatch dev warning
   ONCE per genuine mismatch rather than flooding the trace. Host-side transient
   dev state (NOT runtime-db); cleared per-test by the resources reset hook
   (`re-frame.resources.test-support`). Mirrors the sub-side
   `re-frame.resources.subs/warned-scope-mismatch`."}
  warned-mutation-scope-mismatch
  (atom #{}))

(defn reset-mutation-scope-mismatch-warnings!
  "Drop every recorded write-side scope-mismatch warning dedupe key (test
  isolation). Published through the resources reset hook so the shared CLJS
  reset-runtime fixture clears it per test — it is host-side transient dev
  state, not runtime-db, so the runtime/frames reset does not touch it. Mirrors
  `re-frame.resources.subs/reset-scope-mismatch-warnings!`. Returns nil."
  []
  (reset! warned-mutation-scope-mismatch #{})
  nil)

(defn maybe-warn-scope-mismatch!
  "Emit the dev-only write-side likely-scope-mismatch warning
  (`:rf.warning/mutation-scope-mismatch`, rf2-byl7bk.4) for each dispatched
  invalidation descriptor whose resolved scope matched NO cache entry while the
  SAME tags DO match an entry in a DIFFERENT scope — the write-side complement
  of the read-side `:rf.warning/resource-sub-scope-mismatch`, and the runtime
  tripwire for the mutation-scope footgun (Spec 016 §Mutation scope is two
  distinct scopes — Scope-match guidance).

  DEV-ONLY: the whole body is behind `interop/debug-enabled?` so Closure DCE
  elides it in production (`:advanced` + `goog.DEBUG=false`), and the emit rides
  `trace/emit!` (same gate). One-shot idempotent per distinct
  `[mutation-id descriptor-scope other-scope sorted-tags]` so a mutation
  re-executed repeatedly warns once per genuine mismatch.

  A `:cross-scope? true` descriptor (the AUDITED deliberate escape) is never
  flagged — it ignores the scope filter by construction, so a 'no match in this
  scope' is impossible / intentional for it. Reuses the SHARED pure
  `events/match-invalidation-keys` (against the settle-time `entries`), so the
  diagnostic, the dispatched `:rf.resource/invalidate-tags`, and the settlement
  trace never disagree on the match set. Returns nil."
  [plan {:keys [frame-id mutation-id instance-id mut-scope entries]}]
  (when (and interop/debug-enabled? plan)
    (doseq [{:keys [scope cross-scope? tags]} (:dispatches plan)]
      ;; a cross-scope sweep cannot mis-scope; only a scoped descriptor that
      ;; matched nothing-here-but-something-elsewhere is the footgun.
      (when (and (not cross-scope?) (seq tags))
        (let [tag-set (set tags)
              {:keys [matched other-scope-hit?]}
              (events/match-invalidation-keys entries scope cross-scope? tag-set #{})]
          (when (and (empty? matched) other-scope-hit?)
            (let [other-scope (some (fn [[_k-id entry]]
                                      (let [[s] (:resource/key entry)]
                                        (when (and (not= s scope)
                                                   (seq (set/intersection (set (:tags entry)) tag-set)))
                                          s)))
                                    entries)
                  dedupe-key  [mutation-id scope other-scope (vec (sort-by pr-str tag-set))]]
              (when-not (contains? @warned-mutation-scope-mismatch dedupe-key)
                (swap! warned-mutation-scope-mismatch conj dedupe-key)
                (trace/emit! :warning :rf.warning/mutation-scope-mismatch
                             {:rf.frame/id        frame-id
                              :mutation           mutation-id
                              :instance           instance-id
                              :descriptor-scope   scope
                              :mutation-scope     mut-scope
                              :other-scope        other-scope
                              :tags               (vec tag-set)
                              :recovery           :fix-scope
                              :hint               (str "mutation " (pr-str mutation-id)
                                                       " invalidated tags " (pr-str (vec tag-set))
                                                       " in scope " (pr-str scope)
                                                       " but NO cache entry matched there, while a "
                                                       "DIFFERENT scope " (pr-str other-scope)
                                                       " DOES hold a matching entry — the "
                                                       "invalidation SILENTLY MISSED (the scoped "
                                                       "read is never refreshed). The mutation's "
                                                       "resolved scope (" (pr-str mut-scope)
                                                       ") does not match the scope of the resources "
                                                       "it affects. Declare the matching scope on "
                                                       "the execute payload :scope, or use a "
                                                       "per-target :invalidates descriptor "
                                                       "{:scope … :tags …} (e.g. {:scope {:from-db "
                                                       ":your/session} :tags …}). Per Spec 016 "
                                                       "§Mutation scope is two distinct scopes.")})))))))))

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
  "PURE: build the `[:dispatch <completed-event>]` fx that delivers the
  continuation reply to the call-site `:reply-to` target, or nil when no target
  was supplied. Uses the shared `re-frame.reply/complete` to append the reply
  map as the final argument (the `:append` delivery — static call-site args are
  preserved, the reply lands after them), so the continuation rides the SAME
  reply substrate every managed-async family uses (NOT a family-private
  callback contract). A nil / blank target yields nil (no continuation).

  No trace side effect (rf2-ru73k6 F2): the `:rf.mutation/replied` evidence is
  emitted from the effect-bound settlement boundary, AFTER
  `:rf.mutation/succeeded`, so the trace row reflects the actual phase-6
  ordering (continuation runs after cache consequences + instance settlement,
  Spec 016 §Phase order) rather than landing while the effect vector is still
  being built."
  [reply-to reply]
  (when-let [completed (reply/complete reply-to reply)]
    [:dispatch completed]))

(defn- emit-replied!
  "Emit the `:rf.mutation/replied` phase-6 trace evidence for an accepted reply
  that DID continue into app workflow (a non-nil `cont-fx`). Called from the
  effect-bound settlement boundary AFTER `:rf.mutation/succeeded` /
  `:rf.mutation/failed` is emitted, so the trace row truthfully reflects the
  continuation-dispatch boundary's place in the phase order (rf2-ru73k6 F2;
  tools/xray/spec/024 §6c documents it as post-settlement evidence). Never
  fires on a stale/suppressed reply (that path returns no continuation) nor
  when the call site supplied no `:reply-to` (cont-fx is nil)."
  [cont-fx {:keys [frame-id mutation-id instance-id work-id status reply-to]}]
  (when cont-fx
    (trace/emit! :rf.event :rf.mutation/replied
                 {:rf.frame/id frame-id :mutation mutation-id :instance instance-id
                  :work/id work-id :status status :target reply-to
                  :cause [:mutation mutation-id instance-id]})))

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

  EP-0019 PHASE 1.5 — when the mutation declares an `:optimistic` /
  `:optimistic-tags` plan (and the call did NOT opt out with
  `{:optimistic? false}`, Q4), the FORWARD optimistic patch is applied to the
  resource cache BEFORE the request is lowered: each touched entry is
  snapshotted (the truthful inverse) + its `:revision` recorded on the instance
  row's `:patch-summary` `:rollback` slot, the forward patch applied, and the
  entry's `:revision` bumped. Emits `:rf.mutation/optimistic-applied`. The SETTLE
  slice consumes that recorded inverse (commit / rollback / reconcile).

  Returns the effects map (`:rf.db/runtime` + `:fx`)."
  [{rt :rf.db/runtime, frame-id :rf.frame/id
    gen-allocation :rf.resource/generation-allocation
    time-ms :rf/time-ms, app-db :db}
   [_event-id {:keys [mutation instance scope cause reply-to] :as payload}]]
  (let [where      'rf.mutation/execute
        runtime-db (or rt {})
        spec       (mreg/require-mutation-spec! mutation where)
        ;; rf2-hgy5kf — thread `:params` presence (absent vs explicit nil) to
        ;; the validation boundary: an absent slot defaults to `{}` there, an
        ;; explicit `{:params nil}` reaches `:params-schema` unchanged.
        cparams    (mreg/validate+canonicalize-params
                     mutation spec (state/params-present? payload) where)
        cscope     (mreg/resolve-scope mutation spec scope)
        ;; rf2-e8wj5t — reject a non-serializable caller-supplied instance id
        ;; BEFORE any runtime-db / work-ledger write or HTTP lowering (the
        ;; instance id is durable + trace-visible + epoch-restore-safe). A nil
        ;; instance falls through to the generated id (serializable by
        ;; construction).
        _          (mstate/validate-instance-id! instance where)
        ;; rf2-6kdcs9 — the call-site `:reply-to` continuation (EP-0016 D1) is
        ;; a TRANSPORT-PAYLOAD-only app target (NOT a durable ledger row fact),
        ;; but it MUST still be data-only: it rides the internal reply payload
        ;; across the transport boundary and is later dispatched. Run it through
        ;; the shared `re-frame.reply/durable-target` HERE, at issuance — BEFORE
        ;; any runtime-db / work-ledger write, transport lower, or trace — so a
        ;; malformed target (missing / non-vector `:event`) or a host handle
        ;; smuggled into a public slot FAILS LOUD now rather than silently
        ;; mis-delivering (or stranding a non-serializable value on the wire)
        ;; at completion. A nil target stays nil (no continuation). The
        ;; data-only-validated target rides the `:reply-payload` below.
        reply-to'  (when (some? reply-to) (reply/durable-target reply-to))
        ;; rf2-abyycr — the generation is the RECORDED allocation value (the
        ;; generator-backed `:rf.resource/generation-allocation` cofx minted
        ;; it at processing-start and the runtime recorded it on the token),
        ;; NOT a `(inc snapshot)` re-mint from an ambient read here. The
        ;; instance-id + work-id derive from it, so recording the generation
        ;; reproduces both on replay for free — a recorded mutation reply
        ;; keeps its current-vs-stale verdict on replay.
        generation (:generation gen-allocation)
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
        ;; EP-0010 §Resources, Mutations, And Work-Ledger Timestamps + EP-0017
        ;; declared-only delivery (rf2-601ife): `:rf.mutation/execute` writes the
        ;; durable instance + work-ledger `:started-at` from the TRIGGERING
        ;; TOKEN'S causal `:rf/time-ms` (DECLARED via `:rf.cofx/requires`,
        ;; consumed FLAT — not reached through the `:rf.cofx` token), NOT an
        ;; ambient clock read in the reducer. Replay-stable: the same execute
        ;; token mints the same `:started-at`.
        started-at time-ms
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
                          :resource/key [:rf.mutation instance-id]
                          :generation   generation
                          :transport    transport-id
                          :cause        cause
                          :started-at   started-at})
                       ;; the work record's neutral :work/kind for a mutation
                       ;; attempt (the ledger is named neutrally; the resource
                       ;; writer uses :resource, the mutation writer :mutation).
                       (assoc :work/kind :mutation))
        ;; rf2-rrcfwk — guard the declared transport (registration-time
        ;; misconfig throw), then lower directly into the only initial-scope
        ;; transport. The one-arm dispatch indirection
        ;; (`transport/lower-ensure`) is folded into this guarded call.
        lower-fx   (do
                     (transport/assert-managed-transport! transport-id where)
                     (transport-http/lower
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
                                       ;; the data-only-validated (rf2-6kdcs9)
                                       ;; call-site target rides the payload.
                                       (some? reply-to') (assoc :reply-to reply-to'))
                      :work-id      work-id
                      :resource/key [:rf.mutation instance-id]
                      :scope        cscope
                      :frame-id     frame-id
                      :generation   generation
                      :where        where}))
        rdb0       (-> runtime-db
                       (assoc-in (mstate/instance-path instance-id) instance')
                       (work-ledger/put-record work-id record))
        ;; EP-0019 PHASE 1.5 — the FORWARD optimistic apply, BEFORE the request
        ;; lowers. Per-call opt-out (Q4): `{:optimistic? false}` forces the
        ;; pessimistic path for one call (the registration plan is otherwise
        ;; always-on). Resolve the `:optimistic` exact targets + the
        ;; `:optimistic-tags` tag-matched targets (each fail-closed on a
        ;; nil-resolving `{:from-db …}` scope — Rider 2), snapshot + apply each,
        ;; recording the truthful inverse on the instance row. The apply runs
        ;; against `rdb0` (which already carries the fresh `:pending` instance)
        ;; — but a `:before-request` timing makes this contradictory and is
        ;; rejected at registration (Rider 3), so a mutation reaching here with
        ;; an optimistic plan never also runs a before-plan.
        opt-out?    (and (contains? payload :optimistic?) (false? (:optimistic? payload)))
        has-opt?    (and (not opt-out?)
                         (or (:optimistic spec) (:optimistic-tags spec)))
        opt-entries (when has-opt? (get-in rdb0 (state/entries-path)))
        [exact-tm exact-nils]
        (when has-opt?
          (optimistic-exact-targets (:optimistic spec) cparams cscope app-db where))
        [tag-tm tag-nils]
        (when has-opt?
          (optimistic-tag-targets
            (normalize-optimistic-tag-descriptors
              (when-let [f (:optimistic-tags spec)] (f cparams))
              {:frame-id frame-id :mutation mutation})
            opt-entries cscope app-db where))
        ;; exact targets first, then tag-matched (a tag-matched key that is also
        ;; an exact target keeps the exact patch-fn — exact wins, it is the more
        ;; specific declaration). A nil exact patch-fn (optimistic remove) is
        ;; preserved by `merge`.
        opt-target-map (when has-opt? (merge tag-tm exact-tm))
        opt-nil-ids    (-> (vec exact-nils) (into tag-nils))
        ;; "the apply happened" = there was an optimistic plan AND the call did
        ;; not opt out — even if EVERY target fail-closed-dropped (we still
        ;; record the `:target-unresolved` evidence + emit the trace).
        opt-applied?   (boolean (and has-opt? (or (seq opt-target-map) (seq opt-nil-ids))))
        snapshot-id    (when opt-applied? (mint-snapshot-id instance-id generation))
        [rdb1 opt-ks opt-inverse]
        (if (seq opt-target-map)
          (apply-optimistic rdb0 opt-target-map started-at)
          [rdb0 #{} []])
        ;; record the snapshot inverse on the instance row's reserved
        ;; `:patch-summary` `:snapshot-id` / `:rollback` slots (the SETTLE slice
        ;; replays them). The `:target-unresolved` carries the fail-closed
        ;; nil-resolved `{:from-db …}` optimistic targets (recorded even when
        ;; ALL targets dropped — the evidence must survive).
        rdb'       (if opt-applied?
                     (-> rdb1
                         (assoc-in (conj (mstate/instance-path instance-id) :patch-summary)
                                   {:snapshot-id       snapshot-id
                                    :rollback          opt-inverse
                                    :optimistic-keys   (vec opt-ks)
                                    :target-unresolved (vec opt-nil-ids)
                                    ;; the SETTLE slice fills these:
                                    :reconciliation-refetches nil})
                         ;; a seed / remove may have created / dropped entries +
                         ;; tags — recompute the reverse indexes.
                         (update state/resources-key state/recompute-indexes))
                     rdb1)]
    (when opt-applied?
      (trace/emit! :rf.event :rf.mutation/optimistic-applied
                   {:rf.frame/id frame-id :mutation mutation :instance instance-id
                    :work/id work-id :generation generation :scope cscope
                    :snapshot-id snapshot-id
                    :affected-keys (vec opt-ks)
                    ;; per-key revision observed at apply time (the conflict-check
                    ;; basis the SETTLE slice compares) + the forward op shape.
                    :revisions (mapv (fn [{:keys [resource/key revision forward]}]
                                       {:resource/key key :revision revision :forward forward})
                                     opt-inverse)
                    :tag-matched-keys (vec (keys tag-tm))
                    :target-unresolved (vec opt-nil-ids)
                    :cause [:mutation mutation instance-id]}))
    (trace/emit! :rf.event :rf.mutation/started
                 (cond-> {:rf.frame/id frame-id :mutation mutation :instance instance-id
                          :work/id work-id :generation generation :scope cscope
                          :cause cause :invalidate-timing invalidate-timing}
                   ;; EP-0016 D2: a `:before-request` invalidation attaches its
                   ;; descriptor-level evidence (resolved scopes + fail-closed
                   ;; nil-resolved `{:from-db …}` ids) to the started trace.
                   ;; No populates before the request, so the exempt set is empty.
                   before-plan (assoc :invalidation (plan-trace before-plan #{} runtime-db))))
    ;; DEV-ONLY write-side scope-mismatch tripwire (rf2-byl7bk.4) — a
    ;; `:before-request` invalidation can mis-scope exactly as a success-time
    ;; one (it lowers the same scoped descriptors). Checked against the
    ;; execute-time runtime-db entries (the cache state before the write).
    (maybe-warn-scope-mismatch!
      before-plan {:frame-id frame-id :mutation-id mutation :instance-id instance-id
                   :mut-scope cscope :entries (get-in runtime-db (state/entries-path))})
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
        ;; rf2-8iciw8 — the `:rf.runtime/mutations` map is keyed on the CEDN-1
        ;; byte `key-id`; the storage targets (`dissoc` / `get`) MUST be those
        ;; byte key-ids, never the raw caller-supplied instance id (which is
        ;; `=`-coarser than the byte identity for sequential ids). An explicit
        ;; `:instance` is matched by its OWN byte key-id so a `[:row 7]` clear
        ;; does NOT also gate a CEDN-distinct `'(:row 7)` row.
        target-kids (cond
                      (some? instance) (let [k (mstate/instance-key-id instance)]
                                         (when (contains? instances k) [k]))
                      (some? mutation) (keep (fn [[kid inst]]
                                               (when (= mutation (:mutation/id inst)) kid))
                                             instances)
                      :else            nil)
        target-kids (vec target-kids)
        ;; collect in-flight work ids for the cleared instances (abort + cancel)
        in-flight  (into []
                         (keep (fn [kid]
                                 (let [inst (get instances kid)
                                       wid  (:current-work inst)]
                                   (when wid
                                     [wid (:transport (work-ledger/get-record runtime-db wid))]))))
                         target-kids)
        rdb'       (-> runtime-db
                       (update-in (mstate/instances-path)
                                  (fn [m] (reduce dissoc m target-kids)))
                       (as-> db (reduce (fn [d [wid _]]
                                          (work-ledger/update-record
                                            d wid work-ledger/mark-terminal
                                            :cancelled {:reason :mutation-clear}))
                                        db in-flight)))
        ;; surface the kind-preserving `:instance/id` values in the trace (the
        ;; byte key-ids are an opaque storage detail), read off the rows BEFORE
        ;; they were dissoc'd.
        cleared-ids (mapv #(:instance/id (get instances %)) target-kids)]
    (trace/emit! :rf.event :rf.mutation/cleared
                 {:rf.frame/id frame-id :cleared cleared-ids
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
  mutation reply, carrying its bespoke facts (`:instance` / `:work/id` /
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
                  :work/id work-id :generation generation :outcome outcome
                  ;; reply-envelope vocabulary (Managed-Effects §9) — the
                  ;; canonical :status :stale reply produced via the shared
                  ;; substrate, recorded ADDITIVELY (the bespoke facts above
                  ;; are preserved).
                  :rf.reply/status      (:status summary)
                  :rf.reply/work-status (:work/status summary)
                  :rf.reply/work-id     (:work/id summary)
                  :rf.reply/stale-reason (:stale/reason summary)
                  :rf.reply/correlation (:correlation summary)
                  ;; rf2-waawic — the SHARED carried/current stale-gate facts
                  ;; `re-frame.reply/suppress` already computed on `(:trace
                  ;; stale)`, projected so the uniform reply-envelope view
                  ;; reads the stale gate without mutation-family-specific
                  ;; parsing (Xray's reply-envelope panel reads them at :518).
                  :rf.reply/carried     (:rf.reply/carried (:trace stale))
                  :rf.reply/current     (:rf.reply/current (:trace stale))})))

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
  [{rt :rf.db/runtime, frame-id :rf.frame/id, time-ms :rf/time-ms, app-db :db}
   [_event-id {work-id :work/id :keys [instance-id mutation-id generation scope reply-to] :as payload} http-result]]
  (let [where      'rf.mutation.internal/succeeded
        runtime-db (or rt {})
        ;; EP-0010 §Managed Effects And Reply Tokens / §Resources, Mutations,
        ;; And Work-Ledger Timestamps + EP-0017 declared-only delivery
        ;; (rf2-601ife): the reply is a CAUSAL TOKEN; the host completion time
        ;; (`:completed-at`, read ONCE at the transport finalisation boundary)
        ;; rides the reply event's causal `:rf/time-ms`, DECLARED via
        ;; `:rf.cofx/requires` and consumed FLAT here. The handler MUST NOT
        ;; re-read the clock NOR reach through the `:rf.cofx` token. It carries
        ;; onto the canonical reply as `:completed-at` and is the source of the
        ;; terminal `:settled-at` + any patch/populate `:loaded-at`.
        completed-at time-ms
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
            ;; controlled REMOVES (EP-0016 Rider 2 / rf2-fi6tda.3 finding 1 —
            ;; accepted replies apply patches, populates, invalidates, AND
            ;; removes). Each map-form target's scope resolves exactly as a
            ;; patch / populate target; the resolved key is DISSOC'd + its
            ;; in-flight work settled `:cancelled` (mirroring
            ;; `:rf.resource/remove`). Applied BEFORE the invalidation match so
            ;; a removed key is never ALSO reported stale (it is gone).
            [rdb3 removed-ks removed-work remove-nil-ids] (apply-removes rdb2 (:removes spec) params result scope app-db where)
            patch-affected      (set/union patched-ks populated-ks)
            timer-policies      (merge patch-policies populate-policies)
            target-nil-ids      (-> (vec patch-nil-ids) (into populate-nil-ids) (into remove-nil-ids))
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
            ;; rf2-fi6tda.2 — the keys the invalidation pass WILL mark stale,
            ;; computed against the post-cache-consequence entries (`rdb3`) via
            ;; the SHARED match the dispatched invalidate-tags will use, PLUS
            ;; the actual per-descriptor populate-exempt evidence (rf2-fi6tda.3
            ;; finding 2). Empty when no invalidation timing fires.
            inv-key-evidence (when inv-plan (plan-key-evidence inv-plan populated-ks rdb3))
            invalidated-ks   (vec (:invalidated-keys inv-key-evidence))
            ;; Spec 016 §Mutation completion continuations: `:affected-keys` are
            ;; the keys POPULATED, PATCHED, REMOVED, OR MARKED STALE by the
            ;; accepted reply — the union of all four (rf2-fi6tda.2 + .3).
            affected    (vec (distinct (concat patched-ks populated-ks removed-ks invalidated-ks)))
            ;; EP-0019 PHASE 4 — COMMIT the optimistic apply. An accepted `:ok`
            ;; reply settles the optimistic value AUTHORITATIVELY: the
            ;; `:populates` / `:patches` above already OVERWROTE the optimistic
            ;; value with the server's, so the recorded inverse is DISCARDED (the
            ;; commit superseded it — no rollback). The reconcile only RECORDS the
            ;; evidence: the snapshot id, which optimistic keys the authoritative
            ;; write owned (populate / patch / remove), and the
            ;; `:reconciliation-refetches` — the optimistic keys NOT settled by an
            ;; authoritative populate/patch but marked stale by this mutation's
            ;; invalidation (the read path refetches them; a populated key is
            ;; EXEMPT per Rider 1, so it never lands here). nil when this mutation
            ;; ran no optimistic apply.
            opt-summary  (:patch-summary inst)
            opt-applied? (some? (:snapshot-id opt-summary))
            opt-keys     (when opt-applied?
                           (set (map :resource/key (:rollback opt-summary))))
            authoritative-keys (set/union patched-ks populated-ks (set removed-ks))
            ;; the optimistic keys an authoritative populate/patch/remove owned
            ;; (the commit overwrote the optimistic value with the server's).
            committed-keys     (when opt-applied?
                                 (vec (filter authoritative-keys opt-keys)))
            ;; the optimistic keys this mutation's invalidation marked stale (so
            ;; the read path will refetch the authoritative value) — the truthful
            ;; `:reconciliation-refetches`.
            invalidated-set    (set invalidated-ks)
            reconciliation-refetches (when opt-applied?
                                       (vec (filter invalidated-set opt-keys)))
            patch-summary (cond-> {:patched   (vec patched-ks)
                                   :populated (vec populated-ks)
                                   :removed   (vec removed-ks)
                                   :invalidated      invalidated-ks
                                   :invalidated-tags (vec (or inv-tags #{}))
                                   ;; EP-0016 Rider 2 fail-closed evidence: map-form
                                   ;; populate/patch/remove targets whose {:from-db …}
                                   ;; scope resolved nil were DROPPED (never written
                                   ;; under an implicit global).
                                   :target-unresolved (vec target-nil-ids)
                                   ;; pessimistic write: the optimistic slots stay nil.
                                   :snapshot-id nil :rollback nil
                                   :reconciliation-refetches nil}
                            ;; EP-0019 — FILL the reserved optimistic slots on a
                            ;; committed optimistic write.
                            opt-applied?
                            (assoc :snapshot-id (:snapshot-id opt-summary)
                                   :rollback    (:rollback opt-summary)
                                   :committed   committed-keys
                                   :reconciliation-refetches reconciliation-refetches))
            inst'       (mstate/instance-succeeded
                          inst {:result result :settled-at clock-ms
                                :affected-keys affected :patch-summary patch-summary})
            rdb'        (-> rdb3
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
                                  :resource/key   scoped-key
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
            ;; continuation runs after the invalidation it composes with. PURE —
            ;; the `:rf.mutation/replied` trace is emitted below, AFTER
            ;; `:rf.mutation/succeeded` (rf2-ru73k6 F2).
            cont-fx     (continuation-fx
                          reply-to
                          (continuation-reply
                            reply {:mutation-id mutation-id :params params
                                   :instance-id instance-id :scope scope
                                   ;; rf2-fi6tda.2 + .3: the full affected set
                                   ;; (populated + patched + removed + stale),
                                   ;; not just patch/populate.
                                   :affected-keys affected}))
            ;; best-effort abort of each REMOVED entry's in-flight attempt +
            ;; cancel its advisory timers (its durable facts are gone) —
            ;; mirroring `:rf.resource/remove`. Stale suppression by work-id +
            ;; generation remains the correctness boundary.
            remove-abort-fx (into [] (keep (fn [[wid transport]]
                                             (work-ledger/abort-fx transport frame-id wid)))
                                  removed-work)
            remove-timer-fx (when (seq removed-ks)
                              [[:rf.resource/cancel-timers
                                {:frame-id frame-id :resource/keys (vec removed-ks)}]])]
        (work-ledger/clear-handle! frame-id work-id)
        (trace/emit! :rf.event :rf.mutation/succeeded
                     (cond-> {:rf.frame/id frame-id :instance instance-id :mutation mutation-id
                              :work/id work-id :generation generation
                              :affected-keys affected :patch-summary patch-summary}
                       ;; rf2-fi6tda.3 finding 1: removed keys ride the
                       ;; settlement trace (the same scoped-key VECTOR shape).
                       (seq removed-ks) (assoc :removed (vec removed-ks))
                       ;; EP-0016 D2: the descriptor-level invalidation evidence
                       ;; (resolved scope per descriptor + fail-closed
                       ;; nil-resolved `{:from-db …}` ids) rides the settlement
                       ;; trace (Spec 016 §Trace evidence for invalidation). The
                       ;; Rider 1 populate-exempt evidence (the populated keys
                       ;; exempted from this mutation's refetch) rides the same
                       ;; facet (Spec 016 §Populate is an authoritative load).
                       inv-plan (assoc :invalidation (plan-trace inv-plan populated-ks rdb3))))
        ;; EP-0019 — the optimistic RECONCILE trace (phase 4, ok). Emitted only
        ;; when this mutation ran an optimistic apply: which optimistic keys the
        ;; authoritative populate/patch/remove COMMITTED (overwrote the optimistic
        ;; value with the server's) + the `:reconciliation-refetches` (optimistic
        ;; keys this mutation's invalidation marked stale → the read path
        ;; refetches). The recorded inverse was DISCARDED (the commit superseded
        ;; it — no rollback on success).
        (when opt-applied?
          (trace/emit! :rf.event :rf.mutation/optimistic-reconciled
                       {:rf.frame/id frame-id :instance instance-id :mutation mutation-id
                        :work/id work-id :generation generation
                        :snapshot-id (:snapshot-id opt-summary)
                        :optimistic-keys (vec opt-keys)
                        :committed committed-keys
                        :reconciliation-refetches reconciliation-refetches
                        :cause [:mutation mutation-id instance-id]}))
        ;; DEV-ONLY write-side scope-mismatch tripwire (rf2-byl7bk.4) — for each
        ;; dispatched descriptor that matched NO entry in its resolved scope while
        ;; the same tags DO match an entry in a DIFFERENT scope, emit the loud
        ;; `:rf.warning/mutation-scope-mismatch` diagnostic (the write-side
        ;; complement of the sub-side `:rf.warning/resource-sub-scope-mismatch`).
        ;; Computed against `rdb3` (the post-cache-consequence settle-time
        ;; entries the dispatched invalidate-tags will themselves see).
        (maybe-warn-scope-mismatch!
          inv-plan {:frame-id frame-id :mutation-id mutation-id :instance-id instance-id
                    :mut-scope scope :entries (get-in rdb3 (state/entries-path))})
        ;; PHASE 6 evidence — emitted AFTER `:rf.mutation/succeeded` so the
        ;; `:rf.mutation/replied` trace row truthfully follows settlement in the
        ;; phase order (rf2-ru73k6 F2). No-op when no `:reply-to` continued.
        (emit-replied! cont-fx
                       {:frame-id frame-id :mutation-id mutation-id
                        :instance-id instance-id :work-id work-id
                        :status (:status reply) :reply-to reply-to})
        {:rf.db/runtime rdb'
         :fx (cond-> (vec timer-fx)
               (seq remove-abort-fx) (into remove-abort-fx)
               remove-timer-fx       (into remove-timer-fx)
               inv-fxs               (into inv-fxs)
               cont-fx               (conj cont-fx))}))))

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
  [{rt :rf.db/runtime, frame-id :rf.frame/id, time-ms :rf/time-ms, app-db :db}
   [_event-id {work-id :work/id :keys [instance-id mutation-id generation scope reply-to] :as payload} http-result]]
  (let [where      'rf.mutation.internal/failed
        runtime-db (or rt {})
        ;; EP-0010 §Managed Effects And Reply Tokens / §Resources, Mutations,
        ;; And Work-Ledger Timestamps + EP-0017 declared-only delivery
        ;; (rf2-601ife): a terminal mutation reply writes `:settled-at` from the
        ;; reply completion time. The host `:completed-at` (read ONCE at the
        ;; transport finalisation boundary) rides the reply event's causal
        ;; `:rf/time-ms`, DECLARED via `:rf.cofx/requires` and consumed FLAT
        ;; here; the handler MUST NOT re-read the clock NOR reach through the
        ;; `:rf.cofx` token. Carried onto the canonical reply as `:completed-at`.
        completed-at time-ms
        ;; the ONE canonical reply map (Managed-Effects §The uniform reply
        ;; envelope), `:work/kind :mutation`. `:error` carries the closed
        ;; `:rf.http/*` envelope the instance `:error` also stores.
        reply      (rreply/failure-reply payload (reply-failure-error payload http-result)
                                         {:work-kind rreply/work-kind-mutation
                                          :completed-at completed-at})
        error      (:error reply)
        ;; rf2-qsn30x (EP-0011 §Status taxonomy / §Work-status mapping): an
        ;; `:rf.http/aborted` failure envelope is an intentional CANCELLATION,
        ;; which `failure-reply` lowers to `:status :cancelled` /
        ;; `:work/status :cancelled` (not `:error` / `:failed`). The accepted
        ;; live path MUST settle the work-ledger row terminal `:cancelled`,
        ;; agreeing with the reply's `:work/status` — settling it `:failed`
        ;; while the reply says `:cancelled` violates the closed status
        ;; taxonomy. A STALE abort still settles `:suppressed` (the nil-inst
        ;; path below): stale validation wins over the natural cancellation
        ;; status (Managed-Effects §Stale suppression). The canonical reply's
        ;; `:status` drives the branch (the family's classifier).
        aborted?   (= :cancelled (:status reply))
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
            cause    [:mutation mutation-id instance-id]
            ;; EP-0019 PHASE 4 — ROLL BACK the recorded optimistic apply. An
            ;; accepted `:error` (and an accepted terminal `:cancelled`) reply is
            ;; the deterministic ROLLBACK boundary: for each recorded inverse the
            ;; conflict-aware rule restores the truthful `:before` (an unmoved
            ;; `:revision`) or, on a CONFLICT (a competing authoritative write
            ;; bumped the entry's `:revision` since the apply), defers to
            ;; `:on-conflict` — `:invalidate` (default) marks the moved entry
            ;; stale + refetches the authoritative value (never resurrecting the
            ;; stale inverse), `:force` restores the inverse anyway (single-writer
            ;; last-write-wins, with the tooling warning below). Runs BEFORE the
            ;; failure-time invalidation so that pass reads the ROLLED-BACK cache.
            ;; A purely-pessimistic mutation (no recorded inverse) skips this.
            rollback-inverse (recorded-rollback inst)
            on-conflict      (mstate/on-conflict-policy spec)
            rolled  (when rollback-inverse
                      (settle-optimistic-rollback rollback-inverse runtime-db on-conflict cause))
            ;; the cache the failure-time invalidation + the final instance write
            ;; settle against — the rolled-back cache when an optimistic apply
            ;; existed, else the unchanged reply cache.
            settle-db (or (:runtime-db rolled) runtime-db)
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
            inv-fxs  (when inv-plan (plan->fx inv-plan cause #{}))
            inv-tags (when inv-plan (plan-tags inv-plan))
            ;; rf2-fi6tda.2 — an `:after-failure` invalidation marks tagged
            ;; keys stale; those keys ARE affected and must flow into
            ;; `:affected-keys` (the failure path previously recorded an empty
            ;; vector). Computed against the ROLLED-BACK settle-time entries via
            ;; the SHARED match the dispatched invalidate-tags will use.
            invalidated-ks (when inv-plan
                             (vec (:invalidated-keys (plan-key-evidence inv-plan #{} settle-db))))
            ;; EP-0019 — the keys the rollback refetched on conflict ride the
            ;; instance row's reserved `:reconciliation-refetches` slot + flow
            ;; into the reply `:affected-keys` (a conflict-invalidated key IS
            ;; affected). The restored keys are also affected.
            refetched-ks (vec (:refetched-keys rolled))
            restored-ks  (vec (:restored-keys rolled))
            affected (vec (distinct (concat invalidated-ks restored-ks refetched-ks)))
            ;; EP-0019 — fill the reserved `:patch-summary` slots on the failed
            ;; instance row so Xray's mutation view can show that the optimistic
            ;; apply rolled back, per-key restored-vs-conflict, and which keys
            ;; refetched. nil when this mutation ran no optimistic apply.
            rolled-summary (when rolled
                             (assoc (:patch-summary inst)
                                    :rollback                 (rollback-trace-dispositions
                                                                (:dispositions rolled) on-conflict)
                                    :reconciliation-refetches refetched-ks))
            inst'    (cond-> (mstate/instance-failed
                               inst {:error error :settled-at clock-ms
                                     :affected-keys affected})
                       rolled-summary (assoc :patch-summary rolled-summary))
            rdb'     (-> settle-db
                         (assoc-in (mstate/instance-path instance-id) inst')
                         ;; rf2-qsn30x: the ledger row settles `:cancelled` for
                         ;; an accepted abort (the reply lowered it to
                         ;; `:status :cancelled`), else `:failed`. The outcome
                         ;; summary mirrors the resource abort path
                         ;; (`{:reason :aborted …}` carries no error envelope —
                         ;; an abort is not a user-visible failure) so the
                         ;; ledger status and the canonical reply `:work/status`
                         ;; agree (Managed-Effects §Status taxonomy).
                         (work-ledger/update-record
                           work-id work-ledger/mark-terminal
                           (:work/status reply)
                           (if aborted?
                             {:reason :aborted :completed-at completed-at}
                             {:error error})))
            ;; PHASE 6 — the continuation fires for ANY accepted terminal reply
            ;; (D1 delivery rule: keyed on acceptance, not a status
            ;; enumeration), so an accepted `:error` (and an accepted terminal
            ;; `:cancelled`) reply dispatches `:reply-to` too — the handler folds
            ;; validation errors / form state / notifications off the reply
            ;; `:status`. A failed write applies no patch/populate/remove (no
            ;; result), so the ONLY affected keys are those an `:after-failure`
            ;; invalidation marks stale (rf2-fi6tda.2 — previously dropped to
            ;; `#{}`). Dispatched LAST, after the optional failure-time
            ;; invalidation it composes with.
            ;; PURE — the `:rf.mutation/replied` trace is emitted below, AFTER
            ;; `:rf.mutation/failed` (rf2-ru73k6 F2).
            cont-fx  (continuation-fx
                       reply-to
                       (continuation-reply
                         reply {:mutation-id mutation-id :params params
                                :instance-id instance-id :scope scope
                                :affected-keys affected}))]
        (work-ledger/clear-handle! frame-id work-id)
        ;; EP-0019 — the optimistic rollback trace (phase 4, error/cancel).
        ;; Carries the snapshot id, per-key restored-vs-conflict disposition (the
        ;; `:on-conflict` rule applied on a conflict), and the refetched keys.
        ;; Emitted only when this mutation ran an optimistic apply.
        (when rolled
          (trace/emit! :rf.event :rf.mutation/optimistic-rolled-back
                       {:rf.frame/id frame-id :instance instance-id :mutation mutation-id
                        :work/id work-id :generation generation
                        :snapshot-id (-> inst :patch-summary :snapshot-id)
                        :on-conflict on-conflict
                        :dispositions (rollback-trace-dispositions
                                        (:dispositions rolled) on-conflict)
                        :restored restored-ks
                        :conflicted (vec (:conflicted-keys rolled))
                        :refetched refetched-ks
                        :cause cause})
          ;; `:force` on a CONFLICTED key restored a STALE inverse over a
          ;; concurrent authoritative write — the deliberate single-writer
          ;; escape, but a tooling warning so an unexpected clobber is loud.
          (when (and (= :force on-conflict) (seq (:conflicted-keys rolled)))
            (trace/emit! :warning :rf.warning/optimistic-force-clobber
                         {:rf.frame/id frame-id :instance instance-id :mutation mutation-id
                          :forced-keys (vec (:conflicted-keys rolled))
                          :recovery :review-on-conflict
                          :reason (str "mutation " (pr-str mutation-id)
                                       " rolled back with :on-conflict :force over "
                                       (count (:conflicted-keys rolled))
                                       " entry(ies) whose :revision MOVED since the "
                                       "optimistic apply — :force restored the recorded "
                                       "(now-stale) :before, CLOBBERING a concurrent "
                                       "authoritative write. :force is the single-writer "
                                       "last-write-wins escape; if these entries can be "
                                       "written concurrently, use the :invalidate default "
                                       "(refetch the authoritative value on conflict). Per "
                                       "Spec 016 §Optimistic settle / EP-0019 Decision 3.")})))
        (trace/emit! :rf.event :rf.mutation/failed
                     (cond-> {:rf.frame/id frame-id :instance instance-id :mutation mutation-id
                              :work/id work-id :generation generation :error error
                              :affected-keys affected
                              :invalidated-tags (vec (or inv-tags #{}))}
                       ;; EP-0016 D2: descriptor-level failure-invalidation
                       ;; evidence rides the failed-settlement trace (no
                       ;; populated keys on a failure, so the Rider 1 exempt
                       ;; set is empty).
                       inv-plan (assoc :invalidation (plan-trace inv-plan #{} settle-db))))
        ;; DEV-ONLY write-side scope-mismatch tripwire (rf2-byl7bk.4) — also fires
        ;; on the `:after-failure` / `:after-settle` invalidation timing (a write
        ;; that invalidates on failure to force a re-read of authoritative server
        ;; state can mis-scope exactly as a success-time one). Computed against
        ;; the ROLLED-BACK settle-time entries the dispatched invalidate-tags see.
        (maybe-warn-scope-mismatch!
          inv-plan {:frame-id frame-id :mutation-id mutation-id :instance-id instance-id
                    :mut-scope scope :entries (get-in settle-db (state/entries-path))})
        ;; PHASE 6 evidence — emitted AFTER `:rf.mutation/failed` so the
        ;; `:rf.mutation/replied` row truthfully follows settlement in the phase
        ;; order (rf2-ru73k6 F2). No-op when no `:reply-to` continued.
        (emit-replied! cont-fx
                       {:frame-id frame-id :mutation-id mutation-id
                        :instance-id instance-id :work-id work-id
                        :status (:status reply) :reply-to reply-to})
        {:rf.db/runtime rdb'
         ;; EP-0019: the conflict-rollback invalidations (the `:invalidate`
         ;; disposition's refetch dispatches) run alongside any failure-time
         ;; invalidation, before the continuation.
         :fx (cond-> []
               (seq (:invalidate-fx rolled)) (into (:invalidate-fx rolled))
               inv-fxs (into inv-fxs)
               cont-fx (conj cont-fx))}))))
