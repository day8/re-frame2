(ns re-frame.freehand.spread-literal-nil-jvm-test
  "`(v/spread nil)` — a PRESENT first argument that happens to be nil.

  `v/spread` is common interpreted/compiled grammar and nil is defined as
  an empty runtime attribute map: the public function answers `{}`, and
  §Props forwarding pins a nil runtime map as an element with no
  attributes. The compiled analyzer judged the form's SHAPE by asking
  whether the first argument was nil, which reads a present literal nil
  as an ABSENT argument — so the declaration did not render differently,
  it refused to compile with `:rf.ui.compile/bad-spread`.

  The cost is not the rejection, it is what the rejection is CONDITIONAL
  on. A runtime expression whose value is nil compiled and rendered
  perfectly, so introducing or removing an otherwise unnecessary local
  changed whether the same value was accepted — and the empty-forward
  parity row already in the corpus uses exactly that indirection, which
  is why it never saw this.

  The shape is now judged by ARITY, the way the sibling foreign-component
  spread has always judged its own. Zero arguments and more than two are
  still the same didactic failure at the same phase, and a non-map
  runtime value is still refused where it always was — at the forward
  itself, where the value exists.

  Normative owner:
  [`spec/004-Views.md`](../../../../../spec/004-Views.md) §Props forwarding."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand :as v]
            [re-frame.freehand.compiler :as compiler]
            [re-frame.freehand.tree :as tree]))

(def ^:private params
  "The binding the runtime-nil rows read, so a nil that arrives at RENDER
  is a real runtime value and not a literal in disguise."
  '[{:keys [attrs]}])

(defn- compiled-tree
  "The structural tree the COMPILED front end answers for `body` — the
  emitted lowering, evaluated and called."
  ([body] (compiled-tree body {}))
  ([body props]
   (let [lowering (compiler/compile-structural-view
                    {:form            (list 'v/defview 'subject params body)
                     :menv            nil
                     :ns-sym          're-frame.freehand.spread-literal-nil-jvm-test
                     :vname           'subject
                     :view-id         ::subject
                     :params          params
                     :body            [body]
                     :children-policy :optional})]
     ((eval (:body lowering)) props))))

(defn- interpreted-tree
  [form]
  (dissoc (tree/render form) :rf.ui/tree-version))

(defn- compile-error
  "The `:rf.ui.compile/…` id `body` is refused with, or nil if it compiles."
  [body]
  (try (compiled-tree body) nil
       (catch Exception e
         (:rf.ui.compile/error (ex-data (or (ex-cause e) e))))))

;; ---------------------------------------------------------------------------
;; The accepted forms
;; ---------------------------------------------------------------------------

(def accepted-rows
  "Every way an author can forward nothing, and the ONE tree both modes
  answer. The literal and the runtime rows sit side by side deliberately:
  the defect was that they disagreed, and only a table carrying both
  could have said so."
  [{:note  "a literal nil base is an element with no attributes"
    :body  '[:div (v/spread nil)]
    :form  [:div (v/spread nil)]
    :tree  {:tag :div}}

   {:note  "a runtime value that IS nil — the form that always compiled"
    :body  '[:div (v/spread attrs)]
    :form  [:div (v/spread nil)]
    :props {:attrs nil}
    :tree  {:tag :div}}

   {:note  "literal nil in BOTH positions"
    :body  '[:div (v/spread nil nil)]
    :form  [:div (v/spread nil nil)]
    :tree  {:tag :div}}

   {:note  "a nil base with real overrides — the overrides still land"
    :body  '[:div (v/spread nil {:class "c"})]
    :form  [:div (v/spread nil {:class "c"})]
    :tree  {:tag :div :attrs {:class "c"}}}

   {:note  "and the tag shorthand is untouched by an empty forward"
    :body  '[:div.sugar (v/spread nil)]
    :form  [:div.sugar (v/spread nil)]
    :tree  {:tag :div :attrs {:class "sugar"}}}])

(deftest a-literal-nil-forwards-an-empty-map-in-both-modes
  (testing "nil is a defined value for a forwarded attribute map — the
            public function answers `{}` for it — so the compiled tier
            must accept the literal spelling of that value. Judging the
            form's shape by whether its first argument was nil made
            acceptance depend on whether the author had written the value
            directly or through a local, which is not a distinction the
            grammar makes anywhere else."
    (doseq [{:keys [note form props tree body]} accepted-rows]
      (is (= tree (interpreted-tree form)) (str note " — interpreted"))
      (is (= tree (compiled-tree body (or props {}))) (str note " — compiled")))))

;; ---------------------------------------------------------------------------
;; The failures that must survive
;; ---------------------------------------------------------------------------

(deftest the-genuinely-malformed-arities-still-fail-at-compile-time
  (testing "The control that makes the acceptance above mean something.
            Accepting a present nil is not accepting an ABSENT argument:
            zero arguments and more than two are still the same
            diagnostic at the same phase, so a repair that simply deleted
            the check would show up here."
    (is (= :rf.ui.compile/bad-spread (compile-error '[:div (v/spread)]))
        "zero arguments — there is no map to forward")
    (is (= :rf.ui.compile/bad-spread
           (compile-error '[:div (v/spread {:a 1} {:b 2} {:c 3})]))
        "three arguments — v/spread layers exactly two")))

(deftest a-non-map-runtime-value-is-still-refused-where-the-value-exists
  (testing "The arity check is about the FORM. What the forwarded
            expression evaluates to is judged at the forward itself,
            which is the only phase at which a runtime value exists —
            and that refusal is unchanged, because a compiled body and an
            interpreted one reach it through the same seam."
    (doseq [[mode thunk] [["interpreted" #(interpreted-tree [:div (v/spread "nope")])]
                          ["compiled"    #(compiled-tree '[:div (v/spread attrs)]
                                                         {:attrs "nope"})]]]
      (let [ex (try (thunk) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex) (str mode " — a string is not an attribute map"))
        (when ex
          (is (= :rf.error/ui-tree-malformed (:rf.error/id (ex-data ex)))
              (str mode " — the walk's existing diagnostic, not a compile-phase one")))))))
