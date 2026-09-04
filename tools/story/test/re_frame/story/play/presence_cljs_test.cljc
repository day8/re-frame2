(ns re-frame.story.play.presence-cljs-test
  "S4-H (rf2-qwzmt) — Story's presence rung: the `[:flush-presence]` script
  step consumes the framework's own presence clock so a
  presence-bearing variant settles DETERMINISTICALLY during playback.

  The problem the rung solves: a variant whose view renders a
  `(v/presence {:timeout-ms n} …)` boundary RETAINS a removed keyed child
  in `:unmounting` until the timeout fires. That retention is a CLOCK, not a
  queue, so no rung of the `settled-boundary` ladder settles it — playback
  races the timeout, and the only wall-clock answer (`[:wait ms]`) is the
  determinism opt-out the gate refuses.

  Two layers:

  - PURE grammar (both hosts) — `[:flush-presence]` / `[:flush-presence ms]`
    are known steps with two arities, require NO capability token, do not
    lift `:required-runner` to `:dom`, and are NOT wall-clock steps (the
    determinism gate accepts a script carrying them).
  - The SEAM against a stub presence host (both hosts) — the step routes the
    ms through `re-frame.story.play.presence/advance!`; a script WITHOUT the
    step leaves the retained exit pending and its assertion FAILS, the same
    script WITH it passes. This is the red-before/green-after, proven
    host-agnostically through the real `run!` playback loop.

  THERE IS NO THIRD LAYER, and its absence is deliberate (rf2-5gka). Story
  used to ship an optional Freehand bridge plus two suites driving the real
  substrate clock through it; Freehand is retired (rf2-0yp7w) and no
  supported substrate publishes a presence-clock verb to put in its place, so
  the bridge and those suites went with the donor. What remains is the whole
  of what Story owns: the rung is a SEAM, and a host installs its own advance
  through the public `install-presence-flush!`.

  That makes the stub host the right instrument rather than a compromise. A
  substrate's own three-phase machine (`:mounting` → `:present` →
  `:unmounting` against real DOM) was never Story's to prove; what these
  tests pin is that its PLAYBACK LOOP reaches the installed verb at the right
  point, and fails CLOSED when there is none.

  `.cljc` ending `-cljs-test` rides `npm run test:cljs` (node) AND
  `clojure -M:test` (JVM), so the rung is graft-checked on both hosts."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core                        :as rf]
            [re-frame.frame                       :as rf.frame]
            [re-frame.router                      :as rf.router]
            [re-frame.registrar                   :as rf.registrar]
            [re-frame.substrate.plain-atom        :as rf.substrate.plain-atom]
            [re-frame.story                       :as rf.story]
            [re-frame.story.determinism           :as rf.story.determinism]
            [re-frame.story.late-bind             :as rf.story.late-bind]
            [re-frame.story.plan                  :as rf.story.plan]
            [re-frame.story.play.presence         :as rf.story.play.presence]
            [re-frame.story.play.runner           :as rf.story.play.runner]
            [re-frame.story.play.runner-events    :as rf.story.play.runner-events]
            [re-frame.story.requirements          :as rf.story.requirements]))
;; NO SUBSTRATE :require, and that is the point of the rung (rf2-5gka): the
;; seam under test reaches its advance through the late-bind registry, so
;; every test here drives it with a stub host and Story's test classpath
;; carries no view substrate at all.

;; ===========================================================================
;; PURE: the step grammar (both hosts, no runtime)
;; ===========================================================================

(deftest flush-presence-is-a-known-step
  (testing "the one tagged grammar recognises :flush-presence"
    (is (contains? rf.story.play.runner/step-types :flush-presence))
    (is (= :flush-presence (rf.story.play.runner/step-type [:flush-presence])))
    (is (= :flush-presence (rf.story.play.runner/step-type [:flush-presence 300])))
    (is (true? (rf.story.play.runner/known-step? [:flush-presence])))
    (is (true? (rf.story.play.runner/known-step? [:flush-presence 300])))))

(deftest flush-presence-arity-mirrors-the-framework-verb
  (testing "the two arities are exactly flush-presence!'s: bare (to
            quiescence) and a non-negative ms (partial advance)"
    (is (true?  (rf.story.play.runner/step-arity-ok? [:flush-presence])))
    (is (true?  (rf.story.play.runner/step-arity-ok? [:flush-presence 0])))
    (is (true?  (rf.story.play.runner/step-arity-ok? [:flush-presence 300])))
    (is (false? (rf.story.play.runner/step-arity-ok? [:flush-presence -1])))
    (is (false? (rf.story.play.runner/step-arity-ok? [:flush-presence "300"])))
    (is (false? (rf.story.play.runner/step-arity-ok? [:flush-presence 100 200])))))

(deftest step-presence-ms-reads-the-advance
  (testing "nil means the no-arg arity (to quiescence), NOT 'absent'"
    (is (nil? (rf.story.play.runner/step-presence-ms [:flush-presence])))
    (is (= 300 (rf.story.play.runner/step-presence-ms [:flush-presence 300])))
    (is (nil? (rf.story.play.runner/step-presence-ms [:wait 300]))
        "the tag, never the nil, distinguishes the step")))

(deftest flush-presence-summary
  (is (= "flush-presence" (rf.story.play.runner/step-summary [:flush-presence])))
  (is (= "flush-presence 300ms" (rf.story.play.runner/step-summary [:flush-presence 300]))))

(deftest flush-presence-yields-a-tick
  (testing "the framework verb is Promise-backed on CLJS — the driver yields
            one tick so the retained subtree's removal COMMIT lands before
            the next step reads it"
    (is (contains? rf.story.play.runner/async-yield-step-types :flush-presence))
    (is (true? (rf.story.play.runner/async-yield? [:flush-presence])))))

(deftest flush-presence-round-trips-through-coercion
  (testing "a tagged step is never mistaken for a bare event vector"
    (let [tagged [[:dispatch [:e]] [:flush-presence 100] [:flush-presence]]]
      (is (= tagged (rf.story.play.runner/coerce-script tagged))))))

;; ===========================================================================
;; PURE: capabilities + determinism (both hosts)
;; ===========================================================================

(deftest flush-presence-requires-no-capability
  (testing "the presence clock is a process-global registry — advancing it
            needs no :dom (the ASSERTION that follows carries that)"
    (is (= #{} (get rf.story.requirements/step-capabilities :flush-presence)))
    (is (= #{} (rf.story.requirements/step-tokens [:flush-presence])))
    (is (= #{} (rf.story.requirements/step-tokens [:flush-presence 300]))))
  (testing "a presence-bearing script does not lift :required-runner to :dom"
    (let [p (rf.story.plan/variant-plan
              {:variant/id :story.presence/headless
               :script [[:dispatch [:e]]
                        [:flush-presence 100]
                        [:flush-presence]]}
              {})]
      (is (not (contains? (:required-runner p) :dom))))))

(deftest flush-presence-is-deterministic
  (testing "[:flush-presence] is NOT a wall-clock step — the determinism gate
            refuses [:wait ms] but accepts the fake-clock advance, so a
            presence-bearing variant keeps its stable verdict"
    (let [presence-art {:event-program [[:dispatch [:e]]
                                        [:flush-presence 100]
                                        [:flush-presence]]}
          wait-art     {:event-program [[:dispatch [:e]] [:wait 300]]}]
      (is (= [] (rf.story.determinism/wait-steps presence-art)))
      (is (false? (rf.story.determinism/has-wall-clock-wait? presence-art)))
      (is (true?  (rf.story.determinism/has-wall-clock-wait? wait-art))
          "the wall-clock opt-out is still refused — this rung is its
           deterministic alternative, not a loophole"))))

;; ===========================================================================
;; The host hook (both hosts)
;; ===========================================================================

(deftest install-presence-flush-registers-the-hook
  (let [calls (atom [])]
    (try
      (is (nil? (rf.story.play.presence/presence-flush-fn))
          "no host installed by default")
      (rf.story.play.presence/install-presence-flush! #(swap! calls conj %))
      (is (some? (rf.story.play.presence/presence-flush-fn)))
      (is (identical? (rf.story.play.presence/presence-flush-fn)
                      (rf.story.late-bind/get-fn :flush-presence!))
          "the hook lives in the shared late-bind registry")
      (testing "advance! threads the ms through, nil meaning 'to quiescence'"
        (is (= {:status :advanced :ms 100} (rf.story.play.presence/advance! 100)))
        (is (= {:status :advanced :ms nil} (rf.story.play.presence/advance! nil)))
        (is (= {:status :advanced :ms 0}   (rf.story.play.presence/advance! 0))
            "0 is a LEGAL advance, not an absent one — a host distinguishes
             the two on `some?`, so the seam must hand it 0 rather than
             collapsing it into the quiescence arity")
        (is (= [100 nil 0] @calls)))
      (finally (swap! rf.story.late-bind/hooks dissoc :flush-presence!)))))

(deftest advance-with-no-host-reports-no-host
  (testing "with no host installed the advance DID NOT HAPPEN, and `advance!`
            says so faithfully — the executor projects `:no-host` into a
            `:cannot-run` refusal (rf2-36biz). `advance!` itself stays pure
            data → data: it reports, the executor judges"
    (is (nil? (rf.story.play.presence/presence-flush-fn)))
    (is (= {:status :no-host :ms nil} (rf.story.play.presence/advance! nil)))
    (is (= {:status :no-host :ms 250} (rf.story.play.presence/advance! 250)))))

(deftest advance-surfaces-a-throwing-host
  (let [boom (ex-info "presence host exploded" {})]
    (try
      (rf.story.play.presence/install-presence-flush! (fn [_] (throw boom)))
      (let [res (rf.story.play.presence/advance! nil)]
        (is (= :error (:status res)) "a throwing host is never swallowed")
        (is (string? (:error res))))
      (finally (swap! rf.story.late-bind/hooks dissoc :flush-presence!)))))

;; ===========================================================================
;; PLAYBACK against a live frame
;; ===========================================================================

(def exec-step!
  "The private single-step executor, reached via var-quote — the established
  Story-test seam."
  @#'rf.story.play.runner-events/exec-step!)

(def presence-frame :story.presence/frame)

(defn setup!
  "Fresh registrar + runtime + variant frame. Shared with the real-clock
  companion so both halves of the rung test the same harness."
  []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  ;; Start every test HOOK-FREE, symmetrically with `teardown!`. The hook
  ;; registry is process-global and any namespace may install into it at load
  ;; time, so the no-host tests must not depend on ns-load order to see an
  ;; empty slot.
  (swap! rf.story.late-bind/hooks dissoc :flush-presence!)
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ nil))
  (reset! rf.story.play.runner-events/run-state {})
  ;; The canonical `:rf.assert/*` handlers must be installed so the
  ;; `[:assert-db …]` steps below record onto the assertion slot.
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (rf/make-frame {:id presence-frame :doc "presence rung test frame"})
  ;; `:presence/tick` stands in for "the toast is on screen and has just been
  ;; dismissed" — the source list dropped the key, so the boundary RETAINS the
  ;; child; `:presence/exited` is the app-visible consequence of its terminal
  ;; removal once the retention timeout fires.
  (rf/reg-event :presence/tick   (fn [{:keys [db]} _] {:db (assoc db :toast :retained)}))
  (rf/reg-event :presence/exited (fn [{:keys [db]} _] {:db (assoc db :toast :removed)}))
  nil)

(defn teardown!
  "Drop the process-global presence host.

  There is no clock to reset alongside it: every host in this ns is a stub
  whose whole state is the atom `install-stub-presence-host!` returns, and
  that dies with the test. The reset this used to perform belonged to the
  retired substrate bridge, which armed a process-global exit scheduler."
  []
  ;; The late-bind hook registry is process-global — a leaked presence host
  ;; would silently arm every LATER test's `[:flush-presence]` step.
  (swap! rf.story.late-bind/hooks dissoc :flush-presence!)
  nil)

;; The cross-platform FN form (`meta-fixtures-test`): the map form silently
;; skips every deftest on the JVM half of a `.cljc`. Everything in THIS ns is
;; synchronous — a stub host advances in the calling thread — so the fn form
;; is honoured on both hosts and no arm here needs the map form.
(use-fixtures :each (fn [f] (setup!) (try (f) (finally (teardown!)))))

(defn db [] (rf/app-db-value presence-frame))

;; ---------------------------------------------------------------------------
;; The seam, against a STUB presence host (both hosts)
;; ---------------------------------------------------------------------------
;;
;; A stub host stands in for the framework's exit scheduler: it holds ONE
;; pending exit with a `:timeout-ms` bound and fires it (dispatching the
;; app-visible consequence into the variant frame) only once the advanced
;; logical clock reaches that bound. That is the retention shape the real
;; presence clock has — enough to prove the PLAYBACK seam on both hosts,
;; without re-proving the framework's three-phase machine.

(defn- install-stub-presence-host!
  "Install a stub presence host holding one exit due at `timeout-ms`.
  Returns the atom holding its logical state."
  [timeout-ms]
  (let [state (atom {:now 0 :pending? true :advances []})]
    (rf.story.play.presence/install-presence-flush!
      (fn [ms]
        (swap! state update :advances conj ms)
        (let [now (if (nil? ms)
                    ;; nil = advance to quiescence: past every pending exit
                    (inc timeout-ms)
                    (+ (:now @state) ms))]
          (swap! state assoc :now now)
          (when (and (:pending? @state) (>= now timeout-ms))
            (swap! state assoc :pending? false)
            (rf.router/dispatch-sync! [:presence/exited] {:frame presence-frame})))))
    state))

(deftest presence-step-drives-the-host-verb
  (let [state (install-stub-presence-host! 300)]
    (testing "a partial advance below :timeout-ms leaves the exit RETAINED"
      (let [res (exec-step! presence-frame 0 [:flush-presence 100])]
        (is (nil? (:passed? res)) "an advance contributes no pass/fail of its own")
        (is (nil? (:exception res)))
        (is (true? (:pending? @state)) "still retained — the timeout has not come due")
        (is (nil? (:toast (db))))))
    (testing "an advance to quiescence fires the retained exit"
      (exec-step! presence-frame 1 [:flush-presence])
      (is (false? (:pending? @state)))
      (is (= :removed (:toast (db)))))
    (testing "both arities reached the host verb, ms threaded through"
      (is (= [100 nil] (:advances @state))))))

(deftest presence-step-with-no-host-refuses-cannot-run
  (testing "rf2-36biz — with NO presence host installed the advance did not
            happen, so the step REFUSES (`:cannot-run`) rather than skipping
            silently. `no hook installed` does not prove `no presence runtime
            exists`: an app can render retaining views and simply omit the
            install call, and its presence-bearing playback would then report
            a clean verdict over a clock that never moved"
    (let [res (exec-step! presence-frame 0 [:flush-presence])]
      (is (true? (:cannot-run? res)) "the distinct THIRD status, not a skip")
      (is (false? (:passed? res)))
      (is (nil? (:exception res)) "an absent host is a refusal, not a throw")
      (is (string? (:message res)))
      (is (re-find #"install-presence-flush!" (:message res))
          "the refusal NAMES the install path — an actionable refusal. It is
           now the ONLY install path: the optional bridge that used to be the
           other branch of this alternation retired with Freehand (rf2-5gka),
           so naming it is no longer a weaker claim than naming the seam")
      ;; A SEPARATE assertion rather than a branch of an alternation, for the
      ;; reason the retired substrate arm carried: `re-find` is satisfied by
      ;; any one branch, so folding this in would WEAKEN the install-path
      ;; claim above rather than add to it.
      (is (not (re-find #"(?i)freehand|re-frame\.ui" (:message res)))
          "rf2-5gka — the refusal is SUBSTRATE-NEUTRAL, and this is the only
           test that pins that. The message used to tell the user their app
           was one 'that renders Freehand views' (census row S11); the rung
           reaches its advance through a late-bind hook and never named a
           substrate for any reason but the retired bridge. A user-facing
           string is invisible to a residue grep over :require forms, so
           nothing else would notice a retired name coming back"))))

;; The shipped-bridge arm that stood here retired with Freehand (rf2-5gka). It
;; drove `presence-host/install!` — a namespace whose whole content was two
;; substrate verbs — and asserted that requiring it armed the hook. There is
;; no shipped installer to assert now, and the seam it installed THROUGH is
;; covered directly: `install-presence-flush-registers-the-hook` pins the same
;; late-bind slot and the same `advance!` threading, without a substrate.
;;
;; `0` remains a legal advance distinct from `nil`, and that moved there with
;; it. Which arity a HOST selects on `some?` is now the host's own business —
;; the seam's duty is only to hand `0` through as `0`.

(deftest presence-step-surfaces-a-throwing-host-as-an-exception
  (rf.story.play.presence/install-presence-flush! (fn [_] (throw (ex-info "boom" {}))))
  (let [res (exec-step! presence-frame 0 [:flush-presence])]
    (is (some? (:exception res)) "a throwing host fails the step loudly")
    (is (false? (:passed? res)))))

;; ---------------------------------------------------------------------------
;; RED / GREEN through the real playback loop (JVM — synchronous run!)
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest playback-without-the-presence-step-cannot-settle-the-retention
     (install-stub-presence-host! 300)
     (testing "RED — ordinary settlement does not touch the presence clock:
               dispatching and draining to a fixed point leaves the retained
               exit pending, so the assertion on its terminal removal FAILS.
               This is the race the rung exists to close"
       (let [done  (atom nil)
             _     (rf.story.play.runner-events/run! presence-frame "no-presence"
                            {:name   "no-presence"
                             :script [[:dispatch [:presence/tick]]
                                      [:assert-db [:toast] :removed]]}
                            #(reset! done %))
             state @done]
         (is (= :fail (:status state))
             "the retained exit never fired — playback raced the timeout")
         (is (= :retained (:toast (db)))
             "the child is still retained, exactly as the failing assertion said")))))

#?(:clj
   (deftest playback-with-the-presence-step-settles-deterministically
     (install-stub-presence-host! 300)
     (testing "GREEN — the same script with [:flush-presence] steps observes
               the child still RETAINED below :timeout-ms and then its
               terminal removal, with no wall-clock sleep anywhere"
       (let [done  (atom nil)
             _     (rf.story.play.runner-events/run! presence-frame "presence"
                            {:name   "presence"
                             :script [[:dispatch [:presence/tick]]
                                      [:flush-presence 100]
                                      [:assert-db [:toast] :retained]
                                      [:flush-presence]
                                      [:assert-db [:toast] :removed]]}
                            #(reset! done %))
             state @done]
         (is (= :pass (:status state))
             "both the retained-phase assertion and the removal assertion held")
         (is (= :removed (:toast (db))))))))

;; ---------------------------------------------------------------------------
;; FAIL CLOSED: an uninstalled host is a refusal, never a silent green
;; (rf2-36biz)
;; ---------------------------------------------------------------------------
;;
;; The retired justification for the silent skip was "the DOM assertion that
;; FOLLOWS carries the `:dom` requirement, so an incapable runner refuses
;; there". The grammar never required a following assertion, never required it
;; to be `:assert-dom`, and — more fundamentally — "no hook installed" does
;; not prove "no presence runtime exists". These two tests drive the SAME
;; script under the SAME headless runner with the ONLY following assertion an
;; `:assert-db`, so that premise cannot come back: the pair differs in exactly
;; one thing, whether the host was installed, and the verdicts must differ.

#?(:clj
   (deftest playback-with-no-presence-host-refuses-rather-than-passing-falsely
     (testing "RED-the-bug: no host installed. `[:flush-presence 100]` never
               advanced anything, yet `[:assert-db [:toast] :retained]` holds
               anyway — the toast is retained because NOTHING moved the clock,
               not because the advance stayed below :timeout-ms. The
               assertion cannot tell those two worlds apart, so the STEP must:
               the run is `:cannot-run`, never `:pass`"
       (let [done  (atom nil)
             _     (rf.story.play.runner-events/run! presence-frame "no-host"
                            {:name   "no-host"
                             :script [[:dispatch [:presence/tick]]
                                      [:flush-presence 100]
                                      [:assert-db [:toast] :retained]]}
                            #(reset! done %))
             state @done]
         (is (= :cannot-run (:status state))
             "an uninstalled presence host fails CLOSED")
         (is (not= :pass (:status state))
             "the silent false green rf2-36biz names is gone")))))

#?(:clj
   (deftest playback-with-an-installed-host-still-passes-the-same-script
     (install-stub-presence-host! 300)
     (testing "the other direction — over-tightening would be worse than the
               bug. The IDENTICAL script, `:assert-db` its only assertion,
               still passes once a host is properly installed"
       (let [done  (atom nil)
             _     (rf.story.play.runner-events/run! presence-frame "with-host"
                            {:name   "with-host"
                             :script [[:dispatch [:presence/tick]]
                                      [:flush-presence 100]
                                      [:assert-db [:toast] :retained]]}
                            #(reset! done %))
             state @done]
         (is (= :pass (:status state))
             "a valid setup is never refused")))))

;; A host's OWN clock is not proven here, and no longer anywhere in Story. The
;; `.cljs` companion that drove Freehand's real presence scheduler through the
;; shipped bridge retired with both (rf2-5gka). If a substrate ever publishes
;; a presence-advance verb and a bridge is written for it, its real-clock arm
;; belongs beside that bridge — and will need a MAP fixture if that verb is
;; Promise-backed, which a `.cljc` may not use.
