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
  enumerable via `(rf/registrations :event #(re-find #\"^:rf\\.assert/\"
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
  (:require [clojure.string              :as str]
            [re-frame.core               :as rf]
            [re-frame.elision            :as elision]
            [re-frame.interop            :as interop]
            [re-frame.subs               :as subs]
            [re-frame.story.config       :as config]
            [re-frame.story.predicates   :as pred]
            [re-frame.story.registrar    :as registrar]
            [re-frame.story.requirements :as requirements]
            [malli.core                  :as malli]))

;; ---------------------------------------------------------------------------
;; Per-frame trace-bus accumulators (warnings + emitted fx + dispatched)
;;
;; `:rf.assert/no-warnings` needs to know which warning-level trace
;; events fired during the play sequence. `:rf.assert/effect-emitted`
;; needs to know which fx-ids the cascade emitted. `:rf.assert/dispatched?`
;; needs to know which event vectors fired. We expose one per-frame
;; side-table keyed by frame-id that the play-runner clears at play
;; start and the assertion handlers consult at evaluation time.
;;
;; This is NOT a new framework registry — it's a Story-internal atom
;; (analogous to `re-frame.story.ui.trace-buffer/buffers`) that the
;; runtime populates from the standard trace bus.
;;
;; Shape:
;;   {frame-id {:warnings    [trace-event ...]
;;              :emitted-fx  #{fx-id ...}
;;              :dispatched  [event-vec ...]}}
;;
;; Writes are gated under `config/enabled?`; production builds leave
;; the atom empty and the assertion handlers read empty defaults.
;; ---------------------------------------------------------------------------

(def ^:private empty-frame-accumulators
  {:warnings   []
   :emitted-fx #{}
   :dispatched []})

(defonce
  ^{:doc "frame-id → {:warnings [...] :emitted-fx #{...} :dispatched [...]}.
         The play-runner calls `reset-trace-accumulators!` at play start
         and `drop-trace-accumulators!` at frame teardown."}
  trace-accumulators
  (atom {}))

(defn reset-trace-accumulators!
  "Clear every per-frame trace-bus accumulator for `frame-id`. The
  play-runner calls this at play start. Production callers (without
  config/enabled?) no-op."
  [frame-id]
  (when config/enabled?
    (swap! trace-accumulators assoc frame-id empty-frame-accumulators))
  nil)

(defn drop-trace-accumulators!
  "Discard the per-frame accumulator entry. Called from frame teardown
  so destroyed variants don't leak memory."
  [frame-id]
  (swap! trace-accumulators dissoc frame-id)
  nil)

(defn record-warning!
  "Append a warning trace event to `frame-id`'s accumulator."
  [frame-id ev]
  (when config/enabled?
    (swap! trace-accumulators update-in [frame-id :warnings] (fnil conj []) ev))
  nil)

(defn record-emitted-fx!
  "Add `fx-id` to `frame-id`'s emitted-fx accumulator."
  [frame-id fx-id]
  (when config/enabled?
    (swap! trace-accumulators update-in [frame-id :emitted-fx] (fnil conj #{}) fx-id))
  nil)

(defn record-dispatched!
  "Append `event-vec` to `frame-id`'s dispatched-events accumulator."
  [frame-id event-vec]
  (when config/enabled?
    (swap! trace-accumulators update-in [frame-id :dispatched] (fnil conj []) event-vec))
  nil)

(defn frame-warnings   [frame-id] (get-in @trace-accumulators [frame-id :warnings]   []))
(defn frame-fx         [frame-id] (get-in @trace-accumulators [frame-id :emitted-fx] #{}))
(defn frame-dispatched [frame-id] (get-in @trace-accumulators [frame-id :dispatched] []))

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
  duality contract: a variant with no `:play-script` (and therefore no
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
;; Redaction projection (spec/004 §Privacy, rf2-shy6n / rf2-ee38b.3)
;;
;; A value captured from a path / sub the frame marked sensitive (via
;; `re-frame.core/add-marks` / `set-marks`, or a schema entry with
;; `{:sensitive? true}`) MUST NOT land raw on the assertion record's
;; `:actual` slot — the record serialises into observation surfaces (the
;; test-mode pane, MCP `read-assertions`, JSON-log egress) per spec/015
;; §Data-Classification. We project the captured value through
;; `re-frame.elision/elide-wire-value` (the frame-aware wire-egress
;; projection) so a sensitive path records `:rf/redacted`, not the secret.
;; A path with no sensitive declaration passes through unchanged.
;; ---------------------------------------------------------------------------

(defn- redact-at
  "Project `v` through `elision/elide-wire-value` as if it lives at
  `path` in `frame-id`'s app-db, so sensitive sub-paths (or a sensitive
  root path) substitute `:rf/redacted`. Tolerant — any elision error or
  a nil frame-id returns `v` unchanged (record-don't-throw: redaction
  failure must never break the assertion)."
  [frame-id path v]
  (try
    (elision/elide-wire-value v (cond-> {:path (vec path)}
                                  frame-id (assoc :frame frame-id)))
    (catch #?(:clj Throwable :cljs :default) _ v)))

;; ---------------------------------------------------------------------------
;; The canonical seven — defined as plain helper fns that produce the
;; assertion record. The `install-canonical-assertions!` boot fn wraps
;; each in a `reg-event-fx` shell that consults the cofx for the frame
;; + dispatch-id and writes the record.
;; ---------------------------------------------------------------------------

(defn- evaluate-path-equals
  [frame-id db [path expected]]
  (let [raw     (get-in db path)
        passed? (= expected raw)
        actual  (redact-at frame-id path raw)]
    {:passed?  passed?
     :expected expected
     :actual   actual
     :path     path
     :reason   (if passed?
                 "path equals expected"
                 (str "expected " (pr-str expected)
                      " at " (pr-str path)
                      " but got "  (pr-str actual)))}))

(defn- resolve-sym-pred
  "Resolve a predicate symbol to a callable fn. JVM uses
  `requiring-resolve`; CLJS resolves via a best-effort `goog.global` walk
  on munged dotted names (fragile under advanced compilation — the fn-
  direct authoring path is advanced-safe). Returns nil on miss. Mirrors
  `re-frame.story.play.runner-events/resolve-predicate`; kept here so the
  `[:fn sym]` schema fold (rf2-5x1wt.18 `:assert-db :pred` form) resolves
  at validation time without a require into the impure runner-events ns."
  [sym]
  (when sym
    (try
      #?(:clj
         (when-let [v (requiring-resolve (symbol sym))]
           (when (var? v) @v))
         :cljs
         (let [ns-part (namespace sym) name-part (name sym)]
           (when (and ns-part name-part)
             (let [parts (-> (str ns-part "." name-part)
                             (str/replace #"-" "_")
                             (str/split #"\."))]
               (loop [obj js/window ks parts]
                 (cond
                   (nil? obj)  nil
                   (empty? ks) (when (fn? obj) obj)
                   :else       (recur (aget obj (first ks)) (rest ks))))))))
      (catch #?(:clj Throwable :cljs :default) _ nil))))

(defn- resolve-fn-schema
  "Rewrite a `[:fn sym]` schema (the rf2-5x1wt.18 `:assert-db :pred` fold's
  symbol form) into `[:fn resolved-fn]` so Malli can validate it without
  sci (`[:fn 'sym]` needs sci — unavailable). A `[:fn fn]` (the fn-direct
  fold) and every non-`:fn` schema pass through unchanged. Pure-ish — the
  symbol resolution is the only runtime read. Returns the (possibly
  resolved) schema, or `::unresolved-pred` when the symbol could not be
  resolved (so `malli-validate` can report a useful failure rather than
  letting Malli throw an opaque sci error)."
  [schema]
  (if (and (vector? schema) (= :fn (first schema)) (symbol? (second schema)))
    (if-let [f (resolve-sym-pred (second schema))]
      [:fn f]
      ::unresolved-pred)
    schema))

(defn- malli-validate
  "Best-effort Malli validation. Returns `[passed? explanation]`. Malli
  is a Story dep (per tools/story/deps.edn) so the require resolves on
  both runtimes; production `:advanced` builds with Story disabled DCE
  the entire assertion vocabulary anyway.

  rf2-5x1wt.19 — a `[:fn sym]` schema (the `:assert-db :pred` symbol fold,
  §B5.9) is resolved to `[:fn resolved-fn]` first (`resolve-fn-schema`),
  because Malli's `[:fn 'sym]` form needs sci (unavailable). An
  unresolvable symbol reports a readable failure rather than an opaque
  sci error."
  [schema value]
  (let [schema (resolve-fn-schema schema)]
    (if (= ::unresolved-pred schema)
      [false (str "could not resolve :pred symbol in schema "
                  "(symbol resolution is fragile under advanced CLJS; "
                  "pass the predicate as a fn directly to avoid this)")]
      (try
        (let [ok? (boolean (malli/validate schema value))
              ex  (when-not ok?
                    (try (malli/explain schema value)
                         (catch #?(:clj Throwable :cljs :default) _ nil)))]
          [ok? (when ex (pr-str ex))])
        (catch #?(:clj Throwable :cljs :default) e
          [false (str "malli validation threw: "
                      #?(:clj (.getMessage ^Throwable e)
                         :cljs (str e)))])))))

(defn- evaluate-path-matches
  [frame-id db [path schema]]
  (let [raw                 (get-in db path)
        [passed? explanation] (malli-validate schema raw)
        actual              (redact-at frame-id path raw)]
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
  [frame-id db [sub-vec expected]]
  ;; Use compute-sub against the snapshot — bypasses the reactive cache
  ;; per Spec 008. Subscriptions registered against the variant's frame
  ;; resolve the same way they would in the running app.
  ;;
  ;; Redaction: a sub reading a sensitive path propagates the sensitive
  ;; marker into its output value (spec/015 §reg-sub). We project the
  ;; sub's value through `elide-wire-value` keyed on the SUB's root path
  ;; (`(rest sub-vec)` is the args; the first element is the sub-id, not
  ;; an app-db path). Where the sub-vec carries an app-db path (the
  ;; common `[:sub/id & path]` shape) the projection redacts; otherwise
  ;; the value passes through unchanged.
  (let [raw     (try
                  (subs/compute-sub sub-vec db)
                  (catch #?(:clj Throwable :cljs :default) _
                    ::compute-error))
        passed? (and (not= raw ::compute-error)
                     (= raw expected))
        actual  (cond
                  (= raw ::compute-error) :rf.assert/sub-threw
                  :else                   (redact-at frame-id (vec (rest sub-vec)) raw))]
    {:passed?  passed?
     :expected expected
     :actual   actual
     :sub-vec  sub-vec
     :reason   (cond
                 (= raw ::compute-error) "subscription threw during evaluation"
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
  (let [snap   (get-in db [:rf/runtime :machines :snapshots machine-id])
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

;; ---------------------------------------------------------------------------
;; Schema-error — the EXPECTED schema violation (rf2-5x1wt.21, NET-NEW)
;;
;; `:rf.assert/schema-error` declares that the run is EXPECTED to emit one
;; schema validation failure on a named surface. Unlike the seven app-db /
;; trace-bus assertions it is NOT a `reg-event-fx` handler dispatched into
;; the frame — it carries no app-db semantics. It is a TAPE-evaluated
;; expectation: the runner pairs each declared `:rf.assert/schema-error`
;; against the projected `:rf.error/schema-validation-failure` evidence
;; (`re-frame.story.play.evidence/schema-violations`) by an EXACT
;; multiset-consumption match (spec/017 §Schema rule), so the verdict +
;; assertion record are minted in the result boundary (`result.cljc`), not
;; here. There is deliberately NO `:rf.assert/no-schema-errors` — a
;; schema-clean run is the knob-free runner FLOOR (the agreement floor in
;; `evidence/tape-shows-failure?`), refined by these expectations rather
;; than an opt-in.
;; ---------------------------------------------------------------------------

(def ^:const id-schema-error    :rf.assert/schema-error)

(def canonical-assertion-ids
  "The canonical assertion event ids the P1 vocabulary recognises. The
  SHIPPING seven (spec/007 line 304) the `.18` fold preserves, PLUS the
  NET-NEW `:rf.assert/schema-error` (rf2-5x1wt.21, spec/017 §Schema rule —
  the EXPECTED-schema-violation declaration). `:rf.assert/schema-error` is
  recognised (so plan construction accepts it) but is NOT installed as a
  `reg-event-fx` handler: it is tape-evaluated in the result boundary, not
  dispatched into the frame (see the section comment above)."
  #{id-path-equals
    id-path-matches
    id-sub-equals
    id-dispatched
    id-state-is
    id-no-warnings
    id-effect-emitted
    id-schema-error})

;; ---------------------------------------------------------------------------
;; DOM assertion family — the fold target for the shipping `:assert-dom`
;; step (rf2-5x1wt.18, spec/017 §Assertions — one atom, two positions).
;;
;; These ids are NET-NEW (the shipping vocabulary had only the seven above
;; plus the ad-hoc synthetic `:rf.assert/dom` record `:assert-dom` minted).
;; The DOM runner that EVALUATES them lands later (the `:dom` capability is
;; wired now via `re-frame.story.requirements`); the fold names them so an
;; `:assert-dom` step lowers onto the SAME assertion atom shape as every
;; other assertion, regardless of script position. A headless run that
;; reaches one refuses with `:cannot-run` (the `:dom` capability gate),
;; never a silent pass — that is the fail-closed contract, not this fold's
;; concern.
;; ---------------------------------------------------------------------------

(def ^:const id-dom-visible     :rf.assert/dom-visible)
(def ^:const id-dom-hidden      :rf.assert/dom-hidden)
(def ^:const id-dom-text        :rf.assert/dom-text)

(def dom-assertion-ids
  "The DOM assertion family (rf2-5x1wt.18, NET-NEW). The shipping
  `:assert-dom selector :visible|:hidden|:text` step folds onto these so
  a DOM expectation rides the ONE assertion atom. Each carries the `:dom`
  runner requirement via `re-frame.story.requirements/assertion-capabilities`
  (spec/017 §Runner requirements); the DOM runner that proves them lands
  later."
  #{id-dom-visible
    id-dom-hidden
    id-dom-text})

;; ---------------------------------------------------------------------------
;; Browser-tier assertion family — visual snapshot + axe-style a11y +
;; structural a11y (rf2-5x1wt.28, NET-NEW; spec/017 §Visual, a11y, and
;; browser checks + §Canonical P1 assertions).
;;
;; These are NOT a separate visual-testing system — they are richer
;; runner-tiered assertions on the SAME variant/result model. They ride the
;; ONE assertion-record shape every other assertion produces; the executor
;; (`re-frame.story.play.browser`) projects findings into that record. Each
;; carries its capability requirement via the requirement registry
;; (`re-frame.story.requirements/assertion-capabilities`):
;;
;;   :rf.assert/visual-snapshot  → :pixels       (real browser; headless → :cannot-run)
;;   :rf.assert/a11y             → :a11y-engine   (axe-style; headless → :cannot-run)
;;   :rf.assert/a11y-structural  → :hiccup-structure (hiccup tree; runs at :hiccup)
;;
;; The visual / axe-a11y runners are browser-tier and refuse with
;; `:cannot-run` under a headless runner (the fail-closed contract — never
;; a silent pass — is the `re-frame.story.requirements` capability gate's
;; concern, not this id declaration's). The structural-a11y check is pure
;; over the rendered hiccup tree, so the `:hiccup` runner proves it.
;; ---------------------------------------------------------------------------

(def ^:const id-visual-snapshot  :rf.assert/visual-snapshot)
(def ^:const id-a11y             :rf.assert/a11y)
(def ^:const id-a11y-structural  :rf.assert/a11y-structural)

(def browser-assertion-ids
  "The browser-tier oracle assertion family (rf2-5x1wt.28). Visual snapshot
  and axe-style a11y are browser-only (`:pixels` / `:a11y-engine`); the
  structural a11y check rides the `:hiccup` tier. All three ride the ONE
  assertion atom + the ONE assertion-record shape — they are runner-tiered
  assertions, NOT a separate visual-testing system (spec/017 §Visual, a11y,
  and browser checks)."
  #{id-visual-snapshot
    id-a11y
    id-a11y-structural})

(def known-assertion-ids
  "Every assertion id the P1 vocabulary recognises — the shipping seven,
  the folded DOM family, the browser-tier oracle family (visual / a11y /
  structural-a11y), and the wider set declared in the requirement registry
  (`re-frame.story.requirements/assertion-capabilities`: the schema /
  visual / a11y / reactive-count ids whose runners land later or are
  browser-tiered). Plan construction validates authored assertion atoms
  against this set (`assertion-id-known?`); an unknown id FAILS plan
  construction with a useful error (rf2-5x1wt.18, spec/017 §Assertions).
  Reading the requirement registry keeps this list a derived view of the
  ONE id source of truth rather than a hand-maintained parallel set."
  (into (into (into canonical-assertion-ids dom-assertion-ids)
              browser-assertion-ids)
        (keys requirements/assertion-capabilities)))

(defn assertion-id-known?
  "True iff `id` is a recognised P1 assertion id (`known-assertion-ids`).
  Pure data → data. Used by the plan compiler to FAIL plan construction
  on an unknown assertion atom rather than letting it record a vacuous
  `:rf.assert/unknown` pseudo-record at run time (rf2-5x1wt.18)."
  [id]
  (contains? known-assertion-ids id))

;; ---------------------------------------------------------------------------
;; Assertion-atom fold (rf2-5x1wt.18, spec/017 §Assertions — one atom,
;; two positions)
;;
;; The assertion atom is the data vector `[:rf.assert/id & args]`. It is
;; legal in exactly two positions: terminal `:assertions` and the in-script
;; `[:assert …]` checkpoint. Both positions produce ONE assertion-record
;; shape (the `[:assert …]` checkpoint dispatches the wrapped atom, whose
;; reg-event-fx handler records the canonical record — rf2-5x1wt.17).
;;
;; The shipping ergonomic script steps `:assert-db` / `:assert-dom` are NOT
;; authoring-distinct assertion kinds — they are sugar that FOLDS onto the
;; one atom:
;;
;;   [:assert-db path expected]        → [:rf.assert/path-equals path expected]
;;   [:assert-db path :pred fn-or-sym] → [:rf.assert/path-matches path [:fn …]]
;;   [:assert-dom sel :visible]        → [:rf.assert/dom-visible sel]
;;   [:assert-dom sel :hidden]         → [:rf.assert/dom-hidden sel]
;;   [:assert-dom sel :text txt]       → [:rf.assert/dom-text sel txt]
;;
;; `fold-assert-step` returns the canonical `[:assert assertion-atom]`
;; checkpoint for a shipping `:assert-db` / `:assert-dom` step, so the plan
;; compiler can rewrite a script uniformly to the ONE atom in its
;; checkpoint position — collapsing the two parallel record-minting paths
;; (`runner-events`' synthetic `:rf.assert/db` / `:rf.assert/dom` records)
;; onto the canonical assertion handlers. Pure data → data.
;; ---------------------------------------------------------------------------

(defn- assert-db->atom
  "Fold a shipping `[:assert-db …]` step into its canonical assertion atom.
  The equality form folds to `:rf.assert/path-equals`; the predicate form
  folds to `:rf.assert/path-matches` wrapping the predicate in a Malli
  `[:fn …]` schema (the one canonical way to express an arbitrary
  predicate against a path). A symbol predicate is preserved verbatim in
  the `[:fn …]` schema — the assertion handler's Malli path resolves it
  the same way the shipping `:assert-db :pred` rail did."
  [step]
  (let [path (nth step 1)]
    (if (and (= 4 (count step)) (= :pred (nth step 2)))
      [id-path-matches path [:fn (nth step 3)]]
      [id-path-equals path (nth step 2)])))

(defn- assert-dom->atom
  "Fold a shipping `[:assert-dom …]` step into its canonical DOM-family
  assertion atom (`:rf.assert/dom-visible` / `:rf.assert/dom-hidden` /
  `:rf.assert/dom-text`). Carries the selector (and text) as the atom's
  payload; the `:dom` runner requirement rides the folded id via the
  requirement registry."
  [step]
  (let [selector (nth step 1)
        mode     (nth step 2)]
    (case mode
      :visible [id-dom-visible selector]
      :hidden  [id-dom-hidden  selector]
      :text    [id-dom-text    selector (nth step 3)])))

(defn fold-assert-step
  "Fold a shipping ergonomic assertion step (`[:assert-db …]` /
  `[:assert-dom …]`) into the canonical `[:assert assertion-atom]`
  checkpoint, so terminal `:assertions` and EVERY in-script assertion
  position resolve to the ONE assertion atom (rf2-5x1wt.18). Returns the
  rewritten `[:assert …]` step for a foldable step, or the step unchanged
  for any other step (`:dispatch`, `:wait`, an already-`[:assert …]`
  checkpoint, a bare event vector). Pure data → data — used by the plan
  compiler's script normalization."
  [step]
  (case (and (vector? step) (pos? (count step)) (first step))
    :assert-db  [:assert (assert-db->atom step)]
    :assert-dom [:assert (assert-dom->atom step)]
    step))

(defn fold-script
  "Fold every shipping `:assert-db` / `:assert-dom` step in a coerced
  `script` vector onto the canonical `[:assert assertion-atom]` checkpoint
  (rf2-5x1wt.18). Leaves non-assertion steps and already-canonical
  `[:assert …]` checkpoints untouched. Pure data → data."
  [script]
  (mapv fold-assert-step (or script [])))

(defn assertion-atom-id
  "The `:rf.assert/*` id at the head of an assertion atom, or nil for a
  non-atom. An assertion atom is `[:rf.assert/id & args]`; this is the id
  every fold target + authored atom is validated against (`assertion-id-
  known?`). Pure data → data."
  [assertion-atom]
  (when (and (vector? assertion-atom) (pos? (count assertion-atom)))
    (let [h (first assertion-atom)]
      (when (keyword? h) h))))

;; ---------------------------------------------------------------------------
;; Schema-error expectation — parse the declared atom into its surface
;; selector (rf2-5x1wt.21, spec/017 §Schema rule)
;;
;; The declared atom is `[:rf.assert/schema-error {:where <surface> …}]`.
;; The spec map's `:where` chooses the surface; the surface-specific keys
;; key the EXPECTATION's selector, which the result boundary pairs against
;; a projected violation's `:selector` (`evidence/violation-selector`) by an
;; exact multiset match. The two selector builders MUST agree key-for-key —
;; `evidence/violation-selector` keys a PROJECTED VIOLATION (read from the
;; trace `:tags`), this keys a DECLARED EXPECTATION (read from the author's
;; spec map) — so the same surface produces the same vector on both sides:
;;
;;     {:where :event :event id}                         → [:event id]
;;     {:where :event :event id :path p}                 → [:event id p]
;;     {:where :cofx :cofx id}                           → [:cofx id]
;;     {:where :fx-args :fx-args id}                     → [:fx-args id]
;;     {:where :sub-return :sub-return id :query-v qv}   → [:sub-return id qv]
;;     {:where :app-db :registered-path rp :path p}      → [:app-db rp p]
;;     {:where :machine-data :machine-id m :phase phase} → [:machine-data m phase]
;;
;; A `:where` the matcher does not special-case keys by `[:where failing]`
;; where `failing` is the surface-named id slot (mirroring
;; `evidence/violation-selector`'s open-surface fallback), so a novel
;; surface still pairs by a stable, distinct selector.

(defn schema-error?
  "True iff `assertion-atom` is a `[:rf.assert/schema-error …]` declaration.
  Pure data → data."
  [assertion-atom]
  (= id-schema-error (assertion-atom-id assertion-atom)))

(defn schema-error-spec
  "The expectation spec map of a `[:rf.assert/schema-error spec]` atom (the
  second element), or `{}` when the atom carries no spec (a bare
  `[:rf.assert/schema-error]` expects ANY one violation — keyed `[:any]`).
  Pure data → data."
  [assertion-atom]
  (let [s (nth (vec assertion-atom) 1 nil)]
    (if (map? s) s {})))

(defn schema-error-selector
  "The surface SELECTOR a declared `:rf.assert/schema-error` expectation
  pairs on — mirroring `evidence/violation-selector` so a declared
  expectation and a projected violation produce the SAME vector for the same
  surface (rf2-5x1wt.21, spec/017 §Schema rule). Pure data → data.

  `spec` is the expectation map (`schema-error-spec`). An empty spec (a bare
  `[:rf.assert/schema-error]`) selects `[:any]` — the wildcard that consumes
  any one violation regardless of surface. An unrecognised `:where` keys by
  `[:where failing-id]` (the open-surface fallback)."
  [spec]
  (let [{:keys [where event cofx fx-args sub-return query-v
                registered-path path machine-id phase failing-id]} spec]
    (cond
      (empty? spec)        [:any]
      (nil? where)         [:any]
      (= :event where)     (cond-> [:event event]
                             (some? path) (conj path))
      (= :cofx where)      [:cofx cofx]
      (= :fx-args where)   [:fx-args fx-args]
      (= :sub-return where) [:sub-return sub-return query-v]
      (= :app-db where)    [:app-db registered-path path]
      (= :machine-data where) [:machine-data machine-id phase]
      :else                [where failing-id])))

(defn schema-error-expectation
  "Project a declared `[:rf.assert/schema-error spec]` atom into its
  expectation record `{:atom atom :spec spec :selector selector}` — the
  shape the result boundary's exact-consumption matcher pairs against the
  projected violations (rf2-5x1wt.21). Pure data → data."
  [assertion-atom]
  (let [spec (schema-error-spec assertion-atom)]
    {:atom     assertion-atom
     :spec     spec
     :selector (schema-error-selector spec)}))

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
                         :path-equals     (evaluate-path-equals     frame-id db payload)
                         :path-matches    (evaluate-path-matches    frame-id db payload)
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

(def assertion-event?
  "True iff `event` is a `:rf.assert/*` form. Used by the play-runner
  to distinguish 'real' dispatches from assertions so the dispatched-
  events accumulator can skip recording assertion events themselves.

  Aliased from `re-frame.story.predicates` (the canonical leaf ns)."
  pred/assertion-event?)
