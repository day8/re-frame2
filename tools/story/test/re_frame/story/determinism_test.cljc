(ns re-frame.story.determinism-test
  "Tests for the determinism gate `assert-deterministic` + the per-run
  stamp strip the gate relies on in `canonicalize`
  (spec/017-Testing-Story.md §Determinism gate): the pure strip, `->artifact`
  and `compare-runs`, then the gate replaying into fresh frames."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core      :as rf]
            [re-frame.epoch     :as rf.epoch]
            [re-frame.frame     :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story.artifact    :as rf.story.artifact]
            [re-frame.story.determinism :as rf.story.determinism]
            [re-frame.story.fingerprint :as rf.story.fingerprint]
            [re-frame.story.registrar   :as rf.story.registrar]))

;; ===========================================================================
;; PURE: the per-run stamp strip in canonicalize
;; ===========================================================================

(defn- trace-ev
  "A minimal trace event carrying its per-run stamps (`:id` / `:time`)."
  [id m]
  (merge {:operation :rf.event/run-start :op-type :event
          :id id :time 999 :tags {}}
         m))

(deftest canonicalize-keeps-a-trace-events-semantic-tags
  (let [a {:trace-events [(trace-ev 17 {:tags {:rf.trace/event-id :foo}})]}
        c {:trace-events [(trace-ev 17 {:tags {:rf.trace/event-id :bar}})]}]
    (is (not= (rf.story.fingerprint/canonicalize a) (rf.story.fingerprint/canonicalize c))
        "the event-id tag is behavioural, not a stamp")))

;; :id / :time / :frame are stripped only from trace-event and epoch-record
;; carriers, never from app-db data that happens to use those keys.
(deftest structural-strip-spares-app-db-keys
  (is (not= (rf.story.fingerprint/run-hash {:status :pass :app-db {:user {:id 1 :time 10 :frame :left}}})
            (rf.story.fingerprint/run-hash {:status :pass :app-db {:user {:id 2 :time 20 :frame :right}}}))))

;; ===========================================================================
;; PURE: ->artifact coercion
;; ===========================================================================

(deftest ->artifact-coercion
  (testing "a plan of an unregistered variant folds [:world :setup] ⧺ :script
            and lifts fx-overrides — nothing can supply its setup through
            :extends"
    (let [a (rf.story.determinism/->artifact
              {:variant/id :story/x
               :world  {:setup [[:dispatch [:seed]]]
                        :args  {:n 9}
                        :frame {:fx-overrides {:http/get :http/stub}}}
               :script [[:dispatch [:act]] [:wait 9]]})]
      (is (= {:event-program [[:dispatch [:seed]] [:dispatch [:act]] [:wait 9]]
              :fx-decisions  {:http/get :http/stub}}
             (select-keys a [:event-program :fx-decisions])))
      (is (not (contains? (:source a) :args))
          "the folded setup already holds its resolved args")))

  (testing "a plan of a REGISTERED variant leaves [:world :setup] out of the
            program: promotion :extends that variant, which supplies it"
    (rf.story.registrar/reg-variant* :story.det/registered
      {:setup [[:dispatch [:seed]]] :script [[:dispatch [:act]]]})
    (try
      (let [a (rf.story.determinism/->artifact
                {:variant/id :story.det/registered
                 :world  {:setup [[:dispatch [:seed]]]
                          :args  {:n 9}}
                 :script [[:dispatch [:act]]]})]
        (is (= [[:dispatch [:act]]] (:event-program a)) "the script alone")
        (is (= {:variant/id :story.det/registered :args {:n 9}}
               (select-keys (:source a) [:variant/id :args]))
            "the source names the variant and the args its setup was compiled with"))
      (finally (rf.story.registrar/unregister! :variant :story.det/registered)))))

;; ===========================================================================
;; PURE: compare-runs
;; ===========================================================================

(deftest compare-runs-pure
  (testing "identical canonical runs are deterministic; every reported hash is run-hash"
    (let [r {:status :pass :app-db {:n 1}}
          h (rf.story.fingerprint/run-hash r)]
      (is (= {:deterministic? true :run-count 3 :hashes [h h h] :run-hash h}
             (rf.story.determinism/compare-runs [r r r])))))

  (testing "a divergent run is detected and named (first differing run vs run 0)"
    (let [r0 {:status :pass :app-db {:n 1}}
          r2 {:status :pass :app-db {:n 999}}
          h0 (rf.story.fingerprint/run-hash r0)
          h2 (rf.story.fingerprint/run-hash r2)]
      (is (= {:deterministic? false :run-count 3 :hashes [h0 h0 h2] :run-hash nil
              :divergence {:run 2 :run-hash-0 h0 :run-hash-n h2}}
             (update (rf.story.determinism/compare-runs [r0 r0 r2]) :divergence dissoc :detail))))))

;; ===========================================================================
;; HEADLESS gate: against a live frame  (spec/017 §Determinism gate)
;; ===========================================================================

(defn- reset-rf! [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.epoch/clear-history!)
  (rf.epoch/clear-epoch-listeners!)
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ nil))
  (rf.frame/ensure-default-frame!)
  (test-fn))

(use-fixtures :each reset-rf!)

;; A plan's artifact is a program projection, not the variant's run: it drops
;; decorator stubs, :db-seed, frame-setup, loaders, terminal expectations and
;; extra plays. A variant is judged by running it twice and comparing the
;; run-results with compare-runs.
(deftest gate-refuses-a-normalized-plan
  (rf/reg-event :det/seed (fn [{:keys [db]} [_ v]] {:db (assoc db :v v)}))
  (rf/reg-event :det/bump (fn [{:keys [db]} _] {:db (update db :v inc)}))
  (testing "a normalized plan is REFUSED before any replay"
    (is (= {:status :cannot-run :reason :determinism-plan-target :variant/id :story.det/plan}
           (dissoc (rf.story.determinism/assert-deterministic
                     {:variant/id :story.det/plan
                      :world  {:setup [[:dispatch [:det/seed 10]]]}
                      :script [[:dispatch [:det/bump]]]}
                     {:runs 3})
                   :detail))))
  (testing "control: the same :setup / :script as a BODY map still replays"
    (let [res (rf.story.determinism/assert-deterministic
                {:setup [[:dispatch [:det/seed 10]]] :script [[:dispatch [:det/bump]]]}
                {:runs 3})]
      (is (= :deterministic (:status res)))
      (is (= 3 (:runs res))))))

;; An fx-error run carries a per-run `:error-trace` pointer and the raw
;; thrown exception; compared raw, they would make the gate read a perfectly
;; reproducible failing program as :non-deterministic.
(deftest gate-throwing-fx-program-is-deterministic
  (rf/reg-fx :det.fx/boom {:platforms #{:client :server}}
             (fn [_ _] (throw (ex-info "boom" {:k 1}))))
  (rf/reg-event :det/boom (fn [_ _] {:fx [[:det.fx/boom {}]]}))
  (let [a (rf.story.artifact/make-run-artifact {:event-program [[:dispatch [:det/boom]]]})]
    (is (= :deterministic (:status (rf.story.determinism/assert-deterministic a))))
    (is (= :fail (:status (rf.story.artifact/replay-run-artifact a)))
        "control: the program genuinely fails")))

(deftest gate-detects-real-semantic-nondeterminism
  ;; Each replay reads + bumps a process-global atom, so replay 1 writes 1 and
  ;; replay 2 writes 2 into a fresh frame's app-db.
  (let [counter (atom 0)]
    (rf/reg-event :det/nondet
      (fn [{:keys [db]} _] {:db (assoc db :token (swap! counter inc))}))
    (let [res (rf.story.determinism/assert-deterministic
                (rf.story.artifact/make-run-artifact {:event-program [[:dispatch [:det/nondet]]]}))]
      (is (= :non-deterministic (:status res)))
      (is (= 1 (get-in res [:divergence :run])) "run 1 diverged from run 0")
      (is (= 2 (count (:results res))) "per-run results returned for a semantic diff"))))

(deftest gate-refuses-bare-wall-clock-wait
  ;; [:wait-until …] settles on state, so only the bare [:wait ms] is refused.
  (is (= {:status :cannot-run :reason :determinism-wall-clock-wait :wait-steps [[:wait 50]]}
         (dissoc (rf.story.determinism/assert-deterministic
                   (rf.story.artifact/make-run-artifact
                     {:event-program [[:dispatch [:det/inc]]
                                      [:wait 50]
                                      [:wait-until [:queue-empty]]
                                      [:dispatch [:det/inc]]]}))
                 :detail))))

(deftest gate-reapplies-fx-decisions-deterministically
  (let [hits (atom [])]
    (rf/reg-fx :det.fx/real {:platforms #{:client :server}}
               (fn [_ _] (swap! hits conj :real)))
    (rf/reg-fx :det.fx/stub {:platforms #{:client :server}}
               (fn [_ _] (swap! hits conj :stub)))
    (rf/reg-event :det/fire (fn [_ _] {:fx [[:det.fx/real {}]]}))
    (let [res (rf.story.determinism/assert-deterministic
                (rf.story.artifact/make-run-artifact
                  {:event-program [[:dispatch [:det/fire]]]
                   :fx-decisions  {:det.fx/real :det.fx/stub}}))]
      (is (= :deterministic (:status res)))
      (is (re-matches #"[0-9a-f]{8}" (:run-hash res)) "the shared run-hash is 8-char hex")
      (is (= [:stub :stub] @hits) "the stub fired on both default replays"))))
