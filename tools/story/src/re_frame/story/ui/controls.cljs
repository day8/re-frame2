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
  disable-by-name routing through `resolve-decorators` is a future
  refinement when the decorator stack grows more complex; today the
  toggle is informational."
  (:require [re-frame.story.registrar          :as registrar]
            [re-frame.story.args               :as args]
            [re-frame.story.decorators         :as decorators]
            [re-frame.story.malli-schema-utils :as msu]
            [re-frame.story.ui.controls-styles :refer [styles]]
            [re-frame.story.ui.save-variant    :as save-variant-ui]
            [re-frame.story.ui.state           :as state]))

;; Styles live in `re-frame.story.ui.controls-styles` (pure-data leaf,
;; no Reagent dep). Required as `styles` above so the in-file call
;; sites (`(:wrap styles)` etc.) stay textually identical.

;; ---- pure: Malli-schema introspection -----------------------------------
;;
;; Canonical helpers live in `re-frame.story.malli-schema-utils` (a pure
;; leaf ns shared with `schema-validation`). Aliased privately here so
;; in-file call sites stay textually identical.

(def ^:private properties?      msu/properties?)
(def ^:private schema-op        msu/schema-op)
(def ^:private schema-properties msu/schema-properties)
(def ^:private schema-children  msu/schema-children)
(def ^:private map-entry-key    msu/map-entry-key)
(def ^:private map-entry-schema msu/map-entry-schema)

;; ---- pure: argtype inference --------------------------------------------

;; Two complementary widget-derivation paths feed into the same widget
;; descriptor vocabulary. `infer-widget` walks a Malli schema fragment
;; (the preferred / schema-driven path); `infer-value-shape` walks a
;; live CLJS value (the fallback for variants without a registered
;; schema). Both produce the same descriptor maps so the renderer can
;; dispatch on `:widget` without caring how the descriptor was derived.

(declare infer-widget infer-value-shape)

(defn- schema-map-entries->descriptors
  "Walk a `[:map [k1 s1] [k2 s2] ...]` schema's child entries, applying
  `infer-widget` to each entry's child schema. Used to construct the
  `:entries` vector of a `:group` descriptor."
  [schema]
  (mapv (fn [entry]
          {:key    (map-entry-key entry)
           :widget (infer-widget (map-entry-schema entry))})
        (schema-children schema)))

(defn- value-map-entries->descriptors
  "Walk a live CLJS map's entries, applying `infer-value-shape` to each
  value. Used to construct the `:entries` vector of a `:group`
  descriptor when no schema is on file."
  [m]
  (mapv (fn [[k v]]
          {:key    k
           :widget (infer-value-shape v)})
        m))

(defn- group-descriptor
  "Construct a `:map`-flavoured `:group` widget descriptor."
  [entries]
  {:widget :group :kind :map :entries entries})

(defn- repeater-descriptor
  "Construct a `:vector`- or `:set`-flavoured `:repeater` widget
  descriptor with `element` as the per-entry sub-widget."
  [kind element]
  {:widget :repeater :kind kind :element element})

(defn- tuple-descriptor
  "Construct a `:tuple` widget descriptor whose `:positions` is the
  vector of per-index sub-widgets."
  [positions]
  {:widget :tuple :kind :tuple :positions positions})

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
      :map    (group-descriptor (schema-map-entries->descriptors schema-fragment))
      :vector (repeater-descriptor :vector (infer-widget (first (schema-children schema-fragment))))
      :set    (repeater-descriptor :set    (infer-widget (first (schema-children schema-fragment))))
      :tuple  (tuple-descriptor (mapv infer-widget (schema-children schema-fragment)))
      ;; Unknown vector form — fall back to :text.
      {:widget :text})

    :else
    {:widget :text}))

(defn- infer-value-shape
  "Lightweight fallback used when no schema is on file — classify a
  resolved value by its CLJS shape so the controls panel at least
  renders *something* sensible. Produces the same widget descriptor
  vocabulary as `infer-widget` (schema-driven path)."
  [v]
  (cond
    (map? v)     (group-descriptor (value-map-entries->descriptors v))
    (vector? v)  (repeater-descriptor :vector
                                       (or (some-> v first infer-value-shape)
                                           {:widget :text}))
    (set? v)     (repeater-descriptor :set
                                       (or (some-> v first infer-value-shape)
                                           {:widget :text}))
    (string? v)  {:widget :text}
    (boolean? v) {:widget :boolean}
    (integer? v) {:widget :number}
    (number? v)  {:widget :number}
    (keyword? v) {:widget :text}
    :else        {:widget :text}))

(defn normalize-argtype-spec
  "Translate a single author-supplied argtype-spec to the canonical
  internal shape. Per spec/007-Stories.md §argtypes the author uses
  `:control` as the slot name (a 'control' is what the user sees in
  the UI). Internal dispatch (`scalar-widget`, `arg-widget`) is on
  `:widget`. This fn bridges the two — `:control` wins, then `:widget`
  as the legacy alias.

  Pure data → data; JVM-testable. Idempotent: a spec already keyed on
  `:widget` round-trips unchanged."
  [spec]
  (cond
    (not (map? spec))      spec
    (contains? spec :control)
    (-> spec
        (assoc :widget (:control spec))
        (dissoc :control))
    :else spec))

(defn normalize-argtypes
  "Map `normalize-argtype-spec` over the values of an `:argtypes` map.
  Returns nil for nil input so callers can `merge` safely."
  [argtypes]
  (when (map? argtypes)
    (reduce-kv (fn [acc k v] (assoc acc k (normalize-argtype-spec v)))
               {}
               argtypes)))

(defn resolve-argtypes
  "Build the `{arg-key → widget-spec}` map for a variant. Variant-level
  `:argtypes` wins; otherwise we walk the parent story's `:argtypes`;
  otherwise we infer from the component schema (when registered);
  finally we fall back to inferring from the resolved args' value
  shapes.

  Author-supplied `:argtypes` carry the canonical spec-level `:control`
  slot per spec/007-Stories.md §argtypes; `normalize-argtypes` translates
  them to the internal `:widget` shape before merge.

  Per rf2-agshe the inference recurses into nested `:map` / `:vector` /
  `:set` / `:tuple` shapes, producing widget descriptors the renderer
  expands into nested rows.

  HOT PATH (rf2-wb4y3): `args-editor` is keystroke-sensitive — every
  controls-panel edit re-renders, which re-runs this fn. The optional
  `eff-args` arg threads the caller's already-resolved args through so
  we don't re-run `args/resolve-args` (which itself deep-merges five
  precedence layers and re-reads the registrar). The single-arity
  overload preserves the canonical surface for tests + non-render
  callers; the render path threads its own resolution."
  ([variant-id]
   (resolve-argtypes variant-id (args/resolve-args variant-id)))
  ([variant-id eff-args]
   (let [vb      (registrar/handler-meta :variant variant-id)
         story   (args/parent-story-id variant-id)
         sb      (when story (registrar/handler-meta :story story))
         types   (merge (normalize-argtypes (:argtypes sb))
                        (normalize-argtypes (:argtypes vb)))
         ;; Prefer an explicit `:schema` slot on the variant/story body
         ;; (forward-compatible — Spec 010 will land an `:rf/schema`
         ;; slot on variants for the auto-derivation path). Fall back
         ;; to the registered :view's `:schema` slot (when reg-view
         ;; starts carrying one). Per spec/001 §Schema-derivation pipeline.
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
                                (schema-children derive-schema)))]
     (reduce-kv
       (fn [acc k v]
         (if (contains? acc k)
           acc
           (let [from-schema (when-let [s (get schema-entries k)]
                               (infer-widget s))]
             (assoc acc k (or from-schema (infer-value-shape v))))))
       (or types {})
       eff-args))))

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

;; Per-widget render fns. Each takes `[variant-id path value spec]` and
;; returns a hiccup form. Factoring out the per-case logic keeps the
;; dispatch in `scalar-widget` data-driven and each renderer a tight
;; ~10-line fn.

(defn- on-string-change
  "Generic text-input on-change handler: writes the raw string value
  through to the cell-override at `path`."
  [variant-id path]
  (fn [e] (on-change-at-path variant-id path (read-event-value e))))

(defn- render-text [variant-id path value _]
  [:input {:type      "text"
           :style     (:input styles)
           :value     (if (nil? value) "" (str value))
           :on-change (on-string-change variant-id path)}])

(defn- render-textarea [variant-id path value _]
  [:textarea {:style     (:textarea styles)
              :value     (if (nil? value) "" (str value))
              :on-change (on-string-change variant-id path)}])

(defn- render-number [variant-id path value _]
  [:input {:type      "number"
           :style     (:input styles)
           :value     (if (nil? value) "" value)
           :on-change (fn [e]
                        (let [s (read-event-value e)
                              v (when (seq s) (js/parseFloat s))]
                          (on-change-at-path variant-id path v)))}])

(defn- render-boolean [variant-id path value _]
  [:input {:type      "checkbox"
           :checked   (boolean value)
           :on-change (fn [e]
                        (on-change-at-path variant-id path
                                           (read-event-checked e)))}])

(defn- render-select [variant-id path value {:keys [options]}]
  [:select {:value     (str value)
            :style     (:input styles)
            :on-change (on-string-change variant-id path)}
   (for [opt options]
     ^{:key (str opt)}
     [:option {:value (str opt)} (str opt)])])

(defn- render-radio [variant-id path value {:keys [options]}]
  (into [:div {:style (:radio-row styles)
               :data-controls-radio-group (str (last path))}]
        (for [opt options]
          ^{:key (str opt)}
          [:label {:style (:radio-label styles)}
           [:input {:type      "radio"
                    :name      (str variant-id "/" (pr-str path))
                    :value     (str opt)
                    :checked   (= (str value) (str opt))
                    :on-change (fn [_]
                                 (on-change-at-path variant-id path opt))}]
           (str opt)])))

(defn- render-date [variant-id path value _]
  [:input {:type      "date"
           :style     (:input styles)
           :value     (if (nil? value) "" (str value))
           :on-change (fn [e]
                        (let [s (read-event-value e)]
                          (on-change-at-path variant-id path
                                             (when (seq s) s))))}])

(defn- render-color [variant-id path value _]
  [:input {:type      "color"
           :style     (:color-input styles)
           :value     (if (and (string? value) (seq value))
                        value
                        "#000000")
           :on-change (on-string-change variant-id path)}])

(def ^:private scalar-renderers
  "Closed scalar-widget vocabulary per spec/007-Stories.md §argtypes.
  Each renderer takes `[variant-id path value spec]` and returns hiccup.
  An unknown `:widget` tag falls through to the visible fallback span."
  {:text     render-text
   :textarea render-textarea
   :number   render-number
   :boolean  render-boolean
   :select   render-select
   :radio    render-radio
   :date     render-date
   :color    render-color})

(defn scalar-widget
  "Render a scalar widget for `widget-spec` whose value lives at `path`
  inside `variant-id`'s args. Path is `[arg-key & sub-path]`.

  Closed control vocabulary per spec/007-Stories.md §argtypes:
  `:text` / `:textarea` / `:number` / `:boolean` / `:select` / `:radio`
  / `:date` / `:color`. Unknown widget tags degrade to a visible
  fallback span (the author can refine via `:argtypes`).

  Public (rather than private) so tests can invoke it directly and
  inspect the rendered hiccup without going through Reagent."
  [variant-id path value {:keys [widget] :as widget-spec}]
  (if-let [renderer (scalar-renderers widget)]
    (renderer variant-id path value widget-spec)
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
    :text     ""
    :textarea ""
    :number   0
    :boolean  false
    :select   (first (:options widget-spec))
    :radio    (first (:options widget-spec))
    :date     nil
    :color    "#000000"
    :group    {}
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
  rows (rf2-agshe).

  HOT PATH (rf2-wb4y3): every keystroke in a control row writes through
  `:cell-overrides`, the shell-state ratom re-renders this component,
  which re-runs the whole derivation. We resolve args ONCE here and
  thread the result into `resolve-argtypes` — without the thread the
  resolution ran twice (once here, once inside `resolve-argtypes`'s
  fallback-inference branch). Same precedence chain, no duplicated work."
  [variant-id]
  (let [shell      @state/shell-state-atom
        eff-args   (args/resolve-args
                     variant-id
                     {:active-modes (:active-modes shell)
                      :cell-overrides
                      (get-in shell [:cell-overrides variant-id])})
        argtypes   (resolve-argtypes variant-id eff-args)]
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

(defn panel
  "The full controls panel — args editor + decorator list + save-as-variant
  action. Per rf2-xi9zk the per-variant mode-picker moved to the
  chrome-level toolbar (`re-frame.story.ui.toolbar`); the controls panel
  keeps args / decorator sections only.

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
   [save-variant-ui/save-variant-button variant-id]])
