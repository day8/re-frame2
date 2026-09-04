(ns re-frame.story.play.presence-stale-settlement-cljs-test
  "rf2-6pfpt — STALE SETTLEMENT of a Promise-backed `[:flush-presence]`.

  rf2-iz0t8 (#6367) made the presence step AWAIT its host's thenable before
  recording, closing the 'reported `:pass` over a flush that failed' hazard.
  Awaiting introduces a gap the synchronous path never had: between the
  executor parking on the Promise and the Promise settling, ANOTHER agent can
  take over the run — a concurrent `run!` stamping a fresher `:run-token` on
  the `[frame-id play-key]` slot, or frame teardown removing the slot outright.
  Story already fences that gap for every ordinary step: `run-loop!` re-checks
  the slot's existence AND its token before the next step mutates anything.
  The settled-Promise callback did NOT — it called `record-result!` first and
  re-entered the loop (where the token check lives) afterwards, so the
  mutation happened on the far side of the fence.

  What that costs, concretely: run A parks on a presence Promise; run B takes
  the slot; A settles and appends ITS step-result into B's run-state, bumping
  B's cursor past a step B never ran and contaminating B's verdict. Under
  teardown, `record-step-result` applied to a nil state does not even throw on
  CLJS — `(inc nil)` is 1 — so it RESURRECTS a phantom run-state entry carrying
  no `:run-token`, which the loop's token guard (`(some? (:run-token state))`)
  then declines to abort on.

  THE SAME SHAPE IN THE INTERACTIVE STEPPER. `rf.story.play/step-once!` amends its
  recorded result when the presence Promise settles, guarded only by a BOUNDS
  check (`idx < (count results)`). A bounds check is a size test, not an
  identity test: it correctly declines when a reset has SHRUNK `:results` below
  `idx`, and then silently permits the clobber once a new cursor has grown back
  past `idx` — a stale amendment landing on a different session's step.

  Every test here is DETERMINISTIC. The host returns a hand-rolled thenable
  whose settlement this namespace triggers explicitly (`resolve!` / `reject!`
  run the stored callbacks synchronously), so 'A settles AFTER B took the slot'
  is placed exactly, not raced. A real `js/Promise` would settle on a microtask
  the test cannot interleave against — which is what made this defect class
  intermittent in the wild, and is precisely what a regression test must not
  reproduce.

  Pure `.cljs`: the `::pending-advance` branch is reader-gated to CLJS (the JVM
  presence verb is a synchronous no-op, so `advance!` never yields a
  `:pending`), and the `async` tests below require cljs.test MAP fixtures,
  which a `.cljc` may not use (`re-frame.story.meta-fixtures-test`)."
  (:require [cljs.test :refer [async deftest is testing use-fixtures]]
            [re-frame.story.play                    :as rf.story.play]
            [re-frame.story.play.presence           :as rf.story.play.presence]
            [re-frame.story.play.runner             :as rf.story.play.runner]
            [re-frame.story.play.runner-events      :as rf.story.play.runner-events]
            ;; The shared harness (fresh registrar + runtime + variant frame),
            ;; reused rather than re-built — one harness across the presence
            ;; rung's coverage.
            [re-frame.story.play.presence-cljs-test :as rf.story.play.presence-cljs-test]))

;; `clear-all-runs!` on top of the shared setup: `rf.story.play.presence-cljs-test/setup!` resets
;; `rf.story.play.runner-events/run-state` but not `runs-by-play`, and every assertion here reads the
;; slot through `current-state-for-play` (which derefs `runs-by-play`). A slot
;; surviving from a previous test would make a stale-settlement assertion read
;; a state this test never seeded.
(use-fixtures :each
  {:before (fn [] (rf.story.play.presence-cljs-test/setup!) (rf.story.play.runner-events/clear-all-runs!))
   :after  (fn [] (rf.story.play.presence-cljs-test/teardown!) (rf.story.play.runner-events/clear-all-runs!))})

;; The private run-loop seam, reached via var-quote — the established
;; Story-test seam (`runner-events-cljs-test` drives the abort branches the
;; same way).
(def ^:private run-loop!  @#'rf.story.play.runner-events/run-loop!)
(def ^:private set-state! @#'rf.story.play.runner-events/set-state!)

;; ---------------------------------------------------------------------------
;; A deferred whose settlement the TEST places
;; ---------------------------------------------------------------------------

(defn- deferred
  "A thenable that records its callbacks and settles only when this test says
  so. `presence/thenable` duck-types on `.then` being a fn, so a hand-rolled
  object is observed exactly as a `js/Promise` is — without a microtask the
  test cannot schedule against."
  []
  (let [cbs (atom [])]
    {:thenable (js-obj "then" (fn [on-ok on-err]
                                (swap! cbs conj [on-ok on-err])
                                nil))
     :armed?   (fn [] (boolean (seq @cbs)))
     :resolve! (fn [v] (doseq [[ok _] @cbs] (ok v)) nil)
     :reject!  (fn [e] (doseq [[_ err] @cbs] (err e)) nil)}))

(defn- install-deferred-host!
  "Install a presence host whose advance parks on `d`. `calls` records each
  advance's ms so a test can prove the verb was REACHED — a fence that worked
  by never running the host would pass every staleness assertion here."
  [d calls]
  (rf.story.play.presence/install-presence-flush!
    (fn [ms] (swap! calls conj ms) (:thenable d))))

(defn- seed-run!
  "Stamp a fresh run carrying `token` onto the `[presence-frame nil]` slot.
  Returns the seeded state."
  [token script]
  (let [started (-> (rf.story.play.runner/start
                      (rf.story.play.runner/initial-state {:name nil :script script}) 0)
                    (assoc :run-token token))]
    (set-state! rf.story.play.presence-cljs-test/presence-frame nil started)
    started))

(defn- start-run!
  "Seed a run carrying `token` and drive `run-loop!` for it. With a
  `[:flush-presence]` leading step against a deferred host the loop PARKS: it
  returns having recorded nothing, its continuation held by the thenable."
  ([token script] (start-run! token script nil))
  ([token script done-cb]
   (seed-run! token script)
   (run-loop! rf.story.play.presence-cljs-test/presence-frame nil token done-cb)))

(defn- slot []
  (rf.story.play.runner-events/current-state-for-play rf.story.play.presence-cljs-test/presence-frame nil))

(def ^:private presence-script
  [[:flush-presence] [:dispatch [:presence/tick]]])

;; ===========================================================================
;; The auto-run loop: the run-token fence
;; ===========================================================================

(deftest a-parked-presence-run-has-recorded-nothing-yet
  (testing "the premise every test below rests on — with the host's thenable
            unsettled the loop has PARKED: the verb was reached, and the run
            state carries no result for the step whose outcome is still unknown"
    (let [d     (deferred)
          calls (atom [])]
      (install-deferred-host! d calls)
      (start-run! "tok-A" presence-script)
      (is (= [nil] @calls) "the presence verb was REACHED (bare arity → nil ms)")
      (is (true? ((:armed? d))) "and the run parked on its thenable")
      (is (= 0 (:step-idx (slot))) "no cursor movement while pending")
      (is (= 0 (count (:results (slot)))) "and nothing recorded"))))

(deftest stale-settlement-does-not-mutate-the-replacement-run
  (testing "THE BUG (rf2-6pfpt): run A parks on a presence Promise, a
            concurrent run B takes over the `[frame play-key]` slot, and A
            THEN settles. A's result must not land in B's run-state — it would
            advance B's cursor past a step B never ran and contaminate B's
            verdict. `record-result!` used to run BEFORE the token check the
            loop performs on re-entry"
    (let [d     (deferred)
          calls (atom [])]
      (install-deferred-host! d calls)
      (start-run! "tok-A" presence-script)
      ;; A newer `run!` replaces the slot — exactly what `run!` does when the
      ;; selection-watcher fires while an orchestrated run is mid-script.
      (seed-run! "tok-B" [[:dispatch [:presence/tick]]])
      (is (= 0 (:step-idx (slot))) "precondition: B is at its first step")
      ;; A settles LATE, on the far side of the takeover.
      ((:resolve! d) :ok)
      (let [s (slot)]
        (is (= "tok-B" (:run-token s))
            "B still OWNS the slot — the stale settlement did not re-stamp it")
        (is (= 0 (:step-idx s))
            "rf2-6pfpt — A's settled result did NOT advance B's cursor")
        (is (= 0 (count (:results s)))
            "and was NOT appended to B's results")
        (is (= :running (:status s))
            "B is still running — no terminal transition leaked in")))))

(deftest teardown-while-pending-neither-throws-nor-resurrects-state
  (testing "rf2-6pfpt — the frame is torn down (`clear-state!`, the
            `:drop-run-state` teardown hook) while the presence Promise is in
            flight. Settling must not throw, and must not RESURRECT the slot:
            `record-step-result` over a nil state does not throw on CLJS
            (`(inc nil)` is 1), it fabricates a state map carrying no
            `:run-token` — which the loop's token guard then declines to abort
            on, because it only fires when a token is PRESENT"
    (let [d     (deferred)
          calls (atom [])]
      (install-deferred-host! d calls)
      (start-run! "tok-A" presence-script)
      (rf.story.play.runner-events/clear-state! rf.story.play.presence-cljs-test/presence-frame)
      (is (nil? (slot)) "precondition: teardown removed the slot")
      (is (nil? ((:resolve! d) :ok))
          "settling after teardown completes without throwing")
      (is (nil? (slot))
          "and left NO phantom entry in runs-by-play")
      (is (nil? (get @rf.story.play.runner-events/run-state rf.story.play.presence-cljs-test/presence-frame))
          "nor in run-state — update-state! writes BOTH atoms, so both are
           checked"))))

(deftest an-owning-run-still-records-its-resolved-result
  (testing "POSITIVE CONTROL. A fence that simply stopped recording settled
            presence results would pass every staleness test above while
            breaking every real presence run — and would silently reinstate the
            rf2-iz0t8 hazard it was built on top of. The run that still OWNS
            the slot records exactly as before"
    (let [d     (deferred)
          calls (atom [])]
      (install-deferred-host! d calls)
      (start-run! "tok-A" presence-script)
      ((:resolve! d) :ok)
      (let [s (slot)]
        (is (= 1 (:step-idx s)) "the owning run's cursor advanced")
        (is (= 1 (count (:results s))) "and its settled result was recorded")
        (is (nil? (:exception (first (:results s))))
            "a resolved advance is a clean step")))))

(deftest an-owning-run-still-records-its-rejected-result
  (testing "POSITIVE CONTROL, the other outcome. A rejection is the whole
            reason the await exists (rf2-iz0t8) — it must still reach the run
            state as the ordinary step-exception, unchanged by the fence"
    (let [d     (deferred)
          calls (atom [])]
      (install-deferred-host! d calls)
      (start-run! "tok-A" presence-script)
      ((:reject! d) (ex-info "presence flush failed" {}))
      (let [s (slot)
            r (first (:results s))]
        (is (= 1 (:step-idx s)))
        (is (true? (:exception r))
            "the rejection lands in the EXISTING step-exception vocabulary")
        (is (false? (:passed? r)))
        (is (nil? (:cannot-run? r))
            "a rejected flush is a failure, not a refusal — the host WAS
             reached")))))

(deftest the-run-token-fence-does-not-latch
  (testing "THE SEQUENCE, not just the cases. A state-carrying fence can be
            correct on every single transition and still be broken as a
            machine: it may latch shut after its first refusal, or latch open
            after its first admission. Four transitions in one process, each
            asserted: ADMIT a settlement, REFUSE a stale one, ADMIT again
            (proving the refusal did not latch shut), REFUSE again (proving
            the admission did not latch open)"
    (let [calls (atom [])]
      ;; 1 — CLEAN: the owning run settles and is admitted.
      (let [d1 (deferred)]
        (install-deferred-host! d1 calls)
        (start-run! "tok-1" presence-script)
        ((:resolve! d1) :ok)
        (is (= 1 (:step-idx (slot))) "1/4 ADMITTED — the owner recorded"))
      ;; 2 — VIOLATE: a stale run settles after a takeover; refused.
      (let [d2 (deferred)]
        (install-deferred-host! d2 calls)
        (start-run! "tok-2" presence-script)
        (seed-run! "tok-2-usurper" presence-script)
        ((:resolve! d2) :ok)
        (is (= 0 (:step-idx (slot))) "2/4 REFUSED — the usurper is untouched"))
      ;; 3 — RECOVER: a fresh owning run settles; admitted again. If the fence
      ;; latched shut on the refusal above, this is where it shows.
      (let [d3 (deferred)]
        (install-deferred-host! d3 calls)
        (start-run! "tok-3" presence-script)
        ((:resolve! d3) :ok)
        (is (= 1 (:step-idx (slot)))
            "3/4 ADMITTED AGAIN — the refusal did not latch the fence shut"))
      ;; 4 — VIOLATE AGAIN: the tooth that the `ai/` ratchet was missing. A
      ;; fence that accepted a second violation after a recovery would have
      ;; passed all three transitions above.
      (let [d4 (deferred)]
        (install-deferred-host! d4 calls)
        (start-run! "tok-4" presence-script)
        (seed-run! "tok-4-usurper" presence-script)
        ((:resolve! d4) :ok)
        (is (= 0 (:step-idx (slot)))
            "4/4 REFUSED AGAIN — the admission did not latch the fence open"))
      (is (= 4 (count @calls))
          "all four runs really reached the presence verb"))))

(deftest a-stale-run-settles-its-own-continuation
  (testing "rf2-6pfpt — refusing the MUTATION must not strand the CONTINUATION.
            The stale run still owes its own `done-cb` (the play-promise, and
            the outer `run-variant` promise chained off it, resolve through it
            and carry no timeout). The fence declines to record and re-enters
            the loop, which reaches the SAME `settle-abort!` every other stale
            exit path uses — one abort decision, not a second copy"
    (async done
      (let [d     (deferred)
            calls (atom [])]
        (install-deferred-host! d calls)
        (start-run! "tok-A" presence-script
                    (fn [final]
                      (is (= "tok-B" (:run-token final))
                          "the stale run settled with the LAST-KNOWN slot state,
                           without mutating it")
                      (is (= 0 (:step-idx (slot)))
                          "and B's cursor is still untouched at settle time")
                      (done)))
        (seed-run! "tok-B" presence-script)
        ((:resolve! d) :ok)))))

(deftest a-torn-down-run-settles-its-own-continuation
  (testing "rf2-6pfpt — the same obligation under TEARDOWN. The slot is gone,
            so there is nothing to record and nothing to transition; the
            continuation is still owed and settles with the last-known (nil)
            state rather than hanging forever"
    (async done
      (let [d     (deferred)
            calls (atom [])]
        (install-deferred-host! d calls)
        (start-run! "tok-A" presence-script
                    (fn [final]
                      (is (nil? final)
                          "settled with nil — the slot was torn down, and the
                           continuation only chains the next play")
                      (done)))
        (rf.story.play.runner-events/clear-state! rf.story.play.presence-cljs-test/presence-frame)
        ((:resolve! d) :ok)))))

;; ===========================================================================
;; The interactive stepper: the same shape, fenced by RECORD IDENTITY
;; ===========================================================================
;;
;; The stepper has no run token to carry — its session identity is not minted
;; at one entry point but mutated at five (`begin-stepper!`,
;; `stepper-step-back!`, `stepper-rewind!`, `end-stepper!`,
;; `clear-all-play-state!`). A generation counter would have to be bumped at
;; each, and a bump at `stepper-step-back!` would ALSO invalidate a still-valid
;; amendment for an EARLIER index that step-back never touched.
;;
;; The claim a settling step actually needs is narrower than a session: 'amend
;; the record I MYSELF recorded, not whatever now occupies my index'. That is
;; the provisional record's own object identity — captured with no extra state,
;; and automatically correct at every cursor mutation, including ones not yet
;; written.

(defn- seed-stepper!
  "Seed the stepper cursor directly, as `presence-real-clock-cljs-test` does:
  `begin-stepper!` resolves its script off a REGISTERED variant, and
  registering one would add a fixture without adding coverage. `stepper-state`
  is the documented substrate surface and `step-once!` is driven exactly as the
  UI widget drives it."
  [steps]
  (swap! rf.story.play/stepper-state assoc rf.story.play.presence-cljs-test/presence-frame
         {:remaining steps :ran [] :results []})
  nil)

(defn- stepper-results []
  (:results (get @rf.story.play/stepper-state rf.story.play.presence-cljs-test/presence-frame)))

(deftest stale-stepper-settlement-does-not-clobber-a-new-session
  (testing "THE BUG in the stepper (rf2-6pfpt): a presence step parks, the
            session is REWOUND, and the new cursor runs a different step into
            the same index. The bounds guard (`idx < (count results)`) is a
            SIZE test — it declines while the rewound `:results` is short, then
            permits the clobber the moment the new cursor has grown back past
            `idx`, overwriting a step the stale settlement never ran"
    (let [stale (deferred)
          live  (deferred)
          calls (atom [])]
      (install-deferred-host! stale calls)
      (seed-stepper! [[:flush-presence 100] [:dispatch [:presence/tick]]])
      (rf.story.play/step-once! rf.story.play.presence-cljs-test/presence-frame)
      (is (true? ((:armed? stale))) "the stepper parked on the thenable")
      ;; A real reset/new-cursor path — the UI's rewind button.
      (rf.story.play/stepper-rewind! rf.story.play.presence-cljs-test/presence-frame)
      (is (= 0 (count (stepper-results))) "the rewind emptied :results")
      ;; The new session parks on a DIFFERENT deferred, so settling the stale
      ;; one settles ONLY the abandoned session — otherwise one `resolve!`
      ;; would fire both callbacks and the assertion could not tell a refused
      ;; stale amendment from an admitted live one.
      (install-deferred-host! live calls)
      (rf.story.play/step-once! rf.story.play.presence-cljs-test/presence-frame)
      (let [fresh (first (stepper-results))]
        (is (= 1 (count (stepper-results))) "index 0 belongs to the new session")
        (is (= :flush-presence (:type fresh))
            "precondition: the rewound session re-runs the same first step,
             so the stale amendment's index is occupied again")
        ;; The ABANDONED session's Promise settles.
        ((:resolve! stale) :ok)
        (is (identical? fresh (first (stepper-results)))
            "rf2-6pfpt — the stale settlement did NOT overwrite the new
             session's record at that index")
        ;; ... and the LIVE session's own settlement is still admitted, so the
        ;; refusal above is a fence, not a blanket refusal to amend.
        ((:reject! live) (ex-info "presence flush failed" {}))
        (is (true? (:exception (first (stepper-results))))
            "the OWNING session's amendment landed")))))

(deftest an-owning-stepper-step-still-receives-its-settled-result
  (testing "POSITIVE CONTROL. The whole point of the stepper's settle callback
            (rf2-iz0t8) is that the debugger shows the SAME verdict the auto-run
            loop records. The fence must not cost that: an untouched session's
            record is still amended in place when its Promise rejects"
    (let [d     (deferred)
          calls (atom [])]
      (install-deferred-host! d calls)
      (seed-stepper! [[:flush-presence 100]])
      (rf.story.play/step-once! rf.story.play.presence-cljs-test/presence-frame)
      (is (nil? (:exception (first (stepper-results))))
          "provisional: the parked step reads clean before settlement")
      ((:reject! d) (ex-info "presence flush failed" {}))
      (let [r (first (stepper-results))]
        (is (true? (:exception r))
            "the settled failure replaced the provisional record — the stepper
             agrees with the auto-run path")
        (is (false? (:passed? r)))))))

(deftest a-step-back-does-not-invalidate-an-earlier-pending-amendment
  (testing "WHY IDENTITY, NOT A GENERATION. `stepper-step-back!` pops only the
            LAST step; a pending amendment for an EARLIER index is still owed
            and still correct. A session-level generation bumped on step-back
            would refuse it — silently reinstating the rf2-iz0t8 'clean flush
            over a failed one' hazard. The record-identity fence admits it,
            because the object at that index is still the one it recorded"
    (let [d     (deferred)
          calls (atom [])]
      (install-deferred-host! d calls)
      (seed-stepper! [[:flush-presence 100] [:dispatch [:presence/tick]]])
      (rf.story.play/step-once! rf.story.play.presence-cljs-test/presence-frame)          ; idx 0 — parks
      (rf.story.play/step-once! rf.story.play.presence-cljs-test/presence-frame)          ; idx 1 — synchronous
      (is (= 2 (count (stepper-results))))
      (rf.story.play/stepper-step-back! rf.story.play.presence-cljs-test/presence-frame)  ; pops idx 1 only
      (is (= 1 (count (stepper-results))) "idx 1 was dropped; idx 0 survives")
      (rf.story.play/step-once! rf.story.play.presence-cljs-test/presence-frame)          ; re-runs into idx 1
      (is (= 2 (count (stepper-results))))
      ((:reject! d) (ex-info "presence flush failed" {}))
      (is (true? (:exception (first (stepper-results))))
          "idx 0's amendment was ADMITTED — step-back never touched it"))))

(deftest the-stepper-fence-does-not-latch
  (testing "THE SEQUENCE for the stepper fence: ADMIT, REFUSE, ADMIT again,
            REFUSE again — the same four transitions the run-token fence is
            held to above, for the same reason"
    (let [calls (atom [])]
      ;; 1 — ADMIT.
      (let [d1 (deferred)]
        (install-deferred-host! d1 calls)
        (seed-stepper! [[:flush-presence 100]])
        (rf.story.play/step-once! rf.story.play.presence-cljs-test/presence-frame)
        ((:reject! d1) (ex-info "boom" {}))
        (is (true? (:exception (first (stepper-results))))
            "1/4 ADMITTED — the owning step was amended"))
      ;; 2 — REFUSE across a rewind. The post-rewind step parks on its OWN
      ;; deferred (see `stale-stepper-settlement-does-not-clobber-a-new-session`
      ;; for why the two sessions must not share one).
      (let [d2 (deferred)
            l2 (deferred)]
        (install-deferred-host! d2 calls)
        (seed-stepper! [[:flush-presence 100]])
        (rf.story.play/step-once! rf.story.play.presence-cljs-test/presence-frame)
        (rf.story.play/stepper-rewind! rf.story.play.presence-cljs-test/presence-frame)
        (install-deferred-host! l2 calls)
        (rf.story.play/step-once! rf.story.play.presence-cljs-test/presence-frame)
        (let [fresh (first (stepper-results))]
          ((:reject! d2) (ex-info "boom" {}))
          (is (identical? fresh (first (stepper-results)))
              "2/4 REFUSED — the new session's record stands")))
      ;; 3 — ADMIT again: the refusal must not have latched the fence shut.
      (let [d3 (deferred)]
        (install-deferred-host! d3 calls)
        (seed-stepper! [[:flush-presence 100]])
        (rf.story.play/step-once! rf.story.play.presence-cljs-test/presence-frame)
        ((:reject! d3) (ex-info "boom" {}))
        (is (true? (:exception (first (stepper-results))))
            "3/4 ADMITTED AGAIN — the fence did not latch shut"))
      ;; 4 — REFUSE again: the admission must not have latched it open.
      (let [d4 (deferred)
            l4 (deferred)]
        (install-deferred-host! d4 calls)
        (seed-stepper! [[:flush-presence 100]])
        (rf.story.play/step-once! rf.story.play.presence-cljs-test/presence-frame)
        (rf.story.play/stepper-rewind! rf.story.play.presence-cljs-test/presence-frame)
        (install-deferred-host! l4 calls)
        (rf.story.play/step-once! rf.story.play.presence-cljs-test/presence-frame)
        (let [fresh (first (stepper-results))]
          ((:reject! d4) (ex-info "boom" {}))
          (is (identical? fresh (first (stepper-results)))
              "4/4 REFUSED AGAIN — the fence did not latch open")))
      (is (= 6 (count @calls))
          "every stepped presence step really reached the verb (four sessions,
           two of them stepped twice across a rewind)")
      ;; `stepper-state` is process-global — leave no cursor behind.
      (swap! rf.story.play/stepper-state dissoc rf.story.play.presence-cljs-test/presence-frame))))
