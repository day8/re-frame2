(ns re-frame.ui.test-render-sub-override-schema-jvm-test
  "rf2-vxgfnd.21 — `ui.test/render {:sub-overrides …}` (the explicit JVM
  Story-override door) applies the SAME schema validator + recover-to-nil as
  the Reagent-family `subscribe` path.

  End-to-end on the Tier-1 JVM structural render: a compiled view reads a sub
  declaring an output `:schema`; a `:sub-overrides` HIT that VIOLATES it emits
  `:rf.error/schema-validation-failure {:where :sub-override}` and the view
  surfaces nil (the impossible state never reaches the tree), while a
  conforming override renders unchanged with no failure. This is the door
  `re-frame.ui.reactive/*sub-overrides*` binding that
  `re-frame.ui.test/render` establishes — the compiled-path counterpart of
  the `re-frame.subs-override-seam-cljs-test` Reagent parity."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.late-bind :as late-bind]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace.tooling :as trace-tooling]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.test :as uit]))

(defn- with-stub-validator
  "Install a minimal REGISTERED validator (the same late-bind hook the Malli
  adapter and Spec 010 §step-6 use) so the override schema check runs without
  pulling the schemas artefact onto the ui test classpath. Saves + restores
  the prior hook."
  [test-fn]
  (let [prior (late-bind/get-fn :schemas/validate-with-registered-fn)]
    (late-bind/set-fn! :schemas/validate-with-registered-fn
      (fn [schema value] (if (= schema :int) (int? value) true)))
    (try (test-fn)
         (finally (late-bind/set-fn! :schemas/validate-with-registered-fn prior)))))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  with-stub-validator)

(defview counter
  "Reads a schema-bearing sub — the headless read the override door intercepts."
  []
  [:span.count "n=" (ui/sub [:count/value])])

(defn- collect-errors
  "Run `thunk` capturing every `:rf.error/*` trace event; return the events."
  [thunk]
  (let [seen (atom [])
        lid  ::override-render-errors]
    (trace-tooling/register-listener! lid
      (fn [ev] (when (= :error (:op-type ev)) (swap! seen conj ev))))
    (try (thunk) (finally (trace-tooling/unregister-listener! lid)))
    @seen))

(deftest override-violating-schema-renders-nil-and-emits
  (testing "a :sub-overrides HIT that violates the sub's :schema emits
            :where :sub-override and the compiled view surfaces nil"
    (rf/reg-sub :count/value {:schema :int} (fn [db _] (get db :n 0)))
    (let [f      (rf/make-frame {:initial-events [[:rf/set-db {:n 7}]]})
          tree   (atom nil)
          errors (collect-errors
                   (fn []
                     (reset! tree
                             (rf/with-frame f
                               (uit/render [counter]
                                           {:sub-overrides {[:count/value] "not-an-int"}})))))]
      (is (= "n=" (uit/text (some #(when (= :span (:tag %)) %) (tree-seq map? :children @tree))))
          "the violating override is replaced-with-default (nil), so only the
           literal prefix renders — the impossible value never reaches the tree")
      (let [failure (first (filter #(= :rf.error/schema-validation-failure
                                       (:operation %))
                                   errors))]
        (is (some? failure) "a schema-validation-failure was emitted")
        (is (= :sub-override (-> failure :tags :where))
            "the :where discriminator is :sub-override")))))

(deftest override-conforming-schema-renders-and-no-failure
  (testing "a :sub-overrides HIT that conforms surfaces unchanged, no failure"
    (rf/reg-sub :count/value {:schema :int} (fn [db _] (get db :n 0)))
    (let [f      (rf/make-frame {:initial-events [[:rf/set-db {:n 7}]]})
          tree   (atom nil)
          errors (collect-errors
                   (fn []
                     (reset! tree
                             (rf/with-frame f
                               (uit/render [counter]
                                           {:sub-overrides {[:count/value] 42}})))))]
      (is (= "n=42" (uit/text (some #(when (= :span (:tag %)) %) (tree-seq map? :children @tree))))
          "the conforming override renders unchanged")
      (is (not-any? #(= :rf.error/schema-validation-failure (:operation %)) errors)
          "no schema-validation-failure for a conforming override"))))

(deftest override-on-schemaless-sub-renders-any-value
  (testing "a sub with no :schema → the override renders with no validation"
    (rf/reg-sub :count/value (fn [db _] (get db :n 0)))
    (let [f      (rf/make-frame {:initial-events [[:rf/set-db {:n 7}]]})
          tree   (atom nil)
          errors (collect-errors
                   (fn []
                     (reset! tree
                             (rf/with-frame f
                               (uit/render [counter]
                                           {:sub-overrides {[:count/value] 99}})))))]
      (is (= "n=99" (uit/text (some #(when (= :span (:tag %)) %) (tree-seq map? :children @tree))))
          "any override value renders when the sub declares no schema")
      (is (not-any? #(= :rf.error/schema-validation-failure (:operation %)) errors)
          "no validation runs when the sub has no :schema"))))
