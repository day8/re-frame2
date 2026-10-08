(ns re-frame.root-lifecycle-test
  "The machine ROOT's `:entry` runs once at birth before any state, its `:exit`
  once at teardown after every state, and neither on a transition; its `:tags`
  join the tag union (Spec 005 §State nodes, the machine root)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines]
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

(defn- flat-machine
  "Root `:entry` / `:exit` / `:tags` over two leaves and a top-level `:final?` `:d`."
  [log]
  {:initial :a
   :entry   :root-in
   :exit    :root-out
   :tags    #{:whole}
   :actions (recorders log [:root-in :root-out :a-in :a-out :b-in :b-out :d-in :d-out])
   :states  {:a {:entry :a-in :exit :a-out :on {:go :d :hop :b}}
             :b {:entry :b-in :exit :b-out}
             :d {:entry :d-in :exit :d-out :final? true}}})

(defn- parallel-machine
  "A parallel root with `:entry` / `:exit`; each region reaches `:final?` on `:fin`."
  [log extra]
  (merge
    {:type    :parallel
     :entry   :root-in
     :exit    :root-out
     :actions (recorders log [:root-in :root-out :x1-in :x1-out :x2-in :x2-out
                              :y1-in :y1-out :y2-in :y2-out])
     :regions {:x {:initial :x1
                   :states  {:x1 {:entry :x1-in :exit :x1-out :on {:fin :x2}}
                             :x2 {:entry :x2-in :exit :x2-out :final? true}}}
               :y {:initial :y1
                   :states  {:y1 {:entry :y1-in :exit :y1-out :on {:fin :y2}}
                             :y2 {:entry :y2-in :exit :y2-out :final? true}}}}}
    extra))

(defn- kill!
  "Destroy `actor-id` through the `:rf.machine/destroy` fx."
  [actor-id]
  (rf/reg-event ::kill (fn [_ [_ id]] {:fx [[:rf.machine/destroy id]]}))
  (rf/dispatch-sync [::kill actor-id]))

(deftest root-entry-data-reaches-the-initial-leaf
  (rf/reg-machine :rl/data
    {:initial :a
     :data    {:trail [:seed]}
     :entry   (fn [{:keys [data]}] {:data (update data :trail conj :root)})
     :states  {:a {:entry (fn [{:keys [data]}] {:data (update data :trail conj :a)})}}})
  (rf/dispatch-sync [:rl/data [:rf.machine/start]])
  (is (= [:seed :root :a] (get-in (snapshot :rl/data) [:data :trail]))
      "root :entry runs once, against the seeded :data, before the initial leaf's"))

(deftest flat-root-exit-runs-last-at-finality
  (let [log (atom [])]
    (rf/reg-machine :rl/final (flat-machine log))
    (rf/dispatch-sync [:rl/final [:rf.machine/start]])
    (rf/dispatch-sync [:rl/final [:go]])
    (is (= [:root-in :a-in :a-out :d-in :d-out :root-out] @log))
    (is (nil? (snapshot :rl/final)) "the finished machine is torn down")))

(deftest root-exit-sees-the-leaf-exit-data
  (let [seen (atom nil)]
    (rf/reg-machine :rl/exit-data
      {:initial :a
       :data    {:trail []}
       :exit    (fn [{:keys [data]}] (reset! seen (:trail data)) nil)
       :states  {:a {:exit (fn [{:keys [data]}] {:data (update data :trail conj :a-out)})}}})
    (rf/dispatch-sync [:rl/exit-data [:rf.machine/start]])
    (kill! :rl/exit-data)
    (is (= [:a-out] @seen) "root :exit runs against the snapshot the leaf :exit left")))

(deftest spawned-actor-runs-root-entry-and-exit
  (let [log (atom [])]
    (rf/reg-machine :rl/kid
      {:initial :working
       :entry   :root-in
       :exit    :root-out
       :actions (recorders log [:root-in :root-out :w-in :w-out])
       :states  {:working {:entry :w-in :exit :w-out}}})
    (rf/reg-machine :rl/parent
      {:initial :idle
       :states  {:idle    {:on {:start :working}}
                 :working {:spawn {:machine-id :rl/kid}
                           :on    {:stop :idle}}}})
    (rf/dispatch-sync [:rl/parent [:start]])
    (rf/dispatch-sync [:rl/parent [:stop]])
    (is (= [:root-in :w-in :w-out :root-out] @log)
        "the child's birth runs its root :entry first; the parent's exit destroys it, root :exit last")))

(deftest root-actions-carry-the-birth-and-teardown-phases
  (let [log (atom [])]
    (rf/reg-machine :rl/phases (flat-machine log))
    ;; A lazy birth: the first event's transition trace carries the birth cascade.
    (rf/dispatch-sync [:rl/phases [:hop]])
    (kill! :rl/phases)
    (let [events (rf.machines.test-support/captured-events)
          rows   (into []
                       (comp (filter #(= :rf.machine/action-ran (:operation %)))
                             (map :tags)
                             (map (juxt :phase :action-id)))
                       events)
          steps  (->> events
                      (filter #(= :rf.machine/transition (:operation %)))
                      first
                      :tags
                      :cascade
                      (mapv #(select-keys % [:kind :state :region :action])))]
      (is (= [[:initial-entry :root-in]
              [:initial-entry :a-in]
              [:exit :a-out]
              [:entry :b-in]
              [:destroy-exit :b-out]
              [:destroy-exit :root-out]]
             rows))
      (is (= [{:kind :entry :state [] :region nil :action :root-in}
              {:kind :entry :state [:a] :region nil :action :a-in}]
             (subvec steps 0 2))
          "the birth cascade opens with the root :entry step at the empty path"))))

(deftest root-entry-and-exit-never-run-on-a-transition
  (let [log (atom [])]
    (rf/reg-machine :rl/guard
      {:initial :a
       :entry   :root-in
       :exit    :root-out
       :actions (recorders log [:root-in :root-out :a-in :a-out :b-in :b-out])
       :on      {:reset :a
                 :same  {:target :same-state}
                 :again {:target :a :reenter? true}}
       :states  {:a {:entry :a-in :exit :a-out :on {:hop :b}}
                 :b {:entry :b-in :exit :b-out}}})
    (rf/dispatch-sync [:rl/guard [:hop]])
    (doseq [[event expected] [[:reset [:b-out :a-in]]
                              [:same  [:a-out :a-in]]
                              [:again [:a-out :a-in]]]]
      (reset! log [])
      (rf/dispatch-sync [:rl/guard [event]])
      (is (= expected @log) (str "the root-declared " event " transition")))))

(deftest parallel-root-exit-follows-every-region-at-finality
  (let [log (atom [])]
    (rf/reg-machine :rl/par-final (parallel-machine log {}))
    (rf/dispatch-sync [:rl/par-final [:rf.machine/start]])
    (rf/dispatch-sync [:rl/par-final [:fin]])
    (is (nil? (snapshot :rl/par-final)) "all regions final with no :on-done — the machine finishes")
    (is (= [:root-in :x1-in :y1-in :x1-out :x2-in :y1-out :y2-in :x2-out :y2-out :root-out] @log))))

(deftest parallel-root-with-on-done-rests-until-destroyed
  (let [log (atom [])]
    (rf/reg-machine :rl/par-rest
      (parallel-machine log {:on-done {:action (fn [_] nil)}}))
    (rf/dispatch-sync [:rl/par-rest [:rf.machine/start]])
    (rf/dispatch-sync [:rl/par-rest [:fin]])
    (is (not-any? #{:root-out} @log) "an :on-done keeps the all-final machine alive, root :exit unrun")
    (kill! :rl/par-rest)
    (is (= :root-out (peek @log)) "destroying it runs the root :exit last")))

(deftest hydrated-snapshot-does-not-rerun-root-entry
  (let [log (atom [])]
    (rf/reg-machine :rl/hydrated (flat-machine log))
    (rf/dispatch-sync [:rl/hydrated [:rf.machine/start]])
    (let [server-rt (rf.machines.test-support/runtime-db)]
      ;; A client that never ran the machine receives the server's runtime-db.
      (rf.frame/swap-runtime-db! :rf/default (constantly {}))
      (reset! log [])
      (rf/reg-event :rl/hydrate (fn [_ _] {:rf.db/runtime server-rt}))
      (rf/dispatch-sync [:rl/hydrate])
      (rf/dispatch-sync [:rl/hydrated [:hop]])
      (is (= [:a-out :b-in] @log) "the next event is handled without re-running root :entry")
      (is (= #{:whole} (:tags (snapshot :rl/hydrated))) "the root's :tags join the union"))))
