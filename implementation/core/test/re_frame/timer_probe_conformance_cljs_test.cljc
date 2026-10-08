(ns re-frame.timer-probe-conformance-cljs-test
  "Conformance for `re-frame.timer-probe`, the test-only managed-timer surface
  that `spec/Managed-Effects.md` §How new managed-effect surfaces inherit the
  contract names as its proof that a brand-new surface adopts the managed-effect
  properties by consuming the shared `re-frame.reply` substrate. Each test pins
  one property the probe demonstrates, and the reply-target functor laws hold
  over the probe's own target; the substrate's own laws are pinned by
  `re-frame.reply-cljs-test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.reply :as rf.reply]
            [re-frame.timer-probe :as rf.timer-probe]))

(use-fixtures :each (fn [t] (rf.timer-probe/reset-registry!) (t) (rf.timer-probe/reset-registry!)))

(defn- stub-scheduler
  "A host scheduler stand-in returning an opaque cancel token and the causal
  start reading, so no real clock is involved."
  [start-ms]
  (fn [_args] {:handle (gensym "host-timer-") :started-at start-ms}))

(def ^:private base-args
  {:timer/id    :debounce/search
   :after       300
   :generation  1
   :rf/reply-to [:search/timer-elapsed {:q "re-frame"}]
   :frame       :rf/default})

(def ^:private wid [:rf.work/timer :debounce/search 1])

(deftest args-map-shape-is-closed
  (testing "the reply target is accepted only under the canonical :rf/reply-to key"
    (is (false? (rf.timer-probe/valid-args? (-> base-args
                                                (dissoc :rf/reply-to)
                                                (assoc :reply-to [:t])))))))

(deftest registry-and-teardown-are-frame-scoped
  (testing "two frames' in-flight timers are disjoint, and tearing one frame
            down leaves the other's untouched"
    (rf.timer-probe/probe-timer-fx {:frame :frame/a} base-args (stub-scheduler 1000))
    (rf.timer-probe/probe-timer-fx {:frame :frame/b} (assoc base-args :timer/id :other) (stub-scheduler 1000))
    (is (= [[wid] [[:rf.work/timer :other 1]]]
           [(rf.timer-probe/in-flight :frame/a) (rf.timer-probe/in-flight :frame/b)]))
    (rf.timer-probe/teardown-frame! :frame/a)
    (is (= [[] [[:rf.work/timer :other 1]]]
           [(rf.timer-probe/in-flight :frame/a) (rf.timer-probe/in-flight :frame/b)]))))

(deftest ok-reply-validates
  (testing "an elapsed timer's :ok reply is canonical and carries the work id,
            the frame stamp and the host-supplied causal timestamps verbatim"
    (let [reply (rf.timer-probe/complete-elapsed base-args
                                                 {:fired-at 1781078400456}
                                                 {:started-at 1781078400156 :completed-at 1781078400456})]
      (is (rf.reply/valid-reply? reply))
      (is (= {:status               :ok
              :value                {:fired-at 1781078400456}
              :rf.reply/work-status :completed
              :rf.reply/work-kind   :timer
              :rf.reply/work-id     wid
              :rf.frame/id          :rf/default
              :started-at           1781078400156
              :completed-at         1781078400456}
             reply)))))

(deftest error-reply-carries-family-kind
  (testing "a failed timer's :error reply is canonical and carries an
            :rf.timer/* family :kind"
    (let [reply (rf.timer-probe/complete-error base-args {:message "boom"} {:completed-at 1781078400456})]
      (is (rf.reply/valid-reply? reply))
      (is (= {:status               :error
              :rf.reply/work-status :failed
              :error                {:message "boom" :kind :rf.timer/elapsed-error}}
             (select-keys reply [:status :rf.reply/work-status :error]))))))

(deftest stale-generation-suppresses-no-dispatch
  (testing "a completion whose carried generation was superseded is never
            delivered: a valid :stale reply with no :value, and a data-only
            suppression trace joining the carried and current gates to the
            work id"
    (let [{:keys [reply] :as outcome} (rf.timer-probe/suppress base-args 2 {:completed-at 1781078400456})]
      (is (rf.reply/valid-reply? reply))
      (is (= {:deliver?             false
              :rf.reply/work-status :suppressed
              :reply                {:status                :stale
                                     :stale?                true
                                     :rf.reply/stale-reason :rf.timer/generation-stale
                                     :rf.reply/work-status  :suppressed
                                     :rf.reply/work-kind    :timer
                                     :rf.reply/work-id      wid
                                     :rf.frame/id           :rf/default
                                     :completed-at          1781078400456}
              :trace                {:rf.reply/suppressed?  true
                                     :rf.reply/stale-reason :rf.timer/generation-stale
                                     :rf.reply/work-id      wid
                                     :rf.reply/carried      {:generation 1}
                                     :rf.reply/current      {:generation 2}}}
             outcome)))))

(deftest reply-target-functor-law
  (let [target [:search/timer-elapsed {:q "re-frame"}]
        reply  (rf.timer-probe/complete-elapsed base-args {:fired-at 1} {:completed-at 1})
        done   #(rf.reply/complete % reply)
        f      (fn [e] [:f e])
        g      (fn [e] [:g e])]
    (testing "naturality: mapping the target then completing equals completing then mapping"
      (is (= (f (done target)) (done (rf.reply/map-completed-event f target)))))
    (testing "identity and composition over the timer target"
      (is (= (done target) (done (rf.reply/map-completed-event identity target))))
      (is (= (done (rf.reply/map-completed-event (comp f g) target))
             (done (rf.reply/map-completed-event f (rf.reply/map-completed-event g target))))))))

(deftest retry-is-data
  (testing "a declarative :retry policy projects to a delay schedule — data,
            not caller code"
    (is (= [100 200 300]
           (rf.timer-probe/retry-plan (assoc base-args :retry {:max-attempts 3 :backoff {:base-ms 100}}))))))

(deftest abort-requested-as-data
  (testing "an abort releases the registry-held host handle and returns the
            cancellation as data — a :cancelled reply, never a callback"
    (let [{:keys [handle]} (rf.timer-probe/probe-timer-fx {:frame :rf/default} base-args (stub-scheduler 1000))
          outcome          (rf.timer-probe/request-abort! :rf/default base-args {:completed-at 1781078400999})]
      (is (rf.reply/valid-reply? (:reply outcome)))
      (is (= {:aborted? true
              :released handle
              :reply    {:status                 :cancelled
                         :cancelled?             true
                         :rf.reply/cancel-reason :rf.timer/cancelled
                         :rf.reply/work-status   :cancelled
                         :rf.reply/work-kind     :timer
                         :rf.reply/work-id       wid
                         :rf.frame/id            :rf/default
                         :started-at             1000
                         :completed-at           1781078400999}}
             outcome))
      (is (empty? (rf.timer-probe/in-flight :rf/default)))))
  (testing "aborting a timer that is not in flight is a no-op, recorded as data"
    (is (= {:aborted? false :released nil :reply nil}
           (rf.timer-probe/request-abort! :rf/default base-args {:completed-at 1})))))

(deftest frame-destroy-cancels-and-records
  (testing "frame teardown cancels every in-flight timer and records each
            cancellation as data, never silently dropping one"
    (let [r1      (rf.timer-probe/probe-timer-fx {:frame :rf/default} base-args (stub-scheduler 1000))
          r2      (rf.timer-probe/probe-timer-fx {:frame :rf/default}
                                                 (assoc base-args :timer/id :idle/logout)
                                                 (stub-scheduler 1100))
          records (rf.timer-probe/teardown-frame! :rf/default)]
      (is (= 2 (count records)))
      (is (= #{{:work/id wid :released (:handle r1) :reason :rf.timer/frame-destroyed}
               {:work/id [:rf.work/timer :idle/logout 1] :released (:handle r2) :reason :rf.timer/frame-destroyed}}
             (set records)))
      (is (empty? (rf.timer-probe/in-flight :rf/default))))))

(deftest trace-reply-routes-through-shared-walker
  (testing "the probe's trace summary is the shared re-frame.reply/trace-summary,
            never a family-private elider"
    (let [reply (rf.timer-probe/complete-elapsed base-args {:fired-at 42} {:started-at 1 :completed-at 2})]
      (is (= (rf.reply/trace-summary reply {:frame :rf/default})
             (rf.timer-probe/trace-reply reply {:frame :rf/default}))))))
