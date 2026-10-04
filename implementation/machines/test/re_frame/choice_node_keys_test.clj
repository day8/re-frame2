(ns re-frame.choice-node-keys-test
  "A `:type :choice` state is held to the state-node key vocabulary, per
  Conventions §No silent swallow and Spec 005 §`:type :choice`.

  - An unknown BARE key on a choice state is refused with
    `:rf.error/machine-unknown-node-key`; a namespaced key passes.
  - A choice state is a leaf, so an `:on-done` on it is refused as any leaf's
    is (pinned in `machine_registration_refusals_test`), and a waiting-state
    key keeps `:rf.error/machine-choice-extra-keys` (pinned in `choice_test`).

  Controls: a well-formed choice state registers, with `:meta` and a
  namespaced extension; the same checks hold for a choice state inside a
  parallel region."
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
    (let [d (refusal (build :bogus 1))]
      (is (= :rf.error/machine-unknown-node-key (:rf.error/id d)))
      (is (= [:bogus] (:offending-keys d))))))

;; ---- controls ----------------------------------------------------------------

(deftest well-formed-choice-states-register
  (is (nil? (refusal (with-choice-key :meta {:note "x"}))))
  (is (nil? (refusal (with-choice-key :my.app/note "x"))) "a namespaced key passes")
  (is (nil? (refusal (region-with-choice-key :my.app/note "x")))))
