(ns reagent2.impl.component-test
  "JVM-side tests for the compile-time form-classification helpers in
  reagent2.impl.component (Stage 4-C, rf2-6hyy).

  The helper is a pure CLJ fn (`classify-form-body`) consumed by
  `re-frame.views-macros/expand-reg-view` via `requiring-resolve`. Per
  the rf2-yfbx decision, the fold sits in the canonical `reg-view`
  macro — there is no separate `defview` macro.

  These tests run on the JVM. The CLJS-side runtime tests for
  wrap-render / create-class* / fn-to-class live in
  reagent2/impl/component_cljs_test.cljs."
  (:require [clojure.test :refer [deftest is testing]]
            [reagent2.impl.component :as component]
            [re-frame.views-macros :as vm]))

;; ---------------------------------------------------------------------------
;; classify-form-body — Form-1 / Form-2 detection at compile time
;; ---------------------------------------------------------------------------

(deftest classify-form-body-form-1-vector
  (testing "body returning a hiccup vector classifies as Form-1"
    (is (= :reagent2/form-1
           (component/classify-form-body '([:p "x"]))))))

(deftest classify-form-body-form-1-multi-expr
  (testing "Form-1 with multiple body expressions (last is hiccup)"
    (is (= :reagent2/form-1
           (component/classify-form-body '((let [x 1] nil) [:p :y]))))))

(deftest classify-form-body-form-2-fn
  (testing "body whose last form is a literal (fn ...) classifies as Form-2"
    (is (= :reagent2/form-2
           (component/classify-form-body '((fn [n] [:p n])))))))

(deftest classify-form-body-form-2-fn-star
  (testing "fn* (the desugared form) is recognised too"
    (is (= :reagent2/form-2
           (component/classify-form-body '((fn* [n] [:p n])))))))

(deftest classify-form-body-form-2-with-setup
  (testing "Form-2 with setup expressions before the inner fn"
    (is (= :reagent2/form-2
           (component/classify-form-body
             '((let [setup-state (atom 0)] nil)
               (fn [n] [:p n])))))))

(deftest classify-form-body-non-literal-fn-is-form-1
  (testing "non-literal last form (e.g. let returning a fn) classifies as Form-1"
    ;; The runtime fn? check in wrap-render handles this correctly;
    ;; the compile-time classifier is conservative.
    (is (= :reagent2/form-1
           (component/classify-form-body '((let [f (fn [n] [:p n])] f)))))))

(deftest classify-form-body-empty
  (testing "empty body returns Form-1 (degenerate)"
    (is (= :reagent2/form-1 (component/classify-form-body '())))))

;; ---------------------------------------------------------------------------
;; tag-form-meta — produces the meta map stamped on wrapper fns
;; ---------------------------------------------------------------------------

(deftest tag-form-meta-shape
  (testing "tag-form-meta returns a single-key map under :reagent2/form"
    (is (= {:reagent2/form :reagent2/form-1}
           (component/tag-form-meta :reagent2/form-1)))
    (is (= {:reagent2/form :reagent2/form-2}
           (component/tag-form-meta :reagent2/form-2)))))

;; ---------------------------------------------------------------------------
;; End-to-end fold integration: reg-view's expansion stamps the tag
;;
;; When reagent-slim is on the classpath (as it is here),
;; `re-frame.views-macros/expand-reg-view` consults
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

(deftest fold-reg-view-form-1-expansion-tags-form-1
  (testing "reg-view with a Form-1 body emits an expansion carrying :reagent2/form-1"
    (require 'clojure.walk)
    (let [exp (vm/expand-reg-view {:line 1 :column 1}
                                  'my.ns "my_ns.cljc"
                                  'widget-1 '([n] [:p n]))]
      (is (= :reagent2/form-1 (find-form-tag-in-expansion exp))
          "Form-1 tag landed in the expansion"))))

(deftest fold-reg-view-form-2-expansion-tags-form-2
  (testing "reg-view with a Form-2 body (last form is a literal fn) emits :reagent2/form-2"
    (require 'clojure.walk)
    (let [exp (vm/expand-reg-view {:line 1 :column 1}
                                  'my.ns "my_ns.cljc"
                                  'widget-2 '([_n0]
                                              (fn [n] [:p n])))]
      (is (= :reagent2/form-2 (find-form-tag-in-expansion exp))
          "Form-2 tag landed in the expansion"))))

(deftest fold-reg-view-docstring-still-tags
  (testing "a docstring slot doesn't disturb the form-tag stamping"
    (require 'clojure.walk)
    (let [exp (vm/expand-reg-view {} 'my.ns "my_ns.cljc"
                                  'docced '("doc" [n] [:p n]))]
      (is (= :reagent2/form-1 (find-form-tag-in-expansion exp))))))
