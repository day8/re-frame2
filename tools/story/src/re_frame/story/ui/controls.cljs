(ns re-frame.story.ui.controls
  "Controls panel — args editor, decorator toggles. Per Stage 4
  (rf2-ekai) IMPL-SPEC §4 + §9.4. Per rf2-xi9zk the per-variant mode
  picker moved to the chrome-level toolbar
  (`re-frame.story.ui.toolbar`); the controls panel keeps args /
  decorator sections only.

  ## Args derivation

  Per IMPL-SPEC §9.4 the args editor auto-derives widget shapes from the
  variant's `:argtypes` (an explicit per-arg widget spec) or from the
  Malli schema attached to `:component` (Spec 010 schemas).

  The schema walker covers two tiers:

  - **Scalar forms** — `:string` / `:int` / `:double` / `:boolean` /
    `:keyword` / `[:enum ...]`. Each becomes a single widget.
  - **Collection forms (rf2-agshe)** — `[:map ...]` / `[:vector X]` /
    `[:tuple X Y ...]` / `[:set X]`. Each renders as an expandable group
    whose child rows are recursively derived from the entry schemas.
    Editing a nested control writes through to the cell-override map at
    a *path* anchored on the top-level arg-key, so `{:nest {:k v}}`
    overrides land at `[:cell-overrides variant-id :nest :k]`.

  Author-supplied `:argtypes` overrides the auto-derivation key-by-key
  (per spec/001 §Schema-derivation pipeline).

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
            [re-frame.story.ui.save-variant :as save-variant-ui]
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
   :nested-row  {:display "grid"
                 :grid-template-columns "120px 1fr"
                 :gap "8px"
                 :padding "2px 0"
                 :padding-left "12px"
                 :border-left "1px solid #3a3a3a"
                 :margin-left "4px"
                 :align-items "center"}
   :group-h     {:color "#9cdcfe"
                 :font-weight "bold"
                 :padding "2px 0"
                 :cursor "default"}
   :label       {:color "#9cdcfe"}
   :sublabel    {:color "#bdbdbd"
                 :font-size "10px"}
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
   :rep-button  {:padding "2px 6px"
                 :background "#37373d"
                 :color "#cccccc"
                 :border "1px solid #444"
                 :border-radius "3px"
                 :cursor "pointer"
                 :font-size "10px"
                 :margin-left "4px"}
   :empty       {:color "#9a9a9a" :font-style "italic"}})

;; ---- pure: Malli-schema introspection -----------------------------------

(defn- properties?
  "Is `x` the optional Malli properties map at index 1 of a vector
  schema? Per Malli convention any map at that slot is properties;
  vector entries (child schemas for collections) are never maps."
  [x]
  (map? x))

(defn- schema-op
  "Return the operator symbol of a vector schema (`:map`, `:vector`,
  `:tuple`, `:set`, `:enum`, ...) or nil if `s` is not a vector."
  [s]
  (when (vector? s) (first s)))

(defn- schema-properties
  "Return the optional properties map from a vector schema, or nil."
  [s]
  (when (vector? s)
    (let [x (second s)]
      (when (properties? x) x))))

(defn- schema-children
  "Return the child schemas of a vector schema, skipping the optional
  properties map. For `[:map [:a :string] [:b :int]]` returns
  `([:a :string] [:b :int])`."
  [s]
  (when (vector? s)
    (let [rest* (rest s)]
      (if (properties? (first rest*))
        (rest rest*)
        rest*))))

(defn- map-entry-key
  "Map-entry tuples in Malli have shape `[k props? child]`. Return k."
  [entry]
  (first entry))

(defn- map-entry-schema
  "Map-entry tuples in Malli have shape `[k props? child]`. Return
  child. Skip the optional properties map at index 1."
  [entry]
  (let [r (rest entry)]
    (if (properties? (first r))
      (second r)
      (first r))))

;; ---- pure: argtype inference --------------------------------------------

(declare infer-widget)

(defn- infer-map-widget
  "Recurse into a `[:map [k1 s1] [k2 s2] ...]` schema, producing a
  `:group` widget whose `:entries` is a vector of `{:key :widget}`
  child descriptors. Order is preserved from the schema."
  [schema]
  {:widget  :group
   :kind    :map
   :entries (mapv (fn [entry]
                    {:key    (map-entry-key entry)
                     :widget (infer-widget (map-entry-schema entry))})
                  (schema-children schema))})

(defn- infer-vector-widget
  "Recurse into a `[:vector X]` (or `[:set X]`) schema, producing a
  `:repeater` widget that carries the element schema in `:element` and
  records which collection variant it represents."
  [schema kind]
  {:widget  :repeater
   :kind    kind
   :element (infer-widget (first (schema-children schema)))})

(defn- infer-tuple-widget
  "Recurse into a `[:tuple X Y ...]` schema, producing a `:tuple` widget
  whose `:positions` is the vector of per-index child widgets."
  [schema]
  {:widget    :tuple
   :kind      :tuple
   :positions (mapv infer-widget (schema-children schema))})

(defn infer-widget
  "Given a Malli schema fragment (or simple keyword type), return a
  widget descriptor map. Pure data → data; JVM-testable.

  Scalar shapes:

  - `:string`             → `{:widget :text}`
  - `:int` / `:double`    → `{:widget :number}`
  - `:boolean`            → `{:widget :boolean}`
  - `:keyword`            → `{:widget :text}` (keyword-coercion at edit)
  - `[:enum a b c]`       → `{:widget :select :options [a b c]}`

  Collection shapes (rf2-agshe):

  - `[:map [k1 s1] [k2 s2] ...]`
        → `{:widget :group :kind :map
            :entries [{:key k1 :widget <recur>} ...]}`
  - `[:vector X]`
        → `{:widget :repeater :kind :vector :element <recur on X>}`
  - `[:set X]`
        → `{:widget :repeater :kind :set    :element <recur on X>}`
  - `[:tuple X Y ...]`
        → `{:widget :tuple :kind :tuple :positions [<recur> ...]}`

  Unknown shapes default to `{:widget :text}` — the user can refine
  via the variant's `:argtypes` slot. Per IMPL-SPEC §9.4 + spec/001
  §Schema-derivation pipeline."
  [schema-fragment]
  (cond
    (= :string schema-fragment)  {:widget :text}
    (= :int schema-fragment)     {:widget :number}
    (= :double schema-fragment)  {:widget :number}
    (= :boolean schema-fragment) {:widget :boolean}
    (= :keyword schema-fragment) {:widget :text}

    (vector? schema-fragment)
    (case (schema-op schema-fragment)
      :enum   {:widget :select :options (vec (schema-children schema-fragment))}
      :map    (infer-map-widget schema-fragment)
      :vector (infer-vector-widget schema-fragment :vector)
      :set    (infer-vector-widget schema-fragment :set)
      :tuple  (infer-tuple-widget schema-fragment)
      ;; Unknown vector form — fall back to :text.
      {:widget :text})

    :else
    {:widget :text}))

(defn- infer-value-shape
  "Lightweight fallback used when no schema is on file — classify a
  resolved value by its CLJS shape so the controls panel at least
  renders *something* sensible."
  [v]
  (cond
    (map? v)         {:widget :group :kind :map
                      :entries (mapv (fn [[k v']]
                                       {:key    k
                                        :widget (infer-value-shape v')})
                                     v)}
    (vector? v)      {:widget :repeater :kind :vector
                      :element (or (some-> v first infer-value-shape)
                                   {:widget :text})}
    (set? v)         {:widget :repeater :kind :set
                      :element (or (some-> v first infer-value-shape)
                                   {:widget :text})}
    (string? v)      {:widget :text}
    (boolean? v)     {:widget :boolean}
    (integer? v)     {:widget :number}
    (number? v)      {:widget :number}
    (keyword? v)     {:widget :text}
    :else            {:widget :text}))

(defn resolve-argtypes
  "Build the `{arg-key → widget-spec}` map for a variant. Variant-level
  `:argtypes` wins; otherwise we walk the parent story's `:argtypes`;
  otherwise we infer from the component schema (when registered);
  finally we fall back to inferring from the resolved args' value
  shapes.

  Per rf2-agshe the inference recurses into nested `:map` / `:vector` /
  `:set` / `:tuple` shapes, producing widget descriptors the renderer
  expands into nested rows."
  [variant-id]
  (let [vb      (registrar/handler-meta :variant variant-id)
        story   (args/parent-story-id variant-id)
        sb      (when story (registrar/handler-meta :story story))
        types   (merge (:argtypes sb) (:argtypes vb))
        ;; Prefer an explicit `:schema` slot on the variant/story body
        ;; (forward-compatible — Spec 010 will land an `:rf/schema` slot
        ;; on variants for the auto-derivation path). Fall back to the
        ;; registered :view's `:schema` slot (when reg-view starts
        ;; carrying one). Per spec/001 §Schema-derivation pipeline.
        component-id (or (:component vb) (:component sb))
        component-body (when component-id
                         (registrar/handler-meta :view component-id))
        derive-schema (or (:schema vb)
                          (:schema sb)
                          (:schema component-body))
        schema-entries (when (and (vector? derive-schema)
                                  (= :map (schema-op derive-schema)))
                         (into {}
                               (map (fn [entry]
                                      [(map-entry-key entry)
                                       (map-entry-schema entry)]))
                               (schema-children derive-schema)))
        eff     (args/resolve-args variant-id)]
    (reduce-kv
      (fn [acc k v]
        (if (contains? acc k)
          acc
          (let [from-schema (when-let [s (get schema-entries k)]
                              (infer-widget s))]
            (assoc acc k (or from-schema (infer-value-shape v))))))
      (or types {})
      eff)))

;; ---- widget renderers ----------------------------------------------------

(defn- read-event-value [e]
  (.. e -target -value))

(defn- read-event-checked [e]
  (.. e -target -checked))

(defn- on-change-at-path
  "Write `value` through to `[:cell-overrides variant-id & path]` via
  the shell-state atom. `path` is `[arg-key & sub-path]` where the
  sub-path may be empty for top-level scalars."
  [variant-id path value]
  (state/swap-state! state/set-cell-override variant-id (vec path) value))

(declare arg-widget)

(defn- scalar-widget
  "Render a scalar widget for `widget-spec` whose value lives at `path`
  inside `variant-id`'s args. Path is `[arg-key & sub-path]`."
  [variant-id path value {:keys [widget options]}]
  (case widget
    :text     [:input {:type      "text"
                       :style     (:input styles)
                       :value     (if (nil? value) "" (str value))
                       :on-change (fn [e]
                                    (on-change-at-path
                                      variant-id path
                                      (read-event-value e)))}]
    :number   [:input {:type      "number"
                       :style     (:input styles)
                       :value     (if (nil? value) "" value)
                       :on-change (fn [e]
                                    (let [s (read-event-value e)
                                          v (when (seq s) (js/parseFloat s))]
                                      (on-change-at-path
                                        variant-id path v)))}]
    :boolean  [:input {:type      "checkbox"
                       :checked   (boolean value)
                       :on-change (fn [e]
                                    (on-change-at-path
                                      variant-id path
                                      (read-event-checked e)))}]
    :select   [:select {:value     (str value)
                        :style     (:input styles)
                        :on-change (fn [e]
                                     (on-change-at-path
                                       variant-id path
                                       (read-event-value e)))}
               (for [opt options]
                 ^{:key (str opt)}
                 [:option {:value (str opt)} (str opt)])]
    [:span {:style (:empty styles)}
     (str "unsupported widget " widget)]))

(defn- group-widget
  "Render a `:map`-derived collapsible group. The header sits in its
  own row; each child entry renders as a nested row."
  [variant-id path value {:keys [entries]}]
  [:div
   [:div {:style (:group-h styles)
          :data-controls-group ":map"}
    (str (last path) " {} ")]
   (into [:div]
         (for [{ek :key ew :widget} entries
               :let [child-path  (conj (vec path) ek)
                     child-value (get value ek)]]
           ^{:key (str ek)}
           [:div {:style (:nested-row styles)
                  :data-controls-key (str ek)}
            [:span {:style (:sublabel styles)} (str ek)]
            [arg-widget variant-id child-path child-value ew]]))])

(defn- vector-coerce
  "Normalise a value into a vector. `nil` → `[]`; sets become a stable
  sorted vec; other sequentials become vectors verbatim."
  [v]
  (cond
    (nil? v)         []
    (vector? v)      v
    (set? v)         (vec (sort-by str v))
    (sequential? v)  (vec v)
    :else            [v]))

(defn- default-element-value
  "A sensible empty value for `:repeater`/`:tuple` entries based on the
  element widget shape. Keeps the UI from leaving slots that immediately
  fail validation."
  [widget-spec]
  (case (:widget widget-spec)
    :text    ""
    :number  0
    :boolean false
    :select  (first (:options widget-spec))
    :group   {}
    :repeater (case (:kind widget-spec) :set #{} [])
    :tuple    (mapv default-element-value (:positions widget-spec))
    nil))

(defn- repeater-widget
  "Render a `:vector` (or `:set`) repeater. Each entry gets a nested row
  + an inline `[-]` button. A trailing `[+]` button appends a fresh
  default-valued entry."
  [variant-id path value {:keys [element kind] :as widget-spec}]
  (let [entries (vector-coerce value)
        on-add  (fn []
                  (on-change-at-path
                    variant-id path
                    (let [next-v (conj entries (default-element-value element))]
                      (case kind
                        :set (set next-v)
                        next-v))))
        on-del  (fn [i]
                  (on-change-at-path
                    variant-id path
                    (let [v (vec (concat (subvec entries 0 i)
                                         (subvec entries (inc i))))]
                      (case kind
                        :set (set v)
                        v))))]
    [:div
     [:div {:style (:group-h styles)
            :data-controls-group (str kind)}
      (str (last path) " " (case kind :set "#{}" "[]") " ")
      [:button {:style    (:rep-button styles)
                :data-controls-action "add"
                :on-click (fn [_] (on-add))}
       "[+]"]]
     (into [:div]
           (for [[i entry-value] (map-indexed vector entries)
                 :let [child-path (conj (vec path) i)]]
             ^{:key i}
             [:div {:style (:nested-row styles)
                    :data-controls-index i}
              [:span {:style (:sublabel styles)} (str "[" i "]")]
              [:div
               [arg-widget variant-id child-path entry-value element]
               [:button {:style    (:rep-button styles)
                         :data-controls-action "del"
                         :on-click (fn [_] (on-del i))}
                "[-]"]]]))]))

(defn- tuple-widget
  "Render a `:tuple` — one row per positional element. The arity is
  fixed; no `[+]` / `[-]` affordance. Each position's path is
  `[... i]`."
  [variant-id path value {:keys [positions]}]
  (let [entries (vector-coerce value)]
    [:div
     [:div {:style (:group-h styles)
            :data-controls-group ":tuple"}
      (str (last path) " () ")]
     (into [:div]
           (for [[i pos-widget] (map-indexed vector positions)
                 :let [child-path  (conj (vec path) i)
                       child-value (get entries i)]]
             ^{:key i}
             [:div {:style (:nested-row styles)
                    :data-controls-index i}
              [:span {:style (:sublabel styles)} (str "[" i "]")]
              [arg-widget variant-id child-path child-value pos-widget]]))]))

(defn arg-widget
  "Generic widget dispatcher. `path` is the vector path within the
  variant's args (`[arg-key & sub-keys]`); for top-level scalars the
  path is a 1-vector. The renderer dispatches on `widget-spec`'s
  `:widget` slot — `:group` / `:repeater` / `:tuple` recurse, everything
  else falls through to `scalar-widget`."
  [variant-id path value widget-spec]
  (case (:widget widget-spec)
    :group    [group-widget    variant-id path value widget-spec]
    :repeater [repeater-widget variant-id path value widget-spec]
    :tuple    [tuple-widget    variant-id path value widget-spec]
    [scalar-widget variant-id path value widget-spec]))

;; ---- public components ---------------------------------------------------

(defn args-editor
  "Render the args editor for `variant-id`. Reads the resolved args via
  `args/resolve-args` and walks every key, rendering a widget per the
  inferred argtype. Top-level keys render as flat rows; collection
  argtypes (`:map` / `:vector` / `:set` / `:tuple`) recurse into nested
  rows (rf2-agshe)."
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
         [:div {:style (:row styles)
                :data-controls-arg (str k)}
          [:span {:style (:label styles)} (str k)]
          [arg-widget variant-id [k] v
           (get argtypes k {:widget :text})]]))
     (when (seq (get-in shell [:cell-overrides variant-id]))
       [:button {:style    (:button styles)
                 :on-click (fn [_]
                             (state/swap-state!
                               state/clear-cell-overrides variant-id))}
        "reset overrides"])]))

;; rf2-xi9zk: the controls-panel `mode-picker` is **superseded** by the
;; chrome-level toolbar (`re-frame.story.ui.toolbar`). Modes are
;; chrome-wide now — not a per-variant controls section — so a chrome-
;; level surface is the right home. The controls panel keeps args /
;; decorator sections only.

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
  "The full controls panel — args editor + decorator list + save-layout
  + save-as-variant actions. Per rf2-xi9zk the per-variant mode-picker
  moved to the chrome-level toolbar (`re-frame.story.ui.toolbar`); the
  controls panel keeps args / decorator sections only.

  rf2-one3t: the 'save as new variant' button captures the live canvas
  state (effective args after the five-layer precedence chain) and
  surfaces an EDN `(reg-variant ...)` form in a review-then-commit
  modal — the SB9 story-from-UI parity affordance (per
  spec/005-SOTA-Features §Save current canvas state as variant)."
  [variant-id]
  [:div {:style (:wrap styles)}
   (when variant-id
     [args-editor variant-id])
   (when variant-id
     [decorator-list variant-id])
   [save-variant-ui/save-variant-button variant-id]
   [save-layout-button]])
