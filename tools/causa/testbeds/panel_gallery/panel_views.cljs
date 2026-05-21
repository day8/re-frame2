(ns panel-gallery.panel-views
  "Registered re-frame views referenced by Story variant `:component`
  slots in the Causa panel gallery (rf2-sszlr — rebuilt for the new
  7-tab Causa shape).

  Story canvas resolves `:component` to a registered re-frame view
  via `(rf/view <id>)` (per
  `tools/story/src/re_frame/story/ui/canvas.cljs`). The variant
  frame-provider has already wrapped the rendered tree with
  `[frame-provider {:frame variant-id}]`, so any subscribe inside the
  panel resolves to the variant frame's app-db.

  Each gallery component is a thin wrapper around either a single
  Causa panel's root view (one per L4 tab) or the full Causa shell —
  it absorbs the Story-supplied args map and renders the embed inside
  a card that sets a fixed-ish height so the variants-grid layout
  stays visually uniform.

  ## The 8 L4 tabs

  Per spec/018-Event-Spine.md §5 the chrome surfaces eight tabs whose
  bodies are existing per-panel Panel views:

    - **Event**           → `event-detail/Panel`
    - **App-db**          → `app-db-diff/Panel`
    - **Reactive**        → `reactive-panel/Panel`
    - **Trace**           → `trace/Panel`
    - **Machines**        → `machine-inspector/Panel`
    - **Routing**         → `routing/Panel` (rf2-nrbs9)
    - **Issues**          → `issues-ribbon/Panel`

  ## The chrome (rf2-xy4yb / spec/018)

  `:panel-gallery.chrome/Shell` mounts the full 4-layer chrome
  (`shell/shell-view`). The variant frame-provider is the Story
  canvas's; the shell internally also wraps itself in
  `[frame-provider {:frame :rf/causa}]` — so the shell's reads land
  on `:rf/causa` regardless of the outer variant frame. The variant's
  `:events` seed `:rf/causa` directly via `{:frame :rf/causa}`
  dispatches.

  ## Facade-mount discipline (rf2-043uz)

  Each gallery wrapper mounts the panel through its `reg-view`-
  registered Panel facade. Because facades are `reg-view`-registered,
  their `render-fn` is wrapped with `:contextType frame-context` by
  `re-frame.views/reg-view*`. The Reagent component-vector form
  `[event-detail/Panel]` is therefore safe here — React resolves the
  wrapped class's `:contextType`, the facade body reads
  `current-frame` correctly, and inside the facade body the per-
  panel discipline (function-call for plain-fn leaves, vector for
  reg-view leaves) takes over."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.app-db-diff :as app-db-diff]
            [day8.re-frame2-causa.panels.event-detail :as event-detail]
            [day8.re-frame2-causa.panels.issues-ribbon :as issues-ribbon]
            [day8.re-frame2-causa.panels.machine-inspector :as machine-inspector]
            [day8.re-frame2-causa.panels.routing :as routing]
            [day8.re-frame2-causa.panels.trace :as trace]
            [day8.re-frame2-causa.panels.reactive-panel :as reactive-panel]
            [day8.re-frame2-causa.shell :as shell]
            [day8.re-frame2-causa.theme.tokens :refer [tokens]]))

(def ^:private card-style
  "Card chrome around an embedded panel — gives the variants-grid a
  consistent visual shell with a labelled border so a reviewer can
  spot the panel boundary.

  `:position :relative` (rf2-om6fa) — establishes the positioning
  context for `:modal-positioning :absolute` modal backdrops mounted
  inside the chrome shell. The shell's `:inline` mode already sets
  its outer `<div>` to `position: relative`, but pinning it on the
  card too keeps containment robust against future tweaks to the
  shell's outer styling."
  {:position        "relative"
   :height          "520px"
   :width           "100%"
   :display         "flex"
   :flex-direction  "column"
   :background      (:bg-1 tokens)
   :border          (str "1px solid " (:border-default tokens))
   :border-radius   "6px"
   :overflow        "hidden"
   :font-family     "system-ui, sans-serif"})

(def ^:private chrome-card-style
  "Card chrome for the full-shell variants — taller to give the four
  layers + detail content room to breathe."
  (assoc card-style :height "640px"))

;; ---- per-tab wrappers ----------------------------------------------------

(defn- event-tab-panel
  "Embedded mount of the Event tab body — the event-detail panel."
  [_args]
  [:div {:style       card-style
         :data-testid "panel-gallery-event-card"}
   [event-detail/Panel]])

(defn- app-db-tab-panel
  "Embedded mount of the App-db tab body — the app-db-diff panel."
  [_args]
  [:div {:style       card-style
         :data-testid "panel-gallery-app-db-card"}
   [app-db-diff/Panel]])

(defn- reactive-tab-panel
  "Embedded mount of the Reactive tab body — the reactive panel
  (rf2-wyvf2 · spec/021 §3 · renamed from Views per §11.5)."
  [_args]
  [:div {:style       card-style
         :data-testid "panel-gallery-reactive-card"}
   [reactive-panel/Panel]])

(defn- trace-tab-panel
  "Embedded mount of the Trace tab body — the trace panel."
  [_args]
  [:div {:style       card-style
         :data-testid "panel-gallery-trace-card"}
   [trace/Panel]])

(defn- machines-tab-panel
  "Embedded mount of the Machines tab body — the machine-inspector
  panel (rf2-2tkza Phase 1 + rf2-v869p Phase 2)."
  [_args]
  [:div {:style       card-style
         :data-testid "panel-gallery-machines-card"}
   [machine-inspector/Panel]])

(defn- routing-tab-panel
  "Embedded mount of the Routing tab body — the routing panel
  (rf2-nrbs9; spec/016 §Routing tab + spec/018 §5.6)."
  [_args]
  [:div {:style       card-style
         :data-testid "panel-gallery-routing-card"}
   [routing/Panel]])

(defn- issues-tab-panel
  "Embedded mount of the Issues tab body — the issues-ribbon panel."
  [_args]
  [:div {:style       card-style
         :data-testid "panel-gallery-issues-card"}
   [issues-ribbon/Panel]])

;; ---- full chrome wrapper -------------------------------------------------

(defn- chrome-shell
  "Embedded mount of the full Causa 4-layer chrome (`shell/shell-view`)
  per spec/018-Event-Spine.md §2. The shell sits inside its own
  `[frame-provider {:frame :rf/causa}]` (per shell.cljs); variant
  seed events therefore dispatch with `{:frame :rf/causa}` so the
  reads land where the shell reads.

  ## `:modal-positioning :absolute` (rf2-om6fa)

  Story workspaces mount multiple chrome cells side-by-side. With
  the production default `:fixed` positioning every cell's modal
  backdrop would paint over the entire viewport at max-int z-index
  (`position: fixed; inset: 0`), and a workspace of N cells would
  stack N full-viewport backdrops over the Story shell — popup
  kills window. `:absolute` confines each cell's modals to the
  cell's positioning context (the card's `:position :relative`
  established by `chrome-card-style` / `card-style`) with a sane
  in-cell z-index, so per-cell modals can be opened, dismissed, and
  inspected without leaking into the Story chrome."
  [_args]
  [:div {:style       chrome-card-style
         :data-testid "panel-gallery-chrome-card"}
   [shell/shell-view {:mode :inline
                      :modal-positioning :absolute}]])

;; ---- registration --------------------------------------------------------

(defn register!
  "Register every gallery view-id referenced by a variant `:component`.
  Uses `reg-view*` (the runtime-registration surface, per Spec 004)
  because the gallery view-ids are explicit panel-keyed keywords
  rather than auto-derived from a defn symbol — we want
  `:panel-gallery.event/Panel` not the autogen
  `:panel-gallery.panel-views/event-tab-panel`. Idempotent —
  re-registering the same id replaces the handler; subsequent calls
  are silent at the registry."
  []
  (rf/reg-view* :panel-gallery.event/Panel    event-tab-panel)
  (rf/reg-view* :panel-gallery.app-db/Panel   app-db-tab-panel)
  (rf/reg-view* :panel-gallery.reactive/Panel reactive-tab-panel)
  (rf/reg-view* :panel-gallery.trace/Panel    trace-tab-panel)
  (rf/reg-view* :panel-gallery.machines/Panel machines-tab-panel)
  (rf/reg-view* :panel-gallery.routing/Panel  routing-tab-panel)
  (rf/reg-view* :panel-gallery.issues/Panel   issues-tab-panel)
  (rf/reg-view* :panel-gallery.chrome/Shell   chrome-shell)
  nil)
