(ns re-frame.adapter.uix-dispatch-frame-capture-cljs-test
  "Adapter-parity port of `re-frame.dispatch-frame-capture-cljs-test`
  (rf2-l5q3) to the UIx adapter (rf2-ta4b5).

  The contract: *current-frame* propagates across direct `rf/dispatch`
  calls. The drain loop binds `frame/*current-frame*` to the envelope's
  `:frame` for the duration of the handler chain, so a synchronous
  `rf/dispatch` from inside the handler body sees the in-flight event's
  frame.

  This port exists so the contract is observed under a UIx-installed
  adapter — the dispatch-envelope's `:frame` default is built via the
  `:adapter/current-frame` late-bind hook (registered by the UIx
  adapter at ns-load time, reading `_currentValue` off the shared
  React context object). The headless tests below do not mount React,
  so the React-context tier reads the createContext default
  (`:rf/default`); resolution falls through the dynamic-var tier as
  expected.

  Parallel to:
    - implementation/adapters/reagent/test/re_frame/dispatch_frame_capture_cljs_test.cljs

  ns ends in `-cljs-test` so shadow-cljs `:node-test` picks it up."
  (:require [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            ;; rf2-qwm0a — listener surface lives in
            ;; `re-frame.trace.tooling` (production-DCE split).
            [re-frame.trace.tooling :as trace-tooling]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.test-support :as test-support]))

;; Per cljs.test: async tests require fixtures supplied as a map
;; (fn-form fixtures don't suspend across the async body). Wrap the
;; snapshot/restore pattern as `{:before :after}`.

(def ^:private registrar-snapshot (atom nil))

(defn- before! []
  (reset! registrar-snapshot (test-support/snapshot-registrar))
  (reset! frame/frames {})
  ;; Per rf2-wkxng / rf2-6m0se: clear the per-frame schema registry so
  ;; ns-load `reg-app-schema` calls from sibling test namespaces don't
  ;; fire post-commit validation rollbacks against this test's frames.
  (when-let [clear! (late-bind/get-fn :schemas/clear-by-frame!)]
    (clear!))
  (substrate-adapter/dispose-adapter!)
  (trace-tooling/clear-listeners!)
  (substrate-adapter/install-adapter! uix-adapter/adapter)
  (frame/ensure-default-frame!))

(defn- after! []
  (when-let [snap @registrar-snapshot]
    (test-support/restore-registrar! snap)
    (reset! registrar-snapshot nil))
  (reset! frame/frames {}))

(use-fixtures :each {:before before! :after after!})

;; ---- helpers --------------------------------------------------------------

(defn- seed-frames!
  []
  (rf/reg-frame :rf-l5q3-uix/tenant-a {:doc "tenant-a frame"})
  (rf/reg-frame :rf-l5q3-uix/tenant-b {:doc "tenant-b frame"})
  (rf/reg-event-db :rf-l5q3-uix/seed
                   (fn [_ [_ marker]] {:marker marker :received []}))
  (rf/dispatch-sync [:rf-l5q3-uix/seed :rf/default] {:frame :rf/default})
  (rf/dispatch-sync [:rf-l5q3-uix/seed :tenant-a] {:frame :rf-l5q3-uix/tenant-a})
  (rf/dispatch-sync [:rf-l5q3-uix/seed :tenant-b] {:frame :rf-l5q3-uix/tenant-b}))

(defn- received
  [frame-id]
  (let [db (frame/frame-app-db-value frame-id)]
    (:received db)))

;; ---- 1. Synchronous direct rf/dispatch ------------------------------------

(deftest sync-dispatch-from-handler-body-routes-to-handlers-frame-uix
  (testing "rf/dispatch called synchronously from inside a handler routes to that handler's frame under UIx"
    (seed-frames!)
    (rf/reg-event-fx :rf-l5q3-uix/parent
                     (fn [_ _]
                       (rf/dispatch [:rf-l5q3-uix/landed])
                       {}))
    (rf/reg-event-db :rf-l5q3-uix/landed
                     (fn [db _]
                       (update db :received (fnil conj []) :landed-sync)))
    (rf/dispatch-sync [:rf-l5q3-uix/parent] {:frame :rf-l5q3-uix/tenant-a})
    (is (= [:landed-sync] (received :rf-l5q3-uix/tenant-a))
        "the :landed event must land on :tenant-a, not :rf/default")
    (is (empty? (received :rf/default))
        ":rf/default must NOT have received :landed — the dispatch was scoped to :tenant-a")))

;; ---- 2. setTimeout-deferred direct rf/dispatch ----------------------------

(deftest direct-dispatch-from-set-timeout-falls-through-to-default-uix
  (testing "raw rf/dispatch from a setTimeout callback escapes *current-frame* under UIx — documented gotcha"
    (async done
      (seed-frames!)
      (rf/reg-event-fx :rf-l5q3-uix/defer-raw
                       (fn [_ _]
                         (js/setTimeout
                           (fn [] (rf/dispatch [:rf-l5q3-uix/landed-raw]))
                           0)
                         {}))
      (rf/reg-event-db :rf-l5q3-uix/landed-raw
                       (fn [db _]
                         (update db :received (fnil conj []) :landed-raw)))
      (rf/dispatch-sync [:rf-l5q3-uix/defer-raw] {:frame :rf-l5q3-uix/tenant-a})
      (js/setTimeout
        (fn []
          (js/setTimeout
            (fn []
              (is (= [:landed-raw] (received :rf/default))
                  ":landed-raw lands on :rf/default (dynamic binding is dead in the setTimeout callback)")
              (is (empty? (received :rf-l5q3-uix/tenant-a))
                  ":tenant-a sees nothing — raw rf/dispatch can't recover the in-flight frame from a setTimeout")
              (done))
            10))
        10))))

;; ---- 3. :fx [[:dispatch ...]] from a setTimeout — workaround --------------

(deftest fx-dispatch-from-handler-routes-to-handlers-frame-uix
  (testing ":fx [[:dispatch ...]] routes to the handler's frame under UIx — canonical pattern"
    (seed-frames!)
    (rf/reg-event-fx :rf-l5q3-uix/parent-fx
                     (fn [_ _]
                       {:fx [[:dispatch [:rf-l5q3-uix/landed-fx]]]}))
    (rf/reg-event-db :rf-l5q3-uix/landed-fx
                     (fn [db _]
                       (update db :received (fnil conj []) :landed-fx)))
    (rf/dispatch-sync [:rf-l5q3-uix/parent-fx] {:frame :rf-l5q3-uix/tenant-a})
    (is (= [:landed-fx] (received :rf-l5q3-uix/tenant-a))
        ":fx [[:dispatch ...]] threads the frame through fx/do-fx — lands on :tenant-a")
    (is (empty? (received :rf/default))
        ":rf/default sees nothing")))

;; ---- 4a. :dispatch-later — async with frame capture -----------------------

(deftest dispatch-later-survives-the-timer-uix
  (testing ":dispatch-later threads :frame through the closure under UIx — survives the async escape"
    (async done
      (seed-frames!)
      (rf/reg-event-fx :rf-l5q3-uix/parent-later
                       (fn [_ _]
                         {:fx [[:dispatch-later
                                {:ms    0
                                 :event [:rf-l5q3-uix/landed-later]}]]}))
      (rf/reg-event-db :rf-l5q3-uix/landed-later
                       (fn [db _]
                         (update db :received (fnil conj []) :landed-later)))
      (rf/dispatch-sync [:rf-l5q3-uix/parent-later] {:frame :rf-l5q3-uix/tenant-a})
      (js/setTimeout
        (fn []
          (js/setTimeout
            (fn []
              (is (= [:landed-later] (received :rf-l5q3-uix/tenant-a))
                  ":dispatch-later landed on :tenant-a even though the timer fired after the binding popped")
              (is (empty? (received :rf/default))
                  ":rf/default sees nothing")
              (done))
            50))
        50))))

;; ---- 4b. (dispatcher) — async with explicit capture -----------------------

(deftest dispatcher-survives-set-timeout-uix
  (testing "(rf/dispatcher) captures the in-flight frame under UIx; the captured fn is safe to call from setTimeout"
    (async done
      (seed-frames!)
      (rf/reg-event-fx :rf-l5q3-uix/parent-bound
                       (fn [_ _]
                         (let [d (rf/dispatcher)]
                           (js/setTimeout
                             (fn [] (d [:rf-l5q3-uix/landed-bound]))
                             0))
                         {}))
      (rf/reg-event-db :rf-l5q3-uix/landed-bound
                       (fn [db _]
                         (update db :received (fnil conj []) :landed-bound)))
      (rf/dispatch-sync [:rf-l5q3-uix/parent-bound] {:frame :rf-l5q3-uix/tenant-a})
      (js/setTimeout
        (fn []
          (js/setTimeout
            (fn []
              (is (= [:landed-bound] (received :rf-l5q3-uix/tenant-a))
                  "(rf/dispatcher) captured :tenant-a at call time; the setTimeout callback dispatches there")
              (is (empty? (received :rf/default))
                  ":rf/default sees nothing")
              (done))
            10))
        10))))

;; ---- 5. cross-frame isolation hardness check ------------------------------

(deftest sync-dispatch-isolation-between-frames-uix
  (testing "synchronous dispatch from :tenant-a handler stays in :tenant-a under UIx; :tenant-b is untouched"
    (seed-frames!)
    (rf/reg-event-fx :rf-l5q3-uix/fan
                     (fn [_ [_ payload]]
                       (rf/dispatch [:rf-l5q3-uix/leaf payload])
                       {}))
    (rf/reg-event-db :rf-l5q3-uix/leaf
                     (fn [db [_ payload]]
                       (update db :received (fnil conj []) payload)))
    (rf/dispatch-sync [:rf-l5q3-uix/fan :a-payload] {:frame :rf-l5q3-uix/tenant-a})
    (rf/dispatch-sync [:rf-l5q3-uix/fan :b-payload] {:frame :rf-l5q3-uix/tenant-b})
    (is (= [:a-payload] (received :rf-l5q3-uix/tenant-a))
        ":tenant-a only sees its own :a-payload")
    (is (= [:b-payload] (received :rf-l5q3-uix/tenant-b))
        ":tenant-b only sees its own :b-payload")
    (is (empty? (received :rf/default))
        ":rf/default sees nothing — neither cascade leaked")))
