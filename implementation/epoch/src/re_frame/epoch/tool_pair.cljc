(ns re-frame.epoch.tool-pair
  "Tool-Pair boundary surfaces — the preconditions, restore-perform, and
  off-box projection helpers behind `restore-epoch`, `replace-app-db!`,
  `projected-record`, and `projected-history`.

  The name (rf2-dga99) covers BOTH halves of the seam: the WRITE-in
  boundary (precondition validators + `perform-restore!` behind the
  Tool-Pair time-travel surfaces) AND the off-box egress READ boundary
  (`projected-record` / `projected-history`, the privacy projection per
  Security.md §Epoch privacy posture). The former `write` name accurately
  named the first half but undersold the projection egress — both are
  Tool-Pair-contract surfaces, so `tool-pair` names the seam honestly.

  Responsibilities:

    * **Precondition validators** — `check-restore-preconditions!` and
      `check-replace-app-db-preconditions!` are pure data transforms
      (no trace emission, no app-db writes); they return
      `{:outcome :ok ...}` or `{:outcome :fail :op <kw> :tags <map>}`
      so the orchestrating facade fn can emit the trace and decide
      flow control.

    * **Schema / handler / version probes** — `failing-schema-paths`,
      `missing-references`, `machine-version-mismatch`. Each is a
      single walk over the recorded db; callers bind the result so
      the failure path walks each substrate exactly once per check
      (rf2-081zk).

    * **Perform-restore** — `perform-restore!` carries out the
      container replace + `:rf.epoch/restored` emit once preconditions
      have passed.

    * **Projected egress** — `projected-record` and `projected-history`
      route every payload-bearing slot through the privacy projection
      for off-box egress (Xray-MCP `watch-epochs`, story / pair
      recorders). That is: the canonical `:frame-state-before` /
      `:frame-state-after` slots (app-db partition elided, runtime-db
      partition default-redacted per EP-0001 ruling #14), the derived
      `:db-before` / `:db-after` app-db projections, `:trigger-event`,
      `:trace-events`, the value-bearing structured `:sub-runs` rows
      (their `:prev-value` / `:value` slots — rf2-at60h), AND the structured
      `:effects` rows' payload-bearing `:args` (fail-closed to `:rf/redacted`
      off-box — rf2-rlt3sv). The value-free `:renders` metadata, the
      `:effects` rows' `:fx-id` / `:outcome` / `:error-trace`, and the
      record-level bookkeeping pass through unchanged. `projected-record`'s
      own docstring is the authoritative per-slot contract.

  Per rf2-0wi86 Phase-2 seam E. The orchestrators `restore-epoch` and
  `replace-app-db!` live in the `re-frame.epoch` facade — they wire
  the precondition check + the trace emission + the perform / listener
  fan-out steps together. Pure-data shape of the preconditions makes
  the orchestrators a four-line case-match."
  (:require [re-frame.elision :as elision]
            [re-frame.epoch.assembly :as assembly]
            [re-frame.epoch.state :as state]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.marks :as marks]
            [re-frame.projection :as projection]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.trace :as trace]))

;; ---- restore failure-mode predicates --------------------------------------

(defn- malli-validate-fn
  "Return the malli validate fn or nil.

  Per rf2-t0hq — CLJS has no runtime `resolve`, so the lookup order on
  CLJS is: late-bind hook then nil. Returning nil is treated as
  soft-pass by callers ('cannot disprove, treat as valid').

  Lookup order matches `re-frame.schemas/default-malli-validate`:
    1. Late-bind hook `:schemas/malli-validate` (published by
       `re-frame.schemas.malli` when loaded).
    2. JVM only — fall back to `(requiring-resolve 'malli.core/validate)`.
    3. Return nil (soft-pass — the schema-validate-ok? caller treats
       a nil validate fn as 'cannot disprove, treat as valid')."
  []
  (or (late-bind/get-fn :schemas/malli-validate)
      #?(:clj  (try (requiring-resolve 'malli.core/validate)
                    (catch Throwable _ nil))
         :cljs nil)))

(defn- registered-app-schemas
  "Return the {path → schema-meta} map registered against the named
  frame, or {}. Per Spec 010 §Per-frame schemas the schema set is
  frame-scoped; restore-epoch validates against the schemas registered
  against the frame the epoch belongs to, not a process-global set."
  [frame-id]
  (if-let [entries (late-bind/get-fn :schemas/frame-schema-entries)]
    (entries frame-id)
    {}))

(defn failing-schema-paths
  "Return a vector of failing schema-paths for `db` against `frame-id`'s
  registered app-schemas. Empty vector means valid — either every
  registered schema accepted the path's value, OR no schemas are
  registered, OR no Malli validator is on the classpath. The latter
  two are soft-pass: we can't disprove validity, so we treat the db
  as valid.

  Single walk over the schema set — callers that previously chained
  `schema-validate-ok?` + `failing-paths-for` paid two walks where one
  suffices. The validity question is `(empty? (failing-schema-paths
  frame-id db))`."
  [frame-id db]
  (let [schemas  (registered-app-schemas frame-id)
        validate (malli-validate-fn)]
    (if (or (empty? schemas) (nil? validate))
      []
      (vec
        (keep (fn [[path meta]]
                (let [schema (:schema meta)
                      v      (get-in db path)]
                  (when-not (try (validate schema v)
                                 (catch #?(:clj Throwable :cljs :default) _ true))
                    path)))
              schemas)))))

(defn- machine-registration
  "Resolve a machine-id against the public machine registry. Per
  Spec 005 §Registration / §Querying machines, machines are event
  handlers whose registration metadata carries `:rf/machine? true`
  and `:rf/machine` (the spec map). Returns the registration map
  when machine-id names a registered machine, nil otherwise.

  Per rf2-ocg1: epoch restore validates against this public surface,
  not against the internal `:head` registrar kind that machines
  never used."
  [machine-id]
  (let [reg (registrar/lookup :event machine-id)]
    (when (:rf/machine? reg)
      reg)))

(defn- snapshot-version
  "Read the recorded snapshot's `:rf/snapshot-version`. Per
  Spec-Schemas §`:rf/machine-snapshot` and Spec 005 §Snapshot shape,
  the canonical slot is `[:meta :rf/snapshot-version]`."
  [snapshot]
  (get-in snapshot [:meta :rf/snapshot-version]))

(defn- spec-snapshot-version
  "Read a resolved machine SPEC map's `:rf/snapshot-version`. Per
  Spec 005 §Snapshot shape — the definition's `:meta :rf/snapshot-version`
  is the canonical slot. Nil-spec-safe."
  [machine]
  (get-in machine [:meta :rf/snapshot-version]))

(defn- singleton-definition-version
  "Read a currently-registered SINGLETON machine definition's
  `:rf/snapshot-version` by snapshot key. The key of a singleton's snapshot
  IS its registered machine-id (a `reg-machine`'d machine outlives no
  per-instance allocation), so the registrar probe resolves it directly.
  Returns nil when `machine-id` is not a registered machine (e.g. a spawned
  actor's instance-id key — those resolve via `:rf/machine-type`, see
  `machine-version-mismatch`)."
  [machine-id]
  (some-> (machine-registration machine-id)
          :rf/machine
          spec-snapshot-version))

;; EP-0001 (rf2-vzld77 / rf2-3aizt1 / rf2-k4xe7u) — the machine snapshots
;; and route slice are DURABLE runtime-db partition state. rf2-vzld77 moved
;; them to the namespaced runtime-db root keys `:rf.runtime/machines` /
;; `:rf.runtime/routing`; the restore-precondition readers below take the
;; runtime-db PARTITION value (`(:rf.db/runtime frame-state)`) and walk those
;; namespaced paths. Pre-fix they read the retired app-db `[:rf/runtime …]`
;; path off the epoch-recorded app-db (which is now empty → silently nil), so
;; the missing-handler-machine + version-drift preconditions no longer fired
;; (rf2-k4xe7u). The epoch now captures the whole frame-state (decision #2),
;; so `check-restore-preconditions!` passes the runtime-db partition here.
(def ^:private machine-snapshots-path
  "Path to the machine-snapshots map inside the runtime-db partition value."
  [:rf.runtime/machines :snapshots])

(def ^:private route-current-id-path
  "Path to the active route's `:id` inside the runtime-db partition value."
  [:rf.runtime/routing :current :id])

(defn failing-runtime-paths
  "Return a vector of failing schema-paths for a candidate `runtime-db`
  value against the framework-owned runtime-db validator — the runtime-db
  sibling of `failing-schema-paths` (which targets the app-db partition).
  Empty vector means valid.

  Per Tool-Pair §Pair-tool writes and Spec 010 §App schemas validate the
  app-db partition only: the runtime-db side of `replace-runtime-db!` /
  `replace-frame-state!` is checked against the framework-owned runtime-db
  validator (`reg-runtime-schema`), NOT the user app-schema set. In the
  reference implementation that validator is the machine-data boundary
  (`:machines/validate-machine-data!`, Spec 005 §Schema validation): it
  walks `[:rf.runtime/machines :snapshots]` and validates each snapshot's
  `:data` against the registered machine's `:data-schema`, emitting its
  own per-snapshot trace and returning a boolean.

  Soft-pass cases (return `[]`):
    * the machines artefact is not on the classpath (hook absent) — no
      runtime-db validator means no runtime-db to disprove;
    * the validator returns true / nil (every snapshot conformed, or none
      carried a `:data-schema`).

  Failure case: the validator returned false — at least one snapshot's
  `:data` failed its `:data-schema`. The validator does not surface the
  failing leaf paths (it emits its own per-snapshot trace naming each), so
  the returned vector names the runtime-db root the failure walked
  (`[:rf.runtime/machines :snapshots]`) — the same shape the app-db
  validator returns for an app-db failure. The validity question is
  `(empty? (failing-runtime-paths frame-id runtime-db))`."
  [frame-id runtime-db]
  (if-let [validate (late-bind/get-fn :machines/validate-machine-data!)]
    (let [result (try (validate runtime-db nil frame-id)
                      (catch #?(:clj Throwable :cljs :default) _ true))]
      (if (or (nil? result) (true? result))
        []
        [machine-snapshots-path]))
    []))

(defn missing-references
  "Walk the recorded runtime-db partition for ids that are no longer present
  in the registrar. Closed v1 surface — `[:rf.runtime/machines :snapshots]`
  (each machine-id must reference a registered machine via the public event
  registry, per Spec 005 §Registration — machines are event handlers tagged
  with `:rf/machine?`) and `:route` (`[:rf.runtime/routing :current :id]` must
  reference a registered :route).

  `runtime-db` is the `:rf.db/runtime` partition of the epoch-recorded
  frame-state (EP-0001 rf2-3aizt1 / rf2-k4xe7u — machine snapshots + route
  slice are runtime-db state, NOT the retired app-db `[:rf/runtime …]` path).

  Per rf2-ocg1: machine lookup goes through the event registry, NOT
  the internal `:head` registrar kind. The latter is unrelated to
  the public machine contract.

  Per rf2-a2sn1: a DYNAMICALLY-SPAWNED actor carries no per-instance
  registrar entry — its liveness is derived from its (revertible)
  snapshot. Such a snapshot is a VALID restore target iff its TYPE still
  resolves (registered TYPE keyword, or inline `:definition` carried on
  the snapshot). The `:machines/actor-resolvable?` hook makes that
  determination from the runtime-db partition (it reads
  `[:rf.runtime/machines :snapshots <id>]`); consult it before flagging a
  snapshot whose id is not directly registered. A SINGLETON whose
  registration was cleared (a `reg-machine`'d machine, no `:rf/machine-type`
  on its snapshot) still surfaces as missing — `actor-resolvable?` returns
  false for it. Absent the machines artefact the hook is nil and the prior
  registrar-only check stands.

  Returns a vector of {:kind <kind> :id <id>} entries. Empty when
  every reference resolves."
  [runtime-db]
  (let [actor-resolvable? (late-bind/get-fn :machines/actor-resolvable?)
        ;; Machines under [:rf.runtime/machines :snapshots]: a singleton
        ;; references a registered machine (`:rf/machine? true`, per Spec
        ;; 005 §Registration); a spawned actor (rf2-a2sn1) has no
        ;; per-instance registration but is restorable when its
        ;; `:rf/machine-type` resolves through `actor-resolvable?` (which
        ;; reads the runtime-db partition).
        missing-machines
        (for [[machine-id _snapshot] (get-in runtime-db machine-snapshots-path)
              :when (and (not (machine-registration machine-id))
                         (not (and actor-resolvable?
                                   (actor-resolvable? runtime-db machine-id))))]
          {:kind :machine :id machine-id})
        ;; Active route
        missing-route
        (when-let [route-id (get-in runtime-db route-current-id-path)]
          (when-not (registrar/lookup :route route-id)
            [{:kind :route :id route-id}]))]
    (vec (concat missing-machines missing-route))))

(defn- current-definition-version
  "Resolve the CURRENT definition `:rf/snapshot-version` for one recorded
  snapshot the SAME way dispatch resolves the live spec (rf2-rlt3sv):

    - SINGLETON snapshots — the snapshot key IS the registered machine-id, so
      `singleton-definition-version` resolves the live spec by key.
    - SPAWNED-ACTOR snapshots — the key is an instance-id with NO per-instance
      registration; the actor's TYPE rides the snapshot under `:rf/machine-type`
      (a registered TYPE keyword OR an inline `:definition` spec map, per Spec
      005 §Reserved snapshot-internal keys). The late-bound
      `:machines/spec-from-snapshot` hook (published by `re-frame.machines`,
      body `resolver/spec-from-snapshot`) resolves the same spec the lazy
      actor-handler resolver materialises on dispatch; its
      `[:meta :rf/snapshot-version]` is the spawned actor's current version.

  Returns `{:current <int-or-nil> :type <type-ref-or-nil>}`. `:current` is the
  resolved version (nil when no definition resolves — a cleared TYPE is a
  MISSING reference, caught upstream by `missing-references`, not a version
  drift). `:type` is the spawned actor's `:rf/machine-type` (keyword or inline
  map) when the snapshot carried one, nil for a singleton — surfaced so the
  drift trace can identify the actor's TYPE as well as its instance id."
  [machine-id snapshot]
  (let [singleton (singleton-definition-version machine-id)]
    (if (some? singleton)
      ;; The snapshot key names a registered machine — a singleton.
      {:current singleton :type nil}
      ;; No registered machine under the key: a spawned actor whose TYPE rides
      ;; the snapshot. Resolve the spec the dispatch-time way.
      (let [type-ref      (:rf/machine-type snapshot)
            from-snapshot (late-bind/get-fn :machines/spec-from-snapshot)
            spec          (when from-snapshot (from-snapshot snapshot))]
        {:current (spec-snapshot-version spec)
         :type    type-ref}))))

(defn machine-version-mismatch
  "Walk the recorded runtime-db partition's `[:rf.runtime/machines :snapshots]`
  for snapshot version drift. The recorded snapshot may carry
  `:rf/snapshot-version` under `:meta`; the CURRENT machine definition carries
  `:rf/snapshot-version` under its own `:meta`. When they differ, return the
  first mismatch as
  `{:machine-id <id> :machine-type <type-or-nil> :recorded <int> :current <int>}`.
  nil when no mismatch is found.

  `runtime-db` is the `:rf.db/runtime` partition of the epoch-recorded
  frame-state (EP-0001 rf2-3aizt1 / rf2-k4xe7u).

  The current definition is resolved the SAME way dispatch resolves the live
  spec (rf2-rlt3sv): a SINGLETON by its snapshot key (the key IS the registered
  machine-id), a SPAWNED ACTOR by its snapshot's `:rf/machine-type` (registered
  TYPE keyword or inline `:definition` map — `current-definition-version`).
  Before the fix the version probe compared the snapshot KEY against the
  registrar for EVERY snapshot, so a spawned actor's instance-id key never
  resolved and a hot-reloaded actor TYPE's version drift silently passed — an
  older, incompatible spawned-actor snapshot could be installed by
  `restore-epoch` reporting success.

  Per rf2-ocg1: the recorded version is read through the public Spec 005
  §Snapshot shape contract — the snapshot's `[:meta :rf/snapshot-version]`;
  the current version through the resolved spec's `[:meta :rf/snapshot-version]`."
  [runtime-db]
  (some (fn [[machine-id snapshot]]
          (let [recorded (snapshot-version snapshot)]
            (when (some? recorded)
              (let [{:keys [current type]} (current-definition-version machine-id snapshot)]
                (when (and (some? current) (not= recorded current))
                  {:machine-id   machine-id
                   :machine-type type
                   :recorded     recorded
                   :current      current})))))
        (get-in runtime-db machine-snapshots-path)))

;; ---- shared precondition helpers ------------------------------------------

(defn- find-epoch-in
  "Search a resolved history vector for the record matching `epoch-id`.
  Caller has already paid the `@histories` deref — `check-restore-
  preconditions!` reads history once at the top and reuses the vector
  for both the lookup and the `:history-size` count on the
  unknown-epoch failure path (rf2-3g7x3 — was two derefs)."
  [history epoch-id]
  (some (fn [record] (when (= epoch-id (:epoch-id record)) record))
        history))

(defn emit-precondition-failure!
  [operation tags]
  (trace/emit-error! operation
                     (assoc tags :recovery :no-recovery)))

(defn- drain-in-flight?
  "True when `frame-record`'s router is mid-drain (sync or async).
  Shared by every precondition path that must refuse to write to
  `app-db` while a cascade is being processed."
  [frame-record]
  (let [router (:router frame-record)
        r      (when router @router)]
    (boolean (and r (or (:in-drain? r) (:in-sync-drain? r))))))

(defn- frame-exists-or-fail
  "Resolve `frame-id` to its `frame-record` or yield the canonical
  no-such-handler precondition-failure result. Returns
  `{:outcome :ok :frame-record <record>}` or
  `{:outcome :fail :op :rf.error/no-such-handler
    :tags {:kind :frame :frame frame-id}}`. Shared by every Tool-Pair /
  time-travel write surface so the no-such-handler tag shape stays
  canonical."
  [frame-id]
  (if-let [frame-record (frame/frame frame-id)]
    {:outcome :ok :frame-record frame-record}
    {:outcome :fail
     :op      :rf.error/no-such-handler
     :tags    {:kind  :frame
               :frame frame-id}}))

;; ---- restore preconditions + perform --------------------------------------

(defn check-restore-preconditions!
  "Validate the seven documented preconditions for restoring `frame-id`
  to `epoch-id`. Returns a result map:

    {:outcome :ok :epoch <epoch>}
                 — all checks passed; `:epoch` is the resolved history
                   record whose `:db-after` is the restore target.
    {:outcome :fail :op <kw> :tags <map>}
                 — first failing check; `:op` is the trace operation
                   the caller must emit, `:tags` are its tags. No
                   trace events are emitted from inside this helper —
                   emission is the caller's job so the
                   precondition test stays a pure data check.

  Failure modes preserve the exact operation keywords and tag shapes
  the public surface has always emitted (see `restore-epoch`'s
  docstring for the catalogue)."
  [frame-id epoch-id]
  (let [frame-result (frame-exists-or-fail frame-id)]
    (cond
      ;; (1) Frame registered?
      (= :fail (:outcome frame-result))
      frame-result

      ;; (2) In-flight drain?
      (drain-in-flight? (:frame-record frame-result))
      {:outcome :fail
       :op      :rf.epoch/restore-during-drain
       :tags    {:frame    frame-id
                 :epoch-id epoch-id}}

      :else
      (let [history (state/history-for frame-id)
            epoch   (find-epoch-in history epoch-id)]
        (cond
          ;; (3) Epoch present in current history?
          (nil? epoch)
          {:outcome :fail
           :op      :rf.epoch/restore-unknown-epoch
           :tags    {:frame        frame-id
                     :epoch-id     epoch-id
                     :history-size (count history)}}

          ;; (3a) Halted-cascade target? Per rf2-v0jwt: an epoch whose
          ;; :outcome is not :ok records partial state the cascade
          ;; never settled to, so it is not a valid restore target.
          ;; Refuse before the schema / handler / version checks so
          ;; the failure surfaces with the actual halt context, not
          ;; a downstream consequence of the partial db.
          (not= :ok (get epoch :outcome :ok))
          {:outcome :fail
           :op      :rf.epoch/restore-non-ok-record
           :tags    {:frame       frame-id
                     :epoch-id    epoch-id
                     :outcome     (:outcome epoch)
                     :halt-reason (:halt-reason epoch)}}

          :else
          (let [;; EP-0001 (rf2-3aizt1, decision #2): the canonical restore
                ;; target is the whole frame-state. The app-db partition
                ;; (`:db-after` is its retained projection — equal to
                ;; `(:rf.db/app frame-state-after)`) feeds the schema check;
                ;; the runtime-db partition feeds the machine / route
                ;; reference + version checks (rf2-k4xe7u — snapshots + route
                ;; slice are runtime-db state). Read both off the canonical
                ;; `:frame-state-after`, falling back to the `:db-after`
                ;; projection for the app-db slice (always present alongside).
                frame-state-target (:frame-state-after epoch)
                db-target          (or (get frame-state-target frame/app-partition-key)
                                       (:db-after epoch))
                runtime-target     (get frame-state-target frame/runtime-partition-key)]
            ;; Each helper is called once and its result bound, so the
            ;; failure path walks the recorded db / schema set / machine
            ;; map exactly once per check (rf2-081zk).
            (if-let [failing-paths (seq (failing-schema-paths frame-id db-target))]
              ;; (4) Schema mismatch?
              ;; Per Spec 010 §Schema digest + Tool-Pair §Time-travel:
              ;; the trace carries both the digest pinned on the
              ;; epoch record (recorded) and the current frame's
              ;; live digest, so pair tools can pinpoint *what
              ;; changed* about the schema set, not merely *that*
              ;; it changed.
              {:outcome :fail
               :op      :rf.epoch/restore-schema-mismatch
               :tags    {:frame                  frame-id
                         :epoch-id               epoch-id
                         :schema-digest-recorded (:schema-digest epoch)
                         :schema-digest-current  (assembly/current-schema-digest frame-id)
                         :failing-paths          (vec failing-paths)}}

              (if-let [missing (seq (missing-references runtime-target))]
                ;; (5) Missing handler referenced from runtime-db?
                {:outcome :fail
                 :op      :rf.epoch/restore-missing-handler
                 :tags    {:frame    frame-id
                           :epoch-id epoch-id
                           :missing  (vec missing)}}

                (if-let [{:keys [machine-id machine-type recorded current]} (machine-version-mismatch runtime-target)]
                  ;; (6) Machine snapshot version drift?
                  ;; rf2-rlt3sv: `:machine-type` identifies a SPAWNED actor's
                  ;; TYPE (keyword or inline-definition map) alongside its
                  ;; instance `:machine-id`; nil/omitted for a singleton whose
                  ;; key is its own type. Spawned-actor drift is now caught:
                  ;; the current version resolves via `:rf/machine-type`, not
                  ;; the unregistered instance-id key.
                  {:outcome :fail
                   :op      :rf.epoch/restore-version-mismatch
                   :tags    (cond-> {:frame            frame-id
                                     :epoch-id         epoch-id
                                     :machine-id       machine-id
                                     :version-recorded recorded
                                     :version-current  current}
                              (some? machine-type) (assoc :machine-type machine-type))}

                  {:outcome :ok :epoch epoch})))))))))

;; ---- write-boundary liveness guard (rf2-7i872) ----------------------------
;;
;; Precondition validation (`check-restore-preconditions!` /
;; `check-replace-app-db-preconditions!`) resolves the frame, but a frame
;; can be destroyed in the window BETWEEN the precondition pass and the
;; actual container write (the validate-then-destroy race — most often a
;; tool gesture interleaving with the owning component's teardown). Once
;; destroyed, `frame/app-db-container` returns nil and the choke-point
;; `adapter/replace-container!` no-ops the write with a
;; `:rf.error/write-after-destroy` trace (`adapter.cljc`). Per Tool-Pair
;; §Surface behaviour against destroyed frames the mutating surfaces must
;; report this as the SAME structural failure a frame-miss caught at
;; validate-time produces (`:rf.error/no-such-handler`, kind `:frame`,
;; returns `false`) — NOT a synthetic success. This helper resolves the
;; container at the write boundary and yields the canonical no-such-handler
;; failure when it has disappeared, so `perform-restore!` /
;; `perform-replace-app-db!` can bail BEFORE emitting success, recording a
;; synthetic epoch, or fanning out to listeners.

(defn live-container-or-fail
  "Resolve `frame-id`'s app-db container at the write boundary. Returns
  `{:outcome :ok :container <container>}` when the frame is still live, or
  the canonical no-such-handler precondition-failure result
  (`{:outcome :fail :op :rf.error/no-such-handler :tags {:kind :frame
  :frame frame-id}}`) when the frame was destroyed between precondition
  validation and now (the validate-then-destroy race, rf2-7i872). Mirrors
  `frame-exists-or-fail`'s failure shape so the destroyed-frame write race
  surfaces identically to a frame-miss caught at validate-time."
  [frame-id]
  (if-let [container (frame/app-db-container frame-id)]
    {:outcome :ok :container container}
    {:outcome :fail
     :op      :rf.error/no-such-handler
     :tags    {:kind  :frame
               :frame frame-id}}))

;; ---- runtime-db subsystem reconcile on restore (rf2-7r5mc2) ---------------
;;
;; Epoch restore installs the captured frame-state WHOLESALE — it does not run
;; ordinary `:db`-effect semantics, so a runtime SUBSYSTEM whose durable
;; snapshot is not safe to install verbatim (its transient host world has moved
;; on) must be reconciled against the live world BEFORE the atomic install.
;; This is the SAME install path SSR hydration uses (Spec 016 §Restore and
;; replay): hydration reconciles the SERVER PROJECTION via the
;; `:resources/hydrate-runtime-db` hook; restore reconciles the UNPROJECTED
;; captured snapshot via the `:resources/reconcile-on-restore` hook (which
;; ALSO settles mid-flight entries to last-stable + marks restored non-terminal
;; work-ledger rows dangling — the two settles the wire projection had already
;; done but the unprojected snapshot still carries).
;;
;; Late-bound so epoch never statically depends on the optional Resources
;; artefact — absent the artefact the hook is nil and the runtime-db installs
;; verbatim (the pre-rf2-7r5mc2 behaviour, correct for an app with no resources).

(defn reconcile-runtime-db-on-restore
  "Reconcile the runtime-db partition of a `frame-state` value about to be
  installed by `perform-restore!`, consulting the late-bound
  `:resources/reconcile-on-restore` hook (Spec 016 §Restore and replay). Returns
  the frame-state with its `:rf.db/runtime` partition reconciled — or
  `frame-state` unchanged when no resources artefact is loaded (the hook is nil),
  or when the frame-state carries no runtime-db partition (a legacy / app-db-only
  record). The runtime-db value is passed with the carried `frame-id` so the
  reconcile can stamp its trace. Other runtime subsystems (machines, routing)
  reconcile their own snapshots through their own contracts; this seam is the
  resources-first extension point, mirroring SSR hydration's single
  `:resources/hydrate-runtime-db` consult.

  Per rf2-obi8rr the reconcile is invoked with `{:defer-traces? true}` so it does
  NOT emit its `:rf.resource/restored` / `:rf.resource/owner-released` success
  rows inline — the reconcile runs BEFORE the atomic install, which can still
  fail (a destroyed-frame install returns nil). The deferred trace intents ride
  back as metadata on the reconciled runtime-db; `perform-restore!` emits them via
  `:resources/commit-restore-reconcile!` only AFTER a successful install, so a
  failed restore leaks no resource success traces."
  [frame-id frame-state]
  (if-let [reconcile (late-bind/get-fn :resources/reconcile-on-restore)]
    (if (contains? frame-state frame/runtime-partition-key)
      (update frame-state frame/runtime-partition-key
              (fn [rdb] (when (some? rdb) (reconcile rdb frame-id {:defer-traces? true}))))
      frame-state)
    frame-state))

(defn commit-resources-restore-traces!
  "Emit the resources restore-reconcile trace rows DEFERRED by
  `reconcile-runtime-db-on-restore` (rf2-obi8rr), consulting the late-bound
  `:resources/commit-restore-reconcile!` hook with the reconciled runtime-db
  partition of `frame-state`. Invoked by `perform-restore!` ONLY AFTER a
  successful `replace-frame-state!` install, so the `:rf.resource/restored` /
  `:rf.resource/owner-released` success rows fire exactly once the restore has
  truly installed — never for a destroyed-frame install that wrote nothing.
  No-op when no resources artefact is loaded (hook nil), when the frame-state
  carries no runtime-db partition, or when the runtime-db carries no deferred
  intents (a resource-free restore). Returns nil."
  [frame-state]
  (when-let [commit (late-bind/get-fn :resources/commit-restore-reconcile!)]
    (when-let [rdb (get frame-state frame/runtime-partition-key)]
      (commit rdb)))
  nil)

(defn perform-restore!
  "Carry out the actual frame-state rewind once preconditions have passed.
  Replaces the frame's whole frame-state with `epoch`'s `:frame-state-after`
  (BOTH partitions — app-db AND runtime-db) and emits `:rf.epoch/restored`.
  Returns `true` on a real write.

  EP-0001 (rf2-3aizt1, decisions #2 + #9): restore rewinds to the canonical
  `:frame-state-after` via `replace-frame-state!`, reviving machine
  snapshots, the route slice, elision declarations, and SSR metadata
  (runtime-db state) — not just the app-db partition. Without this, a
  rewind past a machine spawn / destroy left the actor's snapshot un-reverted
  (the Goal-2 revertibility leak the actor-revertibility tests pin). Falls
  back to the retained `:db-after` projection (app-db only) for legacy
  records that carry no `:frame-state-after` — those install runtime-db nil,
  matching the pre-EP behaviour.

  Per rf2-7i872 (validate-then-destroy race): re-resolves the container at
  the write boundary via `live-container-or-fail`. If the frame was
  destroyed between the precondition pass and now, the container is nil and
  `replace-frame-state!` would silently no-op — so instead of emitting
  `:rf.epoch/restored` and returning `true` (a FALSE success), this emits
  the canonical `:rf.error/no-such-handler` (kind `:frame`) failure trace
  and returns `false`, matching the destroyed-frame contract.

  Per rf2-s93722 (post-liveness teardown race): the liveness check above
  closes only HALF the window — a frame destroyed AFTER `live-container-or-
  fail` passes but BEFORE `replace-frame-state!` actually writes still slips
  through. `frame/replace-frame-state!` returns `nil` for a destroyed frame
  (a non-nil — possibly EMPTY — changed-key-set for a live frame, even a
  no-op write), so we check that return: a `nil` return is the destroyed-
  frame signal, surfaced as the same `:rf.error/no-such-handler` (kind
  `:frame`) failure / `false` return BEFORE any success telemetry.

  Per rf2-7r5mc2 (resources runtime-db reconcile on restore): a runtime-db
  partition carrying a runtime SUBSYSTEM whose durable snapshot is not safe to
  install verbatim must be reconciled against the live transient world BEFORE
  the install (Spec 016 §Restore and replay — restore shares the SSR-hydration
  install path). Resources is the first such subsystem: `reconcile-runtime-db-on-
  restore` consults the late-bound `:resources/reconcile-on-restore` hook, which
  settles mid-flight `:loading` / `:fetching` entries to last-stable, clears each
  entry's vanished `:current-work`, orphans SSR / stale-nav owners, recomputes
  the reverse indexes from `:entries`, and records restored non-terminal
  work-ledger rows as dangling so a pre-restore in-flight reply is suppressed.
  No-op (passes the runtime-db through) when no resources artefact is loaded.

  Per rf2-obi8rr (defer restore trace rows until install succeeds): the resources
  reconcile above runs BEFORE the atomic install but its `:rf.resource/restored` /
  `:rf.resource/owner-released` SUCCESS trace rows must fire only once the restore
  TRULY installs. So the reconcile defers those rows (it is called with
  `:defer-traces? true`, riding the intents back as metadata); they are emitted
  via `commit-resources-restore-traces!` ONLY on the success branch below — never
  for a destroyed-frame install that returns nil and writes nothing."
  [frame-id epoch]
  (let [{:keys [outcome op tags]} (live-container-or-fail frame-id)]
    (if (= :fail outcome)
      (do (emit-precondition-failure! op tags)
          false)
      (let [frame-state-target
            (or (:frame-state-after epoch)
                ;; Legacy / synthetic record with only the app-db projection:
                ;; install it as the app-db partition; runtime-db installs nil.
                {frame/app-partition-key (:db-after epoch)})
            ;; rf2-7r5mc2: reconcile the runtime-db partition's runtime
            ;; subsystems (Resources, first writer) BEFORE the atomic install,
            ;; the same way SSR hydration reconciles its installed slice — so a
            ;; mid-flight captured snapshot does not install stranded
            ;; `:loading` / `:fetching` entries pointing at vanished attempts,
            ;; and a pre-restore reply cannot write stale data into a restored
            ;; entry.
            frame-state-target (reconcile-runtime-db-on-restore frame-id frame-state-target)
            ;; EP-0001 (rf2-3aizt1): write the WHOLE frame-state — both
            ;; partitions in ONE atomic install through the one physical
            ;; frame-state container. `frame/app-db-container` /
            ;; `runtime-db-container` are READ-ONLY projections, so the write
            ;; goes through `replace-frame-state!`.
            ;; rf2-s93722: capture the return — `nil` means the frame was
            ;; destroyed in the post-liveness window (no write happened); a
            ;; non-nil changed-key-set (even empty) means the write landed.
            changed (frame/replace-frame-state! frame-id frame-state-target)]
        (if (nil? changed)
          (do (emit-precondition-failure! :rf.error/no-such-handler
                                          {:kind :frame :frame frame-id})
              false)
          (do (trace/emit! :rf.epoch :rf.epoch/restored
                           {:frame    frame-id
                            :epoch-id (:epoch-id epoch)})
              ;; rf2-obi8rr — the install succeeded, so it is now safe to emit
              ;; the resources restore-reconcile success rows the reconcile
              ;; deferred. A destroyed-frame install (nil branch above) returns
              ;; before this point, so those rows never fire for a no-op write.
              (commit-resources-restore-traces! frame-state-target)
              true))))))

;; ---- replace-app-db! preconditions ----------------------------------------

(defn check-replace-app-db-preconditions!
  "Validate the three documented preconditions for `replace-app-db!`.
  Returns `{:outcome :ok}` when all checks pass, otherwise
  `{:outcome :fail :op <kw> :tags <map>}` matching the precondition-
  failure shape of `check-restore-preconditions!`. Pure data — no
  trace events emitted from here; emission is the caller's job."
  [frame-id new-db]
  (let [frame-result (frame-exists-or-fail frame-id)]
    (cond
      ;; (1) Frame registered?
      (= :fail (:outcome frame-result))
      frame-result

      ;; (2) In-flight drain?
      (drain-in-flight? (:frame-record frame-result))
      {:outcome :fail
       :op      :rf.epoch/replace-app-db-during-drain
       :tags    {:frame frame-id}}

      :else
      ;; (3) Schema mismatch? Single walk — `failing-schema-paths`
      ;; returns the failing paths (or [] for the valid / soft-pass
      ;; cases), folding what was previously a two-helper / two-walk
      ;; chain into one.
      (let [failing (failing-schema-paths frame-id new-db)]
        (if (seq failing)
          {:outcome :fail
           :op      :rf.epoch/replace-app-db-schema-mismatch
           :tags    {:frame         frame-id
                     :failing-paths failing}}
          {:outcome :ok})))))

(defn check-replace-runtime-db-preconditions!
  "Validate the three documented preconditions for `replace-runtime-db!`
  (the runtime-db sibling of `check-replace-app-db-preconditions!`).
  Returns `{:outcome :ok}` when all checks pass, otherwise
  `{:outcome :fail :op <kw> :tags <map>}`. Pure data — no trace events
  emitted from here; emission is the caller's job.

  Per Tool-Pair §Pair-tool writes the four injection mutators share the
  identical failure-mode shape — the same `:rf.epoch/replace-app-db-*`
  trace ops cover all four (Spec 009 §Trace events explicitly lists
  `replace-runtime-db!` / `replace-frame-state!` under those ops). The
  only difference is the schema check targets the runtime-db partition
  against the framework-owned runtime-db validator (`failing-runtime-
  paths`), NOT the user app-schema set."
  [frame-id new-runtime-db]
  (let [frame-result (frame-exists-or-fail frame-id)]
    (cond
      ;; (1) Frame registered?
      (= :fail (:outcome frame-result))
      frame-result

      ;; (2) In-flight drain?
      (drain-in-flight? (:frame-record frame-result))
      {:outcome :fail
       :op      :rf.epoch/replace-app-db-during-drain
       :tags    {:frame frame-id}}

      :else
      ;; (3) Runtime-db schema mismatch? Single walk against the
      ;; framework-owned runtime-db validator (Tool-Pair §Pair-tool writes
      ;; — the runtime-db side is checked against `reg-runtime-schema`, not
      ;; the app-schema set).
      (let [failing (failing-runtime-paths frame-id new-runtime-db)]
        (if (seq failing)
          {:outcome :fail
           :op      :rf.epoch/replace-app-db-schema-mismatch
           :tags    {:frame         frame-id
                     :failing-paths failing}}
          {:outcome :ok})))))

(defn check-replace-frame-state-preconditions!
  "Validate the three documented preconditions for `replace-frame-state!`
  (the full-frame sibling of `check-replace-app-db-preconditions!`).
  Returns `{:outcome :ok}` when all checks pass, otherwise
  `{:outcome :fail :op <kw> :tags <map>}`. Pure data — no trace events
  emitted from here; emission is the caller's job.

  `frame-state` carries BOTH partitions (`{:rf.db/app … :rf.db/runtime …}`),
  so the schema check validates the app-db partition against the frame's
  app-schema set AND the runtime-db partition against the framework-owned
  runtime-db validator, surfacing the union of failing paths (Tool-Pair
  §Pair-tool writes — `replace-frame-state!` replaces both atomically, so
  either partition's schema failure rejects the whole install)."
  [frame-id frame-state]
  (let [frame-result (frame-exists-or-fail frame-id)]
    (cond
      ;; (1) Frame registered?
      (= :fail (:outcome frame-result))
      frame-result

      ;; (2) In-flight drain?
      (drain-in-flight? (:frame-record frame-result))
      {:outcome :fail
       :op      :rf.epoch/replace-app-db-during-drain
       :tags    {:frame frame-id}}

      :else
      ;; (3) Schema mismatch? Both partitions are validated — the app-db
      ;; side against the app-schema set, the runtime-db side against the
      ;; framework-owned runtime-db validator. A failure on EITHER rejects
      ;; the whole atomic install; the trace carries the union of failing
      ;; paths.
      (let [app-db      (get frame-state frame/app-partition-key)
            runtime-db  (get frame-state frame/runtime-partition-key)
            failing     (into (vec (failing-schema-paths frame-id app-db))
                              (failing-runtime-paths frame-id runtime-db))]
        (if (seq failing)
          {:outcome :fail
           :op      :rf.epoch/replace-app-db-schema-mismatch
           :tags    {:frame         frame-id
                     :failing-paths failing}}
          {:outcome :ok})))))

;; ---- projected egress -----------------------------------------------------
;;
;; Per Security.md §Epoch privacy posture, EP-0015 §15 (Epoch Redaction) +
;; open-issue 6 (RULED), and rf2-mrsck: the framework's single normative
;; projection emission site for off-box epoch egress. The in-process ring
;; buffer (`epoch-history`) and `register-epoch-listener!` listener fan-out
;; deliver RAW records — restore-epoch and on-box devtools (Xray diff,
;; REPL inspection) need them, and post-EP-0010 those raw records are
;; causal replay material. Tools that egress an epoch record across a
;; process boundary (Xray-MCP `watch-epochs`, story / pair recorders,
;; hosted forwarders) MUST route through `projected-record` first.
;;
;; FRAME/PROFILE PROJECTION (EP-0015 §15). Each tree-shaped payload slot is
;; projected through `re-frame.projection/project-egress` under the
;; `:rf.egress/off-box-observability` profile — the named export boundary
;; for hosted-monitoring / log-shipper / recorder egress (EP-0015 §10
;; default-behaviour table: redact sensitive, elide large, omit digests).
;; `project-egress` resolves that profile to the `:rf.size/*` opt-set
;; `elide-wire-value` consumes; the trusted-local plain opts
;; (`:include-sensitive?` / `:include-large?`) compose on top as explicit
;; `:rf.size/*` overrides (the override wins). The epoch record is not one
;; of `project-egress`'s `:rf.observe/*` record kinds, so each tree slot is
;; handed to `project-egress` as a KINDLESS tree value (the direct-read
;; path), which delegates to `elide-wire-value` against the frame's
;; classification. This replaces the prior ad-hoc `elide-wire-value` calls
;; with the frame/profile projection primitive (EP-0015 bead-plan item 10).
;;
;; Two slots are value-bearing in ways the original four-slot model missed:
;;
;;   - The CANONICAL frame-state slots `:frame-state-before` /
;;     `:frame-state-after` (EP-0001 rf2-3aizt1 decision #2 + ruling #14):
;;     their `:rf.db/app` partition is projected through the same
;;     frame/profile walk the `:db-*` projections receive, while their
;;     `:rf.db/runtime` partition is DEFAULT-REDACTED to `:rf/redacted`
;;     off-box (see `project-frame-state-slot`).
;;   - The structured `:sub-runs` rows (rf2-at60h): each carries the
;;     sub's computed `:prev-value` / `:value`, so the rows DO carry
;;     app-db material and the whole-output `:large?` case is projected
;;     through the `:rf.size/large-elided` marker (see
;;     `elide-sub-runs-slot`). Their non-value metadata (`:sub-id`,
;;     `:query-v`, `:value-changed?`, `:cascade?`, `:cause-sub`,
;;     `:cause-event-id`) passes through.
;;
;; The remaining full-value slots — the derived `:db-before` /
;; `:db-after` app-db projections, `:trigger-event`, and `:trace-events`
;; — also route through `project-egress`. The structured `:effects`
;; rows carry payload-bearing `:args` (raw fx-handler arguments) that fail
;; closed to `:rf/redacted` off-box (rf2-rlt3sv, `elide-effects-slot`); the
;; `:renders` slot carries no app-db material (render-keys + timing +
;; cause), so the projection leaves it as-is. The record-level bookkeeping
;; (`:epoch-id`,
;; `:frame`, `:committed-at`, `:event-id`, `:outcome`, `:halt-reason`,
;; `:schema-digest`, `:rf.epoch/sensitive?`,
;; `:rf.epoch/redacted-modified-paths-count`) is structurally
;; non-sensitive and passes through. `projected-record`'s docstring is
;; the authoritative per-slot contract.
;;
;; APP-SUPPLIED `:redact-fn` ADVANCED OVERRIDE (EP-0015 §15 + open-issue 6,
;; RULED). After the frame/profile projection lands, `projected-record`
;; applies the installed `:redact-fn` (`assembly/apply-redact-fn`) to the
;; PROJECTED record — the rare advanced escape for material the
;; schema-driven projection cannot prove. It runs ONLY here, on the off-box
;; egress copy; the ring stays raw, so it can never affect `restore-epoch`
;; fidelity (the §15 hazard is gone by construction).
;;
;; Per-tool reimplementation of the projection is prohibited (the
;; same posture as the wire-elision walker). New egress tools call
;; `projected-record` and trust the contract.

(def ^:private egress-profile
  "The named export boundary for epoch off-box egress (EP-0015 §10). The
  consumers are hosted monitoring / log shippers / Story / pair recorders /
  Xray-MCP `watch-epochs`; `:rf.egress/off-box-observability` is the
  matching profile (redact sensitive, elide large, omit digests)."
  :rf.egress/off-box-observability)

(defn- egress-opts
  "Build the `project-egress` opts map from a `projected-record` egress
  opts map (rf2-5w06uu). The `:rf.egress/off-box-observability` profile is
  the floor; `:include-sensitive?` / `:include-large?` default `false` (the
  off-box safe path) and, when a trusted-local caller opts them back in,
  compose on top as explicit `:rf.size/*` overrides (the override wins —
  see `re-frame.projection/resolve-elision-opts`). The record frame is
  stamped so schema-declared sensitive / large paths (keyed by absolute
  app-db path) match the projected value."
  [frame-id {:keys [include-sensitive? include-large?]}]
  {:rf.egress/profile          egress-profile
   :frame                      frame-id
   :rf.size/include-sensitive? (boolean include-sensitive?)
   :rf.size/include-large?     (boolean include-large?)})

(defn- project-payload-slot
  "Project one payload slot through `project-egress` under the
  `:rf.egress/off-box-observability` profile, rooted at the named frame.
  Off-box defaults (`:include-sensitive? false`, `:include-large? false`)
  hold unless `opts` opts back in (rf2-5w06uu). The epoch record is not a
  `:rf.observe/*` record kind, so the slot VALUE is projected as a kindless
  tree (the direct-read path → `elide-wire-value` against the frame's
  classification). Returns the projected value; `nil` slots are preserved
  as nil (a halted-destroy record's `:db-before` / `:db-after` are nil per
  rf2-v0jwt; the schema admits the absent / nil slot — the projection
  MUST NOT fabricate a value)."
  [v frame-id opts]
  (when (some? v)
    (projection/project-egress v (egress-opts frame-id opts))))

(defn- project-frame-state-slot
  "Project a `:frame-state-before` / `:frame-state-after` slot for off-box
  egress (EP-0001 rf2-3aizt1, Mike ruling #14). The frame-state value is
  `{:rf.db/app <app-db> :rf.db/runtime <runtime-db>}`; the two partitions
  egress under DIFFERENT default policies:

    - `:rf.db/app` — the application state. Projected through the
      frame/profile `project-egress` walk (sensitive paths → `:rf/redacted`,
      large paths → markers), the SAME projection the `:db-before` /
      `:db-after` app-db projections receive. `opts` `:include-sensitive?` /
      `:include-large?` opt the app-db partition's privacy / size posture
      back in (rf2-5w06uu).

    - `:rf.db/runtime` — the framework runtime-db. REDACTED by default off-box
      (Mike ruling #14 — Spec 011 §Off-box redaction + Security.md §Epoch
      privacy posture): the runtime-db side is substituted with the
      `:rf/redacted` sentinel rather than walked, so machine snapshots /
      route slice / SSR metadata do not egress to AI / log channels by
      default. The runtime-db partition boundary is ORTHOGONAL to the
      app-db `:include-sensitive?` / `:include-large?` opt-ins — those do
      NOT lift it (the rf2-5w06uu bypass). A TRUSTED-LOCAL caller that
      genuinely needs runtime-db diagnostics opts in explicitly with
      `:include-runtime-db? true`; the runtime-db value is then projected
      through the same frame/profile walk (its own per-slot sensitive /
      large declarations still apply) rather than redacted whole.

  Nil-preserving (a halted-destroy record may carry a nil frame-state slot;
  the projection MUST NOT fabricate a value)."
  [fs frame-id {:keys [include-runtime-db?] :as opts}]
  (when (some? fs)
    (cond-> fs
      (contains? fs :rf.db/app)
      (update :rf.db/app projection/project-egress (egress-opts frame-id opts))
      ;; Default-redact the runtime-db side off-box (ruling #14). The
      ;; trusted-local `:include-runtime-db? true` opt-in lifts the
      ;; partition redaction; the value still rides the value walk so its
      ;; own sensitive / large declarations apply (rf2-5w06uu).
      (and (contains? fs :rf.db/runtime) (not include-runtime-db?))
      (assoc :rf.db/runtime :rf/redacted)

      (and (contains? fs :rf.db/runtime) include-runtime-db?)
      (update :rf.db/runtime projection/project-egress (egress-opts frame-id opts)))))

(defn- reroot-trace-event-db-slots
  "Per rf2-ta0y7: the `:rf.event/db-pending` (t1) and
  `:rf.event/db-pending-post-flow` (t2) trace events each carry the
  FULL pending `:db` value under `:tags :rf.event/db`. The bulk
  `project-payload-slot` above walks `:trace-events` as a single root —
  its path tracker treats the nested `:db` value as `[<i> :tags
  :rf.event/db :a :b …]`, so schema-declared sensitive paths like
  `[:auth :password]` do NOT match (the walker expects them rooted at
  the frame's app-db).

  This helper performs the per-event re-root: it walks each trace
  event whose `:operation` is in #{`:rf.event/db-pending`
  `:rf.event/db-pending-post-flow`}, then projects the inner `:db` value
  through `project-egress` with `{:path []}` — re-rooting the walk at
  the frame's app-db so the sensitive / large declarations match
  natively. Symmetric with how `flows.cljc` elides `:rf.flow/computed`'s
  `:result` / `:before` at emit-time using the flow's `:path`; here we
  do it at egress because the t1/t2 stamps are stored raw on the
  ring (Mike's ruling — store the value plain, PDS keeps the cost
  pointer-sized; the egress projection is the privacy boundary).

  Returns the trace-events vector with the inner `:rf.event/db` slots
  re-projected; non-t1/t2 events pass through untouched, and events
  lacking the `:rf.event/db` slot pass through too. Idempotent: a
  second pass against an already-redacted value just walks scalars
  that no longer match any declaration.

  A user-supplied `:redact-fn` may have already replaced the whole
  `:trace-events` slot with a scalar sentinel (`:rf/redacted`) — the
  fn returns it untouched in that case (no descend into a non-
  vector)."
  [trace-events frame-id opts]
  (if-not (sequential? trace-events)
    trace-events
    (let [wire-opts (assoc (egress-opts frame-id opts) :path [])]
      (mapv (fn [ev]
              (if-not (map? ev)
                ev
                (let [op (:operation ev)]
                  (if (and (or (= op :rf.event/db-pending)
                               (= op :rf.event/db-pending-post-flow))
                           (some? (get-in ev [:tags :rf.event/db])))
                    (update-in ev [:tags :rf.event/db]
                               projection/project-egress
                               wire-opts)
                    ev))))
            trace-events))))

(defn- elide-trace-events-slot
  "The `:trace-events` projection chain (rf2-ta0y7): first re-root the
  per-event `:rf.event/db` slots on the t1 / t2 trace events so the
  sensitive / large declarations match natively, then run the bulk
  frame/profile `project-egress` walk over the whole vector to handle the
  other payload-bearing tag values (`:rf.event/v`, `:rf.cofx/value`, etc.)
  with their own per-tag paths. `opts` `:include-sensitive?` /
  `:include-large?` opt the per-call posture back in (rf2-5w06uu).
  Idempotent (a second pass walks already-redacted scalars).
  Nil-preserving."
  [v frame-id opts]
  (when (some? v)
    (-> v
        (reroot-trace-event-db-slots frame-id opts)
        (projection/project-egress (egress-opts frame-id opts)))))

(defn- elide-sub-run-row
  "Project a single structured `:sub-runs` row for off-box egress
  (rf2-at60h). The row carries value-bearing `:prev-value` / `:value`
  slots holding the sub's computed app-data — so, unlike the non-value
  metadata (`:sub-id`, `:query-v`, `:value-changed?`, `:cascade?`,
  `:cause-sub`, `:cause-event-id`), they MUST respect the projection
  contract.

  The whole-output `:sensitive?` case is already redacted at the marks
  emit site (the slots arrive carrying `:rf/redacted`), so no work is
  needed here for sensitive. The whole-output `:large?` case is carried
  on the row as `:large?` (threaded by `capture/sub-run-row`) with the
  RAW value still attached (the on-box ring keeps the exact value). For
  the off-box `:include-large? false` default we substitute the canonical
  `:rf.size/large-elided` marker (`re-frame.marks/large-marker`, the
  same `:reason :marks` provenance the propagation table sets) for both
  value slots, then strip the now-spent `:large?` flag so the projected
  row's shape matches the on-box base shape's metadata. Idempotent: a
  marker value rebuilt through a second pass would be wrong-sized, so a
  slot already carrying a marker is left untouched.

  The structured `:effects` rows are ALSO payload-bearing (rf2-rlt3sv):
  each carries `:args` — the RAW fx-handler argument captured verbatim from
  the `:rf.fx/args` trace tag, NOT routed through the marks-projection
  chokepoint and NOT rooted at the frame's app-db, so the schema-path walker
  cannot prove it safe. Off-box egress FAILS CLOSED: `:args` lands as
  `:rf/redacted` for every outcome row (`:ok` / `:skipped-on-platform` /
  `:error`) under the `:include-fx-args? false` default — see
  `elide-effects-slot`. The value-free row metadata (`:fx-id`, `:outcome`,
  `:error-trace`) passes through unchanged. The trusted-local
  `:include-fx-args? true` opt keeps the raw `:args` (orthogonal to the
  app-db `:include-sensitive?` / `:include-large?` opt-ins).

  Per-PATH large declarations (a sub with `:large [<path>]` marks but no
  whole-output `:large?` stamp) are already substituted INTO the value
  at the marks emit site (`redact-with-paths`), so they ride the row
  pre-marked and need no projection here.

  `opts` `:include-large? true` opts the whole-output large value back in
  (rf2-5w06uu): a trusted-local caller keeps the raw `:value` /
  `:prev-value`; the `:large?` flag is still stripped so the projected
  row's shape matches the on-box base shape's metadata."
  [row {:keys [include-large?]}]
  (cond
    (not (:large? row)) row
    include-large?      (dissoc row :large?)
    :else
    (-> row
        (cond-> (contains? row :value)
          (update :value (fn [v]
                            (if (elision/marker? v) v (marks/large-marker v [:value]))))
          (contains? row :prev-value)
          (update :prev-value (fn [v]
                                (if (elision/marker? v) v (marks/large-marker v [:prev-value])))))
        (dissoc :large?))))

(defn- elide-sub-runs-slot
  "Project the structured `:sub-runs` vector for off-box egress: walk
  each row through `elide-sub-run-row` with the per-call `opts`. Nil- and
  non-sequential-preserving (a `:redact-fn` may have already replaced the
  slot with a scalar sentinel)."
  [sub-runs opts]
  (if-not (sequential? sub-runs)
    sub-runs
    (mapv #(elide-sub-run-row % opts) sub-runs)))

(defn- elide-effect-row
  "Project one structured `:effects` row for off-box egress (rf2-rlt3sv).

  Each row carries `:args` — the RAW fx-handler argument payload captured
  verbatim from the `:rf.fx/args` trace tag (`re-frame.fx/handle-one-fx`).
  These args are payload-bearing user data (an `:http` request body, a
  `[:login pw]` dispatch vector, a payment map, …) and — unlike the
  `:rf.event/db` snapshots — they are NOT passed through the marks-projection
  chokepoint at emit time, NOR are they rooted at the frame's app-db, so the
  schema-path-keyed `elide-wire-value` walker cannot prove any of them safe.
  A safe per-fx projection cannot be proven, so off-box egress FAILS CLOSED
  (Spec 009 §Privacy / sensitive data in traces + Security.md §Off-box egress):
  the `:args` slot is replaced with the `:rf/redacted` sentinel by default for
  EVERY outcome row (`:ok`, `:skipped-on-platform`, `:error` — the
  no-such-fx / handler-exception rows whose args are never pre-redacted).
  `:fx-id`, `:outcome`, and `:error-trace` are value-free metadata and pass
  through unchanged.

  `opts` `:include-fx-args? true` is the trusted-local opt-in (rf2-5w06uu
  family) — a developer's own Xray panel inspecting their own running app
  keeps the raw `:args`. It is ORTHOGONAL to the app-db `:include-sensitive?`
  / `:include-large?` opt-ins (fx args are a different keyspace, not app-db
  values), so those do NOT lift it.

  Idempotent: a row whose `:args` was already replaced with `:rf/redacted`
  re-redacts to the same sentinel. A row carrying no `:args` key passes
  through (no slot to fabricate)."
  [row {:keys [include-fx-args?]}]
  (if (or include-fx-args? (not (contains? row :args)))
    row
    (assoc row :args :rf/redacted)))

(defn- elide-effects-slot
  "Project the structured `:effects` vector for off-box egress (rf2-rlt3sv):
  walk each row through `elide-effect-row`, fail-closed-redacting the
  payload-bearing `:args` slot. Nil- and non-sequential-preserving (a
  `:redact-fn` may have already replaced the whole slot with a scalar
  sentinel)."
  [effects opts]
  (if-not (sequential? effects)
    effects
    (mapv #(elide-effect-row % opts) effects)))

(defn projected-record
  "Project an `:rf/epoch-record` for off-box egress. Routes the
  full-value payload slots (`:frame-state-before`, `:frame-state-after`,
  `:db-before`, `:db-after`, `:trigger-event`, `:trace-events`) through
  `re-frame.projection/project-egress` under the
  `:rf.egress/off-box-observability` profile (EP-0015 §15 / §10) against
  the record's frame, with the off-box defaults `:include-sensitive? false`
  / `:include-large? false`. Sensitive paths land as `:rf/redacted`; large
  paths land as `:rf.size/large-elided` markers per the §Composition rule.

  ## Frame/profile projection then `:redact-fn` advanced override (EP-0015 §15)

  This is a TWO-STAGE projection (open-issue 6, RULED):

    1. **Frame/profile projection.** Each tree-shaped slot is projected
       through `project-egress` with the `:rf.egress/off-box-observability`
       profile — the ordinary redaction every off-box egress gets,
       sourced from the frame's `:sensitive?` / `:large?` classification.
       This is the normal answer; ordinary apps need nothing more.

    2. **`:redact-fn` advanced override.** If the app installed a
       `(rf/configure! :epoch-history {:redact-fn …})`, it is applied to
       the already-projected record (`assembly/apply-redact-fn`) as the
       rare advanced escape for material the schema-driven projection
       cannot prove. It runs ONLY here, on the off-box egress copy — the
       ring stays raw (post-EP-0010 causal replay material), so the fn can
       never affect `restore-epoch` fidelity.

  ## Egress opts (rf2-5w06uu)

  The 2-arity accepts an `opts` map of trusted-local per-call overrides:

      {:include-sensitive?  <bool>   ;; reveal app-db sensitive values
       :include-large?      <bool>   ;; reveal app-db large values
       :include-runtime-db? <bool>   ;; reveal the frame-state runtime-db partition
       :include-fx-args?    <bool>}  ;; reveal the structured `:effects` `:args`

  All default `false` — the 1-arity (`(projected-record record)`) is the
  safe, fully-redacted off-box path. `:include-sensitive?` /
  `:include-large?` opt the APP-DB partition's privacy / size posture back
  in across every payload slot. They are ORTHOGONAL to the runtime-db
  partition boundary: the `:rf.db/runtime` side of the frame-state slots
  stays `:rf/redacted` UNLESS the trusted-local caller ALSO passes
  `:include-runtime-db? true` (which routes runtime-db through the same
  value walk, where its own per-slot sensitive / large declarations still
  apply). This closes the rf2-5w06uu bypass where a per-tool egress path
  walked the raw record and lifted the runtime-db partition just because
  the caller asked for sensitive / large APP-DB values. Extending the
  normative projection (rather than per-tool reimplementation) keeps
  Security.md §Off-box egress's single-emission-site contract.

  EP-0001 (rf2-3aizt1, decision #2 + Mike ruling #14): the CANONICAL
  `:frame-state-before` / `:frame-state-after` slots egress with their
  `:rf.db/app` partition projected (the same projection the `:db-*` slots
  get) and their `:rf.db/runtime` partition DEFAULT-REDACTED to
  `:rf/redacted` off-box — machine snapshots / route slice / SSR metadata
  do not egress to AI / log channels by default (see
  `project-frame-state-slot`).

  The structured `:sub-runs` rows are ALSO value-bearing (rf2-at60h):
  each carries the sub's computed `:prev-value` / `:value`. Their
  whole-output `:sensitive?` case is already redacted at the marks emit
  site, but the whole-output `:large?` case leaves the raw value on the
  row (the on-box ring keeps exact state), so this projection substitutes
  the `:rf.size/large-elided` marker for those slots under the
  `:include-large? false` default — see `elide-sub-runs-slot`. The
  non-value row metadata (`:sub-id`, `:query-v`, `:value-changed?`,
  `:cascade?`, `:cause-sub`, `:cause-event-id`) passes through unchanged.

  The structured `:effects` rows are payload-bearing too (rf2-rlt3sv):
  each carries `:args` — the RAW fx-handler argument, which fails closed to
  `:rf/redacted` off-box under the `:include-fx-args? false` default (see
  `elide-effects-slot`). The value-free row metadata (`:fx-id`, `:outcome`,
  `:error-trace`) and the whole `:renders` projection pass through unchanged.

  The record-level bookkeeping (`:epoch-id`, `:frame`, `:committed-at`,
  `:event-id`, `:outcome`, `:halt-reason`, `:schema-digest`,
  `:rf.epoch/sensitive?`, `:rf.epoch/redacted-modified-paths-count`)
  also passes through unchanged — it carries no app-db material.

  Per Security.md §Epoch privacy posture and rf2-mrsck: this is the
  single normative projection emission site for off-box egress. Tools
  that forward epoch records across a process boundary (Xray-MCP
  `watch-epochs`, story / pair recorders, hosted post-mortem
  forwarders) MUST route through this fn at the wire boundary; the
  on-box ring buffer and `register-epoch-listener!` listener fan-out
  continue to deliver the RAW record so on-box devtools (Xray diff,
  REPL, `restore-epoch`) can reason about exact state.

  After the frame/profile projection lands, the installed `:redact-fn`
  advanced override (`assembly/apply-redact-fn`) runs over the PROJECTED
  record (EP-0015 §15 + open-issue 6, RULED) — the rare escape for
  material the schema-driven projection cannot prove. A throwing override
  emits `:rf.warning/epoch-redact-fn-exception` and falls back to the
  projected record. When no `:redact-fn` is installed it is an identity
  pass-through (the common case).

  `record` may be `nil` (e.g. a missing epoch lookup) — the projection
  returns `nil` in that case, nothing called. Production builds elide the
  entire epoch surface; consumers gate any `register-epoch-listener!`
  registration under `interop/debug-enabled?` per Spec 009 §User-side
  listener registration."
  ([record] (projected-record record nil))
  ([record opts]
   (when (map? record)
     (let [frame-id  (:frame record)
           projected (cond-> record
                       ;; EP-0001 (rf2-3aizt1, decision #2 + ruling #14): the
                       ;; CANONICAL frame-state slots egress with the app-db
                       ;; partition projected and the runtime-db partition
                       ;; default-redacted off-box (see
                       ;; `project-frame-state-slot`). rf2-5w06uu — `opts`
                       ;; thread the trusted-local sensitive / large /
                       ;; runtime-db overrides.
                       (contains? record :frame-state-before)
                       (update :frame-state-before project-frame-state-slot frame-id opts)

                       (contains? record :frame-state-after)
                       (update :frame-state-after  project-frame-state-slot frame-id opts)

                       (contains? record :db-before)
                       (update :db-before     project-payload-slot frame-id opts)

                       (contains? record :db-after)
                       (update :db-after      project-payload-slot frame-id opts)

                       (contains? record :trigger-event)
                       (update :trigger-event project-payload-slot frame-id opts)

                       (contains? record :trace-events)
                       (update :trace-events  elide-trace-events-slot frame-id opts)

                       (contains? record :sub-runs)
                       (update :sub-runs      elide-sub-runs-slot opts)

                       ;; rf2-rlt3sv: the structured `:effects` rows carry
                       ;; payload-bearing `:args` (the raw fx-handler argument).
                       ;; They are NOT app-db-rooted, so the schema-path walker
                       ;; cannot prove them safe — off-box egress fails closed,
                       ;; redacting `:args` to `:rf/redacted` while preserving
                       ;; the value-free `:fx-id` / `:outcome` / `:error-trace`
                       ;; metadata. The trusted-local `:include-fx-args? true`
                       ;; opt keeps the raw args.
                       (contains? record :effects)
                       (update :effects       elide-effects-slot opts))]
       ;; EP-0015 §15 + open-issue 6 (RULED): apply the app `:redact-fn`
       ;; advanced override to the already-projected record. Projection-side
       ;; only — the ring stays raw, so restore fidelity is never affected.
       ;; Identity pass-through when no fn is installed (the common case).
       (assembly/apply-redact-fn projected)))))

(defn projected-history
  "Convenience: return the projected vector of records for a frame.
  Equivalent to `(mapv #(projected-record % opts) (epoch-history frame-id))`.
  Tools that egress the whole ring (an MCP `watch-epochs` initial
  snapshot, a recorder dumping the full session) can call this once
  rather than walking the raw ring and re-wrapping each record. The
  2-arity threads the trusted-local egress `opts` (rf2-5w06uu —
  `:include-sensitive?` / `:include-large?` / `:include-runtime-db?`) to
  every record; the 1-arity is the safe, fully-redacted off-box path."
  ([frame-id] (projected-history frame-id nil))
  ([frame-id opts]
   (mapv #(projected-record % opts) (state/history-for frame-id))))
