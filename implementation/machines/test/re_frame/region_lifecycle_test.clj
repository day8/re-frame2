(ns re-frame.region-lifecycle-test
  "A parallel REGION BODY honours `:entry`, `:exit` and `:tags`, per Spec 005
  §State nodes (the machine root) and §Parallel regions: a region body is
  the root of its region's tree and follows the machine root's rule.

  - Region `:entry` runs ONCE at birth, after the parallel root's `:entry`
    and before the region's initial leaf cascade, regions in declaration
    order.
  - Region `:exit` runs ONCE at teardown, after the region's leaf-to-region
    exit cascade and before the parallel root's `:exit`: on destroy and on
    whole-machine finality. A machine resting all-final under an `:on-done`
    has not run it.
  - Region `:tags` join the snapshot's tag union for the machine's life.
  - A region is never exited or entered by a transition — not by a
    region-root `:on` target (with or without `:reenter? true`), nor by the
    parallel root's region-qualified `:on` target.
  - A spawned parallel actor runs its region `:entry` / `:exit` too.
  - Region `:entry` / `:exit` refs are held to the checks a state's are.

  Live runtime (plain-atom substrate); the callbacks record into an atom so
  teardown order stays observable after the snapshot is gone."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
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
  "A parallel machine whose root and both region bodies declare `:entry` /
  `:exit`, region `:x` a `:tags` set. Each region reaches a `:final?` leaf
  on `:fin`."
  [log extra]
  (merge
    {:type    :parallel
     :entry   :root-in
     :exit    :root-out
     :actions (recorders log action-names)
     :regions {:x {:initial :x1
                   :entry   :x-in
                   :exit    :x-out
                   :tags    #{:in-x}
                   :states  {:x1 {:entry :x1-in :exit :x1-out :tags #{:at-x1} :on {:fin :x2}}
                             :x2 {:entry :x2-in :exit :x2-out :final? true}}}
               :y {:initial :y1
                   :entry   :y-in
                   :exit    :y-out
                   :states  {:y1 {:entry :y1-in :exit :y1-out :on {:fin :y2}}
                             :y2 {:entry :y2-in :exit :y2-out :final? true}}}}}
    extra))

(defn- kill!
  "Destroy `actor-id` through the `:rf.machine/destroy` fx."
  [actor-id]
  (rf/reg-event ::kill (fn [_ [_ id]] {:fx [[:rf.machine/destroy id]]}))
  (rf/dispatch-sync [::kill actor-id]))

;; ---- birth -----------------------------------------------------------------

(deftest region-entry-runs-at-birth-between-root-and-leaf
  (let [log (atom [])]
    (rf/reg-machine :rg/birth (parallel-machine log {}))
    (rf/dispatch-sync [:rg/birth [:rf.machine/start]])
    (is (= [:root-in :x-in :x1-in :y-in :y1-in] @log)
        "root :entry, then per region in declaration order: the body's :entry, then its initial leaf's")))

(deftest region-entry-runs-at-lazy-birth
  (let [log (atom [])]
    (rf/reg-machine :rg/lazy (parallel-machine log {}))
    (rf/dispatch-sync [:rg/lazy [:fin]])
    (is (= [:root-in :x-in :x1-in :y-in :y1-in] (subvec @log 0 5))
        "the first event boots the machine, region :entry among the birth cascade")
    (is (= 1 (count (filter #{:x-in} @log))))))

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

;; ---- tags ------------------------------------------------------------------

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
  (is (= #{:whole :in-x :at-x1} (:tags (snapshot :rg/tags)))
      "root, region body and active leaf tags form one union")
  (rf/dispatch-sync [:rg/tags [:go]])
  (is (= #{:whole :in-x} (:tags (snapshot :rg/tags)))
      "the region tag stays after the leaf that carried a tag exits"))

;; ---- teardown --------------------------------------------------------------

(deftest region-exit-runs-at-teardown-between-leaf-and-root
  (let [log (atom [])]
    (rf/reg-machine :rg/destroy (parallel-machine log {}))
    (rf/dispatch-sync [:rg/destroy [:rf.machine/start]])
    (reset! log [])
    (kill! :rg/destroy)
    (is (= [:x1-out :x-out :y1-out :y-out :root-out] @log)
        "per region in declaration order: its leaf's :exit, then the body's; the root's last")
    (is (nil? (snapshot :rg/destroy)))))

(deftest region-exit-runs-at-finality
  (let [log (atom [])]
    (rf/reg-machine :rg/final (parallel-machine log {}))
    (rf/dispatch-sync [:rg/final [:rf.machine/start]])
    (reset! log [])
    (rf/dispatch-sync [:rg/final [:fin]])
    (is (nil? (snapshot :rg/final)) "all regions final with no :on-done — the machine finishes")
    (is (= [:x2-out :x-out :y2-out :y-out :root-out] (vec (take-last 5 @log))))
    (is (= 1 (count (filter #{:x-out} @log))))))

(deftest region-exit-waits-while-the-machine-rests-all-final
  (let [log (atom [])]
    (rf/reg-machine :rg/rest (parallel-machine log {:on-done {:action (fn [_] nil)}}))
    (rf/dispatch-sync [:rg/rest [:rf.machine/start]])
    (rf/dispatch-sync [:rg/rest [:fin]])
    (is (some? (snapshot :rg/rest)))
    (is (not-any? #{:x-out :y-out} @log) "no region :exit while the machine rests")
    (kill! :rg/rest)
    (is (= [:x2-out :x-out :y2-out :y-out :root-out] (vec (take-last 5 @log))))))

;; ---- a region is never exited or entered by a transition -------------------

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
    (testing "an ordinary child transition"
      (rf/dispatch-sync [:rg/transitions [:hop]])
      (is (= [:a-out :b-in] @log)))
    (testing "a region-root :on target"
      (reset! log [])
      (rf/dispatch-sync [:rg/transitions [:reset]])
      (is (= [:b-out :a-in] @log)))
    (testing "a region-root :reenter? true target"
      (reset! log [])
      (rf/dispatch-sync [:rg/transitions [:again]])
      (is (= [:a-out :a-in] @log)))
    (testing "the parallel root's region-qualified :on target"
      (reset! log [])
      (rf/dispatch-sync [:rg/transitions [:root-go]])
      (is (= [:a-out :b-in] @log)))
    (is (= {:x :b} (:state (snapshot :rg/transitions))))))

;; ---- spawned parallel actors -----------------------------------------------

(deftest spawned-parallel-actor-runs-region-entry-and-exit
  (let [log (atom [])]
    (rf/reg-machine :rg/kid
      {:type    :parallel
       :actions (recorders log [:x-in :x-out :w-in :w-out])
       :regions {:x {:initial :working
                     :entry   :x-in
                     :exit    :x-out
                     :states  {:working {:entry :w-in :exit :w-out}}}}})
    (rf/reg-machine :rg/parent
      {:initial :idle
       :states  {:idle    {:on {:start :working}}
                 :working {:spawn {:machine-id :rg/kid}
                           :on    {:stop :idle}}}})
    (rf/dispatch-sync [:rg/parent [:start]])
    (is (= [:x-in :w-in] @log))
    (rf/dispatch-sync [:rg/parent [:stop]])
    (is (= [:x-in :w-in :w-out :x-out] @log))))

;; ---- the cascade and the action trace name the region body -----------------

(deftest region-actions-name-the-region-body
  (let [log (atom [])]
    (rf/reg-machine :rg/trace (parallel-machine log {}))
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

;; ---- region :entry / :exit refs are checked like a state's -----------------

(defn- refusal-id
  [machine]
  (try (rf.machines/validate-machine! machine) nil
       (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))

(deftest region-entry-and-exit-refs-are-checked
  (let [region (fn [k v] {:type    :parallel
                          :regions {:x {:initial :x1 k v :states {:x1 {}}}}})]
    (is (= :rf.error/machine-unresolved-action (refusal-id (region :entry :nowhere))))
    (is (= :rf.error/machine-unresolved-action (refusal-id (region :exit :nowhere))))
    (is (= :rf.error/machine-bad-action-form (refusal-id (region :exit [:a :b]))))
    (is (nil? (refusal-id (region :entry (fn [_] nil)))) "an inline fn registers")))
