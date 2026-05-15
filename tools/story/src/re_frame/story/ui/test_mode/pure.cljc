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
  (:require [re-frame.story.predicates :as pred]
            [re-frame.story.registrar  :as registrar]))

;; ---- aliases on the leaf predicates ns ----------------------------------
;;
;; `assertion-event?` + `parent-story-id` both live canonically in
;; `re-frame.story.predicates` (a pure leaf ns the rest of Story consumes
;; without cycle risk). Aliased here so internal call sites stay textually
;; identical and external test fixtures keep their qualified shape.

(def ^:private assertion-event? pred/assertion-event?)
(def parent-story-id            pred/parent-story-id)

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
