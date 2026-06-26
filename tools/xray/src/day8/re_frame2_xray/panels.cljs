(ns day8.re-frame2-xray.panels
  "Per-panel mount surface. **Internal-but-stable**, NOT a v1.0
  host-facing embed contract.

  Every Xray panel is independently mountable: a host can mount one
  panel in isolation without the surrounding 4-layer shell, without
  sibling panels, and without any shell-owned chrome state. This
  surface is the load-bearing seam the 4-layer shell composes through
  and that the test suite mounts panels through; it also lets Xray be
  embedded inside Story, inside the Scittle playground (option-c
  progressive disclosure), inside custom debugging configurations, and
  inside the docs / guide / examples surface.

  **Status: internal-but-stable, not a host-facing v1.0 embed
  contract.** The mount fns are stable (the shell + tests depend on
  them; hosts MAY use them) but carry NO v1.0 host-facing-contract
  guarantee — there is no per-panel props vocabulary beyond the single
  `:frame` opt (defaulting to `:rf/xray`). The v1.0 host-facing embed
  contract is the **full-shell** embed per
  `tools/xray/spec/008-Embedding-Contract.md` §Full-shell embed
  contract. This matches the status stated in
  `008-Embedding-Contract.md`, `tools/xray/spec/API.md`, and
  `007-UX-IA.md` §Mountable panel contract — one honest status across
  every reference.

  ## The surface

  Per `tools/xray/spec/008-Embedding-Contract.md` every panel exposes
  a mount fn:

      (mount-epoch-panel!      mount-point opts) → unmount-fn
      (mount-app-db-diff!      mount-point opts) → unmount-fn
      (mount-reactive-panel!   mount-point opts) → unmount-fn
      (mount-trace!            mount-point opts) → unmount-fn
      (mount-machine-inspector! mount-point opts) → unmount-fn
      (mount-routing!          mount-point opts) → unmount-fn
      (mount-resources!        mount-point opts) → unmount-fn

      ;; Spine surface — the L2 event list in isolation.
      ;; The SAME `shell/event-list` reg-view the full 4-layer shell
      ;; composes at L2; mounted standalone it is the compact,
      ;; clickable recent-events navigator. Clicking a row dispatches
      ;; `:rf.xray/focus-cascade`, which re-binds the spine sub
      ;; `:rf.xray/focus` — so any panel mounted alongside (App-db,
      ;; Epoch, …) re-renders against the chosen past epoch.
      (mount-event-spine! mount-point opts) → unmount-fn

      ;; Overlay / popup surfaces — same contract.
      (mount-segment-inspector! mount-point opts) → unmount-fn
      (mount-cancellation-cascade-side-panel! mount-point opts) → unmount-fn
      (mount-cancellation-cascade-popover!    mount-point opts) → unmount-fn

      ;; Inline content surface — managed-fx wire-boundary diff. The
      ;; canonical embed of the per-cascade managed-fx records list.
      (mount-managed-fx! mount-point opts) → unmount-fn

  Plus the master entry that mounts the full 4-layer shell:

      (mount-shell! mount-point opts) → unmount-fn

  ## Single-source panel enumeration

  The SET of mountable panels — the `mount-<panel>!` family above —
  is enumerated ONCE in `day8.re-frame2-xray.panel-enum/panel-enum`
  (the single source of truth). The `mount-*!` fns in this namespace,
  the Xray API spec's panel inventory (`007-UX-IA.md` +
  `008-Embedding-Contract.md`), and the api-manifest `:cljs-only` rows
  are all VALIDATED AGAINST that enum by the single-source guard
  (`panel_enum_guard_cljs_test.cljs`) — a drift between any projection
  and the enum goes RED in CI. Adding / removing / renaming a panel
  starts with a one-line edit to `panel-enum`; the guard then forces
  the facade fn below + the spec inventory to follow. See the
  `panel-enum` ns docstring for the contract.

  ## What every mount fn does

  1. Calls `(registry/register-xray-handlers!)` — idempotent, registers
     every panel's subs + events + fxs under `:rf.xray/*`.
  2. Calls `(rf/reg-frame :rf/xray {})` — idempotent, ensures the
     state-isolation frame exists.
  3. Wraps the panel's `Panel` (or equivalent) view in
     `[rf/frame-provider {:frame :rf/xray} [<Panel>]]` so descendant
     subscribes / dispatches re-anchor to `:rf/xray` regardless of
     the host's React-context.
  4. Delegates to `substrate-adapter/render` with the wrapped tree +
     the supplied mount-point. The substrate adapter is the host's
     (installed via `rf/init!`); the panels are substrate-agnostic
     pure hiccup.
  5. Returns the adapter's unmount fn so the host owns lifecycle.

  ## Why the aggregator pattern

  Each panel facade already follows the canonical shape — public
  `Panel` reg-view + `install!` (per `tools/xray/spec/Conventions.md`
  §Panel facade + leaf split). The mount-fns here are thin wrappers:
  they delegate the chrome (frame-provider, registry install, adapter
  render) to one place so every panel inherits the same contract by
  construction. Adding a new panel = add a new `mount-<panel>!` line.

  ## Per-panel inputs

  Every panel reads its data via subscribes — no sibling-render
  assumptions, no shell-owned local state. The subs (registered by
  the panel's own `install!`) compose against:

  | Panel | Reads | Writes (via dispatch) |
  |---|---|---|
  | **epoch-panel** | `:rf.xray/focus` · `:rf.xray/epoch-history` (via `panels.shared.focus-resolver`) | `:rf.xray.epoch/toggle-row-expand` · `:rf.xray.epoch/set-subs-filter-mode` |
  | **app-db (current-state inspector)** | `:rf.xray/app-db-state` (current-state section model over the observed frame's live app-db, sectioned by reserved `:rf/*` area) | `:rf.xray/open-segment-inspector` |
  | **reactive-panel** | `:rf.xray/reactive-data` (composite over focused cascade's `:trace-events`) | `:rf.xray/reactive-toggle-unchanged` |
  | **trace** | `:rf.xray/trace-feed` (epoch-scoped — projects the focused epoch's `:trace-events` into a flat list of rows, each with a stage column + colour-coded left edge, spec/023) | `:rf.xray/toggle-trace-row-expand` · `:rf.xray/open-in-editor` |
  | **machine-inspector** | `:rf.xray/machine-chart-data` · `:rf.xray/active-timers-for-focused-machine` · `:rf.xray/machine-scrubber-position` | scrubber events · `:rf.xray/focus-cascade` |
  | **routing** | `:rf.xray/registered-routes` · `:rf.xray/current-route-slice` · `:rf.xray/routing-tab-data` | route-simulation events |
  | **resources** | `:rf.xray/resources-tab-data` (composite over `:rf.xray/registered-resources` · `:rf.xray/resource-entries` · `:rf.xray/resource-work-ledger` · the route registry · the trace buffer) | (read-only — no dispatch; observing pins no resource) |
  | **segment-inspector** | `:rf.xray/segment-inspector-open?` · `:rf.xray/segment-inspector-value` | `:rf.xray/close-segment-inspector` |
  | **cancellation-cascade** (side-panel + popover) | `:rf.xray/cancellation-cascade-for-focused-machine` · `:rf.xray/cancellation-cascade-for-focused-event` · `:rf.xray/cancellation-cascade-popover-open?` · `:rf.xray/modal-positioning` | `:rf.xray/cancellation-cascade-close` |
  | **managed-fx** | `:rf.xray/managed-fx-for-focused-event` | `:rf.xray/focus-event` |

  No panel reads sibling-panel state directly. No panel assumes any
  particular frame-picker / tab-bar / event-list / spine-head value
  beyond what the spine sub `:rf.xray/focus` exposes — and `focus`
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

  ## Frame-provider opt — `:frame` defaults to `:rf/xray`

  The default `opts` map is `{:frame :rf/xray}` — Xray's own
  state-isolation frame. Hosts that embed a panel to observe a
  specific app frame pass `{:frame :my-app/cart}` per the embedding
  contract — the panel's subscribes still resolve to `:rf/xray` for
  Xray's own UI state, while the panel-internal frame-selection sub
  (`:rf.xray/observed-frame`) drives the data axis.

  See `tools/xray/spec/007-UX-IA.md` §Mountable panel contract and
  `tools/xray/spec/008-Embedding-Contract.md` for the full
  embedding contract."
  (:require [re-frame.core :as rf]
            [re-frame.substrate.adapter :as substrate-adapter]
            [day8.re-frame2-xray.mount :as mount]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.panels.app-db-diff :as app-db-diff]
            [day8.re-frame2-xray.panels.app-db-segment-inspector :as segment-inspector]
            [day8.re-frame2-xray.panels.cancellation-cascade :as cancellation-cascade]
            [day8.re-frame2-xray.panels.epoch-panel :as epoch-panel]
            [day8.re-frame2-xray.panels.machine-inspector :as machine-inspector]
            [day8.re-frame2-xray.panels.managed-fx-template :as managed-fx]
            [day8.re-frame2-xray.panels.routing :as routing]
            [day8.re-frame2-xray.panels.resources :as resources]
            [day8.re-frame2-xray.panels.trace :as trace]
            [day8.re-frame2-xray.panels.reactive-panel :as reactive-panel]
            [day8.re-frame2-xray.shell :as shell]))

;; ---- internal scaffolding -----------------------------------------------

(defn- ensure-xray-handlers-installed!
  "Idempotent — `register-xray-handlers!` carries its own sentinel so
  multiple panel mounts collapse to one registration pass.

  Routes through `mount/ensure-xray-frame!` so the frame is not just
  registered but ALSO seeded via the first-mount hook table
  — `::seed-trace-and-target-frame`, `::hydrate-filters`,
  `::hydrate-spine-filters`, `::hydrate-static-mode`,
  `::auto-open-watcher`. A direct `(rf/reg-frame :rf/xray {})` here
  would register the frame but skip the hook table, leaving Xray's
  trace-buffer slot empty + `:target-frame` pinned to
  `defaults/default-target-frame` regardless of what's already in the
  framework's per-frame rings and the host's epoch ring. That misalignment is the
  empty-Xray-on-Story-RHS class of bug — Story embeds a panel via
  `mount-<panel>!`, the panel renders against a frame the hooks never
  populated, and the user sees blank inputs even though the host has
  been dispatching events.

  `ensure-xray-frame!` is idempotent (sentinel-guarded hooks +
  reg-frame's surgical-update-on-re-register semantics per Spec 002
  §reg-frame) so multiple panel mounts collapse to one seed pass +
  zero re-registrations across shadow-cljs reloads."
  []
  (registry/register-xray-handlers!)
  (mount/ensure-xray-frame!))

(defn- render-panel!
  "Internal helper. Wraps `panel-view` in `[rf/frame-provider {:frame
  frame} [panel-view]]` and delegates to the substrate adapter's
  render fn. Returns the adapter's unmount fn so the caller can
  tear the mount down without going through this ns again.

  - `panel-view` — the panel's `reg-view`-registered Var (e.g.
    `epoch-panel/Panel`). Wrapped in a Reagent component-vector so
    React-context flows correctly per Spec 006 §706 (a plain `defn`
    invoked as a fn-call would skip the React-context tier and the
    panel's subscribes would route to `:rf/default`).
  - `mount-point` — a DOM element (or substrate-equivalent mount
    target).
  - `opts` — `{:frame <frame-id>}` minimum. Defaults to `:rf/xray`.
    The frame the `frame-provider` resolves to. Hosts embedding a
    panel to observe a specific app frame pass that frame id; the
    panel's own Xray state still lives on `:rf/xray` (the panel
    facade always opens with its own `frame-provider :rf/xray`
    when its body subscribes to `:rf.xray/*` data)."
  [panel-view mount-point opts]
  (ensure-xray-handlers-installed!)
  (let [frame (get opts :frame :rf/xray)
        tree  [rf/frame-provider {:frame frame}
               [panel-view]]]
    (substrate-adapter/render tree mount-point nil)))

;; ---- per-panel mount fns ------------------------------------------------
;;
;; All mount fns share the same shape — install handlers → wrap
;; panel view in frame-provider → delegate to substrate adapter. The
;; only per-panel axis is which `Panel` (or equivalent) view to render.
;; This keeps the surface uniform — adding a panel = adding a line
;; below; the chrome (registration, frame wiring, substrate delegation)
;; lives in `render-panel!` exactly once.
;;
;; The Epoch panel is the canonical "what happened in this epoch"
;; mount target.

(defn mount-epoch-panel!
  "Mount Xray's Epoch tab in isolation at `mount-point`.
  Renders the numbered cascade — the focused epoch's complete
  computational timeline (DISPATCH → COEFFECTS → HANDLER → FLOW →
  FX → SUBSCRIPTIONS → VIEWS) with conditional rendering per the
  trace stream."
  ([mount-point]      (mount-epoch-panel! mount-point nil))
  ([mount-point opts] (render-panel! epoch-panel/Panel mount-point opts)))

(defn mount-app-db-diff!
  "Mount Xray's App-DB tab in isolation at `mount-point`. Renders the
  sections-per-cluster structural diff for the focused cascade."
  ([mount-point]      (mount-app-db-diff! mount-point nil))
  ([mount-point opts] (render-panel! app-db-diff/Panel mount-point opts)))

(defn mount-reactive-panel!
  "Mount Xray's Reactive tab in isolation at `mount-point`.
  Renders the canonical sub-cascade + view-re-render visualisation
  per spec/021 §3."
  ([mount-point]      (mount-reactive-panel! mount-point nil))
  ([mount-point opts] (render-panel! reactive-panel/Panel mount-point opts)))

(defn mount-trace!
  "Mount Xray's Trace tab in isolation at `mount-point`. Renders the
  trace-buffer feed for the focused cascade."
  ([mount-point]      (mount-trace! mount-point nil))
  ([mount-point opts] (render-panel! trace/Panel mount-point opts)))

(defn mount-machine-inspector!
  "Mount Xray's Machines tab in isolation at `mount-point`. Renders
  the chart + arc/ring/cluster overlays for the focused machine.
  The auxiliary inspectors (AfterRingsOverlay, ArcOverlay,
  ClusterView, ScrubberStrip, SimSideRail) render under this Panel
  — they are not independently mountable (see ns docstring §Internal
  sub-components)."
  ([mount-point]      (mount-machine-inspector! mount-point nil))
  ([mount-point opts] (render-panel! machine-inspector/Panel mount-point opts)))

(defn mount-routing!
  "Mount Xray's Routing tab in isolation at `mount-point`. Renders
  the registered-routes lens + simulate-URL surface."
  ([mount-point]      (mount-routing! mount-point nil))
  ([mount-point opts] (render-panel! routing/Panel mount-point opts)))

(defn mount-resources!
  "Mount Xray's Resources tab in isolation at `mount-point` (Spec 016
  §Xray and AI tooling). Renders the static resource registry, the live
  per-frame instance + work-ledger tables, the route/resource graph, the
  lifecycle timeline, the invalidation graph, the cache-growth view, and
  the scope audit + lints. Read-only — observing pins no resource."
  ([mount-point]      (mount-resources! mount-point nil))
  ([mount-point opts] (render-panel! resources/Panel mount-point opts)))

(defn mount-event-spine!
  "Mount Xray's L2 event spine in isolation at `mount-point`.

  Renders the SAME `shell/event-list` reg-view the full 4-layer shell
  composes at L2 — the recent-events timeline (single-line rows,
  latest-on-bottom) that IS the canonical scrubber. This is NOT a
  parallel spine: it reuses the full-shell component verbatim, so the
  embedded spine inherits the row anatomy, the issue-row wash, the
  relative-time chips, virtualisation, filters, and —
  critically — the row-click → `:rf.xray/focus-cascade` write that
  drives the single-axis spine sub `:rf.xray/focus` (spec/018 §4 + §6).

  The contract for a host (Story) is: mount this spine ALONGSIDE a
  focus-keyed panel (`mount-epoch-panel!` / `mount-app-db-diff!` / …)
  in the SAME `:rf/xray` frame. Clicking a past event in the spine
  re-binds `:rf.xray/focus`; the sibling panel re-renders against the
  chosen epoch IN-PLACE — so a variant's event SEQUENCE is inspectable
  without the full-shell pop-out (which remains the deep-history
  escape hatch — spec/008 §Full-shell embed contract).

  Per spec/018 §4 the event list owns its own height via
  `:rf.xray/events-list-height-px`; the host caps the visible band
  through its mount-point CSS for the compact embed footprint (the
  contract is 'the host owns the container size' — spec/008 §Embed
  props inventory)."
  ([mount-point]      (mount-event-spine! mount-point nil))
  ([mount-point opts] (render-panel! shell/event-list mount-point opts)))

;; There is no dedicated Issues tab or aggregate panel. Issues surface
;; inline in the Epoch panel, via the L2 event-row pink-wash, and via
;; the always-on issues ribbon signal — the `:rf.xray/issues-ribbon`
;; composite (registered in `registry.cljs`) is the auto-open-on-error
;; signal source.

(defn mount-segment-inspector!
  "Mount the App-DB segment-inspector popup in isolation at
  `mount-point`. Self-gating — renders nil when no segment is open;
  short-circuits on `:rf.xray/segment-inspector-open?`."
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
  `:rf.xray/cancellation-cascade-popover-open?` is false."
  ([mount-point]      (mount-cancellation-cascade-popover! mount-point nil))
  ([mount-point opts] (render-panel! cancellation-cascade/Popover mount-point opts)))

(rf/reg-view ManagedFxList
  "The managed-fx wire-boundary diff template's mountable wrapper —
  reads `:rf.xray/managed-fx-for-focused-event` and renders the
  records list. `managed-fx-template/records-list` is a pure fn over
  a records vector; this reg-view ties it to the focused cascade's
  managed-fx sub so consumers get the per-cascade managed-fx content
  inline.

  Exposing this as a reg-view (rather than a plain fn) follows the
  Conventions.md panel-facade contract — every mount target
  is a `reg-view` so the React-context tier resolves to the wrapping
  frame-provider per Spec 006 §706."
  []
  (let [records @(rf/subscribe [:rf.xray/managed-fx-for-focused-event])]
    ;; Thread the reg-view-injected frame-aware dispatch so the panel's
    ;; context-menu / focus affordances land on the surrounding instance
    ;; frame, not a `{:frame :rf/xray}` literal.
    (managed-fx/records-list dispatch records)))

(defn mount-managed-fx!
  "Mount the managed-fx wire-boundary diff list in isolation at
  `mount-point`. Renders one record-panel per managed-fx invocation
  inside the focused cascade's managed-fx records. Empty when the
  focused cascade had no managed-fx records."
  ([mount-point]      (mount-managed-fx! mount-point nil))
  ([mount-point opts] (render-panel! ManagedFxList mount-point opts)))

;; ---- full-shell mount ---------------------------------------------------

(defn mount-shell!
  "Mount the full Xray 4-layer shell at `mount-point`. The master
  entry — composes every panel inside the shell's ribbon + event-list
  + tab-bar + detail-panel chrome.

  This is the same mount path `mount.cljs/open!` uses for the
  default in-app `[data-rf-xray-host]` mount; exposing it here lets
  hosts that own their own DOM (Story, custom dev surfaces) mount
  the shell at any element without going through Xray's auto-open
  preload."
  ([mount-point]      (mount-shell! mount-point nil))
  ([mount-point opts]
   (ensure-xray-handlers-installed!)
   (let [mode (get opts :mode :inline)
         tree [shell/shell-view {:mode mode}]]
     (substrate-adapter/render tree mount-point nil))))
