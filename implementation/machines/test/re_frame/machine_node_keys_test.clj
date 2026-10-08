(ns re-frame.machine-node-keys-test
  "No-silent-swallow coverage for state-node/spawn-spec keys and `:tags`: an
  unknown BARE key on a state node (`:rf.error/machine-unknown-node-key`) or a
  `:spawn` / `:spawn-all` child spec (`:rf.error/machine-unknown-spawn-key`) and
  a malformed `:tags` slot (`:rf.error/machine-bad-tags`) are refused at
  registration; a NAMESPACED user key passes."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Load the machines facade so `rf/reg-machine` routes through its
            ;; late-bind hook (`:machines/reg-machine`).
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.subs]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- reg-error
  "Register `machine` under a fresh id, returning the thrown ex-data (or nil
  when registration succeeds)."
  [machine]
  (try (rf/reg-machine (keyword "nk" (str (gensym))) machine) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest unknown-bare-node-key-rejected
  (testing "on a state node (XState's :invoke for :spawn), naming the state, the key and the vocabulary"
    (let [d (reg-error {:initial :idle
                        :states  {:idle {:invoke :x :on {:go :done}}
                                  :done {}}})]
      (is (= {:rf.error/id :rf.error/machine-unknown-node-key :state :idle :offending-keys [:invoke]}
             (select-keys d [:rf.error/id :state :offending-keys])))
      (is (contains? (:valid-keys d) :spawn))))
  (testing "on the machine root"
    (is (= :rf.error/machine-unknown-node-key
           (:rf.error/id (reg-error {:innitial :idle :initial :idle :states {:idle {}}}))))))

(deftest unknown-bare-spawn-key-rejected
  (doseq [node [{:spawn {:machine-id :child :system-id :some-name}}
                {:spawn-all {:children        [{:id :c1 :machine-id :child :bogus true}]
                             :on-all-complete [:all]}}]]
    (is (= :rf.error/machine-unknown-spawn-key
           (:rf.error/id (reg-error {:initial :idle
                                     :states  {:idle (assoc node :on {:go :done})
                                               :done {}}}))))))

(deftest namespaced-spawn-key-passes
  (is (nil? (reg-error {:initial :idle
                        :states  {:idle {:spawn {:machine-id :child :my.app/tag :x}
                                         :on    {:go :done}}
                                  :done {}}}))))

(deftest non-set-tags-rejected
  (let [tags-error (fn [tags] (reg-error {:initial :idle
                                          :states  {:idle {:tags tags :on {:go :done}}
                                                    :done {}}}))]
    (is (= {:rf.error/id :rf.error/machine-bad-tags :state :idle :tags [:busy]}
           (select-keys (tags-error [:busy]) [:rf.error/id :state :tags])))
    (doseq [tags [:busy #{:busy "idle"}]]
      (is (= :rf.error/machine-bad-tags (:rf.error/id (tags-error tags))) (pr-str tags)))))
