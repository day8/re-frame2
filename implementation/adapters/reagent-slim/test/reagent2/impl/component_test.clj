(ns reagent2.impl.component-test
  "JVM-side pin that reagent-slim detects a view's shape only at render
  time, in `reagent2.impl.component/wrap-render`.

  These tests run on the JVM. The CLJS-side runtime tests for
  wrap-render / create-class* / fn-to-class live in
  reagent2/impl/component_cljs_test.cljs."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk]
            [re-frame.core :as rf]))

;; ---------------------------------------------------------------------------
;; reg-view's expansion is the same with reagent-slim on the classpath
;;
;; reagent-slim detects a view's shape when it renders it, so
;; `re-frame.core/expand-reg-view` emits no form tag here either: none in the
;; registry slot's metadata, and none on the render fn.
;; ---------------------------------------------------------------------------

(defn- form-tags-in-expansion
  "Every `:reagent2/form` value in `expansion`, whether a key of a map or of a
  form's metadata."
  [expansion]
  (let [seen (atom [])]
    (clojure.walk/prewalk
      (fn [x]
        (doseq [m [x (meta x)]]
          (when (and (map? m) (contains? m :reagent2/form))
            (swap! seen conj (:reagent2/form m))))
        x)
      expansion)
    @seen))

(deftest reg-view-expansion-carries-no-form-tag
  (doseq [[why coords sym tail]
          [["a body returning hiccup"
            {:line 1 :column 1} 'widget-1 '([n] [:p n])]
           ["a body whose last form is a literal fn"
            {:line 1 :column 1} 'widget-2 '([_n0] (fn [n] [:p n]))]
           ["a docstring slot"
            {}                  'docced   '("doc" [n] [:p n])]]]
    (testing why
      (is (empty? (form-tags-in-expansion
                    (rf/expand-reg-view coords 'my.ns "my_ns.cljc" sym tail)))))))
