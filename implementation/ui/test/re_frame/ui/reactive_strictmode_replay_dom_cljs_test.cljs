(ns re-frame.ui.reactive-strictmode-replay-dom-cljs-test
  "rf2-8ds0v (PR #6567 audit reopen) — the React 19 StrictMode committed-instance
  RECORD replay, on the REAL React 19 + real DOM path (the browser reproduction
  that reopened the obligation).

  React 19 StrictMode intentionally re-invokes a mounting component's layout
  effects (setup→cleanup→setup). For the ViewCell that means its reconcile layout
  effect runs `reactive/commit!` with the EXACT SAME committed capture twice, with
  a `disconnect!` (the effect cleanup) in between. The reconciler was already
  OWNERSHIP-idempotent, but the per-commit view-evidence record was NOT: the
  replayed re-commit OVERWROTE the genuine first `:mount` record with a spurious
  `:foreign-or-react` one and advanced `:render-key` twice for a single rendered
  commit (the exact browser symptom the audit recorded). The fix keys the mint on
  the originating capture's identity so a re-commit of the same capture is a no-op
  (`reactive/mint-commit-record!`).

  This proves the fix on the real path: after a genuine StrictMode dev
  double-invoke (positively controlled by the doubled body count), the owning
  cell's committed-instance record is STILL the genuine `:mount` record — not
  overwritten by a replay. Removing the guard turns the `:mount` assertions red
  (the record reads `:foreign-or-react`), so this cannot pass vacuously.

  Browser-only body — `-dom-cljs-test$` opts into `:browser-test`; `:node-test`
  loads it too, where the body gates on `(browser?)` and no-ops. The production
  elision of the whole record plane is covered by `tool_evidence_elision_prod_test`
  / `render_key_dom_stamp_elision_prod_test`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            ["react" :as react]
            ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview frame-provider sub]]
            [re-frame.ui.client :as client]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.viewcell]))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

;; StrictMode's full mount→cleanup→mount effect replay is driven synchronously by
;; React 19's `act` (unlike `flushSync`, which does not complete the layout-effect
;; replay). Enable the act environment for this suite and restore the prior value
;; in :after so sibling suites on the shared browser page are unaffected.
(defonce ^:private prior-act-env (atom nil))

(defn- enable-act-env! []
  (when (exists? js/globalThis)
    (reset! prior-act-env (.-IS_REACT_ACT_ENVIRONMENT js/globalThis))
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)))

(defn- restore-act-env! []
  (when (exists? js/globalThis)
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) @prior-act-env)))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter :ambient-frame nil :async? true})
  {:before #(do (enable-act-env!) (reactive/reset-scheduler!) (client/reset-live-roots!))
   :after  #(do (reactive/reset-scheduler!) (client/reset-live-roots!) (restore-act-env!))})

(def ^:private frame-kw :sm-replay/frame)
(def ^:private StrictMode react/StrictMode)

(defn- container [] (js/document.createElement "div"))

;; Counts leaf render-body invocations — the POSITIVE CONTROL that StrictMode's
;; dev double-invoke actually happened (it renders the body twice per commit).
(defonce ^:private body-runs (atom 0))

(defview replay-view
  "A sub-bearing view (so it owns a ViewCell) with a compiler-owned host root."
  [_]
  (let [_ (swap! body-runs inc)]
    [:div.sm-replay (str (sub [:smr/n]))]))

;; The child mounts into an ALREADY-COMMITTED StrictMode subtree (toggled in by a
;; sub), so React runs the newly-mounted ViewCell's layout effects
;; setup→cleanup→setup on the SAME preserved fiber — the exact same-capture
;; re-commit the audit reproduced. (A StrictMode root's initial mount instead
;; rebuilds a fresh fiber, so its survivor never sees the replay — rf2-vxgfnd.164.)
(defview shell-view
  [_]
  [:div.sm-shell
   (when (sub [:smr/show?])
     [replay-view {}])])

(defn- register-app! []
  (rf/make-frame {:id frame-kw :doc "StrictMode record-replay probe frame"})
  (rf/reg-sub :smr/n (fn [db _] (:n db)))
  (rf/reg-sub :smr/show? (fn [db _] (:show? db)))
  (rf/reg-event :smr/seed (fn [_ _] {:db {:n 7 :show? false}}))
  (rf/reg-event :smr/show (fn [{:keys [db]} _] {:db (assoc db :show? true)})))

(defn- owning-cell
  "The live ViewCell that committed ownership of [:smr/n] under this frame."
  []
  (some (fn [cell]
          (when (contains? (reactive/committed-target-keys cell)
                           [:sub frame-kw [:smr/n]])
            cell))
        (reactive/current-live-cells)))

;; ===========================================================================
;; A real StrictMode dev double-invoke of the ViewCell's reconcile layout effect
;; must NOT replay the committed-instance record.
;; ===========================================================================

(deftest strictmode-replay-does-not-duplicate-the-committed-instance-record
  (if-not (browser?)
    (is true ":node — no DOM; the :browser-test runner exercises the DOM body")
    (async done
      (let [act-fn (.-act react)]
        (if-not (fn? act-fn)
          (do (is true "React.act unavailable in this runner") (done))
          (do
            (register-app!)
            (rf/dispatch-sync [:smr/seed] {:frame frame-kw})
            (reset! body-runs 0)
            (let [c    (container)
                  root (atom nil)
                  finish!
                  (fn []
                    (.then (js/Promise.resolve (act-fn #(some-> @root ui/unmount!)))
                           (fn [_] (.remove c) (done))))
                  assert-record!
                  (fn [_]
                    (let [cell (owning-cell)]
                      (testing "the StrictMode dev double-invoke really replayed (positive control)"
                        (is (> @body-runs 1)
                            "StrictMode rendered the replay-view body more than once — a
                             replay genuinely occurred, so this proof cannot pass vacuously"))
                      (is (= "7" (.-textContent (.querySelector c ".sm-replay")))
                          "the toggled-in view mounted and rendered under StrictMode")
                      (is (some? cell) "exactly one live cell owns the subscription")
                      (when (some? cell)
                        (let [record (reactive/commit-record cell)]
                          (is (map? record)
                              "the connected commit published a committed-instance record")
                          (is (= :connected (:connection record))
                              "the cell is connected after the replay")
                          (is (= [{:cause :mount}] (:rf.view/causes record))
                              "GREEN: the genuine :mount record STANDS — the StrictMode
                               re-commit of the SAME capture did NOT overwrite it with a
                               spurious :foreign-or-react replay record")
                          (is (integer? (:render-key record))
                              "render-key is a single integer for the one rendered commit"))))
                    (finish!))
                  ;; Step 2: toggle the child IN under the already-committed
                  ;; StrictMode subtree; its ViewCell's layout effects replay on the
                  ;; preserved fiber (setup→cleanup→setup), re-committing the SAME
                  ;; capture. THEN assert the record was not replayed.
                  show-child!
                  (fn [_]
                    (reset! body-runs 0)
                    (.then (js/Promise.resolve
                             (act-fn #(rf/dispatch-sync [:smr/show] {:frame frame-kw})))
                           assert-record!))]
              (.appendChild js/document.body c)
              ;; Step 1: mount the shell (child absent) under a PERSISTENT StrictMode.
              (.then (js/Promise.resolve
                       (act-fn
                         #(reset! root
                                  (ui/mount [StrictMode {}
                                             [frame-provider {:frame frame-kw}
                                              [shell-view {}]]]
                                            c {:root-id :sm-replay/root}))))
                     show-child!))))))))
