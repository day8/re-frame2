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
  (`settle!`/`record!`, the `register-epoch-listener!`/`unregister-epoch-listener!`
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

  ### Scenario 2 — register-epoch-listener! / unregister-epoch-listener! race vs settle fanout

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
    thread concurrently reads `epoch-history` AND fires `restore-epoch!`
    against arbitrary epoch-ids from the snapshot it just read. The
    producer's `swap!` into `histories` races the consumer's deref +
    `find-epoch-in` walk + the `replace-container!` path inside
    `perform-restore!`.

    Note: `restore-epoch!` validates a precondition that no drain is
    in flight on the target frame (`drain-in-flight?`); when the
    producer is mid `dispatch-sync`, the consumer's restore correctly
    refuses with `:rf.epoch/restore-during-drain`. That refusal IS the
    contract — the test asserts that ALL restores either succeed or
    fail with one of the documented refusal traces, and that the
    consumer thread NEVER throws.

    Invariants:
    1. **No exception escapes.** Both threads run their full loop;
       `restore-epoch!` either returns true/false but never throws, and
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
            ;; `state` is used in test BODIES for the private back-fill
            ;; path (`state/back-fill-sub-run!`) + the private listeners
            ;; var (`@#'state/listeners`) the concurrency invariants
            ;; exercise directly — NOT for fixture config reset (that now
            ;; flows through `configure!`).
            [re-frame.epoch.state :as state]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            ;; rf2-v6z0: machines is a separate artefact whose late-bind
            ;; hook publishes `rf/reg-machine` only when loaded. Pulled in
            ;; for symmetry across the suite so the captured ns-load
            ;; baseline includes the machines registrations.
            [re-frame.machines])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

;; ---- fixture --------------------------------------------------------------
;;
;; rf2-yw1w1u — canonical capture/restore fixture. Snapshots the
;; registrar at ns-load + restores around each test, fires the epoch
;; reset-hook table (history / listeners / config-to-default), and the
;; `:init-fn` re-applies the suite's non-default `:trace-events-keep 5`
;; (NOT the shipped 50 = :depth; Mike pair-debug 2026-05-27) through the
;; public `configure!` boundary — no test ns reaches into the private
;; `state/config` var for fixture reset.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn [] (rf/configure! {:epoch-history {:trace-events-keep 5}}))}))

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
    (rf/configure! {:epoch-history {:depth (* 2 stress-iters)}})
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
      (rf/reg-event :bump (fn [{:keys [db]} [_ i]]
                               {:db (assoc db :last i)}))

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
  (testing (str "register-epoch-listener! / unregister-epoch-listener! churn vs "
                stress-iters " settle fanouts — no leaked listeners, "
                "exception isolation holds, every settle records")
    ;; Bump the ring depth above iters — invariant 3 below counts the
    ;; final history; the cap behaviour is covered by the deterministic
    ;; `ring-depth-evicts-oldest` pin.
    (rf/configure! {:epoch-history {:depth (* 2 stress-iters)}})
    (rf/reg-frame :rd7a7.fanout/main {:doc "fanout-stress frame"})
    (rf/reg-event :bump (fn [{:keys [db]} [_ i]]
                             {:db (assoc db :last i)}))

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
      (rf/register-epoch-listener! ::throwing
                             (fn [_]
                               (swap! throw-count inc)
                               (throw (ex-info "intentional" {}))))
      (rf/register-epoch-listener! ::counter
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
                        (rf/register-epoch-listener! cb-id (fn [_] nil))
                        (rf/unregister-epoch-listener! cb-id)
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
          (rf/unregister-epoch-listener! (keyword "rd7a7.fanout"
                                        (str "churner-" i))))
        (let [live (deref @#'state/listeners)
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
;; Concurrent record (settle!) + read (epoch-history + restore-epoch!).
;; One producer fires settles; one consumer reads history and tries
;; restore against arbitrary epoch-ids it observed in the snapshot.
;; Because restore-epoch!'s precondition refuses while a drain is in
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
    (rf/configure! {:epoch-history {:depth (* 2 stress-iters)}})
    (rf/reg-frame :rd7a7.race/main {:doc "ring-buffer race frame"})
    (rf/reg-event :bump (fn [{:keys [db]} [_ i]]
                             {:db (assoc db :last i)}))

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
                            result    (rf/restore-epoch!
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

;; ---- Scenario 4 (EP-0015 §15 / open-issue 6) -----------------------------
;;
;; RAW back-fill under CAS contention. `back-fill-event!` mutates the single
;; global `histories` atom under `swap!`. Per EP-0015 §15 + open-issue 6
;; (RULED, hardened) the back-fill stores the RAW delta — the ring is causal
;; replay material, so it stays raw; the `:redact-fn` advanced override runs
;; projection-side only. The swap update fn (the pure splice inside
;; `back-fill-event!`, rf2-c0rv4v) is therefore PURE — it invokes no injected
;; fn, so a JVM CAS retry re-runs only the pure splice. With no per-back-fill
;; redact invocation there is no double-invoke / duplicate-warning hazard
;; inside the swap.
;;
;; This scenario drives N threads, each performing M post-settle sub-run
;; back-fills (`state/back-fill-sub-run!`) against its OWN frame's settled
;; epoch — but ALL frames share the single global `histories` atom, so the
;; CAS on that atom is heavily contended and retries WILL occur. The
;; invariant the pure splice must hold under that contention:
;;
;;   Invariant 1 (no loss / no duplication): each frame's target epoch
;;     accrues EXACTLY M `:sub-runs` rows — one per back-fill — with the
;;     full distinct value set. A CAS-retry bug that dropped or double-
;;     appended a splice would surface as a wrong row count or a duplicate.
;;   Invariant 2 (raw stored): each back-filled `:sub-runs` row carries
;;     the RAW value verbatim (no redaction at storage — the ring is the
;;     raw causal-replay surface).
;;
;; CLJS is single-threaded; the race cannot manifest there — JVM-only.

(defn- sub-run-event
  "A bare `:rf.sub/run` trace-event map (the shape `back-fill-sub-run!`
  appends to `:trace-events`). We call `state/back-fill-sub-run!` directly
  (the production post-settle path) rather than firing through `trace/emit!`
  so the test drives concurrent back-fills against the contended global
  `histories` atom from N threads — the cross-frame CAS contention the
  runtime's per-frame single-drainer would otherwise serialise away."
  [frame-id sub-id value]
  {:op-type   :rf.sub
   :operation :rf.sub/run
   :tags      {:rf.sub/id      sub-id
               :rf.sub/query-v [sub-id]
               :frame          frame-id
               :rf.sub/value   value}})

(deftest raw-back-fill-lands-exactly-once-under-cas-contention
  (testing (str n-threads " threads × " stress-iters
                " sub-run back-fills each, all contending the single global "
                "histories atom — every RAW back-fill delta lands EXACTLY "
                "ONCE (no CAS-retry loss / duplication) and the stored rows "
                "carry the raw value verbatim (EP-0015 §15 / open-issue 6)")
    (rf/configure! {:epoch-history {:depth (* 2 stress-iters)}})
    (let [n      n-threads
          frames (mapv (fn [t] (keyword "ep0015.cas" (str "frame-" t)))
                       (range n))]
      ;; Seed each frame with one settled epoch (the back-fill target).
      (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
      (doseq [frame-id frames]
        (rf/reg-frame frame-id {})
        (rf/dispatch-sync [:seed] {:frame frame-id}))

      (let [latch   (CountDownLatch. 1)
            futures (mapv
                      (fn [frame-id]
                        (future
                          (.await latch)
                          (let [epoch-id (-> (rf/epoch-history frame-id)
                                             first :epoch-id)]
                            (dotimes [i stress-iters]
                              (let [sid (keyword (str "s" i))]
                                ;; Production post-settle path: the back-fill
                                ;; stores the RAW delta — no redact arg
                                ;; (redaction is projection-side, in
                                ;; `projected-record`).
                                (state/back-fill-sub-run!
                                  frame-id epoch-id
                                  (sub-run-event frame-id sid i)
                                  {:sub-id sid :value i})))
                            :done)))
                      frames)]
        (.countDown latch)
        (let [results (mapv await-future futures)]
          (doseq [r results]
            (is (not= ::timeout r) "back-fill thread completed within timeout"))

          ;; --- Invariant 1: each frame's target epoch accrued EXACTLY
          ;;     `stress-iters` sub-runs, with the full distinct value set.
          ;;     A CAS-retry that dropped a splice would shrink the count; a
          ;;     double-append would inflate it or duplicate a value.
          (doseq [frame-id frames]
            (let [record   (-> (rf/epoch-history frame-id) first)
                  sub-runs (:sub-runs record)
                  values   (set (map :value sub-runs))]
              (is (= stress-iters (count sub-runs))
                  (str frame-id ": expected " stress-iters
                       " back-filled sub-runs, got " (count sub-runs)
                       " — a CAS retry on the contended histories atom lost "
                       "or duplicated a splice."))
              ;; --- Invariant 2: the RAW values were stored verbatim (the
              ;;     ring is the raw causal-replay surface; redaction is
              ;;     projection-side only).
              (is (= (set (range stress-iters)) values)
                  (str frame-id ": the back-filled rows carry the full raw "
                       "value set 0.." (dec stress-iters)
                       " verbatim — none lost, none redacted at storage.")))))))))

;; ---- Scenario 5 (rf2-qh13yf) ----------------------------------------------
;;
;; BACK-FILL vs INTERLEAVED EVICTION at ring cap. `back-fill-event!` fires at
;; React commit / deref / teardown time, OUTSIDE any drain, so a cascade
;; `record!` for the SAME frame can interleave with it. The back-fill must
;; re-derive the target record's ring index from the SINGLE CAS-retried
;; `@histories` value inside the one `swap!`: resolving the index against one
;; deref and then splicing against a later deref would splice the WRONG record,
;; because at ring CAP an interleaved append EVICTS the front record and shifts
;; every index down by one.
;;
;; This scenario pins ONE frame at a small ring cap and runs two contending
;; thread groups against it:
;;   * a RECORDER thread that fires `iters` `dispatch-sync` settles — each at
;;     cap evicts the front, churning indices continuously;
;;   * BACK-FILLER threads that, in a tight loop, snapshot the live ring, pick a
;;     real (currently-present) epoch-id, and `back-fill-sub-run!` a uniquely-
;;     tagged row onto it.
;;
;; Invariant (snapshot consistency): EVERY back-filled row that the fix accepts
;; (the target was still present at splice time) lands on the epoch whose
;; `:epoch-id` matches the tag the row carries — NEVER on a positional
;; neighbour. After the run we walk the final ring and assert no surviving
;; record carries a `:sub-runs` row whose embedded target-id != that record's
;; own epoch-id. A stale-index wrong-record splice surfaces as exactly such a
;; mismatch. Neither thread group may throw.
;;
;; CLJS is single-threaded; the race cannot manifest there — JVM-only.

(deftest back-fill-snapshot-consistent-under-interleaved-eviction-stress
  (testing (str "back-fill vs " stress-iters
                " interleaved cap-evicting settles — every accepted back-fill "
                "lands on the epoch matching its embedded target-id, never a "
                "stale-index neighbour (rf2-qh13yf)")
    ;; Small cap so the recorder's settles continuously evict + shift indices.
    (let [cap 8]
      (rf/configure! {:epoch-history {:depth cap :trace-events-keep 50}})
      (rf/reg-frame :qh13yf.race/main {:doc "back-fill vs eviction race frame"})
      (rf/reg-event :bump (fn [{:keys [db]} [_ i]] {:db (assoc db :n i)}))
      ;; Seed the ring to cap so back-fillers have live targets from the start.
      (dotimes [i cap] (rf/dispatch-sync [:bump i] {:frame :qh13yf.race/main}))

      (let [recorder-done (atom false)
            errors        (atom [])
            accepted      (atom 0)
            n-fillers     (max 2 (quot n-threads 2))
            latch         (CountDownLatch. 1)
            ;; Each back-filled row embeds the TARGET epoch-id it was aimed at,
            ;; so the post-run walk can detect a wrong-record splice (the row's
            ;; embedded target-id != the record it actually landed on).
            bf-event (fn [target-id]
                       {:op-type   :rf.sub
                        :operation :rf.sub/run
                        :tags      {:rf.sub/id     :race-sub
                                    :frame         :qh13yf.race/main
                                    :rf.sub/value  target-id}})
            recorder
            (future
              (.await latch)
              (try
                (dotimes [i stress-iters]
                  (rf/dispatch-sync [:bump (+ cap i)] {:frame :qh13yf.race/main}))
                (catch Throwable t (swap! errors conj t))
                (finally (reset! recorder-done true))))
            fillers
            (vec
              (for [_ (range n-fillers)]
                (future
                  (.await latch)
                  (try
                    (loop []
                      (when-not @recorder-done
                        (let [hist (rf/epoch-history :qh13yf.race/main)]
                          (when (seq hist)
                            (let [target-id (:epoch-id (rand-nth hist))
                                  ;; embed the target-id as the row's :value so a
                                  ;; mis-splice is detectable; back-fill returns
                                  ;; nil when the target evicted before the splice.
                                  result (state/back-fill-sub-run!
                                           :qh13yf.race/main target-id
                                           (bf-event target-id)
                                           {:sub-id :race-sub :value target-id})]
                              (when (some? result) (swap! accepted inc)))))
                        (recur)))
                    (catch Throwable t (swap! errors conj t))))))]
        (.countDown latch)
        (is (not= ::timeout (await-future recorder)) "recorder completed")
        (doseq [f fillers]
          (is (not= ::timeout (await-future f)) "back-filler completed"))

        ;; --- Invariant 0: no exception escaped either thread group.
        (is (empty? @errors)
            (str "no thread threw; got " (count @errors) " exceptions: "
                 (pr-str (mapv #(.getMessage ^Throwable %) (take 3 @errors)))))

        ;; --- Invariant 1 (snapshot consistency): every back-filled row on a
        ;;     surviving record embeds THAT record's own epoch-id. A row whose
        ;;     embedded target-id differs from the record it sits on is a
        ;;     stale-index wrong-record splice (the bug).
        (let [final-hist (rf/epoch-history :qh13yf.race/main)
              mis-spliced
              (for [record final-hist
                    row     (:sub-runs record)
                    :when   (= :race-sub (:sub-id row))
                    :when   (not= (:value row) (:epoch-id record))]
                {:landed-on (:epoch-id record) :aimed-at (:value row)})]
          (is (empty? mis-spliced)
              (str "every accepted back-fill landed on the epoch matching its "
                   "embedded target-id; got " (count mis-spliced)
                   " wrong-record splices (stale-index bug): "
                   (pr-str (vec (take 5 mis-spliced))))))

        ;; --- Diagnostic: the back-fillers actually accepted some splices
        ;;     (a quiescent run would vacuously pass invariant 1).
        (is (pos? @accepted)
            (str "back-fillers accepted " @accepted
                 " splices (a positive count means the race window was "
                 "actually exercised; some attempts return nil when the "
                 "target evicted before the splice)."))))))
