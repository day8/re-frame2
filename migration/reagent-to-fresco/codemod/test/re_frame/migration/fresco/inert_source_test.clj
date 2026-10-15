(ns re-frame.migration.fresco.inert-source-test
  "Reporting, rewriting and refusal analysis must skip inert source without losing live sites."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.migration.fresco.codemod :as rf.migration.fresco.codemod]))

(def ^:private hdr
  "(ns app.p\n  (:require [reagent.core :as r]))\n")

(defn- scan [body] (rf.migration.fresco.codemod/scan-string (str hdr body) "app/p.cljs"))

(defn- classes
  [body]
  (mapv :class (:entries (scan body))))

(defn- rewritten
  [body]
  (:source (rf.migration.fresco.codemod/rewrite-string (str hdr body) "app/p.cljs")))

(deftest inert-source-is-not-a-crossing-site
  (testing "skip inert crossings and their site counts"
    (is (= [[[:computed-value] 1] [[] 0] [[] 0] [[] 0]]
           (mapv (juxt classes (comp :sites scan))
                 ["[:> Foo {:on-click f}]\n"
                  "(comment [:> Foo {:on-click f}])\n"
                  "(def x (quote [:> Foo {:on-click f}]))\n"
                  "(clojure.core/comment [:> Foo {:on-click f}])\n"])))))

(deftest pruning-must-not-swallow-what-follows
  (testing "resume after inert subtrees"
    (is (= [[:computed-value] [:computed-value] [:computed-value]]
           (mapv classes
                 ["#_[:> A {:on-click f}]\n'[:> B {:on-click g}]\n(comment [:> C {:on-click h}])\n[:> D {:on-click i}]\n"
                  "(defn f []\n  (comment [:> A {:on-click g}])\n  [:> B {:on-click h}])\n"
                  "[:> A {:on-click f}]\n(comment [:> B {:on-click g}])\n"])))))

(deftest a-syntax-quote-is-still-a-crossing-site
  (testing "syntax quotes emit live crossings"
    (is (= [:computed-value]
           (classes "(defmacro m [] `[:> Foo {:on-click f}])\n")))))

(deftest the-fixer-does-not-edit-inert-source
  (testing "preserve inert source and repair the live control"
    (let [inert (str "(comment [:> Chart {:options {:page-size 10}}])\n"
                     "#_[:> Chart {:options {:first-name 1}}]\n"
                     "(def x '[:> Chart {:options {:page-size 10}}])\n")]
      (is (= (str hdr inert "[:> Chart {:options {:pageSize 10}}]\n")
             (rewritten (str inert "[:> Chart {:options {:page-size 10}}]\n")))))))

(deftest an-inert-anonymous-fn-body-is-inert-for-every-walk
  (testing "anonymous functions obey the same inert boundary"
    (let [live  "(def h #(do [:> Chart {:options {:page-size 10}}]))\n"
          inert (str "(def f #(comment [:> Chart {:options {:page-size 10}}]))\n"
                     "(def g #(quote [:> Chart {:options {:page-size 10}}]))\n")]
      (is (= [[:nested-map-keys] [] [] []]
             (mapv classes [live
                            inert
                            "[:> Foo {:x #(comment (r/as-element [:div]))}]\n"
                            "[:> Foo {:x #(quote (r/as-element [:div]))}]\n"])))
      (is (= (str hdr "(def h #(do [:> Chart {:options {:pageSize 10}}]))\n" inert)
             (rewritten (str live inert)))))))

(deftest an-inert-adapt-def-is-not-a-def-site
  (testing "ignore inert definitions and call sites"
    (is (= [] (classes "(comment (def Foo (r/adapt-react-class X)))\n")))
    (is (= [:adapt-def-site [5]]
           ((juxt :class #(get-in % [:detail :call-sites-in-this-file]))
            (first (:entries (scan (str "(def Foo (r/adapt-react-class X))\n"
                                        "(comment [Foo {:a 1}])\n"
                                        "[Foo {:b 2}]\n")))))))))

(deftest an-inert-reagent-call-is-not-a-refusal-reason
  (testing "ignore inert calls inside live props"
    (is (= [[:computed-value] [] [:computed-value]]
           (mapv classes ["[:> Foo {:x (comment (r/as-element [:div]))}]\n"
                          "[:> Foo {:x '(r/as-element [:div])}]\n"
                          "[:> Foo {:x (comment (r/atom 0))}]\n"])))))

(deftest a-live-reagent-call-is-still-a-refusal-reason
  (testing "retain live refusal reasons"
    (is (= [[:as-element-island]
            [:reagent-api-residue]
            [:as-element-island :computed-value]
            [:as-element-island :computed-value]]
           (mapv classes ["[:> Foo {:x (fn [] (r/as-element [:div]))}]\n"
                          "[:> Foo {:x (fn [] (r/atom 0))}]\n"
                          (str "[:> Foo {:x (comment (r/as-element [:div]))\n"
                               "         :y (fn [] (r/as-element [:span]))}]\n")
                          "[:> Foo {:x `(r/as-element [:div])}]\n"])))))

(deftest a-reagent-call-at-the-head-of-an-anonymous-fn-is-a-refusal-reason
  (testing "anonymous-function call heads are live"
    (is (= [[:as-element-island] [:reagent-api-residue] []]
           (mapv classes ["[:> Foo {:x #(r/as-element [:div])}]\n"
                          "[:> Foo {:x #(r/atom 0)}]\n"
                          "[:> Foo {:x #(run-later r/as-element)}]\n"]))))

  (testing "a function that adapts a class is not an adapted class"
    (is (= [[:adapt-react-class-head] [] [:adapt-def-site] []]
           (mapv classes ["[(r/adapt-react-class X) {:a 1}]\n"
                          "[#(r/adapt-react-class X) {:a 1}]\n"
                          "(def Foo (r/adapt-react-class X))\n"
                          "(def Foo #(r/adapt-react-class X))\n"])))
    (is (= 0 (:sites (scan "[#(r/adapt-react-class X) {:a 1}]\n"))))))
