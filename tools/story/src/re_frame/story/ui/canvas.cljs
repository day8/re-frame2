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
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [re-frame.story.config :as config]
            [re-frame.story.registrar :as registrar]
            [re-frame.story.args :as args]
            [re-frame.story.decorators :as decorators]
            [re-frame.story.runtime :as runtime]
            [re-frame.story.ui.state :as state]))

;; ---- styling -------------------------------------------------------------

(def ^:private styles
  {:wrap     {:padding "16px"
              :background "#1e1e1e"
              :flex "1"
              :min-height "200px"
              :overflow "auto"
              :color "#ddd"
              :font-family "system-ui, sans-serif"}
   :frame    {:background "#252526"
              :border "1px solid #3c3c3c"
              :border-radius "4px"
              :padding "12px"}
   :empty    {:color "#666"
              :font-style "italic"
              :text-align "center"
              :padding "32px"}
   :title    {:font-weight "bold"
              :margin-bottom "8px"
              :color "#9cdcfe"
              :font-family "monospace"
              :font-size "12px"}
   :error    {:background "#5a1d1d"
              :border "1px solid #be4040"
              :color "#fdd"
              :padding "8px"
              :margin-top "8px"
              :font-family "monospace"
              :font-size "11px"
              :border-radius "3px"}
   :assertion {:padding "4px 8px"
               :border-left "3px solid #be4040"
               :margin "2px 0"
               :font-family "monospace"
               :font-size "11px"
               :background "#332"}})

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

(defn- decorated-view
  "Wrap `view-hiccup` with the variant's `:hiccup`-kind decorators.
  Returns a hiccup vector."
  [view-hiccup hiccup-decorators effective-args]
  (decorators/apply-hiccup-decorators
    hiccup-decorators
    view-hiccup
    effective-args))

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

(defn- canvas-inner
  "The inner render fn — reads the variant's frame-db reactively. Split
  out so the outer `canvas` component can wrap with a lifecycle for
  run-variant + tear-down."
  [variant-id]
  (let [view-id        (variant-component variant-id)
        decorator-pack (decorators/resolve-decorators variant-id)
        eff-args       (args/resolve-args
                         variant-id
                         {:active-modes
                          (:active-modes @state/shell-state-atom)
                          :cell-overrides
                          (get-in @state/shell-state-atom
                                  [:cell-overrides variant-id])})
        assertions     (runtime/read-assertions variant-id)]
    [:div {:style (:frame styles)}
     [:div {:style (:title styles)}
      (str (pr-str variant-id))
      (when view-id
        [:span {:style {:color "#666" :margin-left "8px"}}
         (str "→ " (pr-str view-id))])]
     (cond
       (nil? variant-id)
       [:div {:style (:empty styles)} "no variant selected"]

       (nil? view-id)
       [:div {:style (:empty styles)}
        "variant has no :component registered — register one on the story or variant body"]

       :else
       (let [resolved-view (rf/view view-id)]
         (if resolved-view
           ;; The variant's frame is allocated; the view should
           ;; subscribe through the frame context. We render directly
           ;; under the frame keyword.
           [:div
            (decorated-view
              [resolved-view eff-args]
              (:hiccup decorator-pack)
              eff-args)]
           [:div {:style (:empty styles)}
            (str ":component " (pr-str view-id) " is not registered as a view")])))
     (render-errors (:errors decorator-pack))
     (render-assertions assertions)]))

(defn canvas
  "Render the focused variant. Triggers a `run-variant` on mount and on
  each `:hot-reload-tick` bump. Renders the variant's `:component` view
  with the resolved `:hiccup` decorator stack applied."
  []
  (r/create-class
    {:display-name "rf-story-canvas"
     :component-did-mount
     (fn [_this]
       (when config/enabled?
         (when-let [variant-id (:selected-variant @state/shell-state-atom)]
           (run-with-shell-opts! variant-id))))
     :component-did-update
     (fn [this _old-argv]
       ;; Re-run when the selected variant changes or hot-reload ticks.
       (when config/enabled?
         (when-let [variant-id (:selected-variant @state/shell-state-atom)]
           (run-with-shell-opts! variant-id))))
     :reagent-render
     (fn []
       (let [shell      @state/shell-state-atom
             variant-id (:selected-variant shell)
             _tick      (:hot-reload-tick shell)]   ;; deref to subscribe
         [:div {:style (:wrap styles)}
          (if variant-id
            [canvas-inner variant-id]
            [:div {:style (:empty styles)}
             "select a variant from the sidebar"])]))}))
