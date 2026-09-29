(ns re-frame.epoch-egress-historic-classification-test
  "An epoch record's runtime-db egresses under the classification that applied
  when it was recorded, not only under the frame's registry as it stands now.

  Destroying a machine actor drops the `:sensitive` / `:large` claims lowered
  for its snapshot from the frame's elision registry, while the ring still holds
  frame-states carrying that snapshot: the destroy record's
  `:frame-state-before`, and every older record projected clean while the actor
  lived. Under the trusted-local `:rf.egress/include-runtime-db? true` opt-in
  those partitions are walked, so each must be classified by the registry it
  carries. The default profile redacts the partition whole and is the control.

  The deftest is `^:requires-debug`: it projects recorded epochs, and under
  `-Dre-frame.debug=false` the ring records none."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch]
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
