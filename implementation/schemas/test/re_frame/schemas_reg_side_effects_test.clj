(ns re-frame.schemas-reg-side-effects-test
  "JVM tests for the dev-only `:rf.schema/violation` trace (Spec 010 §Schema
  migration on hot-reload): when a first registration, or a re-registration
  that CHANGES the schema at a path, meets a live app-db value present there
  that fails it, in any live frame that resolves the registration,
  `reg-app-schema` emits a `:warning` for that frame and the app keeps
  running."
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

(deftest violation-fires-in-each-resolving-frame-whose-live-value-fails
  (testing "one :warning, :logged-and-skipped, carrying the five spec'd tags,
            for each live frame that resolves the re-registered path and whose
            own live app-db fails it"
    (rf/reg-event :probe/noop {:ns 'probe.elsewhere} (fn [_ _] {}))
    (rf/make-frame {:id :tenant/a})
    (rf/make-frame {:id :tenant/b})
    (rf/make-frame {:id     :tenant/elsewhere
                    :images [(rf/image {:id        :test/elsewhere
                                        :select-ns {:include ["probe.elsewhere"]}})]})
    (rf/reg-app-schema [:count] :int)
    (set-app-db! :tenant/a {:count "bad"})
    (set-app-db! :tenant/b {:count -1})
    ;; The new schema accepts :rf/default's value, and :tenant/elsewhere
    ;; resolves no schema at the path, so neither reports anything.
    (set-app-db! :rf/default {:count 5})
    (set-app-db! :tenant/elsewhere {:count "bad"})
    (is (= #{[:warning :logged-and-skipped
              {:path               [:count]
               :pre-reload-schema  :int
               :post-reload-schema [:int {:min 0}]
               :mismatching-value  "bad"
               :frame              :tenant/a}]
             [:warning :logged-and-skipped
              {:path               [:count]
               :pre-reload-schema  :int
               :post-reload-schema [:int {:min 0}]
               :mismatching-value  -1
               :frame              :tenant/b}]}
           (set (map (juxt :op-type :recovery spec-tags)
                     (violations #(rf/reg-app-schema [:count] [:int {:min 0}]))))))))

(deftest violation-suppressed-unless-the-schema-changes-under-a-failing-value
  (testing "each row breaks exactly one firing condition, so none emits:
            the schema is unchanged, or the live value satisfies the NEW
            schema"
    (are [k prior live new] (empty? (violations-on-re-registration k prior live new))
      :unchanged   :int     "not-an-int" :int
      :still-valid :int     5            [:int {:min 0}])))

(deftest a-first-registration-checks-a-present-live-value
  (testing "over a present non-conforming value: one :warning,
            :logged-and-skipped, with no :pre-reload-schema because there is
            no prior declaration"
    (is (= [[:warning :logged-and-skipped
             {:path               [:count]
              :post-reload-schema :int
              :mismatching-value  "not-an-int"
              :frame              :rf/default}
             false]]
           (map (juxt :op-type :recovery spec-tags
                      #(contains? (:tags %) :pre-reload-schema))
                (violations-on-re-registration :count ::absent "not-an-int" :int)))))
  (testing "a present nil is a value, so it is checked too"
    (is (= [nil] (map (comp :mismatching-value :tags)
                      (violations-on-re-registration :nil-count ::absent nil :int)))))
  (testing "an absent path emits nothing"
    (set-app-db! {:other 1})
    (is (empty? (violations #(rf/reg-app-schema [:missing] :int))))))

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

(deftest a-stale-slice-blocks-only-the-events-that-write-it
  (testing "after a re-registration the live [:user] fails, an event that
            leaves [:user] as installed commits and one that writes an
            invalid [:user] is rejected and named; a root schema sees every
            write"
    (rf/reg-event :u/seed (fn [{:keys [db]} _] {:db (assoc db :user {:name "a"})}))
    (rf/reg-event :u/bad  (fn [{:keys [db]} _] {:db (assoc db :user {:name "b"})}))
    (rf/reg-event :n/inc  (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (rf/reg-app-schema [:user] [:maybe [:map [:name :string]]])
    (rf/dispatch-sync [:u/seed])
    (let [reported (fn [body-fn]
                     (with-trace-recorder! [traces]
                       (body-fn)
                       (into [] (keep #(case (:operation %)
                                         :rf.schema/violation
                                         [:violation (-> % :tags :path)]
                                         :rf.error/schema-validation-failure
                                         [:rejected (-> % :tags :failing-id)]
                                         nil))
                             @traces)))
          user-with-email [:maybe [:map [:name :string] [:email :string]]]]
      (is (= [[[:violation [:user]] [:rejected :u/bad]]
              {:user {:name "a"} :n 2}]
             [(reported #(do (rf/reg-app-schema [:user] user-with-email)
                             (rf/dispatch-sync [:n/inc])
                             (rf/dispatch-sync [:n/inc])
                             (rf/dispatch-sync [:u/bad])))
              (rf/app-db-value :rf/default)]))
      (rf/reg-app-schema [] [:map [:user user-with-email]])
      (is (= [[:rejected :n/inc]] (reported #(rf/dispatch-sync [:n/inc])))))))
