(ns reagent2.dom.client-cljs-test
  "Tests for reagent2.dom.client.

  Per IMPL-SPEC §4.6 + §12.1 + §12.5 R-005. Covers:

    - flush-views! determinism: dispatch-then-flush gives the
      caller the post-render state synchronously, including work no
      scheduler microtask will drain.
    - flush-views! React-act composition: pending React work is
      drained inside act.
    - Suspense composition, at the scheduler level: an after-render
      callback stands in for the tail a resolved Suspense boundary
      runs, and it has fired by the time flush-views! resolves. Nothing
      here throws a Promise.

  flush-views! drains in the order

      microtask -> act(flush!) -> microtask

  See dom/client.cljs ns docstring for the full rationale.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            ["react" :as react]
            [reagent2.ratom :as ratom]
            [reagent2.impl.batching :as batching]
            [reagent2.impl.component :as component]
            [reagent2.dom.client :as dom-client]))

;; ---------------------------------------------------------------------------
;; The React-19 act floor.
;;
;; `reagent2.dom.client/resolve-act` probes `(.-act react)` and NOTHING else —
;; the pre-18.3 `react-dom/test-utils` location is below the repository's React
;; floor (react / react-dom 19.3.0, pinned in implementation/package.json and
;; its lock, and for generated consumers in tools/template's hooks.clj).
;;
;; This is the non-vacuous proof that the ONE lookup is the live path: swap a
;; spy onto `react.act`, call `flush-views!`, and observe the callback pass
;; THROUGH the spy. Remove the `act` call from `flush-views!` (or resolve `act`
;; from anywhere but the `react` module) and the spy count stays 0 and this
;; goes red.
;; ---------------------------------------------------------------------------

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

;; Work queued with the render scheduler connected also schedules its
;; microtask, which is FIFO-ahead of the drain flush-views! runs inside act and
;; so drains it first. Here the scheduler hook is disconnected for the duration,
;; so the queued recompute has no microtask coming, and flush-views! is the only
;; thing that can drain it.

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

;; ---------------------------------------------------------------------------
;; Suspense ordering — microtask -> act -> microtask
;;
;; Scenario: a Reaction's recompute schedules a microtask drain. Inside
;; that drain, an after-render callback simulates a Suspense-resolved
;; tail-recompute (a downstream Reaction picks up the resolved value
;; and notifies its watchers). The chosen ordering guarantees the
;; tail-recompute is observed by the time flush-views! returns.
;;
;; This is the determinism contract for Suspense composition. The test
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

;; ---------------------------------------------------------------------------
;; Mount-entry scaffolds
;; ---------------------------------------------------------------------------

(deftest mount-entries-scaffolded
  (testing "create-root, render, unmount, hydrate-root are bound"
    (is (fn? dom-client/create-root))
    (is (fn? dom-client/render))
    (is (fn? dom-client/unmount))
    (is (fn? dom-client/hydrate-root))))

(deftest unmount-handles-nil-gracefully
  (testing "unmount on nil root is a no-op (defensive)"
    (is (nil? (dom-client/unmount nil)))))

;; ---------------------------------------------------------------------------
;; Render-path integration (fake-root)
;;
;; Real React DOM rendering requires jsdom; node-test runs without one.
;; We verify the call path uses a stub root (with .render captured) so
;; we can inspect what gets pushed in. The full real-React pass lives
;; in the browser-test target.
;; ---------------------------------------------------------------------------

(deftest render-pushes-react-element-into-root
  (testing "render walks hiccup via as-element and calls (.render root react-el)"
    (let [captured (atom nil)
          fake-root #js {:render (fn [el] (reset! captured el) nil)}]
      (dom-client/render fake-root [:div "hi"])
      (let [^js el @captured]
        (is (some? el) ".render received a React element")
        (is (= "div" (.-type el)) "the React element wraps the hiccup tag")
        (is (= "hi" (-> el .-props .-children))
            "child text travelled through")))))

;; ---------------------------------------------------------------------------
;; Deref-capture wiring
;;
;; Per IMPL-SPEC §4.4 path 1: a class component's render runs inside a
;; per-instance Reaction so deref'd RAtoms register as deps. On dep
;; change the Reaction's auto-run callback queues a forceUpdate via
;; batching/queue-render!.
;;
;; Without this wiring, views would render once and never update. We
;; exercise the wiring via a fake forceUpdate spy.
;; ---------------------------------------------------------------------------

(deftest render-second-render-recomputes-reaction-rf2-u5p5
  (testing "second render after dep change recomputes the Reaction, not returns cached state"
    ;; After a dep change, the Reaction's `_handle-change` calls the
    ;; auto-run callback (queue-render!) but does NOT mark `dirty?`
    ;; (matching stock Reagent's kernel — only the nil-auto-run path
    ;; enqueues). So a render path doing a plain `@cljsRenderRea` on
    ;; every render would get a follow-up `-deref` with `dirty? = false`,
    ;; return the cached prior state rather than recomputing, and never
    ;; update the user-visible count.
    ;;
    ;; So on subsequent render entries the render path calls
    ;; `._run rea false` directly, and deref-capture re-runs the user fn
    ;; with the latest subscribed state. The deref-capture test below
    ;; cannot catch a cached render: it verifies only that forceUpdate
    ;; fired, not that the subsequent render produced updated output.
    (let [a            (ratom/atom 0)
          ;; Capture the hiccup produced on each render via a side-
          ;; channel; the render method returns a React element after
          ;; `->react-element`, so we read .-cljsArgv-equivalent off the
          ;; element to verify it carries the current `@a`.
          last-seen    (atom nil)
          render-fn    (fn []
                         (let [v @a]
                           (reset! last-seen v)
                           [:div v]))
          ^js klass    (component/create-class*
                         {:reagent-render render-fn})
          inst         (new klass #js {:__rfArgv [render-fn]})]
      (set! (.-forceUpdate inst) (fn [] nil))
      ;; First render: reads `a = 0`.
      (.call (.. klass -prototype -render) inst)
      (is (= 0 @last-seen) "first render saw a=0")
      ;; Mutate the dep.
      (swap! a inc)
      ;; Simulate React's re-entry: call render() again. It must
      ;; recompute (a=1), not return the cached hiccup (a=0).
      (.call (.. klass -prototype -render) inst)
      (is (= 1 @last-seen)
          "second render after dep change saw a=1 (recompute, not cache)")
      (.call (.. klass -prototype -componentWillUnmount) inst))))

(deftest render-componentwillunmount-disposes-render-reaction
  (testing "componentWillUnmount disposes the per-instance render Reaction"
    (let [a            (ratom/atom 0)
          render-fn    (fn [] [:div @a])
          ^js klass    (component/create-class*
                         {:reagent-render render-fn})
          inst         (new klass #js {:__rfArgv [render-fn]})]
      (set! (.-forceUpdate inst) (fn [] nil))
      (.call (.. klass -prototype -render) inst)
      (is (some? (.-cljsRenderRea inst))
          "after first render, the render Reaction is cached on the instance")
      (.call (.. klass -prototype -componentWillUnmount) inst)
      (is (nil? (.-cljsRenderRea inst))
          "after componentWillUnmount, the cached Reaction reference is cleared"))))

(deftest render-componentwillunmount-runs-user-callback
  (testing "componentWillUnmount runs the user :component-will-unmount fn"
    (let [unmounted     (atom 0)
          render-fn     (fn [] [:div])
          will-unmount  (fn [_this] (swap! unmounted inc))
          ^js klass     (component/create-class*
                          {:reagent-render render-fn
                           :component-will-unmount will-unmount})
          inst          (new klass #js {:__rfArgv [render-fn]})]
      (set! (.-forceUpdate inst) (fn [] nil))
      (.call (.. klass -prototype -render) inst)
      (.call (.. klass -prototype -componentWillUnmount) inst)
      (is (= 1 @unmounted)
          "user :component-will-unmount fired in addition to the synthetic disposal"))))

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
        ;; First render: the per-component render Reaction subscribes
        ;; to `derived`.
        (.call (.. klass -prototype -render) inst)
        (is (= 1 @renders) "first render ran")
        (is (= 0 @forced) "no forceUpdate yet — no dep change")
        ;; Mutate src; derived recomputes (auto-run); the per-component
        ;; render Reaction sees the change and queues forceUpdate.
        (reset! src 1)
        (-> (js/Promise.resolve (dom-client/flush-views!))
            (.then (fn [_]
                     (is (= 100 @derived) "derived has the post-mutation value")
                     (is (>= @forced 1)
                         "forceUpdate fired after Reaction-mediated dep change")
                     (.call (.. klass -prototype -componentWillUnmount) inst)
                     (done))))))))
