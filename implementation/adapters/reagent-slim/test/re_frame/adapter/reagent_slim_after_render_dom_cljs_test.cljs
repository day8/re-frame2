(ns re-frame.adapter.reagent-slim-after-render-dom-cljs-test
  "rf2-cdoo — the reagent-slim ORDINARY-PATH proof that an `after-render`
  callback observes the COMMITTED DOM.

  WHAT IT PROVES. `reagent2.core/after-render` promises to run `f` \"after the
  next React commit\", and the slim adapter publishes it at
  `:adapter/after-render`, so `rf.interop/after-render` reaches this exact
  queue. This file pins that promise on the path production actually takes:
  the microtask the render scheduler queues by itself, with no test primitive
  driving it.

  WHY A DOM READ AND NOT A COUNTER. The pre-existing slim coverage
  (`reagent2.impl.batching`'s focused tests, and the shared React suite's
  `assert-after-render-runs-after-commit`) asserts that the callback FIRED —
  a counter, or a `[:render :after]` call-order vector over fake components
  whose `forceUpdate` body runs synchronously. A callback invoked while
  React has only SCHEDULED the class update passes every one of those and
  still reads stale DOM. So the witness here reads
  `(.-textContent mount-node)` INSIDE the callback and asserts the new value
  is already there.

  THE FORBIDDEN SHORTCUTS, and why each would make this vacuous:

    - `reagent2.dom.client/flush-views!` and the adapter's `flush-render!`
      both impose a commit boundary of their own (`react/act` /
      `react-dom/flushSync`), which is the very thing under test;
    - a manual `react-dom/flushSync` around the state change or the drain
      does the same by hand;
    - an extra `requestAnimationFrame` (or any await) BEFORE the DOM read
      lets React's scheduler commit first, so the callback would observe
      the new DOM no matter when it ran.

  None of those appears between the state change and the callback's read.
  The `flushSync` that DOES appear wraps only the INITIAL MOUNT — React 19's
  `root.render` first pass is otherwise asynchronous, and without it there
  is no committed baseline to have been stale about. The `js/setTimeout`
  that follows is likewise not a shortcut: the callback has ALREADY recorded
  what it saw by then, so deferring the ASSERTION cannot change the
  OBSERVATION. It only gives the test a point at which the scheduler turn is
  certainly over.

  THE PRE-CHANGE ASSERTION IS LOAD-BEARING. Between `dispatch-sync` and the
  callback the test asserts the DOM still reads the OLD value. Without it a
  substrate that committed synchronously on dispatch would satisfy the
  post-condition trivially, and the test would pin nothing.

  ns ends in `-dom-cljs-test` so shadow-cljs's `:browser-test` build
  discovers it for the real-DOM assertion; the `:node-test` runner also
  loads it, where the body gates on `(browser?)` and no-ops cleanly."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [reagent2.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views]))

;; Map-form (`:async? true`) fixture: a fn-form fixture tears down
;; synchronously and would restore the registrar while an `(async done)` body
;; is still in flight. `:ambient-frame nil` (EP-0002, rf2-9o48ih) opts out of
;; the default ambient `*current-frame*` :rf/default scope — the mount runs
;; inside the test body's dynamic extent, where an ambient :rf/default scope
;; would shadow the React-context tier and the probe's `subscribe` would read
;; :rf/default's empty app-db instead of the provider's seeded frame.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent-slim/adapter
     :async? true
     :ambient-frame nil}))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- make-mount-node! []
  (when (browser?)
    (.createElement js/document "div")))

(deftest after-render-callback-observes-the-committed-dom
  (testing "reagent-slim — an ordinary-path after-render callback sees the
  COMMITTED DOM, not the pre-commit DOM (rf2-cdoo)"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (async done
        (let [frame-kw :rf.reagent-slim-after-render/probe-frame]
          (rf/make-frame {:id frame-kw :doc "after-render post-commit probe frame"})
          (rf/reg-event ::seed (fn [_ _] {:db {:n 1}}))
          (rf/reg-event ::inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
          (rf/dispatch-sync [::seed] {:frame frame-kw})
          (rf/reg-sub ::n (fn [db _] (:n db)))
          (rf/reg-view* :rf.reagent-slim-after-render/probe
                        (fn probe []
                          [:div "n=" @(rf/subscribe [::n])]))
          (let [mount-node (make-mount-node!)
                root       (rdc/create-root mount-node)
                ;; What the callback saw, recorded from INSIDE it. An `is`
                ;; here would be swallowed by the queue's per-callback throw
                ;; isolation (rf2-p27yih), so the callback records and the
                ;; assertions run outside it.
                seen       (atom [])]
            ;; Initial mount inside flushSync so React 19's otherwise-async
            ;; first pass is committed before we go on — this is the baseline
            ;; the callback could be stale about, not a drain of the queue
            ;; under test.
            (react-dom/flushSync
              (fn []
                (rdc/render root [rf/frame-provider {:frame frame-kw}
                                  [(rf/view :rf.reagent-slim-after-render/probe)]])))
            (is (= "n=1" (.-textContent mount-node))
                "baseline: the committed DOM shows the seeded value n=1")

            ;; The state change. Under the rewrite this only ENQUEUES the
            ;; dependent component for the next microtask turn, so nothing has
            ;; committed yet...
            (rf/dispatch-sync [::inc] {:frame frame-kw})
            ;; ...which this asserts, so a substrate that committed
            ;; synchronously here could not satisfy the post-condition below
            ;; vacuously.
            (is (= "n=1" (.-textContent mount-node))
                "precondition: dispatch alone has NOT committed — the DOM still
                 reads n=1, so the callback below has something to be stale about")

            ;; THE PROOF. Queue an ordinary after-render callback into the same
            ;; scheduler turn and let the turn run on its own. No flush-views!,
            ;; no flush-render!, no act, no flushSync, no rAF between here and
            ;; the callback's read.
            (rf.interop/after-render
              (fn after-render-probe []
                (swap! seen conj (.-textContent mount-node))))

            (js/setTimeout
              (fn []
                (is (= 1 (count @seen))
                    (str "the after-render callback fired exactly once — saw "
                         (pr-str @seen)))
                (is (= "n=2" (first @seen))
                    (str "the after-render callback observed the COMMITTED new
                          DOM; it read " (pr-str (first @seen))
                         " — reading \"n=1\" means the callback ran while React
                          had only SCHEDULED the class update (rf2-cdoo)"))
                (is (= "n=2" (.-textContent mount-node))
                    "the update did commit — the callback's read is the only
                     thing in question, not whether the render happened")
                (try (.unmount root) (catch :default _ nil))
                (done))
              50)))))))

(deftest after-render-with-no-dirty-component-still-fires
  (testing "reagent-slim — a callback queued with no dirty component still
  fires asynchronously, and the commit-aware path does not strand it (rf2-cdoo)"
    (async done
      (let [fired (atom 0)]
        (rf.interop/after-render (fn [] (swap! fired inc)))
        (is (= 0 @fired) "not yet — after-render is never synchronous")
        (js/setTimeout
          (fn []
            (is (= 1 @fired)
                "the callback fired on its own scheduler turn with nothing dirty")
            (done))
          50)))))

(deftest after-render-preserves-fifo-and-throw-isolation
  (testing "reagent-slim — callbacks keep FIFO order and one throwing callback
  does not strand the rest (rf2-cdoo preserves rf2-p27yih)"
    (async done
      (let [order (atom [])]
        (rf.interop/after-render (fn [] (swap! order conj :first)))
        (rf.interop/after-render (fn [] (swap! order conj :boom)
                                        (throw (js/Error. "after-render probe"))))
        (rf.interop/after-render (fn [] (swap! order conj :third)))
        (js/setTimeout
          (fn []
            (is (= [:first :boom :third] @order)
                "FIFO order preserved and the throw did not abort the drain")
            (done))
          50)))))
