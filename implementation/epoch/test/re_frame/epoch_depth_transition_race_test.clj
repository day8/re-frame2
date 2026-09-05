(ns re-frame.epoch-depth-transition-race-test
  "rf2-f8wu (post-merge audit) — the `:depth` transition is ONE atomic step
  against the stores it bounds.

  ## The invariant

  `(rf/configure! {:epoch-history {:depth N}})` promises that *once
  `configure!` returns, every ring respects the accepted depth* (Tool-Pair
  §Time-travel \"Bounded history\", and its depth-0 bullet: `epoch-history`
  returns `[]`). The single-threaded half of that promise is pinned in
  `re-frame.epoch-test`. This namespace pins the half a single thread cannot
  reach.

  ## The defect these tests close

  The depth reduction and the ring append were two independent sequences over
  three atoms:

      record!         reads `(depth)`, THEN swaps `histories`
      merge-config!   swaps `config`, THEN prunes `histories`,
                      THEN reconciles the anchors

  Nothing held the stores still between those steps, so a writer that had
  already captured the PREVIOUS depth could commit its append after
  `configure!` had returned. The excess record was then queryable through
  `epoch-history` / `projected-history` and — for a full runtime record —
  a live `restore-epoch!` / `replay-epoch!` target: exactly the two failures
  the original bead named, re-entered through a door the boundary fix left
  open.

  Config-swap-before-prune made the escape TRANSIENT for a positive depth (the
  next append re-caps the ring) but PERMANENT at depth 0, because `record!`
  skips `append-record` entirely at depth 0 — no later append ever arrives to
  repair it. \"Transient\" is also the wrong bar for a positive reduction: the
  promise is about the state at `configure!`'s return, not about some later
  event.

  The anchors have the same shape. `enforce-depth!` computed its retained-id
  set from the pruned snapshot and reconciled `last-settled-epoch` in a
  SEPARATE swap, so a record committed at that seam could have its own,
  correct anchor discarded as unretained.

  ## How these tests establish it deterministically

  A concurrency defect proved by racing threads is a defect proved sometimes.
  Each test below PLACES the interleaving rather than hoping for it: a writer
  thread is parked inside `record!` at the exact point the depth has been
  captured and the ring not yet touched, and the configuring thread runs
  against that held position.

  The seam is `re-frame.epoch.state/trace-events-keep`, which `record!` calls
  exactly once — on the line after it captures the depth and before its
  `swap!`. It is the only call between the two, which is what makes it an
  exact seam rather than an approximate one.

  TOOTH: drop the retention serialization from `record!` or from
  `merge-config!` and both deftests below fail — the parked writer's append
  lands after `configure!` has already published its result."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Side-effect require: publishes the `:epoch/*` late-bind hooks
            ;; the core facade's epoch surface resolves through.
            [re-frame.epoch]
            [re-frame.epoch.state :as epoch-state]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

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
        real-keep epoch-state/trace-events-keep
        writer    (future
                    (with-redefs [epoch-state/trace-events-keep
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

  The wait is a determinism device for the UNFIXED code, not an assertion.
  Without it the configure could still be ahead of its config swap when the
  test releases the writer, and the interleaving under test would simply not
  have happened — a green that proved nothing. With the retention
  serialization in place the configure is blocked instead, so the loop runs
  out its budget and the test proceeds to release the writer."
  [frame-id depth]
  (let [configurer (future (rf/configure! {:epoch-history {:depth depth}})
                           :configured)
        published? (fn []
                     (and (= depth (epoch-state/depth))
                          (<= (count (rf/epoch-history frame-id)) depth)))]
    (loop [remaining 60]
      (when (and (pos? remaining) (not (published?)))
        (Thread/sleep 5)
        (recur (dec remaining))))
    configurer))

(defn- epoch-ids [frame-id]
  (mapv :epoch-id (rf/epoch-history frame-id)))

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
    (is (= 1 (count (rf/epoch-history :test/main))) "precondition: one record")

    (let [{:keys [writer release!]} (park-writer-inside-record!
                                      :test/main [:inc])
          configurer                (configure-depth-on-another-thread!
                                      :test/main 0)]
      (release!)
      (is (= :committed  (deref writer join-ms :timeout))  "the writer finished")
      (is (= :configured (deref configurer join-ms :timeout)) "configure! finished")

      (is (= [] (rf/epoch-history :test/main))
          "the parked append did not escape the transition")
      (is (= [] (rf/projected-history :test/main))
          "and it is not reachable through the off-box projection either")
      (is (nil? (epoch-state/last-settled-epoch-id :test/main))
          "no back-fill anchor survives naming a record the ring does not hold")
      (is (= {:n 2} (rf/app-db-value :test/main))
          "the frame itself still settled the event — only its RETENTION was
           refused"))))

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
    (is (= 3 (count (rf/epoch-history :test/main))) "precondition: three records")

    (let [{:keys [writer release!]} (park-writer-inside-record!
                                      :test/main [:inc])
          configurer                (configure-depth-on-another-thread!
                                      :test/main 1)]
      (release!)
      (is (= :committed  (deref writer join-ms :timeout))  "the writer finished")
      (is (= :configured (deref configurer join-ms :timeout)) "configure! finished")

      (let [ids    (epoch-ids :test/main)
            anchor (epoch-state/last-settled-epoch-id :test/main)]
        (is (= 1 (count ids))
            "the ring holds the accepted depth, not the one the writer captured")
        (is (= {:n 3} (:db-after (last (rf/epoch-history :test/main))))
            "and what it holds is the NEWEST record — the one that raced in")
        (is (some? anchor)
            "the concurrent record's own anchor was not discarded as unretained")
        (is (= (last ids) anchor)
            "the anchor names a record the ring still holds")))))
