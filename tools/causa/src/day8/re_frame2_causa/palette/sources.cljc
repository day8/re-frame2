(ns day8.re-frame2-causa.palette.sources
  "Pure-data palette source aggregator for the Causa command palette
  (rf2-wm7z4).

  Builds the indexed surface the fuzzy scorer ranks against. The fn
  takes a single `inputs` map — the caller (sub layer in
  `palette/subs.cljs`) gathers the live data from app-db / framework
  queries and passes it in. Keeping the aggregator pure means the
  JVM-runnable spec covers source weighting + dedup without driving a
  browser.

  ## Item shape

  Each item is a map:

    {:source        keyword     ; :panel | :recent-event | :frame
                                ; | :handler | :setting | :command
                                ; | :copilot-question
     :id            anything    ; stable identifier within the source
     :label         string      ; what fuzzy matches against + renders
     :hint          string?     ; right-aligned hint (epoch N, file:line,
                                ; shortcut, …)
     :icon          string      ; 1-glyph type icon
     :recency-rank  int?        ; 0 = most recent; absent = no recency
     :boost         long        ; static weight added to fuzzy score
     :action        vector      ; re-frame dispatch vector when invoked
                                ; — wrapped by events.cljs so it can
                                ; cross-frame back to `:rf/causa` for
                                ; panel selection vs `:rf/default` for
                                ; host re-dispatch.
     :popout?       boolean?    ; when true, Ctrl+Enter pop-out semantics
                                ; route the action via popout!
                                ; Item may opt out (settings, palette-
                                ; only commands) — see `popoutable?`.
    }

  ## Scoring

  Final score = fuzzy-score + boost + recency-bonus. Recency adds at
  most +30 (for `recency-rank = 0`), decaying linearly to 0 at
  `recency-rank ≥ 30`. Boost defaults to 0; sources with a stable
  hand-tuned weight (commands > panels > recent-events > frames >
  handlers) set their own.

  ## Dedup

  When two items share the same `[:source :id]` pair, the first wins
  (the aggregator orders sources by importance). Within a source, the
  caller is responsible for upstream dedup."
  (:require [clojure.string :as str]
            [day8.re-frame2-causa.palette.fuzzy :as fuzzy]))

;; ---- per-source boosts ---------------------------------------------------
;;
;; Hand-tuned weights so command verbs rank above panel jumps when
;; both fuzzy-match the same query (e.g. typing "cle" yields the
;; "Clear trace buffer" command above any incidentally-matching panel
;; whose label contains those letters). Recent events sit below
;; panels in static weight but typically dominate via their recency
;; bonus.

(def boost-table
  {:command          40
   :panel            30
   :setting          20
   :recent-event     10
   :frame            5
   :handler          2
   :copilot-question 8})

(def ^:private icon-table
  {:command          "▸"
   :panel            "◧"
   :setting          "⚙"
   :recent-event     "⟳"
   :frame            "◆"
   :handler          "ƒ"
   :copilot-question "?"})

;; ---- source builders -----------------------------------------------------

(defn panel-items
  "One row per sidebar panel. The action dispatches into `:rf/causa`
  via the wrapping events fn (palette events resolve `[:panel id]`
  into `[:rf.causa/select-panel id]`)."
  [panel-entries]
  (mapv
    (fn [{:keys [id label]}]
      {:source :panel
       :id     id
       :label  (str "Open " label " panel")
       :hint   (str "panel/" (name id))
       :icon   (icon-table :panel)
       :boost  (boost-table :panel)
       :action [:palette/select-panel id]
       :popout? false})
    panel-entries))

(defn recent-event-items
  "Indexed from Causa's `:trace-buffer` snapshot. Only `:event/handled`
  + `:event/dispatched` rows are surfaced — the buffer carries every
  trace kind but a recent-events palette source means recent
  dispatches, not every internal trace event.

  Recency rank rises with index from the end (the most-recently-
  pushed event is rank 0). Caps at 30 rows by default — beyond that
  the recency bonus is zero anyway, and the palette result list
  itself is finite."
  ([buffer]
   (recent-event-items buffer 30))
  ([buffer max-rows]
   (let [event-rows (->> buffer
                         (filter (fn [t]
                                   (contains?
                                     #{:event/handled :event/dispatched}
                                     (:op t))))
                         vec)
         total      (count event-rows)
         taken      (cond-> event-rows
                      (pos? total)
                      (subvec (max 0 (- total max-rows))))
         taken-cnt  (count taken)]
     (vec
       (map-indexed
         (fn [i t]
           ;; Last row of `taken` is the most recent — recency-rank 0.
           (let [rank   (- (dec taken-cnt) i)
                 ev-id  (or (:event-id t)
                            (get-in t [:tags :event])
                            (:id t))
                 label  (str (pr-str ev-id))]
             {:source       :recent-event
              :id           (or (:id t) [ev-id i])
              :label        label
              :hint         (str "recent · "
                                 (if (zero? rank) "latest"
                                     (str (inc rank) " ago")))
              :icon         (icon-table :recent-event)
              :recency-rank rank
              :boost        (boost-table :recent-event)
              :action       [:palette/select-event ev-id]
              :popout?      true}))
         taken)))))

(defn frame-items
  "Indexed from `frame-ids` (a seq of registered frame ids). The
  Causa frame itself is excluded — switching focus to `:rf/causa`
  via the palette is not a meaningful user operation."
  [frame-ids]
  (->> frame-ids
       (remove #(= :rf/causa %))
       (mapv (fn [fid]
               {:source :frame
                :id     fid
                :label  (str "Switch focus to frame " (pr-str fid))
                :hint   (str "frame/" (name fid))
                :icon   (icon-table :frame)
                :boost  (boost-table :frame)
                :action [:palette/select-frame fid]
                :popout? false}))))

(defn handler-items
  "Indexed from a `[{:id k :kind k :doc s :file s :line n} ...]`
  seq. The caller assembles this seq from `re-frame.registrar/
  registrations` calls; the aggregator stays substrate-agnostic.
  Caps at 200 handlers by default — beyond that the fuzzy pass
  starts to dominate sub recomputation cost."
  ([handler-entries]
   (handler-items handler-entries 200))
  ([handler-entries max-rows]
   (->> handler-entries
        (take max-rows)
        (mapv (fn [{:keys [id kind doc file line]}]
                {:source :handler
                 :id     [kind id]
                 :label  (str (name kind) " " (pr-str id))
                 :hint   (cond
                           (and file line) (str file ":" line)
                           doc             (str/replace
                                             (subs doc 0 (min 60 (count doc)))
                                             #"\s+" " ")
                           :else           (name kind))
                 :icon   (icon-table :handler)
                 :boost  (boost-table :handler)
                 :action [:palette/inspect-handler kind id]
                 :popout? true})))))

(defn setting-items
  "Static settings the palette can flip / open. Phase 1 ships the co-
  pilot parity entries (toggle rail, mark first-used)."
  []
  [{:source :setting
    :id     :copilot-toggle
    :label  "Toggle co-pilot rail"
    :hint   "Ctrl+Shift+/"
    :icon   (icon-table :setting)
    :boost  (boost-table :setting)
    :action [:palette/copilot-toggle]
    :popout? false}
   {:source :setting
    :id     :density-toggle
    :label  "Cycle display density"
    :hint   "compact / cosy / comfy"
    :icon   (icon-table :setting)
    :boost  (boost-table :setting)
    :action [:palette/cycle-density]
    :popout? false}])

(defn command-items
  "Causa command verbs (Phase 1 subset). Each carries a stable id +
  an action dispatch — the events ns owns the actual side-effect
  fns; the aggregator just describes the choices."
  []
  [{:source :command
    :id     :clear-trace-buffer
    :label  "Clear trace buffer"
    :hint   "drops Causa's ring buffer"
    :icon   (icon-table :command)
    :boost  (boost-table :command)
    :action [:palette/clear-trace-buffer]
    :popout? false}
   {:source :command
    :id     :reset-suppressed-counters
    :label  "Reset redacted-events counter"
    :hint   "clears the REDACTED N indicator"
    :icon   (icon-table :command)
    :boost  (boost-table :command)
    :action [:palette/reset-suppressed-counters]
    :popout? false}
   {:source :command
    :id     :open-popout
    :label  "Open Causa in a pop-out window"
    :hint   "rf-causa-popout"
    :icon   (icon-table :command)
    :boost  (boost-table :command)
    :action [:palette/open-popout]
    :popout? false}
   {:source :command
    :id     :close-palette
    :label  "Close command palette"
    :hint   "ESC"
    :icon   (icon-table :command)
    :boost  (boost-table :command)
    :action [:palette/close]
    :popout? false}])

(defn copilot-question-items
  "Indexed from a `[\"text1\" \"text2\" ...]` recency-ordered seq of
  the last co-pilot questions. The seq's first entry is the most
  recent question."
  ([questions]
   (copilot-question-items questions 10))
  ([questions max-rows]
   (->> questions
        (take max-rows)
        (map-indexed
          (fn [i q]
            {:source       :copilot-question
             :id           [::copilot-q i q]
             :label        (str "Re-ask co-pilot: " q)
             :hint         (str "history · "
                                (if (zero? i) "latest"
                                    (str (inc i) " back")))
             :icon         (icon-table :copilot-question)
             :recency-rank i
             :boost        (boost-table :copilot-question)
             :action       [:palette/reopen-copilot-question q]
             :popout?      false}))
        vec)))

;; ---- aggregator ----------------------------------------------------------

(defn build-index
  "Build the full searchable index from `inputs`. The result is a
  vector of source items, dedup'd on `[:source :id]` (first-wins).
  No fuzzy match runs here — this is the pre-query corpus.

  Inputs map shape:

    {:panels            [{:id kw :label str} ...]
     :trace-buffer      [trace ...]
     :frame-ids         (keyword?)
     :handlers          [{:id :kind :doc :file :line} ...]
     :copilot-questions [str ...]}

  Missing keys default to empty inputs of that kind — partial drives
  (e.g. recency-rank dragons without a populated buffer) are
  tolerable for the empty-state surface."
  [inputs]
  (let [{:keys [panels trace-buffer frame-ids handlers copilot-questions]
         :or   {panels             []
                trace-buffer       []
                frame-ids          []
                handlers           []
                copilot-questions  []}} inputs
        all      (concat
                   (command-items)
                   (panel-items panels)
                   (setting-items)
                   (recent-event-items trace-buffer)
                   (copilot-question-items copilot-questions)
                   (frame-items frame-ids)
                   (handler-items handlers))]
    (loop [seen   (transient #{})
           out    (transient [])
           items  all]
      (if (empty? items)
        (persistent! out)
        (let [it   (first items)
              key  [(:source it) (:id it)]]
          (if (contains? seen key)
            (recur seen out (rest items))
            (recur (conj! seen key) (conj! out it) (rest items))))))))

;; ---- ranking -------------------------------------------------------------

(defn- recency-bonus
  "Linear decay: rank 0 → +30, rank 30+ → 0."
  [rank]
  (if (nil? rank)
    0
    (max 0 (- 30 rank))))

(defn score-item
  "Score a single item against `query`. Returns `nil` when the item's
  label does not fuzzy-match the query; otherwise returns the item
  augmented with `:score` (final) + `:fuzzy` (raw fuzzy score) +
  `:indices` (matched char positions)."
  [item query]
  (when-let [m (fuzzy/score-with-meta (:label item) query)]
    (let [fuzzy-s (:score m)
          final   (+ fuzzy-s
                     (or (:boost item) 0)
                     (recency-bonus (:recency-rank item)))]
      (assoc item
             :score    final
             :fuzzy    fuzzy-s
             :indices  (:indices m)))))

(defn rank
  "Rank `index` against `query`. Returns a sorted vector of scored
  items (highest score first). Empty query keeps every item and
  orders by `boost + recency-bonus` alone — useful for the empty-
  state surface where the palette shows the most-actionable choices."
  ([index query]
   (rank index query 30))
  ([index query limit]
   (let [scored (->> index
                     (keep #(score-item % query))
                     vec)
         sorted (->> scored
                     (sort-by (fn [it]
                                ;; sort descending on score; tie-break
                                ;; ascending on label length (shorter
                                ;; labels are usually the more
                                ;; specific match) then on label
                                ;; lexicographic.
                                [(- (:score it))
                                 (count (:label it))
                                 (:label it)])))]
     (vec (take limit sorted)))))

(defn popoutable?
  "True when an item supports Ctrl+Enter pop-out semantics. The
  palette UI reads this to decide whether to surface the pop-out
  hint and whether to wrap the invoke through `popout!`. Defaults
  to `false` — a source must explicitly opt in."
  [item]
  (true? (:popout? item)))
