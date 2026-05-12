(ns re-frame.story.ui.controls
  "Controls panel — args editor, mode picker, decorator toggles. Per
  Stage 4 (rf2-ekai) IMPL-SPEC §4 + §9.4.

  ## Args derivation

  Per IMPL-SPEC §9.4 the args editor auto-derives widget shapes from the
  variant's `:argtypes` (an explicit per-arg widget spec) or from the
  Malli schema attached to `:component` (Spec 010 schemas). Stage 4
  ships the explicit-`:argtypes` path and the Malli inference for a
  handful of primitive forms — `:string` / `:int` / `:double` /
  `:boolean` / `:enum`. Deeper Malli walks land in Stage 6 alongside
  the design-tokens panel.

  ## Mode picker

  Renders every registered `:mode` as a toggle row. Toggling activates
  the mode; the canvas re-runs with the new mode set on the next tick.

  ## Decorator toggles

  Lists the variant's resolved decorators; each can be skipped at
  runtime by name (the toggle writes a `:disabled-decorators` shell-
  state slot the canvas reads). Stage 4 ships the toggle UI; the
  disable-by-name routing through `resolve-decorators` is a Stage 6
  refinement when the decorator stack grows more complex. For Stage 4
  the toggle is informational + a 'save layout' hook."
  (:require [re-frame.story.registrar :as registrar]
            [re-frame.story.args      :as args]
            [re-frame.story.decorators :as decorators]
            [re-frame.story.ui.state  :as state]))

;; ---- styling -------------------------------------------------------------

(def ^:private styles
  {:wrap        {:padding "8px"
                 :background "#252526"
                 :color "#cccccc"
                 :font-family "monospace"
                 :font-size "11px"
                 :border-top "1px solid #444"}
   :section     {:margin-bottom "12px"}
   :section-h   {:font-weight "bold"
                 :color "#b0b0b0"
                 :text-transform "uppercase"
                 :font-size "10px"
                 :letter-spacing "0.5px"
                 :margin-bottom "4px"}
   :row         {:display "grid"
                 :grid-template-columns "120px 1fr"
                 :gap "8px"
                 :padding "2px 0"
                 :align-items "center"}
   :label       {:color "#9cdcfe"}
   :input       {:background "#1e1e1e"
                 :color "#cccccc"
                 :border "1px solid #444"
                 :padding "2px 6px"
                 :font-family "monospace"
                 :font-size "11px"
                 :width "100%"}
   :chip-row    {:display "flex"
                 :flex-wrap "wrap"
                 :gap "4px"}
   :chip        {:padding "2px 6px"
                 :background "#37373d"
                 :color "#cccccc"
                 :border-radius "10px"
                 :cursor "pointer"
                 :font-size "10px"
                 :user-select "none"}
   :chip-active {:background "#0e639c"
                 :color "white"}
   :button      {:padding "4px 8px"
                 :background "#0e639c"
                 :color "white"
                 :border "none"
                 :border-radius "3px"
                 :cursor "pointer"
                 :font-size "10px"
                 :margin-top "8px"}
   :empty       {:color "#9a9a9a" :font-style "italic"}})

;; ---- pure: argtype inference --------------------------------------------

(defn infer-widget
  "Given a Malli schema fragment (or simple keyword type), return a
  widget descriptor map. Pure data → data; JVM-testable.

  - `:string`             → `{:widget :text}`
  - `:int` / `:double`    → `{:widget :number}`
  - `:boolean`            → `{:widget :boolean}`
  - `[:enum a b c]`       → `{:widget :select :options [a b c]}`

  Unknown shapes default to `{:widget :text}` — the user can refine
  via the variant's `:argtypes` slot. Per IMPL-SPEC §9.4."
  [schema-fragment]
  (cond
    (= :string schema-fragment)  {:widget :text}
    (= :int schema-fragment)     {:widget :number}
    (= :double schema-fragment)  {:widget :number}
    (= :boolean schema-fragment) {:widget :boolean}
    (= :keyword schema-fragment) {:widget :text}
    (and (vector? schema-fragment)
         (= :enum (first schema-fragment)))
    {:widget :select :options (vec (rest schema-fragment))}
    :else
    {:widget :text}))

(defn resolve-argtypes
  "Build the `{arg-key → widget-spec}` map for a variant. Variant-level
  `:argtypes` wins; otherwise we walk the parent story's `:argtypes`;
  otherwise we infer from the resolved args' value shapes.

  Stage 4 punts the full Spec 010 Malli walk (e.g. component-attached
  schemas via registrar) to Stage 6 — for Stage 4 the explicit
  `:argtypes` path is the load-bearing surface."
  [variant-id]
  (let [vb      (registrar/handler-meta :variant variant-id)
        story   (args/parent-story-id variant-id)
        sb      (when story (registrar/handler-meta :story story))
        types   (merge (:argtypes sb) (:argtypes vb))
        eff     (args/resolve-args variant-id)]
    (reduce-kv
      (fn [acc k v]
        (if (contains? acc k)
          acc
          (assoc acc k (infer-widget
                         (cond
                           (string? v)  :string
                           (boolean? v) :boolean
                           (integer? v) :int
                           (number? v)  :double
                           (keyword? v) :keyword
                           :else        :string)))))
      (or types {})
      eff)))

;; ---- widget renderers ----------------------------------------------------

(defn- read-event-value [e]
  (.. e -target -value))

(defn- read-event-checked [e]
  (.. e -target -checked))

(defn- arg-widget
  [variant-id arg-key value {:keys [widget options]}]
  (case widget
    :text     [:input {:type      "text"
                       :style     (:input styles)
                       :value     (if (nil? value) "" (str value))
                       :on-change (fn [e]
                                    (state/swap-state!
                                      state/set-cell-override
                                      variant-id arg-key
                                      (read-event-value e)))}]
    :number   [:input {:type      "number"
                       :style     (:input styles)
                       :value     (if (nil? value) "" value)
                       :on-change (fn [e]
                                    (let [s (read-event-value e)
                                          v (when (seq s) (js/parseFloat s))]
                                      (state/swap-state!
                                        state/set-cell-override
                                        variant-id arg-key v)))}]
    :boolean  [:input {:type      "checkbox"
                       :checked   (boolean value)
                       :on-change (fn [e]
                                    (state/swap-state!
                                      state/set-cell-override
                                      variant-id arg-key
                                      (read-event-checked e)))}]
    :select   [:select {:value     (str value)
                        :style     (:input styles)
                        :on-change (fn [e]
                                     (state/swap-state!
                                       state/set-cell-override
                                       variant-id arg-key
                                       (read-event-value e)))}
               (for [opt options]
                 ^{:key (str opt)}
                 [:option {:value (str opt)} (str opt)])]
    [:span {:style (:empty styles)}
     (str "unsupported widget " widget)]))

;; ---- public components ---------------------------------------------------

(defn args-editor
  "Render the args editor for `variant-id`. Reads the resolved args via
  `args/resolve-args` and walks every key, rendering a widget per the
  inferred argtype."
  [variant-id]
  (let [shell      @state/shell-state-atom
        eff-args   (args/resolve-args
                     variant-id
                     {:active-modes (:active-modes shell)
                      :cell-overrides
                      (get-in shell [:cell-overrides variant-id])})
        argtypes   (resolve-argtypes variant-id)]
    [:div {:style (:section styles)}
     [:div {:style (:section-h styles)} "Args"]
     (if (empty? eff-args)
       [:div {:style (:empty styles)} "no args resolved"]
       (for [[k v] (sort-by key eff-args)]
         ^{:key k}
         [:div {:style (:row styles)}
          [:span {:style (:label styles)} (str k)]
          [arg-widget variant-id k v
           (get argtypes k {:widget :text})]]))
     (when (seq (get-in shell [:cell-overrides variant-id]))
       [:button {:style    (:button styles)
                 :on-click (fn [_]
                             (state/swap-state!
                               state/clear-cell-overrides variant-id))}
        "reset overrides"])]))

(defn mode-picker
  "Render every registered mode as a toggle. Active modes are tracked
  in the shell state's `:active-modes` vector — toggle adds/removes."
  []
  (let [shell  @state/shell-state-atom
        modes  (registrar/handlers :mode)
        active (set (:active-modes shell))]
    [:div {:style (:section styles)}
     [:div {:style (:section-h styles)} "Modes"]
     (if (empty? modes)
       [:div {:style (:empty styles)} "no modes registered"]
       [:div {:style (:chip-row styles)}
        (for [[mid _body] (sort-by key modes)]
          ^{:key mid}
          [:span {:style    (merge (:chip styles)
                                   (when (contains? active mid)
                                     (:chip-active styles)))
                  :on-click
                  (fn [_]
                    (state/swap-state!
                      (fn [s]
                        (state/set-active-modes
                          s
                          (let [v (:active-modes s)]
                            (if (some #(= % mid) v)
                              (vec (remove #(= % mid) v))
                              (conj v mid)))))))}
           (str mid)])])]))

(defn decorator-list
  "Show the variant's resolved decorator stack. Stage 4 is read-only;
  Stage 6 adds the disable-by-name routing.

  Per rf2-4t5u: the resolved stack can carry the SAME decorator id more
  than once — e.g. a story-level decorator and a variant-level
  decorator may share an id, and `resolve-decorators` concats the
  `:hiccup` / `:frame-setup` / `:fx-override` packs without
  de-duping (each pack is a kind-specific layer). React requires
  unique `key`s in a sibling list, so the row key is the `[index id]`
  tuple rather than the bare id."
  [variant-id]
  (let [pack  (decorators/resolve-decorators variant-id)
        all   (concat (:hiccup pack) (:frame-setup pack) (:fx-override pack))]
    [:div {:style (:section styles)}
     [:div {:style (:section-h styles)} "Decorators"]
     (if (empty? all)
       [:div {:style (:empty styles)} "no decorators on this variant"]
       (for [[i {:keys [id body]}] (map-indexed vector all)]
         ^{:key [i id]}
         [:div {:style (:row styles)}
          [:span {:style (:label styles)} (str id)]
          [:span (str ":kind " (or (:kind body) "?"))]]))]))

(defn save-layout-button
  "Emit a stub 'save layout as :Workspace' action. Per IMPL-SPEC §2.5
  the workspace persistence has two modes: local-storage (default) and
  the explicit save-as which writes a transit-shareable registration.
  Stage 4 stubs the save-as button; the transit emission lands in
  Stage 6 alongside the QR code share affordance."
  []
  [:button {:style    (:button styles)
            :on-click (fn [_]
                        ;; Stage 4: no-op visual affordance — the
                        ;; transit-emission lives behind the §2.5
                        ;; surface that lands in Stage 6.
                        (when js/window
                          (js/console.log "[story] save-layout: Stage 6 ships transit emission")))}
   "save layout as :Workspace.x/y"])

(defn panel
  "The full controls panel — args editor + mode picker + decorator
  list + save-layout action."
  [variant-id]
  [:div {:style (:wrap styles)}
   (when variant-id
     [args-editor variant-id])
   [mode-picker]
   (when variant-id
     [decorator-list variant-id])
   [save-layout-button]])
