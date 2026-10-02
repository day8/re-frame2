(ns re-frame.story.predicates-test
  "Unit tests for the shared predicate-symbol resolver
  `re-frame.story.predicates/resolve-sym-pred`.

  The symbol→fn resolver is ONE shared leaf impl, called by both
  `assertions` and `runner-events`; these tests pin the resolver's own
  JVM `requiring-resolve` contract."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.predicates :as rf.story.predicates]))

(deftest resolve-sym-pred-resolves-a-jvm-var
  (testing "a fully-qualified symbol resolves to the var's value (JVM path)
            — the identical fn, not a copy"
    (is (identical? clojure.core/pos? (rf.story.predicates/resolve-sym-pred 'clojure.core/pos?)))))

(deftest resolve-sym-pred-returns-nil-on-miss
  (testing "an unresolvable symbol returns nil (caught, never throws)"
    (is (nil? (rf.story.predicates/resolve-sym-pred 'no.such.ns/missing-pred))))
  (testing "nil input returns nil"
    (is (nil? (rf.story.predicates/resolve-sym-pred nil)))))
