(ns re-frame.substrate.spine-glitch-free-cljs-test
  "Single-recompute + single-notification coverage for the substrate-
  spine's derived-value epoch scheduler (rf2-i21f5).

  The bug: a naive spine wires one `add-watch` per source that recomputes
  and notifies INLINE. A layer-2+ derived value with N changed inputs then
  recomputes once per changed input — the first input's notify drives the
  downstream recompute, the second input's notify drives it AGAIN, etc.
  N recomputes per app-db change instead of one. Each redundant recompute
  re-runs the user's sub body and emits a `:sub/run` trace; a layer-3 sub
  re-fires per redundant layer-2 notification, fanning the waste across
  the whole `:<-` graph. Reagent is immune (native batched `r/flush!`);
  the spine must satisfy the Spec 006 §Invalidation algorithm Phase 1/2/3
  contract explicitly (one recompute + one Phase-3 notification per dirty
  entry per app-db change).

  Note on values: the spine's derived value is PULL-BASED — `-deref`
  recomputes fresh from current sources — so every recompute during the
  cascade reads settled source state and the notified VALUE is always
  coherent (never a half-updated intermediate). The load-bearing defect
  the fix removes is therefore the redundant-recompute storm + over-
  notification, which these tests assert via per-derived recompute and
  notification counters.

  These tests build the spine's derived-value graph directly over plain
  source atoms sharing ONE epoch scheduler, mutate the root through the
  scheduler-aware `replace-container!` (the only app-db mutation entry
  point per Spec 006 §revertibility), and assert exactly-one recompute and
  at-most-one notification per affected derived value per write.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.disposable :as rf-disposable]
            [re-frame.substrate.spine :as spine]))

;; ---- harness --------------------------------------------------------------
;;
;; Mirrors the real sub-graph wiring: `make-state-container` for the root
;; (app-db), `make-derived-value` for every layer-1 / layer-2 sub, and the
;; scheduler-aware `replace-container!` as the single mutation entry point.
;; All derived values share ONE scheduler — exactly as `make-react-spine`
;; wires them per adapter.

(defn- build-graph []
  (let [scheduler    (spine/make-scheduler)
        make-derived (spine/make-derived-value-fn "rf-glitch-" scheduler)
        replace!     (spine/make-replace-container-fn scheduler)
        root         (spine/make-state-container {:a 1 :b 10})]
    {:scheduler    scheduler
     :make-derived make-derived
     :replace!     replace!
     :root         root}))

;; ---- laziness (rf2-ee38b.1 P2) --------------------------------------------

(deftest make-derived-value-is-lazy-no-compute-at-construction
  (testing "constructing a derived value does NOT invoke compute-fn until
            the first deref — matching Reagent's lazy make-reaction and the
            plain-atom recompute-on-deref adapters (rf2-ee38b.1 P2). The
            React-hook spine formerly seeded prev-state eagerly with
            `(recompute)`, running the user sub body (and emitting
            :rf.sub/run) at subscribe-time rather than deref-time."
    (let [{:keys [make-derived root]} (build-graph)
          runs (atom 0)
          d    (make-derived [root] (fn [db] (swap! runs inc) (:a db)))]
      (is (= 0 @runs)
          "compute-fn NOT invoked at construction — body runs on demand")
      (is (= 1 @d) "first deref computes the value")
      (is (= 1 @runs) "compute-fn ran exactly once, on first deref")
      ;; A second deref is pull-based (recompute) — also runs the body, but
      ;; the point of the contract is ZERO runs before the first read.
      @d
      (is (= 2 @runs) "subsequent derefs recompute (pull-based), as before"))))

;; ---- single-input layer-1 (regression guard) ------------------------------

(deftest layer-1-single-input-notifies-once
  (testing "a layer-1 derived value over the root notifies once per
            replace-container! when its slice changes by ="
    (let [{:keys [make-derived replace! root]} (build-graph)
          l1     (make-derived [root] (fn [db] (:a db)))
          notes  (atom [])]
      ;; rf2-ee38b.1: derived values are LAZY — the first deref establishes
      ;; the baseline (the sub-cache always reads the value on subscribe).
      ;; Deref before the change so the notification carries the real prior
      ;; derived value rather than the `unset` sentinel.
      (is (= 1 @l1) "baseline deref establishes prev-state")
      (add-watch l1 :w (fn [_ _ prev nu] (swap! notes conj [prev nu])))
      (replace! root {:a 2 :b 10})
      (is (= [[1 2]] @notes)
          "exactly one notification carrying old→new derived value")
      (is (= 2 @l1) "deref reflects the settled value"))))

(deftest layer-1-value-equal-write-does-not-notify
  (testing "a write that leaves the layer-1 slice =-unchanged does NOT
            notify (Spec 006 §Value-equal means no propagation)"
    (let [{:keys [make-derived replace! root]} (build-graph)
          l1     (make-derived [root] (fn [db] (:a db)))
          notes  (atom 0)]
      ;; rf2-ee38b.1: deref to establish the lazy baseline before watching,
      ;; mirroring the sub-cache's read-on-subscribe.
      (is (= 1 @l1) "baseline deref establishes prev-state")
      (add-watch l1 :w (fn [_ _ _ _] (swap! notes inc)))
      ;; :b changes; :a — the only slice l1 reads — does not.
      (replace! root {:a 1 :b 99})
      (is (= 0 @notes)
          "no notification when the derived value is =-unchanged"))))

;; ---- multi-input layer-2 (the over-recompute case) ------------------------

(deftest layer-2-multi-input-recomputes-once-and-notifies-once
  (testing "a multi-input layer-2 derived value over TWO layer-1 inputs
            recomputes EXACTLY ONCE and notifies its watcher at most once
            per replace-container!, even when BOTH inputs change (rf2-i21f5)"
    (let [{:keys [make-derived replace! root]} (build-graph)
          l1a    (make-derived [root] (fn [db] (:a db)))
          l1b    (make-derived [root] (fn [db] (:b db)))
          recompute-count (atom 0)
          ;; Layer-2: sum of the two layer-1 inputs. With the naive
          ;; per-source-inline spine the body runs once per changed input
          ;; (twice here); the epoch scheduler coalesces to one.
          l2     (make-derived [l1a l1b]
                   (fn [a b] (swap! recompute-count inc) (+ a b)))
          seen   (atom [])]
      (add-watch l2 :w (fn [_ _ _ nu] (swap! seen conj nu)))
      (is (= 11 @l2) "baseline: 1 + 10")
      ;; Reset the counter after baseline construction + deref above.
      (reset! recompute-count 0)
      ;; ONE app-db write changes BOTH inputs.
      (replace! root {:a 2 :b 20})
      (is (= 1 @recompute-count)
          "layer-2 body recomputed EXACTLY ONCE — the N source-watch fires
           coalesced into a single flush (not once per changed input)")
      (is (= 1 (count @seen))
          "layer-2 watcher notified exactly once per replace-container!")
      (is (= [22] @seen)
          "the single notification carries the coherent value (2 + 20)")
      (is (= 22 @l2) "deref reflects the settled value"))))

(deftest layer-2-three-input-recomputes-once
  (testing "the single-recompute contract holds for a 3-input layer-2
            derived value (≥3 sources exercise the recompute-n fallback)"
    (let [scheduler    (spine/make-scheduler)
          make-derived (spine/make-derived-value-fn "rf-glitch3-" scheduler)
          replace!     (spine/make-replace-container-fn scheduler)
          root         (spine/make-state-container {:a 1 :b 2 :c 3})
          l1a    (make-derived [root] (fn [db] (:a db)))
          l1b    (make-derived [root] (fn [db] (:b db)))
          l1c    (make-derived [root] (fn [db] (:c db)))
          recompute-count (atom 0)
          l2     (make-derived [l1a l1b l1c]
                   (fn [a b c] (swap! recompute-count inc) [a b c]))
          seen   (atom [])]
      (add-watch l2 :w (fn [_ _ _ nu] (swap! seen conj nu)))
      (is (= [1 2 3] @l2))
      (reset! recompute-count 0)
      (replace! root {:a 10 :b 20 :c 30})
      (is (= 1 @recompute-count)
          "3-input layer-2 body recomputed exactly once for the epoch")
      (is (= 1 (count @seen))
          "3-input layer-2 watcher notified exactly once")
      (is (= [[10 20 30]] @seen)
          "the single notification carries the fully-settled tuple")
      (is (= [10 20 30] @l2)))))

(deftest layer-3-cascade-each-tier-notifies-once-glitch-free
  (testing "a layer-3 sub over a multi-input layer-2 sub propagates ONE
            coherent notification per tier per replace-container! — the
            cascade settles to the right value with no spurious extra
            notifications down the :<- chain.

            (Recompute counts are deliberately NOT asserted here: the
            spine is pull-based, so a downstream `-deref` of layer-2
            during layer-3's recompute legitimately re-runs layer-2's
            body. That deref-driven recompute is independent of the
            rf2-i21f5 source-watch-storm — which is pinned by the
            standalone `layer-2-multi-input-recomputes-once-and-notifies-
            once` test above.)"
    (let [{:keys [make-derived replace! root]} (build-graph)
          l1a    (make-derived [root] (fn [db] (:a db)))
          l1b    (make-derived [root] (fn [db] (:b db)))
          l2     (make-derived [l1a l1b] (fn [a b] (+ a b)))
          l3     (make-derived [l2] (fn [s] (* 100 s)))
          l2-seen (atom [])
          l3-seen (atom [])]
      (add-watch l2 :w (fn [_ _ _ nu] (swap! l2-seen conj nu)))
      (add-watch l3 :w (fn [_ _ _ nu] (swap! l3-seen conj nu)))
      (is (= 11 @l2))
      (is (= 1100 @l3))
      (replace! root {:a 2 :b 20})
      (is (= [22] @l2-seen) "layer-2 notified once with the coherent sum")
      (is (= [2200] @l3-seen)
          "layer-3 notified once with the value computed against the
           settled layer-2 — no spurious extra notification cascaded down")
      (is (= 2200 @l3)))))

;; ---- dispose mid-cascade does NOT recompute the disposed reaction ---------
;; (rf2-jgzica)

(deftest disposed-layer-2-flush-mid-cascade-does-not-recompute
  (testing "a layer-2 derived value disposed AFTER it is marked dirty but
            BEFORE the scheduler drains its queued flush must NOT re-run its
            compute-fn (the user sub body) — the queued thunk is already on
            the shared epoch scheduler and cannot be dequeued, so flush!
            itself must short-circuit on the disposed guard. Without the
            guard the memo wrapper recomputes and (in dev) emits a spurious
            :rf.sub/run for a reaction whose watchers are already cleared —
            the redundant-recompute class the epoch scheduler exists to
            prevent (rf2-i21f5). (rf2-jgzica)"
    (let [{:keys [make-derived replace! root]} (build-graph)
          l1a    (make-derived [root] (fn [db] (:a db)))
          l1b    (make-derived [root] (fn [db] (:b db)))
          ;; Layer-2 over BOTH layer-1 inputs. Its compute-fn stands in for
          ;; the user sub body; every invocation increments the counter just
          ;; as a real body would emit :rf.sub/run.
          body-runs (atom 0)
          l2     (make-derived [l1a l1b]
                   (fn [a b] (swap! body-runs inc) (+ a b)))]
      ;; Establish the lazy baseline (the sub-cache reads on subscribe), then
      ;; zero the counter so we only measure cascade-time body runs.
      (is (= 11 @l2) "baseline: 1 + 10")
      (reset! body-runs 0)
      ;; Arm the mid-cascade dispose. During the epoch drain l1a's flush
      ;; notifies first → l2 marks dirty and ENQUEUES its flush. l1b's flush
      ;; runs next; this extra watcher on l1b fires during that notify —
      ;; AFTER l2 is already queued but BEFORE l2's own flush drains — and
      ;; disposes l2. The queued l2 flush then runs against a disposed
      ;; reaction.
      (add-watch l1b :dispose-l2 (fn [_ _ _ _] (rf-disposable/-dispose l2)))
      ;; ONE app-db write changes BOTH inputs, so both l1a and l1b flush and
      ;; the dispose lands between l2's mark-dirty and its queued flush.
      (replace! root {:a 2 :b 20})
      (is (= 0 @body-runs)
          "the disposed layer-2 reaction's body did NOT re-run during the
           drain — flush! short-circuited on @disposed? (no spurious
           recompute / :rf.sub/run)"))))

(deftest direct-dispose-then-flush-does-not-recompute
  (testing "a focused variant with no cascade: a derived value is marked
            dirty (its source changes inside an epoch), disposed while the
            epoch is still open, and the queued flush must not recompute it.
            Drives the dispose deterministically by nesting it inside the
            same open epoch via a re-entrant replace! on an unrelated source
            watch — keeping the queued l1 flush pending until the outer epoch
            closes. (rf2-jgzica)"
    (let [{:keys [make-derived replace! root scheduler]} (build-graph)
          body-runs (atom 0)
          l1     (make-derived [root]
                   (fn [db] (swap! body-runs inc) (:a db)))]
      (is (= 1 @l1) "baseline deref establishes prev-state")
      (reset! body-runs 0)
      ;; Open an epoch, mark l1 dirty (enqueues its flush), dispose l1 while
      ;; the epoch is still open (queue not yet drained), then let the epoch
      ;; close and drain. The queued l1 flush must skip the recompute.
      (#'spine/with-epoch scheduler
        (fn []
          (reset! root {:a 99 :b 10})   ;; marks l1 dirty, enqueues flush
          (rf-disposable/-dispose l1))) ;; dispose before the drain
      (is (= 0 @body-runs)
          "the disposed reaction's flush did not recompute on drain"))))

;; ---- direct source mutation outside an epoch ------------------------------

(deftest layer-1-direct-source-write-still-flushes
  (testing "a direct source mutation OUTSIDE replace-container! (test /
            tooling reset!) still flushes the single affected derived
            value synchronously"
    (let [scheduler    (spine/make-scheduler)
          make-derived (spine/make-derived-value-fn "rf-direct-" scheduler)
          src          (atom 5)
          l1     (make-derived [src] (fn [x] (* x 10)))
          notes  (atom [])]
      ;; rf2-ee38b.1: deref to establish the lazy baseline before watching.
      (is (= 50 @l1) "baseline deref establishes prev-state")
      (add-watch l1 :w (fn [_ _ prev nu] (swap! notes conj [prev nu])))
      (reset! src 6)
      (is (= [[50 60]] @notes)
          "the bare reset! drained immediately (no epoch open) and notified
           once with the recomputed value"))))
