(ns re-frame.epoch.write
  "Tool-Pair write surfaces — the preconditions, restore-perform, and
  off-box projection helpers behind `restore-epoch`, `reset-frame-db!`,
  `projected-record`, and `projected-history`.

  Responsibilities:

    * **Precondition validators** — `check-restore-preconditions!` and
      `check-reset-frame-db-preconditions!` are pure data transforms
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
      route the four payload-bearing slots through
      `re-frame.elision/elide-wire-value` for off-box egress
      (Causa-MCP `watch-epochs`, story / pair recorders).

  Per rf2-0wi86 Phase-2 seam E. The orchestrators `restore-epoch` and
  `reset-frame-db!` live in the `re-frame.epoch` facade — they wire
  the precondition check + the trace emission + the perform / listener
  fan-out steps together. Pure-data shape of the preconditions makes
  the orchestrators a four-line case-match."
  (:require [re-frame.elision :as elision]
            [re-frame.epoch.assembly :as assembly]
            [re-frame.epoch.state :as state]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
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

(defn- machine-definition-version
  "Read the currently-registered machine definition's
  `:rf/snapshot-version`. Per Spec 005 §Snapshot shape — the
  definition's `:meta :rf/snapshot-version` is the canonical slot."
  [machine-id]
  (when-let [reg (machine-registration machine-id)]
    (let [machine (:rf/machine reg)]
      (get-in machine [:meta :rf/snapshot-version]))))

(defn missing-references
  "Walk the recorded db for ids that are no longer present in the
  registrar. Closed v1 surface — `:rf/machines` (each machine-id
  must reference a registered machine via the public event registry,
  per Spec 005 §Registration — machines are event handlers tagged
  with `:rf/machine?`) and `:route` (`:id` must reference a registered
  :route).

  Per rf2-ocg1: machine lookup goes through the event registry, NOT
  the internal `:head` registrar kind. The latter is unrelated to
  the public machine contract.

  Returns a vector of {:kind <kind> :id <id>} entries. Empty when
  every reference resolves."
  [db]
  (let [;; Machines under :rf/machines: registered as event handlers with
        ;; :rf/machine? true (per Spec 005 §Registration).
        missing-machines
        (for [[machine-id _snapshot] (:rf/machines db)
              :when (not (machine-registration machine-id))]
          {:kind :machine :id machine-id})
        ;; Active route
        missing-route
        (when-let [route-id (get-in db [:rf/route :id])]
          (when-not (registrar/lookup :route route-id)
            [{:kind :route :id route-id}]))]
    (vec (concat missing-machines missing-route))))

(defn machine-version-mismatch
  "Walk the recorded db's `:rf/machines` for snapshot version drift.
  The recorded snapshot may carry `:rf/snapshot-version` under
  `:meta`; the registered machine definition carries
  `:rf/snapshot-version` under its own `:meta`. When they differ,
  return the first mismatch as
  `{:machine-id <id> :recorded <int> :current <int>}`. nil when no
  mismatch is found.

  Per rf2-ocg1: both versions are read through the public Spec 005
  §Snapshot shape contract — the snapshot's `[:meta :rf/snapshot-version]`
  and the registered machine's `[:meta :rf/snapshot-version]`."
  [db]
  (some (fn [[machine-id snapshot]]
          (let [recorded (snapshot-version snapshot)]
            (when (some? recorded)
              (let [current (machine-definition-version machine-id)]
                (when (and (some? current) (not= recorded current))
                  {:machine-id machine-id
                   :recorded   recorded
                   :current    current})))))
        (:rf/machines db)))

;; ---- shared precondition helpers ------------------------------------------

(defn- find-epoch-in
  "Search a resolved history vector for the record matching `epoch-id`.
  Caller has already paid the `@histories` deref — `check-restore-
  preconditions!` reads history once at the top and reuses the vector
  for both the lookup and the `:history-size` count on the
  unknown-epoch failure path (rf2-3g7x3 — was two derefs)."
  [history epoch-id]
  (some (fn [r] (when (= epoch-id (:epoch-id r)) r))
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
          (let [db-target (:db-after epoch)]
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

              (if-let [missing (seq (missing-references db-target))]
                ;; (5) Missing handler referenced from db?
                {:outcome :fail
                 :op      :rf.epoch/restore-missing-handler
                 :tags    {:frame    frame-id
                           :epoch-id epoch-id
                           :missing  (vec missing)}}

                (if-let [{:keys [machine-id recorded current]} (machine-version-mismatch db-target)]
                  ;; (6) Machine snapshot version drift?
                  {:outcome :fail
                   :op      :rf.epoch/restore-version-mismatch
                   :tags    {:frame            frame-id
                             :epoch-id         epoch-id
                             :machine-id       machine-id
                             :version-recorded recorded
                             :version-current  current}}

                  {:outcome :ok :epoch epoch})))))))))

(defn perform-restore!
  "Carry out the actual `app-db` rewind once preconditions have passed.
  Replaces the frame's container with `epoch`'s `:db-after` and emits
  `:rf.epoch/restored`. Returns `true`."
  [frame-id epoch]
  (let [container (frame/get-frame-db frame-id)
        db-target (:db-after epoch)]
    (adapter/replace-container! container db-target)
    (trace/emit! :rf.epoch :rf.epoch/restored
                 {:frame    frame-id
                  :epoch-id (:epoch-id epoch)})
    true))

;; ---- reset-frame-db! preconditions ----------------------------------------

(defn check-reset-frame-db-preconditions!
  "Validate the three documented preconditions for `reset-frame-db!`.
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
       :op      :rf.epoch/reset-frame-db-during-drain
       :tags    {:frame frame-id}}

      :else
      ;; (3) Schema mismatch? Single walk — `failing-schema-paths`
      ;; returns the failing paths (or [] for the valid / soft-pass
      ;; cases), folding what was previously a two-helper / two-walk
      ;; chain into one.
      (let [failing (failing-schema-paths frame-id new-db)]
        (if (seq failing)
          {:outcome :fail
           :op      :rf.epoch/reset-frame-db-schema-mismatch
           :tags    {:frame         frame-id
                     :failing-paths failing}}
          {:outcome :ok})))))

;; ---- projected egress -----------------------------------------------------
;;
;; Per Security.md §Epoch privacy posture and rf2-mrsck: the framework's
;; single normative projection emission site for off-box epoch egress.
;; The in-process ring buffer (`epoch-history`) and `register-epoch-listener!`
;; listener fan-out deliver RAW records — restore-epoch and on-box
;; devtools (Causa diff, REPL inspection) need them. Tools that
;; egress an epoch record across a process boundary (Causa-MCP
;; `watch-epochs`, story / pair recorders, hosted forwarders) MUST
;; route through `projected-record` first, parallel to how direct-read
;; tools route through `elide-wire-value` (per rf2-czv3p).
;;
;; The projection wraps `re-frame.elision/elide-wire-value` against the
;; record's frame-id and the four payload-bearing slots that may carry
;; sensitive or large material: `:db-before`, `:db-after`,
;; `:trigger-event`, and `:trace-events`. The cheap structured slots
;; (`:sub-runs`, `:renders`, `:effects`) carry no app-db material —
;; they project sub-ids, render-keys, fx-ids, and outcome tags — so
;; the projection leaves them as-is. The record-level bookkeeping
;; (`:epoch-id`, `:frame`, `:committed-at`, `:event-id`, `:outcome`,
;; `:halt-reason`, `:schema-digest`, `:rf.epoch/sensitive?`,
;; `:rf.epoch/redacted-modified-paths-count`) is structurally
;; non-sensitive and passes through.
;;
;; Per-tool reimplementation of the projection is prohibited (the
;; same posture as the wire-elision walker). New egress tools call
;; `projected-record` and trust the contract.

(defn- elide-payload-slot
  "Walk one payload slot through `elide-wire-value` rooted at the named
  frame, with off-box defaults (`:include-sensitive? false`,
  `:include-large? false`). Returns the elided value; `nil` slots are
  preserved as nil (a halted-destroy record's `:db-before` /
  `:db-after` are nil per rf2-v0jwt; the schema admits the absent /
  nil slot — the projection MUST NOT fabricate a value)."
  [v frame-id]
  (when (some? v)
    (elision/elide-wire-value v {:frame frame-id})))

(defn projected-record
  "Project an `:rf/epoch-record` for off-box egress. Routes the four
  payload-bearing slots (`:db-before`, `:db-after`, `:trigger-event`,
  `:trace-events`) through `re-frame.elision/elide-wire-value` against
  the record's frame, with the off-box defaults
  `:include-sensitive? false` / `:include-large? false`. Sensitive
  paths land as `:rf/redacted`; large paths land as
  `:rf.size/large-elided` markers per the §Composition rule. The
  record-level bookkeeping (`:epoch-id`, `:frame`, `:committed-at`,
  `:event-id`, `:outcome`, `:halt-reason`, `:schema-digest`,
  `:rf.epoch/sensitive?`, `:rf.epoch/redacted-modified-paths-count`)
  and the cheap structured projections (`:sub-runs`, `:renders`,
  `:effects`) pass through unchanged — they carry no app-db material.

  Per Security.md §Epoch privacy posture and rf2-mrsck: this is the
  single normative projection emission site for off-box egress. Tools
  that forward epoch records across a process boundary (Causa-MCP
  `watch-epochs`, story / pair recorders, hosted post-mortem
  forwarders) MUST route through this fn at the wire boundary; the
  on-box ring buffer and `register-epoch-listener!` listener fan-out
  continue to deliver the RAW record so on-box devtools (Causa diff,
  REPL, `restore-epoch`) can reason about exact state.

  `record` may be `nil` (e.g. a missing epoch lookup) — the projection
  returns `nil` in that case, no elision called. Production builds
  elide the entire epoch surface; consumers gate any
  `register-epoch-listener!` registration under `interop/debug-enabled?`
  per Spec 009 §User-side listener registration."
  [record]
  (when (map? record)
    (let [frame-id (:frame record)]
      (cond-> record
        (contains? record :db-before)
        (update :db-before     elide-payload-slot frame-id)

        (contains? record :db-after)
        (update :db-after      elide-payload-slot frame-id)

        (contains? record :trigger-event)
        (update :trigger-event elide-payload-slot frame-id)

        (contains? record :trace-events)
        (update :trace-events  elide-payload-slot frame-id)))))

(defn projected-history
  "Convenience: return the projected vector of records for a frame.
  Equivalent to `(mapv projected-record (epoch-history frame-id))`.
  Tools that egress the whole ring (an MCP `watch-epochs` initial
  snapshot, a recorder dumping the full session) can call this once
  rather than walking the raw ring and re-wrapping each record."
  [frame-id]
  (mapv projected-record (state/history-for frame-id)))
