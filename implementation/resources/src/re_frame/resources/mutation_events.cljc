(ns re-frame.resources.mutation-events
  "The mutation event handlers: the causal write surface over managed HTTP,
  with controlled cache consequences and optional optimistic application.

    [:rf.mutation/execute {:mutation … :params … :instance … :scope … :cause …}]
    [:rf.mutation/clear   {:instance …}]   ;; causal instance reset (NOT a form-error reset)

  The framework-internal `:rf.mutation.internal/*` replies carry the
  verification payload and receive the canonical reply map built by
  `re-frame.resources.reply` with `:work/kind :mutation`; user code MUST NOT
  dispatch them. A reply is verified against the live instance's frame,
  work-id and generation before it writes, so a superseded or cleared reply
  never touches a newer instance. Every handler carries framework-write
  authority; the registrations live in the `re-frame.resources` façade.
  Spec 016 §Mutations (first public-beta gate)."
  (:require [clojure.set :as set]
            [re-frame.interop :as rf.interop]
            [re-frame.reply :as rf.reply]
            [re-frame.resources.classification :as rf.resources.classification]
            [re-frame.resources.events :as rf.resources.events]
            [re-frame.resources.mutation-registry :as rf.resources.mutation-registry]
            [re-frame.resources.mutation-runtime :as rf.resources.mutation-runtime]
            [re-frame.resources.registry :as rf.resources.registry]
            [re-frame.resources.reply :as rf.resources.reply]
            [re-frame.resources.reply-handlers :as rf.resources.reply-handlers]
            [re-frame.resources.scope-registry :as rf.resources.scope-registry]
            [re-frame.resources.state :as rf.resources.state]
            [re-frame.resources.transport :as rf.resources.transport]
            [re-frame.resources.transport.http :as rf.resources.transport.http]
            [re-frame.resources.work-ledger :as rf.resources.work-ledger]
            [re-frame.trace :as rf.trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- durable timestamps ---------------------------------------------------
;;
;; Every durable timestamp is a recordable coeffect: `:started-at` is the
;; execute token's `:rf/time-ms`, and `:settled-at` plus any patch / populate
;; `:loaded-at` is the reply token's completion time. No ambient clock is read
;; at a durable write site. The stale / timer helpers live in `state.cljc`,
;; shared with the read path so a patched entry ages exactly as a fetched one.

(defn- timer-delays
  "The advisory stale / GC timer delays from the resource spec's policy, or
  nil when it declares neither (no `:rf.resource/schedule-timers` fx)."
  [resource-spec]
  (let [stale-delay-ms (rf.resources.state/positive-or-nil (:stale-after-ms resource-spec))
        gc-delay-ms    (rf.resources.state/positive-or-nil (:gc-after-ms resource-spec))]
    (when (or stale-delay-ms gc-delay-ms)
      {:stale-delay-ms stale-delay-ms :gc-delay-ms gc-delay-ms})))

(defn- open-gc-windows
  "`runtime-db` with a new GC window on each still-present entry whose GC
  timer the settle arms, as the read path does, so an older queued GC check
  never collects an entry the settle just wrote."
  [runtime-db frame-id timer-policies]
  (reduce (fn [db [scoped-key {:keys [gc-delay-ms]}]]
            (cond-> db
              (get-in db (rf.resources.state/entry-path scoped-key))
              (rf.resources.events/open-gc-window frame-id scoped-key gc-delay-ms)))
          runtime-db
          timer-policies))

;; ---- instance-id minting --------------------------------------------------

(defn- mint-instance-id
  "The caller-supplied `:instance`, else an id derived from the mutation id
  and the recorded generation. Pure, so it reproduces on replay, and two
  concurrent submissions get different ids."
  [mutation-id supplied-instance generation]
  (or supplied-instance
      [:rf.mutation/instance mutation-id generation]))

(defn- retire-fx
  "The `:rf.mutation/clear` fx that retires a generated-id instance once its
  reply is accepted. The caller never held the id, so nothing else could
  clear the row. Placed after the continuation so a `:reply-to` handler
  still sees the settled row. Spec 016 §Phase order (normative)."
  [instance-id]
  [:dispatch [:rf.mutation/clear {:instance instance-id}]])

;; ---- exact-target scope resolution ----------------------------------------

(defn- resolve-exact-target-scope
  "Resolves a map-form target's declared `:scope` (default `:rf.scope/same`)
  to `[:resolved <scope>]`, or `[:nil-resolved <from-db-id>]` when a
  `{:from-db …}` reference resolves nil (the target is dropped, never written
  under an implicit global). A reference reads `from-db-scopes`, the
  mutation's issuance context. Spec 016 §Map-form exact resource targets."
  [target mut-scope from-db-scopes where]
  (let [scope (rf.resources.mutation-runtime/target-scope target)]
    (cond
      (= scope rf.resources.mutation-runtime/same-scope-marker)
      [:resolved mut-scope]

      (rf.resources.scope-registry/from-db-reference? scope)
      (if-let [s (rf.resources.scope-registry/resolve-from-db-scopes scope from-db-scopes where)]
        [:resolved (rf.resources.state/canonicalize-scope s where nil)]
        [:nil-resolved (:from-db scope)])

      :else
      [:resolved (rf.resources.state/canonicalize-scope scope where nil)])))

;; ---- the success-time patch / populate / invalidate composition -----------

(defn- apply-patches
  "Applies `:patches` (`(fn [params result] -> {target patch-fn})`) to entries
  that already hold data; a key with no data is a no-op. Returns
  `[runtime-db' affected-keys policies nil-resolved-ids skipped-targets]`.
  This runs after the server write, so a recoverable bad target is dropped
  and collected rather than stranding the mutation; cache-identity
  corruption still throws. Spec 016 §Map-form exact resource targets."
  [runtime-db patches-fn params result clock-ms mut-scope from-db-scopes where]
  (let [[patches nil-ids skipped]
        (when patches-fn
          (rf.resources.mutation-runtime/validate-target-map!
            (patches-fn params result)
            #(resolve-exact-target-scope % mut-scope from-db-scopes where)
            rf.resources.registry/resource-meta :patches where :skip-recoverable))]
    (if (seq patches)
      (reduce-kv
        (fn [[db' ks policies nils sk] scoped-key patch-fn]
          (let [entry (get-in db' (rf.resources.state/entry-path scoped-key))]
            (if (and entry (rf.resources.state/has-data? entry))
              (let [rspec    (rf.resources.registry/resource-meta (:resource/id entry))
                    stale-at (rf.resources.state/stale-at-for rspec clock-ms)
                    entry'   (rf.resources.mutation-runtime/patch-entry entry patch-fn result
                                                 {:clock-ms clock-ms :stale-at stale-at})
                    delays   (timer-delays rspec)]
                [(assoc-in db' (rf.resources.state/entry-path scoped-key) entry')
                 (conj ks scoped-key)
                 (cond-> policies delays (assoc scoped-key delays))
                 nils sk])
              [db' ks policies nils sk])))
        [runtime-db #{} {} (vec nil-ids) (vec skipped)]
        patches)
      [runtime-db #{} {} (vec nil-ids) (vec skipped)])))

(defn- apply-populates
  "Seeds each `:populates` target (`(fn [params result] -> {target value})`)
  `:loaded`, tagged by its resource's `:tags` fn so a later invalidation can
  reach it. Returns and drops targets as `apply-patches` does.
  Spec 016 §Populate is an authoritative load."
  [runtime-db populates-fn params result clock-ms mut-scope from-db-scopes where]
  (let [[populates nil-ids skipped]
        (when populates-fn
          (rf.resources.mutation-runtime/validate-target-map!
            (populates-fn params result)
            #(resolve-exact-target-scope % mut-scope from-db-scopes where)
            rf.resources.registry/resource-meta :populates where :skip-recoverable))]
    (if (seq populates)
      (reduce-kv
        (fn [[db' ks policies nils sk] scoped-key value]
          (let [[_scope resource-id rparams] scoped-key
                rspec    (rf.resources.registry/resource-meta resource-id)
                stale-at (rf.resources.state/stale-at-for rspec clock-ms)
                tags-fn  (:tags rspec)
                tags     (when tags-fn (set (tags-fn rparams value)))
                entry    (get-in db' (rf.resources.state/entry-path scoped-key))
                entry'   (rf.resources.mutation-runtime/populate-entry entry resource-id value
                                                {:clock-ms clock-ms :stale-at stale-at :tags tags
                                                 :scoped-key scoped-key})
                delays   (timer-delays rspec)]
            [(assoc-in db' (rf.resources.state/entry-path scoped-key) entry')
             (conj ks scoped-key)
             (cond-> policies delays (assoc scoped-key delays))
             nils sk]))
        [runtime-db #{} {} (vec nil-ids) (vec skipped)]
        populates)
      [runtime-db #{} {} (vec nil-ids) (vec skipped)])))

(defn- supersede-reads-under-writes
  "Settles terminal `:suppressed` the in-flight read on each key a patch or
  populate wrote, as a forced refetch does. Without this, a GET the server
  answered before it committed the write would overwrite the written value,
  stamped fresh. Returns `[runtime-db' [[work-id transport] …]]`; `pre-db` is
  the cache before the writes. Spec 016 §Race and in-flight semantics."
  [pre-db runtime-db written-keys by-work-id]
  (reduce
    (fn [[rdb superseded] scoped-key]
      (let [wid    (:current-work (get-in pre-db (rf.resources.state/entry-path scoped-key)))
            record (when wid (rf.resources.work-ledger/get-record rdb wid))]
        (if (and record (not (rf.resources.work-ledger/terminal? (:status record))))
          [(rf.resources.work-ledger/settle-terminal
             rdb wid :suppressed {:reason :superseded :by by-work-id})
           (conj superseded [wid (:transport record)])]
          [rdb superseded])))
    [runtime-db []]
    written-keys))

(defn- apply-removes
  "Dissocs each `:removes` target (`(fn [params result] -> target-or-targets)`)
  from the cache by byte `key-id` (a dissoc by the scoped-key vector would
  leak the entry), settles its in-flight work `:cancelled` and drops its
  ledger rows, as `:rf.resource/remove` does. Returns
  `[runtime-db' removed-keys removed-work nil-resolved-ids skipped-targets]`
  and drops targets as `apply-patches` does.
  Spec 016 §Map-form exact resource targets."
  [runtime-db removes-fn params result clock-ms mut-scope from-db-scopes where]
  (let [targets (when removes-fn
                  (let [raw (removes-fn params result)]
                    ;; validate-target-map! threads values; a remove has none.
                    (cond
                      (nil? raw)                 nil
                      (rf.resources.mutation-runtime/target-map? raw)   {raw ::remove}
                      :else                      (into {} (map (fn [t] [t ::remove])) raw))))
        [resolved nil-ids skipped]
        (when (seq targets)
          (rf.resources.mutation-runtime/validate-target-map!
            targets
            #(resolve-exact-target-scope % mut-scope from-db-scopes where)
            rf.resources.registry/resource-meta :removes where :skip-recoverable))]
    (if (seq resolved)
      (reduce-kv
        (fn [[db' ks work nils sk] scoped-key _placeholder]
          (let [k-id  (rf.resources.state/key-id scoped-key)
                entry (get-in db' (rf.resources.state/entry-path scoped-key))]
            (if entry
              (let [wid       (:current-work entry)
                    transport (when wid (:transport (rf.resources.work-ledger/get-record db' wid)))]
                [(-> db'
                     (update-in (rf.resources.state/entries-path) dissoc k-id)
                     (cond-> wid (rf.resources.work-ledger/update-record
                                   wid rf.resources.work-ledger/mark-terminal
                                   ;; A cancellation is a completion: it carries
                                   ;; the settling reply's clock, never an
                                   ;; ambient read.
                                   :cancelled {:reason :mutation-remove
                                               :completed-at clock-ms}))
                     ;; Spec 016 §Ledger row retention and identity.
                     (rf.resources.work-ledger/drop-rows-for-key scoped-key))
                 (conj ks scoped-key)
                 (cond-> work wid (conj [wid transport]))
                 nils sk])
              [db' ks work nils sk])))
        [runtime-db #{} [] (vec nil-ids) (vec skipped)]
        resolved)
      [runtime-db #{} [] (vec nil-ids) (vec skipped)])))

;; ---- optimistic apply (phase 1.5) -----------------------------------------
;;
;; Before the request lowers, each touched entry is snapshotted (the truthful
;; inverse plus its `:revision`), the forward patch applied, and the inverse
;; recorded on the instance's `:patch-summary` `:rollback` slot for the settle
;; step. `:optimistic` shares `:patches`' exact-target boundary;
;; `:optimistic-tags` shares the invalidation tag index. Both fail closed on a
;; nil-resolving scope, because an optimistic apply writes the cache.
;; Spec 016 §Optimistic mutations.

(declare resolve-descriptor-scope)

(defn- mint-snapshot-id
  "The optimistic `:snapshot-id` for one apply, derived from the instance id
  and generation so it reproduces on replay. A re-execute mints a new one."
  [instance-id generation]
  [:rf.mutation/snapshot instance-id generation])

(defn- optimistic-exact-targets
  "Resolves the `:optimistic` plan `(fn [params] -> {target patch-fn})` to
  `[{scoped-key patch-fn-or-nil} nil-resolved-ids]` through the same
  fail-closed boundary `:patches` uses, or `[nil []]` when there is none. A
  nil patch-fn is an optimistic remove."
  [optimistic-fn params mut-scope from-db-scopes where]
  (if-not optimistic-fn
    [nil []]
    (rf.resources.mutation-runtime/validate-target-map!
      (optimistic-fn params)
      #(resolve-exact-target-scope % mut-scope from-db-scopes where)
      rf.resources.registry/resource-meta :optimistic where)))

(defn- warn-optimistic-tag-descriptor-skipped!
  "Dev-only `:rf.warning/optimistic-tags-descriptor-skipped`, elided in
  production behind `rf.interop/debug-enabled?`. Returns nil."
  [{:keys [frame-id mutation]} reason detail]
  (when rf.interop/debug-enabled?
    (rf.trace/emit! :warning :rf.warning/optimistic-tags-descriptor-skipped
                 (merge {:rf.frame/id frame-id
                         :mutation    mutation
                         :recovery    :fix-descriptor
                         :reason      reason}
                        detail))))

(defn- normalize-optimistic-tag-descriptors
  "The `:optimistic-tags` result (one descriptor or a vector) as a vector of
  `{:scope … :tags #{…} :patch fn}`, `:scope` defaulting to `:rf.scope/same`.
  A malformed descriptor is warned and skipped, not thrown: this runs before
  the request lowers, so a throw would stop the authoritative write, and the
  optimistic paint is reversible. `ctx` is `{:frame-id … :mutation …}`.
  Spec 016 §Optimistic mutations."
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
                  {:scope (if (contains? descriptor :scope) scope rf.resources.mutation-runtime/same-scope-marker)
                   :tags  (rf.resources.state/normalize-tag-set tags)
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
  "Resolves each descriptor's scope (fail-closed on nil) and maps every cache
  entry carrying its tags in that scope to its `:patch` fn, using the same
  matcher as invalidation; a later descriptor wins on a shared key. Returns
  `[{scoped-key patch-fn} nil-resolved-ids]`. There is no cross-scope form.
  Spec 016 §Optimistic writes are fail-closed and scope-bounded."
  [descriptors entries mut-scope from-db-scopes where]
  (reduce
    (fn [[m nils] {:keys [scope tags patch]}]
      (let [[outcome scope-or-id] (resolve-descriptor-scope
                                    {:scope scope} mut-scope from-db-scopes where)]
        (case outcome
          :nil-resolved [m (conj nils scope-or-id)]
          ;; cross-scope is unreachable (a descriptor has no :cross-scope? here)
          :cross-scope  [m nils]
          :resolved
          (let [{:keys [matched]} (rf.resources.events/match-invalidation-keys
                                    entries scope-or-id false (set tags) #{})]
            [(reduce (fn [m' sk] (assoc m' sk patch)) m matched) nils]))))
    [{} []]
    descriptors))

(defn- forward-summary
  "The `:forward` op recorded for the trace: `:remove` for a nil patch-fn,
  `:seed` over an absent entry, else `:patch`."
  [patch-fn before-entry]
  (cond
    (nil? patch-fn)                               :remove
    (= before-entry rf.resources.mutation-runtime/absent-snapshot)       :seed
    :else                                         :patch))

(defn- apply-optimistic
  "Applies `{scoped-key patch-fn-or-nil}` at execute time: snapshots each
  entry, then tombstones it (nil patch-fn) or applies the forward patch and
  bumps its revision. Returns `[runtime-db' affected-keys inverse-vec]`; the
  caller reindexes. Every form leaves a concrete revision behind, so the
  settle-time conflict check is one uniform comparison.
  Spec 016 §Optimistic mutations."
  [runtime-db target-map clock-ms]
  (reduce-kv
    (fn [[db' ks inverses] scoped-key patch-fn]
      (let [entry  (get-in db' (rf.resources.state/entry-path scoped-key))
            before (rf.resources.mutation-runtime/snapshot-entry entry)
            fwd    (forward-summary patch-fn before)
            inverse (rf.resources.mutation-runtime/record-optimistic-entry scoped-key before fwd)
            db''   (if (nil? patch-fn)
                     ;; A tombstone in place, not a dissoc: a departed entry
                     ;; takes with it the owner facts the settle needs, and a
                     ;; failed reply would then resurrect a released owner
                     ;; onto the restored entry, pinning it for the frame's
                     ;; life. An absent key has nothing to tombstone.
                     (if (some? entry)
                       (assoc-in db' (rf.resources.state/entry-path scoped-key)
                                 (rf.resources.mutation-runtime/apply-optimistic-remove entry))
                       db')
                     (let [[_scope resource-id _p] scoped-key
                           rspec    (rf.resources.registry/resource-meta resource-id)
                           stale-at (rf.resources.state/stale-at-for rspec clock-ms)
                           tags-fn  (:tags rspec)
                           ;; A seeded entry needs its resource's tags; an
                           ;; existing entry keeps its own.
                           [_s _r rparams] scoped-key
                           tags     (when tags-fn (set (tags-fn rparams nil)))
                           entry'   (rf.resources.mutation-runtime/apply-optimistic-patch
                                      entry patch-fn resource-id
                                      {:clock-ms clock-ms :stale-at stale-at
                                       :tags tags :scoped-key scoped-key})]
                       (assoc-in db' (rf.resources.state/entry-path scoped-key) entry')))]
        [db'' (conj ks scoped-key) (conj inverses inverse)]))
    [runtime-db #{} []]
    target-map))

;; ---- the settle protocol (phase 4) ----------------------------------------
;;
;; Success commits the apply (the authoritative writes overwrite it and the
;; inverse is discarded). Failure, an accepted cancel or a restore dangle rolls
;; it back: an unmoved revision restores `:before`; a moved one defers to
;; `:on-conflict`. A stale reply writes nothing, but its superseded apply is
;; not forgotten: a re-execute or `:rf.mutation/clear` rolls it back, and rows
;; from such an abandoned attempt carry `:revalidate? true` because the write
;; may still have reached the server.
;; Spec 016 §Optimistic settle — commit / rollback / reconcile.

(defn- recorded-rollback
  "The recorded inverse rows on the live instance's `:patch-summary`
  `:rollback` slot, or nil when no optimistic apply ran."
  [inst]
  (-> inst :patch-summary :rollback seq))

(defn- conflicted-key-refetch-fx
  "The exact `:rf.resource/refetch` fx for one staled key when its entry has
  active owners, else nil (an owner-free entry stays stale for its next
  ensure). Addressed by the exact key, never through `:tags`, which are
  optional and must not gate recovery."
  [runtime-db scoped-key cause]
  (let [entry (get-in runtime-db (rf.resources.state/entry-path scoped-key))
        [scope resource-id params] scoped-key]
    (when (and entry (seq (:active-owners entry)))
      [:dispatch [:rf.resource/refetch
                  {:resource resource-id :scope scope :params params
                   :cause cause}]])))

(defn- settle-optimistic-rollback
  "Rolls back the recorded `inverse` rows against the live cache: each row
  restores `:before`, or on a conflict under `:invalidate` keeps the moved
  entry, marks it stale at its exact key and refetches it when owned. A row
  with `:revalidate? true` is restored and then staled, in that order, so a
  still-pending earlier apply sees the mark as a conflict. `clock-ms` is the
  reply's completion time. Returns `{:runtime-db :dispositions :recovery-fx
  :restored-keys :conflicted-keys :staled-keys :refetched-keys}`;
  `:staled-keys` are affected keys whether refetched or not.
  Spec 016 §Optimistic settle — commit / rollback / reconcile."
  [inverse runtime-db on-conflict cause clock-ms]
  (let [dispositions (mapv (fn [{scoped-key :resource/key :as recorded}]
                             (cond-> (rf.resources.mutation-runtime/rollback-entry-disposition
                                       (get-in runtime-db (rf.resources.state/entry-path scoped-key))
                                       recorded on-conflict)
                               (:revalidate? recorded) (assoc :revalidate? true)))
                           inverse)
        restored?    #(= :restore (:disposition %))
        ;; Only the inverse's keys change, so reindex that bounded set.
        old-entries  (get-in runtime-db (rf.resources.state/entries-path))
        changed-ids  (mapv (fn [{scoped-key :resource/key}] (rf.resources.state/key-id scoped-key))
                           inverse)
        [rdb' staled-ks]
                     (reduce (fn [[rdb staled] {scoped-key :resource/key :as disp}]
                               (case (:disposition disp)
                                 :restore    (let [rdb1 (rf.resources.mutation-runtime/restore-before rdb disp)
                                                   path (rf.resources.state/entry-path scoped-key)]
                                               (if (and (:revalidate? disp)
                                                        (not= (:before disp)
                                                              rf.resources.mutation-runtime/absent-snapshot)
                                                        (get-in rdb1 path))
                                                 [(update-in rdb1 path rf.resources.state/entry-invalidate clock-ms)
                                                  (conj staled scoped-key)]
                                                 [rdb1 staled]))
                                 :invalidate (if (get-in rdb (rf.resources.state/entry-path scoped-key))
                                               [(update-in rdb (rf.resources.state/entry-path scoped-key)
                                                           rf.resources.state/entry-invalidate clock-ms)
                                                (conj staled scoped-key)]
                                               [rdb staled])))
                             [runtime-db []] dispositions)
        rdb''        (update rdb' rf.resources.state/resources-key rf.resources.state/reindex-keys
                             old-entries changed-ids)
        ;; Decided against the rolled-back cache, so it reads the entries
        ;; that stayed.
        recoveries   (into []
                           (keep (fn [scoped-key]
                                   (when-let [fx (conflicted-key-refetch-fx rdb'' scoped-key cause)]
                                     [scoped-key fx])))
                           staled-ks)]
    {:runtime-db      rdb''
     :dispositions    dispositions
     :recovery-fx     (mapv second recoveries)
     :restored-keys   (into [] (comp (filter restored?) (map :resource/key)) dispositions)
     :conflicted-keys (into [] (comp (filter :conflict?) (map :resource/key)) dispositions)
     :staled-keys     staled-ks
     :refetched-keys  (mapv first recoveries)}))

(defn- rollback-trace-dispositions
  "The per-key `{:resource/key :restored :conflict :on-conflict}` rows for the
  `:rf.mutation/optimistic-rolled-back` trace."
  [dispositions on-conflict]
  (mapv (fn [{scoped-key :resource/key :keys [disposition conflict?]}]
          (cond-> {:resource/key scoped-key
                   :restored     (= :restore disposition)
                   :conflict     (boolean conflict?)}
            conflict? (assoc :on-conflict on-conflict)))
        dispositions))

(defn- abandon-optimistic-apply
  "Rolls back `rows` of a pending apply the runtime abandons itself (a
  same-instance re-execute or `:rf.mutation/clear`). Its reply will be
  discarded and the write may still have landed, so every row carries the
  revalidate obligation. Returns the `settle-optimistic-rollback` result plus
  `:on-conflict` and `:cause`."
  [runtime-db inst rows clock-ms]
  (let [mutation-id (:mutation/id inst)
        on-conflict (rf.resources.mutation-runtime/on-conflict-policy
                      (rf.resources.mutation-registry/mutation-meta mutation-id))
        cause       [:mutation mutation-id (:instance/id inst)]]
    (assoc (settle-optimistic-rollback
             (mapv #(assoc % :revalidate? true) rows)
             runtime-db on-conflict cause clock-ms)
           :on-conflict on-conflict
           :cause cause)))

(defn- emit-abandoned-rollback!
  "Emits `:rf.mutation/optimistic-rolled-back` for an abandoned apply,
  carrying its own `:snapshot-id` so tooling joins the rollback to it."
  [frame-id inst {:keys [dispositions on-conflict cause] :as rolled}]
  (rf.trace/emit! :rf.event :rf.mutation/optimistic-rolled-back
                  {:rf.frame/id frame-id :instance (:instance/id inst)
                   :mutation (:mutation/id inst)
                   :work/id (:current-work inst) :generation (:generation inst)
                   :snapshot-id (-> inst :patch-summary :snapshot-id)
                   :on-conflict on-conflict
                   :dispositions (rollback-trace-dispositions dispositions on-conflict)
                   :restored (vec (:restored-keys rolled))
                   :conflicted (vec (:conflicted-keys rolled))
                   :refetched (vec (:refetched-keys rolled))
                   :cause cause}))

;; ---- scoped invalidation descriptors --------------------------------------
;;
;; `:invalidates` lowers to one descriptor vector; each descriptor resolves its
;; own scope and dispatches the single `:rf.resource/invalidate-tags` engine
;; once. Spec 016 §Scoped invalidation descriptors (per-target).

(defn- resolve-descriptor-scope
  "Resolves one descriptor's scope: `[:resolved <scope>]` (`:rf.scope/same` →
  `mut-scope`; concrete canonicalized; `{:from-db …}` from `from-db-scopes`,
  the mutation's issuance context), `[:cross-scope]` for a `:cross-scope?
  true` descriptor, or `[:nil-resolved <from-db-id>]`, which produces no
  invalidation rather than a global one.
  Spec 016 §Scoped invalidation descriptors (per-target)."
  [{:keys [scope cross-scope?]} mut-scope from-db-scopes where]
  (cond
    cross-scope?                                [:cross-scope]
    (= scope rf.resources.mutation-runtime/same-scope-marker)          [:resolved mut-scope]
    (rf.resources.scope-registry/from-db-reference? scope)   (if-let [s (rf.resources.scope-registry/resolve-from-db-scopes scope from-db-scopes where)]
                                                  [:resolved (rf.resources.state/canonicalize-scope s where nil)]
                                                  [:nil-resolved (:from-db scope)])
    :else                                       [:resolved (rf.resources.state/canonicalize-scope scope where nil)]))

(defn- invalidation-plan
  "The plan for `:invalidates` (`(fn [params result] -> tags | descriptor |
  descriptors)`): `{:dispatches [<resolved-descriptor> …] :unresolved
  [<from-db-id> …] :descriptor-count n}`, or nil when the mutation has none.
  Nil-resolving descriptors land in `:unresolved` and dispatch nothing.
  Spec 016 §Cache-consequence callback signatures."
  [invalidates-fn params result mut-scope from-db-scopes where]
  (when invalidates-fn
    (let [descriptors (rf.resources.mutation-runtime/normalize-invalidation-descriptors
                        (invalidates-fn params result) where)]
      (reduce
        (fn [plan {:keys [tags refetch-populated?] :as descriptor}]
          (if-not (seq tags)
            plan
            (let [[outcome scope-or-id] (resolve-descriptor-scope
                                          descriptor mut-scope from-db-scopes where)]
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
  "One `:rf.resource/invalidate-tags` dispatch per planned descriptor, or nil
  when there are none. The keys this mutation just populated ride as
  `:exempt-keys` unless the descriptor sets `:refetch-populated? true`.
  Spec 016 §Populate is an authoritative load."
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
  "The union of every dispatched descriptor's tags."
  [plan]
  (into #{} (mapcat :tags) (:dispatches plan)))

(defn- plan-key-evidence
  "The keys the plan's dispatches will stale and spare, computed with the
  same matcher the engine uses so reply, trace and engine agree:
  `{:invalidated-keys <vec> :populate-exempt <vec> :per-descriptor [<vec> …]}`.
  `:invalidated-keys` feed `:affected-keys`; `:per-descriptor` holds each
  dispatch's spared populated keys, and `:populate-exempt` their union.
  Spec 016 §Trace evidence for invalidation."
  [plan populated-ks runtime-db]
  (let [populated-ids (into #{} (map rf.resources.state/key-id) populated-ks)
        entries       (get-in runtime-db (rf.resources.state/entries-path))
        sk-by-id      (into {} (map (fn [[k-id e]] [k-id (:resource/key e)])) entries)
        id->sk        (fn [ids] (into [] (keep sk-by-id) ids))
        per-descriptor
        (mapv (fn [{:keys [scope cross-scope? tags refetch-populated?]}]
                (let [tag-set (set tags)
                      exempt  (if refetch-populated? #{} populated-ids)
                      {stale :matched-ids} (rf.resources.events/match-invalidation-keys
                                             entries scope cross-scope? tag-set exempt)
                      spared  (if refetch-populated?
                                #{}
                                (let [{m :matched-ids} (rf.resources.events/match-invalidation-keys
                                                         entries scope cross-scope? tag-set #{})]
                                  (set/intersection (set m) populated-ids)))]
                  {:stale stale :exempt spared}))
              (:dispatches plan))
        stale-ids  (into #{} (mapcat :stale) per-descriptor)
        exempt-ids (into #{} (mapcat :exempt) per-descriptor)]
    {:invalidated-keys (id->sk stale-ids)
     :populate-exempt  (into [] (filter (fn [sk] (contains? exempt-ids (rf.resources.state/key-id sk)))) populated-ks)
     :per-descriptor   (mapv (comp id->sk :exempt) per-descriptor)}))

(defn- plan-trace
  "The descriptor-level invalidation facet of the settlement trace: count,
  each dispatched descriptor with its own `:exempt-keys`, the unresolved
  `{:from-db …}` ids and the `:populate-exempt` union. Nil for a nil plan.
  Spec 016 §Trace evidence for invalidation."
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

;; ---- dev-only write-side diagnostics --------------------------------------
;;
;; A mutation with no declared scope runs in `:rf.scope/global`, so a
;; same-scope invalidation of session- or tenant-scoped reads matches nothing
;; and silently misses. The tripwire warns when a descriptor matched nothing
;; in its scope while the same tags match in another; a tag with no entry
;; anywhere, or a `:cross-scope? true` descriptor, never warns. It is the
;; write-side peer of the subs' read-side scope-mismatch warning.
;; Spec 016 §Mutation scope is two distinct scopes (hybrid).

(defonce ^:private
  ^{:doc "Dedupe set of `[mutation-id descriptor-scope other-scope tags]`
   already warned, so a repeated mutation warns once. Host-side dev state,
   reset per test by the resources reset hook."}
  warned-mutation-scope-mismatch
  (atom #{}))

(defn reset-mutation-scope-mismatch-warnings!
  "Clears the scope-mismatch dedupe set (test isolation, via the resources
  reset hook; it is host state the runtime reset does not reach). Returns nil."
  []
  (reset! warned-mutation-scope-mismatch #{})
  nil)

(defonce ^:private
  ^{:doc "Dedupe set of `[mutation-id arm reason resource-id target]` already
   warned by `maybe-warn-target-skipped!`. Host-side dev state, reset per test
   by the resources reset hook."}
  warned-mutation-target-skipped
  (atom #{}))

(defn reset-mutation-target-skipped-warnings!
  "Clears the skipped-target dedupe set (test isolation). Returns nil."
  []
  (reset! warned-mutation-target-skipped #{})
  nil)

(defn maybe-warn-target-skipped!
  "Emits the dev-only `:rf.warning/mutation-target-skipped` once per distinct
  recoverable target a settle-time `:patches` / `:populates` / `:removes` arm
  dropped. Dropping beats stranding a committed write, but it must not be a
  silent swallow. `skipped` is the `[{:reason :target :resource} …]` vector
  from `validate-target-map!`. Elided in production. Returns nil."
  [skipped {:keys [frame-id mutation-id instance-id arm]}]
  (when (and rf.interop/debug-enabled? (seq skipped))
    (doseq [{:keys [reason target resource]} skipped]
      (let [dedupe-key [mutation-id arm reason resource target]]
        (when-not (contains? @warned-mutation-target-skipped dedupe-key)
          (swap! warned-mutation-target-skipped conj dedupe-key)
          (rf.trace/emit! :warning :rf.warning/mutation-target-skipped
                       {:rf.frame/id frame-id
                        :mutation    mutation-id
                        :instance    instance-id
                        :arm         arm
                        :reason      reason
                        :resource    resource
                        :target      target
                        :recovery    :fix-mutation-target
                        :hint        (str "mutation " (pr-str mutation-id) " " (name arm)
                                          " target " target " was SKIPPED at settle time ("
                                          (case reason
                                            :unregistered-resource
                                            (str "the resource " (pr-str resource)
                                                 " is UNREGISTERED — call rf/reg-resource first, "
                                                 "or the seeded / patched entry is unreachable by "
                                                 "any subscription")
                                            :non-keyword-resource
                                            "the target map's :resource must be a keyword"
                                            :non-map-target
                                            (str "the target must be the map-form exact target "
                                                 "{:resource <id> :params <params> :scope <scope>}")
                                            (str (name reason)))
                                          "). The server write already committed, so this "
                                          "recoverable target was DROPPED while the valid "
                                          "siblings in the same arm applied — fix the target so "
                                          "the cache consequence lands. Per Spec 016 §Map-form "
                                          "exact resource targets / EP-0003 §Mutations.")})))))
  nil)

(defn maybe-warn-scope-mismatch!
  "Emits the dev-only `:rf.warning/mutation-scope-mismatch` once per distinct
  `[mutation-id descriptor-scope other-scope tags]` for each dispatched,
  non-cross-scope descriptor that matched no entry in its scope while its
  tags match one elsewhere. Uses the engine's own matcher against the
  settle-time `entries`. Elided in production. Returns nil."
  [plan {:keys [frame-id mutation-id instance-id mut-scope entries]}]
  (when (and rf.interop/debug-enabled? plan)
    (doseq [{:keys [scope cross-scope? tags]} (:dispatches plan)]
      (when (and (not cross-scope?) (seq tags))
        (let [tag-set (set tags)
              {:keys [matched other-scope-hit?]}
              (rf.resources.events/match-invalidation-keys entries scope cross-scope? tag-set #{})]
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
                (rf.trace/emit! :warning :rf.warning/mutation-scope-mismatch
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

;; ---- mutation completion continuation (call-site :reply-to) ---------------
;;
;; An accepted reply dispatches the call-site `:reply-to` target with the
;; canonical reply map appended; a stale reply never fires it. The target is
;; an ordinary event, not a callback, and is data-only so it can ride the
;; verification payload. Spec 016 §Mutation completion continuations — call-site `:reply-to`.

(defn- continuation-reply
  "The canonical reply map plus the top-level mutation facts a continuation
  reads: `:mutation`, `:params`, `:instance`, `:scope`, `:affected-keys` (a
  set) and `:cause`. Pure."
  [reply {:keys [mutation-id params instance-id scope affected-keys]}]
  (assoc reply
         :mutation      mutation-id
         :params        params
         :instance      instance-id
         :scope         scope
         :affected-keys (set affected-keys)
         :cause         [:mutation mutation-id instance-id]))

(defn- continuation-fx
  "The `[:dispatch <completed-event>]` delivering `reply` to `reply-to` via
  the shared `re-frame.reply/complete` (`:append`), or nil without a target.
  Pure: `:rf.mutation/replied` is emitted after the settlement trace, to
  match the phase order. Spec 016 §Phase order (normative)."
  [reply-to reply]
  (when-let [completed (rf.reply/complete reply-to reply)]
    [:dispatch completed]))

(defn- emit-replied!
  "Emits `:rf.mutation/replied` when an accepted reply continued (non-nil
  `cont-fx`). Called after the `:rf.mutation/succeeded` / `failed` trace."
  [cont-fx {:keys [frame-id mutation-id instance-id work-id status reply-to]}]
  (when cont-fx
    (rf.trace/emit! :rf.event :rf.mutation/replied
                 {:rf.frame/id frame-id :mutation mutation-id :instance instance-id
                  :work/id work-id :status status :target reply-to
                  :cause [:mutation mutation-id instance-id]})))

;; ---- :rf.mutation/execute -------------------------------------------------

(defn execute-handler
  "`:rf.mutation/execute` with `{:mutation :params :instance :scope :cause
  :reply-to}`: mints the instance and its work-ledger row, applies any
  optimistic plan, and lowers `:request` through managed HTTP. `:scope` is a
  ScopeInput resolved against this event's app-db, which is also the
  mutation's issuance context: every `{:from-db …}` its consequences name
  resolves from it, at execute and at settle. Params, scope, instance id and
  `:reply-to` are all validated before anything is written. The
  generation is the recorded allocation, so ids and the stale verdict
  reproduce on replay. `{:optimistic? false}` opts one call out of the
  optimistic plan. Returns the effects map.
  Spec 016 §Mutations (first public-beta gate)."
  [{rt :rf.db/runtime, frame-id :rf.frame/id
    gen-allocation :rf.resource/generation-allocation
    time-ms :rf/time-ms, app-db :db}
   [_event-id {:keys [mutation instance scope cause reply-to] :as payload}]]
  (let [where      'rf.mutation/execute
        runtime-db (or rt {})
        spec       (rf.resources.mutation-registry/require-mutation-spec! mutation where)
        ;; Presence, not the value: an absent slot defaults to `{}`, an
        ;; explicit nil reaches `:params-schema`.
        cparams    (rf.resources.mutation-registry/validate+canonicalize-params
                     mutation spec (rf.resources.state/params-present? payload) where)
        cscope     (rf.resources.mutation-registry/resolve-scope mutation spec scope app-db)
        ;; The mutation's one context: every `{:from-db …}` a consequence
        ;; names resolves from it, here and, riding the reply payload, at
        ;; settle, so a reply never lands in another viewer's cache.
        from-db-scopes (rf.resources.scope-registry/resolve-all-pure app-db where)
        _          (rf.resources.mutation-runtime/validate-instance-id! instance where)
        ;; `:reply-to` rides the reply payload across the transport and is
        ;; dispatched later, so a malformed or host-handle target fails here,
        ;; before any write.
        reply-to'  (when (some? reply-to) (rf.reply/durable-target reply-to))
        generation (rf.resources.state/allocated-generation gen-allocation)
        instance-id (mint-instance-id mutation instance generation)
        ;; Keyed by instance id + generation, so a re-execute mints a new
        ;; work-id; a write has no params-keyed cache identity.
        work-id    (rf.resources.work-ledger/resource-work-id [:rf.mutation instance-id] generation)
        ;; Frame-qualified: two frames can hold the same work-id.
        request-id (rf.resources.work-ledger/managed-request-id frame-id work-id)
        started-at time-ms
        transport-id rf.resources.transport/managed-http-transport
        http-args  ((:request spec) cparams nil)
        invalidate-timing (or (:invalidate-timing spec) :after-success)
        before?    (= :before-request invalidate-timing)
        before-plan (when before?
                      (invalidation-plan (:invalidates spec) cparams nil cscope from-db-scopes where))
        ;; Nothing is populated before the request, so nothing is exempt.
        before-fxs  (when before-plan
                      (plan->fx before-plan [:mutation mutation instance-id] #{}))
        instance'  (rf.resources.mutation-runtime/empty-instance
                     mutation instance-id
                     {:scope cscope :params cparams :cause cause
                      :generation generation :work-id work-id :started-at started-at})
        record     (-> (rf.resources.work-ledger/work-record
                         {:work-id      work-id
                          :frame-id     frame-id
                          :resource/key [:rf.mutation instance-id]
                          :generation   generation
                          :transport    transport-id
                          :cause        cause
                          :started-at   started-at})
                       (assoc :work/kind :mutation))
        lower-fx   (rf.resources.transport.http/lower
                     {:http-args    http-args
                      :request-id   request-id
                      :on-success-id :rf.mutation.internal/succeeded
                      :on-failure-id :rf.mutation.internal/failed
                      :reply-payload (cond-> {:instance-id instance-id
                                              :mutation-id mutation
                                              :work/id     work-id
                                              :scope       cscope
                                              :generation  generation}
                                       (seq from-db-scopes) (assoc :from-db-scopes from-db-scopes)
                                       (some? reply-to')    (assoc :reply-to reply-to'))
                      :work-id      work-id
                      :resource/key [:rf.mutation instance-id]
                      :scope        cscope
                      :frame-id     frame-id
                      :generation   generation
                      :where        where})
        ;; Registration rejects an optimistic plan with :before-request
        ;; timing, so the two never both run here.
        opt-out?    (and (contains? payload :optimistic?) (false? (:optimistic? payload)))
        has-opt?    (and (not opt-out?)
                         (or (:optimistic spec) (:optimistic-tags spec)))
        ;; the successor paints on the cache as it stands
        opt-entries (when has-opt? (get-in runtime-db (rf.resources.state/entries-path)))
        [exact-tm exact-nils]
        (when has-opt?
          (optimistic-exact-targets (:optimistic spec) cparams cscope from-db-scopes where))
        [tag-tm tag-nils]
        (when has-opt?
          (optimistic-tag-targets
            (normalize-optimistic-tag-descriptors
              (when-let [f (:optimistic-tags spec)] (f cparams))
              {:frame-id frame-id :mutation mutation})
            opt-entries cscope from-db-scopes where))
        ;; Exact wins over tag-matched; `merge` keeps a nil (remove) patch-fn.
        opt-target-map (when has-opt? (merge tag-tm exact-tm))
        opt-nil-ids    (-> (vec exact-nils) (into tag-nils))
        ;; True even when every target was dropped, so the unresolved
        ;; evidence is still recorded and traced.
        opt-applied?   (boolean (and has-opt? (or (seq opt-target-map) (seq opt-nil-ids))))
        snapshot-id    (when opt-applied? (mint-snapshot-id instance-id generation))
        ;; A re-execute over a still-pending instance supersedes its apply:
        ;; keys the successor does not re-touch roll back now; re-touched keys
        ;; inherit the superseded pre-paint `:before`. Pending is `pending?`,
        ;; never the presence of `:rollback`, which a settled row keeps.
        prior       (get-in runtime-db (rf.resources.mutation-runtime/instance-path instance-id))
        prior-rows  (when (rf.resources.mutation-runtime/pending? prior)
                      (recorded-rollback prior))
        retouched?  #(contains? opt-target-map (:resource/key %))
        superseded  (when-let [rows (seq (remove retouched? prior-rows))]
                      (abandon-optimistic-apply runtime-db prior (vec rows) time-ms))
        inherited   (into {} (comp (filter retouched?) (map (juxt :resource/key identity)))
                          prior-rows)
        rdb0       (-> (or (:runtime-db superseded) runtime-db)
                       (assoc-in (rf.resources.mutation-runtime/instance-path instance-id) instance')
                       (rf.resources.work-ledger/put-record work-id record))
        [rdb1 opt-ks opt-inverse]
        (if (seq opt-target-map)
          (apply-optimistic rdb0 opt-target-map started-at)
          [rdb0 #{} []])
        ;; A re-touched key unmoved since the superseded apply keeps that
        ;; apply's baseline, so a chain restores its earliest unconfirmed
        ;; value; a moved key keeps its own snapshot, never a blind restore
        ;; across an authoritative write.
        opt-inverse (mapv (fn [{k :resource/key :as row}]
                            (if-let [old (get inherited k)]
                              (cond-> (assoc row :revalidate? true)
                                (= (:revision row) (:applied-revision old))
                                (assoc :before (:before old)))
                              row))
                          opt-inverse)
        rdb'       (if opt-applied?
                     (-> rdb1
                         (assoc-in (conj (rf.resources.mutation-runtime/instance-path instance-id) :patch-summary)
                                   {:snapshot-id       snapshot-id
                                    :rollback          opt-inverse
                                    :optimistic-keys   (vec opt-ks)
                                    :target-unresolved (vec opt-nil-ids)
                                    ;; the settle step fills this
                                    :reconciliation-refetches nil})
                         ;; Only the applied keys' index members change.
                         (update rf.resources.state/resources-key rf.resources.state/reindex-keys
                                 opt-entries (mapv rf.resources.state/key-id opt-ks)))
                     rdb1)]
    (when superseded
      (emit-abandoned-rollback! frame-id prior superseded))
    (when opt-applied?
      (rf.trace/emit! :rf.event :rf.mutation/optimistic-applied
                   {:rf.frame/id frame-id :mutation mutation :instance instance-id
                    :work/id work-id :generation generation :scope cscope
                    :snapshot-id snapshot-id
                    :affected-keys (vec opt-ks)
                    ;; the revision observed before this apply's bump
                    :revisions (mapv (fn [{:keys [resource/key revision forward]}]
                                       {:resource/key key :revision revision :forward forward})
                                     opt-inverse)
                    :tag-matched-keys (vec (keys tag-tm))
                    :target-unresolved (vec opt-nil-ids)
                    :cause [:mutation mutation instance-id]}))
    (rf.trace/emit! :rf.event :rf.mutation/started
                 (cond-> {:rf.frame/id frame-id :mutation mutation :instance instance-id
                          :work/id work-id :generation generation :scope cscope
                          :cause cause :invalidate-timing invalidate-timing}
                   before-plan (assoc :invalidation (plan-trace before-plan #{} runtime-db))))
    (maybe-warn-scope-mismatch!
      before-plan {:frame-id frame-id :mutation-id mutation :instance-id instance-id
                   :mut-scope cscope :entries (get-in runtime-db (rf.resources.state/entries-path))})
    {:rf.db/runtime rdb'
     ;; fx run in order: the generation commit and handle record establish
     ;; the stale-suppression identity, then a :before-request invalidation,
     ;; and only then the write lowers.
     :fx (cond-> [[:rf.resource/commit-generation {:value generation}]
                  [:rf.resource/record-work-handle
                   {:frame-id frame-id :work-id work-id
                    :transport transport-id :request-id request-id}]]
           before-fxs (into before-fxs)
           true       (conj lower-fx)
           superseded (into (:recovery-fx superseded)))}))

;; ---- :rf.mutation/clear — causal instance reset ---------------------------

(defn clear-handler
  "`:rf.mutation/clear` with `{:instance …}` or `{:mutation …}`: drops the
  matching instances and all their ledger rows, settles in-flight work
  `:cancelled` and best-effort aborts it (stale suppression protects
  correctness). A pending optimistic apply is rolled back first, with the
  revalidate obligation, because the write's server outcome is unknown.
  Spec 016 §Mutations (first public-beta gate)."
  [{rt :rf.db/runtime, frame-id :rf.frame/id, time-ms :rf/time-ms}
   [_event-id {:keys [instance mutation]}]]
  (let [runtime-db (or rt {})
        instances  (get-in runtime-db (rf.resources.mutation-runtime/instances-path))
        ;; The map is keyed on the CEDN-1 byte `key-id`, which is finer than
        ;; `=` for sequential ids, so `[:row 7]` must not also clear
        ;; `'(:row 7)`.
        target-kids (cond
                      (some? instance) (let [k (rf.resources.mutation-runtime/instance-key-id instance)]
                                         (when (contains? instances k) [k]))
                      (some? mutation) (keep (fn [[kid inst]]
                                               (when (= mutation (:mutation/id inst)) kid))
                                             instances)
                      :else            nil)
        target-kids (vec target-kids)
        in-flight  (into []
                         (keep (fn [kid]
                                 (let [inst (get instances kid)
                                       wid  (:current-work inst)]
                                   (when wid
                                     [wid (:transport (rf.resources.work-ledger/get-record runtime-db wid))]))))
                         target-kids)
        ;; read before the rows are dissoc'd
        cleared-ids (mapv #(:instance/id (get instances %)) target-kids)
        [rdb-r abandoned]
        (reduce (fn [[db acc] kid]
                  (let [inst (get instances kid)
                        rows (when (rf.resources.mutation-runtime/pending? inst)
                               (recorded-rollback inst))]
                    (if rows
                      (let [rolled (abandon-optimistic-apply db inst (vec rows) time-ms)]
                        [(:runtime-db rolled) (conj acc [inst rolled])])
                      [db acc])))
                [runtime-db []] target-kids)
        rdb'       (-> rdb-r
                       (update-in (rf.resources.mutation-runtime/instances-path)
                                  (fn [m] (reduce dissoc m target-kids)))
                       (as-> db (reduce (fn [d [wid _]]
                                          (rf.resources.work-ledger/update-record
                                            d wid rf.resources.work-ledger/mark-terminal
                                            ;; a cancellation carries the event's
                                            ;; causal clock
                                            :cancelled {:reason :mutation-clear
                                                        :completed-at time-ms}))
                                        db in-flight))
                       ;; Every cleared instance, not only in-flight ones: the
                       ;; terminal rows of past attempts are pruned nowhere
                       ;; else. Spec 016 §Ledger row retention and identity.
                       (as-> db (reduce (fn [d iid]
                                          (rf.resources.work-ledger/drop-rows-for-key
                                            d [:rf.mutation iid]))
                                        db cleared-ids)))]
    (doseq [[inst rolled] abandoned]
      (emit-abandoned-rollback! frame-id inst rolled))
    (rf.trace/emit! :rf.event :rf.mutation/cleared
                 ;; The cancelled rows are dropped with the instances, so the
                 ;; trace is where tooling reads the completion time.
                 {:rf.frame/id frame-id :cleared cleared-ids
                  :aborted (mapv first in-flight)
                  :completed-at time-ms})
    {:rf.db/runtime rdb'
     ;; Abort by the frame-qualified request-id; the bare work-id would miss.
     :fx (-> []
             (into (keep (fn [[wid transport]] (rf.resources.work-ledger/abort-fx transport frame-id wid)))
                   in-flight)
             (into (mapcat (fn [[_ rolled]] (:recovery-fx rolled)))
                   abandoned))}))

;; ---- framework-internal reply handlers ------------------------------------
;;
;; Stale suppression goes through the shared `re-frame.resources.reply-handlers`
;; substrate with the mutation knobs below. The transport appends its public
;; payload as the last event arg:
;;   [:rf.mutation.internal/succeeded <verification-payload> {:status :ok    :value <data> …}]
;;   [:rf.mutation.internal/failed    <verification-payload> {:status :error :error <envelope> …}]
;; The handlers re-lift both into the canonical reply map; the instance stores
;; its `:value` under `:result`. A direct-dispatch test may inline :result /
;; :error on arg 2.

(defn- instance-path-for-reply
  "The mutation family's slot path-fn: the instance at `:instance-id`."
  [{:keys [instance-id]}]
  (rf.resources.mutation-runtime/instance-path instance-id))

(defn- mutation-correlation
  "The stale reply's correlation keys `:instance/id`, `:mutation/id` and
  `:scope`, each omitted when absent. Managed-Effects §The reply map."
  [{:keys [instance-id mutation-id scope]}]
  (cond-> {}
    (some? instance-id) (assoc :instance/id instance-id)
    (some? mutation-id) (assoc :mutation/id mutation-id)
    (some? scope)       (assoc :scope scope)))

(def ^:private mutation-stale-knobs
  "The mutation family's knobs for `reply-handlers/stale-suppress-reply`."
  {:work-kind      rf.resources.reply/work-kind-mutation
   :stale-reason   :rf.mutation/superseded
   :correlation-fn mutation-correlation})

(defn- live-instance-for-reply
  "The live instance a reply still owns, or nil (suppress)."
  [runtime-db receiving-frame-id payload]
  (rf.resources.reply-handlers/live-slot-for-reply
    runtime-db receiving-frame-id instance-path-for-reply payload))

(defn- stale-suppress-reply
  "The canonical `:status :stale` outcome for a suppressed mutation reply.
  Managed-Effects §Stale suppression."
  [runtime-db payload extra]
  (rf.resources.reply-handlers/stale-suppress-reply
    runtime-db instance-path-for-reply payload mutation-stale-knobs extra))

(defn- emit-mutation-stale-suppressed!
  "Emits `:rf.mutation/stale-suppressed`. The work identity rides only as
  `:rf.reply/work-id`, one name per fact. Managed-Effects §Tracing."
  [frame-id instance-id _work-id generation outcome stale]
  (rf.resources.reply-handlers/emit-stale-suppressed!
    :rf.mutation/stale-suppressed
    {:rf.frame/id frame-id :instance instance-id
     :generation generation :outcome outcome}
    stale))

(defn succeeded-handler
  "`:rf.mutation.internal/succeeded` (`[_ <verification-payload> <http-result>]`).
  For a live reply: applies `:patches` and `:populates` before invalidation
  (a later same-tag invalidation would re-stale a fresh entry), supersedes
  reads in flight on written keys, applies `:removes`, dispatches the
  `:after-success` / `:after-settle` invalidation, commits any optimistic
  apply, settles the instance `:success`, and continues into `:reply-to`. A
  `{:from-db …}` resolves from the payload's issuance `:from-db-scopes`,
  never this event's app-db. A stale reply is suppressed and writes nothing.
  Spec 016 §Phase order (normative)."
  [{rt :rf.db/runtime, frame-id :rf.frame/id, time-ms :rf/time-ms}
   [_event-id {work-id :work/id :keys [instance-id mutation-id generation scope from-db-scopes reply-to] :as payload} http-result]]
  (let [where      'rf.mutation.internal/succeeded
        runtime-db (or rt {})
        ;; The reply event's declared `:rf/time-ms`, read once at the
        ;; transport boundary; the handler never reads the clock itself.
        completed-at time-ms
        reply      (rf.resources.reply/success-reply payload (rf.resources.reply/transport-success-value payload http-result :result)
                                         {:work-kind rf.resources.reply/work-kind-mutation
                                          :completed-at completed-at})
        result     (:value reply)
        inst       (live-instance-for-reply runtime-db frame-id payload)]
    (if (nil? inst)
      ;; Stale: no durable write, and only this frame's row is settled.
      (let [stale (stale-suppress-reply runtime-db payload {:outcome :success})]
        (emit-mutation-stale-suppressed!
          frame-id instance-id work-id generation :success stale)
        (rf.resources.reply-handlers/settle-suppressed-work!
          runtime-db frame-id payload {:reason :stale-reply :outcome :success}))
      (let [spec        (rf.resources.mutation-registry/mutation-meta mutation-id)
            params      (:params inst)
            timing      (or (:invalidate-timing spec) :after-success)
            clock-ms    completed-at
            ;; Populate wins on a key written by both (it runs last).
            [rdb1 patched-ks patch-policies patch-nil-ids patch-skipped]        (apply-patches runtime-db (:patches spec) params result clock-ms scope from-db-scopes where)
            [rdb2 populated-ks populate-policies populate-nil-ids populate-skipped] (apply-populates rdb1 (:populates spec) params result clock-ms scope from-db-scopes where)
            [rdb2' superseded-reads] (supersede-reads-under-writes
                                       runtime-db rdb2 (set/union patched-ks populated-ks) work-id)
            ;; Before the invalidation match, so a removed key is never also
            ;; reported stale.
            [rdb3 removed-ks removed-work remove-nil-ids remove-skipped] (apply-removes rdb2' (:removes spec) params result clock-ms scope from-db-scopes where)
            timer-policies      (merge patch-policies populate-policies)
            target-nil-ids      (-> (vec patch-nil-ids) (into populate-nil-ids) (into remove-nil-ids))
            patch-target-skipped    (mapv #(assoc % :arm :patches) patch-skipped)
            populate-target-skipped (mapv #(assoc % :arm :populates) populate-skipped)
            remove-target-skipped   (mapv #(assoc % :arm :removes) remove-skipped)
            target-skipped      (-> (vec patch-target-skipped)
                                    (into populate-target-skipped)
                                    (into remove-target-skipped))
            inv-plan    (when (#{:after-success :after-settle} timing)
                          (invalidation-plan (:invalidates spec) params result scope from-db-scopes where))
            inv-fxs     (when inv-plan (plan->fx inv-plan [:mutation mutation-id instance-id] populated-ks))
            inv-tags    (when inv-plan (plan-tags inv-plan))
            ;; Against the post-consequence cache, which the dispatched
            ;; invalidations will also see.
            inv-key-evidence (when inv-plan (plan-key-evidence inv-plan populated-ks rdb3))
            invalidated-ks   (vec (:invalidated-keys inv-key-evidence))
            ;; Spec 016 §Mutation completion continuations — call-site `:reply-to`.
            affected    (vec (distinct (concat patched-ks populated-ks removed-ks invalidated-ks)))
            ;; Commit: the authoritative writes above already overwrote the
            ;; optimistic value, so the inverse is discarded and only the
            ;; reconcile evidence is recorded.
            opt-summary  (:patch-summary inst)
            opt-applied? (some? (:snapshot-id opt-summary))
            opt-keys     (when opt-applied?
                           (set (map :resource/key (:rollback opt-summary))))
            authoritative-keys (set/union patched-ks populated-ks (set removed-ks))
            ;; An optimistic seed the authoritative reply did not cover is an
            ;; ownerless entry no read path ever visited, so nothing armed its
            ;; timers and nothing else would ever reap it; arm them here. A
            ;; patched or tombstoned entry already existed, so the read path
            ;; armed its timers.
            seeded-orphan-policies
            (when opt-applied?
              (into {}
                    (keep (fn [{scoped-key :resource/key forward :forward}]
                            (when (and (= :seed forward)
                                       (not (contains? authoritative-keys scoped-key)))
                              (when-let [policy (timer-delays
                                                  (rf.resources.registry/resource-meta
                                                    (nth scoped-key 1)))]
                                [scoped-key policy]))))
                    (:rollback opt-summary)))
            ;; disjoint by construction
            timer-policies (merge seeded-orphan-policies timer-policies)
            committed-keys     (when opt-applied?
                                 (vec (filter authoritative-keys opt-keys)))
            invalidated-set    (set invalidated-ks)
            reconciliation-refetches (when opt-applied?
                                       (vec (filter invalidated-set opt-keys)))
            patch-summary (cond-> {:patched   (vec patched-ks)
                                   :populated (vec populated-ks)
                                   :removed   (vec removed-ks)
                                   :invalidated      invalidated-ks
                                   :invalidated-tags (vec (or inv-tags #{}))
                                   :target-unresolved (vec target-nil-ids)
                                   :target-skipped (vec target-skipped)
                                   :snapshot-id nil :rollback nil
                                   :reconciliation-refetches nil}
                            ;; `:rollback` keeps the revision facts but drops
                            ;; each `:before`, a whole entry that would
                            ;; otherwise ride the trace off-box and stay pinned
                            ;; on the settled row.
                            opt-applied?
                            (assoc :snapshot-id (:snapshot-id opt-summary)
                                   :rollback    (mapv #(dissoc % :before)
                                                      (:rollback opt-summary))
                                   :committed   committed-keys
                                   :reconciliation-refetches reconciliation-refetches))
            inst'       (rf.resources.mutation-runtime/instance-succeeded
                          inst {:result result :settled-at clock-ms
                                :affected-keys affected :patch-summary patch-summary})
            ;; Only the authoritative keys change index members; the
            ;; dispatched invalidation never touches `:tags` or owners.
            old-entries (get-in runtime-db (rf.resources.state/entries-path))
            rdb'        (-> rdb3
                            (assoc-in (rf.resources.mutation-runtime/instance-path instance-id) inst')
                            (rf.resources.work-ledger/settle-terminal
                              work-id
                              :completed {:settled-at clock-ms})
                            (update rf.resources.state/resources-key rf.resources.state/reindex-keys
                                    old-entries
                                    (mapv rf.resources.state/key-id authoritative-keys))
                            (open-gc-windows frame-id timer-policies))
            ;; Without these, a populated ownerless entry would carry a
            ;; durable stale / GC policy but no armed reaper.
            ;; Spec 016 §Stale and GC scheduling.
            server?     (rf.resources.state/server-frame? frame-id)
            timer-fx    (mapv (fn [[scoped-key {:keys [stale-delay-ms gc-delay-ms]}]]
                                [:rf.resource/schedule-timers
                                 {:frame-id     frame-id
                                  :resource/key scoped-key
                                  ;; `:poll` is absent, so a polling entry
                                  ;; keeps its owner-driven cadence.
                                  :timers       {:stale stale-delay-ms
                                                 :gc    gc-delay-ms}
                                  :server?      server?}])
                              timer-policies)
            cont-fx     (continuation-fx
                          reply-to
                          ;; `:sensitive` params are redacted on the echo; the
                          ;; durable instance keeps the raw value.
                          (rf.resources.classification/redact-continuation-reply
                            (continuation-reply
                              reply {:mutation-id mutation-id :params params
                                     :instance-id instance-id :scope scope
                                     :affected-keys affected})
                            spec))
            remove-abort-fx (into [] (keep (fn [[wid transport]]
                                             (rf.resources.work-ledger/abort-fx transport frame-id wid)))
                                  removed-work)
            remove-timer-fx (when (seq removed-ks)
                              [[:rf.resource/cancel-timers
                                {:frame-id frame-id :resource/keys (vec removed-ks)}]])
            supersede-fx (into [] (mapcat (fn [[wid transport]]
                                            (let [abort (rf.resources.work-ledger/abort-fx
                                                          transport frame-id wid)]
                                              (cond-> [[:rf.resource/clear-work-handle
                                                        {:frame-id frame-id :work-id wid}]]
                                                abort (conj abort)))))
                               superseded-reads)]
        (rf.resources.work-ledger/clear-handle! frame-id work-id)
        (rf.trace/emit! :rf.event :rf.mutation/succeeded
                     (cond-> {:rf.frame/id frame-id :instance instance-id :mutation mutation-id
                              :work/id work-id :generation generation
                              :affected-keys affected :patch-summary patch-summary}
                       (seq removed-ks) (assoc :removed (vec removed-ks))
                       inv-plan (assoc :invalidation (plan-trace inv-plan populated-ks rdb3))))
        (when opt-applied?
          (rf.trace/emit! :rf.event :rf.mutation/optimistic-reconciled
                       {:rf.frame/id frame-id :instance instance-id :mutation mutation-id
                        :work/id work-id :generation generation
                        :snapshot-id (:snapshot-id opt-summary)
                        :optimistic-keys (vec opt-keys)
                        :committed committed-keys
                        :reconciliation-refetches reconciliation-refetches
                        :cause [:mutation mutation-id instance-id]}))
        (maybe-warn-scope-mismatch!
          inv-plan {:frame-id frame-id :mutation-id mutation-id :instance-id instance-id
                    :mut-scope scope :entries (get-in rdb3 (rf.resources.state/entries-path))})
        (doseq [{:keys [arm] :as s} target-skipped]
          (maybe-warn-target-skipped!
            [s] {:frame-id frame-id :mutation-id mutation-id
                 :instance-id instance-id :arm arm}))
        (emit-replied! cont-fx
                       {:frame-id frame-id :mutation-id mutation-id
                        :instance-id instance-id :work-id work-id
                        :status (:status reply) :reply-to reply-to})
        {:rf.db/runtime rdb'
         ;; The continuation is last, after the invalidation it composes with.
         :fx (cond-> (vec timer-fx)
               (seq supersede-fx)    (into supersede-fx)
               (seq remove-abort-fx) (into remove-abort-fx)
               remove-timer-fx       (into remove-timer-fx)
               inv-fxs               (into inv-fxs)
               cont-fx               (conj cont-fx)
               (rf.resources.mutation-runtime/generated-instance-id? instance-id) (conj (retire-fx instance-id)))}))))

(defn failed-handler
  "`:rf.mutation.internal/failed` (`[_ <verification-payload> <http-result>]`).
  For a live reply: rolls back any optimistic apply, dispatches the
  `:after-failure` / `:after-settle` invalidation against the rolled-back
  cache, settles the instance `:error` (terminal until a causal clear), and
  continues into `:reply-to`. An `:rf.http/aborted` envelope lowers to
  `:status :cancelled` and settles the ledger row `:cancelled`. A stale reply
  is suppressed. A `{:from-db …}` resolves from the payload's issuance
  `:from-db-scopes`. Spec 016 §Optimistic settle — commit / rollback / reconcile."
  [{rt :rf.db/runtime, frame-id :rf.frame/id, time-ms :rf/time-ms}
   [_event-id {work-id :work/id :keys [instance-id mutation-id generation scope from-db-scopes reply-to] :as payload} http-result]]
  (let [where      'rf.mutation.internal/failed
        runtime-db (or rt {})
        ;; the reply event's declared `:rf/time-ms`, never a fresh clock read
        completed-at time-ms
        reply      (rf.resources.reply/failure-reply payload (rf.resources.reply/transport-failure-envelope payload http-result)
                                         {:work-kind rf.resources.reply/work-kind-mutation
                                          :completed-at completed-at})
        error      (:error reply)
        ;; The ledger row must agree with the reply's `:rf.reply/work-status`;
        ;; a stale abort still settles `:suppressed`, because stale wins.
        ;; Managed-Effects §Status taxonomy.
        aborted?   (= :cancelled (:status reply))
        inst       (live-instance-for-reply runtime-db frame-id payload)]
    (if (nil? inst)
      ;; Stale: no durable write and no continuation.
      (let [stale (stale-suppress-reply runtime-db payload {:outcome :failure})]
        (emit-mutation-stale-suppressed!
          frame-id instance-id work-id generation :failure stale)
        (rf.resources.reply-handlers/settle-suppressed-work!
          runtime-db frame-id payload {:reason :stale-reply :outcome :failure}))
      (let [spec     (rf.resources.mutation-registry/mutation-meta mutation-id)
            params   (:params inst)
            timing   (or (:invalidate-timing spec) :after-success)
            clock-ms completed-at
            cause    [:mutation mutation-id instance-id]
            ;; Before the failure-time invalidation, so that pass reads the
            ;; rolled-back cache.
            rollback-inverse (recorded-rollback inst)
            on-conflict      (rf.resources.mutation-runtime/on-conflict-policy spec)
            rolled  (when rollback-inverse
                      (settle-optimistic-rollback rollback-inverse runtime-db on-conflict
                                                  cause clock-ms))
            settle-db (or (:runtime-db rolled) runtime-db)
            ;; The failure result is nil, so descriptor fns see only params,
            ;; and nothing was populated to exempt.
            inv-plan (when (#{:after-failure :after-settle} timing)
                       (invalidation-plan (:invalidates spec) params nil scope from-db-scopes where))
            inv-fxs  (when inv-plan (plan->fx inv-plan cause #{}))
            inv-tags (when inv-plan (plan-tags inv-plan))
            invalidated-ks (when inv-plan
                             (vec (:invalidated-keys (plan-key-evidence inv-plan #{} settle-db))))
            ;; Staled keys are affected even when owner-free with no refetch.
            refetched-ks (vec (:refetched-keys rolled))
            restored-ks  (vec (:restored-keys rolled))
            staled-ks    (vec (:staled-keys rolled))
            affected (vec (distinct (concat invalidated-ks restored-ks staled-ks refetched-ks)))
            rolled-summary (when rolled
                             (assoc (:patch-summary inst)
                                    :rollback                 (rollback-trace-dispositions
                                                                (:dispositions rolled) on-conflict)
                                    :reconciliation-refetches refetched-ks))
            inst'    (cond-> (rf.resources.mutation-runtime/instance-failed
                               inst {:error error :settled-at clock-ms
                                     :affected-keys affected})
                       rolled-summary (assoc :patch-summary rolled-summary))
            rdb'     (-> settle-db
                         (assoc-in (rf.resources.mutation-runtime/instance-path instance-id) inst')
                         ;; An abort carries no error envelope: it is not a
                         ;; user-visible failure.
                         (rf.resources.work-ledger/settle-terminal
                           work-id
                           (:rf.reply/work-status reply)
                           (if aborted?
                             {:reason :aborted :completed-at completed-at}
                             {:error error})))
            ;; Delivery is keyed on acceptance, so an accepted :error or
            ;; :cancelled reply continues too.
            ;; Spec 016 §Delivery rule — keyed on acceptance, not on a status enumeration.
            cont-fx  (continuation-fx
                       reply-to
                       (rf.resources.classification/redact-continuation-reply
                         (continuation-reply
                           reply {:mutation-id mutation-id :params params
                                  :instance-id instance-id :scope scope
                                  :affected-keys affected})
                         spec))]
        (rf.resources.work-ledger/clear-handle! frame-id work-id)
        (when rolled
          (rf.trace/emit! :rf.event :rf.mutation/optimistic-rolled-back
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
          ;; :force over a conflicted key clobbered a concurrent
          ;; authoritative write; warn so an unexpected clobber is loud.
          (when (and (= :force on-conflict) (seq (:conflicted-keys rolled)))
            (rf.trace/emit! :warning :rf.warning/optimistic-force-clobber
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
        (rf.trace/emit! :rf.event :rf.mutation/failed
                     (cond-> {:rf.frame/id frame-id :instance instance-id :mutation mutation-id
                              :work/id work-id :generation generation :error error
                              :affected-keys affected
                              :invalidated-tags (vec (or inv-tags #{}))}
                       inv-plan (assoc :invalidation (plan-trace inv-plan #{} settle-db))))
        (maybe-warn-scope-mismatch!
          inv-plan {:frame-id frame-id :mutation-id mutation-id :instance-id instance-id
                    :mut-scope scope :entries (get-in settle-db (rf.resources.state/entries-path))})
        (emit-replied! cont-fx
                       {:frame-id frame-id :mutation-id mutation-id
                        :instance-id instance-id :work-id work-id
                        :status (:status reply) :reply-to reply-to})
        {:rf.db/runtime rdb'
         :fx (cond-> []
               (seq (:recovery-fx rolled)) (into (:recovery-fx rolled))
               inv-fxs (into inv-fxs)
               cont-fx (conj cont-fx)
               (rf.resources.mutation-runtime/generated-instance-id? instance-id) (conj (retire-fx instance-id)))}))))
