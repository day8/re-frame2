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
    - No-invoke variant: a state with :after but no :spawn is a pure
      timed-transition state.
    - :timeout-ms / :on-timeout on :spawn / :spawn-all is REMOVED;
      registration throws :rf.error/spawn-timeout-ms-removed.

  These JVM tests dispatch the synthetic
  [:rf.machine.timer/after-elapsed delay-key epoch] event manually so
  the verification is deterministic without depending on setTimeout
  firing. The CLJS runtime path for actual wall-clock scheduling is
  exercised by machines_cljs_test.cljs."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.subs.cache :as subs-cache]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- frame-db [] (rf/get-frame-db :rf/default))
(defn- snapshot [machine-id] (get-in (frame-db) [:rf/machines machine-id]))

;; ---- registration-time rejection of :timeout-ms ---------------------------

(deftest spawn-timeout-ms-rejected
  (testing ":timeout-ms on :spawn fails registration"
    (let [bad {:initial :idle
               :states  {:idle {:on {:go :r}}
                         :r    {:spawn {:machine-id :stub
                                         :timeout-ms 1000
                                         :on-timeout [:never]}}}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"spawn-timeout-ms-removed"
                            (rf/reg-machine :rmv/bad bad))
          "registration emits the migration error category")))
  (testing ":on-timeout alone on :spawn is also rejected"
    (let [bad {:initial :idle
               :states  {:idle {:on {:go :r}}
                         :r    {:spawn {:machine-id :stub
                                         :on-timeout [:never]}}}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"spawn-timeout-ms-removed"
                            (rf/reg-machine :rmv/bad2 bad)))))
  (testing ":timeout-ms on :spawn-all is rejected"
    (let [bad {:initial :idle
               :states  {:idle {:on {:go :h}}
                         :h    {:spawn-all
                                {:children        [{:id :a :machine-id :stub}]
                                 :join            :all
                                 :on-child-done   :done
                                 :on-child-error  :failed
                                 :on-all-complete [:done!]
                                 :timeout-ms      5000
                                 :on-timeout      [:to]}}}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"spawn-timeout-ms-removed"
                            (rf/reg-machine :rmv/bad3 bad))))))

;; ---- single-delay :after on entry / fire -----------------------------------

(deftest after-single-delay
  (testing ":after schedules with current epoch on entry; fires on synthetic timer event"
    (let [m {:initial :idle
             :data    {}
             :states
             {:idle    {:on {:fetch :loading}}
              :loading {:after {5000 :timeout}
                        :on    {:loaded :ready}}
              :timeout {}
              :ready   {}}}
          traces (atom [])]
      (rf/reg-machine :a/single m)
      (rf/register-listener! ::s (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:a/single [:fetch]])
      (let [s (snapshot :a/single)]
        (is (= :loading (:state s)))
        ;; Per Spec 005 §Hierarchy interaction the epoch is per-decl-path.
        (is (= 1 (get-in s [:data :rf/after-epoch [:loading]]))))
      (is (some #(and (= :rf.machine.timer/scheduled (:operation %))
                       (= 5000     (:delay (:tags %)))
                       (= :literal (:delay-source (:tags %))))
                 @traces)
          ":scheduled trace with :delay-source :literal")
      (rf/dispatch-sync [:a/single [:rf.machine.timer/after-elapsed 5000 1 [:loading]]])
      (is (= :timeout (:state (snapshot :a/single)))
          "matching-epoch firing transitions :loading → :timeout")
      (rf/unregister-listener! ::s))))

;; ---- multi-stage :after — warn at 5s, fail at 30s -------------------------

(deftest after-multi-stage
  (testing "multiple :after entries run independently from entry-time"
    (let [m {:initial :idle
             :data    {}
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
      (let [epoch (get-in (snapshot :a/multi) [:data :rf/after-epoch [:loading]])]
        ;; The 5000ms timer fires first.
        (rf/dispatch-sync [:a/multi [:rf.machine.timer/after-elapsed 5000 epoch [:loading]]])
        (is (= :warn (:state (snapshot :a/multi)))
            "5s timer fires; transition to :warn")
        ;; The original 30000ms timer (epoch 1) is now stale, but at this
        ;; point the snapshot has moved to :warn (which has no :after);
        ;; the [:loading] node is no longer on the active path —
        ;; pick-after-transition surfaces stale-after.
        (rf/dispatch-sync [:a/multi [:rf.machine.timer/after-elapsed 30000 epoch [:loading]]])
        (is (= :warn (:state (snapshot :a/multi)))
            "stale 30s firing does not transition")))))

;; ---- :guard suppresses one :after; siblings continue ---------------------

(deftest after-guard-suppresses-one-siblings-continue
  (testing "guard returning false suppresses the transition without exiting; sibling timers continue"
    (let [m {:initial :idle
             :data    {:slow? false}
             :guards  {:slow? (fn [{:keys [data]}] (:slow? data))}
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
      (rf/register-listener! ::g (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:a/guard [:fetch]])
      (let [epoch (get-in (snapshot :a/guard) [:data :rf/after-epoch [:loading]])]
        ;; 5s fires; guard :slow? returns false → suppressed.
        (rf/dispatch-sync [:a/guard [:rf.machine.timer/after-elapsed 5000 epoch [:loading]]])
        (is (= :loading (:state (snapshot :a/guard)))
            "guard-suppressed :after must not transition")
        (is (= epoch (get-in (snapshot :a/guard) [:data :rf/after-epoch [:loading]]))
            "guard-suppressed :after must not advance epoch")
        (is (some #(and (= :rf.machine.timer/fired (:operation %))
                         (false? (:fired? (:tags %)))
                         (= 5000  (:delay (:tags %))))
                   @traces)
            ":fired? false trace emitted on guard suppression")
        ;; The 30000ms sibling timer is still live (same epoch) — fire it.
        (rf/dispatch-sync [:a/guard [:rf.machine.timer/after-elapsed 30000 epoch [:loading]]])
        (is (= :timeout (:state (snapshot :a/guard)))
            "sibling :after timer continues and transitions on its own"))
      (rf/unregister-listener! ::g))))

;; ---- no-invoke variant (splash screen) -----------------------------------

(deftest after-no-invoke-splash
  (testing "a state with :after but no :spawn is a pure timed-transition state"
    (let [m {:initial :splash
             :data    {}
             :states
             {:splash {:after {3000 :main}
                       :on    {:skip :main}}
              :main   {}}}]
      (rf/reg-machine :a/splash m)
      ;; First dispatch synthesises the snapshot at :splash.
      (rf/dispatch-sync [:a/splash [:noop]])
      (is (= :splash (:state (snapshot :a/splash))))
      (let [epoch (get-in (snapshot :a/splash) [:data :rf/after-epoch [:splash]])]
        (rf/dispatch-sync [:a/splash [:rf.machine.timer/after-elapsed 3000 epoch [:splash]]])
        (is (= :main (:state (snapshot :a/splash)))
            ":after fired transition with no :spawn spawn")))))

;; ---- hierarchy: parent :after survives a child-only transition -----------
;;
;; Per Spec 005 §Hierarchy interaction (the normative external contract at
;; 005:1638): "leaf-only sibling transitions inside the same parent MUST
;; NOT cause that parent's pending :after timer to fire as stale on its
;; next match." A single per-machine epoch could not satisfy this — a
;; child sibling transition that itself bumps the shared counter staled the
;; still-active parent's in-flight timer. The per-decl-path epoch model
;; bumps ONLY the exited/entered nodes, leaving the live parent untouched.

(deftest after-parent-survives-child-sibling-transition
  (testing "a parent's :after stays live across a child-only sibling
            transition where the child ALSO declares :after (rf2-j9hnu)"
    (let [m {:initial :p
             :data    {}
             :states
             {:p {:initial :a
                  :after   {30000 :timed-out}     ;; parent hard-timeout
                  :states  {:a {:after {5000 :a-warn}   ;; child progress timer
                               :on    {:next :b}}
                            :b {:after {7000 :b-warn}}}
                  :on {:reset :p}}
              :timed-out {}
              :a-warn    {}
              :b-warn    {}}}]
      (rf/reg-machine :a/hier m)
      ;; Bootstrap → [:p :a]: both the parent's and child :a's :after
      ;; schedule, each at its own per-path epoch.
      (rf/dispatch-sync [:a/hier [:noop]])
      (is (= [:p :a] (:state (snapshot :a/hier))))
      (let [parent-epoch (get-in (snapshot :a/hier) [:data :rf/after-epoch [:p]])
            a-epoch      (get-in (snapshot :a/hier) [:data :rf/after-epoch [:p :a]])]
        (is (= 1 parent-epoch) "parent :after scheduled at its own epoch")
        (is (= 1 a-epoch) "child :a :after scheduled at its own epoch")

        ;; Child sibling transition :a → :b. The parent is NOT exited; only
        ;; the child levels are. The parent's per-path epoch MUST be
        ;; untouched; the child :a's MUST be bumped.
        (rf/dispatch-sync [:a/hier [:next]])
        (is (= [:p :b] (:state (snapshot :a/hier))))
        (is (= parent-epoch
               (get-in (snapshot :a/hier) [:data :rf/after-epoch [:p]]))
            "parent's per-path epoch is UNCHANGED by the child-only transition")
        (is (not= a-epoch
                  (get-in (snapshot :a/hier) [:data :rf/after-epoch [:p :a]]))
            "child :a's per-path epoch advanced on its exit")

        ;; The parent's in-flight timer (scheduled at parent-epoch, decl-path
        ;; [:p]) now fires. It MUST be live — the parent is still active —
        ;; and drive the transition to :timed-out.
        (rf/dispatch-sync [:a/hier [:rf.machine.timer/after-elapsed
                                    30000 parent-epoch [:p]]])
        (is (= :timed-out (:state (snapshot :a/hier)))
            "parent :after fires (NOT stale) after a child-only transition — rf2-j9hnu")))))

(deftest after-stale-child-timer-after-sibling-transition
  (testing "the OLD child :after timer goes stale after a sibling transition"
    (let [m {:initial :p
             :data    {}
             :states
             {:p {:initial :a
                  :after   {30000 :timed-out}
                  :states  {:a {:after {5000 :a-warn} :on {:next :b}}
                            :b {}}
                  :on {:reset :p}}
              :timed-out {}
              :a-warn    {}}}
          traces (atom [])]
      (rf/reg-machine :a/hier2 m)
      (rf/dispatch-sync [:a/hier2 [:noop]])
      (let [a-epoch (get-in (snapshot :a/hier2) [:data :rf/after-epoch [:p :a]])]
        (rf/dispatch-sync [:a/hier2 [:next]])
        (is (= [:p :b] (:state (snapshot :a/hier2))))
        (rf/register-listener! ::h2 (fn [ev] (swap! traces conj ev)))
        ;; Fire :a's old timer (carried at its pre-exit epoch + decl-path).
        (rf/dispatch-sync [:a/hier2 [:rf.machine.timer/after-elapsed
                                     5000 a-epoch [:p :a]]])
        (is (= [:p :b] (:state (snapshot :a/hier2)))
            "stale child :a timer does NOT transition after the sibling move")
        (is (some #(= :rf.machine.timer/stale-after (:operation %)) @traces)
            ":stale-after trace emitted for the exited child's timer")
        (rf/unregister-listener! ::h2)))))

;; ---- race: real event beats timer; stale firing must not transition ------

(deftest after-race-real-event-wins
  (testing "real event arrives before timer; in-flight timer fires stale and is suppressed"
    (let [m {:initial :idle
             :data    {}
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
      (rf/register-listener! ::r (fn [ev] (swap! traces conj ev)))
      ;; Stale firing from epoch 1 (the :loading visit).
      (rf/dispatch-sync [:a/race [:rf.machine.timer/after-elapsed 5000 1 [:loading]]])
      (is (= :ready (:state (snapshot :a/race)))
          "stale firing must not transition")
      (is (some #(and (= :rf.machine.timer/stale-after (:operation %))
                       (= 5000 (:delay (:tags %))))
                 @traces)
          ":stale-after trace emitted")
      (rf/unregister-listener! ::r))))

;; ---- fn-form delay (computed once at entry) -------------------------------

(deftest after-fn-form-delay
  (testing "(fn [snap] ms) delay form: invoked once at state entry"
    (let [;; Per spec, fn delays use the ORIGINAL fn key for synthetic-event
          ;; lookup. This test exercises pick-after-transition's lookup
          ;; with a fn delay-key. Per rf2-grw4i / rf2-v0rrr the delay-fn
          ;; receives a single context-map arg `{:snapshot ...}`.
          delay-fn (fn [_ctx] 7000)
          m {:initial :idle
             :data    {}
             :states
             {:idle    {:on {:go :loading}}
              :loading {:after {delay-fn :timeout}}
              :timeout {}}}]
      (rf/reg-machine :a/fn m)
      (rf/dispatch-sync [:a/fn [:go]])
      (is (= :loading (:state (snapshot :a/fn))))
      (let [epoch (get-in (snapshot :a/fn) [:data :rf/after-epoch [:loading]])]
        ;; Synthetic event carries the fn as the delay-key.
        (rf/dispatch-sync [:a/fn [:rf.machine.timer/after-elapsed delay-fn epoch [:loading]]])
        (is (= :timeout (:state (snapshot :a/fn)))
            "fn-keyed :after entry resolves and fires")))))

;; ---- :spawn-bearing state with :after — wall-clock guard via :after ----

(deftest after-on-invoke-bearing-state
  (testing ":after on a :spawn-bearing state — firing tears down the spawned child"
    (let [child {:initial :running
                 :states  {:running {:on {:never-fires :done}}
                           :done    {}}}
          parent {:initial :idle
                  :data    {}
                  :on-spawn-actions
                  {:rec (fn [{:keys [data id]}] (assoc data :pending id))}
                  :states
                  {:idle {:on {:go :authenticating}}
                   :authenticating
                   {:spawn {:machine-id :child/auth
                             :on-spawn   :rec}
                    :after  {30000 :timed-out}
                    :on    {:auth/succeeded :authenticated}}
                   :authenticated {}
                   :timed-out     {}}}]
      (rf/reg-machine :child/auth child)
      (rf/reg-machine :sup/atimeout parent)
      (rf/dispatch-sync [:sup/atimeout [:go]])
      (let [child-id (get-in (frame-db) [:rf/spawned :sup/atimeout [:authenticating]])
            epoch    (get-in (snapshot :sup/atimeout) [:data :rf/after-epoch [:authenticating]])]
        (is (some? child-id) "spawn slot bound")
        (is (some? (get-in (frame-db) [:rf/machines child-id]))
            "child snapshot exists")
        (rf/dispatch-sync [:sup/atimeout [:rf.machine.timer/after-elapsed 30000 epoch [:authenticating]]])
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
          delay-fn (fn [_ctx]
                     (throw (ex-info "fn-form delay blew up" {:where :test})))]
      (re-frame.trace/register-listener! ::after-fn-trace listener)
      (try
        (rf/reg-machine :a/throws
                        {:initial :idle
                         :data    {}
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
          (re-frame.trace/unregister-listener! ::after-fn-trace))))))

;; ---- sub-cache ref-count balance on bad-delay early-return (rf2-fva6c.1) ---

(defn- default-sub-cache []
  @(:sub-cache (frame/frame :rf/default)))

(deftest after-sub-vec-bad-delay-does-not-leak-subscription
  ;; rf2-fva6c.1: `schedule-after-timer!` resolves a subscription-vector
  ;; `:after` delay by calling `subs/subscribe`, which bumps the sub-cache
  ;; ref-count BEFORE we know whether the resolved value is positive. When
  ;; the resolved value is nil / 0 / negative, the bad-delay branch
  ;; previously emitted :rf.warning/no-clock-configured and short-circuited
  ;; — without unsubscribing. No entry was stored in `after-timers`, so no
  ;; future cancellation path would ever drop the ref. Long-running CLJS
  ;; processes with repeated bad delays accumulated sub-cache slots.
  ;;
  ;; Use grace-period-ms 0 so the paired unsubscribe disposes the slot
  ;; synchronously and we can observe the cache state without timing.
  (testing "sub-vec :after delay resolving to 0 unsubscribes (no sub-cache leak)"
    (try
      (subs-cache/configure! {:grace-period-ms 0})
      (rf/reg-event-db :a/seed-bad (fn [db _] (assoc db :timeout-config 0)))
      (rf/reg-sub :a/timeout-config-0 (fn [db _] (:timeout-config db)))
      (rf/dispatch-sync [:a/seed-bad])
      (let [m {:initial :idle
               :data    {}
               :states
               {:idle    {:on {:go :running}}
                :running {:after {[:a/timeout-config-0] :timeout}}
                :timeout {}}}
            traces (atom [])]
        (rf/reg-machine :a/sub-bad m)
        (rf/register-listener! ::no-clock (fn [ev] (swap! traces conj ev)))
        (rf/dispatch-sync [:a/sub-bad [:go]])
        (rf/unregister-listener! ::no-clock)
        (is (some (fn [ev]
                    (and (= :rf.warning/no-clock-configured (:operation ev))
                         (= :sub (-> ev :tags :delay-source))))
                  @traces)
            ":rf.warning/no-clock-configured emitted for the bad delay")
        (is (not (contains? (default-sub-cache) [:a/timeout-config-0]))
            "sub-cache has no leaked entry for the resolved-to-0 :after sub"))
      (finally
        (subs-cache/configure! {:grace-period-ms 50}))))
  (testing "sub-vec :after delay resolving to nil also unsubscribes"
    (try
      (subs-cache/configure! {:grace-period-ms 0})
      (rf/reg-sub :a/timeout-config-nil (fn [_db _] nil))
      (let [m {:initial :idle
               :data    {}
               :states
               {:idle    {:on {:go :running}}
                :running {:after {[:a/timeout-config-nil] :timeout}}
                :timeout {}}}]
        (rf/reg-machine :a/sub-nil m)
        (rf/dispatch-sync [:a/sub-nil [:go]])
        (is (not (contains? (default-sub-cache) [:a/timeout-config-nil]))
            "sub-cache has no leaked entry for the resolved-to-nil :after sub"))
      (finally
        (subs-cache/configure! {:grace-period-ms 50}))))
  (testing "repeated bad-delay schedules do not accumulate ref-count"
    (try
      (subs-cache/configure! {:grace-period-ms 0})
      (rf/reg-sub :a/timeout-config-neg (fn [_db _] -1))
      (let [m {:initial :idle
               :data    {}
               :states
               {:idle    {:on {:go :running :reset :idle}}
                :running {:after {[:a/timeout-config-neg] :timeout}
                          :on    {:reset :idle}}
                :timeout {}}}]
        (rf/reg-machine :a/sub-neg m)
        ;; Cycle several entries into :running so the schedule path runs
        ;; multiple times. Each entry subscribes; if the unsubscribe is
        ;; missing the ref-count would accumulate.
        (dotimes [_ 5]
          (rf/dispatch-sync [:a/sub-neg [:go]])
          (rf/dispatch-sync [:a/sub-neg [:reset]]))
        (is (not (contains? (default-sub-cache) [:a/timeout-config-neg]))
            "sub-cache remains clean after 5 bad-delay schedules"))
      (finally
        (subs-cache/configure! {:grace-period-ms 50})))))

;; ---- sub-vec :after exception observability arms (rf2-t4uo0) --------------
;;
;; Per rf2-q1z1u F1 (HIGH) — follow-on for rf2-c1tnr.
;;
;; `timer.cljc` has three error-emit arms in :after-delay resolution:
;; - :rf.error/machine-after-fn-threw     (fn-form throw on `(delay-key snapshot)`)
;; - :rf.error/machine-after-sub-threw    (sub-deref throw on `@reaction`)
;; - :rf.error/machine-after-watch-failed (add-watch throw)
;;
;; The fn-form arm is pinned above by `after-fn-form-throw-surfaces-trace`.
;; The other two arms are the SAFETY NET against the silent-swallow
;; class of bug rf2-c1tnr fixed: pre-rf2-c1tnr a deref-throw or an
;; add-watch-throw was silently caught and the resolution returned
;; [nil nil], surfacing only as `:rf.warning/no-clock-configured`
;; downstream with no signal the underlying reactive surface blew up.
;;
;; These tests pin those two cousin arms at the trace-emit boundary —
;; mirroring the fn-form test's shape exactly so a regression that
;; re-introduces the swallow on either arm fails at a clear test name.

(deftest after-sub-vec-deref-throw-surfaces-trace
  (testing "rf2-t4uo0 — sub-vec :after whose @reaction throws emits
            :rf.error/machine-after-sub-threw with :sub-id +
            :exception slots and :recovery :no-clock-configured"
    ;; Pre-rf2-c1tnr the deref throw was silently caught and the
    ;; resolution returned [nil nil], surfacing only as
    ;; :rf.warning/no-clock-configured. The error arm makes the
    ;; underlying sub failure observable.
    ;;
    ;; A USER-SPACE sub body throw is caught by `validate-and-trace`
    ;; in re-frame.subs.memo BEFORE it reaches the timer's `try
    ;; @reaction`; the sub-internal catch emits :rf.error/sub-exception
    ;; and returns nil. The timer's defensive catch arm is for
    ;; framework-internal failures (e.g. a misbehaving substrate's
    ;; reaction reify whose deref throws synchronously without going
    ;; through the sub-internal catch).
    ;;
    ;; To force the arm to fire we shadow `subs/subscribe` over the
    ;; schedule path to return a reify whose deref throws — the timer
    ;; code's `try @reaction (catch ...)` sees the throw directly.
    (let [captured  (atom [])
          listener  (fn [ev] (swap! captured conj ev))
          throw-msg "reaction deref blew up"
          ;; Reify that satisfies the IDeref shape but throws on deref.
          throwing-reaction (reify clojure.lang.IDeref
                              (deref [_]
                                (throw (ex-info throw-msg {:where :test}))))]
      (re-frame.trace/register-listener! ::after-sub-trace listener)
      (try
        (rf/reg-sub :s/well-formed (fn [_db _] 1000))
        (rf/reg-machine
          :s/throws-machine
          {:initial :idle
           :data    {}
           :states  {:idle    {:on    {:go :running}}
                     :running {:after {[:s/well-formed] :timeout}}
                     :timeout {}}})
        (with-redefs [re-frame.subs/subscribe
                      (fn
                        ([_query-v] throwing-reaction)
                        ([_frame-id _query-v] throwing-reaction))]
          (rf/dispatch-sync [:s/throws-machine [:go]]))
        (let [errors (filter #(= :rf.error/machine-after-sub-threw
                                 (:operation %))
                             @captured)]
          (is (seq errors)
              "deref throw surfaces as :rf.error/machine-after-sub-threw")
          (is (every? #(= :error (:op-type %)) errors)
              "the trace event has :op-type :error")
          (when-let [first-err (first errors)]
            (is (some? (-> first-err :tags :exception))
                ":exception slot is populated under :tags")
            (is (= :s/well-formed (-> first-err :tags :sub-id))
                ":sub-id slot names the offending subscription
                 (first element of the :after delay-key vector)")
            ;; Per Spec 009 §Error event shape, `:recovery` is hoisted
            ;; off `:tags` to the envelope top-level.
            (is (= :no-clock-configured (:recovery first-err))
                ":recovery :no-clock-configured hoisted to top-level")))
        (finally
          (re-frame.trace/unregister-listener! ::after-sub-trace))))))

(deftest after-sub-vec-watch-failure-surfaces-trace
  (testing "rf2-t4uo0 — sub-vec :after where add-watch on the reaction
            throws emits :rf.error/machine-after-watch-failed with
            :sub-id + :exception slots and :recovery :static-delay"
    ;; Pre-rf2-c1tnr an add-watch throw was silently swallowed; the
    ;; sub-changed re-resolution watcher would not fire (so dynamic
    ;; delays would silently stop re-resolving) without any signal.
    ;; This arm makes the failure observable.
    ;;
    ;; To trigger the arm reliably we shadow `clojure.core/add-watch`
    ;; over the schedule path. The shadow throws once for the
    ;; :after-watch key (machines/timer.cljc:298 install site) and
    ;; falls through otherwise — so the runtime's other add-watch call
    ;; sites (substrate, late-bind, etc.) are not disturbed.
    (let [captured     (atom [])
          listener     (fn [ev] (swap! captured conj ev))
          real-add     add-watch
          ;; A real watchable sub so subscribe + deref succeed; only
          ;; the add-watch on the resulting reaction throws.
          throw-add    (fn [target key f]
                         (if (and (vector? key)
                                  (= :re-frame.machines.timer/after-watch
                                     (first key)))
                           (throw (ex-info "add-watch blew up on after-watch"
                                           {:where :test :key key}))
                           (real-add target key f)))]
      (re-frame.trace/register-listener! ::after-watch-trace listener)
      (try
        ;; A well-behaved sub returning a positive delay — subscribe
        ;; succeeds, deref returns 1000 (so the bad-delay branch
        ;; doesn't short-circuit before reaching add-watch).
        (rf/reg-event-db :w/seed (fn [db _] (assoc db :delay-ms 1000)))
        (rf/reg-sub :s/well-behaved (fn [db _] (:delay-ms db)))
        (rf/dispatch-sync [:w/seed])
        (rf/reg-machine
          :w/throws-machine
          {:initial :idle
           :data    {}
           :states  {:idle    {:on    {:go :running}}
                     :running {:after {[:s/well-behaved] :timeout}}
                     :timeout {}}})
        (with-redefs [clojure.core/add-watch throw-add]
          (rf/dispatch-sync [:w/throws-machine [:go]]))
        (let [errors (filter #(= :rf.error/machine-after-watch-failed
                                 (:operation %))
                             @captured)]
          (is (seq errors)
              "add-watch throw surfaces as :rf.error/machine-after-watch-failed")
          (is (every? #(= :error (:op-type %)) errors)
              "the trace event has :op-type :error")
          (when-let [first-err (first errors)]
            (is (some? (-> first-err :tags :exception))
                ":exception slot is populated under :tags")
            (is (= :s/well-behaved (-> first-err :tags :sub-id))
                ":sub-id slot names the subscription whose reaction
                 could not be watched")
            (is (= :w/throws-machine (-> first-err :tags :machine-id))
                ":machine-id slot names the owning machine")
            ;; Per Spec 009 §Error event shape, `:recovery` is hoisted.
            (is (= :static-delay (:recovery first-err))
                ":recovery :static-delay hoisted to top-level — the
                 timer still scheduled, just without dynamic-delay
                 re-resolution")))
        (finally
          (re-frame.trace/unregister-listener! ::after-watch-trace))))))
