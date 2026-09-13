(ns re-frame.join-strict-mint-cljs-test
  "rf2-xfk6fn — CAUSAL proof that a `:spawn-all` join completion honours the
  EFFECTIVE cofx mint policy end-to-end, rather than merely carrying the policy
  keyword alongside it.

  Earlier suites proved a join completion's recordable facts ride `:rf.cofx` and
  survive an EDN round-trip. But every join completion TARGET in those suites
  declared NO generator-backed coeffect requirement, so `:strict` and `:live`
  were OBSERVATIONALLY IDENTICAL there — the `:strict` assertion only ever saw
  the policy KEYWORD in the router options, and a path that silently fell back to
  `:live` minting would have passed exact-head CI unchanged.

  These tests add ONE real generator-backed recordable coeffect to a join
  completion target and compare `:strict` against `:live` through the SAME
  record/replay machinery — no join-specific replay format, coeffect, or test
  framework. The load-bearing promise: a completion under `:strict` does NOT
  consult the host — it replays from recorded causal facts, and an absent fact is
  the canonical `:rf.error/missing-required-cofx`, never a silent live-mint no-op.

  WHERE THE SEAM MOVED. Completion is finality (Spec 005 §Child completion
  protocol), so there is no child-authored completion event and no
  `:rf.machine/join-dispatch` transport to inherit a policy THROUGH — the child
  reaches a `:final?` leaf and the runtime mints the carrier from that
  transition's result. The policy-gated action therefore sits on the transition
  INTO `:final?`, which is a strictly better place for this proof: it is the
  child's own recorded event that a replay re-drives, so the strict/live
  distinction is measured on the real completion path rather than on a transport
  that no longer exists.

  Named `*-cljs-test.cljc` so BOTH the JVM run and the shadow-cljs node run
  discover it — the shared envelope / consumer-attachment path is exercised on
  both platforms."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.late-bind :as rf.late-bind]
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  rf.machines.test-support/trace-capture-fixture)

;; ---------------------------------------------------------------------------
;; helpers
;; ---------------------------------------------------------------------------

(defn- join-state [parent-id]
  (get-in (rf.machines.test-support/runtime-db) [:rf.runtime/machines :spawned parent-id [:racing]]))

(defn- stale-reasons []
  (mapv (comp :rf.reply/stale-reason :tags)
        (rf.machines.test-support/events-of :rf.machine.spawn-all/stale-completion)))

(defn- missing-required-errors []
  (rf.machines.test-support/events-of :rf.error/missing-required-cofx))

(defn- child-completed-terminals []
  (rf.machines.test-support/events-of :rf.machine.spawn-all/child-completed))

;; The generator-call ledger. Registered fresh per test (`reg-roll!`) closing
;; over a caller-owned atom, so "the generator is never called" is a HARD
;; observation, not an inference. A recordable, NON-provided cofx with a
;; value-returning supplier is generator-backed (EP-0017 §5): `:live` /
;; `:explicit-live` run the supplier; `:strict` refuses and an absent fact is
;; missing-required.
(defn- reg-roll!
  "Register `:strictmint/roll` as a generator-backed recordable cofx whose
  supplier increments `calls` and returns `value` (ordinary EDN)."
  [calls value]
  (rf/reg-cofx :strictmint/roll
    {:recordable? true
     :doc "Test generator-backed recordable fact: a join completion's minted roll."}
    (fn [] (swap! calls inc) value)))

(defn- mk-completing-child
  "The JOIN COMPLETION TARGET: a member child whose transition INTO its `:final?`
  state (on `:go`) DECLARES a generator-backed recordable coeffect
  `:strictmint/roll` and stamps the minted value into the `:data` slot its
  `:output-key` names — so the roll rides the completion the runtime mints at
  finality. The action runs only when the ensure step satisfies its
  `:rf.cofx/requires` under the effective mint policy: `:live` mints, `:strict`
  reads the recorded fact or fails missing-required.

  Completion is finality (Spec 005 §Child completion protocol), so the child
  names no parent and dispatches nothing. That MOVES the seam this suite probes
  rather than removing it: the policy-gated action now sits on the transition
  into `:final?`, and the completion the parent folds is the carrier the runtime
  mints from that transition's result."
  []
  {:initial :running
   :data    {:id nil}
   :actions {:record-id  (fn [{data :data ev :event}] {:data (assoc data :id (second ev))})
             :stamp-roll {:rf.cofx/requires [:strictmint/roll]
                          :fn (fn [{data :data cofx :rf.cofx}]
                                {:data (assoc data :roll (:strictmint/roll cofx))})}}
   :states  {:running {:on {:set-id {:action :record-id}
                            :go     {:target :done :action :stamp-roll}}}
             :done {:final? true :output-key :roll}}})

(defn- mk-plain-child
  "A member child with NO coeffect requirement — completes cleanly under any mint
  policy. Pairs with `mk-completing-child` so the two-child `:all` join stays OPEN
  after one worker folds (a non-decisive fold, so the accepted child's terminal
  rides the `:rf.machine.spawn-all/child-completed` trace we assert on)."
  []
  {:initial :running
   :data    {:id nil}
   :actions {:record-id (fn [{data :data ev :event}] {:data (assoc data :id (second ev))})}
   :states  {:running {:on {:set-id {:action :record-id}
                            :go     {:target :done}}}
             :done {:final? true :output-key :id}}})

(defn- reg-parent!
  "A two-child `:all` join parent: child `:a` is the generator-backed completion
  target, child `:b` a plain never-driven sibling holding the join open. Stays on
  `:racing` at fold (no `:on` for `:all/done`) so the join slot survives probes;
  `:abort` exits, `:start` re-enters."
  [parent-kw target-kw plain-kw]
  (rf/reg-machine parent-kw
    {:initial :idle
     :states  {:idle   {:on {:start :racing}}
               :racing {:spawn-all
                        {:children        [{:id :a :machine-id target-kw :start [:set-id :a]}
                                           {:id :b :machine-id plain-kw  :start [:set-id :b]}]
                         :join            :all
                         :on-all-complete [:all/done]}
                        :on {:abort :idle}}}}))

(defn- with-dispatch-observer
  "Run `body-fn` with `:router/dispatch!` wrapped by a PASS-THROUGH observer that
  records every `[event opts]` into `sink` then delegates to the real hook, so
  the completion still folds while the exact opts `child-dispatch!` produced are
  observed. Cross-platform (late-bind is bound on JVM + CLJS). Restores in a
  `finally`."
  [sink body-fn]
  (let [real (rf.late-bind/get-fn :router/dispatch!)]
    (try
      (rf.late-bind/set-fn! :router/dispatch!
                         (fn [event opts]
                           (swap! sink conj [event opts])
                           (real event opts)))
      (body-fn)
      (finally
        (rf.late-bind/set-fn! :router/dispatch! real)))))

(defn- completion-of
  "The FIRST observed completion carrier for `child-id` in `sink`, or nil — the
  reserved `[parent-id [:rf.machine.spawn/done <invoke-id> <completion>]]` event
  the runtime mints at the child's finality. Returns the `completion` MAP, whose
  `:result` is the value the child's `:output-key` named."
  [sink parent-id child-id]
  (some (fn [[event _opts]]
          (when (= parent-id (first event))
            (let [inner (second event)]
              (when (and (vector? inner)
                         (= :rf.machine.spawn/done (first inner))
                         (= child-id (:child-id (nth inner 2 nil))))
                (nth inner 2)))))
        @sink))

;; ---------------------------------------------------------------------------
;; (1) strict omits the generator-backed fact → the generator is never called,
;;     the parent does NOT fold, the canonical missing-fact outcome is produced.
;; ---------------------------------------------------------------------------

(deftest strict-omitted-generator-fact-blocks-completion-and-parent-fold
  (testing "rf2-xfk6fn — under a per-call `:rf.cofx/mint-policy :strict` the
            completion target's generator-backed `:strictmint/roll` is NEITHER
            recorded NOR minted, so its completion action's ensure fails
            `:rf.error/missing-required-cofx`, the child never leaves `:running`,
            its completion carrier is never emitted, and the PARENT never folds
            `:a`. A LIVE control on the SAME target shows the fold DOES happen when
            the policy permits generation — so the assertion has teeth: changing
            the policy to `:live` makes it fail."
    (let [calls (atom 0)]
      (reg-roll! calls 6)
      (rf/reg-machine :sm1/ta (mk-completing-child))
      (rf/reg-machine :sm1/pb (mk-plain-child))
      (reg-parent! :sm1/rp :sm1/ta :sm1/pb)
      (rf/dispatch-sync [:sm1/rp [:start]])
      (let [a (get-in (join-state :sm1/rp) [:children :a])]
        (rf.machines.test-support/reset-captured!)
        ;; STRICT, and the recorded token carries NO :strictmint/roll.
        (rf/dispatch-sync [a [:go]]
                          {:rf.cofx {:rf/time-ms 1} :rf.cofx/mint-policy :strict})
        (is (zero? @calls)
            "strict refused to run the generator — the host was NOT consulted")
        (is (= #{} (:done (join-state :sm1/rp)))
            "the parent did NOT fold — the completion never reached it")
        (is (= 1 (count (missing-required-errors)))
            "the canonical missing-fact outcome fired: :rf.error/missing-required-cofx")
        (is (= :running (rf.machines.test-support/machine-state a))
            "the target child stayed :running — its completion action was skipped")
        (is (empty? (child-completed-terminals))
            "no child-completed terminal — no work attempt closed")))))

;; ---------------------------------------------------------------------------
;; (2) the :live foil for the SAME target invokes the generator + completes.
;; ---------------------------------------------------------------------------

(deftest live-foil-invokes-generator-and-folds
  (testing "rf2-xfk6fn — the SAME completion target under `:live` (the runtime
            default) DOES mint `:strictmint/roll`: the generator runs once, the
            completion action forwards the minted value, and the parent folds `:a`.
            This proves the fixture can OBSERVE the strict/live distinction (vs the
            keyword-only checks)."
    (let [calls (atom 0)]
      (reg-roll! calls 6)
      (rf/reg-machine :sm2/ta (mk-completing-child))
      (rf/reg-machine :sm2/pb (mk-plain-child))
      (reg-parent! :sm2/rp :sm2/ta :sm2/pb)
      (rf/dispatch-sync [:sm2/rp [:start]])
      (let [a    (get-in (join-state :sm2/rp) [:children :a])
            sink (atom [])]
        (rf.machines.test-support/reset-captured!)
        (with-dispatch-observer sink
          (fn [] (rf/dispatch-sync [a [:go]])))   ;; default :live
        (is (= 1 @calls) "the generator ran exactly once under :live")
        (is (= #{:a} (:done (join-state :sm2/rp)))
            "the target folded :a under :live")
        (is (= 6 (:result (completion-of sink :sm2/rp :a)))
            "the minted roll (6) rode the completion the runtime minted at finality")
        (is (empty? (stale-reasons)) "no stale suppression for the genuine completion")
        (is (= 1 (count (child-completed-terminals)))
            "the accepted non-decisive fold published one child-completed terminal")))))

;; ---------------------------------------------------------------------------
;; (3) + (4) real-completion strict replay: record a GENUINE completion carrying
;;     the required fact, restore the pre-event runtime-db, replay under explicit
;;     `:strict` — the fold / authority / terminal evidence reproduce WITHOUT host
;;     generation; strip the recorded fact and strict replay fails canonically.
;; ---------------------------------------------------------------------------

(deftest recorded-completion-strict-replays-without-host-generation
  (testing "rf2-xfk6fn — record a GENUINE completion (drive the target through its
            boundary under :live, capturing the minted roll off the wire), restore
            the pre-event runtime-db, then STRICT-replay the completing event with
            its recorded `:strictmint/roll`: the parent fold, the join authority
            (no stale suppression), and the child-completed terminal all reproduce
            with the generator NEVER re-run (strict reads the recorded fact, does
            not consult the host). Stripping the recorded fact makes the strict
            replay fail through the canonical `:rf.error/missing-required-cofx` with
            no fold; and re-running the SAME stripped replay under `:live` folds —
            so the strict assertion has teeth (changing the policy to :live fails
            the test)."
    (let [calls (atom 0)]
      (reg-roll! calls 6)
      (rf/reg-event :sm3/restore-runtime (fn [_ [_ rt]] {:rf.db/runtime rt}))
      (rf/reg-machine :sm3/ta (mk-completing-child))
      (rf/reg-machine :sm3/pb (mk-plain-child))
      (reg-parent! :sm3/rp :sm3/ta :sm3/pb)
      (rf/dispatch-sync [:sm3/rp [:start]])
      (let [pre-fold (rf.machines.test-support/runtime-db)                      ;; attempt-1 join, :done #{}
            a        (get-in (join-state :sm3/rp) [:children :a])
            sink     (atom [])]
        ;; RECORD a genuine completion under :live; capture the minted roll off
        ;; the observed completion carrier (not hand-reconstructed).
        (with-dispatch-observer sink
          (fn [] (rf/dispatch-sync [a [:go]])))
        (is (= 1 @calls) "the live record ran the generator once")
        (is (= #{:a} (:done (join-state :sm3/rp))) "the live completion folded :a")
        (let [recorded-roll (:result (completion-of sink :sm3/rp :a))
              recorded-cofx {:strictmint/roll recorded-roll :rf/time-ms 1}]
          (is (= 6 recorded-roll) "captured the genuine minted roll")

          ;; RESTORE the pre-event runtime-db (restore-epoch analogue): same
          ;; attempt token + spawned instances, :done #{}.
          (rf/dispatch-sync [:sm3/restore-runtime pre-fold])
          (is (= #{} (:done (join-state :sm3/rp))) "restored to the pre-fold epoch")
          (reset! calls 0)
          (rf.machines.test-support/reset-captured!)

          ;; STRICT REPLAY with the recorded fact present.
          (rf/dispatch-sync [a [:go]]
                            {:rf.cofx recorded-cofx :rf.cofx/mint-policy :strict})
          (is (zero? @calls)
              "strict replay did NOT re-run the generator — the host was not consulted")
          (is (= #{:a} (:done (join-state :sm3/rp)))
              "the parent fold reproduced from the recorded fact")
          (is (empty? (stale-reasons))
              "join authority reproduced — no stale suppression on the faithful replay")
          (is (= 1 (count (child-completed-terminals)))
              "terminal evidence reproduced — one child-completed terminal")

          ;; RESTORE + strict replay WITHOUT the recorded fact → canonical fail.
          (rf/dispatch-sync [:sm3/restore-runtime pre-fold])
          (reset! calls 0)
          (rf.machines.test-support/reset-captured!)
          (rf/dispatch-sync [a [:go]]
                            {:rf.cofx {:rf/time-ms 1} :rf.cofx/mint-policy :strict})
          (is (zero? @calls) "the stripped strict replay did NOT mint")
          (is (= #{} (:done (join-state :sm3/rp)))
              "the stripped strict replay folded nothing")
          (is (= 1 (count (missing-required-errors)))
              "stripping the recorded fact is the canonical :rf.error/missing-required-cofx")

          ;; TEETH: the SAME stripped run under :live DOES fold — so the strict
          ;; assertion above is load-bearing (change the policy → the test fails).
          (rf/dispatch-sync [:sm3/restore-runtime pre-fold])
          (reset! calls 0)
          (rf/dispatch-sync [a [:go]] {:rf.cofx {:rf/time-ms 1} :rf.cofx/mint-policy :live})
          (is (= 1 @calls) "the :live foil minted the fact")
          (is (= #{:a} (:done (join-state :sm3/rp)))
              "the :live foil folded — proving the strict no-fold is policy-driven, not incidental"))))))

;; ---------------------------------------------------------------------------
;; (5) the completion carrier inherits the FINISHING event's per-call mint
;;     policy into the parent's join resolution.
;;
;; HISTORY. The original (5), `transport-propagates-strict-to-inherited-
;; resolution-dispatch`, pinned the retired `:rf.machine/join-dispatch`
;; transport. When that transport went, the runtime began minting the carrier
;; inside finalize with `{:frame … :source :machine-spawn}` and NO policy. The
;; test was deleted, and this comment recorded the gap as deliberate:
;; reinstating the inheritance "would mean threading the router's effective mint
;; policy through finalize, which is new machinery no bead has asked for".
;;
;; rf2-ix8fd OVERTURNS that. A bead now asks for exactly that machinery, and for
;; more than the policy. Core exposes the in-flight envelope to a handler body,
;; and both carriers queue through `rf.fx/child-dispatch!`, the SAME seam as
;; `:dispatch`, so the per-call policy rides with no key list of its own. EP-0017
;; §6 is why it must: a `:strict` replay or test intends the no-host-read
;; discipline for the whole cascade, and the spawn edge (rf2-gbzv9) already
;; carries it INTO the child. Letting the completion drop back to `:live` on the
;; way OUT is the silent fallback §6 forbids. The epoch-replay proof
;; (`re-frame.join-strict-mint-epoch-replay-test`) is unchanged: there every
;; event is replayed from its own record anyway.
;; ---------------------------------------------------------------------------

(defn- reg-resolution-parent!
  "A one-child `:all` join parent whose join-RESOLUTION advance runs a named
  action declaring the generator-backed `:strictmint/roll`. So the policy the
  COMPLETION CARRIER runs under decides whether the parent reaches `:ready`."
  [parent-kw child-kw]
  (rf/reg-machine parent-kw
    {:initial :idle
     :data    {}
     :states  {:idle   {:on {:start :racing}}
               :racing {:spawn-all
                        {:children        [{:id :a :machine-id child-kw :start [:set-id :a]}]
                         :join            :all
                         :on-all-complete [:all/done]}
                        :on {:all/done {:target :ready :action :res-action}}}
               :ready  {}}
     :actions {:res-action
               {:rf.cofx/requires [:strictmint/roll]
                :fn (fn [{data :data cofx :rf.cofx}]
                      {:data (assoc data :roll (:strictmint/roll cofx))})}}}))

(deftest completion-carrier-inherits-strict-into-the-join-resolution
  (testing "rf2-ix8fd — under a per-call `:strict` on the event that FINISHES
            the child, the completion carrier inherits `:strict`, so the parent's
            resolution action does NOT mint its generator-backed fact
            (missing-required) and the parent never reaches `:ready`. The `:live`
            foil mints it and advances, so the policy genuinely crosses the
            child→parent edge rather than riding along as an inert keyword."
    (let [live-calls (atom 0)]
      (reg-roll! live-calls 6)
      (rf/reg-machine :sm5l/ca (mk-plain-child))
      (reg-resolution-parent! :sm5l/rp :sm5l/ca)
      (rf/dispatch-sync [:sm5l/rp [:start]])
      (let [a (get-in (join-state :sm5l/rp) [:children :a])]
        (rf/dispatch-sync [a [:go]])                        ;; default :live
        (is (= 1 @live-calls) "the :live resolution minted its downstream fact")
        (is (= :ready (rf.machines.test-support/machine-state :sm5l/rp))
            "the :live join resolved and advanced through the resolution action")))
    (let [strict-calls (atom 0)]
      (reg-roll! strict-calls 6)
      (rf/reg-machine :sm5s/ca (mk-plain-child))
      (reg-resolution-parent! :sm5s/rp :sm5s/ca)
      (rf/dispatch-sync [:sm5s/rp [:start]])
      (let [a (get-in (join-state :sm5s/rp) [:children :a])]
        (rf.machines.test-support/reset-captured!)
        (rf/dispatch-sync [a [:go]]
                          {:rf.cofx {:rf/time-ms 1} :rf.cofx/mint-policy :strict})
        (is (zero? @strict-calls)
            "the inherited :strict reached the resolution — its generator was NOT run")
        (is (not= :ready (rf.machines.test-support/machine-state :sm5s/rp))
            "the resolution action failed missing-required, so the parent did not advance")
        (is (= 1 (count (missing-required-errors)))
            "the canonical missing-fact outcome fired at the inherited resolution")))))
