(ns re-frame.region-lifecycle-test
  "A parallel REGION BODY honours `:entry`, `:exit` and `:tags`, per Spec 005
  §State nodes (the machine root): `:entry` runs once at birth (after the
  root's, before the region's leaf), `:exit` once at teardown (after the
  region's leaves, before the root's), never on a transition, and region
  `:tags` join the tag union. Region `:entry` / `:exit` refs are checked at
  registration."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(def ^:private snapshot rf.machines.test-support/snapshot)

(defn- recorders
  "An `:actions` map whose every entry appends its own name to `log`."
  [log ks]
  (into {} (map (fn [k] [k (fn [_] (swap! log conj k) nil)])) ks))

(def ^:private action-names
  [:root-in :root-out :x-in :x-out :x1-in :x1-out :x2-in :x2-out
   :y-in :y-out :y1-in :y1-out :y2-in :y2-out])

(defn- parallel-machine
  "Root and both region bodies declare `:entry` / `:exit`; each region
  reaches a `:final?` leaf on `:fin`."
  [log]
  {:type    :parallel
   :entry   :root-in
   :exit    :root-out
   :actions (recorders log action-names)
   :regions {:x {:initial :x1
                 :entry   :x-in
                 :exit    :x-out
                 :states  {:x1 {:entry :x1-in :exit :x1-out :on {:fin :x2}}
                           :x2 {:entry :x2-in :exit :x2-out :final? true}}}
             :y {:initial :y1
                 :entry   :y-in
                 :exit    :y-out
                 :states  {:y1 {:entry :y1-in :exit :y1-out :on {:fin :y2}}
                           :y2 {:entry :y2-in :exit :y2-out :final? true}}}}})

(deftest region-entry-runs-at-birth-between-root-and-leaf
  (let [log (atom [])]
    (rf/reg-machine :rg/birth (parallel-machine log))
    (rf/dispatch-sync [:rg/birth [:rf.machine/start]])
    (is (= [:root-in :x-in :x1-in :y-in :y1-in] @log))))

(deftest region-entry-data-reaches-the-region-leaf
  (rf/reg-machine :rg/data
    {:type    :parallel
     :data    {:trail [:seed]}
     :entry   (fn [{:keys [data]}] {:data (update data :trail conj :root)})
     :regions {:x {:initial :x1
                   :entry   (fn [{:keys [data]}] {:data (update data :trail conj :x)})
                   :states  {:x1 {:entry (fn [{:keys [data]}] {:data (update data :trail conj :x1)})}}}}})
  (rf/dispatch-sync [:rg/data [:rf.machine/start]])
  (is (= [:seed :root :x :x1] (get-in (snapshot :rg/data) [:data :trail]))))

(deftest region-tags-join-the-union
  (rf/reg-machine :rg/tags
    {:type    :parallel
     :tags    #{:whole}
     :regions {:x {:initial :x1
                   :tags    #{:in-x}
                   :states  {:x1 {:tags #{:at-x1} :on {:go :x2}}
                             :x2 {}}}
               :y {:initial :y1
                   :states  {:y1 {}}}}})
  (rf/dispatch-sync [:rg/tags [:rf.machine/start]])
  (is (= #{:whole :in-x :at-x1} (:tags (snapshot :rg/tags))))
  (rf/dispatch-sync [:rg/tags [:go]])
  (is (= #{:whole :in-x} (:tags (snapshot :rg/tags)))
      "the region tag stays after the leaf that carried a tag exits"))

(deftest region-exit-runs-at-teardown-between-leaf-and-root
  (let [log (atom [])]
    (rf/reg-machine :rg/destroy (parallel-machine log))
    (rf/dispatch-sync [:rg/destroy [:rf.machine/start]])
    (reset! log [])
    (rf/reg-event ::kill (fn [_ [_ id]] {:fx [[:rf.machine/destroy id]]}))
    (rf/dispatch-sync [::kill :rg/destroy])
    (is (= [:x1-out :x-out :y1-out :y-out :root-out] @log))))

(deftest region-exit-runs-at-finality
  (let [log (atom [])]
    (rf/reg-machine :rg/final (parallel-machine log))
    (rf/dispatch-sync [:rg/final [:rf.machine/start]])
    (reset! log [])
    (rf/dispatch-sync [:rg/final [:fin]])
    (is (= [:x1-out :x2-in :y1-out :y2-in :x2-out :x-out :y2-out :y-out :root-out] @log)
        "each region :exit runs once, at whole-machine finality, not on reaching its final leaf")))

(deftest region-entry-and-exit-never-run-on-a-transition
  (let [log (atom [])]
    (rf/reg-machine :rg/transitions
      {:type    :parallel
       :actions (recorders log [:x-in :x-out :a-in :a-out :b-in :b-out])
       :on      {:root-go {:target [:x :b]}}
       :regions {:x {:initial :a
                     :entry   :x-in
                     :exit    :x-out
                     :on      {:reset {:target :a}
                               :again {:target :a :reenter? true}}
                     :states  {:a {:entry :a-in :exit :a-out :on {:hop :b}}
                               :b {:entry :b-in :exit :b-out}}}}})
    (rf/dispatch-sync [:rg/transitions [:rf.machine/start]])
    (reset! log [])
    ;; a child transition, a region-root :on target, the same with :reenter?,
    ;; and the parallel root's region-qualified :on target
    (doseq [ev [:hop :reset :again :root-go]]
      (rf/dispatch-sync [:rg/transitions [ev]]))
    (is (= [:a-out :b-in, :b-out :a-in, :a-out :a-in, :a-out :b-in] @log))))

(deftest region-actions-name-the-region-body
  (let [log (atom [])]
    (rf/reg-machine :rg/trace (parallel-machine log))
    (rf/dispatch-sync [:rg/trace [:fin]])
    (let [events (rf.machines.test-support/captured-events)
          ran    (into []
                       (comp (filter #(= :rf.machine/action-ran (:operation %)))
                             (map :tags)
                             (filter #(#{:root-in :x-in :x1-in} (:action-id %)))
                             (map #(select-keys % [:action-id :phase :decl-path :region])))
                       events)
          steps  (->> events
                      (filter #(= :rf.machine/transition (:operation %)))
                      first
                      :tags
                      :cascade
                      (mapv #(select-keys % [:kind :state :region :action])))]
      (is (= [{:action-id :root-in :phase :initial-entry :decl-path []}
              {:action-id :x-in    :phase :initial-entry :decl-path [] :region :x}
              {:action-id :x1-in   :phase :initial-entry :decl-path [:x1] :region :x}]
             ran)
          "the action-ran trace names the declaring node, region-relative inside a region")
      (is (= [{:kind :entry :state [] :region nil :action :root-in}
              {:kind :entry :state [] :region :x :action :x-in}
              {:kind :entry :state [:x1] :region :x :action :x1-in}]
             (subvec steps 0 3))
          "the birth cascade records the region body's :entry at the region's empty path"))))

(defn- refusal-id
  [machine]
  (try (rf.machines/validate-machine! machine) nil
       (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))

(deftest region-entry-and-exit-refs-are-checked
  (doseq [slot [:entry :exit]]
    (is (= :rf.error/machine-unresolved-action
           (refusal-id {:type    :parallel
                        :regions {:x {:initial :x1 slot :nowhere :states {:x1 {}}}}}))
        (str "a region " slot " naming no action"))))
