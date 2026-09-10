(ns day8.re-frame2-xray.panels.reactive-panel
  "View panel facade (rf2-e33ad · Mike-direction 2026-05-21 ·
  prior name: Reactive · prior bead: rf2-wyvf2 · spec/021 §3).

  Per Mike's 2026-05-21 design direction the tab is renamed from
  `Reactive` to `View` — the panel's primary subject is the rendered
  view (the operator hovers a view-row, the rendered DOM highlights)
  while the sub event-bundle is the supporting context.

  The internal panel-registry key stays `:views` (it was always the
  internal id, never a user contract). Per rf2-8ve8z (phase-B of the
  Views-tab redesign) the panel renders the reactive event-bundle as THREE
  STACKED TABLES, top→bottom mirroring the event-bundle flowing toward the
  UI:

      Level 1 subs (observe app-db)   name | changed | code — the subs
                                      that read app-db directly
                                      (`:inputs []` per sub-topology)
      Level 2+ subs                   name | changed | inputs | code —
                                      composed subs; inputs lists the
                                      input-sub names one per line
      Views:                          name | action | reason — one row
                                      per view render/unmount. `name` is
                                      hoverable (pink DOM highlight,
                                      rf2-8l03l); `action` is mount /
                                      rerender / unmount; `reason` is the
                                      changed subs THIS view reads, or
                                      `← parent re-render` (structural,
                                      UNNAMED).

  The Level 1 / Level 2+ split + the inputs/code columns come from
  `re-frame.subs.tooling/sub-topology`; the view action + reason ride
  phase-A's (rf2-9hoos) `:mount?` / `:deref-subs` fields on
  `:rf.view/rendered` + the new `:rf.view/unmounted` op.

  ## Public surface

  - `Panel`        — the canonical embed (per
                     `tools/xray/spec/008-Embedding-Contract.md`); since
                     rf2-k97c.3 an `rf.fresco/defview` BOUNDARY — a real
                     React function component, not a `reg-view`.
  - `Panel-bridge` — the callable a still-`reg-view` shell (and the
                     standalone `panels/mount-reactive-panel!` embed)
                     mounts. Scaffolding with a defined end: it goes in
                     step 3's commit, when `Panel` takes the slot directly.
  - `install!`     — idempotent install for `:rf.xray/reactive-data` +
                     the panel-local toggle + the L4 tab registration."
  (:require [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.panels.reactive-panel-events :as events]
            [day8.re-frame2-xray.panels.reactive-panel-subs :as subs]
            [day8.re-frame2-xray.panels.reactive-panel-view :as view]))

(rf.fresco/defview Panel
  "The Reactive panel's root — a FRESCO BOUNDARY (rf2-k97c.3), not an
  `rf/reg-view`. Reads `:rf.xray/reactive-data` and hands the value plus a
  frame-bound dispatcher to `view/reactive-panel`, the pure hiccup
  projection.

  The READ is `rf.fresco/sub`, a plain call the collector records an edge
  for — no deref and no reaction owned by the installed adapter, which is
  the third of the epic's three couplings and the one a first-paint smoke
  test cannot see. The FRAME it resolves against comes from React context,
  which the enclosing frame boundary writes; `rf/frame-provider` and
  `rf.fresco/frame-provider` write the SAME context, so this resolves
  `:rf/xray` identically under today's Reagent-rendered shell and under
  the Fresco root Xray will own.

  The DISPATCHER is `(:dispatch (rf/capture-frame))` — core's own door,
  which Fresco's authoring surface deliberately does not duplicate, and
  which answers the boundary's declared frame inside a body. It replaces
  the `reg-view`-injected bare `dispatch` (rf2-16y3x), which `defview`
  binds no name for; the disclosure toggle's deferred `:on-click` still
  lands on THIS Xray instance's frame after render scope unwinds rather
  than leaking to `:rf/default`.

  The argument is the ordinary one-props-map vector every `defview`
  takes. This panel reads nothing from props — the L4 registry and the
  standalone embed both mount it with none — so it is destructured away."
  [_props]
  (view/reactive-panel (:dispatch (rf/capture-frame))
                       (rf.fresco/sub [:rf.xray/reactive-data])))

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
;; it answers a real React component a React parent mounts UNDER THE FRAME
;; IT IS ALREADY IN, taking the frame from React context rather than from a
;; second root. So there is no second root here and no props ABI.
;;
;; THIS IS SCAFFOLDING WITH A DEFINED END. When the shell is itself a
;; Fresco tree, `reg-l4-tab!` and `mount-reactive-panel!` take `Panel`
;; directly, `[:>]` goes, and both defs below are deleted.

(def ^:private Panel-component
  "The React component `Panel` presents as, for a non-Fresco parent.
  Declared once at top level beside the view, as `rf.fresco/as-component`'s
  contract requires — deriving it per render would mint a new component
  type every time and remount the panel on each parent render."
  (rf.fresco/as-component Panel))

(defn Panel-bridge
  "The callable the L4 tab registry stores and `panels/mount-reactive-panel!`
  hands to `render-panel!`. Returns Reagent-shaped hiccup interoping to the
  React component above; the enclosing `rf/frame-provider` is what puts
  `:rf/xray` in React context for it.

  PUBLIC because the standalone `mount-*!` facade in `panels.cljs` passes
  it by name — unlike the L4-only panels, whose bridge can stay private."
  []
  [:> Panel-component {}])

(defn install!
  "Idempotent install for the Reactive panel's Xray-side
  registrations. Returns nil per the facade convention."
  []
  (subs/install!)
  (events/install!)
  ;; rf2-wyvf2 — register with the L4 tab registry. Display label is
  ;; 'Views' per spec/021 §11.5 (rf2-5i8nn); the tab key stays `:views`
  ;; (the internal id is not a user contract — pre-alpha posture
  ;; preserves the slot for the smaller diff).
  (panel-registry/reg-l4-tab!
    {:id    :views
     ;; Display label renamed `Reactive` -> `View` -> `Views`. The
     ;; all-plural-domain-noun convention (Mike-direction 2026-05-21)
     ;; aligns the tab vocabulary across both tab sets — Views / Flows /
     ;; Schemas / Routes / Machines are all plural. Internal id stays
     ;; `:views`.
     :label "Views"
     :mnem  "v"
     :modes #{:dynamic}
     :order 2
     ;; rf2-k97c.3 — `Panel-bridge`, not `Panel`. `Panel` is now a React
     ;; component (a Fresco boundary) and the shell mounts `:panel` as a
     ;; Reagent hiccup head; the bridge is the one line between them and
     ;; goes when the shell is a Fresco tree.
     :panel Panel-bridge})
  nil)
