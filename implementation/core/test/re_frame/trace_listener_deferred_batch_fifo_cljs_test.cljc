(ns re-frame.trace-listener-deferred-batch-fifo-cljs-test
  "A deferred trace batch stays AHEAD of any later listener-authored trace, on
  every host.

  The post-drain flush (`re-frame.trace.tooling/drain-deferred-batch!`) queues the
  whole captured batch before driving any of it. Were it to append and drive one
  item at a time, a listener emitting during the FIRST deferred event's fan-out
  would overtake the deferred events not yet queued.

  The probe: one event's `run-start` … `run-end` emits defer as one batch; a
  listener reacts to that `run-start` by emitting a new trace, which carries a
  higher `:id` than the already-captured `run-end`. An observer records delivery
  order, which must keep `:id`s ascending and deliver `run-end` before the new
  trace. A misorder never throws, so the order is the assertion.

  One deftest per flush seam: the outermost flush (top-level `dispatch-sync`,
  `*fanout-ctx*` unbound) and the integrate path (a listener-initiated
  `dispatch-sync` folding into an active outer fan-out)."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core                 :as rf]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support         :as rf.test-support]
            [re-frame.trace                :as rf.trace]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private nested-op :audit/listener-nested)

(defn- index-of [xs x]
  (first (keep-indexed (fn [i y] (when (= x y) i)) xs)))

(defn- register-run-start-emitter!
  "On `event-id`'s `:rf.event/run-start`, emit one `nested-op` trace."
  [event-id]
  (rf.trace.tooling/register-listener! ::emitter
    (fn [ev]
      (when (and (= :rf.event/run-start (:operation ev))
                 (= event-id (first (-> ev :tags :rf.event/v))))
        (rf.trace/emit! :info nested-op {})))))

(defn- assert-batch-outranks-nested! [stream]
  (let [ids (mapv second stream)
        ops (mapv first stream)
        ro  (index-of ops :rf.event/run-end)
        no  (index-of ops nested-op)]
    (is (= (sort ids) ids)
        (str "delivery IDs regressed — a listener-authored trace overtook an "
             "older still-pending deferred item. Stream: " (pr-str stream)))
    (is (and ro no (< ro no))
        (str "the listener-authored trace was not delivered after the "
             "already-captured run-end. Ops in delivery order: " (pr-str ops)))))

;; Every deftest is `^:requires-debug`: the suite drives the dev trace end to
;; end (see scripts/test-core-prod-gate.sh).

(deftest ^:requires-debug deferred-batch-outranks-listener-authored-trace-at-top-level
  (let [seen (atom [])]
    (rf/reg-event :t6vs3/noop (fn [{:keys [db]} _] {:db db}))
    (register-run-start-emitter! :t6vs3/noop)
    (rf.trace.tooling/register-listener! ::observer
      (fn [ev] (swap! seen conj [(:operation ev) (:id ev)])))
    (try
      (rf/dispatch-sync [:t6vs3/noop] {:frame :rf/default})
      (assert-batch-outranks-nested! @seen)
      (finally
        (rf.trace.tooling/unregister-listener! ::emitter)
        (rf.trace.tooling/unregister-listener! ::observer)))))

(deftest ^:requires-debug deferred-batch-outranks-listener-authored-trace-when-integrated
  (let [seen (atom [])]
    (rf/reg-event :t6vs3/inner (fn [{:keys [db]} _] {:db db}))
    ;; Registered first: a clean emit's fan-out (so `*fanout-ctx*` is bound)
    ;; opens the nested drain whose batch integrates on flush.
    (rf.trace.tooling/register-listener! ::trigger
      (fn [ev]
        (when (= :t6vs3/trigger (:operation ev))
          (rf/dispatch-sync [:t6vs3/inner] {:frame :rf/default}))))
    (register-run-start-emitter! :t6vs3/inner)
    (rf.trace.tooling/register-listener! ::observer
      (fn [ev] (swap! seen conj [(:operation ev) (:id ev)])))
    (try
      (rf.trace/emit! :info :t6vs3/trigger {})
      (assert-batch-outranks-nested! @seen)
      (finally
        (rf.trace.tooling/unregister-listener! ::trigger)
        (rf.trace.tooling/unregister-listener! ::emitter)
        (rf.trace.tooling/unregister-listener! ::observer)))))
