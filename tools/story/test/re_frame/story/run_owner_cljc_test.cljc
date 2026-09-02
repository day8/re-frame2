(ns re-frame.story.run-owner-cljc-test
  "Regression net for Story's ONE run owner (rf2-j538f7.34).

  A focused shell selection used to have THREE execution owners — the
  selection-edge frame preallocation (a full `run-variant`), the canvas
  `component-did-mount` run (a second full `run-variant`), and the post-commit
  `auto-run!` — so a variant's play-script (and its external effects) ran up to
  THREE times and a cumulative script double-counted (a passing variant looked
  failed). These tests drive the production ownership sequence directly against
  the real plain-atom adapter + lifecycle machine + plan compiler + runner, and
  pin that the script now executes EXACTLY ONCE.

  Runs on BOTH runtimes: the play-scripts are pure `:dispatch-sync`, which
  `async/promise` executes synchronously, so the frame's app-db and the
  external-effect counter are settled the instant `resume-run!` returns — no
  awaiting needed. The one supersession test that needs an async barrier is
  JVM-gated (it blocks a thread)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.machines :as machines]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story :as story]
            [re-frame.story.loaders :as loaders]
            [re-frame.story.play :as legacy-play]
            [re-frame.story.play.runner-events :as re]
            [re-frame.story.runtime :as rt]
            #?@(:clj [[re-frame.story.async :as async]
                      [re-frame.story.config :as config]])))

;; ---- external-effect counter (an irreversible effect proxy) --------------
;;
;; A single owner would ISSUE this exactly once per script run; the pre-fix
;; three-owner composition issued it up to nine times. app-db resets between
;; runs hide the mutation, but an external effect cannot be un-sent — so this
;; counter is the honest witness of how many times the script actually ran.

(def ^:private ext-effect-count (atom 0))

(defn- reset-all! [test-fn]
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ nil))
  #?(:clj (require 're-frame.machines :reload))
  (machines/reset-timers!)
  (loaders/clear-watchers!)
  #?(:clj (config/set-global-args! {}))
  (reset! legacy-play/stepper-state {})
  (reset! re/run-state {})
  (reset! re/step-boundaries {})
  (rt/reset-run-owner!)
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!)
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

(defn- reg-cumulative!
  "Register a cumulative-counter variant: seed 0, increment three times (each
  increment also issues the external effect), assert final `:count` = 3."
  [vid]
  (story/reg-variant vid
    {:setup      [[:counter/initialise 0]]
     :script [[:dispatch-sync [:probe/inc-and-effect]]
                   [:dispatch-sync [:probe/inc-and-effect]]
                   [:dispatch-sync [:probe/inc-and-effect]]
                   [:dispatch-sync [:rf.assert/path-equals [:count] 3]]]}))

(defn- run-key-for [vid tag] {:variant-id vid :cell-overrides tag})

(defn- prepare! [vid tag]
  (rt/prepare-run! vid {:run-key (run-key-for vid tag)}))

(defn- resume!
  "Call the single run owner's resume. The play-scripts here are pure
  `:dispatch-sync`, so `async/promise` runs the script synchronously and the
  frame's app-db is settled the instant this returns — no await needed."
  [vid]
  (rt/resume-run! vid))

(defn- count-of [vid] (:count (rf/app-db-value vid)))

(defn- passing-assertions [vid]
  (filterv #(true? (:passed? %))
           (:rf.story/assertions (rf/app-db-value vid))))

;; ---- (1) the core defect: exactly once -----------------------------------

(deftest shell-auto-play-executes-exactly-once
  (testing "the three-owner shell sequence (selection preallocation + canvas
            mount prepare + post-commit resume, resume also re-triggered by the
            mount-time block) executes the play-script EXACTLY ONCE"
    (let [vid :story.owner/cumulative]
      (reg-cumulative! vid)
      ;; 1. selection-edge preallocation — PREPARE (no script)
      (prepare! vid :a)
      ;; frame is ready + rendered clean at seed 0, but the script has NOT run
      (is (= 0 (count-of vid)) "prepare did not run the script")
      (is (zero? @ext-effect-count) "prepare issued no external effect")
      (is (= 1 (rt/current-generation vid)) "one generation claimed")
      ;; 2. canvas component-did-mount — PREPARE again (same run-key ⇒ dedup)
      (prepare! vid :a)
      (is (= 1 (rt/current-generation vid))
          "the canvas re-prepare for the same run-key did NOT bump the generation")
      (is (= 0 (count-of vid)) "the dedup prepare did not reset+rerun anything")
      ;; 3. canvas post-commit resume — the single run owner
      (resume! vid)
      (is (= 3 (count-of vid)) "the script ran once: 0 -> 3")
      (is (= 3 @ext-effect-count) "the external effect fired exactly 3 times (one run)")
      (is (= 1 (count (passing-assertions vid)))
          "exactly one passing assertion record — never a second, contradictory one")
      ;; 4. the mount-time block + the shell selection-watcher's setTimeout both
      ;;    ALSO call resume for this generation — every extra call is a no-op.
      (is (nil? (rt/resume-run! vid)) "second resume for the same generation is a no-op")
      (is (nil? (rt/resume-run! vid)) "third resume for the same generation is a no-op")
      (is (= 3 (count-of vid)) "count is still 3 — no double-count to 6")
      (is (= 3 @ext-effect-count) "the external effect still fired exactly 3 times")
      (is (= 1 (count (passing-assertions vid)))
          "still exactly one passing assertion — no contradictory second record"))))

;; ---- (2) prepare is script-free, frame renderable ------------------------

(deftest prepare-only-readies-frame-without-running-script
  (testing "PREPARE completes loaders + setup (frame allocated + :ready) so the
            first render is safe, WITHOUT dispatching the play script"
    (let [vid :story.owner/prep]
      (reg-cumulative! vid)
      (prepare! vid :a)
      ;; frame exists (subs can deref) and lifecycle is renderable
      (is (some? (rf/app-db-value vid)) "the frame is allocated before any resume")
      (is (= :ready (loaders/current-state vid)) "the frame reached :ready in prepare")
      ;; but the script has not run
      (is (= 0 (count-of vid)) "seed 0, no play-script increments yet")
      (is (zero? @ext-effect-count) "no external effect issued by prepare")
      (is (empty? (passing-assertions vid)) "no assertion recorded before resume"))))

;; ---- (3) a genuinely new run-key bumps + re-runs -------------------------

(deftest run-key-change-bumps-generation-and-reruns-once
  (testing "a cell-override / mode / substrate / hot-reload change (new run-key)
            claims a fresh generation and runs the script once more"
    (let [vid :story.owner/rekey]
      (reg-cumulative! vid)
      (prepare! vid :a)
      (resume! vid)
      (is (= 3 (count-of vid)))
      (is (= 3 @ext-effect-count))
      ;; run-key changes → fresh generation, fresh reset+run
      (prepare! vid :b)
      (is (= 2 (rt/current-generation vid)) "changed run-key bumped the generation")
      (is (= 0 (count-of vid)) "the fresh prepare reset the frame to seed 0")
      (resume! vid)
      (is (= 3 (count-of vid)) "the new generation ran the script once: 0 -> 3")
      (is (= 6 @ext-effect-count) "exactly one additional run's worth of effects (3 + 3)"))))

;; ---- (4) a remount after resume re-runs ----------------------------------

(deftest remount-after-resume-bumps-and-reruns-once
  (testing "a React remount (re-prepare for the SAME run-key AFTER the current
            generation was already resumed) claims a fresh generation and runs
            the script once — not zero, not twice"
    (let [vid :story.owner/remount]
      (reg-cumulative! vid)
      (prepare! vid :a)
      (resume! vid)
      (is (= 1 (rt/current-generation vid)))
      (is (= 3 @ext-effect-count))
      ;; remount: same run-key, but the generation was already resumed
      (prepare! vid :a)
      (is (= 2 (rt/current-generation vid)) "post-resume re-prepare bumped the generation")
      (is (= 0 (count-of vid)) "the remount reset the frame")
      (resume! vid)
      (is (= 3 (count-of vid)))
      (is (= 6 @ext-effect-count) "exactly one more run"))))

;; ---- (5) two variants run concurrently + isolated ------------------------

(deftest two-variants-run-concurrently-and-isolated
  (testing "ownership is per variant/frame, not one global lock: two variants
            prepare + resume independently with isolated app-db + effects"
    (let [a :story.owner/a
          b :story.owner/b]
      (reg-cumulative! a)
      (reg-cumulative! b)
      ;; interleave the two variants' prepare/resume owners
      (prepare! a :a)
      (prepare! b :a)
      (is (= 1 (rt/current-generation a)))
      (is (= 1 (rt/current-generation b)))
      (resume! a)
      (is (= 3 (count-of a)) "A ran to 3")
      (is (= 0 (count-of b)) "B is untouched by A's run")
      (resume! b)
      (is (= 3 (count-of a)) "A stays 3 after B runs")
      (is (= 3 (count-of b)) "B ran to 3")
      (is (= 6 @ext-effect-count) "each variant issued its 3 effects exactly once"))))

;; ---- (6) the public headless run-variant is unchanged --------------------

(deftest public-run-variant-still-runs-full-once
  (testing "the headless `story/run-variant` (test-mode / MCP / sidebar
            Run-all) is byte-for-byte the old full run: script once, count 3"
    (let [vid :story.owner/headless]
      (reg-cumulative! vid)
      #?(:clj
         (let [result (async/deref-blocking (story/run-variant vid) 5000)]
           (is (= :ready (:lifecycle result)))
           (is (= 3 (:count (:app-db result))) "one full run: count 3")
           (is (= 3 @ext-effect-count) "one full run's effects")
           (is (= :pass (:status result)) "the passing variant greens"))
         :cljs
         ;; CLJS: the sync script settles the frame synchronously; read it back.
         (do (story/run-variant vid)
             (is (= 3 (count-of vid)) "one full run: count 3")
             (is (= 3 @ext-effect-count) "one full run's effects"))))))

;; ---- (7) supersession never greens a stale run (JVM async barrier) -------

#?(:clj
   (deftest superseded-resume-never-greens-and-never-reads-successor
     (testing "run A parks at [:wait] on its own thread; run B (fresh run-key)
               supersedes it — B's resume stamps a new run-token that aborts A's
               stale continuation. A settles as an explicit superseded result
               (never :pass) and NEVER dispatches its remaining event; B
               completes with only its own state"
       (let [vid  :story.owner/overlap
             wait 250]
         ;; The variant waits, then increments once (issuing the external
         ;; effect), then asserts. The wait opens the supersession window.
         (story/reg-variant vid
           {:setup      [[:counter/initialise 0]]
            :script [[:wait wait]
                          [:dispatch-sync [:probe/inc-and-effect]]
                          [:dispatch-sync [:rf.assert/path-equals [:count] 1]]]})
         ;; A prepares + starts resuming on its OWN thread. resume-run! blocks
         ;; that thread through the [:wait] (JVM waits are Thread/sleep), so the
         ;; future's value is A's settled result-promise.
         (prepare! vid :a)
         (let [gen-a (rt/current-generation vid)
               fut   (future (rt/resume-run! vid))]
           (is (= 1 gen-a))
           ;; Let A reach its wait, then supersede: a fresh run-key bumps the
           ;; generation, resets the frame, and B's resume stamps a NEW
           ;; run-token — which A's run-loop sees on wake and aborts against.
           (Thread/sleep 60)
           (prepare! vid :b)
           (is (= 2 (rt/current-generation vid)) "B claimed a fresh generation")
           (let [b-prom   (rt/resume-run! vid)
                 a-prom   @fut
                 a-result (async/deref-blocking a-prom 5000)
                 b-result (async/deref-blocking b-prom 5000)]
             (is (not= :pass (:status a-result))
                 "the superseded run A must NOT return :pass")
             (is (true? (:superseded? a-result))
                 "A settles as an explicit superseded result")
             (is (= gen-a (:generation a-result))
                 "the superseded result names A's own generation, not B's")
             (is (= :pass (:status b-result)) "B greens on its own state")
             (is (= 1 (:count (:app-db b-result))) "B ran its single increment: 0 -> 1")
             ;; The decisive criterion-5 proof: only B's increment issued an
             ;; external effect. A's stale continuation was aborted before it
             ;; could dispatch — it never mutated the successor frame.
             (is (= 1 @ext-effect-count)
                 "ONLY B's effect fired — A's stale continuation dispatched nothing")))))))
