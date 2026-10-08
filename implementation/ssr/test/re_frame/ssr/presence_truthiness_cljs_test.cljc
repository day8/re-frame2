(ns re-frame.ssr.presence-truthiness-cljs-test
  "`re-frame.ssr.html-helpers/presence-value-truthy?` — would react-dom treat
  this value on a presence attribute as present? — on BOTH runtimes.

  The guarded failure is a host asymmetry: the self-inequality NaN check
  `(not= v v)` is correct in ClojureScript and dead code on the JVM (`=`
  short-circuits on reference identity), so a NaN `:disabled` would render
  `<button disabled>` server-side where react-dom renders `<button>` — a
  hydration divergence. The predicate's docstring has the full account;
  `ssr_boolean_attr_react_parity_test` anchors the rosters to react-dom's
  bytes on the JVM alone."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.ssr.emit :as rf.ssr.emit]
            [re-frame.ssr.html-helpers :as rf.ssr.html-helpers]
            [re-frame.ssr.ui-tree :as rf.ssr.ui-tree]))

(deftest presence-value-truthy-answers-nan-the-way-javascript-does
  (testing "JavaScript truthiness, including the values Clojure answers
            differently (NaN, the number 0, \"\") and the ones a rewrite of the
            NaN or zero arm could take with it (0.0, infinity, the STRING \"0\",
            a whitespace string)"
    (doseq [[v present?] [[##NaN false]
                          [0 false]
                          [0.0 false]
                          [##Inf true]
                          ["0" true]
                          [" " true]
                          ["" false]
                          [nil false]
                          [true true]
                          [false false]]]
      (is (= present? (rf.ssr.html-helpers/presence-value-truthy? v)) (pr-str v)))))

(defn- tree-html [attrs]
  (rf.ssr.ui-tree/emit-ui-tree {:rf.ui/tree-version 1 :tag :button :attrs attrs}))

(defn- hiccup-html [attrs]
  (rf.ssr.emit/render-to-string [:button attrs] {}))

(deftest both-public-emitters-omit-a-presence-attribute-given-nan
  ;; Each pipeline against its OWN no-attribute baseline: they spell presence
  ;; differently by design (bare `disabled` / `disabled=""`).
  (is (= (hiccup-html {}) (hiccup-html {:disabled ##NaN})) "the hiccup path")
  (is (= (tree-html {}) (tree-html {:disabled ##NaN})) "the structural-tree path"))

(deftest an-overloaded-attribute-never-consults-the-presence-predicate
  (testing "`download` is `:overloaded`: a NON-boolean value is KEPT, so a
            change to the presence rule must not reach it"
    (is (not= (rf.ssr.emit/render-to-string [:a {}] {})
              (rf.ssr.emit/render-to-string [:a {:download ##NaN}] {})))))
