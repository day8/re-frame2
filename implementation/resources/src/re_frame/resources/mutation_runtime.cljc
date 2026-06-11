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
  (:require [re-frame.resources.state :as state]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- reserved runtime-db path --------------------------------------------
;;
;; Inside runtime-db itself, framework code reads/writes the bare
;; `[:rf.runtime/mutations …]` path; inside a full frame-state projection
;; the mutation subtree is at `[:rf.db/runtime :rf.runtime/mutations …]`.

(def mutations-key
  "The reserved runtime-db key for the mutation-instance subtree
  (`:rf.runtime/mutations`). A map `{<instance-id> <instance>}`, keyed by
  mutation INSTANCE id so concurrent submissions of the same mutation id
  do not clobber each other. Per Spec 016 §Cache home and write authority /
  EP-0003 §Mutations."
  :rf.runtime/mutations)

(defn instances-path
  "Runtime-db-relative path to the mutation-instances map
  `{<instance-id> <instance>}`. Per Spec 016 §Cache home."
  []
  [mutations-key])

(defn instance-path
  "Runtime-db-relative path to a single mutation instance by its instance
  id. Per EP-0003 §Mutations (runtime state keyed by mutation instance id)."
  [instance-id]
  [mutations-key instance-id])

;; ---- durable mutation-instance shape -------------------------------------
;;
;; Plain EDN — host handles (AbortControllers) live in the work-ledger side
;; table keyed by `[frame-id work-id]`, NEVER on the instance. The instance
;; stores FACTS, not derived booleans (`:pending?` / `:settled?` are public
;; derived sub values, computed in the subs layer, never stored).

(def mutation-statuses
  "The closed set of mutation-instance lifecycle states. A mutation is a
  one-shot causal write (NOT a stale-while-revalidate cache), so the
  lifecycle is simpler than a resource entry's: `:idle` (created, not yet
  dispatched — rare; an execute settles directly to `:pending`),
  `:pending` (in flight), `:success` (settled with a result),
  `:error` (settled with a failure envelope). Per EP-0003 §Mutations
  (mutation pending / error / result state)."
  #{:idle :pending :success :error})

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
   ;; reconciliation refetches"). Optimistic itself is DEFERRED — these slots
   ;; are populated descriptively (what the success path touched) so the
   ;; later optimistic slice fills the symmetric rollback half without a
   ;; shape change. nil until the success path runs.
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
      (assoc entry
             :data           shared
             :status         :loaded
             :loaded-at      clock-ms
             :stale-at       stale-at
             :invalidated-at nil
             :refresh-error  nil))
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
  later invalidation can reach it)."
  [entry resource-id populate-value {:keys [clock-ms stale-at tags]}]
  (let [base   (or entry (state/empty-entry resource-id))
        old    (:data base)
        shared (if (and (some? old) (= old populate-value)) old populate-value)]
    (assoc base
           :resource/id    (:resource/id base resource-id)
           :data           shared
           :status         :loaded
           :error          nil
           :refresh-error  nil
           :loaded-at      clock-ms
           :stale-at       stale-at
           :invalidated-at nil
           :tags           (or tags (:tags base) #{}))))

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

(defn target-key-error
  "Build the canonical thrown-error shape (Spec 009 §The thrown-error shape)
  for an invalid mutation patch / populate TARGET scoped key. `arm`
  (`:patches` | `:populates`) names the offending mutation-spec arm."
  [where arm reason target extra]
  (ex-info ":rf.error/mutation-invalid-target"
           (merge {:rf.error/id :rf.error/mutation-invalid-target
                   :where       where
                   :arm         arm
                   :recovery    :fix-mutation-target
                   :reason      reason
                   :target      (pr-str target)}
                  extra)))

(defn validate-target-key!
  "Fail-closed validation of a SINGLE mutation patch / populate TARGET scoped
  key, BEFORE any cache mutation (EP-0003 §Mutations / Spec 016 §Resource
  identity). A target key is the canonical scoped resource key
  `[scope resource-id params]` — the SAME shape `state/scoped-resource-key`
  produces and the read path writes under. Rejects, loudly:

  - a malformed key (not a 3-element `[scope resource-id params]` vector);
  - a non-keyword `resource-id`;
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
  the CANONICALIZED scoped key `[canonical-scope resource-id canonical-params]`
  on success (so the caller writes under the canonical identity, never a
  caller's alternate spelling). Throws `:rf.error/mutation-invalid-target`
  otherwise."
  [target registered-resource? where arm]
  (when-not (and (vector? target) (= 3 (count target)))
    (throw (target-key-error
             where arm
             (str "a mutation " (name arm) " target must be a canonical "
                  "scoped resource key [scope resource-id params] (a "
                  "3-element vector); got " (pr-str target) ". Per EP-0003 "
                  "§Mutations / Spec 016 §Resource identity.")
             target {})))
  (let [[scope resource-id params] target]
    (when-not (keyword? resource-id)
      (throw (target-key-error
               where arm
               (str "a mutation " (name arm) " target's resource id must be "
                    "a keyword; got " (pr-str resource-id) ".")
               target {:resource-id resource-id})))
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
    (when-not (registered-resource? resource-id)
      (throw (target-key-error
               where arm
               (str "a mutation " (name arm) " targets the UNREGISTERED "
                    "resource " (pr-str resource-id) " — a controlled patch / "
                    "populate must target a resource the read path also "
                    "knows, or the seeded / patched entry is unreachable by "
                    "any subscription. Call rf/reg-resource first. Per "
                    "EP-0003 §Mutations.")
               target {:resource-id resource-id})))
    (state/scoped-resource-key scope resource-id params)))

(defn validate-target-map!
  "Fail-closed validation + canonicalization of a WHOLE mutation `:patches` /
  `:populates` target map `{scoped-key value}` BEFORE any cache mutation
  (EP-0003 §Mutations). Validates EVERY target key via `validate-target-key!`
  (so one bad target rejects the whole success-time patch / populate — no
  partial cache mutation) and returns a NEW map re-keyed by the CANONICAL
  scoped key, values preserved. `kind` (`:patches` | `:populates`) names the
  offending arm in the error. A nil / empty map returns itself."
  [target-map registered-resource? kind where]
  (when (seq target-map)
    (reduce-kv
      (fn [m target value]
        (assoc m (validate-target-key! target registered-resource? where kind) value))
      {}
      target-map)))

;; ---- mutation INSTANCE id validation (Spec 016 §Resource identity) --------

(defn instance-id-error
  "Build the canonical thrown-error shape (Spec 009 §The thrown-error shape)
  for a non-serializable mutation INSTANCE id."
  [instance-id where]
  (ex-info ":rf.error/mutation-non-serializable-instance-id"
           {:rf.error/id :rf.error/mutation-non-serializable-instance-id
            :where       where
            :recovery    :fix-instance-id
            :reason      (str "a mutation instance id must be serializable EDN "
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
            :instance-id (pr-str instance-id)}))

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
