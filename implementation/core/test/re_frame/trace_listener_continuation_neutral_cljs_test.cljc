(ns re-frame.trace-listener-continuation-neutral-cljs-test
  "A public trace listener's BODY runs under a neutral continuation scope, while
  the delivery loop around it keeps checking the caller's exact continuation
  predicate before and after each callback.

  A framework lifecycle emit fenced to incarnation A
  (`re-frame.trace/call-with-continuation-predicate` bound to
  `rf.frame/event-continuation-live?`, as a cold `reg-flow` does) fans out inside
  that binding. A listener that destroys A and creates same-id B with
  `:initial-events` must still get B seeded: inheriting A's now-false predicate
  would drop the seed at `build-envelope` and leave B live with `{}`, beyond the
  reach of a later re-ensure. The same holds for the listener's nested work in an
  unrelated frame. The loop itself must still suppress A's remaining listeners
  once A is gone — and must not suppress them while A is live.

  Synchronous on one thread; the listeners live in a small array-map, so the
  first registered fans out first on both hosts."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core                 :as rf]
            [re-frame.frame                :as rf.frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support         :as rf.test-support]
            [re-frame.trace                :as rf.trace]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- reg-test-events! []
  ;; `:seed-count` makes a replayed seed visible.
  (rf/reg-event :trace.neutral/seed
    (fn [{:keys [db]} _]
      {:db (-> db (assoc :seeded :ok) (update :seed-count (fnil inc 0)))}))
  (rf/reg-event :trace.neutral/mark
    (fn [{:keys [db]} _] {:db (assoc db :marked true)})))

(defn- fire-fenced-emit!
  "Emit `:rf.test/fenced-emit` fenced to `[frame-id token]`'s exact incarnation."
  [frame-id token]
  (rf.trace/call-with-continuation-predicate
    #(rf.frame/event-continuation-live? frame-id token)
    (fn []
      (rf.trace/emit! :rf.registry :rf.test/fenced-emit {:frame frame-id}))))

;; Every deftest is `^:requires-debug`: the suite drives the dev trace end to
;; end (see scripts/test-core-prod-gate.sh).

(deftest ^:requires-debug listener-body-runs-neutral-while-fence-suppresses-a-tail
  (reg-test-events!)
  (let [a-id       :trace.neutral/subject
        c-id       :trace.neutral/unrelated
        b-db       (atom ::unset)
        later-hits (atom 0)]
    (rf/make-frame {:id c-id})
    (rf/make-frame {:id a-id})
    (let [a-token (rf.frame/frame-incarnation-token a-id)]
      ;; First listener: destroys A, creates same-id B with a seed, then does
      ;; nested work in C — all listener-body work, so all must run neutral.
      (rf.trace.tooling/register-listener! ::destroyer
        (fn [ev]
          (when (= :rf.test/fenced-emit (:operation ev))
            (rf.frame/destroy-frame! a-id)
            (rf/make-frame {:id a-id :initial-events [[:trace.neutral/seed]]})
            (reset! b-db (rf/app-db-value a-id))
            (rf/dispatch-sync [:trace.neutral/mark] {:frame c-id}))))
      (rf.trace.tooling/register-listener! ::later-a
        (fn [ev]
          (when (= :rf.test/fenced-emit (:operation ev))
            (swap! later-hits inc))))
      (try
        (fire-fenced-emit! a-id a-token)
        (is (= {:seeded :ok :seed-count 1} @b-db)
            "B's seed ran inside the listener body (A's predicate would leave {})")
        (is (= {:marked true} (rf/app-db-value c-id))
            "the listener's nested dispatch into an unrelated frame ran")
        (is (zero? @later-hits)
            "the loop kept A's predicate: the next listener was suppressed once A died")
        (rf/make-frame {:id a-id :initial-events [[:trace.neutral/seed]]})
        (is (= {:seeded :ok :seed-count 1} (rf/app-db-value a-id))
            "B's seed ran exactly once, and re-ensuring live B does not replay it")
        (finally
          (rf.trace.tooling/unregister-listener! ::destroyer)
          (rf.trace.tooling/unregister-listener! ::later-a))))))

(deftest ^:requires-debug non-destroying-listener-does-not-over-suppress-subsequent-listener
  ;; With A left live, the next listener must still receive A's fenced event
  ;; after the first listener's nested dispatch.
  (reg-test-events!)
  (let [a-id      :trace.neutral/live-subject
        c-id      :trace.neutral/live-unrelated
        later-hit (atom 0)]
    (rf/make-frame {:id c-id})
    (rf/make-frame {:id a-id})
    (let [a-token (rf.frame/frame-incarnation-token a-id)]
      (rf.trace.tooling/register-listener! ::observer
        (fn [ev]
          (when (= :rf.test/fenced-emit (:operation ev))
            (rf/dispatch-sync [:trace.neutral/mark] {:frame c-id}))))
      (rf.trace.tooling/register-listener! ::subsequent
        (fn [ev]
          (when (= :rf.test/fenced-emit (:operation ev))
            (swap! later-hit inc))))
      (try
        (fire-fenced-emit! a-id a-token)
        (is (= [1 {:marked true}] [@later-hit (rf/app-db-value c-id)])
            "the first listener's nested dispatch ran and the next listener still
             received A's live event")
        (finally
          (rf.trace.tooling/unregister-listener! ::observer)
          (rf.trace.tooling/unregister-listener! ::subsequent))))))
