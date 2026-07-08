(ns re-frame.resources.mutation-runtime
  "Mutation runtime-db paths + the durable mutation-INSTANCE shape, plus
  the pure controlled resource patch / populate helpers. Per Spec 016
  §Deferred slices (mutations, first public-beta gate) and EP-0003
  §Mutations.

  A mutation is a named, causal WRITE to remote state that, on success,
  invalidates / patches / populates cached resource reads. `reg-mutation`
  registers it; `:rf.mutation/execute` runs it. Mutation runtime state is
  keyed by mutation **INSTANCE** id, NOT only by mutation id, so two
  concurrent `:comment/add` submissions never clobber each other's
  pending / error / result state (EP-0003 §Mutations).

  Mutation instances live ONLY at `:rf.runtime/mutations` inside the
  runtime-db partition (`:rf.db/runtime`) — a reserved runtime-db key
  (per [Conventions §Reserved runtime-db keys]), allocated lazily,
  per-frame isolated, never an app-db location. The map is keyed by
  instance id `{<instance-id> <instance>}`; Xray groups instances under
  their registered `:mutation/id` while showing each request /
  invalidation / patch / result separately (EP-0003 §Mutations).

  ## Two-level identity (mirrors the resource entry ↔ work-record split)

  - **Mutation INSTANCE** (the durable runtime row, here) — pending /
    error / result FACTS keyed by instance id, per submission.
  - **Work record** (the existing `:rf.runtime/work-ledger`, neutral) —
    the in-flight attempt the instance points at via `:current-work`,
    carrying owners / causes / deadline / outcome and the host-handle
    side-table correlation. Mutations reuse the resource work-ledger
    substrate wholesale (work-kind `:mutation`).

  This namespace fixes the reserved key path, the durable instance
  shape, and the PURE patch / populate transition over resource entries
  (the mutation success handler applies them); the swaps over these
  paths live in `re-frame.resources.mutation-events`."
  (:require [re-frame.error :as error]
            [re-frame.resources.state :as state]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- reserved runtime-db path --------------------------------------------
;;
;; Inside runtime-db itself, framework code reads/writes the bare
;; `[:rf.runtime/mutations …]` path; inside a full frame-state projection
;; the mutation subtree is at `[:rf.db/runtime :rf.runtime/mutations …]`.

(def mutations-key
  "The reserved runtime-db key for the mutation-instance subtree
  (`:rf.runtime/mutations`). A map `{<key-id> <instance>}`, keyed on the
  CEDN-1 byte `key-id` of the mutation INSTANCE id (see `instance-key-id`) so
  concurrent submissions of the same mutation id do not clobber each other.
  Per Spec 016 §Cache home and write authority / EP-0003 §Mutations."
  :rf.runtime/mutations)

(defn instance-key-id
  "The CEDN-1 BYTE-IDENTITY map-key for a mutation INSTANCE id — its
  `canonical-bytes` string (rf2-8iciw8). The SAME canonical identity the
  resource cache key uses (`state/key-id`), applied to the instance id.

  WHY (the EP-0012 `=`-collapse fix, mirrored for mutations): a caller MAY
  supply a sequential / collection instance id (a row-keyed form addresses its
  instance by `[:row 7]`; `validate-instance-id!` accepts any serializable
  EDN). The instance id was used DIRECTLY as a Clojure map key under
  `:rf.runtime/mutations`, and Clojure map keys compare by `=`, which is
  COARSER than the authoritative CEDN-1 byte identity for SEQUENTIAL
  vector-vs-list — `(= [:row 7] '(:row 7))` is TRUE while their
  `canonical-bytes` differ (`v[…]` vs `l(…)`). Two CEDN-distinct submissions
  could therefore address the SAME runtime row and clobber / gate each other.
  Keying on the canonical-bytes STRING makes the map-key comparison EXACTLY
  the CEDN-1 byte identity, so a list-id row and a vector-id row get DISTINCT
  instances — without re-erasing the kind (the kind-preserving instance id is
  stored alongside on the instance as `:instance/id`). The bytes string is
  plain serializable EDN, so it rides the epoch / restore / trace wire with no
  custom handler — exactly the resource cache (`state/key-id`) discipline.

  Total on a `validate-instance-id!`-conforming id (`serializable-edn?` →
  `canonical-bytes` is total on canonical EDN). Per EP-0003 §Mutations /
  Conventions §Canonical EDN identity."
  [instance-id]
  (state/key-id instance-id))

(defn instances-path
  "Runtime-db-relative path to the mutation-instances map
  `{<key-id> <instance>}` — keyed on the CEDN-1 byte `key-id`
  (`instance-key-id`), NOT the raw instance id (rf2-8iciw8). Per Spec 016
  §Cache home."
  []
  [mutations-key])

(defn instance-path
  "Runtime-db-relative path to a single mutation instance by its instance
  id — the map is keyed on the instance id's CEDN-1 byte `key-id`
  (`instance-key-id`), so two CEDN-distinct sequential ids (`[:row 7]` vs
  `'(:row 7)`) address DISTINCT rows (rf2-8iciw8). Per EP-0003 §Mutations
  (runtime state keyed by mutation instance id)."
  [instance-id]
  [mutations-key (instance-key-id instance-id)])

;; ---- durable mutation-instance shape -------------------------------------
;;
;; Plain EDN — host handles (AbortControllers) live in the work-ledger side
;; table keyed by `[frame-id work-id]`, NEVER on the instance. The instance
;; stores FACTS, not derived booleans (`:pending?` / `:settled?` are public
;; derived sub values, computed in the subs layer, never stored).

(def terminal-statuses
  "The terminal mutation-instance statuses (the write has settled). A
  terminal instance is cleared by an explicit causal `:rf.mutation/clear`
  (NOT a form-error reset) or pruned by registration teardown. Per EP-0003
  §Mutations (failure-state lifetime + causal clear/reset)."
  #{:success :error})

(defn terminal?
  "True iff `status` is a terminal mutation-instance status (the write has
  settled with a result or an error). Per EP-0003 §Mutations."
  [status]
  (contains? terminal-statuses status))

(defn empty-instance
  "Construct a fresh `:pending` mutation instance for `mutation-id` /
  `instance-id`. The durable instance stores FACTS (status / result /
  error / timestamps / generation / current-work / the canonical params /
  scope / the affected-resource-key reservation for the optimistic-rollback
  trace shape), NOT derived booleans (`:pending?` / `:settled?` are derived
  in the subs layer). Per EP-0003 §Mutations.

  Opts:
  - `:scope`         — the resolved cache scope the invalidation / patch
                       defaults to (same scope rules as resources)
  - `:params`        — the canonical params the request / `:invalidates` /
                       `:patches` close over
  - `:cause`         — the initiating cause
  - `:generation`    — the monotone generation (stale-suppression identity)
  - `:work-id`       — the linked work-ledger record id
  - `:started-at`    — epoch-ms"
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
   ;; The optimistic-rollback trace reservation (EP-0003 §Mutations:
   ;; "the mutation trace shape should reserve room for them now: affected
   ;; resource keys, patch summaries, snapshot ids, rollback result, and
   ;; reconciliation refetches"). EP-0019 slice 2 fills the optimistic-apply
   ;; half: the `:patch-summary` `:snapshot-id` / `:rollback` slots carry the
   ;; recorded snapshot-inverse the SETTLE slice replays (commit / rollback /
   ;; reconcile). nil until the execute (phase 1.5) / success path writes them.
   :affected-keys nil
   :patch-summary nil})

(defn instance-succeeded
  "Transition a mutation instance to `:success` with the decoded `result`,
  recording `:settled-at`, the produced `:affected-keys` (the resource keys
  the success path patched / populated / invalidated), and the
  `:patch-summary` (the optimistic-rollback trace reservation). Clears
  `:error` / `:current-work`. Pure `(instance, …) -> instance`. Per EP-0003
  §Mutations."
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
  "Transition a mutation instance to `:error` with the failure `error`
  envelope (the closed `:rf.http/*` shape, the same envelope resource
  `:error` carries), recording `:settled-at` and any `:affected-keys` an
  invalidate-on-failure timing produced. Clears `:current-work`. A mutation
  failure has NO `:refresh-error` analogue — a write has no last-known-good
  to preserve, so the failure is the terminal state until a causal clear.
  Pure. Per EP-0003 §Mutations (tag invalidation from failure when useful)."
  [instance {:keys [error settled-at affected-keys]}]
  (assoc instance
         :status        :error
         :error         error
         :result        nil
         :settled-at    settled-at
         :current-work  nil
         :affected-keys affected-keys))

(def dangling-on-restore-error
  "The structured failure envelope a PENDING mutation instance is terminally
  settled to on epoch restore (Spec 016 §Restore and replay part 2). A
  restored `:pending` instance's `:current-work` references an attempt the
  restored timeline no longer owns — its host handle was never serialized and
  a late pre-restore reply must be SUPPRESSED. The write's true outcome is
  unknowable after a timeline rewind (it may have settled server-side, or
  never been sent), so the instance is settled to a terminal `:error` carrying
  this `:dangling-on-restore` envelope rather than left stranded `:pending`
  forever — the app sees an explicit, re-submittable failure (XState-grade:
  a dangling in-flight identity is surfaced, never silently stuck). Shares the
  closed `:rf.http/*` failure taxonomy (`:rf.http/aborted` — the in-flight
  request was effectively abandoned by the rewind)."
  {:kind    :rf.http/aborted
   :reason  :dangling-on-restore
   :message (str "the mutation's in-flight request was abandoned by an epoch "
                 "restore (the captured snapshot was :pending); its outcome is "
                 "unknowable after the timeline rewind, so the instance is "
                 "settled to a terminal :error and any late pre-restore reply is "
                 "suppressed (Spec 016 §Restore and replay).")})

(defn pending?
  "True iff `instance` is a non-terminal `:pending` / `:idle` mutation instance
  (a write still in flight, or created-not-yet-dispatched) — the restore
  reconcile target. Terminal (`:success` / `:error`) instances ride through a
  restore unchanged (they already carry a settled outcome). Per EP-0003
  §Mutations / Spec 016 §Restore and replay part 2."
  [instance]
  (contains? #{:pending :idle} (:status instance)))

(defn instance-dangled
  "Terminally settle a restored PENDING mutation `instance` to `:error` with
  the `dangling-on-restore-error` envelope and CLEAR its `:current-work`
  pointer (Spec 016 §Restore and replay part 2). Clearing `:current-work` is
  the load-bearing half: a late pre-restore reply's `live-instance-for-reply`
  check (`(= work-id (:current-work inst))`) then fails, so the reply is
  suppressed and CANNOT patch / populate / invalidate post-restore state — the
  same work-id + generation stale-suppression gate the resource path uses. The
  terminal `:error` settle is the visible half (no stuck `:pending`). Pure
  `(instance, settled-at) -> instance`. A non-pending (already-terminal)
  instance is returned unchanged."
  [instance settled-at]
  (if (pending? instance)
    (assoc instance
           :status       :error
           :error        dangling-on-restore-error
           :result       nil
           :settled-at   settled-at
           :current-work nil)
    instance))

;; ---- controlled resource patch / populate (Spec 016 / EP-0003) ------------
;;
;; A mutation response can PATCH an existing resource entry (transform its
;; last-known-good `:data` in place) or POPULATE one (seed a `:loaded` entry
;; from the mutation result). These are the "controlled resource patch /
;; populate APIs for mutation responses" (EP-0003 §Mutations) — controlled
;; because they go through the SAME durable entry shape + structural sharing
;; the read path uses, never a raw runtime-db poke. They run on the mutation
;; SUCCESS path, BEFORE the success-time invalidation (a patch makes an entry
;; fresh; a subsequent same-tag invalidation would just re-stale it, so the
;; declarative success path patches first, then invalidates the tags the
;; patch did NOT already satisfy — the events layer orders them).

(defn patch-entry
  "Pure: apply `patch-fn` to an existing resource entry's last-known-good
  `:data`, returning the transformed entry (status stays `:loaded`,
  freshness refreshed: `:invalidated-at` cleared, `:loaded-at` / `:stale-at`
  re-stamped from `clock-ms` + the resource's stale policy). Applies
  STRUCTURAL SHARING (keeps the old `:data` value when the patched value is
  `=`). Returns the entry UNCHANGED when it has no usable data to patch (a
  patch is a transform of existing data — populate seeds a fresh entry).
  Per EP-0003 §Mutations (controlled resource patch APIs).

  `patch-fn` is `(fn [old-data mutation-result] -> new-data)` — the mutation
  author transforms the cached read from the write's result."
  [entry patch-fn mutation-result {:keys [clock-ms stale-at]}]
  (if (state/has-data? entry)
    (let [old      (:data entry)
          patched  (patch-fn old mutation-result)
          ;; structural sharing — keep the old value when nothing changed
          shared   (if (= old patched) old patched)]
      (-> entry
          (assoc
            :data           shared
            :status         :loaded
            :loaded-at      clock-ms
            :stale-at       stale-at
            :invalidated-at nil
            :refresh-error  nil)
          ;; EP-0019 / byl7bk: a patch is an authoritative durable write
          ;; (re-stamps :loaded-at / :stale-at, clears :invalidated-at), so it
          ;; bumps the per-entry :revision write identity UNCONDITIONALLY —
          ;; even on the `=`-shared structural-sharing branch. The no-usable-
          ;; data no-op branch below makes no write and does not bump.
          state/bump-revision))
    entry))

(defn populate-entry
  "Pure: SEED a resource entry's `:data` from a populate value, settling it
  `:loaded` with fresh timestamps (`:loaded-at` / `:stale-at` from
  `clock-ms` + policy, `:invalidated-at` cleared). Used when a mutation
  result IS the new value of a resource read (e.g. an article PUT returns
  the saved article, populating `[:article/by-slug …]` directly without a
  refetch round-trip). Builds on an empty entry when none exists; structural
  sharing preserves an `=` existing value. Per EP-0003 §Mutations
  (controlled resource populate APIs).

  `resource-id` stamps the seeded entry's `:resource/id` when fresh; `tags`
  is the produced tag set (a populated entry MUST carry its own tags so a
  later invalidation can reach it). `scoped-key` (opt) stamps the seeded
  entry's `:resource/key` when fresh (rf2-9e0tyq — a populate may CREATE an
  entry, and every entry must carry its own scoped-key vector now that
  `:entries` is keyed on the byte `key-id`); an existing entry keeps its own."
  [entry resource-id populate-value {:keys [clock-ms stale-at tags scoped-key]}]
  (let [base   (or entry (state/empty-entry resource-id scoped-key))
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
          :tags           (or tags (:tags base) #{}))
        ;; EP-0019 / byl7bk: a populate is an authoritative durable write (it
        ;; seeds / re-stamps :loaded-at / :stale-at / :tags), so it bumps the
        ;; per-entry :revision write identity UNCONDITIONALLY — including the
        ;; `=`-shared branch and a freshly-seeded entry (base revision 0 -> 1).
        state/bump-revision)))

;; ---- optimistic apply (phase 1.5) — snapshot inverse (EP-0019 D1/D2) -------
;;
;; An `:optimistic` / `:optimistic-tags` plan applies a FORWARD patch to the
;; resource cache BEFORE the request settles (phase 1.5). The runtime records
;; the truthful INVERSE per touched entry — a SNAPSHOT of the whole entry as it
;; stood immediately BEFORE the forward patch (`:before`, structural-shared;
;; `:absent` for a missing entry) plus its `:revision` at apply time — on the
;; mutation INSTANCE row's `:patch-summary` `:rollback` slot. The author writes
;; the forward patch only; the runtime owns the inverse (truthful by
;; construction, no author drift — EP-0019 Decision 1 §Why not a forward+inverse
;; pair). These are the PURE pieces; the impure phase-1.5 swap lives in
;; `mutation-events`.

(def absent-snapshot
  "The `:before` sentinel for an optimistic apply against a key with NO entry
  (EP-0019 Open Issue 6 — optimistic SEED of an absent key, or an optimistic
  REMOVE that vanishes a card). A rollback of an `:absent` snapshot REMOVES the
  entry (restores the absence). Distinct from a `nil` entry value so the settle
  protocol can tell \"there was nothing here\" from \"we did not snapshot\"."
  :rf.optimistic/absent)

(defn snapshot-entry
  "PURE: capture the truthful optimistic INVERSE of a single touched entry — the
  WHOLE entry as it stood immediately before the forward patch, by reference
  (structural sharing; the cache already shares structure on `=`). Returns the
  entry itself for an existing entry (so a rollback restores it verbatim,
  including its `:status` / `:data` / freshness timers / `:revision`), or the
  `absent-snapshot` sentinel for a missing entry (a rollback then removes it).
  Per EP-0019 Decision 2 (inverse = entry snapshot, not value diff)."
  [entry]
  (if (some? entry) entry absent-snapshot))

(defn apply-optimistic-patch
  "PURE: apply a FORWARD optimistic `patch-fn` `(fn [old-data] -> new-data)` to a
  resource entry's `:data`, settling it `:loaded`/fresh and bumping the per-entry
  `:revision` (the optimistic apply IS an authoritative durable write a later
  rollback could clobber — EP-0019 Decision 2 / byl7bk Open Issue 5). NO mutation
  result — the reply does not exist yet (phase 1.5).

  Three forms fall out of the snapshot inverse (EP-0019 Open Issue 6):
  - an EXISTING entry — patch its `old-data` through `patch-fn` (an optimistic
    PATCH; structural-shared on `=`);
  - an ABSENT entry — SEED it `:loaded` with `(patch-fn nil)` (an optimistic
    PUT/seed of an absent key); `resource-id` / `scoped-key` / `tags` stamp the
    fresh entry exactly as `populate-entry` does;
  - a `nil` `patch-fn` — an optimistic REMOVE (the caller dissocs the entry; this
    fn is not called for that form).

  `clock-ms` / `stale-at` re-stamp freshness; `tags` (opt) stamps a freshly
  seeded entry's tags (so a later invalidation can reach it). Per EP-0019
  Decision 1 / §Optimistic mutations."
  [entry patch-fn resource-id {:keys [clock-ms stale-at tags scoped-key]}]
  (let [base   (or entry (state/empty-entry resource-id scoped-key))
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
          ;; an EXISTING entry KEEPS its own tags (an optimistic patch does not
          ;; relabel); a freshly SEEDED entry (no prior entry) takes the
          ;; resource's `tags` so a later invalidation can reach it.
          :tags           (if entry (:tags entry) (or tags #{})))
        ;; EP-0019 Decision 2: the optimistic apply is an authoritative durable
        ;; write — it bumps the per-entry `:revision` write identity, so the
        ;; recorded inverse's `:revision` (observed BEFORE this bump) lets the
        ;; settle-time conflict check detect a competing write.
        state/bump-revision)))

(def applied-removed-revision
  "The `:applied-revision` sentinel for an optimistic REMOVE (the apply dissoc'd
  the entry, so it left NO revision behind). The settle conflict check treats a
  still-absent entry (current revision 0) as UNMOVED for a remove, and a
  RE-CREATED entry (a competing write seeded the key) as MOVED. Distinct from a
  numeric applied revision (a patch/seed left the entry at a concrete count)."
  :rf.optimistic/removed)

(defn record-optimistic-entry
  "PURE: the recorded INVERSE shape for ONE touched entry on the instance row's
  `:patch-summary` `:rollback` slot (EP-0019 Decision 2). Carries the canonical
  `:resource/key` (the EP-0012 canonical-identity scoped key), the `:revision`
  observed at apply time (the conflict-check trace basis — the entry's revision
  BEFORE this apply's own bump), the `:applied-revision` (the revision the apply
  LEFT the entry at — the settle-time conflict baseline; the apply's own +1 bump
  is NOT a conflict, only a write landing BEYOND it is), the `:before` snapshot
  (structural-shared entry, or the `absent-snapshot` sentinel), and `:forward` —
  a small descriptive summary of the applied forward op (`:patch` / `:seed` /
  `:remove`) for the trace. The settle slice replays `:before` (revision-
  permitting) or invalidates on conflict; it does NOT consume the live entry
  value here.

  3-arity (slice 2 shape — `:applied-revision` derived from `before` + forward):
  a patch/seed bumps the before-revision by one; a remove leaves no revision.
  4-arity: the caller supplies the observed post-apply `applied-revision`
  explicitly (the apply already computed it). Per EP-0019 Decision 2 / §Optimistic
  settle."
  ([scoped-key before-entry forward]
   (let [observed (state/entry-revision
                    (when (not= before-entry absent-snapshot) before-entry))
         applied  (if (= forward :remove) applied-removed-revision (inc observed))]
     (record-optimistic-entry scoped-key before-entry forward observed applied)))
  ([scoped-key before-entry forward observed-revision applied-revision]
   {:resource/key     scoped-key
    :revision         observed-revision
    :applied-revision applied-revision
    :before           before-entry
    :forward          forward}))

;; ---- the settle protocol (phase 4) — commit / rollback / reconcile ---------
;;
;; The SETTLE slice (EP-0019 Decision 3) consumes the recorded inverse +
;; slice-1's `state/revision-conflict?` to decide, per touched entry, the
;; deterministic terminal disposition of an optimistic apply:
;;
;;   - on mutation SUCCESS  -> COMMIT (the optimistic value is overwritten by
;;     the authoritative `:populates` / `:patches`; the recorded inverse is
;;     discarded — `:reconciled`);
;;   - on mutation FAILURE / accepted cancel / restore-DANGLE -> ROLL BACK,
;;     conflict-aware: if the entry's `:revision` is UNMOVED since the apply,
;;     restore the recorded `:before` verbatim; if it MOVED, `:on-conflict`
;;     governs — `:invalidate` (default) marks the entry stale to recover
;;     authoritative truth via the read path, `:force` restores the (stale)
;;     inverse anyway (single-writer last-write-wins, with a tooling warning);
;;   - on a STALE / superseded reply -> NEITHER (the inverse is discarded; the
;;     newer generation's apply already recorded the truthful inverse).
;;
;; These are the PURE pieces (the per-entry decision + the cache transform); the
;; impure swap (the `:on-conflict` invalidation dispatch, the trace emit) lives
;; in `mutation-events`. The settle is keyed on the work-id + generation
;; acceptance verdict + the per-entry revision — both canonical recorded facts,
;; so there is NO wall-clock race in the decision (EP-0019 §Determinism summary).

(def on-conflict-policies
  "The closed `:on-conflict` rollback-conflict-rule enum (EP-0019 Decision 3).
  `:invalidate` (the default + recommended) defers a CONTESTED rollback to the
  read path — it marks the moved entry stale and lets a refetch recover the
  authoritative value, never resurrecting a stale inverse (the deterministic,
  always-correct choice, and re-frame2's deliberate divergence from
  TanStack/SWR's unconditional context restore). `:force` restores the recorded
  inverse even on conflict (last-write-wins by the rolling-back mutation, for
  entries the author KNOWS are single-writer) — tooling warns it can clobber a
  concurrent write."
  #{:invalidate :force})

(defn on-conflict-policy
  "The resolved `:on-conflict` policy for a mutation `spec`, defaulting to
  `:invalidate` (EP-0019 Decision 3 — the read path is the recovery authority on
  a contested rollback). An unknown / nil value falls to the default; the closed
  enum is enforced at registration. Per Spec 016 §Optimistic settle."
  [spec]
  (let [p (:on-conflict spec)]
    (if (contains? on-conflict-policies p) p :invalidate)))

(defn optimistic-conflict?
  "PURE: did an authoritative durable write land on the entry BETWEEN the
  optimistic apply and the reply settling (EP-0019 Decision 3)? The apply LEFT
  the entry at `applied-revision` (a numeric revision for a patch/seed, or the
  `applied-removed-revision` sentinel for a remove); a CONFLICT is the entry
  moving AWAY from that baseline — the apply's OWN +1 bump is expected and is NOT
  a conflict, only a competing write beyond it is. A canonical-identity
  comparison over the monotone `:revision`, never a value diff:

  - patch / seed (`applied-revision` numeric) -> conflict iff the entry's current
    revision ≠ the applied revision (a competing write bumped it further, or the
    entry was removed-then-reseeded landing at a different count);
  - remove (`applied-revision` = `:removed`) -> conflict iff the entry was
    RE-CREATED (a competing write seeded the key the apply had removed); a
    still-absent entry is UNMOVED (no conflict — the remove stands)."
  [current-entry applied-revision]
  (if (= applied-revision applied-removed-revision)
    (some? current-entry)
    (not= (state/entry-revision current-entry) applied-revision)))

(defn rollback-entry-disposition
  "PURE: decide ONE recorded inverse entry's rollback disposition at settle time
  (EP-0019 Decision 3 conflict rule). `current-entry` is the entry as it stands
  NOW in the cache (nil for a missing key); `recorded` is the
  `record-optimistic-entry` inverse (`{:resource/key :revision :applied-revision
  :before :forward}`); `on-conflict` is the resolved policy
  (`:invalidate` | `:force`). Returns a disposition map:

    {:resource/key <scoped-key>
     :disposition  :restore | :invalidate
     :conflict?    <bool>          ;; whether the entry moved since the apply
     :before       <entry|:absent> ;; the snapshot to restore (when :restore)
     :forward      <:patch|:seed|:remove>}

  - no conflict (entry UNMOVED since the apply left it) -> `:restore` the
    recorded `:before` verbatim (the truthful, conflict-free rollback — `:absent`
    removes the entry);
  - conflict (entry MOVED) + `:force` -> `:restore` the (stale) inverse anyway,
    `:conflict? true` (the caller warns);
  - conflict + `:invalidate` (default) -> `:invalidate`: do NOT restore; the
    caller marks the entry stale + refetches the authoritative value.

  Per EP-0019 §Decision 3 / §Optimistic settle."
  [current-entry {:keys [resource/key applied-revision before forward]} on-conflict]
  (let [conflict? (optimistic-conflict? current-entry applied-revision)]
    {:resource/key key
     :disposition  (if (and conflict? (not= :force on-conflict)) :invalidate :restore)
     :conflict?    conflict?
     :before       before
     :forward      forward}))

(defn restore-before
  "PURE: apply ONE `:restore` rollback disposition to the cache `runtime-db` —
  restore the recorded `:before` entry verbatim at its scoped key (structural-
  shared; the snapshot was captured by reference), or REMOVE the entry (dissoc by
  the byte `key-id`) when `:before` is the `absent-snapshot` sentinel (the apply
  had seeded an absent key — rollback restores the absence). Pure
  `(runtime-db, disposition) -> runtime-db'`. The caller recomputes the reverse
  indexes after the whole settle pass (a restore may re-create / drop entries +
  tags). Per EP-0019 Decision 3 (restore the exact entry that existed)."
  [runtime-db {scoped-key :resource/key :keys [before]}]
  (if (= before absent-snapshot)
    (update-in runtime-db (state/entries-path) dissoc (state/key-id scoped-key))
    (assoc-in runtime-db (state/entry-path scoped-key) before)))

(defn dangle-rollback-optimistic
  "PURE: roll back a restored PENDING optimistic mutation INSTANCE's recorded
  apply on epoch restore (EP-0019 Open Issue 3 / Q3 GUARD). A `:pending`
  optimistic write dangles to a terminal `:error` on restore (`instance-dangled`)
  — the entry shows the optimistic value with no in-flight write to confirm it,
  which is an accepted-error-shaped terminal, so it triggers the SAME
  conflict-aware rollback as a failed reply.

  THE LOAD-BEARING ORDERING (Q3): this runs INSIDE the restore reconciler's
  single pure pass over `runtime-db`, NOT as a second post-restore dispatched
  event — a dispatched `:invalidate` could RACE a fresh load the restored
  timeline issues. So a CONFLICT (the entry's `:revision` moved) marks the entry
  durably STALE in place (`state/entry-invalidate` — `:invalidated-at` set, the
  read path refetches on the next live-owner ensure) rather than dispatching an
  invalidation; an UNMOVED revision restores the recorded `:before` verbatim
  (`restore-before`). `:on-conflict :force` restores the (stale) inverse even on
  conflict. There is no dispatch, no racing event, no second pass.

  `inst` is the (already-dangled) instance row; `runtime-db` is the cache; `spec`
  the mutation spec (for `:on-conflict`, nil → `:invalidate`); `settled-at` the
  restore's causal time (the durable stale `:invalidated-at` stamp). Returns
  `[runtime-db' rolled-back-keys]` (the keys touched by the rollback — for the
  deferred restore trace). A non-optimistic dangled instance is a no-op
  (`[runtime-db []]`). Per EP-0019 Decision 3 / Open Issue 3 / Spec 016
  §Restore and replay."
  [runtime-db inst spec settled-at]
  (let [inverse (-> inst :patch-summary :rollback)]
    (if-not (seq inverse)
      [runtime-db []]
      (let [on-conflict (on-conflict-policy spec)
            rdb' (reduce
                   (fn [rdb {scoped-key :resource/key :as recorded}]
                     (let [{:keys [disposition]}
                           (rollback-entry-disposition
                             (get-in rdb (state/entry-path scoped-key)) recorded on-conflict)]
                       (case disposition
                         :restore    (restore-before rdb recorded)
                         ;; CONFLICT + :invalidate — mark the moved entry stale
                         ;; durably IN THE PASS (no dispatch; the read path
                         ;; recovers on the next ensure — the Q3 no-race rule).
                         :invalidate (update-in rdb (state/entry-path scoped-key)
                                                state/entry-invalidate settled-at))))
                   runtime-db inverse)]
        [rdb' (mapv :resource/key inverse)]))))

;; ---- fail-closed boundary validation (Spec 016 §Resource identity / -------
;;       EP-0003 §Mutations)
;;
;; Mutation runtime state is durable and trace-visible, so the identities it
;; writes — the mutation INSTANCE id and the patch / populate TARGET scoped
;; keys — must follow the SAME serializable-EDN discipline the resource cache
;; key and the resource params already enforce (`state/serializable-edn?` /
;; `state/reject-non-edn!`). These predicates / validators are PURE so they
;; sit in the runtime layer (this namespace, the home of the instance shape +
;; the controlled patch / populate transition); the impure handler boundary
;; (`mutation-events`) CALLS them before any runtime-db / work-ledger write or
;; HTTP lowering, so an invalid identity fails CLOSED — before any partial
;; cache mutation. Resource-registration lookup is injected as a predicate
;; (`registered-resource?`) so the runtime stays free of a registry require.

(def reserved-scope-ns
  "The framework-reserved scope namespace (`:rf.scope/*`, per Conventions
  §Reserved namespaces). A BARE keyword in this namespace is a CLOSED reserved
  enum (`reserved-scope-policies`); any other `:rf.scope/*` bare keyword is a
  TYPO, not a literal scope (mirrors `registry`'s reserved-scope gate). A
  non-keyword scope VALUE (`[:rf.scope/session {…}]` tuple, a map, a string)
  is NOT in the bare-keyword reserved slot."
  "rf.scope")

(def reserved-scope-policies
  "The closed reserved bare-keyword scope-policy enum (mirrors `registry`).
  A canonicalized scoped key may carry a literal SCOPE that is one of these
  reserved policies (`:rf.scope/global` is a legitimate literal scope), an
  app-namespaced keyword, or a data-value (tuple / map / string). Any OTHER
  bare `:rf.scope/*` keyword is a typo and rejected."
  #{:rf.scope/global :rf.scope/from-caller})

(defn reserved-scope-typo?
  "True iff `scope` is a BARE keyword in the framework-reserved `:rf.scope/*`
  namespace that is NOT one of the closed `reserved-scope-policies` — i.e. a
  typo (`:rf.scope/glabal`) that must NOT be silently accepted as a literal
  scope (which would write the cache under a wrong scope). Mirrors the
  fail-closed reserved-namespace gate `registry/valid-scope-policy?` applies
  at resource registration. A non-keyword scope value is never a typo."
  [scope]
  (and (keyword? scope)
       (= reserved-scope-ns (namespace scope))
       (not (contains? reserved-scope-policies scope))))

(def same-scope-marker
  "The `:scope` marker meaning \"the mutation's resolved (execution) scope\" —
  the DEFAULT when an invalidation descriptor OR a map-form exact target omits
  `:scope`, and the meaning of the bare tag-set invalidation shorthand (Spec
  016 §Scoped invalidation descriptors / §Map-form exact resource targets).
  Resolved to the concrete mutation scope by the events layer at settle time;
  it is NOT itself a literal cache scope (it never reaches the cache key)."
  :rf.scope/same)

(defn target-key-error
  "Build the canonical thrown-error shape (Spec 009 §The thrown-error shape)
  for an invalid mutation patch / populate TARGET scoped key. `arm`
  (`:patches` | `:populates`) names the offending mutation-spec arm."
  [where arm reason target extra]
  (error/thrown-ex-info
    :rf.error/mutation-invalid-target
    where
    reason
    {:recovery :fix-mutation-target
     :extra    (merge {:arm    arm
                       :target (pr-str target)}
                      extra)}))

;; ---- map-form exact target (EP-0016 Rider 2 / slice 6) --------------------
;;
;; The ONLY public input form for an exact `:populates` / `:patches` (and a
;; future remove) target is the TARGET MAP `{:resource … :params … :scope …}`
;; (Spec 016 §Map-form exact resource targets / EP-0016 issue 4 — no migration
;; window; pre-alpha, no external consumers). The hand-built scoped-key tuple
;; `[scope resource-id params]` remains the documented INTERNAL / STORAGE
;; representation (the `:rf/scoped-resource-key` shape the read path writes
;; under) — an input-form-vs-storage-form distinction per EP-0007 rule 3, NOT
;; two public spellings of one fact.
;;
;; The map `:scope` is one of: `:rf.scope/same` (the mutation's resolved
;; scope — the DEFAULT when omitted), `:rf.scope/global`, a concrete canonical
;; scope value, or a `{:from-db <id>}` named-resolver reference. The DERIVED
;; scope forms (`:rf.scope/same` / `{:from-db …}`) resolve against the
;; settle-time app-db, so this PURE runtime validator does NOT resolve scope
;; itself — the events layer resolves each target's scope first (it owns the
;; registry + db), then this validator validates + canonicalizes the resulting
;; CONCRETE key fail-closed before any cache write.

(defn target-map?
  "True iff `target` is the public map-form exact target
  `{:resource <id> :params <params>}` (a map carrying `:resource`). The
  `:scope` key is optional (it defaults to `:rf.scope/same`). A 3-element
  storage tuple is NOT a target map. Per Spec 016 §Map-form exact resource
  targets / EP-0016 Rider 2."
  [target]
  (and (map? target) (contains? target :resource)))

(defn- target-summary
  "An egress-SAFE summary of a target for trace / warning evidence
  (rf2-1vpbld). `pr-str`s the target the same way `target-key-error` does, so
  no raw host value leaks onto the trace beyond what the thrown-error shape
  already exposes. Returns a small serializable map `{:resource … :target …}`
  (the resource id is kept literal when it is a keyword — the recoverable
  identity the developer must fix; the whole target is pr-str'd)."
  [target]
  (cond-> {:target (pr-str target)}
    (and (map? target) (keyword? (:resource target)))
    (assoc :resource (:resource target))))

(defn classify-target-key
  "PURE classifier for a SINGLE mutation patch / populate / remove TARGET
  (rf2-1vpbld). Splits the boundary into two dispositions:

  - `[:apply <canonical-storage-key>]` — a VALID target; the canonical
    `[canonical-scope resource-id canonical-params]` storage key the caller
    writes under (never a caller's alternate spelling).
  - `[:skip <reason-kw> <egress-safe-summary>]` — a RECOVERABLE bad target the
    settle-time relaxed policy DROPS-AND-WARNS rather than throws (a typo on
    one sibling must not strand a committed write — the asymmetry-fix the
    settle path needs): a non-map target (`:non-map-target`), a missing /
    non-keyword `:resource` (`:non-keyword-resource`), or an UNREGISTERED
    resource id (`:unregistered-resource`). These are the same 'thing isn't
    there / wrong shape' class a patch on a missing entry already no-ops on.

  THROWS `:rf.error/mutation-invalid-target` (NEVER classified `:skip`) for
  CACHE-IDENTITY CORRUPTION — the cases that would silently write the cache
  under a WRONG identity, which no relaxed policy may swallow:

  - a reserved-scope TYPO (a bare `:rf.scope/*` keyword outside the closed
    enum — `:rf.scope/glabal`), which would write under a wrong scope;
  - a non-EDN / host scope or params (the cache key MUST be serializable EDN).

  The corruption checks run BEFORE the recoverable ones for a malformed map so
  a target that is simultaneously corruption-class (non-EDN scope) and
  recoverable (unregistered) still THROWS. `resolved-scope` is the CONCRETE
  scope the events layer already resolved; `registered-resource?` is the
  injected `(fn [resource-id] -> truthy)` registry predicate; `where` / `arm`
  name the call-site + offending arm for the thrown-error shape."
  [target resolved-scope registered-resource? where arm]
  (if-not (target-map? target)
    ;; a non-map target (e.g. a bare tuple, a keyword) — recoverable: the
    ;; shape is wrong, but nothing is written under a wrong identity.
    [:skip :non-map-target (target-summary target)]
    (let [{:keys [resource params]} target
          scope resolved-scope]
      ;; CORRUPTION-class first (a wrong-identity write no relaxed policy may
      ;; swallow): reserved-scope typo + non-EDN scope / params.
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
      (when-not (state/serializable-edn? scope)
        (throw (target-key-error
                 where arm
                 (str "a mutation " (name arm) " target's scope is not "
                      "serializable EDN — host / opaque values are rejected at "
                      "the cache-key boundary. Per Spec 016 §Resource identity.")
                 target {:scope (pr-str scope)})))
      (when-not (state/serializable-edn? params)
        (throw (target-key-error
                 where arm
                 (str "a mutation " (name arm) " target's params are not "
                      "serializable EDN — host / opaque values are rejected at "
                      "the cache-key boundary. Per Spec 016 §Resource identity.")
                 target {:params (pr-str params)})))
      ;; RECOVERABLE class (settle-time drop-and-warn; pre-write throws via the
      ;; `validate-target-key!` wrapper): non-keyword :resource, unregistered.
      (cond
        (not (keyword? resource))
        [:skip :non-keyword-resource (target-summary target)]

        (not (registered-resource? resource))
        [:skip :unregistered-resource (target-summary target)]

        :else
        [:apply (state/scoped-resource-key scope resource params)]))))

(defn validate-target-key!
  "Fail-closed validation of a SINGLE mutation patch / populate TARGET, BEFORE
  any cache mutation (EP-0003 §Mutations / Spec 016 §Resource identity /
  §Map-form exact resource targets). The STRICT (throwing) wrapper over the
  pure `classify-target-key` (rf2-1vpbld): it throws on BOTH the
  cache-identity-corruption cases AND the recoverable cases (so the default /
  pre-write / optimistic policy is UNCHANGED — a bad target rejects the whole
  arm). The settle path relaxes the RECOVERABLE cases via the
  `:skip-recoverable` policy on `validate-target-map!`; this wrapper does not.

  The public INPUT form is the TARGET MAP
  `{:resource <id> :params <params> :scope <scope>}` (EP-0016 Rider 2 — the
  only public input form, no tuple migration window). The map's `:scope` has
  already been RESOLVED to a CONCRETE scope value by the events layer
  (`resolved-scope` — the `:rf.scope/same` default and any `{:from-db …}`
  reference resolve against the settle-time app-db upstream); this PURE
  validator never resolves scope itself. Rejects, loudly:

  - a non-map target (the tuple is the internal storage form, not a public
    input — a tuple reaching here is rejected loudly);
  - a missing / non-keyword `:resource` id;
  - an UNREGISTERED resource id (`registered-resource?` returns falsey) — a
    patch / populate must target a resource the read path also knows, or the
    seeded / patched entry is unreachable by any subscription;
  - a reserved-scope TYPO (a bare `:rf.scope/*` keyword outside the closed
    enum — `:rf.scope/glabal`), which would silently write under a wrong
    scope;
  - a non-EDN / host scope or params (the cache key MUST be serializable EDN —
    the same boundary `state/reject-non-edn!` enforces for resource params).

  `registered-resource?` is `(fn [resource-id] -> truthy)` — the injected
  registry-lookup predicate (so this PURE validator carries no registry
  require). `where` names the call-site public surface; `arm`
  (`:patches` | `:populates`) names the offending mutation-spec arm. Returns
  the CANONICAL STORAGE key `[canonical-scope resource-id canonical-params]`
  on success (so the caller writes under the canonical identity, never a
  caller's alternate spelling). Throws `:rf.error/mutation-invalid-target`
  otherwise."
  [target resolved-scope registered-resource? where arm]
  (let [[disposition a b] (classify-target-key target resolved-scope
                                               registered-resource? where arm)]
    (case disposition
      :apply a
      ;; STRICT policy: a recoverable :skip ALSO throws (the corruption-class
      ;; already threw inside classify). Reconstruct the precise message.
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
  "The DECLARED `:scope` of a map-form exact target, defaulting to
  `:rf.scope/same` (the mutation's resolved scope) when the target omits
  `:scope` — the same default the invalidation-descriptor `:scope` carries.
  Pure; the events layer resolves the returned scope (concrete /
  `:rf.scope/same` / `{:from-db …}`) against the settle-time app-db. Per Spec
  016 §Map-form exact resource targets."
  [target]
  (if (contains? target :scope) (:scope target) same-scope-marker))

(defn validate-target-map!
  "Fail-closed validation + canonicalization of a WHOLE mutation `:patches` /
  `:populates` / `:removes` target map `{target value}` (EP-0003 §Mutations /
  Spec 016 §Map-form exact resource targets). Each KEY is a public map-form
  target `{:resource :params :scope}`; `resolve-target-scope` is
  `(fn [target] -> [:resolved <concrete-scope>] | [:nil-resolved <from-db-id>])`
  — injected by the events layer (it owns the registry + settle-time db) and
  resolves each target's declared `:scope` (`:rf.scope/same` / concrete /
  `{:from-db …}`). A target whose scope resolves NIL (`:nil-resolved`) is
  FAIL-CLOSED: it is DROPPED (no cache write under an implicit global, never a
  silent wrong-scope poke).

  `policy` (rf2-1vpbld) selects how a BAD resolved key is handled:

  - `:strict` (DEFAULT — the pre-write / optimistic / `:execute`-time callers,
    where no server write has landed): EVERY bad target — recoverable OR
    corruption-class — REJECTS the whole arm (no partial cache mutation), via
    the throwing `validate-target-key!`. Returns `[canonical-map nil-ids]` (the
    historic 2-tuple — the optimistic caller destructures it).

  - `:skip-recoverable` (the POST-WRITE SETTLE arms only): a RECOVERABLE bad
    target (unregistered resource; non-map / non-keyword `:resource`) is
    DROPPED-AND-collected rather than thrown — applying the VALID siblings
    instead of stranding the whole instance AFTER the server write already
    committed (the asymmetry the read path's no-op-on-missing-entry already
    has). CACHE-IDENTITY CORRUPTION (reserved-scope typo; non-EDN scope /
    params) STILL THROWS the whole arm — no relaxed policy may swallow a
    wrong-identity write. Returns the 3-tuple
    `[canonical-map nil-ids skipped]` where `skipped` is a vector of
    `{:reason <kw> :target <pr-str> :resource <id?>}` egress-safe summaries
    (the events layer warns + records them on the trace beside the nil-resolved
    evidence).

  A map re-keyed by the CANONICAL STORAGE key (values preserved). `kind`
  (`:patches` | `:populates` | `:removes`) names the offending arm in the
  error. A nil / empty input map returns the policy's empty shape
  (`[nil []]` / `[nil [] []]`)."
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
               ;; classify-target-key THROWS on corruption-class (kept) and
               ;; returns [:skip …] for the recoverable cases (dropped here).
               (let [[disposition a b]
                     (classify-target-key target scope-or-id registered-resource? where kind)]
                 (case disposition
                   :apply [(assoc m a value) nils skipped]
                   :skip  [m nils (conj skipped {:reason a
                                                 :target (:target b)
                                                 :resource (:resource b)})])))))
         [{} [] []]
         target-map)))))

;; ---- mutation INSTANCE id validation (Spec 016 §Resource identity) --------

(defn instance-id-error
  "Build the canonical thrown-error shape (Spec 009 §The thrown-error shape)
  for a non-serializable mutation INSTANCE id."
  [instance-id where]
  (error/thrown-ex-info
    :rf.error/mutation-non-serializable-instance-id
    where
    (str "a mutation instance id must be serializable EDN "
         "(a scalar — keyword / string / number — or an "
         "EDN collection recursively built from such); got "
         (pr-str instance-id) ". The instance id is stored "
         "in runtime-db, the work-ledger work id, and the "
         "reply payloads — all durable + trace-visible + "
         "epoch / restore-safe — so it follows the same "
         "serializable-identity discipline as resource "
         "params and scopes. Host / opaque values "
         "(functions, promises, dates, DOM nodes, "
         "AbortControllers, JS objects, atoms) are "
         "rejected. Per EP-0003 §Mutations / Spec 016 "
         "§Resource identity.")
    {:recovery :fix-instance-id
     :extra    {:instance-id (pr-str instance-id)}}))

(defn validate-instance-id!
  "Fail-closed: reject a non-serializable mutation INSTANCE id BEFORE it is
  stored in runtime-db / the work-ledger work id / the reply payloads (all
  durable + trace-visible + epoch-restore-safe), mirroring the resource
  params / scope EDN discipline. A caller MAY supply nil (the events layer
  then mints a generated id) — nil passes here; the GENERATED id is itself
  serializable by construction. Returns `instance-id` unchanged when it
  conforms; throws `:rf.error/mutation-non-serializable-instance-id`
  otherwise. Per EP-0003 §Mutations."
  [instance-id where]
  (when (and (some? instance-id) (not (state/serializable-edn? instance-id)))
    (throw (instance-id-error instance-id where)))
  instance-id)

;; ---- scoped invalidation descriptors (EP-0016 D2 / slice 5) ---------------
;;
;; The mutation `:invalidates` arm is `(fn [params result] -> <descriptors>)`.
;; Two PUBLIC input forms lower to ONE canonical descriptor vector (and from
;; there into the SINGLE scoped invalidation engine `:rf.resource/invalidate-
;; tags`, never a second engine — Spec 016 §Scoped invalidation descriptors):
;;
;;   - the BARE tag-set shorthand `#{[:article slug] [:article-list]}` — means
;;     "invalidate those tags in the mutation's resolved (execution) scope"
;;     (`:rf.scope/same`, the default);
;;   - the per-target DESCRIPTOR form — a single descriptor map
;;     `{:scope … :tags #{…}}` or a vector of them, each naming its OWN scope
;;     so one mutation can precisely invalidate global facts AND viewer-relative
;;     facts without a blunt `:cross-scope?` blast.
;;
;; A descriptor `:scope` is one of: `:rf.scope/same` (default when omitted —
;; the mutation's resolved scope), `:rf.scope/global`, a concrete canonical
;; scope value, or a `{:from-db <id>}` named-resolver reference resolved
;; against db at SETTLE time (phase 4, the single use-time rule). A descriptor
;; MAY carry `:cross-scope? true` (the audited escape — invalidate the tag in
;; every scope the cache holds, scopes the call site cannot enumerate) and
;; `:refetch-populated? true` (the Rider-1 same-mutation refetch opt-in, parsed
;; here and carried for the slice-6 populate-exempt pass).
;;
;; This namespace owns the PURE normalization (raw form -> canonical descriptor
;; vector); the impure per-descriptor scope resolution + the engine dispatch fx
;; live in `mutation-events` (it needs the registry + app-db + trace).

(defn invalidation-descriptor-error
  "Build the canonical thrown-error shape (Spec 009 §The thrown-error shape)
  for a malformed mutation `:invalidates` result. A malformed descriptor fails
  CLOSED at settle time (before any invalidation dispatch), never a silent
  no-op — an author who returns garbage from `:invalidates` learns loudly."
  [where reason raw extra]
  (error/thrown-ex-info
    :rf.error/mutation-invalid-invalidation
    where
    reason
    {:recovery :fix-invalidates
     :extra    (merge {:arm         :invalidates
                       :invalidates (pr-str raw)}
                      extra)}))

(defn- normalize-one-descriptor
  "Normalize ONE descriptor map into the canonical shape
  `{:scope <scope-or-same-marker> :tags #{…} :cross-scope? bool
  :refetch-populated? bool}`, validating fail-closed. `:scope` defaults to
  `:rf.scope/same` (Spec 016 §Scoped invalidation descriptors). `:tags` must
  be a non-nil collection of tags; the boolean flags default false. `:tags`
  is lowered through the shared `state/normalize-tag-set` (the SAME
  normalizer the bare tag-set shorthand and the direct
  `:rf.resource/invalidate-tags` `:tags` use), so a LONE vector tag written
  directly (`{:tags [:article slug]}`) is the ONE tag `#{[:article slug]}`,
  NOT a scalar set of its elements `#{:article slug}` (a naive `(set tags)`
  silently matches nothing — rf2-ypgayg). The `:scope` VALUE is NOT
  canonicalized here (a `:rf.scope/same` marker and a `{:from-db …}`
  reference are not concrete scopes yet) — the events layer resolves +
  canonicalizes the concrete scope at settle time. Returns the canonical
  descriptor map; throws `:rf.error/mutation-invalid-invalidation` on a
  non-map descriptor or a non-collection `:tags`."
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
     :tags               (state/normalize-tag-set tags)
     :cross-scope?       (boolean cross-scope?)
     :refetch-populated? (boolean refetch-populated?)}))

(defn normalize-invalidation-descriptors
  "PURE: lower the raw `:invalidates` result into the canonical descriptor
  vector `[{:scope … :tags #{…} :cross-scope? bool :refetch-populated? bool}]`
  — the one shape the events layer resolves + dispatches into the single
  scoped invalidation engine (Spec 016 §Scoped invalidation descriptors,
  EP-0016 D2). Accepts the two PUBLIC input forms:

    - the BARE tag-set shorthand (a set / sequential collection of TAGS, where
      a tag is itself a vector like `[:article slug]`) — one descriptor at
      `:rf.scope/same` carrying all the tags;
    - the per-target DESCRIPTOR form — a single descriptor MAP, or a vector of
      descriptor maps, each `{:scope … :tags #{…}}`.

  A nil / empty raw result yields an empty vector (the mutation invalidates
  nothing). The disambiguation: a MAP is a single descriptor; a sequential /
  set collection whose first element is a MAP is a descriptor vector; any
  other non-empty collection is the bare tag-set (its elements are tags). The
  bare tag-set is lowered through `state/normalize-tag-set`, so a LONE vector
  tag written directly (`[:article slug]` — a vector whose head is a scalar
  marker, not a collection) is the ONE tag `#{[:article slug]}`, NOT a scalar
  set of its elements `#{:article slug}` (which would silently match nothing —
  rf2-ru73k6 F1). A malformed result fails CLOSED
  (`:rf.error/mutation-invalid-invalidation`) rather than silently invalidating
  nothing. The descriptor `:scope` values
  are left UNRESOLVED here (the `:rf.scope/same` marker, a `{:from-db …}`
  reference, a concrete scope) — the events layer resolves each at settle
  time against the mutation scope / app-db."
  [raw where]
  (cond
    (or (nil? raw) (and (coll? raw) (empty? raw)))
    []

    ;; a single descriptor map
    (map? raw)
    [(normalize-one-descriptor raw raw where)]

    ;; a vector / list / set of descriptor maps
    (and (coll? raw) (map? (first raw)))
    (mapv #(normalize-one-descriptor % raw where) raw)

    ;; the bare tag-set shorthand: a collection of TAGS at :rf.scope/same.
    ;; rf2-ru73k6 F1 — `state/normalize-tag-set` treats a LONE vector tag
    ;; (`[:article slug]`) as the ONE tag `#{[:article slug]}` rather than
    ;; silently splitting it into `#{:article slug}` (a scalar set that matches
    ;; nothing); a tag-set (`#{[:article slug]}` / `[[:article slug]]`) lowers
    ;; unchanged. The SAME normalizer the direct `:rf.resource/invalidate-tags`
    ;; `:tags` uses, so a lone vector tag has one meaning across the cache.
    (coll? raw)
    [{:scope              same-scope-marker
      :tags               (state/normalize-tag-set raw)
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
