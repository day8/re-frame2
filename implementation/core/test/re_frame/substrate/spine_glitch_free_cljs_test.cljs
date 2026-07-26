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

;; ---- throw mid-drain: drains the tail (no strand) AND surfaces ------------
;; (rf2-l3lelt + rf2-qcmzc)

(deftest throwing-thunk-mid-drain-does-not-strand-downstream
  (testing "a derived value whose compute-fn THROWS during the epoch drain
            must not strand the downstream thunks still queued behind it:
            the surviving sibling drains its flush in the SAME epoch (BEFORE
            the throw surfaces), its dirty? guard is cleared so a LATER source
            change can re-enqueue it (no stuck-dirty strand), AND the throw is
            SURFACED to the caller after the drain completes (rf2-qcmzc)
            rather than swallowed by the per-thunk guard.

            Wiring mirrors the real graph: both layer-1 values watch the root
            and so both enqueue a flush thunk on the ONE shared scheduler per
            replace-container!. The thrower is constructed FIRST, so its flush
            thunk is drained FIRST — meaning the surviving sibling is the
            still-queued downstream entry a bare (rethrowing) drain would
            strand (its flush never running, its dirty? stuck true, never
            re-markable by `mark-dirty!`'s `(when-not @dirty? …)` guard).
            (rf2-l3lelt)"
    (let [{:keys [make-derived replace! root]} (build-graph)
          boom?      (atom false)
          ;; Thrower: reads :a, throws on demand. Constructed FIRST so its
          ;; flush thunk enqueues (and drains) ahead of the survivor's.
          thrower    (make-derived [root]
                       (fn [db] (when @boom? (throw (js/Error. "boom")))
                         (:a db)))
          ;; Survivor: the downstream thunk a stranding drain would skip.
          survivor   (make-derived [root] (fn [db] (:b db)))
          notes      (atom [])]
      ;; Lazy baselines (the sub-cache reads on subscribe).
      (is (= 1 @thrower) "thrower baseline")
      (is (= 10 @survivor) "survivor baseline")
      (add-watch survivor :w (fn [_ _ prev nu] (swap! notes conj [prev nu])))
      ;; Arm the throw, then ONE replace-container! queues BOTH flush thunks;
      ;; the thrower's recompute throws mid-drain.
      (reset! boom? true)
      (let [caught (atom ::none)]
        (try (replace! root {:a 99 :b 20})
             (catch :default e (reset! caught e)))
        (is (instance? js/Error @caught)
            "the mid-drain recompute throw is SURFACED to the caller AFTER the
             drain (rf2-qcmzc) — no longer swallowed by the per-thunk guard")
        (is (= "boom" (.-message @caught))
            "the surfaced value is the original thrown error (identity carried
             through the drain's caller/error channel)"))
      (is (= [[10 20]] @notes)
          "the survivor's flush STILL ran in the same epoch BEFORE the throw
           surfaced — it was not stranded behind the throwing thunk")
      (is (= 20 @survivor) "survivor deref reflects the settled value")
      ;; The load-bearing strand assertion: disarm the throw and change the
      ;; survivor's slice AGAIN. With the bug its dirty? was stuck true (its
      ;; flush never ran on the first write), so this second change could
      ;; never re-enqueue it and the watcher would NOT fire. With the fix the
      ;; survivor drained cleanly the first time, so it re-marks and notifies.
      (reset! boom? false)
      (reset! notes [])
      (replace! root {:a 99 :b 30})
      (is (= [[20 30]] @notes)
          "a LATER source change re-enqueues + flushes the survivor — no
           stuck-dirty strand (the surviving thunk's guard was cleared)"))))

(deftest throwing-thunk-clears-its-own-dirty-guard
  (testing "the THROWING derived value itself leaves no stuck-dirty guard:
            flush! resets dirty? BEFORE recompute, so even though its
            recompute threw, a later (non-throwing) source change re-enqueues
            and flushes it normally. (rf2-l3lelt)"
    (let [{:keys [make-derived replace! root]} (build-graph)
          boom?    (atom false)
          thrower  (make-derived [root]
                     (fn [db] (when @boom? (throw (js/Error. "boom")))
                       (:a db)))
          notes    (atom [])]
      (is (= 1 @thrower) "baseline deref establishes prev-state")
      (add-watch thrower :w (fn [_ _ prev nu] (swap! notes conj [prev nu])))
      ;; First write throws mid-drain.
      (reset! boom? true)
      (let [caught (atom ::none)]
        (try (replace! root {:a 2 :b 10})
             (catch :default e (reset! caught e)))
        (is (instance? js/Error @caught)
            "the throw is SURFACED to the caller after the drain (rf2-qcmzc)"))
      (is (= [] @notes)
          "the throwing flush did not notify (its recompute never returned)")
      ;; Disarm; a fresh change must re-enqueue + flush the (formerly
      ;; throwing) value — proving its dirty? guard was not left stuck true.
      (reset! boom? false)
      (replace! root {:a 3 :b 10})
      (is (= [[1 3]] @notes)
          "the value recovered: its dirty? guard was cleared by flush! before
           the throw, so the next change re-marks + flushes it"))))

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

;; ---- direct source mutation: earlier dependent throw does NOT suppress -----
;; ---- the later sibling's derived work (rf2-2u4rw depth-zero entry) ---------

(deftest direct-source-earlier-throw-still-drains-later-sibling
  (testing "a DIRECT raw source reset (depth zero, bypassing replace-container!)
            with TWO derived dependents; the EARLIER (first-registered)
            dependent's recompute throws. The source's per-source fan-out
            coordinator (rf2-7ryt0) brackets the whole fan-out in ONE with-epoch:
            both dependents mark inside the epoch, the outermost close drains
            both flushes once, and the earliest failure surfaces via
            surface-escaped! AFTER the later sibling's derived work has run. Net:
            the later sibling's derived work runs, THEN the original first
            failure surfaces once, with identity preserved, and the scheduler
            recovers cleanly. (Before rf2-2u4rw the depth-zero drain re-raised
            inline and stranded the later sibling; #6210's deferral fixed the
            exactly-two case only — rf2-7ryt0 gives every arity a real terminal.)"
    (let [scheduler    (spine/make-scheduler)
          make-derived (spine/make-derived-value-fn "rf-b1-" scheduler)
          src          (atom 1)
          boom?        (atom false)
          later-ran    (atom 0)
          sentinel     (js/Error. "earlier boom")
          ;; EARLIER dependent — constructed first, so its watch fires first and
          ;; its flush drains first. Throws the sentinel on demand.
          earlier      (make-derived [src]
                         (fn [x] (when @boom? (throw sentinel)) x))
          ;; LATER dependent — the sibling a re-raising depth-zero drain would
          ;; have suppressed (its watch never firing because the atom loop
          ;; aborted). Counts its cascade-time flushes.
          later        (make-derived [src]
                         (fn [x] (when @boom? (swap! later-ran inc)) (* x 10)))
          later-notes  (atom [])]
      ;; Lazy baselines (the sub-cache reads on subscribe), before arming.
      (is (= 1 @earlier) "earlier baseline")
      (is (= 10 @later) "later baseline")
      (add-watch later :w (fn [_ _ prev nu] (swap! later-notes conj [prev nu])))
      (reset! boom? true)
      (let [caught (atom ::none)]
        (try (reset! src 2)
             (catch :default e (reset! caught e)))
        (is (identical? sentinel @caught)
            "the EARLIER dependent's failure surfaced to the caller with EXACT
             identity — deferred on :escaped, re-raised after the later sibling")
        (is (= 1 @later-ran)
            "the LATER sibling's flush STILL ran (its watch marked/enqueued) —
             a re-raising depth-zero drain would have suppressed it entirely")
        (is (= [[10 20]] @later-notes)
            "the later sibling notified its own watcher with the settled value
             BEFORE the earlier failure surfaced"))
      ;; Recovery: disarm and reset AGAIN — both dependents re-mark + flush,
      ;; proving neither the :escaped cell nor a dirty? guard was left stuck.
      (reset! boom? false)
      (reset! later-notes [])
      (reset! src 3)
      (is (= 3 @earlier) "earlier recovered — its dirty? guard was cleared")
      (is (= [[20 30]] @later-notes)
          "a later clean reset re-marks + flushes the later sibling — scheduler
           state (:escaped, queue, flushing?) fully recovered"))))

(deftest direct-source-both-dependents-throw-surfaces-earliest
  (testing "when BOTH direct dependents throw (earlier first), the EARLIEST
            escape is the one surfaced (presence capture across the single
            coordinator-bracketed drain) — the later dependent's own throw is
            dropped in favour of the first. (rf2-2u4rw + rf2-7ryt0)"
    (let [scheduler    (spine/make-scheduler)
          make-derived (spine/make-derived-value-fn "rf-b1b-" scheduler)
          src          (atom 1)
          boom?        (atom false)
          first-err    (js/Error. "first boom")
          earlier      (make-derived [src] (fn [x] (when @boom? (throw first-err)) x))
          later        (make-derived [src] (fn [x] (when @boom? (throw (js/Error. "second boom"))) x))]
      (is (= 1 @earlier) "earlier baseline")
      (is (= 1 @later) "later baseline")
      (reset! boom? true)
      (let [caught (atom ::none)]
        (try (reset! src 2)
             (catch :default e (reset! caught e)))
        (is (identical? first-err @caught)
            "the earliest (first-firing dependent's) failure surfaced by
             identity; the later dependent's throw was superseded")))))

;; ---- direct-source fan-out terminal for ARBITRARY arity (rf2-7ryt0) --------
;; #6210's "surface at the next sibling drain" deferral only terminated the
;; exactly-two earlier-throw case: a SOLE / LAST-firing throwing dependent has
;; NO later drain (the failure parked on :escaped until an unrelated mutation),
;; and 3+ dependents with an EARLY throw surfaced at the second dependent's
;; drain, aborting the atom notify loop and stranding the tail. The per-source
;; fan-out coordinator brackets the whole fan-out in ONE with-epoch, so every
;; arity attempts all owned dependents then surfaces the earliest at a real
;; terminal. These tests are RED against #6210 (sole/last park; early-of-3+
;; strands) and GREEN with the coordinator.

(deftest direct-source-sole-throwing-dependent-surfaces-at-terminal
  (testing "a DIRECT raw source reset whose SOLE derived dependent throws
            surfaces that failure SYNCHRONOUSLY at the fan-out's own terminal,
            exactly once, by identity — not parked on :escaped until an
            unrelated later drain. #6210 deferred a fresh escape to 'the next
            sibling drain', which never comes for a sole dependent, so reset!
            returned successfully and the failure was lost until an unrelated
            mutation. The per-source coordinator gives the bare reset a real
            with-epoch terminal. (rf2-7ryt0 — RED against #6210: parked.)"
    (let [scheduler    (spine/make-scheduler)
          make-derived (spine/make-derived-value-fn "rf-sole-" scheduler)
          src          (atom 1)
          boom?        (atom false)
          sentinel     (js/Error. "sole boom")
          d            (make-derived [src] (fn [x] (when @boom? (throw sentinel)) x))]
      (is (= 1 @d) "baseline deref")
      (reset! boom? true)
      (let [caught (atom ::none)]
        (try (reset! src 2)
             (catch :default e (reset! caught e)))
        (is (identical? sentinel @caught)
            "the sole dependent's failure surfaced synchronously at the reset
             terminal with exact identity — no later drain was needed"))
      ;; Recovery: a later clean reset flushes normally — no retained escape.
      (reset! boom? false)
      (let [notes (atom [])]
        (add-watch d :w (fn [_ _ prev nu] (swap! notes conj [prev nu])))
        (reset! src 3)
        (is (= [[1 3]] @notes)
            "a later clean reset re-marks + flushes (prev-state stayed 1 — the
             armed recompute threw before updating it); scheduler state clean")))))

(deftest direct-source-sole-dependent-falsey-throw-surfaces-by-presence
  (testing "sole-dependent surfacing preserves a FALSEY throw (`false`/`nil`,
            both legal CLJS throws) by PRESENCE — the caller observes the exact
            falsey value, never masked by a truthiness test (rf2-7ryt0 +
            rf2-2u4rw)."
    (doseq [thrown-val [false nil]]
      (let [scheduler    (spine/make-scheduler)
            make-derived (spine/make-derived-value-fn "rf-solef-" scheduler)
            src          (atom 1)
            boom?        (atom false)
            d            (make-derived [src] (fn [x] (when @boom? (throw thrown-val)) x))]
        (is (= 1 @d) "baseline")
        (reset! boom? true)
        (let [caught (atom ::none)
              threw? (atom false)]
          (try (reset! src 2)
               (catch :default e (reset! threw? true) (reset! caught e)))
          (is @threw?
              (str "reset! SURFACED the sole `" (pr-str thrown-val) "` throw"))
          (is (identical? thrown-val @caught)
              (str "the surfaced value IS the original `" (pr-str thrown-val)
                   "` throw — identity preserved by presence, not truthiness")))))))

(deftest direct-source-three-plus-dependents-attempt-all-then-surface-earliest
  (testing "with THREE dependents on one raw source, a throw at the FIRST,
            MIDDLE, or LAST position attempts EVERY dependent's flush exactly
            once, then surfaces the earliest failure by exact identity. #6210
            surfaced an early throw at the SECOND dependent's drain, aborting
            the atom notify loop and stranding the remaining dependents (RED for
            :first — d2 never ran; RED for :last — parked, never surfaced); the
            per-source coordinator brackets the whole fan-out in one with-epoch
            so all attempt, then the earliest surfaces. (rf2-7ryt0)"
    (doseq [throw-at [:first :middle :last]]
      (let [scheduler    (spine/make-scheduler)
            make-derived (spine/make-derived-value-fn "rf-3-" scheduler)
            src          (atom 1)
            boom?        (atom false)
            ran          (atom {:first 0 :middle 0 :last 0})
            errs         {:first (js/Error. "d-first")
                          :middle (js/Error. "d-middle")
                          :last (js/Error. "d-last")}
            mk           (fn [pos]
                           (make-derived [src]
                             (fn [x]
                               (swap! ran update pos inc)
                               (when (and @boom? (= throw-at pos))
                                 (throw (get errs pos)))
                               (* x 10))))
            ;; registration order == earliest order: first, middle, last.
            _d0 (mk :first)
            _d1 (mk :middle)
            _d2 (mk :last)]
        (is (= 10 @_d0)) (is (= 10 @_d1)) (is (= 10 @_d2))
        (reset! ran {:first 0 :middle 0 :last 0})
        (reset! boom? true)
        (let [caught (atom ::none)]
          (try (reset! src 2)
               (catch :default e (reset! caught e)))
          (is (identical? (get errs throw-at) @caught)
              (str "throw-at " throw-at ": the (earliest) failure surfaced by identity"))
          (is (= {:first 1 :middle 1 :last 1} @ran)
              (str "throw-at " throw-at
                   ": EVERY dependent's flush was attempted exactly once")))))))

(deftest direct-source-multiple-throwing-dependents-surface-earliest-of-three
  (testing "when MULTIPLE of three dependents throw (first + last), the EARLIEST
            (first-registered) failure is surfaced — presence capture across the
            single bracketed drain — and every dependent is still attempted once;
            the later thrower does not abort the fan-out. (rf2-7ryt0)"
    (let [scheduler    (spine/make-scheduler)
          make-derived (spine/make-derived-value-fn "rf-3m-" scheduler)
          src          (atom 1)
          boom?        (atom false)
          ran          (atom 0)
          e0           (js/Error. "first boom")
          e2           (js/Error. "third boom")
          _d0 (make-derived [src] (fn [x] (swap! ran inc) (when @boom? (throw e0)) x))
          _d1 (make-derived [src] (fn [x] (swap! ran inc) x))
          _d2 (make-derived [src] (fn [x] (swap! ran inc) (when @boom? (throw e2)) x))]
      (is (= 1 @_d0)) (is (= 1 @_d1)) (is (= 1 @_d2))
      (reset! ran 0)
      (reset! boom? true)
      (let [caught (atom ::none)]
        (try (reset! src 2)
             (catch :default e (reset! caught e)))
        (is (identical? e0 @caught)
            "the earliest of the two throwing dependents surfaced by identity")
        (is (= 3 @ran)
            "all three dependents attempted once — the later thrower did not
             abort the fan-out")))))

(deftest direct-source-failure-leaves-scheduler-state-clean
  (testing "after a direct-source fan-out failure surfaces, the scheduler's
            depth/flushing?/queue/queued cells are all clean, :escaped retains
            NO failure, and the next unrelated clean mutation flushes normally
            with no retained earlier failure. (rf2-7ryt0 acceptance — clean
            state after every path.)"
    (let [scheduler    (spine/make-scheduler)
          make-derived (spine/make-derived-value-fn "rf-clean-" scheduler)
          src          (atom 1)
          other        (atom 100)
          boom?        (atom false)
          _d      (make-derived [src] (fn [x] (when @boom? (throw (js/Error. "boom"))) x))
          d-other (make-derived [other] (fn [y] y))
          notes   (atom [])]
      (is (= 1 @_d)) (is (= 100 @d-other))
      (reset! boom? true)
      (try (reset! src 2) (catch :default _ nil))
      ;; Scheduler cells clean after the failed fan-out.
      (is (= 0 @(:depth scheduler)) "depth clean")
      (is (false? @(:flushing? scheduler)) "flushing? clean")
      ;; rf2-jr76s — the drain's scratch is a JS array + `js/Set` mutated in
      ;; place, not a volatile over persistent collections. The CONTRACT this
      ;; asserts is unchanged: both are empty after the failed fan-out, so no
      ;; thunk is stranded and no `queued` entry survives to block a re-mark.
      (is (zero? (alength (:queue scheduler))) "queue empty")
      (is (zero? (.-size (:queued scheduler))) "queued empty")
      (is (not (instance? js/Error @(:escaped scheduler)))
          ":escaped retains no failure")
      ;; A DIFFERENT source's clean mutation must NOT receive the retained failure.
      (add-watch d-other :w (fn [_ _ prev nu] (swap! notes conj [prev nu])))
      (reset! boom? false)
      (let [caught (atom ::none)]
        (try (reset! other 200)
             (catch :default e (reset! caught e)))
        (is (= ::none @caught)
            "the unrelated clean mutation surfaced NO retained earlier failure
             (proves :escaped was clean — else surface-escaped! would re-raise it)")
        (is (= [[100 200]] @notes)
            "the unrelated source flushed normally")))))

;; ---- with-epoch body/finally failure ordering (rf2-2u4rw epoch entry) ------

(deftest with-epoch-body-throw-wins-over-drain-throw
  (testing "a `with-epoch` body that QUEUES work and then throws E1, whose
            queued drain then throws E2, surfaces E1 (the earliest escape) to
            the caller — NOT E2. A bare try/finally would let JavaScript's
            finally-replaces-try semantics surface E2 and lose the primary E1.
            rf2-2u4rw seeds the body escape onto :escaped BEFORE draining so the
            drain's E2 cannot displace it; the drain still attempts the queued
            tail, and E1 surfaces once with exact identity, scheduler clean."
    (let [scheduler    (spine/make-scheduler)
          make-derived (spine/make-derived-value-fn "rf-b2-" scheduler)
          replace!     (spine/make-replace-container-fn scheduler)
          root         (spine/make-state-container {:a 1})
          boom?        (atom false)
          e1           (js/Error. "E1 body")
          ;; A derived whose flush recompute throws E2 during the drain (its
          ;; :a slice moves, so it recomputes and throws when armed).
          drain-thrower (make-derived [root]
                          (fn [db] (when @boom? (throw (js/Error. "E2 drain")))
                            (:a db)))]
      (is (= 1 @drain-thrower) "baseline deref")
      (reset! boom? true)
      (let [caught (atom ::none)]
        ;; Body: reset root (queues drain-thrower's flush at depth 1), THEN
        ;; throw E1. The outermost close drains (flush throws E2) and must
        ;; surface E1.
        (try (#'spine/with-epoch scheduler
               (fn []
                 (reset! root {:a 2})   ;; queues the flush (no drain yet)
                 (throw e1)))           ;; E1 escapes the body after queueing
             (catch :default e (reset! caught e)))
        (is (identical? e1 @caught)
            "E1 (the body's earliest escape) surfaced with exact identity — the
             drain's E2 did not displace it (rf2-2u4rw)"))
      ;; Scheduler recovered: a normal replace! after the failure flushes
      ;; cleanly (queue/flushing?/:escaped all clean).
      (reset! boom? false)
      (let [notes (atom [])]
        (add-watch drain-thrower :w (fn [_ _ _ nu] (swap! notes conj nu)))
        (replace! root {:a 5})
        (is (= [5] @notes)
            "a later replace! flushes normally — scheduler state fully restored
             after the body-throw + drain-throw cascade")
        (is (= 5 @drain-thrower) "the derived value settled to the new slice"))))

  (testing "the body-over-drain primary is preserved by PRESENCE: a body that
            throws a FALSEY value (`nil` / `false`, both legal CLJS throws)
            still wins over a truthy drain E2, with exact identity. (rf2-2u4rw)"
    (doseq [thrown-val [false nil]]
      (let [scheduler    (spine/make-scheduler)
            make-derived (spine/make-derived-value-fn "rf-b2f-" scheduler)
            root         (spine/make-state-container {:a 1})
            boom?        (atom false)
            _drain-thrower (make-derived [root]
                             (fn [db] (when @boom? (throw (js/Error. "E2 drain")))
                               (:a db)))]
        (is (= 1 @_drain-thrower) "baseline deref")
        (reset! boom? true)
        (let [caught (atom ::none)
              threw? (atom false)]
          (try (#'spine/with-epoch scheduler
                 (fn [] (reset! root {:a 2}) (throw thrown-val)))
               (catch :default e (reset! threw? true) (reset! caught e)))
          (is @threw?
              (str "with-epoch SURFACED the `" (pr-str thrown-val) "` body throw"))
          (is (identical? thrown-val @caught)
              (str "the surfaced value IS the original `" (pr-str thrown-val)
                   "` body throw — earliest primary preserved by presence, not "
                   "truthiness, over the truthy drain E2")))))))

;; ---- native derived fan-out: rf= movement law ----------------------------
;; (rf2-vxgfnd.203)

(deftest nan-to-nan-derived-does-not-fan-out-on-no-move
  (testing "a derived value that stays ##NaN across a source tick reports NO
            movement under the frozen rf= law and fans out ZERO direct
            callbacks. Raw `not=` treated `(not= ##NaN ##NaN)` as true and
            fired a false invalidation — disagreeing with the observation
            port and ViewCell layer, which are both rf=-gated. An ordinary
            value movement still notifies exactly once, so the gate is a
            movement test, not a blanket suppression. (rf2-vxgfnd.203)"
    (let [{:keys [make-derived replace! root]} (build-graph)
          nan-d     (make-derived [root] (fn [_db] js/NaN))
          ord-d     (make-derived [root] (fn [db] (:a db)))
          nan-notes (atom 0)
          ord-notes (atom 0)]
      ;; Lazy baselines (the sub-cache reads on subscribe): establish
      ;; prev-state = ##NaN / 1 before watching.
      (is (js/isNaN @nan-d) "NaN baseline establishes prev-state = ##NaN")
      (is (= 1 @ord-d) "ordinary baseline establishes prev-state = 1")
      (add-watch nan-d :w (fn [_ _ _ _] (swap! nan-notes inc)))
      (add-watch ord-d :w (fn [_ _ _ _] (swap! ord-notes inc)))
      ;; ONE write ticks the shared source: :a moves 1→2 (ordinary derived
      ;; MOVES) while the NaN derived recomputes ##NaN again (NO move).
      (replace! root {:a 2 :b 10})
      (is (= 0 @nan-notes)
          "##NaN→##NaN is stable under rf= — zero direct callbacks (raw not=
           would have fired one)")
      (is (= 1 @ord-notes)
          "an ordinary value movement produces exactly one direct callback"))))

;; ---- native derived fan-out: throwing-subscriber containment --------------
;; (rf2-vxgfnd.203)

(deftest throwing-first-subscriber-does-not-suppress-later-sibling
  (testing "with the throwing subscriber registered FIRST (drained first), a
            later sibling still receives the same movement. Bare `run!`
            aborted at the throw and skipped every later subscriber; the fan-
            out now attempts each independently, then re-raises the captured
            failure AFTER delivery — the drain surfaces it to the caller
            (rf2-qcmzc), so replace-container! throws the primary value while
            every sibling still fired. (rf2-vxgfnd.203 + rf2-qcmzc)"
    (let [{:keys [make-derived replace! root]} (build-graph)
          l1      (make-derived [root] (fn [db] (:a db)))
          a-fired (atom 0)
          b-fired (atom 0)]
      (is (= 1 @l1) "baseline deref establishes prev-state")
      ;; :a registered FIRST → iterates first → throws; :b registered second.
      (add-watch l1 :a (fn [_ _ _ _]
                         (swap! a-fired inc)
                         (throw (js/Error. "subscriber A boom"))))
      (add-watch l1 :b (fn [_ _ _ _] (swap! b-fired inc)))
      (let [caught (atom ::none)]
        (try (replace! root {:a 2 :b 10})
             (catch :default e (reset! caught e)))
        (is (= 1 @a-fired) "the throwing subscriber ran")
        (is (= 1 @b-fired)
            "the later sibling STILL fired despite A throwing (bare run! would
             have skipped it)")
        (is (and (instance? js/Error @caught)
                 (= "subscriber A boom" (.-message @caught)))
            "A's throw is SURFACED to the caller AFTER B was delivered
             (rf2-qcmzc) — not swallowed inside the fan-out")))))

(deftest throwing-subscriber-delivery-is-order-independent
  (testing "a throwing subscriber in the MIDDLE of the registration order does
            not prevent EITHER an earlier or a later sibling from receiving the
            movement — delivery depends on ownership + movement, not iteration
            order. Covers the reversed registration order relative to the
            throwing-first test (a sibling registered BEFORE the thrower).
            (rf2-vxgfnd.203)"
    (let [{:keys [make-derived replace! root]} (build-graph)
          l1    (make-derived [root] (fn [db] (:a db)))
          fired (atom #{})]
      (is (= 1 @l1) "baseline deref establishes prev-state")
      ;; Registration order: :b (before), :a (throws), :c (after).
      (add-watch l1 :b (fn [_ _ _ _] (swap! fired conj :b)))
      (add-watch l1 :a (fn [_ _ _ _]
                         (swap! fired conj :a)
                         (throw (js/Error. "middle subscriber boom"))))
      (add-watch l1 :c (fn [_ _ _ _] (swap! fired conj :c)))
      (let [caught (atom ::none)]
        (try (replace! root {:a 2 :b 10})
             (catch :default e (reset! caught e)))
        (is (= #{:a :b :c} @fired)
            "every subscriber fired — the earlier (:b), the thrower (:a), AND the
             later (:c) sibling a bare run! would have skipped")
        (is (and (instance? js/Error @caught)
                 (= "middle subscriber boom" (.-message @caught)))
            "the middle subscriber's throw is SURFACED to the caller after ALL
             siblings were delivered (rf2-qcmzc)")))))

(deftest falsey-subscriber-throws-are-surfaced-by-presence
  (testing "a subscriber that throws a FALSEY value (`false` / `nil` — both
            legal CLJS throws) is captured by PRESENCE, so a later sibling
            still fires AND the primary failure is SURFACED to the caller with
            its exact identity — never masked by a truthiness test. Against
            #5961 the falsey value was captured but the whole failure was then
            swallowed by the drain, so the caller saw nil either way; this pins
            that the caller observes the ORIGINAL falsey throw. (rf2-qcmzc)"
    (doseq [thrown-val [false nil]]
      (let [{:keys [make-derived replace! root]} (build-graph)
            l1      (make-derived [root] (fn [db] (:a db)))
            b-fired (atom 0)]
        (is (= 1 @l1) "baseline deref establishes prev-state")
        (add-watch l1 :a (fn [_ _ _ _] (throw thrown-val)))
        (add-watch l1 :b (fn [_ _ _ _] (swap! b-fired inc)))
        (let [caught (atom ::none)
              threw? (atom false)]
          (try (replace! root {:a 2 :b 10})
               (catch :default e (reset! threw? true) (reset! caught e)))
          (is @threw?
              (str "replace! SURFACED the `" (pr-str thrown-val) "` throw — "
                   "presence capture means a falsey primary is not lost"))
          (is (identical? thrown-val @caught)
              (str "the surfaced value IS the original `" (pr-str thrown-val)
                   "` throw — identity preserved by presence, not truthiness"))
          (is (= 1 @b-fired)
              (str "sibling still fired after the `" (pr-str thrown-val)
                   "` throw — captured by presence, delivered before surfacing")))))))

;; ---- native derived subscriber failures SURFACE after fan-out -------------
;; (rf2-qcmzc)
;;
;; #5961 contained a throwing derived subscriber (every sibling still
;; delivers) and re-raised the captured value AFTER delivery — but that
;; re-raise escaped `flush!` straight into `drain-scheduler!`'s per-thunk
;; `(catch :default _ nil)`, which DISCARDED it. So the primary failure
;; vanished silently: `replace-container!` returned normally and the
;; programmer error never surfaced. These tests observe the ORIGINAL thrown
;; value at the caller (identity preserved), which is exactly what the
;; swallowing #5961 path could not deliver — they are RED against #5961.

(deftest primary-subscriber-throw-surfaces-with-object-identity
  (testing "the FIRST subscriber's thrown value is SURFACED to the caller with
            EXACT object identity preserved, AFTER every later sibling has been
            delivered. #5961 delivered the siblings but the drain swallowed the
            primary, so the caller never observed the thrown object; rf2-qcmzc
            surfaces it through the drain's caller/error channel. (rf2-qcmzc)"
    (let [{:keys [make-derived replace! root]} (build-graph)
          l1       (make-derived [root] (fn [db] (:a db)))
          ;; A distinct object — assert IDENTITY, not just class/message, so
          ;; nothing along the fan-out→drain→caller path can substitute a
          ;; look-alike.
          sentinel (js/Error. "primary failure")
          b-fired  (atom 0)]
      (is (= 1 @l1) "baseline deref establishes prev-state")
      ;; :a registered FIRST → throws the sentinel; :b registered second.
      (add-watch l1 :a (fn [_ _ _ _] (throw sentinel)))
      (add-watch l1 :b (fn [_ _ _ _] (swap! b-fired inc)))
      (let [caught (atom ::none)]
        (try (replace! root {:a 2 :b 10})
             (catch :default e (reset! caught e)))
        (is (identical? sentinel @caught)
            "the EXACT thrown object surfaced to the caller — identity preserved
             through the fan-out capture AND the drain caller/error channel")
        (is (= 1 @b-fired)
            "the later sibling was delivered BEFORE the primary surfaced")))))

(deftest single-subscriber-throw-surfaces-on-fast-path
  (testing "the allocation-free single-subscriber fast path still SURFACES a
            throwing subscriber's failure with identity preserved. With exactly
            one watcher there is no sibling to protect, so the fan-out invokes
            it directly (no capture volatile) and its throw propagates through
            flush! into the drain, which surfaces it to the caller. Guards
            against the fast path silently dropping the sole subscriber's throw.
            (rf2-qcmzc common-path preservation)"
    (let [{:keys [make-derived replace! root]} (build-graph)
          l1       (make-derived [root] (fn [db] (:a db)))
          sentinel (js/Error. "solo boom")]
      (is (= 1 @l1) "baseline deref establishes prev-state")
      (add-watch l1 :only (fn [_ _ _ _] (throw sentinel)))
      (let [caught (atom ::none)]
        (try (replace! root {:a 2 :b 10})
             (catch :default e (reset! caught e)))
        (is (identical? sentinel @caught)
            "the sole subscriber's throw surfaced with identity preserved on the
             allocation-free fast path")))))

;; ---- watcher add/remove during fan-out lands on the NEXT wave --------------
;; (rf2-qcmzc — snapshot semantics)

(deftest watcher-add-remove-during-fan-out-snapshots-to-next-wave
  (testing "the fan-out iterates a SNAPSHOT of the watcher map (@watchers is an
            immutable map derefed once). A subscriber that add-watches / remove-
            watches DURING fan-out therefore lands on the NEXT movement, not the
            current one: a watcher REMOVED mid-fan-out still fires this wave (it
            is in the snapshot), and a watcher ADDED mid-fan-out does NOT fire
            this wave (it is not). On the following movement the roles flip.
            (rf2-qcmzc)"
    (let [{:keys [make-derived replace! root]} (build-graph)
          l1      (make-derived [root] (fn [db] (:a db)))
          a-count (atom 0)
          c-count (atom 0)
          d-count (atom 0)
          mutated (atom false)]
      (is (= 1 @l1) "baseline deref establishes prev-state")
      ;; Registration (== iteration) order: :a, :mutator, :c. The mutator, on
      ;; the FIRST wave only, adds :d and removes :c — mutating the watchers
      ;; atom mid-fan-out.
      (add-watch l1 :a (fn [_ _ _ _] (swap! a-count inc)))
      (add-watch l1 :mutator (fn [_ _ _ _]
                               (when-not @mutated
                                 (reset! mutated true)
                                 (add-watch l1 :d (fn [_ _ _ _] (swap! d-count inc)))
                                 (remove-watch l1 :c))))
      (add-watch l1 :c (fn [_ _ _ _] (swap! c-count inc)))
      ;; Wave 1 (1→2): snapshot = [:a :mutator :c]. :a fires; :mutator adds :d +
      ;; removes :c; :c STILL fires (in the snapshot); :d does NOT (not in it).
      (replace! root {:a 2 :b 10})
      (is (= 1 @a-count) "wave 1: :a fired")
      (is (= 1 @c-count)
          "wave 1: :c fired from the SNAPSHOT even though the mutator removed it
           mid-fan-out — the removal lands on the next wave")
      (is (= 0 @d-count)
          "wave 1: :d added mid-fan-out did NOT fire this wave — it lands on the
           next wave")
      ;; Wave 2 (2→3): watcher map is now {:a :mutator :d} (:c gone, :d present).
      (replace! root {:a 3 :b 10})
      (is (= 2 @a-count) "wave 2: :a fired again")
      (is (= 1 @c-count) "wave 2: :c did NOT fire — it was removed on wave 1")
      (is (= 1 @d-count)
          "wave 2: :d NOW fires — the mid-fan-out add took effect on this wave"))))

;; ---- re-entrant write from a subscriber during fan-out --------------------
;; (rf2-qcmzc — pins current behavior; full re-entrant-write ordering is a
;; later pass per the bead)

(deftest re-entrant-write-from-subscriber-coalesces-into-outer-drain
  (testing "a subscriber that performs a re-entrant replace! during fan-out has
            its write coalesced into the SAME outer drain: the nested epoch's
            drain is a no-op while the outer drain still holds `flushing?`, so
            the re-enqueued flush is picked up by the running outer loop. The
            derived value settles to the re-entrant value and the subscriber
            observes BOTH movements in order. Pins the current behavior — the
            bead scopes full queued / re-entrant-write ordering to a later pass.
            (rf2-qcmzc)"
    (let [{:keys [make-derived replace! root]} (build-graph)
          l1    (make-derived [root] (fn [db] (:a db)))
          seen  (atom [])
          done? (atom false)]
      (is (= 1 @l1) "baseline deref establishes prev-state")
      (add-watch l1 :w (fn [_ _ prev nu]
                         (swap! seen conj [prev nu])
                         (when-not @done?
                           (reset! done? true)
                           ;; Re-entrant write DURING fan-out (single subscriber
                           ;; → exercises the fast path). Guarded so it fires
                           ;; exactly once (an unconditional re-entrant write
                           ;; would spin forever).
                           (replace! root {:a 3 :b 10}))))
      (replace! root {:a 2 :b 10})
      (is (= [[1 2] [2 3]] @seen)
          "the subscriber saw the original 1→2 movement then the re-entrant 2→3
           movement, both drained within the one outer epoch")
      (is (= 3 @l1) "the derived value settled to the re-entrant value")
      ;; A subsequent ordinary write still flushes cleanly — the scheduler state
      ;; was fully restored after the re-entrant cascade (no stuck flushing?).
      (reset! seen [])
      (replace! root {:a 4 :b 10})
      (is (= [[3 4]] @seen)
          "a later write flushes normally — scheduler state restored after the
           re-entrant cascade"))))
