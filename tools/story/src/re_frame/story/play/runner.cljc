(ns re-frame.story.play.runner
  "Pure step executor for Story's `:play-script` slot — the Storybook
  `play()`-equivalent rich DSL (rf2-8i2a9).

  ## What this module does

  Storybook's `play()` function runs after the story mounts and
  simulates user interactions to self-verify behaviour. The re-frame2
  equivalent is declarative — the `:play-script` slot on a variant
  body carries a vector of TAGGED steps:

      :play-script {:script [[:dispatch [:counter/inc]]
                             [:wait 100]
                             [:dispatch-sync [:counter/dec]]
                             [:assert-db [:n] 0]
                             [:assert-dom \"[data-test=foo]\" :visible]
                             [:click \"[data-test=button]\"]
                             [:type  \"[data-test=input]\" \"hello\"]]
                    :auto-run? true     ; default true
                    :name      \"happy path\"}

  A bare vector is also accepted: `:play-script [[:dispatch [...]] ...]`
  — equivalent to `{:script <vector>}` with `:auto-run? true`.

  ## Step types

  | Step                               | Semantics                                                     |
  |------------------------------------|---------------------------------------------------------------|
  | `[:dispatch event-vec]`            | `rf/dispatch` (async) into the frame                          |
  | `[:dispatch-sync event-vec]`       | `rf/dispatch-sync` (synchronous) into the frame               |
  | `[:wait ms]`                       | Sleep N ms (`setTimeout` CLJS / `Thread/sleep` JVM)            |
  | `[:assert-db path value]`          | Assert `(= (get-in @app-db path) value)`                      |
  | `[:assert-db path :pred fn-or-sym]`| Assert custom predicate — `fn` is preferred (works under advanced CLJS); `symbol` is the JVM/dev escape hatch (resolved at run time, fragile under advanced CLJS munging) |
  | `[:assert-dom selector :visible]`  | Assert selector resolves to a visible DOM node                |
  | `[:assert-dom selector :hidden]`   | Assert selector resolves to nothing (or hidden node)          |
  | `[:assert-dom selector :text txt]` | Assert selector's text-content matches `txt`                  |
  | `[:click selector]`                | Synthetic click event at selector                             |
  | `[:type selector text]`            | Synthetic `input` event at selector with `text`               |

  Steps run sequentially. A failed `:assert-*` step is RECORDED — the
  run continues so the user sees all failures, not just the first
  (per IMPL-SPEC §2.3 'record, don't throw').

  ## Pure / impure split

  This namespace is `.cljc` and exposes the PURE step-executor seam:

  - `parse-spec`              — coerce `:play-script` body → normalised
                                `{:script :auto-run? :name}` map.
  - `step-type`               — first element of a step vector.
  - `step-arity-ok?`          — validate step shape (pre-flight).
  - `coerce-script`           — normalise mixed shapes (legacy bare
                                event vectors become `[:dispatch evec]`).
  - `initial-state` / `advance-state` — pure state-machine driving the
                                run-status (`:idle`/`:running`/`:pass`/`:fail`).
  - `step-summary`            — human-readable string for log/trace.

  The RUN driver itself (the side-effecty part — dispatching events,
  reading app-db, sleeping, querying the DOM) lives in
  `re-frame.story.play.runner-events` and `re-frame.story.play.dom`.
  This namespace stays free of `re-frame.core` requires so unit tests
  can exercise the parser + state machine purely via JVM."
  (:require [clojure.string :as str]))

;; ---- step-type vocabulary ------------------------------------------------

(def step-types
  "The canonical step-type tags the runner recognises."
  #{:dispatch :dispatch-sync :wait
    :assert-db :assert-dom
    :click :type})

(def assertion-step-types
  "Steps whose outcome contributes to the play's pass/fail status."
  #{:assert-db :assert-dom})

(def async-yield-step-types
  "Steps that put work on an async queue the runner cannot directly
  flush — `:dispatch` (re-frame router), `:click` / `:type` (synthetic
  DOM events whose handlers re-enter the dispatch chain), and `:wait`
  (the runner sleeps explicitly). The driver yields one tick AFTER these
  steps so the queued effects drain before the next step runs.

  Steps NOT in this set (`:dispatch-sync`, `:assert-db`, `:assert-dom`)
  are pure-synchronous on CLJS — yielding between them is what allowed
  concurrent `auto-run!` calls to interleave and overshoot counter
  increments in the Playwright matrix (rf2-ftow6)."
  #{:dispatch :click :type :wait})

(declare step-type)

(defn async-yield?
  "True iff the step's after-effects need a setTimeout-0 yield to drain
  before the next step runs. Pure data → data. Used by the driver
  (`runner-events/run-loop!`) on CLJS to decide whether to recur
  synchronously or schedule the next step."
  [step]
  (contains? async-yield-step-types (step-type step)))

(defn step-type
  "Return the tag at the head of a step vector, or nil if `step` is
  not a vector / has no head keyword."
  [step]
  (when (and (vector? step) (pos? (count step)))
    (let [head (first step)]
      (when (keyword? head) head))))

(defn known-step?
  "True iff `step` is a vector whose first element is one of the
  registered step-type tags."
  [step]
  (contains? step-types (step-type step)))

(defn step-arity-ok?
  "Light arity / shape check for a step vector. Returns true when the
  step has the right shape for its tag, false otherwise. Used by
  `coerce-script` to pre-flight a script before driving it."
  [step]
  (case (step-type step)
    :dispatch       (boolean
                      (and (= 2 (count step))
                           (vector? (nth step 1))
                           (pos? (count (nth step 1)))
                           (keyword? (first (nth step 1)))))
    :dispatch-sync  (boolean
                      (and (= 2 (count step))
                           (vector? (nth step 1))
                           (pos? (count (nth step 1)))
                           (keyword? (first (nth step 1)))))
    :wait           (boolean
                      (and (= 2 (count step))
                           (number? (nth step 1))
                           (not (neg? (nth step 1)))))
    :assert-db      (boolean
                      (and (>= (count step) 3)
                           (vector? (nth step 1))
                           (or
                             ;; :pred form is 4-arity: [:assert-db path :pred ref]
                             ;; where `ref` is EITHER a fn (preferred — works
                             ;; under advanced CLJS) OR a symbol (JVM escape
                             ;; hatch via `requiring-resolve`).
                             (and (= 4 (count step))
                                  (= :pred (nth step 2))
                                  (let [r (nth step 3)]
                                    (or (fn? r) (symbol? r))))
                             ;; equality form is 3-arity: [:assert-db path value]
                             ;; ANY value (including nil) is legal — but :pred is
                             ;; reserved as a discriminator and would be ambiguous.
                             (and (= 3 (count step))
                                  (not= :pred (nth step 2))))))
    :assert-dom     (boolean
                      (and (>= (count step) 3)
                           (string? (nth step 1))
                           (or (and (= 3 (count step))
                                    (contains? #{:visible :hidden} (nth step 2)))
                               (and (= 4 (count step))
                                    (= :text (nth step 2))
                                    (string? (nth step 3))))))
    :click          (boolean
                      (and (= 2 (count step))
                           (string? (nth step 1))))
    :type           (boolean
                      (and (= 3 (count step))
                           (string? (nth step 1))
                           (string? (nth step 2))))
    false))

;; ---- legacy bare-event-vector lift --------------------------------------

(defn- bare-event-vector?
  "True iff `v` looks like a re-frame event vector but is NOT a known
  step. We treat these as legacy sugar for `[:dispatch v]`."
  [v]
  (and (vector? v)
       (pos? (count v))
       (keyword? (first v))
       (not (known-step? v))))

(defn coerce-script
  "Normalise a `:script` vector: every entry is either a known tagged
  step (`[:dispatch ...]` etc.) or a bare event vector (`[:my/event
  ...]`) which is lifted to `[:dispatch <event-vec>]`. Returns the
  vector of coerced steps in order. Pure data → data."
  [script]
  (->> (or script [])
       (mapv (fn [step]
               (cond
                 (known-step? step)      step
                 (bare-event-vector? step) [:dispatch step]
                 :else                     step)))))

;; ---- spec parsing -------------------------------------------------------

(def ^:const default-auto-run?
  "Default `:auto-run?` value when the spec omits it. The bead reads
  'After mount: auto-run play (if `:auto-run? true`)' — so we make
  auto-run the default behaviour. Authors opt OUT explicitly."
  true)

(defn parse-spec
  "Normalise the `:play-script` body into a canonical map:

      {:script    <coerced vector of steps>
       :auto-run? <bool>
       :name      <string or nil>}

  Two input shapes are recognised:

  - Bare vector — `[[:dispatch [...]] [:wait 100] ...]`. Equivalent to
    `{:script <vector>}`.
  - Map         — `{:script [...] :auto-run? bool :name str}`.

  An unrecognised input shape yields `{:script [] :auto-run? true}`.
  Pure data → data."
  [body]
  (let [raw (cond
              (nil?    body) {:script []}
              (vector? body) {:script body}
              (map?    body) body
              :else          {:script []})
        script    (coerce-script (get raw :script []))
        auto-run? (if (contains? raw :auto-run?)
                    (boolean (:auto-run? raw))
                    default-auto-run?)
        nm        (when-let [n (:name raw)] (str n))]
    (cond-> {:script script :auto-run? auto-run?}
      nm (assoc :name nm))))

;; ---- :plays multi-play resolution (rf2-tl7zk) ----------------------------

(defn- parse-named-play
  "Normalise ONE `:plays` entry. Auto-run defaults differ for the first
  vs subsequent plays — the first entry mirrors the single-play default
  (auto-run? true) so deep-linking to a multi-play variant 'just works';
  subsequent entries default to false so the page doesn't run every
  scenario back-to-back on mount."
  [first? entry]
  (let [script    (coerce-script (get entry :script []))
        auto-run? (if (contains? entry :auto-run?)
                    (boolean (:auto-run? entry))
                    first?)
        nm        (when-let [n (:name entry)] (str n))]
    (cond-> {:script script :auto-run? auto-run?}
      nm (assoc :name nm))))

(defn parse-plays
  "Normalise a `:plays` vector into a vector of `{:script :auto-run?
  :name}` maps. Pure data → data.

  Each entry is run through the same coercion as `parse-spec` so bare
  event vectors lift to `[:dispatch ...]`, and `:auto-run?` defaults to
  true for the FIRST entry / false for the rest (matching the
  single-play `:play-script` deep-link behaviour). Authors override
  the per-play default by setting `:auto-run?` explicitly."
  [plays]
  (let [v (cond
            (nil?    plays) []
            (vector? plays) plays
            :else           [])]
    (->> v
         (map-indexed (fn [idx entry] (parse-named-play (zero? idx) entry)))
         vec)))

(defn variant-body->plays
  "Resolve a variant body's play surface into a CANONICAL vector of
  parsed plays. Pure data → data.

  Resolution order (mutual-exclusion handled at the schema layer; this
  fn is tolerant in case the schema gate is elided):

  - Both `:plays` and `:play-script` present → prefer `:plays` (the
    runtime warning is emitted by the runner-events ns at resolve time).
  - `:plays` present → return parsed plays.
  - `:play-script` present → wrap in a single-entry vector. The wrapped
    entry inherits the script's `:name` (or nil), and `:auto-run?` from
    `parse-spec`.
  - Neither → empty vector.

  Every returned entry carries `{:script :auto-run? :name}` (the same
  shape as `parse-spec`). An entry's `:name` is nil only for the
  single-script wrap-up of `:play-script` when the script body omits
  `:name`."
  [variant-body]
  (cond
    (and (some? variant-body) (contains? variant-body :plays))
    (parse-plays (:plays variant-body))

    (and (some? variant-body) (contains? variant-body :play-script))
    [(parse-spec (:play-script variant-body))]

    :else
    []))

(defn play-key
  "Stable key for ONE play within a variant. The empty / single-script
  shape uses `nil` (the legacy `:play-script` slot has no per-play
  identifier). Multi-play entries use the play's `:name` string.

  Used by the runner-events ns to key per-(variant, play) run-state
  and by the UI's chip dropdown / CI runner to identify a play
  unambiguously."
  [play]
  (when play (:name play)))

(defn find-play
  "Return the play at `play-key` (a name string) in `plays`, or nil.
  `play-key` of nil matches the single-entry case (the legacy
  `:play-script` wrap)."
  [plays play-key]
  (when (seq plays)
    (if (nil? play-key)
      (first plays)
      (some (fn [p] (when (= play-key (:name p)) p)) plays))))

(defn default-play-key
  "Return the default play key for `plays`. For multi-play this is the
  name of the first play (the toolbar starts focused there); for the
  single-play case this is `nil`."
  [plays]
  (when (seq plays)
    (let [first-name (:name (first plays))]
      ;; Single-script :play-script wrap leaves :name nil → keep nil.
      ;; Multi-play entries always carry a :name (enforced by schema).
      first-name)))

(defn multi?
  "True iff `plays` carries more than one entry — i.e. the variant
  declared `:plays` with N >= 2 (or the schema gate was bypassed)."
  [plays]
  (boolean (and (vector? plays) (> (count plays) 1))))

;; ---- run-state state machine ---------------------------------------------

(def ^:const status-idle    :idle)
(def ^:const status-running :running)
(def ^:const status-pass    :pass)
(def ^:const status-fail    :fail)

(defn initial-state
  "Build the initial state map for a run. Pure data → data.

  Fields:

  - `:status`      — `:idle` | `:running` | `:pass` | `:fail`
  - `:step-idx`    — 0-based index into `:script` (next step to run)
  - `:total`       — script length
  - `:results`     — vector of per-step result records
  - `:failures`    — count of failed assertion results
  - `:started-ms`  — wall-clock when run began (nil while idle)
  - `:finished-ms` — wall-clock when run completed (nil while running)
  - `:script`      — the coerced steps (denormalised so consumers can
                     render without re-reading the spec)
  - `:name`        — optional spec name"
  [{:keys [script name]}]
  {:status       status-idle
   :step-idx     0
   :total        (count script)
   :results      []
   :failures     0
   :started-ms   nil
   :finished-ms  nil
   :script       (vec script)
   :name         name})

(defn start
  "Transition `state` to `:running`. Pure data → data."
  [state now-ms]
  (-> state
      (assoc :status      status-running
             :step-idx    0
             :started-ms  now-ms
             :finished-ms nil
             :results     []
             :failures    0)))

(defn record-step-result
  "Append a step-result record to `:results`, bump `:step-idx`, and
  bump `:failures` when the record is an assertion that failed. Pure
  data → data. The caller decides whether the run continues after a
  failed assertion — by IMPL-SPEC §2.3 we record, never throw, so the
  default path runs every step."
  [state result]
  (-> state
      (update :results conj result)
      (update :step-idx inc)
      (cond->
        (and (not (:passed? result))
             (some? (:passed? result)))
        (update :failures inc))))

(defn finish
  "Transition `state` to the terminal `:pass` / `:fail` status.

  - If any step recorded an exception or any assertion failed → `:fail`.
  - Otherwise → `:pass`.

  Pure data → data."
  [state now-ms]
  (let [failed? (or (pos? (:failures state 0))
                    (some #(some? (:exception %)) (:results state)))]
    (assoc state
           :status      (if failed? status-fail status-pass)
           :finished-ms now-ms)))

(defn done?
  "True iff every step in `:script` has been processed."
  [{:keys [step-idx total]}]
  (>= step-idx total))

(defn current-step
  "The step at `:step-idx`, or nil if the run is exhausted."
  [{:keys [script step-idx]}]
  (when (and (vector? script) (< step-idx (count script)))
    (nth script step-idx)))

(defn progress-str
  "Render `:step-idx`/`:total` as `RUNNING(step 3/8)` etc. Used by
  the status chip and the trace banner."
  [{:keys [status step-idx total]}]
  (case status
    :idle    "IDLE"
    :running (str "RUNNING (step " (inc step-idx) "/" total ")")
    :pass    (str "PASS (" total " steps)")
    :fail    (str "FAIL (" step-idx "/" total " steps)")
    (str status)))

(defn assertion?
  "True iff the step is an assertion-class step."
  [step]
  (contains? assertion-step-types (step-type step)))

;; ---- step-result builders (pure) ----------------------------------------

(defn step-pass
  "Construct a `:passed? true` step-result record for `step` at `idx`."
  [idx step]
  {:idx     idx
   :step    step
   :type    (step-type step)
   :passed? true})

(defn step-fail
  "Construct a `:passed? false` step-result record. `extra` merges in
  diagnostic slots like `:expected` / `:actual` / `:message`."
  [idx step extra]
  (merge {:idx     idx
          :step    step
          :type    (step-type step)
          :passed? false}
         extra))

(defn step-skip
  "Construct a no-assertion step-result record (e.g. for `:wait` /
  `:dispatch` — these run, but they don't pass/fail. `:passed?` is
  nil so the finalisation logic doesn't count them as failures."
  [idx step]
  {:idx     idx
   :step    step
   :type    (step-type step)
   :passed? nil})

(defn step-exception
  "Construct a `:passed? false` step-result that records an unexpected
  exception while executing the step."
  [idx step message]
  {:idx       idx
   :step      step
   :type      (step-type step)
   :passed?   false
   :exception true
   :message   (str message)})

(defn unknown-step
  "Construct an `:unknown-step` failure record for a malformed step."
  [idx step]
  (step-fail idx step
             {:message (str "unknown or malformed step: " (pr-str step))}))

;; ---- step humanisation --------------------------------------------------

(defn step-summary
  "Render `step` as a short single-line string for log/trace display.
  Pure data → data; deterministic."
  [step]
  (case (step-type step)
    :dispatch       (str "dispatch " (pr-str (second step)))
    :dispatch-sync  (str "dispatch-sync " (pr-str (second step)))
    :wait           (str "wait " (second step) "ms")
    :assert-db      (cond
                      (and (= 4 (count step)) (= :pred (nth step 2)))
                      (let [ref (nth step 3)]
                        (str "assert-db " (pr-str (second step))
                             " :pred "
                             (cond
                               (symbol? ref) (pr-str ref)
                               (fn? ref)     "<fn>"
                               :else         (pr-str ref))))
                      :else
                      (str "assert-db " (pr-str (second step))
                           " = " (pr-str (nth step 2))))
    :assert-dom     (cond
                      (= 3 (count step))
                      (str "assert-dom " (pr-str (second step))
                           " " (name (nth step 2)))
                      :else
                      (str "assert-dom " (pr-str (second step))
                           " :text " (pr-str (nth step 3))))
    :click          (str "click " (pr-str (second step)))
    :type           (str "type "  (pr-str (second step))
                         " " (pr-str (nth step 2)))
    (str "unknown " (pr-str step))))

;; ---- script validation --------------------------------------------------

(defn validate-script
  "Pre-flight a coerced script. Returns a vector of `{:idx :step
  :reason}` for every malformed step, or `[]` when the script is
  clean. Used by the runner before driving so structural errors land
  in `:results` once, not on every step attempt."
  [script]
  (->> script
       (map-indexed
         (fn [idx step]
           (cond
             (not (known-step? step))
             {:idx idx :step step :reason :unknown-step}
             (not (step-arity-ok? step))
             {:idx idx :step step :reason :bad-arity}
             :else nil)))
       (remove nil?)
       vec))

;; ---- run timeline -------------------------------------------------------

(defn elapsed-ms
  "Wall-clock elapsed for the run, or nil if not yet started/finished."
  [{:keys [started-ms finished-ms]}]
  (when (and started-ms finished-ms)
    (- finished-ms started-ms)))

(defn fail-summary
  "Render the failure summary for the banner: the first failed
  assertion (or exception) + the failure count. Returns nil when
  the run is not in `:fail` state."
  [{:keys [status results]}]
  (when (= status status-fail)
    (let [fails (filterv (fn [r] (false? (:passed? r))) results)]
      {:count   (count fails)
       :first   (first fails)
       :results fails})))

;; ---- selector helpers ---------------------------------------------------

(defn step-selector
  "Return the DOM selector string from a `:click` / `:type` /
  `:assert-dom` step, or nil. Used by the UI's click-to-highlight
  failing-element affordance."
  [step]
  (case (step-type step)
    :click       (nth step 1 nil)
    :type        (nth step 1 nil)
    :assert-dom  (nth step 1 nil)
    nil))

;; ---- exposed step accessors (pure) --------------------------------------

(defn step-event
  "Return the event vector from a `:dispatch` / `:dispatch-sync` step,
  or nil."
  [step]
  (when (#{:dispatch :dispatch-sync} (step-type step))
    (nth step 1 nil)))

(defn step-wait-ms
  "Return the ms duration from a `:wait` step, or nil."
  [step]
  (when (= :wait (step-type step))
    (nth step 1 nil)))

(defn step-assert-db
  "Decompose an `:assert-db` step into `{:path <vec> :mode :equals|:pred
  :expected <val> :pred-ref <fn-or-sym> :pred-fn? <bool>}`.

  The `:pred` form is 4-arity (`[:assert-db path :pred ref]`) where
  `ref` is EITHER a fn (preferred — works under advanced CLJS) OR a
  symbol (resolved via `requiring-resolve` on JVM, best-effort
  `goog.global` walk on CLJS — fragile under advanced). The
  equality form is 3-arity (`[:assert-db path value]`).

  `:pred-fn?` discriminates so the runner can skip resolution when the
  caller already handed in a callable."
  [step]
  (when (= :assert-db (step-type step))
    (let [path (nth step 1)]
      (if (and (= 4 (count step)) (= :pred (nth step 2)))
        (let [ref (nth step 3)]
          {:path path :mode :pred :pred-ref ref :pred-fn? (fn? ref)})
        {:path path :mode :equals :expected (nth step 2)}))))

(defn step-assert-dom
  "Decompose an `:assert-dom` step into `{:selector :mode :text}`."
  [step]
  (when (= :assert-dom (step-type step))
    (let [selector (nth step 1)
          mode     (nth step 2)]
      (cond-> {:selector selector :mode mode}
        (= :text mode) (assoc :text (nth step 3))))))

(defn step-type-text
  "Return `[selector text]` from a `:type` step."
  [step]
  (when (= :type (step-type step))
    [(nth step 1) (nth step 2)]))

;; ---- trace shape --------------------------------------------------------

(def ^:const trace-event-id
  "The synthetic event id emitted into the trace bus per step. Spec/009
  trace correlation: each step landing on the bus lets the Trace tab
  show the full play timeline."
  :rf.story.play/step)

(defn trace-record
  "Build the trace payload for a step execution. Pure data → data; the
  side-effecty wiring (`re-frame.trace.tooling/with-trace`) lives in
  the runner-events ns."
  [{:keys [variant-id name idx step result]}]
  (cond-> {:variant-id variant-id
           :idx        idx
           :step       step
           :summary    (step-summary step)}
    name   (assoc :name name)
    result (assoc :passed? (:passed? result)
                  :message (:message result))))

;; ---- helper: was-failure? --------------------------------------------

(defn any-failure?
  "True iff `state` carries at least one failed assertion / exception
  result. Used by the UI banner."
  [{:keys [results]}]
  (boolean
    (some (fn [r] (or (false? (:passed? r))
                      (some? (:exception r))))
          results)))

;; ---- diagnostics --------------------------------------------------------

(defn explain-state
  "Render the run-state as a short multi-line string for diagnostics.
  Pure data → data."
  [{:keys [status step-idx total failures results name] :as state}]
  (let [header (str (when name (str "[" name "] ")) (progress-str state))]
    (str header
         (when (pos? failures)
           (str "\n  failures: " failures))
         (when (seq results)
           (str "\n  ran: " (count results) "/" total)))))
