(ns re-frame.story.ui.schema-validation
  "Schema-validation panel — live Spec 010 boundary-failure surface per
  variant. Per Story SOTA audit F-N / rf2-dvue.

  ## What it is

  When a variant's `:component` (a registered `:view`) carries a Spec
  010 schema (Malli or whatever validator is installed), this panel
  surfaces two streams in one place:

    1. **Args violations** — given the variant's resolved args + the
       component's registered schema, walks every `:map` entry and
       reports per-key conformance. The user gets an at-a-glance
       'these args don't match the schema' view that complements the
       Controls panel's widget-derivation.

    2. **Trace violations** — every `:rf.error/schema-validation-failure`
       trace event scoped to the variant's frame (per Spec 010
       §Validation timing — events at boundary, sub returns, cofx
       injections, app-db post-handler slices). Boundary validation
       runs even in production, so the panel is the one place a
       developer reading a Story sees 'this variant is silently
       violating its schema right now'.

  ## Differentiator vs Storybook 8

  Storybook 8 has no schema-introspection equivalent. JSON Schema /
  PropTypes are descriptive metadata, not runtime conformance — Story
  consumes the same `:spec` slot the framework's dev-mode validator
  consumes and renders failures from the live trace bus. The reader
  sees 'the args you're rendering this variant with violate the
  component's contract' immediately.

  ## Schema lookup

  The panel looks for the component schema in this order (first match
  wins):

    1. The variant body's `:schema` slot — explicit per-variant
       override (forward-compatible with the `:rf/schema` Spec 010
       slot per IMPL-SPEC §9.4).
    2. The parent story body's `:schema` slot — story-wide schema.
    3. The framework registrar's `:view` metadata for the variant's
       `:component`, looking at `:spec` (Spec 010 canonical) then
       `:schema` (legacy / Story-only alias).

  When no schema is on file, the args section reports 'no schema
  registered' and the trace section still surfaces any
  `:rf.error/schema-validation-failure` events emitted against the
  frame (e.g. from `reg-app-schema` registrations the variant set up,
  or from event / cofx specs on dispatched events).

  ## Pure / impure split

  This namespace is `.cljc` so the pure projection helpers
  (`schema-validation-event?`, `project-failure`, `project-failures`,
  `args-violations`, `format-explain`) run under both the JVM unit-
  test target and the CLJS node-test build. The Reagent rendering,
  the panel registration, and the validator lookup-via-late-bind live
  under `:cljs`.

  ## Elision

  Production builds with `re-frame.story.config/enabled?` false skip
  the `install!` call (per `re-frame.story.ui.panels/install-
  canonical-panels!`'s `(when enabled? ...)` gate); the require graph
  from the disabled shell is DCE-pruned so the panel never reaches
  prod bundles. The pure helpers stay in the bundle but never run.

  ## Panel registry slot

  Registers as `:rf.story.panel/schema-validation` via
  `reg-story-panel*`, placement `:right`. The render view is
  `:rf.story.panel/schema-validation-view`, registered against the
  framework view registry."
  (:require [clojure.string :as str]
            [re-frame.story.malli-schema-utils :as msu]
            #?@(:cljs [[reagent.core              :as r]
                       [re-frame.core             :as rf]
                       [re-frame.late-bind        :as late-bind]
                       [re-frame.story.args       :as args]
                       [re-frame.story.config     :as config]
                       [re-frame.story.registrar  :as story-registrar]
                       [re-frame.story.ui.trace   :as trace]
                       [re-frame.registrar        :as framework-registrar]])))

;; ---- pure: classify a trace event ---------------------------------------

(defn schema-validation-event?
  "True iff `ev` (a raw trace event from the buffer) is a Spec 010
  schema-validation failure — `:op-type :error` +
  `:operation :rf.error/schema-validation-failure`. Pure data → bool;
  JVM-testable."
  [{:keys [op-type operation] :as _ev}]
  (boolean
    (and (= op-type :error)
         (= operation :rf.error/schema-validation-failure))))

;; ---- pure: project a failure into a row --------------------------------

(defn project-failure
  "Project one raw `:rf.error/schema-validation-failure` trace event
  into a row the panel renders. Per Spec 010 §Per-step recovery the
  failing event's `:tags` carries:

    - `:where` — `:event` / `:sub-return` / `:cofx` / `:app-db` /
                 `:fx-args` (per Spec 010 §Validation order).
    - `:failing-id` / `:event-id` / `:sub-id` / `:cofx-id` / `:fx-id`
                 — the id of the artefact whose `:spec` failed.
    - `:received` — the actual value that failed (event vector / value /
                    coeffect map / slice).
    - `:explain` — the validator's explanation (Malli explain map on
                   the CLJS reference; other shapes on other ports).
    - `:path` (when present, for app-db failures) — the failing leaf
                path (registered root + Malli explainer's value-
                navigation suffix per rf2-oh4se). The registration
                anchor itself rides on `:registered-path` when tooling
                needs it.
    - `:frame` — the frame id the failure fired in.
    - `:recovery` — the spec-defined recovery posture.

  Output row shape:

      {:id          <int>              ;; the trace event's :id (stable per-process key)
       :time        <ms-since-epoch>   ;; from the trace event's :time
       :where       <kw>               ;; :event / :sub-return / :cofx / :app-db / :fx-args
       :failing-id  <kw|nil>           ;; the artefact id whose :spec failed
       :path        <vector|nil>       ;; only for app-db failures
       :received    <any>              ;; the rejected value
       :explain     <any|nil>          ;; the validator's explanation
       :recovery    <kw|nil>           ;; the spec-defined recovery
       :raw         <trace-event>}     ;; the full event for trace deep-dives

  Pure data → data; JVM-testable."
  [{:keys [tags id time recovery] :as ev}]
  {:id         id
   :time       time
   :where      (:where tags)
   :failing-id (or (:failing-id tags)
                   (:event-id   tags)
                   (:sub-id     tags)
                   (:cofx-id    tags)
                   (:fx-id      tags))
   :path       (:path     tags)
   :received   (:received tags)
   :explain    (:explain  tags)
   :recovery   (or recovery (:recovery tags))
   :raw        ev})

(defn project-failures
  "Filter `events` (a seq of raw trace events from the buffer) to the
  schema-validation-failure subset and project each one into a row.
  Returns a vector in chronological order (oldest first). Pure data
  → data; JVM-testable."
  [events]
  (into []
        (comp (filter schema-validation-event?)
              (map project-failure))
        events))

;; ---- pure: Malli walk over `:map` schemas ------------------------------
;;
;; Canonical helpers live in `re-frame.story.malli-schema-utils` (a pure
;; leaf ns shared with `controls`). Aliased privately here so in-file
;; call sites stay textually identical.

(def ^:private properties?      msu/properties?)
(def ^:private schema-op        msu/schema-op)
(def ^:private schema-children  msu/schema-children)
(def ^:private map-entry-key    msu/map-entry-key)
(def ^:private map-entry-schema msu/map-entry-schema)

(defn map-schema?
  "True iff `schema` is a Malli `[:map ...]` vector form. The arg-
  walker only descends into top-level maps — every other shape is
  surfaced as a whole-value violation under the synthetic `::root`
  arg-key."
  [schema]
  (and (vector? schema)
       (= :map (schema-op schema))))

(defn map-entries
  "Return the `[k child-schema]` pairs of a `[:map ...]` schema, in
  declared order. Skips the optional properties map at index 1. Pure
  data → seq; JVM-testable."
  [schema]
  (when (map-schema? schema)
    (mapv (fn [e] [(map-entry-key e) (map-entry-schema e)])
          (schema-children schema))))

;; ---- pure: args-violation projection ------------------------------------

(defn args-violations
  "Given a resolved args map, a component schema, and a validator fn
  pair (`{:validate (fn [schema value] truthy?) :explain (fn [schema
  value] explanation-or-nil)}`), return a vector of per-key violation
  maps for the args that fail their entry schema.

  When the schema is a `[:map ...]` form the walker checks every
  registered entry against the matching value in `args` and collects
  the failures. When the schema is anything else (top-level
  non-`:map` shape) the walker validates the whole `args` map against
  the schema and returns a single `::root` violation when it fails.

  Per Spec 010 §Recommended soft-pass when the validator is absent
  (`nil`), every value passes — the panel reports 'no violations'.

  Output: vector of

      {:key      <arg-key|::root>
       :value    <any>
       :schema   <schema-fragment>
       :explain  <any|nil>}

  Pure data → data; JVM-testable.

  Validator-fn shape:

      validator-fns = {:validate <fn> :explain <fn-or-nil>}

  Both fns are pure (per Spec 010 §Locked rules). When `:validate`
  is nil/absent the walker treats every value as conforming."
  [args schema validator-fns]
  (let [validate (:validate validator-fns)
        explain  (:explain  validator-fns)]
    (cond
      ;; No validator registered — soft-pass per Spec 010.
      (nil? validate)
      []

      ;; No schema on file — nothing to validate.
      (nil? schema)
      []

      ;; Top-level :map — walk entries.
      (map-schema? schema)
      (into []
            (keep (fn [[k child-schema]]
                    (let [v (get args k)]
                      (when-not (validate child-schema v)
                        {:key     k
                         :value   v
                         :schema  child-schema
                         :explain (when explain (explain child-schema v))}))))
            (map-entries schema))

      ;; Top-level non-:map — validate whole args against the schema.
      :else
      (if (validate schema args)
        []
        [{:key     ::root
          :value   args
          :schema  schema
          :explain (when explain (explain schema args))}]))))

;; ---- pure: format-explain ------------------------------------------------

(defn format-explain
  "Render a validator explanation as a short, human-readable string
  the panel can stick into a row tooltip / inline note. Malli's
  explanations are nested maps with `:errors` carrying per-path
  detail; we pr-str the whole shape when we can't introspect.

  Pure; JVM-testable.

  - `nil` → empty string.
  - Malli explanation map (has `:errors`) → joined error paths.
  - Anything else → `pr-str`."
  [explanation]
  (cond
    (nil? explanation)
    ""

    (and (map? explanation) (vector? (:errors explanation)))
    (->> (:errors explanation)
         (map (fn [{:keys [path message] :as _err}]
                (let [path-str (if (seq path)
                                 (str/join "->" (map pr-str path))
                                 "(root)")]
                  (str path-str ": " (or message "schema violation")))))
         (str/join "; "))

    :else
    (pr-str explanation)))

;; ---- CLJS: schema lookup ------------------------------------------------

#?(:cljs
   (defn resolve-component-schema
     "Resolve the component schema for `variant-id`, looking in:

       1. The variant body's `:schema` slot.
       2. The parent story body's `:schema` slot.
       3. The framework registrar's `:view` metadata for the
          `:component` id, looking at `:spec` first (Spec 010
          canonical) then `:schema`.

     Returns the schema value, or nil when none is registered.

     The panel's CLJS-only render path calls this; the pure
     helpers (`args-violations`) take a pre-resolved schema and
     are JVM-friendly."
     [variant-id]
     (let [vb         (story-registrar/handler-meta :variant variant-id)
           story-id   (args/parent-story-id variant-id)
           sb         (when story-id (story-registrar/handler-meta :story story-id))
           comp-id    (or (:component vb) (:component sb))
           view-meta  (when comp-id (framework-registrar/handler-meta :view comp-id))]
       (or (:schema vb)
           (:schema sb)
           (:spec   view-meta)
           (:schema view-meta)))))

;; ---- CLJS: validator lookup via late-bind -------------------------------

#?(:cljs
   (defn validator-fns
     "Resolve the registered validator + explainer through the late-bind
     hook table. Returns `{:validate <fn> :explain <fn-or-nil>}`. Both
     entries default to nil when the schemas artefact is not on the
     classpath — `args-violations` then soft-passes per Spec 010
     §Recommended soft-pass.

     The validator hook (`:schemas/validate-with-registered-fn`) is
     published by `re-frame.schemas` on ns-load; the boundary surface
     it fronts is identical to the framework's dev-mode hot path, so
     a panel using these fns sees exactly the same conformance
     decisions the framework's `validate-app-db!` / `validate-event!`
     emit."
     []
     {:validate (late-bind/get-fn :schemas/validate-with-registered-fn)
      :explain  (late-bind/get-fn :schemas/explain-with-registered-fn)}))

;; ---- CLJS: styling ------------------------------------------------------

#?(:cljs
   (def ^:private styles
     {:wrap         {:padding "8px"
                     :background "#252526"
                     :color "#cccccc"
                     :font-family "monospace"
                     :font-size "11px"
                     :border-top "1px solid #444"
                     :overflow "auto"
                     :max-height "320px"}
      :section      {:margin-bottom "10px"}
      :section-h    {:font-weight "bold"
                     :color "#b0b0b0"
                     :text-transform "uppercase"
                     :font-size "10px"
                     :letter-spacing "0.5px"
                     :margin-bottom "4px"}
      :hint         {:color "#9a9a9a"
                     :font-style "italic"
                     :font-size "10px"
                     :margin-bottom "4px"}
      :empty        {:color "#9a9a9a"
                     :font-style "italic"
                     :padding "2px 0"}
      :violation    {:padding "4px 6px"
                     :margin "2px 0"
                     :background "#332"
                     :border-left "3px solid #ff4040"
                     :color "#fdd"}
      :v-key        {:color "#dcdcaa"
                     :font-weight "bold"}
      :v-value      {:color "#9cdcfe"}
      :v-schema     {:color "#ce9178"
                     :font-size "10px"}
      :v-explain    {:color "#aaa"
                     :font-size "10px"
                     :margin-top "2px"}
      :row          {:display "grid"
                     :grid-template-columns "92px 80px 1fr"
                     :gap "6px"
                     :padding "2px 0"
                     :border-bottom "1px dotted #2a2a2a"
                     :align-items "baseline"}
      :cell         {:overflow "hidden"
                     :text-overflow "ellipsis"
                     :white-space "nowrap"}
      :cell-time    {:color "#9a9a9a"}
      :cell-where   {:color "#9cdcfe"
                     :font-style "italic"}
      :cell-detail  {:color "#dcdcaa"}}))

;; ---- CLJS: render helpers -----------------------------------------------

#?(:cljs
   (defn- format-timestamp-ms [ms]
     (when (and ms (number? ms))
       (let [d (js/Date. ms)
             pad (fn [n w] (let [s (str n)]
                             (if (< (count s) w)
                               (str (apply str (repeat (- w (count s)) "0")) s)
                               s)))]
         (str (pad (.getHours d)   2) ":"
              (pad (.getMinutes d) 2) ":"
              (pad (.getSeconds d) 2) "."
              (pad (mod ms 1000)   3))))))

#?(:cljs
   (defn- args-violation-row
     [{:keys [key value schema explain] :as _violation}]
     (let [key-label (if (= key ::root)
                       "(whole args map)"
                       (pr-str key))]
       [:div {:style                     (:violation styles)
              :data-test                 "story-schema-args-violation"
              :data-arg-key              (str key)}
        [:div
         [:span {:style (:v-key styles)} key-label]
         " = "
         [:span {:style (:v-value styles)} (pr-str value)]]
        [:div {:style (:v-schema styles)}
         (str "schema: " (pr-str schema))]
        (when explain
          [:div {:style (:v-explain styles)}
           (format-explain explain)])])))

#?(:cljs
   (defn- trace-failure-row
     [{:keys [id time where failing-id path received explain recovery] :as _row}]
     [:div {:style                  (:row styles)
            :data-test              "story-schema-trace-row"
            :data-where             (when where (name where))
            :data-failing-id        (when failing-id (str failing-id))
            :title                  (when explain (format-explain explain))
            :key                    id}
      [:span {:style (merge (:cell styles) (:cell-time styles))}
       (or (format-timestamp-ms time) "")]
      [:span {:style (merge (:cell styles) (:cell-where styles))}
       (if where (pr-str where) "—")]
      [:span {:style (merge (:cell styles) (:cell-detail styles))}
       (cond-> (str (if failing-id (pr-str failing-id) "—"))
         path     (str " @ " (pr-str path))
         recovery (str " (" (pr-str recovery) ")"))]]))

;; ---- CLJS: the panel ----------------------------------------------------

#?(:cljs
   (defn panel
     "The schema-validation panel. Per rf2-dvue Form-2 — the inner
     render fn derefs the trace buffer so Reagent's reaction
     tracking sees every change.

     The panel surfaces two streams:

       1. **Args violations** — computed on every render from the
          variant's resolved args + the component's schema +
          the registered validator. Cheap to recompute (one Malli
          validate per top-level entry); no caching needed at v1.
       2. **Trace failures** — filtered + projected from the variant's
          per-variant trace buffer (the same buffer the trace + actions
          panels read).

     Renders a scrollable region tagged with `data-test=\"story-
     schema-panel\"` and `role=\"region\"` + `aria-label` so axe-core's
     scrollable-region-focusable rule passes and the browser-test
     spec can anchor on it."
     [variant-id]
     ;; Form-2 — capture the trace buffer atom on the outer closure
     ;; so the per-render fn observes the same source-of-truth across
     ;; the component's lifecycle.
     (let [_buf (trace/ensure-buffer! variant-id)]
       (fn [variant-id]
         (let [buf            (trace/ensure-buffer! variant-id)
               events         @buf
               trace-rows     (project-failures events)
               schema         (resolve-component-schema variant-id)
               vfns           (validator-fns)
               eff-args       (args/resolve-args variant-id)
               args-viols     (args-violations eff-args schema vfns)
               schema-known?  (some? schema)
               validator-on?  (some? (:validate vfns))]
           [:div {:style      (:wrap styles)
                  :role       "region"
                  :aria-label "Schema validation"
                  :tab-index  "0"
                  :data-test  "story-schema-panel"}
            ;; Args section.
            [:div {:style (:section styles)}
             [:div {:style (:section-h styles)}
              "Args vs schema"
              (when variant-id (str " — " (pr-str variant-id)))]
             [:div {:style (:hint styles)}
              (cond
                (not schema-known?)
                "no schema registered for the variant's :component — see Spec 010."

                (not validator-on?)
                "no validator registered (require [re-frame.schemas.malli] at boot to enable Malli)."

                :else
                "every arg checked against the component's Spec 010 schema on each render.")]
             (cond
               (or (not schema-known?) (not validator-on?))
               nil

               (zero? (count args-viols))
               [:div {:style (:empty styles)
                      :data-test "story-schema-args-empty"}
                "no args violations"]

               :else
               [:div {:data-test "story-schema-args-violations"}
                (for [v args-viols]
                  ^{:key (str (:key v))}
                  [args-violation-row v])])]
            ;; Trace section.
            [:div {:style (:section styles)}
             [:div {:style (:section-h styles)}
              "Trace failures — :rf.error/schema-validation-failure"]
             [:div {:style (:hint styles)}
              "every dev-mode or boundary-mode schema check that failed in this variant's frame."]
             (if (zero? (count trace-rows))
               [:div {:style (:empty styles)
                      :data-test "story-schema-trace-empty"}
                "no schema-validation-failure trace events captured yet"]
               [:div {:data-test "story-schema-trace-rows"}
                [:div {:style (merge (:row styles)
                                     {:font-weight "bold"
                                      :color       "#b0b0b0"})}
                 [:span {:style (:cell styles)} "time"]
                 [:span {:style (:cell styles)} "where"]
                 [:span {:style (:cell styles)} "detail"]]
                (for [row trace-rows]
                  ^{:key (:id row)}
                  [trace-failure-row row])])]])))))

;; ---- CLJS: panel registration -------------------------------------------

#?(:cljs
   (def ^:const panel-id
     "Story-panel id for the schema-validation panel. Per Story SOTA
     audit F-N / rf2-dvue."
     :rf.story.panel/schema-validation))

#?(:cljs
   (def ^:const panel-render-id
     "View id the panel registration points at. Registered against the
     framework view registry so the late-bind lookup in
     `re-frame.core/view` finds it."
     :rf.story.panel/schema-validation-view))

#?(:cljs
   (defn install!
     "Register the schema-validation panel via `reg-story-panel*` and
     the panel-render view via `reg-view*`. Idempotent. Skipped when
     `re-frame.story.config/enabled?` is false (the production-elision
     contract).

     Called from `re-frame.story.ui.panels/install-canonical-panels!`
     during `re-frame.story/install-canonical-vocabulary!`."
     []
     (when config/enabled?
       (rf/reg-view* panel-render-id (fn [variant-id] [panel variant-id]))
       (story-registrar/reg-story-panel*
         panel-id
         {:doc       "Live Spec 010 schema-validation panel — args vs schema + boundary trace failures."
          :title     "Schema validation"
          :placement :right
          :render    panel-render-id}))))
