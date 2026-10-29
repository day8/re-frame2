(ns re-frame.story.run-variant-owner-cljs-test
  "The headless `run-variant` runs through the one run owner
  (`003-Render-Shell.md` §One run owner): it claims a generation before it
  touches the frame, so a canvas generation prepared on the same frame can
  no longer resume over it, and it announces the generation and its
  settlement, so every display following `listen-runs!` sees a Run all or
  a watch re-run. The step-debugger's Start retires the current run and
  announces the retirement.

  The scripts are pure `:dispatch-sync`; the settle announcement is read
  on the JVM, where the run settles before `run-variant` returns. Named
  `-cljs-test` so the `:node-test` build selects it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.epoch]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines :as rf.machines]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story :as rf.story]
            [re-frame.story.loaders :as rf.story.loaders]
            [re-frame.story.play :as rf.story.play]
            [re-frame.story.play.runner-events :as rf.story.play.runner-events]
            [re-frame.story.runtime :as rf.story.runtime]
            #?@(:clj [[re-frame.story.async :as rf.story.async]])))

(def ^:private announced (atom []))

(defn- reset-all! [test-fn]
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ nil))
  #?(:clj (require 're-frame.machines :reload))
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (reset! rf.story.play/stepper-state {})
  (reset! rf.story.play.runner-events/run-state {})
  (reset! rf.story.play.runner-events/step-boundaries {})
  (rf.story.runtime/reset-run-owner!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (reset! announced [])
  (rf.story.runtime/listen-runs! ::probe (fn [vid run] (swap! announced conj [vid run])))
  (rf/reg-event :probe/inc (fn [{:keys [db]} _] {:db (update db :count (fnil inc 0))}))
  (rf/reg-event :counter/initialise (fn [{:keys [db]} [_ n]] {:db (assoc db :count (or n 0))}))
  (try (test-fn)
       (finally (rf.story.runtime/listen-runs! ::probe nil))))

(use-fixtures :each reset-all!)

(defn- reg-counter! [vid]
  (rf.story/reg-variant vid
    {:setup  [[:counter/initialise 0]]
     :script [[:dispatch-sync [:probe/inc]]
              [:dispatch-sync [:rf.assert/path-equals [:count] 1]]]}))

(defn- prepare! [vid tag]
  (rf.story.runtime/prepare-run! vid {:run-key {:variant-id vid :cell-overrides tag}}))

(defn- starts [vid]
  (into [] (comp (filter #(= vid (first %))) (map second) (remove #(contains? % :result)))
        @announced))

(deftest run-variant-claims-and-announces-a-generation
  (let [vid :story.headless/claims]
    (reg-counter! vid)
    (let [p (rf.story.runtime/run-variant vid)]
      (is (= 1 (rf.story.runtime/current-generation vid))
          "the headless run claimed the variant's generation")
      (is (= [{:run-key nil :generation 1}] (starts vid))
          "and announced it to listen-runs!")
      #?(:clj
         (let [result (rf.story.async/deref-blocking p 5000)]
           (is (= :pass (:status result)))
           (is (= [[vid {:run-key nil :generation 1 :result result}]]
                  (filterv #(contains? (second %) :result) @announced))
               "its settlement is announced with the result it returned"))
         :cljs
         (is (some? p) "run-variant returns its promise")))))

(deftest run-variant-supersedes-a-pending-canvas-generation
  (testing "a canvas generation prepared but not yet resumed can no longer
            resume once a headless run has claimed the frame, so two scripts
            never drive one frame; the owner's run inputs stay, so the canvas's
            Re-run and its dedupe still read them"
    (let [vid :story.headless/supersedes]
      (reg-counter! vid)
      (prepare! vid :a)
      (rf.story.runtime/run-variant vid)
      (is (= 2 (rf.story.runtime/current-generation vid)))
      (is (nil? (rf.story.runtime/resume-run! vid))
          "the canvas's pending generation does not resume")
      (is (= {:run-key {:variant-id vid :cell-overrides :a} :generation 2}
             (peek (starts vid)))
          "the headless run is announced under the canvas's run-key"))))

(deftest stepper-start-announces-a-retired-generation
  (testing "the step-debugger's Start claims a generation nothing resumes and
            announces it as retired, so a display marking the ended run as
            running can clear the mark"
    (let [vid :story.headless/retired]
      (reg-counter! vid)
      (prepare! vid :a)
      (rf.story.runtime/prepare-variant vid)
      (is (= {:run-key {:variant-id vid :cell-overrides :a} :generation 2 :retired? true}
             (peek (starts vid)))))))

;; Phase 4's promise is held by redefining `run-phase-4!`, so a late
;; rejection lands exactly when the test chooses.

#?(:clj
   (defn- frame-state [vid]
     [(rf.story.loaders/current-state vid) (:rf.story/assertions (rf/app-db-value vid))]))

#?(:clj
   (deftest a-superseded-run-failing-late-leaves-its-successor-alone
     (testing "run A's phase-4 promise is held while run B supersedes it and
               finishes; A's late rejection settles A as superseded and records
               nothing on the frame B now owns"
       (let [vid     :story.headless/late-failure
             phase-4 @#'rf.story.runtime/run-phase-4!
             held    (java.util.concurrent.CompletableFuture.)
             calls   (atom 0)]
         (rf.story/reg-variant vid {})
         (with-redefs-fn {#'rf.story.runtime/run-phase-4!
                          (fn [ctx] (if (= 1 (swap! calls inc)) [ctx held] (phase-4 ctx)))}
           (fn []
             (let [a      (rf.story.runtime/run-variant vid)
                   b      (rf.story.async/deref-blocking (rf.story.runtime/run-variant vid) 5000)
                   before (frame-state vid)]
               (.completeExceptionally held (ex-info "stale run rejected" {}))
               (is (= [:cannot-run true 1]
                      ((juxt :status :superseded? :generation) (rf.story.async/deref-blocking a 5000))))
               (is (= [:pass [:ready nil]] [(:status b) before]) "control: B finished")
               (is (= before (frame-state vid))
                   "A's late failure leaves B's frame as B finished it"))))))))

#?(:clj
   (deftest a-current-run-failing-late-records-its-error
     (testing "control: the same late rejection on the CURRENT generation
               records the error and moves the frame to :error"
       (let [vid  :story.headless/current-failure
             held (java.util.concurrent.CompletableFuture.)]
         (rf.story/reg-variant vid {})
         (with-redefs-fn {#'rf.story.runtime/run-phase-4! (fn [ctx] [ctx held])}
           (fn []
             (let [p (rf.story.runtime/run-variant vid)]
               (.completeExceptionally held (ex-info "current run rejected" {}))
               (is (= :error (:status (rf.story.async/deref-blocking p 5000))))
               (is (= [:error [:rf.error/exception]]
                      (update (frame-state vid) 1 #(mapv :assertion %)))))))))))
