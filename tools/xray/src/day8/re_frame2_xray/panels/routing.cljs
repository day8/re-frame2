(ns day8.re-frame2-xray.panels.routing
  "Routing tab — three stacked sections (rf2-ad7zx.7, reconciled to the
  Figma design `tools/xray/design-reference/xray_devtools_reference.cljs`
  (the `routes-panel` component)
  + spec/021 §7.2).

  ## Three stacked sections (post-rf2-ad7zx.7 reshape)

  The prior 'Active route tree first + This-epoch KV' shape is
  superseded. The panel now reads top → bottom as three stacked
  sections, each separated from the next by a 1px hairline. Per
  spec/021 §14.1 (rf2-6xezz) the panel carries no self-naming heading
  and no per-panel header icon — content opens directly on the CURRENT
  ROUTE section (the L4 tab strip is the single source of panel
  identity, and the Figma `RoutesPanel` opens the same way):

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
  spacer), matching the Figma `RoutesPanel` `ChevronRight` +
  per-level `paddingLeft`. The tree is always fully expanded (§7.1:
  depth ≤ 4). The **current route is highlighted** (mode-accent row +
  `◀ current` marker) and the focused navigation's FROM→TO is marked on
  it — the overlay glyphs `◉ TO` / `◇ FROM` paint inline on the
  matching rows.

  ## Focus contract (rf2-h0120 alignment)

  The panel reads `:rf.xray/focus` per spec/018; that sub already
  auto-resolves head-fallback via `spine/compose-focus`. No inline
  head-fallback needed at this layer.

  ## Pure hiccup (rf2-tijr)

  Same contract as every other Xray panel — the view is pure hiccup,
  no Reagent / UIx references. Frame isolation comes from the
  enclosing `[rf/frame-provider {:frame :rf/xray}]` in `shell.cljs`.

  ## THE VIEW IS A FRESCO BOUNDARY (rf2-k97c.3)

  [[PanelView]] is an `rf.fresco/defview` — a real React function
  component minted by the re-frame-native view layer — rather than an
  `rf/reg-view`. It follows `panels/module_view.cljs`, increment 1's
  merged template, under the epic's ruled design (rf2-k97c.2, Design
  B): Xray's views are re-authored in Fresco and read through Fresco's
  shipped collector, so their observation no longer depends on
  whichever view build the installed adapter happens to supply.

  The one read is `rf.fresco/sub`, which the collector wires,
  activates, and re-wires AND NOTIFIES on invalidation
  (`re-frame.fresco.impl.collector`). It is not a matter of taste: for
  the extent of a boundary's body `re-frame.fresco.impl.intent/with-frame`
  binds core's refusal tier, so an ambient `@(rf/subscribe …)` REFUSES
  rather than silently reading the wrong frame.

  ## WHERE THIS DEPARTS FROM THE TEMPLATE, AND WHY — THE BRIDGE OWNS THE
  ## PUBLIC NAME

  In `module_view.cljs` the boundary keeps the name `Panel` and the
  bridge is a private `Panel-bridge`, because that panel is L4-only:
  its own `install!` is the sole thing that names it. This panel is one
  of the seven standalone-mountable ones, so the name `routing/Panel`
  is read from five places this bead may not edit or would rather not
  move — `panels.cljs`'s `render-panel!` chokepoint, the panel-gallery
  testbed, two shell/mount test suites, and `spec/api-manifest.edn`
  plus its curated metadata sidecar, which are hot-zone files.

  So the assignment is inverted rather than the mechanism: [[Panel]]
  stays the public callable every one of those sites already holds, and
  it is the `rf.fresco/as-component` bridge; the boundary is the private
  [[PanelView]] behind it. Same one door (`as-component`), same defined
  end — when the shell is itself a Fresco tree the bridge collapses and
  `PanelView` takes the name.

  ## Helpers

  Pure-data projection (`project-topology`, `epoch-routing-activity`,
  `project-topology-data`, plus the lens helpers) lives in
  `routing_helpers.cljc` so the algebra runs under the JVM unit-test
  target. The hiccup projection is [[panel-tree]], a plain fn over the
  composite's VALUE — so the panel's markup stays drivable from the
  node lane without a React commit, which is what the pre-migration
  `(routing/Panel)` call gave those tests."
  (:require [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.panels.routing-helpers :as h]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

;; ---- accent -------------------------------------------------------------
;;
;; The Routing panel's accent is the single GitHub-blue `:accent` token
;; (per `theme.tokens/panel-accent`). Both the CURRENT ROUTE id and the
;; current row in the ROUTE TABLE ride this hue so the operator's eye
;; ties the two surfaces together (RoutesPanel `--devtools-active`).

(def ^:private mode-accent (:accent tokens))

;; ---- shared section primitive -------------------------------------------

(defn- section-caption
  "Uppercase, tracked, muted section caption — the Figma
  `devtools-caption uppercase tracking-wide text-muted` idiom
  (RoutesPanel). Plain caption, no collapse glyph; the panel's
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
  carry a 1px solid `border-top` hairline — the RoutesPanel
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
  (let [{:keys [route-id params path]} current
        id route-id]
    (section
      {:first? true :testid "rf-xray-routing-current"}
      (section-caption "Current route" "rf-xray-routing-current-caption")
      (if (nil? id)
        [:div {:data-testid "rf-xray-routing-current-empty"
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
         [:span {:data-testid "rf-xray-routing-current-id"
                 :style       {:color       mode-accent
                               :font-weight 600}}
          (str id)]
         [:span {:style {:color (:text-tertiary tokens)}} "params"]
         [:span {:data-testid "rf-xray-routing-current-params"}
          (pr-str (or params {}))]
         (when path
           [:span {:data-testid "rf-xray-routing-current-path"
                   :style       {:color (:text-tertiary tokens)}}
            path])]))))

;; ---- §2 NAVIGATION THIS EPOCH -------------------------------------------

(defn- nav-outcome
  "Resolve the {:label :colour} outcome chip for the focused epoch's
  navigation activity. Maps the routing phase (per
  `routing-helpers/epoch-routing-activity`) onto the spec §7.2 outcome
  vocabulary, coloured by result:

    - :on-match           → 'transitioned' (green — a route landed)
    - :navigation-blocked → 'blocked'      (warning — a :can-leave guard refused)
    - :entry-denied       → 'entry denied' (warning — a :can-enter guard refused)
    - :fragment-changed   → 'fragment changed' (info — anchor)
    - navigated? but no destination resolved → 'not-found' (error)"
  [{:keys [phase]} navigated? to-id]
  (cond
    (= phase :on-match)
    {:label "transitioned" :colour (:green tokens)}

    (= phase :navigation-blocked)
    {:label "blocked" :colour (:warning tokens)}

    (= phase :entry-denied)
    {:label "entry denied" :colour (:warning tokens)}

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
    {:first? false :testid "rf-xray-routing-nav"}
    (section-caption "Navigation this epoch" "rf-xray-routing-nav-caption")
    (if (or navigated? (some? activity))
      (let [outcome (nav-outcome activity navigated? to-id)
            params  (or (:match activity) (:params current))]
        [:div {:data-testid "rf-xray-routing-nav-row"
               :style       {:display     "flex"
                             :align-items "center"
                             :gap         "8px"
                             :flex-wrap   "wrap"
                             :font-family mono-stack
                             :font-size   "12px"
                             :color       (:text-primary tokens)}}
         [:span {:data-testid "rf-xray-routing-nav-from"
                 :style       {:color (if from-id
                                        (:info tokens)
                                        (:text-tertiary tokens))}}
          (if from-id (str from-id) "—")]
         [:span {:style {:color (:text-tertiary tokens)}} "──►"]
         [:span {:data-testid "rf-xray-routing-nav-to"
                 :style       {:color       (if to-id mode-accent
                                              (:text-tertiary tokens))
                               :font-weight 600}}
          (if to-id (str to-id) "—")]
         (when params
           [:<>
            [:span {:style {:color (:text-tertiary tokens) :margin-left "8px"}}
             "params"]
            [:span {:data-testid "rf-xray-routing-nav-params"}
             (pr-str params)]])
         (when outcome
           [:<>
            [:span {:style {:color (:text-tertiary tokens) :margin-left "8px"}}
             "outcome:"]
            [:span {:data-testid "rf-xray-routing-nav-outcome"
                    :style       {:color       (:colour outcome)
                                  :font-weight 600}}
             (:label outcome)]])])
      [:div {:data-testid "rf-xray-routing-no-activity"
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
  Figma `RoutesPanel` `paddingLeft: level * 2 + 0.5rem` — depth-0
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
  the path when the focused epoch navigated to/from this route.

  The row owns its own React `:key`, in its ATTRIBUTE map. The caller
  used to attach it as `^{:key …}` reader metadata on the CALL FORM
  `(route-table-row entry)` — metadata on a source list, which the call
  discards when it returns a fresh vector, so no key ever reached React
  (the same defect `cancellation_cascade.cljs` records under rf2-ppzid
  and works around with `with-meta`). Putting it in the attribute map
  is the one spelling BOTH substrates read: Reagent's `get-react-key`
  and Fresco's codec head table. A portability fix rather than a Fresco
  accommodation. `route-id` is unique across a topology by
  construction — the topology is projected from a `{<route-id> <meta>}`
  map — and a nil id degrades to the same `\"\"` the old expression
  produced."
  [{:keys [row depth has-children? cycle-root?]}]
  (let [{:keys [route-id path doc marker]} row
        current?  (= marker :here)
        glyph     (marker-glyph marker)
        testid    (str "rf-xray-routing-table-row-"
                       (when route-id (name route-id)))]
    [:div {:key           (str route-id)
           :data-testid   testid
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
     (when cycle-root?
       [:span {:data-testid (str testid "-cycle-badge")
               :title       "This route's :parent metadata forms a cycle — shown at the top level so it is not hidden."
               :style       {:margin-left  "8px"
                             :padding      "1px 5px"
                             :border-radius "3px"
                             :background   (:bg-1 tokens)
                             :color        (:red tokens)
                             :font-family  sans-stack
                             :font-size    "10px"
                             :font-weight  600
                             :text-transform "uppercase"
                             :letter-spacing "0.4px"}}
        "↻ cycle"])
     (when glyph
       [:span {:data-testid (str "rf-xray-routing-table-marker-" (name marker))
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
       [:span {:data-testid "rf-xray-routing-table-current-marker"
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
  surrounding panel renders the silent state instead.

  `route-table-row` is CALLED here, never used as a hiccup head: a
  plain fn in head position is a loud error under Fresco and a silent
  extra component under Reagent, and neither is wanted. The React key
  rides in the row's own attribute map — see its docstring."
  [topology]
  (section
    {:first? false :testid "rf-xray-routing-table"}
    (section-caption "Route table" "rf-xray-routing-table-caption")
    [:div {:data-testid "rf-xray-routing-table-body"
           :style       {:display        "flex"
                         :flex-direction "column"
                         :gap            "2px"}}
     (into [:<>] (map route-table-row topology))]))

;; ---- empty (no routes registered) ---------------------------------------

(defn- silent-state
  "Renders when the host app has NO routes registered. Per
  rf2-g3ghh silent-by-default: no placeholder rows, just a
  single-line caption pointing to Static Routes for browse."
  []
  [:div {:data-testid "rf-xray-routing-silent"
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

(defn panel-tree
  "The Routing tab's markup, as a pure function of the
  `:rf.xray/routing-tab-data` composite's VALUE — three stacked
  sections per spec/021 §7.2 (reconciled to RoutesPanel), top → bottom:

    1. CURRENT ROUTE          — active id + params + matched path.
    2. NAVIGATION THIS EPOCH  — FROM ──► TO + params + outcome
                                (quiet when the focused event isn't a nav).
    3. ROUTE TABLE            — the full registered route graph as a
                                tree, current row highlighted + FROM/TO
                                overlay glyphs.

  Content starts immediately at the CURRENT ROUTE section — per spec/021
  §14.1 (rf2-6xezz) every L4 panel scrubs its self-naming heading + the
  per-panel header icon (the L4 tab strip is the single source of panel
  identity). This matches the Figma `RoutesPanel`, which opens
  directly on the CURRENT ROUTE section with no header chrome.

  When the host has no routes registered this renders the
  silent-by-default caption (no sections).

  SEPARATED FROM THE BOUNDARY DELIBERATELY (rf2-k97c.3). The read moved
  into [[PanelView]], leaving this a value → hiccup projection with no
  reactive surface of its own — the same split
  `routing_helpers.cljc` already makes for the data algebra, one layer
  up. It takes NO reads, so it is drivable from the node lane by handing
  it a composite value, which is what `(routing/Panel)` used to give
  those tests before the panel became a React component."
  [{:keys [silent? topology activity from-id to-id navigated? current]
    :as _data}]
  [:section {:data-testid "rf-xray-routing"
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
      (route-table-section topology)])])

(rf.fresco/defview ^:private PanelView
  "The Routing tab's root — a FRESCO BOUNDARY (rf2-k97c.3), not an
  `rf/reg-view`. Reads `:rf.xray/routing-tab-data` and hands the value
  to [[panel-tree]].

  The READ is `rf.fresco/sub`, a plain call the collector records an
  edge for — no deref, no reaction owned by the installed adapter, and a
  re-wire that NOTIFIES when the substrate disposes the underlying
  derived value. That is the third of the epic's three couplings, and it
  is the one a first-paint smoke test cannot see.

  The FRAME the read resolves against comes from React context, which
  the enclosing frame boundary writes — `rf/frame-provider` and
  `rf.fresco/frame-provider` write the SAME context (core's
  `re-frame.adapter.context/frame-context`) — so this boundary resolves
  `:rf/xray` identically under today's Reagent-rendered shell and under
  the Fresco root Xray will own. It never consults
  `:adapter/current-component`, the hook a foreign root cannot answer.

  PRIVATE, and [[Panel]] in front of it is the public name — see the ns
  docstring's section on the inverted bridge. The argument is the
  ordinary one-props-map vector every `defview` takes; this panel reads
  nothing from props, so it is destructured away."
  [_props]
  (panel-tree (rf.fresco/sub [:rf.xray/routing-tab-data])))

;; ---- the migration bridge (rf2-k97c.3) -----------------------------------
;;
;; Xray's shell, `panels.cljs`'s `render-panel!` and the panel-gallery
;; testbed are all still `reg-view` trees rendered by the installed
;; adapter, and each mounts this panel as the hiccup head `[routing/Panel]`
;; — which a React component is not.
;;
;; `rf.fresco/as-component` is Fresco's own outward door for exactly
;; this: it answers a real React component for a boundary, which a React
;; parent (UIx, Reagent or plain JavaScript) mounts UNDER THE FRAME IT IS
;; ALREADY IN, taking the frame from React context rather than from a
;; second root. So there is no second root here, no adapter-kind branch,
;; and no props ABI — the three things the spike's Arm A needed and the
;; ruling counted against it.
;;
;; THIS IS SCAFFOLDING WITH A DEFINED END. When the shell is itself a
;; Fresco tree, `PanelView` takes the name `Panel` directly, `[:>]` goes,
;; and both defs below are deleted.

(def ^:private Panel-component
  "The React component [[PanelView]] presents as, for a non-Fresco
  parent. Declared once at top level beside the view, as
  `rf.fresco/as-component`'s contract requires — deriving it per render
  would mint a new component type every time and remount the panel on
  each parent render."
  (rf.fresco/as-component PanelView))

(defn Panel
  "The Routing tab's public callable — the one every existing mount site
  already holds: `panels.cljs`'s `render-panel!` chokepoint (and its
  `mount-routing!` facade), `shell/detail-panel`'s tab case, the
  panel-gallery testbed, and the `reg-l4-tab!` entry below.

  Since rf2-k97c.3 it is the migration bridge rather than the view:
  Reagent-shaped hiccup interoping to the React component
  [[PanelView]] presents as. The enclosing `rf/frame-provider` is what
  puts `:rf/xray` in React context for it — this returns no provider of
  its own and needs none.

  Callers wanting the MARKUP as data — the node-lane view tests — call
  [[panel-tree]] with the composite's value instead; this returns an
  interop vector, not a tree to walk."
  []
  [:> Panel-component {}])

;; ---- production value sources --------------------------------------------
;;
;; The raw values the production data subs read. Shared with the
;; test-override seam (`install-test-overrides!` below) so the override
;; branch — `(or override (real …))` — lives in ONE place (the seam),
;; not duplicated across production and test surfaces (rf2-e8330v).

(defn- registered-routes-value
  "The flat `{<route-id> <meta>}` map sourced from the HOST app's
  default-realm `:route` registrar.

  Read via `rf/registrations` with `{:source :store …}` (the SOURCE-STORE read, which never
  consults a bound image generation), NOT `{:frame …}`: this fn runs inside
  the `:rf.xray/registered-routes` sub COMPUTATION, and Xray seats in its OWN
  image-loaded `:rf/xray` frame, so the sub build binds the registrar to Xray's
  image generation — a bare read would resolve through Xray's OWN image (which
  carries no `:route` ids) and return `{}`, collapsing the Routing panel to its
  silent empty-table state. The host's routes live in the process-global
  default-realm registrar; `rf/registrations` reads it directly. See
  spec/API.md §Public registrar query API."
  []
  (rf/registrations {:source :store :kind :route}))

(defn- current-route-slice-value
  "The live route slice off the target frame's runtime-db
  (`[:rf.runtime/routing :current]`; EP-0001 rf2-vzld77 — runtime-db
  state, not app-db)."
  [target-runtime-db]
  (when (map? target-runtime-db)
    (get-in target-runtime-db [:rf.runtime/routing :current])))

;; ---- registration entry --------------------------------------------------

(defn install!
  "Idempotent install for the Routing panel's Xray-side
  registrations. Registers:

    - `:rf.xray/registered-routes` — flat `{<route-id> <meta>}` map
      sourced from `(rf/registrations {:source :store :kind :route})`. The Static Routes
      panel also reads this sub — process-global, frame-agnostic.
    - `:rf.xray/current-route-slice` — composite over the spine's
      target-frame RUNTIME-DB reading the routing slice at
      `[:rf.runtime/routing :current]` (EP-0001 rf2-vzld77 — the route
      slice is framework-owned runtime-db state, not app-db).
    - `:rf.xray/routing-tab-data` — view-facing topology-plus-overlay
      composite (focused-epoch scoped). Carries `:silent?`, `:topology`,
      `:activity`, `:from-id`, `:to-id`, `:navigated?`, `:current`.

  The test-only override seam (`:rf.xray/set-registered-routes-override-
  for-test`, `:rf.xray/set-current-route-slice-override-for-test` + the
  companion `*-override` subs) is NOT installed here — production
  registration carries no `-for-test` ids. Tests opt into it via
  `install-test-overrides!` (rf2-e8330v / xxo3zz F3).

  The browse / search / Simulate-URL slots (`:rf.xray.routing/
  query`, `:rf.xray.routing/sim-url`, `:rf.xray.routing/expanded`,
  `:rf.xray.routing/toggle-row`, etc.) were promoted to the Static
  Routes panel per rf2-o5f5f.3 and now live under
  `:rf.xray.static.routes/*` (installed by
  `static/routes/panel/install!`)."
  []

  ;; Production data subs -------------------------------------------------

  (rf/reg-sub :rf.xray/registered-routes
    {:inputs [[:rf.xray/trace-buffer]]}
    (fn [[_buffer] _query]
      (registered-routes-value)))

  (rf/reg-sub :rf.xray/current-route-slice
    {:inputs [[:rf.xray/target-frame-runtime-db]]}
    (fn [[target-runtime-db] _query]
      (current-route-slice-value target-runtime-db)))

  ;; View-facing composite (topology-plus-overlay shape, rf2-3kjlo) -------

  (rf/reg-sub :rf.xray/routing-tab-data
    {:inputs [[:rf.xray/registered-routes]
              [:rf.xray/current-route-slice]
              [:rf.xray/event-bundles]
              [:rf.xray/focus]]}
    (fn [[routes-map slice event-bundles focus] _query]
      ;; rf2-bz7flo — pass the whole focus map so the lookup is
      ;; frame-strict (dispatch ids collide across frames). Passing only
      ;; `(:dispatch-id focus)` could surface route overlays from a foreign
      ;; frame's same-id event-bundle while focus is on another frame.
      (let [focused-event-bundle (h/focused-event-bundle event-bundles focus)]
        (h/project-topology-data routes-map slice focused-event-bundle))))

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
     ;; rf2-k97c.3 — still `Panel`, and deliberately so: the registry
     ;; requires `:panel` to be CALLABLE and the shell mounts it as a
     ;; Reagent hiccup head, which is what the bridge now is. The
     ;; boundary behind it is the private `PanelView`.
     :panel Panel})

  nil)

;; ---- test-only override seam (rf2-e8330v / xxo3zz F3) ---------------------

(defn install-test-overrides!
  "Install the Routing panel's test-only override seam. Registers the
  `:rf.xray/set-*-override-for-test` events + companion `*-override`
  subs, then RE-registers the production data subs to layer the override
  read on top (`(or override (real …))`). Tests opt in by calling this
  AFTER `register-xray-handlers!` (typically via
  `test-support/install-test-overrides!`). **Test-only — never call
  from production.**"
  []
  (rf/reg-event :rf.xray/set-registered-routes-override-for-test
    (fn [{:keys [db]} [_ ov]]
      {:db (if (nil? ov)
        (dissoc db :registered-routes-override)
        (assoc db :registered-routes-override ov))}))
  (rf/reg-sub :rf.xray/registered-routes-override
    (fn [db _query]
      (get db :registered-routes-override)))

  (rf/reg-event :rf.xray/set-current-route-slice-override-for-test
    (fn [{:keys [db]} [_ ov]]
      {:db (if (nil? ov)
        (dissoc db :current-route-slice-override)
        (assoc db :current-route-slice-override ov))}))
  (rf/reg-sub :rf.xray/current-route-slice-override
    (fn [db _query]
      (get db :current-route-slice-override)))

  ;; Override-aware re-registration of the production data subs.
  (rf/reg-sub :rf.xray/registered-routes
    {:inputs [[:rf.xray/trace-buffer] [:rf.xray/registered-routes-override]]}
    (fn [[_buffer override] _query]
      (or override (registered-routes-value))))

  (rf/reg-sub :rf.xray/current-route-slice
    {:inputs [[:rf.xray/target-frame-runtime-db] [:rf.xray/current-route-slice-override]]}
    (fn [[target-runtime-db override] _query]
      (if (some? override)
        override
        (current-route-slice-value target-runtime-db))))
  nil)
