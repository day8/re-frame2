(ns day8.re-frame2-causa.static.routes.browse-list
  "Flat list + search + per-row inline expand for the Static Routes
  tab (rf2-o5f5f.3).

  ## Shape (post-rf2-lq0ef + Static reshape)

  Routes are flat (no tree). Sort: `:path` ascending default. Search
  is substring across route-id + path + doc — shared with the Dynamic
  Routing lens via `routing-helpers/filter-rows`.

  Per row:
    - path / pattern (mono violet)
    - route-id keyword (mono violet, smaller)
    - source-coord chip (when present)
    - doc-string ellipsis

  Click row → inline expand surface unfolds below it
  (`row_expand/render`). No master-detail; no modal.

  ## Pure hiccup

  Same contract as every Causa view — pure hiccup, no Reagent /
  UIx / Helix references. Subscribes resolve to `:rf/causa` via the
  enclosing frame-provider in `static/shell.cljs`."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.static.routes.row-expand :as row-expand]
            [day8.re-frame2-causa.theme.tokens
             :as t
             :refer [tokens mono-stack sans-stack]]))

(defn- meta-badge
  "Single-letter badge surfacing presence of a metadata key — same
  letters as the Dynamic Routing lens (`M` `L` `T` `P`)."
  [kind]
  (let [{:keys [letter colour title]}
        (case kind
          :on-match  {:letter "M" :colour (:cyan tokens)          :title ":on-match"}
          :can-leave {:letter "L" :colour (:yellow tokens)        :title ":can-leave"}
          :tags      {:letter "T" :colour (:magenta tokens)       :title ":tags"}
          :parent    {:letter "P" :colour (:accent-violet tokens) :title ":parent"}
          nil)]
    (when letter
      [:span {:data-testid (str "rf-causa-static-routes-badge-" (name kind))
              :title       title
              :style       {:display       "inline-block"
                            :min-width     "14px"
                            :height        "14px"
                            :line-height   "14px"
                            :padding       "0 3px"
                            :margin-right  "4px"
                            :background    (:bg-3 tokens)
                            :color         colour
                            :border        (str "1px solid " colour)
                            :border-radius "3px"
                            :font-family   mono-stack
                            :font-size     "9px"
                            :font-weight   700
                            :text-align    "center"}}
       letter])))

(defn- search-box
  "Substring filter. Matches against route-id + path + doc via
  `routing-helpers/filter-rows`. State on
  `:rf.causa.static.routes/query`."
  [query total-routes filtered?]
  [:div {:data-testid "rf-causa-static-routes-search"
         :style       {:display       "flex"
                       :align-items   "center"
                       :gap           "8px"
                       :padding       "8px 16px"
                       :border-bottom (str "1px solid " (:border-subtle tokens))
                       :font-family   sans-stack}}
   [:label {:style {:color          (:text-tertiary tokens)
                    :font-size      "10px"
                    :text-transform "uppercase"
                    :letter-spacing "0.5px"
                    :min-width      "60px"}}
    "Search"]
   [:input {:type        "text"
            :data-testid "rf-causa-static-routes-search-input"
            :placeholder "route-id, path, or doc…"
            :value       (or query "")
            :on-change   (fn [e]
                           (rf/dispatch [:rf.causa.static.routes/set-query
                                         (-> e .-target .-value)]
                                        {:frame :rf/causa}))
            :style       {:flex          1
                          :background    (:bg-3 tokens)
                          :color         (:text-primary tokens)
                          :border        (str "1px solid " (:border-default tokens))
                          :border-radius "3px"
                          :padding       "4px 8px"
                          :font-family   mono-stack
                          :font-size     "12px"}}]
   [:span {:data-testid "rf-causa-static-routes-search-count"
           :style       {:color       (:text-tertiary tokens)
                         :font-family mono-stack
                         :font-size   "11px"
                         :min-width   "60px"
                         :text-align  "right"}}
    (cond
      filtered?              "match"
      (= 1 total-routes)     "1 route"
      :else                  (str total-routes " routes"))]])

(defn- route-row
  "One row in the flat catalogue. No marker chip, no `:here` glyph —
  Static is event-INDEPENDENT. Clicking the row toggles the expand
  surface."
  [{:keys [route-id path doc parent has-on-match? has-can-leave? tags meta]
    :as row}
   {:keys [expanded? sim-open? routes-map on-toggle]}]
  [:li {:data-testid (str "rf-causa-static-routes-row-"
                          (subs (pr-str route-id) 1))
        :style       {:display       "block"
                      :padding       "0"
                      :font-family   mono-stack
                      :font-size     "12px"
                      :color         (:text-primary tokens)
                      :background    (if expanded? (:bg-2 tokens) "transparent")
                      :border-left   (if expanded?
                                       (str "2px solid " (:cyan tokens))
                                       "2px solid transparent")
                      :border-radius "2px"
                      :line-height   "20px"}}
   [:div {:style    {:display     "flex"
                     :align-items "center"
                     :gap         "8px"
                     :padding     "3px 8px"
                     :cursor      "pointer"
                     :white-space "nowrap"
                     :overflow    "hidden"}
          :on-click on-toggle}
    [:span {:data-testid (str "rf-causa-static-routes-chevron-"
                              (subs (pr-str route-id) 1))
            :style       {:color       (:text-tertiary tokens)
                          :font-size   "10px"
                          :min-width   "12px"
                          :user-select "none"}}
     (if expanded? "▾" "▸")]
    [:span {:style {:color       (:accent-violet tokens)
                   :font-weight 500
                   :min-width   "160px"}}
     path]
    [:span {:style {:color     (:text-tertiary tokens)
                    :font-size "11px"
                    :min-width "180px"}}
     (str route-id)]
    [:span {:style {:display     "inline-flex"
                    :align-items "center"
                    :margin-left "4px"}}
     (when has-on-match?  (meta-badge :on-match))
     (when has-can-leave? (meta-badge :can-leave))
     (when (seq tags)     (meta-badge :tags))
     (when parent         (meta-badge :parent))]
    (when doc
      [:span {:style {:color         (:text-secondary tokens)
                      :font-size     "11px"
                      :font-style    "italic"
                      :font-family   sans-stack
                      :overflow      "hidden"
                      :text-overflow "ellipsis"
                      :flex          1}}
       doc])]
   (when expanded?
     [row-expand/render row
      {:sim-open?  sim-open?
       :routes-map routes-map}])])

(defn- empty-state
  "Renders when no routes are registered."
  []
  [:div {:data-testid "rf-causa-static-routes-empty"
         :style       {:padding     "16px"
                       :color       (:text-tertiary tokens)
                       :font-family sans-stack
                       :font-size   "11px"
                       :font-style  "italic"}}
   "No routes registered."])

(defn- empty-filtered
  "Renders when the search query filters out every row."
  [query]
  [:div {:data-testid "rf-causa-static-routes-empty-filtered"
         :style       {:padding     "16px"
                       :color       (:text-tertiary tokens)
                       :font-family sans-stack
                       :font-size   "11px"
                       :font-style  "italic"}}
   (str "No routes match " (pr-str query) ".")])

(defn render
  "Top-level renderer for the flat-list area of the Static Routes
  panel. `data` is the projection from
  `routing-helpers/project-static-data`; `expanded` is the set of
  expanded row ids; `sim-open` is the set of route-ids whose
  hermetic preview is open; `routes-map` is threaded through for the
  preview projection."
  [{:keys [silent? routes total-routes filtered? query] :as _data}
   {:keys [expanded sim-open routes-map]}]
  (cond
    silent?
    (empty-state)

    :else
    [:<>
     (search-box query total-routes filtered?)
     (if (empty? routes)
       (empty-filtered query)
       (into [:ul {:data-testid "rf-causa-static-routes-list"
                   :style       {:list-style     "none"
                                 :margin         "8px 0 0 0"
                                 :padding        "0 8px"
                                 :display        "flex"
                                 :flex-direction "column"
                                 :gap            "1px"}}]
             (for [row routes]
               ^{:key (str (:route-id row))}
               [route-row row
                {:expanded?  (contains? expanded (:route-id row))
                 :sim-open?  (contains? sim-open (:route-id row))
                 :routes-map routes-map
                 :on-toggle  (fn [_]
                               (rf/dispatch [:rf.causa.static.routes/toggle-row
                                             (:route-id row)]
                                            {:frame :rf/causa}))}])))]))
