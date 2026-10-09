(ns re-frame.schemas-reg-side-effects-test
  "JVM tests for the dev-only `:rf.schema/violation` trace (Spec 010 §Schema
  migration on hot-reload): when a re-registration CHANGES the schema at a
  `(frame, path)` and the live app-db value there fails the NEW schema,
  `reg-app-schema` emits a `:warning` and the app keeps running."
  (:require [clojure.string :as str]
            [clojure.test :refer [are deftest is testing use-fixtures]]
            [malli.core :as m]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.schemas.test-fixture :as rf.schemas.test-fixture]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rf.schemas.test-fixture/reset-runtime)

(defn- set-app-db!
  ([db] (set-app-db! :rf/default db))
  ([frame-id db]
   (rf.frame/swap-frame-db! frame-id (constantly db))))

(defn- violations
  "The `:rf.schema/violation` traces `body-fn` emits."
  [body-fn]
  (with-trace-recorder! [traces]
    (body-fn)
    (filterv #(= :rf.schema/violation (:operation %)) @traces)))

(defn- violations-on-re-registration
  "Register `prior` at `[k]` unless it is `::absent`, set the live app-db to
  `{k live}`, then return the violations registering `new` at `[k]` emits."
  [k prior live new]
  (when-not (= ::absent prior)
    (rf/reg-app-schema [k] prior))
  (set-app-db! {k live})
  (violations #(rf/reg-app-schema [k] new)))

(defn- spec-tags [violation]
  (select-keys (:tags violation)
               [:path :pre-reload-schema :post-reload-schema :mismatching-value :frame]))

(deftest violation-fires-when-rereg-changes-schema-and-live-value-fails
  (testing "one :warning, :logged-and-skipped, carrying the five spec'd tags
            read from the REGISTRATION frame's live app-db"
    (rf/make-frame {:id :tenant/a})
    (rf/reg-app-schema [:count] {:frame :tenant/a} :int)
    (set-app-db! :tenant/a {:count "bad"})
    ;; The new schema accepts :rf/default's value, so a wrong-frame read reports nothing.
    (set-app-db! :rf/default {:count 5})
    (is (= [[:warning :logged-and-skipped
             {:path               [:count]
              :pre-reload-schema  :int
              :post-reload-schema [:int {:min 0}]
              :mismatching-value  "bad"
              :frame              :tenant/a}]]
           (map (juxt :op-type :recovery spec-tags)
                (violations #(rf/reg-app-schema [:count] {:frame :tenant/a}
                                                [:int {:min 0}])))))))

(deftest violation-suppressed-unless-the-schema-changes-under-a-failing-value
  (testing "each row breaks exactly one firing condition, so none emits:
            the schema is unchanged, there is no prior registration to
            compare against, or the live value satisfies the NEW schema"
    (are [k prior live new] (empty? (violations-on-re-registration k prior live new))
      :unchanged   :int     "not-an-int" :int
      :first       ::absent "not-an-int" :int
      :still-valid :int     5            [:int {:min 0}])))

(deftest violation-fires-when-the-prior-registered-schema-token-is-nil
  (testing "a registered nil token is a real prior declaration — presence of
            the registry entry, not truthiness of its token — so replacing it
            is a schema change"
    (is (= [{:path               [:count]
             :pre-reload-schema  nil
             :post-reload-schema :int
             :mismatching-value  :bad
             :frame              :rf/default}]
           (map spec-tags (violations-on-re-registration :count nil :bad :int))))))

(deftest violation-redacts-mismatching-value-when-new-schema-sensitive
  (testing "a sensitive new schema redacts :mismatching-value; an opaque
            schema the walker cannot see into, at the root or nested, fails
            closed the same way"
    (doseq [[label live new-schema]
            [["vector-form :sensitive? slot"
              "SECRET-vector" [:string {:sensitive? true :min 32}]]
             ["opaque root"
              "SECRET-opaque" (m/schema [:int {:sensitive? true}])]
             ["opaque nested child"
              {:secret "SECRET-nested"}
              [:map [:secret (m/schema [:string {:sensitive? true :min 999}])]]]]]
      (let [[v] (violations-on-re-registration :token :int live new-schema)]
        (is (= [:rf/redacted true] [(-> v :tags :mismatching-value) (:sensitive? v)])
            label)
        (is (not (str/includes? (pr-str v) "SECRET"))
            label)))))
