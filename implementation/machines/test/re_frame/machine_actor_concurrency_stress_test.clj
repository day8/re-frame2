(ns re-frame.machine-actor-concurrency-stress-test
  "Per rf2-1gpx8 — JVM concurrency stress coverage for the machine actor
  spawn/destroy/invoke lifecycle. Mirrors rf2-35rgj's router concurrency
  stress pattern (`concurrency_stress_test.clj`) but targets the actor
  surface instead of the router.

  The deterministic machine-test suite covers correctness single-shot;
  this namespace pins the actor surface under parallel
  `(rf/dispatch [machine-id ...] {:frame F})` from many threads. The
  invariants are the same as rf2-35rgj's: **no event dropped** and **no
  double-action**.

  The shape — per Spec 005 §Declarative `:spawn` + Spec 002 §Rules
  rule 1 (frames are independent state machines, their drain-locks don't
  share):

    - **N threads run in parallel**, each owning its own frame
      (`:gpx8.stress/f<i>`). Independent frames parallelise cleanly;
      same-frame multi-thread `dispatch-sync` would hit
      `:rf.error/dispatch-sync-in-handler` via the `:in-sync-drain?`
      guard, so we deliberately partition by frame.
    - **Per iter**, the thread runs the spawn → dispatch → destroy
      cycle against a per-thread `driver-mid` parent machine
      (`:gpx8.stress/driver-fN`) whose `:working` state declaratively
      `:spawn`s the per-thread `worker-mid` child
      (`:gpx8.stress/worker-fN`). Per-thread namespacing of the
      machine-ids lets each worker's `:bump` action close over THIS
      thread's per-thread counter atom — `reg-machine` is GLOBAL, so
      one shared `worker-mid` would only bind the last closure.
        1. `(rf/dispatch-sync [driver-mid [:go]]   {:frame F})` —
           drives the parent into `:working`, which spawns the worker
           via `:rf.machine/spawn` fx (rf2-t07u — runtime tracks the
           spawned id at `[:rf/spawned <driver-mid> [:working]]`).
        2. `(rf/dispatch-sync [<actor-id> [:tick]] {:frame F})` —
           dispatches a `:tick` event AT THE SPAWNED ACTOR. The
           transition runs the `:bump` action which increments a
           **per-thread** counter AND a **global atomic** counter
           (`AtomicLong`); both counters tick exactly once per
           transition.
        3. `(rf/dispatch-sync [driver-mid [:done]] {:frame F})` —
           drives the parent back to `:idle`, which fires the exit
           cascade destroying the worker via `:rf.machine/destroy` fx.

  Invariants asserted:

    1. **No event dropped (per-thread counter sum).** Sum of
       per-thread counters across all threads = `(N × iters)`. Tracks
       observation locally per thread; aggregation is the global
       cross-check that no thread silently lost work.

    2. **No double-action (global atomic counter).** `AtomicLong` is
       bumped once per `:bump` action firing. After all threads finish,
       its value must EXACTLY match `(N × iters)`. Higher = a
       transition fired twice for one dispatch (double-action);
       lower = a dispatch was dropped before the action ran.

    3. **Clean destroy lifecycle.** Every frame's `:rf/machines` slot
       MUST be empty at quiescence — every worker spawned was
       destroyed, no actor leaked. Per rf2-t07u Option A revised the
       `[:rf/spawned ...]` registry root is lazy-allocation pruned and
       MUST be absent.

    4. **Spawn-counter monotonicity.** Each frame's parent-snapshot
       slot `[:rf/machines <driver-mid> :rf/spawn-counter <worker-mid>]`
       MUST equal `iters` at the end — every iter allocated exactly
       one fresh worker id from the parent's in-snapshot counter
       (rf2-gr8q), never colliding, never skipping.

  Threads start in lockstep via `CountDownLatch.countDown` — the same
  shape rf2-35rgj scenario 2 uses to maximise contention. Per-thread
  iters default to 5000 (rf2-ynk7 / rf2-35rgj standard); env-overridable
  via `RF2_1GPX8_STRESS_ITERS`.

  CLJS is single-threaded; the JVM is the only runtime where the actor
  spawn/destroy lifecycle CAN race across threads. This test is
  JVM-only by design."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; `re-frame.machines` is required for its load-time
            ;; side-effects: the late-bind hooks `:machines/reg-machine`
            ;; etc. install when this ns is loaded. When this test runs
            ;; in isolation (e.g. `clojure -M:test -n
            ;; re-frame.machine-actor-concurrency-stress-test`) no sibling
            ;; test ns has pulled `re-frame.machines` in for us; the
            ;; first `rf/reg-machine` call would otherwise raise
            ;; `:rf.error/machines-artefact-missing`.
            [re-frame.machines]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support])
  (:import [java.util.concurrent CountDownLatch]
           [java.util.concurrent.atomic AtomicLong]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; Per-thread iteration count. Kept at the rf2-ynk7 / rf2-35rgj standard
;; 5000 so CI stays under ~60s wall-clock with the default thread count.
;; Operators dial up via the env override; CI dials down by lowering it
;; (e.g. `RF2_1GPX8_STRESS_ITERS=500` for a smoke-test pass).
(def ^:private stress-iters
  (or (some-> (System/getenv "RF2_1GPX8_STRESS_ITERS") Long/parseLong)
      5000))

;; Eight parallel threads — matches rf2-ynk7's `concurrent-dispatch-stress`
;; (`n-submitters 8`). Higher contention than the typical 4-core CI box;
;; the per-frame partitioning means we're not over-saturating any one
;; drain-lock, we're driving N independent spawn/destroy cycles in
;; parallel and asserting the actor-id allocator + registrar lookup +
;; spawn-registry write don't tangle across frames.
(def ^:private n-threads 8)

;; ---- machine specs --------------------------------------------------------
;;
;; The worker spec is parameterised over the global+per-thread counters
;; so each thread's worker bumps its own per-thread counter (alongside
;; the shared AtomicLong global). Each thread gets a namespaced worker
;; machine-id (`:gpx8.stress/worker-f0`, `…/worker-f1`, …) so the
;; per-thread atom binds at the closure boundary on registration — see
;; the test body's per-frame `reg-machine` loop. `reg-machine` writes
;; the GLOBAL registrar; each id binds independently.

(defn- worker-spec
  "Build a worker spec whose `:bump` action targets `global-counter`
  (an `AtomicLong` for thread-safe cross-thread observation) AND
  `per-thread-counter` (a plain atom — only this thread's frame writes
  it). The `:bump` action runs on every `:tick` transition exactly
  once per dispatch — the heart of the no-double-action invariant."
  [^AtomicLong global-counter per-thread-counter]
  {:initial :running
   :data    {}
   :actions {:bump (fn [data _ev]
                     (.incrementAndGet global-counter)
                     (swap! per-thread-counter inc)
                     data)}
   :states  {:running {:on {:tick {:action :bump}}}}})

(defn- driver-spec
  "Build a driver parent that declaratively `:spawn`s `worker-mid` on
  entry to `:working` and destroys it on exit. Per Spec 005
  §Declarative `:spawn` the runtime tracks the spawned id at
  `[:rf/spawned <driver-mid> [:working]]`; on exit back to `:idle` the
  matched destroy fx fires automatically (rf2-t07u Option A revised)."
  [worker-mid]
  {:initial :idle
   :states  {:idle    {:on {:go :working}}
             :working {:spawn {:machine-id worker-mid}
                       :on     {:done :idle}}}})

;; ---- the stress test ------------------------------------------------------

(deftest actor-spawn-dispatch-destroy-stress
  ;; rf2-1gpx8 — mirrors rf2-35rgj's pattern for the machine actor surface.
  ;;
  ;; The two pinned invariants are the same as rf2-35rgj's: every
  ;; dispatched event ran exactly once (no drops) and every transition
  ;; fired exactly once per dispatch (no doubles). Two distinct counter
  ;; views (per-thread atom + global AtomicLong) detect them
  ;; independently — divergence between the two would indicate the
  ;; per-thread observation lost track, even when the global global
  ;; total happened to balance.
  (testing (str n-threads " threads × " stress-iters
                " iters spawn/dispatch/destroy — no drops, no doubles")
    (let [global-counter      (AtomicLong. 0)
          per-thread-counters (vec (repeatedly n-threads #(atom 0)))
          ;; Per-thread frame + per-thread namespaced (worker, driver)
          ;; ids. Independent frames parallelise across CPUs (Spec 002
          ;; §Rules rule 1 — frames are independent state machines,
          ;; their drain-locks don't share). Per-thread machine-ids
          ;; let the worker's `:bump` action close over THIS thread's
          ;; per-thread counter atom — `reg-machine` is global so
          ;; each id binds independently in the registrar.
          per-thread
          (vec
            (for [i (range n-threads)]
              {:idx        i
               :frame-id   (keyword "gpx8.stress" (str "f" i))
               :worker-mid (keyword "gpx8.stress" (str "worker-f" i))
               :driver-mid (keyword "gpx8.stress" (str "driver-f" i))
               :counter    (nth per-thread-counters i)}))]
      ;; Set up the frames + machines on the main thread before any
      ;; futures launch. The registrar / frame-registry writes are
      ;; serialised here; the futures only READ from those structures
      ;; (per-frame state lives in independent app-db containers).
      (doseq [{:keys [frame-id worker-mid driver-mid counter]} per-thread]
        (rf/reg-frame frame-id
                      {:doc "per-thread frame for actor stress test"})
        (rf/reg-machine worker-mid (worker-spec global-counter counter))
        (rf/reg-machine driver-mid (driver-spec worker-mid)))

      (let [latch   (CountDownLatch. 1)
            futures (vec
                      (for [{:keys [frame-id worker-mid driver-mid]} per-thread]
                        (future
                          (.await latch)
                          ;; Per-thread cycle: spawn → dispatch → destroy.
                          ;; `dispatch-sync` settles the cascade before
                          ;; returning so the spawned actor's snapshot
                          ;; is in app-db by the time :go's drain ends.
                          (dotimes [_ stress-iters]
                            (rf/dispatch-sync [driver-mid [:go]]
                                              {:frame frame-id})
                            ;; Resolve the spawned actor id from the
                            ;; runtime-owned registry (rf2-t07u Option A
                            ;; revised — the framework writes the slot
                            ;; on every declarative-:spawn spawn).
                            ;; Reading the frame's app-db is safe: frame
                            ;; state is independent (Spec 002 §Rules
                            ;; rule 1) and this thread holds the only
                            ;; writer for this frame.
                            (let [actor-id (get-in (rf/get-frame-db frame-id)
                                                   [:rf/spawned driver-mid [:working]])]
                              (when actor-id
                                (rf/dispatch-sync [actor-id [:tick]]
                                                  {:frame frame-id})))
                            (rf/dispatch-sync [driver-mid [:done]]
                                              {:frame frame-id})))))]
        ;; Release all threads simultaneously — maximises lock-step
        ;; contention on the GLOBAL registrar's lookup path (each
        ;; thread is reading machine specs out of the same `:event`
        ;; kind during its drains).
        (.countDown latch)
        ;; Bounded join — if the cycle ever hangs (e.g. a destroy fx
        ;; that doesn't fire under contention), we want a visible
        ;; failure rather than a stuck CI run. 120s gives ample
        ;; headroom for 8 × 5000 cycles on a slow box; pre-fix races
        ;; that drop events would surface as either a counter
        ;; mismatch OR a future hanging at a downstream dispatch.
        (doseq [f futures]
          (let [v (deref f 120000 ::timeout)]
            (is (not= ::timeout v)
                "thread completed within 120s wall-clock")))

        ;; --- Invariant 1: no event dropped (per-thread sum) -------
        (let [per-thread-totals (mapv deref per-thread-counters)
              actual-sum        (reduce + per-thread-totals)
              expected-sum      (* n-threads stress-iters)]
          (is (= expected-sum actual-sum)
              (str "Per-thread counter sum: expected "
                   expected-sum " (= " n-threads " threads × "
                   stress-iters " iters); got " actual-sum
                   ". Per-thread breakdown: " per-thread-totals))
          ;; And each thread should have hit EXACTLY stress-iters —
          ;; if one thread silently dropped half its events while
          ;; another somehow doubled up, the sum could still balance.
          ;; This per-thread check rules that out.
          (is (every? #(= stress-iters %) per-thread-totals)
              (str "Each thread must have processed exactly "
                   stress-iters " :tick events; got "
                   per-thread-totals)))

        ;; --- Invariant 2: no double-action (global atomic) --------
        (let [global-actual   (.get global-counter)
              global-expected (* n-threads stress-iters)]
          (is (= global-expected global-actual)
              (str "Global AtomicLong: expected "
                   global-expected " bump-action firings (no double-"
                   "action); got " global-actual)))

        ;; --- Invariants 3 + 4: clean destroy lifecycle and
        ;;     spawn-counter monotonicity, per frame.
        (doseq [{:keys [frame-id worker-mid driver-mid]} per-thread]
          (let [db       (rf/get-frame-db frame-id)
                machines (:rf/machines db)
                ;; The driver itself is a singleton machine reg'd on
                ;; the GLOBAL registrar (singleton-registration path);
                ;; its snapshot lives at [:rf/machines <driver-mid>]
                ;; on the frame's app-db. After every iter the driver
                ;; is back in :idle — its snapshot is still present
                ;; (it's a singleton, not a spawned actor). The
                ;; spawned worker IS torn down each iter; assert NO
                ;; lingering worker snapshots.
                worker-ns (namespace worker-mid)
                worker-leaks (filter (fn [[id _]]
                                       (and (= (namespace id) worker-ns)
                                            (not= id driver-mid)))
                                     machines)]
            (is (empty? worker-leaks)
                (str "Frame " frame-id ": expected zero leaked worker "
                     "actor snapshots; got " (count worker-leaks)
                     " leaks: " (pr-str (mapv first worker-leaks))))
            ;; Per rf2-t07u the empty `:rf/spawned` root is pruned to
            ;; absent — all spawn-registry slots were cleared on
            ;; destroy.
            (is (not (contains? db :rf/spawned))
                (str "Frame " frame-id ": :rf/spawned root must be "
                     "lazy-allocation pruned (every spawn was matched "
                     "by a destroy); got "
                     (pr-str (:rf/spawned db))))
            ;; Per rf2-gr8q the declarative-:spawn allocator lives in
            ;; the parent's snapshot at
            ;; `[:rf/machines <driver-mid> :rf/spawn-counter <worker-mid>]`
            ;; — the spawn-counter bumps inside the transition reducer
            ;; that drives the parent state-machine. Final value =
            ;; iters; lower means an iter's spawn was suppressed; higher
            ;; means an iter caused more than one allocator bump.
            (let [counter (get-in db [:rf/machines driver-mid
                                      :rf/spawn-counter worker-mid])]
              (is (= stress-iters counter)
                  (str "Frame " frame-id ": parent snapshot's "
                       ":rf/spawn-counter for " worker-mid
                       " must equal " stress-iters " (every iter "
                       "allocated exactly one fresh actor id); got "
                       counter)))))))))
