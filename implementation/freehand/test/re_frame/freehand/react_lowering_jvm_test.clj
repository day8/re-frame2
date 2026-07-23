(ns re-frame.freehand.react-lowering-jvm-test
  "The React emitter lowers EVERY node kind `:re-frame.freehand/v1` admits.

  `re-frame.freehand.compiler.emit-react` walks the normalized AST with a
  `case` that carries no default arm, and that is deliberate: the grammar
  check refuses every node kind outside v1 before emission, so a missing
  arm is not a fallback, it is a crash. `case` states it as
  `IllegalArgumentException: No matching clause`, thrown during MACRO
  EXPANSION of the ClojureScript compile — so a declaration using the
  unlowered form does not render wrongly, it fails to compile, with a
  diagnostic that names neither the view nor the form.

  A `v/slot` in a `{:compiled true}` body was exactly that: the structural
  emitter had lowered it since the slots slice shipped, and the browser
  emitter had no `:slot` arm at all.

  So this suite drives one source body per admitted op through the analyzer
  and the React emitter, and asserts the table COVERS
  [[re-frame.freehand.compiler.grammar/admitted-ops]] exactly. Widening the
  grammar without widening the emitter fails here, at the emitter, rather
  than in a consumer's ClojureScript build.

  It runs on the JVM against the `:clj` resolver because the two emitters
  consume the SAME normalized AST — the analysis under test is the analysis
  a ClojureScript expansion performs, and only the resolver that reads the
  namespace's aliases differs. The mounted browser half of the claim is
  `re-frame.freehand.compiled-slot-dom-cljs-test`.

  Normative owner:
  [`spec/004D-Freehand-Compiled-Grammar.md`](../../../../../spec/004D-Freehand-Compiled-Grammar.md)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [re-frame.freehand :as v]
            [re-frame.freehand.compiler.analyze :as ana]
            [re-frame.freehand.compiler.emit-react :as emit-react]
            [re-frame.freehand.compiler.env :as env]
            [re-frame.freehand.compiler.grammar :as grammar]))

(v/defview leaf
  "A declared child, so a `:view` crossing has something to cross to."
  [{:keys [label]}]
  [:i label])

(defn- analyzed
  "The normalized AST for one view body, analysed exactly as a compiled
  declaration in THIS namespace would be."
  [body]
  (let [e (-> (env/make-env {:host            :clj
                             :cljs-env        nil
                             :ns-sym          're-frame.freehand.react-lowering-jvm-test
                             :self            'subject
                             :self-id         ::subject
                             :template-anchor "react-lowering"})
              (assoc :self-children? true :hooks-region? true)
              (env/with-locals '#{props}))
        ast (ana/analyze-view-body e [body])]
    (grammar/check! e ::subject ast)
    [e ast]))

(defn- ops
  "Every `:op` the analyzed AST carries."
  [ast]
  (let [found (volatile! #{})]
    (walk/postwalk (fn [x]
                     (when (and (map? x) (keyword? (:op x)))
                       (vswap! found conj (:op x)))
                     x)
                   ast)
    @found))

(def rows
  "One source body per admitted node kind. `:op` is the kind the row
  exists to reach — asserted to really be in the analysed AST, so a row
  that stopped producing its own node kind cannot keep passing."
  [{:op :text     :body '[:div "text"]}
   {:op :nothing  :body '[:div nil]}
   {:op :expr     :body '[:div (:label props)]}
   {:op :element  :body '[:div.card {:id "x"} "a"]}
   {:op :fragment :body '[:<> [:i "a"] [:b "b"]]}
   {:op :view     :body '[:div [leaf {:label "a"}]]}
   {:op :for      :body '[:ul (for [i (:items props)] [:li {:key i} i])]}
   {:op :if       :body '[:div (if (:flag props) [:i "y"] [:b "n"])]}
   {:op :let      :body '[:div (let [x (:label props)] [:i x])]}
   {:op :letfn    :body '[:div (letfn [(f [] "x")] [:i (f)])]}
   {:op :case     :body '[:div (case (:kind props) :a [:i "a"] [:b "z"])]}
   {:op :presence :body '(v/presence {:timeout-ms 120} [:div {:key "a"} "x"])}
   ;; The row this file was written for: an inline render-fn, whose body is
   ;; lowered by THIS emitter, and a prop-carried one, whose carrier is a
   ;; runtime value.
   {:op :slot     :body '[:div (v/slot (v/render-fn [r] [:span r]) (:label props))]}])

(deftest the-react-emitter-lowers-every-admitted-node-kind
  (testing "The emitter's `case` has no default arm, so an unlowered kind
            is a macro-expansion crash rather than a diagnostic. Every kind
            the grammar admits is emitted here, and the failure below names
            the kind."
    (doseq [{:keys [op body]} rows]
      (let [[e ast] (analyzed body)]
        (is (contains? (ops ast) op)
            (str op " — the row really produces the node kind it names"))
        (is (some? (emit-react/emit-react-body e '[props] ast))
            (str op " — the React emitter has an arm for it"))))))

(deftest the-table-covers-the-whole-admitted-roster
  (testing "A per-kind table is only as good as its coverage, so the
            coverage is the assertion: widening `admitted-ops` without
            widening this table — and the emitter it drives — fails here."
    (is (= grammar/admitted-ops (into #{} (map :op) rows))
        "one row per admitted node kind, and no row outside the roster")))

(deftest a-compiled-slot-lowers-to-the-shared-carrier-contract
  (testing "The browser lowering reaches the SAME three runtime calls the
            structural one does — the gate, the host-independent arity
            check, and the carrier's own fn — so a slot cannot mean one
            thing in a structural render and another in the DOM."
    (let [[e ast] (analyzed '[:div (v/slot (v/render-fn [r] [:span r]) (:label props))])
          emitted (pr-str (emit-react/emit-react-body e '[props] ast))]
      (doseq [call ["re-frame.freehand.events/callback"
                    "re-frame.freehand.events/slot-ready?"
                    "re-frame.freehand.events/check-slot-arity!"
                    "re-frame.freehand.events/callback-fn"]]
        (is (.contains emitted call)
            (str "the emitted body calls " call)))
      (is (.contains emitted "re-frame.freehand.compiled-react/el")
          "and the render-fn's own body was lowered through the React emitter"))))
