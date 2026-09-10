(ns day8.re-frame2-xray.static.flows.panel
  "Top-level Flows sub-tab for Xray's Static surface.

  ## Browse-all verb

  Per Lock #15 (two-verbs-two-homes — browse-all lives in Static) the
  Flows sub-tab is a flat catalogue of every flow registered via
  `re-frame.flows/reg-flow`. Each row surfaces the flow-id, its
  `:inputs` paths, its `:output-path`, the owning frame (flows are
  frame-scoped per Spec 013), and the doc-string (when present).

      ┌───────────────────────────────────────────────────┐
      │ Flows — header + descriptive prose                │
      ├───────────────────────────────────────────────────┤
      │ Search: [_______________]            7 flows      │
      ├───────────────────────────────────────────────────┤
      │ ▸ :user/full-name   [:user :first] [:user :last]  │
      │     → [:derived :full-name]    [:rf/default]      │
      │ ▸ :cart/total       …                             │
      └───────────────────────────────────────────────────┘

  ## Data source

  Reads the registered flows through the public introspection surface
  `re-frame.flows/flows-snapshot` (Tool-Pair.md §public APIs; spec/014
  catalogues `:rf.xray/registered-flows` as `rf.flows/flows-snapshot`).
  Since rf2-en00bk the per-frame `flows` atom is the SOLE store; the
  registrar `:flow` kind is RESERVED-but-empty (no write), so the old
  `(rf/registrations {:source :store :kind :flow})` read now returns `{}` (an empty catalogue).
  `flows-snapshot` returns the whole-registry `{frame-id {flow-id
  flow-map}}` value DIRECTLY — already in the per-frame shape the
  projection + picker-scoping helpers consume, so no flat-to-grouped
  regrouping is needed. The store is FRAME-DIVERGENT-per-id (Spec 013):
  the same flow-id against two frames carries each frame's OWN definition,
  which this panel surfaces per frame — a strict improvement over the old
  frame-blind last-registration-wins registrar slot.

  Optional test override slot: `:rf.xray.static.flows/registered-
  flows-override` lets the CLJS test suite inject deterministic
  fixtures without poking the live atom.

  ## State slots (all under `:rf.xray.static.flows/*`)

    - `:rf.xray.static.flows/query`    — search input value.

  ## Pure hiccup

  Same contract as every Xray view — pure hiccup, no Reagent / UIx
  references. Frame isolation comes from the enclosing
  `[rf/frame-provider {:frame :rf/xray}]` in `static/shell.cljs`.

  ## Public surface

  - `Panel`        — the tab's root. Since rf2-k97c.3 an
                     `rf.fresco/defview` BOUNDARY — a real React function
                     component, not an `rf/reg-view`.
  - `panel-tree`   — the whole body, as a pure fn of the read's VALUE and
                     a frame-bound dispatcher. `Panel` is the read plus a
                     call to this.
  - `install!`     — idempotent install for the subs, events and the L4
                     tab registration."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.flows :as rf.flows]
            [re-frame.fresco :as rf.fresco]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.static.shared.catalogue :as catalogue]
            [day8.re-frame2-xray.static.shared.search-box :as search-box]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens sans-stack]]
            [day8.re-frame2-xray.views.edn-widget :as edn]))

;; ---- pure helpers --------------------------------------------------------

(defn scope-to-frame
  "Narrow a `{frame-id {flow-id flow-map}}` registry snapshot to a
  single `frame-id`, returning the same two-level shape carrying only
  that frame's entry. The flows registry is genuinely per-frame (Spec
  013 — `re-frame.flows.registry/flows` is keyed by frame-id), so the
  L1 frame picker's selection MUST scope the catalogue: switching the
  picker changes which frame's flows the Static Flows tab lists.

  A nil `frame-id` (no frame resolved yet) returns the snapshot
  verbatim — the cold-start empty-state stays useful rather than
  blanking the list. Pure data — JVM-runnable."
  [registry-snapshot frame-id]
  (if (nil? frame-id)
    registry-snapshot
    (select-keys registry-snapshot [frame-id])))

(defn project-rows
  "Flatten `{frame-id {flow-id flow-map}}` into a flat vector of rows,
  sorted by flow-id ascending. Pure data so the JVM unit-test target
  can cover the shape without a CLJS runtime."
  [registry-snapshot]
  (->> registry-snapshot
       (mapcat (fn [[frame-id by-id]]
                 (map (fn [[flow-id flow-map]]
                        {:flow-id     flow-id
                         :frame       frame-id
                         :inputs      (vec (:inputs flow-map))
                         :output-path (vec (:output-path flow-map))
                         :doc         (:doc flow-map)})
                      by-id)))
       (sort-by (fn [{:keys [flow-id]}] (str flow-id)))
       vec))

(defn- row-haystack [{:keys [flow-id frame inputs output-path doc]}]
  (str/lower-case
    (str (pr-str flow-id) " "
         (pr-str frame) " "
         (pr-str inputs) " "
         (pr-str output-path) " "
         (or doc ""))))

(defn filter-rows
  "Substring filter against flow-id + frame + inputs + output-path + doc.
  Empty / blank query returns rows verbatim."
  [rows query]
  (search-box/filter-rows row-haystack rows query))

(defn project-data
  "View-facing composite. Folds the registered-flows map + UI controls
  into the shape `panel/Panel` consumes:

      {:silent?     <bool>
       :flows       [<row> ...]
       :total       <pre-filter count>
       :filtered?   <bool>
       :query       <string-or-nil>}

  `frame-id` scopes the per-frame registry to the picker's observed
  frame before projecting (nil = no frame resolved → list every
  frame's flows; see `scope-to-frame`)."
  [registry-snapshot frame-id query]
  (let [scoped   (scope-to-frame registry-snapshot frame-id)
        rows     (project-rows scoped)
        silent?  (empty? rows)
        filtered (filter-rows rows query)]
    {:silent?   silent?
     :flows     filtered
     :total     (count rows)
     :filtered? (not= (count rows) (count filtered))
     :query     query}))

;; ---- search box ----------------------------------------------------------

(defn- search-box
  ;; CALLED, never used as a hiccup head (rf2-k97c.3). `search-box/search-box`
  ;; is a plain fn, and a plain function in head position is a loud error
  ;; inside a Fresco body by design; applying it renders the identical
  ;; markup. The flex-row chrome still lives in the shared component.
  ;;
  ;; `dispatch` arrives from the boundary rather than being captured here.
  ;; The keystroke dispatch is an OUT-OF-RENDER affordance — it fires after
  ;; render unwinds, when the ambient frame is gone — so it must be bound to
  ;; a frame at render time; `Panel` binds it once with `rf/capture-frame`
  ;; and threads it down. nil is legal for a mount that never types.
  [dispatch query total filtered?]
  (search-box/search-box
    {:testid-prefix   "rf-xray-static-flows"
     :dispatch        dispatch
     :set-query-event :rf.xray.static.flows/set-query
     :placeholder     "flow-id, path, or doc…"
     :value           query
     :count-noun      "flow"
     :total           total
     :filtered?       filtered?}))

;; ---- row -----------------------------------------------------------------

(defn- flow-row
  ;; Non-interactive `li` chrome via the shared `catalogue-row`; the flow
  ;; rows are catalogue entries (no row-level dispatch). The interactive
  ;; Static surface that earns keyboard activation is the Routes list
  ;; (whose rows toggle an expand surface — see static/routes/browse_list.cljs).
  [{:keys [flow-id frame inputs output-path doc] :as _row}]
  (catalogue/catalogue-row
   {:testid (str "rf-xray-static-flows-row-" (subs (pr-str flow-id) 1))}
   [:div {:style {:display     "flex"
                  :align-items "baseline"
                  :gap         "8px"}}
    [:span {:style {:color       (:accent tokens)
                    :font-weight 500
                    :min-width   "180px"}}
     (pr-str flow-id)]
    [:span {:data-testid (str "rf-xray-static-flows-frame-"
                              (subs (pr-str flow-id) 1))
            :style {:color     (:text-tertiary tokens)
                    :font-size "10px"}}
     (pr-str frame)]]
   ;; Input + output path values render through the shared cljs-devtools
   ;; EDN widget (spec 007:119 — "all values rendered via the
   ;; cljs-devtools-shaped renderer") rather than raw `pr-str` +
   ;; `[:code]`. `edn/inspect-view` is the widget's FRESCO head — same
   ;; value, same opts, same renderer as `edn/inspect`, differing ONLY in
   ;; that it emits `[ei/edn-inspector-view …]` (a boundary) rather than
   ;; `[ei/edn-inspector …]` (a Reagent component). rf2-k97c.3 made the
   ;; swap mandatory rather than stylistic: `ei/edn-inspector` is a plain
   ;; fn, and a plain fn in hiccup head position is a loud error inside a
   ;; Fresco body. Each value keeps its stable per-flow `node-key`, which
   ;; is now load-bearing twice over — it is the panel-id keying expand
   ;; state AND the boundary's required `:mount-id`, so two mounts sharing
   ;; one node-key would share a width slot and a projection cache.
   (let [flow-key (subs (pr-str flow-id) 1)]
     [:div {:style {:margin-left  "12px"
                    :color        (:text-secondary tokens)
                    :font-size    "11px"
                    :line-height  1.4}}
      [:div {:style {:display "flex" :align-items "baseline" :gap "6px"}}
       [:span {:style {:color (:text-tertiary tokens)
                       :flex  "0 0 auto"}}
        "inputs:"]
       ;; The testid makes the inputs SEQ addressable as a container. A
       ;; keyed fragment adds no DOM node, so this span's direct children
       ;; are exactly the per-input widget roots — which is what lets the
       ;; browser lane assert row identity across a reorder without
       ;; reaching into `edn-inspector`'s own testid derivation.
       (into [:span {:data-testid (str "rf-xray-static-flows-inputs-" flow-key)
                     :style {:display     "inline-flex"
                             :flex-wrap   "wrap"
                             :gap         "6px"}}]
             ;; THE KEY RIDES ON A KEYED FRAGMENT'S ATTRIBUTE MAP, and this
             ;; site has now been wrong in TWO different ways for two
             ;; different reasons (rf2-k97c.3, RULING 2's key sweep).
             ;;
             ;; It was first `^{:key …}` reader meta on the `(edn/inspect …)`
             ;; CALL FORM — metadata on a source list, discarded when the
             ;; call returns its fresh vector, so NO key ever reached React
             ;; and the inputs seq reconciled by index. That was repaired to
             ;; `with-meta` on the RETURNED VECTOR, which Reagent's
             ;; `get-react-key` really does read.
             ;;
             ;; Fresco's codec reads `:key` from an ATTRIBUTE MAP and reads
             ;; Clojure metadata NOWHERE, so that repair goes inert the
             ;; moment this panel renders through a boundary — and a lost key
             ;; does not fail, it degrades silently into index-based
             ;; reconciliation, which paints identically and corrupts
             ;; identity only once the seq changes shape. The fragment
             ;; carries the key without adding a DOM node, and the key
             ;; EXPRESSION is unchanged.
             (for [[i input-path] (map-indexed vector inputs)]
               [:<> {:key (str "in-" i)}
                (edn/inspect-view input-path
                                  (str "static-flows/" flow-key "/input/" i))]))]
      [:div {:style {:display "flex" :align-items "baseline" :gap "6px"}}
       [:span {:style {:color (:text-tertiary tokens)
                       :flex  "0 0 auto"}}
        "output →"]
       ;; The single output value is not in a seq, so it needs no key.
       (edn/inspect-view output-path (str "static-flows/" flow-key "/output"))]
      (when doc
        [:div {:style {:margin-top  "4px"
                       :color       (:text-secondary tokens)
                       :font-family sans-stack
                       :font-style  "italic"}}
         doc])])))

;; ---- the body, as a pure fn of the read's value ---------------------------

(defn panel-tree
  "The Static Flows tab's WHOLE body, as a pure function of the one value
  [[Panel]] reads — the `:rf.xray.static.flows/tab-data` composite — and
  the frame-bound `dispatch` the search box needs.

  SPLIT OUT OF [[Panel]] BY rf2-k97c.3, and the split is `defview`'s own
  documented extract-a-helper spelling rather than an invention. A
  boundary's body may only run inside a React render window, so `(Panel)`
  is no longer a callable that answers hiccup — while the catalogue's
  projection is ordinary data → data and is worth testing in the fast node
  lane. `panel_cljs_test` drives THIS fn with the value it takes from the
  sub directly; the boundary's own behaviour — first paint, liveness,
  frame targeting, evidence isolation, teardown and row identity — is
  `panel_fresco_boundary_dom_cljs_test`'s subject.

  PURE: every helper it calls is a plain fn of its arguments."
  [{:keys [silent? flows total filtered? query]} dispatch]
  (catalogue/catalogue-panel
   {:testid     "rf-xray-static-flows"
    :noun       "flow"
    :query      query
    :silent?    silent?
    :rows       flows
    :search     (search-box dispatch query total filtered?)
    ;; THE KEY RIDES ON A KEYED FRAGMENT, not on reader metadata
    ;; (rf2-k97c.3) — the same move the input seq above makes, for the
    ;; same reason. `^{:key …}` on this vector literal is read by Reagent
    ;; and by Fresco's codec NOWHERE, so it would reach React as nothing
    ;; once the panel renders through a boundary. The fragment carries the
    ;; key without adding a DOM node, which is what keeps `catalogue-row`'s
    ;; `li` chrome the shared presentational helper it is; the key
    ;; expression is unchanged and identity stays domain-shaped and local,
    ;; exactly as `catalogue-panel`'s `:row-render` contract asks.
    :row-render (fn [row]
                  [:<> {:key (str (:frame row) "/" (:flow-id row))}
                   (flow-row row)])}))

;; ---- root view -----------------------------------------------------------

(rf.fresco/defview Panel
  "The Static Flows tab's root — a FRESCO BOUNDARY (rf2-k97c.3), not an
  `rf/reg-view`. Reads the flows composite and hands its value plus a
  frame-bound dispatcher to [[panel-tree]].

  The READ is `rf.fresco/sub`, a plain call the shipped collector records
  an edge for — no deref, no reaction owned by the installed adapter, and
  a re-wire that NOTIFIES when the substrate disposes the underlying
  derived value. That is the third of the epic's three couplings, and the
  one a first-paint smoke test cannot see.

  The FRAME the read resolves against comes from React context, which the
  enclosing frame boundary writes — `rf/frame-provider` and
  `rf.fresco/frame-provider` write the SAME context — so this resolves
  `:rf/xray` identically under today's Reagent-rendered Static shell and
  under the Fresco root Xray will own. It never consults
  `:adapter/current-component`, the hook a foreign root cannot answer.

  The DISPATCHER is `(:dispatch (rf/capture-frame))` — core's own door,
  which Fresco's authoring surface deliberately does not duplicate, and
  which answers the boundary's DECLARED frame inside a body. It replaces
  the render-time `(rf/current-frame-id)` capture the `reg-view` body did:
  same guarantee, one call, and it is the spelling every migrated panel
  now uses. The search box's keystroke dispatch therefore still lands on
  THIS Xray instance's frame after render scope unwinds rather than
  leaking to `:rf/default`.

  ONE read and ONE boundary. Boundary count tracks reads and
  head-position use, not file size: `catalogue-panel`, `catalogue-row`,
  `search-box` and `flow-row` are all CALLED, never used as a hiccup head,
  so Fresco's \"a plain function in head position is a loud error\" rule
  never meets one. The one fn-headed vector that REMAINS in the tree is
  `edn/inspect-view`'s `[ei/edn-inspector-view …]`, which is itself a
  boundary and so is a legal head; nothing else in the interior wants a
  boundary of its own.

  The argument is the ordinary one-props-map vector every `defview`
  takes. This panel reads nothing from props — the L4 registry mounts it
  with none — so it is destructured away."
  [_props]
  (panel-tree (rf.fresco/sub [:rf.xray.static.flows/tab-data])
              (:dispatch (rf/capture-frame))))

;; ---- the migration bridge (rf2-k97c.3) -----------------------------------
;;
;; Xray's Static shell is still a `reg-view` tree rendered by the installed
;; adapter. `static/shell.cljs`'s `detail-panel` mounts the active tab as
;; the hiccup head `[(:panel tab)]`, and `panel-registry/reg-l4-tab!`'s
;; `:pre` requires `:panel` to be CALLABLE — neither of which a React
;; component is.
;;
;; `rf.fresco/as-component` is Fresco's own outward door for exactly this:
;; it answers a real React component for a boundary, which a React parent
;; (Reagent, UIx or plain JavaScript) mounts UNDER THE FRAME IT IS ALREADY
;; IN, taking the frame from React context rather than from a second root.
;; So there is no second root here, no adapter-kind branch, and no props
;; ABI.
;;
;; BOTH DEFS ARE PRIVATE, and that is a measured property of this panel
;; rather than a default: `Panel` is named outside this file only in
;; `static/shell.cljs`'s PROSE (a docstring listing the L4 tabs) and in
;; `spec/api-manifest*.edn`'s rows — never mounted or called by name. The
;; L4 registry is the only consumer, and `install!` below is the only
;; thing that passes the bridge. A panel carrying a standalone `mount-*!`
;; facade needs a PUBLIC bridge instead, because `panels/render-panel!`
;; takes the view to mount as an argument and needs a name to pass; this
;; panel has none — `panels.cljs` names no Static sub-tab.
;;
;; `Panel` KEEPS THE NATURAL NAME, which is the spelling ruled to survive
;; (the #9581 assignment) and is also what keeps the two hot-zone
;; api-manifest rows for `static.flows.panel/Panel` valid without touching
;; either file.
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

;; ---- production value source ---------------------------------------------
;;
;; The raw value the production data sub reads. Shared with the
;; test-override seam (`install-test-overrides!` below) so the override
;; branch lives in ONE place (the seam), not duplicated.

(defn- registered-flows-value
  "The registered flows in the per-frame `{frame-id {flow-id flow-map}}`
  shape, read directly from the authoritative flows store via
  `re-frame.flows/flows-snapshot`.

  Reads `rf.flows/flows-snapshot` — the whole-registry `{frame-id {flow-id
  flow-map}}` snapshot — NOT the registrar `:flow` slot. Since rf2-en00bk
  the per-frame `flows` atom is the SOLE store; the registrar `:flow` kind
  is RESERVED-but-empty (no write), so the old `(rf/registrations {:source :store :kind :flow})` /
  `rf/registrations :flow` read now returns `{}` and the panel
  rendered an empty catalogue. `flows-snapshot` deref's the process-global
  `re-frame.flows.registry/flows` atom directly, so — unlike a registrar
  read — it is generation-INDEPENDENT: it does not route through the
  registrar resolver and is unaffected by Xray's sub build binding to its
  OWN `:rf/xray` image generation. No regrouping is needed: the snapshot
  is ALREADY in the per-frame `{frame-id {flow-id flow-map}}` shape the
  projection + picker-scoping helpers consume, and is FRAME-DIVERGENT by
  construction — the same flow-id registered against two frames carries
  each frame's OWN `:inputs` / `:derive` / `:output-path`, a strict
  improvement over the old frame-blind last-registration-wins registrar
  slot (rf2-20359j)."
  []
  (try (rf.flows/flows-snapshot)
       (catch :default _ {})))

;; ---- registrations -------------------------------------------------------

(defn install!
  "Idempotent install for the Static Flows panel's subs + events.

  Registers:

    - `:rf.xray.static.flows/query`            — search input slot.
    - `:rf.xray.static.flows/set-query`        — search input setter.
    - `:rf.xray.static.flows/registered-flows-override` — test-only
                                                  override slot.
    - `:rf.xray.static.flows/set-registered-flows-override-for-test`
        — test-only override setter.
    - `:rf.xray.static.flows/registered-flows` — production data sub
                                                  reading the public
                                                  `rf.flows/flows-snapshot`
                                                  surface (or override).
    - `:rf.xray.static.flows/tab-data`         — view-facing composite."
  []

  ;; ---- UI state ---------------------------------------------------------

  (rf/reg-event :rf.xray.static.flows/set-query
    (fn [{:keys [db]} [_ q]]
      {:db (if (or (nil? q) (= "" q))
        (dissoc db :rf.xray.static.flows/query)
        (assoc db :rf.xray.static.flows/query q))}))

  (rf/reg-sub :rf.xray.static.flows/query
    (fn [db _]
      (get db :rf.xray.static.flows/query)))

  ;; The test-only override seam (`:rf.xray.static.flows/set-registered-
  ;; flows-override-for-test` + the `*-override` sub) is NOT installed
  ;; here — production registration carries no `-for-test` ids. Tests opt
  ;; into it via `install-test-overrides!`.

  ;; ---- production data sub ---------------------------------------------

  ;; Read the registered flows through the public `rf.flows/flows-snapshot`
  ;; introspection surface (Tool-Pair.md §public APIs) once per sub
  ;; re-fire. The snapshot is already in the per-frame `{frame-id
  ;; {flow-id flow-map}}` shape the projection + picker-scoping helpers
  ;; consume — no flat-to-grouped regroup needed (rf2-en00bk made the
  ;; per-frame flows atom the sole store; the registrar `:flow` slot is
  ;; reserved-but-empty). Declaring `:rf.xray/trace-buffer` as an `:inputs` head
  ;; keeps the sub reactive against the same "something changed" pulse the
  ;; other static-mode subs ride — without it, a fresh `reg-flow!`
  ;; wouldn't surface until the next subscribe re-render.
  (rf/reg-sub :rf.xray.static.flows/registered-flows
    {:inputs [[:rf.xray/trace-buffer]]}
    (fn [[_buffer] _query]
      (registered-flows-value)))

  ;; ---- view-facing composite -------------------------------------------

  ;; `:rf.xray/observed-frame` is the L1 frame picker's current
  ;; selection (installed by `app-db-diff-subs/install!`). The flows
  ;; registry is per-frame (Spec 013), so the picker scopes the
  ;; catalogue — switching frames changes which frame's flows list.
  (rf/reg-sub :rf.xray.static.flows/tab-data
    {:inputs [[:rf.xray.static.flows/registered-flows]
              [:rf.xray/observed-frame]
              [:rf.xray.static.flows/query]]}
    (fn [[registry-snapshot observed-frame query] _query]
      (project-data registry-snapshot observed-frame query)))

  ;; Register the Static Flows tab with the internal L4 tab registry.
  ;; Contiguous order: machines 0 · routes 1 · schemas 2 · flows 3 ·
  ;; interceptors 4.
  ;; Mnemonic is "f" (first-letter-of-label, the Static-mode convention:
  ;; Machines→m, Routes→r, Interceptors→i). "f" is free in the Static
  ;; mnemonic set (Schemas uses "c" because "s" is the Settings key;
  ;; Flows has no such collision), and the Static shell's own canonical
  ;; IA listing (`static/shell.cljs` "Flows (f)") documents "f" as the
  ;; intended binding.
  (panel-registry/reg-l4-tab!
    {:id    :flows
     :label "Flows"
     :mnem  "f"
     :modes #{:static}
     :order 3
     ;; rf2-k97c.3 — `Panel-bridge`, not `Panel`. `Panel` is now a React
     ;; component (a Fresco boundary) and the Static shell mounts `:panel`
     ;; as a Reagent hiccup head; the bridge is the one line between them
     ;; and goes when the shell is a Fresco tree.
     :panel Panel-bridge})

  nil)

;; ---- test-only override seam --------------------------------------------

(defn install-test-overrides!
  "Install the Static Flows panel's test-only override seam — the
  `:rf.xray.static.flows/set-registered-flows-override-for-test` event +
  the `*-override` sub, then RE-register the production
  `:rf.xray.static.flows/registered-flows` sub to layer the override read
  on top. Tests opt in via `test-support/install-test-overrides!` AFTER
  `register-xray-handlers!`. **Test-only — never call from production.**"
  []
  (rf/reg-event :rf.xray.static.flows/set-registered-flows-override-for-test
    (fn [{:keys [db]} [_ ov]]
      {:db (if (nil? ov)
        (dissoc db :rf.xray.static.flows/registered-flows-override)
        (assoc db :rf.xray.static.flows/registered-flows-override ov))}))
  (rf/reg-sub :rf.xray.static.flows/registered-flows-override
    (fn [db _]
      (get db :rf.xray.static.flows/registered-flows-override)))

  (rf/reg-sub :rf.xray.static.flows/registered-flows
    {:inputs [[:rf.xray/trace-buffer] [:rf.xray.static.flows/registered-flows-override]]}
    (fn [[_buffer override] _query]
      (or override (registered-flows-value))))
  nil)
