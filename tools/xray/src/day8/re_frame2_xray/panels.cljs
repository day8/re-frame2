(ns day8.re-frame2-xray.panels
  "Per-panel mount surface. **Internal-but-stable**, NOT a v1.0
  host-facing embed contract.

  Each facade enumerated by `panel-enum` can be mounted without the
  surrounding 4-layer shell or sibling panels. The shell and tests use
  this seam, and development hosts may use it for focused embeds. Graph
  and Frames are L4-only tabs and are not part of the standalone mount
  inventory.

  **Status: internal-but-stable, not a host-facing v1.0 embed
  contract.** The mount fns are stable (the shell + tests depend on
  them; hosts MAY use them) but carry NO v1.0 host-facing-contract
  guarantee — the props vocabulary is two keys wide and one of them is
  only two panels': `:frame` (every mount fn, defaulting to `:rf/xray`)
  and `:instance-id` (`mount-app-db-diff!` and `mount-managed-fx!` — see
  §`:instance-id` opt below). The v1.0 host-facing embed
  contract is the **full-shell** embed per
  `tools/xray/spec/008-Embedding-Contract.md` §Full-shell embed
  contract. This matches the status stated in
  `008-Embedding-Contract.md`, `tools/xray/spec/API.md`, and
  `007-UX-IA.md` §Mountable panel contract — one honest status across
  every reference.

  ## The surface

  The standalone facade inventory is:

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
      ;; `:rf.xray/focus-event`, which re-binds the spine sub
      ;; `:rf.xray/focus` — so any panel mounted alongside (App-db,
      ;; Epoch, …) re-renders against the chosen past epoch.
      (mount-event-spine! mount-point opts) → unmount-fn

      ;; Overlay / popup surfaces — same contract.
      (mount-segment-inspector! mount-point opts) → unmount-fn
      (mount-cancellation-cascade-side-panel! mount-point opts) → unmount-fn
      (mount-cancellation-cascade-popover!    mount-point opts) → unmount-fn

      ;; Inline content surface — managed-fx wire-boundary diff. The
      ;; canonical embed of the per-event-bundle managed-fx records list.
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
  2. Calls `mount/ensure-xray-frame!` for `opts :frame` — the SAME frame
     step 3 provides (rf2-hg3j) — so the state-isolation frame exists and
     its first-mount seed hooks have run.
  3. Wraps the panel's `Panel` (or equivalent) view in a
     `rf/frame-provider` for `opts :frame` (default
     `shell/default-frame-id`) so descendant subscribes and dispatches do
     not inherit the host's ambient React context accidentally.
  4. Delegates to `rf.substrate.adapter/render` with the wrapped tree +
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
  | **reactive-panel** | `:rf.xray/reactive-data` (composite over focused event-bundle's `:trace-events`) | `:rf.xray/reactive-toggle-unchanged` |
  | **trace** | `:rf.xray/trace-feed` (epoch-scoped — projects the focused epoch's `:trace-events` into a flat list of rows, each with a stage column + colour-coded left edge, spec/023) | `:rf.xray/toggle-trace-row-expand` · `:rf.xray/open-in-editor` |
  | **machine-inspector** | `:rf.xray/machine-chart-data` · `:rf.xray/active-timers-for-focused-machine` · `:rf.xray/machine-scrubber-position` | scrubber events · `:rf.xray/focus-event` |
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

  ## `:instance-id` opt — `mount-app-db-diff!` and `mount-managed-fx!`

  Two mounts under two DIFFERENT `:frame`s are already told apart:
  rf2-d2aj keys the edn-inspector's per-mount store by `[frame-id
  mount-id]`. Two mounts sharing ONE `:frame` are not, and nothing
  inside the panel can separate them — its view is a Fresco boundary,
  a React function component with no per-instance storage its body may
  use. So the caller names them, and `:instance-id` is how a STANDALONE
  mount does it:

      (mount-app-db-diff! left-el  {:instance-id \"left\"})
      (mount-app-db-diff! right-el {:instance-id \"right\"})

  rf2-t3fz gave `app-db-diff/Panel` the prop; this opt (rf2-2n8q) is the
  door for a caller that mounts rather than renders, which passes opts and
  never props. Omit it and every id is byte-for-byte what it was.

  rf2-5ykm — `mount-managed-fx!` takes the SAME key for the same reason,
  and it arrived as a REGRESSION rather than a gap: that panel's mount was
  an `rf/reg-view` until rf2-fcy5, and the Reagent head minted a per-mount
  identity the boundary cannot. Two managed-fx lists of one focused event
  in one frame therefore shared one ResizeObserver and one width slot, and
  detaching either released the survivor's — measured on a real
  two-container commit in
  `panels/managed_fx_mount_instance_id_dom_cljs_test`.

  It is SOME panels' opt rather than the surface's, and deliberately so:
  it is only meaningful where a panel's view accepts it, and handing a
  props map to a panel whose view takes none is an arity error rather
  than an ignored key. `render-panel!`'s `props` argument is the seam —
  see its docstring. The two panels that take it each own their own
  normaliser (`app-db-diff-state/instance-token`,
  `managed-fx-template/instance-token`) so a refusal names the caller's
  own panel.

  See `tools/xray/spec/007-UX-IA.md` §Mountable panel contract and
  `tools/xray/spec/008-Embedding-Contract.md` for the full
  embedding contract."
  (:require [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
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
  — `::seed-trace-and-target-frame`, `::reset-transient-filters`,
  `::seed-configured-filters`, `::hydrate-static-mode`,
  `::auto-open-watcher`. A direct `(rf/make-frame {:id :rf/xray})` here
  would register the frame but skip the hook table, leaving Xray's
  trace-buffer slot empty + `:target-frame` pinned to
  `defaults/default-target-frame` regardless of what's already in the
  framework's per-frame rings and the host's epoch ring. That misalignment is the
  empty-Xray-on-Story-RHS class of bug — Story embeds a panel via
  `mount-<panel>!`, the panel renders against a frame the hooks never
  populated, and the user sees blank inputs even though the host has
  been dispatching events.

  `ensure-xray-frame!` is idempotent, so multiple panel mounts and
  shadow-cljs reloads collapse to one seed pass.

  `frame-id` names the Xray-OWN frame to seat — the shell's own app-db,
  not the inspected host target. It defaults to `shell/default-frame-id`,
  which is what every per-panel mount takes; `mount-shell!` passes the
  own frame its caller asked for (rf2-lffg), so a second embed gets its
  own seeded frame rather than sharing the first's. The hook table's
  run-once guard is keyed per `frame-id`, so each instance frame gets
  exactly one seed pass."
  ([] (ensure-xray-handlers-installed! shell/default-frame-id))
  ([frame-id]
   (registry/register-xray-handlers!)
   (mount/ensure-xray-frame! frame-id)))

(defn- render-panel!
  "Internal helper. Wraps `panel-view` in `[rf/frame-provider {:frame
  frame} [panel-view]]` and delegates to the substrate adapter's
  render fn. Returns the adapter's unmount fn so the caller can
  tear the mount down without going through this ns again.

  - `panel-view` — the view (or `*-bridge` callable) this panel is
    mounted through, e.g. `epoch-panel/Panel-bridge`. Wrapped in a
    component-VECTOR rather than CALLED, and the reason is specific to
    this seam: THIS FN RUNS OUTSIDE ANY REACT RENDER, so calling
    `panel-view` here would evaluate its body with no in-flight
    component for the React-context tier to read. The ambient frame then
    resolves to nil and a `subscribe` / `dispatch` RAISES
    `:rf.error/no-frame-context`. There is no `:rf/default` fallback —
    the runtime never synthesises one (Spec 006 §Plain-fn footgun;
    `re-frame.views.provider/current-frame`).

    THAT IS A FACT ABOUT THE MOUNT SEAM, NOT A GENERAL RULE ABOUT
    CALLING VIEWS, and this docstring used to read as the general claim.
    Inside an already-provided tree the direction REVERSES: the context
    is read off whatever component is in flight, so a plain helper
    CALLED from a `reg-view` / boundary body resolves through its
    caller's `:contextType` and is fine, while the same helper in HEAD
    position mints a `:contextType`-less component of its own and is
    what raises. Here the problem is the absent render, not the call.
  - `mount-point` — a DOM element (or substrate-equivalent mount
    target).
  - `opts` — `{:frame <frame-id>}` minimum, defaulting to
    `shell/default-frame-id`. Xray's OWN frame for this panel — the
    frame the `frame-provider` anchors, and therefore the frame the
    panel's `:rf.xray/*` subscribes and dispatches resolve to. NOT the
    inspected host target, which the frame-picker chooses and which
    lives in `:rf.xray/target-frame` inside that own frame's db
    (`008-Embedding-Contract.md` §Own frame vs target frame). The
    frame is SEATED on the way through — the caller is not asked to
    pre-create it, symmetric with `mount-shell!`.

    rf2-hg3j — this used to call `ensure-xray-handlers-installed!` at
    its ZERO arity, which always seats `shell/default-frame-id`, and
    then provide `opts :frame`. On an override those are two different
    frames and every descendant subscribe anchored at one nothing had
    seated or seeded. The docstring's old claim that a panel facade
    opens its own inner `frame-provider :rf/xray` was the reasoning
    that made that look safe, and it is false: no panel view opens a
    provider at all — each one's docstring says its isolation comes
    from the ENCLOSING provider, which is this one.
  - `props` — OPTIONAL (rf2-2n8q), and it is the PANEL's props map, not
    the mount opts. nil — every caller but `mount-app-db-diff!` and
    `mount-managed-fx!` (rf2-5ykm), and those two only when the caller
    named an instance — mounts
    `[panel-view]`, the element this fn has always built. A map mounts
    `[panel-view props]`. The caller decides, because only the caller
    knows whether its panel's view takes props at all: `[trace/Panel
    {…}]` is an arity error, not an ignored map, so this must NOT be
    filled in from `opts` here on every panel's behalf.

  rf2-2n8q — the 4-arity is an ADDITION and the couplings `mount-resources!`
  reserves this fn for are untouched: the frame-provider wrap and the
  `adapter/render` delegation are still written once, here, and are still
  one edit when the shell becomes a Fresco tree."
  ([panel-view mount-point opts]
   (render-panel! panel-view mount-point opts nil))
  ([panel-view mount-point opts props]
   ;; rf2-hg3j — resolve the frame FIRST, then seat that one. The seated
   ;; frame and the provided frame are now the same value by construction,
   ;; so an overriding caller cannot get a provider anchored at an unseated
   ;; frame. `shell/default-frame-id` IS `:rf/xray`, so the default path is
   ;; unchanged; naming it also keeps `defaults/default-frame-id` the single
   ;; permitted bare `:rf/xray` literal (008 §Parameterized shell frame-id).
   (let [frame (get opts :frame shell/default-frame-id)
         tree  [rf/frame-provider {:frame frame}
                (if props
                  [panel-view props]
                  [panel-view])]]
     (ensure-xray-handlers-installed! frame)
     (rf.substrate.adapter/render tree mount-point nil))))

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
  Renders the numbered event-bundle — the focused epoch's complete
  computational timeline (DISPATCH → COEFFECTS → HANDLER → FLOW →
  FX → SUBSCRIPTIONS → VIEWS) with conditional rendering per the
  trace stream."
  ([mount-point]      (mount-epoch-panel! mount-point nil))
  ;; rf2-k97c.3 — `Panel-bridge`, the name RULING 1's spelling has the
  ;; CALLER pass. Today it is a plain alias of the `reg-view` (the same
  ;; object, so this line delivers byte-for-byte the element it always
  ;; did); when this panel migrates it becomes the real bridge inside
  ;; `panels/epoch/view.cljs` and THIS FILE IS NOT TOUCHED AGAIN. Moving
  ;; every remaining mount to its bridge name in one pass is what lets the
  ;; panel migrations run in parallel instead of queueing on this file.
  ([mount-point opts] (render-panel! epoch-panel/Panel-bridge mount-point opts)))

(defn mount-app-db-diff!
  "Mount Xray's App-DB tab in isolation at `mount-point`. Renders the
  sections-per-cluster structural diff for the focused event-bundle.

  `opts :instance-id` — OPTIONAL (rf2-2n8q). A non-blank string or a
  keyword naming THIS mount, for the case where two standalone mounts
  share one `:frame`. It qualifies every id the sections compose — the
  edn-inspector's `:mount-id` AND the expansion/zoom `:site-id` — and
  both halves are the fix: the store's lifecycle key is `[frame-id
  mount-id]` while the measured-width slot is keyed by the bare
  `mount-id` inside the frame, so qualifying one and not the other
  leaves two panels writing one width slot. Left unnamed, two mounts in
  one frame share one store entry, one ResizeObserver and one width
  slot, and detaching either releases the survivor's state.

  OMIT IT when only one app-db panel is on screen in this frame, which
  is every call site in this tree today: the ids are then byte-for-byte
  what they were. `app-db-diff-state/instance-token` refuses, loudly,
  the shapes that could not be stable across renders.

  See `app-db-diff/Panel`'s own `:instance-id` note for what the prop
  means once it arrives, and the ns docstring §`:instance-id` opt for
  why this is one panel's key rather than the surface's."
  ([mount-point]      (mount-app-db-diff! mount-point nil))
  ;; rf2-k97c.3 — `Panel-bridge`, not `Panel`; see `mount-resources!` below
  ;; for the reasoning. rf2-2n8q — and the props map is what carries
  ;; `:instance-id` across that bridge; `Panel-bridge`'s 1-arity is the
  ;; door, its 0-arity is what an unnamed mount still takes.
  ([mount-point opts]
   (render-panel! app-db-diff/Panel-bridge mount-point opts
                  (when-let [id (:instance-id opts)]
                    {:instance-id id}))))

(defn mount-reactive-panel!
  "Mount Xray's Reactive tab in isolation at `mount-point`.
  Renders the canonical sub-cascade + view-re-render visualisation
  per spec/021 §3."
  ([mount-point]      (mount-reactive-panel! mount-point nil))
  ;; rf2-k97c.3 — `Panel-bridge`, not `Panel`; the same one-line caller
  ;; change `mount-resources!` carries below, for the same reason. The
  ;; view is now a Fresco boundary and `render-panel!` builds a Reagent
  ;; tree, which `defview`'s contract forbids mounting a boundary into.
  ;; `render-panel!` itself is untouched.
  ([mount-point opts] (render-panel! reactive-panel/Panel-bridge mount-point opts)))

(defn mount-trace!
  "Mount Xray's Trace tab in isolation at `mount-point`. Renders the
  trace-buffer feed for the focused event-bundle."
  ([mount-point]      (mount-trace! mount-point nil))
  ;; rf2-k97c.3 — `Panel-bridge`; see `mount-epoch-panel!` above for why
  ;; the mount moves to the bridge name BEFORE the panel migrates.
  ([mount-point opts] (render-panel! trace/Panel-bridge mount-point opts)))

(defn mount-machine-inspector!
  "Mount Xray's Machines tab in isolation at `mount-point`. Renders
  the chart + arc/ring/cluster overlays for the focused machine.
  The auxiliary inspectors (AfterRingsOverlay, ArcOverlay,
  ClusterView, ScrubberStrip, SimSideRail) render under this Panel
  — they are not independently mountable (see ns docstring §Internal
  sub-components)."
  ([mount-point]      (mount-machine-inspector! mount-point nil))
  ;; rf2-k97c.3 — `Panel-bridge`; see `mount-epoch-panel!` above for why
  ;; the mount moves to the bridge name BEFORE the panel migrates.
  ([mount-point opts] (render-panel! machine-inspector/Panel-bridge mount-point opts)))

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
  ;; rf2-k97c.3 — `Panel-bridge`, not `Panel`. The Resources panel's view
  ;; is now a Fresco boundary (a real React function component), and
  ;; `re-frame.fresco/defview`'s own contract is that a boundary is
  ;; mounted as `[head props]` inside a Fresco body or through
  ;; `as-component` from outside, NEVER as a hiccup render fn in a
  ;; Reagent tree — which is what `render-panel!` builds. The bridge is
  ;; Fresco's `as-component` door and is deleted with every other
  ;; `*-bridge` when the shell itself becomes a Fresco tree.
  ;;
  ;; `render-panel!` ITSELF is deliberately untouched: it is the single
  ;; chokepoint where the frame-provider and adapter/render couplings are
  ;; severed for the whole embedding contract, and that is one edit in
  ;; the final commit rather than N edits now.
  ([mount-point opts] (render-panel! resources/Panel-bridge mount-point opts)))

(defn mount-event-spine!
  "Mount Xray's L2 event spine in isolation at `mount-point`.

  Renders the SAME `shell/event-list` reg-view the full 4-layer shell
  composes at L2 — the recent-events timeline (single-line rows,
  latest-on-bottom) that IS the canonical scrubber. This is NOT a
  parallel spine: it reuses the full-shell component verbatim, so the
  embedded spine inherits the row anatomy, the issue-row wash, the
  relative-time chips, virtualisation, filters, and —
  critically — the row-click → `:rf.xray/focus-event` write that
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

(defn managed-fx-list-tree
  "The managed-fx list's WHOLE body, as a pure function of the two values
  [[ManagedFxList]] reads and the frame-bound dispatcher it captures.

  SPLIT OUT OF [[ManagedFxList]] BY rf2-fcy5, and the split is `defview`'s
  own documented extract-a-helper spelling rather than an invention: a
  boundary's body may only run inside a React render window, so
  `(ManagedFxList)` is no longer a callable that answers hiccup, while
  this fn is ordinary values → hiccup and stays worth driving from the
  fast node lane.

  It is also where the ONE composition this panel performs lives, and
  keeping it in ONE place is the point. `:rf.xray/managed-fx-for-focused-event`
  answers `{:dispatch-id … :frame … :records […]}` while
  `managed-fx-template/records-list` takes the RECORDS VECTOR. Handing it
  the whole map made `(for [rec records] …)` walk MAP ENTRIES, so
  `(name (:status rec))` got nil and threw before the panel could paint
  (rf2-90kv) — the rf2-qhoj \"panel that never appears\" shape, since no
  error boundary sits above this render path. `managed_fx_subs_cljs_test`
  grades exactly this fn against a seeded two-record cascade; before the
  migration it drove the `reg-view` body through `((rf/view id))`, which a
  boundary has no analogue for.

  `expanded` is the per-section disclosure override map, threaded down as
  plain data: `records-list` / `record-panel` are plain fns, and per Spec
  006 §Plain-fn footgun an ambient `subscribe` inside one cannot resolve
  the surrounding frame, so the read belongs at the boundary and the value
  travels as an argument (the same shape `views/edn-inspector` uses for
  its own expansion slot).

  `instance-id` (rf2-5ykm) is the optional per-mount name from
  [[mount-managed-fx!]]'s opts, threaded down to the four inspector sites
  so two lists in one frame compose disjoint widget identities. nil — the
  3-arity, and every single-mount call site — composes what it always did.

  PURE: every helper it calls is a plain fn of its arguments."
  ([focused expanded dispatch]
   (managed-fx-list-tree focused expanded dispatch nil))
  ([focused expanded dispatch instance-id]
   (managed-fx/records-list dispatch expanded instance-id (:records focused))))

(rf.fresco/defview ManagedFxList
  "The managed-fx wire-boundary diff template's mountable wrapper — a
  FRESCO BOUNDARY (rf2-fcy5), not an `rf/reg-view`. Reads the focused
  event-bundle's managed-fx composite plus the per-section disclosure
  slot and hands their values, with a frame-bound dispatcher, to
  [[managed-fx-list-tree]].

  WHY IT MOVED. `managed_fx_template` is a pure hiccup folder whose only
  non-native heads are the vectors `edn-widget/inspect` returns —
  `[ei/edn-inspector …]`, a `reg-view` head, which Fresco's codec grades
  `:invalid` exactly as it grades a plain `defn`. The replacement head
  `edn/inspect-view` emits the `edn-inspector-view` boundary instead, and
  it could not be adopted while this mount was a `reg-view`, because a
  Fresco boundary in a Reagent head position is the mirror failure. So the
  order was forced and is the whole content of this change: migrate the
  mount, then adopt the head.

  The READS are `rf.fresco/sub`, plain calls the shipped collector records
  an edge for — no deref, no reaction owned by the installed adapter, and
  a re-wire that NOTIFIES when the substrate disposes the underlying
  derived value.

  The DISPATCHER is `(:dispatch (rf/capture-frame))` — core's own door,
  which answers the boundary's DECLARED frame inside a body. It replaces
  the name `reg-view` used to inject lexically: `defview` binds NO name
  inside your body, so the bare `dispatch` this body used to close over
  would be a LOUD compile error, which is the good failure. It still lands
  the panel's context-menu / focus / disclosure affordances on the
  surrounding instance frame rather than on a `{:frame :rf/xray}` literal.

  The argument is the ordinary one-props-map vector every `defview` takes.

  ## `:instance-id` — OPTIONAL, and it names ONE LIVE MOUNT (rf2-5ykm)

  This panel reads no DATA from props: everything it renders comes from the
  two subs below and from nothing else. The one prop it takes is an
  IDENTITY, and it exists because rf2-fcy5 dropped one. Every node-key the
  template composes names a logical SURFACE and is deliberately stable, so
  two lists of the same focused event present the same ones; rf2-d2aj
  qualified the edn-inspector's per-mount store by the FRAME, which
  separates two mounts under two `frame-provider`s and cannot separate two
  under one. Left unnamed the two share one store entry, one
  ResizeObserver, one projection cache and one width slot, and detaching
  either releases the survivor's.

  So the distinction is the caller's to make, and this prop is how:

      [ManagedFxList-bridge {:instance-id \"left\"}]
      (mount-managed-fx! el {:instance-id \"right\"})

  A non-blank string or a keyword, whose NAMESPACE is part of the name.
  `managed-fx-template/instance-token` refuses, loudly, the shapes that
  could not be stable across renders. OMIT IT when only one managed-fx
  list renders in this frame, which is every call site in this tree today:
  the ids are then byte-for-byte what they were.

  BOTH DOORS ANSWER THE SAME. Mounted from a Fresco body the prop arrives
  as written; mounted through [[ManagedFxList-bridge]] it arrives already
  tokenised, because `[:>]` would otherwise convert a keyword with
  `cljs.core/name` and drop its namespace — see that fn's own note."
  [{:keys [instance-id]}]
  (managed-fx-list-tree (rf.fresco/sub [:rf.xray/managed-fx-for-focused-event])
                        (rf.fresco/sub [:rf.xray/managed-fx-expanded-sections])
                        (:dispatch (rf/capture-frame))
                        instance-id))

(def ^:private ManagedFxList-component
  "The React component `ManagedFxList` presents as, for a non-Fresco
  parent. Declared once at top level beside the view, as
  `rf.fresco/as-component`'s contract requires — deriving it per render
  would mint a new component type every time and remount the panel on each
  parent render."
  (rf.fresco/as-component ManagedFxList))

(defn ManagedFxList-bridge
  "The callable a Reagent parent mounts this panel through. Returns
  Reagent-shaped hiccup interoping to the React component above; the
  enclosing `rf/frame-provider` [[render-panel!]] writes is what puts the
  frame in React context for it.

  PUBLIC, unlike `static.routes.panel`'s equivalent, and the difference is
  a real one rather than a slip: this panel carries a standalone
  `mount-managed-fx!` facade, and [[render-panel!]] takes the view to
  mount as an ARGUMENT, so the embedding contract needs a name it can
  pass. `panels/resources`'s bridge is public for the same reason.

  THIS IS SCAFFOLDING WITH A DEFINED END. When `render-panel!` itself
  builds a Fresco tree, it takes `ManagedFxList` directly, `[:>]` goes,
  and both defs here are deleted with every other `*-bridge`.

  rf2-5ykm — the 1-arity is how a caller names one of two lists sharing a
  frame; the 0-arity stays because that is what an unnamed standalone mount
  takes (`[panel-view]`, one list per frame, no instance to name).

  ## The prop is TOKENISED HERE, before the crossing

  `[:>]` converts each prop VALUE before React sees it, and Reagent's
  `convert-prop-value` converts a named value with `cljs.core/name` — which
  DROPS THE NAMESPACE. Passed through raw, `:left/list` and `:right/list`
  would both arrive at the boundary as `\"list\"`, restoring the very
  collision this opt repairs. `app-db-diff/Panel-bridge` carries the full
  account (rf2-4bsq); the remedy is the same one — run the panel's own
  normaliser so a STRING crosses, which Reagent preserves intact, and a
  refused shape throws naming the CALLER's value rather than whatever the
  crossing had turned it into. The boundary's own call on the far side is
  then a no-op, because the fn is idempotent on its own output.

  A blank string tokenises to nil and so mounts with no props, exactly as
  naming no instance does."
  ([] (ManagedFxList-bridge nil))
  ([props]
   [:> ManagedFxList-component
    (if-let [instance-id (managed-fx/instance-token (:instance-id props))]
      {:instance-id instance-id}
      {})]))

(defn mount-managed-fx!
  "Mount the managed-fx wire-boundary diff list in isolation at
  `mount-point`. Renders one record-panel per managed-fx invocation
  inside the focused event-bundle's managed-fx records. Empty when the
  focused event-bundle had no managed-fx records.

  `opts :instance-id` — OPTIONAL (rf2-5ykm). A non-blank string or a
  keyword naming THIS mount, for the case where two standalone managed-fx
  lists share one `:frame`. It qualifies every inspector node-key the
  template composes, and that one string is BOTH the edn-inspector's
  `:mount-id` and its `:panel-id`, so one qualifier separates the widget's
  lifecycle key, its measured-width slot and its expansion/zoom identity
  together. Left unnamed, two mounts in one frame share one store entry and
  one ResizeObserver, the second is never observed at all, and detaching
  either releases the survivor's state.

  RECORD IDENTITY AND DISCLOSURE ARE DELIBERATELY UNQUALIFIED. The
  disclosure slot is keyed by `record-key` in the frame's app-db, so two
  lists of the same records open and close together — the same records, the
  same sections, one operator decision.

  OMIT IT when only one managed-fx list is on screen in this frame, which
  is every call site in this tree today: the ids are then byte-for-byte
  what they were. `managed-fx-template/instance-token` refuses, loudly, the
  shapes that could not be stable across renders."
  ([mount-point]      (mount-managed-fx! mount-point nil))
  ;; rf2-fcy5 — `ManagedFxList-bridge`, not `ManagedFxList`, for the reason
  ;; `mount-resources!` records above: the view is now a Fresco boundary
  ;; and `render-panel!` builds a Reagent tree, which `defview`'s contract
  ;; forbids mounting a boundary into. `render-panel!` itself is untouched.
  ;; rf2-5ykm — and the props map is what carries `:instance-id` across that
  ;; bridge; the bridge's 1-arity is the door, its 0-arity is what an
  ;; unnamed mount still takes.
  ([mount-point opts]
   (render-panel! ManagedFxList-bridge mount-point opts
                  (when-let [id (:instance-id opts)]
                    {:instance-id id}))))

;; ---- full-shell mount ---------------------------------------------------

(defn mount-shell!
  "Mount the full Xray 4-layer shell at `mount-point`. The master
  entry — composes every panel inside the shell's ribbon + event-list
  + tab-bar + detail-panel chrome.

  This is the same mount path `mount.cljs/open!` uses for the
  default in-app `[data-rf-xray-host]` mount; exposing it here lets
  hosts that own their own DOM (Story, custom dev surfaces) mount
  the shell at any element without going through Xray's auto-open
  preload.

  `opts` carries the two props `008-Embedding-Contract.md` §Embed props
  inventory publishes:

  - `:mode` — `:inline` (default) / `:overlay`, the shell's chrome mode.
  - `:frame` — the shell's OWN frame, defaulting to
    `shell/default-frame-id`. Distinct from the inspected host target,
    which the frame-picker chooses and which lives in
    `:rf.xray/target-frame` inside the own frame's db. It is forwarded to
    `shell-view` as its `:frame-id` opt (rf2-lnluk's de-singleton axis)
    and the frame is seated on the way through, so two embeds given
    distinct `:frame`s hold independent tab / mode / focus state instead
    of colliding on one app-db (rf2-lffg).

  Unlike the per-panel mounts, no outer `frame-provider` is added here:
  `shell-view` opens its own around `frame-id`."
  ([mount-point]      (mount-shell! mount-point nil))
  ([mount-point opts]
   (let [frame-id (get opts :frame shell/default-frame-id)
         mode     (get opts :mode :inline)]
     (ensure-xray-handlers-installed! frame-id)
     (rf.substrate.adapter/render [shell/shell-view {:mode     mode
                                                     :frame-id frame-id}]
                                  mount-point
                                  nil))))
