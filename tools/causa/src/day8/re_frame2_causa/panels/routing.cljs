(ns day8.re-frame2-causa.panels.routing
  "Routing tab — three stacked sections (rf2-ad7zx.7, reconciled to the
  Figma design `tools/causa/design-reference/components/RoutesPanel.tsx`
  + spec/021 §7.2).

  ## Three stacked sections (post-rf2-ad7zx.7 reshape)

  The prior 'Active route tree first + This-epoch KV' shape is
  superseded. The panel now reads top → bottom as three stacked
  sections, each separated from the next by a 1px hairline. Per
  spec/021 §14.1 (rf2-6xezz) the panel carries no self-naming heading
  and no per-panel header icon — content opens directly on the CURRENT
  ROUTE section (the L4 tab strip is the single source of panel
  identity, and the Figma `RoutesPanel.tsx` opens the same way):

      │ CURRENT ROUTE                                                   │
      │   :user/profile    params {:id 42}    /users/42                 │
      │ ─────────────────────────────────────────────────────────────  │
      │ NAVIGATION THIS EPOCH    (event-driven · quiet when not a nav)  │
      │   :dashboard ──► :user/profile  params {:id 42}  transitioned   │
      │ ─────────────────────────────────────────────────────────────  │
      │ ROUTE TABLE   (registered · current highlighted · tree)        │
      │   :home               /                                         │
      │   :dashboard          /dashboard                                │
      │   ▾ :users            /users                                    │
      │       :user/profile   /users/:id            ◀ current          │
      │   :settings           /settings                                 │
      │                                                                 │
      │ Empty (no route activity this epoch): CURRENT ROUTE + ROUTE     │
      │   TABLE still render; NAVIGATION THIS EPOCH reads 'No route     │
      │   activity in this epoch.'                                      │
      └─────────────────────────────────────────────────────────────────┘

  ## Section 1 — CURRENT ROUTE (always shown)

  The active route **id** (mode-accent, bold), its **params**, and the
  **matched path / URL**. The 'where am I'. Renders even when the
  focused epoch carried no navigation. When the host has no active
  route slice the section reads a calm caption.

  ## Section 2 — NAVIGATION THIS EPOCH (event-driven lens)

  When the focused event navigated: **FROM route ──► TO route**, the
  **params**, and the **outcome** (transitioned · blocked · cancelled ·
  not-found · fragment-changed; coloured by result). Quiet when the
  focused event isn't a navigation — reads 'No route activity in this
  epoch.' (the topology-plus-overlay contract: the table below still
  shows the full registered graph).

  ## Section 3 — ROUTE TABLE (registered route graph as a tree)

  All registered routes (id → path pattern) drawn as an indented tree
  when nested — nested routes left-indent by depth and their parent
  rows carry a `▾` disclosure chevron (leaf rows get an aligned
  spacer), matching the Figma `RoutesPanel.tsx` `ChevronRight` +
  per-level `paddingLeft`. The tree is always fully expanded (§7.1:
  depth ≤ 4). The **current route is highlighted** (mode-accent row +
  `◀ current` marker) and the focused navigation's FROM→TO is marked on
  it — the overlay glyphs `◉ TO` / `◇ FROM` paint inline on the
  matching rows.

  ## Focus contract (rf2-h0120 alignment)

  The panel reads `:rf.causa/focus` per spec/018; that sub already
  auto-resolves head-fallback via `spine/compose-focus`. No inline
  head-fallback needed at this layer.

  ## Pure hiccup (rf2-tijr)

  Same contract as every other Causa panel — the view is pure hiccup,
  no Reagent / UIx / Helix references. Frame isolation comes from the
  enclosing `[rf/frame-provider {:frame :rf/causa}]` in `shell.cljs`.

  ## Helpers

  Pure-data projection (`project-topology`, `epoch-routing-activity`,
  `project-topology-data`, plus the lens helpers) lives in
  `routing_helpers.cljc` so the algebra runs under the JVM unit-test
  target."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panel-registry :as panel-registry]
            [day8.re-frame2-causa.panels.routing-helpers :as h]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

;; ---- accent -------------------------------------------------------------
;;
;; The Routing panel's accent is the single GitHub-blue `:accent` token
;; (per `panel-domain->token`). Both the CURRENT ROUTE id and the
;; current row in the ROUTE TABLE ride this hue so the operator's eye
;; ties the two surfaces together (RoutesPanel.tsx `--devtools-active`).

(def ^:private mode-accent (:accent tokens))

;; ---- shared section primitive -------------------------------------------

(defn- section-caption
  "Uppercase, tracked, muted section caption — the Figma
  `devtools-caption uppercase tracking-wide text-muted` idiom
  (RoutesPanel.tsx). Plain caption, no collapse glyph; the panel's
  three sections are always visible per spec §7.2."
  [label testid]
  [:div {:data-testid testid
         :style       {:color          (:text-tertiary tokens)
                       :font-family    sans-stack
                       :font-size      "10px"
                       :font-weight    600
                       :text-transform "uppercase"
                       :letter-spacing "0.5px"
                       :margin-bottom  "8px"}}
   label])

(defn- section
  "Wrap a section caption + body. `first?` omits the top hairline (the
  first section sits flush under the header). Sections after the first
  carry a 1px solid `border-top` hairline — the RoutesPanel.tsx
  `border-t border-[var(--devtools-border)]` separator."
  [{:keys [first? testid]} caption body]
  [:section {:data-testid testid
             :style       (cond-> {:padding "12px 16px"}
                            (not first?)
                            (assoc :border-top
                                   (str "1px solid " (:border-subtle tokens))))}
   caption
   body])

;; ---- §1 CURRENT ROUTE ---------------------------------------------------

(defn- current-route-section
  "§1 — the active route id (mode-accent, bold), its params, and the
  matched path. Always renders. When no active slice is present reads a
  calm caption (the host has no current route)."
  [{:keys [current]}]
  (let [{:keys [id params path]} current]
    (section
      {:first? true :testid "rf-causa-routing-current"}
      (section-caption "Current route" "rf-causa-routing-current-caption")
      (if (nil? id)
        [:div {:data-testid "rf-causa-routing-current-empty"
               :style       {:color       (:text-tertiary tokens)
                             :font-family sans-stack
                             :font-style  "italic"
                             :font-size   "12px"}}
         "No active route."]
        [:div {:style {:display      "flex"
                       :align-items  "center"
                       :gap          "12px"
                       :flex-wrap    "wrap"
                       :font-family  mono-stack
                       :font-size    "12px"
                       :color        (:text-primary tokens)}}
         [:span {:data-testid "rf-causa-routing-current-id"
                 :style       {:color       mode-accent
                               :font-weight 600}}
          (str id)]
         [:span {:style {:color (:text-tertiary tokens)}} "params"]
         [:span {:data-testid "rf-causa-routing-current-params"}
          (pr-str (or params {}))]
         (when path
           [:span {:data-testid "rf-causa-routing-current-path"
                   :style       {:color (:text-tertiary tokens)}}
            path])]))))

;; ---- §2 NAVIGATION THIS EPOCH -------------------------------------------

(defn- nav-outcome
  "Resolve the {:label :colour} outcome chip for the focused epoch's
  navigation activity. Maps the routing phase (per
  `routing-helpers/epoch-routing-activity`) onto the spec §7.2 outcome
  vocabulary, coloured by result:

    - :on-match           → 'transitioned' (green — a route landed)
    - :navigation-blocked → 'blocked'      (warning — a guard refused)
    - :fragment-changed   → 'fragment changed' (info — anchor)
    - navigated? but no destination resolved → 'not-found' (error)"
  [{:keys [phase]} navigated? to-id]
  (cond
    (= phase :on-match)
    {:label "transitioned" :colour (:green tokens)}

    (= phase :navigation-blocked)
    {:label "blocked" :colour (:warning tokens)}

    (= phase :fragment-changed)
    {:label "fragment changed" :colour (:info tokens)}

    (and navigated? (nil? to-id))
    {:label "not-found" :colour (:error tokens)}

    navigated?
    {:label "transitioned" :colour (:green tokens)}

    :else nil))

(defn- navigation-section
  "§2 — the event-driven lens. When the focused epoch navigated:
  FROM ──► TO + params + an outcome chip coloured by result. Quiet
  caption ('No route activity in this epoch.') when the focused event
  isn't a navigation — the topology-plus-overlay contract keeps the
  ROUTE TABLE below visible regardless."
  [{:keys [activity from-id to-id navigated? current]}]
  (section
    {:first? false :testid "rf-causa-routing-nav"}
    (section-caption "Navigation this epoch" "rf-causa-routing-nav-caption")
    (if (or navigated? (some? activity))
      (let [outcome (nav-outcome activity navigated? to-id)
            params  (or (:match activity) (:params current))]
        [:div {:data-testid "rf-causa-routing-nav-row"
               :style       {:display     "flex"
                             :align-items "center"
                             :gap         "8px"
                             :flex-wrap   "wrap"
                             :font-family mono-stack
                             :font-size   "12px"
                             :color       (:text-primary tokens)}}
         [:span {:data-testid "rf-causa-routing-nav-from"
                 :style       {:color (if from-id
                                        (:info tokens)
                                        (:text-tertiary tokens))}}
          (if from-id (str from-id) "—")]
         [:span {:style {:color (:text-tertiary tokens)}} "──►"]
         [:span {:data-testid "rf-causa-routing-nav-to"
                 :style       {:color       (if to-id mode-accent
                                              (:text-tertiary tokens))
                               :font-weight 600}}
          (if to-id (str to-id) "—")]
         (when params
           [:<>
            [:span {:style {:color (:text-tertiary tokens) :margin-left "8px"}}
             "params"]
            [:span {:data-testid "rf-causa-routing-nav-params"}
             (pr-str params)]])
         (when outcome
           [:<>
            [:span {:style {:color (:text-tertiary tokens) :margin-left "8px"}}
             "outcome:"]
            [:span {:data-testid "rf-causa-routing-nav-outcome"
                    :style       {:color       (:colour outcome)
                                  :font-weight 600}}
             (:label outcome)]])])
      [:div {:data-testid "rf-causa-routing-no-activity"
             :style       {:color       (:text-tertiary tokens)
                           :font-family sans-stack
                           :font-style  "italic"
                           :font-size   "12px"}}
       "No route activity in this epoch."])))

;; ---- §3 ROUTE TABLE -----------------------------------------------------

(defn- marker-glyph
  "Resolve the overlay glyph + colour for a route-table row. Returns a
  `{:glyph :colour :label}` map; nil when the row carries no marker.

  Per spec/021 §7.2:
    - `:to`   → green `◉` dot (navigation destination)
    - `:from` → `◇` outline diamond (navigation origin)
    - `:here` is folded into the mode-accent row highlight + the
      `◀ current` marker (it is not painted as a glyph)."
  [marker]
  (case marker
    :to   {:glyph "◉" :colour (:green tokens)         :label "TO"}
    :from {:glyph "◇" :colour (:info tokens)          :label "FROM"}
    nil))

(defn- depth->indent
  "Left indent for a route-table row at the given depth. Mirrors the
  Figma `RoutesPanel.tsx` `paddingLeft: level * 2 + 0.5rem` — depth-0
  rows sit at the base 0.5rem, each nesting level adds 2rem. The
  structure reads off indentation alone (per §17.4: tree nesting is
  whitespace, not box-drawing text glyphs)."
  [depth]
  (str (+ 0.5 (* 2 depth)) "rem"))

(defn- disclosure-cell
  "The leading cell of a route-table row. Parent routes (those with
  nested children) paint a `▾` disclosure chevron — the routing tree is
  always fully expanded per §7.1 (depth ≤ 4), so the chevron is a
  static affordance signalling 'this route has children', matching the
  Figma `ChevronRight` parent glyph + the spec mockup's `▾ :users`.
  Leaf rows render an aligned spacer so every id column lines up
  (Figma `{!route.children && <span className=\"w-3\" />}`)."
  [has-children? testid]
  (if has-children?
    [:span {:data-testid (str testid "-chevron")
            :aria-hidden "true"
            :style       {:color       (:text-tertiary tokens)
                          :font-size   "10px"
                          :min-width   "12px"
                          :user-select "none"}}
     "▾"]
    [:span {:aria-hidden "true"
            :style       {:min-width "12px"}}]))

(defn- route-table-row
  "Render one route in the ROUTE TABLE. The current route's row rides
  the mode accent (highlight background + accent id + `◀ current`
  marker); other rows are quiet. Nested routes indent by depth and
  their parent rows carry a `▾` disclosure chevron (leaves get an
  aligned spacer). The FROM/TO overlay glyph paints to the right of
  the path when the focused epoch navigated to/from this route."
  [{:keys [row depth has-children?]}]
  (let [{:keys [route-id path doc marker]} row
        current?  (= marker :here)
        glyph     (marker-glyph marker)
        testid    (str "rf-causa-routing-table-row-"
                       (when route-id (name route-id)))]
    [:div {:data-testid   testid
           :data-route-id (when route-id (str route-id))
           :data-marker   (when marker (name marker))
           :data-current  (when current? "true")
           :style {:display      "flex"
                   :align-items  "center"
                   :gap          "8px"
                   :padding      (str "3px 8px 3px " (depth->indent depth))
                   :border-radius "3px"
                   :font-family  mono-stack
                   :font-size    "12px"
                   :color        (if current? mode-accent (:text-primary tokens))
                   :font-weight  (if current? 600 400)
                   :background   (cond
                                   current?         (:bg-active tokens)
                                   (= marker :to)   (:bg-active tokens)
                                   :else            "transparent")}}
     (disclosure-cell has-children? testid)
     [:span {:data-testid (str testid "-id")
             :style       {:min-width "8rem"
                           :color     (if current? mode-accent
                                       (:text-primary tokens))}}
      (str route-id)]
     [:span {:data-testid (str testid "-path")
             :style       {:color (:text-tertiary tokens)}}
      path]
     (when glyph
       [:span {:data-testid (str "rf-causa-routing-table-marker-" (name marker))
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
        doc])
     (when current?
       [:span {:data-testid "rf-causa-routing-table-current-marker"
               :style       {:margin-left "auto"
                             :color       mode-accent
                             :font-family sans-stack
                             :font-size   "10px"
                             :font-weight 600
                             :text-transform "uppercase"
                             :letter-spacing "0.5px"}}
        "◀ current"])]))

(defn- route-table-section
  "§3 — the full registered route graph as a tree (always visible per
  the topology-plus-overlay contract). Empty topology vector ⇒ the
  surrounding panel renders the silent state instead."
  [topology]
  (section
    {:first? false :testid "rf-causa-routing-table"}
    (section-caption "Route table" "rf-causa-routing-table-caption")
    [:div {:data-testid "rf-causa-routing-table-body"
           :style       {:display        "flex"
                         :flex-direction "column"
                         :gap            "2px"}}
     (into [:<>]
           (for [entry topology]
             ^{:key (str (-> entry :row :route-id))}
             (route-table-row entry)))]))

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
    [:code {:style {:color       (:accent tokens)
                    :font-family mono-stack}}
     "re-frame.routing/reg-route"]
    " — the route table will render once the host installs them."]])

;; ---- public view --------------------------------------------------------

(rf/reg-view Panel
  "The Routing tab's root view — three stacked sections per spec/021 §7.2
  (reconciled to RoutesPanel.tsx). Subscribes to
  `:rf.causa/routing-tab-data` and renders, top → bottom:

    1. CURRENT ROUTE          — active id + params + matched path.
    2. NAVIGATION THIS EPOCH  — FROM ──► TO + params + outcome
                                (quiet when the focused event isn't a nav).
    3. ROUTE TABLE            — the full registered route graph as a
                                tree, current row highlighted + FROM/TO
                                overlay glyphs.

  Content starts immediately at the CURRENT ROUTE section — per spec/021
  §14.1 (rf2-6xezz) every L4 panel scrubs its self-naming heading + the
  per-panel header icon (the L4 tab strip is the single source of panel
  identity). This matches the Figma `RoutesPanel.tsx`, which opens
  directly on the CURRENT ROUTE section with no header chrome.

  When the host has no routes registered the panel renders the
  silent-by-default caption (no sections)."
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
                             :font-size      "14px"
                             :overflow       "auto"}}
     (if silent?
       (silent-state)
       [:<>
        (current-route-section {:current current})
        (navigation-section {:activity   activity
                             :from-id    from-id
                             :to-id      to-id
                             :navigated? navigated?
                             :current    current})
        (route-table-section topology)])]))

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
