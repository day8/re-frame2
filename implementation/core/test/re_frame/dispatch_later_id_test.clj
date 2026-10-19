(ns re-frame.dispatch-later-id-test
  "`:dispatch-later` with an `:id` names its pending timer within the frame.
  Arming the same `:id` again replaces the pending timer, which is debounce;
  `{:id k :cancel? true}` cancels it; the same `:id` in two frames names two
  timers; and frame destroy still cancels every pending timer. An arm with no
  `:id` behaves as it always has: each one fires.

  The host clock is driven through the `rf.interop/set-timeout!` seam, so a
  test fires exactly the callbacks the host still holds, and the router's
  dispatch hook records each deferred dispatch instead of queueing it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.flows :as rf.flows]
            [re-frame.frame :as rf.frame]
            [re-frame.fx :as rf.fx]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.error-emit/clear-error-listeners!)
  (rf.fx/reset-dispatch-later-timers!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (rf/make-frame {:id :rf/default})
  (test-fn)
  (rf.fx/reset-dispatch-later-timers!)
  (rf.error-emit/clear-error-listeners!))

(use-fixtures :each reset-runtime)

(defn- timers-for-frame
  "The pending host timers retained for `frame-id` in the private side table."
  [frame-id]
  (->> @@(resolve 're-frame.fx/dispatch-later-timers)
       (filter (fn [[[fid _tid] _handle]] (= fid frame-id)))))

(defn- run-with-host-clock
  "Run `body-fn` with every host timer recorded instead of scheduled, then fire
  each recorded callback in arming order with `:router/dispatch!` recording
  the deferred event. A callback whose timer was replaced, cancelled or
  released dispatches nothing, exactly as on a real host whose cancellation
  lost the race to an already-started callback.

  Returns `{:armed [handle …] :cleared [handle …] :dispatched [event …]}`."
  [body-fn]
  (let [armed      (atom [])
        cleared    (atom [])
        dispatched (atom [])
        real       (rf.late-bind/get-fn :router/dispatch!)]
    (with-redefs [rf.interop/set-timeout!   (fn [f _ms]
                                              (let [h [::handle (count @armed)]]
                                                (swap! armed conj [h f])
                                                h))
                  rf.interop/clear-timeout! (fn [h] (swap! cleared conj h) nil)]
      (body-fn))
    (try
      (rf.late-bind/set-fn! :router/dispatch! (fn [ev _opts] (swap! dispatched conj ev)))
      (doseq [[_ f] @armed] (f))
      (finally
        (rf.late-bind/set-fn! :router/dispatch! real)))
    {:armed      (mapv first @armed)
     :cleared    @cleared
     :dispatched @dispatched}))

(defn- reg-search-events!
  "`:search/typed` debounces `:search/run` under `:id :search`; `:search/leave`
  cancels it."
  []
  (rf/reg-event :search/run (fn [_ _] {}))
  (rf/reg-event :search/typed
    (fn [_ [_ q]]
      {:fx [[:dispatch-later {:id :search :ms 300 :event [:search/run q]}]]}))
  (rf/reg-event :search/leave
    (fn [_ _]
      {:fx [[:dispatch-later {:id :search :cancel? true}]]})))

(deftest a-second-arm-with-the-same-id-replaces-the-pending-timer
  (reg-search-events!)
  (is (= {:armed      [[::handle 0] [::handle 1]]
          :cleared    [[::handle 0]]
          :dispatched [[:search/run "ab"]]}
         (run-with-host-clock
           #(do (rf/dispatch-sync [:search/typed "a"] {:frame :rf/default})
                (rf/dispatch-sync [:search/typed "ab"] {:frame :rf/default}))))
      "the first timer is cancelled when the second arms, so only the later event runs"))

(deftest cancel-by-id-dispatches-nothing
  (reg-search-events!)
  (is (= {:armed      [[::handle 0]]
          :cleared    [[::handle 0]]
          :dispatched []}
         (run-with-host-clock
           #(do (rf/dispatch-sync [:search/typed "a"] {:frame :rf/default})
                (rf/dispatch-sync [:search/leave] {:frame :rf/default}))))
      "the cancel releases the pending timer and arms nothing")
  (testing "a cancel naming no pending timer is a no-op"
    (is (= {:armed [] :cleared [] :dispatched []}
           (run-with-host-clock #(rf/dispatch-sync [:search/leave] {:frame :rf/default}))))))

(deftest the-same-id-in-two-frames-names-two-timers
  (reg-search-events!)
  (rf/make-frame {:id :search/a})
  (rf/make-frame {:id :search/b})
  (is (= {:armed      [[::handle 0] [::handle 1]]
          :cleared    []
          :dispatched [[:search/run "in a"] [:search/run "in b"]]}
         (run-with-host-clock
           #(do (rf/dispatch-sync [:search/typed "in a"] {:frame :search/a})
                (rf/dispatch-sync [:search/typed "in b"] {:frame :search/b}))))))

(deftest an-arm-without-an-id-is-never-replaced
  (rf/reg-event :toast/hide (fn [_ _] {}))
  (rf/reg-event :toast/show
    (fn [_ [_ n]] {:fx [[:dispatch-later {:ms 300 :event [:toast/hide n]}]]}))
  (is (= {:armed      [[::handle 0] [::handle 1]]
          :cleared    []
          :dispatched [[:toast/hide 1] [:toast/hide 2]]}
         (run-with-host-clock
           #(do (rf/dispatch-sync [:toast/show 1] {:frame :rf/default})
                (rf/dispatch-sync [:toast/show 2] {:frame :rf/default}))))))

(deftest destroy-frame-still-cancels-an-id-d-timer
  (reg-search-events!)
  (rf/make-frame {:id :search/doomed})
  (let [result (run-with-host-clock
                 #(do (rf/dispatch-sync [:search/typed "a"] {:frame :search/doomed})
                      (rf/destroy-frame! :search/doomed)))]
    (is (= {:armed      [[::handle 0]]
            :cleared    [[::handle 0]]
            :dispatched []}
           result))
    (is (empty? (timers-for-frame :search/doomed)))))

(deftest ^:requires-debug replace-and-cancel-each-emit-one-trace
  ;; A dev trace, so it is absent under `-Dre-frame.debug=false`.
  (reg-search-events!)
  (let [traces (atom [])]
    (rf/register-listener! :trace ::rec #(swap! traces conj %))
    (try
      (run-with-host-clock
        #(do (rf/dispatch-sync [:search/typed "a"] {:frame :rf/default})
             (rf/dispatch-sync [:search/typed "ab"] {:frame :rf/default})
             (rf/dispatch-sync [:search/leave] {:frame :rf/default})
             ;; Nothing is pending now, so this cancel traces nothing.
             (rf/dispatch-sync [:search/leave] {:frame :rf/default})))
      (finally (rf/unregister-listener! :trace ::rec)))
    (is (= [{:op-type :rf.fx :frame :rf/default :id :search :reason :on-replace}
            {:op-type :rf.fx :frame :rf/default :id :search :reason :on-cancel}]
           (->> @traces
                (filter #(= :rf.fx/dispatch-later-cancelled (:operation %)))
                (mapv #(assoc (select-keys (:tags %) [:frame :id :reason])
                              :op-type (:op-type %))))))))

(deftest a-cancel-without-an-id-is-refused
  (let [errors (atom [])]
    (rf.error-emit/register-error-listener! ::recorder #(swap! errors conj %))
    (rf/reg-event :search/bad-cancel
      (fn [_ _] {:fx [[:dispatch-later {:cancel? true}]]}))
    (is (= {:armed [] :cleared [] :dispatched []}
           (run-with-host-clock #(rf/dispatch-sync [:search/bad-cancel] {:frame :rf/default}))))
    (is (= [:dispatch-later]
           (->> @errors
                (filter #(= :rf.error/fx-handler-exception (:error %)))
                (mapv :failing-id)))
        "one always-on refusal names the :dispatch-later fx")))
