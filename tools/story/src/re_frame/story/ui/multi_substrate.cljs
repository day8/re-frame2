(ns re-frame.story.ui.multi-substrate
  "Multi-substrate side-by-side rendering. Per `002-Runtime.md` §Substrate hooks
  + `005-SOTA-Features.md` §Multi-substrate side-by-side rendering.

  When a variant declares `:substrates #{:reagent :uix :helix}` the
  canvas renders the variant against each substrate side-by-side. Per
  `002-Runtime.md` §Substrate hooks — substrate-portability gaps are the point — failures
  surface **inline** as a red overlay on the offending cell with the
  error message, rather than auto-skipping.

  ## Substrate registry

  Story doesn't add a new framework registry — substrate-rendering hooks
  are looked up in `substrate->render-fn` here. The Reference
  Implementation ships `:reagent` / `:uix` / `:helix`. Future substrates
  (e.g. `:reagent-slim`) plug in via `register-substrate!`.

  ## Grid layout

  Each substrate renders into its own bordered cell with a small header
  showing the substrate's name. Cells lay out in a CSS grid that
  auto-flows responsive to width. Failures render as a red banner above
  the (empty) cell body.

  ## Bundle isolation

  Inside the Story bundle. DCE under `:advanced` with `:rf.story/enabled?`
  off. Adapter `:require`s are NOT hard-required from this ns — the
  substrate registry is a runtime atom and the host app populates it
  via `register-substrate!`. Story core consequently does not pull
  UIx / Helix into the classpath."
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [re-frame.story.args :as args]
            [re-frame.story.decorators :as decorators]
            [re-frame.story.registrar :as registrar]
            [re-frame.story.ui.state :as state]
            [re-frame.story.theme.typography :as typography :refer [mono-stack]]
            [re-frame.story.theme.colors :as colors]))

;; ---- styling -------------------------------------------------------------

(def ^:private styles
  {:grid       {:display "grid"
                :grid-template-columns "repeat(auto-fit, minmax(280px, 1fr))"
                :gap "12px"
                :padding "12px"
                :background (:bg-canvas colors/tokens)
                :flex "1"
                :overflow "auto"}
   :cell       {:background (:bg-2 colors/tokens)
                :border "1px solid #3c3c3c"
                :border-radius "4px"
                :display "flex"
                :flex-direction "column"
                :min-height "160px"
                :color (:text-primary colors/tokens)
                :font-family mono-stack
                :font-size (:caption typography/type-scale)
                :position "relative"}
   :cell-head  {:padding "6px 10px"
                :background (:bg-2 colors/tokens)
                :border-bottom "1px solid #444"
                :color (:info colors/tokens)
                :font-weight "bold"
                :font-size (:micro typography/type-scale)
                :letter-spacing "0.5px"
                :text-transform "uppercase"
                :border-radius "4px 4px 0 0"}
   :cell-body  {:padding "10px"
                :flex 1}
   :error-cell {:background (:danger-bg colors/tokens)
                :border "1px solid #be4040"
                :color (:danger colors/tokens)
                :border-radius "4px"
                :font-family mono-stack
                :font-size (:caption typography/type-scale)}
   :error-head {:padding "6px 10px"
                :background "#7a2727"
                :font-weight "bold"
                :font-size (:micro typography/type-scale)
                :letter-spacing "0.5px"
                :text-transform "uppercase"
                :border-radius "4px 4px 0 0"}
   :error-body {:padding "10px"
                :white-space "pre-wrap"
                :word-break "break-word"}})

;; ---- substrate registry --------------------------------------------------

(defonce
  ^{:doc "Substrate-id → render-fn map. Render-fn signature:
         `(fn [variant-id view-id args] hiccup-or-react-element)`.
         The fn renders the view registered under `view-id` against
         the named substrate, threading `args` into the component.

         Pre-populated with `:reagent` which uses `re-frame.core/view`
         + plain reagent. UIx / Helix entries plug in via
         `register-substrate!` from the host app."}
  substrate->render-fn
  (atom {}))

(defn register-substrate!
  "Register a substrate render fn under `substrate-id`. The host app
  calls this once at boot for each substrate it wants Story to render
  against. The Reagent substrate registers automatically (see
  `install-reagent-substrate!` below).

  `render-fn` takes `(variant-id view-id args)` and returns a hiccup
  vector (Reagent) or a `react/createElement`-style React element
  (UIx / Helix). Story's grid renders the result inside a `:div` cell."
  [substrate-id render-fn]
  (swap! substrate->render-fn assoc substrate-id render-fn)
  nil)

(defn unregister-substrate!
  [substrate-id]
  (swap! substrate->render-fn dissoc substrate-id)
  nil)

(defn registered-substrates
  "Return the set of registered substrate ids. Used by the canvas
  switcher to enumerate the substrates a variant declares."
  []
  (set (keys @substrate->render-fn)))

;; ---- Reagent built-in substrate -----------------------------------------

(defn- reagent-render
  "Default `:reagent` substrate render fn. Looks up the view via
  `re-frame.core/view` (the framework's late-bind view lookup) and
  renders it inside a hiccup vector."
  [_variant-id view-id eff-args]
  (let [resolved (rf/view view-id)]
    (if resolved
      [resolved eff-args]
      [:div {:style {:color (:text-secondary colors/tokens) :font-style "italic"}}
       (str ":component " (pr-str view-id) " is not registered as a view")])))

(defn install-reagent-substrate!
  "Register the default `:reagent` substrate. Idempotent. Called from
  the Stage 6 boot — `install-canonical-multi-substrate!`."
  []
  (register-substrate! :reagent reagent-render))

(defn render-view
  "Render `view-id` under `substrate` with `eff-args`, via the registered
  substrate render fn — the clean reusable seam the `render-variant`
  host-render hook consumes. Returns the substrate's render
  result (a hiccup vector for `:reagent`); a missing substrate yields an
  inline diagnostic hiccup rather than throwing, so the render verb's
  caller always sees *something*. `(fn [variant-id view-id args] …)` is the
  substrate-render contract."
  [substrate variant-id view-id eff-args]
  (if-let [render-fn (get @substrate->render-fn substrate)]
    (render-fn variant-id view-id eff-args)
    [:div {:style {:color (:text-secondary colors/tokens) :font-style "italic"}}
     (str "substrate :" (name substrate) " is not registered")]))

;; ---- shared decorate-and-render seam -----------------------------------
;;
;; The canvas single-pane path AND the `render-variant` host hook
;; (`re-frame.story.canonical/render-host-scope`) paint the SAME variant
;; through the SAME decorate-and-render seam, so a decorated variant looks
;; identical on the live canvas and through `render-variant`. The decorator
;; REFS both consume are the compiled plan's already-merged
;; `[:world :decorators]` (the single merge authority, spec/017 §305-306),
;; so the registered + inline paths agree too.

(def ^:private decorator-error-styles
  {:wrap   {:background (:danger-bg colors/tokens)
            :border "1px solid #be4040"
            :color (:danger colors/tokens)
            :padding "8px"
            :margin-top "8px"
            :font-family mono-stack
            :font-size (:caption typography/type-scale)
            :border-radius "3px"}
   :uncoated {:margin-top "8px"
              :padding "8px"
              :background (:bg-canvas colors/tokens)
              :border "1px dashed #555"}})

(defn safe-decorated-view
  "Wrap `view-hiccup` with the `:hiccup`-kind `hiccup-decorators`, catching
  any exception a `:wrap` fn throws so a decorator failure renders an inline
  error block (with the bare view beneath it) rather than bubbling into a
  render-tree crash that blanks the shell (`002-Runtime.md` §Substrate hooks / §Error projection — failures
  render inline rather than aborting). `effective-args` is the resolved args
  map every `:wrap` fn receives as `[body effective-args]`.

  The ONE decorate primitive both the canvas single-pane path and the
  `render-variant` host hook call, so the two agree on the decorated tree."
  [view-hiccup hiccup-decorators effective-args]
  (try
    (decorators/apply-hiccup-decorators hiccup-decorators view-hiccup effective-args)
    (catch :default e
      [:div {:style (:wrap decorator-error-styles)}
       [:div "Decorator wrap threw — variant rendered without decorators."]
       [:div {:style {:margin-top "4px"}} (str (.-message e))]
       (when-let [ids (seq (keep :id hiccup-decorators))]
         [:div {:style {:margin-top "4px" :color (:danger colors/tokens)}}
          (str "decorators in stack: " (str/join ", " (map pr-str ids)))])
       ;; Render the variant body itself uncoated so the user still sees
       ;; *something* — the page never blanks on a decorator failure.
       [:div {:style (:uncoated decorator-error-styles)} view-hiccup]])))

(defn render-decorated-view
  "Render `view-id` under `substrate` with `eff-args` (via `render-view`),
  then wrap the result with the variant's `:hiccup` decorators resolved from
  `decorator-refs` (the compiled plan's `[:world :decorators]`). This is the
  single decorate-and-render seam: the `render-variant` host hook calls it so
  a render-variant render of a decorated variant paints the SAME tree the
  live canvas paints.

  `decorator-refs` is a vector of `[decorator-id & args]` refs (raw refs, as
  the plan carries them); only the `:hiccup`-kind decorators wrap the view —
  `:frame-setup` / `:fx-override` decorators are frame concerns applied at
  allocate time, not view-wrapping. A nil/empty `decorator-refs` is
  render-transparent (the bare view)."
  [substrate variant-id view-id eff-args decorator-refs]
  (let [view-hiccup (render-view substrate variant-id view-id eff-args)]
    (if (seq decorator-refs)
      (let [hiccup-decorators (:hiccup (decorators/resolve-decorator-refs decorator-refs))]
        (safe-decorated-view view-hiccup hiccup-decorators eff-args))
      view-hiccup)))

;; ---- failure-tolerant cell render ---------------------------------------

(defn- safe-render-cell
  "Render `view-id` under `substrate` inside a try/catch boundary. Per
  `002-Runtime.md` §Substrate hooks a substrate failure surfaces inline rather than
  aborting the whole grid.

  Returns a Reagent component."
  [variant-id substrate view-id eff-args]
  (let [render-fn (get @substrate->render-fn substrate)]
    (cond
      (nil? render-fn)
      [:div {:style (:error-cell styles)}
       [:div {:style (:error-head styles)}
        (str (name substrate))]
       [:div {:style (:error-body styles)}
        (str "substrate :" (name substrate)
             " is not registered. "
             "Call (rf.story.ui.multi-substrate/register-substrate! "
             ":" (name substrate) " render-fn) "
             "at app boot.")]]

      :else
      ;; Reagent's error boundary mechanism (r/create-class with
      ;; :component-did-catch) is the standard way to isolate render
      ;; errors. Each cell wraps in such a boundary so a throw in one
      ;; substrate doesn't blank out its neighbours.
      [(r/create-class
         {:display-name (str "rf-story-substrate-" (name substrate))
          :component-did-catch
          (fn [_this _error _info]
            ;; The error is captured in state below; this hook just
            ;; prevents the error from propagating up the React tree.
            nil)
          :get-derived-state-from-error
          (fn [error]
            #js {:error (str error)})
          :reagent-render
          (fn [variant-id substrate view-id eff-args]
            (try
              [:div {:style (:cell styles)}
               [:div {:style (:cell-head styles)} (name substrate)]
               [:div {:style (:cell-body styles)}
                (render-fn variant-id view-id eff-args)]]
              (catch :default e
                [:div {:style (:error-cell styles)}
                 [:div {:style (:error-head styles)}
                  (str (name substrate) " — render error")]
                 [:div {:style (:error-body styles)}
                  (str e)]])))})
       variant-id substrate view-id eff-args])))

;; ---- pure: substrate set resolution -------------------------------------

(defn resolve-substrate-set
  "Per `001-Authoring.md` §Registration macros: the variant's effective substrate set is
  `(or (:substrates variant-body) (:substrates story-body) #{<host>})`.
  Pure data → data; JVM-testable.

  `host-substrate` is the shell's default (typically `:reagent`)."
  [variant-body story-body host-substrate]
  (or (when (seq (:substrates variant-body)) (:substrates variant-body))
      (when (seq (:substrates story-body))   (:substrates story-body))
      #{host-substrate}))

;; ---- the multi-substrate grid component ---------------------------------

(defn multi-substrate-grid
  "Top-level component: render `variant-id` against each substrate in
  its `:substrates` set, side-by-side. The canvas component delegates
  here when a variant's substrate set has more than one entry.

  Each cell renders inside a Reagent error boundary so a throw in one
  substrate's render doesn't take down its neighbours (`002-Runtime.md` §Substrate hooks).

  Note: per-substrate decorator stacks (`002-Runtime.md` §Decorator composition order)
  are not yet threaded through here — `safe-render-cell` renders the
  raw view-id under each substrate. When that work lands, resolve the
  decorator pack here and thread it into each cell's render."
  [variant-id]
  (let [shell        @state/shell-state-atom
        variant-body (registrar/handler-meta :variant variant-id)
        story-id     (args/parent-story-id variant-id)
        story-body   (when story-id (registrar/handler-meta :story story-id))
        substrates   (resolve-substrate-set variant-body story-body
                                            (or (:substrate shell) :reagent))
        view-id      (or (:component variant-body) (:component story-body))
        eff-args     (args/resolve-args
                       variant-id
                       {:active-modes   (:active-modes shell)
                        :cell-overrides (get-in shell [:cell-overrides variant-id])})]
    ;; The multi-substrate grid is the canvas's labelled landmark when a
    ;; variant declares ≥2 substrates. `role="group"` + `aria-label`
    ;; exposes the substrate-comparison surface as a group of cells; each
    ;; cell is a `role="region"` with the substrate name as its accessible
    ;; label (set in `safe-render-cell` below), so the cells read as
    ;; labelled regions in landmark navigation.
    [:div {:style       (:grid styles)
           :role        "group"
           :aria-label  (str "Multi-substrate render — "
                             (str/join ", "
                               (map name (sort-by name substrates))))}
     (for [substrate (sort-by name substrates)]
       [:div {:key         (name substrate)
              :role        "region"
              :aria-label  (str (name substrate) " substrate cell")}
        (safe-render-cell variant-id substrate view-id eff-args)])]))
