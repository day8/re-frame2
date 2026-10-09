(ns re-frame.epoch-egress-historic-classification-test
  "An epoch record egresses under the classification recorded with it, not only
  under the frame's registry as it stands now. Removing a classification owner —
  destroying a machine actor, clearing a flow, a declassifying effect — drops its
  claims from the live registry while older records still hold the values those
  claims governed.

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

(defn- leaks? [x needle] (.contains (pr-str x) ^String needle))

(defn- record-for
  "The ring's record for `event-id`, selected by id because a destroy is
  followed by other artefacts' own events."
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
  (is (seq (actor-claims)) "control: the live actor's claims are visible to the probe")
  (rf/dispatch-sync [::destroy])
  (is (empty? (actor-claims)) "the destroy drops the actor's claims; none is retained for egress")

  (testing "the spawn record's `:frame-state-after` and the destroy record's
            `:frame-state-before` each project under the registry they recorded"
    (doseq [[event-id slot] [[::spawn :frame-state-after] [::destroy :frame-state-before]]
            :let [projected (rf/project-egress (record-for event-id) runtime-db-opt)
                  data      (get-in projected (snapshot-data-path slot))]]
      (is (= {:token :rf/redacted :note "visible"} (select-keys data [:token :note]))
          (str slot ": the token redacts and the unclassified sibling rides verbatim"))
      (is (rf.elision/marker? (:blob data)) (str slot ": the large blob is marked"))
      (is (not (leaks? projected secret)) (str slot ": no copy of the secret survives"))))

  (is (= secret (get-in (rf/project-egress (record-for ::spawn)
                                           (assoc runtime-db-opt :rf.egress/include-sensitive? true))
                        (conj (snapshot-data-path :frame-state-after) :token)))
      "the sensitive opt-in still reveals the recorded secret"))

;; ---- app-db: a cleared flow, a declassifying effect -----------------------

(def ^:private flow-secret "secret-historic-flow-output")
(def ^:private auth-secret "secret-historic-auth-token")

(defn- values-at
  "`[slot value]` for every app-db slot of `record` — the two db slots and each
  frame-state's `:rf.db/app` — holding a value at `path`."
  [record path]
  (vec (keep (fn [[slot db]]
               (let [v (get-in db path)]
                 (when (some? v) [slot v])))
             [[:db-before (:db-before record)]
              [:db-after (:db-after record)]
              [:frame-state-before (get-in record [:frame-state-before :rf.db/app])]
              [:frame-state-after (get-in record [:frame-state-after :rf.db/app])]])))

(deftest ^:requires-debug a-cleared-flows-output-keeps-its-recorded-classification
  (rf/reg-event ::seed (fn [{:keys [db]} [_ s]] {:db (assoc db :src s)}))
  (rf/reg-event ::touch (fn [{:keys [db]} _] {:db (update db :touches (fnil inc 0))}))
  (rf/reg-flow ::out {:inputs [[:src]] :output-path [:out] :sensitive [[:token]]}
    (fn [s] {:token (str "secret-historic-flow-" s) :note "visible"}))
  (rf/dispatch-sync [::seed "output"])
  (rf/dispatch-sync [::touch])
  (let [record     (record-for ::touch)
        token-path [:out :token]]
    (is (= [[:db-before flow-secret] [:db-after flow-secret]
            [:frame-state-before flow-secret] [:frame-state-after flow-secret]]
           (values-at record token-path))
        "control: the raw record carries the output in all four app-db slots")
    (rf/clear :flow ::out)
    (is (not (contains? (rf.elision/sensitive-declarations :rf/default) token-path))
        "clearing the flow drops its claim")
    (let [projected (rf/project-egress record)]
      (is (= [[:db-before :rf/redacted] [:db-after :rf/redacted]
              [:frame-state-before :rf/redacted] [:frame-state-after :rf/redacted]]
             (values-at projected token-path))
          "every app-db slot keeps the claim it was recorded under")
      (is (= "visible" (get-in projected [:db-after :out :note]))
          "an unclassified sibling slot rides verbatim")
      (is (not (leaks? projected flow-secret))))))

(deftest ^:requires-debug a-declassified-path-stays-redacted-on-records-made-while-classified
  (rf/reg-event ::sign-in
    (fn [{:keys [db]} _] {:db (assoc db :auth {:token auth-secret}) :sensitive [[:auth :token]]}))
  (rf/reg-event ::declassify (fn [_ _] {:clear-sensitive [[:auth :token]]}))
  (rf/dispatch-sync [::sign-in])
  (rf/dispatch-sync [::declassify])
  (let [token-path [:auth :token]]
    (is (not (contains? (rf.elision/sensitive-declarations :rf/default) token-path))
        "the effect drops its claim")
    (let [projected (rf/project-egress (record-for ::sign-in))]
      (is (= [[:db-after :rf/redacted] [:frame-state-after :rf/redacted]]
             (values-at projected token-path))
          "the record made while the path was classified stays redacted")
      (is (not (leaks? projected auth-secret))))
    (is (= auth-secret (get-in (rf/project-egress (record-for ::declassify))
                               [:db-after :auth :token]))
        "a value recorded after the declassification egresses")))
