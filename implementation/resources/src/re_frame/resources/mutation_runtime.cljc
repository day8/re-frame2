(ns re-frame.resources.mutation-runtime
  "The mutation runtime-db path, the durable mutation-instance shape, and the
  pure cache transforms the mutation handlers apply: controlled patch /
  populate, the optimistic apply and its settle protocol, target and
  instance-id validation, and invalidation-descriptor normalization. The
  impure swaps live in `re-frame.resources.mutation-events`.

  Instances are keyed by instance id, not mutation id, so two concurrent
  submissions of one mutation never clobber each other; each points at its
  in-flight work-ledger record through `:current-work`.
  Spec 016 §Mutations (first public-beta gate)."
  (:require [re-frame.error :as rf.error]
            [re-frame.resources.state :as rf.resources.state]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- reserved runtime-db path --------------------------------------------

(def mutations-key
  "The reserved runtime-db key of the mutation-instance map
  `{<key-id> <instance>}`.
  Spec 016 §`:rf.runtime/mutations` — mutation-instance runtime."
  :rf.runtime/mutations)

(defn instance-key-id
  "The map key for a mutation instance id: its CEDN-1 canonical-bytes string,
  as for the resource cache key. Clojure `=` equates `[:row 7]` and
  `'(:row 7)`, which would let two distinct submissions share a row; the bytes
  string keeps them apart and is plain EDN on every wire. The kind-preserving
  id is stored on the instance as `:instance/id`.
  Conventions §Canonical EDN identity."
  [instance-id]
  (rf.resources.state/key-id instance-id))

(defn instances-path
  "Runtime-db-relative path to the instance map."
  []
  [mutations-key])

(defn instance-path
  "Runtime-db-relative path to one instance, keyed by `instance-key-id`."
  [instance-id]
  [mutations-key (instance-key-id instance-id)])

(defn generated-instance-id?
  "True iff `instance-id` is one the runtime generated for an execute that
  supplied no `:instance` (`[:rf.mutation/instance <mutation-id> <generation>]`).
  The `:rf.mutation/*` head is reserved, so a caller-supplied id never has it.
  The runtime retires a generated-id instance after its last settle, since the
  caller never held the id and so cannot clear the row."
  [instance-id]
  (and (vector? instance-id) (= :rf.mutation/instance (first instance-id))))

;; ---- durable mutation-instance shape -------------------------------------
;; Plain EDN facts only: host handles live in the work-ledger side table, and
;; `:pending?` / `:settled?` are derived in the subs layer.

(def terminal-statuses
  "The settled instance statuses. A terminal instance under a caller id stays
  until a causal `:rf.mutation/clear`; one under a generated id is cleared by
  the runtime."
  #{:success :error})

(defn terminal?
  "True iff `status` is terminal."
  [status]
  (contains? terminal-statuses status))

(defn empty-instance
  "A fresh `:pending` instance for `mutation-id` / `instance-id`. Opts:
  `:scope` (the resolved execution scope), `:params` (canonical), `:cause`,
  `:generation`, `:work-id`, `:started-at`."
  [mutation-id instance-id {:keys [scope params cause generation work-id started-at]}]
  {:mutation/id   mutation-id
   :instance/id   instance-id
   :status        :pending
   :result        nil
   :error         nil
   :scope         scope
   :params        params
   :cause         cause
   :generation    (or generation 0)
   :current-work  work-id
   :started-at    started-at
   :settled-at    nil
   ;; Filled by the execute's optimistic apply and the success path;
   ;; `:patch-summary` carries the recorded inverse the settle step replays.
   :affected-keys nil
   :patch-summary nil})

(defn instance-succeeded
  "`instance` settled `:success` with `result`, its `:affected-keys` and
  `:patch-summary`; clears `:error` and `:current-work`."
  [instance {:keys [result settled-at affected-keys patch-summary]}]
  (assoc instance
         :status        :success
         :result        result
         :error         nil
         :settled-at    settled-at
         :current-work  nil
         :affected-keys affected-keys
         :patch-summary patch-summary))

(defn instance-failed
  "`instance` settled `:error` with the `:rf.http/*` failure envelope; clears
  `:current-work`. A write has no last-known-good, so there is no
  `:refresh-error` analogue."
  [instance {:keys [error settled-at affected-keys]}]
  (assoc instance
         :status        :error
         :error         error
         :result        nil
         :settled-at    settled-at
         :current-work  nil
         :affected-keys affected-keys))

(def dangling-on-restore-error
  "The failure envelope a pending instance settles to on epoch restore: its
  outcome is unknowable after the rewind, so it surfaces as an explicit,
  re-submittable error rather than staying `:pending` forever.
  Spec 016 §2. In-flight work does not survive restore as live work."
  {:kind    :rf.http/aborted
   :reason  :dangling-on-restore
   :message (str "the mutation's in-flight request was abandoned by an epoch "
                 "restore (the captured snapshot was :pending); its outcome is "
                 "unknowable after the timeline rewind, so the instance is "
                 "settled to a terminal :error and any late pre-restore reply is "
                 "suppressed (Spec 016 §Restore and replay).")})

(defn pending?
  "True iff `instance` is `:pending` or `:idle`, the restore reconcile target."
  [instance]
  (contains? #{:pending :idle} (:status instance)))

(defn instance-dangled
  "A restored pending `instance` settled `:error` with
  `dangling-on-restore-error`; other instances unchanged. Clearing
  `:current-work` is what makes a late pre-restore reply fail the work-id gate
  and be suppressed. Spec 016 §2. In-flight work does not survive restore as live work."
  [instance settled-at]
  (if (pending? instance)
    (assoc instance
           :status       :error
           :error        dangling-on-restore-error
           :result       nil
           :settled-at   settled-at
           :current-work nil)
    instance))

;; ---- controlled resource patch / populate ---------------------------------
;; These run on the success path before success-time invalidation, so a tag
;; the patch already satisfied is not re-staled; the events layer orders them.

(defn patch-entry
  "`entry` with `(patch-fn old-data mutation-result)` as its `:data`, settled
  `:loaded` and fresh, with `:revision` bumped (even when the value is `=`,
  since a patch is an authoritative write). Unchanged when the entry has no
  data to patch. Spec 016 §Mutations (first public-beta gate)."
  [entry patch-fn mutation-result {:keys [clock-ms stale-at]}]
  (if (rf.resources.state/has-data? entry)
    (let [old      (:data entry)
          patched  (patch-fn old mutation-result)
          shared   (if (= old patched) old patched)]
      (-> entry
          (assoc
            :data           shared
            :status         :loaded
            :loaded-at      clock-ms
            :stale-at       stale-at
            :invalidated-at nil
            :refresh-error  nil
            ;; A read in flight is superseded by this write (the caller settles
            ;; its row), so its late reply fails the work-id gate.
            :current-work   nil)
          ;; Or the next load-more settle would chain its legs over the
          ;; written pages.
          rf.resources.state/clear-refetch-sweep
          rf.resources.state/bump-revision))
    entry))

(defn populate-entry
  "`entry` (or a fresh one) seeded with `populate-value` as its `:data`,
  settled `:loaded` and fresh, with `:revision` bumped. A fresh entry takes
  `resource-id`, `scoped-key` and `tags`, so a later invalidation can reach
  it. Spec 016 §Populate is an authoritative load."
  [entry resource-id populate-value {:keys [clock-ms stale-at tags scoped-key]}]
  (let [base   (or entry (rf.resources.state/empty-entry resource-id scoped-key))
        old    (:data base)
        shared (if (and (some? old) (= old populate-value)) old populate-value)]
    (-> base
        (assoc
          :resource/id    (:resource/id base resource-id)
          :resource/key   (or (:resource/key base) scoped-key)
          :data           shared
          :status         :loaded
          :error          nil
          :refresh-error  nil
          :loaded-at      clock-ms
          :stale-at       stale-at
          :invalidated-at nil
          ;; As `patch-entry`: a read in flight is superseded.
          :current-work   nil
          :tags           (or tags (:tags base) #{}))
        ;; As `patch-entry`: so is its pending feed sweep.
        rf.resources.state/clear-refetch-sweep
        rf.resources.state/bump-revision)))

;; ---- optimistic apply: the snapshot inverse ------------------------------
;; The author writes the forward patch only; the runtime records the inverse
;; (the whole pre-apply entry plus its revision), so the inverse cannot drift.
;; Spec 016 §Optimistic mutations.

(def absent-snapshot
  "The `:before` sentinel for an apply against a key with no entry; rolling it
  back removes the entry. It marks the absence of an entry, never the forward
  op: removing an entry that exists snapshots that entry."
  :rf.optimistic/absent)

(defn snapshot-entry
  "The inverse of one touched entry: the entry itself, by reference, or
  `absent-snapshot` for a missing one."
  [entry]
  (if (some? entry) entry absent-snapshot))

(defn apply-optimistic-patch
  "`entry` with `(patch-fn old-data)` as its `:data`, settled `:loaded` and
  fresh, `:revision` bumped. An absent entry is seeded, taking
  `resource-id` / `scoped-key` / `tags` as `populate-entry` does; an existing
  entry keeps its own tags. Spec 016 §Optimistic mutations."
  [entry patch-fn resource-id {:keys [clock-ms stale-at tags scoped-key]}]
  (let [base   (or entry (rf.resources.state/empty-entry resource-id scoped-key))
        old    (:data base)
        new    (patch-fn old)
        shared (if (and (some? old) (= old new)) old new)]
    (-> base
        (assoc
          :resource/id    (:resource/id base resource-id)
          :resource/key   (or (:resource/key base) scoped-key)
          :data           shared
          :status         :loaded
          :error          nil
          :refresh-error  nil
          :loaded-at      clock-ms
          :stale-at       stale-at
          :invalidated-at nil
          :tags           (if entry (:tags entry) (or tags #{})))
        ;; The settle-time conflict check compares against the revision this
        ;; bump leaves behind.
        rf.resources.state/bump-revision)))

(defn apply-optimistic-remove
  "An optimistic remove: `entry` tombstoned in place (`:data nil`,
  `:status :idle`, freshness cleared, `:revision` bumped), never dissoc'd.

  In place because every owner-liveness guard is keyed to an entry existing:
  after a dissoc, an owner released mid-flight would move no revision, the
  rollback would see no conflict, and restoring the snapshot would resurrect
  the departed owner, leaving an entry that never GCs. The tombstone keeps
  `:tags`, `:active-owners` and the live read-work facts; an owner-free
  tombstone is GC-eligible as usual. Spec 016 §Optimistic mutations."
  [entry]
  (-> entry
      (assoc
        :data           nil
        :status         :idle
        :error          nil
        :refresh-error  nil
        :loaded-at      nil
        :stale-at       nil
        :invalidated-at nil
        ;; A tombstone must not project, via `:keep-previous?`, the very row
        ;; the user just deleted.
        :previous-key   nil)
      rf.resources.state/bump-revision))

(defn record-optimistic-entry
  "The recorded inverse for one touched entry:
  `{:resource/key :revision :applied-revision :before :forward}`, where
  `:revision` is the pre-apply revision, `:applied-revision` the revision the
  apply left (the settle-time conflict baseline), `:before` the snapshot and
  `:forward` one of `:patch` / `:seed` / `:remove`. The 3-arity derives
  `:applied-revision`: one past the observed revision, except a remove over an
  absent key, which wrote nothing.
  Spec 016 §Optimistic settle — commit / rollback / reconcile."
  ([scoped-key before-entry forward]
   (let [observed  (rf.resources.state/entry-revision
                     (when (not= before-entry absent-snapshot) before-entry))
         no-op?    (and (= forward :remove) (= before-entry absent-snapshot))
         applied   (if no-op? observed (inc observed))]
     (record-optimistic-entry scoped-key before-entry forward observed applied)))
  ([scoped-key before-entry forward observed-revision applied-revision]
   {:resource/key     scoped-key
    :revision         observed-revision
    :applied-revision applied-revision
    :before           before-entry
    :forward          forward}))

;; ---- the settle protocol --------------------------------------------------
;; Success commits (the recorded inverse is discarded); failure, an accepted
;; cancel or a restore dangle rolls back, conflict-aware; a stale reply writes
;; nothing. The decision keys on recorded facts only (acceptance verdict and
;; per-entry revision), so it has no wall-clock race. The impure swap lives in
;; `mutation-events`. Spec 016 §Optimistic settle — commit / rollback / reconcile.

(def on-conflict-policies
  "The closed `:on-conflict` enum. `:invalidate` (default) marks a contested
  entry stale so the read path recovers the truth; `:force` restores the
  inverse anyway, for entries the author knows are single-writer."
  #{:invalidate :force})

(defn on-conflict-policy
  "`spec`'s `:on-conflict`, defaulting to `:invalidate` for nil or unknown
  values (the enum is enforced at registration)."
  [spec]
  (let [p (:on-conflict spec)]
    (if (contains? on-conflict-policies p) p :invalidate)))

(defn optimistic-conflict?
  "True when the entry's revision has moved from the `applied-revision` the
  apply left behind, i.e. a competing write landed. An absent entry reads as
  revision 0, so one rule covers every forward form, including a remove over
  an absent key; the in-place tombstone is what keeps a revision to compare."
  [current-entry applied-revision]
  (not= (rf.resources.state/entry-revision current-entry) applied-revision))

(defn rollback-entry-disposition
  "The rollback disposition of one `recorded` inverse against `current-entry`:
  `{:resource/key :disposition :conflict? :before :forward}`. `:disposition`
  is `:restore` when the entry has not moved or `on-conflict` is `:force`,
  else `:invalidate`. Spec 016 §Optimistic settle — commit / rollback / reconcile."
  [current-entry {:keys [resource/key applied-revision before forward]} on-conflict]
  (let [conflict? (optimistic-conflict? current-entry applied-revision)]
    {:resource/key key
     :disposition  (if (and conflict? (not= :force on-conflict)) :invalidate :restore)
     :conflict?    conflict?
     :before       before
     :forward      forward}))

(def live-work-keys
  "The entry keys a rollback never restores from `:before`: the live read-work
  and ownership facts, which an optimistic apply never writes.

  Restoring them would orphan a refetch started while the mutation was
  pending: a read start does not bump `:revision`, so the settle would choose
  `:restore` and put back the pre-read `:generation` / `:current-work`, the
  read's own valid reply would then be suppressed, and its owner dropped."
  [:generation :current-work :request-id :attempt :active-owners])

(defn reconcile-restored-entry
  "The entry a `:restore` seats: `before` carrying `current`'s
  `live-work-keys`, with `:status` following a still-live `:current-work`
  (`:fetching` / `:loading`) so it never reads settled while a reply is due.
  `before` unchanged when `current` is nil or holds the same work facts."
  [before current]
  (if (nil? current)
    before
    (let [reconciled (merge before (select-keys current live-work-keys))]
      (cond-> reconciled
        (some? (:current-work reconciled))
        (assoc :status (rf.resources.state/next-status
                         (:status reconciled) :start-load
                         (rf.resources.state/has-data? reconciled)))))))

(defn restore-before
  "`runtime-db` with one `:restore` disposition applied: the `:before` entry
  reseated through `reconcile-restored-entry`, or the entry removed for an
  `absent-snapshot`. An absent snapshot under a live read leaves an empty
  entry carrying that read instead of orphaning it. The caller recomputes
  the indexes after the whole pass."
  [runtime-db {scoped-key :resource/key :keys [before]}]
  (let [entry-path (rf.resources.state/entry-path scoped-key)
        current    (get-in runtime-db entry-path)]
    (if (= before absent-snapshot)
      (if (some? (:current-work current))
        (assoc-in runtime-db entry-path
                  (reconcile-restored-entry
                    (rf.resources.state/empty-entry (:resource/id current) scoped-key)
                    current))
        (update-in runtime-db (rf.resources.state/entries-path)
                   dissoc (rf.resources.state/key-id scoped-key)))
      (assoc-in runtime-db entry-path (reconcile-restored-entry before current)))))

(defn dangle-rollback-optimistic
  "Rolls back a dangled optimistic instance's recorded apply on restore,
  returning `[runtime-db' rolled-back-keys]` (`[runtime-db []]` for a
  non-optimistic instance). It runs inside the restore reconciler's single
  pure pass, never as a dispatched event, which could race a fresh load: a
  conflict marks the entry stale in place at `settled-at`, an unmoved entry is
  restored, and `:on-conflict :force` restores regardless.
  Spec 016 §Restore and replay."
  [runtime-db inst spec settled-at]
  (let [inverse (-> inst :patch-summary :rollback)]
    (if-not (seq inverse)
      [runtime-db []]
      (let [on-conflict (on-conflict-policy spec)
            rdb' (reduce
                   (fn [rdb {scoped-key :resource/key :as recorded}]
                     (let [{:keys [disposition]}
                           (rollback-entry-disposition
                             (get-in rdb (rf.resources.state/entry-path scoped-key)) recorded on-conflict)]
                       (case disposition
                         :restore    (restore-before rdb recorded)
                         :invalidate (update-in rdb (rf.resources.state/entry-path scoped-key)
                                                rf.resources.state/entry-invalidate settled-at))))
                   runtime-db inverse)]
        [rdb' (mapv :resource/key inverse)]))))

;; ---- fail-closed boundary validation --------------------------------------
;; Pure, so `mutation-events` runs them before any runtime-db, ledger or HTTP
;; write. Registry lookup is injected (`registered-resource?`) to keep this
;; namespace free of a registry require.

(def reserved-scope-ns
  "The framework-reserved scope namespace. Conventions §Reserved namespaces (framework-owned)."
  "rf.scope")

(def reserved-scope-policies
  "The closed set of reserved bare scope keywords a literal scope may be."
  #{:rf.scope/global})

(defn reserved-scope-typo?
  "True iff `scope` is a bare `:rf.scope/*` keyword outside
  `reserved-scope-policies` (such as `:rf.scope/glabal`), which would
  otherwise write the cache under a wrong scope."
  [scope]
  (and (keyword? scope)
       (= reserved-scope-ns (namespace scope))
       (not (contains? reserved-scope-policies scope))))

(def same-scope-marker
  "The `:scope` meaning the mutation's resolved execution scope: the default
  for invalidation descriptors and exact targets. The events layer resolves
  it at settle time; it never reaches a cache key.
  Spec 016 §Scoped invalidation descriptors (per-target)."
  :rf.scope/same)

(defn target-key-error
  "The `:rf.error/mutation-invalid-target` ex-info for a bad patch / populate
  target; `arm` names the mutation-spec arm."
  [where arm reason target extra]
  (rf.error/thrown-ex-info
    :rf.error/mutation-invalid-target
    where
    reason
    {:recovery :fix-mutation-target
     :extra    (merge {:arm    arm
                       :target (pr-str target)}
                      extra)}))

;; ---- map-form exact targets -----------------------------------------------
;; `{:resource … :params … :scope …}` is the only public target form; the
;; `[scope resource-id params]` tuple is the storage form. Derived scopes
;; resolve against the mutation's issuance context, so the events layer
;; resolves each target's scope before these validators see it.
;; Spec 016 §Map-form exact resource targets.

(defn target-map?
  "True iff `target` is a map carrying `:resource`."
  [target]
  (and (map? target) (contains? target :resource)))

(defn- target-summary
  "An egress-safe `{:target <pr-str> :resource <kw>}` summary of `target` for
  trace and warning evidence."
  [target]
  (cond-> {:target (pr-str target)}
    (and (map? target) (keyword? (:resource target)))
    (assoc :resource (:resource target))))

(defn classify-target-key
  "Classifies one target against its already-resolved concrete scope:
  `[:apply <canonical-storage-key>]`, or `[:skip <reason> <summary>]` for a
  recoverable fault (`:non-map-target`, `:non-keyword-resource`,
  `:unregistered-resource`). Throws `:rf.error/mutation-invalid-target` for
  cache-identity corruption (a reserved-scope typo, non-EDN scope or params),
  checked first so no relaxed policy can swallow a wrong-identity write."
  [target resolved-scope registered-resource? where arm]
  (if-not (target-map? target)
    [:skip :non-map-target (target-summary target)]
    (let [{:keys [resource params]} target
          scope resolved-scope]
      (when (reserved-scope-typo? scope)
        (throw (target-key-error
                 where arm
                 (str "a mutation " (name arm) " target carries the bare "
                      "framework-reserved scope " (pr-str scope) ", which is "
                      "not one of the closed reserved policies "
                      (pr-str reserved-scope-policies) " — a typo would "
                      "silently write the cache under a wrong scope. Per "
                      "Conventions §Reserved namespaces.")
                 target {:scope scope})))
      (when-not (rf.resources.state/serializable-edn? scope)
        (throw (target-key-error
                 where arm
                 (str "a mutation " (name arm) " target's scope is not "
                      "serializable EDN — host / opaque values are rejected at "
                      "the cache-key boundary. Per Spec 016 §Resource identity.")
                 target {:scope (pr-str scope)})))
      (when-not (rf.resources.state/serializable-edn? params)
        (throw (target-key-error
                 where arm
                 (str "a mutation " (name arm) " target's params are not "
                      "serializable EDN — host / opaque values are rejected at "
                      "the cache-key boundary. Per Spec 016 §Resource identity.")
                 target {:params (pr-str params)})))
      (cond
        (not (keyword? resource))
        [:skip :non-keyword-resource (target-summary target)]

        (not (registered-resource? resource))
        [:skip :unregistered-resource (target-summary target)]

        :else
        [:apply (rf.resources.state/scoped-resource-key scope resource params)]))))

(defn validate-target-key!
  "The strict wrapper over `classify-target-key`: returns the canonical
  storage key, and throws `:rf.error/mutation-invalid-target` on every bad
  target, recoverable or not. An unregistered resource is refused because no
  subscription could reach the written entry."
  [target resolved-scope registered-resource? where arm]
  (let [[disposition a] (classify-target-key target resolved-scope
                                             registered-resource? where arm)]
    (case disposition
      :apply a
      :skip
      (case a
        :non-map-target
        (throw (target-key-error
                 where arm
                 (str "a mutation " (name arm) " target must be the map-form exact "
                      "target {:resource <id> :params <params> :scope <scope>} (the "
                      "only public input form — the scoped-key tuple is the internal "
                      "storage representation, not a public input); got "
                      (pr-str target) ". Per Spec 016 §Map-form exact resource "
                      "targets / EP-0016 Rider 2.")
                 target {}))

        :non-keyword-resource
        (throw (target-key-error
                 where arm
                 (str "a mutation " (name arm) " target map's :resource must be "
                      "a keyword; got " (pr-str (:resource target)) " in " (pr-str target)
                      ". Per Spec 016 §Map-form exact resource targets.")
                 target {:resource-id (:resource target)}))

        :unregistered-resource
        (throw (target-key-error
                 where arm
                 (str "a mutation " (name arm) " targets the UNREGISTERED "
                      "resource " (pr-str (:resource target)) " — a controlled patch / "
                      "populate must target a resource the read path also "
                      "knows, or the seeded / patched entry is unreachable by "
                      "any subscription. Call rf/reg-resource first. Per "
                      "EP-0003 §Mutations.")
                 target {:resource-id (:resource target)}))))))

(defn target-scope
  "`target`'s declared `:scope`, defaulting to `:rf.scope/same`."
  [target]
  (if (contains? target :scope) (:scope target) same-scope-marker))

(defn validate-target-map!
  "Validates and canonicalizes a whole `:patches` / `:populates` / `:removes`
  map `{target value}`, re-keyed by canonical storage key. `resolve-target-scope`
  returns `[:resolved <scope>]` or `[:nil-resolved <from-db-id>]`; a
  nil-resolved target is dropped, never written under an implicit scope.

  `:strict` (default; pre-write callers) throws the whole arm on any bad
  target and returns `[map nil-ids]`. `:skip-recoverable` (post-write settle
  only, since the server write has already committed) drops recoverable
  targets and returns `[map nil-ids skipped]`; corruption still throws.
  Spec 016 §Map-form exact resource targets."
  ([target-map resolve-target-scope registered-resource? kind where]
   (validate-target-map! target-map resolve-target-scope registered-resource? kind where :strict))
  ([target-map resolve-target-scope registered-resource? kind where policy]
   (case policy
     :strict
     (if-not (seq target-map)
       [nil []]
       (reduce-kv
         (fn [[m nils] target value]
           (let [[outcome scope-or-id] (resolve-target-scope target)]
             (case outcome
               :nil-resolved [m (conj nils scope-or-id)]
               :resolved     [(assoc m (validate-target-key!
                                         target scope-or-id registered-resource? where kind)
                                     value)
                              nils])))
         [{} []]
         target-map))

     :skip-recoverable
     (if-not (seq target-map)
       [nil [] []]
       (reduce-kv
         (fn [[m nils skipped] target value]
           (let [[outcome scope-or-id] (resolve-target-scope target)]
             (case outcome
               :nil-resolved [m (conj nils scope-or-id) skipped]
               :resolved
               (let [[disposition a b]
                     (classify-target-key target scope-or-id registered-resource? where kind)]
                 (case disposition
                   :apply [(assoc m a value) nils skipped]
                   :skip  [m nils (conj skipped {:reason a
                                                 :target (:target b)
                                                 :resource (:resource b)})])))))
         [{} [] []]
         target-map)))))

;; ---- mutation instance id validation --------------------------------------

(defn instance-id-error
  "The `:rf.error/mutation-non-serializable-instance-id` ex-info."
  [instance-id where]
  (rf.error/thrown-ex-info
    :rf.error/mutation-non-serializable-instance-id
    where
    (str "a mutation instance id must be serializable EDN "
         "(a scalar — keyword / string / safe-range integer / UUID / "
         "instant — or an EDN collection recursively built from such); got "
         (pr-str instance-id) ". The instance id is stored "
         "in runtime-db, the work-ledger work id, and the "
         "reply payloads — all durable + trace-visible + "
         "epoch / restore-safe — so it follows the same "
         "serializable-identity discipline as resource "
         "params and scopes. Host / opaque values "
         "(functions, promises, DOM nodes, "
         "AbortControllers, JS objects, atoms) and "
         "floats are rejected. Per EP-0003 §Mutations / Spec 016 "
         "§Resource identity.")
    {:recovery :fix-instance-id
     :extra    {:instance-id (pr-str instance-id)}}))

(defn validate-instance-id!
  "Returns `instance-id` when it is nil (a generated id follows) or
  serializable EDN; otherwise throws, because the id is stored durably and
  rides traces. Spec 016 §Resource identity."
  [instance-id where]
  (when (and (some? instance-id) (not (rf.resources.state/serializable-edn? instance-id)))
    (throw (instance-id-error instance-id where)))
  instance-id)

;; ---- scoped invalidation descriptors --------------------------------------
;; Both public `:invalidates` forms lower to one descriptor vector for the
;; single invalidation engine; the impure scope resolution and dispatch live
;; in `mutation-events`. Spec 016 §Scoped invalidation descriptors (per-target).

(defn invalidation-descriptor-error
  "The `:rf.error/mutation-invalid-invalidation` ex-info. A malformed
  `:invalidates` result fails before any dispatch, never as a silent no-op."
  [where reason raw extra]
  (rf.error/thrown-ex-info
    :rf.error/mutation-invalid-invalidation
    where
    reason
    {:recovery :fix-invalidates
     :extra    (merge {:arm         :invalidates
                       :invalidates (pr-str raw)}
                      extra)}))

(defn- normalize-one-descriptor
  "One descriptor map as `{:scope :tags :cross-scope? :refetch-populated?}`,
  `:scope` defaulting to `:rf.scope/same` and left unresolved. `:tags` goes
  through `state/normalize-tag-set`, so a lone vector tag stays one tag.
  Throws on a non-map descriptor or non-collection `:tags`."
  [descriptor raw where]
  (when-not (map? descriptor)
    (throw (invalidation-descriptor-error
             where
             (str "a mutation :invalidates descriptor must be a map "
                  "{:scope … :tags #{…}}; got " (pr-str descriptor)
                  ". Per Spec 016 §Scoped invalidation descriptors.")
             raw {:descriptor (pr-str descriptor)})))
  (let [{:keys [scope tags cross-scope? refetch-populated?]} descriptor]
    (when-not (and (some? tags) (coll? tags))
      (throw (invalidation-descriptor-error
               where
               (str "a mutation :invalidates descriptor must carry a non-nil "
                    ":tags collection; got " (pr-str tags) " in "
                    (pr-str descriptor) ". Per Spec 016 §Scoped invalidation "
                    "descriptors.")
               raw {:descriptor (pr-str descriptor) :tags (pr-str tags)})))
    {:scope              (if (contains? descriptor :scope) scope same-scope-marker)
     :tags               (rf.resources.state/normalize-tag-set tags)
     :cross-scope?       (boolean cross-scope?)
     :refetch-populated? (boolean refetch-populated?)}))

(defn normalize-invalidation-descriptors
  "The canonical descriptor vector for a raw `:invalidates` result: nil or
  empty gives `[]`; a map is one descriptor; a collection of maps is a
  descriptor vector; any other collection is the bare tag-set shorthand, one
  descriptor at `:rf.scope/same`. Scopes are left for the events layer to
  resolve. Throws `:rf.error/mutation-invalid-invalidation`
  otherwise. Spec 016 §Scoped invalidation descriptors (per-target)."
  [raw where]
  (cond
    (or (nil? raw) (and (coll? raw) (empty? raw)))
    []

    (map? raw)
    [(normalize-one-descriptor raw raw where)]

    (and (coll? raw) (map? (first raw)))
    (mapv #(normalize-one-descriptor % raw where) raw)

    ;; `normalize-tag-set` keeps a lone vector tag `[:article slug]` as one
    ;; tag, not the scalar set `#{:article slug}` that would match nothing.
    (coll? raw)
    [{:scope              same-scope-marker
      :tags               (rf.resources.state/normalize-tag-set raw)
      :cross-scope?       false
      :refetch-populated? false}]

    :else
    (throw (invalidation-descriptor-error
             where
             (str "a mutation :invalidates result must be a tag-set "
                  "(`#{[:article slug] …}`) or a descriptor map / vector "
                  "(`{:scope … :tags #{…}}`); got " (pr-str raw)
                  ". Per Spec 016 §Scoped invalidation descriptors.")
             raw {}))))
