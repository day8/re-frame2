(ns re-frame.story.ui.test-mode.pure
  "Pure data → data helpers for the `:test` mode pane (rf2-qmjo + spec/009).

  Split out of the legacy `re-frame.story.ui.test-mode` monolith per
  rf2-8n2fz so the JVM test corpus can cover the pane's data shaping
  without booting Reagent or the runtime. Companion namespaces:

  - `re-frame.story.ui.test-mode.state` — CLJS local-state atom + the
    `begin-run!` / `store-result!` / `select-step!` / `toggle-expanded!` /
    `run-variant-pane!` mutators.
  - `re-frame.story.ui.test-mode.view`  — CLJS-only styles, section
    renderers, and the top-level `test-view` component.

  Everything in this namespace is `.cljc` so it runs unchanged on both
  the JVM (for the test corpus) and CLJS (consumed by `view.cljs`)."
  (:require [clojure.string            :as str]
            [re-frame.story.assertions :as assertions]
            [re-frame.story.predicates :as pred]
            [re-frame.story.registrar  :as registrar]))

;; ---- aliases on the leaf predicates ns ----------------------------------
;;
;; `assertion-event?` lives canonically in `re-frame.story.predicates`
;; (a pure leaf ns the rest of Story consumes without cycle risk).
;; Aliased here so internal call sites stay textually identical.
;;
;; rf2-ee38b.3: the `parent-story-id` re-export was DROPPED — its sole
;; caller (`test-mode.view`) now calls `pred/parent-story-id` directly.

(def ^:private assertion-event? pred/assertion-event?)

;; ---- the unified run-result verdicts (spec/017 §Run result) -------------
;;
;; The four verdicts every assertion record / check / run carries
;; (`re-frame.story.result/statuses`). Mirrored here as a local set so the
;; pure helpers can recognise a stamped `:status` without a require into
;; `re-frame.story.result` (which loops through the runtime). rf2-ba86n.11.

(def statuses
  "The unified run / check / assertion verdicts (spec/017 §Run result)."
  #{:pass :fail :cannot-run :error})

;; ---- pure: variant-has-tests? -------------------------------------------

(defn- has-play-script?
  "True iff `vb` carries a non-empty `:play-script` (map `:script` or
  bare vector)."
  [vb]
  (let [script (:play-script vb)]
    (boolean
      (cond
        ;; Map form — {:auto-run? ... :script [...]}
        (map? script)    (seq (:script script))
        ;; Bare-vector form — legacy callers + the test fixtures pass
        ;; the script vector directly; the runner normalises both.
        (vector? script) (seq script)
        :else            false))))

(defn- has-plays?
  "True iff `vb` carries a non-empty `:plays` vector (rf2-tl7zk multi-play)."
  [vb]
  (let [plays (:plays vb)]
    (boolean (and (vector? plays) (seq plays)))))

(defn variant-has-tests?
  "True iff `variant-id`'s registered body declares a non-empty
  `:play-script` OR a non-empty `:plays` vector. Used by the pane to
  gate between the run-and-render path and the empty-state placeholder.

  Per rf2-0wrud (2026-05-20) `:play-script` is the canonical phase-4
  slot; rf2-tl7zk added the multi-play `:plays` slot. rf2-ee38b.3: this
  predicate now recognises BOTH (it previously checked only
  `:play-script`, so a `:plays`-only variant rendered the
  'no tests registered' empty-state even though the runner + CI runner
  see its runnable plays — matching `ci-runner/has-any-play?`).

  Pure data → data; JVM-testable."
  [variant-id]
  (let [vb (registrar/handler-meta :variant variant-id)]
    (or (has-play-script? vb)
        (has-plays? vb))))

;; ---- pure: aggregate-summary --------------------------------------------
;;
;; `aggregate-summary` lives in `re-frame.story.ui.state` (rf2-khmon) so
;; the sidebar / chrome-level test widget can call one canonical fold
;; without a require cycle. test-mode consumers call `state/aggregate-
;; summary` directly.

;; ---- pure: assertion-row ------------------------------------------------

(defn- pretty-payload
  "Render a `:payload` value for the assertion label. Strings show
  quoted; everything else uses `pr-str` so keywords / maps / vectors
  are visibly distinguishable."
  [v]
  (cond
    (nil? v)              ""
    (and (coll? v)
         (= 0 (count v))) ""
    :else                 (pr-str v)))

(defn assertion-row
  "Project one `:assertions` record into the row shape the per-test
  table renders. Each row carries:

      {:assertion :rf.assert/path-equals
       :status    :pass|:fail|:error|:cannot-run|:skip
       :label     \":rf.assert/path-equals [[:count] 7]\"
       :row-key   \":rf.assert/path-equals [[:count] 7]\"
       :detail    {:expected ... :actual ... :reason ...
                   :source <{:file ... :line ...}|nil>}}

  `:detail` is always present so the renderer can read uniformly;
  the renderer decides whether to surface it (only failing rows
  expand by default). Pure data → data; JVM-testable.

  rf2-ba86n.11 — the unified run-result (`re-frame.story.result`) stamps a
  `:status` on every assertion record (`:pass` / `:fail` / `:error` /
  `:cannot-run`). This helper now PREFERS that stamped `:status` (the
  unified shape, spec/017 §Run result) and only derives from `:passed?`
  for a record minted by a status-unaware path. The legacy
  `:passed?`-only read is the fallback, not the primary — completing the
  Test-mode migration off the split lifecycle/assertions reading
  (tools/story/spec/021 §1).

  `:row-key` is the stable identity the view uses to thread :expanded
  state across re-runs (rf2-tistm): keying on positional index opened
  the wrong row when a re-run reordered or inserted assertions. The
  label string is the densest stable id available — it carries the
  assertion id + payload shape together — and is JVM-testable so the
  pure helpers can pin the contract.

  Source-coord stamping arrives on the record as either `:source` or
  `:source-coord` depending on the assertion path that built it
  (per spec/004 + `re-frame.story.assertions`'s record builders).
  The row's `:detail :source` slot accepts either."
  [record]
  (let [rec      (or record {})
        st       (:status rec)
        passed?  (:passed? rec)
        aid      (:assertion rec)
        status   (cond
                   (= :rf.assert/skipped aid)          :skip
                   ;; rf2-ba86n.11 — the unified `:status` wins; the
                   ;; :passed?-only derivation is the legacy fallback.
                   (contains? statuses st)             st
                   (:cannot-run? rec)                  :cannot-run
                   (or (:error rec) (:exception rec))  :error
                   passed?                             :pass
                   ;; A record carrying neither a stamped :status nor a
                   ;; truthy :passed? reads :fail — the conservative
                   ;; view-side default (a missing pass signal can't be
                   ;; rendered green). Matches the legacy contract.
                   :else                               :fail)
        payload  (or (:payload rec) [])
        label    (let [p (pretty-payload payload)]
                   (cond-> (str aid)
                     (seq p) (str " " p)))]
    {:assertion aid
     :status    status
     :label     label
     :row-key   label
     :detail    {:expected (:expected rec)
                 :actual   (:actual rec)
                 :reason   (:reason rec)
                 :variant-id (:variant-id rec)
                 :phase    (:phase rec)
                 :event    (:event rec)
                 :predicate (:predicate rec)
                 :error    (:error rec)
                 :source   (or (:source rec) (:source-coord rec))}}))

;; ---- pure: formatting ---------------------------------------------------

(defn format-elapsed-ms
  "Render an elapsed-ms duration as a short human-readable string.
  Sub-second durations show `\"<n> ms\"`; one-second-plus durations
  show `\"<n.n> s\"`. Pure; JVM-testable.

  `nil` / non-number inputs return the empty string so the renderer
  can interpolate it safely."
  [ms]
  (cond
    (nil? ms)         ""
    (not (number? ms)) ""
    (< ms 0)          ""
    (< ms 1000)       (str (long ms) " ms")
    :else             (str #?(:clj  (format "%.1f" (double (/ ms 1000.0)))
                              :cljs (.toFixed (/ ms 1000) 1))
                           " s")))

(defn format-timestamp-ms
  "Render an epoch-ms timestamp as a short `HH:mm:ss` clock string for
  the last-run badge. Pure; JVM-testable.

  Inputs that don't look like a number return the empty string."
  [ms]
  (if (not (number? ms))
    ""
    #?(:clj
       (let [zone (java.time.ZoneId/systemDefault)
             inst (java.time.Instant/ofEpochMilli (long ms))
             ldt  (java.time.LocalDateTime/ofInstant inst zone)
             fmt  (java.time.format.DateTimeFormatter/ofPattern "HH:mm:ss")]
         (.format ldt fmt))
       :cljs
       (let [d (js/Date. ms)
             pad (fn [n] (if (< n 10) (str "0" n) (str n)))]
         (str (pad (.getHours d)) ":"
              (pad (.getMinutes d)) ":"
              (pad (.getSeconds d)))))))

;; ---- pure: play-step scrubber data (rf2-lc36w) --------------------------
;;
;; Each play event derived from the variant body's `:play-script` is
;; dispatched as a
;; single event — each dispatch produces exactly one epoch in the variant
;; frame's `epoch-history`. So the play sequence maps 1-to-1 onto a slice
;; of epochs. The scrubber row renders one tick per play event; clicking
;; a tick restores the variant frame's app-db to the epoch settled at
;; that step.
;;
;; The pure-data layer here computes:
;;
;;   - per-step `:status`  (:pass | :fail | :event | :skip) — the colour
;;     of the tick. Plain events get :event (a neutral tick — the step
;;     mutated state but recorded no assertion); :rf.assert/* events
;;     get :pass or :fail from the matching assertion record; the
;;     special :rf.assert/skipped id gets :skip.
;;
;;   - per-step `:label` — `(pr-str (first event))` for a compact tick
;;     title; tooltip text the CLJS renderer wires onto each tick.
;;
;;   - the trailing epoch-id slice — the last `count(play-events)` epoch-ids
;;     pulled from the variant frame's history. The CLJS side captures
;;     this on `run-variant` resolve so a later epoch (e.g. a Re-run on
;;     a different tab) can't drift the scrubber's mapping.
;;
;; All four helpers are pure data → data and JVM-testable.

(defn play-step-label
  "Render a compact label for one play step. `event` is the play-event
  vector (e.g. `[:auth/email-changed \"alice@example.com\"]`). Returns
  the stringified event-id (`:auth/email-changed`) — the renderer uses
  this for the tick's tooltip + the step's row label. Returns the empty
  string for nil / malformed events."
  [event]
  (cond
    (not (sequential? event)) ""
    (empty? event)            ""
    :else                     (pr-str (first event))))

(defn play-step-statuses
  "Pure: given a play-events vector (derived from `:play-script`) and an
  `:assertions` records vector,
  return a vector of step-status maps — one per play event.

  Each entry:

      {:index   <0-based play-event index>
       :event   <the play-event vector>
       :label   <stringified event-id>
       :status  :pass | :fail | :skip | :event}

  Rules:

    - non-assertion events (`assertion-event?` false) get `:event` —
      a neutral tick: the step mutated state but recorded nothing.
    - `:rf.assert/*` events consume one record off the `:assertions`
      vector in declared order. `:passed?` true ⇒ :pass; false ⇒
      :fail; `:rf.assert/skipped` ⇒ :skip.
    - if `:assertions` runs short (e.g. a phase-0 setup error bailed
      before play even started) the trailing assertion steps render
      as `:fail` so the user sees the gap.

  Pure data → data; JVM-testable."
  [play-events assertions]
  (let [records (vec (or assertions []))]
    (loop [out      []
           remain   play-events
           rec-idx  0
           step-idx 0]
      (if (empty? remain)
        out
        (let [ev         (first remain)
              assert?    (assertion-event? ev)
              record     (when assert? (nth records rec-idx nil))
              aid        (when record (:assertion record))
              passed?    (when record (:passed? record))
              status     (cond
                           (not assert?)                :event
                           (= :rf.assert/skipped aid)   :skip
                           passed?                      :pass
                           record                       :fail
                           ;; assertion event but no matching record —
                           ;; play bailed early; render as fail so the
                           ;; gap is visible.
                           :else                        :fail)
              row        {:index   step-idx
                          :event   ev
                          :label   (play-step-label ev)
                          :status  status}]
          (recur (conj out row)
                 (rest remain)
                 (if assert? (inc rec-idx) rec-idx)
                 (inc step-idx)))))))

(defn epoch-id-slice
  "Pure: given the variant frame's full `history` vector (oldest-first)
  + the count `n` of play events, return the trailing `n` `:epoch-id`s
  (in play-step order, oldest-first). Returns `[]` when the history
  has fewer than `n` records (production / ring-buffer-trimmed contexts
  — the scrubber gracefully degrades to no ticks rather than mis-mapping
  steps to wrong epochs).

  Pure data → data; JVM-testable."
  [history n]
  (let [hv (vec (or history []))]
    (cond
      (not (pos-int? n))    []
      (< (count hv) n)      []
      :else                 (mapv :epoch-id (subvec hv (- (count hv) n))))))

;; ===========================================================================
;; UNIFIED RUN-RESULT PROJECTION  (rf2-ba86n.11, tools/story/spec/021 §1)
;; ===========================================================================
;;
;; The `:test` pane consumes the ONE unified run-result `run-variant` /
;; `reset-variant` now return (the `re-frame.story.result/run-result`
;; shape merged with the legacy lifecycle slots — runtime.cljc
;; `record-result-map`). These helpers project that result into the
;; per-section render data the view renders, COMPLETING the migration off
;; the legacy split `:lifecycle` / `:assertions` reading (spec/021 §1
;; supersedes 009's result-reading contract):
;;
;;   - `run-status`         — the top-level verdict, including the distinct
;;                            `:cannot-run` THIRD state + a `:pending`
;;                            never-run / no-signal state.
;;   - `check-rows`         — `:checks` grouped by check id (spec/021 §1).
;;   - `schema-rows`        — consumed + unconsumed schema violations
;;                            (spec/021 §1 — "schema violations, including
;;                            consumed expected violations").
;;   - `cannot-run-rows`    — the `:cannot-run` refusal rows (required vs
;;                            available evidence/runner).
;;   - `filter-rows`        — the failed-only filter over assertion rows.
;;   - `evidence-available?`— whether the evidence spine can be linked yet
;;                            (graceful "evidence pending" until ba86n.10).
;;
;; All pure data → data; JVM-testable. Render-what-is-present: a slot the
;; substrate does not yet populate projects to an empty vector, NEVER a
;; fabricated row.

(defn run-status
  "The top-level run verdict for `result` (spec/021 §1; spec/018 §12.6 status
  vocabulary). Prefers the unified run-level `:status` so a tape-floor `:fail`
  / a `:cannot-run` refusal surfaces even when the assertion counts alone
  would read green. Falls back to a count-derived verdict ONLY when the
  result carries no `:status` (a host without the unified shape):

  - no result / nothing recorded  → `:pending`
  - explicit unified `:status`    → that verdict (`:pass` / `:fail` /
                                     `:error` / `:cannot-run`)
  - else (legacy host) derive from the assertion summary:
      zero assertions             → `:pending`  (ran, no signal)
      any fail/error              → `:fail`
      any cannot-run              → `:cannot-run`
      else                        → `:pass`

  `summary` is the `aggregate-summary` fold (passed/failed/cannot-run/total).
  Pure data → data; JVM-testable."
  [result summary]
  (let [st (:status result)]
    (cond
      (nil? result)             :pending
      (contains? statuses st)   st
      :else
      (let [{:keys [total failed cannot-run all-passed?]} (or summary {})]
        (cond
          (zero? (or total 0))     :pending
          (pos? (or failed 0))     :fail
          all-passed?              :pass
          (pos? (or cannot-run 0)) :cannot-run
          :else                    :fail)))))

(defn check-rows
  "Project the run-result's `:checks` slot (spec/017 §Checks, surfaced per
  spec/021 §1 — checks grouped by check id) into render rows:

      [{:check     <check-id>
        :status    :pass|:fail|:error|:cannot-run
        :passed    <n>   ; passing assertions in the group
        :failed    <n>   ; fail + error assertions in the group
        :total     <n>
        :rows      [<assertion-row> …]}]   ; the underlying records,
                                            ; so a failed check shows BOTH
                                            ; the check id AND its records
                                            ; (spec/017 §Run result).

  Empty when the plan declared no checks (the common case today — render an
  honest empty state, never a fabricated check). Pure data → data."
  [result]
  (mapv (fn [{:keys [check status assertions]}]
          (let [rows   (mapv assertion-row (or assertions []))
                buckets (frequencies (map :status rows))
                passed  (get buckets :pass 0)
                failed  (+ (get buckets :fail 0) (get buckets :error 0))]
            {:check  check
             :status status
             :passed passed
             :failed failed
             :total  (count rows)
             :rows   rows}))
        (or (:checks result) [])))

(defn schema-rows
  "Project the run-result's `:schema-violations` evidence slot (the SINGLE
  projected violation source, `re-frame.story.play.evidence/schema-
  violations`) into render rows, marking each as consumed or unconsumed
  (spec/021 §1 — \"schema violations, including consumed expected
  violations\"). Pure data → data.

  A violation is `:consumed?` true iff a declared `:rf.assert/schema-error`
  expectation exactly consumed THAT violation. Consumption is an EXACT
  MULTISET pairing (spec/017 §Schema rule step 4): N expectations of a
  selector consume exactly N of that selector's violations, so when M<N
  expectations match a selector with N violations, only M of the N read
  `:consumed?` (the remaining N−M are the agreement-floor failure cause).
  Marking by mere selector-SET membership would falsely mark ALL N consumed
  and so DISAGREE with the floor (rf2-5mrnwx) — the multiset count comes
  from the run's `:rf.assert/schema-error :pass` records, one per
  matched expectation, each carrying the consumed violation's selector as
  `:actual` (`re-frame.story.result/schema-error-record`).

  The caller-supplied `:consumed-selectors` escape hatch (selectors
  pre-excused outside the matcher, with NO `:pass` record) still excuses
  EVERY same-selector violation — set-keyed, matching the floor's treatment
  of that input. A legacy / partial result that predates the
  `:consumed-selectors` slot falls back to the same `:pass`-record
  derivation (multiset).

      [{:selector   <selector>
        :where      <surface>
        :failing-id <id>
        :consumed?  <bool>
        :reason     <str|nil>
        :epoch-id   <id|nil>}]

  An unconsumed violation reads as the agreement-floor failure cause; a
  consumed one reads as an expected, exactly-paired violation. Empty when
  the tape carried no schema violations. Pure data → data; JVM-testable."
  [result]
  (let [violations (or (:schema-violations result) [])
        ;; The multiset of selectors expectations EXACTLY consumed — one
        ;; `:rf.assert/schema-error :pass` record per matched expectation,
        ;; `:actual` = the consumed violation's selector
        ;; (`result/schema-error-record`). `frequencies` gives the per-selector
        ;; consumed COUNT so M<N consumption marks exactly M of N same-selector
        ;; violations (the agreement-floor multiset, not a set).
        consumed-freqs (frequencies
                         (into []
                               (comp (filter #(= :rf.assert/schema-error (:assertion %)))
                                     (filter #(= :pass (or (:status %)
                                                           (when (:passed? %) :pass))))
                                     (keep :actual))
                               (or (:assertions result) [])))
        ;; The caller-supplied escape-hatch selectors: in `:consumed-selectors`
        ;; but with NO matching `:pass` record (pre-excused outside the
        ;; matcher). These excuse EVERY same-selector violation (set-keyed,
        ;; mirroring the floor). When the slot is absent (legacy result) there
        ;; is no escape hatch — the `:pass`-record multiset is authoritative.
        escape-hatch   (into #{}
                             (remove consumed-freqs)
                             (when (contains? result :consumed-selectors)
                               (or (:consumed-selectors result) #{})))
        ;; Sequential multiset consumption: walk violations in tape order,
        ;; decrementing each selector's remaining consumed budget so exactly
        ;; the first N occurrences of a selector read `:consumed?`.
        step       (fn [{:keys [budget acc]} {:keys [selector] :as v}]
                     (let [remaining (get budget selector 0)
                           consumed? (or (pos? remaining)
                                         (contains? escape-hatch selector))]
                       {:budget (if (pos? remaining)
                                  (update budget selector dec)
                                  budget)
                        :acc    (conj acc
                                      {:selector   selector
                                       :where      (:where v)
                                       :failing-id (:failing-id v)
                                       :consumed?  consumed?
                                       :reason     (:reason v)
                                       :epoch-id   (:epoch-id v)})}))]
    (:acc (reduce step {:budget consumed-freqs :acc []} violations))))

(defn cannot-run-rows
  "Project the run-result's `:cannot-run` slot (the per-requirement refusal
  rows the chosen runner could not even attempt,
  `re-frame.story.requirements/requirement-refusal`) into render rows
  (spec/021 §1 — \"cannot-run rows with required and available evidence\"):

      [{:required  #{token …}   ; the evidence/runner the unit needed
        :available #{token …}   ; what the chosen runner provides
        :missing   #{token …}   ; the gap (required − available)
        :reason    <keyword>    ; :runner-lacks-capability | :no-runner-satisfies
        :runner    <kind|nil>   ; the runner that refused
        :unit      <step|assertion>}]

  A cannot-run row says what was REQUIRED vs AVAILABLE rather than reading
  as a failure (spec/018 §12.6 — the distinct THIRD status). Empty when the
  chosen runner satisfied every requirement. Pure data → data; JVM-testable."
  [result]
  (mapv (fn [{:keys [required-runner available-runner missing reason runner unit]}]
          {:required  (or required-runner #{})
           :available (or available-runner #{})
           :missing   (or missing #{})
           :reason    reason
           :runner    runner
           :unit      unit})
        (or (:cannot-run result) [])))

(defn filter-rows
  "Apply the failed-only filter to a vector of `assertion-row`s (spec/021 §1
  — \"failed-only filtering\"). When `failed-only?` is truthy, keep only the
  rows whose `:status` is `:fail` / `:error` / `:cannot-run` (the actionable
  rows); otherwise return `rows` unchanged. Pure data → data; JVM-testable."
  [rows failed-only?]
  (if failed-only?
    (filterv (fn [r] (#{:fail :error :cannot-run} (:status r))) rows)
    (vec rows)))

(defn evidence-available?
  "True iff `result` carries enough retained evidence for a result→evidence
  spine link (spec/021 §2). The evidence-spine DISPLAY (ba86n.10) is not
  built yet, so today this gates a graceful \"evidence pending\" affordance
  rather than a live link: it is true only when the result carries a
  non-empty `:epoch-tape` / `:narrative` (the retained tape the spine would
  project). Pure data → data; JVM-testable."
  [result]
  (boolean (or (seq (:epoch-tape result))
               (seq (:narrative result)))))

;; ===========================================================================
;; VISUAL + A11Y CHECK RESULTS  (rf2-ba86n.15, tools/story/spec/021 §4)
;; ===========================================================================
;;
;; The run path (post-#2484) evaluates the browser-tier oracle family —
;; `:rf.assert/a11y-structural` (the `:hiccup` structural-a11y check),
;; `:rf.assert/a11y` (the axe-style browser check), and
;; `:rf.assert/visual-snapshot` (the `:pixels` screenshot check) — through
;; `re-frame.story.play.browser/eval-browser-assertion`. Each rides the ONE
;; assertion-record shape (`re-frame.story.play.browser` — "a finding is an
;; assertion record like any other"); they accumulate alongside every other
;; assertion in the run-result's `:assertions` slot, with NO parallel slot.
;;
;; These helpers project those records into the per-section render data the
;; dedicated visual/a11y results component renders (spec/021 §4):
;;
;;   - the structural a11y VIOLATIONS as a readable {:finding :locus} list
;;     (not the raw `:actual` issue-map blob the generic assertions table
;;     would `pr-str`);
;;   - the axe-style a11y violations ({:id :impact :help}) likewise;
;;   - the visual-snapshot screenshot/identity presentation (the reused
;;     `content-hash` snapshot identity — a real pixel diff lands later with
;;     the `:pixels` runner);
;;   - the honest `:cannot-run` row for a browser-tier check the headless /
;;     hiccup runner could not even attempt (spec/017 §`:cannot-run` — never
;;     a false pass or silent blank).
;;
;; All pure data → data; JVM-testable. The browser-tier records are READ off
;; the unified `:assertions` slot — the SAME source Test mode + docs project
;; — so this is not a re-derivation of the run path's verdicts.

(defn- humanize-rule
  "Render a structural-a11y `:rule` keyword as a short readable finding
  phrase (`:img-missing-alt` → `\"image missing alt text\"`). Falls back to
  the kebab-spelling of an unknown rule so a future rule still reads. Pure."
  [rule]
  (case rule
    :img-missing-alt      "image missing alt text"
    :control-missing-name "interactive control has no accessible name"
    :positive-tabindex    "positive tabIndex breaks the natural focus order"
    (some-> rule name (str/replace "-" " "))))

(defn structural-a11y-findings
  "Project a `:rf.assert/a11y-structural` record's `:actual` issue vector
  (`re-frame.story.play.browser/structural-issues` — `{:rule :tag :detail}`
  maps) into a readable findings list (spec/021 §4 — \"render the structural
  a11y VIOLATIONS as a readable list … with the finding + locus\"):

      [{:finding <human phrase>   ; the issue, in words
        :locus   <\"<tag>\">      ; where in the rendered tree, e.g. \"<button>\"
        :detail  <str>            ; the executor's verbatim detail line
        :rule    <keyword>}]      ; the raw rule id, for keying / data-attr

  `:locus` is the offending element's hiccup tag (the structural check works
  over the rendered hiccup TREE, so the tag is the locus the `:hiccup` tier
  can prove). The structural tier has NO real source coordinate to thread
  (rf2-ffu8t): the `:hiccup-structure` runner walks an in-memory hiccup tree,
  not a DOM, so there is no CSS selector and no file/line coord to recover —
  the tree carries none. Honesty over fabrication: the §4 source-link MUST is
  met by the axe `:browser` tier (`axe-a11y-findings`, which recovers a real
  selector from the violation's `:nodes`); a structural finding surfaces its
  hiccup tag as the locus and offers NO selector slot, rather than minting a
  fake coordinate. Empty when the record carried no issues. Pure data → data;
  JVM-testable."
  [record]
  (mapv (fn [{:keys [rule tag detail]}]
          {:finding (humanize-rule rule)
           :locus   (when tag (str "<" (name tag) ">"))
           :detail  detail
           :rule    rule})
        (or (:actual record) [])))

(defn axe-a11y-findings
  "Project a `:rf.assert/a11y` record's `:actual` violation vector
  (`re-frame.story.play.browser/eval-a11y` — `{:id :impact :help :selector
  :targets}` maps, the axe-core surface) into a readable findings list
  (spec/021 §4 + §5 — a11y findings with the finding + locus + SOURCE LINK).

      [{:finding  <help text | rule id | \"a11y violation\">
        :locus    <CSS selector | rule id>   ; the source link, see below
        :selector <CSS selector | nil>        ; the offending element selector
        :targets  [<CSS selector> …]          ; every node selector (multi-node)
        :impact   <impact level | nil>
        :rule     <rule id>}]

  `:locus` is the SOURCE LINK the §4 MUST requires (\"a11y findings with
  selector/source/context links\"). The axe `:browser` tier carries a real
  selector (recovered from the violation's `:nodes` → `:target` by
  `browser/axe-finding`, rf2-ffu8t), so `:locus` is that selector — the
  result UI links it to the offending DOM element. When a violation carried
  no node target (rare — e.g. a page-level rule) `:locus` falls back to the
  rule id so the finding still reads. Empty when no violation. Pure data →
  data; JVM-testable."
  [record]
  (mapv (fn [{:keys [id impact help selector targets]}]
          {:finding  (or help (some-> id name) "a11y violation")
           :locus    (or selector (some-> id name))
           :selector selector
           :targets  (vec targets)
           :impact   impact
           :rule     id})
        (or (:actual record) [])))

(defn browser-result-rows
  "Project the run-result's browser-tier oracle records (visual / a11y /
  structural-a11y — `re-frame.story.assertions/browser-assertion-ids`, read
  off the unified `:assertions` slot) into the render rows the dedicated
  visual/a11y results component renders (spec/021 §4). One row per
  browser-tier record, in record order:

      {:assertion :rf.assert/a11y-structural
       :kind      :a11y-structural        ; :visual | :a11y | :a11y-structural
       :status    :pass|:fail|:cannot-run|:error
       :reason    <str>                   ; the executor's diagnostic line
       :count     <n|nil>                 ; finding count (a11y kinds)
       :findings  [{:finding :locus …} …] ; readable list (a11y kinds)
       :snapshot  <content-hash|nil>      ; visual identity (visual kind)
       :baseline  <content-hash|nil>      ; visual baseline (visual kind)}

  A `:cannot-run` row (a browser-tier check the headless / hiccup runner
  could not even attempt) carries its `:reason` so it reads as a refusal,
  never a false pass or a silent blank (spec/017 §`:cannot-run`). The status
  is the record's unified `:status` (the run path stamped it); the legacy
  `:passed?`-only read is the fallback. Empty when the run recorded no
  browser-tier checks (the common headless case — an honest empty state,
  never a fabricated row). Pure data → data; JVM-testable."
  [result]
  (let [browser? (fn [rec] (contains? assertions/browser-assertion-ids
                                      (:assertion rec)))
        kind-of  (fn [aid]
                   (condp = aid
                     assertions/id-visual-snapshot :visual
                     assertions/id-a11y            :a11y
                     assertions/id-a11y-structural :a11y-structural
                     :browser))
        status-of (fn [rec]
                    (let [st (:status rec)]
                      (cond
                        (contains? statuses st)            st
                        (:cannot-run? rec)                 :cannot-run
                        (or (:error rec) (:exception rec)) :error
                        (:passed? rec)                     :pass
                        :else                              :fail)))]
    (->> (or (:assertions result) [])
         (filterv browser?)
         (mapv (fn [rec]
                 (let [aid    (:assertion rec)
                       kind   (kind-of aid)
                       status (status-of rec)]
                   (cond-> {:assertion aid
                            :kind      kind
                            :status    status
                            :reason    (some-> (:reason rec) str)}
                     (= kind :a11y-structural)
                     (assoc :count    (:count rec)
                            :findings (if (= status :cannot-run)
                                        []
                                        (structural-a11y-findings rec)))

                     (= kind :a11y)
                     (assoc :count    (:count rec)
                            :findings (if (= status :cannot-run)
                                        []
                                        (axe-a11y-findings rec)))

                     (= kind :visual)
                     (assoc :snapshot (when (not= status :cannot-run)
                                        (:actual rec))
                            :baseline (when (and (not= status :cannot-run)
                                                 (not (keyword? (:expected rec))))
                                        (:expected rec))))))))))
