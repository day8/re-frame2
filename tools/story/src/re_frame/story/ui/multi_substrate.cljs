(ns re-frame.story.ui.multi-substrate
  "Multi-substrate side-by-side rendering. Per IMPL-SPEC §2.2 + §2.8.4
  + Stage 6 (rf2-zhwd). Phase-2 §5.1 #5.

  When a variant declares `:substrates #{:reagent :uix :helix}` the
  canvas renders the variant against each substrate side-by-side. Per
  IMPL-SPEC §2.2 — substrate-portability gaps are the point — failures
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
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [re-frame.story.args :as args]
            [re-frame.story.decorators :as decorators]
            [re-frame.story.registrar :as registrar]
            [re-frame.story.ui.state :as state]))

;; ---- styling -------------------------------------------------------------

(def ^:private styles
  {:grid       {:display "grid"
                :grid-template-columns "repeat(auto-fit, minmax(280px, 1fr))"
                :gap "12px"
                :padding "12px"
                :background "#1e1e1e"
                :flex "1"
                :overflow "auto"}
   :cell       {:background "#252526"
                :border "1px solid #3c3c3c"
                :border-radius "4px"
                :display "flex"
                :flex-direction "column"
                :min-height "160px"
                :color "#cccccc"
                :font-family "monospace"
                :font-size "11px"
                :position "relative"}
   :cell-head  {:padding "6px 10px"
                :background "#2d2d30"
                :border-bottom "1px solid #444"
                :color "#9cdcfe"
                :font-weight "bold"
                :font-size "10px"
                :letter-spacing "0.5px"
                :text-transform "uppercase"
                :border-radius "4px 4px 0 0"}
   :cell-body  {:padding "10px"
                :flex 1}
   :error-cell {:background "#5a1d1d"
                :border "1px solid #be4040"
                :color "#fdd"
                :border-radius "4px"
                :font-family "monospace"
                :font-size "11px"}
   :error-head {:padding "6px 10px"
                :background "#7a2727"
                :font-weight "bold"
                :font-size "10px"
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
      [:div {:style {:color "#888" :font-style "italic"}}
       (str ":component " (pr-str view-id) " is not registered as a view")])))

(defn install-reagent-substrate!
  "Register the default `:reagent` substrate. Idempotent. Called from
  the Stage 6 boot — `install-canonical-multi-substrate!`."
  []
  (register-substrate! :reagent reagent-render))

;; ---- failure-tolerant cell render ---------------------------------------

(defn- safe-render-cell
  "Render `view-id` under `substrate` inside a try/catch boundary. Per
  IMPL-SPEC §2.2 a substrate failure surfaces inline rather than
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
  "Per IMPL-SPEC §3.1: the variant's effective substrate set is
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
  substrate's render doesn't take down its neighbours (IMPL-SPEC §2.2)."
  [variant-id]
  (let [shell        @state/shell-state-atom
        variant-body (registrar/handler-meta :variant variant-id)
        story-id     (args/parent-story-id variant-id)
        story-body   (when story-id (registrar/handler-meta :story story-id))
        substrates   (resolve-substrate-set variant-body story-body
                                            (or (:substrate shell) :reagent))
        view-id      (or (:component variant-body) (:component story-body))
        decor-pack   (decorators/resolve-decorators variant-id)
        eff-args     (args/resolve-args
                       variant-id
                       {:active-modes   (:active-modes shell)
                        :cell-overrides (get-in shell [:cell-overrides variant-id])})]
    [:div {:style (:grid styles)}
     (for [substrate (sort-by name substrates)]
       ^{:key substrate}
       (safe-render-cell variant-id substrate view-id eff-args))]))
