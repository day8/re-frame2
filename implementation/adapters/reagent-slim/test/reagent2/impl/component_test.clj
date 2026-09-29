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
;; End-to-end fold integration: reg-view's expansion stamps the tag
;;
;; When reagent-slim is on the classpath (as it is here),
;; `re-frame.core/expand-reg-view` consults
;; `reagent2.impl.component/classify-form-body` via requiring-resolve
;; and threads the form-tag through:
;;
;;   1. The registry slot's metadata (under `:reagent2/form`).
;;   2. The wrapper fn-form's meta (so renderers reading the fn alone,
;;      e.g. via `(rf/view :id)`, can still observe the tag).
;;
;; These tests inspect the macroexpansion shape directly without
;; running through the full reg-view runtime (which would require
;; rf/init! on a frame, etc).
;; ---------------------------------------------------------------------------

(defn- find-form-tag-in-expansion
  "Walk `expansion` looking for a `:reagent2/form` key in any map.
  Returns the value or nil. Used to assert the expansion stamped the
  tag without coupling the test to the precise expansion shape."
  [expansion]
  (let [seen (atom nil)]
    (clojure.walk/prewalk
      (fn [x]
        (when (and (map? x) (contains? x :reagent2/form))
          (reset! seen (:reagent2/form x)))
        x)
      expansion)
    @seen))

(deftest reg-view-expansion-carries-the-form-tag
  (doseq [[why coords sym tail expected]
          [["a Form-1 body stamps :reagent2/form-1"
            {:line 1 :column 1} 'widget-1 '([n] [:p n])               :reagent2/form-1]
           ["a Form-2 body (last form a literal fn) stamps :reagent2/form-2"
            {:line 1 :column 1} 'widget-2 '([_n0] (fn [n] [:p n]))    :reagent2/form-2]
           ["a docstring slot does not disturb the stamp"
            {}                  'docced   '("doc" [n] [:p n])         :reagent2/form-1]]]
    (testing why
      (is (= expected
             (find-form-tag-in-expansion
               (rf/expand-reg-view coords 'my.ns "my_ns.cljc" sym tail)))))))
