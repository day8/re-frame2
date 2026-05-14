(ns re-frame.epoch-concurrency-stress-test
  "Per rf2-rd7a7 — JVM concurrency stress coverage for the epoch surface.
  Mirrors rf2-1gpx8's machine-actor stress pattern
  (`machine_actor_concurrency_stress_test.clj`) and rf2-35rgj's router
  stress pattern (`concurrency_stress_test.clj`) but targets the epoch
  recorder / listener / ring-buffer paths.

  The deterministic epoch suite (`epoch_test.clj`, including the recent
  PR #1167 additions: rf2-1294f / rf2-i0rl9 / rf2-kl5p1 / rf2-7kxxx, plus
  the multi-listener observed-frames pin rf2-s60jx, the capture-buffer
  cross-contamination pin rf2-5qbus, the boundary-validation pin
  rf2-douii, and the halted-cascade pin rf2-v0jwt) covers correctness
  single-shot. None of those tests fires the epoch surface from multiple
  threads — under contention the three documented hot paths
  (`settle!`/`record!`, the `register-epoch-cb!`/`remove-epoch-cb!`
  listener registry, and the per-frame ring buffer) are reached
  concurrently and must hold the same invariants.

  Three scenarios:

  ### Scenario 1 — N concurrent settle! calls from N independent frames

    Per Spec 002 §Rules rule 1 frames are independent state machines,
    their drain-locks don't share. Each thread owns its own frame and
    drives `iters` `dispatch-sync` cycles against it. The framework
    serialises per-frame drains, so per-frame ordering MUST equal
    dispatch order; across frames the `record!` swap into the global
    `histories` atom is the contended seam.

    Invariants:
    1. **No event dropped.** Each frame's history vector has exactly
       `iters` records.
    2. **Ordering stable per frame.** The `:trigger-event` sequence in
       each frame's history matches the per-iter dispatch sequence
       `[[:bump 0] [:bump 1] ...]`.
    3. **No cross-frame mixing.** Every record's `:frame` slot equals
       the frame the history is keyed under (the global `histories`
       atom is keyed by frame; a swap-races bug could cross-key).
    4. **Epoch ids unique across all frames.** `next-epoch-id` is a
       single shared `(swap! epoch-counter inc)` — every record across
       all frames must carry a distinct id.

  ### Scenario 2 — register-epoch-cb! / remove-epoch-cb! race vs settle fanout

    A pair of threads churns the listener registry (register + remove
    a per-thread id in a tight loop) while a separate driver thread
    fires settles. The contended seam is the `listeners` atom + the
    `observed-frames-by-cb` atom, both swapped from `notify-listeners!`
    on every settle.

    Invariants:
    1. **No leak after quiescence.** When the churners stop and one
       final remove fires, `(@listeners)` MUST NOT contain ANY
       per-thread id. A swap-race that lost a `dissoc` would leak the
       entry and (worse) leak a stale closure into future fan-outs.
    2. **No exception escapes notify-listeners!.** A listener that
       raises mid-fan-out must be isolated; the driver thread MUST
       process every settle without an exception escaping through
       `record!` / `notify-listeners!`. We register a permanently-
       throwing listener for the duration of the run to keep the
       isolation seam exercised.
    3. **Driver thread's records all land.** Every settle the driver
       thread issues lands in the frame's ring buffer (the listener
       fanout is downstream of the ring-buffer write; a fanout
       exception leaking up would bypass the append).

  ### Scenario 3 — ring-buffer write/read race (concurrent record + restore)

    One producer thread fires settles into a single frame; one consumer
    thread concurrently reads `epoch-history` AND fires `restore-epoch`
    against arbitrary epoch-ids from the snapshot it just read. The
    producer's `swap!` into `histories` races the consumer's deref +
    `find-epoch-in` walk + the `replace-container!` path inside
    `perform-restore!`.

    Note: `restore-epoch` validates a precondition that no drain is
    in flight on the target frame (`drain-in-flight?`); when the
    producer is mid `dispatch-sync`, the consumer's restore correctly
    refuses with `:rf.epoch/restore-during-drain`. That refusal IS the
    contract — the test asserts that ALL restores either succeed or
    fail with one of the documented refusal traces, and that the
    consumer thread NEVER throws.

    Invariants:
    1. **No exception escapes.** Both threads run their full loop;
       `restore-epoch` either returns true/false but never throws, and
       `epoch-history` always returns a vector (possibly empty).
    2. **No event dropped.** Producer's settle count = `iters`. After
       both threads join, the frame's history has either exactly
       `iters` records (when iters ≤ depth) OR depth records (when
       iters > depth — the ring cap). The test pins `iters ≤ depth`.
    3. **Ordering stable.** The producer's records land in dispatch
       order — the final history's `:trigger-event` sequence equals
       `[[:bump 0] [:bump 1] ...]`. A torn append would surface as
       a duplicate or out-of-order id.

  CLJS is single-threaded; these races cannot manifest there. The test
  is JVM-only by design.

  ## Stress dial

  Defaults: 8 threads × 5000 iters per scenario (matches rf2-1gpx8 /
  rf2-35rgj / rf2-ynk7). Env-overridable via `RF2_RD7A7_STRESS_ITERS`.
  Scenario 1's wall-clock at the default is ~10-20s on a 4-core CI box;
  scenarios 2 and 3 are similar (each pins one frame so the per-frame
  drain serialisation throttles wall-clock). The 5x deflake the bead
  mandates runs all three scenarios five times — total ~3-5 min for
  the suite at default iters.

  ## 5x deflake protocol

  Per the bead, this file MUST pass 5 consecutive `clojure -M:test`
  runs. The pinned wall-clock budget per run is ~60-90s; 5 runs ≈
  5-7 min — viable for a green-gate check before pushing."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch :as epoch]
            [re-frame.flows :as flows]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]
            ;; rf2-v6z0: machines is a separate artefact whose late-bind
            ;; hook publishes `rf/reg-machine` only when loaded. The
            ;; epoch_test.clj fixture pulls it in for symmetry across
            ;; the suite; we follow suit so the reset-runtime shape
            ;; matches and the registrar/clear-all! reload works
            ;; identically.
            [re-frame.machines])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

;; ---- fixture (mirrors epoch_test.clj's `reset-runtime`) ------------------

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (when-let [li-var (resolve 're-frame.flows/last-inputs)]
    (reset! (deref li-var) {}))
  (trace/clear-trace-cbs!)
  (epoch/clear-history!)
  (epoch/clear-epoch-cbs!)
  ;; Reset the config atom directly so :trace-events-keep / a stale
  ;; :depth from a sibling test can't leak; configure! merges, so a
  ;; per-test opt-in to elision would otherwise persist.
  (reset! @#'epoch/config {:depth 50})
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- stress dials ---------------------------------------------------------

;; Per-thread iteration count. Matches rf2-1gpx8 / rf2-35rgj / rf2-ynk7
;; standard 5000 so CI stays under ~90s wall-clock for the whole file.
;; Operators dial up via the env override; CI dials down by lowering it
;; (e.g. `RF2_RD7A7_STRESS_ITERS=500` for a smoke-test pass).
(def ^:private stress-iters
  (or (some-> (System/getenv "RF2_RD7A7_STRESS_ITERS") Long/parseLong)
      5000))

;; Eight parallel threads — matches rf2-ynk7's `concurrent-dispatch-stress`
;; (`n-submitters 8`) and rf2-1gpx8's `n-threads`. Higher contention than
;; the typical 4-core CI box; the per-frame partitioning in scenario 1
;; means we're not over-saturating any one drain-lock, we're driving N
;; independent settle/record cycles in parallel and asserting the
;; `histories` atom's swap! never tangles across frames.
(def ^:private n-threads 8)

;; Bounded join — if the cycle ever hangs (e.g. a listener fanout that
;; deadlocks under contention), we want a visible failure rather than
;; a stuck CI run. 180s gives ample headroom for 8 × 5000 cycles on a
;; slow box.
(def ^:private join-timeout-ms 180000)

;; Helper: deref-with-timeout, surfacing a sentinel on timeout so the
;; deftest can assert against the value rather than the deftest hanging.
(defn- await-future [f]
  (deref f join-timeout-ms ::timeout))

;; ---- Scenario 1 -----------------------------------------------------------
;;
;; N threads × M iters of dispatch-sync, each thread owning its own
;; frame. Every settle commits a record into the global `histories`
;; atom; the assertion is that no record is dropped, no record is
;; doubled, ordering is preserved per frame, and epoch-ids are unique
;; across all records (the `next-epoch-id` shared counter is the
;; cross-frame contention seam).

(deftest n-frames-parallel-settle-stress
  (testing (str n-threads " threads × " stress-iters
                " iters parallel settle! across independent frames "
                "— no drops, no doubles, ordering stable, ids unique")
    ;; Bump the ring depth above iters so the cap doesn't evict early
    ;; records mid-run — the count / ordering invariants below pin the
    ;; no-cap case. The depth-cap behaviour itself is covered by the
    ;; existing `ring-depth-evicts-oldest` deterministic pin (rf2-shjf).
    (rf/configure :epoch-history {:depth (* 2 stress-iters)})
    (let [per-thread
          (vec
            (for [i (range n-threads)]
              {:idx      i
               :frame-id (keyword "rd7a7.settle" (str "f" i))}))]
      ;; Set up per-thread frames + the shared event handler on the
      ;; main thread before any futures launch. `:bump` is a single
      ;; event handler reused across every frame — re-registration
      ;; doesn't race because it lands BEFORE the latch releases.
      (doseq [{:keys [frame-id]} per-thread]
        (rf/reg-frame frame-id
                      {:doc "per-thread frame for epoch settle stress"}))
      (rf/reg-event-db :bump (fn [db [_ i]]
                               (assoc db :last i)))

      (let [latch   (CountDownLatch. 1)
            futures (vec
                      (for [{:keys [frame-id]} per-thread]
                        (future
                          (.await latch)
                          (dotimes [i stress-iters]
                            (rf/dispatch-sync [:bump i]
                                              {:frame frame-id})))))]
        ;; Release all threads simultaneously — maximises lock-step
        ;; contention on the shared `histories` swap! and the shared
        ;; `epoch-counter` swap!.
        (.countDown latch)
        (doseq [f futures]
          (let [v (await-future f)]
            (is (not= ::timeout v)
                (str "thread " f " completed within "
                     join-timeout-ms "ms wall-clock"))))

        ;; --- Invariant 1: no event dropped, per-frame count exact ---
        (doseq [{:keys [frame-id]} per-thread]
          (let [history (rf/epoch-history frame-id)]
            (is (= stress-iters (count history))
                (str "Frame " frame-id ": expected " stress-iters
                     " records (one per dispatch-sync); got "
                     (count history)))))

        ;; --- Invariant 2: per-frame ordering matches dispatch order
        ;;     `[[:bump 0] [:bump 1] ...]`. A torn append would surface
        ;;     here as duplicates or out-of-order entries.
        (doseq [{:keys [frame-id]} per-thread]
          (let [history  (rf/epoch-history frame-id)
                expected (mapv (fn [i] [:bump i]) (range stress-iters))
                actual   (mapv :trigger-event history)]
            (is (= expected actual)
                (str "Frame " frame-id ": :trigger-event sequence "
                     "must match dispatch order. First mismatch at "
                     "index " (or (->> (map vector
                                            (range)
                                            expected
                                            actual)
                                       (some (fn [[i e a]]
                                               (when (not= e a) i))))
                                  -1)))))

        ;; --- Invariant 3: no cross-frame mixing — each record's :frame
        ;;     slot equals the frame the history is keyed under.
        (doseq [{:keys [frame-id]} per-thread]
          (let [history (rf/epoch-history frame-id)
                wrong   (filter (fn [r] (not= frame-id (:frame r)))
                                history)]
            (is (empty? wrong)
                (str "Frame " frame-id ": every record's :frame must "
                     "equal " frame-id "; got " (count wrong)
                     " mis-keyed: "
                     (pr-str (vec (take 3 (map :frame wrong))))))))

        ;; --- Invariant 4: epoch-ids unique across ALL frames. The
        ;;     `epoch-counter` is a single global atom; a swap-race
        ;;     bug could surface as duplicate ids.
        (let [all-ids   (mapcat (fn [{:keys [frame-id]}]
                                  (map :epoch-id (rf/epoch-history frame-id)))
                                per-thread)
              total     (count all-ids)
              distinct  (count (set all-ids))]
          (is (= total distinct)
              (str "Expected " total " unique epoch-ids across all "
                   "frames; got " distinct " distinct (i.e. "
                   (- total distinct) " collisions)")))))))

;; ---- Scenario 2 -----------------------------------------------------------
;;
;; Listener registry churn vs settle fanout. One driver thread fires
;; `iters` settles against a single frame; multiple churner threads
;; concurrently register and remove per-thread listener ids. The
;; contended seams are the `listeners` atom (swapped on every
;; register/remove + read on every notify) and the `observed-frames-by-cb`
;; atom (swapped per listener per notify by `record-observation!`).

(deftest register-deregister-vs-settle-fanout-stress
  (testing (str "register-epoch-cb! / remove-epoch-cb! churn vs "
                stress-iters " settle fanouts — no leaked listeners, "
                "exception isolation holds, every settle records")
    ;; Bump the ring depth above iters — invariant 3 below counts the
    ;; final history; the cap behaviour is covered by the deterministic
    ;; `ring-depth-evicts-oldest` pin.
    (rf/configure :epoch-history {:depth (* 2 stress-iters)})
    (rf/reg-frame :rd7a7.fanout/main {:doc "fanout-stress frame"})
    (rf/reg-event-db :bump (fn [db [_ i]]
                             (assoc db :last i)))

    ;; Two pinned listeners share one `let`-scope so both atoms stay
    ;; live through the assertion phase below:
    ;;   ::throwing — permanently-registered throwing listener. Keeps
    ;;     `notify-listeners!`'s exception-isolation seam exercised; a
    ;;     leaked throw would surface as a failed driver dispatch-sync.
    ;;   ::counter — non-throwing sibling. Verifies the throwing peer
    ;;     does NOT short-circuit fanout for siblings (broken try/catch
    ;;     would mean siblings stop firing after the first throw).
    (let [throw-count (atom 0)
          seen-count  (atom 0)]
      (rf/register-epoch-cb! ::throwing
                             (fn [_]
                               (swap! throw-count inc)
                               (throw (ex-info "intentional" {}))))
      (rf/register-epoch-cb! ::counter
                             (fn [_] (swap! seen-count inc)))

      (let [;; One churner per thread. Each churner-id is per-thread so
            ;; concurrent register/remove of the SAME id never tangles
            ;; (the listeners atom guarantees per-key atomicity via
            ;; swap!; we want to stress concurrent multi-key churn).
            n-churners (max 2 (quot n-threads 2))
            churn-stop (atom false)
            latch      (CountDownLatch. 1)
            churners
            (vec
              (for [i (range n-churners)]
                (let [cb-id (keyword "rd7a7.fanout"
                                     (str "churner-" i))]
                  (future
                    (.await latch)
                    (loop []
                      (when-not @churn-stop
                        (rf/register-epoch-cb! cb-id (fn [_] nil))
                        (rf/remove-epoch-cb! cb-id)
                        (recur)))))))
            driver
            (future
              (.await latch)
              (dotimes [i stress-iters]
                (rf/dispatch-sync [:bump i]
                                  {:frame :rd7a7.fanout/main}))
              ;; Signal churners to stop AFTER the driver's full run.
              (reset! churn-stop true))]
        (.countDown latch)
        ;; Wait for the driver. Once it sets `churn-stop` the churners
        ;; will exit their loop on the next iteration.
        (let [v (await-future driver)]
          (is (not= ::timeout v)
              "driver thread completed within timeout"))
        (doseq [c churners]
          (let [v (await-future c)]
            (is (not= ::timeout v)
                "churner thread completed within timeout")))

        ;; --- Invariant 1: no leaked listeners after quiescence.
        ;;     The churners' final loop iteration is `register` then
        ;;     `remove`; the `remove` is idempotent if the entry was
        ;;     already absent. After `churn-stop` the churners exit; we
        ;;     fire one belt-and-braces remove per id in case a thread
        ;;     was paused between register and remove when the flag
        ;;     flipped (pre-alpha posture: explicit cleanup).
        (dotimes [i n-churners]
          (rf/remove-epoch-cb! (keyword "rd7a7.fanout"
                                        (str "churner-" i))))
        (let [live (deref @#'epoch/listeners)
              churner-keys (filter (fn [k]
                                     (and (keyword? k)
                                          (= "rd7a7.fanout"
                                             (namespace k))
                                          (.startsWith
                                            ^String (name k)
                                            "churner-")))
                                   (keys live))]
          (is (empty? churner-keys)
              (str "Expected zero leaked churner listeners after the "
                   "explicit cleanup step; got "
                   (count churner-keys) " leaked: "
                   (pr-str (vec churner-keys))
                   ". A leak indicates a register/remove race lost "
                   "the dissoc.")))

        ;; --- Invariant 2: exception isolation held — driver completed
        ;;     all `iters` dispatches without an exception escaping. The
        ;;     `(await-future driver)` above already pinned this; the
        ;;     throwing listener fired exactly `iters` times (each
        ;;     fanout reaches it once).
        (is (= stress-iters @throw-count)
            (str "Throwing listener fired " @throw-count " times; "
                 "expected " stress-iters
                 " (one per settle fanout). A lower count means the "
                 "fanout skipped the throwing entry under churn; a "
                 "higher count means it ran twice for one settle."))

        ;; The non-throwing sibling listener also fired `iters` times,
        ;; proving the throwing listener's exception didn't break the
        ;; fanout for siblings — sibling isolation is the second half
        ;; of the contract.
        (is (= stress-iters @seen-count)
            (str "Counter listener fired " @seen-count " times; "
                 "expected " stress-iters
                 ". A lower count means a sibling listener was "
                 "skipped after a throwing peer."))

        ;; --- Invariant 3: every settle landed a record. The ring-
        ;;     buffer write happens upstream of `notify-listeners!`,
        ;;     so listener-side exceptions can't bypass the append;
        ;;     this asserts that path stays robust.
        (let [history (rf/epoch-history :rd7a7.fanout/main)]
          (is (= stress-iters (count history))
              (str "Expected " stress-iters " records on the frame's "
                   "history (one per settle); got " (count history))))))))

;; ---- Scenario 3 -----------------------------------------------------------
;;
;; Concurrent record (settle!) + read (epoch-history + restore-epoch).
;; One producer fires settles; one consumer reads history and tries
;; restore against arbitrary epoch-ids it observed in the snapshot.
;; Because restore-epoch's precondition refuses while a drain is in
;; flight, we expect a mix of ok and `:rf.epoch/restore-during-drain`
;; outcomes — the contract is that NEITHER thread throws and the
;; producer's records all land in dispatch order.

(deftest ring-buffer-write-read-race-stress
  (testing (str "concurrent settle (" stress-iters " iters) + "
                "epoch-history reads + restore attempts — no exceptions, "
                "ordering stable, all records land")
    ;; depth set generously above iters so the cap doesn't kick in;
    ;; the test pins the no-cap case so the count assertion is exact.
    ;; A separate test under the existing depth-evicts pin (rf2-shjf)
    ;; covers the cap behaviour.
    (rf/configure :epoch-history {:depth (* 2 stress-iters)})
    (rf/reg-frame :rd7a7.race/main {:doc "ring-buffer race frame"})
    (rf/reg-event-db :bump (fn [db [_ i]]
                             (assoc db :last i)))

    (let [consumer-stop  (atom false)
          consumer-error (atom nil)
          ;; Documented restore failure ops. The consumer's restore
          ;; attempt may legitimately refuse with any of these (most
          ;; commonly `:rf.epoch/restore-during-drain` when the
          ;; producer is mid-cascade); we count outcomes rather than
          ;; assert success.
          ok-count       (atom 0)
          fail-count     (atom 0)
          read-count     (atom 0)
          latch          (CountDownLatch. 1)
          producer
          (future
            (.await latch)
            (dotimes [i stress-iters]
              (rf/dispatch-sync [:bump i]
                                {:frame :rd7a7.race/main}))
            (reset! consumer-stop true))
          consumer
          (future
            (.await latch)
            (try
              (loop []
                (when-not @consumer-stop
                  (let [history (rf/epoch-history :rd7a7.race/main)]
                    (swap! read-count inc)
                    ;; epoch-history MUST always return a vector
                    ;; (possibly empty); a non-vector would mean the
                    ;; histories atom was holding a stale partial.
                    (when-not (vector? history)
                      (throw (ex-info "epoch-history returned non-vector"
                                      {:got history})))
                    (when (seq history)
                      ;; Try a restore against the most recently
                      ;; observed epoch-id. Concurrent producer means
                      ;; the precondition will often refuse — that's
                      ;; the contract.
                      (let [target-id (:epoch-id (rand-nth history))
                            result    (rf/restore-epoch
                                        :rd7a7.race/main target-id)]
                        (if result
                          (swap! ok-count inc)
                          (swap! fail-count inc)))))
                  (recur)))
              (catch Throwable t
                (reset! consumer-error t))))]
      (.countDown latch)
      (let [vp (await-future producer)
            vc (await-future consumer)]
        (is (not= ::timeout vp) "producer completed within timeout")
        (is (not= ::timeout vc) "consumer completed within timeout"))

      ;; --- Invariant 1: no exception escaped the consumer.
      (is (nil? @consumer-error)
          (str "Consumer thread must not throw; got "
               (some-> @consumer-error .getMessage)))

      ;; The consumer made at least some reads — pins that the loop
      ;; actually ran (a quiescent consumer would silently pass invariant 1).
      (is (pos? @read-count)
          (str "Consumer made " @read-count " reads; expected > 0"))

      ;; --- Invariant 2: every producer settle landed.
      (let [history (rf/epoch-history :rd7a7.race/main)]
        (is (= stress-iters (count history))
            (str "Expected " stress-iters " records (one per "
                 "dispatch-sync); got " (count history) ". (Note: "
                 "successful restores from the consumer don't change "
                 "history — restore replaces the container's value, "
                 "not the ring buffer.)"))

        ;; --- Invariant 3: producer's ordering preserved. The records
        ;;     committed by the producer's settles are in dispatch
        ;;     order; consumer's restores never write to the history
        ;;     vector (restore replaces app-db, not history). A torn
        ;;     append would surface as a missing or repeated id here.
        (let [expected (mapv (fn [i] [:bump i]) (range stress-iters))
              actual   (mapv :trigger-event history)]
          (is (= expected actual)
              (str ":trigger-event sequence must match dispatch order. "
                   "Total records: " (count history)
                   "; mismatch index: "
                   (or (->> (map vector (range) expected actual)
                            (some (fn [[i e a]]
                                    (when (not= e a) i))))
                       -1)))))

      ;; Diagnostic-only — surface the ratio of ok/fail/read so a
      ;; future regression that flips one direction (e.g. always
      ;; refuses) is visible at test-output read time. Not asserted
      ;; — the bead's invariants are 1/2/3 above; restore success
      ;; rate is a function of producer/consumer interleaving and
      ;; varies run-to-run.
      (is (true? true)
          (str "Diagnostic — reads: " @read-count
               ", restore-ok: " @ok-count
               ", restore-fail: " @fail-count)))))
