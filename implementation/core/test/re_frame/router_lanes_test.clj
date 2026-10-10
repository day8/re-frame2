(ns re-frame.router-lanes-test
  "Run-to-completion through two causal lanes (Spec 002 §Run-to-completion).
  A dispatch made synchronously inside the target frame's in-flight event
  joins its INTERNAL lane; every other dispatch joins its EXTERNAL lane. Both
  are FIFO and the drain empties the internal lane before it takes the next
  external event, so everything an event dispatches — whatever kind of
  handler dispatched it — settles before the next outside event runs.

  The `:drain-depth` budget is per FAMILY: an external event (or a
  `dispatch-sync` seed) plus everything it dispatches into the internal lane.
  A runaway family halts and discards only its own internal work; external
  input queued behind it still runs, and finite fan-outs and bursts settle.

  JVM-only: an event is held in flight on the executor thread with a promise,
  so \"external\" here means dispatched from the test thread while that event
  holds the drain. Real registered handlers on the plain-atom adapter. The
  ordering and the always-on depth record are asserted unguarded, so this
  namespace also runs under `scripts/test-core-prod-gate.sh`; the
  `:halted-depth` epoch record is a dev-trace product and is read only in
  debug builds."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.machines]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

;; ---- helpers ----------------------------------------------------------------

(defn- logging-event!
  "Register `id` as a plain handler that logs `k` and dispatches `children`."
  ([log id k] (logging-event! log id k []))
  ([log id k children]
   (rf/reg-event id
     (fn [_ _]
       (swap! log conj k)
       {:fx (mapv (fn [c] [:dispatch [c]]) children)}))))

(defn- logging-machine!
  "Register machine `id` whose `[:go]` action logs `k` and dispatches
  `children` through its `:fx`."
  [log id k children]
  (rf/reg-machine id
    {:initial :idle
     :actions {:fire (fn [_]
                       (swap! log conj k)
                       {:fx (mapv (fn [c] [:dispatch [c]]) children)})}
     :states  {:idle {:on {:go {:target :done :action :fire}}}
               :done {}}}))

(defn- hold-drain!
  "Dispatch `:lanes/block` to frame `fid` and return its gate once the
  handler is running on the drain thread. While the gate is open the frame
  has an event in flight on ANOTHER thread, so every dispatch the test thread
  makes is external. Delivering the gate lets the blocker return, emitting
  `children` from its `:fx`."
  ([log fid] (hold-drain! log fid []))
  ([log fid children]
   (let [started (promise)
         gate    (promise)]
     (rf/reg-event :lanes/block
       (fn [_ _]
         (swap! log conj :block)
         (deliver started true)
         (deref gate 5000 nil)
         {:fx (mapv (fn [c] [:dispatch [c]]) children)}))
     (rf/dispatch [:lanes/block] {:frame fid})
     (is (true? (deref started 5000 false)) "the blocker is in flight")
     gate)))

(defn- await-settled!
  "Wait until frame `fid` has no drain running and nothing queued."
  [fid]
  (rf.test-support/poll-until
    #(let [f (rf.frame/frame fid)
           r @(:router f)]
       (and (not (:scheduled? r))
            (not @(:drain-lock f))
            (empty? (:queue r))
            (empty? (:internal r))))
    {:label (str fid " settles")}))

(defn- record-depth-errors!
  "Collect every always-on `:rf.error/drain-depth-exceeded` record."
  [k]
  (let [records (atom [])]
    (rf.error-emit/register-error-listener! k
      (fn [rec]
        (when (= :rf.error/drain-depth-exceeded (:error rec))
          (swap! records conj rec))))
    records))

;; ---- ordering: the S-probes ---------------------------------------------------

(deftest s1-an-external-event-waits-for-the-family-queued-ahead-of-it
  (let [fid :lanes/s1
        log (atom [])]
    (rf/make-frame {:id fid})
    (logging-event! log :lanes/a :a [:lanes/a-child])
    (logging-event! log :lanes/a-child :a-child)
    (logging-event! log :lanes/b :b)
    (let [gate (hold-drain! log fid)]
      (rf/dispatch [:lanes/a] {:frame fid})
      (rf/dispatch [:lanes/b] {:frame fid})
      (deliver gate true))
    (await-settled! fid)
    (is (= [:block :a :a-child :b] @log)
        ":a's child settles before the external :b queued behind :a")))

(deftest s2-siblings-one-event-dispatches-keep-breadth-first-order
  ;; The control: every option keeps this order.
  (let [fid :lanes/s2
        log (atom [])]
    (rf/make-frame {:id fid})
    (logging-event! log :lanes/kick :kick [:lanes/a :lanes/b])
    (logging-event! log :lanes/a :a [:lanes/a-child])
    (logging-event! log :lanes/a-child :a-child)
    (logging-event! log :lanes/b :b)
    (rf/dispatch-sync [:lanes/kick] {:frame fid})
    (is (= [:kick :a :b :a-child] @log)
        "siblings run in source order, then the next generation")))

(deftest s3-a-plain-relay-keeps-the-machine-family-ahead-of-external-input
  (let [fid :lanes/s3
        log (atom [])]
    (rf/make-frame {:id fid})
    (logging-machine! log :lanes/m :m [:lanes/m-child])
    (logging-event! log :lanes/m-child :m-child [:lanes/m-grandchild])
    (logging-event! log :lanes/m-grandchild :m-grandchild)
    (logging-event! log :lanes/b :b)
    (let [gate (hold-drain! log fid)]
      (rf/dispatch [:lanes/m [:go]] {:frame fid})
      (rf/dispatch [:lanes/b] {:frame fid})
      (deliver gate true))
    (await-settled! fid)
    (is (= [:block :m :m-child :m-grandchild :b] @log)
        "the plain child's own child is still in the family")))

(deftest s4-a-machine-child-does-not-overtake-an-earlier-sibling
  (let [fid :lanes/s4
        log (atom [])]
    (rf/make-frame {:id fid})
    (rf/reg-event :lanes/kick
      (fn [_ _]
        (swap! log conj :kick)
        {:fx [[:dispatch [:lanes/m [:go]]] [:dispatch [:lanes/sib]]]}))
    (logging-machine! log :lanes/m :m [:lanes/m-child])
    (logging-event! log :lanes/m-child :m-child)
    (logging-event! log :lanes/sib :sib)
    (rf/dispatch-sync [:lanes/kick] {:frame fid})
    (is (= [:kick :m :sib :m-child] @log)
        "a machine's child joins the internal lane behind the sibling already there")))

(deftest s5-input-from-another-thread-mid-event-is-external
  (let [fid     :lanes/s5
        log     (atom [])
        started (promise)
        gate    (promise)]
    (rf/make-frame {:id fid})
    (rf/reg-event :lanes/a
      (fn [_ _]
        (swap! log conj :a)
        (deliver started true)
        (deref gate 5000 nil)
        {:fx [[:dispatch [:lanes/a-child]]]}))
    (logging-event! log :lanes/a-child :a-child)
    (logging-event! log :lanes/x :x)
    (rf/dispatch [:lanes/a] {:frame fid})
    (is (true? (deref started 5000 false)))
    ;; :a is in flight on the executor thread; this thread is not running it.
    (rf/dispatch [:lanes/x] {:frame fid})
    (deliver gate true)
    (await-settled! fid)
    (is (= [:a :a-child :x] @log)
        "a dispatch made while the drain is busy, but not by the in-flight event, waits for the family")))

(deftest s6-dispatch-sync-runs-its-seed-family-before-queued-input
  (let [fid   :lanes/s6
        log   (atom [])
        ticks (atom [])]
    (rf/make-frame {:id fid})
    (logging-event! log :lanes/q1 :q1)
    (logging-event! log :lanes/q2 :q2)
    (logging-event! log :lanes/seed :seed [:lanes/seed-child])
    (logging-event! log :lanes/seed-child :seed-child)
    ;; Hold the async drain: its scheduled callback is captured, not run.
    (with-redefs [rf.interop/next-tick (fn [f] (swap! ticks conj f) nil)]
      (rf/dispatch [:lanes/q1] {:frame fid})
      (rf/dispatch [:lanes/q2] {:frame fid})
      (rf/dispatch-sync [:lanes/seed] {:frame fid}))
    (doseq [f @ticks] (f))
    (is (= [:seed :seed-child :q1 :q2] @log)
        "the seed and its child settle before the input queued ahead of the call")))

;; ---- the classifier is causal, never temporal ---------------------------------

(deftest a-dispatch-from-a-future-started-in-a-handler-is-external
  ;; Clojure conveys the handler's dynamic bindings into the future, so an
  ;; owner-binding classifier would call this dispatch internal.
  (let [fid :lanes/future
        log (atom [])]
    (rf/make-frame {:id fid})
    (rf/reg-event :lanes/parent
      (fn [_ _]
        (swap! log conj :parent)
        @(future (rf/dispatch [:lanes/from-future] {:frame fid}))
        {:fx [[:dispatch [:lanes/p-child]]]}))
    (logging-event! log :lanes/p-child :p-child)
    (logging-event! log :lanes/ext :ext)
    (logging-event! log :lanes/from-future :from-future)
    (let [gate (hold-drain! log fid)]
      (rf/dispatch [:lanes/parent] {:frame fid})
      (rf/dispatch [:lanes/ext] {:frame fid})
      (deliver gate true))
    (await-settled! fid)
    (is (= [:block :parent :p-child :ext :from-future] @log)
        "the future's dispatch queues behind :ext; the handler's own child does not")))

(deftest a-bound-fn-called-after-its-handler-returned-is-external
  ;; `bound-fn` carries the handler's bindings past its return.
  (let [fid      :lanes/bound
        log      (atom [])
        captured (atom nil)]
    (rf/make-frame {:id fid})
    (rf/reg-event :lanes/capture
      (fn [_ _]
        (reset! captured (bound-fn [] (rf/dispatch [:lanes/from-bound] {:frame fid})))
        {}))
    (logging-event! log :lanes/block-child :block-child)
    (logging-event! log :lanes/ext :ext)
    (logging-event! log :lanes/from-bound :from-bound)
    (rf/dispatch-sync [:lanes/capture] {:frame fid})
    (let [gate (hold-drain! log fid [:lanes/block-child])]
      (rf/dispatch [:lanes/ext] {:frame fid})
      (@captured)
      (deliver gate true))
    (await-settled! fid)
    (is (= [:block :block-child :ext :from-bound] @log)
        "the bound-fn's dispatch neither joins the blocker's family nor overtakes :ext")))

;; ---- the per-family :drain-depth budget ---------------------------------------

(deftest a-150-child-fan-out-runs-every-child
  (let [fid     :lanes/fan-out
        records (record-depth-errors! ::fan-out)]
    (try
      (rf/make-frame {:id fid})
      (rf/reg-event :lanes/leaf (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
      (rf/reg-event :lanes/fan-out
        (fn [_ _] {:fx (vec (for [i (range 150)] [:dispatch [:lanes/leaf i]]))}))
      (rf/dispatch-sync [:lanes/fan-out] {:frame fid})
      (is (= 150 (:n (rf/app-db-value fid))) "every child ran under the default budget")
      (is (empty? @records) "a finite fan-out is not a runaway")
      (finally (rf.error-emit/unregister-error-listener! ::fan-out)))))

(deftest a-150-event-external-burst-runs-every-event
  (let [fid     :lanes/burst
        log     (atom [])
        records (record-depth-errors! ::burst)]
    (try
      (rf/make-frame {:id fid})
      (rf/reg-event :lanes/leaf (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
      (logging-event! log :lanes/other :other)
      (let [gate (hold-drain! log fid)]
        (dotimes [i 150] (rf/dispatch [:lanes/leaf i] {:frame fid}))
        (rf/dispatch [:lanes/other] {:frame fid})
        (deliver gate true))
      (await-settled! fid)
      (is (= [150 [:block :other]] [(:n (rf/app-db-value fid)) @log])
          "each external event is its own one-event family")
      (is (empty? @records))
      (finally (rf.error-emit/unregister-error-listener! ::burst)))))

(deftest a-runaway-halts-alone-and-external-input-behind-it-still-runs
  (let [fid     :lanes/runaway
        log     (atom [])
        records (record-depth-errors! ::runaway)]
    (try
      (rf/make-frame {:id fid :drain-depth 10})
      (rf/reg-event :lanes/loop
        (fn [{:keys [db]} _]
          {:db (update db :n (fnil inc 0))
           :fx [[:dispatch [:lanes/loop]]]}))
      (logging-event! log :lanes/other :other)
      (let [gate (hold-drain! log fid)]
        (rf/dispatch [:lanes/loop] {:frame fid})
        (rf/dispatch [:lanes/other] {:frame fid})
        (deliver gate true))
      (await-settled! fid)
      (is (= 10 (:n (rf/app-db-value fid))) "the loop's family ran exactly :drain-depth events")
      (is (= [:block :other] @log) "the external event queued behind the runaway ran")
      (is (= [{:depth 10 :queue-size 1 :dropped-event-ids [:lanes/loop]}]
             (mapv #(select-keys % [:depth :queue-size :dropped-event-ids]) @records))
          "one halt, discarding only the family's own pending event")
      (when rf.interop/debug-enabled?
        (is (some #(= :halted-depth (:outcome %)) (rf/epoch-history fid))
            "the halt commits its :halted-depth record"))
      (finally (rf.error-emit/unregister-error-listener! ::runaway)))))

(deftest a-branching-runaway-halts-and-external-input-still-runs
  (let [fid     :lanes/branching
        log     (atom [])
        records (record-depth-errors! ::branching)]
    (try
      (rf/make-frame {:id fid :drain-depth 10})
      (rf/reg-event :lanes/branch
        (fn [_ _] {:fx [[:dispatch [:lanes/branch]] [:dispatch [:lanes/branch]]]}))
      (logging-event! log :lanes/other :other)
      (let [gate (hold-drain! log fid)]
        (rf/dispatch [:lanes/branch] {:frame fid})
        (rf/dispatch [:lanes/other] {:frame fid})
        (deliver gate true))
      (await-settled! fid)
      (is (= [:block :other] @log))
      (is (= [{:depth 10 :queue-size 11}]
             (mapv #(select-keys % [:depth :queue-size]) @records))
          "the halt discards the family's eleven pending events and nothing else")
      (finally (rf.error-emit/unregister-error-listener! ::branching)))))

(deftest a-family-of-exactly-drain-depth-events-settles-with-external-input-waiting
  ;; The halt peeks the internal lane only: the waiting external event belongs
  ;; to the next family, so it does not make a finished family look runaway.
  (let [fid     :lanes/exact
        log     (atom [])
        records (record-depth-errors! ::exact)]
    (try
      (rf/make-frame {:id fid :drain-depth 4})
      (rf/reg-event :lanes/tick
        (fn [{:keys [db]} _]
          (let [n (inc (:n db 0))]
            (cond-> {:db (assoc db :n n)}
              (< n 4) (assoc :fx [[:dispatch [:lanes/tick]]])))))
      (logging-event! log :lanes/other :other)
      (let [gate (hold-drain! log fid)]
        (rf/dispatch [:lanes/tick] {:frame fid})
        (rf/dispatch [:lanes/other] {:frame fid})
        (deliver gate true))
      (await-settled! fid)
      (is (= [4 [:block :other]] [(:n (rf/app-db-value fid)) @log]))
      (is (empty? @records) "no halt")
      (finally (rf.error-emit/unregister-error-listener! ::exact)))))
