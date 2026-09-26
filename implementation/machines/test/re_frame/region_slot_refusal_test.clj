(ns re-frame.region-slot-refusal-test
  "Registration refuses every parallel REGION-BODY key that no runtime
  consumer reads there, with `:rf.error/machine-root-slot-not-supported`
  naming the region under `:path`, per Spec 005 §State nodes (the machine
  root) and Conventions §No silent swallow.

  Refused: `:spawn`, `:spawn-all`, `:always`, `:final?`, `:output-key`,
  `:error?`, `:deep?`, `:default-target`, `:regions`.

  Controls: a region body's `:choice` keeps
  `:rf.error/machine-choice-without-type`, and a region-body typo keeps
  `:rf.error/machine-unknown-node-key`. The honoured region `:entry` /
  `:exit` / `:tags` register throughout `region_lifecycle_test.clj`; a
  region body's `:after` / `:timeout` refusal is pinned in
  `root_after_non_parallel_test.clj`, and the nested `:type :parallel`
  refusal in `parallel_test.clj`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
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

;; ---- controls ----------------------------------------------------------------

(deftest other-region-shapes-keep-their-own-refusal
  (is (= :rf.error/machine-choice-without-type
         (:rf.error/id (refusal (with-region-key :choice [{:target :x1}])))))
  (is (= :rf.error/machine-unknown-node-key
         (:rf.error/id (refusal (with-region-key :invoke {:machine-id :rs/worker}))))))
