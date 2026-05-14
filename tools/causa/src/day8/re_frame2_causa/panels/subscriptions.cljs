(ns day8.re-frame2-causa.panels.subscriptions
  "Subscriptions panel — Phase 5 (rf2-x0f5v, parent rf2-5aw5v).

  Per `tools/causa/spec/012-Subscriptions.md` the Subscriptions panel
  answers the five-canonical-questions framing's #3 and #4:

    3. **Why is this subscription returning the wrong value?**
    4. **Why is this view re-rendering?**

  The panel ships two affordances that did not exist in re-frame-10x:

    - A **five-status badge taxonomy** — `:fresh`, `:re-running`,
      `:invalidated`, `:cached-no-watcher`, `:error` — paired with
      a shape glyph + tooltip per spec §\"Colour is never alone\".
    - An **invalidation-chain affordance** — for any sub that
      re-ran this epoch, a one-click walk **up** the input chain
      to the originating `app-db` slice change.

  ## TanStack-Query peer landscape

  The badge taxonomy intentionally mirrors TanStack Query DevTools'
  vocabulary (fresh / fetching / inactive / paused / stale) but
  prunes to re-frame's value-equality-driven cache: there is no
  `:stale` (per spec §\"Stale vs invalidated\") because the runtime
  has no wall-clock aging. The shape-paired colour discipline + the
  filter-chip header + the in-place re-running animation are the
  TanStack hallmarks Causa adopts.

  ## Pure hiccup (rf2-tijr)

  Same contract as every other Causa panel — the view is pure hiccup,
  no Reagent / UIx / Helix references. Frame isolation comes from the
  enclosing `[rf/frame-provider {:frame :rf/causa}]` in `shell.cljs`.
  Every `subscribe` / `dispatch` here resolves to `:rf/causa`.

  ## Helpers

  All pure-data logic — `project-rows`, `compute-status`, `compute-
  chain`, `status-counts` — lives in `subscriptions_helpers.cljc` so
  the algebra runs under the JVM unit-test target."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.subscriptions-helpers :as h]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

;; ---- pure helpers --------------------------------------------------------

(defn- status-colour
  "Resolve a status's colour token to its hex via the panel's
  `tokens` map. Single point of indirection so the test suite can
  assert against the token without touching the hex."
  [status]
  (let [tok (get h/status->token status :text-tertiary)]
    (get tokens tok (:text-tertiary tokens))))

;; ---- status badge --------------------------------------------------------

(defn- status-badge
  "Per spec §\"Colour is never alone\" — the canonical badge surface.
  Colour + shape + tooltip-label, 14px on cosy density, sitting at
  the left margin of every sub row. `status` is one of
  `#{:fresh :re-running :invalidated :cached-no-watcher :error}`."
  [status]
  (let [glyph   (get h/status->glyph status "?")
        colour  (status-colour status)
        tooltip (get h/status->tooltip status "Unknown")]
    [:span {:data-testid     (str "rf-causa-sub-badge-" (name status))
            :title           tooltip
            :aria-label      tooltip
            :style           {:display        "inline-block"
                              :width          "14px"
                              :height         "14px"
                              :line-height    "14px"
                              :text-align     "center"
                              :color          colour
                              :font-family    mono-stack
                              :font-size      "14px"
                              :font-weight    700
                              :margin-right   "8px"
                              :flex-shrink    0}}
     glyph]))

;; ---- filter chips --------------------------------------------------------

(defn- filter-chip
  "One status filter chip in the header row. Per spec §Filtering and
  grouping — clicking toggles the chip's status in the filter set.
  Active chips render with the status colour + a filled background;
  inactive chips render with the status colour outline only."
  [status active? count]
  (let [colour  (status-colour status)
        glyph   (get h/status->glyph status)
        tooltip (get h/status->tooltip status)]
    [:button {:data-testid (str "rf-causa-sub-filter-" (name status))
              :on-click    #(rf/dispatch [:rf.causa/toggle-sub-filter status])
              :title       tooltip
              :style       {:display        "inline-flex"
                            :align-items    "center"
                            :gap            "4px"
                            :padding        "2px 8px"
                            :margin-right   "6px"
                            :border-radius  "10px"
                            :background     (if active?
                                              "rgba(124, 92, 255, 0.18)"
                                              "transparent")
                            :border         (str "1px solid "
                                                 (if active?
                                                   colour
                                                   (:border-default tokens)))
                            :color          (if active?
                                              (:text-primary tokens)
                                              (:text-secondary tokens))
                            :cursor         "pointer"
                            :font-family    sans-stack
                            :font-size      "11px"
                            :font-weight    (if active? 600 400)}}
     [:span {:style {:color colour :font-family mono-stack :font-weight 700}}
      glyph]
     [:span tooltip]
     (when count
       [:span {:style {:color    (:text-tertiary tokens)
                       :font-family mono-stack
                       :font-size "10px"}}
        (str "(" count ")")])]))

(defn- filter-header
  "Header row carrying the five filter chips + the result count.
  Per spec §Filtering and grouping."
  [active-filters status-counts total]
  [:div {:data-testid "rf-causa-subscriptions-filters"
         :style       {:padding "8px 12px"
                       :display "flex"
                       :align-items "center"
                       :flex-wrap "wrap"
                       :gap "4px"
                       :border-bottom (str "1px solid " (:border-subtle tokens))}}
   (into [:div {:style {:flex 1 :display "flex" :flex-wrap "wrap"}}]
         (for [s h/statuses]
           ^{:key s}
           (filter-chip s (contains? active-filters s) (get status-counts s 0))))
   [:span {:style {:color     (:text-tertiary tokens)
                   :font-family mono-stack
                   :font-size "11px"
                   :margin-left "8px"}}
    (str total " sub" (if (= 1 total) "" "s"))]])

;; ---- sub row -------------------------------------------------------------

(defn- layer-pill
  "Compact layer indicator — `L1`, `L2`, `L3+`. nil-safe."
  [layer]
  (let [label (cond
                (nil? layer)  "?"
                (<= layer 1)  "L1"
                (= layer 2)   "L2"
                :else         "L3+")]
    [:span {:style {:display       "inline-block"
                    :padding       "1px 6px"
                    :margin-right  "8px"
                    :border-radius "3px"
                    :background    (:bg-3 tokens)
                    :color         (:text-secondary tokens)
                    :font-family   mono-stack
                    :font-size     "10px"}}
     label]))

(defn- sub-row
  "One row in the sub list. Clicking the row fires
  `:rf.causa/select-sub` so the chain affordance can open against
  this sub. Right-click would route through a context menu; v1
  exposes the chain via the keyboard shortcut `i` + a small button
  on the row."
  [{:keys [query-v sub-id status layer ref-count recomputed?] :as _row}
   selected?]
  [:li {:data-testid (str "rf-causa-sub-row-"
                          (h/format-sub-id sub-id))
        :on-click   #(rf/dispatch [:rf.causa/select-sub query-v])
        :style      {:display       "flex"
                     :align-items   "center"
                     :padding       "6px 12px"
                     :background    (if selected?
                                      (:bg-active tokens)
                                      "transparent")
                     :border-bottom (str "1px solid " (:border-subtle tokens))
                     :cursor        "pointer"
                     :font-family   mono-stack
                     :font-size     "13px"
                     :color         (:text-primary tokens)}}
   (status-badge status)
   (layer-pill layer)
   [:span {:style {:flex 1
                   :min-width 0
                   :overflow "hidden"
                   :text-overflow "ellipsis"
                   :white-space "nowrap"}}
    (h/format-query-v query-v)]
   [:span {:style {:color (:text-tertiary tokens)
                   :font-size "11px"
                   :margin-left "8px"}}
    (str "refs " ref-count)]
   (when recomputed?
     [:span {:data-testid "rf-causa-sub-row-recomputed"
             :style {:color (:cyan tokens)
                     :font-size "10px"
                     :margin-left "8px"
                     :text-transform "uppercase"
                     :letter-spacing "0.5px"}}
      "ran"])
   [:button {:data-testid (str "rf-causa-sub-row-chain-"
                               (h/format-sub-id sub-id))
             :on-click (fn [e]
                         (.stopPropagation e)
                         (rf/dispatch [:rf.causa/select-sub query-v])
                         (rf/dispatch [:rf.causa/show-invalidation-chain query-v]))
             :title    "Show invalidation chain"
             :style    {:margin-left "8px"
                        :background "transparent"
                        :border (str "1px solid " (:border-default tokens))
                        :color (:text-secondary tokens)
                        :padding "1px 6px"
                        :border-radius "3px"
                        :cursor "pointer"
                        :font-family sans-stack
                        :font-size "10px"}}
    "chain"]])

;; ---- sub list ------------------------------------------------------------

(defn- sub-list
  "The default list view per spec §Where badges appear. Renders one
  row per sub, badge at the left margin, in the canonical sort
  order (errors first, then re-running, etc.)."
  [rows selected-query-v]
  (if (empty? rows)
    [:div {:data-testid "rf-causa-subscriptions-empty-rows"
           :style       {:padding "16px"
                         :color   (:text-tertiary tokens)
                         :font-family sans-stack
                         :font-size "13px"}}
     "No subscriptions match the current filter."]
    (into [:ul {:data-testid "rf-causa-subscriptions-list"
                :style {:list-style "none"
                        :margin     0
                        :padding    0
                        :background (:bg-2 tokens)}}]
          (for [row rows]
            ^{:key (h/format-query-v (:query-v row))}
            (sub-row row (= (:query-v row) selected-query-v))))))

;; ---- invalidation-chain view --------------------------------------------

(defn- chain-link
  "One link in the invalidation chain — one of the focused sub's
  inputs that re-ran this cascade. Per spec §What the affordance
  renders the link carries the input's query-v + its layer + the
  link-reason (`input changed` between sub layers; `slice changed`
  from layer-1 down to the `app-db` path)."
  [{:keys [query-v status layer link-reason]}]
  [:li {:data-testid (str "rf-causa-chain-link-"
                          (h/format-sub-id (first query-v)))
        :style       {:padding "6px 12px"
                      :display "flex"
                      :align-items "center"
                      :border-bottom (str "1px solid " (:border-subtle tokens))
                      :font-family mono-stack
                      :font-size "12px"
                      :color (:text-primary tokens)}}
   (status-badge status)
   (layer-pill layer)
   [:span {:style {:flex 1}} (h/format-query-v query-v)]
   [:span {:style {:color (:text-tertiary tokens)
                   :font-size "10px"
                   :font-family sans-stack
                   :text-transform "uppercase"
                   :letter-spacing "0.5px"
                   :margin-left "8px"}}
    (case link-reason
      :slice-changed "slice changed"
      :input-changed "input changed"
      "")]])

(defn- chain-view
  "The invalidation-chain affordance — surfaces 'why did this sub
  re-run?' as a top-down list per spec §What the affordance renders.
  Renders the focused sub at the top + one level of inputs that
  re-ran this cascade + the originating `app-db` path(s) when the
  attribution lands at layer 1."
  [{:keys [focused inputs app-db-paths missing?] :as _chain}]
  [:section {:data-testid "rf-causa-subscriptions-chain"
             :style       {:border-top (str "1px solid " (:border-default tokens))
                           :background (:bg-1 tokens)}}
   [:header {:style {:padding "8px 12px"
                     :display "flex"
                     :align-items "center"
                     :justify-content "space-between"
                     :background (:bg-3 tokens)
                     :border-bottom (str "1px solid " (:border-subtle tokens))}}
    [:span {:style {:font-family sans-stack
                    :font-size   "12px"
                    :font-weight 600
                    :color       (:text-primary tokens)}}
     "Invalidation chain"]
    [:button {:data-testid "rf-causa-subscriptions-chain-close"
              :on-click    #(rf/dispatch [:rf.causa/hide-invalidation-chain])
              :style       {:background "transparent"
                            :border (str "1px solid " (:border-default tokens))
                            :color (:text-secondary tokens)
                            :padding "1px 8px"
                            :border-radius "4px"
                            :cursor "pointer"
                            :font-family sans-stack
                            :font-size "11px"}}
     "Close"]]
   (cond
     missing?
     [:div {:data-testid "rf-causa-subscriptions-chain-missing"
            :style       {:padding "12px"
                          :color (:text-tertiary tokens)
                          :font-family sans-stack
                          :font-size "12px"}}
      "Sub is not in the cache — nothing to chain. The cache may have
       evicted the entry, or the sub has never been subscribed."]

     :else
     [:div
      ;; Focused sub at the top — read 'this is the sub under
      ;; inspection'.
      [:ul {:data-testid "rf-causa-subscriptions-chain-focused"
            :style {:list-style "none" :margin 0 :padding 0}}
       (chain-link {:query-v (:query-v focused)
                    :status  (:status focused)
                    :layer   (:layer focused)
                    :link-reason (cond
                                   (or (nil? (:layer focused))
                                       (<= (:layer focused) 1))
                                   :slice-changed
                                   :else
                                   :input-changed)})]
      ;; Inputs that re-ran this cascade — one level down.
      (if (empty? inputs)
        [:div {:data-testid "rf-causa-subscriptions-chain-no-inputs"
               :style {:padding "8px 12px"
                       :color (:text-tertiary tokens)
                       :font-family sans-stack
                       :font-size "11px"}}
         "No inputs re-ran this cascade. The sub may be layer-1 — "
         "see the originating slice(s) below."]
        (into [:ul {:data-testid "rf-causa-subscriptions-chain-inputs"
                    :style {:list-style "none"
                            :margin 0
                            :padding 0
                            :border-top (str "1px dashed " (:border-default tokens))}}]
              (for [in inputs]
                ^{:key (h/format-query-v (:query-v in))}
                (chain-link in))))
      ;; Originating slice(s) — layer-1 attribution.
      (when (seq app-db-paths)
        [:div {:data-testid "rf-causa-subscriptions-chain-app-db"
               :style {:padding "8px 12px"
                       :border-top (str "1px solid " (:border-subtle tokens))
                       :font-family mono-stack
                       :font-size "12px"
                       :color (:text-primary tokens)}}
         [:div {:style {:color (:text-tertiary tokens)
                        :font-family sans-stack
                        :font-size "10px"
                        :text-transform "uppercase"
                        :letter-spacing "0.5px"
                        :margin-bottom "4px"}}
          "Originating slice(s)"]
         (into [:ul {:style {:list-style "none"
                             :margin 0
                             :padding 0}}]
               (for [path app-db-paths]
                 ^{:key (pr-str path)}
                 [:li {:style {:padding "2px 0"
                               :color (:accent-violet tokens)}}
                  (pr-str path)]))])])])

;; ---- empty state ---------------------------------------------------------

(defn- empty-state
  "Rendered when the sub-cache is empty (no subs have been
  materialised in the target frame yet). Per spec §JVM behaviour —
  the panel is CLJS-only; on JVM the cache is empty by construction
  and the same empty-state surfaces."
  []
  [:div {:data-testid "rf-causa-subscriptions-empty"
         :style       {:padding "16px"
                       :color   (:text-tertiary tokens)
                       :font-family sans-stack
                       :font-size "13px"}}
   [:p {:style {:margin "0 0 8px 0"}}
    "No subscriptions yet."]
   [:p {:style {:margin 0
                :font-size "12px"
                :color (:text-tertiary tokens)}}
    "Subscribe to a sub in the host app — the cache populates as views render."]])

;; ---- public view --------------------------------------------------------

(defn subscriptions-view
  "The Subscriptions panel's root view. Subscribes to
  `:rf.causa/subscriptions-data` and renders the filter chips +
  sub list + (when a sub is selected and the chain is open) the
  invalidation-chain affordance."
  []
  (let [{:keys [rows status-counts selected-query-v active-filters
                chain-open? chain]}
        @(rf/subscribe [:rf.causa/subscriptions-data])
        filtered-rows (h/filter-by-status rows active-filters)
        total         (count rows)]
    [:section {:data-testid "rf-causa-subscriptions"
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
       "Subscriptions"]
      [:p {:style {:font-size "12px"
                   :color     (:text-tertiary tokens)
                   :margin    "4px 0 0 0"}}
       "Cache state for every materialised sub in the target frame. "
       "Hover a badge for the status tooltip; click "
       [:em "chain"] " for the invalidation walk."]]
     (filter-header (or active-filters #{}) status-counts total)
     [:div {:style {:flex 1 :overflow "auto"}}
      (if (zero? total)
        (empty-state)
        (sub-list filtered-rows selected-query-v))]
     (when chain-open?
       (chain-view chain))]))
