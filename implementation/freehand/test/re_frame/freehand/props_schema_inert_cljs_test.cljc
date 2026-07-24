(ns re-frame.freehand.props-schema-inert-cljs-test
  "PR #6752 promises every props schema is published AS AUTHORED, inert and
  UNEVALUATED — an authored expression evaluated exactly ZERO times. A literal
  [:map …] kept that promise only while it held nothing but self-evaluating
  data: a nested authored FORM inside an entry is executable code, and the
  pre-fix declaration ran it once building Var metadata and once building the
  descriptor, publishing the evaluated value in place of the authored form on
  both surfaces.

  The probe COUNTS evaluations with a side effect, because 'looks unevaluated'
  passes against the defect while 'was evaluated zero times' does not.

  Host-neutral: the declaration expands on the JVM for both targets, and the
  probe is read the same way under `clojure -M:test` and
  `npm run test:freehand`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [re-frame.freehand :as v]
            [re-frame.freehand.compiler.env :as env]
            [re-frame.freehand.props-schema :as props-schema]))

;; The eval probe. Defined BEFORE the view whose schema references it, so a
;; pre-fix UNQUOTED nested form that runs at load can resolve it — the
;; reproduction must be able to run before the fix makes the form inert.
(defonce ^:private nested-schema-eval-count (atom 0))

(v/defview nested-schema-row
  "A literal [:map …] whose one entry value is a nested, side-effecting
  authored form. This is the exact hole PR #6752 left: the map's top level IS
  a literal [:map …], so the pre-fix inert-form left it UNQUOTED and the
  (do …) ran while the declaration loaded."
  {:props [:map [:id (do (swap! nested-schema-eval-count inc) :int)]]}
  [{:keys [id]}]
  [:li.row {:data-id id}])

(def ^:private authored-schema
  "The schema exactly as authored — the nested form intact and UNEVALUATED."
  '[:map [:id (do (swap! nested-schema-eval-count inc) :int)]])

(deftest a-nested-literal-map-schema-form-is-evaluated-zero-times
  (testing "The nested authored form runs ZERO times across Var-metadata and
            descriptor construction. The probe counts, so a side effect that
            ran even once — as it ran TWICE before the fix — fails here."
    (is (zero? @nested-schema-eval-count)
        "the (do (swap! …)) inside the literal map ran zero times at load")))

(deftest the-authored-nested-form-survives-on-every-published-surface
  (testing "Inert also means AUTHORED: both surfaces carry the form the author
            wrote, nested list intact, and never the value it would evaluate
            to."
    (is (= authored-schema (:props-schema (v/describe nested-schema-row)))
        "the descriptor projection carries the authored form")
    #?(:clj
       (is (= authored-schema
              (:re-frame.freehand/props-schema (meta #'nested-schema-row)))
           "and the Var metadata carries the identical authored form"))))

(deftest a-compiled-parent-still-derives-the-closing-roster-from-the-literal-map
  (testing "The fix must not opacify an ordinary literal map. Its top-level
            closing roster stays derivable — at the descriptor projection, and
            at the surface a compiled PARENT reads: the analyzer of the host
            that carries metadata as a FORM hands the reader the (quote …)
            wrapper the declaration emitted, and the reader must see through
            it just as it evaluates away everywhere else."
    (is (props-schema/map-schema? (:props-schema (v/describe nested-schema-row)))
        "the projection is a readable literal [:map …], not an opaque form")
    (is (= [:id] (props-schema/declared-keys (:props-schema (v/describe nested-schema-row))))
        "and its closing roster is [:id]")
    ;; The published inert wrapper, as a compiled parent's analyzer sees it.
    (is (props-schema/map-schema? '(quote [:map [:id :int]]))
        "map-schema? reads through the published inert (quote …) wrapper")
    (is (= [:id] (props-schema/declared-keys '(quote [:map [:id :int]])))
        "and so does the roster derivation")
    (is (= [:id] (props-schema/closing-keys '(quote [:map [:id :int]])))
        "so a compiled parent closes the child's props on [:id]")
    #?(:clj
       (is (= [:id] (env/closed-prop-keys (meta #'nested-schema-row)))
           "and the roster derived from the real Var is the same"))))

(deftest an-opaque-schema-is-still-opaque-through-the-published-wrapper
  (testing "The wrapper the readers see through is the declaration's OWN inert
            quote, not an author's quoted expression: an opaque schema, whose
            published form is the expression itself, still closes nothing at
            every surface."
    (is (not (props-schema/map-schema? '(identity [:map [:id :int]])))
        "an opaque expression is not a literal map")
    (is (nil? (props-schema/closing-keys '(identity [:map [:id :int]])))
        "and so it closes nothing")
    (is (nil? (props-schema/closing-keys 'registry-schema))
        "as does a registry reference")))

(deftest non-vacuity-the-schema-carries-genuinely-evaluable-side-effecting-code
  (testing "Non-vacuity: the entry value is a real (do (swap! …) :int) form —
            executable code with a side effect — so 'evaluated zero times' is a
            claim about code that COULD have run, and did run twice before the
            fix."
    (let [entry-value (last (second authored-schema))]
      (is (seq? entry-value)
          "the entry value is a list form, not a self-evaluating literal")
      (is (= 'do (first entry-value)))
      (is (= 'swap! (first (second entry-value)))
          "whose side effect is a swap! on the eval probe — code that runs iff
           it is evaluated"))))
