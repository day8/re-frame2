(ns re-frame.epoch-replay-cljs-test
  "`replay-epoch!`: strict replay of one retained epoch by id (Tool-Pair
  §Replay).

  Pinned here: the recorded raw `:trigger-event`, post-generation `:rf.cofx`
  token (under `:strict`) and both override maps are re-presented with no
  implicit restore; the refusals made before anything dispatches, capture-time
  classification loss among them; the composition with `restore-epoch!`; and
  that the reported `:epoch-id` is the replayed dispatch's own epoch, or nil —
  never another dispatch's.

  `.cljc` under a `-cljs-test` name so both the `:node-test` build and the
  artefact's `clojure -M:test` run it."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.epoch :as rf.epoch]
            #?(:clj [re-frame.epoch.state :as rf.epoch.state])
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            ;; Side-effect require — publishes the machines late-bind hooks
            ;; for the restore→replay composition proof below.
            [re-frame.machines])
  #?(:clj (:import [java.util.concurrent CountDownLatch TimeUnit])))

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
;; The faithful replay
;; ---------------------------------------------------------------------------

(deftest replay-by-id-re-presents-recorded-facts-args-and-overrides
  (testing "one call from a retained epoch id re-drives the recorded event with
            its raw args, its recorded post-generation :rf.cofx under :strict,
            and BOTH recorded override maps — generator idle, recorded chain
            used, no implicit restore"
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

      ;; The original run: live mint, real fx stubbed, audit removed.
      (rf/dispatch-sync [:replay/add {:text "buy milk"}]
                        {:frame                 frame-id
                         :rf.cofx               {:rf/time-ms 1781078400123}
                         :fx-overrides          {:replay/real-fx :replay/stub-fx}
                         :interceptor-overrides {::audit nil}})

      (let [r (last-record)]
        (is (= [[:replay/add {:text "buy milk"}]
                {:token "gen-1"}
                {:replay/real-fx :replay/stub-fx}
                {::audit nil}]
               ((juxt :trigger-event (comp :replay/minted :rf.cofx)
                      :fx-overrides :interceptor-overrides) r))
            "the record retains the raw trigger, the minted fact and both override maps")
        (is (= [:replay/add :rf/redacted] (:trigger-event (rf/project-egress r)))
            "off-box the arg is only `:rf/redacted`, which is why replay resolves
             the raw record in-process")

        (doseq [a [gen-calls stub-fired real-fired audited]] (reset! a 0))
        (let [res   (rf/replay-epoch! frame-id (:epoch-id r))
              new-r (last-record)]
          (is (= {:ok? true :frame frame-id :source-epoch-id (:epoch-id r)
                  :event-id :replay/add :epoch-id (:epoch-id new-r)}
                 res))
          (is (not= (:epoch-id r) (:epoch-id new-r)) "the replay recorded a NEW epoch")
          (is (= [0 1 0 0] [@gen-calls @stub-fired @real-fired @audited])
              "the generator was not consulted, and the recorded overrides were
               re-supplied: the stub fired, the real fx did not, the audit stayed removed")
          (is (= [{:text "buy milk" :token "gen-1" :at 1781078400123}
                  {:text "buy milk" :token "gen-1" :at 1781078400123}]
                 (items))
              "the handler received the raw arg plus the recorded fact and time,
               on the CURRENT state — replay did not restore")
          (is (= 1781078400123 (:committed-at new-r))
              "the recorded :rf/time-ms makes :committed-at replay-stable"))))))

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
    (rf/replay-epoch! frame-id (:epoch-id (last-record))
                      {:origin       :pair
                       :frame        :some/other-frame
                       :rf.cofx      {:rf/time-ms 99}
                       :fx-overrides {:replay/real-fx nil}})
    (let [new-r (last-record)]
      (is (some #(= :pair (get-in % [:tags :rf.event/origin])) (:trace-events new-r))
          ":origin rode through to the replay's dispatch on the source frame")
      (is (= [42 {:replay/real-fx :replay/stub-fx}]
             ((juxt :committed-at :fx-overrides) new-r))
          "the caller's :rf.cofx and :fx-overrides were discarded for the recorded ones"))))

;; ---------------------------------------------------------------------------
;; The canonical strict failure
;; ---------------------------------------------------------------------------

(deftest replay-strict-refuses-to-mint-a-fact-absent-from-the-record
  (testing "a declared recordable fact the record does not carry is the canonical
            :rf.error/missing-required-cofx — no generator runs"
    (rf/make-frame {:id frame-id})
    (let [calls (atom 0)]
      (rf/reg-cofx :replay/a {:recordable? true} (fn [] (swap! calls inc) :a))
      (rf/reg-cofx :replay/b {:recordable? true} (fn [] (swap! calls inc) :b))
      (rf/reg-event :replay/needs
        {:rf.cofx/requires [:replay/a]}
        (fn [{:keys [db]} _] {:db db}))
      (rf/dispatch-sync [:replay/needs] {:frame frame-id})
      (let [epoch-id (:epoch-id (last-record))]
        ;; Since the recording the handler also declares :replay/b, which the
        ;; record cannot supply.
        (rf/reg-event :replay/needs
          {:rf.cofx/requires [:replay/a :replay/b]}
          (fn [{:keys [db]} _] (swap! calls inc) {:db db}))
        (reset! calls 0)
        (is (= :rf.error/missing-required-cofx
               (ex-id #(rf/replay-epoch! frame-id epoch-id))))
        (is (zero? @calls)
            "neither generator ran (the present fact re-presented, the absent one
             refused under :strict) and the handler did not run")))))

;; ---------------------------------------------------------------------------
;; Refusals before dispatch
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
        (is (= {:ok? false :reason :rf.epoch/replay-unknown-epoch
                :frame frame-id :epoch-id ::never-recorded :history-size n-before}
               (rf/replay-epoch! frame-id ::never-recorded))))

      (testing "unknown frame"
        (is (= {:ok? false :reason :rf.error/no-such-handler :kind :frame}
               (select-keys (rf/replay-epoch! :replay/no-such-frame probe-id)
                            [:ok? :reason :kind]))))

      (testing "synthetic record (replace-frame-state!)"
        (rf/replace-frame-state! frame-id {:rf.db/app {:injected true}})
        (is (= [:rf.epoch/replay-non-replayable-record :synthetic]
               ((juxt :reason :cause)
                (rf/replay-epoch! frame-id (:epoch-id (last-record)))))))

      (testing "recorded :rf/fn-override"
        (rf/dispatch-sync [:replay/probe]
                          {:frame        frame-id
                           :fx-overrides {:replay/real-fx (fn [_ _] :cljs-only)}})
        (let [fn-r (last-record)]
          (is (= {:replay/real-fx :rf/fn-override} (:fx-overrides fn-r))
              "the router marker-ized the fn at capture")
          (is (= [:rf.epoch/replay-unreplayable-fx-override [:replay/real-fx]]
                 ((juxt :reason :fx-ids) (rf/replay-epoch! frame-id (:epoch-id fn-r)))))))

      (testing "called from inside a drain"
        (let [attempt (atom ::unset)]
          (rf/reg-event :replay/reentrant
            (fn [{:keys [db]} _]
              (reset! attempt (rf/replay-epoch! frame-id probe-id))
              {:db db}))
          (rf/dispatch-sync [:replay/reentrant] {:frame frame-id})
          (is (= [false :rf.epoch/replay-during-drain] ((juxt :ok? :reason) @attempt)))))

      (testing "no refusal above dispatched the probe"
        (is (= [2 1] [@ran @real-fired])
            "the handler ran for the original run and the fn-override recording
             run only, and its real fx fired once (the fn-override run redirected it)")))))

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
      (is (= [:rf.epoch/replay-non-replayable-record :halted :halted-depth
              (:halt-reason halted)]
             ((juxt :reason :cause :outcome :halt-reason) res))
          "the refusal carries the record's outcome and structured halt reason")
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
  :rf.cofx replay token."
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
        (rf/dispatch-sync [:replay/mint [:go]]
                          {:frame frame-id :rf.cofx {:rf/time-ms 111}})
        (let [r (last-record)]
          (is (= [100 :done] [(:replay/gen (:rf.cofx r)) (machine-state :replay/mint)])
              "the live run reached :done and its replay token captured the MID-RUN fact")
          (is (true? (rf/restore-epoch! frame-id anchor-id)))
          (is (not= :done (machine-state :replay/mint))
              "restore rewound the machine to its pre-run snapshot")
          (reset! calls 0)
          (reset! seen ::unset)
          (let [res (rf/replay-epoch! frame-id (:epoch-id r))]
            (is (= [true 100 0 :done]
                   [(:ok? res) @seen @calls (machine-state :replay/mint)])
                "the guard read the RECORDED fact, the generator was not
                 consulted, and the replayed macrostep reproduced the live decision")))))))

;; ---------------------------------------------------------------------------
;; Incomplete evidence is refused BEFORE dispatch
;; ---------------------------------------------------------------------------
;;
;; Registration classification runs at trace capture, so a classified event
;; argument or recordable fact reaches the RAW record already substituted.
;; Re-driving it would hand the handler `:rf/redacted` or a size marker in
;; place of the value the original run consumed, writing app-db and re-firing
;; effects with data the run never saw; Tool-Pair §Replay is
;; faithful-or-fail-loud.

(deftest replay-refuses-a-record-whose-inputs-were-classified-at-capture
  (testing "a :sensitive and a :large event arg and a :sensitive recordable fact
            each mark the record as incomplete evidence: replay names every loss
            and refuses before the generator, the handler, its effect or an
            app-db write"
    (rf/make-frame {:id frame-id})
    (let [calls (atom 0)
          ran   (atom 0)
          fired (atom 0)]
      (rf/reg-cofx :replay/session
        {:recordable? true :sensitive [[:token]]}
        (fn [] (swap! calls inc) {:token "jwt-abc" :user "ada"}))
      (rf/reg-fx :replay/notify (fn [_ _] (swap! fired inc)))
      (rf/reg-event :replay/save
        {:sensitive        [[:password]]
         :large            [[:blob]]
         :rf.cofx/requires [:replay/session]}
        (fn [{:keys [db]} [_ payload]]
          (swap! ran inc)
          {:db (assoc db :last-password (:password payload))
           :fx [[:replay/notify nil]]}))
      (rf/dispatch-sync [:replay/save {:password "topsecret"
                                       :blob     (apply str (repeat 600 "X"))}]
                        {:frame frame-id})
      (let [res (rf/replay-epoch! frame-id (:epoch-id (last-record)))]
        (is (= {:ok?    false
                :reason :rf.epoch/replay-non-replayable-record
                :cause  :incomplete-inputs
                :lost   [{:slot :rf.cofx        :path [:replay/session :token] :loss :redacted}
                         {:slot :trigger-event :path [1 :blob]               :loss :elided}
                         {:slot :trigger-event :path [1 :password]           :loss :redacted}]}
               (select-keys res [:ok? :reason :cause :lost])))
        (is (= [1 1 1 "topsecret"]
               [@calls @ran @fired (:last-password (rf/app-db-value frame-id))])
            "nothing re-ran, and app-db was not written with a substitution")))))

;; ---------------------------------------------------------------------------
;; The reported epoch is the REPLAYED dispatch's own
;; ---------------------------------------------------------------------------

(def ^:private evict-frame-id :epoch-replay/eviction)

(defn- parent-handler [{:keys [db]} _]
  (cond-> {:db (update db :runs (fnil inc 0))}
    (:runs db) (assoc :fx [[:dispatch [:review/child]]])))

(defn- register-parent-and-child!
  "`:review/parent` enqueues `:review/child` only on its SECOND run, so the
  original recording retains its own epoch while the REPLAY settles a child
  after the parent."
  []
  (rf/reg-event :review/child
    (fn [{:keys [db]} _] {:db (assoc db :child true)}))
  (rf/reg-event :review/parent parent-handler))

(deftest replay-reports-nil-when-its-own-epoch-was-evicted
  (testing "a queued child that evicts the replayed event's own record does NOT
            become the reported epoch: the ring could not retain it, so the
            documented nil rides back"
    (rf/configure! {:epoch-history {:depth 1}})
    (rf/make-frame {:id evict-frame-id})
    (register-parent-and-child!)
    (rf/dispatch-sync [:review/parent] {:frame evict-frame-id})
    (let [source (last (rf/epoch-history evict-frame-id))
          res    (rf/replay-epoch! evict-frame-id (:epoch-id source))]
      (is (= [:review/child] (mapv :event-id (rf/epoch-history evict-frame-id)))
          "the replayed parent ran and its child evicted the parent's record")
      (is (= {:ok? true :frame evict-frame-id :source-epoch-id (:epoch-id source)
              :event-id :review/parent :epoch-id nil}
             res)))))

;; A public trace listener may `dispatch-sync` from the router's
;; `:rf.event/dispatched`, which is emitted before the replay's own drain: that
;; nested cascade commits INSIDE replay's armed window, before the replayed
;; event has run.

(def ^:private interleave-frame-id :epoch-replay/interleave)

(defn- add-handler [{:keys [db]} [_ amount]]
  {:db (update db :n (fnil + 0) amount)})

(defn- call-with-interleaving-listener
  "Run `(f)` with a ONE-SHOT public trace listener that dispatches
  `nested-event` into `interleave-frame-id` the first time it sees an
  `:rf.event/dispatched` for that frame — from inside the replay's own
  dispatch, before the replay drains."
  [nested-event f]
  (let [fired? (atom false)]
    (rf/register-listener! :trace ::interleave
      (fn [ev]
        (when (and (= :rf.event/dispatched (:operation ev))
                   (= interleave-frame-id (get-in ev [:tags :frame]))
                   (compare-and-set! fired? false true))
          (rf/dispatch-sync nested-event {:frame interleave-frame-id}))))
    (try
      (f)
      (finally
        (rf/unregister-listener! :trace ::interleave)))))

(deftest replay-result-is-not-rescued-by-matching-the-event-id
  (testing "the SAME handler with DIFFERENT arguments commits inside the window:
            an `:event-id` filter would still return the callback's record, so
            the correlation has to be by dispatch identity"
    (rf/configure! {:epoch-history {:depth 10}})
    (rf/make-frame {:id interleave-frame-id})
    (rf/reg-event :review/add add-handler)
    (rf/dispatch-sync [:review/add 1] {:frame interleave-frame-id})
    (let [source (last (rf/epoch-history interleave-frame-id))
          res    (call-with-interleaving-listener
                   [:review/add 100]
                   #(rf/replay-epoch! interleave-frame-id (:epoch-id source)))
          after  (rf/epoch-history interleave-frame-id)]
      (is (= [[:review/add 1] [:review/add 100] [:review/add 1]]
             (mapv :trigger-event after))
          "the callback's dispatch committed inside the window, before the replay's")
      (is (= (:epoch-id (last after)) (:epoch-id res))
          "the reported epoch is the replayed dispatch's own new record"))))

;; The concurrent sibling: another JVM thread's dispatch lands in the gap
;; between the replay arming its observation and entering `dispatch-sync!`. The
;; gap is PLACED, not raced: the real `arm-commit-observation!` runs, then the
;; replay thread parks on a latch until the other thread's dispatch has
;; returned. JVM only — CLJS has one thread and no such gap.

#?(:clj
   (defn- replay-with-foreign-dispatch-after-arming
     "Replay `source-epoch-id` in `fid` on a background thread, parking that
     thread immediately AFTER the real observation arming while THIS thread
     dispatch-syncs `foreign-event` into the same frame. Then release the
     replay and return its result. The latch bounds turn a regression into one
     failed deftest, not a hang."
     [fid source-epoch-id foreign-event]
     (let [armed    (CountDownLatch. 1)
           release  (CountDownLatch. 1)
           real-arm rf.epoch.state/arm-commit-observation!]
       (with-redefs [rf.epoch.state/arm-commit-observation!
                     (fn [frame]
                       (let [token (real-arm frame)]
                         (.countDown armed)
                         (.await release 10 TimeUnit/SECONDS)
                         token))]
         (let [replay (future (rf/replay-epoch! fid source-epoch-id))]
           (try
             (is (.await armed 10 TimeUnit/SECONDS)
                 "the replay thread armed its observation and parked")
             (rf/dispatch-sync foreign-event {:frame fid})
             (finally
               (.countDown release)))
           (deref replay 10000 ::timeout))))))

#?(:clj
   (deftest replay-result-names-its-own-dispatch-not-another-threads
     (testing "another thread's same-frame dispatch commits between replay's
               observation arming and its dispatch; the reported epoch is still
               the replay's own record"
       (rf/configure! {:epoch-history {:depth 10}})
       (rf/make-frame {:id interleave-frame-id})
       (rf/reg-event :review/add add-handler)
       (rf/dispatch-sync [:review/add 1] {:frame interleave-frame-id})
       (let [source (last (rf/epoch-history interleave-frame-id))
             res    (replay-with-foreign-dispatch-after-arming
                      interleave-frame-id (:epoch-id source) [:review/add 100])
             after  (rf/epoch-history interleave-frame-id)]
         (is (= [[:review/add 1] [:review/add 100] [:review/add 1]]
                (mapv :trigger-event after))
             "the other thread's dispatch committed INSIDE replay's armed window")
         (is (= (:epoch-id (last after)) (:epoch-id res))
             "the reported epoch is the replay's OWN new record")))))

;; A handler registered `:rf.trace/no-emit? true` commits no epoch, and epoch
;; capture never hears its `:rf.event/dispatched`. Replay runs against CURRENT
;; code, so replaying a handler re-registered quiet is ordinary use: with no
;; dispatch id of its own no commit is evidence of the replay, and the answer
;; is nil — not a commit some other dispatch made.

(defn- reg-quiet!
  "Re-register `event-id` with `handler` and its tracing opted out."
  [event-id handler]
  (rf/reg-event event-id {:rf.trace/no-emit? true} handler))

(deftest quiet-replay-reports-nil-not-its-queued-childs-epoch
  (testing "on one thread, the quiet replayed parent enqueues a traced child, the
            child commits, and nil rides back rather than the child's record"
    (rf/configure! {:epoch-history {:depth 10}})
    (rf/make-frame {:id evict-frame-id})
    (register-parent-and-child!)
    (rf/dispatch-sync [:review/parent] {:frame evict-frame-id})
    (let [source (last (rf/epoch-history evict-frame-id))
          _      (reg-quiet! :review/parent parent-handler)
          res    (rf/replay-epoch! evict-frame-id (:epoch-id source))]
      (is (= [:review/parent :review/child]
             (mapv :event-id (rf/epoch-history evict-frame-id)))
          "the replay ran: only its child committed")
      (is (= [true nil] ((juxt :ok? :epoch-id) res))))))

(defn- register-three-generations!
  "`:review/parent` enqueues `:review/child` from its SECOND run, and
  `:review/child` always enqueues `:review/grandchild`, so only a replay settles
  a two-level queued cascade."
  []
  (rf/reg-event :review/grandchild
    (fn [{:keys [db]} _] {:db (assoc db :grandchild true)}))
  (rf/reg-event :review/child
    (fn [{:keys [db]} _]
      {:db (assoc db :child true)
       :fx [[:dispatch [:review/grandchild]]]}))
  (rf/reg-event :review/parent parent-handler))

(deftest replay-over-two-queued-levels-reports-its-own-epoch-or-nil-when-quiet
  (testing "a traced replay over two queued levels reports its OWN new parent
            record. Re-registered quiet, the same replay reports nil: the quiet
            parent's emit and its child's enqueue are suppressed, so the traced
            child's enqueue of the grandchild is the first dispatch the arming
            thread sees, and adopting it would name the grandchild's record"
    (rf/configure! {:epoch-history {:depth 10}})
    (rf/make-frame {:id evict-frame-id})
    (register-three-generations!)
    (rf/dispatch-sync [:review/parent] {:frame evict-frame-id})
    (let [source-id (:epoch-id (last (rf/epoch-history evict-frame-id)))
          traced    (rf/replay-epoch! evict-frame-id source-id)
          _         (reg-quiet! :review/parent parent-handler)
          quiet     (rf/replay-epoch! evict-frame-id source-id)
          after     (rf/epoch-history evict-frame-id)]
      (is (= [:review/parent
              :review/parent :review/child :review/grandchild
              :review/child :review/grandchild]
             (mapv :event-id after))
          "both replays ran their two queued levels; the quiet parent added no record")
      (is (= [(:epoch-id (nth after 1)) nil]
             [(:epoch-id traced) (:epoch-id quiet)])))))
