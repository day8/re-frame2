(ns day8.re-frame2-xray.static.routes.browse-list
  "Flat list + search + per-row inline expand for the Static Routes
  tab.

  ## Shape

  Routes are flat (no tree). Sort: `:path` ascending default. Search
  is substring across route-id + path + doc — shared with the Dynamic
  Routing lens via `routing-helpers/filter-rows`.

  Per row:
    - path / pattern (mono mode-accent)
    - route-id keyword (mono mode-accent, smaller)
    - source-coord chip (when present)
    - doc-string ellipsis

  Click row → inline expand surface unfolds below it
  (`row_expand/render`). No master-detail; no modal.

  ## Pure hiccup

  Same contract as every Xray view — pure hiccup, no Reagent /
  UIx / Helix references. Subscribes resolve to `:rf/xray` via the
  enclosing frame-provider in `static/shell.cljs`."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.static.routes.row-expand :as row-expand]
            [day8.re-frame2-xray.static.shared.search-box :as search-box]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

(defn- meta-badge
  "Single-letter badge surfacing presence of a metadata key — same
  letters as the Dynamic Routing lens (`M` `L` `T` `P`)."
  [kind]
  (let [{:keys [letter colour title]}
        (case kind
          :on-match  {:letter "M" :colour (:info tokens)          :title ":on-match"}
          :can-leave {:letter "L" :colour (:yellow tokens)        :title ":can-leave"}
          :tags      {:letter "T" :colour (:magenta tokens)       :title ":tags"}
          :parent    {:letter "P" :colour (:accent tokens) :title ":parent"}
          nil)]
    (when letter
      [:span {:data-testid (str "rf-xray-static-routes-badge-" (name kind))
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
  `:rf.xray.static.routes/query`.

  `dispatch` is threaded from the routes `Panel` reg-view (via
  `render`), not a render-time capture — this whole subtree is invoked as
  a Reagent component and cannot recover the frame. The flex-row markup
  lives in the shared `search-box` component."
  [dispatch query total-routes filtered?]
  [search-box/search-box
   {:testid-prefix   "rf-xray-static-routes"
    :dispatch        dispatch
    :set-query-event :rf.xray.static.routes/set-query
    :placeholder     "route-id, path, or doc…"
    :value           query
    :count-noun      "route"
    :total           total-routes
    :filtered?       filtered?
    :count-min-width "60px"}])

(defn- route-row
  "One row in the flat catalogue. No marker chip, no `:here` glyph —
  Static is event-INDEPENDENT. Clicking the row toggles the expand
  surface. `dispatch` is threaded from the routes `Panel`
  reg-view down into the expand surface's jump / sim-nav affordances."
  [dispatch
   {:keys [route-id path doc parent has-on-match? has-can-leave? tags meta]
    :as row}
   {:keys [expanded? sim-open? routes-map on-toggle]}]
  [:li {:data-testid (str "rf-xray-static-routes-row-"
                          (subs (pr-str route-id) 1))
        :role        "listitem"
        :style       {:display       "block"
                      :padding       "0"
                      :font-family   mono-stack
                      :font-size     "12px"
                      :color         (:text-primary tokens)
                      :background    (if expanded? (:bg-2 tokens) "transparent")
                      :border-left   (if expanded?
                                       (str "2px solid " (:accent tokens))
                                       "2px solid transparent")
                      :border-radius "2px"
                      :line-height   "20px"}}
   ;; Keyboard a11y. The route row's clickable body toggles
   ;; the inline expand surface, so it carries the L2 event-row recipe
   ;; (shell.cljs:1068 `role=button` + `tab-index=0` + `aria-label` +
   ;; Enter/Space activation). `aria-expanded` mirrors the open state so
   ;; assistive tech announces the toggle's disclosure semantics.
   [:div {:role         "button"
          :tab-index    "0"
          :aria-expanded (if expanded? "true" "false")
          :aria-label   (str "Route " (str route-id)
                             (when path (str " (" path ")"))
                             (if expanded? " — collapse" " — expand"))
          :style    {:display     "flex"
                     :align-items "center"
                     :gap         "8px"
                     :padding     "3px 8px"
                     :cursor      "pointer"
                     :white-space "nowrap"
                     :overflow    "hidden"}
          :on-click on-toggle
          :on-key-down (fn [^js e]
                         ;; Enter / Space fire the same row toggle the
                         ;; pointer-click path uses, so keyboard-only
                         ;; users can open/close the expand surface.
                         (let [k (.-key e)]
                           (when (or (= k "Enter") (= k " "))
                             (.preventDefault e)
                             (on-toggle e))))}
    [:span {:data-testid (str "rf-xray-static-routes-chevron-"
                              (subs (pr-str route-id) 1))
            :style       {:color       (:text-tertiary tokens)
                          :font-size   "10px"
                          :min-width   "12px"
                          :user-select "none"}}
     (if expanded? "▾" "▸")]
    [:span {:style {:color       (:accent tokens)
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
     [row-expand/render dispatch row
      {:sim-open?  sim-open?
       :routes-map routes-map}])])

(defn render
  "Top-level renderer for the flat-list area of the Static Routes
  panel. `data` is the projection from
  `routing-helpers/project-static-data`; `expanded` is the set of
  expanded row ids; `sim-open` is the set of route-ids whose
  hermetic preview is open; `routes-map` is threaded through for the
  preview projection."
  [dispatch
   {:keys [silent? routes total-routes filtered? query] :as _data}
   {:keys [expanded sim-open routes-map]}]
  ;; `dispatch` is the frame-aware dispatcher threaded from
  ;; the routes `Panel` reg-view (render is invoked as a Reagent
  ;; component, so it cannot recover the frame itself).
  (cond
    silent?
    (search-box/empty-state "rf-xray-static-routes" "route")

    :else
    [:<>
     (search-box dispatch query total-routes filtered?)
     (if (empty? routes)
       (search-box/empty-filtered "rf-xray-static-routes" "route" query)
       (into [:ul {:data-testid "rf-xray-static-routes-list"
                   :role        "list"
                   :style       {:list-style     "none"
                                 :margin         "8px 0 0 0"
                                 :padding        "0 8px"
                                 :display        "flex"
                                 :flex-direction "column"
                                 :gap            "1px"}}]
             (for [row routes]
               ^{:key (str (:route-id row))}
               [route-row dispatch row
                {:expanded?  (contains? expanded (:route-id row))
                 :sim-open?  (contains? sim-open (:route-id row))
                 :routes-map routes-map
                 :on-toggle  (fn [_]
                               (dispatch [:rf.xray.static.routes/toggle-row
                                          (:route-id row)]))}])))]))
