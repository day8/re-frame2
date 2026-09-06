(ns re-frame.adapter-after-render-cljs-test
  "Behavioural coverage for the Reagent adapter's `:adapter/after-render`
  hook (rf2-ynjts.2 — testing coverage & rigour pass).

  The Reagent adapter injects stock `reagent.core/after-render` under the
  `:adapter/after-render` hook (see `re-frame.adapter.reagent` `:hook-ops`).
  `re-frame.interop/after-render` reads through that hook, so the public
  schedule-a-callback-after-render seam resolves to Reagent's render
  queue when the Reagent adapter is installed.

  Existing coverage and the gap this file closes:

    - `re-frame.late-bind-hooks-cljs-test` already pins that the
      `:adapter/after-render` key is PUBLISHED in the late-bind table and
      listed in the directory with this adapter as a producer. That is a
      wiring/publication pin — it never CALLS the hook.

    - The React-hook adapters (UIx) get the BEHAVIOURAL contract
      from the shared react-suite
      (`react-shared-suite/assert-after-render-runs-after-commit`), but
      that assertion is React-hook-spine specific: it relies on the
      spine's `useLayoutEffect` sentinel injected by the React `make-render`
      and is gated on a real DOM (`with-browser-act`). It does NOT — and
      cannot — exercise Reagent's mechanism (`r/after-render` drives
      Reagent's own batching queue, not a React `useLayoutEffect`
      sentinel). So the Reagent-side behaviour was unverified anywhere:
      no test confirmed that a callback handed to `interop/after-render`
      under the Reagent adapter actually FIRES.

  This file plants the Reagent-specific behavioural pin. Stock Reagent's
  `r/after-render` enqueues onto `reagent.impl.batching`'s render queue and
  arms a deferred drain (`next-tick`, which is `fake-raf` ⇒ a 16ms
  setTimeout under :node-test since there is no `js/window`); the queue
  drains in `flush-queues` AFTER the component / ratom flush. `(r/flush)`
  forces that drain synchronously (it calls `batch/flush` ⇒ `flush-queues`
  ⇒ `flush-after-render`), so we can pin the behaviour deterministically
  with NO DOM, NO component, and NO timing dependence. We assert through
  the real `interop/after-render` seam (not `r/after-render` directly) so
  the test exercises the adapter's injected `:adapter/after-render` op
  end-to-end via the late-bind routing.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.core :as r]
            [re-frame.interop :as rf.interop]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.test-support :as rf.test-support]))

;; The `:adapter/after-render` hook is routed: `interop/after-render`
;; runs Reagent's `r/after-render` ONLY when the Reagent adapter is the
;; currently-installed adapter (per `substrate-adapter/route-hook!`).
;; Other adapter ns'es loaded in the same test bundle publish the same
;; key, so we MUST install the Reagent adapter to make the routed closure
;; dispatch to Reagent's reader rather than chain to a sibling —
;; `make-reset-runtime-fixture {:adapter reagent-adapter/adapter}` does the
;; snapshot/restore + frames-reset + dispose/install. `:ambient-frame nil`
;; preserves the suite's no-ambient-scope behaviour (no frame-scoped ops in
;; the bodies). A composed teardown fixture drains Reagent's after-render
;; queue / pending fake-raf (`r/flush`) so an enqueued callback can't leak
;; into a later test ns sharing the bundle.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter :ambient-frame nil})
  (fn [test-fn] (try (test-fn) (finally (r/flush)))))

;; ---- (1) the hook is reachable through interop under the Reagent adapter ---

(deftest after-render-enqueue-does-not-throw-or-fire-synchronously
  (testing "with the Reagent adapter installed, interop/after-render
            accepts a callback (the routed :adapter/after-render hook
            dispatches to Reagent's r/after-render) and DEFERS it — the
            callback must NOT run during the enqueue call"
    (let [fired (atom 0)]
      ;; The return value is Reagent-internal (stock r/after-render arms a
      ;; deferred drain and hands back its scheduler token); we assert the
      ;; DEFERRAL contract, not the token shape.
      (rf.interop/after-render (fn [] (swap! fired inc)))
      (is (zero? @fired)
          "after-render defers — the callback does not fire synchronously
           during the enqueue call"))))

;; ---- (2) the callback fires once the render queue drains -------------------

(deftest after-render-runs-callback-on-queue-drain
  (testing "a callback handed to interop/after-render under the Reagent
            adapter FIRES when Reagent's render queue drains; (r/flush)
            forces that drain synchronously"
    (let [fired (atom 0)]
      (rf.interop/after-render (fn [] (swap! fired inc)))
      (is (zero? @fired) "not fired before the drain")
      (r/flush)
      (is (= 1 @fired)
          "callback fired exactly once after the render queue drained")
      ;; The queue is re-armed per drain (not a one-shot): a second
      ;; enqueue + drain fires again, and does NOT re-fire the first.
      (rf.interop/after-render (fn [] (swap! fired inc)))
      (r/flush)
      (is (= 2 @fired)
          "a subsequent after-render callback also fires on the next drain")
      ;; A drain with nothing queued is a no-op — the prior callbacks do
      ;; not re-run (the queue was cleared on drain).
      (r/flush)
      (is (= 2 @fired)
          "an empty drain does not re-fire previously-drained callbacks"))))

;; ---- (3) ordering: multiple callbacks run in enqueue order on one drain ----

(deftest after-render-multiple-callbacks-run-in-enqueue-order
  (testing "multiple callbacks enqueued before a single drain all fire,
            in enqueue order (Reagent's afterRender queue is FIFO)"
    (let [order (atom [])]
      (rf.interop/after-render (fn [] (swap! order conj :first)))
      (rf.interop/after-render (fn [] (swap! order conj :second)))
      (rf.interop/after-render (fn [] (swap! order conj :third)))
      (is (= [] @order) "none fire before the drain")
      (r/flush)
      (is (= [:first :second :third] @order)
          "all enqueued callbacks fire in enqueue order on a single drain"))))

;; ---- (4) copied / wrapped adapter map routes to the live hook (rf2-dkl5z1) --
;;
;; `route-hook!` routes by stable token (the canonical :rf.adapter/* :kind),
;; not object identity — so a copied / wrapped stock-Reagent adapter map STILL
;; drives its live `:adapter/after-render` hook. Pre-fix, installing an
;; `assoc`'d copy made the routed closure's identity guard fail and the hook
;; fell through to the `(constantly nil)` chain bottom: `interop/after-render`
;; became a silent no-op (the callback would never fire on r/flush).

(deftest copied-adapter-map-routes-to-live-after-render-hook
  (testing "a copied stock-Reagent adapter map still drives the live
            :adapter/after-render hook (rf2-dkl5z1)"
    (let [original (rf.substrate.adapter/current-adapter-spec)
          copied   (assoc rf.adapter.reagent/adapter :rf.test/instrumentation-wrapper true)
          fired    (atom 0)]
      (rf.substrate.adapter/dispose-adapter!)
      (rf.substrate.adapter/install-adapter! copied)
      (try
        (is (false? (identical? rf.adapter.reagent/adapter (rf.substrate.adapter/current-adapter-spec)))
            "precondition: the installed copy is NOT identical to the routed canonical map")
        (is (= :rf.adapter/reagent (rf.substrate.adapter/current-adapter))
            "precondition: the copy preserves the canonical :kind token")
        (rf.interop/after-render (fn [] (swap! fired inc)))
        (is (zero? @fired) "after-render still DEFERS under the copied map")
        (r/flush)
        (is (= 1 @fired)
            (str "the callback FIRED on the render-queue drain under the COPIED"
                 " Reagent adapter map — the routed :adapter/after-render hook"
                 " dispatched to its live impl despite the copy's distinct"
                 " identity (rf2-dkl5z1)"))
        (finally
          (r/flush)
          (rf.substrate.adapter/dispose-adapter!)
          (rf.substrate.adapter/install-adapter! original))))))
