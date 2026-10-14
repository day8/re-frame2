(ns re-frame.test-quiet-fixture-integrity-test
  "The rule `re-frame.test-quiet.runner/uncallable-fixtures` states: a fixture
  `clojure.test` invokes as a LOOKUP (cljs.test's `{:before f :after g}` map)
  is named. The wiring is pinned across a process boundary in
  `re-frame.test-quiet-runner-contract-test`.

  `uncallable-fixtures` reads `(all-ns)` in the shipped call, so a probe
  namespace left behind with a map fixture would red this lane; that is why
  `with-probe-ns` removes it in a `finally`."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.test-quiet.runner :as rf.test-quiet.runner]))

(def ^:private map-fixture
  {:before (fn []) :after (fn [])})

(defn- with-probe-ns
  "Create `ns-sym` with `fixtures` under `meta-key`, as `use-fixtures` writes
  them, hand it to `f`, then remove it."
  [ns-sym meta-key fixtures f]
  (create-ns ns-sym)
  (alter-meta! (the-ns ns-sym) assoc meta-key fixtures)
  (try
    (f (the-ns ns-sym))
    (finally
      (remove-ns ns-sym))))

(deftest a-map-fixture-is-named-under-either-key
  (doseq [meta-key [:clojure.test/each-fixtures :clojure.test/once-fixtures]]
    (with-probe-ns 'probe.map-fixture-ns meta-key (list map-fixture)
      (fn [ns-obj]
        (is (= [['probe.map-fixture-ns meta-key map-fixture]]
               (rf.test-quiet.runner/uncallable-fixtures [ns-obj])))))))
