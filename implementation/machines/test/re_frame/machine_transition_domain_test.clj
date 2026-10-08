(ns re-frame.machine-transition-domain-test
  "A transition's DOMAIN is the node it is DECLARED on (XState
  `getTransitionDomain`), and the machine ROOT is such a node.

  A transition declared on node D, without `:reenter?`, whose target T is a
  PROPER DESCENDANT of D: EVERY active state below D exits (deepest-first), D
  survives, and D's path down to T (then T's `:initial` chain) enters. The
  boundary depends only on WHERE the transition is written, never on which of
  D's children happens to be active. The root's own `:on`, its
  done / spawn-error fallbacks and a parallel root's per-region targets follow
  the same rule; only the birth cascade — which runs from `:state []` and so
  has nothing to exit — stays outside it.

  Each row pins the callback log, the emitted lifecycle fx (projected to
  `[fx-id invoke-id]`) and the per-path `:after` epoch, because a geometry
  that lands on the right `:state` can still skip an `:exit` (leaking a
  spawned child and a live timer) or an `:entry` (never arming a timer) —
  the snapshot alone cannot see either."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.machines :as rf.machines]))

(defn- logs
  "An `:actions` entry that appends `k` to `[:data :log]` when it fires."
  [k]
  (fn [{:keys [data]}] {:data (update data :log (fnil conj []) k)}))

(defn- step
  "Run one pure transition; return the observables every row compares."
  [machine snapshot event]
  (let [{:keys [status snapshot fx]} (rf.machines/machine-transition machine snapshot event)]
    {:status status
     :state  (:state snapshot)
     :log    (get-in snapshot [:data :log])
     :fx     (mapv (fn [[fx-id arg]] [fx-id (:rf/invoke-id arg)]) fx)
     :epoch  (or (get-in snapshot [:data :rf/after-epoch])
                 (get-in snapshot [:data :rf/after-epoch-by-region]))}))

;; `:p` is a top-level compound (initial `:q`). `:q` carries an `:after` and a
;; `:spawn`; `:r` is a compound (initial `:s`, leaves `:s` and `:x`) carrying
;; its own `:after` and `:spawn`.
(def ^:private deep
  {:initial :p
   :data    {:log []}
   :actions {:enter-p (logs :enter-p) :exit-p (logs :exit-p)
             :enter-q (logs :enter-q) :exit-q (logs :exit-q)
             :enter-r (logs :enter-r) :exit-r (logs :exit-r)
             :enter-s (logs :enter-s) :exit-s (logs :exit-s)
             :enter-x (logs :enter-x) :exit-x (logs :exit-x)}
   :on      {:root-to-q {:target [:p :q]}
             :root-same {:target :same-state}}
   :states
   {:p {:initial :q
        :entry   :enter-p
        :exit    :exit-p
        :on      {:to-s {:target [:p :r :s]}
                  :edit {:target [:p :r :s]}}
        :states
        {:q {:entry :enter-q :exit :exit-q
             :after {1000 [:p :r]}
             :spawn {:machine-id :child/x}}
         :r {:initial :s
             :entry   :enter-r
             :exit    :exit-r
             :after   {2000 [:p :q]}
             :spawn   {:machine-id :child/y}
             :on      {:edit {:target [:p :r :s]}}
             :states  {:s {:entry :enter-s :exit :exit-s}
                       :x {:entry :enter-x :exit :exit-x}}}}}}})

(defn- at [state]
  {:state state :data {:log [] :rf/after-epoch {[:p :q] 1 [:p :r] 1}}})

(def ^:private r-restart
  "Lifecycle fx for restarting `[:p :r]`."
  [[:rf.machine/after-cancel [:p :r]]
   [:rf.machine/destroy [:p :r]]
   [:rf.machine/spawn [:p :r]]
   [:rf.machine/after-schedule [:p :r]]])

(deftest compound-declared-deep-target-through-an-active-intermediate
  (testing "declared on :p, target [:p :r :s] while already at [:p :r :s] —
            every active state below :p exits, so :r restarts (its timer
            re-arms, its child is respawned); :p survives"
    (is (= {:status :ok
            :state  [:p :r :s]
            :log    [:exit-s :exit-r :enter-r :enter-s]
            :fx     r-restart
            :epoch  {[:p :q] 1 [:p :r] 2}}
           (step deep (at [:p :r :s]) [:to-s]))))
  (testing "the same transition from the sibling leaf [:p :r :x], off the
            target's branch — the boundary is still :p, so :r restarts"
    (is (= {:status :ok
            :state  [:p :r :s]
            :log    [:exit-x :exit-r :enter-r :enter-s]
            :fx     r-restart
            :epoch  {[:p :q] 1 [:p :r] 2}}
           (step deep (at [:p :r :x]) [:to-s])))))

(deftest declaring-the-event-on-the-intermediate-keeps-it-alive
  ;; :edit is declared on :p AND on :r. Inside :r, deepest-wins picks :r's
  ;; declaration, whose domain is :r — so :r survives and only :x -> :s changes.
  (is (= {:status :ok
          :state  [:p :r :s]
          :log    [:exit-x :enter-s]
          :fx     []
          :epoch  {[:p :q] 1 [:p :r] 1}}
         (step deep (at [:p :r :x]) [:edit]))))

;; ===========================================================================
;; The machine root is a declaring node.
;; ===========================================================================

(def ^:private flat
  {:initial :idle
   :data    {:log []}
   :actions {:enter-idle (logs :enter-idle) :exit-idle (logs :exit-idle)}
   :on      {:reset  :idle
             :reset! {:target :idle :reenter? true}}
   :states  {:idle {:entry :enter-idle
                    :exit  :exit-idle
                    :after {1000 :busy}}
             :busy {}}})

(deftest root-reset-restarts-the-active-leaf
  (testing "root :on {:reset :idle} while AT :idle re-enters :idle — its :exit
            and :entry run and its :after timer restarts; :reenter? on the
            root gives the same result"
    (doseq [event [:reset :reset!]]
      (is (= {:status :ok
              :state  :idle
              :log    [:exit-idle :enter-idle]
              :fx     [[:rf.machine/after-cancel [:idle]]
                       [:rf.machine/after-schedule [:idle]]]
              :epoch  {[:idle] 2}}
             (step flat {:state :idle :data {:log [] :rf/after-epoch {[:idle] 1}}} [event]))
          (str event)))))

(deftest root-target-inside-the-active-top-level-compound-restarts-it
  (testing "root -> [:p :q] while at [:p :q] — every active state below the
            root exits, so :p and :q restart; the root itself never exits"
    (is (= {:status :ok
            :state  [:p :q]
            :log    [:exit-q :exit-p :enter-p :enter-q]
            :fx     [[:rf.machine/after-cancel [:p :q]]
                     [:rf.machine/destroy [:p :q]]
                     [:rf.machine/spawn [:p :q]]
                     [:rf.machine/after-schedule [:p :q]]]
            :epoch  {[:p :q] 2 [:p :r] 1}}
           (step deep (at [:p :q]) [:root-to-q])))))

(deftest root-same-state-re-descends-the-machine-initial
  ;; A root `:same-state` is the SELF row with the root as the declaring node —
  ;; the root survives, every active state below it exits, and the machine's
  ;; own `:initial` chain re-descends. Exiting the active path and entering
  ;; nothing would leave the machine at `:state []`.
  (is (= {:status :ok
          :state  [:p :q]
          :log    [:exit-x :exit-r :exit-p :enter-p :enter-q]
          :fx     [[:rf.machine/after-cancel [:p :r]]
                   [:rf.machine/destroy [:p :r]]
                   [:rf.machine/spawn [:p :q]]
                   [:rf.machine/after-schedule [:p :q]]]
          :epoch  {[:p :q] 2 [:p :r] 2}}
         (step deep (at [:p :r :x]) [:root-same]))))

(def ^:private par
  {:type    :parallel
   :data    {:log []}
   :actions {:enter-a-idle (logs :enter-a-idle) :exit-a-idle (logs :exit-a-idle)
             :enter-b-x    (logs :enter-b-x)    :exit-b-x    (logs :exit-b-x)}
   :on      {:reset-a {:target [:a :idle]}}
   :regions {:a {:initial :idle
                 :states  {:idle {:entry :enter-a-idle
                                  :exit  :exit-a-idle
                                  :after {1000 :busy}}
                           :busy {}}}
             :b {:initial :x
                 :states  {:x {:entry :enter-b-x :exit :exit-b-x}}}}})

(deftest parallel-root-region-target-re-enters-the-named-region
  (testing "parallel root -> [:a :idle] while region :a already rests at :idle
            — :a's :idle re-enters and its per-path epoch bumps (so the
            in-flight timer goes stale); region :b is untouched"
    (is (= {:status :ok
            :state  {:a :idle :b :x}
            :log    [:exit-a-idle :enter-a-idle]
            :fx     [[:rf.machine/after-cancel [:a :idle]]
                     [:rf.machine/after-schedule [:a :idle]]]
            :epoch  {:a {[:idle] 2}}}
           (step par
                 {:state {:a :idle :b :x}
                  :data  {:log [] :rf/after-epoch-by-region {:a {[:idle] 1}}}}
                 [:reset-a])))))
