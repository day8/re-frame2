(ns re-frame.epoch-depth-transition-race-test
  "The `:depth` transition is ONE atomic step against the stores it bounds:
  once `(rf/configure! {:epoch-history {:depth N}})` returns, every ring
  respects N (Tool-Pair \"Bounded history\"). The single-threaded half is pinned
  in `re-frame.epoch-test`; this namespace pins the concurrent half.

  `record!` reads the depth and THEN appends; `merge-config!` swaps the config,
  THEN prunes, THEN reconciles the back-fill anchors. Without the retention
  serialization, a writer holding the PREVIOUS depth could append after
  `configure!` returned — queryable and restorable, and permanent at depth 0,
  where no later append re-prunes.

  Each test parks a writer inside `record!` at the seam between its depth read
  and its append — `trace-events-keep` is the only call between them — and runs
  the transition against that held position."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Side-effect require: publishes the `:epoch/*` late-bind hooks
            ;; the core facade's epoch surface resolves through.
            [re-frame.epoch]
            [re-frame.epoch.state :as rf.epoch.state]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---- helpers ---------------------------------------------------------------

(def ^:private join-ms
  "Generous upper bound for joining a helper thread. Nothing here waits on it
  in the passing case; it exists so a regression HANGS the one deftest rather
  than the whole suite."
  10000)

(defn- park-writer-inside-record!
  "Dispatch `event` into `frame-id` on a background thread and park that
  thread INSIDE `record!` — after it has read the depth, before it swaps the
  ring.

  Returns `{:writer <future> :release! <fn>}`. The caller drives the
  transition it wants to test against the parked position, then calls
  `release!` and joins `:writer`.

  The park is installed with `with-redefs`, which is process-global rather
  than thread-local — deliberate, since the point is to hold the ONE writer
  the test starts. No other thread in these deftests records, so nothing else
  can reach the redefinition."
  [frame-id event]
  (let [reached   (promise)
        release   (promise)
        real-keep rf.epoch.state/trace-events-keep
        writer    (future
                    (with-redefs [rf.epoch.state/trace-events-keep
                                  (fn []
                                    (deliver reached true)
                                    (deref release join-ms :timeout)
                                    (real-keep))]
                      (rf/dispatch-sync event {:frame frame-id})
                      :committed))]
    (assert (true? (deref reached join-ms :timeout))
            "writer never reached the depth seam inside record!")
    {:writer   writer
     :release! (fn [] (deliver release true))}))

(defn- configure-depth-on-another-thread!
  "Run `(rf/configure! {:epoch-history {:depth depth}})` on its own thread and
  return its future, having first waited for it to reach a settled position:
  either it PUBLISHED its result, or it is waiting on the parked writer.

  The wait is a determinism device for code WITHOUT the retention
  serialization, not an assertion.
  Without it the configure could still be ahead of its config swap when the
  test releases the writer, and the interleaving under test would simply not
  have happened — a green that proved nothing. With the retention
  serialization in place the configure is blocked instead, so the loop runs
  out its budget and the test proceeds to release the writer."
  [frame-id depth]
  (let [configurer (future (rf/configure! {:epoch-history {:depth depth}})
                           :configured)
        published? (fn []
                     (and (= depth (rf.epoch.state/depth))
                          (<= (count (rf/epoch-history frame-id)) depth)))]
    (loop [remaining 60]
      (when (and (pos? remaining) (not (published?)))
        (Thread/sleep 5)
        (recur (dec remaining))))
    configurer))

;; ---- depth 0: the permanent escape ----------------------------------------

(deftest an-in-flight-append-cannot-escape-a-depth-zero-transition
  (testing "a record! that captured the previous depth cannot land after
            configure! returned — at depth 0 nothing later re-prunes, so an
            escape is PERMANENT, queryable and time-travellable"
    (rf/configure! {:epoch-history {:depth 5}})
    (rf/make-frame {:id :test/main})
    (rf/reg-event :seed (fn [_ _] {:db {:n 1}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))

    (rf/dispatch-sync [:seed] {:frame :test/main})

    (let [{:keys [writer release!]} (park-writer-inside-record!
                                      :test/main [:inc])
          configurer                (configure-depth-on-another-thread!
                                      :test/main 0)]
      (release!)
      (is (= [:committed :configured]
             [(deref writer join-ms :timeout) (deref configurer join-ms :timeout)])
          "the writer and configure! both finished")
      (is (= [] (rf/epoch-history :test/main))
          "the parked append did not escape the transition")
      (is (nil? (rf.epoch.state/last-settled-epoch-id :test/main))
          "no back-fill anchor survives naming a record the ring does not hold"))))

;; ---- positive reduction: the accepted cap, and anchor coherence -----------

(deftest an-in-flight-append-cannot-overshoot-a-positive-reduction
  (testing "the ring respects the ACCEPTED depth once configure! returns, even
            when a writer holding the previous depth commits across the
            transition — and the surviving anchor names a retained record"
    (rf/configure! {:epoch-history {:depth 10}})
    (rf/make-frame {:id :test/main})
    (rf/reg-event :seed (fn [_ _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))

    (rf/dispatch-sync [:seed] {:frame :test/main})
    (rf/dispatch-sync [:inc] {:frame :test/main})
    (rf/dispatch-sync [:inc] {:frame :test/main})

    (let [{:keys [writer release!]} (park-writer-inside-record!
                                      :test/main [:inc])
          configurer                (configure-depth-on-another-thread!
                                      :test/main 1)]
      (release!)
      (is (= [:committed :configured]
             [(deref writer join-ms :timeout) (deref configurer join-ms :timeout)])
          "the writer and configure! both finished")
      (let [history (rf/epoch-history :test/main)]
        (is (= [{:n 3}] (mapv :db-after history))
            "the ring holds the accepted depth, and what it holds is the NEWEST
             record — the one that raced in")
        (is (= (:epoch-id (first history))
               (rf.epoch.state/last-settled-epoch-id :test/main))
            "the concurrent record's own anchor survived and names a retained record")))))
