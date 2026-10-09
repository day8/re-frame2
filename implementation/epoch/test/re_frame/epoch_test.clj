(ns re-frame.epoch-test
  "Tool-Pair §Time-travel — epoch recording, ring depth, listeners, restore,
  replace-frame-state! and replay refusal. Post-settle render / sub-run
  attribution lives in `re-frame.epoch-attribution-test`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [are deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            ;; flows, routing and machines are required for the late-bind hooks
            ;; they publish (reg-flow, reg-route, reg-machine), so the
            ;; fixture's ns-load registrar snapshot includes them.
            [re-frame.flows]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.registrar :as rf.registrar]
            ;; The façade, not `re-frame.schemas.malli`: only the façade
            ;; publishes the registrar + digest hooks as well as the validator.
            [re-frame.schemas :as rf.schemas]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace :as rf.trace]
            [re-frame.epoch]
            [re-frame.epoch.assembly :as rf.epoch.assembly]
            [re-frame.epoch.capture :as rf.epoch.capture]
            [re-frame.epoch.listeners :as rf.epoch.listeners]
            [re-frame.epoch.state :as rf.epoch.state]
            [re-frame.epoch.tool-pair :as rf.epoch.tool-pair]
            [re-frame.routing]
            [re-frame.machines]
            [re-frame.machines.timer :as rf.machines.timer]))

;; ---- fixtures --------------------------------------------------------------

;; `:trace-events-keep 5` (below the shipped default of 50) makes the elision
;; path reachable with a handful of dispatches.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn [] (rf/configure! {:epoch-history {:trace-events-keep 5}}))}))

;; ---- helpers ---------------------------------------------------------------

(defn- record-trace! []
  (let [recorded (atom [])]
    (rf/register-listener! :trace ::recorder (fn [ev] (swap! recorded conj ev)))
    recorded))

(defn- has-error-op? [events op]
  (some (fn [ev] (and (= :error (:op-type ev))
                      (= op     (:operation ev))))
        events))

(defn- epoch-source-files
  "Every epoch-artefact `.cljc` source file, discovered off the classpath so a
  new file is scanned without editing the test."
  []
  (let [pkg-dir (-> (io/resource "re_frame/epoch/capture.cljc")
                    io/file
                    (.getParentFile))
        facade  (io/file (io/resource "re_frame/epoch.cljc"))]
    (cons facade
          (filter #(str/ends-with? (.getName ^java.io.File %) ".cljc")
                  (.listFiles pkg-dir)))))

;; ---- recording -------------------------------------------------------------

(deftest record-shape-canonical
  (rf/make-frame {:id :test/main})
  (rf/reg-event :seed (fn [_ _] {:db {:n 0}}))
  (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
  (rf/dispatch-sync [:seed] {:frame :test/main})
  (rf/dispatch-sync [:inc]  {:frame :test/main})
  (let [r (last (rf/epoch-history :test/main))]
    (is (= {:frame :test/main :event-id :inc :trigger-event [:inc] :outcome :ok
            :db-before {:n 0} :db-after {:n 1}}
           (select-keys r [:frame :event-id :trigger-event :outcome :db-before :db-after])))
    (is (= [{:n 0} {:n 1}]
           (map #(get-in r [% :rf.db/app]) [:frame-state-before :frame-state-after]))
        ":db-before / :db-after are the app-db partitions of the frame-state slots")
    (is (every? vector? ((juxt :trace-events :sub-runs :renders :effects) r)))
    (is (some? (:dispatch-id r)))
    (is (= (:dispatch-id r)
           (some #(get-in % [:tags :rf.trace/dispatch-id]) (:trace-events r)))
        ":dispatch-id is the cascade's trace correlation id")))

(deftest committed-at-each-child-event-reads-its-own-token
  (testing ":committed-at is the committing token's :time-ms, never an
            assembly-time clock read; an :fx-dispatched child gets its own fresh
            token rather than inheriting the parent's (EP-0010 §Time)"
    (rf/make-frame {:id :test/main})
    (rf/reg-event :parent (fn [_ _] {:fx [[:dispatch [:child]]]}))
    (rf/reg-event :child  (fn [_ _] {}))
    (let [parent-time 1781078400123
          child-clock 5550000000000]
      (with-redefs [rf.interop/epoch-now-ms (constantly child-clock)]
        (rf/dispatch-sync [:parent] {:frame :test/main :rf.cofx {:rf/time-ms parent-time}}))
      (is (= [[:parent parent-time] [:child child-clock]]
             (mapv (juxt :event-id :committed-at) (rf/epoch-history :test/main)))))))

(deftest record-multi-event-cascade
  (testing "each dequeued event of one drain commits its own epoch with its own
            dispatch-id — a child's :rf.event/dispatched marker rides the
            child's epoch, not the parent's (Spec 009 §Dispatch correlation)"
    (rf/make-frame {:id :test/main})
    (rf/reg-event :outer
      (fn [_ _] {:db {:order [:outer]}
                 :fx [[:dispatch [:inner-1]] [:dispatch [:inner-2]]]}))
    (rf/reg-event :inner-1 (fn [{:keys [db]} _] {:db (update db :order conj :inner-1)}))
    (rf/reg-event :inner-2 (fn [{:keys [db]} _] {:db (update db :order conj :inner-2)}))
    (rf/dispatch-sync [:outer] {:frame :test/main})
    (let [history (rf/epoch-history :test/main)
          dids    (mapv #(into #{} (keep (fn [ev] (-> ev :tags :rf.trace/dispatch-id)))
                               (:trace-events %))
                        history)]
      (is (= [[:outer   {}                     {:order [:outer]}]
              [:inner-1 {:order [:outer]}          {:order [:outer :inner-1]}]
              [:inner-2 {:order [:outer :inner-1]} {:order [:outer :inner-1 :inner-2]}]]
             (mapv (juxt :event-id :db-before :db-after) history)))
      (is (every? #(= 1 (count %)) dids) "each epoch's traces carry one dispatch-id")
      (is (apply distinct? dids) "and no two epochs share one"))))

(deftest machine-raise-macrostep-is-one-epoch
  (testing "a machine's :raise chain runs inside one macrostep (Spec 005): one
            epoch, one dispatch-id, terminal state in the runtime-db partition"
    (rf/make-frame {:id :test/main})
    (rf/reg-machine :mac/chain
      {:initial :s0
       :actions {:a1 (fn [_] {:fx [[:raise [:e2]]]})
                 :a2 (fn [_] {:fx [[:raise [:e3]]]})}
       :states  {:s0 {:on {:e1 {:target :s1 :action :a1}}}
                 :s1 {:on {:e2 {:target :s2 :action :a2}}}
                 :s2 {:on {:e3 :s3}}
                 :s3 {}}})
    (rf/dispatch-sync [:mac/chain [:e1]] {:frame :test/main})
    (let [history (rf/epoch-history :test/main)
          r       (first history)]
      (is (= [:mac/chain] (mapv :event-id history)))
      (is (= :s3 (get-in r [:frame-state-after :rf.db/runtime
                            :rf.runtime/machines :snapshots :mac/chain :state])))
      (is (= 1 (count (into #{} (keep #(-> % :tags :rf.trace/dispatch-id))
                            (:trace-events r))))))))

;; ---- ring depth ------------------------------------------------------------

(deftest ring-depth-evicts-oldest
  (rf/configure! {:epoch-history {:depth 3}})
  (rf/make-frame {:id :test/main})
  (rf/reg-event :set (fn [_ [_ n]] {:db {:n n}}))
  (dotimes [n 6] (rf/dispatch-sync [:set n] {:frame :test/main}))
  (let [history (rf/epoch-history :test/main)]
    (is (= [{:n 3} {:n 4} {:n 5}] (mapv :db-after history)))
    ;; A bare `subvec` window keeps every evicted record reachable through its
    ;; backing vector — an unbounded leak behind a correctly-sized history.
    (is (not (instance? clojure.lang.APersistentVector$SubVector history)))))

;; `configure!` prunes every live ring at once: epoch-history, restore and
;; replay all read the same vector, and at depth 0 no later append arrives to
;; re-cap it.

(deftest lowering-depth-prunes-every-live-ring-immediately
  (rf/configure! {:epoch-history {:depth 10}})
  (rf/make-frame {:id :frame/a})
  (rf/make-frame {:id :frame/b})
  (rf/reg-event :set (fn [_ [_ n]] {:db {:n n}}))
  (doseq [n (range 5) frame [:frame/a :frame/b]]
    (rf/dispatch-sync [:set n] {:frame frame}))
  (rf/configure! {:epoch-history {:depth 2}})
  (is (= [[{:n 3} {:n 4}] [{:n 3} {:n 4}]]
         (mapv #(mapv :db-after (rf/epoch-history %)) [:frame/a :frame/b]))
      "every frame keeps its newest 2 records, oldest-first, with no further dispatch"))

(deftest depth-zero-drops-retained-history-and-refuses-time-travel
  (rf/make-frame {:id :test/main})
  (rf/reg-event :set (fn [_ [_ n]] {:db {:n n}}))
  (rf/dispatch-sync [:set 1] {:frame :test/main})
  (let [saved-id (:epoch-id (first (rf/epoch-history :test/main)))]
    (rf/dispatch-sync [:set 2] {:frame :test/main})
    (rf/configure! {:epoch-history {:depth 0}})
    (is (= [] (rf/epoch-history :test/main)))
    (is (false? (rf/restore-epoch! :test/main saved-id)))
    (is (= {:ok? false :reason :rf.epoch/replay-unknown-epoch}
           (select-keys (rf/replay-epoch! :test/main saved-id) [:ok? :reason])))
    (is (= {:n 2} (rf/app-db-value :test/main))
        "neither refusal touched the frame")))

(deftest re-enabling-depth-starts-from-an-empty-history
  (testing "depth 0 then a positive depth starts clean: no retired record comes
            back, and no back-fill anchor still names a retired epoch (the
            post-settle splice would drop a render aimed at one)"
    (rf/make-frame {:id :test/main})
    (rf/reg-event :seed (fn [_ _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (let [retired-id (:epoch-id (first (rf/epoch-history :test/main)))]
      (rf.epoch.state/record-mount-epoch! :test/main [:view/probe "t1"] retired-id)
      (rf/configure! {:epoch-history {:depth 0}})
      (rf/configure! {:epoch-history {:depth 5}})
      (is (nil? (rf.epoch.state/last-settled-epoch-id :test/main)))
      (is (nil? (rf.epoch.state/mount-epoch-for :test/main [:view/probe "t1"])))
      (rf/dispatch-sync [:inc] {:frame :test/main})
      (is (= [:inc] (mapv :event-id (rf/epoch-history :test/main)))))))

;; ---- listeners -------------------------------------------------------------

(deftest multi-listener-observed-frames-and-re-register-generation
  (testing "two listeners on one frame both fire; re-registering an id replaces
            its fn; a destroy then silences each live observer exactly once"
    (rf/make-frame {:id :test/main})
    (rf/reg-event :seed (fn [_ _] {:db {:n 0}}))
    (let [recorded (record-trace!)
          calls    (atom [])]
      (rf/register-listener! :epoch ::w1 (fn [_] (swap! calls conj :w1)))
      (rf/register-listener! :epoch ::w2 (fn [_] (swap! calls conj :w2)))
      (rf/dispatch-sync [:seed] {:frame :test/main})
      (rf/register-listener! :epoch ::w1 (fn [_] (swap! calls conj :w1-new)))
      (rf/dispatch-sync [:seed] {:frame :test/main})
      (is (= {:w1 1 :w2 2 :w1-new 1} (frequencies @calls)))
      (rf/destroy-frame! :test/main)
      (is (= {::w1 1 ::w2 1}
             (frequencies (keep #(when (= :rf.epoch.cb/silenced-on-frame-destroy (:operation %))
                                   (:cb-id (:tags %)))
                                @recorded)))))))

(deftest listener-exception-emits-trace
  (testing "a throwing listener emits one :rf.epoch.cb/listener-exception per
            invocation, linked to its record; sibling listeners keep firing"
    (rf/make-frame {:id :test/main})
    (rf/reg-event :seed (fn [_ _] {:db {:n 0}}))
    (let [recorded (record-trace!)
          survivor (atom 0)]
      (rf/register-listener! :epoch ::throwing (fn [_] (throw (ex-info "tool blew" {}))))
      (rf/register-listener! :epoch ::survivor (fn [_] (swap! survivor inc)))
      (rf/dispatch-sync [:seed] {:frame :test/main})
      (rf/dispatch-sync [:seed] {:frame :test/main})
      (is (= (mapv (fn [r] {:op-type :error :recovery :no-recovery :frame :test/main
                            :cb-id ::throwing :rf.epoch/id (:epoch-id r) :message "tool blew"})
                   (rf/epoch-history :test/main))
             (keep #(when (= :rf.epoch.cb/listener-exception (:operation %))
                      (merge (select-keys % [:op-type :recovery])
                             (select-keys (:tags %) [:frame :cb-id :rf.epoch/id :message])))
                   @recorded)))
      (is (= 2 @survivor)))))

;; ---- restore ----------------------------------------------------------------

(deftest restore-rewinds-whole-frame-state-revives-runtime-db
  (testing "restore-epoch! reinstalls both partitions of :frame-state-after
            (EP-0001 decision #9), reviving a machine snapshot"
    (rf/make-frame {:id :test/main})
    (rf/reg-machine :m/x {:initial :live :states {:live {} :gone {}}})
    (rf/reg-event :put-machine
      (fn [{rt :rf.db/runtime} _]
        {:db            {:phase :machine-alive}
         :rf.db/runtime (assoc-in rt [:rf.runtime/machines :snapshots :m/x]
                                  {:state :live :data {} :meta {}})}))
    (rf/reg-event :drop-machine
      (fn [{rt :rf.db/runtime} _]
        {:db            {:phase :machine-gone}
         :rf.db/runtime (update-in rt [:rf.runtime/machines :snapshots] dissoc :m/x)}))
    (rf/dispatch-sync [:put-machine] {:frame :test/main})
    (let [alive-epoch (:epoch-id (last (rf/epoch-history :test/main)))]
      (rf/dispatch-sync [:drop-machine] {:frame :test/main})
      (is (true? (rf/restore-epoch! :test/main alive-epoch)))
      (let [fs (rf/frame-state-value :test/main)]
        (is (= [{:phase :machine-alive} {:state :live :data {} :meta {}}]
               [(:rf.db/app fs)
                (get-in fs [:rf.db/runtime :rf.runtime/machines :snapshots :m/x])]))))))

;; perform-restore! runs the captured runtime-db through the late-bound
;; :resources/reconcile-on-restore hook before installing it (Spec 016 §Restore
;; and replay). The hook is stubbed here; its logic is the resources artefact's.

(deftest reconcile-runtime-db-on-restore-noop-without-hook
  (let [hook-key :resources/reconcile-on-restore
        original (rf.late-bind/get-fn hook-key)
        fs       {:rf.db/app {:n 1} :rf.db/runtime {:rf.runtime/resources {:entries {:k :v}}}}]
    (try
      (rf.late-bind/set-fn! hook-key nil)
      (is (= fs (rf.epoch.tool-pair/reconcile-runtime-db-on-restore :test/x fs 1 nil))
          "no resources artefact → the frame-state installs verbatim")
      (finally
        (rf.late-bind/set-fn! hook-key original)))))

(deftest perform-restore!-threads-restored-epoch-causal-time-as-restore-time-ms
  (testing "the reconcile hook receives the RECORDED runtime-db and, as
            :restore-time-ms, the restored epoch's :committed-at rather than the
            install clock (EP-0010 §Restore/Replay); its result is what installs"
    (rf/make-frame {:id :test/main})
    (rf/reg-event :put-resource
      (fn [{rt :rf.db/runtime} _]
        {:db            {:phase :mid-flight}
         :rf.db/runtime (assoc-in rt [:rf.runtime/resources :entries :k] {:status :loading})}))
    (rf/reg-event :clear
      (fn [{rt :rf.db/runtime} _]
        {:db {:phase :cleared} :rf.db/runtime (dissoc rt :rf.runtime/resources)}))
    (let [hook-key   :resources/reconcile-on-restore
          original   (rf.late-bind/get-fn hook-key)
          seen       (atom nil)
          token-time 1781078400777
          clock-time 9999999999999]
      (try
        (rf.late-bind/set-fn! hook-key
                              (fn [rdb frame-id opts]
                                (reset! seen [rdb frame-id
                                              (select-keys opts [:defer-traces? :restore-time-ms])])
                                (assoc rdb ::reconciled true)))
        (rf/dispatch-sync [:put-resource] {:frame :test/main :rf.cofx {:rf/time-ms token-time}})
        (let [mid (last (rf/epoch-history :test/main))]
          (rf/dispatch-sync [:clear] {:frame :test/main})
          (with-redefs [rf.interop/now-ms       (constantly clock-time)
                        rf.interop/epoch-now-ms (constantly clock-time)]
            (is (true? (rf/restore-epoch! :test/main (:epoch-id mid)))))
          (is (= [(get-in mid [:frame-state-after :rf.db/runtime])
                  :test/main
                  {:defer-traces? true :restore-time-ms token-time}]
                 @seen))
          (is (true? (get-in (rf/frame-state-value :test/main) [:rf.db/runtime ::reconciled]))
              "the hook's result is what installed"))
        (finally
          (rf.late-bind/set-fn! hook-key original))))))

(deftest perform-restore!-does-not-quiesce-on-failed-install
  (testing "a restore that writes nothing cancels none of the frame's host work"
    (rf/make-frame {:id :test/main})
    (rf/reg-event :step (fn [{:keys [db]} _] {:db (assoc db :n 1)}))
    (let [k      :machines/on-frame-restored!
          orig   (rf.late-bind/get-fn k)
          fired? (atom false)]
      (try
        (rf.late-bind/set-fn! k (fn [_frame-id] (reset! fired? true)))
        (rf/dispatch-sync [:step] {:frame :test/main})
        (let [record (last (rf/epoch-history :test/main))
              token  (rf.frame/frame-incarnation-token :test/main)]
          (rf/destroy-frame! :test/main)
          (is (= [false false]
                 [(rf.epoch.tool-pair/perform-restore! :test/main token record)
                  @fired?])))
        (finally
          (rf.late-bind/set-fn! k orig))))))

(deftest restore-releases-armed-machine-after-timer-end-to-end
  (testing "restore must not revive host work (EP-0011): a machine :after timer
            armed by an unwound epoch is released, through the real machines hook"
    (rf/make-frame {:id :test/main})
    (rf/reg-machine :rest/m
      {:initial :idle
       :data    {}
       :states  {:idle    {:on {:fetch :loading}}
                 :loading {:after {3600000 :timeout}}
                 :timeout {}}})
    (rf/dispatch-sync [:rest/m [:rf.machine/start]] {:frame :test/main})
    (let [idle-epoch (:epoch-id (last (rf/epoch-history :test/main)))]
      (rf/dispatch-sync [:rest/m [:fetch]] {:frame :test/main})
      (is (seq (get @rf.machines.timer/after-timers :test/main))
          "precondition: :loading armed its :after timer")
      (is (true? (rf/restore-epoch! :test/main idle-epoch)))
      (is (empty? (get @rf.machines.timer/after-timers :test/main))))))

(deftest restore-rewinds-route-slice-and-route-sub
  (testing "a sub held across a restore derefs to the rewound value — here the
            route sub over the runtime-db route slice — with no stale sub cache"
    (rf/make-frame {:id :test/main})
    (rf/reg-route :route/home    {} "/")
    (rf/reg-route :route/article {} "/articles/:id")
    (rf/reg-event :go
      {:rf/machine? true}
      (fn [{rt :rf.db/runtime} [_ route-id]]
        {:rf.db/runtime (assoc-in rt [:rf.runtime/routing :current]
                                  {:route-id route-id :params {}})}))
    (rf/dispatch-sync [:go :route/home] {:frame :test/main})
    (let [home-epoch (:epoch-id (last (rf/epoch-history :test/main)))
          _          (rf/dispatch-sync [:go :route/article] {:frame :test/main})
          route      (rf/subscribe [:rf.route/id] {:frame :test/main})]
      (is (= :route/article @route) "the held sub has cached the pre-restore route")
      (is (true? (rf/restore-epoch! :test/main home-epoch)))
      (is (= :route/home @route))
      (rf/unsubscribe :test/main [:rf.route/id]))))

(defn- app-db-after-install-and-reset-input
  "Run a flow over `{:w 3 :h 2}`, call `install!` (a whole-app-db install that
  rewinds to `{:w 2 :h 2 :area 4}`), then set `:w` back to its pre-install 3
  and return the frame's app-db. `:area` is 6 only when the flow recomputed
  from the installed db rather than skipping on the inputs it cached before
  the install."
  [install!]
  (rf/make-frame {:id :test/main})
  (rf/reg-event :seed  (fn [_ _] {:db {:w 2 :h 2}}))
  (rf/reg-event :set-w (fn [{:keys [db]} [_ w]] {:db (assoc db :w w)}))
  (rf/reg-flow :area {:frame :test/main :inputs [[:w] [:h]] :output-path [:area]} *)
  (rf/dispatch-sync [:seed] {:frame :test/main})
  (let [seeded (:epoch-id (last (rf/epoch-history :test/main)))]
    (rf/dispatch-sync [:set-w 3] {:frame :test/main})
    (is (= {:w 3 :h 2 :area 6} (rf/app-db-value :test/main)))
    (is (true? (install! seeded)))
    (is (= {:w 2 :h 2 :area 4} (rf/app-db-value :test/main)))
    (rf/dispatch-sync [:set-w 3] {:frame :test/main})
    (rf/app-db-value :test/main)))

(deftest restore-resets-the-flows-dirty-check-cache
  (is (= {:w 3 :h 2 :area 6}
         (app-db-after-install-and-reset-input
           #(rf/restore-epoch! :test/main %)))))

(deftest replace-frame-state-resets-the-flows-dirty-check-cache
  (is (= {:w 3 :h 2 :area 6}
         (app-db-after-install-and-reset-input
           (fn [_] (rf/replace-frame-state! :test/main {:rf.db/app {:w 2 :h 2 :area 4}}))))))

;; ---- restore failure modes (Tool-Pair §Time-travel) -------------------------

(deftest restore-failure-unknown-frame
  (let [recorded (record-trace!)]
    (is (false? (rf/restore-epoch! :no.such/frame :ignored)))
    (is (= {:kind :frame :frame :no.such/frame}
           (some #(when (= :rf.error/no-such-handler (:operation %))
                    (select-keys (:tags %) [:kind :frame]))
                 @recorded)))))

(deftest restore-failure-schema-mismatch
  (testing "a record that fails a schema tightened since it was recorded is
            refused; the trace names the failing path, the digest pinned on the
            record and the frame's live digest"
    (rf/make-frame {:id :test/main})
    (rf/reg-event :set (fn [_ [_ n]] {:db {:n n}}))
    (rf/dispatch-sync [:set "not-an-int"] {:frame :test/main})
    (let [target (last (rf/epoch-history :test/main))]
      (rf/dispatch-sync [:set 0] {:frame :test/main})
      (rf/reg-app-schema [:n] {:frame :test/main} [:int])
      (let [recorded (record-trace!)]
        (is (false? (rf/restore-epoch! :test/main (:epoch-id target))))
        (is (= {:n 0} (rf/app-db-value :test/main)) "app-db unchanged")
        (is (= {:failing-paths          [[:n]]
                :schema-digest-recorded (:schema-digest target)
                :schema-digest-current  (rf.schemas/app-schemas-digest {:frame :test/main})}
               (some #(when (= :rf.epoch/restore-schema-mismatch (:operation %))
                        (select-keys (:tags %) [:failing-paths :schema-digest-recorded
                                                :schema-digest-current]))
                     @recorded)))))))

;; Restore and replace validate app-db through the registered validator, as
;; every Spec 010 site does: a nil validator admits, a substituted one decides,
;; and one that throws refuses.

(defn- with-schema-fns
  "Run `f` with `fns` installed via `set-schema-fns!`. The validator is
  process-global and the reset fixture does not restore it."
  [fns f]
  (rf.schemas/set-schema-fns! fns)
  (try (f)
       (finally (rf.schemas/set-schema-fns! rf.schemas/default-schema-fns))))

(defn- replace-schema-mismatch [recorded]
  (some #(when (= :rf.epoch/replace-schema-mismatch (:operation %)) %) @recorded))

(deftest restore-obeys-a-nil-validator
  (rf/make-frame {:id :test/off})
  (rf/reg-app-schema [:n] {:frame :test/off} :int)
  (rf/reg-event :set (fn [_ [_ n]] {:db {:n n}}))
  (with-schema-fns {:validate nil}
    (fn []
      (rf/dispatch-sync [:set "not-an-int"] {:frame :test/off})
      (let [target (last (rf/epoch-history :test/off))]
        (rf/dispatch-sync [:set 0] {:frame :test/off})
        (is (true? (rf/restore-epoch! :test/off (:epoch-id target))))
        (is (= {:n "not-an-int"} (rf/app-db-value :test/off)))))))

(deftest replace-obeys-a-substituted-validators-verdict
  (testing "a substituted (non-Malli) validator's rejection refuses the replace
            and its acceptance admits it — Malli would throw on the foreign token"
    (with-schema-fns {:validate (fn [schema v]
                                  (if (= ::pos schema) (and (int? v) (pos? v)) true))}
      (fn []
        (rf/make-frame {:id :test/sub})
        (rf/reg-app-schema [:n] {:frame :test/sub} ::pos)
        (rf/reg-event :seed (fn [_ _] {:db {:n 1}}))
        (rf/dispatch-sync [:seed] {:frame :test/sub})
        (let [recorded (record-trace!)]
          (is (false? (rf/replace-frame-state! :test/sub {:rf.db/app {:n -5}})))
          (is (= [[:n]] (:failing-paths (:tags (replace-schema-mismatch recorded))))))
        (is (true? (rf/replace-frame-state! :test/sub {:rf.db/app {:n 7}}))
            "CONTROL: a value the validator accepts is admitted")))))

(deftest replace-refuses-when-the-validator-throws
  (testing "default Malli throws on the malformed `[:vector]`; the replace is
            refused, as the hot path refuses the write"
    (rf/make-frame {:id :test/malformed})
    (rf/reg-event :seed (fn [_ _] {:db {:n [1 2]}}))
    (rf/dispatch-sync [:seed] {:frame :test/malformed})
    (rf/reg-app-schema [:n] {:frame :test/malformed} [:vector])
    (let [recorded (record-trace!)]
      (is (false? (rf/replace-frame-state! :test/malformed {:rf.db/app {:n [9 9 9]}})))
      (is (= [[:n]] (:failing-paths (:tags (replace-schema-mismatch recorded))))))))

(deftest epoch-record-stamps-schema-digest
  (rf/make-frame {:id :test/digest})
  (rf/reg-app-schema [:n] {:frame :test/digest} [:int])
  (rf/reg-event :init (fn [_ _] {:db {:n 0}}))
  (rf/dispatch-sync [:init] {:frame :test/digest})
  (is (= (rf.schemas/app-schemas-digest {:frame :test/digest})
         (:schema-digest (last (rf/epoch-history :test/digest))))
      "the record pins the frame's live schema digest at record time"))

(deftest restore-failure-missing-handler-route
  (rf/make-frame {:id :test/main})
  (rf/reg-route :route/users {} "/users")
  (rf/reg-event :route-to
    (fn [{rt :rf.db/runtime} _]
      {:rf.db/runtime (assoc-in rt [:rf.runtime/routing :current] {:route-id :route/users})}))
  (rf/dispatch-sync [:route-to] {:frame :test/main})
  (let [target (last (rf/epoch-history :test/main))]
    (rf.registrar/unregister! :route :route/users)
    (let [recorded (record-trace!)]
      (is (false? (rf/restore-epoch! :test/main (:epoch-id target))))
      (is (= [{:kind :route :id :route/users}]
             (some #(when (= :rf.epoch/restore-missing-handler (:operation %))
                      (:missing (:tags %)))
                   @recorded))))))

(deftest restore-failure-missing-handler-machine
  (testing "a recorded machine snapshot is a missing handler once its machine is
            unregistered — or replaced by a same-id plain event, since machine
            resolution gates on :rf/machine? metadata"
    (rf/make-frame {:id :test/main})
    (rf/reg-machine :machine/tl {:initial :red :states {:red {:on {:tick :green}} :green {}}})
    (rf/dispatch-sync [:machine/tl [:tick]] {:frame :test/main})
    (let [target  (last (rf/epoch-history :test/main))
          restore (fn []
                    (let [recorded (record-trace!)]
                      [(rf/restore-epoch! :test/main (:epoch-id target))
                       (some #(when (= :rf.epoch/restore-missing-handler (:operation %))
                                (:missing (:tags %)))
                             @recorded)]))]
      (rf/reg-event :machine/tl (fn [{:keys [db]} _] {:db db}))
      (is (= [false [{:kind :machine :id :machine/tl}]] (restore)) "same-id plain event")
      (rf.registrar/unregister! :event :machine/tl)
      (is (= [false [{:kind :machine :id :machine/tl}]] (restore)) "unregistered"))))

(defn- restore-across-a-version-change
  "On a fresh frame, record a `:machine/tl` snapshot stamped `recorded` (nil =
  unstamped) under a definition stamped the same, move app-db on, hot-reload the
  definition stamped `current`, then restore the recorded epoch. Returns the
  restore result, whether the frame state was left unchanged, and the
  version-mismatch trace's tags (or nil)."
  [recorded current]
  (let [frame   (keyword "test" (str (gensym "version-")))
        machine (fn [v] (cond-> {:initial :red :states {:red {:on {:tick :green}} :green {}}}
                          (some? v) (assoc :meta {:rf/snapshot-version v})))
        snap    (cond-> {:state :red :data {}}
                  (some? recorded) (assoc :meta {:rf/snapshot-version recorded}))]
    (rf/make-frame {:id frame})
    (rf/reg-machine :machine/tl (machine recorded))
    (rf/reg-event :put-snap
      (fn [{rt :rf.db/runtime} _]
        {:rf.db/runtime (assoc-in rt [:rf.runtime/machines :snapshots :machine/tl] snap)}))
    (rf/reg-event :bump (fn [_ _] {:db {:bumped? true}}))
    (rf/dispatch-sync [:put-snap] {:frame frame})
    (let [target (:epoch-id (last (rf/epoch-history frame)))]
      (rf/dispatch-sync [:bump] {:frame frame})
      (rf/reg-machine :machine/tl (machine current))
      (let [traces (record-trace!)
            before (rf/frame-state-value frame)
            ok?    (rf/restore-epoch! frame target)]
        {:ok?        ok?
         :unchanged? (= before (rf/frame-state-value frame))
         :mismatch   (some #(when (= :rf.epoch/restore-version-mismatch (:operation %))
                              (select-keys (:tags %) [:machine-id :version-recorded
                                                      :version-current]))
                           @traces)}))))

(deftest restore-failure-version-mismatch
  (testing "the recorded snapshot version must equal the current definition's,
            absent included (the machines artefact's own rule): a stamp
            changed, added or removed since the recording is drift"
    (are [recorded current ok?]
         (= (if ok?
              {:ok? true :unchanged? false :mismatch nil}
              {:ok? false :unchanged? true
               :mismatch {:machine-id :machine/tl
                          :version-recorded recorded :version-current current}})
            (restore-across-a-version-change recorded current))
      1   2   false
      nil 1   false
      1   nil false
      nil nil true)))

;; ---- structured projections ------------------------------------------------

(deftest sub-runs-projection
  (testing ":sub-runs carries one row per :rf.sub/run in the cascade, attributed
            to the cascade's event"
    (rf/make-frame {:id :test/main})
    (rf/reg-sub :n   (fn [db _] (:n db)))
    (rf/reg-sub :n*2 {:inputs [[:n]]} (fn [[n] _] (* 2 (or n 0))))
    (rf/reg-event :read-sub (fn [_ _] (rf/subscribe-once [:n*2] {:frame :test/main}) {}))
    (rf/dispatch-sync [:read-sub] {:frame :test/main})
    (is (= [[:n true :read-sub] [:n*2 true :read-sub]]
           (mapv (juxt :sub-id :recomputed? :cause-event-id)
                 (:sub-runs (last (rf/epoch-history :test/main))))))))

(deftest sub-run-row-threads-cause-event-id
  (testing "the projector shared by the settle-time walk and the post-settle
            back-fill lifts :rf.sub/cause-event-id onto the row, and omits the
            slot (not nil) when the emit site omitted the tag"
    (let [row (fn [tags] (rf.epoch.capture/sub-run-row
                           {:operation :rf.sub/run
                            :tags      (assoc tags :rf.sub/id :counter/value)}))]
      (is (= :counter/inc (:cause-event-id (row {:rf.sub/cause-event-id :counter/inc}))))
      (is (not (contains? (row {}) :cause-event-id))))))

(deftest effects-projection-one-entry-per-fx
  (testing ":effects has one row per dispatched fx, in order, on the dispatching
            event's own record (a :dispatch child's record is separate); error
            rows name their error trace by :id"
    (rf/make-frame {:id :test/main})
    (rf/reg-fx :ok-fx       (fn [_ _] :ok))
    (rf/reg-fx :throwing-fx (fn [_ _] (throw (ex-info "boom" {}))))
    (rf/reg-fx :client-only {:platforms #{:client}} (fn [_ _] :nope))
    (rf/reg-event :noop (fn [_ _] {}))
    (rf/reg-event :run
      (fn [_ _] {:fx [[:ok-fx :a] [:throwing-fx :b] [:no/such-fx :c]
                      [:client-only :d] [:dispatch [:noop]]]}))
    (rf/dispatch-sync [:run] {:frame :test/main})
    (let [r     (first (rf/epoch-history :test/main))
          op-of (into {} (map (juxt :id :operation)) (:trace-events r))]
      (is (= [{:fx-id :ok-fx       :args :a      :outcome :ok}
              {:fx-id :throwing-fx :args :b      :outcome :error}
              {:fx-id :no/such-fx  :args :c      :outcome :error}
              {:fx-id :client-only :args :d      :outcome :skipped-on-platform}
              {:fx-id :dispatch    :args [:noop] :outcome :ok}]
             (mapv #(dissoc % :error-trace) (:effects r))))
      (is (= [:none :rf.error/fx-handler-exception :rf.error/no-such-fx :none :none]
             (mapv #(if (contains? % :error-trace) (op-of (:error-trace %)) :none)
                   (:effects r)))))))

;; ---- partial drain ---------------------------------------------------------

(deftest depth-exceeded-commits-halted-record
  (testing "a depth-exceeded drain keeps the events that ran as durable :ok
            epochs and commits one trailing :halted-depth marker carrying the
            last-settled state; listeners and the :rf.epoch/snapshotted /
            :rf.epoch/outcome traces see every record, and the marker is not a
            restore target"
    (rf/make-frame {:id :test/main :drain-depth 5})
    (rf/reg-event :loop
      (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0)) :fx [[:dispatch [:loop]]]}))
    (let [recorded (record-trace!)
          received (atom [])]
      (rf/register-listener! :epoch ::watcher #(swap! received conj %))
      (rf/dispatch-sync [:loop] {:frame :test/main})
      (let [history (rf/epoch-history :test/main)
            halted  (last history)
            last-ok (nth history 4)
            ids     (mapv :epoch-id history)
            emitted (fn [op] (keep #(when (= op (:operation %))
                                      ((juxt :rf.epoch/id :outcome) (:tags %)))
                                   @recorded))]
        (is (= [:ok :ok :ok :ok :ok :halted-depth] (mapv :outcome history)))
        (is (= {:n 5} (:db-after last-ok)) "the events that ran are not rolled back")
        (is (= {:event-id           :loop
                :trigger-event      [:loop]
                :db-before          {:n 5}
                :db-after           {:n 5}
                :frame-state-before (:frame-state-after last-ok)
                :frame-state-after  (:frame-state-after last-ok)
                :halt-reason        {:operation :rf.error/drain-depth-exceeded :depth 5}}
               (-> (select-keys halted [:event-id :trigger-event :db-before :db-after
                                        :frame-state-before :frame-state-after :halt-reason])
                   (update :halt-reason select-keys [:operation :depth]))))
        (is (= (mapv (juxt :epoch-id :outcome) history)
               (mapv (juxt :epoch-id :outcome) @received)
               (emitted :rf.epoch/snapshotted)))
        (is (= (map vector ids [:ok :ok :ok :ok :ok :blocked]) (emitted :rf.epoch/outcome)))
        (let [refusal (record-trace!)]
          (is (false? (rf/restore-epoch! :test/main (:epoch-id halted))))
          (is (has-error-op? @refusal :rf.epoch/restore-non-ok-record)))))))

;; Xray's Trace panel and Story chips render off this mapping.
(deftest outcome-enum-projection-pins-mapping
  (is (= [:ok :blocked :blocked :error]
         (map rf.epoch.assembly/outcome->consumer-facing
              [:ok :halted-depth :halted-destroy :halted-handler-exception]))))

(deftest committed-at-on-halted-destroy-is-destroying-token-time
  (testing "a handler that destroys its own frame commits a :halted-destroy
            record whose :committed-at is the destroying event's token time, not
            the host clock, and emits :rf.epoch/outcome :blocked. The ring goes
            with the frame, so the record is read from a listener."
    (rf/make-frame {:id :test/short-lived})
    (rf/reg-event :self-destruct (fn [_ _] (rf/destroy-frame! :test/short-lived) {}))
    (let [token-time 1781078400123
          clock-time 7777777777777
          halted     (atom [])
          recorded   (record-trace!)]
      (rf/register-listener! :epoch ::watch-committed-at
                             (fn [r] (when (= :halted-destroy (:outcome r)) (swap! halted conj r))))
      (with-redefs [rf.interop/now-ms (constantly clock-time)]
        (rf/dispatch-sync [:self-destruct] {:frame   :test/short-lived
                                            :rf.cofx {:rf/time-ms token-time}}))
      (is (= [token-time] (mapv :committed-at @halted)))
      (is (some #(and (= :rf.epoch/outcome (:operation %)) (= :blocked (-> % :tags :outcome)))
                @recorded)))))

;; ---- a throw rides :trace-events; the epoch still settles :ok -------------
;;
;; :halted-handler-exception is schema-reserved and never committed: handler
;; and flow throws are captured by the interceptor error seam, so the drain
;; does not halt.

(deftest handler-exception-settles-ok-never-halted-handler-exception
  (rf/make-frame {:id :test/main})
  (rf/reg-event :boom (fn [_ _] (throw (ex-info "handler blew" {}))))
  (rf/dispatch-sync [:boom] {:frame :test/main})
  (let [history (rf/epoch-history :test/main)]
    (is (= [[:boom :ok]] (mapv (juxt :event-id :outcome) history)))
    (is (has-error-op? (:trace-events (first history)) :rf.error/handler-exception))))

(deftest flow-throw-epoch-shape
  (testing "a flow whose :derive throws discards the event's pending :db (Spec 013
            §Failure semantics): the epoch settles :ok with :db-before =
            :db-after and the :rf.error/flow-eval-exception in :trace-events"
    (rf/make-frame {:id :test/main})
    (rf/reg-event :seed (fn [_ _] {:db {:n 0}}))
    (rf/reg-event :bump (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (rf/reg-flow :boom {:frame :test/main :inputs [[:n]] :output-path [:derived :doomed]}
                 (fn [_] (throw (ex-info "flow boom" {}))))
    (rf/dispatch-sync [:bump] {:frame :test/main})
    (let [r (last (rf/epoch-history :test/main))]
      (is (= {:event-id :bump :outcome :ok :db-before {:n 0} :db-after {:n 0}}
             (select-keys r [:event-id :outcome :db-before :db-after])))
      (is (has-error-op? (:trace-events r) :rf.error/flow-eval-exception)))))

;; ---- a rejected dispatch commits no epoch ----------------------------------
;;
;; A dispatch to an unregistered event never runs, but its no-such-handler
;; trace carries the cascade's dispatch-id and buffers into capture. The
;; harvest is scoped to the settling dispatch so settle! commits no fake :ok
;; epoch while a sibling's queued marker survives for its own settle.

(deftest no-handler-dispatch-commits-no-epoch
  (rf/make-frame {:id :test/main})
  (rf/reg-event :real (fn [_ _] {:db {:n 1}}))
  (rf/dispatch-sync [:real] {:frame :test/main})
  (let [history-before (rf/epoch-history :test/main)
        fired          (atom 0)]
    (rf/register-listener! :epoch ::probe (fn [_] (swap! fired inc)))
    (rf/dispatch-sync [:no/such-handler 42] {:frame :test/main})
    (is (= history-before (rf/epoch-history :test/main)))
    (is (zero? @fired) "no listener fired for the rejected dispatch"))
  (rf/dispatch-sync [:real] {:frame :test/main})
  (is (= 2 (count (rf/epoch-history :test/main)))
      "the suppression is scoped to the rejected dispatch"))

(deftest no-run-start-harvest-scopes-drop-to-settling-dispatch
  (let [frame   :test/scoped-harvest
        own-err {:op-type :error :operation :rf.error/no-such-handler
                 :tags {:rf.trace/dispatch-id :S :rf.trace/event-id :no/such}}
        child   {:op-type :rf.event :operation :rf.event/dispatched
                 :tags {:rf.trace/dispatch-id :C :rf.trace/event-id :child}}
        orphan  {:op-type :rf.frame :operation :rf.frame/created
                 :tags {:frame frame}}]
    (run! #(rf.epoch.state/buffer-event! frame %) [own-err child orphan])
    (is (= [] (rf.epoch.state/harvest-buffer-for-event! frame :S))
        "nothing ran, so nothing is returned to commit")
    (is (= [child] (rf.epoch.state/buffer-for frame))
        "the settling dispatch's own trace and the orphan are dropped; the
         unrelated child marker is kept")
    (rf.epoch.state/drop-frame-buffer! frame)))

;; ---- :trace-events elision -------------------------------------------------
;;
;; Records older than the newest :trace-events-keep drop their raw
;; :trace-events but keep the structured projections (Security.md §Epoch
;; privacy posture).

(deftest trace-events-keep-elides-older-records
  (rf/configure! {:epoch-history {:depth 10 :trace-events-keep 2}})
  (rf/make-frame {:id :test/main})
  (rf/reg-event :inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
  (dotimes [_ 5] (rf/dispatch-sync [:inc] {:frame :test/main}))
  (let [history (rf/epoch-history :test/main)]
    (is (= [false false false true true] (mapv #(contains? % :trace-events) history)))
    (is (every? #(every? (partial contains? %) [:sub-runs :renders :effects]) history))))

;; ---- replace-frame-state! app-db patches ---------------------------------
;;
;; Tool-Pair §Pair-tool writes: an app-db-only `replace-frame-state!` is the
;; pair tool's state-injection surface. The seven
;; `replace-frame-state-app-only-*` tests below are cited by name from
;; skills/re-frame2-pair/tests/runtime/app_db_reset_test.clj.

(defn- tags-of
  "The `:tags` of the first event in `events` whose `:operation` is `op`."
  [events op]
  (some #(when (= op (:operation %)) (:tags %)) events))

(defn- no-such-frame-errors
  "`[op-type {:kind … :frame …}]` for every `:rf.error/no-such-handler` trace in
  `events` — the typed failure every lost-incarnation path resolves to."
  [events]
  (->> events
       (filter #(= :rf.error/no-such-handler (:operation %)))
       (mapv (fn [ev] [(:op-type ev) (select-keys (:tags ev) [:kind :frame])]))))

(deftest replace-frame-state-app-only-replaces-container
  (testing "an app-db-only patch replaces the app-db and preserves the omitted
            runtime-db partition"
    (rf/make-frame {:id :test/main})
    (rf/reg-event :seed
      (fn [{rt :rf.db/runtime} _]
        {:db            {:n 0}
         :rf.db/runtime (assoc (or rt {}) :rf.runtime/routing {:current {:route-id :kept}})}))
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (is (true? (rf/replace-frame-state! :test/main {:rf.db/app {:n 99 :injected? true}})))
    (is (= {:n 99 :injected? true} (rf/app-db-value :test/main)))
    (is (= {:current {:route-id :kept}}
           (get-in (rf/frame-state-value :test/main) [:rf.db/runtime :rf.runtime/routing]))
        "the omitted runtime-db partition is preserved")))

(deftest replace-frame-state-app-only-records-undo-epoch
  (testing "an app-db-only patch appends one synthetic :rf.epoch/db-replaced
            record that anchors last-settled, and restoring an earlier epoch
            rewinds past the injection"
    (rf/make-frame {:id :test/main})
    (rf/reg-event :seed (fn [_ _] {:db {:n 7}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (rf/replace-frame-state! :test/main {:rf.db/app {:n 999}})
    (let [history (rf/epoch-history :test/main)
          fresh   (peek history)]
      (is (= [:seed :rf.epoch/db-replaced] (mapv :event-id history)))
      (is (= [[:rf.epoch/db-replaced] {:n 7} {:n 999}]
             ((juxt :trigger-event :db-before :db-after) fresh)))
      (is (= (:epoch-id fresh) (rf.epoch.state/last-settled-epoch-id :test/main)))
      (is (true? (rf/restore-epoch! :test/main (:epoch-id (first history)))))
      (is (= {:n 7} (rf/app-db-value :test/main))))))

(deftest replace-frame-state-app-only-emits-trace
  (testing "a successful app-db-only patch emits one :rf.epoch/db-replaced
            tagged with the frame and the synthetic record's :rf.epoch/id"
    (rf/make-frame {:id :test/main})
    (let [recorded (record-trace!)]
      (rf/replace-frame-state! :test/main {:rf.db/app {:n 1}})
      (is (= [{:op-type :rf.epoch
               :tags    {:frame       :test/main
                         :rf.epoch/id (:epoch-id (peek (rf/epoch-history :test/main)))}}]
             (->> @recorded
                  (filter #(= :rf.epoch/db-replaced (:operation %)))
                  (mapv #(select-keys % [:op-type :tags]))))))))

(deftest replace-frame-state-app-only-fires-listeners
  (testing "an app-db-only patch fans the synthetic record out to epoch listeners"
    (rf/make-frame {:id :test/main})
    (rf/reg-event :seed (fn [_ _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (let [received (atom [])]
      (rf/register-listener! :epoch ::reset-listener #(swap! received conj %))
      (rf/replace-frame-state! :test/main {:rf.db/app {:n 42}})
      (is (= [[:test/main :rf.epoch/db-replaced {:n 0} {:n 42}]]
             (mapv (juxt :frame :event-id :db-before :db-after) @received))))))

(deftest replace-frame-state-app-only-failure-unknown-frame
  (testing "an app-db-only patch on an unknown frame returns false and emits
            :rf.error/no-such-handler (kind :frame)"
    (let [recorded (record-trace!)]
      (is (false? (rf/replace-frame-state! :no.such/frame {:rf.db/app {:any 'value}})))
      (is (= [[:error {:kind :frame :frame :no.such/frame}]]
             (no-such-frame-errors @recorded))))))

(deftest replace-frame-state-app-only-failure-during-drain
  (testing "an app-db-only patch from inside a drain returns false, emits
            :rf.epoch/replace-during-drain, and records and fans out no
            synthetic epoch: the only new record is the refusing cascade's own"
    (rf/make-frame {:id :test/main})
    (rf/reg-event :seed (fn [_ _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (let [recorded (record-trace!)
          seen     (atom [])
          attempt  (atom nil)]
      (rf/register-listener! :epoch ::watcher #(swap! seen conj (:event-id %)))
      (rf/reg-event :try-reset
        (fn [{:keys [db]} _]
          (reset! attempt (rf/replace-frame-state! :test/main {:rf.db/app {:n 999}}))
          {:db db}))
      (rf/dispatch-sync [:try-reset] {:frame :test/main})
      (is (false? @attempt))
      (is (= :test/main (:frame (tags-of @recorded :rf.epoch/replace-during-drain))))
      (is (= [:seed :try-reset] (mapv :event-id (rf/epoch-history :test/main))))
      (is (= [:try-reset] @seen)))))

(deftest replace-frame-state-app-only-failure-schema-mismatch
  (testing "an app-db-only patch failing the frame's app-schemas returns
            false, leaves app-db unchanged, and emits
            :rf.epoch/replace-schema-mismatch naming the failing path"
    (rf/make-frame {:id :test/main})
    (rf/reg-app-schema [:n] {:frame :test/main} [:int])
    (rf/reg-event :seed (fn [_ _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (let [recorded (record-trace!)]
      (is (false? (rf/replace-frame-state! :test/main {:rf.db/app {:n "not-an-int"}})))
      (is (= {:n 0} (rf/app-db-value :test/main)))
      (is (= [:test/main [[:n]]]
             ((juxt :frame :failing-paths)
              (tags-of @recorded :rf.epoch/replace-schema-mismatch)))))))

;; Depth 0 retains no history, so the synthetic undo anchor could never land
;; and a success would leave a write nothing can rewind. The contract is a loud
;; refusal (Tool-Pair §Pair-tool writes).

(deftest replace-frame-state-app-only-depth-0-rejects-no-false-undo
  (testing "under depth 0 an app-db-only patch is refused with
            :rf.epoch/replace-history-disabled and app-db is unchanged"
    (rf/configure! {:epoch-history {:depth 0}})
    (rf/make-frame {:id :test/main})
    (rf/reg-event :seed (fn [_ _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (let [recorded (record-trace!)]
      (is (false? (rf/replace-frame-state! :test/main {:rf.db/app {:n 999}})))
      (is (= {:n 0} (rf/app-db-value :test/main)))
      (is (has-error-op? @recorded :rf.epoch/replace-history-disabled)))))

;; ---- a halt record is a marker, never the settled state -------------------
;;
;; `last-settled-epoch` is what `restore-epoch!` rewinds to. A `:halted-depth`
;; record describes an event that never ran; anchoring on it would make a
;; restore to "now" refuse with `:rf.epoch/restore-non-ok-record`.

(deftest drain-depth-halt-leaves-the-restore-anchor-on-the-last-ok-epoch
  (testing "a committed :halted-depth record does not take the last-settled
            anchor, so restoring the anchored epoch still succeeds"
    (rf/make-frame {:id :test/halt-anchor :drain-depth 4})
    (rf/reg-event :halt-loop
      (fn [{:keys [db]} _]
        {:db (update db :n (fnil inc 0))
         :fx [[:dispatch [:halt-loop]]]}))
    (rf/dispatch-sync [:halt-loop] {:frame :test/halt-anchor})
    (is (= :halted-depth (:outcome (peek (rf/epoch-history :test/halt-anchor)))))
    (is (true? (rf/restore-epoch! :test/halt-anchor
                                  (rf.epoch.state/last-settled-epoch-id :test/halt-anchor))))))

;; ---- replace-frame-state! (runtime-db and both partitions) ----------------

(deftest replace-frame-state!-records-undo-epoch-and-restore-rewinds-past
  (testing "a both-partition patch installs both partitions, records one
            synthetic epoch whose :frame-state-after is the installed state,
            and restoring an earlier epoch rewinds both partitions past it"
    (rf/make-frame {:id :test/main})
    (rf/reg-event :seed (fn [_ _] {:db {:n 7}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (let [injected {:rf.db/app     {:a :injected}
                    :rf.db/runtime {:rf.runtime/routing {:r :injected}}}]
      (is (true? (rf/replace-frame-state! :test/main injected)))
      (is (= injected (rf/frame-state-value :test/main)))
      (let [history (rf/epoch-history :test/main)]
        (is (= [:seed :rf.epoch/db-replaced] (mapv :event-id history)))
        (is (= injected (:frame-state-after (peek history))))
        (is (true? (rf/restore-epoch! :test/main (:epoch-id (first history))))))
      (is (= {:n 7} (rf/app-db-value :test/main)))
      (is (nil? (get-in (rf/frame-state-value :test/main) [:rf.db/runtime :rf.runtime/routing]))
          "the runtime-db partition rewound past the injection too"))))

(deftest replace-frame-state!-raises-when-epoch-artefact-missing
  (testing "with the :epoch/replace-frame-state! hook absent, rf/replace-frame-state!
            raises :rf.error/epoch-artefact-missing rather than degrading silently"
    (let [original (rf.late-bind/get-fn :epoch/replace-frame-state!)]
      (try
        (rf.late-bind/set-fn! :epoch/replace-frame-state! nil)
        (is (= :rf.error/epoch-artefact-missing
               (try (rf/replace-frame-state! :any/frame {:rf.db/app {}})
                    nil
                    (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e))))))
        (finally
          (rf.late-bind/set-fn! :epoch/replace-frame-state! original))))))

(deftest replace-frame-state!-failure-runtime-schema-mismatch
  (testing "a runtime-db partition failing the framework-owned runtime-db
            validator rejects the whole patch: false, frame-state unchanged,
            and :rf.epoch/replace-schema-mismatch names the failing path"
    (rf/make-frame {:id :test/main})
    (rf/reg-machine :rf.szbzei/gate
      {:initial :idle
       :data    {:n 1}
       :schemas {:data [:map [:n [:int {:min 0}]]]}
       :states  {:idle {}}})
    (let [before   (rf/frame-state-value :test/main)
          recorded (record-trace!)]
      (is (false? (rf/replace-frame-state!
                    :test/main
                    {:rf.db/app     {:fresh :app}
                     :rf.db/runtime {:rf.runtime/machines
                                     {:snapshots {:rf.szbzei/gate {:state :idle :data {:n -1}}}}}})))
      (is (= before (rf/frame-state-value :test/main)))
      (is (= [:test/main [[:rf.runtime/machines :snapshots]]]
             ((juxt :frame :failing-paths)
              (tags-of @recorded :rf.epoch/replace-schema-mismatch)))))))

;; ---- capture-event! skip-ops ------------------------------------------------
;;
;; Every `:rf.epoch/*` op carries a `:frame` tag. Unskipped, an op fired outside
;; a cascade would accrete into the capture buffer and leak into the next
;; record; the two in-drain refusals carry the refused cascade's dispatch-id, so
;; only `skip-ops` keeps them out of that cascade's own record.

(deftest in-drain-epoch-refusals-stay-out-of-the-settling-record
  (testing "restore-epoch! and replace-frame-state! refused from inside a
            handler emit their -during-drain refusals inside that cascade, yet
            the record it settles carries no :rf.epoch/* op"
    (rf/make-frame {:id :test/main})
    (rf/reg-event :seed (fn [_ _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (let [seed-eid (:epoch-id (peek (rf/epoch-history :test/main)))
          recorded (record-trace!)
          attempts (atom nil)
          refusal? #{:rf.epoch/restore-during-drain :rf.epoch/replace-during-drain}]
      (rf/reg-event :try-both
        (fn [{:keys [db]} _]
          (reset! attempts [(rf/restore-epoch! :test/main seed-eid)
                            (rf/replace-frame-state! :test/main {:rf.db/app {:n 999}})])
          {:db db}))
      (rf/dispatch-sync [:try-both] {:frame :test/main})
      (let [rec (peek (rf/epoch-history :test/main))]
        (is (= [false false] @attempts))
        (is (= #{[:rf.epoch/restore-during-drain (:dispatch-id rec)]
                 [:rf.epoch/replace-during-drain (:dispatch-id rec)]}
               (into #{}
                     (comp (filter #(refusal? (:operation %)))
                           (map (juxt :operation #(get-in % [:tags :rf.trace/dispatch-id]))))
                     @recorded))
            "both refusals carry the settling cascade's dispatch-id")
        (is (not-any? #(= "rf.epoch" (namespace (:operation %))) (:trace-events rec)))))))

(deftest skip-ops-catalogue-pins-every-rf-epoch-op
  (testing "skip-ops is exactly the set of :rf.epoch/*, :rf.epoch.cb/* and
            :rf.warning/* operations the epoch sources emit, scanned from the
            emit sites, so an emit forgotten in skip-ops, or a stale entry,
            fails here"
    (let [src     (apply str (map slurp (epoch-source-files)))
          emitted (into #{}
                        (comp (mapcat #(re-seq % src))
                              (map #(keyword (subs (second %) 1))))
                        [#"emit!\s+:[A-Za-z.]+\s+(:rf\.(?:epoch(?:\.cb)?|warning)/[a-z][a-z0-9-]*)"
                         #":op\s+(:rf\.(?:epoch(?:\.cb)?|warning)/[a-z][a-z0-9-]*)"
                         #"emit-error!\s+(:rf\.(?:epoch(?:\.cb)?|warning)/[a-z][a-z0-9-]*)"])]
      (is (= emitted @#'rf.epoch.capture/skip-ops)))))

;; ---- restore trace tags ------------------------------------------------------
;;
;; Spec 009 and Spec-Schemas reserve `:rf.epoch/id` for the epoch-id slot on
;; every `:rf.epoch/*` trace tag; an unqualified `:epoch-id` would stop a
;; consumer correlating a restore trace with its record.

(deftest restore-trace-tags-use-namespaced-epoch-id
  (testing "restore success and failure traces carry exactly their Spec-Schemas
            tag keys, with the epoch under :rf.epoch/id"
    (rf/make-frame {:id :test/main})
    (rf/reg-event :seed (fn [_ _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (rf/reg-event :try-restore
      (fn [{:keys [db]} [_ eid]] (rf/restore-epoch! :test/main eid) {:db db}))
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})
    (let [eid      (:epoch-id (first (rf/epoch-history :test/main)))
          recorded (record-trace!)]
      (rf/restore-epoch! :test/main eid)
      (rf/restore-epoch! :test/main :no-such-epoch)
      (rf/dispatch-sync [:try-restore eid] {:frame :test/main})
      (is (= {:frame :test/main :rf.epoch/id eid}
             (tags-of @recorded :rf.epoch/restored)))
      (is (= {:category     :rf.epoch/restore-unknown-epoch
              :frame        :test/main
              :rf.epoch/id  :no-such-epoch
              :history-size 2}
             (tags-of @recorded :rf.epoch/restore-unknown-epoch)))
      ;; In-drain, the envelope adds :rf.trace/dispatch-id for correlation.
      (is (= {:category    :rf.epoch/restore-during-drain
              :frame       :test/main
              :rf.epoch/id eid}
             (dissoc (tags-of @recorded :rf.epoch/restore-during-drain)
                     :rf.trace/dispatch-id))))))

;; ---- destroyed-frame contract ----------------------------------------------
;;
;; Tool-Pair §Surface behaviour against destroyed frames: read-shaped surfaces
;; return empty/nil, mutate-shaped ones fail with :rf.error/no-such-handler.

(deftest destroyed-frame-app-db-value-returns-nil
  (testing "(rf/app-db-value frame-id) returns nil for a destroyed frame
            and for a never-registered frame"
    (rf/make-frame {:id :test/short-lived})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed] {:frame :test/short-lived})
    (is (some? (rf/app-db-value :test/short-lived))
        "before destroy, app-db-value returns the live app-db")

    (rf/destroy-frame! :test/short-lived)
    (is (nil? (rf/app-db-value :test/short-lived))
        "after destroy, app-db-value returns nil")
    (is (nil? (rf/app-db-value :no.such/frame))
        "for a never-registered frame, app-db-value returns nil")))

(deftest destroyed-frame-silences-epoch-cb-listener
  (testing "destroying a frame an epoch listener observed emits one
            :rf.epoch.cb/silenced-on-frame-destroy naming the frame and the
            callback; the registration survives, so a recreated frame re-arms
            it and a second destroy silences again"
    (rf/make-frame {:id :test/short-lived})
    (rf/reg-event :seed (fn [_ _] {:db {:n 0}}))
    (let [recorded (record-trace!)
          silenced #(->> @recorded
                         (filter (fn [ev] (= :rf.epoch.cb/silenced-on-frame-destroy (:operation ev))))
                         (mapv (fn [ev] [(:op-type ev) (select-keys (:tags ev) [:frame :cb-id])])))]
      (rf/register-listener! :epoch ::watcher (fn [_] nil))
      (rf/dispatch-sync [:seed] {:frame :test/short-lived})
      (rf/destroy-frame! :test/short-lived)
      (is (= [[:rf.epoch.cb {:frame :test/short-lived :cb-id ::watcher}]] (silenced)))
      (rf/make-frame {:id :test/short-lived})
      (rf/dispatch-sync [:seed] {:frame :test/short-lived})
      (rf/destroy-frame! :test/short-lived)
      (is (= 2 (count (silenced)))))))

;; `destroy-frame!` snapshots terminal evidence before dissoc
;; (`:epoch/snapshot-frame-destroyed`) and publishes it after
;; (`:epoch/on-frame-destroyed`). A throwing snapshot reaches the publish half as
;; a nil bundle, which must still clean the destroyed incarnation's stores (or a
;; same-id successor inherits them) and must fabricate no evidence.

(deftest destroy-cleans-exact-owner-stores-when-snapshot-evidence-is-nil
  (testing "when the pre-dissoc snapshot hook throws, destroy still drops each
            of the five exact-owner stores, and the nil bundle publishes no
            :halted-destroy record, no silencing and no silence mark"
    (let [id            :test/nil-evidence-cleanup
          render-key    ::nil-evidence-view
          records       (atom [])
          traces        (record-trace!)
          original-snap (rf.late-bind/get-fn :epoch/snapshot-frame-destroyed)
          stores        (fn []
                          {:observers    (seq (rf.epoch.state/cbs-observing-frame id))
                           :history      (seq (rf.epoch.state/history-for id))
                           :buffer       (seq (rf.epoch.state/buffer-for id))
                           :last-settled (rf.epoch.state/last-settled-epoch-id id)
                           :mount        (rf.epoch.state/mount-epoch-for id render-key)
                           :render-deps  (rf.epoch.state/render-deps-for id render-key)})]
      (rf/make-frame {:id id})
      (rf/reg-event :seed (fn [_ _] {:db {:n 0}}))
      ;; An observing listener gives a fabricating publish someone to silence.
      (rf/register-listener! :epoch ::nil-evidence-cb #(swap! records conj %))
      (rf/dispatch-sync [:seed] {:frame id})
      ;; The settle seeded observers, history and last-settled; a synchronous
      ;; JVM cascade leaves the buffer empty and records no mount, so seed those.
      (rf.epoch.state/buffer-event! id {:operation :rf.event/run-start
                                        :tags      {:rf.trace/dispatch-id ::seed-dispatch}})
      (rf.epoch.state/record-mount-epoch! id render-key (rf.epoch.state/last-settled-epoch-id id))
      (rf.epoch.state/record-render-deps! id render-key ::a-sub)
      (is (every? some? (vals (stores))) "precondition: every store holds A's state")
      (try
        (rf.late-bind/set-fn! :epoch/snapshot-frame-destroyed
          (fn [& args]
            (if (= id (first args))
              (throw (ex-info "snapshot blew" {}))
              (when original-snap (apply original-snap args)))))
        (rf/destroy-frame! id)
        (finally
          (rf.late-bind/set-fn! :epoch/snapshot-frame-destroyed original-snap)))
      (is (= {} (into {} (filter val) (stores))) "every exact-owner store dropped")
      (is (= [[] [] 0]
             [(filterv #(= :halted-destroy (:outcome %)) @records)
              (filterv #(= :rf.epoch.cb/silenced-on-frame-destroy (:operation %)) @traces)
              (reduce + 0 (map count (vals (rf.epoch.state/terminal-silence-marks-snapshot))))])
          "no fabricated record, silence or silence mark"))))

;; ---- live :halted-destroy partial record -----------------------------------
;;
;; Spec-Schemas §:rf/epoch-record §Outcomes: a :halted-destroy record carries
;; the destroying event's pre-run snapshot as :db-before and the destroy-time
;; state as :db-after, and reaches listeners only (a destroyed frame's history
;; reads empty).

(deftest live-halted-destroy-fires-partial-record-with-real-snapshots
  (testing "a child event that writes and then destroys its frame from an fx
            delivers exactly one :halted-destroy record to listeners: its event
            id, the halt reason, the parent's committed write as :db-before and
            its own write as :db-after; nothing reaches the ring"
    (rf/make-frame {:id :test/main})
    (let [records (atom [])]
      (rf/register-listener! :epoch ::watch #(swap! records conj %))
      (rf/reg-fx :destroy-main (fn [_ _] (rf.frame/destroy-frame! :test/main)))
      (rf/reg-event :child-destroy
        (fn [{:keys [db]} _] {:db (assoc db :phase :child-wrote) :fx [[:destroy-main]]}))
      (rf/reg-event :parent-write-then-spawn
        (fn [_ _] {:db {:phase :parent-done :marker 42} :fx [[:dispatch [:child-destroy]]]}))
      (rf/dispatch-sync [:parent-write-then-spawn] {:frame :test/main})
      (is (= [{:event-id    :child-destroy
               :halt-reason {:operation :rf.frame/destroyed-mid-drain}
               :db-before   {:phase :parent-done :marker 42}
               :db-after    {:phase :child-wrote :marker 42}}]
             (->> @records
                  (filter #(= :halted-destroy (:outcome %)))
                  (mapv #(select-keys % [:event-id :halt-reason :db-before :db-after])))))
      (is (= [] (rf/epoch-history :test/main))))))

;; ---- configure! validates at the boundary -------------------------------

(deftest configure-drops-an-invalid-value-and-keeps-the-prior-one
  (testing "configure! silently drops a negative, non-numeric or nil :depth /
            :trace-events-keep, keeping the prior value, and still applies a
            valid key passed beside an invalid one"
    (let [epoch-config #(select-keys (:epoch-history (rf/current-config))
                                     [:depth :trace-events-keep])]
      (rf/configure! {:epoch-history {:depth 7 :trace-events-keep 3}})
      (rf/configure! {:epoch-history {:depth -1 :trace-events-keep "no"}})
      (is (= {:depth 7 :trace-events-keep 3} (epoch-config)))
      (rf/configure! {:epoch-history {:depth 11 :trace-events-keep nil}})
      (is (= {:depth 11 :trace-events-keep 3} (epoch-config))))))

(deftest depth-zero-still-fires-listeners
  (testing "depth 0 keeps the ring empty but still fans each record out to
            listeners (configure! / register-epoch-listener! docstrings)"
    (rf/configure! {:epoch-history {:depth 0}})
    (rf/make-frame {:id :test/main})
    (rf/reg-event :seed (fn [_ _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (let [seen (atom [])]
      (rf/register-listener! :epoch ::watcher #(swap! seen conj %))
      (rf/dispatch-sync [:seed] {:frame :test/main})
      (rf/dispatch-sync [:inc]  {:frame :test/main})
      (is (= [] (rf/epoch-history :test/main)))
      (is (= [[:seed {:n 0}] [:inc {:n 1}]] (mapv (juxt :event-id :db-after) @seen))))))

(deftest shipped-trace-events-keep-default-is-50
  (testing "the shipped :trace-events-keep default is 50 (this suite's fixture
            overrides it, so reset to the shipped config first)"
    (rf.epoch.state/reset-config!)
    (is (= 50 (:trace-events-keep (:epoch-history (rf/current-config)))))))

;; ============================================================================
;;  Exact-incarnation restore
;; ============================================================================
;;
;; `restore-epoch!` validates against a live frame, then writes it. The whole
;; restore is one transaction keyed on the EXACT incarnation token the
;; preconditions resolved: a frame destroyed, or replaced by a same-id
;; successor B, at any seam must leave B untouched and resolve to the typed
;; :rf.error/no-such-handler (Tool-Pair §Surface behaviour against destroyed
;; frames). The churn is interposed deterministically at each seam.

(deftest restore-fenced-to-exact-incarnation-leaves-same-id-successor-untouched
  (testing "preconditions resolved against incarnation A, then A destroyed and
            a same-id successor B seated before the write: the restore is
            refused with the typed :rf.error/no-such-handler, emits no success
            trace, and leaves B unchanged"
    (rf/make-frame {:id :test/succ})
    (rf/reg-event :set-owner (fn [_ [_ owner n]] {:db {:owner owner :n n}}))
    (rf/dispatch-sync [:set-owner :A 1] {:frame :test/succ})
    (rf/dispatch-sync [:set-owner :A 2] {:frame :test/succ})
    (let [{:keys [outcome epoch incarnation-token]}
          (rf.epoch.tool-pair/check-restore-preconditions!
            :test/succ (:epoch-id (first (rf/epoch-history :test/succ))))]
      (is (= :ok outcome))
      (rf/destroy-frame! :test/succ)
      (rf/make-frame {:id :test/succ})
      (rf/dispatch-sync [:set-owner :B 99] {:frame :test/succ})
      (let [recorded (record-trace!)]
        (is (false? (rf.epoch.tool-pair/perform-restore! :test/succ incarnation-token epoch)))
        (is (= {:owner :B :n 99} (rf/app-db-value :test/succ)))
        (is (= [[:error {:kind :frame :frame :test/succ}]] (no-such-frame-errors @recorded)))
        (is (not-any? #(= :rf.epoch/restored (:operation %)) @recorded))))))

(deftest restore-preconditions-refuse-successor-seated-during-history-sampling
  (testing "seam 1 — a same-id successor B seated while the preconditions
            sample A's history yields the typed no-such-handler refusal, never
            an :ok ticket pairing A's epoch with a stale incarnation"
    (rf/make-frame {:id :test/seam1})
    (rf/reg-event :set-owner (fn [_ [_ o n]] {:db {:owner o :n n}}))
    (rf/dispatch-sync [:set-owner :A 1] {:frame :test/seam1})
    (rf/dispatch-sync [:set-owner :A 2] {:frame :test/seam1})
    (let [a-target-id (:epoch-id (first (rf/epoch-history :test/seam1)))
          real-hist   rf.epoch.state/history-for
          churned?    (atom false)
          result      (with-redefs [rf.epoch.state/history-for
                                    (fn [fid]
                                      (if (and (= fid :test/seam1) (not @churned?))
                                        ;; Capture A's history, seat B, and hand
                                        ;; back A's retained history.
                                        (let [a-hist (real-hist fid)]
                                          (reset! churned? true)
                                          (rf/destroy-frame! :test/seam1)
                                          (rf/make-frame {:id :test/seam1})
                                          (rf/dispatch-sync [:set-owner :B 99] {:frame :test/seam1})
                                          a-hist)
                                        (real-hist fid)))]
                        (rf.epoch.tool-pair/check-restore-preconditions! :test/seam1 a-target-id))]
      (is (= {:outcome :fail
              :op      :rf.error/no-such-handler
              :tags    {:kind :frame :frame :test/seam1}}
             result))
      (is (= {:owner :B :n 99} (rf/app-db-value :test/seam1))))))

(deftest restore-tail-anchoring-fenced-to-exact-incarnation
  (testing "seam 3 — a :rf.epoch/restored trace listener that destroys A and
            seats a same-id successor B after the install: restore still
            returns true (it committed on A), but the post-write tail does not
            stamp B's last-settled anchor with A's restored epoch"
    (rf/make-frame {:id :test/seam3})
    (rf/reg-event :set-owner3 (fn [_ [_ o n]] {:db {:owner o :n n}}))
    (rf/dispatch-sync [:set-owner3 :A 1] {:frame :test/seam3})
    (rf/dispatch-sync [:set-owner3 :A 2] {:frame :test/seam3})
    (let [a-target-id (:epoch-id (first (rf/epoch-history :test/seam3)))
          {:keys [outcome epoch incarnation-token]}
          (rf.epoch.tool-pair/check-restore-preconditions! :test/seam3 a-target-id)
          fired?      (atom false)]
      (is (= :ok outcome))
      (rf/register-listener! :trace ::seam3-churn
        (fn [ev]
          (when (and (not @fired?) (= :rf.epoch/restored (:operation ev)))
            (reset! fired? true)
            (rf/destroy-frame! :test/seam3)
            (rf/make-frame {:id :test/seam3})
            (rf/dispatch-sync [:set-owner3 :B 99] {:frame :test/seam3}))))
      (is (true? (rf.epoch.tool-pair/perform-restore! :test/seam3 incarnation-token epoch)))
      (is (= {:owner :B :n 99} (rf/app-db-value :test/seam3)))
      (is (not= a-target-id (rf.epoch.state/last-settled-epoch-id :test/seam3))))))

(deftest restore-through-all-fences-still-succeeds
  (testing "control — with no churn the reconcile receives the live exact
            incarnation token, the restore installs and returns true, and the
            tail anchors last-settled to the restored epoch"
    (rf/make-frame {:id :test/allfences})
    (rf/reg-event :seedc
      (fn [{rt :rf.db/runtime} [_ n]]
        {:db {:n n} :rf.db/runtime (assoc (or rt {}) :marker :present)}))
    (rf/dispatch-sync [:seedc 1] {:frame :test/allfences})
    (rf/dispatch-sync [:seedc 2] {:frame :test/allfences})
    (let [target-id   (:epoch-id (first (rf/epoch-history :test/allfences)))
          token-live? (atom nil)
          rk          :resources/reconcile-on-restore
          r0          (rf.late-bind/get-fn rk)]
      (try
        (rf.late-bind/set-fn! rk
          (fn [rdb frame-id {:keys [owner-token]}]
            (reset! token-live? (and (some? owner-token)
                                     (rf.frame/frame-incarnation-live? frame-id owner-token)))
            rdb))
        (is (true? (rf/restore-epoch! :test/allfences target-id)))
        (is (true? @token-live?) "the reconcile saw the exact token, live")
        (is (= [{:n 1} target-id]
               [(rf/app-db-value :test/allfences)
                (rf.epoch.state/last-settled-epoch-id :test/allfences)]))
        (finally (rf.late-bind/set-fn! rk r0))))))

;; ---- state injection is the same exact-incarnation transaction -------------

(deftest replace-frame-state-fenced-to-exact-incarnation-leaves-same-id-successor-untouched
  (testing "an app-only injection validated against incarnation A, then A
            destroyed and a same-id successor B seated before the write, is
            refused with the typed :rf.error/no-such-handler, emits no
            :rf.epoch/db-replaced, delivers nothing to listeners, and leaves B's
            frame-state and history at their baselines"
    (rf/make-frame {:id :test/inj})
    (rf/reg-event :set-owner-inj (fn [_ [_ o]] {:db {:owner o}}))
    (rf/dispatch-sync [:set-owner-inj :A] {:frame :test/inj})
    (let [real-check rf.epoch.tool-pair/check-replace-frame-state-preconditions!
          b-baseline (atom nil)
          fanned     (atom [])
          recorded   (record-trace!)]
      (rf/register-listener! :epoch ::inj-fan #(swap! fanned conj %))
      (with-redefs [rf.epoch.tool-pair/check-replace-frame-state-preconditions!
                    (fn [frame-id new-frame-state]
                      ;; The REAL check passes against live A; then churn to B.
                      (let [r (real-check frame-id new-frame-state)]
                        (rf/destroy-frame! frame-id)
                        (rf/make-frame {:id frame-id})
                        (rf/dispatch-sync [:set-owner-inj :B] {:frame frame-id})
                        (reset! b-baseline [(rf/frame-state-value frame-id)
                                            (rf/epoch-history frame-id)])
                        (reset! fanned [])
                        r))]
        (is (false? (rf/replace-frame-state! :test/inj {:rf.db/app {:owner :STALE-A}}))))
      (is (= @b-baseline [(rf/frame-state-value :test/inj) (rf/epoch-history :test/inj)]))
      (is (= [[:error {:kind :frame :frame :test/inj}]] (no-such-frame-errors @recorded)))
      (is (not-any? #(= :rf.epoch/db-replaced (:operation %)) @recorded))
      (is (empty? @fanned)))))

(deftest replace-frame-state-post-write-tail-fenced-to-exact-incarnation
  (testing "A-to-B churn after A's physical write but before the synthetic
            bookkeeping: the result stays true (the install committed on A),
            but no synthetic record, anchor, :rf.epoch/db-replaced trace or
            listener delivery lands in successor B"
    (rf/make-frame {:id :test/inj3})
    (rf/reg-event :set-owner-inj3 (fn [_ [_ o]] {:db {:owner o}}))
    (rf/dispatch-sync [:set-owner-inj3 :A] {:frame :test/inj3})
    (let [real-write rf.frame/replace-frame-state!
          fanned     (atom [])
          recorded   (record-trace!)]
      (rf/register-listener! :epoch ::inj3-fan #(swap! fanned conj (:event-id %)))
      (is (true? (with-redefs [rf.frame/replace-frame-state!
                               (fn [frame-id token fs]
                                 ;; A's exact write lands, then the churn.
                                 (let [changed (real-write frame-id token fs)]
                                   (rf/destroy-frame! frame-id)
                                   (rf/make-frame {:id frame-id})
                                   (rf/dispatch-sync [:set-owner-inj3 :B] {:frame frame-id})
                                   changed))]
                   (rf/replace-frame-state! :test/inj3 {:rf.db/app {:owner :INJECTED-A}}))))
      (let [b-history (rf/epoch-history :test/inj3)]
        (is (= [:set-owner-inj3] (mapv :event-id b-history)))
        (is (= (:epoch-id (first b-history)) (rf.epoch.state/last-settled-epoch-id :test/inj3))))
      (is (= {:owner :B} (rf/app-db-value :test/inj3)))
      (is (not-any? #(= :rf.epoch/db-replaced (:operation %)) @recorded))
      (is (= [:set-owner-inj3] @fanned) "only B's own seed record was delivered"))))

(deftest replace-frame-state-post-liveness-teardown-returns-false
  (testing "a frame destroyed after the liveness gate passes but before the
            physical write returns (the write returns nil): replace-frame-state!
            returns false with the typed :rf.error/no-such-handler and emits no
            :rf.epoch/db-replaced"
    (rf/make-frame {:id :test/short-lived})
    (let [real-write rf.frame/replace-frame-state!
          recorded   (record-trace!)]
      (is (false? (with-redefs [rf.frame/replace-frame-state!
                                (fn [frame-id token fs]
                                  (rf/destroy-frame! frame-id)
                                  (real-write frame-id token fs))]
                    (rf/replace-frame-state! :test/short-lived {:rf.db/app {:n 999}}))))
      (is (= [[:error {:kind :frame :frame :test/short-lived}]] (no-such-frame-errors @recorded)))
      (is (not-any? #(= :rf.epoch/db-replaced (:operation %)) @recorded)))))

;; The other side of the nil / empty-set distinction: a write equal to the
;; current value returns an EMPTY changed-key set, which is a success.
(deftest replace-frame-state-app-only-noop-write-stays-successful
  (testing "a patch equal to the current app-db succeeds and records its
            synthetic epoch rather than reading as a destroyed-frame drop"
    (rf/make-frame {:id :test/main})
    (rf/reg-event :seed (fn [_ _] {:db {:n 7}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (is (true? (rf/replace-frame-state! :test/main {:rf.db/app {:n 7}})))
    (is (= [:seed :rf.epoch/db-replaced] (mapv :event-id (rf/epoch-history :test/main))))))

;; ---- the fenced tail ops are themselves fan-outs ---------------------------
;;
;; The host-work quiesce walks a chain of late-bound hooks and the deferred
;; resource-trace commit walks a list of intents. Each element is a callback
;; boundary addressing the frame by bare id, so the token has to travel into
;; the fan-out: a check before the loop does not fence its later iterations.

(defn- with-quiesce-hook-stubs
  "Install stub `:machines/on-frame-restored!` / `:http/abort-in-flight-for-frame!`
  quiesce hooks, run `f`, restore the originals."
  [machines-fn http-fn f]
  (let [mk :machines/on-frame-restored!
        hk :http/abort-in-flight-for-frame!
        m0 (rf.late-bind/get-fn mk)
        h0 (rf.late-bind/get-fn hk)]
    (try
      (rf.late-bind/set-fn! mk machines-fn)
      (rf.late-bind/set-fn! hk http-fn)
      (f)
      (finally
        (rf.late-bind/set-fn! mk m0)
        (rf.late-bind/set-fn! hk h0)))))

(deftest restore-quiesce-chain-fenced-per-hook
  (testing "restore carries the exact token into the quiesce chain: the first
            hook churns A to a same-id successor B, so the chain stops before
            the HTTP hook can abort B's requests; the committed install still
            returns true"
    (rf/make-frame {:id :test/sdeae-quiesce})
    (rf/reg-event :set-q (fn [_ [_ o n]] {:db {:owner o :n n}}))
    (rf/dispatch-sync [:set-q :A 1] {:frame :test/sdeae-quiesce})
    (rf/dispatch-sync [:set-q :A 2] {:frame :test/sdeae-quiesce})
    (let [target-id (:epoch-id (first (rf/epoch-history :test/sdeae-quiesce)))
          http-saw  (atom [])]
      (with-quiesce-hook-stubs
        (fn [fid]
          (rf/destroy-frame! fid)
          (rf/make-frame {:id fid})
          (rf/dispatch-sync [:set-q :B 99] {:frame fid}))
        (fn [fid] (swap! http-saw conj fid))
        (fn []
          (is (true? (rf/restore-epoch! :test/sdeae-quiesce target-id)))
          (is (= {:owner :B :n 99} (rf/app-db-value :test/sdeae-quiesce)))
          (is (empty? @http-saw)))))))

(defn- with-quiesce-warning-capture
  "Register a global `:trace` listener collecting every
  `:rf.warning/restore-quiesce-hook-exception` row, run `(f warnings-atom)`,
  unregister."
  [f]
  (let [k        ::vy2hj-quiesce-warnings
        warnings (atom [])]
    (rf/register-listener! :trace k
      (fn [ev]
        (when (= :rf.warning/restore-quiesce-hook-exception (:operation ev))
          (swap! warnings conj ev))))
    (try (f warnings)
         (finally (rf/unregister-listener! :trace k)))))

(deftest quiesce-warning-fence-admits-and-refuses-repeatedly
  (testing "a throwing quiesce hook warns, naming the hook, and the best-effort
            chain continues while the incarnation is live; once the hook has
            churned the incarnation the warning is withheld and the chain stops.
            Alternating rounds prove the fence re-reads the live slot rather
            than latching"
    (let [fid   :test/vy2hj-seq
          ;; One round: seat a fresh incarnation and fire the chain with a
          ;; throwing first hook, optionally churning the incarnation first.
          ;; Returns [warnings emitted, second hook ran?].
          round (fn [warnings churn?]
                  (let [before    (count @warnings)
                        http-ran? (atom false)]
                    (rf/make-frame {:id fid})
                    (let [token (rf.frame/frame-incarnation-token fid)]
                      (with-quiesce-hook-stubs
                        (fn [f]
                          (when churn?
                            (rf/destroy-frame! f)
                            (rf/make-frame {:id f}))
                          (throw (ex-info "hook failed" {})))
                        (fn [_f] (reset! http-ran? true))
                        #(rf.epoch.tool-pair/quiesce-orphaned-async-host-work! fid token)))
                    [(- (count @warnings) before) @http-ran?]))]
      (with-quiesce-warning-capture
        (fn [warnings]
          (is (= [[1 true] [0 false] [1 true] [0 false]]
                 (mapv #(round warnings %) [false true false true])))
          (is (= [:machines/on-frame-restored! :machines/on-frame-restored!]
                 (mapv #(get-in % [:tags :hook]) @warnings))))))))

(deftest restore-trace-commit-carries-owner-token
  (testing "restore hands the deferred resource-trace commit the frame id and
            the exact incarnation token, so its per-intent fan-out can
            revalidate ownership at every intent"
    (rf/make-frame {:id :test/sdeae-commit})
    (rf/reg-event :seed-c
      (fn [{rt :rf.db/runtime} _]
        {:db {:n 1} :rf.db/runtime (assoc (or rt {}) :marker :present)}))
    (rf/dispatch-sync [:seed-c] {:frame :test/sdeae-commit})
    (let [token (rf.frame/frame-incarnation-token :test/sdeae-commit)
          ck    :resources/commit-restore-reconcile!
          c0    (rf.late-bind/get-fn ck)
          seen  (atom nil)]
      (try
        (rf.late-bind/set-fn! ck (fn [_rdb frame-id opts] (reset! seen [frame-id (:owner-token opts)])))
        (rf/restore-epoch! :test/sdeae-commit (:epoch-id (first (rf/epoch-history :test/sdeae-commit))))
        (is (= :test/sdeae-commit (first @seen)))
        (is (identical? token (second @seen)))
        (finally (rf.late-bind/set-fn! ck c0))))))

;; The resources restore reconcile runs BEFORE the atomic install and defers its
;; success rows (:rf.resource/restored / :rf.resource/owner-released) as
;; metadata; perform-restore! commits them only once the install has landed.
;; The resources artefact is not loaded here, so its two hooks are stubbed with
;; bodies mirroring ssr.cljc.

(def ^:private rf2-obi8rr-deferred-key
  ;; the metadata key the real ssr.cljc reconcile uses; the stub mirrors it so
  ;; the commit stub reads the deferred intents the same way the real one does.
  :re-frame.resources.ssr/deferred-trace-intents)

(defn- with-stub-resources-restore-hooks
  "Install stub :resources/reconcile-on-restore (defers a :rf.resource/restored
  intent as metadata under :defer-traces? true) + :resources/commit-restore-
  reconcile! (emits whatever intents the reconcile deferred), run `f`, restore."
  [f]
  (let [rk :resources/reconcile-on-restore
        ck :resources/commit-restore-reconcile!
        r0 (rf.late-bind/get-fn rk)
        c0 (rf.late-bind/get-fn ck)]
    (try
      (rf.late-bind/set-fn! rk
                         (fn [rdb frame-id {:keys [defer-traces?]}]
                           (let [intents [{:level :rf.epoch
                                           :op    :rf.resource/restored
                                           :tags  {:rf.frame/id frame-id :reconciled 1}}
                                          {:level :rf.epoch
                                           :op    :rf.resource/owner-released
                                           :tags  {:rf.frame/id frame-id
                                                   :owner [:route :r "nav-OLD"]
                                                   :reason :stale-nav-orphan}}]]
                             (if defer-traces?
                               (vary-meta rdb assoc rf2-obi8rr-deferred-key intents)
                               (do (doseq [{:keys [level op tags]} intents]
                                     (rf.trace/emit! level op tags))
                                   rdb)))))
      ;; Mirrors the real ssr.cljc body: the commit revalidates exact ownership
      ;; at every intent.
      (rf.late-bind/set-fn! ck
                         (fn [rdb frame-id {:keys [owner-token]}]
                           (doseq [{:keys [level op tags]} (-> rdb meta (get rf2-obi8rr-deferred-key))
                                   :while (or (nil? frame-id)
                                              (nil? owner-token)
                                              (rf.frame/frame-incarnation-live? frame-id owner-token))]
                             (rf.trace/emit! level op tags))
                           nil))
      (f)
      (finally
        (rf.late-bind/set-fn! rk r0)
        (rf.late-bind/set-fn! ck c0)))))

(deftest restore-epoch-post-liveness-teardown-returns-false
  (testing "a frame destroyed after the write-boundary liveness gate passes but
            before the physical write returns: perform-restore! returns false
            with the typed :rf.error/no-such-handler and emits no success
            telemetry — neither :rf.epoch/restored nor the resource rows the
            reconcile deferred"
    (rf/make-frame {:id :test/short-lived})
    (rf/reg-event :seed-res
      (fn [{rt :rf.db/runtime} [_ n]]
        {:db {:n n} :rf.db/runtime (assoc (or rt {}) :marker :present)}))
    (rf/dispatch-sync [:seed-res 0] {:frame :test/short-lived})
    (rf/dispatch-sync [:seed-res 1] {:frame :test/short-lived})
    (with-stub-resources-restore-hooks
      (fn []
        (let [{:keys [outcome epoch incarnation-token]}
              (rf.epoch.tool-pair/check-restore-preconditions!
                :test/short-lived (:epoch-id (first (rf/epoch-history :test/short-lived))))
              real-write rf.frame/replace-frame-state!
              recorded   (record-trace!)
              result     (with-redefs [rf.frame/replace-frame-state!
                                       (fn [frame-id token fs]
                                         (rf/destroy-frame! frame-id)
                                         (real-write frame-id token fs))]
                           (rf.epoch.tool-pair/perform-restore!
                             :test/short-lived incarnation-token epoch))]
          (is (= :ok outcome))
          (is (false? result))
          (is (= [[:error {:kind :frame :frame :test/short-lived}]]
                 (no-such-frame-errors @recorded)))
          (is (not-any? #{:rf.epoch/restored :rf.resource/restored :rf.resource/owner-released}
                        (map :operation @recorded))))))))

(deftest restore-successful-install-emits-deferred-resource-traces
  (testing "a successful restore commits the resource rows the reconcile
            deferred, after :rf.epoch/restored"
    (rf/make-frame {:id :test/obi8rr-ok})
    (rf/reg-event :seed-res2
      (fn [{rt :rf.db/runtime} [_ n]]
        {:db {:n n} :rf.db/runtime (assoc (or rt {}) :marker :present)}))
    (rf/dispatch-sync [:seed-res2 0] {:frame :test/obi8rr-ok})
    (rf/dispatch-sync [:seed-res2 1] {:frame :test/obi8rr-ok})
    (with-stub-resources-restore-hooks
      (fn []
        (let [recorded (record-trace!)
              success? #{:rf.epoch/restored :rf.resource/restored :rf.resource/owner-released}]
          (is (true? (rf/restore-epoch! :test/obi8rr-ok
                                        (:epoch-id (first (rf/epoch-history :test/obi8rr-ok))))))
          (is (= [:rf.epoch/restored :rf.resource/restored :rf.resource/owner-released]
                 (filterv success? (map :operation @recorded)))))))))

;; ============================================================================
;;  Same-id listener replacement generations
;; ============================================================================
;;
;; `put-listener!` publishes a new callback with a fresh GENERATION token in one
;; swap, and every observation is stamped with the generation that recorded it.
;; So a same-id replacement can neither erase a fresh observation (a second,
;; ledger-clearing swap would) nor let a stale one arm the new registration.

(deftest same-id-replacement-preserves-new-callback-observation
  (testing "a same-id replacement parked right after publishing the new
            callback: a record fanned out during the pause is observed by the
            new generation, the completed replacement does not erase that
            observation, and destroying the frame silences it exactly once"
    (rf/make-frame {:id :j538/race})
    (let [recorded       (record-trace!)
          published      (promise)
          release        (java.util.concurrent.CountDownLatch. 1)
          armed?         (atom false)
          listeners-atom (deref #'rf.epoch.state/listeners)]
      (rf/register-listener! :epoch ::probe (fn [_] nil))
      ;; Park the next listeners swap (the replacement's publish) until the
      ;; main thread has fanned a record out.
      (add-watch listeners-atom ::barrier
                 (fn [_ _ _ _]
                   (when (compare-and-set! armed? true false)
                     (deliver published :published)
                     (.await ^java.util.concurrent.CountDownLatch release))))
      (reset! armed? true)
      (let [replace-fut (future (rf/register-listener! :epoch ::probe (fn [_] nil)))]
        (try
          (is (= :published (deref published 5000 ::timeout)))
          (rf.epoch.listeners/notify-listeners! {:frame :j538/race :epoch-id 1})
          (finally
            (.countDown release)
            (is (not= ::timeout (deref replace-fut 5000 ::timeout)))
            (remove-watch listeners-atom ::barrier))))
      (rf/destroy-frame! :j538/race)
      (is (= 1 (count (filter #(and (= :rf.epoch.cb/silenced-on-frame-destroy (:operation %))
                                    (= ::probe (:cb-id (:tags %))))
                              @recorded)))))))

(deftest stale-old-generation-fanout-cannot-arm-new-registration
  (testing "a fan-out holding the OLD generation's token cannot record an
            observation for the same-id replacement"
    (rf/register-listener! :epoch ::probe (fn [_] nil))
    (let [stale-gen (:generation (get (rf.epoch.state/listeners-snapshot) ::probe))]
      (rf/register-listener! :epoch ::probe (fn [_] nil))
      (rf.epoch.state/record-observation! ::probe stale-gen :j538/mirror)
      (is (not (contains? (get (rf.epoch.state/observations-snapshot) ::probe)
                          :j538/mirror))))))

(deftest generation-scoped-observations-across-frames
  (testing "old and new generations observing DIFFERENT frames: destroying the
            frame only the replaced generation observed emits no silence,
            destroying the live generation's frame emits exactly one
            :rf.epoch.cb silence, and a same-id frame recreation re-arms it"
    (rf/make-frame {:id :j538/frame-a})
    (rf/make-frame {:id :j538/frame-b})
    (let [recorded (record-trace!)
          silences (fn [frame]
                     (count (filter #(= [:rf.epoch.cb :rf.epoch.cb/silenced-on-frame-destroy
                                         {:frame frame :cb-id ::probe}]
                                        [(:op-type %) (:operation %)
                                         (select-keys (:tags %) [:frame :cb-id])])
                                    @recorded)))]
      (rf/register-listener! :epoch ::probe (fn [_] nil))
      (rf.epoch.listeners/notify-listeners! {:frame :j538/frame-a :epoch-id 1})
      (rf/register-listener! :epoch ::probe (fn [_] nil))
      (rf.epoch.listeners/notify-listeners! {:frame :j538/frame-b :epoch-id 2})
      (rf/destroy-frame! :j538/frame-a)
      (rf/destroy-frame! :j538/frame-b)
      (is (= [0 1] [(silences :j538/frame-a) (silences :j538/frame-b)]))
      (rf/make-frame {:id :j538/frame-b})
      (rf.epoch.listeners/notify-listeners! {:frame :j538/frame-b :epoch-id 3})
      (rf/destroy-frame! :j538/frame-b)
      (is (= 2 (silences :j538/frame-b))))))
