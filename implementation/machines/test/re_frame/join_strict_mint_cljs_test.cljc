(ns re-frame.join-strict-mint-cljs-test
  "rf2-xfk6fn — CAUSAL proof that a `:spawn-all` join completion honours the
  EFFECTIVE cofx mint policy end-to-end, not merely by carrying the policy
  keyword in the transport opts.

  The rf2-lud4af / rf2-t154jx suites proved the recordable `:rf.machine/join-attempt`
  fact rides `:rf.cofx`, survives an EDN round-trip, and that the transport
  re-dispatch carries `:rf.cofx/mint-policy :strict` in its opts. But every join
  completion TARGET in those suites declared NO generator-backed coeffect
  requirement, so `:strict` and `:live` were OBSERVATIONALLY IDENTICAL there — the
  `:strict` assertion only ever saw the policy KEYWORD in the router options, and
  a transport that silently fell back to `:live` minting would have passed
  exact-head CI unchanged. The alleged replay case supplied `:rf.cofx` but never
  `:rf.cofx/mint-policy :strict`, so it ran under the runtime default `:live`;
  stripping the join-attempt exercised the private authority gate, not strict
  rejection of a MISSING RECORDED FACT.

  These tests add ONE real generator-backed recordable coeffect to a join
  completion target (a member child's completion action) and compare `:strict`
  against `:live` through the SAME record/replay machinery — no join-specific
  replay format, coeffect, or test framework. The load-bearing promise: a
  completion under inherited `:strict` does NOT consult the host — it replays from
  recorded causal facts, and an absent fact is the canonical
  `:rf.error/missing-required-cofx`, never a silent live-mint no-op.

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
  "The JOIN COMPLETION TARGET: a member child whose completion action
  (`:dispatch-done`, fired on `:go` → `:done`) DECLARES a generator-backed
  recordable coeffect `:strictmint/roll` and forwards the minted value on its
  completion carrier `[parent-id [:child/done <id> <roll>]]` — the value flows
  through the SAME handler-return boundary the recordable transport stamps. The
  action runs only when the ensure step satisfies its `:rf.cofx/requires` under
  the effective mint policy: `:live` mints, `:strict` reads the recorded fact or
  fails missing-required."
  [parent-id]
  {:initial :running
   :data    {:id nil}
   :actions {:record-id     (fn [{data :data ev :event}] {:data (assoc data :id (second ev))})
             :dispatch-done {:rf.cofx/requires [:strictmint/roll]
                             :fn (fn [{data :data cofx :rf.cofx}]
                                   {:fx [[:dispatch [parent-id [:child/done (:id data)
                                                                (:strictmint/roll cofx)]]]]})}}
   :states  {:running {:on {:set-id {:action :record-id}
                            :go     {:target :done :action :dispatch-done}}}
             :done {}}})

(defn- mk-plain-child
  "A member child with NO coeffect requirement — completes cleanly under any mint
  policy. Pairs with `mk-completing-child` so the two-child `:all` join stays OPEN
  after one worker folds (a non-decisive fold, so the accepted child's terminal
  rides the `:rf.machine.spawn-all/child-completed` trace we assert on)."
  [parent-id]
  {:initial :running
   :data    {:id nil}
   :actions {:record-id     (fn [{data :data ev :event}] {:data (assoc data :id (second ev))})
             :dispatch-done (fn [{data :data}]
                              {:fx [[:dispatch [parent-id [:child/done (:id data)]]]]})}
   :states  {:running {:on {:set-id {:action :record-id}
                            :go     {:target :done :action :dispatch-done}}}
             :done {}}})

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
                         :on-child-done   :child/done
                         :on-child-error  :child/failed
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
  "The FIRST observed completion carrier `[parent-id [:child/done child-id v]]` in
  `sink`, or nil — returns the whole event vector so its forwarded roll can be
  read."
  [sink parent-id child-id]
  (some (fn [[event _opts]]
          (when (and (= parent-id (first event))
                     (let [inner (second event)]
                       (and (vector? inner)
                            (= :child/done (first inner))
                            (= child-id (second inner)))))
            event))
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
      (rf/reg-machine :sm1/ta (mk-completing-child :sm1/rp))
      (rf/reg-machine :sm1/pb (mk-plain-child :sm1/rp))
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
      (rf/reg-machine :sm2/ta (mk-completing-child :sm2/rp))
      (rf/reg-machine :sm2/pb (mk-plain-child :sm2/rp))
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
        (is (= [:sm2/rp [:child/done :a 6]] (completion-of sink :sm2/rp :a))
            "the completion carrier forwarded the minted roll (6)")
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
      (rf/reg-machine :sm3/ta (mk-completing-child :sm3/rp))
      (rf/reg-machine :sm3/pb (mk-plain-child :sm3/rp))
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
        (let [recorded-roll (nth (second (completion-of sink :sm3/rp :a)) 2)
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
;; (5) the recordable TRANSPORT itself carries the policy: an inherited :strict
;;     propagates through `:rf.machine/join-dispatch` / `child-dispatch!` to the
;;     join RESOLUTION dispatch, gating a downstream generator-backed cofx there.
;;     This is the direct proof that the transport does not silently fall back to
;;     :live minting once the completion has crossed the child→parent boundary.
;; ---------------------------------------------------------------------------

(defn- reg-resolution-parent!
  "A single-child `:any` join whose RESOLUTION action (`:res-action`, fired when
  the parent handles the `:on-some-complete` event the fold dispatches) declares a
  generator-backed `:strictmint/roll`. The completion re-dispatch carries the
  member child's envelope through the transport, so the resolution dispatch
  INHERITS the child's mint policy."
  [parent-kw child-kw]
  (rf/reg-machine parent-kw
    {:initial :idle
     :states  {:idle   {:on {:start :racing}}
               :racing {:spawn-all
                        {:children         [{:id :a :machine-id child-kw :start [:set-id :a]}]
                         :join             :any
                         :on-child-done    :child/done
                         :on-child-error   :child/failed
                         :on-some-complete [:some/done]}
                        :on {:some/done {:target :ready :action :res-action}}}
               :ready {}}
     :actions {:res-action
               {:rf.cofx/requires [:strictmint/roll]
                :fn (fn [{data :data cofx :rf.cofx}]
                      {:data (assoc data :roll (:strictmint/roll cofx))})}}}))

(deftest transport-propagates-strict-to-inherited-resolution-dispatch
  (testing "rf2-xfk6fn — the completion re-dispatch inherits the emitting child's
            mint policy THROUGH the `:rf.machine/join-dispatch` transport and the
            fold's resolution dispatch: under a per-call `:strict` on the child's
            completing dispatch, the join folds + resolves but the RESOLUTION
            action's downstream generator-backed `:strictmint/roll` is NOT minted
            (missing-required), so the parent never advances to `:ready`. The
            `:live` foil mints it and advances — so the transport genuinely carries
            the policy across the child→parent boundary, not merely as an inert opt
            keyword."
    (let [live-calls (atom 0)]
      (reg-roll! live-calls 6)
      (rf/reg-machine :sm4l/ca (mk-plain-child :sm4l/rp))
      (reg-resolution-parent! :sm4l/rp :sm4l/ca)
      (rf/dispatch-sync [:sm4l/rp [:start]])
      (let [a (get-in (join-state :sm4l/rp) [:children :a])]
        (rf/dispatch-sync [a [:go]])                        ;; :live
        (is (= 1 @live-calls) "the :live resolution minted its downstream fact")
        (is (= :ready (rf.machines.test-support/machine-state :sm4l/rp))
            "the :live join resolved and advanced through the resolution action")))
    (let [strict-calls (atom 0)]
      (reg-roll! strict-calls 6)
      (rf/reg-machine :sm4s/ca (mk-plain-child :sm4s/rp))
      (reg-resolution-parent! :sm4s/rp :sm4s/ca)
      (rf/dispatch-sync [:sm4s/rp [:start]])
      (let [a (get-in (join-state :sm4s/rp) [:children :a])]
        (rf.machines.test-support/reset-captured!)
        (rf/dispatch-sync [a [:go]]
                          {:rf.cofx {:rf/time-ms 1} :rf.cofx/mint-policy :strict})
        (is (zero? @strict-calls)
            "the inherited :strict propagated through the transport — the
             downstream resolution generator was NOT run")
        (is (not= :ready (rf.machines.test-support/machine-state :sm4s/rp))
            "the resolution action failed missing-required, so the parent did not advance")
        (is (= 1 (count (missing-required-errors)))
            "the canonical missing-fact outcome fired at the inherited resolution")))))
