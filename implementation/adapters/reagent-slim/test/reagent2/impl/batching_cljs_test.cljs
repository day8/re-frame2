(ns reagent2.impl.batching-cljs-test
  "Unit tests for reagent2.impl.batching: the microtask render queue, its
  dedup, drain order and after-render callbacks, the synchronous flush!, and
  the rea-schedule hook that drains reactions."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [reagent2.ratom :as ratom]
            [reagent2.impl.batching :as batching]))

(defn- fake-component
  "A bare object counting `forceUpdate` calls; the queue needs nothing else."
  [counter-atom]
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

(deftest enqueue-during-drain-schedules-fresh-turn
  (testing "a component re-queued during drain fires on a later turn"
    ;; A flattening drain also ends at 3 calls, so the count is read at each
    ;; turn boundary. Each `.then` hop is exactly one microtask tick
    ;; (`next-microtask` would span several, letting all three turns fit in
    ;; one hop).
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
        (-> (js/Promise.resolve)
            (.then (fn [_]
                     (is (= 1 @calls)
                         "turn 1 ran the initial enqueue only — the re-queue was held over")))
            (.then (fn [_]
                     (is (= 2 @calls)
                         "turn 2 ran the first re-queue only — still one call per turn")))
            (.then (fn [_]
                     (is (= 3 @calls)
                         "turn 3 ran the second re-queue — 3 calls across 3 separate turns")
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


;; ---------------------------------------------------------------------------
;; Ordering invariant: ratom/flush! -> render -> after-render
;; ---------------------------------------------------------------------------

(deftest ordering-render-after
  (testing "queues drain in spec order: render, after-render"
    (async done
      (let [order (atom [])
            c     #js {}]
        (set! (.-forceUpdate c) (fn [] (swap! order conj :render)))
        (batching/queue-render! c)
        (batching/do-after-render (fn [] (swap! order conj :after)))
        (-> (next-microtask)
            (.then (fn [_]
                     (is (= [:render :after] @order)
                         "drain order matches IMPL-SPEC §4.1 step 1-4")
                     (done))))))))

;; ---------------------------------------------------------------------------
;; flush! — synchronous drain (the test-flush primitive's worker)
;; ---------------------------------------------------------------------------

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

;; ---------------------------------------------------------------------------
;; A throwing after-render callback must not strand the ones queued behind
;; it: the queue is reset before the drain, so they would be lost for good.
;; ---------------------------------------------------------------------------

(deftest after-render-throw-does-not-strand-later-callbacks
  (testing "an after-render callback that throws does not prevent
            later-registered callbacks from running"
    (async done
      (let [order (atom [])]
        (batching/do-after-render (fn [] (swap! order conj :a)))
        (batching/do-after-render (fn [] (throw (js/Error. "boom"))))
        (batching/do-after-render (fn [] (swap! order conj :c)))
        (-> (next-microtask)
            (.then (fn [_]
                     (is (= [:a :c] @order)
                         "callback :c still ran after the middle callback threw")
                     (done))))))))

;; ---------------------------------------------------------------------------
;; Loading reagent2.impl.batching installs its scheduler into ratom's
;; rea-schedule hook, which the rea-queue calls on its first entry.
;; ---------------------------------------------------------------------------

(deftest rea-schedule-wired-after-batching-load
  (is (fn? @ratom/rea-schedule)))

(deftest rea-schedule-triggers-microtask-drain
  (testing "a Reaction dep change schedules a microtask + drains via batching"
    (async done
      ;; The inner, non-auto-run reaction enqueues itself on a dep change,
      ;; which fires rea-schedule and so the batching microtask.
      (let [a       (ratom/atom 1)
            r       (ratom/make-reaction (fn [] (* @a 10)))
            outer   (ratom/make-reaction (fn [] @r) :auto-run true)]
        @outer ;; wire subscriptions
        (let [seen (atom nil)]
          (add-watch outer :w (fn [_ _ _ nu] (reset! seen nu)))
          (reset! a 2)
          (-> (next-microtask)
              (.then (fn [_]
                       (is (= 20 @seen))
                       (done)))))))))
