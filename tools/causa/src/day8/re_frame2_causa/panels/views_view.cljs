(ns day8.re-frame2-causa.panels.views-view
  "Root view composition for the Views panel (rf2-21ob3).

  Pure hiccup per the Causa convention; consumed by `views/Panel`
  inside the `:rf/causa` frame-provider in `shell.cljs`. Per spec
  §Renderer the panel uses the cljs-devtools-shaped renderers from
  `theme/data_inspector.cljs` (rf2-x9fzk) — `inspect-inline` for
  one-line list cells; `inspect` for the click-expand hero inside
  the inline drilldown.

  ## What ships v1

  - Three-group rendering (mounted / re-rendered / unmounted) per
    spec §Three-group layout.
  - Re-rendered group two-column 'Invalidated by' layout (spec §R3-D).
  - Grid-explosion clustering (spec §Grid-explosion clustering) with
    `[Expand cluster ▾]` affordance for ≥ 50-render clusters.
  - Heatmap-mode toggle + segment bar (spec §Heatmap mode, §R3-E).
  - Per-row inline expansion (spec §R3-F / spec §Per-component
    drilldown) — single-column block under the row with the raw
    `:rf/epoch-record` `:renders` entry rendered via `inspect`.

  ## What's stubbed v1

  - Per-render props-diff (spec §Per-component drilldown → Headline
    content) — the runtime does not yet capture per-render
    `:props-before` / `:props-after`. The drilldown surfaces the
    raw render entry via `inspect` until that capture lands.
  - Render-tracker `:owning-frame` filter (spec §Isolation invariant
    I3) — render entries do not carry the tag yet (P17 pending);
    the spine's frame selection still propagates so the panel is
    forward-compatible.
  - Per-component sub-row sub-status decoration (spec §Sub-status
    legibility) — uses a single rendering for all statuses; the
    detailed taxonomy comes back online once `sub-cache` per-frame
    metadata threads through the new spine.
  - React Fiber metadata (spec §Per-component drilldown → React
    Fiber block) — out of scope v1; collapse-by-default placeholder."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.views-helpers :as h]
            ;; rf2-x9fzk landed on origin/main during this bead's worktree
            ;; lifetime. The data inspector replaces v1's pr-str
            ;; placeholders for sub return values + cluster instance
            ;; payloads. Per spec §Renderer the panel uses
            ;; `inspect-inline` for the one-line list cells and `inspect`
            ;; for the click-expand hero in the inline drilldown.
            [day8.re-frame2-causa.panels.views-sub-diff :as sub-diff]
            [day8.re-frame2-causa.theme.data-inspector :as inspector]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

;; ---- styling primitives -------------------------------------------------

(def ^:private group-section-style
  {:padding "12px 16px 4px 16px"
   :font-size "11px"
   :font-weight 600
   :text-transform "uppercase"
   :letter-spacing "0.05em"
   :color (:text-tertiary tokens)
   :border-bottom (str "1px solid " (:border-subtle tokens))})

(def ^:private row-style
  {:padding "8px 16px"
   :border-bottom (str "1px solid " (:border-subtle tokens))
   :font-family sans-stack
   :font-size "13px"
   :color (:text-primary tokens)
   :display "flex"
   :flex-direction "column"
   :gap "4px"})

(def ^:private row-clickable-style
  (assoc row-style :cursor "pointer"))

(def ^:private mono-style
  {:font-family mono-stack
   :font-size "12px"
   :color (:text-secondary tokens)})

(def ^:private trigger-glyph-style
  {:color (:accent-violet tokens)
   :font-weight 700
   :margin-right "6px"})

(def ^:private non-trigger-glyph-style
  {:color (:text-tertiary tokens)
   :margin-right "6px"})

;; ---- small helpers ------------------------------------------------------

(defn- inspect-inline
  "Per spec §Renderer — one-line tail-elided rendering for compact
  list cells. Delegates to the rf2-x9fzk data inspector's
  `inspect-inline` so sub return values render with the same
  coloured + sentinel-aware chrome the Event tab uses."
  [v]
  (inspector/inspect-inline v))

(defn- format-ms
  [ms]
  (cond
    (nil? ms)       "—"
    (< ms 1)        (str (.toFixed (* ms 1000) 0) "µs")
    (< ms 10)       (str (.toFixed ms 2) "ms")
    :else           (str (.toFixed ms 1) "ms")))

(defn- format-sub-id
  [sub-id]
  (cond
    (= ::h/parent-forced sub-id) "<parent re-rendered>"
    (keyword? sub-id) (str sub-id)
    :else (pr-str sub-id)))

(defn- row-key
  "Stable React key for a row. Cluster rows use the (view-id,
  triggered-by) tuple printed; singles use the full render-key."
  [item]
  (case (:kind item)
    :single  (pr-str (:render-key (:render item)))
    :cluster (str "cluster:" (pr-str [(:view-id item) (:triggered-by item)]))))

;; ---- heatmap ------------------------------------------------------------

(defn- segment-color
  "Per spec §Heatmap mode — segment colour shades cool → warm by ms
  cost. v1 uses three tier colours from the existing palette;
  finer-grained tiering rides the future heatmap-tier helper."
  [fraction]
  (cond
    (>= fraction 0.40) (:red tokens)
    (>= fraction 0.20) (:orange tokens)
    (>= fraction 0.05) (:yellow tokens)
    :else              (:cyan tokens)))

(defn- segment-label
  [seg]
  (case (:kind seg)
    :rest      (str "<rest> · "
                    (.toFixed (* 100 (:fraction seg)) 0) "% · "
                    (format-ms (:total-ms seg)))
    :component (str (h/format-view-id (:view-id seg)) " · "
                    (.toFixed (* 100 (:fraction seg)) 0) "% · "
                    (format-ms (:total-ms seg)))))

(defn- heatmap-bar
  [segments _cascade-ms]
  [:div {:data-testid "rf-causa-views-heatmap"
         :style {:display "flex"
                 :width "100%"
                 :height "48px"
                 :background (:bg-1 tokens)
                 :border (str "1px solid " (:border-subtle tokens))
                 :overflow "hidden"
                 :border-radius "4px"}}
   (for [seg segments]
     ^{:key (str (:view-id seg))}
     [:div {:data-testid (str "rf-causa-views-heatmap-segment-"
                              (if (= ::h/rest (:view-id seg))
                                "rest"
                                (h/format-view-id (:view-id seg))))
            :on-click #(when (not= ::h/rest (:view-id seg))
                         (rf/dispatch [:rf.causa/views-segment-click
                                       (:view-id seg)]))
            :title (segment-label seg)
            :style {:width (str (* 100 (:fraction seg)) "%")
                    :background (segment-color (:fraction seg))
                    :cursor (if (= ::h/rest (:view-id seg))
                              "default" "pointer")
                    :display "flex"
                    :align-items "center"
                    :justify-content "center"
                    :color "#0E0F12"
                    :font-family sans-stack
                    :font-size "10px"
                    :font-weight 600
                    :overflow "hidden"
                    :text-overflow "ellipsis"
                    :white-space "nowrap"
                    :padding "0 4px"
                    :border-right (str "1px solid " (:bg-1 tokens))}}
      (when (> (:fraction seg) 0.04)
        (h/format-view-id (:view-id seg)))])])

;; ---- invalidated-by list (Re-rendered) ---------------------------------

(defn- invalidated-by-list
  [invalidated-by]
  [:div {:data-testid "rf-causa-views-invalidated-by"
         :style {:display "flex"
                 :flex-direction "column"
                 :gap "2px"}}
   (for [[i row] (map-indexed vector invalidated-by)]
     ^{:key i}
     [:div {:style mono-style}
      [:span {:style (if (:trigger? row)
                       trigger-glyph-style
                       non-trigger-glyph-style)}
       (if (:trigger? row) "✱" "·")]
      [:span (format-sub-id (:sub-id row))]
      (when (:clustered? row)
        [:span {:style {:margin-left "6px"
                        :color (:text-tertiary tokens)
                        :font-size "11px"}}
         "(args vary per instance)"])])])

;; ---- per-row body builders ---------------------------------------------

(defn- relevant-sub-diff-records
  "Filter the cascade-wide sub-diff records to subs in this row's
  `invalidated-by` list. Sub-id match — args (`:query-v`) might
  differ across consumers but the most common case is identical args
  per row. Phase 2 v1 ships the cascade-wide records pre-filtered to
  the row's invalidating subs; per-row sub-attribution lands when the
  render-tracker emits `:owning-frame` (P17 wave)."
  [records invalidated-by]
  (let [wanted (set (keep :sub-id invalidated-by))]
    (filterv (fn [rec] (contains? wanted (:sub-id rec))) records)))

(defn- expanded-block
  "Inline expansion content per spec §Per-component drilldown — single-
  column block placed BELOW the row. v1 ships the structural
  scaffolding (props-diff section header, subs-consumed list,
  reason); the props-diff and React-fiber slots stay placeholder
  until `theme/data_inspector.cljc` lands.

  ## Sub-output diff (rf2-xjhhp Phase 2)

  Below the structural scaffolding we mount the sub-output structural
  diff for the row's invalidating subs. Records come from
  `:rf.causa/views-sub-diff-for-focused-event`; rendering goes
  through the Phase 1 sections-per-cluster engine
  (`day8.re-frame2-causa.diff.render/render-sections`) via the
  thin `views-sub-diff/drilldown` wrapper."
  [item]
  (let [r (:render item)
        invalidated-by (:invalidated-by item)
        sub-diff-data @(rf/subscribe [:rf.causa/views-sub-diff-for-focused-event])
        sub-records   (relevant-sub-diff-records (:records sub-diff-data)
                                                 invalidated-by)]
    [:div {:data-testid "rf-causa-views-row-expanded"
           :style {:padding "8px 16px 12px 32px"
                   :background (:bg-1 tokens)
                   :border-bottom (str "1px solid " (:border-subtle tokens))
                   :font-family sans-stack
                   :font-size "12px"
                   :color (:text-secondary tokens)
                   :display "flex"
                   :flex-direction "column"
                   :gap "8px"}}
     [:div [:strong "Render entry (raw)"]
      ;; Per spec §Per-component drilldown §Headline content the
      ;; expansion's headline is the props diff — but per-render props
      ;; capture is a render-tracker extension that hasn't landed yet
      ;; (P17 wave per `ai/findings/2026-05-17-causa-consolidated-design.md`
      ;; §14). Until then we expose the raw `:rf/epoch-record` `:renders`
      ;; entry for the row via the rf2-x9fzk inspector so the user can
      ;; click through to the render-key, triggered-by, elapsed-ms.
      ;; Props-diff renders as a structured tree the moment the runtime
      ;; emits per-render `:props-before` / `:props-after` slots.
      (inspector/inspect r (str "views/" (pr-str (:render-key r))))]
     (when (seq invalidated-by)
       [:div [:strong "Subs consumed"]
        (invalidated-by-list invalidated-by)])
     ;; rf2-xjhhp Phase 2 — sub-output structural diff for the row's
     ;; invalidating subs. The renderer ships an empty-state chip when
     ;; no records remain after filtering (e.g. for parent-forced
     ;; re-renders where no sub invalidated the row).
     [:div [:strong "Sub-output diff"]
      (sub-diff/drilldown sub-records)]
     [:div [:strong "Reason"]
      [:p {:style {:margin "4px 0"}}
       (cond
         (:triggered-by r)
         (str "Re-render triggered by " (format-sub-id (:triggered-by r)))
         :else
         "Parent re-rendered → forced child re-render.")]]
     [:div [:strong "Render timing"]
      [:p {:style {:margin "4px 0"}}
       (str "elapsed " (format-ms (:elapsed-ms r))
            " · (mount/commit phase data ships when React Profiler available)")]]]))

(defn- single-row
  "One render row in either Mounted, Re-rendered, or Unmounted. The
  two-column layout for Re-rendered (spec §Per-row content (Re-
  rendered)) renders the `invalidated-by` list in the right column."
  [item group expanded?]
  (let [r          (:render item)
        view-id    (h/render-key->view-id (:render-key r))
        invalidated-by (:invalidated-by item)
        struck?    (= group :unmounted)]
    [:div {:data-testid (str "rf-causa-views-row-" (name group))
           :on-click    #(rf/dispatch [:rf.causa/views-toggle-row
                                       (pr-str (:render-key r))])
           :style       (cond-> row-clickable-style
                          struck? (assoc :text-decoration "line-through"
                                         :color (:text-tertiary tokens)))}
     [:div {:style {:display "flex"
                    :flex-direction "row"
                    :gap "12px"
                    :align-items "flex-start"}}
      [:span {:style {:color (:accent-violet tokens) :font-weight 700}}
       (get h/group-glyph group "●")]
      [:div {:style {:flex (if (= :rendered group) "0 0 40%" "1 1 auto")
                     :min-width 0}}
       [:div {:style {:display "flex" :align-items "center" :gap "8px"}}
        [:span {:style {:font-weight 600}}
         (str "<" (h/format-view-id view-id) ">")]
        [:span {:style {:color (:text-tertiary tokens) :font-size "11px"}}
         (format-ms (:elapsed-ms r))]
        [:span {:style {:color (:text-tertiary tokens) :font-size "11px"}}
         (str "▾")]]
       (when (and (= :rendered group) (= 1 (count invalidated-by)))
         [:div {:style {:color (:text-tertiary tokens) :font-size "11px"
                        :margin-top "2px"}}
          "Click for inline drilldown"])]
      (when (= :rendered group)
        [:div {:style {:flex "1 1 60%" :min-width 0
                       :border-left (str "1px solid " (:border-subtle tokens))
                       :padding-left "12px"}}
         [:div {:style {:color (:text-tertiary tokens)
                        :font-size "11px"
                        :margin-bottom "4px"}}
          "Invalidated by"]
         (invalidated-by-list invalidated-by)])]
     (when expanded?
       (expanded-block item))]))

(defn- cluster-row
  [item group expanded-cluster?]
  (let [{:keys [view-id triggered-by count total-ms avg-ms p95-ms]} item
        ckey   (str "cluster:" (pr-str [view-id triggered-by]))]
    [:div {:data-testid (str "rf-causa-views-cluster-" (name group))
           :style row-style}
     [:div {:style {:display "flex" :gap "12px" :align-items "flex-start"}}
      [:span {:style {:color (:yellow tokens) :font-weight 700}}
       (get h/group-glyph group "◐")]
      [:div {:style {:flex "1 1 auto" :min-width 0}}
       [:div {:style {:display "flex" :align-items "center" :gap "8px"
                      :flex-wrap "wrap"}}
        [:span {:style {:font-weight 600}}
         (str "<" (h/format-view-id view-id) "> × " count " (clustered)")]
        [:span {:style {:color (:text-tertiary tokens) :font-size "11px"}}
         (str (format-ms total-ms) " total · "
              (format-ms avg-ms) " avg · "
              (format-ms p95-ms) " p95")]]
       [:div {:style mono-style}
        [:span {:style trigger-glyph-style} "✱"]
        [:span (format-sub-id triggered-by)]
        [:span {:style {:margin-left "8px" :color (:text-tertiary tokens)
                        :font-size "11px"}}
         "(args vary per instance)"]]
       [:button {:data-testid (str "rf-causa-views-cluster-toggle-"
                                   (h/format-view-id view-id))
                 :on-click #(rf/dispatch
                             [:rf.causa/views-toggle-cluster ckey])
                 :style {:background "transparent"
                         :border (str "1px solid " (:border-default tokens))
                         :color (:text-secondary tokens)
                         :padding "2px 8px"
                         :font-size "11px"
                         :cursor "pointer"
                         :margin-top "6px"
                         :border-radius "3px"}}
        (if expanded-cluster? "Collapse cluster ▴" "Expand cluster ▾")]
       (when expanded-cluster?
         [:div {:data-testid "rf-causa-views-cluster-instances"
                :style {:margin-top "8px"
                        :padding "8px"
                        :background (:bg-1 tokens)
                        :max-height "240px"
                        :overflow-y "auto"
                        :font-family mono-stack
                        :font-size "11px"}}
          (for [r (take 200 (:renders item))]
            ^{:key (pr-str (:render-key r))}
            [:div {:style {:padding "2px 0"}}
             (str (h/format-view-id (h/render-key->view-id (:render-key r)))
                  " " (pr-str (h/render-key->instance-token (:render-key r)))
                  "  → "
                  (inspect-inline (:triggered-by r))
                  "  · "
                  (format-ms (:elapsed-ms r)))])
          (when (> (count (:renders item)) 200)
            [:div {:style {:color (:text-tertiary tokens) :margin-top "4px"}}
             (str "… (" (count (:renders item))
                  " total; first 200 shown — virtualisation pending)")])])]]]))

(defn- group-section
  "One of the three named groups (mounted / re-rendered / unmounted).
  Hides itself when the group has zero items, per spec §Empty
  states the parent renders a panel-wide message in that case."
  [group items expanded-rows expanded-clusters]
  (when (seq items)
    [:section {:data-testid (str "rf-causa-views-group-" (name group))
               :style {:display "flex" :flex-direction "column"}}
     [:header {:style group-section-style}
      (str (name group) " this cascade (" (count items) ")")]
     (for [item items]
       (let [rk (row-key item)]
         ^{:key rk}
         (case (:kind item)
           :single  (single-row item group (contains? expanded-rows rk))
           :cluster (cluster-row item group (contains? expanded-clusters rk)))))]))

;; ---- chrome -------------------------------------------------------------

(defn- header-block
  [data]
  [:header {:style {:padding "16px 16px 8px 16px"}}
   [:h1 {:style {:font-size "16px" :font-weight 600 :margin 0
                 :color (:text-primary tokens)}}
    "Views"]
   [:p {:style {:font-size "12px" :color (:text-tertiary tokens)
                :margin "4px 0 0 0"
                :font-family sans-stack}}
    (str "Per-view rows: mounted / re-rendered / unmounted. "
         "Subs nest under each view (spec/012). "
         "Cascade ms: " (format-ms (:cascade-ms (:totals data))))]
   (when-let [frame (:frame data)]
     [:p {:style {:font-size "11px" :color (:text-tertiary tokens)
                  :margin "2px 0 0 0"}}
      (str "Frame: " frame
           (when-let [did (:dispatch-id data)]
             (str " · cascade " (pr-str did))))])])

(defn- bottom-controls
  [data]
  (let [heatmap?  (boolean (:heatmap? data))
        cf        (:component-filter data)
        auto?     (boolean (:auto-suggest-heatmap? data))]
    [:footer {:data-testid "rf-causa-views-controls"
              :style {:padding "8px 16px"
                      :border-top (str "1px solid " (:border-subtle tokens))
                      :display "flex"
                      :gap "16px"
                      :align-items "center"
                      :font-size "11px"
                      :color (:text-secondary tokens)}}
     [:label {:style {:cursor "pointer" :display "flex"
                      :gap "6px" :align-items "center"}}
      [:input {:type "checkbox"
               :data-testid "rf-causa-views-heatmap-toggle"
               :checked heatmap?
               :on-change #(rf/dispatch [:rf.causa/views-toggle-heatmap])}]
      "Heatmap mode"
      (when auto?
        [:span {:style {:color (:yellow tokens) :margin-left "4px"}}
         "(auto-suggested)"])]
     (when cf
       [:button {:data-testid "rf-causa-views-clear-filter"
                 :on-click #(rf/dispatch
                             [:rf.causa/views-set-component-filter nil])
                 :style {:background "transparent"
                         :border (str "1px solid " (:border-default tokens))
                         :color (:text-secondary tokens)
                         :padding "2px 8px"
                         :font-size "11px"
                         :cursor "pointer"
                         :border-radius "3px"}}
        (str "Filtered: " (h/format-view-id cf) " ×")])]))

(defn- empty-state
  [data]
  [:div {:data-testid "rf-causa-views-empty"
         :style {:padding "16px"
                 :color (:text-tertiary tokens)
                 :font-family sans-stack
                 :font-size "13px"}}
   (cond
     (nil? (:current (:focus data)))
     [:p "Select an event in the list to inspect its views."]

     :else
     [:p "No views rendered this cascade. (The handler made no app-db "
      "changes that any subscribed view depends on.)"])])

;; ---- panel root --------------------------------------------------------

(defn views-panel
  "Plain Reagent fn — invoked from `views/Panel` (the public facade
  reg-view) via a function call so the React-context frame tier
  resolves to `:rf/causa` inside the leaf's subscribes (per the same
  facade convention `subscriptions.cljs` documents)."
  []
  (let [data @(rf/subscribe [:rf.causa/views-data])
        groups (:groups data)
        expanded-rows (or (:expanded-rows data) #{})
        expanded-clusters (or (:expanded-clusters data) #{})]
    [:section {:data-testid "rf-causa-views"
               :style {:height "100%"
                       :display "flex"
                       :flex-direction "column"
                       :background (:bg-2 tokens)
                       :color (:text-primary tokens)
                       :font-family sans-stack
                       :font-size "14px"}}
     (header-block data)
     [:div {:style {:flex 1 :overflow "auto"}}
      (cond
        (not (:has-cascade? data))
        (empty-state data)

        (:heatmap? data)
        [:div {:style {:padding "12px 16px"}}
         (heatmap-bar (:segments (:heatmap data))
                      (:total-ms (:heatmap data)))
         [:p {:style {:margin "8px 0 0 0" :font-size "11px"
                      :color (:text-tertiary tokens)}}
          "Click a segment to filter the three groups and exit heatmap "
          "mode. Hover for label."]]

        (zero? (+ (count (:mounted groups))
                  (count (:rendered groups))
                  (count (:unmounted groups))))
        (empty-state data)

        :else
        [:div {:data-testid "rf-causa-views-groups"
               :style {:display "flex" :flex-direction "column"}}
         (for [g h/group-order]
           ^{:key g}
           (group-section g (get groups g) expanded-rows expanded-clusters))])]
     (bottom-controls data)]))
