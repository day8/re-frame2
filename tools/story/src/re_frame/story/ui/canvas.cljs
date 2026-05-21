(ns re-frame.story.ui.canvas
  "Variant render area. Per Stage 4 (rf2-ekai) IMPL-SPEC §4.

  The canvas is the surface where one variant renders. It:

  - Watches the shell state's `:hot-reload-tick` so it re-mounts when the
    fingerprint detector ticks.
  - Calls `run-variant` with the active modes / cell-overrides / substrate.
  - Renders the variant's `:component` (registered re-frame view) under
    the `:hiccup` decorator stack from `resolve-decorators`.
  - Surfaces variant-level errors inline (per IMPL-SPEC §2.2 +
    `:assertions`).

  Stage 4 reads the registered `:component` keyword and renders via
  `(re-frame.core/view <id>)` — this is the late-bind view lookup that
  spec/004 / rf2-piag exposes. The view must be registered against the
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
            [re-frame.story.runtime :as runtime]
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
;; Per rf2-c5jz / rf2-zme7: stock Reagent's `convert-prop-value` calls
;; `(name kw)` on named prop values, so a `[:> Provider {:value
;; :story.counter/clicked-three-times}]` reaches React with
;; `value="clicked-three-times"` — the namespace is dropped before the
;; subscribe-time `coerce-context-value` can read it. The reagent-slim
;; adapter (rf2-6hyy) narrows this so non-HTML props pass keywords
;; through unchanged, but Story's reference example targets stock
;; Reagent (and the rf2-zme7 repro IS on stock Reagent).
;;
;; The fix: bypass Reagent's prop conversion by emitting the React
;; element directly via `adapter-context/provider-element`, which uses
;; `React.createElement` with the raw `#js {:value frame-kw}` — no
;; conversion, no name-stripping. The keyword survives the
;; React-context round trip and the variant's frame is what subscribe
;; resolves under.

(defn frame-provider-ns-safe
  "A Reagent component that scopes a namespaced frame keyword to its
  subtree via the React context backing `rf/frame-provider` — but
  bypasses Reagent's prop-conversion so the namespace is preserved.

  Usage:
    [frame-provider-ns-safe {:frame :story.counter/clicked-three-times}
     [child-component args]]

  The component returns a single React element built via
  `React.createElement` directly. Reagent treats fn-returning-element
  as a valid render result, so this drops into normal hiccup trees."
  [props child]
  (let [frame-kw (or (:frame props) :rf/default)]
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
   :frame    {;; rf2-ypd6h: the workshop region. Atmospheric amber-halo
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
   ;; portion and the trailing affordances (open-in-editor chip, share
   ;; button) sit anchored to the right. `flex-wrap` keeps the row from
   ;; cramping on narrow canvases, but the share-button container is
   ;; pinned to the right edge so the popover (anchored from share-button
   ;; via `right: 0`) always extends INTO the canvas — never leftward
   ;; into the sidebar's screen-x range, which Playwright's hit-testing
   ;; flags as a pointer-events intercept (see Tools/Story PR #1554 CI
   ;; trace: with default fallback fonts on Linux, the natural inline
   ;; flow wraps the title and pushes share-button to the left of line 2,
   ;; placing the popover's close button under the sidebar).
   :title    {:font-weight "bold"
              :margin-bottom "8px"
              :color (:info colors/tokens)
              :font-family mono-stack
              :font-size (:body-tight typography/type-scale)
              :display "flex"
              :align-items "center"
              :flex-wrap "wrap"
              :gap "4px"}
   ;; The trailing-affordance cluster (open-in-editor + share). Pushed
   ;; to the right via `margin-left: auto` so the popover always anchors
   ;; from the canvas's right edge rather than wherever inline flow
   ;; happens to land in CI font-fallback conditions.
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
   ;; rf2-0s4p1 — identity-bearing loading skeleton during the
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
   ;; rf2-zgu68 — viewport-px indicator chip. Shows e.g. "375 × 667"
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

;; ---- loading skeleton (rf2-0s4p1) ---------------------------------------

(defn loading-phase?
  "Pure: is the variant in a phase where the loading skeleton should
  render? True when the lifecycle machine is in `:pre-mount`,
  `:mounting`, or `:loading` AND no first render has been committed
  yet AND the runtime has not recorded any assertions against the
  variant frame AND the variant is NOT 'events-only' (rf2-043cm).

  The `assertions-recorded?` arm closes a regression window introduced
  with the skeleton (rf2-qrk2s / Phase 3 cluster): the
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

  The `events-only?` arm (rf2-043cm) closes the regression PR #1574
  introduced for variants whose body declares only `:events` (no
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
  "Hiccup component rendering the identity-bearing loading skeleton
  per rf2-0s4p1. Three amber-shimmer bars on a slate ground with the
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

;; ---- viewport-px indicator chip (rf2-zgu68) ------------------------------

(defn- viewport-indicator-text
  "Pure: render the chip text e.g. `\"375 × 667\"` from a resolved
  viewport `{:width :height :label}` map. Returns nil when the preset
  has no width/height (e.g. `:full`)."
  [{:keys [width height]}]
  (when (and width height)
    (str width " × " height)))

(defn viewport-indicator
  "Hiccup component rendering the bottom-right viewport-px chip per
  rf2-zgu68. Hidden when no viewport mode is active.

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
  to the parent story and read its `:component` (per IMPL-SPEC §3.1 the
  parent story usually carries the component and variants vary only by
  args / events)."
  [variant-id]
  (let [variant-body (registrar/handler-meta :variant variant-id)
        story-id     (args/parent-story-id variant-id)
        story-body   (when story-id (registrar/handler-meta :story story-id))]
    (or (:component variant-body)
        (:component story-body))))

;; ---- decorated-view wrapper ---------------------------------------------

(defn safe-decorated-view
  "Wrap `view-hiccup` with the variant's `:hiccup`-kind decorators,
  catching any exception thrown by a `:wrap` fn so the canvas never
  bubbles into a render-tree crash that blanks the shell.

  Per IMPL-SPEC §2.2 + §5.5 the canvas's contract is 'failures render
  inline rather than aborting'. The Stage 4 path collected
  `resolve-decorators` errors only — runtime throws from inside a
  user-supplied `:wrap` fn used to propagate up Reagent's render
  machinery and React unmounted the whole shell (rf2-zme7). This wrapper
  closes that loop: a thrown exception becomes a hiccup error block
  alongside the variant frame.

  Returns either the decorator-applied hiccup tree, or, on throw, a
  hiccup error block that names the offending decorator stack."
  [view-hiccup hiccup-decorators effective-args]
  (try
    (decorators/apply-hiccup-decorators
      hiccup-decorators
      view-hiccup
      effective-args)
    (catch :default e
      [:div {:style (:error styles)}
       [:div "Decorator wrap threw — variant rendered without decorators."]
       [:div {:style {:margin-top "4px"}}
        (str (.-message e))]
       (when-let [ids (seq (keep :id hiccup-decorators))]
         [:div {:style {:margin-top "4px" :color (:danger colors/tokens)}}
          (str "decorators in stack: "
               (str/join ", " (map pr-str ids)))])
       ;; Render the variant body itself uncoated so the user still sees
       ;; *something* — the page never blanks on a decorator failure.
       [:div {:style {:margin-top "8px"
                      :padding "8px"
                      :background (:bg-canvas colors/tokens)
                      :border "1px dashed #555"}}
        view-hiccup]])))

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
  reads the variant's frame-db reactively after each run."
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
  canvas's trigger condition for its per-cell run loop (rf2-c56hr).
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

;; rf2-0s4p1 — per-variant first-render sentinel. Once a variant has
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
  "Resolve the variant's effective substrate set. Per IMPL-SPEC §3.1
  the variant body's `:substrates` wins, otherwise the parent story's
  `:substrates`, otherwise the shell's host substrate. The canvas uses
  this to decide single-substrate vs side-by-side rendering. Stage 6
  (rf2-zhwd)."
  [variant-id]
  (let [vb (registrar/handler-meta :variant variant-id)
        sid (args/parent-story-id variant-id)
        sb (when sid (registrar/handler-meta :story sid))]
    (multi-substrate/resolve-substrate-set
      vb sb (or (:substrate @state/shell-state-atom) :reagent))))

(defn- canvas-inner
  "The inner render fn — reads the variant's frame-db reactively. Split
  out so the outer `canvas` component can wrap with a lifecycle for
  run-variant + tear-down.

  Per Stage 6 (rf2-zhwd) the inner render branches on
  `(count (variant-substrate-set variant-id))`:
  - 1 substrate → single-pane render (Stage 4 path)
  - >1 substrate → multi-substrate side-by-side grid (IMPL-SPEC §2.2)."
  [variant-id]
  (let [view-id        (variant-component variant-id)
        variant-body   (registrar/handler-meta :variant variant-id)
        decorator-pack (decorators/resolve-decorators variant-id)
        eff-args       (args/resolve-args
                         variant-id
                         {:active-modes
                          (:active-modes @state/shell-state-atom)
                          :cell-overrides
                          (get-in @state/shell-state-atom
                                  [:cell-overrides variant-id])})
        assertions     (runtime/read-assertions variant-id)
        substrates     (variant-substrate-set variant-id)
        multi?         (and variant-id (> (count substrates) 1))
        ;; rf2-0s4p1 — skeleton gating. The lifecycle machine reports
        ;; :pre-mount / :mounting / :loading while the four-phase
        ;; loader cascade runs; once :ready / :error lands, the
        ;; first-rendered sentinel flips (in component-did-mount /
        ;; -did-update) and the skeleton hides.
        lifecycle-phase (try (story-loaders/current-state variant-id)
                             (catch :default _ nil))
        first?         (variant-first-rendered? variant-id)
        ;; rf2-043cm — events-only variants take the lifecycle fast-
        ;; path (`mount-ready!` jumps :pre-mount → :ready directly) so
        ;; the skeleton must not engage even on the brief render
        ;; window before `frames/allocate!` runs. Pure-data check
        ;; against the variant body + decorator stack, mirroring
        ;; `loaders/events-only-variant?`.
        events-only?   (story-loaders/events-only-variant?
                         variant-body decorator-pack)
        ;; rf2-qrk2s — a recorded assertion proves the loader cascade
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
      ;; Trailing-affordance cluster: open-in-editor chip + share popover
      ;; trigger. Pinned to the right end of the title row via the
      ;; `:title-trailing` style's `margin-left: auto`. This anchors the
      ;; share popover (which positions `right: 8px` against its inline-
      ;; block parent) so it always extends LEFTWARD INTO the canvas, not
      ;; off the canvas's left edge into the sidebar's screen-x range —
      ;; which the CI Playwright run flagged as a pointer-events intercept
      ;; when the title wrapped under default Linux fallback fonts.
      (when variant-id
        [:span {:style (:title-trailing styles)}
         ;; rf2-evgf5: per-variant 'Open in editor' chip. Reads :source
         ;; off the variant body and routes through the user's configured
         ;; editor URI scheme. Renders nothing when no source-coord was
         ;; captured at registration.
         (open-in-editor/open-chip-for-variant variant-body)
         ;; Stage 6: per-variant share affordance (IMPL-SPEC §2.8.5).
         [share/share-button variant-id]])]
     ;; rf2-9jthx: share-import hint surfaces a non-blocking note when
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

       ;; rf2-0s4p1: identity-bearing skeleton while the lifecycle
       ;; machine is still draining loaders AND no first render has
       ;; committed. Hides immediately once :ready / :error lands and
       ;; the lifecycle hook flips `first-rendered?`.
       show-skeleton?
       [loading-skeleton]

       multi?
       ;; Stage 6: multi-substrate side-by-side grid. Per IMPL-SPEC §2.2
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
           ;; The variant's frame is allocated; scope the rendered
           ;; view's subscribe / dispatch to it via a frame-provider
           ;; that preserves the namespace of a `:story.x/y`-shaped
           ;; variant id (per rf2-c5jz; the rf2-zme7 fix path). The
           ;; standard `rf/frame-provider` uses Reagent's `:>` interop
           ;; which calls `(name kw)` on prop values and drops the
           ;; namespace before React sees it.
           ;;
           ;; Per rf2-qgms1: stamp `data-rf-story-variant-root` on the
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
             (safe-decorated-view
               [resolved-view eff-args]
               (:hiccup decorator-pack)
               eff-args)]]
           [:div {:style (:empty styles)}
            (str ":component " (pr-str view-id) " is not registered as a view")])))
     (render-errors (:errors decorator-pack))
     (render-assertions assertions)]))

(defn- mark-rendered-if-ready!
  "Lifecycle helper: flip the first-rendered sentinel for the focused
  variant when the lifecycle machine reports `:ready` / `:error`, OR
  when the runtime has recorded an assertion against the variant
  frame (rf2-qrk2s). An assertion means the loader cascade resolved
  its outcome — either by a `:loaders-complete-when` predicate
  reporting incomplete or by a loader event throwing a deterministic
  rejection. In those paths the machine parks at `:loading`, but the
  user view must still render. The skeleton (rf2-0s4p1) hides on the
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
        ;; Re-run only when the variant runtime inputs change. The old
        ;; unconditional update path re-fired `:events` on every app-db
        ;; render, resetting interactive state and polluting recorder
        ;; output with fixture setup events.
        (run-if-needed!)
        ;; rf2-0s4p1 — flip the first-rendered sentinel once the
        ;; lifecycle machine reports :ready / :error.
        (mark-rendered-if-ready!))
      :component-will-unmount
      (fn [_this]
        (reset! canvas-last-run-key nil)
        ;; rf2-0s4p1 — clear the first-rendered sentinel on unmount so a
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
         ;; Per rf2-xc65: the canvas wrap is the scrollable container
         ;; for variant content; `tab-index "0"` makes it keyboard-
         ;; focusable so axe-core's `scrollable-region-focusable` rule
         ;; passes. The `<section>` element + aria-label give it a
         ;; landmark name (it's nested inside the shell's <main> so
         ;; landmark structure remains: main > section).
         ;;
         ;; Per rf2-9la06: also stamp `data-test-variant` with the
         ;; active variant id so Playwright specs can scope selectors
         ;; to the canvas of a specific variant.
         ;; (Per rf2-hscut clicking a variant in the sidebar now clears
         ;; `:selected-workspace`, so the canvas no longer competes with
         ;; a stale workspace pane for the main slot. The stamp is
         ;; retained for cross-route test scoping.)
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
