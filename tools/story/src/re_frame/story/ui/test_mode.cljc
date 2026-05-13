(ns re-frame.story.ui.test-mode
  "The `:test` mode pane — in-canvas aggregated test-runner view.
  Per rf2-qmjo + spec/009.

  Replaces the `mode-tabs/tests-placeholder` stub that landed with the
  mode-tabs primitive (rf2-9hc8). The pane runs the variant's `:play`
  sequence via `run-variant`, reads the `:assertions` vector off the
  result, and renders an aggregated pass/fail summary inside the
  canvas:

      ┌──────────────────────────────────────────────────────┐
      │  Header — variant id · parent story · Re-run button   │
      │           · last-run timestamp + elapsed              │
      ├──────────────────────────────────────────────────────┤
      │  Summary badge — status pill (pass/fail/empty) +      │
      │           ✓ N passed · ✗ N failed · ⊘ N skipped       │
      ├──────────────────────────────────────────────────────┤
      │  Per-test table — one row per assertion record, in    │
      │           execution order; collapsible failure detail │
      │           surfaces :expected / :actual / :reason +    │
      │           the source-coord stamping                   │
      └──────────────────────────────────────────────────────┘

  When the variant body's `:play` slot is empty / absent the pane
  short-circuits and renders an empty-state placeholder pointing at
  the canonical testing-recipes leaf (Story doesn't auto-allocate a
  frame in this branch).

  Read-only — no input mutates args / overrides / modes. The Re-run
  button mutates **runtime state** (re-allocates the variant frame)
  but not the variant's authoring shape. Switching from `:test` back
  to `:dev` restores the canvas exactly as the user left it.

  ## Pure helpers (.cljc)

  The pane's data shaping lives in this namespace as pure data → data
  fns (`variant-has-tests?`, `aggregate-summary`, `assertion-row`,
  `format-elapsed-ms`, `format-timestamp-ms`) so the JVM test corpus
  can cover them without booting the runtime. The Reagent-side
  rendering + the `run-variant` promise wiring are CLJS-only.

  ## Elision

  CLJS-side rendering plus pure helpers in the `.cljc`. The shell's
  `main-pane` only reaches `test-view` via the
  `(when config/enabled? ...)`-gated mount call, so production builds
  never invoke it — closure DCEs the lot."
  (:require [re-frame.story.registrar :as registrar]
            #?@(:cljs [[reagent.core             :as r]
                       [re-frame.epoch           :as epoch]
                       [re-frame.story.runtime   :as runtime]
                       [re-frame.story.async     :as async]
                       [re-frame.interop         :as interop]
                       [re-frame.story.ui.open-in-editor :as open-in-editor]
                       [re-frame.story.ui.state  :as state]])))

;; ---- assertion-event? (mirrored from re-frame.story.assertions) ---------
;;
;; Mirrored here as a pure helper so the step-through scrubber's pure-data
;; layer doesn't have to pull `re-frame.story.assertions` (CLJS-leaning
;; coupling — it `:require`s `re-frame.core`). The shape is the canonical
;; one: `:rf.assert/*` events.

(defn- assertion-event?
  "True iff `event` is a `:rf.assert/*` form."
  [event]
  (let [id (when (sequential? event) (first event))]
    (and (keyword? id)
         (= "rf.assert" (namespace id)))))

;; ---- pure: parent-story-id ----------------------------------------------

(defn parent-story-id
  "Mirror of the helper in `re-frame.story.ui.docs/parent-story-id` so
  this namespace doesn't have to require the docs ns just for one
  data fn. Keep the surface area minimal."
  [variant-id]
  (when (and (keyword? variant-id) (namespace variant-id))
    (keyword (namespace variant-id))))

;; ---- pure: variant-has-tests? -------------------------------------------

(defn variant-has-tests?
  "True iff `variant-id`'s registered body declares a non-empty `:play`
  vector. Used by the pane to gate between the run-and-render path and
  the empty-state placeholder.

  Pure data → data; JVM-testable."
  [variant-id]
  (let [vb (registrar/handler-meta :variant variant-id)]
    (boolean (seq (:play vb)))))

;; ---- pure: aggregate-summary --------------------------------------------

(defn aggregate-summary
  "Walk `assertions` (the vector pulled off a `run-variant` result map)
  and produce the aggregated pass/fail/skip counts:

      {:total       <n>
       :passed      <n>
       :failed      <n>
       :skipped     <n>
       :all-passed? <bool>}

  `:skipped` counts records carrying `:assertion :rf.assert/skipped` —
  re-frame2's v1 runtime doesn't emit this id, but the slot stays open
  so spec/004 additions flow through without a pane refactor.
  `:all-passed?` is true iff `:total > 0 AND :failed = 0 AND :skipped = 0`."
  [assertions]
  (let [items     (or assertions [])
        skipped?  (fn [r] (= :rf.assert/skipped (:assertion r)))
        skipped   (count (filter skipped? items))
        active    (remove skipped? items)
        passed    (count (filter :passed? active))
        failed    (- (count active) passed)
        total     (count items)]
    {:total       total
     :passed      passed
     :failed      failed
     :skipped     skipped
     :all-passed? (and (pos? total) (zero? failed) (zero? skipped))}))

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
       :status    :pass|:fail|:skip
       :label     \":rf.assert/path-equals [[:count] 7]\"
       :detail    {:expected ... :actual ... :reason ...
                   :source <{:file ... :line ...}|nil>}}

  `:detail` is always present so the renderer can read uniformly;
  the renderer decides whether to surface it (only failing rows
  expand by default). Pure data → data; JVM-testable.

  Source-coord stamping arrives on the record as either `:source` or
  `:source-coord` depending on the assertion path that built it
  (per spec/004 + `re-frame.story.assertions`'s record builders).
  The row's `:detail :source` slot accepts either."
  [record]
  (let [rec      (or record {})
        passed?  (:passed? rec)
        aid      (:assertion rec)
        status   (cond
                   (= :rf.assert/skipped aid) :skip
                   passed?                    :pass
                   :else                      :fail)
        payload  (or (:payload rec) [])
        label    (let [p (pretty-payload payload)]
                   (cond-> (str aid)
                     (seq p) (str " " p)))]
    {:assertion aid
     :status    status
     :label     label
     :detail    {:expected (:expected rec)
                 :actual   (:actual rec)
                 :reason   (:reason rec)
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
;; Each entry in the variant body's `:play` vector is dispatched as a
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
;;   - the trailing epoch-id slice — the last `count(:play)` epoch-ids
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

(defn- assertion-records-by-index
  "Walk `assertions` and project a vector of `[assertion-event-index
  record]` pairs — useful for correlating the Nth `:rf.assert/*` event
  in `:play` with the Nth assertion record on the result map. The
  `:assertion-event-index` slot stamps the position of the matching
  play-event among `:rf.assert/*` events only.
  Internal pure-data helper."
  [assertions]
  (vec assertions))

(defn play-step-statuses
  "Pure: given a `:play` events vector and an `:assertions` records vector,
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
  + the count `n` of `:play` events, return the trailing `n` `:epoch-id`s
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

;; ---- CLJS-side rendering -------------------------------------------------

#?(:cljs
   (def ^:private styles
     {:wrap          {:flex             "1"
                      :overflow         "auto"
                      :padding          "20px 28px"
                      :background       "#1e1e1e"
                      :color            "#cccccc"
                      :font-family      "system-ui, sans-serif"
                      :font-size        "13px"
                      :line-height      "1.5"}
      :h1            {:font-family      "monospace"
                      :font-size        "18px"
                      :font-weight      "bold"
                      :color            "white"
                      :margin           "0 0 4px 0"}
      :sub           {:color            "#b0b0b0"
                      :font-family      "monospace"
                      :font-size        "11px"
                      :margin-bottom    "10px"}
      :header-row    {:display          "flex"
                      :justify-content  "space-between"
                      :align-items      "center"
                      :gap              "12px"
                      :margin           "12px 0 8px 0"}
      :rerun-btn     {:padding          "6px 14px"
                      :background       "#0e639c"
                      :color            "white"
                      :border           "none"
                      :border-radius    "3px"
                      :cursor           "pointer"
                      :font-family      "monospace"
                      :font-size        "11px"
                      :letter-spacing   "0.3px"}
      :rerun-running {:background       "#37373d"
                      :color            "#b0b0b0"
                      :cursor           "not-allowed"}
      :last-run      {:color            "#b0b0b0"
                      :font-family      "monospace"
                      :font-size        "10px"}
      :section       {:margin-top       "16px"}
      :section-h     {:font-weight      "bold"
                      :color            "#b0b0b0"
                      :text-transform   "uppercase"
                      :font-size        "10px"
                      :letter-spacing   "0.5px"
                      :margin-bottom    "8px"
                      :border-bottom    "1px solid #444"
                      :padding-bottom   "4px"}
      :pill-row      {:display          "flex"
                      :align-items      "center"
                      :gap              "12px"
                      :margin-bottom    "8px"}
      :pill          {:padding          "4px 10px"
                      :border-radius    "10px"
                      :font-family      "monospace"
                      :font-size        "11px"
                      :font-weight      "bold"
                      :text-transform   "uppercase"
                      :letter-spacing   "0.5px"}
      :pill-pass     {:background       "#1f4d3f"
                      :color            "#4ec9b0"}
      :pill-fail     {:background       "#4d1f1f"
                      :color            "#f48771"}
      :pill-empty    {:background       "#37373d"
                      :color            "#b0b0b0"}
      :counts        {:color            "#b0b0b0"
                      :font-family      "monospace"
                      :font-size        "11px"}
      :count-pass    {:color            "#4ec9b0"}
      :count-fail    {:color            "#f48771"}
      :count-skip    {:color            "#9a9a9a"}
      :table         {:width            "100%"
                      :border-collapse  "collapse"
                      :font-family      "monospace"
                      :font-size        "11px"}
      :th            {:text-align       "left"
                      :padding          "6px 8px"
                      :background       "#2d2d30"
                      :color            "#b0b0b0"
                      :border-bottom    "1px solid #444"
                      :text-transform   "uppercase"
                      :font-size        "10px"
                      :letter-spacing   "0.5px"}
      :td            {:padding          "6px 8px"
                      :border-bottom    "1px solid #2d2d30"
                      :color            "#dcdcdc"
                      :vertical-align   "top"}
      :td-status     {:width            "20px"
                      :text-align       "center"
                      :font-size        "16px"
                      :line-height      "1"}
      :status-pass   {:color            "#4ec9b0"}
      :status-fail   {:color            "#f48771"}
      :status-skip   {:color            "#9a9a9a"}
      :row-fail      {:background       "#2a1a1a"}
      :details-tog   {:cursor           "pointer"
                      :color            "#9cdcfe"
                      :background       "none"
                      :border           "none"
                      :font-family      "monospace"
                      :font-size        "11px"
                      :padding          "0"
                      :text-decoration  "underline"}
      :detail-box    {:background       "#252526"
                      :border-left      "3px solid #f48771"
                      :padding          "8px 12px"
                      :margin-top       "6px"
                      :color            "#dcdcdc"
                      :font-family      "monospace"
                      :font-size        "11px"
                      :white-space      "pre-wrap"}
      :detail-key    {:color            "#9cdcfe"}
      :detail-source {:color            "#b0b0b0"
                      :font-size        "10px"
                      :margin-top       "4px"}
      :empty         {:padding          "32px"
                      :color            "#9a9a9a"
                      :font-style       "italic"
                      :font-family      "system-ui, sans-serif"
                      :text-align       "center"
                      :background       "#1e1e1e"
                      :flex             "1"}
      :empty-link    {:color            "#9cdcfe"
                      :font-family      "monospace"
                      :margin-top       "8px"
                      :display          "block"}

      ;; ---- step-through scrubber (rf2-lc36w) -------------------------
      :scrub-wrap    {:margin           "8px 0 0 0"
                      :padding          "8px 10px"
                      :background       "#252526"
                      :border           "1px solid #3a3a3a"
                      :border-radius    "4px"}
      :scrub-h       {:font-weight      "bold"
                      :color            "#b0b0b0"
                      :text-transform   "uppercase"
                      :font-size        "10px"
                      :letter-spacing   "0.5px"
                      :margin-bottom    "6px"
                      :display          "flex"
                      :justify-content  "space-between"
                      :align-items      "center"}
      :scrub-ticks   {:display          "flex"
                      :gap              "3px"
                      :align-items      "center"
                      :flex-wrap        "wrap"
                      :margin-bottom    "6px"}
      :scrub-tick    {:display          "inline-block"
                      :min-width        "14px"
                      :height           "14px"
                      :line-height      "14px"
                      :text-align       "center"
                      :border-radius    "3px"
                      :cursor           "pointer"
                      :font-family      "monospace"
                      :font-size        "10px"
                      :padding          "0 4px"
                      :user-select      "none"}
      :tick-pass     {:background       "#1f4d3f"
                      :color            "#4ec9b0"}
      :tick-fail     {:background       "#4d1f1f"
                      :color            "#f48771"}
      :tick-event    {:background       "#37373d"
                      :color            "#b0b0b0"}
      :tick-skip     {:background       "#2d2d30"
                      :color            "#9a9a9a"}
      :tick-selected {:outline          "2px solid #9cdcfe"
                      :outline-offset   "1px"}
      :scrub-slider  {:width            "100%"
                      :margin           "4px 0"}
      :scrub-detail  {:color            "#9a9a9a"
                      :font-family      "monospace"
                      :font-size        "10px"
                      :margin-top       "4px"
                      :display          "flex"
                      :gap              "10px"
                      :flex-wrap        "wrap"}
      :scrub-release {:padding          "2px 8px"
                      :background       "#5a5a5a"
                      :color            "white"
                      :border           "none"
                      :border-radius    "3px"
                      :cursor           "pointer"
                      :font-size        "10px"
                      :font-family      "monospace"}}))

;; ---- CLJS local state ----------------------------------------------------
;;
;; The pane keeps its own ratom — one map keyed by variant-id. Each
;; entry carries `{:result <run-variant-result-map>
;;                 :ran-at-ms <epoch-ms>
;;                 :running? <bool>
;;                 :expanded #{<row-index>}
;;                 :play-events <vector>     ; the :play body from the variant
;;                 :epoch-ids   <vector>     ; trailing epoch-id slice
;;                 :selected-step <int|nil>}`. Re-run flips :running?
;; on, calls `runtime/reset-variant`, swaps the result in on resolve.
;;
;; `:play-events`, `:epoch-ids` + `:selected-step` are the step-through
;; scrubber slots (rf2-lc36w). `:selected-step` is the slider position
;; (a slot index into `:epoch-ids`); nil means 'no scrub in flight'
;; — the canvas shows the post-play app-db, the same value the user
;; sees on a fresh run. A non-nil selection has called restore-epoch
;; against the variant frame, so the canvas re-renders against the
;; app-db value at that step.

#?(:cljs
   (defonce ^:private results-atom (r/atom {})))

#?(:cljs
   (defn- begin-run!
     "Mark the variant's slot as running. Returns nothing. Stamps the
     shell-state `:test-runs` slot too so the chrome-level test widget
     and the sidebar's per-variant dot read `:running` while the run
     is in flight (rf2-q0irb)."
     [variant-id]
     (swap! results-atom assoc-in [variant-id :running?] true)
     (state/swap-state! state/mark-test-running variant-id)))

#?(:cljs
   (defn- store-result!
     "Swap a fresh `result` (from `run-variant`) into the variant's
     slot, clear `:running?`, stamp `:ran-at-ms` with the local clock,
     and reset the per-row expanded set so a fresh failure detail
     starts collapsed.

     Captures the play-events vector + the trailing epoch-id slice
     against the same atom so the step-through scrubber (rf2-lc36w)
     has a stable read-surface that doesn't drift on a later
     unrelated dispatch.

     Folds the run's aggregate into the shell-state `:test-runs` slot
     too — the chrome-level test widget + sidebar dots read off that
     slot (rf2-q0irb)."
     [variant-id result]
     (let [now          (interop/now-ms)
           play-events  (or (:play (registrar/handler-meta :variant variant-id))
                            [])
           history      (epoch/epoch-history variant-id)
           epoch-ids    (epoch-id-slice history (count play-events))]
       (swap! results-atom assoc variant-id
              {:result        result
               :ran-at-ms     now
               :running?      false
               :expanded      #{}
               :play-events   (vec play-events)
               :epoch-ids     epoch-ids
               :selected-step nil})
       (let [summary (-> (aggregate-summary (:assertions result))
                         (assoc :ran-at-ms  now
                                :elapsed-ms (:elapsed-ms result)))]
         (state/swap-state! state/record-test-run variant-id summary)))))

#?(:cljs
   (defn- select-step!
     "Set the step-through scrubber's `:selected-step` for `variant-id`
     and call `restore-epoch` against the variant frame so the canvas
     re-renders against the app-db at that step.

     `idx` is a 0-based index into the variant's `:epoch-ids` vector.
     Pass nil to release — the canvas reverts to the post-play app-db
     (we restore against the last epoch-id in the slice, which is the
     play-sequence's terminal state)."
     [variant-id idx]
     (let [slot       (get @results-atom variant-id)
           epoch-ids  (or (:epoch-ids slot) [])
           target-id  (cond
                        (and (integer? idx)
                             (<= 0 idx)
                             (< idx (count epoch-ids)))
                        (nth epoch-ids idx)

                        ;; release → restore terminal state
                        (and (nil? idx) (seq epoch-ids))
                        (peek epoch-ids)

                        :else nil)]
       (swap! results-atom assoc-in [variant-id :selected-step] idx)
       (when target-id
         (epoch/restore-epoch variant-id target-id)))))

#?(:cljs
   (defn- toggle-expanded!
     [variant-id idx]
     (swap! results-atom update-in [variant-id :expanded]
            (fn [s] (let [s (or s #{})]
                      (if (contains? s idx)
                        (disj s idx)
                        (conj s idx)))))))

#?(:cljs
   (defn- run-variant-pane!
     "Drive a fresh `reset-variant` against the variant's frame and
     swap the result into local state when it resolves. No-ops if
     the slot already carries `:running?`."
     [variant-id]
     (let [shell @state/shell-state-atom
           opts  {:active-modes   (:active-modes shell)
                  :cell-overrides nil
                  :substrate      (:substrate shell)}]
       (begin-run! variant-id)
       (-> (runtime/reset-variant variant-id opts)
           (async/then  (fn [r] (store-result! variant-id r) nil))
           (async/catch* (fn [_]
                           ;; Even a rejection clears :running? so the
                           ;; UI button comes back to "Re-run". Drop
                           ;; the shell-state running stamp too — the
                           ;; widget/dot should not stay yellow on a
                           ;; rejection (rf2-q0irb).
                           (swap! results-atom assoc-in
                                  [variant-id :running?] false)
                           (state/swap-state! state/clear-test-run variant-id)
                           nil))))))

;; ---- CLJS-side: section renderers ---------------------------------------

#?(:cljs
   (defn- header
     "Variant id + parent story id + Re-run button + last-run badge."
     [variant-id]
     (let [slot       (get @results-atom variant-id)
           running?   (:running? slot)
           ran-at-ms  (:ran-at-ms slot)
           result     (:result slot)
           elapsed    (:elapsed-ms result)
           story-id   (parent-story-id variant-id)]
       [:div
        [:h1 {:style (:h1 styles)} (str variant-id)]
        [:div {:style     (:sub styles)
               :data-test "story-test-parent-story"}
         (if story-id
           (str "parent story: " story-id)
           "no parent story registered")]
        [:div {:style (:header-row styles)}
         [:button
          {:style          (merge (:rerun-btn styles)
                                  (when running? (:rerun-running styles)))
           :data-test      "story-test-rerun"
           :disabled       running?
           :aria-label     "Re-run the variant's :play sequence"
           :on-click       (fn [_] (when-not running? (run-variant-pane! variant-id)))}
          (if running? "Running…" "Re-run")]
         [:div {:style (:last-run styles)}
          (when ran-at-ms
            [:span {:data-test "story-test-timestamp"}
             (str "ran " (format-timestamp-ms ran-at-ms))])
          (when (and ran-at-ms elapsed)
            [:span " · "])
          (when (number? elapsed)
            [:span {:data-test "story-test-elapsed"}
             (format-elapsed-ms elapsed)])]]])))

#?(:cljs
   (defn- summary-section
     "Status pill + ✓ / ✗ / ⊘ counts. Renders nothing until at least
     one run has executed; the renderer composes its own placeholder
     in that interim state."
     [variant-id]
     (let [slot   (get @results-atom variant-id)
           result (:result slot)]
       (when result
         (let [summary (aggregate-summary (:assertions result))
               {:keys [total passed failed skipped all-passed?]} summary
               pill-style (cond
                            (zero? total)  (:pill-empty styles)
                            all-passed?    (:pill-pass styles)
                            :else          (:pill-fail styles))
               pill-text  (cond
                            (zero? total) "no assertions recorded"
                            all-passed?   (str passed " passed")
                            :else         (str failed " failed of " total))]
           [:div {:style     (:section styles)
                  :data-test "story-test-summary-section"}
            [:div {:style (:section-h styles)} "Summary"]
            [:div {:style (:pill-row styles)}
             [:span {:style     (merge (:pill styles) pill-style)
                     :data-test "story-test-status-pill"}
              pill-text]
             [:span {:style     (:counts styles)
                     :data-test "story-test-counts"}
              [:span {:style (:count-pass styles)} (str "✓ " passed " passed")]
              "  "
              [:span {:style (:count-fail styles)} (str "✗ " failed " failed")]
              "  "
              [:span {:style (:count-skip styles)} (str "⊘ " skipped " skipped")]]]])))))

#?(:cljs
   (defn- tick-style-for
     "Pick the style map for a single tick based on `status`."
     [status]
     (case status
       :pass  (:tick-pass styles)
       :fail  (:tick-fail styles)
       :skip  (:tick-skip styles)
       :event (:tick-event styles)
       (:tick-event styles))))

#?(:cljs
   (defn- step-glyph
     "Compact glyph for a tick: ✓ / ✗ / ⊘ for assertion outcomes, · for
     plain events. Pure-data; doesn't reach into ratoms."
     [status]
     (case status
       :pass  "✓"
       :fail  "✗"
       :skip  "⊘"
       :event "·"
       "·")))

#?(:cljs
   (defn- scrubber-section
     "Step-through scrubber (rf2-lc36w). One tick per `:play` event with
     pass/fail/event/skip status colouring. Click a tick (or drag the
     slider below) to restore the variant's app-db to that step's
     epoch — the canvas re-renders against it.

     Renders nothing until a run has captured an epoch slice. When
     `:play` ran but no epochs were captured (production elision, or
     the ring buffer was disabled), the section short-circuits to a
     muted hint rather than a broken scrubber."
     [variant-id]
     (let [slot          (get @results-atom variant-id)
           result        (:result slot)
           play-events   (or (:play-events slot) [])
           epoch-ids     (or (:epoch-ids slot) [])
           selected-step (:selected-step slot)
           n             (count play-events)]
       (when (and result (pos? n))
         (let [statuses (play-step-statuses play-events (:assertions result))
               has-epochs? (= n (count epoch-ids))]
           [:div {:style     (merge (:section styles) (:scrub-wrap styles))
                  :data-test "story-test-scrubber-section"}
            [:div {:style (:scrub-h styles)}
             [:span "Step-through"]
             [:span {:style {:color "#9a9a9a" :font-weight "normal"}}
              (cond
                (not has-epochs?)        (str n " steps · no epoch buffer")
                (some? selected-step)    (str "step " (inc selected-step) "/" n)
                :else                    (str n " steps"))]]
            (if-not has-epochs?
              [:div {:style     {:color       "#9a9a9a"
                                 :font-style  "italic"
                                 :font-size   "11px"
                                 :font-family "monospace"}
                     :data-test "story-test-scrubber-no-epochs"}
               "epoch buffer empty — scrubber unavailable (run is non-elided?)"]
              [:div
               [:div {:style     (:scrub-ticks styles)
                      :data-test "story-test-scrubber-ticks"}
                (for [{:keys [index status label]} statuses]
                  ^{:key index}
                  [:span {:style       (merge (:scrub-tick styles)
                                              (tick-style-for status)
                                              (when (= index selected-step)
                                                (:tick-selected styles)))
                          :data-test   "story-test-scrubber-tick"
                          :data-index  (str index)
                          :data-status (name status)
                          :title       (str "step " (inc index) " · " label)
                          :on-click    (fn [_] (select-step! variant-id index))}
                   (step-glyph status)])]
               [:input {:type        "range"
                        :min         0
                        :max         (max 0 (dec n))
                        :value       (or selected-step (dec n))
                        :style       (:scrub-slider styles)
                        :data-test   "story-test-scrubber-slider"
                        :aria-label  "Step through play sequence"
                        :on-change   (fn [e]
                                       (let [idx (js/parseInt
                                                   (.. e -target -value))]
                                         (select-step! variant-id idx)))}]
               [:div {:style (:scrub-detail styles)}
                (when (some? selected-step)
                  (let [step (nth statuses selected-step nil)]
                    [:span {:data-test "story-test-scrubber-selected-label"}
                     (str "→ " (:label step))]))
                (when (some? selected-step)
                  [:button {:style     (:scrub-release styles)
                            :data-test "story-test-scrubber-release"
                            :on-click  (fn [_] (select-step! variant-id nil))}
                   "release"])]])])))))

#?(:cljs
   (defn- row-detail
     "The collapsible failure detail — surfaces :expected, :actual,
     :reason, and the source-coord stamping."
     [{:keys [expected actual reason source]}]
     [:div {:style     (:detail-box styles)
            :data-test "story-test-row-detail"}
      (when (some? reason)
        [:div
         [:span {:style (:detail-key styles)} "reason: "]
         (str reason)])
      (when (or (some? expected) (contains? #{0 false} expected))
        [:div
         [:span {:style (:detail-key styles)} "expected: "]
         (pr-str expected)])
      (when (or (some? actual) (contains? #{0 false} actual))
        [:div
         [:span {:style (:detail-key styles)} "actual:   "]
         (pr-str actual)])
      (when (and (map? source) (or (:file source) (:line source)))
        [:div {:style (:detail-source styles)}
         (str "at " (:file source)
              (when (:line source) (str ":" (:line source))))
         ;; rf2-evgf5: per-assertion 'Open in editor' chip — surfaces the
         ;; assertion's source-coord (the play-step site, captured by the
         ;; assertion's record builder per spec/004).
         (open-in-editor/open-chip source :test-detail)])]))

#?(:cljs
   (defn- status-glyph
     [status]
     (case status
       :pass [:span {:style (:status-pass styles)} "●"]
       :fail [:span {:style (:status-fail styles)} "●"]
       :skip [:span {:style (:status-skip styles)} "○"]
       [:span {:style (:status-skip styles)} "○"])))

#?(:cljs
   (defn- rows-section
     "Per-test rows. Empty when no run has executed; renders nothing
     when the run recorded zero assertions (the summary pill already
     told the user)."
     [variant-id]
     (let [slot     (get @results-atom variant-id)
           result   (:result slot)
           expanded (or (:expanded slot) #{})]
       (when result
         (let [rows (mapv assertion-row (:assertions result))]
           (when (seq rows)
             [:div {:style     (:section styles)
                    :data-test "story-test-rows-section"}
              [:div {:style (:section-h styles)} "Per-test"]
              [:table {:style     (:table styles)
                       :data-test "story-test-table"}
               [:thead
                [:tr
                 [:th {:style (merge (:th styles) (:td-status styles))} ""]
                 [:th {:style (:th styles)} "assertion"]
                 [:th {:style (:th styles)} "detail"]]]
               [:tbody
                (for [[i row] (map-indexed vector rows)]
                  (let [open? (contains? expanded i)
                        fail? (= :fail (:status row))]
                    ^{:key i}
                    [:tr {:data-test     "story-test-row"
                          :data-status   (name (:status row))
                          :data-assertion (str (:assertion row))
                          :style         (when fail? (:row-fail styles))}
                     [:td {:style (merge (:td styles) (:td-status styles))}
                      (status-glyph (:status row))]
                     [:td {:style (:td styles)}
                      (:label row)]
                     [:td {:style (:td styles)}
                      (cond
                        fail?
                        [:div
                         [:button
                          {:style    (:details-tog styles)
                           :on-click (fn [_] (toggle-expanded! variant-id i))
                           :aria-expanded (if open? "true" "false")}
                          (if open? "hide detail" "show detail")]
                         (when open? (row-detail (:detail row)))]

                        (= :skip (:status row))
                        [:span {:style (:status-skip styles)} "skipped"]

                        :else
                        "")]]))]]])))))) ;; rows-section closes here

#?(:cljs
   (defn- empty-state
     "Placeholder when the variant has no `:play` slot."
     [variant-id]
     [:div {:style     (:empty styles)
            :data-test "story-test-empty"}
      [:div {:style {:font-weight "bold" :margin-bottom "8px"
                     :color "#cccccc"}}
       "No tests registered for this variant"]
      [:div
       "Add a "
       [:code ":play"]
       " slot to "
       [:code (str variant-id)]
       " to register assertions."]
      [:a {:href       "https://github.com/day8/re-frame2/blob/main/skills/re-frame2/reference/cross-cutting/testing.md"
           :style      (:empty-link styles)
           :data-test  "story-test-empty-link"
           :target     "_blank"
           :rel        "noopener noreferrer"}
       "skills/re-frame2/reference/cross-cutting/testing.md"]]))

;; ---- top-level component -------------------------------------------------

#?(:cljs
   (defn test-view
     "Top-level `:test` mode pane for `variant-id`.

     On first mount for a variant the pane auto-runs the variant once
     so the user lands on the result; subsequent visits to the tab
     show the most recent result until the Re-run button is clicked.

     Returns nil when given no variant id (the shell already gates
     this, but the helper guards itself too).

     Per spec/009 the pane is read-only — no inputs mutate args /
     overrides / modes. The Re-run button mutates **runtime state**
     (re-allocates the variant frame via `reset-variant`) but not
     the variant's authoring shape."
     [variant-id]
     (when variant-id
       (r/create-class
         {:display-name "rf-story-test-view"
          :component-did-mount
          (fn [_this]
            ;; Auto-run on first mount per variant. If a slot already
            ;; exists (returning to the tab without re-mount) we don't
            ;; re-fire — the user clicks Re-run to refresh.
            (when (variant-has-tests? variant-id)
              (when-not (get @results-atom variant-id)
                (run-variant-pane! variant-id))))
          :reagent-render
          (fn [variant-id]
            (cond
              (not (variant-has-tests? variant-id))
              [:section {:style     (:wrap styles)
                         :data-test "story-test-view"
                         :aria-label "Variant tests"}
               [empty-state variant-id]]

              :else
              [:section {:style     (:wrap styles)
                         :data-test "story-test-view"
                         :aria-label "Variant tests"}
               [header variant-id]
               [summary-section variant-id]
               [scrubber-section variant-id]
               [rows-section variant-id]]))}))))
