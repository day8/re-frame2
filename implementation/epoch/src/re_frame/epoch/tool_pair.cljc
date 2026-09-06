(ns re-frame.epoch.tool-pair
  "Tool boundary surfaces: preconditions, restore, state injection, and
  off-box projection helpers behind `restore-epoch!`, `replace-frame-state!`,
  `projected-record`, and `projected-history`.

  Responsibilities:

    * **Precondition validators** — `check-restore-preconditions!` and
      `check-replace-frame-state-preconditions!` are pure data transforms
      (no trace emission, no app-db writes); they return
      `{:outcome :ok ...}` or `{:outcome :fail :op <kw> :tags <map>}`
      so the orchestrating facade fn can emit the trace and decide
      flow control.

    * **Schema / handler / version probes** — `failing-schema-paths`,
      `missing-references`, `machine-version-mismatch`. Each is a
      single walk over the recorded db; callers bind the result so
       the failure path walks each substrate exactly once per check.

    * **Perform-restore** — `perform-restore!` carries out the
      container replace + `:rf.epoch/restored` emit once preconditions
      have passed.

    * **Projected egress** — `projected-record` and `projected-history`
      route every payload-bearing slot through the privacy projection
      for off-box egress (Xray-MCP `watch-epochs`, story / pair
      recorders). That is: the canonical `:frame-state-before` /
      `:frame-state-after` slots (app-db partition elided, runtime-db
       partition default-redacted), the derived
      `:db-before` / `:db-after` app-db projections, `:trigger-event`,
      `:trace-events`, the value-bearing structured `:sub-runs` rows
       (their `:prev-value` / `:value` slots), and the structured
      `:effects` rows' payload-bearing `:args` (fail-closed to `:rf/redacted`
       off-box). The value-free `:renders` metadata, the
      `:effects` rows' `:fx-id` / `:outcome` / `:error-trace`, and the
      record-level bookkeeping pass through unchanged. `projected-record`'s
      own docstring is the authoritative per-slot contract.

  The orchestrators live in the `re-frame.epoch` facade and wire
  the precondition check + the trace emission + the perform / listener
  fan-out steps together. Pure-data shape of the preconditions makes
  the orchestrators a four-line case-match."
  (:require [clojure.string :as str]
            [re-frame.elision :as elision]
            [re-frame.epoch.assembly :as assembly]
            [re-frame.epoch.state :as state]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.classification :as classification]
            [re-frame.projection :as projection]
            [re-frame.registrar :as registrar]
            [re-frame.router :as router]
            [re-frame.trace :as trace]))

;; ---- restore failure-mode predicates --------------------------------------

(defn- malli-validate-fn
  "Return the malli validate fn or nil.

  Looks up the late-bind hook `:schemas/malli-validate`, published by
  `re-frame.schemas.malli` when loaded. This is the only lookup step on both
  CLJ and CLJS, so validation has the same contract in both runtimes.

  Callers treat an unbound hook as a soft pass: without a validator they
  cannot disprove validity."
  []
  (late-bind/get-fn :schemas/malli-validate))

(defn- registered-app-schemas
  "Return the {path → schema-meta} map registered against the named
  frame, or {}. Per Spec 010 §Per-frame schemas the schema set is
  frame-scoped; restore-epoch! validates against the schemas registered
  against the frame the epoch belongs to, not a process-global set."
  [frame-id]
  (if-let [schema-entries-for-frame
           (late-bind/get-fn :schemas/frame-schema-entries)]
    (schema-entries-for-frame frame-id)
    {}))

(defn failing-schema-paths
  "Return a vector of failing schema-paths for `db` against `frame-id`'s
  registered app-schemas. Empty vector means valid — either every
  registered schema accepted the path's value, OR no schemas are
  registered, OR no Malli validator is on the classpath. The latter
  two are soft-pass: we can't disprove validity, so we treat the db
  as valid.

  Single walk over the schema set: the validity question is
  `(empty? (failing-schema-paths frame-id db))`, so callers get both the
  yes/no answer and the failing paths from one traversal."
  [frame-id db]
  (let [app-schemas     (registered-app-schemas frame-id)
        validate-schema (malli-validate-fn)]
    (if (or (empty? app-schemas) (nil? validate-schema))
      []
      (vec
        (keep (fn [[path schema-entry]]
                (let [schema       (:schema schema-entry)
                      schema-value (get-in db path)]
                  (when-not (try (validate-schema schema schema-value)
                                 (catch #?(:clj Throwable :cljs :default) _ true))
                    path)))
              app-schemas)))))

(defn- machine-registration
  "Resolve a machine-id against the public machine registry. Per
  Spec 005 §Registration / §Querying machines, machines are event
  handlers whose registration metadata carries `:rf/machine? true`
  and `:rf/machine` (the spec map). Returns the registration map
  when machine-id names a registered machine, nil otherwise.

  Epoch restore validates against this public surface, not the unrelated
  internal `:head` registrar kind."
  [machine-id]
  (let [registration (registrar/lookup :event machine-id)]
    (when (:rf/machine? registration)
      registration)))

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

;; Machine snapshots and the route slice are durable runtime-db partition
;; state, addressed at the
;; namespaced runtime-db root keys `:rf.runtime/machines` /
;; `:rf.runtime/routing`. The restore-precondition readers below take the
;; runtime-db PARTITION value (`(:rf.db/runtime frame-state)`) and walk those
;; namespaced paths. Each epoch captures the whole frame-state,
;; so `check-restore-preconditions!` passes the runtime-db partition here,
;; against which the missing-handler-machine + version-drift preconditions
;; fire.
(def ^:private machine-snapshots-path
  "Path to the machine-snapshots map inside the runtime-db partition value."
  [:rf.runtime/machines :snapshots])

(def ^:private route-current-id-path
  "Path to the active route's `:route-id` inside the runtime-db partition value."
  [:rf.runtime/routing :current :route-id])

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
  (if-let [validate-machine-data!
           (late-bind/get-fn :machines/validate-machine-data!)]
    (let [validation-result
          (try (validate-machine-data! runtime-db nil frame-id)
               (catch #?(:clj Throwable :cljs :default) _ true))]
      (if (or (nil? validation-result) (true? validation-result))
        []
        [machine-snapshots-path]))
    []))

(defn missing-references
  "Walk the recorded runtime-db partition for ids that are no longer present
  in the registrar. Closed v1 surface — `[:rf.runtime/machines :snapshots]`
  (each machine-id must reference a registered machine via the public event
  registry, per Spec 005 §Registration — machines are event handlers tagged
  with `:rf/machine?`) and `:route` (`[:rf.runtime/routing :current :route-id]` must
  reference a registered :route).

  `runtime-db` is the `:rf.db/runtime` partition of the epoch-recorded
  frame-state. Machine lookup goes through the public event registry, not
  the unrelated internal `:head` registrar kind.

  A dynamically spawned actor carries no per-instance
  registrar entry — its liveness is derived from its (revertible)
  snapshot. Such a snapshot is a VALID restore target iff its TYPE still
  resolves (registered TYPE keyword, or inline `:definition` carried on
  the snapshot). The `:machines/actor-resolvable?` hook makes that
  determination from the runtime-db partition (it reads
  `[:rf.runtime/machines :snapshots <id>]`); consult it before flagging a
  snapshot whose id is not directly registered. A SINGLETON whose
  registration was cleared (a `reg-machine`'d machine, no `:rf/machine-type`
  on its snapshot) still surfaces as missing — `actor-resolvable?` returns
  false for it. When the machines artefact is absent the hook is nil and the
  registrar-only check applies.

  Returns a vector of {:kind <kind> :id <id>} entries. Empty when
  every reference resolves."
  [runtime-db]
  (let [actor-resolvable? (late-bind/get-fn :machines/actor-resolvable?)
        ;; Machines under [:rf.runtime/machines :snapshots]: a singleton
        ;; references a registered machine (`:rf/machine? true`, per Spec
        ;; 005 §Registration); a spawned actor has no
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
  "Resolve the current definition `:rf/snapshot-version` for one recorded
  snapshot the same way dispatch resolves the live spec:

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
  frame-state. The current definition is resolved the same way dispatch
  resolves the live spec: a singleton by its snapshot key (the key is the registered
  machine-id), a SPAWNED ACTOR by its snapshot's `:rf/machine-type` (registered
  type keyword or inline `:definition` map — `current-definition-version`).

  The recorded version is read through the public Spec 005 §Snapshot shape
  contract — the snapshot's `[:meta :rf/snapshot-version]`;
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
  `check-restore-preconditions!` reads history once and reuses the vector for
  both this lookup and the `:history-size` count on the unknown-epoch path."
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
  (let [router       (:router frame-record)
        router-state (when router @router)]
    (boolean (and router-state
                  (or (:in-drain? router-state)
                      (:in-sync-drain? router-state))))))

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
  "Validate the documented preconditions for restoring `frame-id`
  to `epoch-id`. Returns a result map:

    {:outcome :ok :epoch <epoch> :incarnation-token <token>}
                 — all checks passed; `:epoch` is the resolved history
                   record whose `:frame-state-after` is the restore target,
                   and `:incarnation-token` is the EXACT identity token of
                   the frame incarnation these checks resolved against.
                   `restore-epoch!` carries it to the write boundary so
                   `perform-restore!` refuses to install into a same-id
                   SUCCESSOR frame seated between this pass and the physical
                   write (rf2-bjh6y).
    {:outcome :fail :op <kw> :tags <map>}
                 — first failing check; `:op` is the trace operation
                   the caller must emit, `:tags` are its tags. No
                   trace events are emitted from inside this helper —
                   emission is the caller's job so the
                   precondition test stays a pure data check.

  See `restore-epoch!` for the refusal catalogue."
  [frame-id epoch-id]
  (let [frame-result      (frame-exists-or-fail frame-id)
        frame-record      (:frame-record frame-result)
        ;; The EXACT incarnation identity token (the record's `:drain-lock`, per
        ;; `frame-incarnation-token`) DERIVED FROM THE SAME captured record — NOT
        ;; an independent bare-id re-resolve. A same-id successor B seated between
        ;; the record capture above and here can therefore never supply the
        ;; token: the ticket pairs THIS record's resolved epoch/history with THIS
        ;; record's own incarnation, closing seam 1 (rf2-qfrh4 — the prior
        ;; independent `frame-incarnation-token` re-resolve could pair A's
        ;; retained epoch/history with B's live token). Carried out on the `:ok`
        ;; result so the write boundary can reject a stale install after a
        ;; destroy + same-id reconstruction (rf2-bjh6y). nil when the frame is
        ;; absent — the (1) frame-registered branch fails first in that case.
        incarnation-token (some-> frame-record :drain-lock)]
    (cond
      ;; (1) Frame registered?
      (= :fail (:outcome frame-result))
      frame-result

      ;; (2) In-flight drain?
      (drain-in-flight? frame-record)
      {:outcome :fail
       :op      :rf.epoch/restore-during-drain
       :tags    {:frame       frame-id
                 :rf.epoch/id epoch-id}}

      :else
      (let [history (state/history-for frame-id)
            epoch-record (find-epoch-in history epoch-id)]
        (cond
          ;; Exact-owner gate on the history/validation snapshot (rf2-qfrh4
          ;; seam 1). `state/history-for` above (and every validator below)
          ;; resolves by BARE frame-id; a same-id successor seated DURING this
          ;; precondition sampling means the captured incarnation is no longer
          ;; live, so the history/validation snapshot may belong to — or would
          ;; be paired against — the successor. Refuse with the SAME canonical
          ;; no-such-handler failure the write boundary uses rather than resolve
          ;; or validate against a stale incarnation. (Belt-and-braces with the
          ;; record-derived token above: even if a race slips past here the
          ;; ticket still carries A's token, so the exact write rejects B.)
          (not (frame/event-continuation-live? frame-id incarnation-token))
          {:outcome :fail
           :op      :rf.error/no-such-handler
           :tags    {:kind  :frame
                     :frame frame-id}}

          ;; (3) Epoch present in current history?
          (nil? epoch-record)
          {:outcome :fail
           :op      :rf.epoch/restore-unknown-epoch
           :tags    {:frame        frame-id
                     :rf.epoch/id  epoch-id
                     :history-size (count history)}}

          ;; Halted records contain partial state and are not restore targets.
          ;; Refuse before schema, handler, and version checks so
          ;; the failure surfaces with the actual halt context, not
          ;; a downstream consequence of the partial db.
          (not= :ok (get epoch-record :outcome :ok))
          {:outcome :fail
           :op      :rf.epoch/restore-non-ok-record
           :tags    {:frame       frame-id
                     :rf.epoch/id epoch-id
                     :outcome     (:outcome epoch-record)
                     :halt-reason (:halt-reason epoch-record)}}

          :else
          (let [;; The canonical restore target is the whole frame state.
                ;; The app-db partition
                ;; feeds the schema check; the runtime-db partition feeds
                ;; machine/route reference and version checks. Both are
                ;; read off the canonical `:frame-state-after` — the only
                ;; restore target `build-record` ever emits (the `:db-after`
                ;; slot is a retained app-db PROJECTION for tool diffs, never
                ;; a restore source). A record with no `:frame-state-after`
                ;; is malformed/unreachable on the current build path.
                recorded-frame-state (:frame-state-after epoch-record)
                recorded-app-db      (get recorded-frame-state
                                          frame/app-partition-key)
                recorded-runtime-db  (get recorded-frame-state
                                          frame/runtime-partition-key)]
            ;; Bind each probe once so each substrate is walked once.
            ;; failure path walks the recorded db / schema set / machine
            ;; map exactly once per check.
            (if-let [failing-paths
                     (seq (failing-schema-paths frame-id recorded-app-db))]
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
                         :rf.epoch/id            epoch-id
                         :schema-digest-recorded (:schema-digest epoch-record)
                         :schema-digest-current  (assembly/current-schema-digest frame-id)
                         :failing-paths          (vec failing-paths)}}

              (if-let [missing-reference-details
                       (seq (missing-references recorded-runtime-db))]
                ;; (5) Missing handler referenced from runtime-db?
                {:outcome :fail
                 :op      :rf.epoch/restore-missing-handler
                 :tags    {:frame       frame-id
                           :rf.epoch/id epoch-id
                           :missing     (vec missing-reference-details)}}

                (if-let [{:keys [machine-id machine-type recorded current]}
                         (machine-version-mismatch recorded-runtime-db)]
                  ;; (6) Machine snapshot version drift?
                  ;; `:machine-type` identifies a spawned actor's
                  ;; TYPE (keyword or inline-definition map) alongside its
                  ;; instance `:machine-id`; nil/omitted for a singleton whose
                  ;; key is its own type. Spawned-actor drift is now caught:
                  ;; the current version resolves via `:rf/machine-type`, not
                  ;; the unregistered instance-id key.
                  {:outcome :fail
                   :op      :rf.epoch/restore-version-mismatch
                   :tags    (cond-> {:frame            frame-id
                                     :rf.epoch/id      epoch-id
                                     :machine-id       machine-id
                                     :version-recorded recorded
                                     :version-current  current}
                              (some? machine-type) (assoc :machine-type machine-type))}

                  {:outcome :ok
                   :epoch epoch-record
                   :incarnation-token incarnation-token})))))))))

;; ---- replay-epoch! preconditions + perform (Tool-Pair §Replay) -------------
;;
;; The ONE-CALL replay gesture (rf2-ov144). The retained raw record is
;; resolved in-process and its replay material — the argument-bearing
;; `:trigger-event`, the post-generation `:rf.cofx` token, the serializable
;; `:fx-overrides` / `:interceptor-overrides` — is folded into the strict
;; dispatch opts HERE. No caller exports, copies or re-supplies those slots,
;; and the off-box `:rf/redacted` projection of event args
;; (`elide-trigger-event-slot` below) never gets in the way, because the raw
;; ring is what is read. Refusals are decided BEFORE anything dispatches and
;; ride back as a structured envelope; no trace is emitted for them.

(def ^:private fn-override-sentinel
  "`re-frame.router`'s stand-in for a fn-valued `:fx-overrides` entry (Spec
  002 §Per-frame and per-call overrides). A record carrying it is
  UNREPLAYABLE under `:strict`: the fn never rode the record, so the run
  cannot be re-driven with the override the original had active."
  :rf/fn-override)

(defn- fn-override-fx-ids
  "The fx-ids whose recorded `:fx-overrides` entry is the opaque
  `:rf/fn-override` sentinel — empty for a replayable record."
  [record]
  (into []
        (keep (fn [[fx-id target]] (when (= fn-override-sentinel target) fx-id)))
        (:fx-overrides record)))

(defn- non-replayable-cause
  "Why `record` cannot be re-driven, or nil for an ordinary settled event.
  Halted records carry partial state; the synthetic `replace-frame-state!`
  record has no handler run behind it; a record without a trigger or a
  replay token has nothing faithful to re-present."
  [record]
  (cond
    (not= :ok (get record :outcome :ok))         :halted
    (= :rf.epoch/db-replaced (:event-id record)) :synthetic
    (not (vector? (:trigger-event record)))      :missing-trigger-event
    (not (map? (:rf.cofx record)))               :missing-replay-token))

(defn check-replay-preconditions!
  "Validate the preconditions for replaying `frame-id`'s retained epoch
  `epoch-id` through the frame's own handlers. Returns

    {:outcome :ok   :epoch <record>}
    {:outcome :fail :reason <kw> :tags <map>}

  Pure data — nothing is emitted here; `replay-epoch!` folds `:reason` and
  `:tags` into its refusal envelope, which IS the failure surface. The
  refusal catalogue:

    :rf.error/no-such-handler (kind :frame)   — frame not registered / destroyed
    :rf.epoch/replay-during-drain             — a drain is in flight
    :rf.epoch/replay-unknown-epoch            — id not in the frame's current
                                                history (`:history-size`)
    :rf.epoch/replay-non-replayable-record    — `:cause` is `:halted` (with the
                                                record's `:outcome` /
                                                `:halt-reason`), `:synthetic`,
                                                `:missing-trigger-event` or
                                                `:missing-replay-token`
    :rf.epoch/replay-unreplayable-fx-override — a recorded `:fx-overrides` entry
                                                is `:rf/fn-override` (`:fx-ids`)"
  [frame-id epoch-id]
  (let [frame-result (frame-exists-or-fail frame-id)
        frame-record (:frame-record frame-result)]
    (cond
      (= :fail (:outcome frame-result))
      {:outcome :fail :reason (:op frame-result) :tags (:tags frame-result)}

      (drain-in-flight? frame-record)
      {:outcome :fail :reason :rf.epoch/replay-during-drain :tags {}}

      :else
      (let [history (state/history-for frame-id)
            epoch   (find-epoch-in history epoch-id)
            cause   (when epoch (non-replayable-cause epoch))
            fn-ids  (when epoch (fn-override-fx-ids epoch))]
        (cond
          (nil? epoch)
          {:outcome :fail
           :reason  :rf.epoch/replay-unknown-epoch
           :tags    {:history-size (count history)}}

          cause
          {:outcome :fail
           :reason  :rf.epoch/replay-non-replayable-record
           :tags    (cond-> {:cause cause}
                      (= :halted cause) (assoc :outcome     (:outcome epoch)
                                               :halt-reason (:halt-reason epoch)))}

          (seq fn-ids)
          {:outcome :fail
           :reason  :rf.epoch/replay-unreplayable-fx-override
           :tags    {:fx-ids fn-ids}}

          :else
          {:outcome :ok :epoch epoch})))))

(def ^:private replay-owned-opt-keys
  "Dispatch-opts keys the replay gesture OWNS. A caller value under any of
  them is discarded: the record is the only source of replay material, and
  the target frame is the source frame by construction."
  [:frame :rf.cofx :rf.cofx/mint-policy :fx-overrides :interceptor-overrides])

(defn replay-dispatch-opts
  "The strict replay dispatch opts for `record` against `frame-id`
  (Tool-Pair §Replay): the recorded post-generation `:rf.cofx` under
  `:rf.cofx/mint-policy :strict`, plus the record's own `:fx-overrides` /
  `:interceptor-overrides` (absent on the record ⇒ absent here, so an
  override-free replay's opts carry neither key). `opts` is an ordinary
  dispatch-opts map for the slots replay does not own — `:origin`,
  `:source`, `:trace-id` — with any value under an owned key dropped."
  [frame-id record opts]
  (merge (apply dissoc opts replay-owned-opt-keys)
         {:frame               frame-id
          :rf.cofx             (:rf.cofx record)
          :rf.cofx/mint-policy :strict}
         (select-keys record [:fx-overrides :interceptor-overrides])))

(defn perform-replay!
  "Re-drive `record`'s `:trigger-event` synchronously through `frame-id`'s
  own handlers under `replay-dispatch-opts`, then report the ordinary epoch
  the replayed dispatch recorded:

    {:ok? true :frame <id> :source-epoch-id <id> :event-id <kw>
     :epoch-id <the new record's id, nil if the ring could not retain it>}

  `:epoch-id` is the FIRST record the dispatch committed — the replayed
  event's own epoch (a queued child settles after its parent, so it lands
  later in the ring). A declared recordable fact ABSENT from the recorded
  token throws the canonical `:rf.error/missing-required-cofx` out of the
  dispatch exactly as any `:strict` dispatch does — nothing here catches it,
  and nothing mints."
  [frame-id record opts]
  (let [pre-replay-epoch-ids
        (into #{} (map :epoch-id) (state/history-for frame-id))]
    (router/dispatch-sync! (:trigger-event record)
                           (replay-dispatch-opts frame-id record opts))
    (let [new-record
          (some (fn [candidate-record]
                  (when-not (contains? pre-replay-epoch-ids
                                       (:epoch-id candidate-record))
                    candidate-record))
                (state/history-for frame-id))]
      {:ok?             true
       :frame           frame-id
       :source-epoch-id (:epoch-id record)
       :event-id        (:event-id record)
       :epoch-id        (:epoch-id new-record)})))

;; ---- write-boundary liveness guard ----------------------------------------
;;
;; Precondition validation (`check-restore-preconditions!` /
;; `check-replace-frame-state-preconditions!`) resolves the frame, but a frame
;; can be destroyed — or destroyed and reseated under the SAME id — in the
;; window BETWEEN the precondition pass and the actual container write (the
;; validate-then-destroy race — most often a tool gesture interleaving with
;; the owning component's teardown). Per Tool-Pair §Surface behaviour against
;; destroyed frames the mutating surfaces must report this as the SAME
;; structural failure a frame-miss caught at validate-time produces
;; (`:rf.error/no-such-handler`, kind `:frame`, returns `false`) — NOT a
;; synthetic success, and NEVER a write into a same-id successor. Both write
;; paths (`perform-restore!` here, `perform-replace!` in `epoch.cljc`)
;; therefore gate on `frame/event-continuation-live?` with the EXACT
;; incarnation token their preconditions resolved (rf2-bjh6y, rf2-gj2bo) and
;; install through core's exact-incarnation `frame/replace-frame-state!`
;; 3-arity, whose nil return closes the post-liveness half of the window
;; (rf2-s93722): a destroyed-or-reseated incarnation surfaces the canonical
;; failure BEFORE any success telemetry, synthetic epoch, or listener fanout.

;; ---- drain serialization for tool writes ----------------------------------
;;
;; The precondition validators (`check-restore-preconditions!` /
;; `check-replace-frame-state-preconditions!`) read the router's `:in-drain?` /
;; `:in-sync-drain?` flags, but that read and the subsequent coherent
;; read → optional reconcile → physical write → success bookkeeping are a
;; TOCTOU pair: a drain that STARTS on another thread after validation and
;; before the write can interleave between a handler's `db` read and its
;; commit. The tool write then splices into the middle of an event transition —
;; a non-linearizable result — while still returning `true` and emitting
;; success telemetry (rf2-3fc89f.4). For a partial `replace-frame-state!` the
;; same window also lets the recorded synthetic `:frame-state-before` /
;; `:frame-state-after` (computed from a pre-interleave read) diverge from the
;; value the write actually installed.
;;
;; The fix routes the WHOLE state-sensitive operation — not just the final
;; container call — through the core single-drainer `:drain-lock` via
;; `frame/call-serialized-with-drain!` (Spec 002 §Single drainer per frame; the
;; SAME primitive the flows lifecycle ops use). Under that lock no event
;; transition can run concurrently, so the tool write holds ONE serial position
;; relative to any drain: its coherent read and its physical write see the same
;; frame-state, and the recorded synthetic epoch describes exactly the
;; transition installed.
;;
;; REENTRANCY. A restore/replace invoked from the frame's ACTIVE drainer (a
;; tool write issued mid-cascade from an event handler) must NOT run: it would
;; either self-deadlock re-taking `:drain-lock` or, worse, splice a
;; non-linearizable write into the in-flight transition (`call-serialized-with-
;; drain!`'s reentrant branch runs the thunk DIRECTLY — correct for the flows
;; ops, wrong for a tool write). So we refuse the reentrant case up front with
;; the documented during-drain op, returning `false` and recording nothing.
;; `frame/in-drain?` is the same `:in-drain?` thread marker the pre-lock
;; precondition `drain-in-flight?` reads, so this also closes the gap between
;; the (separate) precondition call and this write. On CLJS — single-threaded —
;; a mid-drain call is likewise refused here (no deadlock); a top-level call
;; takes the uncontended lock and runs.

(defn serialize-tool-write!
  "Run `op` — the coherent liveness-check → read → (reconcile) → physical
  write → success-bookkeeping thunk of a Tool-Pair state write — serialized
  against `frame-id`'s event drain through the core `:drain-lock`
  (`frame/call-serialized-with-drain!`), so the whole operation is mutually
  exclusive with any event transition (no validate-then-write TOCTOU).

  `op` is `(fn [] <boolean>)` returning the operation's own result (it still
  owns the destroyed-frame liveness recheck and its `false` returns).

  A call from the frame's ACTIVE drainer (a reentrant mid-cascade tool write)
  is REFUSED here — `during-drain-op` is emitted with `during-drain-tags` and
  `false` is returned, `op` never runs — mirroring the pre-lock
  `drain-in-flight?` precondition and avoiding both self-deadlock and a
  non-linearizable mid-transition splice."
  [frame-id during-drain-op during-drain-tags op]
  (if (frame/in-drain? frame-id)
    (do (emit-precondition-failure! during-drain-op during-drain-tags)
        false)
    (frame/call-serialized-with-drain! frame-id op)))

;; ---- runtime-db subsystem reconcile on restore ----------------------------
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
;; verbatim, which is correct for an app with no resources.

(defn reconcile-runtime-db-on-restore
  "Reconcile the runtime-db partition of a `frame-state` value about to be
  installed by `perform-restore!`, consulting the late-bound
  `:resources/reconcile-on-restore` hook (Spec 016 §Restore and replay). Returns
  the frame-state with its `:rf.db/runtime` partition reconciled — or
  `frame-state` unchanged when no resources artefact is loaded (the hook is nil),
  or when the frame-state carries no runtime-db partition (a `:frame-state-after`
  whose runtime-db is empty). The runtime-db value is passed with the carried `frame-id` so the
  reconcile can stamp its trace. Other runtime subsystems (machines, routing)
  reconcile their own snapshots through their own contracts; this seam is the
  resources-first extension point, mirroring SSR hydration's single
  `:resources/hydrate-runtime-db` consult.

  Reconcile uses `{:defer-traces? true}` so it does
  NOT emit its `:rf.resource/restored` / `:rf.resource/owner-released` success
  rows inline — the reconcile runs BEFORE the atomic install, which can still
  fail (a destroyed-frame install returns nil). The deferred trace intents ride
  back as metadata on the reconciled runtime-db; `perform-restore!` emits them via
  `:resources/commit-restore-reconcile!` only AFTER a successful install, so a
  failed restore leaks no resource success traces.

  The restore's causal time — `restore-time-ms`, the restored
  epoch's `:committed-at` (the committing token's `:rf.cofx` `:rf/time-ms`,
  replay-stable per EP-0010 §Time) — is threaded through `:restore-time-ms` so
  the reconcile stamps a dangled-on-restore mutation instance's DURABLE
  `:settled-at` from that causal input rather than the live install clock
  (`now-ms`). A durable frame-state field MUST come from a causal input, never
  an ambient world read at install (EP-0010 §Restore/Replay). The 2-arity
  passes a nil causal time (a frame-state with no mutation instances to stamp;
  the reconcile then falls back to its own clock for the no-token case).

  The EXACT incarnation `owner-token` (the captured record's `:drain-lock`) is
  threaded through `:owner-token` so the reconcile fences its non-deferred host-
  table clearing (stale/GC timer + work-ledger host handles) to the exact
  incarnation the restore resolved against (rf2-qfrh4 seam 2). The reconcile
  runs BEFORE the atomic write and addresses the frame by BARE id; without the
  token, a callback that churns A to B mid-reconcile would let the bare-id clear
  touch same-id successor B. Passing the token lets the reconcile skip the clear
  once the exact incarnation is lost, so no B host handle is released — B's
  transients belong to B and, if A was destroyed to seat B, `destroy-frame!`
  already released A's. nil owner-token (the 2-/3-arity pure-unit path) has no
  incarnation to fence and clears unconditionally, as before."
  ([frame-id frame-state] (reconcile-runtime-db-on-restore frame-id frame-state nil nil))
  ([frame-id frame-state restore-time-ms]
   (reconcile-runtime-db-on-restore frame-id frame-state restore-time-ms nil))
  ([frame-id frame-state restore-time-ms owner-token]
   (if-let [reconcile-runtime-db!
            (late-bind/get-fn :resources/reconcile-on-restore)]
     (if (contains? frame-state frame/runtime-partition-key)
       (update frame-state frame/runtime-partition-key
               (fn [runtime-db]
                 (when (some? runtime-db)
                   (reconcile-runtime-db!
                     runtime-db frame-id {:defer-traces?   true
                                          :restore-time-ms restore-time-ms
                                          :owner-token     owner-token}))))
       frame-state)
     frame-state)))

;; ---- restore-time host-transient quiesce ----------------------------------
;;
;; Restore must not revive host work. It installs the captured
;; durable frame-state WHOLESALE, but the ASYNC HOST WORK the unwound epochs
;; spawned — machine `:after` host-clock timers (re-frame.machines.timer's
;; frame-scoped handle table) and non-resource managed-HTTP AbortControllers /
;; in-flight handles (re-frame.http.registry) — is NOT frame-state, so the
;; wholesale install does not touch it. It stays attached to the pre-restore
;; timeline. Restore must QUIESCE it for the restored frame: cancel/clear the
;; orphaned host handles so a late pre-restore completion is stale-suppressed and
;; never delivers to its original `:rf/reply-to` target.
;;
;; The resources restore hook reconciles its
;; Resources subsystem's runtime-db slice + clears its host transients. This is
;; the restore counterpart for the OTHER managed async subsystems — the mirror
;; of destroy-frame!'s `:machines/on-frame-destroyed!` /
;; `:http/abort-on-actor-destroy` teardown chain. Each subsystem publishes a
;; restore hook keyed by frame-id:
;;
;;   :machines/on-frame-restored!        — cancel/clear the frame's in-flight
;;                                          `:after` host-clock timers (the pure
;;                                          per-path epoch invariant already
;;                                          stale-drops a fired timer; this
;;                                          eagerly releases the orphaned host
;;                                          handle so it never fires at all).
;;   :http/abort-in-flight-for-frame!    — abort every non-resource managed HTTP
;;                                          request in flight for the frame,
;;                                          suppressing the app reply (no
;;                                          delivery to the original
;;                                          `:rf/reply-to`) and emitting the
;;                                          EP-0011 stale-suppression facts.
;;
;; Late-bound so the epoch artefact never statically depends on the optional
;; machines / http artefacts — absent the artefact the hook is nil and quiesce is
;; a clean no-op (an app with no async host work pays nothing). Each hook is
;; fired best-effort: a throwing hook is swallowed so one bad subsystem cleanup
;; cannot strand the others, matching destroy-frame!'s `safe-call-hook!` posture.

(def ^:private restore-quiesce-hooks
  "The late-bind hook keys perform-restore! fires to quiesce orphaned async host
  work after a successful install. Each takes the restored frame-id. New managed
  async subsystems join the chain by publishing their own restore hook here."
  [:machines/on-frame-restored!
   :http/abort-in-flight-for-frame!])

(defn quiesce-orphaned-async-host-work!
  "Fire the restore-time host-transient quiesce hook chain for `frame-id`
  after a successful
  `replace-frame-state!` install — a destroyed-frame install (which wrote
  nothing) returns before this point, so a restore that never landed never
  cancels a live frame's host work. Each `restore-quiesce-hooks` entry is a
  late-bound `(fn [frame-id])`; an unregistered hook (the artefact is absent) is
  a no-op, and a throwing hook is swallowed so one bad subsystem cleanup cannot
  block the rest. Returns nil.

  The chain is itself a callback FAN-OUT (rf2-sdeae), so the caller's single
  pre-chain liveness check does not fence it: EVERY hook addresses the frame by
  BARE id, and each one is app-observable — a machines cancellation trace fired
  by the first hook can destroy incarnation A and seat a same-id successor B,
  after which the HTTP hook would snapshot and abort B's in-flight requests.
  The 2-arity therefore carries the restore's EXACT `incarnation-token` INTO the
  loop and revalidates ownership at every hook boundary, STOPPING the chain the
  moment the incarnation is lost rather than retargeting the remaining A-only
  cleanup onto B. That revalidation covers the THROWING path too (rf2-vy2hj):
  the swallow-and-warn catch is itself a post-callback tail, so it announces
  only while the captured incarnation is still owned. nil token (the 1-arity)
  has no incarnation to fence and fires the whole chain — and every warning —
  as before."
  ([frame-id] (quiesce-orphaned-async-host-work! frame-id nil))
  ([frame-id incarnation-token]
   (let [still-owned? (fn []
                        (or (nil? incarnation-token)
                            (frame/event-continuation-live? frame-id incarnation-token)))]
      (doseq [hook-key restore-quiesce-hooks
              :while   (still-owned?)]
        (when-let [quiesce-hook (late-bind/get-fn hook-key)]
          (try (quiesce-hook frame-id)
               (catch #?(:clj Throwable :cljs :default) quiesce-error
                ;; rf2-vy2hj: the SETTLED path needs the same fence as the
                ;; loop's `:while`. A hook is the very callback boundary this
                ;; chain polices, so hook 1 may destroy A, seat a same-id
                ;; successor B, and only THEN throw — unwinding into a catch
                ;; that addresses the frame by BARE id, which B now owns. The
                ;; unfenced emit does NOT error; it silently RESURRECTS A's
                ;; cleanup diagnostic as B's, fanning synchronously through B's
                ;; public trace listeners (and retainable under B's id) BEFORE
                ;; the loop's next `:while` gets to stop hook 2. The hazard is
                ;; a run OUTLIVING its claim, not a token failing to arrive —
                ;; the token is already conveyed correctly — so the repair is
                ;; the same live-slot comparison, taken once more at the moment
                ;; of announcement. A hook that throws under a STILL-LIVE
                ;; incarnation is unaffected: it emits its one warning and the
                ;; best-effort chain continues.
                (when (still-owned?)
                  (trace/emit-error! :rf.warning/restore-quiesce-hook-exception
                                      {:category  :rf.warning/restore-quiesce-hook-exception
                                       :hook      hook-key
                                       :frame     frame-id
                                       :exception quiesce-error
                                       :recovery  :ignored})))))))
   nil))

(defn commit-resources-restore-traces!
  "Emit the resources restore-reconcile trace rows DEFERRED by
  `reconcile-runtime-db-on-restore`, consulting the late-bound
  `:resources/commit-restore-reconcile!` hook with the reconciled runtime-db
  partition of `frame-state`. Invoked by `perform-restore!` ONLY AFTER a
  successful `replace-frame-state!` install, so the `:rf.resource/restored` /
  `:rf.resource/owner-released` success rows fire exactly once the restore has
  truly installed — never for a destroyed-frame install that wrote nothing.
  No-op when no resources artefact is loaded (hook nil), when the frame-state
  carries no runtime-db partition, or when the runtime-db carries no deferred
  intents (a resource-free restore). Returns nil.

  The commit walks a LIST of intents and each one emits to the frame's trace
  listeners, so it is a callback FAN-OUT (rf2-sdeae) that a single pre-call
  liveness check cannot fence. `frame-id` and the restore's EXACT
  `incarnation-token` are carried in under the SAME `:owner-token` opt the
  pre-write reconcile already takes, so the commit revalidates exact ownership
  at every intent boundary and stops announcing A's restore once a listener has
  seated a same-id successor B."
  [frame-state frame-id incarnation-token]
  (when-let [commit-restore-reconcile!
             (late-bind/get-fn :resources/commit-restore-reconcile!)]
    (when-let [runtime-db (get frame-state frame/runtime-partition-key)]
      (commit-restore-reconcile! runtime-db frame-id
                                 {:owner-token incarnation-token})))
  nil)

(defn perform-restore!
  "Install the target epoch's whole `:frame-state-after` after validation,
  FENCED to the exact frame incarnation the preconditions resolved against
  (`incarnation-token`, from `check-restore-preconditions!`).

  App-db and runtime-db are restored atomically; `:db-after` is only a tool
  projection. Optional subsystems reconcile captured durable state against
  current host state before install, deferring success traces until the write
  lands.

  The WHOLE restore is one EXACT-incarnation transaction, keyed on
  `incarnation-token`, not the bare id (rf2-bjh6y + rf2-qfrh4). A same-id
  SUCCESSOR frame reseated at ANY seam must not receive the resolved epoch's
  state, touch host tables, or be anchored. Four fences, all on the token:

    - an early `event-continuation-live?` gate refuses BEFORE running the
      subsystem reconcile, so nothing is reconciled against a stale incarnation;
    - the reconcile carries `incarnation-token` (seam 2) so its non-deferred
      pre-write host-table clear is fenced — a callback that churns A to B
      mid-reconcile cannot make the bare-id clear release B's host handles;
    - the physical install goes through the EXACT-INCARNATION
      `replace-frame-state!` arity, which returns nil unless the token still
      names the live record — so even the write itself can never redirect into a
      same-id successor;
    - each post-write bare-id tail op (last-settled anchor, deferred subsystem
      trace commit, host-work quiesce) re-checks `event-continuation-live?`
      (seam 3) — the `:rf.epoch/restored` emit and the commit/quiesce fan-outs
      are callback boundaries that can churn A to B, so a lost incarnation STOPS
      the remaining A-only tail work rather than RETARGETING it onto B;
    - the two tail ops that are themselves FAN-OUTS carry the token INSIDE
      (rf2-sdeae). A check taken before a loop does not fence the loop: the
      quiesce CHAIN walks several late-bound subsystem hooks and the deferred
      trace commit walks several intents, every element addressing the frame by
      bare id and every element app-observable. So `incarnation-token` is
      threaded into `quiesce-orphaned-async-host-work!` and
      `commit-resources-restore-traces!`, which revalidate exact ownership at
      EVERY hook / intent boundary and stop mid-fan-out.

  Every incarnation loss resolves to ONE coherent typed failure —
  `:rf.error/no-such-handler` (kind `:frame`), the same shape a destroyed-frame
  write race produces — and leaves any successor frame byte-for-byte untouched
  with no success telemetry. The write's non-nil return (a changed-key set,
  possibly empty) is success; the public result then stays TRUE even if a tail
  callback churns the incarnation. Only the success branch emits restore
  telemetry, re-anchors post-settle attribution, commits deferred subsystem
  traces, and cancels host-transient work from the abandoned timeline.

  The whole gate → reconcile → write → bookkeeping runs under the
  frame's `:drain-lock` (`serialize-tool-write!`), so no event transition can
  interleave between the reconcile's read and the physical install — the
  restore holds one serial position relative to any drain (rf2-3fc89f.4). A
  restore invoked reentrantly from the active drainer refuses with
  `:rf.epoch/restore-during-drain` rather than deadlocking or splicing."
  [frame-id incarnation-token epoch]
  (serialize-tool-write!
    frame-id
    :rf.epoch/restore-during-drain
    {:frame frame-id :rf.epoch/id (:epoch-id epoch)}
    (fn []
      (if-not (frame/event-continuation-live? frame-id incarnation-token)
        ;; The incarnation these preconditions resolved against is no longer
        ;; live — a same-id SUCCESSOR was seated (or the frame was destroyed /
        ;; is being torn down) between validation and this write. Refuse BEFORE
        ;; the reconcile so no subsystem state is reconciled against a stale
        ;; incarnation, and route to the SAME canonical no-such-handler failure a
        ;; destroyed-frame write race uses (rf2-bjh6y). The successor stays
        ;; byte-for-byte untouched; no success telemetry fires.
        (do (emit-precondition-failure! :rf.error/no-such-handler
                                        {:kind :frame :frame frame-id})
            false)
        (let [;; Whole `:frame-state-after` is the only restore source.
              recorded-frame-state (:frame-state-after epoch)
              ;; Reconcile runtime subsystems before the atomic install,
              ;; the same way SSR hydration reconciles its installed slice — so a
              ;; mid-flight captured snapshot does not install stranded
              ;; `:loading` / `:fetching` entries pointing at vanished attempts,
              ;; and a pre-restore reply cannot write stale data into a restored
              ;; entry.
              ;; Thread the restored epoch's causal time
              ;; (`:committed-at` = the committing token's `:rf.cofx`
              ;; `:rf/time-ms`) so the reconcile stamps a dangled-on-restore
              ;; mutation instance's durable `:settled-at` from a replay-stable
              ;; causal input, not the live install clock (EP-0010 §Restore/Replay).
              ;; Thread the EXACT incarnation token too, so the reconcile's
              ;; pre-write host-table clear is fenced to this incarnation — a
              ;; callback that churns A to B mid-reconcile cannot make the bare-id
              ;; clear release B's host handles (rf2-qfrh4 seam 2).
              reconciled-frame-state
              (reconcile-runtime-db-on-restore frame-id recorded-frame-state
                                               (:committed-at epoch)
                                               incarnation-token)
              ;; Write both partitions through the one physical frame container,
              ;; via the EXACT-INCARNATION arity: it resolves through the
              ;; validated incarnation's own record and returns nil if a same-id
              ;; successor has reseated `frame-id`, so the install can never
              ;; redirect into that successor. Under the drain lock the
              ;; container's re-read matches the value installed. Nil means the
              ;; incarnation was lost after the gate (destroyed, or reseated); a
              ;; non-nil changed-key-set (even empty) means it landed on the
              ;; exact incarnation.
              changed-keys
              (frame/replace-frame-state! frame-id incarnation-token
                                          reconciled-frame-state)]
          (if (nil? changed-keys)
            (do (emit-precondition-failure! :rf.error/no-such-handler
                                            {:kind :frame :frame frame-id})
                false)
            (do (trace/emit! :rf.epoch :rf.epoch/restored
                             {:frame       frame-id
                              :rf.epoch/id (:epoch-id epoch)})
                ;; The exact-incarnation install committed, so the public result
                ;; is TRUE and stays truthful regardless of what follows. But the
                ;; `:rf.epoch/restored` emit above is a synchronous callback
                ;; boundary: a trace listener can destroy A and seat a same-id
                ;; successor B. Every framework-owned tail op below addresses the
                ;; frame by BARE id (`set-last-settled-epoch!`, the resources
                ;; trace commit, the machines/http host-work quiesce chain), so
                ;; each is fenced to the EXACT incarnation the restore installed
                ;; (rf2-qfrh4 seam 3). Re-check liveness before each — the trace
                ;; commit and the quiesce chain themselves fan out to
                ;; listeners/hooks that may churn — so once A is lost the
                ;; remaining A-only tail work is STOPPED rather than RETARGETED
                ;; onto B: no B anchor is stamped, no B resource trace committed,
                ;; no B host handle released or aborted.
                (when (frame/event-continuation-live? frame-id incarnation-token)
                  ;; Restore triggers no ordinary event, so explicitly anchor its
                  ;; repaint/subscription/unmount back-fill to the restored epoch.
                  (state/set-last-settled-epoch! frame-id (:epoch-id epoch)))
                (when (frame/event-continuation-live? frame-id incarnation-token)
                  ;; Deferred subsystem success traces are valid only after install.
                  (commit-resources-restore-traces! reconciled-frame-state
                                                     frame-id incarnation-token))
                (when (frame/event-continuation-live? frame-id incarnation-token)
                  ;; Host timers and HTTP handles are not frame state; cancel the
                  ;; abandoned timeline only after the new state is installed.
                  (quiesce-orphaned-async-host-work! frame-id incarnation-token))
                true)))))))

;; ---- replace-frame-state! preconditions ------------------------------------
;;
;; `replace-frame-state!` accepts a partial map. Preconditions validate: bad
;; keys? (1) frame registered? (2) in-flight drain? (3) history disabled?
;; (4) schema mismatch on present partitions only.

(def ^:private frame-state-partition-keys
  "The two recognized `replace-frame-state!` partition keys — the closed
  key-vocabulary the bad-keys precondition (below) validates a caller's
  partial frame-state map against."
  #{frame/app-partition-key frame/runtime-partition-key})

(defn- replace-frame-state-bad-keys
  "Validate `frame-state`'s key set against the closed
  `frame-state-partition-keys` vocabulary.
  Returns nil when the map is well-formed (at least one recognized
  partition key, no unrecognized ones), otherwise a
  `{:reason <kw> :keys <vec>}` map describing the problem:

    `{:reason :unknown-keys :keys [...]}`        — the map carries a key
                                                    outside the closed
                                                    `#{:rf.db/app
                                                    :rf.db/runtime}`
                                                    vocabulary (a typo'd
                                                    partition key, e.g.
                                                    `:rf.db/apps`).
    `{:reason :no-recognized-keys :keys [...]}`  — the map carries NO
                                                    recognized partition
                                                    key at all (e.g. `{}`,
                                                    or a map of only
                                                    unrelated keys).

  `replace-frame-state!` is a PARTIAL-PATCH surface: a present key
  replaces that partition, an absent key is preserved. Without this
  check a caller's typo'd or empty map would silently no-op the whole
  call while still returning `true` and recording a synthetic no-op undo
  epoch — a false success against the caller's undo invariant. So a
  bad-shaped map is rejected loudly rather than treated as an empty
  partial patch."
  [frame-state]
  (let [frame-state-keys (set (keys frame-state))
        unknown-keys    (into [] (remove frame-state-partition-keys)
                              frame-state-keys)
        recognized-keys (filter frame-state-partition-keys frame-state-keys)]
    (cond
      (seq unknown-keys)       {:reason :unknown-keys
                                :keys   (vec frame-state-keys)}
      (empty? recognized-keys) {:reason :no-recognized-keys
                                :keys   (vec frame-state-keys)}
      :else                   nil)))

(defn check-replace-frame-state-preconditions!
  "Validate the documented preconditions for `replace-frame-state!`, the
  frame-state write surface.
  `frame-state` is a PARTIAL frame-state map (any subset of
  `{:rf.db/app … :rf.db/runtime …}`). Returns
  `{:outcome :ok :incarnation-token <token>}` when every check passes —
  `:incarnation-token` is the EXACT identity token of the frame incarnation
  these checks resolved against, derived from the SAME captured record (not
  a bare-id re-resolve), which `replace-frame-state!` carries to the write
  boundary so the physical write and the synthetic bookkeeping can never
  retarget onto a same-id SUCCESSOR seated after validation (rf2-gj2bo,
  mirroring `check-restore-preconditions!`). Otherwise
  `{:outcome :fail :op <kw> :tags <map>}` matching
  the precondition-failure shape of `check-restore-preconditions!`. Pure
  data — no trace events emitted from here; emission is the caller's job.

  Checks, in order:

    (0) Bad keys? `replace-frame-state-bad-keys` validates `frame-state`'s
        key set against the closed `#{:rf.db/app :rf.db/runtime}`
        vocabulary — checked FIRST, before frame resolution, because it is
        a caller-input-SHAPE error independent of the target frame's
        existence (the same malformed call is malformed against any
        frame). Failure: `:rf.error/replace-frame-state-bad-keys`.
    (1) Frame registered?
    (2) In-flight drain?
    (3) History disabled (depth 0)? The synthetic undo-anchor cannot land
        in the (disabled) ring, so the undo-works-after invariant is
        unsatisfiable, so reject rather than return a false success.
    (4) Schema mismatch? Validates ONLY the PRESENT partitions — an absent
        key is preserved, not written, so it is not walked: the app-db
        partition (when present) against the frame's app-schema set
        (`failing-schema-paths`) and/or the runtime-db partition (when
        present) against the framework-owned runtime-db validator
        (`failing-runtime-paths`, `reg-runtime-schema`), surfacing the
        UNION of failing paths — a failure on EITHER rejects the whole
        atomic install.

  `replace-frame-state!` records a synthetic `:rf.epoch/db-replaced` epoch so
  that `restore-epoch!` can rewind past
  the injection — the caller's invariant is \"undo works after this call\"
  (Tool-Pair §Pair-tool writes, the same invariant the artefact-missing
  wrapper raises to honour at `core-epoch.cljc`). Under
  `(rf/configure! {:epoch-history {:depth 0}})` the ring buffer is
  DISABLED by documented design (Tool-Pair §Time-travel — depth 0 retains
  no history; consume via `register-epoch-listener!`), so the synthetic
  undo-anchor can never land in the ring and `restore-epoch!` of it would
  fail `:rf.epoch/restore-unknown-epoch`. A silent `true` return would lie
  about the undo invariant exactly as a silent no-op would on the
  artefact-missing path. So a depth-0 injection is REJECTED loudly via the
  in-artefact failure channel (a structured `:rf.epoch/replace-history-disabled`
  trace + `false` return), mirroring the artefact-missing throw's intent."
  [frame-id frame-state]
  (if-let [{:keys [reason keys]} (replace-frame-state-bad-keys frame-state)]
    ;; (0) Bad keys? Independent of frame resolution — a malformed call is
    ;; malformed against any frame.
    {:outcome :fail
     :op      :rf.error/replace-frame-state-bad-keys
     :tags    {:frame frame-id :reason reason :keys keys}}

    (let [frame-result      (frame-exists-or-fail frame-id)
          ;; The EXACT incarnation identity token (the record's `:drain-lock`,
          ;; per `frame-incarnation-token`) DERIVED FROM THE SAME captured
          ;; record — NOT an independent bare-id re-resolve — mirroring
          ;; `check-restore-preconditions!` (rf2-gj2bo). Returned on the `:ok`
          ;; result so the write boundary can reject a stale injection after a
          ;; destroy + same-id reconstruction. nil when the frame is absent —
          ;; the (1) frame-registered branch fails first in that case.
          incarnation-token (some-> (:frame-record frame-result) :drain-lock)]
      (cond
        ;; (1) Frame registered?
        (= :fail (:outcome frame-result))
        frame-result

        ;; (2) In-flight drain?
        (drain-in-flight? (:frame-record frame-result))
        {:outcome :fail
         :op      :rf.epoch/replace-during-drain
         :tags    {:frame frame-id}}

        ;; (3) History disabled (depth 0)? The synthetic undo-anchor cannot
        ;; land in the (disabled) ring, so the undo-works-after invariant is
        ;; unsatisfiable, so reject rather than return a false success.
        (not (pos? (state/depth)))
        {:outcome :fail
         :op      :rf.epoch/replace-history-disabled
         :tags    {:frame frame-id}}

        :else
        ;; (4) Schema mismatch? Only the PRESENT partitions are walked — an
        ;; absent key is preserved, not written.
        (let [failing-paths
              (cond-> []
                (contains? frame-state frame/app-partition-key)
                (into (failing-schema-paths
                        frame-id (get frame-state frame/app-partition-key)))

                (contains? frame-state frame/runtime-partition-key)
                (into (failing-runtime-paths
                        frame-id (get frame-state frame/runtime-partition-key))))]
          (cond
            ;; Exact-owner gate on the validation snapshot (rf2-gj2bo,
            ;; mirroring `check-restore-preconditions!`'s history gate). The
            ;; schema/runtime validators above resolve the frame by BARE id;
            ;; a same-id successor seated DURING this precondition sampling
            ;; means the captured incarnation is no longer live, so those
            ;; walks may have validated against the successor. Refuse with
            ;; the SAME canonical no-such-handler failure the write boundary
            ;; uses — checked FIRST so a churned sample never surfaces as the
            ;; successor's schema verdict. (Belt-and-braces with the
            ;; record-derived token above: even if a race slips past here the
            ;; ticket still carries A's token, so the exact write rejects B.)
            (not (frame/event-continuation-live? frame-id incarnation-token))
            {:outcome :fail
             :op      :rf.error/no-such-handler
             :tags    {:kind  :frame
                       :frame frame-id}}

            (seq failing-paths)
            {:outcome :fail
             :op      :rf.epoch/replace-schema-mismatch
             :tags    {:frame         frame-id
                       :failing-paths failing-paths}}

            :else
            {:outcome :ok :incarnation-token incarnation-token}))))))

;; ---- projected egress -----------------------------------------------------
;;
;; Raw records are replay material and remain in-process. Every off-box consumer
;; uses `projected-record`: app-db-rooted trees go through the named egress
;; profile, runtime-db and unclassifiable transient payloads fail closed, and the
;; optional advanced override runs last on the projected copy. The facade
;; docstring is the public per-slot contract.

(def ^:private default-egress-profile
  "The default named export boundary for epoch off-box egress.

  An MCP or AI tool selects the
  `:rf.egress/off-box-tool` boundary instead via `projected-record`'s
  `:rf.egress/profile` opt — that profile keeps the same redact/elide
  defaults but turns on `:rf.size/include-digests?`, so a large owner-local
  slot egresses as a marker carrying the structural indicators / counters a
  tool needs to reason about shape without seeing content."
  :rf.egress/off-box-observability)

(defn- resolve-egress-profile
  "Resolve the named `:rf.egress/profile` an epoch egress call walks under.
  The caller names
  *\"which boundary is this?\"* (hosted observability vs. MCP/AI tool wire)
  rather than assembling boolean combinations. The default is
  `:rf.egress/off-box-observability` (hosted monitoring), so the bare
  1-arity / no-profile call is unchanged.

  An UNKNOWN profile is rejected loudly here against the shared CLOSED
  `re-frame.projection/profiles` enum, so a typo never falls through to a
  silently-permissive walk (it would otherwise reach `project-egress`'s
  own closed-profile guard, but failing fast at the epoch boundary keeps
  the error attributed to the epoch helper)."
  [profile]
  (let [profile (or profile default-egress-profile)]
    (when-not (contains? projection/profiles profile)
      ;; Share the projection layer's closed-enum error builder so wording and
      ;; the machine-readable token cannot drift.
      (throw (projection/unknown-egress-profile-ex 'epoch/projected-record profile)))
    profile))

(defn- egress-opts
  "Build the `project-egress` opts map from a `projected-record` egress
  opts map. The named `:rf.egress/profile`
  selects the boundary (default `:rf.egress/off-box-observability`); MCP /
  AI / tool consumers pass `:rf.egress/off-box-tool` to receive the
  structural marker indicators / counters the tool profile enables. The
  selected profile is the floor; the unqualified `:include-sensitive?`
  / `:include-large?` opts default `false` (the off-box safe path) and, when
  a trusted-local caller opts them back in, compose on top as ADVANCED
  explicit `:rf.size/*` overrides (the override wins — see
  `re-frame.projection/resolve-elision-opts`). The record frame is stamped
  so the frame's declared sensitive / large paths (keyed by absolute app-db
  path) match the projected value."
  [frame-id {:keys [include-sensitive? include-large?] :rf.egress/keys [profile]}]
  {:rf.egress/profile          (resolve-egress-profile profile)
   :frame                      frame-id
   :rf.size/include-sensitive? (boolean include-sensitive?)
   :rf.size/include-large?     (boolean include-large?)})

(defn- project-payload-slot
  "Project one payload slot through `project-egress` under the egress
  profile selected by `opts` (default `:rf.egress/off-box-observability`),
  rooted at the named frame.
  Off-box defaults (`:include-sensitive? false`, `:include-large? false`)
  hold unless `opts` opts back in. The epoch record is not a
  `:rf.observe/*` record kind, so the slot VALUE is projected as a kindless
  tree (the direct-read path → `elide-wire-value` against the frame's
  classification). Returns the projected value; `nil` slots are preserved
  as nil (halted records may have nil app-db slots; the projection
  MUST NOT fabricate a value)."
  [payload frame-id opts]
  (when (some? payload)
    (projection/project-egress payload (egress-opts frame-id opts))))

(defn- project-frame-state-slot
  "Project a `:frame-state-before` / `:frame-state-after` slot for off-box
  egress. The frame-state value is
  `{:rf.db/app <app-db> :rf.db/runtime <runtime-db>}`; the two partitions
  egress under DIFFERENT default policies:

    - `:rf.db/app` — the application state. Projected through the
      frame/profile `project-egress` walk (sensitive paths → `:rf/redacted`,
      large paths → markers), the SAME projection the `:db-before` /
      `:db-after` app-db projections receive. `opts` `:include-sensitive?` /
      `:include-large?` opt the app-db partition's privacy / size posture
      back in.

    - `:rf.db/runtime` — the framework runtime-db. Redacted by default off-box;
      the runtime-db side is substituted with the
      `:rf/redacted` sentinel rather than walked, so machine snapshots /
      route slice / SSR metadata do not egress to AI / log channels by
      default. The runtime-db partition boundary is ORTHOGONAL to the
      app-db `:include-sensitive?` / `:include-large?` opt-ins — those do
      NOT lift it. A trusted-local caller that
      genuinely needs runtime-db diagnostics opts in explicitly with
      `:include-runtime-db? true`; the runtime-db value is then projected
      through the same frame/profile walk (its own per-slot sensitive /
      large declarations still apply) rather than redacted whole.

  Nil-preserving (a halted-destroy record may carry a nil frame-state slot;
  the projection MUST NOT fabricate a value)."
  [frame-state frame-id {:keys [include-runtime-db?] :as opts}]
  (when (some? frame-state)
    (cond-> frame-state
      (contains? frame-state :rf.db/app)
      (update :rf.db/app projection/project-egress (egress-opts frame-id opts))
      ;; Default-redact runtime-db off-box. The
      ;; trusted-local `:include-runtime-db? true` opt-in lifts the
      ;; partition redaction; the value still rides the value walk so its
      ;; own sensitive / large declarations apply.
      (and (contains? frame-state :rf.db/runtime) (not include-runtime-db?))
      (assoc :rf.db/runtime :rf/redacted)

      (and (contains? frame-state :rf.db/runtime) include-runtime-db?)
      (update :rf.db/runtime projection/project-egress (egress-opts frame-id opts)))))

(defn- reroot-trace-event-db-slots
  "The `:rf.event/db-pending` (t1) and
  `:rf.event/db-pending-post-flow` (t2) trace events each carry the
  FULL pending `:db` value under `:tags :rf.event/db`. The bulk
  `project-payload-slot` above walks `:trace-events` as a single root —
  its path tracker treats the nested `:db` value as `[<i> :tags
  :rf.event/db :a :b …]`, so frame-declared sensitive paths like
  `[:auth :password]` do NOT match (the walker expects them rooted at
  the frame's app-db).

  This helper performs the per-event re-root: it walks each trace
  event whose `:operation` is in #{`:rf.event/db-pending`
  `:rf.event/db-pending-post-flow`}, then projects the inner `:db` value
  through `project-egress` with `{:path []}` — re-rooting the walk at
  the frame's app-db so the sensitive / large declarations match
  natively. Symmetric with how `flows.cljc` elides `:rf.flow/computed`'s
  `:result` / `:before` at emit-time using the flow's `:path`; here we
  do it at egress because the t1/t2 stamps are stored raw and the egress
  projection is the privacy boundary.

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
      (mapv (fn [trace-event]
              (if-not (map? trace-event)
                trace-event
                (let [operation (:operation trace-event)]
                  (if (and (or (= operation :rf.event/db-pending)
                               (= operation :rf.event/db-pending-post-flow))
                           (some? (get-in trace-event [:tags :rf.event/db])))
                    (update-in trace-event [:tags :rf.event/db]
                               projection/project-egress
                               wire-opts)
                    trace-event))))
            trace-events))))

;; ---- off-box HTTP response-body fail-closed -------------------------------
;;
;; EP-0015 disposition 5: a managed HTTP response body is a
;; registration-owned transient payload; an UNSCHEMATIZED body (no Malli
;; `:decode` schema) is whole-sensitive and OMITTED off-box. The on-box ring
;; keeps the raw body (the local operator sees their own process); the
;; off-box egress (`projected-record`) omits it.
;;
;; The body's shape cannot be proven from the trace event alone — the
;; request's `:decode` is request-private and never on the trace event — so
;; the HTTP emit site (`re-frame.http.transport`) STAMPS its off-box
;; disposition forward under `:tags :rf.http/off-box-body` (`:omit` for an
;; unschematized body, `:classify` for a schema body whose per-slot marks
;; were already applied on-box). This projector reads the stamp and OMITS
;; (replaces with `:rf/redacted`) the body slot(s) for an `:omit` event. A
;; `:classify` event's body already carries the classified projection
;; (sensitive → `:rf/redacted`, large → `:rf.size/large-elided`) from the
;; on-box emit, so it rides as-is. No HTTP-artefact dependency — only the
;; stamped keyword + the per-operation body-slot path(s) are read.
;;
;; The same disposition-5 rule applies to a raw error-response
;; body: `:rf.http/http-4xx` / `:rf.http/http-5xx` carry the raw response
;; body at `:body`, `:rf.http/decode-failure` carries the raw text at
;; `:body-text`, and `:rf.http/retry-attempt` nests the intermediate failure
;; (which may carry a raw `:body`/`:body-text`) under `:failure`. A raw error
;; body is UNSCHEMATIZED by construction (status classification runs before
;; decode; a decode-failure is the decode itself failing), so the emit site
;; always stamps `:omit` for these — irrespective of the per-call
;; `:sensitive?` flag. This projector omits whichever of the operation's
;; candidate body-slot paths are present.
;;
;; The omission is the off-box DEFAULT and is lifted only by an explicit
;; trusted-local `:include-sensitive?` opt-in (consistent with the
;; runtime-db / event-args opt-back-in), matching the `local-raw` boundary.

(def ^:private http-body-slots
  "The off-box-body-bearing tag-slot PATH(S) per `:rf.http/*` operation —
  the slots an `:rf.http/off-box-body :omit` stamp omits off-box:

   - `:rf.http/replied`        — the decoded body at `[:value]`;
   - `:rf.http/accept-failure` — the pre-`:accept` decoded body at `[:decoded]`;
   - `:rf.http/http-4xx` / `:rf.http/http-5xx` — the raw response body at
     `[:body]`;
   - `:rf.http/decode-failure` — the raw response text at `[:body-text]`;
   - `:rf.http/retry-attempt`  — the intermediate failure's raw body nested at
     `[:failure :body]` / `[:failure :body-text]`.

  A vector of slot PATHS (each a `get-in`/`assoc-in` path) so an operation
  whose body can ride in more than one slot (retry-attempt) is covered with
  one general rule rather than a per-slot special case.

  The five PRODUCTION-REAL operation rows (`:rf.http/replied`,
  `:rf.http/accept-failure`, `:rf.http/http-4xx`, `:rf.http/http-5xx`,
  `:rf.http/decode-failure`) are bound UNCONDITIONALLY — NOT behind
  `interop/debug-enabled?`. `projected-record` is a PURE off-box projection
  transform callable even when the debug gate is false (a JVM SSR process
  started with `RE_FRAME_DEBUG=false`, or an already-held / synthetic record —
  the gate elides record ASSEMBLY, not record PROJECTION). Its contract is
  FAIL-CLOSED on a stamped unschematized HTTP body, so the slots it consults
  must be present whether or not the gate was ever true. Gating the whole
  table (rf2-92uvq) left it `nil` in a cold gate-false process, so
  `omit-off-box-http-bodies` found no slot path and passed every stamped body
  through raw — a mandatory-off-box-safe breach. These five operation keywords
  already ride the production bundle as `:kind`/`:operation` data values, so
  binding them here at namespace load leaks nothing new.

  ONLY the DEV-ONLY `:rf.http/retry-attempt` row stays behind the gate. That
  op is a dev-only trace op (Spec 014 §Retry and backoff — its only emit sites
  are `(when interop/debug-enabled? (trace/emit! :info :rf.http/retry-attempt …))`),
  so its keyword literal must NOT float into the `:advanced` production bundle
  (the Spec 009 elision probe / `scripts/check-elision.cjs` assert the
  `rf.http/retry-attempt` sentinel ABSENT). Under `:advanced` +
  `goog.DEBUG=false` the gated `assoc` is dead code, so the keyword constant
  DCEs and the table folds to the five production rows; on the JVM the gate is
  read once at load and a prod process simply omits the row. A retry-attempt
  record only ever exists in a debug build anyway, so omitting its row under
  prod is unreachable, not a fail-open."
  (cond-> {:rf.http/replied        [[:value]]
           :rf.http/accept-failure [[:decoded]]
           :rf.http/http-4xx       [[:body]]
           :rf.http/http-5xx       [[:body]]
           :rf.http/decode-failure [[:body-text]]}
    interop/debug-enabled?
    (assoc :rf.http/retry-attempt [[:failure :body] [:failure :body-text]])))

(defn- omit-off-box-http-bodies
  "Enforce the fail-closed rule for off-box HTTP body trace events. For each
  `:rf.http/*` event whose emit
  site stamped `:tags :rf.http/off-box-body :omit` (an UNSCHEMATIZED response
  body — whole-sensitive off-box, including every RAW error-response body),
  substitute the `:rf/redacted` sentinel for each of the operation's body-slot
  paths that is present (e.g. `[:value]` for `:rf.http/replied`, `[:body]` for
  `:rf.http/http-4xx`/`:rf.http/http-5xx`, `[:body-text]` for
  `:rf.http/decode-failure`, `[:failure :body]`/`[:failure :body-text]` for
  `:rf.http/retry-attempt`). A `:classify` stamp (a schema-classified body)
  passes through — its per-slot marks were applied on-box at emit. Events with
  no stamp (every non-HTTP event) pass through untouched.

  The omission is the off-box default; an explicit trusted-local
  `:include-sensitive?` opt-in lifts it (the `local-raw` boundary).
  Idempotent and nil-preserving."
  [trace-events {:keys [include-sensitive?]}]
  (if (or include-sensitive? (not (sequential? trace-events)))
    trace-events
    (mapv (fn [trace-event]
            (if-not (map? trace-event)
              trace-event
              (let [body-slot-paths
                    (get http-body-slots (:operation trace-event))]
                (if (and body-slot-paths
                         (= :omit (get-in trace-event
                                         [:tags :rf.http/off-box-body])))
                  (reduce (fn [trace-event body-slot-path]
                            (let [tag-path (into [:tags] body-slot-path)]
                              (if (some? (get-in trace-event tag-path))
                                (assoc-in trace-event tag-path :rf/redacted)
                                trace-event)))
                          trace-event
                          body-slot-paths)
                  trace-event))))
          trace-events)))

(def ^:private event-arg-tag-slots
  "The trace-event tag slots that carry the dispatched event vector
  `[<event-id> <arg> …]`. The same registration-owned
  transient event args the `:trigger-event` record slot carries ride here,
  on EVERY `:rf.event/*` op of a cascade (`:rf.event/dispatched`,
  `:rf.event/run-start`, `:rf.event/db-pending`, `:rf.event/db-changed`,
  `:rf.event/run-end`, …) under `:rf.event/v`, and on the `:rf.error/*`
  handler-exception traces under the bare `:event` slot
  (`HandlerExceptionTags`, Spec-Schemas §`:rf/error-event`). The classification
  chokepoint (`re-frame.classification/project-trace-event`) redacts these against
  the event's REGISTRATION classification at emit time, but UNMARKED args (a bare
  positional secret like `[:login \"topsecret\"]`, or a map arg with no
  declaration) are passed through raw, and the on-box ring stores the raw
  event for `restore-epoch!` fidelity — so off-box egress must fail closed
  here, the same posture as the `:trigger-event` record slot."
  [:rf.event/v :event])

(defn- omit-off-box-event-args
  "Enforce the registration-owned-transient
  fail-closed rule on the dispatched-event-vector tag slots
  (`event-arg-tag-slots`) of every trace event. For each slot present whose
  value is the event vector `[<id> <arg> …]`, retain the head event-id
  keyword and redact every arg to `:rf/redacted` — the SAME shape the
  `:trigger-event` record slot egresses (see `elide-trigger-event-slot`).
  This closes the sibling leak whereby a secret carried in the dispatched
  event vector survives off-box inside `:trace-events` (the marks
  chokepoint cannot prove an UNMARKED arg safe, and the on-box ring keeps
  the raw event).

  The redaction is the off-box default; the trusted-local
  `:include-event-args? true` opt-in lifts it (the same opt that lifts the
  `:trigger-event` redaction — one event-args keyspace, one switch).
  Orthogonal to the app-db `:include-sensitive?` / `:include-large?`
  opt-ins. Idempotent (a `[<id> :rf/redacted …]` re-redacts to the same
  sentinels) and nil/non-sequential-preserving."
  [trace-events {:keys [include-event-args?]}]
  (if (or include-event-args? (not (sequential? trace-events)))
    trace-events
    (mapv (fn [trace-event]
            (if-not (map? trace-event)
              trace-event
              (reduce (fn [trace-event tag-slot]
                        (let [tag-path     [:tags tag-slot]
                              event-vector (get-in trace-event tag-path)]
                          (if (and (vector? event-vector) (seq event-vector))
                            (assoc-in trace-event tag-path
                                      (into [(first event-vector)]
                                            (repeat (dec (count event-vector))
                                                    :rf/redacted)))
                            trace-event)))
                      trace-event
                      event-arg-tag-slots)))
          trace-events)))

(defn- omit-off-box-resource-scope-values
  "Redact the resolver-owned values on a
  `:rf.resource/scope-resolved` trace row for off-box egress. The row carries
  the resolved `:input-values` (the concrete db reads) and the derived `:scope`
  (which embeds them) — owner-local identity-bearing values the generic
  value-path egress walk cannot classify once copied into trace tags. The
  resource family owns the row's egress projector (the SSR/tool-projection
  analogue), consulted here through the late-bound
  `:resources/project-scope-resolved-egress` hook the resources artefact
  publishes: it UNCONDITIONALLY FAILS CLOSED (redacts `:input-values` /
  `:scope`, stamps `:sensitive? true`) for every db-reading resolver — there
  is no declassify hatch (EP-0025 retired the `:rf.egress/output-sensitivity`
  propagation model) — preserving the structural `:resource-id` / `:inputs`
  (declared NAMES) / `:kind` / `:resolved-nil?`.

  The redaction is the off-box default; the trusted-local `:include-sensitive?`
  opt-in lifts it (the `local-raw` boundary — the same switch the app-db /
  HTTP-body redactions honour). No-op when no resources artefact is loaded (the
  hook is nil — an app with no resources emits no scope-resolved rows anyway).
  Idempotent (`:rf/redacted` re-redacts to itself) and nil/non-sequential-
  preserving."
  [trace-events {:keys [include-sensitive?]}]
  (if (or include-sensitive? (not (sequential? trace-events)))
    trace-events
    (if-let [project-scope-resolved-tags
             (late-bind/get-fn :resources/project-scope-resolved-egress)]
      (mapv (fn [trace-event]
              (if (and (map? trace-event)
                       (= :rf.resource/scope-resolved
                          (:operation trace-event))
                       (map? (:tags trace-event)))
                (update trace-event :tags project-scope-resolved-tags)
                trace-event))
            trace-events)
      trace-events)))

(defn- resource-family-op?
  "Whether a trace op belongs to the resource / mutation egress-record family —
  a `:rf.resource/*` or `:rf.mutation/*` keyword whose tags may embed
  owner-local scoped keys. Also covers the resources-family
  DIAGNOSTICS that ride the `:rf.warning/*` namespace but carry the same
  owner-local scoped-key / scope tags — `:rf.warning/resource-*` (e.g.
  `:rf.warning/resource-load-more-owner-ignored`, which carries a
  `:resource/key`). Those rows must take the SAME fail-closed family egress
  projection as the `:rf.resource/*` rows, not slip through the
  scoped-key-blind generic walk."
  [operation]
  (and (keyword? operation)
       (when-let [operation-namespace (namespace operation)]
         (or (= "rf.resource" operation-namespace)
             (= "rf.mutation" operation-namespace)
             (and (= "rf.warning" operation-namespace)
                  (str/starts-with? (name operation) "resource-"))))))

(defn- omit-off-box-resource-trace-keys
  "Redact the owner-local scoped keys embedded in the
  BROADER resource/mutation trace family's tag slots for off-box egress — the
  family-level companion to `omit-off-box-resource-scope-values`. The
  `:rf.resource/*` + `:rf.mutation/*` rows copy scoped keys into `:resource/key`
  (single key), `:resource/keys` / `:matched` / `:removed` / `:keys` /
  `:exempt` / `:committed` / `:restored` / `:conflicted` / `:refetched`
  (key vectors), and the optimistic-rollback `:dispositions` (per-key maps) —
  owner-local identity-bearing values the generic value-path egress walk cannot
  classify once copied into trace tags.

  The resource family owns the family-level egress projector, consulted here
  through the late-bound `:resources/project-resource-trace-egress` hook the
  resources artefact publishes: it projects each scoped key through the resource
  OWNER classification (a `:sensitive?` / `:large?` / derived-sensitive owner
  tokenizes scope + params; an unregistered owner FAILS CLOSED), preserving the
  structural resource-id + every non-key tag, and stamps `:sensitive? true` on a
  row whose key it redacted. Applied to EVERY resource/mutation-family row (the
  slot vocabulary is operation-agnostic — `resource-family-op?`), so a new row
  carrying a known slot is covered without enumerating its op.

  The owner classification resolves against `frame-id` (the record's frame —
  the named-scope-resolver derived-sensitivity inheritance reads it; a per-row
  `:rf.frame/id` tag, when present, takes precedence so a cross-frame row
  classifies against its own owner).

  The redaction is the off-box default; the trusted-local `:include-sensitive?`
  opt-in lifts it (the `local-raw` boundary — the same switch the app-db /
  HTTP-body / scope-resolved redactions honour). No-op when no resources
  artefact is loaded (the hook is nil — an app with no resources emits no
  resource/mutation rows anyway). Idempotent (an opaque token re-projects to
  itself) and nil/non-sequential-preserving."
  [trace-events frame-id {:keys [include-sensitive?]}]
  (if (or include-sensitive? (not (sequential? trace-events)))
    trace-events
    (if-let [project-resource-trace-tags
             (late-bind/get-fn :resources/project-resource-trace-egress)]
      (mapv (fn [trace-event]
              (if (and (map? trace-event)
                       (resource-family-op? (:operation trace-event))
                       (map? (:tags trace-event)))
                (update trace-event :tags project-resource-trace-tags
                        (or (:rf.frame/id (:tags trace-event)) frame-id))
                trace-event))
            trace-events)
      trace-events)))

(defn- omit-off-box-fx-args-resource-keys
  "Redact the owner-local scoped keys the resource family plants in the FX-ARGS
  trace tags — the SLOT-reached companion to `omit-off-box-resource-trace-keys`
  directly above (rf2-1kiuj).

  That arm routes on `resource-family-op?`, the row's OPERATION NAMESPACE. But a
  resource `ensure` lowers into EFFECTS, and those effects address the work BY its
  scoped key, so the family's keys also ride `:rf.fx/args` (stamped by
  `re-frame.fx/handle-one-fx` on `:rf.fx/handled`, on
  `:rf.fx/skipped-on-platform`, and on the always-on `:rf.error/*` fx-failure
  traces) and `:rf.event/fx` (the whole effect vector, stamped by `do-fx`). Those
  rows are `rf.fx` / `rf.error`, so the namespace routing skips them, and a
  resolver-owned key's embedded scope + params are not app-db-rooted, so the
  generic `project-egress` walk cannot classify them either. Between the two blind
  spots a naturally-captured `ensure` record egressed a `:sensitive?` owner's
  resolved scope + canonical params RAW at eighteen paths — while the SAME
  payload's structured `:effects[*].args` slot, three rows below, read
  `:rf/redacted` (`elide-effect-row`). One value, two carriers, one rule applied:
  the rf2-irwsq shape.

  The projector is the resource family's, consulted through the late-bound
  `:resources/project-fx-args-egress` hook; it walks the two slots by SHAPE and
  projects each embedded key through the SAME owner classification the family
  rows' `:resource/key` takes, so the two carriers of one key cannot drift and a
  PLAIN owner's fx args still ride verbatim. Applied to EVERY row rather than to a
  roster of ops: the hook is reference-preserving on a tags map carrying neither
  slot, so nothing here has to be kept in step with the fx emit sites — which is
  the same reason `project-fx-tags` keys off the slot pair rather than off
  `:rf.fx/handled` at the emit end.

  Same posture as its sibling in every other respect: off-box default, lifted by
  the trusted-local `:include-sensitive?`, no-op when no resources artefact is
  loaded, idempotent, nil/non-sequential-preserving."
  [trace-events frame-id {:keys [include-sensitive?]}]
  (if (or include-sensitive? (not (sequential? trace-events)))
    trace-events
    (if-let [project-fx-arg-tags
             (late-bind/get-fn :resources/project-fx-args-egress)]
      (mapv (fn [trace-event]
              (if (and (map? trace-event) (map? (:tags trace-event)))
                (update trace-event :tags project-fx-arg-tags
                        (or (:rf.frame/id (:tags trace-event)) frame-id))
                trace-event))
            trace-events)
      trace-events)))

(defn- elide-whole-output-large-slots
  "The ONE whole-output `:large?` egress rule (rf2-irwsq).

  A `:large?`-stamped sub's computed value reaches off-box egress through TWO
  slots of the same record, and BOTH are projected by this function so they
  structurally cannot drift:

    - the structured `:sub-runs` row      — `:value` / `:prev-value`
    - the `:rf.sub/run` trace tag        — `:rf.sub/value` / `:rf.sub/prev-value`

  Both carry the raw value with a bare `:large?` flag beside it: the emit-time
  chokepoint (`classification/project-sub-tags`) stamps the flag and
  DELIBERATELY leaves the value in place so the on-box ring keeps the exact
  value (Xray diff / `restore-epoch!` need it), and `capture/sub-run-row`
  threads that same flag onto the row. The flag is therefore the whole
  contract, and honouring it is this seam's job — a REGISTRATION marker, not a
  path declaration, so neither the app-db-rooted walker nor the marks emit site
  can act on it.

  Given the `slot-map` container and its two value-slot keys, substitutes the
  canonical `:rf.size/large-elided` marker (`classification/large-marker`) for
  each PRESENT slot and strips the now-spent `:large?` flag so the projected
  shape matches the on-box base shape's metadata. A MARKER, not a drop: a tool
  reading the projection must be able to tell that a value existed and was
  withheld, which a silent drop would make indistinguishable from a sub that
  produced nothing.

  The marker's `:path` names the slot within its own container (`[:value]` /
  `[:rf.sub/value]`), so the two projections agree on `:bytes` / `:type` /
  `:reason` while each still names the slot it replaced.

  `opts` `:include-large? true` is the trusted-local opt-in: it keeps the raw
  value in both slots. The `:large?` flag is stripped either way.

  Idempotent: a slot already carrying a marker is left untouched (rebuilding a
  marker over a marker would report the marker's own size).

  Per-PATH `:large` / `:sensitive` sub marks are NOT handled here — those are
  substituted INTO the value at the marks emit site (`redact-with-paths`), so
  they already ride both slots pre-marked. EP-0025 dropped the whole-output
  `:sensitive?` overload, so there is no sensitive analogue of this rule: the
  axis here is TOKEN BUDGET (a bulky derived value burning an off-box
  consumer's context window), not privacy. Because the per-path sensitive
  substitution already happened at emit, the marker's `:bytes` is measured over
  a value whose secrets are already `:rf/redacted` — no size oracle over live
  secret bytes. And no digest oracle either, at this seam by construction: the
  marker is built through `classification/large-marker`, which reaches
  `elision/->marker` WITHOUT `include-digests?`, so a whole-output marker
  carries no `:digest` under ANY egress profile — including
  `:rf.egress/off-box-tool`, where a PATH-declared marker does get one."
  [slot-map {:keys [include-large?]} value-key prev-value-key]
  (let [mark-slot-value
        (fn [slot-key]
          (fn [slot-value]
            (if (elision/marker? slot-value)
              slot-value
              (classification/large-marker slot-value [slot-key]))))]
    (cond
      (not (:large? slot-map)) slot-map
      include-large?            (dissoc slot-map :large?)
      :else
      (-> slot-map
          (cond-> (contains? slot-map value-key)
            (update value-key (mark-slot-value value-key))
            (contains? slot-map prev-value-key)
            (update prev-value-key (mark-slot-value prev-value-key)))
          (dissoc :large?)))))

(defn- elide-large-sub-trace-values
  "Project the `:rf.sub/run` trace tags' value slots for off-box egress
  (rf2-irwsq).

  The trace-tag TWIN of `elide-sub-run-row`: the same whole-output `:large?`
  sub value rides `[:trace-events <i> :tags :rf.sub/value]` (plus
  `:rf.sub/prev-value` on a reactive recompute) as well as the structured
  `:sub-runs` row, and before this step egress elided only the row — so the
  raw payload still reached every off-box consumer that reads `:trace-events`
  (Xray-MCP `watch-epochs`, Pair-MCP `trace-window` / `watch-epochs` /
  `snapshot`, hosted log shippers). Under the shipped `:trace-events-keep 50`
  every record in the ring keeps its trace-events, so the payload rode on
  EVERY record for that cascade.

  Delegates to the shared `elide-whole-output-large-slots` rule — the same
  code path the row uses — so the two egress projections cannot drift.
  `:rf.sub/run` is the only operation whose tags can carry the flag
  (`classification/project-sub-tags` is applied to that op alone), so this
  walk is scoped to it; every other event and every non-value sub tag
  (`:rf.sub/id`, `:rf.sub/query-v`, `:rf.sub/elapsed-ms`, …) rides through
  untouched. Nil- and non-sequential-preserving."
  [trace-events opts]
  (if-not (sequential? trace-events)
    trace-events
    (mapv (fn [trace-event]
            (if (and (map? trace-event)
                     (= :rf.sub/run (:operation trace-event))
                     (map? (:tags trace-event)))
              (update trace-event :tags elide-whole-output-large-slots opts
                      :rf.sub/value :rf.sub/prev-value)
              trace-event))
          trace-events)))

(defn- elide-trace-events-slot
  "Project `:trace-events`: first re-root the
  per-event `:rf.event/db` slots on the t1 / t2 trace events so the
  sensitive / large declarations match natively, then fail closed on the
  dispatched-event-vector args (`:rf.event/v` / `:event`
  carry the same registration-owned transient args as `:trigger-event`,
  which the app-db walker cannot prove safe), then enforce the off-box
  HTTP response-body fail-closed rule, then run the bulk
  frame/profile `project-egress` walk over the
  whole vector to handle the other payload-bearing tag values
  (`:rf.cofx/value`, etc.) with their own per-tag paths. `opts`
  `:include-sensitive?` / `:include-large?` / `:include-event-args?` opt
  the per-call posture back in. Idempotent (a
  second pass walks already-redacted scalars). Nil-preserving."
  [trace-events frame-id opts]
  (when (some? trace-events)
    (-> trace-events
        (reroot-trace-event-db-slots frame-id opts)
        (omit-off-box-event-args opts)
        (omit-off-box-http-bodies opts)
        ;; Redact resolver-owned values copied into resource trace tags.
        (omit-off-box-resource-scope-values opts)
        ;; Redact owner-local scoped keys embedded in the
        ;; broader `:rf.resource/*` / `:rf.mutation/*` trace family's tag slots
        ;; (`:resource/key` / `:resource/keys` / `:matched` / `:removed` /
        ;; rollback `:dispositions` / …) — the family-level companion to the
        ;; scope-resolved projector above; same fail-closed off-box default.
        (omit-off-box-resource-trace-keys frame-id opts)
        ;; …and the SAME keys where they ride the FX-ARGS carriers
        ;; (`:rf.fx/args` / `:rf.event/fx`) of rows the family does NOT own —
        ;; reached by SLOT, since the arm above is reached by op namespace and a
        ;; lowered `ensure` addresses its work by scoped key inside the effects
        ;; (rf2-1kiuj).
        (omit-off-box-fx-args-resource-keys frame-id opts)
        ;; Honour the whole-output `:large?` stamp on the `:rf.sub/run` tags —
        ;; the trace-tag twin of the `:sub-runs` row elision, sharing one rule
        ;; with it (rf2-irwsq). Runs BEFORE the bulk walk so the marker is
        ;; computed over the same emit-projected value the row's marker is
        ;; built from, keeping the two markers' `:bytes` / `:type` in agreement;
        ;; `project-egress` has no sub-specific arm, so ordering is otherwise
        ;; immaterial.
        (elide-large-sub-trace-values opts)
        (projection/project-egress (egress-opts frame-id opts)))))

(defn- elide-sub-run-row
  "Project a structured `:sub-runs` row. Its `:prev-value` / `:value`
  slots hold computed app data, so unlike the non-value
  metadata (`:sub-id`, `:query-v`, `:value-changed?`, `:cascade?`,
  `:cause-sub`, `:cause-event-id`), they MUST respect the projection
  contract.

  The whole-output `:sensitive?` case is already redacted at the marks
  emit site (the slots arrive carrying `:rf/redacted`), so no work is
  needed here for sensitive. The whole-output `:large?` case is carried
  on the row as `:large?` (threaded by `capture/sub-run-row`) with the
  RAW value still attached (the on-box ring keeps the exact value), and
  is projected by the SHARED `elide-whole-output-large-slots` rule — the
  same code path the `:rf.sub/run` trace tag's value slots go through
  (`elide-large-sub-trace-values`), so the two egress projections of the
  same value cannot drift (rf2-irwsq). See that rule for the marker
  substitution, the spent-flag strip, idempotence, and the
  `:include-large?` opt-in.

  Per-PATH large declarations (a sub with `:large [<path>]` marks but no
  whole-output `:large?` stamp) are already substituted INTO the value
  at the marks emit site (`redact-with-paths`), so they ride the row
  pre-marked and need no projection here."
  [sub-run-row opts]
  (elide-whole-output-large-slots sub-run-row opts :value :prev-value))

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
  "Project one structured `:effects` row for off-box egress.

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

  `opts` `:include-fx-args? true` is the trusted-local opt-in; it
  keeps the raw `:args`. It is ORTHOGONAL to the app-db `:include-sensitive?`
  / `:include-large?` opt-ins (fx args are a different keyspace, not app-db
  values), so those do NOT lift it.

  Idempotent: a row whose `:args` was already replaced with `:rf/redacted`
  re-redacts to the same sentinel. A row carrying no `:args` key passes
  through (no slot to fabricate)."
  [effect-row {:keys [include-fx-args?]}]
  (if (or include-fx-args? (not (contains? effect-row :args)))
    effect-row
    (assoc effect-row :args :rf/redacted)))

(defn- elide-effects-slot
  "Project the structured `:effects` vector for off-box egress:
  walk each row through `elide-effect-row`, fail-closed-redacting the
  payload-bearing `:args` slot. Nil- and non-sequential-preserving (a
  `:redact-fn` may have already replaced the whole slot with a scalar
  sentinel)."
  [effects opts]
  (if-not (sequential? effects)
    effects
    (mapv #(elide-effect-row % opts) effects)))

(defn- elide-trigger-event-slot
  "Project the `:trigger-event` slot for off-box egress.

  `:trigger-event` is the FULL dispatched event vector — `[<event-id>
  <arg> …]` (e.g. `[:login \"topsecret\"]`, `[:auth/login {:password p}]`).
  Per EP-0015 / Spec 015 §Registration-owned transient classification
  (`015-Data-Classification.md` §151) the event ARGS are
  registration-owned transient payloads, NOT frame-app-db-owned data — the
  same class as the `:effects` `:args` slot. They are captured verbatim from the
  `:rf.event/v` trace tag (`capture/find-trigger-event`); they are NOT
  routed through the marks-projection chokepoint at emit time, NOR are they
  rooted at the frame's app-db, so the schema-path-keyed `elide-wire-value`
  walker cannot prove any of them safe. (Routing this slot through the
  generic `project-payload-slot` would root the walk at the frame's app-db
  classification — so a secret carried positionally in the event vector,
  e.g. `[:login \"topsecret\"]`, would egress RAW because no app-db
  sensitive declaration matches the trigger-event path.)

  A safe per-event projection cannot be proven, so off-box egress FAILS
  CLOSED (Spec 009 §Privacy / sensitive data in traces + Security.md
  §Off-box egress): the ARGS are redacted by default while the head
  `<event-id>` keyword — a non-identity, non-payload field (it is the SAME
  value the record carries in its bare `:event-id` slot, per Spec-Schemas
  §`:rf/epoch-record`) — is retained as the event-id SUMMARY. So
  `[:login \"topsecret\"]` egresses as `[:login :rf/redacted]`: a consumer
  still sees WHICH event ran, never its args. Fail-closed covers BOTH
  unmarked positional args (`[:login \"topsecret\"]`) and unmarked map args
  (`[:auth/login {:password p}]`) — neither can be matched by the app-db
  classification walker. The whole-vector redaction (`:rf/redacted`) is
  reserved for a degenerate non-vector or empty slot, which the open record
  schema admits (Spec-Schemas §`:rf/epoch-record` — consumers MUST tolerate
  `:rf/redacted` at `:trigger-event`).

  `opts` `:include-event-args? true` is the trusted-local opt-in that keeps
  raw args. It is orthogonal to
  the app-db `:include-sensitive?` / `:include-large?` opt-ins (event args
  are a different keyspace, not app-db values), so those do NOT lift it,
  matching the `:effects` `:args` / runtime-db boundaries.

  Idempotent: a second pass over an already-projected `[<id> :rf/redacted
  …]` re-redacts the (already-`:rf/redacted`) tail to the same sentinels.
  Nil-preserving (a halted record may carry no `:trigger-event`)."
  [trigger-event {:keys [include-event-args?]}]
  (cond
    (or include-event-args? (nil? trigger-event)) trigger-event
    ;; The canonical shape is a non-empty event vector `[<id> <arg> …]`.
    ;; Retain the head event-id keyword (the non-payload summary); fail
    ;; closed on every positional / map arg in the tail.
    (and (vector? trigger-event) (seq trigger-event))
    (into [(first trigger-event)]
          (repeat (dec (count trigger-event)) :rf/redacted))
    ;; Degenerate non-vector / empty slot (or a `:redact-fn` that already
    ;; substituted a scalar sentinel) — redact wholesale; nothing safe to
    ;; expose, and the open schema admits `:rf/redacted` here.
    :else :rf/redacted))

(defn projected-record
  "Internal projection engine for off-box epoch egress. The public contract
  lives on `re-frame.epoch/projected-record`.

  Each present payload slot is delegated to its own helper under the selected
  frame/profile. Record bookkeeping and absent slots remain unchanged. The
  configured advanced override runs last over the built-in projection, never
  the raw ring. A throwing override falls back to the built-in result.
  Non-map input returns nil."
  ([record] (projected-record record nil))
  ([record opts]
   (when (map? record)
     (let [frame-id (:frame record)
           built-in-projected-record
           (cond-> record
             ;; Whole-frame slots project app-db and redact runtime-db
             ;; unless the corresponding trusted-local opts lift them.
             (contains? record :frame-state-before)
             (update :frame-state-before project-frame-state-slot frame-id opts)

             (contains? record :frame-state-after)
             (update :frame-state-after project-frame-state-slot frame-id opts)

             (contains? record :db-before)
             (update :db-before project-payload-slot frame-id opts)

             (contains? record :db-after)
             (update :db-after project-payload-slot frame-id opts)

             ;; Trigger args are not app-db-rooted and fail closed.
             (contains? record :trigger-event)
             (update :trigger-event elide-trigger-event-slot opts)

             (contains? record :trace-events)
             (update :trace-events elide-trace-events-slot frame-id opts)

             (contains? record :sub-runs)
             (update :sub-runs elide-sub-runs-slot opts)

             ;; Effect args are not app-db-rooted and fail closed.
             (contains? record :effects)
             (update :effects elide-effects-slot opts))]
       ;; Apply the advanced override only to the projected copy.
       (assembly/apply-redact-fn built-in-projected-record)))))

(defn projected-history
  "INTERNAL — the public contract lives on
  `re-frame.epoch/projected-history`. Maps `projected-record` over the
  frame's raw ring (`state/history-for`), threading `opts` to each record;
  the 1-arity is the fully-redacted off-box path."
  ([frame-id] (projected-history frame-id nil))
  ([frame-id opts]
   (mapv #(projected-record % opts) (state/history-for frame-id))))
