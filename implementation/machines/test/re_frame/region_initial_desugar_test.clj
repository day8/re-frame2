(ns re-frame.region-initial-desugar-test
  "rf2-x76af2.7: the synthetic region-spec must be DESUGARED —
  `:timeout` / `:on-timeout` lowered onto `:after`, `:type :choice` / `:choice`
  onto `:always` — on EVERY path, including the birth / root-target paths that
  call `apply-transition-once` directly (`bootstrap-step`,
  `apply-root-region-target`) and therefore BYPASS the per-dispatch
  `machine-transition` desugar seam.

  The fix desugars the region body at `build-region-machine` — the single
  choke-point where synthetic region-specs are born and memoised in the
  `::region-cache` — so the cache holds the lowered form and both the direct-
  apply birth paths and the event path (which re-desugars per dispatch) see
  identically-lowered region bodies.

  Pre-fix, the raw region body was faulted into the cache during
  `build-initial-snapshot`'s tag computation, then served — still raw — on
  the direct-apply paths, so:
    (a) a region-initial `:timeout` never armed its `:after` at birth;
    (b) a region-initial `:type :choice` stayed stuck at its transient node;
    (c) a root `:on` target INTO a `:timeout` state never lowered.
  All three are exercised end-to-end through registration + live dispatch
  (the JVM plain-atom substrate the audit reproduced on)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private snapshot rf.machines.test-support/snapshot)

(defn- scheduled-delays
  "The set of `:delay`s across all `:rf.machine.timer/scheduled` traces."
  [traces]
  (into #{}
        (comp (filter #(= :rf.machine.timer/scheduled (:operation %)))
              (map #(:delay (:tags %))))
        traces))

;; ---- (a) region-initial :timeout arms its :after at birth ------------------

(deftest region-initial-timeout-arms-at-birth
  (testing "a region-initial state's :timeout schedules its :after (5000) at birth"
    (let [m {:type    :parallel
             :data    {}
             :regions {:left {:initial :waiting
                             :states  {:waiting {:timeout    "PT5S"
                                                 :on-timeout {:target :done}}
                                       :done    {}}}}}
          traces (atom [])]
      (rf/reg-machine :rf.region-desugar/timeout m)
      (rf/register-listener! :trace ::t (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.region-desugar/timeout [:rf.machine/start]])
      (rf/unregister-listener! :trace ::t)
      (is (contains? (scheduled-delays @traces) 5000)
          "the region-initial :timeout lowered onto :after and armed at birth
           (pre-fix: SCHEDULED was #{} — the raw region body served from the cache)"))))

;; ---- (b) region-initial :type :choice resolves past the transient node -----

(deftest region-initial-choice-resolves-at-birth
  (testing "a region-initial :type :choice resolves to its guarded branch on start"
    (let [m {:type    :parallel
             :data    {}
             :regions {:left {:initial :pick
                             :states  {:pick {:type   :choice
                                              :choice [{:target :yes}]}
                                       :yes  {}}}}}]
      (rf/reg-machine :rf.region-desugar/choice m)
      (rf/dispatch-sync [:rf.region-desugar/choice [:rf.machine/start]])
      (is (= :yes (get-in (snapshot :rf.region-desugar/choice) [:state :left]))
          "the transient :type :choice node settled past to :yes at birth
           (pre-fix: stuck at :pick — externally observed transient node)"))))

;; ---- (c) a root :on target INTO a :timeout state lowers --------------------

(deftest root-on-target-into-timeout-state-lowers
  (testing "a root :on landing a region on a :timeout state arms that state's :after"
    (let [m {:type    :parallel
             :data    {}
             :on      {:jump {:target [:left :waiting]}}
             :regions {:left {:initial :idle
                             :states  {:idle    {}
                                       :waiting {:timeout    "PT5S"
                                                 :on-timeout {:target :done}}
                                       :done    {}}}}}
          traces (atom [])]
      (rf/reg-machine :rf.region-desugar/root-target m)
      (rf/dispatch-sync [:rf.region-desugar/root-target [:rf.machine/start]])
      ;; capture only the :jump macrostep's traces
      (rf/register-listener! :trace ::t (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.region-desugar/root-target [:jump]])
      (rf/unregister-listener! :trace ::t)
      (is (= :waiting (get-in (snapshot :rf.region-desugar/root-target) [:state :left]))
          "the root :on moved :left into :waiting")
      (is (contains? (scheduled-delays @traces) 5000)
          "entering :waiting via the root target armed its lowered :after
           (pre-fix: the un-desugared :timeout on the cached region body never armed)"))))

;; ---- sanity: an EXPLICIT (already-lowered) :after arms identically ---------
;;
;; Control proving the birth/root scheduling path itself is sound — the bug was
;; purely the un-lowered :timeout, not the scheduler.

(deftest region-initial-explicit-after-arms-at-birth
  (testing "a region-initial state with an explicit :after arms at birth"
    (let [m {:type    :parallel
             :data    {}
             :regions {:left {:initial :waiting
                             :states  {:waiting {:after {5000 {:target :done}}}
                                       :done    {}}}}}
          traces (atom [])]
      (rf/reg-machine :rf.region-desugar/explicit-after m)
      (rf/register-listener! :trace ::t (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.region-desugar/explicit-after [:rf.machine/start]])
      (rf/unregister-listener! :trace ::t)
      (is (contains? (scheduled-delays @traces) 5000)
          "the explicit region-initial :after arms at birth (control for the :timeout case)"))))
