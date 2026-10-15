(ns reagent2.dom.client-cljs-test
  "Test flush ordering and render-reaction lifecycle contracts."
  (:require [cljs.test :refer-macros [deftest is async]]
            ["react" :as react]
            [reagent2.ratom :as ratom]
            [reagent2.impl.batching :as batching]
            [reagent2.impl.component :as component]
            [reagent2.dom.client :as dom-client]))

(deftest flush-views-routes-the-drain-through-react-act
  (async done
    (let [original (.-act react)
          seen     (atom 0)
          restore! (fn [] (set! (.-act react) original))]
      (is (fn? original)
          "PRECONDITION: React 19 exports `act` on the `react` module — if this
           fails the floor moved and the spy below would pass vacuously")
      (set! (.-act react)
            (fn [thunk]
              (swap! seen inc)
              (original thunk)))
      (-> (js/Promise.resolve (dom-client/flush-views!))
          (.then (fn [_]
                   (restore!)
                   (is (= 1 @seen)
                       "flush-views! must route its drain through react/act")
                   (done)))
          (.catch (fn [e]
                    (restore!)
                    (is false (str "flush-views! threw: " e))
                    (done)))))))

;; Disconnecting the scheduler makes flush-views! own the queued recompute.
(deftest flush-views-drains-a-recompute-no-microtask-will-drain
  (async done
    (let [scheduler (deref ratom/rea-schedule)
          a         (ratom/atom 1)
          r         (ratom/make-reaction (fn [] (* @a 100)))
          outer     (ratom/make-reaction (fn [] @r) :auto-run true)
          seen      (atom nil)
          finish!   (fn []

                      (ratom/flush!)
                      (reset! ratom/rea-schedule scheduler)
                      (done))]
      @outer
      (add-watch r :w (fn [_ _ _ nu] (reset! seen nu)))
      (reset! ratom/rea-schedule nil)
      (reset! a 5)
      (-> (js/Promise.resolve)
          (.then (fn [_]
                   (is (nil? @seen)
                       "PRECONDITION: with the scheduler disconnected, no
                        microtask drained the queued recompute")
                   (dom-client/flush-views!)))
          (.then (fn [_]
                   (is (= 500 @seen)
                       "flush-views! drained the queued recompute itself")
                   (finish!)))
          (.catch (fn [e]
                    (is false (str "flush-views! threw: " e))
                    (finish!)))))))

(deftest flush-views-suspense-composition-ordering
  (async done
    (let [input        (ratom/atom 0)
          derived      (ratom/make-reaction (fn [] (inc @input)) :auto-run true)
          tail-effects (atom 0)
          renders      (atom 0)
          c            #js {}]
      @derived
      (set! (.-forceUpdate c) (fn [] (swap! renders inc)))
      (add-watch derived :wire
        (fn [_ _ _ _]
          (batching/queue-render! c)
          (batching/do-after-render
            (fn [] (swap! tail-effects inc)))))
      (reset! input 1)
      (-> (js/Promise.resolve (dom-client/flush-views!))
          (.then (fn [_]
                   (is (= [2 1 1] [@derived @renders @tail-effects])
                       "recomputed, re-rendered once, and the tail fired before return")
                   (done)))))))

(deftest unmount-handles-nil-gracefully
  (is (nil? (dom-client/unmount nil))))

(deftest render-second-render-recomputes-reaction-rf2-u5p5
  (let [a            (ratom/atom 0)
        last-seen    (atom nil)
        render-fn    (fn []
                       (let [v @a]
                         (reset! last-seen v)
                         [:div v]))
        ^js klass    (component/create-class*
                       {:reagent-render render-fn})
        inst         (new klass #js {:__rfArgv [render-fn]})]
    (set! (.-forceUpdate inst) (fn [] nil))
    (.call (.. klass -prototype -render) inst)
    (is (= 0 @last-seen) "first render saw a=0")
    (swap! a inc)
    (.call (.. klass -prototype -render) inst)
    (is (= 1 @last-seen)
        "second render after dep change saw a=1 (recompute, not cache)")
    (.call (.. klass -prototype -componentWillUnmount) inst)))

(deftest render-componentwillunmount-disposes-render-reaction
  (let [a            (ratom/atom 0)
        unmounted    (atom 0)
        render-fn    (fn [] [:div @a])
        ^js klass    (component/create-class*
                       {:reagent-render         render-fn
                        :component-will-unmount (fn [_this] (swap! unmounted inc))})
        inst         (new klass #js {:__rfArgv [render-fn]})]
    (set! (.-forceUpdate inst) (fn [] nil))
    (.call (.. klass -prototype -render) inst)
    (is (some? (.-cljsRenderRea inst)) "control: the render cached its Reaction")
    (.call (.. klass -prototype -componentWillUnmount) inst)
    (is (= [nil 1] [(.-cljsRenderRea inst) @unmounted]))))

(deftest render-deref-capture-tracks-reaction-changes
  (async done
    (let [src       (ratom/atom 0)
          derived   (ratom/make-reaction (fn [] (* @src 100)) :auto-run true)
          render-fn (fn [] [:div @derived])
          forced    (atom 0)
          ^js klass (component/create-class* {:reagent-render render-fn})
          inst      (new klass #js {:__rfArgv [render-fn]})]
      (set! (.-forceUpdate inst) (fn [] (swap! forced inc)))
      (.call (.. klass -prototype -render) inst)
      (is (= 0 @forced))
      (reset! src 1)
      (-> (js/Promise.resolve (dom-client/flush-views!))
          (.then (fn [_]
                   (is (>= @forced 1))
                   (.call (.. klass -prototype -componentWillUnmount) inst)
                   (done)))))))
