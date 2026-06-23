(ns re-frame.internal-events-test
  "EP-0029 A6 — public / private `:internal-events`.

  Covers:
    - the declaration shape — a Clojure SET of keywords (the re-frame2
      set-form divergence from XState's array);
    - registration-time fail-loud validation (a malformed `:internal-events`;
      a name declared BOTH internal AND as a public `:on` transition key);
    - the dispatch boundary — an EXTERNAL dispatch of a declared internal
      event is REJECTED (emits `:rf.error/machine-internal-event-external-dispatch`,
      no state change), while an internal `:raise` of the SAME event is
      handled normally within the machine's own run-to-completion logic.

  The dispatch-boundary tests drive the real runtime (`reg-machine` +
  `dispatch-sync`) and read the settled snapshot + captured traces, so they
  exercise the boundary end-to-end: a self-raised internal event drives a
  transition, but an outside caller's dispatch of the same event is refused."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Load the machines facade so its late-bind hooks (incl.
            ;; `:machines/reg-machine`) are registered — `rf/reg-machine`
            ;; routes through them.
            [re-frame.machines]
            [re-frame.machines.internal-events :as internal-events]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.subs]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ^:private snapshot mtest/snapshot)

;; ---- declaration accessor + boundary predicate ----------------------------

(deftest internal-events-accessor
  (testing "internal-events returns the declared SET (or nil when absent)"
    (is (= #{:tick} (internal-events/internal-events {:internal-events #{:tick}})))
    (is (nil? (internal-events/internal-events {:initial :a})))))

(deftest boundary-predicate-recognises-declared-internal-events
  (testing "internal-event-external? is true ONLY for a declared internal event"
    (let [m {:internal-events #{:tick :retry/internal}}]
      (is (true?  (internal-events/internal-event-external? m [:tick])))
      (is (true?  (internal-events/internal-event-external? m [:retry/internal])))
      (is (false? (internal-events/internal-event-external? m [:public-event])))
      (is (true?  (internal-events/internal-event-external? m [:tick :arg]))
          "the first element is the event id — a declared internal id is rejected even with args")
      (is (false? (internal-events/internal-event-external? m [:public-event :arg]))
          "a non-declared id is public even with args")))
  (testing "internal-event-external? is nil-safe and false for a no-internal-events machine"
    (is (false? (internal-events/internal-event-external? {:initial :a} [:tick])))
    (is (false? (internal-events/internal-event-external? {:internal-events #{:tick}} nil)))
    (is (false? (internal-events/internal-event-external? {:internal-events #{:tick}} [])))))

;; ---- registration-time validation (fail-loud) -----------------------------

(defn- reg-error-id [machine]
  (try (rf/reg-machine (keyword "iet" (str (gensym))) machine) nil
       (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))

(deftest internal-events-accepts-valid-set
  (testing "a well-formed :internal-events SET (with a self-raise + :on clause) registers cleanly"
    (is (nil? (reg-error-id
                {:initial :waiting
                 :internal-events #{:tick}
                 :actions {:kick (fn [_] {:fx [[:raise [:tick]]]})}
                 :states {:waiting {:entry :kick
                                    :on {:tick {:target :checking}}}
                          :checking {}}})))))

(deftest internal-events-rejects-vector-form
  (testing "a VECTOR :internal-events (the rejected XState array form) fails loud"
    (is (= :rf.error/machine-bad-internal-events
           (reg-error-id {:initial :a
                          :internal-events [:tick]
                          :states {:a {}}})))))

(deftest internal-events-rejects-non-keyword-member
  (testing "a SET with a non-keyword member fails loud"
    (is (= :rf.error/machine-bad-internal-events
           (reg-error-id {:initial :a
                          :internal-events #{:tick "tock"}
                          :states {:a {}}})))))

(deftest internal-events-rejects-non-set
  (testing "a non-set, non-vector :internal-events (e.g. a keyword) fails loud"
    (is (= :rf.error/machine-bad-internal-events
           (reg-error-id {:initial :a
                          :internal-events :tick
                          :states {:a {}}})))))

(deftest internal-events-handled-via-on-is-not-a-collision
  (testing "declaring :tick internal AND handling it via :on {:tick …} is the
            EXPECTED shape (the EP example) — it registers cleanly"
    (is (nil? (reg-error-id
                {:initial :waiting
                 :internal-events #{:tick}
                 :states {:waiting {:on {:tick {:target :checking}}}
                          :checking {}}})))))

(deftest internal-events-rejects-reserved-framework-event
  (testing "a reserved :rf/* framework event in :internal-events fails loud —
            framework lifecycle traffic can't be made private"
    (is (= :rf.error/machine-internal-event-reserved
           (reg-error-id {:initial :a
                          :internal-events #{:rf.machine/start}
                          :states {:a {}}})))
    (is (= :rf.error/machine-internal-event-reserved
           (reg-error-id {:initial :a
                          :internal-events #{:rf.machine/done}
                          :states {:a {}}})))
    (is (= :rf.error/machine-internal-event-reserved
           (reg-error-id {:initial :a
                          :internal-events #{:tick :rf.machine.spawn/spawned}
                          :states {:a {}}}))
        "a reserved member alongside a legal one is still rejected")))

;; ---- dispatch boundary — external dispatch of a private event is rejected --

(deftest external-dispatch-of-internal-event-rejected
  (testing "an EXTERNAL dispatch of a declared internal event is refused —
            no state change, an error trace is emitted"
    (let [m {:initial :waiting
             :internal-events #{:tick}
             :states {:waiting {:on {:tick {:target :checking}}}
                      :checking {}}}]
      (rf/reg-machine :iet/reject m)
      ;; Boot the machine first (a real public event), then try to drive it
      ;; with the private event from outside.
      (rf/dispatch-sync [:iet/reject [:rf.machine/start]])
      (is (= :waiting (:state (snapshot :iet/reject))) "booted at :waiting")
      (mtest/with-trace-capture captured
        (rf/dispatch-sync [:iet/reject [:tick]])
        (is (= :waiting (:state (snapshot :iet/reject)))
            "the external :tick was REJECTED — the machine stayed at :waiting (no transition)")
        (let [errors (filter #(= :rf.error/machine-internal-event-external-dispatch
                                 (:operation %))
                             @captured)]
          (is (seq errors)
              "the boundary emitted :rf.error/machine-internal-event-external-dispatch")
          (is (every? #(= :error (:op-type %)) errors)
              "the trace event has :op-type :error"))))))

(deftest internal-raise-of-internal-event-handled
  (testing "an INTERNAL :raise of the SAME event IS handled (the boundary
            refuses only the OUTSIDE caller, not the machine's own plumbing)"
    (let [m {:initial :waiting
             :internal-events #{:tick}
             :actions {:kick (fn [_] {:fx [[:raise [:tick]]]})}
             :states {:waiting {:on {:go {:target :armed :action :kick}}}
                      ;; :armed's :entry self-raises :tick, which the
                      ;; machine handles internally → :checking.
                      :armed {:on {:tick {:target :checking}}}
                      :checking {}}}]
      (rf/reg-machine :iet/raise m)
      (rf/dispatch-sync [:iet/raise [:go]])
      (is (= :checking (:state (snapshot :iet/raise)))
          "the internally-raised :tick drove :armed → :checking within the macrostep"))))

(deftest external-dispatch-of-internal-event-cannot-boot
  (testing "an external dispatch of a private event to an UNBOOTED machine
            neither creates the machine nor drives it"
    (let [m {:initial :waiting
             :internal-events #{:tick}
             :states {:waiting {:on {:tick {:target :checking}}}
                      :checking {}}}]
      (rf/reg-machine :iet/no-boot m)
      (rf/dispatch-sync [:iet/no-boot [:tick]])
      (is (nil? (snapshot :iet/no-boot))
          "the private event was rejected at the boundary BEFORE boot — no snapshot installed"))))
