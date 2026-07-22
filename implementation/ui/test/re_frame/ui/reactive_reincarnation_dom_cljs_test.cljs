(ns re-frame.ui.reactive-reincarnation-dom-cljs-test
  "rf2-vxgfnd.93 — the same-id reincarnation correction fires in the layout
  commit BEFORE paint, under REAL React 19 + real DOM (bead rf2-vxgfnd.14 AC #2
  on the React spine).

  `reactive-reconcile-cljs-test` graft-checks the `:node-key` reincarnation
  detection headlessly (and fails-pre-fix there); this file proves the SAME
  correction rides the `useLayoutEffect` commit on the real spine. The staging is
  deterministic: the `reactive/*commit-barrier* :pre-acquire` seam fires inside
  the leaf's layout commit — after the render probed incarnation A but BEFORE the
  commit's stage-acquire — where the fixture DESTROYS + RECREATES the frame under
  the same id. The commit then ACQUIRES the fresh incarnation (a strictly-greater
  node-key) while holding the render's probe of the destroyed one, so
  `evidence-moved?` classifies it MOVED via the node-key axis and advances the
  revision, which re-renders SYNCHRONOUSLY inside the same `flushSync` — before
  any paint. The DOM reads the reincarnated value with no macrotask/paint between.

  ## Act discipline (rf2-vxgfnd.163)

  The timing-sensitive `flushSync` mount/rerender/unmount run inside React's OWN
  `act(...)` — the repository's native `get-act`/`act!` pattern (the same shape as
  `root-mount-dom-cljs-test` and core's shared React suite), NOT UIx/Reagent
  machinery. `IS_REACT_ACT_ENVIRONMENT` is enabled per test and its prior value
  restored on every exit, so the fixture neither depends on nor leaks the
  shared-page flag. The corrective `flushSync` stays nested inside `act`, and the
  synchronous DOM result is recorded INSIDE the callback — before `act` can drain
  any later microtask/macrotask work — so the before-paint reincarnation
  discriminator is preserved. A bounded, namespace-local console-error capture
  counts React's \"not wrapped in act(...)\" diagnostic while FORWARDING every line
  to the original console: removing the act boundary makes the mount emit that
  warning, the capture counts it, and the zero-warning assertion goes RED.

  Browser-only bodies — `-dom-cljs-test$` opts into `:browser-test`; `:node-test`
  loads it too, where the DOM body gates on `(browser?)` and no-ops."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            ["react" :as react]
            ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview frame-provider sub]]
            [re-frame.ui.client :as client]
            [re-frame.ui.reactive :as reactive]
            ;; the CLJS emitter wraps a sub-bearing view in viewcell/render.
            [re-frame.ui.viewcell]))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

;; --- native React act pattern (rf2-vxgfnd.163; rf2-powx4d sweep template) ----
;; React's OWN act(), enabled per-test in the fixture. NOT UIx/Reagent.

(defn- get-act
  "React's act() — React 19 promotes it to the React namespace proper
  (react-dom/test-utils on 18)."
  []
  (or (when (exists? (.-act react)) (.-act react))
      (try (.-act (js/require "react-dom/test-utils")) (catch :default _ nil))))

(defn- enable-react-act-env! []
  (when (browser?)
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)))

(defn- act!
  "Run `thunk` inside React `act()`, returning the thunk's value. The
  mount/rerender/unmount work commits synchronously under
  IS_REACT_ACT_ENVIRONMENT (enabled per-test in the fixture), so the callback
  runs eagerly and we capture its result."
  [thunk]
  (let [ret    (volatile! nil)
        act-fn (get-act)]
    (act-fn (fn [] (vreset! ret (thunk))))
    @ret))

;; --- bounded namespace-local act-warning capture (the guard's teeth) ---------

(defn- act-warning?
  "True iff `text` is React's 'not wrapped in act(...)' console diagnostic — the
  exact warning the act boundary suppresses. Bounded and namespace-local; used
  BOTH by the live capture and by the detector unit test, so the same predicate
  the zero-warning assertion depends on is proven to match React's wording."
  [text]
  (and (string? text)
       (boolean (re-find #"not wrapped in act" text))))

(defn- with-console-error-capture
  "Run `thunk` with `console.error` intercepted: every call is FORWARDED to the
  original console (diagnostics preserved, never suppressed), and any
  act-discipline warning increments `counter`. The original `console.error` is
  restored on every exit."
  [counter thunk]
  (let [orig (.-error js/console)]
    (set! (.-error js/console)
          (fn [& args]
            (when (some act-warning? args)
              (swap! counter inc))
            (.apply orig js/console (into-array args))))
    (try (thunk) (finally (set! (.-error js/console) orig)))))

(def ^:private prior-act-env (atom nil))

;; Both :each fixtures are FN-form (the reincarnation correction is synchronous —
;; no async settlement — so the default FN-form reset fixture is right). A map
;; fixture here would mix types with the FN-form reset fixture and abort the ns.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter :ambient-frame nil})
  (fn [f]
    (reset! prior-act-env
            (when (browser?) (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)))
    (enable-react-act-env!)
    (reactive/reset-scheduler!)
    (client/reset-live-roots!)
    (try (f)
         (finally
           (reactive/reset-scheduler!)
           (client/reset-live-roots!)
           (when (browser?)
             (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) @prior-act-env))))))

(def ^:private frame-kw :rre/frame)

(defn- container [] (js/document.createElement "div"))

(defview leaf [_] [:span.leaf (str (sub [:rre/n]))])

(defn- register-app! []
  (rf/make-frame {:id frame-kw :doc "reincarnation before-paint probe frame"})
  (rf/reg-sub :rre/n (fn [db _] (:n db)))
  (rf/reg-event :rre/seed (fn [_ _] {:db {:n 1}})))

(deftest reincarnation-in-the-gap-corrects-before-paint
  (if-not (browser?)
    (is true ":node — no DOM; the :browser-test runner exercises the DOM body")
    (do
      (register-app!)
      (rf/dispatch-sync [:rre/seed] {:frame frame-kw})
      (let [c        (container)
            armed    (atom true)
            warnings (atom 0)
            root     (volatile! nil)
            dom-val  (volatile! nil)]
        (try
          ;; MOUNT + corrective RERENDER inside React act. The :pre-acquire seam
          ;; reincarnates the frame ONCE, inside the mount's layout commit:
          ;; destroy A (which the not-yet-connected leaf cell escapes — it enrols
          ;; only at the post-publish connect!) and recreate B under the same id
          ;; with a DIFFERENT value. The commit then acquires B's fresh node (a
          ;; distinct node-key) while holding the render's probe of A.
          (with-console-error-capture warnings
            (fn []
              (act!
               (fn []
                 (binding [reactive/*commit-barrier*
                           (fn [phase _cell]
                             (when (and @armed (= :pre-acquire phase))
                               (reset! armed false)   ;; once — disarm the re-commit
                               (frame/destroy-frame! frame-kw)
                               (rf/make-frame {:id frame-kw})
                               (frame/replace-app-db! frame-kw {:n 2})))]
                   (vreset! root
                            (react-dom/flushSync
                             #(ui/mount [frame-provider {:frame frame-kw} [leaf {}]]
                                        c {:root-id :rre/root})))
                   ;; Record the DOM SYNCHRONOUSLY, inside the flushSync
                   ;; checkpoint, before act can drain any microtask/macrotask —
                   ;; preserving the before-paint reincarnation discriminator.
                   (vreset! dom-val (.-textContent (.querySelector c ".leaf"))))))))
          (testing "the node-key reincarnation was corrected in the layout commit, before paint"
            (is (= "2" @dom-val)
                "the corrective render fired synchronously inside flushSync (before
                 any paint/macrotask) — the reincarnated value is on the DOM"))
          (finally
            ;; Guaranteed act-disciplined unmount — no live Root/ViewCell survives.
            (with-console-error-capture warnings
              (fn [] (when @root (act! #(ui/unmount! @root)))))))
        (testing "the mount, corrective rerender, and unmount stayed inside act()"
          (is (zero? @warnings)
              "zero 'not wrapped in act(...)' warnings escaped the fixture"))
        (is (empty? (client/live-root-ids))
            "every Root was torn down in guaranteed cleanup — none survives")))))

(deftest act-warning-detector-has-teeth
  ;; rf2-vxgfnd.163 AC — a mutation removing the act boundary must make the
  ;; namespace-local warning assertion go RED. Prove the exact predicate the
  ;; zero-warning assertion depends on matches React's real act diagnostic —
  ;; WITHOUT emitting a real warning (which would itself violate zero-warning).
  (is (act-warning?
       "Warning: An update to Leaf inside a test was not wrapped in act(...).")
      "recognises React's act-discipline warning (React 18 phrasing)")
  (is (act-warning?
       "An update to ViewCell inside a test was not wrapped in act(...). When testing…")
      "recognises the React 19 phrasing too")
  (is (not (act-warning? "TypeError: querySelector is not a function"))
      "an unrelated console.error is NOT mistaken for an act warning")
  (is (not (act-warning? nil))
      "nil is not an act warning — the classifier is string-guarded"))
