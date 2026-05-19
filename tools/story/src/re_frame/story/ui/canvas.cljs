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
            [re-frame.story.registrar :as registrar]
            [re-frame.story.args :as args]
            [re-frame.story.decorators :as decorators]
            [re-frame.story.runtime :as runtime]
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
   :title    {:font-weight "bold"
              :margin-bottom "8px"
              :color (:info colors/tokens)
              :font-family mono-stack
              :font-size (:body-tight typography/type-scale)}
   :error    {:background (:danger-bg colors/tokens)
              :border "1px solid #be4040"
              :color (:danger colors/tokens)
              :padding "8px"
              :margin-top "8px"
              :font-family mono-stack
              :font-size (:caption typography/type-scale)
              :border-radius "3px"}
   :assertion {:padding "4px 8px"
               :border-left "3px solid #be4040"
               :margin "2px 0"
               :font-family mono-stack
               :font-size (:caption typography/type-scale)
               :background (:danger-bg colors/tokens)}})

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

(defn- render-assertions [assertions]
  (when (seq assertions)
    [:div
     (for [[i a] (map-indexed vector assertions)]
       ^{:key i}
       [:div {:style (:assertion styles)}
        (pr-str a)])]))

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
        multi?         (and variant-id (> (count substrates) 1))]
    [:div {:style (:frame styles)}
     [:div {:style (:title styles)}
      [:span (str (pr-str variant-id))]
      (when view-id
        [:span {:style {:color (:text-tertiary colors/tokens) :margin-left "8px"}}
         (str "→ " (pr-str view-id))])
      (when multi?
        [:span {:style {:color (:text-secondary colors/tokens) :margin-left "8px"
                        :font-size (:micro typography/type-scale) :font-weight "normal"}}
         (str " (substrates: "
              (str/join ", " (map name (sort-by name substrates)))
              ")")])
      ;; rf2-evgf5: per-variant 'Open in editor' chip. Reads :source
      ;; off the variant body and routes through the user's configured
      ;; editor URI scheme. Renders nothing when no source-coord was
      ;; captured at registration.
      (when variant-id
        (open-in-editor/open-chip-for-variant variant-body))
      ;; Stage 6: per-variant share affordance (IMPL-SPEC §2.8.5).
      (when variant-id
        [share/share-button variant-id])]
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

(def canvas
  "Render the focused variant. Triggers a `run-variant` on mount and on
  each `:hot-reload-tick` bump. Renders the variant's `:component` view
  with the resolved `:hiccup` decorator stack applied."
  (r/create-class
     {:display-name "rf-story-canvas"
      :component-did-mount
      (fn [_this]
        (run-if-needed!))
      :component-did-update
      (fn [_this _old-argv]
        ;; Re-run only when the variant runtime inputs change. The old
        ;; unconditional update path re-fired `:events` on every app-db
        ;; render, resetting interactive state and polluting recorder
        ;; output with fixture setup events.
        (run-if-needed!))
      :component-will-unmount
      (fn [_this]
        (reset! canvas-last-run-key nil))
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
