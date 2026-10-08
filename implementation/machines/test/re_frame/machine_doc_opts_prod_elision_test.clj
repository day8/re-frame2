(ns re-frame.machine-doc-opts-prod-elision-test
  "`reg-machine`'s literal opts-map `:doc` is pure documentation: the
  registrar strips it from the stored handler-meta under the production posture
  (`rf.interop/debug-enabled?` false) and keeps it in dev, while load-bearing
  opts such as `:schema` survive. Core's doc-elision test cannot reach
  `reg-machine` (machines is not on core's test classpath). The CLJS bundle-DCE
  half is pinned by `scripts/check-elision.cjs`, not here."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest reg-machine-opts-doc-stripped-under-disabled-debug-gate
  (with-redefs [rf.interop/debug-enabled? false]
    (rf/reg-machine :prod/machine
      {:doc "machine opts doc elided" :schema [:tuple :keyword]}
      {:initial :idle
       :states  {:idle {}}})
    (is (= {:schema [:tuple :keyword]}
           (select-keys (rf/handler-meta {:source :store :kind :event :id :prod/machine})
                        [:doc :schema])))))

(deftest reg-machine-opts-doc-retained-under-enabled-debug-gate
  (rf/reg-machine :dev/machine
    {:doc "machine opts doc kept in dev"}
    {:initial :idle
     :states  {:idle {}}})
  (is (= "machine opts doc kept in dev"
         (:doc (rf/handler-meta {:source :store :kind :event :id :dev/machine})))))
