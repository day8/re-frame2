(ns re-frame.ui.frame-plan-preflight-race-jvm-test
  "JVM preflight-executor race coverage (rf2-vxgfnd.35).

  `execute-frame-plans!` decides in phase 1 (reads `installed-plans` + frame
  liveness) and records in phase 2. Before rf2-vxgfnd.35 nothing serialized
  two concurrent JVM runs: both could decide `:install` for one frame-id, the
  second `make-frame` surgically overwrote the first, and the last record won
  — the cross-root `:rf.error/frame-payload-conflict` the disposition table
  exists to raise was silently missed (and a run arriving mid-install could
  misread the half-installed frame as boot-authoritative). Since #5720
  `ui.test/render` drives this executor on the JVM, so parallel JVM test
  execution reaches the window.

  Two arms, mirroring `frame-plan-publication-race-jvm-test`'s latch shape —
  no sleeps or scheduler guesses on the deterministic arm:

  - LATCH arm: root A pauses INSIDE its install (an `:initial-events` handler
    blocks on a CountDownLatch — after `make-frame` made the frame live,
    before A's record publishes: exactly the decide/record window). Root B
    arrives mid-run; B must be serialized BEHIND A's whole run and then
    surface the ratified cross-root conflict naming root A's COMPLETED
    install — never an adopt / boot-authority misdiagnosis of the
    half-installed frame, never a silent overwrite.
  - BARRIER stress arm: both threads release from one CyclicBarrier straight
    into `execute-frame-plans!` for the same fresh frame-id with differing
    fingerprints, sweeping real scheduler interleavings. Every rep: exactly
    one winner; the loser conflicts against the WINNER'S record; the
    surviving record is the winner's.

  CLJS cannot interleave two synchronous preflights (single-threaded — the
  serialization is JVM-only by construction), so this fixture is JVM-only."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.ui.frames :as frames])
  (:import [java.util.concurrent CountDownLatch CyclicBarrier TimeUnit]))

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

;; ---------------------------------------------------------------------------
;; LATCH arm — deterministic: root B arrives while root A is mid-install
;; ---------------------------------------------------------------------------

(deftest concurrent-install-serializes-and-the-loser-sees-the-winners-record
  (let [fid     :preflight-race/frame
        entered (CountDownLatch. 1)
        release (CountDownLatch. 1)]
    ;; Pause root A INSIDE phase 2: `make-frame` has made the frame LIVE and
    ;; is draining `:initial-events` synchronously; A's install record is NOT
    ;; yet published. This holds A's whole decide+record run open.
    (rf/reg-event ::pause
      (fn [{:keys [db]} _]
        (.countDown entered)
        (.await release 10 TimeUnit/SECONDS)
        {:db db}))
    (try
      (let [a (future (run-plan :root/a
                                (plan fid "cf1-aaaaaaaa"
                                      {:initial-events [[::pause]]})))]
        (is (.await entered 10 TimeUnit/SECONDS)
            "root A is paused mid-install (frame live, record unpublished)")
        (let [b (future (run-plan :root/b
                                  (plan fid "cf1-bbbbbbbb"
                                        {:initial-events
                                         [[:rf/set-db {:from :b}]]})))]
          ;; Serialization probe: B must be BLOCKED behind A's in-flight run.
          ;; Unserialized, B returns promptly here — it sees A's live frame
          ;; with no record and misdiagnoses it as boot-authoritative.
          (is (= ::pending (deref b 250 ::pending))
              "root B is serialized behind root A's open decide+record run")
          (.countDown release)
          (is (= ::won (deref a 10000 ::timeout))
              "root A's install completes and wins")
          (let [outcome (deref b 10000 ::timeout)]
            (is (map? outcome)
                "root B fails loud — never a silent overwrite")
            (is (= :rf.error/frame-payload-conflict (:rf.error/id outcome))
                "the loser surfaces the ratified conflict outcome")
            (is (= :root/a (get-in outcome [:installed :installed-by]))
                (str "the conflict names root A's COMPLETED install — not a "
                     "boot-authority misdiagnosis of the half-installed frame"))
            (is (= "cf1-aaaaaaaa"
                   (get-in outcome [:installed :config-fingerprint]))
                "the conflict carries the winner's recorded fingerprint")
            (is (= :root/b (get-in outcome [:arriving :root-id]))
                "the conflict scopes the failure to the arriving root"))
          (is (= {:config-fingerprint "cf1-aaaaaaaa" :installed-by :root/a}
                 (frames/installed-plan-entry fid))
              "exactly one install wins — the surviving record is root A's")
          (is (some? (frame/frame fid))
              "the winner's frame is live and untouched (06 §2 failure scoping)")))
      (finally
        ;; Never strand root A's drain if an assertion or setup step fails.
        (.countDown release)))))

;; ---------------------------------------------------------------------------
;; BARRIER stress arm — scheduler-swept: exactly one winner per frame-id
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
      (is (= :rf.error/frame-payload-conflict (:rf.error/id loser-res))
          (str "rep " i ": the loser surfaces the ratified conflict"))
      (is (= winner-root (get-in loser-res [:installed :installed-by]))
          (str "rep " i ": the loser conflicts against the WINNER'S completed "
               "record — never an adopt/boot-authority misread of a mid-run "
               "install"))
      (is (= {:config-fingerprint winner-fp :installed-by winner-root}
             (frames/installed-plan-entry fid))
          (str "rep " i ": the surviving record is the winner's, unclobbered")))))
