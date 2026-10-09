(ns re-frame.warn-once-clear-governance-cljs-test
  "Every adapter/views warn-once `defonce` cache must be wiped by the chained
  `:adapter/clear-warn-once-caches!` hook that `make-reset-runtime-fixture`
  fires, or a sibling test's first-encounter warning swallows a later
  same-key one. Caches enrol through
  `re-frame.late-bind/register-warn-once-clear-fn!`, which both chains the
  clear-fn and records the cache in `warn-once-clear-registry`. This checks
  that the named caches are enrolled and that one firing of the chain wipes
  every enrolled cache carrying `:arm` / `:armed?` probes. The JVM
  `re-frame.warn-once-clear-governance-test` checks that no source chains
  the key around the chokepoint."
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.set :as set]
            [re-frame.late-bind :as rf.late-bind]
            ;; Each populates the registry at ns-load.
            [re-frame.views]
            [re-frame.views.warn-once]
            [re-frame.adapter.uix]
            [re-frame.adapter.reagent-slim]))

(def ^:private expected-labels
  #{:views/warned-non-dom-roots
    :views/seen-render-keys
    :adapter/warned-non-dom-roots          ;; the React-hook spine's per-adapter cache
    :reagent-slim/warned-keyword-prop})

(deftest registry-enrols-every-named-cache-of-the-class
  (let [enrolled (set (map :label @rf.late-bind/warn-once-clear-registry))]
    (is (empty? (set/difference expected-labels enrolled))
        (str "an unenrolled cache is not chained, so the standard fixture will "
             "not re-arm it. Enrolled labels: " (pr-str enrolled)))))

(defn- probed-entries
  "Registry entries carrying both `:arm` and `:armed?` probes. A probe-less
  entry (the slim keyword-prop cache, whose atom is private to its bundle)
  is covered by the enrolment check above and its own re-arm test."
  []
  (filter (fn [{:keys [arm armed?]}] (and (fn? arm) (fn? armed?)))
          @rf.late-bind/warn-once-clear-registry))

(deftest canonical-chain-wipes-every-enrolled-cache
  (let [entries (probed-entries)
        chain   (rf.late-bind/get-fn :adapter/clear-warn-once-caches!)]
    (is (seq entries)
        "precondition: at least one probe-carrying cache is enrolled")
    (doseq [{:keys [arm]} entries] (arm))
    (doseq [{:keys [label armed?]} entries]
      (is (true? (boolean (armed?)))
          (str "precondition: " label " is armed before the chain fires")))
    (chain)
    (doseq [{:keys [label armed?]} entries]
      (is (false? (boolean (armed?)))
          (str label " survived the canonical chain: it is enrolled but its "
               "clear-fn is not wired into the chain the standard fixture fires")))))
