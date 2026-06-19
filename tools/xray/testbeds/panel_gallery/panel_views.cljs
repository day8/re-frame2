(ns panel-gallery.panel-views
  "Registered re-frame views referenced by Story variant `:component`
  slots in the Xray panel gallery (rf2-sszlr — rebuilt for the
  6-tab Xray shape; rf2-5gl5r retired the Event/Handler tab in
  favour of the Epoch panel; rf2-gbz39 folded Issues inline).

  Story canvas resolves `:component` to a registered re-frame view
  via `(rf/view <id>)` (per
  `tools/story/src/re_frame/story/ui/canvas.cljs`). The variant
  frame-provider has already wrapped the rendered tree with
  `[frame-provider-existing {:frame variant-id}]`, so any subscribe inside the
  panel resolves to the variant frame's app-db.

  Each gallery component is a thin wrapper around either a single
  Xray panel's root view (one per L4 tab) or the full Xray shell —
  it absorbs the Story-supplied args map and renders the embed inside
  a card that sets a fixed-ish height so the variants-grid layout
  stays visually uniform.

  ## The L4 tabs

  Per spec/018-Event-Spine.md §5 the chrome surfaces six tabs whose
  bodies are existing per-panel Panel views:

    - **Epoch**           → `epoch-panel/Panel` (rf2-sc3r1; supersedes
                            the retired Event/Handler panel — rf2-5gl5r)
    - **App-db**          → `app-db-diff/Panel`
    - **Reactive**        → `reactive-panel/Panel`
    - **Trace**           → `trace/Panel`
    - **Machines**        → `machine-inspector/Panel`
    - **Routing**         → `routing/Panel` (rf2-nrbs9)

  (rf2-gbz39 — the Issues tab + its `issues-ribbon/Panel` were removed
  per Mike's Option (c) ruling; issues surface inline in the Epoch
  panel + the L2 event-row pink-wash + the always-on issues ribbon
  signal, so there is no dedicated Issues panel to gallery.)

  ## The chrome (rf2-xy4yb / spec/018)

  `:panel-gallery.chrome/Shell` mounts the full 4-layer chrome
  (`shell/shell-view`). Per rf2-1w07r the chrome cell threads the
  Story per-variant frame into `[shell/shell-view {:frame-id …}]`, so
  the shell's app-db lives in the VARIANT frame — N chrome cells in one
  workspace are fully isolated and each variant's `:events` seed THAT
  frame directly. (Pre rf2-1w07r the shell hardcoded a scope-only
  `[frame-provider-existing {:frame :rf/xray}]`, so every cell's reads
  collided on the one global `:rf/xray` app-db regardless of the
  variant frame.)

  ## Facade-mount discipline (rf2-043uz)

  Each gallery wrapper mounts the panel through its `reg-view`-
  registered Panel facade. Because facades are `reg-view`-registered,
  their `render-fn` is wrapped with `:contextType frame-context` by
  `re-frame.views/reg-view*`. The Reagent component-vector form
  `[epoch-panel/Panel]` is therefore safe here — React resolves the
  wrapped class's `:contextType`, the facade body reads
  `current-frame-id` correctly, and inside the facade body the per-
  panel discipline (function-call for plain-fn leaves, vector for
  reg-view leaves) takes over."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.panels.app-db-diff :as app-db-diff]
            [day8.re-frame2-xray.panels.epoch-panel :as epoch-panel]
            [day8.re-frame2-xray.panels.machine-inspector :as machine-inspector]
            [day8.re-frame2-xray.panels.routing :as routing]
            [day8.re-frame2-xray.panels.trace :as trace]
            [day8.re-frame2-xray.panels.reactive-panel :as reactive-panel]
            [day8.re-frame2-xray.shell :as shell]
            [day8.re-frame2-xray.theme.tokens :refer [tokens]]
            [day8.re-frame2-xray.views.edn-inspector :as edn-inspector]))

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
;;
;; (rf2-5gl5r — `event-tab-panel` removed alongside the retired
;; Event/Handler panel; the Epoch wrapper below is the canonical
;; "what happened in this epoch" gallery surface.)

(defn- app-db-tab-panel
  "Embedded mount of the App-db tab body — the app-db-diff panel."
  [_args]
  [:div {:style       card-style
         :data-testid "panel-gallery-app-db-card"}
   [app-db-diff/Panel]])

(defn- epoch-tab-panel
  "Embedded mount of the Epoch panel — `panels.epoch.view/Panel` via the
  orchestrator's re-export (rf2-mzcwt). The panel renders the focused
  epoch as a numbered vertical cascade per spec/021 §9.1; its composite
  sub `:rf.xray/epoch-pipeline` reads `:rf.xray/focus` +
  `:rf.xray/epoch-history` from the variant frame."
  [_args]
  [:div {:style       card-style
         :data-testid "panel-gallery-epoch-card"}
   [epoch-panel/Panel]])

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

;; (rf2-gbz39 — `issues-tab-panel` removed alongside the Issues tab.
;; Option (c): issues surface inline in the Epoch panel + the L2
;; event-row pink-wash + the always-on issues ribbon signal, so there
;; is no standalone Issues panel to embed here.)

;; ---- widget gallery (rf2-hp4ow) -----------------------------------------
;;
;; The edn-inspector is a widget, not a tab — but the panel-views
;; convention is the cleanest mounting surface for it too. The
;; variant frame's fixture slot is read by the gallery ns's
;; `:panel-gallery.edn-inspector/fixture` sub (declared in
;; `gallery_edn_inspector.cljs`); we read it here via a string keyword
;; rather than a namespace alias so this ns has no compile-time
;; dependency on the gallery namespace (the reverse direction —
;; gallery → panel-views — is the dependency edge we keep).

(defn- edn-inspector-tab-panel
  "Embedded mount of the edn-inspector widget. Reads the variant
  frame's `:panel-gallery.edn-inspector/fixture` slot, destructures
  `{:value :before :opts}`, and renders the widget with diff mode
  engaged when `:before` is present."
  [_args]
  (let [fixture @(rf/subscribe [:panel-gallery.edn-inspector/fixture])
        {:keys [value before opts]} fixture
        merged-opts (cond-> (or opts {})
                      (some? before) (assoc :before before))]
    [:div {:style       card-style
           :data-testid "panel-gallery-edn-inspector-card"}
     (if (some? fixture)
       [edn-inspector/edn-inspector value merged-opts]
       [:div {:style {:padding "16px"
                      :color (:text-tertiary tokens)
                      :font-family "system-ui, sans-serif"
                      :font-size "12px"}}
        "No fixture seeded into "
        [:code ":panel-gallery.edn-inspector/fixture"]
        " — variant `:events` did not run, or this view is mounted
        outside a variant frame."])]))

;; ---- full chrome wrapper -------------------------------------------------

(defn- chrome-shell
  "Embedded mount of the full Xray 4-layer chrome (`shell/shell-view`)
  per spec/018-Event-Spine.md §2.

  ## Per-cell frame isolation (rf2-1w07r)

  `chrome-shell` is `reg-view*`-registered (see `register!`), so its
  render-fn is `:contextType frame-context`-aware and `(rf/current-
  frame)` resolves to the Story per-variant frame the canvas wrapped
  the cell in. We thread that frame into `[shell/shell-view {:frame-id
  …}]` so the shell's app-db (focused epoch, selected tab, theme, modal
  open-state) lives in the VARIANT frame — each chrome cell in a
  `:variants-grid` workspace is then fully isolated, and the variant's
  `:events` seed THAT frame directly. (Pre rf2-1w07r the shell
  hardcoded `[frame-provider-existing {:frame :rf/xray}]`, so every cell
  collided on the one `:rf/xray` app-db — driving one drove all; the
  gallery had to serialise rendering with a `:tabs` workspace + a
  re-seed-on-activation shim. The parameterized shell retires that
  workaround.)

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
                      :modal-positioning :absolute
                      :frame-id (rf/current-frame-id)}]])

;; ---- registration --------------------------------------------------------

(defn register!
  "Register every gallery view-id referenced by a variant `:component`.
  Uses `reg-view*` (the runtime-registration surface, per Spec 004)
  because the gallery view-ids are explicit panel-keyed keywords
  rather than auto-derived from a defn symbol — we want
  `:panel-gallery.epoch/Panel` not the autogen
  `:panel-gallery.panel-views/epoch-tab-panel`. Idempotent —
  re-registering the same id replaces the handler; subsequent calls
  are silent at the registry."
  []
  (rf/reg-view* :panel-gallery.app-db/Panel   app-db-tab-panel)
  (rf/reg-view* :panel-gallery.epoch/Panel    epoch-tab-panel)
  (rf/reg-view* :panel-gallery.reactive/Panel reactive-tab-panel)
  (rf/reg-view* :panel-gallery.trace/Panel    trace-tab-panel)
  (rf/reg-view* :panel-gallery.machines/Panel machines-tab-panel)
  (rf/reg-view* :panel-gallery.routing/Panel  routing-tab-panel)
  (rf/reg-view* :panel-gallery.edn-inspector/Panel edn-inspector-tab-panel)
  (rf/reg-view* :panel-gallery.chrome/Shell   chrome-shell)
  nil)
