(ns re-frame.machine-cofx-attach-test
  "Per EP-0017 slice-B.9 (rf2-mjmxgb) — machine consumer attachment.

  Covers the three slice-B.9 pieces and their adversarial corners:

    1. INLINE-FN RESTRICTION — `:rf.cofx/requires` may live ONLY on a named
       `:guards` / `:actions` entry map. An inline declaration (on an `:on`
       slot, or on a `:guards` entry that is a map with no `:fn`) fails
       registration with `:rf.error/machine-cofx-requires-inline`.

    2. DERIVED ENSURE-SETS — the per-(state × event-type) ensure-set is
       ensured BEFORE transition selection. Two adversarial corners:
         (a) a GUARD's generator-backed recordable fact is GENERATED before
             selection, so the guard reads the generated value (not nil) and
             selects the right transition — the replay-sensitive corner;
         (b) the `:always`-CLOSURE correctness — a generator-backed fact
             required by an `:always` ACTION reachable from a transition's
             TARGET is ensured in the SAME macrostep (the closure reaches
             through the candidate target), and the generated value is
             written back into the causal `:rf.cofx` record (so replay
             re-presents it).

    3. ENTRY REQUIRES DELIVERED — a named guard / action declaring a PROVIDED
       recordable fact (`:rf/time-ms`) present on the token reads it off the
       `:rf.cofx` record verbatim and folds it into a durable `:data` write.

  These exercise the ACTUAL failing/working paths (per the project's
  acceptance discipline — the test hits the real dispatch path, not a
  routed-around green)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as machines]
            [re-frame.machines.cofx-attach :as cofx-attach]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom])
  (:import [clojure.lang ExceptionInfo]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ^:private snapshot mtest/snapshot)

;; A fixed wall-clock sentinel the host clock never spontaneously returns.
(def ^:private SCRIPTED-TIME-MS 1234500000)

;; ===========================================================================
;; 1. Inline-fn restriction — :rf.cofx/requires must be on a NAMED entry
;; ===========================================================================

(deftest inline-requires-on-transition-slot-rejected
  (testing "an `:rf.cofx/requires` placed directly on an inline `:on`
            transition map fails registration with
            :rf.error/machine-cofx-requires-inline"
    (let [m {:initial :idle
             :data    {}
             :states  {:idle {:on {:go {:rf.cofx/requires [:rf/time-ms]
                                        :target :done}}}
                       :done {}}}
          e (is (thrown? ExceptionInfo
                         (machines/make-machine-handler m)))]
      (is (= :rf.error/machine-cofx-requires-inline
             (:rf.error/id (ex-data e)))
          "the inline-on-slot declaration is the named error category"))))

(deftest inline-requires-on-bare-entry-map-rejected
  (testing "a `:guards` entry that is a map carrying :rf.cofx/requires but NO
            :fn (an inline declaration with nothing to deliver to) fails
            registration — there is no callback to attach the diet to"
    (let [m {:initial :idle
             :data    {}
             ;; map entry with :rf.cofx/requires but no :fn — illegal
             :guards  {:bad {:rf.cofx/requires [:rf/time-ms]}}
             :states  {:idle {:on {:go {:target :done :guard :bad}}}
                       :done {}}}
          e (is (thrown? ExceptionInfo
                         (machines/make-machine-handler m)))]
      (is (= :rf.error/machine-cofx-requires-inline
             (:rf.error/id (ex-data e)))))))

(deftest named-entry-with-requires-and-fn-is-legal
  (testing "the LEGAL form — a named :guards entry map carrying BOTH
            :rf.cofx/requires AND :fn — registers cleanly"
    (let [m {:initial :idle
             :data    {}
             :guards  {:ok {:rf.cofx/requires [:rf/time-ms]
                            :fn (fn [{cofx :rf.cofx}]
                                  (some? (:rf/time-ms cofx)))}}
             :states  {:idle {:on {:go {:target :done :guard :ok}}}
                       :done {}}}]
      (is (some? (machines/make-machine-handler m))
          "the named entry form constructs a handler without throwing"))))

;; ===========================================================================
;; 2. Derived ensure-sets — ensured BEFORE transition selection
;; ===========================================================================

(deftest guard-generator-backed-fact-ensured-before-selection
  (testing "a GUARD requiring a generator-backed recordable fact has it
            GENERATED before selection — the guard reads the generated value
            (never nil) and selects the transition. The replay-sensitive
            corner: a mid-selection host read would let replay pick a
            DIFFERENT transition."
    ;; A generator-backed recordable cofx: produces a fixed value so the test
    ;; is deterministic. It is NOT supplied on the token → it must be
    ;; GENERATED by the ensure step before the guard runs.
    (rf/reg-cofx :test/roll
      {:recordable? true :doc "Replayable fixed roll."}
      (fn [] 6))
    (let [seen (atom ::unset)
          m {:initial :idle
             :data    {}
             :guards  {:rolled-six?
                       {:rf.cofx/requires [:test/roll]
                        :fn (fn [{cofx :rf.cofx}]
                              (reset! seen (:test/roll cofx))
                              (= 6 (:test/roll cofx)))}}
             :states  {:idle {:on {:go {:target :done :guard :rolled-six?}}}
                       :done {}}}]
      (rf/reg-machine :attach/guard-gen m)
      ;; No :test/roll on the token — the ensure step must generate it BEFORE
      ;; the guard evaluates during selection.
      (rf/dispatch-sync [:attach/guard-gen [:go]]
                        {:rf.cofx {:rf/time-ms SCRIPTED-TIME-MS}})
      (is (= 6 @seen)
          "the guard read the GENERATED fact (not nil) — ensured before selection")
      (is (= :done (mtest/machine-state :attach/guard-gen))
          "the guard fired the transition on the ensured generated value"))))

(deftest always-closure-fact-ensured-in-same-macrostep
  (testing "ENSURE-SET :always-CLOSURE correctness — a generator-backed fact
            required by an :always ACTION reachable from a transition's TARGET
            is ensured in the SAME macrostep (the closure reaches THROUGH the
            candidate target), and the generated value lands in the action's
            :data write"
    (rf/reg-cofx :test/jitter
      {:recordable? true :doc "Replayable fixed jitter."}
      (fn [] 42))
    (let [m {:initial :idle
             :data    {:armed? true}
             :guards  {:armed? (fn [{:keys [data]}] (:armed? data))}
             :actions {;; an :always action consuming a generator-backed fact
                       :record-jitter
                       {:rf.cofx/requires [:test/jitter]
                        :fn (fn [{:keys [data] cofx :rf.cofx}]
                              {:data (assoc data
                                            :jitter (:test/jitter cofx)
                                            :armed? false)})}}
             :states  {:idle {:on {:go :pending}}
                       ;; :pending's :always (guarded by :armed?, which the
                       ;; action flips false) records the jitter then settles.
                       :pending {:always {:guard  :armed?
                                          :action :record-jitter
                                          :target :done}}
                       :done {}}}]
      (rf/reg-machine :attach/always-closure m)
      ;; :go targets :pending; the ensure-set for [:go] at :idle MUST include
      ;; :test/jitter (reachable via :pending's :always action) so the jitter
      ;; is generated BEFORE the macrostep that runs the :always action.
      (rf/dispatch-sync [:attach/always-closure [:go]]
                        {:rf.cofx {:rf/time-ms SCRIPTED-TIME-MS}})
      (let [d (mtest/machine-data :attach/always-closure)]
        (is (= 42 (:jitter d))
            "the :always action wrote the GENERATED jitter — the ensure-set
             closure reached through the candidate target")
        (is (= :done (mtest/machine-state :attach/always-closure))
            "the :always cascade settled on :done")))))

(deftest ensure-set-for-includes-always-closure
  (testing "white-box: ensure-set-for derives the :always-closure id from a
            candidate target, not just the directly-touched guard/action"
    (rf/reg-cofx :test/jitter2 {:recordable? true} (fn [] 1))
    (let [m (cofx-attach/index-ensure-sets
              {:initial :idle
               :actions {:rec {:rf.cofx/requires [:test/jitter2]
                               :fn (fn [_] nil)}}
               :states  {:idle    {:on {:go :pending}}
                         :pending {:always {:action :rec :target :done}}
                         :done    {}}})
          ;; active state :idle, event [:go] → target :pending whose :always
          ;; action :rec requires :test/jitter2.
          es (cofx-attach/ensure-set-for m {:state :idle :data {}} [:go])]
      (is (contains? (set (map :id es)) :test/jitter2)
          "the ensure-set for [:go] at :idle includes the :always-closure
           fact reachable through the :pending target"))))

(deftest no-requires-machine-ensure-set-empty
  (testing "a machine with no :rf.cofx/requires anywhere derives an empty
            ensure-set (the no-op fast path)"
    (let [m (cofx-attach/index-ensure-sets
              {:initial :idle
               :guards  {:g (fn [_] true)}
               :states  {:idle {:on {:go {:target :done :guard :g}}}
                         :done {}}})]
      (is (empty? (cofx-attach/ensure-set-for m {:state :idle :data {}} [:go]))
          "no declared requires → empty ensure-set"))))

;; ===========================================================================
;; 3. Entry requires delivered — a named entry's declared fact reaches the fn
;; ===========================================================================

(deftest named-guard-requires-provided-fact-delivered
  (testing "a named GUARD declaring the PROVIDED recordable :rf/time-ms reads
            it off the :rf.cofx record and decides on it"
    (let [seen (atom nil)
          m {:initial :idle
             :data    {}
             :guards  {:at-scripted?
                       {:rf.cofx/requires [:rf/time-ms]
                        :fn (fn [{cofx :rf.cofx}]
                              (reset! seen (:rf/time-ms cofx))
                              (= SCRIPTED-TIME-MS (:rf/time-ms cofx)))}}
             :states  {:idle {:on {:go {:target :done :guard :at-scripted?}}}
                       :done {}}}]
      (rf/reg-machine :attach/entry-guard m)
      (rf/dispatch-sync [:attach/entry-guard [:go]]
                        {:rf.cofx {:rf/time-ms SCRIPTED-TIME-MS}})
      (is (= SCRIPTED-TIME-MS @seen)
          "the named guard read the provided :rf/time-ms off the record")
      (is (= :done (mtest/machine-state :attach/entry-guard))
          "the guard fired on the delivered fact"))))

(deftest named-action-requires-fact-folded-into-data
  (testing "a named ACTION declaring :rf/time-ms folds the recorded fact into
            a durable :data write (replay-deterministic)"
    (let [m {:initial :idle
             :data    {}
             :actions {:stamp
                       {:rf.cofx/requires [:rf/time-ms]
                        :fn (fn [{cofx :rf.cofx}]
                              {:data {:stamped-at (:rf/time-ms cofx)}})}}
             :states  {:idle {:on {:go {:target :done :action :stamp}}}
                       :done {}}}]
      (rf/reg-machine :attach/entry-action m)
      (rf/dispatch-sync [:attach/entry-action [:go]]
                        {:rf.cofx {:rf/time-ms SCRIPTED-TIME-MS}})
      (is (= SCRIPTED-TIME-MS
             (:stamped-at (mtest/machine-data :attach/entry-action)))
          "the named action wrote the recorded :rf/time-ms into :data"))))

(deftest generated-fact-written-back-into-causal-record
  (testing "a GENERATED ensure-set fact is written back into the causal
            :rf.cofx record an action then reads — replay re-presents it
            (the generation step finds nothing to do on replay)"
    (rf/reg-cofx :test/token {:recordable? true} (fn [] :GENERATED))
    (let [m {:initial :idle
             :data    {}
             :actions {:capture
                       {:rf.cofx/requires [:test/token]
                        :fn (fn [{cofx :rf.cofx}]
                              {:data {:captured (:test/token cofx)}})}}
             :states  {:idle {:on {:go {:target :done :action :capture}}}
                       :done {}}}]
      (rf/reg-machine :attach/writeback m)
      (rf/dispatch-sync [:attach/writeback [:go]]
                        {:rf.cofx {:rf/time-ms SCRIPTED-TIME-MS}})
      (is (= :GENERATED (:captured (mtest/machine-data :attach/writeback)))
          "the action read the GENERATED fact off the augmented record"))))

;; ===========================================================================
;; pure-fn caller unaffected — no token → no ensure, no error
;; ===========================================================================

(deftest pure-fn-caller-no-ensure-no-error
  (testing "a pure machine-transition (no router token) consumes no recordable
            facts — the ensure step no-ops (no :rf/cofx stamp), no error"
    (let [m {:initial :idle
             :data    {}
             :guards  {:g {:rf.cofx/requires [:rf/time-ms]
                           :fn (fn [{cofx :rf.cofx}] (nil? cofx))}}
             :states  {:idle {:on {:go {:target :done :guard :g}}}
                       :done {}}}
          ;; reg-machine* installs the index; drive the pure engine directly.
          handler (machines/make-machine-handler m)]
      (is (some? handler)
          "registration succeeds (named entry, legal)")
      ;; The pure engine path carries no :rf/cofx, so the guard's cofx is nil
      ;; and the ensure step is bypassed — no throw.
      (is (some? (machines/machine-transition m {:state :idle :data {}} [:go]))
          "the pure engine runs without a token and without an ensure error"))))
