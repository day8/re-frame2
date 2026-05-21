(ns day8.re-frame2-causa.panels.routing
  "Routing tab — topology-plus-overlay shape (rf2-3kjlo).

  ## Two verbs, two homes (Mike's decision, 2026-05-19)

  Routes appears in BOTH Dynamic AND Static surfaces with different
  verbs:

    - **Static Routes** — browse-all + Simulate-URL + per-row inline
      expand + hermetic Simulate-navigation preview. Lives at
      `static/routes/panel.cljs`.
    - **Dynamic Routing** — topology-plus-overlay (this ns) per
      spec/021 §7. The full route tree is ALWAYS visible; the focused
      epoch's nav activity overlays as a `:to` / `:from` / `:here`
      marker on the relevant nodes. A 'This epoch' detail block below
      the tree surfaces Phase / From / To / Match / Events.

  ## Topology-plus-overlay shape (post-rf2-3kjlo reshape)

      ┌─ ROUTING · epoch #38 ────────────────────────── [◀ Prev] [Next ▶] ─┐
      │ Stripe: yellow (:yellow)                                            │
      │                                                                     │
      │ Active route tree                                                   │
      │  /                                                                  │
      │  ├─ /cart      ◉  (active this epoch — :on-match)                   │
      │  │  └─ /cart/:id                                                    │
      │  ├─ /orders                                                         │
      │  │  └─ /orders/:order-id                                            │
      │  └─ /settings                                                       │
      │                                                                     │
      │ This epoch                                                          │
      │   Phase     :on-match                                               │
      │   From      /                                                       │
      │   To        /cart                                                   │
      │   Match     {:route :cart}                                          │
      │   Events    [:rf.route/transitioned] [:cart/route-entered]                 │
      │                                                                     │
      │ Empty (no route activity this epoch):                               │
      │   Shows tree with current active node highlighted; 'This epoch'     │
      │   reads 'No route activity in this epoch.'                          │
      └─────────────────────────────────────────────────────────────────────┘

  ## Per-epoch overlay markers

  The overlay paints inline alongside each topology row via
  `routing-helpers/assign-markers`:

    - `:to` (green stripe) — the focused cascade navigated TO this
      route (the new HERE)
    - `:from` (cyan stripe) — the focused cascade navigated AWAY from
      this route
    - `:here` (yellow dot) — the current active route when no
      navigation happened this epoch

  When the focused epoch has NO routing activity (`:activity` is nil)
  the tree still renders with `:here` only; the 'This epoch' block
  reads 'No route activity in this epoch.'

  ## Focus contract (rf2-h0120 alignment)

  The panel reads `:rf.causa/focus` per spec/018; that sub already
  auto-resolves head-fallback via `spine/compose-focus` (when no user
  focus is set, `:dispatch-id` snaps to the head focusable cascade).
  No inline head-fallback needed at this layer — the spine sub IS the
  head-fallback.

  ## Pure hiccup (rf2-tijr)

  Same contract as every other Causa panel — the view is pure hiccup,
  no Reagent / UIx / Helix references. Frame isolation comes from the
  enclosing `[rf/frame-provider {:frame :rf/causa}]` in `shell.cljs`.
  Every `subscribe` / `dispatch` here resolves to `:rf/causa`.

  ## Helpers

  Pure-data projection (`project-topology`, `epoch-routing-activity`,
  `project-topology-data`, plus the legacy lens helpers) lives in
  `routing_helpers.cljc` so the algebra runs under the JVM unit-test
  target."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panel-registry :as panel-registry]
            [day8.re-frame2-causa.panels.routing-helpers :as h]
            [day8.re-frame2-causa.theme.tokens
             :as t
             :refer [tokens mono-stack sans-stack]]))

;; ---- visual primitives --------------------------------------------------

(defn- marker-glyph
  "Resolve the marker glyph + colour for a topology row. Returns a
  `{:glyph :colour :label}` map; nil when the row carries no marker.

  Per spec/021 §7.2 + §17.1.5:
    - `:to` → green `◉` dot (navigation destination)
    - `:from` → cyan `◇` outline diamond (navigation origin)
    - `:here` → yellow `●` (current active route, no nav this epoch)"
  [marker]
  (case marker
    :to   {:glyph "◉" :colour (:green tokens) :label "TO"}
    :from {:glyph "◇" :colour (:cyan tokens)  :label "FROM"}
    :here {:glyph "●" :colour (:yellow tokens) :label "HERE"}
    nil))

(defn- tree-prefix
  "Compose the `├─ └─ │` prefix for a topology row at the given depth.

  Depth-0 rows render with no prefix; deeper rows render
  `<spacer>(└─|├─) ` — `└─` for last-sibling rows, `├─` for non-last.
  This is the same box-drawing scheme the spec mockup uses (§7.2).

  Pragmatic v1: the inter-depth `│` continuation pipes are omitted —
  routing trees are shallow (≤ 4 levels per spec §7.1) so the depth
  indentation + branch glyphs alone read cleanly. A future bead can
  refine the prefix once the inspector has live route topologies to
  tune against."
  [depth last-at-depth?]
  (cond
    (zero? depth) ""
    :else         (let [indent (apply str (repeat (dec depth) "  "))
                        branch (if last-at-depth? "└─ " "├─ ")]
                    (str indent branch))))

(defn- topology-row
  "Render one route in the topology tree. The full row is mono so the
  `├─ └─` glyphs line up; the marker (when present) renders to the
  right of the path with its colour-coded glyph."
  [{:keys [row depth last-at-depth?]}]
  (let [{:keys [route-id path marker doc]} row
        glyph (marker-glyph marker)
        testid (str "rf-causa-routing-topology-row-"
                    (when route-id (name route-id)))]
    [:div {:data-testid testid
           :data-route-id (when route-id (str route-id))
           :data-marker   (when marker (name marker))
           :style {:display      "flex"
                   :align-items  "center"
                   :gap          "8px"
                   :padding      "2px 16px"
                   :font-family  mono-stack
                   :font-size    "12px"
                   :color        (:text-primary tokens)
                   :background   (when (= marker :to)
                                   (:bg-active tokens))
                   :border-left  (str "2px solid "
                                      (if glyph
                                        (:colour glyph)
                                        "transparent"))
                   :white-space  "pre"}}
     [:span {:style {:color (:text-tertiary tokens)}}
      (tree-prefix depth last-at-depth?)]
     [:span {:style {:color       (:text-primary tokens)
                     :font-weight (if (= marker :to) 600 400)}}
      path]
     (when glyph
       [:span {:data-testid (str "rf-causa-routing-topology-marker-"
                                 (name marker))
               :style       {:color       (:colour glyph)
                             :font-size   "11px"
                             :font-weight 600
                             :margin-left "8px"}}
        (:glyph glyph) " " (:label glyph)])
     (when (and (not marker) doc)
       [:span {:style {:color       (:text-tertiary tokens)
                       :font-family sans-stack
                       :font-size   "11px"
                       :font-style  "italic"
                       :margin-left "8px"}}
        doc])]))

(defn- topology-tree
  "Render the full topology — the always-visible base layer per
  spec/021 §7.2. Empty topology vector ⇒ render nothing (the silent-
  by-default contract per rf2-g3ghh; the surrounding panel renders
  its empty section instead)."
  [topology]
  [:div {:data-testid "rf-causa-routing-topology"
         :style       {:padding "8px 0"}}
   [:div {:style {:padding        "0 16px 6px 16px"
                  :color          (:text-tertiary tokens)
                  :font-family    sans-stack
                  :font-size      "10px"
                  :text-transform "uppercase"
                  :letter-spacing "0.5px"}}
    "Active route tree"]
   (into [:<>]
         (for [entry topology]
           ^{:key (str (-> entry :row :route-id))}
           (topology-row entry)))])

;; ---- "This epoch" detail block ------------------------------------------

(defn- epoch-detail-row
  "Render one `Label  value` row in the 'This epoch' grid."
  [label value-hiccup label-testid value-testid]
  [:<>
   [:span {:data-testid label-testid
           :style {:color       (:text-tertiary tokens)
                   :font-family sans-stack
                   :font-size   "11px"}}
    label]
   [:span {:data-testid value-testid
           :style {:color       (:text-primary tokens)
                   :font-family mono-stack
                   :font-size   "12px"}}
    value-hiccup]])

(defn- this-epoch-block
  "Render the 'This epoch' detail block per spec/021 §7.2.

  When `:activity` is nil renders the empty-state caption (' No route
  activity in this epoch. ') — the topology still renders above (per
  the topology-plus-overlay contract).

  When `:activity` is present surfaces Phase / From / To / Match /
  Events using the topology-row marker colors so the overlay reads
  consistently across the tree + this block."
  [{:keys [activity from-id to-id current navigated?]}]
  [:div {:data-testid "rf-causa-routing-this-epoch"
         :style       {:border-top  (str "1px solid " (:border-subtle tokens))
                       :padding     "10px 16px 12px 16px"
                       :font-family sans-stack
                       :font-size   "12px"}}
   [:div {:style {:color          (:text-tertiary tokens)
                  :font-size      "10px"
                  :text-transform "uppercase"
                  :letter-spacing "0.5px"
                  :margin-bottom  "6px"}}
    "This epoch"]
   (if (nil? activity)
     [:div {:data-testid "rf-causa-routing-no-activity"
            :style       {:color      (:text-tertiary tokens)
                          :font-style "italic"
                          :font-size  "12px"}}
      "No route activity in this epoch."]
     (let [{:keys [phase events match]} activity]
       [:div {:style {:display               "grid"
                      :grid-template-columns "80px 1fr"
                      :row-gap               "4px"
                      :column-gap            "12px"
                      :align-items           "baseline"}}
        (epoch-detail-row
          "Phase"
          [:span {:style {:color (:yellow tokens) :font-weight 600}}
           (pr-str phase)]
          "rf-causa-routing-detail-phase-label"
          "rf-causa-routing-detail-phase")
        (epoch-detail-row
          "From"
          (if from-id
            [:span {:style {:color (:cyan tokens)}}
             (str from-id)]
            [:span {:style {:color (:text-tertiary tokens)}} "—"])
          "rf-causa-routing-detail-from-label"
          "rf-causa-routing-detail-from")
        (epoch-detail-row
          "To"
          (if to-id
            [:span {:style {:color (:green tokens) :font-weight 600}}
             (str to-id)]
            [:span {:style {:color (:text-tertiary tokens)}} "—"])
          "rf-causa-routing-detail-to-label"
          "rf-causa-routing-detail-to")
        (epoch-detail-row
          "Match"
          (if (and navigated? (seq match))
            [:span (pr-str match)]
            [:span {:style {:color (:text-tertiary tokens)}}
             (if (and navigated? current)
               (pr-str (or (:params current) {}))
               "—")])
          "rf-causa-routing-detail-match-label"
          "rf-causa-routing-detail-match")
        (epoch-detail-row
          "Events"
          (if (seq events)
            (into [:span {:style {:display "inline-flex" :gap "6px"
                                  :flex-wrap "wrap"}}]
                  (for [[idx ev] (map-indexed vector events)]
                    ^{:key idx}
                    [:span {:style {:color       (:accent-violet tokens)
                                    :background  (:bg-1 tokens)
                                    :padding     "1px 6px"
                                    :border-radius "2px"
                                    :font-size   "11px"}}
                     (pr-str ev)]))
            [:span {:style {:color (:text-tertiary tokens)}} "—"])
          "rf-causa-routing-detail-events-label"
          "rf-causa-routing-detail-events")]))])

;; ---- header --------------------------------------------------------------

(defn- header
  [{:keys [navigated? to-id silent?]}]
  [:div {:data-testid "rf-causa-routing-header"
         :style       {:display         "flex"
                       :align-items     "baseline"
                       :justify-content "space-between"
                       :padding         "12px 16px 8px 16px"
                       :border-bottom   (str "1px solid " (:border-subtle tokens))
                       :font-family     sans-stack}}
   [:div
    ;; rf2-5kfxe.8 — domain-coloured accent stripe (:yellow for
    ;; Routing — side-channel attention tone, distinguished from
    ;; app-db's main colour). Per rf2-6xezz / rf2-rb6js the panel no
    ;; longer renders a heading that names itself ("Routing") — the L4
    ;; tab strip is the single source of panel identity. The icon +
    ;; description paragraph stay as orientation chrome.
    [:div {:style {:display     "flex"
                   :align-items "center"
                   :gap         "8px"}}
     ;; rf2-ezx8w — spec/021 §17.1.5 per-panel header icon. 🌐 in
     ;; :yellow (Routing's domain colour via panel-domain->token).
     [:span {:data-testid "rf-causa-routing-panel-icon"
             :aria-hidden "true"
             :style       (t/panel-icon-style :routing)}
      (:routing t/panel-icon)]]
    [:p {:style {:margin      "4px 0 0 0"
                 :color       (:text-tertiary tokens)
                 :font-size   "11px"
                 :line-height 1.4}}
     "Full topology of registered routes with focused-epoch overlay. "
     [:span {:style {:color (:green tokens) :font-weight 600}} "◉ TO"]
     " / "
     [:span {:style {:color (:cyan tokens) :font-weight 600}} "◇ FROM"]
     " / "
     [:span {:style {:color (:yellow tokens) :font-weight 600}} "● HERE"]
     " mark the per-epoch navigation overlay. For the full route catalogue browse + "
     "Simulate-URL surface, switch to Static mode."]]
   (when (and navigated? to-id (not silent?))
     [:span {:data-testid "rf-causa-routing-nav-summary"
             :style       {:color       (:green tokens)
                           :font-size   "11px"
                           :font-family mono-stack}}
      (str "→ " to-id)])])

;; ---- empty (no routes registered) ---------------------------------------

(defn- silent-state
  "Renders when the host app has NO routes registered. Per
  rf2-g3ghh silent-by-default: no placeholder rows, just a
  single-line caption pointing to Static Routes for browse."
  []
  [:div {:data-testid "rf-causa-routing-silent"
         :style       {:padding        "16px"
                       :display        "flex"
                       :flex-direction "column"
                       :gap            "8px"
                       :color          (:text-tertiary tokens)
                       :font-family    sans-stack
                       :font-size      "11px"}}
   [:span {:style {:font-style "italic"}}
    "No routes registered in the host app."]
   [:span {:style {:color (:text-tertiary tokens)}}
    "Register routes via "
    [:code {:style {:color       (:accent-violet tokens)
                    :font-family mono-stack}}
     "re-frame.routing/reg-route"]
    " — the topology will render once the host installs them."]])

;; ---- public view --------------------------------------------------------

(rf/reg-view Panel
  "The Routing tab's root view — topology-plus-overlay shape per
  spec/021 §7. Subscribes to `:rf.causa/routing-tab-data` and renders
  the full route topology with a per-epoch overlay; below the tree
  the 'This epoch' block surfaces Phase / From / To / Match / Events
  (or 'No route activity in this epoch.' when the focused cascade
  carries no routing trace events)."
  []
  (let [{:keys [silent? topology activity from-id to-id navigated? current]
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
     (header {:navigated? navigated? :to-id to-id :silent? silent?})
     (if silent?
       (silent-state)
       [:<>
        (topology-tree topology)
        (this-epoch-block {:activity   activity
                           :from-id    from-id
                           :to-id      to-id
                           :current    current
                           :navigated? navigated?})])]))

;; ---- registration entry --------------------------------------------------

(defn install!
  "Idempotent install for the Routing panel's Causa-side
  registrations. Registers:

    - `:rf.causa/registered-routes` — flat `{<route-id> <meta>}` map
      sourced from `(rf/registrations :route)`. The Static Routes
      panel also reads this sub — process-global, frame-agnostic.
    - `:rf.causa/current-route-slice` — composite over the spine's
      target-frame app-db reading the `:rf/route` slice.
    - `:rf.causa/routing-tab-data` — view-facing topology-plus-overlay
      composite (focused-epoch scoped). Carries `:silent?`, `:topology`,
      `:activity`, `:from-id`, `:to-id`, `:navigated?`, `:current`.
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

  ;; View-facing composite (topology-plus-overlay shape, rf2-3kjlo) -------

  (rf/reg-sub :rf.causa/routing-tab-data
    :<- [:rf.causa/registered-routes]
    :<- [:rf.causa/current-route-slice]
    :<- [:rf.causa/cascades]
    :<- [:rf.causa/focus]
    (fn [[routes-map slice cascades focus] _query]
      (let [focused-cascade (h/focused-cascade cascades
                                               (:dispatch-id focus))]
        (h/project-topology-data routes-map slice focused-cascade))))

  ;; rf2-2moh1 — register the Dynamic Routing tab with the internal L4
  ;; tab registry. Per rf2-nrbs9 Mike's design call (2026-05-18) Routing
  ;; earns its own L3 lens tab between Machines and Issues.
  ;;
  ;; rf2-mkpnb — order bumped 5 → 6 to make room for the new Machines
  ;; Canvas tab at order 5 (sits adjacent to Machines so the two
  ;; machine sub-domain tabs render next to each other).
  ;; Display label is the plural domain noun "Routes" — matching the
  ;; Static Routes tab so the two tab sets share one vocabulary
  ;; (all-plural-domain-noun convention, Mike-direction 2026-05-21).
  ;; Internal id stays `:routing` (id is not a user contract; same
  ;; posture as `:views` rendering as "Views").
  (panel-registry/reg-l4-tab!
    {:id    :routing
     :label "Routes"
     :mnem  "r"
     :modes #{:dynamic}
     :order 6
     :panel Panel})

  nil)
