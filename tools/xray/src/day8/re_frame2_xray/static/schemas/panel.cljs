(ns day8.re-frame2-xray.static.schemas.panel
  "Top-level Schemas sub-tab for Xray's Static surface.

  ## Browse-all verb

  Per Lock #15 (two-verbs-two-homes — browse-all lives in Static) the
  Schemas sub-tab is a flat catalogue of every registered schema —
  app-db slot schemas (via `re-frame.schemas/reg-app-schema`) plus
  event + sub schemas surfaced through the registrar's `:event` /
  `:sub` slot metadata `:spec` field.

      ┌───────────────────────────────────────────────────┐
      │ Schemas — header + descriptive prose              │
      ├───────────────────────────────────────────────────┤
      │ Search: [_______________]            12 schemas   │
      ├───────────────────────────────────────────────────┤
      │ ▸ app-db   [:user]      [:map ...]       [open]   │
      │ ▸ event    :user/login  [:tuple ...]              │
      │ ▸ sub      :user/full   [:map ...]                │
      └───────────────────────────────────────────────────┘

  ## Data sources

  Three input registries, all read through public surfaces:

    - app-db schemas — assembled from the public `re-frame.schemas`
      façade: `rf/frame-ids` enumerates the live frames, then per
      frame ONE `rf.schemas/app-schemas {:frame f}` read returns that
      frame's whole `{path → schema-meta}` map (`:schema` Malli EDN,
      `:doc`, `:file`/`:line`/`:ns` source
      coords). This yields the `{frame-id {path schema-meta}}` shape
      the per-frame projection consumes — without reaching the private
      `re-frame.schemas.storage/schemas-by-frame` atom.
    - `(rf/registrations {:source :store :kind :event})` — events whose metadata carries a
      `:spec` slot.
    - `(rf/registrations {:source :store :kind :sub})` — subs whose metadata carries a `:spec`
      slot.

  ## Jump-to-source

  Each row carries a source-coord chip (when the registered metadata
  surfaces `:file` / `:line`). Click dispatches
  `:rf.xray/open-in-editor` — the same affordance the Trace + Issues
  panels use.

  ## State slots (all under `:rf.xray.static.schemas/*`)

    - `:rf.xray.static.schemas/query`    — search input value.

  ## Pure hiccup

  Same contract as every Xray view — pure hiccup. Frame isolation
  comes from the enclosing `[rf/frame-provider {:frame :rf/xray}]`
  in `static/shell.cljs`.

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
            [re-frame.fresco :as rf.fresco]
            [re-frame.schemas :as rf.schemas]
            [day8.re-frame2-xray.open-in-editor :as open-in-editor]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.static.shared.catalogue :as catalogue]
            [day8.re-frame2-xray.static.shared.search-box :as search-box]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens mono-stack sans-stack]]
            [day8.re-frame2-xray.views.edn-widget :as edn]))

;; ---- pure helpers --------------------------------------------------------

(defn scope-app-schemas-to-frame
  "Narrow a `{frame-id {path schema-meta}}` app-db-schema snapshot to a
  single `frame-id`. App-db schemas are genuinely per-frame (the
  schemas registry is keyed by frame-id — see `re-frame.schemas/app-schemas`), so the
  L1 frame picker scopes the app-db-schema rows.

  A nil `frame-id` (no frame resolved yet) returns the snapshot
  verbatim. Pure data — JVM-runnable.

  NOTE: only the app-db-schema rows are frame-scoped. Event-spec and
  sub-spec rows come from the process-global registrar (Spec 001 —
  the registrar is per-process; frames isolate state, not
  registrations), so they are unconditionally cross-frame and carry
  `:frame nil`."
  [schemas-by-frame frame-id]
  (if (nil? frame-id)
    schemas-by-frame
    (select-keys schemas-by-frame [frame-id])))

(defn project-app-schema-rows
  "Flatten `{frame-id {path schema-meta}}` into row maps.
  `schema-meta` carries `:schema`, `:doc`, plus `:file`/`:line`/`:ns`
  source-coord slots (Malli EDN + the source-coord stamp)."
  [schemas-by-frame]
  (->> schemas-by-frame
       (mapcat (fn [[frame-id by-path]]
                 (map (fn [[path schema-meta]]
                        {:kind         :app-db
                         :id           path
                         :frame        frame-id
                         :schema       (:schema schema-meta)
                         :doc          (:doc schema-meta)
                         :source-coord (select-keys schema-meta [:file :line :ns])})
                      by-path)))
       vec))

(defn- meta-row
  "Project one registrar `:event` / `:sub` entry to a row when it
  carries a `:spec` slot."
  [kind id meta]
  (when-some [spec (:spec meta)]
    {:kind         kind
     :id           id
     :frame        nil
     :schema       spec
     :doc          (:doc meta)
     :source-coord (select-keys meta [:file :line :ns])}))

(defn project-registrar-rows
  "Walk one kind's `{id meta}` map and return rows for every entry
  whose `:spec` slot is non-nil."
  [kind registrations-map]
  (vec (keep (fn [[id meta]] (meta-row kind id meta)) registrations-map)))

(defn project-rows
  "Combine app-db schema rows + event-spec rows + sub-spec rows into a
  single flat vector sorted by `(kind, id)`."
  [schemas-by-frame events-map subs-map]
  (let [rows (concat (project-app-schema-rows schemas-by-frame)
                     (project-registrar-rows :event events-map)
                     (project-registrar-rows :sub   subs-map))]
    (->> rows
         (sort-by (fn [{:keys [kind id]}] [(name kind) (pr-str id)]))
         vec)))

(defn- row-haystack [{:keys [kind id frame doc schema]}]
  (str/lower-case
    (str (name kind) " "
         (pr-str id) " "
         (pr-str frame) " "
         (or doc "") " "
         (pr-str schema))))

(defn filter-rows
  [rows query]
  (search-box/filter-rows row-haystack rows query))

(defn project-data
  "View-facing composite. `frame-id` scopes the per-frame app-db
  schemas to the picker's observed frame (nil = every frame; see
  `scope-app-schemas-to-frame`); event-spec + sub-spec rows are
  process-global and always included."
  [schemas-by-frame events-map subs-map frame-id query]
  (let [scoped   (scope-app-schemas-to-frame schemas-by-frame frame-id)
        rows     (project-rows scoped events-map subs-map)
        silent?  (empty? rows)
        filtered (filter-rows rows query)]
    {:silent?   silent?
     :schemas   filtered
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
    {:testid-prefix   "rf-xray-static-schemas"
     :dispatch        dispatch
     :set-query-event :rf.xray.static.schemas/set-query
     :placeholder     "kind, id, frame, or doc…"
     :value           query
     :count-noun      "schema"
     :total           total
     :filtered?       filtered?}))

;; ---- row -----------------------------------------------------------------

(defn- kind-badge
  [kind]
  (let [{:keys [letter colour]}
        (case kind
          :app-db {:letter "A" :colour (:info tokens)}
          :event  {:letter "E" :colour (:magenta tokens)}
          :sub    {:letter "S" :colour (:yellow tokens)}
          {:letter "?" :colour (:text-tertiary tokens)})]
    [:span {:data-testid (str "rf-xray-static-schemas-badge-" (name kind))
            :title       (str (name kind) " schema")
            :style       {:display       "inline-block"
                          :min-width     "14px"
                          :height        "14px"
                          :line-height   "14px"
                          :padding       "0 3px"
                          :margin-right  "6px"
                          :background    (:bg-3 tokens)
                          :color         colour
                          :border        (str "1px solid " colour)
                          :border-radius "3px"
                          :font-family   mono-stack
                          :font-size     "9px"
                          :font-weight   700
                          :text-align    "center"}}
     letter]))

(defn- schema-row
  ;; Non-interactive catalogue entry (the only row-level affordance is the
  ;; focusable `open-chip` jump-to-source); the shared `catalogue-row` owns
  ;; the `role=listitem` `li` chrome.
  [{:keys [kind id frame schema doc source-coord] :as _row}]
  (let [id-text (pr-str id)
        row-id  (str (name kind) "-" id-text)]
    (catalogue/catalogue-row
     {:testid (str "rf-xray-static-schemas-row-" row-id)}
     [:div {:style {:display     "flex"
                    :align-items "baseline"
                    :gap         "8px"}}
      (kind-badge kind)
      [:span {:style {:color       (:accent tokens)
                      :font-weight 500
                      :min-width   "200px"}}
       id-text]
      (when frame
        [:span {:data-testid (str "rf-xray-static-schemas-frame-" row-id)
                :style       {:color     (:text-tertiary tokens)
                              :font-size "10px"}}
         (pr-str frame)])
      ;; CALLED, never used as a hiccup head (rf2-k97c.3).
      ;; `open-in-editor/open-chip` is a plain fn answering hiccup (or nil
      ;; when the coord carries no usable `:file`), and a plain fn in head
      ;; position is a loud error inside a Fresco body; applying it renders
      ;; the identical `<a>` chrome, and a nil is a legal child either way.
      ;; Its click handler is untouched — `defview`'s contract passes a
      ;; plain fn at an `on-*` prop through, and `chip-click!` already
      ;; carries its own explicit `{:frame …}`.
      (when (and source-coord (:file source-coord))
        (open-in-editor/open-chip source-coord))]
     ;; The Malli schema EDN renders through the shared cljs-devtools EDN
     ;; widget (spec 007:119 — "all values rendered via the
     ;; cljs-devtools-shaped renderer") rather than raw `pr-str` +
     ;; `[:code]`, so it gains expand/collapse, syntax-colouring parity,
     ;; and the per-node copy host. `edn/inspect-view` is the widget's
     ;; FRESCO head — same value, same opts, same renderer as
     ;; `edn/inspect`, differing ONLY in that it emits
     ;; `[ei/edn-inspector-view …]` (a boundary) rather than
     ;; `[ei/edn-inspector …]` (a Reagent component). rf2-k97c.3 made the
     ;; swap mandatory rather than stylistic: `ei/edn-inspector` is a
     ;; plain fn, and a plain fn in hiccup head position is a loud error
     ;; inside a Fresco body. The `node-key` is stable per (kind,id) so
     ;; expand state survives reloads and doesn't collide across rows —
     ;; now load-bearing twice over, since it is also the boundary's
     ;; required `:mount-id`.
     [:div {:data-testid (str "rf-xray-static-schemas-schema-" row-id)
            :style {:margin-left "20px"
                    :margin-top  "2px"
                    :color       (:text-secondary tokens)
                    :font-size   "11px"
                    :white-space "pre-wrap"
                    :word-break  "break-word"}}
      (edn/inspect-view schema (str "static-schemas/" row-id))]
     (when doc
       [:div {:style {:margin-left "20px"
                      :margin-top  "2px"
                      :color       (:text-secondary tokens)
                      :font-family sans-stack
                      :font-style  "italic"
                      :font-size   "11px"}}
        doc]))))

;; ---- the body, as a pure fn of the read's value ---------------------------

(defn panel-tree
  "The Static Schemas tab's WHOLE body, as a pure function of the one
  value [[Panel]] reads — the `:rf.xray.static.schemas/tab-data`
  composite — and the frame-bound `dispatch` the search box needs.

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
  [{:keys [silent? schemas total filtered? query]} dispatch]
  (catalogue/catalogue-panel
   {:testid     "rf-xray-static-schemas"
    :noun       "schema"
    :query      query
    :silent?    silent?
    :rows       schemas
    :gap        "4px"
    :search     (search-box dispatch query total filtered?)
    ;; THE KEY RIDES ON A KEYED FRAGMENT, not on reader metadata
    ;; (rf2-k97c.3). Fresco's codec reads a literal `:key` from an
    ;; ATTRIBUTE MAP and reads Clojure metadata nowhere, so the
    ;; `^{:key …}` this line used to carry survives Reagent and reaches
    ;; React as NOTHING once the panel renders through the codec — and a
    ;; lost key does not fail, it degrades silently into index-based
    ;; reconciliation. The fragment carries the key without adding a DOM
    ;; node, so `catalogue-row`'s `li` chrome stays the shared
    ;; presentational helper it is; the key expression is unchanged.
    :row-render (fn [row]
                  [:<> {:key (str (name (:kind row)) "/"
                                  (pr-str (:frame row)) "/"
                                  (pr-str (:id row)))}
                   (schema-row row)])}))

;; ---- root view -----------------------------------------------------------

(rf.fresco/defview Panel
  "The Static Schemas tab's root — a FRESCO BOUNDARY (rf2-k97c.3), not an
  `rf/reg-view`. Reads the schemas composite and hands its value plus a
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
  `search-box`, `kind-badge`, `open-in-editor/open-chip` and `schema-row`
  are all CALLED, never used as a hiccup head, so Fresco's \"a plain
  function in head position is a loud error\" rule never meets one. The
  one fn-headed vector that REMAINS in the tree is `edn/inspect-view`'s
  `[ei/edn-inspector-view …]`, which is itself a boundary and so is a
  legal head; nothing else in the interior wants a boundary of its own.

  The argument is the ordinary one-props-map vector every `defview`
  takes. This panel reads nothing from props — the L4 registry mounts it
  with none — so it is destructured away."
  [_props]
  (panel-tree (rf.fresco/sub [:rf.xray.static.schemas/tab-data])
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
;; api-manifest rows for `static.schemas.panel/Panel` valid without
;; touching either file.
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

;; ---- public-surface registry read ----------------------------------------

(defn read-app-schemas-by-frame
  "Assemble the `{frame-id {path schema-meta}}` app-db-schema snapshot
  the per-frame projection consumes, using only public `re-frame.schemas`
  / `re-frame.core` surfaces (Tool-Pair.md §public APIs) — never the
  private `re-frame.schemas.storage/schemas-by-frame` atom.

  For each live frame (`rf/frame-ids`), ONE `(rf.schemas/app-schemas
  {:frame frame-id})` read returns that frame's whole `{path →
  schema-meta}` map — `:schema`, `:doc`, `:file` / `:line` / `:ns` source
  coords. Since rf2-kuky.84 `app-schemas` answers the metadata directly, so
  the per-path second read this used to make is gone. Frames with no app-db
  schemas are dropped so the snapshot mirrors the storage atom's shape
  (absent rather than empty-mapped). Returns `{}` when the schemas artefact
  is not on the classpath (`app-schemas` then yields `{}` per frame)."
  []
  (reduce
    (fn [acc frame-id]
      (let [by-path (rf.schemas/app-schemas {:frame frame-id})]
        (if (seq by-path)
          (assoc acc frame-id by-path)
          acc)))
    {}
    (rf/frame-ids)))

;; ---- production value source ---------------------------------------------
;;
;; The raw value the production data sub reads. Shared with the
;; test-override seam (`install-test-overrides!` below) so the override
;; branch lives in ONE place (the seam), not duplicated.

(defn- registry-value
  "The three input registries assembled from public surfaces: app-db
  schemas via the `re-frame.schemas` façade, event / sub specs via the HOST
  app's `:event` / `:sub` registrar.

  The event / sub reads go through `rf/registrations` with `{:source :store …}`
  (the SOURCE-STORE read, which never consults a bound image generation),
  NOT `{:frame …}`: this runs inside the `:rf.xray.static.schemas/registry` sub
  COMPUTATION, and Xray seats in its OWN image-loaded `:rf/xray` frame, so the
  sub build binds the registrar to Xray's image generation — a bare read would
  resolve through Xray's OWN image and the schemas catalogue would lose the host
  app's event/sub specs. (App-db schemas live in a dedicated side-table keyed by
  frame, not the registrar, so `read-app-schemas-by-frame` is unaffected.) See
  spec/API.md §Public registrar query API."
  []
  {:schemas-by-frame
   (try (read-app-schemas-by-frame)
        (catch :default _ {}))
   :events
   (try (rf/registrations {:source :store :kind :event})
        (catch :default _ {}))
   :subs
   (try (rf/registrations {:source :store :kind :sub})
        (catch :default _ {}))})

;; ---- registrations -------------------------------------------------------

(defn install!
  "Idempotent install for the Static Schemas panel's subs + events.

  Registers:

    - `:rf.xray.static.schemas/query`              — search slot.
    - `:rf.xray.static.schemas/set-query`          — search setter.
    - `:rf.xray.static.schemas/registry-override`  — test seam.
    - `:rf.xray.static.schemas/set-registry-override-for-test`
        — test seam setter; payload shape
          `{:schemas-by-frame ... :events ... :subs ...}`.
    - `:rf.xray.static.schemas/registry`           — production data
                                                      sub reading the
                                                      three live
                                                      registries (or
                                                      override).
    - `:rf.xray.static.schemas/tab-data`           — view composite."
  []

  ;; ---- UI state ---------------------------------------------------------

  (rf/reg-event :rf.xray.static.schemas/set-query
    (fn [{:keys [db]} [_ q]]
      {:db (if (or (nil? q) (= "" q))
        (dissoc db :rf.xray.static.schemas/query)
        (assoc db :rf.xray.static.schemas/query q))}))

  (rf/reg-sub :rf.xray.static.schemas/query
    (fn [db _]
      (get db :rf.xray.static.schemas/query)))

  ;; The test-only override seam (`:rf.xray.static.schemas/set-registry-
  ;; override-for-test` + the `*-override` sub) is NOT installed here —
  ;; production registration carries no `-for-test` ids. Tests opt into
  ;; it via `install-test-overrides!`.

  ;; ---- production data sub ---------------------------------------------

  ;; Assembles the three input registries from public surfaces once per
  ;; re-fire: app-db schemas via the `re-frame.schemas` façade
  ;; (`rf/frame-ids` + one `rf.schemas/app-schemas {:frame f}` read per
  ;; frame) and event / sub specs via `(rf/registrations {:source :store :kind <kind>})`.
  ;; Declares the trace buffer in its `:inputs` so the sub is reactive
  ;; against the same "something changed" pulse the other Static-mode
  ;; subs ride.
  (rf/reg-sub :rf.xray.static.schemas/registry
    {:inputs [[:rf.xray/trace-buffer]]}
    (fn [[_buffer] _query]
      (registry-value)))

  ;; ---- view-facing composite -------------------------------------------

  ;; `:rf.xray/observed-frame` is the L1 frame picker's current
  ;; selection. App-db schemas are per-frame (the `schemas-by-frame`
  ;; side-table is keyed by frame-id), so the picker scopes the app-db
  ;; rows — switching frames changes which frame's app-db schemas
  ;; list. Event + sub specs are process-global (Spec 001) and stay
  ;; cross-frame regardless of the picker.
  (rf/reg-sub :rf.xray.static.schemas/tab-data
    {:inputs [[:rf.xray.static.schemas/registry]
              [:rf.xray/observed-frame]
              [:rf.xray.static.schemas/query]]}
    (fn [[{:keys [schemas-by-frame events subs]} observed-frame query] _query]
      (project-data schemas-by-frame events subs observed-frame query)))

  ;; Register the Static Schemas tab with the internal L4 tab registry.
  (panel-registry/reg-l4-tab!
    {:id    :schemas
     :label "Schemas"
     :mnem  "c"
     :modes #{:static}
     :order 2
     ;; rf2-k97c.3 — `Panel-bridge`, not `Panel`. `Panel` is now a React
     ;; component (a Fresco boundary) and the Static shell mounts `:panel`
     ;; as a Reagent hiccup head; the bridge is the one line between them
     ;; and goes when the shell is a Fresco tree.
     :panel Panel-bridge})

  nil)

;; ---- test-only override seam --------------------------------------------

(defn install-test-overrides!
  "Install the Static Schemas panel's test-only override seam — the
  `:rf.xray.static.schemas/set-registry-override-for-test` event + the
  `*-override` sub, then RE-register the production
  `:rf.xray.static.schemas/registry` sub to layer the override read on
  top. Tests opt in via `test-support/install-test-overrides!` AFTER
  `register-xray-handlers!`. **Test-only — never call from production.**"
  []
  (rf/reg-event :rf.xray.static.schemas/set-registry-override-for-test
    (fn [{:keys [db]} [_ ov]]
      {:db (if (nil? ov)
        (dissoc db :rf.xray.static.schemas/registry-override)
        (assoc db :rf.xray.static.schemas/registry-override ov))}))
  (rf/reg-sub :rf.xray.static.schemas/registry-override
    (fn [db _]
      (get db :rf.xray.static.schemas/registry-override)))

  (rf/reg-sub :rf.xray.static.schemas/registry
    {:inputs [[:rf.xray/trace-buffer] [:rf.xray.static.schemas/registry-override]]}
    (fn [[_buffer override] _query]
      (or override (registry-value))))
  nil)
