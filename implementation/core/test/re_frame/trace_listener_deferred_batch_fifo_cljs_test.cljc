(ns re-frame.trace-listener-deferred-batch-fifo-cljs-test
  "rf2-t6vs3 — a DEFERRED trace batch must stay AHEAD of any later
  listener-authored trace, on EVERY host.

  ## The defect this pins (rf2-t6vs3)

  PR #6452's post-drain flush (`re-frame.trace.tooling/drain-deferred-batch!`)
  preserved FIFO only among the deferred items it had ALREADY inserted into a
  fan-out schedule. It took the captured `pending` batch but appended and drove
  one item at a time — both when integrating into an active `*fanout-ctx*` and
  when opening fresh outermost fan-outs. While driving the FIRST deferred event,
  a listener could synchronously `trace/emit!`; that later trace was appended to
  the current schedule and delivered BEFORE the flush loop had inserted the older
  deferred events still sitting in the batch.

  ## The probe (identical on both hosts)

  Dispatch ONE event. The drain emits `:rf.event/run-start` … `:rf.event/run-end`
  while it owns the frame's lock, so those emits DEFER as one batch. A listener
  reacts to that event's `run-start` by authoring a new trace
  (`:audit/listener-nested`). Because that authored trace is emitted at flush
  time it carries a HIGHER `:id` than the already-captured `run-end`. An observer
  listener records the `:id` (the authoritative emission order) of every event in
  DELIVERY order:

    - correct (post-fix): the whole batch is queued before any callback runs, so
      the authored trace lands behind `run-end`. Delivery IDs stay monotonically
      increasing and the observer sees run-start, run-end, THEN the nested trace.
    - overtake (the defect): the flush drives `run-start` before `run-end` is even
      inserted, so the nested trace (higher id) overtakes the still-pending
      `run-end`. Delivery IDs REGRESS — the observer sees run-start, the nested
      trace, then run-end.

  The assertion is an OBSERVABLE OUTCOME — the delivery-ID order — never an
  exception; this class does not throw, it silently misorders. Ring/ID emission
  order stays correct throughout, so a stateful tool folding the delivered stream
  now receives a different order from the authoritative IDs — the exact drift the
  bead reports.

  Two levers pin the two seams the flush must keep FIFO (bead acceptance):

    - `deferred-batch-outranks-listener-authored-trace-at-top-level` — the
      OUTERMOST flush (a top-level `dispatch-sync`; `*fanout-ctx*` unbound), which
      seeds fresh outermost fan-outs.
    - `deferred-batch-outranks-listener-authored-trace-when-integrated` — the
      INTEGRATE path (a listener-initiated `dispatch-sync` whose batch folds into
      an already-active outer fan-out; `*fanout-ctx*` bound).

  Dual-host (`npm run test:cljs` + `clojure -M:test`): intra-drain listener
  influence is platform-uniform, so the same deftests pin JVM and CLJS. `-cljs-test`
  matches the shadow `:node-test` ns-regexp and cognitect-test-runner's `*-test`
  discovery."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core                 :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support         :as test-support]
            [re-frame.trace                :as trace]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ^:private nested-op :audit/listener-nested)

(defn- monotonic?
  "True iff `xs` never decreases — the delivered `:id` stream must climb, since
  each `:id` is stamped in emission order and a later emit must never be
  delivered before an earlier one."
  [xs]
  (or (< (count xs) 2) (apply <= xs)))

(defn- index-of [xs x]
  (first (keep-indexed (fn [i y] (when (= x y) i)) xs)))

;; ---- Posture: dev-only, declared by `^:requires-debug` (rf2-d2841) ---------
;; Trace machinery end to end: under `-Dre-frame.debug=false` `trace/emit` is a
;; no-op, so there is no semantic residue to run under that posture, and a
;; `(when interop/debug-enabled? ...)` split -- the shape the rest of rf2-d2841
;; used -- would leave EMPTY deftests reporting green (class 2).  Every deftest
;; below is therefore TAGGED, and the production-gate lane skips the tag rather
;; than the file: the namespace is still LOADED there, so a load-time failure
;; under the gate still reddens the job, and an untagged new deftest joins that
;; lane BY DEFAULT.  Mechanism + rationale: `scripts/test-core-prod-gate.sh`.

(deftest ^:requires-debug deferred-batch-outranks-listener-authored-trace-at-top-level
  ;; OUTERMOST seam: a top-level `dispatch-sync` defers the whole run batch, then
  ;; flushes it with `*fanout-ctx*` unbound (fresh outermost fan-outs). A
  ;; run-start listener authors a later trace; it must not overtake the
  ;; already-captured run-end still sitting in the batch.
  (let [seen     (atom [])          ;; observer's [operation id] stream, in delivery order
        emitted? (atom false)]
    (rf/reg-event :t6vs3/noop (fn [{:keys [db]} _] {:db db}))
    ;; EMITTER (registered first): on THIS event's run-start, author exactly one
    ;; nested trace. Emitted at flush time, so its `:id` exceeds run-end's.
    (trace/register-listener! ::emitter
      (fn [ev]
        (when (and (= :rf.event/run-start (:operation ev))
                   (= :t6vs3/noop (first (-> ev :tags :rf.event/v)))
                   (compare-and-set! emitted? false true))
          (trace/emit! :info nested-op {}))))
    ;; OBSERVER: record every event's operation + id, in the order delivered.
    (trace/register-listener! ::observer
      (fn [ev] (swap! seen conj [(:operation ev) (:id ev)])))
    (try
      (rf/dispatch-sync [:t6vs3/noop] {:frame :rf/default})
      (let [stream @seen
            ids    (mapv second stream)
            ops    (mapv first stream)]
        ;; Vacuity guard: the lever actually engaged.
        (is (true? @emitted?)
            "the run-start listener never authored its nested trace")
        (is (some #{nested-op} ops)
            "the observer never received the listener-authored trace")
        ;; PRIMARY lever: delivery IDs must not regress. Under the defect the
        ;; nested trace (higher id) is delivered before run-end (lower id).
        (is (monotonic? ids)
            (str "delivery IDs regressed — a listener-authored trace overtook an "
                 "older still-pending deferred item. Stream: " (pr-str stream)))
        ;; The bead's exact observation: run-start, run-end, THEN the nested trace
        ;; — never run-start, nested, run-end.
        (let [ro (index-of ops :rf.event/run-end)
              no (index-of ops nested-op)]
          (is (and ro no (< ro no))
              (str "the listener-authored trace was delivered before the "
                   "already-captured run-end. Ops in delivery order: "
                   (pr-str ops)))))
      (finally
        (trace/unregister-listener! ::emitter)
        (trace/unregister-listener! ::observer)))))

(deftest ^:requires-debug deferred-batch-outranks-listener-authored-trace-when-integrated
  ;; INTEGRATE seam: a `::trigger` listener reacts to a clean, frameless emit (so
  ;; an OUTER fan-out is in flight and `*fanout-ctx*` is bound) by
  ;; `dispatch-sync`-ing into a frame. That nested drain defers its run batch and,
  ;; on the way out, the flush INTEGRATES the batch into the still-active outer
  ;; schedule. A run-start listener authors a later trace; the same FIFO law must
  ;; hold — it must not overtake the still-pending run-end.
  (let [seen     (atom [])
        fired?   (atom false)
        emitted? (atom false)]
    (rf/reg-event :t6vs3/inner (fn [{:keys [db]} _] {:db db}))
    ;; TRIGGER (registered first): reentrant dispatch-sync from inside the outer
    ;; fan-out — opens the nested drain whose batch integrates on flush.
    (trace/register-listener! ::trigger
      (fn [ev]
        (when (and (= :t6vs3/trigger (:operation ev))
                   (compare-and-set! fired? false true))
          (rf/dispatch-sync [:t6vs3/inner] {:frame :rf/default}))))
    ;; EMITTER: on the INNER event's run-start, author one nested trace.
    (trace/register-listener! ::emitter
      (fn [ev]
        (when (and (= :rf.event/run-start (:operation ev))
                   (= :t6vs3/inner (first (-> ev :tags :rf.event/v)))
                   (compare-and-set! emitted? false true))
          (trace/emit! :info nested-op {}))))
    ;; OBSERVER: record the delivery order of every event.
    (trace/register-listener! ::observer
      (fn [ev] (swap! seen conj [(:operation ev) (:id ev)])))
    (try
      (trace/emit! :info :t6vs3/trigger {})
      (let [stream @seen
            ids    (mapv second stream)
            ops    (mapv first stream)]
        (is (true? @fired?)
            "the trigger listener never opened the nested dispatch-sync")
        (is (true? @emitted?)
            "the inner run-start listener never authored its nested trace")
        (is (some #{nested-op} ops)
            "the observer never received the listener-authored trace")
        ;; PRIMARY lever: delivery IDs must not regress even when the batch is
        ;; integrated into an active outer schedule.
        (is (monotonic? ids)
            (str "delivery IDs regressed on the integrate path — a "
                 "listener-authored trace overtook an older still-pending "
                 "deferred item. Stream: " (pr-str stream)))
        (let [ro (index-of ops :rf.event/run-end)
              no (index-of ops nested-op)]
          (is (and ro no (< ro no))
              (str "the listener-authored trace was delivered before the inner "
                   "drain's already-captured run-end. Ops in delivery order: "
                   (pr-str ops)))))
      (finally
        (trace/unregister-listener! ::trigger)
        (trace/unregister-listener! ::emitter)
        (trace/unregister-listener! ::observer)))))
