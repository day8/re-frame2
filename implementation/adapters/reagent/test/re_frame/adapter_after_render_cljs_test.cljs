(ns re-frame.adapter-after-render-cljs-test
  "A callback handed to `re-frame.interop/after-render` under the Reagent
  adapter FIRES when Reagent's render queue drains — the adapter routes the
  hook to stock `reagent.core/after-render`. `late-bind-hooks-cljs-test` only
  pins the hook's publication, and the shared React suite's pin rides the
  React-hook spine's layout-effect sentinel, which Reagent does not use.
  `(r/flush)` forces the drain synchronously, so this needs no DOM and no
  timing."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.core :as r]
            [re-frame.interop :as rf.interop]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.test-support :as rf.test-support]))

;; The hook is routed, so the Reagent adapter must be the installed one; the
;; trailing `r/flush` stops an enqueued callback leaking into a later ns.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter :ambient-frame nil})
  (fn [test-fn] (try (test-fn) (finally (r/flush)))))

;; ---- (1) the callback fires once the render queue drains -------------------

(deftest after-render-runs-callback-on-queue-drain
  (testing "a callback handed to interop/after-render under the Reagent
            adapter FIRES when Reagent's render queue drains; (r/flush)
            forces that drain synchronously"
    (let [fired (atom 0)]
      (rf.interop/after-render (fn [] (swap! fired inc)))
      (is (zero? @fired) "not fired before the drain")
      (r/flush)
      (is (= 1 @fired)
          "callback fired exactly once after the render queue drained"))))

;; ---- (2) copied / wrapped adapter map routes to the live hook --
;;
;; `route-hook!` routes by the adapter's `:kind` token, not object identity;
;; by identity an `assoc`'d copy would make `interop/after-render` a silent
;; no-op.

(deftest copied-adapter-map-routes-to-live-after-render-hook
  (testing "a copied stock-Reagent adapter map still drives the live
            :adapter/after-render hook"
    (let [original (rf.substrate.adapter/current-adapter)
          copied   (assoc rf.adapter.reagent/adapter :rf.test/instrumentation-wrapper true)
          fired    (atom 0)]
      (rf.substrate.adapter/dispose-adapter!)
      (rf.substrate.adapter/install-adapter! copied)
      (try
        (is (= [false :rf.adapter/reagent]
               [(identical? rf.adapter.reagent/adapter (rf.substrate.adapter/current-adapter))
                (:kind (rf.substrate.adapter/current-adapter))])
            "precondition: the installed copy is a distinct map with the canonical :kind")
        (rf.interop/after-render (fn [] (swap! fired inc)))
        (is (zero? @fired) "after-render still DEFERS under the copied map")
        (r/flush)
        (is (= 1 @fired)
            (str "the callback FIRED on the render-queue drain under the COPIED"
                 " Reagent adapter map — the routed :adapter/after-render hook"
                 " dispatched to its live impl despite the copy's distinct"
                 " identity"))
        (finally
          (r/flush)
          (rf.substrate.adapter/dispose-adapter!)
          (rf.substrate.adapter/install-adapter! original))))))
