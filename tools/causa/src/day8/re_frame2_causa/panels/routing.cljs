(ns day8.re-frame2-causa.panels.routing
  "Routing tab — the 7th L4 tab (rf2-nrbs9).

  Per Mike's design call (2026-05-18 16:35): Routing earns its own
  top-level tab in the Causa shell rather than piling into App-db
  (`app-db panel gets busy`). Routing slices are a cohesive sub-domain
  — the route tree + the current match + nav transitions — and the
  bd memory `cohesive-subdomains-get-their-own-tab` codifies the
  posture: cohesive sub-domains earn their own lens tab.

  ## Lens model (parallel to Machines)

  Always-shown structure: the full route tree (every registered
  route, sorted by path). The tree is the orientation surface — a
  Causa user can flip to the Routing tab and immediately see the
  app's routing topology without having to dig through code.

  Per-focused-event highlighting:

  - `◆ HERE` on the current matched route (always — the orientation
    glyph; even when the focused event has no routing impact).
  - `◆ FROM` / `◆ TO` arrow when the focused cascade caused
    navigation (the cascade carries a
    `:rf.route.nav-token/allocated` trace event).
  - Params + query for the active route surface below the tree.
  - When the app has no routing registered: tab content silent
    (no `(none)` placeholder per rf2-g3ghh silent-by-default).

  ## ASCII sketch (focused event caused navigation)

      ROUTING
        /
        ├ /cart            ◆ FROM ━━━━━━━━━━━━━━━━━━┓
        ├ /checkout                                  ┃
        │   ├ /payment              ━━━━━━━━━━━━━━━━┛
        │   └ /confirm     ◆ TO    (matched)
        ├ /admin
        │   └ /audit
        └ /404

        Params: {:order-id \"ord-1234\"}
        Query:  {:source \"cart\"}

  ## Pure hiccup (rf2-tijr)

  Same contract as every other Causa panel — the view is pure
  hiccup, no Reagent / UIx / Helix references. Frame isolation
  comes from the enclosing `[rf/frame-provider {:frame :rf/causa}]`
  in `shell.cljs`. Every `subscribe` / `dispatch` here resolves to
  `:rf/causa`.

  ## Helpers

  Pure-data projection (`project-route-tree`, `project-data`,
  `from-to-from-cascade`, `assign-markers`) lives in
  `routing_helpers.cljc` so the algebra runs under the JVM unit-test
  target."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.routing-helpers :as h]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

;; ---- visual primitives --------------------------------------------------

(defn- marker-chip
  "Render the `◆ HERE` / `◆ FROM` / `◆ TO` marker chip for a route row.
  Returns nil when no marker — the row aligns naturally with its
  siblings (the chip column is empty)."
  [marker]
  (when marker
    (let [{:keys [label colour]}
          (case marker
            :here {:label "◆ HERE" :colour (:accent-violet tokens)}
            :from {:label "◆ FROM" :colour (:cyan tokens)}
            :to   {:label "◆ TO"   :colour (:green tokens)}
            nil)]
      (when label
        [:span {:data-testid (str "rf-causa-routing-marker-" (name marker))
                :style       {:color         colour
                              :font-weight   600
                              :font-family   sans-stack
                              :font-size     "11px"
                              :letter-spacing "0.3px"
                              :margin-left   "12px"
                              :white-space   "nowrap"}}
         label]))))

(defn- route-row
  "One row in the route tree. Indentation is depth × 16px so nested
  routes visually sit under their parents. Each row carries the
  route's path + the route-id (mono-font) + a marker chip when
  highlighted."
  [{:keys [route-id path depth marker doc] :as _row}]
  ;; testid uses the fully-qualified keyword (namespace + name) so two
  ;; routes whose simple names collide (e.g. `:cart/edit` and
  ;; `:admin/edit`) render with distinct testids. `pr-str` round-trips
  ;; the keyword's whole reader form (`:route/cart`); strip the leading
  ;; `:` so the resulting attribute reads as `rf-causa-routing-row-
  ;; route/cart` — namespace-bearing but stripped of the colon prefix
  ;; that confuses CSS selectors (a leading `:` would be parsed as a
  ;; pseudo-class).
  [:li {:data-testid (str "rf-causa-routing-row-" (subs (pr-str route-id) 1))
        :data-marker (when marker (name marker))
        :style       {:display        "flex"
                      :align-items    "center"
                      :gap            "8px"
                      :padding        "3px 8px"
                      :padding-left   (str (+ 8 (* (or depth 0) 16)) "px")
                      :font-family    mono-stack
                      :font-size      "12px"
                      :color          (:text-primary tokens)
                      :background     (case marker
                                        :to   (:bg-active tokens)
                                        :from (:bg-2 tokens)
                                        :here (:bg-2 tokens)
                                        "transparent")
                      :border-left    (case marker
                                        :to   (str "2px solid " (:green tokens))
                                        :from (str "2px solid " (:cyan tokens))
                                        :here (str "2px solid " (:accent-violet tokens))
                                        "2px solid transparent")
                      :border-radius  "2px"
                      :line-height    "20px"
                      :white-space    "nowrap"}}
   [:span {:style {:color (:text-tertiary tokens)
                   :font-size "11px"
                   :min-width "16px"
                   :text-align "right"}}
    ;; A small `·` for non-root, blank for root — gives the tree a
    ;; faint grid feel without ascii-art tree glyphs (those don't
    ;; survive font-substitution well).
    (if (pos? (or depth 0)) "·" "")]
   [:span {:style {:color (:accent-violet tokens)
                   :font-weight 500}}
    path]
   [:span {:style {:color (:text-tertiary tokens)
                   :font-size "11px"}}
    (str route-id)]
   (when doc
     [:span {:style {:color (:text-secondary tokens)
                     :font-size "11px"
                     :font-style "italic"
                     :font-family sans-stack
                     :overflow "hidden"
                     :text-overflow "ellipsis"}}
      doc])
   (marker-chip marker)])

(defn- header
  "Tab header — title + a brief explainer of the lens model. Always
  rendered (even when silent) so the user knows the tab exists; the
  body switches between the tree and the silent state."
  [{:keys [navigated? to-id]}]
  [:div {:data-testid "rf-causa-routing-header"
         :style       {:display       "flex"
                       :align-items   "baseline"
                       :justify-content "space-between"
                       :padding       "12px 16px 8px 16px"
                       :border-bottom (str "1px solid " (:border-subtle tokens))
                       :font-family   sans-stack}}
   [:div
    [:h2 {:style {:margin     "0"
                  :font-size  "13px"
                  :font-weight 600
                  :text-transform "uppercase"
                  :letter-spacing "0.5px"
                  :color      (:text-primary tokens)}}
     "Routing"]
    [:p {:style {:margin "4px 0 0 0"
                 :color  (:text-tertiary tokens)
                 :font-size "11px"
                 :line-height 1.4}}
     "Lens on the focused event — the full route tree, with "
     [:span {:style {:color (:accent-violet tokens) :font-weight 600}} "◆ HERE"]
     " marking the active route"
     (when navigated?
       [:span " and "
        [:span {:style {:color (:cyan tokens) :font-weight 600}} "◆ FROM"]
        " / "
        [:span {:style {:color (:green tokens) :font-weight 600}} "◆ TO"]
        " marking the navigation transition"])
     "."]]
   (when (and navigated? to-id)
     [:span {:data-testid "rf-causa-routing-nav-summary"
             :style {:color (:green tokens)
                     :font-size "11px"
                     :font-family mono-stack}}
      (str "→ " to-id)])])

(defn- slice-detail
  "Params / query / fragment breakdown for the current route slice.
  Three labelled rows; absent slots render as `—` so the lens always
  shows the same skeleton (predictable scanning)."
  [{:keys [params query fragment] :as _current}]
  [:div {:data-testid "rf-causa-routing-slice-detail"
         :style       {:padding     "10px 16px"
                       :border-top  (str "1px dotted " (:border-subtle tokens))
                       :font-family mono-stack
                       :font-size   "12px"
                       :color       (:text-primary tokens)
                       :display     "grid"
                       :grid-template-columns "80px 1fr"
                       :row-gap     "4px"
                       :column-gap  "12px"}}
   [:span {:style {:color (:text-tertiary tokens)}} "Params:"]
   [:span (if (seq params) (pr-str params) "—")]
   [:span {:style {:color (:text-tertiary tokens)}} "Query:"]
   [:span (if (seq query) (pr-str query) "—")]
   (when fragment
     [:<>
      [:span {:style {:color (:text-tertiary tokens)}} "Fragment:"]
      [:span (pr-str fragment)]])])

(defn- empty-state
  "Silent state — rendered when the host app has no routes registered.
  Per rf2-g3ghh silent-by-default the panel emits an empty section so
  the chrome stays consistent across tabs (every tab carries the
  header + an empty body when its data source is empty)."
  []
  [:div {:data-testid "rf-causa-routing-empty"
         :style       {:padding "16px"
                       :color   (:text-tertiary tokens)
                       :font-family sans-stack
                       :font-size "11px"
                       :font-style "italic"}}
   ;; Terse one-liner so the panel skeleton doesn't look broken (mirror
   ;; of the Issues panel's :no-issues empty state). Honours silent-by-
   ;; default — no `(none)` chip, no marketing copy.
   "No routes registered."])

;; ---- public view --------------------------------------------------------

(rf/reg-view Panel
  "The Routing tab's root view. Subscribes to
  `:rf.causa/routing-tab-data` and renders the route tree + slice
  detail or the silent state."
  []
  (let [{:keys [silent? routes current to-id navigated?]
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
     [:div {:style {:flex 1 :overflow "auto"}}
      (if silent?
        (empty-state)
        [:<>
         (into [:ul {:data-testid "rf-causa-routing-tree"
                     :style       {:list-style "none"
                                   :margin     "8px 0 0 0"
                                   :padding    "0 8px"
                                   :display    "flex"
                                   :flex-direction "column"
                                   :gap        "1px"}}]
               (for [row routes]
                 ^{:key (str (:route-id row))}
                 [route-row row]))
         (when current
           (slice-detail current))])]]))

;; ---- registration entry --------------------------------------------------

(defn install!
  "Idempotent install for the Routing panel's Causa-side registrations
  (rf2-nrbs9). Registers:

    - `:rf.causa/registered-routes` — flat `{<route-id> <meta>}` map
      sourced from `(rf/registrations :route)` (the framework's
      registrar). Falls back to a test-only override slot so JVM /
      node-test fixtures can drive the projection without booting
      a host that registers routes.
    - `:rf.causa/current-route-slice` — composite over the spine's
      target-frame app-db reading the `:rf/route` slice. Mirrors how
      `app-db-diff` reaches the host's app-db.
    - `:rf.causa/routing-tab-data` — view-facing composite folding
      the registered-routes map + current route slice + focused
      cascade into the shape the view consumes (see
      `routing_helpers/project-data`).
    - `:rf.causa/set-registered-routes-override-for-test` — test-only
      override hook the gallery fixtures + JVM tests use to seed the
      registered-routes slot without registering real routes.
    - `:rf.causa/set-current-route-slice-override-for-test` — same
      pattern for the current slice (drives the HERE marker)."
  []

  ;; Test-only override slot for the registered-routes registrar
  ;; lookup. When set (non-nil), the `:rf.causa/registered-routes`
  ;; sub returns this verbatim; when nil the sub falls through to
  ;; `(rf/registrations :route)`. Mirrors the
  ;; `:rf.causa/set-registered-machines-override-for-test` pattern
  ;; (machine_inspector.cljs) so fixtures can seed without a live
  ;; registrar.
  (rf/reg-event-db :rf.causa/set-registered-routes-override-for-test
    (fn [db [_ ov]]
      (if (nil? ov)
        (dissoc db :registered-routes-override)
        (assoc db :registered-routes-override ov))))

  (rf/reg-sub :rf.causa/registered-routes-override
    (fn [db _query]
      (get db :registered-routes-override)))

  ;; The registrar lookup — production reads through `rf/registrations`
  ;; (process-global atom outside re-frame's reactive graph; the sub
  ;; re-fires whenever the trace-buffer writes, which is the same
  ;; cadence the palette uses to recompute its handler index — see
  ;; palette/subs.cljs §palette-index). For the panel this is over-
  ;; eager (the route registrar rarely changes) but the cost is
  ;; bounded by the number of registered routes (typically < 30) and
  ;; the dependency chain keeps the override surface clean.
  (rf/reg-sub :rf.causa/registered-routes
    :<- [:rf.causa/trace-buffer]
    :<- [:rf.causa/registered-routes-override]
    (fn [[_buffer override] _query]
      (or override (rf/registrations :route))))

  ;; Test-only override slot for the current route slice. Same pattern
  ;; as registered-routes above.
  (rf/reg-event-db :rf.causa/set-current-route-slice-override-for-test
    (fn [db [_ ov]]
      (if (nil? ov)
        (dissoc db :current-route-slice-override)
        (assoc db :current-route-slice-override ov))))

  (rf/reg-sub :rf.causa/current-route-slice-override
    (fn [db _query]
      (get db :current-route-slice-override)))

  ;; The current route slice — production reads through the
  ;; target-frame-db sub (registered by `app-db-diff/install!`); that
  ;; sub composes `:rf.causa/target-frame` + `rf/get-frame-db` so
  ;; switching the L1 frame picker re-binds the lens to the new
  ;; frame's route slice. The override slot wins when set (test
  ;; fixtures + JVM coverage).
  (rf/reg-sub :rf.causa/current-route-slice
    :<- [:rf.causa/target-frame-db]
    :<- [:rf.causa/current-route-slice-override]
    (fn [[target-db override] _query]
      (cond
        (some? override) override
        (map? target-db) (:rf/route target-db)
        :else            nil)))

  ;; View-facing composite — folds registered-routes + current slice
  ;; + focused cascade (via `:rf.causa/cascades` + `:rf.causa/focus`)
  ;; into the shape `project-data` returns. The view subscribes to
  ;; this single sub; every component prop the renderer needs is in
  ;; the returned map.
  (rf/reg-sub :rf.causa/routing-tab-data
    :<- [:rf.causa/registered-routes]
    :<- [:rf.causa/current-route-slice]
    :<- [:rf.causa/cascades]
    :<- [:rf.causa/focus]
    (fn [[routes-map slice cascades focus] _query]
      (let [focused-cascade (h/focused-cascade cascades
                                               (:dispatch-id focus))]
        (h/project-data routes-map slice focused-cascade))))

  nil)
