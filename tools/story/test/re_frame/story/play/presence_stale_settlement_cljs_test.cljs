(ns re-frame.story.play.presence-stale-settlement-cljs-test
  "STALE SETTLEMENT of a Promise-backed `[:flush-presence]`.

  The presence step AWAITS its host's thenable before recording, so a run
  never reports `:pass` over a flush that failed. Between parking and
  settling another agent can take over the run (a concurrent `run!`
  stamping a fresher `:run-token`) or tear the frame down, so the settle
  callback checks the live slot before it records: otherwise run A's result
  would land in run B's state, and under teardown `record-step-result` over
  nil (`(inc nil)` is 1 on CLJS) would resurrect a token-less phantom slot.
  The interactive stepper amends its recorded result the same way, fenced on
  the recorded object's identity rather than a bounds check, which a reset
  cursor that has grown back past `idx` would pass.

  The host returns a hand-rolled thenable whose settlement each test
  triggers explicitly, so 'A settles AFTER B took the slot' is placed, not
  raced. Pure `.cljs`: the `::pending-advance` branch is CLJS-only, and the
  `async` tests need map fixtures, which a `.cljc` may not use."
  (:require [cljs.test :refer [async deftest is use-fixtures]]
            [re-frame.story.play                    :as rf.story.play]
            [re-frame.story.play.presence           :as rf.story.play.presence]
            [re-frame.story.play.runner             :as rf.story.play.runner]
            [re-frame.story.play.runner-events      :as rf.story.play.runner-events]
            [re-frame.story.play.presence-cljs-test :as rf.story.play.presence-cljs-test]))

;; The shared presence harness, plus `clear-all-runs!`: setup! resets
;; run-state but not runs-by-play, which every read here goes through.
(use-fixtures :each
  {:before (fn [] (rf.story.play.presence-cljs-test/setup!) (rf.story.play.runner-events/clear-all-runs!))
   :after  (fn []
             (rf.story.play.presence-cljs-test/teardown!)
             (rf.story.play.runner-events/clear-all-runs!)
             (swap! rf.story.play/stepper-state dissoc rf.story.play.presence-cljs-test/presence-frame))})

;; The private run-loop seam, reached via var-quote.
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
     :resolve! (fn [v] (doseq [[ok _] @cbs] (ok v)) nil)
     :reject!  (fn [e] (doseq [[_ err] @cbs] (err e)) nil)}))

(defn- install-deferred-host!
  "Install a presence host whose advance parks on `d`."
  [d]
  (rf.story.play.presence/install-presence-flush! (fn [_ms] (:thenable d))))

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

(deftest stale-settlement-does-not-mutate-the-replacement-run
  ;; run A parks, run B takes the slot, A settles late: B's token, cursor,
  ;; results and status are untouched
  (let [d (deferred)]
    (install-deferred-host! d)
    (start-run! "tok-A" presence-script)
    (seed-run! "tok-B" [[:dispatch [:presence/tick]]])
    ((:resolve! d) :ok)
    (is (= ["tok-B" 0 0 :running]
           ((juxt :run-token :step-idx (comp count :results) :status) (slot))))))

(deftest teardown-while-pending-neither-throws-nor-resurrects-state
  ;; update-state! writes both runs-by-play and run-state, so both are read
  (let [d (deferred)]
    (install-deferred-host! d)
    (start-run! "tok-A" presence-script)
    (rf.story.play.runner-events/clear-state! rf.story.play.presence-cljs-test/presence-frame)
    (is (nil? ((:resolve! d) :ok)) "settling after teardown does not throw")
    (is (= [nil nil]
           [(slot) (get @rf.story.play.runner-events/run-state rf.story.play.presence-cljs-test/presence-frame)]))))

(deftest an-owning-run-still-records-its-resolved-result
  ;; POSITIVE CONTROL: a fence that stopped recording would pass every
  ;; staleness test while breaking every real presence run. The cursor stops
  ;; at 1, so the loop parked on the thenable rather than running on.
  (let [d (deferred)]
    (install-deferred-host! d)
    (start-run! "tok-A" presence-script)
    ((:resolve! d) :ok)
    (let [s (slot)]
      (is (= [1 1 nil] [(:step-idx s) (count (:results s)) (:exception (first (:results s)))])))))

(deftest an-owning-run-still-records-its-rejected-result
  ;; a rejection is the ordinary step-exception: a failure, not a refusal,
  ;; since the host WAS reached
  (let [d (deferred)]
    (install-deferred-host! d)
    (start-run! "tok-A" presence-script)
    ((:reject! d) (ex-info "presence flush failed" {}))
    (let [s (slot)]
      (is (= [1 true false nil]
             (into [(:step-idx s)] ((juxt :exception :passed? :cannot-run?) (first (:results s)))))))))

(deftest a-stale-run-settles-its-own-continuation
  ;; Refusing the mutation must not strand the stale run's done-cb (the
  ;; play-promise chains off it with no timeout); it settles through the same
  ;; settle-abort! as every other stale exit.
  (async done
    (let [d (deferred)]
      (install-deferred-host! d)
      (start-run! "tok-A" presence-script
                  (fn [final]
                    (is (= "tok-B" (:run-token final)) "settled with the last-known slot state")
                    (is (= 0 (:step-idx (slot))) "B's cursor untouched at settle time")
                    (done)))
      (seed-run! "tok-B" presence-script)
      ((:resolve! d) :ok))))

(deftest a-torn-down-run-settles-its-own-continuation
  (async done
    (let [d (deferred)]
      (install-deferred-host! d)
      (start-run! "tok-A" presence-script
                  (fn [final]
                    (is (nil? final) "settled with the last-known (nil) state")
                    (done)))
      (rf.story.play.runner-events/clear-state! rf.story.play.presence-cljs-test/presence-frame)
      ((:resolve! d) :ok))))

;; ===========================================================================
;; The interactive stepper: the same shape, fenced by RECORD IDENTITY
;; ===========================================================================
;;
;; The stepper's session is mutated at five entry points, so a generation
;; counter would need a bump at each, and a bump at `stepper-step-back!` would
;; also refuse a still-valid amendment for an earlier index. A settling step
;; only needs 'amend the record I recorded', which its object identity says.

(defn- seed-stepper!
  "Seed the stepper cursor directly: `begin-stepper!` resolves its script
  off a REGISTERED variant, and registering one would add a fixture without
  adding coverage. `step-once!` is driven exactly as the UI widget drives it."
  [steps]
  (swap! rf.story.play/stepper-state assoc rf.story.play.presence-cljs-test/presence-frame
         {:remaining steps :ran [] :results []})
  nil)

(defn- stepper-results []
  (:results (get @rf.story.play/stepper-state rf.story.play.presence-cljs-test/presence-frame)))

(deftest stale-stepper-settlement-does-not-clobber-a-new-session
  ;; A step parks, the session is rewound, and the new cursor re-runs the step
  ;; into the same index on its own deferred, so one settlement cannot fire
  ;; both sessions' callbacks.
  (let [stale (deferred)
        live  (deferred)]
    (install-deferred-host! stale)
    (seed-stepper! [[:flush-presence 100] [:dispatch [:presence/tick]]])
    (rf.story.play/step-once! rf.story.play.presence-cljs-test/presence-frame)
    (rf.story.play/stepper-rewind! rf.story.play.presence-cljs-test/presence-frame)
    (install-deferred-host! live)
    (rf.story.play/step-once! rf.story.play.presence-cljs-test/presence-frame)
    (let [fresh (first (stepper-results))]
      ((:resolve! stale) :ok)
      (is (identical? fresh (first (stepper-results)))
          "the stale settlement did not overwrite the new session's record")
      ((:reject! live) (ex-info "presence flush failed" {}))
      (is (true? (:exception (first (stepper-results))))
          "the owning session's amendment landed"))))

(deftest an-owning-stepper-step-still-receives-its-settled-result
  ;; the debugger shows the same verdict the auto-run loop records
  (let [d (deferred)]
    (install-deferred-host! d)
    (seed-stepper! [[:flush-presence 100]])
    (rf.story.play/step-once! rf.story.play.presence-cljs-test/presence-frame)
    ((:reject! d) (ex-info "presence flush failed" {}))
    (is (= [true false] ((juxt :exception :passed?) (first (stepper-results)))))))

(deftest a-step-back-does-not-invalidate-an-earlier-pending-amendment
  ;; step-back pops only the LAST step, so idx 0's amendment is still owed
  (let [d (deferred)]
    (install-deferred-host! d)
    (seed-stepper! [[:flush-presence 100] [:dispatch [:presence/tick]]])
    (rf.story.play/step-once! rf.story.play.presence-cljs-test/presence-frame)          ; idx 0 — parks
    (rf.story.play/step-once! rf.story.play.presence-cljs-test/presence-frame)          ; idx 1 — synchronous
    (rf.story.play/stepper-step-back! rf.story.play.presence-cljs-test/presence-frame)  ; pops idx 1 only
    (is (= 1 (count (stepper-results))) "idx 0 survives the step-back")
    (rf.story.play/step-once! rf.story.play.presence-cljs-test/presence-frame)          ; re-runs into idx 1
    ((:reject! d) (ex-info "presence flush failed" {}))
    (is (true? (:exception (first (stepper-results))))
        "idx 0's amendment was admitted")))
