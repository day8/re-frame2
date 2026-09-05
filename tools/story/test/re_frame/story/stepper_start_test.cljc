(ns re-frame.story.stepper-start-test
  "Regression net for the step-debugger's START ordering (rf2-k6y2).

  The `:test` pane's step-debugger promises that cursor 0 IS the pre-play
  state: `009-Test-Mode.md` §Play step-debugger rows Start as
  \"re-allocate the frame + prime the substrate\", labels that position
  \"ready · N steps\", and specifies Rewind as a restore to \"the pre-play
  epoch\". `stepper-state/begin!` used to reach that position through
  `runtime/reset-variant`, which is a FULL run — phases 0-2 AND phase 4 —
  so a bare `:script` (`:auto-run?` defaults to true) executed end to end
  BEFORE cursor 0 was published. The user pressed Start, watched the whole
  script run, and was then shown \"ready\" over a post-script app-db; the
  first Step ran step 1 a SECOND time, and Rewind restored the post-script
  epoch rather than the specified initial state.

  The defect was deterministic, not a race: `reset-variant`'s promise
  settles only after phase 4 has drained, so EVERY script step ran before
  Start's continuation, every time — not a variable prefix.

  These tests drive the same composition `begin!` performs — the runtime
  seam, then `play/begin-stepper!`, then `play/step-once!` — against the
  real plain-atom adapter + lifecycle machine + plan compiler + runner, and
  assert the SEQUENCE the debugger observes rather than the run's end
  state. An end-state assertion is green either way: the script produces
  `:count` 3 whether the debugger watched it or not.

  Runs on BOTH runtimes: phases 0-2 are synchronous and the script steps
  are pure `:dispatch-sync`, so the frame's app-db and the external-effect
  counter are settled the instant each call returns — no awaiting needed."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch :as rf.epoch]
            [re-frame.frame :as rf.frame]
            [re-frame.machines :as rf.machines]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story :as rf.story]
            [re-frame.story.loaders :as rf.story.loaders]
            [re-frame.story.play :as rf.story.play]
            [re-frame.story.play.runner-events :as rf.story.play.runner-events]
            [re-frame.story.runtime :as rf.story.runtime]
            #?@(:clj [[re-frame.story.async :as rf.story.async]
                      [re-frame.story.config :as rf.story.config]])))

;; ---- external-effect counter (an irreversible effect proxy) --------------
;;
;; app-db resets hide a re-run, but an external effect cannot be un-sent —
;; so this counter is the honest witness of whether Start executed script
;; work behind the user's back (acceptance 3).

(def ^:private ext-effect-count (atom 0))

(defn- reset-all! [test-fn]
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  ;; Requiring `re-frame.epoch` installs the epoch artefact's late-bind
  ;; hooks, so `rf/epoch-history` records a real tape and `rf/restore-epoch!`
  ;; can travel back to the pre-play epoch the way the CLJS `rewind!` does.
  ;; Clear the per-frame ring + listeners between tests so each run reads
  ;; only its own epochs.
  (rf.epoch/clear-history!)
  (rf.epoch/clear-epoch-listeners!)
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ nil))
  #?(:clj (require 're-frame.machines :reload))
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  #?(:clj (rf.story.config/set-global-args! {}))
  (reset! rf.story.play/stepper-state {})
  (reset! rf.story.play.runner-events/run-state {})
  (reset! rf.story.play.runner-events/step-boundaries {})
  (rf.story.runtime/reset-run-owner!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (reset! ext-effect-count 0)
  (rf/reg-fx :probe/external-effect (fn [_ _] (swap! ext-effect-count inc)))
  (rf/reg-event :probe/inc-and-effect
    (fn [{:keys [db]} _]
      {:db (update db :count (fnil inc 0))
       :fx [[:probe/external-effect nil]]}))
  (rf/reg-event :counter/initialise
    (fn [{:keys [db]} [_ n]] {:db (assoc db :count (or n 0))}))
  (test-fn))

(use-fixtures :each reset-all!)

;; ---- helpers -------------------------------------------------------------

(defn- reg-mutating!
  "Register a MUTATING default-auto-run variant: `:setup` seeds `:count` 0
  and a bare three-step `:script` increments it (each increment also issues
  the external effect). A bare `:script` omits `:auto-run?`, so
  `runner/default-auto-run?` makes it auto-runnable — the exact shape the
  defect needed."
  [vid]
  (rf.story/reg-variant vid
    {:setup  [[:counter/initialise 0]]
     :script [[:dispatch-sync [:probe/inc-and-effect]]
              [:dispatch-sync [:probe/inc-and-effect]]
              [:dispatch-sync [:probe/inc-and-effect]]]}))

(defn- start!
  "The runtime half of `stepper-state/begin!`: prepare the variant through
  the PRE-PLAY lifecycle, then prime the stepper substrate. The CLJS
  mutator wraps exactly this pair in its promise continuation and adds the
  ratom slot."
  [vid]
  (rf.story.runtime/prepare-variant vid)
  (rf.story.play/begin-stepper! vid))

(defn- count-of [vid] (:count (rf/app-db-value vid)))

(defn- slot [vid] (get @rf.story.play/stepper-state vid))

;; ---- (1) Start leaves the whole script pending ---------------------------

(deftest start-parks-at-the-pre-play-state
  (testing "Start prepares phases 0-2 and runs NO script step: the frame
            carries the :setup state, no script effect has been issued, and
            every step is still pending"
    (let [vid :story.stepper/mutating]
      (reg-mutating! vid)
      (start! vid)
      (is (= 0 (count-of vid))
          "cursor 0 shows the :setup state, not the post-script state")
      (is (zero? @ext-effect-count)
          "Start issued no script effect — nothing ran behind the user")
      (is (= 3 (count (:remaining (slot vid))))
          "all three steps are pending")
      (is (= [] (:ran (slot vid)))
          "the debugger has observed no step yet")
      (is (= [] (:results (slot vid)))
          "and recorded no step outcome yet"))))

;; ---- (2) the observed sequence IS the performed sequence ------------------

(deftest the-debugger-observes-every-step-exactly-once
  (testing "the app-db trajectory the debugger walks is the trajectory the
            script performs — 1:1 with the cursor, no lost prefix and no
            repeat"
    (let [vid :story.stepper/mutating]
      (reg-mutating! vid)
      (start! vid)
      (let [trajectory (into [(count-of vid)]
                             (mapv (fn [_]
                                     (rf.story.play/step-once! vid)
                                     (count-of vid))
                                   (range 3)))]
        (is (= [0 1 2 3] trajectory)
            "step N moves :count from N-1 to N")
        (is (= 3 @ext-effect-count)
            "each script effect was issued exactly once across the run")
        (is (= (rf.story.play/variant-play-steps vid) (:ran (slot vid)))
            "the steps the debugger recorded are the script's steps, in order")
        (is (= [] (:remaining (slot vid)))
            "and the run is parked at the end")))))

;; ---- (3) the pre-play epoch is the one Rewind restores -------------------

(deftest rewind-restores-the-setup-state
  (testing "the epoch Start leaves at the bottom of the stepper's epoch
            stack is the :setup state — restoring it returns :count to 0,
            not to the post-script 3"
    (let [vid :story.stepper/mutating]
      (reg-mutating! vid)
      (start! vid)
      (let [pre-play (-> (rf/epoch-history vid) last :epoch-id)]
        (is (some? pre-play) "the pre-play epoch is recorded")
        (dotimes [_ 3] (rf.story.play/step-once! vid))
        (is (= 3 (count-of vid)) "the stepped run reached the script's end")
        (rf/restore-epoch! vid pre-play)
        (rf.story.play/stepper-rewind! vid)
        (is (= 0 (count-of vid))
            "Rewind returns the frame to the specified initial state")
        (is (= 3 (count (:remaining (slot vid))))
            "and every step is pending again")))))

;; ---- (4) the full-run entry points are UNCHANGED -------------------------

(deftest reset-variant-still-runs-the-whole-script
  (testing "`reset-variant` keeps its full-run meaning (the Re-run button) —
            the fix narrows what START uses, not what a re-run does"
    (let [vid :story.stepper/mutating]
      (reg-mutating! vid)
      (rf.story.runtime/reset-variant vid)
      (is (= 3 (count-of vid))
          "reset-variant ran the script end to end, as before")
      (is (= 3 @ext-effect-count)
          "and issued every script effect"))))

(deftest prepare-run-and-resume-run-still-split-the-lifecycle
  (testing "the one run owner's PREPARE / RESUME split is untouched: prepare
            alone runs no script, resume runs it exactly once"
    (let [vid :story.stepper/mutating]
      (reg-mutating! vid)
      (rf.story.runtime/prepare-run! vid {:run-key {:variant-id vid}})
      (is (= 0 (count-of vid)) "prepare ran no script step")
      (rf.story.runtime/resume-run! vid)
      (is (= 3 (count-of vid)) "resume ran the script")
      (is (= 3 @ext-effect-count) "exactly once"))))

;; ---- (5) a prepare failure settles honestly ------------------------------
;;
;; `begin!` has ONE branch for a failed preparation: the promise's rejection
;; path, whose `.catch` drops the slot so the section returns to its inactive
;; state. So `prepare-variant` REJECTING is not a stylistic choice about how
;; an error travels — it is the whole of what stops the debugger presenting
;; step controls over a frame that never reached the `:setup` state.
;;
;; The failure classes split by HOW the failure travels, and the split is
;; invisible from the call site:
;;
;;   • THROWN — an unknown variant (the plan compiler refuses), a `:db-seed`
;;     that violates a registered schema. The throw escapes `prepare-ctx!`
;;     and `rf.story.async/promise` rejects on it.
;;
;;   • CAPTURED — a throwing `:loaders` or `:setup` handler. `run-loaders!` /
;;     `run-events!` deliberately do NOT rethrow: `capture-phase-errors`
;;     collects the pipeline-exception trace events and `record-error!`
;;     projects each onto the frame's `[:rf.story/assertions]`, then the
;;     phase RETURNS normally. That is `run-variant`'s gather-the-full-picture
;;     contract and it stays. But it means `prepare-ctx!` completes without
;;     throwing over a frame whose `:setup` never ran (rf2-k6y2 post-merge
;;     audit) — so the captured class needs its OWN check, and these tests
;;     are it.

#?(:clj
   (defn- begin-outcome
     "Drive the exact composition `stepper-state/begin!` performs — the
     runtime seam, then `play/begin-stepper!` ONLY on the resolve path — and
     report which branch ran (`:then` / `:catch`).

     `begin!`'s CLJS body is `(-> (prepare-variant v) (.then …) (.catch …))`;
     `rf.story.async/then` + `catch*` are the portable spelling of that same
     pair, so this drives the control flow the Start button drives rather
     than a re-description of it."
     [vid]
     (let [branch (atom nil)]
       (-> (rf.story.runtime/prepare-variant vid)
           (rf.story.async/then   (fn [_]
                                    (rf.story.play/begin-stepper! vid)
                                    (reset! branch :then)
                                    nil))
           (rf.story.async/catch* (fn [_] (reset! branch :catch) nil))
           (rf.story.async/deref-blocking 2000))
       @branch)))

#?(:clj
   (deftest prepare-variant-rejects-on-a-failed-preparation
     (testing "an unregistered variant REJECTS the promise rather than
               resolving, so the caller returns its section to the inactive
               state instead of presenting a stepper over a frame that never
               reached :ready"
       (let [p (rf.story.runtime/prepare-variant :story.stepper/never-registered)]
         (is (thrown? java.util.concurrent.ExecutionException
                      (rf.story.async/deref-blocking p 2000)))))))

#?(:clj
   (deftest start-takes-the-resolve-branch-for-a-healthy-variant
     (testing "the positive control for the two tests below: a variant that
               prepares cleanly resolves, so Start reaches `begin-stepper!`
               and publishes a slot. Without this the `:catch` assertions
               below would pass on a seam that rejected everything"
       (let [vid :story.stepper/mutating]
         (reg-mutating! vid)
         (is (= :then (begin-outcome vid))
             "a clean preparation takes the resolve branch")
         (is (some? (slot vid))
             "and Start primed the stepper substrate")
         (is (= 0 (count-of vid))
             "over the :setup state, with the script still pending")))))

#?(:clj
   (deftest start-refuses-a-captured-setup-failure
     (testing "a `:setup` handler that THROWS is captured onto
               `[:rf.story/assertions]` rather than propagated, so phases 0-2
               return normally — but the frame never reached the state the
               variant's `:setup` describes. Start must take the rejection
               branch and publish NO stepper, exactly as it does for an
               unknown variant (acceptance 3)"
       (let [vid :story.stepper/setup-throws]
         (rf/reg-event :probe/setup-throws
           (fn [_ _] (throw (ex-info "setup handler blew up" {:probe true}))))
         (rf.story/reg-variant vid
           {:setup  [[:probe/setup-throws]]
            :script [[:dispatch-sync [:probe/inc-and-effect]]]})
         (is (= :catch (begin-outcome vid))
             "the failed preparation rejects rather than resolving nil")
         (is (nil? (slot vid))
             "so Start never primed the substrate")
         (is (zero? @ext-effect-count)
             "and issued no script effect")
         (is (seq (filter (comp false? :passed?)
                          (rf.story.runtime/read-assertions vid)))
             "while the captured failure REMAINS on the frame — the
              rejection reports the failure, it does not erase it")))))

#?(:clj
   (deftest start-refuses-a-captured-loader-failure
     (testing "the same for a throwing `:loaders` handler: `run-loaders!`
               captures it into the assertions accumulator and returns, so
               Start must branch on the recorded failure rather than on a
               throw that never arrives"
       (let [vid :story.stepper/loader-throws]
         (rf/reg-event :probe/loader-throws
           (fn [_ _] (throw (ex-info "loader blew up" {:probe true}))))
         (rf.story/reg-variant vid
           {:loaders [[:probe/loader-throws]]
            :setup   [[:counter/initialise 0]]
            :script  [[:dispatch-sync [:probe/inc-and-effect]]]})
         (is (= :catch (begin-outcome vid))
             "the failed preparation rejects rather than resolving nil")
         (is (nil? (slot vid))
             "so Start never primed the substrate")
         (is (zero? @ext-effect-count)
             "and issued no script effect")))))

#?(:clj
   (deftest the-full-run-path-still-gathers-the-whole-picture
     (testing "`run-variant` is UNCHANGED by the refusal above: a captured
               `:setup` failure still RESOLVES a result carrying the failed
               assertion rather than rejecting. The narrowing is Start's
               alone — the runner keeps reporting everything it saw"
       (let [vid :story.stepper/setup-throws-full-run]
         (rf/reg-event :probe/setup-throws
           (fn [_ _] (throw (ex-info "setup handler blew up" {:probe true}))))
         (rf.story/reg-variant vid
           {:setup  [[:probe/setup-throws]]
            :script [[:dispatch-sync [:probe/inc-and-effect]]]})
         (let [result (rf.story.async/deref-blocking
                        (rf.story.runtime/run-variant vid) 2000)]
           (is (map? result)
               "the full run resolved a result rather than rejecting")
           (is (seq (filter (comp false? :passed?) (:assertions result)))
               "carrying the captured failure"))))))

;; ---- (6) a REDACTED prepare failure settles honestly ---------------------
;;
;; Section (5) branches on the assertion records `capture-phase-errors`
;; projects onto `[:rf.story/assertions]`. Those records are the DISPLAY
;; path, and the display path runs THROUGH the privacy egress filter: the
;; capture listener's first `cond` branch drops a `:sensitive?` trace event
;; and only bumps the suppressed counter (Spec 009 §Privacy + EP-0015,
;; `:rf.egress/local-redacted` is the default profile).
;;
;; So a setup/loader handler whose failure is CLASSIFIED SENSITIVE produced
;; no assertion at all, and a readiness check reading assertions saw an
;; empty accumulator — indistinguishable from a clean preparation. Start
;; resolved and published a cursor-0 stepper over a frame whose `:setup`
;; never ran (the rf2-k6y2 post-merge audit of PR #9252).
;;
;; The redaction is CORRECT and stays. What was wrong is reading the
;; ABSENCE of an assertion as evidence of success, so readiness now rests on
;; a privacy-safe fact the egress filter cannot erase — the operation
;; keyword of a suppressed pipeline exception, recorded at the same capture
;; boundary that drops the event.
;;
;; The classification is REAL, not injected: the variant declares
;; `:sensitive {:app-db [[:auth :password]]}`, the throwing handler is
;; path-scoped at `[:auth]`, and the router's own overlap calculation
;; (`re-frame.privacy/collect-redaction-paths` → `:schema-sensitive?` →
;; the handler scope's `:sensitive?` stamp) marks every trace event the
;; handler emits. No synthetic trace event is fed to a listener.

(def ^:private sensitive-path [:auth :password])

#?(:clj
   (defn- reg-sensitive-thrower!
     "Register an event whose interceptor chain focuses app-db at `[:auth]`
     — a PREFIX of the variant's declared sensitive path — and throws. The
     path overlap is what makes the router stamp the handler scope
     `:sensitive?`, so the pipeline-exception trace event the throw emits is
     the one Story's egress filter drops."
     [event-id]
     (rf/reg-event event-id
       {:interceptors [[:rf.interceptor/path [:auth]]]}
       (fn [_ _] (throw (ex-info "sensitive handler blew up"
                                 {:password "hunter2"}))))))

#?(:clj
   (defn- failure-records [vid]
     (filterv (comp false? :passed?) (rf.story.runtime/read-assertions vid))))

#?(:clj
   (deftest start-refuses-a-redacted-setup-failure
     (testing "a `:setup` handler that throws under a SENSITIVE path
               classification emits a pipeline exception the privacy egress
               filter suppresses, so NO assertion is recorded — yet the
               preparation still failed. Start must take the rejection
               branch on evidence the filter cannot erase (rf2-k6y2 audit
               of PR #9252)"
       (let [vid :story.stepper/sensitive-setup-throws]
         (reg-sensitive-thrower! :probe/sensitive-setup-throws)
         (rf.story/reg-variant vid
           {:sensitive {:app-db [sensitive-path]}
            :setup     [[:probe/sensitive-setup-throws]]
            :script    [[:dispatch-sync [:probe/inc-and-effect]]]})
         (let [branch (begin-outcome vid)]
           (is (empty? (failure-records vid))
               "PRECONDITION — the display path really is empty: the
                sensitive exception was redacted away, so a readiness check
                reading assertions has nothing to refuse on. Without this
                the test would be re-running section (5)'s ordinary case")
           (is (pos? (rf.story.config/suppressed-count vid))
               "PRECONDITION — and the egress filter is the reason it is
                empty, not a handler that failed to throw")
           (is (= :catch branch)
               "the redacted preparation failure rejects rather than
                resolving nil")
           (is (nil? (slot vid))
               "so Start never primed the substrate")
           (is (zero? @ext-effect-count)
               "and issued no script effect"))))))

#?(:clj
   (deftest start-refuses-a-redacted-loader-failure
     (testing "the same for a throwing `:loaders` handler under a sensitive
               classification — phase 1 drops the event at the same gate"
       (let [vid :story.stepper/sensitive-loader-throws]
         (reg-sensitive-thrower! :probe/sensitive-loader-throws)
         (rf.story/reg-variant vid
           {:sensitive {:app-db [sensitive-path]}
            :loaders   [[:probe/sensitive-loader-throws]]
            :setup     [[:counter/initialise 0]]
            :script    [[:dispatch-sync [:probe/inc-and-effect]]]})
         (let [branch (begin-outcome vid)]
           (is (empty? (failure-records vid))
               "PRECONDITION — no assertion survived the egress filter")
           (is (= :catch branch)
               "the redacted loader failure rejects")
           (is (nil? (slot vid))
               "so Start never primed the substrate")
           (is (zero? @ext-effect-count)
               "and issued no script effect"))))))

#?(:clj
   (deftest start-runs-a-healthy-sensitive-variant
     (testing "THE CONTROL THAT KEEPS THE REFUSAL HONEST. A variant that
               declares a sensitive path and emits suppressed sensitive
               events but whose preparation SUCCEEDS must still reach
               `begin-stepper!`. Readiness keys on a suppressed PIPELINE
               EXCEPTION, never on suppression itself — a debugger that
               refused every privacy-classified variant would take the tool
               away exactly where it is most wanted"
       (let [vid :story.stepper/sensitive-healthy]
         (rf/reg-event :probe/sensitive-seed
           {:interceptors [[:rf.interceptor/path [:auth]]]}
           (fn [_ _] {:db {:password "hunter2"}}))
         (rf.story/reg-variant vid
           {:sensitive {:app-db [sensitive-path]}
            :setup     [[:probe/sensitive-seed]
                        [:counter/initialise 0]]
            :script    [[:dispatch-sync [:probe/inc-and-effect]]]})
         (let [branch (begin-outcome vid)]
           (is (pos? (rf.story.config/suppressed-count vid))
               "PRECONDITION — this variant DID have sensitive events
                suppressed, so it exercises the same gate as the two
                refusals above")
           (is (= :then branch)
               "a healthy sensitive preparation still resolves")
           (is (some? (slot vid))
               "and Start primed the stepper")
           (is (= 0 (count-of vid))
               "over the :setup state, with the script still pending"))))))

#?(:clj
   (deftest a-redacted-refusal-reveals-nothing
     (testing "the refusal carries the FACT of the failure and no more: the
               rejection's data names the suppressed operation (a framework
               keyword) and never the exception message, its `ex-data`, or
               the failing event. Redaction is not weakened to make the
               debugger honest"
       (let [vid :story.stepper/sensitive-setup-throws-payload]
         (reg-sensitive-thrower! :probe/sensitive-setup-throws)
         (rf.story/reg-variant vid
           {:sensitive {:app-db [sensitive-path]}
            :setup     [[:probe/sensitive-setup-throws]]
            :script    [[:dispatch-sync [:probe/inc-and-effect]]]})
         (let [err (atom nil)]
           (-> (rf.story.runtime/prepare-variant vid)
               (rf.story.async/catch* (fn [e] (reset! err e) nil))
               (rf.story.async/deref-blocking 2000))
           (is (some? @err) "the preparation rejected")
           (let [payload (pr-str (ex-data @err))]
             (is (not (re-find #"hunter2" payload))
                 "the sensitive `ex-data` value is absent from the rejection")
             (is (not (re-find #"sensitive handler blew up" payload))
                 "so is the exception message")
             (is (re-find #"rf\.error/handler-exception" payload)
                 "while the framework operation keyword — which carries no
                  author data — IS reported, so the operator learns WHAT
                  class of failure the filter hid")))))))
