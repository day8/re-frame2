(ns reagent2.impl.batching-cljs-test
  "Microtask batching, synchronous drain and after-render contracts."
  (:require [cljs.test :refer-macros [deftest is async]]
            [reagent2.ratom :as ratom]
            [reagent2.impl.batching :as batching]))

(defn- fake-component
  [counter-atom]
  (let [c #js {}]
    (set! (.-forceUpdate c)
          (fn [] (swap! counter-atom inc)))
    c))

;; Two hops also settle work enqueued during the drain.
(defn- next-microtask
  []

  (-> (js/Promise.resolve)
      (.then (fn [_] (js/Promise.resolve)))))

;; A final count alone cannot distinguish separate turns from a flattened drain.
(deftest enqueue-during-drain-schedules-fresh-turn
  (async done
    (let [calls (atom 0)
          c     #js {}]
      (set! (.-forceUpdate c)
            (fn []
              (swap! calls inc)

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
                   (done)))))))

(deftest dedup-same-component-once
  (async done
    (let [calls (atom 0)
          c     (fake-component calls)]
      (batching/queue-render! c)
      (batching/queue-render! c)
      (-> (next-microtask)
          (.then (fn [_]
                   (is (= 1 @calls))
                   (done)))))))

(deftest ordering-render-after
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
                   (done)))))))

(deftest flush-bang-suppresses-pending-microtask
  (async done
    (let [calls (atom 0)
          c     (fake-component calls)]
      (batching/queue-render! c)

      (batching/flush!)
      (is (= 1 @calls) "flush! drained synchronously")

      (-> (next-microtask)
          (.then (fn [_]
                   (is (= 1 @calls)
                       "microtask body found empty queue; no extra render")
                   (done)))))))

(deftest do-after-render-runs-once
  (async done
    (let [fired (atom 0)]

      (batching/do-after-render (fn [] (swap! fired inc)))
      (-> (next-microtask)
          (.then (fn [_]
                   (is (= 1 @fired) "after-render fired exactly once")

                   (batching/queue-render! (fake-component (atom 0)))
                   (next-microtask)))
          (.then (fn [_]
                   (is (= 1 @fired) "after-render did not re-fire on next drain")
                   (done)))))))

(defn- record-deferred-tasks!
  []
  (let [tasks (atom [])
        orig  (.-setTimeout js/globalThis)]
    (set! (.-setTimeout js/globalThis) (fn [task _ms] (swap! tasks conj task) 0))
    [tasks (fn [] (set! (.-setTimeout js/globalThis) orig))]))

(defn- thrown-by
  [task]
  (try (task) ::returned
       (catch :default e e)))

(deftest after-render-throw-does-not-strand-later-callbacks
  (async done
    (let [order            (atom [])
          boom             (js/Error. "boom")
          [tasks restore!] (record-deferred-tasks!)]
      (batching/do-after-render (fn [] (swap! order conj :a)))
      (batching/do-after-render (fn [] (throw boom)))
      (batching/do-after-render (fn [] (swap! order conj :c)))
      (-> (next-microtask)
          (.then (fn [_]
                   (restore!)
                   (is (= [[:a :c] [boom]] [@order (mapv thrown-by @tasks)]))
                   (done)))))))

(deftest rea-schedule-triggers-microtask-drain
  (async done

    (let [a       (ratom/atom 1)
          r       (ratom/make-reaction (fn [] (* @a 10)))
          outer   (ratom/make-reaction (fn [] @r) :auto-run true)]
      @outer
      (let [seen (atom nil)]
        (add-watch outer :w (fn [_ _ _ nu] (reset! seen nu)))
        (reset! a 2)
        (-> (next-microtask)
            (.then (fn [_]
                     (is (= 20 @seen))
                     (done))))))))
