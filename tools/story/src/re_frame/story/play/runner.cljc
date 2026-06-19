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

  ## The tagged step grammar (spec/017 §Script step grammar)

  P1 uses ONE tagged step grammar across `:setup` and `:script`. Every
  step declares its minimum runner; the boundary ladder
  (`re-frame.story.play.settled-boundary`) governs settlement and the
  capability registry (`re-frame.story.requirements`) governs which
  runner can prove it. Bare event-vector shorthand is NOT the public form
  — it is normalized to `[:dispatch event-vector]` during migration
  (`coerce-script`) because an app event genuinely named `:dispatch` /
  `:click` / `:wait` / `:focus` would otherwise be silently
  un-dispatchable (a reserved-word hazard).

  `[:wait-until predicate-spec]` advances when a queue/state predicate
  becomes true, so it is DETERMINISTIC (the determinism gate accepts it;
  only bare `[:wait ms]` is refused). The predicate-spec is one of
  `[:db path expected]`, `[:db path :pred fn]`, or `[:queue-empty]`. A
  predicate that never becomes true TIMES OUT READABLY with a step-fail,
  never a silent pass.

  `[:assert [:rf.assert/id & args]]` evaluates the assertion atom at this
  exact point in the script (the ONE assertion atom in its mid-script
  checkpoint position; the terminal `:assertions` slot is the other). In
  headless the checkpoint dispatches the wrapped `:rf.assert/*` event
  (the runner's headless implementation rail) and surfaces the recorded
  outcome. `[:assert …]` is REJECTED in `:setup` at plan-compile time
  (`re-frame.story.plan`): setup establishes preconditions, it does not
  judge.

  `[:focus selector]` / `[:click selector]` / `[:type selector text]` /
  `[:assert-dom …]` are DOM steps; under a headless runner they refuse
  with `:cannot-run` (the capability registry requires `:dom`), and they
  run under a `:dom` / `:browser` runner.

  | Step                               | Semantics                                                     |
  |------------------------------------|---------------------------------------------------------------|
  | `[:dispatch event-vec]`            | settled dispatch — drain through `settled-boundary`           |
  | `[:dispatch-sync event-vec]`       | low-level synchronous dispatch escape (not the author form)   |
  | `[:wait-until predicate-spec]`     | deterministic settle-on-condition (queue/state)               |
  | `[:wait ms]`                       | bounded wall-clock sleep — the explicit determinism opt-out   |
  | `[:assert assertion-vector]`       | in-script checkpoint assertion (illegal in `:setup`)          |
  | `[:assert-db path value]`          | Assert `(= (get-in @app-db path) value)`                      |
  | `[:assert-db path :pred fn-or-sym]`| Assert custom predicate — `fn` is preferred (works under advanced CLJS); `symbol` is the JVM/dev escape hatch (resolved at run time, fragile under advanced CLJS munging) |
  | `[:assert-dom selector :visible]`  | Assert selector resolves to a visible DOM node                |
  | `[:assert-dom selector :hidden]`   | Assert selector resolves to nothing (or hidden node)          |
  | `[:assert-dom selector :text txt]` | Assert selector's text-content matches `txt`                  |
  | `[:click selector]`                | Synthetic click event at selector                             |
  | `[:type selector text]`            | Synthetic `input` event at selector with `text`               |
  | `[:focus selector]`                | Synthetic focus event at selector                             |

  Steps run sequentially. A failed `:assert-*` step is RECORDED — the
  run continues so the user sees all failures, not just the first
  (per `004-Assertions.md` §Record-don't-throw semantics 'record, don't throw').

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
  "The canonical step-type tags the runner recognises — the one tagged
  step grammar across `:setup` and `:script` (spec/017 §Script step
  grammar, rf2-5x1wt.17). `:assert` is the in-script checkpoint atom
  (the wrapped `:rf.assert/*` assertion); `:wait-until` is the
  deterministic settle-on-condition; `:focus` is the DOM focus step."
  #{:dispatch :dispatch-sync :wait :wait-until
    :assert :assert-db :assert-dom
    :click :type :focus})

(def assertion-step-types
  "Steps whose outcome contributes to the play's pass/fail status —
  including the `[:assert …]` in-script checkpoint (rf2-5x1wt.17), which
  evaluates a `:rf.assert/*` atom at this exact point in the script."
  #{:assert :assert-db :assert-dom})

(def async-yield-step-types
  "Steps that put work on an async queue the runner cannot directly
  flush — `:click` / `:type` / `:focus` (synthetic DOM events whose
  handlers re-enter the dispatch chain) and `:wait` (the runner sleeps
  explicitly). The driver yields one tick AFTER these steps so the
  queued effects drain before the next step runs.

  `:dispatch` is NOT in this set as of rf2-5x1wt.2: a `[:dispatch …]`
  step now settles through `settled-boundary` (spec/017) — in headless
  the `dispatch-sync*` run-to-fixed-point drain — so it is synchronous
  at the step boundary, exactly like `:dispatch-sync`. Yielding between
  synchronous steps is what let concurrent `auto-run!` calls interleave
  and overshoot counter increments in the Playwright matrix (rf2-ftow6);
  settling `:dispatch` synchronously removes that hazard for the queued
  authoring form too.

  `:wait-until` is NOT here either: in headless the predicate is checked
  synchronously once the preceding dispatch has settled (rf2-5x1wt.17),
  so it recurs synchronously like the assertion steps. A richer runner's
  bounded poll handles its own scheduling.

  Steps NOT in this set (`:dispatch`, `:dispatch-sync`, `:wait-until`,
  `:assert`, `:assert-db`, `:assert-dom`) recur synchronously on CLJS."
  #{:click :type :focus :wait})

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
    ;; rf2-l2cn5d (EP-0017): a `:dispatch` / `:dispatch-sync` step is
    ;; either the bare 2-element `[:dispatch evec]` or the 3-element
    ;; `[:dispatch evec opts]` carrying a recordable-coeffect envelope
    ;; (`{:rf.cofx <map>}`) the runner threads into the dispatch opts.
    ;; The opts slot, when present, MUST be a map.
    :dispatch       (boolean
                      (and (<= 2 (count step) 3)
                           (vector? (nth step 1))
                           (pos? (count (nth step 1)))
                           (keyword? (first (nth step 1)))
                           (or (= 2 (count step)) (map? (nth step 2)))))
    :dispatch-sync  (boolean
                      (and (<= 2 (count step) 3)
                           (vector? (nth step 1))
                           (pos? (count (nth step 1)))
                           (keyword? (first (nth step 1)))
                           (or (= 2 (count step)) (map? (nth step 2)))))
    :wait           (boolean
                      (and (= 2 (count step))
                           (number? (nth step 1))
                           (not (neg? (nth step 1)))))
    ;; `[:wait-until predicate-spec]` — deterministic settle-on-condition.
    ;; The predicate-spec is `[:db path expected]`, `[:db path :pred fn]`,
    ;; or `[:queue-empty]` (rf2-5x1wt.17). A 2-arity vector whose payload
    ;; is itself a tagged predicate-spec vector.
    :wait-until     (boolean
                      (and (= 2 (count step))
                           (let [pspec (nth step 1)]
                             (and (vector? pspec)
                                  (pos? (count pspec))
                                  (case (first pspec)
                                    :db          (or
                                                   ;; [:db path expected]
                                                   (and (= 3 (count pspec))
                                                        (vector? (nth pspec 1))
                                                        (not= :pred (nth pspec 2)))
                                                   ;; [:db path :pred fn-or-sym]
                                                   (and (= 4 (count pspec))
                                                        (vector? (nth pspec 1))
                                                        (= :pred (nth pspec 2))
                                                        (let [r (nth pspec 3)]
                                                          (or (fn? r) (symbol? r)))))
                                    :queue-empty (= 1 (count pspec))
                                    false)))))
    ;; `[:assert assertion-vector]` — the in-script checkpoint atom. The
    ;; wrapped form is a `:rf.assert/*` event vector (rf2-5x1wt.17).
    :assert         (boolean
                      (and (= 2 (count step))
                           (let [a (nth step 1)]
                             (and (vector? a)
                                  (pos? (count a))
                                  (keyword? (first a))))))
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
    :focus          (boolean
                      (and (= 2 (count step))
                           (string? (nth step 1))))
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

(defn auto-runnable?
  "True iff a single play auto-runs: it declares `:auto-run? true` AND
  carries a non-empty `:script`. The empty-script guard keeps a play
  that opts in but has nothing to do from spuriously firing. Pure data
  → data."
  [play]
  (boolean (and (:auto-run? play) (seq (:script play)))))

(defn auto-runnable-plays
  "The subset of `plays` that auto-run on mount — those passing
  `auto-runnable?`. This is the SINGLE definition of 'which plays
  auto-run'; both the runtime orchestrator (`runtime/run-phase-4!`) and
  the shell driver (`runner-events/auto-run!`) delegate here so the rule
  lives in one place. Returns a vector (order-preserving). Pure data →
  data."
  [plays]
  (filterv auto-runnable? plays))

;; ---- run-state state machine ---------------------------------------------

(def ^:const status-idle       :idle)
(def ^:const status-running    :running)
(def ^:const status-pass       :pass)
(def ^:const status-fail       :fail)
(def ^:const status-cannot-run :cannot-run)

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

(defn- cannot-run-step?
  "True iff a step-result is a `:cannot-run` refusal — a capability /
  boundary refusal (`:cannot-run?`) or a no-DOM skip (`:skipped?`). These
  are the distinct THIRD status (spec/017 §`:cannot-run`): the step could
  not be attempted, not a genuine fail."
  [r]
  (boolean (or (:cannot-run? r) (:skipped? r))))

(defn record-step-result
  "Append a step-result record to `:results`, bump `:step-idx`, and
  bump `:failures` when the record is an assertion that GENUINELY failed.
  Pure data → data. The caller decides whether the run continues after a
  failed assertion — by `004-Assertions.md` §Record-don't-throw semantics we record, never throw, so the
  default path runs every step.

  `:failures` counts only genuine failures — a `false?` `:passed?` result
  that is NOT a `:cannot-run` refusal. A `:cannot-run?` / `:skipped?`
  refusal sets `:passed?` false but is the distinct THIRD status (it could
  not be attempted, not a fail), so it must NOT bump `:failures` — otherwise
  `finish`'s `:status :cannot-run` verdict and this `:failures` count
  disagree, and a CI consumer keying off `:failures > 0` would flag a
  cannot-run-only run as red (rf2-eztym.1). This uses the same predicate
  (`cannot-run-step?`) `finish` consults for `real-failures`."
  [state result]
  (-> state
      (update :results conj result)
      (update :step-idx inc)
      (cond->
        (and (false? (:passed? result))
             (not (cannot-run-step? result)))
        (update :failures inc))))

(defn finish
  "Transition `state` to the terminal `:pass` / `:fail` / `:cannot-run`
  status (rf2-5x1wt.19 — `:cannot-run` is the unified distinct THIRD
  status, spec/017 §`:cannot-run`).

  - A genuine failure (an exception, or a failing assertion that is NOT a
    `:cannot-run` refusal) → `:fail`.
  - Else, when the ONLY non-pass step-results are `:cannot-run` refusals
    (a capability / boundary refusal or a no-DOM skip) → `:cannot-run`.
  - Otherwise → `:pass`.

  This mirrors the variant-level aggregation rule
  (`requirements/aggregate-status`): a run whose only unmet expectations
  are refusals is itself `:cannot-run`, never a silent pass. Pure data →
  data."
  [state now-ms]
  (let [results       (:results state)
        exception?    (some #(some? (:exception %)) results)
        real-failures (filter (fn [r] (and (false? (:passed? r))
                                            (not (cannot-run-step? r))))
                              results)
        refusals      (filter cannot-run-step? results)
        status        (cond
                        (or exception? (seq real-failures)) status-fail
                        (seq refusals)                      status-cannot-run
                        :else                               status-pass)]
    (assoc state
           :status      status
           :finished-ms now-ms)))

(defn run-state-refusals
  "The `:cannot-run` refusal records carried by a settled run-`state`'s
  per-step `:results` — the same step-results `finish` inspects to decide
  the run-state status (rf2-q5jw4). Pure data → data.

  A refusal is a step-result a runner could not even ATTEMPT for its
  declared capability (a no-DOM `[:assert-dom …]` / `[:click …]` skip, or a
  boundary `:cannot-run?`), so it must propagate into the UNIFIED run-result
  as the distinct THIRD status (spec/017 §`:cannot-run`), not vanish into a
  vacuous green. Each refusal projects to the
  `{:status :cannot-run :unit :reason :message}` shape the unified result's
  `:unmet` slot folds (`re-frame.story.requirements/aggregate-status`):

      {:status  :cannot-run
       :unit    <step>           ; the step the runner could not attempt
       :reason  :runner-cannot-attempt-step
       :message <diagnostic>}    ; the step-result's :message, when present

  This is the SINGLE bridge from the run-state's refusal facts to the
  unified result, so the run-state and the unified `:status` cannot disagree
  — the false-GREEN class rf2-5x1wt.19 set out to kill (a DOM-skip-only
  variant read `:pass` via the unified result while the run-state read
  `:cannot-run`). Empty when no step refused."
  [state]
  (into []
        (comp (filter cannot-run-step?)
              (map (fn [r]
                     (cond-> {:status :cannot-run
                              :unit   (:step r)
                              :reason :runner-cannot-attempt-step}
                       (:message r) (assoc :message (:message r))))))
        (:results state)))

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
    :idle       "IDLE"
    :running    (str "RUNNING (step " (inc step-idx) "/" total ")")
    :pass       (str "PASS (" total " steps)")
    :fail       (str "FAIL (" step-idx "/" total " steps)")
    :cannot-run (str "CANNOT-RUN (" total " steps)")
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
    :wait-until     (str "wait-until " (pr-str (second step)))
    :assert         (str "assert " (pr-str (second step)))
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
    :focus          (str "focus " (pr-str (second step)))
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
  "Return the DOM selector string from a `:click` / `:type` / `:focus` /
  `:assert-dom` step, or nil. Used by the UI's click-to-highlight
  failing-element affordance."
  [step]
  (case (step-type step)
    :click       (nth step 1 nil)
    :type        (nth step 1 nil)
    :focus       (nth step 1 nil)
    :assert-dom  (nth step 1 nil)
    nil))

;; ---- exposed step accessors (pure) --------------------------------------

(defn step-event
  "Return the event vector from a `:dispatch` / `:dispatch-sync` step,
  or nil."
  [step]
  (when (#{:dispatch :dispatch-sync} (step-type step))
    (nth step 1 nil)))

(defn step-cofx
  "Return the captured flat `:rf.cofx` map from a 3-element
  `[:dispatch evec opts]` / `[:dispatch-sync evec opts]` step
  (rf2-l2cn5d, EP-0017), or nil. The opts map's `:rf.cofx` is the
  recordable-coeffect envelope the recorder captured; the executor
  threads it into the dispatch opts so replay re-presents the recorded
  values (provided facts + the framework `:rf/time-ms`)."
  [step]
  (when (#{:dispatch :dispatch-sync} (step-type step))
    (let [opts (nth step 2 nil)]
      (when (map? opts)
        (:rf.cofx opts)))))

(defn step-wait-ms
  "Return the ms duration from a `:wait` step, or nil."
  [step]
  (when (= :wait (step-type step))
    (nth step 1 nil)))

(defn step-assertion
  "Return the wrapped `:rf.assert/*` assertion atom from an
  `[:assert assertion-vector]` checkpoint step, or nil (rf2-5x1wt.17).
  This is the ONE assertion atom in its in-script position — the same
  vector the terminal `:assertions` slot carries."
  [step]
  (when (= :assert (step-type step))
    (nth step 1 nil)))

(defn step-wait-until
  "Decompose a `[:wait-until predicate-spec]` step into
  `{:kind :db|:queue-empty :path <vec> :mode :equals|:pred :expected <val>
  :pred-ref <fn-or-sym> :pred-fn? <bool>}` (rf2-5x1wt.17). Returns nil
  for a non-`:wait-until` step.

  - `[:db path expected]`       → `{:kind :db :path … :mode :equals :expected …}`
  - `[:db path :pred fn-or-sym]`→ `{:kind :db :path … :mode :pred :pred-ref … :pred-fn? …}`
  - `[:queue-empty]`            → `{:kind :queue-empty}`"
  [step]
  (when (= :wait-until (step-type step))
    (let [pspec (nth step 1 nil)]
      (case (first pspec)
        :db          (let [path (nth pspec 1)]
                       (if (and (= 4 (count pspec)) (= :pred (nth pspec 2)))
                         (let [ref (nth pspec 3)]
                           {:kind :db :path path :mode :pred
                            :pred-ref ref :pred-fn? (fn? ref)})
                         {:kind :db :path path :mode :equals
                          :expected (nth pspec 2)}))
        :queue-empty {:kind :queue-empty}
        nil))))

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
