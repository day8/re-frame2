(ns re-frame.choice-node-keys-test
  "A `:type :choice` state is held to the state-node key vocabulary (Conventions
  §No silent swallow, Spec 005 §`:type :choice`): an unknown BARE key is refused
  with `:rf.error/machine-unknown-node-key`, in a flat machine and inside a
  parallel region, while `:meta` and a namespaced key pass."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.machines :as rf.machines]))

(defn- refusal
  "The ex-data of the registration refusal for `machine`, or nil when it
  validates."
  [machine]
  (try (rf.machines/validate-machine! machine) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- with-choice-key
  "A flat machine whose initial `:type :choice` state `:pick` declares `k`
  `v`."
  [k v]
  {:initial :pick
   :states  {:pick {:type :choice :choice [{:target :a}] k v}
             :a    {}}})

(defn- region-with-choice-key
  "A parallel machine whose region `:r` starts in a `:type :choice` state
  `:pick` declaring `k` `v`."
  [k v]
  {:type    :parallel
   :regions {:r {:initial :pick
                 :states  {:pick {:type :choice :choice [{:target :a}] k v}
                           :a    {}}}}})

(deftest choice-state-refuses-an-unknown-bare-key
  (doseq [build [with-choice-key region-with-choice-key]]
    (is (= {:rf.error/id :rf.error/machine-unknown-node-key :offending-keys [:bogus]}
           (select-keys (refusal (build :bogus 1)) [:rf.error/id :offending-keys])))))

(deftest well-formed-choice-states-register
  (is (nil? (refusal (with-choice-key :meta {:note "x"}))))
  (is (nil? (refusal (with-choice-key :my.app/note "x"))) "a namespaced key passes")
  (is (nil? (refusal (region-with-choice-key :my.app/note "x")))))
