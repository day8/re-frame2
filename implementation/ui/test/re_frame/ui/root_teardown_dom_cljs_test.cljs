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
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            ["react" :as React]
            ["react-dom" :as react-dom]
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
;; `act()`, NOT UIx/Helix/Reagent machinery, so this fixture stays native
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

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter :ambient-frame nil})
  (fn [f]
    ;; Enable React's act environment for this ns's DOM tests, then RESTORE the
    ;; prior value so the fixture neither depends on nor leaks the shared-page
    ;; flag.
    (let [prior (when (browser?) (.-IS_REACT_ACT_ENVIRONMENT js/globalThis))]
      (enable-react-act-env!)
      (reactive/reset-scheduler!)
      (client/reset-live-roots!)
      (try (f)
           (finally (reactive/reset-scheduler!)
                    (client/reset-live-roots!)
                    (when (browser?)
                      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) prior)))))))

(def ^:private frame-kw :rt.dom/frame)

(defn- container [] (js/document.createElement "div"))

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
