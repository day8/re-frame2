(ns day8.re-frame2-xray.panels.app-db-diff
  "app-db tab — current-state inspector (rf2-okvit).

  The app-db tab is a CURRENT-STATE inspector in the re-frame-10x style:
  it renders the observed frame's LIVE `app-db` value, sectioned by
  reserved `:rf/*` area. It is NOT a diff — the diff / value-changed /
  'show me when this changed' affordances were dropped here (rf2-okvit).
  Diff rendering lives in the Epoch panel's `:db` + `:fx` section.

  ## Layout (rf2-okvit)

  The section model `app-db-diff-helpers/current-state-sections`
  produces drives the body:

    - a TOP section — the app-db MINUS every reserved `:rf*` key (the
      user-domain app-db). Per spec/Conventions.md §Reserved namespaces
      any `:rf/*` / `:rf.<subns>/*` key the framework stashes at the
      app-db root is hidden from TOP.
    - one section per operator-facing runtime area (per the
      `runtime-areas` table — machines, routing, spawned,
      pending-navigation, elision), sourced from the SEPARATE runtime-db
      partition (EP-0001 rf2-vzld77 / rf2-tj6w9l — the framework's durable
      subsystem state moved out of app-db's `:rf/runtime` into the
      `:rf.runtime/*` runtime-db roots; this panel reads it via
      `:rf.xray/target-frame-runtime-db` + each focused epoch's runtime-db
      pre/post-image, the same way the Machines inspector + Routing tab
      do). Map-of-instances areas (`:rf/machines`, `:rf/spawned`) FAN OUT
      to one named sub-section per instance (section title = the instance
      id, e.g. `:title/flow`). Singleton slices (the current-route slice
      at `[:rf.runtime/routing :current]` per spec/012 §The `:rf/route`
      slice, and the rest) render as one section each. Absent / empty
      areas are omitted (rf2-jcdvo).

  Values render through the canonical EDN widget's cljs-devtools
  current-state path (`views.edn-widget/inspect`), the same
  engine re-frame-10x adopted.

  Canonical exemplar of the panel facade pattern documented in
  `tools/xray/spec/Conventions.md` — facade owns the public
  `reg-view`, leaves expose plain fns + `install!`, the facade's
  `install!` chains leaf installs and returns `nil`.

  ## Companion namespaces

  - `app-db-diff-state` — the current-state section renderers (this
    panel's body).
  - `app-db-diff-subs` / `app-db-diff-events` — subs + events. This
    panel reads only `:rf.xray/app-db-state` (← the atomic
    `:rf.xray/app-db-current+diff`, which resolves the focused epoch's
    `:db-after` per rf2-02j4r) + `:rf.xray/app-db-current+diff` for the
    per-epoch React render key. rf2-p53m2 — the former composite diff
    family (`:rf.xray/selected-epoch-diff` → `:rf.xray/app-db-diff` and
    its `-flow-writes` / `-redacted-modified-count` inputs) was PRUNED:
    it had no production view consumer. The Epoch panel's `:db` diff
    reads `:rf.xray/selected-epoch-record` and runs its own
    `db-diff-paths`; the MCP `get-app-db-diff` tool projects directly
    through `diff.engine/project` (runtime.cljs) — neither consumed the
    composite."
  ;; rf2-k97c.3 — `re-frame.core` is no longer required here. Both reads
  ;; moved to `rf.fresco/sub` inside the boundary, and this panel dispatches
  ;; nothing, so nothing in the file resolves through core's door any more.
  (:require [re-frame.fresco :as rf.fresco]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.panels.app-db-diff-events :as events]
            [day8.re-frame2-xray.panels.app-db-diff-state :as state]
            [day8.re-frame2-xray.panels.app-db-diff-subs :as subs]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens sans-stack]]
            ))

;; ---- style hoists (rf2-mndut) -------------------------------------------
;;
;; Every literal `:style {...}` map in the Panel view below is hoisted to
;; ns-top defs so React's reconciler sees stable object identities across
;; re-renders (follow-on to rf2-qx414 / rf2-zlk6h / rf2-xjgdk / rf2-gjiog
;; / rf2-alsnz). `tokens` values resolve to `var(--rf-xray-*)` CSS strings
;; at ns load so the light/dark theme toggle continues to flip palette in
;; lockstep without re-evaluation (spec/007 §UX-IA).

(def ^:private panel-root-style
  "Outer `[:section]` chrome for the app-db Panel view."
  {:height         "100%"
   :display        "flex"
   :flex-direction "column"
   :background     (:bg-2 tokens)
   :color          (:text-primary tokens)
   :font-family    sans-stack
   :font-size      "14px"})

(def ^:private panel-body-host-style
  "Scrolling host for the section list / flat-diff body."
  {:flex     1
   :overflow "auto"})

(defn panel-tree
  "The app-db panel's hiccup projection — a PURE FUNCTION of the two
  values `Panel` reads. No read of its own.

  rf2-k97c.3 — split out of `Panel` when `Panel` became a Fresco boundary.
  A boundary's body may only run inside a React render window, so `(Panel)`
  is no longer a callable that answers hiccup, and the unit rows that walk
  this markup by `data-testid` need something they can drive. This is
  `defview`'s own documented extract-a-helper spelling, and keeping the
  MARKUP here rather than transcribed into a test is what keeps those rows
  honest: they still fail when the panel's own shape drifts.

  rf2-vv3m6 (2026-05-29) — the prior `[diff][full][full+diff]` mode
  toggle (rf2-yqjrd) is retired. FULL+DIFF is the single rendering:
  every section renders LIVE current-state WITH the focused epoch's
  `:db-before` threaded so inline diff annotations paint. The auto-
  collapse of unchanged subtrees (rf2-fqcdd), the leaf-scalar `← was X`
  annotation (rf2-fyd8u), and the added/removed colouring (rf2-9d4j8)
  together give FULL+DIFF the density `:diff` used to provide and the
  comparison-context `:full` lacked, so the three-mode toggle (and its
  sub/event/slot trio) is gone."
  [section-model selected-epoch-id]
  [:section {:data-testid "rf-xray-app-db-diff"
             ;; rf2-xvu24 — canonical `data-rf-xray-diff-mode` axis on
             ;; the enclosing section. FULL+DIFF is the single mode
             ;; post-rf2-vv3m6 so the attribute is now a constant
             ;; (kept for selector compatibility — tools + e2e specs
             ;; can still pin "this section is rendering FULL+DIFF").
             :data-rf-xray-diff-mode "full+diff"
             :style       panel-root-style}
   ;; rf2-6xezz — the L4 tab strip is the panel-name source-of-truth;
   ;; content starts immediately under the tab bar.
   [:div {:style panel-body-host-style}
    ;; rf2-5kfxe.2 / rf2-yng0y — key the state body by the focused
    ;; epoch-id so each epoch navigation forces a clean React re-mount
    ;; per event-bundle (replaying the diff-flash CSS animation as
    ;; originally intended, and flushing any per-mount carryover). This
    ;; is a COMPLEMENT to the atomic sub above, not a substitute: a
    ;; remount alone does not fix a sub-level stale-`before` (a fresh
    ;; mount would still receive whatever `before` the sub hands it) —
    ;; the atomic sub guarantees that `before` is never stale; the key
    ;; guarantees a clean per-epoch mount. Zoom + expansion survive the
    ;; remount because both are keyed by the stable `:site-id
    ;; [:rf.xray/app-db render-id]` (e.g. ["top"]) in the `:rf/xray`
    ;; frame's app-db (value-body, app_db_diff_state.cljs), not by
    ;; React component identity.
    ;;
    ;; rf2-k97c.3 — two changes, both forced by the boundary. `state-body`
    ;; is CALLED, never used as a hiccup head: a plain fn in head position
    ;; is a loud error under Fresco (and a silent extra component under
    ;; Reagent). And the remount key moves off reader metadata onto a
    ;; keyed FRAGMENT — Fresco's codec reads a literal `:key` in the
    ;; attribute map and nothing else, `state-body` answers hiccup with no
    ;; attribute map of the panel's own to write into, and `[:<> …]` adds
    ;; no DOM node, so the per-epoch clean remount this comment describes
    ;; survives the migration intact.
    [:<> {:key selected-epoch-id}
     (state/state-body section-model)]]])

(rf.fresco/defview Panel
  "The app-db tab's root — a current-state inspector sectioned by
  reserved `:rf/*` area. The markup is [[panel-tree]]; this is the READ.

  A FRESCO BOUNDARY (rf2-k97c.3), not an `rf/reg-view`. Both reads are
  `rf.fresco/sub`, plain calls the shipped collector records an edge for
  — no deref and no reaction owned by the installed adapter, which is the
  third of the epic's three couplings and the one a first-paint smoke
  test cannot see. The FRAME they resolve against comes from React
  context, which the enclosing frame boundary writes; `rf/frame-provider`
  and `rf.fresco/frame-provider` write the SAME context, so this resolves
  `:rf/xray` identically under today's Reagent-rendered shell and under
  the Fresco root Xray will own.

  rf2-yng0y — the focused epoch-id is sourced from the SAME atomic sub the
  section model derives from (`:rf.xray/app-db-current+diff`), so the
  render key and the threaded `:before` move in lockstep and can never
  name different epochs on the same frame. That property is a property of
  READING BOTH HERE, which is why the two reads stay together in this body
  rather than moving down into the projection.

  The argument is the ordinary one-props-map vector every `defview`
  takes. This panel reads nothing from props — the L4 registry and the
  standalone embed both mount it with none — so it is destructured away."
  [_props]
  (panel-tree (rf.fresco/sub [:rf.xray/app-db-state])
              (:epoch-id (rf.fresco/sub [:rf.xray/app-db-current+diff]))))

;; ---- the migration bridge (rf2-k97c.3) -----------------------------------
;;
;; Xray's shell is still a `reg-view` tree rendered by the installed
;; adapter, and `panels/render-panel!` takes the view to mount as an
;; ARGUMENT and builds a Reagent tree around it. `defview`'s contract is
;; that a boundary is mounted as `[head props]` inside a Fresco body or
;; through `as-component` from outside — never as a hiccup render fn in a
;; Reagent tree. `panel-registry/reg-l4-tab!`'s `:pre` likewise requires
;; `:panel` to be CALLABLE, which a React component is not.
;;
;; `rf.fresco/as-component` is Fresco's own outward door for exactly this:
;; a React parent mounts the component UNDER THE FRAME IT IS ALREADY IN,
;; taking the frame from React context rather than from a second root.
;;
;; THIS IS SCAFFOLDING WITH A DEFINED END. When the shell is itself a
;; Fresco tree, `reg-l4-tab!` and `mount-app-db-diff!` take `Panel`
;; directly, `[:>]` goes, and both defs below are deleted.

(def ^:private Panel-component
  "The React component `Panel` presents as, for a non-Fresco parent.
  Declared once at top level beside the view, as `rf.fresco/as-component`'s
  contract requires — deriving it per render would mint a new component
  type every time and remount the panel on each parent render."
  (rf.fresco/as-component Panel))

(defn Panel-bridge
  "The callable the L4 tab registry stores and `panels/mount-app-db-diff!`
  hands to `render-panel!`. Returns Reagent-shaped hiccup interoping to the
  React component above; the enclosing `rf/frame-provider` is what puts
  `:rf/xray` in React context for it.

  PUBLIC because the standalone `mount-*!` facade in `panels.cljs` passes
  it by name — unlike the L4-only panels, whose bridge can stay private."
  []
  [:> Panel-component {}])

(defn install!
  "Idempotent install for the app-db tab's Xray-side registrations.
  Returns nil per the facade convention."
  []
  (subs/install!)
  (events/install!)
  ;; rf2-2moh1 — register the Dynamic app-db tab with the internal L4
  ;; tab registry. rf2-okvit — label is lowercase "app-db" to match the
  ;; library's app-db naming.
  (panel-registry/reg-l4-tab!
    {:id    :app-db
     :label "app-db"
     :mnem  "a"
     :modes #{:dynamic}
     :order 1
     ;; rf2-k97c.3 — `Panel-bridge`, not `Panel`. `Panel` is now a React
     ;; component (a Fresco boundary) and the shell mounts `:panel` as a
     ;; Reagent hiccup head; the bridge is the one line between them and
     ;; goes when the shell is a Fresco tree.
     :panel Panel-bridge})
  nil)
