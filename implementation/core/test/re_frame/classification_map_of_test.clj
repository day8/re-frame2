(ns re-frame.classification-map-of-test
  "A `:sensitive?` mark under a `:map-of` value redacts under every key.

  The schemas walker writes a mark inside a `:map-of` value without the key
  (`[:accounts :token]`), just as it writes one inside a `:vector` element
  without the index. An index-free `redact-with-paths` reads both as
  collection coordinates, so each caller that hands it walker marks or
  projection-relative declarations — a managed-HTTP decoded body, resource
  params and replies, a machine snapshot's hydration `:data` — redacts the
  slot under every key, as the durable walker the trace path uses does."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.classification :as rf.classification]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.http.privacy-body :as rf.http.privacy-body]
            ;; Loading machines publishes :machines/project-ssr-runtime-db.
            [re-frame.machines]
            ;; Loading schemas publishes the walker hooks `:decode` reads.
            [re-frame.schemas]
            [re-frame.schemas.walker :as rf.schemas.walker]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private accounts-decode
  "A managed-HTTP `:decode` schema: an account map keyed by id."
  [:map [:accounts [:map-of :string [:map [:id :int] [:token {:sensitive? true} :string]]]]])

(deftest a-sensitive-mark-under-a-map-of-value-redacts-under-every-key
  (is (= [[:accounts :token]]
         (keys (rf.schemas.walker/extract-sensitive-paths-from-schema accounts-decode [])))
      "the walker writes the mark without the map-of key")
  (is (= :classify (rf.http.privacy-body/off-box-body-disposition accounts-decode))
      "the body rides off-box classified, so the per-slot redaction is all that guards it")
  (is (= {:accounts {"a" {:id 1 :token :rf/redacted}
                     "b" {:id 2 :token :rf/redacted}}}
         (rf.http.privacy-body/classify-decoded {:accounts {"a" {:id 1 :token "SECRET-A"}
                                                            "b" {:id 2 :token "SECRET-B"}}}
                                                accounts-decode))))

(deftest a-set-element-mark-still-redacts
  (let [schema [:map [:accounts [:set [:map [:id :int] [:token {:sensitive? true} :string]]]]]]
    (is (= {:accounts #{{:id 1 :token :rf/redacted}}}
           (rf.http.privacy-body/classify-decoded {:accounts #{{:id 1 :token "SECRET-SET"}}} schema)))))

(deftest the-key-skip-stays-inside-index-free-declared-subtrees
  (let [v {:accounts {"a" {:id 1 :token "SECRET-A"}}}]
    (testing "exact mode matches the declared path only"
      (is (= v (rf.classification/redact-with-paths v [[:accounts :token]] []))))
    (testing "a key at the root is a named slot, so a declaration never floats past it"
      (let [deeper {:tags {:x {:auth {:password "deeper-not-declared"}}}}]
        (is (= deeper (rf.classification/redact-with-paths
                        deeper [[:auth :password]] [] {:index-free? true})))))
    (testing "a sibling slot under the skipped key rides verbatim"
      (is (= {:accounts {"a" {:id 1 :token :rf/redacted}}}
             (rf.classification/redact-with-paths v [[:accounts :token]] [] {:index-free? true}))))))

(deftest a-large-declaration-under-a-map-of-value-elides-under-every-key
  (let [projected (rf.classification/redact-with-paths
                    {:by-id {"a" {:avatar "BIGBLOB-A" :name "n"}
                             "b" {:avatar "BIGBLOB-B" :name "m"}}}
                    [] [[:by-id :avatar]] {:index-free? true})]
    (doseq [k ["a" "b"]]
      (let [marker (get-in projected [:by-id k :avatar])]
        (is (rf.elision/marker? marker))
        (is (= [:by-id k :avatar] (get-in marker [:rf.size/large-elided :path])))))
    (is (= ["n" "m"] [(get-in projected [:by-id "a" :name])
                      (get-in projected [:by-id "b" :name])]))))

(deftest a-same-named-slot-below-a-matched-segment-also-matches
  ;; A value cannot tell a `:map-of` key from a named slot, so once
  ;; `[:user :email]`'s first segment has matched, the `:email` under the
  ;; nested `:manager` map matches too, as it does on the durable walker. This
  ;; is the conservative reading: it can only over-redact.
  (let [v {:user {:email "me@x" :manager {:email "boss@x" :name "B"}}}]
    (is (= {:user {:email :rf/redacted :manager {:email :rf/redacted :name "B"}}}
           (rf.classification/redact-with-paths v [[:user :email]] [] {:index-free? true})))
    (testing "exact mode still matches the declared path only"
      (is (= {:user {:email :rf/redacted :manager {:email "boss@x" :name "B"}}}
             (rf.classification/redact-with-paths v [[:user :email]] []))))))

(deftest a-keyed-machine-data-declaration-redacts-in-hydration-as-the-trace-does
  ;; A spawned actor's projection-relative `[:data :by-id :token]` names the
  ;; field under every key of the `:by-id` map; the hydration projection and
  ;; the durable walker the trace path uses read it the same way.
  (rf/reg-machine ::kid
    {:initial   :wait
     :data      {:by-id {"a" {:id 1 :token "SECRET-BY-ID-A"}
                         "b" {:id 2 :token "SECRET-BY-ID-B"}}}
     :sensitive [[:data :by-id :token]]
     :states    {:wait {}}})
  (rf/reg-machine ::parent
    {:initial :working
     :data    {}
     :states  {:working {:spawn {:machine-id ::kid}}}})
  (rf/dispatch-sync [::parent [:rf.machine/start]])
  (let [runtime-db        (:rf.db/runtime (rf/frame-state-value :rf/default))
        [kid-id snapshot] (->> (get-in runtime-db [:rf.runtime/machines :snapshots])
                               (filter #(= ::kid (:rf/machine-type (val %))))
                               first)
        projected         (get-in (rf.ssr.payload-policy/project-runtime-db runtime-db :rf/default)
                                  [:rf.runtime/machines :snapshots kid-id :data])]
    (is (some? kid-id) "the parent spawned the kid")
    (is (= {:by-id {"a" {:id 1 :token :rf/redacted}
                    "b" {:id 2 :token :rf/redacted}}}
           (select-keys projected [:by-id])))
    (is (= (rf.elision/elide-wire-value
             (:data snapshot)
             {:frame                    :rf/default
              :path                     [:rf.runtime/machines :snapshots kid-id :data]
              :rf.egress/include-large? true})
           projected)
        "the hydration projection equals the durable walker's")))
