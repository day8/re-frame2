(ns re-frame.story.play.presence-cljs-test
  "S4-H (rf2-qwzmt) — Story's presence rung: the `[:flush-presence]` script
  step consumes the framework's own `re-frame.ui.test/flush-presence!` so a
  presence-bearing variant settles DETERMINISTICALLY during playback.

  The problem the rung solves: a variant whose view renders a
  `(ui/presence {:timeout-ms n} …)` boundary RETAINS a removed keyed child
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
    `re-frame.ui.test/flush-presence!` and the ACTUAL presence exit
    scheduler. That arm is ASYNC (the verb is Promise-backed on CLJS) and
    lives in the pure-`.cljs` companion
    `presence_real_clock_cljs_test.cljs`, which reuses this ns's harness.
    The JVM arm of `flush-presence!` is a documented no-op (there is no
    lifecycle to advance on the structural host), so the real-clock arm is
    CLJS-only — exactly the host split the framework verb declares.

  Mounted three-phase behaviour (`:mounting` → `:present` → `:unmounting`
  against real DOM) belongs to the framework and is pinned there
  (`re-frame.ui.presence-dom-cljs-test`); Story does not re-prove it. What
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
            [re-frame.story.play.runner           :as runner]
            [re-frame.story.play.runner-events    :as re]
            [re-frame.story.requirements          :as requirements]
            ;; The framework's presence exit scheduler — reached ONLY to reset
            ;; it between tests (the CLOCK is a CLJS-only surface: the JVM
            ;; structural host has no lifecycle, so the call is reader-gated).
            ;; `day8/re-frame2-ui` is pinned on the `:test` alias only — Story's
            ;; shipped jar must not depend on the pre-publication artefact,
            ;; which is precisely why the rung takes the verb through a
            ;; late-bind hook instead of a `:require`.
            [re-frame.ui.presence-runtime         :as presence-rt]))

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

(deftest advance-with-no-host-is-a-no-op-not-a-refusal
  (testing "host parity: the framework's own JVM arm of flush-presence! is a
            no-op, so a Story host with no presence runtime advances a no-op
            too — there is no retention to advance and nothing to pass
            falsely (the DOM assertion that follows carries the refusal)"
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

(deftest presence-step-with-no-host-skips-cleanly
  (testing "with no presence host the step is a SKIP, not a refusal and not
            an exception — the same no-op the framework's JVM arm is"
    (let [res (exec-step! presence-frame 0 [:flush-presence])]
      (is (nil? (:passed? res)))
      (is (nil? (:exception res))))))

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

;; The REAL framework verb driven against the REAL presence clock is the
;; pure-`.cljs` companion `presence_real_clock_cljs_test.cljs` — that arm is
;; ASYNC (the verb is Promise-backed on CLJS), and cljs.test requires a MAP
;; fixture once a ns carries async tests, which a `.cljc` may not use.
