(ns day8.re-frame2-causa.panels
  "Public per-panel mount API — rf2-crhr8.

  Every Causa panel is independently mountable: a host can mount one
  panel in isolation without the surrounding 4-layer shell, without
  sibling panels, and without any shell-owned chrome state. This
  contract is the load-bearing surface that lets Causa be embedded
  inside Story, inside the Scittle playground (rf2-i8mv option-c
  progressive disclosure), inside custom debugging configurations,
  and inside the docs / guide / examples surface.

  ## The contract

  Per `tools/causa/spec/008-Embedding-Contract.md` every panel exposes
  a public mount fn:

      (mount-event-detail!     mount-point opts) → unmount-fn
      (mount-app-db-diff!      mount-point opts) → unmount-fn
      (mount-reactive-panel!   mount-point opts) → unmount-fn
      (mount-trace!            mount-point opts) → unmount-fn
      (mount-machine-inspector! mount-point opts) → unmount-fn
      (mount-routing!          mount-point opts) → unmount-fn
      (mount-issues-ribbon!    mount-point opts) → unmount-fn

      ;; Overlay / popup surfaces — same contract.
      (mount-segment-inspector! mount-point opts) → unmount-fn
      (mount-cancellation-cascade-side-panel! mount-point opts) → unmount-fn
      (mount-cancellation-cascade-popover!    mount-point opts) → unmount-fn

      ;; Inline content surface — managed-fx wire-boundary diff. The
      ;; canonical embed of the per-cascade managed-fx records list.
      (mount-managed-fx! mount-point opts) → unmount-fn

  Plus the master entry that mounts the full 4-layer shell:

      (mount-shell! mount-point opts) → unmount-fn

  ## What every mount fn does

  1. Calls `(registry/register-causa-handlers!)` — idempotent, registers
     every panel's subs + events + fxs under `:rf.causa/*`.
  2. Calls `(rf/reg-frame :rf/causa {})` — idempotent, ensures the
     state-isolation frame exists.
  3. Wraps the panel's `Panel` (or equivalent) view in
     `[rf/frame-provider {:frame :rf/causa} [<Panel>]]` so descendant
     subscribes / dispatches re-anchor to `:rf/causa` regardless of
     the host's React-context.
  4. Delegates to `substrate-adapter/render` with the wrapped tree +
     the supplied mount-point. The substrate adapter is the host's
     (installed via `rf/init!`); the panels are substrate-agnostic
     pure hiccup.
  5. Returns the adapter's unmount fn so the host owns lifecycle.

  ## Why the aggregator pattern

  Each panel facade already follows the canonical shape — public
  `Panel` reg-view + `install!` (per `tools/causa/spec/Conventions.md`
  §Panel facade + leaf split). The mount-fns here are thin wrappers:
  they delegate the chrome (frame-provider, registry install, adapter
  render) to one place so every panel inherits the same contract by
  construction. Adding a new panel = add a new `mount-<panel>!` line.

  ## Per-panel inputs (the coupling-map audit, rf2-crhr8 §1)

  Every panel reads its data via subscribes — no sibling-render
  assumptions, no shell-owned local state. The subs (registered by
  the panel's own `install!`) compose against:

  | Panel | Reads | Writes (via dispatch) |
  |---|---|---|
  | **event-detail** | `:rf.causa/focus` · `:rf.causa/cascades` · `:rf.causa/target-frame-db` | `:rf.causa/focus-cascade` |
  | **app-db-diff** | `:rf.causa/app-db-diff` (composite over target-frame + epoch history + focused slice path) | `:rf.causa/focus-slice-path` · `:rf.causa/open-segment-inspector` |
  | **reactive-panel** | `:rf.causa/reactive-data` (composite over focused cascade's `:trace-events`) | `:rf.causa/reactive-toggle-unchanged` |
  | **trace** | `:rf.causa/trace-feed` (incremental projection over the buffer) | `:rf.causa/select-dispatch-id` · `:rf.causa/open-in-editor` |
  | **machine-inspector** | `:rf.causa/machine-chart-data` · `:rf.causa/active-timers-for-focused-machine` · `:rf.causa/machine-scrubber-position` | scrubber events · `:rf.causa/focus-cascade` |
  | **routing** | `:rf.causa/registered-routes` · `:rf.causa/current-route-slice` · `:rf.causa/routing-tab-data` | route-simulation events |
  | **issues-ribbon** | `:rf.causa/issues-ribbon` (composite over focused epoch's `:trace-events` + filter chips per spec/021 §8) | `:rf.causa.issues/toggle-severity` · `:rf.causa.issues/toggle-prefix` · `:rf.causa.issues/clear-filters` |
  | **segment-inspector** | `:rf.causa/segment-inspector-open?` · `:rf.causa/segment-inspector-value` | `:rf.causa/close-segment-inspector` |
  | **cancellation-cascade** (side-panel + popover) | `:rf.causa/cancellation-cascade-for-focused-machine` · `:rf.causa/cancellation-cascade-for-focused-event` · `:rf.causa/cancellation-cascade-popover-open?` · `:rf.causa/modal-positioning` | `:rf.causa/cancellation-cascade-close` |
  | **managed-fx** | `:rf.causa/managed-fx-for-focused-event` | `:rf.causa/focus-event` |

  No panel reads sibling-panel state directly. No panel assumes any
  particular frame-picker / tab-bar / event-list / spine-head value
  beyond what the spine sub `:rf.causa/focus` exposes — and `focus`
  itself defaults to head of the trace buffer when no row is
  selected. Each panel is fully driven by the trace bus + the host's
  `(rf/init!)` plumbing.

  ## Internal sub-components — not independently mountable

  Five surfaces inside `machine-inspector/Panel` are auxiliary
  inspectors that depend on the chart's positioned graph for their
  geometry: `AfterRingsOverlay`, `ArcOverlay`, `ClusterView`,
  `ScrubberStrip`, `SimSideRail`. These render under
  `machine-inspector/Panel` (which owns the chart) and are not
  exposed as standalone mount fns — mounting a ring overlay without
  a chart underneath is geometrically meaningless. They remain
  reachable via `machine-inspector/Panel` and document themselves
  as internal sub-components.

  ## Frame-provider opt — `:frame` defaults to `:rf/causa`

  The default `opts` map is `{:frame :rf/causa}` — Causa's own
  state-isolation frame. Hosts that embed a panel to observe a
  specific app frame pass `{:frame :my-app/cart}` per the embedding
  contract — the panel's subscribes still resolve to `:rf/causa` for
  Causa's own UI state, while the panel-internal frame-selection sub
  (`:rf.causa/observed-frame`) drives the data axis.

  See `tools/causa/spec/007-UX-IA.md` §Mountable panel contract and
  `tools/causa/spec/008-Embedding-Contract.md` for the full
  embedding contract."
  (:require [re-frame.core :as rf]
            [re-frame.substrate.adapter :as substrate-adapter]
            [day8.re-frame2-causa.mount :as mount]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.panels.app-db-diff :as app-db-diff]
            [day8.re-frame2-causa.panels.app-db-segment-inspector :as segment-inspector]
            [day8.re-frame2-causa.panels.cancellation-cascade :as cancellation-cascade]
            [day8.re-frame2-causa.panels.event-detail :as event-detail]
            [day8.re-frame2-causa.panels.issues-ribbon :as issues-ribbon]
            [day8.re-frame2-causa.panels.machine-inspector :as machine-inspector]
            [day8.re-frame2-causa.panels.managed-fx-template :as managed-fx]
            [day8.re-frame2-causa.panels.routing :as routing]
            [day8.re-frame2-causa.panels.trace :as trace]
            [day8.re-frame2-causa.panels.reactive-panel :as reactive-panel]
            [day8.re-frame2-causa.shell :as shell]))

;; ---- internal scaffolding -----------------------------------------------

(defn- ensure-causa-handlers-installed!
  "Idempotent — `register-causa-handlers!` carries its own sentinel so
  multiple panel mounts collapse to one registration pass.

  Routes through `mount/ensure-causa-frame!` so the frame is not just
  registered but ALSO seeded via the first-mount hook table (rf2-y1saa)
  — `::seed-trace-and-target-frame`, `::hydrate-filters`,
  `::hydrate-spine-filters`, `::hydrate-static-mode`,
  `::auto-open-watcher`. A direct `(rf/reg-frame :rf/causa {})` here
  would register the frame but skip the hook table, leaving Causa's
  trace-buffer slot empty + `:target-frame` pinned to
  `defaults/default-target-frame` regardless of what's already in the
  trace-bus and the host's epoch ring. That misalignment is the
  empty-Causa-on-Story-RHS class of bug — Story embeds a panel via
  `mount-<panel>!`, the panel renders against a frame the hooks never
  populated, and the user sees blank inputs even though the host has
  been dispatching events.

  `ensure-causa-frame!` is idempotent (sentinel-guarded hooks +
  reg-frame's surgical-update-on-re-register semantics per Spec 002
  §reg-frame) so multiple panel mounts collapse to one seed pass +
  zero re-registrations across shadow-cljs reloads."
  []
  (registry/register-causa-handlers!)
  (mount/ensure-causa-frame!))

(defn- render-panel!
  "Internal helper. Wraps `panel-view` in `[rf/frame-provider {:frame
  frame} [panel-view]]` and delegates to the substrate adapter's
  render fn. Returns the adapter's unmount fn so the caller can
  tear the mount down without going through this ns again.

  - `panel-view` — the panel's `reg-view`-registered Var (e.g.
    `event-detail/Panel`). Wrapped in a Reagent component-vector so
    React-context flows correctly per Spec 006 §706 (a plain `defn`
    invoked as a fn-call would skip the React-context tier and the
    panel's subscribes would route to `:rf/default`).
  - `mount-point` — a DOM element (or substrate-equivalent mount
    target).
  - `opts` — `{:frame <frame-id>}` minimum. Defaults to `:rf/causa`.
    The frame the `frame-provider` resolves to. Hosts embedding a
    panel to observe a specific app frame pass that frame id; the
    panel's own Causa state still lives on `:rf/causa` (the panel
    facade always opens with its own `frame-provider :rf/causa`
    when its body subscribes to `:rf.causa/*` data)."
  [panel-view mount-point opts]
  (ensure-causa-handlers-installed!)
  (let [frame (get opts :frame :rf/causa)
        tree  [rf/frame-provider {:frame frame}
               [panel-view]]]
    (substrate-adapter/render tree mount-point nil)))

;; ---- per-panel mount fns ------------------------------------------------
;;
;; All eleven public mount fns share the same shape — install handlers
;; → wrap panel view in frame-provider → delegate to substrate adapter.
;; The only per-panel axis is which `Panel` (or equivalent) view to
;; render. This keeps the surface uniform — adding a panel = adding a
;; line below; the chrome (registration, frame wiring, substrate
;; delegation) lives in `render-panel!` exactly once.

(defn mount-event-detail!
  "Mount Causa's Event tab in isolation at `mount-point`. Renders the
  six-domino cascade view for the current spine focus.

  See ns docstring for `opts` shape + the embedding contract."
  ([mount-point]      (mount-event-detail! mount-point nil))
  ([mount-point opts] (render-panel! event-detail/Panel mount-point opts)))

(defn mount-app-db-diff!
  "Mount Causa's App-DB tab in isolation at `mount-point`. Renders the
  sections-per-cluster structural diff for the focused cascade."
  ([mount-point]      (mount-app-db-diff! mount-point nil))
  ([mount-point opts] (render-panel! app-db-diff/Panel mount-point opts)))

(defn mount-reactive-panel!
  "Mount Causa's Reactive tab in isolation at `mount-point` (rf2-wyvf2).
  Renders the canonical sub-cascade + view-re-render visualisation
  per spec/021 §3."
  ([mount-point]      (mount-reactive-panel! mount-point nil))
  ([mount-point opts] (render-panel! reactive-panel/Panel mount-point opts)))

(defn mount-trace!
  "Mount Causa's Trace tab in isolation at `mount-point`. Renders the
  trace-buffer feed for the focused cascade."
  ([mount-point]      (mount-trace! mount-point nil))
  ([mount-point opts] (render-panel! trace/Panel mount-point opts)))

(defn mount-machine-inspector!
  "Mount Causa's Machines tab in isolation at `mount-point`. Renders
  the chart + arc/ring/cluster overlays for the focused machine.
  The auxiliary inspectors (AfterRingsOverlay, ArcOverlay,
  ClusterView, ScrubberStrip, SimSideRail) render under this Panel
  — they are not independently mountable (see ns docstring §Internal
  sub-components)."
  ([mount-point]      (mount-machine-inspector! mount-point nil))
  ([mount-point opts] (render-panel! machine-inspector/Panel mount-point opts)))

(defn mount-routing!
  "Mount Causa's Routing tab in isolation at `mount-point`. Renders
  the registered-routes lens + simulate-URL surface."
  ([mount-point]      (mount-routing! mount-point nil))
  ([mount-point opts] (render-panel! routing/Panel mount-point opts)))

(defn mount-issues-ribbon!
  "Mount Causa's Issues tab in isolation at `mount-point`. Renders
  the focused-epoch issue feed per spec/021 §8 (rf2-jio48)."
  ([mount-point]      (mount-issues-ribbon! mount-point nil))
  ([mount-point opts] (render-panel! issues-ribbon/Panel mount-point opts)))

(defn mount-segment-inspector!
  "Mount the App-DB segment-inspector popup in isolation at
  `mount-point`. Self-gating — renders nil when no segment is open;
  short-circuits on `:rf.causa/segment-inspector-open?`."
  ([mount-point]      (mount-segment-inspector! mount-point nil))
  ([mount-point opts] (render-panel! segment-inspector/Popup mount-point opts)))

(defn mount-cancellation-cascade-side-panel!
  "Mount the cancellation-cascade side-panel in isolation at
  `mount-point`. Renders the destroy-waterfall when the focused
  machine had a cancellation-anchor in the trace window; renders
  nothing otherwise."
  ([mount-point]      (mount-cancellation-cascade-side-panel! mount-point nil))
  ([mount-point opts] (render-panel! cancellation-cascade/SidePanel mount-point opts)))

(defn mount-cancellation-cascade-popover!
  "Mount the cancellation-cascade popover overlay in isolation at
  `mount-point`. Self-gating — renders nil when
  `:rf.causa/cancellation-cascade-popover-open?` is false."
  ([mount-point]      (mount-cancellation-cascade-popover! mount-point nil))
  ([mount-point opts] (render-panel! cancellation-cascade/Popover mount-point opts)))

(rf/reg-view ManagedFxList
  "The managed-fx wire-boundary diff template's mountable wrapper —
  reads `:rf.causa/managed-fx-for-focused-event` and renders the
  records list. `managed-fx-template/records-list` is a pure fn over
  a records vector; this reg-view ties it to the focused cascade's
  managed-fx sub so consumers get the same content the Event tab
  embeds inline under the six-domino cascade view.

  Exposing this as a reg-view (rather than a plain fn) follows the
  Conventions.md panel-facade contract — every public mount target
  is a `reg-view` so the React-context tier resolves to the wrapping
  frame-provider per Spec 006 §706."
  []
  (let [records @(rf/subscribe [:rf.causa/managed-fx-for-focused-event])]
    (managed-fx/records-list records)))

(defn mount-managed-fx!
  "Mount the managed-fx wire-boundary diff list in isolation at
  `mount-point`. Renders one record-panel per managed-fx invocation
  inside the focused cascade's six-domino window. Empty when the
  focused cascade had no managed-fx records."
  ([mount-point]      (mount-managed-fx! mount-point nil))
  ([mount-point opts] (render-panel! ManagedFxList mount-point opts)))

;; ---- full-shell mount ---------------------------------------------------

(defn mount-shell!
  "Mount the full Causa 4-layer shell at `mount-point`. The master
  entry — composes every panel inside the shell's ribbon + event-list
  + tab-bar + detail-panel chrome.

  This is the same mount path `mount.cljs/open!` uses for the
  default in-app `[data-rf-causa-host]` mount; exposing it here lets
  hosts that own their own DOM (Story, custom dev surfaces) mount
  the shell at any element without going through Causa's auto-open
  preload."
  ([mount-point]      (mount-shell! mount-point nil))
  ([mount-point opts]
   (ensure-causa-handlers-installed!)
   (let [mode (get opts :mode :inline)
         tree [shell/shell-view {:mode mode}]]
     (substrate-adapter/render tree mount-point nil))))
