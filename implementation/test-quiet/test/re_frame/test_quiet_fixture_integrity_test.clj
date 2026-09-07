(ns re-frame.test-quiet-fixture-integrity-test
  "The rule `re-frame.test-quiet.runner/uncallable-fixtures` states, and the
  defect it exists to make impossible (rf2-4yw1).

  `cljs.test` accepts a MAP fixture — `(use-fixtures :each {:before f
  :after g})` — and this repo's `*_cljs_test.cljs` files use it throughout.
  `clojure.test` accepts functions only and validates nothing: it folds
  whatever `use-fixtures` was handed into a chain and calls it with the test
  thunk.  A map called with one argument is a KEY LOOKUP, returns nil, and
  the thunk never runs.  Maps are `IFn`, so nothing throws; the namespace
  contributes zero tests and the lane exits 0.

  EVERY TEST HERE PINS THE DEFECT BEFORE IT PINS THE GUARD.  The defect is
  pinned against `clojure.test/join-fixtures` itself — the function
  `test-all-vars` builds its fixture chain with — so a guard that went on
  agreeing with itself after clojure.test changed underneath would be
  caught.  The end-to-end half (a real `deftest` that would FAIL, absent
  from the tally, under the real `-main`, for both the map literal and a
  symbol bound to a map) is pinned across a process boundary in
  `re-frame.test-quiet-runner-contract-test`.

  A NOTE ON THE PROBE NAMESPACES BELOW.  `uncallable-fixtures` reads
  `(all-ns)` in the shipped call, so a probe namespace left behind with a
  map fixture would red THIS artefact's own lane.  `with-probe-ns` removes
  it in a `finally` for that reason, not for tidiness."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.test-quiet.runner :as rf.test-quiet.runner]))

;; ----------------------------------------------------------------------
;; Fixtures

(def ^:private map-fixture
  "The cljs.test form. Legitimate there; uncallable on the JVM."
  {:before (fn [])
   :after  (fn [])})

(defn- with-probe-ns
  "Create `ns-sym`, give it `fixtures` under `meta-key` exactly as
  `clojure.test/use-fixtures` writes them (the ARGS seq, not the first of
  them), hand the namespace to `f`, then remove it."
  [ns-sym meta-key fixtures f]
  (let [ns-obj (doto (create-ns ns-sym)
                 (alter-meta! assoc meta-key fixtures))]
    (try
      (f ns-obj)
      (finally
        (remove-ns ns-sym)))))

;; ----------------------------------------------------------------------
;; The defect, pinned against clojure.test itself.

(deftest a-map-fixture-swallows-the-test-thunk
  (testing "THE DEFECT: `join-fixtures` — what `test-all-vars` builds its
            chain with — calls each fixture with the test thunk, and a map
            called at arity 1 is a key lookup that returns nil"
    (let [ran? (atom false)
          thunk #(reset! ran? true)]
      (is (nil? ((clojure.test/join-fixtures [map-fixture]) thunk))
          "the map returns nil rather than invoking the chain")
      (is (false? @ran?)
          (str "the whole bug: the test thunk is never called, nothing"
               " throws (maps are IFn), and the namespace reports zero"
               " tests. Measured on Clojure 1.12: a single `deftest`"
               " asserting (= 1 2) under this fixture reports `Ran 0 tests"
               " containing 0 assertions. / 0 failures, 0 errors.` and"
               " exits 0."))))

  (testing "the fn form runs the thunk, which is the only difference"
    (let [ran? (atom false)
          fn-fixture (fn [t] (t))]
      ((clojure.test/join-fixtures [fn-fixture]) #(reset! ran? true))
      (is (true? @ran?)))))

;; ----------------------------------------------------------------------
;; The rule.

(deftest a-map-fixture-is-named-under-either-key
  (doseq [meta-key [:clojure.test/each-fixtures :clojure.test/once-fixtures]]
    (with-probe-ns 'probe.map-fixture-ns meta-key (list map-fixture)
      (fn [ns-obj]
        (let [offenders (rf.test-quiet.runner/uncallable-fixtures [ns-obj])]
          (is (= [['probe.map-fixture-ns meta-key map-fixture]] offenders)
              (str "both `use-fixtures` metadata keys are read; got "
                   (pr-str offenders))))))))

(deftest a-symbol-bound-to-a-map-is-named-too
  (testing "the case a static scan cannot see: `use-fixtures` writes the
            VALUE it was handed, so a map reaching the metadata through a
            var is indistinguishable from a map literal here"
    (let [lifecycle map-fixture]
      (with-probe-ns 'probe.bound-fixture-ns :clojure.test/each-fixtures
        (list lifecycle)
        (fn [ns-obj]
          (is (= 1 (count (rf.test-quiet.runner/uncallable-fixtures
                            [ns-obj])))))))))

(deftest function-fixtures-are-not-offenders
  (testing "the guard must not red the honest form — a bare fn, several fns
            from one call, and a namespace with no fixtures at all"
    (with-probe-ns 'probe.fn-fixture-ns :clojure.test/each-fixtures
      (list (fn [t] (t)) (fn [t] (t)))
      (fn [ns-obj]
        (is (= [] (rf.test-quiet.runner/uncallable-fixtures [ns-obj])))))
    (is (= [] (rf.test-quiet.runner/uncallable-fixtures [(the-ns 'clojure.core)])))
    (is (= [] (rf.test-quiet.runner/uncallable-fixtures [])))))

(deftest this-lane-is-itself-clean
  (testing "the shipped call — every namespace this JVM has loaded — and so
            a live control that the rule holds on real code, not only on
            probes"
    (is (= [] (rf.test-quiet.runner/uncallable-fixtures (all-ns))))))

;; ----------------------------------------------------------------------
;; The complaint.

(deftest the-complaint-names-the-namespace-and-the-repair
  (with-probe-ns 'probe.complaint-ns :clojure.test/each-fixtures
    (list map-fixture)
    (fn [ns-obj]
      (let [[[ns-sym meta-key fixture]]
            (rf.test-quiet.runner/uncallable-fixtures [ns-obj])]
        (is (= 'probe.complaint-ns ns-sym))
        (is (= :clojure.test/each-fixtures meta-key))
        (is (map? fixture)
            "the offending value travels with the complaint, so the message
             can name what it is rather than only that it is wrong")))))
