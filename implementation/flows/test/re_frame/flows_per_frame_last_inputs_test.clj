(ns re-frame.flows-per-frame-last-inputs-test
  "Per rf2-94ol5 — the failed-flow rollback in `run-flows-on-db` must be
  scoped to the DRAINING frame's own `last-inputs` container and MUST NOT
  clobber a concurrently-draining sibling frame's dirty-check rows.

  THE BUG (pre-fix). `last-inputs` was a single global atom shaped
  `{flow-id {frame-id inputs}}`. `run-flows-on-db` snapshotted the WHOLE
  atom (all frames' rows) at drain start and, on a flow throw, `reset!`-
  restored the whole thing. Correct for the draining frame, but over-broad:
  it reverted EVERY frame's rows. Drain-locks are PER-FRAME (no global
  cross-frame serialization — Spec 002 rule 1 + the flows concurrency
  stress test rely on frames draining in parallel on different JVM
  threads), so:

    1. Frame A drain (thread 1) snapshots the global atom (B's row = V1).
    2. Frame B drain (thread 2) advances B's row to V2 and commits.
    3. Frame A's flow throws → reset! reverts the global atom → B's row
       reverts to V1.

  Consequence: B's app-db is correctly committed but its dirty-check row
  is stale (V1). On B's NEXT drain with the SAME inputs (V2), `(= V2 V1)`
  is false → the flow recomputes the same value, re-emits
  `:rf.flow/computed`, produces a no-op `:db` write → spurious
  `:rf.event/db-changed` + reactive sub invalidation. A frame-isolation
  contract violation (Spec 002 §Rules rule 1 / Spec 013 §Frame-scoping).

  THE FIX (Mike ruled B, 2026-06-01). Each frame owns its OWN `last-inputs`
  container (`atom {flow-id inputs}`), held in the flows registry's
  `frame-last-inputs` map keyed by frame-id. The rollback snapshots /
  restores ONLY the draining frame's atom — a sibling's container is a
  different atom and is structurally untouchable. Cross-frame interference
  is impossible BY CONSTRUCTION, not merely avoided.

  CLJS is single-threaded; the concurrency surface is JVM-only by design.
  This namespace is JVM-only (`.clj`)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.flows :as flows]
            [re-frame.flows.registry :as registry]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace])
  (:import [java.util.concurrent CountDownLatch]
           [java.util.concurrent.atomic AtomicLong]))

;; ---- per-test reset -------------------------------------------------------
;;
;; Mirrors the flows_test.clj / flows_concurrency_stress_test.clj fixture so
;; each deftest starts from a clean registrar / frames / flows / last-inputs
;; state.

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (trace/clear-listeners!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  ;; EP-0002 (rf2-5q7um6): reg-flow is context-required frame-local — an
  ;; ambient call under no scope raises :rf.error/no-frame-context. Pin
  ;; :rf/default (an ordinary frame) as the established scope for the body.
  (frame/ensure-default-frame!)
  (binding [frame/*current-frame* :rf/default]
    (test-fn)))

(use-fixtures :each reset-runtime)

;; ---------------------------------------------------------------------------
;; 1. Deterministic unit-level repro — the rollback must touch ONLY the
;;    draining frame's container (no thread interleaving needed).
;;
;; This is the bead's "easiest unit-level repro": seed frame B's row to V2
;; directly (simulating B's just-committed advance), then drive frame A's
;; throwing-flow drain via the late-bound `run-flows-on-db`. A's drain-start
;; snapshot predates nothing of B's — B lives in its own atom. Assert B's
;; row is NOT reverted. Pre-fix the global `reset!` would have clobbered it.
;; ---------------------------------------------------------------------------

(deftest rollback-does-not-clobber-sibling-frame-row-deterministic
  (testing "frame A's throwing-flow rollback leaves frame B's last-inputs row intact"
    (rf/reg-frame :a {:doc "frame A — has a throwing flow"})
    (rf/reg-frame :b {:doc "frame B — sibling, drains successfully"})

    ;; Frame A: a flow that always throws when it recomputes.
    (rf/reg-flow {:id     :flow-x
                  :inputs [[:n]]
                  :derive (fn [_] (throw (ex-info "boom-A" {})))
                  :output-path   [:out]}
                 {:frame :a})
    ;; Frame B: the SAME flow id registered against B with a benign output.
    (rf/reg-flow {:id     :flow-x
                  :inputs [[:n]]
                  :derive (fn [n] (* 2 (or n 0)))
                  :output-path   [:out]}
                 {:frame :b})

    ;; Simulate B having drained to completion: its dirty-check row is V2.
    (registry/set-frame-flow-last-inputs! :b :flow-x [42])
    (is (= [42] (registry/get-frame-flow-last-inputs :b :flow-x))
        "precondition: B's row is seeded to V2 = [42]")

    ;; Drive frame A's drain directly. A's flow throws, so run-flows-on-db
    ;; rolls back A's OWN container. The throw propagates — catch it.
    (is (thrown? Throwable
                 (flows/run-flows-on-db :a {:n 7} nil))
        "A's flow throw propagates out of run-flows-on-db")

    ;; THE ASSERTION: B's row is untouched by A's rollback.
    (is (= [42] (registry/get-frame-flow-last-inputs :b :flow-x))
        "B's last-inputs row survives A's throwing-flow rollback (rf2-94ol5)")
    (is (= [42] (get-in (flows/last-inputs-snapshot) [:flow-x :b]))
        "the aggregated snapshot still shows B's row")))

;; ---------------------------------------------------------------------------
;; 2. Single-frame rollback still works — atomicity within ONE frame is
;;    unchanged by the per-frame restructure.
;;
;; A prior successful flow's advance on the SAME frame must be rolled back
;; when a later flow on that frame throws (so it re-attempts next drain).
;; This is the per-frame mirror of
;; flows_trace_test.clj/failed-flow-rolls-back-last-inputs-so-prior-flows-retry;
;; pinned here so the structural change doesn't silently regress the
;; same-frame atomicity contract it preserves.
;; ---------------------------------------------------------------------------

(deftest single-frame-rollback-still-reverts-prior-flow-advance
  (testing "on one frame, a throwing flow rolls back a prior flow's last-inputs advance"
    (rf/reg-frame :solo {:doc "single frame"})
    (let [a-row-before (atom nil)]
      ;; :A succeeds (advances its row); :B (downstream of :A's output)
      ;; throws — so :A's advance must be rolled back.
      (rf/reg-flow {:id     :A
                    :inputs [[:n]]
                    :derive (fn [n] (* 10 (or n 0)))
                    :output-path   [:a-out]}
                   {:frame :solo})
      (rf/reg-flow {:id     :B
                    :inputs [[:a-out]]
                    :derive (fn [_] (throw (ex-info "boom-B" {})))
                    :output-path   [:b-out]}
                   {:frame :solo})
      (is (thrown? Throwable (flows/run-flows-on-db :solo {:n 5} nil)))
      (reset! a-row-before (registry/get-frame-flow-last-inputs :solo :A))
      (is (nil? @a-row-before)
          ":A's last-inputs advance was rolled back (single-frame atomicity intact)")
      (is (nil? (registry/get-frame-flow-last-inputs :solo :B))
          ":B never advanced (it threw)"))))

;; ---------------------------------------------------------------------------
;; 3. JVM concurrency stress — the gap that hid the bug.
;;
;; Frame A repeatedly drains a flow that ALWAYS throws; frame B repeatedly
;; drains a SUCCESSFUL flow whose inputs are STABLE after the first drain.
;; Both run in lockstep on separate threads with no global serialization —
;; exactly the interleaving the per-frame drain-locks permit.
;;
;; Invariant: B's flow `:derive` fires EXACTLY ONCE across all of B's
;; drains. The first drain recomputes (input changed from absent → [7]);
;; every subsequent drain has IDENTICAL inputs ([7] each time) so the
;; dirty-check MUST skip. Pre-fix, frame A's concurrent throwing-drain
;; would `reset!` the global atom and clobber B's just-advanced row, so
;; B's next same-input drain would spuriously recompute — driving the
;; `:derive` count above 1. With per-frame containers B's row is in its
;; own atom and A's rollback can't reach it, so the count stays at 1.
;;
;; A secondary invariant pins the consequence chain: B emits exactly ONE
;; `:rf.flow/computed` trace (the first, genuine recompute) — never a
;; spurious one for a no-op write.
;; ---------------------------------------------------------------------------

(def ^:private stress-iters
  (or (some-> (System/getenv "RF2_94OL5_STRESS_ITERS") Long/parseLong)
      4000))

(deftest concurrent-throwing-drain-does-not-clobber-sibling-dirty-check
  (testing (str "frame A throwing-flow drains × " stress-iters
                " interleaved with frame B stable-input drains — "
                "B's flow recomputes exactly once (no spurious recompute)")
    (rf/reg-frame :rf2-94ol5/a {:doc "throwing-flow frame"})
    (rf/reg-frame :rf2-94ol5/b {:doc "stable sibling frame"})

    (rf/reg-event :rf2-94ol5/set-n (fn [{:keys [db]} [_ n]] {:db (assoc db :n n)}))

    ;; Frame A: a flow that throws on every recompute. A's input changes
    ;; every iter so it ALWAYS recomputes (and therefore always rolls back).
    (rf/reg-flow {:id     :rf2-94ol5/throws
                  :inputs [[:n]]
                  :derive (fn [_] (throw (ex-info "boom-A" {})))
                  :output-path   [:out]}
                 {:frame :rf2-94ol5/a})

    ;; Frame B: a successful flow with STABLE inputs after the first drain.
    (let [b-output-calls (AtomicLong. 0)]
      (rf/reg-flow {:id     :rf2-94ol5/doubles
                    :inputs [[:n]]
                    :derive (fn [n]
                              (.incrementAndGet b-output-calls)
                              (* 2 (or n 0)))
                    :output-path   [:out]}
                   {:frame :rf2-94ol5/b})

      ;; Count B's :rf.flow/computed traces (the spurious-recompute symptom).
      (let [b-computed (AtomicLong. 0)]
        (trace/register-listener!
          ::b-computed-watch
          (fn [ev]
            (when (and (= :rf.flow/computed (:operation ev))
                       (= :rf2-94ol5/b (-> ev :tags :frame)))
              (.incrementAndGet b-computed))))

        ;; Seed B once so it has its first (genuine) recompute, then hold
        ;; its input stable for the remainder.
        (rf/dispatch-sync [:rf2-94ol5/set-n 7] {:frame :rf2-94ol5/b})

        (let [latch    (CountDownLatch. 1)
              ;; Thread A: drive A's throwing drain repeatedly with a
              ;; CHANGING input so it always recomputes-then-rolls-back.
              fut-a    (future
                         (.await latch)
                         (dotimes [i stress-iters]
                           ;; A's flow throws — the router converts the
                           ;; cascade-level throw to a trace; dispatch-sync
                           ;; does not re-throw. Drive with a fresh :n each
                           ;; iter so A's dirty-check always fires.
                           (rf/dispatch-sync [:rf2-94ol5/set-n (inc i)]
                                             {:frame :rf2-94ol5/a}))
                         :a-done)
              ;; Thread B: drain B repeatedly with the SAME input (7) so
              ;; every drain after the seed MUST dirty-check-skip.
              fut-b    (future
                         (.await latch)
                         (dotimes [_ stress-iters]
                           (rf/dispatch-sync [:rf2-94ol5/set-n 7]
                                             {:frame :rf2-94ol5/b}))
                         :b-done)]
          (.countDown latch)
          (is (not= ::timeout (deref fut-a 120000 ::timeout))
              "thread A completed within 120s")
          (is (not= ::timeout (deref fut-b 120000 ::timeout))
              "thread B completed within 120s")

          (trace/unregister-listener! ::b-computed-watch)

          ;; THE INVARIANT: B's :derive fired exactly once. Pre-fix, A's
          ;; concurrent rollback clobbering B's row would push this above 1.
          (is (= 1 (.get b-output-calls))
              (str "B's flow :derive must fire EXACTLY once (the genuine "
                   "first recompute); every later same-input drain must "
                   "dirty-check-skip. Got " (.get b-output-calls)
                   " — a value > 1 means frame A's throwing-flow rollback "
                   "clobbered B's dirty-check row (rf2-94ol5 regression)."))

          ;; Secondary: exactly one :rf.flow/computed trace for B.
          (is (= 1 (.get b-computed))
              (str "B must emit exactly one :rf.flow/computed (the genuine "
                   "first recompute); got " (.get b-computed)
                   ". A spurious recompute would emit extra computed traces "
                   "+ a no-op :db write + sub invalidation."))

          ;; B's app-db is correct regardless: 2 × 7 = 14.
          (is (= 14 (:out (rf/app-db-value :rf2-94ol5/b)))
              "B's flow output is correct (2 × 7)")

          ;; B's last-inputs row survives intact at [7].
          (is (= [7] (registry/get-frame-flow-last-inputs :rf2-94ol5/b
                                                          :rf2-94ol5/doubles))
              "B's last-inputs row is intact at [7] after the stress"))))))
