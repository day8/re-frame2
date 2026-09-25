(ns re-frame.region-slot-refusal-test
  "Registration refuses every parallel REGION-BODY key that no runtime
  consumer reads there, with `:rf.error/machine-root-slot-not-supported`
  naming the region under `:path`, per Spec 005 §State nodes (the machine
  root) and Conventions §No silent swallow.

  Refused: `:spawn`, `:spawn-all`, `:always`, `:final?`, `:output-key`,
  `:error?`, `:deep?`, `:default-target`, `:regions`.

  Controls: the honoured region `:entry` / `:exit` / `:tags` register; a
  region body's `:after` / `:timeout` keep
  `:rf.error/machine-non-parallel-root-after-not-supported`, its `:choice`
  keeps `:rf.error/machine-choice-without-type`, and a nested `:type
  :parallel` keeps `:rf.error/machine-parallel-nested-not-supported`; a
  region-body typo keeps `:rf.error/machine-unknown-node-key`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- refusal
  "The ex-data of the registration refusal for `machine`, or nil when it
  validates."
  [machine]
  (try (rf.machines/validate-machine! machine) nil
       (catch clojure.lang.ExceptionInfo e
         (assoc (ex-data e) ::message (ex-message e)))))

(defn- with-region-key
  "A two-region parallel machine whose region `:x` body declares `k` `v`."
  [k v]
  {:type    :parallel
   :regions {:x {:initial :x1 k v :states {:x1 {} :x2 {}}}
             :y {:initial :y1 :states {:y1 {}}}}})

(def ^:private refused-on-a-region
  "One well-formed value per key refused on a region body."
  {:spawn          {:machine-id :rs/worker}
   :spawn-all      {:children        [{:id :one :machine-id :rs/worker}]
                    :join            :all
                    :on-all-complete [:done]}
   :always         {:action (fn [_] nil)}
   :final?         true
   :output-key     :result
   :error?         true
   :deep?          true
   :default-target :x1
   :regions        {:inner {:initial :z :states {:z {}}}}})

;; ---- refused keys ----------------------------------------------------------

(deftest region-body-refuses-each-unread-key
  (doseq [[k v] refused-on-a-region]
    (testing (str "region body " k)
      (let [d (refusal (with-region-key k v))]
        (is (= :rf.error/machine-root-slot-not-supported (:rf.error/id d)))
        (is (= [k] (:offending-keys d)) "ex-data names the offending key")
        (is (= [:regions :x] (:path d)) "ex-data names the region")))))

(deftest refusal-names-every-offending-key
  (let [d (refusal (-> (with-region-key :final? true)
                       (assoc-in [:regions :x :spawn] {:machine-id :rs/worker})))]
    (is (= [:final? :spawn] (:offending-keys d)))))

(deftest refusal-message-names-the-region-and-the-substitute
  (let [msg (::message (refusal (with-region-key :spawn {:machine-id :rs/worker})))]
    (is (str/includes? msg "region :x"))
    (is (str/includes? msg "compound"))))

(deftest reg-machine-throws-the-refusal
  (let [e (try (rf/reg-machine :rs/live (with-region-key :always {:action (fn [_] nil)})) nil
               (catch clojure.lang.ExceptionInfo ex ex))]
    (is (= :rf.error/machine-root-slot-not-supported (:rf.error/id (ex-data e))))))

;; ---- controls ----------------------------------------------------------------

(deftest honoured-region-slots-register
  (is (nil? (refusal (-> (with-region-key :entry (fn [_] nil))
                         (assoc-in [:regions :x :exit] (fn [_] nil))
                         (assoc-in [:regions :x :tags] #{:in-x}))))))

(deftest other-region-shapes-keep-their-own-refusal
  (is (= :rf.error/machine-non-parallel-root-after-not-supported
         (:rf.error/id (refusal (with-region-key :after {1000 :x2})))))
  (is (= :rf.error/machine-non-parallel-root-after-not-supported
         (:rf.error/id (refusal (-> (with-region-key :timeout 1000)
                                    (assoc-in [:regions :x :on-timeout] :x2))))))
  (is (= :rf.error/machine-choice-without-type
         (:rf.error/id (refusal (with-region-key :choice [{:target :x1}])))))
  (is (= :rf.error/machine-parallel-nested-not-supported
         (:rf.error/id (refusal {:type    :parallel
                                 :regions {:outer {:type    :parallel
                                                   :regions {:inner {:initial :s :states {:s {}}}}}}}))))
  (is (= :rf.error/machine-unknown-node-key
         (:rf.error/id (refusal (with-region-key :invoke {:machine-id :rs/worker}))))))
