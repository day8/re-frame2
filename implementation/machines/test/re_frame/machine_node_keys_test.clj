(ns re-frame.machine-node-keys-test
  "No-silent-swallow coverage for state-node/spawn-spec keys and `:tags`.

  This suite pins the fail-loud behaviour:
    - an unknown BARE key on a state node → `:rf.error/machine-unknown-node-key`;
    - an unknown BARE key on a `:spawn` / `:spawn-all` child spec →
      `:rf.error/machine-unknown-spawn-key`;
    - a non-set `:tags` slot → `:rf.error/machine-bad-tags`;
    - a NAMESPACED user key passes (the open extension carve-out);
    - `:type :choice` and `:type :history` nodes are not double-rejected."
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

(def ^:private snapshot rf.machines.test-support/snapshot)

(defn- reg-error-id
  "Register `machine` under a fresh id, returning the thrown
  `:rf.error/id` (or nil when registration succeeds)."
  [machine]
  (try (rf/reg-machine (keyword "nk" (str (gensym))) machine) nil
       (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))

;; ---- (1) unknown BARE state-node keys signal ------------------------------

(deftest unknown-bare-node-key-rejected
  (doseq [[label machine]
          [[":invoke (XState's spelling of :spawn) on a state node — a footgun that
            would otherwise register silently"
            {:initial :idle
             :states {:idle {:invoke {:machine-id :child}
                             :on {:go :done}}
                      :done {}}}]
           [":on-entry (XState's spelling of :entry) on a state node"
            {:initial :idle
             :states {:idle {:on-entry :log
                             :on {:go :done}}
                      :done {}}}]
           ["a typo like :innitial on the MACHINE ROOT"
            {:innitial :idle
             :initial :idle
             :states {:idle {}}}]
           [":on-spawn-actions on the root — there is no such slot (the reducer binds
            the spawned id under [:data :rf/spawned <invoke-id>]), so it meets the
            closed-vocabulary diagnostic with no bespoke nag"
            {:initial :idle
             :on-spawn-actions {:record (fn [_] nil)}
             :states {:idle {:on {:go :done}}
                      :done {}}}]]]
    (is (= :rf.error/machine-unknown-node-key (reg-error-id machine))
        (str label " is rejected"))))

(deftest unknown-bare-node-key-name-and-vocab-in-ex-data
  (testing "the ex-data names the offending key + the valid vocabulary — the
            diagnostic an author reads to fix the typo"
    (let [e (try (rf/reg-machine :nk/diag
                   {:initial :idle
                    :states {:idle {:invoke :x :on {:go :done}}
                             :done {}}})
                 nil
                 (catch clojure.lang.ExceptionInfo ex ex))
          d (ex-data e)]
      (is (= :rf.error/machine-unknown-node-key (:rf.error/id d)))
      (is (= [:invoke] (:offending-keys d)) "names the offending bare key")
      (is (contains? (:valid-keys d) :spawn)
          "surfaces the valid vocabulary (which includes the intended :spawn)")
      (is (= :idle (:state d)) "names the declaring state"))))

;; ---- (2) unknown BARE spawn-spec keys signal ------------------------------
;;
;; There is no `:on-spawn` and no `:system-id` spawn-spec key: each is an
;; unknown BARE key the closed-vocabulary diagnostic catches, with no
;; diagnostic id of its own. The address is the id, and `:fixed-actor-id` is
;; the one stable-name mechanism.

(defn- spawn-with [spawn-key value]
  {:initial :idle
   :states {:idle {:spawn {:machine-id :child spawn-key value}
                   :on {:go :done}}
            :done {}}})

(defn- spawn-all-child-with [child-key value]
  {:initial :idle
   :states {:idle {:spawn-all {:children [{:id :c1 :machine-id :child child-key value}]
                               :on-all-complete [:all]}
                   :on {:go :done}}
            :done {}}})

(deftest unknown-bare-spawn-key-rejected
  (doseq [[label machine]
          [[":machine (for :machine-id) on a :spawn, which would leave it under-specified"
            {:initial :idle
             :states {:idle {:spawn {:machine :child}
                             :on {:go :done}}
                      :done {}}}]
           [":bogus on a :spawn-all child" (spawn-all-child-with :bogus true)]
           [":on-spawn on a single :spawn" (spawn-with :on-spawn (fn [_] nil))]
           [":on-spawn on a :spawn-all child" (spawn-all-child-with :on-spawn (fn [_] nil))]
           [":system-id on a single :spawn" (spawn-with :system-id :some-name)]
           [":system-id on a :spawn-all child" (spawn-all-child-with :system-id :some-name)]]]
    (is (= :rf.error/machine-unknown-spawn-key (reg-error-id machine))
        (str label " is rejected"))))

(deftest namespaced-spawn-key-passes
  (testing "a NAMESPACED key on a :spawn spec passes (the runtime itself stamps
            :rf/parent-id / :rf/invoke-id — namespaced, always allowed)"
    (is (nil? (reg-error-id {:initial :idle
                             :states {:idle {:spawn {:machine-id :child
                                                     :my.app/tag :x}
                                             :on {:go :done}}
                                      :done {}}})))))

;; ---- (3) malformed :tags shape signals ------------------------------------

(deftest non-set-tags-rejected
  (doseq [[label tags] [["a SINGLE-KEYWORD :tags (not coerced)"             :busy]
                        ["a SET with a non-keyword member (strict [:set :keyword])" #{:busy "idle"}]]]
    (testing label
      (is (= :rf.error/machine-bad-tags
             (reg-error-id {:initial :idle
                            :states {:idle {:tags tags :on {:go :done}}
                                     :done {}}}))))))

(deftest bad-tags-names-offender-in-ex-data
  (testing "the :rf.error/machine-bad-tags ex-data names the state + offending value"
    (let [e (try (rf/reg-machine :nk/bad-tags
                   {:initial :idle
                    :states {:idle {:tags [:busy] :on {:go :done}}
                             :done {}}})
                 nil
                 (catch clojure.lang.ExceptionInfo ex ex))
          d (ex-data e)]
      (is (= :rf.error/machine-bad-tags (:rf.error/id d)))
      (is (= :idle (:state d)))
      (is (= [:busy] (:tags d)) "names the offending non-set value"))))

;; ---- (4) choice and history nodes are not double-rejected ----------------

(deftest valid-choice-and-history-nodes-not-double-rejected
  (testing "a :type :choice node and a :type :history node — which carry keys
            OUTSIDE the ordinary vocabulary but are validated by their OWN
            closed-key-set validators — are NOT rejected by the node-key walk"
    ;; :type :choice carries :choice (not an ordinary-node key) — must pass here.
    (is (nil? (reg-error-id
                {:initial :route
                 :states {:route {:type :choice
                                  :choice [{:guard (constantly true) :target :a}
                                           {:target :b}]}
                          :a {} :b {}}})))
    ;; :type :history carries :deep? / :default-target — must pass here.
    (is (nil? (reg-error-id
                {:initial :outer
                 :states {:outer {:initial :one
                                  :states {:one {:on {:go :two}}
                                           :two {}
                                           :hist {:type :history :deep? true
                                                  :default-target :one}}}}})))))
