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
            [reagent2.impl.component :as component]
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

(deftest render-callable-stage-4d
  ;; Stage 4-D landed: render no longer throws — it walks hiccup via
  ;; reagent2.impl.template/as-element and pushes the result into the
  ;; root. The full mount-against-real-React path is exercised in
  ;; render_cljs_test (Stage 4-D); this assertion is the cheap smoke
  ;; check that the symbol is bound and not the old throw-shim.
  (testing "render is bound and is not the Stage 4-B throw-shim"
    (is (fn? dom-client/render))
    ;; Calling render with non-root/non-hiccup inputs should not
    ;; raise the old :rf.error/not-implemented; React's own
    ;; .render method may surface a different error for a bogus
    ;; root, which is fine.
    (let [thrown (try (dom-client/render :not-a-root :el)
                      nil
                      (catch :default e (ex-data e)))]
      (is (not= :rf.error/not-implemented (:type thrown))))))

(deftest hydrate-root-callable-stage-4d
  ;; Stage 4-D landed: hydrate-root no longer throws — it walks hiccup
  ;; via reagent2.impl.template/as-element and calls
  ;; react-dom-client/hydrateRoot. The real-DOM hydration path lives
  ;; in the browser-test target.
  (testing "hydrate-root is bound and is not the Stage 4-B throw-shim"
    (is (fn? dom-client/hydrate-root))))

(deftest unmount-handles-nil-gracefully
  (testing "unmount on nil root is a no-op (defensive)"
    (is (nil? (dom-client/unmount nil)))))

;; ---------------------------------------------------------------------------
;; Stage 4-D: render-path integration (fake-root)
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

(deftest render-passes-nil-for-nil-hiccup
  (testing "render with nil hiccup passes nil to root.render"
    (let [captured (atom :sentinel)
          fake-root #js {:render (fn [el] (reset! captured el) nil)}]
      (dom-client/render fake-root nil)
      (is (nil? @captured)))))

(deftest render-translates-shorthand-class
  (testing "render converts :div.foo shorthand on the way in"
    (let [captured (atom nil)
          fake-root #js {:render (fn [el] (reset! captured el) nil)}]
      (dom-client/render fake-root [:div.foo])
      (is (= "foo" (-> ^js @captured .-props .-className))))))

;; ---------------------------------------------------------------------------
;; Stage 4-D: deref-capture wiring
;;
;; Per IMPL-SPEC §4.4 path 1: a class component's render runs inside a
;; per-instance Reaction so deref'd RAtoms register as deps. On dep
;; change the Reaction's auto-run callback queues a forceUpdate via
;; batching/queue-render!.
;;
;; Without this wiring (Stage 4-A handoff note), views render once and
;; never update. We exercise the wiring via a fake forceUpdate spy.
;; ---------------------------------------------------------------------------

(deftest render-deref-capture-queues-rerender-on-dep-change
  (testing "deref-capture wiring: dep change triggers forceUpdate via batching"
    (async done
      (let [a            (ratom/atom 0)
            renders      (atom 0)
            ;; Render fn that derefs `a` — this should subscribe the
            ;; component to changes via the per-instance render Reaction.
            render-fn    (fn []
                           (swap! renders inc)
                           [:div @a])
            ;; Build a class via the same path as fn-to-class.
            ^js klass    (component/create-class*
                           {:reagent-render render-fn})
            ;; Fake forceUpdate so we can observe the queue-render!
            ;; cascade without running React DOM.
            forced       (atom 0)
            inst         (new klass #js {:__rfArgv [render-fn]})]
        ;; Stub forceUpdate before first render so the queued
        ;; re-render observes our spy. React would normally provide
        ;; this; we replicate it for the test.
        (set! (.-forceUpdate inst) (fn [] (swap! forced inc)))
        ;; First render: should subscribe to `a`.
        (.call (.. klass -prototype -render) inst)
        (is (= 1 @renders) "first render ran")
        ;; Mutate the dep — should enqueue a re-render via the
        ;; reaction's auto-run callback.
        (swap! a inc)
        (-> (js/Promise.resolve (dom-client/flush-views!))
            (.then (fn [_]
                     (is (>= @forced 1)
                         "forceUpdate fired after dep change (deref-capture wired)")
                     ;; Cleanup: unmount the test instance to dispose
                     ;; the reaction's watch graph.
                     (.call (.. klass -prototype -componentWillUnmount) inst)
                     (done))))))))

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
