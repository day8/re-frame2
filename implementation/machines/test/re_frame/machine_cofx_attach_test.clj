(ns re-frame.machine-cofx-attach-test
  "Machine consumer attachment.

  Covers the three pieces and their adversarial corners:

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

;; ---- multi-hop :always chain ---------------------------------------------
;;
;; The single-hop cases above reach an :always one hop from the candidate
;; target. The runtime, however, settles :always to a MULTI-HOP fixed point
;; in ONE macrostep (transition/drain-to-fixed-point), while the ensure step
;; runs ONCE before that macrostep (registration/ensure-ctx-cofx). A
;; :rf.cofx/requires declared on an :always guard/action reached at chain
;; depth >=2 (A --go--> B, B :always--> C, C :always {:guard g-requiring-cofx})
;; is ensured up front: the static closure chases the :always chain to a
;; fixed point, so the depth>=2 fact is in the ensure-set (the guard reads
;; the ensured value, never a silent nil) — preserving replay-determinism.

(deftest ensure-set-for-follows-multi-hop-always-chain
  (testing "white-box: ensure-set-for chases :always targets to a FIXED POINT,
            so a depth>=2 :always-reached guard's :rf.cofx/requires is in the
            ensure-set (not just the one-hop case)"
    (rf/reg-cofx :test/deep-roll {:recordable? true} (fn [] 6))
    (let [m (cofx-attach/index-ensure-sets
              {:initial :a
               :guards  {;; the deep guard sits TWO :always hops from :a's target
                         :deep? {:rf.cofx/requires [:test/deep-roll]
                                 :fn (fn [{cofx :rf.cofx}]
                                       (= 6 (:test/deep-roll cofx)))}}
               :states  {:a {:on {:go :b}}
                         ;; hop 1: :b's :always settles onto :c (no requires)
                         :b {:always {:target :c}}
                         ;; hop 2: :c's :always guard requires :test/deep-roll
                         :c {:always {:guard :deep? :target :done}}
                         :done {}}})
          ;; active :a, [:go] → :b → (always) :c → (always, guarded) :done.
          es (cofx-attach/ensure-set-for m {:state :a :data {}} [:go])]
      (is (contains? (set (map :id es)) :test/deep-roll)
          "the ensure-set chases B:always→C, then C:always's guard — the
           depth>=2 fact is ensured, not dropped at the one-hop boundary"))))

(deftest multi-hop-always-guard-reads-ensured-fact
  (testing "end-to-end: a guard reached at :always chain depth>=2 reads the
            GENERATED fact (never nil) — the ensure step settled the same
            fixed point the runtime macrostep does, so the deep guard selects
            the right transition on the ensured value"
    (rf/reg-cofx :test/deep-gen {:recordable? true} (fn [] 6))
    (let [seen (atom ::unset)
          m {:initial :a
             :data    {}
             :guards  {:deep-rolled-six?
                       {:rf.cofx/requires [:test/deep-gen]
                        :fn (fn [{cofx :rf.cofx}]
                              (reset! seen (:test/deep-gen cofx))
                              (= 6 (:test/deep-gen cofx)))}}
             :states  {:a {:on {:go :b}}
                       :b {:always {:target :c}}
                       :c {:always {:guard :deep-rolled-six? :target :done}}
                       :done {}}}]
      (rf/reg-machine :attach/multi-hop-gen m)
      ;; No :test/deep-gen on the token — the ensure step must generate it
      ;; BEFORE the macrostep settles A→B→(always)C→(always,guarded)done.
      (rf/dispatch-sync [:attach/multi-hop-gen [:go]]
                        {:rf.cofx {:rf/time-ms SCRIPTED-TIME-MS}})
      (is (= 6 @seen)
          "the depth>=2 :always guard read the GENERATED fact (not nil) —
           ensured before the macrostep settled the multi-hop :always chain")
      (is (= :done (mtest/machine-state :attach/multi-hop-gen))
          "the deep guard fired on the ensured value, settling the chain"))))

(deftest multi-hop-always-missing-provided-fact-throws
  (testing "a PROVIDED (non-generator-backed) recordable fact required by a
            depth>=2 :always guard, absent from the in-flight record, now
            raises :rf.error/missing-required-cofx from the dispatch-time
            ensure step — the depth>=2 diet IS in the ensure-set, so the
            ensure step's missing-required check fires rather than the guard
            SILENTLY reading nil (the rf2-h9gwkx hole). Drives the real
            ensure path (`cofx-attach/ensure-cofx`, run by `ensure-ctx-cofx`
            before the macrostep) so the throw surfaces verbatim rather than
            being routed into the dispatch-sync error pipeline."
    ;; :rf/time-ms is recordable + provided (no generator) — a strict
    ;; required fact. Declared on a depth>=2 :always guard but NOT present on
    ;; the record: the ensure step must surface missing-required, not nil.
    (let [m (cofx-attach/index-ensure-sets
              {:initial :a
               :data    {}
               :guards  {:needs-time?
                         {:rf.cofx/requires [:rf/time-ms]
                          :fn (fn [{cofx :rf.cofx}]
                                (some? (:rf/time-ms cofx)))}}
               :states  {:a {:on {:go :b}}
                         :b {:always {:target :c}}
                         :c {:always {:guard :needs-time? :target :done}}
                         :done {}}})
          ;; the empty record deliberately omits :rf/time-ms.
          e (is (thrown? ExceptionInfo
                         (cofx-attach/ensure-cofx
                           m {:state :a :data {}} [:go]
                           {} nil :attach/multi-hop-missing)))]
      (is (= :rf.error/missing-required-cofx
             (:rf.error/id (ex-data e)))
          "the depth>=2 :always-required provided fact, absent, throws
           missing-required (it WAS in the ensure-set) — not a silent nil")
      ;; counter-check: confirm the depth>=2 fact is present in the derived
      ;; ensure-set (the positive of the throw) — the closure reaches it, so
      ;; it is never dropped to a silent-nil read.
      (is (contains? (set (map :id (cofx-attach/ensure-set-for
                                     m {:state :a :data {}} [:go])))
                     :rf/time-ms)
          "the depth>=2 :always-guard's provided requires is in the ensure-set"))))

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

;; ===========================================================================
;; PARALLEL ROOT :on / :after in the ensure-set
;; ===========================================================================
;;
;; The parallel branch of `ensure-set-for` unions each REGION's scope AND the
;; parallel ROOT's own `:on` / `:after` (live ancestor-fallback transition
;; surfaces the runtime evaluates separately via `transition/root-on-match` /
;; `root-after-match`). A coeffect declared by a root `:on` / root `:after`
;; guard/action is therefore ensured before selection, so the guard/action
;; reads the ensured value (or surfaces the missing-required throw).
;; These tests prove the root surfaces contribute to the ensure-set.
;;
;; XState-v5 alignment: a transition (`on`) or delayed transition (`after`)
;; declared on a `<parallel>` node is a first-class ancestor fallback (Spec 005
;; §Root parallel `:on` / §Root-level `:after`, verified vs xstate@5.32.0); its
;; guard/action requirements must be satisfied like any other node's.

(deftest ensure-set-for-includes-parallel-root-on
  (testing "white-box: ensure-set-for for a parallel machine includes the
            ROOT :on candidate's guard/action requires (the ancestor fallback
            the runtime resolves separately) — not just the regions' scopes"
    (rf/reg-cofx :test/root-roll {:recordable? true} (fn [] 6))
    (let [m (cofx-attach/index-ensure-sets
              {:type    :parallel
               :data    {}
               :guards  {:root-rolled-six?
                         {:rf.cofx/requires [:test/root-roll]
                          :fn (fn [{cofx :rf.cofx}] (= 6 (:test/root-roll cofx)))}}
               ;; root :on — NO region declares :go-all (so the runtime falls
               ;; through to the root). Its guard requires :test/root-roll.
               :on      {:go-all {:target [[:a :two] [:b :two]]
                                  :guard  :root-rolled-six?}}
               :regions {:a {:initial :one :states {:one {} :two {}}}
                         :b {:initial :one :states {:one {} :two {}}}}})
          es (cofx-attach/ensure-set-for
               m {:state {:a :one :b :one} :data {}} [:go-all])]
      (is (contains? (set (map :id es)) :test/root-roll)
          "the ensure-set includes the ROOT :on guard's requires — before
           rf2-bu106a the parallel branch unioned regions only, so :root-on
           returned [] and the fact was never ensured"))))

(deftest ensure-set-for-includes-parallel-root-after
  (testing "white-box: ensure-set-for for the synthetic root :after timer event
            ([:rf.machine.timer/after-elapsed delay epoch []]) includes the
            ROOT :after candidate's requires"
    (rf/reg-cofx :test/root-jitter {:recordable? true} (fn [] 7))
    (let [m (cofx-attach/index-ensure-sets
              {:type    :parallel
               :data    {}
               :actions {:root-stamp
                         {:rf.cofx/requires [:test/root-jitter]
                          :fn (fn [_] nil)}}
               ;; root :after — root-owned delayed transition, decl-path [].
               :after   {1000 {:target [[:a :two] [:b :two]]
                               :action :root-stamp}}
               :regions {:a {:initial :one :states {:one {} :two {}}}
                         :b {:initial :one :states {:one {} :two {}}}}})
          ;; the root timer carries decl-path [] (root-owned, not region-prefixed)
          es (cofx-attach/ensure-set-for
               m {:state {:a :one :b :one}
                  :data  {:rf/after-epoch {[] 1}}}
               [:rf.machine.timer/after-elapsed 1000 1 []])]
      (is (contains? (set (map :id es)) :test/root-jitter)
          "the ensure-set for the root :after timer includes the root :after
           action's requires (rf2-bu106a acceptance #2)"))))

(deftest parallel-root-on-guard-reads-ensured-generated-fact
  (testing "end-to-end: a parallel ROOT :on guard requiring a generator-backed
            fact reads the GENERATED value (never nil) — the ensure step ran
            for the root surface before the root-fallback selection, so the
            guard fires the root transition on the ensured value"
    (rf/reg-cofx :test/root-gen {:recordable? true} (fn [] 6))
    (let [seen (atom ::unset)
          m {:type    :parallel
             :data    {}
             :guards  {:root-six?
                       {:rf.cofx/requires [:test/root-gen]
                        :fn (fn [{cofx :rf.cofx}]
                              (reset! seen (:test/root-gen cofx))
                              (= 6 (:test/root-gen cofx)))}}
             :on      {:go-all {:target [[:a :two] [:b :two]]
                                :guard  :root-six?}}
             :regions {:a {:initial :one :states {:one {} :two {}}}
                       :b {:initial :one :states {:one {} :two {}}}}}]
      (rf/reg-machine :attach/parallel-root-on m)
      ;; No :test/root-gen on the token — the ensure step must generate it
      ;; BEFORE the root-fallback selection evaluates the root guard.
      (rf/dispatch-sync [:attach/parallel-root-on [:go-all]]
                        {:rf.cofx {:rf/time-ms SCRIPTED-TIME-MS}})
      (is (= 6 @seen)
          "the root :on guard read the GENERATED fact (not nil) — ensured
           before the parallel root-fallback selection ran")
      (is (= {:a :two :b :two}
             (:state (snapshot :attach/parallel-root-on)))
          "the root :on fired on the ensured value, moving both regions"))))

(deftest parallel-root-on-missing-provided-fact-throws
  (testing "a PROVIDED (non-generator-backed) fact required by a parallel ROOT
            :on guard, absent from the in-flight record, raises missing-
            required from the dispatch-time ensure step — the root surface IS
            in the ensure-set now (before rf2-bu106a it was dropped, so the
            guard would have silently read nil)"
    (let [m (cofx-attach/index-ensure-sets
              {:type    :parallel
               :data    {}
               :guards  {:needs-time?
                         {:rf.cofx/requires [:rf/time-ms]
                          :fn (fn [{cofx :rf.cofx}] (some? (:rf/time-ms cofx)))}}
               :on      {:go-all {:target [[:a :two] [:b :two]]
                                  :guard  :needs-time?}}
               :regions {:a {:initial :one :states {:one {} :two {}}}
                         :b {:initial :one :states {:one {} :two {}}}}})
          ;; empty record deliberately omits :rf/time-ms.
          e (is (thrown? ExceptionInfo
                         (cofx-attach/ensure-cofx
                           m {:state {:a :one :b :one} :data {}} [:go-all]
                           {} nil :attach/parallel-root-missing)))]
      (is (= :rf.error/missing-required-cofx (:rf.error/id (ex-data e)))
          "the root :on-required provided fact, absent, throws missing-required
           (it WAS in the ensure-set) — not a silent nil")
      (is (contains? (set (map :id (cofx-attach/ensure-set-for
                                     m {:state {:a :one :b :one} :data {}}
                                     [:go-all])))
                     :rf/time-ms)
          "the parallel root :on guard's provided requires is in the ensure-set"))))

;; ===========================================================================
;; :type :choice candidates in the ensure-set (rf2-4y4bzq)
;; ===========================================================================
;;
;; A `:type :choice` transient node carries its candidate vector under
;; `:choice`, which LOWERS to `:always` only at `choice/desugar-choices` (run
;; at transition / birth time). The index + ensure-set run on the RAW
;; pre-desugar machine, so the ensure-set must treat a choice node's `:choice`
;; vector as its `:always` candidates — otherwise a choice candidate guard's
;; :rf.cofx/requires is never ensured, the guard reads nil, and strict replay
;; diverges (it selects a DIFFERENT candidate). The inline-fn restriction must
;; likewise sweep :choice.

(deftest inline-requires-on-choice-candidate-rejected
  (testing "an :rf.cofx/requires placed directly on a :type :choice candidate
            (an inline declaration with no :fn to deliver to) fails
            registration with :rf.error/machine-cofx-requires-inline — the
            :choice slot is swept like :always (the choice lowers to :always)"
    (let [m {:initial :idle
             :data    {}
             :states  {:idle     {:on {:go :checking}}
                       :checking {:type   :choice
                                  :choice [{:rf.cofx/requires [:rf/time-ms]
                                            :target :a}
                                           {:target :b}]}
                       :a {} :b {}}}
          e (is (thrown? ExceptionInfo
                         (machines/make-machine-handler m)))]
      (is (= :rf.error/machine-cofx-requires-inline
             (:rf.error/id (ex-data e)))
          "the inline-on-choice-candidate declaration is rejected"))))

(deftest ensure-set-for-includes-choice-candidate-requires
  (testing "white-box: ensure-set-for treats a :type :choice node's :choice
            vector as its :always candidates, so a choice candidate GUARD's
            :rf.cofx/requires is in the ensure-set (reachable through the
            choice target) — before rf2-4y4bzq always-entries read (:always
            choice-node)=nil and the fact was dropped"
    (rf/reg-cofx :test/choice-roll {:recordable? true} (fn [] 6))
    (let [m (cofx-attach/index-ensure-sets
              {:initial :idle
               :guards  {:needs-roll {:rf.cofx/requires [:test/choice-roll]
                                      :fn (fn [_] true)}}
               :states  {:idle     {:on {:go :checking}}
                         :checking {:type   :choice
                                    :choice [{:guard :needs-roll :target :a}
                                             {:target :b}]}
                         :a {} :b {}}})
          es (cofx-attach/ensure-set-for m {:state :idle :data {}} [:go])]
      (is (contains? (set (map :id es)) :test/choice-roll)
          "the ensure-set for [:go] at :idle includes the choice candidate
           guard's requires — reachable through the :checking choice target"))))

(deftest choice-candidate-guard-reads-ensured-generated-fact
  (testing "end-to-end: a :type :choice candidate GUARD requiring a
            generator-backed fact reads the GENERATED value (never nil) — the
            ensure-set closure treats the choice node's :choice vector as its
            :always candidates, so the fact is ensured BEFORE the choice settles
            (it lowers to :always in the same macrostep) and routes correctly"
    (rf/reg-cofx :test/roll {:recordable? true} (fn [] 6))
    (let [seen (atom ::unset)
          m {:initial :idle
             :data    {}
             :guards  {:rolled-six?
                       {:rf.cofx/requires [:test/roll]
                        :fn (fn [{cofx :rf.cofx}]
                              (reset! seen (:test/roll cofx))
                              (= 6 (:test/roll cofx)))}}
             :states  {:idle     {:on {:go :checking}}
                       :checking {:type   :choice
                                  :choice [{:guard :rolled-six? :target :hit}
                                           {:target :miss}]}
                       :hit  {}
                       :miss {}}}]
      (rf/reg-machine :attach/choice-guard m)
      ;; No :test/roll on the token — the ensure step must generate it BEFORE
      ;; the choice node settles (it lowers to :always in the [:go] macrostep).
      (rf/dispatch-sync [:attach/choice-guard [:go]]
                        {:rf.cofx {:rf/time-ms SCRIPTED-TIME-MS}})
      (is (= 6 @seen)
          "the choice candidate guard read the GENERATED :test/roll (not nil) —
           ensured before the choice settled")
      (is (= :hit (mtest/machine-state :attach/choice-guard))
          "the choice routed to :hit on the ensured value"))))

;; ===========================================================================
;; state-level / per-region :after candidates in the ensure-set (rf2-iyrc9t)
;; ===========================================================================
;;
;; The synthetic timer event [:rf.machine.timer/after-elapsed delay epoch
;; decl-path] routes to the scheduling node's :after-TABLE transition at
;; decl-path/delay — a slot SEPARATE from :on, so the :on walk misses it. The
;; parallel ROOT :after was already handled (parallel-root-diet); the
;; per-state / per-region :after was the asymmetric gap. A state-level :after
;; guard/action declaring :rf.cofx/requires must have its facts ensured when
;; the timer fires, else the guard reads nil / replay diverges.

(deftest ensure-set-for-includes-state-after
  (testing "white-box: ensure-set-for for a state-level :after timer event
            ([:rf.machine.timer/after-elapsed delay epoch [state]]) includes the
            :after candidate's requires — the :after slot the :on walk missed
            (rf2-iyrc9t)"
    (rf/reg-cofx :test/token {:recordable? true} (fn [] :T))
    (let [m (cofx-attach/index-ensure-sets
              {:initial :waiting
               :data    {}
               :guards  {:needs-token
                         {:rf.cofx/requires [:test/token]
                          :fn (fn [{cofx :rf.cofx}] (some? (:test/token cofx)))}}
               :states  {:waiting {:after {5000 {:guard  :needs-token
                                                 :target :done}}}
                         :done    {}}})
          ;; the state timer carries the scheduling node's decl-path [:waiting].
          es (cofx-attach/ensure-set-for
               m {:state :waiting :data {}}
               [:rf.machine.timer/after-elapsed 5000 1 [:waiting]])]
      (is (contains? (set (map :id es)) :test/token)
          "the ensure-set for the state :after timer includes the :after guard's
           requires — before rf2-iyrc9t only the parallel ROOT :after was
           handled, so a per-state :after read nil"))))

(deftest ensure-set-for-includes-per-region-after
  (testing "white-box: for a parallel machine, the synthetic region-qualified
            :after timer ([... [<region> <state>]]) resolves within the region
            scope (region head stripped) and adds the region-state :after
            candidate's requires (rf2-iyrc9t — the region path)"
    (rf/reg-cofx :test/region-token {:recordable? true} (fn [] :RT))
    (let [m (cofx-attach/index-ensure-sets
              {:type    :parallel
               :data    {}
               :guards  {:needs-region-token
                         {:rf.cofx/requires [:test/region-token]
                          :fn (fn [{cofx :rf.cofx}] (some? (:test/region-token cofx)))}}
               :regions {:a {:initial :waiting
                             :states  {:waiting {:after {3000 {:guard  :needs-region-token
                                                               :target :done}}}
                                       :done    {}}}
                         :b {:initial :one :states {:one {} :two {}}}}})
          ;; region-a timer: decl-path is region-qualified [:a :waiting].
          es (cofx-attach/ensure-set-for
               m {:state {:a :waiting :b :one} :data {}}
               [:rf.machine.timer/after-elapsed 3000 1 [:a :waiting]])]
      (is (contains? (set (map :id es)) :test/region-token)
          "the region-qualified :after timer's guard requires is ensured — the
           decl-path region head was stripped to resolve within region-a"))))
