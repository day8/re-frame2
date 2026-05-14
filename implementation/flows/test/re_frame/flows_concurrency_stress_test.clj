(ns re-frame.flows-concurrency-stress-test
  "Per rf2-ztw5p — JVM concurrency stress coverage for the flows
  surface (`reg-flow` / `clear-flow` / dirty-check / topo / failed-flow).
  Mirrors the rf2-1gpx8 (machine actor) and rf2-35rgj (router) stress
  patterns: many threads running independent per-frame cycles in
  lockstep, asserting **no event dropped** and **no double-action**
  invariants on counters that the deterministic single-shot suite
  cannot detect.

  The deterministic flow tests in `flows_test.clj` cover correctness
  single-shot; this namespace pins the surface under parallel
  registration, dirty-evaluation, and clear from many threads. The
  invariants carry over from rf2-35rgj / rf2-1gpx8: every dispatched
  event ran exactly once (no drops) and every flow `:output` invocation
  fired exactly once per dirty evaluation (no doubles).

  The shape — per Spec 013 §Frame-scoping + Spec 002 §Rules rule 1
  (frames are independent state machines, their drain-locks don't
  share):

    - **N threads run in parallel**, each owning its own frame
      (`:ztw5p.stress/f<i>`). Independent frames parallelise cleanly;
      same-frame multi-thread `dispatch-sync` would hit
      `:rf.error/dispatch-sync-in-handler` via the `:in-sync-drain?`
      guard, so we deliberately partition by frame. Per-frame `flows`
      / `last-inputs` slots are independent; the global registrar
      `:flow` slot IS shared (flow-id is global) but reg-flow stamps
      the most-recently-registered frame onto its metadata per Spec
      013 §Frame-scoping line 105.
    - **Per iter**, the thread runs the reg-flow → dispatch-drain →
      clear-flow cycle against a per-thread namespaced flow id
      (`:ztw5p.stress/double-f<i>`) whose `:output` action increments a
      per-thread counter atom AND a shared `AtomicLong` global counter
      both bumping exactly once per dirty evaluation.
        1. `(rf/reg-flow ...)` against the per-thread frame —
           per-thread namespaced `:id` keeps each thread's `:output`
           closure binding independent (registrar is GLOBAL; one
           shared `:id` would only bind the last closure).
        2. `(rf/dispatch-sync [:bump-input] {:frame F})` — drives one
           dirty evaluation. The `:output` fn bumps the global atomic
           AND the per-thread counter atom. Both bump exactly once
           per dirty evaluation (the dirty-check guarantees `:output`
           runs once per input change).
        3. `(rf/clear-flow ...)` against the per-thread frame — must
           dissoc both the per-frame registry slot AND (since this is
           the LAST frame holding the id) the global registrar `:flow`
           slot. Cleanup is the leak-invariant test surface.

  Invariants asserted:

    1. **No event dropped (per-thread counter sum).** Sum of per-thread
       counters across all threads = `(N × iters)`. Tracks observation
       locally per thread; aggregation is the global cross-check that
       no thread silently lost work.

    2. **No double-action (global atomic counter).** `AtomicLong` is
       bumped once per `:output` invocation. After all threads finish,
       its value must EXACTLY match `(N × iters)`. Higher = a flow ran
       twice for one dispatch (double-action); lower = a dirty
       evaluation was dropped before `:output` ran.

    3. **Ordering stable / cycle detection works under contention.**
       Per-thread cycle-detection probe: each thread, mid-stress, does
       a `try`/`catch` `reg-flow` of a flow whose registration would
       close a two-flow cycle. The detector MUST throw
       `:rf.error/flow-cycle` under contention; pre-fix any race in
       topo-sort's snapshot read could miss the prospective edge and
       silently admit the cycle. The probe is deterministic — every
       attempt MUST be detected — so the shared atomic counter for
       cycle hits MUST equal exactly `n-threads` (each thread did one
       probe).

    4. **Clean registry — no leak.** After all threads finish and
       every per-thread cycle is cleared, the per-frame registry
       (`re-frame.flows/flows`) holds zero entries for every test
       frame, the dirty-check `last-inputs` map holds zero entries
       for every per-thread flow id, and the global `:flow`
       registrar slot is unregistered for every per-thread flow id
       (the LAST frame holding it released the id, so per Spec 013
       §Frame-scoping the registrar slot must be vacated).

  Threads start in lockstep via `CountDownLatch.countDown` — the same
  shape rf2-35rgj / rf2-1gpx8 use to maximise contention. Per-thread
  iters default to 5000 (rf2-ynk7 / rf2-35rgj / rf2-1gpx8 standard);
  env-overridable via `RF2_ZTW5P_STRESS_ITERS`.

  CLJS is single-threaded; the JVM is the only runtime where the flows
  reg/clear/eval lifecycle CAN race across threads. This test is
  JVM-only by design."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.flows :as flows]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace])
  (:import [java.util.concurrent CountDownLatch]
           [java.util.concurrent.atomic AtomicLong]))

;; ---- per-test reset -------------------------------------------------------
;;
;; Mirrors the `flows_test.clj` fixture so each deftest starts from a
;; clean registrar / frames / flows / schemas / trace-cbs / last-inputs
;; state. The flows ns keeps a private `last-inputs` atom for
;; dirty-checking (per Spec 013 §Dirty-check semantics); we reset it
;; via `flows/reset-flows!` (which clears BOTH `flows` and
;; `last-inputs` in lockstep per rf2-mb65w) so cross-test state cannot
;; leak in either direction.

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (reset! schemas/schemas-by-frame {})
  (trace/clear-trace-cbs!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; Per-thread iteration count. Kept at the rf2-ynk7 / rf2-35rgj /
;; rf2-1gpx8 standard 5000 so CI stays under ~60s wall-clock with the
;; default thread count. Operators dial up via the env override; CI
;; dials down by lowering it (e.g. `RF2_ZTW5P_STRESS_ITERS=500` for a
;; smoke-test pass).
(def ^:private stress-iters
  (or (some-> (System/getenv "RF2_ZTW5P_STRESS_ITERS") Long/parseLong)
      5000))

;; Eight parallel threads — matches rf2-ynk7's `concurrent-dispatch-stress`
;; (`n-submitters 8`) and the rf2-1gpx8 thread count. Higher contention
;; than the typical 4-core CI box; the per-frame partitioning means
;; we're not over-saturating any one drain-lock, we're driving N
;; independent reg/eval/clear cycles in parallel and asserting the
;; flow-id allocator + registrar lookup + per-frame registry write
;; don't tangle across frames.
(def ^:private n-threads 8)

;; ---- the stress test ------------------------------------------------------

(deftest flow-reg-eval-clear-stress
  ;; rf2-ztw5p — mirrors rf2-1gpx8's pattern for the flows surface.
  ;;
  ;; The two pinned counter invariants are the same as rf2-35rgj /
  ;; rf2-1gpx8: every dispatched event ran exactly once (no drops) and
  ;; every transition / `:output` invocation fired exactly once per
  ;; dispatch (no doubles). Two distinct counter views (per-thread
  ;; atom + global AtomicLong) detect them independently — divergence
  ;; between the two would indicate the per-thread observation lost
  ;; track, even when the global total happened to balance.
  ;;
  ;; Two additional invariants pin the flows-specific surface:
  ;; cycle-detection-under-contention (every prospective cyclic
  ;; registration must throw, even when topo-sort is reading from a
  ;; concurrently-mutating per-frame slot) and clean-registry-
  ;; teardown (the per-frame registry, the dirty-check `last-inputs`
  ;; map, and the global `:flow` registrar slot all clear after the
  ;; matching `clear-flow`).
  (testing (str n-threads " threads × " stress-iters
                " iters reg-flow / dirty-eval / clear-flow — "
                "no drops, no doubles, cycles still detected, no leaks")
    (let [global-counter      (AtomicLong. 0)
          cycle-hits          (AtomicLong. 0)
          per-thread-counters (vec (repeatedly n-threads #(atom 0)))
          ;; Per-thread frame + per-thread namespaced flow ids.
          ;; Independent frames parallelise across CPUs (Spec 002
          ;; §Rules rule 1 — frames are independent state machines,
          ;; their drain-locks don't share). Per-thread flow-ids let
          ;; each thread's `:output` fn close over THIS thread's
          ;; per-thread counter atom — `reg-flow` writes the GLOBAL
          ;; registrar; each id binds independently in the registrar.
          per-thread
          (vec
            (for [i (range n-threads)]
              {:idx        i
               :frame-id   (keyword "ztw5p.stress" (str "f" i))
               :flow-id    (keyword "ztw5p.stress" (str "double-f" i))
               ;; Cycle-probe pair: registering the SECOND closes the
               ;; cycle. Per-thread namespaced so each thread's probe
               ;; is independent — a probe on thread 0 cannot
               ;; accidentally satisfy thread 1's cycle expectation.
               :cyc-a      (keyword "ztw5p.stress" (str "cyc-a-f" i))
               :cyc-b      (keyword "ztw5p.stress" (str "cyc-b-f" i))
               :counter    (nth per-thread-counters i)}))]
      ;; Set up the frames on the main thread before any futures
      ;; launch. The frame-registry write is serialised here; the
      ;; futures only READ from `frame/frames` (per-frame state lives
      ;; in independent app-db containers).
      (doseq [{:keys [frame-id]} per-thread]
        (rf/reg-frame frame-id
                      {:doc "per-thread frame for flows stress test"}))
      ;; Per-thread :seed event (writes :n once so the flow has an
      ;; input value) and :bump-input event (changes :n every iter so
      ;; the dirty-check fires). These events are global (registrar
      ;; is global) but each thread reads from its own frame's
      ;; app-db, so the writes don't tangle.
      (rf/reg-event-db :ztw5p.stress/seed
                       (fn [_ [_ n]] {:n n}))
      (rf/reg-event-db :ztw5p.stress/bump-input
                       (fn [db [_ n]] (assoc db :n n)))

      (let [latch   (CountDownLatch. 1)
            futures (vec
                      (for [{:keys [idx frame-id flow-id cyc-a cyc-b counter]} per-thread]
                        (future
                          (.await latch)
                          ;; Per-thread cycle: register flow → drive
                          ;; many dirty evaluations → clear flow.
                          ;; `dispatch-sync` settles the drain
                          ;; (including `run-flows!`) before
                          ;; returning so the `:output` fn runs to
                          ;; completion before this thread observes
                          ;; the next iter.
                          (rf/reg-flow {:id     flow-id
                                        :inputs [[:n]]
                                        :output (fn [n]
                                                  (.incrementAndGet global-counter)
                                                  (swap! counter inc)
                                                  (* 2 (or n 0)))
                                        :path   [:doubled]}
                                       {:frame frame-id})
                          ;; Drive `stress-iters` dirty evaluations.
                          ;; Use the loop counter (i+1) as the input
                          ;; value so each iter's :n is distinct from
                          ;; the previous iter's — guarantees the
                          ;; dirty-check fires every iter (no
                          ;; value-equal skip path).
                          (dotimes [i stress-iters]
                            (rf/dispatch-sync [:ztw5p.stress/bump-input (inc i)]
                                              {:frame frame-id}))
                          ;; Mid-stress cycle-detection probe.
                          ;; Register `:cyc-a` (depends on `:cyc-b`),
                          ;; then attempt `:cyc-b` (depends on
                          ;; `:cyc-a`). The second registration MUST
                          ;; throw `:rf.error/flow-cycle`; if topo-
                          ;; sort's snapshot read raced and missed
                          ;; the prospective edge, the throw would
                          ;; silently fail to fire. The cycle-hits
                          ;; atomic counts every confirmed throw.
                          (rf/reg-flow {:id     cyc-a
                                        :inputs [[cyc-b]]
                                        :output identity
                                        :path   [cyc-a]}
                                       {:frame frame-id})
                          (try
                            (rf/reg-flow {:id     cyc-b
                                          :inputs [[cyc-a]]
                                          :output identity
                                          :path   [cyc-b]}
                                         {:frame frame-id})
                            ;; If we reach here, the cycle was NOT
                            ;; detected — leave cycle-hits unbumped
                            ;; and let invariant 3 fail loudly.
                            (catch Throwable t
                              (when (re-find #":rf.error/flow-cycle"
                                             (or (ex-message t) ""))
                                (.incrementAndGet cycle-hits))))
                          ;; Clear the cycle-probe registration so
                          ;; the leak invariant can pass. (`:cyc-b`
                          ;; never registered because the cycle
                          ;; throw rolled it back per
                          ;; rf2-7csri/rf2-cdh9h.)
                          (rf/clear-flow cyc-a {:frame frame-id})
                          ;; Clear the main flow so the leak
                          ;; invariant can pass.
                          (rf/clear-flow flow-id {:frame frame-id})
                          ;; Surface the thread idx for diagnostics
                          ;; if a future hangs.
                          idx)))]
        ;; Release all threads simultaneously — maximises lock-step
        ;; contention on the GLOBAL registrar's `:flow` slot (each
        ;; thread is writing/reading the same registrar kind during
        ;; reg/clear).
        (.countDown latch)
        ;; Bounded join — if a cycle ever hangs (e.g. a clear-flow
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
                   stress-iters " :output invocations; got "
                   per-thread-totals)))

        ;; --- Invariant 2: no double-action (global atomic) --------
        (let [global-actual   (.get global-counter)
              global-expected (* n-threads stress-iters)]
          (is (= global-expected global-actual)
              (str "Global AtomicLong: expected "
                   global-expected " :output invocations (no double-"
                   "action); got " global-actual)))

        ;; --- Invariant 3: cycle detection stable under contention --
        ;; Every per-thread probe MUST have closed its cycle and the
        ;; throw MUST have fired. cycle-hits = n-threads exactly.
        (let [hits (.get cycle-hits)]
          (is (= n-threads hits)
              (str "Cycle-detection probe: expected " n-threads
                   " confirmed `:rf.error/flow-cycle` throws (one "
                   "per thread); got " hits ". A short count means "
                   "topo-sort missed a prospective cyclic edge "
                   "under contention.")))

        ;; --- Invariant 4: clean registry — no leak. After every
        ;;     thread cleared its flow, the per-frame registry must
        ;;     be empty for every test frame, the `last-inputs` map
        ;;     must hold no entries for any per-thread flow id, and
        ;;     the global `:flow` registrar slot must be gone for
        ;;     every per-thread flow id (the LAST frame holding it
        ;;     released the id per Spec 013 §Frame-scoping).
        (let [flows-snapshot       @flows/flows
              last-inputs-snapshot @flows/last-inputs]
          (doseq [{:keys [frame-id flow-id cyc-a cyc-b]} per-thread]
            (let [per-frame-slot (get flows-snapshot frame-id)]
              (is (or (nil? per-frame-slot) (empty? per-frame-slot))
                  (str "Frame " frame-id ": expected empty per-frame "
                       "flow registry after teardown; got "
                       (pr-str per-frame-slot))))
            (is (not (contains? last-inputs-snapshot flow-id))
                (str "Flow id " flow-id " must not retain a "
                     "`last-inputs` entry after clear-flow; got "
                     (pr-str (get last-inputs-snapshot flow-id))))
            (is (not (contains? last-inputs-snapshot cyc-a))
                (str "Cycle-probe id " cyc-a " must not retain a "
                     "`last-inputs` entry after clear-flow"))
            ;; `:cyc-b` never registered (cycle throw rolled it
            ;; back) so it should never have appeared in
            ;; `last-inputs`. Pin it explicitly so a regression that
            ;; populates it before the cycle check fails loudly.
            (is (not (contains? last-inputs-snapshot cyc-b))
                (str "Cycle-probe id " cyc-b " never registered "
                     "(cycle rolled back per rf2-7csri); must not "
                     "appear in `last-inputs`"))
            (is (nil? (registrar/lookup :flow flow-id))
                (str ":flow registrar slot for " flow-id " must be "
                     "vacated — the LAST frame holding the id "
                     "released it"))
            (is (nil? (registrar/lookup :flow cyc-a))
                (str ":flow registrar slot for cycle-probe " cyc-a
                     " must be vacated"))))))))
