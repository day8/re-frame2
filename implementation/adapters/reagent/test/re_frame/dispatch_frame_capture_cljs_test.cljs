(ns re-frame.dispatch-frame-capture-cljs-test
  "`*current-frame*` across direct `rf/dispatch` calls. The drain loop's
  `process-event!` binds it to the envelope's `:frame` for the handler
  chain, so a synchronous `rf/dispatch` from a handler body lands on the
  in-flight frame. An async escape (setTimeout, Promise.then,
  requestAnimationFrame) has no binding and, with no `:rf/default` floor,
  raises `:rf.error/no-frame-context`; the explicit captures are
  `:fx [[:dispatch ...]]`, `:dispatch-later` and
  `(:dispatch (rf/capture-frame))`. See spec/002-Frames.md §Dispatch and the
  dynamic-binding tier."
  (:require [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]))

;; Async tests need the map-form fixture (`:async? true`): a fn-form
;; fixture's teardown runs before an `(async done)` body completes.
;; `:ambient-frame nil` because every dispatch here names its frame.

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter :async? true :ambient-frame nil}))

;; ---- helpers --------------------------------------------------------------

(defn- seed-frames!
  "Register two non-default frames and capture per-frame markers in
  app-db so a test assertion can identify which frame served a
  dispatch."
  []
  (rf/make-frame {:id :rf-l5q3/tenant-a :doc "tenant-a frame"})
  (rf/make-frame {:id :rf-l5q3/tenant-b :doc "tenant-b frame"})
  (rf/reg-event :rf-l5q3/seed
                   (fn [{:keys [db]} [_ marker]] {:db {:marker marker :received []}}))
  (rf/dispatch-sync [:rf-l5q3/seed :rf/default] {:frame :rf/default})
  (rf/dispatch-sync [:rf-l5q3/seed :tenant-a] {:frame :rf-l5q3/tenant-a})
  (rf/dispatch-sync [:rf-l5q3/seed :tenant-b] {:frame :rf-l5q3/tenant-b}))

(defn- received
  "Return the :received vector for a frame's current app-db."
  [frame-id]
  (let [db (rf.frame/frame-app-db-value frame-id)]
    (:received db)))

;; ---- 1. Synchronous direct rf/dispatch ------------------------------------
;;
;; The binding is established and torn down PER EVENT, so the same pattern
;; fired next on :tenant-b stays on :tenant-b.

(deftest sync-dispatch-isolation-between-frames
  (testing "synchronous dispatch from :tenant-a handler stays in :tenant-a; :tenant-b is untouched"
    (seed-frames!)
    (rf/reg-event :rf-l5q3/fan
                     (fn [_ [_ payload]]
                       (rf/dispatch [:rf-l5q3/leaf payload])
                       {}))
    (rf/reg-event :rf-l5q3/leaf
                     (fn [{:keys [db]} [_ payload]]
                       {:db (update db :received (fnil conj []) payload)}))
    (rf/dispatch-sync [:rf-l5q3/fan :a-payload] {:frame :rf-l5q3/tenant-a})
    (rf/dispatch-sync [:rf-l5q3/fan :b-payload] {:frame :rf-l5q3/tenant-b})
    (is (= [[:a-payload] [:b-payload] true]
           [(received :rf-l5q3/tenant-a) (received :rf-l5q3/tenant-b) (empty? (received :rf/default))])
        "each tenant sees only its own payload, and :rf/default sees nothing")))

;; ---- 2. setTimeout-deferred direct rf/dispatch ----------------------------
;;
;; The setTimeout callback runs on a fresh stack after `process-event!`'s
;; binding has popped, and EP-0002 puts no `:rf/default` floor beneath it.

(deftest direct-dispatch-from-set-timeout-raises-no-frame-context
  (testing "raw rf/dispatch from a setTimeout callback escapes *current-frame* — EP-0002 fails loudly"
    ;; The throw is caught so the timer callback does not crash the host.
    (async done
      (seed-frames!)
      (let [raised (atom nil)]
        (rf/reg-event :rf-l5q3/defer-raw
                         (fn [_ _]
                           (js/setTimeout
                             (fn []
                               (try (rf/dispatch [:rf-l5q3/landed-raw])
                                    (catch :default e
                                      (reset! raised (:rf.error/id (ex-data e))))))
                             0)
                           {}))
        (rf/reg-event :rf-l5q3/landed-raw
                         (fn [{:keys [db]} _]
                           {:db (update db :received (fnil conj []) :landed-raw)}))
        (rf/dispatch-sync [:rf-l5q3/defer-raw] {:frame :rf-l5q3/tenant-a})
        ;; Wait two macrotasks: one for the setTimeout(0), one for the
        ;; router's next-tick drain (had the dispatch enqueued).
        (js/setTimeout
          (fn []
            (js/setTimeout
              (fn []
                (is (= [:rf.error/no-frame-context true true]
                       [@raised (empty? (received :rf-l5q3/tenant-a)) (empty? (received :rf/default))])
                    "raw async dispatch raised :rf.error/no-frame-context, never enqueued, and fell through nowhere")
                (done))
              10))
          10)))))

;; ---- 3. :fx [[:dispatch ...]] — the canonical pattern ----------------------
;;
;; The fx-walker threads `{:frame frame-id}` through to the :dispatch fx.

(deftest fx-dispatch-from-handler-routes-to-handlers-frame
  (testing ":fx [[:dispatch ...]] routes to the handler's frame — canonical pattern"
    (seed-frames!)
    (rf/reg-event :rf-l5q3/parent-fx
                     (fn [_ _]
                       {:fx [[:dispatch [:rf-l5q3/landed-fx]]]}))
    (rf/reg-event :rf-l5q3/landed-fx
                     (fn [{:keys [db]} _]
                       {:db (update db :received (fnil conj []) :landed-fx)}))
    (rf/dispatch-sync [:rf-l5q3/parent-fx] {:frame :rf-l5q3/tenant-a})
    (is (= [[:landed-fx] true] [(received :rf-l5q3/tenant-a) (empty? (received :rf/default))])
        ":fx [[:dispatch ...]] threads the frame through fx/do-fx — lands on :tenant-a only")))

;; ---- 4a. :dispatch-later — async with frame capture -----------------------
;;
;; `:dispatch-later` captures `frame-id` in the timer's closure.

(deftest dispatch-later-survives-the-timer
  (testing ":dispatch-later threads :frame through the closure — survives the async escape"
    (async done
      (seed-frames!)
      (rf/reg-event :rf-l5q3/parent-later
                       (fn [_ _]
                         {:fx [[:dispatch-later
                                {:ms    0
                                 :event [:rf-l5q3/landed-later]}]]}))
      (rf/reg-event :rf-l5q3/landed-later
                       (fn [{:keys [db]} _]
                         {:db (update db :received (fnil conj []) :landed-later)}))
      (rf/dispatch-sync [:rf-l5q3/parent-later] {:frame :rf-l5q3/tenant-a})
      ;; Two 50ms macrotasks: well above the setTimeout(0) floor plus the
      ;; next-tick drain it schedules.
      (js/setTimeout
        (fn []
          (js/setTimeout
            (fn []
              (is (= [[:landed-later] true] [(received :rf-l5q3/tenant-a) (empty? (received :rf/default))])
                  ":dispatch-later landed on :tenant-a only, though the timer fired after the binding popped")
              (done))
            50))
        50))))

;; ---- 4b. capture-frame :dispatch — async with explicit capture -------------
;;
;; `(:dispatch (rf/capture-frame))` returns a dispatch op locked to the frame
;; current at creation, for plain-fn callers that don't speak re-frame fx.

(deftest dispatcher-survives-set-timeout
  (testing "(:dispatch (rf/capture-frame)) captures the in-flight frame; the captured fn is safe to call from setTimeout"
    (async done
      (seed-frames!)
      (rf/reg-event :rf-l5q3/parent-bound
                       (fn [_ _]
                         (let [d (:dispatch (rf/capture-frame))]
                           (js/setTimeout
                             (fn [] (d [:rf-l5q3/landed-bound]))
                             0))
                         {}))
      (rf/reg-event :rf-l5q3/landed-bound
                       (fn [{:keys [db]} _]
                         {:db (update db :received (fnil conj []) :landed-bound)}))
      (rf/dispatch-sync [:rf-l5q3/parent-bound] {:frame :rf-l5q3/tenant-a})
      (js/setTimeout
        (fn []
          (js/setTimeout
            (fn []
              (is (= [[:landed-bound] true] [(received :rf-l5q3/tenant-a) (empty? (received :rf/default))])
                  "(:dispatch (rf/capture-frame)) captured :tenant-a at call time; the setTimeout callback dispatches there only")
              (done))
            10))
        10))))
