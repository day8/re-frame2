(ns day8.re-frame2-causa.panels.app-db-diff
  "App-DB Diff panel — Phase 5 (rf2-jps1o, parent rf2-5aw5v).

  Per `tools/causa/spec/004-App-DB-Diff.md` the panel is **slice-
  centric**, not tree-centric. Real app-dbs run 1–50MB; rendering the
  whole tree on every dispatch competes for canvas real estate. The
  panel surfaces the *slices that changed in this epoch* plus *slices
  the programmer has pinned*. A full-tree escape hatch is available
  for the rare case the user wants to roam.

  ## What this panel shows

    1. **Changed-slice mini-panels** — one per `[op path before after]`
       triple produced by `app-db-diff-helpers/diff-paths`. Colour-
       coded by op (`:added` green / `:modified` yellow / `:removed`
       red).

    2. **Reserved-keys group** — `:rf/machines`, `:rf/route`,
       `:rf/system-ids`, `:rf/pending-navigation`, `:rf/spawned`
       render in a separate `[runtime]` group at the bottom so the
       programmer recognises them as runtime-owned (per spec
       §Reserved-keys group).

    3. **Pinned-slices** — paths the user has right-click-pinned.
       Live values for the current target frame, dereferenced once
       per epoch per pin. Right-click → Unpin / Move up / Move down /
       Show me when this changed.

    4. **'Show me when this changed' result** — when the user invokes
       the affordance on a path, a list of epochs that touched that
       path renders in place of the slice mini-panels. Clicking an
       entry rebases the panels to that epoch.

    5. **Empty state** — when no dispatches have run.

  ## Read-only forever (Lock 3)

  The panel renders Copy value / Copy path / Pin / Show me when this
  changed affordances; it does not render Edit / Set-to / Inject.
  Per spec §Read-only and `DESIGN-RATIONALE.md` Lock 3 — the runtime
  is the source of truth; pokes from the debugger are out of scope.

  ## Pure hiccup (rf2-tijr)

  Same contract as every other Causa panel — the view is pure hiccup,
  no Reagent / UIx / Helix references. The substrate adapter mounts
  it. Frame isolation comes from the enclosing
  `[rf/frame-provider {:frame :rf/causa}]` in `shell.cljs`.

  ## Helpers

  All pure-data logic — `diff-paths` (structural-sharing diff),
  `partition-reserved` (reserved-keys segregation), pin-store
  transitions, `epochs-touching-path` (the 'Show me when this
  changed' walker) — lives in `app_db_diff_helpers.cljc` so the
  algebra runs under the JVM unit-test target."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.defaults :as defaults]
            [day8.re-frame2-causa.panels.app-db-diff-helpers :as h]
            [day8.re-frame2-causa.panels.time-travel-helpers :as tt-helpers]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

;; ---- op-token resolution -------------------------------------------------

(def ^:private op->border
  "Per spec §Colour coding — the slice mini-panel's left border encodes
  the op. Green for added, yellow for modified, red for removed."
  {:added    :green
   :modified :yellow
   :removed  :red})

(def ^:private op->label
  "Per spec §Colour coding — the key tag rendered next to the path."
  {:added    "(added)"
   :modified "(modified)"
   :removed  "(removed)"})

;; ---- pure helpers --------------------------------------------------------

(defn- format-edn
  "Best-effort EDN-like format. Used to render values + paths in the
  mono column."
  [v]
  (try
    (pr-str v)
    (catch :default _
      (str v))))

(defn- truncate
  "Truncate `s` to `n` chars (adding an ellipsis)."
  [s n]
  (let [s (str s)]
    (if (<= (count s) n)
      s
      (str (subs s 0 (max 0 (dec n))) "…"))))

;; ---- sub-views: empty state ---------------------------------------------

(defn- empty-state
  "Per spec §Empty state — 'app-db is at the boot value. No diffs
  yet — every dispatch will land here with the slices it touched.'"
  [target-frame]
  [:div {:data-testid "rf-causa-app-db-diff-empty"
         :style       {:padding "16px"
                       :color   (:text-tertiary tokens)
                       :font-family sans-stack
                       :font-size "13px"}}
   [:p {:style {:margin "0 0 8px 0"}}
    [:code {:style {:color (:accent-violet tokens) :font-family mono-stack}}
     "app-db"]
    " for "
    [:code {:style {:color (:accent-violet tokens) :font-family mono-stack}}
     (str target-frame)]
    " is at the boot value."]
   [:p {:style {:margin 0}}
    "No diffs yet — every dispatch will land here with the slices it touched."]])

;; ---- sub-views: slice mini-panel ----------------------------------------

(defn- value-block
  "Render one labelled value sub-row inside a slice mini-panel."
  [label value tone]
  [:div {:style {:display     "flex"
                 :align-items "flex-start"
                 :gap         "8px"
                 :padding     "2px 0"}}
   [:div {:style {:flex          "0 0 64px"
                  :color         (tone tokens)
                  :font-family   sans-stack
                  :font-size     "10px"
                  :font-weight   600
                  :text-transform "uppercase"
                  :letter-spacing "0.5px"
                  :padding-top   "2px"}}
    label]
   [:div {:style {:flex          1
                  :min-width     0
                  :font-family   mono-stack
                  :font-size     "12px"
                  :color         (:text-primary tokens)
                  :word-break    "break-word"
                  :white-space   "pre-wrap"}}
    (format-edn value)]])

(defn- slice-row
  "One slice mini-panel for a `[op path before after]` triple.

  - Coloured left border (`:added` / `:modified` / `:removed`).
  - Path in the mono column.
  - For `:modified` shows `before` + `after`; for `:added` shows the
    new value; for `:removed` shows the prior value (struck-through
    per spec §Colour coding).

  Right-click affordances per spec §Read-only — copy / pin / show
  when this changed are wired through `:rf.causa/*` events."
  [{:keys [op path before after] :as _triple}]
  (let [tone (op->border op)]
    [:div {:data-testid (str "rf-causa-app-db-diff-slice-" (pr-str path))
           :style       {:display       "flex"
                         :flex-direction "column"
                         :padding       "8px 12px 8px 10px"
                         :margin        "8px 12px"
                         :background    (:bg-3 tokens)
                         :border-left   (str "3px solid " (tone tokens))
                         :border-top    (str "1px solid " (:border-subtle tokens))
                         :border-right  (str "1px solid " (:border-subtle tokens))
                         :border-bottom (str "1px solid " (:border-subtle tokens))
                         :border-radius "4px"}}
     [:div {:style {:display "flex"
                    :align-items "center"
                    :justify-content "space-between"
                    :gap "8px"
                    :margin-bottom "4px"}}
      [:span {:style {:font-family mono-stack
                      :font-size   "12px"
                      :color       (:text-primary tokens)
                      :font-weight 600}}
       (truncate (format-edn path) 56)]
      [:span {:style {:font-family sans-stack
                      :font-size   "10px"
                      :color       (tone tokens)
                      :text-transform "uppercase"
                      :letter-spacing "0.5px"}}
       (op->label op)]]
     [:div {:style {:display "flex"
                    :gap "6px"
                    :margin-bottom "6px"}}
      [:button {:data-testid (str "rf-causa-app-db-diff-pin-" (pr-str path))
                :on-click    #(rf/dispatch [:rf.causa/pin-slice path] {:frame :rf/causa})
                :style       {:background  "transparent"
                              :color       (:cyan tokens)
                              :border      (str "1px solid " (:border-default tokens))
                              :padding     "1px 6px"
                              :border-radius "4px"
                              :cursor      "pointer"
                              :font-family sans-stack
                              :font-size   "10px"}}
       "Pin"]
      [:button {:data-testid (str "rf-causa-app-db-diff-show-when-" (pr-str path))
                :on-click    #(rf/dispatch [:rf.causa/focus-slice-path path] {:frame :rf/causa})
                :style       {:background  "transparent"
                              :color       (:magenta tokens)
                              :border      (str "1px solid " (:border-default tokens))
                              :padding     "1px 6px"
                              :border-radius "4px"
                              :cursor      "pointer"
                              :font-family sans-stack
                              :font-size   "10px"}}
       "Show me when this changed"]
      [:button {:data-testid (str "rf-causa-app-db-diff-copy-path-" (pr-str path))
                :on-click    #(rf/dispatch [:rf.causa/copy-path-to-clipboard path] {:frame :rf/causa})
                :style       {:background  "transparent"
                              :color       (:text-secondary tokens)
                              :border      (str "1px solid " (:border-default tokens))
                              :padding     "1px 6px"
                              :border-radius "4px"
                              :cursor      "pointer"
                              :font-family sans-stack
                              :font-size   "10px"}}
       "Copy path"]
      [:button {:data-testid (str "rf-causa-app-db-diff-copy-value-" (pr-str path))
                :on-click    #(rf/dispatch [:rf.causa/copy-value-to-clipboard
                                            (case op
                                              :removed before
                                              after)] {:frame :rf/causa})
                :style       {:background  "transparent"
                              :color       (:text-secondary tokens)
                              :border      (str "1px solid " (:border-default tokens))
                              :padding     "1px 6px"
                              :border-radius "4px"
                              :cursor      "pointer"
                              :font-family sans-stack
                              :font-size   "10px"}}
       "Copy value"]]
     (case op
       :added    (value-block "added" after :green)
       :removed  [:div {:style {:text-decoration "line-through"}}
                 (value-block "removed" before :red)]
       :modified [:div
                  (value-block "before" before :text-tertiary)
                  (value-block "after"  after  :yellow)])]))

;; ---- sub-views: reserved keys group -------------------------------------

(defn- reserved-row
  "One row in the reserved-keys group. Renders the key + a one-line
  summary of its current value (per spec §Reserved-keys group)."
  [[k v]]
  [:div {:data-testid (str "rf-causa-app-db-diff-reserved-" (pr-str k))
         :style       {:display "flex"
                       :justify-content "space-between"
                       :gap "12px"
                       :padding "4px 12px"
                       :border-bottom (str "1px solid " (:border-subtle tokens))
                       :font-family mono-stack
                       :font-size "12px"}}
   [:span {:style {:color (:text-secondary tokens)}} (format-edn k)]
   [:span {:style {:color (:text-tertiary tokens)}}
    (truncate (format-edn v) 48)]])

(defn- reserved-group
  "The `[runtime]` group at the bottom of the panel — surfaces the
  reserved app-db keys (`:rf/machines`, `:rf/route`, etc.) clearly
  marked so the programmer recognises them as runtime-owned."
  [reserved-pairs]
  (when (seq reserved-pairs)
    [:section {:data-testid "rf-causa-app-db-diff-reserved-group"
               :style       {:margin "12px 12px"
                             :background (:bg-3 tokens)
                             :border (str "1px solid " (:border-subtle tokens))
                             :border-radius "4px"}}
     [:header {:style {:padding "6px 12px"
                       :border-bottom (str "1px solid " (:border-subtle tokens))
                       :font-family sans-stack
                       :font-size "11px"
                       :font-weight 600
                       :text-transform "uppercase"
                       :letter-spacing "0.5px"
                       :color (:text-secondary tokens)}}
      "[runtime] — reserved app-db keys"]
     (into [:div]
           (for [pair reserved-pairs]
             ^{:key (pr-str (first pair))}
             (reserved-row pair)))]))

;; ---- sub-views: pinned slices --------------------------------------------

(defn- pinned-row
  "One row in the pinned-slices group. Renders the pin's path + live
  value derived from the current target-frame's app-db. Per spec
  §Pinned slices."
  [{:keys [path value]}]
  [:div {:data-testid (str "rf-causa-app-db-diff-pinned-" (pr-str path))
         :style       {:display "flex"
                       :justify-content "space-between"
                       :gap "12px"
                       :padding "4px 12px"
                       :border-bottom (str "1px solid " (:border-subtle tokens))
                       :font-family mono-stack
                       :font-size "12px"}}
   [:span {:style {:color (:text-primary tokens)}}
    (truncate (format-edn path) 36)]
   [:span {:style {:display "flex" :gap "8px" :align-items "center"}}
    [:span {:style {:color (:text-tertiary tokens)}}
     (truncate (format-edn value) 36)]
    [:button {:data-testid (str "rf-causa-app-db-diff-unpin-" (pr-str path))
              :on-click    #(rf/dispatch [:rf.causa/unpin-slice path] {:frame :rf/causa})
              :style       {:background "transparent"
                            :border     "none"
                            :color      (:text-tertiary tokens)
                            :cursor     "pointer"
                            :font-family mono-stack
                            :font-size  "11px"}
              :title       "Unpin"}
     "✕"]]])

(defn- pinned-group
  "The Pinned-slices section. Hidden when no pins exist for the
  current target-frame."
  [pinned-slices]
  (when (seq pinned-slices)
    [:section {:data-testid "rf-causa-app-db-diff-pinned-group"
               :style       {:margin "12px 12px"
                             :background (:bg-3 tokens)
                             :border (str "1px solid " (:border-subtle tokens))
                             :border-radius "4px"}}
     [:header {:style {:padding "6px 12px"
                       :border-bottom (str "1px solid " (:border-subtle tokens))
                       :font-family sans-stack
                       :font-size "11px"
                       :font-weight 600
                       :text-transform "uppercase"
                       :letter-spacing "0.5px"
                       :color (:text-secondary tokens)}}
      "Pinned slices"]
     (into [:div]
           (for [p pinned-slices]
             ^{:key (pr-str (:path p))}
             (pinned-row p)))]))

;; ---- sub-views: show-me-when-this-changed result ------------------------

(defn- focus-result-row
  "One row in the 'Show me when this changed' result list. Clicking
  the row rebases the time-travel scrubber's selected-epoch (which in
  turn re-filters the causality graph + repaints this panel)."
  [{:keys [epoch-id event op before after] :as _hit}]
  [:li {:data-testid (str "rf-causa-app-db-diff-focus-hit-" (pr-str epoch-id))
        :on-click    #(rf/dispatch [:rf.causa/select-epoch epoch-id] {:frame :rf/causa})
        :style       {:display "flex"
                      :align-items "center"
                      :gap "12px"
                      :padding "6px 12px"
                      :cursor "pointer"
                      :border-bottom (str "1px solid " (:border-subtle tokens))
                      :font-family mono-stack
                      :font-size "12px"
                      :color (:text-primary tokens)}}
   [:span {:style {:color (get tokens (op->border op))
                   :flex "0 0 80px"}}
    (op->label op)]
   [:span {:style {:color (:accent-violet tokens) :flex "0 0 120px"}}
    (truncate (format-edn (or event :ungrouped)) 16)]
   [:span {:style {:color (:text-tertiary tokens) :flex 1
                   :overflow "hidden" :text-overflow "ellipsis" :white-space "nowrap"}}
    (case op
      :added   (str "added " (truncate (format-edn after) 32))
      :removed (str "removed " (truncate (format-edn before) 32))
      :modified (str (truncate (format-edn before) 16)
                     " → " (truncate (format-edn after) 16)))]])

(defn- focus-result-panel
  "Renders the 'Show me when this changed' result in place of the
  changed-slice mini-panels. Per spec §'Show me when this changed' —
  walks epoch-history, lists epochs that touched the focused path."
  [focused-path hits]
  [:section {:data-testid "rf-causa-app-db-diff-focus-result"
             :style       {:margin "8px 12px"
                           :background (:bg-3 tokens)
                           :border (str "1px solid " (:border-subtle tokens))
                           :border-radius "4px"}}
   [:header {:style {:padding "8px 12px"
                     :display "flex"
                     :justify-content "space-between"
                     :align-items "center"
                     :border-bottom (str "1px solid " (:border-subtle tokens))
                     :font-family sans-stack
                     :font-size "12px"}}
    [:span {:style {:color (:text-secondary tokens)}}
     "Epochs that touched "
     [:code {:style {:color (:accent-violet tokens) :font-family mono-stack}}
      (format-edn focused-path)]]
    [:button {:data-testid "rf-causa-app-db-diff-clear-focus"
              :on-click    #(rf/dispatch [:rf.causa/clear-slice-focus] {:frame :rf/causa})
              :style       {:background "transparent"
                            :border (str "1px solid " (:border-default tokens))
                            :color (:text-secondary tokens)
                            :padding "2px 8px"
                            :border-radius "4px"
                            :cursor "pointer"
                            :font-family sans-stack
                            :font-size "11px"}}
     "Close"]]
   (if (empty? hits)
     [:p {:style {:padding "12px"
                  :color (:text-tertiary tokens)
                  :font-family sans-stack
                  :font-size "12px"
                  :margin 0}}
      "No epochs in the current ring buffer touched this path."]
     (into [:ul {:data-testid "rf-causa-app-db-diff-focus-hits"
                 :style {:list-style "none"
                         :margin 0
                         :padding 0}}]
           (for [hit hits]
             ^{:key (pr-str (:epoch-id hit))}
             (focus-result-row hit))))])

;; ---- sub-views: changed slices stack ------------------------------------

(defn- changed-slices-stack
  "The stacked slice mini-panels for the selected epoch's changed
  paths (non-reserved keys). Falls back to a tidy 'no changes' note
  when the epoch produced no diffs (synthetic epochs from
  reset-frame-db!)."
  [non-reserved-triples]
  (if (empty? non-reserved-triples)
    [:p {:data-testid "rf-causa-app-db-diff-no-changes"
         :style {:padding "12px"
                 :color   (:text-tertiary tokens)
                 :font-family sans-stack
                 :font-size "12px"
                 :margin 0}}
     "No slice changes in the selected epoch."]
    (into [:div {:data-testid "rf-causa-app-db-diff-slices"}]
          (for [t non-reserved-triples]
            ^{:key (pr-str (:path t))}
            (slice-row t)))))

;; ---- public view --------------------------------------------------------

(rf/reg-view app-db-diff-view
  "The App-DB Diff panel's root view. Subscribes to
  `:rf.causa/app-db-diff` and renders the slice-centric stack +
  pinned slices + reserved-keys group, or the empty state when no
  dispatches have run, or the 'Show me when this changed' result
  when a path is focused."
  []
  (let [{:keys [target-frame
                history-empty?
                changed-non-reserved
                changed-reserved
                pinned-slices
                focused-path
                focused-hits]}
        @(rf/subscribe [:rf.causa/app-db-diff])]
    [:section {:data-testid "rf-causa-app-db-diff"
               :style       {:height         "100%"
                             :display        "flex"
                             :flex-direction "column"
                             :background     (:bg-2 tokens)
                             :color          (:text-primary tokens)
                             :font-family    sans-stack
                             :font-size      "14px"}}
     [:header {:style {:padding "16px 16px 8px 16px"}}
      [:h1 {:style {:font-size   "16px"
                    :font-weight 600
                    :margin      0
                    :color       (:text-primary tokens)}}
       "App-db diff"]
      [:p {:style {:font-size "12px"
                   :color     (:text-tertiary tokens)
                   :margin    "4px 0 0 0"}}
       "Slice-centric. The slices that changed this epoch + pinned "
       "slices + reserved-keys runtime group. Read-only (Lock #3). Frame: "
       [:code {:style {:color (:accent-violet tokens) :font-family mono-stack}}
        (str target-frame)]]]
     [:div {:style {:flex 1 :overflow "auto"}}
      (cond
        focused-path
        (focus-result-panel focused-path focused-hits)

        history-empty?
        [:div
         (empty-state target-frame)
         ;; Pinned slices may already exist (persisted across sessions
         ;; per spec §Pinned slices) — show them even before the first
         ;; dispatch.
         (pinned-group pinned-slices)
         (reserved-group changed-reserved)]

        :else
        [:div
         (changed-slices-stack changed-non-reserved)
         (pinned-group pinned-slices)
         (reserved-group changed-reserved)])]]))

;; ---- registration entry --------------------------------------------------

(defn install!
  "Idempotent install for the App-DB Diff panel's Causa-side
  registrations (Phase 5, rf2-jps1o). Owns the cross-panel
  `:rf.causa/target-frame-db` sub (also read by the Routes and
  Machine Inspector panels) plus the slice / focus / clipboard
  surface."
  []
  ;; ---- Phase 5 (rf2-jps1o) — App-DB Diff subs ------------------

  ;; The host frame's current app-db value. Read via rf/get-frame-db
  ;; against the Phase 3 :rf.causa/target-frame. Wrapped in a sub so
  ;; the panel reacts to host writes via the same reactive surface as
  ;; everything else; the sub fn itself is side-effecting (rf/get-
  ;; frame-db hits a frame atom), but the reactivity is driven by
  ;; epoch-history updates pumped into Causa's app-db on every settle.
  (rf/reg-sub :rf.causa/target-frame-db
    :<- [:rf.causa/target-frame]
    :<- [:rf.causa/epoch-history]
    (fn [[target _epoch-history] _query]
      ;; Depend on :rf.causa/epoch-history so the sub re-fires on
      ;; every settled epoch. The actual read is via rf/get-frame-db
      ;; — the framework's canonical accessor.
      (rf/get-frame-db target)))

  ;; The selected epoch's record from :rf.causa/epoch-history. nil
  ;; when no selection or the selection has aged out of the ring
  ;; buffer.
  (rf/reg-sub :rf.causa/selected-epoch-record
    :<- [:rf.causa/epoch-history]
    :<- [:rf.causa/selected-epoch-id]
    (fn [[history selected-id] _query]
      (when selected-id
        (tt-helpers/find-epoch-in-history history selected-id))))

  ;; The diff triples for the currently-selected epoch. When no
  ;; epoch is selected, the diff is between the newest epoch's
  ;; :db-before and :db-after (the most recent settle).
  ;;
  ;; ## Per-:epoch-id diff cache (rf2-qvaa0)
  ;;
  ;; Diffing two app-dbs is O(changed-subtree-depth × key-count).
  ;; On hosts with large app-dbs this is the most likely user-
  ;; visible Causa slowdown — without the cache, every re-render
  ;; that lands while the selection is unchanged re-runs the diff
  ;; over identical inputs.
  ;;
  ;; Each `:rf/epoch-record` carries a stable `:epoch-id` plus
  ;; immutable `:db-before` / `:db-after` snapshots — keying on
  ;; `:epoch-id` is sufficient (the same id never refers to two
  ;; different db-pairs). The cache is pruned to the current
  ;; history's id-set on every read so it cannot grow beyond the
  ;; retained epoch-history depth (which is itself bounded by the
  ;; framework's epoch-record ring buffer).
  ;;
  ;; The cache is a private atom rather than a `with-meta`
  ;; computation on the sub because reagent-sub identity caching
  ;; only short-circuits when the underlying tuple is
  ;; `identical?`-equal — with a freshly-vec'd `history` on every
  ;; epoch settle the tuple shifts, so the sub re-runs even when
  ;; the selected epoch hasn't changed. The atom-keyed cache
  ;; collapses that to a map lookup.
  (let [diff-cache (atom {})]
    (rf/reg-sub :rf.causa/selected-epoch-diff
      :<- [:rf.causa/epoch-history]
      :<- [:rf.causa/selected-epoch-id]
      (fn [[history selected-id] _query]
        (let [record (if selected-id
                       (tt-helpers/find-epoch-in-history history selected-id)
                       (peek (vec history)))]
          (when record
            (let [epoch-id (:epoch-id record)
                  ;; Cheap hit path — most renders.
                  cached   (get @diff-cache epoch-id ::miss)]
              (if (not= ::miss cached)
                cached
                ;; Miss — compute, store, and prune. Pruning keeps
                ;; the cache size bounded by the live epoch-history
                ;; depth without needing an LRU ordering.
                (let [diff   (h/diff-paths (:db-before record)
                                          (:db-after  record))
                      live   (into #{} (map :epoch-id) history)]
                  (swap! diff-cache
                         (fn [m]
                           (-> m
                               (select-keys live)
                               (assoc epoch-id diff))))
                  diff))))))))

  ;; The pinned-slices store — `{frame-id [path-1 path-2 ...]}`.
  ;; Separate from Phase 3's :pin-store (which pins whole epoch
  ;; snapshots); this is per-frame slice-path pinning.
  (rf/reg-sub :rf.causa/pinned-slices-store
    (fn [db _query]
      (get db :pinned-slices-store {})))

  ;; The live-derefed pinned slices for the current target-frame.
  ;; Each entry is `{:path <vec> :value <current-value>}`.
  (rf/reg-sub :rf.causa/pinned-slices
    :<- [:rf.causa/pinned-slices-store]
    :<- [:rf.causa/target-frame]
    :<- [:rf.causa/target-frame-db]
    (fn [[store target db] _query]
      (h/live-pinned-slices store target db)))

  ;; The 'Show me when this changed' focused path. nil when no
  ;; focus is in flight; the view falls back to the slice mini-
  ;; panels in that case.
  (rf/reg-sub :rf.causa/focused-slice-path
    (fn [db _query]
      (get db :focused-slice-path)))

  ;; The 'Show me when this changed' result — a vector of hit
  ;; maps for epochs that touched the focused path. Empty vector
  ;; when no focus is set or no epoch in the ring buffer touched
  ;; the path.
  (rf/reg-sub :rf.causa/show-me-when-this-changed-result
    :<- [:rf.causa/focused-slice-path]
    :<- [:rf.causa/epoch-history]
    (fn [[focused-path history] _query]
      (if focused-path
        (h/epochs-touching-path history focused-path)
        [])))

  ;; Top-level composite for the App-DB Diff panel. One read
  ;; produces every slot the view needs (matches the Phase-2 /
  ;; Phase-3 / Phase-4 composite pattern).
  (rf/reg-sub :rf.causa/app-db-diff
    :<- [:rf.causa/target-frame]
    :<- [:rf.causa/target-frame-db]
    :<- [:rf.causa/selected-epoch-diff]
    :<- [:rf.causa/pinned-slices]
    :<- [:rf.causa/focused-slice-path]
    :<- [:rf.causa/show-me-when-this-changed-result]
    :<- [:rf.causa/epoch-history]
    (fn [[target db diff-triples pinned focused-path focused-hits history]
         _query]
      (let [history-empty? (empty? history)
            {:keys [reserved non-reserved]}
            (h/partition-reserved (or diff-triples []))]
        {:target-frame          target
         :history-empty?        history-empty?
         :changed-non-reserved  non-reserved
         ;; The [runtime] group always renders the current `:rf/*`
         ;; slot contents off the current db as a one-line summary
         ;; (path + value). Per spec §Reserved-keys group the group
         ;; is informational — it's not gated on whether THIS epoch
         ;; touched a reserved key; the programmer reads it to
         ;; orient against the runtime's state.
         :changed-reserved      (h/reserved-summary db)
         :pinned-slices         pinned
         :focused-path          focused-path
         :focused-hits          focused-hits})))

  ;; ---- Phase 5 (rf2-jps1o) — App-DB Diff events ----------------

  ;; Pin a slice path to the per-frame pinned-slices store. Per
  ;; spec §Pinned slices. Duplicates are dropped at the helper
  ;; layer (re-pin is a no-op).
  (rf/reg-event-db :rf.causa/pin-slice
    (fn [db [_ path]]
      (let [target (get db :target-frame defaults/default-target-frame)]
        (update db :pinned-slices-store
                h/pin-path target path))))

  (rf/reg-event-db :rf.causa/unpin-slice
    (fn [db [_ path]]
      (let [target (get db :target-frame defaults/default-target-frame)]
        (update db :pinned-slices-store
                h/unpin-path target path))))

  ;; Replace the per-frame pin order with `new-order`. The caller
  ;; (the drag-reorder UI) computes the permutation.
  (rf/reg-event-db :rf.causa/reorder-pinned-slices
    (fn [db [_ new-order]]
      (let [target (get db :target-frame defaults/default-target-frame)]
        (update db :pinned-slices-store
                h/reorder-paths target new-order))))

  ;; Set the 'Show me when this changed' focused path. The
  ;; :rf.causa/show-me-when-this-changed-result sub re-fires
  ;; against the new focus and the panel switches into result-list
  ;; mode (per spec §'Show me when this changed').
  (rf/reg-event-db :rf.causa/focus-slice-path
    (fn [db [_ path]]
      (assoc db :focused-slice-path path)))

  (rf/reg-event-db :rf.causa/clear-slice-focus
    (fn [db _event]
      (dissoc db :focused-slice-path)))

  ;; The clipboard fx — best-effort write via the browser
  ;; clipboard API. On non-browser targets (Node test, JVM) the
  ;; effect is a no-op; tests assert the fx fires, not the OS-side
  ;; outcome. Per re-frame v2's reg-fx contract: (fn [ctx args] ...).
  (rf/reg-fx :rf.causa.fx/copy-to-clipboard
    (fn [_ctx {:keys [text]}]
      (try
        (when (and (exists? js/navigator)
                   (.-clipboard js/navigator))
          (.writeText (.-clipboard js/navigator) (str text)))
        (catch :default _ nil))))

  (rf/reg-event-fx :rf.causa/copy-value-to-clipboard
    (fn [_ctx [_ value]]
      {:fx [[:rf.causa.fx/copy-to-clipboard {:text (pr-str value)}]]}))

  (rf/reg-event-fx :rf.causa/copy-path-to-clipboard
    (fn [_ctx [_ path]]
      {:fx [[:rf.causa.fx/copy-to-clipboard {:text (pr-str path)}]]})))
