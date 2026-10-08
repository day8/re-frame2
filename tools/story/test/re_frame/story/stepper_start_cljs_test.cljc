(ns re-frame.story.stepper-start-cljs-test
  "The step-debugger's Start: cursor 0 IS the pre-play state
  (`009-Test-Mode.md` §Play step-debugger). `stepper-state/begin!` reaches it
  through `runtime/prepare-variant` (phases 0-2 only); reaching it through
  `reset-variant` would run the whole script before cursor 0 is shown. These
  tests drive the same composition `begin!` performs — the runtime seam,
  then `play/begin-stepper!`, then `play/step-once!` — and assert the
  sequence the debugger observes, since the script's end state is the same
  either way.

  The pre-play rows run on both runtimes (phases 0-2 and `:dispatch-sync`
  steps settle synchronously); the rows that block on `begin!`'s promise are
  JVM-only. Named `-cljs-test` so the `:node-test` build selects it."
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

;; An external effect cannot be un-sent, so this counter witnesses script
;; work that an app-db reset would hide.
(def ^:private ext-effect-count (atom 0))

(defn- reset-all! [test-fn]
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  ;; `re-frame.epoch` records the tape `rf/restore-epoch!` travels back on;
  ;; clear it so each test reads only its own epochs.
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

(defn- reg-mutating!
  "A default-auto-run variant: `:setup` seeds `:count` 0 and a bare
  three-step `:script` increments it, issuing the external effect each time."
  [vid]
  (rf.story/reg-variant vid
    {:setup  [[:counter/initialise 0]]
     :script [[:dispatch-sync [:probe/inc-and-effect]]
              [:dispatch-sync [:probe/inc-and-effect]]
              [:dispatch-sync [:probe/inc-and-effect]]]}))

(defn- start!
  "The runtime half of `stepper-state/begin!`."
  [vid]
  (rf.story.runtime/prepare-variant vid)
  (rf.story.play/begin-stepper! vid))

(defn- count-of [vid] (:count (rf/app-db-value vid)))

(defn- slot [vid] (get @rf.story.play/stepper-state vid))

(deftest the-debugger-observes-every-step-exactly-once
  (let [vid :story.stepper/mutating]
    (reg-mutating! vid)
    (start! vid)
    (let [trajectory (into [(count-of vid)]
                           (mapv (fn [_]
                                   (rf.story.play/step-once! vid)
                                   (count-of vid))
                                 (range 3)))]
      (is (= [0 1 2 3] trajectory) "step N moves :count from N-1 to N")
      (is (= 3 @ext-effect-count) "each script effect was issued exactly once")
      (is (= (rf.story.play/variant-play-steps vid) (:ran (slot vid))))
      (is (= [] (:remaining (slot vid)))))))

(deftest rewind-restores-the-setup-state
  (let [vid :story.stepper/mutating]
    (reg-mutating! vid)
    (start! vid)
    (let [pre-play (-> (rf/epoch-history vid) last :epoch-id)]
      (dotimes [_ 3] (rf.story.play/step-once! vid))
      (is (= 3 (count-of vid)) "precondition: the stepped run reached the end")
      (rf/restore-epoch! vid pre-play)
      (rf.story.play/stepper-rewind! vid)
      (is (= 0 (count-of vid)) "the bottom epoch is the :setup state, not the post-script one")
      (is (= 3 (count (:remaining (slot vid)))) "and every step is pending again"))))

(deftest reset-variant-still-runs-the-whole-script
  (testing "only Start uses the pre-play seam; `reset-variant` (the Re-run
            button) runs the script"
    (let [vid :story.stepper/mutating]
      (reg-mutating! vid)
      (rf.story.runtime/reset-variant vid)
      (is (= [3 3] [(count-of vid) @ext-effect-count])))))

;; ---- a failed preparation rejects ----------------------------------------
;;
;; `begin!`'s only failure branch is the promise's rejection, so
;; `prepare-variant` must reject on every failed preparation. A throwing
;; `:loaders` / `:setup` handler is captured onto `[:rf.story/assertions]`
;; rather than thrown, and under a sensitive classification the egress filter
;; drops even that record — so readiness rests on the redacted operation
;; keyword the capture boundary keeps.

#?(:clj
   (defn- begin-outcome
     "Drive `begin!`'s composition — `prepare-variant`, then `begin-stepper!`
     only on the resolve path — and report which branch ran."
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
     (let [p (rf.story.runtime/prepare-variant :story.stepper/never-registered)]
       (is (thrown? java.util.concurrent.ExecutionException
                    (rf.story.async/deref-blocking p 2000))))))

#?(:clj (def ^:private sensitive-path [:auth :password]))

#?(:clj
   (defn- reg-sensitive-thrower!
     "An event focused at `[:auth]`, a prefix of the variant's sensitive path,
     so the router stamps its pipeline exception `:sensitive?`."
     [event-id]
     (rf/reg-event event-id
       {:interceptors [[:rf.interceptor/path [:auth]]]}
       (fn [_ _] (throw (ex-info "sensitive handler blew up"
                                 {:password "hunter2"}))))))

#?(:clj
   (defn- failure-records [vid]
     (filterv (comp false? :passed?) (rf.story.runtime/read-assertions vid))))

#?(:clj
   (deftest start-refuses-a-failed-preparation
     (rf/reg-event :probe/throws (fn [_ _] (throw (ex-info "blew up" {:probe true}))))
     (reg-sensitive-thrower! :probe/sensitive-throws)
     (doseq [[vid body redacted?]
             [[:story.stepper/setup-throws {:setup [[:probe/throws]]} false]
              [:story.stepper/loader-throws {:loaders [[:probe/throws]]
                                             :setup   [[:counter/initialise 0]]} false]
              [:story.stepper/sensitive-setup-throws {:sensitive {:app-db [sensitive-path]}
                                                      :setup     [[:probe/sensitive-throws]]} true]
              [:story.stepper/sensitive-loader-throws {:sensitive {:app-db [sensitive-path]}
                                                       :loaders   [[:probe/sensitive-throws]]
                                                       :setup     [[:counter/initialise 0]]} true]]]
       (rf.story/reg-variant vid (assoc body :script [[:dispatch-sync [:probe/inc-and-effect]]]))
       (testing (str vid)
         (is (= :catch (begin-outcome vid)) "the failed preparation rejects")
         (is (nil? (slot vid)) "so Start never primed the substrate")
         (is (zero? @ext-effect-count) "and issued no script effect")
         (is (= redacted? (empty? (failure-records vid)))
             "a captured failure stays on the frame; a redacted one leaves none")
         (when redacted?
           (is (pos? (rf.story.config/suppressed-count vid))
               "precondition: the egress filter is why no record survived"))))))

#?(:clj
   (deftest start-runs-a-healthy-sensitive-variant
     (testing "suppressed sensitive events alone never refuse: only a
               suppressed pipeline exception does"
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
               "precondition: this preparation did suppress sensitive events")
           (is (= :then branch))
           (is (some? (slot vid)) "Start primed the stepper")
           (is (= 0 (count-of vid)) "over the :setup state, script pending"))))))

#?(:clj
   (deftest a-redacted-refusal-reveals-nothing
     (testing "the rejection names the suppressed framework operation and
               never the exception message or its ex-data"
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
           (let [payload (pr-str (ex-data @err))]
             (is (not (re-find #"hunter2" payload)))
             (is (not (re-find #"sensitive handler blew up" payload)))
             (is (re-find #"rf\.error/handler-exception" payload))))))))

;; ---- Start keeps the run's inputs for the play chip's Re-run --------------
;;
;; Start retires the run owner's attempt so nothing resumes the script under
;; the stepper, but must keep the opts the owner was prepared with: the chip's
;; Re-run is `runtime/rerun!`, which re-prepares with them.

#?(:clj
   (deftest the-play-chip-re-run-after-start-runs-with-the-canvas-opts
     (let [vid         :story.stepper/chip-rerun
           run-key     {:variant-id vid :cell-overrides {:value 11}}
           ;; what the canvas prepares the run owner with (`ui.canvas/run-opts`)
           canvas-opts {:active-modes   nil
                        :cell-overrides {:value 11}
                        :substrate      nil
                        :runner         :auto
                        :run-key        run-key}
           ;; what Start prepares the frame with (`test-mode.state/run-opts`)
           start-opts  {:active-modes nil :cell-overrides {:value 11} :substrate nil}
           prepared    (atom [])]
       (rf/reg-event :probe/set-value
         (fn [{:keys [db]} [_ v]] {:db (assoc db :value v)}))
       (rf.story/reg-variant vid
         {:args   {:value 7}
          :script [[:dispatch-sync [:probe/set-value [:arg :value]]]
                   [:assert-dom "#probe" :visible]]})
       (rf.story.runtime/listen-runs! ::chip-rerun
         (fn [v run]
           (when (and (= vid v) (not (contains? run :result)))
             (swap! prepared conj (:run-key run)))))
       (try
         (rf.story.runtime/prepare-run! vid canvas-opts)
         (let [canvas-run (rf.story.async/deref-blocking
                            (rf.story.runtime/resume-run! vid) 5000)]
           (is (= :dom (:runner canvas-run))
               "precondition: :runner :auto takes the canvas run past :headless")
           (rf.story.async/deref-blocking
             (rf.story.runtime/prepare-variant vid start-opts) 5000)
           (reset! prepared [])
           (let [chip-run (rf.story.async/deref-blocking
                            (rf.story.runtime/rerun! vid {:play nil}) 5000)]
             (is (= [run-key] @prepared) "the chip's Re-run prepares under the canvas's run-key")
             (is (= :dom (:runner chip-run)) "with the canvas run's runner")
             (is (= 11 (:value (rf/app-db-value vid))) "and its Controls override")))
         (finally
           (rf.story.runtime/listen-runs! ::chip-rerun nil))))))
