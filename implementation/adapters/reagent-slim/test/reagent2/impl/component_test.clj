(ns reagent2.impl.component-test
  "JVM-side tests for the compile-time form-classification helpers in
  reagent2.impl.component.

  The helper is a pure CLJ fn (`classify-form-body`) consumed by
  `re-frame.core/expand-reg-view` via `requiring-resolve`. The fold
  sits in the canonical `reg-view` macro — there is no separate
  `defview` macro.

  These tests run on the JVM. The CLJS-side runtime tests for
  wrap-render / create-class* / fn-to-class live in
  reagent2/impl/component_cljs_test.cljs."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk]
            [reagent2.impl.component :as component]
            [re-frame.core :as rf]))

;; ---------------------------------------------------------------------------
;; classify-form-body — Form-1 / Form-2 detection at compile time
;; ---------------------------------------------------------------------------

(deftest classify-form-body-classifies-by-the-literal-last-form
  (doseq [[why body expected]
          [["a body returning a hiccup vector is Form-1"
            '([:p "x"])                                  :reagent2/form-1]
           ["several body expressions whose last is hiccup is Form-1"
            '((let [x 1] nil) [:p :y])                   :reagent2/form-1]
           ["a body whose last form is a literal (fn ...) is Form-2"
            '((fn [n] [:p n]))                           :reagent2/form-2]
           ["fn*, the desugared form, is recognised too"
            '((fn* [n] [:p n]))                          :reagent2/form-2]
           ["setup expressions before the inner fn leave it Form-2"
            '((let [setup-state (atom 0)] nil)
              (fn [n] [:p n]))                           :reagent2/form-2]
           ;; The runtime fn? check in wrap-render handles this shape; the
           ;; compile-time classifier is conservative.
           ["a non-literal last form (a let returning a fn) is Form-1"
            '((let [f (fn [n] [:p n])] f))               :reagent2/form-1]
           ["an empty body is Form-1 (degenerate)"
            '()                                          :reagent2/form-1]]]
    (testing why
      (is (= expected (component/classify-form-body body))))))

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
