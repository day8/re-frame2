(ns re-frame.dispatch-frame-capture-cljs-test
  "Regression tests for *current-frame* propagation across direct
  rf/dispatch calls — rf2-l5q3.

  Discovered during rf2-yf97 (websocket example): a handler scoped to
  frame :A doing `(js/setTimeout #(rf/dispatch [:foo]) 0)` produced a
  dispatch that landed on :rf/default, not :A. The workaround the
  example adopted was `:fx [[:dispatch ...]]` — the :dispatch fx in
  re-frame.fx explicitly threads `{:frame frame-id}` so it survives
  any async tier.

  This ns nails down the runtime contract for the four patterns a
  multi-frame app might reach for:

    1. Synchronous direct `rf/dispatch` from inside the handler body.
    2. `js/setTimeout` deferred direct `rf/dispatch` from inside the
       handler body (the rf2-yf97 case).
    3. `:fx [[:dispatch ...]]` from a `reg-event` handler returning an
       `:fx` effects map (the documented workaround).
    4. `:fx [[:dispatch-later {:ms 0 :event ...}]]` and the
       `(:dispatch (rf/capture-frame))` capture-at-creation affordance.

  Per rf2-l5q3 the fix routes through `process-event!`: the drain
  loop now binds `frame/*current-frame*` to the envelope's `:frame`
  for the duration of the handler chain, so a synchronous
  `rf/dispatch` from inside the handler body sees the in-flight
  event's frame. Async escapes (setTimeout / Promise.then /
  requestAnimationFrame) still need an explicit-capture affordance —
  `:fx [[:dispatch ...]]` (canonical), `:dispatch-later`, or
  `(:dispatch (rf/capture-frame))`. See spec/002-Frames.md §Dispatch and the
  dynamic-binding tier."
  (:require [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            ;; rf2-qwm0a — listener surface lives in
            ;; `re-frame.trace.tooling` (production-DCE split).
            [re-frame.trace.tooling :as trace-tooling]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.test-support :as test-support]))

;; Per cljs.test: async tests require fixtures supplied as a map
;; (fn-form fixtures don't suspend across the async body, so the
;; finally clause runs before the async work completes). Wrap the
;; snapshot/restore pattern as `{:before :after}`. Mirrors the
;; tools/story CLJS async-test idiom.

(def ^:private registrar-snapshot (atom nil))

(defn- before! []
  (reset! registrar-snapshot (test-support/snapshot-registrar))
  (reset! frame/frames {})
  (substrate-adapter/dispose-adapter!)
  (trace-tooling/clear-listeners!)
  (substrate-adapter/install-adapter! reagent-adapter/adapter)
  (frame/ensure-default-frame!))

(defn- after! []
  (when-let [snap @registrar-snapshot]
    (test-support/restore-registrar! snap)
    (reset! registrar-snapshot nil))
  (reset! frame/frames {}))

(use-fixtures :each {:before before! :after after!})

;; ---- helpers --------------------------------------------------------------

(defn- seed-frames!
  "Register two non-default frames and capture per-frame markers in
  app-db so a test assertion can identify which frame served a
  dispatch."
  []
  (rf/reg-frame :rf-l5q3/tenant-a {:doc "tenant-a frame"})
  (rf/reg-frame :rf-l5q3/tenant-b {:doc "tenant-b frame"})
  (rf/reg-event :rf-l5q3/seed
                   (fn [{:keys [db]} [_ marker]] {:db {:marker marker :received []}}))
  (rf/dispatch-sync [:rf-l5q3/seed :rf/default] {:frame :rf/default})
  (rf/dispatch-sync [:rf-l5q3/seed :tenant-a] {:frame :rf-l5q3/tenant-a})
  (rf/dispatch-sync [:rf-l5q3/seed :tenant-b] {:frame :rf-l5q3/tenant-b}))

(defn- received
  "Return the :received vector for a frame's current app-db."
  [frame-id]
  (let [db (frame/frame-app-db-value frame-id)]
    (:received db)))

;; ---- 1. Synchronous direct rf/dispatch ------------------------------------
;;
;; A reg-event handler running on :tenant-a calls (rf/dispatch [:landed])
;; in its body and returns no fx. The expectation: the queued :landed
;; event lands on :tenant-a (the in-flight handler's frame), not
;; :rf/default.
;;
;; The fix for rf2-l5q3 routes through `process-event!` — the drain
;; loop binds `frame/*current-frame*` to the envelope's :frame for the
;; duration of the chain, so a synchronous rf/dispatch from inside the
;; handler body picks up the right frame.

(deftest sync-dispatch-from-handler-body-routes-to-handlers-frame
  (testing "rf/dispatch called synchronously from inside a handler routes to that handler's frame"
    (seed-frames!)
    (rf/reg-event :rf-l5q3/parent
                     (fn [_ _]
                       (rf/dispatch [:rf-l5q3/landed])
                       {}))
    (rf/reg-event :rf-l5q3/landed
                     (fn [{:keys [db]} _]
                       {:db (update db :received (fnil conj []) :landed-sync)}))
    ;; Fire the parent under :tenant-a; the synchronous dispatch inside
    ;; its body MUST route to :tenant-a too. dispatch-sync drains the
    ;; full cascade before returning.
    (rf/dispatch-sync [:rf-l5q3/parent] {:frame :rf-l5q3/tenant-a})
    (is (= [:landed-sync] (received :rf-l5q3/tenant-a))
        "the :landed event must land on :tenant-a, not :rf/default")
    (is (empty? (received :rf/default))
        ":rf/default must NOT have received :landed — the dispatch was scoped to :tenant-a")))

;; ---- 2. setTimeout-deferred direct rf/dispatch ----------------------------
;;
;; This is the rf2-yf97 scenario. A handler defers a dispatch via
;; setTimeout. The setTimeout callback runs on a fresh JS stack — the
;; dynamic binding established by `process-event!` has long since
;; been popped. Without an explicit capture the dispatch falls
;; through to `:rf/default`.
;;
;; This test documents the inherent dynamic-scope limit and points
;; users at the three explicit-capture affordances (`:fx [[:dispatch
;; ...]]` / `:dispatch-later` / `(:dispatch (rf/capture-frame))`) covered in the
;; deftests below.

(deftest direct-dispatch-from-set-timeout-raises-no-frame-context
  (testing "raw rf/dispatch from a setTimeout callback escapes *current-frame* — EP-0002 fails loudly"
    ;; EP-0002 (rf2-9wa0lf) REFRAMES the rf2-yf97 gotcha: a raw
    ;; `rf/dispatch` from a setTimeout callback no longer SILENTLY falls
    ;; through to `:rf/default` (there is no `:rf/default` floor). The
    ;; dead dynamic binding means no carried frame stamp, so the dispatch
    ;; now FAILS LOUDLY with `:rf.error/no-frame-context`. The throw is
    ;; caught here so the timer callback does not crash the host; the fix
    ;; is to capture a `capture-frame` / use `:dispatch-later` (the
    ;; deftests below). (Touched by the router bead to retire the old
    ;; fall-through expectation + stop the uncaught-async crash; the
    ;; adapter root/view migration bead — rf2-69r7ui — owns this surface
    ;; more broadly.)
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
                (is (= :rf.error/no-frame-context @raised)
                    "raw async dispatch raised :rf.error/no-frame-context (no :rf/default floor)")
                (is (empty? (received :rf-l5q3/tenant-a))
                    ":tenant-a sees nothing — the dispatch never enqueued")
                (is (empty? (received :rf/default))
                    ":rf/default sees nothing — there is no fall-through target")
                (done))
              10))
          10)))))

;; ---- 3. :fx [[:dispatch ...]] from a setTimeout — workaround --------------
;;
;; The fx-walker in re-frame.fx threads `{:frame frame-id}` through to
;; the :dispatch fx, so a setTimeout-scheduled `:fx` cannot deliver
;; the dispatch to the wrong frame even if the dynamic var has
;; escaped. This is the canonical rf2-yf97 workaround.
;;
;; Note: re-frame.fx walks :fx during `process-event!`, NOT inside the
;; setTimeout. So the right pattern is `:fx [[:dispatch-later ...]]`
;; or a handler that returns `:fx [[:dispatch ...]]` *immediately*.
;; If the handler manually defers via setTimeout and returns `:fx`,
;; the :fx walk happens before the timer fires — that's just a
;; same-tick dispatch. The case we care about is `:dispatch-later`,
;; tested next.

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
    (is (= [:landed-fx] (received :rf-l5q3/tenant-a))
        ":fx [[:dispatch ...]] threads the frame through fx/do-fx — lands on :tenant-a")
    (is (empty? (received :rf/default))
        ":rf/default sees nothing")))

;; ---- 4a. :dispatch-later — async with frame capture -----------------------
;;
;; `:dispatch-later` in re-frame.fx schedules the dispatch via
;; interop/set-timeout! and captures `frame-id` in the closure (per
;; fx.cljc), so the deferred dispatch carries the right frame
;; regardless of when the timer fires. This is the canonical
;; async-frame-safe pattern.

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
      ;; Wait long enough for the setTimeout(0) and the resulting
      ;; next-tick drain (goog.async.nextTick). Two 50ms macrotasks
      ;; are belt-and-braces — node's setTimeout(0) has a ~1-4ms
      ;; floor under default settings, the chained drain another
      ;; macrotask tick. 100ms is well above both.
      (js/setTimeout
        (fn []
          (js/setTimeout
            (fn []
              (is (= [:landed-later] (received :rf-l5q3/tenant-a))
                  ":dispatch-later landed on :tenant-a even though the timer fired after the binding popped")
              (is (empty? (received :rf/default))
                  ":rf/default sees nothing")
              (done))
            50))
        50))))

;; ---- 4b. capture-frame :dispatch — async with explicit capture -------------
;;
;; `(:dispatch (rf/capture-frame))` captures the current frame at creation
;; time and returns a dispatch op locked to that frame. This is the same
;; shape `:fx [[:dispatch ...]]` uses internally — exposed for plain-fn
;; callers (test setup, REPL, async libraries that don't speak re-frame
;; fx). Per rf2-kkut0 `capture-frame` is the keystone affordance replacing
;; the removed `dispatcher` / `subscriber` nouns.

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
              (is (= [:landed-bound] (received :rf-l5q3/tenant-a))
                  "(:dispatch (rf/capture-frame)) captured :tenant-a at call time; the setTimeout callback dispatches there")
              (is (empty? (received :rf/default))
                  ":rf/default sees nothing")
              (done))
            10))
        10))))

;; ---- 5. cross-frame isolation hardness check ------------------------------
;;
;; Sanity: when a handler on :tenant-a synchronously dispatches and
;; *another* handler is also running on :tenant-b in a separate
;; dispatch-sync, neither cascade leaks into the other's frame. The
;; rf2-l5q3 fix binds *current-frame* per `process-event!`, so the
;; binding is established and torn down PER EVENT — sibling frames
;; do not see each other's bindings.

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
    (is (= [:a-payload] (received :rf-l5q3/tenant-a))
        ":tenant-a only sees its own :a-payload")
    (is (= [:b-payload] (received :rf-l5q3/tenant-b))
        ":tenant-b only sees its own :b-payload")
    (is (empty? (received :rf/default))
        ":rf/default sees nothing — neither cascade leaked")))
