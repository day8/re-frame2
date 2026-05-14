(ns re-frame.after-test
  "Per Spec 005 §Delayed :after transitions and rf2-3y3y.

  State-level :after timer semantics:
    - Flat map {<delay> <transition>} per state.
    - Delay key is one of: pos-int? literal, subscription vector, fn.
    - Multiple :after entries per state run independently from state entry.
    - Guards on :after suppress the transition without exiting; siblings
      continue (per Spec 005 §Multi-stage interaction with :guard).
    - Race: whichever transition fires first wins; others go stale via
      the per-machine :rf/after-epoch counter.
    - No-invoke variant: a state with :after but no :invoke is a pure
      timed-transition state.
    - :timeout-ms / :on-timeout on :invoke / :invoke-all is REMOVED;
      registration throws :rf.error/invoke-timeout-ms-removed.

  These JVM tests dispatch the synthetic
  [:rf.machine.timer/after-elapsed delay-key epoch] event manually so
  the verification is deterministic without depending on setTimeout
  firing. The CLJS runtime path for actual wall-clock scheduling is
  exercised by machines_cljs_test.cljs."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace]))

(use-fixtures :each
  (test-support/reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- frame-db [] (rf/get-frame-db :rf/default))
(defn- snapshot [machine-id] (get-in (frame-db) [:rf/machines machine-id]))

;; ---- registration-time rejection of :timeout-ms ---------------------------

(deftest invoke-timeout-ms-rejected
  (testing ":timeout-ms on :invoke fails registration"
    (let [bad {:initial :idle
               :states  {:idle {:on {:go :r}}
                         :r    {:invoke {:machine-id :stub
                                         :timeout-ms 1000
                                         :on-timeout [:never]}}}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"invoke-timeout-ms-removed"
                            (rf/reg-machine :rmv/bad bad))
          "registration emits the migration error category")))
  (testing ":on-timeout alone on :invoke is also rejected"
    (let [bad {:initial :idle
               :states  {:idle {:on {:go :r}}
                         :r    {:invoke {:machine-id :stub
                                         :on-timeout [:never]}}}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"invoke-timeout-ms-removed"
                            (rf/reg-machine :rmv/bad2 bad)))))
  (testing ":timeout-ms on :invoke-all is rejected"
    (let [bad {:initial :idle
               :states  {:idle {:on {:go :h}}
                         :h    {:invoke-all
                                {:children        [{:id :a :machine-id :stub}]
                                 :join            :all
                                 :on-child-done   :done
                                 :on-child-error  :failed
                                 :on-all-complete [:done!]
                                 :timeout-ms      5000
                                 :on-timeout      [:to]}}}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"invoke-timeout-ms-removed"
                            (rf/reg-machine :rmv/bad3 bad))))))

;; ---- single-delay :after on entry / fire -----------------------------------

(deftest after-single-delay
  (testing ":after schedules with current epoch on entry; fires on synthetic timer event"
    (let [m {:initial :idle
             :data    {:rf/after-epoch 0}
             :states
             {:idle    {:on {:fetch :loading}}
              :loading {:after {5000 :timeout}
                        :on    {:loaded :ready}}
              :timeout {}
              :ready   {}}}
          traces (atom [])]
      (rf/reg-machine :a/single m)
      (rf/register-trace-cb! ::s (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:a/single [:fetch]])
      (let [s (snapshot :a/single)]
        (is (= :loading (:state s)))
        (is (= 1 (get-in s [:data :rf/after-epoch]))))
      (is (some #(and (= :rf.machine.timer/scheduled (:operation %))
                       (= 5000     (:delay (:tags %)))
                       (= :literal (:delay-source (:tags %))))
                 @traces)
          ":scheduled trace with :delay-source :literal")
      (rf/dispatch-sync [:a/single [:rf.machine.timer/after-elapsed 5000 1]])
      (is (= :timeout (:state (snapshot :a/single)))
          "matching-epoch firing transitions :loading → :timeout")
      (rf/remove-trace-cb! ::s))))

;; ---- multi-stage :after — warn at 5s, fail at 30s -------------------------

(deftest after-multi-stage
  (testing "multiple :after entries run independently from entry-time"
    (let [m {:initial :idle
             :data    {:rf/after-epoch 0}
             :states
             {:idle    {:on {:fetch :loading}}
              :loading {:after {5000  :warn
                                30000 :timeout}
                        :on    {:loaded :ready}}
              :warn    {:on {:loaded :ready
                             :timeout :timeout}}
              :timeout {}
              :ready   {}}}]
      (rf/reg-machine :a/multi m)
      (rf/dispatch-sync [:a/multi [:fetch]])
      (is (= :loading (:state (snapshot :a/multi))))
      (let [epoch (get-in (snapshot :a/multi) [:data :rf/after-epoch])]
        ;; The 5000ms timer fires first.
        (rf/dispatch-sync [:a/multi [:rf.machine.timer/after-elapsed 5000 epoch]])
        (is (= :warn (:state (snapshot :a/multi)))
            "5s timer fires; transition to :warn")
        ;; The original 30000ms timer (epoch 1) is now stale, but at this
        ;; point the snapshot has moved to :warn (which has no :after);
        ;; pick-after-transition's "no :after table found" + epoch-mismatch
        ;; surfaces stale-after.
        (rf/dispatch-sync [:a/multi [:rf.machine.timer/after-elapsed 30000 epoch]])
        (is (= :warn (:state (snapshot :a/multi)))
            "stale 30s firing does not transition")))))

;; ---- :guard suppresses one :after; siblings continue ---------------------

(deftest after-guard-suppresses-one-siblings-continue
  (testing "guard returning false suppresses the transition without exiting; sibling timers continue"
    (let [m {:initial :idle
             :data    {:rf/after-epoch 0
                       :slow?          false}
             :guards  {:slow? (fn [data _] (:slow? data))}
             :states
             {:idle    {:on {:fetch :loading}}
              :loading {:after {5000  {:guard :slow? :target :warn}
                                30000 :timeout}
                        :on    {:loaded :ready}}
              :warn    {}
              :timeout {}
              :ready   {}}}
          traces (atom [])]
      (rf/reg-machine :a/guard m)
      (rf/register-trace-cb! ::g (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:a/guard [:fetch]])
      (let [epoch (get-in (snapshot :a/guard) [:data :rf/after-epoch])]
        ;; 5s fires; guard :slow? returns false → suppressed.
        (rf/dispatch-sync [:a/guard [:rf.machine.timer/after-elapsed 5000 epoch]])
        (is (= :loading (:state (snapshot :a/guard)))
            "guard-suppressed :after must not transition")
        (is (= epoch (get-in (snapshot :a/guard) [:data :rf/after-epoch]))
            "guard-suppressed :after must not advance epoch")
        (is (some #(and (= :rf.machine.timer/fired (:operation %))
                         (false? (:fired? (:tags %)))
                         (= 5000  (:delay (:tags %))))
                   @traces)
            ":fired? false trace emitted on guard suppression")
        ;; The 30000ms sibling timer is still live (same epoch) — fire it.
        (rf/dispatch-sync [:a/guard [:rf.machine.timer/after-elapsed 30000 epoch]])
        (is (= :timeout (:state (snapshot :a/guard)))
            "sibling :after timer continues and transitions on its own"))
      (rf/remove-trace-cb! ::g))))

;; ---- no-invoke variant (splash screen) -----------------------------------

(deftest after-no-invoke-splash
  (testing "a state with :after but no :invoke is a pure timed-transition state"
    (let [m {:initial :splash
             :data    {:rf/after-epoch 0}
             :states
             {:splash {:after {3000 :main}
                       :on    {:skip :main}}
              :main   {}}}]
      (rf/reg-machine :a/splash m)
      ;; First dispatch synthesises the snapshot at :splash.
      (rf/dispatch-sync [:a/splash [:noop]])
      (is (= :splash (:state (snapshot :a/splash))))
      (let [epoch (get-in (snapshot :a/splash) [:data :rf/after-epoch])]
        (rf/dispatch-sync [:a/splash [:rf.machine.timer/after-elapsed 3000 epoch]])
        (is (= :main (:state (snapshot :a/splash)))
            ":after fired transition with no :invoke spawn")))))

;; ---- race: real event beats timer; stale firing must not transition ------

(deftest after-race-real-event-wins
  (testing "real event arrives before timer; in-flight timer fires stale and is suppressed"
    (let [m {:initial :idle
             :data    {:rf/after-epoch 0}
             :states
             {:idle    {:on {:fetch :loading}}
              :loading {:after {5000 :timeout}
                        :on    {:loaded :ready}}
              :timeout {}
              :ready   {}}}
          traces (atom [])]
      (rf/reg-machine :a/race m)
      (rf/dispatch-sync [:a/race [:fetch]])
      (rf/dispatch-sync [:a/race [:loaded]])
      (is (= :ready (:state (snapshot :a/race))))
      (rf/register-trace-cb! ::r (fn [ev] (swap! traces conj ev)))
      ;; Stale firing from epoch 1 (the :loading visit).
      (rf/dispatch-sync [:a/race [:rf.machine.timer/after-elapsed 5000 1]])
      (is (= :ready (:state (snapshot :a/race)))
          "stale firing must not transition")
      (is (some #(and (= :rf.machine.timer/stale-after (:operation %))
                       (= 5000 (:delay (:tags %))))
                 @traces)
          ":stale-after trace emitted")
      (rf/remove-trace-cb! ::r))))

;; ---- fn-form delay (computed once at entry) -------------------------------

(deftest after-fn-form-delay
  (testing "(fn [snap] ms) delay form: invoked once at state entry"
    (let [;; Per spec, fn delays use the ORIGINAL fn key for synthetic-event
          ;; lookup. This test exercises pick-after-transition's lookup
          ;; with a fn delay-key.
          delay-fn (fn [_snap] 7000)
          m {:initial :idle
             :data    {:rf/after-epoch 0}
             :states
             {:idle    {:on {:go :loading}}
              :loading {:after {delay-fn :timeout}}
              :timeout {}}}]
      (rf/reg-machine :a/fn m)
      (rf/dispatch-sync [:a/fn [:go]])
      (is (= :loading (:state (snapshot :a/fn))))
      (let [epoch (get-in (snapshot :a/fn) [:data :rf/after-epoch])]
        ;; Synthetic event carries the fn as the delay-key.
        (rf/dispatch-sync [:a/fn [:rf.machine.timer/after-elapsed delay-fn epoch]])
        (is (= :timeout (:state (snapshot :a/fn)))
            "fn-keyed :after entry resolves and fires")))))

;; ---- :invoke-bearing state with :after — wall-clock guard via :after ----

(deftest after-on-invoke-bearing-state
  (testing ":after on an :invoke-bearing state — firing tears down the spawned child"
    (let [child {:initial :running
                 :states  {:running {:on {:never-fires :done}}
                           :done    {}}}
          parent {:initial :idle
                  :data    {:rf/after-epoch 0}
                  :on-spawn-actions
                  {:rec (fn [data id] (assoc data :pending id))}
                  :states
                  {:idle {:on {:go :authenticating}}
                   :authenticating
                   {:invoke {:machine-id :child/auth
                             :on-spawn   :rec}
                    :after  {30000 :timed-out}
                    :on    {:auth/succeeded :authenticated}}
                   :authenticated {}
                   :timed-out     {}}}]
      (rf/reg-machine :child/auth child)
      (rf/reg-machine :sup/atimeout parent)
      (rf/dispatch-sync [:sup/atimeout [:go]])
      (let [child-id (get-in (frame-db) [:rf/spawned :sup/atimeout [:authenticating]])
            epoch    (get-in (snapshot :sup/atimeout) [:data :rf/after-epoch])]
        (is (some? child-id) "spawn slot bound")
        (is (some? (get-in (frame-db) [:rf/machines child-id]))
            "child snapshot exists")
        (rf/dispatch-sync [:sup/atimeout [:rf.machine.timer/after-elapsed 30000 epoch]])
        (is (= :timed-out (:state (snapshot :sup/atimeout)))
            "parent transitioned via :after firing")
        (is (nil? (get-in (frame-db) [:rf/machines child-id]))
            "child machine destroyed via standard exit cascade")))))

;; ---- fn-form :after exception observability (rf2-c1tnr) -------------------

(deftest after-fn-form-throw-surfaces-trace
  (testing "fn-form :after that throws emits :rf.error/machine-after-fn-threw"
    ;; Pre-rf2-c1tnr the throw was silently caught and the resolution
    ;; returned [nil nil], surfacing only as :rf.warning/no-clock-
    ;; configured downstream with no signal that the fn itself blew up.
    (let [captured (atom [])
          listener (fn [ev] (swap! captured conj ev))
          delay-fn (fn [_snapshot]
                     (throw (ex-info "fn-form delay blew up" {:where :test})))]
      (re-frame.trace/register-trace-cb! ::after-fn-trace listener)
      (try
        (rf/reg-machine :a/throws
                        {:initial :idle
                         :data    {:rf/after-epoch 0}
                         :states  {:idle    {:on    {:go :running}}
                                   :running {:after {delay-fn :timeout}}
                                   :timeout {}}})
        (rf/dispatch-sync [:a/throws [:go]])
        (let [errors (filter #(= :rf.error/machine-after-fn-threw
                                 (:operation %))
                             @captured)]
          (is (seq errors)
              "fn-form throw surfaces as :rf.error/machine-after-fn-threw")
          (is (every? #(= :error (:op-type %)) errors)
              "the trace event has :op-type :error")
          (when-let [first-err (first errors)]
            (is (some? (-> first-err :tags :exception))
                ":exception slot is populated under :tags")
            ;; Per Spec 009 §Error event shape, `:recovery` is hoisted
            ;; off `:tags` to the envelope top-level.
            (is (= :no-clock-configured (:recovery first-err))
                ":recovery hoisted to the envelope top-level")))
        (finally
          (re-frame.trace/remove-trace-cb! ::after-fn-trace))))))
