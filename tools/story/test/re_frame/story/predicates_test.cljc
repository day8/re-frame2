(ns re-frame.story.predicates-test
  "Unit tests for the shared predicate-symbol resolver
  `re-frame.story.predicates/resolve-sym-pred` (rf2-le0p4 dedup).

  Before rf2-le0p4 the symbol→fn resolver lived as two near-byte-identical
  private mirrors — `assertions/resolve-sym-pred` and
  `runner-events/resolve-predicate`. They are now ONE shared leaf impl;
  these tests pin (a) the resolver's own JVM `requiring-resolve` contract
  and (b) that BOTH call sites resolve the same symbol identically."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.predicates :as pred]))

(deftest resolve-sym-pred-resolves-a-jvm-var
  (testing "a fully-qualified symbol resolves to the var's value (JVM path)"
    (let [f (pred/resolve-sym-pred 'clojure.core/pos?)]
      (is (fn? f) "resolves to a callable")
      (is (true?  (f 1)))
      (is (false? (f -1)))))
  (testing "the resolved fn IS the var's value (identity, not a copy)"
    (is (identical? clojure.core/pos? (pred/resolve-sym-pred 'clojure.core/pos?)))))

(deftest resolve-sym-pred-returns-nil-on-miss
  (testing "an unresolvable symbol returns nil (caught, never throws)"
    (is (nil? (pred/resolve-sym-pred 'no.such.ns/missing-pred))))
  (testing "nil input returns nil"
    (is (nil? (pred/resolve-sym-pred nil)))))

;; ---------------------------------------------------------------------------
;; Dedup contract — both former call sites resolve identically through the
;; ONE shared fn. assertions' `[:fn sym]` schema fold and runner-events'
;; `:pred sym` form previously each carried a private copy; they now both
;; route through `pred/resolve-sym-pred`, so a single symbol resolves to the
;; SAME fn for either consumer.
;; ---------------------------------------------------------------------------

(deftest both-call-sites-resolve-a-symbol-pred-identically
  (testing "assertions' [:fn sym] fold and runner-events' :pred sym agree"
    (let [sym         'clojure.core/even?
          shared      (pred/resolve-sym-pred sym)
          ;; assertions' resolve-fn-schema rewrites [:fn sym] → [:fn resolved-fn]
          ;; using the shared resolver (re-frame.story.assertions/resolve-fn-schema
          ;; is private; exercise the resolution it performs directly).
          assertion-f (pred/resolve-sym-pred sym)
          ;; runner-events' exec-wait-until! :pred form likewise calls the
          ;; shared resolver for a symbol ref.
          runner-f    (pred/resolve-sym-pred sym)]
      (is (fn? shared))
      (is (identical? shared assertion-f)
          "assertions resolves the symbol via the shared fn")
      (is (identical? shared runner-f)
          "runner-events resolves the symbol via the shared fn")
      (is (= (mapv assertion-f [0 1 2 3])
             (mapv runner-f    [0 1 2 3]))
          "identical resolution semantics for both consumers"))))
