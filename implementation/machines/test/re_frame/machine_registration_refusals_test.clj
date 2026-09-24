(ns re-frame.machine-registration-refusals-test
  "Registration refuses machine shapes that would otherwise register cleanly
  and then fail late, generically, or never:

    - an `:initial` naming no declared child (root, compound, region) —
      `:rf.error/machine-unresolved-target` with `:slot :initial`; and the pure
      `machine-transition` refuses a snapshot `:state` naming no declared state
      with `:rf.error/machine-state-not-in-definition`;
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
    (let [d (reg-error {:initial :zzz :states {:a {:on {:go :a}}}})]
      (is (= :rf.error/machine-unresolved-target (:rf.error/id d)))
      (is (= :initial (:slot d)) "names the :initial slot")
      (is (= :zzz (:target d)) "names the undeclared :initial")
      (is (= :rf/root (:state d)))))

  (testing "a compound's :initial naming no child is refused"
    (let [d (reg-error {:initial :p
                        :states  {:p {:initial :zzz :states {:x {}}}}})]
      (is (= :rf.error/machine-unresolved-target (:rf.error/id d)))
      (is (= [:p :initial :zzz] [(:state d) (:slot d) (:target d)]))))

  (testing "a parallel region's :initial naming no state in its :states is refused"
    (let [d (reg-error {:type    :parallel
                        :regions {:r1 {:initial :zzz :states {:a {}}}
                                  :r2 {:initial :p :states {:p {}}}}})]
      (is (= :rf.error/machine-unresolved-target (:rf.error/id d)))
      (is (= [:r1 :initial :zzz] [(:region d) (:slot d) (:target d)]))))

  (testing "controls: a root, compound and region :initial naming a declared child register"
    (is (nil? (reg-error {:initial :p
                          :states  {:p {:initial :x :states {:x {}}}}})))
    (is (nil? (reg-error {:type    :parallel
                          :regions {:r1 {:initial :a :states {:a {}}}
                                    :r2 {:initial :p :states {:p {}}}}})))))

(deftest pure-transition-refuses-an-undeclared-snapshot-state
  (let [definition {:id      :refusal/pure
                    :initial :a
                    :states  {:a {:on {:go :b}} :b {}}}]
    (testing "a snapshot :state naming no declared state throws rather than no-op"
      (let [e (try (rf.machines/machine-transition definition {:state :zzz :data {}} [:go])
                   nil
                   (catch clojure.lang.ExceptionInfo ex ex))]
        (is (some? e) "the pure transition throws on a phantom :state")
        (is (= :rf.error/machine-state-not-in-definition (:rf.error/id (ex-data e))))
        (is (= :zzz (:state (ex-data e))) "names the offending :state")))

    (testing "a compound path naming no declared node throws the same id"
      (is (= :rf.error/machine-state-not-in-definition
             (try (rf.machines/machine-transition definition {:state [:a :zzz] :data {}} [:go])
                  nil
                  (catch clojure.lang.ExceptionInfo ex (:rf.error/id (ex-data ex)))))))

    (testing "a malformed :state still reports its shape, not its membership"
      (is (= :rf.error/machine-bad-state-form
             (try (rf.machines/machine-transition definition {:state "a" :data {}} [:go])
                  nil
                  (catch clojure.lang.ExceptionInfo ex (:rf.error/id (ex-data ex)))))))

    (testing "control: a declared :state transitions"
      (is (= {:status :ok :state :b}
             (-> (rf.machines/machine-transition definition {:state :a :data {}} [:go])
                 (as-> r {:status (:status r) :state (get-in r [:snapshot :state])})))))))

;; ---- :guard / action slots hold one fn or one keyword ------------------------

(deftest guard-and-action-forms-are-refused-at-registration
  (let [f        (fn [_] nil)
        g        (fn [_] true)
        base     {:guards  {:g g :h g}
                  :actions {:a1 f :a2 f}}
        machine  (fn [a-state] (merge base {:initial :a :states {:a a-state :b {}}}))]
    (testing "an :entry / :exit / transition :action vector is refused"
      (let [d (reg-error (machine {:entry [f f]}))]
        (is (= :rf.error/machine-bad-action-form (:rf.error/id d)))
        (is (= :entry (:slot d)) "names the slot"))
      (is (= :rf.error/machine-bad-action-form (reg-error-id (machine {:entry [:a1 :a2]}))))
      (is (= :rf.error/machine-bad-action-form (reg-error-id (machine {:exit [f] :on {:go :b}}))))
      (is (= :rf.error/machine-bad-action-form
             (reg-error-id (machine {:on {:go {:target :b :action [f f]}}})))))

    (testing "the XState {:type …} action object is refused"
      (is (= :rf.error/machine-bad-action-form (reg-error-id (machine {:entry {:type :a1}})))))

    (testing "a vector, map, combinator or string :guard is refused"
      (doseq [bad [[:g 10] {:id :g :params {}} {:and [:g :h]} "g"]]
        (let [d (reg-error (machine {:on {:go {:target :b :guard bad}}}))]
          (is (= :rf.error/machine-bad-guard-form (:rf.error/id d)) (pr-str bad))
          (is (= bad (:guard d)) "names the offending guard"))))

    (testing "an :always candidate's guard / action forms are checked too"
      (is (= :rf.error/machine-bad-action-form
             (reg-error-id (machine {:always [{:guard :g :target :b :action [f]}]})))))

    (testing "controls: fn and keyword guards / actions register"
      (is (nil? (reg-error (machine {:entry f :exit :a1
                                     :on {:go {:target :b :guard :g :action :a2}
                                          :up {:target :b :guard g :action f}}})))))))

;; ---- five registration holes ---------------------------------------------------

(deftest spawn-must-be-one-spec-map
  (testing "a vector :spawn is refused — N children is :spawn-all"
    (let [d (reg-error {:initial :a
                        :states  {:a {:spawn [{:machine-id :k1} {:machine-id :k2}]}}})]
      (is (= :rf.error/machine-spawn-bad-shape (:rf.error/id d)))
      (is (= :a (:state d)))))
  (testing "control: one spawn-spec map registers"
    (is (nil? (reg-error {:initial :a :states {:a {:spawn {:machine-id :k1}}}})))))

(deftest internal-events-has-no-wildcard
  (testing "a :ns/* or :* member is refused — membership is exact"
    (let [d (reg-error {:initial         :a
                        :internal-events #{:change/*}
                        :states          {:a {:on {:change/* :a}}}})]
      (is (= :rf.error/machine-bad-internal-events (:rf.error/id d)))
      (is (= [:change/*] (:wildcards d))))
    (is (= :rf.error/machine-bad-internal-events
           (reg-error-id {:initial :a :internal-events #{:*} :states {:a {}}}))))
  (testing "control: an enumerated member registers"
    (is (nil? (reg-error {:initial         :a
                          :internal-events #{:change/x}
                          :states          {:a {:on {:change/x :a}}}})))))

(deftest tags-refuse-reserved-namespaces
  (testing "an :rf/* or :rf.*/* tag is refused"
    (let [d (reg-error {:initial :a :states {:a {:tags #{:rf/x}}}})]
      (is (= :rf.error/machine-bad-tags (:rf.error/id d)))
      (is (= [:rf/x] (:reserved d))))
    (is (= :rf.error/machine-bad-tags
           (reg-error-id {:initial :a :states {:a {:tags #{:busy :rf.foo/x}}}}))))
  (testing "control: tags in the app's own namespaces register"
    (is (nil? (reg-error {:initial :a :states {:a {:tags #{:busy :ui.state/loading}}}})))))

(deftest on-done-belongs-on-a-compound-node
  (testing ":on-done on a leaf is refused — it could never fire"
    (let [d (reg-error {:initial :a :states {:a {:on-done :b :on {:go :c}} :b {} :c {}}})]
      (is (= :rf.error/machine-unknown-node-key (:rf.error/id d)))
      (is (= [:on-done] (:offending-keys d)))
      (is (= :a (:state d))))
    (is (= :rf.error/machine-unknown-node-key
           (reg-error-id {:initial :a :states {:a {:final? true :on-done :b} :b {}}}))
        "a :final? leaf included"))
  (testing "control: :on-done on a compound registers"
    (is (nil? (reg-error {:initial :p
                          :states  {:p {:initial :x
                                        :on-done :b
                                        :states  {:x {:on {:fin :y}} :y {:final? true}}}
                                    :b {}}})))))

;; ---- an :always needs a :guard or a :target -----------------------------------

(deftest unguarded-targetless-always-is-refused
  (let [f (fn [_] nil)]
    (testing "an :always entry with neither :guard nor :target is refused"
      (let [d (reg-error {:initial :x :actions {:noop f}
                          :states  {:x {:always {:action :noop}}}})]
        (is (= :rf.error/machine-always-unguarded-targetless (:rf.error/id d)))
        (is (= :x (:state d))))
      (is (= :rf.error/machine-always-unguarded-targetless
             (reg-error-id {:initial :x :states {:x {:always [{}]}}}))
          "the empty entry")
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
