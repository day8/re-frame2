(ns re-frame.story.ui.canvas
  "Variant render area. Per `003-Render-Shell.md` §Shell lifecycle.

  The canvas is the surface where one variant renders. It:

  - Watches the shell state's `:hot-reload-tick` so it re-mounts when the
    fingerprint detector ticks.
  - Calls `run-variant` with the active modes / cell-overrides / substrate.
  - Renders the variant's `:component` (registered re-frame view) under
    the `:hiccup` decorator stack from `resolve-decorators` — which reads
    the FULL stack (globals + story + variant chain) off the compiled
    plan's `[:world :decorators]`, the SAME refs
    `render-variant`'s host applies, so canvas + render-variant paint the
    identical decorated tree.
  - Surfaces variant-level errors inline (per `002-Runtime.md` §Substrate hooks +
    `:assertions`).

  The canvas reads the registered `:component` keyword and renders via
  `(re-frame.core/view <id>)` — this is the late-bind view lookup that
  spec/004 exposes. The view must be registered against the
  variant's frame; the runtime allocates the frame, so any
  frame-scoped subscriptions resolve through it correctly."
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [re-frame.adapter.context :as adapter-context]
            [re-frame.story.config :as config]
            [re-frame.story.loaders :as story-loaders]
            [re-frame.story.registrar :as registrar]
            [re-frame.story.args :as args]
            [re-frame.story.decorators :as decorators]
            [re-frame.story.plan :as plan]
            [re-frame.story.render :as render]
            [re-frame.story.runtime :as runtime]
            [re-frame.story.sub-overrides :as sub-overrides]
            [re-frame.story.ui.assertion-strip :as assertion-strip]
            [re-frame.story.ui.multi-substrate :as multi-substrate]
            [re-frame.story.ui.open-in-editor :as open-in-editor]
            [re-frame.story.ui.share :as share]
            [re-frame.story.ui.state :as state]
            [re-frame.story.theme.typography :as typography :refer [sans-stack mono-stack]]
            [re-frame.story.theme.colors :as colors]
            [re-frame.story.theme.depth :as depth]))

;; ---- namespace-preserving frame-provider --------------------------------
;;
;; Stock Reagent's `convert-prop-value` calls
;; `(name kw)` on named prop values, so a `[:> Provider {:value
;; :story.counter/clicked-three-times}]` reaches React with
;; `value="clicked-three-times"` — the namespace is dropped before the
;; subscribe-time `coerce-context-value` can read it. The reagent-slim
;; adapter narrows this so non-HTML props pass keywords
;; through unchanged, but Story's reference example targets stock
;; Reagent (and the repro is on stock Reagent).
;;
;; The fix: bypass Reagent's prop conversion by emitting the React
;; element directly via `adapter-context/provider-element`, which uses
;; `React.createElement` with the raw `#js {:value frame-kw}` — no
;; conversion, no name-stripping. The keyword survives the
;; React-context round trip and the variant's frame is what subscribe
;; resolves under.

(defn frame-provider-ns-safe
  "A Reagent component that scopes a namespaced frame keyword to its
  subtree via the React context backing `rf/frame-provider-existing` —
  but bypasses Reagent's prop-conversion so the namespace is preserved.
  Scope-only: the variant's frame is already allocated by `allocate!`'s
  `rf/make-frame`, so this neither creates nor owns a frame (it is the
  namespace-preserving twin of `rf/frame-provider-existing`, never the
  owned-lifecycle `rf/frame-provider`).

  Usage:
    [frame-provider-ns-safe {:frame :story.counter/clicked-three-times}
     [child-component args]]

  The component returns a single React element built via
  `React.createElement` directly. Reagent treats fn-returning-element
  as a valid render result, so this drops into normal hiccup trees."
  [props child]
  ;; EP-0002 — the provider scopes the EXPLICIT variant frame
  ;; (every caller passes `{:frame variant-id}`). The `:frame` prop is
  ;; threaded through verbatim; there is NO `:rf/default` synthesis when it
  ;; is absent (a frame-scoped render must name its frame — under the
  ;; carried invariant `:rf/default` is an ordinary id, never an
  ;; absence-repair floor, per Spec 002 §Frame target resolution). An
  ;; absent prop establishes the no-provider sentinel, so descendant
  ;; subscribes fail loud (`:rf.error/no-frame-context`) rather than
  ;; silently reading a synthesised default frame.
  (let [frame-kw (:frame props)]
    (adapter-context/provider-element frame-kw (r/as-element child))))

;; ---- styling -------------------------------------------------------------

(def ^:private styles
  {:wrap     {:padding "16px"
              :background (:bg-canvas colors/tokens)
              :flex "1"
              :min-height "200px"
              :overflow "auto"
              :color (:text-primary colors/tokens)
              :font-family sans-stack}
   :frame    {;; The workshop region. Atmospheric amber-halo
              ;; backdrop + amber inset edge so the variant render lifts
              ;; visibly above the surrounding chrome — the user's eye
              ;; lands here automatically.
              :background (:canvas-frame depth/backdrops)
              :border "1px solid transparent"
              :border-radius "6px"
              :padding "16px"
              :box-shadow (:canvas-edge depth/shadows)}
   :empty    {:color (:text-tertiary colors/tokens)
              :font-style "italic"
              :text-align "center"
              :padding "32px"}
   ;; Title row: flex so the variant id + view-id consume the left
   ;; portion and the trailing affordances (open-in-editor chip) sit
   ;; anchored to the right via `:title-trailing`'s `margin-left:
   ;; auto`. `flex-wrap` keeps the row from cramping on narrow
   ;; canvases.
   :title    {:font-weight "bold"
              :margin-bottom "8px"
              :color (:info colors/tokens)
              :font-family mono-stack
              :font-size (:body-tight typography/type-scale)
              :display "flex"
              :align-items "center"
              :flex-wrap "wrap"
              :gap "4px"}
   ;; The trailing-affordance cluster (open-in-editor chip). Pushed to
   ;; the right via `margin-left: auto` so the cluster sits at the
   ;; canvas's right edge rather than wherever inline flow happens to
   ;; land in CI font-fallback conditions.
   :title-trailing {:margin-left "auto"
                    :display "inline-flex"
                    :align-items "center"
                    :gap "8px"
                    :flex-shrink "0"}
   :error    {:background (:danger-bg colors/tokens)
              :border "1px solid #be4040"
              :color (:danger colors/tokens)
              :padding "8px"
              :margin-top "8px"
              :font-family mono-stack
              :font-size (:caption typography/type-scale)
              :border-radius "3px"}
   ;; Identity-bearing loading skeleton during the
   ;; four-phase loader lifecycle. Reads as "workshop loading" — amber-
   ;; shimmer pulse on a slate ground — not the generic Storybook
   ;; skeleton-row pattern.
   :skeleton {:position "relative"
              :padding "32px"
              :min-height "240px"
              :display "flex"
              :flex-direction "column"
              :gap "12px"
              :background (:bg-canvas colors/tokens)
              :border-radius "6px"
              :overflow "hidden"
              :font-family mono-stack
              :color (:text-tertiary colors/tokens)}
   :skeleton-bar {:height "12px"
                  :width "78%"
                  :background (str "linear-gradient(90deg, "
                                   (:bg-3 colors/tokens) " 0%, "
                                   (:accent-amber-soft colors/tokens) " 50%, "
                                   (:bg-3 colors/tokens) " 100%)")
                  :background-size "200% 100%"
                  :border-radius "3px"
                  :animation (str "rf-story-shimmer 1400ms "
                                  "cubic-bezier(0.4, 0.0, 0.2, 1) infinite")}
   :skeleton-bar-medium {:width "62%"}
   :skeleton-bar-short  {:width "44%"}
   :skeleton-edge {:position "absolute"
                   :inset "0"
                   :pointer-events "none"
                   :border (str "1px solid " (:accent-amber-deep colors/tokens))
                   :border-radius "6px"
                   :box-shadow (str "inset 0 0 0 1px " (:accent-amber-soft colors/tokens))}
   :skeleton-label {:font-size (:micro typography/type-scale)
                    :text-transform "uppercase"
                    :letter-spacing "0.12em"
                    :color (:accent-amber colors/tokens)
                    :margin-bottom "4px"}
   ;; Viewport-px indicator chip. Shows e.g. "375 × 667"
   ;; at canvas bottom-right when a viewport mode is active.
   :viewport-chip {:position "absolute"
                   :bottom "12px"
                   :right "16px"
                   :padding "3px 8px"
                   :background (:bg-overlay colors/tokens)
                   :border (str "1px solid " (:border-subtle colors/tokens))
                   :border-radius "4px"
                   :color (:text-secondary colors/tokens)
                   :font-family mono-stack
                   :font-size (:micro typography/type-scale)
                   :letter-spacing "0.04em"
                   :pointer-events "none"
                   :user-select "none"}})

;; ---- loading skeleton -----------------------------------------------------

(defn loading-phase?
  "Pure: is the variant in a phase where the loading skeleton should
  render? True when the lifecycle machine is in `:pre-mount`,
  `:mounting`, or `:loading` AND no first render has been committed
  yet AND the runtime has not recorded any assertions against the
  variant frame AND the variant is NOT 'events-only'.

  The `assertions-recorded?` arm closes a window where the
  `:loaders-complete-when` predicate may declare the loaders incomplete
  (e.g. `:story.counter-matrix/loader-never-completes`), or a loader
  event may throw a deterministic rejection
  (`:story.counter-matrix/loader-rejects`). In both paths the runtime
  records an assertion against the variant frame and the lifecycle
  machine stays parked at `:loading` — but the loader cascade has
  resolved its outcome and the user view (the counter, the inline
  assertion strip, …) must render. A non-empty assertions vector is
  the proof that `run-loaders!` returned; pin the skeleton off so the
  view layer takes over.

  The `events-only?` arm covers variants whose body declares only `:events` (no
  `:loaders`, no `:frame-setup` decorators, no `:loaders-complete-
  when`). Their lifecycle takes the runtime's fast-path
  (`:pre-mount → :ready` on mount via `loaders/mount-ready!`), so by
  the time the canvas reads `current-state` post-allocate the phase
  is already `:ready` — but the canvas may still render once against
  the pre-allocate `:pre-mount` snapshot (e.g. on initial selection
  before `ensure-variant-frame!` has run for the new selection on
  some browsers). For events-only variants we suppress the skeleton
  unconditionally: there is nothing to wait for.

  Returns false when `phase` is nil or `:ready` / `:error`."
  ([phase first-rendered? assertions-recorded?]
   (loading-phase? phase first-rendered? assertions-recorded? false))
  ([phase first-rendered? assertions-recorded? events-only?]
   (boolean
     (and (not first-rendered?)
          (not assertions-recorded?)
          (not events-only?)
          (contains? #{:pre-mount :mounting :loading} phase)))))

(defn loading-skeleton
  "Hiccup component rendering the identity-bearing loading skeleton.
  Three amber-shimmer bars on a slate ground with the
  inset amber edge that matches the canvas-frame chrome — reads as
  'workshop loading' rather than generic skeleton-row.

  Behind `prefers-reduced-motion: reduce` the shimmer falls back to a
  static amber inset edge."
  []
  [:div {:style       (:skeleton styles)
         :data-test   "story-canvas-loading-skeleton"
         :role        "status"
         :aria-live   "polite"
         :aria-label  "Variant loading"}
   [:div {:style (:skeleton-label styles)} "loading"]
   [:div {:style (:skeleton-bar styles)}]
   [:div {:style (merge (:skeleton-bar styles)
                        (:skeleton-bar-medium styles))}]
   [:div {:style (merge (:skeleton-bar styles)
                        (:skeleton-bar-short styles))}]
   [:div {:style (:skeleton-edge styles)
          :aria-hidden "true"}]])

;; ---- viewport-px indicator chip -----------------------------------------

(defn- viewport-indicator-text
  "Pure: render the chip text e.g. `\"375 × 667\"` from a resolved
  viewport `{:width :height :label}` map. Returns nil when the preset
  has no width/height (e.g. `:full`)."
  [{:keys [width height]}]
  (when (and width height)
    (str width " × " height)))

(defn viewport-indicator
  "Hiccup component rendering the bottom-right viewport-px chip.
  Hidden when no viewport mode is active.

  Takes the resolved viewport preset map produced by
  `viewport/resolve`. The chip is `pointer-events: none` so it never
  intercepts hits on the underlying canvas content."
  [resolved-viewport]
  (when-let [text (viewport-indicator-text resolved-viewport)]
    [:div {:style       (:viewport-chip styles)
           :data-test   "story-canvas-viewport-indicator"
           :data-viewport-dims text
           :aria-hidden "true"}
     text]))

;; ---- variant view resolution --------------------------------------------

(defn- variant-component
  "Resolve the variant's `:component` to a renderable thing. The
  variant's body may carry `:component` directly; otherwise we walk up
  to the parent story and read its `:component` (per `001-Authoring.md` §Registration macros the
  parent story usually carries the component and variants vary only by
  args / events)."
  [variant-id]
  (let [variant-body (registrar/handler-meta :variant variant-id)
        story-id     (args/parent-story-id variant-id)
        story-body   (when story-id (registrar/handler-meta :story story-id))]
    (or (:component variant-body)
        (:component story-body))))

;; ---- view-state subscription overrides ----------------------------------
;;
;; A variant whose goal is rendering / design exploration MAY author
;; `:sub-overrides` — a map of exact subscription query vectors → data
;; values the renderer surfaces for them (spec/017 §View-state
;; subscription overrides). At render the canvas resolves the variant's
;; override map (arg-substituting `[:arg key]` placeholders against the
;; effective args, the SAME one-level substitution the plan compiler uses)
;; and binds it through `sub-overrides/with-overrides*` for the view-
;; render extent. The binding never touches app-db or `compute-sub`, so
;; it can never satisfy a subscription assertion (`:rf.assert/sub-equals`).
;;
;; The carriage is a React context
;; (`re-frame.adapter.sub-override-context`), not a dynamic var — the var
;; does not survive into the view's deferred render. `sub-overrides-scope`
;; wraps the view in the override-context Provider; a descendant
;; `@(rf/subscribe)` reads the override at deref time via the
;; `:subs/resolve-sub-override` core hook (consulted dev-only inside
;; `subscribe`'s `interop/debug-enabled?` gate). The override feeds only
;; the constant reaction the view derefs — never app-db / `compute-sub` —
;; so `:rf.assert/sub-equals` stays unsatisfiable by an override. See the
;; `re-frame.story.sub-overrides` ns docstring §STATUS.

(defn- resolve-sub-overrides
  "Return the variant's resolved `:sub-overrides` map (exact query vectors →
  values, `[:arg key]` placeholders substituted against `eff-args`), or nil
  when the variant authors none.

  Routes through the COMPILED variant-plan (`plan/variant-plan`) and the
  SHARED `render/resolve-render-sub-overrides` resolver — the SAME source
  `render-variant` reads — NOT the bare registrar body (the shared
  resolver invariant). Reading `(:sub-overrides body)` off the
  side-table saw ONLY the variant's OWN slot, dropping overrides contributed
  by a `:compose`d fragment or an `:extends` parent (the plan compiler
  COMPOSES them into `[:render-raw :sub-overrides]` — plan.cljc §sub-overrides
  composition). The canvas and `render-variant` therefore diverged for
  composed / extended overrides; routing both through the compiled plan
  removes the divergence (single source of truth).

  `eff-args` is the post-control effective args; `resolve-render-sub-overrides`
  re-substitutes the RAW (pre-`[:arg]`) overrides against them so a
  control-driven override value reflects the live control, exactly as on the
  render-variant path."
  [variant-id eff-args]
  (render/resolve-render-sub-overrides (plan/variant-plan variant-id) eff-args))

(defn sub-overrides-scope
  "A Reagent component that wraps `child`'s render in the override-context
  Provider carrying the variant's resolved `:sub-overrides` map.
  A descendant view's `@(rf/subscribe)` reads the override at deref time
  via the `:subs/resolve-sub-override` core hook. The override never
  touches app-db / `compute-sub`, so it can never satisfy a subscription
  assertion (`:rf.assert/sub-equals`).

  The carriage is a React CONTEXT, not a dynamic var: the var does not
  survive into the view's deferred render (the child renders in its own
  reaction, after a `binding` would have unwound — empirically confirmed
  under react-dom/server). React context survives arbitrary nesting,
  exactly how `re-frame.adapter.context` propagates the frame-id.

  When the variant authors no overrides the map is nil and this wrapper is
  render-transparent (the descendant consult misses and the view reads its
  real subscription)."
  [overrides child]
  (sub-overrides/override-provider overrides child))

;; ---- decorated-view wrapper ---------------------------------------------

(def safe-decorated-view
  "Wrap `view-hiccup` with the variant's `:hiccup`-kind decorators, catching
  any exception a `:wrap` fn throws so the canvas never bubbles into a
  render-tree crash that blanks the shell (`002-Runtime.md` §Substrate hooks + §Error projection — failures
  render inline; the 'never blank the canvas' rule).

  The decorate-and-render primitive is the SHARED
  seam in `re-frame.story.ui.multi-substrate`, called by BOTH the canvas
  single-pane path (below), the workspace cell, AND the `render-variant`
  host hook (`re-frame.story.canonical/render-host-scope`), so the live
  canvas and render-variant paint the identical decorated tree. Re-exported
  here so the canvas / workspace call sites keep one import."
  multi-substrate/safe-decorated-view)

;; ---- error projection ---------------------------------------------------

(defn- render-errors [errors]
  (when (seq errors)
    [:div {:style (:error styles)}
     [:div "Decorator errors:"]
     (for [[i e] (map-indexed vector errors)]
       ^{:key i}
       [:div (pr-str e)])]))

(defn- render-assertions
  "Render the variant frame's accumulated assertions in a structured
  row treatment — status glyph + label + one-line summary with click-
  to-expand detail. Sourced from the shared
  `re-frame.story.ui.assertion-strip` component the workspace cell
  also consumes (single source of truth for the inline strip shape)."
  [assertions]
  (when (seq assertions)
    [assertion-strip/assertion-strip assertions]))

;; ---- the canvas component ------------------------------------------------

(defn- run-with-shell-opts!
  "Drive `run-variant` with the shell's current modes / cell overrides /
  substrate. Returns nothing — the promise resolves async; the canvas
  reads the variant's app-db-value reactively after each run."
  [variant-id]
  (let [shell @state/shell-state-atom
        opts  {:active-modes   (:active-modes shell)
               :cell-overrides (get-in shell [:cell-overrides variant-id])
               :substrate      (:substrate shell)
               :render?        true}]
    (runtime/run-variant variant-id opts)
    nil))

(defn run-key
  "The shell-state slice that should trigger a fresh runtime run for
  `variant-id`. Ordinary app-db updates inside the variant frame must
  not re-dispatch the variant's static `:events`; otherwise user
  interactions reset the canvas and the recorder captures fixture
  initialisation events as if they were user actions.

  Public so the workspace renderer (`ui/workspace.cljc`) can mirror the
  canvas's trigger condition for its per-cell run loop.
  Both surfaces re-run when ANY of `:variant-id` / `:hot-reload-tick` /
  `:active-modes` / `:cell-overrides` / `:substrate` changes — keeping
  them lockstep prevents the workspace cell from rendering against
  stale `:events-seeded` app-db when the controls panel writes a new
  `:cell-overrides` entry or the user toggles a mode / substrate."
  [shell variant-id]
  {:variant-id       variant-id
   :hot-reload-tick  (:hot-reload-tick shell)
   :active-modes     (:active-modes shell)
   :cell-overrides   (get-in shell [:cell-overrides variant-id])
   :substrate        (:substrate shell)})

(defonce ^:private canvas-last-run-key
  (atom nil))

;; Per-variant first-render sentinel. Once a variant has
;; committed its first render, the skeleton never re-appears (a hot-
;; reload re-run is brief enough that re-flashing the skeleton would
;; read as a glitch).
(defonce ^:private first-rendered? (atom #{}))

(defn mark-variant-rendered!
  "Stamp `variant-id` as 'has committed a first render'. Public for tests."
  [variant-id]
  (when variant-id
    (swap! first-rendered? conj variant-id)))

(defn variant-first-rendered?
  "True when `variant-id` has committed a first canvas render."
  [variant-id]
  (contains? @first-rendered? variant-id))

(defn reset-first-rendered!
  "Clear the first-rendered sentinel."
  ([] (reset! first-rendered? #{}) nil)
  ([variant-id] (swap! first-rendered? disj variant-id) nil))

(defn- run-if-needed!
  []
  (when config/enabled?
    (let [shell      @state/shell-state-atom
          variant-id (:selected-variant shell)]
      (if-not variant-id
        (reset! canvas-last-run-key nil)
        (let [key (run-key shell variant-id)]
          (when (not= key @canvas-last-run-key)
            (reset! canvas-last-run-key key)
            (run-with-shell-opts! variant-id)))))))

(defn- variant-substrate-set
  "Resolve the variant's effective substrate set. Per `001-Authoring.md` §Registration macros
  the variant body's `:substrates` wins, otherwise the parent story's
  `:substrates`, otherwise the shell's host substrate. The canvas uses
  this to decide single-substrate vs side-by-side rendering."
  [variant-id]
  (let [vb (registrar/handler-meta :variant variant-id)
        sid (args/parent-story-id variant-id)
        sb (when sid (registrar/handler-meta :story sid))]
    (multi-substrate/resolve-substrate-set
      vb sb (or (:substrate @state/shell-state-atom) :reagent))))

(defn- canvas-inner
  "The inner render fn — reads the variant's app-db-value reactively. Split
  out so the outer `canvas` component can wrap with a lifecycle for
  run-variant + tear-down.

  The inner render branches on
  `(count (variant-substrate-set variant-id))`:
  - 1 substrate → single-pane render
  - >1 substrate → multi-substrate side-by-side grid (`002-Runtime.md` §Substrate hooks)."
  [variant-id]
  (let [view-id        (variant-component variant-id)
        variant-body   (registrar/handler-meta :variant variant-id)
        ;; The SAME per-run opts the `eff-args` resolve below
        ;; uses, threaded into `resolve-decorators` so the plan it
        ;; recompiles to read `[:world :decorators]` substitutes `[:arg]`
        ;; keys with the mode/cell-aware args. Without this an `[:arg key]`
        ;; resolvable ONLY through an active-mode / cell-override layer
        ;; (never the variant chain) throws `:rf.error/story-missing-arg`
        ;; here even though the runtime's plan compile handles it — the
        ;; canvas decorator recompile needs the same opts.
        run-opts       {:active-modes
                        (:active-modes @state/shell-state-atom)
                        :cell-overrides
                        (get-in @state/shell-state-atom
                                [:cell-overrides variant-id])}
        decorator-pack (decorators/resolve-decorators variant-id run-opts)
        eff-args       (args/resolve-args variant-id run-opts)
        assertions     (runtime/read-assertions variant-id)
        ;; Resolve the variant's view-state subscription
        ;; overrides (arg-substituted) for the render-path binding below.
        sub-ovr        (resolve-sub-overrides variant-id eff-args)
        substrates     (variant-substrate-set variant-id)
        multi?         (and variant-id (> (count substrates) 1))
        ;; Skeleton gating. The lifecycle machine reports
        ;; :pre-mount / :mounting / :loading while the four-phase
        ;; loader cascade runs; once :ready / :error lands, the
        ;; first-rendered sentinel flips (in component-did-mount /
        ;; -did-update) and the skeleton hides.
        lifecycle-phase (try (story-loaders/current-state variant-id)
                             (catch :default _ nil))
        first?         (variant-first-rendered? variant-id)
        ;; Events-only variants take the lifecycle fast-
        ;; path (`mount-ready!` jumps :pre-mount → :ready directly) so
        ;; the skeleton must not engage even on the brief render
        ;; window before `frames/allocate!` runs. Pure-data check
        ;; against the variant body + decorator stack, mirroring
        ;; `loaders/events-only-variant?`.
        events-only?   (story-loaders/events-only-variant?
                         variant-body decorator-pack)
        ;; A recorded assertion proves the loader cascade
        ;; resolved its outcome (success path → :ready; failure paths
        ;; like loader-never-completes / loader-rejects park at
        ;; :loading but record an incomplete/rejection assertion). In
        ;; either case the user view must render: the skeleton would
        ;; otherwise pin forever on the deterministic-failure variants
        ;; and hide the count text the load-gate reads.
        show-skeleton? (loading-phase? lifecycle-phase first?
                                       (seq assertions) events-only?)]
    [:div {:style (:frame styles)}
     [:div {:style (:title styles)}
      [:span (str (pr-str variant-id))]
      (when view-id
        [:span {:style {:color (:text-tertiary colors/tokens)}}
         (str "→ " (pr-str view-id))])
      (when multi?
        [:span {:style {:color (:text-secondary colors/tokens)
                        :font-size (:micro typography/type-scale) :font-weight "normal"}}
         (str " (substrates: "
              (str/join ", " (map name (sort-by name substrates)))
              ")")])
      ;; Trailing-affordance cluster: open-in-editor chip. Pinned to the
      ;; right end of the title row via the `:title-trailing` style's
      ;; `margin-left: auto`. The variant URL is already in the browser's
      ;; address bar (Cmd-L / Cmd-A / Cmd-C copies it); there is no Share
      ;; button — the live URL is already the canonical state surface.
      (when variant-id
        [:span {:style (:title-trailing styles)}
         ;; Per-variant 'Open in editor' chip. Reads :source
         ;; off the variant body and routes through the user's configured
         ;; editor URI scheme. Renders nothing when no source-coord was
         ;; captured at registration.
         (open-in-editor/open-chip-for-variant variant-body)])]
     ;; The share-import hint surfaces a non-blocking note when
     ;; a hydrated share URL dropped one or more overrides (variant
     ;; args refactored/renamed/removed). Renders nil when nothing
     ;; dropped, so this is unconditional-safe.
     (when variant-id
       [share/share-import-hint variant-id])
     (cond
       (nil? variant-id)
       [:div {:style (:empty styles)} "no variant selected"]

       (nil? view-id)
       [:div {:style (:empty styles)}
        "variant has no :component registered — register one on the story or variant body"]

       ;; Identity-bearing skeleton while the lifecycle
       ;; machine is still draining loaders AND no first render has
       ;; committed. Hides immediately once :ready / :error lands and
       ;; the lifecycle hook flips `first-rendered?`.
       show-skeleton?
       [loading-skeleton]

       multi?
       ;; Multi-substrate side-by-side grid. Per `002-Runtime.md` §Substrate hooks
       ;; failures render inline rather than aborting. The grid still
       ;; renders user views, so it needs the same frame context as the
       ;; single-substrate path; otherwise Reagent subscriptions fall
       ;; back to the live app/default frame.
       ^{:key (str "multi-" variant-id)}
       [frame-provider-ns-safe {:frame variant-id}
        [multi-substrate/multi-substrate-grid variant-id]]

       :else
       (let [resolved-view (rf/view view-id)]
         (if resolved-view
           ;; The variant's frame is already allocated; scope the
           ;; rendered view's subscribe / dispatch to it (scope-only,
           ;; like `rf/frame-provider-existing`) via a provider that
           ;; preserves the namespace of a `:story.x/y`-shaped
           ;; variant id (the namespace-preserving provider). The
           ;; standard `rf/frame-provider-existing` uses Reagent's `:>`
           ;; interop which calls `(name kw)` on prop values and drops
           ;; the namespace before React sees it.
           ;;
           ;; Stamp `data-rf-story-variant-root` on the
           ;; immediate wrapper around the user-authored decorated view
           ;; so the a11y panel (ui/a11y.cljs) can scope axe-core's
           ;; scan to ONLY the variant's rendered tree — excluding the
           ;; surrounding Story chrome (title bar, share button, open-
           ;; in-editor chip, panels, sidebar, toolbar). Without this
           ;; marker axe-core's `run()` against `document.body` flags
           ;; Story's own UI as the source of violations, which is
           ;; wrong: Story chrome a11y is Story's concern, not the
           ;; variant author's.
           ^{:key (str "single-" variant-id)}
           [frame-provider-ns-safe {:frame variant-id}
            [:div {:key (str "variant-root:" (pr-str variant-id))
                   :data-rf-story-variant-root (pr-str variant-id)}
             ;; Bind the variant's resolved view-state
             ;; subscription overrides for the view-render extent (a no-op
             ;; wrapper when the variant authors none).
             [sub-overrides-scope sub-ovr
              (safe-decorated-view
                [resolved-view eff-args]
                (:hiccup decorator-pack)
                eff-args)]]]
           [:div {:style (:empty styles)}
            (str ":component " (pr-str view-id) " is not registered as a view")])))
     (render-errors (:errors decorator-pack))
     (render-assertions assertions)]))

(defn- mark-rendered-if-ready!
  "Lifecycle helper: flip the first-rendered sentinel for the focused
  variant when the lifecycle machine reports `:ready` / `:error`, OR
  when the runtime has recorded an assertion against the variant
  frame. An assertion means the loader cascade resolved
  its outcome — either by a `:loaders-complete-when` predicate
  reporting incomplete or by a loader event throwing a deterministic
  rejection. In those paths the machine parks at `:loading`, but the
  user view must still render. The skeleton hides on the
  next render pass."
  []
  (when config/enabled?
    (let [shell      @state/shell-state-atom
          variant-id (:selected-variant shell)]
      (when variant-id
        (let [phase (try (story-loaders/current-state variant-id)
                         (catch :default _ nil))
              assertions (try (runtime/read-assertions variant-id)
                              (catch :default _ nil))]
          (when (or (contains? #{:ready :error} phase)
                    (seq assertions))
            (mark-variant-rendered! variant-id)))))))

(def canvas
  "Render the focused variant. Triggers a `run-variant` on mount and on
  each `:hot-reload-tick` bump. Renders the variant's `:component` view
  with the resolved `:hiccup` decorator stack applied."
  (r/create-class
     {:display-name "rf-story-canvas"
      :component-did-mount
      (fn [_this]
        (run-if-needed!)
        (mark-rendered-if-ready!))
      :component-did-update
      (fn [_this _old-argv]
        ;; Re-run only when the variant runtime inputs change. An
        ;; unconditional update would re-fire `:events` on every app-db
        ;; render, resetting interactive state and polluting recorder
        ;; output with fixture setup events.
        (run-if-needed!)
        ;; Flip the first-rendered sentinel once the
        ;; lifecycle machine reports :ready / :error.
        (mark-rendered-if-ready!))
      :component-will-unmount
      (fn [_this]
        (reset! canvas-last-run-key nil)
        ;; Clear the first-rendered sentinel on unmount so a
        ;; re-mount sees the skeleton for the appropriate loader window.
        (reset-first-rendered!))
     :reagent-render
     (fn []
       (let [shell      @state/shell-state-atom
             variant-id (:selected-variant shell)
             opts       {:active-modes   (:active-modes shell)
                         :cell-overrides (get-in shell [:cell-overrides variant-id])
                         :substrate      (:substrate shell)}
             snapshot   (when variant-id
                          (runtime/snapshot-identity variant-id opts))
             _tick      (:hot-reload-tick shell)]   ;; deref to subscribe
         ;; The canvas wrap is the scrollable container
         ;; for variant content; `tab-index "0"` makes it keyboard-
         ;; focusable so axe-core's `scrollable-region-focusable` rule
         ;; passes. The `<section>` element + aria-label give it a
         ;; landmark name (it's nested inside the shell's <main> so
         ;; landmark structure remains: main > section).
         ;;
         ;; Also stamp `data-test-variant` with the active variant id
         ;; so Playwright specs can scope selectors to the canvas of a
         ;; specific variant. Clicking a variant in the sidebar clears
         ;; `:selected-workspace`, so the canvas never competes with a
         ;; stale workspace pane for the main slot; the stamp also serves
         ;; cross-route test scoping.
        [:section (cond-> {:style      (:wrap styles)
                           :aria-label "Variant canvas"
                           :tab-index  "0"}
                     variant-id (assoc :data-test-variant (pr-str variant-id))
                     (:content-hash snapshot) (assoc :data-snapshot-hash
                                                     (:content-hash snapshot)))
          (if variant-id
            [canvas-inner variant-id]
            [:div {:style (:empty styles)}
             "select a variant from the sidebar"])]))}))
