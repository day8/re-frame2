(ns re-frame.spawn-all-test
  "Per Spec 005 §Spawn-and-join via :spawn-all. Verifies the first-class
  `:spawn-all` spawn-and-join surface that gives parallel-region
  state-machines a single declarative shape.

  The test scenarios cover the CLOSED two-member join grammar
  (:all default + :any — rf2-w8gxxz cut {:n N} / {:fn pred} /
  :cancel-on-decision?), unconditional sibling cancellation on the join
  decision, and registration-time validation (including that a removed
  mode is now REJECTED as an unknown join spec).

  Children dispatch their completion via [<parent-id> [:on-child-done
  <child-id> & extra]] (or :on-child-error). The runtime intercepts
  these at the parent's make-machine-handler boundary and updates
  the join-state at [:rf.runtime/machines :spawned <parent> <invoke-id>] in app-db.
  When the join condition resolves, the runtime fires the parent
  join event (:on-all-complete / :on-some-complete / :on-any-failed)
  and (by default) cancels surviving siblings via :rf.machine/destroy.

  All tests run on the JVM through the plain-atom substrate."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace.tooling :as trace-tooling]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; runtime-db / snapshot lookup via the shared machines test-support
;; — no hardcoded `[:rf.runtime/machines …]` path.
(def ^:private frame-db mtest/runtime-db)
(def ^:private snapshot mtest/snapshot)

;; ---- common child machine -------------------------------------------------
;;
;; Trivial child that, on receiving :go, transitions to :done (which
;; dispatches :on-child-done back to the parent). On :fail, transitions
;; to :failed (which dispatches :on-child-error back). The parent-id
;; the child dispatches into is supplied via :data — we pre-populate
;; it via :start [:set-id <id>] from the parent's :spawn-all entry.

(defn- mk-child
  "Return a child spec that dispatches `done-event-kw` /
  `error-event-kw` back to `parent-id` carrying its own :id from :data."
  [parent-id done-event-kw error-event-kw]
  {:initial :running
   :data    {:id nil}
   :actions {:dispatch-done
             (fn [{data :data}]
               {:fx [[:dispatch [parent-id [done-event-kw (:id data)]]]]})
             :dispatch-error
             (fn [{data :data}]
               {:fx [[:dispatch [parent-id [error-event-kw (:id data)]]]]})
             :record-id
             (fn [{data :data ev :event}]
               {:data (assoc data :id (second ev))})}
   :states
   {:running {:on {:set-id {:action :record-id}
                   :go     {:target :done   :action :dispatch-done}
                   :fail   {:target :failed :action :dispatch-error}}}
    :done   {}
    :failed {}}})

;; ---- :join :all — all children complete fires :on-all-complete ----------

(deftest join-all-fires-on-all-complete
  (testing "all N children completing fires :on-all-complete"
    (let [child  (mk-child :sup/all :asset/loaded :asset/failed)
          parent {:initial :idle
                  :states
                  {:idle      {:on {:start :hydrating}}
                   :hydrating
                   {:spawn-all
                    {:children         [{:id :a :machine-id :child/a :start [:set-id :a]}
                                        {:id :b :machine-id :child/b :start [:set-id :b]}
                                        {:id :c :machine-id :child/c :start [:set-id :c]}]
                     :join             :all
                     :on-child-done    :asset/loaded
                     :on-child-error   :asset/failed
                     :on-all-complete  [:hydrate/done]
                     :on-any-failed    [:hydrate/failed]}
                    :on    {:hydrate/done   :ready
                            :hydrate/failed :error}}
                   :ready  {}
                   :error  {}}}]
      (rf/reg-machine :child/a child)
      (rf/reg-machine :child/b child)
      (rf/reg-machine :child/c child)
      (rf/reg-machine :sup/all parent)
      (rf/dispatch-sync [:sup/all [:start]])
      (let [db     (frame-db)
            jstate (get-in db [:rf.runtime/machines :spawned :sup/all [:hydrating]])]
        (is (map? jstate) "join-state seeded under [:rf.runtime/machines :spawned :sup/all [:hydrating]]")
        (is (= #{:a :b :c} (set (keys (:children jstate)))))
        (is (= #{} (:done jstate)))
        (is (= #{} (:failed jstate)))
        (is (false? (:resolved? jstate)))
        (is (some? (get-in db [:rf.runtime/machines :snapshots (get-in jstate [:children :a])])))
        (is (some? (get-in db [:rf.runtime/machines :snapshots (get-in jstate [:children :b])])))
        (is (some? (get-in db [:rf.runtime/machines :snapshots (get-in jstate [:children :c])]))))
      (let [jstate (get-in (frame-db) [:rf.runtime/machines :spawned :sup/all [:hydrating]])
            ids    (:children jstate)]
        (rf/dispatch-sync [(:a ids) [:go]])
        (rf/dispatch-sync [(:b ids) [:go]])
        (rf/dispatch-sync [(:c ids) [:go]]))
      (is (= :ready (:state (snapshot :sup/all)))
          "parent transitioned to :ready via :on-all-complete"))))

;; ---- :join :all with one child failing fires :on-any-failed --------------

(deftest join-all-with-one-failure-fires-on-any-failed-and-cancels
  (testing "one child failing fires :on-any-failed and cancels surviving siblings"
    (let [child  (mk-child :sup/fail-test :asset/loaded :asset/failed)
          parent {:initial :idle
                  :states
                  {:idle      {:on {:start :hydrating}}
                   :hydrating
                   {:spawn-all
                    {:children         [{:id :a :machine-id :child/fa :start [:set-id :a]}
                                        {:id :b :machine-id :child/fb :start [:set-id :b]}
                                        {:id :c :machine-id :child/fc :start [:set-id :c]}]
                     :join             :all
                     :on-child-done    :asset/loaded
                     :on-child-error   :asset/failed
                     :on-all-complete  [:hydrate/done]
                     :on-any-failed    [:hydrate/failed]}
                    :on    {:hydrate/done   :ready
                            :hydrate/failed :error}}
                   :ready  {}
                   :error  {}}}]
      (rf/reg-machine :child/fa child)
      (rf/reg-machine :child/fb child)
      (rf/reg-machine :child/fc child)
      (rf/reg-machine :sup/fail-test parent)
      (rf/dispatch-sync [:sup/fail-test [:start]])
      (let [jstate (get-in (frame-db) [:rf.runtime/machines :spawned :sup/fail-test [:hydrating]])]
        (is (= 3 (count (:children jstate)))))
      (let [jstate (get-in (frame-db) [:rf.runtime/machines :spawned :sup/fail-test [:hydrating]])
            ids    (:children jstate)
            a-id   (:a ids)
            b-id   (:b ids)
            c-id   (:c ids)]
        (rf/dispatch-sync [a-id [:fail]])
        (is (= :error (:state (snapshot :sup/fail-test))))
        (is (nil? (get-in (frame-db) [:rf.runtime/machines :snapshots a-id])))
        (is (nil? (get-in (frame-db) [:rf.runtime/machines :snapshots b-id])))
        (is (nil? (get-in (frame-db) [:rf.runtime/machines :snapshots c-id])))
        (is (nil? (get-in (frame-db) [:rf.runtime/machines :spawned :sup/fail-test [:hydrating]])))))))

;; ---- :join :any fires :on-some-complete on first child ------------------

(deftest join-any-fires-on-first-child
  (testing ":join :any fires :on-some-complete after the first :go"
    (let [child  (mk-child :sup/any-test :done :failed)
          parent {:initial :idle
                  :states
                  {:idle   {:on {:start :racing}}
                   :racing
                   {:spawn-all
                    {:children         [{:id :a :machine-id :child/aa :start [:set-id :a]}
                                        {:id :b :machine-id :child/ab :start [:set-id :b]}
                                        {:id :c :machine-id :child/ac :start [:set-id :c]}]
                     :join             :any
                     :on-child-done    :done
                     :on-child-error   :failed
                     :on-some-complete [:race/won]}
                    :on    {:race/won :winner}}
                   :winner {}}}]
      (doseq [k [:child/aa :child/ab :child/ac]]
        (rf/reg-machine k child))
      (rf/reg-machine :sup/any-test parent)
      (rf/dispatch-sync [:sup/any-test [:start]])
      (let [jstate (get-in (frame-db) [:rf.runtime/machines :spawned :sup/any-test [:racing]])
            ids    (:children jstate)]
        (rf/dispatch-sync [(:b ids) [:go]])
        (is (= :winner (:state (snapshot :sup/any-test))))
        (is (nil? (get-in (frame-db) [:rf.runtime/machines :snapshots (:a ids)])))
        (is (nil? (get-in (frame-db) [:rf.runtime/machines :snapshots (:c ids)])))))))

;; ---- unsatisfiable join warning ------------------------------------------
;;
;; An :all / :any join with NO :on-any-failed silently hangs forever once
;; enough children fail that the success condition is unreachable. The
;; runtime emits a one-shot :rf.warning/spawn-all-join-unsatisfiable on the
;; fold that first makes the join unsatisfiable.

(defn- record-warnings!
  "Register a listener capturing :rf.warning/spawn-all-join-unsatisfiable
  traces into an atom; returns [atom unregister-fn]."
  [k]
  (let [a (atom [])]
    (trace-tooling/register-listener!
      k (fn [ev]
          (when (= :rf.warning/spawn-all-join-unsatisfiable (:operation ev))
            (swap! a conj ev))))
    [a #(trace-tooling/unregister-listener! k)]))

(deftest all-join-unsatisfiable-after-failure-warns
  (testing "C5: an :all join with no :on-any-failed warns once when a failure
            makes all-done unreachable (and the parent stays hung)"
    (let [[warns unregister!] (record-warnings! ::unsat-n)
          child  (mk-child :sup/unsat-n :done :failed)
          parent {:initial :idle
                  :states
                  {:idle    {:on {:start :working}}
                   :working
                   ;; 3 children, need ALL done, NO :on-any-failed.
                   {:spawn-all
                    {:children         [{:id :a :machine-id :child/un-a :start [:set-id :a]}
                                        {:id :b :machine-id :child/un-b :start [:set-id :b]}
                                        {:id :c :machine-id :child/un-c :start [:set-id :c]}]
                     :join             :all
                     :on-child-done    :done
                     :on-child-error   :failed
                     :on-all-complete  [:phase/done]}
                    :on    {:phase/done :ready}}
                   :ready {}}}]
      (try
        (doseq [k [:child/un-a :child/un-b :child/un-c]]
          (rf/reg-machine k child))
        (rf/reg-machine :sup/unsat-n parent)
        (rf/dispatch-sync [:sup/unsat-n [:start]])
        (let [ids (:children (get-in (frame-db) [:rf.runtime/machines :spawned :sup/unsat-n [:working]]))]
          ;; one done (need all 3 — still satisfiable, no failures yet)
          (rf/dispatch-sync [(:a ids) [:go]])
          (is (empty? @warns) "still satisfiable after 1 done — no warning yet")
          ;; one fail → :all can never complete (a failed child never joins :done) → UNSAT
          (rf/dispatch-sync [(:b ids) [:fail]])
          (is (= 1 (count @warns))
              "exactly one :rf.warning/spawn-all-join-unsatisfiable on the fold that broke it")
          ;; a further fail must NOT re-warn (one-shot)
          (rf/dispatch-sync [(:c ids) [:fail]])
          (is (= 1 (count @warns)) "no duplicate warning on subsequent failures")
          (is (= :working (:state (snapshot :sup/unsat-n)))
              "the parent stays hung on the :spawn-all state (the documented footgun)")
          (let [w (first @warns)]
            (is (= :sup/unsat-n (get-in w [:tags :actor-id])))
            (is (= :all (get-in w [:tags :join])))))
        (finally (unregister!))))))

(deftest all-join-with-on-any-failed-does-not-warn
  (testing "C5 control: a join WITH :on-any-failed resolves on the first
            failure, so the unsatisfiable warning never fires"
    (let [[warns unregister!] (record-warnings! ::unsat-guarded)
          child  (mk-child :sup/guarded-n :done :failed)
          parent {:initial :idle
                  :states
                  {:idle    {:on {:start :working}}
                   :working
                   {:spawn-all
                    {:children         [{:id :a :machine-id :child/gn-a :start [:set-id :a]}
                                        {:id :b :machine-id :child/gn-b :start [:set-id :b]}
                                        {:id :c :machine-id :child/gn-c :start [:set-id :c]}]
                     :join             :all
                     :on-child-done    :done
                     :on-child-error   :failed
                     :on-all-complete  [:phase/done]
                     :on-any-failed    [:phase/failed]}
                    :on    {:phase/done   :ready
                            :phase/failed :error}}
                   :ready {}
                   :error {}}}]
      (try
        (doseq [k [:child/gn-a :child/gn-b :child/gn-c]]
          (rf/reg-machine k child))
        (rf/reg-machine :sup/guarded-n parent)
        (rf/dispatch-sync [:sup/guarded-n [:start]])
        (let [ids (:children (get-in (frame-db) [:rf.runtime/machines :spawned :sup/guarded-n [:working]]))]
          (rf/dispatch-sync [(:a ids) [:fail]])
          (is (empty? @warns)
              ":on-any-failed resolved the join on the first failure — no unsat warning")
          (is (= :error (:state (snapshot :sup/guarded-n)))
              "the join resolved via :on-any-failed"))
        (finally (unregister!))))))

;; ---- registration-time validation ----------------------------------------

(deftest registration-time-rejects-bad-shape
  (testing ":spawn-all with no :id on a child — rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"machine-spawn-all-bad-shape"
          (rf/reg-machine :bad/no-id
                          {:initial :s
                           :states
                           {:s {:spawn-all {:children        [{:machine-id :foo}]
                                             :on-child-done   :done
                                             :on-child-error  :failed
                                             :on-all-complete [:done]}}}}))))
  (testing ":spawn-all with duplicate child ids — rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"machine-spawn-all-duplicate-id"
          (rf/reg-machine :bad/dup-id
                          {:initial :s
                           :states
                           {:s {:spawn-all {:children        [{:id :x :machine-id :foo}
                                                                {:id :x :machine-id :bar}]
                                             :on-child-done   :done
                                             :on-child-error  :failed
                                             :on-all-complete [:done]}}}}))))
  (testing ":spawn + :spawn-all on same state — rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"machine-spawn-all-with-spawn"
          (rf/reg-machine :bad/both
                          {:initial :s
                           :states
                           {:s {:spawn     {:machine-id :foo}
                                :spawn-all {:children        [{:id :x :machine-id :bar}]
                                             :on-child-done   :done
                                             :on-child-error  :failed
                                             :on-all-complete [:done]}}}}))))
  (testing ":spawn-all with :join :all but no :on-all-complete — rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"machine-spawn-all-bad-shape"
          (rf/reg-machine :bad/no-on-all
                          {:initial :s
                           :states
                           {:s {:spawn-all {:children        [{:id :x :machine-id :foo}]
                                             :on-child-done   :done
                                             :on-child-error  :failed}}}}))))
  (testing ":spawn-all with :join :any but no :on-some-complete — rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"machine-spawn-all-bad-shape"
          (rf/reg-machine :bad/no-on-some
                          {:initial :s
                           :states
                           {:s {:spawn-all {:children       [{:id :x :machine-id :foo}]
                                             :join           :any
                                             :on-child-done  :done
                                             :on-child-error :failed}}}}))))
  ;; rf2-w8gxxz — the join grammar is a CLOSED two-member enum (:all + :any).
  ;; The removed modes ({:n N}, {:fn pred}) and the removed
  ;; :cancel-on-decision? key are now REJECTED as an unknown join spec /
  ;; unknown node key.
  (testing "rf2-w8gxxz: :spawn-all with the removed :join {:n 2} mode — rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"machine-spawn-all-bad-shape"
          (rf/reg-machine :bad/join-n
                          {:initial :s
                           :states
                           {:s {:spawn-all {:children         [{:id :x :machine-id :foo}]
                                             :join             {:n 2}
                                             :on-child-done    :done
                                             :on-child-error   :failed
                                             :on-some-complete [:some]}}}}))))
  (testing "rf2-w8gxxz: :spawn-all with the removed :join {:fn pred} mode — rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"machine-spawn-all-bad-shape"
          (rf/reg-machine :bad/join-fn
                          {:initial :s
                           :states
                           {:s {:spawn-all {:children         [{:id :x :machine-id :foo}]
                                             :join             {:fn (fn [_] true)}
                                             :on-child-done    :done
                                             :on-child-error   :failed
                                             :on-some-complete [:some]}}}}))))
  (testing "rf2-w8gxxz: :spawn-all with the removed :cancel-on-decision? key — rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"machine-spawn-all-bad-shape"
          (rf/reg-machine :bad/cancel-off
                          {:initial :s
                           :states
                           {:s {:spawn-all {:children            [{:id :x :machine-id :foo}]
                                             :join                :any
                                             :cancel-on-decision? false
                                             :on-child-done       :done
                                             :on-child-error      :failed
                                             :on-some-complete    [:some]}}}}))))
  ;; A :spawn-all child must declare EXACTLY ONE of :machine-id /
  ;; :definition (XOR): a child carrying BOTH is rejected, as is a child
  ;; carrying NEITHER.
  (testing ":spawn-all child with BOTH :machine-id AND :definition — rejected (XOR)"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"machine-spawn-all-bad-shape"
          (rf/reg-machine :bad/child-both
                          {:initial :s
                           :states
                           {:s {:spawn-all {:children        [{:id         :x
                                                                :machine-id :foo
                                                                :definition {:initial :i
                                                                             :states  {:i {}}}}]
                                             :on-child-done   :done
                                             :on-child-error  :failed
                                             :on-all-complete [:done]}}}}))))
  (testing ":spawn-all child with EXACTLY ONE of :machine-id / :definition — accepted"
    (is (some?
          (rf/reg-machine :ok/child-def
                          {:initial :s
                           :states
                           {:s {:spawn-all {:children        [{:id         :x
                                                                :definition {:initial :i
                                                                             :states  {:i {}}}}]
                                             :on-child-done   :done
                                             :on-child-error  :failed
                                             :on-all-complete [:done]}}}}))
        "an inline-definition :spawn-all child (no :machine-id) registers cleanly")))

;; ---- single :spawn :machine-id xor :definition ---------------------------
;;
;; `validate-machine!` enforces a single-:spawn XOR check at registration: a
;; :spawn declaring BOTH :machine-id and :definition (ambiguous: inline init
;; while :rf/machine-type stamps the registered id → type mismatch on
;; restore) or NEITHER (nothing to instantiate → late actor-id allocation
;; failure) fails-closed with :rf.error/machine-spawn-bad-shape. XState-v5
;; alignment: `invoke` takes exactly one source (a referenced/registered
;; actor logic OR an inline one), never both/neither.

(deftest single-spawn-id-xor-definition-validated-at-registration
  (testing "a single :spawn declaring NEITHER :machine-id nor :definition — rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"machine-spawn-bad-shape"
          (rf/reg-machine :spawnxor/neither
                          {:initial :working
                           :states  {:working {:spawn {:on-spawn (fn [_] nil)}}
                                     :done    {}}}))))
  (testing "a single :spawn declaring BOTH :machine-id AND :definition — rejected (XOR)"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"machine-spawn-bad-shape"
          (rf/reg-machine :spawnxor/both
                          {:initial :working
                           :states  {:working {:spawn {:machine-id :some/child
                                                       :definition {:initial :i
                                                                    :states  {:i {}}}}}
                                     :done    {}}}))))
  (testing "a single :spawn declaring EXACTLY ONE — :machine-id alone — accepted"
    (is (some?
          (rf/reg-machine :spawnxor/id-only
                          {:initial :working
                           :states  {:working {:spawn {:machine-id :some/child}}
                                     :done    {}}}))
        "a registered-machine spawn registers cleanly"))
  (testing "a single :spawn declaring EXACTLY ONE — :definition alone — accepted"
    (is (some?
          (rf/reg-machine :spawnxor/def-only
                          {:initial :working
                           :states  {:working {:spawn {:definition {:initial :i
                                                                    :states  {:i {}}}}}
                                     :done    {}}}))
        "an inline-definition spawn registers cleanly"))
  (testing "exactly-one :spawn with :fixed-actor-id / :id-prefix still accepted"
    (is (some?
          (rf/reg-machine :spawnxor/fixed
                          {:initial :working
                           :states  {:working {:spawn {:machine-id     :some/child
                                                       :fixed-actor-id :the-one}}
                                     :done    {}}}))
        "an explicit :fixed-actor-id alongside exactly-one source is fine")
    (is (some?
          (rf/reg-machine :spawnxor/prefix
                          {:initial :working
                           :states  {:working {:spawn {:definition {:initial :i
                                                                    :states  {:i {}}}
                                                       :id-prefix  :worker}}
                                     :done    {}}}))
        "an explicit :id-prefix alongside exactly-one source is fine")))

;; ---- decisive-child payload forwarding -----------------------------------

(defn- mk-child-with-payload
  "Like mk-child but the terminal-state dispatch carries an extra payload
  argument so we can assert it forwards through the join resolution."
  [parent-id done-event-kw error-event-kw payload]
  {:initial :running
   :data    {:id nil}
   :actions {:dispatch-done
             (fn [{data :data}]
               {:fx [[:dispatch [parent-id [done-event-kw (:id data) payload]]]]})
             :dispatch-error
             (fn [{data :data}]
               {:fx [[:dispatch [parent-id [error-event-kw (:id data) payload]]]]})
             :record-id
             (fn [{data :data ev :event}]
               {:data (assoc data :id (second ev))})
             :record-join-event
             (fn [{data :data ev :event}]
               {:data (assoc data :join-event ev)})}
   :states
   {:running {:on {:set-id {:action :record-id}
                   :go     {:target :done   :action :dispatch-done}
                   :fail   {:target :failed :action :dispatch-error}}}
    :done   {}
    :failed {}}})

(deftest decisive-child-payload-forwards-into-join-event
  (testing "the decisive child's & extra is appended onto the resolution event"
    (let [child  (mk-child-with-payload :sup/pay :asset/loaded :asset/failed
                                        {:bytes 4242 :status :ok})
          parent {:initial :idle
                  :actions {:record-payload
                            (fn [{data :data ev :event}]
                              {:data (assoc data :join-event ev)})}
                  :states
                  {:idle      {:on {:start :hydrating}}
                   :hydrating
                   {:spawn-all
                    {:children        [{:id :a :machine-id :child/pa :start [:set-id :a]}]
                     :join            :all
                     :on-child-done   :asset/loaded
                     :on-child-error  :asset/failed
                     :on-all-complete [:hydrate/done]}
                    :on    {:hydrate/done {:target :ready :action :record-payload}}}
                   :ready {}}}]
      (rf/reg-machine :child/pa child)
      (rf/reg-machine :sup/pay parent)
      (rf/dispatch-sync [:sup/pay [:start]])
      (let [ids (get-in (frame-db) [:rf.runtime/machines :spawned :sup/pay [:hydrating] :children])]
        (rf/dispatch-sync [(:a ids) [:go]]))
      (let [snap (snapshot :sup/pay)]
        (is (= :ready (:state snap)) "parent reached :ready")
        ;; The decisive-child payload (:a + {:bytes 4242 :status :ok})
        ;; appears as trailing args on the dispatched join event:
        ;;   [:hydrate/done :a {:bytes 4242 :status :ok}]
        (is (= [:hydrate/done :a {:bytes 4242 :status :ok}]
               (:join-event (:data snap)))
            "resolution event carries decisive child-id and forwarded payload")))))

;; ---- sibling cancellation on the join decision + late-completion ---------
;;
;; intercept-spawn-all-event has a post-resolution branch (the
;; `(:resolved? join-state)` cond arm) that fires when a child-done event
;; arrives AFTER the join already resolved. It emits the
;; :rf.machine.spawn-all/late-completion trace (stale reply; record frozen).
;;
;; rf2-w8gxxz — sibling cancellation on the join decision is now
;; UNCONDITIONAL (the `:cancel-on-decision? false` protocol that let
;; siblings run to completion was cut). When the join resolves, surviving
;; siblings are DESTROYED (the cancellation cascade fires :rf.machine/destroy
;; + :rf.machine.spawn/cancelled-on-join-resolution per survivor) and the
;; record stays frozen at the decisive child.

(deftest join-resolution-cancels-siblings-record-frozen
  (testing "rf2-w8gxxz: when an :any join resolves, the surviving sibling is
            cancelled at resolution and the join-state record stays frozen at
            the decisive child :a"
    (let [traces (atom [])
          child  (mk-child :sup/cancel :asset/loaded :asset/failed)
          parent {:initial :idle
                  :states
                  {:idle      {:on {:start :hydrating}}
                   :hydrating
                   {:spawn-all
                    {:children            [{:id :a :machine-id :child/ca :start [:set-id :a]}
                                           {:id :b :machine-id :child/cb :start [:set-id :b]}]
                     :join                :any
                     :on-child-done       :asset/loaded
                     :on-child-error      :asset/failed
                     :on-some-complete    [:race/won]}}}}]
      (rf/reg-machine :child/ca child)
      (rf/reg-machine :child/cb child)
      (rf/reg-machine :sup/cancel parent)
      (rf/register-listener! :trace ::cancel-cb
                             (fn [ev] (swap! traces conj ev)))
      (try
        (rf/dispatch-sync [:sup/cancel [:start]])
        (let [ids (get-in (frame-db) [:rf.runtime/machines :spawned :sup/cancel [:hydrating] :children])]
          (rf/dispatch-sync [(:a ids) [:go]])
          (let [j (get-in (frame-db) [:rf.runtime/machines :spawned :sup/cancel [:hydrating]])]
            (is (true? (:resolved? j)) ":any resolved on first :go")
            (is (= #{:a} (:done j)) "record holds the decisive child only at resolution")))
        (let [cancel-traces (->> @traces
                                 (filter #(= :rf.machine.spawn/cancelled-on-join-resolution
                                             (:operation %))))]
          (is (pos? (count cancel-traces))
              "surviving sibling :b is cancelled at resolution (unconditional)")
          ;; The record stays frozen at :a — the survivor was destroyed, so
          ;; no late completion arrives to mutate the record.
          (let [j (get-in (frame-db) [:rf.runtime/machines :spawned :sup/cancel [:hydrating]])]
            (is (= #{:a} (:done j))
                "rf2-w8gxxz: the record stays frozen at resolution")))
        (finally
          (rf/unregister-listener! :trace ::cancel-cb))))))

(deftest late-completion-of-known-child-is-stale-record-frozen
  (testing "rf2-w8gxxz: a post-resolution completion of a KNOWN child (the
            :resolved? latch already flipped) traces
            :rf.machine.spawn-all/late-completion with a :stale reply, does
            NOT fold into the record (no :folded? tag), and fires no further
            parent event"
    (let [traces (atom [])
          child  (mk-child :sup/latek :asset/loaded :asset/failed)
          ;; Parent stays in :hydrating across resolution by NOT declaring an
          ;; :on for the resolution event, so the join slot survives and a
          ;; re-dispatch of a known child-id flows through the :resolved?
          ;; branch.
          parent {:initial :idle
                  :states
                  {:idle      {:on {:start :hydrating}}
                   :hydrating
                   {:spawn-all
                    {:children            [{:id :a :machine-id :child/lka :start [:set-id :a]}
                                           {:id :b :machine-id :child/lkb :start [:set-id :b]}]
                     :join                :any
                     :on-child-done       :asset/loaded
                     :on-child-error      :asset/failed
                     :on-some-complete    [:race/won]}}}}]
      (rf/reg-machine :child/lka child)
      (rf/reg-machine :child/lkb child)
      (rf/reg-machine :sup/latek parent)
      (rf/register-listener! :trace ::latek-cb
                             (fn [ev] (swap! traces conj ev)))
      (try
        (rf/dispatch-sync [:sup/latek [:start]])
        (let [ids (get-in (frame-db) [:rf.runtime/machines :spawned :sup/latek [:hydrating] :children])]
          ;; :a resolves the :any join.
          (rf/dispatch-sync [(:a ids) [:go]])
          (let [j (get-in (frame-db) [:rf.runtime/machines :spawned :sup/latek [:hydrating]])]
            (is (true? (:resolved? j)) ":any resolved on first :go")
            (is (= #{:a} (:done j))))
          ;; Re-dispatch a KNOWN child-id's completion AFTER resolution (the
          ;; late straggler). It flows through the :resolved? branch.
          (rf/dispatch-sync [:sup/latek [:asset/loaded :a]])
          (let [j (get-in (frame-db) [:rf.runtime/machines :spawned :sup/latek [:hydrating]])]
            (is (= #{:a} (:done j))
                "rf2-w8gxxz: the record stays frozen — no fold on late completion")
            (is (true? (:resolved? j)) ":resolved? still latched")))
        (let [late-traces (->> @traces
                               (filter #(= :rf.machine.spawn-all/late-completion
                                           (:operation %))))]
          (is (= 1 (count late-traces)) "one late-completion trace fired")
          (let [tags (:tags (first late-traces))]
            (is (= :a (:child-id tags)) "trace carries the late child's id")
            (is (= :stale (:rf.reply/status tags))
                "the late completion is classified stale")
            (is (not (contains? tags :folded?))
                "rf2-w8gxxz: no :folded? tag — the fold machinery was cut")))
        (finally
          (rf/unregister-listener! :trace ::latek-cb))))))

;; ---- forged child-id rejection -------------------------------------------
;;
;; intercept-spawn-all-event must verify the inbound `child-id` is one
;; of the parent's spawned children before mutating the join state.
;; Otherwise a hand-crafted dispatch (typo, copy-paste from a sibling
;; :spawn-all, cascaded event from another parent) silently folds an
;; unknown id into `:done` / `:failed` and can collapse the join early
;; (silent state-machine corruption). The runtime gates this with
;; `:rf.error/machine-spawn-all-bad-child-id` and a no-op fx — the
;; join state is NOT mutated and the join does not resolve on the
;; forged event.
;;
;; Pragmatic security stance: trust the explicit invoker but gate
;; accidents. See ai/findings/machines-security-audit-2026-05-15.md F1.

(defn- mk-inert-child
  "A child that records its parent-id but never auto-dispatches back —
  letting us hand-craft forged `[parent [:on-child-done <bogus>]]`
  events into the parent without race with real child completion."
  []
  {:initial :running
   :data    {:id nil}
   :actions {:record-id
             (fn [{data :data ev :event}]
               {:data (assoc data :id (second ev))})}
   :states
   {:running {:on {:set-id {:action :record-id}}}}})

(defn- collect-traces
  "Run `body-fn` with a trace listener attached; return the collected
  vector of trace envelopes."
  [body-fn]
  (let [traces (atom [])
        cb-key (gensym ::forged-cb)]
    (rf/register-listener! :trace cb-key (fn [ev] (swap! traces conj ev)))
    (try
      (body-fn)
      (finally
        (rf/unregister-listener! :trace cb-key)))
    @traces))

(defn- bad-child-id-error-traces
  [traces]
  (->> traces
       (filter #(= :rf.error/machine-spawn-all-bad-child-id
                   (:operation %)))))

(deftest forged-child-id-with-no-live-children-is-rejected
  (testing "an :on-child-done dispatch carrying a child-id NOT in the
  parent's spawned set emits :rf.error/machine-spawn-all-bad-child-id
  and is a no-op (join state untouched, no resolution)"
    (let [parent {:initial :idle
                  :states
                  {:idle      {:on {:start :hydrating}}
                   :hydrating
                   {:spawn-all
                    {:children        [{:id :a :machine-id :child/forge-a :start [:set-id :a]}
                                       {:id :b :machine-id :child/forge-b :start [:set-id :b]}]
                     :join            :all
                     :on-child-done   :asset/loaded
                     :on-child-error  :asset/failed
                     :on-all-complete [:hydrate/done]
                     :on-any-failed   [:hydrate/failed]}
                    :on    {:hydrate/done   :ready
                            :hydrate/failed :error}}
                   :ready  {}
                   :error  {}}}]
      (rf/reg-machine :child/forge-a (mk-inert-child))
      (rf/reg-machine :child/forge-b (mk-inert-child))
      (rf/reg-machine :sup/forge-none parent)
      (rf/dispatch-sync [:sup/forge-none [:start]])
      (let [pre-jstate (get-in (frame-db) [:rf.runtime/machines :spawned :sup/forge-none [:hydrating]])
            children   (:children pre-jstate)
            _          (is (= #{:a :b} (set (keys children))))
            traces (collect-traces
                    (fn []
                      (rf/dispatch-sync
                        [:sup/forge-none [:asset/loaded :totally-fake-id]])))
            post-jstate (get-in (frame-db) [:rf.runtime/machines :spawned :sup/forge-none [:hydrating]])
            errs (bad-child-id-error-traces traces)]
        (is (= 1 (count errs))
            "exactly one :rf.error/machine-spawn-all-bad-child-id trace fired")
        (let [err  (first errs)
              tags (:tags err)]
          (is (= :sup/forge-none (:actor-id tags)))
          (is (= [:hydrating] (:invoke-id tags)))
          (is (= :totally-fake-id (:child-id tags)))
          (is (= #{:a :b} (:children tags))
              ":children carries the legitimate seed set")
          (is (= :done (:kind tags))
              ":kind carries the resolution-side the forged event aimed at")
          (is (= :event-dropped (:recovery err))
              ":recovery is hoisted to top-level on error envelopes"))
        (is (= pre-jstate post-jstate)
            "join state unchanged: forged id NOT added to :done or :failed")
        (is (= :hydrating (:state (snapshot :sup/forge-none)))
            "parent did NOT resolve on the forged event")))))

(deftest repeated-forged-completions-each-emit-error-and-do-not-resolve
  (testing "repeated forged :on-child-done dispatches each emit error
  traces; the join still does not resolve"
    (let [parent {:initial :idle
                  :states
                  {:idle      {:on {:start :hydrating}}
                   :hydrating
                   {:spawn-all
                    {:children        [{:id :a :machine-id :child/forge-r :start [:set-id :a]}]
                     :join            :all
                     :on-child-done   :asset/loaded
                     :on-child-error  :asset/failed
                     :on-all-complete [:hydrate/done]
                     :on-any-failed   [:hydrate/failed]}
                    :on    {:hydrate/done   :ready
                            :hydrate/failed :error}}
                   :ready  {}
                   :error  {}}}]
      (rf/reg-machine :child/forge-r (mk-inert-child))
      (rf/reg-machine :sup/forge-repeated parent)
      (rf/dispatch-sync [:sup/forge-repeated [:start]])
      (let [traces (collect-traces
                    (fn []
                      (rf/dispatch-sync [:sup/forge-repeated [:asset/loaded :fake-1]])
                      (rf/dispatch-sync [:sup/forge-repeated [:asset/loaded :fake-2]])
                      (rf/dispatch-sync [:sup/forge-repeated [:asset/failed :fake-3]])))
            errs (bad-child-id-error-traces traces)
            jstate (get-in (frame-db) [:rf.runtime/machines :spawned :sup/forge-repeated [:hydrating]])]
        (is (= 3 (count errs))
            "one error trace per forged dispatch")
        (is (= [:fake-1 :fake-2 :fake-3]
               (mapv (comp :child-id :tags) errs))
            "each error trace carries its specific forged child-id")
        (is (= [:done :done :failed]
               (mapv (comp :kind :tags) errs))
            "each error trace carries the resolution-side it aimed at")
        (is (= #{} (:done jstate)))
        (is (= #{} (:failed jstate)))
        (is (false? (:resolved? jstate)))
        (is (= :hydrating (:state (snapshot :sup/forge-repeated))))))))

(deftest sibling-invoke-all-child-id-is-not-counted-into-other-join
  (testing "a child-id legitimate to one parent's :spawn-all is forged
  for ANOTHER parent's :spawn-all and is rejected by the other"
    (let [parent-1 {:initial :idle
                    :states
                    {:idle      {:on {:start :working}}
                     :working
                     {:spawn-all
                      {:children        [{:id :p1-a :machine-id :child/p1a :start [:set-id :p1-a]}]
                       :join            :all
                       :on-child-done   :asset/loaded
                       :on-child-error  :asset/failed
                       :on-all-complete [:done]}}}}
          parent-2 {:initial :idle
                    :states
                    {:idle      {:on {:start :working}}
                     :working
                     {:spawn-all
                      {:children        [{:id :p2-x :machine-id :child/p2x :start [:set-id :p2-x]}]
                       :join            :all
                       :on-child-done   :asset/loaded
                       :on-child-error  :asset/failed
                       :on-all-complete [:done]}}}}]
      (rf/reg-machine :child/p1a (mk-inert-child))
      (rf/reg-machine :child/p2x (mk-inert-child))
      (rf/reg-machine :sup/p1 parent-1)
      (rf/reg-machine :sup/p2 parent-2)
      (rf/dispatch-sync [:sup/p1 [:start]])
      (rf/dispatch-sync [:sup/p2 [:start]])
      ;; Dispatch p2's child-id (:p2-x) into p1's join — it is foreign
      ;; to p1's seeded :children {:p1-a ...} and must be rejected.
      (let [traces (collect-traces
                    (fn []
                      (rf/dispatch-sync [:sup/p1 [:asset/loaded :p2-x]])))
            errs (bad-child-id-error-traces traces)
            p1-j (get-in (frame-db) [:rf.runtime/machines :spawned :sup/p1 [:working]])]
        (is (= 1 (count errs))
            "sibling parent's child-id is forged-for-this-parent and rejected")
        (is (= :p2-x (:child-id (:tags (first errs)))))
        (is (= #{:p1-a} (:children (:tags (first errs))))
            ":children reflects p1's seed, NOT p2's")
        (is (= #{} (:done p1-j))
            "p1's join state untouched by the foreign id")
        (is (false? (:resolved? p1-j)))))))

(deftest legitimate-child-id-flow-not-regressed-by-the-gate
  (testing "the legitimate child-id path still resolves and the gate
  does not emit a bad-child-id error for real children"
    (let [child  (mk-child :sup/gate-ok :asset/loaded :asset/failed)
          parent {:initial :idle
                  :states
                  {:idle      {:on {:start :hydrating}}
                   :hydrating
                   {:spawn-all
                    {:children        [{:id :a :machine-id :child/gate-a :start [:set-id :a]}
                                       {:id :b :machine-id :child/gate-b :start [:set-id :b]}]
                     :join            :all
                     :on-child-done   :asset/loaded
                     :on-child-error  :asset/failed
                     :on-all-complete [:hydrate/done]}
                    :on    {:hydrate/done :ready}}
                   :ready {}}}]
      (rf/reg-machine :child/gate-a child)
      (rf/reg-machine :child/gate-b child)
      (rf/reg-machine :sup/gate-ok parent)
      (let [traces (collect-traces
                    (fn []
                      (rf/dispatch-sync [:sup/gate-ok [:start]])
                      (let [ids (:children (get-in (frame-db)
                                                   [:rf.runtime/machines :spawned :sup/gate-ok
                                                    [:hydrating]]))]
                        (rf/dispatch-sync [(:a ids) [:go]])
                        (rf/dispatch-sync [(:b ids) [:go]]))))
            errs (bad-child-id-error-traces traces)]
        (is (= [] (vec errs))
            "no :rf.error/machine-spawn-all-bad-child-id for legitimate flow")
        (is (= :ready (:state (snapshot :sup/gate-ok)))
            "legitimate :spawn-all resolution still fires :on-all-complete")))))

;; ---- parallel regions reusing the SAME :on-child-done id -----------------
;;
;; Two active parallel regions may legitimately reuse a generic
;; `:on-child-done` event id (`:done`, `:asset/loaded`). `find-active-spawn-all`
;; collects ALL active matches and routes each child completion by which
;; join-state OWNS the arriving child-id, so a completion from one region is
;; never misrouted to a sibling region's join (which would fail its child-id
;; ownership check as forged and leave the correct region hung).

(deftest parallel-regions-sharing-on-child-done-route-by-child-ownership
  (testing "two parallel regions reusing :on-child-done :done route each child completion to the region whose join OWNS it; no bad-child-id, neither region hangs (rf2-w84jv)"
    (rf/reg-machine :rf2-w84jv/r1-child (mk-child :rf2-w84jv/par-parent :done :err))
    (rf/reg-machine :rf2-w84jv/r2-child (mk-child :rf2-w84jv/par-parent :done :err))
    (rf/reg-machine :rf2-w84jv/par-parent
      {:type    :parallel
       :regions {:region-1
                 {:initial :hydrating
                  :states  {:hydrating
                            {:spawn-all
                             {:children        [{:id :r1a :machine-id :rf2-w84jv/r1-child :start [:set-id :r1a]}]
                              :join            :all
                              :on-child-done   :done
                              :on-child-error  :err
                              :on-all-complete [:r1/done]}
                             :on {:r1/done :ready}}
                            :ready {}}}
                 :region-2
                 {:initial :hydrating
                  :states  {:hydrating
                            {:spawn-all
                             {:children        [{:id :r2x :machine-id :rf2-w84jv/r2-child :start [:set-id :r2x]}]
                              :join            :all
                              :on-child-done   :done
                              :on-child-error  :err
                              :on-all-complete [:r2/done]}
                             :on {:r2/done :ready}}
                            :ready {}}}}})
    (rf/dispatch-sync [:rf2-w84jv/par-parent [:rf.machine.spawn/spawned]])
    (let [r1-children (:children (get-in (frame-db) [:rf.runtime/machines :spawned :rf2-w84jv/par-parent [:region-1 :hydrating]]))
          r2-children (:children (get-in (frame-db) [:rf.runtime/machines :spawned :rf2-w84jv/par-parent [:region-2 :hydrating]]))]
      (is (= #{:r1a} (set (keys r1-children))) "region-1 seeded its own join")
      (is (= #{:r2x} (set (keys r2-children))) "region-2 seeded its own join")
      ;; Complete the SECOND region's child first. With the first-match `some`
      ;; this routed to region-1 (whose :children lacks :r2x), flagging forged
      ;; and hanging region-2. Now it routes to region-2 by ownership.
      (let [traces (collect-traces
                     (fn [] (rf/dispatch-sync [(:r2x r2-children) [:go]])))
            errs   (bad-child-id-error-traces traces)]
        (is (= [] (vec errs))
            "no bad-child-id: region-2's child routed to region-2's join by ownership")
        (is (= :ready (get-in (snapshot :rf2-w84jv/par-parent) [:state :region-2]))
            "region-2 resolved its :spawn-all (its child completion was NOT mis-routed to region-1)")
        (is (= :hydrating (get-in (snapshot :rf2-w84jv/par-parent) [:state :region-1]))
            "region-1 is untouched — its own child has not completed yet"))
      ;; Now complete region-1's child — it must resolve region-1 too.
      (let [r1c    (:children (get-in (frame-db) [:rf.runtime/machines :spawned :rf2-w84jv/par-parent [:region-1 :hydrating]]))
            traces (collect-traces
                     (fn [] (rf/dispatch-sync [(:r1a r1c) [:go]])))
            errs   (bad-child-id-error-traces traces)]
        (is (= [] (vec errs)) "no bad-child-id for region-1's own child")
        (is (= :ready (get-in (snapshot :rf2-w84jv/par-parent) [:state :region-1]))
            "region-1 resolved its :spawn-all on its own child completion")))))

(deftest any-failed-trace-carries-reason-payload
  (testing ":rf.machine.spawn-all/any-failed trace carries :reason from decisive failure"
    (let [traces (atom [])
          child  (mk-child-with-payload :sup/fail-payload
                                        :asset/loaded :asset/failed
                                        {:reason :boom :http-status 503})
          parent {:initial :idle
                  :states
                  {:idle      {:on {:start :hydrating}}
                   :hydrating
                   {:spawn-all
                    {:children       [{:id :a :machine-id :child/fpa :start [:set-id :a]}
                                      {:id :b :machine-id :child/fpb :start [:set-id :b]}]
                     :join            :all
                     :on-child-done   :asset/loaded
                     :on-child-error  :asset/failed
                     :on-all-complete [:hydrate/done]
                     :on-any-failed   [:hydrate/failed]}
                    :on    {:hydrate/done   :ready
                            :hydrate/failed :error}}
                   :ready {} :error {}}}]
      (rf/reg-machine :child/fpa child)
      (rf/reg-machine :child/fpb child)
      (rf/reg-machine :sup/fail-payload parent)
      (rf/register-listener! :trace ::any-failed-trace
                             (fn [ev] (swap! traces conj ev)))
      (try
        (rf/dispatch-sync [:sup/fail-payload [:start]])
        (let [ids (get-in (frame-db) [:rf.runtime/machines :spawned :sup/fail-payload [:hydrating] :children])]
          (rf/dispatch-sync [(:a ids) [:fail]]))
        (let [any-failed (->> @traces
                              (filter #(= :rf.machine.spawn-all/any-failed
                                          (:operation %)))
                              first)]
          (is (some? any-failed) ":rf.machine.spawn-all/any-failed trace fired")
          (is (= [{:reason :boom :http-status 503}]
                 (:reason (:tags any-failed)))
              ":reason key on trace carries decisive child's forwarded payload"))
        (finally
          (rf/unregister-listener! :trace ::any-failed-trace))))))
