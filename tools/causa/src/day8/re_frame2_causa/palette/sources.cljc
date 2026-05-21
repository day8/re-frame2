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
   :handler          2})

(def ^:private icon-table
  {:command          "▸"
   :panel            "◧"
   :setting          "⚙"
   :recent-event     "⟳"
   :frame            "◆"
   :handler          "ƒ"})

;; ---- source builders -----------------------------------------------------

(defn panel-items
  "One row per Dynamic sidebar panel. The action dispatches into
  `:rf/causa` via the wrapping events fn (palette events resolve
  `[:panel id]` into `[:rf.causa/select-tab id]`).

  Per rf2-ybjkx each row carries `:modes #{:dynamic}` so the mode
  filter excludes Dynamic tab jumps when the palette opens in Static
  mode. Static-mode tab jumps are produced by `static-tab-items`."
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
       :modes  #{:dynamic}
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
              :modes        #{:dynamic}
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
                :modes  #{:dynamic}
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
                 :modes  #{:dynamic :static}
                 :popout? true})))))

(defn setting-items
  "Static settings the palette can flip / open."
  []
  [{:source :setting
    :id     :density-toggle
    :label  "Cycle display density"
    :hint   "compact / cosy / comfy"
    :icon   (icon-table :setting)
    :boost  (boost-table :setting)
    :action [:palette/cycle-density]
    :modes  #{:dynamic :static}
    :popout? false}])

;; ---- per-source mode visibility ----------------------------------------
;;
;; rf2-ybjkx — mode-aware command surface. Each command carries a
;; `:modes` set declaring which Causa modes (`:dynamic` / `:static`)
;; surface it. Commands meaningful in both modes (theme toggle,
;; reduced-motion, jump-to-settings, toggle-mode) carry `#{:dynamic
;; :static}`. Commands that only make sense in one mode (spine clear,
;; Dynamic L4 jumps; Static sub-tab jumps) carry the appropriate
;; single-mode set.
;;
;; The aggregator filters by `:modes` membership when `:mode` is
;; non-nil in the inputs map; nil mode preserves the pre-bead
;; behaviour (every command surfaced).

(defn command-items
  "Causa command verbs. Each carries a stable id + an action dispatch
  (the events ns owns the actual side-effect fns; the aggregator just
  describes the choices) + a `:modes` set declaring mode visibility
  per rf2-ybjkx (see §per-source mode visibility above).

  ## Dynamic-only verbs

  Trace buffer / suppressed counters / pop-out / select-panel — every
  Dynamic-mode command operates against the event-coupled spine or the
  4-layer chrome. Static mode is event-INDEPENDENT (per
  `static/shell.cljs`); these verbs have no meaning there.

  ## Static-only verbs

  Jump-to-static-tab — selects one of the 5 Static L3 tabs (Machines /
  Routes / Schemas / Views / Events). Static has no spine so the
  Dynamic tab jumps don't apply.

  ## Mode-agnostic verbs (rf2-ybjkx)

  - `:toggle-theme`            — Dark ↔ Light cycle of the theme class.
  - `:cycle-reduced-motion`    — `:os → :always → :never → :os`.
  - `:snapshot-app-db`         — Drop the focused frame's app-db onto
                                 the JS console as a snapshot.
  - `:jump-to-settings`        — Open the Settings popup at the General
                                 tab.
  - `:toggle-mode`             — Flip Dynamic ↔ Static (chord parity
                                 with `Cmd-Shift-M`)."
  []
  [{:source :command
    :id     :clear-trace-buffer
    :label  "Clear trace buffer"
    :hint   "drops Causa's ring buffer"
    :icon   (icon-table :command)
    :boost  (boost-table :command)
    :action [:palette/clear-trace-buffer]
    :modes  #{:dynamic}
    :popout? false}
   {:source :command
    :id     :clear-epoch-history
    :label  "Clear epoch history"
    :hint   "drops Causa's epoch snapshots"
    :icon   (icon-table :command)
    :boost  (boost-table :command)
    :action [:palette/clear-epoch-history]
    :modes  #{:dynamic}
    :popout? false}
   {:source :command
    :id     :reset-suppressed-counters
    :label  "Reset redacted-events counter"
    :hint   "clears the REDACTED N indicator"
    :icon   (icon-table :command)
    :boost  (boost-table :command)
    :action [:palette/reset-suppressed-counters]
    :modes  #{:dynamic}
    :popout? false}
   {:source :command
    :id     :open-popout
    :label  "Open Causa in a pop-out window"
    :hint   "rf-causa-popout"
    :icon   (icon-table :command)
    :boost  (boost-table :command)
    :action [:palette/open-popout]
    :modes  #{:dynamic :static}
    :popout? false}
   ;; rf2-ybjkx — Snapshot the focused frame's app-db onto the JS
   ;; console for clipboard capture / share with a teammate.
   {:source :command
    :id     :snapshot-app-db
    :label  "Snapshot app-db"
    :hint   "→ console.log + clipboard"
    :icon   (icon-table :command)
    :boost  (boost-table :command)
    :action [:palette/snapshot-app-db]
    :modes  #{:dynamic :static}
    :popout? false}
   ;; rf2-ybjkx — Theme toggle. The popup's radio is still the
   ;; canonical UI; the palette is the keyboard-first ergonomic
   ;; shortcut.
   {:source :command
    :id     :toggle-theme
    :label  "Toggle theme (dark ↔ light)"
    :hint   "Settings · Theme"
    :icon   (icon-table :command)
    :boost  (boost-table :command)
    :action [:palette/toggle-theme]
    :modes  #{:dynamic :static}
    :popout? false}
   ;; rf2-ybjkx — Reduced-motion cycle. Three states: `:os` (OS pref
   ;; alone), `:always` (force reduce), `:never` (force full). Cycles
   ;; through them so a single command covers every transition.
   {:source :command
    :id     :cycle-reduced-motion
    :label  "Cycle reduced-motion override (OS → always → never)"
    :hint   "user override of prefers-reduced-motion"
    :icon   (icon-table :command)
    :boost  (boost-table :command)
    :action [:palette/cycle-reduced-motion]
    :modes  #{:dynamic :static}
    :popout? false}
   ;; rf2-ybjkx — Jump to settings. Equivalent to the `,` / `s`
   ;; bare-key shortcut but available from the palette so the user
   ;; can fuzzy-find the gesture without leaving the keyboard.
   {:source :command
    :id     :jump-to-settings
    :label  "Jump to Settings"
    :hint   ","
    :icon   (icon-table :command)
    :boost  (boost-table :command)
    :action [:palette/jump-to-settings]
    :modes  #{:dynamic :static}
    :popout? false}
   ;; rf2-ybjkx — Toggle Dynamic ↔ Static. Chord parity with
   ;; `Cmd/Ctrl-Shift-M` (see `keybinding.cljs`). Surfaced in BOTH
   ;; modes — the user can flip in either direction from the palette.
   {:source :command
    :id     :toggle-mode
    :label  "Toggle mode (Dynamic ↔ Static)"
    :hint   "Cmd/Ctrl+Shift+M"
    :icon   (icon-table :command)
    :boost  (boost-table :command)
    :action [:palette/toggle-mode]
    :modes  #{:dynamic :static}
    :popout? false}
   {:source :command
    :id     :close-palette
    :label  "Close command palette"
    :hint   "ESC"
    :icon   (icon-table :command)
    :boost  (boost-table :command)
    :action [:palette/close]
    :modes  #{:dynamic :static}
    :popout? false}])

(defn static-tab-items
  "Static-mode sub-tab jumps (rf2-ybjkx). Five entries mirroring the
  `static/shell.cljs/tabs` inventory: Machines / Routes / Schemas /
  Views / Events. Each carries `:modes #{:static}` so the aggregator
  filters them out when the palette opens in Dynamic mode."
  [static-tab-entries]
  (mapv
    (fn [{:keys [id label]}]
      {:source :panel
       :id     [:static id]
       :label  (str "Open " label " (Static)")
       :hint   (str "static/" (name id))
       :icon   (icon-table :panel)
       :boost  (boost-table :panel)
       :action [:palette/select-static-tab id]
       :modes  #{:static}
       :popout? false})
    static-tab-entries))

;; ---- aggregator ----------------------------------------------------------

;; ---- recents boosts (rf2-ybjkx) -----------------------------------------

(def recents-boost-max
  "Max boost applied to the most-recently-used command. Decays linearly
  with index so position 0 (most recent) gets `recents-boost-max`,
  position 1 gets `recents-boost-max - recents-boost-step`, …, and
  positions beyond the recents tail get 0. Sized so the most-recent
  command outranks a fresh `:command` source item that fuzzy-matches
  at parity (boost-table `:command` = 40, so 60 = +50% on top — enough
  for the recent to sort first without dominating a typed query)."
  60)

(def recents-boost-step
  "Decay-per-position for the recents boost. With `recents-boost-max =
  60` and `max-recents = 3` this gives 60 / 40 / 20 for positions 0 /
  1 / 2."
  20)

(defn- recents-boost-for-id
  "Compute the recents boost for `command-id` against the recents
  vector. Position 0 = `recents-boost-max`; each subsequent position
  drops by `recents-boost-step`; absent ids get 0."
  [recents command-id]
  (let [idx (some (fn [[i v]] (when (= command-id v) i))
                  (map-indexed vector recents))]
    (if (nil? idx)
      0
      (max 0 (- recents-boost-max (* idx recents-boost-step))))))

;; ---- aggregator ----------------------------------------------------------

(defn- in-mode?
  "Mode predicate. `:modes` membership filter — nil mode keeps every
  item (pre-bead behaviour), a non-nil mode keeps only items whose
  `:modes` set contains it. Items missing `:modes` default to both
  modes (the legacy contract — every item used to be visible always)."
  [mode item]
  (if (nil? mode)
    true
    (let [modes (:modes item)]
      (if (nil? modes)
        true
        (contains? modes mode)))))

(defn build-index
  "Build the full searchable index from `inputs`. The result is a
  vector of source items, dedup'd on `[:source :id]` (first-wins).
  No fuzzy match runs here — this is the pre-query corpus.

  Inputs map shape:

    {:panels        [{:id kw :label str} ...]    ; Dynamic L3 tabs
     :static-tabs   [{:id kw :label str} ...]    ; Static L3 tabs (rf2-ybjkx)
     :trace-buffer  [trace ...]
     :frame-ids     (keyword?)
     :handlers      [{:id :kind :doc :file :line} ...]
     :mode          :dynamic | :static | nil     ; filter (rf2-ybjkx)
     :recents       [command-id ...]             ; recents boost (rf2-ybjkx)
    }

  Missing keys default to empty inputs of that kind — partial drives
  (e.g. recency-rank dragons without a populated buffer) are
  tolerable for the empty-state surface.

  ## Mode-aware filter (rf2-ybjkx)

  Items declare a `:modes` set; the aggregator drops items whose set
  doesn't contain `:mode`. nil `:mode` preserves the pre-bead contract
  (every item surfaced). The filter is applied AFTER source assembly
  but BEFORE dedup so an item with the same `[:source :id]` in both
  modes can still surface — but no source emits the same id under
  both modes today.

  ## Recents boost (rf2-ybjkx)

  Items whose `:source = :command` AND whose `:id` is in `:recents`
  receive an extra position-decayed boost added to their static
  `:boost`. The boost is large enough to lift a recently-used command
  above a fresh fuzzy peer but small enough that a tighter query
  still wins. Pure-data composition — `score-item` reads the boost
  from the item itself, so the recents bump rides the standard
  scoring pipeline."
  [inputs]
  (let [{:keys [panels static-tabs trace-buffer frame-ids handlers mode recents]
         :or   {panels             []
                static-tabs        []
                trace-buffer       []
                frame-ids          []
                handlers           []
                recents            []
                mode               nil}} inputs
        recents-vec (vec recents)
        bump        (fn [item]
                      (let [boost-add (cond
                                        (= :command (:source item))
                                        (recents-boost-for-id recents-vec (:id item))
                                        :else 0)]
                        (if (pos? boost-add)
                          (update item :boost (fnil + 0) boost-add)
                          item)))
        all         (concat
                      (map bump (command-items))
                      (panel-items panels)
                      (static-tab-items static-tabs)
                      (setting-items)
                      (recent-event-items trace-buffer)
                      (frame-items frame-ids)
                      (handler-items handlers))
        filtered    (filter (partial in-mode? mode) all)]
    (loop [seen   (transient #{})
           out    (transient [])
           items  filtered]
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
