(ns re-frame.adapter.routing-token-cljs-test
  "`route-hook!` routes by the installed adapter's canonical `:rf.adapter/*`
  `:kind`, a token that survives a copy, rather than by object identity: a
  copied or wrapped canonical adapter map must still drive its adapter's live
  hooks (Spec 006 §Frame-provider via React context). An adapter with no
  canonical kind falls back to identity."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn- cold-adapter [test-fn]
  (rf.substrate.adapter/dispose-adapter!)
  (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
  (test-fn)
  (rf.substrate.adapter/dispose-adapter!)
  (rf.substrate.adapter/reset-lifecycle-state-for-tests!))

(use-fixtures :each cold-adapter)

(def ^:private probe-key :rf.test/routing-token-probe)

(deftest same-adapter-routes-by-canonical-kind-else-identity
  (let [canonical rf.substrate.plain-atom/adapter
        copied    (assoc canonical :instrumentation-wrapper true)
        custom-a  {:kind :custom :make-state-container identity}
        custom-b  {:kind :custom :make-state-container identity}
        kindless  (dissoc canonical :kind)]
    (doseq [[expected a b why]
            [[true  canonical copied                "a copy keeps the canonical :kind token"]
             [true  custom-a  custom-a              "a non-canonical adapter is the same as itself"]
             [false custom-a  custom-b              "a shared non-canonical :kind does not conflate two adapters"]
             [false kindless  (assoc kindless :x 1) "a kindless copy has no token to route by"]
             [false canonical nil                   "nothing installed"]
             [false nil       canonical             "a nil adapter is never the installed one"]]]
      (is (= expected (rf.substrate.adapter/same-adapter? a b)) why))))

(deftest routed-hook-fires-for-copied-canonical-map
  (rf.substrate.adapter/route-hook! rf.substrate.plain-atom/adapter probe-key
                                    (constantly :live-impl) (constantly :fell-through))
  (rf.substrate.adapter/install-adapter!
    (assoc rf.substrate.plain-atom/adapter :instrumentation-wrapper true))
  (is (= :live-impl ((rf.late-bind/get-fn probe-key)))))
