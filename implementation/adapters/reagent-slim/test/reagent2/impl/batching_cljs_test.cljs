(ns reagent2.impl.batching-cljs-test
  "Unit tests for reagent2.impl.batching (Stage 4-B, rf2-6hyy).

  Per IMPL-SPEC §12.1 + §12.5 R-005. Covers:

    - Microtask scheduling: enqueue triggers a single microtask;
      microtask body drains the queue.
    - Deduplication: same component enqueued multiple times before
      drain runs only once (cljsIsDirty flag).
    - Ordering: before-flush -> rea-flush -> render -> after-render.
    - flush! synchronous drain: callable from test code, identical
      contract to the microtask body.
    - do-after-render: callbacks fire after the render phase.
    - rea-schedule wiring: a Reaction whose dependency changes
      triggers a microtask drain via the batching ns's installed
      schedule fn.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [reagent2.ratom :as ratom]
            [reagent2.impl.batching :as batching]))

;; ---------------------------------------------------------------------------
;; Test helpers — fake component
;;
;; The render scheduler keys dedup on a `cljsIsDirty` field on a
;; component instance. A bare JS object suffices — we don't need a
;; real React component to exercise the queue, only the contract that
;; `forceUpdate` is called on each dirty component.
;; ---------------------------------------------------------------------------

(defn- fake-component [counter-atom]
  (let [c #js {}]
    (set! (.-forceUpdate c)
          (fn [] (swap! counter-atom inc)))
    c))

(defn- next-microtask
  "Return a Promise that resolves on the next microtask turn. Used by
  async tests to wait for the scheduler's microtask body to fire."
  []
  ;; Two ticks: first lets the scheduler's queueMicrotask fire, second
  ;; lets any cascade-microtask the body schedules also fire.
  (-> (js/Promise.resolve)
      (.then (fn [_] (js/Promise.resolve)))))

;; ---------------------------------------------------------------------------
;; Microtask scheduling + drain
;; ---------------------------------------------------------------------------

(deftest microtask-drain-fires-forceUpdate
  (testing "queue-render! schedules a microtask that calls forceUpdate"
    (async done
      (let [calls (atom 0)
            c     (fake-component calls)]
        (batching/queue-render! c)
        (is (= 0 @calls)
            "microtask hasn't fired yet — drain is async")
        (-> (next-microtask)
            (.then (fn [_]
                     (is (= 1 @calls)
                         "microtask body called forceUpdate exactly once")
                     (done))))))))

(deftest enqueue-during-drain-schedules-fresh-turn
  (testing "a component re-queued during drain fires on a later turn"
    ;; Per IMPL-SPEC §4.5: the scheduler does NOT flatten cascades
    ;; into the current drain. A component that re-queues during its
    ;; own forceUpdate gets a fresh microtask turn.
    (async done
      (let [calls (atom 0)
            c     #js {}]
        (set! (.-forceUpdate c)
              (fn []
                (swap! calls inc)
                ;; Mark-rendered ran already (in flush-render before
                ;; calling forceUpdate). Re-queue ourselves.
                (when (< @calls 3)
                  (batching/queue-render! c))))
        (batching/queue-render! c)
        (-> (next-microtask)
            (.then (fn [_] (next-microtask)))
            (.then (fn [_] (next-microtask)))
            (.then (fn [_]
                     ;; 3 distinct microtask turns means exactly 3 calls.
                     (is (= 3 @calls)
                         "re-queue during drain triggered 3 separate turns")
                     (done))))))))

;; ---------------------------------------------------------------------------
;; Deduplication
;; ---------------------------------------------------------------------------

(deftest dedup-same-component-once
  (testing "enqueueing the same component repeatedly before drain runs once"
    (async done
      (let [calls (atom 0)
            c     (fake-component calls)]
        (batching/queue-render! c)
        (batching/queue-render! c)
        (batching/queue-render! c)
        (batching/queue-render! c)
        (-> (next-microtask)
            (.then (fn [_]
                     (is (= 1 @calls)
                         "cljsIsDirty flag deduped 4 enqueues -> 1 forceUpdate")
                     (done))))))))

(deftest dedup-clears-after-render
  (testing "after the drain, the next enqueue triggers a fresh render"
    (async done
      (let [calls (atom 0)
            c     (fake-component calls)]
        (batching/queue-render! c)
        (-> (next-microtask)
            (.then (fn [_]
                     (is (= 1 @calls))
                     ;; Flag must clear; new enqueue picks up.
                     (batching/queue-render! c)
                     (next-microtask)))
            (.then (fn [_]
                     (is (= 2 @calls)
                         "second enqueue triggered second forceUpdate")
                     (done))))))))

;; ---------------------------------------------------------------------------
;; Ordering invariant: before-flush -> ratom/flush! -> render -> after-render
;; ---------------------------------------------------------------------------

(deftest ordering-before-render-after
  (testing "queues drain in spec order: before-flush, render, after-render"
    (async done
      (let [order (atom [])
            c     #js {}]
        (set! (.-forceUpdate c) (fn [] (swap! order conj :render)))
        (batching/do-before-flush (fn [] (swap! order conj :before)))
        (batching/queue-render! c)
        (batching/do-after-render (fn [] (swap! order conj :after)))
        (-> (next-microtask)
            (.then (fn [_]
                     (is (= [:before :render :after] @order)
                         "drain order matches IMPL-SPEC §4.1 step 1-4")
                     (done))))))))

;; ---------------------------------------------------------------------------
;; flush! — synchronous drain (the test-flush primitive's worker)
;; ---------------------------------------------------------------------------

(deftest flush-bang-drains-synchronously
  (testing "(batching/flush!) drains the queue without awaiting a microtask"
    (let [calls (atom 0)
          after (atom 0)
          c     (fake-component calls)]
      (batching/queue-render! c)
      (batching/do-after-render (fn [] (swap! after inc)))
      (batching/flush!)
      (is (= 1 @calls) "synchronous flush! ran forceUpdate")
      (is (= 1 @after) "synchronous flush! ran after-render"))))

(deftest flush-bang-suppresses-pending-microtask
  (testing "flush! cancels the pending microtask schedule"
    (async done
      (let [calls (atom 0)
            c     (fake-component calls)]
        (batching/queue-render! c)
        ;; Synchronous drain BEFORE the microtask fires.
        (batching/flush!)
        (is (= 1 @calls) "flush! drained synchronously")
        ;; The microtask still fires (we can't unschedule it) — but the
        ;; queue is empty, so no extra forceUpdate runs.
        (-> (next-microtask)
            (.then (fn [_]
                     (is (= 1 @calls)
                         "microtask body found empty queue; no extra render")
                     (done))))))))

;; ---------------------------------------------------------------------------
;; do-after-render hooks
;; ---------------------------------------------------------------------------

(deftest do-after-render-runs-once
  (testing "after-render fires once per registered fn per drain"
    (async done
      (let [fired (atom 0)]
        ;; do-after-render schedules a microtask drain itself (stock
        ;; Reagent semantics) — the fn fires on the next turn even
        ;; with no component to render.
        (batching/do-after-render (fn [] (swap! fired inc)))
        (-> (next-microtask)
            (.then (fn [_]
                     (is (= 1 @fired) "after-render fired exactly once")
                     ;; Trigger another drain — the previous after-render
                     ;; should NOT re-fire (queue cleared after drain).
                     (batching/queue-render! (fake-component (atom 0)))
                     (next-microtask)))
            (.then (fn [_]
                     (is (= 1 @fired) "after-render did not re-fire on next drain")
                     (done))))))))

(deftest do-after-render-multiple-callbacks-in-registration-order
  (testing "after-render queue runs callbacks in registration order"
    (async done
      (let [order (atom [])]
        (batching/do-after-render (fn [] (swap! order conj :a)))
        (batching/do-after-render (fn [] (swap! order conj :b)))
        (batching/do-after-render (fn [] (swap! order conj :c)))
        (-> (next-microtask)
            (.then (fn [_]
                     (is (= [:a :b :c] @order))
                     (done))))))))

;; ---------------------------------------------------------------------------
;; rea-schedule wiring (Stage 4-A hook → Stage 4-B implementation)
;;
;; Per the rea-schedule contract: when ratom's rea-queue gets its
;; first entry, it calls @rea-schedule. Stage 4-B installs
;; `batching/schedule` into that hook so the render-side scheduler
;; knows to drain the reactive queue as part of the next microtask.
;; ---------------------------------------------------------------------------

(deftest rea-schedule-wired-after-batching-load
  (testing "requiring reagent2.impl.batching installs batching/schedule into rea-schedule"
    (is (some? @ratom/rea-schedule)
        "rea-schedule got wired at batching ns load time")
    (is (fn? @ratom/rea-schedule)
        "the wired value is a fn")))

(deftest rea-schedule-triggers-microtask-drain
  (testing "a Reaction dep change schedules a microtask + drains via batching"
    (async done
      ;; Build: a ratom + an :auto-run nil Reaction subscribed via an
      ;; outer auto-run so the inner reaction enqueues itself on dep
      ;; change. The act of enqueueing fires rea-schedule -> batching
      ;; microtask. After the microtask, the rea-queue should be drained
      ;; (i.e. nil) and the inner reaction's value re-derefs current.
      (let [a       (ratom/atom 1)
            r       (ratom/make-reaction (fn [] (* @a 10)))
            outer   (ratom/make-reaction (fn [] @r) :auto-run true)]
        @outer ;; wire subscriptions
        (let [seen (atom nil)]
          (add-watch outer :w (fn [_ _ _ nu] (reset! seen nu)))
          (reset! a 2)
          ;; Microtask hasn't fired yet — but a synchronous-auto-run on
          ;; outer may already have observed the change; the contract
          ;; tested here is that the microtask path also drains.
          (-> (next-microtask)
              (.then (fn [_]
                       ;; outer saw r=20.
                       (is (= 20 @seen))
                       ;; Snapshot the reactive value too.
                       (is (= 20 @r))
                       (done)))))))))
