(ns re-frame.epoch-egress-historic-classification-test
  "An epoch record egresses under the classification that applied when it was
  recorded, not only under the frame's registry as it stands now.

  Removing a classification owner drops its claims from the frame's elision
  registry while the ring still holds values those claims governed:

    - destroying a machine actor drops the claims lowered for its snapshot,
      which the destroy record's `:frame-state-before` and every older record
      carry in their runtime-db. Under the trusted-local
      `:rf.egress/include-runtime-db? true` opt-in those partitions are walked;
      the default profile redacts them whole and is the control;
    - clearing a flow drops the claims on its output, and a declassifying event
      effect drops its own, while older records carry the classified value in
      every app-db slot — `:db-before` / `:db-after` and each frame-state's
      `:rf.db/app` — which egress under the default profile. (The pending db
      on the `:rf.event/db-pending*` traces is classified as it is emitted.)

  Each deftest is `^:requires-debug`: it projects recorded epochs, and under
  `-Dre-frame.debug=false` the ring records none."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch]
            [re-frame.flows]
            [re-frame.machines]
            [re-frame.elision :as rf.elision]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private secret "secret-historic-actor-token")
(def ^:private blob (apply str "blob-" (repeat 64 "b")))
(def ^:private probe-type ::probe)
(def ^:private probe-actor ::probe-actor)

(def ^:private runtime-db-opt {:rf.egress/include-runtime-db? true})

(defn- snapshot-data-path
  "Path to the actor's snapshot `:data` inside a frame-state slot."
  [slot]
  [slot :rf.db/runtime :rf.runtime/machines :snapshots probe-actor :data])

(defn- leaks? [x] (.contains (pr-str x) ^String secret))

(defn- record-for
  "The ring's record for `event-id`. Selected by id rather than taken as the
  newest, because a destroy is followed by other artefacts' own events (the
  resources artefact releases the actor's owned resources)."
  [event-id]
  (some #(when (= event-id (:event-id %)) %) (rf/epoch-history :rf/default)))

(defn- actor-claims
  "The frame's live declarations rooted at the actor's snapshot, both axes."
  []
  (let [prefix (conj rf.elision/machine-snapshot-prefix probe-actor)
        under  (fn [decls]
                 (filterv #(= prefix (subvec (vec %) 0 (min (count %) (count prefix))))
                          (keys decls)))]
    (into (under (rf.elision/sensitive-declarations :rf/default))
          (under (rf.elision/declarations :rf/default)))))

(defn- assert-classified
  "`data` is a projected snapshot `:data` map: token redacted, blob marked,
  the unclassified sibling verbatim."
  [data]
  (is (= :rf/redacted (:token data)))
  (is (rf.elision/marker? (:blob data)))
  (is (= "visible" (:note data)) "an unclassified sibling slot rides verbatim"))

(deftest ^:requires-debug a-destroyed-actors-snapshot-keeps-its-recorded-classification
  (rf/reg-machine probe-type
    {:sensitive [[:data :token]]
     :large     [[:data :blob]]
     :initial   :idle
     :data      {:token secret :blob blob :note "visible"}
     :states    {:idle {}}})
  (rf/reg-event ::spawn
    (fn [_ _] {:fx [[:rf.machine/spawn {:machine-id probe-type :fixed-actor-id probe-actor}]]}))
  (rf/reg-event ::destroy
    (fn [_ _] {:fx [[:rf.machine/destroy probe-actor]]}))

  (rf/dispatch-sync [::spawn])
  (let [spawn-record (record-for ::spawn)]
    (testing "control: while the actor lives its claims classify the spawn record"
      (is (seq (actor-claims)))
      (assert-classified (get-in (rf/project-egress spawn-record runtime-db-opt)
                                 (snapshot-data-path :frame-state-after))))

    (rf/dispatch-sync [::destroy])
    (let [destroy-record (record-for ::destroy)]
      (testing "the destroy drops the actor's claims; none is retained for egress"
        (is (empty? (actor-claims))))

      (testing "the destroy record's :frame-state-before is classified"
        (let [projected (rf/project-egress destroy-record runtime-db-opt)]
          (assert-classified (get-in projected (snapshot-data-path :frame-state-before)))
          (is (not (leaks? projected)))))

      (testing "an older record re-projected after the destroy is classified"
        (let [projected (rf/project-egress spawn-record runtime-db-opt)]
          (assert-classified (get-in projected (snapshot-data-path :frame-state-after)))
          (is (not (leaks? projected)))))

      (testing "control: the default profile redacts the runtime-db partition whole"
        (doseq [record [spawn-record destroy-record]]
          (is (not (leaks? (rf/project-egress record))))))

      (testing "control: the sensitive opt-in still reveals the recorded secret"
        (is (= secret (get-in (rf/project-egress
                                spawn-record
                                (assoc runtime-db-opt :rf.egress/include-sensitive? true))
                              (conj (snapshot-data-path :frame-state-after) :token)))))

      (testing "the in-process records stay raw"
        (is (= secret (get-in destroy-record
                              (conj (snapshot-data-path :frame-state-before) :token))))
        (is (= secret (get-in spawn-record
                              (conj (snapshot-data-path :frame-state-after) :token))))))))

;; ---- app-db: a cleared flow, a declassifying effect -----------------------

(def ^:private flow-secret "secret-historic-flow-output")
(def ^:private auth-secret "secret-historic-auth-token")

(defn- app-db-copies
  "The app-db slots `record` carries, as `[slot db]` pairs: the two db slots
  and each frame-state's `:rf.db/app`."
  [record]
  [[:db-before (:db-before record)]
   [:db-after (:db-after record)]
   [:frame-state-before (get-in record [:frame-state-before :rf.db/app])]
   [:frame-state-after (get-in record [:frame-state-after :rf.db/app])]])

(defn- values-at
  "`[slot value]` for every app-db slot of `record` holding a value at `path`."
  [record path]
  (vec (keep (fn [[slot db]]
               (let [v (get-in db path)]
                 (when (some? v) [slot v])))
             (app-db-copies record))))

(defn- all-redacted?
  "Some app-db slot of `record` holds `path`, and every one holding it is redacted."
  [record path]
  (let [vs (values-at record path)]
    (and (seq vs) (every? #(= :rf/redacted (second %)) vs))))

(deftest ^:requires-debug a-cleared-flows-output-keeps-its-recorded-classification
  (rf/reg-event ::seed (fn [{:keys [db]} [_ s]] {:db (assoc db :src s)}))
  (rf/reg-event ::touch (fn [{:keys [db]} _] {:db (update db :touches (fnil inc 0))}))
  (rf/reg-flow ::out {:inputs [[:src]] :output-path [:out] :sensitive [[:token]]}
    (fn [s] {:token (str "secret-historic-flow-" s) :note "visible"}))
  (rf/dispatch-sync [::seed "output"])
  (rf/dispatch-sync [::touch])
  (let [seed-record  (record-for ::seed)
        touch-record (record-for ::touch)
        token-path   [:out :token]]
    (testing "control: the raw records carry the output in every app-db slot after the flow ran"
      (is (= [[:db-after flow-secret] [:frame-state-after flow-secret]]
             (values-at seed-record token-path)))
      (is (= [[:db-before flow-secret] [:db-after flow-secret]
              [:frame-state-before flow-secret] [:frame-state-after flow-secret]]
             (values-at touch-record token-path))))

    (testing "control: while the flow is registered its claim classifies them"
      (is (all-redacted? (rf/project-egress seed-record) token-path))
      (is (all-redacted? (rf/project-egress touch-record) token-path)))

    (rf/clear :flow ::out)
    (testing "clearing the flow drops its claim"
      (is (not (contains? (rf.elision/sensitive-declarations :rf/default) token-path))))

    (testing "every app-db slot on the older records keeps the claim it was recorded under"
      (doseq [record [seed-record touch-record]
              :let [projected (rf/project-egress record)]]
        (is (all-redacted? projected token-path))
        (is (= "visible" (get-in projected [:db-after :out :note]))
            "an unclassified sibling slot rides verbatim")
        (is (not (.contains (pr-str projected) ^String flow-secret)))))))

(deftest ^:requires-debug a-declassified-path-stays-redacted-on-records-made-while-classified
  (rf/reg-event ::sign-in
    (fn [{:keys [db]} _] {:db (assoc db :auth {:token auth-secret}) :sensitive [[:auth :token]]}))
  (rf/reg-event ::declassify (fn [_ _] {:clear-sensitive [[:auth :token]]}))
  (rf/dispatch-sync [::sign-in])
  (let [sign-in-record (record-for ::sign-in)
        token-path     [:auth :token]]
    (testing "control: classified while the claim is live"
      (is (all-redacted? (rf/project-egress sign-in-record) token-path)))

    (rf/dispatch-sync [::declassify])
    (testing "the effect drops its claim"
      (is (not (contains? (rf.elision/sensitive-declarations :rf/default) token-path))))

    (testing "the record made while the path was classified stays redacted"
      (let [projected (rf/project-egress sign-in-record)]
        (is (all-redacted? projected token-path))
        (is (not (.contains (pr-str projected) ^String auth-secret)))))

    (testing "a value recorded after the declassification egresses"
      (is (= auth-secret (get-in (rf/project-egress (record-for ::declassify))
                                 [:db-after :auth :token]))))))
