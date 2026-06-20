(ns re-frame.flows-reg-flow-toctou-test
  "Per rf2-qxwib — JVM regression coverage for the reg-flow
  check-and-insert TOCTOU.

  THE BUG (pre-fix): `reg-flow`'s cycle detection was check-THEN-act.
  It read `@flows` once, built a PROSPECTIVE flow-map, ran
  `topo/topo-sort` on it, and — in a SEPARATE step — committed via
  `swap!`. Two threads registering on the SAME frame two flows that
  together form a dependency cycle (T1 registers A whose `:inputs` read
  B's `:output-path`; T2 registers B whose `:inputs` read A's `:output-path`) could
  interleave so that BOTH read the frame's pre-cycle state, BOTH pass
  their individual prospective check (neither sees the other's
  not-yet-committed flow), and BOTH commit — leaving a CYCLIC registry.
  Drain-time topo-sort then throws `:rf.error/flow-cycle` on every drain
  of that frame, defeating the Spec 013 §Cycle detection promise that a
  cycle is caught AT REGISTRATION.

  THE FIX: fold the cycle check INTO the `swap!` update fn so the
  read-validate-commit is one atomic compare-and-set. The update fn
  reads the committed frame-map, runs topo-sort on the prospective map,
  and only returns the assoc'd map if that passes. `swap!` re-invokes
  the update fn (re-reading committed state, re-validating) on
  contention, so once ONE of the racing pair commits, the OTHER's retry
  sees the committed edge and its topo-sort throws — exactly one of the
  pair is admitted, and the committed registry is NEVER cyclic.

  CLJS is single-threaded; this race is JVM-only by construction. The
  rf2-ztw5p concurrency-stress test deliberately partitions by frame
  (each thread owns its own frame, and runs its own cyc-a/cyc-b probe
  sequentially within that frame), so the SAME-frame interleaving this
  bug needs is out of its scope by design. This namespace targets it
  directly: two threads, ONE shared frame, the cycle-forming pair
  registered in lockstep, asserting the committed registry never holds
  an admitted cycle.

  Many rounds under a `CyclicBarrier` release maximise the interleaving
  window; round count is env-overridable via
  `RF2_QXWIB_TOCTOU_ROUNDS`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.flows :as flows]
            [re-frame.flows.topo :as topo]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace])
  (:import [java.util.concurrent CyclicBarrier]
           [java.util.concurrent.atomic AtomicLong]))

;; ---- per-test reset (mirrors flows_concurrency_stress_test.clj) -----------

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

;; Round count. Each round opens a fresh interleaving window on the
;; shared frame; the pre-fix race needs only ONE round where both
;; threads read the pre-cycle state before either commits, so a few
;; thousand rounds surface it reliably across box/JVM scheduling. CI
;; can dial down via the env override for a smoke pass.
(def ^:private rounds
  (or (some-> (System/getenv "RF2_QXWIB_TOCTOU_ROUNDS") Long/parseLong)
      4000))

(deftest concurrent-same-frame-reg-flow-cannot-admit-a-cycle
  ;; rf2-qxwib — the atomic check-and-insert regression.
  ;;
  ;; Per round, on ONE shared frame, two threads simultaneously register
  ;; the two halves of a cycle-forming pair:
  ;;
  ;;   A : :inputs [[:b]]  :output-path [:a]   (A depends on B's output slot)
  ;;   B : :inputs [[:a]]  :output-path [:b]   (B depends on A's output slot)
  ;;
  ;; Registered together they form the cycle :a → :b → :a. The
  ;; per-round flow-ids are namespaced by round so a stale registrar /
  ;; flows entry from a prior round cannot mask the race.
  ;;
  ;; INVARIANT (the one that catches the bug): after each round, the
  ;; frame's COMMITTED flow-map — read via `flows/flows-snapshot` —
  ;; must NOT be cyclic. We assert this by running `topo/topo-sort` on
  ;; the committed map: it must not throw. Pre-fix, a round that
  ;; interleaved the check-then-act admitted BOTH flows, so the
  ;; committed map was cyclic and this topo-sort throws — the test
  ;; fails. Post-fix the atomic swap admits at most one of the pair, so
  ;; the committed map is always acyclic.
  ;;
  ;; SECONDARY INVARIANT: EXACTLY one of the two registrations must be
  ;; REJECTED with `:rf.error/flow-cycle` (the loser of the CAS race, or
  ;; the second of two serialised registrations, sees the committed edge
  ;; and throws) while the other commits. We count rejections; with a
  ;; real cycle-forming pair on a shared frame, every round admits one
  ;; half and rejects one half, so the rejection count must EQUAL rounds.
  ;; A short count means a cycle was admitted silently (invariant 1 also
  ;; catches it); an over count means a round rejected BOTH halves — a
  ;; spurious double-rejection regression invariant 1 would miss. A
  ;; double-rejection cannot occur legitimately here: we clear the
  ;; round's flow-ids only AFTER the asserts, so the first commit is
  ;; never cleared mid-round. Asserting `=` makes both directions an
  ;; independent signal.
  (testing (str rounds " rounds: two threads, one shared frame, "
                "cycle-forming reg-flow pair in lockstep — committed "
                "registry is never cyclic, at least one half rejected")
    (let [frame-id    :qxwib.toctou/shared
          rejections  (AtomicLong. 0)
          ;; Surfaces the FIRST observed cyclic-commit so a failure
          ;; report names the offending round + committed map rather
          ;; than just "topo-sort threw".
          first-bad   (atom nil)]
      (rf/reg-frame frame-id {:doc "shared frame for the reg-flow TOCTOU repro"})
      (dotimes [round rounds]
        (let [a-id    (keyword "qxwib.toctou" (str "a-" round))
              b-id    (keyword "qxwib.toctou" (str "b-" round))
              ;; A reads B's output path; B reads A's output path —
              ;; together a cycle.
              flow-a  {:id a-id :inputs [[:b]] :derive identity :output-path [:a]}
              flow-b  {:id b-id :inputs [[:a]] :derive identity :output-path [:b]}
              barrier (CyclicBarrier. 2)
              reg!    (fn [flow]
                        (fn []
                          (.await barrier)        ; release both at once
                          (try
                            (rf/reg-flow flow {:frame frame-id})
                            (catch Throwable t
                              (when (re-find #":rf.error/flow-cycle"
                                             (or (ex-message t) ""))
                                (.incrementAndGet rejections))))))
              fa      (future ((reg! flow-a)))
              fb      (future ((reg! flow-b)))]
          ;; Bounded join — a hang (e.g. a swap retry livelock) should
          ;; surface as a visible failure, not a stuck CI run.
          (let [va (deref fa 30000 ::timeout)
                vb (deref fb 30000 ::timeout)]
            (is (and (not= ::timeout va) (not= ::timeout vb))
                (str "round " round ": both reg-flow threads completed "
                     "within 30s")))
          ;; THE load-bearing assertion: the committed registry for the
          ;; shared frame is acyclic. topo-sort throws :rf.error/flow-cycle
          ;; iff a cycle was admitted.
          (let [committed (get (flows/flows-snapshot) frame-id)]
            (try
              (topo/topo-sort committed)
              (catch Throwable t
                (when (nil? @first-bad)
                  (reset! first-bad {:round    round
                                     :committed committed
                                     :ex-data  (ex-data t)})))))
          ;; Clean both ids off the shared frame before the next round so
          ;; each round starts from the frame's pre-cycle state (the
          ;; window the race needs). clear-flow no-ops cleanly on the id
          ;; that was rejected / never committed.
          (flows/clear-flow a-id {:frame frame-id})
          (flows/clear-flow b-id {:frame frame-id})))

      ;; --- Invariant 1: no round ever admitted a cycle. ---------------
      (is (nil? @first-bad)
          (str "A concurrent same-frame reg-flow round admitted a cycle "
               "(committed registry is cyclic — the rf2-qxwib TOCTOU). "
               "First offending round: " (pr-str @first-bad)))

      ;; --- Invariant 2: every round rejected EXACTLY one half. --------
      ;; With a genuine cycle-forming pair on a shared frame, exactly one
      ;; half is admitted and exactly one is rejected: the atomic swap
      ;; admits the CAS winner (or the first of two serialised
      ;; registrations) and the loser/second re-reads the committed edge
      ;; and throws (Spec 013 §Cycle detection — caught at REGISTRATION,
      ;; not deferred to drain). A SHORT count means a round admitted the
      ;; cycle silently (also caught by invariant 1); an OVER count means
      ;; a round rejected BOTH halves — a spurious double-rejection
      ;; interleaving regression that invariant 1 (acyclic commit) would
      ;; not catch. Asserting exact equality makes either an independent
      ;; signal. (We clear the round's flow-ids only AFTER these asserts,
      ;; so the first commit is never cleared mid-round — a legitimate
      ;; double-rejection cannot occur.)
      (is (= rounds (.get rejections))
          (str "Expected exactly " rounds " cycle rejections (exactly one "
               "per round); got " (.get rejections) ". A short count "
               "means a round admitted the cycle silently instead of "
               "rejecting a half at registration; an over count means a "
               "round spuriously rejected BOTH halves.")))))
