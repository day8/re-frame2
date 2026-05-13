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
            [day8.re-frame2-causa.panels.app-db-diff-helpers :as h]))

;; ---- design tokens (mirrors event_detail.cljs / time_travel.cljs) -------

(def ^:private tokens
  "Subset of shell.cljs's dark-theme tokens used by this panel."
  {:bg-1            "#15171B"
   :bg-2            "#1B1E24"
   :bg-3            "#232730"
   :bg-active       "#2A2F3D"
   :border-subtle   "#232730"
   :border-default  "#2F3441"
   :text-primary    "#E8EAF0"
   :text-secondary  "#A8AEC0"
   :text-tertiary   "#6B7080"
   :accent-violet   "#7C5CFF"
   :cyan            "#43C3D0"
   :green           "#4ADE80"
   :yellow          "#FBBF24"
   :red             "#F87171"
   :magenta         "#E879F9"})

(def ^:private mono-stack
  "JetBrains Mono stack per spec/007-UX-IA.md §Typography."
  "JetBrains Mono, ui-monospace, SF Mono, Menlo, monospace")

(def ^:private sans-stack
  "Inter stack per spec/007-UX-IA.md §Typography."
  "Inter, system-ui, -apple-system, Segoe UI, sans-serif")

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
                :on-click    #(rf/dispatch [:rf.causa/pin-slice path])
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
                :on-click    #(rf/dispatch [:rf.causa/focus-slice-path path])
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
                :on-click    #(rf/dispatch [:rf.causa/copy-path-to-clipboard path])
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
                                              after)])
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
              :on-click    #(rf/dispatch [:rf.causa/unpin-slice path])
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
        :on-click    #(rf/dispatch [:rf.causa/select-epoch epoch-id])
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
              :on-click    #(rf/dispatch [:rf.causa/clear-slice-focus])
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

(defn app-db-diff-view
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
