(ns day8.re-frame2-causa.panels.routing
  "Routing tab — focused-event lens (rf2-o5f5f.3 narrows from rf2-lq0ef).

  ## Two verbs, two homes (Mike's decision, 2026-05-19)

  Routes appears in BOTH Runtime AND Static surfaces with different
  verbs:

    - **Static Routes** — browse-all + Simulate-URL + per-row inline
      expand + hermetic Simulate-navigation preview. Lives at
      `static/routes/panel.cljs`.
    - **Runtime Routing** — focused-event lens. Surfaces the
      routing-related slice of the focused event's cascade: FROM/TO
      chips when the focused event triggered navigation; empty state
      otherwise. THIS NAMESPACE.

  Industry pattern (React Router / Tanstack / Vue Router / Next.js
  Devtools): event-driven panels showcase the navigation cascade in
  context; static catalogues live in their own browser. The
  promote-narrow split mirrors that division.

  ## Lens surface (post-narrow)

      ┌──────────────────────────────────────────────────┐
      │ Routing — focused-event lens                     │
      ├──────────────────────────────────────────────────┤
      │ no nav?  →  'Focused event did not navigate.'    │
      │           +  hint to open Static Routes for      │
      │              registry browse.                    │
      │ nav?     →  FROM chip · TO chip · TO id          │
      │           +  params + query + fragment grid      │
      └──────────────────────────────────────────────────┘

  When the focused cascade carries a `:rf.route.nav-token/allocated`
  trace event, FROM and TO chips render with the prior route + the
  new route id. When the slice carries no navigation context (the
  focused event was not a navigation event), the lens shows an
  empty-orientation state — the user is reminded that BROWSE lives
  on the Static surface.

  ## Pure hiccup (rf2-tijr)

  Same contract as every other Causa panel — the view is pure
  hiccup, no Reagent / UIx / Helix references. Frame isolation comes
  from the enclosing `[rf/frame-provider {:frame :rf/causa}]` in
  `shell.cljs`. Every `subscribe` / `dispatch` here resolves to
  `:rf/causa`.

  ## Helpers

  Pure-data projection (`focused-cascade`,
  `nav-token-allocated-in-cascade`, `from-to-from-cascade`) lives in
  `routing_helpers.cljc` so the algebra runs under the JVM unit-test
  target. The catalogue / search / Simulate-URL helpers
  (`project-routes`, `filter-rows`, `simulate-url`) live there too
  and now power the Static Routes panel — same shared primitives,
  per the parent-epic findings §5.1."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panel-registry :as panel-registry]
            [day8.re-frame2-causa.panels.routing-helpers :as h]
            [day8.re-frame2-causa.theme.tokens
             :as t
             :refer [tokens mono-stack sans-stack]]))

;; ---- visual primitives --------------------------------------------------

(defn- marker-chip
  "Render the `◆ FROM` / `◆ TO` marker chip. Returns nil when no
  marker — used inline alongside the route-id."
  [marker]
  (when marker
    (let [{:keys [label colour]}
          (case marker
            :from {:label "◆ FROM" :colour (:cyan tokens)}
            :to   {:label "◆ TO"   :colour (:green tokens)}
            nil)]
      (when label
        [:span {:data-testid (str "rf-causa-routing-marker-" (name marker))
                :style       {:color          colour
                              :font-weight    600
                              :font-family    sans-stack
                              :font-size      "11px"
                              :letter-spacing "0.3px"
                              :margin-right   "6px"
                              :white-space    "nowrap"}}
         label]))))

(defn- nav-chip-row
  "FROM → TO chip row rendered when the focused cascade triggered
  navigation."
  [{:keys [from-id to-id]}]
  [:div {:data-testid "rf-causa-routing-nav-row"
         :style       {:padding     "10px 16px"
                       :display     "flex"
                       :align-items "center"
                       :gap         "8px"
                       :font-family mono-stack
                       :font-size   "13px"
                       :color       (:text-primary tokens)}}
   (when from-id
     [:span {:data-testid "rf-causa-routing-from-cell"
             :style       {:display     "inline-flex"
                           :align-items "center"
                           :padding     "4px 10px"
                           :background  (:bg-2 tokens)
                           :border-left (str "2px solid " (:cyan tokens))
                           :border-radius "2px"
                           :gap         "6px"}}
      (marker-chip :from)
      [:span {:style {:color (:text-primary tokens)}} (str from-id)]])
   (when (and from-id to-id)
     [:span {:style {:color       (:text-tertiary tokens)
                     :font-family sans-stack}}
      "→"])
   (when to-id
     [:span {:data-testid "rf-causa-routing-to-cell"
             :style       {:display     "inline-flex"
                           :align-items "center"
                           :padding     "4px 10px"
                           :background  (:bg-active tokens)
                           :border-left (str "2px solid " (:green tokens))
                           :border-radius "2px"
                           :gap         "6px"}}
      (marker-chip :to)
      [:span {:style {:color (:text-primary tokens)}} (str to-id)]])])

;; ---- header / slice-detail / empty-state --------------------------------

(defn- header
  [{:keys [navigated? to-id]}]
  [:div {:data-testid "rf-causa-routing-header"
         :style       {:display       "flex"
                       :align-items   "baseline"
                       :justify-content "space-between"
                       :padding       "12px 16px 8px 16px"
                       :border-bottom (str "1px solid " (:border-subtle tokens))
                       :font-family   sans-stack}}
   [:div
    ;; rf2-5kfxe.8 — domain-coloured accent stripe (:yellow for
    ;; Routing — side-channel attention tone, distinguished from
    ;; app-db's main colour).
    [:h2 {:style (merge {:margin         "0"
                         :font-size      "13px"
                         :font-weight    600
                         :text-transform "uppercase"
                         :letter-spacing "0.5px"
                         :color          (:text-primary tokens)}
                        (t/accent-stripe-style :routing))}
     "Routing"]
    [:p {:style {:margin     "4px 0 0 0"
                 :color      (:text-tertiary tokens)
                 :font-size  "11px"
                 :line-height 1.4}}
     "Focused-event lens — surfaces the navigation slice of the focused "
     "event's cascade. "
     [:span {:style {:color (:cyan tokens) :font-weight 600}} "◆ FROM"]
     " / "
     [:span {:style {:color (:green tokens) :font-weight 600}} "◆ TO"]
     " mark the transition when the focused event triggered "
     "navigation. For the full route catalogue browse + Simulate-URL "
     "surface, switch to Static mode."]]
   (when (and navigated? to-id)
     [:span {:data-testid "rf-causa-routing-nav-summary"
             :style       {:color       (:green tokens)
                           :font-size   "11px"
                           :font-family mono-stack}}
      (str "→ " to-id)])])

(defn- slice-detail
  "Params / query / fragment breakdown for the route slice. Surfaces
  when the focused event navigated — the `current` slice carries the
  post-navigation params + query, and the user wants to see what
  landed."
  [{:keys [params query fragment] :as _current}]
  [:div {:data-testid "rf-causa-routing-slice-detail"
         :style       {:padding               "10px 16px"
                       :border-top            (str "1px dotted " (:border-subtle tokens))
                       :font-family           mono-stack
                       :font-size             "12px"
                       :color                 (:text-primary tokens)
                       :display               "grid"
                       :grid-template-columns "80px 1fr"
                       :row-gap               "4px"
                       :column-gap            "12px"}}
   [:span {:style {:color (:text-tertiary tokens)}} "Params:"]
   [:span (if (seq params) (pr-str params) "—")]
   [:span {:style {:color (:text-tertiary tokens)}} "Query:"]
   [:span (if (seq query) (pr-str query) "—")]
   (when fragment
     [:<>
      [:span {:style {:color (:text-tertiary tokens)}} "Fragment:"]
      [:span (pr-str fragment)]])])

(defn- empty-state
  "Renders when the focused event did NOT trigger navigation. The
  lens is event-coupled — there is no 'current route' for browse;
  that's the Static Routes verb."
  []
  [:div {:data-testid "rf-causa-routing-empty"
         :style       {:padding     "16px"
                       :display     "flex"
                       :flex-direction "column"
                       :gap         "8px"
                       :color       (:text-tertiary tokens)
                       :font-family sans-stack
                       :font-size   "11px"}}
   [:span {:style {:font-style "italic"}}
    "Focused event did not trigger navigation."]
   [:span {:style {:color (:text-tertiary tokens)}}
    "For browse + Simulate-URL, switch to "
    [:strong {:style {:color (:cyan tokens)}} "Static"]
    " mode (the Routes tab there lists every registered route)."]])

;; ---- public view --------------------------------------------------------

(rf/reg-view Panel
  "The Routing tab's root view — focused-event lens. Subscribes to
  `:rf.causa/routing-tab-data` and renders FROM/TO chips + slice
  detail when the focused cascade triggered navigation; otherwise
  shows the empty orientation state pointing to Static Routes."
  []
  (let [{:keys [from-id to-id navigated? current]
         :as _data}
        @(rf/subscribe [:rf.causa/routing-tab-data])]
    [:section {:data-testid "rf-causa-routing"
               :style       {:height         "100%"
                             :display        "flex"
                             :flex-direction "column"
                             :background     (:bg-2 tokens)
                             :color          (:text-primary tokens)
                             :font-family    sans-stack
                             :font-size      "14px"}}
     (header {:navigated? navigated? :to-id to-id})
     (if-not navigated?
       (empty-state)
       [:<>
        (nav-chip-row {:from-id from-id :to-id to-id})
        (when current
          (slice-detail current))])]))

;; ---- registration entry --------------------------------------------------

(defn install!
  "Idempotent install for the Routing panel's Causa-side
  registrations. Registers:

    - `:rf.causa/registered-routes` — flat `{<route-id> <meta>}` map
      sourced from `(rf/registrations :route)`. The Static Routes
      panel also reads this sub — process-global, frame-agnostic.
    - `:rf.causa/current-route-slice` — composite over the spine's
      target-frame app-db reading the `:rf/route` slice.
    - `:rf.causa/routing-tab-data` — view-facing composite for the
      Runtime Routing lens (focused-event scoped). Carries
      `:from-id`, `:to-id`, `:navigated?`, `:current`.
    - `:rf.causa/set-registered-routes-override-for-test`,
      `:rf.causa/set-current-route-slice-override-for-test` —
      test-only override hooks.

  The browse / search / Simulate-URL slots (`:rf.causa.routing/
  query`, `:rf.causa.routing/sim-url`, `:rf.causa.routing/expanded`,
  `:rf.causa.routing/toggle-row`, etc.) were promoted to the Static
  Routes panel per rf2-o5f5f.3 and now live under
  `:rf.causa.static.routes/*` (installed by
  `static/routes/panel/install!`)."
  []

  ;; Test-only override slots --------------------------------------------

  (rf/reg-event-db :rf.causa/set-registered-routes-override-for-test
    (fn [db [_ ov]]
      (if (nil? ov)
        (dissoc db :registered-routes-override)
        (assoc db :registered-routes-override ov))))

  (rf/reg-sub :rf.causa/registered-routes-override
    (fn [db _query]
      (get db :registered-routes-override)))

  (rf/reg-event-db :rf.causa/set-current-route-slice-override-for-test
    (fn [db [_ ov]]
      (if (nil? ov)
        (dissoc db :current-route-slice-override)
        (assoc db :current-route-slice-override ov))))

  (rf/reg-sub :rf.causa/current-route-slice-override
    (fn [db _query]
      (get db :current-route-slice-override)))

  ;; Production data subs -------------------------------------------------

  (rf/reg-sub :rf.causa/registered-routes
    :<- [:rf.causa/trace-buffer]
    :<- [:rf.causa/registered-routes-override]
    (fn [[_buffer override] _query]
      (or override (rf/registrations :route))))

  (rf/reg-sub :rf.causa/current-route-slice
    :<- [:rf.causa/target-frame-db]
    :<- [:rf.causa/current-route-slice-override]
    (fn [[target-db override] _query]
      (cond
        (some? override) override
        (map? target-db) (:rf/route target-db)
        :else            nil)))

  ;; View-facing composite (focused-event lens) ---------------------------

  (rf/reg-sub :rf.causa/routing-tab-data
    :<- [:rf.causa/current-route-slice]
    :<- [:rf.causa/cascades]
    :<- [:rf.causa/focus]
    (fn [[slice cascades focus] _query]
      (let [focused-cascade (h/focused-cascade cascades
                                               (:dispatch-id focus))
            nav             (h/from-to-from-cascade focused-cascade slice)]
        {:from-id    (:from-id nav)
         :to-id      (:to-id nav)
         :navigated? (:navigated? nav)
         :current    slice})))

  ;; rf2-2moh1 — register the Runtime Routing tab with the internal L4
  ;; tab registry. Per rf2-nrbs9 Mike's design call (2026-05-18) Routing
  ;; earns its own L3 lens tab between Machines and Issues.
  ;;
  ;; rf2-mkpnb — order bumped 5 → 6 to make room for the new Machines
  ;; Canvas tab at order 5 (sits adjacent to Machines so the two
  ;; machine sub-domain tabs render next to each other).
  (panel-registry/reg-l4-tab!
    {:id    :routing
     :label "Routing"
     :mnem  "r"
     :modes #{:runtime}
     :order 6
     :panel Panel})

  nil)
