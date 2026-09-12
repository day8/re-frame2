(ns re-frame.story.stepper-compiled-plan-test
  "rf2-499z — the step-debugger and the scrubber read the COMPILED plan,
  the program the auto-run path executes, not the raw `:script` slot.

  Execution walks the compiled plan's `[:world :scripts]`; the stepper
  (`play/variant-play-steps`) and the scrubber (`play/variant-play-events`)
  used to walk the registered body's `:script` as authored. Anything the
  compiler rewrites therefore stepped differently from how it ran: an
  `[:arg key]` placeholder reached the dispatched event verbatim, a
  `:compose`d fragment's script was missing, and a `:plays` variant (no
  `:script` slot at all) showed no steps.

  Every witness below is a variant whose raw `:script` and compiled plan
  DIFFER, and each first asserts that divergence through `raw-steps` /
  `raw-events` — the pre-fix readers reproduced verbatim — so a witness
  that stopped diverging fails its precondition instead of passing
  vacuously. The comparison target is BEHAVIOURAL: what `run-variant`
  actually did (its final app-db, its epoch tape), never a re-derivation
  of the plan expression the reader itself uses. The plain-`:script`
  case is the CONTROL, where both readers agree trivially.

  JVM-only (`.clj`): `run-variant` / `prepare-variant` settle
  synchronously here, so a stepped session and an auto-run can be driven
  back to back against one frame."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch :as rf.epoch]
            [re-frame.frame :as rf.frame]
            [re-frame.machines :as rf.machines]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story :as rf.story]
            [re-frame.story.assertions :as rf.story.assertions]
            [re-frame.story.async :as rf.story.async]
            [re-frame.story.config :as rf.story.config]
            [re-frame.story.loaders :as rf.story.loaders]
            [re-frame.story.play :as rf.story.play]
            [re-frame.story.play.runner :as rf.story.play.runner]
            [re-frame.story.play.runner-events :as rf.story.play.runner-events]
            [re-frame.story.registrar :as rf.story.registrar]
            [re-frame.story.runtime :as rf.story.runtime]
            [re-frame.story.ui.test-mode.pure :as rf.story.ui.test-mode.pure]))

(defn- reset-all! [test-fn]
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  ;; Requiring `re-frame.epoch` installs the epoch artefact's late-bind
  ;; hooks, so a run records a real `:epoch-tape` for the scrubber witness.
  (rf.epoch/clear-history!)
  (rf.epoch/clear-epoch-listeners!)
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (require 're-frame.machines :reload)
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (rf.story.config/set-global-args! {})
  (reset! rf.story.play/stepper-state {})
  (reset! rf.story.play.runner-events/run-state {})
  (reset! rf.story.play.runner-events/step-boundaries {})
  (rf.story.runtime/reset-run-owner!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (rf/reg-event :ps/set-value (fn [{:keys [db]} [_ v]] {:db (assoc db :value v)}))
  (rf/reg-event :ps/inc       (fn [{:keys [db]} _] {:db (update db :count (fnil inc 0))}))
  (rf/reg-event :ps/log       (fn [{:keys [db]} [_ x]] {:db (update db :log (fnil conj []) x)}))
  (test-fn))

(use-fixtures :each reset-all!)

;; ---- helpers -------------------------------------------------------------

(defn- raw-steps
  "The PRE-FIX stepper reader, reproduced verbatim: the registered body's
  raw `:script` slot, parsed and folded. The 'before' half of every
  witness."
  [vid]
  (let [body (rf.story.registrar/handler-meta :variant vid)]
    (rf.story.assertions/fold-script
      (vec (:script (rf.story.play.runner/parse-spec (:script body)))))))

(defn- raw-events
  "The PRE-FIX scrubber reader: the dispatch events of the raw slot."
  [vid]
  (into []
        (keep (fn [s] (when (and (vector? s)
                                 (#{:dispatch :dispatch-sync} (first s))
                                 (vector? (second s)))
                        (second s))))
        (raw-steps vid)))

(defn- auto-run
  "What the auto-run path DID: the unified result of a full run."
  ([vid] (auto-run vid nil))
  ([vid opts]
   (rf.story.async/deref-blocking (rf.story.runtime/run-variant vid opts) 5000)))

(defn- step-through!
  "What the step-debugger DOES: Start (prepare phases 0-2 with `opts`,
  prime the substrate with the same `opts`), then Step until exhausted.
  Returns the steps it ran, in order, and the final app-db."
  ([vid] (step-through! vid nil))
  ([vid opts]
   (rf.story.async/deref-blocking (rf.story.runtime/prepare-variant vid opts) 5000)
   (rf.story.play/begin-stepper! vid opts)
   (loop [n 0]
     (when (and (< n 100) (rf.story.play/step-once! vid))
       (recur (inc n))))
   {:steps  (:ran (get @rf.story.play/stepper-state vid))
    :app-db (rf/app-db-value vid)}))

;; ---- witnesses: raw and compiled DIFFER ----------------------------------

(deftest arg-placeholder-steps-the-substituted-value
  (testing "an `[:arg key]` in the script is stepped as the SUBSTITUTED
            event the auto-run dispatched, never as the placeholder"
    (let [vid :story.stepper-plan/arg]
      (rf.story/reg-variant vid
        {:args   {:value 7}
         :script [[:dispatch-sync [:ps/set-value [:arg :value]]]]})
      (is (= [[:dispatch-sync [:ps/set-value [:arg :value]]]] (raw-steps vid))
          "PRECONDITION — the raw slot carries the placeholder, so this
           witness really does diverge")
      (let [ran     (auto-run vid)
            stepped (step-through! vid)]
        (is (= 7 (get-in ran [:app-db :value]))
            "the auto-run dispatched the substituted value")
        (is (= [[:dispatch-sync [:ps/set-value 7]]] (:steps stepped))
            "the stepper walked the substituted event")
        (is (= 7 (get-in stepped [:app-db :value]))
            "stepping dispatched the substituted value, so the placeholder
             never reached the handler")))))

(deftest plays-variant-steps-its-auto-run-plays
  (testing "a `:plays` variant has no `:script` slot, so the raw reader saw
            no steps at all; the stepper now walks every AUTO-RUNNABLE play
            in order, and not a play that does not auto-run"
    (let [vid :story.stepper-plan/plays]
      (rf.story/reg-variant vid
        {:plays [{:name   "first"
                  :script [[:dispatch-sync [:ps/log :first]]]}
                 {:name      "second"
                  :auto-run? true
                  :script    [[:dispatch-sync [:ps/log :second-a]]
                              [:dispatch-sync [:ps/log :second-b]]]}
                 {:name   "manual"
                  :script [[:dispatch-sync [:ps/log :manual]]]}]})
      (is (= [] (raw-steps vid))
          "PRECONDITION — the raw reader saw nothing: 'no steps'")
      (let [ran     (auto-run vid)
            stepped (step-through! vid)]
        (is (= [:first :second-a :second-b] (get-in ran [:app-db :log]))
            "the auto-run ran the two auto-runnable plays, not the manual one")
        (is (= 3 (count (:steps stepped)))
            "the stepper walked three steps")
        (is (= (get-in ran [:app-db :log]) (get-in stepped [:app-db :log]))
            "and they are the steps the auto-run executed, in its order")))))

(deftest composed-fragment-script-is-stepped-first
  (testing "a `:compose`d fragment's script runs BEFORE the variant's own;
            the raw reader missed it, the stepper now walks it"
    (let [vid :story.stepper-plan/composed]
      (rf.story/reg-fragment :fragment.stepper-plan/opener
        {:script [[:dispatch-sync [:ps/log :fragment]]]})
      (rf.story/reg-variant vid
        {:compose [:fragment.stepper-plan/opener]
         :script  [[:dispatch-sync [:ps/log :own]]]})
      (is (= [[:dispatch-sync [:ps/log :own]]] (raw-steps vid))
          "PRECONDITION — the raw reader saw only the variant's own step")
      (let [ran     (auto-run vid)
            stepped (step-through! vid)]
        (is (= [:fragment :own] (get-in ran [:app-db :log]))
            "the auto-run ran the fragment's script first")
        (is (= [[:dispatch-sync [:ps/log :fragment]]
                [:dispatch-sync [:ps/log :own]]]
               (:steps stepped))
            "the stepper walked the fragment's step, then the variant's")
        (is (= (get-in ran [:app-db :log]) (get-in stepped [:app-db :log]))
            "so the stepped run and the auto-run agree")))))

(deftest scrubber-events-align-with-the-run-tape
  (testing "the scrubber aligns its play events against the run's OWN epoch
            tape by trigger-event identity; the raw placeholder matched no
            tape record, so the scrubber had no epochs to offer"
    (let [vid :story.stepper-plan/scrub]
      (rf.story/reg-variant vid
        {:args   {:value 7}
         :script [[:dispatch-sync [:ps/set-value [:arg :value]]]
                  [:dispatch-sync [:ps/inc]]]})
      (let [tape (:epoch-tape (auto-run vid))
            ids  (rf.story.ui.test-mode.pure/epoch-id-slice
                   tape (rf.story.play/variant-play-events vid))]
        (is (seq tape) "PRECONDITION — the run recorded an epoch tape")
        (is (= [] (rf.story.ui.test-mode.pure/epoch-id-slice tape (raw-events vid)))
            "PRECONDITION — the raw events matched no record: the scrubber
             was empty for this variant")
        (is (= [[:ps/set-value 7] [:ps/inc]] (rf.story.play/variant-play-events vid))
            "the scrubber's events are the substituted ones")
        (is (= [[:ps/set-value 7] [:ps/inc]]
               (mapv (fn [id] (:trigger-event (first (filter #(= id (:epoch-id %)) tape))))
                     ids))
            "each event resolves to the epoch that event actually committed")))))

;; ---- the control: raw and compiled COINCIDE ------------------------------

(deftest plain-script-control-both-readers-agree
  (testing "CONTROL — a plain `:script` with no `[:arg]`, `:compose` or
            `:plays`: raw and compiled coincide, so the new readers agree
            with the old ones (bare-vector coercion and `:assert-db`
            folding included)"
    (let [vid :story.stepper-plan/plain]
      (rf.story/reg-variant vid
        {:script [[:dispatch-sync [:ps/inc]]
                  [:ps/inc]
                  [:assert-db [:count] 2]]})
      (is (= (raw-steps vid) (rf.story.play/variant-play-steps vid)))
      (is (= (raw-events vid) (rf.story.play/variant-play-events vid)))
      (is (= 2 (get-in (auto-run vid) [:app-db :count])))
      (is (= 2 (get-in (step-through! vid) [:app-db :count]))))))

;; ---- run opts, the no-auto-run fallback, and the edges -------------------

(deftest run-opts-thread-into-the-stepped-program
  (testing "the stepped program substitutes the SAME arg layers the run
            used: a cell override reaches the steps through `opts`, exactly
            as `run-variant` threads it into its own compile"
    (let [vid  :story.stepper-plan/opts
          opts {:cell-overrides {:value "override"}}]
      (rf.story/reg-variant vid
        {:args   {:value "static"}
         :script [[:dispatch-sync [:ps/set-value [:arg :value]]]]})
      (is (= [[:ps/set-value "static"]] (rf.story.play/variant-play-events vid)))
      (is (= [[:ps/set-value "override"]] (rf.story.play/variant-play-events vid opts)))
      (is (= "override" (get-in (auto-run vid opts) [:app-db :value])))
      (is (= "override" (get-in (step-through! vid opts) [:app-db :value]))))))

(deftest mode-only-arg-degrades-the-scrubber-instead-of-throwing
  (testing "an `[:arg]` that only an active mode supplies cannot compile
            without that mode. The scrubber's reader yields [] (its 'no
            epoch buffer' degrade) rather than throwing out of the handler
            that stores the run result, and resolves once given the opts"
    (let [vid  :story.stepper-plan/mode-only
          mode :Mode.stepper-plan/seven]
      (rf.story/reg-mode mode {:args {:value 7}})
      (rf.story/reg-variant vid
        {:script [[:dispatch-sync [:ps/set-value [:arg :value]]]]})
      (is (thrown? clojure.lang.ExceptionInfo (rf.story.play/variant-play-steps vid))
          "PRECONDITION — without the mode the plan really does refuse")
      (is (= [] (rf.story.play/variant-play-events vid)))
      (is (= [[:ps/set-value 7]]
             (rf.story.play/variant-play-events vid {:active-modes [mode]}))))))

(deftest opted-out-script-is-still-steppable
  (testing "a script with `:auto-run? false` auto-runs nothing, so the
            stepper falls back to the primary compiled play and the author
            can still step it by hand — substituted like every other read"
    (let [vid :story.stepper-plan/manual]
      (rf.story/reg-variant vid
        {:args   {:value 3}
         :script {:auto-run? false
                  :script    [[:dispatch-sync [:ps/set-value [:arg :value]]]]}})
      (is (nil? (get-in (auto-run vid) [:app-db :value]))
          "nothing auto-ran")
      (is (= [[:dispatch-sync [:ps/set-value 3]]] (:steps (step-through! vid)))
          "the stepper walked the primary play, substituted"))))

(deftest unregistered-variant-has-no-steps
  (testing "an unregistered id keeps the pre-fix contract — no steps and
            no throw — so `begin-stepper!` on a bare frame seeds an empty
            cursor"
    (is (= [] (rf.story.play/variant-play-steps :story.stepper-plan/never)))
    (is (= [] (rf.story.play/variant-play-events :story.stepper-plan/never)))))
