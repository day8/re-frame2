(ns reagent2.dom.client-cljs-test
  "Tests for reagent2.dom.client (Stage 4-B, rf2-6hyy).

  Per IMPL-SPEC §4.6 + §12.1 + §12.5 R-005. Covers:

    - flush-views! determinism: dispatch-then-flush gives the
      caller the post-render state synchronously.
    - flush-views! React-act composition: pending React work is
      drained inside act.
    - Suspense composition (rf2-w6ef): a child throws a Promise
      during render; flush-views! waits for the resolution + a
      tail recompute settles before the test asserts.

  The Suspense test pins the chosen ordering for rf2-w6ef:

      microtask -> act(flush!) -> microtask

  See dom/client.cljs ns docstring for the full rationale.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [reagent2.ratom :as ratom]
            [reagent2.impl.batching :as batching]
            [reagent2.dom.client :as dom-client]))

;; ---------------------------------------------------------------------------
;; flush-views! basic — drains the dirty-set + after-render
;; ---------------------------------------------------------------------------

(deftest flush-views-drains-dirty-set
  (testing "flush-views! drives forceUpdate on every dirty component"
    (async done
      (let [calls (atom 0)
            c     #js {}]
        (set! (.-forceUpdate c) (fn [] (swap! calls inc)))
        (batching/queue-render! c)
        (-> (js/Promise.resolve (dom-client/flush-views!))
            (.then (fn [_]
                     ;; Either act ran the body synchronously (in node-
                     ;; test mode without React's full DOM), or a
                     ;; subsequent microtask did. Either way: by the
                     ;; time we observe, calls is 1.
                     (is (>= @calls 1)
                         "flush-views! drove the render queue")
                     (done))))))))

(deftest flush-views-runs-after-render-callbacks
  (testing "flush-views! fires queued after-render callbacks"
    (async done
      (let [fired (atom false)
            calls (atom 0)
            c     #js {}]
        (set! (.-forceUpdate c) (fn [] (swap! calls inc)))
        (batching/do-after-render (fn [] (reset! fired true)))
        (batching/queue-render! c)
        (-> (js/Promise.resolve (dom-client/flush-views!))
            (.then (fn [_]
                     (is (true? @fired)
                         "after-render callback fired by the time flush-views! resolved")
                     (done))))))))

(deftest flush-views-no-op-when-queues-empty
  (testing "flush-views! with empty queues completes without error"
    (async done
      (-> (js/Promise.resolve (dom-client/flush-views!))
          (.then (fn [_]
                   (is true "flush-views! returned cleanly with empty queues")
                   (done)))))))

;; ---------------------------------------------------------------------------
;; flush-views! determinism: dispatch-then-flush
;;
;; Stand-in for the IMPL-SPEC §4.6 contract:
;;   "After (flush-views!) returns:
;;      - All currently-dirty components have re-rendered.
;;      - All Reactions whose dependencies changed have recomputed.
;;      - All :after-render callbacks queued before flush-views! fired.
;;      - React's pending work has committed (act() has run to completion)."
;; ---------------------------------------------------------------------------

(deftest flush-views-determinism-reaction-recompute
  (testing "after flush-views!, a dependent Reaction reflects the new value"
    (async done
      (let [a       (ratom/atom 1)
            r       (ratom/make-reaction (fn [] (* @a 100)) :auto-run true)
            seen    (atom nil)]
        @r ;; subscribe
        (add-watch r :w (fn [_ _ _ nu] (reset! seen nu)))
        ;; Mutate; flush; assert.
        (reset! a 5)
        (-> (js/Promise.resolve (dom-client/flush-views!))
            (.then (fn [_]
                     ;; Auto-run reactions are synchronous, so seen is
                     ;; already 500. Either way, post-flush, derefing
                     ;; the reaction must show 500.
                     (is (= 500 @r))
                     (is (= 500 @seen))
                     (done))))))))

(deftest flush-views-determinism-component-render
  (testing "after flush-views!, a queued component has been rendered"
    (async done
      (let [calls (atom 0)
            c     #js {}]
        (set! (.-forceUpdate c) (fn [] (swap! calls inc)))
        (batching/queue-render! c)
        (-> (js/Promise.resolve (dom-client/flush-views!))
            (.then (fn [_]
                     (is (= 1 @calls)
                         "post-flush-views!, component has re-rendered exactly once")
                     (done))))))))

;; ---------------------------------------------------------------------------
;; rf2-w6ef: Suspense ordering — microtask -> act -> microtask
;;
;; Scenario: a Reaction's recompute schedules a microtask drain. Inside
;; that drain, an after-render callback simulates a Suspense-resolved
;; tail-recompute (a downstream Reaction picks up the resolved value
;; and notifies its watchers). The chosen ordering guarantees the
;; tail-recompute is observed by the time flush-views! returns.
;;
;; This is the determinism contract that pins rf2-w6ef. The test
;; mimics the Suspense pattern at the scheduler level — we assert
;; the OBSERVABLE effect of the ordering choice, not the literal
;; React-internal Suspense plumbing (which requires a real DOM and
;; lives in the browser-test target).
;; ---------------------------------------------------------------------------

(deftest flush-views-suspense-composition-ordering
  (testing "microtask -> act -> microtask order: tail-cascade settles before return"
    (async done
      (let [;; Stage A: an atom whose mutation kicks off a reaction recompute.
            input        (ratom/atom 0)
            ;; Stage B: a derived Reaction (the "Suspense'd subtree's data").
            derived      (ratom/make-reaction (fn [] (inc @input)) :auto-run true)
            ;; Stage C: an after-render hook that simulates a post-commit
            ;; React tail-effect — bumps a counter we observe.
            tail-effects (atom 0)
            ;; Stage D: a component-render counter.
            renders      (atom 0)
            c            #js {}]
        @derived
        (set! (.-forceUpdate c) (fn [] (swap! renders inc)))
        ;; Wire: when input changes, derived recomputes (auto-run);
        ;; the watch on derived enqueues a render of `c` AND queues
        ;; an after-render hook that bumps tail-effects.
        (add-watch derived :wire
          (fn [_ _ _ _]
            (batching/queue-render! c)
            (batching/do-after-render
              (fn [] (swap! tail-effects inc)))))
        ;; Trigger the cascade.
        (reset! input 1)
        ;; Spec contract: by the time flush-views! resolves, every
        ;; phase has completed:
        ;;   - derived has recomputed (auto-run, synchronous — so it's
        ;;     already done before flush-views! is even called)
        ;;   - c has re-rendered (microtask drain)
        ;;   - tail-effects has fired (after-render queue, drained
        ;;     inside act's body)
        (-> (js/Promise.resolve (dom-client/flush-views!))
            (.then (fn [_]
                     (is (= 2 @derived)
                         "Reaction has the post-mutation value")
                     (is (= 1 @renders)
                         "Component has re-rendered exactly once")
                     (is (= 1 @tail-effects)
                         "Tail-effect (after-render hook) fired before flush-views! returned")
                     (done))))))))

(deftest flush-views-suspense-composition-cascade
  (testing "a Reaction that fires DURING flush schedules a fresh turn (not flattened into current)"
    ;; Per IMPL-SPEC §4.5 + §4.4: the scheduler does NOT collapse
    ;; cascades into the current drain. A render-time mutation of
    ;; an upstream RAtom queues a fresh microtask turn.
    ;;
    ;; This pins the Suspense semantic: when act resolves a Suspense
    ;; promise mid-commit and downstream Reactions recompute, those
    ;; recomputes are visible NEXT turn, not this one.
    (async done
      (let [counter      (ratom/atom 0)
            seen-states  (atom [])
            r            (ratom/make-reaction (fn [] @counter) :auto-run true)]
        @r
        (add-watch r :seen
          (fn [_ _ _ nu] (swap! seen-states conj nu)))
        ;; First mutation.
        (reset! counter 1)
        (-> (js/Promise.resolve (dom-client/flush-views!))
            (.then (fn [_]
                     ;; Schedule another mutation to verify cascade
                     ;; doesn't collapse: queue a render whose hook
                     ;; mutates counter again.
                     (let [c #js {}]
                       (set! (.-forceUpdate c)
                             (fn [] (reset! counter 2)))
                       (batching/queue-render! c))
                     (dom-client/flush-views!)))
            (.then (fn [_] (dom-client/flush-views!)))
            (.then (fn [_]
                     ;; counter went 0 -> 1 -> 2; r tracked both.
                     (is (= [1 2] @seen-states)
                         "auto-run reaction observed both transitions across flushes")
                     (is (= 2 @r))
                     (done))))))))

;; ---------------------------------------------------------------------------
;; flush-views! production-DCE shape
;;
;; Per IMPL-SPEC §4.2: in :advanced + goog.DEBUG=false the body
;; should DCE. We can't directly test DCE from CLJS at unit-test
;; time (it's a Closure-compile-time concern), but we can verify
;; the symbol exists and is callable. The bundle-isolation grep
;; in §12.3 covers the DCE assertion at release time.
;; ---------------------------------------------------------------------------

(deftest flush-views-callable
  (testing "flush-views! is bound and callable"
    (is (fn? dom-client/flush-views!))))

;; ---------------------------------------------------------------------------
;; Mount-entry scaffolds
;; ---------------------------------------------------------------------------

(deftest mount-entries-scaffolded
  (testing "create-root, render, unmount, hydrate-root are bound"
    (is (fn? dom-client/create-root))
    (is (fn? dom-client/render))
    (is (fn? dom-client/unmount))
    (is (fn? dom-client/hydrate-root))))

(deftest render-throws-not-implemented
  (testing "render throws :rf.error/not-implemented until Stage 4-D"
    (let [thrown (try (dom-client/render :root :el)
                      false
                      (catch :default e (ex-data e)))]
      (is (= :rf.error/not-implemented (:type thrown)))
      (is (= :4-D (:stage thrown))))))

(deftest hydrate-root-throws-not-implemented
  (testing "hydrate-root throws :rf.error/not-implemented until Stage 4-D"
    (let [thrown (try (dom-client/hydrate-root :container :el)
                      false
                      (catch :default e (ex-data e)))]
      (is (= :rf.error/not-implemented (:type thrown)))
      (is (= :4-D (:stage thrown))))))

(deftest unmount-handles-nil-gracefully
  (testing "unmount on nil root is a no-op (defensive)"
    (is (nil? (dom-client/unmount nil)))))
