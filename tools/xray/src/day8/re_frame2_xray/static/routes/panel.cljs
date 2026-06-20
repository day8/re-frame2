(ns day8.re-frame2-xray.static.routes.panel
  "Top-level Routes tab on Xray's Static surface.

  ## Two verbs, two homes

  Routes appears in BOTH Dynamic AND Static surfaces with different
  verbs. **Static gets BROWSE** — flat catalogue + Simulate-URL + per-
  row inline expand + hermetic Simulate-navigation preview. Dynamic
  gets the FOCUSED-EVENT LENS — FROM/TO markers when the focused
  event triggered navigation; otherwise an empty state.

  This panel is the Static-side surface. The Dynamic-side lens lives
  at `panels/routing.cljs` and is narrowed to the focused-event lens
  per the parent epic.

  ## Surface anatomy

      ┌───────────────────────────────────────────────────┐
      │ Routes — header + descriptive prose               │
      ├───────────────────────────────────────────────────┤
      │ Simulate URL: [____________________]  [clear]     │
      │   → result block (when input non-blank)           │
      ├───────────────────────────────────────────────────┤
      │ Search: [_______________]            12 routes    │
      ├───────────────────────────────────────────────────┤
      │ ▸ /cart         :route/cart        M  …doc…       │
      │ ▾ /checkout     :route/checkout                   │
      │   ╾─────── expand panel ─────────╼                │
      │   chips + matched-keys + schema + Simulate-nav    │
      │ ▸ /checkout/payment :route/payment                │
      │   …                                               │
      └───────────────────────────────────────────────────┘

  ## State slots (all under `:rf.xray.static.routes/*`)

    - `:rf.xray.static.routes/query` — search input value.
    - `:rf.xray.static.routes/sim-url` — Simulate-URL input value.
    - `:rf.xray.static.routes/expanded` — set of expanded route-ids.
    - `:rf.xray.static.routes/sim-nav-open` — set of route-ids whose
      hermetic Simulate-navigation preview is open.
    - `:rf.xray.static.routes/tab-data` — view-facing composite.

  ## Cross-link to Dynamic Routing

  The per-row `→ Dynamic` chip fires
  `:rf.xray.static.routes/jump-to-dynamic` which:

    1. Flips Xray to Dynamic mode (`:rf.xray/set-mode :dynamic`).
    2. Selects the Dynamic Routing tab (`:rf.xray/select-tab :routing`).

  The Dynamic side picks up the focused event automatically — there
  is no per-route routing-scope filter on the lens (the lens IS the
  focused event's slice of routing concerns).

  ## Pure hiccup

  Same contract as every Xray view — pure hiccup, no Reagent / UIx
  / Helix references. Frame isolation comes from the enclosing
  `[rf/frame-provider-existing {:frame :rf/xray}]` in `static/shell.cljs`."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.panels.routing-helpers :as h]
            [day8.re-frame2-xray.static.routes.browse-list :as browse-list]
            [day8.re-frame2-xray.static.routes.simulate-url :as simulate-url]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens type-scale sans-stack]]))

;; ---- header --------------------------------------------------------------

(defn- header
  []
  ;; No panel-name heading — the Static surface chrome already names the tab.
  [:div {:data-testid "rf-xray-static-routes-header"
         :style       {:padding "4px 16px"}}])

;; ---- root view -----------------------------------------------------------

(rf/reg-view Panel
  "Static Routes panel root view. Subscribes to the static-routes
  composite + UI-state slots and composes the header + Simulate-URL
  surface + browse list.

  `reg-view`-registered so subscribes resolve to `:rf/xray`."
  []
  (let [data       @(rf/subscribe [:rf.xray.static.routes/tab-data])
        expanded   @(rf/subscribe [:rf.xray.static.routes/expanded])
        sim-open   @(rf/subscribe [:rf.xray.static.routes/sim-nav-open])
        routes-map @(rf/subscribe [:rf.xray/registered-routes])
        {:keys [sim-url sim-result silent?]} data]
    [:section {:data-testid "rf-xray-static-routes"
               :style       {:height         "100%"
                             :display        "flex"
                             :flex-direction "column"
                             :background     (:bg-2 tokens)
                             :color          (:text-primary tokens)
                             :font-family    sans-stack
                             :font-size      (:body type-scale)}}
     (header)
     (if silent?
       [browse-list/render dispatch data {:expanded   expanded
                                          :sim-open   sim-open
                                          :routes-map routes-map}]
       [:<>
        (simulate-url/header dispatch sim-url sim-result)
        [:div {:style {:flex 1 :overflow "auto"}}
         [browse-list/render dispatch data {:expanded   expanded
                                            :sim-open   sim-open
                                            :routes-map routes-map}]]])]))

;; ---- registrations -------------------------------------------------------

(defn install!
  "Idempotent install for the Static Routes panel's subs + events.

  Registers:

    - `:rf.xray.static.routes/query`,
      `:rf.xray.static.routes/sim-url`,
      `:rf.xray.static.routes/expanded`,
      `:rf.xray.static.routes/sim-nav-open` — UI state slots.

    - `:rf.xray.static.routes/set-query`,
      `:rf.xray.static.routes/set-sim-url`,
      `:rf.xray.static.routes/toggle-row`,
      `:rf.xray.static.routes/toggle-sim-nav` — dispatch hooks.

    - `:rf.xray.static.routes/jump-to-dynamic` — cross-link from
      Static to the Dynamic Routing tab (flips mode + selects tab).

    - `:rf.xray.static.routes/tab-data` — view-facing composite over
      `:rf.xray/registered-routes` (registered by
      `panels/routing/install!`) + the UI-state slots."
  []

  ;; ---- UI-state slots --------------------------------------------------

  (rf/reg-event :rf.xray.static.routes/set-query
    (fn [{:keys [db]} [_ q]]
      {:db (if (or (nil? q) (= "" q))
        (dissoc db :rf.xray.static.routes/query)
        (assoc db :rf.xray.static.routes/query q))}))

  (rf/reg-sub :rf.xray.static.routes/query
    (fn [db _]
      (get db :rf.xray.static.routes/query)))

  (rf/reg-event :rf.xray.static.routes/set-sim-url
    (fn [{:keys [db]} [_ url]]
      {:db (if (or (nil? url) (= "" url))
        (dissoc db :rf.xray.static.routes/sim-url)
        (assoc db :rf.xray.static.routes/sim-url url))}))

  (rf/reg-sub :rf.xray.static.routes/sim-url
    (fn [db _]
      (get db :rf.xray.static.routes/sim-url)))

  (rf/reg-event :rf.xray.static.routes/toggle-row
    (fn [{:keys [db]} [_ route-id]]
      {:db (let [expanded (or (:rf.xray.static.routes/expanded db) #{})]
        (assoc db :rf.xray.static.routes/expanded
               (if (contains? expanded route-id)
                 (disj expanded route-id)
                 (conj expanded route-id))))}))

  (rf/reg-sub :rf.xray.static.routes/expanded
    (fn [db _]
      (or (:rf.xray.static.routes/expanded db) #{})))

  (rf/reg-event :rf.xray.static.routes/toggle-sim-nav
    (fn [{:keys [db]} [_ route-id]]
      {:db (let [open (or (:rf.xray.static.routes/sim-nav-open db) #{})]
        (assoc db :rf.xray.static.routes/sim-nav-open
               (if (contains? open route-id)
                 (disj open route-id)
                 (conj open route-id))))}))

  (rf/reg-sub :rf.xray.static.routes/sim-nav-open
    (fn [db _]
      (or (:rf.xray.static.routes/sim-nav-open db) #{})))

  ;; ---- cross-link to Dynamic Routing -----------------------------------

  ;; Per the parent epic findings §4.4: the `→ Dynamic` chip jumps to
  ;; Dynamic + opens the Routing lens. No route-id is plumbed down to
  ;; the Dynamic side — the lens IS the focused-event slice; the
  ;; orientation comes from whatever event is currently focused.
  (rf/reg-event :rf.xray.static.routes/jump-to-dynamic
    (fn [_ [_ _route-id]]
      {:fx [[:dispatch [:rf.xray/set-mode :dynamic]]
            [:dispatch [:rf.xray/select-tab :routing]]]}))

  ;; ---- view-facing composite -------------------------------------------

  (rf/reg-sub :rf.xray.static.routes/tab-data
    :<- [:rf.xray/registered-routes]
    :<- [:rf.xray.static.routes/query]
    :<- [:rf.xray.static.routes/sim-url]
    (fn [[routes-map query sim-url] _query]
      (h/project-static-data routes-map query sim-url)))

  ;; Register the Static Routes tab with the internal L4 tab registry.
  (panel-registry/reg-l4-tab!
    {:id    :routes
     :label "Routes"
     :mnem  "r"
     :modes #{:static}
     :order 1
     :panel Panel})

  nil)
