(ns re-frame.machine-transition-domain-test
  "A transition's DOMAIN is the node it is DECLARED on (XState
  `getTransitionDomain`), and the machine ROOT is such a node.

  A transition declared on node D, without `:reenter?`, whose target T is a
  PROPER DESCENDANT of D: EVERY active state below D exits (deepest-first), D
  survives, and D's path down to T (then T's `:initial` chain) enters. The
  boundary depends only on WHERE the transition is written, never on which of
  D's children happens to be active (rf2-3x7nj.8.1). The root's own `:on`, its
  done / spawn-error fallbacks and a parallel root's per-region targets follow
  the same rule; only the birth cascade — which runs from `:state []` and so
  has nothing to exit — stays outside it (rf2-3x7nj.8.3).

  Each row pins the callback log, the emitted lifecycle fx (projected to
  `[fx-id invoke-id]`) and the per-path `:after` epoch, because a geometry
  that lands on the right `:state` can still skip an `:exit` (leaking a
  spawned child and a live timer) or an `:entry` (never arming a timer) —
  the snapshot alone cannot see either.

  Pure level-1 tests through the public `re-frame.machines/machine-transition`;
  JVM-runnable from arguments alone."
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
;; its own `:after` and `:spawn`. `:z` is a top-level sibling of `:p`.
(def ^:private deep
  {:initial :p
   :data    {:log []}
   :actions {:enter-p (logs :enter-p) :exit-p (logs :exit-p)
             :enter-q (logs :enter-q) :exit-q (logs :exit-q)
             :enter-r (logs :enter-r) :exit-r (logs :exit-r)
             :enter-s (logs :enter-s) :exit-s (logs :exit-s)
             :enter-x (logs :enter-x) :exit-x (logs :exit-x)
             :enter-z (logs :enter-z) :exit-z (logs :exit-z)}
   ;; Root-declared (decl-path []).
   :on      {:root-to-q {:target [:p :q]}
             :root-to-s {:target [:p :r :s]}
             :root-to-z {:target :z}}
   :states
   {:p {:initial :q
        :entry   :enter-p
        :exit    :exit-p
        ;; Declared on the compound :p (decl-path [:p]).
        :on      {:to-s         {:target [:p :r :s]}
                  :to-r         {:target [:p :r]}
                  :to-s-reenter {:target [:p :r :s] :reenter? true}
                  :edit         {:target [:p :r :s]}}
        :states
        {:q {:entry :enter-q :exit :exit-q
             :after {1000 [:p :r]}
             :spawn {:machine-id :child/x}}
         :r {:initial :s
             :entry   :enter-r
             :exit    :exit-r
             :after   {2000 [:p :q]}
             :spawn   {:machine-id :child/y}
             ;; The remedy idiom: the SAME event also declared on the
             ;; intermediate, so deepest-wins keeps :r alive while inside it.
             :on      {:edit {:target [:p :r :s]}}
             :states  {:s {:entry :enter-s :exit :exit-s}
                       :x {:entry :enter-x :exit :exit-x}}}}}
    :z {:entry :enter-z :exit :exit-z}}})

(defn- at [state]
  {:state state :data {:log [] :rf/after-epoch {[:p :q] 1 [:p :r] 1}}})

;; ===========================================================================
;; rf2-3x7nj.8.1 — a transition declared on a compound.
;; ===========================================================================

(def ^:private q-out-r-in
  "Lifecycle fx for leaving `[:p :q]` and entering `[:p :r]`."
  [[:rf.machine/after-cancel [:p :q]]
   [:rf.machine/destroy [:p :q]]
   [:rf.machine/spawn [:p :r]]
   [:rf.machine/after-schedule [:p :r]]])

(def ^:private r-restart
  "Lifecycle fx for restarting `[:p :r]`."
  [[:rf.machine/after-cancel [:p :r]]
   [:rf.machine/destroy [:p :r]]
   [:rf.machine/spawn [:p :r]]
   [:rf.machine/after-schedule [:p :r]]])

(deftest compound-declared-deep-target-off-the-active-branch
  (testing "A: declared on :p, target [:p :r :s] (two levels down, another
            branch) from [:p :q] — :q exits (its timer cancels, its child is
            destroyed) and :r enters before :s (its timer arms, its child
            spawns); :p survives"
    (is (= {:status :ok
            :state  [:p :r :s]
            :log    [:exit-q :enter-r :enter-s]
            :fx     q-out-r-in
            :epoch  {[:p :q] 2 [:p :r] 2}}
           (step deep (at [:p :q]) [:to-s])))))

(deftest compound-declared-deep-target-through-an-active-intermediate
  (testing "B: declared on :p, target [:p :r :s] while already at [:p :r :s]
            — every active state below :p exits, so :r restarts (its timer
            re-arms, its child is respawned); :p survives"
    (is (= {:status :ok
            :state  [:p :r :s]
            :log    [:exit-s :exit-r :enter-r :enter-s]
            :fx     r-restart
            :epoch  {[:p :q] 1 [:p :r] 2}}
           (step deep (at [:p :r :s]) [:to-s]))))
  (testing "C: the same transition from the SIBLING leaf [:p :r :x] — the
            boundary is still :p, so :r restarts as in B"
    (is (= {:status :ok
            :state  [:p :r :s]
            :log    [:exit-x :exit-r :enter-r :enter-s]
            :fx     r-restart
            :epoch  {[:p :q] 1 [:p :r] 2}}
           (step deep (at [:p :r :x]) [:to-s])))))

(deftest compound-declared-depth-1-controls-are-unchanged
  (testing "D: declared on :p, depth-1 target [:p :r] from [:p :q]"
    (is (= {:status :ok
            :state  [:p :r :s]
            :log    [:exit-q :enter-r :enter-s]
            :fx     q-out-r-in
            :epoch  {[:p :q] 2 [:p :r] 2}}
           (step deep (at [:p :q]) [:to-r]))))
  (testing "E: declared on :p, depth-1 target [:p :r] from [:p :r :x]"
    (is (= {:status :ok
            :state  [:p :r :s]
            :log    [:exit-x :exit-r :enter-r :enter-s]
            :fx     r-restart
            :epoch  {[:p :q] 1 [:p :r] 2}}
           (step deep (at [:p :r :x]) [:to-r]))))
  (testing ":reenter? control — declared on :p, target [:p :r :s] with
            :reenter? true from [:p :q]: :p itself exits and re-enters"
    (is (= {:status :ok
            :state  [:p :r :s]
            :log    [:exit-q :exit-p :enter-p :enter-r :enter-s]
            :fx     q-out-r-in
            :epoch  {[:p :q] 2 [:p :r] 2}}
           (step deep (at [:p :q]) [:to-s-reenter])))))

(deftest declaring-the-event-on-the-intermediate-keeps-it-alive
  (testing "the remedy: :edit is declared on :p AND on :r. Inside :r,
            deepest-wins picks :r's declaration, whose domain is :r — so :r
            survives and only :x -> :s changes"
    (is (= {:status :ok
            :state  [:p :r :s]
            :log    [:exit-x :enter-s]
            :fx     []
            :epoch  {[:p :q] 1 [:p :r] 1}}
           (step deep (at [:p :r :x]) [:edit]))))
  (testing "outside :r, :p's declaration covers it, exactly as row A"
    (is (= {:status :ok
            :state  [:p :r :s]
            :log    [:exit-q :enter-r :enter-s]
            :fx     q-out-r-in
            :epoch  {[:p :q] 2 [:p :r] 2}}
           (step deep (at [:p :q]) [:edit])))))

;; ===========================================================================
;; rf2-3x7nj.8.3 — the machine root is a declaring node.
;; ===========================================================================

(def ^:private flat
  {:initial :idle
   :data    {:log []}
   :actions {:enter-idle (logs :enter-idle) :exit-idle (logs :exit-idle)
             :exit-busy  (logs :exit-busy)  :act       (logs :act)}
   :on      {:reset   :idle
             :reset!  {:target :idle :reenter? true}
             :poke    {:action :act}}
   :states  {:idle {:entry :enter-idle
                    :exit  :exit-idle
                    :after {1000 :busy}
                    :on    {:go :busy}}
             :busy {:exit :exit-busy}}})

(defn- flat-at [state]
  {:state state :data {:log [] :rf/after-epoch {[:idle] 1}}})

(deftest root-reset-restarts-the-active-leaf
  (testing "F: root :on {:reset :idle} while AT :idle re-enters :idle — its
            :exit and :entry run and its :after timer restarts"
    (is (= {:status :ok
            :state  :idle
            :log    [:exit-idle :enter-idle]
            :fx     [[:rf.machine/after-cancel [:idle]]
                     [:rf.machine/after-schedule [:idle]]]
            :epoch  {[:idle] 2}}
           (step flat (flat-at :idle) [:reset]))))
  (testing "G control: the same reset from :busy"
    (is (= {:status :ok
            :state  :idle
            :log    [:exit-busy :enter-idle]
            :fx     [[:rf.machine/after-schedule [:idle]]]
            :epoch  {[:idle] 2}}
           (step flat (flat-at :busy) [:reset]))))
  (testing "control: :reenter? true on the root gives the same result as the
            default now does"
    (is (= {:status :ok
            :state  :idle
            :log    [:exit-idle :enter-idle]
            :fx     [[:rf.machine/after-cancel [:idle]]
                     [:rf.machine/after-schedule [:idle]]]
            :epoch  {[:idle] 2}}
           (step flat (flat-at :idle) [:reset!]))))
  (testing "control: a TARGETLESS root :on stays action-only"
    (is (= {:status :ok
            :state  :idle
            :log    [:act]
            :fx     []
            :epoch  {[:idle] 1}}
           (step flat (flat-at :idle) [:poke])))))

(deftest root-target-inside-the-active-top-level-compound-restarts-it
  (testing "H: root -> [:p :q] while at [:p :q] — every active state below
            the root exits, so :p and :q restart; the root itself never exits"
    (is (= {:status :ok
            :state  [:p :q]
            :log    [:exit-q :exit-p :enter-p :enter-q]
            :fx     [[:rf.machine/after-cancel [:p :q]]
                     [:rf.machine/destroy [:p :q]]
                     [:rf.machine/spawn [:p :q]]
                     [:rf.machine/after-schedule [:p :q]]]
            :epoch  {[:p :q] 2 [:p :r] 1}}
           (step deep (at [:p :q]) [:root-to-q]))))
  (testing "I: root -> [:p :r :s] from [:p :q] — :p restarts on the way"
    (is (= {:status :ok
            :state  [:p :r :s]
            :log    [:exit-q :exit-p :enter-p :enter-r :enter-s]
            :fx     q-out-r-in
            :epoch  {[:p :q] 2 [:p :r] 2}}
           (step deep (at [:p :q]) [:root-to-s]))))
  (testing "J control: root -> :z (a disjoint top-level sibling) from [:p :q]"
    (is (= {:status :ok
            :state  :z
            :log    [:exit-q :exit-p :enter-z]
            :fx     [[:rf.machine/after-cancel [:p :q]]
                     [:rf.machine/destroy [:p :q]]]
            :epoch  {[:p :q] 2 [:p :r] 1}}
           (step deep (at [:p :q]) [:root-to-z])))))

(def ^:private par
  {:type    :parallel
   :data    {:log []}
   :actions {:enter-a-idle (logs :enter-a-idle) :exit-a-idle (logs :exit-a-idle)
             :exit-a-busy  (logs :exit-a-busy)
             :enter-b-x    (logs :enter-b-x)    :exit-b-x    (logs :exit-b-x)}
   :on      {:reset-a {:target [:a :idle]}}
   :regions {:a {:initial :idle
                 :states  {:idle {:entry :enter-a-idle
                                  :exit  :exit-a-idle
                                  :after {1000 :busy}}
                           :busy {:exit :exit-a-busy}}}
             :b {:initial :x
                 :states  {:x {:entry :enter-b-x :exit :exit-b-x}}}}})

(defn- par-at [state]
  {:state state :data {:log [] :rf/after-epoch-by-region {:a {[:idle] 1}}}})

(deftest parallel-root-region-target-re-enters-the-named-region
  (testing "K: parallel root -> [:a :idle] while region :a already rests at
            :idle — :a's :idle re-enters and its per-path epoch bumps (so the
            in-flight timer goes stale); region :b is untouched"
    (is (= {:status :ok
            :state  {:a :idle :b :x}
            :log    [:exit-a-idle :enter-a-idle]
            :fx     [[:rf.machine/after-cancel [:a :idle]]
                     [:rf.machine/after-schedule [:a :idle]]]
            :epoch  {:a {[:idle] 2}}}
           (step par (par-at {:a :idle :b :x}) [:reset-a]))))
  (testing "L control: the same root transition while :a is at :busy"
    (is (= {:status :ok
            :state  {:a :idle :b :x}
            :log    [:exit-a-busy :enter-a-idle]
            :fx     [[:rf.machine/after-schedule [:a :idle]]]
            :epoch  {:a {[:idle] 2}}}
           (step par (par-at {:a :busy :b :x}) [:reset-a])))))
