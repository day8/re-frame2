(ns re-frame.ui.frame-plan-preflight-race-jvm-test
  "JVM preflight-executor race coverage (rf2-vxgfnd.35).

  `execute-frame-plans!` decides in phase 1 (reads `installed-plans` + frame
  liveness) and records in phase 2. Before rf2-vxgfnd.35 nothing serialized
  two concurrent JVM runs: both could decide `:install` for one frame-id, the
  second `make-frame` surgically overwrote the first, and the last record won
  — the cross-root `:rf.error/frame-payload-conflict` the disposition table
  exists to raise was silently missed (and a run arriving mid-install could
  misread the half-installed frame as boot-authoritative).

  Current callers are the CLJS client host and direct JVM executor tests such
  as this fixture. `ui.test/render` uses its own exclusive, must-create install
  path; it does NOT call this executor. A future S5 JVM/SSR host may become a
  real JVM caller, but this fixture makes no reachability claim for it today.

  Two arms, mirroring `frame-plan-publication-race-jvm-test`'s latch shape —
  no sleeps or scheduler guesses on the deterministic arm:

  - LATCH arm (the deterministic mutation-killing proof): root A pauses INSIDE
    its install (an `:initial-events` handler blocks on a CountDownLatch — after
    `make-frame` made the frame live, before A's record publishes: exactly the
    decide/record window). Root B's dedicated thread then signals at the call
    boundary. The harness does not release A until B has causally either
    contended at admission or completed. A completed B is valid only when the
    ruled per-ID coordination rejects it with
    `:rf.error/frame-preflight-overlap`; today's serialized implementation lets
    B re-decide after A completes and surface the ratified payload conflict.
    An uncoordinated B instead completes early with a boot-authority
    misdiagnosis, so a delayed scheduler cannot make that mutation pass.
  - BARRIER stress arm: both threads release from one CyclicBarrier straight
    into `execute-frame-plans!` for the same fresh frame-id with differing
    fingerprints, sweeping real scheduler interleavings. This arm is
    supplemental probabilistic breadth, NOT the deterministic counterexample.
    Every rep: exactly one winner; the loser conflicts against the WINNER'S
    record; the surviving record is the winner's.

  This fixture is JVM-only because its counterexample is two OS threads. The
  CLJS client remains a real executor caller; same-thread re-entrant overlap is
  owned by the companion authority/reservation coverage."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.ui.frames :as frames])
  (:import [java.lang Thread$State]
           [java.util.concurrent CountDownLatch CyclicBarrier TimeUnit]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter
                                            :ambient-frame nil})
  (fn [t] (frames/reset-installed-plans!) (t) (frames/reset-installed-plans!)))

(defn- plan [frame-id fingerprint config]
  {:frame-id frame-id :config config :config-fingerprint fingerprint})

(defn- run-plan
  "Run one root's single-plan preflight: `::won` on success, the thrown
  ex-data map on the canonical conflict."
  [root-id p]
  (try
    (frames/execute-frame-plans! root-id [p])
    ::won
    (catch clojure.lang.ExceptionInfo e
      (ex-data e))))

(defn- start-contender
  "Start a named, inspectable thread for the competing call. `at-admission`
  fires as the last harness action before invoking the executor; `outcome`
  captures even an unexpected throwable so cleanup never waits on an
  undelivered promise."
  [at-admission root-id p]
  (let [outcome (promise)
        thread  (Thread.
                 (fn []
                   (.countDown at-admission)
                   (deliver outcome
                            (try
                              (run-plan root-id p)
                              (catch Exception e e))))
                 "frame-plan-preflight-contender")]
    (.setDaemon thread true)
    (.start thread)
    {:thread thread :outcome outcome}))

(defn- await-admission-evidence
  "Wait for causal evidence about a contender whose call-boundary latch fired.

  `:completed` is the ruled fail-fast reservation shape. `:contended` is the
  current JVM serializer shape: the dedicated thread has attempted monitor
  admission while A owns it. The deadline is a failing harness watchdog only;
  elapsed time is never returned as successful evidence. No sleep participates
  in the proof."
  [^Thread thread outcome]
  (let [deadline (+ (System/nanoTime)
                    (.toNanos TimeUnit/SECONDS 10))]
    (loop []
      (cond
        (realized? outcome)
        {:kind :completed :outcome @outcome}

        (= Thread$State/BLOCKED (.getState thread))
        {:kind :contended}

        (>= (System/nanoTime) deadline)
        {:kind :watchdog-timeout}

        :else
        (do
          (Thread/yield)
          (recur))))))

(defn- stop-thread!
  "Bounded cleanup for the dedicated contender; interrupt only after its
  ordinary release/join window so a failed assertion cannot strand the JVM."
  [^Thread thread]
  (when thread
    (.join thread 10000)
    (when (.isAlive thread)
      (.interrupt thread)
      (.join thread 10000))))

;; ---------------------------------------------------------------------------
;; LATCH arm — deterministic: root B attempts admission while A is mid-install
;; ---------------------------------------------------------------------------

(deftest competing-install-cannot-complete-or-last-write-with-an-open-owner
  (let [fid       :preflight-race/frame
        entered   (CountDownLatch. 1)
        release   (CountDownLatch. 1)
        a-ref     (atom nil)
        contender (atom nil)]
    ;; Pause root A INSIDE phase 2: `make-frame` has made the frame LIVE and
    ;; is draining `:initial-events` synchronously; A's install record is NOT
    ;; yet published. This holds A's whole decide+record run open.
    (rf/reg-event ::pause
      (fn [{:keys [db]} _]
        (.countDown entered)
        (when-not (.await release 10 TimeUnit/SECONDS)
          (throw (ex-info "timed out awaiting deterministic race release"
                          {:rf.error/id ::release-timeout})))
        {:db db}))
    (try
      (let [a (future (run-plan :root/a
                                (plan fid "cf1-aaaaaaaa"
                                      {:initial-events [[::pause]]})))
            _ (reset! a-ref a)]
        (is (.await entered 10 TimeUnit/SECONDS)
            "root A is paused mid-install (frame live, record unpublished)")
        (let [at-admission (CountDownLatch. 1)
              b            (start-contender
                            at-admission :root/b
                            (plan fid "cf1-bbbbbbbb"
                                  {:initial-events
                                   [[:rf/set-db {:from :b}]]}))
              _            (reset! contender b)]
          (is (.await at-admission 10 TimeUnit/SECONDS)
              "root B reached the executor admission boundary while A is open")
          ;; Do not release A merely because a deref timed out. Wait until B
          ;; has causally attempted today's admission OR has completed through
          ;; the future fail-fast per-ID reservation. With coordination removed,
          ;; B completes here with the wrong boot-authority diagnosis.
          (let [{:keys [kind outcome] :as early}
                (await-admission-evidence (:thread b) (:outcome b))]
            (is (not= :watchdog-timeout kind)
                (str "root B produced causal admission evidence: " early))
            (when (= :completed kind)
              (is (map? outcome)
                  "a fail-fast contention result is typed data")
              (is (= :rf.error/frame-preflight-overlap (:rf.error/id outcome))
                  (str "only the ruled per-ID overlap rejection may complete "
                       "while root A's run is open; got " (pr-str outcome))))

          (.countDown release)
          (is (= ::won (deref a 10000 ::timeout))
              "root A's install completes and wins")
            (let [final-outcome (deref (:outcome b) 10000 ::timeout)]
              (if (= :contended kind)
                (do
                  (is (map? final-outcome)
                      "the serialized loser fails loud — never a silent overwrite")
                  (is (= :rf.error/frame-payload-conflict
                         (:rf.error/id final-outcome))
                      "the serialized loser surfaces the ratified conflict")
                  (is (= :root/a
                         (get-in final-outcome [:installed :installed-by]))
                      (str "the conflict names root A's COMPLETED install — not "
                           "a boot-authority misdiagnosis of the half-installed frame"))
                  (is (= "cf1-aaaaaaaa"
                         (get-in final-outcome
                                 [:installed :config-fingerprint]))
                      "the conflict carries the winner's recorded fingerprint")
                  (is (= :root/b (get-in final-outcome [:arriving :root-id]))
                      "the conflict scopes the failure to the arriving root"))
                (is (= outcome final-outcome)
                    "the fail-fast overlap result is final and cannot last-write")))
          (is (= {:config-fingerprint "cf1-aaaaaaaa" :installed-by :root/a}
                 (frames/installed-plan-entry fid))
              "exactly one install wins — the surviving record is root A's")
          (is (some? (frame/frame fid))
              "the winner's frame is live and untouched (06 §2 failure scoping)"))))
      (finally
        ;; Never strand root A's drain if an assertion or setup step fails.
        (.countDown release)
        (when-let [a @a-ref]
          (deref a 10000 nil)
          (future-cancel a))
        (stop-thread! (:thread @contender))))))

;; ---------------------------------------------------------------------------
;; BARRIER stress arm — supplemental scheduler breadth, not the causal proof
;; ---------------------------------------------------------------------------

(deftest install-race-stress-exactly-one-winner-never-a-silent-overwrite
  (dotimes [i 50]
    (let [fid     (keyword "preflight-race" (str "stress-" i))
          barrier (CyclicBarrier. 2)
          run     (fn [root-id fingerprint]
                    (future
                      (.await barrier 10 TimeUnit/SECONDS)
                      (run-plan root-id (plan fid fingerprint {}))))
          a       (run :root/a "cf1-aaaaaaaa")
          b       (run :root/b "cf1-bbbbbbbb")
          ra      (deref a 10000 ::timeout)
          rb      (deref b 10000 ::timeout)
          [winner-root winner-fp winner-res loser-res]
          (if (= ::won ra)
            [:root/a "cf1-aaaaaaaa" ra rb]
            [:root/b "cf1-bbbbbbbb" rb ra])]
      (is (= ::won winner-res)
          (str "rep " i ": one thread's install wins"))
      (is (map? loser-res)
          (str "rep " i ": the loser fails loud — two silent wins is the "
               "last-record-wins clobber"))
      (is (contains? #{:rf.error/frame-payload-conflict
                       :rf.error/frame-preflight-overlap}
                     (:rf.error/id loser-res))
          (str "rep " i ": the loser is rejected by completed authority or "
               "the ruled in-flight reservation"))
      (when (= :rf.error/frame-payload-conflict (:rf.error/id loser-res))
        (is (= winner-root (get-in loser-res [:installed :installed-by]))
            (str "rep " i ": a post-settlement loser conflicts against the "
                 "WINNER'S completed record — never an adopt/boot-authority "
                 "misread of a mid-run install")))
      (is (= {:config-fingerprint winner-fp :installed-by winner-root}
             (frames/installed-plan-entry fid))
          (str "rep " i ": the surviving record is the winner's, unclobbered")))))
