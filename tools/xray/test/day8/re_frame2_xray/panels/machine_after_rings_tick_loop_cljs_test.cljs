(ns day8.re-frame2-xray.panels.machine-after-rings-tick-loop-cljs-test
  "The off-render `tick-loop!` that drives the countdown rings' sweep.

  It runs as a rAF/next-tick callback after any `with-frame` scope has
  unwound, so its reads must carry the frame `kick-tick!` captured: an
  ambient `rf/subscribe` would throw, the loop's `catch` would swallow it,
  and the ring would paint once and never sweep. These rows call the
  private `tick-loop!` outside `rf/with-frame` to reproduce that, and await
  its real async `:rf.xray/timer-tick` dispatch with `poll-until`."
  (:require [cljs.test :refer-macros [deftest is use-fixtures async]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]
            [day8.re-frame2-xray.panels.machine-after-rings :as after-rings]
            [day8.re-frame2-xray.panels.machine-after-rings-helpers
             :as rings-h]))

;; The rAF loop is not cancelled by the core runtime reset, so
;; `stop-tick!` brackets every test.
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

(deftest tick-loop-runs-off-render-without-ambient-frame-context
  (async done
    (rf/with-frame :rf/xray
      (override-machines!    [:auth/login])
      (override-definitions! {:auth/login fixture-definition})
      (push-scheduled! 1000 :auth/login :idle 5000 0)
      (after-rings/kick-tick! :rf/xray))
    (is (nil? (:rings/now-ms (rf.frame/frame-app-db-value :rf/xray)))
        "nothing has bumped now-ms yet")
    ;; Outside `rf/with-frame` — the off-render condition under test.
    (#'day8.re-frame2-xray.panels.machine-after-rings/tick-loop!)
    (-> (rf.test-support/poll-until
          #(pos? (or (:rings/now-ms (rf.frame/frame-app-db-value :rf/xray)) 0))
          {:label "tick-loop! dispatch drained onto :rf/xray"})
        (.catch (fn [e]
                  (is false
                      (str "tick-loop! never dispatched :rf.xray/timer-tick "
                           "onto :rf/xray — " (.-message e)))
                  nil))
        (.then (fn [_] (done))))))

;; ---- the clock must OUTLIVE the last armed timer -------------------------
;;
;; A `:cancelled` ring has a retention window, so a DEADLINE, and both
;; gates — `tick-loop!` re-scheduling and `overlay-tree` kicking — read
;; `needs-ticking?`. If the clock stopped with the last armed timer, an idle
;; live chart would freeze `:rings/now-ms`, `prune-timers` would never
;; evict, and the crossed ring would stay on screen for ever. These rows
;; stub the wall clock (`js/Date.now`, which `tick-loop!` reads) and the rAF
;; queue and let the loop decide for itself whether to re-arm.

(defn- push-cancelled!
  "`fold-timer-events` reads `:closed-at` off the event's `:time`, so `id`
  is the ring's deadline anchor."
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
  for `machine-id`, so the rings sub folds for THAT machine."
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
  clock `step` ms per frame, until the loop stops re-arming or `cap`
  frames have run. Returns how many frames ran: a loop that stops on its
  first iteration runs ONE, and one that never stops runs `cap`."
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
      (set! js/requestAnimationFrame (fn [f] (reset! pending f) 0))
      (set! (.-now js/Date) (fn [] @clock))
      (rf/with-frame :rf/xray
        (override-machines!    [:auth/login])
        (override-definitions! {:auth/login fixture-definition})
        (focus-machine!        :auth/login)
        (push-scheduled! 1000 :auth/login :idle 5000 0)
        (push-cancelled! closed-at :auth/login :idle 0)
        (rf/dispatch-sync [:rf.xray/set-scrubber-position :present])
        (is (= [[:cancelled closed-at]]
               (mapv (juxt :status :closed-at)
                     @(rf/subscribe [:rf.xray/active-timers-for-focused-machine])))
            "NOT VACUOUS: one crossed ring and no armed timer")
        (is (nil? (:rings/now-ms (rf.frame/frame-app-db-value :rf/xray)))
            "every now-ms read below is one the LOOP published")
        (after-rings/kick-tick! :rf/xray))
      (let [frames (try
                     (drive-frames! pending clock frame-step-ms 500)
                     (finally
                       (set! js/requestAnimationFrame
                             (if had? orig-raf js/undefined))
                       (set! (.-now js/Date) orig-now)))]
        ;; TERMINATION
        (is (false? (:running? @ts))
            "the loop STOPPED: kept alive for ever it would trade a stuck ring
             for a spinning CPU")
        (is (<= frames max-frames)
            (str "it stopped within the retention window (+1 boundary frame) — ran "
                 frames " frames, bound " max-frames))
        ;; EXPIRY
        (is (> frames 1)
            (str "the clock kept running past the cancellation; exactly 1 means "
                 "the loop saw no :armed timer and stopped at once. Ran " frames))
        (-> (rf.test-support/poll-until
              #(> (or (:rings/now-ms (rf.frame/frame-app-db-value :rf/xray)) 0)
                  deadline)
              {:label "the loop published a clock past the retention deadline"})
            (.then
              (fn [_]
                (let [published (:rings/now-ms
                                  (rf.frame/frame-app-db-value :rf/xray))]
                  (rf/with-frame :rf/xray
                    (let [timers @(rf/subscribe
                                    [:rf.xray/active-timers-for-focused-machine])]
                      (is (= [] (rings-h/prune-timers timers published))
                          "at the clock the LOOP published, the ring is evicted"))))))
            (.catch
              (fn [e]
                (is false
                    (str ":rings/now-ms never reached the ring's deadline ("
                         deadline ") — the clock stopped at "
                         (:rings/now-ms (rf.frame/frame-app-db-value :rf/xray))
                         ". " (.-message e)))
                nil))
            (.then (fn [_]
                     (after-rings/stop-tick!)
                     (swap! ts assoc :mounted? false)
                     (done))))))))

(deftest overlay-kick-arms-the-clock-for-a-cancelled-ring-with-no-armed-timer
  ;; The OTHER gate on the same predicate: a render that finds only a
  ;; crossed ring must still arm the single per-chart clock.
  (let [had?     (exists? js/requestAnimationFrame)
        orig-raf (when had? js/requestAnimationFrame)
        pending  (atom nil)
        ts       @#'day8.re-frame2-xray.panels.machine-after-rings/tick-state]
    (set! js/requestAnimationFrame (fn [f] (reset! pending f) 0))
    (after-rings/stop-tick!)
    (try
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
              "one crossed ring, no armed timer")
          (after-rings/overlay-tree
            {:timers   timers
             :live-now closed-at
             :scrub    :present
             :frame    :rf/xray})))
      (is (true? (:running? @ts))
          "rendering over a ring that still has a deadline arms the clock")
      (finally
        (set! js/requestAnimationFrame (if had? orig-raf js/undefined))
        (after-rings/stop-tick!)
        (swap! ts assoc :mounted? false)))))
