(ns re-frame.after-test
  "Per Spec 005 §Delayed :after transitions.

  State-level :after timer semantics:
    - Flat map {<delay> <transition>} per state.
    - Delay key is one of: pos-int? literal, subscription vector, fn.
    - Multiple :after entries per state run independently from state entry.
    - Guards on :after suppress the transition without exiting; siblings
      continue (per Spec 005 §Multi-stage interaction with :guard).
    - Race: whichever transition fires first wins; others go stale via
      the per-machine :rf/after-epoch counter.
    - A state with :after but no :spawn is a pure timed-transition state.
    - The first-class EP-0029 A4 :timeout / :on-timeout grammar desugars
      onto :after; timeout_cljs_test.cljc covers it, and the rejected
      :timeout-ms slot on :spawn / :spawn-all, on both hosts.

  These JVM tests dispatch the synthetic
  [:rf.machine.timer/after-elapsed delay-key epoch decl-path] event
  manually so the verification is deterministic without depending on
  setTimeout firing. The decl-path is contractual — the runtime always
  emits the 4-element shape (Spec 005 §Hierarchy interaction).
  machines_after_cljs_test.cljs covers the same semantics on the CLJS
  runtime, and after_fire_reap_cljs_test.cljc drives the host-clock
  callback the timer installs."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.subs]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; runtime-db / snapshot lookup via the shared machines test-support.
(def ^:private frame-db rf.machines.test-support/runtime-db)
(def ^:private snapshot rf.machines.test-support/snapshot)

;; ---- same-tick tie-break: first-fired advances epoch, slower drops stale --
;;
;; XState parity: the STEP ALGORITHM's determinism of
;; simultaneously-enabled transitions.
;;
;; XState v5 / SCXML §3.13 resolve simultaneously-enabled transitions by
;; DOCUMENT ORDER (earlier-listed wins). re-frame2 DELIBERATELY diverges
;; here (per Spec 005 §Multiple :after per state): `:after` timers are real
;; HOST-CLOCK deferred events, not entries in a synchronous in-engine queue,
;; so there is no cross-timer arbitration. The `:after` map is keyed by
;; delay, so two entries with the SAME delay are impossible (map keys
;; dedupe). The only race is two entries with DIFFERENT delays whose host
;; callbacks land in the SAME scheduler tick — each fires as a SEPARATE
;; synthetic timer-elapsed event. The observable contract is
;; first-fired-wins, the rest drop stale: the first event to dequeue drives
;; the transition, the exit advances the per-path epoch, and every other
;; same-tick timer's event then carries a now-stale epoch and drops
;; (:rf.machine.timer/stale-after). No double-transition.
;;
;; This test PINS that deliberate non-guarantee externally. Two timers at
;; DIFFERENT delays (5000 + 30000) are scheduled at one entry (same epoch);
;; we capture that epoch, then dispatch BOTH synthetic events back-to-back
;; — the in-test analogue of two host callbacks landing in the same tick.
;; The 5000ms event (modelled as the first to dequeue) wins; the 30000ms
;; event arrives carrying the pre-transition epoch and drops as stale.

(deftest after-same-tick-first-fired-wins-slower-drops-stale
  (testing "two :after timers (DIFFERENT delays) whose callbacks land in the
            same tick: first-fired advances the epoch + transitions; the
            slower one drops stale — no double-transition"
    (let [m {:initial :idle
             :data    {}
             :states
             {:idle    {:on {:fetch :loading}}
              :loading {:after {5000  :warn
                                30000 :timeout}
                        :on    {:loaded :ready}}
              :warn    {}        ;; terminal — proves NO further transition
              :timeout {}
              :ready   {}}}
          traces (atom [])]
      (rf/reg-machine :a/tiebreak m)
      (rf/register-listener! :trace ::tb (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:a/tiebreak [:fetch]])
      (is (= :loading (:state (snapshot :a/tiebreak))))
      ;; Both timers were scheduled at THIS entry's epoch — the same-tick
      ;; precondition: neither has fired yet, so both carry `epoch`.
      (let [epoch (get-in (snapshot :a/tiebreak) [:data :rf/after-epoch [:loading]])]
        (is (= 1 epoch) "both timers carry the entry epoch (none fired yet)")
        ;; First host callback to dequeue (the 5000ms deadline) — fires,
        ;; transitions :loading → :warn, and the exit advances the epoch.
        (rf/dispatch-sync [:a/tiebreak [:rf.machine.timer/after-elapsed 5000 epoch [:loading]]])
        (is (= :warn (:state (snapshot :a/tiebreak)))
            "first-fired timer wins → :warn")
        (is (not= epoch
                  (get-in (snapshot :a/tiebreak) [:data :rf/after-epoch [:loading]]))
            "the winning transition advanced the [:loading] per-path epoch")
        (is (some #(and (= :rf.machine.timer/fired (:operation %))
                        (true?  (:fired? (:tags %)))
                        (= 5000 (:delay (:tags %))))
                  @traces)
            "first timer emits :fired? true")
        ;; The slower 30000ms callback lands in the SAME tick but dequeues
        ;; AFTER the transition. It carries the pre-transition `epoch`, which
        ;; no longer matches — it MUST drop stale, NOT drive a second
        ;; transition into :timeout.
        (reset! traces [])
        (rf/dispatch-sync [:a/tiebreak [:rf.machine.timer/after-elapsed 30000 epoch [:loading]]])
        (is (= :warn (:state (snapshot :a/tiebreak)))
            "slower same-tick timer drops stale — NO double-transition to :timeout")
        (is (some #(and (= :rf.machine.timer/stale-after (:operation %))
                        (= 30000 (:delay (:tags %))))
                  @traces)
            "the slower timer emits :stale-after (deliberate non-guarantee, pinned)"))
      (rf/unregister-listener! :trace ::tb))))

;; ---- a guard-suppressed :after leaves its sibling timers live -------------
;;
;; The runtime counterpart of after-value-forms-test's pure (d) and (g) cases:
;; a guard-suppressed firing neither transitions nor advances the epoch, and a
;; sibling timer scheduled at the same epoch still fires. Each row is one
;; suppressing value form at the 5000 key.

(deftest after-guard-suppression-leaves-sibling-timers-live
  (doseq [[label machine-id machine entry-event]
          [["a single guarded map whose guard fails"
            :a/guard
            {:initial :idle
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
            [:fetch]]
           ["a guarded candidate vector whose every guard fails, with no fallback"
            :a/gv-allfail
            {:initial :idle
             :data    {:a? false :b? false}
             :guards  {:a? (fn [{:keys [data]}] (:a? data))
                       :b? (fn [{:keys [data]}] (:b? data))}
             :states
             {:idle    {:on {:go :loading}}
              :loading {:after {5000  [{:guard :a? :target :x}
                                       {:guard :b? :target :y}]
                                30000 :timeout}}
              :x       {}
              :y       {}
              :timeout {}}}
            [:go]]]]
    (testing label
      (let [traces (atom [])]
        (rf/reg-machine machine-id machine)
        (rf/register-listener! :trace ::suppressed (fn [ev] (swap! traces conj ev)))
        (rf/dispatch-sync [machine-id entry-event])
        (let [epoch (get-in (snapshot machine-id) [:data :rf/after-epoch [:loading]])]
          (rf/dispatch-sync [machine-id [:rf.machine.timer/after-elapsed 5000 epoch [:loading]]])
          (is (= :loading (:state (snapshot machine-id)))
              "the guard-suppressed :after does not transition")
          (is (= epoch (get-in (snapshot machine-id) [:data :rf/after-epoch [:loading]]))
              "the guard-suppressed :after does not advance the epoch")
          (is (some #(and (= :rf.machine.timer/fired (:operation %))
                          (false? (:fired? (:tags %)))
                          (= 5000  (:delay (:tags %))))
                    @traces)
              "a :fired? false trace marks the suppression")
          ;; The 30000ms sibling timer carries the same, unchanged epoch.
          (rf/dispatch-sync [machine-id [:rf.machine.timer/after-elapsed 30000 epoch [:loading]]])
          (is (= :timeout (:state (snapshot machine-id)))
              "the sibling :after timer is still live and transitions on its own"))
        (rf/unregister-listener! :trace ::suppressed)))))

;; ---- hierarchy: parent :after survives a child-only transition -----------
;;
;; Per Spec 005 §Hierarchy interaction (the normative external contract at
;; 005:1638): "leaf-only sibling transitions inside the same parent MUST
;; NOT cause that parent's pending :after timer to fire as stale on its
;; next match." The per-decl-path epoch model satisfies this: it bumps ONLY
;; the exited/entered nodes, leaving the live parent's in-flight timer
;; untouched.

(deftest after-parent-survives-child-sibling-transition
  (testing "a parent's :after stays live across a child-only sibling
            transition where the child ALSO declares :after"
    (let [m {:initial :p
             :data    {}
             :states
             {:p {:initial :a
                  :after   {30000 :timed-out}     ;; parent hard-timeout
                  :states  {:a {:after {5000 [:a-warn]}   ;; child progress timer (root-level warn state — vector target)
                               :on    {:next :b}}
                            :b {:after {7000 [:b-warn]}}}
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
        (is (= [:timed-out] (:state (snapshot :a/hier)))
            "parent :after fires (NOT stale) after a child-only transition")))))

(deftest after-stale-child-timer-after-sibling-transition
  (testing "the OLD child :after timer goes stale after a sibling transition"
    (let [m {:initial :p
             :data    {}
             :states
             {:p {:initial :a
                  :after   {30000 :timed-out}
                  :states  {:a {:after {5000 [:a-warn]} :on {:next :b}}
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
        (rf/register-listener! :trace ::h2 (fn [ev] (swap! traces conj ev)))
        ;; Fire :a's old timer (carried at its pre-exit epoch + decl-path).
        (rf/dispatch-sync [:a/hier2 [:rf.machine.timer/after-elapsed
                                     5000 a-epoch [:p :a]]])
        (is (= [:p :b] (:state (snapshot :a/hier2)))
            "stale child :a timer does NOT transition after the sibling move")
        (is (some #(= :rf.machine.timer/stale-after (:operation %)) @traces)
            ":stale-after trace emitted for the exited child's timer")
        (rf/unregister-listener! :trace ::h2)))))

;; ---- fn-form delay (computed once at entry) -------------------------------

(deftest after-fn-form-delay
  (testing "(fn [snap] ms) delay form: invoked once at state entry"
    (let [;; Per spec, fn delays use the ORIGINAL fn key for synthetic-event
          ;; lookup. This test exercises pick-after-transition's lookup
          ;; with a fn delay-key. The delay-fn receives a single
          ;; context-map arg `{:snapshot ...}`.
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

(deftest after-on-spawn-bearing-state-tears-down-child
  (testing ":after on a :spawn-bearing state — firing tears down the spawned child"
    (let [child {:initial :running
                 :states  {:running {:on {:never-fires :done}}
                           :done    {}}}
          parent {:initial :idle
                  :data    {}
                  :states
                  {:idle {:on {:go :authenticating}}
                   :authenticating
                   {:spawn {:machine-id :child/auth}
                    :after  {30000 :timed-out}
                    :on    {:auth/succeeded :authenticated}}
                   :authenticated {}
                   :timed-out     {}}}]
      (rf/reg-machine :child/auth child)
      (rf/reg-machine :sup/atimeout parent)
      (rf/dispatch-sync [:sup/atimeout [:go]])
      (let [child-id (get-in (frame-db) [:rf.runtime/machines :spawned :sup/atimeout [:authenticating]])
            epoch    (get-in (snapshot :sup/atimeout) [:data :rf/after-epoch [:authenticating]])]
        (is (some? child-id) "spawn slot bound")
        (is (some? (get-in (frame-db) [:rf.runtime/machines :snapshots child-id]))
            "child snapshot exists")
        (rf/dispatch-sync [:sup/atimeout [:rf.machine.timer/after-elapsed 30000 epoch [:authenticating]]])
        (is (= :timed-out (:state (snapshot :sup/atimeout)))
            "parent transitioned via :after firing")
        (is (nil? (get-in (frame-db) [:rf.runtime/machines :snapshots child-id]))
            "child machine destroyed via standard exit cascade")))))

;; ---- fn-form :after exception observability -------------------------------

(deftest after-fn-form-throw-surfaces-trace
  (testing "fn-form :after that throws emits :rf.error/machine-after-fn-threw"
    ;; A fn-form :after delay that throws surfaces the failure: the error
    ;; arm emits :rf.error/machine-after-fn-threw and the skipped timer
    ;; reports :rf.error/machine-bad-after-delay downstream, so the blown-up fn is
    ;; observable rather than silently swallowed.
    (let [delay-fn (fn [_ctx]
                     (throw (ex-info "fn-form delay blew up" {:where :test})))]
      ;; Shared `with-trace-capture` — guaranteed unregister in a `finally`,
      ;; no hand-rolled register/try/finally.
      (rf.machines.test-support/with-trace-capture captured
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
            (is (= :skipped (:recovery first-err))
                ":recovery hoisted to the envelope top-level")))))))

;; ---- sub-cache ref-count balance on bad-delay early-return ----------------

(defn- default-sub-cache []
  @(:sub-cache (rf.frame/frame :rf/default)))

(deftest after-sub-vec-bad-delay-does-not-leak-subscription
  ;; `schedule-after-timer!` resolves a subscription-vector `:after` delay
  ;; by calling `subs/subscribe`, which bumps the sub-cache ref-count BEFORE
  ;; we know whether the resolved value is positive. When the resolved value
  ;; is nil / 0 / negative, the bad-delay branch emits
  ;; :rf.error/machine-bad-after-delay AND unsubscribes, so no sub-cache slot
  ;; leaks even though no entry is stored in `after-timers` (the only
  ;; cancellation path that would otherwise drop the ref).
  ;;
  ;; Sub-cache disposal is synchronous on derefer-count → 0, so we observe
  ;; the cache state without timing.
  (testing "sub-vec :after delay resolving to 0 unsubscribes (no sub-cache leak)"
    (rf/reg-event :a/seed-bad (fn [{:keys [db]} _] {:db (assoc db :timeout-config 0)}))
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
      (rf/register-listener! :trace ::no-clock (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:a/sub-bad [:go]])
      (rf/unregister-listener! :trace ::no-clock)
      (is (some (fn [ev]
                  (and (= :rf.error/machine-bad-after-delay (:operation ev))
                       (= :sub (-> ev :tags :delay-source))))
                @traces)
          ":rf.error/machine-bad-after-delay emitted for the bad delay")
      (is (not (contains? (default-sub-cache) [:a/timeout-config-0]))
          "sub-cache has no leaked entry for the resolved-to-0 :after sub")))
  (testing "sub-vec :after delay resolving to nil also unsubscribes"
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
          "sub-cache has no leaked entry for the resolved-to-nil :after sub")))
  (testing "repeated bad-delay schedules do not accumulate ref-count"
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
          "sub-cache remains clean after 5 bad-delay schedules"))))

;; ---- sub-vec :after exception observability arms --------------------------
;;
;; `timer.cljc` has three error-emit arms in :after-delay resolution:
;; - :rf.error/machine-after-fn-threw     (fn-form throw on `(delay-key snapshot)`)
;; - :rf.error/machine-after-sub-threw    (sub-deref throw on `@reaction`)
;; - :rf.error/machine-after-watch-failed (add-watch throw)
;;
;; The fn-form arm is pinned above by `after-fn-form-throw-surfaces-trace`.
;; The other two arms are the SAFETY NET against silently swallowing a
;; deref-throw or an add-watch-throw: each surfaces the failure as its own
;; error trace rather than returning [nil nil] and showing up only as
;; `:rf.error/machine-bad-after-delay` downstream with no signal the
;; underlying reactive surface blew up.
;;
;; These tests pin those two cousin arms at the trace-emit boundary —
;; mirroring the fn-form test's shape exactly so swallowing the failure on
;; either arm fails at a clear test name.

(deftest after-sub-vec-deref-throw-surfaces-trace
  (testing "sub-vec :after whose @reaction throws emits
            :rf.error/machine-after-sub-threw with :rf.sub/id +
            :exception slots and :recovery :skipped"
    ;; A deref throw on the reaction surfaces the underlying sub failure
    ;; through the error arm rather than returning [nil nil] and showing up
    ;; only as :rf.error/machine-bad-after-delay.
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
    (let [throw-msg "reaction deref blew up"
          ;; Reify that satisfies the IDeref shape but throws on deref.
          throwing-reaction (reify clojure.lang.IDeref
                              (deref [_]
                                (throw (ex-info throw-msg {:where :test}))))]
      ;; Shared `with-trace-capture`.
      (rf.machines.test-support/with-trace-capture captured
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
                        ([_query-v _opts] throwing-reaction))]
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
            (is (= :s/well-formed (-> first-err :tags :rf.sub/id))
                ":rf.sub/id slot names the offending subscription
                 (first element of the :after delay-key vector)")
            (is (= [:s/well-formed] (-> first-err :tags :rf.sub/query-v))
                ":rf.sub/query-v carries the full subscription vector")
            ;; Per Spec 009 §Error event shape, `:recovery` is hoisted
            ;; off `:tags` to the envelope top-level.
            (is (= :skipped (:recovery first-err))
                ":recovery :skipped hoisted to top-level")))))))

(deftest after-sub-vec-watch-failure-surfaces-trace
  (testing "sub-vec :after where add-watch on the reaction
            throws emits :rf.error/machine-after-watch-failed with
            :rf.sub/id + :exception slots and :recovery :static-delay"
    ;; An add-watch throw is made observable: without the error arm the
    ;; sub-changed re-resolution watcher would not fire (so dynamic delays
    ;; would silently stop re-resolving) without any signal. This arm
    ;; surfaces the failure.
    ;;
    ;; To trigger the arm reliably we shadow `clojure.core/add-watch`
    ;; over the schedule path. The shadow throws once for the
    ;; :after-watch key (the add-watch install site in machines/timer.cljc) and
    ;; falls through otherwise — so the runtime's other add-watch call
    ;; sites (substrate, late-bind, etc.) are not disturbed.
    (let [real-add     add-watch
          ;; A real watchable sub so subscribe + deref succeed; only
          ;; the add-watch on the resulting reaction throws.
          throw-add    (fn [target key f]
                         (if (and (vector? key)
                                  (= :re-frame.machines.timer/after-watch
                                     (first key)))
                           (throw (ex-info "add-watch blew up on after-watch"
                                           {:where :test :key key}))
                           (real-add target key f)))]
      ;; Shared `with-trace-capture`.
      (rf.machines.test-support/with-trace-capture captured
        ;; A well-behaved sub returning a positive delay — subscribe
        ;; succeeds, deref returns 1000 (so the bad-delay branch
        ;; doesn't short-circuit before reaching add-watch).
        (rf/reg-event :w/seed (fn [{:keys [db]} _] {:db (assoc db :delay-ms 1000)}))
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
            (is (= :s/well-behaved (-> first-err :tags :rf.sub/id))
                ":rf.sub/id slot names the subscription whose reaction
                 could not be watched")
            (is (= [:s/well-behaved] (-> first-err :tags :rf.sub/query-v))
                ":rf.sub/query-v carries the full subscription vector")
            (is (= :w/throws-machine (-> first-err :tags :actor-id))
                ":actor-id slot names the owning LIVE actor")
            ;; Per Spec 009 §Error event shape, `:recovery` is hoisted.
            (is (= :static-delay (:recovery first-err))
                ":recovery :static-delay hoisted to top-level — the
                 timer still scheduled, just without dynamic-delay
                 re-resolution")))))))
