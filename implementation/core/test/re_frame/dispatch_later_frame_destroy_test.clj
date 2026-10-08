(ns re-frame.dispatch-later-frame-destroy-test
  "`:dispatch-later` timers belong to their frame. Each armed handle is
  retained in `re-frame.fx/dispatch-later-timers` under its frame, a fired
  timer drops its own slot, and `destroy-frame!` cancels and drops the
  frame's slots through the `:fx/on-frame-destroyed!` late-bind hook.

  The slot is also the callback's dispatch authority: host cancellation
  cannot un-run a callback that has already started, so a callback whose slot
  cleanup removed first — during arming, or after an ordinary publish —
  dispatches nothing into the torn-down frame. Those races are driven
  deterministically through the `rf.interop/set-timeout!` seam."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.frame :as rf.frame]
            [re-frame.flows :as rf.flows]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.schemas :as rf.schemas]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  ;; A sibling test's pending host timer must not fire into this run.
  (rf.fx/reset-dispatch-later-timers!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (rf/make-frame {:id :rf/default})
  (test-fn)
  (rf.fx/reset-dispatch-later-timers!))

(use-fixtures :each reset-runtime)

(def ^:private test-frame :destroy/frame)

(defn- timers-for-frame
  "The pending host timers retained for `frame-id` in the private side table,
  which is keyed by `[frame-id timer-id]`."
  [frame-id]
  (->> @@(resolve 're-frame.fx/dispatch-later-timers)
       (filter (fn [[[fid _tid] _handle]] (= fid frame-id)))))

(defn- with-dispatch-stub
  "Run `body-fn` with the `:router/dispatch!` late-bind hook replaced by
  `stub` (`(fn [event opts] …)`), so a deferred dispatch is counted
  synchronously instead of through the async router drain."
  [stub body-fn]
  (let [real (rf.late-bind/get-fn :router/dispatch!)]
    (try
      (rf.late-bind/set-fn! :router/dispatch! stub)
      (body-fn)
      (finally
        (rf.late-bind/set-fn! :router/dispatch! real)))))

;; ---- through the real runtime ---------------------------------------------

(deftest dispatch-later-handle-retained-then-cancelled-on-destroy
  ;; The one test that sees destroy-frame! reach the timer table through the
  ;; :fx/on-frame-destroyed! hook.
  (rf/make-frame {:id test-frame})
  (rf/reg-event :destroy/target (fn [{:keys [db]} _] {:db db}))
  (rf/reg-event :destroy/arm-later
    ;; Long enough that only destroy-frame! can remove the slot.
    (fn [_ _] {:fx [[:dispatch-later {:ms 600000 :event [:destroy/target]}]]}))
  (rf/dispatch-sync [:destroy/arm-later] {:frame test-frame})
  (is (= 1 (count (timers-for-frame test-frame)))
      "the armed handle is retained under the arming frame's key")
  (rf/destroy-frame! test-frame)
  (is (empty? (timers-for-frame test-frame))
      "destroy-frame! cancelled and dropped the frame's pending timer"))

(deftest dispatch-later-still-fires-for-a-live-frame
  (let [target-ran (atom 0)]
    (rf/make-frame {:id test-frame})
    (rf/reg-event :destroy/target
      (fn [{:keys [db]} _] (swap! target-ran inc) {:db db}))
    (rf/reg-event :destroy/arm-short
      (fn [_ _] {:fx [[:dispatch-later {:ms 20 :event [:destroy/target]}]]}))
    (rf/dispatch-sync [:destroy/arm-short] {:frame test-frame})
    (Thread/sleep 300)
    (is (= 1 @target-ran) "the deferred event ran once")
    (is (empty? (timers-for-frame test-frame))
        "a fired timer drops its own slot")))

;; ---- arming races, driven through the set-timeout! seam -------------------

(def ^:private race-frame :race/frame)
(def ^:private race-event [:race/target])

(deftest dispatch-later-immediate-fire-leaves-no-orphan-handle
  ;; The JVM executor may run a 0ms thunk before set-timeout! returns its
  ;; handle, so the handle is published only if its reservation survived.
  (let [dispatched (atom [])
        cleared    (atom [])
        opts       {:source :fx-dispatch-later :source-detail {:ms 0}}]
    (with-dispatch-stub
      (fn [ev op] (swap! dispatched conj [ev op]))
      (fn []
        (with-redefs [rf.interop/set-timeout!   (fn [f _ms] (f) ::spent-handle)
                      rf.interop/clear-timeout! (fn [h] (swap! cleared conj h) nil)]
          (#'rf.fx/arm-dispatch-later! race-frame 0 race-event opts))))
    (is (= {:dispatched [[race-event opts]] :retained [] :cancelled [::spent-handle]}
           {:dispatched @dispatched
            :retained   (vec (timers-for-frame race-frame))
            :cancelled  @cleared})
        "dispatched once with its opts; the spent handle is cancelled, never retained")))

(deftest dispatch-later-cleanup-during-arming-suppresses-callback-dispatch
  ;; Cleanup removes the reservation mid-arming and the host still runs the
  ;; callback before set-timeout! returns: the callback has lost its slot.
  (doseq [[label cleanup!] [["release-frame! (frame destroy)"
                             #(rf.fx/release-frame! race-frame)]
                            ["reset-dispatch-later-timers! (test isolation)"
                             rf.fx/reset-dispatch-later-timers!]]]
    (testing label
      (let [dispatched (atom [])
            cleared    (atom [])
            opts       {:source :fx-dispatch-later :source-detail {:ms 600000}
                        :frame  race-frame}]
        (with-dispatch-stub
          (fn [ev op] (swap! dispatched conj [ev op]))
          (fn []
            (with-redefs [rf.interop/set-timeout!   (fn [f _ms] (cleanup!) (f) ::handle)
                          rf.interop/clear-timeout! (fn [h] (swap! cleared conj h) nil)]
              (#'rf.fx/arm-dispatch-later! race-frame 600000 race-event opts))))
        (is (= {:dispatched [] :retained [] :cancelled [::handle]}
               {:dispatched @dispatched
                :retained   (vec (timers-for-frame race-frame))
                :cancelled  @cleared})
            "nothing dispatched; the returned handle, never the arming sentinel, is cancelled once")))))

(deftest dispatch-later-armed-handle-destroy-then-late-callback-suppressed
  ;; An ordinary published handle, released before it fires, whose callback
  ;; the host had already started.
  (let [dispatched  (atom [])
        cleared     (atom [])
        captured-cb (atom nil)
        opts        {:source :fx-dispatch-later :source-detail {:ms 600000}
                     :frame  race-frame}]
    (with-dispatch-stub
      (fn [ev op] (swap! dispatched conj [ev op]))
      (fn []
        (with-redefs [rf.interop/set-timeout!   (fn [f _ms] (reset! captured-cb f) ::armed-handle)
                      rf.interop/clear-timeout! (fn [h] (swap! cleared conj h) nil)]
          (#'rf.fx/arm-dispatch-later! race-frame 600000 race-event opts)
          (rf.fx/release-frame! race-frame)
          (@captured-cb))))
    (is (= {:dispatched [] :cancelled [::armed-handle]}
           {:dispatched @dispatched :cancelled @cleared})
        "release-frame! cancelled the published handle once; the late callback dispatched nothing")))
