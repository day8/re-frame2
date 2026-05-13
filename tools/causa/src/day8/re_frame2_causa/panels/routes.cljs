(ns day8.re-frame2-causa.panels.routes
  "Routes panel — Phase 5 (rf2-6blai, parent rf2-5aw5v).

  Surfaces re-frame2's registered routes + the active `:rf/route`
  slice + recent navigation history. Consumer of:

    - Spec 012 (Routing)             — the registered-route surface,
                                       `(rf/handlers :route)` is the
                                       `{route-id metadata}` projection
                                       the composite sub reads. The
                                       active route comes from the
                                       target frame's `:rf/route` slice
                                       (per Spec 012 §The `:rf/route`
                                       slice).
    - Spec 009 (Instrumentation)     — the `:rf.route.nav-token/*` +
                                       `:rf.route/url-changed` trace
                                       event vocabulary. The panel
                                       filters the Causa trace buffer
                                       to those operations and renders
                                       them in the history feed.

  ## What this panel shows

  Three stacked sections (top to bottom):

    1. **Active route** — a breadcrumb-style strip showing the current
       `:rf/route` slice for the target frame: route-id · params ·
       query · fragment · transition. Per Spec 012 §The `:rf/route`
       slice.

    2. **Registered routes** — one row per `(rf/handlers :route)`
       entry, sorted by `:path`. Each row shows the route-id +
       path-pattern + `:doc`. The active-route row is highlighted.
       Clicking a row selects it; the v1 surface carries the selection
       only (the deeper detail view — click → source coord — lands
       when the cross-panel jump API stabilises).

    3. **Navigation history** — recent route-history trace events
       (newest first, capped at 50). Click a row → pivots to the
       event-detail panel for that navigation (parity with the Issues
       ribbon's row-click).

  Empty state: \"No routes registered.\"

  ## Pure hiccup

  Same contract as every other Causa panel — the view is pure hiccup,
  no Reagent / UIx / Helix references. Frame isolation comes from the
  enclosing `[rf/frame-provider {:frame :rf/causa}]` in `shell.cljs`.
  Every `subscribe` / `dispatch` here resolves to the `:rf/causa`
  frame.

  ## Helpers

  All pure-data logic — `project-feed`, `project-route-rows`,
  `project-history`, `format-route-id`, ... — lives in
  `routes_helpers.cljc` so the algebra runs under the JVM unit-test
  target. The view here is a thin renderer that reads the
  `:rf.causa/routes-data` composite sub."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.routes-helpers :as h]))

;; ---- design tokens (mirrors flows.cljs / subscriptions.cljs) ------------

(def ^:private tokens
  "Subset of shell.cljs's dark-theme tokens used by this panel. Kept
  in sync manually for now — the v1.0 styling pass replaces these
  with CSS variables across all panels."
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

;; ---- transition swatch --------------------------------------------------

(defn- transition-colour
  "Colour token for the `:rf/route` slice's `:transition` value. Per
  Spec 012 §The `:rf/route` slice the FSM is `:idle / :loading /
  :error`. Maps onto the panel's swatch palette."
  [transition]
  (case transition
    :idle    (:green tokens)
    :loading (:cyan tokens)
    :error   (:red tokens)
    (:text-tertiary tokens)))

(defn- transition-glyph
  "Single-character glyph paired with the transition swatch. Honours
  the spec §Colour is never alone discipline."
  [transition]
  (case transition
    :idle    "●"
    :loading "◐"
    :error   "▲"
    "○"))

;; ---- active-route breadcrumb --------------------------------------------

(defn- active-route-cell
  "One cell in the active-route breadcrumb strip. Renders a label +
  value mono pair."
  [label value data-testid-suffix]
  [:div {:data-testid (str "rf-causa-routes-active-" data-testid-suffix)
         :style       {:display "flex"
                       :flex-direction "column"
                       :gap "2px"
                       :min-width 0}}
   [:span {:style {:color (:text-tertiary tokens)
                   :font-family sans-stack
                   :font-size "10px"
                   :text-transform "uppercase"
                   :letter-spacing "0.5px"}}
    label]
   [:span {:style {:color (:text-primary tokens)
                   :font-family mono-stack
                   :font-size "12px"
                   :overflow "hidden"
                   :text-overflow "ellipsis"
                   :white-space "nowrap"}}
    value]])

(defn- active-route-strip
  "Top breadcrumb-style strip — surfaces the active `:rf/route` slice.
  Renders the empty placeholder when nothing has matched yet."
  [active]
  (if (nil? active)
    [:div {:data-testid "rf-causa-routes-active-empty"
           :style {:padding "10px 16px"
                   :border-bottom (str "1px solid " (:border-subtle tokens))
                   :color (:text-tertiary tokens)
                   :font-family sans-stack
                   :font-size "12px"}}
     "No active route. Navigate the host app to populate "
     [:code {:style {:font-family mono-stack
                     :color       (:accent-violet tokens)}}
      ":rf/route"]
     "."]
    (let [{:keys [route-id params query fragment transition]} active]
      [:div {:data-testid "rf-causa-routes-active"
             :style {:padding "10px 16px"
                     :display "grid"
                     :grid-template-columns "minmax(120px, 1fr) minmax(120px, 1fr) minmax(120px, 1fr) minmax(80px, 0.6fr) auto"
                     :gap "16px"
                     :align-items "end"
                     :border-bottom (str "1px solid " (:border-subtle tokens))
                     :background    (:bg-1 tokens)}}
       (active-route-cell "route" (h/format-route-id route-id) "route-id")
       (active-route-cell "params" (h/format-params params) "params")
       (active-route-cell "query" (h/format-params query) "query")
       (active-route-cell "fragment" (or fragment "—") "fragment")
       [:div {:data-testid "rf-causa-routes-active-transition"
              :style {:display "flex"
                      :flex-direction "column"
                      :gap "2px"
                      :align-items "flex-end"}}
        [:span {:style {:color (:text-tertiary tokens)
                        :font-family sans-stack
                        :font-size "10px"
                        :text-transform "uppercase"
                        :letter-spacing "0.5px"}}
         "transition"]
        [:span {:style {:display "inline-flex"
                        :align-items "center"
                        :gap "6px"
                        :color (transition-colour transition)
                        :font-family mono-stack
                        :font-size "12px"
                        :font-weight 600}
                :title (str transition)}
         [:span {:style {:font-weight 700}}
          (transition-glyph transition)]
         (if transition (name transition) "idle")]]])))

;; ---- registered-route row -----------------------------------------------

(defn- route-row
  "One row in the registered-routes list. Clicking the row fires
  `:rf.causa/select-route`; the active-route row carries the
  highlight + the `active` marker."
  [{:keys [route-id path doc] :as _row} selected? active?]
  [:li {:data-testid (str "rf-causa-route-row-" (h/format-route-id route-id))
        :on-click   #(rf/dispatch [:rf.causa/select-route route-id])
        :style      {:display       "flex"
                     :align-items   "center"
                     :padding       "6px 16px"
                     :background    (cond
                                      selected? (:bg-active tokens)
                                      active?   (:bg-1 tokens)
                                      :else     "transparent")
                     :border-bottom (str "1px solid " (:border-subtle tokens))
                     :border-left   (if active?
                                      (str "2px solid " (:accent-violet tokens))
                                      "2px solid transparent")
                     :cursor        "pointer"
                     :font-family   mono-stack
                     :font-size     "13px"
                     :color         (:text-primary tokens)}}
   [:span {:data-testid (str "rf-causa-route-id-" (h/format-route-id route-id))
           :style {:color (:text-primary tokens)
                   :margin-right "10px"
                   :min-width "200px"
                   :overflow "hidden"
                   :text-overflow "ellipsis"
                   :white-space "nowrap"}}
    (h/format-route-id route-id)]
   [:span {:data-testid (str "rf-causa-route-path-" (h/format-route-id route-id))
           :style {:color (:accent-violet tokens)
                   :margin-right "10px"
                   :min-width "180px"
                   :overflow "hidden"
                   :text-overflow "ellipsis"
                   :white-space "nowrap"}}
    (h/format-path path)]
   [:span {:style {:flex 1
                   :min-width 0
                   :color (:text-secondary tokens)
                   :font-family sans-stack
                   :font-size "11px"
                   :overflow "hidden"
                   :text-overflow "ellipsis"
                   :white-space "nowrap"}
           :title (or doc "")}
    (or doc "")]
   (when active?
     [:span {:data-testid (str "rf-causa-route-row-active-"
                               (h/format-route-id route-id))
             :style {:color (:accent-violet tokens)
                     :font-family sans-stack
                     :font-size "10px"
                     :font-weight 600
                     :text-transform "uppercase"
                     :letter-spacing "0.5px"
                     :margin-left "8px"}}
      "active"])])

(defn- route-list
  "The registered-routes list. Renders one row per registered route in
  helper-determined order (by path)."
  [rows selected-route-id active-route-id]
  (into [:ul {:data-testid "rf-causa-routes-list"
              :style {:list-style "none"
                      :margin     0
                      :padding    0
                      :background (:bg-2 tokens)}}]
        (for [row rows]
          ^{:key (h/format-route-id (:route-id row))}
          (route-row row
                     (= (:route-id row) selected-route-id)
                     (= (:route-id row) active-route-id)))))

;; ---- history feed -------------------------------------------------------

(defn- history-row
  "One row in the navigation-history feed. Click the row → pivot to
  the event-detail panel for the cascade that fired the navigation."
  [{:keys [id time operation route-id nav-token fragment dispatch-id]
    :as _entry}]
  (let [row-test-id (str "rf-causa-routes-history-row-" id)]
    [:li {:data-testid row-test-id
          :on-click    (fn []
                         (when dispatch-id
                           (rf/dispatch [:rf.causa/select-dispatch-id dispatch-id])
                           (rf/dispatch [:rf.causa/select-panel :event-detail])))
          :style       {:display       "grid"
                        :grid-template-columns "84px minmax(160px, 1fr) minmax(120px, 1fr) auto"
                        :gap           "10px"
                        :align-items   "center"
                        :padding       "6px 16px"
                        :border-bottom (str "1px solid " (:border-subtle tokens))
                        :cursor        (if dispatch-id "pointer" "default")
                        :color         (:text-primary tokens)
                        :font-family   mono-stack
                        :font-size     "12px"
                        :line-height   1.35}}
     [:span {:data-testid (str row-test-id "-time")
             :style {:color (:text-tertiary tokens)
                     :font-size "11px"
                     :white-space "nowrap"}}
      (or (h/format-time time) "—")]
     [:span {:data-testid (str row-test-id "-operation")
             :style {:color (:accent-violet tokens)
                     :overflow "hidden"
                     :text-overflow "ellipsis"
                     :white-space "nowrap"}
             :title (str operation)}
      (h/format-operation operation)]
     [:span {:data-testid (str row-test-id "-route-id")
             :style {:color (:text-secondary tokens)
                     :overflow "hidden"
                     :text-overflow "ellipsis"
                     :white-space "nowrap"}
             :title (str route-id)}
      (if route-id (h/format-route-id route-id) "—")]
     [:span {:style {:color (:text-tertiary tokens)
                     :font-size "11px"
                     :white-space "nowrap"
                     :overflow "hidden"
                     :text-overflow "ellipsis"}
             :title (str (when nav-token (str "nav-token=" nav-token))
                         (when fragment (str " fragment=" fragment)))}
      (cond
        nav-token (str "tok " nav-token)
        fragment  (str "#" fragment)
        :else     "—")]]))

(defn- history-section
  "The navigation-history feed. Renders the section heading + the
  rows; falls back to an inline empty marker when there's no history
  yet."
  [history]
  [:section {:data-testid "rf-causa-routes-history"
             :style {:border-top (str "1px solid " (:border-default tokens))
                     :background (:bg-2 tokens)}}
   [:header {:style {:padding "10px 16px 4px 16px"
                     :display "flex"
                     :align-items "baseline"
                     :justify-content "space-between"}}
    [:h2 {:style {:font-size   "12px"
                  :font-weight 600
                  :margin      0
                  :color       (:text-primary tokens)
                  :text-transform "uppercase"
                  :letter-spacing "0.5px"
                  :font-family sans-stack}}
     "Navigation history"]
    [:span {:data-testid "rf-causa-routes-history-count"
            :style {:color     (:text-tertiary tokens)
                    :font-size "11px"
                    :font-family mono-stack}}
     (str (count history) " entr" (if (= 1 (count history)) "y" "ies"))]]
   (if (empty? history)
     [:div {:data-testid "rf-causa-routes-history-empty"
            :style       {:padding "8px 16px 16px 16px"
                          :color   (:text-tertiary tokens)
                          :font-family sans-stack
                          :font-size "12px"}}
      "No navigations recorded yet. The feed populates on every "
      [:code {:style {:font-family mono-stack
                      :color       (:accent-violet tokens)}}
       ":rf.route/navigate"]
      " or browser back/forward event."]
     (into [:ul {:style {:list-style "none"
                         :margin 0
                         :padding 0}}]
           (for [entry history]
             ^{:key (:id entry)}
             (history-row entry))))])

;; ---- empty state --------------------------------------------------------

(defn- empty-state
  "Rendered when no routes are registered. The empty surface matches
  the bead's minimum-viable contract — exactly the copy 'No routes
  registered.' plus a one-line orienting caption."
  []
  [:div {:data-testid "rf-causa-routes-empty"
         :style       {:padding "16px"
                       :color   (:text-tertiary tokens)
                       :font-family sans-stack
                       :font-size "13px"}}
   [:p {:style {:margin "0 0 8px 0"
                :color  (:text-secondary tokens)}}
    "No routes registered."]
   [:p {:style {:margin 0
                :font-size "12px"
                :color (:text-tertiary tokens)}}
    "Register a route via "
    [:code {:style {:font-family mono-stack
                    :color       (:accent-violet tokens)}}
     "rf/reg-route"]
    " — the panel populates as routes register. See "
    [:code {:style {:font-family mono-stack
                    :color       (:accent-violet tokens)}}
     "spec/012-Routing.md"]
    "."]])

;; ---- public view --------------------------------------------------------

(defn routes-view
  "The Routes panel's root view. Subscribes to `:rf.causa/routes-data`
  and renders the active-route breadcrumb + registered-routes list +
  navigation-history feed (or the empty state when no routes are
  registered)."
  []
  (let [{:keys [rows total active-route selected-route-id history empty-kind]}
        @(rf/subscribe [:rf.causa/routes-data])
        active-route-id (:route-id active-route)]
    [:section {:data-testid "rf-causa-routes"
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
       "Routes"]
      [:p {:style {:font-size "12px"
                   :color     (:text-tertiary tokens)
                   :margin    "4px 0 0 0"}}
       "Registered routes + the active "
       [:code {:style {:font-family mono-stack
                       :color       (:accent-violet tokens)}}
        ":rf/route"]
       " slice + recent navigation history."]
      [:p {:style {:font-size "11px"
                   :color     (:text-tertiary tokens)
                   :margin    "4px 0 0 0"
                   :font-family mono-stack}}
       (str total " route" (if (= 1 total) "" "s") " registered")]]
     (if (= :no-routes empty-kind)
       (empty-state)
       [:div {:style {:flex 1
                      :overflow "auto"
                      :display "flex"
                      :flex-direction "column"}}
        (active-route-strip active-route)
        [:div {:style {:flex 1 :overflow "auto"}}
         (route-list rows selected-route-id active-route-id)]
        (history-section history)])]))
