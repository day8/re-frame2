(ns day8.re-frame2-xray.panels.machine-after-rings-tick-loop-cljs-test
  "Off-render `tick-loop!` regression coverage (rf2-nms77h).

  Sibling to `machine_after_rings_cljs_test.cljs`. Lives in a SEPARATE ns
  so the `use-fixtures` map shape `cljs.test/async` requires does not
  conflict with the fn-form `rf.test-support/make-reset-runtime-fixture`
  the existing after-rings tests use — mirrors the split
  `settings/popup_dispatch_routing_cljs_test.cljs` uses for the same
  reason.

  ## The bug this test defends against

  `tick-loop!` is the rAF/next-tick callback that drives the countdown
  rings' sweep. It runs OFF-render — by the time it actually fires, any
  `with-frame` / Provider scope active when `kick-tick!` armed it has
  already unwound (both rAF and `next-tick` resume on a LATER TASK —
  neither is a microtask and neither runs inline — so the callback
  always fires after the synchronous call that scheduled it returns). Before the fix its two internal reads (the active-timers sub
  + the scrubber sub) used the ambient 1-arity `rf/subscribe`, which has
  NO `:rf/default` floor and raises `:rf.error/no-frame-context` with no
  scope established — the FIRST expression in the loop's body, so it
  threw before `:rf.xray/timer-tick` was ever dispatched. The loop's
  defensive `catch` swallowed that error every iteration and set
  `:running? false`, so `:rings/now-ms` never advanced and the ring
  painted once but never swept — silently, no console error. The fix
  reads via the explicit-frame `(rf/subscribe query-v {:frame frame})`
  opts form, targeting the frame captured in `tick-state` at
  `kick-tick!` time.

  ## Why this test invokes the private `tick-loop!` directly

  Calling it outside `rf/with-frame` reproduces the EXACT off-render
  condition deterministically (no waiting on the real rAF/next-tick
  queue to invoke the callback itself). Its OWN `:rf.xray/timer-tick`
  dispatch is still the real async `rf/dispatch` (not `dispatch-sync`),
  so the test awaits the router drain via `rf.test-support/poll-until`
  under `cljs.test/async`, matching this codebase's standard pattern for
  asserting on a queued (non-sync) dispatch."
  (:require [cljs.test :refer-macros [deftest is use-fixtures async]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]
            [day8.re-frame2-xray.panels.machine-after-rings :as after-rings]
            ;; rf2-q9x6h — the retention window is READ from the helper
            ;; rather than retyped, so the rows below cannot drift from the
            ;; window they pin.
            [day8.re-frame2-xray.panels.machine-after-rings-helpers
             :as rings-h]))

;; `make-xray-runtime-fixture` (rf2-vj80u8) composes core
;; `make-reset-runtime-fixture` (snapshot/restore + frames-reset + adapter
;; dispose/install) with Xray's own reset tier. `:tier :all` folds the sentinel +
;; trace-collector rings reset; `:async? true` is the map-form cljs.test/async
;; requires; `:post-reset` re-registers Xray's :rf.xray/* handlers + the panel
;; test-override seams + the :rf/xray frame (rolled back with the per-test
;; registrar snapshot). A composed fixture brackets `after-rings/stop-tick!`
;; before AND after each test — the off-render `tick-loop!` is an rAF loop the
;; core runtime reset does not cancel, so a stale tick from one test must not
;; survive into the next.
(use-fixtures :each
  (xray-test-support/make-xray-runtime-fixture
    {:tier       :all
     :async?     true
     :post-reset (fn []
                   (registry/register-xray-handlers!)
                   (xray-test-support/install-test-overrides!)
                   (rf/make-frame {:id :rf/xray}))})
  {:before (fn [] (after-rings/stop-tick!))
   :after  (fn [] (after-rings/stop-tick!))})

;; ---- fixture data (mirrors machine_after_rings_cljs_test.cljs) --------

(def ^:private fixture-definition
  {:initial :idle
   :states  {:idle    {:on    {:start :authing}
                       :after {5000 :timeout}}
             :authing {:on {:ok :done}}
             :timeout {:on {:retry :idle}}
             :done    {:final? true}}})

(defn- override-machines! [machines]
  (rf/dispatch-sync
    [:rf.xray/set-registered-machines-override-for-test machines]))

(defn- override-definitions! [definitions]
  (rf/dispatch-sync
    [:rf.xray/set-machine-definitions-override-for-test definitions]))

(defn- push-scheduled!
  [id machine-id state delay epoch]
  (trace-collector/seed-trace-for-test!
    {:id id :time id
     :operation :rf.machine.timer/scheduled
     :tags {:machine-id machine-id
            :state state
            :delay delay
            :delay-source :literal
            :epoch epoch}}))

(defn- pin-now-ms! [ms]
  (rf/dispatch-sync [:rf.xray/set-now-ms-override-for-test ms]))

;; ---- rf2-nms77h — off-render tick-loop! dispatches with no ambient frame --

(deftest tick-loop-runs-off-render-without-ambient-frame-context
  (async done
    (rf/with-frame :rf/xray
      (override-machines!    [:auth/login])
      (override-definitions! {:auth/login fixture-definition})
      (push-scheduled! 1000 :auth/login :idle 5000 0)
      ;; `kick-tick!` captures `:rf/xray` as the surrounding instance
      ;; frame (mirrors the overlay reg-view's `rf/current-frame-id`
      ;; capture at render time) into `tick-state`.
      (after-rings/kick-tick! :rf/xray))
    (is (nil? (:rings/now-ms (rf.frame/frame-app-db-value :rf/xray)))
        "sanity: nothing has bumped now-ms yet")
    ;; Runs OUTSIDE `rf/with-frame` — the off-render condition the bug
    ;; hits.
    (#'day8.re-frame2-xray.panels.machine-after-rings/tick-loop!)
    (-> (rf.test-support/poll-until
          #(pos? (or (:rings/now-ms (rf.frame/frame-app-db-value :rf/xray)) 0))
          {:label "tick-loop! dispatch drained onto :rf/xray"})
        (.then (fn [_]
                 (is (pos? (:rings/now-ms (rf.frame/frame-app-db-value :rf/xray)))
                     "the loop's :rf.xray/timer-tick dispatch landed on the
                      captured :rf/xray frame — now-ms was bumped, proving
                      the two internal reads did NOT hit
                      :rf.error/no-frame-context (pre-fix this slot stayed
                      nil forever: the throw happened before dispatch, and
                      the catch swallowed it silently every iteration)")))
        (.catch (fn [e]
                  (is false
                      (str "tick-loop! never dispatched :rf.xray/timer-tick "
                           "onto :rf/xray — " (.-message e)))
                  nil))
        (.then (fn [_] (done))))))

;; ---- rf2-e64drj — the rAF loop is MOUNT-gated, not only DATA-gated --------
;;
;; Pre-fix `tick-loop!` re-scheduled itself whenever `needs-ticking?` was true
;; (an armed `:after` timer + a `:present` scrubber), regardless of whether the
;; `AfterRingsOverlay` was still mounted — so switching to another L3 tab left
;; the loop dispatching `:rf.xray/timer-tick` ~60×/s in the background,
;; outliving its panel. The overlay now owns a `:mounted?` liveness flag
;; (`kick-tick!` sets it, the `overlay-ref!` React ref callback clears it on
;; unmount); `tick-loop!` requires it before dispatching / re-scheduling.

(deftest tick-loop-mount-gate-stops-loop-after-unmount
  ;; Stub `js/requestAnimationFrame` to a no-op capture so a reschedule is a
  ;; deterministic drop (no async next-tick racing the synchronous
  ;; assertions); we assert on `tick-state`, not the real rAF queue.
  (let [had? (exists? js/requestAnimationFrame)
        orig (when had? js/requestAnimationFrame)
        ts   @#'day8.re-frame2-xray.panels.machine-after-rings/tick-state]
    (set! js/requestAnimationFrame (fn [_f] 0))
    (try
      (rf/with-frame :rf/xray
        (override-machines!    [:auth/login])
        (override-definitions! {:auth/login fixture-definition})
        (pin-now-ms! 2000)                          ; now (2000) < fires-at (6000) ⇒ armed
        (push-scheduled! 1000 :auth/login :idle 5000 0)
        (rf/dispatch-sync [:rf.xray/set-scrubber-position :present])
        ;; the mounted overlay's render arms the loop.
        (after-rings/kick-tick! :rf/xray))
      (is (true? (:running? @ts)) "sanity: kick-tick! armed the loop")
      (is (true? (:mounted? @ts)) "sanity: kick-tick! stamped the overlay live")
      ;; `needs-ticking?` is TRUE here (armed timer + :present scrubber), so
      ;; PRE-FIX `tick-loop!` would re-arm regardless of mount. Simulate the
      ;; overlay UNMOUNTING — React fires the ref with nil.
      (#'day8.re-frame2-xray.panels.machine-after-rings/overlay-ref! nil)
      (is (false? (:mounted? @ts)) "the ref-callback recorded the unmount")
      (#'day8.re-frame2-xray.panels.machine-after-rings/tick-loop!)
      (is (false? (:running? @ts))
          "rf2-e64drj: unmounting AfterRingsOverlay stops the rAF loop within
           one frame even though the :after timer is still armed + the scrubber
           sits at :present (pre-fix the data-gated loop kept re-arming)")
      (is (nil? (:rings/now-ms (rf.frame/frame-app-db-value :rf/xray)))
          "the unmounted iteration dispatched NO :rf.xray/timer-tick")
      ;; A remount (the overlay's next render) re-arms the loop.
      (rf/with-frame :rf/xray (after-rings/kick-tick! :rf/xray))
      (is (true? (:mounted? @ts)) "remount re-stamps the overlay live")
      (is (true? (:running? @ts)) "remount re-arms the loop via kick-tick!")
      (finally
        (set! js/requestAnimationFrame (if had? orig js/undefined))
        (after-rings/stop-tick!)
        (swap! ts assoc :mounted? false)))))

;; ---- rf2-q9x6h — the clock must OUTLIVE the last armed timer -------------
;;
;; rf2-y8doi.23 gave a `:cancelled` ring a retention window measured from its
;; `:closed-at` (`rings-h/cancelled-retention-ms`), so a crossed ring now has a
;; DEADLINE — and a deadline needs a clock to reach it. `needs-ticking?` went
;; on answering false the moment the last `:armed` timer went away, and BOTH
;; gates read it: `tick-loop!` stopped re-scheduling and `overlay-tree` declined
;; to kick. So in an otherwise idle LIVE chart, cancelling the LAST armed timer
;; froze `:rings/now-ms` at that instant, the clock never reached `:closed-at`
;; + the window, `prune-timers` never evicted, and the grey crossed ring stayed
;; on screen FOR EVER.
;;
;; ## Why these rows drive the loop rather than pinning the clock
;;
;; `machine_after_rings_cljs_test`'s
;; `overlay-evicts-a-cancelled-ring-once-its-retention-window-passes` pins the
;; `:rf.xray/set-now-ms-override-for-test` slot and calls `overlay-tree`
;; directly — it SUPPLIES the wakeup whose absence IS the defect, which is why
;; it stayed green across it. These rows supply no clock at all. They stub the
;; WALL CLOCK (`js/Date.now` — the source `tick-loop!` itself reads) and the rAF
;; queue, then let the loop decide for itself whether to re-arm; the only
;; `:rings/now-ms` value they ever read is the one the LOOP published.
;;
;; Two properties, which fail for DIFFERENT reasons — neither alone is enough:
;;
;;   1. EXPIRY      — with the last armed timer cancelled and no further host
;;                    events, the ring is gone once the deadline passes. A
;;                    clock that never stopped would also satisfy this.
;;   2. TERMINATION — the clock STOPS once the last ring expires, and
;;                    retrospective mode stays frozen. The BROKEN code
;;                    satisfies this on its own.

(defn- push-cancelled!
  "`:rf.machine.timer/cancelled` closing the record `push-scheduled!`
  armed. `fold-timer-events` reads `:closed-at` off the event's `:time`,
  so `id` IS the ring's deadline anchor."
  [id machine-id state epoch]
  (trace-collector/seed-trace-for-test!
    {:id id :time id
     :operation :rf.machine.timer/cancelled
     :tags {:machine-id machine-id
            :state state
            :epoch epoch
            :reason :on-exit}}))

(defn- focus-machine!
  "Seed a one-epoch history whose cascade carries a `:rf.machine/transition`
  for `machine-id`, so the rings sub folds for THAT machine (rf2-y8doi.23 —
  the sub reads the focused record, not a picker). Same producer-shaped
  fixture `machine_after_rings_cljs_test` uses."
  [machine-id]
  (rf/dispatch-sync
    [:rf.xray/set-epoch-history-for-test
     [{:epoch-id 1
       :trace-events
       [{:id 1 :time 10 :operation :rf.machine/transition
         :tags {:machine-id           machine-id
                :before               {:state :idle :data {}}
                :after                {:state :authing :data {}}
                :event                [:auth/submit]
                :rf.trace/dispatch-id "d-1"}}]}]]))

(defn- drive-frames!
  "Run the rAF callbacks the loop schedules, advancing the stubbed wall
  clock `step` ms per frame, until the loop STOPS re-arming or `cap`
  frames have run. Returns how many frames actually ran — which is itself
  the discriminator: the unfixed loop runs exactly ONE and a loop that
  never stops runs `cap`."
  [pending clock step cap]
  (loop [n 0]
    (if-let [f (and (< n cap) @pending)]
      (do (reset! pending nil)
          (swap! clock + step)
          (f)
          (recur (inc n)))
      n)))

(def ^:private closed-at
  "`:time` of the seeded `/cancelled` trace ⇒ the ring's `:closed-at`."
  2000)

(def ^:private frame-step-ms 100)

(deftest tick-loop-keeps-the-clock-alive-until-a-cancelled-ring-expires
  (async done
    (let [had?     (exists? js/requestAnimationFrame)
          orig-raf (when had? js/requestAnimationFrame)
          orig-now (.-now js/Date)
          clock    (atom closed-at)
          pending  (atom nil)
          ts       @#'day8.re-frame2-xray.panels.machine-after-rings/tick-state
          deadline (+ closed-at rings-h/cancelled-retention-ms)
          ;; the frame that first sees the ring expired, +1 for the frame
          ;; that runs exactly ON the boundary (still on screen there).
          max-frames (+ 2 (quot rings-h/cancelled-retention-ms frame-step-ms))]
      ;; Capture the scheduled callback instead of queueing a real frame, and
      ;; read the wall clock from `clock`, so the drive below is deterministic.
      (set! js/requestAnimationFrame (fn [f] (reset! pending f) 0))
      (set! (.-now js/Date) (fn [] @clock))
      (rf/with-frame :rf/xray
        (override-machines!    [:auth/login])
        (override-definitions! {:auth/login fixture-definition})
        (focus-machine!        :auth/login)
        (push-scheduled! 1000 :auth/login :idle 5000 0)
        (push-cancelled! closed-at :auth/login :idle 0)
        (rf/dispatch-sync [:rf.xray/set-scrubber-position :present])
        ;; NOT VACUOUS — if the fixture folded no ring, or an :armed one, both
        ;; properties below would hold trivially against any implementation.
        (let [timers @(rf/subscribe
                        [:rf.xray/active-timers-for-focused-machine])]
          (is (= 1 (count timers))
              "sanity: the fixture folds exactly one ring")
          (is (= :cancelled (:status (first timers)))
              "sanity: and it is the CANCELLED ring — no armed timer is left,
               which is the precondition the whole defect turns on")
          (is (= closed-at (:closed-at (first timers)))
              "sanity: carrying the deadline anchor prune-timers ages it by"))
        (is (nil? (:rings/now-ms (rf.frame/frame-app-db-value :rf/xray)))
            "sanity: nothing has bumped the clock yet, so every now-ms read
             below is one the LOOP published")
        ;; the mounted overlay's render arms the loop.
        (after-rings/kick-tick! :rf/xray))
      (let [frames (try
                     (drive-frames! pending clock frame-step-ms 500)
                     (finally
                       ;; the stubs are only needed for the synchronous drive.
                       (set! js/requestAnimationFrame
                             (if had? orig-raf js/undefined))
                       (set! (.-now js/Date) orig-now)))]
        ;; ---- PROPERTY 2 — TERMINATION -----------------------------------
        (is (false? (:running? @ts))
            "the loop STOPPED: a fix that keeps the rAF loop alive for ever
             would trade a stuck ring for a spinning CPU")
        (is (nil? @pending)
            "and scheduled no further frame")
        (is (<= frames max-frames)
            (str "it stopped WITHIN the retention window (+1 boundary frame),
                  not merely eventually — ran " frames " frames, bound "
                 max-frames))
        ;; ---- PROPERTY 1 — EXPIRY ----------------------------------------
        (is (> frames 1)
            (str "rf2-q9x6h: the clock kept running past the cancellation. "
                 "PRE-FIX this is exactly 1 — needs-ticking? saw no :armed "
                 "timer, the loop stopped on its first iteration and the "
                 "crossed ring never reached its deadline. Ran " frames))
        (-> (rf.test-support/poll-until
              #(> (or (:rings/now-ms (rf.frame/frame-app-db-value :rf/xray)) 0)
                  deadline)
              {:label "the loop published a clock past the retention deadline"})
            (.then
              (fn [_]
                (let [published (:rings/now-ms
                                  (rf.frame/frame-app-db-value :rf/xray))]
                  (is (> published deadline)
                      "the LOOP drove :rings/now-ms past :closed-at + the
                       retention window with no host event and no test-supplied
                       clock")
                  (rf/with-frame :rf/xray
                    (let [timers @(rf/subscribe
                                    [:rf.xray/active-timers-for-focused-machine])]
                      (is (= [] (rings-h/prune-timers timers published))
                          "and at THAT published clock the ring is evicted —
                           the eviction rf2-y8doi.23 documents is now
                           reachable in an idle live chart"))))))
            (.catch
              (fn [e]
                (is false
                    (str "rf2-q9x6h: :rings/now-ms never reached the ring's "
                         "deadline (" deadline ") — the clock stopped at "
                         (:rings/now-ms (rf.frame/frame-app-db-value :rf/xray))
                         " and the crossed ring is stuck on screen. "
                         (.-message e)))
                nil))
            (.then (fn [_]
                     (after-rings/stop-tick!)
                     (swap! ts assoc :mounted? false)
                     (done))))))))

(deftest tick-loop-stays-frozen-in-retrospective-mode-with-a-cancelled-ring
  ;; PRESERVATION, not the fix: widening `needs-ticking?` to cover a pending
  ;; cancellation must NOT reanimate the clock behind the scrubber. Retro mode
  ;; freezes EVERY ring at the scrubber's anchor, cancelled ones included.
  (let [had?     (exists? js/requestAnimationFrame)
        orig-raf (when had? js/requestAnimationFrame)
        orig-now (.-now js/Date)
        clock    (atom closed-at)
        pending  (atom nil)
        ts       @#'day8.re-frame2-xray.panels.machine-after-rings/tick-state]
    (set! js/requestAnimationFrame (fn [f] (reset! pending f) 0))
    (set! (.-now js/Date) (fn [] @clock))
    (try
      (rf/with-frame :rf/xray
        (override-machines!    [:auth/login])
        (override-definitions! {:auth/login fixture-definition})
        (focus-machine!        :auth/login)
        (push-scheduled! 1000 :auth/login :idle 5000 0)
        (push-cancelled! closed-at :auth/login :idle 0)
        ;; scrubbed BACK — not :present.
        (rf/dispatch-sync [:rf.xray/set-scrubber-position 0])
        (after-rings/kick-tick! :rf/xray))
      (let [frames (drive-frames! pending clock frame-step-ms 500)]
        (is (= 1 frames)
            "the one armed frame ran and the loop refused to re-arm")
        (is (false? (:running? @ts))
            "retrospective mode still stops the clock even though a cancelled
             ring is inside its retention window")
        (is (nil? @pending) "and scheduled no further frame"))
      (finally
        (set! js/requestAnimationFrame (if had? orig-raf js/undefined))
        (set! (.-now js/Date) orig-now)
        (after-rings/stop-tick!)
        (swap! ts assoc :mounted? false)))))

(deftest overlay-kick-arms-the-clock-for-a-cancelled-ring-with-no-armed-timer
  ;; The OTHER gate on the same predicate: `overlay-tree` kicks the single
  ;; per-chart clock only when `needs-ticking?` passes, so pre-fix a render
  ;; that found only a crossed ring armed nothing at all — the mounted path's
  ;; half of the defect. Supplying `:live-now` here does NOT bypass anything:
  ;; what is asserted is that the clock got ARMED, which no supplied timestamp
  ;; can fake.
  (let [had?     (exists? js/requestAnimationFrame)
        orig-raf (when had? js/requestAnimationFrame)
        pending  (atom nil)
        ts       @#'day8.re-frame2-xray.panels.machine-after-rings/tick-state]
    (set! js/requestAnimationFrame (fn [f] (reset! pending f) 0))
    (after-rings/stop-tick!)
    (try
      (is (false? (:running? @ts)) "sanity: no clock is running yet")
      (rf/with-frame :rf/xray
        (override-machines!    [:auth/login])
        (override-definitions! {:auth/login fixture-definition})
        (focus-machine!        :auth/login)
        (push-scheduled! 1000 :auth/login :idle 5000 0)
        (push-cancelled! closed-at :auth/login :idle 0)
        (rf/dispatch-sync [:rf.xray/set-scrubber-position :present])
        (let [timers @(rf/subscribe
                        [:rf.xray/active-timers-for-focused-machine])]
          (is (= [:cancelled] (mapv :status timers))
              "sanity: one crossed ring, no armed timer")
          (after-rings/overlay-tree
            {:timers   timers
             :live-now closed-at
             :scrub    :present
             :frame    :rf/xray})))
      (is (true? (:running? @ts))
          "rf2-q9x6h: rendering the overlay over a ring that still has a
           deadline ARMS the single per-chart clock (pre-fix it did not, so
           nothing ever aged the ring)")
      (finally
        (set! js/requestAnimationFrame (if had? orig-raf js/undefined))
        (after-rings/stop-tick!)
        (swap! ts assoc :mounted? false)))))
