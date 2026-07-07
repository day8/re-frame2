(ns re-frame.story.ui.controls
  "Controls panel — args editor, decorator toggles. Per
  `003-Render-Shell.md` §Shell lifecycle + `001-Authoring.md`
  §Controls — schema-derived, zero-`:argtypes`. The per-variant mode
  picker lives on the chrome-level toolbar
  (`re-frame.story.ui.toolbar`); the controls panel keeps args /
  decorator sections only.

  ## Args derivation

  Per `001-Authoring.md` §Controls — schema-derived, zero-`:argtypes`
  the args editor auto-derives widget shapes from the
  variant's `:argtypes` (an explicit per-arg widget spec) or from the
  Malli schema attached to `:component` (Spec 010 schemas).

  The schema walker covers two tiers:

  - **Scalar forms** — `:string` / `:int` / `:double` / `:boolean` /
    `:keyword` / `[:enum ...]`. Each becomes a single widget.
  - **Collection forms** — `[:map ...]` / `[:vector X]` /
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
  toggle is informational.

  ## Inline validation, diff-from-saved, summarise-before-expand

  Per spec/019-Story-UI-Controls-And-View-States §1/§2/§4/§6 the args
  editor distinguishes a committed value that *violates the component's
  Spec 010 schema* from one that merely differs from its saved
  (declared) value:

  - **Inline schema errors** — each arg-key is checked against the
    component's registered schema via the SAME pure
    `re-frame.story.ui.schema-validation/args-violations` walk the
    schema-validation panel uses (no second validator). A violating row
    shows an inline error under its widget; a panel-level banner names
    how many args block render/test proof. Args are checked, not
    fidelity rungs — the boundary in §5 stays intact.

  - **Diff-from-saved + reset** — the 'saved' baseline is the variant's
    resolved args WITHOUT the controls panel's `:cell-overrides`. An arg
    whose effective value drifts from its saved value shows a 'changed'
    dot and a per-arg reset that reverts just that one arg
    (`state/clear-cell-override`); the panel-level 'reset overrides'
    clears them all.

  - **Summarise before expanding** — nested (`:group` / `:repeater` /
    `:tuple`) controls render a one-line summary with a disclosure
    toggle and lazily render their child rows only when expanded (the
    deep-controls perf risk in §4). The expand/collapse flag is
    *component-local ephemeral UI state* (a Reagent atom on the editor's
    Form-2 closure) — it is deliberately NOT written to `:cell-overrides`
    or the shell-state so controls never become hidden panel state
    (§6 acceptance criterion)."
  (:require [clojure.string                    :as str]
            [reagent.core                      :as r]
            [re-frame.story.budgets            :as budgets]
            [re-frame.story.registrar          :as registrar]
            [re-frame.story.args               :as args]
            [re-frame.story.decorators         :as decorators]
            [re-frame.story.malli-schema       :as msu]
            [re-frame.story.view-args          :as view-args]
            [re-frame.story.ui.author-expectations :as author-expectations]
            [re-frame.story.ui.controls-styles :refer [styles]]
            [re-frame.story.ui.save-variant    :as save-variant-ui]
            [re-frame.story.ui.schema-validation :as schema-validation]
            [re-frame.story.ui.state           :as state]
            [re-frame.story.ui.view-state      :as view-state]))

;; Styles live in `re-frame.story.ui.controls-styles` (pure-data leaf,
;; no Reagent dep). Required as `styles` above so the in-file call
;; sites (`(:wrap styles)` etc.) stay textually identical.

;; ---- pure: Malli-schema introspection -----------------------------------
;;
;; Canonical helpers live in `re-frame.story.malli-schema` (a pure
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

  Collection shapes:

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
  via the variant's `:argtypes` slot. Per `001-Authoring.md`
  §Controls — schema-derived, zero-`:argtypes` + §Schema-derivation pipeline."
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
  internal shape. Per /spec/007-Stories.md §argtypes the author uses
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
  slot per /spec/007-Stories.md §argtypes; `normalize-argtypes` translates
  them to the internal `:widget` shape before merge.

  The inference recurses into nested `:map` / `:vector` /
  `:set` / `:tuple` shapes, producing widget descriptors the renderer
  expands into nested rows.

  HOT PATH: `args-editor` is keystroke-sensitive — every
  controls-panel edit re-renders, which re-runs this fn. The optional
  `eff-args` arg threads the caller's already-resolved args through so
  we don't re-run `args/resolve-args` (which itself deep-merges five
  precedence layers and re-reads the registrar). The single-arity
  overload preserves the canonical surface for tests + non-render
  callers; the render path threads its own resolution.

  SCHEMA SOURCE: the auto-derivation schema is
  the COMPILED variant-plan's `[:world :view-args-schema]`, read through
  the ONE shared resolver `view-args/compiled-view-args-schema` (memoized
  per variant + registrar tick). This is the SAME slot the schema-
  validation panel validates against, so a `:rf/props`-declared view
  gets BOTH derived controls AND validation from one source — including
  any `:extends`-inherited / `:compose`-d `:component`."
  ([variant-id]
   (resolve-argtypes variant-id (args/resolve-args variant-id)))
  ([variant-id eff-args]
   (let [vb      (registrar/handler-meta :variant variant-id)
         story   (args/parent-story-id variant-id)
         sb      (when story (registrar/handler-meta :story story))
         types   (merge (normalize-argtypes (:argtypes sb))
                        (normalize-argtypes (:argtypes vb)))
         ;; The view-args (props) schema off the COMPILED plan — the
         ;; single source of truth (`[:world :view-args-schema]`), resolved
         ;; first-match `[:rf/props :schema]` through the shared resolver.
         ;; Per spec/001 §Schema-derivation pipeline + the
         ;; compiled-plan invariant.
         derive-schema (view-args/compiled-view-args-schema variant-id)
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

;; ---- pure: validation + diff-from-saved + summaries ---------------------

(defn violations-by-key
  "Index a `schema-validation/args-violations` vector into a
  `{arg-key → violation}` map so the args-editor can look up a row's
  violation in O(1). The whole-args `::schema-validation/root` violation
  (a non-`:map` top-level schema) keys under `:rf.story.controls/root`
  so a top-level non-map schema failure still surfaces as a panel banner
  without colliding with any real arg-key.

  Pure data → data; JVM-testable. Reuses the SAME validation the
  schema-validation panel computes — there is no second validator."
  [violations]
  (reduce (fn [acc {:keys [key] :as v}]
            (assoc acc (if (= key :re-frame.story.ui.schema-validation/root)
                         :rf.story.controls/root
                         key)
                       v))
          {}
          violations))

(defn arg-changed?
  "True iff `arg-key`'s effective value differs from its saved (declared)
  value. The 'saved' value is the variant's resolved args WITHOUT the
  controls panel's runtime `:cell-overrides` — i.e. what the variant
  would render with no UI edits. Used to surface the per-arg
  changed-from-saved dot + reset affordance. Pure data → bool."
  [eff-args saved-args arg-key]
  (not= (get eff-args arg-key) (get saved-args arg-key)))

(defn summarize-value
  "One-line, non-recursive summary of a collection value for the
  collapsed (summarise-before-expand) nested-control header. Deep
  contents are NOT walked — that is the whole point of summarising
  before expanding (spec/019 §4 deep-controls perf risk). Pure data →
  string.

  - map     → `\"N keys\"`
  - set     → `\"N items\"`
  - vector / seq → `\"N items\"`
  - nil     → `\"empty\"`
  - other   → `pr-str` (a scalar landing under a collection widget)"
  [v]
  (cond
    (nil? v)         "empty"
    (map? v)         (str (count v) (if (= 1 (count v)) " key" " keys"))
    (set? v)         (str (count v) (if (= 1 (count v)) " item" " items"))
    (sequential? v)  (str (count v) (if (= 1 (count v)) " item" " items"))
    :else            (pr-str v)))

;; ---- widget renderers ----------------------------------------------------

(defn- read-event-value [e]
  (.. e -target -value))

(defn- read-event-checked [e]
  (.. e -target -checked))

(defn- on-change-at-path
  "Write `value` through to `[:cell-overrides variant-id & path]` via
  the shell-state atom. `path` is `[arg-key & sub-path]` where the
  sub-path may be empty for top-level scalars.

  Threads the arg-key's current 'saved' value (active-modes applied,
  cell-overrides excluded — the SAME baseline `args-editor` resolves for
  its diff-from-saved affordance) as `set-cell-override`'s vivification
  seed (rf2-mzfh9c). Without it, editing ONE entry of a not-yet-
  overridden `:vector`/`:set` arg would have no way to know its sibling
  entries and would silently drop them; the seed is read fresh on every
  write (cheap — a registry-backed deep-merge, no validation) rather than
  threaded through the render tree, so this stays a one-line change at
  the single choke point every widget's on-change already funnels
  through."
  [variant-id path value]
  (let [shell (state/get-state)
        base  (get (args/resolve-args variant-id {:active-modes (:active-modes shell)})
                   (first path))]
    (state/swap-state! state/set-cell-override variant-id (vec path) value base)))

(declare arg-widget*)

;; Per-widget render fns. Each takes `[variant-id path value spec]` and
;; returns a hiccup form. Factoring out the per-case logic keeps the
;; dispatch in `scalar-widget` data-driven and each renderer a tight
;; ~10-line fn.

(defn- on-string-change
  "Generic text-input on-change handler: writes the raw string value
  through to the cell-override at `path`."
  [variant-id path]
  (fn [e] (on-change-at-path variant-id path (read-event-value e))))

(defn- path-label
  "Derive an accessible name for an input from its `path`. The path tail
  is the user-visible label sibling (rendered in the `:label` span at
  row level); we mirror it as `aria-label` so screen readers announce
  the input by name. The visible span is purely visual
  — without this association screen readers announce 'edit, blank'.

  Nested paths produce a slash-joined breadcrumb (`outer/inner`) so a
  nested input under `:address.street` reads as 'address / street'."
  [path]
  (let [parts (mapv str path)]
    (str/join " / " parts)))

(defn- render-text [variant-id path value _]
  [:input {:type       "text"
           :style      (:input styles)
           :aria-label (path-label path)
           :value      (if (nil? value) "" (str value))
           :on-change  (on-string-change variant-id path)}])

(defn- render-textarea [variant-id path value _]
  [:textarea {:style      (:textarea styles)
              :aria-label (path-label path)
              :value      (if (nil? value) "" (str value))
              :on-change  (on-string-change variant-id path)}])

(defn- render-number [variant-id path value _]
  [:input {:type       "number"
           :style      (:input styles)
           :aria-label (path-label path)
           :value      (if (nil? value) "" value)
           :on-change  (fn [e]
                         (let [s (read-event-value e)
                               v (when (seq s) (js/parseFloat s))]
                           (on-change-at-path variant-id path v)))}])

(defn- render-boolean [variant-id path value _]
  [:input {:type       "checkbox"
           :aria-label (path-label path)
           :checked    (boolean value)
           :on-change  (fn [e]
                         (on-change-at-path variant-id path
                                            (read-event-checked e)))}])

(defn- render-select [variant-id path value {:keys [options]}]
  [:select {:value      (str value)
            :style      (:input styles)
            :aria-label (path-label path)
            :on-change  (on-string-change variant-id path)}
   (for [opt options]
     ^{:key (str opt)}
     [:option {:value (str opt)} (str opt)])])

(defn- render-radio [variant-id path value {:keys [options]}]
  ;; The radio-set wraps each input in a parent <label>, so
  ;; the inner <input type="radio"> already inherits an accessible name
  ;; from the wrapping label's text (the option's value). We add
  ;; role="radiogroup" + aria-label on the container so screen readers
  ;; announce the group's name (the path) before traversing options.
  (into [:div {:style (:radio-row styles)
               :data-controls-radio-group (str (last path))
               :role       "radiogroup"
               :aria-label (path-label path)}]
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
  [:input {:type       "date"
           :style      (:input styles)
           :aria-label (path-label path)
           :value      (if (nil? value) "" (str value))
           :on-change  (fn [e]
                         (let [s (read-event-value e)]
                           (on-change-at-path variant-id path
                                              (when (seq s) s))))}])

(defn- render-color [variant-id path value _]
  [:input {:type       "color"
           :style      (:color-input styles)
           :aria-label (path-label path)
           :value      (if (and (string? value) (seq value))
                         value
                         "#000000")
           :on-change  (on-string-change variant-id path)}])

(def ^:private scalar-renderers
  "Closed scalar-widget vocabulary per /spec/007-Stories.md §argtypes.
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

  Closed control vocabulary per /spec/007-Stories.md §argtypes:
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

;; ---- flat-panel row cap --------------------------------------------------
;;
;; A controls panel whose top-level arg count exceeds
;; `budgets/controls-flat-row-cap` (60) renders the first `cap` rows and a
;; `+N more` expander rather than flooding the panel (spec/018 §10). The
;; reveal flag rides the SAME component-local `:expanded` ratom that drives
;; the nested summarise-before-expand state, under a reserved sentinel so it
;; never collides with a real nested-control path. Pure helpers stay
;; JVM-/data-testable.

(def flat-expanded-sentinel
  "Reserved key the flat-panel-cap reveal flag occupies in the controls
  editor's `:expanded` ratom set. A namespaced keyword (not a vector path)
  so it can never collide with a nested-control path (paths are vectors of
  arg-keys / indices). Public so the gate can assert the bounding contract."
  ::flat-expanded)

(defn bound-arg-rows
  "Pure data → data: bound the sorted top-level arg entries `entries` (a seq
  of `[k v]` pairs) to at most `budgets/controls-flat-row-cap` rows unless
  `expanded?`. Returns `{:shown [...] :hidden <n>}` via the shared
  `budgets/bound-cells` so the controls panel uses the SAME cap-and-page
  idiom (F1) as the sidebar and the variants-grid. JVM-testable."
  [entries expanded?]
  (budgets/bound-cells entries budgets/controls-flat-row-cap (boolean expanded?)))

;; ---- summarise-before-expand --------------------------------------------
;;
;; Nested controls render a one-line summary with a disclosure toggle and
;; lazily render their child rows only when expanded (spec/019 §4 deep-
;; controls perf risk). The expand state is a Reagent atom holding a SET
;; of expanded paths, carried on `opts` — `{:expanded <ratom>}`. It is
;; ephemeral component-local UI state, NEVER `:cell-overrides` / shell-
;; state, so controls don't become hidden panel state (§6).
;;
;; When `opts` carries no `:expanded` ratom (direct `arg-widget` test
;; calls, or any non-render caller) nested controls render fully expanded
;; — the summarise affordance is a render-shell concern, and the pure
;; recursion stays inspectable.

(defn- path-expanded?
  "Is the nested control at `path` expanded? With no `:expanded` ratom on
  `opts` everything is expanded (the non-render / test default)."
  [opts path]
  (if-let [a (:expanded opts)]
    (contains? @a (vec path))
    true))

(defn- toggle-expanded!
  "Flip `path`'s membership in the `:expanded` ratom's set. No-op when no
  ratom is present."
  [opts path]
  (when-let [a (:expanded opts)]
    (swap! a (fn [s]
               (let [p (vec path)]
                 (if (contains? s p) (disj s p) (conj s p)))))))

(defn- disclosure-header
  "Render the group/repeater/tuple header as a disclosure toggle: a
  `▸`/`▾` triangle + the arg label + the open/close delimiter + a one-
  line summary of the collapsed value. `extra` is optional trailing
  hiccup (e.g. the repeater `[+]` button) appended after the summary."
  ([variant-id path value opts group-tag delim]
   (disclosure-header variant-id path value opts group-tag delim nil))
  ([_variant-id path value opts group-tag delim extra]
   (let [open? (path-expanded? opts path)]
     [:div {:style (:group-h styles)
            :data-controls-group group-tag}
      [:button {:style    (:disclosure styles)
                :data-controls-action "toggle-expand"
                :data-controls-expanded (str open?)
                :aria-expanded (str open?)
                :aria-label (str (path-label path)
                                 (if open? " collapse" " expand"))
                :on-click (fn [_] (toggle-expanded! opts path))}
       (str (if open? "▾ " "▸ ") (last path) " " delim)]
      (when-not open?
        [:span {:style (:summary styles)} (summarize-value value)])
      extra])))

(defn- group-widget
  "Render a `:map`-derived collapsible group. The header is a disclosure
  toggle carrying a one-line summary; child rows render only when the
  group is expanded (summarise-before-expand, spec/019 §4)."
  [variant-id path value {:keys [entries]} opts]
  [:div
   (disclosure-header variant-id path value opts ":map" "{}")
   (when (path-expanded? opts path)
     (into [:div]
           (for [{ek :key ew :widget} entries
                 :let [child-path  (conj (vec path) ek)
                       child-value (get value ek)]]
             ^{:key (str ek)}
             [:div {:style (:nested-row styles)
                    :data-controls-key (str ek)}
              [:span {:style (:sublabel styles)} (str ek)]
              (arg-widget* variant-id child-path child-value ew opts)])))])

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

(defn- row-keys-for-entries
  "Return the React-key vector for `entries` at `[variant-id path]`.
  Reads the row-id vector from the shell-state, syncing it to the
  current entry count (append fresh ids when short; truncate when
  long). The sync mutation is done inside `swap-state!` so concurrent
  renders observe a single authoritative state.

  Per rf2-c8kfy — row keys MUST be a stable per-entry identity so
  React reconciles surviving rows in place across a mid-list delete
  (no focus / cursor leakage onto neighbouring rows). The previous
  positional-key shape (`^{:key i}`) caused the focused input's DOM
  node to be reused with the next entry's value after a delete; for
  `:set`-kind repeaters every keystroke triggered a re-sort and the
  bug fired on every keystroke. Same class of fix as rf2-kgn0c /
  rf2-z4fza / rf2-c56hr."
  [variant-id path n]
  (let [path-v (vec path)
        ids    (state/repeater-row-ids (state/get-state) variant-id path-v)]
    (if (= n (count ids))
      (mapv (fn [id] (str "r:" id)) ids)
      (do
        (state/swap-state! state/ensure-repeater-row-ids variant-id path-v n)
        (mapv (fn [id] (str "r:" id))
              (state/repeater-row-ids (state/get-state) variant-id path-v))))))

(defn- repeater-widget
  "Render a `:vector` (or `:set`) repeater. Each entry gets a nested row
  + an inline `[-]` button. A trailing `[+]` button appends a fresh
  default-valued entry.

  Per rf2-c8kfy each row is keyed on a stable monotonic id from the
  shell-state's `:rf.story/repeater-row-ids` slot — NOT on its index.
  Add allocates a fresh id; delete drops the id at position i in
  lockstep with the entry. This pins React-key stability across a
  mid-list delete so focus / cursor on surviving rows is preserved
  (the focused input visually moves with its edit intact)."
  [variant-id path value {:keys [element kind] :as _widget-spec} opts]
  (let [entries  (vector-coerce value)
        path-v   (vec path)
        row-keys (row-keys-for-entries variant-id path-v (count entries))
        on-add   (fn []
                   (state/swap-state!
                     state/append-repeater-row-id variant-id path-v)
                   (on-change-at-path
                     variant-id path
                     (let [next-v (conj entries (default-element-value element))]
                       (case kind
                         :set (set next-v)
                         next-v))))
        on-del   (fn [i]
                   (state/swap-state!
                     state/remove-repeater-row-id variant-id path-v i)
                   (on-change-at-path
                     variant-id path
                     (let [v (vec (concat (subvec entries 0 i)
                                          (subvec entries (inc i))))]
                       (case kind
                         :set (set v)
                         v))))
        add-btn  [:button {:style    (:rep-button styles)
                           :data-controls-action "add"
                           :on-click (fn [_] (on-add))}
                  "[+]"]]
    [:div
     (disclosure-header variant-id path value opts (str kind)
                        (case kind :set "#{}" "[]") add-btn)
     (when (path-expanded? opts path)
       (into [:div]
             (for [[i entry-value] (map-indexed vector entries)
                   :let [child-path (conj (vec path) i)
                         row-key    (nth row-keys i (str "r:?-" i))]]
               ^{:key row-key}
               [:div {:style (:nested-row styles)
                      :data-controls-index i
                      :data-controls-row-key row-key}
                [:span {:style (:sublabel styles)} (str "[" i "]")]
                [:div
                 (arg-widget* variant-id child-path entry-value element opts)
                 [:button {:style    (:rep-button styles)
                           :data-controls-action "del"
                           :on-click (fn [_] (on-del i))}
                  "[-]"]]])))]))

(defn- tuple-widget
  "Render a `:tuple` — one row per positional element. The arity is
  fixed; no `[+]` / `[-]` affordance. Each position's path is
  `[... i]`.

  Per rf2-c8kfy each row is keyed `t:<i>`. Tuple arity is fixed
  (no add / delete affordance) so positional identity IS stable
  identity — the namespacing prefix is for discipline-consistency
  with the repeater fix and the rf2-kgn0c sibling family, not to
  fix a focus-leak (tuple slots can't reshuffle)."
  [variant-id path value {:keys [positions]} opts]
  (let [entries (vector-coerce value)]
    [:div
     (disclosure-header variant-id path value opts ":tuple" "()")
     (when (path-expanded? opts path)
       (into [:div]
             (for [[i pos-widget] (map-indexed vector positions)
                   :let [child-path  (conj (vec path) i)
                         child-value (get entries i)
                         row-key     (str "t:" i)]]
               ^{:key row-key}
               [:div {:style (:nested-row styles)
                      :data-controls-index i
                      :data-controls-row-key row-key}
                [:span {:style (:sublabel styles)} (str "[" i "]")]
                (arg-widget* variant-id child-path child-value pos-widget opts)])))]))

(defn arg-widget*
  "Internal widget dispatcher carrying the render-shell `opts`
  (currently `{:expanded <ratom>}` for summarise-before-expand). `path`
  is the vector path within the variant's args (`[arg-key & sub-keys]`);
  for top-level scalars the path is a 1-vector. Dispatches on
  `widget-spec`'s `:widget` slot — `:group` / `:repeater` / `:tuple`
  recurse (threading `opts`), everything else falls through to
  `scalar-widget`."
  [variant-id path value widget-spec opts]
  (case (:widget widget-spec)
    ;; Collection widgets are plain hiccup-returning fns (no local
    ;; state); calling them directly keeps the disclosure header +
    ;; nested rows in one inline tree (testable + no extra reactive
    ;; boundary). Scalars stay a `[scalar-widget ...]` component vector
    ;; — the existing nested-dispatch test asserts on that shape.
    :group    (group-widget    variant-id path value widget-spec opts)
    :repeater (repeater-widget variant-id path value widget-spec opts)
    :tuple    (tuple-widget    variant-id path value widget-spec opts)
    [scalar-widget variant-id path value widget-spec]))

(defn arg-widget
  "Generic widget dispatcher. `path` is the vector path within the
  variant's args (`[arg-key & sub-keys]`); for top-level scalars the
  path is a 1-vector. The renderer dispatches on `widget-spec`'s
  `:widget` slot — `:group` / `:repeater` / `:tuple` recurse, everything
  else falls through to `scalar-widget`.

  The 4-arity is the canonical surface for tests + non-render callers —
  it threads an empty `opts`, so nested controls render fully expanded
  (no `:expanded` ratom) and the recursion stays inspectable. The
  render path uses `arg-widget*` to thread the summarise-before-expand
  state."
  [variant-id path value widget-spec]
  (arg-widget* variant-id path value widget-spec nil))

;; ---- public components ---------------------------------------------------

(defn validation-banner
  "Panel-level banner naming how many committed args violate the
  component's Spec 010 schema. Per spec/019 §1/§6 invalid committed
  values block render/test PROOF — the banner is the honest 'this state
  is not a valid render' signal that sits above the rows. Returns nil
  (rendered nothing) when `n` is 0. A plain hiccup-returning fn so the
  banner's `:data-controls-validation` marker renders inline."
  [n]
  (when (pos? n)
    [:div {:style     (:banner styles)
           :role      "alert"
           :data-controls-validation "invalid"
           :data-controls-violation-count (str n)}
     (str "⚠ " n (if (= 1 n) " arg violates" " args violate")
          " the component's schema — fix before claiming a valid render/test.")]))

(defn args-row
  "One top-level arg row: the label (with a changed-from-saved dot +
  per-arg reset when the arg drifts from its saved value), the widget,
  and an inline schema error under the widget when the committed value
  violates the component's Spec 010 schema.

  A plain hiccup-returning fn (NOT a nested Reagent component) so the
  args-editor renders the row's `:data-controls-*` markers + the diff /
  reset / inline-error affordances inline in one tree — the per-row
  state it needs (`violation` / `changed?` / `overridden?`) is already
  computed by the editor, so there is no reactive boundary to draw here.

  `violation` is the per-key entry from `violations-by-key` (or nil).
  `changed?` is the diff-from-saved flag (drives the amber dot).
  `overridden?` is true when a runtime override exists at this arg-key
  (drives the per-arg reset — a same-value override is still resettable).
  `opts` carries the summarise-before-expand ratom."
  [variant-id k value widget-spec {:keys [violation changed? overridden? opts]}]
  [:div {:style                  (:row styles)
         :data-controls-arg      (str k)
         :data-controls-changed  (str (boolean changed?))
         :data-controls-invalid  (str (some? violation))}
   [:span {:style (:label styles)}
    (str k)
    (when (or changed? overridden?)
      [:span {:style (:diff-row styles)}
       (when changed?
         [:span {:style (:diff-dot styles) :title "changed from saved"} " ●"])
       [:button {:style    (:reset-arg styles)
                 :data-controls-action "reset-arg"
                 :aria-label (str "reset " k " to saved")
                 :on-click (fn [_]
                             (state/swap-state!
                               state/clear-cell-override variant-id k))}
        "reset"]])]
   [:div
    (arg-widget* variant-id [k] value widget-spec opts)
    (when violation
      [:div {:style (:error styles)
             :data-controls-error (str k)}
       (str "schema: " (let [expl (schema-validation/format-explain (:explain violation))]
                         (if (seq expl)
                           expl
                           (str "does not match " (pr-str (:schema violation))))))])]])

(defn args-editor
  "Render the args editor for `variant-id`. Reads the resolved args via
  `args/resolve-args` and walks every key, rendering a widget per the
  inferred argtype. Top-level keys render as flat rows; collection
  argtypes (`:map` / `:vector` / `:set` / `:tuple`) recurse into nested
  rows (rf2-agshe).

  Per rf2-ba86n.5 this is a Form-2 component: the outer closure holds the
  `:expanded` ratom — a SET of expanded nested-control paths — so
  summarise-before-expand state is component-local ephemeral UI state,
  NOT `:cell-overrides` / shell-state (controls must not become hidden
  panel state, spec/019 §6). The inner fn:

  - resolves the EFFECTIVE args (with `:cell-overrides`) and the SAVED
    baseline (without) so it can flag per-arg diff-from-saved;
  - runs the SAME `schema-validation/args-violations` walk the schema-
    validation panel uses, indexes it by key, and surfaces an inline
    error per violating row + a panel banner;
  - keeps args a control surface, not a fidelity rung — every value
    here is an explicit view input.

  FLAT-PANEL CAP (rf2-ba86n.18 / C2): a panel whose top-level arg count
  exceeds `budgets/controls-flat-row-cap` (60) renders the first `cap` rows
  and a `+N more` expander rather than flooding the panel (spec/018 §10 —
  cap or page; fail by summarizing, not flooding). The reveal flag is the
  SAME component-local ephemeral `expanded` ratom that drives the nested
  summarise-before-expand state, keyed under the reserved
  `::flat-expanded` sentinel so it never collides with a real nested-control
  path and is never written to `:cell-overrides` / shell-state (controls
  must not become hidden panel state, spec/019 §6). The validation banner
  still counts ALL violations (capped or not — the honest 'N args block
  proof' signal is never paged away).

  HOT PATH (rf2-wb4y3): every keystroke in a control row writes through
  `:cell-overrides`, the shell-state ratom re-renders this component,
  which re-runs the whole derivation. We resolve args ONCE here and
  thread the result into `resolve-argtypes` — without the thread the
  resolution ran twice (once here, once inside `resolve-argtypes`'s
  fallback-inference branch). Same precedence chain, no duplicated work.
  The saved-baseline resolution adds one more pass; it is cheap (a five-
  layer deep-merge over registry reads, no validation)."
  [_variant-id]
  ;; Component-local expanded-set (a SET of expanded nested-control
  ;; paths). Ephemeral UI state — never persisted, never shell-state, so
  ;; controls don't become hidden panel state (§6). Path-keyed, so it
  ;; degrades harmlessly across a variant switch (a path absent in the
  ;; new variant simply matches no control); deliberately not reset on
  ;; switch — re-expanding the same path is cheap and remembering the
  ;; user's disclosure choice across sibling variants is the kinder UX.
  (let [expanded (r/atom #{})]
    (fn [variant-id]
      (let [shell        @state/shell-state-atom
            overrides    (get-in shell [:cell-overrides variant-id])
            eff-args     (args/resolve-args
                           variant-id
                           {:active-modes   (:active-modes shell)
                            :cell-overrides overrides})
            saved-args   (args/resolve-args
                           variant-id
                           {:active-modes (:active-modes shell)})
            argtypes     (resolve-argtypes variant-id eff-args)
            schema       (schema-validation/resolve-component-schema variant-id)
            vfns         (schema-validation/validator-fns)
            viols        (schema-validation/args-violations eff-args schema vfns)
            viols-by-key (violations-by-key viols)
            opts         {:expanded expanded}]
        [:div {:style (:section styles)}
         [:div {:style (:section-h styles)} "Args"]
         (validation-banner (count viols))
         (if (empty? eff-args)
           [:div {:style (:empty styles)} "no args resolved"]
           ;; rf2-ba86n.18 / C2 — flat-panel row cap. Bound the sorted
           ;; top-level rows at `controls-flat-row-cap` (60) with a
           ;; `+N more` expander rather than flooding the panel. The reveal
           ;; flag rides the component-local `expanded` ratom under the
           ;; reserved `flat-expanded-sentinel` so it stays ephemeral UI
           ;; state (never `:cell-overrides` / shell-state, spec/019 §6).
           (let [flat-open? (contains? @expanded flat-expanded-sentinel)
                 {:keys [shown hidden]} (bound-arg-rows (sort-by key eff-args)
                                                        flat-open?)]
             ;; Materialise the rows into the parent `[:div]` vector with
             ;; `into` rather than nesting a bare `(for …)` lazy seq:
             ;; the row widgets deref the shell-state ratom, and a lazy
             ;; seq that derefs during render trips Reagent's "Reactive
             ;; deref not supported in lazy seq" warning AND leaves nested
             ;; group rows unrealised. `args-row` is a plain hiccup-
             ;; returning fn (not a component), so reader `^{:key …}`
             ;; metadata would land on the call form and be discarded —
             ;; stamp the key onto the RETURNED vector via `with-meta`.
             (cond-> (into [:div]
                           (map (fn [[k v]]
                                  (with-meta
                                    (args-row variant-id k v
                                              (get argtypes k {:widget :text})
                                              {:violation   (get viols-by-key k)
                                               :changed?    (arg-changed? eff-args saved-args k)
                                               :overridden? (contains? overrides k)
                                               :opts        opts})
                                    {:key k})))
                           shown)
               (pos? hidden)
               (conj
                 [:button {:style       (:more styles)
                           :type        "button"
                           :data-controls-more "true"
                           :data-controls-hidden (str hidden)
                           :aria-label  (str "Show " hidden
                                             " more control rows")
                           :on-click    (fn [_]
                                          (swap! expanded conj
                                                 flat-expanded-sentinel))}
                  (str "+" hidden " more…")]))))
         (when (seq overrides)
           [:button {:style    (:button styles)
                     :data-controls-action "reset-all"
                     :on-click (fn [_]
                                 (state/swap-state!
                                   state/clear-cell-overrides variant-id))}
            "reset overrides"])]))))

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
  (let [shell (deref state/shell-state-atom)
        ;; rf2-eyrpr — thread the per-run opts into `resolve-decorators` so
        ;; the plan recompile substitutes `[:arg]` keys resolvable only
        ;; through a mode / cell layer (mirrors the args panel's resolve).
        pack  (decorators/resolve-decorators
                variant-id
                {:active-modes   (:active-modes shell)
                 :cell-overrides (get-in shell [:cell-overrides variant-id])})
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
  spec/005-SOTA-Features §Save current canvas state as variant).

  rf2-ba86n.12: the 'add expectations…' button authors EXPECTATIONS onto
  the story (app-db / sub / DOM / schema / a11y), shows the runner cost /
  `:cannot-run` BEFORE save, and emits an `:assertions` reg-variant form.
  DISTINCT from save-current-state (state authoring) and failure promotion
  (a captured artifact) — spec/021 §S5.

  rf2-ba86n.7: the View-State section (`view-state/view-state-section`)
  sits BELOW the args editor — the orthogonal fidelity-ladder channel
  (the three labelled rungs + source/provenance + the honesty guardrail
  + the upgrade path). Args are an explicit CONTROL channel (above), NOT
  a fidelity rung; the View-State section is the rung surface. The order
  reads top-down: explicit inputs → state fidelity → decorators →
  authoring actions (spec/019 §1 control-group order)."
  [variant-id]
  [:div {:style (:wrap styles)}
   (when variant-id
     [args-editor variant-id])
   (when variant-id
     [view-state/view-state-section variant-id])
   (when variant-id
     [decorator-list variant-id])
   [save-variant-ui/save-variant-button variant-id]
   [author-expectations/author-expectations-button variant-id]])
