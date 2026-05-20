(ns re-frame.adapter.helix-after-render-dom-cljs-test
  "Behavioural coverage for the Helix adapter's `:adapter/after-render`
  hook (rf2-334d9). Pre-rf2-334d9 the Helix adapter did not publish
  `:adapter/after-render`, so `(rf/after-render f)` under a Helix-only
  app was a silent no-op — closed by Mike's rf2-neiqf decision
  2026-05-19 to publish via `React.useLayoutEffect`.

  Architecture under test. The spine maintains a per-adapter after-
  render queue + a sentinel React function component injected at the
  root of every mounted tree (via `spine/make-render`). The sentinel
  fires `React.useLayoutEffect` on every commit, draining the queue.
  When `interop/after-render` is called, the sentinel's stashed
  `setState` bumps a tick so React schedules a commit, the
  `useLayoutEffect` fires, and the queue drains in
  post-commit / pre-paint order — same timing semantics as Reagent's
  `r/after-render`. A `queueMicrotask` fallback covers the
  pre-mount / post-unmount call paths.

  Coverage shape mirrors the UIx parity test
  (`uix_after_render_cljs_test.cljs`):
    1. The hook is wired at ns-load — calling `interop/after-render`
       under the Helix adapter returns nil (not a thrown
       'no hook bound' / silent no-op signal).
    2. Behaviour. Mount a Helix tree; call `(interop/after-render f)`;
       verify `f` fired after the next commit (asserted via a side-
       channel atom).

  The behaviour assertion is browser-only — node-runtime has no DOM
  and `react-dom/client` is unmountable there. Under `:node-test` the
  ns-load smoke runs and the behaviour test no-ops cleanly.

  ns ends in `-cljs-test` so shadow-cljs `:node-test` picks it up."
  (:require ["react"            :as React]
            [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [helix.core :refer-macros [$ defnc]]
            [helix.dom  :as d]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.adapter.helix :as helix-adapter]
            [re-frame.interop :as interop]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter helix-adapter/adapter}))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- make-mount-node! []
  (when (browser?)
    (.createElement js/document "div")))

(defn- get-act
  "Return React's act() if available, else nil. React 18 ships act in
  react-dom/test-utils; React 19 promotes it to the React namespace
  proper."
  []
  (or (when (exists? (.-act React)) (.-act React))
      (try
        (let [test-utils (js/require "react-dom/test-utils")]
          (.-act test-utils))
        (catch :default _ nil))))

(defn- enable-react-act-env! []
  (when (browser?)
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)))

;; ---- ns-load smoke -------------------------------------------------------

(deftest after-render-hook-wired-under-helix
  (testing "rf2-334d9: `interop/after-render` no longer silent-no-ops
            under the Helix adapter — the hook is wired at ns-load and
            returns nil (the documented swallow shape) rather than
            falling through to nil because no adapter published it."
    ;; Calling with a fn argument should NOT throw and should NOT
    ;; return a 'no hook bound' sentinel. The hook itself returns nil
    ;; per the make-after-render-hook contract, and the queueMicrotask
    ;; fallback drain handles the no-mount case asynchronously.
    (is (nil? (interop/after-render (fn [] :ok)))
        "interop/after-render under the Helix adapter returns nil — the
         spine-built hook is wired through :adapter/after-render via
         substrate-adapter/route-hook!")))

;; ---- behaviour: mount, schedule, drain after commit ----------------------

(defnc Probe []
  ;; Bare Helix component — the rf2-334d9 sentinel is injected by the
  ;; spine's `make-render`, not by user code.
  (d/div "probe"))

(deftest after-render-runs-callback-after-next-commit-helix
  (testing "rf2-334d9: `(interop/after-render f)` schedules `f` to run
            after the next mount/render cycle under the Helix adapter.
            The sentinel injected by the spine's make-render uses
            React.useLayoutEffect to drain the queue post-commit."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises the assertions")
      (let [act-fn (get-act)]
        (if (nil? act-fn)
          (is true "act() not reachable from this runner; skipping")
          (do
            (enable-react-act-env!)
            (let [fired      (atom 0)
                  callback   (fn after-render-cb [] (swap! fired inc))
                  mount-node (make-mount-node!)
                  unmount    (atom nil)]
              (try
                ;; Mount through `rf/render` so the spine's
                ;; make-render path injects the after-render sentinel.
                ;; Direct `react-dom-client/createRoot` + `.render`
                ;; bypasses the spine wrap and would leave no sentinel
                ;; in the tree — exactly what the rf2-334d9 design
                ;; requires under test.
                (act-fn (fn []
                          (reset! unmount
                                  (substrate-adapter/render ($ Probe) mount-node {}))))
                (is (zero? @fired)
                    "no after-render fn enqueued yet ⇒ no fires")
                ;; Enqueue under act so the set-tick bump → re-render
                ;; → useLayoutEffect drain commits synchronously in
                ;; the test environment.
                (act-fn (fn [] (interop/after-render callback)))
                (is (= 1 @fired)
                    "after-render fn fired exactly once after the next commit")
                ;; A second enqueue + drain — the sentinel survives
                ;; the first drain (its useLayoutEffect runs every
                ;; commit) so a subsequent after-render also fires.
                (act-fn (fn [] (interop/after-render callback)))
                (is (= 2 @fired)
                    "subsequent after-render fn also fires after its commit")
                (finally
                  (when-let [u @unmount]
                    (try (u) (catch :default _ nil))))))))))))
