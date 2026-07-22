(ns re-frame.ui.root-teardown-dom-cljs-test
  "rf2-vxgfnd.62 — the ROOT teardown proof under REAL React + real DOM.

  `reactive-root-teardown-cljs-test` graft-checks the window logic on both
  hosts and `root-teardown-wiring-cljs-test` pins the client kernel with a fake
  root; this file closes the loop end-to-end: a real `ui/mount` then
  `ui/unmount!`, asserting the mounted ViewCell followed the 03 §4 dead-cell
  lifecycle. It proves the load-bearing runtime assumption of the whole
  approach — that React fires each cell's layout-effect cleanup SYNCHRONOUSLY
  inside `root.unmount()`, so the teardown window catches the real cells and a
  real root unmount ends `:unmounted {:proof :host-teardown}` → `:dead` rather
  than stuck at the transient `:disconnected {:reason :unknown}`.

  Browser-only bodies — the `-dom-cljs-test$` suffix opts this file into the
  `:browser-test` build; `:node-test` loads it too, where the DOM body gates on
  `(browser?)` and no-ops. The first-party `re-frame.ui` adapter is the
  watchable substrate the other browser fixtures use; here it provides the
  production observation path beneath a real React mount."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            ["react" :as React]
            ["react-dom" :as react-dom]
            ["react-dom/client" :as rdc]
            [re-frame.core :as rf]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview frame-provider sub]]
            [re-frame.ui.client :as client]
            [re-frame.ui.reactive :as reactive]
            ;; Load-bearing: the CLJS emitter wraps a sub-bearing view in
            ;; `viewcell/render`, so a consumer must load the runtime.
            [re-frame.ui.viewcell]))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

;; ---------------------------------------------------------------------------
;; The canonical React act() helper (rf2-vxgfnd.89 template; rf2-powx4d sweep)
;;
;; The repository's established native-React act pattern — the same
;; get-act / enable-react-act-env! shape as core's shared React suite
;; (react_shared_suite.cljs `with-browser-act`) and the frame-scope keystone
;; fixture (frame_scope_resolve_dom_cljs_test.cljs). It uses React's OWN
;; `act()`, NOT UIx/Reagent machinery, so this fixture stays native
;; re-frame.ui + the substrate adapter under test.
;;
;; `flushSync` is NOT an act substitute: React's test contract treats any
;; update committed outside an act scope as "not wrapped in act(...)". The
;; `:browser-test` build runs EVERY `-dom-cljs-test` namespace on one shared
;; page, and sibling suites set IS_REACT_ACT_ENVIRONMENT=true and never reset
;; it — so a flushSync-only mount/unmount here emits that warning once the flag
;; has leaked true. Wrapping every commit in `act` — with the act env enabled
;; per-test in the fixture — makes the fixture clean under act rather than
;; silencing anything.
(defn- get-act
  "React's act() — React 19 promotes it to the React namespace proper
  (react-dom/test-utils on 18)."
  []
  (or (when (exists? (.-act React)) (.-act React))
      (try (.-act (js/require "react-dom/test-utils")) (catch :default _ nil))))

(defn- enable-react-act-env! []
  (when (browser?)
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)))

(defn- act!
  "Run `thunk` inside React `act()`, returning the thunk's value. `act()`
  itself returns a thenable (not the callback's value); the mount/unmount work
  here commits synchronously under IS_REACT_ACT_ENVIRONMENT (enabled per-test
  in the fixture), so the callback runs eagerly and we capture its result."
  [thunk]
  (let [ret    (volatile! nil)
        act-fn (get-act)]
    (act-fn (fn [] (vreset! ret (thunk))))
    @ret))

;; Map-form fixtures (`:before`/`:after`) so this ns can host ASYNC tests
;; (rf2-vxgfnd.182 — the deferred-teardown settlement is a real microtask the
;; test must await); the prior act-env flag is stashed across the pair.
(def ^:private prior-act-env (atom nil))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter :ambient-frame nil :async? true})
  {:before (fn []
             ;; Enable React's act environment for this ns's DOM tests, stashing
             ;; the prior value so :after can RESTORE it (no shared-page leak).
             (reset! prior-act-env
                     (when (browser?) (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)))
             (enable-react-act-env!)
             (reactive/reset-scheduler!)
             (client/reset-live-roots!))
   :after (fn []
            (reactive/reset-scheduler!)
            (client/reset-live-roots!)
            (when (browser?)
              (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) @prior-act-env)))})

(def ^:private frame-kw :rt.dom/frame)

(defn- container [] (js/document.createElement "div"))

(defn- thrown-id [f]
  (try (f) nil (catch cljs.core/ExceptionInfo e (:rf.error/id (ex-data e)))))

(defn- in-commit-phase!
  "Run `thunk` synchronously inside a React COMMIT (a layout effect), so a
  `.unmount` called within it hits react-dom 19.2's in-render deferral path (it
  returns normally, scheduling teardown for a later microtask). Renders a
  throwaway root whose sole layout effect calls `thunk`, all under `act` (which
  also flushes the deferred teardown + settlement microtasks), then unmounts the
  throwaway. The throwaway is a plain React component — no ViewCell — so it never
  perturbs the observed live-cell population."
  [thunk]
  (let [c2  (container)
        r   (rdc/createRoot c2)
        drv (fn drv []
              (React/useLayoutEffect (fn [] (thunk) js/undefined) #js [])
              nil)]
    (act! (fn [] (.render r (React/createElement drv))))
    (act! (fn [] (.unmount r)))))

;; One sub-bearing view → one ViewCell under the mounted root.
(defview leaf [_] [:span.leaf (str (sub [:rt.dom/n]))])

(defn- register-app! []
  (rf/make-frame {:id frame-kw :doc "root-teardown DOM probe frame"})
  (rf/reg-sub :rt.dom/n (fn [db _] (:n db)))
  (rf/reg-event :rt.dom/seed (fn [_ _] {:db {:n 7}})))

(deftest real-root-unmount-proves-cell-unmounted-then-dead
  (if-not (browser?)
    (is true ":node — no DOM; the :browser-test runner exercises the DOM body")
    (do
      (register-app!)
      (rf/dispatch-sync [:rt.dom/seed] {:frame frame-kw})
      (let [c    (container)
            root (act!
                  #(ui/mount [frame-provider {:frame frame-kw} [leaf {}]]
                             c {:root-id :rt.dom/root}))
            cells (reactive/current-live-cells)
            cell  (first cells)]
        (testing "the sub-bearing view mounted one connected ViewCell"
          (is (= "7" (.-textContent (.querySelector c ".leaf"))))
          (is (= 1 (count cells)) "exactly one live ViewCell under the root")
          (is (= :connected (reactive/lifecycle cell))))
        (testing "a REAL root unmount proves the cell :unmounted → :dead"
          (act! #(ui/unmount! root))
          (is (= :dead (reactive/lifecycle cell))
              "not stuck at the transient :unknown — the host teardown upgraded it")
          (is (= :unmounted (:reason (peek (reactive/intervals cell))))
              "the interval carries the :unmounted reason")
          (is (= :host-teardown (:proof (peek (reactive/intervals cell))))
              "…proven by the host teardown (03 §4)"))
        (testing "the container is re-mountable — the claim was released"
          (is (= #{} (client/live-root-ids))))))))

(deftest real-root-unmount-isolates-a-sibling-root
  (if-not (browser?)
    (is true ":node — no DOM; the :browser-test runner exercises the DOM body")
    (do
      (register-app!)
      (rf/dispatch-sync [:rt.dom/seed] {:frame frame-kw})
      (let [ca    (container)
            cb    (container)
            root-a (act!
                    #(ui/mount [frame-provider {:frame frame-kw} [leaf {}]]
                               ca {:root-id :rt.dom/root-a}))
            root-b (act!
                    #(ui/mount [frame-provider {:frame frame-kw} [leaf {}]]
                               cb {:root-id :rt.dom/root-b}))]
        ;; two roots, two cells; root B still renders after root A unmounts
        (is (= 2 (count (reactive/current-live-cells))) "two live cells")
        (let [before (vec (reactive/current-live-cells))]
          (act! #(ui/unmount! root-a))
          (testing "root A's cell is dead; root B's cell is untouched"
            (let [alive (filter #(= :connected (reactive/lifecycle %)) before)
                  dead  (filter #(= :dead (reactive/lifecycle %)) before)]
              (is (= 1 (count dead)) "exactly one cell died")
              (is (= 1 (count alive)) "exactly one cell still connected")
              (is (= "7" (.-textContent (.querySelector cb ".leaf")))
                  "root B still renders")))
          (act! #(ui/unmount! root-b)))))))

;; ===========================================================================
;; rf2-vxgfnd.182 — a DEFERRED render-phase host teardown FENCES the claim
;;
;; react-dom 19.2 refuses a SYNCHRONOUS `.unmount` from inside render/commit: it
;; consumes the handle, clears its container marker, emits a console error, and
;; RETURNS NORMALLY while the teardown work stays scheduled for a later
;; microtask. Pre-fix, `unmount!*` released the root-id/container claim
;; immediately, so a same-stack reentrant remount was ADMITTED onto a container
;; React was about to clear — a second root over a clobbered container. The fence
;; holds the claim `:tearing-down` until React's deferred teardown settles.
;; ===========================================================================

(deftest deferred-render-phase-unmount-fences-reentrant-remount
  (if-not (browser?)
    (is true ":node — no DOM; the :browser-test runner exercises the DOM body")
    (async
     done
     (register-app!)
     (rf/dispatch-sync [:rt.dom/seed] {:frame frame-kw})
     (let [c    (container)
           M    (act! #(ui/mount [frame-provider {:frame frame-kw} [leaf {}]]
                                 c {:root-id :rt.dom/deferred}))
           cell (first (reactive/current-live-cells))
           unmount-threw   (atom :unset)
           reentrant-error (atom :unset)]
       (is (= "7" (.-textContent (.querySelector c ".leaf"))) "precondition: rendered")
       (is (= :connected (reactive/lifecycle cell)) "precondition: connected cell")
       (in-commit-phase!
        (fn []
          ;; `.unmount` during a React commit → react-dom 19.2 DEFERS: it returns
          ;; NORMALLY, scheduling the teardown (AC1 — proves the non-throwing path).
          (reset! unmount-threw
                  (try (client/unmount!* M) false (catch :default _ true)))
          ;; a same-stack reentrant remount on the EXACT container/id is FENCED,
          ;; rejected BEFORE any React root creation (caught here so it does not
          ;; escape into the driver's commit).
          (reset! reentrant-error
                  (thrown-id #(client/mount*
                               {:root-id :rt.dom/deferred :provenance :authored}
                               c
                               (fn [] (React/createElement "div" nil "reentrant"))
                               nil nil)))))
       (testing "the host .unmount returned normally — teardown deferred, not thrown (AC1)"
         (is (false? @unmount-threw)))
       (testing "the reentrant remount was REJECTED while teardown was deferred (AC2)"
         (is (contains? #{:rf.error/duplicate-root-id :rf.error/root-container-in-use}
                        @reentrant-error)
             "and admitted only AFTER settlement — never onto the deferred container"))
       ;; The settlement is a real microtask FIFO-ordered after React's deferred
       ;; teardown; await it, then assert convergence + the ratified reuse.
       (js/queueMicrotask
        (fn []
          (testing "after the settlement boundary the root/cell/DOM converge (AC3/AC4)"
            (is (= :dead (reactive/lifecycle cell)) "the deferred teardown reaped the cell")
            (is (= :host-teardown (:proof (peek (reactive/intervals cell))))
                "…proven a host teardown, not left :unknown/reconnectable")
            (is (= "" (.-innerHTML c)) "React cleared the container")
            (is (not (contains? (client/live-root-ids) :rt.dom/deferred))
                "the claim is freed only at the settlement boundary"))
          (testing "a fresh mount on the settled container now succeeds"
            (let [M2 (act! #(ui/mount [frame-provider {:frame frame-kw} [leaf {}]]
                                      c {:root-id :rt.dom/deferred}))]
              (is (some? M2))
              (is (= "7" (.-textContent (.querySelector c ".leaf"))) "the replacement renders")
              (act! #(ui/unmount! M2))))
          (done)))))))

(deftest synchronous-unmount-frees-identity-for-immediate-remount
  ;; The CONTROL (the ratified case, rf2-vxgfnd.18): a SYNCHRONOUS host teardown
  ;; (a normal `ui/unmount!`, not from inside render/commit) tears down during
  ;; `.unmount`, so the claim frees immediately and a same-stack immediate
  ;; remount on the exact container succeeds — the fence must NOT hold here.
  (if-not (browser?)
    (is true ":node — no DOM; the :browser-test runner exercises the DOM body")
    (do
      (register-app!)
      (rf/dispatch-sync [:rt.dom/seed] {:frame frame-kw})
      (let [c    (container)
            M    (act! #(ui/mount [frame-provider {:frame frame-kw} [leaf {}]]
                                  c {:root-id :rt.dom/sync}))
            cell (first (reactive/current-live-cells))]
        (is (= :connected (reactive/lifecycle cell)))
        (act! #(client/unmount!* M))
        (testing "a synchronous teardown reaps + frees immediately"
          (is (= :dead (reactive/lifecycle cell)))
          (is (= "" (.-innerHTML c)))
          (is (not (contains? (client/live-root-ids) :rt.dom/sync))
              "the claim is released synchronously — no lingering :tearing-down fence"))
        (testing "an IMMEDIATE same-container remount succeeds (ratified reuse)"
          (let [M2 (act! #(ui/mount [frame-provider {:frame frame-kw} [leaf {}]]
                                    c {:root-id :rt.dom/sync}))]
            (is (some? M2))
            (is (= "7" (.-textContent (.querySelector c ".leaf"))))
            (act! #(ui/unmount! M2))))))))

;; NOTE (rf2-vxgfnd.275): a real-React *cell-less* deferred-fence fixture is NOT
;; constructible in this DEV `:browser-test` build — the emitter gives EVERY dev
;; view a stable ViewCell hook skeleton (HMR same-signature), so no rendered view
;; is cell-less here; a subless view goes cell-less only under `:advanced` +
;; `goog.DEBUG=false` (direct React.memo). The ROOT-LEVEL settlement law
;; (`live-reporters` / `report-root-teardown!`) that makes a rendered cell-less
;; root's deferral observable is exercised in real React by the cell-bearing
;; `deferred-render-phase-unmount-fences-reentrant-remount` above (which now
;; routes through the reporter sentinel), and the cell-less-specific path
;; (reporter live, `root-cell-count` 0) is pinned by the graft + wiring fixtures
;; (`reactive-root-teardown-cljs-test`, `root-teardown-wiring-cljs-test`).
