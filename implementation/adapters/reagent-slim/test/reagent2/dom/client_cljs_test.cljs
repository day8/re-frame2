(ns reagent2.dom.client-cljs-test
  "Tests for reagent2.dom.client: flush-views! (microtask -> act(flush!) ->
  microtask) leaves the caller with the post-render state, and the class
  render path's per-instance Reaction re-renders on dependency changes."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            ["react" :as react]
            [reagent2.ratom :as ratom]
            [reagent2.impl.batching :as batching]
            [reagent2.impl.component :as component]
            [reagent2.dom.client :as dom-client]))

;; `resolve-act` reads `act` off the `react` module only (React 19 is the
;; floor); a spy there proves flush-views! runs its drain inside it.

(deftest flush-views-routes-the-drain-through-react-act
  (testing "flush-views! obtains act from the `react` module and runs its drain
            inside it"
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
                      (done))))))))

;; After flush-views! resolves, dirty components have re-rendered, changed
;; Reactions have recomputed and queued after-render callbacks have fired.
;; With the scheduler hook disconnected no microtask drains a queued
;; recompute, so only flush-views! itself can.

(deftest flush-views-drains-a-recompute-no-microtask-will-drain
  (testing "flush-views! itself drains a queued Reaction recompute when the
            render scheduler requests no microtask for it"
    (async done
      (let [scheduler (deref ratom/rea-schedule)
            a         (ratom/atom 1)
            r         (ratom/make-reaction (fn [] (* @a 100)))
            outer     (ratom/make-reaction (fn [] @r) :auto-run true)
            seen      (atom nil)
            finish!   (fn []
                        ;; Drain anything a broken flush-views! left queued, so
                        ;; the red stays in this test.
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
                      (finish!))))))))

;; An after-render callback stands in for the tail a resolved Suspense
;; boundary runs; it has fired by the time flush-views! resolves.

(deftest flush-views-suspense-composition-ordering
  (testing "microtask -> act -> microtask order: tail-cascade settles before return"
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
                     (done))))))))

(deftest unmount-handles-nil-gracefully
  (testing "unmount on nil root is a no-op (defensive)"
    (is (nil? (dom-client/unmount nil)))))

;; node-test has no DOM, so render is checked against a stub root.

(deftest render-pushes-react-element-into-root
  (testing "render walks hiccup via as-element and calls (.render root react-el)"
    (let [captured (atom nil)
          fake-root #js {:render (fn [el] (reset! captured el) nil)}]
      (dom-client/render fake-root [:div "hi"])
      (let [^js el @captured]
        (is (= ["div" "hi"] [(.-type el) (-> el .-props .-children)]))))))

;; ---------------------------------------------------------------------------
;; A class render runs inside a per-instance Reaction, so its derefs become
;; deps and a dep change queues a forceUpdate.
;; ---------------------------------------------------------------------------

(deftest render-second-render-recomputes-reaction-rf2-u5p5
  (testing "second render after dep change recomputes the Reaction, not returns cached state"
    ;; A dep change runs the auto-run callback without marking the Reaction
    ;; dirty, so a plain deref on re-render would return the cached output.
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
      (.call (.. klass -prototype -componentWillUnmount) inst))))

(deftest render-componentwillunmount-disposes-render-reaction
  (testing "componentWillUnmount disposes the per-instance render Reaction and
            still runs the user :component-will-unmount"
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
      (is (= [nil 1] [(.-cljsRenderRea inst) @unmounted])))))

(deftest render-deref-capture-tracks-reaction-changes
  (testing "deref-capture tracks Reaction (not just RAtom) deps"
    (async done
      (let [src          (ratom/atom 0)
            ;; Build a Reaction that's auto-run so it re-runs on src change.
            derived      (ratom/make-reaction (fn [] (* @src 100))
                                              :auto-run true)
            renders      (atom 0)
            render-fn    (fn []
                           (swap! renders inc)
                           [:div @derived])
            forced       (atom 0)
            ^js klass    (component/create-class*
                           {:reagent-render render-fn})
            inst         (new klass #js {:__rfArgv [render-fn]})]
        (set! (.-forceUpdate inst) (fn [] (swap! forced inc)))
        (.call (.. klass -prototype -render) inst)
        (is (= 0 @forced) "control: no forceUpdate before a dep change")
        (reset! src 1)
        (-> (js/Promise.resolve (dom-client/flush-views!))
            (.then (fn [_]
                     (is (= 100 @derived) "derived has the post-mutation value")
                     (is (>= @forced 1)
                         "forceUpdate fired after Reaction-mediated dep change")
                     (.call (.. klass -prototype -componentWillUnmount) inst)
                     (done))))))))
