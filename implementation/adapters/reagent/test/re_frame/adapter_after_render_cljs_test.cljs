(ns re-frame.adapter-after-render-cljs-test
  "A copied Reagent adapter still defers callbacks until the render queue drains."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.core :as r]
            [re-frame.interop :as rf.interop]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter :ambient-frame nil})
  (fn [test-fn] (try (test-fn) (finally (r/flush)))))

(deftest copied-adapter-map-drives-live-after-render-hook
  (testing "a copied stock-Reagent adapter map still drives the live
            :adapter/after-render hook"
    (let [original (rf.substrate.adapter/current-adapter)
          copied   (assoc rf.adapter.reagent/adapter :rf.test/instrumentation-wrapper true)
          fired    (atom 0)]
      (rf.substrate.adapter/dispose-adapter!)
      (rf.substrate.adapter/install-adapter! copied)
      (try

        (rf.interop/after-render (fn [] (swap! fired inc)))
        (is (zero? @fired) "after-render still DEFERS under the copied map")
        (r/flush)
        (is (= 1 @fired)
            (str "the callback FIRED on the render-queue drain under the COPIED"
                 " Reagent adapter map — install published the copy's own"
                 " :after-render despite its distinct identity"))
        (finally
          (r/flush)
          (rf.substrate.adapter/dispose-adapter!)
          (rf.substrate.adapter/install-adapter! original))))))
