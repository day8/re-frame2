(ns re-frame.epoch-replay-cljs-test
  "rf2-ov144 — `replay-epoch!`: strict replay from a retained epoch id, in
  ONE call (Tool-Pair §Replay).

  Before this surface every proof of replay hand-extracted a record off
  `rf/epoch-history` and re-dispatched its four slots by hand
  (`epoch_test.clj`, `machine_minted_cofx_replay_token_test.clj`,
  `epoch_override_capture_test.clj`, `join_strict_mint_epoch_replay_test.clj`).
  None proved that a programmer — or an off-box tool, which only ever sees
  the `:trigger-event` args as `:rf/redacted` — can name one retained epoch
  and replay it faithfully in one supported call. This suite pins that:

    1. From only a frame-id + epoch-id, `replay-epoch!` re-presents the raw
       argument-bearing `:trigger-event`, the recorded post-generation
       `:rf.cofx` token under hard-wired `:strict`, and BOTH recorded
       override maps — the generator is NOT consulted, the recorded
       effective chain is used, and the arg the off-box projection redacts
       reaches the handler verbatim.
    2. The state semantics are the documented ones: no implicit restore,
       the replayed dispatch records a NEW ordinary epoch, and the recorded
       `:rf/time-ms` makes its `:committed-at` replay-stable.
    3. A declared fact ABSENT from the token stays the canonical strict
       `:rf.error/missing-required-cofx` — no live mint fallback.
    4. Unknown / aged-out ids, an unknown frame, halted and synthetic
       records, a recorded `:rf/fn-override`, and a call from inside a
       drain are refused with stable structured reasons BEFORE anything
       dispatches.
    5. Composition with `restore-epoch!`: a mid-run machine-minted fact
       replays deterministically after rewinding, with the generator idle.

  `.cljc` under a `-cljs-test` name so the consolidated `:node-test` build
  (`cljs-test$`) AND the artefact's `clojure -M:test` (`.*-test$`) both run
  it — the pair-MCP consumer of this surface is CLJS."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.epoch :as rf.epoch]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            ;; Side-effect require — publishes the machines late-bind hooks
            ;; for the restore→replay composition proof below.
            [re-frame.machines]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn []
                (rf.epoch/clear-history!)
                (rf.epoch/clear-epoch-listeners!))}))

(def ^:private frame-id :epoch-replay/main)

(defn- history [] (rf/epoch-history frame-id))
(defn- last-record [] (last (history)))
(defn- items [] (:items (rf/app-db-value frame-id)))

(defn- ex-id
  "Run `f`; return the `:rf.error/id` of the ExceptionInfo it threw, or nil."
  [f]
  (try (f) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

;; ---------------------------------------------------------------------------
;; The faithful replay — AC1 / AC2 / AC5 / AC6
;; ---------------------------------------------------------------------------

(deftest replay-by-id-re-presents-recorded-facts-args-and-overrides
  (testing "one call from a retained epoch id re-drives the recorded event with
            its raw args, its recorded post-generation :rf.cofx under :strict,
            and BOTH recorded override maps — generator idle, recorded chain used"
    (rf/make-frame {:id frame-id})
    (let [gen-calls  (atom 0)
          real-fired (atom 0)
          stub-fired (atom 0)
          audited    (atom 0)]
      (rf/reg-cofx :replay/minted
        {:recordable? true
         :doc "generator-backed recordable fact — minted on :live, recorded"}
        (fn [] (swap! gen-calls inc) {:token (str "gen-" @gen-calls)}))
      (rf/reg-fx :replay/real-fx (fn [_ _] (swap! real-fired inc)))
      (rf/reg-fx :replay/stub-fx (fn [_ _] (swap! stub-fired inc)))
      (rf/reg-interceptor ::audit {:before (fn [ctx] (swap! audited inc) ctx)})
      (rf/reg-event :replay/add
        {:rf.cofx/requires [:rf/time-ms :replay/minted]
         :interceptors     [::audit]}
        (fn [{:keys [db] minted :replay/minted t :rf/time-ms} [_ {:keys [text]}]]
          {:db (update db :items (fnil conj [])
                       {:text text :token (:token minted) :at t})
           :fx [[:replay/real-fx nil]]}))

      ;; ---- the ORIGINAL run: live mint, real fx stubbed, audit removed ----
      (rf/dispatch-sync [:replay/add {:text "buy milk"}]
                        {:frame                 frame-id
                         :rf.cofx               {:rf/time-ms 1781078400123}
                         :fx-overrides          {:replay/real-fx :replay/stub-fx}
                         :interceptor-overrides {::audit nil}})
      (is (= 1 @gen-calls) "the live run minted the fact once")
      (is (= [1 0 0] [@stub-fired @real-fired @audited])
          "the live run redirected the fx to the stub and removed the audit interceptor")
      (is (= [{:text "buy milk" :token "gen-1" :at 1781078400123}] (items))
          "the live handler folded the raw arg, the minted fact and the causal time")

      (let [r (last-record)]
        (is (= [:replay/add {:text "buy milk"}] (:trigger-event r))
            "the record retains the RAW argument-bearing trigger")
        (is (= {:token "gen-1"} (:replay/minted (:rf.cofx r)))
            "the record's :rf.cofx is the post-generation token")
        (is (= {:replay/real-fx :replay/stub-fx} (:fx-overrides r)))
        (is (= {::audit nil} (:interceptor-overrides r)))
        ;; AC2 / AC6 — the arg an off-box consumer would have to copy by hand
        ;; is exactly the one the projection never exposes.
        (let [projected (:trigger-event (rf/projected-record r))]
          (is (= :replay/add (first projected))
              "off-box projection keeps the event id")
          (is (= [:rf/redacted] (vec (rest projected)))
              "off-box projection exposes the arg ONLY as :rf/redacted — the
               manual copy route is closed, which is why replay must resolve
               the raw record in-process"))

        ;; ---- the REPLAY: one call, nothing copied --------------------------
        (reset! gen-calls 0)
        (reset! stub-fired 0)
        (reset! real-fired 0)
        (reset! audited 0)
        (let [res (rf/replay-epoch! frame-id (:epoch-id r))]
          (is (true? (:ok? res)) (str "replay succeeded: " (pr-str res)))
          (is (= (:epoch-id r) (:source-epoch-id res)))
          (is (= :replay/add (:event-id res)))
          (is (some? (:epoch-id res)) "the replayed dispatch recorded a new epoch")
          (is (not= (:epoch-id r) (:epoch-id res))
              "the new epoch is a NEW record, not the source")

          ;; THE CONTROL: strict re-presentation — the generator is never consulted.
          (is (= 0 @gen-calls)
              "the generator was NOT consulted — the recorded fact was re-presented")
          ;; The recorded effective chain: fx redirect + interceptor removal.
          (is (= [1 0 0] [@stub-fired @real-fired @audited])
              "the recorded :fx-overrides / :interceptor-overrides were re-supplied —
               the stub fired, the real fx did not, the audit stayed removed")
          ;; AC5 — no implicit restore: the replay ran on the CURRENT state, so
          ;; the item list grew from one to two, and the second entry carries
          ;; the raw arg + the RECORDED fact + the RECORDED time.
          (is (= [{:text "buy milk" :token "gen-1" :at 1781078400123}
                  {:text "buy milk" :token "gen-1" :at 1781078400123}]
                 (items))
              "the handler received the raw arg verbatim plus the recorded
               fact and time; state was NOT rewound first")

          (let [new-r (last-record)]
            (is (= (:epoch-id res) (:epoch-id new-r)))
            (is (= [:replay/add {:text "buy milk"}] (:trigger-event new-r))
                "the new epoch is an ordinary record of the replayed event")
            (is (= 1781078400123 (:committed-at new-r))
                "re-presenting the recorded :rf/time-ms makes :committed-at replay-stable")
            (is (= (:db-after r) (:db-before new-r))
                "the new epoch starts from the frame's CURRENT state (the
                 source epoch's :db-after) — replay did not restore")
            (is (= (:fx-overrides r) (:fx-overrides new-r)))
            (is (= (:interceptor-overrides r) (:interceptor-overrides new-r)))))))))

(deftest replay-opts-pass-through-only-the-slots-replay-does-not-own
  (testing "the 3-arity threads :origin through to the dispatch, while a caller
            value under an owned key (:rf.cofx / :fx-overrides / :frame) is
            discarded — the record is the only source of replay material"
    (rf/make-frame {:id frame-id})
    (rf/reg-fx :replay/real-fx (fn [_ _] nil))
    (rf/reg-fx :replay/stub-fx (fn [_ _] nil))
    (rf/reg-event :replay/noop (fn [{:keys [db]} _] {:db db :fx [[:replay/real-fx nil]]}))
    (rf/dispatch-sync [:replay/noop]
                      {:frame        frame-id
                       :rf.cofx      {:rf/time-ms 42}
                       :fx-overrides {:replay/real-fx :replay/stub-fx}})
    (let [r   (last-record)
          res (rf/replay-epoch! frame-id (:epoch-id r)
                                {:origin       :pair
                                 :frame        :some/other-frame
                                 :rf.cofx      {:rf/time-ms 99}
                                 :fx-overrides {:replay/real-fx nil}})]
      (is (true? (:ok? res)))
      (is (= frame-id (:frame res)) "the target frame is the source frame, not the caller's")
      (let [new-r (last-record)]
        (is (= 42 (:committed-at new-r))
            "the caller's :rf.cofx was discarded — the RECORDED token was re-presented")
        (is (= {:replay/real-fx :replay/stub-fx} (:fx-overrides new-r))
            "the caller's :fx-overrides was discarded — the RECORDED map was re-supplied")
        (is (some #(= :pair (get-in % [:tags :rf.event/origin])) (:trace-events new-r))
            ":origin — a slot replay does not own — rode through to the dispatch")))))

;; ---------------------------------------------------------------------------
;; The canonical strict failure — AC4, second sentence
;; ---------------------------------------------------------------------------

(deftest replay-strict-refuses-to-mint-a-fact-absent-from-the-record
  (testing "a declared recordable fact the record does not carry is the canonical
            :rf.error/missing-required-cofx — the generator is never run"
    (rf/make-frame {:id frame-id})
    (let [calls-a (atom 0)
          calls-b (atom 0)
          ran     (atom 0)]
      (rf/reg-cofx :replay/a {:recordable? true} (fn [] (swap! calls-a inc) :a))
      (rf/reg-cofx :replay/b {:recordable? true} (fn [] (swap! calls-b inc) :b))
      (rf/reg-event :replay/needs
        {:rf.cofx/requires [:replay/a]}
        (fn [{:keys [db]} _] (swap! ran inc) {:db db}))
      (rf/dispatch-sync [:replay/needs] {:frame frame-id})
      (let [r (last-record)]
        (is (= :a (:replay/a (:rf.cofx r))) "the record carries the minted :replay/a")
        (is (not (contains? (:rf.cofx r) :replay/b)) "…and no :replay/b")
        ;; The code moved on since the recording: the handler now also
        ;; declares :replay/b, which the record cannot supply.
        (rf/reg-event :replay/needs
          {:rf.cofx/requires [:replay/a :replay/b]}
          (fn [{:keys [db]} _] (swap! ran inc) {:db db}))
        (reset! calls-a 0)
        (reset! calls-b 0)
        (reset! ran 0)
        (is (= :rf.error/missing-required-cofx
               (ex-id #(rf/replay-epoch! frame-id (:epoch-id r))))
            "the strict dispatch failed LOUD with the canonical missing-required error")
        (is (= [0 0] [@calls-a @calls-b])
            "NO generator ran — neither the present fact (re-presented) nor the
             absent one (refused under :strict, never live-minted)")
        (is (= 0 @ran) "the handler did not run")))))

;; ---------------------------------------------------------------------------
;; Refusals before dispatch — AC4, first sentence
;; ---------------------------------------------------------------------------

(deftest replay-refuses-before-dispatch-on-non-replayable-input
  (rf/make-frame {:id frame-id})
  (let [ran        (atom 0)
        real-fired (atom 0)]
    (rf/reg-fx :replay/real-fx (fn [_ _] (swap! real-fired inc)))
    (rf/reg-event :replay/probe
      (fn [{:keys [db]} _] (swap! ran inc) {:db (assoc db :probed true)
                                             :fx [[:replay/real-fx nil]]}))
    (rf/dispatch-sync [:replay/probe] {:frame frame-id})
    (let [probe-id (:epoch-id (last-record))
          n-before (count (history))]

      (testing "unknown / aged-out id"
        (let [res (rf/replay-epoch! frame-id ::never-recorded)]
          (is (= {:ok? false :reason :rf.epoch/replay-unknown-epoch
                  :frame frame-id :epoch-id ::never-recorded :history-size n-before}
                 res))))

      (testing "unknown frame"
        (let [res (rf/replay-epoch! :replay/no-such-frame probe-id)]
          (is (false? (:ok? res)))
          (is (= :rf.error/no-such-handler (:reason res)))
          (is (= :frame (:kind res)))))

      (testing "synthetic record (replace-frame-state!)"
        (is (true? (rf/replace-frame-state! frame-id {:rf.db/app {:injected true}})))
        (let [synthetic (last-record)
              res       (rf/replay-epoch! frame-id (:epoch-id synthetic))]
          (is (= :rf.epoch/db-replaced (:event-id synthetic)))
          (is (= :rf.epoch/replay-non-replayable-record (:reason res)))
          (is (= :synthetic (:cause res)))))

      (testing "recorded :rf/fn-override"
        (rf/dispatch-sync [:replay/probe]
                          {:frame        frame-id
                           :fx-overrides {:replay/real-fx (fn [_ _] :cljs-only)}})
        (let [fn-r (last-record)
              res  (rf/replay-epoch! frame-id (:epoch-id fn-r))]
          (is (= {:replay/real-fx :rf/fn-override} (:fx-overrides fn-r))
              "the router marker-ized the fn at capture")
          (is (= :rf.epoch/replay-unreplayable-fx-override (:reason res)))
          (is (= [:replay/real-fx] (:fx-ids res)))))

      (testing "called from inside a drain"
        (let [attempt (atom ::unset)]
          (rf/reg-event :replay/reentrant
            (fn [{:keys [db]} _]
              (reset! attempt (rf/replay-epoch! frame-id probe-id))
              {:db db}))
          (rf/dispatch-sync [:replay/reentrant] {:frame frame-id})
          (is (= :rf.epoch/replay-during-drain (:reason @attempt)))
          (is (false? (:ok? @attempt)))))

      (testing "no refusal above dispatched the probe"
        (is (= 2 @ran)
            "the probe handler ran exactly twice — the ORIGINAL run and the live
             fn-override recording run; no refusal reached it")
        (is (= 1 @real-fired)
            "the probe's real fx fired exactly once (the fn-override run redirected
             it) — no refusal re-fired it")))))

(deftest replay-refuses-a-halted-record
  (testing "a :halted-depth record carries partial state and is not a replay source"
    (rf/make-frame {:id :epoch-replay/halt :drain-depth 3})
    (rf/reg-event :replay/loop
      (fn [{:keys [db]} _]
        {:db (update db :n (fnil inc 0))
         :fx [[:dispatch [:replay/loop]]]}))
    (rf/dispatch-sync [:replay/loop] {:frame :epoch-replay/halt})
    (let [halted (last (rf/epoch-history :epoch-replay/halt))
          n      (:n (rf/app-db-value :epoch-replay/halt))
          res    (rf/replay-epoch! :epoch-replay/halt (:epoch-id halted))]
      (is (= :halted-depth (:outcome halted)) "the trailing record is the halt marker")
      (is (= :rf.epoch/replay-non-replayable-record (:reason res)))
      (is (= :halted (:cause res)))
      (is (= :halted-depth (:outcome res)) "the refusal carries the record's outcome")
      (is (some? (:halt-reason res)) "…and its structured halt reason")
      (is (= n (:n (rf/app-db-value :epoch-replay/halt)))
          "nothing dispatched — the counter did not move"))))

;; ---------------------------------------------------------------------------
;; Composition with restore — a mid-run machine mint, replayed by id
;; ---------------------------------------------------------------------------

(defn- machine-state [machine-id]
  (-> (:rf.db/runtime (rf/frame-state-value frame-id))
      (get-in [:rf.runtime/machines :snapshots machine-id])
      :state))

(defn- mint-machine
  "`:go` raises `[:inner]`; `:inner`'s guard requires the generator-backed
  `:replay/gen`, minted MID-DRAIN under :live and captured into the record's
  :rf.cofx replay token (the machine_minted_cofx_replay_token_test shape)."
  [seen]
  {:initial :a
   :data    {}
   :guards  {:check {:rf.cofx/requires [:replay/gen]
                     :fn (fn [{cofx :rf.cofx}]
                           (reset! seen (:replay/gen cofx))
                           (some? (:replay/gen cofx)))}}
   :actions {:raise-inner (fn [_] {:fx [[:raise [:inner]]]})}
   :states  {:a    {:on {:go {:target :b :action :raise-inner}}}
             :b    {:on {:inner {:target :done :guard :check}}}
             :done {}}})

(deftest restore-then-replay-by-id-reproduces-a-mid-run-machine-mint
  (testing "rewind with restore-epoch!, then replay the machine cascade by id:
            the guard reads the RECORDED mid-run fact, the generator stays idle,
            and the machine reaches :done again"
    (rf/make-frame {:id frame-id})
    (let [calls (atom 0)
          seen  (atom ::unset)]
      (rf/reg-cofx :replay/gen {:recordable? true} (fn [] (swap! calls inc) 100))
      (rf/reg-machine :replay/mint (mint-machine seen))
      (rf/reg-event :replay/anchor (fn [{:keys [db]} _] {:db (assoc db :anchored true)}))
      ;; An anchor epoch to rewind to, taken before the machine has run.
      (rf/dispatch-sync [:replay/anchor] {:frame frame-id})
      (let [anchor-id (:epoch-id (last-record))]
        (is (not= :done (machine-state :replay/mint)) "the machine has not run yet")
        ;; The live macrostep: :go → raise :inner → guard mints :replay/gen.
        (rf/dispatch-sync [:replay/mint [:go]]
                          {:frame frame-id :rf.cofx {:rf/time-ms 111}})
        (let [r (last-record)]
          (is (= 1 @calls) "the live run minted the mid-run fact once")
          (is (= :done (machine-state :replay/mint)))
          (is (= 100 (:replay/gen (:rf.cofx r)))
              "the record's replay token captured the MID-RUN minted fact")

          ;; Rewind: the machine is back in :a; the ring keeps the record.
          (is (true? (rf/restore-epoch! frame-id anchor-id)))
          (is (not= :done (machine-state :replay/mint))
              "restore rewound the machine to its pre-run snapshot")
          (reset! calls 0)
          (reset! seen ::unset)

          (let [res (rf/replay-epoch! frame-id (:epoch-id r))]
            (is (true? (:ok? res)) (str "replay after restore succeeded: " (pr-str res)))
            (is (= 100 @seen)
                "the guard read the RECORDED fact verbatim — strict re-presentation")
            (is (= 0 @calls) "the generator was NOT consulted")
            (is (= :done (machine-state :replay/mint))
                "the replayed macrostep reproduced the live decision")))))))

;; ---------------------------------------------------------------------------
;; The facade reaches the artefact
;; ---------------------------------------------------------------------------

(deftest facade-and-artefact-forms-are-the-same-operation
  (testing "rf/replay-epoch! late-binds to re-frame.epoch/replay-epoch!"
    (rf/make-frame {:id frame-id})
    (let [res-facade   (rf/replay-epoch! frame-id ::nope)
          res-artefact (rf.epoch/replay-epoch! frame-id ::nope)]
      (is (= res-facade res-artefact))
      (is (= :rf.epoch/replay-unknown-epoch (:reason res-facade))))))
