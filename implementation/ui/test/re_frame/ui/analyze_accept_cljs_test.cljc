(ns re-frame.ui.analyze-accept-cljs-test
  "Template-grammar ACCEPT table: the blessed forms lower into the closed
  AST node set, on both hosts (the analyzer is pure — resolution is
  injected, so this suite runs identically under `clojure -M:test` and
  `npm run test:ui`). Also pins the AST-shape gate (closed op set) and
  the serialisation boundary (AST print/read round-trip)."
  (:require [clojure.test :refer [deftest is testing]]
            #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])
            [re-frame.ui.compiler.analyze :as ana]
            [re-frame.ui.compiler.env :as env]))

(def resolver
  "Injected Q5 resolution stub — fqn + var meta per symbol."
  (fn [sym]
    (case sym
      map         {:fqn 'clojure.core/map :meta {}}
      sub         {:fqn 're-frame.ui/sub :meta {}}
      lease       {:fqn 're-frame.ui/lease :meta {}}
      raw         {:fqn 're-frame.ui/raw :meta {}}
      html        {:fqn 're-frame.ui/html :meta {}}
      raw-fn      {:fqn 're-frame.ui/raw-fn :meta {}}
      spread      {:fqn 're-frame.ui/spread :meta {}}
      child-view  {:fqn 'app.views/child-view
                   :meta {:rf.ui/view true :rf.ui/children? true}}
      leaf-view   {:fqn 'app.views/leaf-view
                   :meta {:rf.ui/view true :rf.ui/children? false}}
      closed-view {:fqn 'app.views/closed-view
                   :meta {:rf.ui/view true
                          :rf.ui/closed-prop-keys [:a :b]}}
      declared-view {:fqn 'app.views/declared-view
                     :meta {:rf.ui/view true}} ; (declare ^:rf.ui/view ...)
      ForeignComp {:fqn 'app.interop/ForeignComp :meta {}}
      var-copy    {:fqn 'app.views/var-copy :meta {}} ; (def var-copy view)
      nil)))

(defn mk-env []
  (-> (env/make-env {:host :clj :ns-sym 'app.test
                     :self 'self-view :self-id :app.test/self-view
                     :resolver resolver})
      (assoc :self-children? false :self-closed-keys nil)))

(defn ana* [form]
  (ana/analyze (mk-env) form))

(defn ana-full
  "-> {:ast .. :warnings [..] :sites {..}}"
  [form]
  (let [e (mk-env)
        ast (ana/analyze e form)]
    {:ast ast :warnings @(:warnings e) :sites @(:sites e)}))

;; ---------------------------------------------------------------------------
;; Scalars + basics
;; ---------------------------------------------------------------------------

(deftest scalars-lower
  (is (= {:op :text :value "hi" :static? true} (ana* "hi")))
  (is (= {:op :text :value "3" :static? true} (ana* 3)) "numbers -> JS ToString text")
  (is (= {:op :text :value "3" :static? true} (ana* 3.0)) "integral double -> no .0")
  (is (= {:op :nothing :static? true} (ana* nil)))
  (is (= {:op :nothing :static? true} (ana* false)))
  (is (= {:op :nothing :static? true} (ana* true)) "true renders nothing (pinned)"))

(deftest element-sugar-both-orders
  (let [a (ana* [:div.card#main "x"])
        b (ana* [:div#main.card "x"])]
    (is (= :element (:op a)))
    (is (= (get-in a [:props :class :base-str]) "card"))
    (is (= (get-in b [:props :class :base-str]) "card"))
    (is (= "main" (:value (first (get-in a [:props :attrs])))))
    (is (= "main" (:value (first (get-in b [:props :attrs])))))))

(deftest sugar-class-merges-before-explicit
  (is (= "card extra" (get-in (ana* [:div.card {:class "extra"}])
                              [:props :class :base-str]))
      ".class sugar renders first, then the explicit :class form"))

(deftest flag-map-classes-lexicographic
  (is (= [["alpha" 'a?] ["beta" 'b?]]
         (get-in (ana* [:div {:class {:beta 'b? :alpha 'a?}}])
                 [:props :class :flags]))
      "flag-map entries order lexicographically (map order never trusted)"))

(deftest fragments-lower
  (is (= :fragment (:op (ana* [:<> [:p "a"] [:p "b"]]))))
  (is (true? (get-in (ana* [:<> {:key 'k} [:p "a"]]) [:key :present?]))
      "fragments accept exactly {:key ...}"))

;; ---------------------------------------------------------------------------
;; Control forms normalize INTO the AST
;; ---------------------------------------------------------------------------

(deftest control-forms-normalize
  (is (= :if (:op (ana* '(if c [:p "y"] [:p "n"])))))
  (is (= :if (:op (ana* '(if-not c [:p "y"])))))
  (is (= :if (:op (ana* '(when c [:p "y"])))))
  (is (= :if (:op (ana* '(when-not c [:p "y"])))))
  (is (= :if (:op (ana* '(cond a [:p "1"] :else [:p "2"])))))
  (is (= :case (:op (ana* '(case x :a [:p "a"] [:p "d"])))))
  (is (= :let (:op (ana* '(let [x 1] [:p x])))))
  (is (= :letfn (:op (ana* '(letfn [(f [n] (* n 2))] [:p (f 2)])))))
  (is (= :text (:op (ana* '(do "just this")))) "single-form do unwraps")
  (is (= :for (:op (ana* '(for [x xs] [:li {:key x} x]))))))

(deftest q6-for-subgrammar
  (testing "destructuring patterns bind"
    (is (= :for (:op (ana* '(for [{:keys [id]} xs] [:li {:key id} id]))))))
  (testing ":let/:when/:while modifiers"
    (is (= :for (:op (ana* '(for [x xs
                                  :let [y (inc x)]
                                  :when (odd? y)
                                  :while (< y 10)]
                              [:li {:key x} y]))))))
  (testing "multiple binding pairs = nested iteration in ONE keyed site"
    (is (= :for (:op (ana* '(for [x xs, y (f x)] [:li {:key [x y]} y]))))))
  (testing "sub in the FIRST coll expression is one site — legal"
    (let [{:keys [sites]} (ana-full '(for [x (sub [:q])] [:li {:key x} x]))]
      (is (= 1 (count (:subs sites)))))))

(deftest capture-free-vector-in-loop-is-legal
  (is (= :for (:op (ana* '(for [x xs] [:li {:key x :on-click [:list/refresh]} x]))))
      "capture-free literal vectors share one callback across rows"))

;; ---------------------------------------------------------------------------
;; Q5 — head discrimination
;; ---------------------------------------------------------------------------

(deftest q5-head-classification
  (is (= :view (:op (ana* '[child-view {:x 1} [:p "c"]]))))
  (is (= :app.views/child-view (:view-id (ana* '[child-view {}]))))
  (is (= :view (:op (ana* '[self-view {}]))) "self-recursion classifies internal")
  (is (= :view (:op (ana* '[declared-view {}])))
      "(declare ^:rf.ui/view b) marks forward/mutual references internal")
  (is (= :foreign (:op (ana* '[ForeignComp {:anything (quote x)}]))))
  (is (= :foreign (:op (ana* '[var-copy {}])))
      "var copies do not carry view-ness (def does not copy var meta) — foreign"))

;; ---------------------------------------------------------------------------
;; Handlers
;; ---------------------------------------------------------------------------

(deftest handler-classification-table
  (let [el (ana* '[:button {:on-click [:cart/add id]} "x"])]
    (is (= :vector (:classification (first (get-in el [:props :events])))))
    (is (true? (:serializable? (first (get-in el [:props :events]))))))
  (let [el (ana* '[:input {:on-input [:form/typed :email :rf.ui/value]}])]
    (is (true? (:hoistable? (first (get-in el [:props :events]))))
        "literal/placeholder-only vectors hoist"))
  (let [el (ana* '[:button {:on-click {:event [:x/y] :prevent-default true
                                       :capture true}} "x"])]
    (is (= :options (:classification (first (get-in el [:props :events])))))
    (is (true? (:capture? (first (get-in el [:props :events]))))))
  (let [el (ana* '[:button {:on-click (fn [e] e)} "x"])]
    (is (= :fn (:classification (first (get-in el [:props :events]))))
        "bare fns are legal in known native event properties"))
  (let [el (ana* '[:button {:on-click (if a [:x/a] [:x/b])} "x"])]
    (is (= :dynamic (:classification (first (get-in el [:props :events])))))))

(deftest event-sites-index-into-the-manifest
  (let [{:keys [sites]} (ana-full '[:div
                                    [:button {:on-click [:a/b 1]} "x"]
                                    [:input {:on-input (fn [e] e)}]])]
    (is (= 2 (count (:events sites))))
    (is (= [:a/b 1] (:handler (first (:events sites)))))
    (is (true? (:serializable? (first (:events sites)))))
    (is (= :opaque (:handler (second (:events sites)))))
    (is (false? (:serializable? (second (:events sites)))))))

(deftest sub-sites-index
  (let [{:keys [sites]} (ana-full '[:div
                                    [:h1 (sub [:title])]
                                    [:p {:data-n (sub [:count])} "x"]])]
    (is (= 2 (count (:subs sites))))
    (is (= [[:title]] (map :query (take 1 (:subs sites)))))))

(deftest html-sites-index
  (testing "ui/html records a manifest site — the profile row's 'manifest
  site recording' sub-assertion: every visible escaping bypass is listed with
  its source/template path and serialisability flag"
    (let [{:keys [sites]} (ana-full '[:div
                                      [:section.a (html "<b>x</b>")]
                                      [:aside.b (html "<i>y</i>")]])]
      (is (= 2 (count (:htmls sites))) "both bypass sites recorded")
      (is (= "<b>x</b>" (:form (first (:htmls sites)))))
      (is (true? (:static? (first (:htmls sites)))))
      (is (true? (:serializable? (first (:htmls sites))))
          "a literal-string bypass is serialisable data")
      (is (vector? (:path (first (:htmls sites))))
          "the site carries its template path")))
  (testing "a non-string (dynamic) html argument records a non-serialisable site"
    (let [{:keys [sites]} (ana-full '[:div.c (html markup)])]
      (is (= 1 (count (:htmls sites))))
      (is (false? (:static? (first (:htmls sites)))))
      (is (false? (:serializable? (first (:htmls sites))))))))

;; ---------------------------------------------------------------------------
;; Interop forms
;; ---------------------------------------------------------------------------

(deftest interop-forms-lower
  (is (= :raw (:op (ana* '(raw some-element)))))
  (let [el (ana* '[:div.content (html "<b>x</b>")])]
    (is (= :element (:op el)))
    (is (= "<b>x</b>" (get-in el [:html :form])) "sole-child ui/html rides the element"))
  (let [el (ana* '[:div (spread base {:class "x"})])]
    (is (some? (get-in el [:props :spread])))))

(deftest raw-and-raw-fn-props-mark
  (let [v (ana* '[ForeignComp {:el (raw host-el) :cb (raw-fn f)}])]
    (is (= [:foreign :ui/raw-fn]
           (mapv :marker (get-in v [:props :entries]))))))

;; ---------------------------------------------------------------------------
;; The AST-shape gate + serialisation boundary
;; ---------------------------------------------------------------------------

(def fixture-templates
  '[[:div.card#main {:style {:padding 16} :data-x 1 :on-click [:a/b]}
     "text" 42
     (when c [:p "cond"])
     (for [x xs] [:li {:key x} x])
     [child-view {:v (quote data)} [:em "child"]]
     [ForeignComp {:p 1}]
     [:<> [:i "f1"] [:i "f2"]]]
    (let [x 1] (case x 1 [:p "one"] [:p "other"]))
    (letfn [(f [n] n)] [:p (f 1)])
    [:div (spread b o)]
    (raw el)
    [:div.c (html "<hr/>")]]  )

(defn- ops-of [ast]
  (let [acc (atom #{})]
    ((fn walk [n]
       (when (map? n)
         (when-let [op (:op n)] (swap! acc conj op))
         (doseq [[_ v] n]
           (cond (map? v) (walk v)
                 (vector? v) (doseq [x v] (walk x))))))
     ast)
    @acc))

(deftest ast-shape-gate-closed-op-set
  (doseq [tpl fixture-templates]
    (let [ast (ana* tpl)]
      (is (every? ana/node-ops (ops-of ast))
          (str "every op within the closed set for " (pr-str tpl))))))

(deftest ast-serialisation-boundary
  (doseq [tpl fixture-templates]
    (let [ast (ana* tpl)]
      (is (= ast (edn/read-string {:default (fn [_ v] v)} (pr-str ast)))
          "the AST survives a print/read round-trip (no fn objects, ever)"))))

;; ---------------------------------------------------------------------------
;; Warnings (collected, not thrown)
;; ---------------------------------------------------------------------------

(deftest warning-table
  (is (= [:rf.ui.compile/bare-fn-in-loop]
         (mapv :id (:warnings (ana-full '(for [x xs]
                                           [:li {:key x :on-click (fn [] x)} x])))))
      "bare fns in loops warn (per-row closures work but defeat the data idiom)")
  (is (= [:rf.ui.compile/placeholder-not-top-level]
         (mapv :id (:warnings (ana-full '[:input {:on-input [:a/b [:rf.ui/value]]}]))))
      "nested placeholder keywords warn — placeholders splice at top level only"))
