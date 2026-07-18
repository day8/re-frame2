(ns re-frame.ui.root-incarnation-activity-dom-cljs-test
  "rf2-vxgfnd.92 / .85 — root-incarnation ownership on the REAL React 19 + real
  DOM path (not just the graft).

  rf2-vxgfnd.85 landed the substrate reap; .92 wires the live path —
  `client/mount` mints a root incarnation and provides it via context so every
  ViewCell enrols under it (`attach-root!`), and `client/unmount!` passes it to
  `teardown-root!`. This file closes the loop end-to-end:

    - MOUNT-ATTACH: a real `ui/mount` enrols the cell under the root's incarnation
      (the context→attach seam), and a real `ui/unmount!` reaps it :dead.
    - ACTIVITY: a real React 19 `<Activity mode=\"hidden\">` fires the cell's
      layout-effect cleanup (`disconnect!`) while PRESERVING the cell — so the
      cell is hidden BEFORE any unmount window. A root unmount then reaps that
      pre-hidden cell via the incarnation (`:unmounted {:proof :host-teardown}` →
      `:dead`); an Activity hide with NO root unmount stays reconnectable
      (`:activity-hidden {:proof :reconnect}` on reveal). This activates .85's
      P1 on the real path.

  Browser-only bodies — `-dom-cljs-test$` opts into `:browser-test`; `:node-test`
  loads it too, where each DOM body gates on `(browser?)` and no-ops."
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

;; This ns is a flushSync-TIMING / Activity-microtask discriminator, not an act
;; test: each `flushSync` checkpoint below deliberately forces React to commit
;; exactly what the reactive scheduler / Activity mode flip has notified SO FAR,
;; to observe the disconnect/reconnect lifecycle across the microtask boundary.
;; `act` would drain the pending microtask and collapse that timing, so the
;; whole ns runs the real flushSync path with IS_REACT_ACT_ENVIRONMENT OFF (the
;; react_shared_suite.cljs pattern) — no "not wrapped in act" warning, timing
;; preserved. Sibling suites on the shared `:browser-test` page leak the flag
;; true; the fixture stashes the prior value and restores it in :after (which
;; cljs.test runs after the async body completes).
(defonce ^:private prior-act-env (atom nil))

(defn- disable-act-env! []
  (when (exists? js/globalThis)
    (reset! prior-act-env (.-IS_REACT_ACT_ENVIRONMENT js/globalThis))
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)))

(defn- restore-act-env! []
  (when (exists? js/globalThis)
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) @prior-act-env)))

;; The WATCHABLE first-party adapter makes a sub move fire the port's on-change
;; watch that drives the Activity-mode re-render; `:ambient-frame nil` keeps
;; frame resolution on the enclosing frame-provider.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter :ambient-frame nil :async? true})
  {:before #(do (disable-act-env!) (reactive/reset-scheduler!) (client/reset-live-roots!))
   :after  #(do (reactive/reset-scheduler!) (client/reset-live-roots!) (restore-act-env!))})

(def ^:private frame-kw :ract/frame)

(defn- container [] (js/document.createElement "div"))

(def ^:private Activity react/Activity)
(def ^:private StrictMode react/StrictMode)

;; rf2-vxgfnd.164: counts leaf render-body invocations. StrictMode's dev
;; double-invoke renders the body TWICE per commit, so this is the positive
;; control proving a replay really happened — see
;; `strictmode-replay-control-stays-unannotated`.
(defonce ^:private leaf-body-runs (atom 0))

(defview leaf [_]
  (let [_ (swap! leaf-body-runs inc)]
    [:span.leaf (str (sub [:ract/n]))]))

;; Wraps the leaf in a React 19 <Activity>, its mode driven by a sub — flipping
;; :ract/hidden? re-renders this cell, hides the Activity subtree, and fires the
;; leaf's layout-effect cleanup (disconnect!) while preserving the leaf cell.
(defview act-app [_]
  [Activity {:mode (if (sub [:ract/hidden?]) "hidden" "visible")}
   [leaf {}]])

(defn- register-app! []
  (rf/make-frame {:id frame-kw :doc "root-incarnation Activity probe frame"})
  (rf/reg-sub :ract/n (fn [db _] (:n db)))
  (rf/reg-sub :ract/hidden? (fn [db _] (:hidden? db)))
  (rf/reg-event :ract/seed (fn [_ _] {:db {:n 7 :hidden? false}}))
  (rf/reg-event :ract/hide (fn [{:keys [db]} _] {:db (assoc db :hidden? true)}))
  (rf/reg-event :ract/show (fn [{:keys [db]} _] {:db (assoc db :hidden? false)})))

(defn- incarnation [] (:root-incarnation (client/live-root-entry :ract/root)))

(defn- leaf-cell
  "The mounted ViewCell whose committed dependency set observes [:ract/n]."
  []
  (some (fn [cell]
          (when (contains? (reactive/committed-target-keys cell)
                           [:sub frame-kw [:ract/n]])
            cell))
        (reactive/current-live-cells)))

;; ===========================================================================
;; MOUNT-ATTACH — a real mount enrols the cell under the root incarnation, and a
;; real unmount reaps it (the context→attach + unmount-passes-incarnation wiring)
;; ===========================================================================

(deftest real-mount-attaches-cell-to-root-incarnation-and-unmount-reaps
  (if-not (browser?)
    (is true ":node — no DOM; the :browser-test runner exercises the DOM body")
    (do
      (register-app!)
      (rf/dispatch-sync [:ract/seed] {:frame frame-kw})
      (let [c    (container)
            root (react-dom/flushSync
                  #(ui/mount [frame-provider {:frame frame-kw} [leaf {}]]
                             c {:root-id :ract/root}))
            cell (leaf-cell)]
        (testing "the mounted cell is enrolled under the root's incarnation (context→attach)"
          (is (= "7" (.-textContent (.querySelector c ".leaf"))))
          (is (some? (incarnation)) "the client minted a root incarnation")
          (is (identical? (incarnation) (reactive/cell-root cell))
              "viewcell attached the cell to the root incarnation via the context (rf2-vxgfnd.92)"))
        (testing "a real unmount reaps the cell via the incarnation → :dead"
          (react-dom/flushSync #(ui/unmount! root))
          (is (= :dead (reactive/lifecycle cell)))
          (is (= :host-teardown (:proof (peek (reactive/intervals cell)))))
          (is (= #{} (client/live-root-ids))))))))

;; ===========================================================================
;; ACTIVITY — hide (before any window) → root unmount reaps the pre-hidden cell
;; ===========================================================================

(deftest activity-hidden-then-root-unmount-reaps-the-pre-hidden-cell
  (if-not (browser?)
    (is true ":node — no DOM; the :browser-test runner exercises the DOM body")
    (do
      (register-app!)
      (rf/dispatch-sync [:ract/seed] {:frame frame-kw})
      (let [c    (container)
            root (react-dom/flushSync
                  #(ui/mount [frame-provider {:frame frame-kw} [act-app {}]]
                             c {:root-id :ract/root}))
            cell (leaf-cell)]
        (async done
          (is (= "7" (.-textContent (.querySelector c ".leaf"))) "leaf mounted")
          (is (identical? (incarnation) (reactive/cell-root cell))
              "the leaf cell is owned by the root incarnation")
          ;; Activity-hide the leaf — a sub move re-renders act-app on the
          ;; microtask, flipping the Activity to mode=hidden.
          (rf/dispatch-sync [:ract/hide] {:frame frame-kw})
          (js/queueMicrotask
           (fn []
             (react-dom/flushSync (fn []))    ;; commit the mode flip
             (testing "React 19 Activity fired the leaf's cleanup — hidden, cell preserved + owned"
               (is (= :disconnected (reactive/lifecycle cell))
                   "the pre-window hide is a transient disconnect")
               (is (identical? (incarnation) (reactive/cell-root cell))
                   "root ownership SURVIVES the Activity hide"))
             (testing "a real root unmount reaps the pre-hidden cell via the incarnation"
               (react-dom/flushSync #(ui/unmount! root))
               (is (= :dead (reactive/lifecycle cell))
                   "the pre-hidden leaf is reaped on the LIVE path — not left reconnectable")
               (is (= {:state :disconnected :reason :unmounted :proof :host-teardown}
                      (peek (reactive/intervals cell)))
                   ":unmounted {:proof :host-teardown} — activates .85's P1 on the real path")
               (is (= #{} (client/live-root-ids))))
             (done))))))))

(deftest activity-hidden-without-unmount-stays-reconnectable
  (if-not (browser?)
    (is true ":node — no DOM; the :browser-test runner exercises the DOM body")
    (do
      (register-app!)
      (rf/dispatch-sync [:ract/seed] {:frame frame-kw})
      (let [c    (container)
            root (react-dom/flushSync
                  #(ui/mount [frame-provider {:frame frame-kw} [act-app {}]]
                             c {:root-id :ract/root}))
            cell (leaf-cell)]
        (async done
          (rf/dispatch-sync [:ract/hide] {:frame frame-kw})
          (js/queueMicrotask
           (fn []
             (react-dom/flushSync (fn []))
             (is (= :disconnected (reactive/lifecycle cell)) "hidden, not :dead")
             ;; reveal — no root unmount ever ran
             (rf/dispatch-sync [:ract/show] {:frame frame-kw})
             (js/queueMicrotask
              (fn []
                (react-dom/flushSync (fn []))
                (testing "a reveal reconnects and proves the interval an Activity hide"
                  (is (= :connected (reactive/lifecycle cell)) "reconnected, never :dead")
                  (is (= :activity-hidden (:reason (peek (reactive/intervals cell))))
                      "proven :activity-hidden {:proof :reconnect} — the registry never killed it"))
                (react-dom/flushSync #(ui/unmount! root))
                (done))))))))))

;; ===========================================================================
;; rf2-vxgfnd.164 — TWO REAL COMMITS IN ONE SYNCHRONOUS STACK are honestly
;; :unknown. A microtask separates JavaScript checkpoints, NOT React commits, so
;; the existing reveal fixture above (which yields a microtask between hide and
;; reveal, letting the settle fire → :activity-hidden) cannot expose the
;; ambiguity. Here the two commits are CONSECUTIVE — the reconnect beats the
;; settle exactly as a StrictMode replay would, so no exact discriminator exists
;; and the honest floor is :unknown, never a fabricated Activity-hide proof.
;; ===========================================================================

(deftest consecutive-flushsync-hide-reveal-is-honestly-unknown
  (if-not (browser?)
    (is true ":node — no DOM; the :browser-test runner exercises the DOM body")
    (do
      (register-app!)
      (rf/dispatch-sync [:ract/seed] {:frame frame-kw})
      (let [c    (container)
            root (react-dom/flushSync
                  #(ui/mount [frame-provider {:frame frame-kw} [act-app {}]]
                             c {:root-id :ract/root}))
            cell (leaf-cell)]
        (async done
          (is (= "7" (.-textContent (.querySelector c ".leaf"))) "leaf mounted visible")
          ;; commit 1 — HIDE (the proven dispatch → microtask → flushSync path):
          ;; the reactive flush notifies React on its microtask, and flushSync
          ;; commits the mode flip, firing the leaf's cleanup → disconnect! (which
          ;; arms the settle microtask).
          (rf/dispatch-sync [:ract/hide] {:frame frame-kw})
          (js/queueMicrotask
           (fn []
             (react-dom/flushSync (fn []))
             (is (= :disconnected (reactive/lifecycle cell))
                 "the hide fired the leaf's cleanup — disconnected, settle armed")
             ;; commit 2 — REVEAL, CONSECUTIVE in the SAME synchronous stack. No
             ;; microtask yield: `flush-pending!` makes the notification synchronous
             ;; and flushSync commits it BEFORE the settle microtask (armed at
             ;; commit 1) has run.
             (rf/dispatch-sync [:ract/show] {:frame frame-kw})
             (reactive/flush-pending!)
             (react-dom/flushSync (fn []))
             (is (= :connected (reactive/lifecycle cell)) "the reveal reconnected")
             ;; let the (now too-late) settle microtask run, then read the evidence.
             (js/queueMicrotask
              (fn []
                (testing "consecutive commits are indistinguishable from a replay — honestly :unknown"
                  (is (= :unknown (:reason (peek (reactive/intervals cell))))
                      "the unsettled reconnect stays :unknown — no Activity hide fabricated
                       for two real commits in one stack (rf2-vxgfnd.164)")
                  (is (not-any? #(= :activity-hidden (:reason %)) (reactive/intervals cell))
                      "no interval carries a fabricated :activity-hidden proof"))
                (react-dom/flushSync #(ui/unmount! root))
                (done))))))))))

(deftest strictmode-replay-control-stays-unannotated
  ;; The control (rf2-vxgfnd.164 AC2): a REAL React StrictMode dev double-invoke of
  ;; the leaf's layout effect (setup→cleanup→setup within one mount commit) is a
  ;; reconnect that also beats the settle. It must remain UNANNOTATED — same honest
  ;; :unknown floor as the consecutive-commits case; the host cannot tell them
  ;; apart, and NO :activity-hidden fact is fabricated.
  ;;
  ;; This control asserts the replay POSITIVELY. An earlier version only checked
  ;; that no `:activity-hidden` reason appeared and hedged the disconnect as "if
  ;; any" — which stayed green with StrictMode removed entirely, i.e. it proved
  ;; nothing about replay classification. The body below now pins the actual
  ;; setup→cleanup→setup interval first.
  (if-not (browser?)
    (is true ":node — no DOM; the :browser-test runner exercises the DOM body")
    (do
      (register-app!)
      (rf/dispatch-sync [:ract/seed] {:frame frame-kw})
      (reset! leaf-body-runs 0)
      (let [c    (container)
            root (react-dom/flushSync
                  #(ui/mount [StrictMode {} [frame-provider {:frame frame-kw} [leaf {}]]]
                             c {:root-id :ract/root}))
            cell (leaf-cell)]
        (is (= "7" (.-textContent (.querySelector c ".leaf"))) "leaf mounted under StrictMode")
        (is (= :connected (reactive/lifecycle cell))
            "after the dev double-invoke the leaf is connected")
        ;; POSITIVE CONTROL FIRST (rf2-vxgfnd.164): prove the dev double-invoke
        ;; ACTUALLY happened before asserting anything about how it was
        ;; classified. StrictMode renders each body twice per commit, so a
        ;; single mount runs the leaf body more than once. Dropping StrictMode
        ;; from the tree above leaves exactly one run and turns this red, so the
        ;; test can no longer pass vacuously on a replay that never occurred.
        ;;
        ;; NB the replay's disconnect does NOT land on the surviving cell: the
        ;; double-invoke builds a cell that is torn down and replaced, so the
        ;; live cell's own interval log is legitimately empty of disconnects.
        ;; That is exactly why the earlier "the replay disconnect (if any)"
        ;; phrasing proved nothing — there is no such interval to inspect, so
        ;; the honest claim is about the whole live set, asserted below.
        (is (> @leaf-body-runs 1)
            "the StrictMode dev double-invoke really replayed the leaf body")
        (is (not-any? (fn [c]
                        (some #(= :activity-hidden (:reason %))
                              (reactive/intervals c)))
                      (reactive/current-live-cells))
            "after a REAL StrictMode replay, NO live cell carries a fabricated
             :activity-hidden proof (rf2-vxgfnd.164)")
        (react-dom/flushSync #(ui/unmount! root))))))
