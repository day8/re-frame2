(ns day8.re-frame2-causa.static.routes.panel
  "Top-level Routes tab on Causa's Static surface (rf2-o5f5f.3).

  ## Two verbs, two homes (Mike's decision, 2026-05-19)

  Routes appears in BOTH Runtime AND Static surfaces with different
  verbs. **Static gets BROWSE** — flat catalogue + Simulate-URL + per-
  row inline expand + hermetic Simulate-navigation preview. Runtime
  gets the FOCUSED-EVENT LENS — FROM/TO markers when the focused
  event triggered navigation; otherwise an empty state.

  This panel is the Static-side surface. The Runtime-side lens lives
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

  ## State slots (all under `:rf.causa.static.routes/*`)

    - `:rf.causa.static.routes/query` — search input value.
    - `:rf.causa.static.routes/sim-url` — Simulate-URL input value.
    - `:rf.causa.static.routes/expanded` — set of expanded route-ids.
    - `:rf.causa.static.routes/sim-nav-open` — set of route-ids whose
      hermetic Simulate-navigation preview is open.
    - `:rf.causa.static.routes/tab-data` — view-facing composite.

  ## Cross-link to Runtime Routing

  The per-row `→ Runtime` chip fires
  `:rf.causa.static.routes/jump-to-runtime` which:

    1. Flips Causa to Runtime mode (`:rf.causa/set-mode :runtime`).
    2. Selects the Runtime Routing tab (`:rf.causa/select-tab :routing`).

  The Runtime side picks up the focused event automatically — there
  is no per-route routing-scope filter on the lens (the lens IS the
  focused event's slice of routing concerns).

  ## Pure hiccup

  Same contract as every Causa view — pure hiccup, no Reagent / UIx
  / Helix references. Frame isolation comes from the enclosing
  `[rf/frame-provider {:frame :rf/causa}]` in `static/shell.cljs`."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panel-registry :as panel-registry]
            [day8.re-frame2-causa.panels.routing-helpers :as h]
            [day8.re-frame2-causa.static.routes.browse-list :as browse-list]
            [day8.re-frame2-causa.static.routes.simulate-url :as simulate-url]
            [day8.re-frame2-causa.theme.tokens
             :as t
             :refer [tokens type-scale sans-stack]]))

;; ---- header --------------------------------------------------------------

(defn- header
  []
  ;; rf2-6xezz — Mike-direction 2026-05-21: panel-name heading scrubbed.
  [:div {:data-testid "rf-causa-static-routes-header"
         :style       {:padding "4px 16px"}}])

;; ---- root view -----------------------------------------------------------

(rf/reg-view Panel
  "Static Routes panel root view. Subscribes to the static-routes
  composite + UI-state slots and composes the header + Simulate-URL
  surface + browse list.

  Per rf2-in6l2 `reg-view`-registered so subscribes resolve to
  `:rf/causa`."
  []
  (let [data       @(rf/subscribe [:rf.causa.static.routes/tab-data])
        expanded   @(rf/subscribe [:rf.causa.static.routes/expanded])
        sim-open   @(rf/subscribe [:rf.causa.static.routes/sim-nav-open])
        routes-map @(rf/subscribe [:rf.causa/registered-routes])
        {:keys [sim-url sim-result silent?]} data]
    [:section {:data-testid "rf-causa-static-routes"
               :style       {:height         "100%"
                             :display        "flex"
                             :flex-direction "column"
                             :background     (:bg-2 tokens)
                             :color          (:text-primary tokens)
                             :font-family    sans-stack
                             :font-size      (:body type-scale)}}
     (header)
     (if silent?
       [browse-list/render data {:expanded   expanded
                                 :sim-open   sim-open
                                 :routes-map routes-map}]
       [:<>
        (simulate-url/header sim-url sim-result)
        [:div {:style {:flex 1 :overflow "auto"}}
         [browse-list/render data {:expanded   expanded
                                   :sim-open   sim-open
                                   :routes-map routes-map}]]])]))

;; ---- registrations -------------------------------------------------------

(defn install!
  "Idempotent install for the Static Routes panel's subs + events.

  Registers:

    - `:rf.causa.static.routes/query`,
      `:rf.causa.static.routes/sim-url`,
      `:rf.causa.static.routes/expanded`,
      `:rf.causa.static.routes/sim-nav-open` — UI state slots.

    - `:rf.causa.static.routes/set-query`,
      `:rf.causa.static.routes/set-sim-url`,
      `:rf.causa.static.routes/toggle-row`,
      `:rf.causa.static.routes/toggle-sim-nav` — dispatch hooks.

    - `:rf.causa.static.routes/jump-to-runtime` — cross-link from
      Static to the Runtime Routing tab (flips mode + selects tab).

    - `:rf.causa.static.routes/tab-data` — view-facing composite over
      `:rf.causa/registered-routes` (registered by
      `panels/routing/install!`) + the UI-state slots."
  []

  ;; ---- UI-state slots --------------------------------------------------

  (rf/reg-event-db :rf.causa.static.routes/set-query
    (fn [db [_ q]]
      (if (or (nil? q) (= "" q))
        (dissoc db :rf.causa.static.routes/query)
        (assoc db :rf.causa.static.routes/query q))))

  (rf/reg-sub :rf.causa.static.routes/query
    (fn [db _]
      (get db :rf.causa.static.routes/query)))

  (rf/reg-event-db :rf.causa.static.routes/set-sim-url
    (fn [db [_ url]]
      (if (or (nil? url) (= "" url))
        (dissoc db :rf.causa.static.routes/sim-url)
        (assoc db :rf.causa.static.routes/sim-url url))))

  (rf/reg-sub :rf.causa.static.routes/sim-url
    (fn [db _]
      (get db :rf.causa.static.routes/sim-url)))

  (rf/reg-event-db :rf.causa.static.routes/toggle-row
    (fn [db [_ route-id]]
      (let [expanded (or (:rf.causa.static.routes/expanded db) #{})]
        (assoc db :rf.causa.static.routes/expanded
               (if (contains? expanded route-id)
                 (disj expanded route-id)
                 (conj expanded route-id))))))

  (rf/reg-sub :rf.causa.static.routes/expanded
    (fn [db _]
      (or (:rf.causa.static.routes/expanded db) #{})))

  (rf/reg-event-db :rf.causa.static.routes/toggle-sim-nav
    (fn [db [_ route-id]]
      (let [open (or (:rf.causa.static.routes/sim-nav-open db) #{})]
        (assoc db :rf.causa.static.routes/sim-nav-open
               (if (contains? open route-id)
                 (disj open route-id)
                 (conj open route-id))))))

  (rf/reg-sub :rf.causa.static.routes/sim-nav-open
    (fn [db _]
      (or (:rf.causa.static.routes/sim-nav-open db) #{})))

  ;; ---- cross-link to Runtime Routing -----------------------------------

  ;; Per the parent epic findings §4.4: the `→ Runtime` chip jumps to
  ;; Runtime + opens the Routing lens. No route-id is plumbed down to
  ;; the Runtime side — the lens IS the focused-event slice; the
  ;; orientation comes from whatever event is currently focused.
  (rf/reg-event-fx :rf.causa.static.routes/jump-to-runtime
    (fn [_ [_ _route-id]]
      {:fx [[:dispatch [:rf.causa/set-mode :runtime]]
            [:dispatch [:rf.causa/select-tab :routing]]]}))

  ;; ---- view-facing composite -------------------------------------------

  (rf/reg-sub :rf.causa.static.routes/tab-data
    :<- [:rf.causa/registered-routes]
    :<- [:rf.causa.static.routes/query]
    :<- [:rf.causa.static.routes/sim-url]
    (fn [[routes-map query sim-url] _query]
      (h/project-static-data routes-map query sim-url)))

  ;; rf2-2moh1 — register the Static Routes tab with the internal L4
  ;; tab registry.
  (panel-registry/reg-l4-tab!
    {:id    :routes
     :label "Routes"
     :mnem  "r"
     :modes #{:static}
     :order 1
     :panel Panel
     :placeholder-bead "rf2-o5f5f.3"})

  nil)
