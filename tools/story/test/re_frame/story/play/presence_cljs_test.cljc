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

  Three layers:

  - PURE grammar (both hosts) — `[:flush-presence]` / `[:flush-presence ms]`
    are known steps with the framework verb's two arities, require NO
    capability token, do not lift `:required-runner` to `:dom`, and are NOT
    wall-clock steps (the determinism gate accepts a script carrying them).
  - The SEAM against a stub presence host (both hosts) — the step routes the
    ms through `re-frame.story.play.presence/advance!`; a script WITHOUT the
    step leaves the retained exit pending and its assertion FAILS, the same
    script WITH it passes. This is the red-before/green-after, proven
    host-agnostically through the real `run!` playback loop.
  - The REAL framework verb — the same script driven against the ACTUAL
    `re-frame.freehand.presence-runtime/advance-clock!` and the ACTUAL
    presence exit scheduler. That arm lives in the pure-`.cljs` companion
    `presence_real_clock_cljs_test.cljs`, which reuses this ns's harness.
    The JVM arm of the bridge is a documented no-op (the structural host
    has no lifecycle to advance, and the clock is a CLJS-only surface), so
    the real-clock arm is CLJS-only — exactly the host split the framework
    runtime declares.

  Mounted three-phase behaviour (`:mounting` → `:present` → `:unmounting`
  against real DOM) belongs to the framework and is pinned there
  (`re-frame.freehand.presence-dom-cljs-test`); Story does not re-prove
  it. What
  Story owns — and what these tests pin — is that its PLAYBACK LOOP can
  reach the verb at the right point.

  `.cljc` ending `-cljs-test` rides `npm run test:cljs` (node) AND
  `clojure -M:test` (JVM), so the rung is graft-checked on both hosts."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core                        :as rf]
            [re-frame.frame                       :as frame]
            [re-frame.router                      :as router]
            [re-frame.registrar                   :as registrar]
            [re-frame.substrate.plain-atom        :as plain-atom]
            [re-frame.story                       :as story]
            [re-frame.story.determinism           :as determinism]
            [re-frame.story.late-bind             :as late-bind]
            [re-frame.story.plan                  :as plan]
            [re-frame.story.play.presence         :as story-presence]
            ;; The OPTIONAL bridge under test (rf2-36biz) — the one canonical
            ;; installation path. Requiring it is what an app does; it holds
            ;; the Freehand dependency on the app's side of the seam, so
            ;; Story's own namespaces never require it (and its jar stays
            ;; free of the pre-publication artefact).
            [re-frame.story.play.presence-host    :as presence-host]
            [re-frame.story.play.runner           :as runner]
            [re-frame.story.play.runner-events    :as re]
            [re-frame.story.requirements          :as requirements]
            ;; The framework's presence exit scheduler — reached ONLY to reset
            ;; it between tests (the CLOCK is a CLJS-only surface: the JVM
            ;; structural host has no lifecycle, so the call is reader-gated).
            ;; `day8/re-frame2-freehand` is pinned on the `:test` alias only —
            ;; Story's
            ;; shipped jar must not depend on the pre-publication artefact,
            ;; which is precisely why the rung takes the verb through a
            ;; late-bind hook instead of a `:require`.
            [re-frame.freehand.presence-runtime   :as presence-rt]))

;; ===========================================================================
;; PURE: the step grammar (both hosts, no runtime)
;; ===========================================================================

(deftest flush-presence-is-a-known-step
  (testing "the one tagged grammar recognises :flush-presence"
    (is (contains? runner/step-types :flush-presence))
    (is (= :flush-presence (runner/step-type [:flush-presence])))
    (is (= :flush-presence (runner/step-type [:flush-presence 300])))
    (is (true? (runner/known-step? [:flush-presence])))
    (is (true? (runner/known-step? [:flush-presence 300])))))

(deftest flush-presence-arity-mirrors-the-framework-verb
  (testing "the two arities are exactly flush-presence!'s: bare (to
            quiescence) and a non-negative ms (partial advance)"
    (is (true?  (runner/step-arity-ok? [:flush-presence])))
    (is (true?  (runner/step-arity-ok? [:flush-presence 0])))
    (is (true?  (runner/step-arity-ok? [:flush-presence 300])))
    (is (false? (runner/step-arity-ok? [:flush-presence -1])))
    (is (false? (runner/step-arity-ok? [:flush-presence "300"])))
    (is (false? (runner/step-arity-ok? [:flush-presence 100 200])))))

(deftest step-presence-ms-reads-the-advance
  (testing "nil means the no-arg arity (to quiescence), NOT 'absent'"
    (is (nil? (runner/step-presence-ms [:flush-presence])))
    (is (= 300 (runner/step-presence-ms [:flush-presence 300])))
    (is (nil? (runner/step-presence-ms [:wait 300]))
        "the tag, never the nil, distinguishes the step")))

(deftest flush-presence-summary
  (is (= "flush-presence" (runner/step-summary [:flush-presence])))
  (is (= "flush-presence 300ms" (runner/step-summary [:flush-presence 300]))))

(deftest flush-presence-yields-a-tick
  (testing "the framework verb is Promise-backed on CLJS — the driver yields
            one tick so the retained subtree's removal COMMIT lands before
            the next step reads it"
    (is (contains? runner/async-yield-step-types :flush-presence))
    (is (true? (runner/async-yield? [:flush-presence])))))

(deftest flush-presence-round-trips-through-coercion
  (testing "a tagged step is never mistaken for a bare event vector"
    (let [tagged [[:dispatch [:e]] [:flush-presence 100] [:flush-presence]]]
      (is (= tagged (runner/coerce-script tagged))))))

;; ===========================================================================
;; PURE: capabilities + determinism (both hosts)
;; ===========================================================================

(deftest flush-presence-requires-no-capability
  (testing "the presence clock is a process-global registry — advancing it
            needs no :dom (the ASSERTION that follows carries that)"
    (is (= #{} (get requirements/step-capabilities :flush-presence)))
    (is (= #{} (requirements/step-tokens [:flush-presence])))
    (is (= #{} (requirements/step-tokens [:flush-presence 300]))))
  (testing "a presence-bearing script does not lift :required-runner to :dom"
    (let [p (plan/variant-plan
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
      (is (= [] (determinism/wait-steps presence-art)))
      (is (false? (determinism/has-wall-clock-wait? presence-art)))
      (is (true?  (determinism/has-wall-clock-wait? wait-art))
          "the wall-clock opt-out is still refused — this rung is its
           deterministic alternative, not a loophole"))))

;; ===========================================================================
;; The host hook (both hosts)
;; ===========================================================================

(deftest install-presence-flush-registers-the-hook
  (let [calls (atom [])]
    (try
      (is (nil? (story-presence/presence-flush-fn))
          "no host installed by default")
      (story-presence/install-presence-flush! #(swap! calls conj %))
      (is (some? (story-presence/presence-flush-fn)))
      (is (identical? (story-presence/presence-flush-fn)
                      (late-bind/get-fn :flush-presence!))
          "the hook lives in the shared late-bind registry")
      (testing "advance! threads the ms through, nil meaning 'to quiescence'"
        (is (= {:status :advanced :ms 100} (story-presence/advance! 100)))
        (is (= {:status :advanced :ms nil} (story-presence/advance! nil)))
        (is (= [100 nil] @calls)))
      (finally (swap! late-bind/hooks dissoc :flush-presence!)))))

(deftest advance-with-no-host-reports-no-host
  (testing "with no host installed the advance DID NOT HAPPEN, and `advance!`
            says so faithfully — the executor projects `:no-host` into a
            `:cannot-run` refusal (rf2-36biz). `advance!` itself stays pure
            data → data: it reports, the executor judges"
    (is (nil? (story-presence/presence-flush-fn)))
    (is (= {:status :no-host :ms nil} (story-presence/advance! nil)))
    (is (= {:status :no-host :ms 250} (story-presence/advance! 250)))))

(deftest advance-surfaces-a-throwing-host
  (let [boom (ex-info "presence host exploded" {})]
    (try
      (story-presence/install-presence-flush! (fn [_] (throw boom)))
      (let [res (story-presence/advance! nil)]
        (is (= :error (:status res)) "a throwing host is never swallowed")
        (is (string? (:error res))))
      (finally (swap! late-bind/hooks dissoc :flush-presence!)))))

;; ===========================================================================
;; PLAYBACK against a live frame
;; ===========================================================================

(def exec-step!
  "The private single-step executor, reached via var-quote — the established
  Story-test seam. Public here so the pure-`.cljs` real-clock companion
  (`presence-real-clock-cljs-test`) drives the SAME executor."
  @#'re/exec-step!)

(def presence-frame :story.presence/frame)

(defn setup!
  "Fresh registrar + runtime + variant frame. Shared with the real-clock
  companion so both halves of the rung test the same harness."
  []
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  ;; Start every test HOOK-FREE, symmetrically with `teardown!`. The hook
  ;; registry is process-global, and merely REQUIRING the optional bridge
  ;; (`presence-host`, whose load-time install is the whole point of it)
  ;; arms it for the rest of the process — so the no-host tests must not
  ;; depend on ns-load order to see an empty slot.
  (swap! late-bind/hooks dissoc :flush-presence!)
  (try (rf/init! plain-atom/adapter)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ nil))
  (reset! re/run-state {})
  ;; The canonical `:rf.assert/*` handlers must be installed so the
  ;; `[:assert-db …]` steps below record onto the assertion slot.
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!)
  (rf/make-frame {:id presence-frame :doc "presence rung test frame"})
  ;; `:presence/tick` stands in for "the toast is on screen and has just been
  ;; dismissed" — the source list dropped the key, so the boundary RETAINS the
  ;; child; `:presence/exited` is the app-visible consequence of its terminal
  ;; removal once the retention timeout fires.
  (rf/reg-event :presence/tick   (fn [{:keys [db]} _] {:db (assoc db :toast :retained)}))
  (rf/reg-event :presence/exited (fn [{:keys [db]} _] {:db (assoc db :toast :removed)}))
  nil)

(defn teardown!
  "Drop the process-global presence host + reset the framework clock."
  []
  ;; The late-bind hook registry is process-global — a leaked presence host
  ;; would silently arm every LATER test's `[:flush-presence]` step.
  (swap! late-bind/hooks dissoc :flush-presence!)
  #?(:cljs (do (presence-rt/reset-clock!)
               (presence-rt/set-wall-clock! true)))
  nil)

;; The cross-platform FN form (`meta-fixtures-test`): the map form silently
;; skips every deftest on the JVM half of a `.cljc`. Everything in THIS ns is
;; synchronous, so the fn form is honoured on both hosts. The async arm — which
;; cljs.test requires a MAP fixture for — lives in the pure-`.cljs` companion
;; `presence_real_clock_cljs_test.cljs`, where the map form is legitimate.
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
    (story-presence/install-presence-flush!
      (fn [ms]
        (swap! state update :advances conj ms)
        (let [now (if (nil? ms)
                    ;; nil = advance to quiescence: past every pending exit
                    (inc timeout-ms)
                    (+ (:now @state) ms))]
          (swap! state assoc :now now)
          (when (and (:pending? @state) (>= now timeout-ms))
            (swap! state assoc :pending? false)
            (router/dispatch-sync! [:presence/exited] {:frame presence-frame})))))
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
            exists`: an app can load Freehand and simply omit the bridge
            call, and its presence-bearing playback would then report a clean
            verdict over a clock that never moved"
    (let [res (exec-step! presence-frame 0 [:flush-presence])]
      (is (true? (:cannot-run? res)) "the distinct THIRD status, not a skip")
      (is (false? (:passed? res)))
      (is (nil? (:exception res)) "an absent host is a refusal, not a throw")
      (is (string? (:message res)))
      (is (re-find #"install-presence-flush!|presence-host" (:message res))
          "the refusal NAMES the install path — an actionable refusal")
      ;; A SEPARATE assertion rather than a third branch of the alternation
      ;; above: `re-find` is satisfied by any one branch, so widening that
      ;; regex would have WEAKENED the install-path claim it already makes.
      (is (re-find #"Freehand" (:message res))
          "rf2-gj0a — the refusal also names the SUBSTRATE, and this is the
           only test that pins that word. The census (docs/design/hicasso/
           product/tool-consumer-census.md, row S11) counts it a live donor
           mention; it is invisible to the census's own case-sensitive grep,
           so nothing static will notice it going stale. When the bridge
           leaves Freehand (rf2-5gka) this fails and the user-facing message
           is reworded in the same change"))))

;; The bridge's INSTALLATION is host-agnostic, but DRIVING the installed verb
;; repeatedly is not: on CLJS `flush-presence!` is Promise-backed and holds a
;; single in-flight act token, so back-to-back synchronous advances are the
;; framework's own `:rf.error/ui-test-overlapping-act` misuse. This arm is
;; therefore JVM-only, where the verb is a documented synchronous no-op. The
;; CLJS end-to-end proof — the shipped bridge driving the REAL clock, with the
;; act yields the run loop gives — is the real-clock `.cljs` companion, which
;; installs through this same `presence-host/install!`.
#?(:clj
   (deftest presence-host-bridge-installs-the-framework-verb
     (testing "rf2-36biz — the hook shipped with NO production installer: only
               tests and docstrings called `install-presence-flush!`, so a
               presence-bearing script could never reach the real clock. The
               optional bridge is that missing installer, and it is REAL —
               this drives the SHIPPED `install!`, not a copy of it"
       (is (nil? (story-presence/presence-flush-fn))
           "the fixture cleared the process-global hook")
       (presence-host/install!)
       (is (some? (story-presence/presence-flush-fn))
           "one call — and a bare :require does it at load time")
       (is (identical? (story-presence/presence-flush-fn)
                       (late-bind/get-fn :flush-presence!))
           "installed into the SAME late-bind slot the executor reads")
       (testing "the installed verb runs — the framework's JVM arm of
                 flush-presence! is its own documented no-op"
         (is (= {:status :advanced :ms nil} (story-presence/advance! nil)))
         (is (= {:status :advanced :ms 0}   (story-presence/advance! 0))
             "0 is a legal advance — the arity is chosen on some?, not truth"))
       (testing "idempotent: re-installing replaces the slot, never doubles it"
         (presence-host/install!)
         (is (= {:status :advanced :ms nil} (story-presence/advance! nil))))
       (testing "and the step the bead is about now RUNS instead of refusing"
         (let [res (exec-step! presence-frame 0 [:flush-presence])]
           (is (nil? (:cannot-run? res)))
           (is (nil? (:exception res))))))))

(deftest presence-step-surfaces-a-throwing-host-as-an-exception
  (story-presence/install-presence-flush! (fn [_] (throw (ex-info "boom" {}))))
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
             _     (re/run! presence-frame "no-presence"
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
             _     (re/run! presence-frame "presence"
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
             _     (re/run! presence-frame "no-host"
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
             _     (re/run! presence-frame "with-host"
                            {:name   "with-host"
                             :script [[:dispatch [:presence/tick]]
                                      [:flush-presence 100]
                                      [:assert-db [:toast] :retained]]}
                            #(reset! done %))
             state @done]
         (is (= :pass (:status state))
             "a valid setup is never refused")))))

;; The REAL framework verb driven against the REAL presence clock is the
;; pure-`.cljs` companion `presence_real_clock_cljs_test.cljs` — that arm is
;; ASYNC (the verb is Promise-backed on CLJS), and cljs.test requires a MAP
;; fixture once a ns carries async tests, which a `.cljc` may not use.
