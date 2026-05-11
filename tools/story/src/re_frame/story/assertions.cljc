(ns re-frame.story.assertions
  "The `:rf.assert/*` assertion vocabulary. Per spec/007 §Assertion
  vocabulary + IMPL-SPEC §3.5 + §5.1 + §2.3.

  ## The seven canonical assertions

  Per spec/007 line 304 the canonical seven are:

  | Event id                       | Payload                        | Semantics |
  |--------------------------------|--------------------------------|-----------|
  | `:rf.assert/path-equals`       | `[path expected]`              | `(= (get-in @app-db path) expected)` |
  | `:rf.assert/path-matches`      | `[path malli-schema]`          | Malli validate at path |
  | `:rf.assert/sub-equals`        | `[sub-vec expected]`           | `(= @(subscribe sub-vec) expected)` |
  | `:rf.assert/dispatched?`       | `[event-or-pred]`              | Was this event dispatched? |
  | `:rf.assert/state-is`          | `[machine-id state]`           | Machine in state? |
  | `:rf.assert/no-warnings`       | `[]`                           | No `:warning` trace events since play start |
  | `:rf.assert/effect-emitted`    | `[fx-id (optional pred)]`      | fx-id emitted during play? |

  ## Record, don't throw (per IMPL-SPEC §2.3)

  Each `:rf.assert/*` event is dispatched through the standard re-frame
  cascade. The handler is a plain `reg-event-fx` that:

  1. Evaluates the assertion semantics against the frame's current app-db
     (or the subscribed value, or the trace bus, or the machine snapshot).
  2. Builds an assertion-record map `{:assertion ... :passed? true|false
     :expected ... :actual ... :dispatch-id ... :source-coord ... :elapsed-ms ...}`.
  3. Appends the record to `[:rf.story/assertions]` in the frame's app-db
     via a `:db` effect — NO throw on failure.

  The play-runner reads `[:rf.story/assertions]` after the play sequence
  completes; `run-variant`'s `:assertions` slot returns the accumulator.

  ## Tag handler — the `:rf.assert/*` namespace

  The seven canonical assertions register at Story boot via
  `install-canonical-assertions!`. Production CLJS builds (with
  `re-frame.story.config/enabled?` false) skip the registrations —
  any unknown assertion at run-time records a `:rf.assert/unknown`
  pseudo-record rather than throwing.

  Per the `downstream EPs consume foundation` rule each assertion is a
  regular re-frame event registered against `re-frame.core/reg-event-fx`
  — Story adds NO new registry kind for assertions. The vocabulary is
  enumerable via `(rf/handlers :event #(re-find #\"^:rf\\.assert/\"
  (str (:id %))))` per the existing registrar query API.

  ## Public surface

  - `install-canonical-assertions!` — register the seven handlers. Boot.
  - `record!` — programmatic record helper (for fx-stub assertions and
    play-runner's exception-projection path).
  - `read-assertions` — return the variant frame's accumulated list.
  - `passing?` — predicate on the result list: true iff every entry has
    `:passed? true` (used by Stage 5's `run-variant` test entry, and by
    consumer tests via the public `assertions-passing?`).
  - `canonical-assertion-ids` — the set of registered event ids."
  (:require [re-frame.core            :as rf]
            [re-frame.interop         :as interop]
            [re-frame.subs            :as subs]
            [re-frame.story.config    :as config]
            [re-frame.story.registrar :as registrar]
            [malli.core               :as malli]))

;; ---------------------------------------------------------------------------
;; Per-frame trace-bus accumulator (warnings + emitted fx)
;;
;; `:rf.assert/no-warnings` needs to know which warning-level trace
;; events fired during the play sequence. `:rf.assert/effect-emitted`
;; needs to know which fx-ids the cascade emitted. We expose a small
;; per-frame side-table keyed by frame-id that the play-runner clears
;; at play start and the assertion handlers consult at evaluation time.
;;
;; This is NOT a new framework registry — it's a Story-internal atom
;; (analogous to `re-frame.story.ui.trace/buffers`) that the runtime
;; populates from the standard trace bus.
;;
;; Both atoms are gated under `config/enabled?` at write time; under
;; production builds they stay empty and the assertion handlers read
;; empties.
;; ---------------------------------------------------------------------------

(defonce
  ^{:doc "frame-id → vector of warning trace events captured since the
         most recent `(reset-warnings! frame-id)` call. The play-runner
         calls reset! at play start."}
  warnings-accumulator
  (atom {}))

(defonce
  ^{:doc "frame-id → set of fx-ids emitted in cascades fanning out from
         the most recent play-start. Populated by the per-frame trace
         listener registered by the play-runner."}
  emitted-fx-accumulator
  (atom {}))

(defonce
  ^{:doc "frame-id → vector of event vectors dispatched during the play
         sequence. Used by `:rf.assert/dispatched?` to verify an event
         was observed."}
  dispatched-events-accumulator
  (atom {}))

(defn reset-trace-accumulators!
  "Clear every per-frame trace-bus accumulator for `frame-id`. The
  play-runner calls this at play start. Production callers (without
  config/enabled?) no-op."
  [frame-id]
  (when config/enabled?
    (swap! warnings-accumulator           assoc frame-id [])
    (swap! emitted-fx-accumulator         assoc frame-id #{})
    (swap! dispatched-events-accumulator  assoc frame-id []))
  nil)

(defn drop-trace-accumulators!
  "Discard every per-frame accumulator entry. Called from frame
  teardown so destroyed variants don't leak memory."
  [frame-id]
  (swap! warnings-accumulator           dissoc frame-id)
  (swap! emitted-fx-accumulator         dissoc frame-id)
  (swap! dispatched-events-accumulator  dissoc frame-id)
  nil)

(defn record-warning!
  "Append a warning trace event to `frame-id`'s accumulator."
  [frame-id ev]
  (when config/enabled?
    (swap! warnings-accumulator update frame-id (fnil conj []) ev))
  nil)

(defn record-emitted-fx!
  "Add `fx-id` to `frame-id`'s emitted-fx accumulator."
  [frame-id fx-id]
  (when config/enabled?
    (swap! emitted-fx-accumulator update frame-id (fnil conj #{}) fx-id))
  nil)

(defn record-dispatched!
  "Append `event-vec` to `frame-id`'s dispatched-events accumulator."
  [frame-id event-vec]
  (when config/enabled?
    (swap! dispatched-events-accumulator update frame-id (fnil conj []) event-vec))
  nil)

(defn frame-warnings  [frame-id] (get @warnings-accumulator frame-id []))
(defn frame-fx        [frame-id] (get @emitted-fx-accumulator frame-id #{}))
(defn frame-dispatched [frame-id] (get @dispatched-events-accumulator frame-id []))

;; ---------------------------------------------------------------------------
;; Programmatic record helper
;;
;; Stage 5's play-runner calls `record!` for each `:rf.assert/*` event
;; it dispatches. The handler returns a record-map; we append it to the
;; variant frame's app-db under `[:rf.story/assertions]` via a
;; registered helper event. This mirrors `runtime/record-error!`.
;; ---------------------------------------------------------------------------

(defn record!
  "Append `record` to `[:rf.story/assertions]` in `frame-id`'s app-db.
  Dispatches synchronously so callers see the updated accumulator on
  the next read. Idempotent w.r.t. frame teardown — swallows the dispatch
  exception if the frame is gone (matches `runtime/record-error!`).

  Returns the record."
  [frame-id record]
  (when config/enabled?
    (try
      (rf/dispatch-sync [::append record] {:frame frame-id})
      (catch #?(:clj Throwable :cljs :default) _ nil)))
  record)

(defn read-assertions
  "Return the assertions vector accumulated against `frame-id`'s
  app-db. Used by `run-variant`'s result-map builder + by the public
  `passing?` predicate."
  [frame-id]
  (or (:rf.story/assertions (rf/get-frame-db frame-id)) []))

(defn passing?
  "Per IMPL-SPEC §3.5 + Phase-2 §5.1 #9: true iff every entry in
  `assertions` has `:passed? true`. An assertions vector with zero
  entries is vacuously passing — this is the spec/007 §Story-as-test
  duality contract: a variant with no `:play` (and therefore no
  assertions) still 'passes', and shows up as green in test reports.

  Accepts either an assertions vector or a `run-variant` result map."
  [assertions-or-result]
  (let [items (cond
                (map? assertions-or-result)    (:assertions assertions-or-result)
                (sequential? assertions-or-result) assertions-or-result
                :else                          [])]
    (every? :passed? items)))

;; ---------------------------------------------------------------------------
;; Assertion-evaluation helpers
;;
;; Each `:rf.assert/*` handler is a thin wrapper that:
;;   1. resolves its inputs from the frame's app-db / trace-bus accumulators
;;   2. computes :passed? / :expected / :actual / :reason
;;   3. dispatch-syncs `::append` to land the record on the frame
;;
;; The handlers receive the dispatch envelope's `:frame` via re-frame
;; convention (`{:db ... :event ...}` in the cofx map). We need the frame
;; to (a) write the assertion record to the right app-db, and (b) look
;; up dispatch correlation IDs. Both are available via the cofx map's
;; `:rf/frame` slot per spec/002 §Dispatch envelope.
;; ---------------------------------------------------------------------------

(defn- source-coord-for-variant
  "Per IMPL-SPEC §9.3 the registered variant body carries a `:source`
  slot stamped by the registrar's macro path. We thread that into the
  assertion record so click-to-source works in the UI shell + agent
  surfaces. Returns nil when the frame is not a registered variant
  (e.g. an ad-hoc frame) or the body has no source coord."
  [frame-id]
  (:source (registrar/handler-meta :variant frame-id)))

(defn- assertion-record
  "Construct the assertion record per IMPL-SPEC §3.5. `extras` is the
  assertion-specific data (`:expected` / `:actual` / `:reason` / ...).

  Includes the variant's source coord (per IMPL-SPEC §9.3) so click-to-
  source in the trace panel jumps to the variant registration site."
  [assertion-id payload passed? extras dispatch-id elapsed-ms frame-id]
  (cond-> {:assertion assertion-id
           :payload   (vec payload)
           :passed?   passed?}
    elapsed-ms  (assoc :elapsed-ms elapsed-ms)
    dispatch-id (assoc :dispatch-id dispatch-id)
    frame-id    (assoc :source-coord (source-coord-for-variant frame-id))
    extras      (merge extras)))

(defn- dispatch-id-from-cofx
  "Walk the cofx map for the current dispatch's id. Re-frame's router
  threads `:dispatch-id` onto the dispatch envelope (per spec/009
  §Dispatch correlation); the standard cofx initial-context surface
  (per spec/002 §Routing) lifts the envelope keys onto cofx directly.

  Falls back to `(get cofx :rf/play-dispatch-id)` (when the play-runner
  stamped it on the event vector for offline contexts) and finally nil."
  [cofx]
  (or (:dispatch-id cofx)
      (:rf/play-dispatch-id cofx)
      nil))

(defn- frame-id-from-cofx
  "Return the frame the current dispatch targets. Per spec/002 §Routing
  the cofx map's `:frame` slot is the canonical source (the router lifts
  the envelope's frame onto cofx as the initial context's `:frame`
  coeffect). The play-runner additionally stamps `:rf/play-frame` for
  play-authored assertions that may run outside a `:frame` binding."
  [cofx]
  (or (:frame cofx)
      (:rf/play-frame cofx)
      nil))

;; ---------------------------------------------------------------------------
;; Event vector match — supports a literal `[:event-id ...]` payload
;; or a predicate fn (`fn? payload`). Used by `:rf.assert/dispatched?`.
;; ---------------------------------------------------------------------------

(defn- event-matches?
  [observed needle]
  (cond
    (fn? needle)             (boolean (needle observed))
    (vector? needle)         (= needle observed)
    (keyword? needle)        (= needle (first observed))
    :else                    false))

;; ---------------------------------------------------------------------------
;; The canonical seven — defined as plain helper fns that produce the
;; assertion record. The `install-canonical-assertions!` boot fn wraps
;; each in a `reg-event-fx` shell that consults the cofx for the frame
;; + dispatch-id and writes the record.
;; ---------------------------------------------------------------------------

(defn- evaluate-path-equals
  [db [path expected]]
  (let [actual (get-in db path)
        passed? (= expected actual)]
    {:passed?  passed?
     :expected expected
     :actual   actual
     :path     path
     :reason   (if passed?
                 "path equals expected"
                 (str "expected " (pr-str expected)
                      " at " (pr-str path)
                      " but got "  (pr-str actual)))}))

(defn- malli-validate
  "Best-effort Malli validation. Returns `[passed? explanation]`. Malli
  is a Story dep (per tools/story/deps.edn) so the require resolves on
  both runtimes; production `:advanced` builds with Story disabled DCE
  the entire assertion vocabulary anyway."
  [schema value]
  (try
    (let [ok? (boolean (malli/validate schema value))
          ex  (when-not ok?
                (try (malli/explain schema value)
                     (catch #?(:clj Throwable :cljs :default) _ nil)))]
      [ok? (when ex (pr-str ex))])
    (catch #?(:clj Throwable :cljs :default) e
      [false (str "malli validation threw: "
                  #?(:clj (.getMessage ^Throwable e)
                     :cljs (str e)))])))

(defn- evaluate-path-matches
  [db [path schema]]
  (let [actual              (get-in db path)
        [passed? explanation] (malli-validate schema actual)]
    (cond-> {:passed?  passed?
             :path     path
             :expected schema
             :actual   actual
             :reason   (if passed?
                         "value at path validates against schema"
                         (str "value at " (pr-str path) " failed schema "
                              (pr-str schema)))}
      explanation (assoc :explanation explanation))))

(defn- evaluate-sub-equals
  [_frame-id db [sub-vec expected]]
  ;; Use compute-sub against the snapshot — bypasses the reactive cache
  ;; per Spec 008. Subscriptions registered against the variant's frame
  ;; resolve the same way they would in the running app.
  (let [actual  (try
                  (subs/compute-sub sub-vec db)
                  (catch #?(:clj Throwable :cljs :default) _
                    ::compute-error))
        passed? (and (not= actual ::compute-error)
                     (= actual expected))]
    {:passed?  passed?
     :expected expected
     :actual   (if (= actual ::compute-error)
                 :rf.assert/sub-threw
                 actual)
     :sub-vec  sub-vec
     :reason   (cond
                 (= actual ::compute-error) "subscription threw during evaluation"
                 passed? "subscription returned expected value"
                 :else (str "expected " (pr-str expected)
                            " from " (pr-str sub-vec)
                            " but got " (pr-str actual)))}))

(defn- evaluate-dispatched?
  [frame-id [needle]]
  (let [observed (frame-dispatched frame-id)
        matched  (some #(event-matches? % needle) observed)
        passed?  (boolean matched)]
    {:passed?  passed?
     :expected needle
     :actual   observed
     :reason   (if passed?
                 "matching event was dispatched during play"
                 (str "no dispatched event matched "
                      (pr-str needle)))}))

(defn- evaluate-state-is
  [db [machine-id state]]
  (let [snap   (get-in db [:rf/machines machine-id])
        actual (:state snap)
        passed? (= state actual)]
    {:passed?    passed?
     :expected   state
     :actual     actual
     :machine-id machine-id
     :reason     (cond
                   (nil? snap)
                   (str "machine " (pr-str machine-id)
                        " has no snapshot in this frame")
                   passed?
                   "machine is in the expected state"
                   :else
                   (str "expected " (pr-str state)
                        " for machine " (pr-str machine-id)
                        " but state is " (pr-str actual)))}))

(defn- evaluate-no-warnings
  [frame-id _payload]
  (let [warnings (frame-warnings frame-id)
        passed?  (empty? warnings)]
    {:passed?  passed?
     :expected :no-warnings
     :actual   (mapv :operation warnings)
     :count    (count warnings)
     :reason   (if passed?
                 "no warning-level trace events captured during play"
                 (str (count warnings)
                      " warning trace event(s) captured during play"))}))

(defn- evaluate-effect-emitted
  [frame-id [fx-id pred]]
  (let [emitted   (frame-fx frame-id)
        present?  (contains? emitted fx-id)
        passed?   (boolean (and present? (or (nil? pred)
                                             (try (pred fx-id) (catch #?(:clj Throwable :cljs :default) _ false)))))]
    {:passed?  passed?
     :expected fx-id
     :actual   emitted
     :reason   (cond
                 (not present?)
                 (str "fx " (pr-str fx-id) " was not emitted during play")
                 (and pred (not passed?))
                 (str "fx " (pr-str fx-id) " was emitted but predicate rejected it")
                 :else
                 (str "fx " (pr-str fx-id) " was emitted during play"))}))

;; ---------------------------------------------------------------------------
;; Registered event ids
;; ---------------------------------------------------------------------------

(def ^:const id-path-equals     :rf.assert/path-equals)
(def ^:const id-path-matches    :rf.assert/path-matches)
(def ^:const id-sub-equals      :rf.assert/sub-equals)
(def ^:const id-dispatched      :rf.assert/dispatched?)
(def ^:const id-state-is        :rf.assert/state-is)
(def ^:const id-no-warnings     :rf.assert/no-warnings)
(def ^:const id-effect-emitted  :rf.assert/effect-emitted)

(def canonical-assertion-ids
  "Per spec/007 line 304 — the canonical seven assertion event ids,
  registered at Story boot."
  #{id-path-equals
    id-path-matches
    id-sub-equals
    id-dispatched
    id-state-is
    id-no-warnings
    id-effect-emitted})

;; ---------------------------------------------------------------------------
;; Boot — register the seven canonical handlers
;; ---------------------------------------------------------------------------

(defn- handler-for-evaluator
  "Build the `reg-event-fx` handler body for `assertion-id` whose
  evaluator returns the record extras given `(db, payload)` (or
  `(frame-id, payload)` for trace-bus-driven assertions).

  The handler reads `:db` directly from cofx — which the router has
  populated with the variant frame's app-db (spec/002 §Routing initial
  context) — and returns `{:db (update db :rf.story/assertions conj record)}`.
  This is the record-don't-throw contract per IMPL-SPEC §2.3: the
  assertion's failure mode is a `:db` write, not an exception."
  [assertion-id evaluator-kind]
  (fn [{:keys [db] :as cofx} event-vec]
    (let [start-ms     (interop/now-ms)
          payload      (vec (rest event-vec))
          frame-id     (frame-id-from-cofx cofx)
          dispatch-id  (dispatch-id-from-cofx cofx)
          extras       (case evaluator-kind
                         :path-equals     (evaluate-path-equals     db payload)
                         :path-matches    (evaluate-path-matches    db payload)
                         :sub-equals      (evaluate-sub-equals      frame-id db payload)
                         :dispatched?     (evaluate-dispatched?     frame-id payload)
                         :state-is        (evaluate-state-is        db payload)
                         :no-warnings     (evaluate-no-warnings     frame-id payload)
                         :effect-emitted  (evaluate-effect-emitted  frame-id payload))
          elapsed-ms   (- (interop/now-ms) start-ms)
          record       (assertion-record assertion-id payload
                                         (:passed? extras)
                                         (dissoc extras :passed?)
                                         dispatch-id elapsed-ms
                                         frame-id)]
      ;; Append the record to [:rf.story/assertions] directly on the
      ;; current frame's app-db. The router has populated :db with the
      ;; variant frame's snapshot per spec/002 §Routing.
      {:db (update db :rf.story/assertions (fnil conj []) record)})))

(defn install-canonical-assertions!
  "Per IMPL-SPEC §3.5 + spec/007 line 304 — register the seven canonical
  `:rf.assert/*` event handlers. Idempotent.

  Each handler:
  1. Reads the current frame's app-db via the cofx `:db` slot (per
     spec/002 §Routing the router populates `:db` with the dispatch-
     targeted frame's snapshot).
  2. Computes the assertion result against `:db` (or the per-frame
     trace-bus accumulators for `:rf.assert/no-warnings` /
     `:rf.assert/effect-emitted` / `:rf.assert/dispatched?`).
  3. Returns `{:db (update db :rf.story/assertions conj record)}` —
     writes the record onto the frame's app-db.

  Also registers `::append` — the internal event that the
  programmatic `record!` helper dispatches to land a record on the
  frame from outside an event-handler context (e.g. the play-runner's
  post-drain exception walker).

  No throw on failure — the play sequence runs to completion per
  IMPL-SPEC §2.3."
  []
  (when config/enabled?
    ;; Internal event-db handler used by `record!`. Appends a record to
    ;; the variant frame's [:rf.story/assertions] slot.
    (rf/reg-event-db
      ::append
      (fn [db [_ record]]
        (update db :rf.story/assertions (fnil conj []) record)))
    ;; The seven canonical handlers.
    (rf/reg-event-fx id-path-equals     (handler-for-evaluator id-path-equals     :path-equals))
    (rf/reg-event-fx id-path-matches    (handler-for-evaluator id-path-matches    :path-matches))
    (rf/reg-event-fx id-sub-equals      (handler-for-evaluator id-sub-equals      :sub-equals))
    (rf/reg-event-fx id-dispatched      (handler-for-evaluator id-dispatched      :dispatched?))
    (rf/reg-event-fx id-state-is        (handler-for-evaluator id-state-is        :state-is))
    (rf/reg-event-fx id-no-warnings     (handler-for-evaluator id-no-warnings     :no-warnings))
    (rf/reg-event-fx id-effect-emitted  (handler-for-evaluator id-effect-emitted  :effect-emitted))
    nil))

;; ---------------------------------------------------------------------------
;; Assertion-event detection (used by the play-runner)
;; ---------------------------------------------------------------------------

(defn assertion-event?
  "True iff `event` is a `:rf.assert/*` form. Used by the play-runner
  to distinguish 'real' dispatches from assertions so the dispatched-
  events accumulator can skip recording assertion events themselves."
  [event]
  (let [id (when (sequential? event) (first event))]
    (and (keyword? id)
         (= "rf.assert" (namespace id)))))
