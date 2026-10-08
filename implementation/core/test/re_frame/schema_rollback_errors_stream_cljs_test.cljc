(ns re-frame.schema-rollback-errors-stream-cljs-test
  "A rejected CANDIDATE TRANSITION reports on the always-on `:errors` stream,
  under the validator's own dev gate. Four `:rollback? true` producers report
  this way — `:where :app-db`, `:where :machine-data`, and the two
  `:rf.error/malformed-schema` sites — and share these fixtures so the arms
  cannot drift apart.

  A trace-only rejection is invisible to an application: a permanent rollback
  loop renders nothing and says nothing on the stream an app listens to. So
  each positive deftest carries a control or negative half beside its claim.

  The record's shape is the contract. It is BUILT FROM a closed allow-list of
  structural inputs, so its key set is pinned EQUAL (which also proves every
  payload-bearing trace slot — `:value`, `:explain`, `:schema`, `:path` … —
  absent), and `pr-str` over the record is swept for a planted sentinel. The
  dev trace keeps its full payload, and that half is asserted too.

  The positive deftests are `^:requires-debug`; the `^:prod-gate` deftests at
  the foot are the discriminators (JVM-only: shadow-cljs honours no var tags,
  and `scripts/check-elision.cjs` holds the CLJS half)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.event-emit :as rf.event-emit]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            ;; optional artefacts reached through late-bind hooks: without these
            ;; requires every deftest would pass vacuously
            [re-frame.schemas]
            [re-frame.machines]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     ;; the listener registry is defonce; a leaked sibling listener would
     ;; pollute the counts here
     :init-fn (fn [] (rf.error-emit/clear-error-listeners!))}))

(def ^:private rejection-record-keys
  #{:error :where :registered-path :event-id :failing-id :frame
    :rollback? :recovery :reason :time})

(def ^:private planted-value "rf2-xpd8-planted-payload-must-not-egress")

(def ^:private frame-id :xpd8/rollback)

(def ^:private fx-ran (atom 0))

(defn- register-app!
  "Four `:int` paths over an empty app-db; :xpd8/seed writes one conforming
  slot and one violating one, so three of the four fail."
  []
  (reset! fx-ran 0)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id
    (doseq [p [[:conforms] [:absent-a] [:absent-b] [:leaks]]]
      (rf/reg-app-schema p :int)))
  (rf/reg-fx :xpd8/must-not-run (fn [_ctx _args] (swap! fx-ran inc) nil))
  (rf/reg-event :xpd8/seed
    (fn [_ _] {:db {:conforms 1 :leaks planted-value} :fx [[:xpd8/must-not-run {}]]}))
  (rf/reg-event :xpd8/all-good
    (fn [_ _] {:db {:conforms 1 :absent-a 2 :absent-b 3 :leaks 4}}))
  nil)

(defn- first-index [pred coll]
  (first (keep-indexed (fn [i x] (when (pred x) i)) coll)))

(defn- capture
  "Run `body-fn` with listeners on all three streams; `:errors` and `:trace`
  share one sequenced vector so their ORDER is observable."
  [body-fn]
  (let [sequenced (atom [])
        events    (atom [])]
    (rf.error-emit/register-error-listener! ::rec (fn [r] (swap! sequenced conj [:errors r])))
    (rf/register-listener! :trace  ::rec (fn [e] (swap! sequenced conj [:trace e])))
    (rf.event-emit/register-event-listener! ::rec (fn [r] (swap! events conj r)))
    (try
      (body-fn)
      (finally
        (rf.error-emit/unregister-error-listener! ::rec)
        (rf/unregister-listener! :trace  ::rec)
        (rf.event-emit/unregister-event-listener! ::rec)))
    (let [seqd @sequenced]
      {:sequence seqd
       :errors   (mapv second (filter #(= :errors (first %)) seqd))
       :traces   (mapv second (filter #(= :trace  (first %)) seqd))
       :events   @events})))

(defn- errors-where [captured error where]
  (filterv #(and (= error (:error %)) (or (nil? where) (= where (:where %))))
           (:errors captured)))

(defn- traces-where [captured operation where]
  (filterv #(and (= operation (:operation %)) (or (nil? where) (= where (:where (:tags %)))))
           (:traces captured)))

(defn- record-precedes-trace?
  "The record is emitted before its trace (axis-1-then-axis-2), compared over
  the rejection entries only."
  [captured where]
  (let [seqd    (:sequence captured)
        rec-idx (first-index (fn [[kind v]]
                               (and (= :errors kind)
                                    (= :rf.error/schema-validation-failure (:error v))
                                    (= where (:where v))))
                             seqd)
        trc-idx (first-index (fn [[kind v]]
                               (and (= :trace kind)
                                    (= :rf.error/schema-validation-failure (:operation v))
                                    (= where (:where (:tags v)))))
                             seqd)]
    (and rec-idx trc-idx (< rec-idx trc-idx))))

(defn- outcome-of [captured event-id]
  (:outcome (first (filter #(= event-id (:event-id %)) (:events captured)))))

(defn- schemas-present? []
  (some? (rf.late-bind/get-fn :schemas/validate-app-schema!)))

(defn- machines-present? []
  (some? (rf.late-bind/get-fn :machines/validate-machine-data!)))

;; ---- arm 1: `:where :machine-data` at `:rollback? true` ------------------

(def ^:private machine-frame-id :vkn8/machine)
(def ^:private machine-id       :vkn8.machine/counter)
(def ^:private malformed-frame-id :vkn8/malformed)
(def ^:private throw-frame-id     :vkn8/validator-throw)
(def ^:private machine-planted-value "rf2-vkn8-planted-machine-data-must-not-egress")

(def ^:private malformed-record-keys
  "Both malformed-schema records; the router's backstop carries it minus
  `:registered-path`, because a wholesale throw names no registration."
  #{:error :where :registered-path :event-id :failing-id :frame
    :rollback? :recovery :reason :time})

(defn- runtime-db-value [frame]
  (:rf.db/runtime (rf/frame-state-value frame)))

(defn- register-machine-app! []
  (rf/make-frame {:id machine-frame-id})
  (rf/reg-machine machine-id
    {:initial :idle
     :data    {:n 1}
     :schemas {:data [:map [:n pos-int?] [:note {:optional true} :string]]}
     :actions {:break (fn [_] {:data {:n 0 :note machine-planted-value}})
               :bump  (fn [_] {:data {:n 2}})
               ;; the escape-hatch fx validates at :phase :update-snapshot with
               ;; :rollback? false — a skipped local write, the negative control
               :patch (fn [_]
                        {:fx [[:rf.machine/update-snapshot
                               {:rf/machine-id machine-id
                                :rf/patch      {:data {:n 0}}}]]})}
     :states  {:idle {:on {:break {:target :idle :action :break}
                           :bump  {:target :idle :action :bump}
                           :patch {:target :idle :action :patch}}}}})
  ;; settle the conforming initial :data so the violation is the macrostep's
  (rf/dispatch-sync [machine-id [:noop]] {:frame machine-frame-id}))

(deftest ^:requires-debug rejected-machine-data-candidate-reaches-the-errors-stream
  (when (and rf.interop/debug-enabled? (schemas-present?) (machines-present?))
    (register-machine-app!)
    (let [before   (runtime-db-value machine-frame-id)
          captured (capture #(rf/dispatch-sync [machine-id [:break]] {:frame machine-frame-id}))
          records  (errors-where captured :rf.error/schema-validation-failure :machine-data)
          r        (first records)]
      (testing "one record, naming the machine, the phase and — so it reaches the
                frame's own :observability :errors sink — the frame"
        (is (= [1 {:where :machine-data :machine-id machine-id :failing-id machine-id
                   :phase :macrostep :rollback? true :recovery :no-recovery
                   :frame machine-frame-id}]
               [(count records)
                (select-keys r [:where :machine-id :failing-id :phase :rollback? :recovery :frame])]))
        (is (= #{:error :where :machine-id :failing-id :phase :frame :rollback? :recovery :reason :time}
               (set (keys r)))
            "the key set is CLOSED and EQUAL"))
      (testing "no payload: the machine's :data is what :sensitive protects"
        (is (not (str/includes? (pr-str r) machine-planted-value)))
        (is (= [true true] [(str/includes? (:reason r) (pr-str machine-id))
                            (str/includes? (:reason r) "rejected")])
            "the reason names the machine and the fate of the transaction"))
      (testing "the dev trace keeps the offending :data; its frame comes from the bus stamp"
        (let [traces (traces-where captured :rf.error/schema-validation-failure :machine-data)]
          (is (= [1 true true machine-frame-id]
                 [(count traces)
                  (contains? (:tags (first traces)) :value)
                  (str/includes? (pr-str (:tags (first traces))) machine-planted-value)
                  (:frame (:tags (first traces)))]))))
      (is (record-precedes-trace? captured :machine-data))
      (is (= [before :rolled-back] [(runtime-db-value machine-frame-id) (outcome-of captured machine-id)])
          "the snapshot never installed, and :events carries the consequence"))))

(deftest ^:requires-debug machine-data-negative-controls
  (when (and rf.interop/debug-enabled? (schemas-present?) (machines-present?))
    (register-machine-app!)
    (testing "a conforming macrostep fans nothing"
      (let [captured (capture #(rf/dispatch-sync [machine-id [:bump]] {:frame machine-frame-id}))]
        (is (empty? (errors-where captured :rf.error/schema-validation-failure :machine-data)))))
    (testing "an :update-snapshot violation is :rollback? false — trace-only, so
              the promotion is scoped to :rollback? true, not to :where"
      (let [captured (capture #(rf/dispatch-sync [machine-id [:patch]] {:frame machine-frame-id}))]
        (is (= #{:update-snapshot}
               (set (map #(:phase (:tags %))
                         (traces-where captured :rf.error/schema-validation-failure :machine-data))))
            "premise: the escape-hatch violation fired its trace")
        (is (empty? (errors-where captured :rf.error/schema-validation-failure :machine-data)))))))

;; ---- arm 2: `:rf.error/malformed-schema`, per registered entry -----------

(defn- register-malformed-app! []
  (rf/make-frame {:id malformed-frame-id})
  ;; a childless [:vector] registers cleanly (Malli checks forms lazily) and
  ;; then throws on the first candidate validation
  (rf/with-frame malformed-frame-id
    (rf/reg-app-schema [:broken] [:vector]))
  (rf/reg-event :vkn8/malformed-write
    (fn [_ _] {:db {:broken [1 2 3] :note planted-value}}))
  nil)

(deftest ^:requires-debug malformed-registered-schema-reaches-the-errors-stream
  (when (and rf.interop/debug-enabled? (schemas-present?))
    (register-malformed-app!)
    (let [before   (rf/app-db-value malformed-frame-id)
          captured (capture #(rf/dispatch-sync [:vkn8/malformed-write] {:frame malformed-frame-id}))
          records  (errors-where captured :rf.error/malformed-schema nil)
          r        (first records)
          traces   (traces-where captured :rf.error/malformed-schema nil)]
      (is (= [1 {:where :app-db :registered-path [:broken] :event-id :vkn8/malformed-write
                 :failing-id :vkn8/malformed-write :frame malformed-frame-id
                 :rollback? true :recovery :no-recovery}]
             [(count records)
              (select-keys r [:where :registered-path :event-id :failing-id :frame :rollback? :recovery])]))
      (is (= malformed-record-keys (set (keys r))) "the key set is CLOSED and EQUAL")
      (testing "the :reason is a constant sentence naming the path, never the
                validator's unbounded, author-controlled message; the trace
                keeps the schema form the record omits"
        (is (= [false true] [(str/includes? (pr-str r) planted-value)
                             (str/includes? (:reason r) "[:broken]")]))
        (is (= [true true] [(contains? (:tags (first traces)) :schema)
                            (not= (:reason (:tags (first traces))) (:reason r))])))
      (is (= before (rf/app-db-value malformed-frame-id))
          "rejected fail-closed: a validator that threw cannot prove conformance"))))

;; ---- arm 3: the router's wholesale validator-machinery backstop ----------

(deftest ^:requires-debug validator-machinery-throw-reaches-the-errors-stream
  (when (and rf.interop/debug-enabled? (schemas-present?))
    (rf/make-frame {:id throw-frame-id})
    (rf/reg-event :vkn8/throw-write (fn [_ _] {:db {:whatever 1}}))
    (let [real (rf.late-bind/get-fn :schemas/validate-app-schema!)]
      (try
        ;; break the HOOK itself: only a wholesale throw escapes to the
        ;; router's catch (a per-entry throw is arm 2)
        (rf.late-bind/set-fn! :schemas/validate-app-schema!
                              (fn [& _] (throw (ex-info planted-value {}))))
        (let [before   (rf/app-db-value throw-frame-id)
              captured (capture #(rf/dispatch-sync [:vkn8/throw-write] {:frame throw-frame-id}))
              records  (errors-where captured :rf.error/malformed-schema nil)
              r        (first records)
              traces   (traces-where captured :rf.error/malformed-schema nil)]
          (is (= [1 {:where :app-db :event-id :vkn8/throw-write :failing-id :vkn8/throw-write
                     :frame throw-frame-id :rollback? true :recovery :no-recovery}]
                 [(count records)
                  (select-keys r [:where :event-id :failing-id :frame :rollback? :recovery])]))
          (is (= (disj malformed-record-keys :registered-path) (set (keys r))))
          (is (= [false true] [(str/includes? (pr-str r) planted-value)
                               (str/includes? (pr-str (:tags (first traces))) planted-value)])
              "the throwing validator's message stays on the trace, never the record")
          (is (= before (rf/app-db-value throw-frame-id)) "nothing installs"))
        (finally
          (rf.late-bind/set-fn! :schemas/validate-app-schema! real))))))

;; ---- the :app-db 0-vs-N split, with its control --------------------------

(deftest ^:requires-debug rejected-candidate-reaches-the-errors-stream
  (when (and rf.interop/debug-enabled? (schemas-present?))
    (register-app!)
    (let [before   (rf/app-db-value frame-id)
          captured (capture #(rf/dispatch-sync [:xpd8/seed] {:frame frame-id}))
          records  (errors-where captured :rf.error/schema-validation-failure :app-db)]
      (testing "one record per FAILING registered entry, each naming its registration"
        (is (= #{[:absent-a] [:absent-b] [:leaks]} (set (map :registered-path records))))
        (is (= #{{:where :app-db :rollback? true :recovery :no-recovery :frame frame-id
                  :event-id :xpd8/seed :failing-id :xpd8/seed :keys rejection-record-keys}}
               (set (map #(assoc (select-keys % [:where :rollback? :recovery :frame :event-id :failing-id])
                                 :keys (set (keys %)))
                         records)))
            "and every record carries the same structural slots under the CLOSED key set")
        (is (= 3 (count records))))
      (is (not-any? #(str/includes? (pr-str %) planted-value) records)
          "the planted candidate value never reaches the always-on record")
      (testing "the :reason names the path and the TYPE of the failing leaf, never the leaf"
        (let [by-path (into {} (map (juxt :registered-path :reason)) records)]
          (is (= [true true] [(str/includes? (get by-path [:leaks] "") "got string")
                              (str/includes? (get by-path [:absent-a] "") "got nil")]))
          (is (every? (fn [[path reason]]
                        (and (str/includes? reason "the candidate transition was rejected")
                             (str/includes? reason (pr-str path))))
                      by-path))))
      (testing "the dev trace keeps its payload — a trace quietened to make room
                for the record would pass every assertion above"
        (let [traces (traces-where captured :rf.error/schema-validation-failure :app-db)]
          (is (= 3 (count traces)))
          (is (every? #(and (contains? (:tags %) :value) (contains? (:tags %) :path)) traces))
          (is (some #(str/includes? (pr-str (:tags %)) planted-value) traces))))
      (is (record-precedes-trace? captured :app-db))
      (is (= [before 0 :rolled-back]
             [(rf/app-db-value frame-id) @fx-ran (outcome-of captured :xpd8/seed)])
          "the candidate never installed, :fx never walked, and :events carries the consequence"))))

(deftest ^:requires-debug conforming-commit-is-silent-and-the-control-is-live
  (when (and rf.interop/debug-enabled? (schemas-present?))
    (register-app!)
    (let [captured (capture #(rf/dispatch-sync [:xpd8/all-good] {:frame frame-id}))]
      (is (empty? (errors-where captured :rf.error/schema-validation-failure :app-db)))
      (is (= {:conforms 1 :absent-a 2 :absent-b 3 :leaks 4} (rf/app-db-value frame-id))))
    (testing "CONTROL: the same listener does receive an unrelated always-on category"
      (let [captured (capture #(rf/dispatch-sync [:xpd8/no-such-event] {:frame frame-id}))]
        (is (seq (errors-where captured :rf.error/no-such-handler nil)))))))

;; ---- the production gate: the records are gated WITH the check -----------

#?(:clj
   (deftest ^:prod-gate rejection-record-is-absent-under-the-production-gate
     ;; under -Dre-frame.debug=false the candidate validator does not run, so
     ;; there is nothing to report and the candidate installs (Spec 010
     ;; §Production builds)
     (is (false? rf.interop/debug-enabled?) "premise: the real production gate")
     (when (schemas-present?)
       (register-app!)
       (let [captured (capture #(rf/dispatch-sync [:xpd8/seed] {:frame frame-id}))]
         (is (empty? (errors-where captured :rf.error/schema-validation-failure :app-db)))
         (is (= :ok (outcome-of captured :xpd8/seed)))))))

#?(:clj
   (deftest ^:prod-gate pr2-rejection-records-are-absent-under-the-production-gate
     ;; none of the three other producers runs under the gate; the router's
     ;; backstop needs its own debug-enabled? check because candidate
     ;; validation itself runs in every build
     (is (false? rf.interop/debug-enabled?) "premise: the real production gate")
     (when (and (schemas-present?) (machines-present?))
       (register-machine-app!)
       (let [captured (capture #(rf/dispatch-sync [machine-id [:break]] {:frame machine-frame-id}))]
         (is (empty? (errors-where captured :rf.error/schema-validation-failure :machine-data)))
         (is (empty? (traces-where captured :rf.error/schema-validation-failure :machine-data)))))
     (when (schemas-present?)
       (register-malformed-app!)
       (let [captured (capture #(rf/dispatch-sync [:vkn8/malformed-write] {:frame malformed-frame-id}))]
         (is (empty? (errors-where captured :rf.error/malformed-schema nil)))
         (is (= {:broken [1 2 3] :note planted-value} (rf/app-db-value malformed-frame-id))
             "the candidate installs: a malformed registration is never consulted")))))
