(ns re-frame.machine-structure-and-transition-value-cljs-test
  "`reg-machine` refuses a definition whose state tree is not built of maps, and
  a transition value the macrostep cannot read, with a structured `:rf.error/*`
  category rather than a host exception or a registration that fails later.

  Structure. A `:states` or `:regions` that is neither nil nor a map, and a
  state node that is neither nil nor a map, are refused with
  `:rf.error/machine-bad-structure`, before any other check walks the tree. On
  a `:type :parallel` root a non-map `:regions`, and a region body that is not
  a map, are refused with `:rf.error/machine-parallel-bad-shape`.

  Transition values. A value is valid exactly when the shared grammar the
  runtime normaliser uses accepts it: a target keyword, a target path vector, a
  transition map, a vector of transition maps, or nil. Any other value is
  refused with the category the runtime raises for its slot:
  `:rf.error/machine-bad-on-clause` for an `:on` entry,
  `:rf.error/machine-bad-after-spec` for an `:after` entry, an `:on-timeout`
  and a `:spawn :on-timeout`, `:rf.error/machine-bad-always` for `:always` and
  `:rf.error/machine-bad-on-done-clause` for `:on-done`. Each refusal names the
  `:slot`, the offending `:value` and where it sits.

  Cross-platform (`*_cljs_test.cljc`): the host exception a malformed tree
  would otherwise raise differs per host, so both hosts are pinned."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; Load the machines facade so `rf/reg-machine` routes through its
   ;; late-bind hook (`:machines/reg-machine`).
   [re-frame.machines :as rf.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

(defn- registration
  "Register `machine` under a fresh id. Returns `:registered`, the refusal's
  ex-data, or `{:host-throw <message>}` when the throw carries no
  `:rf.error/id`."
  [machine]
  (try (rf/reg-machine (keyword "structure-shape" (str (gensym))) machine) :registered
       (catch #?(:clj Throwable :cljs :default) t
         (let [data (ex-data t)]
           (if (:rf.error/id data)
             data
             {:host-throw #?(:clj (.getMessage ^Throwable t) :cljs (str t))})))))

(defn- refused-as?
  "Does `refusal` carry category `id` and every entry of `expected`?"
  [refusal id expected]
  (and (= id (:rf.error/id refusal))
       (= expected (select-keys refusal (keys expected)))))

;; ---- structure ------------------------------------------------------------

(def ^:private non-maps
  "Values that are neither nil nor a map."
  [[:a] 42 "a" :a #{:a} (fn [_] nil)])

(def ^:private states-positions
  "Position → [a machine whose `:states` there is `v`, the location its refusal
  names]."
  {:flat-root    (fn [v] [{:initial :a :states v} {:state :rf/root}])
   :compound     (fn [v] [{:initial :o :states {:o {:initial :a :states v}}} {:state :o}])
   :state        (fn [v] [{:initial :a :states {:a {:states v}}} {:state :a}])
   :region-body  (fn [v] [{:type :parallel :regions {:r {:initial :a :states v}}}
                          {:state :rf/region-root :region :r}])
   :region-state (fn [v] [{:type :parallel :regions {:r {:initial :a :states {:a {:initial :x :states v}}}}}
                          {:state :a}])})

(def ^:private regions-positions
  "Position → [a machine whose `:regions` there is `v`, off a parallel root, the
  location its refusal names]."
  {:flat-root    (fn [v] [{:initial :a :regions v :states {:a {}}} {:state :rf/root}])
   :state        (fn [v] [{:initial :a :states {:a {:regions v}}} {:state :a}])
   :region-state (fn [v] [{:type :parallel :regions {:r {:initial :a :states {:a {:regions v}}}}}
                          {:state :a}])})

(deftest a-non-map-states-or-regions-is-refused
  (doseq [[slot positions] [[:states states-positions] [:regions regions-positions]]
          [position make]  positions
          v                non-maps
          :let [[machine location] (make v)
                refusal            (registration machine)]]
    (testing (str slot " " (pr-str v) " on the " position)
      (is (refused-as? refusal :rf.error/machine-bad-structure
                       (assoc location :slot slot :value v))
          (pr-str refusal)))))

(def ^:private node-positions
  "Position → [a machine whose state `:a` is `v`, the location its refusal
  names]."
  {:flat-root (fn [v] [{:initial :a :states {:a v}} {:state :a}])
   :compound  (fn [v] [{:initial :o :states {:o {:initial :a :states {:a v}}}} {:state :a}])
   :region    (fn [v] [{:type :parallel :regions {:r {:initial :a :states {:a v}}}} {:state :a}])})

(deftest a-non-map-state-node-is-refused
  (doseq [[position make] node-positions
          v               non-maps
          :let [[machine location] (make v)
                refusal            (registration machine)]]
    (testing (str "state :a as " (pr-str v) " under the " position)
      (is (refused-as? refusal :rf.error/machine-bad-structure
                       (assoc location :slot :states :value v))
          (pr-str refusal)))))

(deftest a-parallel-root-refuses-non-map-regions-with-its-own-category
  (testing "a :regions that is not a map"
    (doseq [v [42 "r" :r #{:r} [[:r {:initial :a :states {:a {}}}]]]]
      (is (= :rf.error/machine-parallel-bad-shape
             (:rf.error/id (registration {:type :parallel :regions v})))
          (pr-str v))))
  (testing "a region body that is not a map names the region"
    (doseq [v [nil 42 [:a] "a" :a]]
      (is (refused-as? (registration {:type :parallel :regions {:r v}})
                       :rf.error/machine-parallel-bad-shape {:region :r})
          (pr-str v)))))

(deftest nil-and-map-structure-registers
  (doseq [machine [{:initial :a :states {:a {:states nil}}}
                   {:initial :a :states {:a {:states {}}}}
                   {:initial :a :states {:a nil :b {}}}
                   {:initial :o :states {:o {:initial :a :states {:a {}}}}}
                   {:type :parallel :regions {:r {:initial :a :states {:a {:initial :x :states {:x {}}}}}}}]]
    (is (= :registered (registration machine)) (pr-str machine))))

;; ---- transition values ----------------------------------------------------

(def ^:private malformed
  "Values the shared transition grammar refuses."
  [42 1.5 "b" #{:b} true false 'b '(:b) (fn [_] nil)])

(def ^:private targets
  "Valid transition values aiming at state `:b`, and at `[:r :b]` from a
  parallel root, whose targets are region-qualified."
  {:sibling  [nil :b [:b] {:target :b} {} [{:target :b}]]
   :parallel [nil [:r :b] {:target [:r :b]} {} [{:target [:r :b]}]]})

(def ^:private value-positions
  "Slot → the category the slot's malformed value is refused with, and each
  position → [a machine declaring value `v` in the slot there, the location
  its refusal names, the valid values that register there]. A flat root's and
  a region body's `:after` and a machine root's `:always` are refused for their
  place, so they are not positions here."
  {:on
   [:rf.error/machine-bad-on-clause {:event-id :go}
    {:leaf          (fn [v] [{:initial :a :states {:a {:on {:go v}} :b {}}} {:state :a} (:sibling targets)])
     :compound      (fn [v] [{:initial :o :states {:o {:initial :a :on {:go v} :states {:a {}}} :b {}}}
                             {:state :o} (:sibling targets)])
     :region-state  (fn [v] [{:type :parallel :regions {:r {:initial :a :states {:a {:on {:go v}} :b {}}}}}
                             {:state :a} (:sibling targets)])
     :region-body   (fn [v] [{:type :parallel :regions {:r {:initial :a :on {:go v} :states {:a {} :b {}}}}}
                             {:state :rf/region-root :region :r} (:sibling targets)])
     :flat-root     (fn [v] [{:initial :a :on {:go v} :states {:a {} :b {}}} {:state :rf/root} (:sibling targets)])
     :parallel-root (fn [v] [{:type :parallel :on {:go v} :regions {:r {:initial :a :states {:a {} :b {}}}}}
                             {:state :rf/root} (:parallel targets)])}]

   :after
   [:rf.error/machine-bad-after-spec {:delay-key 1000}
    {:leaf          (fn [v] [{:initial :a :states {:a {:after {1000 v}} :b {}}} {:state :a} (:sibling targets)])
     :compound      (fn [v] [{:initial :o :states {:o {:initial :a :after {1000 v} :states {:a {}}} :b {}}}
                             {:state :o} (:sibling targets)])
     :region-state  (fn [v] [{:type :parallel :regions {:r {:initial :a :states {:a {:after {1000 v}} :b {}}}}}
                             {:state :a} (:sibling targets)])
     :parallel-root (fn [v] [{:type :parallel :after {1000 v} :regions {:r {:initial :a :states {:a {} :b {}}}}}
                             {:state :rf/root} (:parallel targets)])}]

   :always
   [:rf.error/machine-bad-always {}
    ;; An unguarded targetless `{}` `:always` is refused for looping, so it is
    ;; not among the values that register.
    (let [valid (remove #{{}} (:sibling targets))]
      {:leaf         (fn [v] [{:initial :a :states {:a {:always v} :b {}}} {:state :a} valid])
       :compound     (fn [v] [{:initial :o :states {:o {:initial :a :always v :states {:a {}}} :b {}}}
                              {:state :o} valid])
       :region-state (fn [v] [{:type :parallel :regions {:r {:initial :a :states {:a {:always v} :b {}}}}}
                              {:state :a} valid])})]

   :on-done
   [:rf.error/machine-bad-on-done-clause {}
    {:compound      (fn [v] [{:initial :o :states {:o {:initial :a :on-done v :states {:a {:final? true}}} :b {}}}
                             {:state :o} (:sibling targets)])
     :region-body   (fn [v] [{:type :parallel :regions {:r {:initial :a :on-done v
                                                                :states  {:a {:final? true} :b {}}}}}
                             {:state :rf/region-root :region :r} (:sibling targets)])
     ;; A parallel root's `:on-done` runs an action; it never names a target.
     :parallel-root (fn [v] [{:type    :parallel :actions {:note (fn [_ctx] nil)} :on-done v
                              :regions {:r {:initial :a :states {:a {:final? true}}}}}
                             {:state :rf/root} [nil {} {:action :note} [{:action :note}]]])}]

   :on-timeout
   [:rf.error/machine-bad-after-spec {}
    {:leaf (fn [v] [{:initial :a :states {:a {:timeout 1000 :on-timeout v} :b {}}}
                    {:state :a} (:sibling targets)])}]

   :spawn/on-timeout
   [:rf.error/machine-bad-after-spec {}
    {:leaf (fn [v] [{:initial :a :states {:a {:spawn {:machine-id :child :timeout 1000 :on-timeout v}} :b {}}}
                    {:state :a} (:sibling targets)])}]})

(deftest a-malformed-transition-value-is-refused-with-its-slot-category
  (doseq [[slot [id slot-extras positions]] value-positions
          [position make]                   positions
          v                                 malformed
          :let [[machine location] (make v)
                refusal            (registration machine)]]
    (testing (str slot " " (pr-str v) " on the " position)
      (is (refused-as? refusal id (merge location slot-extras {:slot slot :value v}))
          (pr-str refusal)))))

(deftest every-value-the-grammar-accepts-registers
  (doseq [[slot [_id _extras positions]] value-positions
          [position make]                positions
          :let [[_ _ valid] (make nil)]
          v                              valid]
    (testing (str slot " " (pr-str v) " on the " position)
      (is (= :registered (registration (first (make v))))))))

(deftest a-value-in-a-slot-the-node-never-reads-is-refused-for-its-place
  (testing "the refusal for the slot's place comes first, as it always has"
    (is (= :rf.error/machine-non-parallel-root-after-not-supported
           (:rf.error/id (registration {:initial :a :after {1000 42} :states {:a {}}}))))
    (is (= :rf.error/machine-root-slot-not-supported
           (:rf.error/id (registration {:initial :a :always 42 :states {:a {}}}))))
    (is (= :rf.error/machine-unknown-node-key
           (:rf.error/id (registration {:initial :a :states {:a {:on-done 42}}}))))))

(deftest registration-refuses-an-on-value-exactly-when-the-macrostep-does
  (testing "for each value, registration refuses it as a malformed :on entry
            iff a macrostep taking the transition throws the same category"
    (doseq [v (concat malformed (:sibling targets)
                      [[] [{:target :b} 42] {:target 42}])
            :let [machine    {:initial :a :states {:a {:on {:go v}} :b {}}}
                  registered (:rf.error/id (registration machine))
                  stepped    (try (rf.machines/machine-transition machine {:state :a :data {}} [:go])
                                  nil
                                  (catch #?(:clj Throwable :cljs :default) t
                                    (:rf.error/id (ex-data t))))]]
      (is (= (= :rf.error/machine-bad-on-clause registered)
             (= :rf.error/machine-bad-on-clause stepped))
          (str (pr-str v) " registration " (pr-str registered) " macrostep " (pr-str stepped))))))
