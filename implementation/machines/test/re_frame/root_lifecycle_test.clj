(ns re-frame.root-lifecycle-test
  "The machine ROOT honours `:entry`, `:exit` and `:tags`, per Spec 005
  §State nodes (the machine root).

  - Root `:entry` runs ONCE at birth, after `:data` is seeded and before the
    initial leaf cascade, on the eager `[:rf.machine/start]` and the lazy
    first-event paths alike, for a singleton and a spawned actor.
  - Root `:exit` runs ONCE at teardown, AFTER the leaf-to-root exit cascade:
    on whole-machine finality and on destroy.
  - Root `:tags` join the snapshot's tag union.
  - The root NEVER exits or enters on a transition — a root `:on` target, a
    root `:same-state` target and a root `:reenter? true` target all leave
    the root's `:entry` / `:exit` unrun.
  - A parallel root runs its `:entry` before every region's entry and its
    `:exit` after every region's exit; a parallel root with an `:on-done`
    rests in its all-final configuration without running its `:exit`.
  - A hydrated snapshot is not re-born, so root `:entry` does not re-run.

  Live runtime (plain-atom substrate); the callbacks record into an atom so
  teardown order stays observable after the snapshot is gone."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
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
  "A flat machine declaring root `:entry` / `:exit` / `:tags`, with a
  state-level control on every leaf. `:d` is a top-level `:final?` leaf."
  [log]
  {:initial :a
   :entry   :root-in
   :exit    :root-out
   :tags    #{:whole}
   :actions (recorders log [:root-in :root-out :a-in :a-out :b-in :b-out :d-in :d-out])
   :states  {:a {:entry :a-in :exit :a-out :tags #{:at-a} :on {:go :d :hop :b}}
             :b {:entry :b-in :exit :b-out :on {:back :a}}
             :d {:entry :d-in :exit :d-out :final? true}}})

(defn- parallel-machine
  "A parallel machine declaring root `:entry` / `:exit` / `:tags`. Each region
  reaches a `:final?` leaf on `:fin`."
  [log extra]
  (merge
    {:type    :parallel
     :entry   :root-in
     :exit    :root-out
     :tags    #{:whole}
     :actions (recorders log [:root-in :root-out :x1-in :x1-out :x2-in :x2-out
                              :y1-in :y1-out :y2-in :y2-out])
     :regions {:x {:initial :x1
                   :states  {:x1 {:entry :x1-in :exit :x1-out :tags #{:at-x1} :on {:fin :x2}}
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

;; ---- flat root: birth ------------------------------------------------------

(deftest flat-root-entry-runs-first-at-eager-birth
  (let [log (atom [])]
    (rf/reg-machine :rl/eager (flat-machine log))
    (rf/dispatch-sync [:rl/eager [:rf.machine/start]])
    (is (= [:root-in :a-in] @log)
        "root :entry runs once, before the initial leaf's :entry")
    (is (= #{:whole :at-a} (:tags (snapshot :rl/eager)))
        "the root's :tags join the leaf's in the snapshot's union")))

(deftest flat-root-entry-runs-first-at-lazy-birth
  (let [log (atom [])]
    (rf/reg-machine :rl/lazy (flat-machine log))
    (rf/dispatch-sync [:rl/lazy [:hop]])
    (is (= [:root-in :a-in :a-out :b-in] @log)
        "the first event boots the machine, root :entry first, then handles the event")
    (is (= #{:whole} (:tags (snapshot :rl/lazy)))
        "the root tag stays in the union after the leaf that carried a tag exits")))

(deftest root-entry-data-reaches-the-initial-leaf
  (testing "root :entry sees the seeded :data, and the initial leaf's :entry sees root :entry's write"
    (rf/reg-machine :rl/data
      {:initial :a
       :data    {:trail [:seed]}
       :entry   (fn [{:keys [data]}] {:data (update data :trail conj :root)})
       :states  {:a {:entry (fn [{:keys [data]}] {:data (update data :trail conj :a)})}}})
    (rf/dispatch-sync [:rl/data [:rf.machine/start]])
    (is (= [:seed :root :a] (get-in (snapshot :rl/data) [:data :trail])))))

;; ---- flat root: teardown ---------------------------------------------------

(deftest flat-root-exit-runs-last-at-finality
  (let [log (atom [])]
    (rf/reg-machine :rl/final (flat-machine log))
    (rf/dispatch-sync [:rl/final [:rf.machine/start]])
    (rf/dispatch-sync [:rl/final [:go]])
    (is (= [:root-in :a-in :a-out :d-in :d-out :root-out] @log)
        "reaching the top-level :final? leaf runs the leaf's :exit, then the root's")
    (is (nil? (snapshot :rl/final)) "the finished machine is torn down")))

(deftest flat-root-exit-runs-last-on-destroy
  (let [log (atom [])]
    (rf/reg-machine :rl/destroy (flat-machine log))
    (rf/dispatch-sync [:rl/destroy [:rf.machine/start]])
    (kill! :rl/destroy)
    (is (= [:root-in :a-in :a-out :root-out] @log)
        "an explicit destroy runs the active leaf's :exit, then the root's")
    (is (nil? (snapshot :rl/destroy)))))

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

;; ---- spawned actors --------------------------------------------------------

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
    (is (= [:root-in :w-in] @log) "the spawned child's birth runs its root :entry first")
    (rf/dispatch-sync [:rl/parent [:stop]])
    (is (= [:root-in :w-in :w-out :root-out] @log)
        "the parent's exit destroys the child: leaf :exit, then root :exit")))

;; ---- phases and the birth cascade ------------------------------------------

(deftest root-actions-carry-the-birth-and-teardown-phases
  (let [log (atom [])]
    (rf/reg-machine :rl/phases (flat-machine log))
    ;; A lazy birth: the first event's `:rf.machine/transition` trace carries
    ;; the birth cascade ahead of the event's own.
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
             rows)
          "root :entry runs in the birth phase, root :exit in the teardown phase")
      (is (= [{:kind :entry :state [] :region nil :action :root-in}
              {:kind :entry :state [:a] :region nil :action :a-in}]
             (subvec steps 0 2))
          "the birth cascade opens with the root :entry step at the empty path"))))

;; ---- the root never exits or enters on a transition ------------------------

(deftest root-entry-and-exit-never-run-on-a-transition
  (let [log (atom [])]
    (rf/reg-machine :rl/guard
      {:initial :a
       :entry   :root-in
       :exit    :root-out
       :actions (recorders log [:root-in :root-out :a-in :a-out :b-in :b-out])
       :on      {:reset  :a
                 :same   {:target :same-state}
                 :again  {:target :a :reenter? true}}
       :states  {:a {:entry :a-in :exit :a-out :on {:hop :b}}
                 :b {:entry :b-in :exit :b-out}}})
    (rf/dispatch-sync [:rl/guard [:rf.machine/start]])
    (reset! log [])
    (testing "an ordinary child transition"
      (rf/dispatch-sync [:rl/guard [:hop]])
      (is (= [:a-out :b-in] @log)))
    (testing "a root :on target"
      (reset! log [])
      (rf/dispatch-sync [:rl/guard [:reset]])
      (is (= [:b-out :a-in] @log)))
    (testing "a root :on target fired at that target"
      (reset! log [])
      (rf/dispatch-sync [:rl/guard [:reset]])
      (is (= [:a-out :a-in] @log)))
    (testing "a root :same-state target"
      (reset! log [])
      (rf/dispatch-sync [:rl/guard [:same]])
      (is (= [:a-out :a-in] @log)))
    (testing "a root :reenter? true target"
      (reset! log [])
      (rf/dispatch-sync [:rl/guard [:again]])
      (is (= [:a-out :a-in] @log)))
    (is (= :a (:state (snapshot :rl/guard))))))

;; ---- parallel root ---------------------------------------------------------

(deftest parallel-root-entry-precedes-every-region
  (let [log (atom [])]
    (rf/reg-machine :rl/par (parallel-machine log {}))
    (rf/dispatch-sync [:rl/par [:rf.machine/start]])
    (is (= [:root-in :x1-in :y1-in] @log)
        "root :entry first, then each region's initial entry in declaration order")
    (is (= #{:whole :at-x1} (:tags (snapshot :rl/par)))
        "the root's :tags join the regions' union")))

(deftest parallel-root-exit-follows-every-region-on-destroy
  (let [log (atom [])]
    (rf/reg-machine :rl/par-destroy (parallel-machine log {}))
    (rf/dispatch-sync [:rl/par-destroy [:rf.machine/start]])
    (kill! :rl/par-destroy)
    (is (= [:root-in :x1-in :y1-in :x1-out :y1-out :root-out] @log))))

(deftest parallel-root-exit-follows-every-region-at-finality
  (let [log (atom [])]
    (rf/reg-machine :rl/par-final (parallel-machine log {}))
    (rf/dispatch-sync [:rl/par-final [:rf.machine/start]])
    (rf/dispatch-sync [:rl/par-final [:fin]])
    (is (nil? (snapshot :rl/par-final)) "all regions final with no :on-done — the machine finishes")
    (is (= [:x2-out :y2-out :root-out] (vec (take-last 3 @log)))
        "each region's final leaf exits, then the root")
    (is (= 1 (count (filter #{:root-out} @log))))))

(deftest parallel-root-with-on-done-rests-until-destroyed
  (let [log (atom [])]
    (rf/reg-machine :rl/par-rest
      (parallel-machine log {:on-done {:action (fn [_] nil)}}))
    (rf/dispatch-sync [:rl/par-rest [:rf.machine/start]])
    (rf/dispatch-sync [:rl/par-rest [:fin]])
    (is (some? (snapshot :rl/par-rest)) "an :on-done keeps the all-final machine alive")
    (is (not-any? #{:root-out} @log) "so the root :exit has not run")
    (kill! :rl/par-rest)
    (is (= :root-out (peek @log)) "destroying it runs the root :exit last")))

;; ---- hydration -------------------------------------------------------------

(deftest hydrated-snapshot-does-not-rerun-root-entry
  (let [log (atom [])]
    (rf/reg-machine :rl/hydrated (flat-machine log))
    (rf/dispatch-sync [:rl/hydrated [:rf.machine/start]])
    (let [server-rt (rf.machines.test-support/runtime-db)]
      ;; A client that has never run the machine, then receives the server's
      ;; runtime-db through an ordinary runtime-db effect.
      (rf.frame/swap-runtime-db! :rf/default (constantly {}))
      (reset! log [])
      (rf/reg-event :rl/hydrate (fn [_ _] {:rf.db/runtime server-rt}))
      (rf/dispatch-sync [:rl/hydrate])
      (is (= #{:whole :at-a} (:tags (snapshot :rl/hydrated)))
          "the hydrated snapshot carries the root tag")
      (rf/dispatch-sync [:rl/hydrated [:hop]])
      (is (= [:a-out :b-in] @log)
          "the next event is handled without re-running root :entry")
      (is (= #{:whole} (:tags (snapshot :rl/hydrated)))))))
