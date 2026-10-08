(ns re-frame.machine-registration-refusals-test
  "Registration refuses machine shapes that would otherwise register cleanly
  and then fail late, generically, or never:

    - an `:initial` naming no declared child (root, compound, region) —
      `:rf.error/machine-unresolved-target` with `:slot :initial`; and the pure
      `machine-transition` refuses a snapshot `:state` naming no declared state
      (a parallel region's value included) with
      `:rf.error/machine-state-not-in-definition`;
    - a `:guard` / `:entry` / `:exit` / `:action` that is neither ONE fn nor
      ONE keyword — the engine's own `:rf.error/machine-bad-guard-form` /
      `:rf.error/machine-bad-action-form`;
    - a vector `:spawn` (`:rf.error/machine-spawn-bad-shape`), a wildcard
      `:internal-events` member (`:rf.error/machine-bad-internal-events`), a
      reserved-namespace tag (`:rf.error/machine-bad-tags`) and `:on-done` on a
      leaf (`:rf.error/machine-unknown-node-key`);
    - an `:always` entry with neither `:guard` nor `:target`
      (`:rf.error/machine-always-unguarded-targetless`).

  Each refusal sits beside a control that registers."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.subs]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- reg-error
  "Register `machine` under a fresh id. Returns the thrown ex-data, or nil when
  registration succeeds."
  [machine]
  (try (rf/reg-machine (keyword "refusal" (str (gensym))) machine) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- reg-error-id [machine] (:rf.error/id (reg-error machine)))

;; ---- an :initial must name a declared child --------------------------------

(deftest undeclared-initial-is-refused
  (testing "a root :initial naming no state in :states is refused"
    (is (= {:rf.error/id :rf.error/machine-unresolved-target :slot :initial :target :zzz :state :rf/root}
           (select-keys (reg-error {:initial :zzz :states {:a {:on {:go :a}}}})
                        [:rf.error/id :slot :target :state]))))
  (testing "a compound's :initial naming no child is refused"
    (let [d (reg-error {:initial :p
                        :states  {:p {:initial :zzz :states {:x {}}}}})]
      (is (= [:rf.error/machine-unresolved-target :p :initial :zzz]
             [(:rf.error/id d) (:state d) (:slot d) (:target d)]))))
  (testing "a parallel region's :initial naming no state in its :states is refused"
    (let [d (reg-error {:type    :parallel
                        :regions {:r1 {:initial :zzz :states {:a {}}}
                                  :r2 {:initial :p :states {:p {}}}}})]
      (is (= [:rf.error/machine-unresolved-target :r1 :initial :zzz]
             [(:rf.error/id d) (:region d) (:slot d) (:target d)])))))

(deftest pure-transition-refuses-an-undeclared-snapshot-state
  (let [definition {:id      :refusal/pure
                    :initial :a
                    :states  {:a {:on {:go :b}} :b {}}}
        refusal    (fn [state]
                     (try (rf.machines/machine-transition definition {:state state :data {}} [:go])
                          nil
                          (catch clojure.lang.ExceptionInfo ex (ex-data ex))))]
    (is (= {:rf.error/id :rf.error/machine-state-not-in-definition :state :zzz}
           (select-keys (refusal :zzz) [:rf.error/id :state]))
        "a snapshot :state naming no declared state throws rather than no-op")
    (is (= :rf.error/machine-state-not-in-definition (:rf.error/id (refusal [:a :zzz])))
        "a compound path naming no declared node throws the same id")
    (is (= :rf.error/machine-bad-state-form (:rf.error/id (refusal "a")))
        "a malformed :state reports its shape, not its membership")))

(deftest pure-transition-refuses-an-undeclared-region-state
  (let [definition {:id      :refusal/pure-parallel
                    :type    :parallel
                    :regions {:r1 {:initial :a
                                   :states  {:a {:on {:go :b}} :b {}}}
                              :r2 {:initial :p
                                   :states  {:p {:initial :p1
                                                 :states  {:p1 {} :p2 {}}}
                                             :q {}}}}}
        refusal    (fn [state]
                     (try (rf.machines/machine-transition definition {:state state :data {}} [:go])
                          nil
                          (catch clojure.lang.ExceptionInfo ex (ex-data ex))))]
    (doseq [[state region bad] [[{:r1 :zzz :r2 :q} :r1 :zzz]
                                [{:r1 :a :r2 [:p :zzz]} :r2 [:p :zzz]]]]
      (let [d (refusal state)]
        (is (= [:rf.error/machine-state-not-in-definition region bad]
               [(:rf.error/id d) (:region d) (:state d)]))))))

;; ---- :guard / action slots hold one fn or one keyword ------------------------

(deftest guard-and-action-forms-are-refused-at-registration
  (let [f        (fn [_] nil)
        g        (fn [_] true)
        base     {:guards  {:g g :h g}
                  :actions {:a1 f :a2 f}}
        machine  (fn [a-state] (merge base {:initial :a :states {:a a-state :b {}}}))]
    (testing "an :entry / :exit / transition :action vector is refused"
      (is (= {:rf.error/id :rf.error/machine-bad-action-form :slot :entry}
             (select-keys (reg-error (machine {:entry [f f]})) [:rf.error/id :slot])))
      (is (= :rf.error/machine-bad-action-form (reg-error-id (machine {:exit [f] :on {:go :b}}))))
      (is (= :rf.error/machine-bad-action-form
             (reg-error-id (machine {:on {:go {:target :b :action [f f]}}})))))
    (testing "the XState {:type …} action object is refused"
      (is (= :rf.error/machine-bad-action-form (reg-error-id (machine {:entry {:type :a1}})))))
    (testing "a non-keyword, non-fn :guard is refused, naming the guard"
      (is (= {:rf.error/id :rf.error/machine-bad-guard-form :guard {:and [:g :h]}}
             (select-keys (reg-error (machine {:on {:go {:target :b :guard {:and [:g :h]}}}}))
                          [:rf.error/id :guard]))))
    (testing "an :always candidate's guard / action forms are checked too"
      (is (= :rf.error/machine-bad-action-form
             (reg-error-id (machine {:always [{:guard :g :target :b :action [f]}]})))))))

;; ---- :spawn, :internal-events, :tags and :on-done shapes -----------------------

(deftest spawn-must-be-one-spec-map
  (is (= {:rf.error/id :rf.error/machine-spawn-bad-shape :state :a}
         (select-keys (reg-error {:initial :a
                                  :states  {:a {:spawn [{:machine-id :k1} {:machine-id :k2}]}}})
                      [:rf.error/id :state]))
      "a vector :spawn is refused — N children is :spawn-all"))

(deftest internal-events-has-no-wildcard
  (testing "a :ns/* or :* member is refused — membership is exact"
    (is (= {:rf.error/id :rf.error/machine-bad-internal-events :wildcards [:change/*]}
           (select-keys (reg-error {:initial         :a
                                    :internal-events #{:change/*}
                                    :states          {:a {:on {:change/* :a}}}})
                        [:rf.error/id :wildcards])))
    (is (= :rf.error/machine-bad-internal-events
           (reg-error-id {:initial :a :internal-events #{:*} :states {:a {}}}))))
  (testing "a name that merely ends in * is an ordinary literal event id"
    (is (nil? (reg-error {:initial         :a
                          :internal-events #{:field/save*}
                          :states          {:a {:on {:field/save* :a}}}})))))

(deftest tags-refuse-reserved-namespaces
  (testing "an :rf/* or :rf.*/* tag is refused"
    (is (= {:rf.error/id :rf.error/machine-bad-tags :reserved [:rf/x]}
           (select-keys (reg-error {:initial :a :states {:a {:tags #{:rf/x}}}})
                        [:rf.error/id :reserved])))
    (is (= :rf.error/machine-bad-tags
           (reg-error-id {:initial :a :states {:a {:tags #{:busy :rf.foo/x}}}}))))
  (testing "control: tags in the app's own namespaces register"
    (is (nil? (reg-error {:initial :a :states {:a {:tags #{:busy :ui.state/loading}}}})))))

(deftest on-done-belongs-on-a-compound-node
  (is (= {:rf.error/id :rf.error/machine-unknown-node-key :offending-keys [:on-done] :state :a}
         (select-keys (reg-error {:initial :a :states {:a {:on-done :b :on {:go :c}} :b {} :c {}}})
                      [:rf.error/id :offending-keys :state]))
      ":on-done on a leaf is refused — it could never fire"))

;; ---- an :always needs a :guard or a :target -----------------------------------

(deftest unguarded-targetless-always-is-refused
  (let [f (fn [_] nil)]
    (testing "an :always entry with neither :guard nor :target is refused"
      (is (= {:rf.error/id :rf.error/machine-always-unguarded-targetless :state :x}
             (select-keys (reg-error {:initial :x :actions {:noop f}
                                      :states  {:x {:always {:action :noop}}}})
                          [:rf.error/id :state])))
      (is (= :rf.error/machine-always-unguarded-targetless
             (reg-error-id {:initial :x :guards {:more? (fn [_] false)} :actions {:noop f}
                            :states  {:x {:always [{:guard :more? :target :y}
                                                   {:action :noop}]}
                                      :y {}}}))
          "a later candidate in the vector"))
    (testing "controls: a guarded targetless and an unguarded targeted :always register"
      (is (nil? (reg-error {:initial :x :guards {:more? (fn [_] false)} :actions {:bump f}
                            :states  {:x {:always {:guard :more? :action :bump}}}})))
      (is (nil? (reg-error {:initial :x :states {:x {:always {:target :y}} :y {}}}))))))
