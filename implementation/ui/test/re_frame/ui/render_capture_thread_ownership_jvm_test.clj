(ns re-frame.ui.render-capture-thread-ownership-jvm-test
  "rf2-vxgfnd.171 — render capture is SINGLE-THREADED, and a render body that
  forks its site reads must be refused, not silently corrupted.

  THE DEFECT. `*ambient*` is a `^:dynamic` Var holding a `volatile!` capture.
  That isolates separate JVM renders (rf2-1llvoh) — each opens its own binding —
  but Clojure CONVEYS dynamic bindings into `future`/`pmap` workers. A render
  body that forks its site reads and joins them therefore hands every child
  thread the SAME non-thread-safe volatile, and their `vswap!`s race. Sites are
  lost: the capture that reaches layout commit is missing ownership the render
  actually declared, and nothing reports it. Measured on this fixture with the
  owner check disabled — 60 trials forking all 16 sites — only 2 renders
  captured all 16; the other 58 returned between 10 and 15 sites,
  nondeterministically, with nothing reporting the loss.

  THE CORRECTION. Deterministic compiler-ordered capture is the contract, so the
  contract is ENFORCED rather than engineered around: the capture records the
  thread that opened it, and `sub-read` throws a typed
  `:rf.error/ui-tree-malformed` from any other thread — BEFORE probing and
  BEFORE mutating the capture, so a rejected fork commits no partial ownership.

  WHY THE OWNER RIDES IN THE CAPTURE VALUE. Binding conveyance is the hazard
  itself, so the guard cannot depend on thread-local state: a `^:dynamic` Var or
  a ThreadLocal would either be conveyed along with the capture (and agree with
  the child) or never reach the child at all. A plain value inside the conveyed
  map is read identically by every thread that receives it, so a child compares
  the ORIGINATING thread against its own and disagrees — for `future`, `pmap`,
  `bound-fn`, or an executor submission alike. `executor-submitted-bound-fn-is-
  refused` pins exactly that route.

  JVM-only: on CLJS the owner is `nil` and the guard reader-conditionals away.

  Removing the owner check makes `forked-*-is-refused` go green-to-red and
  `stress-*` reproduce the incomplete capture directly."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.live-frame :as live-frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.ui.reactive :as reactive])
  (:import [java.util.concurrent CyclicBarrier Executors TimeUnit]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [f]
    (reactive/reset-scheduler!)
    (try (f) (finally (reactive/reset-scheduler!)))))

(def ^:private site-count 16)

(defn- reg-site-subs! []
  (doseq [i (range site-count)]
    (rf/reg-sub (keyword "cap" (str "s" i)) (fn [db _] (get db i i)))))

(defn- ex-data-of
  "`ex-data` of the first `ExceptionInfo` in the thrown cause chain, or nil if
  nothing threw. Walks the chain because a child-thread throw surfaces at
  `deref`/`.get` wrapped in an `ExecutionException` — the guard's own error is
  the CAUSE, not the throwable the caller sees."
  [thunk]
  (try (thunk) nil
       (catch Throwable t
         (loop [e t]
           (cond
             (instance? clojure.lang.ExceptionInfo e) (ex-data e)
             (and (some? (.getCause e))
                  (not (identical? e (.getCause e)))) (recur (.getCause e))
             :else nil)))))

(defn- site-id [i] (keyword "cap" (str "site" i)))

(defn- read-site!
  "One compiled-shaped site read: `(sub-read sid query)`."
  [i]
  (reactive/sub-read (site-id i) [(keyword "cap" (str "s" i))]))

;; ---------------------------------------------------------------------------
;; The refusal — future, pmap, and an executor-submitted bound-fn.
;; ---------------------------------------------------------------------------

(deftest forked-future-site-read-is-refused-with-a-typed-error
  (reg-site-subs!)
  (let [f    (rf/make-frame {:initial-events [[:rf/set-db {}]]})
        cell (reactive/make-cell ::future-fork)
        data (atom nil)]
    (rf/with-frame f
      (reactive/with-capture
        cell
        (fn []
          ;; The parent thread's own read is legitimate and must still work.
          (read-site! 0)
          (reset! data (ex-data-of #(deref (future (read-site! 1)))))
          nil)))
    (let [d @data]
      (is (some? d) "the conveyed child thread's read THREW rather than racing")
      (is (= :rf.error/ui-tree-malformed (:rf.error/id d))
          "typed error, reusing the existing malformed-tree id")
      (is (= 're-frame.ui.reactive/sub-read (:where d)))
      (is (= (site-id 1) (:site-id d))
          "the evidence names the offending site so the fork can be removed")
      (is (string? (:owner-thread d)))
      (is (string? (:current-thread d)))
      (is (not= (:owner-thread d) (:current-thread d))
          "and names both threads — the render's and the fork's"))))

(deftest forked-pmap-site-reads-are-refused
  (reg-site-subs!)
  (let [f    (rf/make-frame {:initial-events [[:rf/set-db {}]]})
        cell (reactive/make-cell ::pmap-fork)
        seen (atom nil)]
    (rf/with-frame f
      (reactive/with-capture
        cell
        (fn []
          (reset! seen (ex-data-of #(doall (pmap read-site! (range 1 site-count)))))
          nil)))
    (is (= :rf.error/ui-tree-malformed (:rf.error/id @seen))
        "pmap conveys the binding exactly as future does, and is refused too")))

(deftest executor-submitted-bound-fn-is-refused
  (testing "the ThreadLocal-shaped escape route: an explicitly `bound-fn`-wrapped
            task on a pool thread still carries the capture, and is still caught
            — because the owner is a VALUE inside the conveyed map, not
            thread-local state"
    (reg-site-subs!)
    (let [f    (rf/make-frame {:initial-events [[:rf/set-db {}]]})
          cell (reactive/make-cell ::executor-fork)
          pool (Executors/newSingleThreadExecutor)
          seen (atom nil)]
      (try
        (rf/with-frame f
          (reactive/with-capture
            cell
            (fn []
              (let [task (bound-fn [] (ex-data-of #(read-site! 2)))
                    fut  (.submit pool ^Callable task)]
                (reset! seen (.get fut 10 TimeUnit/SECONDS)))
              nil)))
        (finally (.shutdownNow pool)))
      (is (= :rf.error/ui-tree-malformed (:rf.error/id @seen)))
      (is (= 're-frame.ui.reactive/sub-read (:where @seen))))))

;; ---------------------------------------------------------------------------
;; The capture the guard protects: no PARTIAL ownership is committed by a
;; rejected fork, and the render thread's own sites are all present.
;; ---------------------------------------------------------------------------

(deftest rejected-fork-commits-no-partial-ownership
  (reg-site-subs!)
  (let [f       (rf/make-frame {:initial-events [[:rf/set-db {}]]})
        cell    (reactive/make-cell ::no-partial)
        [_ cap] (rf/with-frame f
                  (reactive/with-capture
                    cell
                    (fn []
                      (read-site! 0)
                      (read-site! 1)
                      (dotimes [i 4]
                        (ex-data-of #(deref (future (read-site! (+ 10 i))))))
                      nil)))
        sids    (set (:order cap))]
    (is (= #{(site-id 0) (site-id 1)} sids)
        "the capture holds EXACTLY the two sites the render thread read — the
         four refused forks left nothing behind")
    (is (= 2 (count (:by-site cap)))
        "and no half-written site record")))

;; ---------------------------------------------------------------------------
;; Separate top-level renders on different threads must remain valid: each opens
;; its own capture and records its own owner, so neither is a "fork" of the
;; other. This is the property the guard must NOT break (rf2-1llvoh).
;; ---------------------------------------------------------------------------

(deftest concurrent-top-level-renders-stay-isolated-and-complete
  (reg-site-subs!)
  (let [f       (rf/make-frame {:initial-events [[:rf/set-db {}]]})
        threads 4
        barrier (CyclicBarrier. threads)
        run     (fn [t]
                  (let [cell (reactive/make-cell (keyword "cap" (str "top" t)))]
                    (rf/with-frame f
                      (reactive/with-capture
                        cell
                        (fn []
                          (.await barrier 10 TimeUnit/SECONDS)
                          (dotimes [i site-count] (read-site! i))
                          nil)))))
        results (mapv deref (mapv #(future (run %)) (range threads)))]
    (doseq [[_ cap] results]
      (is (= site-count (count (:order cap)))
          "each independent top-level render captured ALL its sites — the guard
           refuses conveyed CHILD threads, never separate renders")
      (is (apply distinct? (:order cap)) "and recorded each site once"))))

;; ---------------------------------------------------------------------------
;; Nested same-thread captures keep save/restore and deterministic ordering —
;; the guard must be invisible to them.
;; ---------------------------------------------------------------------------

(deftest nested-same-thread-captures-preserve-save-restore-and-order
  (reg-site-subs!)
  (let [f     (rf/make-frame {:initial-events [[:rf/set-db {}]]})
        outer (reactive/make-cell ::outer)
        inner (reactive/make-cell ::inner)
        inner-cap (atom nil)
        [_ outer-cap]
        (rf/with-frame f
          (reactive/with-capture
            outer
            (fn []
              (read-site! 0)
              (let [[_ icap] (reactive/with-capture
                               inner
                               (fn [] (read-site! 5) (read-site! 6) nil))]
                (reset! inner-cap icap))
              (read-site! 1)
              nil)))]
    (is (= [(site-id 0) (site-id 1)] (:order outer-cap))
        "the outer capture is restored after the nested one and keeps compiler
         order — the nested sites did not leak into it")
    (is (= [(site-id 5) (site-id 6)] (:order @inner-cap))
        "and the nested capture holds exactly its own sites, in order")))

;; ---------------------------------------------------------------------------
;; The stress probe from the bead. WITH the guard every trial either completes
;; on the render thread or fails loudly; NO trial silently returns an incomplete
;; capture. Removing the owner check makes this reproduce the lossy capture.
;; ---------------------------------------------------------------------------

(deftest stress-forked-render-never-silently-returns-an-incomplete-capture
  (reg-site-subs!)
  (let [f       (rf/make-frame {:initial-events [[:rf/set-db {}]]})
        trials  60
        outcome (for [t (range trials)]
                  (let [cell   (reactive/make-cell (keyword "cap" (str "stress" t)))
                        thrown (atom 0)
                        [_ cap] (rf/with-frame f
                                  (reactive/with-capture
                                    cell
                                    (fn []
                                      (doall
                                       (mapv deref
                                             (mapv (fn [i]
                                                     (future
                                                       (when (ex-data-of #(read-site! i))
                                                         (swap! thrown inc))))
                                                   (range site-count))))
                                      nil)))]
                    {:captured (count (:order cap)) :refused @thrown}))]
    (is (every? #(= site-count (:refused %)) outcome)
        "every forked site read in every trial was REFUSED — pre-fix they were
         admitted and raced")
    (is (every? #(zero? (:captured %)) outcome)
        "and no trial produced a partially-populated capture. PRE-FIX this is
         where the loss showed: captures arrived with between 1 and 15 of the 16
         sites, nondeterministically, with nothing reporting it")))
