(ns re-frame.freehand.compiled-grammar-jvm-test
  "The finite grammar's two halves: what `:re-frame.freehand/v1` refuses,
  and the absence of anywhere for a refused form to quietly succeed.

  A finite language is only worth having if its boundary is legible. A
  compiler that refused unpredictably, or refused without saying what to
  do instead, would train authors to avoid `{:compiled true}` — which is
  the same outcome as not shipping it. So the rejections are proven as a
  TABLE: each row is a shape an author will actually write, and each row
  must produce a stable id, the grammar version that refused it, and a
  recovery ladder drawn from a closed roster.

  Expansion is driven directly rather than through `defview` forms in
  source, because a source-level rejection is a compile failure — the
  suite that proves rejections cannot itself be compiled if it contains
  them."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [re-frame.freehand :as v]
            [re-frame.freehand.compiler :as compiler]
            [re-frame.freehand.compiler.grammar :as grammar]
            [re-frame.freehand.node :as node]))

(v/defview a-declared-child
  "A legal head, so a row that fails does so for the reason it names."
  [{:keys [label]}]
  [:span label])

(defn- compile-body
  "Run the compiled front end over `body` with `params`, as the macro
  does. Returns the lowering map, or throws the diagnostic."
  ([body] (compile-body '[{:keys [items tag label]}] body))
  ([params body]
   (compiler/compile-structural-view
     {:form            (list 'v/defview 'subject params body)
      :menv            nil
      ;; the consuming namespace is named LITERALLY: `*ns*` at test-run time
      ;; is the runner's, and head resolution happens against the namespace a
      ;; declaration lives in.
      :ns-sym          're-frame.freehand.compiled-grammar-jvm-test
      :vname           'subject
      :view-id         ::subject
      :params          params
      :body            [body]
      :children-policy :optional})))

(defn- rejection
  "The diagnostic `body` is refused with, as data."
  [body]
  (try
    (compile-body body)
    ::accepted
    (catch clojure.lang.ExceptionInfo ex
      (assoc (select-keys (ex-data ex)
                          [:rf.ui.compile/error :re-frame.freehand/grammar :recovery])
             :message (ex-message ex)))))

;; ---------------------------------------------------------------------------
;; The rejection table
;; ---------------------------------------------------------------------------

(def rejected-shapes
  "Shapes outside `:re-frame.freehand/v1`, each with the id it is refused
  with and the FIRST rung of the ladder it must offer. Every row is
  something a real author writes: helper-heavy markup, a computed head, a
  list without keys, a component from another library.

  These are the SAME bodies the interpreted mode accepts without comment,
  which is the honest shape of the trade `{:compiled true}` makes."
  [{:note     "a computed head — the head is a runtime value"
    :body     '[tag {:class "x"} "hi"]
    :id       :rf.ui.compile/dynamic-head
    :recovery :use-a-literal-head}

   {:note     "markup manufactured by a helper the analyzer cannot see through"
    :body     '[:ul (map (fn [i] [:li i]) items)]
    :id       :rf.ui.compile/markup-returning-map
    :recovery :make-template-visible}

   {:note     "a list whose rows carry no key"
    :body     '[:ul (for [i items] [:li i])]
    :id       :rf.ui.compile/unkeyed-list-item
    :recovery :key-each-row}

   {:note     "a keyword in content position"
    :body     '[:div :label]
    :id       :rf.ui.compile/keyword-child
    :recovery :pass-computed-value}

   {:note     "a foreign component boundary — outside the v1 roster"
    :body     '[clojure.string/upper-case {:x 1}]
    :id       :rf.ui.compile/unsupported-form
    :recovery :extract-declared-child}

   {:note     "an unresolvable head"
    :body     '[NoSuchComponent {}]
    :id       :rf.ui.compile/unresolved-head
    :recovery :use-a-literal-head}

   {:note     "children under a void element"
    :body     '[:img {:src "a.png"} "caption"]
    :id       :rf.ui.compile/void-children
    :recovery :pass-computed-value}

   {:note     "a raw lazy seq in content position"
    :body     '[:ul (filter some? items)]
    :id       :rf.ui.compile/lazy-seq-child
    :recovery :make-template-visible}])

(deftest every-rejected-shape-is-refused-predictably
  (testing "Each shape outside the finite grammar is refused at BUILD
            time with a stable id, the grammar version that refused it,
            and a ladder whose rungs are all names from the closed
            recovery roster."
    (is (<= 4 (count rejected-shapes)) "the table is not vacuously small")
    (doseq [{:keys [note body id recovery]} rejected-shapes]
      (let [d (rejection body)]
        (is (not= ::accepted d) (str note " — is refused"))
        (when (map? d)
          (is (= id (:rf.ui.compile/error d)) (str note " — stable diagnostic id"))
          (is (= grammar/version (:re-frame.freehand/grammar d))
              (str note " — names the grammar that refused it"))
          (is (= recovery (first (:recovery d)))
              (str note " — leads with the recovery that actually fits"))
          (is (= :keep-interpreted (last (:recovery d)))
              (str note " — the always-available rung is last"))
          (is (every? #(contains? grammar/recoveries %) (:recovery d))
              (str note " — every rung is a name from the closed roster"))
          (is (not (str/includes? (:message d) (str id)))
              (str note " — the message is a sentence, not the id")))))))

(deftest the-table-is-not-testing-a-permanently-broken-compiler
  (testing "The rejections above mean nothing unless the neighbouring
            legal shapes compile. A table of refusals against a front end
            that refuses everything is not evidence of a boundary."
    (doseq [[note body] {"an element with sugar, a flag class and a converted name"
                         '[:section.panel#main {:class {:open true} :tab-index 3} "x"]
                         "a keyed list of declared children"
                         '[:ul (for [i items] [a-declared-child {:key i :label i}])]
                         "a fragment"
                         '[:<> [:i "a"] [:b "b"]]
                         "a conditional"
                         '(if (seq items) [:p "some"] [:p "none"])
                         "a let over an element"
                         '(let [n (count items)] [:p n])
                         "text, numbers and nothing"
                         '[:p "a" 1 nil]}]
      (is (map? (compile-body body)) (str note " — compiles")))))

(deftest the-grammar-roster-is-closed-and-named
  (testing "The version keyword is the promise that the roster does not
            grow under a running codebase, so the roster is asserted
            rather than left implicit."
    (is (= :re-frame.freehand/v1 grammar/version))
    (is (= #{:text :nothing :expr :element :fragment :view :for :if :let :letfn :case
             :presence :slot :behavior}
           grammar/admitted-ops)
        "the admitted node kinds are exactly the v1 roster")
    (is (contains? grammar/recoveries :keep-interpreted)
        "the always-available rung is a real, documented recovery")
    (doseq [[k sentence] grammar/recoveries]
      (is (and (string? sentence) (not (str/blank? sentence)))
          (str k " — the roster names what to DO, not just a token")))))

;; ---------------------------------------------------------------------------
;; No interpreted walker inside compiled markup
;; ---------------------------------------------------------------------------
;;
;; THE ORACLE, and why it is this one.
;;
;; A bundle grep is the wrong instrument for this claim and this repository has
;; the scars to prove it: `goog.DEBUG` survives into release builds inside
;; docstrings, and Closure inlines small functions so a symbol can be absent
;; while its code is present. Either mistake turns "0 occurrences" into a false
;; negative that reads like evidence.
;;
;; So the claim is made against artefacts that are not subject to either
;; failure, at three levels:
;;
;;   1. the VALUE      — a compiled descriptor carries no interpreted body at
;;                       all (asserted in `compiled-promotion-cljs-test`);
;;   2. the FORM       — the emitted lowering is DATA at build time, so it is
;;                       walked symbol by symbol rather than grepped as text;
;;                       nothing is inlined, minified or renamed, and the
;;                       oracle's discrimination is proven below;
;;   3. the BEHAVIOUR  — a runtime value that IS markup is refused inside
;;                       compiled markup, by name. This is the strongest of the
;;                       three: it does not ask whether an interpreter is
;;                       linked, it demonstrates that none is REACHABLE.

(def interpretation-entry-points
  "Every way into the interpreted walk. If a compiled lowering names one
  of these, a compiled body can walk a runtime markup value — which is
  exactly the hidden fallback D010 rules out."
  '#{re-frame.freehand.tree/render
     re-frame.freehand.tree/walk
     re-frame.freehand.node/walked-children
     re-frame.freehand.node/collect})

(defn- symbols-in [form]
  (let [hits (atom #{})]
    (walk/postwalk (fn [x] (when (symbol? x) (swap! hits conj x)) x) form)
    @hits))

(defn- names-an-interpreter? [form]
  (boolean (seq (clojure.set/intersection (symbols-in form)
                                          interpretation-entry-points))))

(deftest the-oracle-discriminates
  (testing "Before trusting an absence, prove the instrument can see a
            presence. The same walk that will report 'no interpreter'
            must FIND one when one is there, and must find the builders
            that genuinely are in the lowering."
    (let [lowering (:body (compile-body '[:section.panel [:h2 "x"] [:p "y"]]))
          syms     (symbols-in lowering)]
      (is (contains? syms 're-frame.freehand.node/element)
          "known-present: the lowering names the element builder")
      (is (contains? syms 're-frame.freehand.node/children)
          "known-present: the lowering names the children canonicalizer")
      (is (not (contains? syms 're-frame.freehand.node/nonexistent-builder))
          "known-absent: a symbol that is not there is reported absent")
      (is (names-an-interpreter?
            (list 'do lowering '(re-frame.freehand.tree/render x)))
          "COUNTERFACTUAL: a lowering that DID name the walk is flagged"))))

(deftest no-interpreted-walker-inside-compiled-markup
  (testing "With the oracle proven, the absence is the claim: no compiled
            lowering names any entry point into the interpreted walk.
            Every shape the grammar admits is checked, not just the easy
            ones — a fallback that only appeared under `for` or a
            conditional would be exactly the interesting case."
    (doseq [[note body] {"elements and text"      '[:div.card [:h2 "x"] "y" 3]
                         "a fragment"             '[:<> [:i "a"] [:b "b"]]
                         "a dynamic expression"   '[:p (str (count items))]
                         "a keyed list"           '[:ul (for [i items]
                                                          [a-declared-child
                                                           {:key i :label i}])]
                         "a mounted boundary"     '[a-declared-child {:label "x"}]
                         "a conditional"          '(if (seq items) [:p "a"] [:p "b"])
                         "a let"                  '(let [n 1] [:p n])
                         "a handler site"         '[:button {:on-click [:go]} "go"]}]
      (let [lowering (:body (compile-body body))]
        (is (not (names-an-interpreter? lowering))
            (str note " — the lowering names no interpretation entry point"))))))

(deftest compiled-markup-refuses-a-runtime-markup-value
  (testing "The behavioural half. A compiled body whose expression
            evaluates to a hiccup vector has nowhere to send it: the
            children canonicalizer, called without a walker, refuses by
            name and names the ladder. This is what 'no hidden
            interpreted fallback' means in operation — not that an
            interpreter is absent from the build, but that a compiled
            template cannot reach one."
    (let [ex (try (node/children [:div "surprise"])
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "a runtime markup value is refused, not walked")
      (is (= :rf.error/ui-tree-malformed (:rf.error/id (ex-data ex))))
      (is (str/includes? (ex-message ex) "compiled, not interpreted")
          "the diagnostic names the language boundary")
      (is (str/includes? (ex-message ex) "keep this view interpreted")
          "and it names the always-available recovery"))
    (is (= "3" (first (node/children 3)))
        "while the values a compiled body legitimately produces still pass")))
