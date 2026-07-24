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
   ;; lowered by THIS emitter. The prop-carried half — the render-fn authored
   ;; at a CALL SITE, which the analyzer records as an analysed template
   ;; rather than a value — has its own claim below.
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

;; ---------------------------------------------------------------------------
;; The OTHER axis — one row per props CARRIER
;; ---------------------------------------------------------------------------
;;
;; The table above is per node KIND, and a per-kind table has a structural
;; blind spot: `grammar/check!` walks `:op`, and so does the coverage
;; assertion, but the content an author writes mostly lives INSIDE a node's
;; `:props`. `:element` is admitted, so an emitter that never read
;; `(:spread props)` produced a perfectly well-formed `:element` with the
;; whole forwarded attribute map missing, and every row above stayed green.
;;
;; Three defects of exactly that shape landed in a row — a call-site
;; `v/render-fn` emitting `nil`, `v/spread` / `v/spread-safe` dropped
;; entirely, a call-site `v/event` emitting `nil` — so the table gained the
;; second axis. Each row names a carrier, proves the analyzed AST really
;; FILLS it, and asserts the emitted form REACHES the lowering that carrier
;; requires. The coverage assertions hold the tables to
;; `grammar/element-props-carriers` and `grammar/crossing-prop-markers`,
;; exactly as the kind table is held to `grammar/admitted-ops`.

(defn- emitted
  "The React lowering of one body, as text to look for calls in."
  [body]
  (let [[e ast] (analyzed body)]
    (pr-str (emit-react/emit-react-body e '[props] ast))))

(def element-rows
  "One source body per ELEMENT props carrier. `:reaches` is the call that
  proves the emitter read it — a per-carrier claim, because `some?` on the
  whole emission proves nothing: a dropped carrier still emits an element."
  [{:carrier :attrs       :body '[:div {:title (:t props)}]
    :reaches "compiled-react/attr!"}
   {:carrier :class       :body '[:div {:class (:c props)}]
    :reaches "compiled-react/class!"}
   {:carrier :style       :body '[:div {:style (:s props)}]
    :reaches "compiled-react/style!"}
   {:carrier :events      :body '[:div {:on-click [:app/go]}]
    :reaches "re-frame.freehand.reactive/event-site"}
   {:carrier :key         :body '[:div {:key (:k props)}]
    :reaches "cljs.core/unchecked-set"}
   {:carrier :spread      :body '[:div.sugar (v/spread (:attrs props) {:class "c"})]
    :reaches "re-frame.freehand.node/spread-attrs"}
   {:carrier :safe-spread :body '[:input (v/spread-safe {:value "v"} (:attrs props))]
    :reaches "re-frame.freehand.node/safe-caller-attrs"}])

(def crossing-rows
  "One source body per CROSSING prop marker. `:reaches` is what the emitted
  props map must carry for the prop to arrive at all."
  [{:marker nil          :body '[:div [leaf {:label (:l props)}]]
    :reaches ":label (:l props)"}
   {:marker :render-fn   :body '[:div [leaf {:row (v/render-fn [r] [:span r])}]]
    :reaches ":render-fn"}
   {:marker :ui-event    :body '[:div [leaf {:on-pick (v/event [x] [:app/picked x])}]]
    :reaches ":event"}
   {:marker :handler     :body '[:div [leaf {:on-done (v/handler [x] (prn x))}]]
    :reaches ":handler"}
   {:marker :foreign     :body '[:div [leaf {:node (v/raw (host-element))}]]
    :reaches "(host-element)"}
   {:marker :v/raw-fn    :body '[:div [leaf {:cb (v/raw-fn (:f props))}]]
    :reaches "re-frame.freehand.events/raw-fn"}])

(defn- carriers-of
  "Every ELEMENT props carrier the analyzed AST actually filled."
  [ast]
  (let [found (volatile! #{})]
    (walk/postwalk (fn [x]
                     (when (= :element (:op x))
                       (doseq [[k v] (:props x)
                               :when (and (contains? grammar/element-props-carriers k)
                                          (if (= :key k) (:present? v) (seq v)))]
                         (vswap! found conj k)))
                     x)
                   ast)
    @found))

(deftest the-react-emitter-reads-every-element-props-carrier
  (testing "A carrier the emitter never reads is not a crash and not a
            diagnostic — it is a well-formed element missing exactly what
            the author put in that slot. So each row proves its carrier is
            really in the analyzed props, then proves the emission reaches
            the lowering that carrier requires."
    (doseq [{:keys [carrier body reaches]} element-rows]
      (let [[_ ast] (analyzed body)]
        (is (contains? (carriers-of ast) carrier)
            (str carrier " — the row really fills the carrier it names")))
      (is (.contains ^String (emitted body) ^String reaches)
          (str carrier " — the emitted body reaches " reaches)))))

(deftest the-react-emitter-lowers-every-crossing-prop-marker
  (testing "A marked crossing prop carries ANALYSED CONTENT under its own
            key rather than a plain `:value`, which is exactly the shape an
            emitter reading only `:value` turns into `nil` — a prop that
            reaches the boundary ABSENT and renders nothing. Each row
            asserts what the emitted props map must carry, and that it
            carries no nil under the prop's own key."
    (doseq [{:keys [marker body reaches]} crossing-rows]
      (let [text (emitted body)]
        (is (.contains ^String text ^String reaches)
            (str marker " — the emitted props map reaches " reaches))
        (is (not (re-find #":(label|row|on-pick|on-done|node|cb) nil" text))
            (str marker " — and the boundary is not handed an absent prop"))))))

(deftest the-props-tables-cover-the-whole-admitted-rosters
  (testing "A per-carrier table is only as good as its coverage, so the
            coverage is the assertion — the same claim the node-kind table
            above makes, on the axis that table cannot see."
    (is (= grammar/element-props-carriers (into #{} (map :carrier) element-rows))
        "one row per element props carrier, and no row outside the roster")
    (is (= grammar/crossing-prop-markers (into #{} (map :marker) crossing-rows))
        "one row per crossing prop marker, and no row outside the roster")))

(deftest a-ref-is-refused-rather-than-silently-unread
  (testing "`:ref` is a props carrier no v1 emitter lowers, so the grammar
            refuses it. Silently leaving it unread rendered an element with
            the ref gone, while the same declaration INTERPRETED refuses
            outright — one declaration, two answers, neither of them said."
    (is (= :rf.ui.compile/unsupported-form
           (try (analyzed '[:div {:ref (v/raw-fn (:f props))}])
                nil
                (catch clojure.lang.ExceptionInfo ex
                  (:rf.ui.compile/error (ex-data ex)))))
        "a compiled :ref names the grammar that refused it")
    (is (some? (analyzed '[:div {:title "t"}]))
        "non-vacuous: the same element without a ref compiles")))

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

(deftest a-compiled-slot-evaluates-its-arguments-before-the-gate
  (testing "rf2-drpa3.133 — an interpreted `v/slot` is an ordinary eager
            call, so its arguments evaluate before the nil-slot gate is
            even reached. Promotion must not move that evaluation inside the
            gate: the emitted body binds each argument in the `let` BEFORE
            the `slot-ready?` `when`, so a side-effecting argument — a
            `v/sub` the analyzer attributes to the enclosing view — runs
            whether or not the slot renders. Asserted on the emitted FORM,
            so the React lowering is pinned without the deferred
            compiled-browser runtime; the behavioural cross-mode oracle is
            `slots-grammar-parity-jvm-test`."
    (let [[e ast] (analyzed '[:div (v/slot (:slot props) (probe-arg))])
          form    (emit-react/emit-react-body e '[props] ast)
          nodes   (tree-seq coll? seq form)
          gate    (first (filter (fn [x]
                                   (and (seq? x)
                                        (= 'clojure.core/when (first x))
                                        (some (fn [y]
                                                (and (seq? y)
                                                     (= 're-frame.freehand.events/slot-ready?
                                                        (first y))))
                                              (tree-seq coll? seq x))))
                                 nodes))]
      (is (some? gate)
          "the slot lowers to a slot-ready? gate")
      (is (some #{'(probe-arg)} nodes)
          "the argument expression is emitted at all")
      (is (not (some #{'(probe-arg)} (tree-seq coll? seq gate)))
          "and it is NOT evaluated inside the gate — it is bound before it,
           so a nil slot still runs it once"))))

(deftest a-call-site-render-fn-prop-carries-the-slot-across-the-boundary
  (testing "The library seam — content authored at the CALL SITE and
            invoked by the callee through `v/slot`. The analyzer records
            such a prop as an analysed TEMPLATE, so the entry carries no
            plain value at all: an emitter that reads only `:value` puts
            `nil` on the props map, the seam's slot gates as absent, and
            the crossing renders nothing while every declaration compiles
            and every mount resolves. Assert the carrier and its lowered
            body are really on the emitted props map."
    (let [[e ast] (analyzed '[:div [leaf {:row (v/render-fn [r] [:span r])}]])
          emitted (pr-str (emit-react/emit-react-body e '[props] ast))]
      (is (.contains emitted "re-frame.freehand.events/callback")
          "the prop carries the same roster callback an interpreted v/render-fn expands to")
      (is (.contains emitted "re-frame.freehand.compiled-react/el")
          "and the slot body was lowered through the React emitter")
      (is (not (.contains emitted ":row nil"))
          "so the boundary is not handed an absent slot"))))
