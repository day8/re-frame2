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

  ## Public surface

  - `Panel`      — the tab's root. Since rf2-k97c.3 an
                   `rf.fresco/defview` BOUNDARY — a real React function
                   component, not an `rf/reg-view`. The LAST of the five
                   Static sub-tabs to migrate.
  - `panel-tree` — the whole body, as a pure fn of the values [[Panel]]
                   reads, a frame-bound dispatcher and the `as-child`
                   spelling for the browse-list REAGENT ISLAND.
  - `install!`   — idempotent install for the subs, events and the L4
                   tab registration.

  ## Frame isolation

  The FRAME comes from React context, which the enclosing frame boundary
  writes — `rf/frame-provider` (today's Reagent-rendered Static shell)
  and `rf.fresco/frame-provider` write the SAME context, so the reads
  resolve `:rf/xray` under either root. Nothing here consults
  `:adapter/current-component`, the hook a foreign root cannot answer.

  ## Pure hiccup, and the ONE Reagent reference

  The markup is still pure hiccup. `reagent.core/as-element` appears in
  exactly one place — [[Panel]]'s `as-child` argument — and is the
  migration seam [[panel-tree]] documents, not a view-layer dependency."
  (:require [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [reagent.core :as r]
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

;; ---- the body, as a pure fn of the reads' values --------------------------

(defn panel-tree
  "The Static Routes tab's WHOLE body, as a pure function of the four
  values [[Panel]] reads, the frame-bound `dispatch` the list and the
  Simulate-URL box need, and the `as-child` spelling for the browse-list
  REAGENT ISLAND.

  SPLIT OUT OF [[Panel]] BY rf2-k97c.3, and the split is `defview`'s own
  documented extract-a-helper spelling rather than an invention: a
  boundary's body may only run inside a React render window, so
  `(Panel)` is no longer a callable that answers hiccup, while this fn
  is ordinary values → hiccup and stays worth driving from the fast node
  lane.

  `ui` is the map [[browse-list/render]] already takes —
  `{:expanded :sim-open :routes-map}` — threaded through unchanged.

  ## WHY THE ISLAND, MEASURED RATHER THAN ASSUMED

  The migration's standard repair for a plain fn in hiccup head position
  is to CALL the helper instead of heading with it. It works only when
  the helper answers hiccup AND that hiccup is head-free ALL THE WAY
  DOWN — the second limit `static/machines` recorded. It is not met
  here. Census of every symbol-headed hiccup vector WRITTEN IN the four
  `static/routes/*.cljs` files, taken at the base of this slice (line
  numbers drift — they are here to make the census re-runnable, not to
  be cited):

      panel.cljs        [browse-list/render …]        ×2   (this file)
      browse_list.cljs  [search-box/search-box …]
      browse_list.cljs  [route-row …]
      browse_list.cljs  [row-expand/render …]
      simulate_url.cljs [candidate-row …]
      row_expand.cljs   [sim-nav-toggle …]
      row_expand.cljs   [jump-button …]
      row_expand.cljs   [sim-nav/preview …]
      simulate_nav.cljs none

  NINE IN-FILE SITES, AND THE `search-box/search-box` ROW IS THE ONE
  THE FIRST PASS OF THIS CENSUS MISSED (rf2-k97c.3, routes-witness
  slice). Its head sits at END OF LINE — the opening bracket, the
  symbol, then the line break, with the props map on the next line —
  and a head pattern anchored on a following SPACE cannot see that.
  Re-run the census with the end-of-line case included, or the count
  comes back one short in the reassuring direction. LINE NUMBERS ARE
  DELIBERATELY GONE from the rows above for the same reason the
  original note gave for printing them: they drift, they were already
  drifting, and a stale number invites citation.

  AND A TENTH THAT NO SYNTACTIC CENSUS OF THOSE FILES CAN SEE, because a
  CALL into a fifth file RETURNS it: `row_expand.cljs` calls
  `edn/inspect`, and `views/edn_widget.cljs`'s `inspect` answers
  `[ei/edn-inspector …]` — a REAGENT component, hence a plain fn head.
  That widget namespace already anticipates the migration with a second
  head, `inspect-view`, emitting the Fresco boundary
  `[ei/edn-inspector-view …]`; under the island `inspect` stays correct,
  because the island IS Reagent. So read the census as a LOWER BOUND on
  a subtree-wide claim, and note the shape: a call site is not evidence
  of a head-free subtree — only reading what the callee returns is.

  Calling this file's two would move the HD-016 site one level down into
  `browse_list.cljs`, and repairing THAT reaches `row_expand.cljs`, and
  so on — FOUR files for one panel plus the widget facade, none of them
  coupled to this file's reads. `codec/head-kind` grades a plain fn
  `:invalid` and `vec->element` raises `:rf.error/fresco-bad-head`, so
  every one of those out-of-file sites would throw at first paint.

  MEASURED, NOT ARGUED, and the measurement is worth more than the
  census. Planting exactly the naive migration — `rf.fresco/sub` for the
  reads, `(:dispatch (rf/capture-frame))` for the dispatcher, and the
  hiccup left as it stood with NO `as-child` — the Static Routes tab
  never paints at all: the browser gate fails with `expected Static
  sub-tab :routes real panel root rf-xray-static-routes mounted …
  (last=null)`. On that same tree `npm run test:cljs` is GREEN, at
  IDENTICAL totals, because the node lane's `as-child` is `identity` and
  so the seam is invisible to it BY CONSTRUCTION. A node-lane green is
  not evidence about this seam; only the browser lane is.

  So the door is the same one `static/shell.cljs` and
  `static/machines/definition_detail.cljs` already use: Fresco's own ABI
  says a React ELEMENT is a legal child anywhere, reached through an
  `as-child` seam. `identity` for a hiccup caller and the node lane,
  which leaves the browse list exactly the fn-headed vector it has
  always been; `reagent.core/as-element` for the boundary, which answers
  a React element Fresco splices in as a child.

  THE SIMULATE-URL HEADER IS CALLED, NOT HEADED — it always was — and it
  STILL needs the seam, because the hiccup it answers contains
  `[candidate-row c]`. That is the same limit stated from the other
  side, and it is why the call form alone is not evidence of safety.

  SCAFFOLDING WITH A DEFINED END: when `browse_list`, `row_expand`,
  `simulate_url` and `simulate_nav` are themselves head-free (each one a
  mechanical call-repair, and none of them coupled to this file's
  reads), `as-child` goes and the two islands become ordinary children.

  PURE: every helper it calls is a plain fn of its arguments."
  [{:keys [sim-url sim-result silent?] :as data} ui dispatch as-child]
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
     (as-child [browse-list/render dispatch data ui])
     [:<>
      (as-child (simulate-url/header dispatch sim-url sim-result))
      [:div {:style {:flex 1 :overflow "auto"}}
       (as-child [browse-list/render dispatch data ui])]])])

;; ---- root view -----------------------------------------------------------

(rf.fresco/defview Panel
  "The Static Routes tab's root — a FRESCO BOUNDARY (rf2-k97c.3), not an
  `rf/reg-view`. Reads the static-routes composite plus the three
  UI-state slots and hands their values, a frame-bound dispatcher and
  the island spelling to [[panel-tree]].

  The READS are `rf.fresco/sub`, plain calls the shipped collector
  records an edge for — no deref, no reaction owned by the installed
  adapter, and a re-wire that NOTIFIES when the substrate disposes the
  underlying derived value. That is the third of the epic's three
  couplings, and the one a first-paint smoke test cannot see. The four
  reads keep the `reg-view` body's ORDER, which is the order the node
  lane reproduces.

  ONE READ SITE, ONE BOUNDARY. Boundary count tracks reads and
  head-position use, not file size (the #9581 sizing note): the four
  slots are read together and rendered together, so splitting them would
  buy nothing and cost a component type.

  The DISPATCHER is `(:dispatch (rf/capture-frame))` — core's own door,
  which Fresco's authoring surface deliberately does not duplicate, and
  which answers the boundary's DECLARED frame inside a body. It replaces
  the name `reg-view` used to inject lexically: `defview` binds NO name
  inside your body, so the bare `dispatch` this body used to close over
  would be a LOUD compile error, which is the good failure.

  `r/as-element` is the `as-child` spelling for the browse-list island —
  [[panel-tree]] records the census that makes it necessary.

  The argument is the ordinary one-props-map vector every `defview`
  takes. This panel reads nothing from props — the L4 registry mounts it
  with none — so it is destructured away."
  [_props]
  (let [data       (rf.fresco/sub [:rf.xray.static.routes/tab-data])
        expanded   (rf.fresco/sub [:rf.xray.static.routes/expanded])
        sim-open   (rf.fresco/sub [:rf.xray.static.routes/sim-nav-open])
        routes-map (rf.fresco/sub [:rf.xray/registered-routes])]
    (panel-tree data
                {:expanded   expanded
                 :sim-open   sim-open
                 :routes-map routes-map}
                (:dispatch (rf/capture-frame))
                r/as-element)))

;; ---- the migration bridge (rf2-k97c.3) -----------------------------------
;;
;; Xray's Static shell mounts the active tab as the hiccup head
;; `[(:panel tab)]` (`static/shell.cljs`'s `detail-panel-tree`), and
;; `panel-registry/reg-l4-tab!`'s `:pre` requires `:panel` to be CALLABLE —
;; neither of which a React component is.
;;
;; `rf.fresco/as-component` is Fresco's own outward door for exactly this:
;; it answers a real React component for a boundary, which a React parent
;; (Reagent, UIx or plain JavaScript) mounts UNDER THE FRAME IT IS ALREADY
;; IN, taking the frame from React context rather than from a second root.
;; So there is no second root here, no adapter-kind branch, and no props
;; ABI.
;;
;; BOTH DEFS ARE PRIVATE, and that is measured rather than defaulted:
;; `Panel` is named outside this file only in `static/shell.cljs`'s PROSE
;; (a docstring listing the L4 tabs), in `tools/xray/spec/API.md`, and in
;; the two `spec/api-manifest*.edn` rows — never mounted or called by name.
;; The L4 registry is the only consumer, and `install!` below is the only
;; thing that passes the bridge. A panel carrying a standalone `mount-*!`
;; facade would need a PUBLIC bridge instead, because `panels/render-panel!`
;; takes the view to mount as an argument and needs a name to pass; this
;; panel has none — `panels.cljs` names no Static sub-tab, so no caller
;; line changes.
;;
;; `Panel` KEEPS THE NATURAL NAME — RULING 1's surviving #9581 spelling —
;; which is also what keeps the two hot-zone `api-manifest` rows for
;; `static.routes.panel/Panel` valid without touching either file.
;;
;; THIS IS SCAFFOLDING WITH A DEFINED END. When the Static shell is itself
;; a Fresco tree, `reg-l4-tab!` takes `Panel` directly, `[:>]` goes, and
;; both defs below are deleted.

(def ^:private Panel-component
  "The React component `Panel` presents as, for a non-Fresco parent.
  Declared once at top level beside the view, as `rf.fresco/as-component`'s
  contract requires — deriving it per render would mint a new component
  type every time and remount the panel on each parent render."
  (rf.fresco/as-component Panel))

(defn ^:private Panel-bridge
  "The callable the L4 tab registry stores. Returns Reagent-shaped hiccup
  interoping to the React component above; the Static shell's enclosing
  `rf/frame-provider` is what puts `:rf/xray` in React context for it."
  []
  [:> Panel-component {}])

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
    {:inputs [[:rf.xray/registered-routes]
              [:rf.xray.static.routes/query]
              [:rf.xray.static.routes/sim-url]]}
    (fn [[routes-map query sim-url] _query]
      (h/project-static-data routes-map query sim-url)))

  ;; Register the Static Routes tab with the internal L4 tab registry.
  (panel-registry/reg-l4-tab!
    {:id    :routes
     :label "Routes"
     :mnem  "r"
     :modes #{:static}
     :order 1
     ;; rf2-k97c.3 — `Panel-bridge`, not `Panel`. `Panel` is now a React
     ;; component (a Fresco boundary) and the Static shell mounts `:panel`
     ;; as a Reagent hiccup head; the bridge is the one line between them
     ;; and goes when the shell is a Fresco tree.
     :panel Panel-bridge})

  nil)
