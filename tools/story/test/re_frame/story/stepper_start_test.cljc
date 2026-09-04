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

#?(:clj
   (deftest prepare-variant-rejects-on-a-failed-preparation
     (testing "an unregistered variant REJECTS the promise rather than
               resolving, so the caller returns its section to the inactive
               state instead of presenting a stepper over a frame that never
               reached :ready"
       (let [p (rf.story.runtime/prepare-variant :story.stepper/never-registered)]
         (is (thrown? java.util.concurrent.ExecutionException
                      (rf.story.async/deref-blocking p 2000)))))))
