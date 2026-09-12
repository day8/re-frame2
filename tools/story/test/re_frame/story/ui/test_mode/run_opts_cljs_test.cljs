(ns re-frame.story.ui.test-mode.run-opts-cljs-test
  "rf2-ad25 — the `:test` pane's scrubber and step-debugger read the
  program compiled against the SAME run opts Re-run uses.

  rf2-499z gave `play/variant-play-events`, `play/variant-play-steps` and
  `play/begin-stepper!` an `opts` arity (the `run-variant` opts map), folded
  into the plan compile exactly as the runtime folds it. The pane's call
  sites still used the one-argument forms, so a script `[:arg]` fed by an
  active mode or a cell override compiled there against the variant's
  STATIC args while the run itself used the moded / overridden ones:

  - the scrubber's events never matched the run's epoch tape, so
    `epoch-id-slice` returned [] and the scrubber showed no epochs;
  - the step-debugger stepped the static value — a different program from
    the one Re-run and the canvas execute — or, for an arg only a mode
    supplies, could not compile the plan and refused to start.

  Each witness compares against what `run-variant-pane!` actually DID (the
  run result's app-db and epoch tape), never against a re-derivation of the
  reader. The no-mode, no-override variant is the CONTROL: static and run
  args coincide there, so it is green before and after.

  The reader's `opts` arity itself is pinned on the JVM by
  `re-frame.story.stepper-compiled-plan-test`
  (`run-opts-thread-into-the-stepped-program`); this suite pins the pane
  call sites that feed it."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.core :as rf]
            [re-frame.epoch :as rf.epoch]
            [re-frame.frame :as rf.frame]
            [re-frame.machines :as rf.machines]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story :as rf.story]
            [re-frame.story.async :as rf.story.async]
            [re-frame.story.loaders :as rf.story.loaders]
            [re-frame.story.play :as rf.story.play]
            [re-frame.story.play.runner-events :as rf.story.play.runner-events]
            [re-frame.story.runtime :as rf.story.runtime]
            [re-frame.story.ui.state :as rf.story.ui.state]
            [re-frame.story.ui.test-mode.state :as rf.story.ui.test-mode.state]
            [re-frame.story.ui.test-mode.stepper-state :as rf.story.ui.test-mode.stepper-state]
            [re-frame.subs :as rf.subs]))

;; ---- fixtures ------------------------------------------------------------

(defn- reset-all! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  ;; Requiring `re-frame.epoch` installs the epoch artefact's late-bind
  ;; hooks, so a run records the real `:epoch-tape` the scrubber aligns
  ;; against. Clear it so each test reads only its own epochs.
  (rf.epoch/clear-history!)
  (rf.epoch/clear-epoch-listeners!)
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch :default _ nil))
  (rf.subs/reg-runtime-sub :rf/machine
    (fn [runtime-db [_ machine-id]]
      (get-in runtime-db [:rf.runtime/machines :snapshots machine-id])))
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (reset! rf.story.play/stepper-state {})
  (reset! rf.story.play.runner-events/run-state {})
  (reset! rf.story.play.runner-events/step-boundaries {})
  (rf.story.runtime/reset-run-owner!)
  (reset! rf.story.ui.test-mode.state/results-atom {})
  (reset! rf.story.ui.test-mode.stepper-state/results-atom {})
  (rf.story.ui.state/reset-shell-state!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (rf/reg-event :tmo/set-value (fn [{:keys [db]} [_ v]] {:db (assoc db :value v)})))

(use-fixtures :each {:before reset-all!})

;; ---- helpers -------------------------------------------------------------

(defn- reg-arg-variant!
  "A variant whose one script step dispatches its `:value` arg, statically
  \"static\". A cell override sits ABOVE the variant layer, so it shows in
  the dispatched event and a reader compiled without it disagrees."
  [vid]
  (rf.story/reg-variant vid
    {:args   {:value "static"}
     :script [[:dispatch-sync [:tmo/set-value [:arg :value]]]]}))

(defn- re-run!
  "What Re-run DOES: `run-variant-pane!`, resolving to the slot it stored."
  [vid]
  (rf.story.async/then (rf.story.ui.test-mode.state/run-variant-pane! vid)
                       (fn [_] (get @rf.story.ui.test-mode.state/results-atom vid))))

(defn- scrubbed-events
  "The trigger event of each epoch the scrubber offers, in tick order."
  [slot]
  (let [tape (get-in slot [:result :epoch-tape])]
    (mapv (fn [id] (:trigger-event (first (filter #(= id (:epoch-id %)) tape))))
          (:epoch-ids slot))))

(defn- start-and-step!
  "What the step-debugger DOES: Start (`begin!`, the one-argument form the
  Start button calls), then Step once per listed step. Resolves to the slot
  Start published and the frame's app-db after the last step."
  [vid]
  (rf.story.async/then
    (rf.story.ui.test-mode.stepper-state/begin! vid)
    (fn [_]
      (let [slot (get @rf.story.ui.test-mode.stepper-state/results-atom vid)]
        (dotimes [_ (or (:total slot) 0)]
          (rf.story.ui.test-mode.stepper-state/step! vid))
        {:slot slot :app-db (rf/app-db-value vid)}))))

(defn- finish! [vid done]
  (rf.story.ui.test-mode.stepper-state/end! vid)
  (rf.story/destroy-variant! vid)
  (done))

(defn- fail-on-reject [vid done]
  (fn [e]
    (is (nil? e) "the promise chain rejected")
    (finish! vid done)))

;; ---- witnesses -----------------------------------------------------------

(deftest scrubber-reads-the-cell-override-the-run-used
  (testing "a controls-panel override feeding a script `[:arg]`: the
            scrubber's events are compiled against the args Re-run used, so
            every tick resolves to the epoch the run committed"
    (let [vid :story.run-opts/scrubbed]
      (reg-arg-variant! vid)
      (rf.story.ui.state/swap-state! assoc-in [:cell-overrides vid] {:value "override"})
      (async done
        (-> (re-run! vid)
            (rf.story.async/then
              (fn [slot]
                (is (= "override" (get-in slot [:result :app-db :value]))
                    "PRECONDITION — Re-run ran with the controls-panel override")
                (is (= [[:tmo/set-value "override"]] (:play-events slot))
                    "the scrubber's events are the ones the run dispatched")
                (is (= [[:tmo/set-value "override"]] (scrubbed-events slot))
                    "each tick resolves to the epoch that event committed; read
                     against the static args, `epoch-id-slice` matched nothing
                     and the scrubber offered no epochs")
                (finish! vid done)))
            (rf.story.async/catch* (fail-on-reject vid done)))))))

(deftest stepper-steps-the-program-re-run-executed
  (testing "a controls-panel override feeding a script `[:arg]`: Start
            prepares and lists the program compiled against the args Re-run
            used, so stepping it to the end reaches the app-db Re-run reached"
    (let [vid :story.run-opts/stepped]
      (reg-arg-variant! vid)
      (rf.story.ui.state/swap-state! assoc-in [:cell-overrides vid] {:value "override"})
      (async done
        (-> (re-run! vid)
            (rf.story.async/then
              (fn [ran]
                (rf.story.async/then
                  (start-and-step! vid)
                  (fn [{:keys [slot app-db]}]
                    (is (= "override" (get-in ran [:result :app-db :value]))
                        "PRECONDITION — Re-run ran with the controls-panel override")
                    (is (= [[:dispatch-sync [:tmo/set-value "override"]]] (:play-steps slot))
                        "the step list is the program Re-run executed, not the
                         static-args one")
                    (is (= "override" (:value app-db))
                        "stepping to the end reaches the app-db Re-run reached")
                    (finish! vid done)))))
            (rf.story.async/catch* (fail-on-reject vid done)))))))

(deftest mode-only-arg-reaches-the-scrubber-and-the-stepper
  (testing "an `[:arg]` only an active mode supplies. Modes sit BELOW the
            variant's own `:args` (`rf.story.args/run-arg-layers` `:pre`), so
            this variant declares no `:value` of its own. Compiled without the
            run's opts the plan cannot resolve the arg at all: the scrubber
            degraded to no events and Start refused to start. With them both
            read the program Re-run executed"
    (let [vid  :story.run-opts/moded
          mode :Mode.run-opts/moded]
      (rf.story/reg-mode mode {:args {:value "moded"}})
      (rf.story/reg-variant vid
        {:script [[:dispatch-sync [:tmo/set-value [:arg :value]]]]})
      (rf.story.ui.state/swap-state! rf.story.ui.state/set-active-modes [mode])
      (async done
        (-> (re-run! vid)
            (rf.story.async/then
              (fn [ran]
                (is (= "moded" (get-in ran [:result :app-db :value]))
                    "PRECONDITION — Re-run ran with the active mode")
                (is (= [[:tmo/set-value "moded"]] (scrubbed-events ran))
                    "the scrubber offers the epoch the moded event committed")
                (rf.story.async/then
                  (start-and-step! vid)
                  (fn [{:keys [slot]}]
                    (is (:active? slot)
                        "Start prepared the frame and published a stepper")
                    (is (= [[:dispatch-sync [:tmo/set-value "moded"]]] (:play-steps slot))
                        "the step list is the moded program Re-run executed")
                    (finish! vid done)))))
            (rf.story.async/catch* (fail-on-reject vid done)))))))

;; ---- the control ---------------------------------------------------------

(deftest control-no-mode-no-override-agrees
  (testing "CONTROL — no active mode and no override: the static args ARE
            the run args, so the scrubber and the stepper agree with Re-run
            on either side of the fix"
    (let [vid :story.run-opts/plain]
      (reg-arg-variant! vid)
      (async done
        (-> (re-run! vid)
            (rf.story.async/then
              (fn [ran]
                (is (= "static" (get-in ran [:result :app-db :value])))
                (is (= [[:tmo/set-value "static"]] (:play-events ran)))
                (is (= [[:tmo/set-value "static"]] (scrubbed-events ran))
                    "the scrubber has its epoch")
                (rf.story.async/then
                  (start-and-step! vid)
                  (fn [{:keys [slot app-db]}]
                    (is (= [[:dispatch-sync [:tmo/set-value "static"]]] (:play-steps slot)))
                    (is (= "static" (:value app-db)))
                    (finish! vid done)))))
            (rf.story.async/catch* (fail-on-reject vid done)))))))
