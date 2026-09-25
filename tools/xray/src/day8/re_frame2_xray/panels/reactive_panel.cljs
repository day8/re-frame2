(ns day8.re-frame2-xray.panels.reactive-panel
  "View panel facade (spec/021 §3).

  The panel's primary subject is the rendered view (the operator hovers
  a view, the rendered DOM highlights) while the sub event-bundle is the
  supporting context. The internal panel-registry key is `:views` — the
  internal id, never a user contract.

  The panel renders the reactive event-bundle as a left → right REACTIVE
  FLOW graph — app-db → Level-1 subs → Level-2+ subs → views; see
  `reactive-panel-view` for its shape and `reactive-flow-graph` for its
  layout. The Level 1 / Level 2+ split comes from
  `re-frame.subs.tooling/sub-topology`; each view's action + cause ride
  the `:mount?` / `:deref-subs` fields on `:rf.view/rendered` + the
  `:rf.view/unmounted` op.

  ## Public surface

  - `Panel`        — the canonical embed (per
                     `tools/xray/spec/008-Embedding-Contract.md`); an
                     `rf.fresco/defview` BOUNDARY — a real React
                     function component, not a `reg-view`.
  - `Panel-bridge` — the callable the L4 registry (and the standalone
                     `panels/mount-reactive-panel!` embed) mounts. NOT
                     scaffolding: the shell reaches the panel across an
                     `as-child` seam and `reg-l4-tab!`'s `:pre` requires
                     a callable `:panel`.
  - `install!`     — idempotent install for `:rf.xray/reactive-data` +
                     the panel-local toggle + the L4 tab registration."
  (:require [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.panels.reactive-panel-events :as events]
            [day8.re-frame2-xray.panels.reactive-panel-subs :as subs]
            [day8.re-frame2-xray.panels.reactive-panel-view :as view]))

(rf.fresco/defview Panel
  "The Reactive panel's root — a FRESCO BOUNDARY, not an
  `rf/reg-view`. Reads `:rf.xray/reactive-data` and hands the value plus a
  frame-bound dispatcher to `view/reactive-panel`, the pure hiccup
  projection.

  The READ is `rf.fresco/sub`, a plain call the collector records an edge
  for — no deref and no reaction owned by the installed adapter, which is
  the coupling a first-paint smoke test cannot see. The FRAME it resolves
  against comes from React context, which the enclosing frame boundary
  writes; `rf/frame-provider` and `rf.fresco/frame-provider` write the
  SAME context, so this resolves `:rf/xray` identically under the Fresco
  root Xray owns and under an `rf/frame-provider` a Reagent parent writes.

  The DISPATCHER is `(:dispatch (rf/capture-frame))` — core's own door,
  which Fresco's authoring surface deliberately does not duplicate, and
  which answers the boundary's declared frame inside a body. `defview`
  binds no `dispatch` name, so this door supplies it; the disclosure
  toggle's deferred `:on-click` lands on THIS Xray instance's frame after
  render scope unwinds. A bare
  global `rf/dispatch` in its place would resolve no frame once that
  scope is gone and RAISE `:rf.error/no-frame-context` — there is no
  `:rf/default` floor under EP-0002 to absorb it.

  The argument is the ordinary one-props-map vector every `defview`
  takes. This panel reads nothing from props — the L4 registry and the
  standalone embed both mount it with none — so it is destructured away."
  [_props]
  (view/reactive-panel (:dispatch (rf/capture-frame))
                       (rf.fresco/sub [:rf.xray/reactive-data])))

;; ---- the React-component bridge -----------------------------------------
;;
;; `panels/render-panel!` takes the view to mount as an ARGUMENT and
;; builds a REAGENT tree around it, and `shell/detail-panel` — a Fresco
;; boundary — reaches each registered tab across an
;; `as-child` seam rather than heading it. `defview`'s contract is
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
;; NOT SCAFFOLDING. `panels/mount-reactive-panel!` reaches this bridge
;; through `render-panel!`, which is ratom-family, so a Reagent parent
;; heads it whatever the L4 registry does. The chain is `[:>]` ->
;; `as-component` -> `Panel`.

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
  ;; Register with the L4 tab registry. Display label is 'Views' per
  ;; spec/021 §11.5; the tab key is `:views` (the internal id is not a
  ;; user contract).
  (panel-registry/reg-l4-tab!
    {:id    :views
     ;; The all-plural-domain-noun convention aligns the tab vocabulary
     ;; across both tab sets — Views / Flows / Schemas / Routes /
     ;; Machines are all plural.
     :label "Views"
     :mnem  "v"
     :modes #{:dynamic}
     :order 2
     ;; `Panel-bridge`, not `Panel`. `Panel` is a React component (a
     ;; Fresco boundary) and the shell mounts `:panel` as a Reagent hiccup
     ;; head; the bridge is the one line between them. The shell is a
     ;; Fresco tree, yet it reaches the panel across an `as-child` seam,
     ;; so `[(:panel tab)]` is a Reagent hiccup vector and
     ;; `reg-l4-tab!`'s `:pre` requires a callable `:panel`.
     :panel Panel-bridge})
  nil)
