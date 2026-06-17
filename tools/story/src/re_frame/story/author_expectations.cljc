(ns re-frame.story.author-expectations
  "Author EXPECTATIONS onto a story — the S5 workflow where a useful story
  becomes a regression test without a separate fixture (rf2-ba86n.12;
  `tools/story/spec/021-Story-UI-Test-And-Evidence.md` §S5,
  `019-Story-UI-Controls-And-View-States.md`).

  ## What this flow is — and is NOT

  Expectation authoring ADDS assertions to a story. The author picks an
  expectation KIND (app-db value, subscription value, rendered hiccup/DOM,
  schema behaviour, browser/a11y evidence), fills its operands, and the
  flow emits the canonical `re-frame.story.assertions` atom(s) folded onto
  the variant body's `:assertions` slot. The saved expectations become
  EXPLICIT variant DATA (a `(reg-variant … {:assertions […]})` form the
  author pastes into source), NOT hidden UI state.

  It is DISTINCT from its two siblings, which it MUST NOT conflate:

  - **save-current-state-as-variant** (`re-frame.story.save-variant`,
    rf2-ba86n.6, spec/019 §3) captures the live canvas STATE (args
    snapshot) into a new variant. It authors a state, not an expectation.
  - **generated-failure promotion** (`re-frame.story.ui.promotion`,
    rf2-ba86n.13, spec/021 §3) turns a captured run ARTIFACT into a named
    regression variant. Its source is a captured run, not a hand-authored
    expectation.

  This flow's source is the author's INTENT: \"I expect this story to hold
  X.\" The three flows share the `review-dialog` review-then-commit
  skeleton but never the same entry point or artifact-kind semantics.

  ## The honesty floor — runner cost / `:cannot-run` BEFORE save

  Every authored expectation declares the capability tokens it needs (via
  the EXISTING `re-frame.story.requirements` registry — this flow reads it,
  it does NOT redefine the runner model). So the dialog can show, for each
  expectation BEFORE it is saved:

  - the capability tokens the expectation requires;
  - the cheapest concrete runner that can prove it (`cheapest-runner`);
  - whether it `:cannot-run` under the default headless runner (a DOM /
    pixels / a11y-engine / reactive-counts expectation cannot run headless)
    — surfaced as an honest `:cannot-run` flag, not hidden.

  A `:cannot-run` expectation is still authorable (it is legitimate to
  declare a browser-tier expectation a headless run will refuse) — the
  point is the user SEES the cost before committing, never discovering it
  only at run time.

  ## Reuse, don't reinvent

  - The assertion VOCABULARY is `re-frame.story.assertions` (the canonical
    `:rf.assert/*` atoms). This ns builds those atoms from the author's
    operands; it does NOT define a parallel expectation model.
  - The runner COST / `:cannot-run` decision is `re-frame.story.requirements`
    (`assertion-tokens` / `cheapest-runner` / `select-runner` /
    `unmet-assertions`). This ns reads it.
  - The schema-kind expectation CONSUMES the existing schema-resolution
    surface — it folds onto `:rf.assert/path-matches` (a Malli schema at a
    path) and `:rf.assert/schema-error` (an expected boundary violation),
    both already in the canonical vocabulary. It does NOT redefine or
    unify the schema resolvers (that is the pending ayu6n/p5ivc/vnedo
    decision's lane).

  ## Pure / CLJS split

  Mirrors `save_variant.cljc` + `promotion.cljc`: the catalog, the atom
  builders, the per-expectation cost projection, the draft transitions, and
  the `(reg-variant …)` snippet are pure `.cljc` (JVM-testable without a
  host). The dialog ratom + the Reagent render live in the CLJS-only
  `re-frame.story.ui.author-expectations`."
  (:require [clojure.string :as str]
            #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [re-frame.story.assertions   :as assertions]
            [re-frame.story.predicates   :as pred]
            [re-frame.story.requirements :as requirements]))

;; ===========================================================================
;; THE EXPECTATION-KIND CATALOG  (the authorable expectation surface)
;; ===========================================================================
;;
;; Each kind names ONE authorable expectation. It carries the human label,
;; the canonical assertion id it folds onto (so the runner cost is read
;; from the requirement registry keyed on that id), the operand fields the
;; author fills, and a pure `:->atom` builder that turns the filled operand
;; map into the canonical `[:rf.assert/* …]` atom. The acceptance criteria
;; (rf2-ba86n.12) name five expectation surfaces — app-db, subscriptions,
;; rendered hiccup/DOM, schema behaviour, browser/a11y evidence — and each
;; resolves to a kind below that folds onto the EXISTING vocabulary.

(defn- read-edn
  "Best-effort read of an EDN operand string into a value. Pure-ish (the
  only impurity is `read-string`, which is data → data here). Returns
  `[ok? value-or-error]`. A blank string reads as nil with `ok? false`
  (so the dialog can show 'fill this operand' rather than committing a nil
  expectation). Tolerant: a malformed form reports `ok? false` with the
  error message rather than throwing."
  [s]
  (cond
    (nil? s)            [false nil]
    (str/blank? s)      [false nil]
    :else
    (try
      [true (edn/read-string s)]
      (catch #?(:clj Exception :cljs :default) e
        [false #?(:clj (.getMessage ^Exception e) :cljs (str e))]))))

(defn- parse-path
  "Parse an app-db path operand string into a vector. Accepts the printed
  vector form `[:a :b]` (read as EDN) or a bare keyword `:a` (lifted to
  `[:a]`). Returns `[ok? path-or-error]`. Pure data → data."
  [s]
  (let [[ok? v] (read-edn s)]
    (cond
      (not ok?)     [false v]
      (vector? v)   [true v]
      (keyword? v)  [true [v]]
      :else         [false (str "path must be a vector or keyword, got " (pr-str v))])))

(def expectation-kinds
  "The ordered catalog of authorable expectation kinds. Each descriptor:

  - `:kind`     — the kind keyword (stable id the dialog + tests key on).
  - `:label`    — human label for the kind picker.
  - `:surface`  — the acceptance-criteria surface it serves (`:app-db` /
                  `:subscriptions` / `:dom` / `:schema` / `:browser`) — so
                  the dialog can group / explain coverage.
  - `:assertion-id` — the canonical `:rf.assert/*` id it folds onto (the
                  key the requirement registry reads for the runner cost).
  - `:operands` — ordered vector of operand field descriptors
                  `{:field :label :placeholder :parse}` where `:parse` is a
                  pure `(string) → [ok? value-or-error]` operand parser.
  - `:doc`      — one-line description shown under the kind in the picker.

  The builder `expectation->atom` reads `:kind` + the filled operand map
  and produces the canonical atom; the cost projection reads
  `:assertion-id` against the requirement registry."
  [{:kind         :app-db-equals
    :label        "App-db value equals"
    :surface      :app-db
    :assertion-id :rf.assert/path-equals
    :doc          "Assert the value at an app-db path equals an expected value (:rf.assert/path-equals)."
    :operands     [{:field :path :label "path" :placeholder "[:counter :value]" :parse parse-path}
                   {:field :expected :label "expected" :placeholder "5" :parse read-edn}]}

   {:kind         :app-db-matches
    :label        "App-db value matches schema"
    :surface      :app-db
    :assertion-id :rf.assert/path-matches
    :doc          "Assert the value at an app-db path validates against a Malli schema (:rf.assert/path-matches)."
    :operands     [{:field :path :label "path" :placeholder "[:user]" :parse parse-path}
                   {:field :schema :label "malli schema" :placeholder "[:map [:id :int]]" :parse read-edn}]}

   {:kind         :sub-equals
    :label        "Subscription value equals"
    :surface      :subscriptions
    :assertion-id :rf.assert/sub-equals
    :doc          "Assert a subscription returns an expected value, via compute-sub against the settled state (:rf.assert/sub-equals)."
    :operands     [{:field :query-v :label "query vector" :placeholder "[:counter/value]" :parse read-edn}
                   {:field :expected :label "expected" :placeholder "5" :parse read-edn}]}

   {:kind         :dispatched
    :label        "Event was dispatched"
    :surface      :app-db
    :assertion-id :rf.assert/dispatched?
    :doc          "Assert a matching event was dispatched during the run (:rf.assert/dispatched?)."
    :operands     [{:field :event :label "event id or vector" :placeholder ":counter/inc" :parse read-edn}]}

   {:kind         :effect-emitted
    :label        "Effect was emitted"
    :surface      :app-db
    :assertion-id :rf.assert/effect-emitted
    :doc          "Assert a user effect was emitted during the run (:rf.assert/effect-emitted)."
    :operands     [{:field :fx-id :label "fx id" :placeholder ":http/get" :parse read-edn}]}

   {:kind         :no-warnings
    :label        "No warnings"
    :surface      :app-db
    :assertion-id :rf.assert/no-warnings
    :doc          "Assert no warning-level trace events fired during the run (:rf.assert/no-warnings)."
    :operands     []}

   {:kind         :dom-text
    :label        "Rendered DOM text"
    :surface      :dom
    :assertion-id :rf.assert/dom-text
    :doc          "Assert a selector's rendered text — needs a DOM-capable runner (:rf.assert/dom-text)."
    :operands     [{:field :selector :label "selector" :placeholder "\".count\"" :parse read-edn}
                   {:field :text :label "text" :placeholder "\"5\"" :parse read-edn}]}

   {:kind         :dom-visible
    :label        "Rendered element visible"
    :surface      :dom
    :assertion-id :rf.assert/dom-visible
    :doc          "Assert a selector is visible in the rendered DOM — needs a DOM-capable runner (:rf.assert/dom-visible)."
    :operands     [{:field :selector :label "selector" :placeholder "\".submit\"" :parse read-edn}]}

   {:kind         :a11y-structural
    :label        "Structural a11y (hiccup tree)"
    :surface      :browser
    :assertion-id :rf.assert/a11y-structural
    :doc          "Assert structural accessibility over the rendered hiccup tree — runs at the :hiccup tier (:rf.assert/a11y-structural)."
    :operands     []}

   {:kind         :a11y
    :label        "Accessibility scan (axe)"
    :surface      :browser
    :assertion-id :rf.assert/a11y
    :doc          "Assert an axe-style accessibility scan passes — needs a browser runner (:rf.assert/a11y)."
    :operands     []}

   {:kind         :visual-snapshot
    :label        "Visual snapshot"
    :surface      :browser
    :assertion-id :rf.assert/visual-snapshot
    :doc          "Assert a visual snapshot matches its baseline — needs a browser runner with pixels (:rf.assert/visual-snapshot)."
    :operands     []}

   {:kind         :schema-error
    :label        "Expected schema violation"
    :surface      :schema
    :assertion-id :rf.assert/schema-error
    :doc          "Assert the run is EXPECTED to emit one schema-validation failure on a named surface (:rf.assert/schema-error)."
    :operands     [{:field :where-spec :label "spec map" :placeholder "{:where :event :event :user/save}"
                    :parse read-edn :optional? true}]}])

(defn kind-descriptor
  "The catalog descriptor for `kind`, or nil. Pure data → data."
  [kind]
  (some (fn [d] (when (= kind (:kind d)) d)) expectation-kinds))

(def surface-labels
  "Human labels for the acceptance-criteria expectation surfaces. Used by
  the dialog's coverage hint so the author sees which surfaces their
  authored expectations span (app-db / subscriptions / DOM / schema /
  browser·a11y)."
  {:app-db        "App-db"
   :subscriptions "Subscriptions"
   :dom           "Rendered DOM"
   :schema        "Schema behaviour"
   :browser       "Browser / a11y"})

;; ===========================================================================
;; OPERAND PARSING + ATOM CONSTRUCTION
;; ===========================================================================
;;
;; An authored expectation row is `{:kind <kind> :operands {field → raw-str}}`.
;; `parse-operands` runs each field's parser, returning the parsed-value map
;; plus the per-field errors. `expectation->atom` builds the canonical
;; `[:rf.assert/* …]` atom from the parsed operands — the SAME atom the
;; runner evaluates, so what the author reviews IS what runs.

(defn parse-operands
  "Parse a row's raw operand strings against its kind's parser specs.
  Pure data → data. Returns:

      {:values {field → parsed-value}      ; only successfully-parsed fields
       :errors {field → error-string}      ; per-field parse errors
       :ok?    <bool>}                      ; every required operand parsed

  An unknown kind yields `{:values {} :errors {} :ok? false}` (the dialog
  guards on a known kind before reaching here, but the fn is total)."
  [{:keys [kind operands] :as _row}]
  (if-let [desc (kind-descriptor kind)]
    (let [specs (:operands desc)
          res   (reduce
                  (fn [acc {:keys [field parse optional?]}]
                    (let [raw      (get operands field)
                          blank?   (or (nil? raw) (str/blank? raw))
                          [ok? v]  (parse raw)]
                      (cond
                        ;; an OPTIONAL operand left blank is simply absent —
                        ;; no value, no error (e.g. a bare schema-error).
                        (and optional? blank?) acc
                        ok?                    (assoc-in acc [:values field] v)
                        :else                  (assoc-in acc [:errors field]
                                                          (if (string? v) v "fill this operand")))))
                  {:values {} :errors {}}
                  specs)]
      (assoc res :ok? (empty? (:errors res))))
    {:values {} :errors {} :ok? false}))

(defn expectation->atom
  "Build the canonical `[:rf.assert/* …]` assertion atom for an authored
  `row` (`{:kind :operands}`). Pure data → data. Returns the atom on a
  clean parse, or nil when the row's operands do not parse (the caller
  guards on `parse-operands`'s `:ok?` before committing).

  The atom is built from the SAME `re-frame.story.assertions` vocabulary
  the runner evaluates — this fn maps operands onto an atom shape, it does
  not define a new assertion kind."
  [{:keys [kind] :as row}]
  (let [{:keys [ok? values]} (parse-operands row)]
    (when ok?
      (case kind
        :app-db-equals    [:rf.assert/path-equals  (:path values) (:expected values)]
        :app-db-matches   [:rf.assert/path-matches (:path values) (:schema values)]
        :sub-equals       [:rf.assert/sub-equals   (:query-v values) (:expected values)]
        :dispatched       [:rf.assert/dispatched?  (:event values)]
        :effect-emitted   [:rf.assert/effect-emitted (:fx-id values)]
        :no-warnings      [:rf.assert/no-warnings]
        :dom-text         [:rf.assert/dom-text     (:selector values) (:text values)]
        :dom-visible      [:rf.assert/dom-visible  (:selector values)]
        :a11y-structural  [:rf.assert/a11y-structural]
        :a11y             [:rf.assert/a11y]
        :visual-snapshot  [:rf.assert/visual-snapshot]
        :schema-error     (let [spec (:where-spec values)]
                            (if (map? spec)
                              [:rf.assert/schema-error spec]
                              [:rf.assert/schema-error]))
        nil))))

;; ===========================================================================
;; RUNNER COST / `:cannot-run` BEFORE SAVE  (the honesty floor)
;; ===========================================================================
;;
;; The keystone acceptance criterion: the runner cost / `:cannot-run` is
;; visible BEFORE save. We read the EXISTING `re-frame.story.requirements`
;; registry — `assertion-tokens` for the capability set, `cheapest-runner`
;; for the runner that can prove it, and the default-headless check for the
;; honest `:cannot-run`. No parallel cost model.

(def default-author-runner
  "The runner an authored expectation is costed against by default — the
  same `:headless` floor a plain `story/run` uses
  (`re-frame.story.requirements/default-runner`). The cost projection
  reports `:cannot-run?` true when the expectation needs more than this
  runner can prove, so the author sees the escalation cost before saving."
  requirements/default-runner)

(defn expectation-cost
  "Project the runner cost of one authored expectation `atom` (a canonical
  `[:rf.assert/* …]` vector) — the honesty-floor data the dialog shows
  BEFORE save. Pure data → data; reads ONLY the existing requirement
  registry. Returns:

      {:required        #{token …}      ; capability tokens the atom needs
       :cheapest-runner <kind|nil>      ; cheapest concrete runner that proves it
                                        ;   (nil = NO runner can — e.g. a
                                        ;    requirement no P1 runner advertises)
       :headless?       <bool>          ; can the default headless runner prove it?
       :cannot-run?     <bool>          ; true iff it needs more than the default
                                        ;   runner — the honest before-save flag
       :missing         #{token …}}     ; tokens the default runner lacks (the gap)

  A `nil` atom (an unparsed row) projects an empty / unknown cost so the
  dialog can render the row without throwing."
  [atom]
  (if (nil? atom)
    {:required #{} :cheapest-runner nil :headless? true :cannot-run? false :missing #{}}
    (let [required (requirements/assertion-tokens atom)
          headless (requirements/runner-provides default-author-runner)
          missing  (requirements/missing-tokens required headless)]
      {:required        required
       :cheapest-runner (requirements/cheapest-runner required)
       :headless?       (empty? missing)
       :cannot-run?     (seq missing)
       :missing         missing})))

(defn row-cost
  "Convenience: parse `row` and project its `expectation-cost`. Pure data →
  data. Returns the cost map (with an empty/headless cost for an unparsed
  row), so the dialog can render a per-row cost stripe live as the author
  fills operands."
  [row]
  (expectation-cost (expectation->atom row)))

(defn draft-summary
  "Summarize a whole expectation `draft` (its `:rows` vector) for the
  before-save honesty banner. Pure data → data. Returns:

      {:count        <int>             ; total authored rows
       :ready        <int>             ; rows whose operands parse
       :atoms        [atom …]          ; the canonical atoms for ready rows
       :required     #{token …}        ; union of every ready atom's tokens
       :cheapest-runner <kind|nil>     ; cheapest runner proving ALL ready atoms
       :cannot-run-rows [{:row :atom :cost} …]  ; rows that can't run headless
       :surfaces     #{surface …}}     ; acceptance surfaces the draft spans

  `:cheapest-runner` is the cheapest concrete runner whose token set is a
  superset of the WHOLE draft's required union (`requirements/cheapest-runner`),
  so the author sees the single runner that would prove every authored
  expectation at once. `:cannot-run-rows` lists the rows that the default
  headless runner refuses — the honest before-save list."
  [{:keys [rows] :as _draft}]
  (let [rows*       (vec (or rows []))
        ready-rows  (filter (fn [r] (:ok? (parse-operands r))) rows*)
        atoms       (mapv expectation->atom ready-rows)
        required    (reduce into #{} (map requirements/assertion-tokens atoms))
        surfaces    (into #{}
                          (keep (fn [r] (:surface (kind-descriptor (:kind r)))))
                          rows*)
        cannot-rows (into []
                          (keep (fn [r]
                                  (let [c (row-cost r)]
                                    (when (:cannot-run? c)
                                      {:row r :atom (expectation->atom r) :cost c}))))
                          rows*)]
    {:count           (count rows*)
     :ready           (count ready-rows)
     :atoms           atoms
     :required        required
     :cheapest-runner (requirements/cheapest-runner required)
     :cannot-run-rows cannot-rows
     :surfaces        surfaces}))

;; ===========================================================================
;; SNIPPET — expectations as EXPLICIT variant DATA (the round-trip)
;; ===========================================================================
;;
;; The saved expectations become explicit variant DATA: a `(reg-variant …)`
;; form whose `:assertions` slot carries the authored atoms, MERGED with the
;; variant's already-declared `:assertions` so re-authoring is additive and
;; round-trips. We emit it onto an `:extends` of the source variant so the
;; new variant inherits the source's world/component/decorators and adds the
;; expectations — explicit data the author pastes into source, never hidden
;; UI state.

(defn merge-assertions
  "Merge `existing` declared assertions with newly `authored` atoms,
  preserving order and dropping exact duplicates (an author re-adding an
  expectation already declared is idempotent). Pure data → vector. The
  authored atoms append after the existing ones, so re-authoring is
  additive — the round-trip the acceptance criteria require."
  [existing authored]
  (let [existing* (vec (or existing []))
        seen      (set existing*)]
    (into existing*
          (remove seen)
          (or authored []))))

(defn- pr-assertions-vector
  "Pretty-print an assertions vector as a multi-line EDN form, each atom on
  its own line aligned under the opening `[` of `:assertions [`. Empty
  renders as `[]`. Uses the shared `predicates/indent-after` so the
  continuation indent stays in lockstep with the recorder / save-variant
  snippets. Pure data → string."
  [atoms]
  (if (empty? atoms)
    "[]"
    (str "["
         (->> atoms
              (map pr-str)
              (str/join (pred/indent-after "   :assertions [")))
         "]")))

(defn gen-expectations-snippet
  "Build the `(reg-variant …)` EDN snippet that adds authored expectations
  to a story. Pure data → string. The saved expectations become explicit
  variant DATA — an `:assertions` slot carrying the canonical atoms, merged
  with the source variant's already-declared assertions (additive
  round-trip). `opts`:

      :variant-id    required — the new variant id (e.g. :story.counter/expects-5)
      :extends       optional — source variant id; the new variant inherits
                                its component / decorators / world via :extends
      :existing      optional — the source variant's already-declared
                                :assertions, merged with the authored atoms
      :authored      required — the authored canonical atoms (from
                                `expectation->atom` over the draft rows)
      :doc           optional — a docstring
      :alias         optional — the reg-variant alias (default \"story\")

  The output is `read-string`-able EDN that round-trips through the Story
  registrar. The author pastes it into source — source is never written
  directly (same escape hatch as save-variant / recorder / promotion)."
  [{:keys [variant-id extends existing authored doc alias]
    :or   {alias "story"}}]
  (let [merged    (merge-assertions existing authored)
        body-keys (cond-> []
                    doc     (conj [:doc (pr-str doc)])
                    extends (conj [:extends (pr-str extends)])
                    true    (conj [:assertions (pr-assertions-vector merged)])
                    true    (conj [:tags (pr-str #{:test})]))]
    (pred/reg-variant-form alias (or variant-id :story.expectations/example) body-keys)))

;; ===========================================================================
;; DEFAULT NEW-VARIANT ID
;; ===========================================================================

(def ^:const default-id-prefix
  "Per-flow prefix for the auto-derived new-variant id. Distinct from
  save-variant's \"saved\" and promotion's \"regression\" so an authored-
  expectations variant reads as an expectation, not a state snapshot or a
  captured regression."
  "expects")

(defn assertions-known?
  "True iff every atom in `atoms` is a recognised P1 assertion id
  (`re-frame.story.assertions/assertion-id-known?`). Pure data → bool.
  The dialog asserts this before offering the snippet so an authored
  expectation can never reference an id plan construction would reject."
  [atoms]
  (every? (fn [a] (assertions/assertion-id-known? (assertions/assertion-atom-id a)))
          (or atoms [])))
