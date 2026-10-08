(ns re-frame.story.run-owner-cljs-test
  "Story's ONE run owner. A focused shell selection reaches it from three
  places (selection-edge preallocation, canvas mount, post-commit resume);
  the script must still execute exactly once. These tests drive the
  production ownership sequence against the real plain-atom adapter,
  lifecycle machine, plan compiler and runner.

  The scripts are pure `:dispatch-sync`, so app-db and the effect counter
  have settled when `resume-run!` returns, on both runtimes; the tests that
  block on a promise or a thread are JVM-only. Named `-cljs-test` so the
  `:node-test` build selects it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            ;; Records the live tape `:rf.assert/dispatched?` reads.
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
            #?@(:clj [[re-frame.story.async :as rf.story.async]
                      [re-frame.story.config :as rf.story.config]
                      [re-frame.story.recorder.play-export-events
                       :as rf.story.recorder.play-export-events]])))

;; An external effect cannot be un-sent, so this counter witnesses how many
;; times the script ran even where an app-db reset hides it.
(def ^:private ext-effect-count (atom 0))

(defn- reset-all! [test-fn]
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
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

(defn- reg-cumulative!
  "Seed 0, increment three times (each issuing the external effect), assert 3."
  [vid]
  (rf.story/reg-variant vid
    {:setup  [[:counter/initialise 0]]
     :script [[:dispatch-sync [:probe/inc-and-effect]]
              [:dispatch-sync [:probe/inc-and-effect]]
              [:dispatch-sync [:probe/inc-and-effect]]
              [:dispatch-sync [:rf.assert/path-equals [:count] 3]]]}))

(defn- prepare! [vid tag]
  (rf.story.runtime/prepare-run! vid {:run-key {:variant-id vid :cell-overrides tag}}))

(defn- resume! [vid] (rf.story.runtime/resume-run! vid))

(defn- count-of [vid] (:count (rf/app-db-value vid)))

(defn- state
  "`[count effects generation]` for `vid`."
  [vid]
  [(count-of vid) @ext-effect-count (rf.story.runtime/current-generation vid)])

(defn- passing-assertions [vid]
  (filterv #(true? (:passed? %))
           (:rf.story/assertions (rf/app-db-value vid))))

(deftest shell-auto-play-executes-exactly-once
  (let [vid :story.owner/cumulative]
    (reg-cumulative! vid)
    (prepare! vid :a)
    (is (= [0 0 1] (state vid)) "prepare ran setup but not the script")
    (is (= :ready (rf.story.loaders/current-state vid)) "the frame is renderable after prepare")
    (prepare! vid :a)
    (is (= [0 0 1] (state vid)) "the canvas re-prepare for the same run-key dedupes")
    (resume! vid)
    (is (= [3 3 1] (state vid)) "the script ran once")
    (is (nil? (rf.story.runtime/resume-run! vid)) "a second resume for the generation is a no-op")
    (is (= [3 3 1] (state vid)))
    (is (= 1 (count (passing-assertions vid))) "exactly one assertion record")))

(deftest remount-after-resume-bumps-and-reruns-once
  (testing "a re-prepare for the same run-key after the generation was
            resumed (a React remount) claims a fresh generation and runs the
            script once more"
    (let [vid :story.owner/remount]
      (reg-cumulative! vid)
      (prepare! vid :a)
      (resume! vid)
      (prepare! vid :a)
      (is (= [0 3 2] (state vid)) "a fresh generation reset the frame")
      (resume! vid)
      (is (= [3 6 2] (state vid)) "exactly one more run"))))

(deftest two-variants-run-concurrently-and-isolated
  (testing "ownership is per variant, not one global lock"
    (let [a :story.owner/a
          b :story.owner/b]
      (reg-cumulative! a)
      (reg-cumulative! b)
      (prepare! a :a)
      (prepare! b :a)
      (resume! a)
      (is (= [3 0] [(count-of a) (count-of b)]) "B is untouched by A's run")
      (resume! b)
      (is (= [3 3 6] [(count-of a) (count-of b) @ext-effect-count])))))

#?(:clj
   (deftest superseded-resume-never-greens-and-never-reads-successor
     (testing "run A parks at [:wait] on its own thread; run B (fresh run-key)
               supersedes it. A settles as an explicit superseded result and
               never dispatches its remaining event; B completes on its own state"
       (let [vid :story.owner/overlap]
         (rf.story/reg-variant vid
           {:setup  [[:counter/initialise 0]]
            :script [[:wait 250]
                     [:dispatch-sync [:probe/inc-and-effect]]
                     [:dispatch-sync [:rf.assert/path-equals [:count] 1]]]})
         (prepare! vid :a)
         (let [gen-a (rf.story.runtime/current-generation vid)
               fut   (future (rf.story.runtime/resume-run! vid))]
           (Thread/sleep 60)
           (prepare! vid :b)
           (let [b-prom   (rf.story.runtime/resume-run! vid)
                 a-result (rf.story.async/deref-blocking @fut 5000)
                 b-result (rf.story.async/deref-blocking b-prom 5000)]
             (is (= [:cannot-run true gen-a]
                    ((juxt :status :superseded? :generation) a-result))
                 "A settles superseded, never :pass, under its own generation")
             (is (= [:pass 1] [(:status b-result) (:count (:app-db b-result))]))
             (is (= 1 @ext-effect-count)
                 "only B's effect fired: A's stale continuation dispatched nothing")))))))

;; ---- every author-triggered run is fresh ---------------------------------
;;
;; The play chip's and banner's Re-run, a dropdown play row, Run all, the
;; recorder export's replay and the CI `runPlay` hook all call `rerun!`, after
;; the canvas's own prepare + resume (`select!`).

(defn- select! [vid opts]
  (rf.story.runtime/prepare-run! vid (assoc opts :run-key {:variant-id vid :opts opts}))
  (resume! vid))

(defn- rerun! [vid selection] (rf.story.runtime/rerun! vid selection))

(defn- play-status [vid play-key]
  (:status (rf.story.play.runner-events/current-state-for-play vid play-key)))

#?(:clj
   (defn- settled-status [p]
     (:status (rf.story.async/deref-blocking p 5000))))

(deftest rerun-is-fresh-for-a-stateful-variant
  (testing "Re-run after the author poked the canvas runs from the declared start"
    (let [vid :story.fresh/counter]
      (rf/reg-event :fresh/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
      (rf.story/reg-variant vid
        {:script [[:dispatch-sync [:fresh/inc]]
                  [:assert [:rf.assert/path-equals [:n] 1]]]})
      (select! vid {})
      (rf/dispatch-sync [:fresh/inc] {:frame vid})
      (rf/dispatch-sync [:fresh/inc] {:frame vid})
      (is (= 3 (:n (rf/app-db-value vid))) "precondition: the author poked the canvas")
      (let [p (rerun! vid {:play nil})]
        (is (= [:pass 1] [(play-status vid nil) (:n (rf/app-db-value vid))])
            "the play ran once from :setup, not on top of the poked state")
        #?(:clj  (is (= :pass (settled-status p)) "the unified verdict agrees")
           :cljs (is (some? p) "rerun! hands back the run's promise"))))))

(deftest rerun-reads-only-its-own-tape
  (testing "the author's own dispatch is not the re-run play's evidence"
    (let [vid :story.fresh/tape]
      (rf/reg-event :fresh/evidence (fn [{:keys [db]} _] {:db (assoc db :evidence true)}))
      (rf.story/reg-variant vid
        {:script [[:assert [:rf.assert/dispatched? [:fresh/evidence]]]]})
      (select! vid {})
      (rf/dispatch-sync [:fresh/evidence] {:frame vid})
      (let [p (rerun! vid {:play nil})]
        (is (= :fail (play-status vid nil)))
        #?(:clj  (is (= :fail (settled-status p)))
           :cljs (is (some? p)))))))

(deftest rerun-runs-the-compiled-play-with-the-run-inputs
  (testing "an [:arg] resolves through the canvas's Controls override, and a
            :compose'd fragment's script runs ahead of the variant's own"
    (rf/reg-event :fresh/set (fn [{:keys [db]} [_ k v]] {:db (assoc db k v)}))
    (let [vid :story.fresh/args]
      (rf.story/reg-variant vid
        {:args   {:value 7}
         :script [[:dispatch-sync [:fresh/set :value [:arg :value]]]
                  [:assert [:rf.assert/path-equals [:value] 11]]]})
      (select! vid {:cell-overrides {:value 11}})
      (rf/dispatch-sync [:fresh/set :value 0] {:frame vid})
      (rerun! vid {:play nil})
      (is (= :pass (play-status vid nil))))
    (let [vid :story.fresh/composed]
      (rf.story/reg-fragment :fragment.fresh/seed
        {:script {:script [[:dispatch-sync [:fresh/set :seeded true]]]}})
      (rf.story/reg-variant vid
        {:compose [:fragment.fresh/seed]
         :script  [[:assert [:rf.assert/path-equals [:seeded] true]]]})
      (select! vid {})
      (rerun! vid {:play nil})
      (is (= :pass (play-status vid nil))))))

(deftest rerun-selects-a-play-and-run-all-sequences
  (testing "a dropdown row runs THAT play from :setup and leaves it active;
            Run all prepares once and runs every play in order"
    (rf/reg-event :fresh/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (let [vid  :story.fresh/multi
          play (fn [nm n] {:name nm :auto-run? false
                           :script [[:dispatch-sync [:fresh/inc]]
                                    [:assert [:rf.assert/path-equals [:n] n]]]})]
      (rf.story/reg-variant vid {:plays [(play "a" 1) (play "b" 2) (play "c" 3)]})
      (select! vid {})
      (rerun! vid {:play "b"})
      (is (= [:fail 1] [(play-status vid "b") (:n (rf/app-db-value vid))])
          "b alone starts from :setup, where the count reaches 1, not 2")
      (is (= "b" (rf.story.play.runner-events/active-play-key vid)))
      (rerun! vid {:play :all})
      (is (= [:pass :pass :pass] (mapv #(play-status vid %) ["a" "b" "c"]))
          "one reset, then a/b/c in declared order")
      #?(:clj (is (= :error (settled-status (rerun! vid {:play "no-such-play"})))
                  "a play key naming no play refuses the run")))))

#?(:clj
   (deftest export-replay-runs-the-recording-fresh
     (testing "'replay in this story' runs the export from the recorded
               variant's declared start: a correct export reads PASS and the
               recording is not doubled"
       (let [vid :story.fresh/recorded-source]
         (rf/reg-event :fresh/submit (fn [{:keys [db]} _] {:db (update db :submits (fnil inc 0))}))
         (rf.story/reg-variant vid {:setup []})
         (select! vid {})
         (rf/dispatch-sync [:fresh/submit] {:frame vid})
         (rf/dispatch-sync [:fresh/submit] {:frame vid})
         (let [{:keys [spec]} (rf.story.recorder.play-export-events/build-export
                                [[:fresh/submit] [:fresh/submit]]
                                {:variant-id   :story.fresh/recorded
                                 :extends      vid
                                 :auto-run?    true
                                 :auto-assert? true
                                 :final-db     (rf/app-db-value vid)})
               done (promise)]
           (rf.story.recorder.play-export-events/replay-script! vid spec #(deliver done %))
           (is (= [:pass 2] [(:status (deref done 5000 :timeout))
                             (:submits (rf/app-db-value vid))])))))))
